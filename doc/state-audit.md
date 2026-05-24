# Forkable-state audit

A `defonce` atom is process-global: every fork of a spindel execution
context shares it, every test mutating it leaves residue for the next,
and there is no copy-on-write across fork boundaries.

spindel already exposes the right substrate:

```clojure
(require '[org.replikativ.spindel.engine.core :as ec])

(ec/swap-state! [:dvergr/foo k] (fnil conj #{}) v)
(ec/get-state   [:dvergr/foo k])
```

`fork-context` copies the parent's state into an overlay backend
(O(1)); writes go to the fork, reads fall through to the parent. So
state held under `[:dvergr/...]` keys on the current context forks
correctly with no extra plumbing.

This audit categorises every `defonce` atom under `src/dvergr/` by
whether it should remain process-singleton or move to ctx-state.

## A. Process-singleton — keep as `defonce`

Static config and lookup tables. Single source of truth, populated
once at startup, read everywhere. Forking would not change semantics.

| File | Atom | Notes |
|---|---|---|
| `model/registry.clj:152,297,413` | `default-models` / config / aliases | model metadata table |
| `model/providers.clj:15` | `providers` | provider instances (HTTP clients) |
| `rooms.clj:28` | `conn-a` | shared datahike connection |
| `stats.clj:31` | `conn-a` | shared datahike connection |
| `mcp/server.clj:43` | `server-db-conn` | shared datahike connection |
| `search.clj:33-35` | `writer-a`, `commit-scheduler`, `pending-count` | single Lucene IndexWriter per JVM |

## B. Per-process resource handle — keep as `defonce`

OS-level resources (sockets, threads, schedulers). One process, one
handle. Forking a ctx must *not* duplicate these.

| File | Atom | Notes |
|---|---|---|
| `daemon.clj:95` | `current-daemon` | the running daemon |
| `web/server.clj:26` | `server-state` | one HTTP server |
| `mcp/server.clj:451,362,452` | `server-state`, `connected-send-fns`, `connections` | one MCP server |
| `distributed.clj:41` | `peer-state` | one kabel peer |
| `calendar/dispatcher.clj:17` | `running?` | dispatch-loop flag |
| `web/server.clj:27` | `agent-handlers` | handler map for the one HTTP server |

## C. Runtime cache / live handles — **move to `ec/swap-state!`**

These hold per-conversation, per-fork, per-test live state. Today
they are process-global and they leak across forks, tests, and
sidecars. This is the source of the `:no-live-context` vs
`:not-found` test flake.

### C1. `dvergr.proposals/result-cache` (proposals.clj:56) — PRIMARY

```clojure
(defonce ^:private result-cache (atom {}))   ; proposal-id → handle
```

Holds live `{:fork :reply :room}` handles for pending proposals. A
proposal made under a forked ctx writes into the parent's cache;
two test runs in the same JVM see each other's stale entries.

**Fix:**

```clojure
;; In propose!:
(ec/swap-state! [:dvergr/proposals proposal-id]
                (constantly {:fork f :reply r :room r2}))

;; In get-cached-handle:
(defn get-cached-handle [proposal-id]
  (ec/get-state [:dvergr/proposals proposal-id]))

;; In accept!/reject!:
(ec/swap-state! [:dvergr/proposals] dissoc proposal-id)
```

The proposal's metadata still persists to Datahike; the live handle
lives on the ctx and dies with it.

### C2. `dvergr.sessions/sessions` (sessions.clj:23)

```clojure
(defonce sessions (atom {}))   ; [chat-id agent-id] → session
```

Conversation/chat sessions are fundamentally per-ctx state. A
sidecar agent and the daemon both writing to this atom is a bug
waiting to happen.

**Fix:** `[:dvergr/sessions [chat-id agent-id]]` on the ctx. The
daemon registers sessions under its own root ctx; sidecars get
their own.

### C3. `dvergr.registry/registry` (registry.clj:23)

```clojure
(defonce registry (atom {}))   ; agent-id → {:agent :status :tags …}
```

The agent registry was modelled as the daemon's. With sidecars and
proposals each running their own agent set, registrations from one
must not be visible to another.

**Fix:** `[:dvergr/agents agent-id]` on the ctx. `register!` writes
to the current ctx; `lookup` reads. `register!`-time idempotency
(deduplication) is by entry, not by global key.

### C4. `dvergr.stats/cache-a` (stats.clj:32)

```clojure
(defonce ^:private cache-a (atom {}))   ; agent-id → stat-map
```

Per-agent stats cache. A fork that reruns an agent should see its
own stats, not inherit the parent's.

**Fix:** `[:dvergr/stats agent-id]` on the ctx. `conn-a` stays
singleton.

### C5. `dvergr.scheduler.core/active-schedules` (scheduler/core.clj:36)

```clojure
(defonce active-schedules (atom {}))   ; schedule-id → {:spin :config}
```

Holds *live spin handles* for running schedules. A fork that
spawns its own schedule cannot mutate the parent's, and cleanup
of the fork should reap its schedules without touching the
parent.

**Fix:** `[:dvergr/schedules schedule-id]` on the ctx.

### C6. `dvergr.rooms.bus/bus-state` (rooms/bus.clj:29)

```clojure
(defonce ^:private bus-state (atom nil))   ; {:subscribers (atom {}) :ctx ec}
```

Double-nested atom, and the `:ctx` field caches the very thing the
caller can already retrieve with `(ec/current-execution-context)`.

**Fix:** `[:dvergr/rooms-bus/subscribers slug]` on the ctx, drop
`bus-state` entirely. `init!` becomes a no-op.

## D. Reconsider / lower priority

| File | Atom | Notes |
|---|---|---|
| `security/allowlist.clj:18` | `allowed-users` | Security policy. Today: process-wide. Argument for ctx-scoping: tests should be able to fork a ctx and tighten/relax the allowlist without polluting siblings. Argument against: a single security boundary is easier to audit. Recommend: keep as `defonce`, document. |
| `channels/core.clj:34` | `channels` | Channel instances are tied to OS resources (Telegram polling, sockets). Singleton per process is correct, but document this and reject `connect!` if a daemon-bound channel is already mounted. |

## E. Out of scope

| File | Notes |
|---|---|
| `sci/impl/clojure_test.clj:261` | Vendored from babashka, leave alone |

## Migration order (recommended)

1. **C1 proposals/result-cache** — directly fixes the observed flake. Small, isolated change. Validate by running the proposals test suite in a JVM that already ran another dvergr test.
2. **C2 sessions** — second-most likely to leak across tests (telegram dispatch fixture).
3. **C6 rooms.bus subscribers** — simplest cleanup; drops `init!` ceremony.
4. **C3 registry**, **C4 stats**, **C5 schedules** — bundle once the substrate is proven on (1)-(3).

After each migration, run `clj -M:test` twice in the same JVM to
catch residue.

## Pattern

Where each migration produces this skeleton:

```clojure
;; Before
(defonce ^:private foo-cache (atom {}))
(defn get-foo [k] (get @foo-cache k))
(defn put-foo! [k v] (swap! foo-cache assoc k v))

;; After
(require '[org.replikativ.spindel.engine.core :as ec])

(defn get-foo [k] (ec/get-state [:dvergr/foo k]))
(defn put-foo! [k v] (ec/swap-state! [:dvergr/foo k] (constantly v)))
```

Forking comes free: `(sp/fork-context parent-ctx)` overlays
`[:dvergr/foo …]` so the child sees the parent's entries but its
writes do not bleed back.
