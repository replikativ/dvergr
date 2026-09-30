(ns dvergr.benchmarks.spreadsheetbench.provider
  "SpreadsheetBench on the generic evaluation path: a candidate edits the
   task's workbook (rechentafel, in memory) through tools, and the trusted
   evaluator replays its edits onto a fresh copy of the input, recalculates,
   and grades the answer range against the gold (`spreadsheetbench.core`).

   Tools: `read` a range (values and formulas), `write` cells (values or
   formulas, the workbook recalculates and shows what they became), `submit`.
   The evidence is the list of writes, so a verdict is reproducible without
   the model. Only tasks the oracle certifies (`spreadsheetbench.oracle`: a
   correct answer is graded correct) should be run."
  (:require [clojure.string :as str]
            [dvergr.agent.environment :as environment]
            [dvergr.agent.evaluation :as evaluation]
            [dvergr.agent.roster :as roster]
            [dvergr.agent.spend :as spend]
            [dvergr.benchmarks.live :as live]
            [dvergr.benchmarks.spreadsheetbench.core :as sb]
            [dvergr.benchmarks.spreadsheetbench.oracle :as oracle]
            [dvergr.model.registry :as registry]
            [rechentafel.cell :as cell]
            [rechentafel.eval :as e]
            [rechentafel.functions.all]
            [rechentafel.poi :as poi]
            [rechentafel.parser :as parser]
            [rechentafel.rc :as rc]
            [rechentafel.unparse :as unparse])
  (:import (org.apache.poi.ss.util CellReference)))

(def version
  "1: read/write/submit over rechentafel; graded by upstream's compare rules
   against the gold's saved values. 2: `write` to a range fills it (a value
   in every cell, blank to clear; a formula relative to its top-left cell,
   as Excel fills); the grade names the first mismatched cells."
  2)

;; ---------------------------------------------------------------------------
;; The workbook as the candidate sees and edits it

(defonce ^:private init-cache (atom {}))

(defn- init-workbook [{:keys [id init]}]
  (or (get @init-cache id)
      (get (swap! init-cache assoc id (poi/load-workbook (str init))) id)))

(defn- sheet-name-of [wb si] (some (fn [[n i]] (when (= i si) n)) (:sheet-names wb)))

(defn- resolve-sheet
  "The sheet index a range names in `wb` (the first sheet when none)."
  [wb {:keys [sheet]}]
  (if sheet (get (:sheet-names wb) sheet) 0))

(defn- a1 [r c] (str (CellReference/convertNumToColString (int c)) (inc (long r))))

(defn- show-value [{:keys [kind v]}]
  (case kind :blank "" :str (pr-str v) :num (let [d (double v)] (if (== d (Math/rint d)) (str (long d)) (str d))) (str v)))

(defn- formula-at [wb id]
  (when-let [ast (get-in wb [:formulas id])]
    (str "=" (unparse/unparse (rc/resolve-at ast (cell/row id) (cell/col id))))))

(def ^:private read-cap 400)

(defn read-range
  "The non-blank cells of range text `s` in `wb`, one per line
   (`B2 = 5    =SUM(A1:A3)`), at most `read-cap`."
  [wb s]
  (let [rng (sb/parse-range s)
        si (when rng (resolve-sheet wb rng))]
    (cond
      (nil? rng) (str "Error: not a range: " s " (write e.g. 'Sheet1'!A1:D20)")
      (nil? si) (str "Error: no sheet " (pr-str (:sheet rng)) "; sheets: " (str/join ", " (keys (:sheet-names wb))))
      :else
      (let [cells (for [r (range (:r0 rng) (inc (:r1 rng))) c (range (:c0 rng) (inc (:c1 rng)))
                        :let [id (cell/pack si r c)
                              v (sb/rechentafel-value (e/get-cell wb id) (sb/formula-result? wb id))
                              f (formula-at wb id)]
                        :when (or f (not= :blank (:kind v)))]
                    (str (a1 r c) " = " (show-value v) (when f (str "    " f))))
            shown (take read-cap cells)]
        (str "Sheet " (pr-str (sheet-name-of wb si)) ", " (count shown) " non-blank cell(s)"
             (when (> (count cells) read-cap) (str " (first " read-cap ")")) ":\n"
             (str/join "\n" shown))))))

