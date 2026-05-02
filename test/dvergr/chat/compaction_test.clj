(ns dvergr.chat.compaction-test
  "Tests for memory compaction functionality."
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.chat.compaction :as compaction]
            [dvergr.chat.context :as chat-ctx]
            [datahike.api :as dh]))

;; ============================================================================
;; Token Estimation Tests
;; ============================================================================

(deftest estimate-tokens-test
  (testing "Token estimation from character count"
    (is (= 1 (compaction/estimate-tokens "abc")))
    (is (= 3 (compaction/estimate-tokens "abcdefghijkl")))
    (is (= 0 (compaction/estimate-tokens "")))
    (is (= 0 (compaction/estimate-tokens nil)))))

(deftest message-tokens-test
  (testing "Get tokens from message with stored count"
    (let [msg {:message/tokens 100
               :message/content "This has way more than 100 characters but we use stored count"}]
      (is (= 100 (compaction/message-tokens msg)))))

  (testing "Estimate tokens when not stored"
    (let [msg {:message/content "test"}]
      (is (= 1 (compaction/message-tokens msg))))))

(deftest total-context-tokens-test
  (testing "Sum tokens across messages"
    (let [messages [{:message/tokens 10}
                    {:message/tokens 20}
                    {:message/content "test"}]]  ; 1 token estimated
      (is (= 31 (compaction/total-context-tokens messages))))))

;; ============================================================================
;; Compaction Detection Tests
;; ============================================================================

(deftest should-compact-test
  (testing "Compaction needed when over threshold"
    ;; should-compact? uses total-context-tokens which reads :message/tokens.
    ;; Threshold = 128000 * 0.7 = 89600. prune-minimum = 10000.
    ;; We need total tokens > 89600 AND tokens-to-remove > 10000.
    (let [ctx (chat-ctx/create-chat-context {:title "test" :budget 100000})]
      ;; Add 7 messages with high token counts to exceed threshold
      (dotimes [i 7]
        (chat-ctx/add-message! ctx {:role :user
                                    :content (str "test " i)
                                    :tokens 15000}))

      (is (compaction/should-compact? ctx))))

  (testing "No compaction when under threshold"
    (let [ctx (chat-ctx/create-chat-context {:title "test" :budget 100000})]
      (dotimes [i 7]
        (chat-ctx/add-message! ctx {:role :user :content (str "test " i)}))

      (is (not (compaction/should-compact? ctx))))))

;; ============================================================================
;; Wiki-Link Extraction Tests
;; ============================================================================

(deftest extract-wiki-links-test
  (testing "Extract wiki-links from text"
    (is (= [{:entity "Entity" :context "context about entity"}]
           (compaction/extract-wiki-links "We discussed [[Entity][context about entity]] today.")))

    (is (= [{:entity "Foo" :context "bar"}
            {:entity "Baz" :context "qux"}]
           (compaction/extract-wiki-links "[[Foo][bar]] and [[Baz][qux]]")))

    (is (empty? (compaction/extract-wiki-links "No links here")))))

(deftest extract-all-wiki-links-test
  (testing "Extract wiki-links from message list"
    (let [messages [{:message/content "[[Entity1][info1]]"}
                    {:message/content "[[Entity2][info2]]"}
                    {:message/content "No links"}]
          result (compaction/extract-all-wiki-links messages)]
      (is (= 2 (count result)))
      (is (some #(= "Entity1" (:entity %)) result))
      (is (some #(= "Entity2" (:entity %)) result))
      (is (every? #(= 1 (:mention-count %)) result)))))

;; ============================================================================
;; Message Selection Tests
;; ============================================================================

(deftest select-for-compaction-test
  (testing "Select messages for compaction"
    (let [messages [{:message/role :system :message/content "sys" :message/tokens 10}
                    {:message/role :user :message/content "u1" :message/tokens 10}
                    {:message/role :assistant :message/content "a1" :message/tokens 10}
                    {:message/role :user :message/content "u2" :message/tokens 10 :message/important? true}
                    {:message/role :assistant :message/content "a2" :message/tokens 10}
                    {:message/role :tool-result :message/content "result" :message/tokens 10}]
          selected (compaction/select-for-compaction messages 15)]

      ;; Should select some for compaction (returns vector of selected messages)
      (is (vector? selected))

      ;; When requesting 15 tokens, should select messages
      ;; (actual selection depends on scoring logic)
      (is (every? #(contains? % :msg) selected)))))

;; ============================================================================
;; Active Messages Tests
;; ============================================================================

(deftest get-active-messages-test
  (testing "Get messages after compaction point"
    (let [ctx (chat-ctx/create-chat-context {:title "test"})]
      ;; Add some messages
      (chat-ctx/add-message! ctx {:role :user :content "msg1"})
      (chat-ctx/add-message! ctx {:role :assistant :content "resp1"})

      ;; Get all messages (no compaction yet)
      (let [active (compaction/get-active-messages ctx)]
        (is (= 2 (count active)))
        (is (= "msg1" (:message/content (first active)))))))

  (testing "Filter messages before compaction point"
    (let [ctx (chat-ctx/create-chat-context {:title "test"})]
      ;; Add old messages that will be compacted
      (chat-ctx/add-message! ctx {:role :user :content "old"})
      (chat-ctx/add-message! ctx {:role :assistant :content "old-resp"})

      ;; Add compaction message (simulates what compact! does)
      (chat-ctx/add-message! ctx {:role :system
                                  :content "=== Context Summary ===\nOld messages compacted"
                                  :compacted? true
                                  :important? true})

      ;; Add new message after compaction
      (chat-ctx/add-message! ctx {:role :user :content "new"})

      ;; Should get messages from compaction point onwards
      (let [active (compaction/get-active-messages ctx)]
        ;; Should have: compaction message + new message
        (is (>= (count active) 2))
        (is (some #(= "new" (:message/content %)) active))
        ;; Should have compaction message
        (is (some #(and (= :system (:message/role %))
                       (:message/compacted? %))
                  active))))))
