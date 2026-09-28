(ns dvergr.agent.experiment.select
  "Choose among variants of a candidate from a pilot's Scorecard: the tuning
   half of a preflight. A pilot on a development split runs a baseline and
   variants of it (a harness option, a guidance text, an action space); this
   picks the cheapest variant that is not worse than the baseline, so the full
   run, on tasks the tuning never saw, runs the efficient one.

   Per variant against the baseline, paired by world: is its reward good
   enough (within `margin`, by the mean on a pilot, by the 95% lower bound
   when confirming), and is it cheaper? The cheapest good-enough variants form
   the shortlist; with none the baseline stays. Tuning chooses on point
   estimates, because a pilot is too small to prove anything (at 12 worlds a
   reward interval is ±0.15 wide); the held-out run is where the claim is
   proven. Every variant's comparison is in the result, so the choice says
   why. Pure and portable (simmis can show it)."
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
   kw :shortlist [ids] :comparisons [...]}`. `variants` excludes `baseline`.

   `:rule` is what the evidence must show:
     :expected      (default; tuning) the mean reward difference is above
                    `-margin`: a pilot is too small to prove anything, so it
                    chooses on point estimates, and the held-out run proves
     :non-inferior  (confirmation) the 95% lower bound is above `-margin`
   Either way only cheaper variants qualify; `:shortlist` is every qualifying
   variant, cheapest first (at most `:keep`, default 2): what a held-out run
   then confirms."
  ([entries baseline variants] (choose entries baseline variants {}))
  ([entries baseline variants {:keys [margin rule keep] :or {margin 0.05 rule :expected keep 2}}]
   (let [comparisons (mapv #(compare-to entries baseline %) variants)
         good-enough? (fn [{:keys [reward]}]
                        (case rule
                          :expected (some-> (:mean reward) (> (- margin)))
                          :non-inferior (when-let [[lo _] (:interval reward)] (> lo (- margin)))))
         cheaper? (fn [{:keys [cost]}] (some-> (:ratio cost) (< 1.0)))
         shortlist (->> comparisons
                        (filter #(and (good-enough? %) (cheaper? %)))
                        (sort-by #(get-in % [:cost :ratio]))
                        (take keep)
                        (mapv :variant))]
     {:choice (or (first shortlist) baseline)
      :shortlist shortlist
      :reason (cond (seq shortlist) :cheaper-and-good-enough
                    (some good-enough? comparisons) :none-cheaper
                    :else :none-good-enough)
      :rule rule
      :baseline baseline
      :margin margin
      :comparisons comparisons})))
