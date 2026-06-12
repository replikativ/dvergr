(ns dvergr.model.api.claude-code
  "Claude Code CLI provider implementation.

   Uses `claude -p` (print mode) to make LLM calls through the Claude Code
   CLI, leveraging a Claude Code subscription (Pro/Max) for billing.

   Supports streaming via --output-format stream-json --verbose --include-partial-messages.

   Tool calling is implemented by embedding tool definitions in the system prompt
   and parsing structured <tool_call> blocks from the response text."
  (:require [dvergr.model.provider :as p]
            [jsonista.core :as json]
            [clojure.string :as str]
            [taoensso.telemere :as tel])
  (:import [java.io BufferedReader InputStreamReader]))

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

(defn- parse-tool-calls
  "Parse <tool_use> blocks from response text.
   Strips hallucinated <tool_result> blocks first to avoid matching old content.
   Deduplicates file-writing tools by path (last writer wins).
   Returns {:text stripped-text, :tool-calls [{:id :name :input}]}."
  [text]
  (if (str/blank? text)
    {:text "" :tool-calls nil}
    (let [;; Strip hallucinated tool_results FIRST — they may contain old tool_use blocks
          cleaned (clean-response-text text)
          matches (re-seq tool-call-pattern cleaned)]
      (if (empty? matches)
        {:text (str/trim cleaned) :tool-calls nil}
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
           :tool-calls (when (seq tool-calls) tool-calls)})))))

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
    (cond-> ["claude" "-p"
             "--output-format" "stream-json"
             "--verbose"
             "--include-partial-messages"
             "--model" model
             "--max-turns" "0"
             "--no-session-persistence"
             "--tools" ""]
      system (into ["--system-prompt" system])
      effort (into ["--effort" effort]))))

;; ============================================================================
;; Streaming Event Processing
;; ============================================================================

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
        effective-system (cond
                           (and system-prompt tools-prompt)
                           (str system-prompt tools-prompt)
                           system-prompt system-prompt
                           tools-prompt tools-prompt
                           :else nil)
        opts-with-system (if effective-system
                           (assoc opts :system effective-system)
                           opts)
        cmd (build-command opts-with-system)
        pb (ProcessBuilder. ^java.util.List cmd)
        _ (.redirectErrorStream pb false)
        process (.start pb)
        stdin (.getOutputStream process)
        _ (.write stdin (.getBytes prompt "UTF-8"))
        _ (.close stdin)
        stdout-reader (BufferedReader. (InputStreamReader. (.getInputStream process) "UTF-8"))
        on-text (:on-text opts)
        result-event (loop [line (.readLine stdout-reader)
                            result nil]
                       (if (nil? line)
                         result
                         (let [event (parse-json-line line)]
                           (when event
                             (when on-text
                               (when-let [text (extract-text-delta event)]
                                 (on-text text))))
                           (recur (.readLine stdout-reader)
                                  (if (and event (= "result" (:type event)))
                                    event
                                    result)))))
        stderr-reader (BufferedReader. (InputStreamReader. (.getErrorStream process) "UTF-8"))
        stderr (slurp stderr-reader)
        exit-code (.waitFor process)]
    (.close stdout-reader)
    (.close stderr-reader)
    (if (and result-event (or (zero? exit-code) result-event))
      (let [model-usage (:modelUsage result-event)
            model-key (first (keys model-usage))
            raw-content (or (:result result-event) "")
            ;; Parse tool calls from response text
            {:keys [text tool-calls]} (if (seq tools)
                                        (parse-tool-calls raw-content)
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
                       :result result-event})))))

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
                                                           {:name (:tool-use/name tu)
                                                            :input (:tool-use/input tu)})
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
