(ns dvergr.benchmarks.runner
  "Run a benchmark experiment: what every provider shares.

   A provider (`dvergr.benchmarks.tau2.provider`, `...bfcl.provider`) brings
   its capabilities, EnvironmentDefs and candidates. This namespace brings the
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
            [hasch.core :as hasch]
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

(defn run!
  "Run (or resume) an experiment. Returns `{:dir :experiment-room :experiment
   :results :failed-cells :scorecard}`.

     :dir           the experiment directory
     :benchmark     keyword; namespaces the experiment and dataset ids
     :capabilities  `{:world-setup :protocol :evaluator}`
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
   the whole process. Run experiments in a dedicated JVM or REPL, never in a
   daemon process."
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
        {:keys [world-setup protocol evaluator]} capabilities
        experiment-def (experiment/make-experiment
                        {:id (keyword (name benchmark) (name room-id))
                         :dataset (experiment/make-dataset
                                   {:id (:id dataset)
                                    :environments environments
                                    :metadata (:metadata dataset)})
                         :candidates (roster/agents team)
                         :repetitions repetitions
                         :metadata (cond-> (or metadata {})
                                     host (assoc :host host))})
        cells (* (count (roster/agents team)) (count environments) repetitions)
        run-once (fn []
                   ;; Wait outside the Runs: inside one, the wait would count
                   ;; against the evaluation's own timeout.
                   (when uses-cc? (cc/await-usage-window! {:threshold usage-pause-threshold}))
                   (binding [ec/*execution-context* (:ctx room)]
                     @(experiment/run
                       room team experiment-def
                       {(evaluation/evaluator-ref evaluator) evaluator}
                       {:world-setups {(evaluation/world-setup-ref world-setup) world-setup}
                        :protocols {(evaluation/protocol-ref protocol) protocol}
                        :parallelism parallelism
                        :max-parallelism (max 16 parallelism)
                        :max-attempts (max 256 cells)
                        :resume? true
                        :complete-only? true})))]
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
        (try (d/close-room! room) (catch Throwable _ nil))
        (conv/close-store! xs)
        (cc/configure! cc-before)))))
