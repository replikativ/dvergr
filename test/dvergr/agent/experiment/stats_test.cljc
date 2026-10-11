(ns dvergr.agent.experiment.stats-test
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.agent.experiment.stats :as stats]))

(defn- close? [a b] (< (Math/abs (- (double a) (double b))) 1e-5))

(deftest beta-quantiles-match-a-reference
  ;; scipy.stats.beta.ppf
  (is (close? 0.223529 (stats/beta-quantile 5.5 5.5 0.025)))
  (is (close? 0.776471 (stats/beta-quantile 5.5 5.5 0.975)))
  (is (close? 0.217196 (stats/beta-quantile 0.5 10.5 0.975)))
  (is (close? 0.618685 (stats/beta-quantile 9.5 1.5 0.025)))
  (is (close? 0.385728 (stats/beta-quantile 2 3 0.5)))
  (is (close? 0.3 (stats/beta-cdf 1 1 0.3)) "Beta(1,1) is uniform"))

(deftest a-pass-rate-comes-with-its-range
  (is (nil? (stats/pass-rate-interval 0 0)))
  (let [[lo hi] (stats/pass-rate-interval 5 10)]
    (is (close? 0.223529 lo))
    (is (close? 0.776471 hi)))
  (testing "at 0 and n passes the interval reaches the bound"
    (is (= 0.0 (first (stats/pass-rate-interval 0 10))))
    (is (close? 0.217196 (second (stats/pass-rate-interval 0 10))))
    (is (= 1.0 (second (stats/pass-rate-interval 4 4)))))
  (testing "more attempts, a narrower range"
    (let [width (fn [[lo hi]] (- hi lo))]
      (is (< (width (stats/pass-rate-interval 30 40)) (width (stats/pass-rate-interval 3 4)))))))

(deftest a-mean-reward-comes-with-its-range
  (is (nil? (stats/mean-interval [])))
  (is (nil? (stats/mean-interval [0.7])) "one attempt says nothing about spread")
  (is (= [0.5 0.5] (stats/mean-interval [0.5 0.5 0.5])))
  (let [[lo hi] (stats/mean-interval [0.6 0.8])]
    ;; mean 0.7, sd 0.1414, t(1) = 12.706: clipped to [0, 1]
    (is (= 0.0 lo))
    (is (= 1.0 hi)))
  (let [[lo hi] (stats/mean-interval [0.6 0.7 0.8 0.7 0.6 0.8 0.7 0.7])]
    (is (< 0.6 lo 0.7 hi 0.8))))

(deftest comparing-two-pass-rates
  (testing "the same record: even odds"
    (is (close? 0.5 (stats/prob-rate-above [3 5] [3 5] 0.0))))
  (testing "the two directions add up to one"
    (is (< (Math/abs (- 1.0 (+ (stats/prob-rate-above [7 10] [4 10] 0.0)
                               (stats/prob-rate-above [4 10] [7 10] 0.0))))
           1e-3)))
  (testing "a clearly better record, and a margin makes \"no worse\" likelier"
    (is (< 0.95 (stats/prob-rate-above [19 20] [5 20] 0.0)))
    (is (< (stats/prob-rate-above [4 6] [5 6] 0.0) (stats/prob-rate-above [4 6] [5 6] 0.05)))))

(deftest a-paired-difference
  (is (nil? (stats/paired-difference [])))
  (let [{:keys [mean interval n]} (stats/paired-difference [[0.9 1.0] [0.8 1.0] [1.0 1.0] [0.7 0.9]])]
    (is (= 4 n))
    (is (close? -0.125 mean))
    (is (<= -1.0 (first interval) mean (second interval) 1.0))))

