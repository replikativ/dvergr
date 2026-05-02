(ns dvergr.group.core
  "Group chat abstraction - multiple agents with shared context.

   A group is itself an agent - from outside, you ask! it like any agent.
   Inside, it coordinates multiple agents with a shared ChatContext."
  (:require [clojure.spec.alpha :as s]
            [org.replikativ.spindel.signal :as sig]
            [dvergr.chat.context :as chat-ctx]))

;; ============================================================================
;; Group Spec
;; ============================================================================

(s/def ::name string?)
(s/def ::participants (s/coll-of map? :kind vector?))
(s/def ::workflow (s/nilable fn?))
(s/def ::structure (s/nilable vector?))
(s/def ::budget pos-int?)
(s/def ::isolation #{:native :sci :shared-sci})

(s/def ::group
  (s/keys :req-un [::name ::participants]
          :opt-un [::workflow ::structure ::budget ::isolation]))

;; ============================================================================
;; Group Record
;; ============================================================================

(defrecord Group
  [name           ; String - group identifier
   participants   ; Atom<Vector<Agent>> - group members
   workflow       ; Optional fn - custom group coordination logic
   structure      ; Optional vector - declarative behavior tree structure
   context        ; ChatContext - shared context for group
   status         ; Atom<keyword> - :active/:paused/:completed
   budget         ; Integer - total budget for group
   isolation])    ; Keyword - :native/:sci/:shared-sci

;; ============================================================================
;; Constructor
;; ============================================================================

(defn make-group
  "Create a group chat with multiple agents.

   A group coordinates multiple agents with a shared ChatContext.
   From outside, it can be used with ask!/spawn! like any agent.

   Options:
   - :name         - Group identifier (required)
   - :participants - Initial agents in the group (default: [])
   - :workflow     - Optional fn [task group-ctx] -> result
   - :structure    - Optional declarative behavior tree (future Phase 4)
   - :budget       - Total budget for group (default: 50000)
   - :isolation    - Execution isolation level (default: :native)

   Either :workflow OR :structure should be provided (not both).
   If neither is provided, group will execute agents in parallel by default.

   Examples:

   ;; Simple group with workflow function
   (make-group {:name \"dev-team\"
                :participants [researcher coder tester]
                :workflow (fn [task ctx]
                            (let [research (ask! researcher task)
                                  code (ask! coder research)
                                  tests (ask! tester code)]
                              tests))})

   ;; Group with default parallel execution
   (make-group {:name \"research-team\"
                :participants [agent-a agent-b agent-c]
                :budget 30000})

   ;; Future: Declarative structure (Phase 4)
   (make-group {:name \"pipeline\"
                :participants [a b c]
                :structure [:sequence
                             [:action a]
                             [:parallel {:threshold 2}
                               [:action b]
                               [:action c]]]})"
  [{:keys [name participants workflow structure budget isolation]
    :or {participants []
         budget 50000
         isolation :native}}]
  {:pre [(s/valid? ::group {:name name
                             :participants participants
                             :workflow workflow
                             :structure structure
                             :budget budget
                             :isolation isolation})
         (not (and workflow structure))]}  ; Can't have both

  (let [;; Create shared ChatContext
        ctx (chat-ctx/create-chat-context
             {:title (str name " (group)")
              :budget budget
              :with-sci? (= :shared-sci isolation)})]

    (map->Group {:name name
                 :participants (atom (vec participants))
                 :workflow workflow
                 :structure structure
                 :context ctx
                 :status (atom :active)
                 :budget budget
                 :isolation isolation})))

;; ============================================================================
;; Group State Operations
;; ============================================================================

(defn get-participants
  "Get current participants in the group."
  [group]
  @(:participants group))

(defn get-status
  "Get current group status."
  [group]
  @(:status group))

(defn active?
  "Check if group is active (not paused/completed)."
  [group]
  (= :active (get-status group)))

(defn get-context
  "Get the shared ChatContext for this group."
  [group]
  (:context group))

;; ============================================================================
;; Participant Management
;; ============================================================================

(defn add-agent!
  "Add an agent to the group.

   The agent will have access to the shared group context.

   Example:
     (add-agent! group new-expert)"
  [group agent]
  (swap! (:participants group) conj agent)
  group)

(defn remove-agent!
  "Remove an agent from the group by name.

   Example:
     (remove-agent! group \"slow-agent\")"
  [group agent-name]
  (swap! (:participants group)
         (fn [agents]
           (filterv #(not= (:name %) agent-name) agents)))
  group)

(defn clear-participants!
  "Remove all participants from the group."
  [group]
  (reset! (:participants group) [])
  group)

;; ============================================================================
;; Admin Control
;; ============================================================================

(defn pause!
  "Pause the group - sets status to :paused.

   Agents should check group status and yield if paused.

   Example:
     (pause! group)"
  [group]
  (reset! (:status group) :paused)
  group)

(defn resume!
  "Resume a paused group - sets status back to :active.

   Example:
     (resume! group)"
  [group]
  (reset! (:status group) :active)
  group)

(defn cancel!
  "Cancel the group - sets status to :cancelled.

   Example:
     (cancel! group)"
  [group]
  (reset! (:status group) :cancelled)
  group)

(defn complete!
  "Mark the group as completed.

   Example:
     (complete! group)"
  [group]
  (reset! (:status group) :completed)
  group)
