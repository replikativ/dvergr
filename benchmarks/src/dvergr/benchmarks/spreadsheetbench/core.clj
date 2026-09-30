(ns dvergr.benchmarks.spreadsheetbench.core
  "SpreadsheetBench (https://github.com/RUCKBReasoning/SpreadsheetBench,
   CC BY-SA 4.0): spreadsheet tasks from Excel forums, graded by comparing
   the cells of an answer range with a gold workbook. The Verified set (400
   tasks, one test case each) is `SPREADSHEETBENCH_ROOT` (default
   `~/.cache/dvergr-bench/spreadsheetbench/data/spreadsheetbench_verified_400`).

   Grading is upstream's `compare_workbooks` (evaluation.py), ported:
   answer ranges column-major, the first gold sheet when a range names none;
   numbers and booleans rounded to 2 places (Python's `round`: the exact
   binary value, half to even), dates to a day serial, times to \"HH:MM\",
   numeric strings as numbers, nil and \"\" equal, otherwise equal type and
   value. The truth is the value Excel saved in the gold workbook. One
   deliberate difference: rechentafel keeps no number formats, so a produced
   cell is typed (date, time, number) by the gold cell's format, not its own."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [rechentafel.cell :as cell]
            [jsonista.core :as json])
  (:import (java.math BigDecimal RoundingMode)
           (org.apache.poi.ss.usermodel Cell CellType DateUtil FormulaError Workbook WorkbookFactory)
           (org.apache.poi.ss.util CellReference)
           (org.apache.poi.openxml4j.util ZipSecureFile)))

(defn root []
  (or (System/getenv "SPREADSHEETBENCH_ROOT")
      (str (System/getProperty "user.home")
           "/.cache/dvergr-bench/spreadsheetbench/data/spreadsheetbench_verified_400")))

(defn available? ([] (available? (root))) ([r] (.exists (io/file r "dataset.json"))))

(defn- task-file
  "The task's `init`/`golden` workbook (`1_<id>_<kind>.xlsx`; a few tasks
   name theirs `initial.xlsx`/`golden.xlsx`)."
  [r id kind]
  (let [f (io/file r "spreadsheet" (str id) (str "1_" id "_" kind ".xlsx"))]
    (if (.exists f) f (io/file r "spreadsheet" (str id) ({"init" "initial.xlsx" "golden" "golden.xlsx"} kind)))))

;; Upstream's workbooks include highly compressible ones that POI's
;; zip-bomb guard (a compression-ratio threshold) refuses.
(ZipSecureFile/setMinInflateRatio 0.001)

