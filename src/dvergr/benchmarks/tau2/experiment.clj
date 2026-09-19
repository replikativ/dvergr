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
   with `:claude-cli` (a versioned binary), every system prompt gets
   `host-context-note` (the CLI injects the operator's account email and the
   wall-clock date, which cannot be disabled), and cells pause before
   starting while a usage window is at least `:usage-pause-threshold`
   utilized; a cell that fails on a usage limit is re-run after the reset,
   at most `:usage-retries` times. The CLI version and note are part of the
   ExperimentDef, so changing either never resumes into old cells.

   Use `dvergr.benchmarks.tau2.inspect` to read the results."
  (:refer-clojure :exclude [run!])
  (:require [clojure.java.io :as io]
            [dvergr.agent.conversation :as conv]
            [dvergr.agent.environment :as environment]
            [dvergr.agent.experiment :as experiment]
            [dvergr.agent.roster :as roster]
            [dvergr.benchmarks.tau2.core :as t2]
            [dvergr.benchmarks.tau2.episode :as episode]
            [dvergr.benchmarks.tau2.live :as live]
            [dvergr.discourse :as d]
            [dvergr.model.api.claude-code :as cc]
            [dvergr.model.registry :as registry]
            [dvergr.room.store :as store]
            [hasch.core :as hasch])
  (:import [java.util.concurrent Executors TimeUnit]))

(defn environment-def
  "Content-addressed EnvironmentDef naming one upstream tau2 task."
  [domain task-id limits]
  (environment/make-environment
   {:id (keyword (str "tau2." (:domain domain)) (str "task-" task-id))
    :task {:domain (:domain domain) :task-id task-id
           :upstream (:revision t2/upstream)
           :retrieval-config (:retrieval-config domain)}
    :verifier {:id :tau2/grader :version 1 :basis (:revision t2/upstream)}
    :limits limits
    :world {:isolation :room}
    :metadata {:benchmark :tau2 :initial-db-hash (:initial-db-hash domain)}}))

