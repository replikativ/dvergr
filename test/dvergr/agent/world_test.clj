(ns dvergr.agent.world-test
  (:require [clojure.test :refer [deftest is]]
            [datahike.api :as db]
            [dvergr.agent.world :as world]
            [dvergr.catalog.workspace :as catalog]
            [dvergr.discourse :as d]
            [dvergr.room.store.memory :as memory]
            [dvergr.rooms.forks :as forks]
            [dvergr.substrate.geschichte :as workspace]
            [dvergr.system.rooms :as srooms]
            [geschichte.content :as content]
            [geschichte.repo :as repo]
            [muschel.fs :as fs]
            [org.replikativ.spindel.engine.core :as ec]))

(defn- with-parent [f]
  (let [parent (d/make-room {:id (keyword (str "workspace-settle-" (random-uuid))) :store (memory/make)})
        connection (binding [ec/*execution-context* (:ctx parent)]
                     (:conn (catalog/ensure-workspace! parent)))]
    (try
      (catalog/seed! parent {"/old.txt" "remove" "/keep.txt" "unchanged"})
      (f parent)
      (finally (d/close-room! parent) (db/release connection)))))

(defn- pending-files! [room]
  (binding [ec/*execution-context* (:ctx room)]
    (let [filesystem (workspace/filesystem)]
      (fs/mkdir filesystem "/out")
      (with-open [sink (fs/-open-sink filesystem "/out/run.bin" false)]
        (.write ^java.io.OutputStream sink (byte-array [0 -1 -128 10 42])))
      (fs/chmod filesystem "/out/run.bin" 0x81ED)
      (fs/delete filesystem "/old.txt"))))

(defn- tree [room]
  (binding [ec/*execution-context* (:ctx room)]
    (repo/worktree (:conn (workspace/current-workspace)))))

(deftest automatic-settlement-commits-binary-mode-and-deletion-after-quiescence
  (with-parent
    (fn [parent]
      (let [w (world/open! parent (random-uuid) :automatic)]
        (try
          (pending-files! (:work w))
          (is (= #{"old.txt" "keep.txt"} (set (keys (tree parent)))))
          (is (seq (forks/workspace-changes (:work w))))
          (is (= {:status :merged} (world/settle! w :completed)))
          (let [after (tree parent)]
            (is (= #{"out/run.bin" "keep.txt"} (set (keys after))))
            (is (= 0x81ED (get-in after ["out/run.bin" :mode])))
            (binding [ec/*execution-context* (:ctx parent)]
              (is (= [0 -1 -128 10 42]
                     (vec (content/read-by-id (:conn (workspace/current-workspace))
                                              (get-in after ["out/run.bin" :content])))))))
          (finally (when-not @(:settlement w) (d/discard (:work w)))))))))

(deftest dirty-parent-retains-fork-before-committing-any-of-its-work
  (with-parent
    (fn [parent]
      (let [w (world/open! parent (random-uuid) :automatic)]
        (try
          (pending-files! (:work w))
          (binding [ec/*execution-context* (:ctx parent)]
            (fs/write-string! (workspace/filesystem) "/dirty.txt" "parent change" false))
          (let [result (world/settle! w :completed)]
            (is (= :review (:status result)))
            (is (= :automatic-merge-failed (:reason result)))
            (is (= #{"old.txt" "keep.txt" "dirty.txt"} (set (keys (tree parent)))))
            (is (seq (forks/workspace-changes (:work w))))
            (is (contains? (tree (:work w)) "out/run.bin")))
          (finally (d/discard (:work w))))))))

(deftest permissions-are-rechecked-after-committing-before-substrate-merge
  (with-parent
    (fn [parent]
      (let [w (world/open! parent (random-uuid) :automatic)
            checks (atom [])]
        (try
          (pending-files! (:work w))
          (with-redefs [srooms/assert-merge-permitted!
                        (fn [_ _]
                          (swap! checks conj (boolean (seq (forks/workspace-changes (:work w)))))
                          (when (= 2 (count @checks)) (throw (ex-info "Grant revoked" {}))))]
            (is (= :review (:status (world/settle! w :completed)))))
          (is (= [true false] @checks) "the second check sees committed file changes")
          (is (= #{"old.txt" "keep.txt"} (set (keys (tree parent)))))
          (is (contains? (tree (:work w)) "out/run.bin"))
          (finally (d/discard (:work w))))))))

(deftest failed-and-cancelled-worlds-never-commit-or-merge-pending-files
  (doseq [status [:failed :cancelled]]
    (with-parent
      (fn [parent]
        (let [w (world/open! parent (random-uuid) :automatic)
              commits (atom 0)
              commit forks/commit-workspace!]
          (pending-files! (:work w))
          (with-redefs [forks/commit-workspace! (fn [& args] (swap! commits inc) (apply commit args))]
            (is (= {:status :discarded :reason status} (world/settle! w status))))
          (is (zero? @commits))
          (is (= #{"old.txt" "keep.txt"} (set (keys (tree parent))))))))))

(deftest absent-world-without-a-discard-receipt-is-not-successful-disposal
  (let [w (world/->RunWorld :absent nil {} :discard (atom nil))]
    (with-redefs [d/discard-outcome (constantly {:outcome :gone})]
      (is (= {:status :recovery-required :reason :discard-unacknowledged}
             (world/settle! w :completed))))))

(deftest deferred-disposal-needs-positive-native-and-integration-return
  (let [w (world/->RunWorld :absent nil {} :deferred (atom nil))]
    (with-redefs [d/discard-deferred (constantly nil) d/settlement (constantly nil)]
      (is (= {:status :recovery-required :reason :discard-unacknowledged}
             (world/settle! w :failed))))))