(deftest an-unpaired-difference-uses-every-attempt
  ;; Haiku vs Luna on two real corpora x three runs (2026-09-27); scipy's
  ;; Welch interval: 0.046167 [-0.065972, 0.158305] (df 9.72; ours floors the
  ;; df to 9, a little wider, never narrower)
  (let [{:keys [mean interval n]} (stats/welch-difference [0.965 0.704 0.862 0.942 0.877 0.922]
                                                          [0.94 0.745 0.808 0.747 0.871 0.884])
        [lo hi] interval]
    (is (close? 0.046167 mean))
    (is (= [6 6] n))
    (is (< (Math/abs (- lo -0.065972)) 0.01))
    (is (< (Math/abs (- hi 0.158305)) 0.01))
    (is (<= lo -0.065972) "conservative")
    (is (nil? (stats/welch-difference [0.5] [0.4 0.6])))))

(deftest the-exact-mcnemar-test-matches-the-binomial
  (testing "hand-computed: 2 · Σ_{i ≤ min(b,c)} C(b+c, i) / 2^(b+c)"
    (is (close? (/ 212.0 16384) (stats/mcnemar-p 12 2)) "the post: 12 against 2 decides (0.013)")
    (is (close? (/ 212.0 16384) (stats/mcnemar-p 2 12)) "symmetric")
    (is (= 1.0 (stats/mcnemar-p 6 5)) "the post: 6 against 5 says nothing")
    (is (= 1.0 (stats/mcnemar-p 0 0)) "no disagreement, no evidence")
    (is (= 1.0 (stats/mcnemar-p 4 4)))
    (is (= 1.0 (stats/mcnemar-p 3 2)) "within one: exactly 1, not 1 − ε")
    (is (close? 0.0625 (stats/mcnemar-p 5 0)) "a clean sweep of 5 cannot reach 0.05")
    (is (close? 0.03125 (stats/mcnemar-p 6 0))))
  (testing "benchmarks/reference/README.md's paired tests (mcnemar.py)"
    (is (close? 0.012939 (stats/mcnemar-p 2 12)) "BIRD Luna, plain prompt against the columns line: p = 0.013")
    (is (close? 0.003418 (stats/mcnemar-p 1 12)) "BIRD Haiku: p = 0.0034")
    (is (close? 0.34375 (stats/mcnemar-p 3 7)) "BIRD columns line against Dvergr's SQLite workflow: p = 0.34")
    (is (close? 0.000519 (stats/mcnemar-p 1 15)) "BIRD plain prompt against Dvergr: p = 0.0005")
    (is (close? 0.041389 (stats/mcnemar-p 5 15)) "SpreadsheetBench multi-round against Dvergr: p = 0.041"))
  (testing "large counts stay finite and accurate"
    ;; exact: 2 · Σ_{i ≤ 400} C(1000, i) / 2^1000 (Python fractions)
    (is (< (Math/abs (- 1.0 (/ (stats/mcnemar-p 400 600) 2.7284641560660184e-10))) 1e-9) "relative error")
    (is (= 1.0 (stats/mcnemar-p 2000 2000)))))

(deftest holm-adjusts-in-order
  (is (= [] (stats/holm [])))
  (is (= [0.04] (stats/holm [0.04])) "one comparison: unchanged")
  (let [adj (stats/holm [0.01 0.04 0.03])]
    (is (close? 0.03 (nth adj 0)) "smallest × 3")
    (is (close? 0.06 (nth adj 2)) "next × 2")
    (is (close? 0.06 (nth adj 1)) "monotone: max(0.04 × 1, 0.06)"))
  (is (= [1.0 1.0] (stats/holm [0.8 0.9])) "capped at 1"))

(defn- verdicts
  "`{case {0 passed?}}` from a string of 1s and 0s, one case each."
  [s]
  (into {} (map-indexed (fn [i ch] [i {0 (= ch \1)}])) s))

