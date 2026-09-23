(ns dvergr.benchmarks.bfcl.provider
  "BFCL as a benchmark provider for the generic evaluation path
   (doc/evaluation-model.md). The four things a benchmark brings:

     world setup   nothing to install: a BFCL task has no state. The setup
                   records the digest of the tool specs the candidate is given,
                   so an Attempt says what its candidate saw.
     protocol      one model step: the question and the compiled tools go in,
                   the calls of the FIRST response come out. Nothing is
                   executed, as upstream; a candidate that would go on after a
                   tool result is never asked to.
     verifier      upstream's AST checker (`bfcl.core/grade`), behind the
                   evaluator boundary: it sees the recorded calls, not the run.
     private data  the possible answers never leave this namespace's closures;
                   an EnvironmentDef names a task by category and id only.

   There is no driver and no judge, so the only model in a score is the
   candidate's."
  (:require [clojure.string :as str]
            [dvergr.agent.environment :as environment]
            [dvergr.agent.evaluation :as evaluation]
            [dvergr.agent.roster :as roster]
            [dvergr.agent.spend :as spend]
            [dvergr.agent.verifiers :as verifiers]
            [dvergr.benchmarks.bfcl.core :as bfcl]
            [dvergr.benchmarks.bfcl.harness :as harness]
            [dvergr.benchmarks.live :as live]
            [dvergr.benchmarks.pyjson :as pj]
            [dvergr.model.registry :as registry]))

(def version 1)

(defn tasks
  "`{task-id task}` of `categories`, the private side included."
  ([categories] (tasks categories nil))
  ([categories opts]
   (into {} (mapcat (fn [category]
                      (map (juxt :id identity) (bfcl/load-category category opts))))
         categories)))

(defn- task-of [tasks definition]
  (let [task-id (get-in definition [:environment/task :task-id])]
    (or (get tasks task-id)
        (throw (ex-info "EnvironmentDef names an unknown BFCL task"
                        {:type ::unknown-task :task-id task-id})))))

