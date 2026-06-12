(ns dvergr.model.api.anthropic
  "Anthropic Messages API implementation."
  (:require [dvergr.model.provider :as p]
            [jsonista.core :as json]))

;; ============================================================================
;; Message Formatting
;; ============================================================================

(defn- extract-system
  "Extract system prompt from messages or opts."
  [messages opts]
  (or (:system opts)
      (when-let [sys (first (filter #(= "system" (:role %)) messages))]
        (:content sys))))

(defn- remove-system-messages
  "Remove system messages from message list."
  [messages]
  (filterv #(not= "system" (:role %)) messages))

(defn- format-message
  "Format a single message for Anthropic API."
  [msg]
  (let [role (:role msg)
        content (:content msg)]
    {:role (if (keyword? role) (name role) role)
     :content content}))

(defn- format-messages
  "Format messages for Anthropic API."
  [messages]
  (mapv format-message messages))

;; ============================================================================
;; Provider Record
;; ============================================================================

(defrecord AnthropicProvider [config]
  p/LLMProvider

  (provider-id [_] :anthropic)

  (api-type [_] :anthropic-messages)

  (build-request [_ messages opts]
    (let [system (extract-system messages opts)
          msgs (remove-system-messages messages)
          tools (:tools opts)]
      {:url (str (or (:base-url config) "https://api.anthropic.com/v1") "/messages")
       :headers {"x-api-key" (:api-key config)
                 "anthropic-version" (or (:api-version config) "2023-06-01")
                 "content-type" "application/json"}
       :body (cond-> {:model (:model opts "claude-sonnet-4-5")
                      :max_tokens (:max-tokens opts 8192)
                      :stream true
                      :messages (format-messages msgs)}
               system (assoc :system system)
               (seq tools) (assoc :tools (p/format-tools _ tools)))}))

  (create-accumulator [_ model-def]
    {:current-blocks {}      ; index -> {:type :id :name :content}
     :completed []           ; finished blocks in order
     :usage {:input-tokens 0 :output-tokens 0}
     :stop-reason nil
     :model nil
     :id nil})

  (accumulate-event [_ state event-type event-data model-def]
    (case event-type
      "message_start"
      (let [msg (:message event-data)]
        (-> state
            (assoc :id (:id msg))
            (assoc :model (:model msg))
            (assoc-in [:usage :input-tokens] (get-in msg [:usage :input_tokens] 0))))

      "content_block_start"
      (let [idx (:index event-data)
            block (:content_block event-data)]
        (assoc-in state [:current-blocks idx]
                  {:type (keyword (:type block))
                   :id (:id block)
                   :name (:name block)
                   :content ""}))

      "content_block_delta"
      (let [idx (:index event-data)
            delta (:delta event-data)]
        (case (:type delta)
          "text_delta"
          (update-in state [:current-blocks idx :content] str (:text delta))
          "input_json_delta"
          (update-in state [:current-blocks idx :content] str (:partial_json delta))
          "thinking_delta"
          (update-in state [:current-blocks idx :content] str (:thinking delta))
          ;; Unknown delta type
          state))

      "content_block_stop"
      (let [idx (:index event-data)
            block (get-in state [:current-blocks idx])
            ;; Parse tool call JSON if it's a tool_use block
            block (if (= :tool_use (:type block))
                    (try
                      (assoc block :input (json/read-value (:content block) json/keyword-keys-object-mapper))
                      (catch Exception e
                        (assoc block :parse-error (.getMessage e))))
                    block)]
        (-> state
            (update :completed conj block)
            (update :current-blocks dissoc idx)))

      "message_delta"
      (let [delta (:delta event-data)
            usage (:usage event-data)]
        (-> state
            (assoc :stop-reason (keyword (:stop_reason delta)))
            (assoc-in [:usage :output-tokens] (get usage :output_tokens 0))))

      "message_stop"
      state

      ;; Unknown event type - pass through
      state))

  (extract-response [_ state]
    (let [completed (:completed state)
          text-content (->> completed
                            (filter #(= :text (:type %)))
                            (map :content)
                            (apply str))
          tool-calls (->> completed
                          (filter #(= :tool_use (:type %)))
                          (mapv (fn [tc]
                                  {:id (:id tc)
                                   :name (:name tc)
                                   :input (:input tc)})))
          thinking (->> completed
                        (filter #(= :thinking (:type %)))
                        (map :content)
                        (apply str))]
      {:content text-content
       :tool-calls (when (seq tool-calls) tool-calls)
       :thinking (when (seq thinking) thinking)
       :usage (:usage state)
       :stop-reason (:stop-reason state)
       :model (:model state)
       :id (:id state)}))

  p/ToolFormatter

  (format-tools [_ tools]
    (mapv (fn [{:keys [name description parameters]}]
            {:name name
             :description description
             :input_schema parameters})
          tools))

  p/MessageFormatter

  (format-messages [_ messages model]
    ;; Anthropic: tool results grouped into user messages with tool_result content blocks
    ;; Assistant messages with tool calls have content blocks [{:type "text"} {:type "tool_use"}]
    (let [strip-ns (fn [m]
                     (when (map? m)
                       (into {} (map (fn [[k v]] [(if (keyword? k) (keyword (name k)) k) v]) m))))
          groups (partition-by #(= :tool-result (:message/role %)) messages)]
      (vec (mapcat
            (fn [group]
              (if (= :tool-result (:message/role (first group)))
                 ;; Tool results -> single user message with tool_result blocks
                [{:role "user"
                  :content (mapv (fn [msg]
                                   {:type "tool_result"
                                    :tool_use_id (:message/tool-use-id msg)
                                    :content (:message/content msg)})
                                 group)}]
                 ;; Regular messages
                (mapv (fn [msg]
                        (let [role (:message/role msg)
                              tool-uses (:message/tool-uses msg)]
                          (if (and (= role :assistant) (seq tool-uses))
                            {:role "assistant"
                             :content (vec (concat
                                            (when-let [text (:message/content msg)]
                                              (when (seq text)
                                                [{:type "text" :text text}]))
                                            (mapv (fn [tu]
                                                    {:type "tool_use"
                                                     :id (:tool-use/id tu)
                                                     :name (:tool-use/name tu)
                                                     :input (strip-ns (:tool-use/input tu))})
                                                  tool-uses)))}
                            {:role (name role)
                             :content (:message/content msg)})))
                      group)))
            groups))))

  p/ThinkingSupport

  (thinking-params [_ budget-tokens]
    {:thinking {:type "enabled"
                :budget_tokens budget-tokens}})

  (extract-thinking [_ response]
    (:thinking response)))

;; ============================================================================
;; Constructor
;; ============================================================================

(defn create
  "Create an Anthropic provider instance.

   Config options:
   - :api-key     - Anthropic API key (required, or from env)
   - :base-url    - API base URL (default: https://api.anthropic.com/v1)
   - :api-version - API version header (default: 2023-06-01)"
  [config]
  (let [api-key (or (:api-key config)
                    (System/getenv "ANTHROPIC_API_KEY"))]
    (when-not api-key
      (throw (ex-info "Anthropic API key required" {:env "ANTHROPIC_API_KEY"})))
    (->AnthropicProvider (assoc config :api-key api-key))))

(defn create-if-available
  "Create Anthropic provider if API key is available, otherwise nil."
  [config]
  (when (or (:api-key config) (System/getenv "ANTHROPIC_API_KEY"))
    (create config)))
