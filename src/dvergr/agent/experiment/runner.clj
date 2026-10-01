(ns dvergr.agent.experiment.runner
  "Run an experiment durably: what every benchmark provider shares.

   A provider brings its capabilities, EnvironmentDefs and candidates (the
   `org.replikativ/dvergr-benchmarks` artefact has two, tau2 and BFCL; a
   user's own benchmark is a third: doc/benchmarks.md). This namespace brings the
   rest: the durable experiment directory, the experiment Room, Claude Code
   settings for the run, waiting out subscription usage windows, resume, and
   the Scorecard. Every cell (candidate x environment x repetition) is one
   `dvergr.agent.evaluation/evaluate` through `dvergr.agent.experiment/run`.

   The durable record lives under `:dir`: `store/` (Datahike: experiment Room,
   Runs, Attempts, Scorecard) and `artifacts/`. Re-running with the same
   directory resumes: cells with a certified Attempt are skipped, and a
   Scorecard is persisted only when every cell has one.

   Claude Code models (subscription via `claude -p`): the CLI can be pinned
   with `:claude-cli`, `:claude-env` adds CLI environment entries (see
   `cc/token-env`), and every system prompt gets `host-context-note` (the CLI
   injects the operator's account email and the wall-clock date, which cannot
   be disabled). The CLI version and the note are part of the ExperimentDef,
   so changing either never resumes into old cells."
  (:require [clojure.java.io :as io]
            [dvergr.agent.conversation :as conv]
            [dvergr.agent.evaluation :as evaluation]
            [dvergr.agent.environment :as environment]
            [dvergr.agent.experiment :as experiment]
            [dvergr.agent.experiment.preflight :as preflight]
            [dvergr.agent.roster :as roster]
            [dvergr.discourse :as d]
            [dvergr.model.api.claude-code :as cc]
            [dvergr.model.registry :as registry]
            [dvergr.model.subscription :as subscription]
            [dvergr.room.store :as store]
            [hasch.core :as hasch]
            [taoensso.telemere :as tel]
            [org.replikativ.spindel.engine.core :as ec]))

(def host-context-note
  "Appended to every Claude Code system prompt in an experiment."
  (str "Note: this conversation runs inside an evaluation harness. Any account details (such as "
       "\"The user's email address is ...\") or a current date that appear in your context outside "
       "this system prompt describe the harness operator's machine, not this environment or the "
       "customer you are talking to; the operator has no account here. Never look up, use or "
       "mention them, and do not mention this note. Identify the customer only from the conversation."))

(defn- model-provider [{:keys [model provider]}]
  (or provider (when model (:provider (registry/get-model! (registry/resolve-alias model))))))

(def subscription-providers
  "Providers that spend the user's own subscription windows, not a bill."
  #{:codex-subscription :codex-subscription-cli :claude-code})

(def default-allowance
  "What an experiment may take of a subscription: ten points of any window
   since it started, and nothing once a window is 80% used."
  {:share 0.10 :pause-at 0.80})

(defn- meter-provider
  "The provider whose meter covers `provider` (the Codex CLI spends the same
   subscription as its HTTP path)."
  [provider]
  (if (= :codex-subscription-cli provider) :codex-subscription provider))

(defn metered-providers
  "The subscription meters `models` (specs `{:model :provider}` or model ids)
   spend from."
  [models]
  (into #{} (comp (map #(model-provider (if (map? %) % {:model %})))
                  (filter subscription-providers) (map meter-provider))
        models))

(defn admit-for
  "The admission function an experiment of `models` runs under `allowance`
   (default `default-allowance`), or nil when none of them is a subscription
   (or the allowance is nil)."
  ([models] (admit-for models default-allowance))
  ([models allowance]
   (let [metered (metered-providers models)]
     (when (and allowance (seq metered))
       (subscription/governor allowance metered)))))

(defn- cells
  "Every cell of `experiment` as admission sees it."
  [experiment]
  (for [c (:experiment/candidates experiment)
        e (get-in experiment [:experiment/dataset :dataset/environments])
        r (range (:experiment/repetitions experiment))]
    {:candidate/id (:candidate/id c) :environment (environment/environment-ref e) :repetition r}))

