(ns dvergr.participant
  "Local session incarnations, owned handlers and reply obligations."
  (:require [dvergr.discourse :as discourse]
            [dvergr.room :as room]
            [dvergr.store :as store]
            [dvergr.value :as v]
            [dvergr.world.systems :as systems]
            [is.simm.partial-cps.sequence :as aseq]
            [org.replikativ.spindel.effects.await :refer [await]]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.pubsub.buffer :as buffer]
            [org.replikativ.spindel.pubsub.mult :as mult]
            [org.replikativ.spindel.spin.core :as spin-core]
            [org.replikativ.spindel.spin.cps :refer [spin]]
            [org.replikativ.spindel.spin.sync :as sync]))

(deftype ^:private Obligation [session ask request suppressed])
(alter-meta! #'->Obligation assoc :private true)

(defn- session! [installation session]
  (when-not (identical? installation (:installation session)) (v/fail! :operation-denied {}))
  (when @(:closed session) (v/fail! :closed {}))
  (room/open! installation (get-in session [:venue :room]))
  session)

(defn- obligation! [installation session obligation]
  (session! installation session)
  (when-not (and (instance? Obligation obligation)
                 (identical? session (.-session ^Obligation obligation)))
    (v/fail! :invalid-obligation {})))

(defn suppress!
  "Release handler reply responsibility; timeout stays owned."
  [installation session obligation]
  (obligation! installation session obligation)
  (reset! (.-suppressed ^Obligation obligation) true)
  nil)

(defn reply!
  "Validate the caller's selected world, persist a reply, then arbitrate."
  [installation session obligation spec]
  (let [result (locking (:lock installation)
                 (obligation! installation session obligation)
                 (let [venue (:venue session) r (:room venue) ^Obligation o obligation
                       selected (systems/selected! installation (get-in r [:record :room/world]))
                       _ (systems/resolve! installation (:system venue) :discourse/write)
                       intent (:ask/intent (store/ask-record (:control installation) (.-ask o)))]
                   (when-not (= (get-in selected [:descriptor :world/id]) (get-in r [:record :room/world]))
                     (v/fail! :effect-boundary-unavailable {}))
                   (when-not (and (= (:id session) (:session intent))
                                  (= (.-request o) (:message/id intent))
                                  (= (:participant/id (:identity session)) (:target intent))
                                  (or (nil? (:reply-to spec)) (= (.-request o) (:reply-to spec)))
                                  (or (nil? (:ask/id spec)) (= (.-ask o) (:ask/id spec))))
                     (v/fail! :invalid-reply {}))
                   (let [result (discourse/offer! installation venue
                                                  (discourse/persist! installation venue
                                                                      (assoc (:identity session) :session/id (:id session))
                                                                      (assoc spec :ask/id (.-ask o) :reply-to (.-request o))))]
                     result)))]
    (discourse/finish! installation (get-in session [:venue :room]) (.-ask ^Obligation obligation)
                       {:status :reply :message/id (:id spec)})
    (reset! (.-suppressed ^Obligation obligation) true)
    result))

(defn subscribe!
  "Add a matching rule without resetting the seen set."
  [installation session selector]
  (locking (:lock installation)
    (session! installation session) (discourse/selector! selector)
    (let [id (random-uuid)] (swap! (:rules session) assoc id selector) id)))

(defn unsubscribe!
  "Remove a matching rule idempotently."
  [installation session id]
  (when-not (identical? installation (:installation session)) (v/fail! :operation-denied {}))
  (swap! (:rules session) dissoc id) nil)

(defn- complete! [session]
  (when (and @(:exited session) @(:detached session) (zero? @(:cancelling session)))
    (sync/deliver! (:completion session) {:status :closed})))

(defn leave!
  "Fence admission; retry durable ending evidence before detaching and joining."
  [installation session]
  (when-not (identical? installation (:installation session)) (v/fail! :operation-denied {}))
  (let [r (get-in session [:venue :room]) owner (:owner r)]
    (locking (:lock installation) (reset! (:closed session) true))
    (binding [ec/*execution-context* (:context (:world r))]
      (locking (:cleanup-lock session)
        (when-not @(:detached session)
          (store/end-session! (:control installation) (:id session) (:process installation))
          (doseq [[id entry] @(:asks owner) :when (= (:id session) (get-in entry [:intent :session]))]
            (discourse/finish! installation r id {:status :leave :reason :participant-left}))
          (swap! (:cancelling session) inc)
          (try
            (mult/untap (:mult r) (:tap session))
            (room/cancel-task! installation r @(:child session))
            (reset! (:detached session) true)
            (locking (:lock installation)
              (when (= (:id session) (:id (get @(:sessions owner) (:key session))))
                (swap! (:sessions owner) dissoc (:key session))))
            (finally (swap! (:cancelling session) dec) (complete! session)))))
      (:completion session))))

(defn- claim! [installation session envelope]
  (locking (:lock installation)
    (when (and (not @(:closed session))
               (discourse/request-eligible? installation (:id session) envelope)
               (some #(discourse/matches? (:venue session) (:identity session) false % envelope)
                     (vals @(:rules session)))
               (not (contains? @(:seen session) (:message/id envelope))))
      (if (>= (count @(:seen session)) (:capacity session)) :capacity
          (do (swap! (:seen session) conj (:message/id envelope)) :claimed)))))

(defn- obligation [installation session envelope]
  (when-let [id (:ask/id envelope)]
    (let [intent (:ask/intent (store/ask-record (:control installation) id))]
      (when (and (= (:id session) (:session intent)) (= (:message/id envelope) (:message/id intent)))
        (Obligation. session id (:message/id envelope) (atom false))))))

(defn- handler-task [installation session handler envelope o]
  (spin
   (try
     (let [answer (when (and (not @(:closed session))
                             (discourse/request-eligible? installation (:id session) envelope))
                    (handler {:installation installation :session session} envelope o))
           answer (if (instance? org.replikativ.spindel.spin.core.Spin answer)
                    (let [done (sync/deferred) r (get-in session [:venue :room])]
                       ;; Spawn the returned body independently: cancelling an awaiting
                       ;; parent alone is not physical-exit evidence for this child.
                      (reset! (:child session) answer)
                      (room/start! installation r answer
                                   #(sync/deliver! done [:ok %]) #(sync/deliver! done [:error %]))
                      (when @(:closed session) (room/cancel-task! installation r answer))
                      (let [[status value] (await done)]
                        (reset! (:child session) nil)
                        (if (= :error status) (throw value) value))) answer)]
       (when (and o (map? answer) (not @(:closed session)) (not @(.-suppressed ^Obligation o)))
         (reply! installation session o answer)))
     (catch Throwable e
       (when (and o (not @(:closed session)) (not= spin-core/spin-cancelled (:type (ex-data e))))
         (try (reply! installation session o
                      {:id (random-uuid) :recipients :broadcast :type :conversation/error
                       :body {:error :handler-failed}})
              (catch Throwable _ nil)))))))

(defn join!
  "Publish prepared membership under the lock; start its consumer outside it."
  [installation venue author {:keys [handler capacity] :or {capacity 10000} :as opts}]
  (when-not (and (every? #{:handler :capacity} (keys opts)) (ifn? handler) (not (coll? handler))
                 (pos-int? capacity) (<= capacity 100000)) (v/fail! :unsupported-handler {}))
  (let [[session launch]
        (locking (:lock installation)
          (let [r (:room venue) owner (:owner r)
                session (let [identity (discourse/author! installation venue author)
                              key [(:projection/id venue) (:participant/id identity)]
                              session {:installation installation :venue venue :identity identity :key key :id (random-uuid)
                                       :closed (atom false) :detached (atom false) :cleanup-lock (Object.)
                                       :seen (atom #{}) :capacity capacity :child (atom nil) :exited (atom false)
                                       :cancelling (atom 0) :rules (atom {(random-uuid) {:direct? true :broadcast? true}})
                                       :tap (mult/tap (:mult r) (buffer/sliding-buffer 32))
                                       :completion (sync/create-deferred (:context (:world r)))}
                              session (assoc session :close #(leave! installation session))]
                          (when (contains? @(:sessions owner) key)
                            (mult/untap (:mult r) (:tap session)) (v/fail! :already-joined {}))
                          (swap! (:sessions owner) assoc key session) session)
                task (spin
                      (loop []
                        (when-not @(:closed session)
                          (when-let [[envelope _] (await (aseq/anext (:tap session)))]
                            (case (claim! installation session envelope)
                              :capacity (leave! installation session)
                              :claimed (let [done (sync/deferred)]
                                         (room/start! installation r (handler-task installation session handler envelope
                                                                                   (obligation installation session envelope))
                                                      #(sync/deliver! done %) #(sync/deliver! done nil))
                                         (await done)) nil)
                            (recur)))))
                finish (fn [_]
                         (reset! (:exited session) true)
                         (when-not @(:closed session) (leave! installation session))
                         (complete! session))]
            [session (room/prepare! installation r task finish finish)]))]
    (launch)
    session))

(def decision-handle discourse/decision-handle)
(def set-decider! discourse/set-decider!)

(defn decide
  "Resolve the validated decision binding once and retain its version in the ask."
  [installation venue author handle request]
  (when-not (and (= (:projection/id venue) (:projection/id handle))
                 (= (systems/handle-data (:system venue)) (systems/handle-data (:system handle))))
    (v/fail! :operation-denied {}))
  (let [binding (discourse/decision-binding! installation venue (:decision/id handle))]
    (discourse/ask-selected! installation venue author (assoc request :target (:participant/id binding))
                             (select-keys binding [:decision/id :version]))))
