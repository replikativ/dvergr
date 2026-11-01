(ns dvergr.agent.run.admission-recovery-test
  (:require [clojure.test :refer [deftest is]]
            [dvergr.agent.program :as program]
            [dvergr.agent.roster :as roster]
            [dvergr.agent.run :as run]
            [dvergr.agent.world :as world]
            [dvergr.jobs-cleanup-test :as fixture]
            [dvergr.discourse :as d]
            [dvergr.room.registry :as registry]
            [org.replikativ.spindel.engine.core :as ec]
            [dvergr.room.store :as store]
            [dvergr.runtime.threads :as threads]
            [org.replikativ.spindel.yggdrasil :as ygg]))

(defn- team []
  (roster/make-agent (roster/make-roster) {:id :worker :program {:kind :echo}}))

(defn- obligation [room parent]
  (first (get-in (run/observe-settlement room parent) [:family :leases])))

(defn- recover! [room parent]
  (when-let [expected (obligation room parent)]
    (run/reconcile-settlement! room parent expected)))

(deftest known-preflight-refusal-does-not-retain-a-phantom-world
  (@#'fixture/with-room
   (fn [room]
     (let [parent (:run/id (run/start! room :test (random-uuid) nil {}))
           entered (atom 0)]
       (try
         (with-redefs [world/open! (fn [& _] (swap! entered inc) (throw (AssertionError. "Unexpected world")))]
           (is (thrown? clojure.lang.ExceptionInfo
                        (program/hire-prepared-in! room room (team) :worker
                                                   {:task :done :parent-run parent :settlement :invalid} nil))))
         (is (zero? @entered))
         (is (empty? (get-in (run/observe-settlement room parent) [:family :leases])))
         (run/seal-family! room parent)
         (with-redefs [world/open! (fn [& _] (swap! entered inc) (throw (AssertionError. "Unexpected world")))]
           (is (thrown? clojure.lang.ExceptionInfo
                        (program/hire-prepared-in! room room (team) :worker
                                                   {:task :done :parent-run parent :settlement :discard} nil))))
         (is (zero? @entered))
         (finally (run/finish! parent :completed)))))))

