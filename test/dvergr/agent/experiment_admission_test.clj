(ns dvergr.agent.experiment-admission-test
  (:require [clojure.test :refer [deftest is]]
            [dvergr.agent.environment :as environment]
            [dvergr.agent.evaluation :as evaluation]
            [dvergr.agent.evaluators :as evaluators]
            [dvergr.agent.experiment :as experiment]
            [dvergr.agent.experiment-admission :as admission]
            [dvergr.agent.roster :as roster]))

(def scorer (evaluation/make-evaluator
             {:id :test/scoped :version 1 :observe identity :verify (fn [_ _] {:reward 1.0})}))
(def scope {:evaluators {(evaluation/evaluator-ref scorer) scorer} :world-setups {}})
(def ceiling {:program-kinds #{:echo :scripted} :funded-program-kinds #{:llm}
              :experiment-capabilities scope :max-attempts 8 :max-parallelism 2})

(defn definition [kind grant]
  (let [team (roster/make-agent (roster/make-roster)
                                (cond-> {:id :candidate :program {:kind kind}}
                                  (= :llm kind)
                                  (assoc :model-policy {:provider :codex-subscription
                                                        :model "codex-subscription-sol-6.1"})))
        env (environment/make-environment
             {:id :test/case :task :work :verifier {:id :test/scoped :version 1}
              :world (cond-> {:isolation :ctx :settlement :discard} (seq grant) (assoc :resources grant))})]
    {:team team
     :experiment (experiment/make-experiment
                  {:id :test/funded :dataset (experiment/make-dataset {:id :test/data :environments [env]})
                   :candidates [(roster/agent team :candidate)] :repetitions 2})}))

(deftest funded-llm-cells-need-explicit-aggregate-delegation
  (let [{:keys [team experiment]} (definition :llm {"microUSD" 100M "model-dispatch" 2M})
        grant {"microUSD" 200M "model-dispatch" 4M}
        plan #(admission/plan team experiment % ceiling)]
    (is (= grant (:required (plan {:resources grant}))))
    (is (= 2 (:cells (plan {:resources grant}))))
    (is (= scope (:capabilities (plan {:resources grant}))))
    (doseq [opts [{} {:resources {"microUSD" 199M "model-dispatch" 4M}}
                  {:resources {"microUSD" 200M "model-dispatch" 3M}}
                  {:resources grant :parallelism 3}
                  {:resources grant :parent-run (random-uuid)}
                  {:resources {"microUSD" ##Inf}}
                  {:resources {"microUSD" -1M}}]]
      (is (thrown? clojure.lang.ExceptionInfo (plan opts))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (admission/plan team experiment {:resources grant} (assoc ceiling :max-attempts 1))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (admission/plan team experiment {:resources grant} (assoc ceiling :funded-program-kinds #{}))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (admission/plan (roster/revise-agent team :candidate {:prompt "different"})
                                 experiment {:resources grant} ceiling)))))

(deftest money-without-dispatch-admissions-does-not-open-paid-cells
  (let [{:keys [team experiment]} (definition :llm {"microUSD" 100M})]
    (is (thrown? clojure.lang.ExceptionInfo
                 (admission/plan team experiment {:resources {"microUSD" 200M}} ceiling)))))

(deftest native-funding-is-required-even-for-a-trusted-unrestricted-coordinator
  (let [{:keys [team experiment]} (definition :llm {})]
    (is (thrown? clojure.lang.ExceptionInfo (admission/plan team experiment {} nil))))
  (let [{:keys [team experiment]} (definition :llm {"microUSD" 100M "model-dispatch" 2M})
        native (roster/agent team :candidate)
        team (roster/revise-agent team :candidate {:model-policy {:provider :claude-code :model "claude-code-haiku"}})
        env (first (get-in experiment [:experiment/dataset :dataset/environments]))
        cli (experiment/make-experiment
             {:id :test/cli :dataset (experiment/make-dataset {:id :test/cli :environments [env]})
              :candidates [(roster/agent team :candidate)]})]
    (is (= :llm (get-in native [:agent/program :kind])))
    (is (thrown? clojure.lang.ExceptionInfo
                 (admission/plan team cli {:resources {"microUSD" 100M "model-dispatch" 2M}} ceiling)))))

(deftest explicit-capability-scopes-do-not-fall-back-to-global-registration
  (let [{:keys [team experiment]} (definition :echo {})]
    (is (= 2 (:cells (admission/plan team experiment {} ceiling))))
    (evaluators/register-evaluator! scorer)
    (try
      (is (= [] (:evaluators (evaluators/offered {}))))
      (is (thrown? clojure.lang.ExceptionInfo (evaluators/capabilities-for experiment {})))
      (is (thrown? clojure.lang.ExceptionInfo
                   (admission/plan team experiment {} (assoc ceiling :experiment-capabilities {}))))
      (is (= scope (evaluators/capabilities-for experiment scope)))
      (finally
        (swap! (var-get #'evaluators/registry) update :evaluators dissoc (evaluation/evaluator-ref scorer))))))

(deftest scoped-capability-refs-not-runtime-functions-enter-input-identity
  (let [project (ns-resolve 'dvergr.agent.program 'with-experiment-inputs)
        inputs {:inputs/kind :llm :prompt-id :test}
        first-inputs (project inputs {:experiment-capabilities scope})
        same-refs (project inputs {:experiment-capabilities
                                   {:evaluators {(evaluation/evaluator-ref scorer) :different-host-object}}})
        empty-inputs (project inputs {:experiment-capabilities {}})]
    (is (= first-inputs same-refs))
    (is (not= first-inputs empty-inputs))
    (is (= inputs (project inputs {})))
    (is (= #{(evaluation/evaluator-ref scorer)}
           (get-in first-inputs [:experiment-capabilities :evaluators])))))
