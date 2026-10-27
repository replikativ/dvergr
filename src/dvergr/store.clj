(ns dvergr.store
  "A local, single-writer control capability, separate from all work adapters."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [datahike.api :as d]
            [dvergr.schema :as schema]
            [dvergr.value :as v]))

(deftype ^:private ControlStore [conn lock closed info provenance])
(alter-meta! #'->ControlStore assoc :private true)

(defn- storage! [operation & args]
  (try (apply operation args)
       (catch Throwable e
         (if (:dvergr/error (ex-data e))
           (throw e)
           (throw (ex-info "Control storage failed" {:dvergr/error :storage-failure} e))))))

(defn ^:no-doc storage-identity
  "Canonical physical provenance; branch names never distinguish stores."
  [config]
  (let [{:keys [backend id path]} (:store config)]
    (case backend
      :memory (when (uuid? id) {:backend :memory :id id})
      :file (when (and (uuid? id) (string? path) (seq path))
              {:backend :file :path (.getCanonicalPath (java.io.File. path)) :id id})
      nil)))

(defn ^:no-doc info [^ControlStore control] (.-info control))
(defn ^:no-doc provenance [^ControlStore control] (.-provenance control))

(defn- connection! [^ControlStore control]
  (when @(.-closed control) (v/fail! :store-closed {}))
  (.-conn control))

(defn- payload [db key]
  (when-let [eid (:e (storage! #(first (d/datoms db {:index :avet :components [:control/key key]}))))]
    (:control/payload (storage! d/pull db [:control/payload] eid))))

(defn- encode [record]
  (let [payload (binding [*print-length* nil *print-level* nil *print-meta* false]
                  (pr-str record))]
    (when (> (count payload) (:string-size schema/limits)) (v/fail! :value-limit {}))
    payload))

(defn- read-record [control key]
  (when-let [payload (payload @(connection! control) key)]
    (when (> (count payload) (:string-size schema/limits)) (v/fail! :value-limit {}))
    (let [record (edn/read-string {:readers {} :default (fn [_ _] (v/fail! :nonportable-value {}))}
                                  payload)]
      (schema/control-record! (keyword (first (str/split key #"/" 2))) record))))

(defn open!
  "Open an explicit local control database for one installation writer."
  [{:keys [installation-id control-store-id database test?] :as config}]
  (when-not (and (= #{:installation-id :control-store-id :database :test?} (set (keys config)))
                 (uuid? installation-id) (uuid? control-store-id)
                 (boolean? test?) (storage-identity database)
                 (or test? (= :file (get-in database [:store :backend])))
                 (every? #{:store :schema-flexibility :keep-history? :commit-graph? :writer}
                         (keys database))
                 (not (contains? database :initial-tx))
                 (not (contains? database :branch))
                 (not= false (:keep-history? database))
                 (not= false (:commit-graph? database))
                 (or (nil? (:writer database))
                     (= {:backend :self :writer-ownership :exclusive} (:writer database)))
                 (or (nil? (:schema-flexibility database))
                     (= :write (:schema-flexibility database))))
    (v/fail! :invalid-control-config {}))
  (let [db-config (assoc database :schema-flexibility :write :keep-history? true
                         :commit-graph? true :writer {:backend :self :writer-ownership :exclusive})
        expected {:installation/id installation-id :control-store/id control-store-id
                  :schema/version 1 :store/role :control}
        exists? (storage! d/database-exists? db-config)]
    (when-not exists?
      (storage! d/create-database
                (assoc db-config :initial-tx
                       (conj (schema/control-schema)
                             {:control/key "installation/current" :control/payload (encode expected)}))))
    (let [conn (storage! d/connect db-config)
          control (ControlStore. conn (Object.) (atom false) expected (storage-identity db-config))]
      (try
        (let [raw (payload @conn "installation/current")
              actual (when raw (edn/read-string {:readers {} :default (fn [_ _] (v/fail! :wrong-store-role {}))} raw))]
          (when-not (= :control (:store/role actual)) (v/fail! :wrong-store-role {}))
          (when-not (= 1 (:schema/version actual)) (v/fail! :schema-version {}))
          (when-not (= actual expected) (v/fail! :identity-conflict {})))
        control
        (catch Throwable e (storage! d/release conn) (throw e))))))

(defn world-record
  "Read a durable world descriptor independently of runtime availability."
  [control world-id]
  (v/identity! :world world-id)
  (read-record control (str "world/" world-id)))

(defn room-record
  "Read an immutable room binding."
  [control room-id]
  (v/identity! :room room-id)
  (read-record control (str "room/" room-id)))

(defn- insert! [^ControlStore control kind id record validate! indexes]
  (schema/control-record! kind record)
  (locking (.-lock control)
    (connection! control)
    (let [key (str (name kind) "/" id)]
      (if-let [old (read-record control key)]
        (if (= old record) old (v/fail! :identity-conflict {}))
        (do (validate!)
            (storage! d/transact (connection! control)
                      [(merge {:control/key key :control/payload (encode record)} indexes)])
            record)))))

(defn put-world!
  "Insert immutable descriptor and source edge atomically; identical retries are harmless."
  [control record]
  (insert! control :world (:world/id record) record
           #(do
              (when-not (= (:control-store/id (info control)) (:assembly/control-store record))
                (v/fail! :identity-conflict {}))
              (when-let [source (:world/source record)]
                (let [parent (world-record control source)]
                  (when-not parent (v/fail! :missing-source {}))
                  (when-not (= (:world/root parent) (:world/root record))
                    (v/fail! :identity-conflict {})))))
           (cond-> (select-keys record [:world/id :world/root])
             (:world/source record) (assoc :world/source (:world/source record)))))

(defn lineage
  "Reconstruct root-to-world source ancestry using only durable records."
  [control world-id]
  (when-not (world-record control world-id) (v/fail! :unknown-world {}))
  (loop [id world-id seen #{} path ()]
    (if (nil? id)
      (vec path)
      (let [record (world-record control id)]
        (when (or (nil? record) (contains? seen id)) (v/fail! :broken-lineage {}))
        (recur (:world/source record) (conj seen id) (conj path record))))))

(defn put-room!
  "Insert a fixed room binding to an already recorded world."
  [control record]
  (insert! control :room (:room/id record) record
           #(when-not (world-record control (:room/world record)) (v/fail! :unknown-world {}))
           (select-keys record [:room/id])))

(defn close!
  "Release this capability's connection lease and refuse subsequent operations."
  [^ControlStore control]
  (locking (.-lock control)
    (when-not @(.-closed control)
      (storage! d/release (.-conn control))
      (reset! (.-closed control) true))))
