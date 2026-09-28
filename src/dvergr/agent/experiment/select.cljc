(ns dvergr.agent.experiment.select
  "Choose among variants of a candidate from a pilot's Scorecard: the tuning
   half of a preflight. A pilot on a development split runs a baseline and
   variants of it (a harness option, a guidance text, an action space); this
   picks the cheapest variant that is not worse than the baseline, so the full
   run, on tasks the tuning never saw, runs the efficient one.

   The rule, per variant against the baseline, paired by world:

     non-inferior  the 95% lower bound of the reward difference is above
                   `-margin` (default 0.05): it loses at most that much
     cheaper       its mean cost per world is below the baseline's

   Among non-inferior variants the cheapest wins; when none is non-inferior,
   or none is cheaper, the baseline stays. Every variant's comparison is in
   the result, so the choice says why. Pure and portable (simmis can show it)."
  (:require [dvergr.agent.experiment.stats :as stats]))

(defn- cost [entry]
  (let [spend (:spend entry)]
    (double (or (:notional-microdollars spend) (:microdollars spend) 0))))

(defn- per-world
  "`{world-content-id {:reward mean :cost mean}}` of one candidate's entries."
  [entries]
  (into {}
        (map (fn [[world es]]
               [world {:reward (/ (reduce + 0.0 (map #(double (:reward %)) es)) (count es))
                       :cost (/ (reduce + 0.0 (map cost es)) (count es))}]))
        (group-by #(get-in % [:environment :environment/content-id]) entries)))

(defn compare-to
  "`variant` against `baseline` over `entries` (Scorecard entries): paired
   reward difference, cost difference and cost ratio on the worlds both ran."
  [entries baseline variant]
  (let [by (group-by :candidate/id entries)
        b (per-world (get by baseline))
        v (per-world (get by variant))
        worlds (filter b (keys v))
        cost-diffs (mapv #(- (get-in v [% :cost]) (get-in b [% :cost])) worlds)
        mean-of (fn [k m] (/ (reduce + 0.0 (map #(get-in m [% k]) worlds)) (max 1 (count worlds))))]
    {:variant variant
     :worlds (count worlds)
     :reward (stats/paired-difference (mapv #(vector (get-in v [% :reward]) (get-in b [% :reward])) worlds))
     :cost {:mean (when (seq worlds) (/ (reduce + 0.0 cost-diffs) (count worlds)))
            :interval (stats/mean-interval cost-diffs [##-Inf ##Inf])
            :ratio (let [bc (mean-of :cost b)] (when (pos? bc) (/ (mean-of :cost v) bc)))}}))

(defn choose
  "The variant to run, from pilot Scorecard `entries`: `{:choice id :reason
   kw :comparisons [...]}`. `variants` excludes `baseline`."
  ([entries baseline variants] (choose entries baseline variants {}))
  ([entries baseline variants {:keys [margin] :or {margin 0.05}}]
   (let [comparisons (mapv #(compare-to entries baseline %) variants)
         non-inferior? (fn [{:keys [reward]}]
                         (when-let [[lo _] (:interval reward)] (> lo (- margin))))
         cheaper? (fn [{:keys [cost]}] (some-> (:ratio cost) (< 1.0)))
         eligible (filter #(and (non-inferior? %) (cheaper? %)) comparisons)
         best (first (sort-by #(get-in % [:cost :ratio]) eligible))]
     {:choice (if best (:variant best) baseline)
      :reason (cond best :cheaper-and-non-inferior
                    (some non-inferior? comparisons) :none-cheaper
                    :else :none-non-inferior)
      :baseline baseline
      :margin margin
      :comparisons comparisons})))
