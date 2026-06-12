(ns dvergr.code
  "The Clojure → categorical-data mapping (programmatic knowledge, L0/L1).
   See doc/programmatic-knowledge.md §7b.

   A source file ⟷ an ORDERED sequence of blocks (top-level forms + trivia),
   each carrying its EXACT source via rewrite-clj — a *strict* round-trip iso
   (`materialize ∘ parse-blocks = identity`, whitespace and comments preserved).
   Layered on each form is a DERIVED index (tools.analyzer): what a `def`
   defines, the vars it references (the signature Σ), and its local bindings
   (the context Γ). Clojure's two binding mechanisms map onto category theory's
   two variable notions — `:local` = Γ, `:var` = Σ — and that index is the
   relational layer behind dependency-closed loading, transitive capability
   closure, structured (by-var) merge, and IDE features (go-to-definition,
   find-references, rename) as graph queries.

   The exact source is the runnable truth (materializes byte-for-byte and feeds
   the SCI sandbox); the index is a derived view that may be represented many
   ways (materialized, reactive, lazily recomputed) — the model is the spec.

   NOTE: resolution here is against the host JVM. For sandboxed *agent* code,
   analyze in the SCI ctx instead, so refs/capabilities reflect the sandbox's
   signature, not the host's (doc §7b)."
  (:require [rewrite-clj.parser :as p]
            [rewrite-clj.node :as n]
            [clojure.string :as str]
            [clojure.tools.analyzer.jvm :as ana]
            [clojure.tools.analyzer.ast :as ast]
            [sci.core :as sci]))

;; ---------------------------------------------------------------------------
;; L0 — the strict iso: source ⟷ ordered blocks (exact text per block)
;; ---------------------------------------------------------------------------

(defn- trivia? [node]
  (or (n/whitespace? node) (n/comment? node)))

(defn parse-blocks
  "Parse `source` into a vector of blocks, one per top-level node (forms and the
   whitespace/comment trivia between them), preserving order and exact text:
     {:kind :form|:trivia, :source <exact string>, :sexpr <form | ::unreadable | nil>}
   `materialize` is its exact inverse."
  [source]
  (mapv (fn [node]
          (let [t? (trivia? node)]
            {:kind   (if t? :trivia :form)
             :source (n/string node)
             :sexpr  (when-not t?
                       (try (n/sexpr node) (catch Throwable _ ::unreadable)))}))
        (n/children (p/parse-string-all source))))

(defn materialize
  "Blocks → source. Exact inverse of `parse-blocks`."
  [blocks]
  (apply str (map :source blocks)))

(defn faithful?
  "True iff `source` round-trips byte-for-byte through parse/materialize."
  [source]
  (= source (materialize (parse-blocks source))))

;; ---------------------------------------------------------------------------
;; L1 — the derived index: defines / Σ-refs (vars) / Γ (locals) per form
;; ---------------------------------------------------------------------------

(defn- var->sym [v]
  (symbol (str (ns-name (.ns ^clojure.lang.Var v))) (name (.sym ^clojure.lang.Var v))))

