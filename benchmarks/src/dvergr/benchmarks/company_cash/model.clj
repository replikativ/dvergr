(ns dvergr.benchmarks.company-cash.model
  "Evaluate a frozen learned model in a fresh, effect-free SCI interpreter.
   Only public observations and proposed actions enter that interpreter.
   Limits are cooperative SCI limits, not an OS process boundary."
  (:require [dvergr.benchmarks.company-cash.kernel :as kernel]
            [dvergr.benchmarks.company-cash.scoring :as scoring]
            [dvergr.sandbox :as sandbox]
            [hasch.core :as hasch]
            [sci.core :as sci]))

(def policy
  {:version 1 :timeout-ms 10000 :max-allocated-bytes (* 128 1024 1024)
   :max-calls 2000000 :max-data-nodes 100000 :max-source-chars 65536
   :samples 64 :max-samples 256 :fit-seed 1701 :simulation-seed 2701
   :scoring-seed 3701 :scoring-worlds 512 :probe-seed 4701 :probe-count 1})

(defn- reject! [message]
  (throw (ex-info message {:type ::invalid-model})))

(defn- finite? [x]
  (and (number? x) (Double/isFinite (double x))))

(defn forecast-skill
  "Improvement over the pooled baseline, normalized by its excess loss over
   the reference. Negative values expose regressions; baseline is zero even for small gaps. nil means the
   baseline/reference gap is too small to identify a relative skill."
  [loss baseline reference tolerance]
  (let [gap (- baseline reference)]
    (when (> gap tolerance)
      (/ (- baseline loss) gap))))

(defn- portable!
  "Bound traversal and reject functions, records, lazy collections and handles."
  [x guard! max-nodes]
  (let [remaining (volatile! max-nodes)]
    (letfn [(visit [x]
              (guard!)
              (when (neg? (vswap! remaining dec)) (reject! "Model data exceeds its node allowance"))
              (cond
                (or (nil? x) (boolean? x) (keyword? x) (finite? x)) x
                (string? x) (if (<= (count x) 8192) x (reject! "Model string exceeds its allowance"))
                (record? x) (reject! "Model data cannot contain records")
                (vector? x) (mapv visit x)
                (map? x) (into {} (map (fn [[k v]] [(visit k) (visit v)])) x)
                :else (reject! "Model must return finite portable maps and vectors")))]
      (visit x))))

(defn- outcomes! [outcomes n company guard!]
  (when-not (and (vector? outcomes) (= n (count outcomes)))
    (reject! "simulate must return exactly n outcome maps in a vector"))
  (let [minimum (- (:cash company) (reduce + (:outflows company)))
        maximum (+ minimum (reduce + (map :amount (:invoices company))))]
    (doseq [{:keys [final-cash final-value ruin?] :as outcome} outcomes]
      (guard!)
      (when-not (and (map? outcome) (finite? final-cash) (finite? final-value)
                     (boolean? ruin?) (<= minimum final-cash final-value maximum)
                     (or (not (neg? final-cash)) ruin?))
        (reject! "Model outcomes violate the cash/value/ruin contract"))))
  outcomes)

