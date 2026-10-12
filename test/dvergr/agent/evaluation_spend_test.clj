(ns dvergr.agent.evaluation-spend-test
  "Who pays for an Attempt's model calls: a protocol's (the candidate's and
   its driver's) come out of the Run's wallet, an observation's (a judge's)
   out of the wallet the Run was granted from, never the candidate's."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as dh]
            [dvergr.agent.environment :as environment]
            [dvergr.agent.evaluation :as evaluation]
            [dvergr.agent.roster :as roster]
            [dvergr.chat.schema :as chat-schema]
            [dvergr.discourse :as d]
            [dvergr.resource :as resource]
            [dvergr.room.store :as room-store]
            [dvergr.room.store.datahike :as datahike-store]
            [kontor.governance :as kontor-governance]
            [kontor.resource :as kontor]
            [org.replikativ.spindel.engine.core :as ec]))

(defn- ledger-room [id]
  (let [cfg {:store {:backend :memory :id (random-uuid)} :keep-history? true :schema-flexibility :write}
        chat-id (random-uuid)]
    (dh/create-database cfg)
    (let [conn (dh/connect cfg)]
      (chat-schema/ensure-full-schema! conn)
      (dh/transact conn [(merge (chat-schema/create-chat-entity {:id chat-id :title (name id)})
                                {:room/slug (room-store/room-id->slug id) :room/type :internal})])
      (resource/install-connection! conn id chat-id)
      [(d/make-room {:id id :store (datahike-store/make conn)}) conn])))

(def ^:private usage {:input-tokens 100 :output-tokens 50})

(defn- usd [room] (get (resource/balance room) resource/microdollars 0M))

(deftest protocol-and-judge-calls-are-paid
  (let [[room conn] (ledger-room :evaluation-spend)
        seen (atom {})
        protocol (evaluation/make-protocol
                  {:id :spend-test/protocol :version 1
                   :run (fn [{:keys [run/id]}]
                          ;; the candidate's call on the worker thread, the
                          ;; driver's on another thread carrying the binding
                          (resource/charge-usage! "m" usage)
                          @(future-call (bound-fn [] (resource/charge-usage! "m" usage)))
                          (swap! seen assoc :run-id id
                                 :wallet (resource/wallet-microdollars room id))
                          {:answer 42})})
        evaluator (evaluation/make-evaluator
                   {:id :spend-test/judged :version 1
                    :observe (fn [{:keys [default]}]
                               ;; the judge
                               (resource/charge-usage! "m" usage)
                               default)
                    :verify (fn [_ evidence]
                              {:checks {:answered? (= {:answer 42} (:result evidence))}
                               :reward 1.0})})
        env (environment/make-environment
             {:id :spend-test/env :task {:q 1}
              :verifier {:id :spend-test/judged :version 1}
              :limits {:timeout-ms 10000 :cancel-timeout-ms 5000}
              :world {:isolation :ctx :settlement :discard
                      :resources {resource/microdollars 10000M}
                      :protocol (evaluation/protocol-ref protocol)}})
        team (roster/make-agent (roster/make-roster {:id :spend-test})
                                {:id :candidate :program {:kind :echo}})]
    (try
      (resource/mint! room {:id (random-uuid) :resources {resource/microdollars 100000M}})
      ;; $1 per 1M input tokens, $2 per 1M output: a call costs 200 microdollars
      (with-redefs [dvergr.chat.accounting/get-model-pricing (fn [_] {:input 1 :output 2})]
        (let [result (binding [ec/*execution-context* (:ctx room)]
                       @(evaluation/evaluate room team :candidate env evaluator {:protocol protocol}))
              run-id (:run/id result)
              receipt (fn [scope] (room-store/-resource-receipt
                                   (:store room) (resource/spend-debit-id scope 1)))]
          (is (= 1.0 (get-in result [:attempt-receipt :attempt/reward])))
          (testing "the protocol's calls, on either thread, were paid from the Run's wallet"
            (is (= run-id (:run-id @seen)))
            (is (= 9600M (:wallet @seen))))
          (testing "the judge was paid from the wallet the Run was granted from"
            (let [r (receipt [:observe run-id])]
              (is (= {resource/microdollars 200M} (:resources r)))
              (is (= (kontor/account-ref (resource/room-wallet-id (:id room)))
                     (kontor/account-ref (:source r)))))
            (is (= (kontor/account-ref (resource/run-wallet-id run-id))
                   (kontor/account-ref (:source (receipt [:protocol run-id]))))))
          (is (= 99400M (usd room)) "three calls paid once each, the rest returned")))
      (finally
        (kontor-governance/ungovern! conn)
        (d/close-room! room)))))
