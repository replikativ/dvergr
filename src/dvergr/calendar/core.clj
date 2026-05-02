(ns dvergr.calendar.core
  "Calendar CRUD operations and query API.

   All functions take a Datahike connection explicitly — no global state.
   SCI wrapper uses partial to bind the connection."
  (:require [datahike.api :as d]
            [dvergr.calendar.schema :as schema]
            [clojure.string :as str])
  (:import [java.util Date UUID]
           [java.time Instant ZoneId ZonedDateTime LocalDate LocalTime]))

;; ============================================================================
;; Schema Installation
;; ============================================================================

(defn install-schema!
  "Install calendar schema into datahike. Idempotent."
  [db-conn]
  (try
    (d/transact db-conn schema/schema)
    (catch Exception _
      nil)))

;; ============================================================================
;; Helpers
;; ============================================================================

(defn- get-timezone
  "Return the configured timezone or system default."
  ([] (get-timezone nil))
  ([tz-str]
   (if tz-str
     (ZoneId/of tz-str)
     (ZoneId/systemDefault))))

(defn from-now
  "Return a java.util.Date offset from now.
   Accepts a map with :minutes :hours :days keys."
  [{:keys [minutes hours days]
    :or {minutes 0 hours 0 days 0}}]
  (let [total-ms (+ (* minutes 60000)
                     (* hours 3600000)
                     (* days 86400000))]
    (Date. (+ (System/currentTimeMillis) total-ms))))

(defn- start-of-day
  "Return java.util.Date for start of day in the given timezone."
  ([tz] (start-of-day tz (LocalDate/now (get-timezone tz))))
  ([tz local-date]
   (let [zone (get-timezone tz)
         zdt (.atStartOfDay local-date zone)]
     (Date/from (.toInstant zdt)))))

(defn- end-of-day
  "Return java.util.Date for end of day in the given timezone."
  ([tz] (end-of-day tz (LocalDate/now (get-timezone tz))))
  ([tz local-date]
   (let [zone (get-timezone tz)
         zdt (ZonedDateTime/of local-date (LocalTime/of 23 59 59) zone)]
     (Date/from (.toInstant zdt)))))

;; ============================================================================
;; CRUD
;; ============================================================================

(defn add-event!
  "Add a calendar event. Returns the event map with :cal/id.

   Required: :title :start :end
   Optional: :type :participants :description :location :notify-before-ms
             :created-by :source :source-uid :status"
  [conn {:keys [title start end type participants description location
                notify-before-ms created-by source source-uid status]}]
  {:pre [(string? title) (instance? Date start) (instance? Date end)]}
  (let [event-id (UUID/randomUUID)
        entity (cond-> {:cal/id         event-id
                        :cal/title      title
                        :cal/start      start
                        :cal/end        end
                        :cal/status     (or status :confirmed)
                        :cal/source     (or source :internal)
                        :cal/created-at (Date.)
                        :cal/created-by (or created-by "human")}
                 type             (assoc :cal/type type)
                 (seq participants) (assoc :cal/participants (set participants))
                 description      (assoc :cal/description description)
                 location         (assoc :cal/location location)
                 notify-before-ms (assoc :cal/notify-before-ms notify-before-ms)
                 source-uid       (assoc :cal/source-uid source-uid))]
    (d/transact conn [entity])
    entity))

(defn update-event!
  "Update an existing calendar event by UUID. Merges updates into entity."
  [conn event-id updates]
  (let [tx-data (assoc updates :cal/id event-id)]
    (d/transact conn [tx-data])
    tx-data))

(defn cancel-event!
  "Set event status to :cancelled."
  [conn event-id]
  (d/transact conn [{:cal/id event-id :cal/status :cancelled}]))

(defn delete-event!
  "Retract an event entity entirely."
  [conn event-id]
  (when-let [eid (d/q '[:find ?e .
                         :in $ ?id
                         :where [?e :cal/id ?id]]
                       @conn event-id)]
    (d/transact conn [[:db/retractEntity eid]])))

;; ============================================================================
;; Queries
;; ============================================================================

(defn get-event
  "Pull a full event by UUID."
  [conn event-id]
  (d/q '[:find (pull ?e [*]) .
         :in $ ?id
         :where [?e :cal/id ?id]]
       @conn event-id))

