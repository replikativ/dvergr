(ns dvergr.runtime.threads
  "Threads that do not keep what their tasks were bound to.

   `clojure.core/future` conveys the caller's dynamic bindings to the pool
   thread and never restores the thread's own frame when the task ends. An idle
   pool thread then keeps the last task's bindings reachable: in dvergr that is
   the forked execution context a Run or attempt was bound to, with all the
   state it holds, until the thread runs another task or dies."
  (:refer-clojure :exclude [future future-call]))

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
