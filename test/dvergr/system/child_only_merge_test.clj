(ns dvergr.system.child-only-merge-test
  (:require [clojure.test :refer [deftest is]]
            [datahike.api :as dh]
            [dvergr.discourse :as discourse]
            [dvergr.room.store.memory :as memory]
            [dvergr.substrate.datahike :as substrate]
            [dvergr.system.db :as sdb]
            [dvergr.system.rooms :as rooms]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.yggdrasil :as ygg]))

(defn- database []
  (let [cfg {:store {:backend :memory :id (random-uuid)}
             :schema-flexibility :write :keep-history? true :commit-graph? true}]
    (dh/create-database cfg)
    {:cfg cfg :conn (dh/connect cfg)}))

(defn- with-world [protected? f]
  (let [registry (database)
        kb (database)
        room-id (random-uuid)
        system-id (random-uuid)
        scope (str "/memory/child-only-" system-id)
        runtime-id (str "room-kb-child-only-" system-id)
        slug (str "child-only-" room-id)
        parent (discourse/make-room {:id (keyword slug) :store (memory/make)})]
    (try
      (dh/transact (:conn registry) sdb/schema)
      (dh/transact (:conn registry)
                   (cond-> [{:room/id room-id :room/slug slug :room/name slug}]
                     protected? (conj {:system/id system-id :system/type :kb
                                       :system/name (str "child-only-" system-id)
                                       :system/scope scope})))
      (dh/transact (:conn kb) [{:db/ident :datum/text :db/valueType :db.type/string
                                :db/cardinality :db.cardinality/one}])
      (dh/transact (:conn kb) [{:db/id 100 :datum/text "base"}])
      (binding [ec/*execution-context* (:ctx parent)]
        (with-redefs [sdb/get-conn (constantly (:conn registry))]
          (when protected? (sdb/attach! room-id system-id :read-write))
          (f {:parent parent :room-id room-id :system-id system-id
              :runtime-id runtime-id :conn (:conn kb)})))
      (finally
        (discourse/close-room! parent)
        (doseq [{:keys [cfg conn]} [registry kb]]
          (dh/release conn)
          (dh/delete-database cfg))))))

(defn- register! [room runtime-id conn]
  (binding [ec/*execution-context* (:ctx room)]
    (substrate/provision! {:conn conn :schema? false :system-name runtime-id})))

(defn- revoke! [room-id system-id operation]
  (case operation
    :downgrade (sdb/attach! room-id system-id :read)
    :detach (sdb/detach! room-id system-id)))

(deftest protected-child-only-adoption-rechecks-current-grants
  (doseq [operation [:downgrade :detach]]
    (with-world
      true
      (fn [{:keys [parent room-id system-id runtime-id conn]}]
        (let [fork (discourse/fork-room parent {:isolation :ctx})]
          (try
            (register! fork runtime-id conn)
            (is (not (contains? (ygg/registered-systems) runtime-id)))
            (is (not (contains? (ygg/context-diff (:ctx fork)) runtime-id))
                "Shared-pair deltas omit the capability a merge would introduce")
            (revoke! room-id system-id operation)
            (is (false? ((rooms/system-write-check room-id false) runtime-id))
                "The runtime identity must resolve to the revoked protected system")
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"may not write"
                                  (discourse/merge-room parent fork)))
            (is (not (contains? (ygg/registered-systems) runtime-id))
                "Refused settlement must not introduce a protected system")
            (is (ygg/open-fork? (discourse/fork-handle fork)))
            (is (nil? (discourse/settlement fork)))
            (finally (discourse/discard fork))))))))

(deftest allowed-and-unregistered-child-only-systems-still-merge
  (doseq [protected? [true false]]
    (with-world
      protected?
      (fn [{:keys [parent runtime-id conn]}]
        (let [fork (discourse/fork-room parent {:isolation :ctx})]
          (try
            (register! fork runtime-id conn)
            (discourse/merge-room parent fork)
            (is (contains? (ygg/registered-systems) runtime-id))
            (is (= :merged (:op (discourse/settlement fork))))
            (finally (discourse/discard fork))))))))

(deftest protected-shared-system-without-a-usable-delta-fails-closed
  (doseq [missing-delta [{} nil]]
    (with-world
      true
      (fn [{:keys [parent room-id system-id runtime-id conn]}]
        (register! parent runtime-id conn)
        (let [fork (discourse/fork-room parent {:isolation :ctx})
              diff (if (nil? missing-delta) {runtime-id nil} missing-delta)]
          (try
            (binding [ec/*execution-context* (:ctx fork)]
              (dh/transact (:conn (ygg/system runtime-id)) [{:db/id 100 :datum/text "changed"}]))
            (sdb/detach! room-id system-id)
            (with-redefs [ygg/context-diff (constantly diff)]
              (is (thrown? clojure.lang.ExceptionInfo (discourse/merge-room parent fork))))
            (is (= "base" (dh/q '[:find ?text . :where [_ :datum/text ?text]] @conn)))
            (is (ygg/open-fork? (discourse/fork-handle fork)))
            (finally (discourse/discard fork))))))))

(deftest late-guard-changes-are-rechecked-at-the-substrate-frontier
  (doseq [operation [:downgrade :detach :register]]
    (with-world
      true
      (fn [{:keys [parent room-id system-id runtime-id conn]}]
        (when-not (= :register operation) (register! parent runtime-id conn))
        (let [fork (discourse/fork-room parent {:isolation :ctx})]
          (try
            (if (= :register operation)
              ;; At early preflight neither composite names this protected
              ;; system. The guard introduces it only at the merge frontier.
              (sdb/detach! room-id system-id)
              (binding [ec/*execution-context* (:ctx fork)]
                (dh/transact (:conn (ygg/system runtime-id)) [{:db/id 100 :datum/text "changed"}])))
            (is (thrown-with-msg?
                 clojure.lang.ExceptionInfo #"may not write"
                 (discourse/merge-room
                  parent fork
                  {:guard (fn [settle!]
                            (if (= :register operation)
                              (register! fork runtime-id conn)
                              (revoke! room-id system-id operation))
                            (settle!))})))
            (is (= "base" (dh/q '[:find ?text . :where [_ :datum/text ?text]] @conn)))
            (is (ygg/open-fork? (discourse/fork-handle fork)))
            (is (nil? (discourse/settlement fork)))
            (when (= :register operation)
              (is (not (contains? (ygg/registered-systems) runtime-id))))
            (finally (discourse/discard fork))))))))