(defn- execute*
  "Run fit(observations, seed, budget) and simulate(posterior, actions, seed, n).
   simulate returns n {:final-cash number :final-value number :ruin? boolean}
   maps. Verify repeatability and the fully factored cash conservation case.
   Host-only opts select the versioned limits and independent random seeds."
  [source observations actions opts]
  (let [{:keys [timeout-ms max-allocated-bytes max-calls max-data-nodes max-source-chars
                samples max-samples fit-seed simulation-seed] :as options} (merge policy opts)
        _ (when-not (and (string? source) (<= 1 (count source) max-source-chars))
            (reject! "A bounded /out/model/receivables.clj source file is required"))
        _ (when-not (and (integer? samples) (<= 1 samples max-samples))
            (reject! "Model sample count exceeds its allowance"))
        started (System/nanoTime)
        deadline (+ started (* 1000000 timeout-ms))
        calls (volatile! 0)
        stopped? (volatile! false)
        allocation! (sandbox/make-resource-limits :max-bytes max-allocated-bytes)
        guard! (fn []
                 (when (.isInterrupted (Thread/currentThread))
                   (throw (java.util.concurrent.CancellationException. "Model scoring worker interrupted")))
                 (when (or @stopped?
                           (>= (System/nanoTime) deadline) (> (vswap! calls inc) max-calls))
                   (vreset! stopped? true)
                   (reject! "Model evaluation deadline or call allowance reached"))
                 (try (allocation!)
                      (catch Exception e (vreset! stopped? true) (throw e))))
        ctx (sandbox/create-base-ctx :load-fn (constantly nil) :resource-limits guard!)
        ;; Base room interpreters expose host taps for debugging. This
        ;; evaluator admits no process-global publication or registration.
        _ (sci/add-namespace! ctx 'clojure.core
                              (zipmap '[tap> add-tap remove-tap]
                                      (repeat (fn [& _] (reject! "Model cannot access host taps")))))
        {model-ns :ns} (sci/eval-string+ ctx source)
        fit (sci/eval-string* ctx (str "(ns-resolve '" model-ns " 'fit)"))
        simulate (sci/eval-string* ctx (str "(ns-resolve '" model-ns " 'simulate)"))]
    (guard!)
    (when-not (and fit simulate) (reject! "Model source must define fit and simulate"))
    (let [fit-once (fn [] (portable! (@fit observations fit-seed {:max-samples max-samples}) guard! max-data-nodes))
          posterior (fit-once)
          repeated-fit (fit-once)
          _ (when-not (= posterior repeated-fit) (reject! "fit must be reproducible for an explicit seed"))
          sample (fn [plan seed n]
                   (outcomes! (portable! (@simulate posterior plan seed n) guard! max-data-nodes)
                              n (:company observations) guard!))
          outcomes (sample actions simulation-seed samples)
          repeated (sample actions simulation-seed samples)
          _ (when-not (= outcomes repeated) (reject! "simulate must be reproducible for an explicit seed"))
          company (:company observations)
          factored (zipmap (map :id (:invoices company)) (repeat :factor))
          factor-result (sample factored (inc simulation-seed) 4)
          ;; The trusted kernel sees no hidden customer parameters here: the
          ;; all-factor policy removes payment uncertainty entirely.
          expected (first (kernel/simulate (assoc company :worlds 1 :seed 1) {} factored))]
      (doseq [outcome factor-result]
        (when-not (and (< (Math/abs (- (:final-cash outcome) (:final-cash expected))) 1.0e-6)
                       (< (Math/abs (- (:final-value outcome) (:final-value expected))) 1.0e-6)
                       (= (:ruin? outcome) (:ruin? expected)))
          (reject! "Model fails factoring conservation")))
      (guard!)
      {:outcomes outcomes :posterior posterior
       :source-id (str (hasch/uuid source))
       :policy (dissoc options :scoring-seed :scoring-worlds)
       :elapsed-ms (/ (- (System/nanoTime) started) 1.0e6)
       :simulation-count (+ (* 2 samples) 4)})))

(defn execute
  "Evaluate one frozen model on public data, without host console/input or
   process-global tap access. Limits are applied by the owned caller."
  [source observations actions opts]
  (sci/binding [sci/out (java.io.PrintWriter. (java.io.Writer/nullWriter))
                sci/err (java.io.PrintWriter. (java.io.Writer/nullWriter))
                sci/in (java.io.StringReader. "")]
    (execute* source observations actions opts)))

