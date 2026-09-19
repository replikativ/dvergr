(ns dvergr.model.api.claude-code
  "Claude Code CLI provider implementation.

   Uses `claude -p` (print mode) to make LLM calls through the Claude Code
   CLI, leveraging a Claude Code subscription (Pro/Max) for billing.

   Supports streaming via --output-format stream-json --verbose --include-partial-messages.

   Tool calling is implemented by embedding tool definitions in the system prompt
   and parsing structured <tool_call> blocks from the response text.

   Subscription usage limits are tracked from the CLI's `rate_limit_event`s:
   `rate-limit-status`, `usage-limited?` and `await-usage-window!` let long
   runs pause until a window resets instead of failing call after call.
   `configure!` pins the CLI binary and appends an optional note to every
   system prompt (the CLI injects host context -- the account email and the
   current date -- that cannot be switched off)."
  (:require [dvergr.model.provider :as p]
            [dvergr.model.quirks :as quirks]
            [dvergr.chat.tool-schema :as tool-schema]
            [jsonista.core :as json]
            [clojure.string :as str]
            [taoensso.telemere :as tel])
  (:import [java.io BufferedReader Closeable InputStreamReader]
           [java.util.concurrent CancellationException TimeUnit]))

;; ============================================================================
;; Settings
;; ============================================================================

(defonce ^:private settings
  (atom {:cli "claude" :system-note nil :env nil}))

