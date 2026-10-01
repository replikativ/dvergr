(ns dvergr.benchmarks.spreadsheetbench-smc-test
  "SMC over agent steps on SpreadsheetBench, with a scripted stochastic model
  that writes one answer cell per turn, right with probability ½: an episode
  succeeds with probability ½^k. Steering by the (privileged) partial grade
  resamples towards right cells after every turn; best-of-N at the same
  number of model calls does not. Skipped without the dataset."
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.benchmarks.spreadsheetbench.core :as sb]
            [dvergr.benchmarks.spreadsheetbench.experiment :as experiment]
            [dvergr.benchmarks.spreadsheetbench.provider :as provider]
            [dvergr.benchmarks.spreadsheetbench.smc :as smc]
            [clojure.string :as str]
            [dvergr.test-support :as support])
  (:import (org.apache.poi.ss.util CellReference)))

(defn- answer-cells
  "[cell-ref gold-value] of the task's plain-value answer cells."
  [task]
  (with-open [pg (sb/open (:golden task))]
    (let [names (mapv #(.getSheetName pg (int %)) (range (.getNumberOfSheets pg)))]
      (vec (for [{:keys [sheet row col saved formula array]} (sb/gold-cells pg (sb/answer-ranges task))
                 :when (and (not formula) (not array) (not= :blank (:kind saved)) (number? (:v saved)))]
             [(str "'" (nth names sheet) "'!" (CellReference/convertNumToColString (int col)) (inc (long row)))
              (:v saved)])))))

(defn- coin-model
  "Turn t writes answer cell t, right with probability ½, then submits."
  [cells]
  (fn [{:keys [messages]}]
    (let [t (count (filter #(= :assistant (:role %)) messages))]
      (if-let [[cell v] (get cells t)]
        {:content "" :tool-calls [{:id (str "w" t) :name "write"
                                   :arguments {"cells" [{"cell" cell "value" (if (< (rand) 0.5) v (+ v 1000))}]}}]}
        {:content "done" :tool-calls []}))))

(defn- task-with-numeric-answers
  "A task whose answer is 3 to 6 plain numbers, every one of which the coin
  model writes (writing them all right grades correct), with as many as
  there are."
  []
  (->> (sb/tasks)
       ;; the oracle-certified tasks: their gold files are there and grade
       (filter (let [ids (experiment/certified-ids)] #(and ids (contains? ids (:id %)))))
       (keep (fn [t]
               (let [cs (answer-cells t)]
                 (when (and (<= 3 (count cs) 6)
                            (:correct (provider/grade t (mapv (fn [[c v]] {:cell c :value v}) cs))))
                   [t cs]))))
       ;; the most cells: the hardest for independent episodes
       (sort-by (comp - count second))
       first))

(deftest steering-by-the-partial-grade-beats-best-of-n
  (if-not (and (sb/available?) (experiment/certified-ids))
    (support/skip! "steering-by-the-partial-grade-beats-best-of-n: no SpreadsheetBench data")
    (let [[task cells] (task-with-numeric-answers)
          k (count cells)
          runs 20
          success (fn [opts]
                    (count (filter #(:correct (provider/grade task (:writes %)))
                                   (repeatedly runs #(smc/run task (assoc opts :generate (coin-model cells)
                                                                          :max-turns (inc k)))))))
          steered (success {:particles 4 :twist :oracle :reward :oracle})
          best-of-n (success {:particles 4 :twist :none :reward :oracle :resample-threshold 0.0})]
      (testing (str k " answer cells, a single episode succeeds with probability " (Math/pow 0.5 k))
        (is (> steered best-of-n) (str "steered " steered "/" runs " vs best-of-4 " best-of-n "/" runs))
        (is (>= steered (* 0.6 runs)) (str steered "/" runs)))
      (testing "the search counts every particle's model calls"
        (let [out (smc/run task {:particles 4 :generate (coin-model cells) :max-turns (inc k)})]
          (is (= (* 4 (inc k)) (get-in out [:search :model-steps])))
          (is (= :submitted (:termination out)))))
      (testing "a cancelled search ends every particle without another model call"
        (let [out (smc/run task {:particles 4 :generate (coin-model cells) :max-turns (inc k)
                                 :cancelled? (constantly true)})]
          (is (= 0 (get-in out [:search :model-steps])))
          (is (= :cancelled (:termination out))))))))

(deftest a-judge-reply-is-a-probability
  (is (= 0.7 (smc/parse-probability "{\"p\": 0.7}")))
  (is (= 1.0 (smc/parse-probability "{\"p\": 3}")))
  (is (= 0.01 (smc/parse-probability "no idea"))))

(deftest the-judge-scores-each-state-once
  (if-not (and (sb/available?) (experiment/certified-ids))
    (support/skip! "the-judge-scores-each-state-once: no SpreadsheetBench data")
    (let [[task cells] (task-with-numeric-answers)
          k (count cells)
          prompts (atom [])
          judge (fn [{:keys [messages]}]
                  (swap! prompts conj (:content (first messages)))
                  {:content "{\"p\": 0.5}" :usage {:input-tokens 10 :output-tokens 2}})
          out (smc/run task {:particles 4 :twist :judge :reward :judge :judge judge
                             :generate (coin-model cells) :max-turns (inc k)})]
      (is (= :submitted (:termination out)))
      ;; every state a particle reaches is judged at most once, copies included
      (is (<= 1 (get-in out [:search :judge-calls]) (get-in out [:search :model-steps])))
      (is (= (get-in out [:search :judge-calls]) (count @prompts)))
      (is (every? #(str/includes? % "ANSWER POSITION NOW") @prompts)))))

(deftest searches-export-training-records
  (if-not (and (sb/available?) (experiment/certified-ids))
    (support/skip! "searches-export-training-records: no SpreadsheetBench data")
    (let [[task cells] (task-with-numeric-answers)
          k (count cells)
          seen (atom nil)
          out (smc/run task {:particles 4 :twist :oracle :reward :oracle
                             :generate (coin-model cells) :max-turns (inc k)
                             :on-trajectories #(reset! seen %)})
          records (smc/training-records task @seen)]
      (is (= 4 (count @seen)))
      (is (= (reduce + (map (comp count :states) @seen)) (count records)) "one record per state")
      (is (every? #(and (string? (:state %)) (boolean? (get-in % [:gold :success :label]))) records))
      (is (some #(get-in % [:gold :success :label]) records) "the steered search ends correct somewhere")
      (is (< (Math/abs (- 1.0 (reduce + (map :weight @seen)))) 1e-9))
      (is (= :submitted (:termination out))))))
