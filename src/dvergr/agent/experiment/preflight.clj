(ns dvergr.agent.experiment.preflight
  "Predict what an experiment will cost before running all of it.

   A pilot runs a stratified sample of the experiment's own cells (one per
   candidate per stratum, the first repetition), so nothing is spent twice:
   the pilot's Attempts are cells of the experiment and a resumed run keeps
   them. From the pilot's bills the rest is extrapolated per candidate, since
   candidates differ in cost far more than tasks do:

     :expected      the pilot's mean per cell × the cells left
     :conservative  a one-sided 95% upper bound on the mean × the cells left
     :worst-seen    the dearest pilot cell × the cells left (agent costs are
                    heavy-tailed: an episode at its step bound costs several
                    ordinary ones)

   for list-price dollars (what a BYOK key would be billed), billed dollars,
   tokens and wall time at the run's parallelism; and, for subscriptions, the
   window points the pilot moved per token, applied to the tokens left.

   `gate` compares an estimate with a budget: `{:dollars d :subscription
   share}` (a share of a window, e.g. 0.10). This is the experiment predicting
   its own resource use before committing to it; the same measurements, kept,
   make the next prediction better.")

(defn stratum
  "The default stratum of an environment ref: its id's namespace
   (`:automationbench.sales/task-103` → \"automationbench.sales\")."
  [env-ref]
  (some-> (:environment/id env-ref) namespace))

(defn pilot-cells
  "The pilot of an experiment's `jobs` (`{:candidate/id :environment
   :repetition}`, as admission sees them, in the experiment's order): per
   candidate, the first environment of each stratum, first repetition. A set
   of `[candidate-id environment-content-id]`."
  ([jobs] (pilot-cells jobs stratum))
  ([jobs stratum-fn]
   (->> jobs
        (filter #(zero? (:repetition %)))
        (reduce (fn [picked {:keys [environment] :as job}]
                  (let [k [(:candidate/id job) (stratum-fn environment)]]
                    (if (contains? picked k)
                      picked
                      (assoc picked k [(:candidate/id job) (:environment/content-id environment)]))))
                {})
        vals
        set)))

(defn pilot-admit
  "An admission function that admits only `pilot` cells (see `pilot-cells`)."
  [pilot]
  (fn [job]
    (when-not (and (zero? (:repetition job))
                   (contains? pilot [(:candidate/id job) (get-in job [:environment :environment/content-id])]))
      {:reason :preflight-pilot})))

(defn- mean [xs] (/ (reduce + 0.0 xs) (count xs)))

(defn- ucb95
  "A one-sided 95% upper bound on the mean of `xs` (Student t; with one sample,
   the sample itself doubled)."
  [xs]
  (let [n (count xs) m (mean xs)]
    (if (< n 2)
      (* 2.0 m)
      (let [var (/ (reduce + (map #(let [d (- % m)] (* d d)) xs)) (dec n))
            ;; one-sided 95% t quantiles for small samples, else normal
            t (get {1 6.314 2 2.920 3 2.353 4 2.132 5 2.015 6 1.943 7 1.895 8 1.860 9 1.833}
                   (dec n) 1.645)]
        (+ m (* t (Math/sqrt (/ var n))))))))

(defn- cell-costs
  "Per-cell measurements of a pilot Attempt receipt."
  [receipt]
  (let [spend (get-in receipt [:attempt/metrics :spend])
        tokens (reduce + 0 (vals (select-keys (:tokens spend) [:input :output])))]
    {:list-microdollars (or (:notional-microdollars spend) (:microdollars spend) 0)
     :billed-microdollars (or (:microdollars spend) 0)
     :tokens tokens
     :elapsed-ms (or (:attempt/elapsed-ms receipt) (get-in receipt [:attempt/metrics :elapsed-ms]) 0)}))

(defn- extrapolate [samples left]
  (into {}
        (for [k [:list-microdollars :billed-microdollars :tokens :elapsed-ms]
              :let [xs (map k samples)]]
          [k {:expected (* left (mean xs))
              :conservative (* left (ucb95 xs))
              :worst-seen (* left (reduce max xs))}])))

(defn estimate
  "The estimate for the cells not yet run, from `pilot-receipts` (Attempt
   receipts of pilot cells, carrying `:attempt/metrics :experiment-candidate`).
   `remaining` is `{candidate-id cells-left}`; `parallelism` the run's;
   `window-points` the fraction of a subscription window the pilot moved (nil
   when unmetered); a reading shows whole points, so a pilot that moved none
   is counted as having moved one (`resolution`), an upper bound."
  [{:keys [pilot-receipts remaining parallelism window-points resolution]
    :or {resolution 0.01}}]
  (let [by-candidate (group-by #(get-in % [:attempt/metrics :experiment-candidate]) pilot-receipts)
        per-candidate (into {}
                            (for [[c receipts] by-candidate
                                  :let [samples (map cell-costs receipts)]]
                              [c (assoc (extrapolate samples (get remaining c 0))
                                        :pilot-cells (count samples)
                                        :cells-left (get remaining c 0))]))
        total (fn [k band] (reduce + 0.0 (map #(get-in % [k band]) (vals per-candidate))))
        pilot-tokens (reduce + 0 (map (comp :tokens cell-costs) pilot-receipts))
        points-per-token (when (and window-points (pos? pilot-tokens))
                           (/ (max (double window-points) resolution) pilot-tokens))]
    {:candidates per-candidate
     :total (into {}
                  (for [k [:list-microdollars :billed-microdollars :tokens]]
                    [k (into {} (for [band [:expected :conservative :worst-seen]]
                                  [band (total k band)]))]))
     :wall-ms (into {} (for [band [:expected :conservative :worst-seen]]
                         [band (/ (total :elapsed-ms band) (max 1 (or parallelism 1)))]))
     :window-points (when points-per-token
                      (into {} (for [band [:expected :conservative :worst-seen]]
                                 [band (* points-per-token (total :tokens band))])))
     :pilot {:cells (count pilot-receipts) :tokens pilot-tokens :window-points window-points}}))

(defn gate
  "Whether the conservative estimate fits `budget` (`{:dollars :subscription}`,
   either may be nil): nil when it does, else what exceeds."
  [{:keys [total window-points]} {:keys [dollars subscription]}]
  (let [usd (/ (get-in total [:billed-microdollars :conservative] 0.0) 1e6)
        points (get window-points :conservative)]
    (not-empty
     (cond-> {}
       (and dollars (> usd dollars)) (assoc :dollars {:estimate usd :budget dollars})
       (and subscription points (> points subscription))
       (assoc :subscription {:estimate points :budget subscription})))))
