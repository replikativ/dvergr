(ns dvergr.room.store
  "PRoomStore — pluggable durability for a Room.

   Every Room (`dvergr.discourse.Room`) optionally carries a
   `:store`. When non-nil, the Room spawns an internal listener at
   construction that mirrors every message-shaped event on its bus
   to the store via `-store-message!`. Consumers of Rooms never call
   the store directly — they post via `discourse/post!` and read via
   `room/messages` (which routes to the store when present, the bus
   log otherwise).

   The store also persists room *metadata* (title, parent, agent-ids,
   etc.) so the daemon can re-hydrate the registry on startup.

   Two impls ship:
     - dvergr.room.store.memory     — atom-backed; for tests and
                                       ephemeral rooms (e.g. forks)
     - dvergr.room.store.datahike   — wraps the existing
                                       `:chat/*` / `:message/*` /
                                       `:room/*` schema, same data
                                       as today's `dvergr.rooms`."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

(defprotocol PRoomStore
  "Pluggable durability surface for Rooms. Implementations decide
   where data lives (Datahike, in-memory atom, KV store, etc.); the
   shape is the same.

   `room-id` is the Room's keyword id (its `:id` field). Implementations
   that need a UUID for their own storage layer compute one from the
   keyword (e.g. an alias lookup) — callers always pass the id."

  (-store-room! [this room-id metadata]
    "Persist room metadata. `metadata` is a map with at minimum
     :slug; may also carry :title :parent-id :agent-ids :telegram-chat-id
     :type :meta. Implementations are idempotent (re-storing updates).")

  (-load-room [this id-or-slug]
    "Look up a room by id (keyword) or slug (string). Returns the
     metadata map (same shape as -store-room! input) or nil.")

  (-delete-room! [this room-id]
    "Remove the room and all its messages from the store. Idempotent.")

  (-list-rooms [this]
    "Return every room metadata map known to the store, ordered by
     :updated-at descending (or any stable order). Used at daemon
     startup to re-hydrate the registry.")

  (-store-message! [this room-id message]
    "Persist a single Message. `message` is a discourse.Message record
     (with :id :from :to :content :ts :in-reply-to :thread-root-id :metadata) OR a
     map with the same keys. :from and non-nil :to are canonical keyword actor
     ids; :in-reply-to is the stable immediate-parent UUID,
     :thread-root-id is the stable topical-root UUID, and :metadata uses dvergr's
     typed durable vocabulary (role/source, audience/mentions, attachment,
     provenance, notification, tool-use and reasoning fields). Implementations
     replay these envelope fields losslessly and must be first-write-wins
     idempotent on :id — re-stores are no-ops. Unknown durable metadata is an
     error: extend the typed schema rather than adding an opaque encoding.
     Returns :inserted when this call won the immutable identity, :duplicate
     when the id already existed, and :failed when durability was unavailable.")

  (-message-thread-root [this room-id message-id]
    "Return one message's stable topical-root UUID inside `room-id`, or nil.
     This bounded lookup lets the authoritative Room derive and validate a
     reply's thread without trusting a client-supplied ancestor.")

  (-list-messages [this room-id {:keys [limit since thread-root-id]}]
    "Return messages in chronological order. :limit caps result size
     (default impl-specific); :since is an instant — only messages
     after that ts are returned; :thread-root-id restricts the result to one
     topical projection before the limit is applied.")

  (-store-run! [this room-id run]
    "Create or update a durable Run projection owned by `room-id`. Run identity,
     kind, room, actor, trigger, parent, and start timestamps are immutable by
     contract; lifecycle updates change status and terminal fields.")

  (-load-run [this room-id run-id]
    "Return one durable Run by UUID when it belongs to `room-id`, else nil.")

  (-list-runs [this room-id {:keys [limit status actor]}]
    "Return recent Runs, newest first. Optional :status and :actor filters are
     applied before :limit."))

(defprotocol PResourceStore
  "Conserved resource authority cohabiting with durable Room control state.

   This deliberately exposes semantic wallet/transfer operations rather than a
   raw database connection. SCI programs receive still narrower closures over
   these operations, so they cannot mint authority or bypass the governor."

  (-open-resource-wallet! [this spec]
    "Open or idempotently re-open a wallet from {:id :owner :name}.")

  (-install-resource-unit! [this spec]
    "Install or idempotently re-install one conserved resource coordinate.")

  (-allocate-resource-wallet! [this wallet-spec transfer-spec]
    "Atomically open a child wallet and grant its initial conserved vector.")

  (-resource-balance [this account]
    "Return the current conserved vector for a wallet/account ref.")

  (-transfer-resources! [this spec]
    "Commit a governed :mint/:grant/:consume/:return transfer spec.")

  (-resource-receipt [this transfer-id]
    "Return the durable transfer receipt, or nil."))

(defprotocol PAttentionStore
  "Participant-specific attention projections over immutable Room messages.

   Attention is control/memory state, not speech, so it deliberately does not
   travel through PRoomStore's message log. Implementations colocate it with the
   Room authority and make first-write identity durable."

  (-store-attention! [this room-id fact]
    "Persist one validated attention fact. Returns the stored fact.")

  (-list-attention [this room-id opts]
    "List attention facts, optionally restricted by exact :id, :participant,
     and :limit. Exact identity lookup is never subject to the history limit."))

;; =============================================================================
;; Helpers
;; =============================================================================

(def durable-run-statuses
  "Lifecycle states stored in a Room. `:cancelling` is deliberately live-only:
   durable cancellation is acknowledged by the terminal `:cancelled` state."
  #{:running :waiting :completed :failed :cancelled})

(def terminal-run-statuses #{:completed :failed :cancelled})

(def durable-attention-keys
  #{:attention/id :attention/participant :attention/message-id
    :attention/decision-id :attention/run-id :attention/memory :attention/activation
    :attention/control :attention/at :attention/priority
    :attention/status :attention/reason :attention/metadata
    :attention/result-run-id :attention/created-at})

(defn attention-id
  "Deterministic identity for one decision or disposition over a message."
  [room-id participant message-id run-id phase]
  (java.util.UUID/nameUUIDFromBytes
   (.getBytes (pr-str [:dvergr/attention room-id participant message-id run-id phase])
              java.nio.charset.StandardCharsets/UTF_8)))

(def attention-semantic-keys
  [:attention/participant :attention/message-id :attention/run-id
   :attention/memory :attention/activation :attention/control :attention/at
   :attention/priority :attention/reason :attention/metadata])

(defn validate-attention-disposition!
  "Require an applied fact to match one existing ready decision exactly."
  [decision applied]
  (when-not decision
    (throw (ex-info "Applied attention references a missing decision"
                    {:type :room-store/orphan-attention-disposition
                     :applied applied})))
  (when-not (= :ready (:attention/status decision))
    (throw (ex-info "Only a ready attention decision can be applied"
                    {:type :room-store/invalid-attention-disposition
                     :decision decision :applied applied})))
  (when-not (= (:attention/id decision) (:attention/decision-id applied))
    (throw (ex-info "Applied attention references the wrong decision"
                    {:type :room-store/invalid-attention-disposition
                     :decision decision :applied applied})))
  (when-not (= (select-keys decision attention-semantic-keys)
               (select-keys applied attention-semantic-keys))
    (throw (ex-info "Applied attention axes differ from its decision"
                    {:type :room-store/attention-disposition-mismatch
                     :decision decision :applied applied})))
  (if (= :enqueue (:attention/activation applied))
    (when-not (uuid? (:attention/result-run-id applied))
      (throw (ex-info "Applied enqueue attention requires a successor Run"
                      {:type :room-store/missing-attention-result-run
                       :decision decision :applied applied})))
    (when (:attention/result-run-id applied)
      (throw (ex-info "Only applied enqueue attention may reference a successor Run"
                      {:type :room-store/unexpected-attention-result-run
                       :decision decision :applied applied}))))
  applied)

(defn validate-attention-result-run!
  "Require enqueue acknowledgement to name the exact successor execution."
  [applied result-run]
  (when (= :enqueue (:attention/activation applied))
    (when-not result-run
      (throw (ex-info "Applied enqueue attention references a missing successor Run"
                      {:type :room-store/missing-attention-result-run
                       :applied applied})))
    (when-not (and (= (:attention/participant applied) (:run/actor result-run))
                   (= (:attention/message-id applied) (:run/trigger result-run)))
      (throw (ex-info "Applied enqueue attention references an unrelated successor Run"
                      {:type :room-store/invalid-attention-result-run
                       :applied applied :result-run result-run}))))
  applied)

(defn validate-attention!
  "Validate one durable participant attention projection and return it."
  [fact]
  (when-let [unknown (seq (remove durable-attention-keys (keys fact)))]
    (throw (ex-info "Unknown durable attention keys"
                    {:type :room-store/unknown-attention-keys
                     :unknown (set unknown)})))
  (doseq [k [:attention/id :attention/message-id]
          :let [v (get fact k)]]
    (when-not (uuid? v)
      (throw (ex-info (str k " must be a UUID")
                      {:type :room-store/invalid-attention :key k :fact fact}))))
  (when-not (keyword? (:attention/participant fact))
    (throw (ex-info ":attention/participant must be a keyword"
                    {:type :room-store/invalid-attention :fact fact})))
  (when (and (:attention/run-id fact) (not (uuid? (:attention/run-id fact))))
    (throw (ex-info ":attention/run-id must be a UUID"
                    {:type :room-store/invalid-attention :fact fact})))
  (when (and (:attention/result-run-id fact)
             (not (uuid? (:attention/result-run-id fact))))
    (throw (ex-info ":attention/result-run-id must be a UUID"
                    {:type :room-store/invalid-attention :fact fact})))
  (when (and (:attention/decision-id fact)
             (not (uuid? (:attention/decision-id fact))))
    (throw (ex-info ":attention/decision-id must be a UUID"
                    {:type :room-store/invalid-attention :fact fact})))
  (when (and (= :applied (:attention/status fact))
             (not (uuid? (:attention/decision-id fact))))
    (throw (ex-info "Applied attention requires :attention/decision-id"
                    {:type :room-store/invalid-attention :fact fact})))
  (doseq [[k allowed] [[:attention/memory #{:ignore :remember :include}]
                       [:attention/activation #{:none :enqueue :wake}]
                       [:attention/control #{:continue :integrate :restart :suspend :cancel}]
                       [:attention/at #{:now :next-safe-boundary :before-model :token
                                        :before-tool :after-tool :after-model :quiescent}]
                       [:attention/status #{:ready :deferred :invalid :applied
                                            :baseline-complete}]]
          :let [v (get fact k)]
          :when (and (some? v) (not (contains? allowed v)))]
    (throw (ex-info (str "Invalid durable " k)
                    {:type :room-store/invalid-attention :key k :value v})))
  (when (and (some? (:attention/priority fact))
             (not (number? (:attention/priority fact))))
    (throw (ex-info ":attention/priority must be numeric"
                    {:type :room-store/invalid-attention :fact fact})))
  (when (and (:attention/reason fact)
             (not (keyword? (:attention/reason fact))))
    (throw (ex-info ":attention/reason must be a keyword"
                    {:type :room-store/invalid-attention :fact fact})))
  (when (and (:attention/metadata fact)
             (not (map? (:attention/metadata fact))))
    (throw (ex-info ":attention/metadata must be a map"
                    {:type :room-store/invalid-attention :fact fact})))
  (when-let [metadata (:attention/metadata fact)]
    (let [round-trip
          (try
            (edn/read-string (pr-str metadata))
            (catch Throwable error
              (throw (ex-info ":attention/metadata must be round-trippable EDN"
                              {:type :room-store/invalid-attention-metadata
                               :metadata metadata}
                              error))))]
      (when-not (= metadata round-trip)
        (throw (ex-info ":attention/metadata must round-trip without type loss"
                        {:type :room-store/invalid-attention-metadata
                         :metadata metadata :round-trip round-trip})))))
  (when-not (instance? java.util.Date (:attention/created-at fact))
    (throw (ex-info ":attention/created-at must be an instant"
                    {:type :room-store/invalid-attention :fact fact})))
  (cond-> fact
    (some? (:attention/priority fact))
    (update :attention/priority double)))

(defn unapplied-attention
  "Return ready decisions that have no append-only applied disposition.

   This is recovery/audit input, not an instruction to replay blindly: the
   owning interpreter must reconcile the durable Run and effect boundary before
   deciding whether retry is safe."
  [facts]
  (let [applied (into #{}
                      (keep #(when (= :applied (:attention/status %))
                               (:attention/decision-id %)))
                      facts)]
    (->> facts
         (filter #(= :ready (:attention/status %)))
         (remove #(contains? applied (:attention/id %)))
         vec)))

(def durable-run-keys
  #{:run/id :run/kind :run/room :run/actor :run/trigger :run/parent
    :run/status :run/created-at :run/started-at :run/updated-at :run/ended-at
    :run/reason :run/error
    :run/world :run/isolation :run/settlement-policy
    :run/settlement-status :run/settlement-reason
    :run/roster :run/agent-version :run/program-kind
    :run/interpreter-version :run/agent-def-hash :run/chat-id})

(def immutable-run-keys
  [:run/id :run/kind :run/room :run/actor :run/trigger :run/parent
   :run/created-at :run/started-at
   :run/world :run/isolation :run/settlement-policy
   :run/roster :run/agent-version :run/program-kind
   :run/interpreter-version :run/agent-def-hash :run/chat-id])

(defn validate-run!
  "Validate the minimal durable Run contract and return `run`."
  [run]
  (when-let [unknown (seq (remove durable-run-keys (keys run)))]
    (throw (ex-info "Unknown durable Run keys"
                    {:type :room-store/unknown-run-keys
                     :unknown (set unknown)
                     :allowed durable-run-keys})))
  (doseq [k [:run/id :run/trigger]]
    (when-not (uuid? (get run k))
      (throw (ex-info (str k " must be a UUID")
                      {:type :room-store/invalid-run :key k :run run}))))
  (when-not (keyword? (:run/room run))
    (throw (ex-info ":run/room must be a keyword"
                    {:type :room-store/invalid-run :key :run/room :run run})))
  (when-not (keyword? (:run/actor run))
    (throw (ex-info ":run/actor must be a keyword"
                    {:type :room-store/invalid-run :key :run/actor :run run})))
  (when-not (keyword? (:run/kind run))
    (throw (ex-info ":run/kind must be a keyword"
                    {:type :room-store/invalid-run :key :run/kind :run run})))
  (when-not (contains? durable-run-statuses (:run/status run))
    (throw (ex-info "Invalid durable run status"
                    {:type :room-store/invalid-run-status
                     :status (:run/status run) :run run})))
  (when (and (:run/parent run) (not (uuid? (:run/parent run))))
    (throw (ex-info ":run/parent must be a UUID"
                    {:type :room-store/invalid-run :key :run/parent :run run})))
  (when (and (:run/roster run) (not (keyword? (:run/roster run))))
    (throw (ex-info ":run/roster must be a keyword"
                    {:type :room-store/invalid-run :key :run/roster :run run})))
  (doseq [k [:run/agent-version :run/interpreter-version]
          :let [v (get run k)]
          :when (and (some? v) (not (and (integer? v) (pos? v))))]
    (throw (ex-info (str k " must be a positive integer")
                    {:type :room-store/invalid-run :key k :run run})))
  (when (and (:run/program-kind run) (not (keyword? (:run/program-kind run))))
    (throw (ex-info ":run/program-kind must be a keyword"
                    {:type :room-store/invalid-run :key :run/program-kind :run run})))
  (when (and (:run/agent-def-hash run) (not (uuid? (:run/agent-def-hash run))))
    (throw (ex-info ":run/agent-def-hash must be a UUID"
                    {:type :room-store/invalid-run :key :run/agent-def-hash :run run})))
  (when (and (:run/chat-id run) (not (uuid? (:run/chat-id run))))
    (throw (ex-info ":run/chat-id must be a UUID"
                    {:type :room-store/invalid-run :key :run/chat-id :run run})))
  (doseq [k [:run/world :run/isolation :run/settlement-policy
             :run/settlement-status :run/settlement-reason]
          :let [v (get run k)]
          :when (and (some? v) (not (keyword? v)))]
    (throw (ex-info (str k " must be a keyword")
                    {:type :room-store/invalid-run :key k :run run})))
  (doseq [k [:run/created-at :run/started-at :run/updated-at]
          :when (not (instance? java.util.Date (get run k)))]
    (throw (ex-info (str k " must be an instant")
                    {:type :room-store/invalid-run :key k :run run})))
  (when (and (contains? terminal-run-statuses (:run/status run))
             (not (instance? java.util.Date (:run/ended-at run))))
    (throw (ex-info "A terminal run requires :run/ended-at"
                    {:type :room-store/invalid-run :key :run/ended-at :run run})))
  run)

(defn validate-run-update!
  "Reject an update that changes the causal identity of an existing Run."
  [existing run]
  (when (and existing
             (not= (select-keys existing immutable-run-keys)
                   (select-keys run immutable-run-keys)))
    (throw (ex-info "Durable run identity fields are immutable"
                    {:type :room-store/immutable-run-update
                     :run-id (:run/id run)
                     :existing (select-keys existing immutable-run-keys)
                     :update (select-keys run immutable-run-keys)})))
  run)

(def durable-message-metadata-keys
  "The top-level metadata keys that every PRoomStore implementation accepts.
   New durable extensions must add typed storage in persistent implementations
   before being added here."
  #{:role :source-user :source-username :source-user-id
    :audience :mentions :attachment :provenance
    :object
    :tool-uses :activities :reasoning :kind :from :source :schedule-id
    :notification/type :notification/agent :notification/task
    :notification/elapsed :run-id})

(def ^:private attachment-metadata-keys #{:blob-id :node-id :mime :name :size})
(def ^:private provenance-metadata-keys #{:mode :source})
(def ^:private object-metadata-keys #{:kind :id})
(def ^:private activity-metadata-keys
  #{:activity/id :activity/run-id :activity/kind :activity/verb
    :activity/status :activity/tool-name :activity/tool-use-id
    :activity/outcome :activity/critical? :activity/at})

(defn- reject-unknown-metadata! [kind allowed value]
  (let [unknown (seq (remove allowed (keys (or value {}))))]
    (when unknown
      (throw (ex-info (str "Unknown durable message " kind " keys")
                      {:type :room-store/unknown-message-metadata
                       :kind kind
                       :unknown (set unknown)
                       :allowed allowed})))))

(defn- validate-activity! [activity]
  (when-not (map? activity)
    (throw (ex-info "Message activity must be a map"
                    {:type :room-store/invalid-message-metadata
                     :activity activity})))
  (reject-unknown-metadata! :activity activity-metadata-keys activity)
  (doseq [[key pred label] [[:activity/id uuid? "UUID"]
                            [:activity/kind keyword? "keyword"]
                            [:activity/verb keyword? "keyword"]
                            [:activity/run-id uuid? "UUID"]
                            [:activity/status keyword? "keyword"]
                            [:activity/tool-name string? "string"]
                            [:activity/tool-use-id string? "string"]
                            [:activity/outcome string? "string"]
                            [:activity/critical? boolean? "boolean"]
                            [:activity/at #(instance? java.util.Date %) "instant"]]]
    (when (and (contains? activity key) (not (pred (get activity key))))
      (throw (ex-info (str "Message " key " must be a " label)
                      {:type :room-store/invalid-message-metadata
                       :key key :activity activity}))))
  (when-not (and (uuid? (:activity/id activity))
                 (keyword? (:activity/kind activity))
                 (keyword? (:activity/verb activity)))
    (throw (ex-info "Message activity requires UUID :activity/id and keyword kind/verb"
                    {:type :room-store/invalid-message-metadata
                     :activity activity})))
  activity)

(defn validate-message-metadata!
  "Validate the typed durable message metadata vocabulary and return `metadata`.
   Kept at the protocol boundary so ephemeral and persistent stores reject the
   same accidental, unmodelled extensions."
  [metadata]
  (when metadata
    (when-not (map? metadata)
      (throw (ex-info "Durable message metadata must be a map"
                      {:type :room-store/invalid-message-metadata
                       :value metadata})))
    (reject-unknown-metadata! :metadata durable-message-metadata-keys metadata)
    (when-let [attachment (:attachment metadata)]
      (when-not (map? attachment)
        (throw (ex-info "Message attachment metadata must be a map"
                        {:type :room-store/invalid-message-metadata
                         :attachment attachment})))
      (reject-unknown-metadata! :attachment attachment-metadata-keys attachment))
    (when-let [provenance (:provenance metadata)]
      (when-not (map? provenance)
        (throw (ex-info "Message provenance metadata must be a map"
                        {:type :room-store/invalid-message-metadata
                         :provenance provenance})))
      (reject-unknown-metadata! :provenance provenance-metadata-keys provenance))
    (when (contains? metadata :activities)
      (when-not (sequential? (:activities metadata))
        (throw (ex-info "Message :activities must be sequential"
                        {:type :room-store/invalid-message-metadata
                         :activities (:activities metadata)})))
      (run! validate-activity! (:activities metadata)))
    (when-let [message-run-id (:run-id metadata)]
      (doseq [activity (:activities metadata)]
        (when (and (:activity/run-id activity)
                   (not= message-run-id (:activity/run-id activity)))
          (throw (ex-info "Message activity Run must match its enclosing message"
                          {:type :room-store/invalid-message-metadata
                           :message-run-id message-run-id
                           :activity activity})))))
    (when (contains? metadata :object)
      (let [object (:object metadata)]
        (when-not (map? object)
          (throw (ex-info "Message object reference must be a map"
                          {:type :room-store/invalid-message-metadata
                           :object object})))
        (reject-unknown-metadata! :object object-metadata-keys object)
        (when-not (keyword? (:kind object))
          (throw (ex-info "Message object :kind must be a keyword"
                          {:type :room-store/invalid-message-metadata
                           :object object})))
        (when-not (uuid? (:id object))
          (throw (ex-info "Message object :id must be a UUID"
                          {:type :room-store/invalid-message-metadata
                           :object object}))))))
  (when (and (:run-id metadata) (not (uuid? (:run-id metadata))))
    (throw (ex-info "Message :run-id metadata must be a UUID"
                    {:type :room-store/invalid-message-metadata
                     :run-id (:run-id metadata)})))
  metadata)

(defn normalize-message-thread
  "Return `msg` with a typed durable `:thread-root-id`.

   Top-level messages self-root. Legacy replies without an explicit root treat
   their immediate parent as the root; live nested replies preserve the actual
   root through `dvergr.discourse/reply`, while out-of-order importers should
   supply it explicitly. A top-level message claiming another root is invalid:
   joining a thread requires an immediate causal parent."
  [msg]
  (let [id     (:id msg)
        parent (:in-reply-to msg)
        root   (or (:thread-root-id msg) parent id)]
    (when-not (uuid? root)
      (throw (ex-info "Durable message thread root must be a UUID"
                      {:type :room-store/invalid-thread-root
                       :message-id id :thread-root-id root})))
    (when (and (nil? parent) (not= id root))
      (throw (ex-info "A top-level message must be its own thread root"
                      {:type :room-store/invalid-thread-root
                       :message-id id :thread-root-id root})))
    (assoc msg :thread-root-id root)))

(defn message-shape?
  "True if `msg` looks like a Message (has the required keys). Used by
   the Room's internal persistence listener to filter bus events:
   user/agent message envelopes get persisted, ticks and source events
   do not."
  [msg]
  (and (map? msg)
       (contains? msg :id)
       (contains? msg :content)
       ;; The bus tags Messages with :type :user/message; filter to
       ;; message-shaped events only.
       (or (= :user/message (:type msg))
           (= :agent/reply  (:type msg))
           (and (not (contains? msg :type))
                (instance? java.util.UUID (:id msg))))))

(defn infer-role
  "Global conversation role for a message — the single source of truth shared
   by the persisted store and the live `:room-messages` signal so both folds
   of the bus agree. An explicit `:role` (on the message or its `:metadata`)
   wins; otherwise a keyword `:from` (an agent/system actor id) is `:assistant`
   and a user/external post is `:user`. User input carries an explicit
   `:role :user`, so it is never misread as an agent reply."
  [m]
  (or (:role m)
      (:role (:metadata m))
      (cond
        (= (:from m) :_system) :assistant
        (keyword? (:from m))   :assistant
        :else                  :user)))

(defn slug->room-id
  "Canonical mapping slug → Room id (a keyword). The id is the slug
   with `/` re-encoded to `_fork_` so forks of `boardroom` slugged as
   `boardroom/fork-abc12345` get a valid keyword id."
  [slug]
  (when slug
    (keyword (str/replace slug "/" "_fork_"))))

(defn room-id->slug
  "Inverse of slug->room-id."
  [room-id]
  (when room-id
    (str/replace (name room-id) "_fork_" "/")))
