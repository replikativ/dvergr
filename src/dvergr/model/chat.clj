(ns dvergr.model.chat
  "Unified chat interface for LLM interactions.

   This is the main entry point for sending chat requests to any provider.
   Uses the provider abstraction to handle provider-specific details."
  (:require [dvergr.model.provider :as p]
            [dvergr.model.providers :as providers]
            [dvergr.model.registry :as registry]
            [hato.client :as hc]
            [jsonista.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io BufferedReader]
           [java.net SocketTimeoutException]
           [java.io IOException]))

;; ============================================================================
;; HTTP Client
;; ============================================================================

(def ^:dynamic *http-client* nil)

(defn- get-http-client []
  (or *http-client*
      (hc/build-http-client {:connect-timeout 30000
                             :redirect-policy :normal})))

;; ============================================================================
;; Retry Configuration
;; ============================================================================

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
        5000))))

(defn- calculate-backoff
  "Calculate exponential backoff delay with jitter."
  [attempt response]
  (let [retry-after (some-> response :headers (get "retry-after") parse-retry-after)
        base-delay (* (:base-delay-ms retry-config) (Math/pow 2 attempt))
        jitter (rand-int 1000)
        delay (+ base-delay jitter)]
    (min (or retry-after delay)
         (:max-delay-ms retry-config))))

;; ============================================================================
;; Error Handling
;; ============================================================================

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

;; ============================================================================
;; SSE Parsing
;; ============================================================================

