(ns dvergr.runtime.host-operation-test
  (:require [clojure.test :refer [deftest is]]
            [dvergr.discourse :as discourse]
            [dvergr.room.registry :as registry]
            [dvergr.runtime.preparation :as preparation]
            [org.replikativ.spindel.core :as sp]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.engine.impl.simple :as simple])
  (:import [java.util.concurrent CountDownLatch Executors Callable TimeUnit]))

(defn- failure-type [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo error (:type (ex-data error)))))

(defn- await-uninterruptibly! [^CountDownLatch gate interrupted]
  (loop []
    (when-not (try (.await gate) true
                   (catch InterruptedException _ (deliver interrupted true) false))
      (recur))))

(deftest lexical-ownership-exact-room-current-thread-and-self-close
  (let [room (discourse/make-room {:id :host-operation-lexical})
        retained (atom nil)]
    (try
      (is (= :done
             (preparation/with-host-operation!
               room
               (fn [lease]
                 (reset! retained lease)
                 (is (= :host-operation (:kind lease)))
                 (is (= :current (preparation/with-host-current! lease (constantly :current))))
                 (preparation/drain-agent! room nil)
                 (preparation/assert-agent-quiescent! room nil)
                 (is (= :pending @(:state lease)))
                 (is (= ::preparation/stale-host-operation
                        @(future (failure-type #(preparation/with-host-current! lease (constantly :wrong))))))
                 (is (= ::preparation/self-drain (failure-type #(discourse/close-room! room))))
                 (is (nil? (:dvergr/fork-transfer-state @(:meta room))))
                 (is (not (.isInterrupted (Thread/currentThread))))
                 (is (= ::preparation/self-drain (failure-type #(preparation/drain-room! room))))
                 (is (= ::preparation/not-quiescent
                        (failure-type #(discourse/fork-room room {:clone-participants? false}))))
                 :done))))
      (is (empty? (get @(:meta room) preparation/leases-key)))
      (is (true? ((:finished? @retained))))
      (preparation/cancel! @retained)
      (is (not (.isInterrupted (Thread/currentThread))) "retired cancellation cannot interrupt its old owner")
      (is (= ::preparation/stale-host-operation
             (failure-type #(preparation/with-host-current! @retained (constantly :wrong)))))
      (is (= ::preparation/admission-closed
             (failure-type #(preparation/with-host-operation!
                              (assoc room :incarnation (random-uuid)) (fn [_] :wrong)))))
      (is (= ::preparation/admission-closed
             (failure-type #(preparation/with-host-operation!
                              (assoc room :meta (atom @(:meta room))) (fn [_] :wrong)))))
      (is (= ::body-failed
             (failure-type #(preparation/with-host-operation!
                              room (fn [_] (throw (ex-info "Body failed" {:type ::body-failed})))))))
      (is (empty? (get @(:meta room) preparation/leases-key)))
      (finally (discourse/close-room! room)))))

(deftest close-retains-ownership-until-physical-exit-and-fences-admission
  (let [room (discourse/make-room {:id :host-operation-close})
        entered (promise) interrupted (promise) closing (promise)
        gate (CountDownLatch. 1)
        task (future
               (preparation/with-host-operation!
                 room (fn [lease]
                        (deliver entered lease)
                        (await-uninterruptibly! gate interrupted)
                        :exited)))]
    (try
      (let [lease (deref entered 3000 ::timeout)]
        (is (map? lease))
        ;; Agent/participant drains must not select a HOST lease, even for nil.
        (preparation/drain-agent! room nil)
        (preparation/drain-participant! room {:active (atom nil)})
        (is (= :pending @(:state lease)))
        (is (= ::preparation/not-quiescent (failure-type #(preparation/assert-quiescent! room))))
        (binding [ec/*execution-context* (:ctx room)]
          (is (= ::preparation/not-quiescent
                 (failure-type #(registry/register! (assoc room :incarnation (random-uuid)))))))
        (add-watch (:meta room) ::closing
                   (fn [_ _ _ state]
                     (when (= :tearing-down (get-in state [:dvergr/fork-transfer-state :state]))
                       (deliver closing true))))
        (let [closed (future (discourse/close-room! room) :closed)]
          (is (= true (deref closing 3000 ::timeout)))
          (is (= true (deref interrupted 3000 ::timeout)))
          (is (not (realized? closed)))
          (is (not ((:finished? lease))))
          (is (identical? lease (get-in @(:meta room) [preparation/leases-key (:id lease)])))
          (is (= ::preparation/admission-closed
                 (failure-type #(preparation/with-host-operation! room (fn [_] :late)))))
          (.countDown gate)
          (is (= :exited (deref task 3000 ::timeout)))
          (is (= :closed (deref closed 10000 ::timeout)))
          (is (true? ((:finished? lease))))))
      (finally
        (.countDown gate)
        (deref task 10000 nil)
        (remove-watch (:meta room) ::closing)
        (discourse/close-room! room)))))

(deftest stale-drain-snapshot-does-not-interrupt-the-next-pool-job
  (let [room (discourse/make-room {:id :host-operation-reused-thread})
        pool (Executors/newSingleThreadExecutor)
        started (promise) interrupted (promise)
        gate (CountDownLatch. 1)]
    (try
      (let [first-job (.submit pool ^Callable (bound-fn []
                                                (preparation/with-host-operation! room identity)))
            lease (.get first-job 3 TimeUnit/SECONDS)
            next-job (.submit pool ^Callable (bound-fn []
                                               (deliver started true)
                                               (await-uninterruptibly! gate interrupted)
                                               (.isInterrupted (Thread/currentThread))))]
        (is (= true (deref started 3000 ::timeout)))
        (preparation/cancel! lease)
        (.countDown gate)
        (is (false? (.get next-job 3 TimeUnit/SECONDS)))
        (is (not (realized? interrupted))))
      (finally
        (.countDown gate)
        (.shutdownNow pool)
        (is (.awaitTermination pool 3 TimeUnit/SECONDS))
        (discourse/close-room! room)))))

(deftest host-leases-share-bounded-native-capacity
  (let [room (discourse/make-room {:id :host-operation-capacity})
        finished (atom false) dispatched (atom false)]
    (try
      (doseq [i (range 64)]
        (let [participant {:id (keyword (str "worker-" i))
                           :participant/incarnation (random-uuid) :active (atom nil)}]
          (swap! (:participants room) assoc (:id participant) participant)
          (preparation/admit! room participant
                              {:cancel! (constantly nil) :join! (fn [_] nil) :finished? #(deref finished)})))
      (is (= ::preparation/capacity-exhausted
             (failure-type #(preparation/with-host-operation! room (fn [_] (reset! dispatched true))))))
      (is (false? @dispatched))
      (is (= 64 (count (get @(:meta room) preparation/leases-key))))
      (finally
        (reset! finished true)
        (reset! (:participants room) {})
        (discourse/close-room! room)))))

(deftest failed-join-retains-the-owner-and-later-exit-allows-cleanup
  (let [room (discourse/make-room {:id :host-operation-join-retry})
        entered (promise) interrupted (promise)
        gate (CountDownLatch. 1)
        task (future (preparation/with-host-operation!
                       room (fn [lease]
                              (deliver entered lease)
                              (await-uninterruptibly! gate interrupted))))]
    (try
      (let [lease (deref entered 3000 ::timeout)]
        ;; Exercise the real bounded join, not a Future-cancellation receipt.
        (is (= ::preparation/drain-failed (failure-type #(preparation/drain-room! room))))
        (is (= true (deref interrupted 3000 ::timeout)))
        (is (not ((:finished? lease))))
        (is (identical? lease (get-in @(:meta room) [preparation/leases-key (:id lease)])))
        (.countDown gate)
        (deref task 3000 ::timeout)
        (preparation/drain-room! room)
        (is (empty? (get @(:meta room) preparation/leases-key))))
      (finally
        (.countDown gate)
        (deref task 10000 nil)
        (discourse/close-room! room)))))

(deftest engine-drain-cannot-own-a-blocking-host-operation
  (let [room (discourse/make-room {:id :host-operation-engine})
        result (promise) dispatched (atom false)
        gate (binding [ec/*execution-context* (:ctx room)] (sp/deferred))]
    (try
      (binding [ec/*execution-context* (:ctx room)]
        (sp/spawn!
         (sp/spin
          ;; The eager prefix of a Spin may run on its caller. Resume from a
          ;; real deferred event to test physical drain ownership, without a delay.
          (sp/await gate)
          (deliver result {:drain? (simple/on-drain-thread?)
                           :type (failure-type #(preparation/with-host-operation!
                                                  room (fn [_] (reset! dispatched true))))})))
        (sp/deliver! gate true))
      (is (= {:drain? true :type ::preparation/blocking-host-operation} (deref result 3000 ::timeout)))
      (is (false? @dispatched))
      (is (empty? (get @(:meta room) preparation/leases-key)))
      (finally (discourse/close-room! room)))))
