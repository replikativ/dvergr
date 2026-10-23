(ns dvergr.runtime.invocation-test
  (:require [clojure.test :refer [deftest is]]
            [dvergr.runtime.invocation :as invocation])
  (:import [java.util.concurrent CountDownLatch TimeUnit Executor FutureTask RejectedExecutionException
            Callable Executors]))

(defn- await-release! [^CountDownLatch release]
  (let [deadline (+ (System/nanoTime) 30000000000)]
    (loop []
      (let [remaining (max 0 (- deadline (System/nanoTime)))
            released? (try (.await release remaining TimeUnit/NANOSECONDS)
                           (catch InterruptedException _ ::interrupted))]
        (cond (= ::interrupted released?) (recur)
              released? true
              :else (throw (ex-info "Finite invocation test latch expired" {})))))))

(defn- held [options]
  (let [entered (promise) released (CountDownLatch. 1) exits (atom 0)
        handle (invocation/start!
                (fn [control]
                  (invocation/with-deadline!
                    control #(try (deliver entered true) (await-release! released)
                                  {:success true :value :late}
                                  (finally (swap! exits inc)))))
                (merge {:timeout-ms 60000} options))]
    {:handle handle :entered entered :release released :exits exits}))

(defn- stop! [{:keys [handle release]}]
  (.countDown ^CountDownLatch release)
  (invocation/cancel! handle)
  (invocation/join! handle 30000))

(deftest success-error-and-dynamic-bindings-complete-on-the-actual-worker
  (let [caller (Thread/currentThread)
        handle (binding [*print-length* 2]
                 (invocation/start! (fn [control]
                                      (invocation/with-deadline!
                                        control #(hash-map :success true :worker? (not (identical? caller (Thread/currentThread)))
                                                           :printed (pr-str (range 5)))))
                                    {:timeout-ms 30000}))]
    (is (= {:success true :worker? true :printed "(0 1 ...)"} (invocation/await! handle)))
    (is (invocation/finished? handle) "Normal delivery follows physical completion")
    (is (true? (invocation/join! handle 30000)))
    (is (invocation/finished? handle)))
  (let [handle (invocation/start! (fn [_] (throw (ex-info "Controlled failure" {}))) {})]
    (is (= "Controlled failure" (get-in (invocation/await! handle) [:error :message])))
    (is (true? (invocation/join! handle 30000)))))

(deftest cancellation-result-is-not-a-future-or-physical-completion-ack
  (let [{:keys [handle entered exits] :as work} (held {})]
    (try
      (is (true? (deref entered 30000 nil)))
      (invocation/cancel! handle)
      (let [result (invocation/await! handle)]
        (is (= "CancellationException" (get-in result [:error :type])))
        (is (.isCancelled ^FutureTask @(::invocation/task handle)))
        (is (.isDone ^FutureTask @(::invocation/task handle)))
        (is (false? (invocation/finished? handle)))
        (is (true? (invocation/body-entered? handle)))
        (is (zero? @exits))
        (is (thrown? clojure.lang.ExceptionInfo (invocation/join! handle 0)))
        (.countDown ^CountDownLatch (:release work))
        (invocation/join! handle 30000)
        (is (= result (invocation/await! handle)) "A late healthy return cannot overwrite the stop receipt")
        (is (= 1 @exits)))
      (finally (stop! work)))))

(deftest cancellation-before-entry-and-rejected-dispatch-have-no-entry-acks
  (let [queue (atom []) entries (atom 0)
        executor (reify Executor (execute [_ task] (swap! queue conj task)))
        handle (invocation/start! (fn [_] (swap! entries inc)) {:executor executor})]
    (invocation/cancel! handle)
    (is (true? (invocation/join! handle 0)))
    (.run ^Runnable (first @queue))
    (is (zero? @entries))
    (is (false? (invocation/body-entered? handle)))
    (is (= "CancellationException" (get-in (invocation/await! handle) [:error :type]))))
  (let [entries (atom 0)
        handle (invocation/start! (fn [_] (swap! entries inc))
                                  {:executor (reify Executor (execute [_ _] (throw (RejectedExecutionException. "Controlled"))))})]
    (is (true? (invocation/join! handle 0)))
    (is (zero? @entries))
    (is (= "RejectedExecutionException" (get-in (invocation/await! handle) [:error :type])))))

