(ns dvergr.rooms.facts
  "One change feed per room: a signal bumped after every durable change its
   store records (a message, a Run, a tool call, an Attempt, a Scorecard, a
   ledger entry, a merge's collapse).

   The store is the room's boundary to the outside world, so this is a signal:
   views of the room's facts are spins that `track` it and re-read the store —
   progress, the Run tree, a dashboard — and so are consumers outside the FRP
   world (MCP resource notifications, the simmis relay) through `watch!`. The
   feed says only that something changed; what changed is always read from the
   facts, so a view is a function of the store and never of the events.

   Like the room message signal (`dvergr.rooms.messages`) it lives in the root
   context, is created when the room registers and dropped when it goes."
  (:require [dvergr.room.registry :as rreg]
            [dvergr.room.store :as store]
            [dvergr.runtime.ctx :as rctx]
            [org.replikativ.spindel.core :as sp]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.incremental.interval :as iv]
            [org.replikativ.spindel.signal :as sig]
            [org.replikativ.spindel.spin.cps :refer [spin]]
            [org.replikativ.spindel.effects.track :refer [track]]
            [taoensso.telemere :as tel]))

;; room-id -> {:signal SignalRef :store store :key listener-key :ctx root-ctx
;;             :watchers {key {:running? atom}}}
(defonce ^:private feeds (atom {}))

(defn- feed-key [room-id] [::facts room-id])

(defn facts-signal
  "Get-or-create `room`'s change signal, `{:version n}`: `n` grows with every
   durable change of its store. nil for a room without a store that reports
   changes."
  [room]
  (or (:signal (get @feeds (:id room)))
      (let [st (:store room)]
        (when (satisfies? store/PFactFeed st)
          (let [ctx (rctx/root-ctx (:ctx room))
                signal (binding [ec/*execution-context* ctx] (sig/signal {:version 0}))
                k (feed-key (:id room))
                [old _] (swap-vals! feeds (fn [m]
                                            (if (get m (:id room))
                                              m
                                              (assoc m (:id room) {:signal signal :store st
                                                                   :key k :ctx ctx :watchers {}}))))]
            (if-let [existing (get old (:id room))]
              (:signal existing)
              (do (store/-listen! st k
                                  (fn []
                                    (try
                                      (binding [ec/*execution-context* ctx]
                                        (swap! signal update :version inc))
                                      (catch Throwable t
                                        (tel/log! {:level :warn :id ::bump-failed
                                                   :data {:room (:id room) :error (ex-message t)}}
                                                  "Room facts signal could not be bumped")))))
                  signal)))))))

(defn view
  "A Spin of `(f room)`, re-run after every durable change of `room`: a view of
   its facts (for example `(view room experiment/progress)`). Track it from
   another spin, or deref it at a boundary for the current value."
  [room f]
  (when-let [signal (facts-signal room)]
    (binding [ec/*execution-context* (rctx/root-ctx (:ctx room))]
      (spin
       (iv/get-new (track signal))
       (f room)))))

(defn watch!
  "Call `(f)` after durable changes of `room`, at most every `min-interval-ms`
   (a change inside the interval is reported at its end, so the last one is
   never lost). For consumers outside spins: MCP notifications, relays. `key`
   is idempotent; `unwatch!` stops it."
  [room key f & {:keys [min-interval-ms] :or {min-interval-ms 250}}]
  (when-let [signal (facts-signal room)]
    (let [running? (atom true)
          pending? (atom false)
          last-at (atom 0)
          fire! (fn fire! []
                  (when @running?
                    (let [wait (- (+ @last-at min-interval-ms) (System/currentTimeMillis))]
                      (if (pos? wait)
                        (when (compare-and-set! pending? false true)
                          (future (Thread/sleep wait)
                                  (reset! pending? false)
                                  (fire!)))
                        (do (reset! last-at (System/currentTimeMillis))
                            ;; Off the drain: a slow consumer must not hold it.
                            (future
                              (try (f)
                                   (catch Throwable t
                                     (tel/log! {:level :warn :id ::watcher-failed
                                                :data {:room (:id room) :error (ex-message t)}}
                                               "Room facts watcher failed")))))))))]
      (swap! feeds assoc-in [(:id room) :watchers key] {:running? running?})
      (binding [ec/*execution-context* (rctx/root-ctx (:ctx room))]
        (let [subscribed-at (:version @signal)]
          (sp/spawn!
           (spin
            (let [{:keys [version]} (iv/get-new (track signal))]
              ;; The first run is the subscription itself, not a change.
              (when (and @running? (> version subscribed-at)) (fire!))
              :watched)))))
      key)))

(defn unwatch!
  "Stop the watcher `key` of `room-id`."
  [room-id key]
  (when-let [{:keys [running?]} (get-in @feeds [room-id :watchers key])]
    (reset! running? false)
    (swap! feeds update-in [room-id :watchers] dissoc key))
  nil)

(defn drop-room!
  "Stop `room-id`'s feed: its store listener and every watcher."
  [room-id]
  (when-let [{:keys [store key watchers]} (get @feeds room-id)]
    (doseq [[_ {:keys [running?]}] watchers] (reset! running? false))
    (try (store/-unlisten! store key) (catch Throwable _ nil))
    (swap! feeds dissoc room-id))
  nil)

(rreg/add-register-hook! ::facts
                         (fn [room] (try (facts-signal room) (catch Throwable _ nil))))
(rreg/add-unregister-hook! ::facts drop-room!)
