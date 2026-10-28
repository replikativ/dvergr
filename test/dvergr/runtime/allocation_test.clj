(ns dvergr.runtime.allocation-test
  (:require [clojure.test :refer [deftest is]]
            [dvergr.runtime.allocation :as allocation]))

(def limit (* 16 1024 1024))
(defn- checkpoints! [policy] (dotimes [_ 10000] (policy)))
(defn- retain-churn! [holder] (reset! holder (byte-array (* 32 1024 1024))))
(defn- failure [f] (try (f) nil (catch clojure.lang.ExceptionInfo error (ex-data error))))

(deftest fresh-scopes-exclude-earlier-work-and-enforce-their-own-allocation
  (let [policy (allocation/interrupt-policy limit) holder (atom nil)]
    (is (nil? (allocation/call-with-scope #(checkpoints! policy))))
    (retain-churn! holder)
    (is (nil? (allocation/call-with-scope #(checkpoints! policy))))
    (let [error (failure #(allocation/call-with-scope
                           (fn [] (retain-churn! holder) (checkpoints! policy))))]
      (is (= :evaluation (:allocation-scope error)))
      (is (= limit (:limit-bytes error)))
      (is (> (:allocated-bytes error) limit)))))

(deftest outer-scope-includes-nested-work-without-reusing-its-counter
  (let [policy (allocation/interrupt-policy limit) holder (atom nil)]
    (let [error (failure #(allocation/call-with-scope
                           (fn []
                             (allocation/call-with-scope (fn [] (retain-churn! holder)))
                             (checkpoints! policy))))]
      (is (= :evaluation (:allocation-scope error)))
      (is (> (:allocated-bytes error) limit)))))

(deftest bound-callbacks-never-capture-the-creators-observation
  (let [policy (allocation/interrupt-policy limit) holder (atom nil)
        callback (allocation/call-with-scope #(bound-fn [] (checkpoints! policy)))]
    (retain-churn! holder)
    (is (nil? (allocation/call-with-scope callback)))
    (is (nil? (callback)) "unscoped callbacks use their actual thread's fallback")))

(deftest workers-account-only-their-own-actual-evaluation
  (let [policy (allocation/interrupt-policy limit) holder (atom nil)]
    (allocation/call-with-scope
     (fn []
       (retain-churn! holder)
       (let [clean (future (allocation/call-with-scope #(checkpoints! policy)))
             allocating (future
                          (failure #(allocation/call-with-scope
                                     (fn [] (retain-churn! holder) (checkpoints! policy)))))]
         (is (nil? (deref clean 10000 ::timeout)))
         (let [error (deref allocating 10000 ::timeout)]
           (is (= :evaluation (:allocation-scope error)))
           (is (> (:allocated-bytes error) limit))))))))

(deftest throwing-nested-scopes-restore-the-outer-observation
  (let [policy (allocation/interrupt-policy limit) holder (atom nil)
        error (failure #(allocation/call-with-scope
                         (fn []
                           (try
                             (allocation/call-with-scope
                              (fn [] (retain-churn! holder) (throw (ex-info "inner" {}))))
                             (catch clojure.lang.ExceptionInfo _))
                           (checkpoints! policy))))]
    (is (= :evaluation (:allocation-scope error)))
    (is (> (:allocated-bytes error) limit))
    (is (nil? (allocation/call-with-scope #(checkpoints! policy))))))

(deftest unscoped-embedding-retains-a-conservative-thread-backstop
  (let [policy (allocation/interrupt-policy limit) holder (atom nil)]
    (policy)
    (retain-churn! holder)
    (let [error (failure #(checkpoints! policy))]
      (is (= :implicit-thread (:allocation-scope error)))
      (is (> (:allocated-bytes error) limit)))
    (is (= :implicit-thread (:allocation-scope (failure #(checkpoints! policy))))
        "an exceeded fallback does not silently reset")
    (is (nil? (allocation/call-with-scope #(checkpoints! policy))))))

(deftest cancellation-is-checked-before-the-next-sampled-allocation
  (let [policy (allocation/interrupt-policy limit)]
    (try
      (.interrupt (Thread/currentThread))
      (is (= :thread-interrupt (:cause (failure policy))))
      (finally (Thread/interrupted)))))
