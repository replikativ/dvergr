(ns dvergr.chat.agent
  "Reactive agent loop using spindel.

   Each agent turn is a spin that:
   1. Reads messages from the chat context (reactive)
   2. Checks for compaction needs
   3. Calls the LLM
   4. Executes any tool calls
   5. Updates messages signal (triggers dependents)"
  (:refer-clojure :exclude [await])
  (:require [org.replikativ.spindel.engine.core :as rtc]
            [org.replikativ.spindel.core :refer [spin track await]]
            [dvergr.chat.context :as chat-ctx]
            [dvergr.chat.compaction :as compaction]
            [dvergr.model.chat :as model-chat]
            [dvergr.model.provider :as model-provider]
            [dvergr.model.providers :as model-providers]
            [dvergr.model.registry :as model-registry]
            [dvergr.model.quirks :as quirks]
            [dvergr.tools :as tools]
            [dvergr.sandbox.workspace :as workspace]
            [jsonista.core :as json]
            [datahike.api :as dh]
            [taoensso.telemere :as tel]))

;; ============================================================================
;; Convert ChatContext messages to API format
;; ============================================================================

(defn- strip-ns-keys
  "Strip namespace prefixes from map keys (datahike stores with ns-qualified attrs)."
  [m]
  (when (map? m)
    (into {} (map (fn [[k v]] [(if (keyword? k) (keyword (name k)) k) v]) m))))

(declare messages->api-format)

(defn- messages->api-format-legacy
  "Legacy case-based message formatting. Used as fallback when provider
   does not implement MessageFormatter protocol."
  [messages provider model]
  (let [messages (if (model-registry/has-quirk? model :kimi-tool-id-format?)
                   (quirks/rewrite-kimi-tool-ids messages)
                   messages)]
    (case provider
      :anthropic
    ;; Anthropic needs tool results as content blocks in user messages
      (let [;; Group consecutive tool-results together
            groups (partition-by #(= :tool-result (:message/role %)) messages)]
        (vec (mapcat (fn [group]
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
                                   ;; Assistant with tool uses -> content blocks
                                     {:role "assistant"
                                      :content (vec (concat
                                                     (when-let [text (:message/content msg)]
                                                       (when (seq text)
                                                         [{:type "text" :text text}]))
                                                     (mapv (fn [tu]
                                                             {:type "tool_use"
                                                              :id (:tool-use/id tu)
                                                              ;; Guard replay of any pre-existing poisoned
                                                              ;; history: clean a leaked-envelope name and
                                                              ;; never send nil input.
                                                              :name (quirks/clean-tool-name (:tool-use/name tu))
                                                              :input (or (strip-ns-keys (:tool-use/input tu)) {})})
                                                           tool-uses)))}
                                   ;; Regular message
                                     {:role (name role)
                                      :content (:message/content msg)})))
                               group)))
                     groups)))

    ;; OpenAI/others: tool results are separate messages
      (mapv (fn [msg]
              (let [role (:message/role msg)]
                (if (= role :tool-result)
                  {:role "tool"
                   :tool_call_id (:message/tool-use-id msg)
                   :content (:message/content msg)}
                  (let [tool-uses (:message/tool-uses msg)
                        reasoning (:message/reasoning msg)]
                    (if (and (= role :assistant) (seq tool-uses))
                    ;; Assistant with tool calls
                      (cond-> {:role "assistant"
                               :content (:message/content msg)
                               :tool_calls (mapv (fn [tu]
                                                   {:id (:tool-use/id tu)
                                                    :type "function"
                                                    ;; clean-tool-name guards replay of already-poisoned
                                                    ;; history (a leaked `name<arg_key>…` is a hard 400);
                                                    ;; nil input (no-arg tool call) must replay as "{}" —
                                                    ;; "null" arguments are also a 400 on OpenAI-compat APIs.
                                                    :function {:name (quirks/clean-tool-name (:tool-use/name tu))
                                                               :arguments (json/write-value-as-string
                                                                           (or (strip-ns-keys (:tool-use/input tu)) {}))}})
                                                 tool-uses)}
                        (seq reasoning) (assoc :reasoning_content reasoning))
                      (cond-> {:role (name role)
                               :content (:message/content msg)}
                        (and (= role :assistant) (seq reasoning))
                        (assoc :reasoning_content reasoning)))))))
            messages))))

