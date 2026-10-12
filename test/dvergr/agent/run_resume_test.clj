(ns dvergr.agent.run-resume-test
  "Resuming a stopped Run from its turn savepoint (doc/runs.md, Resuming a Run): each
   gap between model steps is persisted on the Run; a new Run continues from
   the latest one, caused by the old, once."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as dh]
            [dvergr.agent.program :as program]
            [dvergr.agent.roster :as roster]
            [dvergr.agent.run :as run]
            [dvergr.agent.world :as world]
            [dvergr.chat.agent :as chat-agent]
            [dvergr.chat.context :as chat-context]
            [dvergr.chat.schema :as chat-schema]
            [dvergr.discourse :as d]
            [dvergr.model.providers :as providers]
            [dvergr.resource :as resource]
            [dvergr.sandbox]
            [dvergr.room.store :as room-store]
            [dvergr.room.store.datahike :as datahike-store]
            [kontor.governance :as kontor-governance]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.savepoint.portable :as portable]))

(defn- durable-room [id]
  (let [cfg {:store {:backend :memory :id (random-uuid)} :keep-history? false :schema-flexibility :write}]
    (dh/create-database cfg)
    (let [conn (dh/connect cfg)]
      (chat-schema/ensure-full-schema! conn)
      (d/make-room {:id id :store (datahike-store/make conn)}))))

(def ^:private team
  (roster/make-agent
   (roster/make-roster {:id :resume-team})
   {:id :worker
    :tools #{:clojure_eval}
    :model-policy {:provider :codex-subscription :model "codex-subscription-sol"}
    :program {:kind :llm :max-model-steps 6 :auto-compact? false}}))

(defn- tool-step! [chat-ctx n]
  (chat-context/add-message! chat-ctx {:role :assistant :content ""
                                       :tool-uses [{:tool-use/id (str "call-" n)
                                                    :tool-use/name "clojure_eval"
                                                    :tool-use/input {:code "(+ 1 1)"}}]})
  (chat-context/add-message! chat-ctx {:role :tool-result :tool-use-id (str "call-" n) :content "=> 2"})
  :continue)

