(ns dvergr.resource
  "Conserved, affine authority for Rooms and Runs.

   Resource facts live in the durable control Room store. Work-world forks may
   create or discard speculative application state, but they never copy or
   settle this authority: a child receives resources only through one governed
   Kontor transfer from its parent wallet."
  (:require [dvergr.model.registry :as registry]
            [dvergr.room.store :as store]
            [kontor.resource.identity :as kontor]
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

(def cli-run-admissions
  "One bounded, non-resumable Claude text Run, not one internal HTTP request."
  "cli-run-admission")

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
   already exist so its lookup ref can own the wallet. Database initialization
   loads only at this host boot boundary, not in model/gateway clients."
  [conn room-id chat-id]
  ((requiring-resolve 'dvergr.resource.datahike/install-connection!) conn room-id chat-id))

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
                  :purpose :run
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

(defn- current-payer
  "Select one owned payer. Copied authority is never a fallback to ambient
   bindings; explicitly unfunded worlds cannot make governed charges."
  []
  (let [ctx ec/*execution-context*
        state (some-> ctx (rtp/get-state [:dvergr/wallet]))
        bound (:payer *spend-wallet*)]
    (if state
      (do
        (when (or (not= (:fork-id ctx) (:fork-id state))
                  (= :unfunded (:kind state))
                  (contains? #{:pending :refused} (:funding state)))
          (throw (ex-info "World has no owned spending authority"
                          {:type ::unfunded-world :fork-id (:fork-id ctx)})))
        (let [room (or (get @ledger-rooms (:room-id state))
                       (when (= (:room-id state) (get-in bound [:room :id])) (:room bound)))]
          (when-not room
            (throw (ex-info "World's resource ledger is unavailable"
                            {:type ::resource-store-unavailable :room-id (:room-id state)})))
          (case (:kind state)
            :run {:room room :run-id (:run-id state) :wallet (:wallet state)}
            :world {:room room :wallet (:wallet state)}
            (throw (ex-info "Unknown world funding state" {:type ::invalid-world-wallet})))))
      bound)))

(defn- monetary-payer []
  (when-let [{:keys [room run-id] :as payer} (current-payer)]
    ;; Identity does not depend on the money coordinate: admission-only Runs
    ;; still own a payer, but monetary accounting remains opt-in.
    (if run-id (spend-wallet room run-id) payer)))

(defn require-model-scope!
  "A dispatch-governed context cannot lose governance on another thread.
   Money-only legacy Runs keep their accounting without admission enforcement;
   an explicitly unfunded speculative child is not a legacy Run."
  []
  (when-let [state (some-> ec/*execution-context* (rtp/get-state [:dvergr/wallet]))]
    (let [{:keys [room run-id]} (current-payer)
          funded? (or (= :world (:kind state))
                      (and run-id (model-scope room run-id)))]
      (when (and funded? (nil? *model-scope*))
        (throw (ex-info "Funded world dispatch requires a host model-call scope"
                        {:type ::missing-model-scope :fork-id (:fork-id ec/*execution-context*)}))))))

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
        state (some-> ctx (rtp/get-state [:dvergr/wallet]))
        payer (monetary-payer)]
    (cond
      (and payer (= :world (:kind state)))
      {:payer payer
       :id (if bound
             (spend-debit-id [(:scope bound) (:fork-id ctx)] (swap! (:debits bound) inc))
             (let [n (volatile! nil)]
               (rtp/swap-state! ctx [:dvergr/wallet :debits] #(vreset! n (inc (or % 0))))
               (spend-debit-id [:dvergr/world (:fork-id ctx)] @n)))}

      payer
      {:payer payer
       :id (if bound
             (spend-debit-id (:scope bound) (swap! (:debits bound) inc))
             (let [n (volatile! nil)]
               (rtp/swap-state! ctx [:dvergr/wallet :debits] #(vreset! n (inc (or % 0))))
               (spend-debit-id [:dvergr/run (:fork-id ctx)] @n)))})))

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
  "The maps of a request body's content, without what is data to the model
   rather than content the provider reads: a tool call's arguments (`:input`
   of a `tool_use` part, `:arguments`), tool definitions (`:tools`)."
  [body]
  (letfn [(kind [m] (or (:type m) (get m "type")))
          (children [x]
            (cond
              (map? x) (vals (cond-> (apply dissoc x [:tools "tools" :arguments "arguments"])
                               (#{"tool_use" "function_call"} (kind x))
                               (dissoc :input "input")))
              (coll? x) (seq x)))]
    (filter map? (tree-seq coll? children body))))

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
   writes) in any of its bands (`registry/highest-pricing`, long context
   included); plus its enforced output limit `max-output` (reasoning
   included) at the highest output price; all doubled for what the registry
   does not model (service tiers). Prices are USD per million
   tokens, so microdollars per token. Explicitly zero effective rates across
   all price bands have a zero monetary bound even without an output cap
   (native subscriptions). Dispatch admission still applies. nil when no
   reliable bound exists: missing/invalid prices, or a priced request without
   an output limit or with content whose cost its size does not bound."
  [model-def body body-bytes max-output]
  (let [bands (remove nil? [(:pricing model-def) (get-in model-def [:long-context :pricing])])
        valid-rate? #(and (number? %) (Double/isFinite (double %)) (not (neg? %)))]
    (when (and (map? (:pricing model-def))
               (valid-rate? (get-in model-def [:pricing :input]))
               (valid-rate? (get-in model-def [:pricing :output]))
               (every? map? bands) (every? valid-rate? (mapcat vals bands)))
      (let [{:keys [input output cache-read cache-write cache-write-1h] :as rates}
            (registry/highest-pricing model-def)]
        (when (and (number? input) (number? output))
          (if (every? zero? (vals rates))
            0M
            (when (and (pos-int? max-output) (pos-int? body-bytes) (not (unbounded-content? body)))
              (let [in-price (apply max (filter number? [input cache-read cache-write cache-write-1h]))
                    in-tokens (+ body-bytes dispatch-overhead-tokens (* image-tokens (image-count body)))]
                (.setScale (bigdec (* 2 (+ (* in-tokens in-price) (* max-output output))))
                           0 java.math.RoundingMode/CEILING)))))))))

(declare return!)

(defn reconcile-return!
  "Host cleanup boundary: AFTER physical quiescence, reconcile an uncertain
   allocation and return its remaining balance. Store failures retain ownership
   and retry with stable transfer identities. Never call from a drain."
  [room run-id parent-run allocation-state]
  (let [allocated?
        (loop []
          (case @allocation-state
            (:not-requested :not-started) false
            :allocated true
            (:attempting :uncertain)
            (let [outcome (try
                            (if (satisfies? store/PResourceStore (:store room))
                              (boolean (store/-resource-receipt (:store room) (allocation-id run-id)))
                              false)
                            (catch Throwable t t))]
              (if (instance? Throwable outcome)
                (do (Thread/sleep 25) (recur))
                outcome))))]
    (when allocated?
      (loop []
        (let [outcome (try
                        (let [remaining (run-balance room run-id)]
                          (when (seq remaining) (return! room run-id parent-run remaining)))
                        :returned
                        (catch Throwable t t))]
          (when (instance? Throwable outcome)
            (Thread/sleep 25)
            (recur)))))))

(defn funded-grant?
  "Does a child's grant `resources` fund provider calls: money to pay them
   and admissions to make them (doc/design/runs-as-worlds.md, Budgets as a
   tree)? A child so funded reserves each dispatch's cost from its own wallet
   (`reserve-dispatch!`), so its parent's grant bounds what it can spend."
  [resources]
  (boolean (and (pos? (get resources microdollars 0))
                (pos? (get resources model-dispatches 0)))))

(def ^:dynamic *dispatch-id* nil)

(defn- current-dispatch []
  (when *dispatches*
    (if *dispatch-id*
      (some #(when (= *dispatch-id* (:id %)) %) @*dispatches*)
      (peek @*dispatches*))))

(defn- update-dispatch! [id f & args]
  (apply swap! *dispatches*
         (fn [records & xs]
           (mapv #(if (= id (:id %)) (apply f % xs) %) records)) args))

(defn- dispatch-record [id]
  (some #(when (= id (:id %)) %) @*dispatches*))

(defn- submit-dispatch-command!
  "Retain the exact intent until the ledger acknowledges it. Only Kontor's
   synchronous insufficient-balance rejection proves a definite refusal."
  [id key payer]
  (let [record (dispatch-record id) command (get record key)
        state-key (keyword (str (name key) "-state"))]
    (when (and command (not (#{:acknowledged :refused} (get record state-key))))
      (update-dispatch! id assoc state-key :submitted)
      (try
        (store/-transfer-resources! (resource-store! (:room payer)) command)
        (update-dispatch! id assoc state-key :acknowledged)
        (catch Throwable error
          (when (and (#{:reserve-command :admission-command} key)
                     (= :kontor.resource/insufficient (:type (ex-data error))))
            (update-dispatch! id assoc state-key :refused))
          (throw (ex-info "Model dispatch accounting requires reconciliation"
                          {:type ::dispatch-accounting-failed :dispatch id :phase key} error)))))))

(declare append-dispatch!)

(defn- open-dispatch-wallet! [room spec]
  (try (store/-open-resource-wallet! (resource-store! room) spec)
       (catch Throwable error
         (throw (ex-info "Model dispatch wallet requires reconciliation"
                         {:type ::dispatch-accounting-failed :phase :open-wallet :wallet (:id spec)} error)))))

(defn- reserve!
  "Publish reservation identity BEFORE either wallet creation or transfer."
  [{:keys [room wallet] :as payer} amount]
  (let [id (UUID/randomUUID)
        reservation (stable-id [:dvergr/reservation id])
        record {:id id :wallet reservation :amount amount :payer payer
                :reserve-command {:id (stable-id [:dvergr/reserve id]) :kind :grant
                                  :source (kontor/account-ref wallet)
                                  :destination (kontor/account-ref reservation)
                                  :effective-date (java.util.Date.) :resources {microdollars amount}}}]
    (append-dispatch! record)
    (open-dispatch-wallet! room
                           {:id reservation :purpose :reservation :name (str "dispatch " id)})
    (submit-dispatch-command! id :reserve-command payer)
    (dispatch-record id)))

(defn reserve-dispatch!
  "Begin one governed dispatch before any ledger submission or HTTP entry.
   Zero-price and admission-only calls retain their admission identity too."
  []
  (require-model-scope!)
  (when *model-scope*
    (let [payer (monetary-payer) amount *dispatch-bound*]
      (when payer
        (when-not (and (number? amount) (Double/isFinite (double amount)) (not (neg? amount)))
          (throw (ex-info "A funded model dispatch needs a bound on its cost: a priced model, an output limit, no remote content"
                          {:type ::unbounded-dispatch})))
        (when-not *dispatches*
          (throw (ex-info "A funded model dispatch must be made by a model call that settles it"
                          {:type ::unsettled-dispatch}))))
      (if (and payer (pos? amount))
        (reserve! payer amount)
        (when *dispatches*
          (let [record {:id (UUID/randomUUID) :amount 0M :payer payer}]
            (append-dispatch! record)
            record))))))

(defn dispatch-entered!
  "The next native call may reach the provider. Recovery never enters HTTP."
  []
  (when-let [record (current-dispatch)]
    (update-dispatch! (:id record) assoc :egress-entered? true)))

(defn- move! [payer reservation kind id destination amount]
  (when (pos? amount)
    ;; Generated audit dates belong to Kontor. Explicit fresh dates would
    ;; change a stable command's replay payload after a lost acknowledgement.
    (store/-transfer-resources! (resource-store! (:room payer))
                                {:id id :kind kind
                                 :source (kontor/account-ref reservation)
                                 :destination destination :resources {microdollars amount}})))

(defn- refused? [status]
  (or (= :refused status) (and (number? status) (>= status 400))))

(defn- settlement-plan [{:keys [id wallet amount payer status egress-entered?]} cost final?]
  (let [kind (cond (or (not egress-entered?) (refused? status)) :release
                   (and final? (some? cost)) :hold
                   :else :forfeit)
        held (if (= kind :hold) (min amount (max 0M (bigdec cost))) 0M)
        command (fn [suffix kind destination n]
                  (when (pos? n)
                    {:id (stable-id [suffix id]) :kind kind
                     :source (kontor/account-ref wallet) :destination destination
                     :effective-date (java.util.Date.) :resources {microdollars n}}))]
    {:kind kind :held held :reported-cost (when final? cost)
     :hold-command (when (pos? held)
                     (command :dvergr/hold :grant
                              (kontor/account-ref (hold-wallet-id (:wallet payer))) held))
     :release-command (when (#{:hold :release} kind)
                        (command :dvergr/release :return (kontor/account-ref (:wallet payer)) (- amount held)))
     :forfeit-command (when (= :forfeit kind)
                        (command :dvergr/forfeit :consume kontor/sink-account amount))}))

(defn- settle-record! [id]
  (let [{:keys [payer admission-payer reserve-command reserve-command-state] :as r} (dispatch-record id)]
    (when-not (:settled? r)
      (when (and reserve-command (not= :refused reserve-command-state))
        ;; Wallet creation may itself have lost its acknowledgement.
        (open-dispatch-wallet! (:room payer)
                               {:id (:wallet r) :purpose :reservation :name (str "dispatch " id)})
        (submit-dispatch-command! id :reserve-command payer))
      (when (:admission-command r)
        (submit-dispatch-command! id :admission-command admission-payer))
      (when-not (= :refused (:reserve-command-state (dispatch-record id)))
        (when (pos? (get-in r [:settlement :held] 0))
          (let [hold (hold-wallet-id (:wallet payer))]
            (open-dispatch-wallet! (:room payer)
                                   {:id hold :purpose :hold :name (str "hold " (:wallet payer))})))
        (doseq [key [:hold-command :release-command :forfeit-command]]
          (submit-dispatch-command! id key payer)))
      (update-dispatch! id assoc :settled? true))))

(defn dispatch-status!
  "Record refusal evidence before its fallible refund. A lost refund ACK must
   never turn a known refusal into ambiguous provider spend."
  [status]
  (when-let [r (current-dispatch)]
    (when (and (not (:settled? r)) (nil? (:status r)))
      (let [id (:id r)]
        ;; A native HTTP status also acknowledges provider entry. The
        ;; pre-invocation marker remains necessary for response-less failures.
        (update-dispatch! id #(cond-> (assoc % :status status)
                                (number? status) (assoc :egress-entered? true)))
        (when (refused? status)
          (let [plan (settlement-plan (dispatch-record id) nil false)]
            (update-dispatch! id #(if (:settlement %) % (merge % plan {:settlement plan}))))
          (settle-record! id))))))

(defn settle-dispatches!
  "Freeze every unresolved plan before its first ledger submission. Retry
   resumes the original commands, including a partially acknowledged hold and
   return; no record is drained before acknowledgement."
  [cost]
  (when-let [ds *dispatches*]
    (let [records @ds
          final (when (some? cost)
                  (:id (last (filter #(and (number? (:status %)) (< (:status %) 300)) records))))]
      (doseq [r records
              :when (and (some? cost) (= :hold (get-in r [:settlement :kind]))
                         (not= (bigdec cost) (bigdec (get-in r [:settlement :reported-cost]))))]
        (throw (ex-info "Dispatch settlement cost changed" {:type ::settlement-conflict :dispatch (:id r)})))
      (swap! ds (fn [rs]
                  (mapv (fn [r]
                          (if (or (:settled? r) (:settlement r)) r
                              (let [plan (settlement-plan r cost (= final (:id r)))]
                                (merge r plan {:settlement plan})))) rs)))
      (doseq [r @ds :when (not (:settled? r))] (settle-record! (:id r))))))

(defn make-dispatch-owner
  "Private host custody for one executor's model calls. No balances live here;
   Kontor remains authoritative. Never put this owner in forkable Room state."
  []
  (atom {:sealed? false :calls {} :hold-transfers []}))

(def ^:dynamic *dispatch-call* nil)
(def ^:dynamic *dispatch-thread* nil)

(defn reusable-dispatch-call?
  "A conveyed binding is not ownership of its originating call. Raw requests
   on another thread or after that call exits must register a fresh call."
  []
  (boolean
   (and *dispatch-call* (identical? *dispatch-thread* (Thread/currentThread))
        (if-let [owner (:dispatch-owner *model-scope*)]
          (let [call (get-in @owner [:calls *dispatch-call*])]
            (and (= :active (:phase call)) (identical? *dispatches* (:dispatches call))))
          true))))

(defn- append-dispatch! [record]
  (let [owner (:dispatch-owner *model-scope*)]
    (locking (or owner *dispatches*)
      (when (and owner (:sealed? @owner))
        (throw (ex-info "Model dispatch owner is sealed" {:type ::dispatch-owner-sealed})))
      (when (and owner (not (reusable-dispatch-call?)))
        (throw (ex-info "Model dispatch has no live call owner" {:type ::dispatch-call-unowned})))
      (swap! *dispatches* conj record))))

(defn- complete-dispatch-call! [owner id ds]
  (when owner
    (locking owner
      (when (identical? ds (get-in @owner [:calls id :dispatches]))
        (let [holds (for [r @ds :when (pos? (get-in r [:settlement :held] 0))]
                      {:dispatch (:id r) :payer-wallet (get-in r [:payer :wallet])
                       :hold-wallet (hold-wallet-id (get-in r [:payer :wallet]))
                       :transfer (get-in r [:hold-command :id])
                       :usage-consumption :not-established})]
          (swap! owner #(-> % (update :calls dissoc id)
                            (update :hold-transfers into holds))))))))

(defn- hold-dispatch-call! [owner id ds error]
  (when owner
    (locking owner
      (when (identical? ds (get-in @owner [:calls id :dispatches]))
        (swap! owner update-in [:calls id] assoc :phase :exited :error error)))))

(defn call-with-dispatch-accounting!
  "Retain a model call before effects and through its physical exit. On error,
   the original owner keeps every unresolved ledger command. This function
   never repeats inference or authorizes another dispatch."
  [f]
  (let [owner (:dispatch-owner *model-scope*)
        id (UUID/randomUUID) ds (atom [])]
    (when owner
      (locking owner
        (when (:sealed? @owner)
          (throw (ex-info "Model dispatch owner is sealed" {:type ::dispatch-owner-sealed})))
        (swap! owner assoc-in [:calls id] {:dispatches ds :phase :active})))
    (binding [*dispatches* ds *dispatch-call* id *dispatch-id* nil
              *dispatch-thread* (Thread/currentThread)]
      (try
        (let [result (f)]
          (settle-dispatches! nil)
          (complete-dispatch-call! owner id ds)
          result)
        (catch Throwable original
          (try
            (settle-dispatches! nil)
            (complete-dispatch-call! owner id ds)
            (catch Throwable reconciliation
                ;; Only now has this thread stopped touching the call. A HOST
                ;; reconciler may claim it after this monotonic handoff.
              (hold-dispatch-call! owner id ds reconciliation)
              (when-not (identical? original reconciliation)
                (.addSuppressed original reconciliation))))
          (throw original))))))

(defmacro with-dispatch-accounting [& body]
  `(call-with-dispatch-accounting! (fn [] ~@body)))

(defn dispatch-accounting-state
  "Read-only process-local acknowledgement facts. Hold transfers are custody
   evidence, NOT proof of usage consumption. Missing owner is unknown."
  [owner]
  (if-not owner
    {:reservation-reconciliation :unknown :usage-consumption :not-established}
    (let [{:keys [sealed? calls hold-transfers]} @owner]
      {:admission-sealed? sealed?
       :reservation-reconciliation (if (empty? calls) :complete :pending)
       :usage-consumption :not-established
       :hold-transfers hold-transfers
       :calls (mapv (fn [[id {:keys [phase dispatches error]}]]
                      {:id id :phase phase :error-class (some-> error class .getName)
                       :dispatches (mapv #(select-keys % [:id :wallet :amount :status :egress-entered?
                                                          :reserve-command-state :admission-command-state
                                                          :hold-command-state :release-command-state
                                                          :forfeit-command-state :settled?]) @dispatches)}) calls)})))

(defn reconcile-dispatch-owner!
  "After the executor's physical join, close admission and retain the original
   owner until all submitted reservation/admission commands acknowledge.
   Active conveyed calls are not classified as exited. This native finalizer
   retries exact ledger commands only, never HTTP or consumer usage charging."
  [owner]
  (when owner
    (locking owner (swap! owner assoc :sealed? true))
    (loop []
      (doseq [id (keys (:calls @owner))]
        (when-let [call (locking owner
                          (let [current (get-in @owner [:calls id])]
                            (when (= :exited (:phase current))
                              (swap! owner assoc-in [:calls id :phase] :reconciling)
                              current)))]
          (let [ds (:dispatches call)]
            (binding [*dispatches* ds]
              (try
                (settle-dispatches! nil)
                (complete-dispatch-call! owner id ds)
                (catch Throwable error (hold-dispatch-call! owner id ds error)))))))
      (when (seq (:calls @owner))
        ;; Cleanup is externally supervised, like reconcile-return!. A store
        ;; failure or interrupt cannot silently abandon its owned obligations.
        (try (Thread/sleep 25) (catch InterruptedException _))
        (recur)))))

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
  (require-model-scope!)
  (when-let [{:keys [room run-id cancel?]} *model-scope*]
    (when (or (.isInterrupted (Thread/currentThread)) (and cancel? (cancel?)))
      (throw (java.util.concurrent.CancellationException. "Model admission cancelled")))
    (let [payer (or (current-payer) {:room room :wallet (run-wallet-id run-id)})
          command {:id (UUID/randomUUID) :kind :consume
                   :source (kontor/account-ref (:wallet payer)) :destination kontor/sink-account
                   :resources {model-dispatches 1M}}]
      (if-let [record (current-dispatch)]
        (do (update-dispatch! (:id record) assoc :admission-command command :admission-payer payer)
            (submit-dispatch-command! (:id record) :admission-command payer))
        (store/-transfer-resources! (resource-store! (:room payer)) command)))))

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
