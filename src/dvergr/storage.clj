(ns dvergr.storage
  "Datahike storage for REPL history, conversations, and code metadata."
  (:require [datahike.api :as d]
            [clojure.java.io :as io]
            [rewrite-clj.zip :as z])
  (:import [java.util UUID]
           [java.nio.charset StandardCharsets]
           [java.security MessageDigest]))

;; ---------------------------------------------------------------------------
;; Schema
;; ---------------------------------------------------------------------------

(def schema
  "Datahike schema for session storage.

   Entities:
   - :eval/* - REPL evaluation history
   - :turn/* - Agent conversation turns
   - :message/* - Individual messages within turns
   - :tool-call/* - Tool calls made by agent
   - :tool-result/* - Results from tool execution
   - :session/* - Session metadata"

  [;; Session metadata
   {:db/ident :session/id
    :db/valueType :db.type/string
    :db/unique :db.unique/identity
    :db/cardinality :db.cardinality/one
    :db/doc "Unique session identifier"}

   {:db/ident :session/created-at
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident :session/title
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident :session/provider
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident :session/model
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}

   ;; REPL evaluation history
   {:db/ident :eval/id
    :db/valueType :db.type/uuid
    :db/unique :db.unique/identity
    :db/cardinality :db.cardinality/one
    :db/doc "Unique eval identifier"}

   {:db/ident :eval/session-id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/index true
    :db/doc "Session this eval belongs to"}

   {:db/ident :eval/input
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Code that was evaluated"}

   {:db/ident :eval/output
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Result value (pr-str)"}

   {:db/ident :eval/stdout
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Standard output captured during eval"}

   {:db/ident :eval/stderr
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Standard error captured during eval"}

   {:db/ident :eval/exception
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Exception message if eval failed"}

   {:db/ident :eval/timestamp
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/index true}

   {:db/ident :eval/success
    :db/valueType :db.type/boolean
    :db/cardinality :db.cardinality/one}

   ;; Agent conversation turns
   {:db/ident :turn/id
    :db/valueType :db.type/uuid
    :db/unique :db.unique/identity
    :db/cardinality :db.cardinality/one}

   {:db/ident :turn/session-id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/index true}

   {:db/ident :turn/number
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc "Turn number within session (1, 2, 3...)"}

   {:db/ident :turn/timestamp
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident :turn/messages
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/doc "Messages in this turn"}

   {:db/ident :turn/tool-calls
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/doc "Tool calls made in this turn"}

   ;; Messages
   {:db/ident :message/id
    :db/valueType :db.type/uuid
    :db/unique :db.unique/identity
    :db/cardinality :db.cardinality/one}

   {:db/ident :message/role
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "user, assistant, system"}

   {:db/ident :message/content
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident :message/timestamp
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one}

   ;; Tool calls
   {:db/ident :tool-call/id
    :db/valueType :db.type/string
    :db/unique :db.unique/identity
    :db/cardinality :db.cardinality/one
    :db/doc "Tool call ID from LLM"}

   {:db/ident :tool-call/name
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident :tool-call/input
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "EDN string of input map"}

   {:db/ident :tool-call/result
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident :tool-call/timestamp
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one}

   ;; Tool results
   {:db/ident :tool-result/id
    :db/valueType :db.type/uuid
    :db/unique :db.unique/identity
    :db/cardinality :db.cardinality/one}

   {:db/ident :tool-result/type
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc ":success or :error"}

   {:db/ident :tool-result/content
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident :tool-result/metadata
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "EDN string of metadata map"}

   ;; Compaction snapshots (ghost snapshots — original messages before compaction)
   {:db/ident :compaction-snapshot/id
    :db/valueType :db.type/uuid
    :db/unique :db.unique/identity
    :db/cardinality :db.cardinality/one
    :db/doc "Unique compaction snapshot identifier"}

   {:db/ident :compaction-snapshot/chat-id
    :db/valueType :db.type/uuid
    :db/cardinality :db.cardinality/one
    :db/index true
    :db/doc "Chat this compaction belongs to"}

   {:db/ident :compaction-snapshot/timestamp
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident :compaction-snapshot/original-messages
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "EDN string of original messages before compaction"}

   {:db/ident :compaction-snapshot/summary
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Summary that replaced the original messages"}

   {:db/ident :compaction-snapshot/token-count
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc "Total tokens in original messages"}

   ;; Code metadata (syntax-level via rewrite-clj)
   {:db/ident :code/id
    :db/valueType :db.type/uuid
    :db/unique :db.unique/identity
    :db/cardinality :db.cardinality/one
    :db/doc "Unique identifier for this code entity"}

   {:db/ident :code/session-id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/index true
    :db/doc "Session that last modified this code"}

   {:db/ident :code/file
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/index true
    :db/doc "File path containing this definition"}

   {:db/ident :code/ns
    :db/valueType :db.type/symbol
    :db/cardinality :db.cardinality/one
    :db/index true
    :db/doc "Namespace symbol"}

   {:db/ident :code/name
    :db/valueType :db.type/symbol
    :db/cardinality :db.cardinality/one
    :db/index true
    :db/doc "Var name (e.g., 'greet, 'process-data)"}

   {:db/ident :code/qualified-name
    :db/valueType :db.type/symbol
    :db/unique :db.unique/identity
    :db/cardinality :db.cardinality/one
    :db/doc "Fully qualified name (e.g., 'myapp.core/greet)"}

   {:db/ident :code/type
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/index true
    :db/doc "Definition type: :defn :def :defmethod :defmacro :deftest :defprotocol etc"}

   {:db/ident :code/line
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc "Line number in file"}

   {:db/ident :code/column
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc "Column number in file"}

   {:db/ident :code/arglists
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "EDN string of argument lists (e.g., '([x] [x y])')"}

   {:db/ident :code/doc
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Docstring"}

   {:db/ident :code/calls
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/doc "References to code entities this function calls"}

   {:db/ident :code/called-by
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/doc "References to code entities that call this function"}

   {:db/ident :code/private?
    :db/valueType :db.type/boolean
    :db/cardinality :db.cardinality/one
    :db/doc "Whether this var is private"}

   {:db/ident :code/macro?
    :db/valueType :db.type/boolean
    :db/cardinality :db.cardinality/one
    :db/doc "Whether this is a macro"}

   {:db/ident :code/protocol
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc "Protocol reference for defmethod"}

   {:db/ident :code/dispatch-val
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "EDN string of dispatch value for defmethod"}

   {:db/ident :code/indexed-at
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/index true
    :db/doc "When this code was indexed"}])

;; ---------------------------------------------------------------------------
;; Database Management
;; ---------------------------------------------------------------------------

(defn- session-uuid
  "Create a deterministic UUID from session-id.
   This ensures the same UUID is used for create and connect operations."
  [session-id]
  (when session-id
    (let [md5 (MessageDigest/getInstance "MD5")
          hash (.digest md5 (.getBytes (str session-id) "UTF-8"))
          msb (BigInteger. 1 (byte-array (take 8 hash)))
          lsb (BigInteger. 1 (byte-array (take 8 (drop 8 hash))))]
      (UUID. (.longValue msb) (.longValue lsb)))))

(defn create-db
  "Create a new datahike database for a session.

   Options:
   - :store-id - unique ID for this DB (defaults to session-id)
   - :base-path - base directory for DB storage (default: data/sessions)"
  [{:keys [session-id store-id base-path]
    :or {base-path "data/sessions"}}]
  (let [db-path (str base-path "/" (or store-id session-id))
        config {:store {:backend :file
                        :path db-path
                        :id (session-uuid session-id)}
                :schema-flexibility :write
                :keep-history? true}]

    ;; Create database if it doesn't exist
    (when-not (d/database-exists? config)
      (d/create-database config))

    ;; Get connection and transact schema
    (let [conn (d/connect config)]
      (d/transact conn schema)
      conn)))

(defn connect-db
  "Connect to an existing session database."
  [{:keys [session-id store-id base-path]
    :or {base-path "data/sessions"}}]
  (let [db-path (str base-path "/" (or store-id session-id))
        config {:store {:backend :file
                        :path db-path
                        :id (session-uuid session-id)}
                :schema-flexibility :write
                :keep-history? true}]
    (when (d/database-exists? config)
      (d/connect config))))

(defn close-db
  "Close database connection."
  [conn]
  (d/release conn))

;; ---------------------------------------------------------------------------
;; REPL History
;; ---------------------------------------------------------------------------

(defn store-eval!
  "Store a REPL evaluation in the database."
  [conn {:keys [session-id code result]}]
  (let [eval-id (UUID/randomUUID)
        {:keys [value stdout stderr success error]} result
        base-data {:eval/id eval-id
                   :eval/session-id session-id
                   :eval/input code
                   :eval/output (pr-str value)
                   :eval/success (boolean success)
                   :eval/timestamp (java.util.Date.)}
        ;; Only add non-nil/non-empty values
        tx-data [(cond-> base-data
                   (not-empty stdout) (assoc :eval/stdout stdout)
                   (not-empty stderr) (assoc :eval/stderr stderr)
                   (and error (:message error)) (assoc :eval/exception (:message error)))]]
    (d/transact conn tx-data)
    eval-id))

(defn query-evals
  "Query REPL evaluation history.

   Examples:
   ;; All evals in session
   (query-evals conn {:session-id \"session-123\"})

   ;; Only successful evals
   (query-evals conn {:session-id \"session-123\" :success true})

   ;; Evals matching pattern
   (query-evals conn {:session-id \"session-123\" :pattern #\"defn\"})"
  [conn {:keys [session-id success pattern limit]
         :or {limit 100}}]
  (let [db (d/db conn)
        ;; Build query dynamically
        query (if (some? success)
                '[:find [(pull ?e [*]) ...]
                  :in $ ?session-id ?success
                  :where
                  [?e :eval/session-id ?session-id]
                  [?e :eval/success ?success]]
                '[:find [(pull ?e [*]) ...]
                  :in $ ?session-id
                  :where
                  [?e :eval/session-id ?session-id]])

        ;; Execute query
        results (if (some? success)
                  (d/q query db session-id success)
                  (d/q query db session-id))

        ;; Filter by pattern if specified
        results (if pattern
                  (filter #(re-find pattern (:eval/input %)) results)
                  results)

        ;; Sort by timestamp and limit
        results (->> results
                     (sort-by :eval/timestamp)
                     (reverse)
                     (take limit))]
    results))

;; ---------------------------------------------------------------------------
;; Agent Conversations
;; ---------------------------------------------------------------------------

(defn store-turn!
  "Store an agent conversation turn."
  [conn {:keys [session-id turn-number messages tool-calls]}]
  (let [turn-id (UUID/randomUUID)

        ;; Create message entities
        message-entities (mapv (fn [{:keys [role content]}]
                                 {:message/id (UUID/randomUUID)
                                  :message/role role
                                  :message/content content
                                  :message/timestamp (java.util.Date.)})
                               messages)

        ;; Create tool-call entities with results
        tool-call-entities (mapv (fn [{:keys [id name input result]}]
                                   (let [result-id (UUID/randomUUID)
                                         result-entity {:tool-result/id result-id
                                                        :tool-result/type (:type result)
                                                        :tool-result/content (or (:content result) "")
                                                        :tool-result/metadata (pr-str (:metadata result))}]
                                     [{:tool-call/id id
                                       :tool-call/name name
                                       :tool-call/input (pr-str input)
                                       :tool-call/result result-id
                                       :tool-call/timestamp (java.util.Date.)}
                                      result-entity]))
                                 tool-calls)

        ;; Flatten tool-call entities
        tool-call-entities (vec (mapcat identity tool-call-entities))

        ;; Create turn entity
        turn-entity {:turn/id turn-id
                     :turn/session-id session-id
                     :turn/number turn-number
                     :turn/timestamp (java.util.Date.)
                     :turn/messages (mapv :message/id message-entities)
                     :turn/tool-calls (mapv #(:tool-call/id (first %))
                                            (partition 2 tool-call-entities))}

        ;; Combine all entities
        tx-data (into [turn-entity]
                      (concat message-entities tool-call-entities))]

    (d/transact conn tx-data)
    turn-id))

(defn query-turns
  "Query agent conversation turns for a session."
  [conn session-id]
  (let [db (d/db conn)]
    (d/q '[:find [(pull ?t [* {:turn/messages [*]
                                :turn/tool-calls [* {:tool-call/result [*]}]}]) ...]
           :in $ ?session-id
           :where
           [?t :turn/session-id ?session-id]]
         db
         session-id)))

;; ---------------------------------------------------------------------------
;; Session Management
;; ---------------------------------------------------------------------------

(defn store-session!
  "Store session metadata."
  [conn {:keys [session-id title provider model created-at]}]
  (d/transact conn
              [{:session/id session-id
                :session/title title
                :session/provider provider
                :session/model model
                :session/created-at (or created-at (java.util.Date.))}]))

(defn get-session
  "Retrieve session metadata."
  [conn session-id]
  (let [db (d/db conn)]
    (d/q '[:find (pull ?s [*]) .
           :in $ ?session-id
           :where
           [?s :session/id ?session-id]]
         db
         session-id)))

(comment
  ;; Example usage
  (def conn (create-db {:session-id "test-session-1"}))

  ;; Store session metadata
  (store-session! conn {:session-id "test-session-1"
                        :title "Test Session"
                        :provider "fireworks"
                        :model "qwen"})

  ;; Store a REPL eval
  (store-eval! conn {:session-id "test-session-1"
                     :code "(+ 1 2)"
                     :result {:value 3
                              :stdout ""
                              :stderr ""
                              :success true}})

  ;; Query evals
  (query-evals conn {:session-id "test-session-1"})

  ;; Store a turn
  (store-turn! conn {:session-id "test-session-1"
                     :turn-number 1
                     :messages [{:role "user" :content "Hello"}
                                {:role "assistant" :content "Hi there"}]
                     :tool-calls [{:id "call-1"
                                   :name "read_file"
                                   :input {:path "test.clj"}
                                   :result {:type :success
                                            :content "(ns test)"
                                            :metadata {:size 100}}}]})

  ;; Query turns
  (query-turns conn "test-session-1")

  ;; Cleanup
  (close-db conn))

;; ---------------------------------------------------------------------------
;; Compaction Snapshot Queries (Ghost Snapshots)
;; ---------------------------------------------------------------------------

(defn query-compaction-snapshots
  "Query compaction snapshots for a chat.
   Returns vector of snapshots with :timestamp, :summary, :token-count.
   Original messages are stored as EDN strings (use edn/read-string to restore)."
  [conn chat-id]
  (let [db (d/db conn)]
    (->> (d/q '[:find [(pull ?e [*]) ...]
                :in $ ?chat-id
                :where [?e :compaction-snapshot/chat-id ?chat-id]]
              db chat-id)
         (sort-by :compaction-snapshot/timestamp))))

(defn restore-compaction-messages
  "Restore original messages from a ghost snapshot.
   Returns the message vector that was compacted away."
  [snapshot]
  (when-let [edn-str (:compaction-snapshot/original-messages snapshot)]
    (clojure.edn/read-string edn-str)))

;; ---------------------------------------------------------------------------
;; Code Metadata Analysis (rewrite-clj based - simpler, syntax-level only)
;; ---------------------------------------------------------------------------

(defn- extract-namespace
  "Extract namespace declaration from source code."
  [zloc]
  (when-let [ns-loc (z/find-value zloc z/next 'ns)]
    (try
      (when-let [ns-name-loc (z/right ns-loc)]
        (z/sexpr ns-name-loc))
      (catch Exception _ nil))))

(defn- extract-calls
  "Extract function calls from a form (syntax-level, not semantic).
   Returns set of symbols that look like function calls."
  [form]
  (let [calls (atom #{})]
    (clojure.walk/postwalk
     (fn [node]
       ;; Look for lists where first element is a symbol (likely a call)
       (when (and (list? node)
                  (seq node)
                  (symbol? (first node)))
         (swap! calls conj (first node)))
       node)
     form)
    @calls))

(defn extract-code-metadata
  "Extract code metadata from source using rewrite-clj.

   Returns vector of maps with:
   - :ns :name :qualified-name :type :line :column
   - :arglists :doc :private? :macro?
   - :calls (vector of symbols called by this def)"
  [file-path source]
  (try
    (let [zloc (z/of-string source {:track-position? true})
          ns-name (extract-namespace zloc)]

      (loop [loc zloc
             results []]
        (cond
          (z/end? loc) results

          ;; Match def forms: defn, def, defmethod, defmacro, deftest
          (and (z/list? loc)
               (contains? #{"defn" "def" "defmethod" "defmacro" "deftest"}
                          (-> loc z/down z/sexpr str)))
          (let [form-type (-> loc z/down z/sexpr str)
                form-name (try (-> loc z/down z/right z/sexpr str)
                               (catch Exception _ "unknown"))
                [row col] (try (z/position loc) (catch Exception _ [1 1]))

                ;; Extract full form to analyze calls
                full-form (try (z/sexpr loc) (catch Exception _ nil))
                calls (when full-form (extract-calls full-form))

                ;; Extract metadata from the name symbol
                name-meta (try
                            (meta (z/sexpr (z/right (z/down loc))))
                            (catch Exception _ {}))

                ;; Extract docstring if present
                docstring (try
                            (let [after-name (-> loc z/down z/right z/right)]
                              (when (string? (z/sexpr after-name))
                                (z/sexpr after-name)))
                            (catch Exception _ nil))

                ;; Extract arglist for defn/defmethod
                arglists (try
                           (let [after-name (-> loc z/down z/right z/right)
                                 maybe-args (if (string? (z/sexpr after-name))
                                             (z/right after-name)
                                             after-name)]
                             (when (z/vector? maybe-args)
                               [(z/sexpr maybe-args)]))
                           (catch Exception _ nil))

                qualified-name (when (and ns-name form-name)
                                 (symbol (str ns-name) form-name))]

            (recur (z/next loc)
                   (conj results
                         (cond-> {:ns ns-name
                                  :name (symbol form-name)
                                  :qualified-name qualified-name
                                  :type (keyword form-type)
                                  :file file-path
                                  :line row
                                  :column col
                                  :calls (vec (remove #(= (str %) form-name) calls))}

                           docstring
                           (assoc :doc docstring)

                           arglists
                           (assoc :arglists (pr-str arglists))

                           (:private name-meta)
                           (assoc :private? true)

                           (= form-type "defmacro")
                           (assoc :macro? true)))))

          :else (recur (z/next loc) results))))

    (catch Exception e
      [])))

(defn store-code-metadata!
  "Store code metadata for a file in datahike.

   Arguments:
   - conn: Datahike connection
   - session-id: Current session ID
   - file-path: Path to the file being indexed
   - source: Source code string

   Process:
   1. Retract existing code metadata for this file
   2. Extract metadata using rewrite-clj (syntax-level)
   3. Store in datahike with bidirectional call relationships"
  [conn session-id file-path source]
  (try
    ;; Step 1: Retract existing code for this file
    (let [db (d/db conn)
          existing (d/q '[:find [?e ...]
                          :in $ ?file
                          :where [?e :code/file ?file]]
                        db
                        file-path)]
      (when (seq existing)
        (d/transact conn (mapv (fn [e] [:db/retractEntity e]) existing))))

    ;; Step 2: Extract metadata
    (let [metadata (extract-code-metadata file-path source)]

      (when (seq metadata)
        ;; Step 3: Store metadata
        ;; First pass: create entities
        (let [;; Create entities with proper :code/ prefixed attributes
              entities (mapv (fn [m]
                              (cond-> {:code/id (UUID/randomUUID)
                                       :code/session-id session-id
                                       :code/indexed-at (java.util.Date.)
                                       :code/ns (:ns m)
                                       :code/name (:name m)
                                       :code/qualified-name (:qualified-name m)
                                       :code/type (:type m)
                                       :code/file (:file m)
                                       :code/line (:line m)
                                       :code/column (:column m)}

                                ;; Add optional fields only if present
                                (:arglists m) (assoc :code/arglists (:arglists m))
                                (:doc m) (assoc :code/doc (:doc m))
                                (:private? m) (assoc :code/private? (:private? m))
                                (:macro? m) (assoc :code/macro? (:macro? m))))
                             metadata)

              ;; Transact entities
              _ (d/transact conn entities)

              ;; Second pass: add call relationships
              db (d/db conn)

              ;; Build qualified-name -> entity-id map
              qname->id (into {}
                              (d/q '[:find ?qname ?e
                                     :where [?e :code/qualified-name ?qname]]
                                   db))

              ;; Build call relationship transactions
              ;; Try to match calls by: 1) qualified name, 2) same-namespace name
              call-txs (for [m metadata
                             :let [caller-id (get qname->id (:qualified-name m))
                                   caller-ns (:ns m)]
                             :when caller-id
                             called-sym (:calls m)
                             ;; Try to find the called entity by qualified name or by qualifying with caller's ns
                             :let [called-id (or (get qname->id called-sym)
                                                (when (and caller-ns (symbol? called-sym))
                                                  (get qname->id (symbol (str caller-ns) (str called-sym)))))]
                             :when called-id]
                         [:db/add caller-id :code/calls called-id])]

          ;; Transact call relationships
          (when (seq call-txs)
            (d/transact conn call-txs))

          {:success true
           :file file-path
           :entities-stored (count entities)
           :call-relationships (count call-txs)})))

    (catch Exception e
      {:success false
       :file file-path
       :error (.getMessage e)})))

(defn query-code
  "Query code metadata using datahike queries.

   Examples:
   ;; Find all functions
   (query-code conn '[:find ?name
                      :where [?e :code/type :defn]
                             [?e :code/name ?name]])

   ;; Find all callers of a function
   (query-code conn '[:find ?caller-name
                      :in $ ?called-name
                      :where [?called :code/name ?called-name]
                             [?caller :code/calls ?called]
                             [?caller :code/name ?caller-name]]
                 'greet)

   ;; Find all functions in a namespace
   (query-code conn '[:find ?name ?line
                      :in $ ?ns
                      :where [?e :code/ns ?ns]
                             [?e :code/type :defn]
                             [?e :code/name ?name]
                             [?e :code/line ?line]]
                 'myapp.core)"
  [conn query & inputs]
  (let [db (d/db conn)]
    (apply d/q query db inputs)))

;; ---------------------------------------------------------------------------
;; Code Query Helpers (Common patterns for agents)
;; ---------------------------------------------------------------------------

(defn find-function
  "Find a function by name (qualified or unqualified).
   Returns map with :name :qualified-name :file :line :doc :arglists"
  [conn name-or-qname]
  (let [db (d/db conn)
        ;; Try both :code/name and :code/qualified-name separately
        by-name (d/q '[:find [?e ...]
                       :in $ ?search-name
                       :where
                       [?e :code/name ?name]
                       [(= ?name ?search-name)]]
                     db
                     name-or-qname)
        by-qname (d/q '[:find [?e ...]
                        :in $ ?search-qname
                        :where
                        [?e :code/qualified-name ?qname]
                        [(= ?qname ?search-qname)]]
                      db
                      name-or-qname)
        eid (first (or (seq by-name) (seq by-qname)))]
    (when eid
      (let [entity (d/pull db '[:code/name :code/qualified-name :code/file
                                :code/line :code/doc :code/arglists] eid)]
        {:name (:code/name entity)
         :qualified-name (:code/qualified-name entity)
         :file (:code/file entity)
         :line (:code/line entity)
         :doc (:code/doc entity)
         :arglists (when (:code/arglists entity)
                     (read-string (:code/arglists entity)))}))))

(defn list-functions
  "List all functions in a namespace (or all if no namespace given).
   Returns vector of maps with :name :qualified-name :line :doc"
  ([conn]
   (let [db (d/db conn)
         ;; Query for entity IDs, then pull all attributes
         eids (d/q '[:find [?e ...]
                     :where [?e :code/type :defn]]
                   db)]
     (mapv (fn [eid]
             (let [entity (d/pull db '[:code/name :code/qualified-name
                                       :code/line :code/doc] eid)]
               {:name (:code/name entity)
                :qualified-name (:code/qualified-name entity)
                :line (:code/line entity)
                :doc (:code/doc entity)}))
           (sort-by (fn [eid]
                      (:code/line (d/pull db '[:code/line] eid)))
                    eids))))
  ([conn ns-sym]
   (let [db (d/db conn)
         ;; Query for entity IDs in namespace, then pull all attributes
         eids (d/q '[:find [?e ...]
                     :in $ ?ns
                     :where [?e :code/type :defn]
                            [?e :code/ns ?n]
                            [(= ?n ?ns)]]
                   db
                   ns-sym)]
     (mapv (fn [eid]
             (let [entity (d/pull db '[:code/name :code/qualified-name
                                       :code/line :code/doc] eid)]
               {:name (:code/name entity)
                :qualified-name (:code/qualified-name entity)
                :line (:code/line entity)
                :doc (:code/doc entity)}))
           (sort-by (fn [eid]
                      (:code/line (d/pull db '[:code/line] eid)))
                    eids)))))

(defn find-callers
  "Find all functions that call the given function.
   Returns vector of maps with :name :qualified-name :file :line"
  [conn callee-name-or-qname]
  (let [db (d/db conn)
        by-name (d/q '[:find ?caller-name ?caller-qname ?file ?line
                       :in $ ?callee
                       :where
                       [?e :code/name ?name]
                       [(= ?name ?callee)]
                       [?caller :code/calls ?e]
                       [?caller :code/name ?caller-name]
                       [?caller :code/qualified-name ?caller-qname]
                       [?caller :code/file ?file]
                       [?caller :code/line ?line]]
                     db
                     callee-name-or-qname)
        by-qname (d/q '[:find ?caller-name ?caller-qname ?file ?line
                        :in $ ?callee
                        :where
                        [?e :code/qualified-name ?qname]
                        [(= ?qname ?callee)]
                        [?caller :code/calls ?e]
                        [?caller :code/name ?caller-name]
                        [?caller :code/qualified-name ?caller-qname]
                        [?caller :code/file ?file]
                        [?caller :code/line ?line]]
                      db
                      callee-name-or-qname)
        ;; Use set to deduplicate, then sort
        results (into #{} (concat by-name by-qname))]
    (vec (sort-by :line
                  (map (fn [[name qname file line]]
                         {:name name
                          :qualified-name qname
                          :file file
                          :line line})
                       results)))))

(defn find-callees
  "Find all functions called by the given function.
   Returns vector of maps with :name :qualified-name :file :line"
  [conn caller-name-or-qname]
  (let [db (d/db conn)
        by-name (d/q '[:find ?callee-name ?callee-qname ?file ?line
                       :in $ ?caller
                       :where
                       [?e :code/name ?name]
                       [(= ?name ?caller)]
                       [?e :code/calls ?callee]
                       [?callee :code/name ?callee-name]
                       [?callee :code/qualified-name ?callee-qname]
                       [?callee :code/file ?file]
                       [?callee :code/line ?line]]
                     db
                     caller-name-or-qname)
        by-qname (d/q '[:find ?callee-name ?callee-qname ?file ?line
                        :in $ ?caller
                        :where
                        [?e :code/qualified-name ?qname]
                        [(= ?qname ?caller)]
                        [?e :code/calls ?callee]
                        [?callee :code/name ?callee-name]
                        [?callee :code/qualified-name ?callee-qname]
                        [?callee :code/file ?file]
                        [?callee :code/line ?line]]
                      db
                      caller-name-or-qname)
        ;; Use set to deduplicate, then sort
        results (into #{} (concat by-name by-qname))]
    (vec (sort-by :line
                  (map (fn [[name qname file line]]
                         {:name name
                          :qualified-name qname
                          :file file
                          :line line})
                       results)))))

(defn search-functions-by-doc
  "Search functions by documentation string (case-insensitive substring match).
   Returns vector of maps with :name :qualified-name :file :line :doc"
  [conn search-term]
  (let [db (d/db conn)
        lower-term (clojure.string/lower-case search-term)
        all-fns (d/q '[:find ?name ?qname ?file ?line ?doc
                       :where
                       [?e :code/type :defn]
                       [?e :code/name ?name]
                       [?e :code/qualified-name ?qname]
                       [?e :code/file ?file]
                       [?e :code/line ?line]
                       [?e :code/doc ?doc]]
                     db)]
    (mapv (fn [[name qname file line doc]]
            {:name name
             :qualified-name qname
             :file file
             :line line
             :doc doc})
          (sort-by #(nth % 3)
                   (filter (fn [[_ _ _ _ doc]]
                             (when doc
                               (clojure.string/includes?
                                (clojure.string/lower-case doc)
                                lower-term)))
                           all-fns)))))
