(ns dvergr.agent.measurements
  "Pure presentation completeness shared by host reports and client replicas.")

(defn summarize
  "Presentation-only completeness over individual recorded spends. Unknown
   is nil, not zero, and a mixed group never divides a partial total by all
   attempts. Explicitly unpriced spends have no measured cost; their reported
   token counts remain usable. Missing token dimensions are independently
   unknown. Legacy numeric costs without a priced flag remain recorded costs.

   Does not change spend/total, receipt payloads or content-addressed Scorecard
   summaries. Call with the individual entries, not their already folded sum."
  [spends]
  (let [spends (vec spends)
        amount (fn [s field]
                 (when-not (false? (:priced? s))
                   (let [n (if (= field :notional-microdollars)
                             (get s field (:microdollars s)) (get s field))]
                     (when (number? n) n))))
        billed (mapv #(amount % :microdollars) spends)
        worth (mapv #(amount % :notional-microdollars) spends)
        complete-sum (fn [xs] (when (every? some? xs) (reduce + 0 xs)))
        token (fn [s k] (let [n (get-in s [:tokens k])] (when (number? n) n)))
        reported? (fn [s] (and (some? (token s :input)) (some? (token s :output))))
        tokens (into {} (keep (fn [k]
                                (when-let [n (complete-sum (map #(token % k) spends))] [k n])))
                     [:input :output :cache-read :cache-write :reasoning])]
    {:priced? (every? some? billed)
     :notional-priced? (every? some? worth)
     :unpriced-attempts (count (filter nil? billed))
     :usage-reported? (every? reported? spends)
     :unreported-attempts (count (remove reported? spends))
     :microdollars (complete-sum billed)
     :notional-microdollars (complete-sum worth)
     :tokens tokens}))
