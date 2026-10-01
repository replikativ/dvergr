(ns dvergr.benchmarks.spreadsheetbench.smc
  "SMC over agent steps on SpreadsheetBench: N particles, each an episode (a
  plain value: workbook, writes, transcript), advanced one model turn at a
  time and resampled on a value estimate after every turn — foerster's
  `steer` (twisted SMC; target p(trajectory)·exp(reward)). The model's turn is
  the proposal: its randomness has no sample site, so the weights are the
  value estimates' increments and the final reward.

  Value estimates and rewards (`:twist`, `:reward`):
    :oracle      −β × the answer cells still wrong (the gold
                 answer: privileged — an upper bound on what steering can
                 do, never a fair candidate)
    :structural  −c × write errors so far (free, weak, fair)
    :none        no value estimate: no resampling signal (with
                 `:resample-threshold` 0, best-of-N selection by the reward)

  Every particle's model calls are counted, so a search is compared with
  single episodes and best-of-N at equal model calls."
  (:refer-clojure :exclude [await])
  (:require [dvergr.benchmarks.spreadsheetbench.core :as sb]
            [dvergr.benchmarks.spreadsheetbench.oracle :as oracle]
            [dvergr.benchmarks.spreadsheetbench.provider :as provider]
            [is.simm.partial-cps.async :as pcps-async]
            [org.replikativ.foerster.core :as infer]
            [org.replikativ.foerster.measure :as m]
            [org.replikativ.foerster.steer :as steer]
            [org.replikativ.spindel.effects.await :refer [await]]
            [org.replikativ.spindel.engine.context :as context]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.spin.core :as spin-core]
            [org.replikativ.spindel.spin.cps :refer [spin]]))

(defn- off-thread
  "A spin of `(f)` computed on a future — a model call blocks — and resumed
  in the world that awaited it."
  [f]
  (spin
   (await (fn [resolve reject]
            (let [ctx (ec/current-execution-context)]
              (future
                (let [[ok? v] (try [true (f)] (catch Throwable t [false t]))]
                  (binding [ec/*execution-context* ctx
                            pcps-async/*in-trampoline* false]
                    (spin-core/resume (if ok? resolve reject) v)))))))))

(defn- gold-of
  "The gold answer cells and the sheet names of `task`, read once."
  [task]
  (let [ranges (sb/answer-ranges task)]
    (with-open [pg (sb/open (:golden task))]
      {:gold (sb/gold-cells pg ranges)
       :names (mapv #(.getSheetName pg (int %)) (range (.getNumberOfSheets pg)))})))

(defn wrong-cells
  "How many of `task`'s answer cells `state`'s workbook still has wrong."
  [{:keys [gold names]} state]
  (count (:mismatches (oracle/compare-cells (:wb state) names gold))))

(defn- potential
  "(fn [state]) -> log potential of `kind`."
  [kind task {:keys [beta error-cost] :or {beta 2.0 error-cost 1.0}}]
  (case kind
    :oracle (let [g (gold-of task)] #(- (* beta (wrong-cells g %))))
    :structural #(- (* error-cost (:errors % 0)))
    :none nil))

(defn run
  "SMC over the episodes of `task`. Options:

    :particles           N (default 4)
    :twist :reward       :oracle, :structural or :none (defaults :oracle)
    :beta :error-cost    potential scales per cell / per error (2.0, 1.0)
    :resample-threshold  ESS fraction (default 1.0: resample every turn;
                         0: never — best-of-N)
    :select              :max-reward (default: the final particle whose state
                         scores highest under `:reward`, as best-of-N selects),
                         :max-weight or :sample (∝ weight: a draw from the
                         target). After the last resampling the weights are
                         equal, so :max-weight alone picks arbitrarily.
    :generate            (fn [request]) -> response, the model
    :max-turns           (default 30)
    :cancelled?          (fn []) -> true once the runner gives up: every
                         particle ends at its next turn

  Returns the provider's outcome of the selected episode, with its usage the
  usage of every particle, and `:search` {:particles :model-steps
  :log-evidence :resamplings :weights}."
  [task {:keys [particles twist reward resample-threshold select generate max-turns cancelled?]
         :or {particles 4 twist :oracle reward :oracle resample-threshold 1.0
              select :max-reward max-turns 30}
         :as opts}]
  (let [usage (atom {})
        steps (atom 0)
        counted (fn [request]
                  (let [response (generate request)]
                    (swap! steps inc)
                    (swap! usage #(merge-with (fn [a b] (if (and (number? a) (number? b)) (+ a b) b))
                                              % (select-keys (:usage response) [:input-tokens :output-tokens :cache-read-tokens])))
                    response))
        value (potential twist task opts)
        final (potential reward task opts)
        model (fn []
                (steer/model (cond-> {:init (provider/initial-state task)
                                      :step (fn [state]
                                              (if (and cancelled? (cancelled?))
                                                (assoc state :termination :cancelled)
                                                (off-thread #(provider/step state counted max-turns))))
                                      :done? provider/done?
                                      :max-steps (inc max-turns)}
                               value (assoc :value value)
                               final (assoc :reward final))))
        ctx (context/create-execution-context)]
    (try
      (let [measure (binding [ec/*execution-context* ctx]
                      (deref (spin (await (infer/smc-infer (model) particles
                                                           {:resample-threshold resample-threshold
                                                            :resampling :stratified})))
                             (* 30 60 1000) ::timeout))
            _ (when (= ::timeout measure) (throw (ex-info "SMC search timed out" {:type ::timeout})))
            ps (m/get-particles measure)
            weights (mapv second ps)
            chosen (case (if (and (= :max-reward select) (nil? final)) :sample select)
                     :max-reward (apply max-key (comp final m/get-value) (map first ps))
                     :max-weight (first (apply max-key second ps))
                     :sample (first (nth ps (m/sample-categorical (m/normalize-log-weights weights)))))
            state (m/get-value chosen)
            state (cond-> state (not (provider/done? state)) (assoc :termination :max-turns))]
        (assoc (provider/outcome state)
               :usage @usage
               :search {:particles particles :model-steps @steps
                        :log-evidence (m/log-marginal measure)
                        :resamplings (count (filter :resampled? (:history measure)))
                        :weights weights}))
      (finally (context/stop-context! ctx)))))
