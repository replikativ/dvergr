(ns dvergr.benchmarks.automationbench.provider
  "AutomationBench (Zapier) as a benchmark provider for the generic evaluation
   path (doc/evaluation-model.md, doc/benchmarks.md). An adapter, not a
   transcription: 47 simulated SaaS apps are ~85k lines of Python and the
   rubric ~350 assertion types, so upstream's own code runs behind a
   stateless service (`automationbench.sidecar`) and dvergr owns the episode.

     world setup   the task's initial world (upstream's `setup_state`), made
                   at a recorded instant, kept in the Run's forked world
     protocol      upstream's loop: the task's system and user prompt, the
                   toolset's tools, a model step at a time until a reply
                   without tool calls or `:max-turns` (50) model steps; each
                   call goes to the service with the world and its new world
                   comes back
     verifier      replays the recorded calls in one process on one world, as
                   upstream's runner would have run them, checks that world is
                   the one the Run kept, and grades it with upstream's rubric
                   (`partial_credit`; a pass when every scored assertion holds)
     private data  the assertions stay in the service; an EnvironmentDef names
                   a task by domain and id

   There is no driver and no judge: the only model in a score is the
   candidate's."
  (:require [clojure.string :as str]
            [dvergr.agent.environment :as environment]
            [dvergr.agent.evaluation :as evaluation]
            [dvergr.agent.roster :as roster]
            [dvergr.agent.spend :as spend]
            [dvergr.agent.verifiers :as verifiers]
            [dvergr.benchmarks.automationbench.sidecar :as sc]
            [dvergr.benchmarks.live :as live]
            [dvergr.benchmarks.pyjson :as pj]
            [dvergr.model.registry :as registry]
            [org.replikativ.spindel.engine.core :as ec]))

(def version 1)

(def ^:private episode-path [::episode])

