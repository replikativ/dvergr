(ns dvergr.runtime.threads
  "Threads that do not keep what their tasks were bound to.

   `clojure.core/future` conveys the caller's dynamic bindings to the pool
   thread and never restores the thread's own frame when the task ends. An idle
   pool thread then keeps the last task's bindings reachable: in dvergr that is
   the forked execution context a Run or attempt was bound to, with all the
   state it holds, until the thread runs another task or dies."
  (:refer-clojure :exclude [future future-call])
  (:import [java.util.concurrent Callable Executors ThreadFactory]
           [java.util.concurrent.atomic AtomicLong]))

(defonce ^:private native-thread-counter (AtomicLong.))

(defonce native-executor
  ;; Lifecycle joiners must not occupy Agent/soloExecutor while waiting for
  ;; children whose lifecycle monitors are queued on that same bounded pool.
  (Executors/newCachedThreadPool
   (reify ThreadFactory
     (newThread [_ runnable]
       (doto (Thread. ^Runnable runnable)
         (.setName (str "dvergr-agent-native-" (.incrementAndGet native-thread-counter)))
         (.setDaemon true))))))

(def ^:private top-frame
  "The binding frame of a thread that was never bound: what a pool thread
   should hold between tasks (`Var$Frame/TOP` is not public)."
  (let [p (promise)]
    (doto (Thread. #(deliver p (clojure.lang.Var/getThreadBindingFrame))) (.start))
    @p))

(defn future-call
  "`clojure.core/future-call` whose pool thread drops the task's bindings when
   the task ends.

   It submits with the caller's bindings set aside, so the pool thread is given
   an empty frame, and installs the caller's bindings around the task itself,
   restoring whatever frame the running thread had before: an idle pool thread
   holds nothing, and a caller that runs the task itself (an inline executor,
   a test) keeps its own bindings."
  [f]
  (let [conveyed (clojure.lang.Var/cloneThreadBindingFrame)
        own (clojure.lang.Var/getThreadBindingFrame)
        task (fn []
               (let [before (clojure.lang.Var/getThreadBindingFrame)]
                 (clojure.lang.Var/resetThreadBindingFrame conveyed)
                 (try (f)
                      (finally (clojure.lang.Var/resetThreadBindingFrame before)))))]
    (clojure.lang.Var/resetThreadBindingFrame top-frame)
    (try (clojure.core/future-call task)
         (finally (clojure.lang.Var/resetThreadBindingFrame own)))))

(defmacro future
  "`clojure.core/future` whose pool thread drops the task's bindings when the
   task ends. The task still sees the caller's bindings."
  [& body]
  `(future-call (^{:once true} fn* [] ~@body)))

(defn native-future-call
  "Submit a native lifecycle task to Dvergr's existing cached daemon pool.
   Convey bindings for this task and restore the pool thread's frame on exit.
   Returns a java.util.concurrent.Future, independent of Agent executors."
  [f]
  (let [bindings (get-thread-bindings)
        own (clojure.lang.Var/getThreadBindingFrame)
        task (fn []
               ;; Fresh binding boxes also isolate set! when an executor runs
               ;; the task inline on its caller. Cloned frames share boxes.
               (with-bindings bindings (f)))]
    (clojure.lang.Var/resetThreadBindingFrame top-frame)
    (try (.submit native-executor ^Callable task)
         (finally (clojure.lang.Var/resetThreadBindingFrame own)))))

(defmacro native-future [& body]
  `(native-future-call (^{:once true} fn* [] ~@body)))
