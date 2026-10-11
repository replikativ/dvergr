(ns dvergr.substrate.spin
  "Spindel helpers dvergr needs beyond spindel's facade.

   Loading this namespace closes a race in spindel's `cancel-spin!` (up to
   0.1.109 at least). It reads the spin's parked continuations before it
   caches the cancellation, while an await on another thread registers its
   continuation and then re-checks for a cancellation. An await that parks
   between that read and the cache write is missed by both: the spin stays
   parked, a later delivery is dropped as cancelled, and whoever derefs it
   waits forever (a cancelled dvergr job then never settled).

   Cancelling a second time, after the first has cached the cancellation,
   closes the gap: an await registered before the second read is found and
   rejected, one registered after it sees the cancellation in its own
   re-check. `cancel-spin!` cancels the children a spin awaits (and the
   children a combinator owns) through its var, so the var gets the repeating
   version: every level of a cancellation repeats, and spindel's own
   cancellations (race, timeout) too. A spin whose cancellation is not cached
   after the first pass (it completed in between) is left alone, and a
   repeated pass finds nothing left to do for a spin the first pass reached.

   spindel#116 reads the continuations after the cache write. On a spindel
   release with it, drop the patch and call `cancel-spin!` directly."
  (:require [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.spin.core :as spin-core]))

(defn- repeating-cancel
  "`cancel-spin!` that cancels again while the cancellation is cached."
  [cancel-spin!]
  (with-meta
    (fn cancel-spin-closing-the-park-race [spin]
      (cancel-spin! spin)
      (when (ec/spin-is-cancelled? (spin-core/spin-id spin))
        (cancel-spin! spin))
      nil)
    {::repeats true}))

(defonce ^:private patched
  (alter-var-root #'spin-core/cancel-spin!
                  (fn [f] (if (::repeats (meta f)) f (repeating-cancel f)))))

(defn cancel!
  "Cancel `spin` from any thread, also when it (or a child it awaits) is about
   to park on an await. Requires `*execution-context*` to be bound to the
   spin's context."
  [spin]
  (spin-core/cancel-spin! spin))
