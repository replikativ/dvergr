(ns dvergr.proposals-test
  "Tests for dvergr.proposals — propose/accept/reject lifecycle on top
   of dvergr.discourse fork-room + merge-room + discard.

   Each test uses an in-memory Datahike DB with the proposal schema
   installed, and a `scripted` Participant as the worker — no real LLM
   calls."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [datahike.api :as dh]
            [org.replikativ.spindel.core :as sp]
            [org.replikativ.spindel.engine.core :as ec]
            [dvergr.discourse :as d]
            [dvergr.proposals :as p]
            [dvergr.chat.schema :as schema]))

;; ============================================================================
;; Fixtures
;; ============================================================================

(def ^:dynamic *conn* nil)

(defn- mem-db-fixture [f]
  (let [cfg {:store {:backend :memory :id (random-uuid)}
             :keep-history? false
             :schema-flexibility :write}]
    (dh/create-database cfg)
    (let [conn (dh/connect cfg)]
      (schema/install-schema! conn)
      (binding [*conn* conn]
        (try (f)
             (finally
               (dh/release conn)
               (dh/delete-database cfg)))))))

(use-fixtures :each mem-db-fixture)

(defn- await-spin
  "Run spin-fn in the room's context, wait up to wait-ms.
   Default 10s — tests sometimes pile spins on the shared executor and
   the cumulative latency surprises a 3s budget under CI load."
  ([room spin-fn] (await-spin room spin-fn 10000))
  ([room spin-fn wait-ms]
   (let [pr (promise)]
     (binding [ec/*execution-context* (:ctx room)]
       (sp/spawn!
         (sp/spin (deliver pr (sp/await (spin-fn room))))))
     (deref pr wait-ms ::timeout))))

(defn- run-propose
  "Helper: spawn propose! and wait for the proposal map."
  [opts]
  (await-spin (:room opts) (fn [_] (p/propose! opts))))

;; ============================================================================
;; propose!
;; ============================================================================

(deftest propose-persists-pending-proposal
  (testing "Worker replies → status :pending → Datahike row + cached fork"
    (let [room   (d/room :t)
          worker (d/scripted :hire-target ["done"] (:ctx room))
          prop   (run-propose {:room room :worker worker :conn *conn*
                               :goal "do the thing"})]
      (is (= :pending (:proposal/status prop)))
      (is (= "done"   (:proposal/summary prop)))
      (is (= :hire-target (:proposal/agent-id prop)))
      ;; Persisted
      (let [from-db (p/get-proposal *conn* (:proposal/id prop))]
        (is (some? from-db))
        (is (= :pending (:proposal/status from-db))))
      ;; Live fork cached
      (is (some? (p/get-cached-handle (:proposal/id prop)))))))

(deftest propose-test-fn-failure-auto-discards
  (testing "test-fn :pass? false → :failed status, fork discarded immediately"
    (let [room   (d/room :t)
          worker (d/scripted :w ["done"] (:ctx room))
          prop   (run-propose {:room room :worker worker :conn *conn*
                               :goal "x"
                               :test-fn (fn []
                                          {:pass? false
                                           :output "test fail"})})]
      (is (= :failed (:proposal/status prop)))
      (is (re-find #"test fail" (:proposal/test-result prop)))
      ;; No live handle for a failed proposal
      (is (nil? (p/get-cached-handle (:proposal/id prop)))))))

(deftest propose-test-fn-success-keeps-pending
  (testing "test-fn :pass? true → status stays :pending"
    (let [room   (d/room :t)
          worker (d/scripted :w ["done"] (:ctx room))
          prop   (run-propose {:room room :worker worker :conn *conn*
                               :goal "x"
                               :test-fn (fn [] {:pass? true})})]
      (is (= :pending (:proposal/status prop)))
      (is (re-find #":pass\? true" (:proposal/test-result prop))))))

(deftest propose-on-propose-callback-fires
  (testing "on-propose callback is invoked exactly once after persist"
    (let [room   (d/room :t)
          worker (d/scripted :w ["done"] (:ctx room))
          calls  (atom [])
          _      (run-propose {:room room :worker worker :conn *conn*
                               :goal "x"
                               :on-propose (fn [pr] (swap! calls conj pr))})]
      (is (= 1 (count @calls)))
      (is (= :pending (:proposal/status (first @calls)))))))

;; ============================================================================
;; accept-proposal! / reject-proposal!
;; ============================================================================

(deftest accept-proposal-merges-fork
  (testing "Accept merges fork log into parent, status → :accepted"
    (let [room   (d/room :t)
          worker (d/scripted :w ["proposed change"] (:ctx room))
          prop   (run-propose {:room room :worker worker :conn *conn*
                               :goal "x"})
          parent-log-before (d/log room)]
      (is (zero? (count parent-log-before))
          "parent starts empty (worker only sees the fork)")
      (let [result (p/accept-proposal! *conn* (:proposal/id prop))]
        (is (= :accepted result))
        (is (pos? (count (d/log room)))
            "fork's messages flowed into the parent log after merge")
        (is (= :accepted (:proposal/status
                           (p/get-proposal *conn* (:proposal/id prop)))))
        ;; Cache cleared
        (is (nil? (p/get-cached-handle (:proposal/id prop))))))))

(deftest reject-proposal-discards-fork
  (testing "Reject discards the fork; parent log untouched"
    (let [room   (d/room :t)
          worker (d/scripted :w ["draft"] (:ctx room))
          prop   (run-propose {:room room :worker worker :conn *conn*
                               :goal "x"})]
      (is (zero? (count (d/log room))))
      (is (= :rejected (p/reject-proposal! *conn* (:proposal/id prop))))
      (is (zero? (count (d/log room)))
          "parent log stays empty — discard does not merge")
      (is (= :rejected (:proposal/status
                         (p/get-proposal *conn* (:proposal/id prop))))))))

(deftest accept-unknown-id-returns-error
  (testing "Accepting a non-existent proposal returns {:error :not-found}"
    (let [result (p/accept-proposal! *conn* (random-uuid))]
      (is (= :not-found (:error result))))))

(deftest accept-without-live-context-returns-error
  (testing "If the in-memory fork is gone, accept returns :no-live-context"
    (let [room   (d/room :t)
          worker (d/scripted :w ["draft"] (:ctx room))
          prop   (run-propose {:room room :worker worker :conn *conn*
                               :goal "x"})]
      ;; Simulate daemon restart by clearing the (private) cache atom.
      (reset! @(resolve 'dvergr.proposals/result-cache) {})
      (let [result (p/accept-proposal! *conn* (:proposal/id prop))]
        (is (= :no-live-context (:error result)))))))

;; ============================================================================
;; list-proposals / pending-count
;; ============================================================================

(deftest list-and-count-filters-by-status
  (testing "Status filter narrows the list; pending-count agrees"
    (let [room    (d/room :t)
          worker  (d/scripted :w ["a" "b" "c"] (:ctx room))
          p1      (run-propose {:room room :worker worker :conn *conn*
                                :goal "first"})
          p2      (run-propose {:room room :worker worker :conn *conn*
                                :goal "second"})
          p3      (run-propose {:room room :worker worker :conn *conn*
                                :goal "third"})
          _       (p/reject-proposal! *conn* (:proposal/id p2))]
      (is (= 3 (count (p/list-proposals *conn*))))
      (is (= 2 (count (p/list-proposals *conn* :status :pending))))
      (is (= 1 (count (p/list-proposals *conn* :status :rejected))))
      (is (= 2 (p/pending-count *conn*))))))
