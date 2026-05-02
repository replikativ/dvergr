(ns dvergr.sessions
  "Session management for Telegram chat-id to agent mapping.

   Each Telegram chat-id gets a persistent session that holds:
   - An agent reference (from registry)
   - A ChatContext for conversation history
   - Timestamps for lifecycle tracking

   The flow:
     Telegram msg -> on-message callback -> get-or-create-session!
     -> agent/send! -> agent thinks -> outbox -> send-response! -> telegram_send

   Sessions are keyed by chat-id (integer from Telegram)."
  (:require [dvergr.registry :as registry]
            [dvergr.chat.context :as chat-ctx]
            [dvergr.channels.telegram :as tg]
            [clojure.string :as str]))

;; ============================================================================
;; Session State
;; ============================================================================

(defonce sessions (atom {}))  ;; [chat-id agent-id] -> session map

;; ============================================================================
;; Telegram Message Chunking
;; ============================================================================

(def ^:private telegram-max-length 4096)

(defn chunk-message
  "Split a message into chunks that respect Telegram's 4096-char limit.
   Tries to split at newline boundaries when possible."
  [text]
  (if (<= (count text) telegram-max-length)
    [text]
    (loop [remaining text
           chunks []]
      (if (<= (count remaining) telegram-max-length)
        (conj chunks remaining)
        (let [chunk (subs remaining 0 telegram-max-length)
              ;; Try to find a good split point (last newline in chunk)
              split-at (let [nl-idx (str/last-index-of chunk "\n")]
                         (if (and nl-idx (> nl-idx (/ telegram-max-length 2)))
                           (inc nl-idx) ;; include the newline
                           telegram-max-length))]
          (recur (subs remaining split-at)
                 (conj chunks (subs remaining 0 split-at))))))))

;; ============================================================================
;; Session CRUD
;; ============================================================================

(defn create-session!
  "Create a new session for a [chat-id agent-id] pair.

   Each agent gets its own conversation history per Telegram chat.

   Args:
     chat-id    - Telegram chat ID (integer)
     agent-id   - Registry agent ID (:var, :sentinel, etc.)
     user-info  - Map with :username, :first_name etc from Telegram

   Returns the created session map."
  [chat-id agent-id user-info]
  (let [session {:chat-id     chat-id
                 :agent-id    agent-id
                 :user-info   user-info
                 :chat-ctx    (chat-ctx/create-chat-context
                                {:title (str (name agent-id) " with "
                                             (or (:first_name user-info)
                                                 (:username user-info)
                                                 (str "user-" chat-id)))
                                 :budget-dollars 2.0})
                 :created-at  (java.util.Date.)
                 :last-active (java.util.Date.)}]
    (swap! sessions assoc [chat-id agent-id] session)
    session))

(defn get-session
  "Get session for a [chat-id agent-id] pair, or nil.
  When called with one arg, returns the most recently active session for chat-id."
  ([chat-id]
   (->> @sessions
        (filter (fn [[[cid _] _]] (= cid chat-id)))
        (sort-by (fn [[_ s]] (.getTime (or (:last-active s) (:created-at s)))) >)
        first
        second))
  ([chat-id agent-id]
   (get @sessions [chat-id agent-id])))

(defn get-or-create-session!
  "Get existing session or create a new one for this chat-id + agent-id pair.

   Each agent gets its own conversation context per Telegram chat, so
   switching between /sentinel and /var doesn't pollute histories.

   Args:
     chat-id  - Telegram chat ID
     agent-id - Agent ID (keyword)
     user-info - User info from Telegram message

   Returns the session map."
  [chat-id agent-id user-info]
  (if-let [session (get-session chat-id agent-id)]
    (do
      ;; Update last-active timestamp
      (swap! sessions assoc-in [[chat-id agent-id] :last-active] (java.util.Date.))
      session)
    (create-session! chat-id agent-id user-info)))

;; ============================================================================
;; Pending Fork Tracking
;; ============================================================================

(defn register-pending-fork!
  "Store the full result from ask! so merge!/discard! can be called later.

   result - the map returned by prim/ask! (contains :child-ctx, :messages, etc.)
   summary - human-readable description of what the worker did"
  [chat-id agent-id result summary]
  (swap! sessions assoc-in [[chat-id agent-id] :pending-fork]
         {:result     result
          :summary    summary
          :created-at (java.util.Date.)})
  nil)

