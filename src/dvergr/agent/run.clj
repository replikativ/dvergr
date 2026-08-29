(ns dvergr.agent.run
  "Minimal durable Run lifecycle for causally bounded room execution.

   A Run is durable identity and correlation data. Live execution and
  cancellation handles remain private in this namespace and are never returned
   by snapshots, lifecycle events, or the Room store."
  (:require [dvergr.chat.context :as chat-ctx]
            [dvergr.room.store :as store]
            [org.replikativ.spindel.engine.core :as ec]
            [taoensso.telemere :as tel]))

(def ^:private finish-statuses #{:waiting :completed :failed :cancelled})
(defonce ^:private active (atom {}))
(defonce ^:private subscribers (atom {}))
(def ^:private lifecycle-lock (Object.))

(def provenance-keys
  "Run fields an interpreter may add without changing core causal identity."
  #{:run/roster :run/agent-version :run/program-kind
    :run/interpreter-version :run/agent-def-hash :run/chat-id
    :run/world :run/isolation :run/settlement-policy
    :run/settlement-status :run/settlement-reason})

(defn- store-room-id [room]
  (or (some-> room :meta deref :conversation-id)
      (:id room)))

(defn- public-entry [entry]
  (:run entry))

(defn- ordered-runs [entries]
  (->> entries
       (map public-entry)
       (sort-by (juxt #(some-> ^java.util.Date (:run/started-at %) .getTime)
                      #(str (:run/id %))))
       vec))

(defn active-runs
  "Return serializable snapshots of live Runs, never their ChatContexts or
   cancellation handles. With `room-id`, restrict to that runtime Room."
  ([] (ordered-runs (vals @active)))
  ([room-id]
   (ordered-runs (filter #(= room-id (get-in % [:run :run/room]))
                         (vals @active)))))

(defn- notify! [event]
  ;; Called while lifecycle-lock is held. Serializing snapshot registration and
  ;; transitions makes the initial snapshot a real subscription frontier.
  (doseq [[_ f] @subscribers]
    (try
      (f event)
      (catch Throwable t
        (tel/log! {:level :warn :id ::subscriber-failed
                   :data {:error (.getMessage t)}}
                  "Run lifecycle subscriber failed")))))

(defn watch-runs!
  "Subscribe `f` to Run lifecycle events. Registration first emits
   `{:type :runs/snapshot :runs [...]}`, then ordered `:run/started`,
   `:run/cancel-requested`, and `:run/finished` events. `key` is idempotent."
  [key f]
  (locking lifecycle-lock
    (swap! subscribers assoc key f)
    (try
      (f {:type :runs/snapshot :runs (active-runs)})
      (catch Throwable t
        (tel/log! {:level :warn :id ::subscriber-failed
                   :data {:error (.getMessage t)}}
                  "Run lifecycle subscriber failed during initial snapshot"))))
  key)

(defn unwatch-runs! [key]
  (locking lifecycle-lock
    (swap! subscribers dissoc key))
  nil)

(defn- admission-path [room-id]
  [:dvergr/run-admissions room-id])

(defn open-room-admission!
  "Open Run admission for a newly constructed Room in its Spindel context."
  [room-id execution-ctx]
  (locking lifecycle-lock
    (binding [ec/*execution-context* execution-ctx]
      (ec/swap-state! (admission-path room-id) (constantly :open))))
  nil)

(defn close-room-admission!
  "Atomically close fork-local Run admission for `room` and return the fixed set
   admitted before the fence. Teardown drains exactly this set."
  [room]
  (let [room-id (:id room)]
    (locking lifecycle-lock
      (binding [ec/*execution-context* (:ctx room)]
        (ec/swap-state! (admission-path room-id) (constantly :closed)))
      (->> (vals @active)
           (keep (fn [entry]
                   (when (= room-id (get-in entry [:run :run/room]))
                     (get-in entry [:run :run/id]))))
           set))))

(defn start!
  "Persist and publish one live Run before execution begins.

   `trigger` is the precise triggering message (or its UUID). `:parent` is
   optional explicit structural containment (the Run that spawned this Run),
   never inferred from causal message succession. Returns the public Run map."
  ([room actor trigger live-chat-ctx]
   (start! room actor trigger live-chat-ctx {}))
  ([room actor trigger live-chat-ctx {:keys [id kind parent now provenance]
                                      :or {kind :agent-turn}}]
   (when-let [unknown (seq (remove provenance-keys (keys provenance)))]
     (throw (ex-info "Run provenance may not override causal identity"
                     {:type ::invalid-provenance
                      :unknown (set unknown)
                      :allowed provenance-keys})))
   (let [now        (or now (java.util.Date.))
         trigger-id (if (map? trigger) (:id trigger) trigger)
         run        (store/validate-run!
                     (merge
                      (cond-> {:run/id         (or id (random-uuid))
                               :run/kind       kind
                               :run/room       (:id room)
                               :run/actor      actor
                               :run/trigger    trigger-id
                               :run/status     :running
                               :run/created-at now
                               :run/started-at now
                               :run/updated-at now}
                        parent (assoc :run/parent parent))
                      provenance))
         entry      {:run run
                     :chat-ctx live-chat-ctx
                     ;; Explicitly process-local control state. Durable meaning
                     ;; is the Room-store Run projection; this live owner map
                     ;; and its token are never copied or restored as workflow
                     ;; state.
                     :cancelled? (atom false)
                     ;; Process-local callbacks let an interpreter abort native
                     ;; workers as soon as cancellation is requested. They are
                     ;; control capabilities, never durable Run state.
                     :cancel-hooks {}
                     :store (:store room)
                     :store-room-id (store-room-id room)}]
     (locking lifecycle-lock
       (when (and (:ctx room)
                  (= :closed
                     (binding [ec/*execution-context* (:ctx room)]
                       (ec/get-state (admission-path (:id room))))))
         (throw (ex-info "Run admission is closed for Room teardown"
                         {:type ::room-admission-closed
                          :run/id (:run/id run)
                          :run/room (:id room)})))
       ;; Persistence is inside the same admission critical section as the
       ;; fence check: teardown can see either no Run or the fully admitted Run,
       ;; never a durable-but-unowned half-admission.
       (when-let [room-store (:store entry)]
         (when-not (store/-store-run! room-store (:store-room-id entry) run)
           (throw (ex-info "Run admission failed: start was not durable"
                           {:type ::start-not-durable
                            :run/id (:run/id run)
                            :run/room (:id room)}))))
       (swap! active assoc (:run/id run) entry)
       (notify! {:type :run/started :run run}))
     run)))

(defn finish!
  "End the live execution and persist its final/waiting projection. Idempotent
   after the live entry has gone. `:waiting` has no ended-at; terminal states do."
  ([run-id status] (finish! run-id status {}))
  ([run-id status {:keys [reason error now settlement-status settlement-reason]}]
   (when-not (contains? finish-statuses status)
     (throw (ex-info "Invalid run finish status"
                     {:type ::invalid-finish-status :status status :run-id run-id})))
   (locking lifecycle-lock
     (when-let [entry (get @active run-id)]
       (let [now (or now (java.util.Date.))
             run (cond-> (assoc (:run entry)
                                :run/status status
                                :run/updated-at now)
                   (contains? store/terminal-run-statuses status)
                   (assoc :run/ended-at now)
                   reason (assoc :run/reason reason)
                   settlement-status (assoc :run/settlement-status settlement-status)
                   settlement-reason (assoc :run/settlement-reason settlement-reason)
                   error (assoc :run/error (or (ex-message error) (str error))))]
         (when-let [room-store (:store entry)]
           (when-not (store/-store-run! room-store (:store-room-id entry) run)
             ;; Keep the live entry: callers may retry and subscribers must not
             ;; observe a terminal projection that the Room store does not own.
             (throw (ex-info "Run finish was not durable"
                             {:type ::finish-not-durable
                              :run/id run-id
                              :run/status status}))))
         (swap! active dissoc run-id)
         (notify! {:type :run/finished :run run})
         run)))))

(defn retain-finished!
  "Durably write a final/waiting Run projection while retaining its live
   execution lease. Interpreters use this before publishing fork-local result
   state; `release-finished!` then removes the lease and emits `:run/finished`.

   This two-phase boundary prevents Room teardown from closing the execution
   context between durable terminal persistence and Deferred publication."
  ([run-id status] (retain-finished! run-id status {}))
  ([run-id status {:keys [reason error now settlement-status settlement-reason]}]
   (when-not (contains? finish-statuses status)
     (throw (ex-info "Invalid run finish status"
                     {:type ::invalid-finish-status :status status :run-id run-id})))
   (locking lifecycle-lock
     (when-let [entry (get @active run-id)]
       ;; Exactly one settlement path owns result publication. A graph callback
       ;; racing the normal Spin sees this retained terminal state and yields.
       (when-not (contains? finish-statuses (get-in entry [:run :run/status]))
         (let [now (or now (java.util.Date.))
               run (cond-> (assoc (:run entry)
                                  :run/status status
                                  :run/updated-at now)
                     (contains? store/terminal-run-statuses status)
                     (assoc :run/ended-at now)
                     reason (assoc :run/reason reason)
                     settlement-status (assoc :run/settlement-status settlement-status)
                     settlement-reason (assoc :run/settlement-reason settlement-reason)
                     error (assoc :run/error (or (ex-message error) (str error))))]
           (when-let [room-store (:store entry)]
             (when-not (store/-store-run! room-store (:store-room-id entry) run)
               (throw (ex-info "Run finish was not durable"
                               {:type ::finish-not-durable
                                :run/id run-id
                                :run/status status}))))
           (swap! active assoc-in [run-id :run] run)
           run))))))

(defn release-finished!
  "Release a Run whose terminal projection was written by `retain-finished!`.
   Emits the lifecycle finish event only as the execution lease disappears."
  [run-id]
  (locking lifecycle-lock
    (when-let [entry (get @active run-id)]
      (let [run (:run entry)]
        (when-not (contains? finish-statuses (:run/status run))
          (throw (ex-info "Cannot release a non-finished Run"
                          {:type ::run-not-finished
                           :run/id run-id
                           :run/status (:run/status run)})))
        (swap! active dissoc run-id)
        (notify! {:type :run/finished :run run})
        run))))

(defn publish-finished!
  "Publish a retained result and release its live lease under one lifecycle
   fence. Room teardown cannot observe the lease disappear and close the
   execution context before `publish!` has written the fork-aware result."
  [run-id publish! result]
  (locking lifecycle-lock
    (when-let [entry (get @active run-id)]
      (let [run (:run entry)]
        (when-not (contains? finish-statuses (:run/status run))
          (throw (ex-info "Cannot publish a non-finished Run"
                          {:type ::run-not-finished
                           :run/id run-id
                           :run/status (:run/status run)})))
        (swap! active dissoc run-id)
        (try
          (publish! result)
          (catch Throwable t
            ;; Keep the durable terminal lease recoverable if publication into
            ;; the execution context itself fails.
            (swap! active assoc run-id entry)
            (throw t)))
        (notify! {:type :run/finished :run run})
        run))))

(defn update-durable-settlement!
  "Update the settlement axis of an already-quiesced Run. This is the durable
   half of a later human/agent decision on a retained review world. Execution
   identity and status are preserved."
  [room run-id status & [reason]]
  (when-not (keyword? status)
    (throw (ex-info "Run settlement status must be a keyword"
                    {:type ::invalid-settlement-status :status status})))
  (when-let [room-store (:store room)]
    (let [room-id (store-room-id room)
          existing (store/-load-run room-store room-id run-id)]
      (when-not existing
        (throw (ex-info "Run settlement owner is not durable in this Room"
                        {:type ::run-not-found
                         :run/id run-id
                         :run/room (:id room)})))
      (let [updated (cond-> (assoc existing
                                   :run/settlement-status status
                                   :run/updated-at (java.util.Date.))
                      reason (assoc :run/settlement-reason reason))]
        (or (store/-store-run! room-store room-id updated)
            (throw (ex-info "Run settlement update was not durable"
                            {:type ::settlement-not-durable
                             :run/id run-id
                             :run/settlement-status status})))))))

(defn cancel-requested?
  "True when targeted cancellation has been requested for this live Run. The
  private token disappears with the live entry; durable cancellation is the
  executor's later `:cancelled` acknowledgement."
  [run-id]
  (boolean (some-> (get @active run-id) :cancelled? deref)))

(defn- request-cancel!
  [run-id expected-room]
  (let [entry
        (locking lifecycle-lock
          (when-let [entry (get @active run-id)]
            (when (or (nil? expected-room)
                      (= expected-room (get-in entry [:run :run/room])))
              (if (or @(:cancelled? entry)
                      ;; A two-phase finisher retains its execution lease only
                      ;; to publish fork-local state. Teardown must wait for that
                      ;; lease, not turn the already-durable outcome back into
                      ;; `:cancelling` or interrupt its publication.
                      (contains? finish-statuses
                                 (get-in entry [:run :run/status])))
                entry
                (let [_ (reset! (:cancelled? entry) true)
                      entry' (assoc-in entry [:run :run/status] :cancelling)]
                  (swap! active assoc run-id entry')
                  (notify! {:type :run/cancel-requested :run (public-entry entry')})
                  entry')))))]
    ;; Never invoke interpreter code under lifecycle-lock: a hook may settle the
    ;; Run, which takes the same lock. Repeated requests re-invoke idempotent
    ;; hooks so a worker registered concurrently with the first request is not
    ;; missed.
    (doseq [hook (if (contains? finish-statuses
                                (get-in entry [:run :run/status]))
                   []
                   (vals (:cancel-hooks entry)))]
      (try
        (hook)
        (catch Throwable t
          (tel/log! {:level :warn :id ::cancel-hook-failed
                     :data {:run-id run-id :error (.getMessage t)}}
                    "Run cancellation hook failed"))))
    (boolean entry)))

(defn register-cancel-hook!
  "Register process-local `f` for targeted cancellation of a live Run. If a
   cancellation request already won the race, invoke `f` immediately. Returns
   true when the Run is still live, false after settlement."
  [run-id key f]
  (let [cancelled?
        (locking lifecycle-lock
          (when-let [entry (get @active run-id)]
            (swap! active assoc-in [run-id :cancel-hooks key] f)
            @(:cancelled? entry)))]
    (when cancelled? (f))
    (some? cancelled?)))

(defn unregister-cancel-hook! [run-id key]
  (locking lifecycle-lock
    (when (get @active run-id)
      (swap! active update-in [run-id :cancel-hooks] dissoc key)))
  nil)

(defn cancel-run!
  "Request cooperative cancellation of exactly one live Run. Returns true when
   found/signalled, false for an unknown or already-finished Run. Durable status
  becomes `:cancelled` only when the executor acknowledges via `finish!`."
  [run-id]
  (request-cancel! run-id nil))

(defn cancel-room-run!
  "Request cancellation only when `run-id` is live in `room-id`. This is the
   capability-scoped boundary for room SCI; UUID knowledge alone grants no
   cross-Room control. Trusted host/admin code may use `cancel-run!`."
  [room-id run-id]
  (request-cancel! run-id room-id))

(defn cancel-room-runs!
  "Request cancellation of every live Run in `room-id`; return the count."
  [room-id]
  (let [ids (mapv :run/id (active-runs room-id))]
    (doseq [run-id ids] (cancel-room-run! room-id run-id))
    (count ids)))

(defn register-live!
  "Compatibility registration for callers that only have the old
   `[room-id actor ChatContext]` tuple. New execution paths should call `start!`
   with the real Room and trigger message so the Run is durable and causal."
  [room-id actor live-chat-ctx]
  (:run/id (start! {:id room-id :store nil :meta (atom {})}
                   actor (random-uuid) live-chat-ctx)))

(defn unregister-live!
  "Compatibility live unregister. The run-id arity is targeted; the two-arity
   form removes every matching old-style room/actor registration."
  ([run-id]
   (when-let [entry (get @active run-id)]
     (finish! run-id
              (if (or (cancel-requested? run-id)
                      (= :cancelled (chat-ctx/get-status (:chat-ctx entry))))
                :cancelled
                :completed))))
  ([room-id actor]
   (doseq [run-id (->> (vals @active)
                       (filter #(and (= room-id (get-in % [:run :run/room]))
                                     (= actor (get-in % [:run :run/actor]))))
                       (map #(get-in % [:run :run/id]))
                       vec)]
     (unregister-live! run-id))))

(defn run
  "Load one durable Run from its Room store."
  [room run-id]
  (when-let [room-store (:store room)]
    (store/-load-run room-store (store-room-id room) run-id)))

(defn runs
  "List recent durable Runs from a Room store. Options: :limit, :status, :actor."
  ([room] (runs room {}))
  ([room opts]
   (if-let [room-store (:store room)]
     (store/-list-runs room-store (store-room-id room) opts)
     [])))
