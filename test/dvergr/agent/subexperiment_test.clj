(ns dvergr.agent.subexperiment-test
  (:require [clojure.test :refer [deftest is]]
            [dvergr.agent.program-test :as fixtures]
            [dvergr.agent.program :as program]
            [dvergr.agent.environment :as environment]
            [dvergr.agent.evaluation :as evaluation]
            [dvergr.agent.experiment :as experiment]
            [dvergr.agent.roster :as roster]
            [dvergr.agent.run :as run]
            [dvergr.agent.subexperiment :as subexperiment]
            [dvergr.agent.invocations :as invocations]
            [dvergr.chat.agent :as chat-agent]
            [dvergr.discourse :as d]
            [dvergr.jobs :as jobs]
            [dvergr.model.chat :as chat]
            [dvergr.model.providers :as providers]
            [dvergr.resource :as resource]
            [dvergr.room.registry :as registry]
            [dvergr.room.store.memory :as memory]
            [org.replikativ.spindel.engine.core :as ec]))

(deftest job-ownership-needs-both-publication-and-durable-terminality
  (doseq [publication-first? [true false]]
    (let [room (d/make-room {:id (keyword (str "job-frontiers-" publication-first?))
                             :store (memory/make)})
          parent (binding [ec/*execution-context* (:ctx room)]
                   (:run/id (run/start! room :parent (random-uuid) nil)))
          supervisor (binding [ec/*execution-context* (:ctx room)]
                       (assoc (#'program/make-supervisor (:ctx room)) :run-id parent :control-room room))
          published (atom nil) child (random-uuid)]
      (try
        (binding [ec/*execution-context* (:ctx room)]
          (#'program/with-owned-child!
           supervisor
           #(do (run/start! room :job (random-uuid) nil {:id child :parent parent})
                {:run/id child ::program/cancel! (constantly nil)
                 ::program/on-settled! (fn [f] (reset! published f))}))
          (is (= 1 (count (:children @(:state supervisor)))))
          (if publication-first? (@published) (run/finish! child :completed))
          (is (= 1 (count (:children @(:state supervisor))))
              "one frontier alone is insufficient")
          (if publication-first? (run/finish! child :completed) (@published))
          (run/await-family! room parent)
          (is (empty? (:children @(:state supervisor)))))
        (finally
          (when (some #(= child (:run/id %)) (run/active-runs (:id room)))
            (run/finish! child :cancelled))
          (when @published (@published))
          (run/await-family! room parent)
          (run/finish! parent :completed)
          (d/close-room! room))))))

(def nested-code
  "(require '[dvergr.agent :as agent])
   (let [team (agent/make-agent (agent/roster)
                {:id :child :tools #{} :model-policy {:provider :codex-subscription :model \"codex-subscription-luna\"}
                 :program {:kind :llm :max-model-steps 1 :auto-compact? false}})
         env (agent/environment {:id :test/owned :task :work :verifier {:id :test/owned :version 1}
               :limits {:timeout-ms 10000 :cancel-timeout-ms 5000}
               :world {:isolation :ctx :settlement :discard :resources {\"microUSD\" 100M \"model-dispatch\" 1M}}})
         exp (agent/experiment {:id :test/owned :dataset (agent/dataset {:id :test/owned :environments [env]})
                                :candidates [(agent/lookup team :child)] :repetitions 2})]
      (agent/run-experiment! team exp {:resources {\"microUSD\" 200M \"model-dispatch\" 2M}})
      :started)")

(defn- exercise-owned-experiment [cancel?]
  (let [[room conn] (#'fixtures/resource-test-room :owned-subexperiment)
        child-entered (promise) release (promise) parent-done (promise)
        parent-calls (atom 0) dispatches (atom 0)
        seen-policies (atom {})
        execute-turn chat-agent/run-agent-turn!
        policy {:tools #{:clojure_eval} :sandboxes #{#{}}
                :providers #{:test :codex-subscription} :max-attempts 2 :max-parallelism 1}
        scorer (evaluation/make-evaluator
                {:id :test/owned :version 1 :observe (fn [{:keys [default]}] default)
                 :verify (fn [_ _] {:checks {:done true} :reward 1.0})})
        scope {:evaluators {(evaluation/evaluator-ref scorer) scorer} :world-setups {}}
        team (roster/make-agent (roster/make-roster)
                                {:id :parent :tools #{:clojure_eval}
                                 :model-policy {:provider :test :model "parent"}
                                 :program {:kind :llm :max-model-steps 2 :auto-compact? false}})]
    (try
      (binding [ec/*execution-context* (:ctx room)] (registry/register! room))
      (resource/install-unit! room {:symbol resource/model-dispatches :name "Model dispatches" :precision 0})
      (resource/mint! room {:id (random-uuid) :resources {"microUSD" 300M "model-dispatch" 6M}})
      (with-redefs [providers/ensure-initialized! (constantly nil)
                    chat-agent/messages->api-format (fn [messages _ _] messages)
                    chat-agent/run-agent-turn! (fn [ctx opts]
                                                 (swap! seen-policies assoc (:model opts)
                                                        (get-in opts [:tool-ctx :agent-program-ceiling :delegation-policy]))
                                                 (execute-turn ctx opts))
                    chat/chat
                    (fn [_ opts]
                      (resource/admit-model-dispatch!)
                      (swap! dispatches inc)
                      (if (= "parent" (:model opts))
                        (if (= 1 (swap! parent-calls inc))
                          {:content "" :tool-calls [{:id "nested" :name "clojure_eval" :input {:code nested-code}}]
                           :usage {:input-tokens 0 :output-tokens 0} :stop-reason :tool-use}
                          (do (deliver parent-done true)
                              {:content "parent done" :usage {:input-tokens 0 :output-tokens 0} :stop-reason :end-turn}))
                        (do (deliver child-entered true)
                            ;; Simulate a physical worker that does not stop
                            ;; merely because its future was interrupted.
                            (loop []
                              (when-not (try @release true (catch InterruptedException _ false))
                                (recur)))
                            {:content "child done" :usage {:input-tokens 0 :output-tokens 0} :stop-reason :end-turn})))]
        (binding [ec/*execution-context* (:ctx room)]
          (let [handle (program/hire-in!
                        room room team :parent {:task :coordinate :settlement :discard
                                                :resources {"microUSD" 300M "model-dispatch" 6M}}
                        {:experiment-capabilities scope
                         :delegation-policy policy})]
            (try
              (is (= true (deref child-entered 10000 ::timeout)))
              (is (= true (deref parent-done 10000 ::timeout)))
              (when cancel? (is (true? (program/cancel! handle))))
              (is (= ::timeout (deref handle 100 ::timeout))
                  "ignoring the experiment Spin cannot release the parent")
              (is (= {} (resource/balance room)))
              (finally (deliver release true)))
            (is (= (if cancel? :cancelled :completed) (:run/status (deref handle 10000 ::timeout))))
            (is (= 2 (count @seen-policies)))
            (is (contains? @seen-policies "parent"))
            (is (every? #(= policy %) (vals @seen-policies)))
            (is (= {"microUSD" 300M "model-dispatch" (bigdec (- 6 @dispatches))} (resource/balance room)))
            (is (empty? (run/active-runs (:id room)))))))
      (finally
        (deliver release true)
        (#'fixtures/close-resource-test-room! room conn)))))

(deftest ignored-child-experiment-is-owned-until-physical-cleanup
  (exercise-owned-experiment false))

(deftest cancellation-does-not-refund-a-still-live-child-experiment
  (exercise-owned-experiment true))

(deftest ambiguous-job-allocation-is-reconciled-before-failed-admission-returns
  (let [[room conn] (#'fixtures/resource-test-room :ambiguous-subexperiment-allocation)
        grant {"microUSD" 10M}
        team (roster/make-agent (roster/make-roster) {:id :cell :program {:kind :echo}})
        scorer (evaluation/make-evaluator
                {:id :test/ambiguous :version 1 :observe identity
                 :verify (fn [_ _] {:reward 1.0})})
        env (environment/make-environment
             {:id :test/ambiguous :task :work :verifier {:id :test/ambiguous :version 1}
              :world {:isolation :ctx :settlement :discard :resources grant}})
        definition (experiment/make-experiment
                    {:id :test/ambiguous :dataset (experiment/make-dataset {:id :test/ambiguous :environments [env]})
                     :candidates [(roster/agent team :cell)]})
        allocate! resource/allocate-run!]
    (try
      (resource/mint! room {:id (random-uuid) :resources grant})
      (with-redefs [resource/allocate-run! (fn [& args]
                                             (apply allocate! args)
                                             (throw (ex-info "lost allocation acknowledgement" {})))]
        (binding [ec/*execution-context* (:ctx room)]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"lost allocation acknowledgement"
                                (subexperiment/start!
                                 room room team definition {:resources grant}
                                 {:experiment-capabilities {:evaluators {(evaluation/evaluator-ref scorer) scorer}
                                                            :world-setups {}}})))))
      (is (= grant (resource/balance room)))
      (is (empty? (run/active-runs (:id room))))
      (binding [ec/*execution-context* (:ctx room)]
        (let [job (first (run/runs room))]
          (is (= :failed (:run/status job)))
          (is (= {} (resource/run-balance room (:run/id job))))))
      (finally (#'fixtures/close-resource-test-room! room conn)))))

(deftest child-experiment-delegates-a-real-wallet-and-returns-only-unused-resources
  (let [[room conn] (#'fixtures/resource-test-room :funded-subexperiment)
        parent (:run/id (run/start! room :parent (random-uuid) nil))
        grant {"microUSD" 200M "model-dispatch" 4M}
        scorer (evaluation/make-evaluator
                {:id :test/funded :version 1 :observe (fn [{:keys [default]}] default)
                 :verify (fn [_ _] {:checks {:done true} :reward 1.0})})
        scope {:evaluators {(evaluation/evaluator-ref scorer) scorer} :world-setups {}}
        team (roster/make-agent (roster/make-roster)
                                {:id :candidate :model-policy {:provider :test :model "stub"}
                                 :tools #{} :program {:kind :llm :max-model-steps 1 :auto-compact? false}})
        env (environment/make-environment
             {:id :test/funded :task "finish" :verifier {:id :test/funded :version 1}
              :limits {:timeout-ms 10000 :cancel-timeout-ms 5000}
              :world {:isolation :ctx :settlement :discard
                      :resources {"microUSD" 100M "model-dispatch" 2M}}})
        definition (experiment/make-experiment
                    {:id :test/child :dataset (experiment/make-dataset {:id :test/data :environments [env]})
                     :candidates [(roster/agent team :candidate)] :repetitions 2})
        calls (atom [])]
    (try
      (binding [ec/*execution-context* (:ctx room)] (registry/register! room))
      (resource/install-unit! room {:symbol resource/model-dispatches :name "Model dispatches" :precision 0})
      (resource/mint! room {:id (random-uuid) :resources grant})
      (resource/allocate-run! room parent nil grant)
      (with-redefs [providers/ensure-initialized! (constantly nil)
                    chat-agent/messages->api-format (fn [messages _ _] messages)
                    chat/chat (fn [_ _]
                                (swap! calls conj (:run-id resource/*model-scope*))
                                ;; Exercise the conserved admission, without
                                ;; making a provider request or inventing a bill.
                                (resource/admit-model-dispatch!)
                                {:content "done" :usage {:input-tokens 0 :output-tokens 0}
                                 :tool-calls nil :stop-reason :end-turn})]
        (binding [ec/*execution-context* (:ctx room)]
          (let [result @(subexperiment/start!
                         room room team definition {:resources grant}
                         {:parent-run parent :program-kinds #{:echo :scripted}
                          :funded-program-kinds #{:llm} :funded-providers #{:test}
                          :experiment-capabilities scope})
                tree (run/runs room {:root-run-id parent})]
            (is (= :completed (:status result)))
            (is (= 2 (count (get-in result [:scorecard :scorecard/entries]))))
            (is (= 2 (count @calls)))
            (is (= 2 (count (set @calls))))
            (is (= 4 (count tree)))
            (is (= {"microUSD" 200M "model-dispatch" 2M} (resource/run-balance room parent)))
            (is (= {} (resource/run-balance room (:job result))))
            (is (every? #(empty? (resource/run-balance room %)) @calls))
            ;; Publication precedes adapter ACK persistence and native release.
            (is (:settled? (jobs/status (:job result) 5000)))
            (is (= 1 (count (run/active-runs (:id room))))))))
      (finally
        (run/finish! parent :completed)
        (#'fixtures/close-resource-test-room! room conn)))))

(deftest host-clean-parent-keeps-accounting-and-result-in-the-caller
  (let [[control conn] (#'fixtures/resource-test-room :clean-parent-control)
        [clean clean-conn] (#'fixtures/resource-test-room :clean-parent-fixture)
        parent (:run/id (run/start! control :parent (random-uuid) nil))
        grant {"microUSD" 20M}
        seen (atom [])
        setup (evaluation/make-world-setup
               {:id :test/clean :version 1 :basis :clean-origin
                :prepare (fn [{:keys [room]}]
                           (swap! seen conj (:parent-id room))
                           {:parent (:parent-id room)})})
        scorer (evaluation/make-evaluator
                {:id :test/clean :version 1
                 :observe (fn [{:keys [default setup/evidence]}] (assoc default :clean-parent (:parent evidence)))
                 :verify (fn [_ observed] {:checks {:clean? (= (:id clean) (:clean-parent observed))}
                                           :reward (if (= (:id clean) (:clean-parent observed)) 1.0 0.0)})})
        team (roster/make-agent (roster/make-roster) {:id :candidate :program {:kind :echo}})
        env (environment/make-environment
             {:id :test/clean :task "clean-parent test" :verifier {:id :test/clean :version 1}
              :world {:isolation :ctx :settlement :discard :setup (evaluation/world-setup-ref setup)
                      :resources {"microUSD" 10M}}})
        definition (experiment/make-experiment
                    {:id :test/clean :dataset (experiment/make-dataset {:id :test/clean :environments [env]})
                     :candidates [(roster/agent team :candidate)]})
        scope {:evaluators {(evaluation/evaluator-ref scorer) scorer}
               :world-setups {(evaluation/world-setup-ref setup) setup}}
        offer {:invocation/id :test/clean-benchmark :invocation/version 1 :invocation/basis :fixture}
        handler (fn [{:keys [control-room work-room authority]} team opts]
                  (subexperiment/start! control-room work-room team definition opts
                                        (assoc authority :experiment-capabilities scope)
                                        {:world-parent clean}))
        authority {:parent-run parent :experiment-capabilities (assoc scope :invocations {offer handler})}]
    (try
      (binding [ec/*execution-context* (:ctx control)] (registry/register! control))
      (binding [ec/*execution-context* (:ctx clean)] (registry/register! clean))
      (resource/mint! control {:id (random-uuid) :resources grant})
      (resource/allocate-run! control parent nil grant)
      (binding [ec/*execution-context* (:ctx control)]
        (let [result @(invocations/invoke! control control authority offer team {:resources grant})]
          (is (= :completed (:status result)))
          (is (= [(:id clean)] @seen))
          (is (= 1.0 (get-in result [:scorecard :scorecard/entries 0 :reward])))
          (is (= parent (:run/parent (run/run control (:job result)))))
          (is (= grant (resource/run-balance control parent)))
          (is (empty? (run/runs clean)))
          (is (= {} (resource/balance clean)))))
      (is (thrown? Exception (subexperiment/start! control control team definition {} authority {:world-parent nil})))
      (finally
        (resource/return! control parent nil (resource/run-balance control parent))
        (run/finish! parent :completed)
        (#'fixtures/close-resource-test-room! clean clean-conn)
        (#'fixtures/close-resource-test-room! control conn)))))

(deftest host-settlement-failure-retains-capacity-and-parent-acknowledgement
  (let [[room conn] (#'fixtures/resource-test-room :host-settled-recovery)
        parent (:run/id (run/start! room :parent (random-uuid) nil))
        scorer (evaluation/make-evaluator {:id :test/host-settled :version 1
                                           :observe (fn [{:keys [default]}] default)
                                           :verify (fn [_ _] {:reward 1.0})})
        team (roster/make-agent (roster/make-roster) {:id :candidate :program {:kind :echo}})
        env (environment/make-environment {:id :test/host-settled :task :work
                                           :verifier {:id :test/host-settled :version 1}
                                           :world {:isolation :ctx :settlement :discard}})
        definition (experiment/make-experiment
                    {:id :test/host-settled
                     :dataset (experiment/make-dataset {:id :test/host-settled :environments [env]})
                     :candidates [(roster/agent team :candidate)]})
        held (promise) acknowledged (promise) job-id (atom nil) owner (atom nil)
        admissions (atom 0) settlements (atom 0) releases (atom 0)
        gate (java.util.concurrent.Semaphore. 1) acquired (java.util.concurrent.atomic.AtomicBoolean. false)
        authority {:parent-run parent
                   :experiment-capabilities {:evaluators {(evaluation/evaluator-ref scorer) scorer} :world-setups {}}
                   :own-child! (fn [admit!]
                                 (let [handle (admit!)]
                                   ((::program/on-settled! handle) #(deliver acknowledged true)) handle))}
        options {:admit! (fn [id]
                           (reset! job-id id) (swap! admissions inc)
                           (reset! owner (get-in @(var-get #'dvergr.jobs/live) [id :state]))
                           (add-watch @owner ::held
                                      (fn [_ _ _ state] (when (= :held (:phase state)) (deliver held true))))
                           (when-not (.tryAcquire gate) (throw (ex-info "Busy fixture" {})))
                           (.set acquired true))
                 :settled! (fn [_ _]
                             (when (= 1 (swap! settlements inc)) (throw (ex-info "Release failed once" {})))
                             (when (.compareAndSet acquired true false)
                               (.release gate) (swap! releases inc)))}]
    (try
      (binding [ec/*execution-context* (:ctx room)]
        (registry/register! room)
        (let [result (subexperiment/start! room room team definition {} authority options)]
          (is (true? (deref held 10000 false)))
          (is (= :settled (:stage (dvergr.jobs/recovery-state @job-id))))
          (is (false? (:settled? (dvergr.jobs/status @job-id 0))))
          (is (not (realized? acknowledged)))
          (is (= 0 (.availablePermits gate)))
          (is (= ::pending (deref result 20 ::pending)) "Failed release cannot publish a result Spin")
          (dvergr.jobs/recover! @job-id (dvergr.jobs/recovery-state @job-id) :retry)
          (is (= :completed (:status @result)))
          (is (true? (deref acknowledged 10000 false)))
          (is (= 1 @admissions @releases (.availablePermits gate)))
          (is (= 2 @settlements))))
      (finally
        (when-let [state (some-> @job-id dvergr.jobs/recovery-state)]
          (dvergr.jobs/recover! @job-id state :retry))
        (when @owner (remove-watch @owner ::held))
        (run/finish! parent :completed)
        (#'fixtures/close-resource-test-room! room conn)))))