(defn events-between
  "All events overlapping the [start, end] window.
   An event overlaps if its start < window-end AND its end > window-start."
  [conn start-inst end-inst]
  (d/q '[:find [(pull ?e [*]) ...]
         :in $ ?ws ?we
         :where
         [?e :cal/start ?s]
         [?e :cal/end ?en]
         [(< ?s ?we)]
         [(< ?ws ?en)]
         [?e :cal/status ?st]
         [(not= ?st :cancelled)]]
       @conn start-inst end-inst))

(defn today
  "Events for today (midnight to midnight in configured timezone)."
  ([conn] (today conn nil))
  ([conn tz]
   (let [s (start-of-day tz)
         e (end-of-day tz)]
     (events-between conn s e))))

(defn week
  "Events for the next 7 days."
  ([conn] (week conn nil))
  ([conn tz]
   (let [zone (get-timezone tz)
         now-date (LocalDate/now zone)
         s (start-of-day tz now-date)
         e (end-of-day tz (.plusDays now-date 6))]
     (events-between conn s e))))

(defn events-for-participant
  "All future events involving a specific participant."
  [conn participant-kw]
  (d/q '[:find [(pull ?e [*]) ...]
         :in $ ?p ?now
         :where
         [?e :cal/participants ?p]
         [?e :cal/end ?en]
         [(< ?now ?en)]
         [?e :cal/status ?st]
         [(not= ?st :cancelled)]]
       @conn participant-kw (Date.)))

(defn upcoming
  "Events in the next n minutes from now. Used by dispatcher."
  [conn minutes]
  (let [now (Date.)
        later (Date. (+ (.getTime now) (* minutes 60000)))]
    (events-between conn now later)))

(defn undispatched-upcoming
  "Events in the next n minutes that haven't been dispatched yet."
  [conn minutes]
  (let [now (Date.)
        later (Date. (+ (.getTime now) (* minutes 60000)))]
    (d/q '[:find [(pull ?e [*]) ...]
           :in $ ?ws ?we
           :where
           [?e :cal/start ?s]
           [?e :cal/end ?en]
           [(< ?s ?we)]
           [(< ?ws ?en)]
           [?e :cal/status ?st]
           [(not= ?st :cancelled)]
           (not [?e :cal/dispatched? true])]
         @conn now later)))

(defn mark-dispatched!
  "Mark an event as dispatched to prevent double-firing."
  [conn event-id]
  (d/transact conn [{:cal/id event-id :cal/dispatched? true}]))

(defn free-slots
  "Find free time slots on a given date.
   Returns a vector of {:start Date :end Date} gaps of at least duration-minutes.

   date-str: \"2026-02-24\" (ISO format)
   duration-minutes: minimum slot length"
  ([conn date-str duration-minutes] (free-slots conn date-str duration-minutes nil))
  ([conn date-str duration-minutes tz]
   (let [zone (get-timezone tz)
         local-date (LocalDate/parse date-str)
         day-start (start-of-day tz local-date)
         day-end (end-of-day tz local-date)
         ;; Work hours: 8:00-18:00
         work-start (Date/from (.toInstant (ZonedDateTime/of local-date (LocalTime/of 8 0) zone)))
         work-end (Date/from (.toInstant (ZonedDateTime/of local-date (LocalTime/of 18 0) zone)))
         events (->> (events-between conn day-start day-end)
                     (sort-by :cal/start))
         min-ms (* duration-minutes 60000)]
     ;; Walk through the day finding gaps
     (loop [cursor work-start
            events events
            slots []]
       (if (or (empty? events) (not (.before cursor work-end)))
         ;; Check trailing gap
         (let [gap-ms (- (.getTime work-end) (.getTime cursor))]
           (if (>= gap-ms min-ms)
             (conj slots {:start cursor :end work-end})
             slots))
         (let [evt (first events)
               evt-start (:cal/start evt)
               evt-end (:cal/end evt)]
           (if (.before cursor evt-start)
             ;; Gap before this event
             (let [gap-ms (- (.getTime evt-start) (.getTime cursor))]
               (if (>= gap-ms min-ms)
                 (recur evt-end (rest events) (conj slots {:start cursor :end evt-start}))
                 (recur evt-end (rest events) slots)))
             ;; Cursor inside or after this event
             (recur (if (.after evt-end cursor) evt-end cursor)
                    (rest events) slots))))))))
