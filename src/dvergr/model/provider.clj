(ns dvergr.model.provider
  "Protocol definitions for LLM providers.

   Providers implement these protocols to integrate with the unified chat interface.
   This allows adding new providers without modifying core code.")

;; ============================================================================
;; Core Provider Protocol
;; ============================================================================

(defprotocol LLMProvider
  "Protocol for LLM provider implementations.

   Each provider (Anthropic, OpenAI, Fireworks, etc.) implements this protocol
   to handle the specifics of their API."

  (provider-id [this]
    "Return the provider keyword identifier, e.g. :anthropic, :openai, :fireworks")

  (api-type [this]
    "Return the API type keyword, e.g. :anthropic-messages, :openai-chat")

  (build-request [this messages opts]
    "Build the HTTP request map for this provider.

     Args:
       messages - Vector of message maps {:role :content ...}
       opts     - Options map with :model, :max-tokens, :tools, etc.

     Returns:
       {:url     \"https://...\"
        :headers {\"Content-Type\" \"application/json\" ...}
        :body    {...request body as map...}}")

  (create-accumulator [this model-def]
    "Create the initial state for SSE event accumulation.

     Args:
       model-def - Model definition from registry (for quirk handling)

     Returns:
       Initial accumulator state map")

  (accumulate-event [this state event-type event-data model-def]
    "Process one SSE event and update accumulator state.

     Args:
       state      - Current accumulator state
       event-type - Event type string (e.g. \"content_block_delta\")
       event-data - Parsed event data map
       model-def  - Model definition for quirk handling

     Returns:
       Updated accumulator state")

  (extract-response [this state]
    "Extract the final response from accumulated state.

     Args:
       state - Final accumulator state after all events

     Returns:
       {:content    \"text response\"
        :tool-calls [{:id :name :input} ...]
        :usage      {:input-tokens N :output-tokens M}
        :stop-reason :end-turn | :tool-use | :max-tokens | ...}"))

;; ============================================================================
;; Tool Formatting Protocol
;; ============================================================================

(defprotocol ToolFormatter
  "Protocol for formatting tool definitions per provider.

   Different providers expect different tool definition formats."

  (format-tools [this tools]
    "Format tool definitions for this provider's API.

     Args:
       tools - Vector of tool maps {:name :description :parameters}

     Returns:
       Provider-specific tool definitions"))

;; ============================================================================
;; Optional: Thinking/Reasoning Support
;; ============================================================================

(defprotocol ThinkingSupport
  "Protocol for providers that support extended thinking/reasoning.

   Optional - only implement if the provider supports thinking."

  (thinking-params [this budget-tokens]
    "Return request parameters to enable thinking with given token budget.

     Args:
       budget-tokens - Max tokens for thinking

     Returns:
       Map to merge into request body")

  (extract-thinking [this response]
    "Extract thinking content from a response.

     Args:
       response - Extracted response map

     Returns:
       Thinking content string or nil"))

;; ============================================================================
;; Optional: Vision Support
;; ============================================================================

(defprotocol VisionSupport
  "Protocol for providers that support image inputs.

   Optional - only implement if the provider supports vision."

  (format-image-content [this image-data mime-type]
    "Format image data for inclusion in a message.

     Args:
       image-data - Base64 encoded image data or URL
       mime-type  - MIME type string (e.g. \"image/png\")

     Returns:
       Provider-specific image content block"))

;; ============================================================================
;; Optional: Cache Control Support
;; ============================================================================

(defprotocol CacheControlSupport
  "Protocol for providers that support prompt caching.

   Optional - only implement if the provider supports caching."

  (apply-cache-control [this messages]
    "Apply cache control markers to messages.

     Args:
       messages - Vector of messages

     Returns:
       Messages with cache control applied"))

;; ============================================================================
;; Optional: Message Formatting (tool call conventions)
;; ============================================================================

(defprotocol MessageFormatter
  "Protocol for converting internal chat messages to provider-specific API format.

   This covers the full tool call convention for a provider/model:
   - How assistant messages with tool calls are structured
   - How tool results are formatted in the message history
   - Provider-specific quirks (e.g., Kimi K2 tool ID rewriting)

   When not implemented, falls back to Anthropic-style formatting in agent.clj."

  (format-messages [this messages model]
    "Convert chat context messages to API message format.

     Args:
       messages - Vector of internal messages with :message/role, :message/content,
                  :message/tool-uses, :message/tool-use-id, etc.
       model    - Model ID string (for quirk lookup)

     Returns:
       Vector of API-format messages ready for the provider."))

;; ============================================================================
;; Optional: Direct Chat (bypass HTTP+SSE)
;; ============================================================================

(defprotocol DirectChat
  "Protocol for providers that handle chat directly (e.g., CLI-based).

   When implemented, the chat function bypasses the HTTP+SSE streaming
   path and delegates entirely to the provider."

  (direct-chat [this messages opts]
    "Execute a chat completion directly, returning the standard response map.

     Args:
       messages - Vector of message maps {:role :content ...}
       opts     - Options map with :model, :max-tokens, :system, :tools, :on-text, etc.

     Returns:
       {:content    \"text response\"
        :tool-calls [{:id :name :input} ...]
        :usage      {:input-tokens N :output-tokens M}
        :stop-reason :end-turn | :tool-use | :max-tokens | ...}"))

;; ============================================================================
;; Helpers for Protocol Implementation
;; ============================================================================

(defn implements-direct-chat?
  "Check if a provider implements DirectChat (bypasses HTTP+SSE)."
  [provider]
  (satisfies? DirectChat provider))

(defn implements-thinking?
  "Check if a provider implements ThinkingSupport."
  [provider]
  (satisfies? ThinkingSupport provider))

(defn implements-vision?
  "Check if a provider implements VisionSupport."
  [provider]
  (satisfies? VisionSupport provider))

(defn implements-cache-control?
  "Check if a provider implements CacheControlSupport."
  [provider]
  (satisfies? CacheControlSupport provider))

(defn implements-message-formatter?
  "Check if a provider implements MessageFormatter."
  [provider]
  (satisfies? MessageFormatter provider))