(defn get-pending-fork
  "Get the pending fork for a [chat-id agent-id], or nil."
  [chat-id agent-id]
  (get-in @sessions [[chat-id agent-id] :pending-fork]))

(defn clear-pending-fork!
  "Remove pending fork tracking (after merge! or discard!)."
  [chat-id agent-id]
  (swap! sessions update [chat-id agent-id] dissoc :pending-fork)
  nil)

(defn describe-pending
  "Human-readable description of pending work, or nil if none."
  [chat-id agent-id]
  (when-let [pending (get-pending-fork chat-id agent-id)]
    (str "Pending work from " (:created-at pending) ":\n"
         (:summary pending)
         "\n\nSay **merge** to apply or **discard** to cancel.")))

(defn close-session!
  "Close and remove a session.

   Optionally closes the chat context."
  [chat-id agent-id & {:keys [close-chat?] :or {close-chat? true}}]
  (when-let [session (get-session chat-id agent-id)]
    (when (and close-chat? (:chat-ctx session))
      (try
        (chat-ctx/close-chat! (:chat-ctx session))
        (catch Exception _)))
    (swap! sessions dissoc [chat-id agent-id])
    :closed))

;; ============================================================================
;; Session Listing
;; ============================================================================

(defn list-sessions
  "List all active sessions.

   Returns vector of maps with :chat-id, :agent-id, :user-info, :created-at, :last-active."
  []
  (mapv (fn [[_key session]]
          (select-keys session [:chat-id :agent-id :user-info :created-at :last-active]))
        @sessions))

(defn session-count
  "Get the number of active sessions."
  []
  (count @sessions))

;; ============================================================================
;; Markdown to Telegram HTML Conversion
;; ============================================================================

