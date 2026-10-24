(ns dvergr.benchmarks.company-cash.reference
  "History-only conjugate/grid reference with importance sampling of shared effects.
   The numerical baseline ignores correspondence and market news."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [dvergr.benchmarks.company-cash.kernel :as kernel :refer [payment-day normal]]))

(defn simulate [company model plan seed worlds]
  (kernel/simulate (assoc company :seed seed :worlds worlds) model plan))

;; ---------------------------------------------------------- numerics

(def ^:private lanczos [676.5203681218851 -1259.1392167224028 771.32342877765313
                        -176.61502916214059 12.507343278686905 -0.13857109526572012
                        9.9843695780195716e-6 1.5056327351493116e-7])

(defn log-gamma [x]
  (if (< x 0.5)
    (- (Math/log (/ Math/PI (Math/sin (* Math/PI x)))) (log-gamma (- 1.0 x)))
    (let [x (dec x)
          a (reduce + 0.99999999999980993 (map-indexed (fn [i c] (/ c (+ x i 1.0))) lanczos))
          t (+ x 7.5)]
      (+ (* 0.5 (Math/log (* 2.0 Math/PI))) (* (+ x 0.5) (Math/log t)) (- t) (Math/log a)))))

(defn log-sum-exp [xs]
  (let [m (apply max xs)] (+ m (Math/log (reduce + (map #(Math/exp (- % m)) xs))))))

(defn sample-gamma
  "Gamma(shape, 1) by Marsaglia–Tsang; `rand` gives uniforms in (0, 1)."
  [shape rand]
  (if (< shape 1.0)
    (* (sample-gamma (inc shape) rand) (Math/pow (rand) (/ 1.0 shape)))
    (let [d (- shape (/ 1.0 3.0)) c (/ 1.0 (Math/sqrt (* 9.0 d)))]
      (loop []
        (let [z (normal (rand) (rand)) v (Math/pow (+ 1.0 (* c z)) 3)]
          (if (and (pos? v) (< (Math/log (rand)) (+ (* 0.5 z z) (- d (* d v)) (* d (Math/log v)))))
            (* d v)
            (recur)))))))

;; ----------------------------------------------------------- inference

(def prior
  "What the reference assumes before seeing the history."
  {:late {:m0 2.8 :k0 1.0 :a0 3.0 :b0 0.72}   ; log-lateness: level ~ N(m0, s²/k0), s² ~ IG(a0, b0)
   :default {:a 1.0 :b 12.0}                ; chance of never paying ~ Beta(a, b)
   :effects {:reminder [0.3 1.0] :call [0.2 1.0] :default-factor [0.1 1.0]}}) ; uniform ranges

(def default-grid (mapv #(* 0.01 (+ 0.5 %)) (range 70)))   ; 0.005 … 0.695

(defn late-posterior
  "Normal-inverse-gamma posterior of a customer's log-lateness from `ys`
   (log days late, the action's effect already removed) and its log
   marginal likelihood."
  [{:keys [m0 k0 a0 b0]} ys]
  (let [n (count ys)]
    (if (zero? n)
      {:mn m0 :kn k0 :an a0 :bn b0 :log-ml 0.0}
      (let [ybar (/ (reduce + ys) n)
            ss (reduce + (map #(let [e (- % ybar)] (* e e)) ys))
            kn (+ k0 n) mn (/ (+ (* k0 m0) (* n ybar)) kn) an (+ a0 (/ n 2.0))
            bn (+ b0 (* 0.5 ss) (/ (* k0 n (- ybar m0) (- ybar m0)) (* 2.0 kn)))]
        {:mn mn :kn kn :an an :bn bn
         :log-ml (+ (- (log-gamma an) (log-gamma a0)) (* a0 (Math/log b0)) (- (* an (Math/log bn)))
                    (* 0.5 (- (Math/log k0) (Math/log kn))) (* -0.5 n (Math/log (* 2.0 Math/PI))))}))))

(defn default-posterior
  "Grid posterior of a customer's chance of never paying from `rows`
   (written off?, called?) and its log marginal likelihood."
  [{:keys [a b]} cf rows]
  (let [log-prior (fn [d] (+ (* (dec a) (Math/log d)) (* (dec b) (Math/log (- 1.0 d)))
                             (- (log-gamma (+ a b)) (log-gamma a) (log-gamma b))))
        log-post (mapv (fn [d]
                         (+ (log-prior d)
                            (reduce + (map (fn [{:keys [off? call?]}]
                                             (let [p (if call? (* d cf) d)]
                                               (Math/log (if off? p (- 1.0 p)))))
                                           rows))))
                       default-grid)
        z (log-sum-exp log-post)]
    {:weights (mapv #(Math/exp (- % z)) log-post)
     :log-ml (+ z (Math/log 0.01))}))

(defn rows
  "Per customer, the history as the inference reads it."
  [history]
  (group-by :customer
            (for [{:keys [customer action outcome days-late]} history]
              {:customer customer :action action :off? (= outcome :written-off) :call? (= action :call)
               :log-late (when days-late (Math/log (max 0.5 (double days-late))))})))

(defn conditional
  "Everything about the customers given the action effects: per customer
   posteriors and the history's log marginal likelihood."
  [by-customer {:keys [reminder call default-factor]}]
  (let [shift {:none 0.0 :reminder (Math/log reminder) :call (Math/log call)}
        cs (into {} (for [[c rs] by-customer]
                      [c {:late (late-posterior (:late prior) (keep #(when (:log-late %) (- (:log-late %) (shift (:action %)))) rs))
                          :default (default-posterior (:default prior) default-factor rs)}]))]
    {:customers cs
     :log-ml (reduce + (map (fn [[_ {:keys [late default]}]] (+ (:log-ml late) (:log-ml default))) cs))}))

(defn posterior
  "Importance sampling over the shared action effects (`n` prior draws),
   exact per customer given them. Returns the weighted particles and the
   effective sample size."
  ([history n rand] (posterior history (distinct (map :customer history)) n rand))
  ([history customers n rand]
   (let [by (merge (zipmap customers (repeat [])) (rows history))
         between (fn [[lo hi]] (+ lo (* (- hi lo) (rand))))
         particles (vec (for [_ (range n)
                              :let [fx {:reminder (between (get-in prior [:effects :reminder]))
                                        :call (between (get-in prior [:effects :call]))
                                        :default-factor (between (get-in prior [:effects :default-factor]))}
                                    cond (conditional by fx)]]
                          (assoc cond :effects fx)))
         z (log-sum-exp (map :log-ml particles))
         ws (mapv #(Math/exp (- (:log-ml %) z)) particles)]
     {:particles particles :weights ws
      :ess (/ 1.0 (reduce + (map #(* % %) ws)))
      :log-evidence (- z (Math/log n))})))

(defn pick-weighted [ws rand]
  (let [u (rand)]
    (loop [i 0 acc 0.0]
      (let [acc (+ acc (nth ws i))]
        (if (or (<= u acc) (= i (dec (count ws)))) i (recur (inc i) acc))))))

(defn draw-world
  "One possible truth from the posterior: action effects and every customer's
   parameters, in the simulator's format."
  [{:keys [particles weights]} rand]
  (let [{:keys [effects customers]} (nth particles (pick-weighted weights rand))]
    {:reminder (:reminder effects)
     :call {:effect (:call effects) :default-factor (:default-factor effects)}
     :customers (into {} (for [[c {:keys [late default]}] customers
                               :let [{:keys [mn kn an bn]} late
                                     s2 (/ bn (sample-gamma an rand))
                                     mu (+ mn (* (Math/sqrt (/ s2 kn)) (normal (rand) (rand))))]]
                           [c {:mu mu :sigma (Math/sqrt s2)
                               :default (nth default-grid (pick-weighted (:weights default) rand))}]))}))

;; ------------------------------------------------------------ decisions

(defn summarize [outcomes]
  (let [n (count outcomes) cash (vec (sort (map :final-cash outcomes)))
        q (fn [p] (nth cash (min (dec n) (long (* p n)))))]
    {:expected-value (/ (reduce + (map :final-value outcomes)) n)
     :p-ruin (/ (count (filter :ruin? outcomes)) (double n))
     :cash {:p10 (q 0.1) :p50 (q 0.5) :p90 (q 0.9)}}))

(defn judge
  "A plan under a set of possible truths: outcomes pooled over them."
  [company truths plan worlds]
  (summarize (vec (mapcat (fn [i t] (simulate company t plan (+ 7001 (* 31 i)) worlds)) (range) truths))))

(defn valid-plan? [{:keys [invoices founder-hours]} plan]
  (and (every? (set (map :id invoices)) (keys plan))
       (<= (count (filter #{:call} (vals plan))) founder-hours)))

(defn best-plan
  "Local search for the plan with the highest expected worth whose chance of
   ruin stays within the limit, under `truths`."
  [company truths worlds]
  (let [ids (mapv :id (:invoices company))
        score (fn [plan] (let [s (judge company truths plan worlds)]
                           (assoc s :plan plan :viable? (<= (:p-ruin s) (:max-ruin company)))))
        better? (fn [a b] (if (= (:viable? a) (:viable? b))
                            (if (:viable? a) (> (:expected-value a) (:expected-value b)) (< (:p-ruin a) (:p-ruin b)))
                            (:viable? a)))]
    (loop [best (score (zipmap ids (repeat :reminder))) pass 0]
      (let [next (reduce (fn [best id]
                           (reduce (fn [best a]
                                     (let [plan (assoc (:plan best) id a)]
                                       (if (valid-plan? company plan)
                                         (let [s (score plan)] (if (better? s best) s best))
                                         best)))
                                   best [:none :reminder :call :factor]))
                         best ids)]
        (if (or (= (:plan next) (:plan best)) (= pass 4)) next (recur next (inc pass)))))))

(defn payment-odds
  "P(paid by day 14, 28, 56) of each open invoice if left alone, over `truths`."
  [company truths per-truth rand]
  (into {} (for [inv (:invoices company)
                 :let [days (for [t truths _ (range per-truth)]
                              (payment-day (get-in t [:customers (:customer inv)]) t inv :none (vec (repeatedly 9 rand))))
                       n (double (count days))]]
             [(:id inv) (zipmap [:by-day-14 :by-day-28 :by-day-56]
                                (map (fn [h] (/ (count (filter #(and % (<= % h)) days)) n)) [14 28 56]))])))

(defn answer
  "The history-only Bayesian answer for `company`."
  [company history {:keys [particles truths worlds seed] :or {particles 300 truths 40 worlds 10 seed 1}}]
  (let [r (java.util.Random. seed)
        rand #(.nextDouble r)
        post (posterior history (distinct (map :customer (:invoices company))) particles rand)
        ts (vec (repeatedly truths #(draw-world post rand)))
        best (best-plan company ts worlds)]
    {:ess (:ess post)
     :answer {:actions (:plan best)
              :payments (payment-odds company ts 25 rand)
              :cash (:cash best) :p-ruin (:p-ruin best)}}))

(defn model-source
  "Self-contained, history-only learned-model baseline for the artifact contract.
   No private parameters enter it. Eight shared-effect particles keep this
   initial SCI baseline small; this is a numerical baseline, not a gold model."
  []
  (let [kernel-source (slurp (io/resource "dvergr/benchmarks/company_cash/kernel.clj"))
        reference-source (slurp (io/resource "dvergr/benchmarks/company_cash/reference.clj"))]
    (str "(ns company-cash-model)\n"
         (str/replace (subs kernel-source (.indexOf kernel-source "(def modulus"))
                      "(defn simulate" "(defn simulate-kernel")
         (subs reference-source (.indexOf reference-source ";; ---------------------------------------------------------- numerics")
               (.indexOf reference-source ";; ------------------------------------------------------------ decisions"))
         "\n(defn fit [observations seed budget]\n"
         "  (let [r (java.util.Random. seed)]\n"
         "    {:company (:company observations)\n"
         "     :posterior (posterior (:history observations) (distinct (map :customer (get-in observations [:company :invoices]))) 8 #(.nextDouble r))}))\n"
         "(defn simulate [fitted actions seed n]\n"
         "  (let [r (java.util.Random. seed)]\n"
         "    (mapv (fn [i]\n"
         "            (let [truth (draw-world (:posterior fitted) #(.nextDouble r))]\n"
         "              (first (simulate-kernel (assoc (:company fitted) :seed (+ seed i) :worlds 1) truth actions))))\n"
         "          (range n))))\n")))
