(ns dvergr.agent.workflow
  "Run one task several times on isolated forks of a room, and keep every
   attempt's world for review.

   This is the loop the product sells: an agentic task, attempted N times per
   model, each attempt on a copy-on-write fork of the room (its database, its
   repository, its REPL), each a certified Attempt with its bill, and each
   world retained so that one can be adopted by merge and the others discarded
   (`dvergr.rooms.forks/merge!` / `discard!`, the `room/merge` / `room/discard`
   ops).

   An Experiment would discard its worlds (it requires `:discard` settlement);
   these are single evaluations with `:review` settlement for that reason.

   Without a verifier of the task's own, an attempt is scored by completion:
   a reviewer chooses by the diff and the bill."
  (:require [clojure.string :as str]
            [dvergr.agent.environment :as environment]
            [dvergr.agent.evaluation :as evaluation]
            [dvergr.agent.roster :as roster]
            [dvergr.model.providers :as providers]
            [dvergr.model.registry :as registry]
            [dvergr.room.registry :as rreg]
            [dvergr.rooms.forks :as forks]
            [dvergr.tools :as tools]
            [hasch.core :as hasch]
            [org.replikativ.spindel.core :as sp]
            [org.replikativ.spindel.engine.core :as ec]))

(def max-attempts
  "Attempts per model, and models per call: bounded, since every attempt is a
   live fork and a model bill."
  {:attempts 8 :models 4})

(def completion-evaluator
  "Scores an attempt by whether its Run completed. The trusted default when a
   task brings no verifier: the choice between attempts is the reviewer's."
  (evaluation/make-evaluator
   {:id :workflow/completion :version 1
    :observe (fn [{:keys [default]}] default)
    :verify (fn [_ evidence]
              (let [status (get-in evidence [:trace :runs 0 :run/status])
                    done? (= :completed status)]
                {:checks {:completed? done?}
                 :reward (if done? 1.0 0.0)}))}))

