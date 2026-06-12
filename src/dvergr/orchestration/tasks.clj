(ns dvergr.orchestration.tasks
  "Task ledger for skill dispatches that target non-agent actors.

   Agents react to inbox messages directly; they don't need a task row.
   Humans and externals, however, need a persistent record of what
   was asked of them so:
     - the dashboard can show \"pending tasks\" for a human
     - retries are possible (an :ignored task can be re-dispatched)
     - audit answers \"did var ever ask Alice to do the legal review?\"

   Status flow:
     :pending  → :accepted  → :completed
                            → :ignored

   :completed and :ignored set :task/completed-at. :ignored is set by
   the dispatcher (or a cleanup job) when too much time passes; humans
   themselves use :complete or :decline.

   The task is the durable handle; the actual content lives both here
   (as :task/content) AND as a posted message in the room (so it
   appears in the chat transcript). The dispatcher does both."
  (:require [datahike.api :as dh]
            [taoensso.telemere :as tel]))

(def ^:private pull-pattern
  [:task/id :task/actor-id :task/from-actor :task/room-id
   :task/skill :task/content :task/status
   :task/created-at :task/completed-at :task/result])

(defn- ent->task [ent]
  (when ent
    (->> {:id           (:task/id ent)
          :actor-id     (:task/actor-id ent)
          :from-actor   (:task/from-actor ent)
          :room-id      (:task/room-id ent)
          :skill        (:task/skill ent)
          :content      (:task/content ent)
          :status       (:task/status ent)
          :created-at   (:task/created-at ent)
          :completed-at (:task/completed-at ent)
          :result       (:task/result ent)}
         (filter (fn [[_ v]] (some? v)))
         (into {}))))

(defn create-task!
  "Persist a new task entity. Returns the task map.

   Required: :actor-id :room-id :content
   Optional: :skill :from-actor :id (UUID; auto-generated if absent)"
  [conn {:keys [id actor-id from-actor room-id skill content]
         :or   {id (random-uuid)}}]
  (let [tx (cond-> {:task/id         id
                    :task/actor-id   actor-id
                    :task/room-id    room-id
                    :task/content    content
                    :task/status     :pending
                    :task/created-at (java.util.Date.)}
             skill      (assoc :task/skill skill)
             from-actor (assoc :task/from-actor from-actor))]
    (dh/transact conn [tx])
    (tel/log! {:id :tasks/created
               :data {:task-id id :actor-id actor-id :skill skill}}
              "Task created")
    (ent->task (dh/q '[:find (pull ?e pattern) .
                       :in $ ?id pattern
                       :where [?e :task/id ?id]]
                     @conn id pull-pattern))))

(defn lookup
  "Return the task by id, or nil."
  [conn id]
  (ent->task (dh/q '[:find (pull ?e pattern) .
                     :in $ ?id pattern
                     :where [?e :task/id ?id]]
                   @conn id pull-pattern)))

(defn list-tasks
  "Tasks, optionally filtered by :actor-id and/or :status. Newest first."
  [conn & {:keys [actor-id status]}]
  (->> (dh/q '[:find [(pull ?e pattern) ...]
               :in $ pattern
               :where [?e :task/id _]]
             @conn pull-pattern)
       (map ent->task)
       (filter (fn [t]
                 (and (or (nil? actor-id) (= actor-id (:actor-id t)))
                      (or (nil? status)   (= status   (:status t))))))
       (sort-by :created-at #(compare %2 %1))
       vec))

(defn- transition!
  [conn id new-status & extras]
  (when (lookup conn id)
    (let [tx (apply hash-map
                    :task/id       id
                    :task/status   new-status
                    extras)]
      (dh/transact conn [tx])
      (lookup conn id))))

(defn accept!     [conn id]      (transition! conn id :accepted))
(defn complete!   [conn id result]
  (transition! conn id :completed
               :task/result        (str result)
               :task/completed-at  (java.util.Date.)))
(defn ignore!     [conn id]
  (transition! conn id :ignored :task/completed-at (java.util.Date.)))
