(ns dvergr.discourse
  "Durable envelopes, retained projections and logical decision bindings."
  (:require [dvergr.room :as room]
            [dvergr.schema :as schema]
            [dvergr.value :as v]
            [dvergr.world.systems :as systems]
            [yggdrasil.protocols :as p]))

(deftype ^:private Author [installation room world principal participant edit])
(alter-meta! #'->Author assoc :private true)

(defn ^:no-doc authenticate!
  "Trusted host identity adapter seam; never an SCI export."
  [installation live-room {:keys [principal-id participant-id edit-decisions?]}]
  (room/open! installation live-room)
  (v/identity! :participant participant-id)
  (v/identity! :participant principal-id)
  (Author. (:id installation) (get-in live-room [:record :room/id])
           (get-in live-room [:record :room/world]) principal-id participant-id
           (true? edit-decisions?)))

(defn- identity-data! [installation venue author local?]
  (room/open! installation (:room venue))
  (when-not (instance? Author author) (v/fail! :operation-denied {}))
  (let [^Author a author
        selected (systems/selected! installation (.-world a))]
    (when-not (and (= (:id installation) (.-installation a))
                   (= (get-in venue [:room :record :room/id]) (.-room a)))
      (v/fail! :operation-denied {}))
    (when (and local? (not= (get-in selected [:descriptor :world/id]) (.-world a)))
      (v/fail! :effect-boundary-unavailable {}))
    {:principal/id (.-principal a) :participant/id (.-participant a)}))

(defn- author! [installation venue author]
  (identity-data! installation venue author true))

(defn- editor! [installation venue author]
  (identity-data! installation venue author false)
  (when-not (.-edit ^Author author) (v/fail! :operation-denied {})))

(defn- basis [installation venue]
  (p/snapshot-id (systems/resolve! installation (:system venue) :discourse/read)))

(defn- persist! [installation venue author-data spec]
  (schema/message! spec)
  (room/open! installation (:room venue))
  (room/work! installation venue :discourse/write
              {:command :post :id (:id spec)
               :origin (get-in (systems/selected! installation
                                                  (:anchor (systems/handle-data (:system venue))))
                               [:descriptor :world/id])
               :canonical {:request spec :author author-data
                           :room/id (get-in venue [:room :record :room/id])
                           :projection/id (:projection/id venue)}}))

(defn post!
  "Persist an immutable envelope; live publication is not offered."
  [installation venue author spec]
  (locking (:lock installation)
    (let [identity (author! installation venue author)]
      (when (contains? spec :destination) (v/fail! :effect-boundary-unavailable {}))
      (persist! installation venue identity spec))))

(defn history
  "Read a bounded page at one retained basis; cursor replay is explicit."
  [installation venue {:keys [limit cursor] :or {limit 100} :as query}]
  (schema/request! query)
  (when-not (and (every? #{:limit :cursor} (keys query)) (int? limit) (<= 1 limit 256))
    (v/fail! :invalid-request {}))
  (locking (:lock installation)
    (room/open! installation (:room venue))
    (when (and cursor
               (not (and (map? cursor) (= #{:basis :projection/id :after} (set (keys cursor)))
                         (string? (:basis cursor)) (string? (:after cursor))
                         (= (:projection/id venue) (:projection/id cursor)))))
      (v/fail! :invalid-cursor {}))
    (room/work! installation venue :discourse/read
                {:command :history :id (:projection/id venue)
                 :basis (:basis cursor) :after (:after cursor) :limit limit})))

(defn branch!
  "Retain a parent history basis and share the exact execution world."
  [installation venue {:keys [id] :as spec}]
  (when-not (= #{:id} (set (keys spec))) (v/fail! :invalid-request {}))
  (v/identity! :projection id)
  (locking (:lock installation)
    (room/open! installation (:room venue))
    (let [old (room/work! installation venue :discourse/read {:command :projection :id id})
          record (or old {:projection/id id :projection/parent (:projection/id venue)
                          :projection/basis (basis installation venue)
                          :room/id (get-in venue [:room :record :room/id])})]
      (when-not (and (= (:projection/id venue) (:projection/parent record))
                     (= (get-in venue [:room :record :room/id]) (:room/id record)))
        (v/fail! :identity-conflict {}))
      (room/work! installation venue :discourse/write {:command :projection :record record})
      (room/venue installation (:room venue) id))))

(defn- decision-binding! [installation venue decision-id]
  (let [data (systems/handle-data (:system venue))
        room (:room venue)
        binding (or (room/work! installation venue :discourse/read {:command :decision :id decision-id})
                    (v/fail! :unknown-decision {}))]
    (when-not (and (= (:projection/id venue) (:projection/id binding))
                   (or (nil? room)
                       (and (= (:anchor data) (get-in room [:record :room/world]))
                            (= (:system-id data) (get-in room [:record :room/discourse :system])))))
      (v/fail! :operation-denied {}))
    binding))

(defn decision-handle
  "Build a logical decision reference using the existing system handle."
  [installation venue decision-id]
  (v/identity! :decision decision-id)
  (decision-binding! installation venue decision-id)
  {:decision/id decision-id :system (:system venue) :projection/id (:projection/id venue)})

(defn set-decider!
  "Replace one work binding at its expected version."
  [installation handle author {:keys [expected-version participant-id] :as request}]
  (locking (:lock installation)
    (let [r (some (fn [[_ owners]] (some #(when (= (:room/world (:record %))
                                                   (:anchor (systems/handle-data (:system handle)))) %) owners))
                  @(:owners installation))
          venue (assoc handle :room r)]
      (editor! installation venue author)
      (decision-binding! installation venue (:decision/id handle))
      (when-not (= #{:expected-version :participant-id} (set (keys request))) (v/fail! :invalid-request {}))
      (room/work! installation venue :discourse/write {:command :decider :id (:decision/id handle)
                                                       :expected-version expected-version :participant-id participant-id}))))
