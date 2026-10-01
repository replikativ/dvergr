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
    :judge       log P(the final answer will be correct) as a judge model
                 (`:judge`, a generate fn) estimates it from the task, the
                 particle's transcript and its answer position: fair, and
                 read-heavy — the judge reads what the proposer wrote
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
            [org.replikativ.foerster.learn :as learn]
            [org.replikativ.foerster.measure :as m]
            [org.replikativ.foerster.steer :as steer]
            [org.replikativ.spindel.effects.await :refer [await]]
            [org.replikativ.spindel.engine.context :as context]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.spin.core :as spin-core]
            [org.replikativ.spindel.spin.cps :refer [spin]]
            [clojure.string :as str]))

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

(def judge-system
  (str "You review an agent solving a spreadsheet task with tools (read, write, submit). "
       "Judge from the task, the agent's transcript so far and the current content of the answer "
       "position how likely the answer position will be graded correct once the agent finishes "
       "(it is graded by the recalculated values there against a hidden reference). Check the "
       "agent's formulas and values against the task and the data it read. Reply with only "
       "{\"p\": <probability between 0 and 1>}."))

(def ^:private result-cap 3000)

(defn- render-transcript [history]
  (str/join
   "\n\n"
   (for [{:keys [role content tool-calls]} history]
     (case role
       :user (str "TASK\n" content)
       :assistant (str "AGENT" (when (seq (str content)) (str "\n" content))
                       (apply str (for [{:keys [name arguments]} tool-calls]
                                    (str "\n-> " name " " (pr-str arguments)))))
       :tool (let [c (str content)]
               (str "RESULT\n" (if (> (count c) result-cap) (str (subs c 0 result-cap) " …") c)))))))

