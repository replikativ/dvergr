(ns dvergr.agent.checkpoint-prediction-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [datahike.api :as db]
            [dvergr.agent.checkpoint-admission-test :as fixture]
            [dvergr.agent.environment :as environment]
            [dvergr.agent.evaluation :as evaluation]
            [dvergr.agent.experiment :as experiment]
            [dvergr.agent.program :as program]
            [dvergr.agent.roster :as roster]
            [dvergr.agent.run :as run]
            [dvergr.chat.agent :as chat-agent]
            [dvergr.model.chat :as model-chat]
            [dvergr.model.providers :as providers]
            [dvergr.substrate.geschichte :as gs]
            [dvergr.sandbox.workspace :as workspace]
            [geschichte.bytes :as bytes]
            [geschichte.query :as query]
            [geschichte.repo :as repo]
            [muschel.fs :as fs]))

(defn- commit-files! [conn marker]
  (repo/write! conn "AGENTS.md" (bytes/utf8 (str "# Instructions\n" marker "\n")))
  (repo/write! conn "state.md" (bytes/utf8 marker))
  (repo/stage-all! conn)
  (repo/commit! conn {:message marker :author "checkpoint-prediction-test"}))

(defn- checkpoint [conn]
  {"workspace" {:version 1 :kind :datahike :commit (db/commit-id @conn)
                :source {:store (get-in @conn [:config :store :id])
                         :repository (:geschichte.repo/id (query/repository @conn))}}})

(defn- setup [selected preparations]
  (evaluation/make-world-setup
   {:id :checkpoint/prediction :basis "v1" :checkpoints selected
    :prepare (fn [_] (swap! preparations inc) {:prepared true})}))

(def scorer
  (evaluation/make-evaluator
   {:id :checkpoint/prediction :observe (fn [{:keys [default]}] default)
    :verify (fn [_ evidence]
              (let [ok? (= "answer" (:result evidence))]
                {:checks {:answer? ok?} :reward (if ok? 1.0 0.0)}))}))

(defn- fixture-experiment [capability]
  (let [team (roster/make-agent
              (roster/make-roster)
              {:id :candidate :tools #{:clojure_eval} :sandbox #{}
               :model-policy {:provider :test :model "checkpoint-inputs-test"}
               :program {:kind :llm :max-model-steps 2 :budget-dollars 1.0 :auto-compact? false}})
        env (environment/make-environment
             {:id :checkpoint/prediction :task "answer" :verifier {:id :checkpoint/prediction :version 1}
              :limits {:timeout-ms 15000 :cancel-timeout-ms 3000}
              :world {:isolation :ctx :settlement :discard :setup (evaluation/world-setup-ref capability)}})
        definition (experiment/make-experiment
                    {:id :checkpoint/prediction
                     :dataset (experiment/make-dataset {:id :checkpoint/data :environments [env]})
                     :candidates [(roster/agent team :candidate)] :repetitions 1})]
    {:team team :definition definition
     :options {:world-setups {(evaluation/world-setup-ref capability) capability}
               :resume? true :complete-only? true}}))

