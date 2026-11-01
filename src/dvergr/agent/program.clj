(ns dvergr.agent.program
  "Run-backed execution of portable AgentDefs.

   Roster construction is pure; `hire!` is the effect boundary that admits a
   durable Run, posts its precise task trigger, and starts a Spindel Spin.
   Workflow results live in the fork-aware execution graph, while Room messages
   and Run lifecycle remain the durable source of truth.

   An `:llm` Run saves a turn savepoint as it goes; `resume!` continues a
   stopped Run from its latest one as a new Run caused by it
   (doc/runs.md, Resuming a Run). `run-inputs`/`inputs-id` say what a Run presents to its
   candidate when it starts (assembled prompt, tools, resolved model), which an
   experiment's resume compares per cell."
  (:require [clojure.string :as str]
            [dvergr.agent.roster :as roster]
            [dvergr.agent.sci-program :as sci-program]
            [dvergr.agent.authority :as delegation]
            [dvergr.agent.invocations :as invocations]
            [dvergr.sandbox.extensions :as extensions]
            [dvergr.agent.prompt :as prompt]
            [dvergr.agent.room-context :as room-context]
            [dvergr.agent.run :as run]
            [dvergr.agent.conversation-capture :as conversation-capture]
            [dvergr.agent.turn :as turn]
            [dvergr.agent.world :as world]
            [dvergr.chat.agent :as chat-agent]
            [dvergr.chat.context :as chat-context]
            [dvergr.chat.outcome :as turn-outcome]
            [dvergr.discourse :as d]
            [dvergr.model.providers :as providers]
            [dvergr.model.registry :as registry]
            [dvergr.model.api.claude-code :as claude-code]
            [dvergr.resource :as resource]
            [dvergr.room.store :as room-store]
            [dvergr.system.rooms :as system-rooms]
            [dvergr.tools :as tools]
            [datahike.api :as dh]
            [hasch.core :as hasch]
            [org.replikativ.spindel.blocking :as blocking]
            [org.replikativ.spindel.core :as sp]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.engine.impl.simple :as simple]
            [org.replikativ.spindel.atom :as ratom]
            [org.replikativ.spindel.spin.combinators :as comb]
            [org.replikativ.spindel.spin.core :as spin-core]
            [org.replikativ.spindel.spin.sync :as sync]
            [org.replikativ.spindel.effects.savepoint :as savepoint :refer [savepoint]]
            [org.replikativ.spindel.savepoint.portable :as portable]
            [dvergr.resource.authority :as authority]
            [taoensso.telemere :as tel])
  (:import [java.nio.charset StandardCharsets]
           [java.util UUID]
           [java.util.concurrent Callable CountDownLatch Executors FutureTask
            ThreadFactory]
           [java.util.concurrent.atomic AtomicLong]))

(def run-sink
  "Reserved non-subscribed Room address for private Run inputs and outputs.
   Internal coordination is returned through the Run's result Spin; publishing
   to a Participant is a separate, explicit speech act."
  :_runs)

(def interpreter-version 10)

(def ^:private default-max-model-steps 32)

(defonce ^:private native-thread-counter (AtomicLong.))

(defonce ^:private native-executor
  ;; Clojure futures and send-off share Agent/soloExecutor. A finalization watcher
  ;; blocks until its orchestration publishes an outcome, so using that same
  ;; executor for the native worker which produces the outcome creates a
  ;; scheduler-dependent deadlock when an embedder bounds or replaces the Agent
  ;; executor. Dvergr owns this cached daemon pool as part of its process-local
  ;; Run supervisor instead.
  (Executors/newCachedThreadPool
   (reify ThreadFactory
     (newThread [_ runnable]
       (doto (Thread. ^Runnable runnable)
         (.setName (str "dvergr-agent-native-"
                        (.incrementAndGet native-thread-counter)))
         (.setDaemon true))))))

(declare with-owned-child! cancel!)

