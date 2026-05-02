(ns dvergr.scheduler.core-test
  "Tests for the scheduler system."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [dvergr.scheduler.core :as scheduler]
            [dvergr.registry :as registry]
            [dvergr.tools :as tools]))

;; ============================================================================
;; Fixtures
;; ============================================================================

(use-fixtures :each
  (fn [f]
    (let [orig-schedules @scheduler/active-schedules
          orig-registry @registry/registry]
      (try
        (f)
        (finally
          ;; Cancel any schedules created during test
          (scheduler/cancel-all!)
          (reset! scheduler/active-schedules orig-schedules)
          (reset! registry/registry orig-registry))))))

;; ============================================================================
;; Tests
;; ============================================================================

(deftest test-create-schedule
  (testing "Creating a schedule returns a UUID and tracks it"
    (let [id (scheduler/create-schedule! nil
               {:agent-id :test-agent
                :task "Do something"
                :interval-ms 60000
                :description "Test schedule"})]
      (is (uuid? id))
      (is (= 1 (count (scheduler/list-schedules))))
      (let [s (first (scheduler/list-schedules))]
        (is (= :test-agent (:agent-id s)))
        (is (= "Do something" (:task s)))
        (is (= 60000 (:interval-ms s)))
        (is (= "Test schedule" (:description s)))))))

(deftest test-cancel-schedule
  (testing "Cancelling a schedule removes it from active list"
    (let [id (scheduler/create-schedule! nil
               {:agent-id :test-agent
                :task "Recurring task"
                :interval-ms 60000})]
      (is (= 1 (count (scheduler/list-schedules))))
      (is (= :cancelled (scheduler/cancel-schedule! id)))
      (is (= 0 (count (scheduler/list-schedules)))))))

(deftest test-cancel-nonexistent
  (testing "Cancelling a nonexistent schedule returns nil"
    (is (nil? (scheduler/cancel-schedule! (random-uuid))))))

(deftest test-cancel-all
  (testing "cancel-all! removes all active schedules"
    (scheduler/create-schedule! nil {:agent-id :a :task "task1" :interval-ms 60000})
    (scheduler/create-schedule! nil {:agent-id :b :task "task2" :interval-ms 120000})
    (is (= 2 (count (scheduler/list-schedules))))
    (is (= 2 (scheduler/cancel-all!)))
    (is (= 0 (count (scheduler/list-schedules))))))

(deftest test-list-schedules
  (testing "list-schedules returns config maps with IDs"
    (let [id1 (scheduler/create-schedule! nil {:agent-id :a :task "t1" :interval-ms 60000})
          id2 (scheduler/create-schedule! nil {:agent-id :b :task "t2" :interval-ms 120000})
          schedules (scheduler/list-schedules)]
      (is (= 2 (count schedules)))
      (is (= #{id1 id2} (set (map :id schedules)))))))

(deftest test-create-schedule-validation
  (testing "Invalid interval throws"
    (is (thrown? AssertionError
          (scheduler/create-schedule! nil
            {:agent-id :test
             :task "bad"
             :interval-ms -1}))))

  (testing "Missing agent-id throws"
    (is (thrown? AssertionError
          (scheduler/create-schedule! nil
            {:agent-id "not-a-keyword"
             :task "bad"
             :interval-ms 60000}))))

  (testing "Missing task throws"
    (is (thrown? AssertionError
          (scheduler/create-schedule! nil
            {:agent-id :test
             :task nil
             :interval-ms 60000})))))

(deftest test-schedule-tools-registered
  (testing "Schedule tools are registered in tool registry"
    ;; Tools should be registered when the namespace is loaded
    (require 'dvergr.scheduler.tools)
    (is (some? (tools/get-tool "schedule_create")))
    (is (some? (tools/get-tool "schedule_list")))
    (is (some? (tools/get-tool "schedule_cancel")))))
