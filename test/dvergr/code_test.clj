(ns dvergr.code-test
  "Tests for the Clojure → categorical-data mapping: the strict round-trip iso
   and the derived var/ref index (doc/programmatic-knowledge.md §7b)."
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.code :as code]
            [sci.core :as sci]))

(def sample
  ;; deliberately full of cosmetics: comments, blank lines, odd indentation
  "(ns demo.core)\n\n;; a greeting\n(defn greet\n  \"say hi\"\n  [name]\n  (str \"Hello, \" name))\n\n\n(def n 3)   ; trailing comment\n")

(deftest round-trip-is-byte-faithful
  (testing "parse-blocks → materialize preserves source exactly (whitespace, comments)"
    (is (code/faithful? sample))
    (is (= sample (code/materialize (code/parse-blocks sample))))
    (testing "even pathological spacing/comments"
      (doseq [s ["  (+ 1   2)  ;c\n\n\n"
                 "#_(skipped) (real)\n"
                 "(let [x 1\n      y 2] (+ x y))\n"]]
        (is (code/faithful? s) (str "round-trip failed for: " (pr-str s)))))))

(deftest blocks-preserve-order-and-kind
  (let [bs (code/parse-blocks sample)]
    (is (= sample (apply str (map :source bs))) "concatenation is the source")
    (is (some #(= :trivia (:kind %)) bs) "trivia blocks present")
    (is (= 3 (count (filter #(= :form (:kind %)) bs))) "ns + defn + def")))

(deftest references-distinguish-locals-from-vars
  (testing "a defn: defines a var, references vars (Σ), introduces locals (Γ)"
    (let [r (code/references '(defn greet [name] (str "Hello, " name)))]
      (is (= 'greet (:defines r)))
      (is (contains? (set (:vars r)) 'clojure.core/str) "Σ reference")
      (is (= '[name] (:locals r)) "Γ binding, gensym suffix stripped")))
  (testing "a plain def with no refs"
    (let [r (code/references '(def n 3))]
      (is (= 'n (:defines r)))
      (is (empty? (:vars r))))))

(deftest index-source-builds-the-def-graph
  (let [{:keys [blocks defs]} (code/index-source sample)]
    (is (= sample (code/materialize blocks)) "blocks still round-trip")
    (let [by-name (into {} (map (juxt :defines identity)) defs)]
      (is (contains? by-name 'greet))
      (is (contains? (set (:vars (by-name 'greet))) 'clojure.core/str)
          "greet → str edge in the reference graph"))))

(def ^:private ns-base
  "(ns demo.core)\n\n(defn a [x] (* 2 x))\n\n(defn b [y] (+ y 1))\n")

(deftest diff-source-reports-the-minimal-per-def-change-set
  (testing "editing one def → just that def is :modified"
    (let [d (code/diff-source ns-base
                              "(ns demo.core)\n\n(defn a [x] (* 3 x))\n\n(defn b [y] (+ y 1))\n")]
      (is (= [] (mapv :key (:added d))))
      (is (= [] (:removed d)))
      (is (= '[a] (mapv :key (:modified d))))
      (is (= "(defn a [x] (* 2 x))" (:old (first (:modified d)))))
      (is (= "(defn a [x] (* 3 x))" (:new (first (:modified d)))))))
  (testing "adding and removing defs"
    (let [d (code/diff-source ns-base
                              "(ns demo.core)\n\n(defn a [x] (* 2 x))\n\n(defn c [] :c)\n")]
      (is (= '[c] (mapv :key (:added d))))
      (is (= '[b] (:removed d)))
      (is (= [] (:modified d)))))
  (testing "identity diff is empty"
    (is (every? empty? (vals (code/diff-source ns-base ns-base))))))

(deftest diff-source-reflects-text-state-at-def-granularity
  (testing "inter-def whitespace (layout, lives in the text) transacts nothing"
    (let [d (code/diff-source ns-base
                              "(ns demo.core)\n\n\n(defn a [x] (* 2 x))\n\n\n(defn b [y] (+ y 1))\n")]
      (is (every? empty? (vals d))
          "blank lines between forms are not part of the per-def projection")))
  (testing "intra-form reformatting IS a textual change to that def → it is :modified"
    (let [d (code/diff-source ns-base
                              "(ns demo.core)\n\n(defn a\n  [x]\n  (* 2 x))\n\n(defn b [y] (+ y 1))\n")]
      (is (= '[a] (mapv :key (:modified d)))))))

(deftest references-in-ctx-resolves-against-the-sandbox
  (testing "agent code is analyzed against the SCI ctx's signature + macros, not the host"
    (let [ctx (sci/init {})]
      (sci/eval-string* ctx "(ns kb) (defn double [x] (* 2 x))")
      (sci/eval-string* ctx "(in-ns 'user) (require '[kb])")
      (sci/eval-string* ctx "(def threshold 10)")
      (sci/eval-string* ctx "(defmacro twice [x] (list 'do x x))") ; a SANDBOX macro the host JVM lacks
      (let [r  (code/references-in-ctx ctx
                                       '(defn score [items]
                                          (let [n (count items)]
                                            (twice (when (> n threshold) (kb/double n))))))
            vs (set (:vars r))]
        (is (= 'score (:defines r)))
        (is (= '#{items n} (set (:locals r))) "lexical bindings (Γ)")
        (is (contains? vs 'user/threshold) "user var resolved against the sandbox")
        (is (contains? vs 'clojure.core/count))
        (is (contains? vs 'kb/double)
            "library var resolved against the sandbox, AND seen through the expanded `twice` macro")))))
