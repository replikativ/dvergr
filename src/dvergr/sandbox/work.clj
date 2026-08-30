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

(deftype Controller [native room-id owner-context taps ceiling])

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
    (swap! rooms* update-in [(.-room-id controller) :controllers]
           (fn [controllers] (disj (or controllers #{}) controller))))
  nil)

(defn create!
  "Create and register an opaque, room-owned controller."
  [room-id owner-context ceiling strategy opts work-fn]
  (ensure-room! room-id)
  (let [ceiling (normalize-ceiling ceiling)
        opts (bounded-opts strategy ceiling opts)]
    (locking lifecycle-lock
      (let [{:keys [state controllers]
             :or {state :open controllers #{}}} (get @rooms* room-id)]
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
              controller (Controller. native room-id owner-context (atom #{}) ceiling)]
          (swap! rooms* assoc room-id
                 {:state :open :controllers (conj controllers controller)})
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

(defn- await-controller! [^Controller controller deadline-ms]
  (let [settled (promise)
        ctx (.-owner-context controller)]
    (binding [ec/*execution-context* ctx]
      (work/cancel! (.-native controller))
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
  "Fence, cancel, and join every SCI controller owned by `room`. Idempotent."
  [room]
  (when room
    (let [room-id (:id room)
          controllers
          (locking lifecycle-lock
            (let [controllers (get-in @rooms* [room-id :controllers] #{})]
              (swap! rooms* assoc room-id
                     {:state :closing :controllers controllers})
              controllers))
          deadline (+ (System/currentTimeMillis) 5000)]
      (doseq [controller controllers]
        (await-controller! controller deadline))))
  nil)

(defn- reopen-room! [room]
  (locking lifecycle-lock
    (swap! rooms* assoc (:id room) {:state :open :controllers #{}}))
  nil)

;; Registration reopens a reused durable room id. Pre-unregister is the common
;; settlement boundary for close, fork discard, and merge cleanup.
(room-registry/add-register-hook! ::work-admission reopen-room!)
(room-registry/add-pre-unregister-hook! ::work-admission close-room-work!)
