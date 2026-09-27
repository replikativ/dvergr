(ns dvergr.agent.run-resume-test
  "Resuming a stopped Run from its turn savepoint (doc/run-resume.md): each
   gap between model steps is persisted on the Run; a new Run continues from
   the latest one, caused by the old, once."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as dh]
            [dvergr.agent.program :as program]
            [dvergr.agent.roster :as roster]
            [dvergr.agent.run :as run]
            [dvergr.chat.agent :as chat-agent]
            [dvergr.chat.context :as chat-context]
            [dvergr.chat.schema :as chat-schema]
            [dvergr.discourse :as d]
            [dvergr.model.providers :as providers]
            [dvergr.room.store.datahike :as datahike-store]
            [org.replikativ.spindel.engine.core :as ec]))

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
        seen (atom [])]
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
                  @h
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
                      (program/run-id h)))]
              (is (= [1] (mapv :step @seen)) "the steps count on from the savepoint")
              (is (= 750000 (:budget (first @seen)))
                  "its budget is what the stopped Run left, not a fresh one")
              (is (= [:system :user :assistant :tool-result] (:messages (first @seen)))
                  "the conversation is the old Run's, up to the savepoint")
              (is (contains? (:run/caused-by (run/run room second-run)) first-run))
              (is (= second-run (:run/resumed-by (run/run room first-run))))
              (testing "and a Run is resumed once"
                (is (thrown-with-msg? Exception #"resumed before"
                                      (program/resume! room room first-run))))))))
      (finally (d/close-room! room)))))
