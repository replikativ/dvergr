(ns dvergr.system.component-merge-test
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as dh]
            [dvergr.discourse :as discourse]
            [dvergr.room.store.memory :as memory]
            [dvergr.substrate.datahike :as substrate]
            [dvergr.system.db :as sdb]
            [dvergr.system.rooms :as rooms]
            [org.replikativ.spindel.engine.context :as context]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.yggdrasil :as ygg]))

(defn- database []
  (let [cfg {:store {:backend :memory :id (random-uuid)}
             :schema-flexibility :write :keep-history? true :commit-graph? true}]
    (dh/create-database cfg)
    {:cfg cfg :conn (dh/connect cfg)}))

(defn with-world [f]
  (let [registry (database) left (database) right (database)
        root (context/create-execution-context)
        room-id (random-uuid) slug (str "component-merge-" room-id)
        parent (discourse/make-room {:id (keyword slug) :ctx root :store (memory/make)})
        systems (mapv (fn [side]
                        (let [id (random-uuid) name (str "component-" (name side) "-" id)]
                          {:system/id id :system/type :kb :system/name name
                           :system/scope (str "/memory/" name) :sid (str "room-kb-" name)})) [:left :right])]
    (try
      (dh/transact (:conn registry) sdb/schema)
      (dh/transact (:conn registry) (into [{:room/id room-id :room/slug slug :room/name slug}]
                                          (map #(dissoc % :sid) systems)))
      (binding [ec/*execution-context* root]
        (doseq [[db system] (map vector [left right] systems)]
          (substrate/provision! {:conn (:conn db) :schema? false :system-name (:sid system)
                                 :extra-schema [{:db/ident :datum/id :db/valueType :db.type/uuid
                                                 :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
                                                {:db/ident :datum/text :db/valueType :db.type/string
                                                 :db/cardinality :db.cardinality/one}]})
          (dh/transact (:conn db) [{:datum/id room-id :datum/text "base"}]))
        (with-redefs [sdb/get-conn (constantly (:conn registry))]
          (doseq [system systems] (sdb/attach! room-id (:system/id system) :read-write))
          (f {:parent parent :room-id room-id :slug slug :systems systems
              :left (:conn left) :right (:conn right)})))
      (finally
        (discourse/close-room! parent)
        (context/close-context! root)
        (doseq [{:keys [cfg conn]} [registry left right]]
          (dh/release conn)
          (dh/delete-database cfg))))))

(defn value [conn]
  (dh/q '[:find ?text . :where [_ :datum/text ?text]] @conn))

(defn fork-and-change [parent room-id systems sides]
  (let [fork (discourse/fork-room parent {:isolation :ctx})]
    (binding [ec/*execution-context* (:ctx fork)]
      (doseq [n sides]
        (dh/transact (:conn (ygg/system (:sid (nth systems n))))
                     [{:datum/id room-id :datum/text (str "changed-" n)}])))
    fork))

(defn partition-world [fork systems]
  (let [transfer (discourse/transfer-fork! fork :test-adopter
                                           {:prepare! (constantly :adoption-receipt) :abort! (fn [_])})
        parts (discourse/partition-transferred-fork!
               transfer (mapv (fn [system] {:systems #{(:sid system)} :owner :test-adopter}) systems))]
    parts))

(defn leaf [tree sid]
  (first (filter #(contains? (get-in % [:fork/descriptor :fork/systems]) sid) (:fork/partitions tree))))

(defn lifecycle [slug node prepared]
  {:prepare! (fn [{operation :fork/operation}]
               (when (= :merge operation)
                 (rooms/assert-fork-merge-permitted! slug (:fork/handle node)))
               (swap! prepared inc)
               :receipt)
   :abort! (fn [_])
   :commit! (fn [_ _])})

(deftest adopted-component-rechecks-downgraded-and-detached-grants-before-journaling
  (doseq [revocation [:downgrade :detach]]
    (with-world
      (fn [{:keys [parent room-id slug systems left right]}]
        (let [fork (fork-and-change parent room-id systems [0 1])
              tree (partition-world fork systems)
              blocked (leaf tree (:sid (first systems)))
              allowed (leaf tree (:sid (second systems)))
              prepared (atom 0)]
          (if (= revocation :downgrade)
            (sdb/attach! room-id (:system/id (first systems)) :read)
            (sdb/detach! room-id (:system/id (first systems))))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"may no longer write"
                                (discourse/settle-transferred-fork! blocked :merge (lifecycle slug blocked prepared))))
          (is (zero? @prepared) "No durable settling intent before permission")
          (is (ygg/open-fork? (:fork/handle blocked)))
          (is (= "base" (value left)))
          (let [merged (discourse/settle-transferred-fork! allowed :merge (lifecycle slug allowed prepared))
                discarded (discourse/settle-transferred-fork! blocked :discard (lifecycle slug blocked prepared))]
            (is (= "changed-1" (value right)) "A blocked sibling does not block the selected allowed leaf")
            (is (= "base" (value left)))
            (is (= 2 @prepared) "Revocation does not prevent discard")
            (discourse/release-transferred-fork! (assoc tree :fork/partitions [merged discarded]))))))))

(deftest unchanged-blocked-component-can-settle-without-authorizing-a-write
  (with-world
    (fn [{:keys [parent room-id slug systems left right]}]
      (let [tree (partition-world (fork-and-change parent room-id systems [1]) systems)
            prepared (atom 0)]
        (sdb/detach! room-id (:system/id (first systems)))
        (let [settled (mapv #(discourse/settle-transferred-fork! % :merge (lifecycle slug % prepared))
                            (:fork/partitions tree))]
          (is (= "base" (value left)))
          (is (= "changed-1" (value right)))
          (discourse/release-transferred-fork! (assoc tree :fork/partitions settled)))))))

(deftest protected-unknown-error-and-uninspectable-deltas-fail-closed
  (with-world
    (fn [{:keys [parent room-id slug systems left]}]
      (let [tree (partition-world (fork-and-change parent room-id systems [0]) systems)
            node (leaf tree (:sid (first systems))) handle (:fork/handle node)
            prepared (atom 0) sid (:sid (first systems))]
        (sdb/detach! room-id (:system/id (first systems)))
        (doseq [diff [{} {sid nil} {sid {:error :unavailable}}]]
          (with-redefs [ygg/fork-diff (constantly diff)]
            (is (thrown? clojure.lang.ExceptionInfo (rooms/assert-fork-merge-permitted! slug handle)))))
        (with-redefs [ygg/fork-diff (fn [_] (throw (ex-info "Cannot diff" {})))]
          (is (thrown? clojure.lang.ExceptionInfo (rooms/assert-fork-merge-permitted! slug handle))))
        (is (thrown? clojure.lang.ExceptionInfo (rooms/assert-fork-merge-permitted! "missing-room" handle)))
        (is (= "base" (value left)))
        (is (ygg/open-fork? handle))
        (discourse/release-transferred-fork!
         (assoc tree :fork/partitions
                (mapv #(discourse/settle-transferred-fork! % :discard (lifecycle slug % prepared))
                      (:fork/partitions tree))))))))

(deftest unpartitioned-authority-is-not-confused-with-a-fixed-settlement-leaf
  (with-world
    (fn [{:keys [parent room-id slug systems]}]
      (let [transfer (discourse/transfer-fork! (fork-and-change parent room-id systems []) :test-adopter
                                               {:prepare! (constantly :receipt) :abort! (fn [_])})]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"fixed partition leaf"
                              (rooms/assert-fork-merge-permitted! slug (:fork/handle transfer))))
        (discourse/release-transferred-fork! (discourse/settle-transferred-fork! transfer :discard))))))

(deftest registry-identity-is-exact-and-ambiguity-refuses
  (with-world
    (fn [{:keys [systems]}]
      (let [db @(sdb/get-conn) system (first systems)]
        (is (= (:system/id system) (:system/id (rooms/registry-system-for db (:sid system)))))
        (is (nil? (rooms/registry-system-for db "not-registered")))
        (dh/transact (sdb/get-conn) [{:system/id (random-uuid) :system/type :kb
                                      :system/name "collision" :system/scope (str "/other/" (:system/name system))}])
        (is (thrown? clojure.lang.ExceptionInfo (rooms/registry-system-for (:sid system))))))))

(deftest ambiguous-target-cannot-borrow-a-colliding-systems-write-grant
  (with-world
    (fn [{:keys [parent room-id slug systems left]}]
      (let [fork (fork-and-change parent room-id systems [0])
            tree (partition-world fork systems)
            system (first systems)
            node (leaf tree (:sid system))
            before-collision (rooms/system-write-check room-id false)
            prepared (atom 0)]
        (dh/transact (sdb/get-conn)
                     [{:system/id (random-uuid) :system/type :kb :system/name "collision"
                       :system/scope (str "/other/" (:system/name system))}])
        (is (true? (before-collision (:sid system))) "A previously captured policy keeps its DB snapshot")
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"ambiguous registry identity"
                              ((rooms/system-write-check room-id false) (:sid system))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"ambiguous registry identity"
                              (rooms/assert-merge-permitted! slug (:ctx fork))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"ambiguous registry identity"
                              (discourse/settle-transferred-fork! node :merge (lifecycle slug node prepared))))
        (is (zero? @prepared))
        (is (= "base" (value left)))
        (is (ygg/open-fork? (:fork/handle node)))
        (discourse/release-transferred-fork!
         (assoc tree :fork/partitions
                (mapv #(discourse/settle-transferred-fork! % :discard (lifecycle slug % prepared))
                      (:fork/partitions tree))))))))

(deftest malformed-protected-locator-never-becomes-an-unregistered-local-system
  (with-world
    (fn [{:keys [room-id systems]}]
      (let [system (first systems)]
        (dh/transact (sdb/get-conn)
                     [[:db/retract [:system/id (:system/id system)]
                       :system/scope (:system/scope system)]])
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no valid scope locator"
                              (rooms/registry-system-for (:sid system)))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no valid scope locator"
                            (rooms/system-write-check room-id false))))))
