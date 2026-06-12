(ns dvergr.model.api.openai
  "OpenAI Chat Completions API implementation.

   Also used by OpenAI-compatible providers like Fireworks, Together, etc."
  (:require [dvergr.model.provider :as p]
            [dvergr.model.registry :as registry]
            [dvergr.model.quirks :as quirks]
            [jsonista.core :as json]
            [clojure.string :as str]))

;; ============================================================================
;; Message Formatting
;; ============================================================================

(defn- format-message
  "Format a single message for OpenAI API.
   Preserves tool_calls on assistant messages and tool_call_id on tool messages."
  [msg]
  (let [role (:role msg)
        role-str (if (keyword? role) (name role) role)]
    (cond-> {:role role-str
             :content (:content msg)}
      ;; Preserve tool_calls on assistant messages
      (:tool_calls msg) (assoc :tool_calls (:tool_calls msg))
      ;; Preserve tool_call_id on tool messages
      (:tool_call_id msg) (assoc :tool_call_id (:tool_call_id msg))
      ;; Preserve interleaved-thinking state fed back to the model
      (:reasoning_content msg) (assoc :reasoning_content (:reasoning_content msg)))))

(defn- with-system
  "Prepend a system message from the `:system` opt when one isn't already present
   in the list. Parity with the Anthropic provider (which reads `:system` from
   opts); OpenAI-compatible APIs take the system prompt as the first message."
  [messages system]
  (if (and system (not (str/blank? system))
           (not (some #(let [r (:role %)] (= "system" (if (keyword? r) (name r) r))) messages)))
    (into [{:role "system" :content system}] (vec messages))
    (vec messages)))

(defn- format-messages
  "Format messages for OpenAI API."
  [messages]
  (mapv format-message messages))

;; ============================================================================
;; Provider Record
;; ============================================================================

(defrecord OpenAIProvider [config]
  p/LLMProvider

  (provider-id [_]
    (or (:provider-id config) :openai))

  (api-type [_] :openai-chat)

  (build-request [_ messages opts]
    (let [tools (:tools opts)]
      {:url (str (or (:base-url config) "https://api.openai.com/v1") "/chat/completions")
       :headers (merge {"Authorization" (str "Bearer " (:api-key config))
                        "Content-Type" "application/json"}
                       (:extra-headers config))
       :body (cond-> {:model (:model opts "gpt-4o")
                      :max_completion_tokens (:max-tokens opts 8192)
                      :stream true
                      :stream_options {:include_usage true}
                      :messages (format-messages (with-system messages (:system opts)))}
               ;; Temperature if specified (some models like Kimi K2.5 require specific values)
               (:temperature opts) (assoc :temperature (:temperature opts))
               ;; Top-p if specified (Kimi / MiniMax M2 use 0.95)
               (:top-p opts) (assoc :top_p (:top-p opts))
               ;; Top-k if specified — a Fireworks extension param (MiniMax M2: 40)
               (:top-k opts) (assoc :top_k (:top-k opts))
               (seq tools) (assoc :tools (p/format-tools _ tools)))}))

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
     :kimi-tool-leak? (boolean (registry/get-quirk (:id model-def) :kimi-tool-id-format?))})

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
          text-content (cond-> (->> completed
                                    (filter #(= :text (:type %)))
                                    (map :content)
                                    (apply str))
                         ;; Kimi-only: strip raw tool-call tokens Fireworks leaked
                         ;; into content (see quirks/strip-kimi-tool-tokens).
                         (:kimi-tool-leak? state) quirks/strip-kimi-tool-tokens)
          tool-calls (->> completed
                          (filter #(= :tool_use (:type %)))
                          (mapv (fn [tc]
                                  {:id (:id tc)
                                   :name (:name tc)
                                   :input (:input tc)})))]
      {:content text-content
       :reasoning (:reasoning state)
       :tool-calls (when (seq tool-calls) tool-calls)
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
    (let [messages (if (registry/has-quirk? model :kimi-tool-id-format?)
                     (quirks/rewrite-kimi-tool-ids messages)
                     messages)
          strip-ns (fn [m]
                     (when (map? m)
                       (into {} (map (fn [[k v]] [(if (keyword? k) (keyword (name k)) k) v]) m))))]
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
                                                   {:id (:tool-use/id tu)
                                                    :type "function"
                                                    :function {:name (:tool-use/name tu)
                                                               :arguments (json/write-value-as-string
                                                                           (strip-ns (:tool-use/input tu)))}})
                                                 tool-uses)}
                        (seq reasoning) (assoc :reasoning_content reasoning))
                      (cond-> {:role (name role)
                               :content (:message/content msg)}
                        (and (= role :assistant) (seq reasoning))
                        (assoc :reasoning_content reasoning)))))))
            messages))))

;; ============================================================================
;; Constructors
;; ============================================================================

(defn create
  "Create an OpenAI provider instance.

   Config options:
   - :api-key       - OpenAI API key (required, or from env)
   - :base-url      - API base URL (default: https://api.openai.com/v1)
   - :provider-id   - Override provider ID (default: :openai)
   - :extra-headers - Additional HTTP headers"
  [config]
  (let [api-key (or (:api-key config)
                    (System/getenv "OPENAI_API_KEY"))]
    (when-not api-key
      (throw (ex-info "OpenAI API key required" {:env "OPENAI_API_KEY"})))
    (->OpenAIProvider (assoc config :api-key api-key))))

(defn create-if-available
  "Create OpenAI provider if API key is available, otherwise nil."
  [config]
  (when (or (:api-key config) (System/getenv "OPENAI_API_KEY"))
    (create config)))

;; ============================================================================
;; Fireworks Provider (OpenAI-compatible)
;; ============================================================================

(defn create-fireworks
  "Create a Fireworks provider (uses OpenAI-compatible API).

   Config options:
   - :api-key       - Fireworks API key (or from env)
   - :base-url      - API base URL (default: Fireworks endpoint)
   - :extra-headers - Additional HTTP headers"
  [config]
  (let [api-key (or (:api-key config)
                    (System/getenv "FIREWORKS_API_KEY")
                    (System/getenv "OPENAI_API_KEY"))
        base-url (or (:base-url config)
                     (System/getenv "OPENAI_BASE_URL")
                     "https://api.fireworks.ai/inference/v1")]
    (when-not api-key
      (throw (ex-info "Fireworks API key required"
                      {:env ["FIREWORKS_API_KEY" "OPENAI_API_KEY"]})))
    (->OpenAIProvider (assoc config
                             :api-key api-key
                             :base-url base-url
                             :provider-id :fireworks))))

(defn create-fireworks-if-available
  "Create Fireworks provider if API key is available, otherwise nil."
  [config]
  (when (or (:api-key config)
            (System/getenv "FIREWORKS_API_KEY")
            (System/getenv "OPENAI_API_KEY"))
    (create-fireworks config)))
