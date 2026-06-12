(ns dvergr.runtime.clock
  "The daemon's single reactive clock — the ONE timer that drives all
   time-based reactivity (per-room schedulers, future time-driven views).

   Time is the external-world boundary, so it lives in exactly one signal
   advanced by exactly one heartbeat; everything else `track`s it and reacts.
   This replaces N independent `comb/sleep` loops (one per schedule) with a
   single source of time-impurity.

   The signal holds the current wall-clock minute as a `java.util.Date`
   truncated to :00 seconds, so its value changes once per minute — trackers
   (e.g. `dvergr.rooms.scheduler`) re-run on the minute boundary, not on every
   internal tick. Minute resolution is the right granularity for agent cron
   (\"every day at 09:00\", \"every 15 min\"); these fire LLM turns, not μs jobs.

   The signal is Tier-1 shared state (see `dvergr.runtime.ctx`): created on the
   ROOT ctx so every per-room scheduler spin (also rooted) sees it. Reads fall
   through child→parent, so a forked room's scheduler still tracks the one clock."
  (:refer-clojure :exclude [await])
  (:require [dvergr.runtime.ctx :as rctx]
            [org.replikativ.spindel.signal :as sig]
            [org.replikativ.spindel.core :as sp :refer [spin await]]
            [org.replikativ.spindel.spin.combinators :as comb]
            [org.replikativ.spindel.engine.core :as ec]))

(def ^:private tick-ms 60000)

(defn minute-now
  "The current instant truncated to the minute (seconds/millis zeroed)."
  ^java.util.Date []
  (java.util.Date. (* 60000 (quot (System/currentTimeMillis) 60000))))

;; {:signal SignalRef :running? bool}. The signal survives stop!/start! so a
;; same-process restart reuses it (trackers keep their reference).
(defonce ^:private clock-state (atom {:signal nil :running? false}))

(defn clock-signal
  "The daemon clock signal — a `java.util.Date` at minute resolution. Created
   lazily on the root ctx on first use; one per process. `track` it inside a
   spin to react each minute. Requires a bound execution context (to anchor at
   root on first creation)."
  []
  (or (:signal @clock-state)
      (binding [ec/*execution-context* (rctx/current-root)]
        (let [s (sig/signal (minute-now))]
          (swap! clock-state assoc :signal s)
          s))))

(defn start!
  "Start the single heartbeat that advances the clock once per minute.
   Idempotent. Must be called with a (root) execution context bound."
  []
  (when-not (:running? @clock-state)
    (let [s (clock-signal)]
      (swap! clock-state assoc :running? true)
      (binding [ec/*execution-context* (rctx/current-root)]
        (sp/spawn!
         (spin
            ;; Error-isolated, long-lived: sleep → advance → repeat while running.
            ;; Exits cleanly on the next wake after `stop!`.
          (loop []
            (await (comb/sleep tick-ms))
            (when (:running? @clock-state)
              (try (reset! s (minute-now)) (catch Throwable _ nil))
              (recur)))))))
    :started))

(defn stop!
  "Stop the heartbeat (the loop exits on its next wake). Keeps the signal."
  []
  (swap! clock-state assoc :running? false)
  :stopped)

(defn tick!
  "Advance the clock to the current minute immediately. For tests / manual
   nudges; the heartbeat does this once a minute on its own."
  []
  (binding [ec/*execution-context* (rctx/current-root)]
    (reset! (clock-signal) (minute-now)))
  nil)

(defn set-now!
  "Force the clock to a specific `java.util.Date` (tests: drive the due-check to
   an arbitrary minute without waiting). Returns the value set."
  [^java.util.Date d]
  (binding [ec/*execution-context* (rctx/current-root)]
    (reset! (clock-signal) d)))
