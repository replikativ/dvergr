(ns dvergr.agent.evaluation
  "Algorithm-neutral execution of verified EnvironmentDefs.

   Evaluation does not introduce another scheduler, world, or inference model.
   It returns a Spin which admits an ordinary isolated Run, observes its durable
   outcome, invokes a matching host-owned verifier, and produces an attempt
   receipt. Parallelism, races, quorums, and later inference policies compose
   these Spins with the existing Spindel combinators."
  (:require [dvergr.agent.environment :as environment]
            [dvergr.effects :as effects]
            [dvergr.sandbox.extensions :as extensions]
            [dvergr.artifact :as artifact]
            [dvergr.agent.attempt :as attempt]
            [dvergr.agent.program :as program]
            [dvergr.agent.run :as run]
            [dvergr.agent.roster :as roster]
            [dvergr.agent.spend :as spend]
            [dvergr.resource :as resource]
            [dvergr.room.registry :as registry]
            [dvergr.rooms.forks :as forks]
            [hasch.core :as hasch]
            [org.replikativ.spindel.blocking :as blocking]
            [org.replikativ.spindel.core :as sp]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.engine.impl.simple :as simple]
            [org.replikativ.spindel.spin.combinators :as comb]
            [org.replikativ.spindel.spin.core :as spin-core]
            [org.replikativ.spindel.spin.sync :as sync]
            [org.replikativ.spindel.work :as work]
            [taoensso.telemere :as tel]))

(defrecord Evaluator [ref observe verify capture tier])

(def trust-tiers
  "Who vouches for a verifier, weakest last. `:trusted` verifiers ship with
   the host; `:room` verifiers were authored in a Room and vetted; `:ad-hoc`
   ones are unvetted. The tier is recorded on every receipt
   (`:verifier-trust` in its metrics), so a reward is never read without it."
  [:trusted :room :ad-hoc])
(defrecord WorldSetup [ref prepare])
(defrecord Protocol [ref run limit-keys inputs])

(defn- remaining-ms [deadline-nanos]
  ;; Ceiling avoids expiring a still-positive sub-millisecond remainder.
  (max 0 (long (Math/ceil (/ (- deadline-nanos (System/nanoTime)) 1000000.0)))))

(defonce ^:private pending-tasks (atom {}))

(declare positive-timeout! start-task!)

(defn- tracked-admission
  "Register BEFORE handing admission to an executor. Cancellation never
   interrupts admission half-way, nor releases its cleanup gate early: a late
   handle is cancelled and its passive physical-completion edge is joined.
   Ownership remains here until a registered scorer accepts it. Cancellation
   before that handoff also discards the completed, uncertified Run world."
  [room group admit abandon abandon-completed]
  (let [outcome (promise)
        handoff (promise)
        cancellation (promise)
        state (atom {:phase :pending :owner :admission})
        request-cancel!
        (fn []
          (when-let [handle
                     (locking state
                       (let [{:keys [handle cancelled? owner cancellation-requested?]} @state]
                         (when (and handle cancelled? (= :admission owner)
                                    (not cancellation-requested?))
                           (swap! state assoc :cancellation-requested? true)
                           handle)))]
            (deliver cancellation
                     (try (abandon handle) {:ok true}
                          (catch Throwable error {:error error})))))
        cancel! (fn []
                  (locking state
                    (when (= :admission (:owner @state))
                      (swap! state #(cond-> (assoc % :cancelled? true)
                                      (= :pending (:phase %)) (assoc :phase :cancelled)))
                      ;; Publish the decision before any fallible cancellation
                      ;; work. Never leave a scorer waiting for a lost owner.
                      (deliver handoff false)))
                  (request-cancel!))
        handoff! (fn []
                   (locking state
                     (if (:cancelled? @state)
                       false
                       (do (swap! state assoc :owner :scorer)
                           (deliver handoff true)
                           true))))]
    (start-task!
     room group
     (fn []
       (let [[before _] (swap-vals! state
                                    #(if (= :pending (:phase %))
                                       (assoc % :phase :admitting) %))]
         (if (= :cancelled (:phase before))
           (deliver outcome {:error (java.util.concurrent.CancellationException.
                                     "Evaluation cancelled before admission")})
           (try
             (let [handle (admit)]
               (locking state
                 (swap! state assoc :phase :admitted :handle handle))
               (deliver outcome {:ok handle})
               (request-cancel!)
               (let [result (program/result-spin handle)
                 ;; Even a failed cancellation request cannot release a live
                 ;; child's gate. This fresh passive observer is not owned by
                 ;; the cancelled graph. Completion follows resource return.
                     completed @result]
                 (when-not @handoff
                   (request-cancel!)
                   (let [cancel-result @cancellation
                         cleanup (abandon-completed handle completed)]
                     (when-not (true? (:ok? cleanup))
                       (throw (ex-info "Uncertified evaluation world requires cleanup recovery"
                                       {:type ::cleanup-failed :result cleanup})))
                     (when-let [error (:error cancel-result)] (throw error))))
                 (swap! state assoc :phase :done)
                 true))
             (catch Throwable error
               (deliver outcome {:error error})
               (throw error)))))))
    {:cancel! cancel!
     :handoff! handoff!
     :await-handoff (fn [] @handoff)
     :await (fn []
              (let [{:keys [ok error]} @outcome]
                (if error (throw error) ok)))}))

(defn- cleanup-scope [room]
  [(:id room) (:incarnation room)])

(defn cleanup-group
  "Create a process-local identity for cleanup tasks owned by one host
   operation. Pass it through evaluation/experiment options and join it with
   `await-cleanups-for!`; it is coordination authority, not durable evidence."
  []
  (random-uuid))

(defn- require-cleanup-group! [group]
  (when-not (uuid? group)
    (throw (ex-info "Cleanup group must be a UUID"
                    {:type ::invalid-cleanup-group :cleanup/group group})))
  group)

