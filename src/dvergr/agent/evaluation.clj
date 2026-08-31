(ns dvergr.agent.evaluation
  "Algorithm-neutral execution of verified EnvironmentDefs.

   Evaluation does not introduce another scheduler, world, or inference model.
   It returns a Spin which admits an ordinary isolated Run, observes its durable
   outcome, invokes a matching host-owned verifier, and produces an attempt
   receipt. Parallelism, races, quorums, and later inference policies compose
   these Spins with the existing Spindel combinators."
  (:require [dvergr.agent.environment :as environment]
            [dvergr.agent.program :as program]
            [dvergr.agent.roster :as roster]
            [dvergr.room.registry :as registry]
            [dvergr.rooms.forks :as forks]
            [hasch.core :as hasch]
            [org.replikativ.spindel.core :as sp]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.spin.combinators :as comb]
            [org.replikativ.spindel.spin.core :as spin-core]
            [org.replikativ.spindel.spin.sync :as sync]))

(defrecord Evaluator [ref observe verify])

(defn make-evaluator
  "Create a process-local trusted evaluator capability.

   `id`, `version`, and optional portable `basis` must exactly match an
   EnvironmentDef verifier reference. `observe` receives durable execution
   facts and returns portable evidence. `verify` receives the EnvironmentDef
   plus that evidence and returns `{:checks {keyword boolean} :reward number}`.
   Evaluators are deliberately not portable and are never exposed to SCI."
  [{:keys [id version basis observe verify]
    :or {version 1}}]
  (when-not (keyword? id)
    (throw (ex-info "Evaluator :id must be a keyword"
                    {:type ::invalid-evaluator-id :id id})))
  (when-not (and (integer? version) (pos? version))
    (throw (ex-info "Evaluator :version must be a positive integer"
                    {:type ::invalid-evaluator-version :version version})))
  (when-not (fn? observe)
    (throw (ex-info "Evaluator :observe must be a function"
                    {:type ::invalid-observer})))
  (when-not (fn? verify)
    (throw (ex-info "Evaluator :verify must be a function"
                    {:type ::invalid-verifier})))
  (when-not (roster/data-value? basis)
    (throw (ex-info "Evaluator :basis must contain only portable data"
                    {:type ::invalid-evaluator-basis :basis basis})))
  (->Evaluator (cond-> {:verifier/id id :verifier/version version}
                 (some? basis) (assoc :verifier/basis basis))
               observe verify))

(defn evaluator-ref
  "Return the portable verifier reference named by an Evaluator capability."
  [evaluator]
  (:ref evaluator))

(defn- require-matching-evaluator! [definition evaluator]
  (when-not (instance? Evaluator evaluator)
    (throw (ex-info "Evaluation requires a host Evaluator capability"
                    {:type ::invalid-evaluator})))
  (let [expected (:environment/verifier definition)
        actual (evaluator-ref evaluator)]
    (when-not (= expected actual)
      (throw (ex-info "Evaluator does not match the EnvironmentDef verifier"
                      {:type ::evaluator-mismatch
                       :expected expected
                       :actual actual}))))
  evaluator)

(defn- default-evidence [{:keys [result durable]}]
  {:result (:run/value result)
   :trace
   {:runs [(select-keys durable
                        [:run/id :run/parent :run/caused-by :run/actor
                         :run/status :run/error :run/settlement-status
                         :run/roster :run/agent-version :run/agent-def-hash
                         :run/program-kind :run/interpreter-version])]}})

(defn- execution-identity [agent result durable]
  (let [metrics (:run/metrics result)
        kind (or (:run/program-kind durable)
                 (get-in agent [:agent/program :kind]))
        model-policy (:agent/model-policy agent)
        resolved? (and (:provider metrics) (:model metrics))
        intended? (and (:provider model-policy) (:model model-policy))]
    {:provider (or (:provider metrics) (:provider model-policy) :dvergr)
     :model (or (:model metrics) (:model model-policy) (name kind))
     :metrics
     (merge {:program-kind kind
             :model-resolution (cond resolved? :resolved
                                     intended? :intended
                                     :else :not-applicable)
             :agent-version (or (:run/agent-version durable)
                                (:agent/version agent))
             :agent-def-hash (or (:run/agent-def-hash durable)
                                 (hasch/uuid agent))
             :interpreter-version (or (:run/interpreter-version durable)
                                      program/interpreter-version)}
            (when-let [roster-id (:run/roster durable)]
              {:roster roster-id})
            metrics)}))

(defn- positive-timeout! [label value]
  (when-not (and (integer? value) (pos? value))
    (throw (ex-info (str label " must be a positive integer")
                    {:type ::invalid-timeout :label label :value value})))
  value)

