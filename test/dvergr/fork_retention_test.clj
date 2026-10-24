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

(defn- cancelled? [^java.util.concurrent.Future f] (.isCancelled f))

(deftest an-ended-fork-leaves-no-armed-timer
  (doseq [[how end!] [["discarded" (fn [_ fork] (d/discard fork))]
                      ["merged" (fn [parent fork] (d/merge-room parent fork))]
                      ["closed" (fn [_ fork] (d/close-room! fork))]]]
    (testing how
      (let [parent (d/room (keyword (str "fork-timers-" how)))
            ;; armed BEFORE the fork: a fork must not inherit (and cancel) it
            parent-timer (arm! (:ctx parent))
            fork (d/fork-room parent {:isolation :ctx})
            fork-timer (arm! (:ctx fork))]
        (try
          (is (= 1 (count (handles (:ctx fork)))) "the fork starts with only its own timer")
          (end! parent fork)
          (is (empty? (handles (:ctx fork))) "the fork's timers are released")
          (is (cancelled? fork-timer))
          (is (not (cancelled? parent-timer)) "the parent's are not")
          (finally
            (delayed/cancel-all-timers! (:ctx parent))
            (d/close-room! parent)))))))

(deftest a-shared-context-fork-leaves-its-owners-timers
  ;; A :none fork inside an isolated fork shares that fork's context: ending it
  ;; must not cancel the isolated fork's timers.
  (let [root (d/room :fork-timers-nested)
        fork (d/fork-room root {:isolation :ctx})
        fork-timer (arm! (:ctx fork))
        nested (d/fork-room fork {:isolation :none})]
    (try
      (is (identical? (:ctx fork) (:ctx nested)))
      (d/discard nested)
      (is (not (cancelled? fork-timer)) "discarding the :none fork keeps them")
      (d/discard fork)
      (is (cancelled? fork-timer) "discarding their owner releases them")
      (finally
        (delayed/cancel-all-timers! (:ctx root))
        (d/close-room! root)))))

(def ^:dynamic *held* nil)

(defn- pool-thread-frame-after
  "Run `start-task` (which starts a future) with a value bound, on a single
   worker thread standing in for the agent pool, then read that worker's
   binding frame directly: deterministic, no GC or pool reuse involved."
  [start-task]
  (let [worker (java.util.concurrent.Executors/newSingleThreadExecutor)
        original clojure.lang.Agent/soloExecutor]
    (try
      (set-agent-send-off-executor! worker)
      (binding [*held* :bound] @(start-task))
      (.get (.submit worker ^java.util.concurrent.Callable #(clojure.lang.Var/getThreadBindingFrame)))
      (finally
        (set-agent-send-off-executor! original)
        (.shutdown worker)))))

(def ^:private top-frame @#'threads/top-frame)

(deftest a-finished-future-drops-what-it-was-bound-to
  (is (identical? top-frame (pool-thread-frame-after #(threads/future (assert (= :bound *held*)))))
      "the worker holds the empty frame again once the task ended")
  (is (not (identical? top-frame (pool-thread-frame-after #(future (assert (= :bound *held*))))))
      "clojure.core/future, by contrast, leaves the task's bindings on the worker"))
