(ns dvergr.discourse.generation-test
  (:require [clojure.test :refer [deftest is testing]]
            [org.replikativ.spindel.core :as sp]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.engine.context :as ctx]
            [org.replikativ.spindel.spin.sync :as sync]
            [dvergr.discourse.generation :as gen]))

(defn- await-ctx
  "Block-deref a deferred under the given ctx binding."
  [ctx d]
  (binding [ec/*execution-context* ctx]
    @(sp/spin (sp/await d))))

(deftest sync-handle-completes
  (let [c (ctx/create-execution-context)
        h (gen/sync-handle c (fn [] (* 6 7)))]
    (is (= 42 (await-ctx c (:done h))))))

(deftest sync-handle-catches-throws
  (let [c (ctx/create-execution-context)
        h (gen/sync-handle c (fn [] (throw (RuntimeException. "kaboom"))))
        v (await-ctx c (:done h))]
    (is (gen/error-result? v))
    (is (= "kaboom" (::gen/error v)))))

(deftest future-handle-runs-on-other-thread
  (let [c (ctx/create-execution-context)
        captured-thread (atom nil)
        h (gen/future-handle c
                             (fn []
                               (reset! captured-thread (.getName (Thread/currentThread)))
                               :done))]
    (is (= :done (await-ctx c (:done h))))
    ;; Future ran on a thread distinct from the awaiter.
    (is (not= (.getName (Thread/currentThread)) @captured-thread))))

(deftest race-handles-picks-first
  (let [c (ctx/create-execution-context)
        ;; sync wins because it delivers immediately; future sleeps 200ms.
        h (gen/race-handles c
                            (gen/sync-handle c (fn [] :fast))
                            (gen/future-handle c (fn [] (Thread/sleep 200) :slow)))]
    (is (= :fast (await-ctx c (:done h))))))

(deftest fallback-handle-recovers-on-error
  (let [c (ctx/create-execution-context)
        h (gen/fallback-handle
           c
           (gen/sync-handle c (fn [] (throw (RuntimeException. "primary-fail"))))
           (fn [_err] (gen/sync-handle c (fn [] :secondary-ok))))]
    (is (= :secondary-ok (await-ctx c (:done h))))))

(deftest fallback-handle-skips-on-success
  (let [c (ctx/create-execution-context)
        called-secondary? (atom false)
        h (gen/fallback-handle
           c
           (gen/sync-handle c (fn [] :primary-ok))
           (fn [_err]
             (reset! called-secondary? true)
             (gen/sync-handle c (fn [] :secondary))))]
    (is (= :primary-ok (await-ctx c (:done h))))
    (is (not @called-secondary?))))

(deftest external-handle-delivers-via-caller
  (let [c (ctx/create-execution-context)
        d (binding [ec/*execution-context* c]
            (sync/create-deferred c))
        h (gen/external-handle d {})]
    ;; Caller (test) delivers manually.
    (future
      (Thread/sleep 30)
      (binding [ec/*execution-context* c]
        (sync/deliver! d :externally-decided)))
    (is (= :externally-decided (await-ctx c (:done h))))))
