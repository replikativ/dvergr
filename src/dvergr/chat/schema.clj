(ns dvergr.chat.schema
  "Chat schema for cognitive agent group chats.

   Adapted from simmis schema with agent-specific extensions:
   - Budget tracking for resource accounting
   - Parent/child hierarchy for sub-chats
   - Participant permissions for tiered agents
   - Message importance for context compaction

   Architecture:
   - Each chat maps to a SpindelContext (for reactive state)
   - Each chat has its own datahike (for durable storage)
   - Sub-chats fork parent's SpindelContext (O(1) CoW)
   - Messages are synced: spindel signal ↔ datahike"
  (:require [datahike.api :as d]
            [dvergr.chat.tool-schema :as tool-schema]))

;; ============================================================================
;; Chat Schema
;; ============================================================================

(def chat-schema
  "Schema for chat threads (group chats with agents)."
  [;; Identity
   {:db/ident :chat/id
    :db/valueType :db.type/uuid
    :db/unique :db.unique/identity
    :db/cardinality :db.cardinality/one
    :db/doc "Unique identifier for chat"}

   {:db/ident :chat/title
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Display title of chat"}

   {:db/ident :chat/description
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Optional description of chat purpose/task"}

   ;; Hierarchy
   {:db/ident :chat/parent-id
    :db/valueType :db.type/uuid
    :db/cardinality :db.cardinality/one
    :db/doc "Parent chat UUID if this is a sub-chat (nil for root chats)"}

   ;; Participants
   {:db/ident :chat/admin
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc "Admin participant who can stop/pause/merge this chat"}

   {:db/ident :chat/participants
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/doc "All participants in this chat"}

   ;; Budget (for accounting)
   {:db/ident :chat/budget-total
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc "Total token budget allocated to this chat"}

   {:db/ident :chat/budget-used
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc "Tokens consumed so far"}

   ;; Status
   {:db/ident :chat/status
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc "Chat status: :active :paused :completed :cancelled :budget-exceeded"}

   ;; Timestamps
   {:db/ident :chat/created-at
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc "When the chat was created"}

   {:db/ident :chat/updated-at
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc "Last activity timestamp"}

   ;; Summary (for parent propagation)
   {:db/ident :chat/summary
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "AI-generated summary of chat (created on completion)"}])

;; ============================================================================
;; Participant Schema
;; ============================================================================

(def participant-schema
  "Schema for chat participants (humans and agents)."
  [{:db/ident :participant/id
    :db/valueType :db.type/uuid
    :db/unique :db.unique/identity
    :db/cardinality :db.cardinality/one
    :db/doc "Unique identifier for participant"}

   {:db/ident :participant/name
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Display name"}

   {:db/ident :participant/type
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc "Participant type: :human :privileged-agent :sci-agent :podman-agent"}

   {:db/ident :participant/permissions
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/many
    :db/doc "Set of permissions: :spawn-chat :stop-chat :write-file :shell :wiki-edit :wiki-propose"}

   {:db/ident :participant/budget
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc "Per-participant budget limit (optional)"}

   {:db/ident :participant/model
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "LLM model for agent participants (e.g., 'claude-sonnet-4-20250514')"}

   {:db/ident :participant/provider
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc "LLM provider for agent participants: :anthropic :openai :fireworks"}])

;; ============================================================================
;; Message Schema
;; ============================================================================

(def message-schema
  "Schema for messages within chats."
  [;; Identity
   {:db/ident :message/id
    :db/valueType :db.type/uuid
    :db/unique :db.unique/identity
    :db/cardinality :db.cardinality/one
    :db/doc "Unique identifier for message"}

   {:db/ident :message/chat
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc "Reference to chat this message belongs to"}

   ;; Author
   {:db/ident :message/author
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc "Reference to participant who sent this message"}

   {:db/ident :message/role
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc "Message role: :user :assistant :system :tool-result"}

   ;; Content
   {:db/ident :message/content
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Message content (text or structured content as EDN)"}

   ;; Threading
   {:db/ident :message/reply-to
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc "Optional reference to message this is a reply to"}

   ;; Mentions/References
   {:db/ident :message/mentions
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/doc "References to mentioned entities (participants, knowledge articles)"}

   ;; Accounting
   {:db/ident :message/tokens
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc "Token count for this message (for budget tracking)"}

   ;; Compaction
   {:db/ident :message/important?
    :db/valueType :db.type/boolean
    :db/cardinality :db.cardinality/one
    :db/doc "If true, never compact this message (key decisions, approvals)"}

   {:db/ident :message/summary
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "AI-generated summary (filled during compaction)"}

   {:db/ident :message/compacted?
    :db/valueType :db.type/boolean
    :db/cardinality :db.cardinality/one
    :db/doc "If true, this message has been compacted (content replaced with summary)"}

   ;; Tool uses (for assistant messages)
   {:db/ident :message/tool-uses
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/isComponent true
    :db/doc "Tool use requests in this message (references tool-use entities)"}

   ;; Tool result reference (for tool-result messages)
   {:db/ident :message/tool-use-id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "References the tool_use_id this result responds to (for tool-result role)"}

   ;; Turn tracking (for UI grouping)
   {:db/ident :message/turn-number
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc "Which agent turn this message belongs to (1-based). Enables UI grouping."}

   ;; Timestamps
   {:db/ident :message/created-at
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc "When the message was sent"}

   {:db/ident :message/edited-at
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc "When the message was last edited (nil if never)"}])

;; ============================================================================
;; Tool Call Schema
;; ============================================================================

(def tool-call-schema
  "Schema for tool calls within messages.
   Note: Uses :tool-use/ prefix for component entities in messages."
  [;; Tool call tracking (for analytics)
   {:db/ident :tool-call/id
    :db/valueType :db.type/uuid
    :db/unique :db.unique/identity
    :db/cardinality :db.cardinality/one
    :db/doc "Unique identifier for tool call"}

   {:db/ident :tool-call/message
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc "Reference to message containing this tool call"}

   {:db/ident :tool-call/name
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Tool name (e.g., 'read_file', 'clojure_eval')"}

   {:db/ident :tool-call/input
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Tool input as EDN string"}

   {:db/ident :tool-call/result
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Tool result as EDN string"}

   {:db/ident :tool-call/duration-ms
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc "Execution duration in milliseconds"}

   {:db/ident :tool-call/error?
    :db/valueType :db.type/boolean
    :db/cardinality :db.cardinality/one
    :db/doc "True if tool call resulted in error"}

   {:db/ident :tool-call/status
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc "Execution lifecycle: :pending :running :completed :error :skipped"}

   {:db/ident :tool-call/approval
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc "Approval state: :auto-approved :pending-approval :approved :rejected :cached"}

   {:db/ident :tool-call/tool-use-id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/index true
    :db/doc "Links to the tool-use/id in the assistant message (for joining)"}

   {:db/ident :tool-call/result-message
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc "Reference to the tool-result message"}

   {:db/ident :tool-call/started-at
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc "When execution started"}

   ;; Tool use components (embedded in message/tool-uses)
   {:db/ident :tool-use/id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Tool use ID from LLM response"}

   {:db/ident :tool-use/name
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Tool name requested"}

   {:db/ident :tool-use/input
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/isComponent true
    :db/doc "Tool input as structured entity (schema auto-generated from tool definition)"}])

;; ============================================================================
;; Accounting Schema (for budget tracking)
;; ============================================================================

(def accounting-schema
  "Schema for resource accounting entries."
  [{:db/ident :account/id
    :db/valueType :db.type/uuid
    :db/unique :db.unique/identity
    :db/cardinality :db.cardinality/one
    :db/doc "Unique identifier for accounting entry"}

   {:db/ident :account/chat
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc "Reference to chat this entry belongs to"}

   {:db/ident :account/type
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc "Resource type: :input-tokens :output-tokens :tool-call :api-call"}

   {:db/ident :account/amount
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc "Amount consumed"}

   {:db/ident :account/message
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc "Optional reference to message that triggered this usage"}

   {:db/ident :account/timestamp
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc "When this usage occurred"}

   {:db/ident :account/metadata
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Additional metadata as EDN (model, provider, etc.)"}])

;; ============================================================================
;; Task Schema (for agent task tracking)
;; ============================================================================

;; ============================================================================
;; Knowledge Schema (for wiki-link entities)
;; ============================================================================

(def knowledge-schema
  "Schema for knowledge entities extracted from conversations.

   Entities are identified by title and accumulate context over time.
   Links between entities form a knowledge graph."
  [{:db/ident :entity/id
    :db/valueType :db.type/uuid
    :db/unique :db.unique/identity
    :db/cardinality :db.cardinality/one
    :db/doc "Unique identifier for entity"}

   {:db/ident :entity/title
    :db/valueType :db.type/string
    :db/unique :db.unique/value
    :db/cardinality :db.cardinality/one
    :db/doc "Entity title (the [[Title]] in wiki-links)"}

   {:db/ident :entity/summary
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "AI-generated summary of what this entity is"}

   {:db/ident :entity/contexts
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/many
    :db/doc "Context strings from [[Entity][context]] mentions"}

   {:db/ident :entity/links
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/doc "References to related entities"}

   {:db/ident :entity/from-sessions
    :db/valueType :db.type/uuid
    :db/cardinality :db.cardinality/many
    :db/doc "Sessions that mentioned this entity"}

   {:db/ident :entity/mention-count
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc "Total number of mentions across all sessions"}

   {:db/ident :entity/created-at
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc "When the entity was first mentioned"}

   {:db/ident :entity/updated-at
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc "When the entity was last updated"}

   {:db/ident :entity/type
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc "Entity type: :competitor :client :partner :project :technology"}

   {:db/ident :entity/url
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Primary URL for this entity"}

   {:db/ident :entity/tags
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/many
    :db/doc "Tags for categorization"}

   ;; Sync sources — URLs to periodically fetch and extract context from
   {:db/ident :entity/sync-sources
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "EDN string: [{:type \"linkedin|twitter|github|bluesky|web\" :url \"...\"}]"}

   {:db/ident :entity/last-synced
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc "When entity sources were last fetched and updated"}

   ;; Person-specific attributes
   {:db/ident :entity/role
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Job title / role (for :person entities, e.g. 'VP of Engineering at Acme Corp')"}

   {:db/ident :entity/employer
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc "Employing company entity ref (for :person entities)"}])

;; ============================================================================
;; Task Schema (for agent task tracking)
;; ============================================================================

(def task-schema
  "Schema for tasks that agents can create and manage."
  [{:db/ident :task/id
    :db/valueType :db.type/uuid
    :db/unique :db.unique/identity
    :db/cardinality :db.cardinality/one
    :db/doc "Unique identifier for task"}

   {:db/ident :task/title
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Task title/summary"}

   {:db/ident :task/description
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Detailed task description"}

   {:db/ident :task/status
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc "Task status: :pending :in-progress :completed :blocked"}

   {:db/ident :task/priority
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc "Task priority: :low :medium :high :critical"}

   {:db/ident :task/created-by
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Name of agent/user who created this task"}

   {:db/ident :task/assigned-to
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Name of agent/user assigned to this task"}

   {:db/ident :task/created-at
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc "When the task was created"}

   {:db/ident :task/completed-at
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc "When the task was completed"}

   {:db/ident :task/tags
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/many
    :db/doc "Tags for categorizing tasks"}])

;; ============================================================================
;; Full Schema
;; ============================================================================

;; Web schema is defined in dvergr.web.search namespace
;; and should be installed separately or included via:
;; (require '[dvergr.web.search :as web])
;; (web/install-web-schema! conn)

;; ============================================================================
;; Ledger Schema (detailed cost tracking with numéraire conversion)
;; ============================================================================

(def ledger-schema
  "Schema for detailed ledger entries with microdollar cost tracking.
   Microdollars (μ$) are the numéraire: 1 USD = 1,000,000 μ$"
  [{:db/ident :ledger/id
    :db/valueType :db.type/uuid
    :db/unique :db.unique/identity
    :db/cardinality :db.cardinality/one
    :db/doc "Unique identifier for ledger entry"}

   {:db/ident :ledger/context
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc "Reference to chat/context this entry belongs to"}

   {:db/ident :ledger/timestamp
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc "When this entry was recorded"}

   {:db/ident :ledger/resource
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc "Resource type: :token-input :token-output :web-search etc."}

   {:db/ident :ledger/amount
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc "Amount in natural units (tokens, calls, ms)"}

   {:db/ident :ledger/cost-microdollars
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc "Cost in microdollars (1 USD = 1,000,000)"}

   {:db/ident :ledger/model
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Model ID if LLM-related"}

   {:db/ident :ledger/provider
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc "Provider: :anthropic :fireworks :brave etc."}

   {:db/ident :ledger/tool
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Tool name if tool-related"}

   {:db/ident :ledger/metadata
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Additional metadata as EDN"}])

;; ============================================================================
;; Budget Schema (context-level budget tracking)
;; ============================================================================

(def budget-schema
  "Schema for budget tracking at context level.
   Uses microdollars as the unit of account."
  [{:db/ident :budget/context
    :db/valueType :db.type/ref
    :db/unique :db.unique/identity
    :db/cardinality :db.cardinality/one
    :db/doc "Reference to chat/context (one budget per context)"}

   {:db/ident :budget/total-microdollars
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc "Total budget allocated in microdollars"}

   {:db/ident :budget/used-microdollars
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc "Budget consumed so far in microdollars"}

   {:db/ident :budget/crossed-thresholds
    :db/valueType :db.type/double
    :db/cardinality :db.cardinality/many
    :db/doc "Set of threshold percentages already crossed (0.5, 0.75, 0.9, 0.95)"}])

;; ============================================================================
;; Pricing Schema (for DB-backed pricing - future use)
;; ============================================================================

(def pricing-schema
  "Schema for storing pricing in database.
   Enables dynamic price updates and historical cost recalculation."
  [{:db/ident :pricing/id
    :db/valueType :db.type/keyword
    :db/unique :db.unique/identity
    :db/cardinality :db.cardinality/one
    :db/doc "Pricing rule ID (e.g., :claude-sonnet-input)"}

   {:db/ident :pricing/resource
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc "Resource type this pricing applies to"}

   {:db/ident :pricing/model
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Model ID (for model-specific pricing)"}

   {:db/ident :pricing/microdollars-per-unit
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc "Cost in microdollars per natural unit"}

   {:db/ident :pricing/effective-from
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc "When this pricing became effective"}])

;; ============================================================================
;; Room Schema (for persistent chat rooms)
;; ============================================================================

(def room-schema
  "Schema for rooms — persistent chat rooms mirroring Telegram groups or internal."
  [{:db/ident :room/type
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc ":telegram-mirror or :internal"}

   {:db/ident :room/telegram-chat-id
    :db/valueType :db.type/long
    :db/unique :db.unique/identity
    :db/cardinality :db.cardinality/one}

   {:db/ident :room/slug
    :db/valueType :db.type/string
    :db/unique :db.unique/identity
    :db/cardinality :db.cardinality/one
    :db/doc "URL-friendly identifier for web routes"}

   {:db/ident :room/agent-ids
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/many}

   {:db/ident :message/source-user
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Display name of external sender"}

   {:db/ident :message/source-username
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident :message/source-user-id
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one}

   ;; Room hierarchy
   {:db/ident :room/parent-id
    :db/valueType :db.type/uuid
    :db/cardinality :db.cardinality/one
    :db/doc "Parent room chat-id (nil = top-level, e.g., boardroom)"}

   {:db/ident :room/report-interval-ms
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc "How often to summarize to parent (0 = manual)"}

   ;; Message reactions (for voting/consensus)
   {:db/ident :message/reactions
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "EDN map of {emoji -> [agent-id ...]} reactions"}])

;; ============================================================================
;; Proposal Schema (for merge proposals)
;; ============================================================================

(def proposal-schema
  "Schema for merge proposals — formalized fork/test/review/merge lifecycle.
   Proposals are created by agents via propose!, reviewed by humans or
   other agents, then accepted (merged) or rejected (discarded)."
  [{:db/ident :proposal/id
    :db/valueType :db.type/uuid
    :db/unique :db.unique/identity
    :db/cardinality :db.cardinality/one
    :db/doc "Unique identifier for proposal"}

   {:db/ident :proposal/agent-id
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc "Agent that created this proposal"}

   {:db/ident :proposal/status
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc "Proposal status: :pending :accepted :rejected :failed"}

   {:db/ident :proposal/summary
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Human-readable summary of what the proposal does"}

   {:db/ident :proposal/task
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Original task string that generated this proposal"}

   {:db/ident :proposal/fork-id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Serialized fork-id for context recovery"}

   {:db/ident :proposal/test-result
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Test result as EDN string"}

   {:db/ident :proposal/created-at
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc "When the proposal was created"}

   {:db/ident :proposal/resolved-at
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc "When the proposal was accepted or rejected"}])

;; Forward declaration for use in create-chat-db!
(declare get-tool-registry)

;; ============================================================================
;; Simulation Schema (executable models with dedicated DBs)
;; ============================================================================

(def simulation-schema
  "Schema for simulations — executable Clojure code (SCI) with dedicated Datahike DBs.

   Simulations are not limited to digital twins of real entities. They can model:
   - Competitors, clients, markets (entity-linked)
   - Hypothetical scenarios or abstract dynamics
   - Intake aggregation pipelines
   - Any programmatic model an agent wants to maintain"
  [{:db/ident :simulation/id
    :db/valueType :db.type/uuid
    :db/unique :db.unique/identity
    :db/cardinality :db.cardinality/one
    :db/doc "Unique identifier for simulation"}

   {:db/ident :simulation/name
    :db/valueType :db.type/string
    :db/unique :db.unique/value
    :db/cardinality :db.cardinality/one
    :db/doc "Unique name (e.g., \"acmedb\", \"example-bank\", \"my-sim\")"}

   {:db/ident :simulation/entity
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc "Optional link to :entity/id in knowledge graph"}

   {:db/ident :simulation/code
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Clojure source code (SCI-executable)"}

   {:db/ident :simulation/db-uri
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Datahike connection config for dedicated DB (as EDN string)"}

   {:db/ident :simulation/status
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc "Status: :draft :active :paused :archived"}

   {:db/ident :simulation/maintainer
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc "Agent-id responsible for this simulation (e.g., :sentinel)"}

   {:db/ident :simulation/budget
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc "Microdollars allocated per cycle"}

   {:db/ident :simulation/interval-ms
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc "How often the simulation runs (0 = manual only)"}

   {:db/ident :simulation/created-at
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc "When the simulation was created"}

   {:db/ident :simulation/updated-at
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc "Last update timestamp"}

   {:db/ident :simulation/version
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc "Incremented on each code update"}])

;; ============================================================================
;; Score Schema (emoji-based agent scoring for budget accountability)
;; ============================================================================

(def score-schema
  "Schema for scoring agent outputs via emoji reactions.
   Scores feed into budget adjustment: good agents get more budget."
  [{:db/ident :score/id
    :db/valueType :db.type/uuid
    :db/unique :db.unique/identity
    :db/cardinality :db.cardinality/one
    :db/doc "Unique identifier for score entry"}

   {:db/ident :score/agent-id
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc "Agent being scored"}

   {:db/ident :score/scorer-id
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc "Who scored (agent or :human)"}

   {:db/ident :score/emoji
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Reaction emoji (e.g., \"+1\", \"-1\", \"rocket\")"}

   {:db/ident :score/value
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc "Numeric value (+1, -1, +2 etc.)"}

   {:db/ident :score/message-ref
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Reference to the scored output (message-id as string)"}

   {:db/ident :score/created-at
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc "When the score was recorded"}])

;; ============================================================================
;; Page Capture Schema (browser extension intake)
;; ============================================================================

(def page-capture-schema
  "Schema for page captures from the Dvergr Feed browser extension.
   Stores raw page data for processing by agents."
  [{:db/ident :page-capture/id
    :db/valueType :db.type/uuid
    :db/unique :db.unique/identity
    :db/cardinality :db.cardinality/one
    :db/doc "Unique identifier for page capture"}

   {:db/ident :page-capture/url
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Full URL of captured page"}

   {:db/ident :page-capture/title
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Page title"}

   {:db/ident :page-capture/domain
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Domain extracted from URL (e.g. linkedin.com)"}

   {:db/ident :page-capture/text
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Page text content (truncated to 50K chars)"}

   {:db/ident :page-capture/meta
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Meta tags as EDN string"}

   {:db/ident :page-capture/raw-path
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Filesystem path to raw HTML capture (for re-processing)"}

   {:db/ident :page-capture/source
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Capture source (e.g. 'extension')"}

   {:db/ident :page-capture/timestamp
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc "When the page was captured"}])

;; ============================================================================
;; Full Schema
;; ============================================================================

(def full-schema
  "Complete chat schema for dvergr.
   Note: Web schema (web-result, web-page) is in dvergr.web.search."
  (vec (concat chat-schema
               participant-schema
               message-schema
               tool-call-schema
               accounting-schema
               ledger-schema
               budget-schema
               pricing-schema
               knowledge-schema
               task-schema
               room-schema
               proposal-schema
               simulation-schema
               score-schema
               page-capture-schema)))

;; ============================================================================
;; Schema Installation
;; ============================================================================

(defn install-schema!
  "Install the chat schema into a datahike connection."
  [conn]
  (d/transact conn full-schema))

(defn create-chat-db!
  "Create a new datahike database for a chat with schema installed.

   Installs:
   - Core chat schema (messages, participants, etc.)
   - Tool input schemas (auto-generated from tool registry)
   - Web search schema (for search results and fetched pages)

   Args:
     cfg - Datahike config (e.g., {:store {:backend :mem :id \"chat-123\"}})"
  [cfg]
  (when-not (d/database-exists? cfg)
    (d/create-database cfg))
  (let [conn (d/connect cfg)]
    ;; Install core schema
    (install-schema! conn)
    ;; Install all tool schemas from registry
    (when-let [registry (get-tool-registry)]
      (tool-schema/install-all-tool-schemas! conn registry))
    ;; Install web search schema
    (try
      (require 'dvergr.web.search)
      (let [ws-ns (find-ns 'dvergr.web.search)
            install-web-schema! (ns-resolve ws-ns 'install-web-schema!)]
        (when install-web-schema!
          (install-web-schema! conn)))
      (catch Exception e
        ;; Web search is optional, don't fail if not available
        nil))
    conn))

(defn ensure-full-schema!
  "Ensure full schema is installed on an existing connection.

   This is used when reusing a Yggdrasil-managed Datahike connection
   that may not have all schemas installed yet.

   Installs:
   - Core chat schema
   - Tool input schemas (from tool registry)
   - Web search schema
   - Knowledge schema (for compaction notes)

   Safe to call multiple times - Datahike ignores duplicate schema attributes."
  [conn]
  ;; Install core schema
  (install-schema! conn)
  ;; Install all tool schemas from registry
  (when-let [registry (get-tool-registry)]
    (tool-schema/install-all-tool-schemas! conn registry))
  ;; Install web search schema
  (try
    (require 'dvergr.web.search)
    (let [ws-ns (find-ns 'dvergr.web.search)
          install-web-schema! (ns-resolve ws-ns 'install-web-schema!)]
      (when install-web-schema!
        (install-web-schema! conn)))
    (catch Exception e
      ;; Web search is optional
      nil))
  ;; Install knowledge schema (for compaction note creation)
  (try
    (require 'dvergr.knowledge.schema)
    (let [ks-ns (find-ns 'dvergr.knowledge.schema)
          install-knowledge-schema! (ns-resolve ks-ns 'install-knowledge-schema!)]
      (when install-knowledge-schema!
        (install-knowledge-schema! conn)))
    (catch Exception e
      ;; Knowledge schema is optional
      nil))
  conn)

;; ============================================================================
;; Helper Functions
;; ============================================================================

(defn create-chat-entity
  "Create a chat entity map for transacting.

   Args:
     opts - Map with :id, :title, :admin-id, :budget, etc."
  [{:keys [id title description admin-id budget parent-id]}]
  (cond-> {:chat/id (or id (random-uuid))
           :chat/title (or title "Untitled Chat")
           :chat/status :active
           :chat/budget-total (or budget 0)
           :chat/budget-used 0
           :chat/created-at (java.util.Date.)
           :chat/updated-at (java.util.Date.)}
    description (assoc :chat/description description)
    admin-id (assoc :chat/admin [:participant/id admin-id])
    parent-id (assoc :chat/parent-id parent-id)))

(defn create-participant-entity
  "Create a participant entity map for transacting.

   Args:
     opts - Map with :name, :type, :permissions, :model, :provider, etc."
  [{:keys [name type permissions model provider budget]}]
  (cond-> {:participant/id (random-uuid)
           :participant/name name
           :participant/type (or type :sci-agent)}
    (seq permissions) (assoc :participant/permissions (set permissions))
    model (assoc :participant/model model)
    provider (assoc :participant/provider provider)
    budget (assoc :participant/budget budget)))

(defn- get-tool-registry
  "Dynamically get the tool registry to avoid circular deps.
   Returns the registry map (not the atom)."
  []
  (try
    (require 'dvergr.tools)
    ;; ns-resolve returns the var, @ gets the atom, @@ gets the map
    @@(ns-resolve 'dvergr.tools 'registry)
    (catch Exception _ nil)))

(defn- serialize-tool-use
  "Serialize a tool-use for Datahike storage.

   Converts :tool-use/input map to a structured entity using the tool's
   JSON Schema definition. All inputs become entities - no EDN strings.

   Throws if:
   - Tool is not registered (configuration error)
   - Schema conversion fails (schema mismatch)"
  [tool-use]
  (let [tool-name (:tool-use/name tool-use)
        input (:tool-use/input tool-use)
        registry (get-tool-registry)
        tool-def (get registry tool-name)]
    (when-not tool-def
      (throw (ex-info (str "Tool not registered: " tool-name
                           ". All tools must be registered before use.")
                      {:tool-name tool-name
                       :available-tools (keys registry)})))
    (when-not (map? input)
      (throw (ex-info (str "Tool input must be a map, got: " (type input))
                      {:tool-name tool-name
                       :input input})))
    (if-let [input-entity (tool-schema/tool-input->entity tool-def input)]
      (assoc tool-use :tool-use/input input-entity)
      (throw (ex-info (str "Failed to convert tool input to entity for: " tool-name)
                      {:tool-name tool-name
                       :input input
                       :parameters (:parameters tool-def)})))))

(defn create-message-entity
  "Create a message entity map for transacting.

   Args:
     opts - Map with :chat-id, :author-id, :role, :content, :tokens, :tool-uses, etc.

   Tool-uses are stored as component entities with input serialized to EDN.
   The :message/tool-uses key references the tool-use entities."
  [{:keys [chat-id author-id role content tokens important? compacted? reply-to-id tool-uses tool-use-id turn-number]}]
  (cond-> {:message/id (random-uuid)
           :message/chat [:chat/id chat-id]
           :message/role (or role :user)
           :message/content content
           :message/created-at (java.util.Date.)
           :message/compacted? false}
    author-id (assoc :message/author [:participant/id author-id])
    tokens (assoc :message/tokens (long tokens))
    important? (assoc :message/important? true)
    compacted? (assoc :message/compacted? true)
    reply-to-id (assoc :message/reply-to [:message/id reply-to-id])
    (seq tool-uses) (assoc :message/tool-uses (mapv serialize-tool-use tool-uses))
    tool-use-id (assoc :message/tool-use-id tool-use-id)
    turn-number (assoc :message/turn-number (long turn-number))))

;; ============================================================================
;; Tool Call Queries (for UI rendering)
;; ============================================================================

(defn get-tool-calls-for-message
  "Get tool-call analytics for a message's tool uses.
   Returns tool calls joined by tool-use-id.

   Useful for rendering tool call status, duration, and errors in the UI."
  [conn message-id]
  (let [db (d/db conn)
        ;; Get tool-use-ids from the message
        tool-use-ids (d/q '[:find [?tuid ...]
                            :in $ ?mid
                            :where
                            [?m :message/id ?mid]
                            [?m :message/tool-uses ?tu]
                            [?tu :tool-use/id ?tuid]]
                          db message-id)]
    (when (seq tool-use-ids)
      (vec (for [tuid tool-use-ids]
             (d/q '[:find (pull ?tc [:tool-call/name :tool-call/status :tool-call/approval
                                     :tool-call/duration-ms :tool-call/error?
                                     :tool-call/started-at]) .
                    :in $ ?tuid
                    :where [?tc :tool-call/tool-use-id ?tuid]]
                  db tuid))))))

(defn get-turn-summary
  "Get a summary of tool calls grouped by turn number for a chat.
   Returns [{:turn N :tool-calls [{:name :status :duration-ms :error?}]}]

   Useful for rendering turn boundaries and collapsed tool call groups."
  [conn chat-id]
  (let [db (d/db conn)
        ;; Get all assistant messages with tool uses, grouped by turn
        messages (d/q '[:find [(pull ?m [:message/id :message/turn-number
                                         {:message/tool-uses [:tool-use/id :tool-use/name]}]) ...]
                        :in $ ?cid
                        :where
                        [?m :message/chat ?c]
                        [?c :chat/id ?cid]
                        [?m :message/role :assistant]
                        [?m :message/tool-uses _]]
                      db chat-id)]
    (->> messages
         (sort-by :message/turn-number)
         (mapv (fn [msg]
                 {:turn (:message/turn-number msg)
                  :message-id (:message/id msg)
                  :tool-calls (vec (for [tu (:message/tool-uses msg)
                                         :let [tc (d/q '[:find (pull ?tc [:tool-call/name :tool-call/status
                                                                          :tool-call/duration-ms :tool-call/error?]) .
                                                         :in $ ?tuid
                                                         :where [?tc :tool-call/tool-use-id ?tuid]]
                                                       db (:tool-use/id tu))]]
                                     (or tc {:tool-call/name (:tool-use/name tu)
                                             :tool-call/status :unknown})))})))))

(defn account-usage!
  "Record a resource usage in the accounting table.

   Args:
     conn - Datahike connection
     chat-id - UUID of the chat
     type - Resource type keyword
     amount - Amount consumed
     opts - Optional :message-id, :metadata"
  [conn chat-id type amount & {:keys [message-id metadata]}]
  (let [entry (cond-> {:account/id (random-uuid)
                       :account/chat [:chat/id chat-id]
                       :account/type type
                       :account/amount amount
                       :account/timestamp (java.util.Date.)}
                message-id (assoc :account/message [:message/id message-id])
                metadata (assoc :account/metadata (pr-str metadata)))]
    (d/transact conn [entry
                      ;; Also update chat's budget-used
                      {:db/id [:chat/id chat-id]
                       :chat/budget-used (d/q '[:find ?used .
                                                :in $ ?cid
                                                :where [?c :chat/id ?cid]
                                                [?c :chat/budget-used ?used]]
                                              @conn chat-id)}])))

;; ============================================================================
;; Knowledge Entity Helpers
;; ============================================================================

(defn create-entity
  "Create or update a knowledge entity from wiki-link data.

   If entity with title exists, updates contexts and mention count.
   Otherwise creates new entity.

   Args:
     conn - Datahike connection
     title - Entity title from [[Title]]
     contexts - Vector of context strings
     session-id - UUID of session that mentioned this"
  [conn title contexts session-id]
  (let [existing (d/q '[:find [?e ?mc]
                        :in $ ?t
                        :where
                        [?e :entity/title ?t]
                        [?e :entity/mention-count ?mc]]
                      @conn title)]
    (if existing
      ;; Update existing entity
      (let [[eid mention-count] existing]
        (d/transact conn [(cond-> {:db/id eid
                                   :entity/mention-count (inc mention-count)
                                   :entity/updated-at (java.util.Date.)}
                           (seq contexts) (update :entity/contexts
                                                  (fnil into [])
                                                  contexts)
                           session-id (update :entity/from-sessions
                                              (fnil conj [])
                                              session-id))]))
      ;; Create new entity
      (d/transact conn [{:entity/id (random-uuid)
                         :entity/title title
                         :entity/contexts (vec contexts)
                         :entity/from-sessions (if session-id [session-id] [])
                         :entity/mention-count 1
                         :entity/created-at (java.util.Date.)
                         :entity/updated-at (java.util.Date.)}]))))

(defn get-entity-by-title
  "Get entity by title, returns nil if not found."
  [conn title]
  (d/q '[:find (pull ?e [*]) .
         :in $ ?t
         :where [?e :entity/title ?t]]
       @conn title))

(defn list-entities
  "List all entities, optionally filtered.

   Options:
   - :limit - Max entities to return
   - :order-by - :mention-count, :updated-at, :title"
  [conn & {:keys [limit order-by]
           :or {limit 100
                order-by :mention-count}}]
  (let [entities (d/q '[:find [(pull ?e [*]) ...]
                        :where [?e :entity/id _]]
                      @conn)]
    (->> entities
         (sort-by (case order-by
                    :mention-count #(- (or (:entity/mention-count %) 0))
                    :updated-at #(- (.getTime (or (:entity/updated-at %) (java.util.Date. 0))))
                    :title :entity/title))
         (take limit))))

(defn list-entities-by-type
  "List entities filtered by type.

   Args:
   - conn  - Datahike connection
   - type  - Keyword type (:competitor, :client, :partner, :project, :technology)"
  [conn entity-type & {:keys [limit] :or {limit 100}}]
  (let [entities (d/q '[:find [(pull ?e [*]) ...]
                         :in $ ?type
                         :where [?e :entity/id _]
                                [?e :entity/type ?type]]
                       @conn entity-type)]
    (->> entities
         (sort-by #(- (or (:entity/mention-count %) 0)))
         (take limit))))

(defn link-entities
  "Create a link between two entities.

   Args:
     conn - Datahike connection
     from-title - Source entity title
     to-title - Target entity title"
  [conn from-title to-title]
  (let [from-id (d/q '[:find ?e .
                       :in $ ?t
                       :where [?e :entity/title ?t]]
                     @conn from-title)
        to-id (d/q '[:find ?e .
                     :in $ ?t
                     :where [?e :entity/title ?t]]
                   @conn to-title)]
    (when (and from-id to-id)
      (d/transact conn [[:db/add from-id :entity/links to-id]]))))

(comment
  ;; Example usage:

  ;; Create in-memory database
  (def cfg {:store {:backend :mem :id "test-chat"}})
  (def conn (create-chat-db! cfg))

  ;; Create a human participant
  (def human (create-participant-entity
              {:name "User"
               :type :human
               :permissions #{:spawn-chat :stop-chat}}))
  (d/transact conn [human])

  ;; Create an agent participant
  (def agent (create-participant-entity
              {:name "Claude"
               :type :privileged-agent
               :model "claude-sonnet-4-20250514"
               :provider :anthropic
               :permissions #{:spawn-chat :write-file :shell}}))
  (d/transact conn [agent])

  ;; Create a chat
  (def chat (create-chat-entity
             {:title "Implement authentication"
              :admin-id (:participant/id human)
              :budget 10000}))
  (d/transact conn [chat])

  ;; Add participants to chat
  (d/transact conn [{:db/id [:chat/id (:chat/id chat)]
                     :chat/participants [[:participant/id (:participant/id human)]
                                         [:participant/id (:participant/id agent)]]}])

  ;; Create a message
  (def msg (create-message-entity
            {:chat-id (:chat/id chat)
             :author-id (:participant/id human)
             :role :user
             :content "Please implement JWT authentication for the API."
             :tokens 15}))
  (d/transact conn [msg])

  ;; Query messages in chat
  (d/q '[:find [(pull ?m [*]) ...]
         :in $ ?cid
         :where
         [?m :message/chat ?c]
         [?c :chat/id ?cid]]
       @conn (:chat/id chat))
  )