(defn configure!
  "Process-wide CLI settings, merged into the current ones:
     :cli          executable to run (a versioned binary pins the CLI)
     :system-note  text appended to every system prompt, or nil
     :env          environment entries added to the CLI process, e.g.
                   {\"CLAUDE_CONFIG_DIR\" dir \"CLAUDE_CODE_OAUTH_TOKEN\" token}"
  [m]
  (swap! settings merge (select-keys m [:cli :system-note :env])))

(defn settings-snapshot [] @settings)

(defn token-env
  "CLI environment for an isolated, token-authenticated CLI: an empty config
   directory (no stored account profile, hence no injected account email) and
   a long-lived inference token from `claude setup-token`, which never
   refreshes and so cannot disturb the interactive login."
  [config-dir token]
  (.mkdirs (java.io.File. (str config-dir)))
  {"CLAUDE_CONFIG_DIR" (.getAbsolutePath (java.io.File. (str config-dir)))
   "CLAUDE_CODE_OAUTH_TOKEN" (str/trim (str token))})

(def ^:private cli-version*
  (memoize
   (fn [cli]
     (try
       (let [process (.start (ProcessBuilder. ^java.util.List [cli "--version"]))
             out (slurp (.getInputStream process))]
         (when (zero? (.waitFor process))
           (first (str/split (str/trim out) #"\s+"))))
       (catch Exception _ nil)))))

(defn cli-version
  "Version reported by the configured CLI binary, or nil when unavailable."
  []
  (cli-version* (:cli @settings)))

;; ============================================================================
;; Usage Limits
;; ============================================================================

(defonce ^:private rate-limits (atom nil))

(defn- epoch-ms [seconds] (when seconds (* 1000 (long seconds))))

(defn- record-rate-limit! [{:keys [status resetsAt rateLimitType utilization unifiedWindows]}]
  (reset! rate-limits
          {:status (keyword status)
           :type (some-> rateLimitType keyword)
           :utilization utilization
           :resets-at-ms (epoch-ms resetsAt)
           :windows (into {} (map (fn [[k {:keys [utilization resetsAt]}]]
                                    [k {:utilization utilization :resets-at-ms (epoch-ms resetsAt)}]))
                          unifiedWindows)
           :observed-at-ms (System/currentTimeMillis)}))

(defn rate-limit-status
  "The most recent subscription usage report from the CLI, or nil."
  []
  @rate-limits)

(defn usage-limited?
  "True while the last report rejected calls and its window has not reset."
  []
  (let [{:keys [status resets-at-ms]} @rate-limits]
    (and (= :rejected status)
         (or (nil? resets-at-ms) (> resets-at-ms (System/currentTimeMillis))))))

(defn- usage-limit-error [message data]
  (ex-info message (merge {:claude-code/usage-limit true
                           :resets-at-ms (:resets-at-ms @rate-limits)}
                          data)))

(defn usage-limit-error?
  "True when `e` (or a cause) is a Claude Code usage-limit failure."
  [e]
  (boolean (some #(:claude-code/usage-limit (ex-data %))
                 (take-while some? (iterate #(.getCause ^Throwable %) e)))))

(defn usage-wait-ms
  "Milliseconds until every window that is rejected or at least `threshold`
   utilized has reset (plus `margin-ms`); 0 when no pause is needed."
  ([threshold] (usage-wait-ms threshold 60000))
  ([threshold margin-ms]
   (let [now (System/currentTimeMillis)
         {:keys [status resets-at-ms windows]} @rate-limits
         blocked (concat
                  (when (= :rejected status) [resets-at-ms])
                  (keep (fn [[_ {:keys [utilization resets-at-ms]}]]
                          (when (and utilization (>= utilization threshold)) resets-at-ms))
                        windows))
         until (reduce max 0 (keep #(when (and % (> % now)) %) blocked))]
     (if (pos? until) (+ (- until now) margin-ms) 0))))

(defn await-usage-window!
  "Block the calling (host) thread while the subscription is rate limited or
   a window is at least `threshold` utilized. Returns the milliseconds waited.
   Never call on the Spindel drain."
  [{:keys [threshold max-wait-ms] :or {threshold 0.97 max-wait-ms (* 8 24 3600 1000)}}]
  (let [wait (min (usage-wait-ms threshold) max-wait-ms)]
    (when (pos? wait)
      (tel/log! {:level :warn :id :claude-code/usage-pause
                 :data {:wait-ms wait :status (rate-limit-status)}}
                "Pausing until the Claude subscription usage window resets")
      (Thread/sleep (long wait)))
    wait))

;; ============================================================================
;; Message Formatting
;; ============================================================================

(defn- format-conversation
  "Format a vector of messages into a conversation string for claude -p stdin.
   System messages are extracted separately for --system-prompt.
   Tool results are wrapped in <tool_result> blocks for natural round-trip."
  [messages]
  (let [non-system (remove #(= "system" (name (:role %))) messages)]
    (if (= 1 (count non-system))
      (:content (first non-system))
      (->> non-system
           (map (fn [{:keys [role content] :as msg}]
                  (case (name role)
                    "user" (str "Human: " content)
                    "assistant" (str "Assistant: " content)
                    "tool" (str "Human: <tool_result name=\""
                                (or (:tool-use-id msg) (:tool_call_id msg) "unknown")
                                "\">\n" content "\n</tool_result>")
                    "tool-result" (str "Human: <tool_result name=\""
                                       (or (:tool-use-id msg) (:message/tool-use-id msg) "unknown")
                                       "\">\n" content "\n</tool_result>")
                    (str (str/capitalize (name role)) ": " content))))
           (str/join "\n\n")))))

(defn- extract-system-prompt
  "Extract system prompt from messages or opts."
  [messages opts]
  (or (:system opts)
      (->> messages
           (filter #(= "system" (name (:role %))))
           (map :content)
           (str/join "\n")
           not-empty)))

;; ============================================================================
;; Tool Definition Formatting
;; ============================================================================

(defn- build-tools-prompt
  "Build tools section using Anthropic-native JSON schema format.
   Uses <tool_use> blocks which match Claude's native tool calling training."
  [tools]
  (when (seq tools)
    (let [tool-defs (mapv (fn [{:keys [name description parameters]}]
                            {:name name
                             :description description
                             :input_schema (or parameters {:type "object" :properties {}})})
                          tools)]
      (str "\n\n# Available Tools\n\n"
           (json/write-value-as-string tool-defs)
           "\n\n"
           "When you need to use a tool, output each call in a <tool_use> block:\n\n"
           "<tool_use>\n"
           "{\"name\": \"tool_name\", \"input\": {\"param1\": \"value1\"}}\n"
           "</tool_use>\n\n"
           "You may include a brief message before tool calls explaining your reasoning. "
           "You may output multiple <tool_use> blocks. "
           "After the last <tool_use> block, stop generating immediately. "
           "Do not predict or simulate tool results — the system executes tools and provides results in <tool_result> blocks in the next turn.\n"
           "When you call tools, output ONLY a brief message and the <tool_use> blocks, then stop.\n"))))

;; ============================================================================
;; Tool Call Parsing
;; ============================================================================

(def ^:private tool-call-pattern
  "Regex to match <tool_use>...</tool_use> blocks (Anthropic-native naming)."
  #"(?s)<tool_use>\s*(\{.*?\})\s*</tool_use>")

(def ^:private tool-result-pattern
  "Regex to match hallucinated <tool_result>...</tool_result> blocks.
   Also catches variants wrapped in </thinking> or other tags."
  #"(?s)<tool_result[^>]*>.*?</tool_result>|</thinking>")

(defn- clean-response-text
  "Strip hallucinated tool_result blocks from response BEFORE parsing tool_use.
   The model sometimes generates fake tool results alongside real tool calls."
  [text]
  (str/replace text tool-result-pattern ""))

(def ^:private bare-call-pattern
  "A whole response that is only a JSON object, optionally fenced."
  #"(?s)\A\s*(?:```(?:json)?\s*)?(\{.*\})\s*(?:```)?\s*\z")

(defn- bare-tool-call
  "Recover a tool call the model emitted without its <tool_use> wrapper.

   Only a response consisting of exactly one JSON object with a string
   `name` naming an OFFERED tool and a map (or absent) `input` qualifies.
   Anything else stays text: prose that merely contains JSON, unknown names,
   or extra keys are never executed."
  [text tool-names]
  (when-let [[_ json-str] (re-matches bare-call-pattern text)]
    (let [parsed (try (json/read-value json-str json/keyword-keys-object-mapper)
                      (catch Exception _ nil))]
      (when (and (map? parsed)
                 (string? (:name parsed))
                 (contains? tool-names (:name parsed))
                 (every? #{:name :input} (keys parsed))
                 (or (nil? (:input parsed)) (map? (:input parsed))))
        (tel/log! {:level :info :id :claude-code/bare-tool-call-recovered
                   :data {:name (:name parsed)}}
                  "Recovered unwrapped tool call")
        {:id (str "tc_" (java.util.UUID/randomUUID))
         :name (:name parsed)
         :input (or (:input parsed) {})}))))

(defn- parse-tool-calls
  "Parse <tool_use> blocks from response text.
   Strips hallucinated <tool_result> blocks first to avoid matching old content.
   Deduplicates file-writing tools by path (last writer wins). When no block
   is present, a response that is solely one well-formed call of an offered
   tool is recovered (see `bare-tool-call`).
   Returns {:text stripped-text, :tool-calls [{:id :name :input}]}."
  ([text] (parse-tool-calls text #{}))
  ([text tool-names]
  (if (str/blank? text)
    {:text "" :tool-calls nil}
    (let [;; Strip hallucinated tool_results FIRST — they may contain old tool_use blocks
          cleaned (clean-response-text text)
          matches (re-seq tool-call-pattern cleaned)]
      (if (empty? matches)
        (if-let [call (bare-tool-call cleaned tool-names)]
          {:text "" :tool-calls [call]}
          {:text (str/trim cleaned) :tool-calls nil})
        (let [raw-calls
              (into []
                    (comp
                     (map second)
                     (keep (fn [json-str]
                             (try
                               (let [parsed (json/read-value json-str json/keyword-keys-object-mapper)]
                                 {:id (or (:id parsed) (str "tc_" (java.util.UUID/randomUUID)))
                                  :name (:name parsed)
                                  :input (or (:input parsed) {})})
                               (catch Exception e
                                 (tel/log! {:level :warn :id :claude-code/tool-call-parse-error
                                            :data {:json json-str :error (.getMessage e)}}
                                           "Failed to parse tool call JSON")
                                 nil)))))
                    matches)
              ;; Deduplicate file-writing tools: last write to same path wins
              file-tools #{"write_file" "edit_file"}
              tool-calls (let [{file-writes true others false}
                               (group-by #(contains? file-tools (:name %)) raw-calls)
                               ;; For file writes, group by path, keep last
                               deduped-writes (->> file-writes
                                                   (group-by #(get-in % [:input :path]))
                                                   vals
                                                   (map last))]
                           (vec (concat others deduped-writes)))
              stripped (-> cleaned
                           (str/replace tool-call-pattern "")
                           str/trim)]
          {:text stripped
           :tool-calls (when (seq tool-calls) tool-calls)}))))))

;; ============================================================================
;; Model Mapping
;; ============================================================================

(def ^:private model-aliases
  {"claude-sonnet-4-6"        "sonnet"
   "claude-opus-4-6"          "opus"
   "claude-haiku-4-5"         "haiku"
   "claude-sonnet-4-5"        "sonnet"
   "claude-opus-4"            "opus"})

(defn- resolve-cli-model [model-id]
  (or (get model-aliases model-id)
      (cond
        (str/includes? (str model-id) "opus") "opus"
        (str/includes? (str model-id) "sonnet") "sonnet"
        (str/includes? (str model-id) "haiku") "haiku"
        :else "sonnet")))

;; ============================================================================
;; Effort Mapping
;; ============================================================================

(defn- resolve-effort [opts]
  (cond
    (:effort opts) (name (:effort opts))
    (:thinking opts)
    (let [budget (get-in opts [:thinking :budget-tokens] 10000)]
      (cond
        (<= budget 2000)  "low"
        (<= budget 5000)  "medium"
        (<= budget 16000) "high"
        :else             "max"))
    :else nil))

;; ============================================================================
;; CLI Command Building
;; ============================================================================

(defn- build-command
  "Build the claude CLI command with arguments."
  [opts]
  (let [model (resolve-cli-model (:model opts))
        system (extract-system-prompt [] opts)
        effort (resolve-effort opts)]
    (cond-> [(:cli @settings) "-p"
             "--output-format" "stream-json"
             "--verbose"
             "--include-partial-messages"
             "--model" model
             "--max-turns" "0"
             "--no-session-persistence"
             ;; Isolate the subprocess from the host's Claude Code environment,
             ;; or its leaked native tools shadow dvergr's text <tool_use>
             ;; protocol and tool calls fail non-deterministically (dvergr #11).
             ;; `--safe-mode` drops user/project customizations while retaining
             ;; subscription auth; current Claude Code versions honor an empty
             ;; `--tools` list as the authoritative way to disable built-ins.
             "--safe-mode"
             "--disable-slash-commands"
             "--tools" ""
             ;; Defense in depth for older CLI versions where an empty tools
             ;; list was ineffective:
             ;;  - MCP: --strict-mcp-config + an empty config drops the user's
             ;;    global MCP servers ("{}" alone is rejected — needs mcpServers).
             "--strict-mcp-config"
             "--mcp-config" "{\"mcpServers\":{}}"
             "--disallowedTools"
             "Bash Read Edit Write Glob Grep Task WebFetch WebSearch ToolSearch Skill Workflow ListAgents ReportFindings ScheduleWakeup"]
      system (into ["--system-prompt" system])
      effort (into ["--effort" effort]))))

;; ============================================================================
;; Streaming Event Processing
;; ============================================================================

(def ^:private cancel-poll-ms 10)
(def ^:private process-exit-grace-ms 100)

(defn- start-process [cmd]
  (let [pb (doto (ProcessBuilder. ^java.util.List cmd)
             (.redirectErrorStream false))]
    (when-let [env (:env @settings)]
      (.putAll (.environment pb) ^java.util.Map env))
    (.start pb)))

(defn- close-quietly! [resource]
  (when (instance? Closeable resource)
    (try
      (.close ^Closeable resource)
      (catch Exception _))))

(defn- terminate-process!
  "Terminate process, escalating promptly when a graceful destroy is ignored."
  [^Process process]
  (when process
    (locking process
      (when (.isAlive process)
        (.destroy process)
        (when-not (.waitFor process process-exit-grace-ms TimeUnit/MILLISECONDS)
          (.destroyForcibly process)
          (.waitFor process process-exit-grace-ms TimeUnit/MILLISECONDS))))))

(defn- parse-json-line [line]
  (when (and (string? line) (not (str/blank? line)))
    (try
      (json/read-value line json/keyword-keys-object-mapper)
      (catch Exception _ nil))))

(defn- extract-text-delta [event]
  (when (= "stream_event" (:type event))
    (let [inner (:event event)]
      (when (= "content_block_delta" (:type inner))
        (get-in inner [:delta :text])))))

(defn- parse-result-usage [result-event]
  (let [usage (:usage result-event)
        input (or (:input_tokens usage) 0)
        output (or (:output_tokens usage) 0)
        cache-read (or (:cache_read_input_tokens usage) 0)
        cache-creation (or (:cache_creation_input_tokens usage) 0)]
    {:input-tokens (+ input cache-read cache-creation)
     :output-tokens output
     :cache-read-tokens cache-read
     :cache-creation-tokens cache-creation
     :raw-input-tokens input}))

(defn- run-claude-streaming
  "Execute claude -p with streaming. Parses tool_call blocks from the response.
   Returns the final parsed response with :content and :tool-calls."
  [messages opts]
  (let [prompt (format-conversation messages)
        system-prompt (extract-system-prompt messages opts)
        ;; Append tool definitions to system prompt if tools provided
        tools (:tools opts)
        tools-prompt (build-tools-prompt tools)
        note (:system-note @settings)
        effective-system (some->> [system-prompt tools-prompt (when note (str "\n\n" note))]
                                  (remove nil?) seq (apply str) not-empty)
        opts-with-system (if effective-system
                           (assoc opts :system effective-system)
                           opts)
        cmd (build-command opts-with-system)
        process (start-process cmd)
        stdin (.getOutputStream process)
        stdout-reader (BufferedReader. (InputStreamReader. (.getInputStream process) "UTF-8"))
        stderr-reader (BufferedReader. (InputStreamReader. (.getErrorStream process) "UTF-8"))
        on-text (:on-text opts)
        ;; Claude can fill the OS stderr pipe before producing stdout. Drain it
        ;; from process start so neither stream can block the other.
        stderr-future (future (slurp stderr-reader))
        cancelled? (atom false)
        monitor-done? (atom false)
        cancel? (:cancel? opts)
        cancel-monitor
        (when cancel?
          (future
            (try
              (loop []
                (when (and (not @monitor-done?) (.isAlive process))
                  (if (cancel?)
                    (do
                      (reset! cancelled? true)
                      (terminate-process! process))
                    (do
                      (Thread/sleep cancel-poll-ms)
                      (recur)))))
              (catch InterruptedException _)
              (catch Exception e
                (tel/log! {:level :warn :id :claude-code/cancel-monitor-error
                           :data {:error (.getMessage e)}}
                          "Claude Code cancellation monitor failed")))))]
    (try
      ;; Check once synchronously so an already-cancelled request cannot feed
      ;; the subprocess before the monitor's first poll.
      (when (and cancel? (cancel?))
        (reset! cancelled? true)
        (terminate-process! process)
        (throw (CancellationException. "LLM call cancelled")))
      (try
        (.write stdin (.getBytes prompt "UTF-8"))
        (.close stdin)
        (catch java.io.IOException e
          ;; A CLI that rejects its invocation may close stdin immediately.
          ;; Preserve its exit code and stderr instead of masking them with a
          ;; broken-pipe exception. An alive process still indicates a real I/O
          ;; failure (or a cancellation race handled by the outer catch).
          (when (.isAlive process)
            (throw e))))
      (let [result-event (loop [line (.readLine stdout-reader)
                                result nil]
                           (if (nil? line)
                             result
                             (let [event (parse-json-line line)]
                               (when (= "rate_limit_event" (:type event))
                                 (record-rate-limit! (:rate_limit_info event)))
                               (when event
                                 (when on-text
                                   (when-let [text (extract-text-delta event)]
                                     (on-text text))))
                               (recur (.readLine stdout-reader)
                                      (if (and event (= "result" (:type event)))
                                        event
                                        result)))))
            exit-code (.waitFor process)
            stderr @stderr-future]
        (when @cancelled?
          (throw (CancellationException. "LLM call cancelled")))
        ;; An error result carries an error message, never a model reply.
        (when (:is_error result-event)
          (let [text (str (:result result-event))
                data {:exit-code exit-code :subtype (:subtype result-event)
                      :result text :stderr stderr}]
            (throw (if (or (usage-limited?)
                           (re-find #"(?i)usage limit|hit your .*limit|rate limit" text))
                     (usage-limit-error (str "Claude subscription usage limit: " text) data)
                     (ex-info (str "claude CLI error: " text) data)))))
        (if result-event
          (let [model-usage (:modelUsage result-event)
                model-key (first (keys model-usage))
                raw-content (or (:result result-event) "")
                ;; Parse tool calls from response text
                {:keys [text tool-calls]} (if (seq tools)
                                            (parse-tool-calls raw-content
                                                              (set (keep :name tools)))
                                            {:text raw-content :tool-calls nil})]
            {:content text
             :tool-calls tool-calls
             :usage (parse-result-usage result-event)
             :stop-reason (if (seq tool-calls) :tool-use :end-turn)
             :model (when model-key (name model-key))
             :id (:session_id result-event)
             :cost-usd (:total_cost_usd result-event)})
          (throw (ex-info (str "claude CLI failed (exit " exit-code ")")
                          {:exit-code exit-code
                           :stderr stderr
                           :result result-event}))))
      (catch Throwable t
        ;; The monitor can destroy the process between any two stream
        ;; operations. Cancellation remains the public outcome regardless of
        ;; which blocked I/O operation observes process death first.
        (if (and @cancelled? (not (instance? CancellationException t)))
          (throw (doto (CancellationException. "LLM call cancelled")
                   (.initCause t)))
          (throw t)))
      (finally
        (reset! monitor-done? true)
        ;; Closing all streams plus process termination releases reader futures
        ;; on success, cancellation, callback failure, and malformed output.
        (close-quietly! stdin)
        (close-quietly! stdout-reader)
        (close-quietly! stderr-reader)
        (terminate-process! process)
        (when cancel-monitor
          (future-cancel cancel-monitor))
        (when-not (realized? stderr-future)
          (future-cancel stderr-future))))))

;; ============================================================================
;; Provider Record
;; ============================================================================

(defrecord ClaudeCodeProvider [config]
  p/LLMProvider

  (provider-id [_] :claude-code)
  (api-type [_] :claude-code-cli)

  (build-request [_ _messages _opts]
    (throw (ex-info "ClaudeCodeProvider uses DirectChat, not HTTP requests" {})))
  (create-accumulator [_ _model-def]
    (throw (ex-info "ClaudeCodeProvider uses DirectChat, not SSE accumulation" {})))
  (accumulate-event [_ _state _event-type _event-data _model-def]
    (throw (ex-info "ClaudeCodeProvider uses DirectChat, not SSE accumulation" {})))
  (extract-response [_ _state]
    (throw (ex-info "ClaudeCodeProvider uses DirectChat, not SSE accumulation" {})))

  p/DirectChat

  (direct-chat [_ messages opts]
    (tel/log! {:id :claude-code/chat-start
               :data {:model (:model opts)
                      :message-count (count messages)
                      :tools (count (:tools opts))
                      :streaming? (some? (:on-text opts))}}
              "Claude Code CLI chat")
    ;; Don't spend a subprocess on a call the subscription will reject.
    (when (usage-limited?)
      (throw (usage-limit-error "Claude subscription usage limit active" {})))
    (let [parsed (run-claude-streaming messages opts)]
      (tel/log! {:id :claude-code/chat-complete
                 :data {:model (:model parsed)
                        :usage (:usage parsed)
                        :cost-usd (:cost-usd parsed)
                        :stop-reason (:stop-reason parsed)
                        :tool-calls (mapv :name (:tool-calls parsed))}}
                "Claude Code CLI chat complete")
      parsed))

  p/ToolFormatter

  (format-tools [_ tools]
    (mapv (fn [{:keys [name description parameters]}]
            {:name name :description description :parameters parameters})
          tools))

  p/MessageFormatter

  (format-messages [_ messages _model]
    ;; Text-based: format as Human/Assistant conversation with <tool_result> blocks
    ;; This is used when building multi-turn conversations for claude -p stdin
    (mapv (fn [msg]
            (let [role (:message/role msg)]
              (case role
                :tool-result
                {:role "user"
                 :content (str "<tool_result name=\""
                               (or (:message/tool-use-id msg) "unknown")
                               "\">\n" (:message/content msg) "\n</tool_result>")}
                :assistant
                (let [tool-uses (:message/tool-uses msg)
                      text (:message/content msg)
                      tool-blocks (when (seq tool-uses)
                                    (str/join "\n"
                                              (map (fn [tu]
                                                     (str "<tool_use>\n"
                                                          (json/write-value-as-string
                                                           {:name (quirks/clean-tool-name (:tool-use/name tu))
                                                            :input (tool-schema/input-entity->args (:tool-use/input tu))})
                                                          "\n</tool_use>"))
                                                   tool-uses)))]
                  {:role "assistant"
                   :content (str (or text "") (when tool-blocks (str "\n" tool-blocks)))})
                ;; Default
                {:role (name role)
                 :content (:message/content msg)})))
          messages)))

;; ============================================================================
;; Constructors
;; ============================================================================

(defn create
  "Create a Claude Code CLI provider instance."
  [config]
  (->ClaudeCodeProvider config))

(defn create-if-available
  "Create Claude Code provider if the claude CLI is available."
  [config]
  (try
    (let [process (.start (ProcessBuilder. ["claude" "--version"]))
          exit (.waitFor process)]
      (when (zero? exit)
        (create config)))
    (catch Exception _ nil)))
