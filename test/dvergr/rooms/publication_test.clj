(ns dvergr.rooms.publication-test
  (:require [clojure.core.async :as async]
            [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [dvergr.rooms.publication :as publication]
            [geschichte.repo :as repo]
            [geschichte.workspace :as workspace]))

(defn- with-repository [f]
  (let [config {:store {:backend :memory :id (random-uuid)} :schema-flexibility :write
                :keep-history? true :commit-graph? true}]
    (d/create-database config)
    (let [conn (d/connect config)]
      (try
        (repo/init! conn)
        (publication/install! conn)
        (f conn)
        (finally (d/release conn) (d/delete-database config))))))

(defn- publication-identity []
  {:publication/id (random-uuid) :publication/intent (random-uuid)
   :publication/actor (random-uuid) :publication/manifest (random-uuid)})

(defn- staged! [conn]
  (repo/write! conn "wiki/brief.md" (.getBytes "# Brief" "UTF-8"))
  (repo/stage! conn ["wiki/brief.md"]))

(defn- prepared [conn identity]
  (let [plan (publication/prepare conn {:message "Adopt wiki output"} ["/wiki/brief.md"])]
    [plan (publication/receipt plan identity
                               {"/wiki/brief.md" (get (:index plan) "wiki/brief.md")})]))

(deftest duplicate-publication-returns-the-winning-commit-after-later-edits
  (with-repository
    (fn [conn]
      (staged! conn)
      (let [identity (publication-identity)
            [first-plan first-receipt] (prepared conn identity)
            [losing-plan losing-receipt] (prepared conn identity)
            winning (publication/publish! conn first-plan first-receipt)]
        (is (not= (get-in first-plan [:commit :geschichte.commit/id])
                  (get-in losing-plan [:commit :geschichte.commit/id])))
        (is (= (get-in first-plan [:commit :geschichte.commit/id]) (:publication/commit winning)))
        (is (= (:publication/commit winning) (:geschichte.commit/id (repo/head-commit conn))))
        (is (= (get-in first-plan [:index "wiki/brief.md" :content])
               (get-in winning [:publication/entries 0 :publication.entry/content :geschichte.content/id])))
        (repo/set-config! conn "later" "change")
        (let [recovered (publication/publish! conn losing-plan losing-receipt)]
          (is (= (:publication/commit winning) (:publication/commit recovered)))
          (is (= (:publication/id winning) (:publication/id recovered))))
        (is (thrown? Exception
                     (publication/publish! conn losing-plan
                                           (assoc losing-receipt :publication/intent (random-uuid)))))))))

(deftest stale-preparation-publishes-neither-commit-nor-receipt
  (with-repository
    (fn [conn]
      (staged! conn)
      (let [[plan receipt] (prepared conn (publication-identity))]
        (repo/set-config! conn "unrelated" "invalidates")
        (is (thrown? Exception (publication/publish! conn plan receipt)))
        (is (nil? (repo/head-commit conn)))
        (is (nil? (publication/lookup @conn (:publication/id receipt))))))))

(deftest invalid-receipt-rolls-back-the-whole-publication-transaction
  (with-repository
    (fn [conn]
      (staged! conn)
      (let [[plan receipt] (prepared conn (publication-identity))]
        (doseq [[key value] [[:publication/noop? "invalid"]
                             [:publication/noop? true]
                             [:publication/commit (random-uuid)]
                             [:publication/head (random-uuid)]
                             [:publication/entries []]
                             [:publication/ref "refs/heads/elsewhere"]]]
          (is (thrown? Exception (publication/publish! conn plan (assoc receipt key value))))
          (is (nil? (repo/head-commit conn)))
          (is (nil? (publication/lookup @conn (:publication/id receipt)))))))))

(deftest inherited-receipt-does-not-recover-another-physical-target
  (with-repository
    (fn [conn]
      (staged! conn)
      (let [[plan receipt] (prepared conn (publication-identity))]
        (publication/publish! conn plan receipt)
        (workspace/fork! conn "other")
        (let [other (d/connect (assoc (:config @conn) :branch :geschichte.workspace/other))]
          (try
            (is (some? (publication/lookup @other (:publication/id receipt))))
            (is (thrown? Exception (publication/publish! other plan receipt)))
            (finally (d/release other))))))))

(deftest no-change-has-a-real-receipt-but-no-fake-commit
  (with-repository
    (fn [conn]
      (staged! conn)
      (let [head (:geschichte.commit/id (repo/commit! conn {:message "Base"}))
            [plan receipt] (prepared conn (publication-identity))
            published (publication/publish! conn plan receipt)]
        (is (:publication/noop? published))
        (is (nil? (:publication/commit published)))
        (is (= head (:publication/head published)))
        (is (= head (:geschichte.commit/id (repo/head-commit conn))))))))

(deftest receipt-entries-must-match-the-prepared-index
  (with-repository
    (fn [conn]
      (staged! conn)
      (let [plan (publication/prepare conn {:message "Brief"} ["/wiki/brief.md"])
            entry (get-in plan [:index "wiki/brief.md"])]
        (doseq [entries [{"/wiki/brief.md" (assoc entry :size 999)}
                         {"/wiki/brief.md" nil}
                         {"/wiki/../brief.md" nil}
                         {"/wiki\\brief.md" nil}
                         {"/wiki\u0000brief.md" nil}]]
          (is (thrown? Exception (publication/receipt plan (publication-identity) entries))))))))

(deftest interrupted-caller-retains-ownership-until-the-native-transaction-finishes
  (with-repository
    (fn [conn]
      (staged! conn)
      (let [[plan receipt] (prepared conn (publication-identity))
            original d/transact!
            dispatched (promise)
            native-result (async/promise-chan)
            completed (promise)
            submissions (atom 0)
            thread (Thread.
                    (fn []
                      (try
                        (let [winning (publication/publish! conn plan receipt)]
                          (deliver completed {:receipt winning
                                              :interrupted? (.isInterrupted (Thread/currentThread))}))
                        (catch Throwable error (deliver completed {:error error})))))]
        (with-redefs [d/transact! (fn [& _]
                                    (swap! submissions inc)
                                    (deliver dispatched true)
                                    native-result)]
          (.start thread)
          (is (= true (deref dispatched 5000 ::timeout)))
          (.interrupt thread)
          ;; No acknowledgement exists yet: interruption cannot release the
          ;; caller, settle ownership or submit another transaction.
          (is (not (realized? completed)))
          (async/>!! native-result @(original conn [[:db.fn/call publication/publish-tx plan receipt]]))
          (let [result (deref completed 5000 ::timeout)]
            (is (map? result))
            (is (:interrupted? result))
            (is (= (:publication/id receipt) (get-in result [:receipt :publication/id]))))
          (.join thread 5000)
          (is (not (.isAlive thread)))
          (is (= 1 @submissions)))))))

(deftest lost-acknowledgement-requires-exact-recovery-not-republication
  (with-repository
    (fn [conn]
      (staged! conn)
      (let [[plan receipt] (prepared conn (publication-identity))
            original d/transact!
            submissions (atom 0)
            error (with-redefs [d/transact! (fn [& args]
                                              (swap! submissions inc)
                                              @(apply original args)
                                              (let [reply (async/promise-chan)]
                                                (async/>!! reply (ex-info "lost acknowledgement" {}))
                                                reply))]
                    (try (publication/publish! conn plan receipt) nil
                         (catch Exception error error)))]
        (is (publication/uncertain? error))
        (is (= 1 @submissions))
        (repo/set-config! conn "later" "edit")
        (is (= (:publication/commit receipt)
               (:publication/commit (publication/recover @conn receipt))))
        (is (thrown? Exception
                     (publication/recover @conn (assoc receipt :publication/actor (random-uuid)))))
        (is (nil? (publication/recover @conn (assoc receipt :publication/id (random-uuid)))))
        (is (= 1 @submissions))))))

(deftest deterministic-rejection-does-not-dispatch-or-become-uncertain
  (with-repository
    (fn [conn]
      (staged! conn)
      (let [[plan receipt] (prepared conn (publication-identity))
            submissions (atom 0)
            error (with-redefs [d/transact! (fn [& _] (swap! submissions inc)
                                              (throw (ex-info "Must not dispatch" {})))]
                    (try (publication/publish! conn plan (assoc receipt :publication/head (random-uuid)))
                         nil
                         (catch Exception error error)))]
        (is (some? error))
        (is (not (publication/uncertain? error)))
        (is (zero? @submissions))))))

(deftest closed-native-result-keeps-the-operation-unresolved-without-replay
  (with-repository
    (fn [conn]
      (staged! conn)
      (let [[plan receipt] (prepared conn (publication-identity))
            submissions (atom 0)
            closed (async/chan)
            _ (async/close! closed)
            error (with-redefs [d/transact! (fn [& _] (swap! submissions inc) closed)]
                    (try (publication/publish! conn plan receipt) nil
                         (catch Exception error error)))]
        (is (publication/uncertain? error))
        (is (= 1 @submissions))
        (is (nil? (publication/recover @conn receipt)))
        (is (nil? (repo/head-commit conn)))
        (is (contains? (repo/worktree conn) "wiki/brief.md"))))))

(deftest a-real-throwing-transaction-listener-cannot-hide-the-publication
  (with-repository
    (fn [conn]
      (staged! conn)
      (let [[plan receipt] (prepared conn (publication-identity))
            completion (promise)
            thread (Thread. (fn []
                              (deliver completion
                                       (try (publication/publish! conn plan receipt)
                                            (catch Throwable error error)))))]
        (d/listen conn ::broken (fn [_] (throw (ex-info "observer failed" {}))))
        (try
          (.setDaemon thread true)
          (.start thread)
          (let [result (deref completion 10000 ::timeout)]
            (is (map? result) "observer failure cannot strand the native result")
            (is (= (:publication/id receipt) (:publication/id result)))
            (is (= (:publication/commit receipt) (:publication/commit result))))
          (is (= (:publication/id receipt)
                 (:publication/id (publication/recover @conn receipt))))
          (finally (d/unlisten conn ::broken)))))))
