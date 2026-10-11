(ns dvergr.catalog.casepack-cli
  "A case pack from the shell: a table of historical cases into a bundle
   directory that `dvergr.catalog.room-run` benchmarks.

     clojure -M -m dvergr.catalog.casepack-cli cases.csv \\
       --id id --inputs description,supplier_country,net_amount \\
       --outcome vat_code:exact --outcome vat_amount:number:0.01 \\
       --out packs/vat-codes

   `--outcome column:rule[:tolerance]` names a column the answer must get
   right (rules: exact, ci, number, set, date); give it once per column. The
   bundle's name is the directory's (`--out`). The certification summary is
   printed; `certification.edn` in the bundle lists every excluded case and
   why. The same as `catalog_cases` over MCP (`casepack/from-table` and
   `casepack/write-dir!`)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.cli :as cli]
            [dvergr.catalog.casepack :as casepack]))

(def ^:private rules #{"exact" "ci" "number" "set" "date"})

(defn- parse-outcome [s]
  (let [[col rule tol] (str/split s #":")]
    (when (or (str/blank? col) (not (contains? rules (or rule "exact"))))
      (throw (IllegalArgumentException. (str "--outcome is column:rule[:tolerance], rule one of "
                                             (str/join ", " (sort rules)) ": " s))))
    [col (cond-> {:rule (keyword (or rule "exact"))}
           tol (assoc :tolerance (Double/parseDouble tol)))]))

(def ^:private options
  [[nil "--id COLUMN" "The column naming a case"]
   [nil "--inputs COLUMNS" "Comma-separated columns a case's /docs/case.edn holds"
    :parse-fn #(str/split % #",")]
   [nil "--outcome COLUMN:RULE[:TOL]" "A column the answer must get right (repeatable)"
    :parse-fn parse-outcome :multi true :default [] :update-fn conj]
   [nil "--attachments COLUMNS" "Comma-separated columns naming text files beside the table"
    :parse-fn #(str/split % #",")]
   [nil "--out DIR" "The bundle directory to write (its name is the bundle's)"]
   [nil "--title TITLE" "The workflow's title"]
   [nil "--task TEXT" "What to do with a case (the answer contract is appended)"]
   [nil "--delimiter CHAR" "The CSV delimiter (default: , ; or tab, whichever the header has most)"]
   ["-h" "--help"]])

(defn run
  "Write the pack the arguments describe; `{:exit n :lines [...]}`."
  [args]
  (let [{:keys [options arguments errors summary]} (cli/parse-opts args options)
        [table] arguments
        usage (str "Usage: casepack-cli <table.csv|.jsonl|.edn> --id COL --inputs A,B "
                   "--outcome COL:RULE [--outcome …] --out DIR [options]\n" summary)
        missing (cond-> []
                  (not table) (conj "the table")
                  (not (:id options)) (conj "--id")
                  (empty? (:inputs options)) (conj "--inputs")
                  (empty? (:outcome options)) (conj "--outcome")
                  (not (:out options)) (conj "--out"))
        out (some-> (:out options) io/file .getAbsoluteFile)
        name (some-> out .getName)]
    (cond
      (:help options) {:exit 0 :lines [usage]}
      (seq errors) {:exit 1 :lines (conj (vec errors) usage)}
      (seq missing) {:exit 1 :lines [(str "Missing: " (str/join ", " missing)) usage]}
      (not (.isFile (io/file table))) {:exit 1 :lines [(str "No table " table)]}
      (not (re-matches #"[a-z0-9][a-z0-9-]*" name))
      {:exit 1 :lines [(str "The --out directory's name is the bundle's: lowercase letters, digits and dashes, not " name)]}
      (and (.exists out) (seq (.list out)))
      {:exit 1 :lines [(str out " is not empty; give a new directory")]}
      :else
      (let [spec (cond-> {:name name :id (:id options) :inputs (:inputs options)
                          :expected (into {} (:outcome options))}
                   (:attachments options) (assoc :attachments (:attachments options))
                   (:title options) (assoc :title (:title options))
                   (:task options) (assoc :task (:task options)))
            {:keys [files certification] :as pack}
            (casepack/from-table spec table (when-let [d (:delimiter options)] {:delimiter (first d)}))
            summary (str (:certified certification) " of " (:cases certification) " cases certified"
                         (when-let [by (seq (dissoc (:by-reason certification) :certified))]
                           (str "; excluded: " (str/join ", " (for [[r n] by] (str n " " (clojure.core/name r)))))))]
        (if-not files
          {:exit 1 :lines [summary "No case can grade an answer: nothing written."]}
          (do (casepack/write-dir! out pack)
              {:exit 0 :lines [summary
                               (str "Wrote " out " (certification.edn lists each excluded case)")
                               (str "Benchmark it: clojure -M -m dvergr.catalog.room-run " out " --models <m1,m2>")]}))))))

(defn -main [& args]
  (let [{:keys [exit lines]} (try (run args)
                                  (catch IllegalArgumentException e {:exit 1 :lines [(.getMessage e)]}))]
    (binding [*out* (if (zero? exit) *out* *err*)]
      (run! println lines))
    (shutdown-agents)
    (System/exit exit)))
