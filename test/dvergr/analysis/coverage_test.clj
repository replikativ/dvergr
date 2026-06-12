(ns dvergr.analysis.coverage-test
  "Minimal smoke tests for coverage analyzer."
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.analysis.coverage :as cov]))

(deftest find-clojure-files-test
  (testing "Can find Clojure files in src directory"
    (let [files (cov/find-clojure-files "src")]
      (is (vector? files))
      (is (pos? (count files)))
      (is (every? #(.endsWith (.getName %) ".clj") files)))))

(deftest coverage-analysis-smoke-test
  (testing "Coverage analysis runs without errors"
    (let [analysis (cov/analyze-coverage "src" "test")]
      (is (map? analysis))
      (is (contains? analysis :files))
      (is (contains? analysis :summary))
      (is (vector? (:files analysis)))
      (is (map? (:summary analysis))))))

(deftest coverage-stats-test
  (testing "Coverage stats returns expected format"
    (let [stats (cov/coverage-stats "src" "test")]
      (is (map? stats))
      (is (contains? stats :files))
      (is (contains? stats :with-tests))
      (is (contains? stats :percentage))
      (is (number? (:files stats)))
      (is (number? (:with-tests stats)))
      (is (number? (:percentage stats)))
      (is (>= (:percentage stats) 0.0))
      (is (<= (:percentage stats) 100.0)))))

(deftest find-untested-functions-test
  (testing "Can find untested functions"
    (let [untested (cov/find-untested-functions "src" "test")]
      (is (vector? untested))
      ;; Each entry should have :namespace, :function, :file
      (when (seq untested)
        (let [first-entry (first untested)]
          (is (contains? first-entry :namespace))
          (is (contains? first-entry :function))
          (is (contains? first-entry :file)))))))

(deftest has-tests-check-test
  (testing "has-tests? correctly identifies test presence"
    ;; Coverage namespace should have tests (coverage_test.clj)
    (is (true? (cov/has-tests? 'dvergr.analysis.coverage "test")))

    ;; Non-existent namespace should not have tests
    (is (false? (cov/has-tests? 'dvergr.nonexistent.namespace "test")))))

(deftest suggest-test-cases-test
  (testing "Test case suggestions return valid code structure"
    (let [suggestion (cov/suggest-test-cases 'dvergr.analysis.coverage "src" "test")]
      (is (string? suggestion))
      ;; Should contain ns form
      (is (.contains suggestion "(ns ")))))
