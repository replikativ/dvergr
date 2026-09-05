(ns dvergr.model.api.openai
  "OpenAI Chat Completions API implementation.

   Also used by OpenAI-compatible providers like Fireworks, Together, etc."
  (:require [dvergr.model.provider :as p]
            [dvergr.model.gateway :as gateway]
            [dvergr.model.registry :as registry]
            [dvergr.model.quirks :as quirks]
            [dvergr.chat.tool-schema :as tool-schema]
            [jsonista.core :as json]
            [clojure.string :as str]))

;; ============================================================================
;; Message Formatting
;; ============================================================================

(defn- format-message
  "Format a single message for OpenAI API.
   Preserves tool_calls on assistant messages and tool_call_id on tool messages."
  [msg instruction-role]
  (let [role (:role msg)
        role-str (if (keyword? role) (name role) role)
        role-str (if (= "system" role-str) instruction-role role-str)]
    (cond-> {:role role-str
             :content (:content msg)}
      ;; Preserve tool_calls on assistant messages
      (:tool_calls msg) (assoc :tool_calls (:tool_calls msg))
      ;; Preserve tool_call_id on tool messages
      (:tool_call_id msg) (assoc :tool_call_id (:tool_call_id msg))
      ;; Preserve interleaved-thinking state fed back to the model
      (:reasoning_content msg) (assoc :reasoning_content (:reasoning_content msg)))))

(defn- with-instructions
  "Prepend product instructions from `:system` when the message list does not
   already contain an instruction message. Native o1-and-newer OpenAI models use
   `developer`; compatible endpoints retain the broadly supported `system` role."
  [messages system instruction-role]
  (if (and system (not (str/blank? system))
           (not (some #(let [r (:role %)
                             r (if (keyword? r) (name r) r)]
                         (#{"system" "developer"} r))
                      messages)))
    (into [{:role instruction-role :content system}] (vec messages))
    (vec messages)))

