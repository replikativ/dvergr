(ns dvergr.sandbox.ns.agent
  "SCI injectors — agent identity + work: agents (self/inbox), actors (durable
   identity), skills, tasks, scheduler. Split out of dvergr.sandbox (Phase 4).
   Subsystems reached via inline require + ns-resolve.

   Each `sci/add-namespace!` map is wrapped in `doc/with-docs` so the injected
   closures carry `:doc`/`:arglists` — without it `(clojure.repl/doc …)` and
   `(find-doc …)` answer nothing for them inside the sandbox. See
   `dvergr.sandbox.ns.doc`."
  (:require [clojure.string :as str]
            [dvergr.substrate.load :as load]
            [sci.core :as sci]
            [dvergr.runtime.ctx :as runtime-ctx]
            [dvergr.sandbox.ns.doc :as doc]
            [org.replikativ.spindel.engine.core :as ec]))

;; ============================================================================
;; Malli shapes for the injected fns' function schemas.
;;
;; These are plain literal malli forms (data), shared across the doc tables
;; below via `with-schemas`, so each fn's `:malli/schema` states the exact map
;; keys it takes and returns. They mirror the host validators
;; (`dvergr.agent.roster`, `.environment`, `.experiment`, `.program`,
;; `dvergr.room.store/validate-run!`, `dvergr.actors`, ...), which remain the
;; enforcing authority — these schemas describe, they do not validate.
;; ============================================================================

(def ^:private PosInt [:int {:min 1}])

(def ^:private SkillName
  "Skill/definition names are `str`-ed by the wrappers."
  [:or :string :keyword :symbol])

(def ^:private KeywordColl
  "Accepted wherever the host calls `(set x)` on a keyword collection."
  [:or [:set :keyword] [:sequential :keyword]])

(def ^:private AgentProgram
  "The program kinds `hire!` has interpreters for (validated at hire time)."
  [:multi {:dispatch :kind}
   [:echo [:map {:closed true}
           [:kind [:= :echo]]
           [:delay-ms {:optional true} [:int {:min 0 :max 600000}]]]]
   [:scripted [:map {:closed true}
               [:kind [:= :scripted]]
               [:delay-ms {:optional true} [:int {:min 0 :max 600000}]]
               [:reply {:optional true} :any]
               [:result {:optional true} :any]]]
   [:llm [:map {:closed true}
          [:kind [:= :llm]]
          [:max-model-steps {:optional true} [:int {:min 1 :max 256}]]
          [:budget-dollars {:optional true} [:and 'number? 'pos?]]
          [:auto-compact? {:optional true} :boolean]
          [:compaction-model {:optional true} :string]]]])

(def ^:private ModelPolicy [:map {:closed true} [:provider :keyword] [:model :string]])

(def ^:private AgentSpec
  "Friendly keys shown; canonical `:agent/*` spellings are accepted too."
  [:map
   [:id :keyword]
   [:version {:optional true} PosInt]
   [:status {:optional true} :keyword]
   [:skills {:optional true} KeywordColl]
   [:name {:optional true} :string]
   [:prompt {:optional true} :string]
   [:program {:optional true} AgentProgram]
   [:tools {:optional true} KeywordColl]
   [:model-policy {:optional true} ModelPolicy]
   [:metadata {:optional true} :any]])

(def ^:private AgentDef
  [:map {:closed true}
   [:agent/id :keyword]
   [:agent/version PosInt]
   [:agent/status :keyword]
   [:agent/skills [:set :keyword]]
   [:agent/name {:optional true} :string]
   [:agent/prompt {:optional true} :string]
   [:agent/program {:optional true} AgentProgram]
   [:agent/tools {:optional true} [:set :keyword]]
   [:agent/model-policy {:optional true} ModelPolicy]
   [:agent/metadata {:optional true} :any]])

(def ^:private AgentRef [:map [:agent/id :keyword] [:agent/version PosInt]])

(def ^:private AgentIdOrRef [:or :keyword AgentRef])

(def ^:private Roster
  [:map {:closed true}
   [:roster/agents [:map-of :keyword AgentDef]]
   [:roster/defaults :map]
   [:roster/scope :map]
   [:roster/id {:optional true} :keyword]
   [:roster/metadata {:optional true} :map]])

(def ^:private RosterOpts
  [:map {:closed true}
   [:id {:optional true} :keyword]
   [:defaults {:optional true} :map]
   [:scope {:optional true} :map]
   [:metadata {:optional true} :map]])

(def ^:private AgentSelector
  [:map
   [:id {:optional true} :keyword]
   [:status {:optional true} :keyword]
   [:skill {:optional true} :keyword]
   [:skills {:optional true} KeywordColl]
   [:where {:optional true} :map]])

(def ^:private SetupRef
  [:map {:closed true}
   [:setup/id :keyword]
   [:setup/version PosInt]
   [:setup/basis {:optional true} :any]])

(def ^:private EnvironmentSpec
  [:map {:closed true}
   [:id :keyword]
   [:version {:optional true} PosInt]
   [:task :any]
   [:verifier [:map {:closed true}
               [:id :keyword]
               [:version {:optional true} PosInt]
               [:basis {:optional true} :any]]]
   [:limits {:optional true} :map]
   [:world {:optional true} [:map [:setup {:optional true} SetupRef]]]
   [:metadata {:optional true} :map]])

(def ^:private EnvironmentDef
  [:map {:closed true}
   [:environment/id :keyword]
   [:environment/version PosInt]
   [:environment/task :any]
   [:environment/verifier [:map {:closed true}
                           [:verifier/id :keyword]
                           [:verifier/version PosInt]
                           [:verifier/basis {:optional true} :any]]]
   [:environment/limits :map]
   [:environment/world [:map [:setup {:optional true} SetupRef]]]
   [:environment/metadata {:optional true} :map]
   [:environment/content-id :uuid]])

(def ^:private EnvironmentRef
  [:map [:environment/id :keyword] [:environment/version PosInt]
   [:environment/content-id :uuid]])

(def ^:private DatasetSpec
  [:map {:closed true}
   [:id :keyword]
   [:version {:optional true} PosInt]
   [:environments [:vector {:min 1} EnvironmentDef]]
   [:metadata {:optional true} :map]])

(def ^:private DatasetDef
  [:map {:closed true}
   [:dataset/id :keyword]
   [:dataset/version PosInt]
   [:dataset/environments [:vector {:min 1} EnvironmentDef]]
   [:dataset/metadata {:optional true} :map]
   [:dataset/content-id :uuid]])

(def ^:private DatasetRef
  [:map [:dataset/id :keyword] [:dataset/version PosInt] [:dataset/content-id :uuid]])

(def ^:private ExperimentSpec
  [:map {:closed true}
   [:id :keyword]
   [:version {:optional true} PosInt]
   [:dataset DatasetDef]
   [:candidates [:vector {:min 1} AgentDef]]
   [:repetitions {:optional true} PosInt]
   [:metadata {:optional true} :map]])

(def ^:private ExperimentDef
  [:map {:closed true}
   [:experiment/id :keyword]
   [:experiment/version PosInt]
   [:experiment/dataset DatasetDef]
   [:experiment/candidates
    [:vector {:min 1} [:map {:closed true}
                       [:candidate/id :keyword]
                       [:candidate/agent AgentRef]
                       [:candidate/agent-content-id :uuid]]]]
   [:experiment/repetitions PosInt]
   [:experiment/metadata {:optional true} :map]
   [:experiment/content-id :uuid]])