(defn messages->api-format
  "Convert chat context messages to API message format.

   Dispatches to the provider's MessageFormatter implementation when available,
   which handles tool call conventions, result formatting, and model quirks
   (e.g., Kimi K2 tool ID rewriting).

   Falls back to legacy case-based formatting for providers without MessageFormatter."
  [messages provider model]
  (if-let [provider-instance (model-providers/get-provider provider)]
    (if (model-provider/implements-message-formatter? provider-instance)
      (model-provider/format-messages provider-instance messages model)
      (messages->api-format-legacy messages provider model))
    (messages->api-format-legacy messages provider model)))

;; ============================================================================
;; Doom Loop Detection
;; ============================================================================

(def ^:const doom-loop-threshold
  "Number of identical tool calls before injecting a warning."
  3)

(defn detect-doom-loop
  "Detect if the agent is stuck calling the same tool with the same arguments.

   Checks the last N assistant messages for repeated tool calls.
   Returns the repeated tool-use map if a doom loop is detected, nil otherwise."
  [messages]
  (let [recent-tool-calls (->> (reverse messages)
                               (filter #(= :assistant (:message/role %)))
                               (take 4)
                               (mapcat :message/tool-uses)
                               (take (* doom-loop-threshold 2)))
        fingerprints (map #(hash (select-keys % [:tool-use/name :tool-use/input]))
                          recent-tool-calls)
        freqs (frequencies fingerprints)]
    (when-let [repeated-hash (some (fn [[h cnt]] (when (>= cnt doom-loop-threshold) h))
                                   freqs)]
      ;; Return the actual repeated tool call for diagnostics
      (first (filter #(= repeated-hash
                         (hash (select-keys % [:tool-use/name :tool-use/input])))
                     recent-tool-calls)))))

(def fragment-nudge
  "Corrective shown to a model that emitted code as its message instead of
   calling a tool. Names the mistake and the fix, without scolding — and it is
   idempotent: `nudged-for-fragment?` matches on this exact text so we say it at
   most once per turn loop."
  (str "Your last message was CODE, not a reply to the room. Nobody ran it and "
       "nobody read it.\n\n"
       "If you meant to execute it, call the `clojure_eval` tool with that code "
       "as the `code` argument — code placed in the message body is never "
       "executed.\n\n"
       "If you meant to speak to the humans, write prose."))

