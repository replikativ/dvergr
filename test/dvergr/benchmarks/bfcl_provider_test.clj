(ns dvergr.benchmarks.bfcl-provider-test
  "BFCL on the generic evaluation path, with scripted candidates (no model)."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [dvergr.agent.episode :as attempts]
            [dvergr.agent.evaluation :as evaluation]
            [dvergr.agent.run :as run]
            [dvergr.benchmarks.bfcl.core :as bfcl]
            [dvergr.benchmarks.bfcl.equivalence :as eq]
            [dvergr.benchmarks.bfcl.provider :as provider]
            [dvergr.discourse :as d]
            [dvergr.room.store.memory :as memory]
            [org.replikativ.spindel.engine.core :as ec]))

(def ^:private checkout?
  (.exists (io/file bfcl/default-root (:path bfcl/upstream) "bfcl_eval" "data")))

(defn- as-tool-calls
  "Decoded calls `[{name args}]` as a model response's `:tool-calls`."
  [calls]
  (map-indexed (fn [i call]
                 (let [[tool-name args] (first call)]
                   {:id (str "call_" i) :name tool-name :arguments args}))
               calls))

(defn- scripted
  "A candidate that answers every task with `(answer task)`: decoded calls, or
   a string for a text answer."
  [answer]
  (fn [task]
    (fn [request]
      (let [a (answer task request)]
        (if (string? a)
          {:content a :tool-calls []}
          {:content "" :tool-calls (as-tool-calls a)})))))

(defn- team []
  (provider/candidate-roster [{:id :scripted :model "claude-code-sonnet"}]))

(defn- evaluate! [room tasks task-id answer]
  (let [caps (provider/capabilities tasks {:agent-generate (scripted answer)})
        env (provider/environment-def (get tasks task-id) caps {:timeout-ms 60000})]
    (binding [ec/*execution-context* (:ctx room)]
      @(evaluation/evaluate room (team) :scripted env (:evaluator caps)
                            {:world-setup (:world-setup caps)
                             :protocol (:protocol caps)}))))

(deftest a-task-through-evaluate
  (if-not checkout?
    (println "SKIP a-task-through-evaluate: no ../gorilla checkout")
    (let [tasks (provider/tasks ["parallel" "irrelevance"])
          room (d/make-room {:id :bfcl/provider-test :store (memory/make)})
          seen (atom nil)]
      (try
        (testing "the gold answer, as a model would emit it"
          (let [result (evaluate! room tasks "parallel_0"
                                  (fn [task request] (reset! seen request) (eq/gold-calls task)))
                receipt (:attempt-receipt result)
                attempt (first (attempts/attempts room {:limit 10}))]
            (is (= 1.0 (:attempt/reward receipt)))
            (is (= :completed (:attempt/status receipt)))
            (is (= :trusted (get-in receipt [:attempt/metrics :verifier-trust])))
            (is (true? (get-in receipt [:attempt/checks :valid])))
            (testing "the candidate got the question and the compiled tools, not the answers"
              (is (= :user (:role (first (:messages @seen)))))
              (is (= (count (:functions (get tasks "parallel_0"))) (count (:tools @seen))))
              (is (not (re-find #"ground" (pr-str @seen)))))
            (testing "the certified Attempt carries the recorded calls"
              (is (= (:attempt/id receipt) (:attempt/id attempt)))
              (is (= (eq/gold-calls (get tasks "parallel_0"))
                     (get-in attempt [:attempt/evidence :calls]))))
            (testing "the run's world was discarded"
              (is (= :discarded (:run/settlement-status (run/run room (:run/id result))))))))
        (testing "a wrong answer scores zero and says why"
          (let [receipt (:attempt-receipt
                         (evaluate! room tasks "parallel_0"
                                    (fn [task _] (vec (rest (eq/gold-calls task))))))]
            (is (= 0.0 (:attempt/reward receipt)))
            (is (false? (get-in receipt [:attempt/checks
                                         :bfcl.error/parallel_function_checker_no_order_wrong_count])))))
        (testing "irrelevance: a text answer passes, a call fails"
          (is (= 1.0 (:attempt/reward (:attempt-receipt
                                       (evaluate! room tasks "irrelevance_0"
                                                  (fn [_ _] "I cannot help with that using these functions."))))))
          (is (= 0.0 (:attempt/reward (:attempt-receipt
                                       (evaluate! room tasks "irrelevance_0"
                                                  (fn [_ request]
                                                    [{(get-in (first (:tools request)) ["function" "name"]) {}}])))))))
        (testing "no Run is left alive"
          (is (empty? (run/active-runs (:id room)))))
        (finally (d/close-room! room))))))
