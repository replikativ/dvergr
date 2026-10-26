(ns dvergr.scorecard.bounds
  "Read budgets for a restricted Scorecard reader, independent of execution
   budgets and the set of all valid native Scorecards. No storage or authority.")

(def ceilings
  {:max-bytes (* 4 1024 1024)
   :max-candidates 32
   :max-cells 4096
   :max-environments 128
   :max-repetitions 128})

(defn- refuse! [reason & [dimension]]
  (throw (ex-info "Scorecard exceeds the bounded reader contract"
                  (cond-> {:type ::refused :reason reason}
                    dimension (assoc :dimension dimension)))))

(defn limits [options]
  (when-not (and (map? options) (every? (set (keys ceilings)) (keys options)))
    (refuse! :invalid-limits))
  (doseq [[key value] options]
    (when-not (and (integer? value) (pos? value) (<= value (get ceilings key)))
      (refuse! :invalid-limits key)))
  (merge ceilings options))

(defn check-value!
  "Bound cardinalities and matrix work BEFORE canonical validation. The native
   validator still checks identities, completeness, ordering and statistics."
  [value budget]
  (let [budget (limits budget)
        definition (:scorecard/experiment value)
        dataset (:experiment/dataset definition)
        candidates (:experiment/candidates definition)
        environments (:dataset/environments dataset)
        repetitions (:experiment/repetitions definition)]
    (when-not (and (map? value) (map? definition) (map? dataset))
      (refuse! :invalid-structure))
    (doseq [[dimension items] [[:max-candidates candidates]
                               [:max-environments environments]
                               [:max-cells (:scorecard/entries value)]
                               [:max-candidates (:scorecard/summary value)]]]
      (when-not (vector? items) (refuse! :invalid-structure dimension))
      (when (> (count items) (get budget dimension)) (refuse! :too-many-items dimension)))
    (when-not (and (integer? repetitions) (pos? repetitions)
                   (<= repetitions (:max-repetitions budget)))
      (refuse! :invalid-repetitions :max-repetitions))
    (when (> (*' (count candidates) (count environments) repetitions) (:max-cells budget))
      (refuse! :matrix-too-large :max-cells))
    value))