(defn- require-supported-policy! [definition agent]
  (let [limits (:environment/limits definition)
        world (:environment/world definition)
        model-limits (select-keys limits [:max-model-steps :budget-dollars])]
    (when-let [unknown (seq (remove #{:timeout-ms :cancel-timeout-ms
                                      :max-model-steps :budget-dollars}
                                    (keys limits)))]
      (throw (ex-info "Evaluation environment contains unsupported limits"
                      {:type ::unsupported-evaluation-limits
                       :unknown (set unknown)})))
    (when-let [unknown (seq (remove #{:isolation :settlement :resources}
                                    (keys world)))]
      (throw (ex-info
              "Evaluation environment contains unsupported world policy; setup requires a trusted resolver"
              {:type ::unsupported-evaluation-world
               :unknown (set unknown)})))
    (when (and (seq model-limits)
               (not= :llm (get-in agent [:agent/program :kind])))
      (throw (ex-info "Environment model limits require an LLM AgentDef"
                      {:type ::model-limits-require-llm
                       :agent/id (:agent/id agent)
                       :limits model-limits})))
    model-limits))

(defn- settle-certified! [fork requested claim!]
  (when-not fork
    (throw (ex-info "Deferred evaluation world is no longer available"
                    {:type ::missing-evaluation-world})))
  (case requested
    :review
    (forks/release-deferred! fork :evaluation-certified claim!)

    :discard
    (let [settled (forks/discard-deferred! fork :evaluation-policy claim!)]
      (when-not (:ok? settled)
        (throw (ex-info "Certified evaluation world could not be discarded"
                        {:type ::settlement-failed
                         :settlement requested
                         :fork/id (:id fork)
                         :error (:error settled)})))))
  requested)

(defn- discard-uncertified! [fork reason]
  (when fork
    (forks/discard-deferred! fork reason)))

(defn- spin-cancelled? [error]
  (loop [error error]
    (when error
      (or (= spin-core/spin-cancelled (:type (ex-data error)))
          (= "Spin cancelled" (ex-message error))
          (recur (ex-cause error))))))

(defn- certification-candidate
  [{:keys [room definition evaluator agent run-id result durable
           started-at started-nanos timeout?]}]
  (let [evidence ((:observe evaluator)
                  {:room room
                   :environment definition
                   :run-id run-id
                   :result result
                   :durable durable
                   :default (default-evidence {:result result
                                               :durable durable})})
        _ (when-not (and (map? evidence) (roster/data-value? evidence))
            (throw (ex-info "Evaluator evidence must be a portable map"
                            {:type ::invalid-evidence :evidence evidence})))
        {:keys [checks reward]} ((:verify evaluator) definition evidence)
        {:keys [provider model metrics]} (execution-identity agent result durable)
        elapsed-ms (long (/ (- (System/nanoTime) started-nanos) 1000000))
        receipt
        (environment/make-attempt-receipt
         definition
         (cond-> {:run-id run-id
                  :provider provider
                  :model model
                  :status (:run/status result)
                  :started-at started-at
                  :elapsed-ms elapsed-ms
                  :metrics (assoc metrics :timed-out? timeout?)
                  :checks checks
                  :reward reward}
           (contains? evidence :result) (assoc :result (:result evidence))
           (contains? evidence :trace) (assoc :trace (:trace evidence))
           (contains? evidence :resources) (assoc :resources
                                                  (:resources evidence))))]
    {:evidence evidence :receipt receipt}))

