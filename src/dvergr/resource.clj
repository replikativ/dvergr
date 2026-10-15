(ns dvergr.resource
  "Conserved, affine authority for Rooms and Runs.

   Resource facts live in the durable control Room store. Work-world forks may
   create or discard speculative application state, but they never copy or
   settle this authority: a child receives resources only through one governed
   Kontor transfer from its parent wallet."
  (:require [dvergr.agent.attempt.governance :as attempt-governance]
            [dvergr.room.store :as store]
            [kontor.resource :as kontor]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.engine.protocols :as rtp])
  (:import [java.nio.charset StandardCharsets]
           [java.util UUID]))

(def microdollars
  "The initial numeraire coordinate. Other coordinates remain ordinary data and
   may be installed by the trusted host API."
  "microUSD")

(def model-dispatches
  "Optional conserved allowance for native provider HTTP dispatch admissions.
   This is neither a token budget nor a price. Each retry needs another unit."
  "model-dispatch")

(def ^:dynamic *model-scope*
  "Trusted process-local scope for a resource-governed model worker. Balances
   remain in Kontor's control store, never in this binding or a forked counter."
  nil)

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
  ;; Kontor currently owns Datahike's single tx-predicate registration slot.
  ;; Recompose the Attempt governor after every (idempotent) installation so
  ;; neither invariant family can replace the other by boot order.
  (attempt-governance/govern! conn)
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

(defn run-wallet-balance
  "The balance of Run `run-id`'s wallet, or nil when the Run has none: its
   Room has no conserved-resource store, or the Run was never granted a
   wallet. A failed read throws."
  [room run-id]
  (try
    (run-balance room run-id)
    (catch clojure.lang.ExceptionInfo e
      (if (contains? #{::resource-store-unavailable :kontor.resource/account-not-found}
                     (:type (ex-data e)))
        nil
        (throw e)))))

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
            :resources resources}
     effective-date (assoc :effective-date effective-date)
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
          started-at (:run/started-at
                      (store/-load-run resource-store (store/conversation-id room) run-id))
          _ (when-not started-at
              (throw (ex-info "Run resource allocation requires a durable Run"
                              {:type ::run-not-durable :run/id run-id})))
          transfer {:id (allocation-id run-id)
                    :kind :grant
                    :source (kontor/account-ref parent-wallet)
                    :destination (kontor/account-ref (run-wallet-id run-id))
                    :resources resources
                    :effective-date started-at}]
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
            :resources resources}
     effective-date (assoc :effective-date effective-date)
     posted-at (assoc :posted-at posted-at)
     actor (assoc :actor actor))))

(defn model-scope
  "Resolve opt-in dispatch authority from the original durable Run allocation,
   not its remaining balance (zero must never disable enforcement)."
  [room run-id]
  (when (satisfies? store/PResourceStore (:store room))
    (let [allocation (store/-resource-receipt (:store room) (allocation-id run-id))]
      (when (contains? (:resources allocation) model-dispatches)
        {:room room :run-id run-id}))))

(def ^:dynamic *spend-wallet*
  "Where model spend made here goes: a `spending` value, bound around the
   model calls of one charge scope (an LLM Run's model step, a protocol Run,
   an Attempt's observation). Its `:payer` is the conserved microUSD wallet
   that pays (`{:room :wallet}`, nil to only record); unlike `*model-scope*`
   it restricts no provider: it only moves spend onto the ledger
   (doc/worlds-and-forks.md, Worlds, savepoints and budgets)."
  nil)

(defn spend-wallet
  "The spend wallet of `run-id` in control Room `room`: when its allocation
   carries microdollars."
  [room run-id]
  (when (satisfies? store/PResourceStore (:store room))
    (let [allocation (store/-resource-receipt (:store room) (allocation-id run-id))]
      (when (contains? (:resources allocation) microdollars)
        {:room room :run-id run-id :wallet (run-wallet-id run-id)}))))

(defn room-spend-wallet
  "The Room root wallet of `room` as a spend wallet: when it holds
   microdollars."
  [room]
  (when (satisfies? store/PResourceStore (:store room))
    (let [wallet (room-wallet-id (:id room))]
      (when (contains? (try (balance room (kontor/account-ref wallet))
                            (catch clojure.lang.ExceptionInfo _ {}))
                       microdollars)
        {:room room :wallet wallet}))))

(defn spending
  "A `*spend-wallet*` value: model spend in charge scope `scope` (portable
   data naming what is charged, e.g. `[:llm-step run-id 3]`) paid by `payer`
   (a `spend-wallet`, or nil to only record). Each debit's id is derived from
   `scope` and the debit's ordinal within it, so the same scope charged again
   (a retried step) debits once, and each further charge in it is a new
   debit. `:calls` records the provider usage `charge-usage!` was given."
  [payer scope]
  {:payer payer :scope scope :debits (atom 0) :calls (atom [])})

