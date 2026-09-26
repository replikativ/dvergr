(ns dvergr.agent.experiment.stats
  "How sure a Scorecard is: intervals for a pass rate and a mean reward.
   Benchmark runs are small (a few worlds, a few repetitions), so a pass rate
   of 3/4 is reported with the range it is compatible with, not as 0.75.

   Pass rates use the Jeffreys interval: the central 95% of the Beta(passes +
   ½, fails + ½) posterior, well behaved at 0 and n passes. Mean rewards
   (bounded in [0, 1]) use a t interval, clipped to [0, 1], from two attempts
   on. Two candidates compare by the probability that one pass rate beats
   (or is no worse than) the other, and by their paired reward difference on
   the worlds both ran. Pure functions, no dependencies, portable: the same
   code runs on the JVM and in the browser (simmis's boards).")

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
