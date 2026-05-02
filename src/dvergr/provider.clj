(ns dvergr.provider
  "Multi-provider LLM API layer with streaming SSE support.
   Supports Anthropic, OpenAI, and OpenAI-compatible APIs (Fireworks)."
  (:require [hato.client :as hc]
            [jsonista.core :as json]
            [clojure.string :as str]
            [clojure.java.io :as io])
  (:import [java.io BufferedReader]
           [java.net SocketTimeoutException]
           [java.io IOException]))

;; ---------------------------------------------------------------------------
;; Configuration
;; ---------------------------------------------------------------------------

(def ^:dynamic *http-client* nil)

(defn get-http-client []
  (or *http-client*
      (hc/build-http-client {:connect-timeout 30000
                             :redirect-policy :normal})))

(def providers
  {:anthropic {:base-url "https://api.anthropic.com/v1"
               :env-key  "ANTHROPIC_API_KEY"
               :api-version "2023-06-01"}
   :openai    {:base-url "https://api.openai.com/v1"
               :env-key  "OPENAI_API_KEY"}
   ;; Fireworks can use either FIREWORKS_API_KEY or OPENAI_API_KEY with OPENAI_BASE_URL
   :fireworks {:base-url (or (System/getenv "OPENAI_BASE_URL")
                             "https://api.fireworks.ai/inference/v1")
               :env-key  (if (System/getenv "FIREWORKS_API_KEY")
                           "FIREWORKS_API_KEY"
                           "OPENAI_API_KEY")}})

(def models
  "Model configurations with pricing (per 1M tokens)"
  {"claude-sonnet-4-5-20250514"   {:provider :anthropic :input 3.00  :output 15.00  :context 200000}
   "claude-opus-4-5-20251101"     {:provider :anthropic :input 15.00 :output 75.00  :context 200000}
   "claude-3-5-haiku-20241022"    {:provider :anthropic :input 1.00  :output 5.00   :context 200000}
   "gpt-4o"                       {:provider :openai    :input 2.50  :output 10.00  :context 128000}
   "gpt-4o-mini"                  {:provider :openai    :input 0.15  :output 0.60   :context 128000}
   "gpt-4.1"                      {:provider :openai    :input 2.00  :output 8.00   :context 1000000}
   "gpt-4.1-mini"                 {:provider :openai    :input 0.40  :output 1.60   :context 1000000}
   ;; Fireworks models (OpenAI-compatible API)
   "accounts/fireworks/models/minimax-m2p5"
   {:provider :fireworks :input 0.30 :output 1.20 :context 196608}
   "accounts/fireworks/models/llama-v3p3-70b-instruct"
   {:provider :fireworks :input 0.90 :output 0.90 :context 131072}
   "accounts/fireworks/models/deepseek-v3"
   {:provider :fireworks :input 0.90 :output 0.90 :context 131072}
   "accounts/fireworks/models/qwen2p5-coder-32b-instruct"
   {:provider :fireworks :input 0.90 :output 0.90 :context 32768}
   "accounts/fireworks/models/qwen3-coder-480b-a35b-instruct"
   {:provider :fireworks :input 0.90 :output 0.90 :context 40000}
   ;; Kimi K2 - strong agentic/tool-use model (use 0905 version for tool calling)
   "accounts/fireworks/models/kimi-k2-instruct"
   {:provider :fireworks :input 0.90 :output 0.90 :context 131072}
   "accounts/fireworks/models/kimi-k2-instruct-0905"
   {:provider :fireworks :input 0.90 :output 0.90 :context 131072}})

;; ---------------------------------------------------------------------------
;; Error Handling & Retries
;; ---------------------------------------------------------------------------