(def ^:private default-prompt
  "You are a capable AI worker. Complete the given task thoroughly, in this
   room's files and data. Say briefly what you changed.")

(defn- model-policy [model]
  (registry/ensure-models-loaded!)
  (let [id (registry/resolve-alias model)]
    {:provider (:provider (registry/get-model! id)) :model id}))

(defn candidates
  "AgentDefs, one per model: `{:team roster :ids [agent-id ...]}`."
  [{:keys [models budget-dollars profile prompt]}]
  (let [policies (if (seq models)
                   (mapv model-policy models)
                   [(or (providers/default-spec)
                        (throw (ex-info "No LLM provider is available"
                                        {:type ::no-provider})))])
        tool-set (if (= "developer" profile)
                   tools/minimal-coding-tools
                   tools/minimal-readonly-tools)
        ids (mapv (fn [i {:keys [model]}]
                    (keyword "workflow" (str (str/replace (str model) #"[^A-Za-z0-9_.-]" "-")
                                             "-" i)))
                  (range) policies)
        team (reduce (fn [team [id policy]]
                       (roster/make-agent team
                                          {:id id
                                           :prompt (or prompt default-prompt)
                                           :tools tool-set
                                           :model-policy policy
                                           :program {:kind :llm
                                                     :budget-dollars (or budget-dollars 0.50)}}))
                     (roster/make-roster {:id :workflow/candidates})
                     (map vector ids policies))]
    {:team team :ids ids}))

(defn environment-def
  "The EnvironmentDef of `task`: its id is `environment-id` or the task's
   content hash, so the same task over time is one environment, and its
   Attempts line up. Verified by `evaluator` (default: completion)."
  [task {:keys [timeout-ms evaluator environment-id]}]
  (environment/make-environment
   {:id (or environment-id (keyword "workflow" (str "task-" (subs (str (hasch/uuid task)) 0 8))))
    :task task
    :verifier (let [ref (evaluation/evaluator-ref (or evaluator completion-evaluator))]
                {:id (:verifier/id ref) :version (:verifier/version ref)})
    :limits {:timeout-ms (or timeout-ms (* 10 60 1000)) :cancel-timeout-ms 30000
             :on-timeout :verdict}
    :world {:isolation :ctx :settlement :review}}))

(defn- delta-summary
  "A yggdrasil delta as plain data: files for a repository, datom counts for a
   database, the message for a failed diff."
  [delta]
  (cond
    (nil? delta) nil
    (:error delta) {:error (:error delta)}
    (contains? delta :files) (cond-> {:files (vec (:files delta))}
                               (:summary delta) (assoc :summary (:summary delta))
                               (:stat delta) (assoc :stat (:stat delta)))
    (contains? delta :summary) {:summary (:summary delta)}
    :else {:changed true}))

(defn review
  "What a reviewer decides on for a retained attempt world:
   `{:tier :trivial|:reviewable|:conflict :systems {system {...}} :conflicts n
     :state token}` (`:state` pins a merge to this review), or nil when the
   world is gone."
  [world-id]
  (when-let [fork (and world-id (rreg/lookup world-id))]
    (when-let [{:keys [tier diff conflicts state uncommitted]} (forks/review fork)]
      (cond-> {:tier tier
               :systems (into {} (map (fn [[k v]] [(str k) (delta-summary v)])) diff)
               :conflicts (count conflicts)
               :state state}
        (seq uncommitted) (assoc :uncommitted uncommitted)))))

(defn start
  "Start `task` `attempts` times for each of `models` on forks of `room`.
   Returns `{:spin s :ctx c :finish f}`: `s` yields the evaluations (deref or
   cancel it with `c` bound), `(f evaluations)` the rows `attempt!` returns.
   Validates before starting anything.

   The evaluations run in `room`'s own context (`c`), never a caller's: a Run's
   supervisor delivers into its room's context, and a daemon room's context is
   a child of the daemon's, so evaluating in the latter loses every wakeup.

   Attempts run one after another. Concurrent Runs on one durable room can
   kill its Datahike writer (Scriptum \"generation already has a publication
   owner\", datahike 0.8.1865); a job keeps the caller from waiting anyway."
  [room {:keys [task attempts] :as opts}]
  (when (str/blank? (str task))
    (throw (ex-info "A workflow attempt needs a task" {:type ::no-task})))
  (let [n (or attempts 1)
        _ (when-not (<= 1 n (:attempts max-attempts))
            (throw (ex-info "attempts must be between 1 and 8" {:type ::attempts :attempts n})))
        _ (when (< (:models max-attempts) (count (:models opts)))
            (throw (ex-info "at most 4 models per call" {:type ::models})))
        {:keys [team ids]} (candidates opts)
        evaluator (or (:evaluator opts) completion-evaluator)
        env (environment-def task opts)
        ctx (:ctx room)
        order (vec (for [id ids _ (range n)] id))]
    {:ctx ctx
     :spin (binding [ec/*execution-context* ctx]
             (sp/spin
              (loop [todo order acc []]
                (if (seq todo)
                  (recur (rest todo)
                         (conj acc (sp/await (evaluation/evaluate room team (first todo) env
                                                                  evaluator {}))))
                  acc))))
     :finish (fn [results]
               (binding [ec/*execution-context* ctx]
                 (mapv (fn [r]
                         (let [world (get-in r [:run/result :run/world])]
                           ;; The attempt's files, committed in its world: what
                           ;; the review shows and a merge adopts.
                           (when-let [w (and world (rreg/lookup world))]
                             (forks/commit-workspace!
                              w (str "Attempt " (get-in r [:attempt :attempt/receipt :attempt/run-id]
                                                        (name world)))))
                           {:attempt (:attempt r)
                            :world world
                            :review (review world)}))
                       results)))}))

(defn attempt!
  "Run `task` `attempts` times for each of `models` on forks of `room` and
   block until all are certified. Call at a boundary (not inside a Spin).

   Returns `[{:attempt certified-Attempt :world world-id :review review-map}]`,
   the retained worlds still open: adopt one with `forks/merge!`, drop the rest
   with `forks/discard!`."
  [room opts]
  (let [{:keys [spin ctx finish]} (start room opts)]
    (finish (binding [ec/*execution-context* ctx] @spin))))
