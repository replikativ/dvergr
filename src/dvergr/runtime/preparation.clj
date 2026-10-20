(ns dvergr.runtime.preparation
  "Process-local, physically joined preparation leases for joined participants.
   These are runtime resources, never copied into a child Room's metadata."
  (:require [dvergr.room.registry :as registry]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.engine.impl.simple :as simple]))

(def leases-key ::leases)
(def ^:private max-room-preparations 64)

(defn- admitted? [room]
  (binding [ec/*execution-context* (:ctx room)]
    (registry/admitted-incarnation? room)))

(defn- prune! [room]
  (swap! (:meta room) update leases-key
         (fn [leases]
           (into {} (remove (fn [[_ lease]] ((:finished? lease)))) leases))))

(defn- live-participant? [{:keys [room participant]}]
  (let [current (get @(:participants room) (:id participant))]
    (and (some? (:active participant))
         (= (:participant/incarnation participant)
            (:participant/incarnation current))
         (identical? (:active participant) (:active current))
         (not= :dvergr.discourse/participant-closed @(:active participant)))))

(defn admit!
  "Retain a lease BEFORE dispatch. `cancel!` must not block; `join!` must join
   physical exit, not Future cancellation. Admission never waits for capacity."
  [room participant {:keys [cancel! join! finished?] :as callbacks}]
  (when-not (every? fn? [cancel! join! finished?])
    (throw (ex-info "Preparation requires physical lifecycle callbacks" {})))
  (locking (:meta room)
    (prune! room)
    (let [leases (get @(:meta room) leases-key)
          lease (merge callbacks {:id (random-uuid) :kind :preparation :room room
                                  :agent-id (:id participant)
                                  :participant participant :state (atom :pending)})]
      (when-not (and (admitted? room)
                     (nil? (:dvergr/fork-transfer-state @(:meta room)))
                     (live-participant? lease))
        (throw (ex-info "Participant preparation admission is closed"
                        {:type ::admission-closed :room-id (:id room)})))
      (when (or (>= (count leases) max-room-preparations)
                (some #(and (identical? (:active participant)
                                        (:active (:participant %)))
                            (not (when-let [ready? (:admission-ready? %)] (ready?))))
                      (vals leases)))
        (throw (ex-info "Participant preparation capacity exhausted"
                        {:type ::capacity-exhausted :room-id (:id room)})))
      (swap! (:meta room) assoc-in [leases-key (:id lease)] lease)
      lease)))

(defn with-current!
  "Make a short publication/authority decision under the exact Room lease.
   Cancellation uses this same monitor. Never construct or release SCI here."
  [{:keys [room state id] :as lease} f]
  (locking (:meta room)
    (when-not (and (= :preparation (:kind lease))
                   (identical? lease (get-in @(:meta room) [leases-key id]))
                   (= :pending @state)
                   (live-participant? lease)
                   (admitted? room)
                   (nil? (:dvergr/fork-transfer-state @(:meta room))))
      (throw (ex-info "Participant preparation is no longer current"
                      {:type ::stale-preparation :room-id (:id room)})))
    (f)))

(defn cancel!
  "Fence a lease before requesting cancellation, without blocking the engine."
  [{:keys [room state cancel!] :as lease}]
  (locking (:meta room) (reset! state :cancelled))
  (cancel!)
  lease)

(defn cancel-participant! [room participant]
  (let [leases (locking (:meta room)
                 (vec (filter #(and (= :preparation (:kind %))
                                    (identical? (:active participant)
                                                (:active (:participant %))))
                              (vals (get @(:meta room) leases-key)))))]
    (doseq [lease leases] (cancel! lease)))
  nil)

(defn- drain! [room leases]
  (when (and (simple/on-drain-thread?) (seq leases))
    (throw (ex-info "Cannot join participant preparation on the engine"
                    {:type ::blocking-drain :room-id (:id room)})))
  (let [deadline (+ (System/nanoTime) 5000000000)
        failures (atom [])]
    (doseq [lease leases]
      (try (cancel! lease)
           (catch Throwable t (swap! failures conj t))))
    (doseq [lease leases]
      (try ((:join! lease) (max 0 (quot (- deadline (System/nanoTime)) 1000000)))
           (when-not ((:finished? lease))
             (throw (ex-info "Preparation join did not acknowledge physical exit"
                             {:type ::unconfirmed-drain})))
           (catch Throwable t (swap! failures conj t))))
    (locking (:meta room) (prune! room))
    (when-let [error (first @failures)]
      (throw (ex-info "Participant preparation did not quiesce"
                      {:type ::drain-failed :room-id (:id room)} error))))
  nil)

(defn drain-room! [room]
  (drain! room (locking (:meta room)
                 (vec (vals (get @(:meta room) leases-key))))))

(defn drain-agent! [room agent-id]
  (drain! room (locking (:meta room)
                 (vec (filter #(= agent-id (:agent-id %))
                              (vals (get @(:meta room) leases-key)))))))

(defn drain-participant! [room participant]
  (drain! room (locking (:meta room)
                 (vec (filter #(and (= :preparation (:kind %))
                                    (identical? (:active participant)
                                                (:active (:participant %))))
                              (vals (get @(:meta room) leases-key)))))))

(defn assert-quiescent!
  "Refuse a snapshot while a worker can still modify this execution world."
  [room]
  (locking (:meta room)
    (prune! room)
    (when (seq (get @(:meta room) leases-key))
      (throw (ex-info "Room has live participant preparation"
                      {:type ::not-quiescent :room-id (:id room)}))))
  nil)

(defn assert-agent-quiescent! [room agent-id]
  (locking (:meta room)
    (prune! room)
    (when (some #(= agent-id (:agent-id %))
                (vals (get @(:meta room) leases-key)))
      (throw (ex-info "Agent has live participant preparation"
                      {:type ::not-quiescent :room-id (:id room) :agent-id agent-id}))))
  nil)

(defn retain-cleanup!
  "Retain an entry before detaching it, until native cleanup acknowledges exit.
   Distinct from participant admission: leaving must never interrupt cleanup.
   Caller holds the Room monitor across retention and cache detachment."
  [room agent-id resource]
  (when (simple/on-drain-thread?)
    (throw (ex-info "Context retirement requires a host worker"
                    {:type ::blocking-cleanup :room-id (:id room)})))
  (locking (:meta room)
    (assert-agent-quiescent! room agent-id)
    (when-not (and (admitted? room)
                   (nil? (:dvergr/fork-transfer-state @(:meta room))))
      (throw (ex-info "Context retirement admission is closed"
                      {:type ::admission-closed :room-id (:id room)})))
    (when (>= (count (get @(:meta room) leases-key)) max-room-preparations)
      (throw (ex-info "Context retirement capacity exhausted"
                      {:type ::capacity-exhausted :room-id (:id room)})))
    (let [completion (promise)
          lease {:id (random-uuid) :kind :cleanup :room room :agent-id agent-id
                 :resource resource :state (atom :pending) :completion completion
                 :cancel! (constantly nil)
                 :finished? #(true? (deref completion 0 nil))
                 :join! (fn [timeout-ms]
                          (let [outcome (deref completion timeout-ms ::pending)]
                            (when-not (true? outcome)
                              (throw (ex-info "Context cleanup has not succeeded"
                                              {:type ::cleanup-unconfirmed}
                                              (when (instance? Throwable outcome) outcome))))))}]
      (swap! (:meta room) assoc-in [leases-key (:id lease)] lease)
      lease)))

(defn complete-cleanup! [lease error]
  (deliver (:completion lease) (or error true))
  (when-not error
    ;; No interpreter/context access follows the host acknowledgement. Drop the
    ;; successful resource handle promptly rather than retaining a whole SCI
    ;; heap until the next Room operation. Failed owners remain quarantined.
    (let [room (:room lease)]
      (locking (:meta room)
        (when (identical? lease (get-in @(:meta room) [leases-key (:id lease)]))
          (swap! (:meta room) update leases-key dissoc (:id lease))))))
  nil)

(defn retain-room-cleanup!
  "Retain a detached cache batch until every exact entry acknowledges release.
   Called under the Room monitor by its pre-unregister hook, while that exact
   incarnation is still registered. Unlike ordinary failed worker cleanup, this
   host-owned, idempotent release can be retried by a later physical drain."
  [room entries release!]
  (when (simple/on-drain-thread?)
    (throw (ex-info "Room cleanup requires a host thread"
                    {:type ::blocking-cleanup :room-id (:id room)})))
  (locking (:meta room)
    (prune! room)
    (when-not (binding [ec/*execution-context* (:ctx room)]
                (identical? room (registry/lookup (:id room))))
      (throw (ex-info "Room cleanup no longer owns the registered incarnation"
                      {:type ::admission-closed :room-id (:id room)})))
    (when (and (seq entries)
               (>= (count (get @(:meta room) leases-key)) max-room-preparations))
      (throw (ex-info "Room cleanup capacity exhausted"
                      {:type ::capacity-exhausted :room-id (:id room)})))
    (let [remaining (atom entries)
          monitor (Object.)
          lease {:id (random-uuid) :kind :room-cleanup :room room
                 :resource remaining :state (atom :pending)
                 :cancel! (constantly nil)
                 :finished? #(empty? @remaining)
                 :join! (fn [_]
                          ;; The lifecycle reservation excludes competing close
                          ;; operations. Keep the receipt itself serialized too:
                          ;; no exact resource may be released concurrently.
                          (locking monitor
                            (let [failures (atom [])]
                              (doseq [[key entry] @remaining]
                                (try
                                  (release! entry)
                                  (swap! remaining
                                         #(if (identical? entry (get % key))
                                            (dissoc % key) %))
                                  (catch Throwable error (swap! failures conj error))))
                              (when-let [error (first @failures)]
                                (throw (ex-info "Room context cleanup is incomplete"
                                                {:type ::cleanup-unconfirmed
                                                 :room-id (:id room)
                                                 :remaining (count @remaining)} error))))))}]
      (when (seq entries)
        ;; Retain BEFORE the caller detaches these entries from its live cache.
        (swap! (:meta room) assoc-in [leases-key (:id lease)] lease))
      lease)))

(registry/add-pre-unregister-hook! ::drain drain-room!)
(registry/add-pre-register-hook!
 ::replacement
 (fn [room]
   (when-let [previous (registry/lookup (:id room))]
     (when-not (= (:incarnation previous) (:incarnation room))
       (assert-quiescent! previous)))))
