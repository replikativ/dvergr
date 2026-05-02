(ns dvergr.calendar.schema
  "Datahike schema for calendar events.")

(def schema
  "Calendar event attributes for datahike."
  [{:db/ident       :cal/id
    :db/valueType   :db.type/uuid
    :db/unique      :db.unique/identity
    :db/cardinality :db.cardinality/one
    :db/doc         "Unique calendar event identifier"}

   {:db/ident       :cal/title
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Event title / summary"}

   {:db/ident       :cal/description
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Detailed event description"}

   {:db/ident       :cal/start
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Event start time"}

   {:db/ident       :cal/end
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Event end time"}

   {:db/ident       :cal/type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Event type: :meeting :discussion :deadline :review :external"}

   {:db/ident       :cal/participants
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/many
    :db/doc         "Agent IDs and/or :human participating in the event"}

   {:db/ident       :cal/source
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Event source: :internal :ical-import :agent"}

   {:db/ident       :cal/source-uid
    :db/valueType   :db.type/string
    :db/unique      :db.unique/identity
    :db/cardinality :db.cardinality/one
    :db/doc         "iCal UID for dedup on import"}

   {:db/ident       :cal/location
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Event location"}

   {:db/ident       :cal/status
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Event status: :confirmed :tentative :cancelled"}

   {:db/ident       :cal/created-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "When this event was created"}

   {:db/ident       :cal/created-by
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Who created this event (agent-id, human, or ical-import)"}

   {:db/ident       :cal/rrule
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "iCal RRULE string for recurrence (future)"}

   {:db/ident       :cal/notify-before-ms
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc         "Notification lead time in milliseconds"}

   {:db/ident       :cal/dispatched?
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one
    :db/doc         "Whether this event has been dispatched (prevents double-firing)"}])