(defn- answer-now [task state]
  (str/join "\n" (for [piece (str/split (str (:answer-position task)) #",")
                       :let [piece (str/trim piece)
                             r (if (or (str/includes? piece "!") (nil? (:answer-sheet task)))
                                 piece
                                 (str "'" (:answer-sheet task) "'!" piece))]]
                   (str r ":\n" (provider/read-range (:wb state) r)))))

(defn parse-probability
  "The probability in a judge's reply, clamped to [0.01, 1]; 0.01 when it
  gives none."
  [text]
  (let [p (some->> (re-find #"\"p\"\s*:\s*([0-9]*\.?[0-9]+(?:[eE]-?[0-9]+)?)" (str text)) second parse-double)]
    (-> (or p 0.01) (max 0.01) (min 1.0))))

(defn- judge-potential
  "(fn [state]) -> a spin of log P(correct) by `judge`; one call per state,
  shared by a particle's resampled copies."
  [task judge calls]
  (let [cache (java.util.Collections/synchronizedMap (java.util.IdentityHashMap.))]
    (fn [state]
      (if-let [v (.get cache (:history state))]
        v
        (off-thread
         #(let [prompt (str (render-transcript (:history state))
                            "\n\nANSWER POSITION NOW\n" (answer-now task state)
                            (when (:termination state) "\n\nThe agent has submitted."))
                response (judge {:system judge-system :messages [{:role :user :content prompt}] :tools []})
                v (Math/log (parse-probability (:content response)))]
            (swap! calls (fn [{:keys [n usage]}]
                           {:n (inc (or n 0))
                            :usage (merge-with + (or usage {}) (select-keys (:usage response) [:input-tokens :output-tokens :cache-read-tokens]))}))
            (.put cache (:history state) v)
            v))))))

(defn- potential
  "(fn [state]) -> log potential of `kind` (a value or a spin)."
  [kind task {:keys [beta error-cost judge] :or {beta 2.0 error-cost 1.0}} judge-calls]
  (case kind
    :oracle (let [g (gold-of task)] #(- (* beta (wrong-cells g %))))
    :structural #(- (* error-cost (:errors % 0)))
    :judge (judge-potential task (or judge (throw (ex-info ":judge needs a judge generate fn" {}))) judge-calls)
    :none nil))

(defn run
  "SMC over the episodes of `task`. Options:

    :particles           N (default 4)
    :twist :reward       :oracle, :structural, :judge or :none (defaults :oracle)
    :judge               (fn [request]) -> response, the judge model (for :judge)
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
    :on-trajectories     (fn [trajectories]) receives every final particle's
                         trajectory (`foerster.learn/trajectories`: states,
                         reward, weight), e.g. for `training-records`

  Returns the provider's outcome of the selected episode, with its usage the
  usage of every particle, and `:search` {:particles :model-steps
  :log-evidence :resamplings :weights :judge-calls :judge-usage}."
  [task {:keys [particles twist reward resample-threshold select generate max-turns cancelled?
                on-trajectories]
         :or {particles 4 twist :oracle reward :oracle resample-threshold 1.0
              select :max-reward max-turns 30}
         :as opts}]
  (let [ctx (context/create-execution-context)
        usage (atom {})
        steps (atom 0)
        counted (fn [request]
                  (let [response (generate request)]
                    (swap! steps inc)
                    (swap! usage #(merge-with (fn [a b] (if (and (number? a) (number? b)) (+ a b) b))
                                              % (select-keys (:usage response) [:input-tokens :output-tokens :cache-read-tokens])))
                    response))
        judge-calls (atom {})
        kinds (into {} (map (fn [k] [k (potential k task opts judge-calls)])) (distinct [twist reward]))
        value (kinds twist)
        final (kinds reward)
        ;; a potential may be a spin: the judge's, for a state it has not seen
        score (fn [state] (let [v (final state)]
                            (if (number? v) v (binding [ec/*execution-context* ctx] @v))))
        model (fn []
                (steer/model (cond-> {:init (provider/initial-state task)
                                      :step (fn [state]
                                              (if (and cancelled? (cancelled?))
                                                (assoc state :termination :cancelled)
                                                (off-thread #(provider/step state counted max-turns))))
                                      :done? provider/done?
                                      :max-steps (inc max-turns)}
                               value (assoc :value value)
                               final (assoc :reward final))))]
    (try
      (let [measure (binding [ec/*execution-context* ctx]
                      (deref (spin (await (infer/smc-infer (model) particles
                                                           {:resample-threshold resample-threshold
                                                            :resampling :stratified})))
                             (* 30 60 1000) ::timeout))
            _ (when (= ::timeout measure) (throw (ex-info "SMC search timed out" {:type ::timeout})))
            _ (when on-trajectories (on-trajectories (learn/trajectories measure)))
            ps (m/get-particles measure)
            weights (mapv second ps)
            chosen (case (if (and (= :max-reward select) (nil? final)) :sample select)
                     :max-reward (apply max-key (comp score m/get-value) (map first ps))
                     :max-weight (first (apply max-key second ps))
                     :sample (first (nth ps (m/sample-categorical (m/normalize-log-weights weights)))))
            state (m/get-value chosen)
            state (cond-> state (not (provider/done? state)) (assoc :termination :max-turns))]
        (assoc (provider/outcome state)
               :usage @usage
               :search {:particles particles :model-steps @steps
                        :log-evidence (m/log-marginal measure)
                        :resamplings (count (filter :resampled? (:history measure)))
                        :weights weights
                        :judge-calls (:n @judge-calls 0)
                        :judge-usage (:usage @judge-calls {})}))
      (finally (context/stop-context! ctx)))))

(def success-question
  {:type :noul
   :instructions (str "The transcript is an agent solving a spreadsheet task. Will its answer "
                      "position be graded correct once it finishes?")})

(defn training-records
  "finetune-rstr typed-decision records (`finetune.decision.data`) of a
  search's `trajectories` on `task`: one per state a particle passed through,
  its transcript so far, asking whether the episode ends correct, with the
  final grade as the target. `:episode` {:particle :step :weight} groups a
  particle's steps for TD(λ) targets; the weights are the particles' in the
  search's target, so a learner resamples or weights by them."
  [task trajectories]
  (vec (for [[i {:keys [states weight]}] (map-indexed vector trajectories)
             :let [correct (boolean (:correct (provider/grade task (:writes (peek states)))))]
             [t state] (map-indexed vector states)]
         {:state (render-transcript (:history state))
          :questions {:success success-question}
          :gold {:success {:label correct}}
          :source-group-id (:id task)
          :episode {:particle i :step t :steps (count states) :weight weight}})))
