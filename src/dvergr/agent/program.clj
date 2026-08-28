(ns dvergr.agent.program
  "Run-backed execution of portable AgentDefs.

   This is deliberately a thin first interpreter. Roster construction is pure;
   `hire!` is the effect boundary that posts the precise task trigger, admits a
   durable Run, and starts a Spindel Spin. Live results are cached by Spindel,
   while Room messages and Run lifecycle remain the durable source of truth."
  (:require [dvergr.agent.roster :as roster]
            [dvergr.agent.run :as run]
            [dvergr.discourse :as d]
            [hasch.core :as hasch]
            [org.replikativ.spindel.core :as sp]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.spin.combinators :as comb]
            [org.replikativ.spindel.spin.core :as spin-core]
            [org.replikativ.spindel.spin.sync :as sync]))

(def run-sink
  "Reserved non-subscribed Room address for private Run inputs and outputs.
   Internal coordination is returned through the Run's result Spin; publishing
   to a Participant is a separate, explicit speech act."
  :_runs)

(def interpreter-version 1)

(deftype RunHandle [id room-id owner-fork-id execution completion]
  clojure.lang.ILookup
  (valAt [_ k]
    (case k
      :run/id id
      :run/room room-id
      nil))
  (valAt [_ k not-found]
    (case k
      :run/id id
      :run/room room-id
      not-found))

  ;; Blocking is intentionally available only at a REPL/test boundary.
  clojure.lang.IDeref
  (deref [_]
    (let [current-fork-id (:fork-id (ec/current-execution-context))]
      (when-not (= owner-fork-id current-fork-id)
        (throw (ex-info "RunHandle belongs to another Spindel context"
                        {:type ::foreign-execution-context
                         :run/id id
                         :owner-fork-id owner-fork-id
                         :current-fork-id current-fork-id})))
      @execution))
  clojure.lang.IBlockingDeref
  (deref [_ timeout-ms timeout-value]
    (let [current-fork-id (:fork-id (ec/current-execution-context))]
      (when-not (= owner-fork-id current-fork-id)
        (throw (ex-info "RunHandle belongs to another Spindel context"
                        {:type ::foreign-execution-context
                         :run/id id
                         :owner-fork-id owner-fork-id
                         :current-fork-id current-fork-id})))
      (deref execution timeout-ms timeout-value)))

  Object
  (toString [_]
    (str "#<RunHandle " id " room=" room-id ">")))

(defmethod print-method RunHandle [^RunHandle handle ^java.io.Writer writer]
  (.write writer (.toString handle)))

(defn- ensure-owner-context! [^RunHandle handle]
  (let [owner-fork-id (.-owner-fork-id handle)
        current-fork-id (:fork-id (ec/current-execution-context))]
    (when-not (= owner-fork-id current-fork-id)
      (throw (ex-info "RunHandle belongs to another Spindel context"
                      {:type ::foreign-execution-context
                       :run/id (:run/id handle)
                       :owner-fork-id owner-fork-id
                       :current-fork-id current-fork-id})))
    handle))

(defn run-id "The durable Run UUID represented by `handle`." [handle]
  (:run/id handle))

(defn result-spin
  "A native Spindel observer Spin for `handle`'s completion. Await this inside
   workflow Spins. Each call returns a fresh graph node over a fork-aware
   Deferred, so combinators never invoke the already-running execution again.
  The observer owns that execution during its initial pre-await window."
  [^RunHandle handle]
  (ensure-owner-context! handle)
  (let [execution (.-execution handle)
        completion (.-completion handle)
        id (:run/id handle)
        room-id (:run/room handle)
        observer (sp/spin
                  (try
                    (sp/await completion)
                    (catch Throwable t
                      (when (= spin-core/spin-cancelled (:type (ex-data t)))
                        (run/cancel-room-run! room-id id))
                      (throw t))))]
    ;; A race can choose another arm before its executor has started this
    ;; observer, so no await-cont exists yet. Record the same fork-local
    ;; ownership edge Spindel's fan-out combinators use for that initial window.
    (spin-core/set-owned-spins! (spin-core/spin-id observer) [execution])
    observer))

(defn- cancelled! [run-id]
  (throw (ex-info "Run cancelled" {:type ::cancelled :run/id run-id})))

