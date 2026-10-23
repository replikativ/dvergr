(ns dvergr.runtime.host-cleanup-test
  (:require [clojure.test :refer [deftest is]]
            [dvergr.discourse :as d]
            [dvergr.runtime.preparation :as preparation])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(defn- refusal [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo error (:type (ex-data error)))))

(defn- await-release! [^CountDownLatch latch interrupted]
  ;; Finite test-only non-cooperative work. The test also opens every latch in
  ;; finally, so failure cannot leave a live native test worker behind.
  (let [deadline (+ (System/nanoTime) 30000000000)]
    (loop []
      (let [remaining (- deadline (System/nanoTime))]
        (when-not (pos? remaining) (throw (ex-info "Test latch expired" {})))
        (let [outcome (try (if (.await latch remaining TimeUnit/NANOSECONDS) :released :expired)
                           (catch InterruptedException _ (deliver interrupted true) :interrupted))]
          (case outcome :released true :interrupted (recur)
                (throw (ex-info "Test latch expired" {}))))))))

(deftest exclusive-host-ownership-survives-transfer-and-cleanup-retry
  (let [room (d/make-room {:id :exclusive-host-cleanup})
        key [:human-sci (random-uuid)] calls (atom {}) fail? (atom true) recovery (atom nil)]
    (try
      (doseq [options [{:exclusive-key nil} {:exclusive-key key :unknown true}]]
        (is (= ::preparation/invalid-options
               (refusal #(preparation/with-host-operation! room options (fn [_] :wrong))))))
      (preparation/with-host-operation!
        room {:exclusive-key key}
        (fn [lease]
          (is (= ::preparation/capacity-exhausted
                 (refusal #(preparation/with-host-operation! room {:exclusive-key key} (fn [_] :wrong)))))
          (is (= :other (preparation/with-host-operation! room {:exclusive-key :other} (fn [_] :other))))
          (reset! recovery
                  (preparation/retain-host-cleanup!
                   lease (array-map :good :good-resource :bad :bad-resource)
                   (fn [resource]
                     (swap! calls update resource (fnil inc 0))
                     (when (and (= :bad-resource resource) (compare-and-set! fail? true false))
                       (throw (ex-info "Controlled cleanup failure" {}))))))
          (is (= (:id lease) (:id @recovery)))
          (is (= ::preparation/self-drain (refusal #((:join! @recovery) 0))))
          (is (= ::preparation/self-drain (refusal #(preparation/assert-not-host-owner! room))))))
      (is (identical? @recovery (get-in @(:meta room) [preparation/leases-key (:id @recovery)])))
      (is (false? ((:finished? @recovery))))
      (is (some? (refusal #(preparation/drain-room! room))))
      (is (= {:good-resource 1 :bad-resource 1} @calls))
      (is (= ::preparation/capacity-exhausted
             (refusal #(preparation/with-host-operation! room {:exclusive-key key} (fn [_] :wrong)))))
      (preparation/drain-room! room)
      (is (= {:good-resource 1 :bad-resource 2} @calls))
      (is (= :accepted (preparation/with-host-operation! room {:exclusive-key key} (fn [_] :accepted))))
      (finally (reset! fail? false) (d/close-room! room)))))

(deftest transfer-reuses-the-slot-at-full-capacity-and-allows-cancelled-recovery
  (let [room (d/make-room {:id :full-host-cleanup}) key [:human-sci (random-uuid)]
        releases (atom 0)]
    (try
      (dotimes [i 63] (preparation/retain-room-cleanup! room {i (Object.)} (fn [_] (swap! releases inc))))
      (preparation/with-host-operation!
        room {:exclusive-key key}
        (fn [lease]
          (preparation/cancel! lease)
          (Thread/interrupted)
          (swap! (:meta room) assoc :dvergr/fork-transfer-state :test-fenced)
          (let [cleanup (preparation/retain-host-cleanup! lease {:last (Object.)} (fn [_] (swap! releases inc)))]
            (is (= :cancelled @(:state cleanup)))
            (is (= key (:exclusive-key cleanup)))
            (is (= 64 (count (get @(:meta room) preparation/leases-key))))
            (is (not (contains? (d/metadata-update! room assoc :test-marker :retained)
                                preparation/leases-key)))
            (is (false? ((:finished? cleanup)))))))
      (swap! (:meta room) dissoc :dvergr/fork-transfer-state)
      (preparation/drain-room! room)
      (is (= 64 @releases))
      (is (empty? (get @(:meta room) preparation/leases-key)))
      (finally
        (Thread/interrupted)
        (swap! (:meta room) dissoc :dvergr/fork-transfer-state)
        (d/close-room! room)))))

(deftest transferred-cleanup-waits-for-physical-exit-and-cannot-interrupt-reused-thread
  (let [room (d/make-room {:id :physical-host-cleanup})
        handoff (promise) interrupted (promise) original-exited (promise) result (promise)
        release-original (CountDownLatch. 1) release-reused (CountDownLatch. 1)
        releases (atom 0)
        worker (Thread.
                ^Runnable
                (fn []
                  (try
                    (preparation/with-host-operation!
                      room {:exclusive-key [:human-sci :test]}
                      (fn [lease]
                        (deliver handoff (preparation/retain-host-cleanup!
                                          lease {:heap (Object.)} (fn [_] (swap! releases inc))))
                        (await-release! release-original interrupted)))
                    ;; Same native thread now represents a later executor job.
                    (Thread/interrupted)
                    (deliver original-exited true)
                    (deliver result (try (let [released? (.await release-reused 30000 TimeUnit/MILLISECONDS)]
                                           {:released? released? :interrupted? (Thread/interrupted)})
                                         (catch InterruptedException _ {:interrupted? true})))
                    (catch Throwable error (deliver result {:error error})))))]
    (.start worker)
    (try
      (let [cleanup (deref handoff 30000 nil)]
        (is (map? cleanup))
        (when cleanup
          (is (= ::preparation/unconfirmed-drain (refusal #((:join! cleanup) 0))))
          (is (zero? @releases))
          (is (false? ((:finished? cleanup))))
          (preparation/cancel! cleanup)
          (is (true? (deref interrupted 30000 nil)))
          (.countDown release-original)
          (is (true? (deref original-exited 30000 nil)))
          (is (identical? cleanup (get-in @(:meta room) [preparation/leases-key (:id cleanup)])))
          ((:join! cleanup) 30000)
          (is (= 1 @releases))
          (is (true? ((:finished? cleanup))))
          (is (.isAlive worker))
          (preparation/cancel! cleanup)
          (.countDown release-reused)
          (is (= {:released? true :interrupted? false} (deref result 30000 nil)))))
      (finally
        (.countDown release-original) (.countDown release-reused)
        (.join worker 31000)
        (is (not (.isAlive worker)))
        (d/close-room! room)))))

(deftest stale-and-foreign-thread-transfers-cannot-replace-an-owner
  (let [room (d/make-room {:id :host-cleanup-identity}) previous (atom nil)]
    (try
      (preparation/with-host-operation!
        room
        (fn [lease]
          (reset! previous lease)
          (is (= ::preparation/stale-host-operation
                 (deref (future (refusal #(preparation/retain-host-cleanup! lease {:x :resource} (fn [_])))) 30000 nil)))
          (is (identical? lease (get-in @(:meta room) [preparation/leases-key (:id lease)])))))
      (is (= ::preparation/stale-host-operation
             (refusal #(preparation/retain-host-cleanup! @previous {:x :resource} (fn [_])))))
      (is (empty? (get @(:meta room) preparation/leases-key)))
      (finally (d/close-room! room)))))
