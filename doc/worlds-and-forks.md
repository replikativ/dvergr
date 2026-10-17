# Worlds, forks and state

Status: built. This page is where every kind of state lives and whether it forks
(first part), the fork contract shared by Yggdrasil, Spindel, Dvergr and Simmis
(second part), and how Run worlds, savepoints and budgets are instances of the
spindel primitives (third part). The on-disk layout of a home, moved and copied
homes, and the headless daemon are in [homes.md](homes.md). Design history: the
use cases and migration sequence of the fork contract
([design/fork-and-world-migration.md](design/fork-and-world-migration.md)) and
the plan that unified worlds, savepoints and budgets
([design/unified-worlds.md](design/unified-worlds.md)). Names marked
*provisional* describe semantics, not necessarily public function names.

## Where state lives

A fork copies what is in an execution context (copy-on-write) and branches the
Yggdrasil systems registered in it; everything else is shared or rebuilt. So
each kind of state is in one of four places (`dvergr.runtime.ctx` describes the
same split from the code's side).

### Forkable: a room's own context and systems

Each room runs on its **own execution context**, a fork of the daemon's root
with `:systems :none` that registers only that room's systems
(`dvergr.system.rooms`, `fork-room-ctx`). Forking the room with `:isolation
:ctx` creates one canonical Spindel `ForkHandle`, which copy-on-write copies
the context **and** branches those systems as a unit; settling that handle
merges or discards it. A room's systems:

- **messages store** (`:msgs`, a Datahike DB of its own): the conversation,
  the room's Runs, certified Attempt indexes and the blobs of their payloads,
  and the room's resource governance (Kontor wallets). Its fulltext
  index is a native Datahike `:scriptum` secondary index whose files are a
  `-ft` sibling of the store directory (`dvergr.search.secondary`).
- **knowledge base** (`:kb`, Datahike): `:entity/*`, with its own `-ft`
  fulltext index.
- **repository** (`:repo`): a Geschichte workspace, a Datahike branch with a
  muschel virtual filesystem, not a native checkout. The agent's file edits
  land here.
- optionally a Kontor **business book** (`:book`, settles by replay) and the
  **data DBs** an agent created (`:data`).
- **in-context signals**: per-chat messages/budget/status signals, the room's
  Yggdrasil composite, proposal handles, shell sessions.

A room may also attach another room's KB or repository (a `:grant` in the
system DB, `:read` or `:read-write`); the live attachment is a registration in
the room's composite. The grant's permission decides what the room may do with
it: sandbox code transacts a database only on a grant that permits `:write`
(`dvergr.system.rooms/grant-permits?`), the room's GC (`dvergr.room/gc!`)
skips the stores it may not write, and a merge that would change one (a fork
wrote it before the grant was downgraded or detached) is refused. A host that writes
grants with its own words (simmis adds `:merge`) answers for them through
`dvergr.system.rooms/set-grant-permits!`, a `(fn [permission action])` that
dvergr asks with `:write`; without it `:owner` and `:read-write` write.

Introducing a protected system that exists only in the child also requires a
current write grant, even when its database has no content changes. Ordinary
Room merges recheck after quiescence and inside the merge guard's substrate
callback, not only at early preflight. Missing deltas for protected mergeable
systems refuse settlement; unchanged shared non-mergeable systems and ordinary
unregistered extensions keep their existing behavior. This is a fresh check,
not an atomic transaction with an independent grant writer.

### Shared, durable: the system DB

One Datahike DB per home (`dvergr.system.db`), not per room and not forked:
parties (humans and agents, `:party/*`, with their actor rows), the registry
of rooms (`:room/*`), of systems and their store scopes (`:system/*`),
assignments and grants. The daemon rehydrates the room registry and every
room's context from it at boot (`dvergr.rooms/hydrate-registry!`,
`dvergr.system.rooms/hydrate-rooms!`). A `:ctx` fork that creates a data DB defers its system-DB
grant (reconciled on merge, dropped on discard), so a fork's DB does not
resurrect on restart.

