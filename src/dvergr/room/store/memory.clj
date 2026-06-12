(ns dvergr.room.store.memory
  "In-memory PRoomStore — atom-backed. For tests and ephemeral rooms
   (e.g. `:isolation :none` forks where the agent's substrate ctx is
   shared with the parent so persistence would be redundant)."
  (:require [dvergr.room.store :as store]))

(defrecord MemoryStore [state]
  ;; state atom shape:
  ;;   {:rooms     {room-id metadata}
  ;;    :messages  {room-id [msg ...] (chronological)}}
  store/PRoomStore

  (-store-room! [_ room-id metadata]
    (swap! state assoc-in [:rooms room-id] (assoc metadata :updated-at (java.util.Date.))))

  (-load-room [_ id-or-slug]
    (let [rooms (:rooms @state)]
      (or (get rooms id-or-slug)
          (some (fn [[_ m]] (when (= (:slug m) (str id-or-slug)) m))
                rooms))))

  (-delete-room! [_ room-id]
    (swap! state (fn [s]
                   (-> s
                       (update :rooms    dissoc room-id)
                       (update :messages dissoc room-id)))))

  (-list-rooms [_]
    (->> (vals (:rooms @state))
         (sort-by #(- (.getTime (or (:updated-at %) (java.util.Date. 0)))))
         vec))

  (-store-message! [_ room-id msg]
    (let [msg-id (:id msg)]
      (swap! state update-in [:messages room-id]
             (fn [existing]
               (let [v (or existing [])]
                 (if (some #(= (:id %) msg-id) v)
                   v
                   (conj v msg)))))
      (swap! state update-in [:rooms room-id]
             (fn [m] (when m (assoc m :updated-at (java.util.Date.)))))))

  (-list-messages [_ room-id {:keys [limit since]}]
    (let [all (get-in @state [:messages room-id] [])
          filtered (if since
                     (filter #(let [t (:ts %)]
                                (and t (> (.getTime ^java.util.Date (java.util.Date. ^long t))
                                          (.getTime ^java.util.Date since))))
                             all)
                     all)
          n (or limit (count filtered))]
      (vec (take-last n filtered)))))

(defn make
  "Create a fresh in-memory store."
  []
  (->MemoryStore (atom {:rooms {} :messages {}})))
