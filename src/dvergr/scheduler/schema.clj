(ns dvergr.scheduler.schema
  "Datahike schema for the scheduler — RF5 transparent form.

   The firing rule is decomposed into typed, queryable attributes rather than
   an opaque EDN blob, so schedules: (a) merge per-attribute when a room forks,
   (b) are queryable (\"what fires on Mondays / at 09:00 / today\"), and
   (c) validate at transact time. `:schedule/next-fire` is materialized + indexed
   so the per-tick due-check is a single range query instead of per-row cron
   computation.

   A schedule is one of three KINDS (`:schedule/kind`):
     :interval   — fixed period, `:schedule/interval-ms`
     :recurring  — cron-like: `:schedule/every` (+ time-of-day / weekday / day-of-month / tz)
     :once       — fire once at `:schedule/next-fire`, then deactivate")

(def schema
  [{:db/ident       :schedule/id
    :db/valueType   :db.type/uuid
    :db/unique      :db.unique/identity
    :db/cardinality :db.cardinality/one
    :db/doc         "Unique schedule identifier"}

   {:db/ident       :schedule/agent-id
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Agent (a participant in THIS room) to fire the task at"}

   {:db/ident       :schedule/task
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Task message posted to the agent on each fire"}

   {:db/ident       :schedule/active?
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one
    :db/doc         "Live vs cancelled (cancel flips to false, keeps the row)"}

   {:db/ident       :schedule/created-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "When this schedule was created"}

   {:db/ident       :schedule/last-run
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Timestamp of the last successful fire"}

   {:db/ident       :schedule/description
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Human-readable label"}

   ;; --- the firing rule (transparent) ---

   {:db/ident       :schedule/kind
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":interval | :recurring | :once"}

   {:db/ident       :schedule/next-fire
    :db/valueType   :db.type/instant
    :db/index       true
    :db/cardinality :db.cardinality/one
    :db/doc         "Materialized next fire time — the due-check reads THIS (indexed)"}

   ;; :interval
   {:db/ident       :schedule/interval-ms
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc         "Fixed period between fires (:interval kind)"}

   ;; :recurring
   {:db/ident       :schedule/every
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":minute | :hour | :day | :week | :month (:recurring kind)"}

   {:db/ident       :schedule/time-of-day
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc         "Minutes since midnight (sortable/queryable); nil ⇒ default 09:00"}

   {:db/ident       :schedule/weekday
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":monday…:sunday (for :every :week)"}

   {:db/ident       :schedule/day-of-month
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc         "1–28 (for :every :month)"}

   {:db/ident       :schedule/tz
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "IANA timezone string; nil ⇒ system zone"}])