### Shared, reactive: the root context

The live room registry (`[:dvergr/rooms]`), the rooms tree and the peer bus
live in the **root** context and are read by every frontend; writes to them
climb to the root whatever context is bound (`dvergr.runtime.ctx`'s `shared-*`
ops), so a fork never gets a private copy the UI cannot see.

### Files, and transient daemon state

The bytes of all those stores, the sandbox workspace clone, blobs, mail, the
MCP token and the log live in the home directory ([homes.md](homes.md)); a
store's *content* forks, its directory is just where the bytes land.

In memory only, rebuilt on restart and never forked: the `Daemon` record
(`config`, `execution-ctx`, `discourse-room`, `telegram-ch`, `http-server`,
`status`; `dvergr.orchestration.daemon`), `current-daemon`, the live Run
registry (the turn API in `dvergr.agent.turn` is a projection over it), the
map of room contexts, live participants and spins, and the stats/diff caches.
All of it is a projection of the durable state: agents and rooms rehydrate
from actor rows and the registry, budgets from the ledger, Geschichte
workspaces re-open. Losing it loses nothing durable.

## The chat-state `ChatContext` (per `[room, agent]`)

The working state an agent reasons with is one cached `ChatContext` per
room-agent pair (`dvergr.agent.room-context/ensure-ctx!`), a fold of the
forkable state:

- `messages-signal`: deltaable vector of the conversation (in-memory projection)
- `budget-signal`: `{:total :used :by-type}` token accounting
- `status-signal`: `:active` / `:paused` / `:completed`
- `db-conn`: the Datahike connection it persists to (in a fork: the branched one)
- `sci-component`: a stable Spindel component reference selecting the agent's
  **SCI sandbox** (`clojure_eval` world) in the current execution context

An agent's ChatContext in a room is **`:durable? false`**: its messages stay
signals. The room's bus persists every message itself, inside `post!` and
before the message is visible anywhere (`dvergr.room.store`, its
`:durable-append!` hook; a store failure fails the post). So the durable chat
state **is** the room's messages store; the `ChatContext` is its reactive fold
plus the per-session sandbox.

`ChatContext` exposes no raw `:sci-ctx` field. Embedders call
`dvergr.chat.context/sci-context-in` with the intended execution context, or
`sci-context` while that context is bound. `:sci-component` is an opaque
stable reference and must not be passed to SCI directly. Tool contexts still
use `:sci-ctx`; supply the interpreter returned by one of those resolvers.

## What a `:isolation :ctx` fork forks

`dvergr.discourse/fork-room` calls `spindel.yggdrasil/fork!`, retains its
process-local `ForkHandle`, and branches every registered system of the room as
a unit:

- **the messages store** → branched: messages, Runs, Attempt indexes, all
  isolated until merge. The fork's own store wraps the branched
  connection under the parent's conversation id, so the fork's messages are
  the same logical conversation on a Datahike branch and a merge collapses
  them natively. Exact Attempt payloads are immutable blobs in the room's own
  Datahike store (`dvergr.artifact/datahike-store`, tracked by its GC); only a
  branch's typed reference makes one part of that world. Older string
  references are read from the legacy global blob CAS.
- **the KB** → branched.
- **the Geschichte workspace** → a branch of the room's repository; the
  agent's file edits are isolated.
- **muschel shell session** → its env-atom is CoW-forked alongside, so a
  worker's `cd` / shell state can't leak to the parent.
- **per-agent SCI sandbox** → the SCI interpreter is a Spindel world
  component. Forking the execution context forks its heap (vars, atoms,
  bindings, and continuations) from the effective parent state. The child
  reuses the stable `ChatContext` identity while resolving an independent
  interpreter; `(def x …)` in one fork is invisible to its parent and
  siblings. Discard unregisters the child realization rather than merging
  transient interpreter state.

