(ns dvergr.sandbox
  "SCI sandbox for isolated evaluation contexts.

   Each session gets its own SCI context via fork. Future enhancements:
   - Agents can fork their own contexts for sub-agents
   - Virtual filesystem in memory/datahike
   - Integration with yggdrasil for CoW branching
   - Integration with spindel for async execution (CPS works through SCI)"
  (:require [sci.core :as sci]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [datahike.api :as dh]
            [org.replikativ.spindel.engine.core :as rtc]
            [org.replikativ.spindel.core :as sync]
            [dvergr.sci.clojure-test :as sci-test]
            [hiccup.compiler :as hc]
            [hiccup.util :as hu]
            [hiccup2.core :as hiccup])
  (:import [java.io StringWriter]
           [java.lang.management ManagementFactory]))

;; Lazy-load spindel SCI macro support to avoid compile-time dep
(defn- spindel-sci-macro-ns []
  (require 'org.replikativ.spindel.sci.macro)
  (find-ns 'org.replikativ.spindel.sci.macro))

;; ---------------------------------------------------------------------------
;; Base Context Creation
;; ---------------------------------------------------------------------------

(def ^:private base-classes
  "Java classes exposed in all SCI contexts (base and spindel-backed)."
  {'Math Math
   'String String
   'Integer Integer
   'Long Long
   'Double Double
   'Boolean Boolean
   'java.lang.Throwable Throwable
   'java.lang.Exception Exception
   'java.lang.AssertionError AssertionError
   'java.lang.Error Error
   'clojure.lang.ExceptionInfo clojure.lang.ExceptionInfo
   'java.time.Instant java.time.Instant
   'java.time.LocalDateTime java.time.LocalDateTime
   'java.time.ZonedDateTime java.time.ZonedDateTime
   'java.util.UUID java.util.UUID
   'java.util.Date java.util.Date})

;; ---------------------------------------------------------------------------
;; Resource Limits
;; ---------------------------------------------------------------------------

(defn make-resource-limits
  "Return an :interrupt-fn callback for sci/init.

   SCI calls this at the start of every user-defined fn body. The
   callback's job is twofold:

     1. Pick up Thread.isInterrupted() so the host's outer Esc / watchdog
        signal unwinds the eval cooperatively at the next fn boundary.
     2. Enforce a single coarse bound — thread-allocated memory — so a
        runaway allocation can't OOM the daemon. Sampled every 10k calls
        to keep overhead negligible.

   Token / dollar / time budgets are tracked by dvergr.chat.accounting +
   chat-ctx ledger at much finer granularity; per-call wall-time and
   op-count caps in here used to fight with those, hitting legitimate
   long iterations (large folds, multi-step research) as easily as
   actual infinite loops. They're gone now. The 60s eval-code outer
   fence + Esc cancellation cover the same threat with no false
   positives.

   Options:
     :max-bytes - max bytes allocated by the current thread (default: 256 MiB)"
  [& {:keys [max-bytes]
      :or   {max-bytes (* 256 1024 1024)}}]
  (let [ops    (java.util.concurrent.atomic.AtomicLong. 0)
        tmx    (ManagementFactory/getThreadMXBean)
        tid    (.getId (Thread/currentThread))
        mem0   (when (.isThreadAllocatedMemoryEnabled tmx)
                 (.getThreadAllocatedBytes tmx tid))]
    (fn []
      ;; Cheap interrupt check on every call — picks up make-timeout watchdog signals.
      ;; Thread/interrupted also clears the flag so subsequent code runs cleanly.
      (when (.isInterrupted (Thread/currentThread))
        (Thread/interrupted)
        (throw (ex-info "Evaluation interrupted"
                        {:cause :thread-interrupt})))
      ;; Sample memory every 10k ops to amortise the ThreadMXBean call.
      (let [n (.incrementAndGet ops)]
        (when (and (zero? (mod n 10000))
                   mem0
                   (.isThreadAllocatedMemoryEnabled tmx))
          (let [delta (- (.getThreadAllocatedBytes tmx tid) mem0)]
            (when (> delta max-bytes)
              (throw (ex-info "Resource limit: memory exceeded"
                              {:limit-bytes max-bytes :allocated-bytes delta})))))))))

(defn make-timeout
  "Start a watchdog daemon thread that interrupts the calling thread after timeout-ms.

   Returns {:cancel! fn} — call cancel! when eval completes to stop the watchdog.
   If you don't cancel and the timeout fires, the eval thread receives Thread.interrupt().

   The interrupt signal is picked up by:
     1. make-resource-limits check-fn (next SCI op after the interrupt is set)
     2. Java blocking IO that checks the interrupt flag (sockets, NIO channels)
     3. Thread.sleep / Object.wait inside JVM code

   For non-interruptible blocking syscalls, pair with eval-code's :timeout-ms option
   which uses future/deref as an outer fence.

   Typical usage (managed by eval-code automatically when :timeout-ms is passed):
     (let [{:keys [cancel!]} (make-timeout 5000)]
       (try (eval-code ctx code)
            (finally (cancel!))))"
  [timeout-ms]
  (let [target     (Thread/currentThread)
        cancelled? (atom false)
        ;; Set true when the watchdog actually fired its interrupt (as
        ;; opposed to being cancelled by `cancel!` after a clean
        ;; completion). Callers use this to distinguish a
        ;; watchdog-caused InterruptedException from one with another
        ;; origin, so the timeout can be surfaced clearly rather than
        ;; as a bare InterruptedException.
        fired?     (atom false)
        watchdog (doto (Thread.
                         ^Runnable
                         (fn []
                           (try
                             (Thread/sleep (long timeout-ms))
                             ;; Timeout elapsed — signal the eval thread unless cancelled
                             (when-not @cancelled?
                               (reset! fired? true)
                               (.interrupt target))
                             (catch InterruptedException _
                               ;; Watchdog interrupted by cancel! — exit quietly
                               nil)))
                         "sci-sandbox-watchdog")
                   (.setDaemon true)
                   .start)]
    {:cancel! (fn []
                (reset! cancelled? true)
                (.interrupt watchdog))
     :fired?  (fn [] @fired?)}))

;; ---------------------------------------------------------------------------
;; IO Audit Log + Policies
;; ---------------------------------------------------------------------------

(defn make-audit-log
  "Return a fresh audit log: an atom containing a vector of IO events.

   Each event is {:op keyword :t epoch-ms :data map}.
   Attach to a SCI context via the :audit-log option on add-*-ns! calls.
   The log is returned alongside the SCI ctx so callers can inspect it."
  []
  (atom []))

(defn- audit!
  "Append an IO event to the audit log (no-op when log is nil)."
  [log op data]
  (when log
    (swap! log conj {:op op :t (System/currentTimeMillis) :data data})))

(defn sensitive-path-policy
  "Throw if path matches known-sensitive OS path patterns.
   Call this synchronously before opening a file."
  [path]
  (when (and path
             (re-find #"(?i)(\.ssh[/\\]|\.gnupg[/\\]|/etc/shadow|/etc/passwd|
                             /etc/sudoers|/proc/|/sys/|\.aws[/\\]|\.azure[/\\]|
                             \.gcloud[/\\]|/run/secrets|\.env$|\.env\.)"
                      path))
    (throw (ex-info "Access denied: sensitive path" {:path path}))))

(defn make-domain-policy
  "Return a policy fn that throws when url does not start with any allowed domain.
   An empty or nil allowed-domains set permits all domains (open)."
  [allowed-domains]
  (when (seq allowed-domains)
    (fn [url]
      (when-not (some #(str/starts-with? url %) allowed-domains)
        (throw (ex-info "HTTP request to unauthorized domain"
                        {:url url :allowed allowed-domains}))))))

(defn create-base-ctx
  "Create base SCI context with safe Clojure core and exposed tool functions.

   This context is created once and forked for each session. It provides:
   - Safe subset of clojure.core
   - Standard Clojure namespaces (clojure.string, clojure.set, clojure.walk, clojure.edn)
   - Basic Java classes (Math, String, numeric wrappers, exception types, java.time, UUID)
   - Denies dangerous operations (eval, load-file, load-string)
   - Resource limits on operation count, wall time, and thread memory allocation

   Options:
     :resource-limits - result of (make-resource-limits ...) or nil to disable.
                        Defaults to (make-resource-limits) with standard bounds."
  [& {:keys [resource-limits]
      :or   {resource-limits (make-resource-limits)}}]
  ;; SCI includes most of clojure.core and standard namespaces by default.
  ;; clojure.string, clojure.set, clojure.walk, clojure.edn are built-in.
  ;; Agents must use standard (require ...) forms - no magic aliases.
  (let [ctx
        (sci/init
          {;; Allow access to safe Java classes
           ;; NOTE: System is NOT exposed — it allows System/exit, System/getenv (leaks secrets)
           ;; System/currentTimeMillis is available via (inst-ms (java.util.Date.))
           :classes base-classes

           ;; Deny dangerous operations
           :deny '[eval load-file load-string
                   clojure.core/eval
                   clojure.core/load-file
                   clojure.core/load-string]

           ;; Features (for reader conditionals if needed)
           :features #{:clj}

           ;; interrupt-fn fires at the start of every user-defined fn body evaluation —
           ;; catches infinite loops and deep recursion without OS-level involvement.
           :interrupt-fn resource-limits})]
    ;; clojure.test is always available — agents write tests via clojure_eval
    ;; and run them with (clojure.test/run-tests) inside SCI, fully isolated.
    ;; Use ctx-aware-test-namespace so run-tests can find vars via the real SCI ctx.
    (sci/merge-opts ctx {:namespaces {'clojure.test (sci-test/ctx-aware-test-namespace ctx)}})
    ctx))

(def ^:private base-context
  "Shared base context - DO NOT MUTATE. Fork this for each session."
  (delay (create-base-ctx)))

;; ---------------------------------------------------------------------------
;; Spindel SCI Context (Full FRP)
;; ---------------------------------------------------------------------------

(defn create-spindel-sci-ctx
  "Create an SCI context with full spindel macro support (spin/await/track).

   Uses spindel's create-spin-macro-context which provides CPS-transformed
   spin macro, await effect, and track effect inside SCI. This gives agents
   full reactive programming capabilities in sandboxed code.

   Args:
     spindel-ctx - Spindel execution context (from create-execution-context or fork-context)

   Returns an SCI context that supports:
     (require '[org.replikativ.spindel.spin.cps :refer [spin]])
     (require '[org.replikativ.spindel.effects.await :refer [await]])
     (spin (let [x (await some-spin)] (* x 2)))"
  [spindel-ctx]
  (let [macro-ns (spindel-sci-macro-ns)
        create-fn (ns-resolve macro-ns 'create-spin-macro-context)]
    ;; Hand spindel the same make-resource-limits interrupt-fn the
    ;; non-spindel base context uses. Without it the spindel-backed
    ;; sandbox couldn't notice Thread.interrupt() at SCI fn-entry, so
    ;; user-cancel only fired at the eval-code outer fence (~100ms
    ;; minimum). With it, the interrupt unwinds at the next fn entry.
    (create-fn {:runtime      spindel-ctx
                :interrupt-fn (make-resource-limits)})))

(defn add-spindel-extras-ns!
  "Expose spindel combinators, sync primitives, and signals to SCI.

   Replaces the limited add-spindel-sync-ns! with a fuller API:
   - spindel.comb — parallel, race, timeout, sleep
   - sync         — deferred, deliver!, mailbox, post!
   - spindel.sig  — signal (for external world boundary)

   These are safe: they only coordinate within the SCI context."
  [sci-ctx spindel-ctx]
  (require 'org.replikativ.spindel.spin.combinators)
  (require 'org.replikativ.spindel.signal)
  (let [comb-ns (find-ns 'org.replikativ.spindel.spin.combinators)
        sig-ns  (find-ns 'org.replikativ.spindel.signal)]
    (binding [rtc/*execution-context* spindel-ctx]
      ;; Sync primitives (same as before but unified here)
      (sci/add-namespace! sci-ctx 'sync
                          {'deferred  (fn [] (sync/deferred))
                           'deliver!  (fn [d v] (sync/deliver! d v))
                           'mailbox   (fn [] (sync/mailbox))
                           'post!     (fn [mb v] (mb v))})
      ;; Combinators
      (sci/add-namespace! sci-ctx 'spindel.comb
                          {'parallel @(ns-resolve comb-ns 'parallel)
                           'race     @(ns-resolve comb-ns 'race)
                           'timeout  @(ns-resolve comb-ns 'timeout)
                           'sleep    @(ns-resolve comb-ns 'sleep)})
      ;; Signals — signal is a macro; wrap as a function using the underlying record
      (let [signal-ref-ctor (ns-resolve sig-ns '->SignalRef)
            addr-ns (do (require 'org.replikativ.spindel.engine.addressing)
                        (find-ns 'org.replikativ.spindel.engine.addressing))
            next-addr! @(ns-resolve addr-ns 'next-address!)
            deltaable-ns (do (require 'org.replikativ.spindel.incremental.deltaable)
                             (find-ns 'org.replikativ.spindel.incremental.deltaable))
            clear-deltas @(ns-resolve deltaable-ns 'clear-deltas)]
        (sci/add-namespace! sci-ctx 'spindel.sig
                            {'signal (fn [initial-value]
                                       (let [ctx (rtc/current-execution-context)
                                             id  (next-addr! ctx "signal" {:file "sci" :line 0 :column 0})]
                                         (signal-ref-ctor id (clear-deltas initial-value))))})))))

;; ---------------------------------------------------------------------------
;; Session Context Management
;; ---------------------------------------------------------------------------

(defn fork-for-session
  "Fork base context for a new session. Returns isolated SCI context.

   Each session gets its own isolated context where:
   - (def x 1) persists within the session
   - Changes don't affect other sessions
   - clojure.test run-tests/test-ns enumerate vars in THIS forked ctx

   When spindel-ctx is provided, creates a spindel-backed SCI context
   with full spin/await/track macro support instead of a plain SCI fork."
  ([] (fork-for-session nil))
  ([spindel-ctx]
   (let [forked (if spindel-ctx
                  (create-spindel-sci-ctx spindel-ctx)
                  (sci/fork @base-context))]
     ;; Spindel creates a fresh SCI context without the base classes — add them back.
     (when spindel-ctx
       (sci/merge-opts forked {:classes base-classes}))
     ;; Re-bind high-level test runners to close over the forked ctx, not the base.
     ;; Without this, run-tests would look up vars in the base context and find none.
     (sci/merge-opts forked {:namespaces {'clojure.test (sci-test/ctx-aware-test-namespace forked)}})
     forked)))

;; ---------------------------------------------------------------------------
;; Evaluation
;; ---------------------------------------------------------------------------

(defn eval-code
  "Evaluate Clojure code in the session's SCI context.

   Returns map with:
   - :value     - evaluation result
   - :stdout    - captured stdout
   - :stderr    - captured stderr
   - :success   - boolean, true if evaluation succeeded
   - :error     - error map if failed {:message :type :data :stacktrace}

   Options:
     :timeout-ms - wall-clock timeout in milliseconds. Combines two mechanisms:
                   1. make-timeout watchdog that interrupts the eval thread (handles
                      responsive IO and SCI computation via the interrupt-fn hook).
                   2. future/deref as an outer fence that unblocks the caller even if
                      the eval thread is stuck in a non-interruptible syscall.
                   Returns {:success false :error {:message \"Timed out...\"}} on timeout.

   Example:
     (eval-code ctx \"(+ 1 2)\")
     => {:value 3 :stdout \"\" :stderr \"\" :success true}

     (eval-code ctx \"(Thread/sleep 10000)\" :timeout-ms 1000)
     => {:success false :error {:message \"Timed out after 1000ms\" ...}}"
  [sci-ctx code & {:keys [timeout-ms cancel?]}]
  (if timeout-ms
    ;; Timeout path: watchdog + future/deref outer fence.
    ;;
    ;; The watchdog MUST be started inside the future so it captures the eval
    ;; thread (not the calling thread) as its interrupt target.
    ;;
    ;; Two-layer design:
    ;;   Layer 1 — watchdog interrupts the eval thread after timeout-ms.
    ;;             Caught by: interrupt-fn (SCI fn-body entries),
    ;;             responsive IO (sockets/NIO that honour Thread.interrupt).
    ;;   Layer 2 — outer deref with timeout-ms + 5s safety buffer.
    ;;             Unblocks the caller even if the eval thread is stuck in a
    ;;             non-interruptible syscall (rare; eval thread leaks but caller
    ;;             gets a result).
    ;;
    ;; If a :cancel? 0-arity predicate is supplied, a tiny poller thread
    ;; watches it and fires the same watchdog cancel! early — so user-
    ;; initiated cancellation triggers the exact same path as a real
    ;; timeout (interrupted eval, structured TimeoutException, no leaks).
    (let [stdout      (StringWriter.)
          stderr      (StringWriter.)
          cancel-poll-stop (atom false)
          cancel-fired (atom false)
          eval-future (future
                        ;; Watchdog starts here on the eval thread
                        (let [{:keys [cancel! fired?]} (make-timeout timeout-ms)
                              eval-thread (Thread/currentThread)
                              _ (when cancel?
                                  (let [t (Thread.
                                            #(loop []
                                               (when-not @cancel-poll-stop
                                                 (if (try (cancel?) (catch Throwable _ false))
                                                   ;; Mirror watchdog semantics:
                                                   ;; mark fired and interrupt the
                                                   ;; eval thread so SCI's
                                                   ;; interrupt-fn / responsive IO
                                                   ;; sees it and unwinds the
                                                   ;; computation cleanly.
                                                   (do (reset! cancel-fired true)
                                                       (.interrupt eval-thread))
                                                   (do (Thread/sleep 100) (recur))))))]
                                    (.setDaemon t true)
                                    (.setName t "dvergr-eval-cancel-poll")
                                    (.start t)))]
                          (try
                            (sci/binding [sci/out stdout sci/err stderr]
                              (let [result (sci/eval-string* sci-ctx code)]
                                {:value result :stdout (str stdout) :stderr (str stderr)
                                 :success true}))
                            (catch Throwable e
                              ;; Catch Throwable so JVM Errors (StackOverflowError,
                              ;; OutOfMemoryError) also produce a structured result
                              ;; rather than crashing the future unhandled.
                              ;;
                              ;; If the watchdog fired, the exception is the
                              ;; downstream effect of Thread.interrupt() —
                              ;; surface it as a CLEAN timeout error rather
                              ;; than a confusing bare InterruptedException
                              ;; (or whatever the interrupted op raised),
                              ;; matching the layer-2 outer-fence message.
                              ;; Otherwise the eval-thread inner catch (the
                              ;; watchdog path through @spin / LockSupport)
                              ;; would surface to the agent as e.g. "sleep
                              ;; interrupted / InterruptedException" and the
                              ;; agent has to GUESS it was a timeout.
                              (cond
                                @cancel-fired
                                {:error   {:message "Evaluation cancelled by user"
                                           :type    "CancellationException"
                                           :data    {:cause :user-cancel}}
                                 :stdout  (str stdout)
                                 :stderr  (str stderr)
                                 :success false}

                                (fired?)
                                {:error   {:message (str "Timed out after " timeout-ms "ms"
                                                         " — your code likely got stuck"
                                                         " (a (spin …) that never resolved,"
                                                         " an infinite loop, or oversized"
                                                         " inference). Try fewer particles,"
                                                         " bound your loops, or check for"
                                                         " hung @spin / await.")
                                           :type    "TimeoutException"
                                           :data    {:timeout-ms timeout-ms
                                                     :cause :watchdog-interrupt}}
                                 :stdout  (str stdout)
                                 :stderr  (str stderr)
                                 :success false}

                                :else
                                {:error {:message (.getMessage e)
                                         :type     (str (class e))
                                         :data     (when (instance? clojure.lang.ExceptionInfo e)
                                                     (ex-data e))
                                         :stacktrace (when (instance? Exception e)
                                                       (sci/stacktrace e))}
                                 :stdout (str stdout)
                                 :stderr (str stderr)
                                 :success false}))
                            (finally
                              ;; Always cancel the watchdog when eval finishes
                              (cancel!)
                              (reset! cancel-poll-stop true)))))]
      ;; Outer fence: poll the eval-future short-interval, so we ALSO
      ;; honour user cancel even when the inner SCI interrupt-fn isn't
      ;; reachable (spindel-backed SCI doesn't expose it for every
      ;; fn-entry, and pure tight loops never cross the boundary). On
      ;; cancel we future-cancel and return cleanly; the eval thread
      ;; may leak (same caveat as a real outer-fence timeout) but the
      ;; caller and the user get immediate feedback.
      (let [outer-deadline (+ (System/currentTimeMillis) timeout-ms 5000)
            result
            (loop []
              (let [r (deref eval-future 100 ::pending)]
                (cond
                  (not= r ::pending)              r
                  (and cancel? (cancel?))         (do (reset! cancel-poll-stop true)
                                                      (future-cancel eval-future)
                                                      ::cancelled)
                  (> (System/currentTimeMillis) outer-deadline) ::timed-out
                  :else (recur))))]
        (case result
          ::cancelled
          {:error  {:message "Evaluation cancelled by user"
                    :type    "CancellationException"
                    :data    {:cause :outer-fence-cancel}}
           :stdout  (str stdout)
           :stderr  (str stderr)
           :success false}

          ::timed-out
          {:error   {:message (str "Timed out after " timeout-ms "ms"
                                   " (the eval thread didn't respond to"
                                   " interruption — likely a non-interruptible"
                                   " blocking syscall). Avoid uninterruptible"
                                   " blocking IO and bound long computations.")
                     :type    "TimeoutException"
                     :data    {:timeout-ms timeout-ms
                               :cause :outer-fence-non-interruptible}}
           :stdout  (str stdout)
           :stderr  (str stderr)
           :success false}

          result)))
    ;; No timeout — direct eval on caller's thread
    (let [stdout (StringWriter.)
          stderr (StringWriter.)]
      (try
        (sci/binding [sci/out stdout sci/err stderr]
          (let [result (sci/eval-string* sci-ctx code)]
            {:value result :stdout (str stdout) :stderr (str stderr) :success true}))
        (catch Exception e
          {:error {:message (.getMessage e)
                   :type    (str (class e))
                   :data    (ex-data e)
                   :stacktrace (sci/stacktrace e)}
           :stdout (str stdout)
           :stderr (str stderr)
           :success false})))))

(defn eval-forms
  "Evaluate multiple forms sequentially in the same context.
   Returns vector of results (one per form)."
  [sci-ctx forms]
  (mapv (partial eval-code sci-ctx) forms))

;; ---------------------------------------------------------------------------
;; Datahike Integration
;; ---------------------------------------------------------------------------

(defn add-datahike-query-ns!
  "Add datahike query namespace to SCI context.

   Exposes read-only datahike functions:
   - dh/q       - datalog queries
   - dh/pull    - pull patterns
   - dh/pull-many
   - dh/entity  - entity maps
   - dh/datoms  - raw datom access
   - dh/schema  - schema inspection

   Does NOT expose transact - use tools for modifications.

   The db-atom should be an atom containing the current db value,
   updated by the caller when db changes."
  [sci-ctx db-atom]
  (let [;; Accept canonical calls (with explicit DB as first arg) AND
        ;; the sugared form (auto-uses session db). LLMs default to the
        ;; canonical shape from their training; auto-prepending @db-atom
        ;; in that case dispatches the multimethod on the wrong arg
        ;; (a DB value instead of the index keyword) and fails opaquely.
        db? (fn [x] (instance? datahike.db.DB x))
        q-fn (fn [query & args]
               ;; (q query db & inputs) OR (q query & inputs) — auto-add db
               ;; only when the first input isn't already one.
               (if (and (seq args) (db? (first args)))
                 (apply dh/q query args)
                 (apply dh/q query @db-atom args)))
        pull-fn (fn [a b & [c]]
                  (if (db? a) (dh/pull a b c) (dh/pull @db-atom a b)))
        pull-many-fn (fn [a b & [c]]
                       (if (db? a) (dh/pull-many a b c) (dh/pull-many @db-atom a b)))
        entity-fn (fn [a & [b]]
                    (if (db? a) (dh/entity a b) (dh/entity @db-atom a)))
        datoms-fn (fn [a & rest]
                    (if (db? a)
                      (apply dh/datoms a rest)
                      (apply dh/datoms @db-atom a rest)))
        schema-fn (fn ([] (dh/schema @db-atom))
                      ([db] (dh/schema db)))
        db-fn (fn [] @db-atom)]

    (sci/add-namespace! sci-ctx 'dh
                        {'q q-fn
                         'pull pull-fn
                         'pull-many pull-many-fn
                         'entity entity-fn
                         'datoms datoms-fn
                         'schema schema-fn
                         'db db-fn})))

(defn add-inference-ns!
  "Add probabilistic programming primitives to SCI context.

   Extends the SCI spin macro's breakpoints to recognise choose/sample/observe
   as CPS effects (same mechanism as await).  After calling this, agents can
   write probabilistic models directly inside (spin ...) forms:

     (spin
       (let [p (sample (dist/beta 1 1))]
         (observe (dist/flip p) true)
         p))

   And run inference with:

     (spin
       (let [measure (await (infer/smc-infer (my-model) 100))]
         (infer/query measure identity)))

   Namespaces added:
   - dist/   — Anglican distributions: normal, beta, gamma, uniform, flip, …
   - infer/  — smc-infer, importance-sampling, query, predict
   - org.replikativ.spindel.inference.effects/ — sample, observe, choose (as CPS breakpoints)

   NOTE: must be called AFTER fork-for-session (needs the SCI spin/await context)."
  [sci-ctx]
  ;; Load JVM-side namespaces (auto-registers effects on load)
  (require '[org.replikativ.spindel.inference.effects])
  (require '[org.replikativ.spindel.inference.inference :as infer*])
  (require '[org.replikativ.spindel.inference.measure   :as measure*])
  (require '[org.replikativ.spindel.engine.effects      :as eff*])
  (require '[anglican.runtime :as ar*])

  ;; Inject dispatch-symbol-call as a native function accessible from SCI code.
  ;; Also add it to the org.replikativ.spindel.engine.effects namespace for
  ;; use in the generated CPS code (handler bodies reference it by qualified symbol).
  (sci/add-namespace! sci-ctx 'org.replikativ.spindel.engine.effects
    {'dispatch-symbol-call @(resolve 'org.replikativ.spindel.engine.effects/dispatch-symbol-call)})

  ;; Define sample/observe/choose as proper SCI vars using defn so that SCI's
  ;; (resolve sym) returns a Var whose (meta v) has :name/:ns.
  ;; ioc/var-name relies on (meta (resolve sym)) to get the fully-qualified symbol
  ;; for breakpoint lookup — plain functions added via sci/add-namespace! lack
  ;; this metadata and are invisible to the CPS transformer.
  (sci/eval-string* sci-ctx
    "(ns org.replikativ.spindel.inference.effects)
     (defn choose [& _] (throw (ex-info \"choose called outside spin context\" {})))
     (defn sample [& _] (throw (ex-info \"sample called outside spin context\" {})))
     (defn observe [& _] (throw (ex-info \"observe called outside spin context\" {})))")

  ;; Extend pcps-async/breakpoints inside SCI so the spin macro CPS-transforms
  ;; calls to sample/observe/choose (mirrors how spindel's await was wired in macro.clj).
  ;;
  ;; IMPORTANT: breakpoints values must be SYMBOLS pointing to named SCI vars, not
  ;; function values. invert-impl calls (resolve <value-from-breakpoints>) expecting
  ;; a symbol.  We define named handler fns (choose-bp, sample-bp, observe-bp) in
  ;; the is.simm.partial-cps.async namespace and store their symbols.
  (sci/eval-string* sci-ctx
    "(in-ns 'is.simm.partial-cps.async)

     (defn choose-bp [_ r e]
       (fn [args]
         `(let [resolve# (fn [v#] (is.simm.partial-cps.async/invoke-continuation ~r v#))
                reject#  (fn [err#] (is.simm.partial-cps.async/invoke-continuation ~e err#))
                spin-id# org.replikativ.spindel.engine.core/*spin-id*]
            (org.replikativ.spindel.engine.effects/dispatch-symbol-call
              org.replikativ.spindel.engine.core/*execution-context*
              'org.replikativ.spindel.inference.effects/choose
              [~@args]
              spin-id#
              \"infer\"
              resolve#
              reject#))))

     (defn sample-bp [_ r e]
       (fn [args]
         `(let [resolve# (fn [v#] (is.simm.partial-cps.async/invoke-continuation ~r v#))
                reject#  (fn [err#] (is.simm.partial-cps.async/invoke-continuation ~e err#))
                spin-id# org.replikativ.spindel.engine.core/*spin-id*]
            (org.replikativ.spindel.engine.effects/dispatch-symbol-call
              org.replikativ.spindel.engine.core/*execution-context*
              'org.replikativ.spindel.inference.effects/sample
              [~@args]
              spin-id#
              \"infer\"
              resolve#
              reject#))))

     (defn observe-bp [_ r e]
       (fn [args]
         `(let [resolve# (fn [v#] (is.simm.partial-cps.async/invoke-continuation ~r v#))
                reject#  (fn [err#] (is.simm.partial-cps.async/invoke-continuation ~e err#))
                spin-id# org.replikativ.spindel.engine.core/*spin-id*]
            (org.replikativ.spindel.engine.effects/dispatch-symbol-call
              org.replikativ.spindel.engine.core/*execution-context*
              'org.replikativ.spindel.inference.effects/observe
              [~@args]
              spin-id#
              \"infer\"
              resolve#
              reject#))))

     (def breakpoints
       (assoc breakpoints
         'org.replikativ.spindel.inference.effects/choose  'is.simm.partial-cps.async/choose-bp
         'org.replikativ.spindel.inference.effects/sample  'is.simm.partial-cps.async/sample-bp
         'org.replikativ.spindel.inference.effects/observe 'is.simm.partial-cps.async/observe-bp))")

  ;; Anglican distribution constructors
  (sci/add-namespace! sci-ctx 'dist
    (let [ar (find-ns 'anglican.runtime)]
      {'normal             @(ns-resolve ar 'normal)
       'beta               @(ns-resolve ar 'beta)
       'gamma              @(ns-resolve ar 'gamma)
       'uniform-continuous @(ns-resolve ar 'uniform-continuous)
       'exponential        @(ns-resolve ar 'exponential)
       'flip               @(ns-resolve ar 'flip)
       'bernoulli          @(ns-resolve ar 'bernoulli)
       'poisson            @(ns-resolve ar 'poisson)
       'dirichlet          @(ns-resolve ar 'dirichlet)
       'categorical        @(ns-resolve ar 'categorical)
       'mvn                @(ns-resolve ar 'mvn)
       'chi-squared        @(ns-resolve ar 'chi-squared)
       'student-t          (fn [nu] (@(ns-resolve ar 'student-t) nu))}))

  ;; Inference runners — these take/return spins, compose with await in spin bodies
  (sci/add-namespace! sci-ctx 'infer
    {'smc-infer          @(resolve 'org.replikativ.spindel.inference.inference/smc-infer)
     'importance-sampling @(resolve 'org.replikativ.spindel.inference.inference/importance-sampling)
     'kernel-infer       @(resolve 'org.replikativ.spindel.inference.inference/kernel-infer)
     'query              @(resolve 'org.replikativ.spindel.inference.inference/query)
     'predict            @(resolve 'org.replikativ.spindel.inference.inference/predict)
     'pimh-infer         @(resolve 'org.replikativ.spindel.inference.inference/pimh-infer)
     'pgibbs-infer       @(resolve 'org.replikativ.spindel.inference.inference/pgibbs-infer)}))

(defn add-datahike-write-ns!
  "Add datahike write operations to SCI context.

   Extends the 'dh namespace (created by add-datahike-query-ns!) with:
   - transact!  - transact data or schema into the (forked) datahike conn
   - retract!   - retract entities/attributes

   conn is the raw datahike connection. In a forked spindel context this is
   automatically the fork's isolated connection — writes stay in the fork
   until merge! is called."
  [sci-ctx conn]
  (sci/add-namespace! sci-ctx 'dh
                      {'transact! (fn [tx-data]
                                    @(dh/transact conn tx-data))
                       'retract!  (fn [tx-data]
                                    @(dh/transact conn tx-data))}))

(defn add-spindel-sync-ns!
  "Expose spindel sync primitives to SCI as the 'sync namespace.
   DEPRECATED: Use add-spindel-extras-ns! which includes these plus combinators.

   Gives agents access to:
   - (sync/deferred)        - create a one-shot promise
   - (sync/deliver! d val)  - resolve a deferred
   - (sync/mailbox)         - create a FIFO queue
   - (sync/post! mb val)    - post to a mailbox"
  [sci-ctx spindel-ctx]
  (binding [rtc/*execution-context* spindel-ctx]
    (sci/add-namespace! sci-ctx 'sync
                        {'deferred  (fn [] (sync/deferred))
                         'deliver!  (fn [d v] (sync/deliver! d v))
                         'mailbox   (fn [] (sync/mailbox))
                         'post!     (fn [mb v] (mb v))})))

;; ---------------------------------------------------------------------------
;; Intake Namespaces (code-first agents)
;; ---------------------------------------------------------------------------

(defn add-intake-namespaces!
  "Expose intake library functions as SCI namespaces for code-first agents.

   After calling this, agents can write natural Clojure in clojure_eval:

     (require '[intake.hn :as hn])
     (require '[intake.web :as web])
     (hn/search \"datahike\" {:days-back 7})
     (web/fetch \"https://...\")

   Available namespaces:
   - intake.hn      — hn/search, hn/top
   - intake.web     — web/fetch, web/search (Brave)
   - intake.yt      — yt/transcript (raw), yt/transcript-summary (fetch + LLM summarize)
   - intake.tw      — tw/lookup
   - intake.reddit  — reddit/search, reddit/top
   - intake.lobsters — lobsters/hottest
   - intake.bluesky — bluesky/search (requires BLUESKY_HANDLE + BLUESKY_APP_PASSWORD)
   - intake.mail    — mail/inbox, mail/search, mail/read, mail/sync!
   - intake.zulip   — zulip/streams, zulip/messages, zulip/search, zulip/topics
   - intake.github  — github/search-repos, github/releases, github/trending,
                       github/user, github/contributors, github/search-code,
                       github/org-members, github/issues, github/repo-details
   - intake.devto   — devto/top (top articles by tag/time range)
   - intake.mastodon — mastodon/trending (trending posts/links from Mastodon instances)
   - intake.sec     — sec/search-companies, sec/company-facts, sec/filings, sec/insider-trades
   - intake.uk      — uk/search-companies, uk/company, uk/officers, uk/filings, uk/psc
   - intake.stock   — stock/quote, stock/profile, stock/earnings, stock/news,
                       stock/insiders, stock/peers, stock/financials
   - intake.rss     — rss/discover, rss/feed
   - intake.gleif   — gleif/search-entities, gleif/entity, gleif/direct-parent,
                       gleif/ultimate-parent, gleif/children, gleif/corporate-tree
   - intake.crt     — crt/search-certificates, crt/subdomains, crt/analyze
   - intake.wikidata — wikidata/search, wikidata/company-profile, wikidata/subsidiaries,
                        wikidata/competitors, wikidata/industry, wikidata/sparql
   - intake.wayback — wayback/snapshots, wayback/availability, wayback/snapshot,
                       wayback/changes
   - intake.linkedin — linkedin/parse-company, linkedin/parse-profile, linkedin/parse-jobs
   - intake.jobs — jobs/search, jobs/salary-history, jobs/top-companies, jobs/company-jobs"
  [sci-ctx]
  ;; Ensure all intake namespaces are loaded (idempotent)
  (require 'dvergr.intake.hn)
  (require 'dvergr.intake.web-fetch)
  (require 'dvergr.intake.web-search)
  (require 'dvergr.intake.youtube)
  (require 'dvergr.intake.twitter)
  (require 'dvergr.intake.reddit)
  (require 'dvergr.intake.lobsters)
  (require 'dvergr.intake.bluesky)
  (require 'dvergr.intake.mail)
  (require 'dvergr.intake.zulip)
  (require 'dvergr.intake.github-intake)
  (require 'dvergr.intake.devto)
  (require 'dvergr.intake.mastodon)
  (require 'dvergr.intake.sec-edgar)
  (require 'dvergr.intake.companies-house)
  (require 'dvergr.intake.finnhub)
  (require 'dvergr.intake.rss)
  (require 'dvergr.intake.gleif)
  (require 'dvergr.intake.crt-sh)
  (require 'dvergr.intake.wikidata)
  (require 'dvergr.intake.wayback)
  (require 'dvergr.intake.linkedin)
  (require 'dvergr.intake.adzuna)

  (sci/add-namespace! sci-ctx 'intake.hn
                      {'search @(ns-resolve 'dvergr.intake.hn 'search-stories)
                       'top    @(ns-resolve 'dvergr.intake.hn 'fetch-top)})

  (sci/add-namespace! sci-ctx 'intake.web
                      {'fetch  @(ns-resolve 'dvergr.intake.web-fetch 'fetch-page)
                       'search @(ns-resolve 'dvergr.intake.web-search 'search)})

  (let [raw-transcript @(ns-resolve 'dvergr.intake.youtube 'get-transcript)]
    (sci/add-namespace! sci-ctx 'intake.yt
                        {'transcript raw-transcript
                         'transcript-summary
                         (fn [url-or-id context-prompt]
                           (let [result (raw-transcript url-or-id)]
                             (if (:error result)
                               result
                               (let [llm-call (requiring-resolve 'dvergr.llm-call/cheap-llm-call)
                                     summary  (llm-call context-prompt
                                                        (str "Video: " (:title result) "\n\n"
                                                             (:transcript result))
                                                        {:max-tokens 800})]
                                 (assoc result
                                        :transcript-summary (:text summary)
                                        :transcript (subs (:transcript result) 0
                                                          (min 200 (count (:transcript result)))))))))}))

  (sci/add-namespace! sci-ctx 'intake.tw
                      {'lookup @(ns-resolve 'dvergr.intake.twitter 'lookup-tweet)})

  (sci/add-namespace! sci-ctx 'intake.reddit
                      {'search @(ns-resolve 'dvergr.intake.reddit 'search-posts)
                       'top    @(ns-resolve 'dvergr.intake.reddit 'fetch-top)})

  (sci/add-namespace! sci-ctx 'intake.lobsters
                      {'hottest @(ns-resolve 'dvergr.intake.lobsters 'fetch-hottest)})

  (sci/add-namespace! sci-ctx 'intake.bluesky
                      {'search @(ns-resolve 'dvergr.intake.bluesky 'search-posts)})

  (sci/add-namespace! sci-ctx 'intake.mail
                      {'inbox  @(ns-resolve 'dvergr.intake.mail 'list-inbox)
                       'search @(ns-resolve 'dvergr.intake.mail 'search-mail)
                       'read   @(ns-resolve 'dvergr.intake.mail 'read-message)
                       'sync!  @(ns-resolve 'dvergr.intake.mail 'sync-inbox!)})

  (sci/add-namespace! sci-ctx 'intake.zulip
                      {'streams  @(ns-resolve 'dvergr.intake.zulip 'fetch-streams)
                       'messages @(ns-resolve 'dvergr.intake.zulip 'fetch-messages)
                       'search   @(ns-resolve 'dvergr.intake.zulip 'search-messages)
                       'topics   @(ns-resolve 'dvergr.intake.zulip 'fetch-topics)})

  (sci/add-namespace! sci-ctx 'intake.github
                      {'search-repos    @(ns-resolve 'dvergr.intake.github-intake 'search-repos)
                       'releases        @(ns-resolve 'dvergr.intake.github-intake 'fetch-releases)
                       'trending        @(ns-resolve 'dvergr.intake.github-intake 'fetch-trending)
                       'user            @(ns-resolve 'dvergr.intake.github-intake 'fetch-user)
                       'contributors    @(ns-resolve 'dvergr.intake.github-intake 'fetch-contributors)
                       'search-code     @(ns-resolve 'dvergr.intake.github-intake 'search-code)
                       'org-members     @(ns-resolve 'dvergr.intake.github-intake 'fetch-org-members)
                       'issues          @(ns-resolve 'dvergr.intake.github-intake 'fetch-issues)
                       'repo-details    @(ns-resolve 'dvergr.intake.github-intake 'fetch-repo-details)})

  (sci/add-namespace! sci-ctx 'intake.devto
                      {'top @(ns-resolve 'dvergr.intake.devto 'fetch-top)})

  (sci/add-namespace! sci-ctx 'intake.mastodon
                      {'trending @(ns-resolve 'dvergr.intake.mastodon 'fetch-trending)})

  ;; SEC EDGAR (US public company financials, filings)
  (sci/add-namespace! sci-ctx 'intake.sec
                      {'search-companies @(ns-resolve 'dvergr.intake.sec-edgar 'search-companies)
                       'company-facts    @(ns-resolve 'dvergr.intake.sec-edgar 'fetch-company-facts)
                       'filings          @(ns-resolve 'dvergr.intake.sec-edgar 'fetch-filings)
                       'insider-trades   @(ns-resolve 'dvergr.intake.sec-edgar 'fetch-insider-trades)})

  ;; UK Companies House (company details, officers, filings, PSC)
  (sci/add-namespace! sci-ctx 'intake.uk
                      {'search-companies @(ns-resolve 'dvergr.intake.companies-house 'search-companies)
                       'company           @(ns-resolve 'dvergr.intake.companies-house 'fetch-company)
                       'officers          @(ns-resolve 'dvergr.intake.companies-house 'fetch-officers)
                       'filings           @(ns-resolve 'dvergr.intake.companies-house 'fetch-filing-history)
                       'psc               @(ns-resolve 'dvergr.intake.companies-house 'fetch-persons-significant-control)})

  ;; Finnhub (stock quotes, financials, earnings, news, insider trades)
  (sci/add-namespace! sci-ctx 'intake.stock
                      {'quote      @(ns-resolve 'dvergr.intake.finnhub 'fetch-quote)
                       'profile    @(ns-resolve 'dvergr.intake.finnhub 'fetch-company-profile)
                       'earnings   @(ns-resolve 'dvergr.intake.finnhub 'fetch-earnings)
                       'news       @(ns-resolve 'dvergr.intake.finnhub 'fetch-company-news)
                       'insiders   @(ns-resolve 'dvergr.intake.finnhub 'fetch-insider-transactions)
                       'peers      @(ns-resolve 'dvergr.intake.finnhub 'fetch-peers)
                       'financials @(ns-resolve 'dvergr.intake.finnhub 'fetch-basic-financials)})

  ;; RSS/Atom feeds (autodiscovery + parsing)
  (sci/add-namespace! sci-ctx 'intake.rss
                      {'discover @(ns-resolve 'dvergr.intake.rss 'discover-feeds)
                       'feed     @(ns-resolve 'dvergr.intake.rss 'fetch-feed)})

  ;; GLEIF LEI (legal entity identifiers, corporate ownership chains)
  (sci/add-namespace! sci-ctx 'intake.gleif
                      {'search-entities    @(ns-resolve 'dvergr.intake.gleif 'search-entities)
                       'entity             @(ns-resolve 'dvergr.intake.gleif 'fetch-entity)
                       'direct-parent      @(ns-resolve 'dvergr.intake.gleif 'fetch-direct-parent)
                       'ultimate-parent    @(ns-resolve 'dvergr.intake.gleif 'fetch-ultimate-parent)
                       'children           @(ns-resolve 'dvergr.intake.gleif 'fetch-children)
                       'corporate-tree     @(ns-resolve 'dvergr.intake.gleif 'map-corporate-tree)})

  ;; crt.sh Certificate Transparency (subdomain discovery)
  (sci/add-namespace! sci-ctx 'intake.crt
                      {'search-certificates @(ns-resolve 'dvergr.intake.crt-sh 'search-certificates)
                       'subdomains          @(ns-resolve 'dvergr.intake.crt-sh 'discover-subdomains)
                       'analyze             @(ns-resolve 'dvergr.intake.crt-sh 'analyze-subdomains)})

  ;; Wikidata SPARQL (structured entity relationships)
  (sci/add-namespace! sci-ctx 'intake.wikidata
                      {'search          @(ns-resolve 'dvergr.intake.wikidata 'search-entities)
                       'company-profile @(ns-resolve 'dvergr.intake.wikidata 'fetch-company-profile)
                       'subsidiaries    @(ns-resolve 'dvergr.intake.wikidata 'fetch-subsidiaries)
                       'competitors     @(ns-resolve 'dvergr.intake.wikidata 'fetch-competitors)
                       'industry        @(ns-resolve 'dvergr.intake.wikidata 'fetch-industry-companies)
                       'sparql          @(ns-resolve 'dvergr.intake.wikidata 'custom-sparql)})

  ;; Wayback Machine (historical website tracking)
  (sci/add-namespace! sci-ctx 'intake.wayback
                      {'snapshots    @(ns-resolve 'dvergr.intake.wayback 'search-snapshots)
                       'availability @(ns-resolve 'dvergr.intake.wayback 'check-availability)
                       'snapshot     @(ns-resolve 'dvergr.intake.wayback 'fetch-snapshot)
                       'changes      @(ns-resolve 'dvergr.intake.wayback 'track-changes)})

  ;; LinkedIn page parser (for browser extension captures)
  (sci/add-namespace! sci-ctx 'intake.linkedin
                      {'parse-company @(ns-resolve 'dvergr.intake.linkedin 'parse-company-page)
                       'parse-profile @(ns-resolve 'dvergr.intake.linkedin 'parse-profile-page)
                       'parse-jobs    @(ns-resolve 'dvergr.intake.linkedin 'parse-jobs-page)})

  ;; Adzuna job market API (search, salary trends, top companies)
  (sci/add-namespace! sci-ctx 'intake.jobs
                      {'search         @(ns-resolve 'dvergr.intake.adzuna 'search-jobs)
                       'salary-history @(ns-resolve 'dvergr.intake.adzuna 'salary-history)
                       'top-companies  @(ns-resolve 'dvergr.intake.adzuna 'top-companies)
                       'company-jobs   @(ns-resolve 'dvergr.intake.adzuna 'company-jobs)}))

;; ---------------------------------------------------------------------------
;; File Namespace (code persistence)
;; ---------------------------------------------------------------------------

(defn add-file-ns!
  "Expose file I/O as 'file namespace in SCI.

   For task (worktree) contexts pass base-path = worktree directory so that
   agent-written code ends up in the fork and can be merged back.
   Defaults to the JVM's current working directory.

   Available in SCI after calling this:
     (require '[file])
     (file/read \"src/agents/my_tool.clj\")
     (file/write \"src/agents/my_tool.clj\" source-string)
     (file/exists? \"path/to/file\")
     (file/list \"src/**/*.clj\")"
  [sci-ctx & {:keys [base-path] :or {base-path (System/getProperty "user.dir")}}]
  (let [resolve-path (fn [p]
                       (let [f (java.io.File. p)]
                         (if (.isAbsolute f) p (str base-path "/" p))))

        read-fn   (fn [path] (slurp (resolve-path path)))

        write-fn  (fn [path content]
                    (let [f (java.io.File. (resolve-path path))]
                      (some-> (.getParentFile f) .mkdirs)
                      (spit f content)
                      path))

        exists-fn (fn [path]
                    (.exists (java.io.File. (resolve-path path))))

        list-fn   (fn [pattern]
                    (let [base  (java.io.File. base-path)
                          ;; Convert glob to regex: ** matches anything, * matches non-slash
                          rx    (-> pattern
                                    (str/replace "." "\\.")
                                    (str/replace "**" "\u0000")
                                    (str/replace "*" "[^/]*")
                                    (str/replace "\u0000" ".*"))
                          regex (re-pattern rx)]
                      (->> (file-seq base)
                           (filter #(.isFile %))
                           (map #(.getPath %))
                           (remove #(re-find #"/\.[^/]+" %))  ; skip .git, .git-worktrees, etc.
                           (filter #(re-find regex %))
                           sort
                           vec)))]
    (sci/add-namespace! sci-ctx 'file
                        {'read    read-fn
                         'write   write-fn
                         'exists? exists-fn
                         'list    list-fn})))

;; ---------------------------------------------------------------------------
;; Enhanced Filesystem Namespace
;; ---------------------------------------------------------------------------

(defn- fs-safe-resolve
  "Resolve user-supplied path against base-dir, canonicalising symlinks and `..`
   via File.getCanonicalFile so that the result is a real path with no traversal
   components.  Throws ex-info if the canonical result would escape base-dir.

   base-canonical must already be canonical (computed once at add-fs-ns! time)."
  ^java.io.File [^java.io.File base-canonical user-path]
  (let [resolved (-> (java.io.File. base-canonical (str user-path))
                     .getCanonicalFile)]
    (when-not (.startsWith (.toPath resolved) (.toPath base-canonical))
      (throw (ex-info "Path escape attempt: resolved path is outside sandbox"
                      {:path user-path :base (str base-canonical)})))
    resolved))

(defn add-fs-ns!
  "Expose rich filesystem operations as 'fs namespace in SCI.

   Backed by babashka.fs. All user-supplied paths are canonicalised via
   File.getCanonicalFile before use, which resolves '..' components and follows
   symlinks.  Any path that resolves outside base-path throws immediately.
   Sensitive OS path patterns (/etc/passwd, .ssh/, .env, etc.) are also blocked.

   :base-path  - absolute root for all relative path resolution (default: user.dir)
   :audit-log  - atom from (make-audit-log); records every FS op with timestamp

   Usage in SCI:
     (require '[fs])
     (fs/ls \"src\")
     ;; => [{:name \"core.clj\" :path \"/abs/src/core.clj\" :type :file :size 1234 :modified \"...\"}]
     (fs/ls \"src\" \"*.clj\")
     (fs/glob \"**/*.clj\")
     (fs/stat \"src/core.clj\")    ; => {:path :type :size :modified}
     (fs/mkdir \"new/nested/dir\")
     (fs/delete \"tmp/scratch.clj\")
     (fs/move \"old.clj\" \"new.clj\")
     (fs/copy \"src.clj\" \"dst.clj\")
     (fs/read \"src/core.clj\")
     (fs/write \"src/out.clj\" content)"
  [sci-ctx & {:keys [base-path audit-log]
              :or   {base-path (System/getProperty "user.dir")}}]
  (require 'babashka.fs)
  (let [fs-ns        (find-ns 'babashka.fs)
        bb-exists?   @(ns-resolve fs-ns 'exists?)
        bb-dir?      @(ns-resolve fs-ns 'directory?)
        bb-file?     @(ns-resolve fs-ns 'regular-file?)
        bb-symlink?  @(ns-resolve fs-ns 'sym-link?)
        bb-size      @(ns-resolve fs-ns 'size)
        bb-modified  @(ns-resolve fs-ns 'last-modified-time)
        bb-parent    @(ns-resolve fs-ns 'parent)
        bb-fname     @(ns-resolve fs-ns 'file-name)
        bb-list-dir  @(ns-resolve fs-ns 'list-dir)
        bb-glob      @(ns-resolve fs-ns 'glob)
        bb-mkdirs    @(ns-resolve fs-ns 'create-dirs)
        bb-delete    @(ns-resolve fs-ns 'delete-if-exists)
        bb-move      @(ns-resolve fs-ns 'move)
        bb-copy      @(ns-resolve fs-ns 'copy)

        ;; Canonicalise base-path once at construction time
        base-canonical (-> (java.io.File. (str base-path)) .getCanonicalFile)

        ;; Safe resolve: canonical check + sensitive-path guard
        safe-resolve (fn [user-path]
                       (let [path-str (str user-path)]
                         (sensitive-path-policy path-str)
                         (fs-safe-resolve base-canonical path-str)))

        entry->map  (fn [p]
                      {:name     (str (bb-fname p))
                       :path     (str p)
                       :type     (cond (bb-dir? p)  :dir
                                       (bb-file? p) :file
                                       :else        :other)
                       :size     (when (bb-file? p) (bb-size p))
                       :modified (str (bb-modified p))})

        read-fn     (fn [path]
                      (let [f (safe-resolve path)]
                        (audit! audit-log :fs/read {:path (str f)})
                        (slurp f)))

        write-fn    (fn [path content]
                      (let [f (safe-resolve path)]
                        (audit! audit-log :fs/write {:path (str f)})
                        (some-> (bb-parent f) bb-mkdirs)
                        (spit f content)
                        path))

        exists-fn   (fn [path]
                      (bb-exists? (safe-resolve path)))

        ls-fn       (fn
                      ([dir]
                       (let [d (safe-resolve dir)]
                         (audit! audit-log :fs/ls {:path (str d)})
                         (->> (bb-list-dir d) (mapv entry->map))))
                      ([dir pattern]
                       (let [d (safe-resolve dir)]
                         (audit! audit-log :fs/ls {:path (str d) :pattern pattern})
                         (->> (bb-glob d pattern) (mapv entry->map)))))

        glob-fn     (fn [pattern]
                      (audit! audit-log :fs/glob {:base (str base-canonical) :pattern pattern})
                      (->> (bb-glob base-canonical pattern) (mapv str)))

        mkdir-fn    (fn [path]
                      (let [f (safe-resolve path)]
                        (audit! audit-log :fs/mkdir {:path (str f)})
                        (str (bb-mkdirs f))))

        delete-fn   (fn [path]
                      (let [f (safe-resolve path)]
                        (audit! audit-log :fs/delete {:path (str f)})
                        (bb-delete f)))

        move-fn     (fn [src dst]
                      (let [sf (safe-resolve src)
                            df (safe-resolve dst)]
                        (audit! audit-log :fs/move {:src (str sf) :dst (str df)})
                        (bb-move sf df)
                        dst))

        copy-fn     (fn [src dst]
                      (let [sf (safe-resolve src)
                            df (safe-resolve dst)]
                        (audit! audit-log :fs/copy {:src (str sf) :dst (str df)})
                        (bb-copy sf df)
                        dst))

        stat-fn     (fn [path]
                      (let [f (safe-resolve path)]
                        {:path     (str f)
                         :type     (cond (bb-dir? f)     :dir
                                         (bb-file? f)    :file
                                         (bb-symlink? f) :symlink
                                         :else           :other)
                         :size     (when (bb-file? f) (bb-size f))
                         :modified (str (bb-modified f))}))]

    (sci/add-namespace! sci-ctx 'fs
                        {'read    read-fn
                         'write   write-fn
                         'exists? exists-fn
                         'ls      ls-fn
                         'glob    glob-fn
                         'mkdir   mkdir-fn
                         'delete  delete-fn
                         'move    move-fn
                         'copy    copy-fn
                         'stat    stat-fn})))

;; ---------------------------------------------------------------------------
;; Process Namespace (capability-gated subprocess execution)
;; ---------------------------------------------------------------------------

(defn- run-process*
  "Execute a subprocess via ProcessBuilder.
   Returns {:exit int :out string :err string} or throws on timeout."
  [{:keys [allow base-path extra-env timeout-ms]
    :or   {timeout-ms 30000}}
   cmd args]
  (when-not (contains? (set (map str allow)) (str cmd))
    (throw (ex-info (str "Command not in allowlist: " cmd)
                    {:cmd cmd :allow allow})))
  (let [all-args  (into [(str cmd)] (map str args))
        pb        (doto (ProcessBuilder. ^java.util.List all-args)
                    (.directory (java.io.File. (str base-path))))
        _         (when extra-env
                    (let [env (.environment pb)]
                      (doseq [[k v] extra-env]
                        (.put env (str k) (str v)))))
        proc      (.start pb)
        out-fut   (future (slurp (.getInputStream proc)))
        err-fut   (future (slurp (.getErrorStream proc)))
        finished? (.waitFor proc (long timeout-ms)
                            java.util.concurrent.TimeUnit/MILLISECONDS)]
    (if finished?
      {:exit (.exitValue proc) :out @out-fut :err @err-fut}
      (do (.destroyForcibly proc)
          (throw (ex-info (str cmd " timed out after " timeout-ms "ms")
                          {:cmd cmd :timeout-ms timeout-ms}))))))

(defn add-proc-ns!
  "Expose capability-gated process execution as 'proc namespace in SCI.

   :allow    - set of allowed command name strings (default: #{}, nothing allowed)
   :base-path - working directory for all commands (default: user.dir)

   Usage in SCI:
     (require '[proc])
     (proc/run \"git\" \"status\")
     ;; => {:exit 0 :out \"On branch main\\n...\" :err \"\"}

     (proc/run! \"git\" \"add\" \".\")
     ;; => {:exit 0 ...} or throws ex-info with {:exit :out :err}

     (proc/lines \"git\" \"log\" \"--oneline\" \"-5\")
     ;; => [\"abc1234 Fix bug\" ...]

     ;; Pass opts map as first arg for extra-env or timeout:
     (proc/run {:extra-env {\"NODE_ENV\" \"test\"}} \"npm\" \"test\")

   Commands not in :allow throw immediately without spawning a process."
  [sci-ctx & {:keys [allow base-path]
              :or   {allow     #{}
                     base-path (System/getProperty "user.dir")}}]
  (let [defaults    {:allow allow :base-path base-path}

        parse-args  (fn [args]
                      ;; Optional leading opts map merges with defaults
                      (if (map? (first args))
                        [(merge defaults (first args)) (second args) (drop 2 args)]
                        [defaults (first args) (rest args)]))

        run-fn      (fn [& args]
                      (let [[opts cmd rest-args] (parse-args args)]
                        (run-process* opts cmd rest-args)))

        run!-fn     (fn [& args]
                      (let [[opts cmd rest-args] (parse-args args)
                            result               (run-process* opts cmd rest-args)]
                        (if (zero? (:exit result))
                          result
                          (throw (ex-info (str cmd " exited with " (:exit result))
                                          result)))))

        lines-fn    (fn [& args]
                      (let [[opts cmd rest-args] (parse-args args)
                            result               (run-process* opts cmd rest-args)]
                        (if (zero? (:exit result))
                          (str/split-lines (:out result))
                          (throw (ex-info (str cmd " exited with " (:exit result))
                                          result)))))]

    (sci/add-namespace! sci-ctx 'proc
                        {'run   run-fn
                         'run!  run!-fn
                         'lines lines-fn})))

;; ---------------------------------------------------------------------------
;; Git Namespace (structured git operations for worktree agents)
;; ---------------------------------------------------------------------------

(defn- git-run*
  "Run git in base-path. Returns stdout string or throws on non-zero exit."
  [base-path & args]
  (let [all-args (into ["git"] (map str args))
        pb       (doto (ProcessBuilder. ^java.util.List all-args)
                   (.directory (java.io.File. (str base-path))))
        proc     (.start pb)
        out      (future (slurp (.getInputStream proc)))
        err      (future (slurp (.getErrorStream proc)))
        exit     (.waitFor proc)]
    (if (zero? exit)
      @out
      (throw (ex-info (str "git " (first args) " failed")
                      {:exit exit :out @out :err @err :args args})))))

(defn- parse-porcelain-status
  "Parse `git status --porcelain=v1 --branch` into a structured map."
  [output]
  (let [lines       (str/split-lines output)
        branch-line (first (filter #(str/starts-with? % "## ") lines))
        branch      (when branch-line
                      (-> (subs branch-line 3)
                          (str/split #"\.\.\.")
                          first
                          str/trim))
        file-lines  (remove #(str/starts-with? % "##") lines)
        entries     (->> file-lines
                         (remove str/blank?)
                         (mapv (fn [line]
                                 (when (>= (count line) 4)
                                   (let [xy   (subs line 0 2)
                                         x    (subs xy 0 1)
                                         y    (subs xy 1 2)
                                         path (str/trim (subs line 3))]
                                     {:path       path
                                      ;; X column: staged change (not untracked/ignored/unmodified)
                                      :staged?    (and (not= " " x) (not= "?" x) (not= "!" x))
                                      ;; Y column: unstaged change (not untracked/ignored/unmodified)
                                      :unstaged?  (and (not= " " y) (not= "?" y) (not= "!" y))
                                      :untracked? (= "??" xy)}))))
                         (remove nil?))]
    {:branch    (or branch "unknown")
     :staged    (mapv :path (filter :staged? entries))
     :unstaged  (mapv :path (filter :unstaged? entries))
     :untracked (mapv :path (filter :untracked? entries))}))

(defn- parse-git-log
  "Parse `git log --format=%H|%s|%an|%ai` output into vector of maps."
  [output]
  (->> (str/split-lines output)
       (remove str/blank?)
       (mapv (fn [line]
               (let [[hash msg author date] (str/split line #"\|" 4)]
                 {:hash    (str/trim (str hash))
                  :message (str/trim (str msg))
                  :author  (str/trim (str author))
                  :date    (str/trim (str date))})))))

(defn add-git-ns!
  "Expose structured git operations as 'git namespace in SCI.

   Agents working in yggdrasil worktrees need programmatic git access
   to understand workspace state before committing. This namespace returns
   structured Clojure data rather than raw strings.

   :base-path - git working directory (default: user.dir)
   :audit-log - atom from (make-audit-log); records write ops (add, commit)

   Usage in SCI:
     (require '[git])

     (git/status)
     ;; => {:branch \"main\" :staged [\"foo.clj\"] :unstaged [] :untracked [\"bar.clj\"]}

     (git/log {:n 5})
     ;; => [{:hash \"abc1234\" :message \"Fix bug\" :author \"Alice\" :date \"2026-04-15...\"}]

     (git/diff)                ; unstaged changes as string
     (git/diff \"src/foo.clj\") ; single file diff

     (git/add \"src/foo.clj\")  ; stage file(s)
     (git/add \".\")            ; stage all

     (git/commit \"Add feature\")
     ;; => \"[main abc1234] Add feature\""
  [sci-ctx & {:keys [base-path audit-log]
              :or   {base-path (System/getProperty "user.dir")}}]
  (let [run!      (fn [& args] (apply git-run* base-path args))

        status-fn (fn []
                    (parse-porcelain-status
                      (run! "status" "--porcelain=v1" "--branch")))

        log-fn    (fn [& [opts]]
                    (let [n (str "-" (or (:n opts) 10))]
                      (parse-git-log
                        (run! "log" "--format=%H|%s|%an|%ai" n))))

        diff-fn   (fn [& args]
                    (if (seq args)
                      (apply run! "diff" args)
                      (run! "diff")))

        add-fn    (fn [& paths]
                    (audit! audit-log :git/add {:paths (vec paths)})
                    (apply run! "add" paths)
                    :ok)

        commit-fn (fn [message & [opts]]
                    (audit! audit-log :git/commit {:message message})
                    (let [args (cond-> ["commit" "-m" message]
                                 (:author opts) (into ["--author" (:author opts)]))]
                      (str/trim (apply run! args))))]

    (sci/add-namespace! sci-ctx 'git
                        {'status status-fn
                         'log    log-fn
                         'diff   diff-fn
                         'add    add-fn
                         'commit commit-fn})))

;; ---------------------------------------------------------------------------
;; LLM Namespace (cheap sub-calls)
;; ---------------------------------------------------------------------------

(defn add-llm-ns!
  "Expose cheap one-shot LLM calls as 'llm namespace in SCI.

   Agents can write natural Clojure in clojure_eval:

     (require '[llm])
     (llm/summarize transcript {:max-tokens 300})
     (llm/call \"Extract product names:\" content)

   Returns {:text :usage :model} or {:error}.
   No SCI-level budget tracking — account at the tool level."
  [sci-ctx]
  (require 'dvergr.llm-call)
  (let [call-fn      @(ns-resolve 'dvergr.llm-call 'cheap-llm-call)
        summarize-fn (fn [content & [opts]]
                       (call-fn "Summarize the key points concisely:"
                                content (or opts {})))]
    (sci/add-namespace! sci-ctx 'llm
                        {'call      call-fn
                         'summarize summarize-fn})))

(defn add-calendar-ns!
  "Expose calendar CRUD and query functions as 'calendar namespace in SCI.

   After calling this, agents can write:
     (require '[calendar])
     (calendar/today)
     (calendar/add-event! {:title \"Review\" :start (calendar/from-now {:hours 2})
                           :end (calendar/from-now {:hours 2 :minutes 30})
                           :type :discussion :participants [:volva :muninn]})
     (calendar/free-slots \"2026-02-24\" 30)"
  [sci-ctx conn]
  (require 'dvergr.calendar.core)
  (let [cal-ns (find-ns 'dvergr.calendar.core)]
    (sci/add-namespace! sci-ctx 'calendar
                        {'add-event!    (partial @(ns-resolve cal-ns 'add-event!) conn)
                         'update-event! (partial @(ns-resolve cal-ns 'update-event!) conn)
                         'cancel-event! (partial @(ns-resolve cal-ns 'cancel-event!) conn)
                         'delete-event! (partial @(ns-resolve cal-ns 'delete-event!) conn)
                         'get-event     (partial @(ns-resolve cal-ns 'get-event) conn)
                         'today         (partial @(ns-resolve cal-ns 'today) conn)
                         'week          (partial @(ns-resolve cal-ns 'week) conn)
                         'events-between (partial @(ns-resolve cal-ns 'events-between) conn)
                         'events-for-participant (partial @(ns-resolve cal-ns 'events-for-participant) conn)
                         'free-slots    (partial @(ns-resolve cal-ns 'free-slots) conn)
                         'from-now      @(ns-resolve cal-ns 'from-now)})))

(defn add-search-ns!
  "Expose fulltext search as 'search namespace in SCI.

   After calling this, agents can write:
     (require '[search])
     (search/find \"datahike\" {:source \"youtube\" :limit 10})
     (search/count)

   Read-only — agents cannot modify the index from SCI."
  [sci-ctx]
  (let [search-fn (fn [query & [opts]]
                    (let [s (requiring-resolve 'dvergr.search/search)]
                      (apply s query (mapcat identity (or opts {})))))
        count-fn  (fn []
                    ((requiring-resolve 'dvergr.search/doc-count)))]
    (sci/add-namespace! sci-ctx 'search
                        {'find  search-fn
                         'count count-fn})))

(defn add-entity-ns!
  "Expose entity knowledge-graph operations as 'entity namespace in SCI.

   After calling this, agents can write:
     (require '[entity])
     (entity/get \"AcmeDB\")
     (entity/list {:type \"person\"})
     (entity/link! \"Alice Chen\" \"AcmeDB\")
     (entity/add-source! \"Alice Chen\" {:type \"linkedin\" :url \"https://...\"})"
  [sci-ctx conn]
  (let [get-fn     (fn [title]
                     (dh/q '[:find (pull ?e [*]) .
                             :in $ ?t
                             :where [?e :entity/title ?t]]
                           @conn title))
        list-fn    (fn [& [opts]]
                     (let [type-kw (when-let [t (:type opts)] (keyword t))
                           entities (if type-kw
                                      (dh/q '[:find [(pull ?e [*]) ...]
                                              :in $ ?type
                                              :where [?e :entity/id _]
                                                     [?e :entity/type ?type]]
                                            @conn type-kw)
                                      (dh/q '[:find [(pull ?e [*]) ...]
                                              :where [?e :entity/id _]]
                                            @conn))
                           limit (or (:limit opts) 50)]
                       (->> entities
                            (sort-by #(- (or (:entity/mention-count %) 0)))
                            (take limit))))
        link-fn    (fn [from-title to-title]
                     (let [from-eid (dh/q '[:find ?e . :in $ ?t :where [?e :entity/title ?t]] @conn from-title)
                           to-eid   (dh/q '[:find ?e . :in $ ?t :where [?e :entity/title ?t]] @conn to-title)]
                       (if (and from-eid to-eid)
                         (do @(dh/transact conn [[:db/add from-eid :entity/links to-eid]])
                             {:linked [from-title to-title]})
                         {:error (str "Entity not found: " (when-not from-eid from-title) (when-not to-eid to-title))})))
        add-source-fn (fn [title source-map]
                        (let [eid (dh/q '[:find ?e . :in $ ?t :where [?e :entity/title ?t]] @conn title)]
                          (if-not eid
                            {:error (str "Entity not found: " title)}
                            (let [existing (dh/q '[:find ?s . :in $ ?e :where [?e :entity/sync-sources ?s]] @conn eid)
                                  sources  (if existing (clojure.edn/read-string existing) [])
                                  updated  (if (some #(= (:url %) (:url source-map)) sources)
                                             sources
                                             (conj sources source-map))]
                              @(dh/transact conn [{:db/id eid :entity/sync-sources (pr-str updated)}])
                              {:added source-map :total (count updated)}))))
        peers-fn   (fn [title]
                     (let [eid (dh/q '[:find ?e . :in $ ?t :where [?e :entity/title ?t]] @conn title)]
                       (when eid
                         (dh/q '[:find [(pull ?linked [:entity/title :entity/type :entity/summary]) ...]
                                 :in $ ?e
                                 :where [?e :entity/links ?linked]]
                               @conn eid))))]
    (sci/add-namespace! sci-ctx 'entity
                        {'get        get-fn
                         'list       list-fn
                         'link!      link-fn
                         'add-source! add-source-fn
                         'peers      peers-fn})))

(defn add-env-ns!
  "Expose environment/secrets access as 'env namespace in SCI.

   Resolution order:
   1. User config map (per-user secrets from Datahike/profile)
   2. System environment variables (server-level)

   Usage in SCI:
     (require '[env])
     (env/get \"SLACK_TOKEN\")      ;; => \"xoxb-...\" or nil
     (env/get \"API_KEY\" \"default\") ;; with fallback
     (env/keys)                     ;; list configured keys (no values)
     (env/set \"KEY\" \"value\")     ;; store in user config (if mutable)

   The user-config atom can be populated from Datahike user profile
   or passed directly for testing."
  [sci-ctx & {:keys [user-config]}]
  (let [config-atom (or user-config (atom {}))
        get-fn (fn
                 ([key]
                  (or (get @config-atom key)
                      (get @config-atom (keyword key))
                      (System/getenv (if (keyword? key) (name key) (str key)))))
                 ([key default]
                  (or (get @config-atom key)
                      (get @config-atom (keyword key))
                      (System/getenv (if (keyword? key) (name key) (str key)))
                      default)))
        set-fn (fn [key value]
                 (swap! config-atom assoc (str key) value)
                 :ok)
        keys-fn (fn []
                  (vec (distinct
                         (concat (map str (keys @config-atom))
                                 ;; Don't expose all env vars — only whitelisted prefixes
                                 (->> (System/getenv)
                                      keys
                                      (filter #(or (.startsWith % "DVERGR_")
                                                   (.startsWith % "SLACK_")
                                                   (.startsWith % "NOTION_")
                                                   (.startsWith % "GITHUB_")
                                                   (.startsWith % "TRELLO_")
                                                   (.startsWith % "LINEAR_")
                                                   (.startsWith % "DISCORD_"))))))))]
    (sci/add-namespace! sci-ctx 'env
                        {'get  get-fn
                         'set  set-fn
                         'keys keys-fn})))

(defn add-http-ns!
  "Expose HTTP client as 'http namespace in SCI.

   Agents can make HTTP requests to build integrations.
   Responses are returned as maps with :status, :headers, :body.
   JSON bodies are auto-parsed.

   :audit-log      - atom from (make-audit-log); records every request (method + url, no body)
   :allowed-domains - set of URL prefix strings; non-empty set restricts outbound requests.
                      Empty or nil permits all domains (open).
                      Example: #{\"https://api.github.com\" \"https://slack.com\"}

   Usage in SCI:
     (require '[http])
     (http/get \"https://api.github.com/repos/clojure/clojure\")
     (http/post \"https://slack.com/api/chat.postMessage\"
       {:headers {\"Authorization\" (str \"Bearer \" (env/get \"SLACK_TOKEN\"))}
        :json {:channel \"#general\" :text \"Hello from dvergr\"}})
     (http/request {:url \"...\" :method :put :headers {...} :body \"...\"})"
  [sci-ctx & {:keys [audit-log allowed-domains]}]
  (let [domain-check (make-domain-policy allowed-domains)
        do-request
        (fn [{:keys [url method headers body json query-params timeout]
              :or {method :get timeout 30000}}]
          ;; Audit first (even blocked requests should appear in the log)
          ;; then enforce domain policy — order matters for forensics
          (audit! audit-log :http/request {:method method :url url})
          (when domain-check (domain-check url))
          (require 'hato.client)
          (let [hato-request (requiring-resolve 'hato.client/request)
                opts (cond-> {:url url
                              :method method
                              :connect-timeout timeout
                              :socket-timeout timeout}
                       headers (assoc :headers headers)
                       body (assoc :body body)
                       json (-> (assoc :content-type :json
                                       :body ((requiring-resolve 'cheshire.core/encode) json))
                                (update :headers merge {"Content-Type" "application/json"}))
                       query-params (assoc :query-params query-params))
                resp (hato-request opts)
                body-str (:body resp)
                ;; Auto-parse JSON responses
                parsed (if (and body-str
                                (some-> (get-in resp [:headers "content-type"])
                                        (clojure.string/includes? "json")))
                          (try ((requiring-resolve 'cheshire.core/decode) body-str true)
                               (catch Exception _ body-str))
                          body-str)]
            {:status (:status resp)
             :headers (into {} (:headers resp))
             :body parsed}))]
    (sci/add-namespace! sci-ctx 'http
                        {'request do-request
                         'get     (fn [url & [opts]] (do-request (merge {:url url :method :get} opts)))
                         'post    (fn [url & [opts]] (do-request (merge {:url url :method :post} opts)))
                         'put     (fn [url & [opts]] (do-request (merge {:url url :method :put} opts)))
                         'patch   (fn [url & [opts]] (do-request (merge {:url url :method :patch} opts)))
                         'delete  (fn [url & [opts]] (do-request (merge {:url url :method :delete} opts)))})))

(defn add-process-ns!
  "Expose the deliberable-process surface to SCI.

   Bound to a chat-ctx so the wrapper fns operate on the right registry.
   Vár can call these from clojure_eval to see + steer her own long-
   running work:

     (require '[processes])
     (processes/list)                       ; → vector of snapshots
     (processes/snapshot some-pid)          ; → single snapshot
     (processes/directive! pid {:type :abort :reason \"…\"})
     (processes/directive! pid {:type :extend-budget :dollars 0.10})
     (processes/directive! pid {:type :refocus :hint \"…\"})"
  [sci-ctx chat-ctx]
  (let [proc-ns   (find-ns 'dvergr.process)
        list-fn   (var-get (ns-resolve proc-ns 'list-processes))
        snap-fn   (var-get (ns-resolve proc-ns 'snapshot))
        get-fn    (var-get (ns-resolve proc-ns 'get-process))
        dir-fn    (var-get (ns-resolve proc-ns 'directive!))]
    (sci/add-namespace! sci-ctx 'processes
                        {'list       (fn [] (list-fn chat-ctx))
                         'snapshot   (fn [pid] (when-let [p (get-fn chat-ctx pid)]
                                                 (snap-fn p)))
                         'directive! (fn [pid d] (dir-fn chat-ctx pid d))})))

(declare add-scheduler-ns!)

(defn setup-agent-namespaces!
  "Wire execution-context-aware namespaces into a SCI context.

   Call after fork-for-session, passing the spindel execution context
   (which for worker agents is the forked context with isolated systems).

   Sets up:
   - dh/q dh/pull dh/entity ...      - datahike read (if datahike registered)
   - dh/transact! dh/retract!        - datahike write (same forked conn)
   - sync/deferred sync/mailbox      - spindel sync primitives
   - intake.hn, intake.web, etc.     - intake library functions
   - fs/read, fs/write, fs/ls, ...   - rich filesystem (path-safe, audited)
   - file/read, file/write, etc.     - legacy file I/O (kept for compat)
   - proc/run, proc/run!, proc/lines - capability-gated process execution
   - git/status, git/log, git/diff, git/add, git/commit - structured git (audited)
   - llm/call, llm/summarize         - cheap one-shot LLM calls
   - calendar/today, calendar/add-event!, etc. - calendar CRUD and queries
   - entity/get entity/list entity/link! entity/add-source! entity/peers

   Because writes go to the fork-local datahike conn, transact! in a worker
   agent context writes to the isolated fork — nothing lands in the parent
   until merge! is called.

   base-path controls where fs/* and git/* resolve relative paths.
   Pass the worktree directory for task contexts.

   proc-allow is the capability set for proc/run (set of command name strings).
   Defaults to #{} (nothing). Add e.g. #{\"clj\" \"npm\" \"cargo\"} for build agents.
   git/* is always available and uses git directly (already scoped to worktree).

   allowed-http-domains is a set of URL prefixes; non-empty restricts outbound HTTP.

   Returns the audit-log atom — a vector of IO events ({:op :t :data}) accumulated
   during the agent's execution.  Attach to the agent result for post-hoc analysis."
  [sci-ctx spindel-ctx & {:keys [base-path proc-allow allowed-http-domains]
                           :or   {proc-allow #{}}}]
  (let [audit-log  (make-audit-log)
        cwd        (or base-path (System/getProperty "user.dir"))]
    (binding [rtc/*execution-context* spindel-ctx]
      (when-let [dh-sys (rtc/get-state [:external-refs "dvergr-chat-db"])]
        (let [conn    (:conn dh-sys)
              db-atom (atom @conn)]
          (add-datahike-query-ns! sci-ctx db-atom)
          (add-datahike-write-ns! sci-ctx conn)
          (add-calendar-ns! sci-ctx conn)
          (add-entity-ns! sci-ctx conn))))
    (add-spindel-extras-ns! sci-ctx spindel-ctx)
    (add-intake-namespaces! sci-ctx)
    (add-search-ns! sci-ctx)
    (apply add-file-ns! sci-ctx (when base-path [:base-path base-path]))
    (add-fs-ns!   sci-ctx :base-path cwd :audit-log audit-log)
    (add-proc-ns! sci-ctx :allow proc-allow :base-path cwd)
    (add-git-ns!  sci-ctx :base-path cwd :audit-log audit-log)
    (add-llm-ns!  sci-ctx)
    (add-env-ns!  sci-ctx)
    (add-http-ns! sci-ctx :audit-log audit-log :allowed-domains allowed-http-domains)
    (add-scheduler-ns! sci-ctx)
    audit-log))

(defn add-scheduler-ns!
  "Expose scheduling as 'scheduler namespace in SCI.

   Agents can schedule themselves or other agents:
     (require '[scheduler])
     (scheduler/every :day \"09:00\" :huginn \"Run morning intake sweep\")
     (scheduler/every :week :monday \"14:00\" :analyst \"Weekly market review\")
     (scheduler/at \"2026-04-01T09:00\" :var \"April Fools reminder\")
     (scheduler/cancel schedule-id)
     (scheduler/list)"
  [sci-ctx]
  (require 'dvergr.scheduler.core)
  (let [sched-create  @(ns-resolve 'dvergr.scheduler.core 'create-schedule!)
        sched-cancel  @(ns-resolve 'dvergr.scheduler.core 'cancel-schedule!)
        sched-list    @(ns-resolve 'dvergr.scheduler.core 'list-schedules)

        every-fn (fn [period & args]
                   (let [[opts agent-id task]
                         (cond
                           (and (keyword? (first args)) (string? (second args)))
                           [{:every period :on (first args) :at (second args)}
                            (nth args 2) (nth args 3)]
                           (string? (first args))
                           [{:every period :at (first args)} (second args) (nth args 2)]
                           (keyword? (first args))
                           [{:every period} (first args) (second args)]
                           :else
                           (throw (ex-info "Invalid schedule args" {:period period :args args})))]
                     (sched-create nil
                       {:agent-id agent-id
                        :task task
                        :schedule opts
                        :description (str "Every " (name period)
                                          (when (:at opts) (str " at " (:at opts)))
                                          (when (:on opts) (str " on " (name (:on opts)))))})))

        at-fn (fn [datetime agent-id task]
                (sched-create nil
                  {:agent-id agent-id :task task
                   :schedule {:at datetime :once true}
                   :description (str "One-shot at " datetime)}))

        interval-fn (fn [ms agent-id task]
                      (sched-create nil
                        {:agent-id agent-id :task task
                         :interval-ms ms
                         :description (str "Every " (/ ms 60000.0) " minutes")}))]
    (sci/add-namespace! sci-ctx 'scheduler
                        {'every    every-fn
                         'at       at-fn
                         'interval interval-fn
                         'cancel   sched-cancel
                         'list     sched-list})))

(defn add-datahike-diff-ns!
  "Add datahike diff/merge helpers to SCI context.

   Exposes functions for comparing databases:
   - diff/find-new-entities
   - diff/find-modified-entities
   - diff/find-deleted-entities

   These help agents analyze what changed between parent/child branches."
  [sci-ctx parent-db-atom child-db-atom]
  (let [find-new-fn
        (fn [entity-attr]
          (let [parent-ids (set (dh/q `[:find [~'?id ...]
                                        :where [~'?e ~entity-attr ~'?id]]
                                      @parent-db-atom))
                child-entities (dh/q `[:find [(~'pull ~'?e [~'*]) ...]
                                       :where [~'?e ~entity-attr ~'_]]
                                     @child-db-atom)]
            (filterv #(not (parent-ids (get % entity-attr))) child-entities)))

        find-modified-fn
        (fn [entity-attr compare-attrs]
          (let [parent-entities (into {}
                                  (map (fn [e] [(get e entity-attr) (select-keys e compare-attrs)])
                                       (dh/q `[:find [(~'pull ~'?e ~compare-attrs) ...]
                                               :where [~'?e ~entity-attr ~'_]]
                                             @parent-db-atom)))
                child-entities (dh/q `[:find [(~'pull ~'?e ~compare-attrs) ...]
                                       :where [~'?e ~entity-attr ~'_]]
                                     @child-db-atom)]
            (filterv (fn [child-e]
                       (when-let [parent-e (get parent-entities (get child-e entity-attr))]
                         (not= (select-keys parent-e compare-attrs)
                               (select-keys child-e compare-attrs))))
                     child-entities)))

        find-deleted-fn
        (fn [entity-attr]
          (let [child-ids (set (dh/q `[:find [~'?id ...]
                                       :where [~'?e ~entity-attr ~'?id]]
                                     @child-db-atom))
                parent-entities (dh/q `[:find [(~'pull ~'?e [~'*]) ...]
                                        :where [~'?e ~entity-attr ~'_]]
                                      @parent-db-atom)]
            (filterv #(not (child-ids (get % entity-attr))) parent-entities)))]

    (sci/add-namespace! sci-ctx 'diff
                        {'find-new-entities find-new-fn
                         'find-modified-entities find-modified-fn
                         'find-deleted-entities find-deleted-fn})))

;; ---------------------------------------------------------------------------
;; clojure.test Integration (from Babashka)
;; ---------------------------------------------------------------------------

(defn add-clojure-test-ns!
  "Add clojure.test namespace to SCI context.

   Exposes Babashka's SCI-compatible clojure.test implementation:
   - deftest, is, testing, are - test definition macros
   - run-tests, run-all-tests - test execution
   - use-fixtures, compose-fixtures - test fixtures
   - *report-counters*, do-report - custom reporting

   This enables agents to write and run tests completely isolated in SCI:

   (add-clojure-test-ns! sci-ctx)
   (eval-code sci-ctx \"
     (ns my-test
       (:require [clojure.test :refer [deftest is testing run-tests]]))

     (deftest my-test
       (testing \\\"addition\\\"
         (is (= 4 (+ 2 2)))))

     (run-tests 'my-test)
   \")

   Tests run fully isolated - no JVM clojure.test needed.
   Compatible with SCI's fork mechanism for sub-agent testing."
  [sci-ctx]
  ;; Add the entire clojure.test namespace from babashka (via our adapter)
  ;; Also add Java exception classes needed by clojure.test
  (sci/merge-opts sci-ctx
                  {:namespaces {'clojure.test sci-test/clojure-test-namespace}
                   :classes {'java.lang.Throwable Throwable
                             'java.lang.Exception Exception
                             'java.lang.AssertionError AssertionError
                             'java.lang.Error Error
                             'clojure.lang.ExceptionInfo clojure.lang.ExceptionInfo}}))

;; ---------------------------------------------------------------------------
;; Hiccup HTML Generation
;; ---------------------------------------------------------------------------

(defn add-hiccup-ns!
  "Add hiccup2.core namespace to SCI context.

   Exposes hiccup as runtime functions so agents can generate HTML:
   - (require '[h]) or (h/html [:div ...])
   - (h/raw \"<b>bold</b>\")

   The html macro is wrapped as a function using hiccup.compiler/render-html
   so it works at runtime in SCI (macros need compile-time expansion).

   Example in agent code:
     (h/html [:div {:class \"card\"} [:h1 \"Hello\"]])
     ;; => \"<div class=\\\"card\\\"><h1>Hello</h1></div>\""
  [sci-ctx]
  (let [html-fn (fn [& body]
                  (hu/raw-string
                    (apply str (map hc/render-html body))))]
    (sci/add-namespace! sci-ctx 'hiccup2.core
                        {'html html-fn
                         'raw  hiccup/raw})
    (sci/add-namespace! sci-ctx 'h
                        {'html html-fn
                         'raw  hiccup/raw})))

;; ---------------------------------------------------------------------------
;; Context Manipulation
;; ---------------------------------------------------------------------------

(defn add-binding!
  "Add a new binding to the SCI context.

   This mutates the context atom. Use for:
   - Injecting values from JVM into SCI
   - Setting up initial context state
   - Providing session-specific data

   Example:
   (add-binding! ctx 'session-id \"session-123\")"
  [sci-ctx sym val]
  (sci/eval-string* sci-ctx (str "(def " sym " " (pr-str val) ")")))

(defn add-namespace!
  "Add a namespace with bindings to the SCI context.

   Example:
   (add-namespace! ctx 'my.tools {'read-file read-file-fn
                                   'write-file write-file-fn})"
  [sci-ctx ns-sym bindings]
  (sci/add-namespace! sci-ctx ns-sym bindings))

;; ---------------------------------------------------------------------------
;; Namespace Loading/Reloading
;; ---------------------------------------------------------------------------

(defn load-source!
  "Load Clojure source code into SCI context.

   The source should contain a (ns ...) form. All definitions become
   available in the SCI context. Can be called multiple times to 'reload'
   a namespace - new definitions replace old ones.

   Returns the result of the last evaluated form."
  [sci-ctx source]
  (sci/eval-string* sci-ctx source))

(defn load-file!
  "Load a Clojure source file into SCI context.

   Reads the file and evaluates it. The file should contain a (ns ...) form.
   Can be called after file edits to reload the namespace.

   Example:
   (load-file! ctx \"src/myapp/tasks.clj\")
   ;; Later, after editing the file:
   (load-file! ctx \"src/myapp/tasks.clj\")  ;; reloads with new definitions"
  [sci-ctx file-path]
  (let [source (slurp file-path)]
    (load-source! sci-ctx source)))

(defn load-files!
  "Load multiple Clojure source files into SCI context.

   Files are loaded in order. Use for loading a set of namespaces
   that may depend on each other."
  [sci-ctx file-paths]
  (doseq [path file-paths]
    (load-file! sci-ctx path)))

(defn ns-publics-in-sci
  "Get public vars of a namespace in the SCI context.
   Returns a map of symbol -> var, like clojure.core/ns-publics."
  [sci-ctx ns-sym]
  (sci/eval-string* sci-ctx (str "(ns-publics '" ns-sym ")")))

;; ---------------------------------------------------------------------------
;; Future: Virtual Filesystem
;; ---------------------------------------------------------------------------

(comment
  "Virtual filesystem will be implemented when integrating with datahike.

   Design:
   - Each session has a virtual FS (map of paths -> content)
   - Stored in datahike for persistence
   - Later: backed by yggdrasil for CoW branching
   - Even later: can mount real folders via .git or podman

   Interface:
   (vfs/read-file ctx path)
   (vfs/write-file ctx path content)
   (vfs/list-files ctx pattern)

   This keeps sessions isolated and enables:
   - Safe concurrent agent execution
   - Rollback/replay
   - Diffing between session states")

;; ---------------------------------------------------------------------------
;; Future: Agent Forking
;; ---------------------------------------------------------------------------

(comment
  "Agents can fork their own contexts for sub-agents.

   Design:
   - Parent agent has SCI context A
   - Spawns sub-agent with (fork-context A) -> context B
   - Sub-agent works in isolation
   - Parent can merge results back

   Interface:
   (fork-context parent-ctx)
   (merge-context! parent-ctx child-ctx)

   This enables:
   - Speculative execution
   - Parallel problem solving
   - Hierarchical agent organization")

;; ---------------------------------------------------------------------------
;; REPL Testing
;; ---------------------------------------------------------------------------

(comment
  ;; Test basic evaluation
  (def ctx (fork-for-session))
  (eval-code ctx "(+ 1 2 3)")
  ;; => {:value 6, :stdout "", :stderr "", :success true}

  ;; Test isolation
  (def ctx1 (fork-for-session))
  (def ctx2 (fork-for-session))

  (eval-code ctx1 "(def x 10)")
  (eval-code ctx1 "x")
  ;; => {:value 10 ...}

  (eval-code ctx2 "x")
  ;; => {:success false, :error {:message "Could not resolve symbol: x" ...}}

  ;; Test stdout capture
  (eval-code ctx1 "(println \"hello\") (+ 1 2)")
  ;; => {:value 3, :stdout "hello\n", ...}

  ;; Test error handling
  (eval-code ctx1 "(/ 1 0)")
  ;; => {:success false, :error {:message "Divide by zero" ...}}

  ;; Test denied operations
  (eval-code ctx1 "(eval '(+ 1 2))")
  ;; => {:success false, :error {:message "eval is not allowed!" ...}}

  ;; Test stateful REPL
  (eval-code ctx1 "(defn square [x] (* x x))")
  (eval-code ctx1 "(square 5)")
  ;; => {:value 25 ...}
  )
