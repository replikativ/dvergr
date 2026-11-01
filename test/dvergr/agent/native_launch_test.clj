(ns dvergr.agent.native-launch-test
  (:require [clojure.test :refer [deftest is]]
            [dvergr.agent.program :as program]
            [dvergr.agent.roster :as roster]
            [dvergr.agent.run :as run]
            [dvergr.jobs-cleanup-test :as fixture]
            [org.replikativ.spindel.engine.core :as ec]))

(defn- team []
  (roster/make-agent (roster/make-roster) {:id :worker :program {:kind :echo}}))

(defn- observe-launch
  ([room id] (observe-launch room id :family))
  ([room id kind] (first (filter #(= kind (:kind %)) (:launches (run/observe-settlement room id))))))

(defn- recover-held! [room id]
  (dotimes [_ 10]
    (doseq [launch (:launches (run/observe-settlement room id))
            :when (#{:held-construction :held-submission :held-callback} (:phase launch))]
      (try (run/reconcile-settlement! room id launch) (catch Exception _ nil)))
    (Thread/sleep 5)))

(deftest family-construction-failure-retains-its-original-launch-and-recovery-revision
  (doseq [target-kind [:family :cleanup]]
    (@#'fixture/with-room
     (fn [room]
       (let [native @#'program/start-worker! remaining (atom 2) held (promise)
             supervisor (atom nil) hook (atom 0) watch-key (Object.) handle (atom nil)]
         (try
           (with-redefs-fn
             {#'program/start-worker!
              (fn [sup f & [kind launch]]
                (when (and (= target-kind kind) (pos? @remaining))
                  (reset! supervisor sup)
                  (add-watch (:state sup) watch-key
                             (fn [_ _ _ state]
                               (when (= :held-construction (get-in state [(if (= :family target-kind) :family-launch :cleanup-launch) :phase]))
                                 (deliver held true))))
                  (swap! remaining dec)
                  (throw (ex-info "Constructor refused before registration" {})))
                (native sup f (or kind :normal) launch))}
             (fn []
               (reset! handle
                       (program/hire-prepared-in! room room (team) :worker
                                                  {:task "done" :settlement :discard}
                                                  (fn [{register! :register-cleanup!}] (register! #(swap! hook inc)))))
               (is (= true (deref held 5000 false)))
               (let [id (program/run-id @handle) first-held (observe-launch room id target-kind)]
                 (is (= :held-construction (:phase first-held)))
                 (is (zero? @hook))
                 (binding [ec/*execution-context* nil] (run/reconcile-settlement! room id first-held))
                 (let [second-held (observe-launch room id target-kind)]
                   (is (= (:id first-held) (:id second-held)))
                   (is (not= (:token first-held) (:token second-held)))
                   (is (thrown? clojure.lang.ExceptionInfo (run/reconcile-settlement! room id first-held)))
                   (run/reconcile-settlement! room id second-held))
                 (is (= :completed (:run/status (deref @handle 5000 nil))))
                 (is (= 1 @hook)))))
           (finally
             (reset! remaining 0)
             (when @supervisor (remove-watch (:state @supervisor) watch-key))
             (when @handle (recover-held! room (program/run-id @handle)) (deref @handle 5000 nil)))))))))

(deftest a-failed-family-worker-is-not-positive-family-return
  (@#'fixture/with-room
   (fn [room]
     (let [native @#'program/start-worker! fail? (atom true) held (promise)
           supervisor (atom nil) hook (atom 0) watch-key (Object.) handle (atom nil)]
       (try
         (with-redefs-fn
           {#'program/start-worker!
            (fn [sup f & [kind launch]]
              (if (and (= :family kind) (compare-and-set! fail? true false))
                (do
                  (reset! supervisor sup)
                  (add-watch (:state sup) watch-key
                             (fn [_ _ _ state]
                               (when (= :held-callback (get-in state [:family-launch :phase]))
                                 (deliver held true))))
                  (native sup #(throw (ex-info "Join worker failed" {})) kind launch))
                (native sup f (or kind :normal) launch)))}
           (fn []
             (reset! handle
                     (program/hire-prepared-in! room room (team) :worker
                                                {:task "done" :settlement :discard}
                                                (fn [{register! :register-cleanup!}] (register! #(swap! hook inc)))))
             (is (= true (deref held 5000 false)))
             (let [id (program/run-id @handle)
                   worker (get-in @(:state @supervisor) [:family-launch :worker])]
               (.get ^java.util.concurrent.FutureTask (:task worker) 5 java.util.concurrent.TimeUnit/SECONDS)
               (is (= :held (:family-join-phase @(:state @supervisor))))
               (is (zero? @hook))
               (is (= :waiting (deref @handle 20 :waiting)))
               (run/reconcile-settlement! room id (observe-launch room id))
               (is (= :completed (:run/status (deref @handle 5000 nil))))
               (is (= 1 @hook)))))
         (finally
           (reset! fail? false)
           (when @supervisor (remove-watch (:state @supervisor) watch-key))
           (when @handle (recover-held! room (program/run-id @handle)) (deref @handle 5000 nil))))))))

(deftest uncertain-submission-reuses-the-exact-registered-task
  (doseq [accepted? [false true]]
    (@#'fixture/with-room
     (fn [room]
       (let [supervisor (@#'program/make-supervisor (:ctx room))
             calls (atom 0) submissions (atom []) queued (atom []) fail? (atom true)
             launch {:id (random-uuid) :worker-id (random-uuid) :kind :cleanup
                     :callback #(swap! calls inc) :phase :selected}
             executor (reify java.util.concurrent.Executor
                        (execute [_ task]
                          (swap! submissions conj task)
                          (if (compare-and-set! fail? true false)
                            (do (when accepted? (swap! queued conj task))
                                (throw (java.util.concurrent.RejectedExecutionException. "Uncertain submission")))
                            (swap! queued conj task))))]
         (swap! (:state supervisor) assoc :cleanup-launch launch :cleanup-phase :running)
         (with-redefs-fn
           {#'program/native-executor executor}
           (fn []
             (@#'program/launch-owned-worker! supervisor :cleanup-launch)
             (let [held (first (@#'program/native-launches supervisor))
                   task (get-in @(:state supervisor) [:cleanup-launch :worker :task])]
               (is (= :held-submission (:phase held)))
               (is (false? (.isCancelled ^java.util.concurrent.FutureTask task)))
               (is (zero? @calls))
               (@#'program/recover-native-launch! supervisor held)
               (is (= 2 (count @submissions)))
               (is (every? #(identical? task %) @submissions))
               (doseq [queued-task @queued] (.run ^Runnable queued-task))
               (is (= 1 @calls))
               (is (= :completed (get-in @(:state supervisor) [:cleanup-launch :phase])))
               (is (empty? (:workers @(:state supervisor))))
               (is (thrown? clojure.lang.ExceptionInfo
                            (@#'program/recover-native-launch! supervisor held)))))))))))
