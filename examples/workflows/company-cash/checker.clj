(ns company-cash-checker (:require [clojure.edn :as edn]))

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

(defn pinball
  "Mean quantile loss of the 10/50/90 % forecast `q` over outcomes `ys`."
  [ys q]
  (/ (reduce + (for [y ys
                     [tau k] [[0.1 :p10] [0.5 :p50] [0.9 :p90]]
                     :let [e (- y (get q k))]]
                 (if (neg? e) (* (- tau 1.0) e) (* tau e))))
     (count ys)))

(defn quantile-forecast? [q]
  (and (map? q) (every? number? [(:p10 q) (:p50 q) (:p90 q)]) (<= (:p10 q) (:p50 q) (:p90 q))))

(def horizons [[:by-day-14 0] [:by-day-28 1] [:by-day-56 2]])

(defn payment-skill
  "Brier skill of the payment forecasts: 0 at the pooled baseline, 1 at the
   truth. A missing or invalid probability counts as 0.5."
  [payment-odds forecast]
  (let [terms (for [[id {:keys [truth pooled]}] payment-odds
                    [k i] horizons
                    :let [q (get-in forecast [id k])
                          q (if (and (number? q) (<= 0.0 q 1.0)) q 0.5)
                          p (nth truth i)
                          b (nth pooled i)]]
                [(* (- q p) (- q p)) (* (- b p) (- b p))])
        agent (reduce + (map first terms))
        base (reduce + (map second terms))]
    (clamp01 (- 1.0 (/ agent (max base 1e-6))))))

(defn valid-plan? [{:keys [invoices founder-hours]} plan]
  (let [ids (set (map :id invoices))]
    (and (map? plan)
         (every? ids (keys plan))
         (every? actions (vals plan))
         (<= (count (filter #{:call} (vals plan))) founder-hours))))

(defn read-answer [raw]
  (try (when (and (string? raw) (<= (count raw) 40000))
         (let [v (edn/read-string raw)] (when (map? v) v)))
       (catch Exception _ nil)))

(defn check [{:keys [files gold]}]
  (let [answer (read-answer (get files "/out/answer.edn"))
        plan (:actions answer)
        valid? (boolean (and answer (valid-plan? gold plan)))
        runs (when valid? (simulate gold (:truth gold) plan))
        result (when runs (summarize runs))
        {:keys [oracle idle max-ruin payment-odds]} gold
        viable? (boolean (and result (<= (:p-ruin result) max-ruin)))
        decision (if result
                   (clamp01 (/ (- (:expected-value result) (:expected-value idle))
                               (- (:expected-value oracle) (:expected-value idle))))
                   0.0)
        pay (payment-skill payment-odds (:payments answer))
        q (:cash answer)
        ys (when runs (mapv :final-cash runs))
        cash-skill (if (and ys (quantile-forecast? q))
                     (let [best (pinball ys (:cash result))
                           base (pinball ys (quantiles (map :final-cash (simulate gold (:pooled gold) plan))))
                           agent (pinball ys q)]
                       (clamp01 (- 1.0 (/ (- agent best) (max (- base best) (* 0.5 best) 1.0)))))
                     0.0)
        p-ruin (:p-ruin answer)
        ruin-score (if (and result (number? p-ruin))
                     (clamp01 (- 1.0 (/ (abs* (- p-ruin (:p-ruin result))) 0.2)))
                     0.0)
        covered (when (and ys (quantile-forecast? q))
                  (/ (count (filter #(<= (:p10 q) % (:p90 q)) ys)) (double (count ys))))]
    {:checks {:plan-valid? valid?
              :viable? viable?
              :beats-doing-nothing? (boolean (and viable? (> (:expected-value result) (:expected-value idle))))
              :near-optimal? (and viable? (>= decision 0.9))
              :payments-skilful? (>= pay 0.5)
              :cash-forecast-skilful? (>= cash-skill 0.5)
              :ruin-forecast-close? (>= ruin-score 0.5)}
     :reward (+ (if viable? (* 0.5 decision) 0.0)
                (* 0.3 pay)
                (* 0.2 (+ (* 0.75 cash-skill) (* 0.25 ruin-score))))
     :metrics {:decision decision :payment-skill pay :cash-skill cash-skill :ruin-score ruin-score
               :cash-coverage-80 covered
               :expected-value (:expected-value result) :p-ruin (:p-ruin result) :cash (:cash result)}}))
