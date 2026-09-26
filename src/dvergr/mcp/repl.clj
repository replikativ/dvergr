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
   connection never reaches a function another connection installed. An agent's
   own sandbox in a room gets none of this."
  (:require [clojure.string :as str]
            [dvergr.mcp.surface :as surface]
            [dvergr.ops :as ops]
            [dvergr.sandbox.ns.doc :as doc]
            [malli.core :as m]
            [malli.error :as me]
            [sci.core :as sci]))

(defn session-actor
  "The REPL session of `selection` in a room: one per selection, so connections
   with different authority never share definitions (`:mcp/offload`, or
   `:mcp/sel-<hash>` for a custom one)."
  [{:keys [profile] :as selection}]
  (let [authority #(-> (select-keys % [:toolsets :read-only?]) (update :read-only? boolean))]
    (if (= (authority (surface/selection {:profile profile})) (authority selection))
      (keyword "mcp" (name (or profile surface/default-profile)))
      (keyword "mcp" (str "sel-" (Integer/toHexString (hash (authority selection))))))))

(defn- fn-name [op] (symbol (str/replace (ops/op->name op) "_" "-")))

(defn visible-ops
  "The ops `selection` shows, as `{op spec}`."
  [selection]
  (into (sorted-map)
        (filter (fn [[op {:keys [kind]}]]
                  (surface/visible? selection {:dvergr/toolset (surface/tool-toolset (ops/op->name op) op false)
                                               :annotations (surface/op-annotations op kind)})))
        ops/specification))

(def ^:private closes-room
  "Ops that close a room: never the one the REPL runs in (closing it would wait
   for this very evaluation)."
  #{:room/delete :room/purge})

(defn- invoke-checked
  "Validate `args` against `op`'s schema, then run it on `daemon`."
  [daemon room op args]
  (let [args (or args {})
        schema (ops/malli-schema op)]
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
    (ops/invoke daemon op args)))

(defn ns-map
  "The `dvergr.ops` namespace for `selection` on `daemon`, evaluating in `room`."
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