(def ^:private ExperimentRef
  [:map [:experiment/id :keyword] [:experiment/version PosInt]
   [:experiment/content-id :uuid]])

(def ^:private RunHandle
  "Opaque `dvergr.agent.program.RunHandle`; `(:run/id h)`/`(:run/room h)` read it."
  :any)

(def ^:private HireOpts
  [:map {:closed true}
   [:task :any]
   [:from {:optional true} :keyword]
   [:parent-run {:optional true} :uuid]
   [:settlement {:optional true} [:enum :automatic :review :discard]]
   [:resources {:optional true}
    [:map-of {:min 1} [:or :string :keyword] [:and 'number? 'pos?]]]
   [:limits {:optional true}
    [:map {:closed true}
     [:max-model-steps {:optional true} [:int {:min 1 :max 256}]]
     [:budget-dollars {:optional true} [:and 'number? 'pos?]]]]])

(def ^:private Balance
  "Conserved resource vector: coordinate symbol (e.g. \"microUSD\") → amount
   (BigDecimal); zero coordinates are omitted."
  [:map-of :string 'number?])

(def ^:private Run
  "Durable Run projection (`dvergr.room.store/validate-run!`)."
  [:map
   [:run/id :uuid]
   [:run/kind :keyword]
   [:run/room :keyword]
   [:run/actor :keyword]
   [:run/trigger :uuid]
   [:run/status [:enum :running :waiting :completed :failed :cancelled]]
   [:run/created-at 'inst?]
   [:run/started-at 'inst?]
   [:run/updated-at 'inst?]
   [:run/ended-at {:optional true} 'inst?]
   [:run/parent {:optional true} :uuid]
   [:run/caused-by {:optional true} [:set :uuid]]
   [:run/reason {:optional true} :any]
   [:run/error {:optional true} :any]
   [:run/world {:optional true} :keyword]
   [:run/isolation {:optional true} :keyword]
   [:run/settlement-policy {:optional true} :keyword]
   [:run/settlement-status {:optional true} :keyword]
   [:run/settlement-reason {:optional true} :keyword]
   [:run/roster {:optional true} :keyword]
   [:run/agent-version {:optional true} PosInt]
   [:run/program-kind {:optional true} :keyword]
   [:run/interpreter-version {:optional true} PosInt]
   [:run/agent-def-hash {:optional true} :uuid]
   [:run/chat-id {:optional true} :uuid]])

(def ^:private RunSummary
  "`inspect`'s compact Run: instants become epoch millis."
  [:map
   [:run/id :uuid]
   [:run/kind :keyword]
   [:run/room :keyword]
   [:run/actor :keyword]
   [:run/trigger :uuid]
   [:run/status :keyword]
   [:run/started-at [:maybe :int]]
   [:run/ended-at [:maybe :int]]
   [:run/parent {:optional true} :uuid]
   [:run/world {:optional true} :keyword]
   [:run/settlement-status {:optional true} :keyword]
   [:run/settlement-reason {:optional true} :keyword]
   [:run/caused-by {:optional true} [:set :uuid]]
   [:run/caused-by-count {:optional true} :int]
   [:run/caused-by-truncated? {:optional true} :boolean]
   [:run/reason {:optional true} :any]
   [:run/error {:optional true} :any]])

(def ^:private MessageSummary
  [:map
   [:message/id :any]
   [:message/from :any]
   [:message/to :any]
   [:message/at :any]
   [:message/in-reply-to :any]
   [:message/thread-root-id :any]
   [:message/run-id [:maybe :uuid]]
   [:message/content-preview {:optional true} :string]
   [:message/content-truncated? {:optional true} :boolean]
   [:message/activities {:optional true} [:vector :map]]
   [:message/activity-count {:optional true} :int]
   [:message/activities-truncated? {:optional true} :boolean]
   [:message/tool-uses {:optional true}
    [:vector [:map [:tool-use/id :any] [:tool-use/name :any]]]]
   [:message/tool-use-count {:optional true} :int]
   [:message/tool-uses-truncated? {:optional true} :boolean]])

(def ^:private InspectOpts
  [:map {:closed true}
   [:run-limit {:optional true} PosInt]
   [:message-limit {:optional true} PosInt]
   [:content-limit {:optional true} PosInt]
   [:content-budget {:optional true} PosInt]
   [:detail-limit {:optional true} PosInt]])

(def ^:private Observation
  [:map
   [:observation/room-id :keyword]
   [:observation/scope-run-id :uuid]
   [:observation/runs [:vector RunSummary]]
   [:observation/frontier [:vector :uuid]]
   [:observation/messages [:vector MessageSummary]]
   [:observation/activities [:vector :map]]
   [:observation/failures [:vector :map]]
   [:observation/summary [:map
                          [:runs :int] [:active :int] [:messages :int]
                          [:activities :int] [:failures :int]
                          [:possibly-truncated? :boolean]]]
   [:observation/resources {:optional true}
    [:map [:scope Balance] [:runs [:map-of :uuid Balance]]
     [:possibly-truncated? :boolean]]]
   [:observation/receipt-id :uuid]])

(def ^:private OnlineAgent
  [:map [:id :keyword] [:status :keyword] [:tags [:set :keyword]] [:description :any]])

(def ^:private Actor
  "Durable actor row as `dvergr.actors/lookup` returns it (nil fields dropped)."
  [:map
   [:id :keyword]
   [:kind :keyword]
   [:status :keyword]
   [:skills [:set :keyword]]
   [:created-at 'inst?]
   [:name {:optional true} :string]
   [:profile-ref {:optional true} :string]
   [:system-prompt {:optional true} :string]
   [:cost {:optional true} :any]
   [:config {:optional true} :any]
   [:external-refs {:optional true} [:map-of :keyword :any]]
   [:skill-priorities {:optional true} [:map-of :keyword :int]]])

(def ^:private ActorFields
  [:map
   [:name {:optional true} :string]
   [:profile-ref {:optional true} :string]
   [:system-prompt {:optional true} :string]
   [:skills {:optional true} KeywordColl]
   [:status {:optional true} :keyword]
   [:cost {:optional true} :any]
   [:config {:optional true} :map]
   [:external-refs {:optional true} [:map-of :keyword :any]]
   [:skill-priorities {:optional true} [:map-of :keyword :int]]])

(def ^:private Task
  "A task-ledger row (`dvergr.orchestration.tasks`, nil fields dropped)."
  [:map
   [:id :uuid]
   [:actor-id :keyword]
   [:room-id :keyword]
   [:content :string]
   [:status [:enum :pending :accepted :completed :ignored]]
   [:created-at 'inst?]
   [:from-actor {:optional true} :keyword]
   [:skill {:optional true} :keyword]
   [:completed-at {:optional true} 'inst?]
   [:result {:optional true} :string]])

(def ^:private SkillDef
  "A parsed skill definition: frontmatter fields plus loader keys (open map)."
  [:map
   [:name :string]
   [:content :string]
   [:file :string]
   [:path :string]
   [:scope [:enum :builtin :user :project :room]]
   [:description {:optional true} :string]
   [:provides {:optional true} [:vector :keyword]]
   [:vetted {:optional true} :boolean]
   [:vetted-by {:optional true} :string]
   [:vetted-at {:optional true} :string]
   [:source {:optional true} :string]
   [:requires-tools {:optional true} [:vector :string]]
   [:requires-env {:optional true} [:vector :string]]])

(def ^:private DispatchResult
  [:map
   [:status [:enum :dispatched :no-provider :unsupported :error]]
   [:actor {:optional true} Actor]
   [:task {:optional true} [:maybe Task]]
   [:error {:optional true} :string]])

(def ^:private ScheduleSpec
  [:map {:closed true}
   [:every {:optional true} :keyword]
   [:n {:optional true} PosInt]
   [:every-ms {:optional true} PosInt]
   [:interval-ms {:optional true} PosInt]
   [:at {:optional true} :string]
   [:on {:optional true} :keyword]
   [:on-day {:optional true} :int]
   [:once {:optional true} :boolean]
   [:tz {:optional true} :string]])

(def ^:private Schedule
  [:map
   [:id :uuid]
   [:agent-id :keyword]
   [:task [:maybe :string]]
   [:code [:maybe :string]]
   [:kind [:enum :interval :once :recurring]]
   [:active? :boolean]
   [:next-fire [:maybe 'inst?]]
   [:last-run [:maybe 'inst?]]
   [:description [:maybe :string]]
   [:interval-ms {:optional true} :int]
   [:every {:optional true} :keyword]
   [:at {:optional true} :string]
   [:on {:optional true} :keyword]
   [:on-day {:optional true} :int]])

(defn- via-var
  "A metadata-carrying fn that calls through host var `v`. A `Var` is not an
   `IObj`, so `doc/with-docs` cannot attach the sandbox doc/schema to it
   directly; calling through the var keeps host redefinitions live."
  [v]
  (fn [& args] (apply v args)))

(defn- with-schemas
  "Append each fn's malli function schema as the third element of its
   `{sym [arglists doc]}` doc-table entry. A schema for a symbol the table
   does not document is a typo and throws."
  [docs schemas]
  (when-let [unknown (seq (remove #(contains? docs %) (keys schemas)))]
    (throw (ex-info "Schemas for undocumented symbols" {:unknown (vec unknown)})))
  (reduce-kv (fn [m sym schema]
               (update m sym (fn [[arglists doc]] [arglists doc schema])))
             docs
             schemas))

(defn add-programming-ns!
  "Expose immutable AgentDefs and Run-backed hiring as `dvergr.agent` in SCI.

   The namespace deliberately has no hidden current roster. `roster`,
   `make-agent`, and `revise-agent` return ordinary immutable values, so a
   Spindel computation can branch with a different team without coordinating a
   mutable registry. `hire!` is the explicit effect boundary: it resolves this
   sandbox's Room, starts a durable Run in the Room's execution context, and
   returns an opaque RunHandle whose native observer Spin is explicit. When
   the authority map supplies `:parent-run`, `hire!` uses it as the structural
   parent unless the caller explicitly supplies one.

   Program data:
     {:kind :echo :delay-ms 10}
     {:kind :scripted :delay-ms 10 :reply value}
     {:kind :llm :max-model-steps 32 :budget-dollars 0.5}

   Join usage:
     (require '[dvergr.agent :as agent]
              '[org.replikativ.spindel.spin.cps :refer [spin]]
              '[org.replikativ.spindel.effects.await :refer [await]])
     (let [team (-> (agent/roster)
                    (agent/make-agent
                     {:id :analyst
                      :skills #{:research}
                      :program {:kind :echo}}))]
       @(spin (-> (await (agent/result-spin
                          (agent/hire! team :analyst {:task :inspect})))
                  :run/value)))

   Ownership-aware race (the losing Run is cancelled):
     (require '[spindel.comb :as comb])
     (let [a (agent/hire! team :fast {:task :solve})
           b (agent/hire! team :slow {:task :solve})]
       @(spin
          (-> (await (comb/race (agent/owned-result-spin a)
                                (agent/owned-result-spin b)))
              :run/value)))"
  [sci-ctx room-id spindel-ctx agent-program-ceiling & [binding-resolver]]
  (let [make-roster*   (requiring-resolve 'dvergr.agent.roster/make-roster)
        make-agent*    (requiring-resolve 'dvergr.agent.roster/make-agent)
        revise-agent*  (requiring-resolve 'dvergr.agent.roster/revise-agent)
        lookup-agent*  (requiring-resolve 'dvergr.agent.roster/agent)
        agent-ref*     (requiring-resolve 'dvergr.agent.roster/agent-ref)
        agents*        (requiring-resolve 'dvergr.agent.roster/agents)
        select-agents* (requiring-resolve 'dvergr.agent.roster/select-agents)
        make-environment* (requiring-resolve 'dvergr.agent.environment/make-environment)
        environment-ref* (requiring-resolve 'dvergr.agent.environment/environment-ref)
        make-dataset*   (requiring-resolve 'dvergr.agent.experiment/make-dataset)
        dataset-ref*    (requiring-resolve 'dvergr.agent.experiment/dataset-ref)
        make-experiment* (requiring-resolve 'dvergr.agent.experiment/make-experiment)
        experiment-ref* (requiring-resolve 'dvergr.agent.experiment/experiment-ref)
        hire-in*       (requiring-resolve 'dvergr.agent.program/hire-in!)
        observe*       (requiring-resolve 'dvergr.agent.program/observe)
        snapshot*      (requiring-resolve 'dvergr.agent.observation/snapshot)
        issue-receipt* (requiring-resolve 'dvergr.agent.observation/issue-receipt!)
        revoke-receipt* (requiring-resolve 'dvergr.agent.observation/revoke-receipt!)
        lifecycle-activity* (requiring-resolve 'dvergr.activity/lifecycle-activity)
        message*       (requiring-resolve 'dvergr.discourse/message)
        post*          (requiring-resolve 'dvergr.discourse/post!)
        cancel*        (requiring-resolve 'dvergr.agent.program/cancel!)
        run-id*        (requiring-resolve 'dvergr.agent.program/run-id)
        result-spin*   (requiring-resolve 'dvergr.agent.program/result-spin)
        owned-result-spin* (requiring-resolve 'dvergr.agent.program/owned-result-spin)
        room-balance*  (requiring-resolve 'dvergr.resource/balance)
        run-balance*   (requiring-resolve 'dvergr.resource/run-balance)
        room-lookup*   (requiring-resolve 'dvergr.room.registry/lookup)
        selected-ctx   #(runtime-ctx/selected-context spindel-ctx)
        current-room-id #(or (when binding-resolver
                               (:room-runtime-id (binding-resolver)))
                             room-id)
        current-room   (fn []
                         (when-let [room-id (current-room-id)]
                           (binding [ec/*execution-context* (selected-ctx)]
                             (room-lookup* room-id))))
        room!          (fn []
                         (or (current-room)
                             (throw (ex-info
                                     "No current Room — agent execution is room-scoped"
                                     {:type ::no-current-room
                                      :room-id room-id}))))
        control-room!  (fn [work-room]
                         (loop [candidate work-room]
                           (if (some-> candidate :meta deref :run-world?)
                             (if-let [parent (room-lookup* (:parent-id candidate))]
                               (recur parent)
                               (throw (ex-info
                                       "Run world has no registered control ancestor"
                                       {:type ::missing-control-room
                                        :room-id (:id candidate)
                                        :parent-id (:parent-id candidate)})))
                             candidate)))
        hire-fn        (fn [roster agent-ref opts]
                         (let [work-room (room!)
                               control-room (control-room! work-room)
                               definition (lookup-agent* roster agent-ref)
                               kind (get-in definition [:agent/program :kind])
                               allowed-kinds (:program-kinds agent-program-ceiling)
                               ambient-parent (:parent-run agent-program-ceiling)]
                           (when (= :deferred (:settlement opts))
                             (throw (ex-info
                                     "Deferred settlement is reserved for trusted host policies"
                                     {:type ::deferred-settlement-forbidden})))
                           (when (and allowed-kinds
                                      (not (contains? allowed-kinds kind)))
                             (throw (ex-info
                                     "Child program exceeds this sandbox's delegation ceiling"
                                     {:type ::program-ceiling-exceeded
                                      :agent-ref agent-ref
                                      :program-kind kind
                                      :allowed-program-kinds allowed-kinds})))
                           (when (and ambient-parent
                                      (contains? opts :parent-run)
                                      (not= ambient-parent (:parent-run opts)))
                             (throw (ex-info
                                     "Child parent must be the current Run"
                                     {:type ::parent-run-exceeds-authority
                                      :parent-run (:parent-run opts)
                                      :current-run ambient-parent})))
                           (binding [ec/*execution-context* (:ctx work-room)]
                             (let [admit-child!
                                   #(hire-in* control-room work-room roster agent-ref
                                              (if (and ambient-parent
                                                       (not (contains? opts :parent-run)))
                                                (assoc opts :parent-run ambient-parent)
                                                opts))]
                               (if-let [own-child! (:own-child! agent-program-ceiling)]
                                 (own-child! admit-child!)
                                 (admit-child!))))))
        observe-fn     (fn [handle-or-id]
                         (let [work-room (room!)
                               control-room (control-room! work-room)]
                           (binding [ec/*execution-context* (:ctx work-room)]
                             (observe* control-room handle-or-id))))
        inspect-fn     (fn inspect-fn
                         ([] (inspect-fn {}))
                         ([opts]
                          (let [work-room (room!)
                                control-room (control-room! work-room)
                                scope-run-id (:parent-run agent-program-ceiling)]
                            (when-not scope-run-id
                              (throw
                               (ex-info
                                "agent/inspect requires an ambient Run scope"
                                {:type ::inspection-scope-required})))
                            (binding [ec/*execution-context* (:ctx work-room)]
                              (let [view (snapshot* control-room scope-run-id opts)
                                    receipt-id (issue-receipt* scope-run-id)
                                    receipt
                                    (assoc
                                     (lifecycle-activity*
                                      scope-run-id :observation :inspect
                                      :completed nil)
                                     :activity/id receipt-id)]
                                (try
                                  (post* control-room
                                         (message*
                                          :system :_activity
                                          "Inspected scoped execution tree"
                                          nil
                                          {:role :tool
                                           :run-id scope-run-id
                                           :activities [receipt]}))
                                  (assoc view :observation/receipt-id receipt-id)
                                  (catch Throwable error
                                    (revoke-receipt* scope-run-id receipt-id)
                                    (throw error))))))))
        cancel-fn      (fn [handle-or-id]
                         ;; Run cancellation tokens are process-local. Binding
                         ;; the Room ctx keeps this boundary consistent with
                         ;; hire/observe and ready for a Spindel-local registry.
                         (let [work-room (room!)
                               control-room (control-room! work-room)]
                           (binding [ec/*execution-context* (:ctx work-room)]
                             (cancel* control-room handle-or-id))))
        result-spin-fn (fn [handle]
                         (if-let [run-id (:parent-run agent-program-ceiling)]
                           (result-spin* run-id handle)
                           (result-spin* handle)))
        owned-result-spin-fn
        (fn [handle]
          (if-let [run-id (:parent-run agent-program-ceiling)]
            (owned-result-spin* run-id handle)
            (owned-result-spin* handle)))
        balance-fn     (fn []
                         (let [work-room (room!)
                               control-room (control-room! work-room)]
                           (if-let [run-id (:parent-run agent-program-ceiling)]
                             (run-balance* control-room run-id)
                             (room-balance* control-room))))]
    (sci/add-namespace!
     sci-ctx 'dvergr.agent
     (doc/with-docs
       {'roster       (via-var make-roster*)
        'make-agent   (via-var make-agent*)
        'revise-agent (via-var revise-agent*)
        'lookup       (via-var lookup-agent*)
        'ref          (via-var agent-ref*)
        'list         (via-var agents*)
        'select       (via-var select-agents*)
        'environment  (via-var make-environment*)
        'environment-ref (via-var environment-ref*)
        'dataset      (via-var make-dataset*)
        'dataset-ref  (via-var dataset-ref*)
        'experiment   (via-var make-experiment*)
        'experiment-ref (via-var experiment-ref*)
        'room-id      (fn [] (:id (room!)))
        'hire!        hire-fn
        'observe      observe-fn
        'inspect      inspect-fn
        'cancel!      cancel-fn
        'balance      balance-fn
        'run-id       (via-var run-id*)
        'result-spin  result-spin-fn
        'owned-result-spin owned-result-spin-fn}
       (with-schemas
       '{roster      [([] [opts]) "Create an immutable Roster value. Options may include portable :id, :defaults, :scope, and :metadata data."]
         make-agent   [([roster spec]) "Return a NEW Roster containing `spec`. Programs are {:kind :echo :delay-ms n}, {:kind :scripted :delay-ms n :reply value}, or {:kind :llm :max-model-steps n :budget-dollars n} plus :model-policy and :tools. Pure: input unchanged."]
         revise-agent [([roster id patch]) "Return a NEW Roster with AgentDef `id` revised and its version incremented."]
         lookup       [([roster id-or-ref]) "Resolve an AgentDef by keyword id or versioned AgentRef. A stale versioned ref is an error."]
         ref          [([agent-def]) "Return the stable {:agent/id :agent/version} reference for an AgentDef."]
         list         [([roster]) "All AgentDefs in a Roster, deterministically ordered by id."]
         select       [([roster selector]) "Select AgentDefs by :id, :status, :skill/:skills, and exact portable :where data."]
         environment  [([spec]) "Create a portable, content-addressed EnvironmentDef. Requires :id, :task, and trusted verifier ref {:id keyword :version n}; optional :limits/:world/:metadata stay data, never live functions or handles."]
         environment-ref [([environment]) "Return the stable logical/version/content reference for one exact EnvironmentDef. Individual execution Run IDs remain unique."]
         dataset      [([spec]) "Create a portable DatasetDef from a non-empty vector of exact EnvironmentDefs. Dataset construction is pure and content-addressed."]
         dataset-ref  [([dataset]) "Return the stable logical/version/content reference for one exact DatasetDef."]
         experiment   [([spec]) "Create a portable full-factorial ExperimentDef. Requires a DatasetDef and a non-empty vector of AgentDefs; repetitions default to one. Candidates bind exact AgentDef content. Concurrency and admission ceilings remain host policy."]
         experiment-ref [([experiment]) "Return the stable logical/version/content reference for one exact ExperimentDef. Running and trusted scoring remain host-owned capabilities."]
         room-id      [([]) "Return the live identity of the current Room/world. In an isolated fork this is the child Room, not its parent."]
         hire!        [([roster agent-ref opts]) "Durably start one owned AgentDef in the current Room: (hire! team :a {:task value :resources {\"microUSD\" 1000}}). Returns a RunHandle. The current Run remains responsible for the child even if the handle is ignored. opts: :task (required); :from (a keyword sender); :settlement (:automatic, :review or :discard); :resources, a MAP of resource coordinate → positive amount (e.g. {\"microUSD\" 1000}) split from the current Run/Room's conserved balance; :limits {:max-model-steps n :budget-dollars x}, which can only TIGHTEN the agent's own limits for this Run; :parent-run, the parent Run's uuid (defaults to the current Run and, when one is ambient, must equal it). Unknown keys are rejected."]
         observe      [([handle-or-run-id]) "Read the current Room's durable Run projection for a RunHandle or UUID."]
         inspect      [([] [opts]) "Inspect the current Run and its structural descendants as one bounded snapshot of Runs, frontier, correlated messages, semantic activities, failures, and conserved balances. Inside a hired agent this cannot see parent or sibling Runs, and inspection without an ambient Run fails closed. A durable semantic receipt identifies the inspection. Options: :run-limit, :message-limit, :content-limit, :content-budget, :detail-limit."]
         cancel!      [([handle-or-run-id]) "Request cooperative cancellation of exactly one live Run. Returns true when the Run was found."]
         balance      [([]) "Return the conserved resource vector available to the current Run, or the Room root at top level."]
         run-id       [([handle]) "Return the durable Run UUID represented by a RunHandle."]
         result-spin  [([handle]) "Return a passive Spindel observer Spin for a RunHandle. On resolution the current Run durably records the child as a causal input. Multiple observers may await it; cancelling an observer does not cancel the Run. The Spin resolves to the result map {:run/id uuid :run/status :completed|:waiting|:cancelled|:failed :run/value … :run/world … :run/settlement-status …} (plus :run/output/:run/reason/:run/error/:run/metrics as applicable)."]
         owned-result-spin [([handle]) "Return an ownership-coupled result Spin. On resolution the current Run durably records the child as a causal input. Cancelling this observer also cancels the underlying Run; use only when the observer owns that child execution. Resolves to the same result map as `result-spin`."]}
       {'roster          [:function [:=> [:cat] Roster] [:=> [:cat RosterOpts] Roster]]
        'make-agent      [:=> [:cat Roster AgentSpec] Roster]
        'revise-agent    [:=> [:cat Roster :keyword [:map-of :keyword :any]] Roster]
        'lookup          [:=> [:cat Roster AgentIdOrRef] [:maybe AgentDef]]
        'ref             [:=> [:cat AgentDef] AgentRef]
        'list            [:=> [:cat Roster] [:vector AgentDef]]
        'select          [:=> [:cat Roster AgentSelector] [:vector AgentDef]]
        'environment     [:=> [:cat EnvironmentSpec] EnvironmentDef]
        'environment-ref [:=> [:cat EnvironmentDef] EnvironmentRef]
        'dataset         [:=> [:cat DatasetSpec] DatasetDef]
        'dataset-ref     [:=> [:cat DatasetDef] DatasetRef]
        'experiment      [:=> [:cat ExperimentSpec] ExperimentDef]
        'experiment-ref  [:=> [:cat ExperimentDef] ExperimentRef]
        'room-id         [:=> [:cat] :keyword]
        'hire!           [:=> [:cat Roster AgentIdOrRef HireOpts] RunHandle]
        'observe         [:=> [:cat [:or :uuid RunHandle]] [:maybe Run]]
        'inspect         [:function [:=> [:cat] Observation]
                          [:=> [:cat InspectOpts] Observation]]
        'cancel!         [:=> [:cat [:or :uuid RunHandle]] :boolean]
        'balance         [:=> [:cat] Balance]
        'run-id          [:=> [:cat RunHandle] :uuid]
        'result-spin     [:=> [:cat RunHandle] :any]
        'owned-result-spin [:=> [:cat RunHandle] :any]})))))

(defn add-agents-ns!
  "Expose the agent registry as 'agents namespace in SCI.

   Lets var ground-truth which personalities are actually running in
   THIS daemon (not just which ones the profile mentions). When the
   user asks 'have Skald draft a post', var should `(agents/list)`
   first to confirm Skald is online before calling `(room/join! …)`.

   Usage:
     (require '[agents])
     (agents/list)                       ; all registered agents
     (agents/lookup :skald)               ; full entry or nil
     (agents/online? :huginn)             ; convenience boolean
     (agents/by-tag :coding)              ; ids matching a tag"
  [sci-ctx]
  (load/require! 'dvergr.actors)
  (let [online-actors* @(ns-resolve 'dvergr.actors 'online-actors)
        online?*       @(ns-resolve 'dvergr.actors 'online?)
        list-fn      (fn [] (online-actors*))
        lookup-fn    (fn [id] (some #(when (= id (:id %)) %) (online-actors*)))
        online?-fn   (fn [id] (online?* id))
        by-tag-fn    (fn [tag] (filterv #(contains? (:tags %) tag) (online-actors*)))]
    (sci/add-namespace! sci-ctx 'dvergr.agents
                        (doc/with-docs
                          {'list    list-fn
                           'lookup  lookup-fn
                           'online? online?-fn
                           'by-tag  by-tag-fn}
                          (with-schemas
                          '{list    [([]) "Every agent currently ONLINE in this daemon, as a vector of entries. Ground-truth for who can actually take work right now — a profile mentioning an agent does not mean it is running."]
                            lookup  [([id]) "The full entry for one agent id (e.g. :skald), or nil if it is not online."]
                            online? [([id]) "Whether an agent id is running right now. Check before dispatching work to it."]
                            by-tag  [([tag]) "Online agents whose :tags contain `tag` (e.g. :coding) — a vector, possibly empty."]}
                           {'list    [:=> [:cat] [:vector OnlineAgent]]
                            'lookup  [:=> [:cat :keyword] [:maybe OnlineAgent]]
                            'online? [:=> [:cat :keyword] :boolean]
                            'by-tag  [:=> [:cat :keyword] [:vector OnlineAgent]]})))))

(defn add-skills-ns!
  "Expose the skill registry + dispatch as 'skills namespace in SCI.

   Usage:
     (require '[skills])
     (skills/all)                        ; every skill on disk
     (skills/find :research)             ; skill maps providing :research
     (skills/providers :research)        ; actor-ids that declare :research
     (skills/rank :research)             ; ranked online providers
     (skills/dispatch :research)         ; the single best provider (actor map or nil)"
  [sci-ctx conn]
  (load/require! 'dvergr.orchestration.skills)
  (let [load-all*   @(ns-resolve 'dvergr.orchestration.skills 'load-all)
        read-skill* @(ns-resolve 'dvergr.orchestration.skills 'read-skill)
        find-prov   @(ns-resolve 'dvergr.orchestration.skills 'find-providers)
        rank-prov   @(ns-resolve 'dvergr.orchestration.skills 'rank-providers)
        dispatch    @(ns-resolve 'dvergr.orchestration.skills 'dispatch-target)
        dispatch!*  @(ns-resolve 'dvergr.orchestration.skills 'dispatch!)
        author*     @(requiring-resolve 'dvergr.discourse.definitions/author!)
        promote*    @(requiring-resolve 'dvergr.discourse.definitions/promote!)
        ;; Resolved lazily at call time: an agent runs in its ROOM's execution
        ;; context, so this returns the room's sandbox-repo path — letting
        ;; `all`/`read`/`find` see skills the room itself defines (highest
        ;; precedence). Reads fall back to the shared workspace.
        room-dir    (fn [] (try ((requiring-resolve
                                  'dvergr.sandbox.workspace/workspace-root))
                                (catch Throwable _ nil)))
        ;; WRITES (author!/lift!/promote!) need the ROOM's own versioned repo —
        ;; never the shared fallback workspace, which `workspace-root` returns
        ;; when no room is bound. nil here means there is no room repo.
        room-repo   (fn [] (try ((requiring-resolve
                                  'dvergr.sandbox.workspace/room-workspace-root))
                                (catch Throwable _ nil)))
        room-repo!  (fn [op]
                      (or (room-repo)
                          (throw (ex-info (str "skills/" op " needs a room sandbox repo (no room workspace bound)")
                                          {:op (symbol op)}))))
        provides?   (fn [tag s] (some #(= tag %) (:provides s)))]
    (sci/add-namespace! sci-ctx 'dvergr.skills
                        (doc/with-docs
                          {'all       (fn [] (load-all* (room-dir)))
                         ;; Progressive disclosure: the system prompt shows a
                         ;; brief index; pull a skill's FULL instructions here.
                           'read      (fn [skill-name] (read-skill* skill-name (room-dir)))
                           'find      (fn [provides-tag]
                                        (into [] (filter #(provides? provides-tag %))
                                              (vals (load-all* (room-dir)))))
                           'providers (fn [skill] (find-prov conn skill))
                           'rank      (fn [skill] (rank-prov conn skill))
                           'dispatch  (fn [skill] (dispatch conn skill))
                           'dispatch! (fn [skill opts]
                                        (dispatch!* conn skill opts))
                         ;; Authoring lifecycle (writes into THIS room's repo —
                         ;; versioned + forkable + mergeable). Agent-authored
                         ;; skills land `vetted: false`, so the vetting gate keeps
                         ;; them out of prompts until a reviewer promotes them.
                           'author!   (fn [skill-name frontmatter body]
                                        (author* "skills" (room-repo! "author!") (str skill-name)
                                                 frontmatter (str body)))
                         ;; Lift external content (an openclaw/Claude skill, a URL
                         ;; you fetched) into the room as an UNVETTED skill.
                           'lift!     (fn [skill-name source body]
                                        (author* "skills" (room-repo! "lift!") (str skill-name)
                                                 {:source (str source) :vetted false} (str body)))
                         ;; Promote a room skill to vetted (reviewer action).
                         ;; Only the ROOM's own skills: user/project/builtin
                         ;; definitions live outside the room repo (a sandbox
                         ;; must not rewrite ~/.dvergr or the classpath).
                           'promote!  (fn [skill-name by date]
                                        (let [definition (get (load-all* (room-repo! "promote!"))
                                                              (str skill-name))]
                                          (cond
                                            (nil? definition)
                                            (throw (ex-info (str "no such skill to promote: " skill-name) {}))

                                            (not= :room (:scope definition))
                                            (throw (ex-info (str "skills/promote!: " skill-name
                                                                 " is a " (name (:scope definition))
                                                                 " skill, not one of this room's — only room skills can be promoted here")
                                                            {:skill (str skill-name) :scope (:scope definition)}))

                                            :else
                                            (do (promote* definition (str by) (str date)) true))))}
                          (with-schemas
                          '{all       [([]) "Every skill visible here — on disk plus any this room defines (the room's own take precedence). A map of skill-name → definition."]
                            read      [([skill-name]) "The FULL instructions for one skill. The system prompt carries only a brief index; pull the body with this before following a skill."]
                            find      [([provides-tag]) "Skill definitions that provide `provides-tag` (e.g. :research) — a vector, possibly empty. Includes this room's own skills (as `all` does)."]
                            providers [([skill]) "Actor-ids that declare they can perform `skill`, whether or not they are online."]
                            rank      [([skill]) "Providers of `skill` ranked by suitability, ONLINE ones only."]
                            dispatch  [([skill]) "The single best online provider for `skill` (an actor map), or nil if nobody can take it."]
                            dispatch! [([skill opts]) "Actually hand `skill` to its best provider. `opts` carries the payload for the receiving actor."]
                            author!   [([skill-name frontmatter body]) "Write a NEW skill into this room's repo (versioned, forkable, mergeable). Lands `vetted: false`, so it stays out of prompts until a reviewer promotes it. Throws if no room workspace is bound."]
                            lift!     [([skill-name source body]) "Import external content (another agent's skill, a fetched URL) into the room as an UNVETTED skill, recording `source`. Throws if no room workspace is bound."]
                            promote!  [([skill-name by date]) "Mark one of THIS room's skills vetted — a REVIEWER action; this is what lets it appear in prompts. Throws if there is no such skill, if it is not a room skill (user/project/built-in skills are not promotable from here), or if no room workspace is bound."]}
                           {'all       [:=> [:cat] [:map-of :string SkillDef]]
                            'read      [:=> [:cat SkillName] [:maybe :string]]
                            'find      [:=> [:cat :keyword] [:vector SkillDef]]
                            'providers [:=> [:cat :keyword] [:vector :keyword]]
                            'rank      [:=> [:cat :keyword] [:vector Actor]]
                            'dispatch  [:=> [:cat :keyword] [:maybe Actor]]
                            'dispatch! [:=> [:cat :keyword
                                             [:map
                                              [:task :string]
                                              [:room-id {:optional true} :keyword]
                                              [:from-actor {:optional true} [:maybe :keyword]]]]
                                        DispatchResult]
                            'author!   [:=> [:cat SkillName [:map-of :keyword :any] :any] :string]
                            'lift!     [:=> [:cat SkillName :any :any] :string]
                            'promote!  [:=> [:cat SkillName :any :any] [:= true]]})))))

(defn add-actors-ns!
  "Expose the durable actor table as 'actors namespace in SCI.

   The runtime 'agents namespace (above) answers \"who is alive right
   now\" by consulting the in-context registry. 'actors answers \"who
   does the system know about\" — including offline / retired actors.
   It also lets var spawn new agents and dismiss them, with changes
   persisted to Datahike.

   Usage:
     (require '[actors])
     (actors/list)                          ; every durable actor
     (actors/list :kind :agent :status :online)
     (actors/lookup :var)                   ; the durable row
     (actors/online? :var)                  ; runtime check
     (actors/spawn-agent! {:id :scribe
                            :name \"Scribe\"
                            :profile-ref \"scribe.md\"
                            :skills #{:writing}
                            :config {:provider :fireworks
                                     :model \"...\"}})
     (actors/dismiss! :scribe)              ; flag :status :retired
     (actors/update! :scribe {:skills #{:prose :writing}})
     (actors/add-skill! :scribe :prose)
     (actors/remove-skill! :scribe :writing)"
  [sci-ctx conn]
  (load/require! 'dvergr.actors)
  (let [list-fn         @(ns-resolve 'dvergr.actors 'list-actors)
        lookup-fn       @(ns-resolve 'dvergr.actors 'lookup)
        online?-fn      @(ns-resolve 'dvergr.actors 'online?)
        spawn-agent-fn  @(ns-resolve 'dvergr.actors 'spawn-agent!)
        spawn-human-fn  @(ns-resolve 'dvergr.actors 'spawn-human!)
        dismiss-fn      @(ns-resolve 'dvergr.actors 'dismiss!)
        update-fn       @(ns-resolve 'dvergr.actors 'update-actor!)
        add-skill-fn    @(ns-resolve 'dvergr.actors 'add-skill!)
        remove-skill-fn @(ns-resolve 'dvergr.actors 'remove-skill!)]
    (sci/add-namespace! sci-ctx 'dvergr.actors
                        (doc/with-docs
                          {'list          (fn [& kvs] (apply list-fn conn kvs))
                           'lookup        (fn [id]      (lookup-fn conn id))
                           'online?       (fn [id]      (online?-fn id))
                           'spawn-agent!  (fn [opts]    (spawn-agent-fn conn opts))
                           'spawn-human!  (fn [opts]    (spawn-human-fn conn opts))
                           'dismiss!      (fn [id]      (dismiss-fn conn id))
                           'update!       (fn [id patch] (update-fn conn id patch))
                           'add-skill!    (fn [id skill] (add-skill-fn conn id skill))
                           'remove-skill! (fn [id skill] (remove-skill-fn conn id skill))}
                          (with-schemas
                          '{list          [([] [& {:keys [kind status]}]) "Every DURABLE actor the system knows — including offline and retired ones (contrast dvergr.agents/list, which is who is alive now). Filter with :kind (:agent/:human) and :status (e.g. :online, :retired)."]
                            lookup        [([id]) "The durable row for one actor id, or nil. Persisted state, not runtime state."]
                            online?       [([id]) "Runtime check — is this actor actually running now?"]
                            spawn-agent!  [([opts]) "Create a NEW agent, persisted to Datahike. `opts` takes :id :name :profile-ref :skills :config (provider/model). This grows the roster; prefer an existing agent when one fits."]
                            spawn-human!  [([opts]) "Register a HUMAN participant as an actor, so work can be assigned to and tracked for them."]
                            dismiss!      [([id]) "Retire an actor — flags :status :retired rather than deleting, so its history survives."]
                            update!       [([id patch]) "Merge `patch` into an actor's durable row (e.g. {:skills #{:prose :writing}})."]
                            add-skill!    [([id skill]) "Declare that an actor can perform `skill` — this is what makes it show up in dvergr.skills/providers."]
                            remove-skill! [([id skill]) "Withdraw a skill declaration from an actor."]}
                           {'list          [:=> [:cat [:* [:alt [:cat [:= :kind] :keyword]
                                                            [:cat [:= :status] :keyword]
                                                            [:cat [:= :skill] :keyword]]]]
                                            [:vector Actor]]
                            'lookup        [:=> [:cat :keyword] [:maybe Actor]]
                            'online?       [:=> [:cat :keyword] :boolean]
                            'spawn-agent!  [:=> [:cat (into [:map [:id :keyword]] (rest ActorFields))] Actor]
                            'spawn-human!  [:=> [:cat (into [:map [:id :keyword]
                                                             [:external-refs [:map-of {:min 1} :keyword :any]]]
                                                            (remove #(= :external-refs (first %)))
                                                            (rest ActorFields))]
                                            Actor]
                            'dismiss!      [:=> [:cat :keyword] [:maybe [:= :dismissed]]]
                            'update!       [:=> [:cat :keyword ActorFields] [:maybe Actor]]
                            'add-skill!    [:=> [:cat :keyword :keyword] Actor]
                            'remove-skill! [:=> [:cat :keyword :keyword] Actor]})))))

(defn add-tasks-ns!
  "Expose the task ledger as 'tasks namespace in SCI.

   Tasks are persistent rows for skill dispatches to non-agent actors
   (humans now; externals in phase D-externals). Agents just react to
   inbox messages — they don't need a task row.

   Usage:
     (require '[tasks])
     (tasks/list)                      ; every task
     (tasks/list :actor-id :alice :status :pending)
     (tasks/lookup task-uuid)
     (tasks/accept!   task-uuid)
     (tasks/complete! task-uuid \"done — here's what I found\")
     (tasks/ignore!   task-uuid)"
  [sci-ctx conn]
  (load/require! 'dvergr.orchestration.tasks)
  (let [list-fn     @(ns-resolve 'dvergr.orchestration.tasks 'list-tasks)
        lookup-fn   @(ns-resolve 'dvergr.orchestration.tasks 'lookup)
        accept-fn   @(ns-resolve 'dvergr.orchestration.tasks 'accept!)
        complete-fn @(ns-resolve 'dvergr.orchestration.tasks 'complete!)
        ignore-fn   @(ns-resolve 'dvergr.orchestration.tasks 'ignore!)]
    (sci/add-namespace! sci-ctx 'dvergr.tasks
                        (doc/with-docs
                          {'list      (fn [& kvs] (apply list-fn conn kvs))
                           'lookup    (fn [id]    (lookup-fn conn id))
                           'accept!   (fn [id]    (accept-fn conn id))
                           'complete! (fn [id r]  (complete-fn conn id r))
                           'ignore!   (fn [id]    (ignore-fn conn id))}
                          (with-schemas
                          '{list      [([] [& {:keys [actor-id status]}]) "The shared task ledger — persistent rows for work dispatched to non-agent actors (humans). Filter with :actor-id and :status (e.g. :pending). Agents themselves just react to inbox messages and need no task row."]
                            lookup    [([id]) "One task by its uuid, or nil."]
                            accept!   [([id]) "Claim a task — marks it accepted so nobody else picks it up."]
                            complete! [([id result]) "Finish a task, recording `result` (a string describing what was done/found)."]
                            ignore!   [([id]) "Decline a task, leaving it for someone else."]}
                           {'list      [:=> [:cat [:* [:alt [:cat [:= :actor-id] :keyword]
                                                        [:cat [:= :status] :keyword]]]]
                                        [:vector Task]]
                            'lookup    [:=> [:cat :uuid] [:maybe Task]]
                            'accept!   [:=> [:cat :uuid] [:maybe Task]]
                            'complete! [:=> [:cat :uuid :any] [:maybe Task]]
                            'ignore!   [:=> [:cat :uuid] [:maybe Task]]})))))

(defn add-scheduler-ns!
  "Expose scheduling as 'scheduler namespace in SCI.

   Schedules are per-room (RF5): each call operates on the CURRENT room (the one
   the agent's sandbox is running in). Agents can schedule themselves or other
   agents in that room:
     (require '[scheduler])
     (scheduler/every :day \"09:00\" :huginn \"Run morning intake sweep\")
     (scheduler/every :week :monday \"14:00\" :analyst \"Weekly market review\")
     (scheduler/at \"2026-04-01T09:00\" :var \"April Fools reminder\")
     (scheduler/cancel schedule-id)
     (scheduler/list)"
  [sci-ctx]
  (load/require! 'dvergr.scheduler.core)
  ;; A raw `(fn …)` carries no metadata, so the closures below are documented
  ;; through `doc/with-docs` — otherwise an agent's only way to learn a
  ;; signature was to call it and read the error.
  (let [sched-create  @(ns-resolve 'dvergr.scheduler.core 'create-schedule!)
        sched-cancel  @(ns-resolve 'dvergr.scheduler.core 'cancel-schedule!)
        sched-list    @(ns-resolve 'dvergr.scheduler.core 'list-schedules)
        current-room  @(ns-resolve 'dvergr.scheduler.core 'current-room)
        room!         (fn []
                        (or (current-room)
                            (throw (ex-info "No current room — schedules are per-room" {}))))
        create-keys   #{:agent-id :task :code :interval-ms :schedule :description :id}

        every-fn (fn [period & args]
                   ;; Dispatch on ARITY, not on the types of the first two args.
                   ;;
                   ;; The type-based cond could not tell `(every :day :var "task")`
                   ;; from `(every :week :monday "14:00" :agent "task")` — both open
                   ;; (keyword, string) — so the 4-arg branch swallowed the 2-arg
                   ;; form and then ran `(nth args 2)` off the end. That surfaced as
                   ;; a bare IndexOutOfBoundsException with a nil message, and with
                   ;; no arglists to consult an agent reasonably concluded the
                   ;; function was broken. It was not: both documented forms work.
                   ;; The 2-arg form was genuinely unreachable, and is now reachable.
                   (let [n (count args)
                         wrong (fn []
                                 (throw (ex-info
                                         (str "scheduler/every: cannot read these "
                                              n " argument(s) after the period. Expected one of:\n"
                                              "  (every period agent-id task)\n"
                                              "  (every period \"HH:MM\" agent-id task)\n"
                                              "  (every period :day-of-week \"HH:MM\" agent-id task)\n"
                                              "where agent-id is a keyword and task a string.")
                                         {:period period :args (vec args)})))
                         [opts agent-id task]
                         (case n
                           2 [{:every period} (first args) (second args)]
                           3 [{:every period :at (first args)} (second args) (nth args 2)]
                           4 [{:every period :on (first args) :at (second args)}
                              (nth args 2) (nth args 3)]
                           (wrong))]
                     ;; Check the SHAPE, not just the count. Arity alone still lets
                     ;; `(every :day "07:00" :var)` through as agent-id "07:00" and
                     ;; task :var, which only fails later against create-schedule!'s
                     ;; `(keyword? agent-id)` precondition — an AssertionError naming
                     ;; neither the caller nor what it should have passed.
                     (when-not (and (keyword? agent-id) (string? task)
                                    (or (not (:at opts)) (string? (:at opts)))
                                    (or (not (:on opts)) (keyword? (:on opts))))
                       (wrong))
                     (sched-create (room!)
                                   {:agent-id agent-id
                                    :task task
                                    :schedule opts
                                    :description (str "Every " (name period)
                                                      (when (:at opts) (str " at " (:at opts)))
                                                      (when (:on opts) (str " on " (name (:on opts)))))})))

        at-fn (fn [datetime agent-id task]
                (sched-create (room!)
                              {:agent-id agent-id :task task
                               :schedule {:at datetime :once true}
                               :description (str "One-shot at " datetime)}))

        interval-fn (fn [ms agent-id task]
                      (sched-create (room!)
                                    {:agent-id agent-id :task task
                                     :interval-ms ms
                                     :description (str "Every " (/ ms 60000.0) " minutes")}))]
    (sci/add-namespace!
     sci-ctx 'dvergr.scheduler
     (doc/with-docs
       {'every    every-fn
        'at       at-fn
        'interval interval-fn
        ;; CODE schedules — the durable-pipeline primitive:
        ;; the form evals in YOUR sandbox on each fire
        ;; (deterministic, no LLM turn). Put the fns in a
        ;; namespace in your workspace repo, then e.g.
        ;; (dvergr.scheduler/create
        ;;   {:agent-id :me :schedule {:every :day :at \"07:00\"}
        ;;    :code \"(require 'intake.news)(intake.news/scan!)\"
        ;;    :description \"morning news scan\"})
        ;; Cadence forms: {:every :day :at \"07:00\"} (daily at
        ;; a wall-clock time), {:every :hour :n 4} (every 4h —
        ;; :n multiplies :minute/:hour/:day/:week into a fixed
        ;; interval), {:every-ms N}, {:at \"ISO\" :once true}.
        ;; Unknown keys are REJECTED (no silent wrong cadence).
        'create   (fn [cfg]
                    ;; `:schedule` keys are checked by the cadence parser; the
                    ;; TOP-level keys are checked here, so a typo like
                    ;; `:intervall-ms` fails loudly instead of being ignored.
                    (when-let [unknown (seq (remove create-keys (keys cfg)))]
                      (throw (ex-info (str "scheduler/create: unknown key(s) "
                                           (str/join " " (map pr-str unknown))
                                           " — allowed: "
                                           (str/join " " (map pr-str (sort create-keys))))
                                      {:unknown (vec unknown) :allowed create-keys})))
                    (sched-create (room!) cfg))
        'cancel   (fn [id] (sched-cancel (room!) id))
        'list     (fn [] (sched-list (room!)))}
       (with-schemas
         '{every    [([period agent-id task]
                      [period at agent-id task]
                      [period day-of-week at agent-id task])
                     "Recurring schedule in THIS room. `period` is :day/:week/…, `at` is \"HH:MM\" wall-clock, `day-of-week` a keyword like :monday. e.g. (every :day \"09:00\" :huginn \"Morning sweep\")."]
           at       [([iso-datetime agent-id task])
                     "One-shot at a FULL ISO datetime — \"2026-04-01T09:00\", not \"09:00\". For a daily wall-clock time use `every` instead."]
           interval [([ms agent-id task])
                     "Repeat every `ms` milliseconds."]
           create   [([cfg])
                     "Richest form. cfg = {:agent-id kw :task \"…\" | :code \"…\" :schedule {…} | :interval-ms N :description \"…\"}. `:code` evals in your sandbox on each fire with no LLM turn. Unknown keys — top-level or inside :schedule — are REJECTED."]
           cancel   [([schedule-id])
                     "Deactivate a schedule BY ID — the uuid itself, not the map `list` returns."]
           list     [([])
                     "Active schedules in this room, as maps carrying :id :kind :next-fire …"]}
         {'every    [:function
                     [:=> [:cat :keyword :keyword :string] :uuid]
                     [:=> [:cat :keyword :string :keyword :string] :uuid]
                     [:=> [:cat :keyword :keyword :string :keyword :string] :uuid]]
          'at       [:=> [:cat :string :keyword :string] :uuid]
          'interval [:=> [:cat PosInt :keyword :string] :uuid]
          'create   [:=> [:cat [:map {:closed true}
                                [:agent-id :keyword]
                                [:task {:optional true} :string]
                                [:code {:optional true} :string]
                                [:interval-ms {:optional true} PosInt]
                                [:schedule {:optional true} ScheduleSpec]
                                [:description {:optional true} :string]
                                [:id {:optional true} :uuid]]]
                     :uuid]
          'cancel   [:=> [:cat :uuid] [:maybe [:= :cancelled]]]
          'list     [:=> [:cat] [:vector Schedule]]})))))
