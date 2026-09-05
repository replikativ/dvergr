(ns dvergr.agent.arenas.renewal-bench
  "Opt-in live-provider REPL runner for the state-backed renewal arena.

   The trusted evaluator and certified Attempt remain the authority for reward.
   This dev-only runner additionally returns the host's generated tool inputs
   and semantic outcomes so API discovery failures can be diagnosed without
   widening `agent/inspect`."
  (:refer-clojure :exclude [run!])
  (:require [dvergr.activity :as activity]
            [dvergr.agent.arenas.renewal :as renewal]
            [dvergr.agent.evaluation :as evaluation]
            [dvergr.agent.experiment :as experiment]
            [dvergr.agent.roster :as roster]
            [dvergr.agent.run :as run]
            [dvergr.discourse :as discourse]
            [dvergr.resource :as resource]
            [dvergr.room.store.datahike]
            [dvergr.tools :as tools]
            [org.replikativ.spindel.engine.core :as ec]))

(def default-prompt
  (str "Work directly through the Dvergr SCI REPL. Execute the portable task "
       "contract exactly. Use clojure_eval to query the fork-local Datahike "
       "room state, construct the required immutable specialist roster, hire "
       "and await both specialists through Spindel, then call renewal_plan "
       "with their returned evidence IDs. Return the exact requested EDN "
       "shape. Do not answer from prose or guessed identifiers."))

(defn- candidate [team {:keys [id provider model prompt]}]
  (when-not (and (keyword? id) (keyword? provider) (string? model))
    (throw (ex-info "Candidate requires keyword :id/:provider and string :model"
                    {:id id :provider provider :model model})))
  (roster/make-agent
   team
   {:id id
    :prompt (or prompt default-prompt)
    :tools #{:clojure-eval :renewal-plan}
    :model-policy {:provider provider :model model}
    :program {:kind :llm :max-model-steps 16
              :budget-dollars 2.0 :auto-compact? false}}))

(defn- host-tool-trace [room root-run-id]
  (when-not (uuid? root-run-id)
    (throw (ex-info "Host tool trace requires one admitted root Run"
                    {:type ::missing-root-run :run/id root-run-id})))
  (let [run-ids (into #{} (map :run/id)
                      (run/runs room {:root-run-id root-run-id :limit 200}))]
    (into []
          (comp
           (filter #(and (= :_activity (:to %))
                         (contains? run-ids (activity/message-run-id %))))
           (map activity/tool-trace-entry)
           (filter #(seq (:tool-uses %))))
          (discourse/messages room {:limit 500 :run-ids run-ids}))))

(defn- preflight-room! [room]
  (when-not (instance? dvergr.room.store.datahike.DatahikeStore (:store room))
    (throw (ex-info "Renewal benchmark requires a durable Datahike Room"
                    {:type ::datahike-room-required
                     :room/id (:id room)})))
  (when-not (renewal/exact-tool-installed? (tools/get-tool "renewal_plan"))
    (throw (ex-info "Install the exact renewal arena tool before running"
                    {:type ::renewal-tool-not-installed
                     :tool "renewal_plan"})))
  (let [balance (resource/balance room)
        available (get balance renewal/review-unit 0)]
    (when (< available 1)
      (throw (ex-info "Renewal benchmark requires one provisioned review unit"
                      {:type ::insufficient-review-capacity
                       :room/id (:id room)
                       :resource renewal/review-unit
                       :required 1
                       :available available}))))
  room)

(defn- run-with-cleanup-barrier [room cleanup-group f]
  (let [outcome (try
                  {:value (f)}
                  (catch Throwable error
                    {:error error}))
        cleanup-error (try
                        (evaluation/await-cleanups-for! room cleanup-group)
                        nil
                        (catch Throwable error error))]
    (if-let [error (:error outcome)]
      (do
        (when cleanup-error
          (.addSuppressed ^Throwable error ^Throwable cleanup-error))
        (throw error))
      (if cleanup-error
        (throw cleanup-error)
        (:value outcome)))))

(defn run!
  "Run one live candidate through the exact renewal arena in `room`.

   `candidate-opts` requires keyword `:id` and `:provider`, string `:model`,
   and optionally a prompt. Returns the portable ExperimentDef, certified
   Attempt and Scorecard plus a host-only scoped tool-input trace. The caller
   owns the fully initialized Datahike/Kontor Room and must provision one
  renewal-review unit per Attempt before calling. Provider calls consume real
   subscription/API resources. Invalid storage or missing review capacity fail
   before the Experiment can admit model work.

     (renewal/register-tool!)
     (renewal/provision-review-capacity!
      room {:id (random-uuid) :amount 1})
     (run! room {:id :codex-renewal
                 :provider :codex-subscription
                 :model \"codex-subscription-sol\"})"
  [room candidate-opts]
  (preflight-room! room)
  (let [cleanup-group (evaluation/cleanup-group)
        environment (renewal/environment-def)
        team (candidate
              (roster/make-roster {:id :renewal/live-candidates})
              candidate-opts)
        agent (roster/agent team (:id candidate-opts))
        definition
        (experiment/make-experiment
         {:id :business/renewal-live-probe-v1
          :dataset
          (experiment/make-dataset
           {:id :business/renewal-live-probe-v1
            :environments [environment]})
          :candidates [agent]})
        result
        (run-with-cleanup-barrier
         room cleanup-group
         (fn []
           (binding [ec/*execution-context* (:ctx room)]
             @(experiment/run
               room team definition
               {renewal/verifier-ref (renewal/evaluator)}
               {:parallelism 1
                :cleanup-group cleanup-group
                :world-setups {renewal/setup-ref (renewal/world-setup)}}))))
        root-run-id (get-in result [:attempts 0 :attempt/run-id])]
    (assoc (select-keys result [:experiment :execution :attempts :scorecard])
           :host/tool-trace (host-tool-trace room root-run-id))))
