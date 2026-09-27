(ns contract-review.checker
  "Contract review against CUAD's lawyers' annotations. Per clause type: 0 for
   the wrong presence; 1 for a clause correctly absent; for a clause present,
   1 when the quote matches an annotated span, 0.5 when it names the clause
   but quotes something else. The reward is the mean over the clause types.

   A quote matches a span when their tokens overlap (F1 at least 0.5), when
   it contains the span (80% of the span's tokens, in at most 200 tokens: a
   whole numbered section, not the contract), or when it lies inside the span
   (90% of its at least 8 tokens: one item of a long annotated list). CUAD
   annotates sentences; a reviewer quotes the clause, whose bounds differ."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

(defn- tokens [s]
  (remove str/blank? (str/split (str/lower-case (str s)) #"[^a-z0-9]+")))

(defn- f1 [a b]
  (let [fa (frequencies (tokens a)) fb (frequencies (tokens b))
        common (reduce + (map (fn [[t n]] (min n (get fb t 0))) fa))
        na (reduce + (vals fa)) nb (reduce + (vals fb))]
    (if (or (zero? common) (zero? na) (zero? nb))
      0.0
      (let [p (/ common na) r (/ common nb)]
        (double (/ (* 2 p r) (+ p r)))))))

(defn- overlap
  "`[common quote-tokens span-tokens]` of `quote` and `span`."
  [quote span]
  (let [fq (frequencies (tokens quote)) fs (frequencies (tokens span))]
    [(reduce + (map (fn [[t n]] (min n (get fq t 0))) fs))
     (reduce + (vals fq)) (reduce + (vals fs))]))

(defn- matches? [quote span]
  (let [[common nq ns] (overlap quote span)]
    (or (<= 0.5 (f1 quote span))
        (and (pos? ns) (<= 0.8 (/ common ns)) (<= nq 200))
        (and (<= 8 nq) (<= 0.9 (/ common nq))))))

(defn- review [files]
  (try (let [v (edn/read-string (get files "/out/review.edn" ""))]
         (when (map? v) v))
       (catch Exception _ nil)))

(defn- score [{:keys [present spans]} answer]
  (let [said (boolean (:present answer))]
    (cond
      (not= present said) 0.0
      (not present) 1.0
      (some #(matches? (:quote answer) %) spans) 1.0
      :else 0.5)))

(defn check [{:keys [files gold]}]
  (let [r (review files)
        clauses (:clauses gold)
        scores (into {} (for [[k g] clauses] [k (score g (get r k))]))
        presence-right (count (filter (fn [[k g]] (= (:present g) (boolean (:present (get r k))))) clauses))]
    {:checks {:wrote-review? (some? r)
              :presence-all-right? (= presence-right (count clauses))
              :quotes-match? (every? (fn [[k g]] (or (not (:present g)) (= 1.0 (get scores k)))) clauses)}
     :reward (if (seq scores) (/ (reduce + (vals scores)) (count scores)) 0.0)}))
