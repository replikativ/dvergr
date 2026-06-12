(ns dvergr.sandbox
  "SCI sandbox for isolated evaluation contexts.

   Each session gets its own SCI context via fork. Future enhancements:
   - Agents can fork their own contexts for sub-agents
   - Virtual filesystem in memory/datahike
   - Integration with yggdrasil for CoW branching
   - Integration with spindel for async execution (CPS works through SCI)"
  (:require [sci.core :as sci]
            [sci.impl.utils :refer [clojure-core-ns]]
            [clojure.string :as str]
            [datahike.api :as dh]
            [org.replikativ.spindel.engine.core :as rtc]
            [org.replikativ.spindel.yggdrasil :as ygg]
            [org.replikativ.spindel.core :as sync]
            [dvergr.sci.clojure-test :as sci-test]
            [dvergr.sandbox.ns.intake :as ns-intake]
            [dvergr.sandbox.ns.codec :as ns-codec]
            [dvergr.sandbox.ns.dev :as ns-dev]
            [dvergr.sandbox.ns.data :as ns-data]
            [dvergr.sandbox.ns.kb :as ns-kb]
            [dvergr.sandbox.ns.room :as ns-room]
            [dvergr.sandbox.ns.mail :as ns-mail]
            [dvergr.sandbox.ns.datahike :as ns-datahike]
            [dvergr.sandbox.workspace :as workspace]
            [dvergr.sandbox.ns.agent :as ns-agent]
            [dvergr.sandbox.ns.io :as ns-io]
            [dvergr.system.db :as sdb])
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
   ;; Bare exception class names so idiomatic `(catch Throwable t …)` / `(catch
   ;; Error e …)` work — SCI provides bare Exception/RuntimeException by default
   ;; but NOT Throwable/Error, which agents reach for to catch broadly.
   'Throwable Throwable
   'Error Error
   'java.lang.Throwable Throwable
   'java.lang.Exception Exception
   'java.lang.AssertionError AssertionError
   'java.lang.Error Error
   'clojure.lang.ExceptionInfo clojure.lang.ExceptionInfo
   'java.time.Instant java.time.Instant
   'java.time.LocalDate java.time.LocalDate
   'java.time.LocalDateTime java.time.LocalDateTime
   'java.time.ZonedDateTime java.time.ZonedDateTime
   'java.time.ZoneOffset java.time.ZoneOffset
   'java.time.Duration java.time.Duration
   'java.time.format.DateTimeFormatter java.time.format.DateTimeFormatter
   'java.time.temporal.ChronoUnit java.time.temporal.ChronoUnit
   'java.util.UUID java.util.UUID
   'java.util.Date java.util.Date})

(def ^:private core-extras
  "clojure.core 1.11/1.12 additions SCI's default set lacks. Copied from the REAL
   vars (docs + arglists intact) via `sci/copy-var` — the babashka approach (see
   `babashka.impl.clojure.core/core-extras`) — so the sandbox's clojure.core is
   reasonably complete, not a hand-rolled subset. Merged into BOTH the base ctx and
   the spindel-backed agent ctx (the spindel SCI starts fresh, so it needs these
   re-merged like the classes + clojure.test runners). Host is Clojure 1.12, so all
   these resolve."
  {'random-uuid    (sci/copy-var random-uuid clojure-core-ns)
   'parse-uuid     (sci/copy-var parse-uuid clojure-core-ns)
   'parse-long     (sci/copy-var parse-long clojure-core-ns)
   'parse-double   (sci/copy-var parse-double clojure-core-ns)
   'parse-boolean  (sci/copy-var parse-boolean clojure-core-ns)
   'update-vals    (sci/copy-var update-vals clojure-core-ns)
   'update-keys    (sci/copy-var update-keys clojure-core-ns)
   'abs            (sci/copy-var abs clojure-core-ns)
   'NaN?           (sci/copy-var NaN? clojure-core-ns)
   'infinite?      (sci/copy-var infinite? clojure-core-ns)
   'iteration      (sci/copy-var iteration clojure-core-ns)
   'splitv-at      (sci/copy-var splitv-at clojure-core-ns)
   'partitionv     (sci/copy-var partitionv clojure-core-ns)
   'partitionv-all (sci/copy-var partitionv-all clojure-core-ns)
   'tap>           (sci/copy-var tap> clojure-core-ns)
   'add-tap        (sci/copy-var add-tap clojure-core-ns)
   'remove-tap     (sci/copy-var remove-tap clojure-core-ns)
   'Throwable->map (sci/copy-var Throwable->map clojure-core-ns)})

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

           ;; Deny dangerous operations. `require`/`load-string` stay allowed —
           ;; with the workspace `:load-fn` below they resolve agent code from the
           ;; sandbox's workspace dir only, and loaded source still resolves classes
           ;; solely from `base-classes` (the JVM-escape barrier), so it can't widen
           ;; reach. `eval`/`load-file` (arbitrary host path) remain denied.
          :deny '[eval load-file
                  clojure.core/eval
                  clojure.core/load-file]

           ;; Resolve `(require '[ns] :reload)` against the agent's workspace repo,
           ;; path-clamped (dvergr.sandbox.workspace). nil → not found → SCI throws.
          :load-fn workspace/load-fn

           ;; Clojure 1.11 core additions SCI's clojure.core lacks — agents reach for
           ;; `(random-uuid)`/`(parse-long …)` constantly, so provide equivalents
           ;; (pure, drop-in) rather than have them error with "Unable to resolve".
          :namespaces {'clojure.core core-extras}

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
                :interrupt-fn (make-resource-limits)
                ;; Pass the workspace :load-fn through to SCI's init so agents can
                ;; `(require '[my.ns])` their OWN files from the room repo. (Needs
                ;; spindel ≥ the version whose create-spin-macro-context accepts
                ;; :sci-opts and merges it into sci/init.)
                :sci-opts     {:load-fn workspace/load-fn}})))

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
     ;; Spindel creates a fresh SCI context without the base classes OR the 1.11
     ;; core shims — re-add both (else agents lose `(catch Throwable …)` +
     ;; `(random-uuid)`/`(parse-long …)` in the real sandbox they actually run in).
     (when spindel-ctx
       (sci/merge-opts forked {:classes    base-classes
                               :namespaces {'clojure.core core-extras}}))
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

;; ---------------------------------------------------------------------------
;; File Namespace (code persistence)
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; Enhanced Filesystem Namespace
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; Process Namespace (capability-gated subprocess execution)
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; Git Namespace (structured git operations for worktree agents)
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; LLM Namespace (cheap sub-calls)
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; Sandbox self-reflection — what namespaces/fns are available in the SCI ctx
;; ---------------------------------------------------------------------------

(def ^:private hidden-ns-prefixes
  ["clojure" "sci.impl" "sci.lang" "is.simm.partial-cps"
   "org.replikativ.spindel" "hiccup"])

(defn- plumbing-ns?
  "Internal SCI/spindel/host namespaces an agent shouldn't need to see — the
   reactive primitives stay reachable via the friendly `spindel.comb`/
   `spindel.sig`/`sync` aliases, which are NOT hidden. `intake.bash` is hidden
   because the same fns are surfaced under the shorter `bash`."
  [s]
  (or (#{"user" "h" "intake.bash"} s)
      ;; clojure.data.xml is a library WE mount (the hardened parser) — keep it
      ;; visible even though it's clojure-prefixed.
      (and (not (#{"clojure.data.xml"} s))
           (some #(str/starts-with? s %) hidden-ns-prefixes))))

(defn- interesting-ns?
  "Keep the INTEGRATED namespaces (dvergr.intake.*, d, dvergr.room, llm, scheduler,
   room, fs, git, bash, …) plus anything the agent defined itself this session;
   drop stock Clojure + SCI/spindel plumbing."
  [ns-sym]
  (not (plumbing-ns? (str ns-sym))))

;; Curated [purpose example] per integrated namespace. The fn LIST stays
;; auto-derived (drift-free); this supplies only the "what is it for + one real
;; call" that the erased fn metadata (raw injected fns carry no :doc/:arglists)
;; can't. Examples are checked against the live fn lists.
;; The borrowed surfaces are the REAL babashka/clojure namespaces (sandboxed) so
;; the model's training transfers; require + alias them as you would in babashka.
(def ^:private ns-guide
  {"babashka.fs" ["filesystem — the real babashka.fs API, path-clamped to the workspace. Content I/O is slurp/spit."
                  "(require '[babashka.fs :as fs]) (fs/list-dir \"src\")  (fs/glob \".\" \"**/*.clj\")  (slurp \"f.clj\")"]
   "babashka.http-client" ["outbound HTTP (domain-gated). Body is a RAW string — parse it yourself."
                           "(require '[babashka.http-client :as http]) (-> (http/get url) :body (cheshire.core/parse-string true))"]
   "babashka.process" ["shell, muschel-backed + jailed (pipes/builtins/redirects parsed in the string)."
                       "(require '[babashka.process :as p]) (p/shell \"git log --oneline -5\")  ;=> {:exit :out :err}"]
   "cheshire.core" ["JSON (the real cheshire). parse-string defaults string keys; pass true for keywords."
                    "(cheshire.core/parse-string s true)   (cheshire.core/generate-string m)"]
   "clojure.data.xml" ["XML → {:tag :attrs :content} (our hardened, XXE-safe parser under the standard name)."
                       "(require '[clojure.data.xml :as xml]) (xml/parse-str s)"]
   "datahike.api" ["the REAL datahike API on any conn (q/pull/transact/create-database)."
                   "(require '[datahike.api :as d]) (d/q '[:find ?t :where [?e :entity/title ?t]] @dvergr.room/*kb*)"]
   "dvergr.codec" ["base64 / url / html helpers (dvergr extras — no babashka equivalent)."
                   "(dvergr.codec/base64-encode s)  (dvergr.codec/url-encode s)  (dvergr.codec/strip-tags html)"]
   "dvergr.room" ["YOUR room: *kb*/*room* conns, databases, fork!/merge!, post to other rooms, + queries"
                  "(dvergr.room/kb-search \"datahike\")  (dvergr.room/post! \"research\" \"found a lead\")"]
   "dvergr.mail" ["YOUR room's attached mailbox conn (`*inbox*`, fork-aware; nil if none). Read helpers are seed source."
                  "(require '[dvergr.mail.inbox]) (dvergr.mail.inbox/recent :limit 10)"]
   "dvergr.intake" ["read-only world sources as require-able SOURCE (dvergr/intake/*.clj — read/extend): hn github sec stock rss arxiv wikidata …"
                    "(require '[dvergr.intake.hn]) (dvergr.intake.hn/search-stories \"clojure\")"]
   "llm"      ["cheap one-shot LLM calls (extract/summarize)"
               "(llm/summarize text {:max-tokens 300})"]
   "git"      ["structured git for the workspace (dvergr convenience; or `(babashka.process/shell \"git …\")`)"
               "(git/status)   (git/diff)"]
   "env"      ["read sandbox env vars (secrets redacted)"
               "(env/get \"PATH\")"]
   "dvergr.scheduler" ["schedule recurring / one-off work — this IS your calendar"
                       "(dvergr.scheduler/at \"2026-06-15T09:00\" :var \"Standup\") (dvergr.scheduler/list)"]
   "dvergr.tasks"    ["the shared task ledger — list/accept/complete work items"
                      "(dvergr.tasks/list)   (dvergr.tasks/complete! id)"]
   "dvergr.agents"   ["directory of agents (read-only): who exists / is online"
                      "(dvergr.agents/list)   (dvergr.agents/online? :var)"]
   "dvergr.actors"   ["mutate the roster — spawn sub-agents / humans, assign skills"
                      "(dvergr.actors/spawn-agent! {:prompt \"…\" :budget 0.10})"]
   "dvergr.skills"   ["reusable skills you can find + dispatch"
                      "(dvergr.skills/find \"draft a brief\")"]})

(def ^:private guide-order
  ["babashka.fs" "babashka.http-client" "babashka.process" "cheshire.core" "clojure.data.xml"
   "datahike.api" "dvergr.room" "dvergr.mail" "dvergr.intake" "dvergr.codec" "git" "env" "llm"
   "dvergr.scheduler" "dvergr.tasks" "dvergr.agents" "dvergr.actors" "dvergr.skills"])

(defn ns-overview-data
  "Introspect the SCI ctx's injected namespaces → sorted seq of
   {:ns \"intake.hn\" :fns [\"search\" \"top\"]}. Drift-free: it reflects exactly
   what `setup-agent-namespaces!` injected (plus the agent's own defs), not a
   hand-maintained list."
  [sci-ctx]
  (->> (:namespaces @(:env sci-ctx))
       (filter (fn [[ns-sym _]] (interesting-ns? ns-sym)))
       (keep (fn [[ns-sym vars]]
               (let [fns (->> (keys vars) (filter symbol?) (map str) sort vec)]
                 (when (seq fns) {:ns (str ns-sym) :fns fns}))))
       (sort-by :ns)
       vec))

(defn ns-overview-md
  "A markdown overview of the sandbox: curated Core namespaces (purpose + a real
   call + fn list), the dvergr.intake.* family, everything else, and the agent's own
   defs — so a code-first agent can see WHAT exists and HOW to call it."
  [sci-ctx]
  (let [data    (ns-overview-data sci-ctx)
        by-ns   (into {} (map (juxt :ns identity)) data)
        intake? (fn [n] (str/starts-with? n "intake."))
        guided  (set guide-order)
        others  (->> data
                     (remove (fn [{:keys [ns]}] (or (guided ns) (intake? ns))))
                     (sort-by :ns))
        core-ln (fn [n]
                  (let [[purpose eg] (ns-guide n)]
                    (str "- `" n "` — " purpose "\n"
                         "    e.g. " eg "\n"
                         "    fns: " (str/join " " (:fns (by-ns n))))))]
    (str
     "# clojure_eval sandbox\n\n"
     "Write Clojure that calls these integrated namespaces and compose/transform "
     "in ONE eval — state persists across your evals. Call fns fully-qualified, "
     "no `require` needed: `(ns/fn …)`.\n\n"
     "## Core\n"
     (str/join "\n" (for [n guide-order :when (by-ns n)] (core-ln n)))
     "\n\n## dvergr.intake.* — read-only external sources\n"
     (str/join "\n"
               (for [{:keys [ns fns]} data :when (intake? ns)]
                 (str "- `" ns "`: " (str/join " " fns))))
     "\n\n## More\n"
     (str/join "\n"
               (for [{:keys [ns fns]} others]
                 (str "- `" ns "`: " (str/join " " fns))))
     "\n\nReactive primitives live in `spindel.comb` `spindel.sig` `sync`. "
     "Your own `(def …)`/`(defn …)` persist across evals this session and appear "
     "here under their namespace — that's how you grow your own helpers/tools. "
     "Use `(sandbox/doc 'some-ns)` for one namespace at a time.")))

(defn ns-doc-md
  "Focused help for ONE namespace (token-cheaper than the whole overview)."
  [sci-ctx ns-name]
  (let [n     (name ns-name)
        entry (first (filter #(= n (:ns %)) (ns-overview-data sci-ctx)))]
    (if-not entry
      (str "No sandbox namespace `" n "`. Run `(sandbox/overview)` for the list.")
      (let [[purpose eg] (ns-guide n)]
        (str "`" n "`" (when purpose (str " — " purpose)) "\n"
             (when eg (str "e.g. " eg "\n"))
             "fns: " (str/join " " (:fns entry)))))))

(def sandbox-prompt-pointer
  "System-prompt block teaching the agent how to use its `clojure_eval` sandbox
   (a pointer + the essentials, not a full namespace dump — keeps tokens down)."
  (str "## Your sandbox (`clojure_eval`)\n\n"
       "`clojure_eval` is your main tool: it runs Clojure in a sandbox preloaded "
       "with the libraries you already know, mounted under their REAL names (sandboxed): "
       "`babashka.fs` (+ `slurp`/`spit`), `babashka.http-client`, `babashka.process` (shell), "
       "`cheshire.core` (JSON), `clojure.data.xml` (hardened), `datahike.api`; plus dvergr's "
       "own — `dvergr.room` (your room's data + ops), `dvergr.mail`, `dvergr.intake.*` "
       "(read/extend the SOURCE), `dvergr.codec`, `dvergr.scheduler`/`dvergr.tasks`/"
       "`dvergr.agents`/`dvergr.actors`/`dvergr.skills`, `llm`, `git`, `env`. Use full names "
       "or alias as in babashka — `(require '[babashka.fs :as fs])`. Prefer composing "
       "Clojure that calls these — chain + transform results in one eval, state "
       "persists across evals — over asking for separate tools. Your own `(defn …)` "
       "persist too, so you can build helpers as you go.\n\n"
       "**Your databases.** Your room owns its data — NOT a shared global DB. "
       "`dvergr.room/*kb*` is your knowledge base, `dvergr.room/*room*` your room's "
       "own datahike (messages/state); query them with ordinary datahike, e.g. "
       "`(d/q '[:find ?t :where [?e :entity/title ?t]] @dvergr.room/*kb*)`. "
       "`(dvergr.room/databases)` lists them; `(dvergr.room/db \"name\")` connects "
       "one by name. Make a NEW room-owned database with real datahike — "
       "`(d/create-database {:store {:backend :file :id \"notes\"}})` then "
       "`(d/connect {:store {:id \"notes\"}})` — it's confined to your room and "
       "forks/merges/discards with it. (`:backend :mem` = throwaway scratch.) "
       "Everything you write here is fork-isolated and shows in the merge diff.\n\n"
       "**Discover at runtime:** `(sandbox/overview)` lists every namespace with a "
       "purpose + example + its fns; `(sandbox/doc 'babashka.fs)` zooms into one; "
       "`(dvergr.shell/builtins)` lists the muschel shell's built-in commands.\n\n"
       "**Your workspace is the room** — a persistent project with its OWN git repo "
       "(your code), knowledge base, and schedules. Read/write code with `fs`/`git`/"
       "`bash` (changes live in the room's repo); recall/save facts with the "
       "knowledge tools; automate recurring work with `dvergr.scheduler` "
       "(`(dvergr.scheduler/interval ms :agent \"task\")`, `(dvergr.scheduler/at \"09:00\" …)`). "
       "For a substantial or risky change, FORK first: `(dvergr.room/fork! room)` "
       "gives an isolated branch (own git + DB) you can experiment in freely; when "
       "it's good `(dvergr.room/merge! parent fork)` collapses your work back (the "
       "merge surfaces a git + database diff for review), or `(dvergr.room/discard! "
       "fork)` throws it away. This is your safe edit→test→merge loop.\n\n"
       "**Shell:** `(bash/run \"…\")` is a muschel shell (the `shell` tool is the "
       "same thing). Your workspace is the PROJECT mounted at `/`. **Use relative "
       "paths** (`(bash/run \"ls\")`, `(bash/run \"cat src/foo.clj\")`) — absolute "
       "system paths like `/etc` don't exist. Built-in POSIX tools include ls cat "
       "grep sed awk find sort uniq cut tr jq xargs wc head tail diff git — no "
       "external binary needed. Destructive ops (rm -rf, sudo, git push --force) "
       "are blocked by design; that's expected, not a failure.\n\n"
       "**Knowledge:** recall with `knowledge_search`/`(search/find …)` BEFORE "
       "researching, and save findings with `knowledge_add` so they persist past "
       "this session (sandbox `(defn …)` do not)."))

(defn- add-reflection-ns!
  "Expose `sandbox/*` introspection INSIDE the sandbox so a code-first agent can
   discover what's available at runtime (the closure reads the live ctx env, so
   it sees every namespace injected by `setup-agent-namespaces!`)."
  [sci-ctx]
  (sci/add-namespace! sci-ctx 'sandbox
                      {'namespaces (fn [] (ns-overview-data sci-ctx))
                       'overview   (fn [] (ns-overview-md sci-ctx))
                       'help       (fn [] (ns-overview-md sci-ctx))
                       'doc        (fn [ns-name] (ns-doc-md sci-ctx ns-name))}))

(defn setup-agent-namespaces!
  "Wire execution-context-aware namespaces into a SCI context.

   Call after fork-for-session, passing the spindel execution context
   (which for worker agents is the forked context with isolated systems).

   Sets up:
   - d/q d/pull d/entity ...         - datahike read (the real datahike.api)
   - dh/transact! dh/retract!        - datahike write (same forked conn)
   - sync/deferred sync/mailbox      - spindel sync primitives
   - intake.hn, intake.web, etc.     - intake library functions
   - fs/read, fs/write, fs/ls, ...   - rich filesystem (path-safe, audited)
   - file/read, file/write, etc.     - legacy file I/O (kept for compat)
   - proc/run, proc/run!, proc/lines - capability-gated process execution
   - git/status, git/log, git/diff, git/add, git/commit - structured git (audited)
   - llm/call, llm/summarize         - cheap one-shot LLM calls
   - dvergr.room/kb-find, kb-search, *kb* - the room's knowledge base (datalog via `d`)

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
  [sci-ctx spindel-ctx & {:keys [base-path proc-allow allowed-http-domains room-conn kb-conn room-id]
                          :or   {proc-allow #{}}}]
  (let [audit-log  (make-audit-log)
        ;; The agent's PROJECT is its room's OWN git repo (a per-room yggdrasil git
        ;; system), not the daemon's cwd. Default `fs`/`git`/`proc` (and so the
        ;; `require` workspace) to that worktree — resolved fork-aware under the
        ;; room ctx — so write/update/require/commit happen in the room's repo,
        ;; isolated from the dvergr source tree. A fork's ctx yields the fork's
        ;; branched worktree automatically. When there's NO repo system (room-less
        ;; ctxs: sidecar/tests, or the bare root room) we fall back to the isolated
        ;; `.dvergr/workspace` clone — NEVER the host cwd / dvergr source tree.
        cwd        (or base-path
                       (binding [rtc/*execution-context* spindel-ctx]
                         (try ((requiring-resolve 'dvergr.substrate.git/current-worktree-path))
                              (catch Throwable _ nil)))
                       ((requiring-resolve 'dvergr.substrate.git/safe-workspace-root)))]
    (binding [rtc/*execution-context* spindel-ctx]
      ;; The agent's DATA lives in ITS room (fork-aware) — `*room*` = the room's own
      ;; datahike (messages store), `*kb*` = its knowledge base — never system-db.
      ;; system-db (`sys-conn`) backs ONLY the global registry surface: the narrow
      ;; actor/skill/task API. Room-less ctxs (sidecar/tests) have no room → the generic `dh`
      ;; falls back to sys-conn so it still has a queryable db (flagged: give them a
      ;; scratch db so even room-less never writes the registry).
      (let [sys-conn (sdb/get-conn)]
        (ns-datahike/add-datahike-ns! sci-ctx room-id spindel-ctx) ; the ONE datahike surface: faithful `d`/`datahike.api`
        ;; ONE `dvergr.room` (+ legacy `room` alias): the Room ops MERGED with the
        ;; room's DB surface (*room*/*kb*/databases/db + queries). The KB is reached
        ;; via `dvergr.room/*kb*` + `kb-find`/`kb-search` + `d` — no separate `entity`
        ;; namespace (dropped as redundant; a global entity CRM can return later).
        (ns-room/add-room-ns! sci-ctx room-conn kb-conn room-id spindel-ctx)
        ;; dvergr.mail/*inbox* — the room's attached mailbox conn (fork-aware),
        ;; nil when no mailbox attached. Read helpers are seed source (dvergr/mail/).
        (ns-mail/add-mail-ns! sci-ctx)
        (ns-agent/add-actors-ns! sci-ctx sys-conn)
        (ns-agent/add-skills-ns! sci-ctx sys-conn)
        (ns-agent/add-tasks-ns! sci-ctx sys-conn)))
    (ns-agent/add-agents-ns! sci-ctx)
    (ns-data/add-spindel-extras-ns! sci-ctx spindel-ctx)
    (ns-codec/add-codec-namespaces! sci-ctx)   ; cheshire.core / clojure.data.xml / dvergr.codec
    (ns-intake/add-intake-namespaces! sci-ctx)
    (ns-io/add-fs-ns!   sci-ctx :base-path cwd :audit-log audit-log)
    ;; (proc folded into the muschel-backed babashka.process — add-bash-ns! in turn.clj)
    (ns-io/add-git-ns!  sci-ctx :base-path cwd :audit-log audit-log)
    (ns-kb/add-llm-ns!  sci-ctx)
    ;; Boundary secret injection (doc/boundary-secret-injection.md): build the
    ;; host-side secret registry from config `:secrets` (resolved against the host
    ;; env), and share it between `env` (returns placeholders) and `http`
    ;; (substitutes at egress + scrubs the response). requiring-resolve to avoid a
    ;; sandbox→config compile dep; nil specs ⇒ empty registry ⇒ no-op.
    (let [secret-specs (try ((requiring-resolve 'dvergr.substrate.config/secret-specs))
                            (catch Throwable _ nil))
          sandbox-env  (try ((requiring-resolve 'dvergr.substrate.config/sandbox-env))
                            (catch Throwable _ nil))
          secrets      (ns-io/build-secret-registry secret-specs)]
      ;; :user-config = non-secret config values returned verbatim (identifiers,
      ;; site URLs); :secrets = sensitive values returned as placeholders.
      (ns-io/add-env-ns!  sci-ctx :user-config (atom (or sandbox-env {})) :secrets secrets)
      (ns-io/add-http-ns! sci-ctx :audit-log audit-log :allowed-domains allowed-http-domains
                          :secrets secrets))
    (ns-agent/add-scheduler-ns! sci-ctx)
    ;; Default coder kit: discovery, dep-add, HTML, tests — these are
    ;; safe and useful for any coding agent regardless of role. Each
    ;; is still individually callable if a caller wants just one.
    (ns-dev/add-clojure-repl-ns! sci-ctx)
    (ns-dev/add-clojure-repl-deps-ns! sci-ctx)
    (ns-dev/add-hiccup-ns! sci-ctx)
    ;; Self-reflection LAST, so (sandbox/overview) sees every ns injected above.
    (add-reflection-ns! sci-ctx)
    audit-log))

;; ---------------------------------------------------------------------------
;; clojure.test Integration (from Babashka)
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

(defn sandbox-status
  "Read-only snapshot of a session SCI sandbox — for the UI/`/sandbox` command.
   Returns `{:vars [names…] :namespaces [names…] :ns-count n}`:
     :vars       — the agent's OWN defs this session (the `user` ns), i.e. the
                   helpers it built; the dynamic, interesting part.
     :namespaces — the injected namespaces it can call (dh, room, bash, dvergr.intake.*,
                   …), drift-free from `ns-overview-data`.
   Pure inspection — does not mutate the sandbox. nil sci-ctx → nil."
  [sci-ctx]
  (when sci-ctx
    {:vars       (try (->> (ns-publics-in-sci sci-ctx 'user) keys (map str) sort vec)
                      (catch Throwable _ []))
     :namespaces (try (->> (ns-overview-data sci-ctx) (map :ns) (remove #{"user"}) sort vec)
                      (catch Throwable _ []))
     :ns-count   (try (count (remove #(= "user" (:ns %)) (ns-overview-data sci-ctx)))
                      (catch Throwable _ 0))}))

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
