(ns dvergr.process
  "Deliberable long-running work — a spin registered with a chat-ctx that
   parks at checkpoints, awaits a directive, and resumes after manager-
   issued effects on chat-ctx have been applied.

   See doc/process-model.md for the design rationale. In short:

     - `->process` wraps a spin body and registers it.
     - Inside the body, `checkpoint!` snapshots state and parks.
     - `directive!` (manager-side) runs effects then unparks.
     - `snapshot` is a pure read of the current progress.

   The wire shape of a directive is data:

     {:type :continue :effects [{:op :extend-budget :dollars 0.5} …]}
     {:type :abort   :reason \"…\"}

   Effects are applied by `run-effect!`, a multimethod against
   chat-ctx — unknown ops are logged and skipped, never crash."
  (:require [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.signal :as sig]
            [dvergr.chat.context :as chat-ctx]
            [dvergr.chat.accounting :as acct]
            [taoensso.telemere :as tel])
  (:import [java.util.concurrent CancellationException CountDownLatch TimeUnit]
           [java.util.concurrent.atomic AtomicReference]))

;; ============================================================================
;; Registry (ctx-scoped under [:dvergr/processes] on chat-ctx's spindel-ctx)
;; ============================================================================

(def ^:private registry-path [:dvergr/processes])

(defn- with-chat-ctx*
  "Run f with the chat-ctx's spindel-ctx bound. f sees a stable
   *execution-context* for ec/swap-state! and signal derefs."
  [chat-ctx f]
  (binding [ec/*execution-context* (:spindel-ctx chat-ctx)]
    (f)))

(defn- registry
  "Current process registry on a chat-ctx (map of id → record). May be nil."
  [chat-ctx]
  (with-chat-ctx* chat-ctx #(ec/get-state registry-path)))

(defn- swap-registry!
  [chat-ctx f & args]
  (with-chat-ctx* chat-ctx
    (fn []
      (ec/swap-state! registry-path
                      (fn [m] (apply f (or m {}) args))))))

;; ============================================================================
;; Effects
;; ============================================================================

(defmulti run-effect!
  "Apply a single effect to a chat-ctx. Effects mutate chat-ctx state
   (signals / ledger / messages) before the process resumes — so on
   resume the spin sees the new world.

   Returns a map describing what happened (echoed back to the spin as
   part of :effects-applied)."
  (fn [_chat-ctx op] (:op op)))

(defmethod run-effect! :extend-budget
  [chat-ctx {:keys [dollars]}]
  (let [delta (long (* (or dollars 0) acct/MICRODOLLARS-PER-DOLLAR))]
    (with-chat-ctx* chat-ctx
      #(swap! (:budget-signal chat-ctx) update :total + delta))
    {:op :extend-budget :delta-microdollars delta}))

(defmethod run-effect! :inject-message
  [chat-ctx {:keys [role content]}]
  (chat-ctx/add-message! chat-ctx {:role (or role :system) :content content})
  {:op :inject-message :role role :length (count content)})

(defmethod run-effect! :default
  [_chat-ctx op]
  (tel/log! {:level :warn :id :process/unknown-effect :data {:op op}}
            "Unknown effect op — skipping")
  {:op (:op op) :status :skipped})

(defn- run-effects!
  "Run a sequence of effects, returning the audit list."
  [chat-ctx effects]
  (mapv #(run-effect! chat-ctx %) (or effects [])))

;; ============================================================================
;; Sugar — single-effect shorthands desugar to :continue + effect bundle
;; ============================================================================

(def ^:private sugar-types
  "Directive :types that are sugar for {:type :continue :effects [{...}]}.
   The mapping rewrites the directive in-place before delivery."
  {:extend-budget  (fn [d] [{:op :extend-budget :dollars (:dollars d)}])
   :refocus        (fn [d] [{:op :inject-message
                             :role :system
                             :content (str "[Refocus directive] " (:hint d))}])
   :swap-model     (fn [d] [{:op :swap-model :model (:model d)}])
   :set-temperature (fn [d] [{:op :set-temperature :temp (:temp d)}])})

(defn- canonicalize
  "Rewrite sugar directive shapes into {:type :continue :effects [...]}.
   Pass :continue and :abort through verbatim."
  [directive]
  (cond
    (= :abort    (:type directive)) directive
    (= :continue (:type directive)) directive
    (contains? sugar-types (:type directive))
    {:type :continue
     :effects ((get sugar-types (:type directive)) directive)}
    :else
    (do (tel/log! {:level :warn :id :process/unknown-directive
                   :data {:type (:type directive)}}
                  "Unknown directive type — treating as :continue with no effects")
        {:type :continue :effects []})))

;; ============================================================================
;; Process record
;; ============================================================================

(defrecord Proc
    ;; "Proc" not "Process" to avoid the auto-imported java.lang.Process
    ;; class colliding with a defrecord of the same name. Public API
    ;; (->process, snapshot, etc.) keeps the human-facing name.
    [id
     chat-ctx
     description
     grace-ms
     status-signal       ;; signal of :running | :awaiting-decision | :completed | :aborted
     progress-signal     ;; signal of latest snapshot map
     directive-ref       ;; AtomicReference + latch — first-wins delivery
     directive-latch     ;; CountDownLatch — checkpoint! awaits, directive! signals
     started-at
     tracked-only?])     ;; true → no body thread, work-owner polls aborted?

(defn snapshot
  "Return the latest progress snapshot for a process — a pure read.
   Does not disturb the parked spin."
  [process]
  (binding [ec/*execution-context* (:spindel-ctx (:chat-ctx process))]
    {:id          (:id process)
     :description (:description process)
     :status      @(:status-signal process)
     :progress    @(:progress-signal process)
     :started-at  (:started-at process)
     :elapsed-ms  (- (System/currentTimeMillis) (:started-at process))}))

(defn list-processes
  "All current processes on a chat-ctx, as snapshot maps. Recent
   completions stay visible until the GC trim."
  [chat-ctx]
  (mapv snapshot (vals (registry chat-ctx))))

(defn get-process
  "Look up a Process by id."
  [chat-ctx id]
  (get (registry chat-ctx) id))

;; ============================================================================
;; Checkpoint (process-side)
;; ============================================================================

(def ^:dynamic *current-process*
  "Bound by ->process around the spin body so checkpoint! can find its
   own Process without explicit threading."
  nil)

(defn checkpoint!
  "Yield to the deliberator. Snapshots state into progress-signal,
   flips status to :awaiting-decision, awaits the directive deferred.
   Returns the resolved directive map (with :effects-applied) on
   :continue, or throws CancellationException on :abort.

   Soft-continue: if no directive arrives within the process's
   :grace-ms (default 5000), returns {:type :continue :effects-applied []}.

   For v1 checkpoint! is a blocking call on the future thread that runs
   the process body. Spin-and-CPS integration is a later move (it'd let
   the body park without holding a JVM thread); see #164 follow-ups."
  [{:keys [state description]}]
  (if-let [process *current-process*]
    (let [chat-ctx  (:chat-ctx process)
          grace-ms  (:grace-ms process 5000)
          chat-spc  (:spindel-ctx chat-ctx)]
      (binding [ec/*execution-context* chat-spc]
        (reset! (:progress-signal process)
                (cond-> (or state {})
                  description (assoc :description description)))
        (reset! (:status-signal process) :awaiting-decision))
      (let [^CountDownLatch latch (:directive-latch process)
            ^AtomicReference dref  (:directive-ref process)
            ;; Block on the latch up to grace-ms. Soft-continue if it
            ;; expires before any directive lands.
            _         (.await latch grace-ms TimeUnit/MILLISECONDS)
            directive (or (.get dref)
                          {:type :continue :effects-applied []})
            applied   (or (:effects-applied directive) [])]
        (binding [ec/*execution-context* chat-spc]
          (reset! (:status-signal process) :running))
        (case (:type directive)
          :abort
          (let [reason (or (:reason directive) "aborted by manager")]
            (binding [ec/*execution-context* chat-spc]
              (reset! (:status-signal process) :aborted))
            (throw (CancellationException. reason)))

          :continue
          (assoc directive :effects-applied applied))))
    ;; Outside any process — no-op so library code can sprinkle
    ;; checkpoint! freely without checking for registration.
    {:type :continue :effects-applied []}))

;; ============================================================================
;; Directive (manager-side)
;; ============================================================================

(defn directive!
  "Manager-side. Run effects against chat-ctx, then deliver the
   directive to the parked process. First-wins via compareAndSet —
   returns :delivered on success, :already-decided if a prior directive
   already won the race, :no-such-process if id unknown."
  [chat-ctx process-id directive]
  (if-let [process (get-process chat-ctx process-id)]
    (let [^AtomicReference dref  (:directive-ref process)
          ^CountDownLatch latch  (:directive-latch process)
          canonical (canonicalize directive)
          ;; Sentinel value claims delivery rights atomically before
          ;; running any effects — losing managers' effects must NOT
          ;; fire. The winner replaces the sentinel with the final
          ;; directive once effects are applied. checkpoint! reads
          ;; only after the latch counts down, so it never sees the
          ;; sentinel.
          claimed?  (.compareAndSet dref nil ::claiming)]
      (if claimed?
        (let [applied (when (= :continue (:type canonical))
                        (run-effects! chat-ctx (:effects canonical)))
              final   (assoc canonical :effects-applied (or applied []))]
          (.set dref final)
          (.countDown latch)
          ;; Tracked-only processes have no checkpoint! body that
          ;; sees the latch — flip status directly so work-owners
          ;; polling (aborted? proc) see the abort.
          (when (and (:tracked-only? process) (= :abort (:type canonical)))
            (binding [ec/*execution-context* (:spindel-ctx chat-ctx)]
              (reset! (:status-signal process) :aborted)))
          :delivered)
        :already-decided))
    :no-such-process))

;; ============================================================================
;; ->process — wrap a spin body, register, fire
;; ============================================================================

(defn register!
  "Register a tracking-only Process — no body thread, no checkpoint
   loop. Used when the actual work lives elsewhere (e.g. an inline
   spindel spin loop that can't easily move into a JVM future).

   The Process still exposes :status-signal, :progress-signal, and
   the directive surface; the work owner is responsible for:
     - polling (= :aborted @(:status-signal proc)) at safe points,
     - calling (complete! proc) when done normally,
     - calling (progress! proc state) to keep the manager informed.

   directive! :abort on a tracked process flips :status-signal to
   :aborted (idempotent first-wins) but doesn't throw anywhere — the
   work-owner has to react."
  [chat-ctx {:keys [id description grace-ms]
             :or {grace-ms 5000}}]
  {:pre [(string? description)]}
  (let [pid (or id (random-uuid))
        process
        (binding [ec/*execution-context* (:spindel-ctx chat-ctx)]
          (let [s (sig/signal :running)
                p (sig/signal {})]
            (map->Proc
              {:id              pid
               :chat-ctx        chat-ctx
               :description     description
               :grace-ms        grace-ms
               :status-signal   s
               :progress-signal p
               :directive-ref   (AtomicReference. nil)
               :directive-latch (CountDownLatch. 1)
               :started-at      (System/currentTimeMillis)
               :tracked-only?   true})))]
    (swap-registry! chat-ctx assoc pid process)
    process))

(defn progress!
  "Update the progress signal on a tracked process."
  [process state]
  (binding [ec/*execution-context* (:spindel-ctx (:chat-ctx process))]
    (reset! (:progress-signal process) state))
  state)

(defn complete!
  "Mark a tracked process as completed and remove from the registry's
   active view. Called by the work-owner when the underlying work
   finishes normally."
  [process]
  (let [cctx (:chat-ctx process)]
    (binding [ec/*execution-context* (:spindel-ctx cctx)]
      (reset! (:status-signal process) :completed))
    ;; Stays in registry briefly so a manager can still snapshot. We
    ;; don't trim here — list-processes filters terminal states later.
    :completed))

(defn aborted?
  "True if a manager has aborted the process. Work-owners poll this
   at safe points."
  [process]
  (= :aborted
     (binding [ec/*execution-context* (:spindel-ctx (:chat-ctx process))]
       @(:status-signal process))))

(defn ->process
  "Wrap a spin body as a Process registered on chat-ctx. Starts the
   spin firing immediately. Returns the Process.

   Options:
     :id           - explicit UUID (defaults to random)
     :description  - human-readable, what this process is doing (required)
     :grace-ms     - soft-continue timeout in ms (default 5000)
     :on-complete  - (fn [result]) when spin returns normally
     :on-abort     - (fn [reason]) on CancellationException

   `body-fn` is a 0-arity fn — runs on a fresh future. `*current-process*`
   is bound while it executes so `checkpoint!` calls inside resolve to
   this process automatically."
  [chat-ctx {:keys [id description grace-ms on-complete on-abort]
             :or {grace-ms 5000}}
   body-fn]
  {:pre [(string? description)]}
  (let [pid (or id (random-uuid))
        process
        (binding [ec/*execution-context* (:spindel-ctx chat-ctx)]
          (let [s (sig/signal :running)
                p (sig/signal {})]
            (map->Proc
              {:id              pid
               :chat-ctx        chat-ctx
               :description     description
               :grace-ms        grace-ms
               :status-signal   s
               :progress-signal p
               :directive-ref   (AtomicReference. nil)
               :directive-latch (CountDownLatch. 1)
               :started-at      (System/currentTimeMillis)})))]
    (swap-registry! chat-ctx assoc pid process)
    (future
      (binding [*current-process*    process
                ec/*execution-context* (:spindel-ctx chat-ctx)]
        (try
          (let [result (body-fn)]
            (reset! (:status-signal process) :completed)
            (when on-complete (on-complete result))
            result)
          (catch CancellationException e
            (reset! (:status-signal process) :aborted)
            (when on-abort (on-abort (.getMessage e)))
            nil)
          (catch Throwable e
            (reset! (:status-signal process) :aborted)
            (tel/log! {:level :error :id :process/error
                       :data {:process-id pid
                              :description description}
                       :error e}
                      "Process threw")
            (when on-abort (on-abort (.getMessage e)))
            nil))))
    process))
