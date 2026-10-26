(ns dvergr.agent.checkpoint-prediction-lifetime-test
  (:require [clojure.test :refer [deftest is]]
            [datahike.api :as db]
            [dvergr.agent.checkpoint-admission-test :as fixture]
            [dvergr.agent.evaluation :as evaluation]
            [dvergr.agent.run :as run]
            [dvergr.agent.roster :as roster]
            [geschichte.query :as query]
            [org.replikativ.spindel.world.scope :as scope]
            [org.replikativ.spindel.yggdrasil :as ygg])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(defn- selected [conn]
  {"workspace" {:version 1 :kind :datahike :commit (db/commit-id @conn)
                :source {:store (get-in @conn [:config :store :id])
                         :repository (:geschichte.repo/id (query/repository @conn))}}})
(defn- error [f] (try (f) nil (catch Throwable error error)))
(defn- worker [f]
  (let [result (promise)
        thread (Thread. (fn []
                          (try (deliver result {:value (f) :interrupted? (.isInterrupted (Thread/currentThread))})
                               (catch Throwable error
                                 (deliver result {:error error :interrupted? (.isInterrupted (Thread/currentThread))})))))]
    (.setDaemon thread true)
    (.start thread)
    {:thread thread :result result}))
(defn- finished! [{:keys [thread result]}]
  (let [outcome (deref result 5000 ::timeout)]
    (is (not= ::timeout outcome))
    (.join ^Thread thread 5000)
    (is (not (.isAlive ^Thread thread)))
    outcome))

(deftest failed-computation-and-disposal-retain-one-recoverable-manager-owner
  (#'fixture/with-room-workspace
   (fn [room conn]
     (#'fixture/commit! conn "C1")
     (let [branches (db/branches conn) selected (selected conn)
           owner (atom {:connection conn})
           computation-error (ex-info "Prediction body failed" {:construction/owner owner}
                                      (ex-info "Nested owner" {:adapter/owner owner}))
           cleanup-error (ex-info "Injected cleanup preflight refusal" {:adapter/owner owner})
           failure (with-redefs [scope/discard! (fn [_] (fn [_ reject] (reject cleanup-error)))]
                     (error #(evaluation/with-checkpoint-world!
                               room room nil selected (fn [] (throw computation-error)))))
           token (:cleanup/token (ex-data failure))]
       (is (= ::evaluation/checkpoint-prediction-cleanup-required (:type (ex-data failure))))
       (let [entry (get-in @@#'evaluation/pending-tasks [[(:id room) (:incarnation room)] token])]
         (is (identical? computation-error (get-in entry [:native-errors :computation])))
         (is (identical? cleanup-error (get-in entry [:native-errors :cleanup]))))
       (is (roster/data-value? (ex-data failure)))
       (is (nil? (ex-cause failure)))
       (is (empty? (.getSuppressed failure)))
       (is (uuid? token))
       (is (not (contains? (ex-data failure) :cleanup/owner)) "No dereferenceable Scope escapes to SCI")
       (is (= (inc (count branches)) (count (db/branches conn))))
       (doseq [_ (range 2)]
         (let [observed (error #(evaluation/await-cleanups! room 100))]
           (is (= ::evaluation/cleanup-incomplete (:type (ex-data observed))))
           (is (roster/data-value? (ex-data observed)))
           (is (nil? (ex-cause observed)))))
       (with-redefs [scope/discard! (fn [_] (fn [_ reject] (reject cleanup-error)))]
         (let [retry (error #(evaluation/recover-native-cleanup! room token))]
           (is (= ::evaluation/native-cleanup-recovery-required (:type (ex-data retry))))
           (is (roster/data-value? (ex-data retry)))
           (is (nil? (ex-cause retry)))))
       (is (true? (evaluation/recover-native-cleanup! room token)))
       (is (= branches (db/branches conn)))
       (is (empty? (run/runs room)))
       (is (true? (evaluation/await-cleanups! room 100)))))))

(deftest interrupting-an-active-prediction-joins-resources-and-restores-interruption
  (#'fixture/with-room-workspace
   (fn [room conn]
     (#'fixture/commit! conn "C1")
     (let [branches (db/branches conn) entered (CountDownLatch. 1) release (CountDownLatch. 1)
           selected (selected conn)
           task (worker #(evaluation/with-checkpoint-world!
                           room room nil selected
                           (fn [] (.countDown entered) (.await release) :done)))]
       (try
         (is (.await entered 5000 TimeUnit/MILLISECONDS))
         (let [waiting (error #(evaluation/await-cleanups! room 10))
               token (get-in (ex-data waiting) [:failures 0 :token])]
           (is (= ::evaluation/cleanup-incomplete (:type (ex-data waiting))))
           (is (= ::evaluation/native-operation-active
                  (:type (ex-data (error #(evaluation/recover-native-cleanup! room token)))))))
         (.interrupt ^Thread (:thread task))
         (let [outcome (finished! task)]
           (is (= ::evaluation/checkpoint-prediction-failed (:type (ex-data (:error outcome)))))
           (is (= "java.lang.InterruptedException" (get-in (ex-data (:error outcome)) [:prediction-error :class])))
           (is (true? (:interrupted? outcome))))
         (is (= branches (db/branches conn)))
         (is (= ::evaluation/cleanup-incomplete (:type (ex-data (error #(evaluation/await-cleanups! room 100))))))
         (is (true? (evaluation/await-cleanups! room 100)))
         (finally (.countDown release) (.interrupt ^Thread (:thread task))))))))

(deftest an-interrupted-caller-keeps-a-late-fork-ack-owned-until-explicit-recovery
  (#'fixture/with-room-workspace
   (fn [room conn]
     (#'fixture/commit! conn "C1")
     (let [branches (db/branches conn) selected (selected conn) allocated (promise)
           deliver-ack (atom nil) computations (atom 0) original ygg/fork!]
       (with-redefs-fn
         {#'evaluation/native-cleanup-timeout-ms 30
          #'ygg/fork! (fn [options]
                        (let [operation (original options)]
                          (fn [resolve reject]
                            (operation (fn [handle]
                                         (reset! deliver-ack #(resolve handle))
                                         (deliver allocated true)) reject))))}
         (fn []
           (let [task (worker #(evaluation/with-checkpoint-world!
                                 room room nil selected (fn [] (swap! computations inc))))]
             (try
               (is (= true (deref allocated 5000 ::timeout)))
               (.interrupt ^Thread (:thread task))
               (let [outcome (finished! task) failure (:error outcome)
                     token (:cleanup/token (ex-data failure))]
                 (is (= ::evaluation/checkpoint-prediction-cleanup-required (:type (ex-data failure))))
                 (is (= "java.lang.InterruptedException" (get-in (ex-data failure) [:prediction-error :class])))
                 (is (true? (:interrupted? outcome)))
                 (is (= ::evaluation/native-operation-active
                        (:type (ex-data (error #(evaluation/recover-native-cleanup! room token))))))
                 (is (= ::evaluation/cleanup-incomplete
                        (:type (ex-data (error #(evaluation/await-cleanups! room 100))))))
                 (is (= (inc (count branches)) (count (db/branches conn))))
                 (@deliver-ack)
                 (is (zero? @computations) "A cancelled wait never starts the prediction body")
                 (is (true? (evaluation/recover-native-cleanup! room token)))
                 (is (= branches (db/branches conn)))
                 (is (true? (evaluation/await-cleanups! room 100))))
               (finally
                 (when-let [ack @deliver-ack] (ack))
                 (.interrupt ^Thread (:thread task)))))))))))

(deftest a-completed-gate-does-not-drop-held-ownership-without-settlement-ack
  (#'fixture/with-room-workspace
   (fn [room conn]
     (#'fixture/commit! conn "C1")
     (let [branches (db/branches conn) original scope/discard!
           failure (with-redefs [scope/discard!
                                 (fn [owned]
                                   (fn [_ reject]
                                     ((original owned)
                                      (fn [_] (reject (ex-info "Lost outer cleanup acknowledgement" {})))
                                      reject)))]
                     (error #(evaluation/with-checkpoint-world! room room nil (selected conn) (constantly :metadata))))
           token (:cleanup/token (ex-data failure))]
       (is (= ::evaluation/checkpoint-prediction-cleanup-required (:type (ex-data failure))))
       (is (= branches (db/branches conn)) "Native disposal finished, but the outer operation did not acknowledge it")
       (dotimes [_ 2]
         (is (= ::evaluation/cleanup-incomplete
                (:type (ex-data (error #(evaluation/await-cleanups! room 100)))))))
       (is (true? (evaluation/recover-native-cleanup! room token)))
       (is (true? (evaluation/await-cleanups! room 100)))))))

(deftest throwing-error-accessors-cannot-strand-the-native-operation-gate
  (#'fixture/with-room-workspace
   (fn [room conn]
     (#'fixture/commit! conn "C1")
     (let [branches (db/branches conn)
           hostile (proxy [RuntimeException] []
                     (getMessage [] (throw (IllegalStateException. "Accessor failure"))))
           failure (error #(evaluation/with-checkpoint-world! room room nil (selected conn) (fn [] (throw hostile))))
           token (:cleanup/token (ex-data failure))
           entry (get-in @@#'evaluation/pending-tasks [[(:id room) (:incarnation room)] token])]
       (is (= ::evaluation/checkpoint-prediction-failed (:type (ex-data failure))))
       (is (= {:class "java.lang.Throwable" :message "Native error details unavailable"}
              (:prediction-error (ex-data failure))))
       (is (identical? hostile (get-in entry [:native-errors :computation])))
       (is (= :settled @(:native-phase entry)))
       (is (realized? (:gate entry)))
       (is (= branches (db/branches conn)))
       (is (= ::evaluation/cleanup-incomplete
              (:type (ex-data (error #(evaluation/await-cleanups! room 100))))))
       (is (true? (evaluation/await-cleanups! room 100)))))))

(deftest an-inflight-discard-ack-cannot-be-replayed-by-recovery
  (#'fixture/with-room-workspace
   (fn [room conn]
     (#'fixture/commit! conn "C1")
     (let [branches (db/branches conn) entered (promise) ack (atom nil)
           calls (atom 0) original ygg/discard-fork!]
       (with-redefs-fn
         {#'evaluation/native-cleanup-timeout-ms 30
          #'ygg/discard-fork!
          (fn [handle options]
            (swap! calls inc)
            (let [operation (original handle options)]
              (fn [resolve reject]
                (operation (fn [value] (reset! ack #(resolve value)) (deliver entered true)) reject))))}
         (fn []
           (let [task (worker #(evaluation/with-checkpoint-world! room room nil (selected conn) (constantly :metadata)))]
             (try
               (is (= true (deref entered 5000 ::timeout)))
               (let [failure (:error (finished! task)) token (:cleanup/token (ex-data failure))]
                 (is (= ::evaluation/checkpoint-prediction-cleanup-required (:type (ex-data failure))))
                 (is (= ::evaluation/native-operation-active
                        (:type (ex-data (error #(evaluation/recover-native-cleanup! room token))))))
                 (is (= 1 @calls))
                 (@ack)
                 (is (true? (evaluation/recover-native-cleanup! room token)))
                 (is (= 1 @calls) "No duplicate native disposal after the late acknowledgement")
                 (is (= branches (db/branches conn)))
                 (is (true? (evaluation/await-cleanups! room 100))))
               (finally (when-let [resume @ack] (resume)))))))))))
