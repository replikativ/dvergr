(ns dvergr.substrate.repository-profile-test
  (:require [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [dvergr.substrate.geschichte :as g]
            [dvergr.substrate.datahike :as sdh]
            [dvergr.substrate.repository-profile :as profile]
            [geschichte.repo :as repo]
            [yggdrasil.adapters.geschichte :as gy]))

(defn- with-store [f]
  (let [cfg {:store {:backend :memory :id (random-uuid)} :schema-flexibility :write
             :crypto-hash? true :keep-history? false :attribute-refs? false}
        scope (str "/tmp/dvergr-profile-" (random-uuid))]
    (with-redefs [g/repository-config (constantly cfg)
                  g/sandbox-repo (fn [] (throw (AssertionError. "Profile must not import sandbox Markdown")))]
      (try (f cfg scope)
           (finally (when (d/database-exists? cfg) (d/delete-database cfg)))))))

(deftest explicit-profiles-require-closed-trusted-policies
  (let [hooks {:initialize! (fn [& _]) :prepare-open! (fn [& _]) :abort-create! (fn [& _])}]
    (is (nil? (profile/resolve! {} nil)))
    (is (= hooks (profile/resolve! {:fixture/v1 hooks} :fixture/v1)))
    (doseq [[profiles id] [[{} :unknown] [{} false] [{} "fixture/v1"]
                           [{:fixture/v1 (dissoc hooks :prepare-open!)} :fixture/v1]
                           [{:fixture/v1 (assoc hooks :initialize! :executable-keyword)} :fixture/v1]
                           [{:fixture/v1 (assoc hooks :extra identity)} :fixture/v1]]]
      (is (thrown? clojure.lang.ExceptionInfo (profile/resolve! profiles id))))))

(deftest new-profile-is-pristine-and-reopened-before-adapter-publication
  (with-store
    (fn [cfg scope]
      (let [events (atom [])
            hooks {:initialize! (fn [conn context]
                                  (swap! events conj [:init (:profile context)])
                                  (is (empty? (d/datoms @conn :eavt)))
                                  (is (empty? (d/schema @conn)))
                                  (repo/init! conn {:name "Profile fixture"})
                                  (repo/write! conn "wiki/start.md" (.getBytes "# Starter\n" "UTF-8")))
                   :prepare-open! (fn [conn context]
                                    (swap! events conj [:open (:profile context)])
                                    (is (= scope (:scope context)))
                                    (is (contains? (repo/worktree conn) "wiki/start.md")))
                   :abort-create! (fn [& _] (swap! events conj :abort))}
            options {:repository-profile :fixture/v1 :repository-profiles {:fixture/v1 hooks}}]
        (g/ensure-repository! scope options)
        (g/ensure-repository! scope options)
        (is (= [[:init :fixture/v1]] @events))
        (dotimes [_ 2]
          (let [system (g/create-system :scope scope :repository-profile :fixture/v1
                                        :repository-profiles {:fixture/v1 hooks})]
            (d/release (gy/connection system))))
        (is (= [[:init :fixture/v1] [:open :fixture/v1] [:open :fixture/v1]] @events))
        (is (d/database-exists? cfg))
        (is (thrown? clojure.lang.ExceptionInfo
                     (g/create-system :scope scope :repository-profile :fixture/v1 :repository-profiles {})))
        (is (thrown? clojure.lang.ExceptionInfo
                     (g/create-system :scope scope :repository-profile :fixture/v1
                                      :repository-profiles {:fixture/v1 (assoc hooks :prepare-open!
                                                                               (fn [& _] (throw (ex-info "Governance unavailable" {}))))})))
        (is (d/database-exists? cfg) "Failed reopen never deletes the store")))))

(deftest failed-unpublished-initialization-cleans-only-after-joined-host-abort
  (doseq [cleanup-fails? [false true]]
    (with-store
      (fn [cfg scope]
        (let [aborted (atom false) fail-cleanup (atom cleanup-fails?) releases (atom 0) deletions (atom 0)
              opened (atom nil) initializations (atom 0)
              release d/release delete sdh/delete-database!
              hooks {:initialize! (fn [conn _]
                                    (swap! initializations inc)
                                    (reset! opened conn)
                                    (repo/init! conn {:name "Partial"})
                                    (throw (ex-info "Initialization failed" {})))
                     :prepare-open! (fn [& _] (throw (AssertionError. "Failed init must not open")))
                     :abort-create! (fn [conn _]
                                      (is (identical? @opened conn))
                                      (reset! aborted true)
                                      (is (d/database-exists? cfg))
                                      (when @fail-cleanup (throw (ex-info "Cleanup failed" {}))))}
              attempt (fn [] (try (g/ensure-repository! scope {:repository-profile :fixture/v1
                                                               :repository-profiles {:fixture/v1 hooks}})
                                  nil (catch Throwable error error)))
              error (with-redefs [d/release (fn [conn] (swap! releases inc) (release conn))
                                  sdh/delete-database! (fn [cfg] (swap! deletions inc) (delete cfg))]
                      (attempt))]
          (is @aborted)
          (is (some? error))
          (is (= cleanup-fails? (d/database-exists? cfg)))
          (when cleanup-fails?
            (is (= :dvergr.substrate.geschichte/initialization-recovery-required (:type (ex-data error))))
            (is (= :owned (:phase (ex-data error))))
            (is (= "Initialization failed" (ex-message (ex-cause error))))
            (is (= #{:type :phase} (set (keys (ex-data error)))) "No live handle in wire/log ex-data")
            (is (= 0 @releases @deletions) "Unjoined abort retains the exact connection")
            (is (thrown? clojure.lang.ExceptionInfo (g/recover-initialization! error)))
            (is (d/database-exists? cfg))
            (reset! fail-cleanup false)
            (is (true? (g/recover-initialization! error)))
            (is (true? (g/recover-initialization! error)) "Successful cleanup recovery is idempotent")
            (is (= 1 @initializations) "Recovery never reruns initialization")
            (is (not (d/database-exists? cfg)))))))))

(deftest release-or-delete-uncertainty-retains-a-nonrepeatable-phase
  (doseq [failure [:release :delete :successful-initialization-release]]
    (with-store
      (fn [cfg scope]
        (let [opened (atom nil) releases (atom 0) deletes (atom 0) aborts (atom 0)
              release d/release delete sdh/delete-database!
              hooks {:initialize! (fn [conn _]
                                    (reset! opened conn)
                                    (repo/init! conn {:name "Uncertain fixture"})
                                    (when-not (= failure :successful-initialization-release)
                                      (throw (ex-info "Failed init" {}))))
                     :prepare-open! (fn [& _]) :abort-create! (fn [& _] (swap! aborts inc))}
              error (with-redefs [d/release (fn [conn]
                                              (swap! releases inc)
                                              (if (= failure :delete) (release conn)
                                                  (throw (ex-info "Release uncertainty" {}))))
                                  sdh/delete-database! (fn [_]
                                                         (swap! deletes inc)
                                                         (throw (ex-info "Delete uncertainty" {})))]
                      (try (g/ensure-repository! scope {:repository-profile :fixture/v1
                                                        :repository-profiles {:fixture/v1 hooks}})
                           nil (catch Throwable error error)))]
          (try
            (is (= (if (= failure :delete) :deleting :releasing) (:phase (ex-data error))))
            (is (= 1 @releases))
            (is (= (if (= failure :delete) 1 0) @deletes))
            (is (= (if (= failure :successful-initialization-release) 0 1) @aborts))
            (is (thrown? clojure.lang.ExceptionInfo (g/recover-initialization! error)))
            (is (= 1 @releases) "Recovery never blindly repeats uncertain native release")
            (is (d/database-exists? cfg))
            (finally (when-not (= failure :delete) (release @opened)))))))))
