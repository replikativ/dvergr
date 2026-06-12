# dvergr state model

Where every kind of state lives, whether it has **value semantics** (CoW-forkable
via the spindel execution context + yggdrasil workspace), and how this behaves when
dvergr is embedded as a library. Companion to `doc/configuration.md`.

## Three tiers

### Tier 1 — Value-semantic / forkable
Lives in the **spindel execution context** and the **yggdrasil composite workspace**
(`[:external-refs ::workspace]`). Forking a room with `:isolation :ctx` does
`ctx/fork-context`, which copy-on-write copies the context **and** branches the
workspace as a unit; `merge-to-parent!` collapses the branch back.

- **The chat Datahike DB** (one DB, system `"dvergr-chat-db"`): messages, rooms
  (`:chat/*`), **actors/agents** (`:actor/*`), tool-uses, **ledger** (budget),
  **knowledge** (`:entity/*`), code, tasks, proposals. Branches on fork.
- **Git worktrees**: the agent's file edits; branch on fork.
- **In-context signals**: per-chat messages/budget/status signals, the live room
  registry (`[:dvergr/rooms]`), processes, etc. CoW-copied with the context.

Almost all durable, meaningful state is here — so it gets value semantics for free.

### Tier 2 — Filesystem, NOT value-semantic
Everything under `.dvergr/` (gitignored; `config.local.edn` too). The state root
resolves `paths/set-home!` → `DVERGR_HOME` env → `./.dvergr` (project-relative).

- Datahike **store bytes** (`.dvergr/db/`), per-sim DBs, intake transcripts, the log.
  The DB's *content* is Tier 1 (branchable); its *store directory* is just where bytes
  land. (Fulltext search is moving to a native Datahike `:scriptum` secondary index
  maintained inside the DB store — no separate Lucene directory.)
  (Agent **prompts** used to live here as `.dvergr/agents/<id>.md`; they now live in
  the actor row — Tier 1. See "Personas" below.)

### Tier 3 — Transient daemon (in-memory, rebuilt on restart, never forked)
The `Daemon` record fields (`config`, `execution-ctx`, `discourse-room`,
`telegram-ch`, `http-server`, `status`), `current-daemon`, the `room-turns` cancel
registry (now in `dvergr.agent.turn`), live participants/spins, and the stats/diff
caches. **All a projection of Tiers 1–2** —
agents/rooms rehydrate from Datahike actor rows + the registry, budget from the
ledger, worktrees re-open. This is the "fine to be stateful" part: losing it loses
nothing durable.

## The chat-state `ChatContext` (per `[room, agent]`)

The working state an agent reasons with is one cached `ChatContext` per room-agent
pair (`dvergr.agent.room-context/ensure-ctx!`) — itself a **fold of Tier 1**:

- `messages-signal` — deltaable vector of the conversation (in-memory projection)
- `budget-signal` — `{:total :used :by-type}` token accounting
- `status-signal` — `:active` / `:paused` / `:completed`
- `db-conn` — the `dvergr-chat-db` connection (in a fork: the *branched* one)
- `sci-ctx` — the agent's **SCI sandbox** (its `clojure_eval` world)

The agent's own ctx is **`:durable? false`** — signal-only. The room store's
bus→store listener is the *lone* durable writer for the conversation. So the durable
chat state **is** the Datahike DB (Tier 1); the `ChatContext` is its reactive fold
plus the per-session sandbox.

## What a `:isolation :ctx` fork actually forks

`fork-room` calls `ctx/fork-context`, which CoW-copies the execution context and
branches **every `[:external-refs]` yggdrasil system as a unit**:

- **Datahike `dvergr-chat-db`** → branched — messages, KB, ledger, proposals, all
  isolated until merge. *This is the whole chat state, forked through yggdrasil.*
- **git worktree** → a fresh worktree; the agent's file edits are isolated.
- **muschel shell session** → its env-atom is CoW-forked alongside, so a worker's
  `cd` / shell state can't leak to the parent.
- **per-agent SCI sandbox** → on its first turn in the fork, `ensure-ctx!` builds a
  fresh `ChatContext` whose `sci-ctx` is a `sandbox/fork-for-session` fork —
  **transient and isolated per fork, never shared** with the parent or siblings
  (`(def x …)` in one fork is invisible to others).

The fork's conversation is **seeded from the branched store**, so the agent sees
inherited (pre-fork) messages *plus* its own — exactly what the UI shows.
`merge-room` → `yggdrasil/merge-to-parent!` lands the Datahike + git branches
atomically; `discard` → `discard-from-parent!` deletes them. A fork is thus a fully
**substrate-isolated world** you can review (`diff`) before committing (`merge!`).

## Personas / agent config — managed, not files

An agent's model/provider/skills/budget/description live in its **actor row**
(Tier 1), and so does the system prompt (`:actor/system-prompt`), edited only through
the API (`dvergr.agent.ops/update-agent!`, the web textarea, the REPL).

Rationale: agents should mutate dvergr through **controlled surfaces** (the REPL, our
git-backed bash, the API) — not by scribbling unmanaged files outside the substrate.
Putting the prompt in the actor row makes it:
- **value-semantic** — a prompt edit inside a `:ctx` fork stays on that branch;
- **versioned** — it lives in the Datahike branch history with everything else;
- **uniform** — one source of truth for all agent settings.

The built-in `resources/agents/<id>.md` files remain as **read-only seeds/defaults**
shipped on the classpath (and tracked in dvergr's own git). `persona/resolve-prompt`
reads the actor row first, falling back to the built-in resource.

Editing surfaces: the web config textarea and `ops/update-agent!`/the REPL write the
row directly; the TUI's agent-config view offers `p` to edit the prompt in `$EDITOR`
(dumped to a throwaway tmpfile, saved back to the DB) via spindel-tui's
`:with-suspended`. The tmpfile is transient editing scratch, never state.

## As a library

No client/server boilerplate; the host owns its state root and config:
- **State root**: `paths/set-home!` / `DVERGR_HOME` / `./.dvergr`.
- **Config**: `config.local.edn` (or `$DVERGR_CONFIG`), loaded once by
  `dvergr.substrate.config/load-config`; it is a **seed** — after first boot the
  Datahike actor rows are authoritative.
- **Boot**: `(config/load-config)` → `(daemon/start! (config/daemon-config))`.
- **APIs**: `dvergr.core` (room/participant/ask/fork facade) and
  `dvergr.clients.client` — the nREPL client (`c/start!` to attach or boot a lite
  daemon; `c/room`/`c/spawn` for a Room handle; `c/fork`/`c/merge!`).

The host owns `.dvergr/`, `config.local.edn`, and any agent overrides; the library
owns the rest, and all durable meaning is in the forkable Datahike DB — the property
you want from a dependency.