(defn score-files
  "Score reusable model forecasts separately from the submitted decision.
   The model is fitted only on public observations, then forecast under the
   fixed :none policy. Independent hidden scoring draws never enter SCI.
   An excellent final answer without an executable model earns zero."
  [files gold observations opts]
  (let [{:keys [scoring-seed scoring-worlds] :as options} (merge policy opts)
        cash-range (reduce + (map :amount (:invoices gold)))
        _ (when-not (pos? cash-range)
            (throw (ex-info "Company-cash scoring requires a positive invoice total" {:type ::invalid-company})))
        started (System/nanoTime)]
    (try
      (let [actions (zipmap (map :id (:invoices gold)) (repeat :none))
            {:keys [outcomes source-id elapsed-ms simulation-count]}
            (execute (get files "/out/model/receivables.clj") observations actions options)
            predicted (kernel/summarize outcomes)
            scoring-world (assoc gold :seed scoring-seed :worlds scoring-worlds)
            truth (kernel/simulate scoring-world (:truth gold) actions)
            baseline (kernel/simulate scoring-world (:pooled gold) actions)
            ys (mapv :final-cash truth)
            best (scoring/pinball ys (kernel/quantiles ys))
            base-loss (scoring/pinball ys (kernel/quantiles (map :final-cash baseline)))
            loss (scoring/pinball ys (:cash predicted))
            skill (forecast-skill loss base-loss best 1.0e-6)
            cash-scale (* 1.5 cash-range)
            ;; These are positive affine transforms of proper losses on the
            ;; public outcome interval. Clamp only protects floating edges.
            cash-quality (kernel/clamp01 (- 1.0 (/ loss cash-scale)))
            base-cash-quality (kernel/clamp01 (- 1.0 (/ base-loss cash-scale)))
            brier (fn [probability]
                    (/ (reduce + (map (fn [{:keys [ruin?]}]
                                        (let [error (- probability (if ruin? 1.0 0.0))]
                                          (* error error))) truth)) scoring-worlds))
            ruin-brier (brier (:p-ruin predicted))
            base-brier (brier (:p-ruin (kernel/summarize baseline)))
            truth-brier (brier (:p-ruin (kernel/summarize truth)))
            ruin-skill (forecast-skill ruin-brier base-brier truth-brier 1.0e-6)
            ruin-quality (- 1.0 ruin-brier)
            base-ruin-quality (- 1.0 base-brier)
            model-score (+ (* 0.75 cash-quality) (* 0.25 ruin-quality))
            base-model-score (+ (* 0.75 base-cash-quality) (* 0.25 base-ruin-quality))
            decision (scoring/check {:files files :gold gold})]
        {:checks (cond-> (merge (:checks decision)
                                {:model-executable? true :model-deterministic? true :factor-conservation? true
                                 :model-no-worse-than-pooled? (>= model-score base-model-score)})
                   (some? skill) (assoc :model-cash-skilful? (>= skill 0.5))
                   (some? ruin-skill) (assoc :model-ruin-skilful? (>= ruin-skill 0.5)))
         :reward (+ (* 0.7 (or model-score 0.0)) (* 0.3 (:reward decision)))
         :metrics {:model-source-id source-id :model-pinball-loss loss :baseline-pinball-loss base-loss
                   :truth-pinball-loss best :model-cash-skill skill :model-ruin-brier ruin-brier
                   :baseline-ruin-brier base-brier :truth-ruin-brier truth-brier :model-ruin-skill ruin-skill
                   :cash-loss-scale cash-scale :model-cash-quality cash-quality :model-ruin-quality ruin-quality
                   :baseline-model-score base-model-score :baseline-cash-quality base-cash-quality
                   :baseline-ruin-quality base-ruin-quality
                   :model-samples (count outcomes) :scoring-worlds scoring-worlds
                   :scoring-seed scoring-seed :fit-seed (:fit-seed options)
                   :simulation-seed (:simulation-seed options) :model-elapsed-ms elapsed-ms
                   :model-simulation-count simulation-count :model-fit-count 2
                   :model-score model-score
                   :decision-reward (:reward decision) :decision (:metrics decision)}})
      (catch Exception error
        (if (.isInterrupted (Thread/currentThread))
          (throw error)
          {:checks {:model-executable? false}
           :reward 0.0
           :metrics {:model-elapsed-ms (/ (- (System/nanoTime) started) 1.0e6)
                     :model-error (subs (or (ex-message error) "Invalid model")
                                        0 (min 500 (count (or (ex-message error) "Invalid model"))))}})))))