(defn- format-messages
  "Format messages for OpenAI API."
  [messages instruction-role]
  (mapv #(format-message % instruction-role) messages))

(defn- product-instruction-role
  "Wire role for product instructions. This is model metadata, not an ID guess,
   and is native-only because compatible services need not implement OpenAI's
   o1-and-newer developer-message contract."
  [config model]
  (if (:native-openai? config)
    (name (registry/instruction-role model))
    "system"))

;; ============================================================================
;; Provider Record
;; ============================================================================

(defrecord OpenAIProvider [config]
  p/LLMProvider

  (provider-id [_]
    (or (:provider-id config) :openai))

  (api-type [_] :openai-chat)

  (build-request [_ messages opts]
    (let [tools (:tools opts)
          instruction-role (product-instruction-role config (:model opts))
          ;; GPT-5.6: /v1/chat/completions refuses function tools unless
          ;; reasoning_effort is "none" — "To use function tools, use
          ;; /v1/responses or set reasoning_effort to 'none'". Sending it
          ;; buys tool use at the cost of server-side reasoning; a model
          ;; that needs both belongs on the Responses API, which this
          ;; provider does not speak. Only for models carrying the quirk:
          ;; gpt-5.5 and older take tools and reasoning together, and
          ;; forcing "none" there would quietly make them dumber. Compatible
          ;; endpoints do not get the native workaround merely because they
          ;; share this adapter.
          effort-none? (and (:native-openai? config)
                            (seq tools)
                            (registry/get-quirk (:model opts) :chat-tools-need-effort-none?))]
      {:url (str (or (:base-url config) "https://api.openai.com/v1") "/chat/completions")
       :headers (merge {"Content-Type" "application/json"}
                       (:extra-headers config))
       :credentials (:credentials config)
       :body (cond-> {:model (:model opts "gpt-4o")
                      :max_completion_tokens (:max-tokens opts 8192)
                      :stream true
                      :stream_options {:include_usage true}
                      :messages (format-messages
                                 (with-instructions messages (:system opts) instruction-role)
                                 instruction-role)}
               ;; Temperature if specified (some models like Kimi K2.5 require specific values)
               (:temperature opts) (assoc :temperature (:temperature opts))
               ;; Top-p if specified (Kimi / MiniMax M2 use 0.95)
               (:top-p opts) (assoc :top_p (:top-p opts))
               ;; Top-k if specified — a Fireworks extension param (MiniMax M2: 40)
               (:top-k opts) (assoc :top_k (:top-k opts))
               (seq tools) (assoc :tools (p/format-tools _ tools))
               effort-none? (assoc :reasoning_effort "none"))}))

  (create-accumulator [_ model-def]
    {:current-blocks {}
     :completed []
     :usage {:input-tokens 0 :output-tokens 0}
     :stop-reason nil
     :model nil
     :id nil
     ;; Model-specific quirk handling
     :tool-id-continuation? (registry/get-quirk (:id model-def) :tool-id-in-every-chunk?)
     ;; Kimi-thinking models leak raw tool-call tokens into content on Fireworks;
     ;; clean them in extract-response (Kimi only — see quirks/strip-kimi-tool-tokens).
     :kimi-tool-leak? (boolean (registry/get-quirk (:id model-def) :kimi-tool-id-format?))
     ;; GLM models leak their <arg_key>/<arg_value> tool envelope into content on
     ;; Fireworks; recover the call + scrub the text in extract-response (GLM only).
     :glm-tool-leak? (boolean (registry/get-quirk (:id model-def) :glm-tool-format?))})

  (accumulate-event [_ state event-type event-data model-def]
    ;; OpenAI doesn't have explicit event types - process based on content
    (let [;; First process choices if present
          state (if-let [choices (:choices event-data)]
                  (reduce
                   (fn [s choice]
                     (let [idx (:index choice)
                           delta (:delta choice)
                           finish (:finish_reason choice)]
                       (cond-> s
                          ;; Text content
                         (:content delta)
                         (update-in [:current-blocks idx]
                                    (fn [block]
                                      (-> (or block {:type :text :content ""})
                                          (update :content str (:content delta)))))

                          ;; Reasoning content (interleaved thinking:
                          ;; DeepSeek-R1 / MiniMax-M2 / Kimi). Accumulated
                          ;; separately so it can be fed back next turn as
                          ;; `reasoning_content` — these models degrade badly
                          ;; when prior-round thinking state isn't preserved.
                         (:reasoning_content delta)
                         (update :reasoning (fnil str "") (:reasoning_content delta))

                          ;; Tool calls - handle Kimi K2 quirk
                         (:tool_calls delta)
                         (as-> s'
                               (reduce
                                (fn [s'' tc]
                                  (let [tc-idx (:index tc)
                                        key [idx tc-idx]
                                        existing (get-in s'' [:current-blocks key])]
                                    ;; Key fix for Kimi K2: check for existing block,
                                    ;; not just :id presence (Kimi sends :id in every chunk)
                                    (if existing
                                      ;; Continuation - append arguments, update name if we get it
                                      (-> s''
                                          (update-in [:current-blocks key :content]
                                                     str (get-in tc [:function :arguments] ""))
                                          (cond-> (get-in tc [:function :name])
                                            (assoc-in [:current-blocks key :name]
                                                      (get-in tc [:function :name]))))
                                      ;; New tool call
                                      ;; Note: Kimi K2 returns IDs with leading space, so we trim
                                      (assoc-in s'' [:current-blocks key]
                                                {:type :tool_use
                                                 :id (some-> (:id tc) str/trim)
                                                 :name (get-in tc [:function :name])
                                                 :content (or (get-in tc [:function :arguments]) "")}))))
                                s'
                                (:tool_calls delta)))

                          ;; Finish - parse all tool calls and move to completed
                         finish
                         (-> (assoc :stop-reason (keyword finish))
                             (as-> s'
                                   (reduce-kv
                                    (fn [acc k block]
                                      (let [block (if (= :tool_use (:type block))
                                                    (try
                                                      (assoc block :input
                                                             (json/read-value (:content block)
                                                                              json/keyword-keys-object-mapper))
                                                      (catch Exception e
                                                        (assoc block :parse-error (.getMessage e))))
                                                    block)]
                                        (update acc :completed conj block)))
                                    s'
                                    (:current-blocks s')))
                             (assoc :current-blocks {})))))
                   state
                   choices)
                  state)]
      ;; Always check for usage (may be in same event as choices for some providers)
      (if-let [usage (:usage event-data)]
        (-> state
            (assoc-in [:usage :input-tokens] (:prompt_tokens usage))
            (assoc-in [:usage :output-tokens] (:completion_tokens usage))
            (cond-> (:model event-data) (assoc :model (:model event-data)))
            (cond-> (:id event-data) (assoc :id (:id event-data))))
        state)))

  (extract-response [_ state]
    (let [completed (:completed state)
          raw-content (->> completed
                           (filter #(= :text (:type %)))
                           (map :content)
                           (apply str))
          text-content (cond-> raw-content
                         ;; Kimi-only: strip raw tool-call tokens Fireworks leaked
                         ;; into content (see quirks/strip-kimi-tool-tokens).
                         (:kimi-tool-leak? state) quirks/strip-kimi-tool-tokens
                         ;; GLM-only: scrub the leaked <arg_key>/<arg_value> envelope.
                         (:glm-tool-leak? state) quirks/strip-glm-tool-tokens)
          structured (->> completed
                          (filter #(= :tool_use (:type %)))
                          (mapv (fn [tc]
                                  {:id (:id tc)
                                   :name (:name tc)
                                   :input (:input tc)}))
                          ;; GLM-only: the <arg_key> envelope sometimes leaks
                          ;; INTO the structured name field — repair it or the
                          ;; invalid name poisons the replayed history.
                          ((fn [calls]
                             (if (:glm-tool-leak? state)
                               (mapv quirks/sanitize-glm-structured-call calls)
                               calls))))
          ;; GLM leak RECOVERY: when Fireworks dumped the tool call into content
          ;; instead of tool_calls, parse it back into an executable call so the
          ;; turn continues (rather than dying on a garbage message). Only when
          ;; nothing structured came through, to avoid double-firing.
          recovered (when (and (:glm-tool-leak? state) (empty? structured))
                      (some->> (quirks/parse-glm-tool-calls raw-content)
                               (map-indexed (fn [i c]
                                              {:id (str "glm-recovered-" i)
                                               :name (:name c)
                                               :input (:input c)}))
                               vec))
          tool-calls (or (seq structured) (seq recovered))]
      {:content text-content
       :reasoning (:reasoning state)
       :tool-calls (when tool-calls (vec tool-calls))
       :usage (:usage state)
       :stop-reason (:stop-reason state)
       :model (:model state)
       :id (:id state)}))

  p/ToolFormatter

  (format-tools [_ tools]
    (mapv (fn [{:keys [name description parameters]}]
            {:type "function"
             :function {:name name
                        :description description
                        :parameters parameters}})
          tools))

  p/MessageFormatter

  (format-messages [_ messages model]
    ;; OpenAI/Fireworks: tool results as separate "tool" messages with tool_call_id
    ;; Kimi K2 quirk: rewrite tool IDs to functions.{name}:{idx}
    (let [instruction-role (product-instruction-role config model)
          messages (if (registry/has-quirk? model :kimi-tool-id-format?)
                     (quirks/rewrite-kimi-tool-ids messages)
                     messages)]
      (mapv (fn [msg]
              (let [role (:message/role msg)]
                (if (= role :tool-result)
                  {:role "tool"
                   :tool_call_id (:message/tool-use-id msg)
                   :content (:message/content msg)}
                  (let [tool-uses (:message/tool-uses msg)
                        ;; Feed prior-round reasoning back to interleaved-thinking
                        ;; models (MiniMax-M2 / Kimi / DeepSeek-R1) so they keep
                        ;; their thinking state across turns.
                        reasoning (:message/reasoning msg)]
                    (if (and (= role :assistant) (seq tool-uses))
                      (cond-> {:role "assistant"
                               :content (:message/content msg)
                               :tool_calls (mapv (fn [tu]
                                                   ;; Replay guards: clean-tool-name strips a
                                                   ;; leaked envelope off a poisoned durable name;
                                                   ;; input-entity->args round-trips a raw-EDN
                                                   ;; fallback entity and never yields nil —
                                                   ;; "arguments": "null" is a hard 400.
                                                   {:id (:tool-use/id tu)
                                                    :type "function"
                                                    :function {:name (quirks/clean-tool-name (:tool-use/name tu))
                                                               :arguments (json/write-value-as-string
                                                                           (tool-schema/input-entity->args (:tool-use/input tu)))}})
                                                 tool-uses)}
                        (seq reasoning) (assoc :reasoning_content reasoning))
                      (cond-> {:role (if (= role :system)
                                       instruction-role
                                       (name role))
                               :content (:message/content msg)}
                        (and (= role :assistant) (seq reasoning))
                        (assoc :reasoning_content reasoning)))))))
            messages))))

;; ============================================================================
;; Constructors
;; ============================================================================

(def ^:private default-openai-base-url "https://api.openai.com/v1")
(def ^:private default-fireworks-base-url "https://api.fireworks.ai/inference/v1")

(defn- normalize-base-url [base-url]
  (str/replace base-url #"/+$" ""))

(defn- system-env [env-key]
  (System/getenv env-key))

(defn create
  "Create an OpenAI provider instance.

   Config options:
   - :api-key       - OpenAI API key (required, or from env)
   - :base-url      - API base URL (default: https://api.openai.com/v1)
   - :provider-id   - Override provider ID (default: :openai)
   - :extra-headers - Additional HTTP headers"
  ([config]
   (create config system-env))
  ([config env-lookup]
   (let [api-key (or (:api-key config)
                     (env-lookup "OPENAI_API_KEY"))
         custom-base-url (or (:base-url config)
                             (env-lookup "OPENAI_BASE_URL"))
         base-url (normalize-base-url
                   (or custom-base-url default-openai-base-url))
         provider-id (or (:provider-id config) :openai)
         credentials (or (:credentials config)
                         (when api-key
                           (gateway/static-credentials
                            :openai-api-key
                            {"Authorization" (str "Bearer " api-key)}
                            #{(gateway/request-origin base-url)})))]
     (when-not credentials
       (throw (ex-info "OpenAI API key required" {:env "OPENAI_API_KEY"})))
     (->OpenAIProvider
      (-> config
          (dissoc :api-key)
          (assoc :base-url base-url
                 :provider-id provider-id
                 ;; The documented explicit canonical URL is semantically the same
                 ;; as omitting it. Other URLs are compatible endpoints and do not
                 ;; inherit OpenAI-specific request roles or model workarounds.
                 :native-openai? (and (= :openai provider-id)
                                      (= default-openai-base-url base-url))
                 :credentials credentials))))))

(defn create-if-available
  "Create OpenAI provider if API key is available, otherwise nil."
  ([config]
   (create-if-available config system-env))
  ([config env-lookup]
   (when (or (:credentials config)
             (:api-key config)
             (env-lookup "OPENAI_API_KEY"))
     (create config env-lookup))))

;; ============================================================================
;; Fireworks Provider (OpenAI-compatible)
;; ============================================================================

(defn create-fireworks
  "Create a Fireworks provider (uses OpenAI-compatible API).

   Config options:
   - :api-key       - Fireworks API key (or from env)
   - :base-url      - API base URL (default: Fireworks endpoint)
   - :extra-headers - Additional HTTP headers"
  ([config]
   (create-fireworks config system-env))
  ([config env-lookup]
   (let [api-key (or (:api-key config)
                     (env-lookup "FIREWORKS_API_KEY"))
         base-url (normalize-base-url
                   (or (:base-url config)
                       (env-lookup "FIREWORKS_BASE_URL")
                       default-fireworks-base-url))
         credentials (or (:credentials config)
                         (when api-key
                           (gateway/static-credentials
                            :fireworks-api-key
                            {"Authorization" (str "Bearer " api-key)}
                            #{(gateway/request-origin base-url)})))]
     (when-not credentials
       (throw (ex-info "Fireworks API key required"
                       {:env "FIREWORKS_API_KEY"})))
     (->OpenAIProvider
      (-> config
          (dissoc :api-key)
          (assoc :credentials credentials
                 :base-url base-url
                 :provider-id :fireworks
                 :native-openai? false))))))

(defn create-fireworks-if-available
  "Create Fireworks provider if API key is available, otherwise nil."
  ([config]
   (create-fireworks-if-available config system-env))
  ([config env-lookup]
   (when (or (:credentials config)
             (:api-key config)
             (env-lookup "FIREWORKS_API_KEY"))
     (create-fireworks config env-lookup))))
