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

(defonce ^:private ledger-rooms
  ;; room id -> the control Room whose ledger holds the wallets a ledger
  ;; authority opened: a world records only its Room's id, since a Room
  ;; (store, connection) cannot live in forked state
  (atom {}))

(defn register-ledger-room!
  "Make `room`'s ledger reachable from the wallets of worlds its authority
   funds (`dvergr.resource.authority/authority` calls it)."
  [room]
  (swap! ledger-rooms assoc (:id room) room)
  room)

(defn- world-charge
  "Where a charge made now goes, and the ordinal of its debit.

   Spend follows the world (doc/design/speculation.md, Law 4): a world the
   ledger authority funded carries its own wallet and its own charge scope
   in its state, so its spend is charged to its share on whatever thread runs
   it. Inside a bound charge scope (`*spend-wallet*`, e.g. a Run's model
   step) the binding's ordinal names the debit, scoped further by the world;
   without one, a counter in the world's state does, so a replayed world
   debits the same ids. Otherwise the binding's payer pays, as before."
  []
  (let [bound *spend-wallet*
        ctx ec/*execution-context*
        {:keys [wallet room-id]} (some-> ctx (rtp/get-state [:dvergr/wallet]))
        room (when wallet (or (get @ledger-rooms room-id) (get-in bound [:payer :room])))]
    (cond
      (and wallet room)
      {:payer {:room room :wallet wallet}
       :id (if bound
             (spend-debit-id [(:scope bound) (:fork-id ctx)] (swap! (:debits bound) inc))
             (let [n (volatile! nil)]
               (rtp/swap-state! ctx [:dvergr/wallet :debits] #(vreset! n (inc (or % 0))))
               (spend-debit-id [:dvergr/world (:fork-id ctx)] @n)))}

      (:payer bound)
      {:payer (:payer bound)
       :id (spend-debit-id (:scope bound) (swap! (:debits bound) inc))})))

(defn- current-payer
  "The wallet a charge made now would come from (`world-charge`), without
   naming a debit: `{:room :wallet}` or nil."
  []
  (let [bound *spend-wallet*
        ctx ec/*execution-context*
        {:keys [wallet room-id]} (some-> ctx (rtp/get-state [:dvergr/wallet]))
        room (when wallet (or (get @ledger-rooms room-id) (get-in bound [:payer :room])))]
    (cond
      (and wallet room) {:room room :wallet wallet}
      (:payer bound) (:payer bound))))

;; ---------------------------------------------------------------------------
;; Dispatch reservations (doc/design/runs-as-worlds.md, Budgets as a tree)
;;
;; Admissions cap the number of provider calls, not their cost: a call in
;; flight when the process dies, or a streamed retry, can incur usage that is
;; never charged. A governed call that has a paying wallet therefore reserves
;; a conservative bound of its cost before every HTTP dispatch, under the
;; dispatch's own id, and each reservation is settled once:
;;
;; - a definitive refusal (an HTTP error status, or refused before it left)
;;   is returned whole at once, so the next attempt can reserve;
;; - the answer, when the provider reported its final usage, returns what
;;   exceeds that usage's cost to the wallet and moves the cost to the
;;   wallet's hold (`hold-wallet-id`), a durable account the charges that
;;   follow from the same usage (`charge-spend!`) consume first, under their
;;   own debit ids;
;; - every other outcome (a broken or truncated stream, an exception, a
;;   crash) is consumed whole.
;;
;; What a hold has is never spendable again, and it survives a restart: a
;; charge made, or replayed, after one draws on it, so a dispatch costs its
;; wallet its usage, or its bound when the outcome is unknown.
;; ---------------------------------------------------------------------------

(def ^:dynamic *dispatch-bound*
  "The microdollars a dispatch made now may cost at most, computed from the
   request (`dispatch-bound`), or nil."
  nil)

(def ^:dynamic *dispatches*
  "An atom of the dispatches of one model call (`[{:id :wallet :amount
   :payer :status :settled?}]`), bound by the caller that settles them."
  nil)

(defn hold-wallet-id
  "The account that holds, for the charges that follow, the settled cost of
   the answers `wallet` paid for."
  [wallet]
  (stable-id [:dvergr/hold wallet]))

(def dispatch-overhead-tokens
  "Input tokens a provider may add to a request on its own (a tool-use system
   prompt), beyond what the request body says."
  4096)

(def image-tokens
  "Input tokens one image part may cost at most, whatever its encoded size:
   providers scale an image to a bounded number of tiles."
  10000)

(defn- content-parts
  "Every map in a request body."
  [body]
  (filter map? (tree-seq coll? seq body)))

(def ^:private unbounded-part-types
  "Content parts whose cost their encoded size does not bound: a document's
   extracted text and page images, audio and video by duration."
  #{"document" "file" "input_file" "input_audio" "audio" "video"})

(defn- unbounded-content?
  "Does the request carry content whose cost its body does not show: content
   the provider fetches or keeps itself (a URL that is not a data URL, an
   uploaded file), or a document, audio or video part?"
  [body]
  (some (fn [m]
          (or (some (fn [k] (let [v (get m k)]
                              (and (string? v) (not (.startsWith ^String v "data:")))))
                    [:url "url"])
              (some #(contains? m %) [:file_id "file_id"])
              (contains? unbounded-part-types (or (:type m) (get m "type")))))
        (content-parts body)))

(defn- image-count [body]
  (count (filter (fn [m] (contains? #{"image" "image_url" "input_image"}
                                    (or (:type m) (get m "type"))))
                 (content-parts body))))

(defn dispatch-bound
  "A conservative bound, in microdollars, of what a request costs: its
   `body-bytes` (an upper bound on its text input tokens), what a provider
   adds on its own (`dispatch-overhead-tokens`) and `image-tokens` for each
   image part, at the model's highest input-side price (input, cache
   writes); plus its enforced output limit `max-output` (reasoning included)
   at the output price; all doubled for the premiums the registry's base
   rates leave out (long context, service tiers). Prices are USD per million
   tokens, so microdollars per token. nil when no reliable bound exists: no
   output limit, no input or output price, or content whose cost its size
   does not bound (`unbounded-content?`; `body`, the request, is checked)."
  [model-def body body-bytes max-output]
  (let [{:keys [input output cache-write cache-write-1h]} (:pricing model-def)]
    (when (and (number? input) (number? output) (pos-int? max-output) (pos-int? body-bytes)
               (not (unbounded-content? body)))
      (let [in-price (apply max (filter number? [input cache-write cache-write-1h]))
            in-tokens (+ body-bytes dispatch-overhead-tokens (* image-tokens (image-count body)))]
        (.setScale (bigdec (* 2 (+ (* in-tokens in-price) (* max-output output))))
                   0 java.math.RoundingMode/CEILING)))))

(defn- reserve!
  "Move `amount` from the paying wallet into a reservation of its own."
  [{:keys [room wallet] :as payer} amount]
  (let [id (UUID/randomUUID)
        reservation (stable-id [:dvergr/reservation id])]
    ;; an empty reservation left by a crash before the grant holds nothing
    (store/-open-resource-wallet! (resource-store! room)
                                  {:id reservation :name (str "dispatch " id)})
    (store/-transfer-resources! (resource-store! room)
                                {:id (stable-id [:dvergr/reserve id]) :kind :grant
                                 :source (kontor/account-ref wallet)
                                 :destination (kontor/account-ref reservation)
                                 :effective-date (java.util.Date.) :resources {microdollars amount}})
    (let [record {:id id :wallet reservation :amount amount :payer payer}]
      (swap! *dispatches* conj record)
      record)))

(defn reserve-dispatch!
  "Before a governed dispatch that has a paying wallet: move its bound
   (`*dispatch-bound*`) from that wallet into a reservation of its own; the
   ledger refuses what the wallet cannot cover. A dispatch without a bound,
   or without a model call to settle it (`*dispatches*`), is refused."
  []
  (when-let [payer (when *model-scope* (current-payer))]
    (let [amount *dispatch-bound*]
      (when-not (and amount (pos? amount))
        (throw (ex-info "A funded model dispatch needs a bound on its cost: a priced model, an output limit, no remote content"
                        {:type ::unbounded-dispatch})))
      (when-not *dispatches*
        (throw (ex-info "A funded model dispatch must be made by a model call that settles it"
                        {:type ::unsettled-dispatch})))
      (reserve! payer amount))))

(defn- move! [payer reservation kind id destination amount]
  (when (pos? amount)
    (store/-transfer-resources! (resource-store! (:room payer))
                                {:id id :kind kind
                                 :source (kontor/account-ref reservation)
                                 :destination destination
                                 :effective-date (java.util.Date.)
                                 :resources {microdollars amount}})))

(defn- release! [{:keys [id wallet amount payer]}]
  (move! payer wallet :return (stable-id [:dvergr/release id])
         (kontor/account-ref (:wallet payer)) amount))

(defn- forfeit! [{:keys [id wallet amount payer]}]
  (move! payer wallet :consume (stable-id [:dvergr/forfeit id]) kontor/sink-account amount))

(defn- hold! [{:keys [id wallet amount payer]} cost]
  (let [cost (min amount (max 0M (bigdec cost)))
        hold (hold-wallet-id (:wallet payer))]
    (when (pos? cost)
      (store/-open-resource-wallet! (resource-store! (:room payer))
                                    {:id hold :name (str "hold " (:wallet payer))})
      (move! payer wallet :grant (stable-id [:dvergr/hold id]) (kontor/account-ref hold) cost))
    (move! payer wallet :return (stable-id [:dvergr/release id])
           (kontor/account-ref (:wallet payer)) (- amount cost))))

(defn- refused? [status]
  (or (= :refused status) (and (number? status) (>= status 400))))

(defn dispatch-status!
  "Note the HTTP status of the latest dispatch of this model call; a refused
   dispatch's reservation is returned at once."
  [status]
  (when-let [ds *dispatches*]
    (when-let [r (peek @ds)]
      (when (and (not (:settled? r)) (nil? (:status r)))
        (when (refused? status) (release! r))
        (swap! ds (fn [v] (assoc v (dec (count v))
                                 (cond-> (assoc (peek v) :status status)
                                   (refused? status) (assoc :settled? true)))))))))

(defn settle-dispatches!
  "Settle the dispatches of one model call (`*dispatches*`) not settled yet:
   the latest successful one holds `cost` (microdollars from the final usage
   the provider reported, nil when it reported none) and returns the rest;
   every other one is consumed."
  [cost]
  (when-let [ds *dispatches*]
    (let [records (remove :settled? (first (reset-vals! ds [])))
          final (when cost (last (filter #(and (number? (:status %)) (< (:status %) 300)) records)))]
      (doseq [r records]
        (if (= r final) (hold! r cost) (forfeit! r))))))

(defn- draw-held!
  "Consume up to `cost` of what `payer`'s hold has (`hold-wallet-id`), under
   debit `id`; returns the amount drawn. Its caller holds the wallet's lock."
  [{:keys [room wallet] :as payer} id cost]
  (let [hold (hold-wallet-id wallet)
        ;; no hold opened yet holds nothing; any other failure to read it
        ;; must not send the charge to the wallet
        has (get (try (balance room (kontor/account-ref hold))
                      (catch clojure.lang.ExceptionInfo e
                        (if (= :kontor.resource/account-not-found (:type (ex-data e)))
                          {}
                          (throw e))))
                 microdollars 0M)
        n (min cost has)]
    (move! payer hold :consume id kontor/sink-account n)
    (max n 0M)))

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

   What the wallet's hold has (settled dispatch reservations,
   `hold-wallet-id`) is consumed first, a debit of its own derived from the
   charge's id, the rest from the wallet under the charge's id: a charge
   whose wallet debit exists is complete, and a replayed charge finds its
   draw. Which charge of a wallet draws on the hold does not change what the
   wallet pays in all.

   Spend follows the world (doc/design/speculation.md, Law 4): when the
   current world was granted a wallet of its own by the ledger authority
   (`dvergr.resource.authority`), that wallet pays.  See `world-charge`."
  [cost]
  (when (pos? cost)
    (when-let [{:keys [payer id]} (world-charge)]
      (let [{:keys [room wallet]} payer]
        (locking (wallet-lock wallet)
          (let [done (store/-resource-receipt (resource-store! room) id)
                draw-id (stable-id [:dvergr/held id])
                drawn (if-let [d (store/-resource-receipt (resource-store! room) draw-id)]
                        (get-in d [:resources microdollars] 0M)
                        (if done 0M (draw-held! payer draw-id (bigdec cost))))
                cost (- (bigdec cost) drawn)]
            (if (or done (not (pos? cost)))
              {:charged (+ drawn (get-in done [:resources microdollars] 0M))
               :exhausted? (<= (wallet-left payer) 0M)}
              ;; Other writers (a grant to a new Run, a fork's escrow) may take
                ;; from the wallet between the read and the debit; the ledger then
                ;; refuses the debit. The provider was paid: read the rest again
                ;; and take that.
              (loop [tries 0]
                (let [left (wallet-left payer)
                      amount (min cost left)
                      outcome (try
                                (when (pos? amount)
                                  (store/-transfer-resources!
                                   (resource-store! room)
                                   {:id id
                                    :kind :consume
                                    :source (kontor/account-ref wallet)
                                    :destination kontor/sink-account
                                    :resources {microdollars amount}}))
                                {:charged (+ drawn amount) :exhausted? (<= left cost)}
                                (catch clojure.lang.ExceptionInfo e e))]
                  (cond
                    (map? outcome) outcome
                    (store/-resource-receipt (resource-store! room) id)
                    {:charged (+ drawn amount) :exhausted? (<= left cost)}
                    (and (< tries 8) (< (wallet-left payer) amount)) (recur (inc tries))
                    :else (throw outcome)))))))))))

(defn charge-usage!
  "Record one model call's provider `usage` under `model` in the bound
   `spending` and charge what it cost at the registry's price
   (`dvergr.agent.spend/of-usage`) to its payer: for model calls made outside
   a chat context (a protocol's driver or reference candidate, a judge).
   A chat context's turns are charged by `chat.context/account-usage!`."
  [model usage]
  (when (map? usage)
    (when-let [{:keys [calls]} *spend-wallet*]
      (swap! calls conj {:model model :usage usage}))
    ;; charged where no scope is bound too: in a world with its own wallet
    (charge-spend! (:microdollars ((requiring-resolve 'dvergr.agent.spend/of-usage) model usage) 0))))

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
