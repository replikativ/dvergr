(ns dvergr.code.index
  "The in-memory CODE index as a katzen ACSet (interface-level L2,
   doc/katzen-knowledge-and-code.md).

   The git-worktree text is ground truth; THIS is a derived projection over
   `katzen.schema.clojure-code` — `Namespace`s and `Def`s keyed by the shared
   `:def/qname` Identity URI, with their relationships (`:def/ns`, `:def/refs`,
   `:def/method-of`, `:def/implements`) and interface facts (`:def/kind`,
   `:def/doc`, `:def/private`, `:def/arities`, …) — built from source via
   `dvergr.code/project-source`, which merges a SYNTACTIC pass (rewrite-clj form
   heads + meta: kind/qname/doc/private?/dispatch/implements) with a SEMANTIC
   pass (tools.analyzer: resolved `:def/refs`, arities, variadic?). It lives on a
   `:memory` datahike conn, is rebuildable from text at any moment, and is never
   durably persisted.

   Because it is an ACSet over a shared `Identity`, it renders through the same
   S→Comp functor as the knowledge base and cross-references the KB (and anything
   else) via `katzen.xref` — a KB note about `demo.core/parse` joins to this
   index's `Def` of the same qname. find-references = the inverse image of
   `:def/refs` (`incident`); a multimethod's methods = the inverse image of
   `:def/method-of`.

   The index REPROJECTS per file (`index-file!`) — cheap for an in-memory store.
   `dvergr.code/diff-source` (per-def minimal change set) remains for the
   text / durable-knowledge layer, where minimal transactions matter.

   DEV-SCOPED: requires katzen. Semantic resolution is against the host JVM; for
   sandboxed *agent* code, analyze in the SCI ctx instead (doc §7b). Alias
   resolution for `:def/method-of` / `:def/implements` is syntactic best-effort
   (a follow-up, like intra-project ref resolution)."
  (:require [clojure.string :as str]
            [dvergr.code :as code]
            [katzen.acset :as a]
            [katzen.acset.datahike :as kdh]
            [katzen.schema.clojure-code :as cc]))

(def idents
  "Bind the abstract canonical clojure-code schema names to dvergr's datahike
   idents. Objects (Def, Namespace) keep their names; homs/attrs are renamed."
  {;; Def attrs/homs
   :qname :def/qname  :kind :def/kind   :source :def/source :file :def/file
   :line :def/line    :doc :def/doc     :private? :def/private
   :dispatch-val :def/dispatch-val      :implements :def/implements
   :refs :def/refs    :arities :def/arities :variadic? :def/variadic
   :def-ns :def/ns    :method-of :def/method-of
   ;; Namespace
   :ns-name :ns/name  :requires :ns/requires :ns-doc :ns/doc})

(def code-schema
  "dvergr's code-index ACSet schema = the canonical `katzen.schema.clojure-code`
   bound to dvergr's datahike idents."
  (a/rename-schema cc/schema idents))

(defn create
  "A fresh, empty code index (a katzen ACSet on a :memory datahike conn)."
  []
  (kdh/datahike-acset code-schema))

;; ---------------------------------------------------------------------------
;; Internals

(defn- find-def [acset uri]    (first (a/incident acset :def/qname uri)))
(defn- find-ns-part [acset nm] (first (a/incident acset :ns/name nm)))

