(ns dvergr.catalog.report
  "The report a benchmark pilot hands over, from an experiment's result
   (`dvergr.agent.experiment.runner/run!`: its Scorecard and `:receipts`) and, for a case pack, the bundle's certification:

   - the frontier: per candidate, how often it passed (95% interval), its
     mean reward, what an attempt and a pass cost at list price, tokens and
     time per attempt;
   - paired comparisons: each pair of candidates (or each against a
     baseline) on the cases both have a verdict for, the cases where they
     disagree, the exact McNemar p and a verdict;
   - resources: per candidate, input and output tokens per attempt, the
     cached share of the input, median, p90, summed and wall-clock time,
     and the total cost; and when a judge model graded, the judge's cost
     (list price and billed) apart, as the experiment's;
   - where candidates fail: per check, how often it failed;
   - which cases could not grade an answer, and why (certification);
   - when cells ran again because their inputs changed, a note saying how
     many (`inputs-note`).

   As Markdown, to read or to publish. `room-report` renders it from what an
   experiment Room stores (its Scorecard and certified Attempts): the
   `experiment/report` op, which every binding (MCP, the REPL, the
   `room-run` shell) reads."
  (:require [clojure.string :as str]
            [dvergr.agent.experiment :as experiment]
            [dvergr.agent.experiment.stats :as xstats]
            [dvergr.agent.spend :as spend]
            [dvergr.room.store :as store]))

(defn- money [microdollars] (format "$%.4f" (/ (double microdollars) 1e6)))

(defn- pct [x] (if x (format "%.0f%%" (* 100.0 x)) "–"))

(defn- median [xs]
  (when (seq xs)
    (let [v (vec (sort xs)) n (count v)]
      (if (odd? n) (nth v (quot n 2)) (/ (+ (nth v (dec (quot n 2))) (nth v (quot n 2))) 2.0)))))

