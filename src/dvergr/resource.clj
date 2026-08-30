(ns dvergr.resource
  "Conserved, affine authority for Rooms and Runs.

   Resource facts live in the durable control Room store. Work-world forks may
   create or discard speculative application state, but they never copy or
   settle this authority: a child receives resources only through one governed
   Kontor transfer from its parent wallet."
  (:require [dvergr.room.store :as store]
            [kontor.resource :as kontor])
  (:import [java.nio.charset StandardCharsets]
           [java.util Date UUID]))

(def microdollars
  "The initial numeraire coordinate. Other coordinates remain ordinary data and
   may be installed by the trusted host API."
  "microUSD")

(defn- stable-id [value]
  (UUID/nameUUIDFromBytes
   (.getBytes (str value) StandardCharsets/UTF_8)))

(defn room-wallet-id
  "Stable wallet UUID for a durable Room keyword."
  [room-id]
  (stable-id (str "dvergr/resource/room|" room-id)))

(defn run-wallet-id
  "A Run's durable UUID is also its wallet identity."
  [run-id]
  run-id)

(defn allocation-id [run-id]
  (stable-id (str "dvergr/resource/allocation|" run-id)))

(defn return-id [run-id]
  (stable-id (str "dvergr/resource/return|" run-id)))

(defn- resource-store! [room]
  (let [resource-store (:store room)]
    (when-not (satisfies? store/PResourceStore resource-store)
      (throw (ex-info "Room has no durable conserved-resource store"
                      {:type ::resource-store-unavailable
                       :room-id (:id room)})))
    resource-store))

(defn install-connection!
  "Install the minimal Kontor kernel and the Room's root wallet on `conn`.

   This is a trusted boot boundary, not an SCI capability. The chat entity must
   already exist so its lookup ref can own the wallet."
  [conn room-id chat-id]
  (kontor/install! conn)
  (kontor/install-unit! conn {:symbol microdollars
                              :name "Micro US dollars"
                              :precision 0})
  (kontor/open-account! conn {:id (room-wallet-id room-id)
                              :owner [:chat/id chat-id]
                              :name (str "Room " room-id)})
  conn)

(defn install-unit!
  "Trusted host operation: install a resource coordinate in a Room's book."
  [room spec]
  (store/-install-resource-unit! (resource-store! room) spec))

(defn balance
  "Read a Room root wallet, or an explicitly named wallet UUID/ref."
  ([room]
   (balance room (kontor/account-ref (room-wallet-id (:id room)))))
  ([room account]
   (store/-resource-balance (resource-store! room) account)))

(defn run-balance [room run-id]
  (balance room (kontor/account-ref (run-wallet-id run-id))))

(defn mint!
  "Trusted host provisioning operation. `spec` requires a stable UUID `:id`
   plus a positive `:resources` vector; minting is never exposed inside SCI."
  [room {:keys [id resources effective-date posted-at actor]}]
  (store/-transfer-resources!
   (resource-store! room)
   (cond-> {:id id
            :kind :mint
            :source kontor/source-account
            :destination (kontor/account-ref (room-wallet-id (:id room)))
            :resources resources
            :effective-date (or effective-date (Date.))}
     posted-at (assoc :posted-at posted-at)
     actor (assoc :actor actor))))

(defn allocate-run!
  "Atomically create `run-id`'s wallet and split `resources` from its immediate
   parent Run wallet, or from the Room root wallet for a top-level Run."
  [room run-id parent-run resources]
  (when (seq resources)
    (let [resource-store (resource-store! room)
          parent-wallet (if parent-run
                          (run-wallet-id parent-run)
                          (room-wallet-id (:id room)))
          transfer {:id (allocation-id run-id)
                    :kind :grant
                    :source (kontor/account-ref parent-wallet)
                    :destination (kontor/account-ref (run-wallet-id run-id))
                    :resources resources
                    :effective-date (Date.)}]
      {:wallet (kontor/account-ref (run-wallet-id run-id))
       :receipt (store/-allocate-resource-wallet!
                 resource-store
                 {:id (run-wallet-id run-id)
                  :owner [:run/id run-id]
                  :name (str "Run " run-id)}
                 transfer)})))

(defn consume!
  "Consume resources from one Run into the global sink. A stable UUID `:id` is
   required so a retried effect cannot charge twice."
  [room run-id {:keys [id resources effective-date posted-at actor]}]
  (store/-transfer-resources!
   (resource-store! room)
   (cond-> {:id id
            :kind :consume
            :source (kontor/account-ref (run-wallet-id run-id))
            :destination kontor/sink-account
            :resources resources
            :effective-date (or effective-date (Date.))}
     posted-at (assoc :posted-at posted-at)
     actor (assoc :actor actor))))

(defn return!
  "Return an explicit positive vector from a Run to its immediate parent/Room."
  [room run-id parent-run resources]
  (store/-transfer-resources!
   (resource-store! room)
   {:id (return-id run-id)
    :kind :return
    :source (kontor/account-ref (run-wallet-id run-id))
    :destination (kontor/account-ref
                  (if parent-run
                    (run-wallet-id parent-run)
                    (room-wallet-id (:id room))))
    :resources resources
    :effective-date (Date.)}))