(defn- with-target-sheet
  "`wb` with the sheet `target` names, created when missing (a sheet-level
   task may ask for a new sheet)."
  [wb target]
  (if-let [nm (:sheet (sb/parse-range (str target)))] (oracle/ensure-sheet wb nm) wb))

(defn- parse-target [wb target]
  (let [rng (sb/parse-range (str target))
        si (when rng (resolve-sheet wb rng))]
    (when (and rng si (= (:r0 rng) (:r1 rng)) (= (:c0 rng) (:c1 rng)))
      (cell/pack si (:r0 rng) (:c0 rng)))))

(defn- parse-range-target [wb target]
  (let [rng (sb/parse-range (str target))
        si (when rng (resolve-sheet wb rng))]
    (when (and rng si) [(cell/pack si (:r0 rng) (:c0 rng)) (:r1 rng) (:c1 rng)])))

(defn- fill-range
  "`wb` with `input` (a value, or a formula string) in every cell from
   `anchor` to row `r1`, column `c1`: a value as it is, a formula relative to
   the anchor (=A1*2 filled down B1:B3 is =A2*2 in B2), as Excel fills."
  [wb anchor r1 c1 input]
  (let [s (cell/sheet anchor) r0 (cell/row anchor) c0 (cell/col anchor)
        cells (for [r (range r0 (inc (long r1))) c (range c0 (inc (long c1)))] [r c])]
    (if (and (string? input) (str/starts-with? input "="))
      (let [rc (rc/normalize (parser/parse (subs input 1)) r0 c0)]
        (reduce (fn [wb [r c]] (e/set-cell wb (cell/pack s r c) (if (and (= r r0) (= c c0)) input (rc/resolve-at rc r c))))
                wb cells))
      (reduce (fn [wb [r c]] (e/set-cell wb (cell/pack s r c) input)) wb cells))))

(defn apply-writes
  "`wb` with `writes` `[{:cell \"'S'!B2\" :value v | :formula \"=…\" :array bool}]`
   applied and recalculated: `{:wb :errors}`."
  [wb writes]
  (let [[wb errors]
        (reduce (fn [[wb errs] {:keys [cell value formula array] :as w}]
                  (let [wb (with-target-sheet wb cell)]
                    (if (and array formula)
                    ;; a legacy (Ctrl+Shift+Enter) array formula over a range
                      (if-let [[anchor r1 c1] (parse-range-target wb cell)]
                        (try [(e/set-array-formula wb anchor (let [f (str/trim (str formula))] (if (str/starts-with? f "=") f (str "=" f))) r1 c1) errs]
                             (catch Throwable t [wb (conj errs (str cell ": " (ex-message t)))]))
                        [wb (conj errs (str "not a range: " (pr-str cell)))])
                      (if-let [[anchor r1 c1] (parse-range-target wb cell)]
                        (try
                          [(fill-range wb anchor r1 c1
                                       (if (some? formula)
                                         (let [f (str/trim (str formula))] (if (str/starts-with? f "=") f (str "=" f)))
                                         (if (string? value) {:t :str :v value} value)))
                           errs]
                          (catch Throwable t [wb (conj errs (str cell ": " (ex-message t)))]))
                        [wb (conj errs (str "not a cell or range: " (pr-str cell)))]))))
                [wb []] writes)]
    {:wb (e/recalc wb) :errors errors}))

(defn- normalize-writes [cells]
  (vec (for [w cells :when (map? w)]
         {:cell (or (get w :cell) (get w "cell"))
          :value (let [v (or (get w :value) (get w "value"))] v)
          :formula (or (get w :formula) (get w "formula"))
          :array (boolean (or (get w :array) (get w "array")))})))

(defn- sheets-overview [{:keys [init]}]
  (with-open [pg (sb/open init)]
    (str/join "\n"
              (for [i (range (.getNumberOfSheets pg))
                    :let [sh (.getSheetAt pg (int i))
                          last-row (.getLastRowNum sh)
                          last-col (reduce max 0 (for [r (range 0 (inc last-row))
                                                       :let [row (.getRow sh (int r))] :when row]
                                                   (max 0 (dec (.getLastCellNum row)))))]]
                (str "  " (pr-str (.getSheetName sh)) ": A1:" (a1 last-row last-col))))))

;; ---------------------------------------------------------------------------
;; The episode

(def system-prompt
  (str "You solve a spreadsheet task by editing the workbook with tools. `read` shows a range's "
       "values and formulas; `write` sets cells to values or formulas (a formula starts with =, "
       "in Excel syntax) and shows what they calculate to; `submit` ends the task. The answer is "
       "graded by the values in the answer position after recalculation, so write formulas or "
       "values there; formulas that would work on other data are preferred."))

