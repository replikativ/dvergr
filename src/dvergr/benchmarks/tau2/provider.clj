(ns dvergr.benchmarks.tau2.provider
  "tau2 as a benchmark provider for the generic evaluation path
   (doc/evaluation-model.md).

   A benchmark brings four things; everything else is
   `dvergr.agent.evaluation/evaluate` and `dvergr.agent.experiment/run`:

     world setup   the task's initial world, installed in the Run's forked world
     protocol      the conversation with the simulated customer (the driver),
                   hosted by the Run inside that world
     verifier      tau2's grader, behind the evaluator boundary: the observer
                   reads the final world, the verifier sees portable evidence
     private data  gold actions and assertions never leave this namespace's
                   closures; candidates see the policy, the tools and the
                   customer

   The customer simulator and the judge are part of what a score means, so
   their models are in the capability references (`:basis`), hence in every
   EnvironmentDef's content id."
  (:require [clojure.string :as str]
            [dvergr.agent.environment :as environment]
            [dvergr.agent.evaluation :as evaluation]
            [dvergr.agent.verifiers :as verifiers]
            [dvergr.benchmarks.tau2.core :as t2]
            [dvergr.benchmarks.tau2.episode :as episode]
            [dvergr.benchmarks.tau2.live :as live]
            [dvergr.benchmarks.tau2.pyjson :as pj]))

(def version 1)

(defn- task-of [domain definition]
  (let [task-id (get-in definition [:environment/task :task-id])]
    (or (get-in domain [:tasks task-id])
        (throw (ex-info "EnvironmentDef names an unknown tau2 task"
                        {:type ::unknown-task :domain (:domain domain) :task-id task-id})))))

(defn- basis [domain role]
  (cond-> {:upstream (:revision t2/upstream) :domain (:domain domain)}
    (:retrieval-config domain) (assoc :retrieval-config (:retrieval-config domain))
    role (assoc :model (select-keys role [:model :provider]))))

(defn capabilities
  "The trusted capabilities for `domain`: `{:world-setup :protocol :evaluator}`.
   `user` and `judge` are model specs (`{:model :provider}`); `:user-fn` and
   `:judge-fn` inject generate functions instead (tests), as do the per-task
   `:user-generate` and `:agent-generate` (`(fn [task]) -> generate fn`; the
   latter for the reference harness)."
  [domain {:keys [user judge user-fn judge-fn user-generate agent-generate]}]
  (let [user-gen (or user-fn (when-not user-generate (live/model-generate user)))
        judge-gen (or judge-fn (when judge (live/model-generate judge)))]
    {:world-setup
     (evaluation/make-world-setup
      {:id :tau2/initial-world :version version :basis (basis domain nil)
       :prepare (fn [{:keys [room environment]}]
                  (let [task (task-of domain environment)]
                    (episode/prepare-world! room domain task)
                    {:world/initial-hash ((:world-hash domain) ((:initial-world domain) task))}))})

     :protocol
     (evaluation/make-protocol
      {:id :tau2/conversation :version version :basis (basis domain user)
       :limit-keys #{:max-steps :max-errors}
       :run (fn [{:keys [room agent environment cancelled?]}]
              (let [task (task-of domain environment)
                    limits (merge {:max-steps 200 :max-errors 10}
                                  (select-keys (:environment/limits environment)
                                               [:max-steps :max-errors]))
                    outcome (episode/converse!
                             {:room room :domain domain :task task :agent agent
                              :agent-generate (when agent-generate (agent-generate task))
                              :user (if user-generate (user-generate task) user-gen)
                              :limits limits :cancelled? cancelled?
                              ;; The evaluation's own timeout cancels the Run.
                              :timeout-ms nil})]
                (when-let [failure (:failure outcome)]
                  ;; An infrastructure fault is a failed Run, never a verdict.
                  (throw (ex-info (str "tau2 conversation failed (" (name (:source failure)) "): "
                                       (:message failure))
                                  {:type ::infrastructure-fault :failure failure})))
                outcome))})

     :evaluator
     (evaluation/make-evaluator
      {:id :tau2/grader :version version :basis (basis domain judge) :tier :trusted
       :observe (fn [{:keys [environment result] room :world/room}]
                  (let [task (task-of domain environment)
                        outcome (:run/value result)
                        {:keys [world log]} (when room (episode/episode-snapshot room))
                        termination (:termination outcome)]
                    {:result {:termination termination}
                     :trajectory (vec log)
                     :episode (select-keys outcome [:steps :errors :usage :agent-runs])
                     :world (when world {:final-hash ((:world-hash domain) world)})
                     :facts (when (and world (#{:agent-stop :user-stop} termination))
                              (t2/world-facts domain task {:world world}))
                     :prompts {:user-system-sha256 (pj/sha256-hex (t2/user-system-prompt domain task))}}))
       :verify (fn [definition {:keys [trajectory facts] :as evidence}]
                 (let [task (task-of domain definition)
                       termination (get-in evidence [:result :termination])
                       grade (t2/grade-facts task
                                             {:messages (episode/trajectory trajectory)
                                              :termination termination}
                                             facts {:judge judge-gen})]
                   {:reward (double (:reward grade))
                    :checks (merge {:terminated-normally (contains? #{:agent-stop :user-stop} termination)}
                                   (into {} (map (fn [[k v]] [k (= 1.0 (double v))]))
                                         (:reward-breakdown grade)))}))})}))

(defn register!
  "Register `capabilities` in the process registry. Returns their references:
   `{:setup :protocol :verifier}`."
  [{:keys [world-setup protocol evaluator]}]
  {:setup (verifiers/register-world-setup! world-setup)
   :protocol (verifiers/register-protocol! protocol)
   :verifier (verifiers/register-evaluator! evaluator)})

(defn- id-name
  "`task-id` as the name part of a readable keyword: ids with characters a
   keyword cannot round-trip through EDN (telecom's
   `[mms_issue]a|b[PERSONA:Hard]`) are sanitized with a digest suffix."
  [task-id]
  (let [safe (str/replace (str task-id) #"[^A-Za-z0-9_.\-]" "_")]
    (if (= safe (str task-id))
      safe
      (str safe "-" (subs (pj/sha256-hex (str task-id)) 0 12)))))

(defn environment-def
  "EnvironmentDef for one tau2 task on the generic evaluation path.
   `capabilities` fixes the exact world setup, protocol and verifier."
  [domain task-id {:keys [world-setup protocol evaluator]}
   {:keys [max-steps max-errors timeout-ms cancel-timeout-ms]
    :or {max-steps 200 max-errors 10 timeout-ms (* 30 60 1000) cancel-timeout-ms 60000}}]
  (let [ver (evaluation/evaluator-ref evaluator)]
    (environment/make-environment
     {:id (keyword (str "tau2." (:domain domain)) (str "task-" (id-name task-id)))
      :task {:domain (:domain domain) :task-id task-id}
      :verifier (cond-> {:id (:verifier/id ver) :version (:verifier/version ver)}
                  (:verifier/basis ver) (assoc :basis (:verifier/basis ver)))
      :limits {:max-steps max-steps :max-errors max-errors
               :timeout-ms timeout-ms :cancel-timeout-ms cancel-timeout-ms}
      :world {:isolation :ctx :settlement :discard
              :setup (evaluation/world-setup-ref world-setup)
              :protocol (evaluation/protocol-ref protocol)}
      :metadata {:benchmark :tau2 :initial-db-hash (:initial-db-hash domain)}})))
