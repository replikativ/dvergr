(ns dvergr.rooms.stats-test
  "RF5 S4: cost lives in each room's OWN store; per-room stats read that store and
   per-agent stats fan out over all rooms. This verifies the fan-out sums."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as dh]
            [dvergr.chat.schema :as cschema]
            [dvergr.discourse :as d]
            [dvergr.room.store.datahike :as store-dh]
            [dvergr.rooms.messages :as rmsg]
            [dvergr.rooms.stats :as rstats]
            [dvergr.orchestration.stats :as ostats]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.engine.context :as ctx]))

(defn- datahike-room [ctx slug]
  (let [cfg  {:store {:backend :memory :id (random-uuid)} :schema-flexibility :write}
        _    (dh/create-database cfg)
        conn (dh/connect cfg)]
    (dh/transact conn cschema/full-schema)
    (dh/transact conn [(merge (cschema/create-chat-entity {:id (random-uuid) :title slug})
                              {:room/slug slug :room/type :internal})])
    [(d/make-room {:id (keyword slug) :slug slug :ctx ctx :store (store-dh/make conn)})
     conn cfg]))

(defn- add-ledger!
  "A ledger entry of `microdollars` for a chat titled `title` in `conn`."
  [conn title microdollars]
  (let [cid (random-uuid)]
    (dh/transact conn [{:chat/id cid :chat/title title}
                       {:ledger/id (random-uuid)
                        :ledger/context [:chat/id cid]
                        :ledger/timestamp (java.util.Date.)
                        :ledger/resource :output-tokens
                        :ledger/amount 100
                        :ledger/cost-microdollars (long microdollars)}])))

(deftest per-room-and-per-agent-cost-fan-out
  (let [c (ctx/create-execution-context)]
    (binding [ec/*execution-context* c]
      (let [[ra ca cfga] (datahike-room c "rmA")
            [rb cb cfgb] (datahike-room c "rmB")]
        (try
          ;; var: $1 in rmA, $2 in rmB ; huginn: $0.50 in rmA
          (add-ledger! ca "var-rmA"    1000000)
          (add-ledger! cb "var-rmB"    2000000)
          (add-ledger! ca "huginn-rmA"  500000)
          (testing "per-room cost reads that room's OWN store"
            (is (= 1.5 (:cost-dollars (rstats/room-stats ra))) "rmA = var $1 + huginn $0.50")
            (is (= 2.0 (:cost-dollars (rstats/room-stats rb))) "rmB = var $2"))
          (testing "system rollup sums every room's ledger"
            (is (= 3.5 (:cost-dollars (rstats/system-stats)))))
          (testing "per-agent cost fans out across rooms (chat-title prefix)"
            (is (= 3.0 (#'ostats/query-cost "var"))    "var = $1 (rmA) + $2 (rmB)")
            (is (= 0.5 (#'ostats/query-cost "huginn")) "huginn = $0.50 (rmA)"))
          (finally
            (rmsg/drop-room! (:id ra)) (rmsg/drop-room! (:id rb))
            (dh/release ca) (dh/delete-database cfga)
            (dh/release cb) (dh/delete-database cfgb)))))))
