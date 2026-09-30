(ns dvergr.benchmarks.spreadsheetbench.oracle
  "Which SpreadsheetBench tasks rechentafel can grade, at zero tokens: for
   each task,

   - `:gold`     recalculating the gold workbook reproduces the answer cells
                 Excel saved (a mismatch is a rechentafel bug or a loader gap);
   - `:solvable` the init workbook with the gold's answer cells written into
                 it (the gold's formulas where it has them, else its values)
                 recalculates to the saved answer: a correct answer would be
                 graded correct.

   A task is certified when it is solvable; the rest are reported with why,
   never charged to a candidate."
  (:require [dvergr.benchmarks.spreadsheetbench.core :as sb]
            [rechentafel.cell :as cell]
            [rechentafel.eval :as e]
            [rechentafel.functions.all]
            [rechentafel.mtv :as mtv]
            [rechentafel.poi :as poi])
  (:import (java.util.concurrent Executors TimeUnit TimeoutException Future)))

(defn- with-timeout [ms f]
  (let [ex (Executors/newSingleThreadExecutor)
        ^Future fut (.submit ex ^Callable f)]
    (try (.get fut (long ms) TimeUnit/MILLISECONDS)
         (catch TimeoutException _ (.cancel fut true) ::timeout)
         (finally (.shutdownNow ex)))))

(defn compare-cells
  "`{:compared :uncached :mismatches}` of rechentafel workbook `wb` (sheets
   by name, `names` the gold's sheet names) against the gold's cells."
  [wb names gold]
  (let [rows (for [{:keys [sheet row col saved format] :as g} gold
                   :when (not= :uncached (:kind saved))
                   :let [si (get (:sheet-names wb) (nth names sheet))
                         id (when si (cell/pack si row col))
                         got (if si
                               (sb/rechentafel-value (e/get-cell wb id) (sb/formula-result? wb id))
                               {:kind :blank})
                         a (sb/transform got format)
                         b (sb/transform saved format)]]
               (when-not (sb/same? a b) (assoc (select-keys g [:sheet :row :col :formula]) :want b :got a)))]
    {:compared (count (remove #(= :uncached (get-in % [:saved :kind])) gold))
     :uncached (count (filter #(= :uncached (get-in % [:saved :kind])) gold))
     :mismatches (vec (remove nil? rows))}))

(defn ensure-sheet
  "`wb` with a sheet named `nm`, appended when missing (a sheet-level task's
   answer may be on a sheet the input lacks). rechentafel has no public
   API for this yet: a new sheet is an empty column store and a name."
  [wb nm]
  (if (contains? (:sheet-names wb) nm)
    wb
    (-> wb
        (update :sheets conj (mtv/empty-sheet))
        (assoc-in [:sheet-names nm] (count (:sheets wb))))))

(defn write-answer
  "rechentafel workbook `wb` with the gold's answer cells written into it."
  [wb names gold]
  (e/recalc
   (reduce (fn [wb {:keys [sheet row col saved formula array array-sibling]}]
             (let [wb (ensure-sheet wb (nth names sheet))
                   si (get (:sheet-names wb) (nth names sheet))]
               (let [id (cell/pack si row col)
                     v (case (:kind saved)
                         :num (:v saved) :str {:t :str :v (:v saved)} :bool (:v saved) nil)]
                 (cond
                   array-sibling wb
                   (and formula array) (e/set-array-formula wb id (str "=" formula) (first array) (second array))
                   formula (e/set-cell wb id (str "=" formula))
                   :else (e/set-cell wb id v)))))
           wb gold)))

(defn check-task
  "The oracle's verdict on one task: `{:id :status …}`, `:status` one of
   :certified, :certified-gold (the gold recalculates as saved, but writing
   only its answer cells into the input does not: the solution edits more),
   :unsolvable (with the mismatches), :malformed,
   :missing-sheet, :error, :timeout."
  [{:keys [id golden init] :as task} {:keys [timeout-ms] :or {timeout-ms 60000}}]
  (let [ranges (sb/answer-ranges task)]
    (if-not ranges
      {:id id :status :malformed}
      (let [r (with-timeout timeout-ms
                (fn []
                  (try
                    (with-open [pg (sb/open golden)]
                      (if-let [gold (sb/gold-cells pg ranges)]
                        (let [names (mapv #(.getSheetName pg (int %)) (range (.getNumberOfSheets pg)))
                              g (compare-cells (poi/load-workbook (str golden)) names gold)
                              s (compare-cells (write-answer (poi/load-workbook (str init)) names gold) names gold)]
                          {:id id :cells (count gold) :uncached (:uncached g)
                           :gold-mismatches (count (:mismatches g))
                           ;; certified: the gold's answer written into the input is
                           ;; graded correct, or (a solution that also edits other
                           ;; cells) rechentafel reproduces the gold as saved
                           :status (cond (empty? (:mismatches s)) :certified
                                         (empty? (:mismatches g)) :certified-gold
                                         :else :unsolvable)
                           :mismatches (vec (take 5 (:mismatches s)))})
                        {:id id :status :missing-sheet}))
                    (catch Throwable t {:id id :status :error :error (str (.getSimpleName (class t)) ": " (ex-message t))}))))]
        (if (= ::timeout r) {:id id :status :timeout} r)))))

(defn report
  "The oracle over `tasks` (all by default): `{:summary :results}`."
  ([] (report (sb/tasks) {}))
  ([tasks opts]
   (let [results (mapv #(check-task % opts) tasks)]
     {:summary {:tasks (count results)
                :by-status (frequencies (map :status results))
                :uncached-cells (reduce + (keep :uncached results))}
      :results results})))
