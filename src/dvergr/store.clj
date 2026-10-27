(ns dvergr.store
  "A local, single-writer control capability, separate from all work adapters."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [datahike.api :as d]
            [dvergr.schema :as schema]
            [dvergr.value :as v]
            [yggdrasil.protocols :as p]))

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

(defn- work-record [db key]
  (when-let [eid (:e (storage! #(first (d/datoms db {:index :avet :components [:discourse/key key]}))))]
    (edn/read-string {:readers {} :default (fn [_ _] (v/fail! :history-unavailable {}))}
                     (:discourse/payload (storage! d/pull db [:discourse/payload] eid)))))

(defn- work-write! [conn entries]
  (storage! d/transact conn
            (mapv (fn [[key record]]
                    (cond-> {:discourse/key key :discourse/payload (encode record)}
                      (:envelope record) (merge {:discourse/message (get-in record [:envelope :message/id])
                                                 :discourse/projection (get-in record [:envelope :projection/id])
                                                 :discourse/order (get-in record [:envelope :order/key])}))) entries)))

(defn- reachable! [system basis error]
  (when-not (try (or (= basis (p/snapshot-id system))
                     (p/ancestor? system basis (p/snapshot-id system)))
                 (catch Throwable _ false))
    (v/fail! error {}))
  basis)

(defn- history-page [messages fixed projection-id more?]
  {:messages messages :basis fixed
   :cursor (when more? {:basis fixed :projection/id projection-id
                        :after (:order/key (peek messages))})})

(defn ^:no-doc work!
  "Closed work operation vocabulary; caller holds the installation writer lock."
  [system operation {:keys [command id record canonical origin expected-version participant-id] :as request}]
  (schema/request! request)
  (let [conn (:conn system) db @conn
        basis (fn [id] (reachable! system id :history-unavailable)
                (try (or (p/as-of system id) (v/fail! :history-unavailable {}))
                     (catch Throwable _ (v/fail! :history-unavailable {}))))
        projection #(work-record % (str "projection/" %2))]
    (case [operation command]
      [:discourse/read :projection] (projection db id)
      [:discourse/read :decision] (work-record db (str "decision/" id))
      [:discourse/read :message] (some-> (work-record db (str "message/" id)) :envelope)
      [:discourse/write :projection]
      (do
        (when-not (and (= #{:projection/id :projection/parent :projection/basis :room/id} (set (keys record)))
                       (uuid? (:projection/id record)) (uuid? (:room/id record))
                       (or (nil? (:projection/parent record))
                           (and (uuid? (:projection/parent record)) (string? (:projection/basis record)))))
          (v/fail! :reserved-control-kind {}))
        (when-let [parent (:projection/parent record)]
          (let [p (projection (basis (:projection/basis record)) parent)]
            (when-not (= (:room/id record) (:room/id p)) (v/fail! :history-unavailable {}))))
        (if-let [old (projection db (:projection/id record))]
          (if (= old record) old (v/fail! :identity-conflict {}))
          (do (work-write! conn [[(str "projection/" (:projection/id record)) record]]) record)))
      [:discourse/write :post]
      (do
        (schema/message! (:request canonical))
        (when-not (= #{:request :author :room/id :projection/id} (set (keys canonical)))
          (v/fail! :reserved-control-kind {}))
        (when-not (projection db (:projection/id canonical)) (v/fail! :unknown-projection {}))
        (if-let [old (work-record db (str "message/" id))]
          (if (= canonical (:canonical old))
            {:envelope (:envelope old) :persistence :existing :live-publication :not-offered}
            (v/fail! :identity-conflict {}))
          (let [sequence (inc (or (work-record db (str "sequence/" origin)) 0))
                order (format "%020d|%s|%s" sequence (name origin) id)
                spec (:request canonical)
                envelope (merge {:message/version 1 :message/id id :room/id (:room/id canonical)
                                 :projection/id (:projection/id canonical) :author (:author canonical)
                                 :reply-to nil :ask/id nil :run/id nil :control/refs [] :tags #{}}
                                (dissoc spec :id)
                                {:origin {:world/id origin :sequence sequence} :order/key order})]
            ;; Admission includes both the post result and a complete singleton page.
            (v/portable! {:envelope envelope :persistence :inserted :live-publication :not-offered} schema/limits)
            (v/portable! (history-page [envelope] (p/snapshot-id system) (:projection/id canonical) true) schema/limits)
            (work-write! conn [[(str "sequence/" origin) sequence]
                               [(str "message/" id) {:canonical canonical :envelope envelope}]])
            {:envelope envelope :persistence :inserted :live-publication :not-offered})))
      [:discourse/read :history]
      (letfn [(visible [view projection-id seen]
                (when (contains? seen projection-id) (v/fail! :history-unavailable {}))
                (let [p (or (projection view projection-id) (v/fail! :history-unavailable {}))
                      local (map (fn [datom]
                                   (let [metadata (storage! d/pull view [:discourse/message :discourse/order] (:e datom))]
                                     {:id (:discourse/message metadata) :order (:discourse/order metadata)
                                      :view view :eid (:e datom)}))
                                 (storage! d/datoms view {:index :avet :components [:discourse/projection projection-id]}))]
                  (concat
                   (when-let [parent (:projection/parent p)]
                     (visible (basis (:projection/basis p)) parent (conj seen projection-id))) local)))]
        (when-not (and (int? (:limit request)) (<= 1 (:limit request) 256)
                       (or (nil? (:after request)) (string? (:after request))))
          (v/fail! :invalid-request {}))
        (let [fixed (or (:basis request) (p/snapshot-id system))
              _ (when (:basis request) (reachable! system fixed :invalid-cursor))
              metadata (->> (visible (basis fixed) id #{})
                            (reduce #(assoc %1 (:id %2) %2) {}) vals (sort-by :order))]
          (when (and (:after request) (not-any? #(= (:after request) (:order %)) metadata))
            (v/fail! :invalid-cursor {}))
          (loop [remaining (seq (filter #(or (nil? (:after request))
                                             (pos? (compare (:order %) (:after request)))) metadata))
                 messages []]
            (if (or (nil? remaining) (= (:limit request) (count messages)))
              (history-page messages fixed id (boolean remaining))
              (let [{:keys [view eid]} (first remaining)
                    envelope (:envelope (edn/read-string
                                         (:discourse/payload (storage! d/pull view [:discourse/payload] eid))))
                    candidate (conj messages envelope)
                    fits? (try (v/portable! (history-page candidate fixed id (boolean (next remaining))) schema/limits)
                               true
                               (catch clojure.lang.ExceptionInfo e
                                 (if (= :value-limit (:dvergr/error (ex-data e))) false (throw e))))]
                (if fits?
                  (recur (next remaining) candidate)
                  (if (seq messages)
                    (history-page messages fixed id true)
                    (v/fail! :history-unavailable {}))))))))
      [:discourse/write :decision]
      (do
        (when-not (and (= #{:decision/id :projection/id :participant/id :version} (set (keys record)))
                       (uuid? (:decision/id record)) (uuid? (:projection/id record))
                       (uuid? (:participant/id record)) (= 0 (:version record)))
          (v/fail! :reserved-control-kind {}))
        (when-not (projection db (:projection/id record)) (v/fail! :unknown-projection {}))
        (if-let [old (work-record db (str "decision/" (:decision/id record)))]
          (if (= old record) old (v/fail! :identity-conflict {}))
          (do (work-write! conn [[(str "decision/" (:decision/id record)) record]]) record)))
      [:discourse/write :decider]
      (let [old (or (work-record db (str "decision/" id)) (v/fail! :unknown-decision {}))]
        (when-not (= expected-version (:version old)) (v/fail! :version-conflict {}))
        (v/identity! :participant participant-id)
        (let [new (assoc old :version (inc expected-version) :participant/id participant-id)]
          (work-write! conn [[(str "decision/" id) new]]) new))
      (v/fail! :operation-denied {}))))
