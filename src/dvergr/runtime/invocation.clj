(ns dvergr.runtime.invocation
  "HOST-native evaluation work. Result delivery and physical exit are separate;
   neither this handle nor its callbacks belong in forkable semantic state."
  (:import [java.util.concurrent Callable Executor FutureTask CountDownLatch TimeUnit]))

(defn- now [] (System/nanoTime))

(def ^:private reporting-failure
  {:success false :error {:type "EvaluationError" :message "Evaluation failed while reporting its error"}})

(defn- failure-result [error]
  (try {:success false :error {:type (.getName (class error)) :message (.getMessage ^Throwable error)}}
       (catch Throwable _ reporting-failure)))

(defn- default-cancel-result [{:keys [kind]}]
  {:success false
   :error {:type (case kind :timeout "TimeoutException" :rejected "RejectedExecutionException"
                       "CancellationException")
           :message (case kind :timeout "Evaluation timed out" :rejected "Evaluation dispatch was refused"
                          "Evaluation was cancelled")}})

(defn- cancellation-result [invocation reason]
  (try ((::cancel-result invocation) reason)
       (catch Throwable _
         {:success false :error {:type "CancellationException"
                                 :message "Evaluation stopped; output snapshot unavailable"}})))

(defn finished? [invocation] (realized? (::finished invocation)))
(defn reason [invocation] (:reason @(::state invocation)))
(defn aborted? [invocation] (some? (reason invocation)))
(defn body-entered? [invocation] (true? (:body-entered? @(::state invocation))))

(defn cancel!
  "Request a typed stop and freeze one caller result. A cancelled Future is
   not physical completion. The shared retirement gate prevents interruption
   of a later executor job reusing this worker thread. HOST-only."
  ([invocation] (cancel! invocation :cancelled))
  ([invocation kind]
   (let [state (::state invocation)]
     (locking state
       (when-not (= :finished (:phase @state))
         (when-not (:reason @state)
           (let [stop {:kind kind :body-entered? (:body-entered? @state)}]
             (swap! state assoc :reason stop)
             ;; Snapshot callbacks must be bounded private data reads, never
             ;; resource access or application callbacks under this monitor.
             (deliver (::result invocation) (cancellation-result invocation stop))))
         (when-let [^FutureTask task @(::task invocation)]
           (.cancel task true)))))
   invocation))

(defn- expired? [invocation]
  (when-let [deadline (::deadline invocation)]
    (not (pos? (- deadline (now))))))

(defn check!
  "Actual-worker admission fence. Call after waiting for application, Room or
   interpreter admission, immediately before entering user computation."
  [invocation]
  (let [state (::state invocation)]
    (locking state
      (when-not (and (= :running (:phase @state))
                     (identical? (Thread/currentThread) (:thread @state)))
        (throw (ex-info "Evaluation callback is not on its live worker" {:type ::foreign-worker})))
      (when (expired? invocation) (cancel! invocation :timeout))
      (when (and (.isInterrupted (Thread/currentThread)) (not (:reason @state)))
        (cancel! invocation :interrupted))
      (when-let [stop (:reason @state)]
        (throw (ex-info "Evaluation admission was cancelled" {:type ::aborted :reason stop})))))
  nil)

(defn- cancelled-task! [invocation]
  (let [state (::state invocation)]
    (locking state
      (when-not (= :finished (:phase @state))
        (when-not (:reason @state)
          (let [stop {:kind :cancelled :body-entered? (:body-entered? @state)}]
            (swap! state assoc :reason stop)
            (deliver (::result invocation) (cancellation-result invocation stop))))
        (when (= :new (:phase @state))
          (swap! state assoc :phase :finished :thread nil)
          (deliver (::finished invocation) true))))))

(defn- worker-finished! [invocation result]
  (let [state (::state invocation)]
    (locking state
      (try
        (swap! state assoc :phase :finished :thread nil)
        (finally
          ;; All resource-owning work ended before this helper. A normal
          ;; result must not race ahead of its physical-completion ACK.
          (deliver (::finished invocation) true)
          (when-not (realized? (::result invocation))
            (deliver (::result invocation) result)))))))

