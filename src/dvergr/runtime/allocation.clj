(ns dvergr.runtime.allocation
  "HOST allocation observations. They belong to actual evaluation extents,
   never to an interpreter, world, or forked semantic state."
  (:import [java.lang.management ManagementFactory]))

(def ^:private mx (ManagementFactory/getThreadMXBean))
(defn- allocation-enabled? []
  (and (instance? com.sun.management.ThreadMXBean mx)
       (.isThreadAllocatedMemoryEnabled ^com.sun.management.ThreadMXBean mx)))
(defn- allocated-bytes [thread-id]
  (.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean mx (long thread-id)))
;; Plain ThreadLocal: semantic binding frames/continuations never convey it.
;; This Var is read at every checkpoint; reflection allocates method arrays.
(def ^:private ^ThreadLocal current-observation (ThreadLocal.))

(defn- observation [kind]
  (let [thread-id (.getId (Thread/currentThread))]
    {:thread-id thread-id :kind kind
     :baseline (when (allocation-enabled?)
                 (allocated-bytes thread-id))
     :checks (volatile! 0)}))

(defn call-with-scope
  "Run top-level SCI work with a fresh allocation observation on this thread.
   Nested scopes are independent; the outer baseline includes their allocation.
   The scope is HOST-only and never conveyed by bound-fn or FRP continuations."
  [f]
  (let [previous (.get current-observation)
        scope (observation :evaluation)]
    (.set current-observation scope)
    (try (f)
         (finally
           (if previous (.set current-observation previous) (.remove current-observation))))))

(defn interrupt-policy
  "Return SCI's interruption/allocation callback for an immutable byte limit.
   Owned callers use call-with-scope. Raw embedding without a scope keeps a
   conservative cumulative allowance per physical thread, starting at its
   first checkpoint. That fallback cannot infer raw SCI evaluation boundaries
   and never rebases itself after exceeding its limit."
  [max-bytes]
  (let [fallback (ThreadLocal.)]
    (fn []
      (when (.isInterrupted (Thread/currentThread))
        (Thread/interrupted)
        (throw (ex-info "Evaluation interrupted" {:cause :thread-interrupt})))
      (let [thread-id (.getId (Thread/currentThread))
            scope (or (.get current-observation)
                      (.get fallback)
                      (let [scope (observation :implicit-thread)]
                        (.set fallback scope)
                        scope))]
        (when (and (zero? (mod (vswap! (:checks scope) inc) 10000))
                   (:baseline scope) (allocation-enabled?))
          (let [delta (- (allocated-bytes thread-id) (:baseline scope))]
            (when (> delta max-bytes)
              (throw (ex-info "Resource limit: memory exceeded"
                              {:limit-bytes max-bytes :allocated-bytes delta
                               :allocation-scope (:kind scope)
                               :hint "Raw SCI embedders should wrap top-level work in call-with-allocation-scope."})))))))))
