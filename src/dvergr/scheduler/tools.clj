(ns dvergr.scheduler.tools
  "Agent-facing tools for creating and managing schedules."
  (:require [dvergr.tools :as tools]))

;; Lazy require to avoid loading at compile time
(defn- scheduler-ns []
  (require 'dvergr.scheduler.core)
  (find-ns 'dvergr.scheduler.core))

(tools/register!
  {:name "schedule_create"
   :description "Create a recurring scheduled task.

   The scheduler will send the task message to the specified agent's inbox
   at the given interval. Use this for periodic monitoring, polling, or
   maintenance tasks.

   Parameters:
   - agent_id: Agent to receive the task (e.g., 'var', 'ops')
   - task: Task message to send each interval
   - interval_minutes: How often to run (in minutes, minimum 1)
   - description: Human-readable description of the schedule

   Example: Poll RSS feeds every 15 minutes
   {\"agent_id\": \"ops\", \"task\": \"Check RSS feeds for new articles\",
    \"interval_minutes\": 15, \"description\": \"RSS feed poll\"}

   Example: Daily status report
   {\"agent_id\": \"var\", \"task\": \"Generate daily status summary\",
    \"interval_minutes\": 1440, \"description\": \"Daily status report\"}"
   :parameters {:type "object"
                :properties {:agent_id {:type "string"
                                        :description "Agent ID to receive scheduled tasks"}
                             :task {:type "string"
                                    :description "Task message to send on each interval"}
                             :interval_minutes {:type "number"
                                                :description "Interval in minutes (minimum 1)"}
                             :description {:type "string"
                                           :description "Human-readable description"}}
                :required ["agent_id" "task" "interval_minutes"]}
   :execute (fn [{:keys [agent_id task interval_minutes description]}
                 {:keys [execution-ctx]}]
              (try
                (when (< interval_minutes 1)
                  (throw (ex-info "Interval must be at least 1 minute" {})))
                (let [sched-ns (scheduler-ns)
                      create! (ns-resolve sched-ns 'create-schedule!)
                      ;; We pass nil as daemon since tools don't have daemon ref.
                      ;; The schedule still runs via the active spindel context.
                      schedule-id (create! nil
                                    {:agent-id (keyword agent_id)
                                     :task task
                                     :interval-ms (long (* interval_minutes 60000))
                                     :description (or description
                                                      (str task " (every " interval_minutes " min)"))})]
                  {:type :success
                   :content (str "Schedule created!\n"
                                 "ID: " schedule-id "\n"
                                 "Agent: " agent_id "\n"
                                 "Interval: every " interval_minutes " minute(s)\n"
                                 "Task: " task)
                   :metadata {:schedule-id (str schedule-id)
                              :agent-id agent_id
                              :interval-minutes interval_minutes}})
                (catch Exception e
                  {:type :error
                   :error (str "Failed to create schedule: " (.getMessage e))})))})

(tools/register!
  {:name "schedule_list"
   :description "List all active schedules.

   Returns a list of all currently active recurring schedules with their
   details: agent, task, interval, last run time.

   Example: {}  (no parameters needed)"
   :parameters {:type "object"
                :properties {}
                :required []}
   :execute (fn [_input _ctx]
              (try
                (let [sched-ns (scheduler-ns)
                      list-fn (ns-resolve sched-ns 'list-schedules)
                      schedules (list-fn)]
                  {:type :success
                   :content (if (seq schedules)
                              (str "Active schedules (" (count schedules) "):\n\n"
                                   (clojure.string/join "\n\n"
                                     (map (fn [s]
                                            (str "- " (or (:description s) (:task s)) "\n"
                                                 "  ID: " (:id s) "\n"
                                                 "  Agent: " (name (:agent-id s)) "\n"
                                                 "  Interval: every " (/ (:interval-ms s) 60000.0) " min\n"
                                                 "  Task: " (:task s)))
                                          schedules)))
                              "No active schedules.")
                   :metadata {:count (count schedules)
                              :schedules schedules}})
                (catch Exception e
                  {:type :error
                   :error (str "Failed to list schedules: " (.getMessage e))})))})

(tools/register!
  {:name "schedule_cancel"
   :description "Cancel an active schedule by its ID.

   Parameters:
   - id: Schedule ID (UUID string, from schedule_list output)

   Example: {\"id\": \"550e8400-e29b-41d4-a716-446655440000\"}"
   :parameters {:type "object"
                :properties {:id {:type "string"
                                  :description "Schedule ID to cancel (UUID)"}}
                :required ["id"]}
   :execute (fn [{:keys [id]} _ctx]
              (try
                (let [sched-ns (scheduler-ns)
                      cancel! (ns-resolve sched-ns 'cancel-schedule!)
                      schedule-id (parse-uuid id)]
                  (if schedule-id
                    (if (cancel! schedule-id)
                      {:type :success
                       :content (str "Schedule cancelled: " id)
                       :metadata {:schedule-id id}}
                      {:type :error
                       :error (str "Schedule not found: " id)})
                    {:type :error
                     :error (str "Invalid UUID: " id)}))
                (catch Exception e
                  {:type :error
                   :error (str "Failed to cancel schedule: " (.getMessage e))})))})
