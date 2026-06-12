(ns dvergr.chat.compaction
  "Memory management through message compaction.

   Implements OpenCode-style compaction strategy:
   1. Detects when context is approaching budget threshold
   2. Selects messages for compaction (protects important/recent)
   3. Generates summary using cheap LLM
   4. Extracts wiki-links [[Entity][context]] for knowledge graph
   5. Creates/updates simmis-compatible notes for wiki-linked entities
   6. Inserts summary message as compression point
   7. Original messages preserved (immutable history)

   The compaction point divides history:
   - Before: Full messages stored but not sent to LLM
   - After: Messages sent to LLM in API calls

   Key differences from simple truncation:
   - Important messages always preserved
   - Wiki-links build project knowledge over time
   - Notes capture factual, invariant knowledge about entities
   - Summary maintains context continuity"
  (:require [dvergr.chat.context :as chat-ctx]
            [dvergr.model.chat :as model-chat]
            [clojure.string :as str]
            [datahike.api :as dh]
            [taoensso.telemere :as tel])
  (:import))

;; Forward declarations for functions used before definition
(declare get-active-messages)

;; ============================================================================
;; Token Estimation
;; ============================================================================

(def ^:private chars-per-token
  "Approximate characters per token for most LLMs.
   Conservative estimate - actual varies by tokenizer."
  4)

(defn estimate-tokens
  "Estimate token count for a string.
   Uses simple character-based heuristic."
  [s]
  (if (string? s)
    (int (Math/ceil (/ (count s) chars-per-token)))
    0))

(defn message-tokens
  "Get token count for a message.
   Uses stored value if available, otherwise estimates from content."
  [msg]
  (or (:message/tokens msg)
      (estimate-tokens (:message/content msg))))

(defn total-context-tokens
  "Sum token count for all messages."
  [messages]
  (reduce + (map message-tokens messages)))

;; ============================================================================
;; Output Truncation
;; ============================================================================

(def ^:const max-tool-output-tokens
  "Maximum tokens for a single tool output.
   Conservative to ensure compaction always has room to work.
   At 4 chars/token, this is ~60K characters."
  15000)

(def ^:const truncation-prefix-ratio
  "Ratio of truncation budget for prefix (beginning of output).
   Rest goes to suffix. 0.4 means 40% prefix, 60% suffix."
  0.4)

(defn truncate-output
  "Truncate text to fit within token budget, preserving prefix and suffix.

   Uses middle-out truncation like codex: keeps beginning for context
   and end for recency. Inserts marker showing what was removed.

   Args:
     text - String to truncate
     max-tokens - Maximum tokens (default: max-tool-output-tokens)

   Returns:
     Truncated string with marker, or original if within budget."
  ([text] (truncate-output text max-tool-output-tokens))
  ([text max-tokens]
   (if-not (string? text)
     text
     (let [estimated (estimate-tokens text)]
       (if (<= estimated max-tokens)
         text
         ;; Need to truncate
         (let [max-chars (* max-tokens chars-per-token)
               prefix-chars (int (* max-chars truncation-prefix-ratio))
               suffix-chars (- max-chars prefix-chars 100) ; Reserve for marker

               prefix (subs text 0 (min prefix-chars (count text)))
               suffix (when (> (count text) suffix-chars)
                        (subs text (- (count text) suffix-chars)))

               removed-tokens (- estimated max-tokens)
               removed-chars (- (count text) (count prefix) (count (or suffix "")))

               marker (str "\n\n[OUTPUT TRUNCATED — removed " removed-tokens " tokens / "
                           removed-chars " chars from middle. "
                           "Showing first " (int (* 100 truncation-prefix-ratio))
                           "% and last " (int (* 100 (- 1.0 truncation-prefix-ratio)))
                           "% of " estimated " total tokens. "
                           "If you need the full output, re-run the tool with more specific parameters.]\n\n")]
           (str prefix marker suffix)))))))

(defn truncate-tool-result
  "Truncate a tool result map's :content field if too large.

   Adds :truncated? true to metadata if truncation occurred.

   Args:
     result - Tool result map with :content key
     max-tokens - Maximum tokens (default: max-tool-output-tokens)

   Returns:
     Result map with truncated content if needed."
  ([result] (truncate-tool-result result max-tool-output-tokens))
  ([result max-tokens]
   (if-let [content (:content result)]
     (let [original-tokens (estimate-tokens content)
           truncated-content (truncate-output content max-tokens)]
       (if (= content truncated-content)
         result
         (-> result
             (assoc :content truncated-content)
             (assoc-in [:metadata :truncated?] true)
             (assoc-in [:metadata :original-tokens] original-tokens)
             (assoc-in [:metadata :truncated-tokens] (estimate-tokens truncated-content)))))
     result)))