(defn start!
  "Dispatch one native function on the existing Clojure executor. f receives
   its private handle on the actual worker and must complete all owned native
   access before returning. A supplied Executor is a trusted HOST/test seam.
   A monotonic timeout includes queued admission; actual evaluation uses
   with-deadline! inside its resource-owning wrapper. Awaiting also bounds work
   that has not yet entered that wrapper."
  [f {:keys [timeout-ms cancel? cancel-result executor] :as options}]
  (when-not (and (fn? f) (map? options)
                 (every? #{:timeout-ms :cancel? :cancel-result :executor} (keys options))
                 (or (nil? timeout-ms) (and (integer? timeout-ms) (<= 1 timeout-ms 2147483647)))
                 (or (nil? cancel?) (fn? cancel?))
                 (or (nil? cancel-result) (fn? cancel-result))
                 (or (nil? executor) (instance? Executor executor)))
    (throw (ex-info "Invalid native evaluation invocation" {:type ::invalid-options})))
  (let [state (atom {:phase :new :thread nil :reason nil :body-entered? false})
        work (bound-fn [invocation] (f invocation))
        invocation {:id (random-uuid) ::state state ::task (atom nil)
                    ::result (promise) ::finished (promise)
                    ::deadline (when timeout-ms (+ (now) (* 1000000 (long timeout-ms))))
                    ::cancel? cancel? ::cancel-result (or cancel-result default-cancel-result)}
        task (proxy [FutureTask]
                    [(reify Callable
                       (call [_]
                         (let [entered? (locking state
                                          (when (= :new (:phase @state))
                                            (swap! state assoc :phase :running :thread (Thread/currentThread))
                                            true))]
                           (when entered?
                             (let [result (volatile! reporting-failure)]
                               (try
                                 (vreset! result (try (check! invocation) (work invocation)
                                                      (catch Throwable error (failure-result error))))
                                 @result
                                 (finally
                                  ;; f's finally (including ownership release)
                                  ;; ended. ACK even if error reporting failed.
                                   (worker-finished! invocation @result))))))))]
               (done []
                 (when (.isCancelled this) (cancelled-task! invocation))))]
    (reset! (::task invocation) task)
    (try (.execute ^Executor (or executor clojure.lang.Agent/soloExecutor) task)
         (catch Throwable _ (cancel! invocation :rejected)))
    invocation))

(defn join!
  "Join actual worker-finally completion, or confirmed cancellation before any
   entry. This never treats Future.done/cancelled as a running-worker ACK."
  [invocation timeout-ms]
  (when-not (true? (deref (::finished invocation) timeout-ms nil))
    (throw (ex-info "Evaluation has not physically exited" {:type ::unconfirmed-exit :id (:id invocation)})))
  true)

(defn result-now
  "A boxed immutable delivered result, or nil before delivery. The retirement
   gate excludes observing a stop reason before its snapshot is frozen."
  [invocation]
  (locking (::state invocation)
    (when (realized? (::result invocation)) {:result @(::result invocation)})))

(defn await!
  "Wait for one bounded caller result. Interruption requests cancellation and
   rethrows with the caller's interrupt status preserved. No implicit retry."
  [invocation]
  (try
    (let [pending (Object.)]
      (loop []
        (when-let [cancel? (::cancel? invocation)]
          (try (when (cancel?) (cancel! invocation :cancelled))
               (catch InterruptedException error (throw error))
               (catch Throwable _ (cancel! invocation :predicate-failed))))
        (when (expired? invocation) (cancel! invocation :timeout))
        (let [result (deref (::result invocation) 50 pending)]
          (if (identical? pending result) (recur) result))))
    (catch InterruptedException error
      (cancel! invocation :caller-interrupted)
      (.interrupt (Thread/currentThread))
      (throw error))))

(defn with-deadline!
  "Run actual user computation once admitted. The watchdog has no application
   or Room callbacks; retire and physically join it BEFORE releasing the caller's
   surrounding ownership wrapper. Cancellation state survives swallowed thread
   interruptions. No new executor is created."
  [invocation f]
  (check! invocation)
  (let [stop (CountDownLatch. 1)
        watchdog (when-let [deadline (::deadline invocation)]
                   (doto (Thread.
                          ^Runnable
                          (fn []
                            (try
                              (let [remaining (max 0 (- deadline (now)))]
                                (when-not (.await stop remaining TimeUnit/NANOSECONDS)
                                  (cancel! invocation :timeout)))
                              (catch InterruptedException _ nil)))
                          (str "dvergr-eval-deadline-" (:id invocation)))
                     (.setDaemon true)
                     (.start)))]
    (try
      ;; Admission and the entry marker share cancellation's monitor, so a
      ;; stopped waiter can never execute later with a false no-entry receipt.
      (locking (::state invocation)
        (check! invocation)
        (swap! (::state invocation) assoc :body-entered? true))
      (f)
      (finally
        ;; Scheduler delay must never turn overdue work into healthy success.
        ;; This trusted state is visible to the surrounding retirement wrapper.
        (when (expired? invocation) (cancel! invocation :timeout))
        (.countDown stop)
        (when watchdog
          ;; An interrupted evaluator must still join its private watchdog.
          ;; No retirement monitor is held here, so the guard can finish.
          (loop []
            (let [joined? (try (.join ^Thread watchdog) true
                               (catch InterruptedException _
                                 (when-not (aborted? invocation) (cancel! invocation :interrupted))
                                 false))]
              (when-not joined? (recur)))))
        (when (and (.isInterrupted (Thread/currentThread)) (not (aborted? invocation)))
          (cancel! invocation :interrupted))))))
