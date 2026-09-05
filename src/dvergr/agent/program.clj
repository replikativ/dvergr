(ns dvergr.agent.program
  "Run-backed execution of portable AgentDefs.

   Roster construction is pure; `hire!` is the effect boundary that admits a
   durable Run, posts its precise task trigger, and starts a Spindel Spin.
   Workflow results live in the fork-aware execution graph, while Room messages
   and Run lifecycle remain the durable source of truth."
  (:require [dvergr.agent.roster :as roster]
            [dvergr.agent.prompt :as prompt]
            [dvergr.agent.room-context :as room-context]
            [dvergr.agent.run :as run]
            [dvergr.agent.turn :as turn]
            [dvergr.agent.world :as world]
            [dvergr.chat.agent :as chat-agent]
            [dvergr.chat.context :as chat-context]
            [dvergr.discourse :as d]
            [dvergr.model.providers :as providers]
            [dvergr.resource :as resource]
            [dvergr.room.store :as room-store]
            [dvergr.system.rooms :as system-rooms]
            [dvergr.tools :as tools]
            [hasch.core :as hasch]
            [org.replikativ.spindel.core :as sp]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.atom :as ratom]
            [org.replikativ.spindel.spin.combinators :as comb]
            [org.replikativ.spindel.spin.core :as spin-core]
            [org.replikativ.spindel.spin.sync :as sync]
            [taoensso.telemere :as tel])
  (:import [java.nio.charset StandardCharsets]
           [java.util UUID]
           [java.util.concurrent Callable CountDownLatch FutureTask]))

(def run-sink
  "Reserved non-subscribed Room address for private Run inputs and outputs.
   Internal coordination is returned through the Run's result Spin; publishing
   to a Participant is a separate, explicit speech act."
  :_runs)

(def interpreter-version 5)

(def ^:private default-max-model-steps 32)

(declare with-owned-child! cancel!)

