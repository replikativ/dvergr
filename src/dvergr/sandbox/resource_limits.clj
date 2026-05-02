(ns dvergr.sandbox.resource-limits
  "Resource bounding for SCI sandboxes.

  Uses SCI's :interrupt-fn callback (called at the start of every user-defined
  fn body / loop iteration) combined with a background watchdog thread that
  monitors per-thread memory allocation and CPU time via JMX.

  The check function reads a volatile boolean flag — ~1ns per iteration.
  The watchdog polls JMX every poll-ms and sets the flag when limits
  are exceeded. This decouples detection (expensive) from enforcement
  (cheap, runs in the hot loop).

  Usage:
    (let [wd (create-watchdog thread-id {:max-alloc-mb 100 :max-cpu-ms 30000})
          ctx (sci/init {:interrupt-fn (:check-fn wd)})]
      (try
        (sci/eval-string* ctx code)
        (finally ((:stop! wd)))))

  Or use the convenience wrapper:
    (eval-sandboxed sci-ctx code {:max-alloc-mb 100 :max-cpu-ms 5000})"
  (:import [com.sun.management ThreadMXBean]
           [java.lang.management ManagementFactory]))

;; =============================================================================
;; JMX Thread Bean (singleton)
;; =============================================================================

(def ^:private ^ThreadMXBean thread-mx
  (let [tmx ^ThreadMXBean (ManagementFactory/getThreadMXBean)]
    (.setThreadAllocatedMemoryEnabled tmx true)
    tmx))

;; =============================================================================
;; Resource Watchdog
;; =============================================================================

(defn create-watchdog
  "Create a resource watchdog for a specific thread.

  Monitors memory allocation and CPU time via JMX. Sets a volatile flag
  when limits are exceeded. The flag is read by SCI's :interrupt-fn
  callback on every fn-body / loop-iteration entry.

  Options:
    :max-alloc-mb   - Memory allocation limit in MB (default 256)
    :max-cpu-ms     - CPU time limit in ms (default 30000)
    :max-iterations - Hard iteration cap, checked inline (default nil)
    :poll-ms        - Watchdog poll interval in ms (default 10)

  Returns map:
    :check-fn - Pass this as SCI's :interrupt-fn option
    :stop!    - Call when eval is done to clean up watchdog thread
    :flag     - The volatile boolean (for inspection)
    :details  - Atom with details when limit exceeded
    :counter  - Volatile with iteration count"
  [eval-thread-id {:keys [max-alloc-mb max-cpu-ms max-iterations poll-ms]
                   :or {max-alloc-mb 256 max-cpu-ms 30000 poll-ms 10}}]
  (let [flag (volatile! false)
        details (atom nil)
        counter (volatile! 0)
        alloc-start (.getThreadAllocatedBytes thread-mx eval-thread-id)
        cpu-start (.getThreadCpuTime thread-mx eval-thread-id)
        max-alloc-bytes (long (* max-alloc-mb 1048576))
        max-cpu-ns (long (* max-cpu-ms 1000000))
        running (volatile! true)

        watchdog-thread
        (doto (Thread.
                (fn []
                  (while @running
                    (try (Thread/sleep poll-ms) (catch InterruptedException _))
                    (when (and @running (not @flag))
                      (let [alloc (- (.getThreadAllocatedBytes thread-mx eval-thread-id)
                                    alloc-start)
                            cpu (- (.getThreadCpuTime thread-mx eval-thread-id)
                                   cpu-start)]
                        (cond
                          (> alloc max-alloc-bytes)
                          (do (reset! details {:reason :memory
                                               :alloc-mb (/ (double alloc) 1048576.0)
                                               :limit-mb max-alloc-mb
                                               :iterations @counter})
                              (vreset! flag true))

                          (> cpu max-cpu-ns)
                          (do (reset! details {:reason :cpu-time
                                               :cpu-ms (/ (double cpu) 1000000.0)
                                               :limit-ms max-cpu-ms
                                               :iterations @counter})
                              (vreset! flag true)))))))
                "sci-watchdog")
          (.setDaemon true)
          (.start))]

    {:flag flag
     :details details
     :counter counter

     :check-fn
     (if max-iterations
       (let [max-iter (long max-iterations)]
         (fn []
           (when @flag
             (throw (ex-info (str "SCI resource limit: " (:reason @details))
                             (or @details {}))))
           (when (> (vswap! counter inc) max-iter)
             (throw (ex-info "SCI iteration limit"
                             {:reason :iterations
                              :count @counter
                              :limit max-iterations})))))
       (fn []
         (when @flag
           (throw (ex-info (str "SCI resource limit: " (:reason @details))
                           (or @details {}))))))

     :stop!
     (fn []
       (vreset! running false)
       (.interrupt watchdog-thread))}))

;; =============================================================================
;; Convenience API
;; =============================================================================

(defn eval-sandboxed
  "Evaluate SCI code with resource bounding.

  Attaches a watchdog to the current thread, runs eval, cleans up.

  Options: same as create-watchdog.

  Returns {:ok result} or {:error message :details {...}}"
  [sci-ctx code {:keys [max-alloc-mb max-cpu-ms max-iterations poll-ms]
                 :as limits}]
  (let [tid (.getId (Thread/currentThread))
        wd (create-watchdog tid (or limits {}))
        ctx (assoc sci-ctx :interrupt-fn (:check-fn wd))]
    (try
      {:ok (sci.core/eval-string* ctx code)}
      (catch clojure.lang.ExceptionInfo e
        {:error (.getMessage e) :details (ex-data e)})
      (catch Exception e
        {:error (.getMessage e) :details {:type (str (class e))}})
      (finally
        ((:stop! wd))))))

(defn wrap-resource-check
  "Add resource limits to an existing SCI context via :interrupt-fn.

  Returns [ctx stop-fn]. Call (stop-fn) when done with evaluation.

  Example:
    (let [[ctx stop] (wrap-resource-check base-ctx {:max-alloc-mb 50})]
      (try (sci/eval-string* ctx code)
           (finally (stop))))"
  [sci-ctx {:keys [max-alloc-mb max-cpu-ms max-iterations poll-ms]
            :as limits}]
  (let [tid (.getId (Thread/currentThread))
        wd (create-watchdog tid (or limits {}))]
    [(assoc sci-ctx :interrupt-fn (:check-fn wd))
     (:stop! wd)]))
