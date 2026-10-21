(ns dvergr.system.profiled-room-creation-test
  (:require [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [dvergr.substrate.geschichte :as g]
            [dvergr.substrate.datahike :as sdh]
            [dvergr.substrate.paths :as paths]
            [dvergr.system.db :as db]
            [dvergr.system.rooms :as rooms]
            [geschichte.repo :as repo]
            [kontor.governance :as governance]
            [org.replikativ.spindel.engine.context :as context]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.yggdrasil :as ygg]))

(defn- memory-config []
  {:store {:backend :memory :id (random-uuid)} :schema-flexibility :write
   :keep-history? false :attribute-refs? false :crypto-hash? true :commit-graph? true})

;; Test-native ownership, not application state. If teardown is uncertain, retain
;; the exact resources rather than deleting stores or losing the recovery owner
;; when with-redefs restores the production registries. Never retry blindly.
(defonce ^:private held-native-fixture (atom nil))

(defn- with-fixture [f]
  (when @held-native-fixture
    (throw (ex-info "Previous fixture requires native teardown investigation" {})))
  (let [control-config (memory-config) configs (atom {}) root (context/create-execution-context)
        conn-ref (atom nil) joined? (atom false)
        contexts (atom {}) errors (atom {}) cleanup-lease (atom nil)
        owner {:control-config control-config :configs configs :root root
               :conn conn-ref :contexts contexts :errors errors :cleanup-lease cleanup-lease}
        config-for (fn [scope] (get (swap! configs #(if (contains? % scope) % (assoc % scope (memory-config)))) scope))]
    (try
      (d/create-database control-config)
      (let [conn (d/connect control-config)]
        (reset! conn-ref conn)
        (with-redefs-fn
          {#'db/get-conn (constantly conn)
           #'paths/systems-dir (constantly "/virtual/profiled-room-test")
           #'g/repository-config config-for
           #'rooms/msgs-cfg config-for
           #'rooms/kb-cfg config-for
           #'rooms/room-ctxs contexts
           #'rooms/room-hydration-errors errors}
          #(binding [ec/*execution-context* root]
             (try
               (d/transact conn db/schema)
               (f {:conn conn :root root :configs configs})
               (finally
                 (rooms/release-room-stores!)
                 (reset! joined? true))))))
      (finally
        (if-not @joined?
          (reset! held-native-fixture owner)
          (try
            (doseq [cfg (vals @configs) :when (d/database-exists? cfg)]
              (let [native (d/connect cfg)]
                (reset! cleanup-lease native)
                (governance/ungovern! native)
                (d/release native)
                (reset! cleanup-lease nil))
              (d/delete-database cfg))
            (d/release @conn-ref)
            (d/delete-database control-config)
            (context/close-context! root)
            (catch Throwable error
              (reset! held-native-fixture owner)
              (throw error))))))))

(defn- policies [events]
  {:fixture/knowledge
   {:initialize! (fn [conn _] (swap! events conj :initialize) (repo/init! conn {:name "Typed fixture"}))
    :prepare-open! (fn [_ _] (swap! events conj :prepare-open)
                     (is (empty? (ygg/registered-systems)) "Mandatory adapters are private until all are prepared"))
    :abort-create! (fn [& _] (swap! events conj :abort))}})

(deftest explicit-creation-publishes-three-prepared-systems-after-durable-readiness
  (with-fixture
    (fn [{:keys [conn]}]
      (let [events (atom []) result (rooms/provision-profiled-room!
                                     {:slug "typed-created" :repository-profile :fixture/knowledge} (policies events))
            id (:room-id result)]
        (is (= [:initialize :prepare-open] @events))
        (is (= :ready (:room/provisioning-state (db/room-by-slug "typed-created"))))
        (is (identical? (:room-ctx result) (rooms/room-ctx-for id)))
        (is (= (:repo-id result) (:system/id (db/default-repository id))))
        (binding [ec/*execution-context* (:room-ctx result)]
          (is (= 3 (count (ygg/registered-systems)))))
        (let [before @conn]
          (is (thrown? clojure.lang.ExceptionInfo
                       (rooms/provision-profiled-room! {:slug "typed-created" :repository-profile :fixture/knowledge}
                                                       (policies events))))
          (is (= before @conn))
          (is (= [:initialize :prepare-open] @events)))))))

(deftest unknown-policy-and-child-world-are-refused-before-control-intent
  (with-fixture
    (fn [{:keys [conn]}]
      (let [before @conn events (atom [])]
        (is (thrown? clojure.lang.ExceptionInfo
                     (rooms/provision-profiled-room! {:slug "unknown" :repository-profile :fixture/knowledge} {})))
        (let [child (:child-ctx (ygg/fork! {:systems :none :purpose :fixture}))]
          (is (thrown? clojure.lang.ExceptionInfo
                       (binding [ec/*execution-context* child]
                         (rooms/provision-profiled-room! {:slug "child" :repository-profile :fixture/knowledge}
                                                         (policies events))))))
        (is (= before @conn))
        (is (empty? @events))))))

(deftest schema-failure-after-the-last-acquisition-keeps-pending-intent-without-publication
  (with-fixture
    (fn [{:keys [configs]}]
      (let [events (atom []) provision! sdh/provision! failure (ex-info "KB preparation failed after schema" {})]
        (with-redefs [sdh/provision! (fn [options]
                                       (let [result (provision! options)]
                                         (when (false? (:schema? options)) (throw failure))
                                         result))]
          (is (identical? failure
                          (try (rooms/provision-profiled-room! {:slug "failed-kb" :repository-profile :fixture/knowledge}
                                                               (policies events))
                               (catch Throwable error error)))))
        (let [room (db/room-by-slug "failed-kb")]
          (is (= :pending (:room/provisioning-state room)))
          (is (= 3 (count (db/systems-for-room (:room/id room)))))
          (is (thrown? clojure.lang.ExceptionInfo (rooms/room-ctx-for (:room/id room))))
          (is (= 3 (count @configs)))
          (is (every? d/database-exists? (vals @configs))))))))

(deftest failed-initializer-cleanup-owner-cannot-be-cleared-or-retried-around
  (with-fixture
    (fn [_]
      (let [events (atom []) abort-fails? (atom true)
            hooks (-> (get (policies events) :fixture/knowledge)
                      (assoc :initialize! (fn [conn _] (repo/init! conn {:name "Partial"})
                                            (throw (ex-info "Initializer failed" {}))))
                      (assoc :abort-create! (fn [& _] (when @abort-fails? (throw (ex-info "Abort not joined" {}))))))
            error (try (rooms/provision-profiled-room! {:slug "retained-init" :repository-profile :fixture/knowledge}
                                                       {:fixture/knowledge hooks})
                       (catch Throwable error error))
            id (:room/id (db/room-by-slug "retained-init"))]
        (try
          (is (g/initialization-recovery-required? error))
          (is (thrown? clojure.lang.ExceptionInfo (rooms/clear-room-ctxs!)))
          (is (thrown? clojure.lang.ExceptionInfo (rooms/hydrate-rooms!)))
          (finally
            (reset! abort-fails? false)
            (rooms/recover-room-preparation! id)))))))
