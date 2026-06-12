(ns dvergr.channels.telegram-send-test
  "Tests for the Telegram outbound rendering (chunking + markdown→HTML +
   tool-activity blockquote). Was `dvergr.orchestration.sessions-test`; the dead
   session-CRUD tests went away with the session machinery."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [dvergr.channels.telegram-send :as tg-send]))

;; ============================================================================
;; Chunking
;; ============================================================================

(deftest test-chunk-message-short
  (testing "Short messages are not chunked"
    (let [chunks (tg-send/chunk-message "Hello, world!")]
      (is (= 1 (count chunks)))
      (is (= "Hello, world!" (first chunks))))))

(deftest test-chunk-message-long
  (testing "Long messages are chunked at 4096"
    (let [long-msg (apply str (repeat 5000 "x"))
          chunks (tg-send/chunk-message long-msg)]
      (is (> (count chunks) 1))
      (is (every? #(<= (count %) 4096) chunks))
      (is (= long-msg (apply str chunks))))))

(deftest test-chunk-message-newline-split
  (testing "Chunking prefers newline boundaries"
    (let [lines (repeatedly 200 #(apply str (repeat 30 "a")))
          long-msg (str/join "\n" lines)
          chunks (tg-send/chunk-message long-msg)]
      (is (> (count chunks) 1))
      (is (every? #(<= (count %) 4096) chunks)))))

(deftest test-chunk-message-empty
  (testing "Empty message returns single empty string"
    (let [chunks (tg-send/chunk-message "")]
      (is (= 1 (count chunks)))
      (is (= "" (first chunks))))))

;; ============================================================================
;; Markdown → Telegram HTML
;; ============================================================================

(deftest test-markdown->html-basic
  (testing "Bold, italic and inline code convert to Telegram HTML"
    (let [html (tg-send/markdown->telegram-html "**bold** *italic* `code`")]
      (is (str/includes? html "<b>bold</b>"))
      (is (str/includes? html "<code>code</code>")))))

(deftest test-markdown->html-escapes
  (testing "Raw angle brackets in code are HTML-escaped, not interpreted"
    (let [html (tg-send/markdown->telegram-html "`a<b>c`")]
      (is (str/includes? html "&lt;b&gt;")))))

;; ============================================================================
;; Tool-activity blockquote
;; ============================================================================

(deftest test-tool-activity-html
  (testing "Non-blank lines become an expandable blockquote; blanks → nil"
    (is (nil? (tg-send/tool-activity-html [])))
    (is (nil? (tg-send/tool-activity-html ["" "  "])))
    (let [html (tg-send/tool-activity-html ["🔧 clojure_eval" "🔧 glob"])]
      (is (str/includes? html "<blockquote expandable>"))
      (is (str/includes? html "clojure_eval")))))
