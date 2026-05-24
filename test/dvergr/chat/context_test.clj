(ns dvergr.chat.context-test
  "Integration tests for ChatContext budget tracking."
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.chat.context :as ctx]
            [dvergr.chat.accounting :as acct]))

(deftest create-chat-context-test
  (testing "Create chat with dollar budget"
    (let [chat (ctx/create-chat-context {:title "Test"
                                          :budget-dollars 1.0
                                          :with-sci? false})]
      (is (some? chat))
      (is (= "Test" (:title chat)))
      (let [budget (ctx/get-budget chat)]
        (is (= 1000000 (:total budget)))
        (is (= 0 (:used budget)))))))

(deftest account-usage-test
  (testing "Basic token accounting"
    (let [chat (ctx/create-chat-context {:budget-dollars 0.01 :with-sci? false})]
      ;; Account for 1000 input tokens (Claude Sonnet: 3 microdollars per token)
      (ctx/account-usage! chat :input-tokens 1000
                         :model "claude-sonnet-4-5")
      (let [budget (ctx/get-budget chat)]
        (is (= 3000 (:used budget)))
        (is (= 1000 (get-in budget [:by-type :input-tokens]))))))

  (testing "Multiple accountings accumulate"
    (let [chat (ctx/create-chat-context {:budget-dollars 0.01 :with-sci? false})]
      (ctx/account-usage! chat :input-tokens 500
                         :model "claude-sonnet-4-5")
      (ctx/account-usage! chat :output-tokens 300
                         :model "claude-sonnet-4-5")
      (let [budget (ctx/get-budget chat)]
        ;; 500 * 3 + 300 * 15 = 1500 + 4500 = 6000
        (is (= 6000 (:used budget)))
        (is (= 500 (get-in budget [:by-type :input-tokens])))
        (is (= 300 (get-in budget [:by-type :output-tokens])))))))

(deftest threshold-crossing-test
  (testing "50% threshold crossing"
    (let [chat (ctx/create-chat-context {:budget-dollars 0.01 :with-sci? false})]
      ;; Cross 50% (need 5000 microdollars, 1667 tokens @ $3/MTok)
      (let [result (ctx/account-usage! chat :input-tokens 1667
                                      :model "claude-sonnet-4-5")]
        (is (:threshold-crossed? result))
        (is (= :info (:threshold-level result)))
        (is (= "Budget: 50% used" (:threshold-message result)))
        ;; Check crossed-thresholds set
        (is (contains? (:crossed-thresholds (ctx/get-budget chat)) 0.5)))))

  (testing "Multiple threshold crossings"
    (let [chat (ctx/create-chat-context {:budget-dollars 0.01 :with-sci? false})]
      ;; Cross 50%
      (ctx/account-usage! chat :input-tokens 1667
                         :model "claude-sonnet-4-5")
      ;; Cross 75%
      (let [result (ctx/account-usage! chat :input-tokens 833
                                      :model "claude-sonnet-4-5")]
        (is (:threshold-crossed? result))
        (is (= :notice (:threshold-level result))))
      ;; Check both thresholds recorded
      (let [crossed (:crossed-thresholds (ctx/get-budget chat))]
        (is (contains? crossed 0.5))
        (is (contains? crossed 0.75)))))

  (testing "Same threshold doesn't trigger twice"
    (let [chat (ctx/create-chat-context {:budget-dollars 0.01 :with-sci? false})]
      ;; Cross 50%
      (ctx/account-usage! chat :input-tokens 1667
                         :model "claude-sonnet-4-5")
      ;; Try to cross 50% again - should not trigger
      (let [result (ctx/account-usage! chat :input-tokens 50
                                      :model "claude-sonnet-4-5")]
        (is (not (:threshold-crossed? result)))))))

(deftest budget-checking-test
  (testing "Budget remaining calculation"
    (let [chat (ctx/create-chat-context {:budget-dollars 1.0 :with-sci? false})]
      (is (= 1000000 (ctx/budget-remaining chat)))
      (ctx/account-usage! chat :input-tokens 1000
                         :model "claude-sonnet-4-5")
      (is (= 997000 (ctx/budget-remaining chat)))))

  (testing "Budget exceeded detection"
    (let [chat (ctx/create-chat-context {:budget-dollars 0.001 :with-sci? false})]
      (is (not (ctx/budget-exceeded? chat)))
      ;; Use more than budget (1000 microdollars)
      (ctx/account-usage! chat :input-tokens 500
                         :model "claude-sonnet-4-5")  ; 1500 microdollars
      (is (ctx/budget-exceeded? chat)))))

(deftest add-message-test
  (testing "Message addition"
    (let [chat (ctx/create-chat-context {:budget-dollars 1.0 :with-sci? false})]
      (ctx/add-message! chat {:role :user :content "Hello"})
      (let [messages (ctx/get-messages chat)]
        (is (= 1 (count messages)))
        (is (= :user (:message/role (first messages))))
        (is (= "Hello" (:message/content (first messages)))))))

  (testing "Important messages marked"
    (let [chat (ctx/create-chat-context {:budget-dollars 1.0 :with-sci? false})]
      (ctx/add-message! chat {:role :system
                              :content "Important!"
                              :important? true})
      (let [msg (first (ctx/get-messages chat))]
        (is (:message/important? msg))))))

(deftest sub-chat-forking-test
  (testing "Fork sub-chat with budget allocation"
    (let [parent (ctx/create-chat-context {:budget-dollars 1.0 :with-sci? false})
          child (ctx/fork-sub-chat parent {:title "Sub-task"
                                            :budget-dollars 0.2})]
      (is (some? child))
      (is (= "Sub-task" (:title child)))
      ;; Child gets allocated budget
      (is (= 200000 (:total (ctx/get-budget child))))
      ;; Parent's used increases (allocation)
      (is (= 200000 (:used (ctx/get-budget parent))))))

  (testing "Sub-chat can't exceed parent budget"
    (let [parent (ctx/create-chat-context {:budget-dollars 0.01 :with-sci? false})]
      ;; Try to allocate more than parent has
      (is (thrown? Exception
                   (ctx/fork-sub-chat parent {:title "Greedy"
                                               :budget-dollars 1.0}))))))

(deftest status-management-test
  (testing "Initial status is active"
    (let [chat (ctx/create-chat-context {:budget-dollars 1.0 :with-sci? false})]
      (is (= :active (ctx/get-status chat)))))

  (testing "Status can be changed"
    (let [chat (ctx/create-chat-context {:budget-dollars 1.0 :with-sci? false})]
      (ctx/set-status! chat :paused)
      (is (= :paused (ctx/get-status chat)))
      (ctx/set-status! chat :completed)
      (is (= :completed (ctx/get-status chat))))))
