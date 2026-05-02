(ns dvergr.scheduler.schema
  "Datahike schema for the scheduler system.")

(def schema
  "Schedule entity attributes for datahike."
  [{:db/ident       :schedule/id
    :db/valueType   :db.type/uuid
    :db/unique      :db.unique/identity
    :db/cardinality :db.cardinality/one
    :db/doc         "Unique schedule identifier"}

   {:db/ident       :schedule/agent-id
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Agent to receive scheduled tasks"}

   {:db/ident       :schedule/task
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Task message to send to agent on each interval"}

   {:db/ident       :schedule/interval-ms
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc         "Interval between executions in milliseconds"}

   {:db/ident       :schedule/last-run
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Timestamp of last successful execution"}

   {:db/ident       :schedule/active?
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one
    :db/doc         "Whether this schedule is currently active"}

   {:db/ident       :schedule/description
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Human-readable description of the schedule"}

   {:db/ident       :schedule/created-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "When this schedule was created"}

   {:db/ident       :schedule/spec
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Cron-like schedule spec as EDN string (e.g. {:every :week :on :monday :at \"09:00\"})"}])