(defn- nudged-for-fragment?
  "Have we already told this agent, in this turn loop, that it fumbled code into
   the prose channel? A second identical nudge cannot help — if the model does
   it twice, let the turn end rather than burning budget in a nudge loop."
  [chat-ctx]
  (->> (chat-ctx/get-messages chat-ctx)
       (some (fn [m]
               (and (#{:system "system"} (or (:role m) (:message/role m)))
                    (= fragment-nudge (or (:content m) (:message/content m))))))
       boolean))

;; ============================================================================
;; Agent Turn (Non-Reactive Core)
;; ============================================================================

(defn run-agent-turn!
  "Execute a single agent turn.

   This is the core non-reactive turn logic:
   1. Check for compaction (auto-compact if threshold reached)
   2. Get active messages (filtered by compaction point)
   3. Call LLM
   4. Execute tool calls if any, creating tool-call/* analytics entities
   5. Add response messages to chat with turn numbers

   Options:
   - :provider - LLM provider
   - :model - LLM model
   - :tools - Tool registry
   - :tool-ctx - Tool execution context
   - :on-text - Callback for text chunks
   - :on-reply - (fn [reply-content] → {:content str :notes [str…]}) optional
                 embedder hook run on the assistant's text reply BEFORE it is
                 added to the chat-ctx. :content replaces the reply (e.g. rewrite
                 references) so the agent's own next-turn copy carries it; each
                 :note is injected as a {:role :system} message the agent reads
                 next turn (same channel as budget alerts). Fail-safe: a throw or
                 nil return leaves the reply untouched.
   - :auto-compact? - Enable automatic compaction (default true)
   - :compaction-model - Model for summarization
   - :turn-number - Current turn number (for message grouping)

   Returns:
   - :continue if more turns needed (tool calls made)
   - :complete if agent is done (no tool calls)
   - :error if something failed"
  [chat-ctx {:keys [provider model tools tool-ctx on-text auto-compact? compaction-model turn-number cancel?
                    system-suffix on-reply]
             :or {auto-compact? true}}]
  (try
    ;; Check for automatic compaction before turn
    (when auto-compact?
      (compaction/maybe-compact! chat-ctx
                                 :model (or compaction-model
                                            (model-registry/get-default :compaction-model)
                                            "accounts/fireworks/models/minimax-m2p7")
                                 :provider (or provider :fireworks)))

    (let [;; Get active messages (filtered by compaction point)
          messages (cond->> (compaction/get-active-messages chat-ctx)
                     ;; Transient per-turn system note (e.g. the /plan-mode
                     ;; guideline): appended to the FIRST system message for THIS
                     ;; call ONLY — never written back to chat-ctx. Mirrors the
                     ;; daemon's per-turn system-prompt suffix without polluting
                     ;; the persistent conversation. Active messages carry
                     ;; ns-qualified keys (:message/role / :message/content); be
                     ;; robust to the plain-key shape too (tests).
                     system-suffix
                     (map-indexed (fn [i m]
                                    (if (and (zero? i)
                                             (= :system (or (:message/role m) (:role m))))
                                      (cond
                                        (contains? m :message/content)
                                        (update m :message/content str "\n\n" system-suffix)
                                        (contains? m :content)
                                        (update m :content str "\n\n" system-suffix)
                                        :else m)
                                      m))))
          api-messages (messages->api-format messages provider model)

          _ (tel/log! {:level :info :id :agent/llm-call
                       :data {:provider provider :model model
                              :messages (count api-messages)
                              :tools (count tools)
                              :turn turn-number}}
                      "LLM call starting")

          ;; Call LLM using new model abstraction. :cancel? is polled
          ;; per SSE event inside model-chat/chat so Esc closes the
          ;; socket mid-stream and stops billed-token generation.
          response (model-chat/chat
                    api-messages
                    {:model model
                     :provider provider
                     :tools (if tools
                              (tools/tool-definitions tools)
                              (tools/tool-definitions))
                     :on-text (or on-text (fn [_]))
                     :cancel? cancel?})

          ;; Extract from new response format
          {:keys [content tool-calls usage stop-reason reasoning]} response
          _ (tel/log! {:level :info :id :agent/llm-response
                       :data {:stop-reason stop-reason
                              :tool-calls (mapv :name tool-calls)
                              :content-len (count content)
                              :tokens usage}}
                      "LLM response received")

          ;; Product reference hook: let the embedder rewrite outbound
          ;; references in the reply (e.g. bare [[Title]] → [[dh://…]]) so the
          ;; agent's OWN next-turn copy — and the reply it posts — carry
          ;; resolved links, and surface system notes for anything the embedder
          ;; couldn't resolve. The notes are injected below exactly like a
          ;; budget alert, so the agent sees the feedback next turn without a
          ;; visible message or an extra turn. Fail-safe: a hook error (or a nil
          ;; return) leaves the reply untouched — a product hook must never
          ;; break a turn.
          reply-hook  (when (and on-reply (string? content) (seq content))
                        (try (on-reply content)
                             (catch Throwable t
                               (tel/log! {:level :warn :id :agent/on-reply-failed
                                          :data {:error (.getMessage t)}}
                                         "on-reply hook threw; using raw reply")
                               nil)))
          content     (or (:content reply-hook) content)
          reply-notes (:notes reply-hook)

          ;; Account for token usage and capture threshold warnings
          input-result (when (:input-tokens usage)
                         (chat-ctx/account-tokens! chat-ctx
                                                   :input-tokens
                                                   (:input-tokens usage)
                                                   {:model model}))
          output-result (when (:output-tokens usage)
                          (chat-ctx/account-tokens! chat-ctx
                                                    :output-tokens
                                                    (:output-tokens usage)
                                                    {:model model}))

          ;; Inject threshold warnings as system messages
          _ (doseq [result [input-result output-result]
                    :when (:threshold-crossed? result)]
              (chat-ctx/add-message! chat-ctx
                                     {:role :system
                                      :content (str "⚠️ BUDGET ALERT: "
                                                    (:threshold-message result))
                                      :important? true}))

          ;; Add assistant message if there's content
          _ (when (or (seq content) (seq tool-calls))
              (chat-ctx/add-message! chat-ctx
                                     {:role :assistant
                                      :content (if (empty? content)
                                                 (str "Tool calls: " (mapv :name tool-calls))
                                                 content)
                                      ;; Interleaved-thinking trace, fed back to
                                      ;; the model next turn (see openai provider).
                                      :reasoning reasoning
                                      ;; Sanitize BEFORE persisting: a leaked
                                      ;; GLM envelope in the name or a nil
                                      ;; argument map, once durable, makes every
                                      ;; later turn replay an invalid tool_call
                                      ;; and 400 forever (bricks the room). This
                                      ;; is the load-bearing guard.
                                      :tool-uses (when (seq tool-calls)
                                                   (mapv (fn [tc]
                                                           (let [{:keys [id name input]}
                                                                 (quirks/sanitize-tool-call tc)]
                                                             {:tool-use/id id
                                                              :tool-use/name name
                                                              :tool-use/input input}))
                                                         tool-calls))
                                      :tokens (:output-tokens usage)
                                      :turn-number turn-number}))

          ;; Surface embedder reply notes (e.g. unresolvable links) as system
          ;; messages the agent reads next turn — same channel as budget alerts.
          _ (doseq [note reply-notes
                    :when (and (string? note) (seq note))]
              (chat-ctx/add-message! chat-ctx {:role :system :content note}))]

      ;; Handle tool calls
      (if (seq tool-calls)
        (let [_ (tel/log! {:level :info :id :agent/tool-calls
                           :data {:tools (mapv :name tool-calls)
                                  :turn turn-number}}
                          "Executing tools")
              ;; Use passed tool-ctx or create default. Thread cancel?
              ;; through so individual tools (clojure_eval, future shell)
              ;; can short-circuit; we also check between tools.
              ctx (or (some-> tool-ctx (assoc :cancel? cancel?))
                      (tools/make-context {:cwd ((requiring-resolve 'dvergr.substrate.git/safe-workspace-root))
                                           :cancel? cancel?}))

              ;; Execute tools sequentially. Reduce instead of mapv so a
              ;; mid-batch cancel stops dispatching further calls — any
              ;; remaining tools get a synthetic :cancelled result so the
              ;; LLM history stays coherent (one result per tool_use_id).
              results (reduce
                       (fn [acc {:keys [id name input]}]
                         (let [cancelled? (and cancel? (cancel?))]
                           (if cancelled?
                             (conj acc {:id id
                                        :result {:type :error
                                                 :content "[cancelled before execution]"
                                                 :error "cancelled"}
                                        :duration-ms 0})
                             (let [start-time (System/currentTimeMillis)
                                    ;; P2c: resolve clojure_eval's (require …) against
                                    ;; the room's own + attached repos (load-fn falls
                                    ;; back to the base workspace if absent).
                                   result (binding [workspace/*workspace-roots* (:workspace-roots ctx)]
                                            (tools/execute name input ctx))
                                   duration-ms (- (System/currentTimeMillis) start-time)
                                   error? (= :error (:type result))]
                               (when-let [conn (:db-conn chat-ctx)]
                                 (try
                                   (dh/transact conn
                                                [{:tool-call/id (java.util.UUID/randomUUID)
                                                  :tool-call/name name
                                                  :tool-call/input (pr-str input)
                                                  :tool-call/result (pr-str (select-keys result [:type :content :error]))
                                                  :tool-call/duration-ms duration-ms
                                                  :tool-call/error? error?
                                                  :tool-call/status (if error? :error :completed)
                                                  :tool-call/approval :auto-approved
                                                  :tool-call/tool-use-id id
                                                  :tool-call/started-at (java.util.Date.)}])
                                   (catch Exception e
                                     (tel/log! {:level :warn :id :agent/tool-call-persist-error :error e}
                                               "Failed to persist tool-call analytics"))))
                               (conj acc {:id id :result result :duration-ms duration-ms})))))
                       []
                       tool-calls)

              _ (tel/log! {:level :info :id :agent/tool-results
                           :data {:tools (mapv (fn [r]
                                                 {:id (:id r)
                                                  :error? (= :error (get-in r [:result :type]))
                                                  :duration-ms (:duration-ms r)})
                                               results)
                                  :turn turn-number}}
                          "Tool results ready")]

          ;; Add tool results to chat
          (doseq [{:keys [id result]} results]
            (chat-ctx/add-message! chat-ctx
                                   {:role :tool-result
                                    :tool-use-id id
                                    :content (or (:content result)
                                                 (:error result)
                                                 (pr-str result))
                                    :turn-number turn-number}))
          ;; Loop bound: if the agent is repeating the SAME tool call with the
          ;; same args and making no progress, stop the auto-continue cycle
          ;; instead of spinning until budget runs out. History stays coherent
          ;; (tool_uses + their results are both present); a nudge tells the
          ;; model why it stopped, and the next human turn resumes it. This
          ;; finally wires detect-doom-loop, which was dead code.
          (if-let [looped (detect-doom-loop (chat-ctx/get-messages chat-ctx))]
            (do
              (tel/log! {:level :warn :id :agent/doom-loop
                         :data {:tool (:tool-use/name looped) :turn turn-number}}
                        "Repeated identical tool call with no progress — ending turn cycle")
              (chat-ctx/add-message! chat-ctx
                                     {:role :system
                                      :important? true
                                      :content (str "You have called `" (:tool-use/name looped)
                                                    "` with identical arguments "
                                                    doom-loop-threshold "+ times without making "
                                                    "progress. Stop repeating it — either take a "
                                                    "materially different approach, or reply to the "
                                                    "room describing what you found and what is "
                                                    "blocking you.")})
              :complete)
            :continue))

        ;; No tool calls. Usually that means the agent is done — but a model can
        ;; also fumble its code into the PROSE channel (stop_reason=stop, no
        ;; tool_calls, content = a code fragment). Read literally, that ends the
        ;; turn and posts the fragment to the room as if it were an answer: the
        ;; agent appears to spill code and quit mid-task. It hasn't finished; it
        ;; misaddressed a tool call. Name that once and let it try again — the
        ;; turn loop is bounded by BUDGET, not by a turn cap, so one corrective
        ;; turn costs a few cents and rescues the work.
        (if (and (quirks/code-fragment? content)
                 (not (nudged-for-fragment? chat-ctx)))
          (do
            (tel/log! {:level :warn :id :agent/code-in-prose-channel
                       :data {:turn turn-number
                              :preview (subs (str content) 0 (min 80 (count (str content))))}}
                      "Model emitted code as content instead of a tool call — nudging")
            (chat-ctx/add-message! chat-ctx
                                   {:role :system
                                    :content fragment-nudge
                                    :important? true})
            :continue)
          :complete)))

    (catch java.util.concurrent.CancellationException _
      (tel/log! {:level :info :id :agent/turn-cancelled} "Agent turn cancelled")
      :cancelled)
    (catch Exception e
      (tel/log! {:level :error :id :agent/turn-error :error e} "Agent turn error")
      :error)))

;; ============================================================================
;; Reactive Agent Loop
;; ============================================================================

(defn create-agent-spin
  "Create a reactive spin that runs agent turns until completion.

   The spin tracks the messages signal and re-executes when messages change.
   This allows external processes to inject messages and have the agent respond.

   Args:
     chat-ctx - ChatContext
     opts - Map with :provider, :model, :on-text"
  [chat-ctx {:keys [provider model on-text]
             :or {provider :anthropic
                  model "claude-sonnet-4-20250514"}}]
  (let [spindel-ctx (:spindel-ctx chat-ctx)]
    (binding [rtc/*execution-context* spindel-ctx]
      (spin
        ;; Track messages to make this reactive
       (let [messages (:new (track (:messages-signal chat-ctx)))
             status (:new (track (:status-signal chat-ctx)))]

          ;; Only run if active and has messages
         (when (and (= status :active)
                    (seq messages)
                     ;; Only run if last message is from user or tool
                    (#{:user :tool-result} (:message/role (last messages))))

            ;; Check budget first
           (if (chat-ctx/check-budget! chat-ctx)
              ;; Run the turn
             (let [result (run-agent-turn! chat-ctx
                                           {:provider provider
                                            :model model
                                            :on-text on-text})]
                ;; Return the result for inspection
               {:turn-result result
                :message-count (count messages)})

              ;; Budget exceeded
             {:turn-result :budget-exceeded
              :message-count (count messages)})))))))

;; ============================================================================
;; Simple Run Function
;; ============================================================================

(defn run-chat
  "Run an agent chat to completion.

   Creates a ChatContext, adds the task as first message, then runs
   agent turns until natural termination:
   - Budget exhausted (primary control)
   - Task complete (no more tool calls)
   - Error occurs

   Use FRP combinators for additional constraints:
   - (timeout (run-chat ...) ms fallback) - deadline

   Returns:
   - :complete on success
   - :budget-exceeded if out of tokens
   - :error on failure"
  [task & {:keys [provider model budget on-text]
           :or {provider :anthropic
                model "claude-sonnet-4-20250514"
                budget 50000}}]
  (let [;; Create chat context
        chat-ctx (chat-ctx/create-chat-context
                  {:title (subs task 0 (min 50 (count task)))
                   :budget budget
                   :with-sci? true})

        ;; Add system prompt
        _ (chat-ctx/add-message! chat-ctx
                                 {:role :system
                                  :content "You are a helpful coding assistant."})

        ;; Add user task
        _ (chat-ctx/add-message! chat-ctx
                                 {:role :user
                                  :content task})]

    (println "Starting chat:" (:chat-id chat-ctx))
    (println "Task:" task)

    ;; Run turns until natural termination
    (loop [turn 0]
      (let [status (chat-ctx/get-status chat-ctx)]
        (cond
          ;; Status changed (completed, cancelled, error)
          (not= status :active)
          {:status status
           :turns turn
           :chat-id (:chat-id chat-ctx)
           :budget (chat-ctx/get-budget chat-ctx)}

          ;; Budget exceeded (primary control)
          (chat-ctx/budget-exceeded? chat-ctx)
          (do
            (chat-ctx/set-status! chat-ctx :budget-exceeded)
            {:status :budget-exceeded
             :turns turn
             :chat-id (:chat-id chat-ctx)
             :budget (chat-ctx/get-budget chat-ctx)})

          ;; Run a turn
          :else
          (let [result (run-agent-turn! chat-ctx
                                        {:provider provider
                                         :model model
                                         :on-text on-text
                                         :turn-number (inc turn)})]
            (case result
              :continue (recur (inc turn))
              :complete (do
                          (chat-ctx/set-status! chat-ctx :completed)
                          {:status :complete
                           :turns (inc turn)
                           :chat-id (:chat-id chat-ctx)
                           :budget (chat-ctx/get-budget chat-ctx)
                           :messages (chat-ctx/get-messages chat-ctx)})
              :error {:status :error
                      :turns (inc turn)
                      :chat-id (:chat-id chat-ctx)
                      :budget (chat-ctx/get-budget chat-ctx)})))))))

(comment
  ;; Test the agent with chat context
  (require '[dvergr.chat.agent :as agent] :reload)

  ;; Simple test
  (def result (agent/run-chat "Say hello and tell me what 2+2 equals"
                              :provider :anthropic
                              :model "claude-sonnet-4-20250514"))

  ;; Check result
  (:status result)
  (:turns result)
  (count (:messages result))

  ;; Print messages
  (doseq [msg (:messages result)]
    (println "---")
    (println (:message/role msg))
    (println (subs (:message/content msg) 0 (min 200 (count (:message/content msg)))))))
