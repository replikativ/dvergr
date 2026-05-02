(ns dvergr.tools.approval-test
  "Tests for approval workflow tools."
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.tools.approval :as approval]))

(deftest request-dependency-test
  (testing "Request dependency creates pending approval"
    (let [result (approval/request-dependency
                   {:lib "buddy/buddy-core"
                    :version "1.11.423"
                    :justification "Need cryptographic functions for auth"
                    :alternatives-considered "clojure.crypto but lacks features"})]
      (is (= :pending (:status result)))
      (is (some? (:request-id result)))
      (is (string? (:message result)))))

  (testing "Request without justification still works"
    (let [result (approval/request-dependency
                   {:lib "test/test"
                    :version "1.0.0"
                    :justification "Testing"})]
      (is (= :pending (:status result))))))

(deftest approval-status-test
  (testing "Check status of pending request"
    (let [req-result (approval/request-dependency
                       {:lib "test/lib"
                        :version "1.0.0"
                        :justification "Test"})
          req-id (:request-id req-result)
          status (approval/get-approval-status req-id)]
      (is (= :pending (:status status)))
      (is (= :dependency (:type status))))))

(deftest list-pending-approvals-test
  (testing "List all pending approvals"
    ;; Clear any existing approvals first
    (reset! approval/pending-approvals {})

    ;; Create some requests
    (approval/request-dependency
      {:lib "lib1/lib1" :version "1.0.0" :justification "Test"})
    (approval/request-dependency
      {:lib "lib2/lib2" :version "2.0.0" :justification "Test"})

    (let [pending (approval/list-pending-approvals)]
      (is (= 2 (count pending)))
      (is (every? #(= :pending (:status %)) pending)))))

(deftest reject-dependency-test
  (testing "Reject a dependency request"
    (let [req-result (approval/request-dependency
                       {:lib "bad/lib"
                        :version "1.0.0"
                        :justification "Test"})
          req-id (:request-id req-result)
          reject-result (approval/reject-dependency! req-id "Security concerns")]
      (is (:success reject-result))
      (let [status (approval/get-approval-status req-id)]
        (is (= :rejected (:status status)))
        (is (= "Security concerns" (get-in status [:response :reason])))))))

(deftest approve-dependency-test
  (testing "Approve and load a valid dependency"
    ;; Use a small, stable library
    (let [req-result (approval/request-dependency
                       {:lib "org.clojure/data.json"
                        :version "2.5.0"
                        :justification "JSON parsing"})
          req-id (:request-id req-result)
          approve-result (approval/approve-dependency! req-id)]
      ;; May fail if library can't be loaded, but should return structured response
      (is (contains? approve-result :success))
      (if (:success approve-result)
        (do
          (is (string? (:message approve-result)))
          (let [status (approval/get-approval-status req-id)]
            (is (= :approved (:status status)))))
        ;; If approval failed, should have error message
        (is (string? (:error approve-result)))))))

(deftest request-plan-review-test
  (testing "Request plan review creates pending approval"
    (let [result (approval/request-plan-review
                   {:approach "Add authentication using buddy"
                    :files-to-modify ["src/auth.clj" "src/routes.clj"]
                    :dependencies-needed ["buddy/buddy-auth"]
                    :risks "Session storage complexity"
                    :alternatives "Roll our own auth (not recommended)"})]
      (is (= :pending (:status result)))
      (is (some? (:request-id result)))
      (is (string? (:message result)))))

  (testing "Plan review with minimal fields"
    (let [result (approval/request-plan-review
                   {:approach "Simple change"
                    :files-to-modify ["src/core.clj"]})]
      (is (= :pending (:status result))))))

(deftest approve-plan-test
  (testing "Approve a plan"
    (let [req-result (approval/request-plan-review
                       {:approach "Test approach"
                        :files-to-modify ["test.clj"]})
          req-id (:request-id req-result)
          approve-result (approval/approve-plan! req-id :feedback "Looks good!")]
      (is (:success approve-result))
      (is (= "Looks good!" (:feedback approve-result)))
      (let [status (approval/get-approval-status req-id)]
        (is (= :approved (:status status)))))))

(deftest reject-plan-test
  (testing "Reject a plan with feedback"
    (let [req-result (approval/request-plan-review
                       {:approach "Bad approach"
                        :files-to-modify ["test.clj"]})
          req-id (:request-id req-result)
          reject-result (approval/reject-plan! req-id "Need to reconsider architecture")]
      (is (:success reject-result))
      (is (= "Need to reconsider architecture" (:feedback reject-result)))
      (let [status (approval/get-approval-status req-id)]
        (is (= :rejected (:status status)))))))

(deftest clear-completed-approvals-test
  (testing "Clear completed approvals keeps pending"
    (reset! approval/pending-approvals {})

    (let [req1 (approval/request-dependency
                 {:lib "lib1/lib1" :version "1.0.0" :justification "Test"})
          req2 (approval/request-dependency
                 {:lib "lib2/lib2" :version "1.0.0" :justification "Test"})
          req1-id (:request-id req1)
          req2-id (:request-id req2)]

      ;; Approve one, leave one pending
      (approval/approve-dependency! req1-id)

      ;; Clear completed
      (approval/clear-completed-approvals!)

      ;; Should only have pending request
      (let [pending (approval/list-pending-approvals)]
        (is (= 1 (count pending)))
        (is (= req2-id (:request-id (first pending))))))))
