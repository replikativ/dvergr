(ns dvergr.sandbox.work
  "Room-owned capability boundary for SCI work-admission controllers.

   Native Spindel controllers intentionally remain process-local. This namespace
   gives them an opaque SCI handle, bounds their resource fan-out, and ties them
   to the Room lifecycle so a discarded fork cannot leave work running on the
   root executor. The registry contains handles only; semantic state and public
   projections remain in Spindel."
  (:require [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.work :as work]
            [dvergr.room.registry :as room-registry]))

(def default-ceiling
  {:controllers 16
   :concurrency 8
   :capacity 1024
   :ingress-capacity 1024
   :event-taps 8})

(deftype Controller [native room-id generation owner-context taps ceiling])

(defonce ^:private lifecycle-lock (Object.))
(defonce ^:private rooms* (atom {}))

(defn- positive-limit! [ceiling k]
  (let [value (get ceiling k)]
    (when-not (and (integer? value) (pos? value))
      (throw (ex-info "SCI work-admission ceiling must be a positive integer"
                      {:type ::invalid-ceiling :limit k :value value})))
    value))

(defn normalize-ceiling
  "Validate an optional attenuation of the host's SCI work-admission limits."
  [ceiling]
  (let [unknown (seq (remove (set (keys default-ceiling)) (keys ceiling)))
        effective (merge default-ceiling ceiling)]
    (when unknown
      (throw (ex-info "Unknown SCI work-admission ceiling keys"
                      {:type ::invalid-ceiling :unknown-keys (vec unknown)})))
    (doseq [k (keys default-ceiling)]
      (positive-limit! effective k)
      (when (> (get effective k) (get default-ceiling k))
        (throw (ex-info "SCI work-admission ceiling cannot exceed the host maximum"
                        {:type ::invalid-ceiling
                         :limit k
                         :requested (get effective k)
                         :maximum (get default-ceiling k)}))))
    effective))

(defn- native-controller [controller]
  (if (instance? Controller controller)
    (.-native ^Controller controller)
    (throw (ex-info "Expected a spindel.work controller"
                    {:type ::invalid-controller}))))

(defn- ensure-room! [room-id]
  (when-not room-id
    (throw (ex-info "Structured work admission requires a room-scoped SCI session"
                    {:type ::room-required}))))

(defn- bounded-opts [strategy ceiling opts]
  (let [allowed #{:concurrency :capacity :ingress-capacity}
        unknown (seq (remove allowed (keys opts)))
        defaults {:concurrency (if (= strategy :parallel)
                                 (min 4 (:concurrency ceiling))
                                 1)
                  :capacity (min 1024 (:capacity ceiling))
                  :ingress-capacity (min 1024 (:ingress-capacity ceiling))}
        effective (merge defaults opts)]
    (when unknown
      (throw (ex-info "Unknown SCI work-admission options"
                      {:type ::invalid-options :unknown-keys (vec unknown)})))
    (doseq [[option limit] [[:concurrency :concurrency]
                            [:capacity :capacity]
                            [:ingress-capacity :ingress-capacity]]]
      (let [value (get effective option)]
        (when-not (and (integer? value)
                       (if (= option :capacity) (not (neg? value)) (pos? value)))
          (throw (ex-info "SCI work-admission option is outside its numeric domain"
                          {:type ::invalid-options :option option :value value})))
        (when (> value (get ceiling limit))
          (throw (ex-info "SCI work-admission option exceeds this sandbox's ceiling"
                          {:type ::ceiling-exceeded
                           :option option
                           :requested value
                           :ceiling (get ceiling limit)})))))
    effective))

