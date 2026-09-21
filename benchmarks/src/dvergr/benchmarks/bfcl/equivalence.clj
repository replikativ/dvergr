(ns dvergr.benchmarks.bfcl.equivalence
  "Equivalence of the transcribed BFCL checker with upstream's.

   A *corpus* is a deterministic set of candidate answers for every task: the
   gold answer built from the task's possible answers, and seeded mutations of
   it that a model plausibly (or implausibly) emits: another accepted value, a
   wrong type, a reformatted string, a missing or an unexpected parameter, a
   wrong or renamed function, reordered, dropped or duplicated calls, no call.
   `benchmarks/dev/bfcl/oracle.py` grades the corpus with upstream's own code;
   `compare` grades it here and reports every disagreement.

   The test suite does not need Python: it regenerates the corpus, grades it
   here, and compares `verdict-digest` with the digest of the oracle's
   verdicts pinned in the test."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [dvergr.benchmarks.bfcl.core :as bfcl]
            [dvergr.benchmarks.pyjson :as pj])
  (:import [java.util Random]))

;; ---------------------------------------------------------------------------
;; Gold answers

(defn- optional-marker? [answer] (= "" answer))

(defn- realize-answer
  "An accepted value as a model would emit it. For a `dict` parameter an
   accepted value is `{key [accepted values]}`, not a dict to emit; for an
   array of dicts, a list of those."
  [pick details answer]
  (let [dict-value (fn [d]
                     (reduce (fn [m [k answers]]
                               (let [a (pick answers)]
                                 (if (optional-marker? a) m (assoc m k a))))
                             (array-map) d))]
    (cond
      (and (= "dict" (get details "type")) (map? answer)) (dict-value answer)
      (and (#{"array" "tuple"} (get details "type"))
           (= "dict" (get-in details ["items" "type"]))
           (sequential? answer) (every? map? answer))
      (mapv dict-value answer)
      :else answer)))

(defn gold-call
  "One accepted call for a ground-truth entry `{function {param [answers]}}`,
   with the tool name a provider sees. `pick` chooses among the accepted
   values of a parameter; choosing the optional marker omits the parameter."
  [functions ground-truth-entry pick]
  (let [[function params] (first ground-truth-entry)
        doc (some #(when (= function (get % "name")) %) functions)
        details (get-in doc ["parameters" "properties"])
        required (set (get-in doc ["parameters" "required"]))]
    {(bfcl/tool-name function)
     (reduce (fn [args [param answers]]
               (let [answer (pick answers)
                     optional? (some optional-marker? answers)]
                 (cond
                   ;; upstream data: an accepted value for a parameter the
                   ;; function does not have can only be left out
                   (and optional? (not (contains? details param))) args
                   ;; ... and a required parameter whose only accepted value is
                   ;; the optional marker can only be empty (a string, a list)
                   (and (optional-marker? answer) (required param))
                   (assoc args param (if (#{"array" "tuple"} (get-in details [param "type"])) [] ""))
                   (optional-marker? answer) args
                   :else (assoc args param (realize-answer pick (get details param) answer)))))
             (array-map)
             params)}))

(defn gold-calls
  "The first accepted value of every parameter."
  [task]
  (mapv #(gold-call (:functions task) %
                    ;; `false` is an accepted value, so no `or`
                    (fn [answers] (let [accepted (remove optional-marker? answers)]
                                    (if (seq accepted) (first accepted) ""))))
        (:ground-truth task)))

;; ---------------------------------------------------------------------------
;; Mutations

(defn- pick [^Random rng xs]
  (if (empty? xs) "" (nth (vec xs) (.nextInt rng (count xs)))))

(defn- reformat-string [^Random rng ^String s]
  (case (.nextInt rng 5)
    0 (str/upper-case s)
    1 (str/replace s " " "  ")
    2 (str/replace s #"[,.]" "")
    3 (str/replace s "_" "-")
    4 (str s " ")))

(defn- mutate-value [^Random rng v]
  (cond
    (boolean? v) (pick rng [(not v) (if v 1 0) (str v)])
    (integer? v) (pick rng [(str v) (double v) (inc v) (= 1 v) [v]])
    (float? v) (pick rng [(str v) (long v) (+ v 0.5) [v]])
    (string? v) (pick rng [(reformat-string rng v) (str v "x") 7 [v] ""])
    (map? v) (if (empty? v)
               {"unexpected" 1}
               (let [k (pick rng (keys v))]
                 (pick rng [(dissoc v k)
                            (assoc v "unexpected" 1)
                            (update v k #(mutate-value rng %))])))
    (sequential? v) (if (empty? v)
                      [1]
                      (let [i (.nextInt rng (count v))]
                        (pick rng [(vec (rest v))
                                   (vec (reverse v))
                                   (update (vec v) i #(mutate-value rng %))
                                   (conj (vec v) (first v))
                                   (first v)])))
    :else "x"))

(defn- mutate-call [^Random rng call]
  (let [[function args] (first call)]
    (case (.nextInt rng 10)
      0 (if (empty? args)
          {function {"unexpected" 1}}
          {function (dissoc args (pick rng (keys args)))})
      1 {function (assoc args "unexpected_param" 1)}
      2 {(str function "_x") args}
      3 {(str/replace function "_" ".") args}
      ;; 4 to 9: a value, the branches with the most logic behind them
      (if (empty? args)
        {function {"unexpected" "x"}}
        (let [k (pick rng (keys args))]
          {function (update args k #(mutate-value rng %))})))))

(defn- mutate-calls [^Random rng task calls]
  (case (.nextInt rng 14)
    0 []
    1 (vec (rest calls))
    2 (conj (vec calls) (first calls))
    3 (vec (reverse calls))
    4 [{}]
    (5 6) (mapv #(gold-call (:functions task) % (fn [answers] (pick rng answers))) (:ground-truth task))
    ;; 7 to 13: one call, possibly mutated twice
    (if (empty? calls)
      []
      (let [i (.nextInt rng (count calls))
            once (update (vec calls) i #(mutate-call rng %))]
        (if (zero? (.nextInt rng 3))
          (update once i #(mutate-call rng %))
          once)))))

(def ^:private unrelated-call {"some_function" {"x" 1}})

(defn cases
  "The corpus for `task`: `[{\"case\" \"category\" \"id\" \"calls\"}]`. Tasks
   without possible answers (relevance and irrelevance) get the shapes that
   matter there."
  [task mutations seed]
  (let [rng (Random. (long (+ seed (hash (:id task)))))
        base {"category" (:category task) "id" (:id task)}
        named (fn [i calls] (assoc base "case" (str (:id task) "#" i) "calls" calls))]
    (if (= :ast (:kind task))
      (let [gold (gold-calls task)]
        (into [(named "gold" gold)]
              (map (fn [i] (named i (mutate-calls rng task gold))))
              (range mutations)))
      (map-indexed named [[] [{}] [unrelated-call] [unrelated-call unrelated-call]
                          "a text answer" [{"f" 1}]]))))

(defn corpus
  "Every case of `categories`, in order."
  [categories {:keys [mutations seed root] :or {mutations 12 seed 20260921}}]
  (into []
        (mapcat (fn [category]
                  (mapcat #(cases % mutations seed)
                          (bfcl/load-category category (when root {:root root})))))
        categories))

(defn write-corpus! [path corpus]
  (with-open [w (io/writer path)]
    (doseq [c corpus]
      (.write w (pj/dumps c))
      (.write w "\n"))))

;; ---------------------------------------------------------------------------
;; Grading here, and comparing

(defn verdicts
  "`[[case valid? error-type]]` of the transcription for `corpus`."
  [corpus {:keys [root]}]
  (let [tasks (memoize (fn [category]
                         (into {} (map (juxt :id identity))
                               (bfcl/load-category category (when root {:root root})))))]
    (mapv (fn [{:strs [case category id calls]}]
            (let [{:keys [valid error-type]} (bfcl/grade (get (tasks category) id)
                                                         (when (sequential? calls) calls))]
              [case (boolean valid) (when-not valid error-type)]))
          corpus)))

(defn verdict-digest
  "A digest of verdicts `[[case valid? error-type]]`, the same whoever graded."
  [verdicts]
  (pj/sha256-hex (str/join "\n" (map (fn [[c v e]] (str c "\t" v "\t" e)) verdicts))))

(defn oracle-verdicts
  "Verdicts from the oracle's output file (one JSON object per line)."
  [path]
  (into []
        (comp (remove str/blank?)
              (map pj/parse)
              (map (fn [{:strs [case valid error_type]}] [case (boolean valid) error_type])))
        (str/split-lines (slurp path))))

(defn compare-verdicts
  "Disagreements between the transcription and the oracle:
   `{:cases n :mismatches [{:case :ours :theirs}]}`."
  [ours theirs]
  (let [by-case (into {} (map (juxt first identity)) theirs)]
    {:cases (count ours)
     :oracle-cases (count theirs)
     :mismatches (into []
                       (keep (fn [[c :as verdict]]
                               (let [oracle (get by-case c)]
                                 (when (not= verdict oracle)
                                   {:case c :ours (rest verdict) :theirs (rest oracle)}))))
                       ours)}))