(defn- local-name [n]
  ;; the analyzer suffixes locals for uniqueness (name__#0); strip for display
  (symbol (str/replace (name n) #"__#\d+$" "")))

(defn- arglist-arities
  "From a seq of arglists (e.g. `([x] [x & xs])`), the fixed arities and whether
   any is variadic."
  [arglists]
  {:arities  (->> arglists
                  (map #(count (take-while (complement #{'&}) %)))
                  distinct sort vec)
   :variadic? (boolean (some #(some #{'&} %) arglists))})

(defn references
  "For a top-level `form`, derive its relational index (the SEMANTIC pass):
     {:defines <sym|nil>      ; the var it defines (if a def/defn/…)
      :vars    [fq-sym …]     ; vars it references (signature Σ — the :ref edges)
      :locals  [sym …]        ; local bindings it introduces (context Γ)
      :arities [n …]          ; fixed arities of the defined fn
      :variadic? <bool>}      ; whether the defined fn is variadic
   Returns {:error msg} if the form can't be analyzed (macro/env issues)."
  [form]
  (try
    (let [ast      (ana/analyze form (ana/empty-env))
          nodes    (ast/nodes ast)
          arglists (:arglists ast)]
      (merge
       {:defines (->> nodes (filter #(= :def (:op %))) (map :name)
                      (map #(symbol (name %))) first)
        :vars    (->> nodes (filter #(= :var (:op %))) (keep :var)
                      (map var->sym) distinct vec)
        :locals  (->> nodes (filter #(= :binding (:op %))) (map :name)
                      (map local-name) distinct vec)}
       (when (seq arglists) (arglist-arities arglists))))
    (catch Throwable e {:error (.getMessage e)})))

;; ---------------------------------------------------------------------------
;; L1 (sandbox-correct) — analyze AGENT code against the SCI ctx, "inside out"
;; ---------------------------------------------------------------------------

(defn- ctx-qname
  "Resolve `sym` against the SCI `ctx`; return its fully-qualified symbol, or nil
   if it doesn't resolve there. Falls back to the symbol's own namespace when the
   resolved var carries none (e.g. host-injected library vars)."
  [ctx sym]
  (when-let [v (sci/resolve ctx sym)]
    (let [m  (meta v)
          ns (or (some-> (:ns m) str) (namespace sym))]
      (symbol ns (name (or (:name m) (symbol (name sym))))))))

(defn references-in-ctx
  "Like `references`, but for SANDBOXED agent code: resolution and
   macroexpansion are delegated to the SCI `ctx` (sci/resolve, sci/eval-form),
   so the `:vars` (and thus capability closure) reflect the AGENT's signature —
   not the host JVM — and the sandbox's own macros are expanded first (the
   DrRacket 'expand-first' rule). The walk is host-side; only the two
   environment-sensitive ops are invoked 'inside out' in the sandbox.

   Returns {:defines <sym|nil> :vars [fq-sym …] :locals [sym …]}. Handles the
   core binding forms (def, let*, loop*, fn*, catch) after macroexpansion;
   unhandled special forms fall through to a structural recursion."
  [ctx form]
  (let [vars    (atom #{})
        defines (atom nil)
        locals  (atom #{})
        mx (fn [f] (try (sci/eval-form ctx (list 'macroexpand (list 'quote f)))
                        (catch Throwable _ f)))]
    (letfn [(w [f env]
              (cond
                (symbol? f)
                (if (contains? env f)
                  (swap! locals conj f)
                  (when-let [q (ctx-qname ctx f)] (swap! vars conj q)))

                (seq? f)
                (let [f (mx f) h (when (seq? f) (first f))]
                  (case h
                    quote nil
                    def   (do (when (symbol? (second f)) (reset! defines (second f)))
                              (when (= 3 (count f)) (w (nth f 2) env)))
                    (let* loop*)
                    (let [binds (partition 2 (second f))
                          env'  (reduce (fn [e [b v]] (w v e) (swap! locals conj b) (conj e b))
                                        env binds)]
                      (doseq [b (drop 2 f)] (w b env')))
                    fn*
                    (let [tail    (if (symbol? (second f)) (drop 2 f) (rest f))
                          arities (if (vector? (first tail)) [tail] tail)]
                      (doseq [ar arities]
                        (let [ps   (remove #{'&} (first ar))
                              env' (into env ps)]
                          (swap! locals into ps)
                          (doseq [b (rest ar)] (w b env')))))
                    catch (let [[_ cls bnd & body] f]
                            (w cls env) (swap! locals conj bnd)
                            (doseq [b body] (w b (conj env bnd))))
                    (doseq [x f] (w x env))))

                (coll? f) (doseq [x f] (w x env))
                :else nil))]
      (w form #{}))
    {:defines @defines :vars (vec @vars) :locals (vec @locals)}))

(defn index-source
  "Source → {:blocks <all blocks, round-tripping> :defs <per-form index>}.
   `:defs` is the code's relational layer: one entry per analyzable top-level
   form, `{:defines :vars :locals :source}`. The union of `:defs` forms a
   var-reference graph (`:defines` → `:vars`) over the whole source."
  [source]
  (let [blocks (parse-blocks source)]
    {:blocks blocks
     :defs (->> blocks
                (filter #(= :form (:kind %)))
                (filter #(seq? (:sexpr %)))
                (mapv (fn [b] (assoc (references (:sexpr b)) :source (:source b)))))}))

;; ---------------------------------------------------------------------------
;; L2 — interface-level per-definition facts (SYNTACTIC pass) + the projector
;;       that merges syntactic + semantic into the katzen clojure-code ACSet.
;; ---------------------------------------------------------------------------

(def ^:private kind-of
  "Top-level form head → schema `:kind`."
  '{def :def, defn :defn, defn- :defn, defmacro :defmacro, defmulti :defmulti,
    defmethod :defmethod, defonce :defonce, deftype :deftype, defrecord :defrecord,
    defprotocol :defprotocol, extend-type :extend, extend-protocol :extend, extend :extend})

(defn- str-qualify
  "Qualify a (possibly simple) symbol to a qname URI string against `ns-sym`.
   Already-namespaced symbols are kept verbatim (alias resolution is a follow-up,
   like intra-project ref resolution)."
  [ns-sym sym]
  (cond (namespace sym) (str sym)
        ns-sym          (str ns-sym "/" sym)
        :else           (str sym)))

(defn- docstring [sexpr]
  (let [x (nth sexpr 2 nil)]
    (when (and (string? x) (> (count sexpr) 3)) x)))

(defn- private-form? [sexpr]
  (boolean (or (= 'defn- (first sexpr)) (:private (meta (second sexpr))))))

(defn def-facts
  "Interface-level SYNTACTIC facts for a top-level def-like `sexpr`, or nil if it
   isn't one. Keys: :kind :defined(sym|nil) :doc :private? :dispatch-val
   :method-of(sym|nil) :implements([sym]). `:defined` drives the qname for the
   def-family; defmethod/extend get a synthetic qname from `project-source`."
  [sexpr]
  (when (seq? sexpr)
    (when-let [kind (kind-of (first sexpr))]
      (case kind
        :defmethod {:kind :defmethod :defined nil
                    :method-of (second sexpr)
                    :dispatch-val (pr-str (nth sexpr 2 nil))}
        :extend    (if (= 'extend-protocol (first sexpr))
                     {:kind :extend :defined nil :implements [(second sexpr)]}
                     {:kind :extend :defined (when (symbol? (second sexpr)) (second sexpr))
                      :implements (->> (drop 2 sexpr) (filter symbol?) vec)})
        (let [nm (second sexpr)]
          (cond-> {:kind kind :defined (when (symbol? nm) nm)
                   :doc (docstring sexpr) :private? (private-form? sexpr)}
            (#{:deftype :defrecord} kind)
            (assoc :implements (->> (drop 3 sexpr) (filter symbol?) vec))))))))

(defn ns-facts
  "Namespace facts from `source`: {:ns sym :requires [ns-sym …] :doc str|nil}, or
   nil if there is no ns form."
  [source]
  (when-let [form (some (fn [b] (let [s (:sexpr b)]
                                  (when (and (seq? s) (= 'ns (first s))) s)))
                        (parse-blocks source))]
    {:ns       (second form)
     :doc      (let [x (nth form 2 nil)] (when (string? x) x))
     :requires (->> (rest form)
                    (filter #(and (seq? %) (= :require (first %))))
                    (mapcat rest)
                    (map (fn [spec] (if (sequential? spec) (first spec) spec)))
                    (filter symbol?) distinct vec)}))

(defn project-source
  "Project `source` into interface-level L2 data for the code ACSet:
     {:ns   {:ns sym :requires [..] :doc ..} | nil
      :defs [{:qname :kind :source :doc :private? :method-of :dispatch-val
              :implements :refs :arities :variadic?} …]}
   Combines the SYNTACTIC pass (`def-facts`) with the SEMANTIC pass (`references`)
   per top-level def-like form. `:method-of`/`:implements` symbols are qualified
   to qname URIs against the file ns."
  [source]
  (let [nsf    (ns-facts source)
        ns-sym (:ns nsf)
        the-ns (when ns-sym (find-ns ns-sym))   ; loaded? → analyze in its alias context
        qual   (fn [sym] (str-qualify ns-sym sym))]
    {:ns nsf
     :defs
     (binding [*ns* (or the-ns *ns*)]           ; so tools.analyzer resolves the ns's aliases
       (->> (parse-blocks source)
            (filter #(= :form (:kind %)))
            (keep (fn [{:keys [sexpr] blk-src :source}]
                    (when-let [{:keys [defined kind method-of implements dispatch-val]
                                :as facts} (def-facts sexpr)]
                      (let [sem   (references sexpr)
                            qname (cond
                                    defined            (qual defined)
                                    (= :defmethod kind) (str (qual method-of) "#" dispatch-val)
                                    :else               (str (qual (or (second sexpr) 'form))
                                                             "@" (hash blk-src)))]
                        (cond-> {:qname qname :kind kind :source blk-src}
                          (:doc facts)         (assoc :doc (:doc facts))
                          (:private? facts)    (assoc :private? true)
                          method-of            (assoc :method-of (qual method-of))
                          dispatch-val         (assoc :dispatch-val dispatch-val)
                          (seq implements)     (assoc :implements (mapv qual implements))
                          (seq (:vars sem))    (assoc :refs (mapv str (:vars sem)))
                          (seq (:arities sem)) (assoc :arities (:arities sem))
                          (:variadic? sem)     (assoc :variadic? true))))))
            vec))}))

;; ---------------------------------------------------------------------------
;; L2 — structured per-definition diff (keep the datahike projection in sync
;;       with the text, efficiently)
;;
;; The git worktree text is AUTHORITATIVE. The structured representation is a
;; per-definition projection of it: {var-name → form source (+ refs)}, the key
;; being the DEFINED VAR SYMBOL — a stable content identity, never an allocated
;; datahike eid. When the text changes we want to transact ONLY the defs that
;; changed, not re-serialize the whole file. `diff-source` computes that
;; minimal change set.
;;
;; This layer does NOT merge or resolve conflicts. Text merges happen in git
;; (or an agent resolving a textual conflict); afterwards the structure is
;; re-derived from the merged text via the same diff. That also sidesteps the
;; substrate eid-collision in sibling datahike forks: we never
;; merge two datahike branches — there is a single writer applying a minimal,
;; name-keyed delta that reflects the current text.
;; ---------------------------------------------------------------------------

(def ^:private def-heads
  '#{def defn defn- defmacro defmulti defonce deftype defrecord defprotocol})

(defn- form-key
  "Stable merge key for a top-level form: the defined var symbol for a def-like
   form, `[:ns]` for the ns form, else a positional `[:form i]` (so non-def
   top-level forms still round-trip, keyed by order)."
  [sexpr i]
  (cond
    (not (seq? sexpr))                         [:form i]
    (= 'ns (first sexpr))                       [:ns]
    (and (def-heads (first sexpr))
         (symbol? (second sexpr)))              (second sexpr)
    :else                                       [:form i]))

(defn- unitize
  "Source → ordered units, each a form with its LEADING trivia attached:
     {:key <merge key> :leading <ws/comments before it> :source <exact form text>
      :sexpr <read form | ::unreadable | nil>}
   Trailing trivia (e.g. the final newline) becomes a `[:trailing]` unit with
   empty `:source`, so `(str leading source)` over the units round-trips."
  [source]
  (loop [bs (parse-blocks source), lead "", i 0, out []]
    (if (empty? bs)
      (cond-> out
        (seq lead) (conj {:key [:trailing] :leading lead :source "" :sexpr nil}))
      (let [{:keys [kind source sexpr]} (first bs)]
        (if (= :trivia kind)
          (recur (rest bs) (str lead source) i out)
          (recur (rest bs) "" (inc i)
                 (conj out {:key   (form-key sexpr i)
                            :leading lead :source source :sexpr sexpr})))))))

(defn diff-source
  "Structured per-definition diff from `old` source to `new` source, keyed on
   the defined var symbol — the minimal change set for updating a structured
   (e.g. datahike) projection without re-transacting unchanged defs.

     {:added    [{:key :source :sexpr} …]        ; defs only in `new`
      :modified [{:key :old :new :sexpr} …]       ; defs whose FORM TEXT changed
      :removed  [key …]}                          ; defs only in `old`

   Compares by exact form text (`:source`), so the projection reflects the text
   state at per-definition granularity: any textual change to a def's form
   re-transacts that one def; untouched defs (and inter-def layout/whitespace,
   which lives in the authoritative text, not the projection) transact nothing.
   Positional `[:form i]` keys (non-def top-level forms) are compared by
   position; a moved one shows as remove+add."
  [old new]
  (let [uo (unitize old)
        un (unitize new)
        mo (into {} (map (juxt :key identity)) uo)
        mn (into {} (map (juxt :key identity)) un)
        real? (fn [k] (not= [:trailing] k))]
    {:added    (->> un
                    (filter #(and (real? (:key %)) (not (contains? mo (:key %)))))
                    (mapv #(select-keys % [:key :source :sexpr])))
     :removed  (->> uo (map :key)
                    (filter #(and (real? %) (not (contains? mn %))))
                    vec)
     :modified (->> un
                    (filter #(and (real? (:key %)) (contains? mo (:key %))))
                    (keep (fn [{:keys [key source sexpr]}]
                            (let [old-src (:source (mo key))]
                              (when (not= old-src source)
                                {:key key :old old-src :new source :sexpr sexpr}))))
                    vec)}))