(defn request
  "What a candidate is given for `task`: `{:system :messages :tools}`. A system
   message in the question becomes the system prompt, as upstream does for
   providers that take it separately."
  [task]
  (let [turn (first (:question task))
        system (some #(when (= "system" (get % "role")) (get % "content")) turn)]
    {:system system
     :messages (into []
                     (comp (remove #(= "system" (get % "role")))
                           (map (fn [m] {:role (keyword (get m "role")) :content (get m "content")})))
                     turn)
     :tools (mapv (fn [tool]
                    {"type" "function"
                     "function" {"name" (get tool "name")
                                 "description" (get tool "description")
                                 "parameters" (get tool "input_schema")}})
                  (bfcl/compile-tools (:functions task)))}))

(defn- tools-digest [task]
  (pj/sha256-hex (pj/dumps (bfcl/compile-tools (:functions task)) true)))

(defn decode
  "Recorded tool calls `[{:name :arguments}]` as upstream's decoded output,
   `[{tool-name arguments}]` with string keys."
  [tool-calls]
  (mapv (fn [{:keys [name arguments]}]
          {name (pj/stringify-keys (or arguments {}))})
        tool-calls))

(defn- basis []
  {:upstream (:revision bfcl/upstream) :version (:version bfcl/upstream)})

(defn capabilities
  "The trusted capabilities over `tasks` (see `tasks`): `{:world-setup
   :protocol :evaluator}`. `:agent-generate` `(fn [task]) -> generate fn`
   replaces the candidate's model (tests, replays); a generate fn takes
   `{:system :messages :tools}` and returns `{:content :tool-calls :usage}`."
  [tasks {:keys [agent-generate]}]
  {:world-setup
   (evaluation/make-world-setup
    {:id :bfcl/task-world :version version :basis (basis)
     :prepare (fn [{:keys [environment]}]
                {:world/tools-sha256 (tools-digest (task-of tasks environment))})})

   :protocol
   (evaluation/make-protocol
    {:id :bfcl/single-turn :version version :basis (basis)
     :limit-keys #{}
     :run (fn [{:keys [room agent environment]}]
            (let [task (task-of tasks environment)
                  {:bfcl/keys [harness action-space repl-guidance parallel-tool-calls]}
                  (:agent/metadata agent)
                  policy (cond-> (:agent/model-policy agent)
                           (some? parallel-tool-calls)
                           (assoc :parallel-tool-calls parallel-tool-calls))]
              (if (and (= :dvergr harness) (not agent-generate))
                ;; one step of Dvergr's agent loop, with recording functions
                (let [{:keys [calls content usage outcome]}
                      (harness/step room task (request task)
                                    (assoc policy
                                           :action-space action-space
                                           :guidance (or repl-guidance :neutral)
                                           :budget-dollars (get-in agent [:agent/program :budget-dollars])))]
                  {:termination :completed :calls calls :content content
                   :stop-reason outcome :usage usage})
                (let [generate (if agent-generate
                                 (agent-generate task)
                                 (live/model-generate policy))
                      {:keys [content tool-calls usage stop-reason]} (generate (request task))]
                  {:termination :completed
                   :calls (decode tool-calls)
                   :content (when-not (str/blank? (str content)) (str content))
                   :stop-reason stop-reason
                   :usage usage}))))})

   :evaluator
   (evaluation/make-evaluator
    {:id :bfcl/ast-checker :version version :basis (basis) :tier :trusted
     :observe (fn [{:keys [result durable agent]}]
                (let [outcome (:run/value result)
                      model (get-in agent [:agent/model-policy :model])
                      usage (:usage outcome)]
                  {:result {:termination (or (:termination outcome) :infrastructure-error)}
                   :failure (when-not (= :completed (:run/status result))
                              {:status (:run/status result)
                               :reason (:run/reason durable)
                               :message (:run/error durable)})
                   :calls (:calls outcome)
                   :content (:content outcome)
                   :episode (select-keys outcome [:usage :stop-reason])
                   ;; the Dvergr step reports its chat budget, the reference
                   ;; call the provider's raw counts
                   :spend (cond
                            (and (map? usage) (contains? usage :used)) (spend/of-budget model usage)
                            (map? usage) (spend/of-usage model usage)
                            :else spend/zero)}))
     :verify (fn [definition {:keys [calls] :as evidence}]
               (let [task (task-of tasks definition)
                     completed? (= :completed (get-in evidence [:result :termination]))
                     verdict (if completed?
                               (bfcl/grade task calls)
                               {:valid false :error-type "infrastructure:no_response"})]
                 {:reward (if (:valid verdict) 1.0 0.0)
                  :checks (cond-> {:responded completed?
                                   :valid (boolean (:valid verdict))}
                            ;; a check per error type, so a Scorecard can
                            ;; say WHY a candidate loses
                            (not (:valid verdict))
                            (assoc (keyword "bfcl.error" (str/replace (str (:error-type verdict)) #"[^A-Za-z0-9_.\-]" "_"))
                                   false))}))})})

(defn register!
  "Register `capabilities` in the process registry. Returns their references:
   `{:setup :protocol :verifier}`."
  [{:keys [world-setup protocol evaluator]}]
  {:setup (verifiers/register-world-setup! world-setup)
   :protocol (verifiers/register-protocol! protocol)
   :verifier (verifiers/register-evaluator! evaluator)})

(defn environment-def
  "EnvironmentDef for one BFCL task on the generic evaluation path."
  [task {:keys [world-setup protocol evaluator]}
   {:keys [timeout-ms cancel-timeout-ms] :or {timeout-ms (* 5 60 1000) cancel-timeout-ms 30000}}]
  (let [ver (evaluation/evaluator-ref evaluator)]
    (environment/make-environment
     {:id (keyword (str "bfcl." (:category task)) (str "task-" (:id task)))
      :task {:category (:category task) :task-id (:id task)}
      :verifier (cond-> {:id (:verifier/id ver) :version (:verifier/version ver)}
                  (:verifier/basis ver) (assoc :basis (:verifier/basis ver)))
      :limits {:timeout-ms timeout-ms :cancel-timeout-ms cancel-timeout-ms}
      :world {:isolation :ctx :settlement :discard
              :setup (evaluation/world-setup-ref world-setup)
              :protocol (evaluation/protocol-ref protocol)}
      :metadata {:benchmark :bfcl
                 :unsatisfiable? (contains? bfcl/unsatisfiable (:id task))}})))

(defn candidate-roster
  "AgentDefs for candidate specs `{:id :model :provider :harness :action-space
   :repl-guidance :parallel-tool-calls :budget-dollars}`. `:harness :reference` (default) is the model behind one
   API call; `:dvergr` is one step of Dvergr's agent loop with `:action-space
   :tools` or `:repl` (`bfcl.harness`). The prompt is the task's."
  [specs]
  (reduce (fn [team {:keys [id model provider budget-dollars harness action-space repl-guidance
                            parallel-tool-calls]
                     :or {budget-dollars 1.0 harness :reference action-space :tools
                          repl-guidance :neutral}}]
            (let [model-id (registry/resolve-alias model)]
              (when-not (contains? harness/repl-guidance repl-guidance)
                (throw (ex-info "Unknown :repl-guidance"
                                {:type ::unknown-repl-guidance :repl-guidance repl-guidance
                                 :known (set (keys harness/repl-guidance))})))
              (roster/make-agent
               team
               {:id id
                :prompt "BFCL single-turn candidate (the prompt is the task's question)"
                :tools #{}
                :model-policy {:provider (or provider (:provider (registry/get-model! model-id)))
                               :model model-id}
                :program {:kind :llm :max-model-steps 1 :budget-dollars budget-dollars}
                :metadata (cond-> {:bfcl/harness harness}
                            (some? parallel-tool-calls)
                            (assoc :bfcl/parallel-tool-calls parallel-tool-calls)
                            (= :dvergr harness) (assoc :bfcl/action-space action-space)
                            (and (= :dvergr harness) (= :repl action-space))
                            (assoc :bfcl/repl-guidance repl-guidance))})))
          (roster/make-roster {:id :bfcl/candidates})
          specs))
