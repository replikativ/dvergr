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
            [clojure.set :as set]
            [dvergr.agent.environment :as environment]
            [dvergr.agent.evaluation :as evaluation]
            [dvergr.agent.evaluators :as evaluators]
            [dvergr.agent.experiment :as experiment]
            [dvergr.agent.roster :as roster]
            [dvergr.agent.program :as program]
            [dvergr.model.providers :as providers]
            [dvergr.principal :as principal]
            [dvergr.resource :as resource]
            [dvergr.agent.run :as run]
            [dvergr.model.registry :as registry]
            [dvergr.room.registry :as rreg]
            [dvergr.room.store :as room-store]
            [dvergr.rooms.forks :as forks]
            [dvergr.tools :as tools]
            [hasch.core :as hasch]
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

(evaluators/register-evaluator! completion-evaluator)

(def ^:private default-prompt
  "You are a capable AI worker. Complete the given task thoroughly, in this
   room's files and data. Say briefly what you changed.")

(defn- model-policy [model]
  (registry/ensure-models-loaded!)
  (let [id (registry/resolve-alias model)]
    {:provider (:provider (registry/get-model! id)) :model id}))

(defn admit-model!
  "The same caller admission for candidates and reviewers. Registry aliases
   are resolved before consulting the caller's model cap and host authorizer."
  [opts model]
  (let [policy (model-policy model)
        caller (:principal opts)]
    (when caller
      (when-let [allowed (:principal/models caller)]
        (when-not (contains? (set (map registry/resolve-alias allowed)) (:model policy))
          (throw (ex-info "Model outside caller policy" {:model (:model policy)})))))
    (when (and caller (not (principal/local? caller)))
      ((requiring-resolve 'dvergr.ops/check!) (:daemon opts) caller
                                              {:op :workflow/start :action :use :resource [:model (:model policy)]
                                               :resolved (when-let [room (:room opts)]
                                                           {:room (if (keyword? room) (subs (str room) 1) (str room))})
                                               :args {:model (:model policy)}}))
    policy))

(defn candidates
  "AgentDefs, one per model: `{:team roster :ids [agent-id ...]}`."
  [{:keys [models budget-dollars profile prompt] :as opts}]
  (let [policies (if (seq models)
                   (mapv #(admit-model! opts %) models)
                   [(let [p (or (providers/default-spec)
                                (throw (ex-info "No LLM provider is available" {:type ::no-provider})))]
                      (admit-model! opts (:model p)))])
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
  [task {:keys [timeout-ms evaluator environment-id effects]}]
  (environment/make-environment
   {:id (or environment-id (keyword "workflow" (str "task-" (subs (str (hasch/uuid task)) 0 8))))
    :task task
    :verifier (let [ref (evaluation/evaluator-ref (or evaluator completion-evaluator))]
                (cond-> {:id (:verifier/id ref) :version (:verifier/version ref)}
                  (:verifier/basis ref) (assoc :basis (:verifier/basis ref))))
    :limits {:timeout-ms (or timeout-ms (* 10 60 1000)) :cancel-timeout-ms 30000
             :on-timeout :verdict}
    :world (cond-> {:isolation :ctx :settlement :review}
             ;; the attempt world's effect handlers (dvergr.effects), e.g. a
             ;; search's speculative filter
             effects (assoc :effects effects))}))

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

(defn- review-fn [name] (requiring-resolve (symbol "dvergr.agent.review" name)))

(defn- authority
  [room world opts run-id]
  (let [daemon (or (:daemon opts) {:execution-ctx (:ctx room)})
        caller (:principal opts)
        claims (select-keys caller [:principal/delegation :principal/delegation-scope])
        scoped? (and (:principal/delegation caller) (map? (:principal/delegation-scope caller)))
        grant-args (when scoped? claims)
        token (when scoped?
                (principal/mint-run-token!
                 {:actor :reviewer :room world
                  :tools #{"room_merge" "room_discard"}
                  :claims claims}))
        delegated (when token (principal/run-token-principal token))
        choose (fn [op]
                 (some (fn [p]
                         (when p
                           (try
                             ((requiring-resolve 'dvergr.ops/authorize!) daemon p op (merge {:room world} grant-args))
                             p (catch Throwable _ nil))))
                       [caller delegated]))
        merge-p (choose :room/merge) discard-p (choose :room/discard)]
    {:merge? (boolean merge-p) :discard? (boolean discard-p)
     :close #(when token (principal/revoke-run-token! token))
     :invoke (fn [op args]
               (program/with-worker-decision!
                 (fn []
                   (when (or ((review-fn "decision") room run-id)
                             (.isInterrupted (Thread/currentThread))
                             (and (:parent-run opts) (run/cancel-requested? (:parent-run opts))))
                     (throw (ex-info "Settlement cancelled before decision" {})))
                   ((requiring-resolve 'dvergr.ops/invoke) daemon
                                                           (case op :room/merge merge-p :room/discard discard-p nil) op (merge args grant-args)))))}))

(defn effective-policy
  "Bundle review is a request. The host adds the declared write scope and
   conflict check; only an authorized principal can request automatic action."
  [opts auth]
  (let [spec (merge {:review/on-pass :merge :review/on-fail :escalate} (:review opts))
        spec (-> spec
                 (update :review/on-pass #(if (= :merge %) :merge :propose))
                 (update :review/on-fail #(if (= :discard %) :discard :escalate)))
        writes (:workflow/writes opts)
        delegation (:principal/delegation-scope (:principal opts))
        scoped? (and (:principal/delegation (:principal opts)) (map? delegation))
        normalize (fn [p]
                    (when (string? p)
                      (let [p (str/replace p #"^/+" "")]
                        (when (and (not (str/blank? p))
                                   (not-any? #{"." ".."} (str/split p #"/"))) p))))
        within? (fn [p parent]
                  (or (= p parent)
                      (str/starts-with? p (str (str/replace parent #"/+$" "") "/"))))
        ;; A broad bundle prefix is attenuated to the delegated prefix. Neither
        ;; a root nor an empty bundle path can grant anything beyond it.
        paths (vec (distinct
                    (when scoped?
                      (for [raw (:repo writes)
                            :when (string? raw)
                            grant (:paths delegation)
                            :let [g (normalize grant) p (normalize raw)]
                            :when (and g (or (str/blank? (str/replace raw #"^/+" ""))
                                             (and p (or (within? p g) (within? g p)))))]
                        (if (and p (within? p g)) p g)))))
        declared-kb (if (set? (:kb writes))
                      (zipmap (:kb writes) (repeat #{:wiki/atom :wiki/overview :wiki/index}))
                      (:kb writes))
        kb-scope (into {} (when scoped?
                            (for [[system kinds] declared-kb
                                  :let [allowed (set/intersection (set kinds) (set (get-in delegation [:kb system])))]
                                  :when (seq allowed)]
                              [system allowed])))
        kb (mapv (fn [[system kinds]] [:diff/only-kb-pages {:system system :kinds kinds}]) kb-scope)
        scope? (or (seq paths) (seq kb))
        downgrades (cond-> []
                     (and (= :merge (:review/on-pass spec)) (not (:merge? auth)))
                     (conj {:key :review/on-pass :from :merge :to :propose :reason "No principal authorized for room/merge"})
                     (and (= :merge (:review/on-pass spec)) (not scope?))
                     (conj {:key :review/on-pass :from :merge :to :propose :reason "No writes within delegated scope"})
                     (and (= :discard (:review/on-fail spec)) (not (:discard? auth)))
                     (conj {:key :review/on-fail :from :discard :to :escalate :reason "No principal authorized for room/discard"}))
        ;; only-paths also forbids file changes for KB-only workflows.
        checks (vec (distinct (concat [:fork/review-tier [:diff/only-paths paths]] kb
                                      (:review/checks spec))))]
    (reduce (fn [p {:keys [key to]}] (assoc p key to))
            (assoc spec :review/checks checks :review/settlement? true
                   :review/declared-writes writes
                   :review/delegation-scope delegation
                   :review/write-scope {:paths paths :kb kb-scope}
                   :review/downgrades downgrades
                   :review/host-checks (vec (concat [:fork/review-tier [:diff/only-paths paths]] kb)))
            downgrades)))

(defn bounded-reviewer-spec
  [room opts policy]
  (if-let [reviewer (when-not (:reviewer opts) (:review/reviewer policy))]
    (let [caller (:principal opts)
          model (:model (admit-model! (assoc opts :room (:id room)) (:model reviewer)))
          cap (min (double (or (:budget-dollars opts) 0.50))
                   (double (or (:principal/budget-dollars caller) 0.50)))
          remaining (if-let [payer (:principal/run caller)]
                      (/ (double (resource/wallet-microdollars room payer)) 1000000.0)
                      cap)
          budget (min (double (or (:budget-dollars reviewer) 0.50)) cap remaining)]
      (when-not (pos? budget)
        (throw (ex-info "Caller has no remaining reviewer budget" {})))
      (cond-> (assoc policy :review/reviewer (assoc reviewer :model model :budget-dollars budget))
        (< budget (double (or (:budget-dollars reviewer) 0.50)))
        (update :review/downgrades (fnil conj [])
                {:key [:review/reviewer :budget-dollars]
                 :from (or (:budget-dollars reviewer) 0.50) :to budget
                 :reason "Bounded by caller budget cap and remaining Run wallet"})))
    policy))

(defn- intent-policy [room opts result]
  (let [world (get-in result [:run/result :run/world])
        auth (authority room (if (keyword? world) (subs (str world) 1) (str world)) opts (:run/id result))]
    (try
      (let [policy (effective-policy opts auth)]
        (try (bounded-reviewer-spec room opts policy)
             (catch Throwable _ policy)))
      (finally ((:close auth))))))

(defn- settle!
  [room opts result]
  (let [world (get-in result [:run/result :run/world])
        run-id (:run/id result)]
    (if-not (:review opts)
      (when-let [w (and world (rreg/lookup world))]
        (forks/commit-workspace! w (str "Attempt " run-id))
        (when (= :review (get-in result [:run/result :run/settlement-status]))
          (when-let [hook (:on-settlement opts)]
            (hook {:room room :world world :run-id run-id :result (:run/result result)}))))
      (or ((review-fn "decision") room run-id)
          (let [auth (authority room (if (keyword? world) (subs (str world) 1) (str world)) opts run-id)
                requested (effective-policy opts auth)
                bounded (try {:policy (bounded-reviewer-spec room opts requested)}
                             (catch Throwable t {:policy requested :error t}))
                policy (:policy bounded)]
            (try
              (if ((review-fn "claim!") room run-id world policy)
                (try
                  (when-let [error (:error bounded)] (throw error))
                  (when-let [w (rreg/lookup world)]
                    (forks/commit-workspace! w (str "Attempt " run-id)))
                  (let [reviewer (or (:reviewer opts)
                                     (when (:review/reviewer policy)
                                       ((review-fn "model-reviewer") (:daemon opts) room world policy (:principal opts))))
                        decision ((review-fn "review!") room world policy :run-id run-id
                                                        :id ((review-fn "decision-id") run-id) :reviewer reviewer :invoke (:invoke auth))]
                    (when-let [hook (:on-settlement opts)]
                      (hook {:room room :world world :run-id run-id :result (:run/result result)}))
                    decision)
                  (catch Throwable t
                    ;; Cancellation must still leave a durable decision.
                    (Thread/interrupted)
                    ((review-fn "failure!") room run-id world policy
                                            (str "Settlement review failed: " (ex-message t)))))
                (loop []
                  (or ((review-fn "decision") room run-id)
                      (do (Thread/sleep 10) (recur)))))
              (finally ((:close auth)))))))))

(defn- await-decision [room run-id]
  (loop []
    (or ((review-fn "decision") room run-id)
        (do (Thread/sleep 10) (recur)))))

(defn- settle-on-worker! [room opts result]
  (if-not (:review opts)
    (program/on-worker! room #(settle! room opts result))
    (let [run-id (:run/id result)
          world (get-in result [:run/result :run/world])]
      (or ((review-fn "decision") room run-id)
          (try
            (program/on-worker! room (:parent-run opts) #(settle! room opts result))
            (catch Throwable t
              (let [decision (or ((review-fn "decision") room run-id)
                                 (if ((review-fn "claim!") room run-id world (:review opts))
                                   ((review-fn "failure!") room run-id world (:review opts)
                                                           (str "Settlement worker failed: " (ex-message t)))
                                   ;; Another finish owns this attempt and its
                                   ;; failure handler owns the durable decision.
                                   (await-decision room run-id)))]
                (when (and (:parent-run opts) (run/cancel-requested? (:parent-run opts)))
                  (throw t))
                decision)))))))

(defn start
  "Start `task` `attempts` times for each of `models` on forks of `room`.
   Returns `{:spin s :ctx c :finish f}`: `s` yields the evaluations (deref or
   cancel it with `c` bound), `(f evaluations)` the rows `attempt!` returns.
   Validates before starting anything.

   The evaluations run in `room`'s own context (`c`), never a caller's: a Run's
   supervisor delivers into its room's context, and a daemon room's context is
   a child of the daemon's, so evaluating in the latter loses every wakeup.

   At most `:parallelism` attempts (default 4) run at once. With `:parent-run`
   every attempt's Run is that Run's child (a job, `dvergr.jobs`).
   `:review` is a review spec; `:daemon` and `:principal` configure its model
   reviewer (or supply `:reviewer`). `:on-settlement` is a host callback of
   `{:room :world :run-id :result}`, called on a worker after commitment of a
   world settled :review. Finish waits for these decisions at the boundary."
  [room {:keys [task attempts] :as opts}]
  (when (and (:review opts) (not (satisfies? room-store/PReviewStore (:store room))))
    (throw (ex-info "Automatic review requires a Review store" {})))
  (when (str/blank? (str task))
    (throw (ex-info "A workflow attempt needs a task" {:type ::no-task})))
  (let [n (or attempts 1)
        _ (when-not (<= 1 n (:attempts max-attempts))
            (throw (ex-info "attempts must be between 1 and 8" {:type ::attempts :attempts n})))
        _ (when (< (:models max-attempts) (count (:models opts)))
            (throw (ex-info "at most 4 models per call" {:type ::models})))
        {:keys [team ids]} (candidates (assoc opts :room (:id room)))
        evaluator (or (:evaluator opts) completion-evaluator)
        env (environment-def task opts)
        ctx (:ctx room)
        order (vec (for [id ids _ (range n)] id))]
    {:ctx ctx
     :spin (binding [ec/*execution-context* ctx]
             (experiment/run-batches
              (mapv #(evaluation/evaluate room team % env evaluator
                                          (select-keys opts [:parent-run :hold?]))
                    order)
              (max 1 (or (:parallelism opts) 4))))
     :finish (fn [results]
               (binding [ec/*execution-context* ctx]
                 ;; All completed attempts must survive a crash in the first review.
                 (let [intents (when (:review opts)
                                 (mapv (fn [r]
                                         (let [policy (intent-policy room opts r)]
                                           ((review-fn "intent!") room (:run/id r)
                                                                  (get-in r [:run/result :run/world]) policy)
                                           policy)) results))]
                   (try
                     (mapv (fn [r]
                             (let [world (get-in r [:run/result :run/world])
                                   decision (settle-on-worker! room opts r)]
                               (cond-> {:attempt (:attempt r)
                                        :world world
                                        :review (review world)}
                                 (:review opts) (assoc :decision decision))))
                           results)
                     (catch Throwable t
                       (when (:review opts)
                         (Thread/interrupted)
                         (doseq [[r policy] (map vector results intents)]
                           ((review-fn "failure!") room (:run/id r)
                                                   (get-in r [:run/result :run/world]) policy
                                                   (str "Settlement interrupted: " (ex-message t)))))
                       (throw t))))))}))

(defn attempt!
  "Run `task` `attempts` times for each of `models` on forks of `room` and
   block until all are certified. Call at a boundary (not inside a Spin).

   Returns `[{:attempt certified-Attempt :world world-id :review review-map}]`,
   with worlds retained unless a review spec or settlement hook decided them.
   Adopt an open world with `forks/merge!`, drop it with `forks/discard!`."
  [room opts]
  (let [{:keys [spin ctx finish]} (start room opts)]
    (finish (binding [ec/*execution-context* ctx] @spin))))