(defn- forget-controller! [^Controller controller]
  (locking lifecycle-lock
    (let [room-id (.-room-id controller)
          generation (.-generation controller)]
      (swap! rooms*
             (fn [rooms]
               (if (= generation (get-in rooms [room-id :generation]))
                 (update-in rooms [room-id :controllers]
                            (fn [controllers] (disj (or controllers #{}) controller)))
                 rooms)))))
  nil)

(defn create!
  "Create and register an opaque, room-owned controller."
  [room-id owner-context ceiling strategy opts work-fn]
  (ensure-room! room-id)
  (let [ceiling (normalize-ceiling ceiling)
        opts (bounded-opts strategy ceiling opts)]
    (locking lifecycle-lock
      (let [{:keys [state controllers generation]
             lifecycle-owner :owner-context
             :as lifecycle} (get @rooms* room-id)]
        (when-not lifecycle
          (throw (ex-info "Room work admission has no live lifecycle owner"
                          {:type ::room-not-registered :room-id room-id})))
        (when-not (identical? lifecycle-owner owner-context)
          (throw (ex-info "SCI session does not own this Room incarnation"
                          {:type ::stale-room-incarnation :room-id room-id})))
        (when-not (= :open state)
          (throw (ex-info "Room work admission is closed for teardown"
                          {:type ::admission-closed :room-id room-id})))
        (when (>= (count controllers) (:controllers ceiling))
          (throw (ex-info "SCI work-admission controller limit reached"
                          {:type ::ceiling-exceeded
                           :limit :controllers
                           :ceiling (:controllers ceiling)})))
        (let [native (binding [ec/*execution-context* owner-context]
                       (work/work-admission (assoc opts :strategy strategy) work-fn))
              controller (Controller. native room-id generation owner-context
                                      (atom #{}) ceiling)]
          (swap! rooms* update-in [room-id :controllers] conj controller)
          ;; A passive completion observer releases the allocation slot. It does
          ;; not own/cancel the controller and cannot be abandoned by SCI.
          (binding [ec/*execution-context* owner-context]
            ((work/completion native)
             (fn [_] (forget-controller! controller))
             (fn [_] (forget-controller! controller))))
          controller)))))

(defn submit!
  ([controller value]
   (work/submit! (native-controller controller) value))
  ([controller id value]
   (work/submit! (native-controller controller) id value)))

(defn snapshot [controller]
  (work/snapshot (native-controller controller)))

(defn completion [controller]
  (work/completion (native-controller controller)))

(defn close! [controller]
  (work/close! (native-controller controller)))

(defn cancel! [controller]
  (work/cancel! (native-controller controller)))

(defn events! [^Controller controller]
  (when-not (instance? Controller controller)
    (native-controller controller))
  (let [taps (.-taps controller)
        ceiling (get (.-ceiling controller) :event-taps)]
    (locking taps
      (when (>= (count @taps) ceiling)
        (throw (ex-info "SCI work-admission event-tap limit reached"
                        {:type ::ceiling-exceeded
                         :limit :event-taps
                         :ceiling ceiling})))
      (let [tap (work/events (.-native controller))]
        (swap! taps conj tap)
        tap))))

(defn untap! [^Controller controller event-source]
  (when-not (instance? Controller controller)
    (native-controller controller))
  (let [taps (.-taps controller)]
    (locking taps
      (when-not (contains? @taps event-source)
        (throw (ex-info "Event source does not belong to this controller"
                        {:type ::foreign-event-source})))
      (work/untap-events! (.-native controller) event-source)
      (swap! taps disj event-source)))
  nil)

(defn- cancel-controller! [^Controller controller]
  (binding [ec/*execution-context* (.-owner-context controller)]
    (work/cancel! (.-native controller))))

(defn- await-controller! [^Controller controller deadline-ms]
  (let [settled (promise)
        ctx (.-owner-context controller)]
    (binding [ec/*execution-context* ctx]
      ((work/completion (.-native controller))
       #(deliver settled [:ok %])
       #(deliver settled [:error %])))
    (let [remaining (max 0 (- deadline-ms (System/currentTimeMillis)))
          result (deref settled remaining ::timeout)]
      (if (= ::timeout result)
        (throw (ex-info "Room teardown timed out waiting for SCI work"
                        {:type ::teardown-timeout
                         :room-id (.-room-id controller)
                         :snapshot (binding [ec/*execution-context*
                                             (.-owner-context controller)]
                                     (work/snapshot (.-native controller)))}))
        (case (first result)
          :ok nil
          :error (throw (ex-info "SCI work controller failed during Room teardown"
                                 {:type ::teardown-failed
                                  :room-id (.-room-id controller)}
                                 (second result))))))))

(defn close-room-work!
  "Fence, broadcast cancellation to, and join every SCI controller owned by
   `room`. Returns the fence generation. Repeated calls join the same fence."
  [room]
  (when room
    (let [room-id (:id room)
          {:keys [controllers fence]}
          (locking lifecycle-lock
            (when-let [lifecycle (get @rooms* room-id)]
              (when-not (identical? (:owner-context lifecycle) (:ctx room))
                (throw (ex-info "Room instance does not own its work lifecycle"
                                {:type ::stale-room-incarnation :room-id room-id})))
              (let [fence (or (:fence lifecycle) (random-uuid))
                    lifecycle (assoc lifecycle :state :closing :fence fence)]
                (swap! rooms* assoc room-id lifecycle)
                {:controllers (:controllers lifecycle) :fence fence})))
          deadline (+ (System/currentTimeMillis) 5000)
          failures (transient [])]
      ;; Cancellation is a broadcast. A broken controller must not prevent its
      ;; siblings from receiving the teardown signal.
      (doseq [controller controllers]
        (try
          (cancel-controller! controller)
          (catch Throwable error
            (conj! failures {:phase :cancel :controller controller :error error}))))
      ;; Then join every controller against one common deadline, aggregating all
      ;; failures so callers can retry or enter explicit recovery.
      (doseq [controller controllers]
        (try
          (await-controller! controller deadline)
          (catch Throwable error
            (conj! failures {:phase :join :controller controller :error error}))))
      (let [failures (persistent! failures)]
        (when (seq failures)
          (throw (ex-info "Room teardown failed waiting for SCI work"
                          {:type ::room-work-teardown-failed
                           :room-id room-id
                           :fence fence
                           :failures (mapv #(select-keys % [:phase]) failures)}
                          (:error (first failures)))))
        fence))))

(defn recover-room-work!
  "Reopen exactly the fenced Room incarnation identified by `fence`. Returns
   true when recovery won, false for a stale/missing fence."
  [room fence]
  (when (and room fence)
    (locking lifecycle-lock
      (let [room-id (:id room)
            lifecycle (get @rooms* room-id)]
        (when (and (= fence (:fence lifecycle))
                   (= :closing (:state lifecycle))
                   (identical? (:owner-context lifecycle) (:ctx room)))
          (swap! rooms* assoc room-id
                 (-> lifecycle (assoc :state :open) (dissoc :fence)))
          true)))))

(defn- register-room! [room]
  (let [room-id (:id room)
        owner-context (:ctx room)]
    (locking lifecycle-lock
      (if-let [lifecycle (get @rooms* room-id)]
        (cond
          (not (identical? owner-context (:owner-context lifecycle)))
          (throw (ex-info "Room id is still owned by another live incarnation"
                          {:type ::room-incarnation-conflict :room-id room-id}))

          (not= :open (:state lifecycle))
          (throw (ex-info "Room work lifecycle is fenced"
                          {:type ::admission-closed :room-id room-id}))

          :else
          ;; Idempotent refresh of the same Room context preserves controllers,
          ;; ceilings, and the lifecycle generation.
          nil)
        (swap! rooms* assoc room-id
               {:state :open
                :generation (random-uuid)
                :owner-context owner-context
                :controllers #{}}))))
  nil)

(defn- unregister-room! [room-id]
  ;; The pre-unregister fence leaves this incarnation :closing. Removing it
  ;; makes stale SCI sessions fail closed instead of attaching to a later Room
  ;; that happens to reuse the durable id.
  (locking lifecycle-lock
    (swap! rooms* dissoc room-id))
  nil)

;; The safety hook runs before add-or-replace registration and may reject a
;; stale incarnation. Pre-unregister is the common settlement boundary for
;; close, fork discard, and merge cleanup.
(room-registry/add-pre-register-hook! ::work-admission register-room!)
(room-registry/add-pre-unregister-hook! ::work-admission close-room-work!)
(room-registry/add-unregister-hook! ::work-admission unregister-room!)