(defn- parse-sse-line
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

(defn- sse-seq
  "Returns a lazy seq of parsed SSE events from a BufferedReader."
  [^BufferedReader reader]
  (lazy-seq
    (when-let [line (.readLine reader)]
      (if-let [event (parse-sse-line line)]
        (cons event (sse-seq reader))
        (sse-seq reader)))))

;; ============================================================================
;; HTTP Request
;; ============================================================================

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

;; ============================================================================
;; Core Chat Implementation
;; ============================================================================

(defn- stream-chat-impl
  "Internal implementation of streaming chat with a provider."
  [provider model-def messages opts]
  (let [;; Build request using provider
        {:keys [url headers body]} (p/build-request provider messages opts)

        ;; Make HTTP request
        response (make-request url headers body)
        reader (BufferedReader. (io/reader (:body response)))]

    {:events (sse-seq reader)
     :reader reader
     :close! (fn [] (.close reader))}))

(defn- determine-event-type
  "Determine event type for accumulator.
   Anthropic has explicit types, OpenAI uses structure."
  [provider event]
  (case (p/api-type provider)
    :anthropic-messages (:type event)
    :openai-chat "chunk"  ; OpenAI doesn't have explicit types
    "chunk"))

(defn stream-chat
  "Stream a chat completion from a provider.

   Returns a map with:
   - :events  - Lazy seq of parsed SSE events
   - :close!  - Function to close the stream

   Includes automatic retry with exponential backoff for:
   - Rate limits (429)
   - Server errors (500, 502, 503, 504)
   - Network timeouts

   Args:
     provider  - Provider instance or keyword
     model-def - Model definition from registry
     messages  - Vector of message maps
     opts      - Options map"
  [provider model-def messages opts]
  (let [provider (if (keyword? provider)
                   (providers/get-provider! provider)
                   provider)]
    (loop [attempt 0
           last-error nil]
      (if (>= attempt (:max-retries retry-config))
        (throw (ex-info "Max retries exceeded"
                        {:provider (p/provider-id provider)
                         :attempts attempt
                         :last-error (when last-error (.getMessage last-error))}
                        last-error))
        (let [result (try
                       {:success (stream-chat-impl provider model-def messages opts)}
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
              (recur (inc attempt) (:error result)))))))))

(defn chat
  "Send a chat completion request and accumulate the full response.

   This is the main entry point for LLM interactions.

   Args:
     messages - Vector of message maps {:role \"user\" :content \"...\"}

   Options:
     :model     - Model ID (required)
     :provider  - Provider keyword (optional, inferred from model)
     :max-tokens - Max output tokens
     :system    - System prompt (overrides system message in messages)
     :tools     - Tool definitions
     :thinking  - {:budget-tokens N} for thinking models
     :on-text   - Callback for streaming text: (fn [text-chunk])
     :on-event  - Callback for raw events: (fn [event])

   Returns:
     {:content    \"text response\"
      :tool-calls [{:id :name :input} ...]
      :usage      {:input-tokens N :output-tokens M}
      :stop-reason :end-turn | :tool-use | :max-tokens | ...}"
  [messages opts]
  ;; Ensure providers are initialized
  (providers/ensure-initialized!)

  (let [;; Resolve model
        model-id (or (:model opts)
                     (throw (ex-info "Model ID required" {:opts opts})))
        model-id (registry/resolve-alias model-id)
        model-def (registry/get-model! model-id)

        ;; Get provider
        provider-key (or (:provider opts) (:provider model-def))
        provider (providers/get-provider! provider-key)

        ;; Validate capabilities
        _ (when (and (seq (:tools opts))
                     (not (registry/supports? model-id :tools)))
            (throw (ex-info "Model does not support tools"
                           {:model model-id
                            :capabilities (registry/capabilities-of model-id)})))

        ;; Merge thinking params if requested
        opts (if (and (:thinking opts)
                      (p/implements-thinking? provider)
                      (registry/supports? model-id :thinking))
               (merge opts (p/thinking-params provider
                            (get-in opts [:thinking :budget-tokens] 10000)))
               opts)

        ;; Apply default top_p from model quirks if not specified
        ;; (e.g., Kimi K2.5 uses top_p=0.95)
        opts (if (and (not (:top-p opts))
                      (registry/get-quirk model-id :default-top-p))
               (assoc opts :top-p (registry/get-quirk model-id :default-top-p))
               opts)

        ;; Ensure model is in opts
        opts (assoc opts :model model-id)]

    ;; DirectChat providers bypass the HTTP+SSE path entirely
    (if (p/implements-direct-chat? provider)
      (p/direct-chat provider messages opts)

      ;; Standard HTTP+SSE streaming path
      (let [{:keys [events close!]} (stream-chat provider model-def messages opts)
            on-text (:on-text opts)
            on-event (:on-event opts)
            ;; :cancel? - optional 0-arity predicate. Polled before each
            ;; SSE event; once true we close! the reader (kills the
            ;; underlying socket — Anthropic/Fireworks stop generating
            ;; billed tokens) and throw an explicit
            ;; CancellationException so the turn-loop catcher can bail
            ;; out instead of returning a half-baked response.
            cancel? (:cancel? opts)
            api-type (p/api-type provider)]
        (try
          (let [final-state
                (reduce
                  (fn [state event]
                    (when (and cancel? (cancel?))
                      (close!)
                      (throw (java.util.concurrent.CancellationException.
                               "LLM call cancelled")))

                    ;; Call event callback
                    (when on-event (on-event event))

                    ;; Stream text callback - handle both API types
                    (when on-text
                      (case api-type
                        :anthropic-messages
                        (when (and (= "content_block_delta" (:type event))
                                   (= "text_delta" (get-in event [:delta :type])))
                          (on-text (get-in event [:delta :text])))

                        :openai-chat
                        (doseq [choice (:choices event)]
                          (when-let [text (get-in choice [:delta :content])]
                            (on-text text)))

                        nil))

                    ;; Accumulate
                    (let [event-type (determine-event-type provider event)]
                      (p/accumulate-event provider state event-type event model-def)))
                  (p/create-accumulator provider model-def)
                  events)]

            ;; Extract final response
            (p/extract-response provider final-state))
          (finally
            (close!)))))))

;; ============================================================================
;; Convenience Functions
;; ============================================================================

(defn quick-chat
  "Quick one-shot chat without streaming.

   Args:
     prompt - User prompt string
     model  - Model ID or alias

   Returns: Response content string"
  [prompt model]
  (:content (chat [{:role "user" :content prompt}]
                  {:model model})))

(defn chat-with-tools
  "Chat with tool definitions and automatic capability check.

   Args:
     messages - Message history
     tools    - Tool definitions
     opts     - Additional options (must include :model)"
  [messages tools opts]
  (chat messages (assoc opts :tools tools)))

;; ============================================================================
;; Cost Calculation
;; ============================================================================

(defn calculate-cost
  "Calculate cost in dollars for a chat response.

   Args:
     model-id - Model ID
     usage    - Usage map from response

   Returns:
     {:input-cost N :output-cost M :total-cost N+M}"
  [model-id usage]
  (let [{:keys [input-tokens output-tokens]} usage]
    (if-let [pricing (registry/pricing-of model-id)]
      (let [input-cost (* (or input-tokens 0) (/ (:input pricing) 1000000.0))
            output-cost (* (or output-tokens 0) (/ (:output pricing) 1000000.0))]
        {:input-cost input-cost
         :output-cost output-cost
         :total-cost (+ input-cost output-cost)})
      {:error "Unknown model pricing"})))
