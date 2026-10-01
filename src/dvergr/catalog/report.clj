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
                 tok (fn [k] (reduce + 0 (map #(get-in % [:spend :tokens k] 0) entries)))
                 tokens (+ (tok :input) (tok :output))
                 receipts (keep #(receipt-by-id (:attempt/id %)) entries)
                 secs (sort (keep #(some-> (:attempt/elapsed-ms %) (/ 1000.0)) receipts))
                 spans (keep (fn [r] (when-let [t0 (:attempt/started-at r)]
                                       [t0 (+ t0 (or (:attempt/elapsed-ms r) 0))]))
                             receipts)
                 failed (frequencies (mapcat (fn [r] (keep (fn [[k v]] (when (false? v) k)) (:attempt/checks r))) receipts))]]
       {:candidate cid :n n :passes passes
        :pass-rate (when (pos? n) (/ passes (double n)))
        :pass-interval (xstats/pass-rate-interval passes n)
        :reward (when (pos? n) (/ (reduce + rewards) n))
        :reward-interval (xstats/mean-interval rewards)
        :notional-per-attempt (when (pos? n) (/ notional n))
        :notional-per-pass (when (pos? passes) (/ notional passes))
        :tokens-per-attempt (when (pos? n) (quot tokens n))
        :input-per-attempt (when (pos? n) (quot (tok :input) n))
        :output-per-attempt (when (pos? n) (quot (tok :output) n))
        ;; unknown, not 0, where no attempt recorded its cache split
        :cache-share (when (and (pos? (tok :input)) (some #(contains? (get-in % [:spend :tokens]) :cache-read) entries))
                       (/ (tok :cache-read) (double (tok :input))))
        :notional-total notional
        :median-seconds (median secs)
        :p90-seconds (when (seq secs) (nth (vec secs) (int (* 0.9 (dec (count secs))))))
        :total-seconds (when (seq secs) (reduce + secs))
        ;; first start to last end: with parallel cells less than the sum
        :wall-seconds (when (seq spans)
                        (/ (- (reduce max (map second spans)) (reduce min (map first spans))) 1000.0))
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
     (when-let [{:keys [cells]} (:incomplete (:scorecard result))]
       (str "**Incomplete:** " cells " cell" (when (not= 1 cells) "s") " did not finish, so there is no "
            "Scorecard yet; running the experiment again resumes them.\n\n"))
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
     "\n## Resources\n\n"
     "| Candidate | Input tokens / attempt | Output tokens / attempt | Cached input | Median time | p90 time | Summed time | Wall clock | Total cost |\n"
     "|---|---:|---:|---:|---:|---:|---:|---:|---:|\n"
     (str/join
      (for [{:keys [candidate input-per-attempt output-per-attempt cache-share median-seconds p90-seconds total-seconds
                    wall-seconds notional-total]} rs]
        (format "| %s | %s | %s | %s | %s | %s | %s | %s | %s |\n" (name candidate)
                (or input-per-attempt "–") (or output-per-attempt "–") (pct cache-share)
                (if median-seconds (format "%.0f s" median-seconds) "–")
                (if p90-seconds (format "%.0f s" p90-seconds) "–")
                (if total-seconds (format "%.1f min" (/ total-seconds 60.0)) "–")
                (if wall-seconds (format "%.1f min" (/ wall-seconds 60.0)) "–")
                (money (or notional-total 0)))))
     "\nSummed time adds up every attempt's own time; the wall clock runs from the first attempt's start to "
     "the last one's end (it includes waits, and is shorter than the sum when attempts run in parallel). "
     "Cached input is the share of input tokens read from the provider's cache (– where it was not recorded).\n"
     "\nCosts are at the models' list prices (what the same tokens cost through the API), whether or not "
     "a subscription paid for them.\n"
     (when (seq checks)
       (str "\n## Where answers fail\n\n"
            "How often each check failed, of all attempts.\n\n"
            "| Failed check | " (str/join " | " (map (comp name :candidate) rs)) " |\n"
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
