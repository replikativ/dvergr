(ns dvergr.agent.primitives-test
  "Tests for agent communication primitives."
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.agent.task :as prim]
            [dvergr.agent.config :as agent]
            [org.replikativ.spindel.engine.core :as rtc]
            [org.replikativ.spindel.engine.context :as ctx]))

;; ============================================================================
;; Result Extraction Tests
;; ============================================================================

(deftest extract-result-test
  (testing "Extract result from completed agent"
    (let [result {:status :complete
                  :messages [{:message/role :user :message/content "Task"}
                             {:message/role :assistant :message/text "I did it"}
                             {:message/role :assistant :message/text "Final result"}]}]
      (is (= "Final result" (prim/extract-result result)))))

  (testing "Extract from messages with tool uses (no text)"
    (let [result {:status :complete
                  :messages [{:message/role :assistant
                              :message/text ""
                              :message/tool-uses [{:name "foo"}]}
                             {:message/role :assistant
                              :message/content "Actual result"}]}]
      (is (= "Actual result" (prim/extract-result result)))))

  (testing "Handle empty result"
    (let [result {:status :complete :messages []}]
      (is (= "" (prim/extract-result result))))))

(deftest successful?-test
  (testing "Complete status is successful"
    (is (prim/successful? {:status :complete})))

  (testing "Other statuses are not successful"
    (is (not (prim/successful? {:status :budget-exceeded})))
    (is (not (prim/successful? {:status :cancelled})))
    (is (not (prim/successful? {:status :error})))))

(deftest extract-all-text-test
  (testing "Extract all assistant text responses"
    (let [result {:messages [{:message/role :user :message/content "Q"}
                             {:message/role :assistant :message/text "A1"}
                             {:message/role :assistant :message/text "A2"}
                             {:message/role :assistant :message/content "A3"}]}]
      (is (= ["A1" "A2" "A3"] (prim/extract-all-text result))))))

(deftest extract-tool-uses-test
  (testing "Extract tool uses from messages"
    (let [result {:messages [{:message/role :assistant
                              :message/tool-uses [{:tool-use/name "read_file"
                                                   :tool-use/input {:path "test.clj"}}
                                                  {:tool-use/name "write_file"
                                                   :tool-use/input {:path "out.clj"}}]}
                             {:message/role :assistant
                              :message/tool-uses [{:tool-use/name "shell"
                                                   :tool-use/input {:command "test"}}]}]}
          tools (prim/extract-tool-uses result)]
      (is (= 3 (count tools)))
      (is (some #(= "read_file" (:tool-use/name %)) tools))
      (is (some #(= "write_file" (:tool-use/name %)) tools))
      (is (some #(= "shell" (:tool-use/name %)) tools)))))

;; ============================================================================
;; Agent Creation and Execution Tests (Integration-style)
;; ============================================================================

(deftest ask-basic-test
  (testing "ask! returns a spin"
    (let [runtime (ctx/create-execution-context)
          test-agent (agent/make-agent {:name "test"})]

      (binding [rtc/*execution-context* runtime]
        (let [result-spin (prim/ask! test-agent "test task" {:budget-dollars 0.01})]
          ;; Should return a Spin object (not nil)
          (is (some? result-spin))
          (is (= "org.replikativ.spindel.spin.core.Spin"
                 (.getName (class result-spin))))))))

  (testing "spawn! returns a spin immediately"
    (let [runtime (ctx/create-execution-context)
          test-agent (agent/make-agent {:name "test"})]

      (binding [rtc/*execution-context* runtime]
        (let [result-spin (prim/spawn! test-agent "test task" {:budget-dollars 0.01})]
          ;; Should return a Spin object
          (is (some? result-spin))
          (is (= "org.replikativ.spindel.spin.core.Spin"
                 (.getName (class result-spin))))))))

  (testing "tell! returns nil (fire-and-forget)"
    (let [runtime (ctx/create-execution-context)
          test-agent (agent/make-agent {:name "test"})]

      (binding [rtc/*execution-context* runtime]
        (let [result (prim/tell! test-agent "test task" {:budget-dollars 0.01})]
          ;; Should return nil
          (is (nil? result)))))))

;; ============================================================================
;; Merge/Discard Tests (Unit - just verify they don't crash)
;; ============================================================================

(deftest merge-discard-test
  (testing "merge! accepts result and returns it"
    (let [result {:status :complete :child-ctx nil}]
      (is (= result (prim/merge! result)))))

  (testing "discard! accepts result and returns it"
    (let [result {:status :complete :child-ctx nil}]
      (is (= result (prim/discard! result)))))

  (testing "merge! and discard! handle nil child-ctx"
    (let [result {:status :complete :child-ctx nil}]
      (is (= result (prim/merge! result)))
      (is (= result (prim/discard! result))))))

;; ============================================================================
;; Combinator Re-exports Tests (Verify they exist)
;; ============================================================================

(deftest combinators-test
  (testing "Combinator functions are available"
    (is (fn? prim/parallel))
    (is (fn? prim/race))
    (is (fn? prim/timeout))
    (is (fn? prim/sleep))))