(defn- put-ns!
  "Find-or-create the Namespace for `ns-facts`; set its name/requires/doc.
   Returns the part-id (or nil if there's no ns)."
  [acset {:keys [ns requires doc]}]
  (when ns
    (let [nm (str ns)
          p  (or (find-ns-part acset nm) (second (a/add-part acset :Namespace)))]
      (a/set-subpart acset :ns/name p nm)
      (when (seq requires) (a/set-subpart acset :ns/requires p (set (map str requires))))
      (when doc            (a/set-subpart acset :ns/doc p doc))
      p)))

(defn- put-def!
  "Find-or-create the Def for `:qname`; set every interface-level attr present in
   the projected fact map, plus the `:def/ns` hom. Returns the Def part-id.
   `:def/method-of` is linked in a second pass (`link-methods!`)."
  [acset ns-part {:keys [qname kind source doc private? dispatch-val
                         implements refs arities variadic?]}]
  (let [p (or (find-def acset qname) (second (a/add-part acset :Def)))]
    (a/set-subpart acset :def/qname    p qname)
    (a/set-subpart acset :def/private  p (boolean private?))
    (a/set-subpart acset :def/variadic p (boolean variadic?))
    (when ns-part          (a/set-subpart acset :def/ns p ns-part))
    (when kind             (a/set-subpart acset :def/kind p kind))
    (when source           (a/set-subpart acset :def/source p source))
    (when doc              (a/set-subpart acset :def/doc p doc))
    (when dispatch-val     (a/set-subpart acset :def/dispatch-val p dispatch-val))
    (when (seq implements) (a/set-subpart acset :def/implements p (set implements)))
    (when (seq refs)       (a/set-subpart acset :def/refs p (set refs)))
    (when (seq arities)    (a/set-subpart acset :def/arities p (set (map long arities))))
    p))

(defn- link-methods!
  "Second pass: set the partial `:def/method-of` hom for every `:defmethod`
   whose multifn is also in the index."
  [acset defs]
  (doseq [{:keys [qname method-of]} defs :when method-of]
    (when-let [src (find-def acset qname)]
      (when-let [tgt (find-def acset method-of)]
        (a/set-subpart acset :def/method-of src tgt)))))

;; ---------------------------------------------------------------------------
;; Public: build / update (per-file reprojection)

(defn index-file!
  "(Re)index an entire `file`'s namespace + defs into `acset` from `source`,
   replacing any Defs previously indexed for that file. Returns the acset."
  [acset file source]
  (doseq [p (a/parts acset :Def)
          :when (= file (a/subpart acset :def/file p))]
    (a/rem-part acset :Def p))
  (let [{:keys [ns defs]} (code/project-source source)
        ns-part (put-ns! acset ns)]
    (doseq [d defs]
      (let [p (put-def! acset ns-part d)]
        (a/set-subpart acset :def/file p file)))
    (link-methods! acset defs))
  acset)

(defn apply-diff!
  "Update the index for `file` to the `new` source. The in-memory index
   reprojects the whole file (cheap); `dvergr.code/diff-source` remains for the
   durable text layer where per-def minimal transactions matter."
  [acset file _old new]
  (index-file! acset file new))

;; ---------------------------------------------------------------------------
;; Public: queries (graph queries over the projection)

(def identity-attr
  "The Def's Identity Attr — pair with another ACSet's identity Attr in
   `katzen.xref/xref` (e.g. KB `:entity/title`) to cross-reference."
  :def/qname)

(defn defs
  "All def qnames (URIs) currently in the index."
  [acset]
  (set (vals (a/subpart-all acset :def/qname))))

(defn def-source
  "The exact source text of the def named `uri`, or nil."
  [acset uri]
  (some->> (find-def acset uri) (a/subpart acset :def/source)))

(defn dependents
  "Qnames of the Defs that reference `uri` (find-references = the inverse image
   of the cardinality-many `:def/refs`)."
  [acset uri]
  (->> (a/incident acset :def/refs uri)
       (keep #(a/subpart acset :def/qname %))
       set))

(defn methods-of
  "Qnames of the `:defmethod` Defs dispatching on the multifn `uri` (the inverse
   image of the `:def/method-of` hom)."
  [acset uri]
  (when-let [multi (find-def acset uri)]
    (->> (a/incident acset :def/method-of multi)
         (keep #(a/subpart acset :def/qname %))
         set)))

(defn implementors
  "Qnames of the Defs (types/records/extends) that implement protocol `uri`
   (the inverse image of the cardinality-many `:def/implements`)."
  [acset uri]
  (->> (a/incident acset :def/implements uri)
       (keep #(a/subpart acset :def/qname %))
       set))

;; ---------------------------------------------------------------------------
;; Public: name resolution + interface facts (the code_query tool backing)

(defn- name-part [qname]
  (let [s (str qname) i (.lastIndexOf s "/")] (if (neg? i) s (subs s (inc i)))))

(defn- ns-part [qname]
  (let [s (str qname) i (.lastIndexOf s "/")] (when (pos? i) (subs s 0 i))))

(defn resolve-name
  "Qnames matching `nm` — the exact qname URI if present, else every Def whose
   short name (the part after the last `/`) equals `nm`. Lets callers pass either
   `demo.core/greet` or just `greet`."
  [acset nm]
  (let [s (str nm) all (defs acset)]
    (if (contains? all s)
      #{s}
      (into #{} (filter #(= s (name-part %)) all)))))

(defn def-info
  "Interface facts for `uri` (or nil if absent): :qname :kind :ns :file :doc
   :private? :arities :source."
  [acset uri]
  (when-let [p (find-def acset uri)]
    (let [g (fn [m] (a/subpart acset m p))]
      (cond-> {:qname uri :ns (ns-part uri)}
        (g :def/kind)         (assoc :kind (g :def/kind))
        (g :def/file)         (assoc :file (g :def/file))
        (g :def/doc)          (assoc :doc (g :def/doc))
        (g :def/source)       (assoc :source (g :def/source))
        (g :def/private)      (assoc :private? true)
        (seq (g :def/arities)) (assoc :arities (sort (g :def/arities)))))))

(defn list-defs
  "Interface facts for every Def, optionally filtered to namespace `ns-str`
   (the qname's ns part) and/or a set of `kinds`. Sorted by qname."
  ([acset] (list-defs acset nil nil))
  ([acset ns-str kinds]
   (->> (defs acset)
        (map #(def-info acset %))
        (filter (fn [{:keys [qname kind]}]
                  (and (or (nil? kinds) (contains? kinds kind))
                       (or (nil? ns-str) (= ns-str (ns-part qname))))))
        (sort-by :qname))))

(defn references-of
  "Callee qnames referenced by `uri` (the def's `:def/refs`) — find-callees."
  [acset uri]
  (when-let [p (find-def acset uri)]
    (or (a/subpart acset :def/refs p) #{})))

(defn search-by-doc
  "Interface facts for Defs whose `:def/doc` contains `term` (case-insensitive)."
  [acset term]
  (let [t (str/lower-case term)]
    (->> (a/subpart-all acset :def/doc)
         (keep (fn [[p doc]]
                 (when (and doc (str/includes? (str/lower-case doc) t))
                   (a/subpart acset :def/qname p))))
         set
         (map #(def-info acset %))
         (sort-by :qname))))
