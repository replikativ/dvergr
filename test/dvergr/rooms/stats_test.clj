(ns dvergr.rooms.stats-test
  "RF5 S4: cost lives in each room's OWN store; per-room stats read that store and
   per-agent stats fan out over all rooms. This verifies the fan-out sums."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as dh]
            [dvergr.chat.schema :as cschema]
            [dvergr.agent.room-context :as room-context]
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
  "A ledger entry of `microdollars` for `agent`'s working chat in the room
   `room-id` (the chat id and title `room-context/ensure-ctx!` gives it)."
  [conn room-id agent microdollars]
  (let [cid (room-context/stable-chat-id room-id agent)]
    (dh/transact conn [{:chat/id cid :chat/title (str (name agent) "-" (name room-id))}
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
          ;; var: $1 in rmA, $2 in rmB ; huginn: $0.50 in rmA ;
          ;; variant (a name var is a prefix of): $4 in rmA
          (add-ledger! ca :rmA :var    1000000)
          (add-ledger! cb :rmB :var    2000000)
          (add-ledger! ca :rmA :huginn  500000)
          (add-ledger! ca :rmA :variant 4000000)
          (testing "per-room cost reads that room's OWN store"
            (is (= 5.5 (:cost-dollars (rstats/room-stats ra))) "rmA = var $1 + huginn $0.50 + variant $4")
            (is (= 2.0 (:cost-dollars (rstats/room-stats rb))) "rmB = var $2"))
          (testing "system rollup sums every room's ledger"
            (is (= 7.5 (:cost-dollars (rstats/system-stats)))))
          (testing "per-agent cost fans out across rooms, by agent id"
            (is (= 3.0 (#'ostats/query-cost :var))     "var = $1 (rmA) + $2 (rmB), not variant's $4")
            (is (= 0.5 (#'ostats/query-cost :huginn))  "huginn = $0.50 (rmA)")
            (is (= 4.0 (#'ostats/query-cost :variant)) "variant = $4 (rmA)"))
          (testing "last activity is the agent's own"
            (is (some? (#'ostats/query-last-active :var)))
            (is (nil? (#'ostats/query-last-active :va))))
          (finally
            (rmsg/drop-room! (:id ra)) (rmsg/drop-room! (:id rb))
            (dh/release ca) (dh/delete-database cfga)
            (dh/release cb) (dh/delete-database cfgb)))))))
