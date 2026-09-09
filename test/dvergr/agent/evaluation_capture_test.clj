(ns dvergr.agent.evaluation-capture-test
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.agent.environment :as environment]
            [dvergr.agent.evaluation :as evaluation]
            [dvergr.agent.program :as program]
            [dvergr.agent.roster :as roster]
            [dvergr.agent.run :as run]
            [dvergr.discourse :as d]
            [dvergr.room.registry :as registry]
            [dvergr.room.store :as store]
            [dvergr.room.store.memory :as memory]
            [org.replikativ.spindel.core :as sp]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.engine.protocols :as rtp]
            [org.replikativ.spindel.spin.combinators :as comb]
            [org.replikativ.spindel.spin.sync :as sync]))

(def team
  (roster/make-agent (roster/make-roster)
                     {:id :candidate :program {:kind :echo}}))

(defn- evaluator [capture]
  (evaluation/make-evaluator
   {:id :test/capture :capture capture
    :observe (fn [{:keys [default execution/evidence result]}]
               (assoc default :captured evidence
                      :completed? (= :completed (:run/status result))))
    :verify (fn [_ evidence]
              {:checks {:completed? (:completed? evidence)}
               :reward (if (:completed? evidence) 1.0 0.0)})}))

(defn- definition [opts]
  (environment/make-environment
   (merge {:id :test/capture :task :work
           :verifier {:id :test/capture :version 1}
           :world {:isolation :ctx :settlement :discard}
           :limits {:timeout-ms 5000 :cancel-timeout-ms 5000}} opts)))

(defn- launch! [room computation]
  (let [done (promise)]
    (binding [ec/*execution-context* (:ctx room)]
      (sync/spawn! computation {:on-success #(deliver done {:ok %})
                                :on-error #(deliver done {:error %})}))
    done))

(defn- result! [done]
  (let [result (deref done 15000 ::timeout)]
    (when (= ::timeout result) (throw (ex-info "Test timed out" {})))
    (if-let [error (:error result)] (throw error) (:ok result))))

(deftest capture-preserves-failed-and-cancelled-work-without-retaining-worlds
  (doseq [status [:completed :failed :cancelled]]
    (testing (name status)
      (let [room (d/make-room {:id (keyword (str "capture-" (name status)))
                               :store (memory/make)})
            wrote (promise)
            captured (atom [])
            check (evaluator
                   (fn [{:keys [world/room run-id]}]
                     (let [value (rtp/get-state (:ctx room) [:test :source])]
                       (swap! captured conj run-id)
                       {:source value :run-id run-id})))
            original @#'program/execute-program]
        (try
          (with-redefs-fn
            {#'program/execute-program
             (fn [& args]
               (sp/spin
                ;; The orchestration Spin lives in the control context. Tool
                ;; execution explicitly enters the work context, as here.
                (binding [ec/*execution-context* (:ctx (second args))]
                  (ec/swap-state! [:test :source] (constantly "(def answer 42)")))
                (deliver wrote true)
                (case status
                  :failed (throw (ex-info "Candidate failed after edit" {}))
                  :cancelled (sp/await (comb/sleep 60000))
                  :completed (sp/await (apply original args)))))}
            (fn []
              (let [done (binding [ec/*execution-context* (:ctx room)]
                           (launch! room (evaluation/evaluate room team :candidate
                                                              (definition {}) check)))]
                (is (= true (deref wrote 5000 ::timeout)))
                (when (= :cancelled status)
                  (is (run/cancel-room-run! (:id room)
                                            (:run/id (first (run/active-runs (:id room)))))))
                (let [result (result! done)
                      id (:run/id result)
                      evidence {:source "(def answer 42)" :run-id id}]
                  (is (= status (get-in result [:attempt-receipt :attempt/status])))
                  (is (= evidence (get-in result [:evidence :captured])))
                  (is (= evidence (get-in result [:attempt :attempt/evidence :captured])))
                  (is (= evidence
                         (-> (store/-list-attempts (:store room) (:id room) {})
                             first :attempt/evidence :captured)))
                  (is (= [id] @captured))
                  (is (= :discarded (get-in result [:run/result :run/settlement-status])))
                  (binding [ec/*execution-context* (:ctx room)]
                    (is (nil? (registry/lookup (get-in result [:run/result :run/world])))))
                  (is (nil? (rtp/get-state (:ctx room) [:test :source])))
                  (is (empty? (run/active-runs (:id room))))))))
          (finally
            (evaluation/await-cleanups! room)
            (d/close-room! room)))))))

(deftest capture-runs-after-resource-cleanup-in-the-work-context
  (let [room (d/make-room {:id :capture-cleanup :store (memory/make)})
        setup (evaluation/make-world-setup
               {:id :test/setup
                :prepare (fn [{:keys [register-cleanup!]}]
                           (register-cleanup!
                            #(ec/swap-state! [:test :cleaned] (constantly true)))
                           nil)})
        check (evaluator
               (fn [{:keys [world/room]}]
                 {:cleaned? (ec/get-state [:test :cleaned])
                  :work-context? (identical? (:ctx room) (ec/current-execution-context))}))]
    (try
      (binding [ec/*execution-context* (:ctx room)]
        (let [result (result!
                      (launch! room
                               (evaluation/evaluate
                                room team :candidate
                                (definition {:world {:isolation :ctx :settlement :discard
                                                     :setup (evaluation/world-setup-ref setup)}})
                                check {:world-setup setup})))]
          (is (= {:cleaned? true :work-context? true}
                 (get-in result [:evidence :captured])))))
      (finally
        (evaluation/await-cleanups! room)
        (d/close-room! room)))))

(deftest capture-errors-prevent-certification-but-do-not-rewrite-the-run
  (doseq [capture [(fn [_] (throw (ex-info "Cannot read artifact" {})))
                   (fn [_] (atom :not-portable))]]
    (let [room (d/make-room {:id :capture-error :store (memory/make)})]
      (try
        (binding [ec/*execution-context* (:ctx room)]
          (let [done (launch! room (evaluation/evaluate room team :candidate
                                                        (definition {}) (evaluator capture)))
                error (:error (deref done 15000 nil))]
            (is (= ::evaluation/certification-failed (:type (ex-data error))))
            (is (= ::evaluation/capture-failed (:type (ex-data (ex-cause error)))))
            (let [[value] (run/runs room)]
              (is (= :completed (:run/status value)))
              (is (= :discarded (:run/settlement-status value)))
              (is (nil? (registry/lookup (:run/world value)))))
            (is (empty? (store/-list-attempts (:store room) (:id room) {})))))
        (finally
          (evaluation/await-cleanups! room)
          (d/close-room! room))))))

(deftest one-evaluator-captures-parallel-attempts-independently
  (let [room (d/make-room {:id :capture-parallel :store (memory/make)})
        check (evaluator (fn [{:keys [run-id]}] {:run-id run-id}))]
    (try
      (binding [ec/*execution-context* (:ctx room)]
        (let [results (result!
                       (launch! room
                                (comb/parallel
                                 (evaluation/evaluate room team :candidate (definition {}) check)
                                 (evaluation/evaluate room team :candidate (definition {}) check))))]
          (is (= 2 (count (set (map :run/id results)))))
          (doseq [result results]
            (is (= (:run/id result) (get-in result [:evidence :captured :run-id]))))))
      (finally
        (evaluation/await-cleanups! room)
        (d/close-room! room)))))