(defn- cooperative-delay
  "Delay inside Spindel while polling the Run-local cancellation token."
  [run-id delay-ms]
  (sp/spin
   (loop [remaining (long (or delay-ms 0))]
     (when (run/cancel-requested? run-id)
       (cancelled! run-id))
     (when (pos? remaining)
       (let [slice (min remaining 25)]
         (sp/await (comb/sleep slice))
         (recur (- remaining slice)))))))

(defn- execute-program
  "Interpret the provider-free program vocabulary. Returns Spin[value]."
  [run-id agent task]
  (let [{:keys [kind delay-ms reply result] :as program} (:agent/program agent)]
    (sp/spin
     (sp/await (cooperative-delay run-id delay-ms))
     (when (run/cancel-requested? run-id)
       (cancelled! run-id))
     (case kind
       :scripted (cond
                   (contains? program :result) result
                   (contains? program :reply) reply
                   :else nil)
       :echo task
       (throw (ex-info "No interpreter for AgentDef program kind"
                       {:type ::unknown-program-kind
                        :kind kind
                        :agent/id (:agent/id agent)}))))))

(defn- result-content [value]
  (if (string? value) value (pr-str value)))

(def ^:private hire-option-keys #{:task :from :parent-run})

(defn- validate-program!
  [{:keys [kind delay-ms] :as program} agent-ref]
  (let [allowed (case kind
                  :scripted #{:kind :delay-ms :reply :result}
                  :echo #{:kind :delay-ms}
                  nil)]
    (when-not allowed
      (throw (ex-info "hire! currently requires a provider-free program"
                      {:type ::unsupported-program
                       :agent-ref agent-ref
                       :kind kind})))
    (when-let [unknown (seq (remove allowed (keys program)))]
      (throw (ex-info "Unknown keys for AgentDef program kind"
                      {:type ::unknown-program-options
                       :agent-ref agent-ref
                       :kind kind
                       :unknown (set unknown)
                       :allowed allowed})))
    (when (and delay-ms
               (not (and (integer? delay-ms) (<= 0 delay-ms 600000))))
      (throw (ex-info "Program :delay-ms must be an integer from 0 to 600000"
                      {:type ::invalid-delay :delay-ms delay-ms})))
    program))

(defn- validate-hire!
  [roster agent-ref {:keys [task from parent-run] :as opts}]
  (when-let [unknown (seq (remove hire-option-keys (keys opts)))]
    (throw (ex-info "Unknown hire! options"
                    {:type ::unknown-hire-options
                     :unknown (set unknown)
                     :allowed hire-option-keys})))
  (when-not (contains? opts :task)
    (throw (ex-info "hire! requires :task"
                    {:type ::missing-task :agent-ref agent-ref})))
  (when-not (keyword? from)
    (throw (ex-info "hire! :from must be a keyword"
                    {:type ::invalid-hirer :from from})))
  (when (and parent-run (not (uuid? parent-run)))
    (throw (ex-info "hire! :parent-run must be a UUID"
                    {:type ::invalid-parent-run :parent-run parent-run})))
  (when-not (roster/data-value? task)
    (throw (ex-info "Task must be portable data"
                    {:type ::non-portable-task :task task})))
  (let [agent (or (roster/agent roster agent-ref)
                  (throw (ex-info "Unknown AgentRef"
                                  {:type ::unknown-agent :agent-ref agent-ref})))]
    (validate-program! (:agent/program agent) agent-ref)
    agent))

(defn- cancelled-error? [t run-id]
  (or (= ::cancelled (:type (ex-data t)))
      (= spin-core/spin-cancelled (:type (ex-data t)))
      (run/cancel-requested? run-id)))

(defn- execution-spin
  [room agent task trigger id completion]
  (sp/spin
   (let [result
         (try
           (let [value (sp/await (execute-program id agent task))]
             (when (run/cancel-requested? id)
               (cancelled! id))
             (let [output (d/reply (:agent/id agent) run-sink
                                   (result-content value) trigger
                                   {:role :assistant :run-id id})]
               ;; Room posting is durability-first. Completion is acknowledged
               ;; only after the correlated private output exists.
               (d/post! room output)
               (run/finish! id :completed)
               {:run/id id
                :run/status :completed
                :run/value value
                :run/output output}))
           (catch Throwable t
             (if (cancelled-error? t id)
               (do
                 (run/finish! id :cancelled {:reason :cancel-requested})
                 {:run/id id :run/status :cancelled})
               (do
                 (run/finish! id :failed {:reason :program-error :error t})
                 {:run/id id
                  :run/status :failed
                  :run/error (ex-message t)}))))]
     ;; Completion is a Spindel Deferred allocated in this Room's execution
     ;; context, not a JVM promise or host atom. Its value therefore follows
     ;; Spindel's copy-on-write state semantics.
     (completion result)
     result)))

(defn hire!
  "Start one provider-free AgentDef execution and return an opaque RunHandle.

   `agent-ref` is a keyword id or versioned ref resolved against immutable
   `roster`. Options:

   - `:task`       portable task value (required)
   - `:from`       triggering actor, default `:repl`
   - `:parent-run` explicit structural parent Run UUID
   The built-in first-pass program kinds are `:scripted` and `:echo`. Real LLM,
   tool, simulation, and replay interpreters will implement the same boundary."
  [room roster agent-ref {:keys [task from parent-run]
                          :or {from :repl}
                          :as raw-opts}]
  (let [opts      (assoc raw-opts :from from)
        agent     (validate-hire! roster agent-ref opts)
        actor     (:agent/id agent)
        id        (random-uuid)
        ;; Private Run facts are still Room messages, but never addressed to an
        ;; installed Participant: direct interpretation and participant routing
        ;; must not execute the same task twice.
        trigger   (d/message from run-sink (result-content task) nil {:role :user})
        provenance (cond-> {:run/agent-version (:agent/version agent)
                            :run/program-kind (get-in agent [:agent/program :kind])
                            :run/interpreter-version interpreter-version
                            :run/agent-def-hash (hasch/uuid agent)}
                     (:roster/id roster) (assoc :run/roster (:roster/id roster)))]
    ;; Reserve durable Run ownership before any trigger effect. Teardown either
    ;; fences us out here or includes this Run in its fixed drain set; it can
    ;; never miss an orphan trigger between posting and admission.
    (run/start! room actor trigger nil
                (cond-> {:id id :kind :agent-task :provenance provenance}
                  parent-run (assoc :parent parent-run)))
    (try
      ;; The precise trigger must exist before execution begins. A failed post
      ;; terminalizes the already-admitted Run instead of leaving a :running
      ;; record or an unowned message behind.
      (d/post! room trigger)
      (catch Throwable t
        (run/finish! id :failed {:reason :trigger-emission-failed :error t})
        (throw t)))
    (try
      (let [completion (sync/deferred)
            execution (execution-spin room agent task trigger id completion)
            owner-fork-id (:fork-id (ec/current-execution-context))
            handle    (RunHandle. id (:id room) owner-fork-id execution completion)]
        (sp/spawn!
         execution
         {:on-error
          (fn [t]
            ;; Graph-level cancellation (parent/race) can abort before the Spin
            ;; body catches. Settle the durable lifecycle from the callback;
            ;; finish! is idempotent if the body already did so.
            (let [result (if (cancelled-error? t id)
                           {:run/id id :run/status :cancelled}
                           {:run/id id :run/status :failed
                            :run/error (ex-message t)})]
              (try
                (if (= :cancelled (:run/status result))
                  (run/finish! id :cancelled {:reason :structured-cancellation})
                  (run/finish! id :failed {:reason :execution-error :error t}))
                (catch Throwable _ nil))
              (completion result)))})
        handle)
      (catch Throwable t
        (run/finish! id :failed {:reason :spawn-failed :error t})
        (throw t)))))

(defn observe
  "Read the durable Run projection for `handle` or UUID."
  [room handle-or-id]
  (run/run room (if (uuid? handle-or-id) handle-or-id (run-id handle-or-id))))

(defn cancel!
  "Request room-scoped cancellation. A handle carries its Room capability;
   cancelling an arbitrary UUID requires the explicit Room arity."
  ([handle]
   (when (uuid? handle)
     (throw (ex-info "Cancelling a Run UUID requires an explicit Room"
                     {:type ::room-required :run/id handle})))
   (ensure-owner-context! handle)
   (run/cancel-room-run! (:run/room handle) (run-id handle)))
  ([room handle-or-id]
   (when-not (uuid? handle-or-id)
     (ensure-owner-context! handle-or-id))
   (run/cancel-room-run! (:id room)
                         (if (uuid? handle-or-id)
                           handle-or-id
                           (run-id handle-or-id)))))
