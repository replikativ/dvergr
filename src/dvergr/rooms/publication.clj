(ns dvergr.rooms.publication
  "Host-only durable receipts published atomically with Geschichte commits.
   A receipt is evidence, not authority: callers recheck room/effect permission
   before lookup or publication. No extra execution engine or result store."
  (:require [clojure.core.async :as async]
            [clojure.string :as str]
            [datahike.api :as d]
            [dvergr.rooms.file-tx :as file-tx]
            [dvergr.rooms.subtree :as subtree]
            [geschichte.repo :as repo]
            [geschichte.query :as query])
  (:import [java.util.concurrent CompletableFuture]))

(def schema
  (vec
   (concat
    (for [[ident type] [[:publication/id :db.type/uuid]
                        [:publication/intent :db.type/uuid]
                        [:publication/actor :db.type/uuid]
                        [:publication/proposal :db.type/uuid]
                        [:publication/manifest :db.type/uuid]
                        [:publication/attempt :db.type/uuid]
                        [:publication/repository :db.type/uuid]
                        [:publication/store :db.type/uuid]
                        [:publication/backend :db.type/keyword]
                        [:publication/branch :db.type/keyword]
                        [:publication/ref :db.type/string]
                        [:publication/commit :db.type/uuid]
                        [:publication/head :db.type/uuid]
                        [:publication/noop? :db.type/boolean]
                        [:publication/created-at :db.type/instant]
                        [:publication.entry/path :db.type/string]
                        [:publication.entry/state :db.type/keyword]
                        [:publication.entry/size :db.type/long]
                        [:publication.entry/mode :db.type/long]
                        [:publication.entry/content :db.type/ref]]]
      (cond-> {:db/ident ident :db/valueType type :db/cardinality :db.cardinality/one}
        (= ident :publication/id) (assoc :db/unique :db.unique/identity)))
    [{:db/ident :publication/entries :db/valueType :db.type/ref
      :db/cardinality :db.cardinality/many :db/isComponent true}])))

