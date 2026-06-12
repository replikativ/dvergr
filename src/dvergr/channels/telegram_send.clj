(ns dvergr.channels.telegram-send
  "Telegram OUTBOUND rendering — the rich reply path for the Telegram channel:
   Markdown→Telegram-HTML conversion, 4096-char chunking, the collapsible
   tool-activity blockquote, and `send-response!` (HTML send with a plain-text
   fallback on rejection). The daemon's Telegram `:send-fn` is `send-response!`.

   (Was `dvergr.orchestration.sessions` — a legacy per-chat ChatContext session
   table that nothing populated after the per-room refactor. The dead session
   machinery is gone; what remains is purely Telegram transport, so it now lives
   under `channels/` next to `channels.telegram`.)"
  (:require [dvergr.channels.telegram :as tg]
            [clojure.string :as str]
            [taoensso.telemere :as tel]))

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

(defn html-escape
  "Escape a string for Telegram HTML parse mode (&, <, >)."
  [s]
  (-> (str s) (str/replace "&" "&amp;") (str/replace "<" "&lt;") (str/replace ">" "&gt;")))

(defn tool-activity-html
  "An expandable Telegram blockquote summarising a turn's tool activity, or nil.
   `lines` are plain summary strings (e.g. '🔧 clojure_eval'). Collapsed by
   default so the channel reflects the internal turn without polluting it."
  [lines]
  (when-let [ls (seq (remove str/blank? lines))]
    (str "<blockquote expandable>"
         (html-escape (str/join "\n" ls))
         "</blockquote>")))

(defn send-response!
  "Send a response back to a Telegram chat, converting Markdown to HTML.

   Args:
     token     - Telegram bot token
     chat-id   - Telegram chat ID
     text      - Response text (converted to Telegram HTML, chunked if > 4096)
     html-suffix - optional RAW HTML appended AFTER conversion (not escaped) —
                   e.g. a `tool-activity-html` expandable blockquote."
  ([token chat-id text] (send-response! token chat-id text nil))
  ([token chat-id text html-suffix]
   (let [html-text (str (or (markdown->telegram-html (str text)) (str text))
                        (when html-suffix (str "\n" html-suffix)))
         chunks (chunk-message html-text)]
     (doseq [chunk chunks]
       (try
         (tg/api-call token "sendMessage"
                      {:chat_id chat-id
                       :text chunk
                       :parse_mode "HTML"})
         (tel/log! {:level :debug :id :telegram/sent
                    :data {:chat-id chat-id :chars (count chunk) :mode :html}})
         (catch Exception e1
          ;; HTML parse rejected (e.g. bad entities) — retry as plain text. This
          ;; is recoverable, so :warn (not lost): a recurring warn here means our
          ;; markdown→HTML is producing entities Telegram won't accept.
           (tel/log! {:level :warn :id :telegram/html-rejected
                      :data {:chat-id chat-id :chars (count chunk)
                             :error (.getMessage e1)}})
           (try
             (tg/api-call token "sendMessage"
                          {:chat_id chat-id
                           :text (str/replace chunk #"<[^>]+>" "")})
             (tel/log! {:level :debug :id :telegram/sent
                        :data {:chat-id chat-id :chars (count chunk) :mode :plain-fallback}})
             (catch Exception e2
              ;; Both HTML and plain failed → the message is DROPPED. This is a
              ;; real, user-visible loss (e.g. a 429 rate-limit) — never swallow.
               (tel/log! {:level :error :id :telegram/send-failed
                          :data {:chat-id chat-id :chars (count chunk)
                                 :error (.getMessage e2)}})))))))))

