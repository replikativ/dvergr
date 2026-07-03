(ns dvergr.search.secondary
  "Fulltext memory search via datahike's NATIVE pluggable secondary index
   (`datahike.index.secondary` + the `:scriptum` adapter) — NOT a hand-rolled
   sidecar. Declared in the room's schema, maintained by datahike on every
   transact, planner-integrated, and fork-aware (`IVersionedSecondaryIndex`
   forks the Lucene segments WITH the room's datahike branch — so per-room
   memory branches on fork and merges on merge-room for free).

   The retrieval unit is the datahike ENTITY: `search` returns BM25-ranked
   `[[entity-id score] …]` (via `sec/-slice-ordered`), a datalog RELATION binding
   — so ranked fulltext composes with structured constraints (room/author/time/
   type) in one `d/q`:
     [(search :room/fulltext \"gc reap\") [[?e ?score]]]  [?e :message/content ?c]
   This is \"fulltext working in datalog queries themselves\": the search fn is
   called inside a `:where` clause and its hits join on the entity.

   NOTE: the `:scriptum`/`:proximum` adapters live in datahike's `src-secondary`
   root, which the RELEASED jar already ships (verified in datahike-0.8.1708);
   only a `:local/root \"../datahike\"` source override needs the extra path (see
   deps.edn :local)."
  (:require [datahike.api :as d]
            [datahike.index.secondary :as sec]
            ;; registers the :scriptum index type in the secondary registry
            [datahike.index.secondary.scriptum]))

(defn declare-index!
  "Declare a scriptum fulltext secondary index `ident` over `attrs` on `conn`,
   with scriptum storage at `path`. Idempotent-ish (re-transacting the same
   ident updates it). datahike builds it async; poll `ready?`."
  [conn ident attrs path]
  (d/transact conn [{:db/ident              ident
                     :db.secondary/type     :scriptum
                     :db.secondary/attrs    (vec attrs)
                     :db.secondary/config   {:path path}}])
  ident)

;; Standard per-room index idents (one scriptum index per corpus, forks with the
;; room's datahike store). The scriptum storage path is derived from the store
;; path so it sits alongside the room's data.
(def message-fulltext-ident :room/fulltext)
(def kb-fulltext-ident      :room/kb-fulltext)

(defn declare-message-fulltext!
  "Declare the standard messages fulltext index on a room's msgs `conn`; scriptum
   storage at `<store-path>-ft`. Best-effort — a failure here must NOT break room
   provisioning, only leave fulltext search unavailable for the room."
  [conn store-path]
  (try (declare-index! conn message-fulltext-ident [:message/content] (str store-path "-ft"))
       (catch Throwable _ nil)))

(defn declare-kb-fulltext!
  "Declare the standard KB fulltext index on a room's kb `conn`; indexes entity
   title + summary + contexts (the block-like atoms). Best-effort."
  [conn store-path]
  (try (declare-index! conn kb-fulltext-ident
                       [:entity/title :entity/summary :entity/contexts]
                       (str store-path "-ft"))
       (catch Throwable _ nil)))

(defn ready?
  "True once datahike has finished building the secondary index `ident`."
  [db ident]
  (= :ready (get-in db [:schema ident :db.secondary/status])))

(defn- index-of [db ident] (get-in db [:secondary-indices ident]))

(defn search
  "Relevance-RANKED fulltext search of secondary index `ident` on `db`. Returns
   `[[entity-id score] …]` — Long eid + BM25 score, descending by score. That
   tuple shape is a datalog RELATION binding, so ranking composes straight into a
   `d/q`:

     [(search :room/fulltext \"gc reap\") [[?e ?score]]]  ; in a :where clause
     [?e :message/content ?content]                       ; joined with structure

   `:limit` caps results (default 20), `:field` the datom field (default :value),
   `:filter` an optional EntityBitSet that pre-restricts the candidate set (e.g.
   the entity set of a prior structured query). Backed by datahike's native
   `sec/-slice-ordered` on the :scriptum adapter."
  ([db ident query] (search db ident query {}))
  ([db ident query {:keys [field filter limit] :or {field :value limit 20}}]
   (if-let [ft (index-of db ident)]
     (mapv (fn [{:keys [entity-id score]}] [(long entity-id) score])
           (sec/-slice-ordered ft {:query query :field field} filter nil :desc limit))
     [])))

(defn search-eids
  "Ranked entity ids only (Long, descending relevance) — `(map first (search …))`.
   Use when you don't need scores; binds as `[?e ...]` in datalog."
  ([db ident query] (search-eids db ident query {}))
  ([db ident query opts] (mapv first (search db ident query opts))))