(defn install!
  "Install receipt schema before writing/staging/preparing a publication.
   Call under the same repository write lease used for the batch."
  [conn]
  (let [known (set (d/q '[:find [?a ...] :where [_ :db/ident ?a]] @conn))
        missing (filterv #(not (contains? known (:db/ident %))) schema)]
    (when (seq missing) (d/transact conn missing)))
  nil)

(def ^:private identity-keys
  [:publication/id :publication/intent :publication/actor :publication/proposal
   :publication/manifest :publication/attempt :publication/repository
   :publication/store :publication/backend :publication/branch :publication/ref])

(defn lookup
  "Read one canonical receipt from an immutable DB. No content bytes are loaded."
  [db operation-id]
  (when-let [eid (d/q '[:find ?e . :in $ ?id :where [?e :publication/id ?id]] db operation-id)]
    (d/pull db '[* {:publication/entries
                    [* {:publication.entry/content [:geschichte.content/id]}]}] eid)))

(defn- checked-existing [db receipt]
  ;; An inherited receipt in another physical workspace is not recovery of
  ;; this operation's target. Unlike checkpoint validation, this check also
  ;; applies to duplicates, and survives unrelated edits and logical checkouts.
  (when-not (= (select-keys receipt [:publication/repository :publication/store :publication/backend :publication/branch])
               {:publication/repository (:geschichte.repo/id (query/repository db))
                :publication/store (get-in db [:config :store :id])
                :publication/backend (get-in db [:config :store :backend])
                :publication/branch (get-in db [:config :branch])})
    (throw (ex-info "Publication belongs to a different physical target" {:type ::wrong-target})))
  (when-let [existing (lookup db (:publication/id receipt))]
    (when-not (= (select-keys existing identity-keys) (select-keys receipt identity-keys))
      (throw (ex-info "Publication operation already belongs to a different intent"
                      {:type ::intent-conflict :operation (:publication/id receipt)})))
    existing))

(defn validate-identity!
  "Pure host validation of a complete recovery identity; returns it unchanged.
   This neither authorizes a read/write nor installs receipt schema."
  [identity]
  (when-not (and (map? identity)
                 (every? #(uuid? (get identity %))
                         [:publication/id :publication/intent :publication/actor
                          :publication/manifest :publication/repository :publication/store])
                 (every? #(or (nil? (get identity %)) (uuid? (get identity %)))
                         [:publication/proposal :publication/attempt])
                 (keyword? (:publication/backend identity))
                 (keyword? (:publication/branch identity))
                 (string? (:publication/ref identity)))
    (throw (ex-info "Invalid publication recovery identity" {:type ::invalid})))
  identity)

(defn recover
  "Look up a receipt only after checking its complete host identity and current
   physical target. Callers must authorize this read first. Absence is not proof
   that an outstanding publication failed and never authorizes a replay."
  [db identity]
  (validate-identity! identity)
  (checked-existing db identity))

(defn authorize!
  "Host publication callbacks are synchronous and must explicitly return true.
   False, nil, Throwable values and async handles do not confer authority."
  [options]
  (when-not (true? ((:authorize! options)))
    (throw (ex-info "Host refused file publication" {:type ::not-authorized})))
  true)

(defn- canonical-path? [path]
  (and (string? path) (.startsWith ^String path "/")
       (< 1 (count path) 513)
       (not-any? #(str/includes? path %) ["\\" "\u0000" "//"])
       (not-any? #{"." ".." ".git"} (str/split path #"/"))))

(defn- prepare* [conn options paths validate-basis!]
  (when-not (and (coll? paths) (<= 1 (count paths) 200)
                 (= (count paths) (count (set paths))) (every? canonical-path? paths)
                 (or (nil? validate-basis!) (fn? validate-basis!)))
    (throw (ex-info "Invalid publication paths or host validator" {:type ::invalid})))
  (let [db @conn
        plan (assoc (repo/prepare-commit (atom db) options) :publication/paths (vec (sort paths)))]
    (when (and validate-basis! (not (true? (validate-basis! db))))
      (throw (ex-info "Host refused prepared publication basis" {:type ::invalid-basis})))
    plan))

(defn prepare
  "Prepare publication of a host-validated file batch. The canonical writer
   must already have checked that no staged changes escape these touched paths.
   Freeze the complete path set so fresh receipt projection cannot omit entries.

   Optional trusted HOST validate-basis! receives the SAME immutable DB used by
   prepare-commit and must synchronously return exactly true. It runs outside
   the serialized writer, before the applying journal. The final transaction's
   prepared checkpoint fences every intervening write. No predicate/DB/handle
   is persisted or supplied by RPC/SCI. A callback is not itself authorization,
   a preservation policy or proof an earlier reviewed basis remains unchanged."
  ([conn options paths] (prepare* conn options paths nil))
  ([conn options paths validate-basis!]
   (when-not (fn? validate-basis!)
     (throw (ex-info "An explicit publication validator must be a host function" {:type ::invalid})))
   (prepare* conn options paths validate-basis!)))

(defn receipt
  "Build a typed host receipt for exactly the touched regular-file entries.
   `identity` carries id/intent/actor/manifest and optional proposal/attempt UUIDs
   under :publication/*. The caller binds intent to the frozen manifest/policy.
   `entries` maps absolute paths to staged {:content :size :mode}, nil if removed."
  [plan identity entries]
  (when-not (and (map? identity)
                 (every? #(uuid? (get identity %))
                         [:publication/id :publication/intent :publication/actor :publication/manifest])
                 (every? uuid? (vals identity))
                 (every? (set (take 6 identity-keys)) (keys identity))
                 (<= 1 (count (:publication/paths plan)) 200)
                 (map? entries) (= (set (keys entries)) (set (:publication/paths plan))))
    (throw (ex-info "Invalid host publication identity" {:type ::invalid})))
  (let [commit-id (get-in plan [:commit :geschichte.commit/id])]
    (cond-> (merge identity
                   {:publication/repository (:repository plan)
                    :publication/store (get-in plan [:workspace :store :id])
                    :publication/backend (get-in plan [:workspace :store :backend])
                    :publication/branch (get-in plan [:workspace :branch])
                    :publication/ref (:ref plan)
                    :publication/noop? (:noop? plan)
                    :publication/created-at (java.util.Date.)
                    :publication/entries
                    (mapv (fn [[path entry]]
                            (when-not (and (canonical-path? path)
                                           (= entry (get (:index plan) (subs path 1)))
                                           (or (nil? entry)
                                               (and (uuid? (:content entry))
                                                    (integer? (:size entry)) (<= 0 (:size entry))
                                                    (#{33188 33261} (:mode entry)))))
                              (throw (ex-info "Invalid publication file entry" {:type ::invalid :path path})))
                            (cond-> {:publication.entry/path path
                                     :publication.entry/state (if entry :present :deleted)}
                              entry (assoc :publication.entry/content [:geschichte.content/id (:content entry)]
                                           :publication.entry/size (:size entry)
                                           :publication.entry/mode (:mode entry))))
                          (sort-by key entries))})
      commit-id (assoc :publication/commit commit-id)
      (or commit-id (:head plan)) (assoc :publication/head (or commit-id (:head plan))))))

(defn publish-tx
  "Transaction function: deduplicate BEFORE validating the prepared basis.
   A lost acknowledgement is recoverable after later edits or GC. A fresh
   operation must use a still-current prepared commit; receipt and ref move
   are one transaction. A host may preflight this pure function, but MUST also
   submit it as a transaction function for atomic validation against the writer."
  [db plan candidate]
  (if (checked-existing db candidate)
    []
    (let [_ (when-let [condition (:required-subtree plan)] (subtree/assert-after! db condition))
          expected (receipt plan (select-keys candidate (take 6 identity-keys))
                            (into {} (map (fn [path] [path (get (:index plan) (subs path 1))]))
                                  (:publication/paths plan)))]
      (when-not (and (instance? java.util.Date (:publication/created-at candidate))
                     (= (dissoc candidate :publication/created-at)
                        (dissoc expected :publication/created-at)))
        (throw (ex-info "Publication receipt does not describe its prepared commit"
                        {:type ::invalid-receipt})))
      (into (conj (repo/prepared-commit-tx db plan) candidate)
            (file-tx/remove-empty-dirs-tx
             db (distinct (mapcat (comp file-tx/ancestors-of :publication.entry/path)
                                  (filter #(= :deleted (:publication.entry/state %))
                                          (:publication/entries candidate)))))))))

(defn uncertain?
  "True when publication was dispatched but its outcome needs scoped recovery."
  [error]
  (true? (::uncertain? (ex-data error))))

(defn- transact-joined!
  "ONE owned native submission. Internal consume runs before interrupt restore;
   no caller-supplied hook, extra executor or timeout-as-physical-completion."
  [conn tx-data consume]
  (let [completion (CompletableFuture.) interrupted? (volatile! false)]
    (try
      (async/take! (d/transact! conn tx-data) #(.complete completion %))
      (let [report (loop []
                     (let [result (try [:done (.get completion)]
                                       (catch InterruptedException _
                                         (vreset! interrupted? true) [:interrupted]))]
                       (if (= :interrupted (first result)) (recur) (second result))))]
        (when (instance? Throwable report) (throw report))
        (consume report))
      (finally (when @interrupted? (.interrupt (Thread/currentThread)))))))

(defn prepared-write-tx
  "Fixed HOST condition-enabled ordinary publication. BOTH exact prefixes and
   the complete prepared basis are checked in the SAME writer, also for noop."
  [db plan]
  (subtree/assert-after! db (:required-subtree plan))
  (when-not (and (vector? (:subtree/removed plan)) (<= (count (:subtree/removed plan)) subtree/max-entries)
                 (every? #(and (canonical-path? %)
                               (str/starts-with? % (:prefix (:required-subtree plan)))
                               (nil? (get (:index plan) (subs % 1)))) (:subtree/removed plan)))
    (throw (ex-info "Invalid conditional cleanup paths" {:type ::invalid})))
  (into (repo/prepared-commit-tx db plan)
        (file-tx/remove-empty-dirs-tx
         db (distinct (mapcat file-tx/ancestors-of
                              (:subtree/removed plan))))))

(defn commit-prepared!
  "HOST fixed conditional commit/noop; no fake receipt journal. Only the
   ORIGINAL joined report acknowledges success. Dispatched uncertainty NEVER
   permits restore/replan/retry. Returned tree is the validated published index."
  [conn plan]
  (prepared-write-tx @conn plan)
  (try
    (transact-joined!
     conn [[:db.fn/call prepared-write-tx plan]]
     (fn [{:keys [db-before db-after]}]
       ;; Datahike .1910 transaction completion increments max-tx exactly once,
       ;; even when the fixed function returns no commit/ref datoms. This fences
       ;; a stale before snapshot substituted for a noop's original after ACK.
       (when-not (and db-before db-after (integer? (:max-tx db-before))
                      (= (inc (:max-tx db-before)) (:max-tx db-after)))
         (throw (ex-info "Prepared write acknowledgement unavailable" {:type ::recovery-required})))
       (prepared-write-tx db-before plan)
       (subtree/assert-after! db-after (:required-subtree plan))
       (let [commit (get-in plan [:commit :geschichte.commit/id])
             expected-head (or commit (:head plan))]
         (when-not (and (= (:repository plan) (:geschichte.repo/id (query/repository db-after)))
                        (= (:ref plan) (query/current-ref db-after))
                        (= expected-head (get (query/refs db-after) (:ref plan)))
                        (= (:worktree plan) (query/worktree db-after))
                        (= (:index plan) (repo/index-tree (atom db-after)))
                        (or (nil? commit)
                            (= (:checkpoint plan) (:geschichte.commit/snapshot (query/commit db-after commit)))))
           (throw (ex-info "Prepared write acknowledgement does not match its commit"
                           {:type ::recovery-required})))
         {:commit (some-> commit str) :tree (:index plan)})))
    (catch Throwable cause
      (throw (ex-info "Prepared write outcome is uncertain; do not replay"
                      {:type ::recovery-required ::uncertain? true} cause)))))

(defn stage-subtree!
  "Fixed condition-only stage CAS. A lexical native witness is set ONLY from
   this submission's validated original before/after report, never readback."
  [conn basis after paths witness]
  (subtree/stage-tx @conn basis after paths)
  (try
    (transact-joined!
     conn [[:db.fn/call subtree/stage-tx basis after paths]]
     (fn [{:keys [db-before db-after]}]
       (when-not (and db-before db-after (integer? (:max-tx db-before))
                      (= (inc (:max-tx db-before)) (:max-tx db-after)))
         (throw (ex-info "Conditional stage acknowledgement unavailable" {:type ::recovery-required})))
       (subtree/stage-tx db-before basis after paths)
       (subtree/assert-after! db-after after)
       (vreset! witness true)
       true))
    (when (.isInterrupted (Thread/currentThread))
      (throw (InterruptedException. "Interrupted after conditional stage acknowledgement")))
    true
    (catch Throwable cause
      (throw (ex-info "Conditional staging is uncertain; do not undo or replay"
                      {:type ::recovery-required ::uncertain? true} cause)))))

(defn undo-subtree-tx [db basis after touched stage-owned? paths created]
  (let [{:keys [tx]} (subtree/undo-data db basis after touched stage-owned? paths)]
    ;; Transaction functions receive the writer's transient DB. Return the
    ;; fixed cleanup AFTER undo datoms, so it observes their actual applied
    ;; state in this same transaction; never nest d/with on a transient DB.
    (cond-> tx (seq created) (conj [:db.fn/call file-tx/remove-empty-dirs-tx created]))))

(defn undo-subtree!
  "Fixed value-CAS cleanup, joined before return/interrupt restoration. Foreign
   FULL worktree/index values are preserved. No stage ACK means no index undo."
  [conn basis after touched stage-owned? paths created]
  (try
    (transact-joined!
     conn [[:db.fn/call undo-subtree-tx basis after touched stage-owned? paths created]]
     (fn [{:keys [db-before db-after]}]
       (when-not (and db-before db-after (integer? (:max-tx db-before))
                      (= (inc (:max-tx db-before)) (:max-tx db-after)))
         (throw (ex-info "Conditional cleanup acknowledgement unavailable" {:type ::recovery-required})))
       (let [original (subtree/undo-data db-before basis after touched stage-owned? paths)
             tx (undo-subtree-tx db-before basis after touched stage-owned? paths created)
             projected (if (seq tx) (:db-after (d/with db-before tx)) db-before)
             dirs #(mapv (fn [path] (boolean (:db/id (d/entity % [:geschichte.work-dir/path (subs path 1)])))) created)]
         (when-not (and (= (subtree/work-rows projected touched) (subtree/work-rows db-after touched))
                        (= (subtree/stage-rows projected paths) (subtree/stage-rows db-after paths))
                        (= (dirs projected) (dirs db-after)))
           (throw (ex-info "Conditional cleanup acknowledgement does not match" {:type ::recovery-required})))
         (:conflicts original))))
    (catch Throwable cause
      (throw (ex-info "Conditional cleanup is uncertain; do not replay"
                      {:type ::recovery-required ::uncertain? true} cause)))))

(defn publish!
  "Publish under the host's canonical repository lease. Return the WINNING
   receipt from db-after, not the caller's possibly losing candidate commit.
   Once dispatched, NEVER undo staged files or automatically replay/replan on
   an error. Recover the exact operation or retain its unresolved journal.
   Interrupted callers join the one native transaction, then retain their
   interrupt flag; interruption must not release ownership while it can write."
  [conn plan receipt]
  (or (recover @conn receipt)
      (do
        ;; Deterministic rejection here is pre-dispatch and safe for the host to
        ;; roll back. This is not a substitute for writer-side validation.
        (publish-tx @conn plan receipt)
        (try
          (transact-joined!
           conn [[:db.fn/call publish-tx plan receipt]]
           (fn [report]
             (or (when-let [db (:db-after report)] (recover db receipt))
                 (throw (ex-info "Committed publication receipt is unavailable"
                                 {:type ::recovery-required})))))
          (catch Throwable cause
            (throw (ex-info "Publication outcome requires scoped recovery"
                            {:type ::recovery-required ::uncertain? true
                             :operation (:publication/id receipt)} cause)))))))
