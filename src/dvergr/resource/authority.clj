(ns dvergr.resource.authority
  "spindel's resource authority over dvergr's ledger (doc/worlds-and-forks.md).

   `world.scope/PResourceAuthority` is what a spindel scope asks when its
   worlds may spend something forking must not duplicate: a fork MOVES
   authority. dvergr's conserved wallets (`dvergr.resource`, Kontor transfers
   in the control Room's store) are that ledger. This authority lets spindel's
   own forks (a savepoint session's forks, an inference search over agent
   turns, any `world-scope/fork!` with `:grant`) spend and return dvergr
   budgets, so one ledger conserves everything.

   A world's wallet: a Run world's is its Run's (`[:dvergr/run-id]` in its
   state); a world an authority funded has one of its own
   (`[:dvergr/wallet]`), named by its fork id. Every transfer id is derived
   from what it moves, so a retry cannot move twice."
  (:require [dvergr.resource :as resource]
            [dvergr.room.store :as store]
            [kontor.resource :as kontor]
            [org.replikativ.spindel.engine.protocols :as rtp]
            [org.replikativ.spindel.world.scope :as world-scope])
  (:import [java.nio.charset StandardCharsets]
           [java.util UUID]))

(defn- stable-id [& parts]
  (UUID/nameUUIDFromBytes (.getBytes (pr-str (vec parts)) StandardCharsets/UTF_8)))

(defn- store! [room]
  (or (:store room)
      (throw (ex-info "The authority's Room has no store" {:type ::no-store :room (:id room)}))))

(defn wallet-of
  "The wallet account `ctx` spends from: its own, its Run's, or the Room's."
  [room ctx]
  (if-let [state (rtp/get-state ctx [:dvergr/wallet])]
    (if (and (= (:fork-id ctx) (:fork-id state)) (:account state))
      (:account state)
      (throw (ex-info "World has no owned funding authority"
                      {:type ::unfunded-world :fork-id (:fork-id ctx)})))
    (kontor/account-ref (resource/room-wallet-id (:id room)))))

(defn- open-funded-wallet!
  "Open wallet `wallet-id` for `ctx` and move `resources` into it from
   `source` in one transfer `transfer-id`; record where it came from."
  [room ctx wallet-id owner source resources transfer-id]
  (let [account (kontor/account-ref wallet-id)]
    ;; open, then fund: a failed grant leaves an empty wallet, never
    ;; duplicated authority (Run allocations have their own atomic path)
    (store/-open-resource-wallet! (store! room) (cond-> {:id wallet-id :name (str "world " (:fork-id ctx))}
                                                  owner (assoc :owner owner)))
    (let [command {:id transfer-id :kind :grant :source source :destination account
                   :resources resources :effective-date (java.util.Date.)}]
      (try
        (store/-transfer-resources! (store! room) command)
        (catch Throwable error
          ;; A committed grant can lose its acknowledgement. Replaying the
          ;; exact command asks the ledger to verify identity, never moves it
          ;; twice, and only then exposes the child's owned wallet.
          (if (store/-resource-receipt (store! room) transfer-id)
            (store/-transfer-resources! (store! room) command)
            (throw error)))))
    (rtp/swap-state! ctx [:dvergr/wallet] (constantly {:kind :world :fork-id (:fork-id ctx)
                                                       :account account :wallet wallet-id :room-id (:id room) :from source}))
    account))

(defn- remaining [room account]
  (not-empty (into {} (filter (fn [[_ v]] (pos? v)))
                   (store/-resource-balance (store! room) account))))

(defrecord LedgerAuthority [room]
  world-scope/PResourceAuthority
  (grant! [_ source-context child-context grant]
    (if (seq grant)
      (let [wallet-id (stable-id :dvergr/world-wallet (:fork-id child-context))]
        (open-funded-wallet! room child-context wallet-id nil
                             (wallet-of room source-context) grant
                             (stable-id :dvergr/grant wallet-id)))
      (rtp/swap-state! child-context [:dvergr/wallet]
                       (constantly {:kind :unfunded :fork-id (:fork-id child-context)}))))

  (return! [_ context]
    ;; only a wallet this authority opened goes back; a Run's wallet is the
    ;; Run's to return when it ends
    (let [{:keys [account from fork-id kind]} (rtp/get-state context [:dvergr/wallet])]
      (when (and (= kind :world) (= fork-id (:fork-id context)) account from)
        (when-let [left (remaining room account)]
          (store/-transfer-resources! (store! room)
                                      {:id (stable-id :dvergr/return account) :kind :return
                                       :source account :destination from :resources left
                                       :effective-date (java.util.Date.)})))))

  (escrow! [_ context key]
    (let [source (wallet-of room context)
          escrow-id (stable-id :dvergr/escrow key)]
      (store/-open-resource-wallet!
       (store! room) {:id escrow-id :name (str "escrow " key)})
      (when-let [left (remaining room source)]
        (store/-transfer-resources!
         (store! room)
         {:id (stable-id :dvergr/escrow-transfer key) :kind :grant
          :source source :destination (kontor/account-ref escrow-id) :resources left
          :effective-date (java.util.Date.)}))))

  (balance [_ context]
    ;; what a world's own wallet has left, for `world-scope/copy!` to split
    ;; between its copies; a world without one (a Run's world) has nothing
    ;; a copy may split
    (let [{:keys [account fork-id kind]} (rtp/get-state context [:dvergr/wallet])]
      (when (and (= kind :world) (= fork-id (:fork-id context)) account)
        (remaining room account))))

  (claim! [_ key context]
    (let [escrow (kontor/account-ref (stable-id :dvergr/escrow key))
          claimed (stable-id :dvergr/claim key)]
      (when (store/-resource-receipt (store! room) claimed)
        (throw (ex-info "The escrow was claimed already" {:type ::claimed :key key})))
      ;; Arrival installs a new wallet, never replaces a still-owned one.
      (let [{:keys [kind fork-id account]} (rtp/get-state context [:dvergr/wallet])]
        (when (and (= fork-id (:fork-id context)) account (contains? #{:world :run} kind))
          (throw (ex-info "Escrow arrival must be an unfunded world"
                          {:type ::funded-arrival :fork-id fork-id}))))
      (let [left (remaining room escrow)]
        (if left
          (do (open-funded-wallet! room context (stable-id :dvergr/world-wallet (:fork-id context))
                                   nil escrow left claimed)
              ;; what an arrived world leaves goes back to the ledger's root,
              ;; the Room: never into the escrow, which would claim again
              (rtp/swap-state! context [:dvergr/wallet :from]
                               (constantly (kontor/account-ref (resource/room-wallet-id (:id room))))))
          ;; an empty escrow is claimed by recording nothing to move
          (rtp/swap-state! context [:dvergr/wallet]
                           (constantly {:kind :unfunded :fork-id (:fork-id context) :claimed key})))))))

(defn authority
  "The ledger authority of control Room `room`."
  [room]
  (->LedgerAuthority (resource/register-ledger-room! room)))
