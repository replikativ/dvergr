(ns dvergr.sandbox.ns.data
  "SCI injectors — datahike read/write/diff, spindel sync/combinators/signals,
   and probabilistic inference. Split out of dvergr.sandbox (Phase 4)."
  (:require [sci.core :as sci]
            [datahike.api :as dh]
            [org.replikativ.spindel.engine.core :as rtc]
            [org.replikativ.spindel.core :as sync]))

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
