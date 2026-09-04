(ns dvergr.agent.experiment-test
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.agent.evaluation :as evaluation]
            [dvergr.agent.experiment :as experiment]
            [dvergr.agent.roster :as roster]
            [dvergr.agent.run :as run]
            [dvergr.discourse :as d]
            [dvergr.room.registry :as registry]
            [dvergr.room.store :as store]
            [dvergr.room.store.memory :as memory]
            [org.replikativ.spindel.engine.core :as ec]))

(defn- environment [id task]
  ((requiring-resolve 'dvergr.agent.environment/make-environment)
   {:id id
    :task task
    :verifier {:id :test/exact :version 1 :basis "experiment-test:v1"}
    :limits {:timeout-ms 2000 :cancel-timeout-ms 1000}
    :world {:isolation :ctx :settlement :review}}))

(def exact-evaluator
  (evaluation/make-evaluator
   {:id :test/exact
    :version 1
    :basis "experiment-test:v1"
    :observe (fn [{:keys [default]}] default)
    :verify (fn [definition evidence]
              (let [ok? (= (:environment/task definition) (:result evidence))]
                {:checks {:exact? ok?}
                 :reward (if ok? 1.0 0.0)}))}))

(defn- fixture []
  (let [team (-> (roster/make-roster {:id :experiment/team})
                 (roster/make-agent {:id :alpha :program {:kind :echo}})
                 (roster/make-agent {:id :beta :program {:kind :echo}}))
        dataset (experiment/make-dataset
                 {:id :experiment/tasks
                  :environments [(environment :task/one {:value 1})
                                 (environment :task/two {:value 2})]})]
    {:team team
     :dataset dataset
     :definition
     (experiment/make-experiment
      {:id :experiment/paired
       :dataset dataset
       :candidates [(roster/agent team :alpha) (roster/agent team :beta)]
       :repetitions 2
       :parallelism 2})}))

(deftest definitions-are-canonical-portable-values
  (let [{:keys [team dataset definition]} (fixture)
        reordered (experiment/make-dataset
                   {:environments (:dataset/environments dataset)
                    :id :experiment/tasks})]
    (is (= dataset reordered))
    (is (= dataset (experiment/validate-dataset dataset)))
    (is (= definition (experiment/validate-experiment definition)))
    (is (uuid? (:dataset/content-id dataset)))
    (is (uuid? (:experiment/content-id definition)))
    (is (= #{:alpha :beta}
           (set (map :candidate/id (:experiment/candidates definition)))))
    (is (every? uuid?
                (map :candidate/agent-content-id
                     (:experiment/candidates definition))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"content ID"
                          (experiment/validate-dataset
                           (assoc dataset :dataset/version 2))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"content ID"
                          (experiment/validate-experiment
                           (assoc definition :experiment/repetitions 3))))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"duplicate"
         (experiment/make-dataset
          {:id :duplicate
           :environments [(first (:dataset/environments dataset))
                          (first (:dataset/environments dataset))]})))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"portable"
         (experiment/make-experiment
          {:id :non-portable :dataset dataset
           :candidates [(assoc (roster/agent team :alpha)
                               :agent/metadata {:live (atom 1)})]})))))

(deftest exact-candidates-and-evaluators-fail-before-run-admission
  (let [{:keys [team definition]} (fixture)
        room (d/make-room {:id :experiment-preflight :store (memory/make)})]
    (try
      (let [changed-team (roster/revise-agent team :alpha
                                              {:prompt "different definition"})]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"version"
                              (experiment/run room changed-team definition
                                              {(:ref exact-evaluator)
                                               exact-evaluator})))
        (is (empty? (run/active-runs (:id room)))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no exact host Evaluator"
                            (experiment/run room team definition {})))
      (let [wrong (evaluation/make-evaluator
                   {:id :test/wrong
                    :observe (constantly {})
                    :verify (fn [_ _] {:checks {} :reward 0.0})})]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo #"does not match"
             (experiment/run room team definition
                             {(:ref exact-evaluator) wrong}))))
      (is (empty? (run/active-runs (:id room))))
      (finally
        (d/close-room! room)))))

(deftest repeated-paired-experiment-composes-ordinary-certified-evaluations
  (let [{:keys [team definition]} (fixture)
        room (d/make-room {:id :experiment-run :store (memory/make)})
        evaluator-ref (:ref exact-evaluator)]
    (try
      (binding [ec/*execution-context* (:ctx room)]
        (let [spin (experiment/run room team definition
                                   {evaluator-ref exact-evaluator})]
          (is (empty? (run/active-runs (:id room)))
              "constructing the Experiment Spin admits no Runs")
          (let [{:keys [results attempts scorecard]} @spin]
            (try
              (is (= 8 (count results) (count attempts)))
              (is (= 8 (count (set (map :attempt/id attempts)))))
              (is (= 8 (count (:scorecard/entries scorecard))))
              (is (= [{:candidate/id :alpha :attempt-count 4
                       :passed-count 4 :reward-sum 4.0 :reward-mean 1.0}
                      {:candidate/id :beta :attempt-count 4
                       :passed-count 4 :reward-sum 4.0 :reward-mean 1.0}]
                     (:scorecard/summary scorecard)))
              (is (= scorecard (experiment/validate-scorecard scorecard)))
              (is (every? #(= % (store/-load-attempt
                                 (:store room) (:id room) (:attempt/id %)))
                          attempts))
              (is (empty? (run/active-runs (:id room))))
              (testing "entry and aggregate tampering are rejected"
                (is (thrown-with-msg?
                     clojure.lang.ExceptionInfo #"summary"
                     (experiment/validate-scorecard
                      (assoc-in scorecard [:scorecard/summary 0 :reward-mean]
                                0.5))))
                (is (thrown-with-msg?
                     clojure.lang.ExceptionInfo #"content ID"
                     (experiment/validate-scorecard
                      (assoc scorecard :scorecard/content-id (random-uuid))))))
              (finally
                (doseq [result results]
                  (when-let [fork (some-> result :run/result :run/world
                                          registry/lookup)]
                    (d/discard fork))))))))
      (finally
        (d/close-room! room)))))
