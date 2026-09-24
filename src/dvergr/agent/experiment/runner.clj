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
            [dvergr.agent.experiment :as experiment]
            [dvergr.agent.roster :as roster]
            [dvergr.discourse :as d]
            [dvergr.model.api.claude-code :as cc]
            [dvergr.model.registry :as registry]
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
   store, resumable; `:parent-run` makes every cell's Run its child. Evaluates
   in `room`'s own context, whoever calls: a
   daemon room's context is a fork of the daemon's, and an evaluation in the
   latter loses its Run's wakeups. `capabilities` is
   `{:world-setup :protocol :evaluator}` (setup and protocol when named)."
  [room {:keys [capabilities team experiment parallelism cleanup-group benchmark parent-run]
         :or {parallelism 1}}]
  (let [{:keys [world-setup protocol evaluator]} capabilities
        cells (* (count (:experiment/candidates experiment))
                 (count (get-in experiment [:experiment/dataset :dataset/environments]))
                 (:experiment/repetitions experiment))]
    (binding [ec/*execution-context* (:ctx room)]
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
        :parent-run parent-run}))))

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
  (let [cc-before (cc/settings-snapshot)
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
        run-once (fn []
                   ;; Wait outside the Runs: inside one, the wait would count
                   ;; against the evaluation's own timeout.
                   (when uses-cc? (cc/await-usage-window! {:threshold usage-pause-threshold}))
                   (let [spin (run-in room {:capabilities capabilities :team team
                                            :experiment experiment-def :parallelism parallelism
                                            :cleanup-group cleanup-group :benchmark benchmark})]
                     (binding [ec/*execution-context* (:ctx room)] @spin)))]
    (try
      (let [result (loop [n 0]
                     (let [r (run-once)]
                       ;; Cells a rejected subscription call failed are re-run
                       ;; (resume skips the completed ones) after the reset.
                       (if (and uses-cc? (< n usage-retries)
                                (:incomplete (:scorecard r)) (cc/usage-limited?))
                         (recur (inc n))
                         r)))
            failed-cells (get-in result [:scorecard :incomplete :cells] 0)]
        {:dir dir :experiment-room room-id :experiment experiment-def
         :results (count (:results result)) :failed-cells failed-cells
         :scorecard (:scorecard result)})
      (finally
        (try (evaluation/await-cleanups-for! room cleanup-group) (catch Throwable _ nil))
        (try (evaluation/await-cleanups! room) (catch Throwable _ nil))
        (try (d/close-room! room) (catch Throwable _ nil))
        (conv/close-store! xs)
        (cc/configure! cc-before)))))