(defn- child-program-authority
  "Authority made available to code and tools running inside one paid LLM Run.
   Keep this one value shared by the SCI and native-tool surfaces so a model
   cannot bypass delegation attenuation by choosing a different interface."
  [run-id supervisor]
  {:program-kinds #{:echo :scripted}
   ;; Conserved vectors can now be split recursively, but model calls are not
   ;; yet debited from the Run wallet. Keep paid recursive effects closed until
   ;; provider usage and Kontor receipts form one atomic/effectively-once path.
   :provider-effects? false
   :parent-run run-id
   ;; Process-local structured ownership. The sandbox may construct immutable
   ;; rosters freely, but every admitted child execution is leased to this
   ;; supervisor before `hire!` returns to agent code.
   :own-child! #(with-owned-child! supervisor %)})

(defn- program-result
  ([status value] {::status status ::value value})
  ([status value reason] {::status status ::value value ::reason reason}))

(defn- run-chat-id [run-id]
  (UUID/nameUUIDFromBytes
   (.getBytes (str "dvergr-agent-run|" run-id) StandardCharsets/UTF_8)))

(declare start-worker!)

(defn- make-supervisor
  ([execution-ctx] (make-supervisor execution-ctx execution-ctx))
  ([execution-ctx worker-ctx]
   {:execution-ctx execution-ctx
    :worker-ctx worker-ctx
    :state (atom {:cancelled? false
                  :sealed? false
                  :workers {}
                  :children {}
                  :cleanup nil
                  :cleanup-phase :pending
                  :cleanup-error nil
                  :llm-metrics nil
                  :execution-phase :admitted
                  :quiesced? false})
    :quiesced (sync/deferred)
    :quiesced-latch (CountDownLatch. 1)}))

(defn- update-llm-metrics!
  "Update provider evidence under the same lock as cleanup/quiescence state."
  [supervisor f & args]
  (locking supervisor
    (apply swap! (:state supervisor) update :llm-metrics f args)))

(defn- advance-supervisor!
  "Advance cleanup/quiescence after an atomic supervisor transition. Cleanup
   starts only after every normal worker has acknowledged termination."
  [supervisor]
  (let [action
        (locking supervisor
          (let [{:keys [sealed? workers children cleanup cleanup-phase quiesced?]
                 :as state}
                @(:state supervisor)
                normal-live? (or (seq children)
                                 (some #(= :normal (:kind %)) (vals workers)))]
            (cond
              (and sealed? (not normal-live?) (= :pending cleanup-phase) cleanup)
              (do (swap! (:state supervisor) assoc :cleanup-phase :running)
                  [:cleanup cleanup])

              (and sealed? (not normal-live?) (= :pending cleanup-phase))
              (do (swap! (:state supervisor) assoc :cleanup-phase :done)
                  :advance)

              (and sealed? (= :done cleanup-phase) (empty? workers) (not quiesced?))
              (let [state' (assoc state :quiesced? true)]
                (reset! (:state supervisor) state')
                [:quiesced state'])

              :else nil)))]
    (cond
      (= :advance action)
      (advance-supervisor! supervisor)

      (= :cleanup (first action))
      (start-worker! supervisor (second action) :cleanup)

      (= :quiesced (first action))
      (try
        ;; Deferred publication is context access. The stable latch is released
        ;; only afterwards, so Room drain can treat it as a true quiescence ack.
        (binding [ec/*execution-context* (:execution-ctx supervisor)]
          ((:quiesced supervisor) (second action)))
        (finally
          (.countDown ^CountDownLatch (:quiesced-latch supervisor)))))
    nil))

(defn- worker-finished! [supervisor worker-id kind result]
  (locking supervisor
    (swap! (:state supervisor)
           (fn [state]
             (cond-> (update state :workers dissoc worker-id)
               (= :cleanup kind)
               (assoc :cleanup-phase :done
                      :cleanup-error (when (and (map? result)
                                                (contains? result ::worker-error))
                                       (::worker-error result)))))))
  (advance-supervisor! supervisor))

(defn- start-worker!
  "Register and start blocking/native work under the process-local supervisor.
   Cancellation is sticky: registration and the cancellation check share the
   supervisor lock, closing the cancel-before-start and between-step races."
  ([supervisor f] (start-worker! supervisor f :normal))
  ([supervisor f kind]
   (let [done       (sync/deferred)
         worker-id  (random-uuid)
         phase      (atom :new)
         result     (atom nil)
         callable   (reify Callable
                      (call [_]
                        (when (compare-and-set! phase :new :running)
                          (binding [ec/*execution-context* (:worker-ctx supervisor)
                                    ;; A native worker is an effect boundary,
                                    ;; not another node in the caller's Spin.
                                    ;; In particular trusted world setup must
                                    ;; not inherit the drain/graph identity
                                    ;; which admitted the Run.
                                    ec/*spin-id* nil]
                            (try
                              (reset! result (f))
                              (catch Throwable t
                                (reset! result {::worker-error t}))
                              (finally
                                (reset! phase :terminated)
                               ;; Publish the per-worker result before removing
                               ;; its live lease from the stable supervisor.
                                (binding [ec/*execution-context*
                                          (:execution-ctx supervisor)]
                                  (try
                                    (done @result)
                                    (finally
                                      (worker-finished! supervisor worker-id kind
                                                        @result))))))))))
         task       (proxy [FutureTask] [callable]
                      (done []
                       ;; Cancellation before the executor begins means the
                       ;; Callable's finally cannot acknowledge termination.
                       ;; Win that one state transition here. If it was already
                       ;; running, its finally is the only acknowledgement.
                        (when (and (.isCancelled this)
                                   (compare-and-set! phase :new :terminated))
                          (let [value (or @result ::worker-cancelled)]
                            (binding [ec/*execution-context* (:execution-ctx supervisor)]
                              (try
                                (done value)
                                (finally
                                  (worker-finished! supervisor worker-id kind
                                                    value))))))))
         worker     {:id worker-id :task task :done done :kind kind}
         cancel-now?
         (locking supervisor
           (let [{:keys [sealed? cancelled?]} @(:state supervisor)]
             (when (and sealed? (= :normal kind))
               (throw (ex-info "Cannot start work after supervisor seal"
                               {:type ::supervisor-sealed})))
             (swap! (:state supervisor) assoc-in [:workers worker-id] worker)
             (and cancelled? (= :normal kind))))]
     (if cancel-now?
      ;; Do not even enqueue work when cancellation preceded registration.
       (.cancel task false)
       (try
         (.execute clojure.lang.Agent/soloExecutor task)
         (catch Throwable t
          ;; Make executor rejection flow through the same acknowledgement path.
           (reset! result {::worker-error t})
           (.cancel task false))))
     worker)))

(defn- cancel-supervisor! [supervisor]
  (let [[tasks cancel-children]
        (locking supervisor
          (swap! (:state supervisor) assoc :cancelled? true)
          [(->> (get @(:state supervisor) :workers)
                vals
                (filter #(= :normal (:kind %)))
                (map :task)
                vec)
           (->> (get @(:state supervisor) :children)
                vals
                (map :cancel!)
                vec)])]
    (doseq [^FutureTask task tasks]
      (.cancel task true))
    (doseq [cancel-child! cancel-children]
      (try
        (cancel-child!)
        (catch Throwable t
          (tel/log! {:level :warn :id ::child-cancellation-failed
                     :data {:error (ex-message t)}}
                    "Owned child cancellation failed")))))
  nil)

(defn- register-cleanup! [supervisor cleanup]
  (locking supervisor
    (when-not (= :pending (:cleanup-phase @(:state supervisor)))
      (throw (ex-info "Cleanup registered after supervisor cleanup began"
                      {:type ::late-cleanup-registration})))
    (swap! (:state supervisor) assoc :cleanup cleanup))
  nil)

(defn- seal-supervisor! [supervisor]
  (locking supervisor
    (swap! (:state supervisor) assoc :sealed? true))
  (advance-supervisor! supervisor)
  (:quiesced supervisor))

(defn- worker-result-spin [worker]
  (sp/spin (sp/await (:done worker))))

(defn- worker-error? [value]
  (and (map? value) (contains? value ::worker-error)))

(defn- await-supervisor! [supervisor]
  (.await ^CountDownLatch (:quiesced-latch supervisor))
  nil)

(deftype RunHandle [id room-id owner-fork-id execution completion worker-execution]
  clojure.lang.ILookup
  (valAt [_ k]
    (case k
      :run/id id
      :run/room room-id
      nil))
  (valAt [_ k not-found]
    (case k
      :run/id id
      :run/room room-id
      not-found))

  ;; Blocking is intentionally available only at a REPL/test boundary.
  clojure.lang.IDeref
  (deref [_]
    (let [current-fork-id (:fork-id (ec/current-execution-context))]
      (when-not (= owner-fork-id current-fork-id)
        (throw (ex-info "RunHandle belongs to another Spindel context"
                        {:type ::foreign-execution-context
                         :run/id id
                         :owner-fork-id owner-fork-id
                         :current-fork-id current-fork-id})))
      @execution))
  clojure.lang.IBlockingDeref
  (deref [_ timeout-ms timeout-value]
    (let [current-fork-id (:fork-id (ec/current-execution-context))]
      (when-not (= owner-fork-id current-fork-id)
        (throw (ex-info "RunHandle belongs to another Spindel context"
                        {:type ::foreign-execution-context
                         :run/id id
                         :owner-fork-id owner-fork-id
                         :current-fork-id current-fork-id})))
      (deref execution timeout-ms timeout-value)))

  Object
  (toString [_]
    (str "#<RunHandle " id " room=" room-id ">")))

(defmethod print-method RunHandle [^RunHandle handle ^java.io.Writer writer]
  (.write writer (.toString handle)))

(defn- ensure-owner-context! [^RunHandle handle]
  (let [owner-fork-id (.-owner-fork-id handle)
        current-fork-id (:fork-id (ec/current-execution-context))]
    (when-not (= owner-fork-id current-fork-id)
      (throw (ex-info "RunHandle belongs to another Spindel context"
                      {:type ::foreign-execution-context
                       :run/id (:run/id handle)
                       :owner-fork-id owner-fork-id
                       :current-fork-id current-fork-id})))
    handle))

(defn run-id "The durable Run UUID represented by `handle`." [handle]
  (:run/id handle))

(defn result-spin
  "A passive Spindel observer Spin for `handle`'s completion. Await this inside
   workflow Spins. Each call returns a fresh graph node over a fork-aware
   Deferred; cancelling one observer never cancels the shared execution or a
   peer observer. Use `owned-result-spin` when structured cancellation should
   propagate from a race arm to the hired Run."
  ([^RunHandle handle]
   (ensure-owner-context! handle)
   (let [completion (.-completion handle)]
     (sp/spin (sp/await completion))))
  ([consumer-run-id ^RunHandle handle]
   (let [observer (result-spin handle)
         cause-run-id (run-id handle)]
     (sp/spin
      (let [result (sp/await observer)]
        (run/record-cause! consumer-run-id cause-run-id)
        result)))))

(defn owned-result-spin
  "An owning Spindel observer for a RunHandle. If a combinator cancels this
   observer (for example, as the losing arm of `race`), cancellation propagates
   to the hired Run. Prefer passive `result-spin` for ordinary or shared reads."
  ([^RunHandle handle]
   (ensure-owner-context! handle)
   (let [execution (.-worker-execution handle)
         id (:run/id handle)
         room-id (:run/room handle)
         observer (sp/spin
                   (try
                     (sp/await (.-completion handle))
                     (catch Throwable t
                       (when (= spin-core/spin-cancelled (:type (ex-data t)))
                         (run/cancel-room-run! room-id id))
                       (throw t))))]
     ;; A race can choose another arm before its executor has started this
     ;; observer, so no await-cont exists yet. Record the same fork-local
     ;; ownership edge Spindel's fan-out combinators use for that initial window.
     (spin-core/set-owned-spins! (spin-core/spin-id observer) [execution])
     observer))
  ([consumer-run-id ^RunHandle handle]
   (let [observer (owned-result-spin handle)
         cause-run-id (run-id handle)]
     (sp/spin
      (let [result (sp/await observer)]
        (run/record-cause! consumer-run-id cause-run-id)
        result)))))

(defn- child-finished! [supervisor lease-id]
  (locking supervisor
    (swap! (:state supervisor) update :children dissoc lease-id))
  (advance-supervisor! supervisor))

(defn- with-owned-child!
  "Reserve parent ownership before invoking zero-argument `admit-child!`.

   The reservation closes the cancellation/seal race around durable child
   admission: a parent cannot quiesce while admission is in progress, and a
   cancellation that arrives before the RunHandle exists is replayed against
   it immediately afterward. The lease is released only when admission fails
   or the admitted child reaches its durable terminal state."
  [supervisor admit-child!]
  (let [lease-id (random-uuid)
        cancel-slot (atom nil)
        watch-key (Object.)
        acknowledged? (atom false)
        acknowledge! (fn []
                       (when (compare-and-set! acknowledged? false true)
                         (run/unwatch-runs! watch-key)
                         (child-finished! supervisor lease-id)))]
    (locking supervisor
      (let [{:keys [sealed? cancelled?]} @(:state supervisor)]
        (when (or sealed? cancelled?)
          (throw (ex-info "Cannot hire an owned child after parent cancellation/seal"
                          {:type ::supervisor-sealed})))
        (swap! (:state supervisor) assoc-in [:children lease-id]
               {:cancel! #(when-let [cancel-child! @cancel-slot]
                            (cancel-child!))})))
    (try
      (let [^RunHandle handle (admit-child!)
            child-id (run-id handle)
            owner-ctx (ec/current-execution-context)
            cancel-child! #(binding [ec/*execution-context* owner-ctx]
                             (cancel! handle))]
        (reset! cancel-slot cancel-child!)
        (locking supervisor
          (swap! (:state supervisor) assoc-in [:children lease-id :handle] handle))
        ;; Lifecycle registration and its initial active snapshot share the Run
        ;; registry lock. We therefore cannot miss a child that terminalizes
        ;; during registration, and acknowledgement is independent of either
        ;; execution graph being cancelled.
        (run/watch-runs!
         watch-key
         (fn [{:keys [type runs run]}]
           (when (or (and (= :runs/snapshot type)
                          (not-any? #(= child-id (:run/id %)) runs))
                     (and (= :run/finished type)
                          (= child-id (:run/id run))))
             (acknowledge!))))
        (when (:cancelled? @(:state supervisor))
          (cancel-child!))
        handle)
      (catch Throwable t
        (acknowledge!)
        (when-let [cancel-child! @cancel-slot]
          (try (cancel-child!) (catch Throwable _)))
        (throw t)))))

(defn- cancelled! [run-id]
  (throw (ex-info "Run cancelled" {:type ::cancelled :run/id run-id})))

(defn- cooperative-delay
  "Delay inside Spindel while polling the Run-local cancellation token."
  [run-id delay-ms]
  (sp/spin
   (loop [remaining (long (or delay-ms 0))]
     (when (run/cancel-requested? run-id)
       (cancelled! run-id))
     (when (pos? remaining)
       (let [slice (min remaining 25)]
         (sp/await (comb/sleep slice))
         (recur (- remaining slice)))))))

(defn- execute-deterministic-program
  "Interpret the deterministic program vocabulary. Returns Spin[ProgramResult]."
  [run-id agent task]
  (let [{:keys [kind delay-ms reply result] :as program} (:agent/program agent)]
    (sp/spin
     (sp/await (cooperative-delay run-id delay-ms))
     (when (run/cancel-requested? run-id)
       (cancelled! run-id))
     (case kind
       :scripted (program-result
                  :completed
                  (cond
                    (contains? program :result) result
                    (contains? program :reply) reply
                    :else nil))
       :echo (program-result :completed task)
       (throw (ex-info "No interpreter for AgentDef program kind"
                       {:type ::unknown-program-kind
                        :kind kind
                        :agent/id (:agent/id agent)}))))))

(defn- result-content [value]
  (if (string? value) value (pr-str value)))

(defn- last-assistant-content [chat-ctx]
  (some->> (chat-context/get-messages chat-ctx)
           reverse
           (some (fn [message]
                   (when (= :assistant
                            (or (:message/role message) (:role message)))
                     (or (:message/content message) (:content message)))))))

(defn- resolve-model-spec [agent]
  (providers/ensure-initialized!)
  (or (:agent/model-policy agent)
      (providers/default-spec)
      (throw (ex-info "No LLM provider is available for AgentDef"
                      {:type ::no-provider
                       :agent/id (:agent/id agent)}))))

(defn- new-llm-context
  [control-room work-room run-id agent budget-dollars chat-id supervisor]
  (let [system-id (room-context/room-system-id work-room)
        ;; Model messages, tool results, authorization receipts, and costs are
        ;; control-plane evidence. Keep that trace in the parent even when the
        ;; work plane is later discarded.
        trace-db (some-> control-room :store :conn)
        chat-ctx (turn/new-working-ctx
                  {:execution-ctx (:ctx work-room)
                   :chat-id chat-id
                   :title (str "agent-task " (name (:agent/id agent)))
                   :budget-dollars budget-dollars
                   :db-conn trace-db
                   :kb-conn (when system-id (system-rooms/room-kb-conn system-id))
                   :room-id system-id
                   :room-runtime-id (:id work-room)
                   :room-incarnation (:incarnation work-room)
                   ;; Generic resource vectors split recursively, but paid model
                   ;; usage is not debited yet. Permit only provider-free child
                   ;; programs until that receipt path exists.
                   :agent-program-ceiling (child-program-authority run-id supervisor)})]
    {:chat-ctx chat-ctx
     :owned-db? (nil? trace-db)}))

(defn- llm-tool-context [control-room room chat-ctx agent run-id supervisor]
  (let [system-id (room-context/room-system-id room)
        tool-map (tools/normalize-tools (or (:agent/tools agent) #{}))
        ;; Ordinary room/task effects target the branched work store. A
        ;; store-less Room uses the per-Run ephemeral DB, which is itself owned
        ;; by the work context and closed before settlement.
        work-db (or (some-> room :store :conn)
                    (when-not (some-> control-room :store :conn)
                      (:db-conn chat-ctx)))]
    {:tool-map tool-map
     :tool-ctx
     (-> (tools/make-context
          {:db-conn work-db
           :chat-ctx chat-ctx
           :sci-ctx (chat-context/sci-context-in chat-ctx (:ctx room))
           :tools tool-map
           :isolation :sci
           :execution-ctx (:ctx room)
           :control-room control-room
           ;; Delegation tools are adapters over the same Run interpreter as
           ;; the SCI API. Carry structural parentage and the identical
           ;; attenuation policy across this boundary explicitly.
           :run-id run-id
           :agent-program-ceiling (child-program-authority run-id supervisor)
           :actor (:agent/id agent)
           :model-policy (:agent/model-policy agent)})
         (assoc :workspace-roots
                (when system-id (system-rooms/room-load-roots system-id)))
         (assoc :kb-conn
                (when system-id (system-rooms/room-kb-conn system-id)))
         (assoc :room room))}))

(defn- execute-llm-program
  "Run a bounded Dvergr-native model/tool loop. Each blocking model round-trip
   runs under a supervised worker; this Spin retains orchestration, activity
   correlation, cleanup, and cancellation in the Room's execution graph."
  [control-room work-room run-id chat-id agent task trigger supervisor limits]
  (let [{:keys [max-model-steps budget-dollars auto-compact? compaction-model]
         :or {max-model-steps default-max-model-steps
              budget-dollars 1.0
              auto-compact? true}} (:agent/program agent)
        max-model-steps (min max-model-steps
                             (or (:max-model-steps limits) max-model-steps))
        budget-dollars (min (double budget-dollars)
                            (double (or (:budget-dollars limits)
                                        budget-dollars)))
        effective-limits {:max-model-steps max-model-steps
                          :budget-dollars budget-dollars}]
    (sp/spin
     ;; Context/schema/SCI construction and credential discovery may block.
     ;; Build the whole runtime bundle under the same supervised worker as
     ;; provider calls, before exposing it to the orchestration Spin.
     (let [init-worker
           (start-worker!
            supervisor
            (fn []
              (let [{:keys [chat-ctx owned-db?]}
                    (new-llm-context control-room work-room run-id agent
                                     budget-dollars chat-id supervisor)
                    _ (when owned-db?
                        (register-cleanup!
                         supervisor #(chat-context/close-chat! chat-ctx)))
                    {:keys [tool-map tool-ctx]}
                    (llm-tool-context control-room work-room chat-ctx agent run-id
                                      supervisor)
                    model-spec (resolve-model-spec agent)
                    instructions
                    (prompt/assemble-system-prompt
                     (str "You are a model inside the Dvergr harness. Dvergr owns "
                          "tools and the sandbox. Call only tools explicitly supplied "
                          "in this request; when none are supplied, answer directly. "
                          "Never request shell, grep, file, web, or other native "
                          "Codex tools unless Dvergr explicitly supplied that exact tool."
                          (when-let [agent-prompt (:agent/prompt agent)]
                            (str "\n\n" agent-prompt)))
                     {:tools tool-map :isolation :sci :profile :workflow})]
                (update-llm-metrics!
                 supervisor
                 (constantly
                  (cond->
                   {:prompt-id (hasch/uuid [:dvergr/llm-system-prompt instructions])
                    :provider (:provider model-spec)
                    :model (:model model-spec)
                    :model-steps 0
                    :usage {}}
                    (seq limits) (assoc :limits effective-limits))))
                (chat-context/add-message!
                 chat-ctx {:role :system :content instructions})
                (chat-context/add-message!
                 chat-ctx {:role :user :content (result-content task)})
                {:chat-ctx chat-ctx
                 :tool-map tool-map
                 :tool-ctx tool-ctx
                 :model-spec model-spec})))
           initialized (sp/await (worker-result-spin init-worker))]
       (when (or (= ::worker-cancelled initialized)
                 (run/cancel-requested? run-id))
         (cancelled! run-id))
       (when (worker-error? initialized)
         (throw (ex-info "LLM runtime initialization failed"
                         {:type ::runtime-initialization-failed
                          :agent/id (:agent/id agent)}
                         (::worker-error initialized))))
       (let [{:keys [chat-ctx tool-map tool-ctx model-spec]} initialized
             posted (ratom/create-atom 0)]
         (loop [model-step 0]
           (when (run/cancel-requested? run-id)
             (cancelled! run-id))
           (let [call
                 (start-worker!
                  supervisor
                  (fn []
                    (chat-agent/run-agent-turn!
                     chat-ctx
                     {:provider (:provider model-spec)
                      :model (:model model-spec)
                        ;; The SAME normalized map defines both the model schema
                        ;; and execute-side authority. Empty means no tools.
                      :tools tool-map
                      :tool-ctx tool-ctx
                      :cancel? (turn/cancel?-fn
                                chat-ctx (:ctx work-room)
                                (fn [] (run/cancel-requested? run-id)))
                      :auto-compact? auto-compact?
                      :compaction-model compaction-model
                      ;; The existing single-exchange core still calls this
                      ;; trace field `turn-number`; semantically it is a model
                      ;; integration step, not a conversational turn.
                      :turn-number model-step
                      :run-id run-id})))
                 outcome (sp/await (worker-result-spin call))
                 budget (chat-context/get-budget chat-ctx)]
             (update-llm-metrics!
              supervisor merge {:model-steps (inc model-step)
                                :usage (select-keys budget [:used :by-type])})
             (turn/post-turn-activity! control-room (:agent/id agent) chat-ctx posted
                                       run-id trigger)
             (cond
               (or (= ::worker-cancelled outcome)
                   (run/cancel-requested? run-id))
               (cancelled! run-id)

               (worker-error? outcome)
               (throw (ex-info "LLM model step failed"
                               {:type ::llm-model-step-failed
                                :agent/id (:agent/id agent)
                                :model-step model-step}
                               (::worker-error outcome)))

               (= :cancelled outcome)
               (cancelled! run-id)

               (= :error outcome)
               (throw (ex-info "LLM model step failed"
                               {:type ::llm-model-step-failed
                                :agent/id (:agent/id agent)
                                :model-step model-step}))

               (= :complete outcome)
               (if-let [value (last-assistant-content chat-ctx)]
                 (program-result :completed value)
                 (throw (ex-info "LLM program completed without an assistant result"
                                 {:type ::missing-llm-result
                                  :agent/id (:agent/id agent)})))

               (not= :continue outcome)
               (throw (ex-info "Unknown LLM model-step outcome"
                               {:type ::unknown-model-step-outcome
                                :outcome outcome
                                :model-step model-step}))

               (chat-context/budget-exceeded? chat-ctx)
               (program-result :waiting nil :budget-exhausted)

               (>= (inc model-step) max-model-steps)
               (throw (ex-info "LLM program exceeded its model-step bound"
                               {:type ::model-step-limit-exceeded
                                :agent/id (:agent/id agent)
                                :max-model-steps max-model-steps}))

               :else
               (recur (inc model-step))))))))))

(defn- execute-program
  [control-room work-room run-id chat-id agent task trigger supervisor]
  (case (get-in agent [:agent/program :kind])
    :llm (execute-llm-program control-room work-room run-id chat-id agent task
                              trigger supervisor (::limits agent))
    (execute-deterministic-program run-id agent task)))

(def ^:private hire-option-keys
  #{:task :from :parent-run :settlement :resources :limits})

(defn- validate-program!
  [{:keys [kind delay-ms max-model-steps budget-dollars auto-compact? compaction-model]
    :as program} agent-ref agent]
  (let [allowed (case kind
                  :scripted #{:kind :delay-ms :reply :result}
                  :echo #{:kind :delay-ms}
                  :llm #{:kind :max-model-steps :budget-dollars
                         :auto-compact? :compaction-model}
                  nil)]
    (when-not allowed
      (throw (ex-info "hire! does not have an interpreter for this program"
                      {:type ::unsupported-program
                       :agent-ref agent-ref
                       :kind kind})))
    (when-let [unknown (seq (remove allowed (keys program)))]
      (throw (ex-info "Unknown keys for AgentDef program kind"
                      {:type ::unknown-program-options
                       :agent-ref agent-ref
                       :kind kind
                       :unknown (set unknown)
                       :allowed allowed})))
    (when (and delay-ms
               (not (and (integer? delay-ms) (<= 0 delay-ms 600000))))
      (throw (ex-info "Program :delay-ms must be an integer from 0 to 600000"
                      {:type ::invalid-delay :delay-ms delay-ms})))
    (when (and (= :llm kind)
               (not (and (integer? (or max-model-steps default-max-model-steps))
                         (<= 1 (or max-model-steps default-max-model-steps) 256))))
      (throw (ex-info "LLM program :max-model-steps must be an integer from 1 to 256"
                      {:type ::invalid-max-model-steps
                       :max-model-steps max-model-steps})))
    (when (and (= :llm kind)
               (not (and (number? (or budget-dollars 1.0))
                         (Double/isFinite (double (or budget-dollars 1.0)))
                         (pos? (double (or budget-dollars 1.0))))))
      (throw (ex-info "LLM program :budget-dollars must be positive"
                      {:type ::invalid-budget :budget-dollars budget-dollars})))
    (when (and (= :llm kind) (some? auto-compact?) (not (boolean? auto-compact?)))
      (throw (ex-info "LLM program :auto-compact? must be boolean"
                      {:type ::invalid-auto-compact :auto-compact? auto-compact?})))
    (when (and (= :llm kind) compaction-model (not (string? compaction-model)))
      (throw (ex-info "LLM program :compaction-model must be a string"
                      {:type ::invalid-compaction-model :compaction-model compaction-model})))
    (when (= :llm kind)
      (let [policy (:agent/model-policy agent)]
        (when (and policy
                   (not (and (= #{:provider :model} (set (keys policy)))
                             (keyword? (:provider policy))
                             (string? (:model policy))
                             (seq (:model policy)))))
          (throw (ex-info "LLM AgentDef :model-policy must be {:provider keyword :model string}"
                          {:type ::invalid-model-policy
                           :agent-ref agent-ref
                           :model-policy policy})))))
    program))

(defn- validate-hire!
  [roster agent-ref {:keys [task from parent-run resources limits] :as opts}]
  (when-let [unknown (seq (remove hire-option-keys (keys opts)))]
    (throw (ex-info "Unknown hire! options"
                    {:type ::unknown-hire-options
                     :unknown (set unknown)
                     :allowed hire-option-keys})))
  (when-not (contains? opts :task)
    (throw (ex-info "hire! requires :task"
                    {:type ::missing-task :agent-ref agent-ref})))
  (when-not (keyword? from)
    (throw (ex-info "hire! :from must be a keyword"
                    {:type ::invalid-hirer :from from})))
  (when (and parent-run (not (uuid? parent-run)))
    (throw (ex-info "hire! :parent-run must be a UUID"
                    {:type ::invalid-parent-run :parent-run parent-run})))
  (when (and (some? resources)
             (not (and (map? resources)
                       (seq resources)
                       (every? (fn [[coordinate amount]]
                                 (and (or (string? coordinate)
                                          (keyword? coordinate))
                                      (number? amount)
                                      (pos? amount)))
                               resources))))
    (throw (ex-info "hire! :resources must be a non-empty positive resource vector"
                    {:type ::invalid-resources :resources resources})))
  (when-not (or (nil? limits) (map? limits))
    (throw (ex-info "hire! :limits must be a map"
                    {:type ::invalid-limits :limits limits})))
  (when-let [unknown (seq (remove #{:max-model-steps :budget-dollars}
                                  (keys limits)))]
    (throw (ex-info "hire! :limits contains unknown keys"
                    {:type ::unknown-limit-options
                     :unknown (set unknown)
                     :allowed #{:max-model-steps :budget-dollars}})))
  (when-let [value (:max-model-steps limits)]
    (when-not (and (integer? value) (<= 1 value 256))
      (throw (ex-info "hire! limit :max-model-steps must be an integer from 1 to 256"
                      {:type ::invalid-max-model-steps :max-model-steps value}))))
  (when-let [value (:budget-dollars limits)]
    (when-not (and (number? value)
                   (Double/isFinite (double value))
                   (pos? (double value)))
      (throw (ex-info "hire! limit :budget-dollars must be positive"
                      {:type ::invalid-budget :budget-dollars value}))))
  (when-not (roster/data-value? task)
    (throw (ex-info "Task must be portable data"
                    {:type ::non-portable-task :task task})))
  (let [agent (or (roster/agent roster agent-ref)
                  (throw (ex-info "Unknown AgentRef"
                                  {:type ::unknown-agent :agent-ref agent-ref})))]
    (validate-program! (:agent/program agent) agent-ref agent)
    (when (and (seq limits) (not= :llm (get-in agent [:agent/program :kind])))
      (throw (ex-info "hire! model limits require an LLM AgentDef"
                      {:type ::limits-require-llm
                       :agent-ref agent-ref
                       :limits limits})))
    agent))

(defn- cancelled-error? [t run-id]
  (or (loop [error t]
        (when error
          (or (= ::cancelled (:type (ex-data error)))
              (= spin-core/spin-cancelled (:type (ex-data error)))
              ;; Some Spindel graph boundaries preserve the cancellation
              ;; message while wrapping its ex-data. Follow the cause chain so
              ;; a structured race cannot be misclassified as program failure.
              (= "Spin cancelled" (ex-message error))
              (recur (ex-cause error)))))
      (run/cancel-requested? run-id)))

(defn- graph-cancelled-error? [t]
  (loop [error t]
    (when error
      (or (= spin-core/spin-cancelled (:type (ex-data error)))
          (= "Spin cancelled" (ex-message error))
          (recur (ex-cause error))))))

(defn- publish-result-and-release-spin
  "Retry terminal persistence without losing the computed result. The Run keeps
   its live execution lease and its completion remains unresolved until the
   durable projection succeeds. This makes a transient store outage visible as
   a still-active Run and gives it a real recovery path instead of silently
   leaving a durable `:running` projection behind."
  [id completion result finish-opts]
  (sp/spin
   (loop []
     (let [retained (try
                      (run/retain-finished! id (:run/status result) finish-opts)
                      (catch Throwable t t))]
       (cond
         (instance? Throwable retained)
         (do
           ;; Store calls are normally immediate. Only the backoff is async, so
           ;; an unavailable durable store never parks Spindel's drain thread.
           (sp/await (comb/sleep 25))
           (recur))

         retained
         (run/publish-finished! id completion result)

         ;; Another exactly-once settlement path already retained/released it.
         :else nil)))
   result))

(defn- publish-result-and-release!
  "Blocking counterpart for the process-local settlement watcher.

   This must not construct a Spin: it runs specifically after its owning graph
   was cancelled, and re-entering the same execution context through another
   graph node can inherit/reuse cancellation bookkeeping. The watcher already
   runs off the drain thread, so a short blocking durability backoff is safe."
  [id completion result finish-opts]
  (loop []
    (let [retained (try
                     (run/retain-finished! id (:run/status result) finish-opts)
                     (catch Throwable t t))]
      (cond
        (instance? Throwable retained)
        (do
          (Thread/sleep 25)
          (recur))

        retained
        (run/publish-finished! id completion result)

        :else nil)))
  result)

(defn- settlement-result [run-world result]
  (let [{:keys [status reason]}
        (try
          (world/settle! run-world (:run/status result))
          (catch Throwable t
            (tel/log! {:level :error :id ::world-settlement-failed
                       :data {:run/id (:run/id result)
                              :run/world (:id run-world)
                              :error (ex-message t)}}
                      "Run world settlement failed; retaining it for review")
            {:status :review :reason :settlement-failed}))]
    {:result (assoc result
                    :run/world (:id run-world)
                    :run/settlement-status status
                    :run/settlement-reason reason)
     :finish-opts (cond-> {:settlement-status status}
                    reason (assoc :settlement-reason reason))}))

(defn- return-unused-resources!
  "Return a Run's remaining conserved vector after its owned work quiesces.

   The transfer id is stable, so a retry after an uncertain commit is
   idempotent. A configured resource allocation is never silently abandoned:
   transient store failures retain the live Run and retry behind the same
   physical-quiescence fence as durable terminal persistence."
  [room id parent-run allocation-state]
  (let [allocated?
        (loop []
          (case @allocation-state
            (:not-requested :not-started) false
            :allocated true
            ;; The allocating worker exited through an exception. Reconcile its
            ;; stable transfer identity after physical quiescence: a receipt is
            ;; authoritative evidence that the wallet/grant committed, while an
            ;; authoritative nil proves there is nothing to return.
            (:attempting :uncertain)
            (let [outcome
                  (try
                    (if (satisfies? room-store/PResourceStore (:store room))
                      (boolean
                       (room-store/-resource-receipt
                        (:store room) (resource/allocation-id id)))
                      false)
                    (catch Throwable t t))]
              (if (instance? Throwable outcome)
                (do (Thread/sleep 25) (recur))
                outcome))))]
    (when allocated?
      (loop []
        (let [outcome (try
                        (let [remaining (resource/run-balance room id)]
                          (when (seq remaining)
                            (resource/return! room id parent-run remaining))
                          :returned)
                        (catch Throwable t t))]
          (when (instance? Throwable outcome)
            (Thread/sleep 25)
            (recur)))))))

(defn- finalize-execution-external!
  "Finalize from a process-local watcher only after the orchestration Spin has a
   cached terminal result and the stable supervisor reports every native worker
   and owned cleanup quiescent. World settlement happens behind the same
   physical-quiescence fence."
  [room control-room run-world id parent-run allocation-state supervisor execution completion outcome]
  (future
    ;; `future` conveys dynamic bindings. A cancellation hook normally launches
    ;; this watcher from the losing observer Spin, so retaining its *spin-id*
    ;; would make the supposedly external durability path a child of the very
    ;; graph being reaped. Keep the Room memory context but detach graph identity.
    (binding [ec/*execution-context* (:ctx room)
              ec/*spin-id* nil]
      ;; Wait until the executor has published either its ordinary outcome or
      ;; its graph-level cancellation/failure through the process-local bridge.
      (let [outcome @outcome]
        ;; Keep the durable cancellation token/live lease until the executor has
      ;; acknowledged termination. Direct cancellation relies on that token at
      ;; its next cooperative checkpoint; releasing it merely because a pure
      ;; program has no native workers would let the body continue to effects.
        (let [execution-id (spin-core/spin-id execution)]
          (loop []
            (when-not (ec/spin-current-result execution-id)
              (Thread/sleep 5)
              (recur))))
        (await-supervisor! supervisor)
        (return-unused-resources! control-room id parent-run allocation-state)
        (let [{:keys [cleanup-error llm-metrics]} @(:state supervisor)
              result (cond-> (if cleanup-error
                               {:run/id id :run/status :failed
                                :run/error (ex-message cleanup-error)}
                               (:result outcome))
                       llm-metrics (assoc :run/metrics llm-metrics))
              execution-opts (merge
                              (:finish-opts outcome)
                              (when cleanup-error
                                {:reason :cleanup-error :error cleanup-error}))
              {:keys [result finish-opts]} (settlement-result run-world result)]
          (publish-result-and-release! id completion result
                                       (merge execution-opts finish-opts)))))))

(defn- execution-spin
  [control-room work-room agent task trigger id chat-id supervisor limits outcome-promise]
  (sp/spin
   (let [outcome
         (try
           (let [{status ::status value ::value reason ::reason}
                 (sp/await
                  (execute-program control-room work-room id chat-id
                                   (cond-> agent
                                     (seq limits) (assoc ::limits limits))
                                   task trigger supervisor))
                 ;; Seal admits no further provider work, runs owned cleanup
                 ;; after all workers terminate, and publishes one stable
                 ;; quiescence acknowledgement.
                 _ (sp/await (seal-supervisor! supervisor))
                 cleanup-error (:cleanup-error @(:state supervisor))]
             (when cleanup-error
               (throw (ex-info "Agent program cleanup failed"
                               {:type ::cleanup-failed}
                               cleanup-error)))
             (when (run/cancel-requested? id)
               (cancelled! id))
             (let [llm-metrics (:llm-metrics @(:state supervisor))]
               (case status
                 :completed
                 (let [output (d/reply (:agent/id agent) run-sink
                                       (result-content value) trigger
                                       {:role :assistant :run-id id})]
                 ;; Room posting is durability-first. Completion is acknowledged
                 ;; only after the correlated private output exists.
                   (d/post! control-room output)
                   {:result (cond-> {:run/id id
                                     :run/status :completed
                                     :run/value value
                                     :run/output output}
                              llm-metrics (assoc :run/metrics llm-metrics))
                    :finish-opts {}})

                 :waiting
                 {:result (cond-> {:run/id id :run/status :waiting :run/reason reason}
                            llm-metrics (assoc :run/metrics llm-metrics))
                  :finish-opts {:reason reason}}

                 (throw (ex-info "Interpreter returned an unknown program status"
                                 {:type ::unknown-program-status
                                  :status status
                                  :agent/id (:agent/id agent)})))))
           (catch Throwable t
             (let [cancelled? (cancelled-error? t id)
                   graph-cancelled? (graph-cancelled-error? t)
                   quiesced (seal-supervisor! supervisor)]
               (if graph-cancelled?
                 ;; Cancellation is sticky on a Spin. Awaiting `quiesced` from
                 ;; this catch would immediately throw cancellation again,
                 ;; before the body reaches its local reject callback. Under a
                 ;; nested race that can strand the durable Run at
                 ;; :cancelling even though the native worker already stopped.
                 ;; Reject without another breakpoint; the spawn callback below
                 ;; waits on the stable supervisor from outside the cancelled
                 ;; graph, then settles the Run durability-first.
                 (throw t)
                 (do
                   ;; Ordinary failures still own a live, non-cancelled Spin and
                   ;; can await cleanup compositionally. A direct Run
                   ;; cancellation is in this branch too: its execution graph is
                   ;; still valid, so it returns an ordinary cancelled result.
                   (sp/await quiesced)
                   (let [cleanup-error (:cleanup-error @(:state supervisor))
                         error (or cleanup-error t)
                         llm-metrics (:llm-metrics @(:state supervisor))]
                     (if (and cancelled? (nil? cleanup-error))
                       {:result (cond-> {:run/id id :run/status :cancelled}
                                  llm-metrics (assoc :run/metrics llm-metrics))
                        :finish-opts {:reason :cancel-requested}}
                       {:result (cond-> {:run/id id
                                         :run/status :failed
                                         :run/error (ex-message error)}
                                  llm-metrics (assoc :run/metrics llm-metrics))
                        :finish-opts {:reason (if cleanup-error
                                                :cleanup-error
                                                :program-error)
                                      :error error}})))))))
         result (:result outcome)]
     ;; Settlement must not merge/discard a context from inside the drain graph
     ;; currently using it. Bridge the computed outcome to the process-local
     ;; quiescence watcher; the public result still arrives through the
     ;; context-owned Deferred after durable settlement.
     (deliver outcome-promise outcome)
     result)))

(defn- prepared-execution-spin
  "Run trusted fork-local preparation off the drain before candidate effects.

   The setup worker is owned by the same supervisor as model/native work, so
   targeted cancellation interrupts it and physical quiescence waits for its
   actual exit. Resource allocation and candidate execution remain behind the
   successful setup gate; the private causal trigger is already durable."
  [control-room work-room agent task trigger id chat-id parent-run resources
   supervisor allocation-state limits outcome-promise prepare-world!]
  (sp/spin
   (let [_ (swap! (:state supervisor) assoc :execution-phase :world-setup)
         worker (start-worker! supervisor
                               #(prepare-world! {:room work-room :run/id id}))
         prepared (sp/await (worker-result-spin worker))]
     (cond
       (run/cancel-requested? id)
       (throw (ex-info "Run cancelled during world setup"
                       {:type ::world-setup-cancelled :run/id id}))

       (worker-error? prepared)
       (let [cause (::worker-error prepared)]
         (throw (ex-info (str "Run world setup failed: " (ex-message cause))
                         {:type ::world-setup-failed :run/id id}
                         cause))))
     ;; Durable resource admission can block on Datahike. Keep it off the
     ;; execution-context drain and under the same cancellation/quiescence fence
     ;; as setup and provider work.
     (when (seq resources)
       (swap! (:state supervisor) assoc :execution-phase :resource-allocation)
       (let [allocation-worker
             (start-worker!
              supervisor
              (fn []
                (reset! allocation-state :attempting)
                (try
                  (let [allocation
                        (resource/allocate-run! control-room id parent-run resources)]
                    (reset! allocation-state :allocated)
                    allocation)
                  (catch Throwable t
                    (reset! allocation-state :uncertain)
                    (throw t)))))
             allocation (sp/await (worker-result-spin allocation-worker))]
         (when (or (= ::worker-cancelled allocation)
                   (run/cancel-requested? id))
           (throw (ex-info "Run cancelled during resource allocation"
                           {:type ::world-setup-cancelled :run/id id})))
         (when (worker-error? allocation)
           (throw (ex-info "Run resource allocation failed"
                           {:type ::resource-allocation-failed :run/id id}
                           (::worker-error allocation))))))
     (when (run/cancel-requested? id)
       (throw (ex-info "Run cancelled after world setup"
                       {:type ::world-setup-cancelled :run/id id})))
     (swap! (:state supervisor) assoc :execution-phase :candidate)
     (sp/await
      (execution-spin control-room work-room agent task trigger id chat-id
                      supervisor limits outcome-promise)))))

(defn- execution-failure-reason [error]
  (loop [error error]
    (if error
      (case (:type (ex-data error))
        ::world-setup-failed :world-setup-failed
        ::world-setup-cancelled :world-setup-cancelled
        ::resource-allocation-failed :resource-allocation-failed
        ::trigger-emission-failed :trigger-emission-failed
        (recur (ex-cause error)))
      :execution-error)))

(defn ^:no-doc hire-prepared-in!
  "Host-only Run admission with a trusted fork-local world preparer.

   `control-room` owns durable Run/message facts. `world-parent` is the
   immediate Spindel/Yggdrasil world that the child forks and later settles
   into. They are identical for a top-level hire and intentionally differ for
   recursive hires inside an already-isolated Run world.

   The preparer runs against the isolated work Room after durable admission and
   before resource allocation or candidate-visible work. The private trigger
   is already durable at that point so a setup failure remains causally valid.
   This entry point is deliberately absent from the SCI surface."
  [control-room world-parent roster agent-ref
   {:keys [task from parent-run settlement resources limits]
    :or {from :repl settlement :automatic}
    :as raw-opts}
   prepare-world!]
  (when-not (or (nil? prepare-world!) (fn? prepare-world!))
    (throw (ex-info "Run world preparer must be a function"
                    {:type ::invalid-world-preparer})))
  (let [opts      (assoc raw-opts :from from)
        agent     (validate-hire! roster agent-ref opts)
        actor     (:agent/id agent)
        id        (random-uuid)
        chat-id   (run-chat-id id)
        run-world (world/open! world-parent id settlement)
        work-room (:work run-world)
        supervisor (make-supervisor (:ctx world-parent) (:ctx work-room))
        allocation-state
        (atom (if (seq resources) :not-started :not-requested))
        ;; Private Run facts are still Room messages, but never addressed to an
        ;; installed Participant: direct interpretation and participant routing
        ;; must not execute the same task twice.
        trigger   (d/message from run-sink (result-content task) nil {:role :user})
        provenance (cond-> {:run/agent-version (:agent/version agent)
                            :run/program-kind (get-in agent [:agent/program :kind])
                            :run/interpreter-version interpreter-version
                            :run/agent-def-hash (hasch/uuid agent)
                            :run/world (:id run-world)
                            :run/isolation :ctx
                            :run/settlement-policy settlement
                            :run/settlement-status :open}
                     (= :llm (get-in agent [:agent/program :kind]))
                     (assoc :run/chat-id chat-id)
                     (:roster/id roster) (assoc :run/roster (:roster/id roster)))]
    ;; Reserve durable Run ownership before any trigger effect. Teardown either
    ;; fences us out here or includes this Run in its fixed drain set; it can
    ;; never miss an orphan trigger between posting and admission.
    (try
      (run/start! control-room actor trigger nil
                  (cond-> {:id id :kind :agent-task :provenance provenance}
                    parent-run (assoc :parent parent-run)))
      (catch Throwable t
        (d/discard work-room)
        (throw t)))
    (run/register-cancel-hook! id ::native-worker
                               #(cancel-supervisor! supervisor))
    ;; The trigger is the Run's durable causal input, not candidate execution.
    ;; Persist it for every admitted Run—including setup failures—before any
    ;; asynchronous phase starts. The private run sink prevents participant
    ;; routing, while the exact message remains available to thread/audit
    ;; projections.
    (try
      (d/post! control-room trigger)
      (catch Throwable t
        (let [{:keys [result finish-opts]}
              (settlement-result
               run-world
               {:run/id id :run/status :failed
                :run/error (ex-message t)})]
          (run/finish! id (:run/status result)
                       (merge {:reason :trigger-emission-failed :error t}
                              finish-opts)))
        (throw t)))
    ;; The ordinary path retains its fail-fast admission semantics. Trusted
    ;; setup runs through `prepared-execution-spin` instead so setup, allocation,
    ;; and candidate execution are all behind one timed/cancellable gate.
    (when-not prepare-world!
      (when (seq resources) (reset! allocation-state :attempting))
      (try
        (resource/allocate-run! control-room id parent-run resources)
        (when (seq resources) (reset! allocation-state :allocated))
        (catch Throwable t
          ;; The durable transfer may have committed even when its caller saw an
          ;; exception. Reconcile its stable receipt and return any authority
          ;; before publishing the failed Run; hire! retains its synchronous
          ;; admission-failure contract without leaking a committed grant.
          (when (seq resources) (reset! allocation-state :uncertain))
          (return-unused-resources! control-room id parent-run allocation-state)
          (let [{:keys [status reason]} (world/settle! run-world :failed)]
            (run/finish! id :failed {:reason :resource-allocation-failed
                                     :error t
                                     :settlement-status status
                                     :settlement-reason reason}))
          (throw t))))
    (try
      (let [completion (sync/deferred)
            outcome-promise (promise)
            worker-execution
            (if prepare-world!
              (prepared-execution-spin control-room work-room agent task trigger
                                       id chat-id parent-run resources supervisor
                                       allocation-state limits outcome-promise
                                       prepare-world!)
              (execution-spin control-room work-room agent task trigger id chat-id
                              supervisor limits outcome-promise))
            execution (sp/spin (sp/await completion))
            owner-fork-id (:fork-id (ec/current-execution-context))
            handle    (RunHandle. id (:id control-room) owner-fork-id execution completion
                                  worker-execution)
            cancel! (fn []
                      (cancel-supervisor! supervisor)
                      ;; Cancellation closes admission to further native work.
                      ;; Cleanup may still be registered by an already-running
                      ;; initialization worker while the phase is :pending; the
                      ;; supervisor starts it after that worker terminates.
                      ;; The already-running process-local watcher settles only
                      ;; after the execution and supervisor quiescence fences.
                      (seal-supervisor! supervisor))]
        ;; Replace the early sticky worker hook with the complete cancellation
        ;; boundary. register-cancel-hook! immediately invokes it if cancellation
        ;; won between durable admission and construction of this execution.
        (run/register-cancel-hook! id ::native-worker cancel!)
        (sp/spawn!
         worker-execution
         {:on-error
          (fn [t]
            ;; Graph-level cancellation is settled outside the cancelled Spin:
            ;; this callback may safely block on stable supervisor quiescence,
            ;; while the cancelled body may not cross another await breakpoint.
            ;; finish! is idempotent if another terminal path already won.
            (let [cancelled? (cancelled-error? t id)
                  setup-cancelled?
                  (and cancelled?
                       prepare-world!
                       (not= :candidate
                             (:execution-phase @(:state supervisor))))
                  failure-reason (if setup-cancelled?
                                   :world-setup-cancelled
                                   (execution-failure-reason t))
                  result (if cancelled?
                           {:run/id id :run/status :cancelled}
                           {:run/id id :run/status :failed
                            :run/error (ex-message t)})]
              (when (= :cancelled (:run/status result))
                (run/cancel-room-run! (:id control-room) id))
              (cancel-supervisor! supervisor)
              (seal-supervisor! supervisor)
              (deliver outcome-promise
                       {:result result
                        :finish-opts
                        (if (= :cancelled (:run/status result))
                          {:reason (if (= :world-setup-cancelled failure-reason)
                                     failure-reason
                                     :structured-cancellation)}
                          {:reason failure-reason
                           :error t})})))})
        (finalize-execution-external! world-parent control-room run-world id parent-run
                                      allocation-state supervisor worker-execution
                                      completion outcome-promise)
        handle)
      (catch Throwable t
        (return-unused-resources! control-room id parent-run allocation-state)
        (let [{:keys [status reason]} (world/settle! run-world :failed)]
          (run/finish! id :failed {:reason :spawn-failed :error t
                                   :settlement-status status
                                   :settlement-reason reason}))
        (throw t)))))

(defn hire-in!
  "Start one AgentDef execution with separate control and work parents.

   `control-room` owns durable Run/message facts. `world-parent` is the
   immediate Spindel/Yggdrasil world that the child forks and later settles
   into. They are identical for a top-level hire and intentionally differ for
   recursive hires inside an already-isolated Run world.

   `agent-ref` is a keyword id or versioned ref resolved against immutable
   `roster`. Options:

   - `:task`       portable task value (required)
   - `:from`       triggering actor, default `:repl`
   - `:parent-run` explicit structural parent Run UUID
   - `:settlement` `:automatic` (default), `:review`, `:discard`, or host-owned `:deferred`
   - `:resources`  positive conserved vector split from the parent Run/Room
   - `:limits`     restrictive LLM `:max-model-steps` / `:budget-dollars`
   Built-in program kinds are deterministic `:scripted` / `:echo` and the
   bounded Dvergr-native `:llm` model/tool loop. Simulation and replay
   interpreters implement the same boundary."
  [control-room world-parent roster agent-ref opts]
  (hire-prepared-in! control-room world-parent roster agent-ref opts nil))

(defn hire!
  "Start a top-level AgentDef execution in `room` and return an opaque
   RunHandle. Recursive runtimes use `hire-in!` so durable control facts remain
   in the root Room while the child world forks its immediate parent."
  [room roster agent-ref opts]
  (hire-in! room room roster agent-ref opts))

(defn observe
  "Read the durable Run projection for `handle` or UUID."
  [room handle-or-id]
  (run/run room (if (uuid? handle-or-id) handle-or-id (run-id handle-or-id))))

(defn cancel!
  "Request room-scoped cancellation. A handle carries its Room capability;
   cancelling an arbitrary UUID requires the explicit Room arity."
  ([handle]
   (when (uuid? handle)
     (throw (ex-info "Cancelling a Run UUID requires an explicit Room"
                     {:type ::room-required :run/id handle})))
   (ensure-owner-context! handle)
   (run/cancel-room-run! (:run/room handle) (run-id handle)))
  ([room handle-or-id]
   (when-not (uuid? handle-or-id)
     (ensure-owner-context! handle-or-id))
   (run/cancel-room-run! (:id room)
                         (if (uuid? handle-or-id)
                           handle-or-id
                           (run-id handle-or-id)))))
