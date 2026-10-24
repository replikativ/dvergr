(ns dvergr.benchmarks.company-cash.kernel
  "Seeded synthetic receivables dynamics shared by generation, reference inference and scoring.
   These assumptions define a synthetic experiment, not an empirically validated firm.")

(def modulus 2147483647)

(defn next-state [x] (mod (* 48271 x) modulus))

(defn uniform [x] (/ (+ 0.5 (double x)) (+ 1.0 modulus)))

(defn stream-seed [seed world index]
  (let [s (mod (+ (* 7919 (inc world)) (* 104729 (inc index)) seed) modulus)]
    (if (zero? s) 1 s)))

(defn abs* [x] (if (neg? x) (- x) x))

(defn clamp01 [x] (max 0.0 (min 1.0 x)))

(def actions #{:none :reminder :call :factor})

(defn draws
  "The uniforms one invoice uses in one world: default, then pairs for
   Box-Muller normals (a few, for the overdue rejection)."
  [seed world index]
  (loop [s (next-state (stream-seed seed world index)) acc []]
    (if (= (count acc) 9) acc (recur (next-state s) (conj acc (uniform s))))))

(defn normal [u1 u2] (* (Math/sqrt (* -2.0 (Math/log u1))) (Math/cos (* 2.0 Math/PI u2))))

(defn payment-day
  "The day an invoice is paid (nil: never) under `action`. Days late are
   lognormal for the customer, scaled by the action's effect; an invoice
   already overdue is paid after today."
  [{:keys [mu sigma default]} {:keys [reminder call]} {:keys [due-day]} action us]
  (let [scale (case action :reminder reminder :call (:effect call) 1.0)
        p-default (if (= action :call) (* default (:default-factor call)) default)]
    (when-not (< (nth us 0) p-default)
      (let [late (fn [i] (* scale (Math/exp (+ mu (* sigma (normal (nth us i) (nth us (inc i))))))))
            day (some (fn [i] (let [d (+ due-day (late i))] (when (> d 0) d))) [1 3 5 7])]
        (or day 1.0)))))

(def late-value
  "What an invoice still unpaid at the horizon is worth, if its customer pays
   later: the time and effort until then."
  0.98)

(defn simulate
  "Per world under `model` (the truth, or a baseline's belief): final cash,
   final worth (cash plus what is still owed and will be paid) and whether
   cash was below zero at any week's end."
  [{:keys [invoices cash outflows factoring-fee seed worlds]} model plan]
  (let [weeks (count outflows)
        customers (:customers model)]
    (mapv (fn [w]
            (let [[inflow owed]
                  (reduce
                   (fn [[inflow owed] [i inv]]
                     (let [action (get plan (:id inv) :none)
                           amount (:amount inv)]
                       (if (= action :factor)
                         [(update inflow 0 + (* (- 1.0 factoring-fee) amount)) owed]
                         (let [day (payment-day (get customers (:customer inv)) model inv action
                                                (draws seed w i))
                               week (when day (long (Math/floor (/ (max 0.0 (dec day)) 7))))]
                           (cond (nil? day) [inflow owed]
                                 (< week weeks) [(update inflow week + amount) owed]
                                 :else [inflow (+ owed (* late-value amount))])))))
                   [(vec (repeat weeks 0.0)) 0.0]
                   (map-indexed vector invoices))]
              (loop [week 0 balance cash ruin? false]
                (if (= week weeks)
                  {:final-cash balance :final-value (+ balance owed) :ruin? ruin?}
                  (let [b (- (+ balance (nth inflow week)) (nth outflows week))]
                    (recur (inc week) b (or ruin? (neg? b))))))))
          (range worlds))))

(defn quantiles [xs]
  (let [v (vec (sort xs)) n (count v)
        q (fn [p] (nth v (min (dec n) (long (* p n)))))]
    {:p10 (q 0.1) :p50 (q 0.5) :p90 (q 0.9)}))

(defn summarize [outcomes]
  (let [n (count outcomes)]
    {:expected-value (/ (reduce + (map :final-value outcomes)) n)
     :expected-cash (/ (reduce + (map :final-cash outcomes)) n)
     :p-ruin (/ (count (filter :ruin? outcomes)) (double n))
     :cash (quantiles (map :final-cash outcomes))}))

