(ns dvergr.chat.context-test
  "Integration tests for ChatContext budget tracking."
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.chat.context :as ctx]
            [dvergr.chat.accounting :as acct]
            [dvergr.runtime.ctx :as runtime-ctx]
            [dvergr.sandbox :as sandbox]
            [org.replikativ.spindel.engine.component :as component]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.engine.context :as execution-context]))

(deftest ambient-selection-only-refines-into-descendants
  (let [parent (execution-context/create-execution-context)
        owner (execution-context/fork-context parent :mode :frozen)
        sibling (execution-context/fork-context parent :mode :frozen)
        descendant (execution-context/fork-context owner :mode :frozen)]
    (try
      (is (identical? owner (runtime-ctx/selected-context owner)))
      (binding [ec/*execution-context* parent]
        (is (identical? owner (runtime-ctx/selected-context owner))))
      (binding [ec/*execution-context* sibling]
        (is (identical? owner (runtime-ctx/selected-context owner))))
      (binding [ec/*execution-context* descendant]
        (is (identical? descendant (runtime-ctx/selected-context owner))))
      (finally
        (execution-context/close-context! descendant)
        (execution-context/close-context! sibling)
        (execution-context/close-context! owner)
        (execution-context/close-context! parent)))))

(deftest sci-interpreter-is-selected-by-world
  (testing "one stable ChatContext ref resolves independent SCI heaps after fork"
    (let [parent (execution-context/create-execution-context)
          chat   (ctx/create-chat-context
                  {:title "forkable repl"
                   :execution-context parent})]
      (try
        (let [ref (:sci-component chat)
              parent-sci (ctx/sci-context-in chat parent)
              eval-in (fn [world interpreter source]
                        (sandbox/eval-code interpreter source
                                           :execution-context world))]
          (is (some? ref))
          ;; Exercise the interpreter agents actually receive, not only the
          ;; bare Spindel macro context. Every injected capability must either
          ;; be world-relative or declare its ambient sharing policy.
          (sandbox/setup-agent-namespaces! parent-sci parent)
          (is (= {:value [:parent] :stdout "" :stderr "" :success true}
                 (eval-in
                  parent parent-sci
                  "(def observations (atom [:parent])) @observations")))
          (let [child (execution-context/fork-context parent :mode :frozen)]
            (try
              (let [child-sci (ctx/sci-context-in chat child)]
                (is (not (identical? parent-sci child-sci)))
                (is (= [:parent :child]
                       (:value (eval-in
                                child child-sci
                                "(swap! observations conj :child)"))))
                (is (= [:parent]
                       (:value (eval-in parent parent-sci "@observations"))))
                (is (= [:parent :child]
                       (:value (eval-in child child-sci "@observations"))))
                (let [result
                      (eval-in
                       child child-sci
                       "(ns child-only-test
                          (:require [clojure.test :refer [deftest is run-tests]]))
                        (deftest child-proof (is (= 3 (+ 1 2))))
                        (run-tests 'child-only-test)")]
                  (is (:success result))
                  (is (= {:test 1 :pass 1 :fail 0 :error 0}
                         (select-keys (:value result)
                                      [:test :pass :fail :error])))
                  (is (false?
                       (:value
                        (eval-in
                         parent parent-sci
                         "(boolean (find-ns 'child-only-test))"))))))
              (ctx/release-sci-in! chat child)
              (is (thrown-with-msg?
                   clojure.lang.ExceptionInfo
                   #"not available"
                   (ctx/sci-context-in chat child)))
              (is (contains?
                   (binding [ec/*execution-context* parent]
                     (component/registered))
                   (:id ref))
                  "releasing a child interpreter preserves its parent")
              (finally
                (execution-context/close-context! child)))))
        (finally
          (ctx/close-chat! chat)
          (execution-context/close-context! parent))))))

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

(deftest add-system-note-test
  (testing "add-system-note! is the single seam for out-of-band notes: an
            :system message, optionally protected from pruning"
    (let [chat (ctx/create-chat-context {:budget-dollars 1.0 :with-sci? false})]
      (ctx/add-system-note! chat "plain")
      (ctx/add-system-note! chat "urgent" :important? true)
      (let [[m1 m2] (ctx/get-messages chat)]
        (is (= :system (:message/role m1)))
        (is (= "plain" (:message/content m1)))
        (is (not (:message/important? m1)))
        (is (= :system (:message/role m2)))
        (is (= "urgent" (:message/content m2)))
        (is (true? (:message/important? m2)))))))
