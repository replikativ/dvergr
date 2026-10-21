(ns dvergr.system.room-intent
  "HOST control-store intent for explicit profiled-room creation. Native stores
   and executable policy do not participate in this transaction. Pending intent
   is NOT readiness and cannot authorize ordinary hydrate/create-if-missing."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [datahike.api :as d]
            [dvergr.substrate.paths :as paths]
            [dvergr.system.db :as db]))

(def schema
  (filterv #(#{:room/provisioning-state :room/provisioning-version} (:db/ident %)) db/schema))

(defn- refuse! [reason]
  (throw (ex-info "Profiled room creation intent refused" {:type ::refused :reason reason})))

(defn basis
  "HOST control-store snapshot only, not native readiness evidence. A pending
   creation's metadata/grants must remain exact while native work prepares.
   At most 32 grants are inspected; refuse amplification before pulling rows."
  [snapshot room-id]
  (let [eid (:db/id (d/entity snapshot [:room/id room-id]))
        grants (if eid (vec (take 33 (d/datoms snapshot :avet :grant/room eid))) [])]
    (when (> (count grants) 32) (refuse! :too-many-intent-grants))
    {:room (d/pull snapshot '[:room/id :room/slug :room/name :room/type :room/created :room/db-scope
                              :room/agent-ids :room/telegram-chat-id :room/parent-slug
                              :room/provisioning-state :room/provisioning-version
                              {:room/owner [:party/id]} {:room/default-repository [:system/id]}]
                   [:room/id room-id])
     :attachments (vec (sort-by #(str (:grant/id %))
                                (map (fn [datom]
                                       (d/pull snapshot '[:grant/id :grant/permission :grant/created
                                                          {:grant/system [:system/id :system/type :system/name :system/scope
                                                                          :system/created :system/repository-profile
                                                                          {:system/owner [:party/id]}]}] (:e datom))) grants)))}))

(defn- create-tx [snapshot entities slug owner-id]
  (when (d/entity snapshot [:room/slug slug]) (refuse! :slug-already-exists))
  (when (and owner-id (nil? (d/entity snapshot [:party/id owner-id]))) (refuse! :unknown-owner))
  entities)

(defn create!
  "Create ONE atomic registry intent, with stable UUIDs, default and owner
   grants. Refuses an existing slug rather than adopting/reclassifying it.
   The host must install schema and serialize native provisioning separately."
  [{:keys [slug name owner-id repository-profile] :as options}]
  (when-not (and (map? options) (every? #{:slug :name :owner-id :repository-profile} (keys options))
                 (string? slug) (<= 1 (count slug) 256) (not (str/blank? slug))
                 (or (nil? name) (and (string? name) (<= (count name) 512)))
                 (or (nil? owner-id) (uuid? owner-id)) (keyword? repository-profile))
    (refuse! :invalid-host-options))
  (let [conn (db/get-conn) room-id (random-uuid) at (java.util.Date.)
        systems (mapv (fn [type]
                        (cond-> {:system/id (random-uuid) :system/type type
                                 :system/name (str slug "-" (clojure.core/name type))
                                 :system/scope (str (io/file (paths/systems-dir) (str (random-uuid))))
                                 :system/created at}
                          owner-id (assoc :system/owner [:party/id owner-id])
                          (= :repo type) (assoc :system/repository-profile repository-profile))) [:repo :msgs :kb])
        repo-id (:system/id (first systems))
        room (cond-> {:room/id room-id :room/slug slug :room/name (or name slug) :room/type :project
                      :room/created at :room/db-scope (str (random-uuid))
                      :room/default-repository [:system/id repo-id]
                      :room/provisioning-state :pending :room/provisioning-version 1}
               owner-id (assoc :room/owner [:party/id owner-id]))
        grants (mapv (fn [system]
                       {:grant/id (random-uuid) :grant/room [:room/id room-id]
                        :grant/system [:system/id (:system/id system)] :grant/permission :owner :grant/created at}) systems)
        report (d/transact conn [[:db.fn/call create-tx (into (conj systems room) grants) slug owner-id]])]
    {:room (d/pull (:db-after report) '[*] [:room/id room-id]) :systems systems
     :basis (basis (:db-after report) room-id)}))

(defn- ready-tx [snapshot expected]
  (let [room-id (get-in expected [:room :room/id])
        actual (basis snapshot room-id)
        pending (assoc-in actual [:room :room/provisioning-state] :pending)]
    (when-not (and (uuid? room-id) (= 1 (get-in expected [:room :room/provisioning-version]))
                   (= :pending (get-in expected [:room :room/provisioning-state]))
                   (#{:pending :ready} (get-in actual [:room :room/provisioning-state]))
                   (= expected pending))
      (refuse! :creation-intent-changed))
    (if (= :ready (get-in actual [:room :room/provisioning-state])) []
        [[:db/add [:room/id room-id] :room/provisioning-state :ready]])))

(defn mark-ready!
  "HOST ONLY, after successful owned native preparation: CAS the exact control
   intent BEFORE publishing the live context. This does not inspect/prove native
   stores. An acknowledgement-loss retry is idempotent only for the same intent."
  [expected]
  (let [report (d/transact (db/get-conn) [[:db.fn/call ready-tx expected]])]
    (get-in (basis (:db-after report) (get-in expected [:room :room/id])) [:room])))
