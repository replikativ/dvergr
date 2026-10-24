(ns dvergr.fork-retention-test
  "What an ended fork or a finished future must not keep alive: a fork's own
   timers (the timer queue held the whole fork context until they fired), and
   the bindings a future conveyed to its pool thread."
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.discourse :as d]
            [dvergr.runtime.threads :as threads]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.engine.impl.delayed :as delayed]))

(defn- arm! [ctx]
  (delayed/set-time-mode! ctx :real)
  (delayed/schedule-delayed! ctx 600000 (fn [] nil))
  (binding [ec/*execution-context* ctx]
    (first (vals (ec/get-state [:engine/timer-handles])))))

(defn- handles [ctx]
  (binding [ec/*execution-context* ctx] (ec/get-state [:engine/timer-handles])))

(deftest an-ended-fork-leaves-no-armed-timer
  (doseq [[how end!] [["discarded" (fn [_ fork] (d/discard fork))]
                      ["merged" (fn [parent fork] (d/merge-room parent fork))]
                      ["closed" (fn [_ fork] (d/close-room! fork))]]]
    (testing how
      (let [parent (d/room (keyword (str "fork-timers-" how)))
            fork (d/fork-room parent {:isolation :ctx})
            parent-timer (arm! (:ctx parent))
            fork-timer (arm! (:ctx fork))]
        (try
          (is (instance? java.util.concurrent.Future fork-timer))
          (end! parent fork)
          (is (empty? (handles (:ctx fork))) "the fork's timers are released")
          (is (.isCancelled ^java.util.concurrent.Future fork-timer))
          (is (not (.isCancelled ^java.util.concurrent.Future parent-timer)) "the parent's are not")
          (finally
            (delayed/cancel-all-timers! (:ctx parent))
            (d/close-room! parent)))))))

(def ^:dynamic *held* nil)

(defn- held-after?
  "Whether the value bound for `run-task` is still reachable once it ended.
   The referent is read only after collecting, so no local keeps it alive."
  [run-task]
  (let [ref (binding [*held* (Object.)]
              (let [r (java.lang.ref.WeakReference. *held*)]
                (run-task)
                r))]
    (dotimes [_ 10] (System/gc) (Thread/sleep 50))
    (some? (.get ref))))

(deftest a-finished-future-drops-what-it-was-bound-to
  ;; With clojure.core/future the value stays reachable from the idle pool
  ;; thread's binding frame until that thread runs another task.
  (is (false? (held-after? #(deref (threads/future (assert (some? *held*) "the task sees the binding")))))
      "nothing keeps the bound value once the task has ended"))
