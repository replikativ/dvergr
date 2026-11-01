(ns dvergr.agent.run
  "Minimal durable Run lifecycle for causally bounded room execution.

   A Run is durable identity and correlation data. Live execution and
  cancellation handles remain private in this namespace and are never returned
   by snapshots, lifecycle events, or the Room store."
  (:require [clojure.edn]
            [dvergr.chat.context :as chat-ctx]
            [dvergr.agent.run.family :as family]
            [org.replikativ.spindel.engine.impl.simple :as simple]
            [dvergr.room.store :as store]
            [org.replikativ.spindel.engine.core :as ec]
            [taoensso.telemere :as tel]))

(def ^:private finish-statuses #{:waiting :completed :failed :cancelled})
(defonce ^:private active (atom {}))
(defonce ^:private subscribers (atom {}))
(def ^:private lifecycle-lock
  "Orders the in-memory lifecycle: `active` and the events subscribers see
   (`watch-runs!`'s snapshot is a frontier of that order). Held briefly, never
   across a store write."
  (Object.))

(defonce ^:private room-locks (java.util.concurrent.ConcurrentHashMap.))

(defn- room-lock
  "The read-write lock of one Room's Run lifecycle. Admission fences (open,
   close, fence, recover) hold it exclusively and see a fixed admitted set;
   a Run's admission and its durable writes hold it shared, together with
   their Run's own lock, so the writes of different Runs of one Room proceed
   together (an experiment's cells are Runs of one Room: one exclusive lock
   queued every cell's Run start behind the others' commits).
   Order: Room lock, then Run lock, then `lifecycle-lock`, then Room metadata."
  ^java.util.concurrent.locks.ReentrantReadWriteLock [room-id]
  (.computeIfAbsent ^java.util.concurrent.ConcurrentHashMap room-locks (or room-id ::none)
                    (reify java.util.function.Function
                      (apply [_ _] (java.util.concurrent.locks.ReentrantReadWriteLock.)))))

(def ^:private run-stripes
  "Run locks, striped by Run id: a fixed set, nothing to reclaim."
  (vec (repeatedly 256 #(Object.))))

(def ^:private ^ThreadLocal held-run (ThreadLocal.))

(defn- run-stripe [run-id]
  (nth run-stripes (mod (hash run-id) (count run-stripes))))

(defn- call-exclusive [room-id f]
  (let [lock (room-lock room-id)]
    (when (pos? (.getReadHoldCount lock))
      ;; a read hold cannot be upgraded: this would wait for itself
      (throw (ex-info "Room admission fence taken inside one of the Room's Run operations"
                      {:type ::lock-upgrade :room-id room-id})))
    (.lock (.writeLock lock))
    (try (f) (finally (.unlock (.writeLock lock))))))

(defn- call-with-run-lock [room-id run-id f]
  (let [lock (room-lock room-id)
        stripe (run-stripe run-id)
        held (.get held-run)]
    (when (and (some? held) (not= held run-id))
      ;; two Run locks nested on one thread could deadlock against the
      ;; reverse nesting on another (also when the two Runs share a stripe:
      ;; the nested Room lock can queue behind a writer): refuse instead
      ;; of waiting. The same Run again is reentrant.
      (throw (ex-info "A Run operation nested inside another Run's"
                      {:type ::nested-run-locks :run/id run-id :held held})))
    (.lock (.readLock lock))
    (try
      (locking stripe
        (.set held-run run-id)
        (try (f) (finally (.set held-run held))))
      (finally (.unlock (.readLock lock))))))

(defmacro ^:private with-room-exclusive [room-id & body]
  `(call-exclusive ~room-id (fn [] ~@body)))

(defmacro ^:private with-run-lock
  "Hold the shared lock of live Run `run-id`'s Room and the Run's own lock
   (any Room's when it is not live: the operation then finds no entry and
   does nothing)."
  [run-id & body]
  `(let [run-id# ~run-id]
     (call-with-run-lock (get-in @active [run-id# :run :run/room]) run-id# (fn [] ~@body))))

(def provenance-keys
  "Run fields an interpreter may add without changing core causal identity."
  #{:run/roster :run/agent-version :run/program-kind
    :run/interpreter-version :run/agent-def-hash :run/chat-id
    :run/world :run/isolation :run/settlement-policy
    :run/settlement-status :run/settlement-reason :run/role})

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
  (with-room-exclusive room-id
    (binding [ec/*execution-context* execution-ctx]
      (ec/swap-state! (admission-path room-id) (constantly :open))))
  nil)

(defn initialize-unpublished-room-admission!
  "Initialize admission for a fresh Room before it is published.

   This deliberately does not acquire `lifecycle-lock`: callers may be holding
   a parent Room's metadata lock while constructing the child. It is only safe
   for a unique Room ID that is not registered, shared, or running yet."
  [room-id execution-ctx]
  (when (some #(= room-id (get-in % [:run :run/room])) (vals @active))
    (throw (ex-info "Cannot initialize admission for a Room with active Runs"
                    {:type ::room-already-active
                     :room-id room-id})))
  (binding [ec/*execution-context* execution-ctx]
    (ec/swap-state! (admission-path room-id) (constantly :open)))
  nil)

(defn close-room-admission!
  "Atomically close fork-local Run admission for `room` and return the fixed set
   admitted before the fence. Teardown drains exactly this set."
  [room]
  (let [room-id (:id room)]
    (with-room-exclusive room-id
      (binding [ec/*execution-context* (:ctx room)]
        (ec/swap-state! (admission-path room-id) (constantly :closed)))
      (->> (vals @active)
           (keep (fn [entry]
                   (when (= room-id (get-in entry [:run :run/room]))
                     (get-in entry [:run :run/id]))))
           set))))

(defn fence-room-admission!
  "Close Run admission and invoke `install-fence!` with the fixed admitted Run
   IDs and their public snapshots while holding the lifecycle frontier.

   `install-fence!` may acquire a Room lifecycle lock. This establishes the
   only nested order as Run lifecycle -> Room lifecycle; it must not publish
   Run events or invoke lifecycle subscribers."
  [room install-fence!]
  (let [room-id (:id room)]
    (with-room-exclusive room-id
      (binding [ec/*execution-context* (:ctx room)]
        (ec/swap-state! (admission-path room-id) (constantly :closed)))
      (let [entries (->> (vals @active)
                         (filter #(= room-id (get-in % [:run :run/room]))))
            admitted (into #{} (map #(get-in % [:run :run/id])) entries)
            runs (ordered-runs entries)]
        (install-fence! admitted runs)
        admitted))))

(defn recover-room-admission!
  "Atomically reopen a fenced Room when `recover-fence!` confirms and removes
   the exact Room lifecycle fence being recovered.

   The callback runs under the Run lifecycle lock and may acquire Room metadata,
   preserving the sole nested order Run lifecycle -> Room metadata. It must
   return truthy only when it removed the expected fence generation."
  [room recover-fence!]
  (with-room-exclusive (:id room)
    (when (recover-fence!)
      (binding [ec/*execution-context* (:ctx room)]
        (ec/swap-state! (admission-path (:id room)) (constantly :open)))
      true)))

;; Child admissions belong to this existing Run registry. Their semantic
;; transitions are pure; native references and callbacks never enter Room state.
(def ^:dynamic *child-admission* nil)

(declare cancel-room-run! with-owned-child! start!)

(defn- family-entry! [room parent]
  (let [entry (get @active parent)]
    (when-not (and entry (= (:id room) (get-in entry [:run :run/room]))
                   (identical? (:ctx room) (:execution-context entry)))
      (throw (ex-info "Parent Run has no original live family owner"
                      {:type ::family-owner-unavailable :run/id parent})))
    entry))

(defn- change-family! [parent owner f]
  (locking lifecycle-lock
    (let [entry (get @active parent)]
      (when-not (and entry (identical? owner (:native-owner entry)))
        (throw (ex-info "Child admission owner changed" {:type ::family-owner-unavailable :run/id parent})))
      (let [changed (f (:family entry))
            ;; Native leases without an adapter hook release with bookkeeping
            ;; alone. No arbitrary callback executes under this frontier.
            next (reduce (fn [state [token value]]
                           (if (and (family/releasable? value) (nil? (:on-release value)))
                             (-> state (family/releasing token) (family/released token)) state))
                         changed (:leases changed))
            signal (:family-signal entry)]
        (swap! active update parent assoc :family next :family-signal (promise))
        (deliver signal true)
        next))))

(defn- current-admission? [room parent]
  (locking lifecycle-lock
    (let [entry (get @active parent) marker *child-admission*]
      (and marker entry (= parent (:parent marker))
           (identical? (:ctx room) (:execution-context entry))
           (identical? (:ctx room) (:context marker))
           (identical? (:owner marker) (:native-owner entry))
           (contains? (get-in entry [:family :leases]) (:token marker))))))

(defn seal-family!
  "Close new child admission without waiting. Existing reservations remain
   owned; this is a native Run-family fence, not a Spindel world fence."
  [room parent]
  (let [owner (locking lifecycle-lock (:native-owner (family-entry! room parent)))]
    (change-family! parent owner family/seal))
  nil)

(defn- family-guard! [entry]
  (when-not (family/quiescent? (:family entry))
    (throw (ex-info "Run still owns child admissions or executions"
                    {:type ::family-pending :run/id (get-in entry [:run :run/id])}))))

(defn- claim-child! [room parent child-id]
  (when-not (current-admission? room parent)
    (throw (ex-info "Structural child has no original admission lease"
                    {:type ::child-admission-required :run/id child-id :parent parent})))
  (let [{:keys [owner token]} *child-admission*]
    (change-family! parent owner #(family/claim % token child-id))))

(defn call-with-child-admission!
  "Acquire/consume an original parent lease BEFORE a child creates a world,
   moves a grant, or constructs work. An inherited consumed marker cannot
   admit a second child. `f` receives this admission's fixed child UUID."
  [room parent child-id f]
  (if-not parent
    (binding [*child-admission* nil] (f child-id))
    (if (and (current-admission? room parent)
             (= :reserved (get-in @active [parent :family :leases (:token *child-admission*) :phase]))
             (not (get-in @active [parent :family :leases (:token *child-admission*) :callback-exited?])))
      (do (claim-child! room parent child-id) (f child-id))
      (with-owned-child! room parent
        #(do (claim-child! room parent child-id) (f child-id))))))

(defn child-preparation-entered!
  "Retain preparation ownership before creating a child's native work plane."
  []
  (when-let [{:keys [parent owner token]} *child-admission*]
    (change-family! parent owner #(family/preparation-entered % token))))

(defn retain-child-preparation!
  "Retain the original idempotent constructor cleanup before native effects.
   Only failed pre-admission HOST recovery may invoke this private callback."
  [cleanup!]
  (when-let [{:keys [parent owner token]} *child-admission*]
    (change-family! parent owner
                    #(do (when-not (= :preparing (:phase (family/lease % token)))
                           (throw (ex-info "Preparation no longer owns construction" {:type ::stale-preparation})))
                         (assoc-in % [:leases token :preparation-cleanup!] cleanup!)))))

(defn child-preparation-cleaned!
  "Acknowledge successful physical cleanup of a failed pre-admission plane."
  []
  (when-let [{:keys [parent owner token]} *child-admission*]
    (change-family! parent owner #(family/preparation-cleaned % token))))

(defn- begin-child-start! [room parent child-id command]
  (when parent
    (when-not (current-admission? room parent)
      (throw (ex-info "Structural child requires a live original admission lease"
                      {:type ::child-admission-required :run/id child-id :parent parent})))
    (let [{:keys [owner token]} *child-admission*]
      ;; Direct native Run creation can consume a reserved lease here. Public
      ;; programming boundaries consume it earlier, before native preparation.
      (change-family! parent owner
                      #(let [value (family/lease % token)
                             claimed (if (= :reserved (:phase value)) (family/claim % token child-id) %)]
                         (-> (family/starting claimed token child-id)
                             (assoc-in [:leases token :start-command] command)
                             (assoc-in [:leases token :start-fence]
                                       (assoc command :run/status :failed
                                              :run/ended-at (:run/updated-at command)
                                              :run/reason :admission-failed))))))))

(defn- child-started! [room parent child-id]
  (when parent
    (let [{:keys [owner token]} *child-admission*
          key [::child-family owner token]]
      (change-family! parent owner #(family/admitted % token child-id))
      ;; Snapshot + notifications share the lifecycle frontier. The callback
      ;; performs bookkeeping only; adapter hooks run on an external joiner.
      (watch-runs!
       key
       (fn [{:keys [type runs run]}]
         (when (or (and (= :runs/snapshot type) (not-any? #(= child-id (:run/id %)) runs))
                   (and (= :run/finished type) (= child-id (:run/id run))))
           (change-family! parent owner #(family/child-returned % token child-id))
           (unwatch-runs! key)))))))

(defn- replay-child-cancellation! [room parent child-id]
  (when parent
    (when (get-in @active [parent :family :leases (:token *child-admission*) :cancelled?])
      (cancel-room-run! (:id room) child-id))))

(defn cancel-family!
  "Seal the original family and forward sticky cancellation outside its lock."
  [room parent]
  (when-let [owner (locking lifecycle-lock
                     (when (get @active parent) (:native-owner (family-entry! room parent))))]
    (let [state (change-family! parent owner family/cancel)]
      (doseq [[_ value] (:leases state)
              :when (= :admitted (:phase value))]
        (cancel-room-run! (:id room) (:child-id value)))))
  nil)

(defn require-child-publication!
  "Register an optional publication edge before the admission callback exits."
  []
  (let [{:keys [parent owner token]} *child-admission*]
    (change-family! parent owner #(family/require-publication % token))
    (fn []
      (locking lifecycle-lock
        (when (and (identical? owner (:native-owner (get @active parent)))
                   (get-in @active [parent :family :leases token]))
          (change-family! parent owner #(family/publication-acknowledged % token)))))))

(defn with-family-admission!
  "Original native owner implementation, shared by program and Job adapters.
   Native release hooks must be idempotent bookkeeping, with no external effect.
   Hooks execute only on a HOST joiner, outside all lifecycle/Run/Room locks."
  [room parent f & [{:keys [on-reserve on-release]}]]
  (let [token (random-uuid)
        owner (locking lifecycle-lock
                (let [entry (family-entry! room parent)]
                  (when (or @(:cancelled? entry) (finish-statuses (get-in entry [:run :run/status])))
                    (throw (ex-info "Parent Run is no longer admitting children" {:type ::family-sealed :run/id parent})))
                  (:native-owner entry)))
        marker {:parent parent :owner owner :token token :context (:ctx room)}]
    (change-family! parent owner
                    #(-> (family/reserve % token)
                         (assoc-in [:leases token :on-release] on-release)))
    (binding [*child-admission* marker]
      (try
        (when on-reserve (on-reserve token))
        (f)
        (catch Throwable error
          (change-family! parent owner
                          #(-> % (assoc-in [:leases token :admission-error] error)
                               (assoc-in [:leases token :recovery-revision] (random-uuid))))
          (throw error))
        (finally
          ;; No return-value inference: run/start! recorded the actual child.
          ;; A callback exception after admission still owns that child.
          (change-family! parent owner #(family/callback-exited % token)))))))

(defn await-family!
  "HOST join of the original native Run family. Seals admission, then waits
   for callback/preparation and child release, driving only native bookkeeping
   hooks. Never call while holding an engine drain or any Run/Room/owner lock."
  [room parent]
  (when (or (simple/on-drain-thread?) (.get held-run) (Thread/holdsLock lifecycle-lock)
            (pos? (.getReadHoldCount (room-lock (:id room))))
            (.isWriteLockedByCurrentThread (room-lock (:id room))))
    (throw (ex-info "Run family join requires an external unlocked HOST thread" {:type ::family-join-under-lock})))
  (let [owner (locking lifecycle-lock (:native-owner (family-entry! room parent)))]
    (change-family! parent owner family/seal)
    (loop []
      (let [{:keys [done? claim signal]}
            (locking lifecycle-lock
              (let [entry (family-entry! room parent)
                    state (:family entry)
                    ready (some (fn [[token value]] (when (family/releasable? value) [token value])) (:leases state))]
                (cond
                  (family/quiescent? state) {:done? true}
                  ready
                  (let [[token value] ready]
                    (change-family! parent owner #(family/releasing % token))
                    {:claim [token value]})
                  :else {:signal (:family-signal entry)})))]
        (cond
          done? nil
          claim
          (let [[token value] claim
                error (try (when-let [release! (:on-release value)] (release! token)) nil
                           (catch Throwable t t))]
            (if error
              (do
                ;; Only idempotent native reference-release adapters are retried;
                ;; no provider, world, publication or money operation lives here.
                (change-family! parent owner
                                #(-> % (assoc-in [:leases token :phase] (:phase value))
                                     (assoc-in [:leases token :release-error] error)))
                (try (Thread/sleep 25) (catch InterruptedException _)))
              (change-family! parent owner #(family/released % token)))
            (recur))
          :else (do @signal (recur)))))))

(defn- start-unowned!
  "Persist and publish one live Run before execution begins.

   `trigger` is the precise triggering message (or its UUID). `:parent` is
   optional explicit structural containment (the Run that spawned this Run),
   never inferred from causal message succession. `:caused-by` names terminal
   Runs this one continues (a resumed Run's predecessor), recorded with its
   admission. Returns the public Run map."
  ([room actor trigger live-chat-ctx]
   (start! room actor trigger live-chat-ctx {}))
  ([room actor trigger live-chat-ctx {:keys [id kind parent caused-by now provenance native-controls]
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
                        parent (assoc :run/parent parent)
                        (seq caused-by) (assoc :run/caused-by (set caused-by)))
                      provenance))
         entry      {:run run
                     :execution-context (:ctx room)
                     :native-owner (Object.)
                     :family (family/empty-family)
                     :family-signal (promise)
                     :chat-ctx live-chat-ctx
                     ;; Explicitly process-local control state. Durable meaning
                     ;; is the Room-store Run projection; this live owner map
                     ;; and its token are never copied or restored as workflow
                     ;; state.
                     :cancelled? (atom false)
                     ;; Process-local callbacks let an interpreter abort native
                     ;; workers as soon as cancellation is requested. They are
                     ;; control capabilities, never durable Run state.
                     :cancel-hooks (or (:cancel-hooks native-controls) {})
                     :own-child! (:own-child! native-controls)
                     :settlement-observer (:settlement-observer native-controls)
                     :settlement-reconciler (:settlement-reconciler native-controls)
                     :store (:store room)
                     :store-room-id (store/conversation-id room)}]
     (call-with-run-lock (:run/room run) (:run/id run) (fn []
                                                         (when (and (:ctx room)
                                                                    (= :closed
                                                                       (binding [ec/*execution-context* (:ctx room)]
                                                                         (ec/get-state (admission-path (:id room))))))
                                                           (throw (ex-info "Run admission is closed for Room teardown"
                                                                           {:type ::room-admission-closed
                                                                            :run/id (:run/id run)
                                                                            :run/room (:id room)})))
                                                         (begin-child-start! room parent (:run/id run) run)
       ;; Persistence is inside the same admission critical section as the
       ;; fence check: teardown can see either no Run or the fully admitted Run,
       ;; never a durable-but-unowned half-admission.
                                                         (when-let [room-store (:store entry)]
                                                           (when-not (store/-store-run! room-store (:store-room-id entry) run)
                                                             (throw (ex-info "Run admission failed: start was not durable"
                                                                             {:type ::start-not-durable
                                                                              :run/id (:run/id run)
                                                                              :run/room (:id room)}))))
                                                         (locking lifecycle-lock
                                                           (swap! active assoc (:run/id run) entry)
                                                           (notify! {:type :run/started :run run})
                                                           (child-started! room parent (:run/id run)))))
     (replay-child-cancellation! room parent (:run/id run))
     run)))

(defn start!
  "Admit one native Run. Direct structural children acquire the same original
   family lease as programming/Job boundaries; closed parents fail before IO."
  ([room actor trigger live-chat-ctx] (start! room actor trigger live-chat-ctx {}))
  ([room actor trigger live-chat-ctx opts]
   (let [id (or (:id opts) (random-uuid)) parent (:parent opts)
         current? (and parent (current-admission? room parent)
                       (#{:reserved :preparing}
                        (get-in @active [parent :family :leases (:token *child-admission*) :phase])))]
     (if (or (nil? parent) current?)
       (if parent
         (start-unowned! room actor trigger live-chat-ctx (assoc opts :id id))
         (binding [*child-admission* nil]
           (start-unowned! room actor trigger live-chat-ctx (assoc opts :id id))))
       (call-with-child-admission!
        room parent id #(start-unowned! room actor trigger live-chat-ctx (assoc opts :id %)))))))

(defn record-cause!
  "Durably add `cause-run-id` to the causal inputs observed by a live Run.

   Structural ownership remains in `:run/parent`; this cardinality-many edge
   records that `run-id` actually crossed the completion boundary of another
   Run. Both Runs must have a store, and the cause must already be durable and
   terminal in that same Room store. Parallel
   observations serialize through the lifecycle lock, so additions cannot
   overwrite one another."
  [run-id cause-run-id]
  (when-not (and (uuid? run-id) (uuid? cause-run-id) (not= run-id cause-run-id))
    (throw (ex-info "Run causality requires two distinct Run UUIDs"
                    {:type ::invalid-cause
                     :run/id run-id
                     :cause-run/id cause-run-id})))
  (with-run-lock run-id
    (let [entry (or (get @active run-id)
                    (throw (ex-info "Causal consumer Run is not live"
                                    {:type ::run-not-active :run/id run-id})))
          room-store (:store entry)
          store-room-id (:store-room-id entry)]
      (when-not room-store
        (throw (ex-info "Durable Run causality requires a Room store"
                        {:type ::causal-store-required :run/id run-id})))
      (let [now (java.util.Date.)
            updated (-> (:run entry)
                        (update :run/caused-by (fnil conj #{}) cause-run-id)
                        (assoc :run/updated-at now))]
        (when room-store
          (when-not (store/-store-run! room-store store-room-id updated)
            (throw (ex-info "Run causality update was not durable"
                            {:type ::cause-not-durable
                             :run/id run-id
                             :cause-run/id cause-run-id}))))
        (locking lifecycle-lock (swap! active assoc-in [run-id :run] updated))
        updated))))

(defn finish!
  "End the live execution and persist its final/waiting projection. Idempotent
   after the live entry has gone. `:waiting` has no ended-at; terminal states do."
  ([run-id status] (finish! run-id status {}))
  ([run-id status {:keys [reason error now settlement-status settlement-reason]}]
   (when-not (contains? finish-statuses status)
     (throw (ex-info "Invalid run finish status"
                     {:type ::invalid-finish-status :status status :run-id run-id})))
   (with-run-lock run-id
     (when-let [entry (get @active run-id)]
       (let [owner (:native-owner entry)]
         (change-family! run-id owner family/seal)
         (family-guard! (get @active run-id)))
       (let [now (or now (java.util.Date.))
             run (cond-> (assoc (:run entry)
                                :run/status status
                                :run/updated-at now)
                   (:store entry)
                   (assoc :run/family-return-ack (store/family-return-ack-id run-id))
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
         (locking lifecycle-lock
           (swap! active dissoc run-id)
           (notify! {:type :run/finished :run run}))
         run)))))

(defn- retry-in-background!
  "Retry `attempt!` on a daemon thread with backoff (up to 5 s apart) while
   `pending?` holds, until it returns without throwing. Never gives up: what
   it retries would otherwise stay undone once the store recovers."
  [thread-name pending? attempt! log-data]
  (doto (Thread.
         ^Runnable
         (fn []
           (loop [delay-ms 25 attempt 1]
             (Thread/sleep (long delay-ms))
             (when (pending?)
               (let [outcome (try (attempt!) :done (catch Throwable t t))]
                 (when (instance? Throwable outcome)
                   (when (zero? (mod attempt 20))
                     (tel/log! {:level :error :id ::durable-write-still-refused
                                :data (assoc log-data :attempts attempt
                                             :error (ex-message outcome))}
                               "A durable Run write is still refused; retrying"))
                   (recur (min 5000 (* 2 delay-ms)) (inc attempt)))))))
         thread-name)
    (.setDaemon true)
    (.start))
  nil)

(defn- finish-durably-at! [run-id status opts]
  (try
    (finish! run-id status opts)
    (catch Throwable t
      (when (= ::invalid-finish-status (:type (ex-data t)))
        (throw t))
      (tel/log! {:level :warn :id ::finish-retrying
                 :data {:run/id run-id :run/status status :error (ex-message t)}}
                "Run finish refused; retrying off the caller's thread")
      (retry-in-background! (str "dvergr-run-finish-" run-id)
                            #(contains? @active run-id)
                            #(do
                               (when-let [entry (get @active run-id)]
                                 (await-family! {:id (get-in entry [:run :run/room])
                                                 :ctx (:execution-context entry)} run-id))
                               (finish! run-id status opts))
                            {:run/id run-id :run/status status})
      nil)))

(defn finish-durably!
  "`finish!` for a caller that cannot wait for the store. When the terminal
   write is refused the Run stays live, as `finish!` leaves it, and the write
   is retried on a background thread until it is durable or the Run is no
   longer live; nothing is lost and Room teardown sees the Run until it is.
   Returns the finished Run, or nil while the write is retried."
  ([run-id status] (finish-durably! run-id status {}))
  ([run-id status opts]
   ;; One end for every attempt: a write that was stored although it reported
   ;; failure is then repeated exactly, which the store accepts (a terminal
   ;; Run's end is immutable).
   (let [opts (update opts :now #(or % (java.util.Date.)))]
     (finish-durably-at! run-id status opts))))

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
   (with-run-lock run-id
     (when-let [entry (get @active run-id)]
       (let [owner (:native-owner entry)]
         (change-family! run-id owner family/seal)
         (family-guard! (get @active run-id)))
       ;; Exactly one settlement path owns result publication. A graph callback
       ;; racing the normal Spin sees this retained terminal state and yields.
       (when-not (contains? finish-statuses (get-in entry [:run :run/status]))
         (let [now (or now (java.util.Date.))
               run (cond-> (assoc (:run entry)
                                  :run/status status
                                  :run/updated-at now)
                     (:store entry)
                     (assoc :run/family-return-ack (store/family-return-ack-id run-id))
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
           (locking lifecycle-lock (swap! active assoc-in [run-id :run] run))
           run))))))

(defn release-finished!
  "Release a Run whose terminal projection was written by `retain-finished!`.
   Emits the lifecycle finish event only as the execution lease disappears."
  [run-id]
  (with-run-lock run-id
    (when-let [entry (get @active run-id)]
      (family-guard! entry)
      (let [run (:run entry)]
        (when-not (contains? finish-statuses (:run/status run))
          (throw (ex-info "Cannot release a non-finished Run"
                          {:type ::run-not-finished
                           :run/id run-id
                           :run/status (:run/status run)})))
        (locking lifecycle-lock
          (swap! active dissoc run-id)
          (notify! {:type :run/finished :run run}))
        run))))

(defn publish-finished!
  "Publish a retained result and release its live lease under one lifecycle
   fence. Room teardown cannot observe the lease disappear and close the
   execution context before `publish!` has written the fork-aware result."
  [run-id publish! result]
  (with-run-lock run-id
    (when-let [entry (get @active run-id)]
      (family-guard! entry)
      (let [run (:run entry)]
        (when-not (contains? finish-statuses (:run/status run))
          (throw (ex-info "Cannot publish a non-finished Run"
                          {:type ::run-not-finished
                           :run/id run-id
                           :run/status (:run/status run)})))
        (locking lifecycle-lock
          (swap! active dissoc run-id)
          (try
            (publish! result)
            (catch Throwable t
              ;; Keep the durable terminal lease recoverable if publication
              ;; into the execution context itself fails.
              (swap! active assoc run-id entry)
              (throw t)))
          (notify! {:type :run/finished :run run}))
        run))))

(defn update-durable-settlement!
  "Update the settlement axis of an already-quiesced Run. This is the durable
   half of a later human/agent decision on a retained review world. Execution
   identity and status are preserved."
  [room run-id status & [reason]]
  (when-not (keyword? status)
    (throw (ex-info "Run settlement status must be a keyword"
                    {:type ::invalid-settlement-status :status status})))
  ;; read and write under the Room's exclusive lock, as the resume claim and
  ;; a search's record do, so none loses another's write
  (with-room-exclusive (:id room)
    (when-let [room-store (:store room)]
      (let [room-id (store/conversation-id room)
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
                               :run/settlement-status status}))))))))

(defn acknowledge-job-cleanup!
  "Record one configured native Job cleanup boundary while its original
   execution lease protects this exact Room. Retry confirms a committed ACK
   and synchronizes the live projection without replaying cleanup."
  [room run-id stage]
  (let [attribute (get store/job-cleanup-attributes stage)
        ack (store/job-cleanup-ack-id run-id stage)
        attributes {attribute ack :run/family-return-ack (store/family-return-ack-id run-id)}
        confirmed? (fn [value] (= attributes (select-keys value (keys attributes))))]
    (with-run-lock run-id
      (let [entry (get @active run-id)]
        (when-not (and entry (= (:id room) (get-in entry [:run :run/room]))
                       (identical? (:ctx room) (:execution-context entry))
                       (identical? (:store room) (:store entry)))
          (throw (ex-info "Job cleanup acknowledgement has no original live owner"
                          {:type ::cleanup-owner-unavailable :run/id run-id})))
        (change-family! run-id (:native-owner entry) family/seal)
        (family-guard! (get @active run-id))
        (binding [ec/*execution-context* (:ctx room)]
          (let [st (:store entry) conversation (:store-room-id entry)
                durable (store/-load-run st conversation run-id)]
            (when-not durable
              (throw (ex-info "Job cleanup owner is not durable" {:type ::run-not-found :run/id run-id})))
            (when-not (confirmed? durable)
              (when-not (store/-store-run! st conversation (merge durable attributes {:run/updated-at (java.util.Date.)}))
                (throw (ex-info "Job cleanup acknowledgement was not durable"
                                {:type ::cleanup-ack-unconfirmed :run/id run-id :stage stage}))))
            (let [confirmed (store/-load-run st conversation run-id)]
              (when-not (confirmed? confirmed)
                (throw (ex-info "Job cleanup acknowledgement was not confirmed"
                                {:type ::cleanup-ack-unconfirmed :run/id run-id :stage stage})))
              (locking lifecycle-lock
                ;; Cancellation can update the live lifecycle without taking
                ;; this Run's persistence lock. Repair only the confirmed ACK
                ;; fields; never replace a concurrent :cancelling projection.
                (swap! active update-in [run-id :run]
                       merge (select-keys confirmed store/native-run-ack-attributes)))
              ack)))))))

(defn record-savepoint!
  "Keep `data` (a portable savepoint, `savepoint.portable/persist`) as the live
   Run's latest point to resume from (doc/runs.md, Resuming a Run). Durable and in the
   live entry, so the Run's later updates carry it. nil when the Run is not
   live in this process."
  [run-id origin data prepared]
  (with-run-lock run-id
    (when-let [entry (get @active run-id)]
      (let [room-store (:store entry)
            current (:run entry)]
        (when-not (and (= (:run/world current) (:id origin))
                       (= (:fork-id (:ctx origin)) (:fork-id ec/*execution-context*))
                       (satisfies? store/PRunSavepointStore room-store))
          (throw (ex-info "Run has no owned conversation checkpoint publisher"
                          {:type ::savepoint-not-durable :run/id run-id})))
        (let [durable (store/-load-run room-store (:store-room-id entry) run-id)
              facts (store/-record-run-savepoint!
                     room-store (:store-room-id entry) run-id
                     {:world (:id origin) :chat (:run/chat-id current)}
                     (:run/savepoint durable) data prepared)]
          (when-not (and (= (pr-str data) (:run/savepoint facts))
                         (= (get-in data [:savepoint/payload :conversation :ref])
                            (:run/conversation-ref facts)))
            (throw (ex-info "Run checkpoint did not acknowledge exact publication"
                            {:type ::savepoint-not-durable :run/id run-id})))
          (locking lifecycle-lock
            (swap! active update-in [run-id :run] merge facts))
          (get-in @active [run-id :run]))))))

(def ^:private search-intent
  "What a search decided for a member, written once and never changed by a
   later record of the same search (only its outcome, status and error are)."
  [:search/policy :search/members :search/manifest :search/weight :search/decision])

(defn record-search!
  "Record on durable Run `run-id` in `room` the search it is a member of
   (`dvergr.agent.search`): `search` is portable data, kept as EDN. A Run is
   a member of one search: a record of another search is refused. A record of
   the same search keeps the intent already recorded (`search-intent`) and
   updates the outcome. Read and written under the Room's exclusive lock, as
   the resume claim and settlement updates, so none loses another's write.
   Allowed on a terminal Run: it changes neither its lifecycle nor identity."
  [room run-id search]
  (when-let [room-store (:store room)]
    (with-room-exclusive (:id room)
      (let [room-id (store/conversation-id room)
            existing (store/-load-run room-store room-id run-id)]
        (when-not existing
          (throw (ex-info "A search member is not a durable Run in this Room"
                          {:type ::run-not-found :run/id run-id :run/room (:id room)})))
        (let [prior (some-> (:run/search existing) clojure.edn/read-string)]
          (when (and prior (not= (:search/id prior) (:search/id search)))
            (throw (ex-info "This Run is a member of another search"
                            {:type ::claimed-by-another-search :run/id run-id
                             :search/id (:search/id search) :claimed-by (:search/id prior)})))
          (or (store/-store-run! room-store room-id
                                 (assoc existing
                                        :run/search (pr-str (merge search (select-keys prior search-intent)))
                                        :run/updated-at (java.util.Date.)))
              (throw (ex-info "A search record was not durable"
                              {:type ::search-not-durable :run/id run-id}))))))))

(defonce ^:private search-reservations
  ;; run id -> the search that reserved it in this process: made before a
  ;; claim's durable writes, so a claim a failed write left partial is still
  ;; this search's to resume (settling a member needs its live world, which
  ;; is this process's too)
  (atom {}))

(defn- store-claim!
  "The durable write of one member's claim (`claim-search!`)."
  [room-store room-id existing search]
  (or (store/-store-run! room-store room-id
                         (assoc existing :run/search (pr-str search)
                                :run/updated-at (java.util.Date.)))
      (throw (ex-info "A search claim was not durable"
                      {:type ::search-not-durable :run/id (:run/id existing)}))))

(defn claim-search!
  "Claim the Runs of `records` ({run-id search-record}) in `room` for one
   search, all or none: under the Room's exclusive lock, refuse when any is
   a member or reservation of another search, reserve them all in this
   process, then record each not yet claimed by this one. A claim a failed
   write left part way is resumed by claiming again, and no other search
   can take its members meanwhile."
  [room records]
  (when-let [room-store (:store room)]
    (with-room-exclusive (:id room)
      (let [room-id (store/conversation-id room)
            loaded (into {} (map (fn [[run-id _]] [run-id (store/-load-run room-store room-id run-id)])) records)]
        (doseq [[run-id search] records
                :let [reserved (get @search-reservations run-id)]]
          (when (and reserved (not= reserved (:search/id search)))
            (throw (ex-info "This Run is reserved by another search"
                            {:type ::claimed-by-another-search :run/id run-id
                             :search/id (:search/id search) :claimed-by reserved}))))
        (doseq [[run-id search] records
                :let [existing (get loaded run-id)
                      prior (some-> (:run/search existing) clojure.edn/read-string)]]
          (when-not existing
            (throw (ex-info "A search member is not a durable Run in this Room"
                            {:type ::run-not-found :run/id run-id :run/room (:id room)})))
          (when (and prior (not= (:search/id prior) (:search/id search)))
            (throw (ex-info "This Run is a member of another search"
                            {:type ::claimed-by-another-search :run/id run-id
                             :search/id (:search/id search) :claimed-by (:search/id prior)}))))
        (swap! search-reservations into (map (fn [[run-id search]] [run-id (:search/id search)])) records)
        (doseq [[run-id search] records
                :let [existing (get loaded run-id)]
                :when (nil? (:run/search existing))]
          (store-claim! room-store room-id existing search))))))

(defn search-record
  "The search record of durable `run` (`record-search!`), as data, or nil."
  [run]
  (some-> (:run/search run) clojure.edn/read-string))

(defn savepoint
  "The latest portable savepoint of durable `run`, as data, or nil."
  [run]
  (some-> (:run/savepoint run) clojure.edn/read-string))

(defn load-conversation!
  "Load one validated frozen checkpoint before successor admission. No live fallback."
  [room run]
  (let [room-store (:store room)]
    (when-not (satisfies? store/PRunSavepointStore room-store)
      (throw (ex-info "Room cannot restore frozen Run conversations"
                      {:type ::unsupported-savepoint-store})))
    (:messages (store/-load-run-conversation room-store (store/conversation-id room) (:run/id run)
                                             (:run/savepoint run)))))

(defonce ^:private resume-reservations
  ;; Run ids a `resume!` in this process is resuming right now: excludes a
  ;; second resume of the same Run before the first one's successor is admitted
  (atom #{}))

(defonce ^:private void-successors
  ;; successor Run ids whose resume claim is withdrawn but whose withdrawal is
  ;; not durable yet: their claims do not count while it is retried
  (atom #{}))

(def ^:private unexecuted-reasons
  "Reasons a successor Run failed before it executed anything. Such a Run did
   not resume its predecessor, so the predecessor can be resumed again."
  #{:trigger-emission-failed :resource-allocation-failed :resume-claim-failed
    :spawn-failed})

(defn- resume-blocker
  "Why stored Run `existing` of `room` cannot be resumed now, as ex-info, or nil."
  [room run-id existing]
  (let [successor-id (:run/resumed-by existing)
        successor (when successor-id
                    (store/-load-run (:store room) (store/conversation-id room) successor-id))]
    (cond
      (nil? existing)
      (ex-info "No such Run" {:type ::run-not-found :run/id run-id})
      (contains? @active run-id)
      (ex-info "The Run is live in this process: nothing to resume"
               {:type ::run-live :run/id run-id})
      (nil? (:run/savepoint existing))
      (ex-info "The Run has no savepoint to resume from"
               {:type ::no-savepoint :run/id run-id})
      (and successor-id
           (not (contains? @void-successors successor-id))
           (not (and (= :failed (:run/status successor))
                     (contains? unexecuted-reasons (:run/reason successor)))))
      (ex-info "The Run was resumed before"
               {:type ::already-resumed :run/id run-id
                :run/resumed-by successor-id}))))

(defn reserve-resume!
  "Reserve stored Run `run-id` of `room` for one resume in this process and
   return it; throws when it has no savepoint, is live, was resumed before, or
   is being resumed. Nothing durable changes: `claim-resume!` records the
   successor once it is admitted, and `release-resume!` ends the reservation
   either way."
  [room run-id]
  (with-room-exclusive (:id room)
    (let [existing (some-> (:store room) (store/-load-run (store/conversation-id room) run-id))]
      (when-let [blocker (resume-blocker room run-id existing)]
        (throw blocker))
      (when (contains? @resume-reservations run-id)
        (throw (ex-info "The Run is being resumed"
                        {:type ::resume-in-progress :run/id run-id})))
      (swap! resume-reservations conj run-id)
      existing)))

(defn release-resume!
  "End this process's resume reservation of `run-id`."
  [run-id]
  (swap! resume-reservations disj run-id)
  nil)

(defn claim-resume!
  "Mark the stored Run `run-id` of `room` as continued by `resumed-by`, once:
   a Run is resumed at most one time. The successor claims it once admitted,
   holding the wallet it took over, so a failed resume leaves the Run as it
   was. Returns the updated Run; throws when it has no savepoint, is live, or
   was resumed before."
  [room run-id resumed-by]
  (with-room-exclusive (:id room)
    (let [room-store (:store room)
          room-id (store/conversation-id room)
          existing (some-> room-store (store/-load-run room-id run-id))]
      (when-let [blocker (resume-blocker room run-id existing)]
        (throw blocker))
      (let [updated (assoc existing :run/resumed-by resumed-by
                           :run/updated-at (java.util.Date.))]
        (or (store/-store-run! room-store room-id updated)
            (throw (ex-info "Resume claim was not durable"
                            {:type ::claim-not-durable :run/id run-id})))))))

(defn release-resume-claim!
  "Withdraw `resumed-by`'s claim on stored Run `run-id` of `room`: its
   successor failed before it executed. A claim by another Run is left alone.
   Returns the updated Run, or nil when there was nothing to withdraw."
  [room run-id resumed-by]
  (with-room-exclusive (:id room)
    (let [room-store (:store room)
          room-id (store/conversation-id room)
          existing (some-> room-store (store/-load-run room-id run-id))]
      (when (and existing (= resumed-by (:run/resumed-by existing)))
        (let [updated (-> existing
                          (dissoc :run/resumed-by)
                          (assoc :run/updated-at (java.util.Date.)))]
          (or (store/-store-run! room-store room-id updated)
              (throw (ex-info "Resume claim release was not durable"
                              {:type ::claim-not-durable :run/id run-id}))))))))

(defn withdraw-resume-claim!
  "Withdraw `resumed-by`'s claim on Run `run-id` of `room`, whether or not the
   claim was written: its successor did not execute. The claim stops counting
   at once in this process; when its durable withdrawal is refused, that is
   retried in the background until it is durable (or another Run claimed the
   Run since)."
  [room run-id resumed-by]
  (swap! void-successors conj resumed-by)
  (let [withdraw! (fn []
                    (release-resume-claim! room run-id resumed-by)
                    (swap! void-successors disj resumed-by))]
    (try
      (withdraw!)
      (catch Throwable t
        (tel/log! {:level :warn :id ::claim-withdrawal-retrying
                   :data {:run/id run-id :resumed-by resumed-by :error (ex-message t)}}
                  "A resume claim withdrawal was refused; retrying off the caller's thread")
        (retry-in-background! (str "dvergr-resume-claim-" run-id)
                              #(contains? @void-successors resumed-by)
                              withdraw!
                              {:run/id run-id :resumed-by resumed-by}))))
  nil)

(defn cancel-requested?
  "True when targeted cancellation has been requested for this live Run. The
  private token disappears with the live entry; durable cancellation is the
  executor's later `:cancelled` acknowledgement."
  [run-id]
  (boolean (some-> (get @active run-id) :cancelled? deref)))

(defn cancel-requested-nanos
  "When cancellation of this live Run was first requested (`System/nanoTime`),
   or nil. Process-local, like the cancellation token."
  [run-id]
  (:cancel-requested-nanos (get @active run-id)))

(defonce ^:private watched-cancel-times
  ;; run-id -> nanoTime of the first cancellation request (nil: none yet), for
  ;; Runs a host watches: kept after the Run retires, until unwatched
  (atom {}))

(defn watch-cancel-time!
  "Keep when `run-id` is first asked to cancel, past its retirement, until
   taken. Watch before the Run starts (its id chosen by the caller) so no
   request can be missed; a request already made is kept too."
  [run-id]
  (locking lifecycle-lock
    (swap! watched-cancel-times assoc run-id (cancel-requested-nanos run-id)))
  nil)

(defn watched-cancel-time
  "The watched Run's first cancellation request (`System/nanoTime`), or nil."
  [run-id]
  (get @watched-cancel-times run-id))

(defn take-cancel-time!
  "Stop watching `run-id` and return its first cancellation request, or nil:
   read and removed under the lifecycle lock, so a request in progress is
   either seen or not recorded at all."
  [run-id]
  (locking lifecycle-lock
    (let [t (get @watched-cancel-times run-id)]
      (swap! watched-cancel-times dissoc run-id)
      t)))

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
                      ;; when it was first asked, with the token, under the
                      ;; lock: what tells a cancellation from a deadline
                      now (System/nanoTime)
                      _ (when (contains? @watched-cancel-times run-id)
                          (swap! watched-cancel-times assoc run-id now))
                      entry' (-> entry
                                 (assoc-in [:run :run/status] :cancelling)
                                 (assoc :cancel-requested-nanos now))]
                  (swap! active assoc run-id entry')
                  (notify! {:type :run/cancel-requested :run (public-entry entry')})
                  entry')))))]
    (when entry
      (cancel-family! {:id (get-in entry [:run :run/room]) :ctx (:execution-context entry)} run-id))
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

(defn register-child-owner!
  "Attach the executor's process-local child admission lease to a live Run."
  [run-id own-child!]
  (locking lifecycle-lock
    (when (get @active run-id)
      (swap! active assoc-in [run-id :own-child!] own-child!)
      true)))

(defn register-settlement-observer!
  "Attach read-only facts from the original native owner. Private callbacks
   are never persisted, forked, or returned by Run snapshots."
  [room run-id observe & [reconcile]]
  (locking lifecycle-lock
    (when-let [entry (get @active run-id)]
      (when (and (= (:id room) (get-in entry [:run :run/room]))
                 (identical? (:ctx room) (:execution-context entry)))
        (swap! active update run-id assoc :settlement-observer observe :settlement-reconciler reconcile)
        true))))

(defn observe-settlement
  "HOST read-only facts for this exact live Room incarnation. Missing native
   owner is unknown, not a durable acknowledgement of any family obligation."
  [room run-id]
  (let [entry (locking lifecycle-lock
                (let [value (get @active run-id)]
                  (when (and (= (:id room) (get-in value [:run :run/room]))
                             (identical? (:ctx room) (:execution-context value))) value)))
        facts (if-let [observe (:settlement-observer entry)] (observe)
                      {:reservation-reconciliation :unknown :usage-consumption :not-established})]
    (assoc facts :family
           (if-not (and entry (family/valid? (:family entry))) {:admission :unknown :return :unknown}
                   {:admission (if (get-in entry [:family :sealed?]) :sealed :open)
                    :return (if (family/quiescent? (:family entry)) :acknowledged :pending)
                    :leases (mapv (fn [[token value]]
                                    (assoc (select-keys value [:phase :child-id :callback-exited? :child-returned?
                                                               :preparation-safe? :publication-acknowledged? :cancelled?])
                                           :token token :kind :child-admission
                                           :revision (:recovery-revision value)
                                           :recovery-running? (boolean (:recovery-running? value))
                                           :error-type (:type (ex-data (or (:admission-error value) (:release-error value))))
                                           :error-class (some-> (or (:admission-error value) (:release-error value)) class .getName)))
                                  (get-in entry [:family :leases]))}))))

(defn- reconcile-child-admission! [room parent expected]
  (let [token (:token expected)
        owner (locking lifecycle-lock (:native-owner (family-entry! room parent)))
        claimed (change-family!
                 parent owner
                 (fn [state]
                   (let [value (family/lease state token)]
                     (when-not (and (uuid? (:revision expected))
                                    (= (:revision expected) (:recovery-revision value))
                                    (= (:phase expected) (:phase value))
                                    (#{:preparing :starting} (:phase value))
                                    (:callback-exited? value) (not (:recovery-running? value)))
                       (throw (ex-info "Child admission recovery changed" {:type ::stale-child-recovery})))
                     (assoc-in state [:leases token :recovery-running?] true))))
        value (family/lease claimed token)]
    (try
      (when (= :starting (:phase value))
        ;; Persistence and disposal are outside all native lifecycle locks. The
        ;; original callback has returned; an executing child is never disposed.
        (when (get @active (:child-id value))
          (throw (ex-info "Child admission already has an executing owner" {:type ::child-already-admitted})))
        (let [fence (:start-fence value) st (:store room) cid (store/conversation-id room)]
          (when-not (and st fence (store/-store-run! st cid fence)
                         (= fence (store/-load-run st cid (:child-id value))))
            (throw (ex-info "Failed admission fence is not acknowledged" {:type ::admission-fence-unconfirmed})))
          (change-family! parent owner #(family/admission-fenced % token (:child-id value)))))
      (when-not (:preparation-safe? value)
        (if-let [cleanup! (:preparation-cleanup! value)]
          (cleanup!)
          (throw (ex-info "Original preparation cleanup is unavailable" {:type ::preparation-owner-unavailable}))))
      (when-let [fence (:start-fence value)]
        (when (:run/world fence)
          (let [final (assoc fence :run/settlement-status :discarded :run/settlement-reason :admission-failed)
                st (:store room) cid (store/conversation-id room)]
            (when-not (and (store/-store-run! st cid final)
                           (= final (store/-load-run st cid (:child-id value))))
              (throw (ex-info "Disposed admission projection is not acknowledged" {:type ::admission-disposal-unconfirmed}))))))
      (change-family! parent owner
                      #(-> (family/preparation-cleaned % token)
                           (update-in [:leases token] dissoc :recovery-running? :recovery-revision)))
      nil
      (catch Throwable error
        (change-family! parent owner
                        #(-> % (assoc-in [:leases token :recovery-running?] false)
                             (assoc-in [:leases token :recovery-revision] (random-uuid))
                             (assoc-in [:leases token :admission-error] error)))
        (throw error)))))

(defn reconcile-settlement!
  "HOST-only advance of an original native owner's supported held obligation.
   Expected launch/phase tokens are coordination facts, never permission. No
   new inference, grant, worker code or replacement Room may be supplied."
  [room run-id expected]
  (when (or (simple/on-drain-thread?) (.get held-run) (Thread/holdsLock lifecycle-lock))
    (throw (ex-info "Native settlement recovery requires an unlocked HOST worker" {:type ::engine-thread})))
  (binding [ec/*execution-context* (:ctx room) ec/*spin-id* nil simple/*in-drain?* false]
    (if (= :child-admission (:kind expected))
      (reconcile-child-admission! room run-id expected)
      (let [reconcile (locking lifecycle-lock (:settlement-reconciler (family-entry! room run-id)))]
        (when-not reconcile
          (throw (ex-info "Original native owner has no supported recovery adapter" {:type ::recovery-unavailable})))
        (reconcile expected)))))

(defn with-owned-child!
  "Admit under the original live native Run family. Program supervisors attach
   their existing preparation/cleanup hooks; Jobs use the same canonical lease."
  [room run-id admit-child!]
  (let [owner (locking lifecycle-lock (:own-child! (family-entry! room run-id)))]
    (if owner (owner admit-child!)
        (with-family-admission! room run-id admit-child!))))

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

(def orphaned-reason
  "The `:run/reason` of a Run that was still running when its process stopped."
  :orphaned)

(defn reconcile-orphaned-runs!
  "Fail every Run `room`'s store records as `:running` that no live execution
   in this process owns: its process stopped while it ran, so nothing will
   finish it, and it would read as running forever. `:waiting` Runs are left
   alone; waiting is a state a Run may rest in. Call when a durable Room is
   opened, before it admits work. Returns the Runs it failed."
  [room]
  (if-let [room-store (:store room)]
    (let [key (store/conversation-id room)]
      (locking lifecycle-lock
        (let [now (java.util.Date.)]
          (->> (store/-list-runs room-store key {:status :running :limit 100000})
               (remove #(contains? @active (:run/id %)))
               (keep (fn [r]
                       (let [failed (assoc r
                                           :run/status :failed
                                           :run/updated-at now
                                           :run/ended-at now
                                           :run/reason orphaned-reason
                                           :run/error "The process stopped while this Run was running")]
                         (when (store/-store-run! room-store key failed)
                           failed))))
               vec))))
    []))

(defn run
  "Load one durable Run from its Room store."
  [room run-id]
  (when-let [room-store (:store room)]
    (store/-load-run room-store (store/conversation-id room) run-id)))

(defn runs
  "List durable Runs from a Room store.

   Options: :limit, :status, :actor, and :root-run-id. The structural root
   restricts the query to that Run and descendants before applying the limit."
  ([room] (runs room {}))
  ([room opts]
   (if-let [room-store (:store room)]
     (store/-list-runs room-store (store/conversation-id room) opts)
     [])))
