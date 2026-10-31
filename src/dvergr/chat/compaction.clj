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

   Each summary records the exact original message identities it covers.
   Originals remain stored; uncovered messages and prior summaries stay active.

   Key differences from simple truncation:
   - Important messages always preserved
   - Wiki-links build project knowledge over time
   - Notes capture factual, invariant knowledge about entities
   - Summary maintains context continuity"
  (:require [dvergr.chat.context :as chat-ctx]
            [dvergr.model.chat :as model-chat]
            [clojure.string :as str]
            [clojure.set :as set]
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
                           "This clips only what is PRINTED — any value you computed is intact. "
                           "Don't re-print it: in clojure_eval capture it with `def` and reduce it "
                           "programmatically (filter/select/count/parse) so you return only the small "
                           "result you need; for a file or URL, read/fetch a slice instead of the whole.]\n\n")]
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
           (let [orig-tokens (message-tokens msg)
                 content (str "[tool output pruned — was " orig-tokens " tokens]")
                 remaining (estimate-tokens content)]
             (swap! tokens-recovered + (- orig-tokens remaining))
             (assoc msg
                    :message/content content
                    :message/tokens remaining
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

(defn- selection-groups
  "Keep an assistant's tool calls and their result messages in one selection unit.
   Reused tool IDs conservatively connect overlapping groups, so protection and
   token accounting apply once to the entire connected component."
  [messages]
  (let [indexed (vec (map-indexed vector messages))
        tool-groups
        (for [[idx msg] indexed
              :let [ids (set (keep :tool-use/id (:message/tool-uses msg)))]
              :when (and (= :assistant (:message/role msg)) (seq ids))]
          (into #{idx}
                (keep (fn [[result-idx result]]
                        (when (and (= :tool-result (:message/role result))
                                   (contains? ids (:message/tool-use-id result)))
                          result-idx)))
                indexed))
        components
        (reduce (fn [components group]
                  (let [overlaps? #(seq (set/intersection group %))
                        joined (reduce set/union group (filter overlaps? components))]
                    (conj (vec (remove overlaps? components)) joined)))
                [] tool-groups)
        grouped (apply set/union #{} components)]
    (concat components
            (for [[idx _] indexed :when (not (contains? grouped idx))] #{idx}))))

(defn select-for-compaction
  "Select older active messages, protecting recent turns and tool call/result groups.
   Noncontiguous selection is intentional; only the selected identities are covered.
   Results are returned in source order, regardless of selection priority."
  [messages tokens-to-remove]
  (let [messages (vec messages)
        protected-start (- (count messages) (count-turns-from-end messages protected-turns))
        eligible? (fn [idx]
                    (and (< idx protected-start)
                         (message-compactable? (messages idx))))
        score (fn [idx]
                (case (:message/role (messages idx))
                  :tool-result 100
                  :assistant 50
                  10))
        candidates (->> (selection-groups messages)
                        (filter #(every? eligible? %))
                        (map (fn [indices]
                               {:indices indices
                                :score (apply max (map score indices))
                                :tokens (reduce + (map #(message-tokens (messages %)) indices))}))
                        (sort-by (juxt (comp - :score) (comp #(apply min %) :indices))))]
    (loop [selected #{} remaining candidates tokens-selected 0]
      (if (or (>= tokens-selected tokens-to-remove) (empty? remaining))
        (mapv (fn [idx] {:idx idx :msg (messages idx)
                         :tokens (message-tokens (messages idx))})
              (sort selected))
        (let [{:keys [indices tokens]} (first remaining)]
          (recur (into selected indices) (rest remaining) (+ tokens-selected tokens)))))))

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
             :or {model "accounts/fireworks/models/minimax-m3"
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

   Only these exact message identities are archived from the active projection.
   The summary does not imply coverage of any other earlier message."
  [summary wiki-links compacted-message-ids]
  (when-not (and (seq compacted-message-ids) (every? uuid? compacted-message-ids))
    (throw (ex-info "Compaction requires exact original message identities"
                    {:type ::invalid-coverage})))
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
   :message/compacted-message-ids (vec compacted-message-ids)
   :message/created-at (java.util.Date.)
   :db/doc "Summary covering exactly :message/compacted-message-ids"})

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
               :or {model "accounts/fireworks/models/minimax-m3"
                    provider :fireworks
                    context-window 128000}}]
  (let [messages (get-active-messages chat-ctx)
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

        ;; Add a summary with exact coverage. Its position does not archive the
        ;; protected recent tail or any unselected earlier messages.
        (chat-ctx/add-message! chat-ctx
                               {:role :system
                                :content (:message/content compaction-msg)
                                :important? true
                                :compacted? true
                                :compacted-message-ids compacted-ids})

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
  (when (apply should-compact? chat-ctx opts)
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
      (if (apply should-compact? chat-ctx opts)
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
  "Project only messages not explicitly covered by a summary.

   Summaries and uncovered originals remain in source order. Markers without exact
   coverage cannot archive any originals: retain those originals and expose their
   marker identities in result metadata instead of silently losing context."
  [chat-ctx]
  (let [messages (chat-ctx/get-messages chat-ctx)
        summaries (filter #(and (= :system (:message/role %))
                                (:message/compacted? %)) messages)
        covered (into #{} (mapcat :message/compacted-message-ids) summaries)
        unknown (into [] (comp (remove #(seq (:message/compacted-message-ids %)))
                               (map :message/id)) summaries)]
    (with-meta
      (filterv #(not (contains? covered (:message/id %))) messages)
      (when (seq unknown) {:compaction/coverage-unavailable-ids unknown}))))

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
            :model "accounts/fireworks/models/minimax-m3"
            :on-wiki-links (fn [links]
                             (println "Extracted:" links)))

  ;; Get active messages (after filtering compacted)
  (get-active-messages chat-ctx))
