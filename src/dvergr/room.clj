(ns dvergr.room
  "Durable fixed room bindings and independent live root rooms."
  (:require [dvergr.store :as store]
            [dvergr.value :as v]
            [dvergr.world :as world]))

(defn create!
  "Create an independent root room; snapshot and following rooms refuse before acquisition."
  [installation {:keys [id kind assembly] :as spec}]
  (v/identity! :room id)
  (case kind
    :snapshot (v/fail! :missing-prerequisite {:prerequisite :P1})
    :root nil
    (v/fail! :unsupported-mode {}))
  (when-not (= #{:id :kind :assembly} (set (keys spec))) (v/fail! :invalid-control-record {}))
  (locking (:lock installation)
    (when (store/room-record (:control installation) id) (v/fail! :identity-conflict {}))
    (let [root (world/create! installation assembly)
          record {:room/id id :room/world (:world/id (world/descriptor installation root))
                  :room/kind :root :schema/version 1}]
      (try
        (store/put-room! (:control installation) record)
        {:installation installation :record record :world root}
        (catch Throwable e (world/close! installation root) (throw e))))))

(defn lookup
  "Read a room's durable binding without implying runtime hydration."
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