(deftest a-pair-compares-on-the-cases-where-it-disagrees
  (let [a (verdicts "1111100000")
        b (verdicts "1100000011")
        r (stats/paired-verdicts a b)]
    (is (= {:cases 10 :excluded-cases 0 :excluded-cells 0 :repetitions 1
            :both-passed 2 :a-only 3 :b-only 2 :both-failed 3 :tied-mixed 0}
           (dissoc r :p :pass-rate-difference :pass-rate-difference-interval)))
    (is (= 1.0 (:p r)))
    (is (close? 0.1 (:pass-rate-difference r))))
  (testing "a case without a verdict of both is left out, and said so"
    (let [r (stats/paired-verdicts (dissoc (verdicts "1111") 3) (assoc-in (verdicts "0000") [4 0] true))]
      (is (= 3 (:cases r)))
      (is (= 2 (:excluded-cases r)) "case 3 (A faulted) and case 4 (A has none)")
      (is (= 2 (:excluded-cells r)))
      (is (= 3 (:a-only r)))))
  (testing "repetitions: a case counts once, on the repetitions both have"
    (let [a {:x {0 true 1 true 2 false} :y {0 true 1 false} :z {0 false 1 false} :w {0 true 1 true}}
          b {:x {0 true 1 false 2 false} :y {0 false 1 true} :z {0 false 1 false} :w {0 true}}
          r (stats/paired-verdicts a b)]
      (is (= {:cases 4 :both-passed 1 :a-only 1 :b-only 0 :both-failed 1 :tied-mixed 1
              :excluded-cases 0 :excluded-cells 1 :repetitions 3}
             (select-keys r [:cases :both-passed :a-only :b-only :both-failed :tied-mixed
                             :excluded-cases :excluded-cells :repetitions]))
          "x: 2/3 against 1/3; y: 1/2 each; w: only repetition 0 is common"))))

(deftest many-candidates-compare-against-the-best
  (let [cells (fn [n] (into {} (for [i (range n)]
                                 [(str "c" i) (verdicts (apply str (concat (repeat (- 20 i) \1) (repeat i \0))))])))]
    (testing "up to five candidates: every pair, best first"
      (let [p (stats/paired-comparisons (cells 3))]
        (is (= :all-pairs (:policy p)))
        (is (= [["c0" "c1"] ["c0" "c2"] ["c1" "c2"]] (mapv (juxt :a :b) (:comparisons p))))
        (is (= 0 (:pairs-omitted p)))
        (is (every? #(= :no-difference (:verdict %)) (:comparisons p)))
        (is (every? #(not (:decidable? %)) (:comparisons p)) "at most two disagreements")))
    (testing "beyond five: each against the best"
      (let [p (stats/paired-comparisons (cells 8))]
        (is (= :against-best (:policy p)))
        (is (= "c0" (:baseline p)))
        (is (= 7 (count (:comparisons p))))
        (is (= 21 (:pairs-omitted p)))
        (is (= ["c0" "c7"] ((juxt :a :b) (last (:comparisons p)))))))
    (testing "with a baseline: each against it, the baseline as B"
      (let [p (stats/paired-comparisons (cells 3) {:baseline "c2"})]
        (is (= :against-baseline (:policy p)))
        (is (= [["c0" "c2"] ["c1" "c2"]] (mapv (juxt :a :b) (:comparisons p)))))))
  (testing "a clear difference survives Holm; a raw p < 0.05 alone may not"
    (let [p (stats/paired-comparisons {"a" (verdicts (str (apply str (repeat 14 \1)) "000000"))
                                       "b" (verdicts (str "11" (apply str (repeat 18 \0))))
                                       "c" (verdicts (str "11111111" (apply str (repeat 12 \0))))})
          by (into {} (map (juxt (juxt :a :b) identity)) (:comparisons p))]
      (is (= :a-better (:verdict (by ["a" "b"]))) "12 against 0")
      (is (close? (* 3 (stats/mcnemar-p 12 0)) (:p-adjusted (by ["a" "b"]))))
      (is (< (:p (by ["c" "b"])) 0.05) "6 against 0: p = 0.031 alone")
      (is (= :no-difference (:verdict (by ["c" "b"]))) "but not after the correction"))
    (is (nil? (stats/paired-comparisons {"a" (verdicts "1")})) "one candidate: nothing to compare")))
