(ns dvergr.mcp.repl
  "Every op an MCP connection may call, as functions in its room REPL.

   The MCP surface has two halves over one source: tools derived from
   `dvergr.ops/specification`, and `clojure_eval` in a room's SCI sandbox. This
   namespace puts the first into the second, so a client can write one program
   against the API (start a benchmark, poll it, read the Scorecard, compare)
   instead of chaining tool calls. `install!` adds a `dvergr.ops` namespace to
   the connection's REPL session:

     (dvergr.ops/room-list {})
     (dvergr.ops/catalog-benchmark {:workflow \"wiki/v3\" :models [\"…\"]})
     (dvergr.ops/call :scorecard/detail {:room \"r\" :id \"…\"})
     (doc dvergr.ops/job-status)   (dir dvergr.ops)

   one function per op, named like its tool with `-` for `_`, documented from
   the spec and validated against its malli schema; results are the op's plain
   data. A connection's REPL has exactly the authority its tools have: only the
   ops its selection (profile, toolsets, read-only) shows are installed, and
   each selection has a session of its own (`session-actor`), so a read-only
   connection never reaches a function another connection installed. A
   principal other than the local one has sessions of its own, and every op
   runs for the principal of the evaluation that calls it (`*caller*`, which
   the MCP server binds around `clojure_eval`), never for the one that
   installed or defined it: a function that reaches another session (a
   closure kept in a shared place) carries no authority of its own, and one
   called outside an evaluation (a Spin's later step, another thread) is
   refused. An agent's own sandbox in a room gets none of this."
  (:require [clojure.string :as str]
            [dvergr.mcp.surface :as surface]
            [dvergr.ops :as ops]
            [dvergr.principal :as principal]
            [dvergr.sandbox.ns.doc :as doc]
            [malli.core :as m]
            [malli.error :as me]
            [sci.core :as sci]))

(defn session-actor
  "The REPL session of `selection` (for `principal`, default the local one) in
   a room: one per selection, so connections with different authority never
   share definitions (`:mcp/offload`, or `:mcp/sel-<hash>` for a custom one),
   and one per principal besides the local one (`:mcp/p-<hash>`), so tenants
   never share them either."
  ([selection] (session-actor selection principal/local-principal))
  ([{:keys [profile] :as selection} principal]
   (let [authority #(-> (select-keys % [:toolsets :repl-toolsets :read-only?]) (update :read-only? boolean))]
     (cond
       (not (principal/local? principal))
       (keyword "mcp" (str "p-" (Integer/toHexString (hash [(:principal/id principal) (authority selection)]))))

       (= (authority (surface/selection {:profile profile})) (authority selection))
       (keyword "mcp" (name (or profile surface/default-profile)))

       :else
       (keyword "mcp" (str "sel-" (Integer/toHexString (hash (authority selection)))))))))

(def ^:dynamic *caller*
  "`{:principal :selection}` of the MCP evaluation now running (bound by
   `dvergr.mcp.server` around `clojure_eval`), nil elsewhere. The `dvergr.ops`
   functions run for it, and only for it."
  nil)

(defn- fn-name [op] (symbol (str/replace (ops/op->name op) "_" "-")))

(defn visible-ops
  "The ops a REPL of `selection` may call, as `{op spec}`: those its tools
   show, or its `:repl-toolsets` (the `code` profile lists only the REPL)."
  [selection]
  (into (sorted-map)
        (filter (fn [[op {:keys [kind]}]]
                  (surface/visible? (surface/repl-selection selection)
                                    {:dvergr/toolset (surface/tool-toolset (ops/op->name op) op false)
                                     :annotations (surface/op-annotations op kind)})))
        (ops/operations)))

(def ^:private closes-room
  "Ops that close a room: never the one the REPL runs in (closing it would wait
   for this very evaluation)."
  #{:room/delete :room/purge})

(declare visible-ops)

(defn- invoke-checked
  "Validate `args` against `op`'s schema, then run it on `daemon` for the
   evaluation's caller (`*caller*`), when its selection shows `op`."
  [daemon room op args]
  (let [args (or args {})
        schema (ops/malli-schema op)
        {:keys [principal selection]} *caller*]
    (when-not principal
      (throw (ex-info (str (fn-name op) " runs only inside the evaluation of a connection (clojure_eval)")
                      {:type ::no-caller :op op})))
    (when-not (contains? (visible-ops selection) op)
      (throw (ex-info (str "Op " op " is not available on this connection") {:type ::unavailable :op op})))
    (when-not (map? args)
      (throw (ex-info (str (fn-name op) " takes one map of arguments") {:type ::bad-args :op op})))
    (when-not (m/validate schema args)
      (throw (ex-info (str (fn-name op) ": " (pr-str (me/humanize (m/explain schema args))))
                      {:type ::invalid-args :op op :errors (me/humanize (m/explain schema args))})))
    (when (and (closes-room op) room
               (let [target (ops/resolve-room daemon (:room args))]
                 (and target (= (:id target) (:id room)))))
      (throw (ex-info (str (fn-name op) " cannot close the room this REPL runs in; call it from another room")
                      {:type ::closes-own-room :op op})))
    (ops/invoke daemon principal op args)))

(defn ns-map
  "The `dvergr.ops` namespace for `selection` on `daemon`, evaluating in `room`;
   each function runs for the caller of the moment (`*caller*`)."
  [daemon selection room]
  (let [visible (visible-ops selection)]
    (into {'call (doc/documented
                  'call '([op args])
                  (str "Run op `op` (a keyword, e.g. :room/list) with `args`. Ops available here: "
                       (str/join ", " (map str (keys visible))))
                  (fn call [op & [args]]
                    (if (contains? visible op)
                      (invoke-checked daemon room op args)
                      (throw (ex-info (str "Op " op " is not available on this connection")
                                      {:type ::unavailable :op op})))))}
          (for [[op {:keys [doc]}] visible]
            [(fn-name op)
             (doc/documented (fn-name op) '([args])
                             (str doc "\n\nArgs (malli): " (pr-str (ops/malli-schema op)))
                             (fn [& [args]] (invoke-checked daemon room op args)))]))))

(defn install!
  "Add the `dvergr.ops` namespace for `selection` to `sci-ctx` (a room REPL
   session of that selection), its ops run on `daemon`."
  [sci-ctx daemon selection room]
  (sci/add-namespace! sci-ctx 'dvergr.ops (ns-map daemon selection room))
  sci-ctx)