(defn rows
  "Per candidate: `{:candidate :n :passes :pass-rate :pass-interval :reward
   :reward-interval :notional-per-attempt :notional-per-pass :tokens-per-attempt
   :median-seconds :failed-checks {check n} :judge-notional-total
   :judge-billed-total}` from a run result. The judge totals (nil when no
   receipt records a judge) are what judging the candidate's attempts cost:
   in none of its own costs."
  [{:keys [scorecard receipts]}]
  (let [receipt-by-id (into {} (map (juxt :attempt/id identity)) receipts)]
    (vec
     (for [[cid entries] (sort-by key (group-by :candidate/id (:scorecard/entries scorecard)))
           :let [n (count entries)
                 passes (count (filter :passed? entries))
                 rewards (mapv #(double (or (:reward %) 0)) entries)
                 ;; a paid call's bill is its list price; a subscription's is notional
                 measured (spend/measurements (map :spend entries))
                 notional (:notional-microdollars measured)
                 tok #(get-in measured [:tokens %])
                 tokens (when (:usage-reported? measured) (+ (tok :input) (tok :output)))
                 receipts (keep #(receipt-by-id (:attempt/id %)) entries)
                 secs (sort (keep #(some-> (:attempt/elapsed-ms %) (/ 1000.0)) receipts))
                 spans (keep (fn [r] (when-let [t0 (:attempt/started-at r)]
                                       [t0 (+ t0 (or (:attempt/elapsed-ms r) 0))]))
                             receipts)
                 failed (frequencies (mapcat (fn [r] (keep (fn [[k v]] (when (false? v) k)) (:attempt/checks r))) receipts))
                 ;; judging the candidate's attempts: the experiment's cost,
                 ;; never in the candidate's own
                 judge (spend/measurements (keep #(get-in % [:attempt/metrics :judge-spend]) receipts))
                 judged? (some #(get-in % [:attempt/metrics :judge-spend]) receipts)]]
       {:candidate cid :n n :passes passes
        :pass-rate (when (pos? n) (/ passes (double n)))
        :pass-interval (xstats/pass-rate-interval passes n)
        :reward (when (pos? n) (/ (reduce + rewards) n))
        :reward-interval (xstats/mean-interval rewards)
        :priced? (:priced? measured) :notional-priced? (:notional-priced? measured)
        :unpriced-attempts (:unpriced-attempts measured)
        :usage-reported? (:usage-reported? measured) :unreported-attempts (:unreported-attempts measured)
        :notional-per-attempt (when (and (some? notional) (pos? n)) (/ notional n))
        :notional-per-pass (when (and (some? notional) (pos? passes)) (/ notional passes))
        :tokens-per-attempt (when (and (some? tokens) (pos? n)) (quot tokens n))
        :input-per-attempt (when (and (some? (tok :input)) (pos? n)) (quot (tok :input) n))
        :output-per-attempt (when (and (some? (tok :output)) (pos? n)) (quot (tok :output) n))
        ;; unknown, not 0, where no attempt recorded its cache split
        :cache-share (when (and (some? (tok :input)) (pos? (tok :input)) (some? (tok :cache-read)))
                       (/ (tok :cache-read) (double (tok :input))))
        :notional-total notional
        :median-seconds (median secs)
        :p90-seconds (when (seq secs) (nth (vec secs) (int (* 0.9 (dec (count secs))))))
        :total-seconds (when (seq secs) (reduce + secs))
        ;; first start to last end: with parallel cells less than the sum
        :wall-seconds (when (seq spans)
                        (/ (- (reduce max (map second spans)) (reduce min (map first spans))) 1000.0))
        :failed-checks (into (sorted-map) failed)
        :judge-recorded? (boolean judged?)
        :judge-notional-total (when judged? (:notional-microdollars judge))
        :judge-billed-total (when judged? (:microdollars judge))}))))

;; ---------------------------------------------------------------------------
;; Paired comparisons
;; ---------------------------------------------------------------------------

(defn- id-str
  "A candidate id as a string: a namespaced keyword in full, a bare one by name."
  [k]
  (cond (keyword? k) (if (namespace k) (str (namespace k) "/" (name k)) (name k))
        :else (str k)))

(defn paired-cells
  "`{candidate {case {repetition passed?}}}` (`xstats/paired-verdicts`) from
   Scorecard `entries`: a case is an environment (its content id), the pass
   is the entry's `:passed?` (every check held, whatever the reward), and
   only cells with a verdict count. With `receipts` (the Attempts' receipts
   with their `:attempt/id`), an entry counts only when its receipt is there
   and is a verdict (`experiment/verdict?`): a fault, or a receipt that could
   not be loaded, is left out. `receipts` nil trusts every entry (a run
   result without receipts). Every candidate and every case it ran keeps a
   key, so one without verdicts still shows, with its cases excluded."
  [entries receipts]
  (let [receipt (when receipts (into {} (map (juxt :attempt/id identity)) receipts))]
    (reduce (fn [m e]
              (let [c (id-str (:candidate/id e))
                    case-id (get-in e [:environment :environment/content-id])
                    verdict? (or (nil? receipt)
                                 (some-> (receipt (:attempt/id e)) (#(experiment/verdict? {:attempt/receipt %}))))]
                (cond-> (update m c #(or % {}))
                  case-id (update-in [c case-id] #(or % {}))
                  (and case-id verdict?) (assoc-in [c case-id (or (:repetition e) 0)] (boolean (:passed? e))))))
            {} entries)))

(defn- paired-data
  "`xstats/paired-comparisons` of `cells` as plain data (keywords as strings).
   A `baseline` that names no candidate is refused."
  [cells baseline]
  (when (and baseline (seq cells) (not (contains? cells (id-str baseline))))
    (throw (ex-info (str "No candidate " (id-str baseline) " to compare against; the candidates are "
                         (str/join ", " (sort (keys cells))))
                    {:type ::unknown-baseline :baseline (id-str baseline)})))
  (when-let [p (and (some seq (vals cells))
                    (xstats/paired-comparisons cells {:baseline (some-> baseline id-str)}))]
    (-> p
        (update :policy name)
        (update :correction name)
        (update :comparisons (fn [cs] (mapv #(update % :verdict name) cs))))))

(defn paired
  "The paired comparisons of a run result (`{:scorecard :receipts}`), each
   pair of candidates on the cases both have a verdict for: the counts, the
   exact McNemar p (Holm-adjusted over the pairs compared) and a verdict
   (`xstats/paired-comparisons`). `baseline` (a candidate id) compares every
   other candidate against it. Nil without two candidates with entries."
  [{:keys [scorecard receipts]} & [{:keys [baseline]}]]
  (when-let [entries (seq (:scorecard/entries scorecard))]
    (paired-data (paired-cells entries receipts) baseline)))

(defn- p-str [p]
  (if (< p 1e-4)
    "< 0.0001"
    (.toPlainString (.round (bigdec p) (java.math.MathContext. 2)))))

(defn paired-markdown
  "The \"Paired comparisons\" section of the `paired` data."
  [{:keys [policy baseline alpha comparisons pairs-omitted family-size]}]
  (when (seq comparisons)
    (let [reps? (some #(< 1 (:repetitions %)) comparisons)
          m (or family-size (count comparisons))
          excluded (filter #(or (pos? (:excluded-cases %)) (pos? (:excluded-cells %))) comparisons)]
      (str
       "\n## Paired comparisons\n\n"
       "Every candidate answers the same cases, so two of them compare on the cases where they disagree: "
       "*A only* counts the cases A passed and B failed, *B only* the reverse. The exact McNemar test asks "
       "whether that split could be chance; cases both passed or both failed do not count. "
       (if reps?
         (str "With repetitions, a case counts once: the candidate with the higher pass rate over the case's "
              "repetitions wins it (an exact sign test, McNemar's with one repetition); *Tied* are cases both "
              "passed equally often but not always or never. ")
         "")
       (format "A difference is a p below %s" (str alpha))
       (cond (= m (count comparisons) 1) ""
             (= m (count comparisons)) (str ", after Holm's correction for the " m " comparisons shown")
             :else (str ", after Holm's correction for all " m " pairs (choosing the best looked at all of them)"))
       ". A pass is the Frontier's: every check held.\n\n"
       (case policy
         "against-baseline" (str "Each candidate against the baseline, " baseline ".\n\n")
         "against-best" (str "Each candidate against the one with the highest pass rate, " baseline
                             " (the other " pairs-omitted " pairs are not shown).\n\n")
         "")
       "| A | B | Cases | Both passed | A only | B only | Both failed | " (when reps? "Tied | ")
       "p | " (when (< 1 m) "p (Holm) | ") "Verdict |\n"
       "|---|---|---:|---:|---:|---:|---:|" (when reps? "---:|") "---:|" (when (< 1 m) "---:|") "---|\n"
       (str/join
        (for [{:keys [a b cases both-passed a-only b-only both-failed tied-mixed p p-adjusted verdict decidable?]} comparisons]
          (str "| " a " | " b " | " cases " | " both-passed " | " a-only " | " b-only " | " both-failed " | "
               (when reps? (str tied-mixed " | "))
               (p-str p) " | " (when (< 1 m) (str (p-str p-adjusted) " | "))
               (case verdict
                 "a-better" (str a " better")
                 "b-better" (str b " better")
                 (if decidable?
                   "no detectable difference at these cases"
                   "no detectable difference: too few disagreements to decide"))
               " |\n")))
       (when (seq excluded)
         (str "\nLeft out, where only one candidate (or neither) has a verdict (a fault, or not run yet): "
              (str/join "; " (for [{:keys [a b excluded-cases excluded-cells]} excluded]
                               (str a " / " b ": "
                                    (str/join ", "
                                              (cond-> []
                                                (pos? excluded-cases)
                                                (conj (str excluded-cases " case" (when (not= 1 excluded-cases) "s")))
                                                (pos? excluded-cells)
                                                (conj (str excluded-cells " cell" (when (not= 1 excluded-cells) "s")
                                                           " without a counterpart")))))))
              ".\n"))
       "\n\"No detectable difference\" is not \"equal\": more cases, or cases where the candidates differ, can still "
       "separate them.\n"))))

(defn- judge-markdown
  "The judge's cost, when a judge model graded the attempts: per candidate
   whose attempts it judged, at list price and as billed. It is the
   experiment's cost, so no candidate's cost above includes it."
  [rs]
  (when (some :judge-recorded? rs)
    (let [judged (filter :judge-recorded? rs)
          total (fn [field] (let [xs (map field judged)] (when (every? some? xs) (reduce + 0 xs))))]
      (str "\n### Judge\n\n"
           "What the judge model's calls cost, grading each candidate's attempts. Judging is the experiment's "
           "cost: it is in no candidate's cost per attempt, per pass or total above.\n\n"
           "| Candidate judged | Judge cost (list price) | Judge cost (billed) | Per attempt (list price) |\n"
           "|---|---:|---:|---:|\n"
           (str/join
            (for [{:keys [candidate n judge-recorded? judge-notional-total judge-billed-total]} rs
                  :when judge-recorded?]
              (format "| %s | %s | %s | %s |\n" (name candidate)
                      (if (some? judge-notional-total) (money judge-notional-total) "–")
                      (if (some? judge-billed-total) (money judge-billed-total) "–")
                      (if (and (some? judge-notional-total) (pos? n)) (money (/ judge-notional-total n)) "–"))))
           (format "| **judge, all** | %s | %s | |\n"
                   (if-some [n (total :judge-notional-total)] (money n) "–")
                   (if-some [n (total :judge-billed-total)] (money n) "–"))))))

(defn markdown
  "The pilot report: `title`, the run `result`, and optionally the bundle's
   `certification` (`dvergr.catalog.casepack`), a `note`, a `baseline`
   candidate for the paired comparisons, or their data as `paired`."
  [{:keys [title result certification note baseline] :as opts}]
  (let [rs (rows result)
        comparisons (if (contains? opts :paired) (:paired opts) (paired result {:baseline baseline}))
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
     (paired-markdown comparisons)
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
                (if (some? notional-total) (money notional-total) "–"))))
     "\nSummed time adds up every attempt's own time; the wall clock runs from the first attempt's start to "
     "the last one's end (it includes waits, and is shorter than the sum when attempts run in parallel). "
     "Cached input is the share of input tokens read from the provider's cache (– where it was not recorded).\n"
     "\nCosts are at the models' list prices (what the same tokens cost through the API), whether or not "
     "a subscription paid for them. Unreported or incompletely priced usage is shown as –, never as measured zero.\n"
     (judge-markdown rs)
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

;; ---------------------------------------------------------------------------
;; From an experiment Room's store
;; ---------------------------------------------------------------------------

(defn- bundle-certification
  "The certification of the room-workflow bundle `exp` ran, when that very
   bundle (same content id) is in `room`'s workspace."
  [room exp]
  (let [{:dataset/keys [id metadata]} (:experiment/dataset exp)]
    (when (= "room-workflow" (some-> id namespace))
      (try (let [b ((requiring-resolve 'dvergr.catalog.room/read-bundle) room (name id))]
             (when (= (str (:id b)) (:bundle metadata))
               (:certification b)))
           (catch Exception _ nil)))))

(defn- progress-note
  "What an experiment without a Scorecard has certified so far."
  [room experiment-content-id]
  (let [xs (cond->> (:experiments (experiment/progress room))
             experiment-content-id (filter #(= (str experiment-content-id) (str (:experiment/content-id %)))))
        cs (mapcat :candidates xs)]
    (str "**Incomplete:** there is no Scorecard yet: " (reduce + 0 (map :verdicts cs)) " cells have a verdict and "
         (reduce + 0 (map :faults cs)) " faulted. Running the experiment again resumes it: cells with a "
         "verdict made with the current inputs are kept, the others run again."
         (when (seq cs)
           (str "\n\n| Candidate | Verdicts | Faults | Mean reward |\n|---|---:|---:|---:|\n"
                (str/join (for [{:keys [verdicts faults reward-mean] cid :candidate/id} cs]
                            (format "| %s | %d | %d | %s |\n" (name cid) verdicts faults
                                    (if reward-mean (format "%.3f" (double reward-mean)) "–")))))))))

(defn- progress-cells
  "`paired-cells` of an experiment without a Scorecard, from the certified
   Attempts in `room`'s store: those of the ExperimentDef
   `experiment-content-id`, or of the room's only one. Faults are left out,
   as in a Scorecard; nil when the room has several experiments and none is named."
  [room experiment-content-id]
  (let [st (:store room)
        attempts (if (satisfies? store/PAttemptStore st)
                   (store/-list-attempts st (store/conversation-id room) {:limit 1000000})
                   [])
        of-exp #(get-in % [:attempt/receipt :attempt/metrics :experiment-content-id])
        ids (set (keep of-exp attempts))
        exp-id (if experiment-content-id
                 (some #(when (= (str experiment-content-id) (str %)) %) ids)
                 (when (= 1 (count ids)) (first ids)))
        ;; only what was made with the candidates' current inputs, as in a
        ;; Scorecard
        attempts (experiment/current-attempts (filter #(= exp-id (of-exp %)) attempts))]
    (when exp-id
      (paired-cells
       (for [a attempts
             :let [r (:attempt/receipt a) m (:attempt/metrics r)]]
         {:candidate/id (:experiment-candidate m)
          :environment {:environment/content-id (get-in r [:attempt/environment :environment/content-id])}
          :repetition (:experiment-repetition m)
          :attempt/id (:attempt/id a)
          :passed? (experiment/passed? r)})
       (for [a attempts]
         (assoc (:attempt/receipt a) :attempt/id (:attempt/id a)))))))

(defn inputs-note
  "What the report says of cells run again because their inputs changed
   (`experiment/inputs-changes`), or nil when none did."
  [{:keys [rerun awaiting superseded]} superseded-scorecard?]
  (when (or (pos? (or rerun 0)) (pos? (or awaiting 0)) superseded-scorecard?)
    (str "**Inputs changed:** "
         (when (pos? (or rerun 0))
           (str rerun " cell" (when (not= 1 rerun) "s") " ran again because what the candidate was presented "
                "with changed (its assembled system prompt, the tools offered, or the resolved model). "))
         (when (pos? (or awaiting 0))
           (str awaiting " cell" (when (not= 1 awaiting) "s") " made with earlier inputs "
                (if (= 1 awaiting) "awaits" "await") " a re-run (running the experiment again does it). "))
         (when superseded-scorecard?
           "The Scorecard shown was made with inputs since superseded. ")
         (when (pos? (or superseded 0))
           (str "The " superseded " earlier Attempt" (when (not= 1 superseded) "s") " stay in the store and "
                "count in no Scorecard, frontier or comparison here."))
         "\n\n")))

(defn room-report
  "The report of an experiment kept in `room`, from its store: the Scorecard
   `scorecard` (a content id), else the newest one of the ExperimentDef
   `experiment` (a content id), else the room's newest; unless named, only a
   Scorecard made with its candidates' current inputs
   (`experiment/scorecard-current?`): one whose cells ran again because their
   system prompt, tools or resolved model changed is superseded. Its Attempts'
   receipts are loaded from the store, and a room-workflow bundle's
   certification from the room's workspace. Without a Scorecard, what the
   experiment has certified so far with the current inputs. With `baseline`
   (a candidate id), the paired comparisons are every candidate against it.
   Returns `{:scorecard :experiment :complete :markdown :paired :inputs}`:
   `:paired` is the paired comparisons as data (`paired`), over the Attempts
   certified so far while there is no Scorecard; `:inputs` is
   `experiment/inputs-changes` of the experiment (cells re-run, awaiting a
   re-run, superseded Attempts), also stated in the Markdown when any."
  [room {:keys [scorecard experiment title baseline]}]
  (let [inputs-of (memoize #(experiment/inputs-of room %))
        current? (fn [sc] (experiment/scorecard-current?
                           sc (:current (inputs-of (get-in sc [:scorecard/experiment :experiment/content-id])))))
        ;; unless one is named, the newest Scorecard made with the current
        ;; inputs: one whose cells ran again with other inputs is superseded
        sc (if scorecard
             (experiment/scorecard room scorecard)
             (some #(when (current? %) %)
                   (experiment/scorecards room (cond-> {:limit 1000}
                                                 experiment (assoc :experiment-content-id experiment)))))
        exp (:scorecard/experiment sc)
        ;; without a Scorecard or a named one: the room's only ExperimentDef
        exp-id (or (:experiment/content-id exp) experiment
                   (let [st (:store room)
                         ids (when (satisfies? store/PAttemptStore st)
                               (into #{} (keep #(get-in % [:attempt/receipt :attempt/metrics :experiment-content-id]))
                                     (store/-list-attempts st (store/conversation-id room) {:limit 1000000})))]
                     (when (= 1 (count ids)) (first ids))))
        inputs (when exp-id (inputs-of exp-id))
        inputs-text (when inputs (inputs-note inputs (boolean (and sc (not (current? sc))))))
        st (:store room)
        cid (store/conversation-id room)
        receipts (vec (keep (fn [{:attempt/keys [id]}]
                              (some-> (store/-load-attempt st cid id) :attempt/receipt (assoc :attempt/id id)))
                            (:scorecard/entries sc)))
        title (or title
                  (some-> (:experiment/id exp) (#(str (namespace %) " — " (name %))))
                  (:title room))
        comparisons (if sc
                      (paired {:scorecard sc :receipts receipts} {:baseline baseline})
                      (some-> (progress-cells room experiment) (paired-data baseline)))]
    {:scorecard (some-> (:scorecard/content-id sc) str)
     :experiment (some-> (or (:experiment/content-id exp) experiment) str)
     :complete (some? sc)
     :paired comparisons
     :inputs (some-> inputs (dissoc :current))
     :markdown (markdown (cond-> {:title title :result {:scorecard sc :receipts receipts} :paired comparisons}
                           sc (assoc :certification (bundle-certification room exp))
                           (and sc inputs-text) (assoc :note (str/trimr inputs-text))
                           (nil? sc) (assoc :note (str inputs-text (progress-note room experiment)))))}))
