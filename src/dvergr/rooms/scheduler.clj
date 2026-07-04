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
            [sci.ctx-store :as sci-ctx-store]
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

(defn- run-code-task!
  "Evaluate a :schedule/code form in the agent's working-ctx sandbox and
   post the outcome to the room as :_activity (transcript, no agent
   turn). Errors are caught and reported the same way; a fire never
   throws out of the scheduler."
  [room aid s]
  (let [outcome
        (try
          (let [cctx ((requiring-resolve 'dvergr.agent.room-context/ensure-ctx!)
                      room aid {})
                sci-ctx (:sci-ctx cctx)
                ;; The eval runs in a future (timeout fence). It needs BOTH
                ;; contexts bound in that thread: spindel's *execution-context*
                ;; (conveyed from fire-one!'s binding, so kb/*room* datahike
                ;; systems resolve) AND SCI's ctx-store (so injected fns that
                ;; re-enter the interpreter find it). eval-string* sets the SCI
                ;; store only during eval, so a LAZY value in the result would
                ;; trip get-ctx when realized later — pr-str INSIDE the store
                ;; binding forces realization while it's still set.
                fut (future
                      (sci-ctx-store/with-ctx sci-ctx
                        (pr-str ((requiring-resolve 'sci.core/eval-string*)
                                 sci-ctx (:schedule/code s)))))
                r (deref fut (* 5 60 1000) ::timeout)]
            (if (= r ::timeout)
              (do (future-cancel fut) "⏱ code task TIMED OUT (5min)")
              (str "⏱ " (or (:schedule/description s) "code task") " → "
                   (subs r 0 (min 500 (count r))))))
          (catch Throwable t
            (str "⏱ " (or (:schedule/description s) "code task")
                 " FAILED: " (ex-message t))))]
    (disc/post! room (disc/message :scheduler :_activity outcome nil
                                   {:role :assistant
                                    :source :scheduler
                                    :schedule-id (:schedule/id s)}))))

(defn- fire-one!
  "Fire one schedule: a :schedule/code task evals in the agent's sandbox
   (deterministic, no LLM); otherwise the task message posts to the
   agent as a prompt turn. Then advance the row: bump last-run,
   recompute next-fire (or deactivate a fired :once)."
  [room conn ^java.util.Date now s]
  (let [aid (:schedule/agent-id s)]
    (if (:schedule/code s)
      (run-code-task! room aid s)
      (disc/post! room (disc/message :scheduler aid (:schedule/task s) nil
                                     {:source :scheduler
                                      :schedule-id (:schedule/id s)})))
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
