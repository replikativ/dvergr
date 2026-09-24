(ns dvergr.jobs-test
  "A job is a durable Run of its room: its status is the Run's, cancelling it
   settles the Run, and the Runs its work starts are its children."
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.agent.run :as run]
            [dvergr.discourse :as d]
            [dvergr.jobs :as jobs]
            [dvergr.room.registry :as rreg]
            [dvergr.room.store.memory :as memory]
            [org.replikativ.spindel.core :as sp]
            [org.replikativ.spindel.engine.core :as ec]))

(defmacro ^:private with-room [[sym id] & body]
  `(let [~sym (d/make-room {:id ~id :store (memory/make)})]
     (try
       (binding [ec/*execution-context* (:ctx ~sym)]
         (rreg/register! ~sym)
         ~@body)
       (finally
         (try (binding [ec/*execution-context* (:ctx ~sym)] (rreg/unregister! (:id ~sym)))
              (catch Throwable _# nil))
         (d/close-room! ~sym)))))

(defn- blocking-spin
  "A Spin that yields `(f)` computed on another thread, or throws what it threw."
  [room f]
  (let [ctx (:ctx room)
        d (binding [ec/*execution-context* ctx] (sp/deferred))]
    (future
      (let [v (try (f) (catch Throwable t t))]
        (binding [ec/*execution-context* ctx] (sp/deliver! d v))))
    (binding [ec/*execution-context* ctx]
      (sp/spin (let [v (sp/await d)]
                 (if (instance? Throwable v) (throw v) v))))))

(deftest a-job-is-a-run-of-its-room
  (with-room [room :jobs-run]
    (let [job (jobs/start! room {:kind :workflow}
                           (fn [_] (blocking-spin room #(do (Thread/sleep 200) 42))))]
      (is (= :running (:status job)))
      (is (= :running (:status (jobs/status (:id job)))) "no wait: answers at once")
      (let [done (jobs/status (:id job) 5000)]
        (is (= :completed (:status done)))
        (is (inst? (:finished-at done))))
      (is (= :completed (:run/status (binding [ec/*execution-context* (:ctx room)]
                                       (run/run room (:id job)))))
          "the status is the durable Run's")
      (is (some #(= (:id job) (:id %)) (jobs/jobs room))))))

(deftest a-failing-job-fails-its-run
  (with-room [room :jobs-fail]
    (let [job (jobs/start! room {:kind :workflow}
                           (fn [_] (blocking-spin room #(throw (ex-info "no budget left" {})))))
          done (jobs/status (:id job) 5000)]
      (is (= :failed (:status done)))
      (is (re-find #"no budget left" (:error done))))))

(deftest cancelling-a-job-settles-its-run-cancelled
  (with-room [room :jobs-cancel]
    (let [release (promise)
          job (jobs/start! room {:kind :workflow}
                           (fn [_] (blocking-spin room #(deref release 5000 :late))))]
      (is (= :cancelled (:status (jobs/cancel! (:id job)))))
      (deliver release true)
      (Thread/sleep 300)
      (is (= :cancelled (:status (jobs/status (:id job)))) "finishing later does not revive it")
      (is (= :cancelled (:status (jobs/cancel! (:id job)))) "cancelling again changes nothing")
      (is (nil? (jobs/status (random-uuid))))
      (is (nil? (jobs/cancel! (random-uuid)))))))

(deftest the-runs-a-job-starts-are-its-children
  (with-room [room :jobs-tree]
    (let [child (promise)
          job (jobs/start! room {:kind :experiment}
                           (fn [job-id]
                             (binding [ec/*execution-context* (:ctx room)]
                               (let [c (run/start! room :worker (random-uuid) nil
                                                   {:kind :agent-task :parent job-id})]
                                 (deliver child c)
                                 (sp/spin (run/finish! (:run/id c) :completed) :done)))))]
      (is (= :completed (:status (jobs/status (:id job) 5000))))
      (let [tree (binding [ec/*execution-context* (:ctx room)]
                   (run/runs room {:root-run-id (:id job)}))]
        (is (= #{(:id job) (:run/id @child)} (set (map :run/id tree))))
        (is (= (:id job) (:run/parent (first (filter #(= (:run/id @child) (:run/id %)) tree)))))))))

(deftest waiting-is-capped
  (with-room [room :jobs-wait]
    (let [job (jobs/start! room {:kind :workflow}
                           (fn [_] (blocking-spin room #(do (Thread/sleep 60000) :never))))
          t0 (System/currentTimeMillis)]
      (with-redefs [jobs/max-wait-ms 300]
        (is (= :running (:status (jobs/status (:id job) 100000)))))
      (is (< (- (System/currentTimeMillis) t0) 5000))
      (jobs/cancel! (:id job)))))