Resources are the exception: resource facts live in the control Room's store,
and a work-world fork never copies or settles that authority. A child receives
resources only through one governed Kontor transfer from its parent's wallet
(`dvergr.resource`).

Injected host capabilities follow a stricter rule than ordinary SCI values.
They never close over a Room, Datahike connection, Geschichte workspace, or
filesystem. Each interpreter component owns a stable capability identity whose
serializable binding lives in the Spindel execution context. A copied function
therefore resolves that identity in the currently selected descendant world at
call time. This is why a helper defined *before* a fork cannot write back into
its parent even though the helper's SCI function value itself was inherited.
Namespace refresh after projection exposes new APIs; it is not the isolation
mechanism.

The fork's conversation is **seeded from the branched store**, so the agent
sees inherited (pre-fork) messages *plus* its own, exactly what the UI shows.
`merge-room` and `discard` settle that same handle exactly once. A fork is thus
a fully **substrate-isolated world** you can review (`diff`) before adopting or
discarding. The live handle is process-local; `dvergr.discourse/fork-descriptor`
is the portable projection used by Runs, proposals, UIs, and audit state.

## Personas and agent config: managed, not files

An agent's model/provider/skills/budget/description live in its **actor row**
in the system DB, and so does the system prompt (`:actor/system-prompt`),
edited only through the API (`dvergr.agent.ops/update-agent!`, the web
textarea, the REPL). Agents mutate dvergr through controlled surfaces (the
REPL, the git-backed shell, the API), not by writing unmanaged files outside
the substrate; one row is the single source of truth for an agent's settings.

The built-in `resources/agents/<id>.md` files remain read-only seeds and
defaults on the classpath. `dvergr.agent.persona/resolve-prompt` reads the
actor row first, falling back to the built-in definition's body.

Editing surfaces: the web config textarea and `ops/update-agent!`/the REPL
write the row directly; the TUI's agent-config view offers `p` to edit the
prompt in `$EDITOR` (dumped to a throwaway tmpfile, saved back to the DB) via
spindel-tui's `:with-suspended`. The tmpfile is transient editing scratch,
never state.

## As a library

No client/server boilerplate; the host owns its state root and config:

- **State root** (a dvergr *home*): `paths/set-home!` / `DVERGR_HOME` /
  `./.dvergr`. One home per process; a moved home is adopted, a copied one
  refused; to try something on a home's data, fork a room instead
  ([homes.md](homes.md)).
- **Config**: `config.local.edn` (or `$DVERGR_CONFIG`), loaded once by
  `dvergr.substrate.config/load-config`; it is a **seed**: after first boot
  the actor rows are authoritative.
- **Boot**: `(config/load-config)` → `(daemon/start! (config/daemon-config))`.
- **APIs**: `dvergr.core` (room/participant/ask/fork facade) and
  `dvergr.clients.client`, the REPL surface over a running (or booted-lite)
  daemon (`c/start!`; `c/room`/`c/spawn` for a Room handle; `c/fork`/`c/merge!`).

The host owns the home directory, `config.local.edn`, and any agent overrides;
the library owns the rest, and all durable meaning is in forkable stores and
the system DB.

# The fork contract

## Objective

There must be one implementation of state forking while still allowing each
layer to add its own meaning:

```text
Simmis Proposal / ForkSet     durable governance and review
             │ adopts or references
Dvergr RunWorld / RoomBranch  discourse, execution policy, quiescence
             │ wraps
Spindel ForkHandle            one fork of a reactive execution world
             │ automatically forks
Yggdrasil systems             Datahike, Geschichte, CRDTs, other substrates
```

Higher layers may project, govern, or own a fork. They must not independently
implement substrate branches, fork identity, or settlement.

## Canonical vocabulary

### Yggdrasil: one system

- A **snapshot** is an immutable identity for one system state.
- A **branch** is a durable named reference to snapshots.
- An **overlay** is a transient, abandonable isolated workspace over a parent
  system, with an observation mode such as `:frozen` or `:following`.