(deftest uncertain-start-is-fenced-before-original-world-disposal
  (doseq [committed? [false true]]
    (let [fail? (atom false)]
      (@#'fixture/with-room
       #(@#'fixture/fault-ack-store % :run/parent fail? committed?)
       (fn [room]
         (let [parent (:run/id (run/start! room :test (random-uuid) nil {}))
               native world/open! opened (atom nil)]
           (try
             (reset! fail? true)
             (with-redefs [world/open! (fn [& args] (let [value (apply native args)] (reset! opened value) value))]
               (is (thrown? clojure.lang.ExceptionInfo
                            (program/hire-prepared-in! room room (team) :worker
                                                       {:task :done :parent-run parent :settlement :discard} nil))))
             (let [first-held (obligation room parent) id (:child-id first-held)
                   disposition #(-> @opened :work :fork-handle deref ygg/fork-disposition :status)]
               (is (= :starting (:phase first-held)))
               (is (= :open (disposition)))
               (is (= [parent] (mapv :run/id (run/active-runs (:id room)))))
               ;; The same unavailable writer cannot establish the fence. In
               ;; particular, successful world cleanup is not substituted for it.
               (is (thrown? clojure.lang.ExceptionInfo (run/reconcile-settlement! room parent first-held)))
               (is (= :open (disposition)))
               (is (not= (:revision first-held) (:revision (obligation room parent))))
               (reset! fail? false)
               (is (thrown? clojure.lang.ExceptionInfo (run/reconcile-settlement! room parent first-held)))
               (recover! room parent)
               (is (= :discarded (disposition)))
               (is (nil? (obligation room parent)))
               (let [failed (run/run room id)]
                 (is (= :failed (:run/status failed)))
                 (is (= :admission-failed (:run/reason failed)))
                 (is (= :discarded (:run/settlement-status failed)))
                 (is (not (store/family-return-acknowledged? failed)))
                 (is (= [parent] (mapv :run/id (run/active-runs (:id room)))))))
             (finally
               (reset! fail? false)
               (recover! room parent)
               (run/await-family! room parent)
               (run/finish! parent :completed)))))))))

(deftest setup-failure-after-room-return-retains-positive-disposal-requirement
  (@#'fixture/with-room
   (fn [room]
     (let [parent (:run/id (run/start! room :test (random-uuid) nil {}))
           native ec/swap-state! fail? (atom true)]
       (try
         (with-redefs [ec/swap-state!
                       (fn [path f & args]
                         (if (and (= [:dvergr/run-id] path) (compare-and-set! fail? true false))
                           (throw (ex-info "Setup failed after Room return" {}))
                           (apply native path f args)))]
           (is (thrown? clojure.lang.ExceptionInfo
                        (program/hire-prepared-in! room room (team) :worker
                                                   {:task :done :parent-run parent :settlement :discard} nil))))
         (is (= :preparing (:phase (obligation room parent))))
         (doseq [outcome [:lost :gone]]
           (with-redefs [d/discard-outcome (fn [& _] {:outcome outcome})]
             (is (thrown? clojure.lang.ExceptionInfo (recover! room parent)))
             (is (some? (obligation room parent)))))
         (recover! room parent)
         (is (nil? (obligation room parent)))
         (is (= [parent] (mapv :run/id (run/active-runs (:id room)))))
         (finally
           (recover! room parent)
           (run/await-family! room parent)
           (run/finish! parent :completed)))))))

(deftest unpublished-room-constructor-retains-the-original-native-handle
  (@#'fixture/with-room
   (fn [room]
     (let [parent (:run/id (run/start! room :test (random-uuid) nil {}))
           native ygg/fork! handle (atom nil)]
       (try
         (with-redefs [ygg/fork! (fn [& args] (let [value (apply native args)] (reset! handle value) value))
                       registry/register-fork! (fn [& _] (throw (ex-info "Publication refused" {})))]
           (is (thrown? clojure.lang.ExceptionInfo
                        (program/hire-prepared-in! room room (team) :worker
                                                   {:task :done :parent-run parent :settlement :discard} nil))))
         (is (= :preparing (:phase (obligation room parent))))
         (is (= :open (:status (ygg/fork-disposition @handle))))
         (recover! room parent)
         (is (= :discarded (:status (ygg/fork-disposition @handle))))
         (is (nil? (obligation room parent)))
         (finally
           (recover! room parent)
           (run/await-family! room parent)
           (run/finish! parent :completed)))))))

(deftest concurrent-recovery-and-cancellation-cannot-release-a-live-cleanup
  (@#'fixture/with-room
   (fn [room]
     (let [parent (:run/id (run/start! room :test (random-uuid) nil {}))
           entered (promise) release (promise) recovery (atom nil)]
       (try
         (is (thrown? clojure.lang.ExceptionInfo
                      (run/call-with-child-admission!
                       room parent (random-uuid)
                       (fn [_]
                         (run/child-preparation-entered!)
                         (run/retain-child-preparation! #(do (deliver entered true) @release))
                         (throw (ex-info "Preparation interrupted" {}))))))
         (let [expected (obligation room parent)]
           (reset! recovery (threads/native-future (run/reconcile-settlement! room parent expected)))
           (is (= true (deref entered 5000 false)))
           (is (thrown? clojure.lang.ExceptionInfo (run/reconcile-settlement! room parent expected)))
           (run/cancel-run! parent)
           (is (true? (:recovery-running? (obligation room parent))))
           (is (some #(= parent (:run/id %)) (run/active-runs (:id room))))
           (deliver release true)
           (.get ^java.util.concurrent.Future @recovery 5 java.util.concurrent.TimeUnit/SECONDS)
           (is (nil? (obligation room parent))))
         (finally
           (deliver release true)
           (when @recovery (.get ^java.util.concurrent.Future @recovery 5 java.util.concurrent.TimeUnit/SECONDS))
           (run/await-family! room parent)
           (run/finish! parent :cancelled)))))))

(deftest lost-disposal-projection-ack-does-not-repeat-native-disposal
  (let [start-fail? (atom false) projection-fail? (atom false)]
    (@#'fixture/with-room
     #(-> %
          (@#'fixture/fault-ack-store :run/parent start-fail? false)
          (@#'fixture/fault-ack-store :run/settlement-reason projection-fail? true))
     (fn [room]
       (let [parent (:run/id (run/start! room :test (random-uuid) nil {}))
             dispose! d/discard-outcome disposals (atom 0)]
         (try
           (reset! start-fail? true)
           (is (thrown? clojure.lang.ExceptionInfo
                        (program/hire-prepared-in! room room (team) :worker
                                                   {:task :done :parent-run parent :settlement :discard} nil)))
           (reset! start-fail? false)
           (with-redefs [d/discard-outcome (fn [work] (swap! disposals inc) (dispose! work))]
             (reset! projection-fail? true)
             (is (thrown? clojure.lang.ExceptionInfo (recover! room parent)))
             (is (= 1 @disposals))
             (let [id (:child-id (obligation room parent)) before (run/run room id)]
               (is (= :discarded (:run/settlement-status before)))
               (reset! projection-fail? false)
               (recover! room parent)
               (is (= before (run/run room id))))
             (is (= 1 @disposals))
             (is (nil? (obligation room parent))))
           (finally
             (reset! start-fail? false)
             (reset! projection-fail? false)
             (recover! room parent)
             (run/await-family! room parent)
             (run/finish! parent :completed))))))))
