(ns dvergr.scheduler.core
  "Spindel-native scheduler for periodic agent tasks.

   Each schedule is a spin loop with comb/sleep. Schedules are persisted
   in datahike and restored on daemon restart.

   Usage:
     (create-schedule! daemon {:agent-id :var
                               :task \"Check email inbox and triage\"
                               :interval-ms 300000  ; 5 minutes
                               :description \"Email triage every 5 min\"})

     (list-schedules)
     (cancel-schedule! schedule-id)"
  (:require [dvergr.registry :as registry]
            [dvergr.discourse :as disc]
            [dvergr.sessions :as sessions]
            [dvergr.scheduler.schema :as schema]
            [dvergr.scheduler.cron :as cron]
            [org.replikativ.spindel.engine.core :as rtc]
            [org.replikativ.spindel.core :refer [spin await]]
            [org.replikativ.spindel.spin.combinators :as comb]
            [datahike.api :as d])
  (:import [java.util Date UUID]))

;; Resolve the daemon's `current-daemon` atom lazily — `dvergr.daemon`
;; requires this namespace, so a hard `:require` would create a cycle.
(defn- current-daemon
  []
  (some-> (requiring-resolve 'dvergr.daemon/current-daemon) deref deref))

;; ============================================================================
;; State (ctx-scoped)
;;
;; Active schedules live on the current spindel execution-context under
;; `[:dvergr/schedules]` as `{schedule-id -> {:spin spin :config map}}`.
;; The daemon binds its ctx around create/cancel; the schedule-loop spin
;; runs in that same ctx so `(get-active schedule-id)` finds itself.
;; Tests and sidecars get isolated schedule tables for free.
;; ============================================================================

(def ^:private path [:dvergr/schedules])

(defn- current-ctx-or-nil []
  (try (rtc/current-execution-context)
       (catch Throwable _ nil)))

(defn- all-active []
  (if (current-ctx-or-nil)
    (or (rtc/get-state path) {})
    {}))

(defn- get-active [schedule-id]
  (get (all-active) schedule-id))

(defn- swap-active! [f & args]
  (when (current-ctx-or-nil)
    (rtc/swap-state! path (fn [m] (apply f (or m {}) args)))))

;; ============================================================================
;; Datahike Persistence
;; ============================================================================

(defn install-schema!
  "Install scheduler schema into datahike. Idempotent."
  [db-conn]
  (try
    (d/transact db-conn schema/schema)
    (catch Exception _
      ;; Schema attributes already exist
      nil)))

(defn- save-schedule!
  "Persist a schedule config to datahike."
  [db-conn config]
  (d/transact db-conn
    [(cond-> {:schedule/id          (:id config)
              :schedule/agent-id    (:agent-id config)
              :schedule/task        (:task config)
              :schedule/active?     true
              :schedule/created-at  (Date.)}
       (:interval-ms config)
       (assoc :schedule/interval-ms (:interval-ms config))
       (:schedule config)
       (assoc :schedule/spec (pr-str (:schedule config)))
       (:description config)
       (assoc :schedule/description (:description config)))]))

(defn- deactivate-schedule!
  "Mark a schedule as inactive in datahike."
  [db-conn schedule-id]
  (d/transact db-conn
    [{:schedule/id schedule-id
      :schedule/active? false}]))

(defn- update-last-run!
  "Update the last-run timestamp for a schedule."
  [db-conn schedule-id]
  (try
    (d/transact db-conn
      [{:schedule/id schedule-id
        :schedule/last-run (Date.)}])
    (catch Exception e
      (binding [*err* *err*]
        (.println *err* (str "dvergr-scheduler: failed to update last-run: " (.getMessage e)))
        (.flush *err*)))))

(defn- load-active-schedules
  "Load all active schedules from datahike."
  [db-conn]
  (try
    (d/q '[:find [(pull ?s [*]) ...]
           :where
           [?s :schedule/active? true]]
         @db-conn)
    (catch Exception _
      [])))

;; ============================================================================
;; Schedule Loop
;; ============================================================================

(defn- schedule-loop
  "Create a spin that runs a schedule loop: sleep → dispatch → repeat.
   Returns the spin. Must be called with *execution-context* bound.

   Supports two scheduling modes:
   - :interval-ms — fixed interval (legacy)
   - :schedule — cron-like spec (see dvergr.scheduler.cron)

   notify-fn - Optional (fn [text]) called with the agent's response text
               after each scheduled run completes."
  [db-conn config notify-fn]
  (spin
    (loop []
      ;; Sleep until next fire time
      (let [sleep-ms (if-let [spec (:schedule config)]
                       (cron/next-fire-ms spec)
                       (:interval-ms config))]
        (await (comb/sleep sleep-ms)))
      (let [d    (current-daemon)
            room (:discourse-room d)
            aid  (:agent-id config)]
        (when (and room (registry/get-agent aid))
          (try
            (if notify-fn
              ;; Synchronous: ask + await reply, hand text to notify-fn.
              (let [reply (await (disc/ask room aid
                                           {:content (:task config)
                                            :metadata {:source :scheduler
                                                       :schedule-id (:id config)}}))]
                (notify-fn (str (:content reply))))
              ;; Fire-and-forget.
              (disc/post! room
                          (disc/message :scheduler aid (:task config) nil
                                        {:source :scheduler
                                         :schedule-id (:id config)})))
            (catch Exception e
              (binding [*err* *err*]
                (.println *err* (str "dvergr-scheduler: dispatch error for "
                                     (:id config) ": " (.getMessage e)))
                (.flush *err*))))))
      (when db-conn
        (update-last-run! db-conn (:id config)))
      ;; Check if still active
      (when (get-active (:id config))
        (recur)))))

;; ============================================================================
;; Public API
;; ============================================================================

(defn create-schedule!
  "Create a new recurring schedule.

   Args:
     daemon - Daemon record (or nil for testing)
     config - Map with:
       :agent-id     - Keyword agent ID to receive tasks
       :task         - String task message
       :interval-ms  - Interval in milliseconds (simple mode)
       :schedule     - Cron-like spec map (advanced mode):
                       {:every :day :at \"09:00\"}
                       {:every :week :on :monday :at \"09:00\"}
                       {:every :hour}
                       {:at \"2026-03-20T14:00:00+01:00[Europe/Berlin]\" :once true}
       :description  - Optional human-readable description

   Returns the schedule ID (UUID)."
  [daemon {:keys [agent-id task interval-ms schedule description] :as config}]
  {:pre [(keyword? agent-id)
         (string? task)
         (or (pos-int? interval-ms) (map? schedule))]}
  (let [schedule-id (or (:id config) (UUID/randomUUID))
        full-config (assoc config :id schedule-id)
        exec-ctx (or (when daemon (:execution-ctx daemon))
                     rtc/*execution-context*)
        db-conn (when daemon
                  (some-> exec-ctx
                          deref
                          :db-conn))]

    ;; Persist to datahike if available
    (when db-conn
      (save-schedule! db-conn full-config))

    ;; Build notification callback if daemon has :notify-chat-id configured
    (let [notify-fn (when daemon
                      (when-let [notify-id (get-in (:config daemon) [:notify-chat-id])]
                        (when-let [token (get-in (:config daemon) [:telegram :token])]
                          (fn [text]
                            (sessions/send-response! token notify-id
                              (str "Research digest completed\n\n" text))))))]

    ;; Start the spin loop (requires execution context)
    (when exec-ctx
      (let [s (binding [rtc/*execution-context* exec-ctx]
                (schedule-loop db-conn full-config notify-fn))]
        ;; Fire the spin
        (s (fn [_] (println "[scheduler] Schedule completed:" schedule-id))
           (fn [e] (binding [*err* *err*]
                     (.println *err* (str "dvergr-scheduler: schedule error " schedule-id ": " e))
                     (.flush *err*))))))

    ;; Track in active-schedules (store db-conn for cancel-schedule! to deactivate)
    (swap-active! assoc schedule-id
                  {:spin nil :config full-config :db-conn db-conn})

    (println "[scheduler] Created schedule:" schedule-id
             "- every" (/ interval-ms 60000.0) "min →" (name agent-id))
    schedule-id)))

(defn cancel-schedule!
  "Cancel an active schedule and mark it inactive in datahike."
  [schedule-id]
  (when-let [entry (get-active schedule-id)]
    (when-let [db-conn (:db-conn entry)]
      (deactivate-schedule! db-conn schedule-id))
    (swap-active! dissoc schedule-id)
    (println "[scheduler] Cancelled schedule:" schedule-id)
    :cancelled))

(defn list-schedules
  "List all active schedules. Returns vector of config maps."
  []
  (mapv (fn [[id {:keys [config]}]]
          (assoc config :id id))
        (all-active)))

(defn cancel-all!
  "Cancel all active schedules on the current ctx. Called during daemon shutdown."
  []
  (let [ids (keys (all-active))]
    (doseq [id ids]
      (cancel-schedule! id))
    (println "[scheduler] Cancelled" (count ids) "schedule(s)")
    (count ids)))

(defn snapshot
  "Return the live schedule table — tests / debugging."
  []
  (all-active))

(defn restore!
  "Replace the schedule table on the current ctx — tests."
  [m]
  (swap-active! (constantly (or m {})))
  nil)

(defn restore-schedules!
  "Restore active schedules from datahike after daemon restart."
  [daemon]
  (when-let [exec-ctx (:execution-ctx daemon)]
    (when-let [db-conn (some-> exec-ctx deref :db-conn)]
      (let [saved (load-active-schedules db-conn)]
        (doseq [s saved]
          (let [config (cond-> {:id          (:schedule/id s)
                                :agent-id    (:schedule/agent-id s)
                                :task        (:schedule/task s)
                                :description (:schedule/description s)}
                         (:schedule/interval-ms s)
                         (assoc :interval-ms (:schedule/interval-ms s))
                         (:schedule/spec s)
                         (assoc :schedule (clojure.edn/read-string (:schedule/spec s))))]
            (try
              (create-schedule! daemon config)
              (catch Exception e
                (binding [*err* *err*]
                  (.println *err* (str "dvergr-scheduler: failed to restore schedule: " (.getMessage e)))
                  (.flush *err*))))))
        (println "[scheduler] Restored" (count saved) "schedule(s) from database")))))