- A **system merge** combines one lineage into another according to that
  adapter's semantics.

Yggdrasil does not own Rooms, Runs, agents, proposals, or execution graphs.
Geschichte workspaces and Datahike branches are implementations of this layer,
not additional orchestration worlds.

### Spindel: one executing world

An **execution world** is a Spindel `ExecutionContext` containing:

- the reactive graph and its values;
- fork-local queues, continuations, timers, and subscriptions;
- context bindings and simulation metadata;
- Yggdrasil systems registered as forkable signals.

A **world fork** is the existing Spindel Yggdrasil `ForkHandle`. Forking the
context creates a CoW reactive overlay and automatically forks each selected
registered Yggdrasil system. Nested forks always fork the effective state of
their immediate parent.

The live handle is a process-local capability. A portable **ForkDescriptor**
(*provisional*) records the durable basis of the fork:

```clojure
{:fork/id       fork-id
 :fork/parent   parent-world-id
 :fork/purpose  :run|:workroom|:particle|:proposal|:experiment
 :fork/systems
 {system-id {:base-snapshot snapshot-id
             :branch        branch-id
             :head          snapshot-id
             :mode          :frozen|:following
             :rights        :write}}
 :fork/owner    owner-id}
```

The descriptor schema should extend or reuse Spindel's existing distributed
workspace checkout/fork descriptor rather than introduce a competing manifest.

### Dvergr: discourse and execution policy

A **Room** is a long-lived social and work environment. It is not a synonym for
a Spindel fork.

A **discourse branch** is an alternate message/participant projection used for
an imagined conversation. It need not fork state.

A **RoomBranch** (*provisional*) is a Room facade over a Spindel world fork. It
adds a bus, conversation projection, participant policy, registration, and
control-plane events. It does not own a second substrate fork.

A **Run** is the durable identity of one causally bounded execution. It is not a
fork. A Run may execute in a private world fork, share an ambient world, or
produce no substrate changes.

A **RunWorld** is a Run's lease and policy over one world fork. As built
(`dvergr.agent.world`):

```clojure
(defrecord RunWorld [id parent work policy settlement])
;; id          the work Room's id
;; parent      the Room it forks
;; work        the work Room, opened by `d/fork-room` with :isolation :ctx
;; policy      :automatic | :review | :discard | :deferred
;; settlement  an atom caching the one settlement decision
```

The Run's id is in the work Room's meta (`:run-id`, beside `:run-world?`,
`:record-room-id` and `:settlement-policy`) and in its context state
(`[:dvergr/run-id]`, for the resource authority). Its fork identity, parent,
child context, and live settlement capability come from the canonical handle
`ygg/fork!` returned, which the work Room keeps (`fork-handle` in
`dvergr.discourse`), and are not recreated in Dvergr. `settle!` decides
once: failure or cancellation discards; `:deferred` holds the world until the
host releases it; `:waiting` and `:review` retain it; `:discard` drops it;
otherwise it merges, and a failed merge is retained for review. Automatic
settlement drains the fork's work, refuses a dirty parent, commits pending
workspace edits, and rechecks current merge permissions before adopting those
commits. Binary contents, modes and deletions therefore settle with the Run,
not just changes the agent explicitly committed. A pinned review-state merge
keeps its separate guard: preparing different contents would invalidate its pin.

### Simmis: governance

A **Proposal/ForkSet** is a durable governance object over one or more fork
contributions. It adds rationale, contributors, checks, comments, conflicts,
authorization, per-scope decisions, and resolution provenance.

It does not implement another execution fork. Promotion adopts an existing fork
descriptor and transfers settlement authority. A Proposal message, Tasks,
Futures, Feed, and Timelines are projections of that same governance object.

## Fork lifecycle and affine ownership

The canonical lifecycle is:

```text
                         merge
                    ┌────────────▶ merged
                    │
open ────────────────┼── discard ─▶ discarded
                    │
                    └── transfer ─▶ adopted by proposal/room
```