(defn- episode-state [room]
  (binding [ec/*execution-context* (:ctx room)]
    (ec/get-state episode-path)))

(defn- swap-episode! [room f & args]
  (binding [ec/*execution-context* (:ctx room)]
    (ec/swap-state! episode-path #(apply f % args))))

(defn- task-of [definition]
  (let [{:keys [domain task-id toolset]} (:environment/task definition)]
    (when-not (and domain task-id)
      (throw (ex-info "EnvironmentDef names no AutomationBench task"
                      {:type ::unknown-task :task (:environment/task definition)})))
    {:domain domain :id task-id :toolset (or toolset "api")}))

(defn- basis [revision]
  {:upstream (:repo sc/upstream) :revision revision})

(defn- add-usage [acc usage]
  (if (map? usage)
    (merge-with (fn [a b] (if (and (number? a) (number? b)) (+ a b) b))
                acc (select-keys usage [:input-tokens :output-tokens
                                        :cache-read-tokens :cache-creation-tokens]))
    acc))

(defn- ->message [{:strs [role content]}]
  {:role (keyword role) :content content})

(defn run-episode!
  "Upstream's rollout in `room`'s world: model steps (`generate`, taking
   `{:system :messages :tools}`) until a reply without tool calls or
   `max-turns` model steps. Returns `{:termination :calls :transcript
   :usage}`; every call's world is in the Run's world as it happens."
  [{:keys [room sidecar task generate max-turns cancelled?]}]
  (let [{:keys [domain id toolset]} task
        {:keys [prompt tools]} (episode-state room)
        system (some #(when (= "system" (get % "role")) (get % "content")) prompt)
        opening (into [] (comp (remove #(= "system" (get % "role"))) (map ->message)) prompt)]
    (loop [turn 0 history opening calls [] usage {}]
      (let [done (fn [termination]
                   {:termination termination :calls calls :usage usage
                    :model-steps turn :transcript (subvec history (count opening))})]
        (cond
          (and cancelled? (cancelled?)) (done :cancelled)
          (>= turn max-turns) (done :max-turns)
          :else
          (let [{:keys [content tool-calls] :as response}
                (generate {:system system :messages history :tools tools})
                usage (add-usage usage (:usage response))
                history (conj history (cond-> {:role :assistant :content content}
                                        (seq tool-calls) (assoc :tool-calls (vec tool-calls))))]
            (if (empty? tool-calls)
              (assoc (done :agent-stop) :usage usage :model-steps (inc turn)
                     :transcript (subvec history (count opening)))
              (let [[history calls]
                    (reduce (fn [[history calls] {call-id :id :keys [name arguments]}]
                              (let [n (count calls)
                                    args (pj/stringify-keys (or arguments {}))
                                    r (sc/call sidecar domain id
                                               {:toolset toolset :n n :name name :arguments args
                                                :world (:world (episode-state room))})]
                                (swap-episode! room assoc :world (get r "world") :digest (get r "digest"))
                                [(conj history {:role :tool :id call-id :content (get r "content")})
                                 (conj calls {:n n :name name :arguments args :at (get r "at")
                                              :error (boolean (get r "error"))})]))
                            [history calls] tool-calls)]
                (recur (inc turn) history calls usage)))))))))

(defn capabilities
  "The trusted capabilities: `{:world-setup :protocol :evaluator}`.
   `:sidecar` (default: the shared one) serves upstream's code;
   `:agent-generate` `(fn [task]) -> generate fn` replaces the candidate's
   model (tests, scripted candidates)."
  [{:keys [sidecar agent-generate]}]
  (let [sidecar (or sidecar (sc/shared!))
        revision (sc/revision sidecar)]
    (when-not (= revision (:revision sc/upstream))
      (throw (ex-info "The AutomationBench checkout is not at the pinned revision"
                      {:type ::revision :checkout revision :pinned (:revision sc/upstream)})))
    {:world-setup
     (evaluation/make-world-setup
      {:id :automationbench/initial-world :version version :basis (basis revision)
       :prepare (fn [{:keys [room environment]}]
                  (let [{:keys [domain id toolset]} (task-of environment)
                        r (sc/start sidecar domain id {:toolset toolset})]
                    (swap-episode! room (constantly {:at (get r "at")
                                                     :prompt (get r "prompt")
                                                     :tools (get r "tools")
                                                     :world (get r "world")
                                                     :digest (get r "digest")}))
                    {:world/initial-digest (get r "digest")
                     :world/started-at (get r "at")
                     :task/contract (get r "contract")
                     :world/tools-sha256 (pj/sha256-hex (pj/dumps (get r "tools") true))}))})

     :protocol
     (evaluation/make-protocol
      {:id :automationbench/rollout :version version :basis (basis revision)
       :limit-keys #{:max-turns}
       :run (fn [{:keys [room agent environment cancelled? model-scope]}]
              (let [task (task-of environment)
                    max-turns (get-in environment [:environment/limits :max-turns] 50)
                    generate (live/scoped (if agent-generate
                                            (agent-generate task)
                                            (live/model-generate (:agent/model-policy agent)))
                                          model-scope)]
                (assoc (run-episode! {:room room :sidecar sidecar :task task :generate generate
                                      :max-turns max-turns :cancelled? cancelled?})
                       :started-at (:at (episode-state room))
                       :world-digest (:digest (episode-state room)))))})

     :evaluator
     (evaluation/make-evaluator
      {:id :automationbench/rubric :version version :basis (basis revision) :tier :trusted
       :observe (fn [{:keys [result durable agent]}]
                  (let [outcome (:run/value result)]
                    {:result {:termination (or (:termination outcome) :infrastructure-error)}
                     :failure (when-not (= :completed (:run/status result))
                                {:status (:run/status result)
                                 :reason (:run/reason durable)
                                 :message (:run/error durable)})
                     :calls (:calls outcome)
                     :started-at (:started-at outcome)
                     :world-digest (:world-digest outcome)
                     :episode (select-keys outcome [:model-steps :usage])
                     :transcript (:transcript outcome)
                     :spend (if (map? (:usage outcome))
                              (spend/of-usage (get-in agent [:agent/model-policy :model]) (:usage outcome))
                              spend/zero)}))
       :verify (fn [definition {:keys [calls started-at world-digest] :as evidence}]
                 (let [{:keys [domain id toolset]} (task-of definition)
                       termination (get-in evidence [:result :termination])
                       ran? (contains? #{:agent-stop :max-turns} termination)]
                   (if-not (and ran? started-at)
                     {:reward 0.0 :checks {:completed false}}
                     (let [replayed (sc/replay sidecar domain id {:toolset toolset :start-at started-at
                                                                  :calls calls})
                           grade (sc/grade sidecar domain id (get replayed "world"))
                           failed (->> (get grade "assertions")
                                       (remove #(get % "excluded"))
                                       (remove #(get % "passed"))
                                       (map #(get % "type")))]
                       {:reward (double (get grade "partial_credit"))
                        :checks (into {:completed true
                                       :stopped-by-itself (= :agent-stop termination)
                                       :world-replays (= world-digest (get replayed "digest"))
                                       :passed (boolean (get grade "passed"))}
                                      ;; a check per failed assertion type, so a
                                      ;; Scorecard can say WHY a candidate loses
                                      (map (fn [t] [(keyword "automationbench.failed"
                                                             (str/replace (str t) #"[^A-Za-z0-9_.\-]" "_"))
                                                    false]))
                                      (distinct failed))}))))})}))

(defn register!
  "Register `capabilities` in the process registry. Returns their references:
   `{:setup :protocol :verifier}`."
  [{:keys [world-setup protocol evaluator]}]
  {:setup (verifiers/register-world-setup! world-setup)
   :protocol (verifiers/register-protocol! protocol)
   :verifier (verifiers/register-evaluator! evaluator)})

(defn environment-def
  "EnvironmentDef for one task: `{\"domain\" \"id\" \"contract\"}` as
   `sidecar/tasks` lists it."
  [{:strs [domain id contract]} {:keys [world-setup protocol evaluator]}
   {:keys [toolset max-turns timeout-ms cancel-timeout-ms]
    :or {toolset "api" max-turns 50 timeout-ms (* 20 60 1000) cancel-timeout-ms 30000}}]
  (let [ver (evaluation/evaluator-ref evaluator)]
    (environment/make-environment
     {:id (keyword (str "automationbench." domain) (str "task-" id))
      :task {:domain domain :task-id id :toolset toolset :contract contract}
      :verifier (cond-> {:id (:verifier/id ver) :version (:verifier/version ver)}
                  (:verifier/basis ver) (assoc :basis (:verifier/basis ver)))
      :limits {:max-turns max-turns :timeout-ms timeout-ms :cancel-timeout-ms cancel-timeout-ms}
      :world {:isolation :ctx :settlement :discard
              :setup (evaluation/world-setup-ref world-setup)
              :protocol (evaluation/protocol-ref protocol)}
      :metadata {:benchmark :automationbench :scored? (not= "simple" domain)}})))

(defn candidate-roster
  "AgentDefs for candidate specs `{:id :model :provider :budget-dollars}`:
   the model behind upstream's loop (the prompt and tools are the task's)."
  [specs]
  (reduce (fn [team {:keys [id model provider budget-dollars] :or {budget-dollars 2.0}}]
            (let [model-id (registry/resolve-alias model)]
              (roster/make-agent
               team
               {:id id
                :prompt "AutomationBench candidate (the prompt is the task's)"
                :tools #{}
                :model-policy {:provider (or provider (:provider (registry/get-model! model-id)))
                               :model model-id}
                :program {:kind :llm :max-model-steps 50 :budget-dollars budget-dollars}
                :metadata {:automationbench/harness :reference}})))
          (roster/make-roster {:id :automationbench/candidates})
          specs))
