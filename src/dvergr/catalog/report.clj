(ns dvergr.catalog.report
  "The report a benchmark pilot hands over, from an experiment's result
   (`dvergr.agent.experiment.runner/run!`: its Scorecard and `:receipts`) and, for a case pack, the bundle's certification:

   - the frontier: per candidate, how often it passed (95% interval), its
     mean reward, what an attempt and a pass cost at list price, tokens and
     time per attempt;
   - where candidates fail: per check, how often it failed;
   - which cases could not grade an answer, and why (certification).

   As Markdown, to read or to publish."
  (:require [clojure.string :as str]
            [dvergr.agent.experiment.stats :as xstats]))

(defn- money [microdollars] (format "$%.4f" (/ (double microdollars) 1e6)))

(defn- pct [x] (if x (format "%.0f%%" (* 100.0 x)) "–"))

(defn- median [xs]
  (when (seq xs)
    (let [v (vec (sort xs)) n (count v)]
      (if (odd? n) (nth v (quot n 2)) (/ (+ (nth v (dec (quot n 2))) (nth v (quot n 2))) 2.0)))))

(defn rows
  "Per candidate: `{:candidate :n :passes :pass-rate :pass-interval :reward
   :reward-interval :notional-per-attempt :notional-per-pass :tokens-per-attempt
   :median-seconds :failed-checks {check n}}` from a run result."
  [{:keys [scorecard receipts]}]
  (let [receipt-by-id (into {} (map (juxt :attempt/id identity)) receipts)]
    (vec
     (for [[cid entries] (sort-by key (group-by :candidate/id (:scorecard/entries scorecard)))
           :let [n (count entries)
                 passes (count (filter :passed? entries))
                 rewards (mapv #(double (or (:reward %) 0)) entries)
                 notional (reduce + 0 (map #(get-in % [:spend :notional-microdollars] 0) entries))
                 tokens (reduce + 0 (map #(let [t (get-in % [:spend :tokens])] (+ (:input t 0) (:output t 0))) entries))
                 receipts (keep #(receipt-by-id (:attempt/id %)) entries)
                 failed (frequencies (mapcat (fn [r] (keep (fn [[k v]] (when (false? v) k)) (:attempt/checks r))) receipts))]]
       {:candidate cid :n n :passes passes
        :pass-rate (when (pos? n) (/ passes (double n)))
        :pass-interval (xstats/pass-rate-interval passes n)
        :reward (when (pos? n) (/ (reduce + rewards) n))
        :reward-interval (xstats/mean-interval rewards)
        :notional-per-attempt (when (pos? n) (/ notional n))
        :notional-per-pass (when (pos? passes) (/ notional passes))
        :tokens-per-attempt (when (pos? n) (quot tokens n))
        :median-seconds (some-> (median (keep :attempt/elapsed-ms receipts)) (/ 1000.0))
        :failed-checks (into (sorted-map) failed)}))))

(defn markdown
  "The pilot report: `title`, the run `result`, and optionally the bundle's
   `certification` (`dvergr.catalog.casepack`) and a `note`."
  [{:keys [title result certification note]}]
  (let [rs (rows result)
        checks (sort (distinct (mapcat (comp keys :failed-checks) rs)))]
    (str
     "# " (or title "Benchmark report") "\n\n"
     (when note (str note "\n\n"))
     "## Frontier\n\n"
     "| Candidate | Attempts | Passed | 95% interval | Mean reward | Cost / attempt | Cost / pass | Tokens / attempt | Median time |\n"
     "|---|---:|---:|---|---:|---:|---:|---:|---:|\n"
     (str/join
      (for [{:keys [candidate n passes pass-rate pass-interval reward notional-per-attempt notional-per-pass
                    tokens-per-attempt median-seconds]} rs]
        (format "| %s | %d | %d (%s) | %s | %.3f | %s | %s | %s | %s |\n"
                (name candidate) n passes (pct pass-rate)
                (if pass-interval (str (pct (first pass-interval)) "–" (pct (second pass-interval))) "–")
                (double (or reward 0))
                (if notional-per-attempt (money notional-per-attempt) "–")
                (if notional-per-pass (money notional-per-pass) "–")
                (or tokens-per-attempt "–")
                (if median-seconds (format "%.0f s" median-seconds) "–"))))
     "\nCosts are at the models' list prices (what the same tokens cost through the API), whether or not "
     "a subscription paid for them.\n"
     (when (seq checks)
       (str "\n## Where answers fail\n\n"
            "| Check | " (str/join " | " (map (comp name :candidate) rs)) " |\n"
            "|---|" (str/join (repeat (count rs) "---:|")) "\n"
            (str/join (for [c checks]
                        (str "| " (name c) " | "
                             (str/join " | " (for [r rs] (str (get-in r [:failed-checks c] 0) " / " (:n r))))
                             " |\n")))))
     (when certification
       (str "\n## Cases\n\n"
            (:certified certification) " of " (:cases certification) " cases could grade an answer"
            (let [ex (dissoc (:by-reason certification) :certified)]
              (when (seq ex)
                (str "; the others were excluded: "
                     (str/join ", " (for [[k v] ex] (str v " " (str/replace (name k) "-" " "))))
                     " (`certification.edn` lists each)")))
            ".\n")))))