(deftest an-executor-cancelling-the-queued-future-cannot-strand-result-delivery
  (let [handle (invocation/start! (fn [_] :wrong)
                                  {:executor (reify Executor (execute [_ task] (.cancel ^FutureTask task false)))})]
    (is (invocation/finished? handle))
    (is (= "CancellationException" (get-in (invocation/await! handle) [:error :type])))))

(deftest timeout-and-repeated-cancel-freeze-only-one-output-snapshot
  (let [snapshots (atom 0) output (atom "before")
        {:keys [handle entered] :as work}
        (held {:cancel-result (fn [reason] (swap! snapshots inc)
                                {:success false :reason (:kind reason) :stdout @output})})]
    (try
      (is (true? (deref entered 30000 nil)))
      (invocation/cancel! handle :timeout)
      (reset! output "after")
      (invocation/cancel! handle)
      (is (= {:success false :reason :timeout :stdout "before"} (invocation/await! handle)))
      (is (= 1 @snapshots))
      (is (not (invocation/finished? handle)))
      (finally (stop! work)))))

(deftest caller-interruption-cancels-without-forging-worker-exit
  (let [{:keys [handle entered] :as work} (held {}) waiting (promise) outcome (promise)
        waiter (Thread. ^Runnable (fn [] (deliver waiting true)
                                    (try (invocation/await! handle) (deliver outcome :wrong)
                                         (catch InterruptedException _
                                           (deliver outcome (.isInterrupted (Thread/currentThread)))))))]
    (try
      (is (true? (deref entered 30000 nil)))
      (.start waiter)
      (is (true? (deref waiting 30000 nil)))
      (.interrupt waiter)
      (is (true? (deref outcome 30000 nil)))
      (is (= :caller-interrupted (:kind (invocation/reason handle))))
      (is (not (invocation/finished? handle)))
      (finally
        (stop! work)
        (.join waiter 30000)
        (is (not (.isAlive waiter)))))))

(deftest actual-deadline-interrupts-finite-cooperative-work-and-retires-its-watchdog
  (let [entered (promise) exits (atom 0) release (CountDownLatch. 1)
        handle (invocation/start!
                (fn [control]
                  (invocation/with-deadline!
                    control #(try (deliver entered true)
                                  (.await release 30000 TimeUnit/MILLISECONDS)
                                  {:success true}
                                  (finally (swap! exits inc)))))
                {:timeout-ms 1000})]
    (try
      (is (true? (deref entered 30000 nil)))
      (is (= "TimeoutException" (get-in (invocation/await! handle) [:error :type])))
      (is (true? (invocation/join! handle 30000)))
      (is (= 1 @exits))
      (finally (.countDown release) (invocation/cancel! handle) (invocation/join! handle 30000)))))

