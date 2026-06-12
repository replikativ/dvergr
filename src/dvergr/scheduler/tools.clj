(ns dvergr.scheduler.tools
  "Agent-facing tools for managing the CURRENT room's schedules (RF5).

   Schedules are per-room: each tool resolves the room from the tool context
   (`:room`, set by the turn handler) and operates on its store. The room's
   reactive scheduler spin (`dvergr.rooms.scheduler`) does the firing."
  (:require [clojure.string :as str]
            [dvergr.tools :as tools]))

;; Lazy require to avoid a compile-time cycle.
(defn- scheduler-ns []
  (require 'dvergr.scheduler.core)
  (find-ns 'dvergr.scheduler.core))

(defn- resolve-room
  "The room a tool acts on: the `:room` from the tool ctx, else the room of the
   bound execution context (SCI sandbox), else nil."
  [ctx]
  (or (:room ctx)
      ((ns-resolve (scheduler-ns) 'current-room))))

(tools/register!
 {:name "schedule_create"
  :description "Create a recurring scheduled task in THIS room.

   The room's scheduler fires the task into this room (addressed to the given
   agent) at the interval; the agent runs it in its sandbox. Use for periodic
   monitoring, polling, or maintenance.

   Parameters:
   - agent_id: Agent in this room to receive the task (e.g., 'var', 'ops')
   - task: Task message to post each interval
   - interval_minutes: How often to run (minutes, minimum 1)
   - description: Human-readable description

   Example: {\"agent_id\": \"ops\", \"task\": \"Check RSS feeds\",
             \"interval_minutes\": 15, \"description\": \"RSS feed poll\"}"
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
  :execute (fn [{:keys [agent_id task interval_minutes description]} ctx]
             (try
               (when (< interval_minutes 1)
                 (throw (ex-info "Interval must be at least 1 minute" {})))
               (if-let [room (resolve-room ctx)]
                 (let [create! (ns-resolve (scheduler-ns) 'create-schedule!)
                       sid (create! room
                                    {:agent-id (keyword agent_id)
                                     :task task
                                     :interval-ms (long (* interval_minutes 60000))
                                     :description (or description
                                                      (str task " (every " interval_minutes " min)"))})]
                   {:type :success
                    :content (str "Schedule created in this room.\n"
                                  "ID: " sid "\nAgent: " agent_id "\n"
                                  "Interval: every " interval_minutes " minute(s)\nTask: " task)
                    :metadata {:schedule-id (str sid) :agent-id agent_id
                               :interval-minutes interval_minutes}})
                 {:type :error :error "No room in context — schedules are per-room."})
               (catch Exception e
                 {:type :error :error (str "Failed to create schedule: " (.getMessage e))})))})

(tools/register!
 {:name "schedule_list"
  :description "List this room's active schedules (agent, task, interval, next fire)."
  :parameters {:type "object" :properties {} :required []}
  :execute (fn [_input ctx]
             (try
               (if-let [room (resolve-room ctx)]
                 (let [list-fn (ns-resolve (scheduler-ns) 'list-schedules)
                       schedules (list-fn room)]
                   {:type :success
                    :content (if (seq schedules)
                               (str "Active schedules (" (count schedules) "):\n\n"
                                    (str/join "\n\n"
                                              (map (fn [s]
                                                     (str "- " (or (:description s) (:task s)) "\n"
                                                          "  ID: " (:id s) "\n"
                                                          "  Agent: " (name (:agent-id s)) "\n"
                                                          "  Next: " (:next-fire s) "\n"
                                                          "  Task: " (:task s)))
                                                   schedules)))
                               "No active schedules in this room.")
                    :metadata {:count (count schedules) :schedules schedules}})
                 {:type :error :error "No room in context — schedules are per-room."})
               (catch Exception e
                 {:type :error :error (str "Failed to list schedules: " (.getMessage e))})))})

(tools/register!
 {:name "schedule_cancel"
  :description "Cancel an active schedule in this room by its ID (UUID from schedule_list)."
  :parameters {:type "object"
               :properties {:id {:type "string" :description "Schedule ID to cancel (UUID)"}}
               :required ["id"]}
  :execute (fn [{:keys [id]} ctx]
             (try
               (if-let [room (resolve-room ctx)]
                 (let [cancel! (ns-resolve (scheduler-ns) 'cancel-schedule!)
                       sid (parse-uuid id)]
                   (cond
                     (nil? sid)         {:type :error :error (str "Invalid UUID: " id)}
                     (cancel! room sid) {:type :success :content (str "Schedule cancelled: " id)
                                         :metadata {:schedule-id id}}
                     :else              {:type :error :error (str "Schedule not found: " id)}))
                 {:type :error :error "No room in context — schedules are per-room."})
               (catch Exception e
                 {:type :error :error (str "Failed to cancel schedule: " (.getMessage e))})))})
