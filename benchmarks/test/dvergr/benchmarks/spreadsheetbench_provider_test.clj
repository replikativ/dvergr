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
            [rechentafel.cell]
            [rechentafel.eval]
            [rechentafel.rc]
            [rechentafel.unparse]
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

(defn- scripted
  "A model that answers with `steps`, one per response: `[tool args]`, or a
   vector of those for calls sent together."
  [steps]
  (fn [_task]
    (let [left (atom steps)]
      (fn [_request]
        (let [step (first @left)
              calls (if (vector? (first step)) step (when step [step]))]
          (swap! left rest)
          (if (seq calls)
            {:content "" :tool-calls (vec (map-indexed (fn [i [tool args]] {:id (str "c" (count @left) "-" i) :name tool :arguments args})
                                                       calls))}
            {:content "done" :tool-calls []}))))))

(defn- evaluate! [room task steps]
  (let [caps (provider/capabilities [task] {:agent-generate (scripted steps)})
        env (provider/environment-def task caps {:timeout-ms 300000})
        team (provider/candidate-roster [{:id :scripted :model "claude-code-sonnet"}])]
    (binding [ec/*execution-context* (:ctx room)]
      (:attempt-receipt @(evaluation/evaluate room team :scripted env (:evaluator caps) {:protocol (:protocol caps)})))))

(deftest a-range-write-fills-it
  (let [wb (:wb (provider/apply-writes (rechentafel.eval/empty-workbook ["S"])
                                       [{:cell "'S'!A1:A3" :value 2}
                                        {:cell "'S'!B1:B3" :formula "=A1*10"}
                                        {:cell "'S'!C1:C2" :value "x"}
                                        {:cell "'S'!C1:C2" :value nil}]))
        v #(:v (rechentafel.eval/get-cell wb (rechentafel.cell/pack 0 %1 %2)))]
    (is (= [2.0 2.0 2.0] [(v 0 0) (v 1 0) (v 2 0)]) "a value in every cell")
    (is (= [20.0 20.0 20.0] [(v 0 1) (v 1 1) (v 2 1)]) "a formula relative to the top-left cell")
    (is (= "=A3*10" (str "=" (rechentafel.unparse/unparse (rechentafel.rc/resolve-at (get-in wb [:formulas (rechentafel.cell/pack 0 2 1)]) 2 1)))))
    (is (nil? (v 0 2)) "null clears")))

(deftest writing-the-gold-answer-is-graded-correct
  (if-not (sb/available?)
    (support/skip! "writing-the-gold-answer-is-graded-correct: no SpreadsheetBench data")
    (let [task (some #(when (= "17-35" (:id %)) %) (sb/tasks))
          room (d/make-room {:id :spreadsheetbench/provider-test :store (memory/make)})]
      (try
        (let [r (evaluate! room task [["write" {"cells" (gold-writes task)}] ["submit" {}]])]
          (is (= 1.0 (:attempt/reward r)) (pr-str (:attempt/checks r))))
        (testing "a write sent with the submit is part of the answer"
          ;; Haiku sent its last write with its submit in 20 of 79 attempts;
          ;; the write was dropped and each was graded 0
          (let [r (evaluate! room task [[["write" {"cells" (gold-writes task)}] ["submit" {}]]])]
            (is (= 1.0 (:attempt/reward r)) (pr-str (:attempt/checks r)))))
        (testing "the input as it is fails"
          (let [r (evaluate! room task [["submit" {}]])]
            (is (= 0.0 (:attempt/reward r)))
            (is (pos? (get-in r [:attempt/metrics :verification :mismatched])) "the receipt says what mismatched")
            (is (seq (get-in r [:attempt/metrics :verification :mismatches])))))
        (finally (d/close-room! room))))))