(defn candidate-roster
  "AgentDefs for candidate specs `{:id :harness :action-space :model :provider
   :max-model-steps :budget-dollars}`."
  [specs]
  (reduce (fn [team {:keys [id harness action-space model provider max-model-steps budget-dollars]
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
                :metadata (cond-> {:conversation/harness harness}
                            (= :dvergr harness) (assoc :conversation/action-space action-space))})))
          (roster/make-roster {:id :tau2/candidates})
          specs))

(def host-context-note
  "Appended to every Claude Code system prompt in an experiment."
  (str "Note: this conversation runs inside an evaluation harness. Any account "
       "details (such as an email address) or a current date that appear in your "
       "context outside this system prompt describe the harness operator's "
       "machine, not this environment or the person you are talking to. Ignore "
       "them and rely only on this system prompt, the conversation and tool results."))

(defn- model-provider [{:keys [model provider]}]
  (or provider (when model (:provider (registry/get-model! (registry/resolve-alias model))))))

(defn- cell-key
  "A cell is identified within one exact ExperimentDef (candidates, dataset,
   repetitions, user/judge models, limits): resuming under different settings
   never reuses Attempts."
  [experiment-content-id agent-hash env-content-id repetition]
  [experiment-content-id agent-hash env-content-id repetition])

(defn- existing-cells
  "Cells that already have a certified, completed Attempt."
  [store room-id]
  (into {}
        (keep (fn [a]
                (let [r (:attempt/receipt a)]
                  (when (= :completed (:attempt/status r))
                    [(cell-key (get-in r [:attempt/metrics :experiment-content-id])
                               (:attempt/agent-def-hash a)
                               (get-in a [:attempt/environment :environment/content-id])
                               (get-in r [:attempt/metrics :repetition]))
                     a]))))
        (store/-list-attempts store room-id {:limit 100000})))

(defn run!
  "Run (or resume) an experiment. Returns `{:dir :experiment-room :experiment
   :results :failed-cells :scorecard}`.

   Calls `conv/isolate-home!`: Dvergr's state root becomes `<dir>/home` for
   the whole process. Run experiments in a dedicated JVM or REPL, never in a
   daemon process."
  [{:keys [dir domain task-ids split repetitions parallelism candidates user judge limits
           timeout-ms experiment-id user-fn judge-fn agent-generate
           claude-cli usage-pause-threshold usage-retries]
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
        _ (when uses-cc? (cc/configure! (cond-> {:system-note note} claude-cli (assoc :cli claude-cli))))
        host (when uses-cc?
               {:claude-cli (or (cc/cli-version)
                                (throw (ex-info "Claude Code CLI not runnable" {:cli (:cli (cc/settings-snapshot))})))
                :host-context-note-id (some-> note hasch/uuid str)})
        xs (conv/open-store! dir)
        room-id (or experiment-id (keyword "tau2" (.getName (io/file dir))))
        room (d/make-room {:id room-id :store (:store xs) :title (str "tau2 experiment " (name room-id))})
        task-ids (or task-ids (get-in domain [:splits split]))
        definitions (mapv #(environment-def domain % limits) task-ids)
        team (candidate-roster candidates)
        experiment-def (experiment/make-experiment
                        {:id (keyword "tau2" (name room-id))
                         :dataset (experiment/make-dataset
                                   {:id (keyword (str "tau2." (:domain domain)) (str "tasks-" (hasch/uuid task-ids)))
                                    :environments definitions
                                    :metadata {:upstream t2/upstream :split (if (:task-ids domain) :explicit split)}})
                         :candidates (roster/agents team)
                         :repetitions repetitions
                         :metadata (cond-> {:user (select-keys user [:model :provider])
                                            :judge (select-keys judge [:model :provider])
                                            :limits limits}
                                     host (assoc :host host))})
        ;; `:user-fn`/`:judge-fn`/`:agent-generate` inject scripted models (tests).
        user-gen (or user-fn (live/model-generate user))
        judge-gen (or judge-fn (when judge (live/model-generate judge)))
        done (existing-cells (:store xs) room-id)
        jobs (for [candidate (:experiment/candidates experiment-def)
                   definition definitions
                   repetition (range repetitions)]
               {:candidate candidate :definition definition :repetition repetition})
        run-job (fn [{:keys [candidate definition repetition]}]
                  (let [;; Experiments carry AgentDef refs; resolve the exact def.
                        agent (roster/agent team (:candidate/agent candidate))
                        k (cell-key (:experiment/content-id experiment-def)
                                    (:candidate/agent-content-id candidate)
                                    (:environment/content-id definition) repetition)
                        run-cell (fn []
                                    (let [task (get-in domain [:tasks (get-in definition [:environment/task :task-id])])
                                          r (episode/run!
                                             {:experiment-room room :store (:store xs) :domain domain
                                              :task task :definition definition :agent agent
                                              :agent-generate (when agent-generate (agent-generate task))
                                              :user user-gen :judge judge-gen :limits limits
                                              :timeout-ms timeout-ms :repetition repetition
                                              :experiment-content-id (:experiment/content-id experiment-def)})]
                                      (:attempt r)))
                        attempt (or (get done k)
                                    (loop [n 0]
                                      (when uses-cc? (cc/await-usage-window! {:threshold usage-pause-threshold}))
                                      (let [a (run-cell)]
                                        ;; The failed Attempt stays recorded; the cell is re-run
                                        ;; once the window resets.
                                        (if (and uses-cc? (< n usage-retries)
                                                 (not= :completed (get-in a [:attempt/receipt :attempt/status]))
                                                 (cc/usage-limited?))
                                          (recur (inc n))
                                          a))))]
                    {:experiment/job {:candidate/id (:candidate/id candidate)
                                      :candidate/agent (:candidate/agent candidate)
                                      :candidate/agent-content-id (:candidate/agent-content-id candidate)
                                      :environment (environment/environment-ref definition)
                                      :repetition repetition}
                     :attempt attempt}))
        pool (Executors/newFixedThreadPool (int parallelism))]
    (try
      (let [futures (mapv (fn [job] (.submit pool ^Callable (fn [] (run-job job)))) jobs)
            ;; Every job is awaited; a job that throws is reported, not
            ;; allowed to abandon the others.
            outcomes (mapv (fn [^java.util.concurrent.Future f]
                             (try {:ok (.get f)}
                                  (catch java.util.concurrent.ExecutionException e
                                    {:error (.getMessage (.getCause e))})))
                           futures)
            results (keep :ok outcomes)
            failed-cells (count (filter #(not= :completed (get-in % [:attempt :attempt/receipt :attempt/status]))
                                        results))
            job-errors (vec (keep :error outcomes))
            ;; Only a complete, fault-free experiment gets a Scorecard; resume
            ;; re-runs failed cells.
            scorecard (if (and (zero? failed-cells) (empty? job-errors))
                        (experiment/persist-scorecard! room (experiment/make-scorecard experiment-def results))
                        {:incomplete {:failed-cells failed-cells :job-errors job-errors}})]
        {:dir dir :experiment-room room-id :experiment experiment-def
         :results (count results) :failed-cells failed-cells :scorecard scorecard})
      (finally
        (.shutdown pool)
        (.awaitTermination pool 1 TimeUnit/MINUTES)
        (try (d/close-room! room) (catch Throwable _ nil))
        (conv/close-store! xs)
        (cc/configure! cc-before)))))