(def ^:private tools
  [{"type" "function"
    "function" {"name" "read" "description" "Values and formulas of a range, e.g. 'Sheet1'!A1:D20 (non-blank cells)."
                "parameters" {"type" "object" "properties" {"range" {"type" "string"}} "required" ["range"]}}}
   {"type" "function"
    "function" {"name" "write" "description" "Set cells to values or formulas; returns what they calculate to."
                "parameters" {"type" "object"
                              "properties" {"cells" {"type" "array"
                                                     "items" {"type" "object"
                                                              "properties" {"cell" {"type" "string" "description" "a cell, e.g. 'Sheet1'!B2, or a range to fill: every cell gets `value` (null clears), or `formula` relative to the range's top-left cell as Excel fills it"}
                                                                            "value" {"description" "a number, text or boolean"}
                                                                            "formula" {"type" "string" "description" "e.g. =SUM(A1:A3)"}
                                                                            "array" {"type" "boolean" "description" "true: `cell` is a range and `formula` a legacy array (Ctrl+Shift+Enter) formula filling it"}}
                                                              "required" ["cell"]}}}
                              "required" ["cells"]}}}
   {"type" "function"
    "function" {"name" "submit" "description" "Finish: the answer position is graded as it is now."
                "parameters" {"type" "object" "properties" {}}}}])

