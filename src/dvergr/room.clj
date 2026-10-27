(ns dvergr.room
  "Fixed world bindings and durable discourse attachment."
  (:require [dvergr.schema :as schema]
            [dvergr.store :as store]
            [dvergr.value :as v]
            [dvergr.world :as world]
            [dvergr.world.systems :as systems]
            [org.replikativ.spindel.engine.core :as ec]))

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

(defn create!
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
          (let [room {:installation installation :world root :record record
                      :owner {:closed (atom false)} :closure (atom nil)}]
            (swap! (:owners installation) update (:room/world record) (fnil conj #{}) room)
            room))
        (catch Throwable e (world/close! installation root) (throw e))))))

(defn close!
  "Fence durable room work and release its world closure guard."
  [installation room]
  (when-not (identical? installation (:installation room)) (v/fail! :room-offline {}))
  (locking (:lock installation)
    (or @(:closure room)
        (do
          (if-let [owner (:owner room)]
            (do (reset! (:closed owner) true)
                (swap! (:owners installation) update (:room/world (:record room)) disj room))
            (world/close! installation (:world room)))
          (reset! (:closure room) (fn [resolve _] (resolve {:status :closed})))))))