(defn tasks
  "The tasks: `[{:id :instruction :type :answer-position :answer-sheet
   :init :golden :exclude}]` (`:init`/`:golden` are files)."
  ([] (tasks (root)))
  ([r]
   (mapv (fn [{:strs [id instruction instruction_type answer_position answer_sheet exclude]}]
           {:id (str id) :instruction instruction :type instruction_type
            :answer-position answer_position :answer-sheet answer_sheet :exclude exclude
            :init (task-file r id "init") :golden (task-file r id "golden")})
         (json/read-value (io/file r "dataset.json")))))

;; ---------------------------------------------------------------------------
;; Answer positions

(defn- strip-quotes [s] (str/replace (str/trim s) #"^['‘’]+|['‘’]+$" ""))

(defn parse-range
  "One `['Sheet'!]A1[:B2]` range as `{:sheet :r0 :c0 :r1 :c1}` (0-based;
   `:sheet` nil when it names none), or nil when it is not one (a whole
   column, a missing row, …: upstream mishandles those, see the survey)."
  [s]
  (let [[sheet ref] (let [i (str/last-index-of s "!")]
                      (if i [(strip-quotes (subs s 0 i)) (subs s (inc i))] [nil s]))
        ref (strip-quotes ref)
        [a b] (str/split ref #":")
        cell (fn [x] (when (and x (re-matches #"[A-Za-z]{1,3}[0-9]+" x))
                       (let [cr (CellReference. (str/upper-case x))] [(.getRow cr) (int (.getCol cr))])))]
    (when-let [[r0 c0] (cell a)]
      (when-let [[r1 c1] (if b (cell b) [r0 c0])]
        {:sheet sheet :r0 (min r0 r1) :c0 (min c0 c1) :r1 (max r0 r1) :c1 (max c0 c1)}))))

(defn answer-ranges
  "The task's answer ranges, or nil when any of them is malformed."
  [{:keys [answer-position]}]
  (let [rs (map parse-range (str/split (str answer-position) #","))]
    (when (and (seq rs) (every? some? rs)) (vec rs))))

(defn cells-of
  "The `[row col]` of a range, column-major (upstream's order)."
  [{:keys [r0 c0 r1 c1]}]
  (for [c (range c0 (inc c1)) r (range r0 (inc r1))] [r c]))

;; ---------------------------------------------------------------------------
;; Values, as upstream compares them

(defn- round2
  "Python's round(x, 2): the exact binary value, half to even."
  [x]
  (-> (BigDecimal. (double x)) (.setScale 2 RoundingMode/HALF_EVEN) .doubleValue))

(defn- round0 [x] (-> (BigDecimal. (double x)) (.setScale 0 RoundingMode/HALF_EVEN) .doubleValue))

(defn- numeric-string [s]
  (try (let [t (str/trim s)]
         (when (re-matches #"[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?" t) (Double/parseDouble t)))
       (catch Exception _ nil)))

(defn transform
  "A cell value `{:kind :num|:str|:bool|:err|:blank :v}` typed by `format`
   (`:date`, `:time` or nil) as upstream's `transform_value` makes it:
   `[:num x]`, `[:str s]` or `[:none]`."
  [{:keys [kind v]} fmt]
  (case kind
    :blank [:none]
    :err [:str (str v)]
    :bool [:num (round2 (if v 1.0 0.0))]
    :num (case fmt
           :date [:num (round0 v)]
           :time (let [secs (Math/round (* 86400.0 (- (double v) (Math/floor (double v)))))]
                   [:str (format "%02d:%02d" (quot (mod secs 86400) 3600) (quot (mod secs 3600) 60))])
           [:num (round2 v)])
    :str (if-let [x (numeric-string v)] [:num (round2 x)] [:str v])))

(defn same?
  "Upstream's `compare_cell_value` on transformed values."
  [a b]
  (let [blankish #(or (= [:none] %) (= [:str ""] %))]
    (or (and (blankish a) (blankish b)) (= a b))))

;; ---------------------------------------------------------------------------
;; Reading workbooks with POI (saved values and formats)

(defn open ^Workbook [file] (with-open [in (io/input-stream file)] (WorkbookFactory/create in)))

(defn- format-of [^Cell c]
  (when (and c (#{CellType/NUMERIC CellType/FORMULA} (.getCellType c)))
    (try
      (when (DateUtil/isCellDateFormatted c)
        (let [f (str/lower-case (.getDataFormatString (.getCellStyle c)))]
          (if (re-find #"[dy]" (str/replace f #"\"[^\"]*\"|\[[^\]]*\]" "")) :date :time)))
      (catch Exception _ nil))))

(defn- saved-value
  "The value Excel saved in `c`: `{:kind :v}`; `{:kind :uncached}` for a
   formula without one. An empty saved value (`<v/>`, as some writers leave
   a formula) is blank, as openpyxl reads it upstream (None), not POI's 0."
  [^Cell c]
  (if (nil? c)
    {:kind :blank}
    (let [t (.getCellType c)
          ct (when (instance? org.apache.poi.xssf.usermodel.XSSFCell c)
               (.getCTCell ^org.apache.poi.xssf.usermodel.XSSFCell c))
          t (if (= CellType/FORMULA t)
              (cond
                (and ct (not (.isSetV ct))) ::uncached
                (and ct (str/blank? (.getV ct))) ::empty
                :else (.getCachedFormulaResultType c))
              t)]
      (condp = t
        ::uncached {:kind :uncached}
        ::empty {:kind :blank}
        CellType/NUMERIC {:kind :num :v (.getNumericCellValue c)}
        CellType/STRING {:kind :str :v (.getStringCellValue c)}
        CellType/BOOLEAN {:kind :bool :v (.getBooleanCellValue c)}
        CellType/ERROR {:kind :err :v (.getString (FormulaError/forInt (.getErrorCellValue c)))}
        {:kind :blank}))))

(defn sheet-index
  "The index of the range's sheet in `wb` (upstream: the first sheet when
   the range names none), or nil when the workbook has no such sheet."
  [^Workbook wb {:keys [sheet]}]
  (if (nil? sheet)
    0
    (let [i (.getSheetIndex wb ^String sheet)] (when (>= i 0) i))))

(defn gold-cells
  "The gold's answer cells: `[{:sheet :row :col :saved :format :formula}]`
   (`:formula` the gold's formula text, if any), or nil when a range's sheet
   is missing."
  [^Workbook wb ranges]
  (let [out (for [rng ranges]
              (when-let [si (sheet-index wb rng)]
                (let [sh (.getSheetAt wb (int si))]
                  (for [[r c] (cells-of rng)
                        :let [row (.getRow sh (int r))
                              ^Cell cell (when row (.getCell row (int c)))]]
                    {:sheet si :row r :col c
                     :saved (saved-value cell)
                     :format (format-of cell)
                     :formula (when (and cell (= CellType/FORMULA (.getCellType cell))) (.getCellFormula cell))
                     ;; a non-anchor cell of an array formula (legacy or a dynamic
                     ;; spill): the anchor's output, not something one writes
                     :array-sibling (boolean (and cell (.isPartOfArrayFormulaGroup cell)
                                                  (let [a (.getArrayFormulaRange cell)]
                                                    (not (and (= r (.getFirstRow a)) (= c (.getFirstColumn a)))))))
                     :array (when (and cell (.isPartOfArrayFormulaGroup cell)
                                       (not (and (instance? org.apache.poi.xssf.usermodel.XSSFCell cell)
                                                 (.isSetCm (.getCTCell ^org.apache.poi.xssf.usermodel.XSSFCell cell)))))
                              (let [a (.getArrayFormulaRange cell)]
                                (when (and (= r (.getFirstRow a)) (= c (.getFirstColumn a)))
                                  [(.getLastRow a) (.getLastColumn a)])))}))))]
    (when (every? some? out) (vec (apply concat out)))))

(defn- as-saved
  "`x` as a spreadsheet saves a formula's result: 15 significant digits.
   Upstream grades saved files, and =D9*1.5 over 5.35 is saved as 8.025
   (above the half, 8.03 at 2 places), not the product's 8.02499999999999857…
   A value written as it is keeps its digits."
  [x]
  (let [x (double x)]
    (if (or (Double/isNaN x) (Double/isInfinite x) (zero? x))
      x
      (.doubleValue (BigDecimal. x (java.math.MathContext. 15))))))

(defn formula-result?
  "Whether cell `id` of rechentafel workbook `wb` holds a formula's result:
   its own formula, or a cell a spill or a legacy array range fills."
  [wb ^long id]
  (let [s (cell/sheet id) r (cell/row id) c (cell/col id)
        in? (fn [anchor r1 c1] (and (= s (cell/sheet anchor))
                                    (<= (cell/row anchor) r (long r1)) (<= (cell/col anchor) c (long c1))))]
    (boolean (or (contains? (:formulas wb) id)
                 (some (fn [[a {:keys [r1 c1 error]}]] (and (not error) (in? a r1 c1))) (:spills wb))
                 (some (fn [[a {:keys [r1 c1]}]] (in? a r1 c1)) (:array-ranges wb))))))

(defn rechentafel-value
  "A rechentafel cell value as `{:kind :v}`; with `formula?`, a number as a
   file saves a formula's result."
  ([v] (rechentafel-value v false))
  ([v formula?]
   (let [t (:t v)]
     (case t
       :num {:kind :num :v (if formula? (as-saved (:v v)) (double (:v v)))}
       :str {:kind :str :v (str (:v v))}
       :bool {:kind :bool :v (boolean (:v v))}
       :err {:kind :err :v (get {:div0 "#DIV/0!" :ref "#REF!" :na "#N/A" :value "#VALUE!" :name "#NAME?"
                                 :num "#NUM!" :null "#NULL!" :spill "#SPILL!" :calc "#CALC!"}
                                (:v v) (str "#" (name (:v v))))}
       {:kind :blank}))))
