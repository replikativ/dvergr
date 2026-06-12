(ns dvergr.scheduler.core
  "Room-scoped schedule management — RF5.

   A schedule is a per-room project artifact: its transparent `:schedule/*` row
   lives in the room's OWN msgs store, and the room's reactive scheduler spin
   (`dvergr.rooms.scheduler`) fires it by tracking the daemon clock. This
   namespace is just the CRUD over those rows — create / cancel / list, plus a
   `current-room` resolver for callers (the SCI `scheduler` ns) that only have a
   bound execution context. The firing itself is reactive; there is no
   per-schedule timer here anymore.

   Usage:
     (create-schedule! room {:agent-id :var
                             :task \"Check email inbox and triage\"
                             :interval-ms 300000})           ; or :schedule {:every :day :at \"09:00\"}
     (list-schedules room)
     (cancel-schedule! room schedule-id)"
  (:require [datahike.api :as d]
            [dvergr.runtime.ctx :as rctx]
            [dvergr.room.registry :as rreg]
            [dvergr.scheduler.cron :as cron]
            [org.replikativ.spindel.engine.core :as rtc])
  (:import [java.util Date]))

(defn- room-conn
  "The room's own datahike store connection (where :schedule/* live)."
  [room]
  (some-> room :store :conn))

(defn current-room
  "The registered room whose execution context is the one currently bound — i.e.
   the room an agent's sandbox is running in (the turn binds `(:ctx room)`).
   nil when there is no bound ctx or no matching room."
  []
  (let [ctx (try (rtc/current-execution-context) (catch Throwable _ nil))]
    (when ctx
      (first (filter #(identical? (:ctx %) ctx) (rreg/list-rooms))))))

;; ============================================================================
;; CRUD over the room's :schedule/* rows
;; ============================================================================

(defn create-schedule!
  "Create a schedule in `room`. `cfg`:
     :agent-id    keyword — a participant in this room to fire the task at
     :task        string  — the message posted on each fire
     :interval-ms long    — simple fixed interval, OR
     :schedule    map      — a cron spec {:every :day :at \"09:00\"} | {:at ISO :once true} | …
     :description string  — optional label

   Writes the transparent `:schedule/*` row (+ materialized `:schedule/next-fire`)
   into the room's own store; the room's scheduler spin fires it. Returns the id."
  [room {:keys [agent-id task interval-ms schedule description] :as cfg}]
  {:pre [(keyword? agent-id) (string? task)
         (or (pos-int? interval-ms) (map? schedule))]}
  (let [conn (room-conn room)]
    (when-not conn
      (throw (ex-info "Room has no datahike store — cannot persist a schedule"
                      {:room (:id room)})))
    (let [id   (or (:id cfg) (random-uuid))
          now  (Date.)
          spec (or schedule {:interval-ms interval-ms})
          row  (merge {:schedule/id        id
                       :schedule/agent-id  agent-id
                       :schedule/task      task
                       :schedule/active?   true
                       :schedule/created-at now}
                      (when description {:schedule/description description})
                      (cron/spec->attrs spec now))]
      (d/transact conn [row])
      id)))

(defn cancel-schedule!
  "Flag a schedule inactive in `room` (keeps the row). Returns :cancelled, or
   nil if the id is unknown / the room has no store."
  [room schedule-id]
  (when-let [conn (room-conn room)]
    (when (d/q '[:find ?s . :in $ ?id :where [?s :schedule/id ?id]] @conn schedule-id)
      (d/transact conn [{:schedule/id schedule-id :schedule/active? false}])
      :cancelled)))

(defn- ent->display
  "Normalize a `:schedule/*` entity to a display map (UI / tools)."
  [e]
  (cond-> {:id          (:schedule/id e)
           :agent-id    (:schedule/agent-id e)
           :task        (:schedule/task e)
           :kind        (:schedule/kind e)
           :active?     (:schedule/active? e)
           :next-fire   (:schedule/next-fire e)
           :last-run    (:schedule/last-run e)
           :description (:schedule/description e)}
    (:schedule/interval-ms e)  (assoc :interval-ms (:schedule/interval-ms e))
    (:schedule/every e)        (assoc :every (:schedule/every e))
    (:schedule/time-of-day e)  (assoc :at (cron/mins->hhmm (:schedule/time-of-day e)))
    (:schedule/weekday e)      (assoc :on (:schedule/weekday e))
    (:schedule/day-of-month e) (assoc :on-day (:schedule/day-of-month e))))

(defn list-schedules
  "Active schedules in `room` as display maps (newest next-fire first)."
  [room]
  (if-let [conn (room-conn room)]
    (->> (d/q '[:find [(pull ?s [*]) ...]
                :where [?s :schedule/id _] [?s :schedule/active? true]]
              @conn)
         (mapv ent->display)
         (sort-by #(some-> ^Date (:next-fire %) .getTime))
         vec)
    []))

(defn list-all-schedules
  "Cross-room aggregation: every active schedule across every registered room,
   each tagged with its :room slug. The fan-out read that replaces the old single
   global query (used by /api/schedules)."
  []
  (->> (rreg/list-rooms)
       (mapcat (fn [room]
                 (map #(assoc % :room (:slug room)) (list-schedules room))))
       vec))
