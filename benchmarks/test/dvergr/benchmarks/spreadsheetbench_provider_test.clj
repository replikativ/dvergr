(ns dvergr.benchmarks.spreadsheetbench-provider-test
  "SpreadsheetBench on the generic evaluation path with scripted candidates:
   writing the gold's answer through the tools is graded correct, submitting
   the input as it is is not; and the grading rules. Skipped without the
   dataset (see `dvergr.benchmarks.spreadsheetbench.core`)."
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.agent.evaluation :as evaluation]
            [dvergr.benchmarks.spreadsheetbench.core :as sb]
            [dvergr.benchmarks.spreadsheetbench.provider :as provider]
            [dvergr.discourse :as d]
            [dvergr.room.store.memory :as memory]
            [dvergr.test-support :as support]
            [org.replikativ.spindel.engine.core :as ec])
  (:import (org.apache.poi.ss.util CellReference)))

(deftest the-grading-rules-are-upstreams
  (is (sb/same? (sb/transform {:kind :num :v 3.1} nil) (sb/transform {:kind :str :v "3.10"} nil))
      "a numeric string is its number, rounded to 2 places")
  (is (sb/same? (sb/transform {:kind :blank} nil) (sb/transform {:kind :str :v ""} nil)) "nil equals \"\"")
  (is (sb/same? (sb/transform {:kind :bool :v true} nil) (sb/transform {:kind :num :v 1} nil)) "TRUE is 1.0")
  (is (not (sb/same? (sb/transform {:kind :str :v "a"} nil) (sb/transform {:kind :str :v "A"} nil))) "strings exactly")
  (is (= [:num 2.67] (sb/transform {:kind :num :v 2.675} nil)) "Python's round: the binary value, half to even")
  (is (= [:num 45000.0] (sb/transform {:kind :num :v 45000.4} :date)) "a date to its day")
  (is (= [:str "12:30"] (sb/transform {:kind :num :v 0.5208333333} :time)) "a time to HH:MM")
  (is (= {:sheet "Sheet 1" :r0 0 :c0 0 :r1 9 :c1 3} (sb/parse-range "'Sheet 1'!A1:D10")))
  (is (nil? (sb/parse-range "Sheet3'!A:G")) "a whole column is not a range upstream can grade"))

(defn- gold-writes
  "The task's gold answer cells as `write` tool cells."
  [task]
  (with-open [pg (sb/open (:golden task))]
    (let [names (mapv #(.getSheetName pg (int %)) (range (.getNumberOfSheets pg)))]
      (vec (for [{:keys [sheet row col saved formula array array-sibling]} (sb/gold-cells pg (sb/answer-ranges task))
                 :when (and (not array-sibling) (or formula (not= :blank (:kind saved))))]
             (cond-> {"cell" (str "'" (nth names sheet) "'!" (CellReference/convertNumToColString (int col)) (inc (long row))
                                  (when array (str ":" (CellReference/convertNumToColString (int (second array))) (inc (long (first array))))))}
               formula (assoc "formula" (str "=" formula))
               array (assoc "array" true)
               (not formula) (assoc "value" (:v saved))))))))

(defn- scripted [steps]
  (fn [_task]
    (let [left (atom steps)]
      (fn [_request]
        (let [[tool args] (first @left)]
          (swap! left rest)
          (if tool
            {:content "" :tool-calls [{:id (str "c" (count @left)) :name tool :arguments args}]}
            {:content "done" :tool-calls []}))))))

(defn- evaluate! [room task steps]
  (let [caps (provider/capabilities [task] {:agent-generate (scripted steps)})
        env (provider/environment-def task caps {:timeout-ms 300000})
        team (provider/candidate-roster [{:id :scripted :model "claude-code-sonnet"}])]
    (binding [ec/*execution-context* (:ctx room)]
      (:attempt-receipt @(evaluation/evaluate room team :scripted env (:evaluator caps) {:protocol (:protocol caps)})))))

(deftest writing-the-gold-answer-is-graded-correct
  (if-not (sb/available?)
    (support/skip! "writing-the-gold-answer-is-graded-correct: no SpreadsheetBench data")
    (let [task (some #(when (= "17-35" (:id %)) %) (sb/tasks))
          room (d/make-room {:id :spreadsheetbench/provider-test :store (memory/make)})]
      (try
        (let [r (evaluate! room task [["write" {"cells" (gold-writes task)}] ["submit" {}]])]
          (is (= 1.0 (:attempt/reward r)) (pr-str (:attempt/checks r))))
        (testing "the input as it is fails"
          (is (= 0.0 (:attempt/reward (evaluate! room task [["submit" {}]])))))
        (finally (d/close-room! room))))))
