(ns dvergr.kb.schema
  "The knowledge base as a katzen ACSet — BOUND from the canonical
   `katzen.schema.knowledge`. dvergr no longer defines a KB schema of its own;
   it maps the abstract canonical names onto its datahike idents (`:entity/*`,
   `:link/*`) via `katzen.acset/rename-schema`. Single source of truth = katzen
   (doc/katzen-knowledge-and-code.md, P2).

   `as-acset` wraps an app-managed knowledge-schema conn: install missing schema
   idents (incl. the `:katzen/ob` marker — existing columns are left as the app
   defined them) and tag existing entities so they enumerate."
  (:require [datahike.api :as d]
            [katzen.acset :as ka]
            [katzen.schema.knowledge :as k]))

(def idents
  "Bind the abstract canonical knowledge-schema names to dvergr's datahike
   idents (the columns dvergr already reads/writes). Objects (Entity, Link) and
   attr-types keep their names; only morphisms are renamed."
  {:title         :entity/title
   :summary       :entity/summary
   :kind          :entity/type
   :url           :entity/url
   :role          :entity/role
   :mention-count :entity/mention-count
   :created-at    :entity/created-at
   :updated-at    :entity/updated-at
   :employer      :entity/employer
   :links         :entity/links
   ;; dvergr-internal KB columns (domain-extension)
   :id            :entity/id
   :contexts      :entity/contexts
   :from-sessions :entity/from-sessions
   :tags          :entity/tags})

(def domain-extension
  "dvergr's CRM-flavoured + storage KB fields, NOT part of the generic katzen
   knowledge graph. Merged onto `katzen.schema.knowledge` rather than baked into
   it. Together with the generic schema these are the COMPLETE set of `:entity/*`
   columns dvergr stores — `knowledge-datahike-schema` generates the datahike
   schema from here, so this is the single source of truth (see
   `dvergr.chat.schema/knowledge-schema`).

   `id` is dvergr's internal datahike key (uuid, unique-identity); the categorical
   cross-ACSet Identity remains `title` (the URI). `contexts`/`tags`/`from-sessions`
   are cardinality-many; `employer` is the person→company edge."
  {:homs       [{:name :employer :dom :Entity :codom :Entity}]
   :attr-types [:Long :UUID]
   :attrs      [{:name :id            :dom :Entity :codom :UUID :unique :db.unique/identity}
                {:name :url           :dom :Entity :codom :String}
                {:name :role          :dom :Entity :codom :String}
                {:name :mention-count :dom :Entity :codom :Long}
                {:name :contexts      :dom :Entity :codom :String :cardinality :many}
                {:name :from-sessions :dom :Entity :codom :UUID   :cardinality :many}
                {:name :tags          :dom :Entity :codom :String :cardinality :many}]})

(def kb-schema
  "dvergr's KB ACSet schema = the generic `katzen.schema.knowledge` extended
   with `domain-extension`, then bound to dvergr's datahike idents."
  (ka/rename-schema (ka/merge-schema k/schema domain-extension) idents))

;; ---------------------------------------------------------------------------
;; datahike schema generation — kb-schema is the single source of truth for the
;; KB's `:entity/*` columns; dvergr.chat.schema installs what this emits.

(def ^:private attr-type->value-type
  "The datahike :db/valueType for each katzen attr-type the KB uses (a thin local
   mirror of katzen.acset.datahike's mapping — only the types this schema uses)."
  {:Identity :db.type/string :String :db.type/string :Keyword :db.type/keyword
   :Long :db.type/long :Instant :db.type/instant :UUID :db.type/uuid})

(def ^:private column-docs
  "Human docs per column, attached to the generated datahike schema."
  {:entity/id            "Unique identifier for entity"
   :entity/title         "Entity title (the [[Title]] in wiki-links)"
   :entity/summary       "AI-generated summary of what this entity is"
   :entity/contexts      "Context strings from [[Entity][context]] mentions"
   :entity/links         "References to related entities"
   :entity/from-sessions "Sessions that mentioned this entity"
   :entity/mention-count "Total number of mentions across all sessions"
   :entity/created-at    "When the entity was first mentioned"
   :entity/updated-at    "When the entity was last updated"
   :entity/type          "Entity type: :competitor :client :partner :project :technology"
   :entity/url           "Primary URL for this entity"
   :entity/tags          "Tags for categorization"
   :entity/role          "Job title / role (for :person entities, e.g. 'VP of Engineering at Acme Corp')"
   :entity/employer      "Employing company entity ref (for :person entities)"})

(defn- card [m]
  (if (= :many (:cardinality m)) :db.cardinality/many :db.cardinality/one))

(defn knowledge-datahike-schema
  "The datahike attribute schema for the KB, GENERATED from `kb-schema`. Homs
   (links, employer) become `:db.type/ref`; attrs map their katzen attr-type to a
   datahike valueType; `:unique` and `:cardinality` carry through; docs come from
   `column-docs`. This — not a hand-written list — is what dvergr installs, so
   `kb-schema` is the single source of truth for the KB's columns. (No `:db/index`
   and no `:katzen/ob` marker here: the marker is installed only when wrapping a
   conn as an ACSet, see `as-acset`.)"
  []
  (let [doc  (fn [ident] (when-let [d (column-docs ident)] {:db/doc d}))
        ref-attr (fn [{:keys [name unique] :as h}]
                   (merge {:db/ident name :db/valueType :db.type/ref :db/cardinality (card h)}
                          (when unique {:db/unique unique}) (doc name)))
        val-attr (fn [{:keys [name codom unique] :as a}]
                   (merge {:db/ident name
                           :db/valueType (get attr-type->value-type codom :db.type/string)
                           :db/cardinality (card a)}
                          (when unique {:db/unique unique}) (doc name)))]
    (-> (mapv ref-attr (:homs kb-schema))
        (into (map val-attr) (:attrs kb-schema)))))

(def identity-attr
  "The bound Identity attr — the entity's URI / [[wiki-link]] title."
  (get idents k/identity-attr))      ; => :entity/title

(defn ensure-object-markers!
  "Tag every knowledge Entity on `conn` (anything with `:entity/id`) that isn't
   already tagged with the katzen object marker `:katzen/ob :Entity`, so an ACSet
   can enumerate Entity parts. Idempotent. The `:katzen/ob` attribute must be
   installed first (`as-acset`/`datahike-acset` does that). Returns the count
   newly tagged. Pure datahike — no katzen dependency."
  [conn]
  (let [untagged (->> (d/q '[:find ?e
                             :where [?e :entity/id _]
                             (not [?e :katzen/ob :Entity])]
                           (d/db conn))
                      (map first))]
    (when (seq untagged)
      (d/transact conn {:tx-data (mapv (fn [e] {:db/id e :katzen/ob :Entity}) untagged)}))
    (count untagged)))

(defn as-acset
  "Wrap a live, app-managed KB datahike `conn` as a katzen ACSet over
   `kb-schema`: install any missing schema idents (incl. the `:katzen/ob`
   marker — existing columns like `:entity/title` are left exactly as the app
   defined them), then tag existing entities. Returns the ACSet."
  [conn]
  (let [datahike-acset (requiring-resolve 'katzen.acset.datahike/datahike-acset)
        acset (datahike-acset kb-schema conn)]
    (ensure-object-markers! conn)
    acset))
