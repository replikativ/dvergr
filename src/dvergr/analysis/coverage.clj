(ns dvergr.analysis.coverage
  "Static test coverage analysis - matches functions in src/ to tests in test/.

   This provides a quick overview of what has tests without running actual tests.
   For runtime coverage, use kaocha-cloverage."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [dvergr.tools.code-analyzer :as analyzer]))

;; ============================================================================
;; File Discovery
;; ============================================================================

(defn find-clojure-files
  "Find all .clj files in a directory tree.

   Args:
     path - Root directory path

   Returns:
     Vector of File objects"
  [path]
  (let [dir (io/file path)]
    (if (.exists dir)
      (->> (file-seq dir)
           (filter #(.isFile %))
           (filter #(str/ends-with? (.getName %) ".clj"))
           vec)
      [])))

(defn namespace-from-file
  "Extract namespace from a Clojure file path.

   Args:
     file - File object
     root-path - Root directory (e.g., 'src' or 'test')

   Returns:
     Namespace symbol or nil"
  [file root-path]
  (let [path (.getPath file)
        rel-path (str/replace path (str root-path "/") "")
        ns-path (-> rel-path
                    (str/replace "/" ".")
                    (str/replace "_" "-")
                    (str/replace ".clj" ""))]
    (when (seq ns-path)
      (symbol ns-path))))

;; ============================================================================
;; Function Extraction
;; ============================================================================

(defn extract-defns
  "Extract all defn/defn- names from a file using rewrite-clj.

   Args:
     file - File object

   Returns:
     Set of function name symbols"
  [file]
  (try
    (let [content (slurp file)
          defs (analyzer/extract-all-defs content)]
      (->> defs
           (filter #(contains? #{:defn :defmacro} (:type %)))
           (map :name)
           set))
    (catch Exception e
      #{})))

(defn extract-test-names
  "Extract all deftest names from a test file.

   Args:
     file - File object

   Returns:
     Set of test name symbols"
  [file]
  (try
    (let [content (slurp file)
          defs (analyzer/extract-all-defs content)]
      (->> defs
           (filter #(= :deftest (:type %)))
           (map :name)
           set))
    (catch Exception e
      #{})))

;; ============================================================================
;; Coverage Analysis
;; ============================================================================

(defn match-test-file
  "Find the corresponding test file for a source file.

   Args:
     src-file - Source File object
     test-dir - Test directory path (e.g., 'test')

   Returns:
     Test File object or nil"
  [src-file test-dir]
  (let [src-path (.getPath src-file)
        ;; Extract relative path from src/
        parts (str/split src-path #"/src/")
        rel-path (when (> (count parts) 1) (second parts))]
    (when rel-path
      (let [test-path (str test-dir "/" (str/replace rel-path ".clj" "_test.clj"))
            test-file (io/file test-path)]
        (when (.exists test-file)
          test-file)))))

(defn analyze-file-coverage
  "Analyze coverage for a single source file.

   Args:
     src-file - Source File object
     test-dir - Test directory path

   Returns:
     Map with:
       :namespace - Namespace symbol
       :file - Source file path
       :functions - Set of function names
       :test-file - Test file path (or nil)
       :tests - Set of test names
       :coverage - :full, :partial, or :none"
  [src-file test-dir]
  (let [ns-name (namespace-from-file src-file "src")
        functions (extract-defns src-file)
        test-file (match-test-file src-file test-dir)
        tests (if test-file (extract-test-names test-file) #{})
        coverage (cond
                   (nil? test-file) :none
                   (empty? tests) :none
                   (= (count tests) (count functions)) :full
                   (> (count tests) 0) :partial
                   :else :none)]
    {:namespace ns-name
     :file (.getPath src-file)
     :functions functions
     :function-count (count functions)
     :test-file (when test-file (.getPath test-file))
     :tests tests
     :test-count (count tests)
     :coverage coverage}))

(defn analyze-coverage
  "Analyze test coverage for entire project.

   Args:
     src-dir - Source directory path (default 'src')
     test-dir - Test directory path (default 'test')

   Returns:
     Map with:
       :files - Vector of per-file coverage analysis
       :summary - Overall statistics"
  ([] (analyze-coverage "src" "test"))
  ([src-dir test-dir]
   (let [src-files (find-clojure-files src-dir)
         analyses (vec (map #(analyze-file-coverage % test-dir) src-files))
         total-files (count analyses)
         files-with-tests (count (filter #(not= (:coverage %) :none) analyses))
         total-functions (reduce + 0 (map :function-count analyses))
         total-tests (reduce + 0 (map :test-count analyses))
         full-coverage (count (filter #(= (:coverage %) :full) analyses))
         partial-coverage (count (filter #(= (:coverage %) :partial) analyses))
         no-coverage (count (filter #(= (:coverage %) :none) analyses))]
     {:files analyses
      :summary {:total-files total-files
                :files-with-tests files-with-tests
                :total-functions total-functions
                :total-tests total-tests
                :full-coverage full-coverage
                :partial-coverage partial-coverage
                :no-coverage no-coverage
                :coverage-percentage (if (pos? total-files)
                                       (* 100.0 (/ files-with-tests total-files))
                                       0.0)}})))

(defn find-untested-functions
  "Find all functions that don't have obvious corresponding tests.

   Args:
     src-dir - Source directory path (default 'src')
     test-dir - Test directory path (default 'test')

   Returns:
     Vector of {:namespace ns :function fn-name :file path}"
  ([] (find-untested-functions "src" "test"))
  ([src-dir test-dir]
   (let [analysis (analyze-coverage src-dir test-dir)]
     (->> (:files analysis)
          (filter #(not= (:coverage %) :full))
          (mapcat (fn [file-analysis]
                    (let [tested-fns (->> (:tests file-analysis)
                                          (map name)
                                          (map #(str/replace % #"-test$" ""))
                                          set)]
                      (->> (:functions file-analysis)
                           (remove #(contains? tested-fns (name %)))
                           (map (fn [fn-name]
                                  {:namespace (:namespace file-analysis)
                                   :function fn-name
                                   :file (:file file-analysis)}))))))
          vec))))

;; ============================================================================
;; Reporting
;; ============================================================================

(defn print-coverage-report
  "Print human-readable coverage report.

   Args:
     src-dir - Source directory path (default 'src')
     test-dir - Test directory path (default 'test')"
  ([] (print-coverage-report "src" "test"))
  ([src-dir test-dir]
   (let [analysis (analyze-coverage src-dir test-dir)
         {:keys [files summary]} analysis]
     (println "\n" (apply str (repeat 60 "=")) "\n")
     (println " TEST COVERAGE ANALYSIS (Static)\n")
     (println (apply str (repeat 60 "=")) "\n")

     ;; Summary
     (println "Summary:")
     (println (str "  Total Files:       " (:total-files summary)))
     (println (str "  Files with Tests:  " (:files-with-tests summary)
                   " (" (format "%.1f%%" (:coverage-percentage summary)) ")"))
     (println (str "  Total Functions:   " (:total-functions summary)))
     (println (str "  Total Tests:       " (:total-tests summary)))
     (println)

     (println "Coverage Breakdown:")
     (println (str "  Full Coverage:     " (:full-coverage summary) " files"))
     (println (str "  Partial Coverage:  " (:partial-coverage summary) " files"))
     (println (str "  No Coverage:       " (:no-coverage summary) " files"))
     (println)

     ;; Files needing tests
     (let [needs-tests (filter #(= (:coverage %) :none) files)]
       (when (seq needs-tests)
         (println (str "Files Needing Tests (" (count needs-tests) " total):"))
         (doseq [f (take 10 needs-tests)]
           (println (str "  " (:namespace f) " (" (:function-count f) " functions)")))
         (when (> (count needs-tests) 10)
           (println (str "  ... and " (- (count needs-tests) 10) " more")))
         (println)))

     ;; Partial coverage files
     (let [partial (filter #(= (:coverage %) :partial) files)]
       (when (seq partial)
         (println (str "Partially Tested Files (" (count partial) " total):"))
         (doseq [f (take 5 partial)]
           (println (str "  " (:namespace f)
                         ": " (:test-count f) "/" (:function-count f) " functions")))
         (when (> (count partial) 5)
           (println (str "  ... and " (- (count partial) 5) " more")))
         (println)))

     (println (apply str (repeat 60 "=")) "\n"))))

(defn suggest-test-cases
  "Suggest test cases for untested functions.

   Args:
     namespace-sym - Namespace symbol to analyze
     src-dir - Source directory path (default 'src')
     test-dir - Test directory path (default 'test')

   Returns:
     String with suggested test code"
  ([namespace-sym] (suggest-test-cases namespace-sym "src" "test"))
  ([namespace-sym src-dir test-dir]
   (let [untested (find-untested-functions src-dir test-dir)
         ns-untested (filter #(= (:namespace %) namespace-sym) untested)]
     (if (empty? ns-untested)
       (str "No untested functions found in " namespace-sym)
       (str "(ns " namespace-sym "-test\n"
            "  (:require [clojure.test :refer [deftest is testing]]\n"
            "            [" namespace-sym " :as sut]))\n\n"
            (str/join "\n\n"
                      (map (fn [{:keys [function]}]
                             (str "(deftest " function "-test\n"
                                  "  (testing \"" function "\"\n"
                                  "    ;; TODO: Add test cases\n"
                                  "    (is (= true true))))"))
                           ns-untested)))))))

;; ============================================================================
;; Quick Checks
;; ============================================================================

(defn has-tests?
  "Quick check if a namespace has tests.

   Args:
     namespace-sym - Namespace symbol
     test-dir - Test directory path (default 'test')

   Returns:
     Boolean"
  ([namespace-sym] (has-tests? namespace-sym "test"))
  ([namespace-sym test-dir]
   (let [ns-path (-> (str namespace-sym)
                     (str/replace "." "/")
                     (str/replace "-" "_"))
         test-file (io/file (str test-dir "/" ns-path "_test.clj"))]
     (.exists test-file))))

(defn coverage-stats
  "Get quick coverage statistics.

   Args:
     src-dir - Source directory path (default 'src')
     test-dir - Test directory path (default 'test')

   Returns:
     Map with :files, :with-tests, :percentage"
  ([] (coverage-stats "src" "test"))
  ([src-dir test-dir]
   (let [analysis (analyze-coverage src-dir test-dir)
         summary (:summary analysis)]
     {:files (:total-files summary)
      :with-tests (:files-with-tests summary)
      :percentage (:coverage-percentage summary)})))
