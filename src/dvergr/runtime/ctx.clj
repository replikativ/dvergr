(ns dvergr.runtime.ctx
  "The execution-context memory model for dvergr's room/fork world.

   Spindel execution contexts are copy-on-write: state READS fall through
   child→parent, but WRITES never propagate parent←child. dvergr state therefore
   splits into three tiers by that property:

   - **Tier 1 — SHARED / reactive.** The room registry, the rooms-tree + per-room
     message signals, the peer-bus: read by every frontend sitting in the ROOT
     execution context. A Tier-1 WRITE must target the root — a write made from a
     room's (or a fork's) ctx forks a CoW-local copy the UI never sees. Use the
     `shared-*` ops below; they climb to the root regardless of the bound ctx.

   - **Tier 2 — ISOLATION.** A room's yggdrasil composite (msgs/kb/repo systems),
     proposal handles, the chat-ctx signals, bash sessions: these SHOULD CoW-fork
     with the room/fork. Use the plain spindel ops (`ec/swap-state!`, signals
     created under the bound ctx) — they write the currently-bound (room/fork) ctx,
     so a fork gets its own copy and a room gets its own once it runs on its own ctx.

   - **Tier 3 — daemon-global JVM atoms.** Caches/handles that intentionally never
     fork (e.g. the room-turns registry, the message-signal cache). They bypass the
     ctx entirely; nothing here applies to them.

   The whole point: pick the tier deliberately at each call site. `shared-*` says
   \"this is global UI state, root it\"; plain `ec/*` says \"this isolates with the room\"."
  (:require [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.engine.protocols :as rtp]))

(defn root-ctx
  "Walk a ctx's parent chain to the root (the ctx whose `:parent-ctx` is nil).
   Tier-1 shared state must live here so every frontend — in any fork — sees it."
  [ctx]
  (if-let [p (:parent-ctx ctx)] (recur p) ctx))

(defn descendant-context?
  "True when `candidate` is `ancestor` or belongs to its fork lineage.

   A dynamically bound context must not override an object's owning world when
   it is the owner's parent or an unrelated sibling. That would make an async
   coordinator accidentally read the parent projection of child-owned state."
  [candidate ancestor]
  (boolean
   (when (and candidate ancestor)
     (loop [ctx candidate]
       (cond
         (or (identical? ctx ancestor)
             (= (:fork-id ctx) (:fork-id ancestor))) true
         (:parent-ctx ctx) (recur (:parent-ctx ctx))
         :else false)))))

(defn selected-context
  "Select a bound descendant of `owner`, otherwise retain `owner`.

   This is the ambient-world rule for stable handles: child execution may
   refine an owner into its fork, while parent/sibling execution cannot pull a
   child-owned value out of its world."
  [owner]
  (let [bound (when (ec/execution-context-bound?)
                (ec/current-execution-context))]
    (if (descendant-context? bound owner) bound owner)))

(def ^:private sandbox-bindings-path
  "Fork-local bindings used by stable SCI host capabilities.  The binding key
   is stable for the lifetime of one interpreter component; Yggdrasil copies
   the map into a child and the child projection replaces only its data."
  [:dvergr/sandbox-bindings])

(defn install-sandbox-binding!
  "Install serializable world data for `binding-id` in `ctx`.

   Host functions injected into SCI must capture only `owner` + `binding-id`,
   never a Room, connection, filesystem or workspace.  That makes functions
   defined before a SCI fork select the child binding when invoked there."
  [ctx binding-id data]
  (binding [ec/*execution-context* ctx]
    (ec/swap-state! (conj sandbox-bindings-path binding-id)
                    #(merge (or % {}) data)))
  data)

(defn update-sandbox-binding!
  "Update a capability binding in the ambient descendant world of `owner`."
  [owner binding-id f & args]
  (let [ctx (selected-context owner)]
    (binding [ec/*execution-context* ctx]
      (ec/swap-state! (conj sandbox-bindings-path binding-id)
                      #(apply f (or % {}) args)))))

(defn sandbox-binding
  "Read `binding-id` in the ambient descendant world of `owner`."
  [owner binding-id]
  (let [ctx (selected-context owner)]
    (binding [ec/*execution-context* ctx]
      (ec/get-state (conj sandbox-bindings-path binding-id)))))

(defn sandbox-binding-resolver
  "Return a stable zero-argument resolver suitable for an SCI host closure."
  [owner binding-id]
  #(or (sandbox-binding owner binding-id)
       (throw (ex-info "SCI capability has no world binding"
                       {:binding-id binding-id
                        :owner-fork-id (:fork-id owner)}))))

(defn current-root
  "The root of the currently-bound execution context."
  []
  (root-ctx (ec/current-execution-context)))

(defn shared-swap-state!
  "Tier-1 `swap-state!`: ALWAYS targets the ROOT ctx, so the write is visible to
   every frontend regardless of which room/fork ctx is bound. Use for the room
   registry, tree/message signals, peer-bus — anything the UI reactively reads."
  [path f]
  (rtp/swap-state! (current-root) path f))

(defn shared-swap-root!
  "Atomically update the complete Tier-1 root state.

   Use this sparingly for invariants spanning multiple Tier-1 paths, such as
   publishing a Room together with its structural fork edge."
  [f]
  (rtp/swap-state! (current-root) [] f))

(defn shared-get-state
  "Tier-1 read from the ROOT ctx (the authoritative copy)."
  [path]
  (rtp/get-state (current-root) path))