(defn- remove-task! [scope token]
  (swap! pending-tasks
         (fn [tasks]
           (let [remaining (dissoc (get tasks scope) token)]
             (if (seq remaining)
               (assoc tasks scope remaining)
               (dissoc tasks scope))))))

(defn- start-task! [room group task-fn]
  (let [scope (cleanup-scope room)
        token (random-uuid)
        gate (promise)
        started (promise)]
    ;; Registration happens before the Future can start. A caller that regains
    ;; control after evaluation cancellation can therefore never miss the
    ;; cleanup it must join before closing the Room.
    (swap! pending-tasks update scope (fnil assoc {}) token
           {:gate gate :group group})
    (let [worker
          (future
            (deliver started true)
            (let [outcome
                  (try
                    ;; Futures convey dynamic bindings, but this worker is no
                    ;; longer executing on the originating engine drain.
                    (let [result (binding [simple/*in-drain?* false
                                           ec/*spin-id* nil]
                                   (task-fn))]
                      (if (and (map? result) (false? (:ok? result)))
                        {:error (ex-info "Evaluation task failed"
                                         {:type ::cleanup-failed
                                          :result result})}
                        {:ok result}))
                    (catch Throwable error {:error error}))]
              (deliver gate outcome)
              ;; Successful work no longer needs a barrier entry. Failures stay
              ;; until a teardown owner observes them rather than being silently
              ;; forgotten.
              (when (contains? outcome :ok)
                (remove-task! scope token))))]
      {:token token :gate gate :started started :future worker})))

(defn track-controller!
  "HOST cleanup ownership of an existing admission controller. Register before
   submitting cells: cancellation is only a request, so controller completion
   must be joined before a host's cleanup scan can finish. This passive joiner
   never cancels the controller and must not itself be Future-cancelled."
  [room group controller]
  (when (some? group) (require-cleanup-group! group))
  (let [completion (work/completion controller)]
    (start-task! room group
                 #(deref (sp/spin (sp/await completion))))))

(defn- await-cleanups* [room group timeout-ms]
  (positive-timeout! "Cleanup timeout" timeout-ms)
  (let [scope (cleanup-scope room)
        deadline (+ (System/nanoTime) (* 1000000 timeout-ms))]
    (loop [observed #{} failures []]
      (let [entries (->> (get @pending-tasks scope)
                         (remove (comp observed key))
                         (filter (fn [[_ entry]]
                                   (or (nil? group)
                                       (= group (:group entry))))))]
        (if (seq entries)
          (let [outcomes
                (mapv
                 (fn [[token {:keys [gate]}]]
                   (let [remaining-ms
                         (max 1 (long (/ (- deadline (System/nanoTime))
                                         1000000)))
                         outcome (deref gate remaining-ms ::timeout)]
                     [token outcome]))
                 entries)
                failures'
                (into failures
                      (keep (fn [[token outcome]]
                              (cond
                                (= ::timeout outcome)
                                {:token token :type ::cleanup-timeout}

                                (:error outcome)
                                {:token token :type ::cleanup-failed
                                 :error (:error outcome)}

                                :else nil)))
                      outcomes)]
            ;; Every completed gate has now been handed to this owner. A timed
            ;; out task remains registered so a later join or Room teardown can
            ;; still recover it.
            (doseq [[token outcome] outcomes
                    :when (not= ::timeout outcome)]
              (remove-task! scope token))
            (recur (into observed (map first outcomes)) failures'))
          (if (seq failures)
            (throw (ex-info "Evaluation work did not quiesce before cleanup boundary"
                            {:type ::cleanup-incomplete
                             :room/id (:id room)
                             :cleanup/group group
                             :timeout-ms timeout-ms
                             :failures failures}))
            true))))))

(defn await-cleanups!
  "Block a Room teardown boundary until all detached evaluation cleanup completes.

   Evaluation cancellation itself stays non-blocking for the Spindel drain.
   Includes admission and the physical completion of admitted Runs, not only
   detached certification. Ephemeral Room owners must call this outside a Spin before closing the Room.
   Throws on timeout or a cleanup failure."
  ([room] (await-cleanups! room 30000))
  ([room timeout-ms]
   (await-cleanups* room nil timeout-ms)))

(defn await-cleanups-for!
  "Join only detached evaluation cleanup owned by `group` in `room`.

   This is the operation boundary for experiments in a shared Room. It never
   waits on or consumes failures from another cleanup group. Room owners must
   still use `await-cleanups!` before teardown."
  ([room group] (await-cleanups-for! room group 30000))
  ([room group timeout-ms]
   (await-cleanups* room (require-cleanup-group! group) timeout-ms)))

(defn make-evaluator
  "Create a process-local trusted evaluator capability.

   `id`, `version`, and optional portable `basis` must exactly match an
   EnvironmentDef verifier reference. `observe` receives durable execution
   facts (`:result :durable :room :world/room :run-id :environment :agent`,
   `:timed-out?` when the Run was cancelled at the environment's deadline,
   and the setup and execution evidence) and returns portable evidence; a
   `:spend` it returns (`dvergr.agent.spend`) is the Attempt's bill on the
   receipt, else the Run's own metrics are. Model calls `observe` makes (a
   judge's, charged with `resource/charge-usage!`) are paid by the wallet the
   Run was granted from and recorded apart, as `:judge-spend` in the
   receipt's metrics: the experiment's cost, not the candidate's.
   `verify` receives the EnvironmentDef plus that evidence and returns
   `{:checks {keyword boolean} :reward number}`,
   and optionally `:metrics`, a portable map of what it measured (the receipt
   records it under `:metrics :verification`).
   Optional `capture` receives `{:room control-room :world/room work-room
   :run-id uuid :environment EnvironmentDef}` after candidate work and owned
   resource cleanup quiesce, before world settlement (including failure and
   cancellation). It must perform bounded, read-only collection of portable
   data, not execute candidate source or depend on already-closed resources.
   Its result is passed to `observe` as `:execution/evidence`; the observer must
   include it in its returned map to persist it. Capture errors fail
   certification, not the candidate Run. Capture is supervised cleanup and
   must terminate promptly. Capture time counts toward the Run deadline, but
   cancellation does not interrupt cleanup.
   `tier` is one of `trust-tiers` (default `:trusted`: only host code can
   construct an Evaluator; hosts building one from agent-authored source pass
   the weaker tier). Evaluators are deliberately not portable and are never
   exposed to SCI."
  [{:keys [id version basis observe verify capture tier]
    :or {version 1 tier :trusted}}]
  (when-not (some #{tier} trust-tiers)
    (throw (ex-info "Evaluator :tier must be a trust tier"
                    {:type ::invalid-evaluator-tier :tier tier
                     :allowed trust-tiers})))
  (when-not (keyword? id)
    (throw (ex-info "Evaluator :id must be a keyword"
                    {:type ::invalid-evaluator-id :id id})))
  (when-not (and (integer? version) (pos? version))
    (throw (ex-info "Evaluator :version must be a positive integer"
                    {:type ::invalid-evaluator-version :version version})))
  (when-not (fn? observe)
    (throw (ex-info "Evaluator :observe must be a function"
                    {:type ::invalid-observer})))
  (when-not (fn? verify)
    (throw (ex-info "Evaluator :verify must be a function"
                    {:type ::invalid-verifier})))
  (when-not (or (nil? capture) (fn? capture))
    (throw (ex-info "Evaluator :capture must be a function"
                    {:type ::invalid-capture})))
  (when-not (roster/data-value? basis)
    (throw (ex-info "Evaluator :basis must contain only portable data"
                    {:type ::invalid-evaluator-basis :basis basis})))
  (->Evaluator (cond-> {:verifier/id id :verifier/version version}
                 (some? basis) (assoc :verifier/basis basis))
               observe verify capture tier))

(defn make-world-setup
  "Create a process-local trusted preparer for one exact world setup.

   `prepare` runs after the candidate Run and its isolated world exist, but
   before resource allocation or candidate work. Its private causal trigger is
   already durable. It receives
   `{:room isolated-work-room :run/id uuid :environment EnvironmentDef}` and may
   transact only against that fork-local world. Its optional return value must
   be portable data and is supplied to the trusted Evaluator observer as
   `:setup/evidence`. The capability itself is never portable or exposed to SCI."
  [{:keys [id version basis prepare]
    :or {version 1}}]
  (when-not (keyword? id)
    (throw (ex-info "World setup :id must be a keyword"
                    {:type ::invalid-setup-id :id id})))
  (when-not (and (integer? version) (pos? version))
    (throw (ex-info "World setup :version must be a positive integer"
                    {:type ::invalid-setup-version :version version})))
  (when-not (roster/data-value? basis)
    (throw (ex-info "World setup :basis must contain only portable data"
                    {:type ::invalid-setup-basis :basis basis})))
  (when-not (fn? prepare)
    (throw (ex-info "World setup :prepare must be a function"
                    {:type ::invalid-setup-preparer})))
  (->WorldSetup (cond-> {:setup/id id :setup/version version}
                  (some? basis) (assoc :setup/basis basis))
                prepare))

(defn make-protocol
  "Create a process-local trusted interaction protocol.

   By default a Run interprets its AgentDef's program against one task. An
   environment that names a protocol instead has the Run host an interaction
   in its isolated world, e.g. a conversation between the candidate and an
   environment driver (a simulated counterpart with its own tools and private
   scenario).

   `run` is blocking host work, called under the Run's supervisor with
   `{:control-room :room (the isolated work Room) :run/id :agent (AgentDef)
   :task :limits :cancelled? (fn [])}`. It must leave the work Room quiescent
   (no participants, no live Runs) and return the Run's portable value.
   `limit-keys` names the environment limits this protocol interprets, beyond
   the evaluator's own.

   `inputs` (optional, `(fn [agent])` → portable data) says what the protocol
   resolves at Run start and presents to the candidate `agent`, beyond its
   ref, the AgentDef and the EnvironmentDef: e.g. the system prompt it
   assembles, or the tool schemas it offers. It enters the Attempt's inputs
   id (`dvergr.agent.program/run-inputs`), so a resume re-runs cells made
   when it said something else. The capability is never portable or exposed
   to SCI."
  [{:keys [id version basis run limit-keys inputs]
    :or {version 1 limit-keys #{}}}]
  (when-not (keyword? id)
    (throw (ex-info "Protocol :id must be a keyword"
                    {:type ::invalid-protocol-id :id id})))
  (when-not (and (integer? version) (pos? version))
    (throw (ex-info "Protocol :version must be a positive integer"
                    {:type ::invalid-protocol-version :version version})))
  (when-not (roster/data-value? basis)
    (throw (ex-info "Protocol :basis must contain only portable data"
                    {:type ::invalid-protocol-basis :basis basis})))
  (when-not (fn? run)
    (throw (ex-info "Protocol :run must be a function"
                    {:type ::invalid-protocol-run})))
  (when-not (and (set? limit-keys) (every? keyword? limit-keys))
    (throw (ex-info "Protocol :limit-keys must be a set of keywords"
                    {:type ::invalid-protocol-limit-keys :limit-keys limit-keys})))
  (when-not (or (nil? inputs) (fn? inputs))
    (throw (ex-info "Protocol :inputs must be a function"
                    {:type ::invalid-protocol-inputs})))
  (->Protocol (cond-> {:protocol/id id :protocol/version version}
                (some? basis) (assoc :protocol/basis basis))
              run limit-keys inputs))

(defn protocol-ref
  "Return the portable exact reference named by a Protocol capability."
  [protocol]
  (:ref protocol))

(defn evaluator-ref
  "Return the portable verifier reference named by an Evaluator capability."
  [evaluator]
  (:ref evaluator))

(defn evaluator-tier
  "The trust tier an Evaluator capability was constructed with."
  [evaluator]
  (:tier evaluator))

(defn world-setup-ref
  "Return the portable exact reference named by a WorldSetup capability."
  [setup]
  (:ref setup))

(defn- require-matching-evaluator! [definition evaluator]
  (when-not (instance? Evaluator evaluator)
    (throw (ex-info "Evaluation requires a host Evaluator capability"
                    {:type ::invalid-evaluator})))
  (let [expected (:environment/verifier definition)
        actual (evaluator-ref evaluator)]
    (when-not (= expected actual)
      (throw (ex-info "Evaluator does not match the EnvironmentDef verifier"
                      {:type ::evaluator-mismatch
                       :expected expected
                       :actual actual}))))
  evaluator)

(defn- require-matching-setup! [definition setup]
  (let [expected (get-in definition [:environment/world :setup])]
    (cond
      (nil? expected)
      (when setup
        (throw (ex-info "Environment without :setup received a WorldSetup"
                        {:type ::unexpected-world-setup
                         :actual (when (instance? WorldSetup setup)
                                   (:ref setup))})))

      (not (instance? WorldSetup setup))
      (throw (ex-info "Environment requires an exact host WorldSetup"
                      {:type ::missing-world-setup :expected expected}))

      (not= expected (:ref setup))
      (throw (ex-info "WorldSetup does not match EnvironmentDef"
                      {:type ::world-setup-mismatch
                       :expected expected :actual (:ref setup)})))
    setup))

(defn- require-matching-protocol! [definition protocol]
  (let [expected (get-in definition [:environment/world :protocol])]
    (cond
      (nil? expected)
      (when protocol
        (throw (ex-info "Environment without :protocol received a Protocol"
                        {:type ::unexpected-protocol
                         :actual (when (instance? Protocol protocol)
                                   (:ref protocol))})))

      (not (instance? Protocol protocol))
      (throw (ex-info "Environment requires an exact host Protocol"
                      {:type ::missing-protocol :expected expected}))

      (not= expected (:ref protocol))
      (throw (ex-info "Protocol does not match EnvironmentDef"
                      {:type ::protocol-mismatch
                       :expected expected :actual (:ref protocol)})))
    protocol))

(defn- default-evidence [{:keys [result durable]}]
  {:result (:run/value result)
   :trace
   {:runs [(select-keys durable
                        [:run/id :run/parent :run/caused-by :run/actor
                         :run/status :run/error :run/settlement-status
                         :run/roster :run/agent-version :run/agent-def-hash
                         :run/program-kind :run/interpreter-version])]}})

(defn- execution-identity [agent result durable]
  (let [metrics (:run/metrics result)
        kind (or (:run/program-kind durable)
                 (get-in agent [:agent/program :kind]))
        model-policy (:agent/model-policy agent)
        resolved? (and (:provider metrics) (:model metrics))
        intended? (and (:provider model-policy) (:model model-policy))]
    {:provider (or (:provider metrics) (:provider model-policy) :dvergr)
     :model (or (:model metrics) (:model model-policy) (name kind))
     :metrics
     (merge {:program-kind kind
             :model-resolution (cond resolved? :resolved
                                     intended? :intended
                                     :else :not-applicable)
             :agent-version (or (:run/agent-version durable)
                                (:agent/version agent))
             :agent-def-hash (or (:run/agent-def-hash durable)
                                 (hasch/uuid agent))
             :interpreter-version (or (:run/interpreter-version durable)
                                      program/interpreter-version)}
            (when-let [roster-id (:run/roster durable)]
              {:roster roster-id})
            ;; what the AgentDef's :sandbox names resolved to in this process
            (when-let [ids (seq (:agent/sandbox agent))]
              {:sandbox-extensions (extensions/refs ids)})
            metrics)}))

(defn- positive-timeout! [label value]
  (when-not (and (integer? value) (pos? value))
    (throw (ex-info (str label " must be a positive integer")
                    {:type ::invalid-timeout :label label :value value})))
  value)

(defn- require-supported-policy! [definition agent protocol]
  (let [limits (:environment/limits definition)
        world (:environment/world definition)
        model-limits (select-keys limits [:max-model-steps :budget-dollars])]
    (when-let [unknown (seq (remove (into #{:timeout-ms :cancel-timeout-ms :on-timeout
                                            :max-model-steps :budget-dollars}
                                          (:limit-keys protocol))
                                    (keys limits)))]
      (throw (ex-info "Evaluation environment contains unsupported limits"
                      {:type ::unsupported-evaluation-limits
                       :unknown (set unknown)})))
    ;; :fault (default): a timed-out Attempt is re-run; :verdict: it is scored.
    (when-not (contains? #{nil :fault :verdict} (:on-timeout limits))
      (throw (ex-info "Environment :on-timeout must be :fault or :verdict"
                      {:type ::invalid-on-timeout :on-timeout (:on-timeout limits)})))
    (when-let [unknown (seq (remove #{:isolation :settlement :resources :setup
                                      :protocol :effects}
                                    (keys world)))]
      (throw (ex-info
              "Evaluation environment contains unsupported world policy; setup requires a trusted resolver"
              {:type ::unsupported-evaluation-world
               :unknown (set unknown)})))
    ;; the world's effect handlers (dvergr.effects/environment-handlers!)
    (when-let [fx (:effects world)]
      (let [bad (or (seq (remove #{:faults :record :read-only :admit :deny-hosts :allow-hosts :quota-bytes :speculative} (keys fx)))
                    (when-let [hs (:allow-hosts fx)] (when-not (and (coll? hs) (every? string? hs)) [:allow-hosts]))
                    (when-let [q (:quota-bytes fx)] (when-not (pos-int? q) [:quota-bytes]))
                    (when-let [hs (:deny-hosts fx)] (when-not (and (coll? hs) (every? string? hs)) [:deny-hosts]))
                    (seq (remove #{:seed :rate :only :kinds} (keys (:faults fx))))
                    (when-let [r (get-in fx [:faults :rate])]
                      (when-not (and (number? r) (<= 0 r 1)) [:rate]))
                    (seq (remove #{:error :timeout :rate-limit :server-error}
                                 (get-in fx [:faults :kinds]))))]
        (when bad
          (throw (ex-info "Environment :world :effects is not a supported handler configuration"
                          {:type ::unsupported-world-effects :effects fx :invalid (vec bad)})))))
    (when (and (seq model-limits)
               (not= :llm (get-in agent [:agent/program :kind])))
      (throw (ex-info "Environment model limits require an LLM AgentDef"
                      {:type ::model-limits-require-llm
                       :agent/id (:agent/id agent)
                       :limits model-limits})))
    model-limits))

(defn- settle-certified! [fork requested claim!]
  (when-not fork
    (throw (ex-info "Deferred evaluation world is no longer available"
                    {:type ::missing-evaluation-world})))
  (case requested
    :review
    (forks/release-deferred! fork :evaluation-certified claim!)

    :discard
    (let [settled (forks/discard-deferred! fork :evaluation-policy claim!)]
      (when-not (:ok? settled)
        (throw (ex-info "Certified evaluation world could not be discarded"
                        {:type ::settlement-failed
                         :settlement requested
                         :fork/id (:id fork)
                         :error (:error settled)})))))
  requested)

(defn- discard-uncertified! [fork reason]
  (when fork
    (forks/discard-deferred! fork reason)))

(defn- abandon-completed-admission! [room handle result]
  (if (= :deferred (:run/settlement-status result))
    (binding [ec/*execution-context* (:ctx room)]
      (let [fork (some-> (:run/world result) registry/lookup)
            run-id (program/run-id handle)]
        (when-not (and fork (= run-id (:run-id @(:meta fork)))
                       (forks/deferred? fork))
          (throw (ex-info "Uncertified admission has lost its deferred world"
                          {:type ::cleanup-failed :run/id run-id
                           :run/world (:run/world result)})))
        (discard-uncertified! fork :evaluation-cancelled)))
    {:ok? true}))

(defn- spin-cancelled? [error]
  (loop [error error]
    (when error
      (or (= spin-core/spin-cancelled (:type (ex-data error)))
          (= "Spin cancelled" (ex-message error))
          (recur (ex-cause error))))))

(defn- effect-log
  "What an Attempt's world did, for its metrics: how many effects, the
   denials by who decided them, how many may not be redone (`:once`), and the
   receipts themselves as a content-addressed artifact of the control room
   (`:log`), when it has a store."
  [room receipts]
  (let [receipts (vec receipts)
        store (or (some-> room :store :artifacts)
                  (some-> room :store :conn artifact/datahike-store))
        log (when store
              (try (artifact/publish-value! store {:dvergr/effect-log receipts} identity)
                   (catch Throwable e
                     (tel/log! {:level :warn :id ::effect-log-not-stored :data {:error (ex-message e)}}
                               "An Attempt's effect log could not be stored")
                     nil)))]
    (cond-> {:count (count receipts)
             :denials (effects/denials receipts)
             :once (count (filter #(= :once (:idempotency %)) receipts))}
      log (assoc :log (str log)))))

(defn- observation-payer
  "Who pays for the model calls an Attempt's observation makes (a judge): the
   wallet the candidate's Run was granted from, its parent Run's or else the
   control Room's, when it holds microdollars. Judging is the experiment's
   cost, never the candidate's: the candidate's wallet is not charged (and was
   returned when its Run ended)."
  [room durable]
  (if-let [parent (:run/parent durable)]
    (resource/spend-wallet room parent)
    (resource/room-spend-wallet room)))

(defn- certification-candidate
  [{:keys [room world-room setup-evidence execution-evidence definition evaluator agent run-id
           result durable started-at started-nanos timeout? extra-metrics effect-receipts]}]
  (let [;; the observation's model calls (a judge's), in their own charge
        ;; scope: observing this Run again does not charge them twice
        judging (resource/spending (observation-payer room durable) [:observe run-id])
        evidence (binding [resource/*spend-wallet* judging]
                   ((:observe evaluator)
                    {:room room
                     :agent agent
                     :world/room world-room
                     :setup/evidence setup-evidence
                     :execution/evidence execution-evidence
                     :environment definition
                     :run-id run-id
                     :result result
                     :durable durable
                     ;; the Run was cancelled at the environment's deadline
                     :timed-out? (boolean timeout?)
                     :default (default-evidence {:result result
                                                 :durable durable})}))
        _ (when-not (and (map? evidence) (roster/data-value? evidence))
            (throw (ex-info "Evaluator evidence must be a portable map"
                            {:type ::invalid-evidence :evidence evidence})))
        {:keys [checks reward] verification :metrics} ((:verify evaluator) definition evidence)
        _ (when (and (some? verification) (not (and (map? verification) (roster/data-value? verification))))
            (throw (ex-info "Evaluator :verify :metrics must be a portable map"
                            {:type ::invalid-verification :metrics verification})))
        {:keys [provider model metrics]} (execution-identity agent result durable)
        ;; What the Attempt cost, on every receipt: an LLM program's Run
        ;; carries its chat budget in :run/metrics; a protocol Run's observer
        ;; puts its provider's usage, folded by `spend`, under :spend.
        attempt-spend (or (:spend evidence)
                          (spend/of-metrics (:run/metrics result)))
        ;; what judging the Attempt cost (the observation's model calls), kept
        ;; apart: it is the experiment's cost, not the candidate's
        judge-spend (when-let [calls (seq @(:calls judging))]
                      (spend/total (map (fn [{:keys [model usage]}] (spend/of-usage model usage)) calls)))
        elapsed-ms (long (/ (- (System/nanoTime) started-nanos) 1000000))
        receipt
        (environment/make-attempt-receipt
         definition
         (cond-> {:run-id run-id
                  :provider provider
                  :model model
                  :status (:run/status result)
                  :started-at started-at
                  :elapsed-ms elapsed-ms
                  ;; Host-supplied metrics (e.g. an experiment's cell
                  ;; identity) never override what the evaluation measured.
                  :metrics (cond-> (assoc (merge extra-metrics metrics)
                                          :timed-out? timeout?
                                          :verifier-trust (:tier evaluator)
                                          :spend attempt-spend)
                             judge-spend (assoc :judge-spend judge-spend)
                             ;; what the verifier measured beside its checks
                             ;; (which cells mismatched, how many compared)
                             (seq verification) (assoc :verification verification)
                             (seq effect-receipts)
                             (assoc :effects (effect-log room effect-receipts))
                             ;; An environment may count a timeout against the
                             ;; candidate (a verdict: it did not finish in time)
                             ;; rather than as a fault to re-run.
                             (and timeout?
                                  (= :verdict (get-in definition [:environment/limits :on-timeout]))
                                  (not (:failure metrics)))
                             (assoc :failure {:kind :model
                                              :cause (str "timed out after "
                                                          (get-in definition [:environment/limits :timeout-ms])
                                                          " ms")}))
                  :checks checks
                  :reward reward}
           (contains? evidence :result) (assoc :result (:result evidence))
           (contains? evidence :trace) (assoc :trace (:trace evidence))
           (contains? evidence :resources) (assoc :resources
                                                  (:resources evidence))))]
    {:evidence evidence :receipt receipt}))

(defn evaluate
  "Return a Spin which evaluates one AgentDef in one EnvironmentDef.

   The Run is the causal execution identity and its ordinary RunWorld is the
   isolated scenario. Environment `:world` may specify `:isolation`,
   `:settlement`, a conserved `:resources` vector, a `:setup` reference (with
   a matching `:world-setup` capability), a `:protocol` reference (with a
   matching `:protocol` capability) and `:effects` (the world's effect
   handlers: faults, record, read-only, admission, host lists, quota).
   `:limits` may specify `:timeout-ms`, `:cancel-timeout-ms`, `:on-timeout`
   (`:fault`, the default, or `:verdict`), for an `:llm` program
   `:max-model-steps` and `:budget-dollars`, and the limit keys a protocol
   declares. One monotonic timeout covers admission, setup, allocation and
   execution through physical quiescence. Admission expiry is a host fault,
   not a model-quality Attempt; a late Run remains owned until cancelled and
   joined. Cleanup has its own grace period and may outlive the deadline.
   Verification/certification run after quiescence, outside this timeout.

   Options may provide `:from` and structural `:parent-run`. `:world-parent`
   (default `room`) is the Room the attempt's world forks: a world inside
   another Run's world, while `room` keeps the durable Run and Attempt. The
   Spin must then run in `:world-parent`'s context. The returned map
   contains portable evidence/receipt plus the process-local RunHandle; callers
   settle a retained world through the existing room-fork APIs."
  ([room team agent-ref definition evaluator]
   (evaluate room team agent-ref definition evaluator {}))
  ([room team agent-ref definition evaluator
    {:keys [from parent-run world-setup cleanup-group protocol metrics world-parent hold?]
     :or {from :environment} :as opts}]
   (environment/validate-environment definition)
   (require-matching-evaluator! definition evaluator)
   (when-not (or (nil? metrics) (and (map? metrics) (roster/data-value? metrics)))
     (throw (ex-info "Evaluation :metrics must be a portable map"
                     {:type ::invalid-metrics :metrics metrics})))
   (let [world-setup (require-matching-setup! definition world-setup)
         protocol (require-matching-protocol! definition protocol)
         agent (roster/agent team agent-ref)
         {:keys [timeout-ms cancel-timeout-ms]
          :or {timeout-ms 120000 cancel-timeout-ms 10000}}
         (:environment/limits definition)
         {:keys [settlement resources]
          :or {settlement :review}}
         (:environment/world definition)
         model-limits (when agent (require-supported-policy! definition agent protocol))]
     (when-let [unknown (seq (remove #{:from :parent-run :world-setup
                                       :cleanup-group :protocol :metrics :world-parent
                                       :hold? :experiment-capabilities :delegation-policy}
                                     (keys opts)))]
       (throw (ex-info "Evaluation contains unknown options"
                       {:type ::unknown-evaluation-options
                        :unknown (set unknown)})))
     (when-not agent
       (throw (ex-info "Evaluation AgentDef does not exist in the Roster"
                       {:type ::unknown-agent :agent-ref agent-ref})))
     (when (some? cleanup-group) (require-cleanup-group! cleanup-group))
     (positive-timeout! "Environment :timeout-ms" timeout-ms)
     (positive-timeout! "Environment :cancel-timeout-ms" cancel-timeout-ms)
     (when-not (contains? #{nil :ctx}
                          (get-in definition [:environment/world :isolation]))
       (throw (ex-info "Evaluation environments currently require :ctx isolation"
                       {:type ::unsupported-evaluation-isolation
                        :isolation (get-in definition
                                           [:environment/world :isolation])})))
     (when-not (#{:review :discard} settlement)
       (throw (ex-info
               "Evaluations must retain successful worlds for review or discard them"
               {:type ::unsafe-evaluation-settlement
                :settlement settlement
                :allowed #{:review :discard}})))
     (sp/spin
      (let [started-at (System/currentTimeMillis)
            started-nanos (System/nanoTime)
            deadline-nanos (+ started-nanos (* 1000000 timeout-ms))
            hire-opts (cond-> {:task (:environment/task definition)
                               :from from
                               ;; Verification is a two-phase gate over this
                               ;; same RunWorld. Existing merge/adoption
                               ;; operations reject it until trusted scoring.
                               :settlement :deferred}
                        parent-run (assoc :parent-run parent-run)
                        (seq resources) (assoc :resources resources)
                        (seq model-limits) (assoc :limits model-limits))
            setup-evidence (atom nil)
            ;; Per-invocation host handoff, not another world-state store.
            ;; Portable captured evidence enters the durable Attempt.
            captured (atom ::pending)
            world-effects (get-in definition [:environment/world :effects])
            ;; every Attempt world keeps its receipts: what the candidate's
            ;; effects were, kept with the Attempt (durable, content-addressed)
            world-sink (effects/make-sink)
            prepare-world!
            (fn [context]
              (effects/set-world-sink! (:ctx (:room context)) world-sink)
                ;; The environment's effect handlers (faults, recording,
                ;; read-only) apply to everything that runs in the isolated
                ;; world, the candidate's sandbox and an external agent's MCP
                ;; eval alike. The same seed for every Attempt: candidates meet
                ;; the same faults, so comparisons stay paired.
                ;; Registered before the capture below, so released after it
                ;; (cleanup is LIFO): a capture may read the world's recording.
              (when world-effects
                (let [{:keys [specs release recording]} (effects/environment-handlers! world-effects)
                      world (:ctx (:room context))]
                  ((:register-cleanup! context) (fn [] (release) nil))
                  (effects/install-world! world specs)
                  (when recording (effects/set-world-recording! world recording))))
              (when-let [capture (:capture evaluator)]
                  ;; Register first: supervisor cleanup is LIFO, so capture
                  ;; sees the final substrate after other resource cleanup.
                ((:register-cleanup! context)
                 (fn []
                   (reset! captured
                           (try
                             (let [evidence
                                   (capture {:room room
                                             :world/room (:room context)
                                             :run-id (:run/id context)
                                             :environment definition})]
                               (when-not (roster/data-value? evidence)
                                 (throw (ex-info "Captured evidence must be portable"
                                                 {:type ::invalid-captured-evidence})))
                               {:ok evidence})
                             (catch Throwable error {:error error})))
                   nil)))
              (when world-setup
                (let [evidence ((:prepare world-setup)
                                (assoc context :environment definition))]
                  (when-not (roster/data-value? evidence)
                    (throw (ex-info "World setup evidence must be portable"
                                    {:type ::non-portable-setup-evidence
                                     :setup (:ref world-setup)})))
                  (reset! setup-evidence evidence))))
            ;; Like the world setup, the protocol is told which exact
            ;; environment it is hosting.
            hosted-protocol
            (when protocol
              (update protocol :run
                      (fn [run] (fn [context]
                                  (run (assoc context :environment definition))))))
            ;; Admission (fork the world, the Run's durable start) blocks on
            ;; the store, so it runs off the drain. (It once hung there at
            ;; :parallelism 8: spins built on several threads at once shared
            ;; ids, spindel#110.)
            admission (tracked-admission
                       room cleanup-group
                       #(program/hire-prepared-in! room (or world-parent room) team agent-ref
                                                   hire-opts prepare-world! hosted-protocol
                                                   (assoc (select-keys opts [:experiment-capabilities :delegation-policy])
                                                          :deadline-nanos deadline-nanos))
                       #(program/cancel! room %)
                       #(abandon-completed-admission! room %1 %2))]
        (try
          (let [timed-out ::timed-out
                handle (try
                         (let [left (remaining-ms deadline-nanos)
                               admitted (if (pos? left)
                                          (sp/await (comb/timeout (blocking/blocking (:await admission))
                                                                  left timed-out))
                                          timed-out)]
                           (when (= timed-out admitted)
                             (throw (ex-info "Environment deadline expired during admission; late Run cleanup remains owned"
                                             {:type ::admission-timeout :timeout-ms timeout-ms
                                              :phase :admission :cleanup/group cleanup-group})))
                           admitted)
                         (catch Throwable error
                           ((:cancel! admission))
                           (throw error)))
                ;; when the Run was first asked to cancel: before the deadline,
                ;; someone else cancelled it and it did not time out
                cancel-requested-at (atom nil)
                _ (run/register-cancel-hook! (program/run-id handle) ::cancel-provenance
                                             #(compare-and-set! cancel-requested-at nil (System/nanoTime)))
                left (remaining-ms deadline-nanos)
                initial (if (pos? left)
                          (sp/await (comb/timeout (program/owned-result-spin handle)
                                                  left timed-out))
                          timed-out)
            ;; A delayed timer must not make an overdue result successful. A
            ;; Run someone else had asked to cancel before the deadline was not
            ;; timed out, though its cleanup ran past the deadline.
                deadline? (or (= timed-out initial) (zero? (remaining-ms deadline-nanos)))
                timeout? (and deadline?
                              (not (some-> @cancel-requested-at (< deadline-nanos))))
                _ (when timeout? (program/cancel! room handle))
                result (if deadline?
                         (sp/await
                          (comb/timeout (program/result-spin handle)
                                        cancel-timeout-ms timed-out))
                         initial)
                _ (run/unregister-cancel-hook! (program/run-id handle) ::cancel-provenance)]
            (when (= timed-out result)
              (throw (ex-info "Environment Run did not quiesce after cancellation"
                              {:type ::cancellation-timeout
                               :run/id (program/run-id handle)
                               :cancel-timeout-ms cancel-timeout-ms})))
            (let [run-id (program/run-id handle)
                  durable (sp/await (blocking/blocking #(program/observe room handle)))
                  fork (some-> (:run/world result) registry/lookup)
                  _ (when (contains? #{:world-setup-failed
                                       :world-setup-cancelled}
                                     (:run/reason durable))
                  ;; Setup is trusted environment construction, not candidate
                  ;; work. It has an auditable Run and settled world but must
                  ;; never produce a reward-bearing Attempt or enter the
                  ;; evaluator.
                      (throw
                       (ex-info (or (:run/error durable)
                                    "Environment world setup did not complete")
                                {:type ::world-setup-failed
                                 :run/id run-id
                                 :run/world (:run/world result)
                                 :run/status (:run/status durable)
                                 :run/reason (:run/reason durable)})))
                  evaluation-spin-id ec/*spin-id*
                  cancelled-externally?
                  #(and evaluation-spin-id
                        (ec/spin-current-result evaluation-spin-id))
                  state (atom :scoring)
                  persisted-attempt (atom nil)
                  cleanup-result (atom ::pending)
                  cleanup-once!
                  (fn [reason]
                    (locking cleanup-result
                      (if (= ::pending @cleanup-result)
                        (let [cleanup (discard-uncertified! fork reason)]
                          (reset! cleanup-result cleanup)
                          cleanup)
                        @cleanup-result)))
                  done (sync/deferred)
              ;; `done` lives in this evaluation's context. The certification
              ;; worker below binds the control room's, which differs when the
              ;; world parent is another Room (or a fork of it): delivering
              ;; there would reach none of the readers awaiting here.
                  done-ctx (ec/current-execution-context)
                  deliver-done! (fn [value]
                                  (binding [ec/*execution-context* done-ctx]
                                    (sync/deliver! done value)))
                  certification-failure!
                  (fn [error]
                    (if (cancelled-externally?)
                      (compare-and-set! state :scoring :cancelled)
                      (compare-and-set! state :scoring :failed))
                    (let [cleanup
                          (cleanup-once!
                           (if (= :cancelled @state)
                             :evaluation-cancelled
                             :evaluation-certification-failed))]
                      (deliver-done!
                       {:error
                        (ex-info "Evaluation certification failed"
                                 {:type ::certification-failed
                                  :run/id run-id
                                  :run/world (:run/world result)
                                  :cleanup cleanup}
                                 error)})
                      cleanup))
                  settlement-failure!
                  (fn [error]
                    (deliver-done!
                     {:error
                      (ex-info "Evaluation was certified but world settlement requires recovery"
                               {:type ::settlement-recovery-required
                                :run/id run-id
                                :run/world (:run/world result)
                                :attempt @persisted-attempt
                                :run/settlement-status
                                (:run/settlement-status result)}
                               error)})
                    {:ok? false
                     :run/id run-id
                     :run/world (:run/world result)
                     :reason :settlement-recovery-required})
                  worker
                  (start-task!
                   room
                   cleanup-group
                   (fn []
                 ;; A registered worker is not yet the settlement owner.
                 ;; Keep pre-handoff interruption outside certification's
                 ;; compensation handler: only one side may discard.
                     (when ((:await-handoff admission))
                       (binding [ec/*execution-context* (:ctx room)
                                 ec/*spin-id* nil]
                         (try
                           (let [_ (when (and timeout? (= :completed (:run/status result)))
                               ;; The candidate may have completed late, or
                               ;; its observer resumed late. Neither supports
                               ;; a bounded quality verdict. Keep the durable
                               ;; Run truthful and use this worker's existing
                               ;; compensation path to discard its world.
                                     (throw (ex-info "Completed Run was observed beyond its execution deadline"
                                                     {:type ::overdue-completion :run/id run-id})))
                                 _ (when (and (:capture evaluator)
                                              (not (and (map? @captured)
                                                        (contains? @captured :ok))))
                                     (throw (ex-info "Execution evidence capture failed"
                                                     {:type ::capture-failed :run/id run-id}
                                                     (:error @captured))))
                                 {:keys [evidence receipt]}
                                 (certification-candidate
                                  {:room room :definition definition
                                   :evaluator evaluator :agent agent :run-id run-id
                                   :world-room fork
                                   :setup-evidence @setup-evidence
                                   :execution-evidence (:ok @captured)
                                   :effect-receipts @world-sink
                                   :result result :durable durable
                                   :started-at started-at :started-nanos started-nanos
                                   :timeout? timeout? :extra-metrics metrics})
                                 certified-attempt
                                 (attempt/make-attempt definition agent receipt evidence
                                                       settlement)]
                             (let [deferred? (= :deferred
                                                (:run/settlement-status result))
                                   claim!
                                   #(if (cancelled-externally?)
                                      (do
                                        (compare-and-set! state :scoring :cancelled)
                                        false)
                                      (when (compare-and-set! state :scoring
                                                              :certifying)
                                 ;; This write occurs inside the fork's affine
                                 ;; settlement lock. The world cannot become
                                 ;; reviewable or disappear before its trusted
                                 ;; certification is durable.
                                        (reset! persisted-attempt
                                                (attempt/persist! room
                                                                  certified-attempt))
                                        true))]
                               (when (cancelled-externally?)
                                 (compare-and-set! state :scoring :cancelled))
                               (if (or deferred? (claim!))
                                 (let [final-settlement
                                       (cond
                                   ;; host-only: a search holds its members
                                   ;; behind the deferred gate until it has
                                   ;; decided (dvergr.agent.search); the
                                   ;; certification is durable either way
                                         (and deferred? hold?)
                                         (if (claim!)
                                           :deferred
                                           (throw (ex-info "Held evaluation world lost its settlement race"
                                                           {:type ::settlement-failed :fork/id (:id fork)})))
                                         deferred? (settle-certified! fork settlement claim!)
                                         :else (:run/settlement-status result))
                                       result (assoc result
                                                     :run/settlement-status
                                                     (case final-settlement
                                                       :discard :discarded
                                                       :review :review
                                                       final-settlement))]
                                   (reset! state :certified)
                                   (deliver-done!
                                    {:ok {:environment definition
                                          :attempt @persisted-attempt
                                          :attempt-receipt receipt
                                          :evidence evidence
                                          :run/id run-id
                                          :run/result result
                                          :run/handle handle}}))
                                 (cleanup-once! :evaluation-cancelled))))
                           (catch Throwable error
                             (if @persisted-attempt
                               (settlement-failure! error)
                               (certification-failure! error))))))))
                  _ ((:handoff! admission))]
              (try
                (let [{:keys [ok error]} (sp/await done)]
                  (if error (throw error) ok))
                (catch Throwable error
                  (when (and (spin-cancelled? error)
                             (compare-and-set! state :scoring :cancelled))
                ;; Interrupt an evaluator already executing on its worker. A
                ;; not-yet-started worker is left scheduled: it will observe the
                ;; closed state and cannot certify, while its registered gate
                ;; prevents teardown from racing it.
                ;; Cancellation remains non-blocking for the Spindel drain.
                ;; The scorer owns cleanup and stays registered until it
                ;; physically exits; the closed gate prevents certification.
                    (when (realized? (:started worker))
                      (future-cancel (:future worker))))
                  (throw error)))))
          (catch Throwable error
            ((:cancel! admission))
            (throw error))))))))
