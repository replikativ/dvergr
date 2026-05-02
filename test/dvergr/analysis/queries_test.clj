(ns dvergr.analysis.queries-test
  "Minimal smoke tests for query helpers."
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.analysis.queries :as q]
            [dvergr.chat.context :as ctx]
            [datahike.api :as d]))

(deftest query-helpers-smoke-test
  (testing "Query helpers work with a minimal chat context"
    (let [;; Create a minimal test chat
          chat (ctx/create-chat-context {:title "Test Query Helpers"
                                          :budget-dollars 0.01
                                          :with-sci? false})
          db-conn (:db-conn chat)
          chat-id (:chat-id chat)]

      ;; Add a test message
      (ctx/add-message! chat {:role :user :content "Test message"})

      ;; Test message queries
      (testing "get-chat-messages returns messages"
        (let [messages (q/get-chat-messages @db-conn chat-id)]
          (is (vector? messages))
          (is (= 1 (count messages)))
          (is (= :user (:message/role (first messages))))))

      (testing "get-message-count returns correct count"
        (is (= 1 (q/get-message-count @db-conn chat-id)))
        (is (= 1 (q/get-message-count @db-conn chat-id :user)))
        (is (= 0 (q/get-message-count @db-conn chat-id :assistant))))

      (testing "get-recent-messages works"
        (let [recent (q/get-recent-messages @db-conn chat-id 5)]
          (is (vector? recent))
          (is (<= (count recent) 5))))

      ;; Test budget queries
      (testing "get-budget-status returns budget info"
        (let [budget (q/get-budget-status @db-conn chat-id)]
          (is (some? budget))
          ;; Budget should be a tuple of [total used remaining]
          (is (= 3 (count budget)))))

      (testing "summarize-chat returns comprehensive summary"
        (let [summary (q/summarize-chat @db-conn chat-id)]
          (is (map? summary))
          (is (contains? summary :chat-id))
          (is (contains? summary :messages))
          (is (contains? summary :budget))
          (is (contains? summary :tools)))))))

(deftest aggregation-helpers-smoke-test
  (testing "Aggregation helpers work"
    (let [chat (ctx/create-chat-context {:title "Test Aggregation"
                                          :budget-dollars 0.01
                                          :with-sci? false})
          db-conn (:db-conn chat)
          chat-id (:chat-id chat)]

      ;; Add some messages
      (ctx/add-message! chat {:role :user :content "Message 1"})
      (ctx/add-message! chat {:role :assistant :content "Reply 1"})
      (ctx/add-message! chat {:role :user :content "Message 2"})

      (testing "summarize-chat aggregates correctly"
        (let [summary (q/summarize-chat @db-conn chat-id)]
          (is (= 3 (get-in summary [:messages :total])))
          (is (= 2 (get-in summary [:messages :by-role :user])))
          (is (= 1 (get-in summary [:messages :by-role :assistant]))))))))