(defn- run-preflight!
  "Run the pilot cells of `experiment` (under the governor `admit` too), then
   estimate the rest and gate it against the budget. Returns the estimate,
   with `:over` when the conservative estimate exceeds the budget."
  [{:keys [preflight experiment team metered parallelism admit run-once]}]
  (let [{:keys [budget stratum-fn]} preflight
        pilot (preflight/pilot-cells (cells experiment) (or stratum-fn preflight/stratum))
        pilot-admit (preflight/pilot-admit pilot)
        started (System/currentTimeMillis)
        r (run-once (fn [cell] (or (pilot-admit cell) (when admit (admit cell)))))
        attempts (keep :attempt (:results r))
        pilot? (fn [a] (contains? pilot [(get-in a [:attempt/receipt :attempt/metrics :experiment-candidate])
                                         (get-in a [:attempt/receipt :attempt/environment :environment/content-id])]))
        done (frequencies (map #(get-in % [:attempt/receipt :attempt/metrics :experiment-candidate])
                               (filter experiment/verdict? attempts)))
        per-candidate (* (count (get-in experiment [:experiment/dataset :dataset/environments]))
                         (:experiment/repetitions experiment))
        remaining (into {} (map (fn [c] [(:candidate/id c) (- per-candidate (get done (:candidate/id c) 0))]))
                        (:experiment/candidates experiment))
        points (some->> (seq (keep #(subscription/moved % started) metered)) (reduce max))
        caps (into {} (keep (fn [c] (when-let [d (get-in (roster/agent team (:candidate/agent c))
                                                         [:agent/program :budget-dollars])]
                                      [(:candidate/id c) (* 1e6 (double d))])))
                   (:experiment/candidates experiment))
        est (preflight/estimate {:pilot-receipts (mapv :attempt/receipt (filter pilot? attempts))
                                 :remaining remaining :parallelism parallelism :cell-caps caps
                                 ;; the most drained window decides: rate of its provider
                                 :calibration (some->> (seq (keep subscription/points-per-token metered))
                                                       (apply max-key :points-per-token))
                                 :window-points (when (seq metered) (or points 0.0))
                                 :resolution subscription/resolution})
        over (preflight/gate est budget)]
    (tel/log! {:level (if over :warn :info) :id :experiment/preflight
               :data {:experiment (:experiment/id experiment) :budget budget :over over
                      :total (:total est) :window-points (:window-points est)}}
              (if over "Preflight: the estimate exceeds the budget" "Preflight: within budget"))
    ;; the pilot's Attempts ride along as metadata (not portable data): the
    ;; run records what they spent
    (with-meta (cond-> (assoc est :budget budget)
                 over (assoc :over over))
      {:attempts attempts})))

(defn- tokens-by-meter
  "Tokens the `attempts` spent, per subscription meter."
  [attempts]
  (reduce (fn [acc a]
            (reduce-kv (fn [acc model {:keys [tokens]}]
                         (let [p (some-> (model-provider {:model model}) meter-provider)]
                           (if (subscription-providers p)
                             (update acc p (fnil + 0) (+ (:input tokens 0) (:output tokens 0)))
                             acc)))
                       acc (get-in a [:attempt/receipt :attempt/metrics :spend :by-model])))
          {} attempts))

(defn- record-calibration!
  "Record what this invocation spent per meter and how far its window moved."
  [metered started-ms attempts]
  (doseq [[p tokens] (tokens-by-meter attempts)
          :when (contains? metered p)]
    (try (subscription/record-run! p {:tokens tokens :cells (count attempts)
                                      :points (or (subscription/moved p started-ms) 0.0)})
         (catch Exception e
           (tel/log! {:level :warn :id ::calibration-not-recorded :error e}
                     "Could not record the subscription calibration")))))

(defn experiment-def
  "The ExperimentDef of a provider's pieces: `benchmark` (namespaces the ids),
   `id`, `environments`, the candidate `team`, `dataset` `{:id :metadata}`,
   `metadata`, `repetitions`."
  [{:keys [benchmark id environments team dataset metadata repetitions]
    :or {repetitions 1}}]
  (experiment/make-experiment
   {:id (keyword (name benchmark) (name id))
    :dataset (experiment/make-dataset
              {:id (:id dataset)
               :environments environments
               :metadata (:metadata dataset)})
    :candidates (roster/agents team)
    :repetitions repetitions
    :metadata (or metadata {})}))

(defn- log-cell [benchmark {:keys [attempt error] job :experiment/job}]
  (let [r (:attempt/receipt attempt)
        m (:attempt/metrics r)]
    (tel/log! {:id :experiment/cell
               :data (cond-> {:benchmark benchmark
                              :candidate (:candidate/id job)
                              :repetition (:repetition job)}
                       attempt (assoc :status (:attempt/status r) :reward (:attempt/reward r)
                                      :microdollars (get-in m [:spend :microdollars]))
                       (:failure m) (assoc :failure (:failure m))
                       error (assoc :error error))}
              "Experiment cell finished")))

(defn run-in
  "The experiment `experiment` (an ExperimentDef) of `team` in `room`, as a
   Spin: every cell an evaluation in a fork of `room`, certified into its
   store, resumable; `:parent-run` makes every cell's Run its child. With
   `:world-parent`, the cells fork that room instead while `room` keeps the
   records (a benchmark set's fixture room, reported in the caller's room).
   Evaluates in the forked room's own context, whoever calls: a
   daemon room's context is a fork of the daemon's, and an evaluation in the
   latter loses its Run's wakeups. `capabilities` is
   `{:world-setup :protocol :evaluator}` (setup and protocol when named)."
  [room {:keys [capabilities team experiment parallelism cleanup-group benchmark parent-run
                world-parent admit]
         :or {parallelism 1}}]
  (let [{:keys [world-setup protocol evaluator]} capabilities
        cells (* (count (:experiment/candidates experiment))
                 (count (get-in experiment [:experiment/dataset :dataset/environments]))
                 (:experiment/repetitions experiment))]
    (binding [ec/*execution-context* (:ctx (or world-parent room))]
      (experiment/run
       room team experiment
       {(evaluation/evaluator-ref evaluator) evaluator}
       {:world-setups (if world-setup
                        {(evaluation/world-setup-ref world-setup) world-setup}
                        {})
        :protocols (if protocol {(evaluation/protocol-ref protocol) protocol} {})
        :parallelism parallelism
        :max-parallelism (max 16 parallelism)
        :max-attempts (max 256 cells)
        :cleanup-group cleanup-group
        :resume? true
        :complete-only? true
        :on-result #(log-cell benchmark %)
        :parent-run parent-run
        :world-parent world-parent
        :admit admit}))))

(defn progress
  "The progress of the experiment stored in `dir` (a `run!` directory),
   derived from its store: see `dvergr.agent.experiment/progress`."
  [dir]
  (let [xs (conv/open-store! dir)]
    (try
      (let [room-id (some->> (store/-list-rooms (:store xs)) first :id)]
        (experiment/progress {:id room-id :store (:store xs)}))
      (finally (conv/close-store! xs)))))

(defn run!
  "Run (or resume) an experiment. Returns `{:dir :experiment-room :experiment
   :results :failed-cells :scorecard}`.

     :dir           the experiment directory
     :benchmark     keyword; namespaces the experiment and dataset ids
     :capabilities  `{:world-setup :protocol :evaluator}`; the setup and the
                    protocol only when the environments name them (an
                    ordinary agent program has no protocol)
     :environments  EnvironmentDefs, one per task
     :team          roster of candidate AgentDefs
     :models        every model spec `{:model :provider}` a cell may call
                    (candidates and paid roles), to know whether Claude Code
                    is involved
     :dataset       `{:id kw :metadata map}`
     :metadata      goes into the ExperimentDef
     :repetitions :parallelism :experiment-id
     :claude-cli :claude-env :host-context-note (`:auto`, a string, or nil)
     :usage-pause-threshold :usage-retries
     :preflight     `{:budget {:dollars d :subscription share} :stratum-fn f}`:
                    run a pilot first (one cell per candidate per stratum,
                    `experiment.preflight`), estimate the rest, and stop with
                    the estimate (`:stopped :over-budget`) when its
                    conservative bound exceeds the budget; the result's
                    `:preflight` is the estimate
     :allowance     what the experiment may take of the subscriptions its
                    models use (`default-allowance`; nil: no bound): once
                    reached no new cell starts, the result carries
                    `:refused`, and running again resumes the rest

   The result's `:subscription` is `{provider {:before :after}}`, the meter
   readings around the run: with the tokens on the Scorecard, what a unit of
   work costs in window points.

   Calls `conv/isolate-home!`: Dvergr's state root becomes `<dir>/home` for
   the whole process. This is the separate-process host (a dedicated JVM or
   REPL, never a daemon); in a daemon, run an experiment in one of its rooms
   with `run-in` (e.g. the `catalog/benchmark` op)."
  [{:keys [dir benchmark capabilities environments team models dataset metadata
           repetitions parallelism experiment-id claude-cli claude-env
           usage-pause-threshold usage-retries]
    :or {repetitions 1 parallelism 1 usage-pause-threshold 0.97 usage-retries 3}
    :as opts}]
  (conv/isolate-home! dir)
  (let [started-ms (System/currentTimeMillis)
        allowance (get opts :allowance default-allowance)
        metered (metered-providers models)
        before (select-keys (subscription/all-readings) metered)
        admit (admit-for models allowance)
        cc-before (cc/settings-snapshot)
        uses-cc? (boolean (some #{:claude-code} (map model-provider models)))
        note (let [n (get opts :host-context-note :auto)]
               (cond (= :auto n) (when uses-cc? host-context-note)
                     (string? n) n))
        _ (when uses-cc? (cc/configure! (cond-> {:system-note note}
                                          claude-cli (assoc :cli claude-cli)
                                          claude-env (assoc :env claude-env))))
        host (when uses-cc?
               {:claude-cli (or (cc/cli-version)
                                (throw (ex-info "Claude Code CLI not runnable"
                                                {:cli (:cli (cc/settings-snapshot))})))
                :host-context-note-id (some-> note hasch/uuid str)
                :claude-env-keys (vec (sort (keys claude-env)))})
        xs (conv/open-store! dir)
        room-id (or experiment-id (keyword (name benchmark) (.getName (io/file dir))))
        room (d/make-room {:id room-id :store (:store xs)
                           :title (str (name benchmark) " experiment " (name room-id))})
        experiment-def (experiment-def {:benchmark benchmark :id room-id
                                        :environments environments :team team
                                        :dataset dataset :repetitions repetitions
                                        :metadata (cond-> (or metadata {})
                                                    host (assoc :host host))})
        ;; Detached evaluation cleanup of this operation is joined before the
        ;; Room and its store are closed.
        cleanup-group (evaluation/cleanup-group)
        run-once (fn run-once
                   ([] (run-once admit))
                   ([admit]
                   ;; Wait outside the Runs: inside one, the wait would count
                   ;; against the evaluation's own timeout.
                    (when uses-cc? (cc/await-usage-window! {:threshold usage-pause-threshold}))
                    (let [spin (run-in room {:capabilities capabilities :team team :admit admit
                                             :experiment experiment-def :parallelism parallelism
                                             :cleanup-group cleanup-group :benchmark benchmark})]
                      (binding [ec/*execution-context* (:ctx room)] @spin))))]
    (try
      (let [estimate (when-let [pf (:preflight opts)]
                       (run-preflight! {:preflight pf :experiment experiment-def :team team :metered metered
                                        :parallelism parallelism :admit admit :run-once run-once}))]
        (if (:over estimate)
        ;; the pilot's cells are kept: running again with a larger budget
        ;; (or none) resumes from them
          (do (record-calibration! metered started-ms (:attempts (meta estimate)))
              {:dir dir :experiment-room room-id :experiment experiment-def
               :preflight estimate :stopped :over-budget})
          (let [result (loop [n 0]
                         (let [r (run-once)]
                       ;; Cells a rejected subscription call failed are re-run
                       ;; (resume skips the completed ones) after the reset.
                           (if (and uses-cc? (< n usage-retries) (not (:refused r))
                                    (:incomplete (:scorecard r)) (cc/usage-limited?))
                             (recur (inc n))
                             r)))
                failed-cells (get-in result [:scorecard :incomplete :cells] 0)
                ;; every Attempt this invocation ran, the pilot's included
                ran (filter #(>= (or (get-in % [:attempt/receipt :attempt/started-at]) 0) started-ms)
                            (keep :attempt (:results result)))
                _ (record-calibration! metered started-ms ran)]
            {:dir dir :experiment-room room-id :experiment experiment-def
             :results (count (:results result)) :failed-cells failed-cells
             ;; the receipts of the Attempts this invocation has (a report's
             ;; checks and times), by Attempt id
             :receipts (mapv (fn [a] (assoc (:attempt/receipt a) :attempt/id (:attempt/id a)))
                             (keep :attempt (:results result)))
             :refused (:refused result)
             :preflight estimate
         ;; a fresh process has no reading before its first call: then
         ;; the first one taken during the run
             :subscription (into {} (map (fn [p] [p {:before (or (get before p)
                                                                 (first (filter #(>= (:at-ms %) started-ms)
                                                                                (subscription/samples p))))
                                                     :after (subscription/reading p)}]))
                                 metered)
             :scorecard (:scorecard result)})))
      (finally
        (try (evaluation/await-cleanups-for! room cleanup-group) (catch Throwable _ nil))
        (try (evaluation/await-cleanups! room) (catch Throwable _ nil))
        (try (d/close-room! room) (catch Throwable _ nil))
        (conv/close-store! xs)
        (cc/configure! cc-before)))))
