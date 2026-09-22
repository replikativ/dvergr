(ns dvergr.ops-evaluation-test
  "The evaluation read model in `dvergr.ops`: Runs, Attempts and Scorecards as
   the plain data every binding (MCP, web) renders — checked against a real
   certified experiment."
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.agent.environment :as environment-def]
            [dvergr.agent.evaluation :as evaluation]
            [dvergr.agent.experiment :as experiment]
            [dvergr.agent.roster :as roster]
            [dvergr.discourse :as d]
            [dvergr.ops :as ops]
            [dvergr.room.registry :as rreg]
            [dvergr.room.store.memory :as memory]
            [org.replikativ.spindel.engine.core :as ec]))

(def ^:private exact-evaluator
  (evaluation/make-evaluator
   {:id :ops-test/exact :version 1 :basis "ops-evaluation-test:v1"
    :observe (fn [{:keys [default]}] default)
    :verify (fn [definition evidence]
              (let [ok? (= (:environment/task definition) (:result evidence))]
                {:checks {:exact? ok?} :reward (if ok? 1.0 0.0)}))}))

(defn- environment [id task]
  (environment-def/make-environment
   {:id id :task task
    :verifier {:id :ops-test/exact :version 1 :basis "ops-evaluation-test:v1"}
    :limits {:timeout-ms 2000 :cancel-timeout-ms 1000}
    :world {:isolation :ctx :settlement :discard}}))

(deftest runs-attempts-and-scorecards-are-readable-through-the-ops-spec
  (let [team (-> (roster/make-roster {:id :ops-test/team})
                 (roster/make-agent {:id :alpha :program {:kind :echo}}))
        definition (experiment/make-experiment
                    {:id :ops-test/exp
                     :dataset (experiment/make-dataset
                               {:id :ops-test/tasks
                                :environments [(environment :ops-test/t1 {:value 1})
                                               (environment :ops-test/t2 {:value 2})]})
                     :candidates [(roster/agent team :alpha)]
                     :repetitions 1})
        room (d/make-room {:id :ops-evaluation-test :store (memory/make)})
        daemon {:execution-ctx (:ctx room)}]
    (try
      (binding [ec/*execution-context* (:ctx room)]
        (rreg/register! room)
        (let [{:keys [scorecard]} @(experiment/run room team definition
                                                   {(:ref exact-evaluator) exact-evaluator}
                                                   {:parallelism 1})]
          (is (some? scorecard))
          (testing "the spec classifies the read model as reads, named for every binding"
            (doseq [op [:run/list :run/detail :attempt/list :attempt/detail
                        :scorecard/list :scorecard/detail]]
              (is (= :read (:kind (ops/specification op))) (str op))
              (is (string? (ops/op->name op)))))
          (testing "attempts are leaderboard rows with a bill"
            (let [rows (ops/invoke daemon :attempt/list {:room "ops-evaluation-test"})]
              (is (= 2 (count rows)))
              (doseq [row rows]
                (is (= "alpha" (:agent row)))
                (is (= "completed" (:status row)))
                (is (= 1.0 (:reward row)))
                (is (= {:exact? true} (:checks row)))
                (is (= "trusted" (:verifier-trust row)))
                (is (map? (:spend row)))
                (is (number? (:dollars (:spend row))))
                (is (not (contains? row :evidence)) "list rows carry no evidence"))
              (let [one (ops/invoke daemon :attempt/detail {:room "ops-evaluation-test" :id (:id (first rows))})]
                (is (= (:id (first rows)) (:id one)))
                (is (contains? one :evidence) "detail carries the evidence"))
              (is (= 1 (count (ops/invoke daemon :attempt/list
                                          {:room "ops-evaluation-test" :environment "ops-test/t1"}))))))
          (testing "runs are listed and readable one by one"
            (let [runs (ops/invoke daemon :run/list {:room "ops-evaluation-test"})]
              (is (<= 2 (count runs)))
              (is (every? #(and (string? (:id %)) (string? (:status %))) runs))
              (is (= (:id (first runs))
                     (:id (ops/invoke daemon :run/detail {:room "ops-evaluation-test" :id (:id (first runs))}))))))
          (testing "a scorecard is a leaderboard"
            (let [[sc :as scs] (ops/invoke daemon :scorecard/list {:room "ops-evaluation-test"})]
              (is (= 1 (count scs)))
              (is (= "ops-test/exp" (get-in sc [:experiment :id])))
              (is (= 2 (:cells sc)))
              (is (= [{:candidate "alpha" :attempts 2 :passed 2}]
                     (mapv #(select-keys % [:candidate :attempts :passed]) (:leaderboard sc))))
              (is (= 1.0 (:pass-rate (first (:leaderboard sc)))))
              (is (map? (:spend (first (:leaderboard sc)))))
              (is (not (contains? sc :entries)))
              (let [full (ops/invoke daemon :scorecard/detail {:room "ops-evaluation-test" :id (:id sc)})]
                (is (= 2 (count (:entries full))))
                (is (every? :passed? (:entries full))))))))
      (finally
        (try (rreg/unregister! (:id room)) (catch Throwable _ nil))
        (d/close-room! room)))))
