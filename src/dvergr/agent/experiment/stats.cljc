(ns dvergr.agent.experiment.stats
  "How sure a Scorecard is: intervals for a pass rate and a mean reward.
   Benchmark runs are small (a few worlds, a few repetitions), so a pass rate
   of 3/4 is reported with the range it is compatible with, not as 0.75.

   Pass rates use the Jeffreys interval: the central 95% of the Beta(passes +
   ½, fails + ½) posterior, well behaved at 0 and n passes. Mean rewards
   (bounded in [0, 1]) use a t interval, clipped to [0, 1], from two attempts
   on. Two candidates compare by the probability that one pass rate beats
   (or is no worse than) the other, by their paired reward difference on the
   worlds both ran, and by the exact McNemar (sign) test on the cases where
   they disagree (`paired-comparisons`). Pure functions, no dependencies,
   portable: the same code runs on the JVM and in the browser (simmis's boards).")

;; ============================================================================
;; The Beta distribution
;; ============================================================================

(defn- log-gamma
  "ln Γ(x) for x > 0 (Lanczos, g = 7)."
  [x]
  (if (< x 0.5)
    (- (Math/log (/ Math/PI (Math/sin (* Math/PI x)))) (log-gamma (- 1.0 x)))
    (let [cs [0.99999999999980993 676.5203681218851 -1259.1392167224028 771.32342877765313
              -176.61502916214059 12.507343278686905 -0.13857109526572012
              9.9843695780195716e-6 1.5056327351493116e-7]
          x (- x 1.0)
          t (+ x 7.5)
          s (reduce + (first cs) (map-indexed (fn [i c] (/ c (+ x i 1.0))) (rest cs)))]
      (+ (* 0.5 (Math/log (* 2 Math/PI))) (* (+ x 0.5) (Math/log t)) (- t) (Math/log s)))))

(defn- beta-cf
  "The continued fraction of the incomplete Beta function (modified Lentz)."
  [a b x]
  (let [tiny 1e-300
        clamp #(if (< (Math/abs (double %)) tiny) tiny %)
        qab (+ a b) qap (+ a 1.0) qam (- a 1.0)
        d0 (/ 1.0 (clamp (- 1.0 (/ (* qab x) qap))))]
    (loop [m 1 c 1.0 d d0 h d0]
      (let [m2 (* 2 m)
            aa (/ (* m (- b m) x) (* (+ qam m2) (+ a m2)))
            d (/ 1.0 (clamp (+ 1.0 (* aa d))))
            c (clamp (+ 1.0 (/ aa c)))
            h (* h d c)
            aa (/ (- (* (+ a m) (+ qab m) x)) (* (+ a m2) (+ qap m2)))
            d (/ 1.0 (clamp (+ 1.0 (* aa d))))
            c (clamp (+ 1.0 (/ aa c)))
            del (* d c)
            h (* h del)]
        (if (or (< (Math/abs (- del 1.0)) 1e-14) (< 300 m))
          h
          (recur (inc m) c d h))))))

(defn beta-cdf
  "P(X ≤ x) for X ~ Beta(a, b): the regularized incomplete Beta function."
  [a b x]
  (cond (<= x 0.0) 0.0
        (>= x 1.0) 1.0
        :else
        (let [front (Math/exp (+ (- (log-gamma (+ a b)) (log-gamma a) (log-gamma b))
                                 (* a (Math/log x)) (* b (Math/log (- 1.0 x)))))]
          (if (< x (/ (+ a 1.0) (+ a b 2.0)))
            (/ (* front (beta-cf a b x)) a)
            (- 1.0 (/ (* front (beta-cf b a (- 1.0 x))) b))))))

(defn beta-quantile
  "The p-quantile of Beta(a, b), by bisection on `beta-cdf`."
  [a b p]
  (loop [lo 0.0 hi 1.0 i 0]
    (let [mid (/ (+ lo hi) 2.0)]
      (if (< 60 i)
        mid
        (if (< (beta-cdf a b mid) p) (recur mid hi (inc i)) (recur lo mid (inc i)))))))

;; ============================================================================
;; Intervals
;; ============================================================================

(defn pass-rate-interval
  "The 95% Jeffreys interval of a pass rate: `[lo hi]`, nil without attempts.
   At 0 passes the lower end is 0, at n passes the upper end is 1."
  [passes n]
  (when (pos? n)
    (let [a (+ passes 0.5) b (+ (- n passes) 0.5)]
      [(if (zero? passes) 0.0 (beta-quantile a b 0.025))
       (if (= passes n) 1.0 (beta-quantile a b 0.975))])))

(def ^:private t-975
  "Student's t, 97.5% quantile, by degrees of freedom (1-30); 1.96 beyond."
  [12.706 4.303 3.182 2.776 2.571 2.447 2.365 2.306 2.262 2.228 2.201 2.179 2.160 2.145 2.131
   2.120 2.110 2.101 2.093 2.086 2.080 2.074 2.069 2.064 2.060 2.056 2.052 2.048 2.045 2.042])

(defn mean-interval
  "The 95% t interval of the mean of `xs`, clipped to `[lo hi]` (default
   [0, 1], for rewards): `[lo hi]`, nil for fewer than two values."
  ([xs] (mean-interval xs [0.0 1.0]))
  ([xs [floor ceiling]]
   (let [n (count xs)]
     (when (<= 2 n)
       (let [mean (/ (reduce + 0.0 xs) n)
             var (/ (reduce + 0.0 (map #(let [d (- % mean)] (* d d)) xs)) (dec n))
             t (get t-975 (dec (dec n)) 1.96)
             half (* t (Math/sqrt (/ var n)))]
         [(max floor (- mean half)) (min ceiling (+ mean half))])))))

;; ============================================================================
;; Comparing two candidates
;; ============================================================================

(defn prob-rate-above
  "P(p₁ > p₂ − margin) for pass rates p₁ of `passes-1`/`n-1` and p₂ of
   `passes-2`/`n-2`, under their independent Jeffreys posteriors: with margin
   0, the probability that the first is better; with a margin, that it is no
   worse by more than it. Integrated on a grid of 2,000 cells of the first
   posterior's CDF."
  [[passes-1 n-1] [passes-2 n-2] margin]
  (let [a1 (+ passes-1 0.5) b1 (+ (- n-1 passes-1) 0.5)
        a2 (+ passes-2 0.5) b2 (+ (- n-2 passes-2) 0.5)
        cells 2000
        xs (mapv #(/ (double %) cells) (range (inc cells)))
        fx (mapv #(beta-cdf a1 b1 %) xs)]
    (reduce + 0.0 (for [i (range cells)
                        :let [mid (/ (+ (xs i) (xs (inc i))) 2.0)]]
                    (* (- (fx (inc i)) (fx i))
                       (beta-cdf a2 b2 (min 1.0 (+ mid margin))))))))

(defn welch-difference
  "The difference of the means of `xs` and `ys` (all Attempts, unpaired) and
   its 95% Welch interval in [-1, 1]: `{:mean :interval :n [nx ny]}`, nil
   with fewer than two values on a side. With few worlds but several
   repetitions, pairing by world leaves too few pairs for a useful interval;
   this uses every Attempt."
  [xs ys]
  (let [nx (count xs) ny (count ys)]
    (when (and (<= 2 nx) (<= 2 ny))
      (let [mean (fn [v] (/ (reduce + 0.0 v) (count v)))
            variance (fn [v m] (/ (reduce + 0.0 (map #(let [d (- % m)] (* d d)) v)) (dec (count v))))
            mx (mean xs) my (mean ys)
            a (/ (variance xs mx) nx) b (/ (variance ys my) ny)
            se (Math/sqrt (+ a b))
            df (if (zero? (+ a b))
                 (+ nx ny -2)
                 (/ (* (+ a b) (+ a b))
                    (+ (/ (* a a) (dec nx)) (/ (* b b) (dec ny)))))
            t (get t-975 (dec (max 1 (int (Math/floor df)))) 1.96)
            d (- mx my)]
        {:mean d
         :interval [(max -1.0 (- d (* t se))) (min 1.0 (+ d (* t se)))]
         :n [nx ny]}))))

(defn paired-difference
  "The mean of the paired differences `(- x y)` over `pairs` of `[x y]` (the
   same world scored for two candidates) and its 95% interval in [-1, 1]:
   `{:mean :interval :n}`, nil without pairs."
  [pairs]
  (when (seq pairs)
    (let [ds (mapv (fn [[x y]] (- (double x) (double y))) pairs)]
      {:mean (/ (reduce + 0.0 ds) (count ds))
       :interval (mean-interval ds [-1.0 1.0])
       :n (count ds)})))

;; ============================================================================
;; Paired comparisons: the exact McNemar (sign) test
;; ============================================================================

(defn mcnemar-p
  "The exact two-sided McNemar p-value of `b` cases where A passed and B
   failed against `c` cases where B passed and A failed: the two-sided exact
   binomial test of b against Binomial(b + c, ½),
   p = min(1, 2 · Σ_{i ≤ min(b,c)} C(b+c, i) / 2^(b+c)). Cases both passed or
   both failed do not count; with no disagreement p = 1. Computed in log space,
   so it holds for thousands of discordant cases."
  [b c]
  (let [n (+ b c) k (min b c)]
    (if (<= (dec n) (* 2 k))
      ;; no disagreement, or the counts within one of each other: the tail
      ;; holds half the mass or more, exactly 1 (not 1 − ε from the sum)
      1.0
      (let [lp0 (* (- n) (Math/log 2.0))
            tail (loop [i 0 lp lp0 acc 0.0]
                   (if (< k i)
                     acc
                     (recur (inc i)
                            (+ lp (Math/log (- n i)) (- (Math/log (inc i))))
                            (+ acc (Math/exp lp)))))]
        (min 1.0 (* 2.0 tail))))))

(defn holm
  "Holm's step-down adjustment of the p-values `ps` (family-wise error rate),
   in their order: the i-th smallest of m is multiplied by m − i (i from 0),
   kept monotone and capped at 1."
  [ps]
  (let [m (count ps)
        order (sort-by #(nth ps %) (range m))
        adjusted (second (reduce (fn [[running out] [rank i]]
                                   (let [a (max running (min 1.0 (* (- m rank) (double (nth ps i)))))]
                                     [a (assoc out i a)]))
                                 [0.0 (vec (repeat m nil))]
                                 (map-indexed vector order)))]
    adjusted))

(defn paired-verdicts
  "Two candidates on the same cases. `a` and `b` are `{case {repetition
   passed?}}`, verdicts only (a faulted or missing cell is absent). A case
   counts on the repetitions both have a verdict for; a case without one is
   excluded, and so is every cell outside the common repetitions.

   Each counted case is decided by its pass rates over those repetitions:
   A higher (`:a-only`), B higher (`:b-only`), or equal: both always passed
   (`:both-passed`), both never (`:both-failed`), or the same rate in between
   (`:tied-mixed`, only with repetitions). With one repetition this is
   McNemar's table and `:p` its exact test; with several it is the exact sign
   test on the per-case differences, which needs no assumption that
   repetitions of one case are independent (the case is the unit). Also the
   mean per-case pass-rate difference A − B with its paired 95% interval.

   `{:cases :excluded-cases :excluded-cells :repetitions :both-passed :a-only
   :b-only :both-failed :tied-mixed :p :pass-rate-difference
   :pass-rate-difference-interval}`; `:repetitions` is the largest number of
   common repetitions of a case."
  [a b]
  (let [rows (for [id (distinct (concat (keys a) (keys b)))
                   :let [ra (get a id {}) rb (get b id {})
                         common (filter #(contains? rb %) (keys ra))
                         n (count common)]]
               (if (zero? n)
                 {:excluded? true :cells (+ (count ra) (count rb))}
                 (let [pa (count (filter #(true? (get ra %)) common))
                       pb (count (filter #(true? (get rb %)) common))]
                   {:n n
                    :cells (- (+ (count ra) (count rb)) (* 2 n))
                    :diff (/ (- pa pb) (double n))
                    :kind (cond (> pa pb) :a-only
                                (< pa pb) :b-only
                                (= pa n) :both-passed
                                (zero? pa) :both-failed
                                :else :tied-mixed)})))
        counted (remove :excluded? rows)
        kinds (frequencies (map :kind counted))
        k #(get kinds % 0)
        diff (paired-difference (map (fn [r] [(:diff r) 0.0]) counted))]
    {:cases (count counted)
     :excluded-cases (count (filter :excluded? rows))
     :excluded-cells (reduce + 0 (map :cells rows))
     :repetitions (reduce max 0 (map :n counted))
     :both-passed (k :both-passed)
     :a-only (k :a-only)
     :b-only (k :b-only)
     :both-failed (k :both-failed)
     :tied-mixed (k :tied-mixed)
     :p (mcnemar-p (k :a-only) (k :b-only))
     :pass-rate-difference (:mean diff)
     :pass-rate-difference-interval (:interval diff)}))

(def ^:private all-pairs-up-to
  "Up to this many candidates every pair is compared (10 pairs at 5);
   beyond, each against the best one."
  5)

(defn paired-comparisons
  "Paired comparisons of the candidates of `cells` (`{candidate {case
   {repetition passed?}}}`, `paired-verdicts`). Candidates rank by pass rate
   over their verdicts (then by name). With `:baseline` (a key of `cells`),
   every other candidate against it; else every pair while there are at most
   five candidates, and beyond that every candidate against the best. In each
   pair A ranks above B (against a baseline B is the baseline).

   The p-values are Holm-adjusted over the family of comparisons
   (`:family-size`): against a baseline, its comparisons; else every pair,
   also when only those against the best are shown, since picking the best
   looked at all of them. A pair differs when its adjusted p is below
   `:alpha` (default 0.05): `:verdict`
   `:a-better`, `:b-better` or `:no-difference`; `:decidable?` false when
   even a clean sweep of its disagreeing cases could not reach `:alpha`.

   `{:policy (:all-pairs | :against-baseline | :against-best) :baseline
   :alpha :correction :holm :family-size :pairs-omitted :comparisons [{:a :b
   ...}]}`, nil
   with fewer than two candidates."
  [cells & [{:keys [baseline alpha] :or {alpha 0.05}}]]
  (let [rate (fn [c] (let [vs (mapcat vals (vals (get cells c)))]
                       (if (seq vs) (/ (count (filter true? vs)) (double (count vs))) 0.0)))
        ranked (vec (sort-by (juxt (comp - rate) str) (keys cells)))
        m (count ranked)
        baseline (when (contains? cells baseline) baseline)
        policy (cond baseline :against-baseline
                     (<= m all-pairs-up-to) :all-pairs
                     :else :against-best)
        all-pairs (for [i (range m) j (range (inc i) m)] [(ranked i) (ranked j)])
        ;; the family Holm covers: against a baseline named in advance, its
        ;; comparisons; else every pair, also when only those against the
        ;; observed best are shown (choosing it looked at all of them)
        family (if baseline
                 (for [c ranked :when (not= c baseline)] [c baseline])
                 all-pairs)
        shown? (case policy
                 :against-best (fn [[x _]] (= x (first ranked)))
                 (constantly true))]
    (when (<= 2 m)
      (let [results (mapv (fn [[x y]] (assoc (paired-verdicts (get cells x) (get cells y)) :a x :b y)) family)
            adjusted (holm (mapv :p results))
            shown (filterv (comp shown? (juxt :a :b) first) (map vector results adjusted))]
        {:policy policy
         :baseline (case policy :against-baseline baseline :against-best (first ranked) nil)
         :alpha alpha
         :correction :holm
         :family-size (count family)
         :pairs-omitted (- (quot (* m (dec m)) 2) (count shown))
         :comparisons
         (mapv (fn [[r p-adj]]
                 (let [discordant (+ (:a-only r) (:b-only r))]
                   (assoc r
                          :p-adjusted p-adj
                          :decidable? (< (mcnemar-p discordant 0) alpha)
                          :verdict (cond (<= alpha p-adj) :no-difference
                                         (> (:a-only r) (:b-only r)) :a-better
                                         :else :b-better))))
               shown)}))))
