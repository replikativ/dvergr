(ns dvergr.benchmarks.tau2.experiment
  "tau2 experiments as certified Dvergr experiments.

   Every cell (candidate x task x repetition) is a certified conversational
   episode (`dvergr.benchmarks.tau2.episode`); cells run on bounded host
   threads. The durable record lives under the experiment directory:
   `store/` (Datahike: experiment Room, episode Rooms, Runs, messages,
   Attempts, Scorecard) and `artifacts/` (immutable values). Re-running with
   the same directory resumes: cells with a certified Attempt are skipped.
   When every cell has an Attempt the Scorecard is persisted.

     (run! {:dir \".dvergr/benchmarks/retail-cmp\" :domain dom
            :task-ids [\"0\" \"1\"] :repetitions 2 :parallelism 3
            :candidates [{:id :reference :harness :reference :model \"claude-code-sonnet\"}
                         {:id :dvergr-repl :harness :dvergr :action-space :repl
                          :model \"claude-code-sonnet\"}]
            :user {:model \"claude-code-opus\"} :judge {:model \"claude-code-opus\"}})

   Claude Code models (subscription via `claude -p`): the CLI can be pinned
   with `:claude-cli` (a versioned binary), `:claude-env` adds CLI environment
   entries (see `cc/token-env`), and every system prompt gets
   `host-context-note` (the CLI injects the operator's account email and the
   wall-clock date, which cannot be disabled). Each pass waits first while a
   usage window is at least `:usage-pause-threshold` utilized; cells a
   rejected call failed are re-run after the reset, at most `:usage-retries`
   times. The CLI version and note are part of the ExperimentDef, so changing
   either never resumes into old cells.

   Use `dvergr.benchmarks.tau2.inspect` to read the results."
  (:refer-clojure :exclude [run!])
  (:require [clojure.string :as str]
            [dvergr.agent.environment :as environment]
            [dvergr.agent.roster :as roster]
            [dvergr.benchmarks.tau2.core :as t2]
            [dvergr.benchmarks.tau2.episode :as episode]
            [dvergr.benchmarks.runner :as runner]
            [dvergr.benchmarks.tau2.provider :as provider]
            [dvergr.benchmarks.tau2.pyjson :as pj]
            [dvergr.model.registry :as registry]
            [hasch.core :as hasch]))

(defn- id-name
  "`task-id` as the name part of a readable keyword. Ids of safe characters
   (retail, airline, banking) are unchanged; others (telecom's
   `[mms_issue]a|b[PERSONA:Hard]`) are sanitized with a digest suffix, since
   the keyword must survive an EDN round trip (Attempts are stored as EDN)."
  [task-id]
  (let [safe (str/replace (str task-id) #"[^A-Za-z0-9_.\-]" "_")]
    (if (= safe (str task-id))
      safe
      (str safe "-" (subs (pj/sha256-hex (str task-id)) 0 12)))))

(defn environment-def
  "Content-addressed EnvironmentDef naming one upstream tau2 task."
  [domain task-id limits]
  (environment/make-environment
   {:id (keyword (str "tau2." (:domain domain)) (str "task-" (id-name task-id)))
    :task {:domain (:domain domain) :task-id task-id
           :upstream (:revision t2/upstream)
           :retrieval-config (:retrieval-config domain)}
    :verifier {:id :tau2/grader :version 1 :basis (:revision t2/upstream)}
    :limits limits
    :world {:isolation :room}
    :metadata {:benchmark :tau2 :initial-db-hash (:initial-db-hash domain)}}))

(defn candidate-roster
  "AgentDefs for candidate specs `{:id :harness :action-space :model :provider
   :max-model-steps :budget-dollars}`. With `domain`, each AgentDef carries the
   sha256 of the system prompt it will run with, so a prompt change is a new
   candidate (never resumed into old cells)."
  ([specs] (candidate-roster specs nil))
  ([specs domain]
   (reduce (fn [team {:keys [id harness action-space model provider max-model-steps budget-dollars
                             repl-guidance]
                      :or {harness :dvergr action-space :tools max-model-steps 100 budget-dollars 5.0}}]
             (let [model-id (registry/resolve-alias model)]
               (roster/make-agent
                team
                {:id id
                 :prompt "tau2 environment agent prompt (see EnvironmentDef)"
                 :tools #{}
                 :model-policy {:provider (or provider (:provider (registry/get-model! model-id)))
                                :model model-id}
                 :program {:kind :llm :max-model-steps max-model-steps :budget-dollars budget-dollars}
                 :metadata (let [m (cond-> {:conversation/harness harness}
                                     (= :dvergr harness) (assoc :conversation/action-space action-space))]
                             (cond-> m
                               domain (assoc :conversation/system-prompt-sha256
                                             (pj/sha256-hex (episode/agent-system-prompt
                                                             domain {:agent/metadata m})))))})))
           (roster/make-roster {:id :tau2/candidates})
           specs)))

(def host-context-note
  "Appended to every Claude Code system prompt in an experiment."
  runner/host-context-note)

(defn run!
  "Run (or resume) an experiment. Returns `{:dir :experiment-room :experiment
   :results :failed-cells :scorecard}`.

   Every cell is one `dvergr.agent.evaluation/evaluate` through
   `dvergr.agent.experiment/run`, with tau2's capabilities from
   `dvergr.benchmarks.tau2.provider`: the episode runs in a forked world that
   is discarded after certification, and the grader sits behind the evaluator
   boundary. Re-running with the same directory resumes; a Scorecard is
   persisted only when every cell completed.

   Calls `conv/isolate-home!`: Dvergr's state root becomes `<dir>/home` for
   the whole process. Run experiments in a dedicated JVM or REPL, never in a
   daemon process."
  [{:keys [domain task-ids split candidates user judge limits timeout-ms
           user-fn judge-fn user-generate agent-generate]
    :or {split "base" limits {:max-steps 200 :max-errors 10} timeout-ms (* 30 60 1000)}
    :as opts}]
  (let [task-ids (or task-ids (get-in domain [:splits split]))
        caps (provider/capabilities
              domain
              {:user user :judge judge :user-fn user-fn :judge-fn judge-fn
               :user-generate user-generate :agent-generate agent-generate})]
    (runner/run!
     (merge
      (select-keys opts [:dir :repetitions :parallelism :experiment-id :claude-cli :claude-env
                         :host-context-note :usage-pause-threshold :usage-retries])
      {:benchmark :tau2
       :capabilities caps
       :environments (mapv #(provider/environment-def domain % caps
                                                      (assoc limits :timeout-ms timeout-ms))
                           task-ids)
       :team (candidate-roster candidates domain)
       :models (concat candidates [user judge])
       :dataset {:id (keyword (str "tau2." (:domain domain)) (str "tasks-" (hasch/uuid task-ids)))
                 :metadata {:upstream t2/upstream :split (if (:task-ids opts) :explicit split)}}
       :metadata {:limits limits}}))))
