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
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [dvergr.agent.conversation :as conv]
            [dvergr.agent.environment :as environment]
            [dvergr.agent.experiment :as experiment]
            [dvergr.agent.roster :as roster]
            [dvergr.benchmarks.tau2.core :as t2]
            [dvergr.benchmarks.tau2.episode :as episode]
            [dvergr.benchmarks.tau2.live :as live]
            [dvergr.benchmarks.tau2.provider :as provider]
            [dvergr.benchmarks.tau2.pyjson :as pj]
            [dvergr.agent.evaluation :as evaluation]
            [dvergr.discourse :as d]
            [dvergr.model.api.claude-code :as cc]
            [dvergr.model.registry :as registry]
            [dvergr.room.store :as store]
            [hasch.core :as hasch]
            [org.replikativ.spindel.engine.core :as ec]))

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

   Every cell is one `dvergr.agent.evaluation/evaluate` through
   `dvergr.agent.experiment/run`, with tau2's capabilities from
   `dvergr.benchmarks.tau2.provider`: the episode runs in a forked world that
   is discarded after certification, and the grader sits behind the evaluator
   boundary. Re-running with the same directory resumes; a Scorecard is
   persisted only when every cell completed.

   Calls `conv/isolate-home!`: Dvergr's state root becomes `<dir>/home` for
   the whole process. Run experiments in a dedicated JVM or REPL, never in a
   daemon process."
  [{:keys [dir domain task-ids split repetitions parallelism candidates user judge limits
           timeout-ms experiment-id user-fn judge-fn user-generate agent-generate
           claude-cli claude-env usage-pause-threshold usage-retries]
    :or {split "base" repetitions 1 parallelism 1
         limits {:max-steps 200 :max-errors 10} timeout-ms (* 30 60 1000)
         usage-pause-threshold 0.97 usage-retries 3}
    :as opts}]
  (conv/isolate-home! dir)
  (let [cc-before (cc/settings-snapshot)
        uses-cc? (boolean (some #{:claude-code}
                                (map model-provider (concat candidates [user judge]))))
        note (let [n (get opts :host-context-note :auto)]
               (cond (= :auto n) (when uses-cc? host-context-note)
                     (string? n) n))
        _ (when uses-cc? (cc/configure! (cond-> {:system-note note}
                                          claude-cli (assoc :cli claude-cli)
                                          claude-env (assoc :env claude-env))))
        host (when uses-cc?
               {:claude-cli (or (cc/cli-version)
                                (throw (ex-info "Claude Code CLI not runnable" {:cli (:cli (cc/settings-snapshot))})))
                :host-context-note-id (some-> note hasch/uuid str)
                ;; Names only: values may be secrets.
                :claude-env-keys (vec (sort (keys claude-env)))})
        xs (conv/open-store! dir)
        room-id (or experiment-id (keyword "tau2" (.getName (io/file dir))))
        room (d/make-room {:id room-id :store (:store xs) :title (str "tau2 experiment " (name room-id))})
        task-ids (or task-ids (get-in domain [:splits split]))
        caps (provider/capabilities
              domain
              {:user user :judge judge :user-fn user-fn :judge-fn judge-fn
               :user-generate user-generate :agent-generate agent-generate})
        definitions (mapv #(provider/environment-def domain % caps
                                                     (assoc limits :timeout-ms timeout-ms))
                          task-ids)
        team (candidate-roster candidates domain)
        experiment-def (experiment/make-experiment
                        {:id (keyword "tau2" (name room-id))
                         :dataset (experiment/make-dataset
                                   {:id (keyword (str "tau2." (:domain domain)) (str "tasks-" (hasch/uuid task-ids)))
                                    :environments definitions
                                    :metadata {:upstream t2/upstream :split (if (:task-ids opts) :explicit split)}})
                         :candidates (roster/agents team)
                         :repetitions repetitions
                         :metadata (cond-> {:limits limits}
                                     host (assoc :host host))})
        run-once (fn []
                   ;; Wait outside the Runs: inside one, the wait would count
                   ;; against the evaluation's own timeout.
                   (when uses-cc? (cc/await-usage-window! {:threshold usage-pause-threshold}))
                   (binding [ec/*execution-context* (:ctx room)]
                     @(experiment/run
                       room team experiment-def
                       {(evaluation/evaluator-ref (:evaluator caps)) (:evaluator caps)}
                       {:world-setups {(evaluation/world-setup-ref (:world-setup caps)) (:world-setup caps)}
                        :protocols {(evaluation/protocol-ref (:protocol caps)) (:protocol caps)}
                        :parallelism parallelism
                        :max-parallelism (max 16 parallelism)
                        :max-attempts (max 256 (* (count candidates) (count task-ids) repetitions))
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
