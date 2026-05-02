(ns dvergr.sessions-test
  "Tests for session management."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [dvergr.sessions :as sessions]))

;; ============================================================================
;; Fixtures
;; ============================================================================

(use-fixtures :each
  (fn [f]
    (let [orig @sessions/sessions]
      (try
        (f)
        (finally
          (reset! sessions/sessions orig))))))

;; ============================================================================
;; Chunking Tests
;; ============================================================================

(deftest test-chunk-message-short
  (testing "Short messages are not chunked"
    (let [chunks (sessions/chunk-message "Hello, world!")]
      (is (= 1 (count chunks)))
      (is (= "Hello, world!" (first chunks))))))

(deftest test-chunk-message-long
  (testing "Long messages are chunked at 4096"
    (let [long-msg (apply str (repeat 5000 "x"))
          chunks (sessions/chunk-message long-msg)]
      (is (> (count chunks) 1))
      (is (every? #(<= (count %) 4096) chunks))
      (is (= long-msg (apply str chunks))))))

(deftest test-chunk-message-newline-split
  (testing "Chunking prefers newline boundaries"
    (let [lines (repeatedly 200 #(apply str (repeat 30 "a")))
          long-msg (clojure.string/join "\n" lines)
          chunks (sessions/chunk-message long-msg)]
      (is (> (count chunks) 1))
      (is (every? #(<= (count %) 4096) chunks)))))

(deftest test-chunk-message-empty
  (testing "Empty message returns single empty string"
    (let [chunks (sessions/chunk-message "")]
      (is (= 1 (count chunks)))
      (is (= "" (first chunks))))))

;; ============================================================================
;; Session Lifecycle Tests
;; Sessions are keyed by [chat-id agent-id] — each agent has its own
;; conversation history per chat.
;; ============================================================================

(deftest test-create-and-get-session
  (testing "Create and retrieve a session by [chat-id agent-id]"
    (let [session (sessions/create-session! 12345 :var
                                            {:username "testuser"
                                             :first_name "Test"})]
      (is (= 12345 (:chat-id session)))
      (is (= :var (:agent-id session)))
      (is (= "testuser" (get-in session [:user-info :username])))
      (is (some? (:chat-ctx session)))
      (is (instance? java.util.Date (:created-at session)))

      (let [got (sessions/get-session 12345 :var)]
        (is (= 12345 (:chat-id got)))
        (is (= :var (:agent-id got)))))))

(deftest test-get-session-missing
  (testing "Get missing session returns nil"
    (is (nil? (sessions/get-session 99999 :var)))))

(deftest test-multiple-agents-same-chat
  (testing "Each agent gets its own session per chat"
    (sessions/create-session! 42 :var {:username "u"})
    (sessions/create-session! 42 :sentinel {:username "u"})
    (is (= :var (:agent-id (sessions/get-session 42 :var))))
    (is (= :sentinel (:agent-id (sessions/get-session 42 :sentinel))))))

(deftest test-get-or-create-session-new
  (testing "get-or-create creates new session when none exists"
    (let [session (sessions/get-or-create-session! 42 :var {:username "newuser"})]
      (is (= 42 (:chat-id session)))
      (is (= :var (:agent-id session))))))

(deftest test-get-or-create-session-existing
  (testing "get-or-create returns existing session for same [chat-id agent-id]"
    (sessions/create-session! 42 :var {:username "user1"})
    (let [session (sessions/get-or-create-session! 42 :var {:username "user1"})]
      (is (= :var (:agent-id session))))))

(deftest test-close-session
  (testing "Close removes a specific [chat-id agent-id] session"
    (sessions/create-session! 200 :var {:username "u"})
    (is (some? (sessions/get-session 200 :var)))
    (sessions/close-session! 200 :var :close-chat? false)
    (is (nil? (sessions/get-session 200 :var)))))

(deftest test-close-session-leaves-other-agents
  (testing "Closing one agent's session leaves other agents intact"
    (sessions/create-session! 300 :var {:username "u"})
    (sessions/create-session! 300 :sentinel {:username "u"})
    (sessions/close-session! 300 :var :close-chat? false)
    (is (nil? (sessions/get-session 300 :var)))
    (is (some? (sessions/get-session 300 :sentinel)))))

(deftest test-list-sessions
  (testing "List all sessions"
    (sessions/create-session! 1 :a {:username "u1"})
    (sessions/create-session! 2 :b {:username "u2"})
    (let [listed (sessions/list-sessions)]
      (is (= 2 (count listed)))
      (is (every? :chat-id listed))
      (is (every? :agent-id listed)))))

(deftest test-session-count
  (testing "Session count"
    (is (= 0 (sessions/session-count)))
    (sessions/create-session! 1 :a {:username "u1"})
    (is (= 1 (sessions/session-count)))
    (sessions/create-session! 2 :b {:username "u2"})
    (is (= 2 (sessions/session-count)))))

(deftest test-clear-all
  (testing "Clear all sessions"
    (sessions/create-session! 1 :a {:username "u1"})
    (sessions/create-session! 2 :b {:username "u2"})
    (is (= 2 (sessions/session-count)))
    (sessions/clear-all!)
    (is (= 0 (sessions/session-count)))))
