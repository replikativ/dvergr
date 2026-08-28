(ns dvergr.model.api.codex-subscription
  "Native ChatGPT Codex subscription provider plus a CLI compatibility path.

   The primary provider sends dvergr-owned history and tools directly to the
   ChatGPT Codex Responses endpoint. The trusted gateway injects file-backed
   subscription credentials. `create-cli` retains the earlier isolated
   `codex exec` adapter for keyring logins and wire-compatibility fallback."
  (:require [clojure.string :as str]
            [dvergr.chat.tool-schema :as tool-schema]
            [dvergr.model.api.codex-auth :as codex-auth]
            [dvergr.model.provider :as p]
            [dvergr.model.quirks :as quirks]
            [jsonista.core :as json]
            [taoensso.telemere :as tel])
  (:import [java.io BufferedReader InputStreamReader]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.security MessageDigest]
           [java.util UUID]))

(def ^:private output-schema
  {:type "object"
   :properties
   {:content {:type "string"
              :description "Text to show the user before or instead of tool calls."}
    :tool_calls
    {:type "array"
     :items {:type "object"
             :properties {:id {:type "string"}
                          :name {:type "string"}
                          ;; Codex strict output schemas reject an open-ended
                          ;; nested object. Carry arbitrary dvergr arguments as
                          ;; JSON on the wire and decode them at the boundary.
                          :input {:type "string"
                                  :description "A JSON-encoded object containing the tool arguments."}}
             :required ["id" "name" "input"]
             :additionalProperties false}}}
   :required ["content" "tool_calls"]
   :additionalProperties false})

(def ^:private model-aliases
  {"codex-subscription-sol" "gpt-5.6-sol"
   "codex-subscription-terra" "gpt-5.6-terra"
   "codex-subscription-luna" "gpt-5.6-luna"})

