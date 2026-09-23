(ns dvergr.jobs-test
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.jobs :as jobs]))

(deftest a-job-completes-and-status-waits-for-it
  (let [job (jobs/start! {:op "test" :room "r"} #(do (Thread/sleep 200) {:answer 42}))]
    (is (= :running (:status job)))
    (is (= :running (:status (jobs/status (:id job)))) "no wait: answers at once")
    (let [done (jobs/status (:id job) 5000)]
      (is (= :completed (:status done)))
      (is (= {:answer 42} (:result done)))
      (is (<= (:started-at done) (:finished-at done))))
    (is (some #(= (:id job) (:id %)) (jobs/jobs "r")))
    (is (not-any? #(= (:id job) (:id %)) (jobs/jobs "other")))))

(deftest a-failing-job-reports-its-error
  (let [job (jobs/start! {:op "test"} #(throw (ex-info "no budget left" {})))
        done (jobs/status (:id job) 5000)]
    (is (= :failed (:status done)))
    (is (= "no budget left" (:error done)))))

(deftest cancel-runs-the-cancel-fn-once-and-sticks
  (let [cancels (atom 0) release (promise)
        job (jobs/start! {:op "test"} #(do (deref release 5000 nil) :late)
                         :cancel #(swap! cancels inc))]
    (is (= :cancelled (:status (jobs/cancel! (:id job)))))
    (is (= :cancelled (:status (jobs/cancel! (:id job)))) "cancelling again changes nothing")
    (is (= 1 @cancels))
    (deliver release true)
    (Thread/sleep 100)
    (is (= :cancelled (:status (jobs/status (:id job)))))
    (is (nil? (jobs/status "no-such-job")))
    (is (nil? (jobs/cancel! "no-such-job")))))

(deftest waiting-is-capped
  (let [job (jobs/start! {:op "test"} #(do (Thread/sleep 60000) :never))
        t0 (System/currentTimeMillis)]
    (with-redefs [jobs/max-wait-ms 300]
      (is (= :running (:status (jobs/status (:id job) 100000)))))
    (is (< (- (System/currentTimeMillis) t0) 5000))
    (jobs/cancel! (:id job))))
