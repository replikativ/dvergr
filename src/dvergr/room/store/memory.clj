(ns dvergr.room.store.memory
  "In-memory PRoomStore — atom-backed. For tests and ephemeral rooms
   (e.g. `:isolation :none` forks where the agent's substrate ctx is
   shared with the parent so persistence would be redundant)."
  (:require [dvergr.room.store :as store]))

(defrecord MemoryStore [state]
  ;; state atom shape:
  ;;   {:rooms     {room-id metadata}
  ;;    :messages  {room-id [msg ...] (chronological)}
  ;;    :runs      {room-id {run-id run}}}
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
                       (update :messages dissoc room-id)
                       (update :runs     dissoc room-id)))))

  (-list-rooms [_]
    (->> (vals (:rooms @state))
         (sort-by #(- (.getTime (or (:updated-at %) (java.util.Date. 0)))))
         vec))

  (-store-message! [_ room-id msg]
    (let [msg    (store/normalize-message-thread msg)
          msg-id (:id msg)]
      (swap! state update-in [:messages room-id]
             (fn [existing]
               (let [v (or existing [])]
                 (if (some #(= (:id %) msg-id) v)
                   v
                   (do
                     (store/validate-message-metadata! (:metadata msg))
                     (conj v msg))))))
      (swap! state update-in [:rooms room-id]
             (fn [m] (when m (assoc m :updated-at (java.util.Date.)))))))

  (-message-thread-root [_ room-id message-id]
    (some #(when (= message-id (:id %)) (:thread-root-id %))
          (get-in @state [:messages room-id] [])))

  (-list-messages [_ room-id {:keys [limit since thread-root-id]}]
    (let [all (get-in @state [:messages room-id] [])
          filtered (cond->> all
                     since
                     (filter #(let [t (:ts %)]
                                (and t (> (.getTime ^java.util.Date (java.util.Date. ^long t))
                                          (.getTime ^java.util.Date since)))))

                     thread-root-id
                     (filter #(= thread-root-id (:thread-root-id %))))
          n (or limit (count filtered))]
      (vec (take-last n filtered))))

  (-store-run! [_ room-id run]
    (let [run (->> run
                   store/validate-run!
                   (store/validate-run-update!
                    (get-in @state [:runs room-id (:run/id run)])))]
      (swap! state assoc-in [:runs room-id (:run/id run)] run)
      run))

  (-load-run [_ room-id run-id]
    (get-in @state [:runs room-id run-id]))

  (-list-runs [_ room-id {:keys [limit status actor]}]
    (->> (vals (get-in @state [:runs room-id] {}))
         (filter #(if status (= status (:run/status %)) true))
         (filter #(if actor (= actor (:run/actor %)) true))
         (sort-by (juxt #(some-> ^java.util.Date (:run/started-at %) .getTime)
                        #(str (:run/id %)))
                  #(compare %2 %1))
         (take (or limit 100))
         vec)))

(defn make
  "Create a fresh in-memory store."
  []
  (->MemoryStore (atom {:rooms {} :messages {} :runs {}})))
