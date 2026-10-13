(ns dvergr.fork-settlement-test
  "A fork is settled once: the merge or discard that claims it first settles
   it, in both isolation modes; the other does nothing and its caller reports
   the winner. A claim is held until its settlement completes, or given back
   after a failed one recovered."
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.agent.world :as world]
            [dvergr.discourse :as d]
            [dvergr.ops :as ops]
            [dvergr.rooms.forks :as forks]
            [dvergr.sandbox.work :as sandbox-work]
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
  ;; fork, not read afterwards: a merge that then fails and gives the claim
  ;; back does not turn it into a discard
  (let [parent (d/room :settle-lost)
        fork (d/fork-room parent {:isolation :ctx})
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
        (let [merging (future (try (d/merge-room parent fork) (catch Throwable t t)))]
          (is (true? (deref entered 30000 ::timeout)))
          (let [lost (binding [ec/*execution-context* (:ctx parent)] (forks/discard! fork))]
            (deliver go true)
            (is (instance? Throwable (deref merging 30000 ::timeout)))
            (is (= {:ok? false :error (str "fork " (:id fork) " is being merged")} lost))
            (is (nil? (d/settlement fork)) "nothing settled it")
            (is (nil? (d/settlement-claim fork)) "the failed merge gave its claim back"))))
      (is (= {:outcome :discarded} (d/discard-outcome fork)) "a later discard settles it")
      (finally
        (deliver go true)
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
                                (ops/invoke {:execution-ctx (:ctx parent)} :room/discard
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
