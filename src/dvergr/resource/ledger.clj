(ns dvergr.resource.ledger
  "Datahike boundary for immutable resource-account classification and views.
   Kontor remains the only authority for balances and governed transfers."
  (:require [datahike.api :as d]
            [dvergr.room.store.governance :as governance]
            [kontor.gate :as gate]
            [kontor.resource :as kontor]))

(def purposes #{:room :run :world :escrow :reservation :hold})

(defn ensure-schema! [conn]
  (when-not (d/entity @conn :dvergr.resource-account/purpose)
    (d/transact conn [{:db/ident :dvergr.resource-account/purpose
                       :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}]))
  conn)

(defn validate-report! [{:keys [db-before db-after tx-data] :as report}]
  (let [purpose-eid (:db/id (d/entity db-after :dvergr.resource-account/purpose))
        touched (filter #(or (= :dvergr.resource-account/purpose (:a %))
                             (= purpose-eid (:a %))) tx-data)]
    (doseq [datom touched
            :let [eid (:e datom)
                  before (d/pull db-before [:kontor.resource-account/id] eid)
                  after (d/pull db-after [:kontor.resource-account/id
                                          :kontor.resource-account/kind
                                          :dvergr.resource-account/purpose
                                          {:kontor.resource-account/owner [:run/id]}] eid)]]
      (when-not (and (:added datom)
                     (nil? (:kontor.resource-account/id before))
                     (uuid? (:kontor.resource-account/id after))
                     (= :wallet (:kontor.resource-account/kind after))
                     (contains? purposes (:dvergr.resource-account/purpose after))
                     (or (not= :run (:dvergr.resource-account/purpose after))
                         (= (:kontor.resource-account/id after)
                            (get-in after [:kontor.resource-account/owner :run/id]))))
        (throw (ex-info "Resource purpose must classify a new wallet atomically"
                        {:type ::invalid-purpose :account eid}))))
    report))

(defn govern! [conn]
  (governance/compose! conn ::purpose validate-report!))

(defn account-tx-data [{:keys [id purpose] :as spec}]
  (when-not (contains? purposes purpose)
    (throw (ex-info "Dvergr resource wallet requires an explicit purpose"
                    {:type ::invalid-purpose :id id :purpose purpose})))
  (mapv #(assoc % :dvergr.resource-account/purpose purpose)
        (kontor/open-account-tx-data spec)))

(defn- assert-purpose! [db {:keys [id purpose]}]
  (let [actual (:dvergr.resource-account/purpose
                (d/pull db [:dvergr.resource-account/purpose] (kontor/account-ref id)))]
    (when-not (= purpose actual)
      (throw (ex-info "Resource wallet purpose conflicts with its immutable classification"
                      {:type ::purpose-conflict :id id :requested purpose :actual actual})))))

(defn open-account! [conn {:keys [id] :as spec}]
  (when-not (contains? purposes (:purpose spec))
    (throw (ex-info "Dvergr resource wallet requires an explicit purpose"
                    {:type ::invalid-purpose :id id :purpose (:purpose spec)})))
  (if (d/entity @conn (kontor/account-ref id))
    (do
      (assert-purpose! @conn spec)
      ;; Kontor verifies the requested owner on replay.
      (kontor/open-account! conn spec))
    (try
      (gate/transact-with-validation conn (account-tx-data spec))
      (kontor/account-ref id)
      (catch Throwable error
        (if (d/entity @conn (kontor/account-ref id))
          (open-account! conn spec)
          (throw error))))))

(defn assert-wallet! [conn spec]
  (when-not (contains? purposes (:purpose spec))
    (throw (ex-info "Dvergr resource wallet requires an explicit purpose"
                    {:type ::invalid-purpose :id (:id spec) :purpose (:purpose spec)})))
  (when-not (d/entity @conn (kontor/account-ref (:id spec)))
    (throw (ex-info "Resource wallet does not exist"
                    {:type ::kontor/account-not-found :id (:id spec)})))
  (assert-purpose! @conn spec)
  (kontor/open-account! conn spec))

(defn- add-vector [a b]
  (into {} (remove (comp zero? val)) (merge-with + a b)))

(defn read-view
  "An eager immutable view of one DB value. Balances are folded from the same
   sealed posted transfers that provide funding provenance, never live reads."
  [conn]
  (let [db @conn
        accounts (into {}
                       (map (fn [a]
                              [(:kontor.resource-account/id a)
                               {:kind (:kontor.resource-account/kind a)
                                :purpose (:dvergr.resource-account/purpose a)
                                :run-id (get-in a [:kontor.resource-account/owner :run/id])
                                :balance {}}]))
                       (d/q '[:find [(pull ?a [:kontor.resource-account/id
                                               :kontor.resource-account/kind
                                               :dvergr.resource-account/purpose
                                               {:kontor.resource-account/owner [:run/id]}]) ...]
                              :where [?a :kontor.resource-account/id]] db))
        rows (d/q '[:find ?id ?kind ?source-id ?destination-id ?p ?symbol ?amount
                    :where
                    [?tx :kontor.resource-transfer/id ?id]
                    [?tx :kontor.transaction/state :posted]
                    [?tx :kontor.resource-transfer/kind ?kind]
                    [?tx :kontor.resource-transfer/source ?source]
                    [?source :kontor.resource-account/id ?source-id]
                    [?tx :kontor.resource-transfer/destination ?destination]
                    [?destination :kontor.resource-account/id ?destination-id]
                    [?p :kontor.posting/transaction ?tx]
                    [?p :kontor.posting/account ?destination]
                    [?p :kontor.posting/ledger ?ledger]
                    [?ledger :kontor.ledger/code "resource"]
                    [?p :kontor.posting/commodity ?commodity]
                    [?commodity :kontor.commodity/symbol ?symbol]
                    [?p :kontor.posting/amount ?amount]] db)
        transfers (vals (reduce (fn [out [id kind source destination _ symbol amount]]
                                  (-> out
                                      (update id #(merge {:id id :kind kind :source source
                                                          :destination destination :resources {}} %))
                                      (update-in [id :resources symbol] (fnil + 0M) amount)))
                                {} rows))
        accounts (reduce (fn [out {:keys [source destination resources]}]
                           (-> out
                               (update-in [source :balance] add-vector (update-vals resources -))
                               (update-in [destination :balance] add-vector resources)))
                         accounts transfers)]
    {:accounts accounts :transfers (vec transfers)}))
