(ns dvergr.sandbox.ns.doc
  "Attach `:doc`/`:arglists` to the fns dvergr injects into the SCI sandbox.

   The discovery machinery an agent has — `(clojure.repl/doc …)`, `dir`,
   `apropos`, `find-doc`, `(sandbox/doc 'ns)` — reads metadata off the injected
   VALUE. `sci/copy-var` carries a real var's metadata across, which is why every
   borrowed namespace (clojure.string, cheshire, babashka.fs …) documents itself.
   But dvergr's own vocabulary is injected as bare `(fn …)` closures, and a raw
   closure carries no metadata, so those same tools answered:

     (clojure.repl/doc dvergr.room/kb-search)
     ;=> dvergr.room/kb-search
     ;     ; arity unknown — injected as a bare fn
     ;     ; no docstring

   and `(find-doc \"knowledge\")` reported that nothing in the sandbox matches —
   for a capability the sandbox very much has. An agent's only way to learn a
   signature was to call the fn and read the error, and its only way to learn a
   capability existed was to be told in the prompt. `dvergr.scheduler` was fixed
   this way once (a local `documented` helper); this ns is that helper, shared,
   so the rest of the vocabulary can follow and a test can hold the line.

   Usage — keep the wiring as it is and hang a doc table off it:

     (sci/add-namespace! sci-ctx 'dvergr.agents
       (doc/with-docs
         {'list    list-fn
          'lookup  lookup-fn}
         '{list   [([]) \"Every agent currently online in this daemon.\"]
           lookup [([id]) \"The full entry for one agent id, or nil.\"]}))

   Docs live next to the wiring on purpose: the fn list stays auto-derived from
   what is actually injected (drift-free), and a var that gains a doc gains it
   in the same place a reader is already looking."
  (:require [clojure.string :as str]))

(defn documented
  "Return `f` with `:name`/`:arglists`/`:doc` metadata attached.

   Only values that can carry metadata are touched — a datahike conn, `nil`, or
   any other non-`IObj` injected value is returned unchanged rather than
   throwing, so a doc table may mention a var whose value is not a fn without
   the caller special-casing it."
  [sym arglists doc f]
  (if (instance? clojure.lang.IObj f)
    (vary-meta f merge (cond-> {:name sym}
                         arglists (assoc :arglists arglists)
                         doc      (assoc :doc doc)))
    f))

(defn with-docs
  "Attach metadata from a doc table to an SCI namespace map.

   `ns-map` is the map you would pass to `sci/add-namespace!`.
   `docs` is `{sym [arglists doc]}` — typically quoted as a whole, so the
   arglists read like the ones on a real `defn`.

   Vars absent from `docs` pass through untouched (a var that legitimately has
   no signature to state, e.g. `*kb*`, needs no entry). Entries in `docs` that
   name a var NOT in `ns-map` throw: a doc table that has drifted away from the
   wiring is a bug, and a silent no-op is exactly how it would rot unnoticed."
  [ns-map docs]
  (let [unknown (remove (set (keys ns-map)) (keys docs))]
    (when (seq unknown)
      (throw (ex-info (str "doc table names vars that are not injected: "
                           (str/join ", " (sort (map str unknown))))
                      {:unknown (vec (sort unknown))
                       :injected (vec (sort (map str (keys ns-map))))}))))
  (reduce-kv (fn [m sym [arglists doc]]
               (cond-> m
                 (contains? m sym) (update sym #(documented sym arglists doc %))))
             ns-map
             docs))

(defn from-var
  "Document `f` by harvesting `:arglists`/`:doc` off the var it wraps.

   Most injected fns are thin wrappers that partially apply a host var — e.g.
   `(fn [id] (lookup-fn conn id))` over `dvergr.orchestration.tasks/lookup`,
   whose own arglist is `([conn id])`. `drop-args` says how many leading
   parameters the wrapper supplies, so the sandbox sees `([id])`: the signature
   an agent actually calls, derived from the source rather than restated (and so
   unable to drift from it).

   `doc` overrides the source docstring when the source has none, or when the
   sandbox-facing meaning differs from the host-facing one."
  [sym src-var f & {:keys [drop-args doc]
                    :or   {drop-args 0}}]
  (let [m        (meta src-var)
        arglists (some->> (:arglists m)
                          (map #(vec (drop drop-args %)))
                          (apply list))]
    (documented sym arglists (or doc (:doc m)) f)))
