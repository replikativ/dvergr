(ns dvergr.agent.experiment.runner-authority-test
  (:require [clojure.test :refer [deftest is]]
            [dvergr.agent.environment :as environment]
            [dvergr.agent.evaluation :as evaluation]
            [dvergr.agent.experiment :as experiment]
            [dvergr.agent.experiment.runner :as runner]
            [dvergr.agent.roster :as roster]
            [dvergr.agent.run :as run]
            [dvergr.agent.subexperiment :as subexperiment]
            [dvergr.jobs :as jobs]
            [dvergr.chat.agent :as chat-agent]
            [dvergr.discourse :as discourse]
            [dvergr.model.chat :as chat]
            [dvergr.model.providers :as providers]
            [dvergr.room.store.memory :as memory]
            [org.replikativ.spindel.blocking]
            [org.replikativ.spindel.engine.core :as ec]))

(def evaluator
  (evaluation/make-evaluator
   {:id :runner-authority/outer-private
    :observe (fn [{:keys [default]}] default)
    :verify (fn [_ _] {:checks {:completed? true} :reward 1.0})}))

(def policy {:tools #{} :sandboxes #{#{}} :providers #{:test}
             :max-attempts 1 :max-parallelism 1})

(defn- options [agent]
  (let [team (roster/make-agent (roster/make-roster) agent)
        env (environment/make-environment
             {:id :runner-authority/task :task "Return done."
              :verifier {:id :runner-authority/outer-private :version 1}
              :limits {:timeout-ms 10000 :cancel-timeout-ms 5000}
              :world {:isolation :ctx :settlement :discard}})]
    {:team team
     :experiment (experiment/make-experiment
                  {:id :runner-authority/experiment
                   :dataset (experiment/make-dataset {:id :runner-authority/cases :environments [env]})
                   :candidates [(roster/agent team :candidate)]})
     :capabilities {:evaluator evaluator}
     :experiment-capabilities {}
     :delegation-policy policy}))

