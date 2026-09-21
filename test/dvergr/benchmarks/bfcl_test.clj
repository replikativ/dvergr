(ns dvergr.benchmarks.bfcl-test
  "BFCL transcription against the pinned upstream revision.

   The digests below are of UPSTREAM's outputs (`dev/benchmarks/bfcl/oracle.py`
   running upstream's own checker and tool compiler), so these tests prove
   equivalence without Python. To re-pin after an upstream bump: regenerate
   the corpus (`equivalence/write-corpus!`), run `oracle.py <root> check` and
   `oracle.py <root> tools`, and take `equivalence/verdict-digest` of the
   oracle's verdicts."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dvergr.benchmarks.bfcl.core :as bfcl]
            [dvergr.benchmarks.bfcl.equivalence :as eq]
            [dvergr.benchmarks.tau2.pyjson :as pj]))

(def ^:private categories (vec (sort (keys bfcl/categories))))

(defn- upstream-present? []
  (.exists (io/file bfcl/default-root (:path bfcl/upstream) "bfcl_eval" "data")))

;; oracle.py check, over the corpus of all eleven categories (37403 cases,
;; 14149 of them valid)
(def ^:private oracle-verdict-digest
  "ddab7c93abff95b6762fa9c71ae8077a0d203c318bab4b8da6adc0c59a66266e")
(def ^:private corpus-digest
  "c4a33d82ef6038cb865e0d3e7227b58f4ee8eaf2da038d59985b960dd7f99dfc")
;; oracle.py tools, over all 3491 tasks
(def ^:private oracle-tools-digest
  "f5d212ec48b73100b23803036ed89cb0c71742b63bdc554039b150dba3bfff36")

(deftest data-is-the-pinned-revision
  (when (upstream-present?)
    (let [counts (into {} (map (fn [c] [c (count (bfcl/load-category c))])) categories)]
      (is (= {"simple_python" 400 "multiple" 200 "parallel" 200 "parallel_multiple" 200
              "irrelevance" 240 "live_simple" 258 "live_multiple" 1053 "live_parallel" 16
              "live_parallel_multiple" 24 "live_irrelevance" 884 "live_relevance" 16}
             counts))
      (is (= 3491 (reduce + (vals counts)))))))

(deftest every-task-but-the-known-faults-has-a-valid-answer
  (when (upstream-present?)
    (let [rejected (for [category categories
                         task (bfcl/load-category category)
                         :when (= :ast (:kind task))
                         :when (not (:valid (bfcl/grade task (eq/gold-calls task))))]
                     (:id task))]
      (is (= bfcl/unsatisfiable (set rejected))))))

(deftest the-checker-gives-upstreams-verdicts
  (when (upstream-present?)
    (let [corpus (eq/corpus categories {})
          path (java.io.File/createTempFile "bfcl-corpus" ".jsonl")]
      (try
        (eq/write-corpus! path corpus)
        (testing "the corpus is the one the oracle graded"
          (is (= 37403 (count corpus)))
          (is (= corpus-digest (pj/sha256-hex (slurp path)))))
        (testing "and every verdict and error type is upstream's"
          (is (= oracle-verdict-digest (eq/verdict-digest (eq/verdicts corpus {})))))
        (finally (.delete path))))))

(deftest tools-are-compiled-as-upstream-compiles-them
  (when (upstream-present?)
    (is (= oracle-tools-digest
           (pj/sha256-hex
            (str/join "\n"
                      (for [category categories
                            task (bfcl/load-category category)]
                        (str (:id task) "\t" (pj/dumps (bfcl/compile-tools (:functions task)) true)))))))))

(deftest python-semantics-the-verdicts-depend-on
  (let [function {"name" "f" "parameters" {"type" "dict" "required" ["n"]
                                           "properties" {"n" {"type" "integer"}
                                                         "x" {"type" "float"}
                                                         "s" {"type" "string"}
                                                         "b" {"type" "boolean"}}}}
        task (fn [answers] {:kind :ast :category "simple_python" :functions [function]
                            :ground-truth [{"f" answers}]})
        verdict (fn [answers args] (select-keys (bfcl/grade (task answers) [{"f" args}])
                                                [:valid :error-type]))]
    (testing "bool is not int"
      (is (= {:valid false :error-type "type_error:simple"}
             (verdict {"n" [1]} {"n" true}))))
    (testing "an int is accepted where a float is expected, and compared by value"
      (is (= {:valid true :error-type "simple_function_checker:unclear"}
             (verdict {"n" [1] "x" [2.0]} {"n" 1 "x" 2}))))
    (testing "strings are compared without case, spaces and punctuation"
      (is (:valid (verdict {"n" [1] "s" ["April 1, 2024"]} {"n" 1 "s" "april 1,2024"}))))
    (testing "an optional parameter may be left out, a required one may not"
      (is (:valid (verdict {"n" [1] "s" ["a" ""]} {"n" 1})))
      (is (= "simple_function_checker:missing_optional"
             (:error-type (verdict {"n" [1] "s" ["a"]} {"n" 1}))))
      (is (= "simple_function_checker:missing_required"
             (:error-type (verdict {"n" [1]} {"s" "a"})))))
    (testing "a string where a number is expected is a variable when the answers say so"
      (is (:valid (verdict {"n" ["count"]} {"n" "count"}))))))

(deftest relevance-is-about-whether-anything-was-called
  (let [irrelevant {:kind :irrelevance :category "irrelevance"}
        relevant {:kind :relevance :category "live_relevance"}]
    (is (:valid (bfcl/grade irrelevant nil)))
    (is (:valid (bfcl/grade irrelevant [])))
    (is (:valid (bfcl/grade irrelevant [{}])))
    (is (not (:valid (bfcl/grade irrelevant [{"f" {"x" 1}}]))))
    (is (:valid (bfcl/grade relevant [{"f" {"x" 1}}])))
    (is (not (:valid (bfcl/grade relevant []))))))
