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

(defn shared-get-state
  "Tier-1 read from the ROOT ctx (the authoritative copy)."
  [path]
  (rtp/get-state (current-root) path))
