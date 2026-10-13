(ns dvergr.fork-settlement-test
  "A fork is settled once: the merge or discard that claims it first settles
   it, in both isolation modes; the other does nothing and its caller reports
   the winner. A claim is held until its settlement completes, or given back
   after a failed one recovered."
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.agent.world :as world]
            [dvergr.discourse :as d]
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
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"already settled: discarded"
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