;; ============================================================================
;; Tool Output Pruning (Phase 1 — before full compaction)
;; ============================================================================

(def ^:private prune-threshold-tokens
  "Minimum token count for a tool-result to be worth pruning."
  500)

(defn prune-tool-outputs
  "Replace old tool-result content with truncation markers.
   Preserves the last `protected-turns` turns intact.
   Returns [pruned-messages tokens-recovered].

   This is a cheap first pass that can recover 20-40K tokens
   without paying for an LLM summarization call."
  [messages & {:keys [n-protected-turns] :or {n-protected-turns 2}}]
  (let [n (count messages)
        ;; Find the start of protected turns (count backwards from end)
        turn-boundary (loop [msgs (reverse messages)
                             turn-count 0
                             msg-count 0]
                        (if (or (empty? msgs) (>= turn-count n-protected-turns))
                          (- n msg-count)
                          (let [msg (first msgs)
                                new-tc (if (= :user (:message/role msg))
                                         (inc turn-count) turn-count)]
                            (recur (rest msgs) new-tc (inc msg-count)))))
        tokens-recovered (atom 0)]
    [(vec
      (map-indexed
       (fn [i msg]
         (if (and (< i turn-boundary)
                  (= :tool-result (:message/role msg))
                  (not (:message/pruned? msg))
                  (> (message-tokens msg) prune-threshold-tokens))
           (let [orig-tokens (message-tokens msg)]
             (swap! tokens-recovered + (- orig-tokens 20)) ;; marker is ~20 tokens
             (assoc msg
                    :message/content (str "[tool output pruned — was " orig-tokens " tokens]")
                    :message/pruned? true
                    :message/original-tokens orig-tokens))
           msg))
       messages))
     @tokens-recovered]))

;; ============================================================================
;; Compaction Detection
;; ============================================================================

(def ^:private compaction-threshold
  "Trigger compaction when context reaches this fraction of budget."
  0.7)

(def ^:private target-after-compaction
  "Target context size after compaction (fraction of budget)."
  0.4)

(def ^:private prune-minimum
  "Minimum tokens to remove before triggering compaction.
   OpenCode uses 20,000. We use lower for smaller context windows."
  10000)

(defn context-fullness
  "How full a chat-ctx's ACTIVE context is relative to the model's window —
   the data behind the compaction progress bar. Call with the chat-ctx's
   spindel-ctx bound (it reads the messages-signal).

   Returns {:tokens :limit :pct :compact-pct :should-compact?} where :pct is
   tokens/limit (0.0–1.0+) and :compact-pct is the fraction at which automatic
   compaction kicks in (so the UI can mark the threshold)."
  [chat-ctx context-window]
  (let [tokens (total-context-tokens (get-active-messages chat-ctx))
        limit  (or context-window 0)]
    {:tokens          tokens
     :limit           limit
     :pct             (if (pos? limit) (double (/ tokens limit)) 0.0)
     :compact-pct     compaction-threshold
     :should-compact? (boolean (and (pos? limit)
                                    (> tokens (* limit compaction-threshold))))}))

(defn should-compact?
  "Check if chat context needs compaction.

   Returns true when:
   - Context tokens > threshold * context-window
   - Tokens to remove > prune-minimum (avoid tiny compactions)
   - There are enough messages to compact

   Uses model's context window for threshold, not budget."
  [chat-ctx & {:keys [context-window] :or {context-window 128000}}]
  (let [messages (chat-ctx/get-messages chat-ctx)
        active-messages (get-active-messages chat-ctx)
        current-tokens (total-context-tokens active-messages)
        threshold-tokens (* context-window compaction-threshold)
        tokens-to-remove (- current-tokens (* context-window target-after-compaction))]
    (and (> current-tokens threshold-tokens)
         (> tokens-to-remove prune-minimum)  ; Only compact if enough to remove
         (> (count messages) 6))))  ; Need at least 6 to have something to compact

