(ns dvergr.model.chat
  "Unified chat interface for LLM interactions.

   This is the main entry point for sending chat requests to any provider.
   Uses the provider abstraction to handle provider-specific details."
  (:require [dvergr.model.provider :as p]
            [dvergr.model.gateway :as gateway]
            [dvergr.model.providers :as providers]
            [dvergr.model.registry :as registry]
            [dvergr.model.subscription :as subscription]
            [dvergr.resource :as resource]
            [hato.client :as hc]
            [jsonista.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [taoensso.telemere :as log])
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
                             :redirect-policy (if resource/*model-scope* :never :normal)})))

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
  "Parse a Retry-After header: seconds; anything else (an HTTP date) → 5000 ms."
  [value]
  (when value
    (try
      (* 1000 (Integer/parseInt value))
      (catch NumberFormatException _
        5000))))

(defn- calculate-backoff
  "Calculate exponential backoff delay with jitter. A `response`'s Retry-After
   would take precedence, but both callers pass nil, so it is never honoured."
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

(def ^:dynamic *request-open-timeout-ms*
  "Maximum wait for HTTP response headers, before stream-idle supervision can
   begin. A TCP connect timeout alone does not bound a provider that accepts
   the request but never opens its response. Streaming bodies keep their own
   idle deadline after headers arrive. Ten minutes accommodates reasoning-only
   work before visible output; this is a per-dispatch bound, not a turn limit."
  600000)

(defn- make-request
  "Make HTTP request with error handling. Returns response or throws. The
   usage a subscription provider reports in the headers is recorded, also
   on a refusal."
  [provider-id url headers body credentials opts]
  (let [response (gateway/request! {:method :post
                                    :url url
                                    :headers headers
                                    :credentials credentials
                                    :body (json/write-value-as-string body)
                                    :as :stream
                                    :timeout *request-open-timeout-ms*
                                    :http-client (get-http-client)
                                    :throw-exceptions false})]
    (try (subscription/observe-headers! provider-id (:headers response))
         (catch Exception _ nil))
    (if (>= (:status response) 400)
      (let [error (wrap-api-error response)
            config-key (or (:model-config-key opts) :model)]
        (if (and (= 404 (:status response))
                 (str/includes? (str/lower-case (:message (ex-data error))) "model not found"))
          (throw (ex-info (str "Model " (:model opts) " from " config-key
                               " is unavailable: " (.getMessage error))
                          (assoc (ex-data error) :type ::model-not-found
                                 :model (:model opts) :config-key config-key)
                          error))
          (throw error)))
      response)))

;; ============================================================================
;; Core Chat Implementation
;; ============================================================================

(def ^:dynamic *stream-idle-ms*
  "How long a response stream may send nothing before it counts as stalled:
   the stream is closed and the call retried. A provider that holds a stream
   open without data otherwise blocks its caller until something above gives
   up (an evaluation's timeout)."
  180000)

(defonce ^:private watchdog
  (delay (java.util.concurrent.Executors/newSingleThreadScheduledExecutor
          (reify java.util.concurrent.ThreadFactory
            (newThread [_ r] (doto (Thread. ^Runnable r "dvergr-stream-watchdog") (.setDaemon true)))))))

(defn- watched-stream
  "`in` whose reads record activity in `last-read` (epoch ms)."
  ^java.io.InputStream [^java.io.InputStream in ^java.util.concurrent.atomic.AtomicLong last-read]
  (proxy [java.io.FilterInputStream] [in]
    (read
      ([] (let [b (.read in)] (.set last-read (System/currentTimeMillis)) b))
      ([bs] (let [n (.read in ^bytes bs)] (.set last-read (System/currentTimeMillis)) n))
      ([bs off len] (let [n (.read in ^bytes bs (int off) (int len))]
                      (.set last-read (System/currentTimeMillis)) n)))))

(defn- stream-chat-impl
  "Internal implementation of streaming chat with a provider."
  [provider model-def messages opts]
  (let [;; Build request using provider
        {:keys [url headers body credentials]} (p/build-request provider messages opts)

        ;; Make HTTP request; a funded dispatch reserves a bound of its cost
        ;; (`resource/reserve-dispatch!`)
        response (binding [resource/*dispatch-bound*
                           (when resource/*model-scope*
                             (resource/dispatch-bound
                              model-def
                              body
                              (alength (.getBytes ^String (json/write-value-as-string body) "UTF-8"))
                              (some body [:max_tokens :max_completion_tokens :max_output_tokens])))]
                   (make-request (p/provider-id provider) url headers body credentials opts))
        last-read (java.util.concurrent.atomic.AtomicLong. (System/currentTimeMillis))
        stalled (atom false)
        ^java.io.InputStream raw (:body response)
        reader (BufferedReader. (io/reader (watched-stream raw last-read)))
        idle-ms (long *stream-idle-ms*)
        check (.scheduleWithFixedDelay
               ^java.util.concurrent.ScheduledExecutorService @watchdog
               ^Runnable (fn []
                           (when (> (- (System/currentTimeMillis) (.get last-read)) idle-ms)
                             (reset! stalled true)
                             ;; the stream, not the reader: a blocked readLine
                             ;; holds the reader's lock, so closing the reader
                             ;; would wait for it; closing the response body
                             ;; aborts the blocked read
                             (try (.close raw) (catch Exception _ nil))))
               (min idle-ms 5000) (min idle-ms 5000) java.util.concurrent.TimeUnit/MILLISECONDS)]
    {:events (sse-seq reader)
     :reader reader
     :stalled? (fn [] @stalled)
     :close! (fn []
               (.cancel ^java.util.concurrent.ScheduledFuture check false)
               (.close reader))}))

(defn- determine-event-type
  "Determine event type for accumulator.
   Anthropic has explicit types, OpenAI uses structure."
  [provider event]
  (case (p/api-type provider)
    :anthropic-messages (:type event)
    :openai-chat "chunk"  ; OpenAI doesn't have explicit types
    :openai-responses (:type event)
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
      (when (and (:cancel? opts) ((:cancel? opts)))
        (throw (java.util.concurrent.CancellationException. "Model call cancelled before dispatch")))
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
                (log/log! {:level :warn :id :model/chat-retry
                           :data {:attempt (inc attempt)
                                  :max-retries (:max-retries retry-config)
                                  :delay-ms delay-ms
                                  :error (.getMessage (:error result))}}
                          "Retrying model chat after retryable error"))
              (Thread/sleep (long delay-ms))
              (recur (inc attempt) (:error result)))))))))

(declare consume-stream)

(defn chat
  "Send a chat completion request and accumulate the full response.

   This is the main entry point for LLM interactions.

   Args:
     messages - Vector of message maps {:role \"user\" :content \"...\"}

   Options:
     :model     - Model ID (required)
     :model-config-key - Source config key for model error diagnostics (default :model)
     :provider  - Provider keyword (optional, inferred from model)
     :max-tokens - Max output tokens
     :system    - System prompt (overrides system message in messages)
     :tools     - Tool definitions
     :thinking  - {:budget-tokens N} for thinking models
     :effort    - Declared provider effort level; also accepted as model@effort
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
        model-id (registry/resolve-candidate model-id (:effort opts))
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

        ;; Apply default sampling params from model quirks when the caller didn't
        ;; set them — each model family ships the inference settings its authors
        ;; recommend (Kimi: top_p 0.95; MiniMax M2: temperature 1.0, top_p 0.95,
        ;; top_k 40). Caller-supplied values always win.
        opts (cond-> opts
               (and (not (:top-p opts))       (registry/get-quirk model-id :default-top-p))
               (assoc :top-p       (registry/get-quirk model-id :default-top-p))
               (and (not (:temperature opts)) (registry/get-quirk model-id :default-temperature))
               (assoc :temperature (registry/get-quirk model-id :default-temperature))
               (and (not (:top-k opts))       (registry/get-quirk model-id :default-top-k))
               (assoc :top-k       (registry/get-quirk model-id :default-top-k)))

        ;; Ensure model is in opts
        ;; Only the base id goes over the wire. Attribution and returned
        ;; responses retain the full candidate id, including effort.
        opts (cond-> (assoc opts :model (or (:base-model model-def) model-id))
               (:effort model-def) (assoc :effort (:effort model-def)))]
    ;; Response identity is the execution id used for pricing and attribution.

    ;; DirectChat providers bypass the HTTP+SSE path entirely
    (if (p/implements-direct-chat? provider)
      (if resource/*model-scope*
        (throw (ex-info "Dispatch-governed Runs require a native HTTP provider"
                        {:type ::unsupported-dispatch-accounting
                         :provider provider-key}))
        (assoc (p/direct-chat provider messages opts) :model model-id))

      ;; Standard HTTP+SSE streaming path. A model call has no side effect,
      ;; so a stream that stalls or breaks while it is read is asked again,
      ;; as a request that fails to open is (`stream-chat`).
      ;; Every dispatch of a funded call reserved a bound of its cost; they
      ;; are settled here, once each: the answer's against its usage, the
      ;; rest kept unless the provider refused them (`resource/settle-dispatches!`).
      (resource/with-dispatch-accounting
        (let [response
              (try
                (loop [attempt 0]
                  (let [r (try {:response (consume-stream provider model-def messages opts)}
                               (catch IOException e
                                 (if (< attempt 2) {:retry e} (throw e))))]
                    (if-let [e (:retry r)]
                      (do (log/log! {:level :warn :id :model/stream-retry
                                     :data {:attempt (inc attempt) :provider provider-key :model model-id
                                            :error (.getMessage ^Exception e)}}
                                    "The response stream stalled or broke; asking again")
                          (Thread/sleep (long (calculate-backoff attempt nil)))
                          (recur (inc attempt)))
                      (assoc (:response r) :model model-id))))
                (catch Throwable e
                  (resource/settle-dispatches! nil)
                  (throw e)))
              reserved? (some :reserve-command @resource/*dispatches*)
              ;; only usage the provider reported as final settles: a stream
              ;; that ended without it, or an unpriced answer, forfeits its
              ;; reservation, which then stands for its cost, so no usage is
              ;; left for the caller to charge
              cost (when-let [usage (and (:usage-final? response) (:usage response))]
                     (let [{:keys [microdollars priced?]}
                           ((requiring-resolve 'dvergr.agent.spend/of-usage) model-id usage)]
                       (when priced? microdollars)))]
          (resource/settle-dispatches! cost)
          (if (and reserved? (nil? cost))
            (assoc response
                   :usage {:input-tokens 0 :output-tokens 0}
                   :usage-forfeited (:usage response))
            response))))))

(defn- consume-stream
  "Stream one response and accumulate it (see `chat`)."
  [provider model-def messages opts]
  (let [{:keys [events close! stalled?]} (stream-chat provider model-def messages opts)
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

                   :openai-responses
                   (when (= "response.output_text.delta" (:type event))
                     (when-let [text (:delta event)]
                       (on-text text)))

                   nil))

                    ;; Accumulate
               (let [event-type (determine-event-type provider event)]
                 (p/accumulate-event provider state event-type event model-def)))
             (p/create-accumulator provider model-def)
             events)]

            ;; Extract final response
        (p/extract-response provider final-state))
      (catch IOException e
        (throw (if (stalled?)
                 (IOException. (str "no data for " *stream-idle-ms* " ms") e)
                 e)))
      (finally
        (close!)))))

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
