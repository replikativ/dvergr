(ns dvergr.room
  "Fixed world bindings and durable discourse attachment."
  (:require [dvergr.schema :as schema]
            [dvergr.store :as store]
            [dvergr.value :as v]
            [dvergr.world :as world]
            [dvergr.world.systems :as systems]
            [org.replikativ.spindel.engine.core :as ec]
            [is.simm.partial-cps.sequence :as aseq]
            [org.replikativ.spindel.effects.await :refer [await]]
            [org.replikativ.spindel.pubsub.mult :as mult]
            [org.replikativ.spindel.pubsub.buffer :as buffer]
            [org.replikativ.spindel.spin.core :as spin-core]
            [org.replikativ.spindel.spin.cps :refer [spin]]
            [org.replikativ.spindel.spin.sync :as sync]
            [org.replikativ.spindel.world.scope :as scope]))

;; Public scope integration and the only spawn site.
(defn- lease! [owner kind] (scope/begin-activity! (:scope owner) kind))
(defn- release! [owner lease] (scope/end-activity! (:scope owner) lease))
(defn- quiescence [owner] (scope/await-quiescence (:scope owner)))

(defn- spawn-owned! [owner lease task success failure]
  (let [finish (fn [callback value]
                 (try (callback value)
                      (finally (swap! (:spins owner) disj task) (release! owner lease))))]
    (swap! (:spins owner) conj task)
    (sync/spawn! task {:owner (:scope owner)
                       :on-success #(finish success %) :on-error #(finish failure %)})
    task))

(defn ^:no-doc prepare! [installation room task success failure]
  (when-not (identical? installation (:installation room)) (v/fail! :room-offline {}))
  (let [owner (:owner room) lease (lease! owner :spin)]
    (swap! (:spins owner) conj task)
    (fn []
      (binding [ec/*execution-context* (:context (:world room))]
        (spawn-owned! owner lease task success failure)))))

(defn ^:no-doc start! [installation room task success failure]
  ((prepare! installation room task success failure)))

(defn ^:no-doc cancel-task! [installation room task]
  (when task
    (when-not (identical? installation (:installation room)) (v/fail! :room-offline {}))
    ;; cancel-spin! unwinds parents before children. Keep ownership across the
    ;; entire call as well as the independently spawned body callbacks.
    (let [lease (lease! (:owner room) :cancellation)]
      (try (binding [ec/*execution-context* (:context (:world room))]
             (spin-core/cancel-spin! task))
           (finally (release! (:owner room) lease))))))

(defn ^:no-doc finite-source [mailbox end]
  (reify aseq/PAsyncSeq
    (anext [this]
      (fn [resolve reject]
        ((aseq/anext mailbox)
         (fn [[value _]] (resolve (when-not (identical? end value) [value this]))) reject)))))

(defn lookup
  "Read a durable binding without hydrating live state."
  [control room-id]
  (store/room-record control room-id))

(defn world
  "Return this live room's fixed world."
  [installation room]
  (when-not (and (identical? installation (:installation room))
                 (identical? (:world room)
                             (get @(:live installation) (get-in room [:record :room/world]))))
    (v/fail! :room-offline {}))
  (:world room))

(defn ^:no-doc open! [installation room]
  (world installation room)
  (when (and (:owner room) (not @(:closed (:owner room)))
             (not (contains? (get @(:owners installation) (get-in room [:record :room/world])) room)))
    (v/fail! :room-offline {}))
  (when (or (nil? (:owner room)) @(:closed (:owner room))) (v/fail! :closed {}))
  room)

(defn ^:no-doc work! [installation venue operation request]
  (systems/invoke! installation (:system venue) operation request))

(defn venue
  "Return an existing logical conversation projection."
  [installation room projection-id]
  (open! installation room)
  (v/identity! :projection projection-id)
  (let [system (systems/handle installation (:world room)
                               (get-in room [:record :room/discourse :system])
                               #{:discourse/read :discourse/write})
        venue {:system system :projection/id projection-id :room room}
        record (work! installation venue :discourse/read {:command :projection :id projection-id})]
    (when-not (= (get-in room [:record :room/id]) (:room/id record))
      (v/fail! :unknown-projection {}))
    venue))

(defn- create-binding!
  "Create a fixed root binding and optional durable discourse."
  [installation {:keys [id kind assembly discourse] :as spec}]
  (v/identity! :room id)
  (case kind :snapshot (v/fail! :missing-prerequisite {:prerequisite :P1})
        :root nil (v/fail! :unsupported-mode {}))
  (when-not (= (if discourse #{:id :kind :assembly :discourse} #{:id :kind :assembly})
               (set (keys spec))) (v/fail! :invalid-control-record {}))
  (when discourse
    (let [system (first (filter #(= (:id %) (:system discourse)) (:systems assembly)))]
      (when-not (and (= #{:system :projection :retention} (set (keys discourse)))
                     (= :indefinite (:retention discourse)) (uuid? (:projection discourse))
                     (= :datahike (:adapter system))
                     (= schema/discourse-attributes (:attributes system))
                     (= #{:discourse/read :discourse/write} (:operations system)))
        (v/fail! :invalid-discourse-system {}))))
  (locking (:lock installation)
    (when (store/room-record (:control installation) id) (v/fail! :identity-conflict {}))
    (let [root (world/create! installation assembly)
          record (cond-> {:room/id id :room/world (:world/id (world/descriptor installation root))
                          :room/kind :root :schema/version (if discourse 2 1)}
                   discourse (assoc :room/discourse discourse))]
      (try
        (when discourse
          (binding [ec/*execution-context* (:context root)]
            (systems/invoke! installation
                             (systems/handle installation root (:system discourse) #{:discourse/write})
                             :discourse/write
                             {:command :projection
                              :record {:projection/id (:projection discourse) :room/id id
                                       :projection/parent nil :projection/basis nil}})))
        (store/put-room! (:control installation) record)
        (if-not discourse
          {:installation installation :record record :world root :closure (atom nil)}
          (let [owner {:scope (scope/create {:purpose :discourse}) :spins (atom #{})
                       :closed (atom false) :sessions (atom {}) :observers (atom {}) :asks (atom {})
                       :stopped (atom false) :cleanup-lock (Object.) :closure (sync/create-deferred (:context root))}
                owner (assoc owner :lifetime (lease! owner :room))
                mailbox (sync/create-mailbox (:context root)) end (Object.)
                room {:installation installation :world root :record record :owner owner
                      :mailbox mailbox :end end :mult (mult/mult (finite-source mailbox end))}]
            (swap! (:owners installation) update (:room/world record) (fnil conj #{}) room)
            room))
        (catch Throwable e (world/close! installation root) (throw e))))))

(defn create!
  "Create durable bindings, then start owned delivery outside the installation lock."
  [installation spec]
  (let [room (create-binding! installation spec)]
    (when (:owner room)
      (binding [ec/*execution-context* (:context (:world room))]
        (let [tap (mult/tap (:mult room) (buffer/sliding-buffer 1))]
          (mult/start! (:mult room))
          (mult/untap (:mult room) tap)
          (start! installation room (spin (await (mult/mult-pump (:mult room))))
                  (fn [_]) (fn [_])))))
    room))

(defn close!
  "Fence admission; retry unfinished cleanup and join physical scope exit."
  [installation room]
  (when-not (identical? installation (:installation room)) (v/fail! :room-offline {}))
  (if-let [owner (:owner room)]
    (do
      (locking (:lock installation) (reset! (:closed owner) true))
      (binding [ec/*execution-context* (:context (:world room))]
        (doseq [session (vals @(:sessions owner))] ((:close session)))
        (doseq [close (vals @(:observers owner))] (close))
        (doseq [ask (vals @(:asks owner))] ((:finish ask) {:status :leave :reason :room-closed}))
        (locking (:cleanup-lock owner)
          (when-not @(:stopped owner)
            (sync/post! (:mailbox room) (:end room))
            (reset! (:stopped owner) true)
            (release! owner (:lifetime owner))
            ((quiescence owner)
             (fn [_]
               (when (mult/mult-closed? (:mult room))
                 (swap! (:owners installation) update (:room/world (:record room)) disj room)
                 (binding [ec/*execution-context* (:context (:world room))]
                   (sync/deliver! (:closure owner) {:status :closed}))))
             (fn [_])))))
      (:closure owner))
    (locking (:lock installation)
      (or @(:closure room)
          (do (world/close! installation (:world room))
              (reset! (:closure room) (fn [resolve _] (resolve {:status :closed}))))))))