(defn compaction-needed-tokens
  "Calculate how many tokens need to be removed.
   Uses model's context window for target, not budget."
  [chat-ctx & {:keys [context-window] :or {context-window 128000}}]
  (let [active-messages (get-active-messages chat-ctx)
        current-tokens (total-context-tokens active-messages)
        target-tokens (* context-window target-after-compaction)]
    (long (max 0 (- current-tokens target-tokens)))))

;; ============================================================================
;; Message Selection for Compaction
;; ============================================================================

(def ^:private protected-turns
  "Number of recent turns to protect from compaction.
   A turn = user message + assistant response + tool results.
   OpenCode uses 2 turns."
  2)

(defn- count-turns-from-end
  "Count how many messages are in the last N turns.
   A turn starts with a :user message."
  [messages n-turns]
  (loop [msgs (reverse messages)
         turn-count 0
         msg-count 0]
    (if (or (empty? msgs) (>= turn-count n-turns))
      msg-count
      (let [msg (first msgs)
            role (:message/role msg)
            new-turn-count (if (= role :user)
                             (inc turn-count)
                             turn-count)]
        (recur (rest msgs)
               new-turn-count
               (inc msg-count))))))

(defn- message-compactable?
  "Check if a message can be compacted.

   Protected messages:
   - System messages (always needed)
   - Important messages (marked by user/agent)
   - Already compacted messages (they're summaries)"
  [msg]
  (and (not= :system (:message/role msg))
       (not (:message/important? msg))
       (not (:message/compacted? msg))))

(defn select-for-compaction
  "Select messages to compact to achieve target token reduction.

   Strategy (following OpenCode):
   1. Protect last N turns (user + assistant + tool results)
   2. Protect system and important messages
   3. Stop at previous compaction point
   4. Select from older messages, preferring tool-result (verbose)"
  [messages tokens-to-remove]
  (let [n (count messages)
        ;; Protect last N turns (not just N messages)
        protected-from-end (count-turns-from-end messages protected-turns)
        protected-end-start (max 0 (- n protected-from-end))

        ;; Find previous compaction point (stop there)
        compaction-idx (->> messages
                            (map-indexed vector)
                            (filter (fn [[_ msg]]
                                      (and (= :system (:message/role msg))
                                           (:message/compacted? msg))))
                            last
                            first)

        ;; Compactable range: after last compaction, before protected turns
        start-idx (if compaction-idx (inc compaction-idx) 0)
        end-idx protected-end-start

        ;; Score messages for compaction priority
        ;; Higher score = more likely to compact
        score-message (fn [idx msg]
                        (cond
                          ;; Outside compactable range
                          (< idx start-idx) -1000
                          (>= idx end-idx) -1000
                          ;; Not compactable (system, important, already compacted)
                          (not (message-compactable? msg)) -1000
                          ;; Tool results are verbose - prioritize for compaction
                          (= :tool-result (:message/role msg)) 100
                          ;; Assistant messages next
                          (= :assistant (:message/role msg)) 50
                          ;; User messages last
                          :else 10))

        scored (->> messages
                    (map-indexed (fn [idx msg]
                                   {:idx idx
                                    :msg msg
                                    :score (score-message idx msg)
                                    :tokens (message-tokens msg)}))
                    (filter #(pos? (:score %)))
                    (sort-by :score >))]

    ;; Greedily select until we have enough tokens
    (loop [selected []
           remaining scored
           tokens-selected 0]
      (if (or (>= tokens-selected tokens-to-remove)
              (empty? remaining))
        selected
        (let [next-msg (first remaining)]
          (recur (conj selected next-msg)
                 (rest remaining)
                 (+ tokens-selected (:tokens next-msg))))))))

;; ============================================================================
;; Wiki-Link Extraction
;; ============================================================================

(def ^:private wiki-link-pattern
  "Pattern for [[Entity][context]] or [[Entity]] wiki-links."
  #"\[\[([^\]]+)\](?:\[([^\]]+)\])?\]")

(def ^:private code-block-pattern
  "Pattern for fenced code blocks (```...```)."
  #"```[\s\S]*?```")

(def ^:private inline-code-pattern
  "Pattern for inline code (`...`)."
  #"`[^`]+`")

(defn- valid-entity-name?
  "Check if an entity name looks like a valid entity, not code/regex fragment.
   Valid entities should:
   - Be at least 2 characters
   - Start with a letter (not punctuation)
   - Not be mostly punctuation/symbols
   - Not look like code patterns"
  [entity]
  (and (string? entity)
       (>= (count entity) 2)
       ;; First char should be a letter (not punctuation or number)
       (re-matches #"^[\p{L}].*" entity)
       ;; Should not be mostly punctuation/brackets
       (< (/ (count (re-seq #"[\[\]\{\}\(\)\<\>\|\^\$\*\+\?\\\:\=]" entity))
             (max 1 (count entity)))
          0.3)
       ;; Should not look like code identifiers (snake_case, SCREAMING_CASE)
       (not (re-matches #"^[a-z][a-z0-9_]+$" entity))
       (not (re-matches #"^[A-Z][A-Z0-9_]+$" entity))
       ;; Should not contain code syntax characters
       (not (re-find #"[\:\=\$\#\@]" entity))))

(defn- strip-code-blocks
  "Remove code blocks and inline code from text to avoid extracting
   wiki-links from code examples."
  [text]
  (-> text
      (str/replace code-block-pattern "")
      (str/replace inline-code-pattern "")))

(defn extract-wiki-links
  "Extract wiki-links from text, skipping code blocks.

   Returns vector of {:entity \"Name\" :context \"description\"}"
  [text]
  (when (string? text)
    (let [clean-text (strip-code-blocks text)]
      (->> (re-seq wiki-link-pattern clean-text)
           (map (fn [[_ entity context]]
                  {:entity entity
                   :context (or context entity)}))
           (filter #(valid-entity-name? (:entity %)))
           vec))))

(defn extract-all-wiki-links
  "Extract wiki-links from all messages."
  [messages]
  (->> messages
       (mapcat #(extract-wiki-links (:message/content %)))
       (group-by :entity)
       (map (fn [[entity mentions]]
              {:entity entity
               :contexts (mapv :context mentions)
               :mention-count (count mentions)}))
       vec))

;; ============================================================================
;; Summarization
;; ============================================================================

(def ^:private summarization-prompt
  "You are summarizing a portion of an AI assistant conversation for context compaction.

Create a concise summary that preserves:
1. Key decisions made
2. Important information discovered
3. Current task state/progress
4. Any entities mentioned (use [[Entity Name]] format)

Format guidelines:
- Be concise but complete
- Use bullet points for clarity
- Include [[Entity Name]] wiki-links for important concepts
- Note any unresolved questions or pending tasks

Messages to summarize:
%s

Summary:")

(defn- format-messages-for-summary
  "Format messages for the summarization prompt."
  [messages]
  (->> messages
       (map (fn [msg]
              (str "[" (name (:message/role msg)) "] "
                   (let [content (:message/content msg)]
                     (if (> (count content) 500)
                       (str (subs content 0 500) "...")
                       content)))))
       (str/join "\n\n")))

(defn summarize-messages
  "Generate a summary of messages using LLM.

   Uses a cheap/fast model to minimize cost."
  [messages {:keys [model provider]
             :or {model "accounts/fireworks/models/minimax-m2p5"
                  provider :fireworks}}]
  (let [formatted (format-messages-for-summary messages)
        prompt (format summarization-prompt formatted)
        response (model-chat/chat
                  [{:role "user" :content prompt}]
                  {:model model
                   :provider provider
                   :tools []})]
    (:content response)))

;; ============================================================================
;; Compaction Execution
;; ============================================================================

(defn create-compaction-message
  "Create a compaction summary message.

   This message marks the compaction point in history.
   Messages before this are stored but not sent to LLM."
  [summary wiki-links compacted-message-ids]
  {:message/id (random-uuid)
   :message/role :system
   :message/content (str "=== Context Summary ===\n\n"
                         summary
                         (when (seq wiki-links)
                           (str "\n\n--- Entities ---\n"
                                (str/join ", " (map :entity wiki-links)))))
   :message/compacted? true
   :message/important? true  ; Never compact the compaction message
   :message/summary summary
   :message/created-at (java.util.Date.)
   :db/doc "Compaction point - messages before this are archived"})

(defn compact!
  "Execute compaction on chat context.

   1. Selects messages to compact
   2. Generates summary via LLM (or uses provided summarizer)
   3. Extracts wiki-links for knowledge graph
   4. Marks original messages as compacted
   5. Inserts summary message as new compaction point

   Options:
   - :model - Model for summarization
   - :provider - Provider for summarization
   - :summarizer - Custom summarizer fn (fn [messages opts] -> summary-string)
                   Use this for testing with deterministic summaries
   - :on-wiki-links - Callback fn receiving extracted wiki-links

   Returns map with:
   - :compacted-count - Number of messages compacted
   - :tokens-saved - Approximate tokens freed
   - :wiki-links - Extracted wiki-links
   - :summary - Generated summary"
  [chat-ctx & {:keys [model provider summarizer on-wiki-links context-window]
               :or {model "accounts/fireworks/models/minimax-m2p5"
                    provider :fireworks
                    context-window 128000}}]
  (let [messages (chat-ctx/get-messages chat-ctx)
        tokens-to-remove (compaction-needed-tokens chat-ctx :context-window context-window)

        ;; Select messages to compact
        to-compact (select-for-compaction messages tokens-to-remove)]

    (if (empty? to-compact)
      {:compacted-count 0
       :tokens-saved 0
       :wiki-links []
       :summary nil}

      (let [;; Get the actual messages to compact
            compact-messages (mapv :msg to-compact)
            compact-indices (set (map :idx to-compact))

            ;; Generate summary (use custom summarizer if provided)
            summary (if summarizer
                      (summarizer compact-messages {:model model :provider provider})
                      (summarize-messages compact-messages
                                          {:model model :provider provider}))

            ;; Extract wiki-links from compacted messages
            wiki-links (extract-all-wiki-links compact-messages)

            ;; Also extract from summary (LLM may have added new ones)
            summary-links (extract-wiki-links summary)
            all-links (concat wiki-links
                              (map #(assoc % :from-summary true) summary-links))

            ;; Create compaction message
            compacted-ids (mapv #(-> % :msg :message/id) to-compact)
            compaction-msg (create-compaction-message summary wiki-links compacted-ids)

            ;; Calculate tokens saved
            tokens-saved (reduce + (map :tokens to-compact))]

        ;; Persist ghost snapshot before marking as compacted
        (when-let [conn (:db-conn chat-ctx)]
          ;; Ghost snapshot: preserve original messages for audit/recovery
          (try
            (dh/transact conn
                         [{:compaction-snapshot/id (java.util.UUID/randomUUID)
                           :compaction-snapshot/chat-id (:chat-id chat-ctx)
                           :compaction-snapshot/timestamp (java.util.Date.)
                           :compaction-snapshot/original-messages (pr-str (mapv :msg to-compact))
                           :compaction-snapshot/summary summary
                           :compaction-snapshot/token-count (long tokens-saved)}])
            (tel/log! {:level :debug :id :compaction/ghost-snapshot-saved
                       :data {:messages (count to-compact) :tokens tokens-saved}}
                      "Ghost snapshot saved")
            (catch Exception e
              (tel/log! {:level :warn :id :compaction/ghost-snapshot-failed :error e}
                        "Failed to save ghost snapshot"))))

        ;; Mark original messages as compacted in datahike (if they exist there)
        (when-let [conn (:db-conn chat-ctx)]
          (doseq [{:keys [msg]} to-compact]
            (when-let [msg-id (:message/id msg)]
              ;; Only update if message exists in datahike
              (when (dh/q '[:find ?e .
                            :in $ ?mid
                            :where [?e :message/id ?mid]]
                          @conn msg-id)
                (dh/transact conn [{:db/id [:message/id msg-id]
                                    :message/compacted? true}])))))

        ;; Add compaction message to chat
        ;; IMPORTANT: :compacted? true marks this as the compaction point
        ;; so get-active-messages knows to filter messages before this
        (chat-ctx/add-message! chat-ctx
                               {:role :system
                                :content (:message/content compaction-msg)
                                :important? true
                                :compacted? true})

        ;; Call wiki-links callback if provided
        (when (and on-wiki-links (seq all-links))
          (on-wiki-links all-links))

        {:compacted-count (count to-compact)
         :tokens-saved tokens-saved
         :wiki-links all-links
         :summary summary}))))

;; ============================================================================
;; Automatic Compaction Hook
;; ============================================================================

(defn maybe-compact!
  "Check if compaction is needed and execute if so.

   First attempts pruning (cheap — just strips old tool outputs).
   Only falls back to full LLM summarization if pruning isn't enough.

   Call this before each agent turn to keep context manageable.

   Returns nil if no compaction needed, compaction result otherwise."
  [chat-ctx & opts]
  (when (should-compact? chat-ctx)
    ;; Phase 1: Try pruning tool outputs first (free, no LLM call)
    (let [messages (chat-ctx/get-messages chat-ctx)
          [pruned-msgs tokens-recovered] (prune-tool-outputs messages)]
      (when (pos? tokens-recovered)
        (tel/log! {:level :info :id :compaction/pruned
                   :data {:tokens-recovered tokens-recovered}}
                  "Pruned old tool outputs")
        ;; Replace messages in chat context with pruned versions
        (chat-ctx/replace-messages! chat-ctx pruned-msgs))

      ;; Phase 2: Check if pruning was enough
      (if (should-compact? chat-ctx)
        (do
          (tel/log! {:level :info :id :compaction/triggered}
                    "Pruning insufficient, running full compaction")
          (let [result (apply compact! chat-ctx opts)]
            (tel/log! {:level :info :id :compaction/done
                       :data {:compacted (:compacted-count result)
                              :tokens-saved (:tokens-saved result)
                              :wiki-links (count (:wiki-links result))}}
                      "Compaction complete")
            result))
        ;; Pruning was enough
        {:compacted-count 0
         :tokens-saved tokens-recovered
         :wiki-links []
         :summary nil
         :pruning-only true}))))

;; ============================================================================
;; Context Filtering for API Calls
;; ============================================================================

(defn get-active-messages
  "Get messages to send to LLM, filtering out compacted ones.

   Returns messages from the most recent compaction point onwards,
   plus any important messages from before."
  [chat-ctx]
  (let [messages (chat-ctx/get-messages chat-ctx)

        ;; Find the most recent compaction point (system message with :compacted? true)
        compaction-idx (->> messages
                            (map-indexed vector)
                            (filter (fn [[_ msg]]
                                      (and (= :system (:message/role msg))
                                           (:message/compacted? msg))))
                            last
                            first)]

    ;; If no compaction point, return all messages
    (if-not compaction-idx
      messages
      (let [;; Get messages after compaction point
            after-compaction (subvec (vec messages) compaction-idx)

            ;; Get important messages from before compaction (but not too many)
            before-compaction (subvec (vec messages) 0 compaction-idx)
            important-before (->> before-compaction
                                  (filter :message/important?)
                                  (take 5))]

        ;; Combine: important-before + after-compaction
        (vec (concat important-before after-compaction))))))

;; ============================================================================
;; Manual Compaction Tool (for agents)
;; ============================================================================

(defn make-compact-tool
  "Create a tool that allows agents to manually trigger compaction.

   Useful when agent knows context is getting large or wants to
   checkpoint important information.

   Args:
     chat-ctx - The ChatContext to compact (not an atom - the tool
                gets the current context from the tool execution ctx)

   NOTE: For dynamic context access, pass the chat-ctx from the tool's
   execution context (:chat-ctx in ctx) rather than wrapping in an atom.
   This avoids fork-safety issues with different state management strategies."
  [chat-ctx]
  {:name "compact_context"
   :description "Manually compact conversation context to free up memory.

   Use this when:
   - You notice the conversation is getting long
   - You want to checkpoint important information
   - Before starting a new major task

   The compaction will summarize older messages while preserving
   important information and recent context."
   :parameters {:type "object"
                :properties {:reason {:type "string"
                                      :description "Why you're compacting (for logging)"}}}
   :execute (fn [{:keys [reason]} ctx]
              ;; Use chat-ctx from tool execution context if provided, else use the one passed at creation
              (let [active-ctx (or (:chat-ctx ctx) chat-ctx)
                    result (compact! active-ctx)]
                {:type :success
                 :content (str "Compaction complete. "
                               "Compacted " (:compacted-count result) " messages, "
                               "saved ~" (:tokens-saved result) " tokens.\n\n"
                               "Summary:\n" (:summary result))}))})

(comment
  ;; Example usage:

  ;; Check if compaction is needed
  (should-compact? chat-ctx)

  ;; Get tokens needed to remove
  (compaction-needed-tokens chat-ctx)

  ;; Extract wiki-links from text
  (extract-wiki-links "The [[User Authentication][auth system]] uses [[JWT tokens]].")
  ;; => [{:entity "User Authentication" :context "auth system"}
  ;;     {:entity "JWT tokens" :context "JWT tokens"}]

  ;; Manual compaction
  (compact! chat-ctx
            :model "accounts/fireworks/models/minimax-m2p5"
            :on-wiki-links (fn [links]
                             (println "Extracted:" links)))

  ;; Get active messages (after filtering compacted)
  (get-active-messages chat-ctx))
