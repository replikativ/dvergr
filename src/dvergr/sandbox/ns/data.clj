(ns dvergr.sandbox.ns.data
  "SCI injectors — datahike read/write/diff, spindel sync/combinators/signals,
   and probabilistic inference. Split out of dvergr.sandbox (Phase 4)."
  (:require [sci.core :as sci]
            [is.simm.partial-cps.sequence :as aseq]
            [datahike.api :as dh]
            [dvergr.sandbox.ns.doc :as doc]
            [dvergr.sandbox.work :as sandbox-work]
            [org.replikativ.spindel.engine.core :as rtc]
            [org.replikativ.spindel.core :as sync]))

(defn add-spindel-extras-ns!
  "Expose spindel combinators, sync primitives, and signals to SCI.

   Replaces the limited add-spindel-sync-ns! with a fuller API:
   - spindel.comb — parallel, race, timeout, sleep
   - sync         — deferred, deliver!, mailbox, post!
   - spindel.sig  — signal (for external world boundary)
   - spindel.work — structured latest/serial/busy/parallel admission

   These are safe: they only coordinate within the SCI context."
  ([sci-ctx spindel-ctx]
   (add-spindel-extras-ns! sci-ctx spindel-ctx {}))
  ([sci-ctx spindel-ctx {:keys [room-id ceiling]}]
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
                           (doc/with-docs
                             {'parallel @(ns-resolve comb-ns 'parallel)
                              'race     @(ns-resolve comb-ns 'race)
                              'timeout  @(ns-resolve comb-ns 'timeout)
                              'sleep    @(ns-resolve comb-ns 'sleep)}
                             '{parallel [([& spins]) "Run Spins concurrently and produce their values in input order. Cancelling the composition cancels its branches."]
                               race     [([& spins]) "Produce the first Spin value and cancel losing branches. For hired Runs, pass dvergr.agent/owned-result-spin when branch cancellation must cancel the Run; passive result-spin deliberately leaves shared work alive."]
                               timeout  [([spin timeout-ms] [spin timeout-ms timeout-value]) "Race a Spin against a timer and cancel the timed-out branch."]
                               sleep    [([milliseconds]) "Return a Spin that completes after the given duration without blocking the engine thread."]}))
      ;; Structured work admission. `task` is defined below inside SCI because
      ;; it must run partial-cps expansion in the sandbox's own symbol table;
      ;; copying the host macro would resolve sandbox vars against the host.
       (sci/add-namespace! sci-ctx 'spindel.work
                           (doc/with-docs
                             {'latest       (fn
                                              ([work-fn]
                                               (sandbox-work/create! room-id spindel-ctx ceiling :latest {} work-fn))
                                              ([opts work-fn]
                                               (sandbox-work/create! room-id spindel-ctx ceiling :latest opts work-fn)))
                              'serial       (fn
                                              ([work-fn]
                                               (sandbox-work/create! room-id spindel-ctx ceiling :serial {} work-fn))
                                              ([opts work-fn]
                                               (sandbox-work/create! room-id spindel-ctx ceiling :serial opts work-fn)))
                              'busy         (fn
                                              ([work-fn]
                                               (sandbox-work/create! room-id spindel-ctx ceiling :busy {} work-fn))
                                              ([opts work-fn]
                                               (sandbox-work/create! room-id spindel-ctx ceiling :busy opts work-fn)))
                              'parallel     (fn
                                              ([work-fn]
                                               (sandbox-work/create! room-id spindel-ctx ceiling :parallel {} work-fn))
                                              ([opts work-fn]
                                               (sandbox-work/create! room-id spindel-ctx ceiling :parallel opts work-fn)))
                              'controller   (fn
                                              ([work-fn]
                                               (sandbox-work/create! room-id spindel-ctx ceiling :serial {} work-fn))
                                              ([opts work-fn]
                                               (sandbox-work/create! room-id spindel-ctx ceiling
                                                                     (:strategy opts :serial)
                                                                     (dissoc opts :strategy)
                                                                     work-fn)))
                              'submit!      sandbox-work/submit!
                              'events       sandbox-work/events!
                              'next-event   aseq/anext
                              'untap!       sandbox-work/untap!
                              'snapshot     sandbox-work/snapshot
                              'completion   sandbox-work/completion
                              'close!       sandbox-work/close!
                              'cancel!      sandbox-work/cancel!}
                             '{latest      [([work-fn] [opts work-fn]) "Create switch-to-latest admission. A replacement starts only after superseded work has quiesced."]
                               serial      [([work-fn] [opts work-fn]) "Create FIFO admission with bounded waiting capacity."]
                               busy        [([work-fn] [opts work-fn]) "Create exhaust/busy admission: suppress input while work is active."]
                               parallel    [([work-fn] [opts work-fn]) "Create bounded parallel admission; set :concurrency in opts."]
                               controller  [([work-fn] [opts work-fn]) "Create admission with explicit :strategy (:latest, :serial, :busy, or :parallel)."]
                               submit!     [([controller value] [controller id value]) "Submit a value without blocking; returns its correlation id or nil when ingress is closed/full."]
                               events      [([controller]) "Open an independent hot event stream for later admission and completion events."]
                               next-event  [([event-source]) "Return an awaitable for the next [event remaining-source] pair."]
                               untap!      [([controller event-source]) "Detach an abandoned event stream."]
                               snapshot    [([controller]) "Return fork-local active, queued, and lifecycle state without live handles."]
                               completion  [([controller]) "Return a passive awaitable that joins controller quiescence after close!/cancel!."]
                               close!      [([controller]) "Stop admission, drain accepted work, and close."]
                               cancel!     [([controller]) "Stop admission and cooperatively cancel queued and active owned work."]}))
       (sci/eval-string*
        sci-ctx
        "(ns spindel.work (:require [is.simm.partial-cps.async :as pcps-async]))
        (defmacro task
          \"Create reusable one-shot CPS work. The controller gives every submission fresh Spin identity and ownership.\"
          [& body]
          `(pcps-async/async ~@body))")
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
                                          (signal-ref-ctor id (clear-deltas initial-value))))}))))))

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
        db-fn (fn [] @db-atom)
        ;; Fulltext memory search via datahike's native scriptum secondary index
        ;; (dvergr.search.secondary/search). It's just a fn returning BM25-RANKED
        ;; [[entity-id score] …], so the agent calls it INSIDE a datalog clause —
        ;; fulltext ranking composed with structured constraints in one q:
        ;;   (dh/q '[:find ?c ?score
        ;;           :in $ ?ft
        ;;           :where [(?ft :room/fulltext "gc reap") [[?e ?score]]]
        ;;                  [?e :message/content ?c]]
        ;;         (dh/db) dh/search)
        ;; Resolve lazily + gracefully: the scriptum secondary-index adapter
        ;; lives in datahike's src-secondary root, which a released build may
        ;; not ship. The sandbox must still build; dh/search errors clearly only
        ;; if actually called without support.
        secondary  (try (requiring-resolve 'dvergr.search.secondary/search)
                        (catch Throwable _ nil))
        search-fn  (fn [ident query & [opts]]
                     (if secondary
                       (secondary @db-atom ident query (or opts {}))
                       (throw (ex-info "fulltext search unavailable — datahike secondary index (:scriptum) not on classpath"
                                       {:ident ident}))))]

    (sci/add-namespace! sci-ctx 'dh
                        {'q q-fn
                         'pull pull-fn
                         'pull-many pull-many-fn
                         'entity entity-fn
                         'datoms datoms-fn
                         'schema schema-fn
                         'search search-fn
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