(deftest a-stopped-run-continues-from-its-last-turn
  (let [room (durable-room :resume-room)
        seen (atom [])
        continued (atom [])
        first-metrics (atom nil)
        continue-llm-run program/continue-llm-run]
    (try
      (binding [ec/*execution-context* (:ctx room)]
        ;; the first Run: a tool step, then its model call fails (as a process
        ;; that stopped would leave it: failed, with the gap after step 0 kept)
        (let [first-run
              (with-redefs [providers/ensure-initialized! (constantly nil)
                            dvergr.chat.accounting/calculate-cost (fn [_type amount & _] amount)
                            chat-agent/run-agent-turn!
                            (fn [chat-ctx opts]
                              (if (zero? (:turn-number opts))
                                (do (chat-context/account-usage! chat-ctx :output-tokens 250000)
                                    (tool-step! chat-ctx 0))
                                (throw (ex-info "the process stopped" {}))))]
                (let [h (program/hire! room team :worker {:task "calculate"})]
                  (reset! first-metrics (:run/metrics @h))
                  (program/run-id h)))
              old (run/run room first-run)
              data (run/savepoint old)]
          (testing "the failed Run kept the gap after its completed step"
            (is (= :failed (:run/status old)))
            (is (= 0 (get-in data [:savepoint/payload :step])))
            (is (= {:fn `program/continue-llm-run :args [first-run 0]} (:savepoint/resume data)))
            (is (uuid? (:savepoint/id data)))
            (is (= data (clojure.edn/read-string (pr-str data))) "plain data"))
          (testing "resuming: a new Run, caused by the old, continuing at step 1 with its conversation"
            (let [second-run
                  (with-redefs [providers/ensure-initialized! (constantly nil)
                                ;; the harness prompt changed since the Run stopped
                                dvergr.sandbox/sandbox-prompt-pointer "a newer sandbox prompt"
                                ;; spindel's hydrate-into! starts the named continuation
                                program/continue-llm-run
                                (fn [run-id step value]
                                  (swap! continued conj [run-id step])
                                  (continue-llm-run run-id step value))
                                chat-agent/run-agent-turn!
                                (fn [chat-ctx opts]
                                  (swap! seen conj {:step (:turn-number opts)
                                                    :budget (:total (chat-context/get-budget chat-ctx))
                                                    :messages (mapv :message/role (chat-context/get-messages chat-ctx))})
                                  (chat-context/add-message! chat-ctx {:role :assistant :content "The result is 2."})
                                  :complete)]
                    (let [h (program/resume! room room first-run)
                          result @h]
                      (is (= :completed (:run/status result)))
                      (is (= "The result is 2." (:run/value result)))
                      (is (= (select-keys @first-metrics [:prompt-id :inputs-id])
                             (select-keys (:run/metrics result) [:prompt-id :inputs-id]))
                          "it records the system prompt it continues with, the stopped Run's")
                      (program/run-id h)))]
              (is (= [1] (mapv :step @seen)) "the steps count on from the savepoint")
              (is (= 750000 (:budget (first @seen)))
                  "its budget is what the stopped Run left, not a fresh one")
              (is (= [[first-run 0]] @continued)
                  "the new world was hydrated from the savepoint: its continuation ran there")
              (is (= [:system :user :assistant :tool-result] (:messages (first @seen)))
                  "the conversation is the old Run's, up to the savepoint")
              (is (contains? (:run/caused-by (run/run room second-run)) first-run))
              (is (= second-run (:run/resumed-by (run/run room first-run))))
              (testing "and a Run is resumed once"
                (is (thrown-with-msg? Exception #"resumed before"
                                      (program/resume! room room first-run))))))))
      (finally (d/close-room! room)))))

(defn- resource-room [id]
  (let [cfg {:store {:backend :memory :id (random-uuid)} :keep-history? true :schema-flexibility :write}
        chat-id (random-uuid)]
    (dh/create-database cfg)
    (let [conn (dh/connect cfg)]
      (chat-schema/ensure-full-schema! conn)
      (dh/transact conn [(merge (chat-schema/create-chat-entity {:id chat-id :title (name id)})
                                {:room/slug (room-store/room-id->slug id) :room/type :internal})])
      (resource/install-connection! conn id chat-id)
      [(d/make-room {:id id :store (datahike-store/make conn)}) conn])))

(deftest a-failed-resume-leaves-the-old-run-untouched
  (let [[room conn] (resource-room :resume-failure-room)]
    (try
      (resource/mint! room {:id (random-uuid) :resources {resource/microdollars 10000000M}})
      (binding [ec/*execution-context* (:ctx room)]
        (let [first-run
              ;; a process that stopped mid-Run: failed, its wallet not returned
              (with-redefs [providers/ensure-initialized! (constantly nil)
                            program/return-unused-resources! (fn [& _] nil)
                            chat-agent/run-agent-turn!
                            (fn [chat-ctx opts]
                              (if (zero? (:turn-number opts))
                                (tool-step! chat-ctx 0)
                                (throw (ex-info "the process stopped" {}))))]
                (let [h (program/hire! room team :worker
                                       {:task "calculate"
                                        :resources {resource/microdollars 4000000M}})]
                  @h
                  (program/run-id h)))
              wallet (resource/run-balance room first-run)
              room-balance (resource/balance room)
              untouched? (fn []
                           (and (nil? (:run/resumed-by (run/run room first-run)))
                                (= wallet (resource/run-balance room first-run))
                                (= room-balance (resource/balance room))
                                (empty? (run/active-runs :resume-failure-room))))]
          (is (pos? (get wallet resource/microdollars 0M)) "the stopped Run kept its wallet")
          (testing "a world that cannot open"
            (with-redefs [providers/ensure-initialized! (constantly nil)
                          world/open! (fn [& _] (throw (ex-info "no world" {})))]
              (is (thrown-with-msg? Exception #"no world"
                                    (program/resume! room room first-run))))
            (is (untouched?) "no claim, no transfer"))
          (testing "a savepoint that cannot hydrate"
            (with-redefs [providers/ensure-initialized! (constantly nil)
                          portable/hydrate-into!
                          (fn [& _] (fn [_ok fail] (fail (ex-info "no hydration" {}))))]
              (is (thrown-with-msg? Exception #"no hydration"
                                    (program/resume! room room first-run))))
            (is (untouched?) "no claim, no transfer"))
          (testing "a Run that cannot be admitted"
            (with-redefs [providers/ensure-initialized! (constantly nil)
                          run/start! (fn [& _] (throw (ex-info "no admission" {})))]
              (is (thrown-with-msg? Exception #"no admission"
                                    (program/resume! room room first-run))))
            (is (untouched?) "no claim, no transfer"))
          (testing "a wallet that cannot be split"
            (with-redefs [providers/ensure-initialized! (constantly nil)
                          resource/allocate-run! (fn [& _] (throw (ex-info "no allocation" {})))]
              (is (thrown-with-msg? Exception #"no allocation"
                                    (program/resume! room room first-run))))
            (is (untouched?) "the claim is released and the wallet stays"))
          (testing "a wallet that cannot be read"
            (with-redefs [providers/ensure-initialized! (constantly nil)
                          resource/run-balance (fn [& _] (throw (ex-info "store down" {})))]
              (is (thrown-with-msg? Exception #"store down"
                                    (program/resume! room room first-run))))
            (is (untouched?) "the resume stops before anything changes"))
          (testing "an execution that cannot be spawned, after the claim"
            (let [claimed (atom nil)
                  claim-resume! run/claim-resume!]
              (with-redefs [providers/ensure-initialized! (constantly nil)
                            run/claim-resume! (fn [& args]
                                                (reset! claimed (apply claim-resume! args)))
                            program/execution-spin (fn [& _] (throw (ex-info "no spawn" {})))]
                (is (thrown-with-msg? Exception #"no spawn"
                                      (program/resume! room room first-run))))
              (is (uuid? (:run/resumed-by @claimed)) "the successor had claimed the Run")
              (is (untouched?) "the claim is withdrawn and the wallet returned")))
          (testing "a failed spawn whose claim withdrawal and terminal write are refused once"
            (let [finish! run/finish!
                  release-resume-claim! run/release-resume-claim!
                  failed-finishes (atom 0)
                  withdrawals (atom 0)]
              (with-redefs [providers/ensure-initialized! (constantly nil)
                            program/execution-spin (fn [& _] (throw (ex-info "no spawn" {})))
                            run/release-resume-claim!
                            (fn [& args]
                              (if (= 1 (swap! withdrawals inc))
                                (throw (ex-info "store refused" {}))
                                (apply release-resume-claim! args)))
                            run/finish! (fn [run-id status & opts]
                                          (if (and (= :failed status) (= 1 (swap! failed-finishes inc)))
                                            (throw (ex-info "store refused" {}))
                                            (apply finish! run-id status opts)))]
                (is (thrown-with-msg? Exception #"no spawn"
                                      (program/resume! room room first-run)))
                (is (= first-run (:run/id (run/reserve-resume! room first-run)))
                    "at once resumable again, although the withdrawal is not durable yet")
                (run/release-resume! first-run)
                (let [deadline (+ (System/currentTimeMillis) 3000)]
                  (while (and (or (seq (run/active-runs :resume-failure-room))
                                  (:run/resumed-by (run/run room first-run)))
                              (< (System/currentTimeMillis) deadline))
                    (Thread/sleep 10))))
              (is (<= 2 @failed-finishes) "the refused finish was retried")
              (is (<= 2 @withdrawals) "the refused withdrawal was retried")
              (is (untouched?) "the claim is withdrawn durably and the wallet returned")))
          (testing "a resume in progress excludes another"
            (run/reserve-resume! room first-run)
            (try
              (is (thrown-with-msg? Exception #"being resumed"
                                    (program/resume! room room first-run)))
              (finally (run/release-resume! first-run)))
            (is (untouched?)))
          (testing "the Run still resumes, and the new Run owns the old wallet"
            (let [observed (promise)
                  second-run
                  (with-redefs [providers/ensure-initialized! (constantly nil)
                                chat-agent/run-agent-turn!
                                (fn [chat-ctx _opts]
                                  (deliver observed
                                           (resource/run-balance
                                            room (:run/id (first (run/active-runs :resume-failure-room)))))
                                  (chat-context/add-message! chat-ctx {:role :assistant :content "The result is 2."})
                                  :complete)]
                    (let [h (program/resume! room room first-run)]
                      (is (= :completed (:run/status @h)))
                      (program/run-id h)))]
              (is (= wallet (deref observed 1000 ::timeout))
                  "while it ran, the new Run held what the old one left")
              (is (= second-run (:run/resumed-by (run/run room first-run))))
              (is (contains? (:run/caused-by (run/run room second-run)) first-run))
              (is (= {} (resource/run-balance room first-run)))
              (is (= (merge-with + room-balance wallet) (resource/balance room))
                  "its remainder returned to the Room when it finished")))))
      (finally
        (kontor-governance/ungovern! conn)
        (d/close-room! room)))))
