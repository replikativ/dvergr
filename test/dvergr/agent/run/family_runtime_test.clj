(ns dvergr.agent.run.family-runtime-test
  (:require [clojure.test :refer [deftest is]]
            [dvergr.agent.run :as run]
            [dvergr.jobs :as jobs]
            [dvergr.jobs-cleanup-test :as fixture]
            [dvergr.room.store :as store]
            [dvergr.runtime.threads :as threads]
            [org.replikativ.spindel.core :as sp]
            [org.replikativ.spindel.spin.sync :as sync]))

(deftest uninitialized-live-family-cannot-mint-new-cleanup-evidence
  (@#'fixture/with-room
   (fn [room]
     (let [id (:run/id (run/start! room :test (random-uuid) nil {:kind :workflow}))
           registry @#'run/active before (run/run room id)]
       (try
         (with-redefs-fn
           {#'run/active (atom (update @registry id dissoc :family))}
           (fn []
             (is (= :unknown (get-in (run/observe-settlement room id) [:family :return])))
             (is (thrown? clojure.lang.ExceptionInfo (run/acknowledge-job-cleanup! room id :core)))
             (is (thrown? clojure.lang.ExceptionInfo (run/finish! id :completed)))
             (is (= before (run/run room id)))))
         (finally (run/finish! id :completed)))))))

(deftest terminal-family-witness-lost-ack-retains-native-owner-until-exact-retry
  (doseq [committed? [false true]]
    (let [fail? (atom true)]
      (@#'fixture/with-room
       #(@#'fixture/fault-ack-store % :run/family-return-ack fail? committed?)
       (fn [room]
         (let [id (:run/id (run/start! room :test (random-uuid) nil {}))
               opts {:now (java.util.Date.)}]
           (try
             (is (thrown? clojure.lang.ExceptionInfo (run/finish! id :completed opts)))
             (is (= committed? (store/family-return-acknowledged? (run/run room id))))
             (is (some #(= id (:run/id %)) (run/active-runs)))
             (reset! fail? false)
             (run/finish! id :completed opts)
             (is (store/family-return-acknowledged? (run/run room id)))
             (is (not-any? #(= id (:run/id %)) (run/active-runs)))
             (finally
               (reset! fail? false)
               (when (some #(= id (:run/id %)) (run/active-runs))
                 (run/finish! id :completed opts))))))))))

(deftest historical-job-needs-every-independent-cleanup-witness
  (@#'fixture/with-room
   (fn [room]
     (let [job (jobs/start! room {:kind :workflow} (fn [_] (sp/spin :done))) id (:id job)]
       (is (:settled? (jobs/status id 5000)))
       (let [original (run/run room id)]
         (doseq [attribute store/native-run-ack-attributes]
           (with-redefs [run/run (fn [& _] (dissoc original attribute))]
             (is (false? (:settled? (jobs/observe-settlement room id)))))))))))

(deftest acknowledging-old-core-only-evidence-cannot-invent-family-return
  (@#'fixture/with-room
   (fn [room]
     (let [native run/acknowledge-job-cleanup! core (atom 0)
           job (with-redefs [run/acknowledge-job-cleanup!
                             (fn [original id stage]
                               (if (= :core stage)
                                 (let [value (assoc (run/run original id)
                                                    :run/job-core-cleanup-ack (store/job-cleanup-ack-id id :core))]
                                   (store/-store-run! (:store original) (store/conversation-id original) value)
                                   (throw (ex-info "Old core-only witness" {})))
                                 (native original id stage)))]
                 (let [started (jobs/start! room {:kind :workflow} (fn [_] (sp/spin :done))
                                            :before-finish (fn [& _] (swap! core inc)))]
                   (@#'fixture/held! (:id started))
                   started))
           id (:id job)]
       (try
         (let [expected (@#'fixture/held! id) before (run/run room id)]
           (is (= :core-ack (:stage expected)))
           (is (store/job-cleanup-acknowledged? before :core))
           (is (not (store/family-return-acknowledged? before)))
           (is (thrown? clojure.lang.ExceptionInfo (jobs/recover! id expected :acknowledge)))
           (is (= before (run/run room id)))
           (is (:settled? (jobs/recover! id (jobs/recovery-state id) :retry)))
           (is (= 1 @core))
           (is (store/family-return-acknowledged? (run/run room id))))
         (finally (@#'fixture/recover-held! id)))))))

(defn- live? [id] (some #(= id (:run/id %)) (run/active-runs)))

(deftest family-join-never-holds-a-parent-stripe-needed-by-its-child
  (@#'fixture/with-room
   (fn [room]
     (let [parent (random-uuid)
           child (first (filter #(= (mod (hash parent) 256) (mod (hash %) 256)) (repeatedly random-uuid)))
           joined (atom nil)]
       (run/start! room :test (random-uuid) nil {:id parent})
       (try
         (run/start! room :test (random-uuid) nil {:id child :parent parent})
         (reset! joined (threads/future (run/await-family! room parent) :joined))
         (is (= :waiting (deref @joined 20 :waiting)))
         (is (thrown? clojure.lang.ExceptionInfo (run/finish! parent :completed)))
         (is (thrown? clojure.lang.ExceptionInfo
                      (run/start! room :test (random-uuid) nil {:parent parent})))
         ;; This finishes on the same stripe while the parent HOST join waits.
         (run/finish! child :completed)
         (is (= :joined (deref @joined 5000 :timeout)))
         (run/finish! parent :completed)
         (is (not (live? parent)))
         (finally
           (when (live? child) (run/finish! child :cancelled))
           (when (live? parent) (run/await-family! room parent) (run/finish! parent :cancelled))
           (when @joined (deref @joined 5000 nil))))))))

(deftest child-release-before-callback-exit-and-publication-is-not-parent-return
  (@#'fixture/with-room
   (fn [room]
     (let [parent (:run/id (run/start! room :test (random-uuid) nil {}))
           publish (atom nil) marker (atom nil) joined (atom nil)]
       (try
         (run/with-owned-child!
           room parent
           (fn []
             (let [child (:run/id (run/start! room :test (random-uuid) nil {:parent parent}))]
               (reset! marker run/*child-admission*)
               (reset! publish (run/require-child-publication!))
               (run/finish! child :completed)
               (is (= :pending (get-in (run/observe-settlement room parent) [:family :return]))))))
         (run/seal-family! room parent)
         (binding [run/*child-admission* @marker]
           (is (thrown? clojure.lang.ExceptionInfo
                        (run/start! room :test (random-uuid) nil {:parent parent}))))
         (reset! joined (threads/future (run/await-family! room parent) :joined))
         (is (= :waiting (deref @joined 20 :waiting)))
         (@publish)
         (is (= :joined (deref @joined 5000 :timeout)))
         (run/finish! parent :completed)
         (finally
           (when @publish (@publish))
           (when (live? parent) (run/await-family! room parent) (run/finish! parent :cancelled))
           (when @joined (deref @joined 5000 nil))))))))

(deftest throwing-job-construction-retains-the-actually-admitted-child
  (@#'fixture/with-room
   (fn [room]
     (let [gate (sync/deferred) child (atom nil) cleanup (atom 0)
           parent (jobs/start! room {:kind :workflow} (fn [_] (sp/spin (sp/await gate))))
           parent-id (:id parent)]
       (try
         (is (thrown-with-msg?
              clojure.lang.ExceptionInfo #"Constructor failed"
              (jobs/start! room {:kind :workflow :parent parent-id}
                           (fn [id] (reset! child id) (throw (ex-info "Constructor failed" {})))
                           :before-finish (fn [& _]
                                            (when (= 1 (swap! cleanup inc))
                                              (throw (ex-info "Child cleanup held" {})))))))
         (let [expected (@#'fixture/held! @child)]
           (sp/deliver! gate :done)
           (is (false? (:settled? (jobs/status parent-id 20))))
           (is (live? parent-id))
           (is (= @child (get-in (run/observe-settlement room parent-id) [:family :leases 0 :child-id])))
           (is (:settled? (jobs/recover! @child expected :retry)))
           (is (:settled? (jobs/status parent-id 5000)))
           (is (not (live? parent-id))))
         (finally
           (sp/deliver! gate :done)
           (when @child (@#'fixture/recover-held! @child))
           (jobs/status parent-id 5000)))))))

(def ^:dynamic *native-task-binding* nil)

(deftest nested-jobs-do-not-use-the-agent-send-off-pool
  (@#'fixture/with-room
   (fn [room]
     (let [original clojure.lang.Agent/soloExecutor
           rejected (java.util.concurrent.Executors/newSingleThreadExecutor)
           gate (sync/deferred) parent (atom nil) child (atom nil)]
       (.shutdownNow rejected)
       (try
         (set-agent-send-off-executor! rejected)
         (reset! parent (jobs/start! room {:kind :workflow} (fn [_] (sp/spin (sp/await gate)))))
         (reset! child (jobs/start! room {:kind :workflow :parent (:id @parent)} (fn [_] (sp/spin :child))))
         (sp/deliver! gate :done)
         (is (:settled? (jobs/status (:id @child) 5000)))
         (is (:settled? (jobs/status (:id @parent) 5000)))
         (is (empty? (run/active-runs (:id room))))
         (finally
           (set-agent-send-off-executor! original)
           (sp/deliver! gate :done)
           (when @child (jobs/status (:id @child) 5000))
           (when @parent (jobs/status (:id @parent) 5000))
           (.shutdownNow rejected)))))))

(deftest native-task-binding-is-conveyed-and-restored-on-an-inline-executor
  (let [inline (proxy [java.util.concurrent.AbstractExecutorService] []
                 (execute [task] (.run ^Runnable task))
                 (shutdown []) (shutdownNow [] []) (isShutdown [] false)
                 (isTerminated [] false) (awaitTermination [_ _] false))]
    (with-redefs [threads/native-executor inline]
      (binding [*native-task-binding* :caller]
        (is (= :caller (.get ^java.util.concurrent.Future
                        (threads/native-future-call
                         #(do (assert (= :caller *native-task-binding*))
                              (set! *native-task-binding* :task)
                              :caller)))))
        (is (= :caller *native-task-binding*))))))
