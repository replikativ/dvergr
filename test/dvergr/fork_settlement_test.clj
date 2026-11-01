(ns dvergr.fork-settlement-test
  "A fork is settled once: the merge or discard that claims it first settles
   it, in both isolation modes; the other does nothing and its caller reports
   the winner. A claim is held until its settlement completes, or given back
   after a failed one recovered."
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.agent.world :as world]
            [dvergr.agent.run :as agent-run]
            [dvergr.clients.client :as client]
            [dvergr.discourse :as d]
            [dvergr.ops :as ops]
            [dvergr.room.registry :as rreg]
            [dvergr.rooms.forks :as forks]
            [dvergr.runtime.preparation :as preparation]
            [dvergr.sandbox.work :as sandbox-work]
            [org.replikativ.spindel.core :as sp]
            [org.replikativ.spindel.engine.core :as ec]))

(defn- paused-close
  "close-room-work! that waits for `go` once `entered` is delivered."
  [entered go]
  (let [close sandbox-work/close-room-work!
        first? (atom true)]
    (fn [& args]
      (when (compare-and-set! first? true false)
        (deliver entered true)
        @go)
      (apply close args))))

(deftest a-settlement-in-progress-holds-the-fork
  (doseq [isolation [:ctx :none]]
    (testing (str "isolation " isolation)
      (let [parent (d/room (keyword (str "settle-held-" (name isolation))))
            fork (d/fork-room parent {:isolation isolation})
            entered (promise)
            go (promise)]
        (try
          (with-redefs [sandbox-work/close-room-work! (paused-close entered go)]
            (let [merging (future (d/merge-room parent fork))]
              (is (true? (deref entered 30000 ::timeout)))
              (testing "a discard while the merge holds the fork does nothing"
                (is (= fork (d/discard fork)))
                (is (nil? (d/settlement fork)))
                (is (= {:op :merged :parent (:id parent)} (d/settlement-claim fork)))
                (is (= {:ok? false :error (str "fork " (:id fork) " is being merged")}
                       (binding [ec/*execution-context* (:ctx parent)] (forks/discard! fork)))))
              (deliver go true)
              (is (= parent (deref merging 30000 ::timeout)))))
          (is (= {:op :merged :parent (:id parent)} (d/settlement fork)))
          (testing "and after it completed"
            (is (= {:ok? false :error (str "fork " (:id fork) " was merged")}
                   (binding [ec/*execution-context* (:ctx parent)] (forks/discard! fork)))))
          (finally
            (deliver go true)
            (d/close-room! parent)))))))

(deftest a-failed-settlement-gives-its-claim-back
  (testing "a merge that failed after claiming the fork, once it recovered"
    (let [parent (d/room :settle-failed)
          fork (d/fork-room parent {:isolation :ctx})
          close sandbox-work/close-room-work!
          failed? (atom false)]
      (try
        (with-redefs [sandbox-work/close-room-work!
                      (fn [& args]
                        (if (compare-and-set! failed? false true)
                          (throw (ex-info "work did not close" {}))
                          (apply close args)))]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"work did not close"
                                (d/merge-room parent fork))))
        (is (nil? (d/settlement-claim fork)))
        (is (= fork (d/discard fork)) "the discard can settle it")
        (is (= {:op :discarded} (d/settlement fork)))
        (finally (d/close-room! parent)))))
  (testing "a merge whose listeners did not drain"
    (let [parent (d/room :settle-drain-failed)
          fork (d/fork-room parent {:isolation :ctx})
          drain @#'d/drain-room-listeners!
          failed? (atom false)]
      (try
        (with-redefs [d/drain-room-listeners!
                      (fn [& args]
                        (if (compare-and-set! failed? false true)
                          (throw (ex-info "listeners did not drain" {}))
                          (apply drain args)))]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"did not drain"
                                (d/merge-room parent fork)))
          (is (nil? (d/settlement-claim fork)))
          (is (= fork (d/discard fork)))
          (is (= {:op :discarded} (d/settlement fork))))
        (finally (d/close-room! parent))))))

(deftest a-merge-of-a-discarded-fork-is-refused
  (let [parent (d/room :settle-refused)
        fork (d/fork-room parent {:isolation :none})]
    (try
      (d/discard fork)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"was already discarded"
                            (d/merge-room parent fork)))
      (is (= {:op :discarded} (d/settlement fork)))
      (finally (d/close-room! parent)))))

(deftest a-run-world-reports-the-settlement-that-won
  (let [parent (d/room :settle-world)
        w (world/open! parent (random-uuid) :discard)]
    (try
      (d/merge-room parent (:work w))
      (is (= {:status :merged :reason :settled-elsewhere}
             (world/settle! w :completed))
          "its discard policy found the work merged")
      (finally (d/close-room! parent)))))

(deftest a-concurrent-retry-leaves-the-holding-attempts-claim
  (doseq [isolation [:ctx :none]]
    (testing (str "isolation " isolation)
      (let [parent (d/room (keyword (str "settle-retry-" (name isolation))))
            fork (d/fork-room parent {:isolation isolation})
            entered (promise)
            go (promise)]
        (try
          (with-redefs [sandbox-work/close-room-work! (paused-close entered go)]
            (let [merging (future (d/merge-room parent fork))]
              (is (true? (deref entered 30000 ::timeout)))
              (is (thrown-with-msg? clojure.lang.ExceptionInfo #"is being merged"
                                    (d/merge-room parent fork)))
              (is (= {:op :merged :parent (:id parent)} (d/settlement-claim fork))
                  "the first attempt still holds the fork")
              (deliver go true)
              (is (= parent (deref merging 30000 ::timeout)))))
          (is (= {:op :merged :parent (:id parent)} (d/settlement fork)))
          (finally
            (deliver go true)
            (d/close-room! parent)))))))

(deftest a-discard-that-lost-stays-lost-when-the-winner-fails
  ;; the discard's outcome is decided when it finds the merge holding the
  ;; fork. The merge then fails and gives the claim back before the discard
  ;; reports, which is when a winner read from the fork's state would find
  ;; none: the Run's status must not become :discarded
  (let [parent (d/room :settle-lost)
        fork (d/fork-room parent {:isolation :ctx})
        run-id (random-uuid)
        merge-entered (promise) merge-go (promise)
        decided (promise) report (promise)
        writes (atom [])
        close sandbox-work/close-room-work!
        discard-outcome d/discard-outcome
        first? (atom true)]
    (swap! (:meta fork) assoc :run-id run-id)
    (try
      (with-redefs [sandbox-work/close-room-work!
                    (fn [& args]
                      (if (compare-and-set! first? true false)
                        (do (deliver merge-entered true) @merge-go (throw (ex-info "work did not close" {})))
                        (apply close args)))
                    d/discard-outcome (fn [f]
                                        (let [outcome (discard-outcome f)]
                                          (deliver decided outcome)
                                          @report
                                          outcome))
                    agent-run/update-durable-settlement! (fn [& args] (swap! writes conj args))]
        (let [merging (future (try (d/merge-room parent fork) (catch Throwable t t)))
              _ (is (true? (deref merge-entered 30000 ::timeout)))
              discarding (future (binding [ec/*execution-context* (:ctx parent)] (forks/discard! fork)))]
          (is (= {:outcome :lost :settlement {:op :merged :parent (:id parent)} :in-progress? true}
                 (deref decided 30000 ::timeout)))
          (deliver merge-go true)
          (is (instance? Throwable (deref merging 30000 ::timeout)))
          (is (nil? (d/settlement-claim fork)) "the failed merge gave its claim back")
          (deliver report true)
          (is (= {:ok? false :error (str "fork " (:id fork) " is being merged")}
                 (deref discarding 30000 ::timeout)))
          (is (empty? @writes) "no :discarded status for a discard that did nothing")))
      (is (= {:outcome :discarded} (d/discard-outcome fork)) "a later discard settles it")
      (finally
        (deliver merge-go true)
        (deliver report true)
        (d/close-room! parent)))))

(deftest a-discard-between-a-merges-unregister-and-its-completion-lost
  ;; a settlement unregisters the fork before it records its completion; a
  ;; discard in between reads the claim, not "gone"
  (doseq [isolation [:ctx :none]]
    (testing (str "isolation " isolation)
      (let [parent (d/room (keyword (str "settle-window-" (name isolation))))
            fork (d/fork-room parent {:isolation isolation})
            entered (promise)
            go (promise)
            hooks @#'rreg/unregister-hooks]
        (rreg/add-unregister-hook! ::pause (fn [id] (when (= id (:id fork)) (deliver entered true) @go)))
        (try
          (let [merging (future (d/merge-room parent fork))]
            (is (true? (deref entered 30000 ::timeout)))
            (is (nil? (binding [ec/*execution-context* (:ctx parent)] (rreg/lookup (:id fork)))))
            (is (= {:outcome :lost :settlement {:op :merged :parent (:id parent)} :in-progress? true}
                   (d/discard-outcome fork)))
            (is (= {:ok? false :error (str "fork " (:id fork) " is being merged")}
                   (binding [ec/*execution-context* (:ctx parent)] (forks/discard! fork))))
            (deliver go true)
            (is (= parent (deref merging 30000 ::timeout))))
          (finally
            (swap! hooks dissoc ::pause)
            (deliver go true)
            (d/close-room! parent)))))))

(deftest a-merge-whose-substrate-settled-keeps-its-claim
  ;; the merge settled the substrate, then failed: its claim stays and no
  ;; discard may settle the fork; a retry of the same merge passes the claim
  ;; (the room's own recovery fence then governs it)
  (let [parent (d/room :settle-takeover)
        fork (d/fork-room parent {:isolation :ctx})
        leave-all @#'d/leave-all!
        failed? (atom false)]
    (try
      (with-redefs-fn {#'d/leave-all! (fn [& args]
                                        (if (compare-and-set! failed? false true)
                                          (throw (ex-info "participants did not leave" {}))
                                          (apply leave-all args)))}
        (fn []
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"did not leave" (d/merge-room parent fork)))
          (is (= {:op :merged :parent (:id parent)} (d/settlement-claim fork)) "the claim stays")
          (is (nil? (d/settlement fork)))
          (is (= :lost (:outcome (d/discard-outcome fork))) "a discard cannot take it")
          (let [retry (try (d/merge-room parent fork) (catch Throwable t t))]
            (is (= :dvergr.discourse/room-lifecycle-in-progress (:type (ex-data retry)))
                "the retry passes the ended attempt's claim and meets the recovery fence")
            (is (= {:op :merged :parent (:id parent)} (d/settlement-claim fork)) "the claim still stays")
            (is (= :lost (:outcome (d/discard-outcome fork)))))))
      (let [claim (d/settlement-claim fork)]
        (d/close-room! fork)
        (is (= claim (d/settlement-claim fork)) "explicit cleanup preserves the failed merge's claim")
        (is (nil? (d/settlement fork)) "cleanup does not pretend the semantic merge completed"))
      (finally
        (d/close-room! fork)
        (d/close-room! parent)))))

(deftest post-settlement-close-joins-cleanup-without-stealing-an-active-retry
  (let [parent (d/room :settle-close-recovery)
        fork (d/fork-room parent {:isolation :ctx})
        leave-all @#'d/leave-all!
        begin @#'d/begin-room-quiescence!
        retry-entered (promise)
        retry-go (promise)
        release? (atom false)
        releases (atom 0)
        failure-type #(try (%) nil
                           (catch clojure.lang.ExceptionInfo error (:type (ex-data error))))
        registered? #(binding [ec/*execution-context* (:ctx parent)]
                       (identical? % (rreg/lookup (:id %))))]
    (try
      (with-redefs-fn {#'d/leave-all! (fn [room]
                                        (if (identical? room fork)
                                          (throw (ex-info "post-settlement cleanup failed" {}))
                                          (leave-all room)))}
        #(is (thrown-with-msg? clojure.lang.ExceptionInfo #"post-settlement cleanup failed"
                               (d/merge-room parent fork))))
      (let [claim (d/settlement-claim fork)
            handle! d/fork-handle
            handle (d/fork-handle fork)]
        (doseq [authority [(assoc @(:authority handle) :token (random-uuid))
                           (assoc @(:authority handle) :status :partitioned)
                           (assoc @(:authority handle) :status :transferred)]]
          (with-redefs [d/fork-handle
                        (fn [room]
                          (if (identical? room fork)
                            (assoc handle :authority (atom authority))
                            (handle! room)))]
            (is (= ::d/room-lifecycle-in-progress (failure-type #(d/close-room! fork)))
                "stale or partitioned affine authority cannot be reclaimed")))
        (with-redefs-fn {#'d/begin-room-quiescence!
                         (fn [room operation]
                           (when (and (identical? room fork) (= :merge operation))
                             (deliver retry-entered true)
                             @retry-go)
                           (begin room operation))}
          (fn []
            (let [retry (future (failure-type #(d/merge-room parent fork)))]
              (is (= true (deref retry-entered 3000 ::timeout)))
              (is (= ::d/room-lifecycle-in-progress (failure-type #(d/close-room! fork)))
                  "an active retry retains its original settlement authority")
              (deliver retry-go true)
              (is (= ::d/room-lifecycle-in-progress (deref retry 3000 ::timeout))))))
        (preparation/retain-room-cleanup!
         fork {:agent {:resource :exact-post-settlement-owner}}
         (fn [_]
           (swap! releases inc)
           (when-not @release? (throw (ex-info "native cleanup has not acknowledged" {})))))
        (is (= ::preparation/drain-failed (failure-type #(d/close-room! fork))))
        (is (registered? fork) "failed physical cleanup retains the exact canonical child")
        (is (= claim (d/settlement-claim fork)))
        (is (nil? (d/settlement fork)))
        (is (= ::d/already-settled (failure-type #(d/merge-room parent fork)))
            "close takeover fences later settlement admission")
        (is (= ::d/context-still-owned (failure-type #(d/close-room! parent))))
        (is (registered? parent))
        (let [alive (promise)]
          (binding [ec/*execution-context* (:ctx parent)]
            (sp/spawn! (sp/spin (deliver alive :alive))))
          (is (= :alive (deref alive 3000 ::timeout))))
        (reset! release? true)
        (d/close-room! fork)
        (is (= 2 @releases) "only the retained failed cleanup is retried")
        (is (not (registered? fork)))
        (is (= claim (d/settlement-claim fork)))
        (is (nil? (d/settlement fork)))
        (d/close-room! parent))
      (finally
        (deliver retry-go true)
        (reset! release? true)
        (d/close-room! fork)
        (d/close-room! parent)))))

(deftest the-client-refuses-a-discard-that-lost
  (let [parent (d/room :settle-client)
        fork (d/fork-room parent {:isolation :ctx})
        entered (promise)
        go (promise)]
    (try
      (with-redefs-fn {#'sandbox-work/close-room-work! (paused-close entered go)
                       #'client/current-daemon! (constantly {:execution-ctx (:ctx parent)})}
        (fn []
          (let [merging (future (d/merge-room parent fork))]
            (is (true? (deref entered 30000 ::timeout)))
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"is being merged" (client/discard! fork)))
            (deliver go true)
            (is (= parent (deref merging 30000 ::timeout))))))
      (finally
        (deliver go true)
        (d/close-room! parent)))))

(deftest a-run-world-that-lost-to-a-discard-reports-a-discard
  (let [parent (d/room :settle-world-discarded)
        w (world/open! parent (random-uuid) :discard)]
    (try
      (with-redefs [d/discard-outcome (constantly {:outcome :lost :settlement {:op :discarded} :in-progress? false})]
        (is (= {:status :discarded :reason :settled-elsewhere} (world/settle! w :completed))))
      (finally
        (d/discard (:work w))
        (d/close-room! parent)))))

(deftest the-fork-ops-refuse-a-discard-that-lost
  (let [parent (d/room :settle-ops)
        fork (d/fork-room parent {:isolation :ctx})
        entered (promise)
        go (promise)]
    (try
      (with-redefs [sandbox-work/close-room-work! (paused-close entered go)]
        (let [merging (future (d/merge-room parent fork))]
          (is (true? (deref entered 30000 ::timeout)))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"refused: fork .* is being merged"
                                (ops/invoke {:execution-ctx (:ctx parent)} dvergr.principal/local-principal :room/discard
                                            {:room (subs (str (:id fork)) 1)})))
          (deliver go true)
          (is (= parent (deref merging 30000 ::timeout)))))
      (finally
        (deliver go true)
        (d/close-room! parent)))))

(deftest a-run-world-settles-once-the-competing-settlement-ended
  (testing "the competitor completes: the world reports its merge"
    (let [parent (d/room :settle-world-won)
          w (world/open! parent (random-uuid) :discard)
          entered (promise)
          go (promise)]
      (try
        (with-redefs [sandbox-work/close-room-work! (paused-close entered go)]
          (let [merging (future (d/merge-room parent (:work w)))]
            (is (true? (deref entered 30000 ::timeout)))
            (is (= {:status :review :reason :settlement-in-progress} (world/settle! w :completed)))
            (deliver go true)
            (deref merging 30000 ::timeout)))
        (is (= {:status :merged :reason :settled-elsewhere} (world/settle! w :completed))
            "the provisional answer was not kept")
        (finally
          (deliver go true)
          (d/close-room! parent)))))
  (testing "the competitor fails: the world's own settlement runs"
    (let [parent (d/room :settle-world-retry)
          w (world/open! parent (random-uuid) :discard)
          entered (promise)
          go (promise)
          close sandbox-work/close-room-work!
          first? (atom true)]
      (try
        (with-redefs [sandbox-work/close-room-work!
                      (fn [& args]
                        (if (compare-and-set! first? true false)
                          (do (deliver entered true) @go (throw (ex-info "work did not close" {})))
                          (apply close args)))]
          (let [merging (future (try (d/merge-room parent (:work w)) (catch Throwable t t)))]
            (is (true? (deref entered 30000 ::timeout)))
            (is (= {:status :review :reason :settlement-in-progress} (world/settle! w :completed)))
            (deliver go true)
            (deref merging 30000 ::timeout))
          (is (= {:status :discarded :reason :policy} (world/settle! w :completed))))
        (is (= {:op :discarded} (d/settlement (:work w))))
        (finally
          (deliver go true)
          (d/close-room! parent))))))

(deftest a-fork-of-a-fork-being-settled-is-open
  (let [parent (d/room :settle-nested)
        fork (d/fork-room parent {:isolation :ctx})
        entered (promise)
        go (promise)
        drain @#'d/drain-room-listeners!
        first? (atom true)]
    (try
      (with-redefs-fn {#'d/drain-room-listeners!
                       (fn [& args]
                         (when (compare-and-set! first? true false)
                           (deliver entered true)
                           @go)
                         (apply drain args))}
        (fn []
          (let [merging (future (d/merge-room parent fork))]
            (is (true? (deref entered 30000 ::timeout)))
            (is (= {:op :merged :parent (:id parent)} (d/settlement-claim fork)) "the merge holds its fork")
            (let [child (binding [ec/*execution-context* (:ctx fork)]
                          (d/fork-room fork {:isolation :none}))]
              (is (nil? (d/settlement-claim child)) "the child is not settled with it")
              (is (= {:outcome :discarded} (d/discard-outcome child))))
            (deliver go true)
            (is (= parent (deref merging 30000 ::timeout))))))
      (finally
        (deliver go true)
        (d/close-room! parent)))))

(deftest a-discard-that-lost-to-a-plain-discard-writes-the-runs-status
  ;; the contender finds a plain d/discard holding the fork, which completes
  ;; while the contender reads the winner: nobody else writes the Run's status
  (let [parent (d/room :settle-plain-winner)
        fork (d/fork-room parent {:isolation :ctx})
        run-id (random-uuid)
        writes (atom [])
        drain-entered (promise) drain-go (promise)
        reading (promise) read-go (promise)
        contender (atom nil)
        drain @#'d/drain-room-listeners!
        settlement d/settlement
        first? (atom true)]
    (swap! (:meta fork) assoc :run-id run-id)
    (try
      (with-redefs-fn {#'d/drain-room-listeners!
                       (fn [& args]
                         (when (compare-and-set! first? true false)
                           (deliver drain-entered true)
                           @drain-go)
                         (apply drain args))
                       #'d/settlement
                       (fn [f]
                         (when (identical? (Thread/currentThread) @contender)
                           (deliver reading true)
                           @read-go)
                         (settlement f))
                       #'agent-run/update-durable-settlement! (fn [& args] (swap! writes conj (vec (drop 1 args))))}
        (fn []
          (let [winner (future (d/discard fork))
                _ (is (true? (deref drain-entered 30000 ::timeout)))
                result (future (reset! contender (Thread/currentThread))
                               (binding [ec/*execution-context* (:ctx parent)] (forks/discard! fork)))]
            (is (true? (deref reading 30000 ::timeout)))
            (deliver drain-go true)
            (is (= fork (deref winner 30000 ::timeout)))
            (deliver read-go true)
            (is (= {:ok? true} (deref result 30000 ::timeout)))
            (is (= [[run-id :discarded :review-rejected]] @writes)))))
      (finally
        (deliver drain-go true)
        (deliver read-go true)
        (d/close-room! parent)))))

(deftest missing-fork-without-discard-ack-cannot-create-a-discarded-projection
  (with-redefs-fn
    {#'forks/require-settleable! (fn [& _] nil)
     #'d/discard-outcome (constantly {:outcome :gone})
     #'rreg/lookup (constantly nil)}
    (fn []
      (is (= {:ok? false :error "fork has no completed discard acknowledgement"}
             (forks/discard! {:id :unacknowledged :meta (atom {})}))))))
