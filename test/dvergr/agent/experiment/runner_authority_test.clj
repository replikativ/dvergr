(ns dvergr.agent.experiment.runner-authority-test
  (:require [clojure.test :refer [deftest is]]
            [dvergr.agent.environment :as environment]
            [dvergr.agent.evaluation :as evaluation]
            [dvergr.agent.experiment :as experiment]
            [dvergr.agent.experiment.runner :as runner]
            [dvergr.agent.roster :as roster]
            [dvergr.agent.program :as program]
            [dvergr.agent.run :as run]
            [dvergr.agent.subexperiment :as subexperiment]
            [dvergr.jobs :as jobs]
            [dvergr.chat.agent :as chat-agent]
            [dvergr.discourse :as discourse]
            [dvergr.model.chat :as chat]
            [dvergr.model.providers :as providers]
            [dvergr.room.store.memory :as memory]
            [dvergr.room.registry :as registry]
            [dvergr.sandbox.ns.agent :as agent-ns]
            [sci.core :as sci]
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

(deftest llm-resume-inputs-track-execution-semantics-without-native-owners
  (let [inputs #(@#'program/llm-inputs "Fixed instructions" {} {:provider :test :model "fixed"} nil #{})
        before (inputs)
        after (with-redefs [program/interpreter-version (inc program/interpreter-version)] (inputs))]
    (is (= program/interpreter-version (:interpreter-version before)))
    (is (not= (program/inputs-id before) (program/inputs-id after)))
    (is (roster/data-value? before))
    (is (not (contains? before :control-room)))))

(deftest a-missing-explicit-control-owner-never-falls-back-to-world-ancestry
  (let [room (discourse/make-room {:id :runner-authority/nil-control :store (memory/make)})]
    (try
      (binding [ec/*execution-context* (:ctx room)]
        (registry/register! room)
        (let [ctx (sci/init {})]
          (agent-ns/add-programming-ns! ctx (:id room) (:ctx room)
                                        {:parent-run (random-uuid) :control-room nil})
          (is (thrown-with-msg? Exception #"Run has no original control owner"
                                (sci/eval-string* ctx "(dvergr.agent/balance)")))
          (is (empty? (run/runs room {:limit 10})))
          (is (empty? (jobs/jobs room)))))
      (finally (discourse/close-room! room)))))

(deftest sci-child-runs-stay-with-the-original-control-owner-of-a-clean-fixture
  (let [control (discourse/make-room {:id :runner-authority/original-control :store (memory/make)})
        fixture (discourse/make-room {:id :runner-authority/independent-fixture :store (memory/make)})
        calls (atom 0)
        opts (-> (options {:id :candidate :tools #{:clojure_eval}
                           :model-policy {:provider :test :model "composite"}
                           :program {:kind :llm :max-model-steps 2 :auto-compact? false}})
                 (assoc :world-parent fixture)
                 (assoc-in [:delegation-policy :tools] #{:clojure_eval}))
        code "(let [team (dvergr.agent/make-agent (dvergr.agent/roster) {:id :child :program {:kind :echo}})
                    result @(dvergr.agent/hire! team :child {:task :child-task :settlement :discard})]
                (assert (= :child-task (:run/value result)))
                (assert (= (:run/id result) (:run/id (dvergr.agent/observe (:run/id result))))))"]
    (try
      (with-redefs [providers/ensure-initialized! (constantly nil)
                    chat-agent/messages->api-format (fn [messages _ _] messages)
                    chat/chat (fn [& _]
                                (if (= 1 (swap! calls inc))
                                  {:content "" :stop-reason :tool-use :usage {:input-tokens 0 :output-tokens 0}
                                   :tool-calls [{:id "child" :name "clojure_eval" :input {:code code}}]}
                                  {:content "done" :stop-reason :end-turn :usage {:input-tokens 0 :output-tokens 0}}))]
        ;; Spins execute in the caller's selected context. The original
        ;; control Room owns records; the independent fixture owns execution.
        (binding [ec/*execution-context* (:ctx fixture)]
          (let [result @(runner/run-in control opts)
                runs (run/runs control {:limit 10})
                child (first (filter #(= :child (:run/actor %)) runs))]
            (is (= 1 (count (:attempts result))))
            (is (= 2 @calls) (pr-str (:results result)))
            (is (= 2 (count runs)))
            (is (= (:attempt/run-id (first (:attempts result))) (:run/parent child)))
            (is (= :completed (:run/status child)))
            (is (= :discarded (:run/settlement-status child)))
            (is (empty? (run/runs fixture {:limit 10}))))))
      (finally
        (evaluation/await-cleanups! control 10000)
        (discourse/close-room! fixture)
        (discourse/close-room! control)))))
