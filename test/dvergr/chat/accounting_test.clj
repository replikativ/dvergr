(ns dvergr.chat.accounting-test
  "Tests for critical budget accounting features."
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.chat.accounting :as acct]))

(deftest calculate-cost-test
  (testing "Token cost calculation with registered model"
    ;; Claude Sonnet 4.5: $3/MTok input = 3 microdollars per token
    (is (= 3000 (acct/calculate-cost :input-tokens 1000
                                     {:model "claude-sonnet-4-5"})))
    (is (= 15000 (acct/calculate-cost :output-tokens 1000
                                      {:model "claude-sonnet-4-5"}))))

  (testing "Fallback for unknown model"
    ;; Should use fallback pricing
    (let [input-cost (acct/calculate-cost :input-tokens 1000 {:model "unknown"})
          output-cost (acct/calculate-cost :output-tokens 1000 {:model "unknown"})]
      (is (pos? input-cost) "Should have non-zero cost")
      (is (pos? output-cost) "Should have non-zero cost"))))

(deftest budget-status-test
  (testing "Budget status provides usage percentages"
    (let [status (acct/budget-status 10000 5000)]
      (is (= 0.5 (:pct-used status)))
      (is (= 5000 (:remaining status))))))

(deftest check-thresholds-test
  (testing "Threshold crossing returns highest crossed threshold"
    ;; Cross 50%
    (let [result (acct/check-thresholds 0.51 #{})]
      (is (some? result))
      (is (= 0.50 (:pct result)))
      (is (= :info (:level result))))

    ;; Cross 75%
    (let [result (acct/check-thresholds 0.76 #{0.50})]
      (is (= 0.75 (:pct result)))
      (is (= :notice (:level result))))

    ;; Already crossed - returns nil
    (is (nil? (acct/check-thresholds 0.76 #{0.50 0.75})))))

(deftest threshold-progression-test
  (testing "Thresholds trigger in order as budget is consumed"
    ;; Starting from 0%
    (let [t1 (acct/check-thresholds 0.49 #{})]
      (is (nil? t1) "Below 50%"))

    ;; Cross 50%
    (let [t2 (acct/check-thresholds 0.51 #{})]
      (is (= :info (:level t2))))

    ;; Cross 75%
    (let [t3 (acct/check-thresholds 0.76 #{0.50})]
      (is (= :notice (:level t3))))

    ;; Cross 90%
    (let [t4 (acct/check-thresholds 0.91 #{0.50 0.75})]
      (is (= :warning (:level t4))))

    ;; Cross 95%
    (let [t5 (acct/check-thresholds 0.96 #{0.50 0.75 0.90})]
      (is (= :critical (:level t5))))))
