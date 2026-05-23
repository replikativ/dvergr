(ns dvergr.registry
  "Named agent registry with metadata, tags, and status tracking.

   Provides a global registry of agent instances keyed by id, with metadata
   about status, capabilities, and which execution context they belong to.

   Integrates with spindel's execution-context-registry — when an agent is
   registered, its context can also be registered with a namespaced key
   like :agent/coder-1 for distributed addressing.

   Usage:
     (register! :coder agent {:tags #{:coding} :description \"Code writer\"})
     (lookup :coder)           ;; => {:agent Agent :status :running ...}
     (list-agents)             ;; => [{:id :coder :status :running ...}]
     (agents-by-tag :coding)   ;; => [:coder]
     (unregister! :coder)"
  (:require [org.replikativ.spindel.distributed.core :as dist]))

;; ============================================================================
;; Registry State
;; ============================================================================

(defonce registry (atom {}))

;; ============================================================================
;; Registration
;; ============================================================================

(defn register!
  "Register an agent in the registry.

   Args:
     agent-id    - Keyword identifier
     agent       - The agent value — typically a dvergr.discourse Participant.
                   The registry treats it opaquely; metadata lives on the
                   entry, not inside the agent.
     opts        - Map with:
       :tags        - Set of keyword tags (e.g., #{:coding :research})
       :description - Human-readable description
       :context-id  - Spindel execution context id for distributed addressing
                      (auto-derived as :agent/<id> if not provided)
       :config      - Optional agent config map (model, provider, system-prompt,
                      tools) — stored for stats/UI introspection."
  [agent-id agent & {:keys [tags description context-id config]}]
  (let [ctx-id (or context-id (keyword "agent" (name agent-id)))
        entry {:agent agent
               :status :registered
               :tags (or tags #{})
               :description (or description "")
               :config (or config {})
               :created-at (java.util.Date.)
               :context-id ctx-id}]
    (swap! registry assoc agent-id entry)
    entry))

(defn unregister!
  "Remove an agent from the registry.

   Also unregisters its execution context from distributed addressing."
  [agent-id]
  (when-let [entry (get @registry agent-id)]
    ;; Unregister from distributed context registry
    (when-let [ctx-id (:context-id entry)]
      (try
        (dist/unregister-context! ctx-id)
        (catch Exception _)))
    (swap! registry dissoc agent-id)
    :unregistered))

;; ============================================================================
;; Lookup
;; ============================================================================

(defn lookup
  "Look up an agent entry by id.

   Returns map with :agent, :status, :tags, :description, :created-at, :context-id
   or nil if not found."
  [agent-id]
  (get @registry agent-id))

(defn get-agent
  "Get the agent record for an id, or nil."
  [agent-id]
  (:agent (lookup agent-id)))

;; ============================================================================
;; Listing & Filtering
;; ============================================================================

(defn list-agents
  "List all registered agents with summary info.

   Options (keyword args):
     :status - Filter by status keyword
     :tags   - Filter by tags (agents must have ALL specified tags)

   Returns vector of maps with :id, :status, :tags, :description, :created-at."
  [& {:keys [status tags]}]
  (let [pairs (seq @registry)
        filtered (cond->> pairs
                   status (filter #(= status (:status (second %))))
                   tags   (filter #(every? (:tags (second %)) tags)))]
    (mapv (fn [[agent-id entry]]
            {:id agent-id
             :status (:status entry)
             :tags (:tags entry)
             :description (:description entry)
             :created-at (:created-at entry)
             :context-id (:context-id entry)})
          filtered)))

(defn agents-by-tag
  "Get agent ids that have the specified tag."
  [tag]
  (->> @registry
       (filter (fn [[_id entry]] (contains? (:tags entry) tag)))
       (mapv first)))

(defn agent-ids
  "Get all registered agent ids."
  []
  (vec (keys @registry)))

;; ============================================================================
;; Status Updates
;; ============================================================================

(defn update-status!
  "Update the registry status for an agent."
  [agent-id new-status]
  (swap! registry update agent-id assoc :status new-status)
  new-status)

(defn update-tags!
  "Update tags for an agent."
  [agent-id tags]
  (swap! registry update agent-id assoc :tags tags)
  tags)

;; ============================================================================
;; Cleanup
;; ============================================================================

(defn clear!
  "Clear the entire registry. Use with caution."
  []
  (reset! registry {})
  :cleared)
