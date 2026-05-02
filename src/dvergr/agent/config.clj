(ns dvergr.agent.config
  "Agent abstraction - constructor and configuration.

   An agent is a configured entity that can be asked to perform tasks.
   From outside: opaque, you ask! it and get a result.
   From inside: full autonomy over turns, tools, and sub-agents."
  (:require [clojure.spec.alpha :as s]))

;; ============================================================================
;; Agent Spec
;; ============================================================================

(s/def ::name string?)
(s/def ::model string?)
(s/def ::provider #{:anthropic :openai :fireworks})
(s/def ::tools (s/or :all #{:all}
                     :set set?
                     :fn fn?))
(s/def ::permissions (s/coll-of keyword? :kind set?))
(s/def ::isolation #{:native :sci :shared-sci})
(s/def ::body (s/nilable fn?))
(s/def ::system-prompt (s/nilable string?))

(s/def ::agent
  (s/keys :req-un [::name]
          :opt-un [::model ::provider ::tools
                   ::permissions ::isolation ::body ::system-prompt]))

;; ============================================================================
;; Agent Record
;; ============================================================================

(defrecord Agent
  [name           ; String - agent identifier
   model          ; String - LLM model to use
   provider       ; Keyword - :anthropic/:openai/:fireworks
   tools          ; :all | set | fn - tool permissions
   permissions    ; Set - agent capabilities #{:spawn-agents :admin}
   isolation      ; Keyword - :native/:sci/:shared-sci
   body           ; Optional fn - custom agent logic
   system-prompt]) ; Optional string - system prompt override

;; ============================================================================
;; Constructor
;; ============================================================================

(defn make-agent
  "Create an agent configuration.

   Options:
   - :name        - Agent identifier (required)
   - :model       - LLM model to use (default: claude-sonnet-4-5)
   - :provider    - LLM provider (default: :anthropic)
   - :tools       - Tool permissions: :all | set | predicate fn (default: :all)
   - :permissions - Agent permissions set (default: #{})
   - :isolation   - Execution isolation: :native/:sci/:shared-sci (default: :native)
   - :body        - Optional custom agent logic fn [task ctx] -> result
   - :system-prompt - Optional system prompt override (string)

   Agents run until natural termination:
   - Budget exhausted (primary control)
   - Task complete
   - Error occurs
   - Cancelled (via race/timeout combinators)

   Use FRP combinators for constraints:
   - (timeout (ask! agent task) ms fallback) - deadline
   - (race (ask! a1 t1) (ask! a2 t2)) - first wins
   - (parallel (ask! a1 t1) (ask! a2 t2)) - all complete

   Examples:

   (make-agent {:name \"researcher\"})

   (make-agent {:name \"coder\"
                :permissions #{:spawn-agents :use-tools}
                :isolation :sci})

   (make-agent {:name \"custom\"
                :body (fn [task ctx]
                        ;; Custom logic
                        (run-my-logic task ctx))})"
  [{:keys [name model provider tools permissions isolation body system-prompt]
    :or {model "claude-sonnet-4-5-20250514"
         provider :anthropic
         tools :all
         permissions #{}
         isolation :native}}]
  {:pre [(s/valid? ::agent (assoc {:name name}
                                   :model model
                                   :provider provider
                                   :tools tools
                                   :permissions permissions
                                   :isolation isolation
                                   :body body
                                   :system-prompt system-prompt))]}
  (map->Agent {:name name
               :model model
               :provider provider
               :tools tools
               :permissions permissions
               :isolation isolation
               :body body
               :system-prompt system-prompt}))

;; ============================================================================
;; Agent Queries
;; ============================================================================

(defn can-spawn?
  "Check if agent has permission to spawn sub-agents."
  [agent]
  (contains? (:permissions agent) :spawn-agents))

(defn can-use-tool?
  "Check if agent can use a specific tool."
  [agent tool-name]
  (let [tools (:tools agent)]
    (cond
      (= :all tools) true
      (set? tools) (contains? tools tool-name)
      (fn? tools) (tools tool-name)
      :else false)))

(defn isolated?
  "Check if agent runs in isolated (SCI) context."
  [agent]
  (#{:sci :shared-sci} (:isolation agent)))