(defn- child-program-authority
  "Authority made available to code and tools running inside one paid LLM Run.
   Keep this one value shared by the SCI and native-tool surfaces so a model
   cannot bypass delegation attenuation by choosing a different interface."
  [run-id supervisor]
  (cond-> {:program-kinds #{:echo :scripted :sci}
   ;; A child that calls providers is admitted only when its grant funds
   ;; them (`resource/funded-grant?`): money and dispatch admissions split
   ;; from this Run's wallet, which bound what it can spend, each dispatch's
   ;; cost reserved before it leaves (doc/design/runs-as-worlds.md, Budgets
   ;; as a tree). An unfunded child, an experiment's candidates, and provider
   ;; calls made from this Run's own sandbox stay closed.
           :funded-program-kinds #{:llm}
           :provider-effects? false
           :parent-run run-id
   ;; Process-local structured ownership. The sandbox may construct immutable
   ;; rosters freely, but every admitted child execution is leased to this
   ;; supervisor before `hire!` returns to agent code.
           :own-child! #(with-owned-child! supervisor %)}
    (contains? supervisor :experiment-capabilities)
    (assoc :experiment-capabilities (:experiment-capabilities supervisor))
    (contains? supervisor :delegation-policy)
    (merge (select-keys (:delegation-policy supervisor) [:max-attempts :max-parallelism])
           {:delegation-policy (:delegation-policy supervisor)})))

(defn- program-result
  ([status value] {::status status ::value value})
  ([status value reason] {::status status ::value value ::reason reason})
  ([status value reason diagnostic]
   {::status status ::value value ::reason reason ::diagnostic diagnostic}))

(defn run-chat-id
  "The chat a Run's model turns are kept under, in its control room."
  [run-id]
  (UUID/nameUUIDFromBytes
   (.getBytes (str "dvergr-agent-run|" run-id) StandardCharsets/UTF_8)))

(def ^:dynamic *worker-supervisor* nil)

(defn with-worker-decision!
  "Serialize an irreversible decision with this worker's cancellation fence."
  [f]
  (if-let [supervisor *worker-supervisor*]
    (locking supervisor
      (when (or (:cancelled? @(:state supervisor))
                (and (:lifecycle-run supervisor) (run/cancel-requested? (:lifecycle-run supervisor))))
        (throw (ex-info "Worker cancelled before decision" {})))
      (f))
    (f)))

(declare start-worker!)

(defn- deadline-expired? [supervisor]
  (when-let [deadline (:deadline-nanos supervisor)]
    (>= (System/nanoTime) deadline)))

(defn- check-deadline! [supervisor]
  (when (deadline-expired? supervisor)
    (throw (ex-info "Run execution deadline expired"
                    {:type ::deadline-expired}))))

(defn- cancelled-or-expired? [supervisor run-id]
  (or (deadline-expired? supervisor) (run/cancel-requested? run-id)))

(defn- make-supervisor
  ([execution-ctx] (make-supervisor execution-ctx execution-ctx))
  ([execution-ctx worker-ctx]
   {:execution-ctx execution-ctx
    :worker-ctx worker-ctx
    :dispatch-owner (resource/make-dispatch-owner)
    :state (atom {:cancelled? false
                  :sealed? false
                  ;; Sealing closes admission. It does not prove that the
                  ;; orchestration body has stopped using resources acquired
                  ;; by a completed worker. Cleanup needs this separate gate.
                  :cleanup-safe? false
                  :workers {}
                  :children {}
                  :cleanups []
                  ;; Cleanup registration remains open while a cancelled but
                  ;; still-live setup worker hands back resources. The first
                  ;; physical cleanup claim closes it monotonically; unlike
                  ;; :cleanup-phase, it never reopens between stack entries.
                  :cleanup-admission-open? true
                  :cleanup-phase :pending
                  :cleanup-error nil
                  :llm-metrics nil
                  :execution-phase :admitted
                  :quiesced? false})
    :quiesced (sync/deferred)
    :quiesced-latch (CountDownLatch. 1)}))

(defn- update-llm-metrics!
  "Update provider evidence under the same lock as cleanup/quiescence state."
  [supervisor f & args]
  (locking supervisor
    (apply swap! (:state supervisor) update :llm-metrics f args)))

(defn- advance-supervisor!
  "Advance cleanup/quiescence after an atomic supervisor transition. Cleanup
   starts only after every normal worker has acknowledged termination."
  [supervisor]
  (let [action
        (locking supervisor
          (let [{:keys [sealed? cleanup-safe? workers children cleanups
                        cleanup-phase quiesced?]
                 :as state}
                @(:state supervisor)
                normal-live? (or (seq children)
                                 (some #(= :normal (:kind %)) (vals workers)))]
            (cond
              (and sealed? cleanup-safe? (not normal-live?) (= :pending cleanup-phase)
                   (seq cleanups))
              (let [cleanup (peek cleanups)]
                (swap! (:state supervisor)
                       #(-> %
                            (assoc :cleanup-admission-open? false
                                   :cleanup-phase :running)
                            (update :cleanups pop)))
                [:cleanup cleanup])

              (and sealed? cleanup-safe? (not normal-live?) (= :pending cleanup-phase))
              (do (swap! (:state supervisor)
                         assoc
                         :cleanup-admission-open? false
                         :cleanup-phase :done)
                  :advance)

              (and sealed? (= :done cleanup-phase) (empty? workers) (not quiesced?))
              (let [state' (assoc state :quiesced? true)]
                (reset! (:state supervisor) state')
                [:quiesced state'])

              :else nil)))]
    (cond
      (= :advance action)
      (advance-supervisor! supervisor)

      (= :cleanup (first action))
      (start-worker! supervisor (second action) :cleanup)

      (= :quiesced (first action))
      (try
        ;; Deferred publication is context access. The stable latch is released
        ;; only afterwards, so Room drain can treat it as a true quiescence ack.
        (binding [ec/*execution-context* (:execution-ctx supervisor)]
          ((:quiesced supervisor) (second action)))
        (finally
          (.countDown ^CountDownLatch (:quiesced-latch supervisor)))))
    nil))

(defn- worker-finished! [supervisor worker-id kind result]
  (locking supervisor
    (swap! (:state supervisor)
           (fn [state]
             (cond-> (update state :workers dissoc worker-id)
               (= :cleanup kind)
               (assoc :cleanup-phase :pending)

               (and (= :cleanup kind)
                    (map? result)
                    (contains? result ::worker-error))
               (update :cleanup-error #(or % (::worker-error result)))))))
  (advance-supervisor! supervisor))

(defn- worker-stage! [supervisor worker-id kind stage started-nanos]
  (try
    (tel/log! {:level :debug :id ::worker-stage
               :data {:run/id (:run-id supervisor) :worker/id worker-id :kind kind :stage stage
                      :fork/id (:fork-id (:execution-ctx supervisor))
                      :elapsed-ms (/ (- (System/nanoTime) started-nanos) 1000000.0)}})
    ;; Diagnostics must never suppress result publication or a physical ack.
    (catch Throwable _ nil)))

(defn- start-worker!
  "Register and start blocking/native work under the process-local supervisor.
   Cancellation is sticky: registration and the cancellation check share the
   supervisor lock, closing the cancel-before-start and between-step races."
  ([supervisor f] (start-worker! supervisor f :normal))
  ([supervisor f kind]
   (let [done       (sync/deferred)
         worker-id  (random-uuid)
         started-nanos (System/nanoTime)
         phase      (atom :new)
         result     (atom nil)
         callable   (reify Callable
                      (call [_]
                        (when (compare-and-set! phase :new :running)
                          (binding [ec/*execution-context* (:worker-ctx supervisor)
                                    ;; A native worker is an effect boundary,
                                    ;; not another node in the caller's Spin.
                                    ;; In particular trusted world setup must
                                    ;; not inherit the drain/graph identity
                                    ;; which admitted the Run.
                                    ec/*spin-id* nil
                                    *worker-supervisor* supervisor]
                            (try
                              (worker-stage! supervisor worker-id kind :entered started-nanos)
                              ;; Absolute deadlines are host-owned coordination,
                              ;; not forkable AgentDef state. Check at physical
                              ;; entry even if the engine's timer is delayed.
                              (when (= :normal kind) (check-deadline! supervisor))
                              (reset! result (f))
                              (catch Throwable t
                                (reset! result {::worker-error t}))
                              (finally
                                (reset! phase :terminated)
                               ;; Publish the per-worker result before removing
                               ;; its live lease from the stable supervisor.
                                (binding [ec/*execution-context*
                                          (:execution-ctx supervisor)]
                                  (try
                                    (worker-stage! supervisor worker-id kind :publishing started-nanos)
                                    (done @result)
                                    (finally
                                      (worker-finished! supervisor worker-id kind
                                                        @result)
                                      (worker-stage! supervisor worker-id kind :acknowledged started-nanos))))))))))
         task       (proxy [FutureTask] [callable]
                      (done []
                       ;; Cancellation before the executor begins means the
                       ;; Callable's finally cannot acknowledge termination.
                       ;; Win that one state transition here. If it was already
                       ;; running, its finally is the only acknowledgement.
                        (when (and (.isCancelled this)
                                   (compare-and-set! phase :new :terminated))
                          (let [value (or @result ::worker-cancelled)]
                            (binding [ec/*execution-context* (:execution-ctx supervisor)]
                              (try
                                (done value)
                                (finally
                                  (worker-finished! supervisor worker-id kind
                                                    value))))))))
         worker     {:id worker-id :task task :done done :kind kind :started-nanos started-nanos}
         cancel-now?
         (locking supervisor
           (let [{:keys [sealed? cancelled?]} @(:state supervisor)]
             (when (and sealed? (= :normal kind))
               (throw (ex-info "Cannot start work after supervisor seal"
                               {:type ::supervisor-sealed})))
             (swap! (:state supervisor) assoc-in [:workers worker-id] worker)
             (and cancelled? (= :normal kind))))]
     (if cancel-now?
      ;; Do not even enqueue work when cancellation preceded registration.
       (.cancel task false)
       (try
         (.execute native-executor task)
         (catch Throwable t
          ;; Make executor rejection flow through the same acknowledgement path.
           (reset! result {::worker-error t})
           (.cancel task false))))
     worker)))

(declare cancel-supervisor! mark-cleanup-safe! seal-supervisor!)

(defn on-worker!
  "Call f on a native worker. The owned arity admits a room lifecycle Run,
   links job cancellation to its supervisor, and waits for physical quiescence."
  ([room f]
   (when ec/*spin-id*
     (throw (ex-info "A worker boundary cannot block a Spin" {:type ::worker-boundary})))
   (let [outcome (promise)
         worker (start-worker! (make-supervisor (:ctx room))
                               #(deliver outcome (try {:value (f)}
                                                      (catch Throwable t {:error t}))))]
     (.get ^FutureTask (:task worker))
     (let [{:keys [value error]} @outcome]
       (if error (throw error) value))))
  ([room owner f]
   (when ec/*spin-id*
     (throw (ex-info "A worker boundary cannot block a Spin" {:type ::worker-boundary})))
   (let [r (run/start! room :settlement (random-uuid) nil
                       (cond-> {:kind :settlement} owner (assoc :parent owner)))
         id (:run/id r)
         supervisor (assoc (make-supervisor (:ctx room)) :lifecycle-run id)
         key [::settlement id]
         cancelled? (atom false)
         interrupted? (atom false)
         failure (atom nil)
         cancel #(do (reset! cancelled? true) (cancel-supervisor! supervisor))
         join! (fn []
                 ;; Future cancellation is not a physical acknowledgement. An
                 ;; outer blocking Spin may interrupt this host waiter again;
                 ;; retain the Run and clear/remember interrupts until native
                 ;; workers and cleanup have actually left.
                 (when (Thread/interrupted)
                   (reset! interrupted? true)
                   (cancel))
                 (loop []
                   (when-not
                    (try
                      (.await ^CountDownLatch (:quiesced-latch supervisor))
                      true
                      (catch InterruptedException _
                        (reset! interrupted? true)
                        (cancel)
                        false))
                     (recur))))
         outcome (promise)]
     (try
       (run/register-cancel-hook! id key cancel)
       (when owner
         (when-not (run/register-cancel-hook! owner key cancel)
           (throw (ex-info "Settlement job is no longer live" {:run/id owner}))))
       (let [worker (start-worker! supervisor
                                   #(deliver outcome (try {:value (f)}
                                                          (catch Throwable t {:error t}))))]
         (try (.get ^FutureTask (:task worker))
              (catch java.util.concurrent.CancellationException _ nil)
              (catch InterruptedException error
                (reset! interrupted? true)
                (cancel)
                (throw error)))
         (mark-cleanup-safe! supervisor)
         (seal-supervisor! supervisor)
         (join!)
         (when @cancelled? (throw (ex-info "Settlement cancelled" {:run/id id})))
         (when-let [error (:cleanup-error @(:state supervisor))]
           (throw (ex-info "Settlement cleanup failed" {:run/id id} error)))
         (when-not (realized? outcome)
           (throw (ex-info "Settlement worker exited without an outcome"
                           {:type ::missing-worker-outcome :run/id id})))
         (let [{:keys [value error]} @outcome]
           (if error (throw error) value)))
       (catch Throwable error
         (reset! failure error)
         (throw error))
       (finally
         (try
           (mark-cleanup-safe! supervisor)
           (seal-supervisor! supervisor)
           (join!)
           (when owner (run/unregister-cancel-hook! owner key))
           (run/finish! id (cond @cancelled? :cancelled @failure :failed :else :completed))
           (finally
             ;; Restoring earlier could interrupt durable Run retirement and
             ;; falsely leave an otherwise quiescent lifecycle owner behind.
             (when @interrupted?
               (.interrupt (Thread/currentThread))))))))))

(defn- cancel-supervisor! [supervisor]
  (let [[tasks cancel-children]
        (locking supervisor
          (swap! (:state supervisor) assoc :cancelled? true)
          [(->> (get @(:state supervisor) :workers)
                vals
                (filter #(= :normal (:kind %)))
                (map :task)
                vec)
           (->> (get @(:state supervisor) :children)
                vals
                (map :cancel!)
                vec)])]
    (doseq [^FutureTask task tasks]
      (.cancel task true))
    (doseq [cancel-child! cancel-children]
      (try
        (cancel-child!)
        (catch Throwable t
          (tel/log! {:level :warn :id ::child-cancellation-failed
                     :data {:error (ex-message t)}}
                    "Owned child cancellation failed")))))
  nil)

(defn- register-cleanup! [supervisor cleanup]
  (when-not (fn? cleanup)
    (throw (ex-info "Run cleanup must be a function"
                    {:type ::invalid-cleanup})))
  (locking supervisor
    (when-not (:cleanup-admission-open? @(:state supervisor))
      (throw (ex-info "Cleanup registered after supervisor cleanup began"
                      {:type ::late-cleanup-registration})))
    ;; Stack order is intentional: resources unwind in reverse acquisition
    ;; order, as with lexical `finally` scopes. Every registered cleanup runs
    ;; even when an earlier cleanup fails; the first error determines the Run
    ;; outcome after physical quiescence.
    (swap! (:state supervisor) update :cleanups conj cleanup))
  nil)

(defn- mark-cleanup-safe!
  "Acknowledge that the orchestration body can no longer touch acquired live
   resources. Cancellation alone must never make cleanup eligible: a setup
   worker can finish while the enclosing Spin is still allocating resources or
   transitioning into candidate execution."
  [supervisor]
  (locking supervisor
    (swap! (:state supervisor) assoc :cleanup-safe? true))
  (advance-supervisor! supervisor)
  nil)

(defn- seal-supervisor! [supervisor]
  (locking supervisor
    (swap! (:state supervisor) assoc :sealed? true))
  (advance-supervisor! supervisor)
  (:quiesced supervisor))

(defn- worker-result-spin [worker]
  (sp/spin (sp/await (:done worker))))

(defn- worker-error? [value]
  (and (map? value) (contains? value ::worker-error)))

(defn- await-supervisor! [supervisor]
  (.await ^CountDownLatch (:quiesced-latch supervisor))
  nil)

(defn preparation-scope
  "Create a host-owned native preparation scope bound to `execution-ctx`.

   The caller must register this scope with its lifecycle owner BEFORE calling
   `start-preparation!`. This is the same physical-worker supervisor used by
   interpreted programs; cancelling a Future is never a physical completion
   acknowledgement. Preparation is not itself a provider-admitted Run."
  [execution-ctx]
  (binding [ec/*execution-context* execution-ctx]
    (make-supervisor execution-ctx)))

(defn start-preparation!
  "Dispatch one owned initialization worker. Admission is cancellation fenced."
  [scope f]
  (binding [ec/*execution-context* (:execution-ctx scope)]
    (start-worker! scope f)))

(defn preparation-result
  "Reactively await an initialization worker without blocking the engine."
  [worker]
  (sp/spin
   (let [result (sp/await (:done worker))]
     (cond
       (worker-error? result) (throw (::worker-error result))
       (= ::worker-cancelled result)
       (throw (ex-info "Preparation cancelled before execution"
                       {:type ::preparation-cancelled}))
       :else result))))

(defn register-preparation-cleanup!
  "Register cleanup before handing an acquired resource out of its worker.
   Cleanup cannot run until every worker has physically acknowledged exit."
  [scope f]
  (register-cleanup! scope f))

(defn with-preparation-decision!
  "Serialize publication against cancellation of the preparation owner.
   `f` must only perform a short publication decision, never blocking setup."
  [scope f]
  (binding [*worker-supervisor* scope]
    (with-worker-decision! f)))

(defn complete-preparation!
  "Seal a preparation after its consumer can no longer touch private resources."
  [scope]
  (mark-cleanup-safe! scope)
  (seal-supervisor! scope))

(defn cancel-preparation!
  "Fence further decisions and request cancellation without waiting on workers."
  [scope]
  (cancel-supervisor! scope)
  (complete-preparation! scope))

(defn preparation-finished?
  "True only after physical worker/cleanup acknowledgement, without failure."
  [scope]
  (and (zero? (.getCount ^CountDownLatch (:quiesced-latch scope)))
       (nil? (:cleanup-error @(:state scope)))))

(defn preparation-admission-ready?
  "Logical work and cleanup are finished; the physical lease may still be
   publishing its acknowledgement and must remain retained for native drain."
  [scope]
  (let [state @(:state scope)]
    (and (:quiesced? state) (nil? (:cleanup-error state)))))

(defn preparation-completion
  "Reactively await logical worker/cleanup quiescence. Native teardown must
   still use await-preparation!, whose physical latch follows publication."
  [scope]
  (sp/spin
   (let [state (sp/await (:quiesced scope))]
     (when-let [error (:cleanup-error state)]
       (throw (ex-info "Preparation cleanup failed"
                       {:type ::preparation-cleanup-failed} error)))
     state)))

(defn await-preparation!
  "Join physical preparation and cleanup off the reactive engine.
   A failed/timed-out join does not relinquish ownership of live resources."
  [scope timeout-ms]
  (when ec/*spin-id*
    (throw (ex-info "Cannot physically join preparation on the reactive engine"
                    {:type ::blocking-preparation-join})))
  (when-not (.await ^CountDownLatch (:quiesced-latch scope)
                    (long timeout-ms) java.util.concurrent.TimeUnit/MILLISECONDS)
    (throw (ex-info "Preparation has not physically quiesced"
                    {:type ::preparation-drain-timeout})))
  (when-let [error (:cleanup-error @(:state scope))]
    (throw (ex-info "Preparation cleanup failed"
                    {:type ::preparation-cleanup-failed} error)))
  nil)

(deftype RunHandle [id room-id owner-fork-id execution completion worker-execution]
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
  "A passive Spindel observer Spin for `handle`'s completion. Await this inside
   workflow Spins. Each call returns a fresh graph node over a fork-aware
   Deferred; cancelling one observer never cancels the shared execution or a
   peer observer. Use `owned-result-spin` when structured cancellation should
   propagate from a race arm to the hired Run."
  ([^RunHandle handle]
   (ensure-owner-context! handle)
   (let [completion (.-completion handle)]
     (sp/spin (sp/await completion))))
  ([consumer-run-id ^RunHandle handle]
   (let [observer (result-spin handle)
         cause-run-id (run-id handle)]
     (sp/spin
      (let [result (sp/await observer)]
        (run/record-cause! consumer-run-id cause-run-id)
        result)))))

(defn owned-result-spin
  "An owning Spindel observer for a RunHandle. If a combinator cancels this
   observer (for example, as the losing arm of `race`), cancellation propagates
   to the hired Run. Prefer passive `result-spin` for ordinary or shared reads."
  ([^RunHandle handle]
   (ensure-owner-context! handle)
   (let [execution (.-worker-execution handle)
         id (:run/id handle)
         room-id (:run/room handle)
         observer (sp/spin
                   (try
                     (sp/await (.-completion handle))
                     (catch Throwable t
                       (when (= spin-core/spin-cancelled (:type (ex-data t)))
                         (run/cancel-room-run! room-id id))
                       (throw t))))]
     ;; A race can choose another arm before its executor has started this
     ;; observer, so no await-cont exists yet. Record the same fork-local
     ;; ownership edge Spindel's fan-out combinators use for that initial window.
     (spin-core/set-owned-spins! (spin-core/spin-id observer) [execution])
     observer))
  ([consumer-run-id ^RunHandle handle]
   (let [observer (owned-result-spin handle)
         cause-run-id (run-id handle)
         consumer (sp/spin
                   (let [result (sp/await observer)]
                     (run/record-cause! consumer-run-id cause-run-id)
                     result))]
     ;; The same initial window one level up: a race that is decided before
     ;; this body awaits `observer` cancels a Spin with no await-cont, and
     ;; without this edge the cancellation stops here. The hired Run then
     ;; finishes and settles as if it had won.
     (spin-core/set-owned-spins! (spin-core/spin-id consumer) [observer])
     consumer)))

(defn- child-finished! [supervisor lease-id]
  (locking supervisor
    (swap! (:state supervisor) update :children dissoc lease-id))
  (advance-supervisor! supervisor))

(defn- with-owned-child!
  "Reserve parent ownership before invoking zero-argument `admit-child!`.

   The reservation closes the cancellation/seal race around durable child
   admission: a parent cannot quiesce while admission is in progress, and a
   cancellation that arrives before the RunHandle exists is replayed against
   it immediately afterward. The lease is released only when admission fails
   or the admitted child reaches its durable terminal state."
  [supervisor admit-child!]
  (let [lease-id (random-uuid)
        cancel-slot (atom nil)
        watch-key (Object.)
        acknowledged? (atom false)
        acknowledge! (fn []
                       (when (compare-and-set! acknowledged? false true)
                         (run/unwatch-runs! watch-key)
                         (child-finished! supervisor lease-id)))]
    (locking supervisor
      (let [{:keys [sealed? cancelled?]} @(:state supervisor)]
        (when (or sealed? cancelled?)
          (throw (ex-info "Cannot hire an owned child after parent cancellation/seal"
                          {:type ::supervisor-sealed})))
        (swap! (:state supervisor) assoc-in [:children lease-id]
               {:cancel! #(when-let [cancel-child! @cancel-slot]
                            (cancel-child!))})))
    (try
      (let [handle (admit-child!)
            child-id (run-id handle)
            owner-ctx (ec/current-execution-context)
            cancel-child! #(binding [ec/*execution-context* owner-ctx]
                             ;; Host-owned jobs share the same lease. They
                             ;; terminalize only after their before-finish
                             ;; boundary has joined cleanup and returned grants.
                             (if-let [cancel-job! (when (map? handle) (::cancel! handle))]
                               (cancel-job!)
                               (cancel! handle)))]
        (reset! cancel-slot cancel-child!)
        (locking supervisor
          (swap! (:state supervisor) assoc-in [:children lease-id :handle] handle))
        ;; Lifecycle registration and its initial active snapshot share the Run
        ;; registry lock. We therefore cannot miss a child that terminalizes
        ;; during registration, and acknowledgement is independent of either
        ;; execution graph being cancelled.
        (let [watch-terminal!
              (fn [] (run/watch-runs!
                      watch-key
                      (fn [{:keys [type runs run]}]
                        (when (or (and (= :runs/snapshot type)
                                       (not-any? #(= child-id (:run/id %)) runs))
                                  (and (= :run/finished type)
                                       (= child-id (:run/id run))))
                          (acknowledge!)))))]
          (if-let [on-settled! (when (map? handle) (::on-settled! handle))]
            ;; A Job must BOTH publish into its still-live caller context and
            ;; durably terminalize. Neither frontier alone releases ownership.
            (on-settled! watch-terminal!)
            (watch-terminal!)))
        (when (:cancelled? @(:state supervisor))
          (cancel-child!))
        handle)
      (catch Throwable t
        (acknowledge!)
        (when-let [cancel-child! @cancel-slot]
          (try (cancel-child!) (catch Throwable _)))
        (throw t)))))

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

(defn- execute-deterministic-program
  "Interpret the deterministic program vocabulary. Returns Spin[ProgramResult]."
  [run-id agent task]
  (let [{:keys [kind delay-ms reply result] :as program} (:agent/program agent)]
    (sp/spin
     (sp/await (cooperative-delay run-id delay-ms))
     (when (run/cancel-requested? run-id)
       (cancelled! run-id))
     (case kind
       :scripted (program-result
                  :completed
                  (cond
                    (contains? program :result) result
                    (contains? program :reply) reply
                    :else nil))
       :echo (program-result :completed task)
       (throw (ex-info "No interpreter for AgentDef program kind"
                       {:type ::unknown-program-kind
                        :kind kind
                        :agent/id (:agent/id agent)}))))))

(defn- result-content [value]
  (if (string? value) value (pr-str value)))

(defn- missing-outputs-note
  "The note for a model that ended its turn before writing the files the
   environment requires (`:required-outputs`), or nil when they exist."
  [tool-ctx required]
  (when-let [missing (seq (remove #(try (tools/workspace-file? tool-ctx %) (catch Exception _ false))
                                  required))]
    (str "You ended your turn, but " (str/join ", " missing)
         (if (next missing) " do not exist yet." " does not exist yet.")
         " The task is scored on " (if (next required) "those files" "that file")
         " alone: write " (if (next missing) "them" "it") " before you finish.")))

(defn- last-assistant-content [chat-ctx]
  (some->> (chat-context/get-messages chat-ctx)
           reverse
           (some (fn [message]
                   (when (= :assistant
                            (or (:message/role message) (:role message)))
                     (or (:message/content message) (:content message)))))))

(defn- resolve-model-spec [agent]
  (providers/ensure-initialized!)
  (let [spec (or (:agent/model-policy agent)
                 (providers/default-spec)
                 (throw (ex-info "No LLM provider is available for AgentDef"
                                 {:type ::no-provider :agent/id (:agent/id agent)})))]
    (cond-> spec
      (:model spec) (update :model registry/resolve-candidate
                            (get-in agent [:agent/program :effort])))))

(def ^:private llm-base-prompt
  (str "You are a model inside the Dvergr harness. Dvergr owns "
       "tools and the sandbox. Call only tools explicitly supplied "
       "in this request; when none are supplied, answer directly. "
       "Never request shell, grep, file, web, or other native "
       "Codex tools unless Dvergr explicitly supplied that exact tool."))

(defn llm-instructions
  "The system prompt an LLM Run of `agent` starts with, assembled the way the
   Run assembles it (`prompt/assemble-system-prompt`) for its `tool-map`: the
   harness preamble, the agent's own prompt, skills, the tool-use guideline,
   the sandbox pointer and the workspace guide of the bound context's
   workspace. Reads that workspace (skills, AGENTS.md), so blocking work."
  [agent tool-map]
  (prompt/assemble-system-prompt
   (str llm-base-prompt
        (when-let [agent-prompt (:agent/prompt agent)]
          (str "\n\n" agent-prompt)))
   {:tools tool-map :isolation :sci :profile :workflow}))

(defn prompt-id
  "The content id of system prompt `instructions` (`:attempt/prompt-id`)."
  [instructions]
  (hasch/uuid [:dvergr/llm-system-prompt instructions]))

(defn- model-inputs
  "What the Run's model is, resolved now: the alias resolved to its registry
   id, the provider, and the registry entry that shapes every request (API
   type, quirks such as sampling defaults, output limit, context size,
   capabilities, instruction role, reasoning efforts). Its prices and display
   names are not in it: they change what a call costs or is called, not what
   the model is asked. A models.dev refresh that changes a limit changes it."
  [{:keys [provider model]}]
  (registry/ensure-models-loaded!)
  (let [id (when model (registry/resolve-alias model))
        definition (when id (registry/get-model id))]
    (merge (dissoc definition :id :name :pricing :list-price-of)
           {:provider (or provider (:provider definition))
            :model id})))

(defn- tool-inputs
  "The tools a model is offered, as it is offered them: each one's name,
   description and input schema, in name order."
  [tool-map]
  (->> (vals tool-map)
       (map (fn [{:keys [name description parameters]}]
              {:name name :description description :parameters parameters}))
       (sort-by :name)
       vec))

(defn- pin-model
  "`agent` with its policy's model alias resolved: the model its Run calls
   throughout, whatever the alias names later."
  [agent]
  (registry/ensure-models-loaded!)
  (cond-> agent
    (get-in agent [:agent/model-policy :model])
    (update-in [:agent/model-policy :model] registry/resolve-alias)))

(defn- model-entry-changed!
  "When the registry entries of the models a Run calls (`specs`, nil for one
   it does not call) are no longer the ones its inputs id was computed from
   (`before`, their `model-inputs` then), the Run drops its inputs id and
   says so (`:inputs-drifted?`): its model calls were not all shaped alike,
   so no single id says what it was presented with, and it enters no
   Scorecard (a resume runs its cell again)."
  [supervisor run-id before specs]
  (when (not= before (mapv #(some-> % model-inputs) specs))
    (tel/log! {:level :warn :id ::model-entry-changed :data {:run/id run-id :models (mapv :model specs)}}
              "A model's registry entry changed during the Run: it records no inputs id")
    (update-llm-metrics! supervisor #(-> % (dissoc :inputs-id) (assoc :inputs-drifted? true)))))

(defmacro ^:private with-model-entry-checked
  "`body` (one or more model calls), then `model-entry-changed!`."
  [supervisor run-id before specs & body]
  `(try ~@body
        (finally (model-entry-changed! ~supervisor ~run-id ~before ~specs))))

(defn- compaction-spec
  "The model an LLM Run of `agent` compacts its conversation with, resolved,
   as `chat.agent/run-agent-turn!` picks it (`:compaction-model`, else the
   registry default), or nil when it does not compact."
  [agent model-spec]
  (let [{:keys [auto-compact? compaction-model] :or {auto-compact? true}} (:agent/program agent)]
    ;; the registry default comes from models.edn: loaded first, whoever asks
    (registry/ensure-models-loaded!)
    (when auto-compact?
      {:model (registry/resolve-alias (or compaction-model
                                          (registry/get-default :compaction-model)
                                          "accounts/fireworks/models/minimax-m3"))
       :provider (or (:provider model-spec) :fireworks)})))

(defn- llm-inputs
  "The `run-inputs` of an LLM Run that starts with `instructions`, offers
   `tool-map`, calls the model `model-spec`, compacts with `compaction` (a
   model spec, or nil) and installs the sandbox extensions `sandbox` (the
   AgentDef's `:sandbox` ids): what they resolve to in this process's
   registry (`extensions/refs`), since a changed extension changes what the
   candidate can call while its name, in the AgentDef, stays the same."
  [instructions tool-map model-spec compaction sandbox]
  (cond-> {:inputs/kind :llm
           :prompt-id (prompt-id instructions)
           :tools (tool-inputs tool-map)
           :model (model-inputs model-spec)}
    compaction (assoc :compaction (model-inputs compaction))
    (seq sandbox) (assoc :sandbox (extensions/refs sandbox))))

(defn- with-experiment-inputs [inputs host-options]
  (cond-> inputs
    (and inputs (contains? host-options :experiment-capabilities))
    (assoc :experiment-capabilities
           (into {} (for [kind (cond-> [:evaluators :world-setups]
                                 (contains? (:experiment-capabilities host-options) :invocations) (conj :invocations))]
                      [kind (if (= :invocations kind)
                              (invocations/refs (:experiment-capabilities host-options))
                              (set (keys (get-in host-options [:experiment-capabilities kind]))))])))
    (and inputs (contains? host-options :delegation-policy))
    (assoc :delegation-policy (delegation/inputs (:delegation-policy host-options)))))

(defn run-inputs
  "What a Run of `agent` will present to its candidate when it starts now,
   as portable data: the inputs resolved at Run start that are not in the
   AgentDef or the EnvironmentDef, so not in an ExperimentDef's identity.

   - An LLM program: the assembled system prompt (by its `prompt-id`), the
     tools offered (`tool-inputs`), the resolved model (`model-inputs`) and,
     when its AgentDef names sandbox extensions, what they resolve to in the
     process's extension registry (`extensions/refs`), which the Run installs.
   - A protocol (`protocol`, the environment's): its ref, the resolved model
     of the AgentDef's policy, and what the protocol's own `:inputs` says
     (`dvergr.agent.evaluation/make-protocol`).
   - A SCI program: exact program data, interpreter version and resolved
     sandbox extension revisions. Task data remains the EnvironmentDef's input.
   - Any other program: nil. A deterministic program (`:echo`, `:scripted`)
     resolves nothing at Run start: everything it does is in its AgentDef.

   The LLM case reads the bound context's workspace, as the Run reads its
   world's (`llm-instructions`). `inputs-id` is its content id."
  ([agent] (run-inputs agent nil))
  ([agent protocol]
   (cond
     protocol
     (let [agent (pin-model agent)]
       (cond-> {:inputs/kind :protocol
                :protocol (:ref protocol)
                :model (model-inputs (:agent/model-policy agent))}
         (:inputs protocol)
         (assoc :protocol-inputs
                (let [inputs ((:inputs protocol) agent)]
                  (when-not (roster/data-value? inputs)
                    (throw (ex-info "Protocol :inputs must return portable data"
                                    {:type ::non-portable-protocol-inputs :protocol (:ref protocol)})))
                  inputs))))

     (= :llm (get-in agent [:agent/program :kind]))
     (let [tool-map (tools/normalize-tools (or (:agent/tools agent) #{}))]
       (let [spec (resolve-model-spec agent)]
         (cond-> (llm-inputs (llm-instructions agent tool-map) tool-map spec (compaction-spec agent spec)
                             (:agent/sandbox agent))
           (get-in agent [:agent/program :execution-mode])
           (assoc :execution-mode (get-in agent [:agent/program :execution-mode])
                  :provider-policy (claude-code/execution-policy)))))

     (= :sci (get-in agent [:agent/program :kind]))
     (cond-> {:inputs/kind :sci :program (:agent/program agent)
              :interpreter-version interpreter-version}
       (seq (:agent/sandbox agent)) (assoc :sandbox (extensions/refs (:agent/sandbox agent))))

     :else nil))
  ([agent protocol host-options]
   (with-experiment-inputs (run-inputs agent protocol) host-options)))

(defn inputs-id
  "The content id of `run-inputs` (`:attempt/inputs-id`); nil for nil (a
   deterministic program, which resolves nothing at Run start)."
  [inputs]
  (when (some? inputs) (hasch/uuid [:dvergr/run-inputs inputs])))

(defn- new-program-context
  [control-room work-room run-id agent budget-dollars chat-id supervisor]
  (let [system-id (room-context/room-system-id work-room)
        ;; Model messages, tool results, authorization receipts, and costs are
        ;; control-plane evidence. Keep that trace in the parent even when the
        ;; work plane is later discarded.
        trace-db (some-> control-room :store :conn)
        chat-ctx (turn/new-working-ctx
                  {:execution-ctx (:ctx work-room)
                   :chat-id chat-id
                   :title (str "agent-task " (name (:agent/id agent)))
                   :agent-id (:agent/id agent)
                   :sandbox-extensions (:agent/sandbox agent)
                   :budget-dollars budget-dollars
                   :db-conn trace-db
                   :kb-conn (when system-id (system-rooms/room-kb-conn system-id))
                   :room-id system-id
                   :room-runtime-id (:id work-room)
                   :room-incarnation (:incarnation work-room)
                   ;; provider-free children, or children whose grant funds
                   ;; their provider calls (`child-program-authority`)
                   :agent-program-ceiling (child-program-authority run-id supervisor)})]
    {:chat-ctx chat-ctx
     :owned-db? (nil? trace-db)}))

(defn- llm-tool-context [control-room room chat-ctx agent run-id supervisor]
  (let [system-id (room-context/room-system-id room)
        tool-map (tools/normalize-tools (or (:agent/tools agent) #{}))
        ;; Ordinary room/task effects target the branched work store. A
        ;; store-less Room uses the per-Run ephemeral DB, which is itself owned
        ;; by the work context and closed before settlement.
        work-db (or (some-> room :store :conn)
                    (when-not (some-> control-room :store :conn)
                      (:db-conn chat-ctx)))]
    {:tool-map tool-map
     :tool-ctx
     (-> (tools/make-context
          {:db-conn work-db
           :chat-ctx chat-ctx
           :sci-ctx (chat-context/sci-context-in chat-ctx (:ctx room))
           :tools tool-map
           :isolation :sci
           :execution-ctx (:ctx room)
           :control-room control-room
           ;; Delegation tools are adapters over the same Run interpreter as
           ;; the SCI API. Carry structural parentage and the identical
           ;; attenuation policy across this boundary explicitly.
           :run-id run-id
           :agent-program-ceiling (child-program-authority run-id supervisor)
           :actor (:agent/id agent)
           :model-policy (:agent/model-policy agent)})
         (assoc :workspace-roots
                (when system-id (system-rooms/room-load-roots system-id)))
         (assoc :kb-conn
                (when system-id (system-rooms/room-kb-conn system-id)))
         (assoc :room room))}))

;; ---------------------------------------------------------------------------
;; Turn savepoints (doc/runs.md, Resuming a Run)
;; ---------------------------------------------------------------------------

(defonce ^:private resuming
  ;; stopped Run id -> {:deferred :world}: the continuation `hydrate-into!`
  ;; started for it, until `resume!` delivers the resumed Run's result
  (atom {}))

(defn continue-llm-run
  "The continuation a turn savepoint names (its portable `:resume`). Hydrating
   the savepoint (`resume!`, through `savepoint.portable/hydrate-into!`) runs it
   in the new Run's world, prepared as the savepoint recorded it; the new Run
   continues the stopped one's loop from step `step`, and this spin resolves
   with that Run's result, so the session's end is the resumed Run's end."
  [run-id _step _value]
  (let [done (sync/deferred)]
    (swap! resuming assoc run-id {:deferred done :world ec/*execution-context*})
    (sp/spin (sp/await done))))

(defn- resumed! [run-id result]
  (when-let [{:keys [deferred world]} (get @resuming run-id)]
    (swap! resuming dissoc run-id)
    (binding [ec/*execution-context* world]
      (deferred result))))

(defn- turn-savepoint-handler
  "Pure metadata handshake; native publication never runs on the world's drain."
  [origin caller published point]
  (if-not (= (:fork-id (:ctx origin)) (:fork-id (:savepoint/world point)))
    (savepoint/resume point (:savepoint/payload point))
    (binding [ec/*execution-context* caller]
      (published {:point point}))))

(defn- persist-turn-on-worker! [point]
  ;; snapshot-id may perform native IO (for example Git rev-parse). Both the
  ;; call and its original callback belong to this already-supervised worker.
  ;; A late callback only settles local data; it can never publish a checkpoint.
  (let [done (CountDownLatch. 1)
        outcome (volatile! nil)
        interrupted? (volatile! false)
        finish! (fn [result] (vreset! outcome result) (.countDown done))]
    (when (.isInterrupted (Thread/currentThread))
      (throw (InterruptedException. "Turn capture was interrupted before persistence")))
    ((portable/persist point)
     #(finish! {:data %}) #(finish! {:error %}))
    (loop []
      (when-not (try (.await done) true
                     (catch InterruptedException _ (vreset! interrupted? true) false))
        (recur)))
    (when (or @interrupted? (.isInterrupted (Thread/currentThread)))
      (.interrupt (Thread/currentThread))
      (throw (InterruptedException. "Turn capture was interrupted during persistence")))
    (if-let [error (:error @outcome)]
      (do
        (when (loop [cause error seen #{}]
                (cond (nil? cause) false
                      (contains? seen cause) false
                      (instance? InterruptedException cause) true
                      :else (recur (.getCause ^Throwable cause) (conj seen cause))))
          (.interrupt (Thread/currentThread)))
        (throw error))
      (:data @outcome))))

(defn- install-turn-savepoints!
  "Own an independent session for this Run. Its ordinary savepoint forks share
   it, but hired Runs open their own; all funded worlds must settle before
   their owning Run returns its wallet. LLM turn savepoints are persisted by
   the installed handler."
  [control-room work-room]
  (let [world (:ctx work-room)
        table {:conversation/turn #(savepoint/resume % (:savepoint/payload %))}]
    ;; world/open! masks inherited session state at fork creation. Opening must
    ;; fail rather than silently reuse another computation's cleanup owner.
    (savepoint/open! world (cond-> {:handlers table :purpose :run
                                    :seed (savepoint/seed world)}
                             (satisfies? room-store/PResourceStore (:store control-room))
                             (assoc :authority (authority/authority control-room))))))

(defn- close-session-reconciled!
  "Financial cleanup is an ownership fence, not a best-effort timeout.
   Keep the session and its live recovery handles reachable until close has
   acknowledged success. Never start a second close while the first is still
   in flight; a slow callback is not evidence that the operation failed."
  [session]
  (loop [delay-ms 25]
    (let [done (promise)
          _ (try
              ((savepoint/close! session)
               (fn [_] (deliver done {:closed? true}))
               (fn [error] (deliver done {:error error})))
              (catch Throwable error
                (deliver done {:error error})))
          ;; Cleanup workers are never cancelled with execution workers. A
          ;; failed close retains this worker lease while the scope owns the
          ;; unreturned funds; no terminal Run or parent refund can overtake it.
          {:keys [closed? error]} @done]
      (if closed?
        :closed
        (do
          (tel/log! {:level :warn :id ::session-cleanup-recovery
                     :data {:session/id (:id session)
                            :retry-ms delay-ms :error (ex-message error)}}
                    "Savepoint cleanup failed; retaining Run ownership for recovery")
          (Thread/sleep delay-ms)
          (recur (min 1000 (* 2 delay-ms))))))))

(defn- turn-savepoint!
  "Publish the gap after model step `step` of Run `run-id` as a savepoint in
   the work world (where its systems are, so the portable form records their
   snapshots). A Spin in the calling (control) context that completes once
   the handler continued it; with no handler it completes at once."
  [work-room run-id step payload supervisor prepared]
  (let [done (sync/deferred)
        published (sync/deferred)
        caller ec/*execution-context*
        world (:ctx work-room)
        previous (get (savepoint/handlers world) :conversation/turn)]
    (sp/spin
     (try
       (savepoint/install-handlers!
        world {:conversation/turn #(turn-savepoint-handler work-room caller published %)})
       (binding [ec/*execution-context* world]
         (sync/spawn! (sp/spin (savepoint :conversation/turn payload
                                          {:resume `continue-llm-run :args [run-id step]}))
                      {:on-success (fn [_] (binding [ec/*execution-context* caller] (done :ok)))
                       :on-error (fn [e] (binding [ec/*execution-context* caller]
                                           (published {:error e})
                                           (done {::savepoint-error e})))}))
       (let [{:keys [point error]} (sp/await published)
             result (when point
                      (sp/await
                       (worker-result-spin
                        (start-worker! supervisor
                                       #(binding [ec/*execution-context* world]
                                          (let [data (persist-turn-on-worker! point)]
                                            (run/record-savepoint! run-id work-room data prepared)))))))
             failure (or error (when (worker-error? result) (::worker-error result)))]
         (when failure
           (tel/log! {:level :warn :id ::savepoint-not-recorded
                      :data {:run/id run-id :error (ex-message failure)}}
                     "Turn capture failed; preserving the prior durable checkpoint"))
         (when point (savepoint/resume point (:savepoint/payload point)))
         (sp/await done))
       (finally
         (savepoint/install-handlers! world {:conversation/turn previous}))))))

(defn- model-step-usage [budget uncertain?]
  (cond-> (select-keys budget [:used :by-type :unreported-types])
    uncertain? (update :unreported-types (fnil into #{}) [:input-tokens :output-tokens])))

(defn- completed-model-step-usage [budget outcome cancelled? failure]
  ;; :error is also a model-quality verdict after a measured response (e.g.
  ;; repeated empty output). Provider failures already mark the chat budget
  ;; incomplete at the call boundary. Only explicit :model verdicts occur
  ;; after response accounting: :error may also be an exception during that
  ;; accounting (malformed usage). Never treat that as an acknowledged step.
  (model-step-usage budget
                    (or cancelled?
                        (not (or (contains? #{:continue :complete} outcome)
                                 (turn-outcome/stopped? outcome)
                                 (and (= :error outcome) (= :model (:kind failure))))))))

(defn- execute-llm-program
  "Run a bounded Dvergr-native model/tool loop. Each blocking model round-trip
   runs under a supervised worker; this Spin retains orchestration, activity
   correlation, cleanup, and cancellation in the Room's execution graph."
  [control-room work-room run-id chat-id agent task trigger supervisor limits]
  (let [{:keys [max-model-steps budget-dollars auto-compact?]
         :or {max-model-steps default-max-model-steps
              budget-dollars 1.0
              auto-compact? true}} (:agent/program agent)
        max-model-steps (min max-model-steps
                             (or (:max-model-steps limits) max-model-steps))
        budget-dollars (min (double budget-dollars)
                            (double (or (:budget-dollars limits)
                                        budget-dollars)))
        ;; the one ledger (doc/worlds-and-forks.md, Worlds, savepoints and budgets): a Run allocated
        ;; microdollars spends its wallet, and a resumed Run what the stopped one
        ;; left, not a fresh budget
        ;; A resumed Run's wallet holds what the stopped one left, so its
        ;; budget is the configured one less the stopped Run's spend, capped by
        ;; that wallet: the spend is subtracted once, not from the wallet again.
        spend-wallet (resource/spend-wallet control-room run-id)
        budget-dollars (cond-> budget-dollars
                         (::resume agent)
                         (-> (- (/ (double (or (get-in agent [::resume :spent]) 0)) 1e6)) (max 0.0))
                         spend-wallet
                         (min (/ (double (resource/wallet-microdollars control-room run-id)) 1e6)))
        ;; files the task is scored on: a turn that ends without them is
        ;; told once and continues
        required-outputs (seq (:required-outputs limits))
        effective-limits {:max-model-steps max-model-steps
                          :budget-dollars budget-dollars}]
    (sp/spin
     ;; Context/schema/SCI construction and credential discovery may block.
     ;; Build the whole runtime bundle under the same supervised worker as
     ;; provider calls, before exposing it to the orchestration Spin.
     (let [init-worker
           (start-worker!
            supervisor
            (fn []
              (when-let [expected (get-in agent [:agent/program :provider-policy])]
                (when-not (= expected (claude-code/execution-policy))
                  (throw (ex-info "Admitted Claude Code policy changed" {:type ::provider-policy-drift}))))
              (when (get-in agent [:agent/program :single-use?])
                ;; Each physical entry needs a fresh debit. The Run owns
                ;; exactly ONE conserved admission, so concurrent/replayed
                ;; initialization cannot both pass Kontor's atomic validation.
                (resource/consume! control-room run-id
                                   {:id (random-uuid) :resources {resource/cli-run-admissions 1M}}))
              (let [{:keys [chat-ctx owned-db?]}
                    (new-program-context control-room work-room run-id agent
                                         budget-dollars chat-id supervisor)
                    _ (when owned-db?
                        (register-cleanup!
                         supervisor #(chat-context/close-chat! chat-ctx)))
                    {:keys [tool-map tool-ctx]}
                    (llm-tool-context control-room work-room chat-ctx agent run-id
                                      supervisor)
                    ;; every model step calls the model the inputs id names:
                    ;; an alias re-registered mid-Run does not move it
                    model-spec (let [spec (resolve-model-spec agent)]
                                 ;; aliases come from models.edn too
                                 (registry/ensure-models-loaded!)
                                 (cond-> spec
                                   (:model spec) (update :model registry/resolve-alias)))
                    instructions (llm-instructions agent tool-map)
                    ;; resuming: the conversation so far is the stopped Run's,
                    ;; system prompt included
                    resumed (when (::resume agent) (get-in agent [::resume :messages]))
                    sent (if (::resume agent)
                           (some (fn [m] (when (= :system (or (:message/role m) (:role m)))
                                           (or (:message/content m) (:content m))))
                                 resumed)
                           instructions)
                    ;; what the model is presented with: the system prompt it
                    ;; is sent (a resumed Run's is the one it started with)
                    compaction (compaction-spec agent model-spec)
                    inputs (with-experiment-inputs
                             (cond-> (llm-inputs sent tool-map model-spec compaction (:agent/sandbox agent))
                               (get-in agent [:agent/program :execution-mode])
                               (assoc :execution-mode (get-in agent [:agent/program :execution-mode])
                                      :provider-policy (claude-code/execution-policy))) supervisor)]
                (update-llm-metrics!
                 supervisor
                 (constantly
                  (cond->
                   {:prompt-id (:prompt-id inputs)
                    ;; what this Run presented to its model, as `run-inputs`
                    ;; predicts it: what a resume compares
                    :inputs-id (inputs-id inputs)
                    :provider (:provider model-spec)
                    :model (:model model-spec)
                    :model-steps 0
                    :usage {}}
                    (seq limits) (assoc :limits effective-limits))))
                (if (::resume agent)
                  (chat-context/replace-messages! chat-ctx resumed)
                  (do (chat-context/add-message!
                       chat-ctx {:role :system :content instructions})
                      (chat-context/add-message!
                       chat-ctx {:role :user :content (result-content task)})))
                {:chat-ctx chat-ctx
                 :tool-map tool-map
                 :tool-ctx tool-ctx
                 :compaction compaction
                 :model-inputs [(:model inputs) (:compaction inputs)]
                 :provider-policy (:provider-policy inputs)
                 :model-spec model-spec})))
           initialized (sp/await (worker-result-spin init-worker))]
       (when (or (= ::worker-cancelled initialized)
                 (run/cancel-requested? run-id))
         (cancelled! run-id))
       (when (worker-error? initialized)
         (throw (ex-info "LLM runtime initialization failed"
                         {:type ::runtime-initialization-failed
                          :agent/id (:agent/id agent)}
                         (::worker-error initialized))))
       (let [{:keys [chat-ctx tool-map tool-ctx model-spec model-inputs compaction provider-policy]} initialized
             posted (ratom/create-atom 0)
             ;; whether this Run's model called tools, and whether it then
             ;; ended silently (`chat-agent/*run-activity*`); a resumed Run
             ;; starts without it, so silence right after a resume still fails
             activity (volatile! {})]
         (loop [model-step (if-let [{:keys [step]} (::resume agent)] (inc step) 0)]
           (when (run/cancel-requested? run-id)
             (cancelled! run-id))
           (let [failure (volatile! nil)
                 ;; An in-flight call has no complete usage report yet. If
                 ;; cancellation bypasses the post-worker snapshot, terminal
                 ;; metrics must not retain the previous step's known totals.
                 _ (update-llm-metrics! supervisor update :usage model-step-usage true)
                 call
                 (start-worker!
                  supervisor
                  (fn []
                    (when (and provider-policy (not= provider-policy (claude-code/execution-policy)))
                      (throw (ex-info "Claude Code execution policy changed" {:type ::provider-policy-drift})))
                    (binding [chat-agent/*turn-failure* failure
                              chat-agent/*run-activity* activity
                              ;; the step is the charge scope: its debits have
                              ;; ids of this Run and step, so charging the step
                              ;; again does not charge it twice
                              resource/*spend-wallet*
                              (some-> spend-wallet
                                      (resource/spending [:llm-step run-id model-step]))
                              resource/*model-scope*
                              (some-> (resource/model-scope control-room run-id)
                                      (assoc :cancel? #(cancelled-or-expired? supervisor run-id)
                                             :dispatch-owner (:dispatch-owner supervisor)))]
                      (let [outcome (with-model-entry-checked supervisor run-id model-inputs [model-spec compaction]
                                      (chat-agent/run-agent-turn!
                                       chat-ctx
                                       {:provider (:provider model-spec)
                                        :model (:model model-spec)
                        ;; The SAME normalized map defines both the model schema
                        ;; and execute-side authority. Empty means no tools.
                                        :tools tool-map
                                        :tool-ctx tool-ctx
                                        :cancel? (turn/cancel?-fn
                                                  chat-ctx (:ctx work-room)
                                                  (fn [] (cancelled-or-expired? supervisor run-id)))
                                        :auto-compact? auto-compact?
                          ;; the one its inputs id names
                                        :compaction-model (:model compaction)
                      ;; The existing single-exchange core still calls this
                      ;; trace field `turn-number`; semantically it is a model
                      ;; integration step, not a conversational turn.
                                        :turn-number model-step
                                        :run-id run-id
                        ;; The world this Run works in, for an adapter whose
                        ;; model is an external agent (claude -p): it invokes
                        ;; that agent INTO this room over MCP.
                                        :model-opts (cond-> {:room-id (let [id (:id work-room)]
                                                                        (if (keyword? id) (subs (str id) 1) (str id)))
                                       ;; whose run token it gets (dvergr.principal)
                                                             :actor (:agent/id agent) :run-id run-id}
                                                      (get-in agent [:agent/program :execution-mode])
                                                      (assoc :execution-mode (get-in agent [:agent/program :execution-mode])
                                                             :expected-provider-policy provider-policy))}))]
                        ;; ended its turn without the files the environment
                        ;; requires: say so once, and go on
                        (if-let [note (when (and (= :complete outcome) required-outputs
                                                 (not (:outputs-nudged? @activity)))
                                        (missing-outputs-note tool-ctx required-outputs))]
                          ;; the turn's silent finish is superseded too
                          (do (vswap! activity #(-> % (assoc :outputs-nudged? true) (dissoc :silent?)))
                              (chat-context/add-system-note! chat-ctx note :important? true)
                              :continue)
                          outcome)))))
                 outcome (sp/await (worker-result-spin call))
                 _ (worker-stage! supervisor (:id call) :normal :resumed (:started-nanos call))
                 budget (chat-context/get-budget chat-ctx)]
             (update-llm-metrics!
              supervisor merge {:model-steps (inc model-step)
                                :usage (completed-model-step-usage budget outcome
                                                                   (run/cancel-requested? run-id) @failure)})
             ;; posting the turn's rows is a durable write: off the drain
             (sp/await (blocking/blocking
                        #(turn/post-turn-activity! control-room (:agent/id agent) chat-ctx posted
                                                   run-id trigger)))
             (cond
               (or (= ::worker-cancelled outcome)
                   (run/cancel-requested? run-id))
               (cancelled! run-id)

               (worker-error? outcome)
               (let [e (::worker-error outcome)
                     f {:kind :infrastructure
                        :cause (str (ex-message e))
                        :model-step model-step}]
                 (update-llm-metrics! supervisor assoc :failure f)
                 (throw (ex-info (str "LLM model step failed: " (:cause f))
                                 {:type ::llm-model-step-failed
                                  :agent/id (:agent/id agent)
                                  :model-step model-step
                                  :failure f}
                                 e)))

               (= :cancelled outcome)
               (cancelled! run-id)

               (= :error outcome)
               ;; Why the turn failed, as the turn recorded it: the model's own
               ;; failure is a verdict about the model, not a fault to retry.
               (let [f (assoc (or @failure {:kind :infrastructure :cause "unknown turn error"})
                              :model-step model-step)]
                 (update-llm-metrics! supervisor assoc :failure f)
                 (throw (ex-info (str "LLM model step failed (" (name (:kind f)) "): " (:cause f))
                                 {:type ::llm-model-step-failed
                                  :agent/id (:agent/id agent)
                                  :model-step model-step
                                  :failure f})))

               (turn-outcome/stopped? outcome)
               (let [reason (:reason outcome)]
                 (update-llm-metrics! supervisor assoc :failure
                                      {:kind :model :cause turn-outcome/stop-diagnostic
                                       :reason reason :model-step model-step})
                 (program-result :failed nil reason turn-outcome/stop-diagnostic))

               (= :complete outcome)
               (if-let [value (if (:silent? @activity) "" (last-assistant-content chat-ctx))]
                 (program-result :completed value)
                 (throw (ex-info "LLM program completed without an assistant result"
                                 {:type ::missing-llm-result
                                  :agent/id (:agent/id agent)})))

               (not= :continue outcome)
               (throw (ex-info "Unknown LLM model-step outcome"
                               {:type ::unknown-model-step-outcome
                                :outcome outcome
                                :model-step model-step}))

               (chat-context/budget-exceeded? chat-ctx)
               ;; An interactive Run waits for more budget. A Run under
               ;; verification (deferred settlement: an evaluation) has no one
               ;; to extend it, and a waiting Run cannot be certified: running
               ;; out of budget is then its verdict, scored like any failure.
               (if (= :deferred (some-> work-room :meta deref :settlement-policy))
                 (let [f {:kind :model :cause "budget exhausted" :model-step model-step}]
                   (update-llm-metrics! supervisor assoc :failure f)
                   (throw (ex-info "LLM program exhausted its budget"
                                   {:type ::budget-exhausted
                                    :agent/id (:agent/id agent)
                                    :failure f})))
                 (program-result :waiting nil :budget-exhausted))

               (>= (inc model-step) max-model-steps)
               (do (update-llm-metrics! supervisor assoc :failure
                                        {:kind :model :cause "model-step bound reached"
                                         :model-step model-step})
                   (throw (ex-info "LLM program exceeded its model-step bound"
                                   {:type ::model-step-limit-exceeded
                                    :agent/id (:agent/id agent)
                                    :max-model-steps max-model-steps})))

               :else
               (do
                 ;; the gap after this step: a point to resume the Run from
                 (let [prepared
                       (sp/await
                        (worker-result-spin
                         (start-worker! supervisor
                                        #(binding [ec/*execution-context* (:ctx work-room)]
                                           (conversation-capture/prepare
                                            (chat-context/get-messages chat-ctx) tool-map)))))]
                   (cond
                     (= ::worker-cancelled prepared) (cancelled! run-id)
                     (worker-error? prepared)
                     (tel/log! {:level :warn :id ::conversation-not-captured
                                :data {:run/id run-id :error (ex-message (::worker-error prepared))}}
                               "Conversation capture failed; preserving the prior checkpoint")
                     :else
                     (sp/await (turn-savepoint!
                                work-room run-id model-step
                                {:run run-id :step model-step :task task
                                 :agent (dissoc agent ::protocol ::resume)
                                 :conversation (conversation-capture/manifest prepared)
                                 :control-room (:id control-room)}
                                supervisor prepared))))
                 (recur (inc model-step)))))))))))

(declare execute-agent-program)

(defn- execute-protocol-program
  "Run an environment protocol as this Run's program.

   A protocol is a trusted host capability (see
   `dvergr.agent.evaluation/make-protocol`): instead of interpreting the
   AgentDef's program against one task, the Run hosts an interaction inside
   its isolated work Room, e.g. a conversation between the candidate and an
   environment driver. `:run` is blocking host work, so it executes under a
   supervised worker and is interrupted by cancellation. It returns the Run's
   portable value.

   Model calls a protocol makes are the Run's: the worker runs under the same
   `resource/*model-scope*` as an LLM program's turns (nil when the Run was
   allocated no `model-dispatches`), so they spend the Run's admissions. The
   binding is the worker thread's; a protocol whose model calls happen on
   other threads (Room participants, futures) gets the scope as `:model-scope`
   and binds it there. Their cost is the Run's too: with a microdollar
   wallet, `resource/*spend-wallet*` is bound to it (charge scope `[:protocol
   run-id]`), and a protocol carries that binding to its other threads as
   `dvergr.benchmarks.live/scoped` does.

   A model turn the protocol runs on the worker thread (`chat.agent`'s
   `run-agent-turn!`) that fails for `:infrastructure` (transport, provider
   limits; see `chat-agent/*turn-failure*`) fails the Run, whatever the
   protocol returns: the path to the model failed, so the Attempt is a fault
   to re-run, not a verdict on the model (a failed turn leaves no answer, and
   no answer can pass a task, e.g. BFCL irrelevance). A protocol that binds
   `*turn-failure*` itself decides for its own turns; one whose turns run on
   other threads must make such a failure fail its `:run`."
  [control-room work-room run-id agent task supervisor protocol]
  (sp/spin
   (let [failure (volatile! nil)
         call (start-worker!
               supervisor
               (fn []
                 (let [;; the model the inputs id names, resolved once
                       pinned (pin-model (dissoc agent ::limits ::protocol))
                       inputs (run-inputs pinned protocol supervisor)]
                   ;; what the protocol presents, as `run-inputs` predicts it
                   (update-llm-metrics!
                    supervisor
                    (constantly {:inputs-id (inputs-id inputs)}))
                   (let [scope (some-> (resource/model-scope control-room run-id)
                                       (assoc :cancel? #(cancelled-or-expired? supervisor run-id)
                                              :dispatch-owner (:dispatch-owner supervisor)))]
                     (binding [resource/*model-scope* scope
                               ;; the protocol's model calls, the candidate's
                               ;; and its driver's, are paid from the Run's
                               ;; wallet (`live/scoped` carries this binding
                               ;; to the threads they run on)
                               resource/*spend-wallet*
                               (some-> (resource/spend-wallet control-room run-id)
                                       (resource/spending [:protocol run-id]))
                               chat-agent/*turn-failure* failure]
                       (with-model-entry-checked supervisor run-id [(:model inputs)]
                         [(:agent/model-policy pinned)]
                         ((:run protocol)
                          {:control-room control-room
                           :room work-room
                           :run/id run-id
                           :agent pinned
                           :task task
                           :limits (::limits agent)
                           :model-scope scope
                           :cancelled? (fn [] (cancelled-or-expired? supervisor run-id))})))))))
         outcome (sp/await (worker-result-spin call))]
     (cond
       (or (= ::worker-cancelled outcome)
           (run/cancel-requested? run-id))
       (cancelled! run-id)

       (worker-error? outcome)
       (throw (ex-info (str "Environment protocol failed: "
                            (ex-message (::worker-error outcome)))
                       {:type ::protocol-failed
                        :agent/id (:agent/id agent)}
                       (::worker-error outcome)))

       (= :infrastructure (:kind @failure))
       (throw (ex-info (str "Environment protocol failed: a model turn failed: "
                            (:cause @failure))
                       {:type ::protocol-failed
                        :agent/id (:agent/id agent)
                        :failure @failure}))

       (not (roster/data-value? outcome))
       (throw (ex-info "Environment protocol must return portable data"
                       {:type ::non-portable-protocol-value
                        :agent/id (:agent/id agent)}))

       :else (program-result :completed outcome)))))

(defn- execute-sci-program
  [control-room work-room run-id chat-id agent task supervisor]
  (let [program (:agent/program agent)
        deadline (min (or (:deadline-nanos supervisor) Long/MAX_VALUE)
                      (+ (System/nanoTime) (* 1000000 (long (:timeout-ms program)))))
        check! (fn []
                 (when (or (run/cancel-requested? run-id) (:cancelled? @(:state supervisor)))
                   (cancelled! run-id))
                 (when (>= (System/nanoTime) deadline)
                   (throw (ex-info "SCI execution deadline expired" {:type ::sci-timeout}))))]
    (sp/spin
     (let [worker (start-worker!
                   supervisor
                   (fn []
                     (check!)
                     (let [{:keys [chat-ctx owned-db?]}
                           (new-program-context control-room work-room run-id agent 0.0 chat-id supervisor)]
                       (register-cleanup!
                        supervisor
                        #(if owned-db?
                           (chat-context/close-chat! chat-ctx)
                           (chat-context/release-sci-in! chat-ctx (:ctx work-room))))
                       (check!)
                       (let [inputs (run-inputs agent nil supervisor)
                             _ (update-llm-metrics! supervisor merge {:inputs-id (inputs-id inputs)})
                             result (sci-program/execute!
                                     (chat-context/sci-context-in chat-ctx (:ctx work-room))
                                     program task check!)]
                         (check!)
                         (select-keys result [:value])))))
           remaining-ms (max 1 (long (Math/ceil (/ (- deadline (System/nanoTime)) 1000000.0))))
           outcome (sp/await (comb/timeout (worker-result-spin worker) remaining-ms ::sci-timeout))]
       (when (= ::sci-timeout outcome)
         (cancel-supervisor! supervisor)
         (throw (ex-info "SCI execution deadline expired" {:type ::sci-timeout})))
       (when (or (= ::worker-cancelled outcome) (run/cancel-requested? run-id))
         (cancelled! run-id))
       (when (worker-error? outcome)
         ;; The sticky worker deadline may win before the graph timer. Fatal
         ;; source failures must cancel owned children on either frontier.
         (cancel-supervisor! supervisor)
         (throw (::worker-error outcome)))
       (try (check!)
            (catch Throwable error
              (cancel-supervisor! supervisor)
              (throw error)))
       (program-result :completed (:value outcome))))))

(defn- execute-program
  [control-room work-room run-id chat-id agent task trigger supervisor]
  (if-let [protocol (::protocol agent)]
    (execute-protocol-program control-room work-room run-id agent task
                              supervisor protocol)
    (execute-agent-program control-room work-room run-id chat-id agent task
                           trigger supervisor)))

(defn- execute-agent-program
  [control-room work-room run-id chat-id agent task trigger supervisor]
  (case (get-in agent [:agent/program :kind])
    :llm (execute-llm-program control-room work-room run-id chat-id agent task
                              trigger supervisor (::limits agent))
    :sci (execute-sci-program control-room work-room run-id chat-id agent task supervisor)
    (execute-deterministic-program run-id agent task)))

(def ^:private hire-option-keys
  #{:task :from :parent-run :settlement :resources :limits :role})

(defn- validate-program!
  [{:keys [kind delay-ms max-model-steps budget-dollars auto-compact? compaction-model execution-mode single-use? provider-policy]
    :as program} agent-ref agent]
  (let [allowed (case kind
                  :scripted #{:kind :delay-ms :reply :result}
                  :echo #{:kind :delay-ms}
                  :sci #{:kind :source :entrypoint :args :timeout-ms}
                  :llm #{:kind :max-model-steps :budget-dollars
                         :auto-compact? :compaction-model :execution-mode :single-use? :provider-policy}
                  nil)]
    (when-not allowed
      (throw (ex-info "hire! does not have an interpreter for this program"
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
    (when (and (= :llm kind)
               (not (and (integer? (or max-model-steps default-max-model-steps))
                         (<= 1 (or max-model-steps default-max-model-steps) 256))))
      (throw (ex-info "LLM program :max-model-steps must be an integer from 1 to 256"
                      {:type ::invalid-max-model-steps
                       :max-model-steps max-model-steps})))
    (when (and (= :llm kind)
               (not (and (number? (or budget-dollars 1.0))
                         (Double/isFinite (double (or budget-dollars 1.0)))
                         (pos? (double (or budget-dollars 1.0))))))
      (throw (ex-info "LLM program :budget-dollars must be positive"
                      {:type ::invalid-budget :budget-dollars budget-dollars})))
    (when (and (= :llm kind) (some? auto-compact?) (not (boolean? auto-compact?)))
      (throw (ex-info "LLM program :auto-compact? must be boolean"
                      {:type ::invalid-auto-compact :auto-compact? auto-compact?})))
    (when (and (= :llm kind) compaction-model (not (string? compaction-model)))
      (throw (ex-info "LLM program :compaction-model must be a string"
                      {:type ::invalid-compaction-model :compaction-model compaction-model})))
    (when (and execution-mode
               (not (and (= :text execution-mode)
                         (= :claude-code (get-in agent [:agent/model-policy :provider])))))
      (throw (ex-info "Explicit text mode requires Claude Code" {:type ::invalid-execution-mode})))
    (when (and (contains? program :single-use?)
               (not (and (true? single-use?) (= :text execution-mode) (false? auto-compact?))))
      (throw (ex-info "Single-use CLI Runs require text mode and disabled compaction" {:type ::invalid-single-use})))
    (when (and provider-policy (not (and (= :text execution-mode) (map? provider-policy)
                                         (roster/data-value? provider-policy))))
      (throw (ex-info "Provider policy requires text mode and portable data" {:type ::invalid-provider-policy})))
    (when (= :llm kind)
      (let [policy (:agent/model-policy agent)]
        (when (and policy
                   (not (and (= #{:provider :model} (set (keys policy)))
                             (keyword? (:provider policy))
                             (string? (:model policy))
                             (seq (:model policy)))))
          (throw (ex-info "LLM AgentDef :model-policy must be {:provider keyword :model string}"
                          {:type ::invalid-model-policy
                           :agent-ref agent-ref
                           :model-policy policy})))))
    (when (= :sci kind) (sci-program/validate! program))
    program))

(defn- validate-hire!
  [roster agent-ref {:keys [task from parent-run resources limits role] :as opts}]
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
  (when (and (contains? opts :role) (not (keyword? role)))
    (throw (ex-info "hire! :role must be a keyword"
                    {:type ::invalid-role :role role})))
  (when (and (some? resources)
             (not (and (map? resources)
                       (seq resources)
                       (every? (fn [[coordinate amount]]
                                 (and (or (string? coordinate)
                                          (keyword? coordinate))
                                      (number? amount)
                                      (pos? amount)))
                               resources))))
    (throw (ex-info "hire! :resources must be a non-empty positive resource vector"
                    {:type ::invalid-resources :resources resources})))
  (when-not (or (nil? limits) (map? limits))
    (throw (ex-info "hire! :limits must be a map"
                    {:type ::invalid-limits :limits limits})))
  (when-let [unknown (seq (remove #{:max-model-steps :budget-dollars :required-outputs}
                                  (keys limits)))]
    (throw (ex-info "hire! :limits contains unknown keys"
                    {:type ::unknown-limit-options
                     :unknown (set unknown)
                     :allowed #{:max-model-steps :budget-dollars :required-outputs}})))
  (when-let [paths (:required-outputs limits)]
    (when-not (and (vector? paths) (seq paths)
                   (every? #(and (string? %) (str/starts-with? % "/")) paths))
      (throw (ex-info "hire! limit :required-outputs must be a vector of absolute paths"
                      {:type ::invalid-required-outputs :required-outputs paths}))))
  (when-let [value (:max-model-steps limits)]
    (when-not (and (integer? value) (<= 1 value 256))
      (throw (ex-info "hire! limit :max-model-steps must be an integer from 1 to 256"
                      {:type ::invalid-max-model-steps :max-model-steps value}))))
  (when-let [value (:budget-dollars limits)]
    (when-not (and (number? value)
                   (Double/isFinite (double value))
                   (pos? (double value)))
      (throw (ex-info "hire! limit :budget-dollars must be positive"
                      {:type ::invalid-budget :budget-dollars value}))))
  (when-not (roster/data-value? task)
    (throw (ex-info "Task must be portable data"
                    {:type ::non-portable-task :task task})))
  (let [agent (or (roster/agent roster agent-ref)
                  (throw (ex-info "Unknown AgentRef"
                                  {:type ::unknown-agent :agent-ref agent-ref})))]
    (validate-program! (:agent/program agent) agent-ref agent)
    (when (= :sci (get-in agent [:agent/program :kind]))
      (sci-program/portable-value! task))
    (when (and (get-in agent [:agent/program :single-use?])
               (not= {resource/cli-run-admissions 1M} resources))
      (throw (ex-info "Single-use CLI Runs require exactly one CLI admission, no other provider grants"
                      {:type ::invalid-cli-grant})))
    (when (and (seq limits) (not= :llm (get-in agent [:agent/program :kind])))
      (throw (ex-info "hire! model limits require an LLM AgentDef"
                      {:type ::limits-require-llm
                       :agent-ref agent-ref
                       :limits limits})))
    agent))

(defn- cancelled-error? [t run-id]
  (or (loop [error t]
        (when error
          (or (= ::cancelled (:type (ex-data error)))
              (= spin-core/spin-cancelled (:type (ex-data error)))
              ;; Some Spindel graph boundaries preserve the cancellation
              ;; message while wrapping its ex-data. Follow the cause chain so
              ;; a structured race cannot be misclassified as program failure.
              (= "Spin cancelled" (ex-message error))
              (recur (ex-cause error)))))
      (run/cancel-requested? run-id)))

(defn- graph-cancelled-error? [t]
  (loop [error t]
    (when error
      (or (= spin-core/spin-cancelled (:type (ex-data error)))
          (= "Spin cancelled" (ex-message error))
          (recur (ex-cause error))))))

(defn- publish-result-and-release-spin
  "Retry terminal persistence without losing the computed result. The Run keeps
   its live execution lease and its completion remains unresolved until the
   durable projection succeeds. This makes a transient store outage visible as
   a still-active Run and gives it a real recovery path instead of silently
   leaving a durable `:running` projection behind."
  [id completion result finish-opts]
  (sp/spin
   (loop []
     (let [retained (try
                      (run/retain-finished! id (:run/status result) finish-opts)
                      (catch Throwable t t))]
       (cond
         (instance? Throwable retained)
         (do
           ;; Store calls are normally immediate. Only the backoff is async, so
           ;; an unavailable durable store never parks Spindel's drain thread.
           (sp/await (comb/sleep 25))
           (recur))

         retained
         (run/publish-finished! id completion result)

         ;; Another exactly-once settlement path already retained/released it.
         :else nil)))
   result))

(defn- publish-result-and-release!
  "Blocking counterpart for the process-local settlement watcher.

   This must not construct a Spin: it runs specifically after its owning graph
   was cancelled, and re-entering the same execution context through another
   graph node can inherit/reuse cancellation bookkeeping. The watcher already
   runs off the drain thread, so a short blocking durability backoff is safe.

   The result is published only once its terminal state is durable, so a store
   that refuses the write (a full disk, say) is retried rather than given up on.
   It is logged when it starts failing and every minute it goes on failing, with
   a backoff from 25 ms to 5 s: otherwise it would look exactly like a hang."
  [id completion result finish-opts]
  (loop [attempt 0 delay-ms 25 failing-since nil logged-at nil]
    (let [retained (try
                     (run/retain-finished! id (:run/status result) finish-opts)
                     (catch Throwable t t))]
      (cond
        (instance? Throwable retained)
        (let [now (System/currentTimeMillis)
              since (or failing-since now)
              log? (or (nil? logged-at) (<= 60000 (- now logged-at)))]
          (when log?
            (tel/log! {:level (if (zero? attempt) :warn :error)
                       :id ::terminal-write-failing
                       :data {:run/id id :run/status (:run/status result)
                              :attempts (inc attempt) :failing-ms (- now since)
                              :error (ex-message retained)}}
                      "A finished Run's terminal state could not be written; retrying"))
          (Thread/sleep (long delay-ms))
          (recur (inc attempt) (min 5000 (* 2 delay-ms)) since (if log? now logged-at)))

        retained
        (do (when failing-since
              (tel/log! {:level :info :id ::terminal-write-recovered
                         :data {:run/id id :attempts (inc attempt)}}
                        "A finished Run's terminal state was written after retries"))
            (run/publish-finished! id completion result))

        :else nil)))
  result)

(defn- settlement-result [run-world result]
  (let [{:keys [status reason]}
        (try
          (world/settle! run-world (:run/status result))
          (catch Throwable t
            (tel/log! {:level :error :id ::world-settlement-failed
                       :data {:run/id (:run/id result)
                              :run/world (:id run-world)
                              :error (ex-message t)}}
                      "Run world settlement failed; retaining its canonical settlement state")
            (world/settlement-failure run-world :settlement-failed t)))]
    {:result (assoc result
                    :run/world (:id run-world)
                    :run/settlement-status status
                    :run/settlement-reason reason)
     :finish-opts (cond-> {:settlement-status status}
                    reason (assoc :settlement-reason reason))}))

(defn- return-unused-resources!
  "Return a Run's remaining conserved vector after its owned work quiesces.

   The transfer id is stable, so a retry after an uncertain commit is
   idempotent. A configured resource allocation is never silently abandoned:
   transient store failures retain the live Run and retry behind the same
   physical-quiescence fence as durable terminal persistence."
  [room id parent-run allocation-state]
  (resource/reconcile-return! room id parent-run allocation-state))

(defn- finalize-execution-external!
  "Finalize from a process-local watcher only after the orchestration Spin has a
   cached terminal result and the stable supervisor reports every native worker
   and owned cleanup quiescent. World settlement happens behind the same
   physical-quiescence fence."
  [room control-room run-world id parent-run allocation-state supervisor execution-terminal
   completion outcome]
  (.execute
   native-executor
   ^Runnable
   (bound-fn []
     ;; `bound-fn` conveys dynamic bindings. A cancellation hook normally
     ;; launches this watcher from the losing observer Spin, so retaining its
     ;; *spin-id* would make the supposedly external durability path a child of
     ;; the very graph being reaped. Keep the Room memory context but detach
     ;; graph identity.
     (binding [simple/*in-drain?* false
               ec/*execution-context* (:ctx room)
               ec/*spin-id* nil]
       ;; Wait until the executor has published either its ordinary outcome or
       ;; its graph-level cancellation/failure through the process-local bridge.
       (let [outcome @outcome]
         ;; Keep the durable cancellation token/live lease until the supervising
         ;; Spin explicitly acknowledges terminality. Polling context state is
         ;; racy with graph cleanup and is not a compositional completion edge.
         @execution-terminal
         (await-supervisor! supervisor)
         (resource/reconcile-dispatch-owner! (:dispatch-owner supervisor))
         (return-unused-resources! control-room id (get outcome ::return-to parent-run)
                                   allocation-state)
         (let [{:keys [cleanup-error llm-metrics]} @(:state supervisor)
               result (cond-> (if cleanup-error
                                {:run/id id :run/status :failed
                                 :run/error (ex-message cleanup-error)}
                                (:result outcome))
                        llm-metrics (assoc :run/metrics llm-metrics))
               execution-opts (merge
                               (:finish-opts outcome)
                               (when cleanup-error
                                 {:reason :cleanup-error :error cleanup-error}))
               {:keys [result finish-opts]} (settlement-result run-world result)]
           (publish-result-and-release! id completion result
                                        (merge execution-opts finish-opts))))))))

(defn- execution-spin
  [control-room work-room agent task trigger id chat-id supervisor limits outcome-promise]
  (sp/spin
   (let [outcome
         (try
           (let [{status ::status value ::value reason ::reason diagnostic ::diagnostic}
                 (sp/await
                  (execute-program control-room work-room id chat-id
                                   (cond-> agent
                                     (seq limits) (assoc ::limits limits))
                                   task trigger supervisor))
                 ;; Candidate execution has returned and can no longer touch
                 ;; setup-owned resources. This is distinct from sealing:
                 ;; cancellation may seal admission while this body is live.
                 _ (mark-cleanup-safe! supervisor)
                 ;; Seal admits no further provider work, runs owned cleanup
                 ;; after all workers terminate, and publishes one stable
                 ;; quiescence acknowledgement.
                 _ (sp/await (seal-supervisor! supervisor))
                 cleanup-error (:cleanup-error @(:state supervisor))]
             (when cleanup-error
               (throw (ex-info "Agent program cleanup failed"
                               {:type ::cleanup-failed}
                               cleanup-error)))
             (when (run/cancel-requested? id)
               (cancelled! id))
             (let [llm-metrics (:llm-metrics @(:state supervisor))]
               (case status
                 :completed
                 (let [output (d/reply (:agent/id agent) run-sink
                                       (result-content value) trigger
                                       {:role :assistant :run-id id})]
                 ;; Room posting is durability-first. Completion is acknowledged
                 ;; only after the correlated private output exists.
                   (d/post! control-room output)
                   {:result (cond-> {:run/id id
                                     :run/status :completed
                                     :run/value value
                                     :run/output output}
                              llm-metrics (assoc :run/metrics llm-metrics))
                    :finish-opts {}})

                 :failed
                 {:result (cond-> {:run/id id :run/status :failed
                                   :run/reason reason :run/error diagnostic}
                            llm-metrics (assoc :run/metrics llm-metrics))
                  :finish-opts {:reason reason :error diagnostic}}

                 :waiting
                 {:result (cond-> {:run/id id :run/status :waiting :run/reason reason}
                            llm-metrics (assoc :run/metrics llm-metrics))
                  :finish-opts {:reason reason}}

                 (throw (ex-info "Interpreter returned an unknown program status"
                                 {:type ::unknown-program-status
                                  :status status
                                  :agent/id (:agent/id agent)})))))
           (catch Throwable t
             (let [cancelled? (cancelled-error? t id)
                   graph-cancelled? (graph-cancelled-error? t)
                   ;; A graph cancellation is only cleanup-safe once the
                   ;; terminal spawn callback runs. Other failures are handled
                   ;; by this still-live body after candidate work unwound.
                   _ (when-not graph-cancelled?
                       (mark-cleanup-safe! supervisor))
                   quiesced (seal-supervisor! supervisor)]
               (if graph-cancelled?
                 ;; Cancellation is sticky on a Spin. Awaiting `quiesced` from
                 ;; this catch would immediately throw cancellation again,
                 ;; before the body reaches its local reject callback. Under a
                 ;; nested race that can strand the durable Run at
                 ;; :cancelling even though the native worker already stopped.
                 ;; Reject without another breakpoint; the spawn callback below
                 ;; waits on the stable supervisor from outside the cancelled
                 ;; graph, then settles the Run durability-first.
                 (throw t)
                 (do
                   ;; Ordinary failures still own a live, non-cancelled Spin and
                   ;; can await cleanup compositionally. A direct Run
                   ;; cancellation is in this branch too: its execution graph is
                   ;; still valid, so it returns an ordinary cancelled result.
                   (sp/await quiesced)
                   (let [cleanup-error (:cleanup-error @(:state supervisor))
                         error (or cleanup-error t)
                         llm-metrics (:llm-metrics @(:state supervisor))]
                     (if (and cancelled? (nil? cleanup-error))
                       {:result (cond-> {:run/id id :run/status :cancelled}
                                  llm-metrics (assoc :run/metrics llm-metrics))
                        :finish-opts {:reason :cancel-requested}}
                       {:result (cond-> {:run/id id
                                         :run/status :failed
                                         :run/error (ex-message error)}
                                  llm-metrics (assoc :run/metrics llm-metrics))
                        :finish-opts {:reason (if cleanup-error
                                                :cleanup-error
                                                :program-error)
                                      :error error}})))))))
         result (:result outcome)]
     ;; Settlement must not merge/discard a context from inside the drain graph
     ;; currently using it. Bridge the computed outcome to the process-local
     ;; quiescence watcher; the public result still arrives through the
     ;; context-owned Deferred after durable settlement.
     (deliver outcome-promise outcome)
     result)))

(defn- prepared-execution-spin
  "Run trusted fork-local preparation off the drain before candidate effects.

   The setup worker is owned by the same supervisor as model/native work, so
   targeted cancellation interrupts it and physical quiescence waits for its
   actual exit. Resource allocation and candidate execution remain behind the
   successful setup gate; the private causal trigger is already durable."
  [control-room work-room agent task trigger id chat-id parent-run resources
   supervisor allocation-state limits outcome-promise prepare-world!]
  (sp/spin
   (let [_ (swap! (:state supervisor) assoc :execution-phase :world-setup)
         worker (start-worker!
                 supervisor
                 #(prepare-world!
                   {:room work-room
                    :run/id id
                    ;; Host-only affine registrar. WorldSetup code may acquire
                    ;; several live resources, but cannot transfer this
                    ;; process-local authority to SCI or durable definitions.
                    :register-cleanup!
                    (fn [cleanup] (register-cleanup! supervisor cleanup))}))
         prepared (sp/await (worker-result-spin worker))]
     (cond
       (run/cancel-requested? id)
       (throw (ex-info "Run cancelled during world setup"
                       {:type ::world-setup-cancelled :run/id id}))

       (worker-error? prepared)
       (let [cause (::worker-error prepared)]
         (throw (ex-info (str "Run world setup failed: " (ex-message cause))
                         {:type ::world-setup-failed :run/id id}
                         cause))))
     ;; Durable resource admission can block on Datahike. Keep it off the
     ;; execution-context drain and under the same cancellation/quiescence fence
     ;; as setup and provider work.
     (when (seq resources)
       (swap! (:state supervisor) assoc :execution-phase :resource-allocation)
       (let [allocation-worker
             (start-worker!
              supervisor
              (fn []
                (reset! allocation-state :attempting)
                (try
                  (let [allocation
                        (resource/allocate-run! control-room id parent-run resources)]
                    (reset! allocation-state :allocated)
                    allocation)
                  (catch Throwable t
                    (reset! allocation-state :uncertain)
                    (throw t)))))
             allocation (sp/await (worker-result-spin allocation-worker))]
         (when (or (= ::worker-cancelled allocation)
                   (run/cancel-requested? id)
                   (deadline-expired? supervisor))
           (throw (ex-info "Run cancelled during resource allocation"
                           {:type ::world-setup-cancelled :run/id id})))
         (when (worker-error? allocation)
           (throw (ex-info "Run resource allocation failed"
                           {:type ::resource-allocation-failed :run/id id}
                           (::worker-error allocation))))))
     ;; Setup/allocation expiry is a host failure before candidate entry,
     ;; even in an environment that scores candidate timeouts as verdicts.
     (when (or (run/cancel-requested? id) (deadline-expired? supervisor))
       (throw (ex-info "Run cancelled after world setup"
                       {:type ::world-setup-cancelled :run/id id})))
     ;; Allocation may have blocked beyond the deadline after setup passed.
     ;; Do not enter candidate execution while waiting for a timer event.
     (swap! (:state supervisor) assoc :execution-phase :candidate)
     (sp/await
      (execution-spin control-room work-room agent task trigger id chat-id
                      supervisor limits outcome-promise)))))

(defn- execution-failure-reason [error]
  (loop [error error]
    (if error
      (case (:type (ex-data error))
        ::world-setup-failed :world-setup-failed
        ::world-setup-cancelled :world-setup-cancelled
        ::resource-allocation-failed :resource-allocation-failed
        ::trigger-emission-failed :trigger-emission-failed
        (recur (ex-cause error)))
      :execution-error)))

(defn- settle-unadmitted!
  "Settle the world of a Run that failed before it executed, as failed; a
   settlement error is logged, not raised over the admission error."
  [run-world]
  (try (world/settle! run-world :failed)
       (catch Throwable t
         (tel/log! {:level :warn :id ::unadmitted-world-not-settled
                    :data {:world (:id run-world) :error (ex-message t)}}
                   "The world of a Run that was not admitted could not be settled")
         nil)))

(defn ^:no-doc hire-prepared-in!
  "Host-only Run admission with a trusted fork-local world preparer.

   `control-room` owns durable Run/message facts. `world-parent` is the
   immediate Spindel/Yggdrasil world that the child forks and later settles
   into. They are identical for a top-level hire and intentionally differ for
   recursive hires inside an already-isolated Run world.

   The preparer runs against the isolated work Room after durable admission and
   before resource allocation or candidate-visible work. The private trigger
   is already durable at that point so a setup failure remains causally valid.
   The optional HOST :checkpoints map selects only this admission's initial
   repository state. It is not a delegation capability and is not installed in
   the supervisor; the caller owns retention and any manual Run origin record.
   This entry point is deliberately absent from the SCI surface."
  [control-room world-parent roster agent-ref
   {:keys [task from parent-run settlement resources limits role]
    :or {from :repl settlement :automatic}
    :as raw-opts}
   prepare-world! & [protocol {resume-from :resume preset-id :id
                               on-world :on-world resources-from :resources-from
                               deadline-nanos :deadline-nanos :as host-options}]]
  (when-not (or (nil? deadline-nanos) (integer? deadline-nanos))
    (throw (ex-info "Run deadline must be an absolute monotonic timestamp"
                    {:type ::invalid-deadline})))
  (when-not (or (nil? prepare-world!) (fn? prepare-world!))
    (throw (ex-info "Run world preparer must be a function"
                    {:type ::invalid-world-preparer})))
  (when-not (or (nil? protocol) (fn? (:run protocol)))
    (throw (ex-info "Run protocol must be a host capability with a :run function"
                    {:type ::invalid-protocol})))
  (let [opts      (assoc raw-opts :from from)
        agent     (delegation/check-agent! (validate-hire! roster agent-ref opts) host-options)
        actor     (:agent/id agent)
        id        (or preset-id (random-uuid))
        chat-id   (run-chat-id id)
        ;; A resumed Run takes over its predecessor's wallet: its grant comes
        ;; from there, and goes back there if the Run is not admitted. What it
        ;; leaves once it ran returns to its parent, as for any Run.
        grantor   (or resources-from parent-run)
        run-world (world/open! world-parent id settlement control-room
                               (not-empty (into {} (remove (comp nil? val))
                                                (select-keys host-options [:snapshots :checkpoints]))))
        work-room (:work run-world)
        supervisor (cond-> (assoc (make-supervisor (:ctx world-parent) (:ctx work-room)) :run-id id)
                     deadline-nanos (assoc :deadline-nanos deadline-nanos)
                     (contains? host-options :experiment-capabilities)
                     (assoc :experiment-capabilities (:experiment-capabilities host-options))
                     (contains? host-options :delegation-policy)
                     (assoc :delegation-policy (:delegation-policy host-options)))
        session-close (atom nil)
        close-owned-session! #(when-let [close! @session-close] (close!))
        _ (when (:store control-room)
            (try
              (when-let [session (install-turn-savepoints! control-room work-room)]
                (let [close-session! #(close-session-reconciled! session)]
                  (reset! session-close close-session!)
                  (when on-world (on-world work-room session))
                  (register-cleanup! supervisor close-session!)))
              (catch Throwable t
                ;; Creation-time masking guarantees this cannot be the parent
                ;; Session, even when installation failed partway through.
                (when-let [session (savepoint/session (:ctx work-room))]
                  (close-session-reconciled! session))
                (try ((if (= :deferred settlement) d/discard-deferred d/discard) work-room)
                     (catch Throwable cleanup-error
                       (.addSuppressed t cleanup-error)))
                (throw t))))
        allocation-state
        (atom (if (seq resources) :not-started :not-requested))
        ;; Private Run facts are still Room messages, but never addressed to an
        ;; installed Participant: direct interpretation and participant routing
        ;; must not execute the same task twice.
        trigger   (d/message from run-sink (result-content task) nil {:role :user})
        provenance (cond-> {:run/agent-version (:agent/version agent)
                            :run/program-kind (get-in agent [:agent/program :kind])
                            :run/interpreter-version interpreter-version
                            :run/agent-def-hash (hasch/uuid agent)
                            :run/world (:id run-world)
                            :run/isolation :ctx
                            :run/settlement-policy settlement
                            :run/settlement-status :open}
                     (= :llm (get-in agent [:agent/program :kind]))
                     (assoc :run/chat-id chat-id)
                     (:roster/id roster) (assoc :run/roster (:roster/id roster))
                     ;; the part the Run plays for its parent, which its spend
                     ;; is summed under (`dvergr.agent.budget/spend-by-role`)
                     role (assoc :run/role role))]
    ;; Reserve durable Run ownership before any trigger effect. Teardown either
    ;; fences us out here or includes this Run in its fixed drain set; it can
    ;; never miss an orphan trigger between posting and admission.
    (try
      (run/start! control-room actor trigger nil
                  (cond-> {:id id :kind :agent-task :provenance provenance}
                    parent-run (assoc :parent parent-run)
                    (:from resume-from) (assoc :caused-by #{(:from resume-from)})))
      (catch Throwable t
        ;; Drop the world the Run never owned, by its own policy (a deferred
        ;; world refuses a plain discard), and keep the admission error.
        (close-owned-session!)
        (try ((if (= :deferred settlement) d/discard-deferred d/discard) work-room)
             (catch Throwable _ nil))
        (throw t)))
    (run/register-child-owner! id #(with-owned-child! supervisor %))
    (run/register-settlement-observer!
     control-room id
     #(assoc (resource/dispatch-accounting-state (:dispatch-owner supervisor))
             :workers-quiesced? (:quiesced? @(:state supervisor))
             :supervisor-admission-sealed? (:sealed? @(:state supervisor))))
    (run/register-cancel-hook! id ::native-worker
                               #(cancel-supervisor! supervisor))
    ;; The trigger is the Run's durable causal input, not candidate execution.
    ;; Persist it for every admitted Run—including setup failures—before any
    ;; asynchronous phase starts. The private run sink prevents participant
    ;; routing, while the exact message remains available to thread/audit
    ;; projections.
    (try
      (d/post! control-room trigger)
      (catch Throwable t
        (close-owned-session!)
        (let [{:keys [result finish-opts]}
              (settlement-result
               run-world
               {:run/id id :run/status :failed
                :run/error (ex-message t)})]
          (run/finish! id (:run/status result)
                       (merge {:reason :trigger-emission-failed :error t}
                              finish-opts)))
        (throw t)))
    ;; The ordinary path retains its fail-fast admission semantics. Trusted
    ;; setup runs through `prepared-execution-spin` instead so setup, allocation,
    ;; and candidate execution are all behind one timed/cancellable gate.
    (when-not prepare-world!
      (when (seq resources) (reset! allocation-state :attempting))
      (try
        (resource/allocate-run! control-room id grantor resources)
        (when (seq resources) (reset! allocation-state :allocated))
        (catch Throwable t
          ;; The durable transfer may have committed even when its caller saw an
          ;; exception. Reconcile its stable receipt and return any authority
          ;; before publishing the failed Run; hire! retains its synchronous
          ;; admission-failure contract without leaking a committed grant.
          (when (seq resources) (reset! allocation-state :uncertain))
          (close-owned-session!)
          (return-unused-resources! control-room id grantor allocation-state)
          (let [{:keys [status reason]} (world/settle! run-world :failed)]
            (run/finish! id :failed {:reason :resource-allocation-failed
                                     :error t
                                     :settlement-status status
                                     :settlement-reason reason}))
          (throw t))))
    ;; A resumed Run claims its predecessor last, once it holds the
    ;; predecessor's wallet: until here a failure leaves that Run as it was.
    (when-let [predecessor (:from resume-from)]
      (try
        (run/claim-resume! control-room predecessor id)
        (catch Throwable t
          (close-owned-session!)
          (return-unused-resources! control-room id grantor allocation-state)
          ;; the claim write may have committed although it reported an error
          (run/withdraw-resume-claim! control-room predecessor id)
          (let [{:keys [status reason]} (settle-unadmitted! run-world)]
            (run/finish-durably! id :failed (cond-> {:reason :resume-claim-failed :error t}
                                              status (assoc :settlement-status status
                                                            :settlement-reason reason))))
          (throw t))))
    (let [finalizing? (atom false)]
      (try
        (let [completion (sync/deferred)
              outcome-promise (promise)
              worker-execution
            ;; The protocol rides on the agent value like `::limits`; it
            ;; is attached after the provenance hash above was taken.
              (let [agent (cond-> agent
                            protocol (assoc ::protocol protocol)
                            resume-from (assoc ::resume resume-from))]
                (if prepare-world!
                  (prepared-execution-spin control-room work-room agent task trigger
                                           id chat-id parent-run resources supervisor
                                           allocation-state limits outcome-promise
                                           prepare-world!)
                  (execution-spin control-room work-room agent task trigger id chat-id
                                  supervisor limits outcome-promise)))
              execution-terminal (promise)
              execution (sp/spin (sp/await completion))
              owner-fork-id (:fork-id (ec/current-execution-context))
              handle    (RunHandle. id (:id control-room) owner-fork-id execution completion
                                    worker-execution)
              cancel! (fn []
                        (cancel-supervisor! supervisor)
                      ;; Cancellation closes admission to further native work.
                      ;; Cleanup may still be registered by an already-running
                      ;; initialization worker while the phase is :pending; the
                      ;; supervisor starts it after that worker terminates.
                      ;; The already-running process-local watcher settles only
                      ;; after the execution and supervisor quiescence fences.
                        (seal-supervisor! supervisor))]
        ;; Replace the early sticky worker hook with the complete cancellation
        ;; boundary. register-cancel-hook! immediately invokes it if cancellation
        ;; won between durable admission and construction of this execution.
          (run/register-cancel-hook! id ::native-worker cancel!)
        ;; The watcher owns the Run's end from here on: a later failure is the
        ;; Run's own, reported through it (its handle resolves failed), never
        ;; thrown at the caller of an admission that has committed.
          (finalize-execution-external! world-parent control-room run-world id parent-run
                                        allocation-state supervisor execution-terminal
                                        completion outcome-promise)
          (reset! finalizing? true)
        ;; Use Spindel's fire-and-forget GC pin together with exact terminal
        ;; callbacks instead of polling internal context state.
          (try
            (sync/spawn!
             worker-execution
             {:on-success
              (fn [_]
                (deliver execution-terminal true))
              :on-error
              (fn [t]
            ;; This callback is invoked only after the Spin rejects, including
            ;; cancellation before its body begins.
                (deliver execution-terminal true)
            ;; Graph-level cancellation is settled outside the cancelled Spin:
            ;; this callback may safely block on stable supervisor quiescence,
            ;; while the cancelled body may not cross another await breakpoint.
            ;; finish! is idempotent if another terminal path already won.
                (let [cancelled? (cancelled-error? t id)
                      setup-cancelled?
                      (and cancelled?
                           prepare-world!
                           (not= :candidate
                                 (:execution-phase @(:state supervisor))))
                      failure-reason (if setup-cancelled?
                                       :world-setup-cancelled
                                       (execution-failure-reason t))
                      result (if cancelled?
                               {:run/id id :run/status :cancelled}
                               {:run/id id :run/status :failed
                                :run/error (ex-message t)})]
                  (when (= :cancelled (:run/status result))
                    (run/cancel-room-run! (:id control-room) id))
                  (cancel-supervisor! supervisor)
              ;; The spawned orchestration graph is terminal here. In
              ;; particular, a cancellation during the gap after setup cannot
              ;; release its resources before this callback acknowledges that
              ;; the enclosing body has stopped using them.
                  (mark-cleanup-safe! supervisor)
                  (seal-supervisor! supervisor)
                  (deliver outcome-promise
                           {:result result
                            :finish-opts
                            (if (= :cancelled (:run/status result))
                              {:reason (if (= :world-setup-cancelled failure-reason)
                                         failure-reason
                                         :structured-cancellation)}
                              {:reason failure-reason
                               :error t})})))})
            (catch Throwable t
              ;; The execution was not started: the watcher ends the Run as
              ;; failed behind the same quiescence fence, and returns what it
              ;; was granted to where it came from (a resumed predecessor's
              ;; wallet), since nothing executed.
              (deliver execution-terminal true)
              (cancel-supervisor! supervisor)
              (mark-cleanup-safe! supervisor)
              (seal-supervisor! supervisor)
              (deliver outcome-promise
                       {:result {:run/id id :run/status :failed :run/error (ex-message t)}
                        :finish-opts {:reason :spawn-failed :error t}
                        ::return-to grantor})))
          handle)
        (catch Throwable t
          ;; Not admitted to the watcher: nothing executed, and nothing waits.
          (when @finalizing?
            (throw t))
          (close-owned-session!)
          (return-unused-resources! control-room id grantor allocation-state)
          (when-let [predecessor (:from resume-from)]
            ;; First, and whatever fails after: the predecessor is as it was
            ;; before the claim.
            (run/withdraw-resume-claim! control-room predecessor id))
          (let [{:keys [status reason]} (settle-unadmitted! run-world)]
            (run/finish-durably! id :failed (cond-> {:reason :spawn-failed :error t}
                                              status (assoc :settlement-status status
                                                            :settlement-reason reason))))
          (throw t))))))

(defn- chat-spent
  "Microdollars the chat `chat-id` recorded as used, in `room`'s store."
  [room chat-id]
  (or (when-let [conn (some-> room :store :conn)]
        (dh/q '[:find ?u . :in $ ?c :where [?e :chat/id ?c] [?e :chat/budget-used ?u]] @conn chat-id))
      0))

(defn resume!
  "Continue Run `run-id` of `control-room` from its latest turn savepoint
   (doc/runs.md, Resuming a Run): a new Run, caused by it, whose world is a fork of
   `world-parent` at the systems' recorded snapshots, whose conversation
   starts as the old Run's did at the savepoint and whose steps count on from
   it. The old Run must be terminal (a Run whose process stopped is failed on
   start) and is resumed once; what was left in its wallet moves to the new
   Run. Returns the new Run's handle.

   Atomic for the caller: when it throws, the old Run keeps its wallet and can
   be resumed again (it is unclaimed, or claimed by a successor that failed
   before it ran, which does not count); when it returns, the new Run is
   admitted, holds that wallet and has claimed the old Run.

   The step the old Run was in when it stopped is redone from the savepoint;
   `:once` effects it had already performed (its receipts) are the caller's to
   reconcile."
  [control-room world-parent run-id]
  (let [old (run/run control-room run-id)
        data (some-> old run/savepoint)]
    (when-not old
      (throw (ex-info "No such Run" {:type ::run-not-found :run/id run-id})))
    (when-not (contains? room-store/terminal-run-statuses (:run/status old))
      (throw (ex-info "Only a stopped Run is resumed" {:type ::run-not-terminal
                                                       :run/id run-id :run/status (:run/status old)})))
    (when-not data
      (throw (ex-info "The Run has no savepoint to resume from" {:type ::no-savepoint :run/id run-id})))
    (let [{:keys [agent task step]} (:savepoint/payload data)
          _ (when (get-in agent [:agent/program :single-use?])
              (throw (ex-info "Single-use CLI Runs require a new explicitly admitted task"
                              {:type ::resume-denied :run/id run-id})))
          ;; All bytes/codec/identity/semantic checks precede successor claims,
          ;; world creation and conserved resource transfer. Never load live tail.
          messages (run/load-conversation! control-room old)
          new-id (random-uuid)
          ;; excludes a concurrent resume; nothing durable changes until the
          ;; new Run is admitted and holds the old Run's wallet
          _ (run/reserve-resume! control-room run-id)]
      (try
        (let [parent (:run/parent old)
              ;; conserved: the old Run's remainder, granted from its wallet to
              ;; the new Run's when that Run is admitted
              ;; a Run without a wallet moves none; a failed read stops the
              ;; resume before anything changes
              remaining (resource/run-wallet-balance control-room run-id)
              roster (assoc-in (roster/make-roster) [:roster/agents (:agent/id agent)] agent)
              handle
              (try
                (hire-prepared-in!
                 control-room world-parent roster (:agent/id agent)
                 (cond-> {:task task :from :resume
                          :settlement (or (:run/settlement-policy old) :automatic)}
                   parent (assoc :parent-run parent)
                   ;; the successor plays the part its predecessor did
                   (:run/role old) (assoc :role (:run/role old))
                   (seq remaining) (assoc :resources remaining))
                 nil nil
                 {:id new-id :snapshots (not-empty (:world/systems data))
                  :resources-from (when (seq remaining) run-id)
                  ;; spindel's own resume: the new world becomes the
                  ;; savepoint's continuation (seed, sequence, bookkeeping,
                  ;; pinned components as recorded; `continue-llm-run` its
                  ;; computation) before the Run's first step
                  :on-world (fn [_work-room session]
                              (let [done (promise)]
                                ((portable/hydrate-into! session (:root session) data
                                                         (:savepoint/payload data))
                                 (fn [_] (deliver done :ok)) (fn [e] (deliver done e)))
                                (let [r (deref done 10000 (ex-info "hydrate-into! timed out" {}))]
                                  (when (instance? Throwable r) (throw r)))))
                  :resume {:from run-id :chat (:run/chat-id old) :step step :messages messages
                           ;; what the stopped Run's chat spent (microdollars):
                           ;; its budget is not spent again
                           :spent (chat-spent control-room (:run/chat-id old))}})
                (catch Throwable t
                  ;; a continuation hydrated into the dropped world ends with it
                  (resumed! run-id {:run/status :failed :run/error (ex-message t)})
                  (throw t)))]
          ;; the hydrated continuation resolves with the resumed Run's result.
          ;; The resume has committed: a failure here is logged, not thrown.
          (try
            (sync/spawn! (result-spin handle)
                         {:on-success #(resumed! run-id %)
                          :on-error #(resumed! run-id {:run/status :failed :run/error (ex-message %)})})
            (catch Throwable t
              (tel/log! {:level :warn :id ::resume-continuation-unobserved
                         :data {:run/id run-id :resumed-by new-id :error (ex-message t)}}
                        "The resumed Run runs, but its savepoint continuation is not told its end")))
          handle)
        (finally
          (run/release-resume! run-id))))))

(defn hire-in!
  "Start one AgentDef execution with separate control and work parents.

   `control-room` owns durable Run/message facts. `world-parent` is the
   immediate Spindel/Yggdrasil world that the child forks and later settles
   into. They are identical for a top-level hire and intentionally differ for
   recursive hires inside an already-isolated Run world.

   `agent-ref` is a keyword id or versioned ref resolved against immutable
   `roster`. Options:

   - `:task`       portable task value (required)
   - `:from`       triggering actor, default `:repl`
   - `:parent-run` explicit structural parent Run UUID
   - `:settlement` `:automatic` (default), `:review`, `:discard`, or host-owned `:deferred`
   - `:resources`  positive conserved vector split from the parent Run/Room
   - `:limits`     restrictive LLM `:max-model-steps` / `:budget-dollars`, and
                   `:required-outputs` (files a turn must not end without)
   Built-in program kinds are deterministic `:scripted` / `:echo`, pinned
   `:sci` source entrypoints and the bounded `:llm` model/tool loop. (No simulation or replay
   interpreter exists; one would implement the same boundary.)"
  ([control-room world-parent roster agent-ref opts]
   (hire-in! control-room world-parent roster agent-ref opts {}))
  ([control-room world-parent roster agent-ref opts host-options]
   (when-let [unknown (seq (remove (conj (set delegation/host-option-keys) :checkpoints) (keys host-options)))]
     (throw (ex-info "Unknown host hiring capabilities" {:unknown (set unknown)})))
   (hire-prepared-in! control-room world-parent roster agent-ref opts nil nil host-options)))

(defn hire!
  "Start a top-level AgentDef execution in `room` and return an opaque
   RunHandle. Recursive runtimes use `hire-in!` so durable control facts remain
   in the root Room while the child world forks its immediate parent."
  [room roster agent-ref opts]
  (hire-in! room room roster agent-ref opts))

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