(defn- escape-html
  "Escape HTML special characters in text."
  [^String s]
  (-> s
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- apply-inline
  "Apply inline markdown transforms to HTML-escaped text:
   links, bold, italic, inline code."
  [s]
  (-> s
      ;; Links: [text](url)
      (str/replace #"\[([^\]\n]*)\]\(([^\)\n]*)\)" "<a href=\"$2\">$1</a>")
      ;; Bold: **text** (before single-star italic)
      (str/replace #"\*\*([^*\n]+)\*\*" "<b>$1</b>")
      ;; Bold: __text__
      (str/replace #"__([^_\n]+)__" "<b>$1</b>")
      ;; Italic: *text* (single star)
      (str/replace #"(?<!\*)\*([^*\n]+)\*(?!\*)" "<i>$1</i>")
      ;; Italic: _text_ (single underscore)
      (str/replace #"(?<!_)_([^_\n]+)_(?!_)" "<i>$1</i>")
      ;; Inline code
      (str/replace #"`([^`\n]+)`" "<code>$1</code>")))

(defn- process-text-line
  "Convert a single markdown line (not inside a code block or table) to Telegram HTML."
  [line]
  (let [t (str/trim line)]
    (cond
      (str/blank? t) nil
      ;; Heading: # to ######
      (re-find #"^#{1,6}\s" t)
      (str "<b>" (apply-inline (escape-html (str/replace-first t #"^#+\s+" ""))) "</b>")
      ;; Unordered list
      (re-find #"^[-*+]\s" t)
      (str "• " (apply-inline (escape-html (str/replace-first t #"^[-*+]\s+" ""))))
      ;; Ordered list
      (re-find #"^\d+\.\s" t)
      (let [[_ n text] (re-find #"^(\d+)\.\s+(.*)" t)]
        (str n ". " (apply-inline (escape-html text))))
      ;; Blockquote
      (str/starts-with? t "> ")
      (str "<blockquote>" (apply-inline (escape-html (subs t 2))) "</blockquote>")
      ;; Horizontal rule — discard
      (re-matches #"[-*_=]{3,}\s*" t) nil
      ;; Regular text
      :else (apply-inline (escape-html line)))))

(defn- table-sep?
  "True if a table row is a separator line (|---|---|)."
  [row]
  (re-matches #"[\s\-:+]+" (str/replace row "|" "")))

(defn- render-table
  "Convert accumulated markdown table rows to Telegram HTML (bold header + plain rows)."
  [rows]
  (let [parse-row (fn [row]
                    (->> (str/split row #"\|")
                         (map str/trim)
                         (remove str/blank?)))
        data (remove table-sep? rows)]
    (when (seq data)
      (let [[hdr & body] data
            hdr-cells (parse-row hdr)
            hdr-line (str "<b>" (str/join " | " (map #(apply-inline (escape-html %)) hdr-cells)) "</b>")]
        (str hdr-line
             (when (seq body)
               (str "\n"
                    (str/join "\n"
                      (map (fn [row]
                             (str/join " | " (map #(apply-inline (escape-html %)) (parse-row row))))
                           body)))))))))

(defn markdown->telegram-html
  "Convert Markdown text to Telegram HTML for use with parse_mode=HTML.

   Converts: headings→bold, **bold**, *italic*, [links](url), `code`,
   fenced code blocks, list items→bullets, blockquotes, tables→plain text."
  [text]
  (let [s (str text)]
    (when (seq s)
      (let [sb (StringBuilder.)]
        (loop [[line & rest :as lines] (str/split-lines s)
               in-code? false
               code-buf []
               tbl-buf []]
          (if (empty? lines)
            (do
              (when in-code?
                (.append sb "<pre><code>")
                (.append sb (escape-html (str/join "\n" code-buf)))
                (.append sb "</code></pre>"))
              (when (seq tbl-buf)
                (when-let [t (render-table tbl-buf)]
                  (.append sb t)))
              (str sb))
            (let [trimmed (str/trim line)]
              (cond
                ;; Code fence start
                (and (not in-code?) (str/starts-with? trimmed "```"))
                (recur rest true [] tbl-buf)

                ;; Code fence end
                (and in-code? (str/starts-with? trimmed "```"))
                (do
                  (.append sb "<pre><code>")
                  (.append sb (escape-html (str/join "\n" code-buf)))
                  (.append sb "</code></pre>\n")
                  (recur rest false [] tbl-buf))

                ;; Inside code block — accumulate
                in-code?
                (recur rest true (conj code-buf line) tbl-buf)

                ;; Table row (starts with |)
                (str/starts-with? trimmed "|")
                (recur rest false [] (conj tbl-buf trimmed))

                ;; Non-table line after accumulated table — flush table first
                (seq tbl-buf)
                (do
                  (when-let [t (render-table tbl-buf)]
                    (.append sb t)
                    (.append sb "\n"))
                  (when-let [out (process-text-line line)]
                    (.append sb out)
                    (.append sb "\n"))
                  (recur rest false [] []))

                ;; Normal line
                :else
                (do
                  (when-let [out (process-text-line line)]
                    (.append sb out)
                    (.append sb "\n"))
                  (recur rest false [] []))))))))))

;; ============================================================================
;; Response Sending
;; ============================================================================

(defn send-response!
  "Send a response back to a Telegram chat, converting Markdown to HTML.

   Args:
     token   - Telegram bot token
     chat-id - Telegram chat ID
     text    - Response text (converted to Telegram HTML, chunked if > 4096 chars)"
  [token chat-id text]
  (let [html-text (or (markdown->telegram-html (str text)) (str text))
        chunks (chunk-message html-text)]
    (doseq [chunk chunks]
      (try
        (#'tg/api-call token "sendMessage"
                       {:chat_id chat-id
                        :text chunk
                        :parse_mode "HTML"})
        (catch Exception _
          ;; Fallback: strip HTML tags and send as plain text
          (try
            (#'tg/api-call token "sendMessage"
                           {:chat_id chat-id
                            :text (str/replace chunk #"<[^>]+>" "")})
            (catch Exception e2
              (binding [*err* *err*]
                (.println *err* (str "dvergr-sessions: send error: " (.getMessage e2)))
                (.flush *err*)))))))))

;; ============================================================================
;; Cleanup
;; ============================================================================

(defn clear-all!
  "Clear all sessions. Use with caution."
  []
  (reset! sessions {})
  :cleared)