(def ^:private passive-item-types
  ;; Fail closed for every other current or future Codex item type. Dvergr only
  ;; accepts model text/reasoning; command, file, MCP, collaboration, web, plan,
  ;; and unknown items belong to Codex's harness and must never cross over.
  #{"agent_message" "reasoning" "error"})

(defn- role-name [role]
  (if (keyword? role) (name role) (str role)))

(defn- format-conversation [messages]
  (->> messages
       (remove #(= "system" (role-name (:role %))))
       (map (fn [{:keys [role content] :as message}]
              (case (role-name role)
                "tool" (str "Tool result "
                            (or (:tool_call_id message) (:tool-use-id message) "unknown")
                            ":\n" content)
                "tool-result" (str "Tool result "
                                   (or (:message/tool-use-id message)
                                       (:tool-use-id message)
                                       "unknown")
                                   ":\n" content)
                (str (str/capitalize (role-name role)) ":\n" content))))
       (str/join "\n\n")))

(defn- extract-system [messages opts]
  (or (:system opts)
      (->> messages
           (filter #(= "system" (role-name (:role %))))
           (map :content)
           (remove str/blank?)
           (str/join "\n\n")
           not-empty)))

(defn- tools-instructions [tools]
  (when (seq tools)
    (str "\n\nAvailable dvergr tools:\n"
         (json/write-value-as-string
          (mapv (fn [{:keys [name description parameters]}]
                  {:name name
                   :description description
                   :input_schema (or parameters {:type "object" :properties {}})})
                tools))
         "\n\nWhen a tool is needed, return it in tool_calls and stop. "
         "Encode each tool call's input object as JSON in its input string. "
         "Do not execute, simulate, or predict tool results. The host dvergr "
         "runtime executes the calls and supplies their results on the next turn.")))

(defn- build-prompt [messages opts]
  (str "You are acting as the model inside the dvergr agent harness. "
       "Dvergr owns the conversation, tools, sandbox, and workflow. "
       "Do not use any Codex-native tools or inspect the local environment.\n\n"
       (when-let [system (extract-system messages opts)]
         (str "System instructions:\n" system "\n\n"))
       "Conversation:\n"
       (format-conversation messages)
       (tools-instructions (:tools opts))))

(defn- resolve-model [model]
  ;; The generic model intentionally omits -m so the installed Codex version can
  ;; choose its current subscription default. Named entries provide reproducible
  ;; opt-in choices without colliding with the OpenAI API provider's model ids.
  (get model-aliases model))

(defn- resolve-effort [opts]
  (let [effort (or (some-> (:effort opts) name)
                   (when-let [budget (get-in opts [:thinking :budget-tokens])]
                     (cond
                       (<= budget 2000) "low"
                       (<= budget 8000) "medium"
                       (<= budget 20000) "high"
                       :else "xhigh")))]
    (when (and effort
               (not (contains? #{"low" "medium" "high" "xhigh" "max" "ultra"}
                               effort)))
      (throw (ex-info "Unsupported Codex reasoning effort" {:effort effort})))
    effort))

(defn- build-command [schema-path workspace opts]
  (let [model (resolve-model (:model opts))
        effort (resolve-effort opts)]
    (cond-> ["codex" "exec"
             "--json"
             "--ephemeral"
             "--ignore-user-config"
             "--ignore-rules"
             "--skip-git-repo-check"
             "--sandbox" "read-only"
             "--disable" "shell_tool"
             "--disable" "view_image"
             "--disable" "multi_agent"
             "--disable" "apps"
             "--disable" "plugins"
             "--disable" "hooks"
             "-c" "web_search=\"disabled\""
             "-c" "update_plan_enabled=false"
             "-C" workspace
             "--output-schema" schema-path]
      model (into ["--model" model])
      effort (into ["-c" (str "model_reasoning_effort=\"" effort "\"")])
      true (conj "-"))))

(defn- default-runner [command prompt]
  (let [process (.start (doto (ProcessBuilder. ^java.util.List command)
                          (.redirectErrorStream false)))
        stderr-future (future (slurp (.getErrorStream process)))]
    (try
      (with-open [stdin (.getOutputStream process)]
        (.write stdin (.getBytes ^String prompt StandardCharsets/UTF_8)))
      (let [lines (with-open [reader (BufferedReader.
                                      (InputStreamReader. (.getInputStream process)
                                                          StandardCharsets/UTF_8))]
                    (loop [acc []]
                      (if-let [line (.readLine reader)]
                        (recur (conj acc line))
                        acc)))
            exit-code (.waitFor process)]
        {:exit-code exit-code
         :lines lines
         :stderr @stderr-future})
      (finally
        (when (.isAlive process)
          (.destroyForcibly process))))))

(defn- parse-json-line [line]
  (when-not (str/blank? line)
    (try
      (json/read-value line json/keyword-keys-object-mapper)
      (catch Exception _ nil))))

(defn- accumulate-event [state event]
  (let [event-type (:type event)
        item (:item event)
        item-type (:type item)]
    (cond
      (= event-type "thread.started")
      (assoc state :thread-id (:thread_id event))

      (and (= event-type "item.completed") (= item-type "agent_message"))
      (update state :messages conj (:text item))

      (and (= event-type "item.completed") (= item-type "error"))
      (assoc state :error (:message item))

      (and (#{"item.started" "item.updated" "item.completed"} event-type)
           item-type
           (not (passive-item-types item-type)))
      (update state :native-effects conj item-type)

      (= event-type "turn.completed")
      (assoc state :usage (:usage event) :completed? true)

      (= event-type "turn.failed")
      (assoc state :error (get-in event [:error :message]))

      (= event-type "error")
      (assoc state :error (:message event))

      :else state)))

(defn- parse-structured-message [text]
  (try
    (json/read-value text json/keyword-keys-object-mapper)
    (catch Exception e
      (throw (ex-info "Codex did not return the required structured response"
                      {:response text}
                      e)))))

(defn- parse-tool-input [input]
  (let [value (if (string? input)
                (try
                  (json/read-value input json/keyword-keys-object-mapper)
                  (catch Exception e
                    (throw (ex-info "Codex returned invalid JSON tool input"
                                    {:input input}
                                    e))))
                input)]
    (if (map? value)
      value
      (throw (ex-info "Codex tool input must decode to an object"
                      {:input input :decoded value})))))

(defn- run-codex [config messages opts]
  (let [schema-path (Files/createTempFile "dvergr-codex-schema-" ".json"
                                          (make-array FileAttribute 0))
        workspace (Files/createTempDirectory "dvergr-codex-workspace-"
                                             (make-array FileAttribute 0))]
    (try
      (Files/writeString schema-path
                         (json/write-value-as-string output-schema)
                         StandardCharsets/UTF_8
                         (make-array java.nio.file.OpenOption 0))
      (let [runner (or (:runner config) default-runner)
            command (build-command (str schema-path) (str workspace) opts)
            {:keys [exit-code lines stderr]} (runner command (build-prompt messages opts))
            state (reduce accumulate-event
                          {:messages [] :native-effects []}
                          (keep parse-json-line lines))]
        (when (seq (:native-effects state))
          (throw (ex-info "Codex attempted to use its native harness; refusing the result"
                          {:native-effects (:native-effects state)})))
        (when (or (not (zero? exit-code)) (:error state) (not (:completed? state)))
          (throw (ex-info (or (:error state)
                              (str "Codex CLI failed (exit " exit-code ")"))
                          {:exit-code exit-code :stderr stderr :state state})))
        (let [raw (last (:messages state))
              parsed (parse-structured-message raw)
              tool-calls (->> (:tool_calls parsed)
                              (mapv (fn [{:keys [id name input]}]
                                      {:id id :name name :input (parse-tool-input input)})))
              usage (:usage state)
              result {:content (or (:content parsed) "")
                      :tool-calls (when (seq tool-calls) tool-calls)
                      :usage {:input-tokens (or (:input_tokens usage) 0)
                              :output-tokens (or (:output_tokens usage) 0)
                              :cache-read-tokens (or (:cached_input_tokens usage) 0)
                              :cache-write-tokens (or (:cache_write_input_tokens usage) 0)
                              :reasoning-output-tokens (or (:reasoning_output_tokens usage) 0)}
                      :stop-reason (if (seq tool-calls) :tool-use :end-turn)
                      :model (or (resolve-model (:model opts)) "codex-default")
                      :id (:thread-id state)}]
          (when-let [on-text (:on-text opts)]
            (when-not (str/blank? (:content result))
              (on-text (:content result))))
          result))
      (finally
        (Files/deleteIfExists schema-path)
        (Files/deleteIfExists workspace)))))

(defrecord CodexSubscriptionCliProvider [config]
  p/LLMProvider
  (provider-id [_] :codex-subscription-cli)
  (api-type [_] :codex-cli)
  (build-request [_ _ _]
    (throw (ex-info "CodexSubscriptionProvider uses DirectChat" {})))
  (create-accumulator [_ _]
    (throw (ex-info "CodexSubscriptionProvider uses DirectChat" {})))
  (accumulate-event [_ _ _ _ _]
    (throw (ex-info "CodexSubscriptionProvider uses DirectChat" {})))
  (extract-response [_ _]
    (throw (ex-info "CodexSubscriptionProvider uses DirectChat" {})))

  p/DirectChat
  (direct-chat [_ messages opts]
    (tel/log! {:id :codex-subscription-cli/chat-start
               :data {:model (:model opts)
                      :message-count (count messages)
                      :tools (count (:tools opts))}}
              "Codex subscription CLI chat")
    (let [response (run-codex config messages opts)]
      (tel/log! {:id :codex-subscription-cli/chat-complete
                 :data {:model (:model response)
                        :usage (:usage response)
                        :stop-reason (:stop-reason response)
                        :tool-calls (mapv :name (:tool-calls response))}}
                "Codex subscription CLI chat complete")
      response))

  p/ToolFormatter
  (format-tools [_ tools] tools)

  p/MessageFormatter
  (format-messages [_ messages _model]
    ;; Codex receives a text transcript, but the durable dvergr history remains
    ;; authoritative. Preserve both halves of every tool exchange so a fresh,
    ;; ephemeral CLI process can reconstruct the next turn without owning any
    ;; conversation state itself.
    (mapv (fn [message]
            (case (:message/role message)
              :tool-result
              {:role "tool-result"
               :content (:message/content message)
               :tool-use-id (:message/tool-use-id message)}

              :assistant
              (let [tool-calls (mapv (fn [tool-use]
                                       {:id (:tool-use/id tool-use)
                                        :name (quirks/clean-tool-name
                                               (:tool-use/name tool-use))
                                        :input (tool-schema/input-entity->args
                                                (:tool-use/input tool-use))})
                                     (:message/tool-uses message))]
                {:role "assistant"
                 :content (str (or (:message/content message) "")
                               (when (seq tool-calls)
                                 (str "\nDvergr tool calls:\n"
                                      (json/write-value-as-string tool-calls))))})

              {:role (name (:message/role message))
               :content (:message/content message)}))
          messages)))

(defn create-cli
  "Create the isolated `codex exec` compatibility provider. An optional
   :runner is intended for deterministic tests."
  [config]
  (->CodexSubscriptionCliProvider config))

(defn- default-login-probe []
  (try
    (let [process (.start (doto (ProcessBuilder. ^java.util.List
                                 ["codex" "login" "status"])
                            (.redirectErrorStream true)))
          output (slurp (.getInputStream process))
          exit-code (.waitFor process)]
      (and (zero? exit-code) (str/includes? output "ChatGPT")))
    (catch Exception _ false)))

(defn create-cli-if-available
  "Create the CLI fallback only when Codex reports a ChatGPT login."
  [config]
  (when ((or (:login-probe config) default-login-probe))
    (create-cli config)))

;; ============================================================================
;; Native Responses transport
;; ============================================================================

(def ^:private native-default-model "gpt-5.6-sol")

(defn- resolve-native-model [model]
  (or (get model-aliases model)
      (when (and model (str/starts-with? model "gpt-")) model)
      native-default-model))

(defn- sha256-hex [value]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                        (.getBytes (str value) StandardCharsets/UTF_8))]
    (apply str (map #(format "%02x" (bit-and (int %) 0xff)) digest))))

(defn- stable-id [prefix value]
  (str prefix "_" (subs (sha256-hex value) 0 32)))

(defn- response-message [role content]
  {:type "message"
   :role role
   :content [{:type (if (= "assistant" role) "output_text" "input_text")
              :text (or content "")}]})

(defn- formatted-input [messages]
  (mapv (fn [message]
          (if (:type message)
            message
            (response-message (role-name (:role message)) (:content message))))
        messages))

(defn- native-tools [tools responses-lite?]
  (let [functions (mapv (fn [{:keys [name description parameters]}]
                          {:type "function"
                           :name name
                           :description (or description "")
                           :strict false
                           :parameters (or parameters
                                           {:type "object" :properties {}})})
                        tools)]
    (if responses-lite?
      (when (seq functions)
        [{:type "namespace"
          :name "functions"
          :description ""
          :tools functions}])
      functions)))

(defn- prompt-cache-key [instructions tools opts]
  (or (:prompt-cache-key opts)
      (str "dvergr-" (subs (sha256-hex [instructions tools]) 0 32))))

(defn- session-id [cache-key]
  (str (UUID/nameUUIDFromBytes (.getBytes ^String cache-key StandardCharsets/UTF_8))))

(defn- native-prefix [instructions tools]
  (cond-> []
    (seq tools)
    (conj {:id (stable-id "at" tools)
           :type "additional_tools"
           :role "developer"
           :tools tools})

    (not (str/blank? instructions))
    (conj {:id (stable-id "msg" instructions)
           :type "message"
           :role "developer"
           :content [{:type "input_text" :text instructions}]})))

(defn- native-request [config credentials messages opts]
  (let [model (resolve-native-model (:model opts))
        responses-lite? (not= false (:responses-lite? config))
        instructions (or (extract-system messages opts) "")
        messages (remove #(= "system" (role-name (:role %))) messages)
        tools (native-tools (:tools opts) responses-lite?)
        cache-key (prompt-cache-key instructions tools opts)
        sid (session-id cache-key)
        effort (or (resolve-effort opts)
                   (:default-effort config)
                   (if (= model "gpt-5.6-sol") "low" "medium"))
        input (formatted-input messages)
        body-base {:model model
                   :input (if responses-lite?
                            (into (native-prefix instructions tools) input)
                            input)
                   :tool_choice "auto"
                   :parallel_tool_calls (and (boolean (:parallel-tool-calls opts))
                                             (not responses-lite?))
                   :reasoning (cond-> {:effort effort}
                                responses-lite? (assoc :context "all_turns"))
                   :store false
                   :stream true
                   :include ["reasoning.encrypted_content"]
                   :prompt_cache_key cache-key
                   :text {:verbosity (or (:verbosity opts) "low")}}
        body (if responses-lite?
               body-base
               (cond-> (assoc body-base :instructions instructions)
                 (seq tools) (assoc :tools tools)))]
    {:url (str (or (:base-url config)
                   "https://chatgpt.com/backend-api/codex")
               "/responses")
     :headers (cond-> {"Content-Type" "application/json"
                       "Accept" "text/event-stream"
                       "originator" (or (:originator config) "dvergr")
                       "session-id" sid
                       "thread-id" sid
                       "x-codex-routing-hint" (str "model=" model)}
                responses-lite?
                (assoc "x-openai-internal-codex-responses-lite" "true"))
     :credentials credentials
     :body body}))

(defn- parse-arguments [arguments]
  (try
    (let [value (json/read-value (or arguments "{}")
                                 json/keyword-keys-object-mapper)]
      (if (map? value) value {:value value}))
    (catch Exception e
      (throw (ex-info "Codex returned invalid function arguments"
                      {:arguments arguments}
                      e)))))

(defn- output-item-text [item]
  (->> (:content item)
       (filter #(= "output_text" (:type %)))
       (map :text)
       (apply str)))

(defrecord CodexSubscriptionProvider [config credentials]
  p/LLMProvider
  (provider-id [_] :codex-subscription)
  (api-type [_] :openai-responses)

  (build-request [_ messages opts]
    (native-request config credentials messages opts))

  (create-accumulator [_ _]
    {:content ""
     :reasoning ""
     :tool-calls []
     :usage {:input-tokens 0 :output-tokens 0}
     :stop-reason nil
     :model nil
     :id nil})

  (accumulate-event [_ state event-type event-data _]
    (case event-type
      "response.output_text.delta"
      (update state :content str (:delta event-data))

      "response.reasoning_summary_text.delta"
      (update state :reasoning str (:delta event-data))

      "response.output_item.done"
      (let [item (:item event-data)]
        (case (:type item)
          "function_call"
          (update state :tool-calls conj
                  {:id (:call_id item)
                   :name (quirks/clean-tool-name (:name item))
                   :input (parse-arguments (:arguments item))})

          "message"
          (if (str/blank? (:content state))
            (assoc state :content (output-item-text item))
            state)

          state))

      "response.completed"
      (let [response (:response event-data)
            usage (:usage response)]
        (-> state
            (assoc :id (:id response)
                   :model (:model response)
                   :stop-reason (if (seq (:tool-calls state))
                                  :tool-use
                                  :end-turn)
                   :usage {:input-tokens (or (:input_tokens usage) 0)
                           :output-tokens (or (:output_tokens usage) 0)
                           :cache-read-tokens (or (get-in usage
                                                          [:input_tokens_details
                                                           :cached_tokens])
                                                  0)
                           :reasoning-output-tokens
                           (or (get-in usage
                                       [:output_tokens_details :reasoning_tokens])
                               0)})))

      "response.incomplete"
      (assoc state :stop-reason :max-tokens)

      "response.failed"
      (throw (ex-info (or (get-in event-data [:response :error :message])
                          "Codex Responses request failed")
                      {:error (get-in event-data [:response :error])}))

      state))

  (extract-response [_ state]
    {:content (:content state)
     :reasoning (not-empty (:reasoning state))
     :tool-calls (not-empty (:tool-calls state))
     :usage (:usage state)
     :stop-reason (or (:stop-reason state)
                      (if (seq (:tool-calls state)) :tool-use :end-turn))
     :model (:model state)
     :id (:id state)})

  p/ToolFormatter
  (format-tools [_ tools]
    (native-tools tools (not= false (:responses-lite? config))))

  p/MessageFormatter
  (format-messages [_ messages _]
    (vec
     (mapcat
      (fn [message]
        (case (:message/role message)
          :tool-result
          [{:type "function_call_output"
            :call_id (:message/tool-use-id message)
            :output (:message/content message)}]

          :assistant
          (let [text (:message/content message)
                calls (mapv (fn [tool-use]
                              {:type "function_call"
                               :call_id (:tool-use/id tool-use)
                               :name (quirks/clean-tool-name (:tool-use/name tool-use))
                               :arguments (json/write-value-as-string
                                           (tool-schema/input-entity->args
                                            (:tool-use/input tool-use)))})
                            (:message/tool-uses message))]
            (cond-> []
              (not (str/blank? text)) (conj (response-message "assistant" text))
              (seq calls) (into calls)))

          [{:role (name (:message/role message))
            :content (:message/content message)}]))
      messages))))

(defn create
  "Create the native subscription provider. :credentials may inject a gateway
   Credentials implementation; production defaults to Codex's auth.json."
  [config]
  (->CodexSubscriptionProvider
   config
   (or (:credentials config) (codex-auth/create config))))

(defn create-if-available
  "Create the native provider for a usable file-backed ChatGPT login."
  [config]
  (when (or (:credentials config) (codex-auth/available? config))
    (create config)))