Only an open handle can be settled. Merge, discard, and transfer consume its
settlement authority exactly once. After transfer, only the adopter may settle
the fork. A RunWorld cannot retain a hidden second merge/discard path.

The live affine token should not be made copyable merely to make it
"fork-aware". It is intentionally process-local authority. Durable ownership
and status are facts in the Room/Simmis store; semantic state remains forkable.

Parent control is structural:

- code executing in a world can fork children;
- it may settle children it owns into itself;
- it cannot merge itself into its parent;
- transferring a child requires explicit authority from its current owner;
- a nested merge targets the immediate parent, never an inferred ancestor.

## What settlement means

Forking and merging are deliberately asymmetric.

### Durable substrate state

Registered Yggdrasil systems are merged or discarded through their native
protocols. This includes Datahike data, Geschichte files/history, and registered
CRDT systems.

### Reactive runtime state

Spindel's generic `OverlayBackend` contains both semantic values and execution
machinery. Its child delta must not be wholesale merged into the parent:

- continuations and subscriptions belong to one execution graph;
- mailbox claims and pending events have single-world delivery semantics;
- timers, cancellation tokens, provider streams, and native handles are live
  capabilities;
- copying cached graph nodes back can corrupt dependency identity.

Pure workflow results therefore return explicitly. When a reactive value should
contribute to its parent, the program supplies an explicit reducer/join, or the
value is promoted to a Yggdrasil-managed convergent system. A later
`PSettletableValue`-like hook may make selected signal settlement convenient,
but it must remain opt-in and algebra-specific.

### Conversation and execution facts

Room messages, Run lifecycle, activity, accounting receipts, and proposal facts
are durable control-plane facts written to their authoritative stores. They are
not recovered by merging arbitrary Spindel engine state.

## Multi-system settlement guarantee

The universal guarantee is **prechecked, journaled, and recoverable**, not
cross-backend ACID atomicity.

Spindel can preflight every system and then settle them in a deterministic
order. Because heterogeneous stores do not share a transaction coordinator, a
failure can occur after one system has merged. Settlement must therefore record
per-system progress and be safely resumable or compensatable.

A Yggdrasil `CompositeSystem` may provide a single causal visibility gate where
all readers resolve through that composite, especially for co-located CRDT
systems. It does not make arbitrary external backends universally rollbackable.
Dvergr and Simmis must not claim stronger atomicity than the selected systems
provide.

## Fork policy

What a world fork receives is decided where it is created. `ygg/fork!` (spindel) takes:

```clojure
{:purpose   :run | :workroom | :room | :particle | :proposal | ...  ; descriptor tag
 :owner     owner-id                     ; initial affine settlement owner
 :systems   :all | :none | #{system-id ...}   ; systems visible and branched
 :mode      :following | :frozen         ; overlay relation to the parent
 :snapshots {system-id snapshot-id}      ; pin systems at fixed values
 :rights    {system-id :write}}          ; explicit grants
```