(defn score-panels
  "Freeze source once, then refit it on independent businesses that were not
   available during construction. Decision scoring uses only the original
   business. A non-executable model on any panel cannot earn decision credit."
  [files gold observations opts probe-cases]
  (let [panels (into [{:id :original :gold gold :observations observations}] probe-cases)
        verdicts (mapv (fn [index {:keys [gold observations]}]
                         (score-files (if (zero? index) files
                                          (select-keys files ["/out/model/receivables.clj"]))
                                      gold observations
                                      (-> (merge policy opts)
                                          (update :scoring-seed + (* 10000 index))
                                          (update :simulation-seed + (* 10000 index)))))
                       (range) panels)
        valid? (every? #(true? (get-in % [:checks :model-executable?])) verdicts)
        mean-of (fn [values] (let [values (vec (filter some? values))]
                               (when (seq values) (/ (reduce + values) (count values)))))
        scores (mapv #(get-in % [:metrics :model-score]) verdicts)
        mean-score (mean-of scores)
        cash-skill (mean-of (map #(get-in % [:metrics :model-cash-skill]) verdicts))
        ruin-skill (mean-of (map #(get-in % [:metrics :model-ruin-skill]) verdicts))
        transfer-cash (mean-of (map #(get-in % [:metrics :model-cash-skill]) (rest verdicts)))
        transfer-ruin (mean-of (map #(get-in % [:metrics :model-ruin-skill]) (rest verdicts)))
        baseline-score (mean-of (map #(get-in % [:metrics :baseline-model-score]) verdicts))
        transfer-score (mean-of (map #(get-in % [:metrics :model-score]) (rest verdicts)))
        transfer-baseline (mean-of (map #(get-in % [:metrics :baseline-model-score]) (rest verdicts)))
        original (first verdicts)]
    {:checks (cond-> (assoc (dissoc (:checks original) :model-cash-skilful? :model-ruin-skilful?)
                            :model-executable? valid? :model-deterministic? valid? :factor-conservation? valid?
                            :model-transfer-executable? (every? #(true? (get-in % [:checks :model-executable?])) (rest verdicts)))
               (some? cash-skill) (assoc :model-cash-skilful? (>= cash-skill 0.5))
               (some? ruin-skill) (assoc :model-ruin-skilful? (>= ruin-skill 0.5))
               (some? transfer-cash) (assoc :model-transfer-cash-skilful? (>= transfer-cash 0.5))
               (some? transfer-ruin) (assoc :model-transfer-ruin-skilful? (>= transfer-ruin 0.5))
               (and (some? mean-score) (some? baseline-score))
               (assoc :model-no-worse-than-pooled? (>= mean-score baseline-score))
               (and (some? transfer-score) (some? transfer-baseline))
               (assoc :model-transfer-no-worse-than-pooled? (>= transfer-score transfer-baseline))
               (some? (get-in original [:metrics :model-cash-skill]))
               (assoc :model-original-cash-skilful? (>= (get-in original [:metrics :model-cash-skill]) 0.5))
               (some? (get-in original [:metrics :model-ruin-skill]))
               (assoc :model-original-ruin-skilful? (>= (get-in original [:metrics :model-ruin-skill]) 0.5)))
     :reward (if valid? (+ (* 0.7 (or mean-score 0.0)) (* 0.3 (get-in original [:metrics :decision-reward] 0.0))) 0.0)
     :metrics (assoc (:metrics original)
                     :model-panel-count (count panels) :model-score mean-score
                     :model-informative-panels (count (filter #(some some? [(get-in % [:metrics :model-cash-skill])
                                                                            (get-in % [:metrics :model-ruin-skill])]) verdicts))
                     :baseline-model-score baseline-score :model-transfer-score transfer-score
                     :model-original-score (get-in original [:metrics :model-score])
                     :model-original-cash-skill (get-in original [:metrics :model-cash-skill])
                     :model-original-ruin-skill (get-in original [:metrics :model-ruin-skill])
                     :model-cash-skill cash-skill :model-ruin-skill ruin-skill
                     :model-panels (mapv (fn [panel verdict]
                                           (assoc (dissoc (:metrics verdict) :decision :decision-reward)
                                                  :panel (:id panel))) panels verdicts))}))
