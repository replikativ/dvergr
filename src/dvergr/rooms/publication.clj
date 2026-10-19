(ns dvergr.rooms.publication
  "Host-only durable receipts published atomically with Geschichte commits.
   A receipt is evidence, not authority: callers recheck room/effect permission
   before lookup or publication. No extra execution engine or result store."
  (:require [clojure.core.async :as async]
            [clojure.string :as str]
            [datahike.api :as d]
            [dvergr.rooms.file-tx :as file-tx]
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

(defn recover
  "Look up a receipt only after checking its complete host identity and current
   physical target. Callers must authorize this read first. Absence is not proof
   that an outstanding publication failed and never authorizes a replay."
  [db identity]
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

(defn prepare
  "Prepare publication of a host-validated file batch. The canonical writer
   must already have checked that no staged changes escape these touched paths.
   Freeze the complete path set so fresh receipt projection cannot omit entries."
  [conn options paths]
  (when-not (and (coll? paths) (<= 1 (count paths) 200)
                 (= (count paths) (count (set paths))) (every? canonical-path? paths))
    (throw (ex-info "Invalid publication paths" {:type ::invalid})))
  (assoc (repo/prepare-commit conn options) :publication/paths (vec (sort paths))))

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
    (let [expected (receipt plan (select-keys candidate (take 6 identity-keys))
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
        (let [completion (CompletableFuture.)
              interrupted? (volatile! false)]
          (try
            (try
              ;; Datahike owns the native operation. Its throwable-promise is a
              ;; ReadPort; a callback lets us join without wrapped interrupts or
              ;; another worker, and without ever submitting the operation twice.
              (async/take! (d/transact! conn [[:db.fn/call publish-tx plan receipt]])
                           #(.complete completion %))
              (let [report (loop []
                             (let [result (try [:done (.get completion)]
                                               (catch InterruptedException _
                                                 (vreset! interrupted? true)
                                                 [:interrupted]))]
                               (if (= :interrupted (first result))
                                 (recur)
                                 (second result))))]
                (when (instance? Throwable report) (throw report))
                (or (when-let [db (:db-after report)] (recover db receipt))
                    (throw (ex-info "Committed publication receipt is unavailable"
                                    {:type ::recovery-required}))))
              (catch Throwable cause
                (throw (ex-info "Publication outcome requires scoped recovery"
                                {:type ::recovery-required ::uncertain? true
                                 :operation (:publication/id receipt)} cause))))
            (finally
              (when @interrupted? (.interrupt (Thread/currentThread)))))))))