(defn spend-debit-id
  "The debit id of the `n`th charge (from 1) in charge scope `scope`."
  [scope n]
  (stable-id (pr-str [:dvergr/resource-spend scope n])))

(defn- wallet-left [{:keys [room wallet]}]
  (get (balance room (kontor/account-ref wallet)) microdollars 0M))

(defn wallet-microdollars
  "What is left in `run-id`'s wallet, in microdollars."
  [room run-id]
  (get (run-balance room run-id) microdollars 0M))

(defonce ^:private wallet-locks
  ;; wallet uuid -> monitor: a capped debit reads what is left and then
  ;; transfers, so charges to one wallet (parallel observations sharing the
  ;; Room's or a parent's) are serialized within the process
  (atom {}))

(defn- wallet-lock [wallet]
  (or (get @wallet-locks wallet)
      (get (swap! wallet-locks #(if (contains? % wallet) % (assoc % wallet (Object.)))) wallet)))

(defn- world-payer
  "`payer`, or the current world's own wallet when the ledger authority
   granted it one."
  [payer]
  (if-let [own (some-> ec/*execution-context* (rtp/get-state [:dvergr/wallet :wallet]))]
    (assoc payer :wallet own)
    payer))

(defn charge-spend!
  "Consume `cost` microdollars of model spend from the bound spend wallet.
   The provider was already paid, so the ledger cannot refuse it: a cost
   beyond what is left takes the rest; charges to one wallet are serialized,
   and a debit refused because another writer took from the wallet meanwhile
   takes the new rest. The debit's id is the next one of the
   binding's charge scope (`spending`): when that debit was already made (the
   scope charged again, a retried step), it is not made twice. Returns
   `{:charged n :exhausted? bool}`, or nil when no wallet is bound or there is
   nothing to charge.

   Spend follows the world (doc/design/speculation.md, Law 4): when the
   current world was granted a wallet of its own by the ledger authority
   (`dvergr.resource.authority`), the charge is taken from that wallet, so a
   forked world cannot spend more than its share; otherwise the bound payer
   pays."
  [cost]
  (when-let [{:keys [payer scope debits]} *spend-wallet*]
    (when (and payer (pos? cost))
      (let [payer (world-payer payer)
            {:keys [room wallet]} payer
            id (spend-debit-id scope (swap! debits inc))]
        (locking (wallet-lock wallet)
          (if-let [done (store/-resource-receipt (resource-store! room) id)]
            {:charged (get-in done [:resources microdollars] 0M)
             :exhausted? (<= (wallet-left payer) 0M)}
            ;; Other writers (a grant to a new Run, a fork's escrow) may take
            ;; from the wallet between the read and the debit; the ledger then
            ;; refuses the debit. The provider was paid: read the rest again
            ;; and take that.
            (loop [tries 0]
              (let [left (wallet-left payer)
                    amount (min (bigdec cost) left)
                    outcome (try
                              (when (pos? amount)
                                (store/-transfer-resources!
                                 (resource-store! room)
                                 {:id id
                                  :kind :consume
                                  :source (kontor/account-ref wallet)
                                  :destination kontor/sink-account
                                  :resources {microdollars amount}}))
                              {:charged amount :exhausted? (<= left (bigdec cost))}
                              (catch clojure.lang.ExceptionInfo e e))]
                (cond
                  (map? outcome) outcome
                  (store/-resource-receipt (resource-store! room) id)
                  {:charged amount :exhausted? (<= left (bigdec cost))}
                  (and (< tries 8) (< (wallet-left payer) amount)) (recur (inc tries))
                  :else (throw outcome))))))))))

(defn charge-usage!
  "Record one model call's provider `usage` under `model` in the bound
   `spending` and charge what it cost at the registry's price
   (`dvergr.agent.spend/of-usage`) to its payer: for model calls made outside
   a chat context (a protocol's driver or reference candidate, a judge).
   A chat context's turns are charged by `chat.context/account-usage!`."
  [model usage]
  (when-let [{:keys [calls]} *spend-wallet*]
    (when (map? usage)
      (swap! calls conj {:model model :usage usage})
      (charge-spend! (:microdollars ((requiring-resolve 'dvergr.agent.spend/of-usage) model usage) 0)))))

(defn admit-model-dispatch!
  "Spend one admission immediately before native model egress. No read/check/
   write race: Kontor validates the debit atomically. Rejected admission never
   reaches the provider. An admitted failed/ambiguous dispatch is not refunded.
   Each gateway retry has a new identity, not a replay of the previous effect:
   an admission pays for an egress, not for a result, so unlike a spend debit
   (`charge-spend!`) its id is fresh on purpose."
  []
  (when-let [{:keys [room run-id cancel?]} *model-scope*]
    (when (or (.isInterrupted (Thread/currentThread)) (and cancel? (cancel?)))
      (throw (java.util.concurrent.CancellationException. "Model admission cancelled")))
    (consume! room run-id {:id (UUID/randomUUID)
                           :resources {model-dispatches 1M}})))

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
    :resources resources}))
