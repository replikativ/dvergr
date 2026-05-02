(ns dvergr.knowledge.schema
  "Simmis-compatible knowledge schema for dvergr.

   This schema is designed to be compatible with simmis's categorical
   block/page model. Notes created by dvergr agents will render
   correctly in simmis's wiki UI.

   Key design decisions:
   - Use simmis entity-schema and block-schema
   - Pages reference S/Page type via :instance/of-role
   - Content stored in :block/content (markdown)
   - Wiki-links stored in :block/references
   - Add :dvergr/* attributes for agent-specific metadata"
  (:require [datahike.api :as d]
            [clojure.string :as str]))

;; ============================================================================
;; Simmis Core Schema (imported)
;; ============================================================================

(def entity-schema
  "Universal attributes for all entities (from simmis)"
  [{:db/ident :entity/uuid
    :db/valueType :db.type/uuid
    :db/unique :db.unique/identity
    :db/cardinality :db.cardinality/one
    :db/doc "Stable UUID for all entities"}

   {:db/ident :entity/name
    :db/valueType :db.type/string
    :db/unique :db.unique/identity
    :db/cardinality :db.cardinality/one
    :db/doc "Human-readable unique name. Used for lookup refs."}

   {:db/ident :entity/created-at
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc "Creation timestamp"}

   {:db/ident :entity/updated-at
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc "Last update timestamp"}])

(def block-schema
  "Block attributes (from simmis - everything is a block)"
  [{:db/ident :block/parent
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc "Parent block. Nil for top-level pages."}

   {:db/ident :block/order
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Fractional index string for block ordering."}

   {:db/ident :block/content
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Content of the block (markdown for notes)"}

   {:db/ident :block/references
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/doc "Entities referenced via [[page]] or ((block-uuid)) syntax"}

   {:db/ident :block/collapsed
    :db/valueType :db.type/boolean
    :db/cardinality :db.cardinality/one
    :db/doc "Whether this block is collapsed in the UI"}

   {:db/ident :instance/of-role
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/doc "Schema objects (types) this instance conforms to. Points to S/Page etc."}])

;; ============================================================================
;; Page Properties (simmis morphism-generated)
;; ============================================================================

(def page-schema
  "Page-specific attributes (morphism-generated in simmis)"
  [{:db/ident :S.Page/title
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Page title (display name)"}

   {:db/ident :S.Page/archived
    :db/valueType :db.type/boolean
    :db/cardinality :db.cardinality/one
    :db/doc "Whether this page is archived"}])

;; ============================================================================
;; Dvergr-Specific Extensions
;; ============================================================================

(def dvergr-schema
  "Dvergr-specific attributes for knowledge entities"
  [;; Authorship tracking
   {:db/ident :dvergr/author
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Name of agent/user that created this note"}

   {:db/ident :dvergr/author-model
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Model used by agent author (e.g., 'claude-sonnet-4-5')"}

   ;; Session tracking
   {:db/ident :dvergr/from-sessions
    :db/valueType :db.type/uuid
    :db/cardinality :db.cardinality/many
    :db/doc "UUIDs of sessions that contributed to this note"}

   ;; Context accumulation (from wiki-link contexts)
   {:db/ident :dvergr/contexts
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/many
    :db/doc "Context strings from [[Entity][context]] mentions"}

   ;; Mention tracking
   {:db/ident :dvergr/mention-count
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc "Total number of mentions across all sessions"}

   ;; Source type
   {:db/ident :dvergr/source
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc "How this note was created: :compaction :web-fetch :manual"}])

;; ============================================================================
;; Full Knowledge Schema
;; ============================================================================

(def knowledge-schema
  "Complete knowledge schema (simmis-compatible + dvergr extensions)"
  (vec (concat entity-schema
               block-schema
               page-schema
               dvergr-schema)))

;; ============================================================================
;; S/Page Type Reference
;; ============================================================================

;; Well-known UUID for S/Page type in simmis
(def page-type-uuid #uuid "00000000-0000-0000-0000-000000000022")

;; Lookup ref for S/Page (works if simmis seed data is present)
(def page-type-ref [:entity/name "S/Page"])

;; ============================================================================
;; Schema Installation
;; ============================================================================

(defn install-knowledge-schema!
  "Install the knowledge schema into a datahike connection.

   Note: This creates the attributes but not the S/Page type entity.
   If using with simmis, the type will be provided by simmis seed data."
  [conn]
  (d/transact conn knowledge-schema))

;; ============================================================================
;; Note Creation Helpers
;; ============================================================================

(defn create-note
  "Create a simmis-compatible note (page) entity.

   Args:
     title - Note title (becomes :entity/name and :S.Page/title)
     content - Markdown content for :block/content

   Options:
     :author - Agent/user name
     :author-model - Model used
     :session-id - UUID of session creating this note
     :contexts - Vector of context strings
     :references - Vector of entity lookup refs for :block/references
     :source - :compaction, :web-fetch, or :manual

   Returns entity map ready for transacting."
  [title content & {:keys [author author-model session-id contexts references source]
                    :or {source :manual}}]
  (let [now (java.util.Date.)
        uuid (random-uuid)]
    (cond-> {:entity/uuid uuid
             :entity/name title
             :entity/created-at now
             :entity/updated-at now
             :S.Page/title title
             :S.Page/archived false
             :block/content content
             :dvergr/source source
             :dvergr/mention-count 1}
      author (assoc :dvergr/author author)
      author-model (assoc :dvergr/author-model author-model)
      session-id (assoc :dvergr/from-sessions [session-id])
      (seq contexts) (assoc :dvergr/contexts (vec contexts))
      (seq references) (assoc :block/references (vec references)))))

(defn update-note
  "Generate update map for an existing note.

   Args:
     note-eid - Entity ID of the note to update
     content - New markdown content

   Options:
     :session-id - UUID of session contributing this update
     :contexts - Additional context strings to add
     :references - New references to add

   Returns entity map for transacting (incremental update)."
  [note-eid content & {:keys [session-id contexts references]}]
  (let [now (java.util.Date.)]
    (cond-> {:db/id note-eid
             :entity/updated-at now
             :block/content content}
      session-id (assoc :dvergr/from-sessions session-id)
      (seq contexts) (assoc :dvergr/contexts contexts)
      (seq references) (assoc :block/references references))))

;; ============================================================================
;; Note Query Helpers
;; ============================================================================

(defn find-note-by-title
  "Find a note by title. Returns entity map or nil."
  [db title]
  (d/q '[:find (pull ?e [*]) .
         :in $ ?title
         :where
         [?e :entity/name ?title]
         [?e :S.Page/title _]]
       db title))

(defn find-note-eid-by-title
  "Find note entity ID by title. Returns eid or nil."
  [db title]
  (d/q '[:find ?e .
         :in $ ?title
         :where
         [?e :entity/name ?title]
         [?e :S.Page/title _]]
       db title))

(defn list-notes
  "List all notes with optional filtering/sorting.

   Options:
     :limit - Max notes to return (default 100)
     :order-by - :updated-at, :created-at, :mention-count, :title
     :source - Filter by :dvergr/source

   Returns vector of entity maps."
  [db & {:keys [limit order-by source]
         :or {limit 100 order-by :updated-at}}]
  (let [notes (d/q '[:find [(pull ?e [*]) ...]
                     :where
                     [?e :S.Page/title _]
                     [?e :entity/uuid _]]
                   db)
        filtered (if source
                   (filter #(= source (:dvergr/source %)) notes)
                   notes)
        sorted (case order-by
                 :updated-at (sort-by #(- (.getTime (or (:entity/updated-at %) (java.util.Date. 0)))) filtered)
                 :created-at (sort-by #(- (.getTime (or (:entity/created-at %) (java.util.Date. 0)))) filtered)
                 :mention-count (sort-by #(- (or (:dvergr/mention-count %) 0)) filtered)
                 :title (sort-by :entity/name filtered)
                 filtered)]
    (vec (take limit sorted))))

(defn find-backlinks
  "Find all notes that reference a given note.

   Args:
     db - Datahike database value
     note-eid - Entity ID of the note

   Returns vector of entity maps."
  [db note-eid]
  (d/q '[:find [(pull ?e [*]) ...]
         :in $ ?target
         :where
         [?e :block/references ?target]
         [?e :S.Page/title _]]
       db note-eid))

(defn search-notes
  "Full-text search across note titles and content.

   Note: This is a simple contains? search. For production,
   use Scriptum's Lucene integration.

   Args:
     db - Datahike database value
     query - Search string

   Returns vector of entity maps."
  [db query]
  (let [pattern (re-pattern (str "(?i)" (java.util.regex.Pattern/quote query)))
        notes (d/q '[:find [(pull ?e [*]) ...]
                     :where
                     [?e :S.Page/title _]]
                   db)]
    (->> notes
         (filter (fn [note]
                   (or (re-find pattern (or (:entity/name note) ""))
                       (re-find pattern (or (:block/content note) "")))))
         vec)))

;; ============================================================================
;; Wiki-Link Helpers
;; ============================================================================

(def wiki-link-pattern
  "Regex for extracting [[entity][context]] patterns"
  #"\[\[([^\]\[]+)\](?:\[([^\]]+)\])?\]")

(defn extract-wiki-links
  "Extract wiki-links from text.
   Returns vector of {:title \"Entity\" :context \"optional context\"}"
  [text]
  (->> (re-seq wiki-link-pattern text)
       (map (fn [[_ title context]]
              {:title title
               :context context}))
       vec))

(defn resolve-wiki-link-refs
  "Resolve wiki-link titles to entity lookup refs.
   Returns vector of [:entity/name title] refs for existing entities."
  [db titles]
  (let [existing (set (d/q '[:find [?name ...]
                             :in $ [?name ...]
                             :where [?e :entity/name ?name]]
                           db titles))]
    (->> titles
         (filter existing)
         (mapv (fn [title] [:entity/name title])))))

(comment
  ;; Example usage:

  ;; Create database and install schema
  (def cfg {:store {:backend :mem :id "knowledge-test"}})
  (d/create-database cfg)
  (def conn (d/connect cfg))
  (install-knowledge-schema! conn)

  ;; Create a note
  (d/transact conn [(create-note "Clojure"
                                 "[[Clojure]] is a dynamic [[Lisp]] dialect on the [[JVM]]."
                                 :author "claude-sonnet"
                                 :source :compaction)])

  ;; Find the note
  (find-note-by-title @conn "Clojure")

  ;; List all notes
  (list-notes @conn :order-by :updated-at)

  ;; Extract wiki-links
  (extract-wiki-links "Check out [[Clojure][the language]] and [[Lisp]]")
  ;; => [{:title "Clojure" :context "the language"} {:title "Lisp" :context nil}]
  )