(defn- episode!
  [{:keys [task generate max-turns cancelled?]}]
  (let [user (str "Task (" (:type task) "):\n" (:instruction task)
                  "\n\nAnswer position: " (:answer-position task)
                  (when (and (:answer-sheet task) (not (str/includes? (str (:answer-position task)) "!")))
                    (str " (sheet " (pr-str (:answer-sheet task)) ")"))
                  "\n\nSheets:\n" (sheets-overview task))]
    (loop [turn 0 history [{:role :user :content user}] wb (init-workbook task) writes [] usage {}]
      (let [done (fn [termination turns history usage]
                   {:termination termination :writes writes :usage usage :model-steps turns
                    :transcript (subvec history 1)})]
        (cond
          (and cancelled? (cancelled?)) (done :cancelled turn history usage)
          (>= turn max-turns) (done :max-turns turn history usage)
          :else
          (let [{:keys [content tool-calls] :as response}
                (generate {:system system-prompt :messages history :tools tools})
                usage (merge-with #(if (and (number? %1) (number? %2)) (+ %1 %2) %2)
                                  usage (select-keys (:usage response) [:input-tokens :output-tokens :cache-read-tokens]))
                history (conj history (cond-> {:role :assistant :content content}
                                        (seq tool-calls) (assoc :tool-calls (vec tool-calls))))]
            (cond
              (some #(= "submit" (:name %)) tool-calls) (done :submitted (inc turn) history usage)
              (empty? tool-calls) (done :submitted (inc turn) history usage)
              :else
              (let [[wb writes results]
                    (reduce (fn [[wb writes results] {:keys [id name arguments]}]
                              (case name
                                "read" [wb writes (conj results [id (read-range wb (str (or (:range arguments) (get arguments "range"))))])]
                                "write" (let [ws (normalize-writes (or (:cells arguments) (get arguments "cells")))
                                              {wb' :wb errors :errors} (apply-writes wb ws)]
                                          [wb' (into writes ws)
                                           (conj results [id (str "Wrote " (- (count ws) (count errors)) " cell(s)."
                                                                  (when (seq errors) (str " Errors: " (str/join "; " errors)))
                                                                  "\n" (str/join "\n" (for [w ws :let [cid (parse-target wb' (:cell w))] :when cid]
                                                                                        (str (:cell w) " = " (show-value (sb/rechentafel-value (e/get-cell wb' cid) (sb/formula-result? wb' cid)))))))])])
                                [wb writes (conj results [id (str "Unknown tool " name)])]))
                            [wb writes []] tool-calls)]
                (recur (inc turn)
                       (into history (map (fn [[id text]] {:role :tool :id id :content text})) results)
                       wb writes usage)))))))))

;; ---------------------------------------------------------------------------
;; Grading

(defn grade
  "`{:correct :cells :mismatched}` of `writes` replayed on the task's input."
  [task writes]
  (let [ranges (sb/answer-ranges task)]
    (with-open [pg (sb/open (:golden task))]
      (let [gold (sb/gold-cells pg ranges)
            names (mapv #(.getSheetName pg (int %)) (range (.getNumberOfSheets pg)))
            {:keys [wb]} (apply-writes (init-workbook task) writes)
            {:keys [compared mismatches]} (oracle/compare-cells wb names gold)]
        {:correct (empty? mismatches) :cells compared :mismatched (count mismatches)
         ;; the first ones, to tell a model's miss from a harness gap
         :mismatches (vec (take 5 mismatches))}))))

(defn- task-of [by-id definition] (get by-id (get-in definition [:environment/task :id])))

(defn capabilities
  "The trusted capabilities over `tasks`. `:agent-generate` `(fn [task]) ->
   generate fn` replaces the model."
  [tasks {:keys [agent-generate]}]
  (let [by-id (into {} (map (juxt :id identity)) tasks)
        basis {:upstream "SpreadsheetBench verified_400" :grading "compare_workbooks (evaluation.py), gold saved values"}]
    {:protocol
     (evaluation/make-protocol
      {:id :spreadsheetbench/read-write-submit :version version :basis basis :limit-keys #{:max-turns}
       :run (fn [{:keys [agent environment cancelled? model-scope]}]
              (let [task (task-of by-id environment)]
                (episode! {:task task
                           :max-turns (get-in environment [:environment/limits :max-turns] 30)
                           :cancelled? cancelled?
                           :generate (live/scoped (if agent-generate
                                                    (agent-generate task)
                                                    (live/model-generate (:agent/model-policy agent)))
                                                  model-scope)})))})
     :evaluator
     (evaluation/make-evaluator
      {:id :spreadsheetbench/answer-range :version version :basis basis :tier :trusted
       :observe (fn [{:keys [result durable agent]}]
                  (let [outcome (:run/value result)]
                    {:result {:termination (or (:termination outcome) :infrastructure-error)}
                     :failure (when-not (= :completed (:run/status result))
                                {:status (:run/status result) :reason (:run/reason durable)
                                 :message (:run/error durable)})
                     :writes (:writes outcome)
                     :episode (select-keys outcome [:model-steps :usage])
                     :transcript (:transcript outcome)
                     :spend (if (map? (:usage outcome))
                              (spend/of-usage (get-in agent [:agent/model-policy :model]) (:usage outcome))
                              spend/zero)}))
       :verify (fn [definition {:keys [writes] :as evidence}]
                 (let [task (task-of by-id definition)
                       submitted? (= :submitted (get-in evidence [:result :termination]))
                       g (when submitted? (grade task (or writes [])))]
                   {:reward (if (:correct g) 1.0 0.0)
                    :checks {:submitted submitted? :correct (boolean (:correct g))}
                    :metrics (select-keys g [:cells :mismatched :mismatches])}))})}))

(defn environment-def
  [{:keys [id type]} {:keys [protocol evaluator]}
   {:keys [max-turns timeout-ms] :or {max-turns 30 timeout-ms (* 10 60 1000)}}]
  (let [ver (evaluation/evaluator-ref evaluator)]
    (environment/make-environment
     {:id (keyword "spreadsheetbench" (str "t-" id))
      :task {:id id}
      :verifier (cond-> {:id (:verifier/id ver) :version (:verifier/version ver)}
                  (:verifier/basis ver) (assoc :basis (:verifier/basis ver)))
      :limits {:max-turns max-turns :timeout-ms timeout-ms :cancel-timeout-ms 30000}
      :world {:isolation :ctx :settlement :discard :protocol (evaluation/protocol-ref protocol)}
      :metadata {:benchmark :spreadsheetbench :instruction-type type}})))

(defn candidate-roster
  "AgentDefs for `{:id :model :provider :budget-dollars}`."
  [specs]
  (reduce (fn [team {:keys [id model provider budget-dollars] :or {budget-dollars 1.0}}]
            (let [model-id (registry/resolve-alias model)]
              (roster/make-agent
               team
               {:id id
                :prompt "SpreadsheetBench candidate (the prompt is the task's)"
                :tools #{}
                :model-policy {:provider (or provider (:provider (registry/get-model! model-id))) :model model-id}
                :program {:kind :llm :max-model-steps 30 :budget-dollars budget-dollars}})))
          (roster/make-roster {:id :spreadsheetbench/candidates})
          specs))