(defn evaluate
  "Return a Spin which evaluates one AgentDef in one EnvironmentDef.

   The Run is the causal execution identity and its ordinary RunWorld is the
   isolated scenario. Environment `:world` may specify `:settlement` and a
   conserved `:resources` vector; `:limits` may specify `:timeout-ms` and
   `:cancel-timeout-ms`. A timeout requests targeted Run cancellation and no
   receipt is certified until the Run has physically quiesced.

   Options may provide `:from` and structural `:parent-run`. The returned map
   contains portable evidence/receipt plus the process-local RunHandle; callers
   settle a retained world through the existing room-fork APIs."
  ([room team agent-ref definition evaluator]
   (evaluate room team agent-ref definition evaluator {}))
  ([room team agent-ref definition evaluator
    {:keys [from parent-run] :or {from :environment} :as opts}]
   (environment/validate-environment definition)
   (require-matching-evaluator! definition evaluator)
   (let [agent (roster/agent team agent-ref)
         {:keys [timeout-ms cancel-timeout-ms]
          :or {timeout-ms 120000 cancel-timeout-ms 10000}}
         (:environment/limits definition)
         {:keys [settlement resources]
          :or {settlement :review}}
         (:environment/world definition)
         model-limits (when agent (require-supported-policy! definition agent))]
     (when-let [unknown (seq (remove #{:from :parent-run} (keys opts)))]
       (throw (ex-info "Evaluation contains unknown options"
                       {:type ::unknown-evaluation-options
                        :unknown (set unknown)})))
     (when-not agent
       (throw (ex-info "Evaluation AgentDef does not exist in the Roster"
                       {:type ::unknown-agent :agent-ref agent-ref})))
     (positive-timeout! "Environment :timeout-ms" timeout-ms)
     (positive-timeout! "Environment :cancel-timeout-ms" cancel-timeout-ms)
     (when-not (contains? #{nil :ctx}
                          (get-in definition [:environment/world :isolation]))
       (throw (ex-info "Evaluation environments currently require :ctx isolation"
                       {:type ::unsupported-evaluation-isolation
                        :isolation (get-in definition
                                           [:environment/world :isolation])})))
     (when-not (#{:review :discard} settlement)
       (throw (ex-info
               "Evaluations must retain successful worlds for review or discard them"
               {:type ::unsafe-evaluation-settlement
                :settlement settlement
                :allowed #{:review :discard}})))
     (sp/spin
      (let [started-at (System/currentTimeMillis)
            started-nanos (System/nanoTime)
            hire-opts (cond-> {:task (:environment/task definition)
                               :from from
                               ;; Verification is a two-phase gate over this
                               ;; same RunWorld. Existing merge/adoption
                               ;; operations reject it until trusted scoring.
                               :settlement :deferred}
                        parent-run (assoc :parent-run parent-run)
                        (seq resources) (assoc :resources resources)
                        (seq model-limits) (assoc :limits model-limits))
            handle (program/hire! room team agent-ref hire-opts)
            timed-out ::timed-out
            initial (sp/await
                     (comb/timeout (program/owned-result-spin handle)
                                   timeout-ms timed-out))
            timeout? (= timed-out initial)
            _ (when timeout? (program/cancel! room handle))
            result (if timeout?
                     (sp/await
                      (comb/timeout (program/result-spin handle)
                                    cancel-timeout-ms timed-out))
                     initial)]
        (when (= timed-out result)
          (throw (ex-info "Environment Run did not quiesce after cancellation"
                          {:type ::cancellation-timeout
                           :run/id (program/run-id handle)
                           :cancel-timeout-ms cancel-timeout-ms})))
        (let [run-id (program/run-id handle)
              durable (program/observe room handle)
              fork (some-> (:run/world result) registry/lookup)
              evaluation-spin-id ec/*spin-id*
              cancelled-externally?
              #(and evaluation-spin-id
                    (ec/spin-current-result evaluation-spin-id))
              state (atom :scoring)
              cleanup-result (atom ::pending)
              cleanup-once!
              (fn [reason]
                (locking cleanup-result
                  (if (= ::pending @cleanup-result)
                    (let [cleanup (discard-uncertified! fork reason)]
                      (reset! cleanup-result cleanup)
                      cleanup)
                    @cleanup-result)))
              done (sync/deferred)
              worker
              (future
                (binding [ec/*execution-context* (:ctx room)
                          ec/*spin-id* nil]
                  (try
                    (let [{:keys [evidence receipt]}
                          (certification-candidate
                           {:room room :definition definition
                            :evaluator evaluator :agent agent :run-id run-id
                            :result result :durable durable
                            :started-at started-at :started-nanos started-nanos
                            :timeout? timeout?})]
                      (let [deferred? (= :deferred
                                         (:run/settlement-status result))
                            claim!
                            #(if (cancelled-externally?)
                               (do
                                 (compare-and-set! state :scoring :cancelled)
                                 false)
                               (compare-and-set! state :scoring :certifying))]
                        (when (cancelled-externally?)
                          (compare-and-set! state :scoring :cancelled))
                        (if (or deferred? (claim!))
                          (let [final-settlement
                                (if deferred?
                                  (settle-certified! fork settlement claim!)
                                  (:run/settlement-status result))
                                result (assoc result
                                              :run/settlement-status
                                              (case final-settlement
                                                :discard :discarded
                                                :review :review
                                                final-settlement))]
                            (reset! state :certified)
                            (sync/deliver!
                             done
                             {:ok {:environment definition
                                   :attempt-receipt receipt
                                   :evidence evidence
                                   :run/id run-id
                                   :run/result result
                                   :run/handle handle}}))
                          (cleanup-once! :evaluation-cancelled))))
                    (catch Throwable error
                      (if (cancelled-externally?)
                        (compare-and-set! state :scoring :cancelled)
                        (compare-and-set! state :scoring :failed))
                      (let [cleanup
                            (cleanup-once!
                             (if (= :cancelled @state)
                               :evaluation-cancelled
                               :evaluation-certification-failed))]
                        (sync/deliver!
                         done
                         {:error
                          (ex-info "Evaluation certification failed"
                                   {:type ::certification-failed
                                    :run/id run-id
                                    :run/world (:run/world result)
                                    :cleanup cleanup}
                                   error)}))))))]
          (try
            (let [{:keys [ok error]} (sp/await done)]
              (if error (throw error) ok))
            (catch Throwable error
              (when (and (spin-cancelled? error)
                         (compare-and-set! state :scoring :cancelled))
                (future-cancel worker)
                ;; Cancellation remains non-blocking for the Spindel drain
                ;; path. The gate is already closed by `state`; cleanup runs
                ;; externally and the interrupted worker can never certify.
                (future
                  (binding [ec/*execution-context* (:ctx room)
                            ec/*spin-id* nil]
                    (cleanup-once! :evaluation-cancelled))))
              (throw error)))))))))