(deftest an-experiment-candidate-receives-only-the-explicit-child-scope
  ;; Use the real runner/evaluation/Run/tool-context path. Only the provider is
  ;; fake; no model/network call or global evaluator mutation is needed.
  (let [room (discourse/make-room {:id :runner-authority/scoped :store (memory/make)})
        seen (atom []) execute-turn chat-agent/run-agent-turn!
        opts (options {:id :candidate :tools #{}
                       :model-policy {:provider :test :model "scoped-candidate"}
                       :program {:kind :llm :max-model-steps 1 :auto-compact? false}})]
    (try
      (with-redefs [providers/ensure-initialized! (constantly nil)
                    chat-agent/messages->api-format (fn [messages _ _] messages)
                    chat-agent/run-agent-turn!
                    (fn [ctx args]
                      (swap! seen conj (select-keys (get-in args [:tool-ctx :agent-program-ceiling])
                                                    [:experiment-capabilities :delegation-policy]))
                      (execute-turn ctx args))
                    chat/chat (fn [& _] {:content "done" :stop-reason :end-turn
                                         :usage {:input-tokens 0 :output-tokens 0}})]
        (binding [ec/*execution-context* (:ctx room)]
          (let [result @(runner/run-in room opts)]
            (is (= 1 (count (:attempts result))))
            (is (= 1.0 (:reward (first (:scorecard/entries (:scorecard result))))))
            (is (= [{:experiment-capabilities {} :delegation-policy policy}] @seen))
            (is (not (contains? (:experiment-capabilities (first @seen)) :evaluators))
                "The outer private evaluator is not a candidate capability"))))
      (finally
        (evaluation/await-cleanups! room 10000)
        (discourse/close-room! room)))))

(deftest a-runner-cannot-drop-a-policy-that-refuses-the-candidate
  (let [room (discourse/make-room {:id :runner-authority/refused :store (memory/make)})
        opts (options {:id :candidate :tools #{:write_file} :program {:kind :echo}})]
    (try
      (binding [ec/*execution-context* (:ctx room)]
        (let [outcome (try {:result @(runner/run-in room opts)}
                           (catch clojure.lang.ExceptionInfo e {:error (ex-data e)}))]
          (is (empty? (run/runs room {:limit 10})) "Refuse before any Run/world admission")
          (is (or (= :dvergr.agent.authority/exceeds-policy (get-in outcome [:error :type]))
                  (= ["Agent exceeds the host delegation policy"]
                     (mapv :error (get-in outcome [:result :results])))))))
      (finally
        ;; An admission failure is also reported by the existing cleanup join.
        ;; It must not prevent this test's owner from closing its Room.
        (try
          (try (evaluation/await-cleanups! room 10000)
               (catch clojure.lang.ExceptionInfo e
                 (is (= :dvergr.agent.evaluation/cleanup-incomplete (:type (ex-data e))))))
          (finally (discourse/close-room! room)))))))

(deftest omitted-child-scope-remains-distinct-from-explicit-empty
  (let [captured (atom nil)
        opts (options {:id :candidate :program {:kind :echo}})]
    (with-redefs [experiment/run (fn [_ _ _ _ options] (reset! captured options) :captured)]
      (runner/run-in {:ctx nil} (dissoc opts :experiment-capabilities :delegation-policy))
      (is (not (contains? @captured :experiment-capabilities)))
      (is (not (contains? @captured :delegation-policy)))
      (runner/run-in {:ctx nil} (assoc opts :experiment-capabilities {}))
      (is (contains? @captured :experiment-capabilities))
      (is (= {} (:experiment-capabilities @captured))))))

(deftest host-child-options-do-not-replace-private-scoring-authority
  (doseq [explicit? [false true]]
    (let [room (discourse/make-room {:id (keyword (str "runner-child-scope-" (random-uuid)))
                                     :store (memory/make)})
          {:keys [team experiment]} (options {:id :candidate :program {:kind :echo}})
          scoring {:evaluators {(evaluation/evaluator-ref evaluator) evaluator} :world-setups {}}
          authority {:experiment-capabilities scoring :delegation-policy policy}
          child-options {:experiment-capabilities {} :delegation-policy policy}
          captured (atom nil) execute experiment/run]
      (try
        (binding [ec/*execution-context* (:ctx room)]
          (with-redefs [experiment/run (fn [room team definition evaluators options]
                                         (reset! captured {:evaluators evaluators :options options})
                                         (execute room team definition evaluators options))]
            (let [result @(subexperiment/start! room room team experiment {} authority
                                                (if explicit? {:candidate-options child-options} {}))]
              (is (= :completed (:status result)))
              (is (= 1.0 (:reward (first (get-in result [:scorecard :scorecard/entries])))))
              (is (= (:evaluators scoring) (:evaluators @captured)))
              (is (= (if explicit? {} scoring)
                     (get-in @captured [:options :experiment-capabilities])))
              (is (= policy (get-in @captured [:options :delegation-policy]))))))
        (finally
          (evaluation/await-cleanups! room 10000)
          (discourse/close-room! room))))))

(deftest ambiguous-or-forged-host-child-options-refuse-before-job-admission
  (let [room (discourse/make-room {:id :runner-authority/invalid-child-options :store (memory/make)})
        {:keys [team experiment]} (options {:id :candidate :program {:kind :echo}})]
    (try
      (doseq [bad [nil {} {:experiment-capabilities {}} {:delegation-policy policy}
                   {:experiment-capabilities nil :delegation-policy policy}
                   {:experiment-capabilities {} :delegation-policy nil}
                   {:experiment-capabilities {} :delegation-policy policy :parent-run (random-uuid)}]]
        (is (thrown? clojure.lang.ExceptionInfo
                     (subexperiment/start! room room team experiment {} {}
                                           {:candidate-options bad}))))
      (is (empty? (jobs/jobs room)))
      (is (empty? (run/runs room {:limit 10})))
      (finally (discourse/close-room! room)))))
