(ns dvergr.rooms.scheduler
  "Per-room reactive scheduler — RF5.

   Each room runs ONE scheduler spin that `track`s the daemon clock
   (`dvergr.runtime.clock`) and, on every minute boundary, fires the room's due
   schedules by posting their task into the room's own bus (addressed to an
   agent). A scheduled task is therefore an ordinary inbound room message
   (`:source :scheduler`) — it flows through the same persistence listener,
   message-signal fold, and turn handler as any human/agent message, and the
   agent runs it in its per-[room,agent] ctx/sandbox.

   This mirrors `dvergr.rooms.messages`: a daemon-global room→handle map, the
   spin created on a register hook + dropped on an unregister hook, spawned on
   the ROOT ctx so it sees the (root-anchored) clock. Schedules live in the
   room's OWN msgs store (`:schedule/*`, transparent attrs), so they fork/merge
   with the room and drop when it's deleted. The due-check is one indexed query
   on the materialized `:schedule/next-fire`.

   Teardown: spindel `track` has no disposal handle, so a torn-down room's spin
   keeps tracking but no-ops via its `:running?` flag (cleared on unregister).
   A negligible lingering no-op per deleted/discarded room until restart."
  (:require [datahike.api :as dh]
            [dvergr.discourse :as disc]
            [dvergr.runtime.clock :as clock]
            [dvergr.runtime.ctx :as rctx]
            [dvergr.room.registry :as rreg]
            [dvergr.scheduler.cron :as cron]
            [org.replikativ.spindel.core :as sp]
            [org.replikativ.spindel.spin.cps :refer [spin]]
            [org.replikativ.spindel.effects.track :refer [track]]
            [org.replikativ.spindel.incremental.interval :as iv]
            [org.replikativ.spindel.engine.core :as ec]
            [taoensso.telemere :as tel]))

;; room-id → handle atom {:running? bool}
(defonce ^:private room-schedulers (atom {}))

(defn- room-conn
  "The room's own msgs-store datahike connection (where :schedule/* live).
   Read straight off the Room value — no execution context needed."
  [room]
  (some-> room :store :conn))

(defn- due-schedules
  "Active schedules whose materialized next-fire is at or before `now`.
   One indexed range query on :schedule/next-fire."
  [conn ^java.util.Date now]
  (try
    (dh/q '[:find [(pull ?s [*]) ...]
            :in $ ?now
            :where [?s :schedule/active? true]
            [?s :schedule/next-fire ?t]
            [(<= ?t ?now)]]
          @conn now)
    (catch Throwable _ [])))

(defn- fire-one!
  "Post one schedule's task into the room (addressed to its agent), then advance
   the row: bump last-run, recompute next-fire (or deactivate a fired :once)."
  [room conn ^java.util.Date now s]
  (let [aid (:schedule/agent-id s)]
    (disc/post! room (disc/message :scheduler aid (:schedule/task s) nil
                                   {:source :scheduler
                                    :schedule-id (:schedule/id s)}))
    (let [next-fire (cron/compute-next-fire (assoc s :schedule/last-run now) now)]
      (dh/transact conn [(cond-> {:schedule/id (:schedule/id s)
                                  :schedule/last-run now}
                           next-fire        (assoc :schedule/next-fire next-fire)
                           (nil? next-fire) (assoc :schedule/active? false))]))
    (tel/log! {:id :scheduler/fired
               :data {:room (:slug room) :agent aid :schedule (:schedule/id s)}}
              "Scheduled task fired")))

(defn fire-due!
  "Fire every schedule in `room` due at `now`. Each fire is isolated — one
   failure does not skip the rest."
  [room ^java.util.Date now]
  (when-let [conn (room-conn room)]
    (doseq [s (due-schedules conn now)]
      (try (fire-one! room conn now s)
           (catch Throwable t
             (tel/log! {:level :warn :id :scheduler/fire-one-error
                        :data {:room (:slug room) :schedule (:schedule/id s)
                               :error (.getMessage t)}}
                       "Scheduled fire failed"))))))

(defn start-room-scheduler!
  "Spawn the room's scheduler spin (idempotent). Tracks the clock; fires due
   schedules each minute. Created on the ROOT ctx (clock visibility)."
  [room]
  (when-not (get @room-schedulers (:id room))
    (let [handle (atom {:running? true})]
      (swap! room-schedulers assoc (:id room) handle)
      (binding [ec/*execution-context* (rctx/root-ctx (:ctx room))]
        (sp/spawn!
         (spin
            ;; Re-runs on every clock change. The whole body is error-isolated
            ;; (a throw must NOT kill the reactive subscription — same hazard the
            ;; message fold guards against).
          (let [now (iv/get-new (track (clock/clock-signal)))]
            (when (:running? @handle)
              (try (fire-due! room now)
                   (catch Throwable t
                     (tel/log! {:level :warn :id :scheduler/tick-error
                                :data {:room (:slug room) :error (.getMessage t)}}
                               "Scheduler tick failed"))))
            :tick)))))
    :started))

(defn stop-room-scheduler!
  "Stop a room's scheduler (the spin no-ops on its next tick)."
  [room-id]
  (when-let [handle (get @room-schedulers room-id)]
    (swap! handle assoc :running? false)
    (swap! room-schedulers dissoc room-id))
  nil)

;; Establish the scheduler at room registration (alongside the message fold) and
;; drop it on every teardown path (delete, fork discard) — same hooks as
;; dvergr.rooms.messages.
(rreg/add-register-hook! ::scheduler
                         (fn [room] (try (start-room-scheduler! room) (catch Throwable _ nil))))

(rreg/add-unregister-hook! ::scheduler stop-room-scheduler!)
