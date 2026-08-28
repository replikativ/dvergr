(ns dvergr.agent.run
  "Minimal durable Run lifecycle for causally bounded room execution.

   A Run is durable identity and correlation data. Live execution and
   cancellation handles remain private in this namespace and are never returned
   by snapshots, lifecycle events, or the Room store."
  (:require [dvergr.chat.context :as chat-ctx]
            [dvergr.room.store :as store]
            [taoensso.telemere :as tel]))

(def ^:private finish-statuses #{:waiting :completed :failed :cancelled})
(defonce ^:private active (atom {}))
(defonce ^:private subscribers (atom {}))
(def ^:private lifecycle-lock (Object.))

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

(defn start!
  "Persist and publish one live Run before execution begins.

   `trigger` is the precise triggering message (or its UUID). `:parent` is
   optional explicit structural containment (the Run that spawned this Run),
   never inferred from causal message succession. Returns the public Run map."
  ([room actor trigger live-chat-ctx]
   (start! room actor trigger live-chat-ctx {}))
  ([room actor trigger live-chat-ctx {:keys [id kind parent now]
                                      :or {kind :agent-turn}}]
   (let [now        (or now (java.util.Date.))
         trigger-id (if (map? trigger) (:id trigger) trigger)
         run        (store/validate-run!
                     (cond-> {:run/id         (or id (random-uuid))
                              :run/kind       kind
                              :run/room       (:id room)
                              :run/actor      actor
                              :run/trigger    trigger-id
                              :run/status     :running
                              :run/created-at now
                              :run/started-at now
                              :run/updated-at now}
                       parent (assoc :run/parent parent)))
         entry      {:run run
                     :chat-ctx live-chat-ctx
                     :cancelled? (atom false)
                     :store (:store room)
                     :store-room-id (store-room-id room)}]
     (when-let [room-store (:store entry)]
       (when-not (store/-store-run! room-store (:store-room-id entry) run)
         (throw (ex-info "Run admission failed: start was not durable"
                         {:type ::start-not-durable
                          :run/id (:run/id run)
                          :run/room (:id room)}))))
     (locking lifecycle-lock
       (swap! active assoc (:run/id run) entry)
       (notify! {:type :run/started :run run}))
     run)))

(defn finish!
  "End the live execution and persist its final/waiting projection. Idempotent
   after the live entry has gone. `:waiting` has no ended-at; terminal states do."
  ([run-id status] (finish! run-id status {}))
  ([run-id status {:keys [reason error now]}]
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

(defn cancel-requested?
  "True when targeted cancellation has been requested for this live Run. The
   private token disappears with the live entry; durable cancellation is the
   executor's later `:cancelled` acknowledgement."
  [run-id]
  (boolean (some-> (get @active run-id) :cancelled? deref)))

(defn cancel-run!
  "Request cooperative cancellation of exactly one live Run. Returns true when
   found/signalled, false for an unknown or already-finished Run. Durable status
   becomes `:cancelled` only when the executor acknowledges via `finish!`."
  [run-id]
  (let [entry
        (locking lifecycle-lock
          (when-let [entry (get @active run-id)]
            (if (cancel-requested? run-id)
              entry
              (let [_ (reset! (:cancelled? entry) true)
                    entry' (assoc-in entry [:run :run/status] :cancelling)]
                (swap! active assoc run-id entry')
                (notify! {:type :run/cancel-requested :run (public-entry entry')})
                entry'))))]
    (if entry
      true
      false)))

(defn cancel-room-runs!
  "Request cancellation of every live Run in `room-id`; return the count."
  [room-id]
  (let [ids (mapv :run/id (active-runs room-id))]
    (doseq [run-id ids] (cancel-run! run-id))
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
