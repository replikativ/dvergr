(ns dvergr.agent.experiment-observation-test
  (:require [clojure.test :refer [deftest is]]
            [dvergr.agent.program-test :as fixtures]
            [dvergr.agent.environment :as environment]
            [dvergr.agent.evaluation :as evaluation]
            [dvergr.agent.experiment :as experiment]
            [dvergr.agent.experiment-observation :as observation]
            [dvergr.agent.episode :as episode]
            [dvergr.agent.public-feedback :as feedback]
            [dvergr.agent.invocations :as invocations]
            [dvergr.agent.roster :as roster]
            [dvergr.agent.run :as run]
            [dvergr.agent.subexperiment :as subexperiment]
            [dvergr.jobs :as jobs]
            [dvergr.room.store :as store]
            [org.replikativ.spindel.core :as sp]
            [org.replikativ.spindel.engine.core :as ec]))

(defn- reason [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (or (:reason (ex-data e)) (:type (ex-data e))))))

(deftest exact-child-result-is-repeatable-scoped-and-causal
  (let [[room conn] (#'fixtures/resource-test-room :observed-experiments)
        parent (:run/id (run/start! room :parent (random-uuid) nil))
        other (:run/id (run/start! room :other (random-uuid) nil))
        evaluator (evaluation/make-evaluator
                   {:id :observed/check :version 1 :observe (fn [{:keys [default]}] default)
                    :verify (fn [_ _] {:checks {:correct true} :reward 1.0})})
        team (roster/make-agent (roster/make-roster) {:id :echo :program {:kind :echo}})
        env (environment/make-environment
             {:id :observed/case :task {:private-task "must-not-leak"}
              :verifier {:id :observed/check :version 1} :world {:isolation :ctx :settlement :discard}})
        definition (experiment/make-experiment
                    {:id :observed/test :dataset (experiment/make-dataset {:id :observed/data :environments [env]})
                     :candidates [(roster/agent team :echo)]})
        offer {:invocation/id :observed/exact :invocation/version 1 :invocation/basis {:revision (random-uuid)}}
        authority {:parent-run parent :control-room room :program-kinds #{:echo}
                   :experiment-capabilities {:evaluators {(evaluation/evaluator-ref evaluator) evaluator}
                                             :invocations {offer (fn [& _] (throw (AssertionError. "Observation readmitted work")))}}}
        context {:control-room room :run-id parent :agent-program-ceiling authority
                 :deadline-nanos (+ (System/nanoTime) 30000000000)}
        start #(binding [ec/*execution-context* (:ctx room)]
                 (subexperiment/start! room room team definition {} authority
                                       {:observation-ref offer :return-receipt? true}))]
    (try
      (let [receipt (start)
            id (:job receipt)
            _ (is (= parent (:owner-run receipt)))
            first-result (observation/await! context id 0)
            second-receipt (start)
            second-result (observation/await! context (:job second-receipt) 0)]
        (is (:settled? first-result))
        (is (= :available (:result-state first-result)))
        (is (= 1.0 (get-in first-result [:entries 0 :reward])))
        (is (= :completed (get-in first-result [:entries 0 :run/status])))
        (is (not (contains? (first (:entries first-result)) :feedback))
            "existing offers disclose no grading criteria")
        (is (not (.contains (pr-str first-result) "must-not-leak")))
        (let [reader experiment/scorecard-bounded]
          (with-redefs [experiment/scorecard-bounded
                        (fn [& args]
                          (update (apply reader args) :scorecard/entries
                                  #(mapv (fn [entry] (assoc-in entry [:spend :private-note] "PRIVATE-SPEND-SENTINEL")) %)))]
            (is (not (.contains (pr-str (observation/await! context id 0)) "PRIVATE-SPEND-SENTINEL")))))
        (is (not= (:scorecard-id first-result) (:scorecard-id second-result))
            "two invocations of the same experiment retain their own exact attempts")
        (is (= first-result (observation/await! context id 0)))
        (is (= #{id (:job second-receipt)} (set (map :job (:children (observation/children context 0))))))
        (is (contains? (:run/caused-by (run/run room parent)) id))
        (is (= :not-admitted-child
               (reason #(observation/await! (-> context (assoc :run-id other)
                                                (assoc-in [:agent-program-ceiling :parent-run] other)) id 0))))
        (is (= :not-admitted-child
               (reason #(observation/await! (assoc-in context [:agent-program-ceiling :experiment-capabilities :invocations]
                                                      {(assoc offer :invocation/version 2) (constantly nil)}) id 0))))
        (is (= :owner-expired (reason #(observation/await! (assoc context :deadline-nanos 0) id 0))))
        (let [row (run/run room id)]
          (is (= (invocations/content-id offer) (:run/job-offer row)))
          (is (= (:experiment/content-id definition) (:run/job-experiment row)))
          (is (= (:scorecard-id first-result) (:run/job-scorecard row)))
          (is (= :room-store/immutable-job-result
                 (reason #(store/-store-run! (:store room) (store/conversation-id room)
                                             (assoc row :run/job-scorecard (:scorecard-id second-result))))))))
      (finally
        (run/await-family! room parent)
        (run/finish! parent :completed)
        (run/finish! other :completed)
        (#'fixtures/close-resource-test-room! room conn)))))

(deftest explicit-public-checks-are-bound-to-the-exact-offer-and-certified-cell
  (let [[room conn] (#'fixtures/resource-test-room :public-feedback)
        parent (:run/id (run/start! room :parent (random-uuid) nil))
        evaluator (evaluation/make-evaluator
                   {:id :feedback/check :version 1 :basis "PRIVATE-VERIFIER-BASIS"
                    :observe (fn [{:keys [default]}] (assoc default :private "PRIVATE-EVIDENCE"))
                    :verify (fn [_ _] {:checks {:allowed false :secret true} :reward 0.0})})
        disclosure {:version 1 :verifier (evaluation/evaluator-ref evaluator)
                    :checks {:allowed "The public predicate held." :absent "May be unavailable."}}
        offer {:invocation/id :feedback/exact :invocation/version 1 :invocation/basis :fixed
               :invocation/public-feedback disclosure}
        authority {:parent-run parent :control-room room :program-kinds #{:echo}
                   :experiment-capabilities {:evaluators {(evaluation/evaluator-ref evaluator) evaluator}
                                             :invocations {offer (constantly nil)}}}
        ctx {:control-room room :run-id parent :agent-program-ceiling authority
             :deadline-nanos (+ (System/nanoTime) 30000000000)}
        team (roster/make-agent (roster/make-roster) {:id :echo :program {:kind :echo}})
        env (environment/make-environment
             {:id :feedback/case :task {:secret "PRIVATE-TASK"}
              :verifier {:id :feedback/check :version 1 :basis "PRIVATE-VERIFIER-BASIS"}
              :world {:isolation :ctx :settlement :discard}})
        definition (experiment/make-experiment
                    {:id :feedback/experiment
                     :dataset (experiment/make-dataset {:id :feedback/data :environments [env]})
                     :candidates [(roster/agent team :echo)]})]
    (try
      (let [receipt (binding [ec/*execution-context* (:ctx room)]
                      (subexperiment/start! room room team definition {} authority
                                            {:observation-ref offer :return-receipt? true}))
            result (observation/await! ctx (:job receipt) 0)
            row (first (:entries result))
            card (experiment/scorecard-bounded room (:scorecard-id result))
            entry (first (:scorecard/entries card))
            certified (episode/attempt-bounded room (:attempt/id entry))]
        (is (= (feedback/public-declaration disclosure) (:public-feedback result)))
        (is (not= (:verifier-content-id (:public-feedback result))
                  (:verifier-content-id (feedback/public-declaration
                                         (assoc-in disclosure [:verifier :verifier/basis] :changed)))))
        (is (= {:checks {:allowed false} :unavailable-checks [:absent]} (:feedback row)))
        (is (= :completed (:attempt/status row) (:run/status row)))
        (is (= :discarded (:run/settlement-status row)))
        (doseq [private ["PRIVATE-EVIDENCE" "PRIVATE-TASK" "PRIVATE-VERIFIER-BASIS" ":secret"]]
          (is (not (.contains (pr-str result) private))))
        (is (= result (observation/await! ctx (:job receipt) 0)))
        (is (not= (invocations/content-id offer)
                  (invocations/content-id (assoc-in offer [:invocation/public-feedback :checks :allowed] "Changed meaning."))))
        (is (= :not-admitted-child
               (reason #(observation/await!
                         (assoc-in ctx [:agent-program-ceiling :experiment-capabilities :invocations]
                                   {(assoc-in offer [:invocation/public-feedback :checks :allowed] "Changed meaning.") (constantly nil)})
                         (:job receipt) 0))))
        (is (= :feedback-binding-mismatch
               (reason #(feedback/project (assoc-in disclosure [:verifier :verifier/basis] :foreign)
                                          (:experiment/content-id definition) entry certified))))
        (doseq [mismatch [(assoc entry :attempt/id (random-uuid))
                          (assoc entry :attempt/content-id (random-uuid))
                          (assoc entry :candidate/agent-content-id (random-uuid))
                          (assoc-in entry [:environment :environment/content-id] (random-uuid))
                          (assoc entry :repetition 12)]]
          (is (= :feedback-binding-mismatch
                 (reason #(feedback/project disclosure (:experiment/content-id definition) mismatch certified))))))
      (finally
        (run/await-family! room parent)
        (run/finish! parent :completed)
        (#'fixtures/close-resource-test-room! room conn)))))

(deftest public-feedback-declarations-and-pages-are-bounded
  (let [declaration {:version 1 :verifier {:verifier/id :feedback/check :verifier/version 1}
                     :checks {:ok "A public criterion."}}
        too-many (into {} (for [i (range 17)] [(keyword (str "c" i)) "Criterion"]))
        too-long (into {} (for [i (range 16)] [(keyword (str "c" i)) (apply str (repeat 512 "x"))]))]
    (is (feedback/valid? declaration))
    (is (= {:run/status :unavailable :run/settlement-status :unavailable}
           (feedback/run-state {:run/status :PRIVATE-STATUS :run/settlement-status :PRIVATE-SETTLEMENT})))
    (is (= :unavailable (feedback/execution-status :PRIVATE-ATTEMPT-STATUS)))
    (is (= :adopted (:run/settlement-status (feedback/run-state {:run/settlement-status :adopted}))))
    (doseq [invalid [(assoc declaration :private "not admitted")
                     (assoc declaration :checks {})
                     (assoc declaration :checks {:ok "  "})
                     (assoc declaration :checks too-many)
                     (assoc declaration :checks too-long)]]
      (is (not (feedback/valid? invalid))))
    (is (not (invocations/valid-ref? {:invocation/id :feedback/check :invocation/version 1
                                      :invocation/basis :fixed :invocation/public-feedback nil})))
    (let [checks (into {} (for [i (range 16)] [(keyword (str i (apply str (repeat 70 "界")))) true]))
          declaration (assoc declaration :checks (into {} (map (fn [[k _]] [k "x"])) checks))
          entry {:feedback {:checks checks}}
          page {:job (random-uuid) :status :completed :settled? true :result-state :available
                :scorecard-id (random-uuid) :entry-count 20 :offset 4
                :public-feedback declaration :entries (vec (repeat 8 entry))}
          packed (#'observation/bounded-page page)]
      (is (feedback/valid? declaration))
      (is (<= (alength (.getBytes (pr-str packed) "UTF-8")) 24000))
      (is (seq (:entries packed)))
      (is (< (count (:entries packed)) 8))
      (is (= (+ 4 (count (:entries packed))) (:next-offset packed))))))

(deftest incomplete-and-failed-outcomes-settle-without-inventing-a-scorecard
  (let [[room conn] (#'fixtures/resource-test-room :observed-incomplete)
        parent (:run/id (run/start! room :parent (random-uuid) nil))
        offer {:invocation/id :observed/incomplete :invocation/version 1 :invocation/basis :fixed}
        ctx {:control-room room :run-id parent :deadline-nanos (+ (System/nanoTime) 10000000000)
             :agent-program-ceiling {:control-room room :parent-run parent
                                     :experiment-capabilities {:invocations {offer (constantly nil)}}}}]
    (try
      (doseq [[failure? expected] [[false :incomplete] [true :unavailable]]]
        (let [job (jobs/start! room {:kind :experiment :parent parent
                                     :observation {:offer (invocations/content-id offer) :experiment (random-uuid)}}
                               (fn [_] (sp/spin (if failure? (throw (ex-info "failed cell" {}))
                                                    {:scorecard {:incomplete {:missing 1}}}))))
              result (observation/await! ctx (:id job) 0)]
          (is (:settled? result))
          (is (= expected (:result-state result)))
          (is (not (contains? result :scorecard-id)))))
      (finally
        (run/await-family! room parent)
        (run/finish! parent :completed)
        (#'fixtures/close-resource-test-room! room conn)))))

(defn- exercise-model-await [mode]
  (let [[room conn] (#'fixtures/resource-test-room :await-model-worker)
        release (promise) entered (promise) receipt (atom nil) calls (atom 0)
        eval-timeout @dvergr.tools/eval-timeout-ms
        original-await observation/await!
        evaluator (evaluation/make-evaluator
                   {:id :observed/slow :version 1 :observe (fn [{:keys [default]}] default)
                    :verify (fn [_ _]
                              (loop [] (when-not (try @release true (catch InterruptedException _ false)) (recur)))
                              {:checks {:correct true} :reward 1.0})})
        offer {:invocation/id :observed/slow :invocation/version 1 :invocation/basis :v1}
        handler (fn [{:keys [control-room work-room authority]} team opts]
                  (let [env (environment/make-environment
                             {:id :observed/slow :task :work :verifier {:id :observed/slow :version 1}
                              :world {:isolation :ctx :settlement :discard}})
                        definition (experiment/make-experiment
                                    {:id :observed/slow
                                     :dataset (experiment/make-dataset {:id :observed/slow :environments [env]})
                                     :candidates (roster/agents team)})
                        result (subexperiment/start!
                                control-room work-room team definition opts
                                (assoc authority :experiment-capabilities
                                       {:evaluators {(evaluation/evaluator-ref evaluator) evaluator}})
                                {:observation-ref offer :return-receipt? true})]
                    (reset! receipt result) result))
        code "(require '[dvergr.agent :as agent]) (agent/invoke-experiment! (first (:invocations (agent/offered))) (agent/make-agent (agent/roster) {:id :child :tools #{} :program {:kind :echo}}) {})"
        team (roster/make-agent (roster/make-roster)
                                {:id :coordinator :tools #{:clojure_eval :await_child}
                                 :model-policy {:provider :test :model "coordinator"}
                                 :program {:kind :llm :max-model-steps 3 :auto-compact? false}})]
    (try
      (dvergr.resource/install-unit! room {:symbol dvergr.resource/model-dispatches :name "Model dispatches" :precision 0})
      (dvergr.resource/mint! room {:id (random-uuid) :resources {"microUSD" 100M "model-dispatch" 3M}})
      (with-redefs [dvergr.model.providers/ensure-initialized! (constantly nil)
                    dvergr.chat.agent/messages->api-format (fn [messages _ _] messages)
                    observation/await! (fn [& args] (deliver entered true) (apply original-await args))
                    dvergr.model.chat/chat
                    (fn [_ _]
                      (dvergr.resource/admit-model-dispatch!)
                      (let [n (swap! calls inc)]
                        (merge {:usage {:input-tokens 0 :output-tokens 0}}
                               (case n
                                 1 {:content "" :stop-reason :tool-use
                                    :tool-calls [{:id "start" :name "clojure_eval" :input {:code code}}]}
                                 2 (do (reset! dvergr.tools/eval-timeout-ms 1)
                                       {:content "" :stop-reason :tool-use
                                        :tool-calls [{:id "await" :name "await_child" :input {:job (str (:job @receipt))}}]})
                                 3 {:content (str "Benchmark report: " (:job @receipt)) :stop-reason :end-turn}
                                 (throw (AssertionError. "Unexpected inference while waiting"))))))]
        (let [deadline (+ (System/nanoTime) (if (= mode :deadline) 3000000000 15000000000))
              releaser (when (= mode :complete)
                         (future (when (= true (deref entered 10000 false)) (Thread/sleep 100)) (deliver release true)))]
          (try
            (binding [ec/*execution-context* (:ctx room)]
              (let [handle (dvergr.agent.program/hire-prepared-in!
                            room room team :coordinator
                            {:task :benchmark-and-report :settlement :discard
                             :resources {"microUSD" 100M "model-dispatch" 3M}}
                            nil nil {:deadline-nanos deadline
                                     :experiment-capabilities {:invocations {offer handler}}
                                     :delegation-policy {:tools #{:clojure_eval :await_child} :sandboxes #{#{}}
                                                         :providers #{:test} :max-attempts 1 :max-parallelism 1}})
                    _ (when-not (= mode :complete)
                        (is (= true (deref entered 10000 false)))
                        (if (= mode :cancel)
                          (dvergr.agent.program/cancel! handle)
                          (while (< (System/nanoTime) deadline) (Thread/sleep 25)))
                        (is (= ::pending (deref handle 100 ::pending)) "uncooperative verifier retains parent custody")
                        (is (some #(= (:job @receipt) (:run/id %)) (run/active-runs (:id room))))
                        (deliver release true))
                    result (deref handle 20000 ::timeout)]
                (is (= ({:complete :completed :cancel :cancelled :deadline :failed} mode) (:run/status result)) (pr-str result))
                (is (= true (deref entered 1 false)))
                (is (= (if (= mode :complete) 3 2) @calls) "no polling and no inference after cancellation/deadline")
                (is (:settled? (jobs/status-in room (:job @receipt))))
                (when (= mode :complete)
                  (is (contains? (:run/caused-by (run/run room (:run/id result))) (:job @receipt))))))
            (finally (deliver release true) (when releaser @releaser)))))
      (finally
        (reset! dvergr.tools/eval-timeout-ms eval-timeout)
        (deliver release true)
        (#'fixtures/close-resource-test-room! room conn)))))

(deftest model-waits-once-outside-sci-timeout-and-resumes-reporting
  (exercise-model-await :complete))

(deftest cancellation-during-native-await-retains-uncooperative-child
  (exercise-model-await :cancel))

(deftest deadline-during-native-await-prevents-further-inference
  (exercise-model-await :deadline))
