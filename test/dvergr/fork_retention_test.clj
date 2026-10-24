(ns dvergr.fork-retention-test
  "What an ended fork or a finished future must not keep alive: a fork's own
   timers (the timer queue held the whole fork context until they fired), and
   the bindings a future conveyed to its pool thread."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [dvergr.discourse :as d]
            [dvergr.orchestration.daemon :as daemon]
            [dvergr.runtime.threads :as threads]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.yggdrasil :as ygg]
            [dvergr.substrate.geschichte :as geschichte]
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

(deftest a-transferred-fork-releases-its-timers-once-settled
  (doseq [operation [:merge :discard]]
    (testing (name operation)
      (let [parent (d/room (keyword (str "fork-timers-transfer-" (name operation))))
            fork (d/fork-room parent {:isolation :ctx})
            fork-timer (arm! (:ctx fork))
            transfer (d/transfer-fork! fork :owner {:prepare! (constantly :receipt) :abort! (fn [_])})]
        (try
          (is (not (cancelled? fork-timer)) "a transfer keeps the fork's timers")
          (let [settled (d/settle-transferred-fork! transfer operation)]
            (is (not (cancelled? fork-timer)) "settling a leaf alone keeps them")
            (d/release-transferred-fork! settled))
          (is (cancelled? fork-timer) "releasing the settled transfer cancels them")
          (finally
            (delayed/cancel-all-timers! (:ctx fork))
            (d/close-room! parent)))))))

(deftest a-partition-of-a-transfer-cannot-release-it
  (let [dir (str (System/getProperty "java.io.tmpdir") "/dvergr-fork-timers-" (System/nanoTime))
        ;; a world with systems to partition: a git workspace beside the room
        base (daemon/create-shared-context :repo-path (str dir "/repository")
                                           :with-git? true :with-datahike? false)
        ;; and a second repository, so the world has two settlement scopes
        _ (binding [ec/*execution-context* base]
            (ygg/register! (geschichte/create-system :scope (str dir "/second-repository")
                                                     :system-name :second-repository)))
        parent (d/room :fork-timers-partition base)
        fork (d/fork-room parent {:isolation :ctx})
        fork-timer (arm! (:ctx fork))
        transfer (d/transfer-fork! fork :owner {:prepare! (constantly :receipt) :abort! (fn [_])})
        systems (vec (keys (get-in transfer [:fork/descriptor :fork/systems])))
        lifecycle {:prepare! (fn [plan] (:fork/operation plan)) :abort! (fn [_]) :commit! (fn [_ _])}]
    (try
      (is (<= 2 (count systems)) "the world has systems to partition")
      (when (<= 2 (count systems))
        (let [tree (d/partition-transferred-fork!
                    transfer
                    [{:systems #{(first systems)} :owner :a :purpose :component}
                     {:systems (set (rest systems)) :owner :b :purpose :component}]
                    {:prepare! (constantly :partition-receipt) :abort! (fn [_]) :commit! (fn [_ _])})
              [a b] (:fork/partitions tree)
              a (d/settle-transferred-fork! a :discard lifecycle)]
          (is (= ::d/partition-cannot-release
                 (:type (ex-data (try (d/release-transferred-fork! a)
                                      (catch clojure.lang.ExceptionInfo e e))))))
          (is (not (cancelled? fork-timer)) "the open sibling's timers survive")
          (let [b (d/settle-transferred-fork! b :discard lifecycle)]
            (d/release-transferred-fork! (assoc tree :fork/partitions [a b])))
          (is (cancelled? fork-timer) "releasing the whole tree cancels them")))
      (finally
        (delayed/cancel-all-timers! (:ctx fork))
        (d/close-room! parent)
        (run! io/delete-file (reverse (file-seq (io/file dir))))))))

(def ^:dynamic *held* nil)

(defn- worker-holds-after?
  "Run `start-task` (which starts a future) with `*held*` bound, on a single
   worker thread standing in for the agent pool, then ask that worker whether
   it still has `*held*` bound: deterministic, no GC or pool reuse involved."
  [start-task]
  (let [worker (java.util.concurrent.Executors/newSingleThreadExecutor)
        original clojure.lang.Agent/soloExecutor]
    (try
      (set-agent-send-off-executor! worker)
      (binding [*held* :bound] @(start-task))
      (.get (.submit worker ^java.util.concurrent.Callable
                     #(contains? (get-thread-bindings) #'*held*)))
      (finally
        (set-agent-send-off-executor! original)
        (.shutdown worker)))))

(deftest a-finished-future-drops-what-it-was-bound-to
  (is (false? (worker-holds-after? #(threads/future (assert (= :bound *held*)))))
      "the worker holds nothing once the task ended")
  (is (true? (worker-holds-after? #(future (assert (= :bound *held*)))))
      "clojure.core/future, by contrast, leaves the task's bindings on the worker"))

(deftest a-future-queued-by-its-own-worker-drops-its-bindings
  ;; a task that starts a future on the same single worker: it runs later, on
  ;; the same thread, and must not leave the bindings behind either
  (is (false? (worker-holds-after?
               #(threads/future
                  (binding [*held* :nested] (threads/future (assert (= :nested *held*)))))))))

(deftest a-future-run-later-by-its-caller-keeps-the-callers-bindings
  ;; evaluation-test queues the task and runs it itself on the calling thread
  (let [queued (promise)]
    (with-redefs [clojure.core/future-call (fn [f] (deliver queued f) (promise))]
      (binding [*held* :outer]
        (threads/future (assert (= :outer *held*)))
        (binding [*held* :inner]
          (@queued)
          (is (= :inner *held*) "the caller's own frame is restored"))
        (is (= :outer *held*))))))

(deftest a-future-run-inline-keeps-the-callers-bindings
  ;; an executor (or a test) may run the task on the calling thread
  (with-redefs [clojure.core/future-call (fn [f] (doto (promise) (deliver (f))))]
    (binding [*held* :outer]
      (is (= :outer @(threads/future *held*)))
      (is (= :outer *held*) "the caller's frame is untouched"))))