Dvergr adds the rest on its own layers: a Run world's settlement policy
(`:automatic`, `:review`, `:discard`, `:deferred`, on the RunWorld), the effect
policy of the world (handlers in world state, [effects.md](effects.md)), and its
resources (a governed transfer from the parent's wallet, below). A room's own
context is a fork with `:systems :none` that registers only that room's systems;
a Run world is a `:purpose :run` fork of it, with all of them.

Spindel accepts only the enforceable `:write` right. It rejects `:read` and
`:fork` instead of placing an unenforced label in the descriptor; those rights
belong here once the substrate and SCI capability boundary can enforce them.
Simulations and inference should default to no live external effects unless
explicitly granted; eventually Kontor allocations should bound the fork policy
a child may receive.

Inference worlds follow this. The sandbox's `infer/smc-infer`,
`infer/importance-sampling` and `infer/kernel-infer` (foerster's inference on
Spindel worlds) run in fresh worlds by default (`:world-policy :fresh`): a fresh
world has no room state, and a stable handle called from an inference world
outside its owner's lineage throws (`dvergr.runtime.ctx/selected-context`). A
model that needs room state passes `{:world-policy :fork}`: each particle and
resampling descendant then owns a frozen canonical Yggdrasil fork, discarded
once the particle results are captured ([agent-programs.md](agent-programs.md)).

## Adoption: handing a fork to a durable owner

A Run that completes in review mode keeps its world open. Promotion hands that
open fork to a durable owner (a Simmis ForkSet, a long-lived child Room) through
`dvergr.rooms.forks/adopt!`. Its `:prepare!` callback must persist the
prospective descriptor and owner before Spindel transfers authority. A
successful transfer detaches the transient Room and returns the adopter's sole
process-local `ForkHandle`; a preparation failure leaves the review world open.
If the affine CAS loses a race after preparation, the required `:abort!`
compensates the prepared durable row. The handle is never persisted. The
prepared descriptor includes `:dvergr/room-id`, `:dvergr/parent-room-id`, and
(for a nested isolated world) `:dvergr/parent-fork-id`; the durable owner must
retain that ancestry until the child settles.

Adoption starts as whole-world authority. `partition-adoption!` can then consume
that aggregate handle into an exhaustive tree of disjoint system capabilities.
Each leaf settles exactly once; Dvergr releases the retained structural ancestry
only after every leaf is terminal and the supplied tree proves that no partition
was omitted. A durable adopter prepares the intended plan before partitioning
and commits the exact child descriptors afterward. If that commit fails, the
API returns the error together with all live child handles so recovery cannot
silently lose authority (`retry-partition-commit!`). No proposal layer may
accept or dismiss systems by mutating raw branches outside this capability tree.

Each durable leaf decision goes through `settle-adoption!`: governance prepares
the intent, Spindel consumes the affine capability, and governance commits the
terminal descriptor as Spindel's single-execution post-commit callback. A
failed durable commit leaves terminal authority and its receipt available to
`retry-settlement-commit!`; it never recreates an actionable review. Structural
release rejects failed partition persistence, duplicate or substituted handles,
and any durable leaf whose terminal commit is incomplete. Commit callbacks see
only portable operation/descriptor data, never execution contexts or live
systems from Spindel's internal settlement payload. Failed compensation is
returned as explicit recovery state with the durable receipt still attached;
`dvergr.discourse/retry-settlement-abort!` repairs that durable intent before
the still-open capability can receive a new decision.

Affine possession is not a current grant on the merge targets. Product adopters
must check human decision authority and current originating-room grants before
journaling a merge. `dvergr.system.rooms/assert-fork-merge-permitted!` supplies
the room-grant preflight for a fixed partition leaf; partition even a singleton
world before using it. Call it in the trusted `prepare!` callback with the
actual leaf handle and origin slug resolved from the adopter's durable room
identity, not caller-supplied descriptor ancestry. It checks only that leaf,
refuses unavailable protected deltas, and leaves discard/commit-only recovery
possible after revocation. This preflight is not atomic with an independent
grant writer. `dvergr.system.rooms/registry-system-for` resolves runtime system
names through the canonical registry rules so a product can separately enforce
its human per-target permissions without duplicating those naming rules.

The durable half belongs to the adopter and is not in this repository: store the
descriptor as a GC root, index its systems, journal per-system settlement, and
after a restart reopen the named Datahike/Geschichte branches under that
ownership claim (never deserialize the old Spindel reactive context).

## Laws and characterization tests

The implementation should satisfy these laws before higher-level APIs depend on
it:

1. **Isolation:** a child write is invisible to parent and siblings before
   settlement.
2. **Basis:** every system contribution is diffed from the fork-time basis, not
   from timestamps or a moving parent head.
3. **Immediate parent:** settling a nested fork changes only its explicit parent.
4. **Single settlement:** merge, discard, and transfer are mutually exclusive and
   idempotent at the public boundary.
5. **Transfer:** after adoption, the previous owner cannot settle.
6. **Quiescence:** no worker may access a substrate after settlement begins.
7. **Explicit runtime reduction:** generic child engine state never leaks back by
   substrate settlement.
8. **Recoverability:** interruption after any per-system settlement step can be
   detected and safely resumed or surfaced.
9. **Restart:** adopted durable branches remain live GC roots; abandoned
   process-local branches are reclaimable.
10. **Particle independence:** resampled particle worlds never alias writable
    external systems unless the policy explicitly declares sharing.
11. **Attenuation:** a child cannot acquire systems, effects, or resource rights
    not delegated by its parent.
12. **Projection identity:** Room, Run, Proposal, UI, and audit projections refer
    to the same fork ID/descriptor rather than minting parallel fork identities.

# Worlds, savepoints and budgets

dvergr's Run worlds, budgets, resume and Attempt worlds are instances of the
spindel primitives, so the laws spindel states (and tests) hold for them too.
How each maps, as built:

| dvergr | spindel primitive | as built |
|---|---|---|
| Run world: `dvergr.agent.world/open!` → `d/fork-room` → a `ygg/fork!` handle the work Room owns and settles (merge, review, discard) | a world fork (`ForkHandle`) | the Room keeps the canonical handle; RunWorld adds only Run policy |
| Run budget: `dvergr.resource` wallets (Kontor), conserved transfers keyed by Run | `PResourceAuthority`: `grant!` on fork, `return!` on discard, `escrow!`/`claim!` across a process boundary | `dvergr.resource.authority/authority` is a `PResourceAuthority` over the ledger, mapping a world to the Run that owns it (`:run-id` in the Room's meta); Run worlds' savepoint sessions carry it |
| model spend of a Run | a resource in the authority | a Run allocated microdollars pays its model spend from its wallet: `chat.context/account-usage!` charges each cost through `resource/*spend-wallet*` (bound by the LLM loop; unlike `*model-scope*` it restricts no provider); a cost beyond what is left takes the rest and exhausts the budget, and the Run's budget is at most its wallet. Each debit has a fresh id (`resource/charge-spend!`), so a retried call is not charged effectively once |
| turn savepoints (`:conversation/turn`, persisted on the Run) | `savepoint`, `savepoint.portable/persist` | persisted without an escrow |
| `program/resume!`: a new Run in a Run world forked at the savepoint's snapshots | `portable/hydrate-into!` | dvergr forks the world and keeps the handle; `hydrate-into!` makes it the savepoint's continuation (seed and sequence, bookkeeping cleared, pinned components repinned, `continue-llm-run` started as the session's computation before the Run's first step); the supervisor drives the loop from the recorded step, and the session ends with the Run. The stopped Run's remainder is granted from its wallet to the new Run's (`:resources-from`); escrow is for a planned handoff across a process boundary ([runs.md](runs.md)). A resumed Run without a wallet starts with its budget minus what the stopped Run's chat spent |
| effect handler configuration in world state (`[:dvergr/effects :handlers]`, inherited by forks, composed) | savepoint handler tables in world state (`[:savepoint/handlers]`, inherited by forks, replaceable) | two tables: dvergr's holds portable effect specs with a composition algebra, spindel's maps savepoint sites to functions; one convention would rename keys and add no guarantee |
| Attempt worlds: one forked world per cell, discarded after scoring | a `world-scope` over the experiment's worlds | not a scope: each cell's Run pays from a wallet of its candidate's budget (the preflight reports the cells × per-Run cap), cells settle `:discard` after certification and cleanup groups join the teardown. A scope pays once an algorithm runs over Attempts (SMC, MCTS: resampling agent runs, branching at savepoints) |

SCI effects stay tail-resumptive handlers ([effects.md](effects.md)): SCI code
is not CPS-transformed, so its effects cannot be spindel effects. The effect
*mechanism* in the sandbox stays dvergr's.