(deftest overdue-return-is-stopped-even-when-the-watchdog-observes-an-older-clock
  (let [clock (atom 0) worker (atom nil) observed (promise)]
    (with-redefs-fn {#'invocation/now (fn [] (if (identical? @worker (Thread/currentThread)) @clock 0))}
      (fn []
        (let [handle (invocation/start!
                      (fn [control]
                        (reset! worker (Thread/currentThread))
                        (let [result (invocation/with-deadline!
                                       control #(do (reset! clock 60000000001) {:success true :value :overdue}))]
                          ;; This represents the owning wrapper's retirement
                          ;; decision, before that wrapper releases its owners.
                          (deliver observed (invocation/reason control))
                          result))
                      {:timeout-ms 60000})]
          (try
            (is (= "TimeoutException" (get-in (invocation/await! handle) [:error :type])))
            (is (true? (invocation/join! handle 30000)))
            (is (= {:kind :timeout :body-entered? true} (deref observed 30000 nil)))
            (finally (invocation/cancel! handle) (invocation/join! handle 30000))))))))

(deftest failed-error-formatting-does-not-strand-native-retirement
  (let [bad-error (proxy [Exception] [] (getMessage [] (throw (AssertionError. "Controlled formatter failure"))))
        handle (invocation/start! (fn [_] (throw bad-error)) {:timeout-ms 30000})]
    (try
      (is (= "EvaluationError" (get-in (invocation/await! handle) [:error :type])))
      (is (true? (invocation/join! handle 30000)))
      (is (invocation/finished? handle))
      (finally (invocation/cancel! handle) (invocation/join! handle 30000)))))

(deftest callbacks-refuse-cross-thread-and-retired-invocations
  (let [entries (atom 0)
        handle (invocation/start!
                (fn [control]
                  {:foreign (deref (future
                                     (try (invocation/with-deadline! control #(swap! entries inc))
                                          (catch clojure.lang.ExceptionInfo error (:type (ex-data error)))))
                                   30000 nil)})
                {:timeout-ms 30000})]
    (try
      (is (= {:foreign ::invocation/foreign-worker} (invocation/await! handle)))
      (is (zero? @entries))
      (invocation/join! handle 30000)
      (is (thrown? clojure.lang.ExceptionInfo (invocation/check! handle)))
      (finally (invocation/cancel! handle) (invocation/join! handle 30000)))))

(deftest late-cancellation-cannot-mark-success-aborted-or-interrupt-a-reused-executor-thread
  (let [executor (Executors/newSingleThreadExecutor) entered (promise) release (CountDownLatch. 1)
        handle (invocation/start! (fn [_] {:thread (.getId (Thread/currentThread))}) {:executor executor})]
    (try
      (let [first-result (invocation/await! handle)]
        (invocation/join! handle 30000)
        (let [next-job (.submit executor
                                ^Callable
                                (reify Callable
                                  (call [_]
                                    (deliver entered (.getId (Thread/currentThread)))
                                    (.await release 30000 TimeUnit/MILLISECONDS)
                                    (.isInterrupted (Thread/currentThread)))))]
          (is (= (:thread first-result) (deref entered 30000 nil)))
          (invocation/cancel! handle)
          (is (nil? (invocation/reason handle)))
          (.countDown release)
          (is (false? (.get next-job 30 TimeUnit/SECONDS)))
          (is (= first-result (invocation/await! handle)))))
      (finally
        (.countDown release)
        (.shutdownNow executor)
        (is (.awaitTermination executor 30 TimeUnit/SECONDS))))))

(deftest interruption-inside-the-cancellation-predicate-preserves-caller-status
  (let [{:keys [handle entered] :as work}
        (held {:cancel? (fn [] (throw (InterruptedException. "Controlled predicate interruption")))})]
    (try
      (is (true? (deref entered 30000 nil)))
      ;; Capture before clojure.test reporting/STM can consume the thread flag.
      (let [observed (try (invocation/await! handle) {:thrown? false}
                          (catch InterruptedException _
                            {:thrown? true :interrupted? (.isInterrupted (Thread/currentThread))}))]
        (Thread/interrupted)
        (is (:thrown? observed))
        (is (:interrupted? observed)))
      (is (= :caller-interrupted (:kind (invocation/reason handle))))
      (is (not (invocation/finished? handle)))
      (finally (Thread/interrupted) (stop! work)))))

(deftest cancellation-during-admission-cannot-enter-user-body-afterward
  (let [admitted (promise) release (CountDownLatch. 1) entries (atom 0)
        handle (invocation/start!
                (fn [control]
                  (deliver admitted true)
                  (await-release! release)
                  (invocation/with-deadline! control #(swap! entries inc)))
                {:timeout-ms 30000})]
    (try
      (is (true? (deref admitted 30000 nil)))
      (invocation/cancel! handle)
      (is (false? (:body-entered? (invocation/reason handle))))
      (.countDown release)
      (is (true? (invocation/join! handle 30000)))
      (is (zero? @entries))
      (is (false? (invocation/body-entered? handle)))
      (finally (.countDown release) (invocation/cancel! handle) (invocation/join! handle 30000)))))
