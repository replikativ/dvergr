(ns dvergr.world.systems
  "Approved adapters and logical handles resolved on every operation."
  (:require [datahike.api :as d]
            [dvergr.schema :as schema]
            [dvergr.store :as store]
            [dvergr.value :as v]
            [org.replikativ.spindel.engine.context :as context]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.yggdrasil :as y]
            [sci.fork :as fork]
            [yggdrasil.adapters.datahike :as dh]
            [yggdrasil.adapters.geschichte :as git]
            [yggdrasil.protocols :as p]))

(defrecord ^:private Installation [id control live lock])
(alter-meta! #'->Installation assoc :private true)
(alter-meta! #'map->Installation assoc :private true)

(defn ^:no-doc installation [control]
  (->Installation (:installation/id (store/info control)) control (atom {}) (Object.)))

(def ^:dynamic ^:no-doc *installation* nil)

(deftype ^:private SystemHandle [installation-id anchor system-id operations classifier]
  fork/Forkable
  (fork-value [this] this))
(alter-meta! #'->SystemHandle assoc :private true)

(defn ^:no-doc handle-data [x]
  (when (instance? SystemHandle x)
    (let [^SystemHandle h x]
      {:installation-id (.-installation-id h) :anchor (.-anchor h)
       :system-id (.-system-id h) :operations (.-operations h) :classifier (.-classifier h)})))

(defn ^:no-doc selected! [installation anchor]
  (when-not ec/*execution-context* (v/fail! :no-selected-world {}))
  (let [ctx (ec/current-execution-context)
        id (context/get-fork-id ctx)
        world (get @(:live installation) id)]
    (when-not (and world (identical? ctx (:context world))) (v/fail! :unknown-world {}))
    (when-not (some #(= anchor (:world/id %)) (store/lineage (:control installation) id))
      (v/fail! :unrelated-lineage {}))
    world))

(def ^:private operation-vocabulary #{:datahike/pull :datahike/transact})

(defn ^:no-doc validate-spec! [control spec]
  (let [{:keys [id adapter connection operations attributes ownership]} spec]
    (v/identity! :system id)
    (when-not (and (= #{:id :adapter :connection :operations :attributes :ownership}
                      (set (keys spec)))
                   (#{:datahike :geschichte} adapter)
                   (#{:owned :borrowed} ownership)
                   (set? operations) (every? operation-vocabulary operations)
                   (or (= :datahike adapter) (empty? operations))
                   (set? attributes) (every? keyword? attributes)
                   (not-any? #(#{"db" "db.install" "db.alter"} (namespace %)) attributes)
                   (instance? datahike.connector.Connection connection))
      (v/fail! :unsupported-adapter {}))
    (let [identity (store/storage-identity (:config @connection))
          control-id (store/provenance control)]
      (when-not identity (v/fail! :unsupported-adapter {}))
      (when (or (= (:id identity) (:id control-id))
                (and (= :file (:backend identity) (:backend control-id))
                     (or (= (:path identity) (:path control-id))
                         (and (:id identity) (= (:id identity) (:id control-id))))))
        (v/fail! :control-store-alias {})))
    (try
      (v/portable! (dissoc spec :connection) schema/limits)
      (when (= :datahike adapter)
        (doseq [attribute attributes]
          (when-not (= :db.cardinality/one
                       (:db/cardinality (d/pull @connection [:db/cardinality] [:db/ident attribute])))
            (v/fail! :unsupported-adapter {}))))
      (catch Throwable e
        (throw (ex-info "Unsupported work schema" {:dvergr/error :unsupported-adapter} e))))
    spec))

(defn register!
  "Register one approved constructor with verified physical work-store provenance."
  [assembly spec]
  (when @(:published assembly) (v/fail! :assembly-published {}))
  (validate-spec! (:control assembly) spec)
  (let [{:keys [id adapter connection]} spec]
    (when (contains? @(:systems assembly) id) (v/fail! :duplicate-system {}))
    (let [system ((case adapter :datahike dh/create :geschichte git/create)
                  connection {:system-name id})
          basis (p/snapshot-id system)]
      (binding [ec/*execution-context* (:context assembly)] (y/register! system))
      (swap! (:systems assembly) assoc id
             (assoc (dissoc spec :connection) :connection connection
                    :manifest {:classification :snapshotted
                               :adapter (case adapter
                                          :datahike :dvergr.adapter.datahike/v1
                                          :geschichte :dvergr.adapter.geschichte/v1)
                               :tier (when (= :datahike adapter) :cold) :basis basis}))
      id)))

(defn handle
  "Create a portable logical reference with bounded installed permissions."
  [installation world system-id operations]
  (when-not (identical? world (get @(:live installation) (get-in world [:descriptor :world/id])))
    (v/fail! :world-unavailable {}))
  (let [spec (get (:systems world) system-id)]
    (when-not spec (v/fail! :system-unavailable {}))
    (try (v/portable! operations schema/limits)
         (catch clojure.lang.ExceptionInfo _ (v/fail! :unsupported-operation {})))
    (when-not (and (set? operations) (every? (:operations spec) operations))
      (v/fail! :unsupported-operation {}))
    (SystemHandle. (:id installation) (get-in world [:descriptor :world/id])
                   system-id operations :dvergr.injection/v1)))

(defn ^:no-doc assembly-handle [assembly system-id operations]
  (let [spec (get @(:systems assembly) system-id)]
    (when-not spec (v/fail! :system-unavailable {}))
    (when-not (and (set? operations) (every? (:operations spec) operations))
      (v/fail! :unsupported-operation {}))
    (SystemHandle. (:id (:installation assembly))
                   (context/get-fork-id (:context assembly)) system-id operations
                   :dvergr.injection/v1)))

(defn resolve!
  "Resolve the effective selected adapter for one permitted invocation."
  [installation handle operation]
  (let [{:keys [installation-id anchor system-id operations]} (handle-data handle)]
    (when-not installation-id (v/fail! :operation-denied {}))
    (when-not ec/*execution-context* (v/fail! :no-selected-world {}))
    (when-not (= (:id installation) installation-id) (v/fail! :unrelated-lineage {}))
    (let [world (selected! installation anchor)
          spec (get (:systems world) system-id)]
      (when-not spec (v/fail! :system-unavailable {}))
      (when-not (and (contains? operations operation) (contains? (:operations spec) operation))
        (v/fail! :operation-denied {}))
      (or (y/system system-id) (v/fail! :system-unavailable {})))))

(defn- request! [spec operation request]
  (try (v/portable! request {:depth 8 :nodes 2048 :string-size 8192})
       (catch clojure.lang.ExceptionInfo _ (v/fail! :invalid-request {})))
  (let [attrs (:attributes spec)]
    (when-not
     (case operation
       :datahike/pull
       (and (map? request) (= #{:selector :eid} (set (keys request)))
            (pos-int? (:eid request)) (vector? (:selector request))
            (<= 1 (count (:selector request)) 64)
            (every? #(or (= :db/id %) (contains? attrs %)) (:selector request)))
       :datahike/transact
       (and (vector? request) (<= 1 (count request) 128)
            (every? (fn [datom]
                      (and (vector? datom) (= 4 (count datom))
                           (#{:db/add :db/retract} (nth datom 0))
                           (int? (nth datom 1)) (not (zero? (nth datom 1)))
                           (contains? attrs (nth datom 2))
                           (v/scalar? (nth datom 3)))) request))
       false)
      (v/fail! :invalid-request {}))))

(defn invoke!
  "Invoke a keyword operation with bounded data. Queries arrive with the effects slice."
  [installation handle operation request]
  (locking (:lock installation)
    (let [system (resolve! installation handle operation)
          {:keys [system-id]} (handle-data handle)
          world (get @(:live installation) (context/get-fork-id (ec/current-execution-context)))
          spec (get (:systems world) system-id)]
      (request! spec operation request)
      (try
        (v/portable!
         (case operation
           :datahike/pull (d/pull @(:conn system) (:selector request) (:eid request))
           :datahike/transact
           (let [report (d/transact (:conn system) request)]
             {:tempids (:tempids report) :datom-count (count (:tx-data report))}))
         schema/limits)
        (catch Throwable e
          (throw (ex-info "Work adapter failed" {:dvergr/error :adapter-failure} e)))))))