(def ^:private retry-config
  {:max-retries 3
   :base-delay-ms 1000
   :max-delay-ms 30000
   :retryable-statuses #{429 500 502 503 504}})

(defn- retryable-error?
  "Check if an error is retryable."
  [error]
  (or (instance? SocketTimeoutException error)
      (instance? IOException error)
      (and (instance? clojure.lang.ExceptionInfo error)
           (let [data (ex-data error)]
             (contains? (:retryable-statuses retry-config)
                        (:status data))))))

(defn- parse-retry-after
  "Parse Retry-After header (seconds or HTTP date)."
  [value]
  (when value
    (try
      (* 1000 (Integer/parseInt value))
      (catch NumberFormatException _
        5000))))  ; Default 5s for date format

(defn- calculate-backoff
  "Calculate exponential backoff delay with jitter."
  [attempt response]
  (let [retry-after (some-> response :headers (get "retry-after") parse-retry-after)
        base-delay (* (:base-delay-ms retry-config) (Math/pow 2 attempt))
        jitter (rand-int 1000)
        delay (+ base-delay jitter)]
    (min (or retry-after delay)
         (:max-delay-ms retry-config))))

(defn- parse-error-body
  "Parse error response body for detailed message."
  [body]
  (try
    (let [parsed (json/read-value (slurp body) json/keyword-keys-object-mapper)]
      (or (get-in parsed [:error :message])
          (:message parsed)
          (str parsed)))
    (catch Exception _
      "Unknown error")))

(defn- wrap-api-error
  "Wrap HTTP error response in ExceptionInfo."
  [response]
  (let [status (:status response)
        message (parse-error-body (:body response))]
    (ex-info (str "API error " status ": " message)
             {:status status
              :message message
              :headers (:headers response)})))

;; ---------------------------------------------------------------------------
;; SSE Parsing
;; ---------------------------------------------------------------------------

(defn parse-sse-line
  "Parse a single SSE line. Returns nil for empty/comment lines."
  [line]
  (cond
    (str/blank? line) nil
    (str/starts-with? line ":") nil  ; comment
    (str/starts-with? line "data: ")
    (let [data (subs line 6)]
      (when (not= data "[DONE]")
        (try
          (json/read-value data json/keyword-keys-object-mapper)
          (catch Exception e
            {:parse-error (.getMessage e) :raw data}))))
    :else nil))

(defn sse-seq
  "Returns a lazy seq of parsed SSE events from a BufferedReader."
  [^BufferedReader reader]
  (lazy-seq
    (when-let [line (.readLine reader)]
      (if-let [event (parse-sse-line line)]
        (cons event (sse-seq reader))
        (sse-seq reader)))))

;; ---------------------------------------------------------------------------
;; Anthropic SSE Accumulator
;; ---------------------------------------------------------------------------

(defn anthropic-accumulator
  "State machine for accumulating Anthropic streaming events.
   Handles partial tool call JSON correctly."
  []
  {:current-blocks {}      ; index -> {:type :id :name :content}
   :completed []           ; finished blocks in order
   :usage {:input 0 :output 0}
   :stop-reason nil
   :model nil
   :id nil})

(defn anthropic-accumulate
  "Process a single Anthropic SSE event, returning updated state."
  [state event]
  (case (:type event)
    "message_start"
    (let [msg (:message event)]
      (-> state
          (assoc :id (:id msg))
          (assoc :model (:model msg))
          (assoc-in [:usage :input] (get-in msg [:usage :input_tokens] 0))))

    "content_block_start"
    (let [idx (:index event)
          block (:content_block event)]
      (assoc-in state [:current-blocks idx]
                {:type (keyword (:type block))
                 :id (:id block)
                 :name (:name block)
                 :content ""}))

    "content_block_delta"
    (let [idx (:index event)
          delta (:delta event)]
      (case (:type delta)
        "text_delta"
        (update-in state [:current-blocks idx :content] str (:text delta))
        "input_json_delta"
        (update-in state [:current-blocks idx :content] str (:partial_json delta))
        ;; Unknown delta type
        state))

    "content_block_stop"
    (let [idx (:index event)
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
    (let [delta (:delta event)
          usage (:usage event)]
      (-> state
          (assoc :stop-reason (keyword (:stop_reason delta)))
          (assoc-in [:usage :output] (get usage :output_tokens 0))))

    "message_stop"
    state

    ;; Unknown event type - pass through
    state))

;; ---------------------------------------------------------------------------
;; OpenAI SSE Accumulator
;; ---------------------------------------------------------------------------

(defn openai-accumulator
  "State machine for OpenAI streaming events."
  []
  {:current-blocks {}
   :completed []
   :usage {:input 0 :output 0}
   :stop-reason nil
   :model nil
   :id nil})

(defn openai-accumulate
  "Process a single OpenAI SSE event."
  [state event]
  (let [;; First process choices if present
        state (if-let [choices (:choices event)]
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

                        ;; Tool calls
                        (:tool_calls delta)
                        (as-> s'
                            (reduce
                              (fn [s'' tc]
                                (let [tc-idx (:index tc)
                                      key [idx tc-idx]
                                      existing (get-in s'' [:current-blocks key])]
                                  (if existing
                                    ;; Continuation - append arguments, update name if we get it
                                    (-> s''
                                        (update-in [:current-blocks key :content]
                                                   str (get-in tc [:function :arguments] ""))
                                        (cond-> (get-in tc [:function :name])
                                          (assoc-in [:current-blocks key :name]
                                                    (get-in tc [:function :name]))))
                                    ;; New tool call
                                    (assoc-in s'' [:current-blocks key]
                                              {:type :tool_use
                                               :id (:id tc)
                                               :name (get-in tc [:function :name])
                                               :content (or (get-in tc [:function :arguments]) "")}))))
                              s'
                              (:tool_calls delta)))

                        ;; Finish
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
    (if-let [usage (:usage event)]
      (-> state
          (assoc-in [:usage :input] (:prompt_tokens usage))
          (assoc-in [:usage :output] (:completion_tokens usage))
          (cond-> (:model event) (assoc :model (:model event)))
          (cond-> (:id event) (assoc :id (:id event))))
      state)))

;; ---------------------------------------------------------------------------
;; Provider API Calls
;; ---------------------------------------------------------------------------

(defn anthropic-request
  "Build Anthropic API request body."
  [messages opts]
  (let [tools (:tools opts)
        system (or (:system opts)
                   (when-let [sys (first (filter #(= "system" (:role %)) messages))]
                     (:content sys)))
        messages (filterv #(not= "system" (:role %)) messages)]
    (cond-> {:model (:model opts "claude-sonnet-4-5-20250514")
             :max_tokens (:max-tokens opts 8192)
             :stream true
             :messages messages}
      system (assoc :system system)
      (seq tools) (assoc :tools tools))))

(defn openai-request
  "Build OpenAI/Fireworks API request body."
  [messages opts]
  (let [tools (:tools opts)]
    (cond-> {:model (:model opts "gpt-4o")
             :max_completion_tokens (:max-tokens opts 8192)
             :stream true
             :stream_options {:include_usage true}
             :messages messages}
      (seq tools) (assoc :tools (mapv (fn [t]
                                        {:type "function"
                                         :function {:name (:name t)
                                                    :description (:description t)
                                                    :parameters (:parameters t)}})
                                      tools)))))

(defn- make-request
  "Make HTTP request with error handling. Returns response or throws."
  [url headers body]
  (let [response (hc/request {:method :post
                              :url url
                              :headers headers
                              :body (json/write-value-as-string body)
                              :as :stream
                              :http-client (get-http-client)
                              :throw-exceptions false})]
    (if (>= (:status response) 400)
      (throw (wrap-api-error response))
      response)))

(defn- stream-chat-impl
  "Internal implementation of stream-chat with single attempt."
  [provider-id messages opts]
  (let [provider (get providers provider-id)
        api-key (System/getenv (:env-key provider))
        _ (when-not api-key
            (throw (ex-info (str "Missing API key: " (:env-key provider))
                            {:provider provider-id
                             :env-key (:env-key provider)})))

        url (case provider-id
              :anthropic (str (:base-url provider) "/messages")
              (str (:base-url provider) "/chat/completions"))

        headers (case provider-id
                  :anthropic {"x-api-key" api-key
                              "anthropic-version" (:api-version provider)
                              "content-type" "application/json"}
                  {"Authorization" (str "Bearer " api-key)
                   "Content-Type" "application/json"})

        body (case provider-id
               :anthropic (anthropic-request messages opts)
               (openai-request messages opts))

        response (make-request url headers body)
        reader (BufferedReader. (io/reader (:body response)))]

    {:events (sse-seq reader)
     :close! (fn [] (.close reader))}))

(defn stream-chat
  "Stream a chat completion from the specified provider.
   Returns a map with :events (lazy seq) and :close! (fn to close stream).

   Includes automatic retry with exponential backoff for transient errors:
   - Rate limits (429)
   - Server errors (500, 502, 503, 504)
   - Network timeouts"
  [provider-id messages opts]
  (loop [attempt 0
         last-error nil]
    (if (>= attempt (:max-retries retry-config))
      (throw (ex-info "Max retries exceeded"
                      {:provider provider-id
                       :attempts attempt
                       :last-error (when last-error (.getMessage last-error))}
                      last-error))
      (let [result (try
                     {:success (stream-chat-impl provider-id messages opts)}
                     (catch Exception e
                       (if (retryable-error? e)
                         {:error e}
                         (throw e))))]
        (if (:success result)
          (:success result)
          (let [delay-ms (calculate-backoff attempt nil)]
            (when (> attempt 0)
              (binding [*out* *err*]
                (println (str "Retry " (inc attempt) "/" (:max-retries retry-config)
                              " after " delay-ms "ms: " (.getMessage (:error result))))))
            (Thread/sleep (long delay-ms))
            (recur (inc attempt) (:error result))))))))

(defn chat
  "Send a chat completion request and accumulate the full response.
   Returns {:blocks [...] :usage {:input N :output N} :stop-reason :keyword}

   Options:
   - :model     - model ID
   - :system    - system prompt
   - :tools     - tool definitions
   - :on-text   - callback for streaming text: (fn [text-chunk])
   - :on-event  - callback for raw events: (fn [event])"
  [provider-id messages opts]
  (let [{:keys [events close!]} (stream-chat provider-id messages opts)
        accumulator (case provider-id
                      :anthropic anthropic-accumulator
                      openai-accumulator)
        accumulate (case provider-id
                     :anthropic anthropic-accumulate
                     openai-accumulate)
        on-text (:on-text opts)
        on-event (:on-event opts)]
    (try
      (reduce
        (fn [state event]
          (when on-event (on-event event))
          ;; Stream text as it arrives
          (when (and on-text
                     (= "content_block_delta" (:type event))
                     (= "text_delta" (get-in event [:delta :type])))
            (on-text (get-in event [:delta :text])))
          (when (and on-text
                     (:choices event))
            (doseq [choice (:choices event)]
              (when-let [text (get-in choice [:delta :content])]
                (on-text text))))
          (accumulate state event))
        (accumulator)
        events)
      (finally
        (close!)))))

;; ---------------------------------------------------------------------------
;; Utility Functions
;; ---------------------------------------------------------------------------

(defn calculate-cost
  "Calculate cost in dollars for token usage.
   Accepts usage with either :input/:output or :total-input/:total-output keys."
  [model-id usage]
  (if-let [model (models model-id)]
    (let [input-tokens (or (:total-input usage) (:input usage) 0)
          output-tokens (or (:total-output usage) (:output usage) 0)
          input-cost (* input-tokens (/ (:input model) 1000000))
          output-cost (* output-tokens (/ (:output model) 1000000))]
      {:input-cost input-cost
       :output-cost output-cost
       :total-cost (+ input-cost output-cost)})
    {:error "Unknown model"}))

(defn provider-for-model
  "Get the provider ID for a model."
  [model-id]
  (get-in models [model-id :provider] :anthropic))

(comment
  ;; Test Anthropic
  (chat :anthropic
        [{:role "user" :content "Say hello in 5 words"}]
        {:model "claude-sonnet-4-5-20250514"
         :on-text print})

  ;; Test OpenAI
  (chat :openai
        [{:role "user" :content "Say hello in 5 words"}]
        {:model "gpt-4o-mini"
         :on-text print})

  ;; Test Fireworks
  (chat :fireworks
        [{:role "user" :content "Say hello in 5 words"}]
        {:model "accounts/fireworks/models/llama-v3p3-70b-instruct"
         :on-text print}))
