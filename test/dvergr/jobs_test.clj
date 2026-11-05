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

(deftest explicit-room-await-preserves-work-and-requires-settlement
  (with-room [room :jobs-explicit-await]
    (let [gate (sp/deferred)
          job (jobs/start! room {:kind :experiment} (fn [_] (sp/spin (sp/await gate) :done)))
          id (:id job)
          deadline #(+ (System/nanoTime) (* 1000000 %))]
      (is (= :running (:status (jobs/status-in room id))))
      (is (= ::jobs/worker-required
             ((ns-resolve 'dvergr.agent.run 'call-with-run-lock)
              (:id room) id
              #(try (jobs/await-in! room id (deadline 100)) nil
                    (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))))
      (is (not (contains? (jobs/status-in room id) :room-value)))
      (is (= ::jobs/foreign-owner
             (try (jobs/await-in! (assoc room :replacement true) id (deadline 100)) nil
                  (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))
          "matching Room id does not replace the original live owner")
      (is (= ::jobs/await-deadline
             (try (jobs/await-in! room id (deadline 5)) nil
                  (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))
      (is (false? (boolean (run/cancel-requested? id))) "observation expiry never cancels work")
      (is (= :running (:status (jobs/status-in room id))))
      (is (= ::jobs/worker-required
             (binding [ec/*spin-id* :test-engine-call]
               (try (jobs/await-in! room id (deadline 100)) nil
                    (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))))
      (with-room [other :jobs-explicit-other]
        (is (= ::jobs/foreign-owner
               (try (jobs/await-in! other id (deadline 100)) nil
                    (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))))
      (sp/deliver! gate true)
      (is (= {:status :completed :settled? true}
             (select-keys (jobs/await-in! room id (deadline 5000)) [:status :settled?])))
      (is (:settled? (jobs/await-in! room id (deadline 5000))) "repeat observation uses durable witnesses")
      (is (= ::jobs/await-deadline
             (try (jobs/await-in! room id (dec (System/nanoTime))) nil
                  (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))
          "even a completed result cannot revive an expired owning execution")
      (is (nil? (jobs/status-in room (random-uuid)))))))

(deftest explicit-room-await-does-not-hide-held-adapter-cleanup
  (with-room [room :jobs-await-held]
    (let [fail? (atom true)
          job (jobs/start! room {:kind :experiment} (fn [_] (sp/spin :done))
                           :settled (fn [& _] (when @fail? (throw (ex-info "hold adapter" {})))))
          id (:id job)
          deadline #(+ (System/nanoTime) 5000000000)]
      (try
        (is (= ::jobs/cleanup-held
               (try (jobs/await-in! room id (deadline)) nil
                    (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))
        (is (false? (:settled? (jobs/status-in room id))))
        (is (some #(= id (:run/id %)) (run/active-runs (:id room))))
        (finally
          (reset! fail? false)
          (when-let [state (jobs/recovery-state id)] (jobs/recover! id state :retry))))
      (is (:settled? (jobs/await-in! room id (deadline)))))))

(deftest explicit-room-await-interruption-does-not-release-the-job
  (with-room [room :jobs-await-interruption]
    (let [gate (sp/deferred)
          job (jobs/start! room {:kind :experiment} (fn [_] (sp/spin (sp/await gate) :done)))
          entered (promise) observed (promise)
          worker (Thread. ^Runnable
                  (fn []
                    (deliver entered true)
                    (try
                      (jobs/await-in! room (:id job) (+ (System/nanoTime) 5000000000))
                      (deliver observed :unexpected-completion)
                      (catch InterruptedException _ (deliver observed :interrupted))
                      (catch Throwable t (deliver observed t)))))]
      (try
        (.start worker)
        (is (= true (deref entered 1000 ::timeout)))
        (.interrupt worker)
        (.join worker 1000)
        (is (= :interrupted (deref observed 1000 ::timeout)))
        (is (not (.isAlive worker)))
        (is (= :running (:status (jobs/status-in room (:id job)))))
        (is (false? (boolean (run/cancel-requested? (:id job)))))
        (finally
          (sp/deliver! gate true)
          (jobs/await-in! room (:id job) (+ (System/nanoTime) 5000000000)))))))

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

(deftest physical-finish-boundary-precedes-durable-terminality
  (with-room [room :jobs-physical-boundary]
    (let [entered (promise) release (promise)
          job (jobs/start! room {:kind :experiment}
                           (fn [_] (binding [ec/*execution-context* (:ctx room)] (sp/spin :done)))
                           :before-finish (fn [id status value]
                                            (deliver entered [id status value]) @release))]
      (try
        (is (= [(:id job) :completed :done] (deref entered 5000 ::timeout)))
        (is (= :running (:status (jobs/status (:id job)))))
        (is (false? (:settled? (jobs/status (:id job)))))
        (finally (deliver release true)))
      (is (= :completed (:status (jobs/status (:id job) 5000)))))))

(deftest physical-finish-boundary-also-runs-on-construction-failure
  (with-room [room :jobs-construction-boundary]
    (let [called (atom [])]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"construction"
                            (jobs/start! room {:kind :experiment}
                                         (fn [_] (throw (ex-info "construction" {})))
                                         :before-finish (fn [id status _] (swap! called conj [id status])))))
      (is (= 1 (count @called)))
      (is (= :failed (second (first @called))))
      (is (empty? (run/active-runs (:id room)))))))

(deftest waiting-is-capped
  (with-room [room :jobs-wait]
    (let [job (jobs/start! room {:kind :workflow}
                           (fn [_] (blocking-spin room #(do (Thread/sleep 60000) :never))))
          t0 (System/currentTimeMillis)]
      (with-redefs [jobs/max-wait-ms 300]
        (is (= :running (:status (jobs/status (:id job) 100000)))))
      (is (< (- (System/currentTimeMillis) t0) 5000))
      (jobs/cancel! (:id job)))))

(deftest a-waiter-is-released-once-the-job-has-settled
  ;; a host closes the job's stores once it is settled: `settled` (a
  ;; benchmark archiving its fixture room) must be done by then
  (with-room [room :jobs-settled]
    (let [release (promise)
          settled (atom nil)
          job (jobs/start! room {:kind :workflow}
                           (fn [_] (blocking-spin room (constantly 42)))
                           :settled (fn [status _] @release (reset! settled status)))]
      (testing "a short wait may see the Run over, but not settled"
        (let [st (loop [n 0]
                   (let [st (jobs/status (:id job) 100)]
                     (if (and (= :running (:status st)) (< n 100)) (recur (inc n)) st)))]
          (is (= :completed (:status st)))
          (is (false? (:settled? st)))))
      (deliver release true)
      (testing "a waiter returns once it has settled"
        (let [st (jobs/status (:id job) 5000)]
          (is (true? (:settled? st)))
          (is (= :completed @settled)))))))