(deftest checkpoint-prediction-matches-actual-c1-prompt-and-resumes-without-model-dispatch
  (#'fixture/with-room-workspace
   (fn [room conn]
     (commit-files! conn "CHECKPOINT-C1-INSTRUCTIONS")
     (let [selected (checkpoint conn) preparations (atom 0) capability (setup selected preparations)
           {:keys [team definition options]} (fixture-experiment capability)
           calls (atom 0) prompts (atom []) instructions program/llm-instructions]
       (commit-files! conn "PARENT-C2-INSTRUCTIONS")
       (let [branches (db/branches conn)]
         (with-redefs [providers/ensure-initialized! (constantly nil)
                       chat-agent/messages->api-format (fn [messages _ _] messages)
                       model-chat/chat (fn [_ _] (swap! calls inc)
                                         {:content "answer" :tool-calls nil
                                          :usage {:input-tokens 10 :output-tokens 5} :stop-reason :end-turn})
                       program/llm-instructions (fn [agent tools]
                                                  (let [value (instructions agent tools)]
                                                    (swap! prompts conj value) value))]
           (let [first-pass @(experiment/run room team definition {(evaluation/evaluator-ref scorer) scorer} options)]
             (is (not-any? :unpredicted? (:results first-pass)))
             (is (= 1 (count (get-in first-pass [:scorecard :scorecard/entries]))))
             (is (= 1 @calls))
             (is (= 1 @preparations))
             (is (= 2 (count @prompts)) "One metadata prediction and one actual Run")
             (is (every? #(str/includes? % "CHECKPOINT-C1-INSTRUCTIONS") @prompts))
             (is (not-any? #(str/includes? % "PARENT-C2-INSTRUCTIONS") @prompts)))
           (let [resumed @(experiment/run room team definition {(evaluation/evaluator-ref scorer) scorer} options)]
             (is (= 1 (count (get-in resumed [:scorecard :scorecard/entries]))))
             (is (= 1 @calls) "Resumed cell dispatches no additional inference")
             (is (= 1 @preparations) "Prediction never runs the preparer")
             (is (= 1 (count (run/runs room))) "Prediction creates no Run")
             (is (= branches (db/branches conn))))
           (is (= "PARENT-C2-INSTRUCTIONS" (fs/read-file (gs/filesystem) "/state.md")))
           (is (true? (evaluation/await-cleanups! room 3000)))))))))

(deftest explicit-host-workspace-override-refuses-before-checkpoint-allocation
  (#'fixture/with-room-workspace
   (fn [room conn]
     (commit-files! conn "C1")
     (let [preparations (atom 0) capability (setup (checkpoint conn) preparations)
           {:keys [team definition options]} (fixture-experiment capability)
           branches (db/branches conn)]
       (binding [workspace/*workspace-dir* "/not-the-selected-checkpoint"]
         (let [error (try (experiment/run room team definition {(evaluation/evaluator-ref scorer) scorer} options)
                          nil (catch Exception error error))]
           (is (= ::experiment/checkpoint-workspace-override (:type (ex-data error))))))
       (is (= branches (db/branches conn)))
       (is (zero? @preparations))
       (is (empty? (run/runs room)))))))

(deftest a-composed-setup-forwards-checkpoints-and-preserves-prompt-drift-exclusion
  (#'fixture/with-room-workspace
   (fn [room conn]
     (commit-files! conn "CHECKPOINT-C1-INSTRUCTIONS")
     (let [preparations (atom 0) base (setup (checkpoint conn) preparations)
           composed (evaluation/make-world-setup
                     {:id :checkpoint/composed :basis {:base (evaluation/world-setup-ref base)}
                      :checkpoints (:checkpoints base)
                      :prepare (fn [context]
                                 ((:prepare base) context)
                                 (fs/write-string! (gs/filesystem) "/AGENTS.md" "SETUP-CHANGED-INSTRUCTIONS" false)
                                 {:changed-guide true})})
           {:keys [team definition options]} (fixture-experiment composed)
           calls (atom 0) prompts (atom []) instructions program/llm-instructions]
       (commit-files! conn "PARENT-C2-INSTRUCTIONS")
       (with-redefs [providers/ensure-initialized! (constantly nil)
                     chat-agent/messages->api-format (fn [messages _ _] messages)
                     model-chat/chat (fn [_ _] (swap! calls inc)
                                       {:content "answer" :tool-calls nil
                                        :usage {:input-tokens 10 :output-tokens 5} :stop-reason :end-turn})
                     program/llm-instructions (fn [agent tools]
                                                (let [value (instructions agent tools)]
                                                  (swap! prompts conj value) value))]
         (let [result @(experiment/run room team definition {(evaluation/evaluator-ref scorer) scorer} options)]
           (is (= 1 @calls))
           (is (= 1 @preparations))
           (is (str/includes? (first @prompts) "CHECKPOINT-C1-INSTRUCTIONS"))
           (is (str/includes? (second @prompts) "SETUP-CHANGED-INSTRUCTIONS"))
           (is (= 1 (get-in result [:scorecard :incomplete :unpredicted])))
           (is (every? :unpredicted? (:results result)))
           (is (= "PARENT-C2-INSTRUCTIONS" (fs/read-file (gs/filesystem) "/state.md")))))))))
