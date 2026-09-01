(ns dvergr.room.store.memory
  "In-memory PRoomStore — atom-backed. For tests and ephemeral rooms
   (e.g. `:isolation :none` forks where the agent's substrate ctx is
   shared with the parent so persistence would be redundant)."
  (:require [dvergr.agent.attempt :as attempt]
            [dvergr.room.store :as store]))

(defrecord MemoryStore [state]
  ;; state atom shape:
  ;;   {:rooms     {room-id metadata}
  ;;    :messages  {room-id [msg ...] (chronological)}
  ;;    :runs      {room-id {run-id run}}
  ;;    :attempts  {room-id {attempt-id attempt}}}
  store/PRoomStore

  (-store-room! [_ room-id metadata]
    (swap! state assoc-in [:rooms room-id] (assoc metadata :updated-at (java.util.Date.))))

  (-load-room [_ id-or-slug]
    (let [rooms (:rooms @state)]
      (or (get rooms id-or-slug)
          (some (fn [[_ m]] (when (= (:slug m) (str id-or-slug)) m))
                rooms))))

  (-delete-room! [_ room-id]
    (swap! state
           (fn [s]
             (let [attention-ids (keys (get-in s [:attention room-id] {}))]
               (-> s
                   (update :rooms    dissoc room-id)
                   (update :messages dissoc room-id)
                   (update :runs     dissoc room-id)
                   (update :attempts dissoc room-id)
                   (update :attention dissoc room-id)
                   (update :attention-index
                           #(apply dissoc % attention-ids)))))))

  (-list-rooms [_]
    (->> (vals (:rooms @state))
         (sort-by #(- (.getTime (or (:updated-at %) (java.util.Date. 0)))))
         vec))

  (-store-message! [_ room-id msg]
    (let [msg    (store/normalize-message-thread msg)
          msg-id (:id msg)
          _ (store/validate-message-metadata! (:metadata msg))
          [before _]
          (swap-vals! state
                      (fn [s]
                        (if (some #(= (:id %) msg-id)
                                  (get-in s [:messages room-id] []))
                          s
                          (do
                            (when-let [activity-run-id
                                       (some :activity/run-id
                                             (get-in msg [:metadata :activities]))]
                              (when-not (get-in s [:runs room-id activity-run-id])
                                (throw
                                 (ex-info
                                  "Run-correlated activity references a missing Run in this Room"
                                  {:type :room-store/orphan-message-activity
                                   :room-id room-id
                                   :message-id msg-id
                                   :run-id activity-run-id}))))
                            (-> s
                                (update-in [:messages room-id] (fnil conj []) msg)
                                (update-in [:rooms room-id]
                                           (fn [m]
                                             (when m
                                               (assoc m :updated-at
                                                      (java.util.Date.))))))))))]
      (if (some #(= (:id %) msg-id)
                (get-in before [:messages room-id] []))
        :duplicate
        :inserted)))

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
    (let [run (store/validate-run! run)]
      (swap! state
             (fn [snapshot]
               (let [existing (get-in snapshot [:runs room-id (:run/id run)])
                     run (->> run
                              (store/validate-run-update! existing)
                              (store/validate-run-causes!
                               existing
                               #(get-in snapshot [:runs room-id %])))]
                 (assoc-in snapshot [:runs room-id (:run/id run)] run))))
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
         vec))

  store/PAttemptStore

  (-store-attempt! [_ room-id value]
    (let [value (attempt/validate-attempt value)
          attempt-id (:attempt/id value)]
      (swap! state
             (fn [snapshot]
               (let [run (get-in snapshot [:runs room-id
                                           (:attempt/run-id value)])
                     existing (get-in snapshot [:attempts room-id attempt-id])]
                 (when-not (and run
                                (contains? store/terminal-run-statuses
                                           (:run/status run)))
                   (throw (ex-info "Attempt references a missing or non-terminal Run"
                                   {:type :room-store/invalid-attempt-run
                                    :room-id room-id
                                    :attempt/id attempt-id
                                    :run/id (:attempt/run-id value)})))
                 (when-not (= (:run/status run)
                              (get-in value [:attempt/receipt :attempt/status]))
                   (throw (ex-info "Attempt receipt status differs from its Run"
                                   {:type :room-store/attempt-run-status-mismatch
                                    :run/status (:run/status run)
                                    :attempt/status
                                    (get-in value [:attempt/receipt
                                                   :attempt/status])})))
                 (doseq [[attempt-key run-key]
                         [[:attempt/agent-def-hash :run/agent-def-hash]
                          [:program-kind :run/program-kind]
                          [:interpreter-version :run/interpreter-version]]]
                   (let [attempt-value
                         (if (= :attempt/agent-def-hash attempt-key)
                           (get value attempt-key)
                           (get-in value [:attempt/receipt :attempt/metrics
                                          attempt-key]))]
                     (when-not (= attempt-value (get run run-key))
                       (throw
                        (ex-info "Attempt provenance differs from its Run"
                                 {:type
                                  :room-store/attempt-run-provenance-mismatch
                                  :attempt/key attempt-key
                                  :attempt/value attempt-value
                                  :run/value (get run run-key)})))))
                 (doseq [evidence-run-id (:attempt/evidence-run-ids value)]
                   (when-not (get-in snapshot [:runs room-id evidence-run-id])
                     (throw
                      (ex-info "Attempt evidence Run is missing from this Room"
                               {:type :room-store/invalid-attempt-evidence-run
                                :room-id room-id
                                :attempt/id attempt-id
                                :run/id evidence-run-id}))))
                 (doseq [evidence-message-id
                         (:attempt/evidence-message-ids value)]
                   (when-not (some #(= evidence-message-id (:id %))
                                   (get-in snapshot [:messages room-id] []))
                     (throw
                      (ex-info "Attempt evidence message is missing from this Room"
                               {:type :room-store/invalid-attempt-evidence-message
                                :room-id room-id
                                :attempt/id attempt-id
                                :message/id evidence-message-id}))))
                 (when (and existing (not= existing value))
                   (throw (ex-info "Attempt identity is immutable"
                                   {:type :room-store/attempt-identity-collision
                                    :existing existing :attempt value})))
                 (if existing snapshot
                     (assoc-in snapshot [:attempts room-id attempt-id] value)))))
      value))

  (-load-attempt [_ room-id attempt-id]
    (get-in @state [:attempts room-id attempt-id]))

  (-list-attempts [_ room-id {:keys [limit environment-id
                                     environment-content-id provider model
                                     status]}]
    (->> (vals (get-in @state [:attempts room-id] {}))
         (filter #(if environment-id
                    (= environment-id
                       (get-in % [:attempt/environment :environment/id]))
                    true))
         (filter #(if environment-content-id
                    (= environment-content-id
                       (get-in % [:attempt/environment
                                  :environment/content-id]))
                    true))
         (filter #(if provider
                    (= provider (get-in % [:attempt/receipt
                                           :attempt/provider]))
                    true))
         (filter #(if model
                    (= model (get-in % [:attempt/receipt :attempt/model]))
                    true))
         (filter #(if status
                    (= status (get-in % [:attempt/receipt :attempt/status]))
                    true))
         (sort-by (juxt :attempt/certified-at #(str (:attempt/id %)))
                  #(compare %2 %1))
         (take (or limit 100))
         vec))

  store/PAttentionStore

  (-store-attention! [_ room-id fact]
    (let [fact (store/validate-attention! fact)
          attention-id (:attention/id fact)
          path [:attention room-id attention-id]
          [_ after]
          (swap-vals!
           state
           (fn [s]
             (if (get-in s [:attention-index attention-id])
               s
               (do
                 (when (= :applied (:attention/status fact))
                   (-> (store/validate-attention-disposition!
                        (get-in s [:attention-index (:attention/decision-id fact) :fact])
                        fact)
                       (store/validate-attention-result-run!
                        (get-in s [:runs room-id (:attention/result-run-id fact)]))))
                 (-> s
                     (assoc-in path fact)
                     (assoc-in [:attention-index attention-id]
                               {:room-id room-id :fact fact}))))))
          stored (get-in after [:attention-index attention-id])]
      (when-not (= {:room-id room-id :fact fact} stored)
        (throw (ex-info "Attention identity is immutable"
                        {:type :room-store/attention-identity-collision
                         :existing stored :fact fact})))
      fact))

  (-list-attention [_ room-id {:keys [id participant limit]}]
    (if id
      (some-> (get-in @state [:attention room-id id]) vector)
      (->> (vals (get-in @state [:attention room-id] {}))
           (filter #(if participant (= participant (:attention/participant %)) true))
           (sort-by (juxt #(some-> ^java.util.Date (:attention/created-at %) .getTime)
                          #(str (:attention/id %))))
           (take-last (or limit 1000))
           vec))))

(defn make
  "Create a fresh in-memory store."
  []
  (->MemoryStore (atom {:rooms {} :messages {} :runs {} :attempts {}
                        :attention {} :attention-index {}})))
