(ns dvergr.benchmarks.company-cash.scoring
  "Forecast and decision scoring for the frozen company-cash v1 fixtures."
  (:require [clojure.edn :as edn]
            [dvergr.benchmarks.company-cash.kernel :refer [actions abs* clamp01 simulate summarize quantiles]]))

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
