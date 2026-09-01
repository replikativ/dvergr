# Forks, worlds, and settlement

Status: proposed cross-project contract. This document maps the existing
Yggdrasil, Spindel, Dvergr, and Simmis abstractions before their APIs are
consolidated. Names marked *provisional* describe semantics, not necessarily the
next public function names.

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

A **RunWorld** is a Run's lease and policy over one world fork:

```clojure
{:run/id             run-id
 :fork/handle        live-fork-handle
 :room/view          work-room
 :settlement/policy  :automatic|:review|:discard}
```

Its fork identity, parent, child context, and live settlement capability come
from the canonical Spindel handle and are not recreated in Dvergr.

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

World creation needs an explicit policy surface:

```clojure
{:purpose :run|:workroom|:particle|:proposal|:experiment
 :systems :all|:none|#{system-id ...}
 :mode :overlay|:snapshot
 :rights {system-id :write}
 :effects :live|:record|:stub
 :settlement :automatic|:review|:discard}
```

The default for a normal isolated Dvergr Run can remain all systems granted to
the Room. Simulations and inference should default to no live external effects
unless explicitly granted. Eventually Kontor allocations determine both
resource rights and the maximum fork policy a child may receive.

Today Spindel accepts only the enforceable `:write` right. It rejects `:read` and
`:fork` instead of placing an unenforced label in the descriptor. Those rights
belong here once the substrate and SCI capability boundary can enforce them.

## Representative use cases

### Coding Run from the REPL

1. Open one Spindel world fork from the Room.
2. Execute SCI/model/tool work against fork-resolved Geschichte and Datahike.
3. Run trusted checks in the same world.
4. Return the computed result through the Run.
5. Merge, retain for review, or discard after physical quiescence.

Needs: canonical handle migration and recoverable settlement. The core path
otherwise exists.

### Recursive team

A lead Run works in world `A`. Hiring a researcher creates `B = fork(A)`; that
researcher may create `C = fork(B)`. Accepted `C` work merges into `B`, and
accepted `B` work merges into `A`. Trunk is never an implicit target.

The canonical nested ownership path now exists: SCI `dvergr.agent/hire!` and the
`spawn_agent` / `propose_change` tool adapters all use Run worlds. Recursive
`hire-in!` keeps durable facts in the root control Room while forking and settling
against the immediate work-world parent. Paid recursive LLM delegation remains
attenuated until measured provider usage is debited from the already-supported
conserved Run resource vectors. Owned child Runs delay parent settlement and
resource return even when their handles are ignored; ambient/detached work must
eventually receive a distinct durable owner rather than arise by accident.

### Simmis proposal

A standalone Run completes in review mode. Promotion transfers its open fork to
a durable ForkSet and persists the descriptor as a GC root. The Run becomes a
contributor and loses settlement authority. Review may survive restart and
settles the adopted world as one affine unit.

The live bridge is `dvergr.rooms.forks/adopt!`. Its `:prepare!` callback must
persist the prospective descriptor/owner before Spindel transfers authority.
Successful transfer detaches the transient Room and returns the adopter's sole
process-local `ForkHandle`; preparation failure leaves the review world open.
If the affine CAS loses a race after preparation, the required `:abort!`
compensates the prepared durable row. The handle is never persisted.
The prepared descriptor includes `:dvergr/room-id`,
`:dvergr/parent-room-id`, and (for a nested isolated world)
`:dvergr/parent-fork-id`. The durable owner must retain that ancestry until the
child settles; Dvergr's shared topology is its immediate in-process projection.

Adoption starts as whole-world authority. `partition-adoption!` can then consume
that aggregate handle into an exhaustive tree of disjoint system capabilities.
Each leaf settles exactly once; Dvergr releases the retained structural ancestry
only after every leaf is terminal and the supplied tree proves that no partition
was omitted. A durable adopter prepares the intended plan before partitioning
and commits the exact child descriptors afterward. If that commit fails, the
API returns the error together with all live child handles so recovery cannot
silently lose authority. No proposal layer may accept or dismiss systems by
mutating raw branches outside this capability tree.

Each durable leaf decision goes through `settle-adoption!`: governance prepares
the intent, Spindel consumes the affine capability, and governance commits the
terminal descriptor as Spindel's single-execution post-commit callback. A
failed durable commit leaves terminal authority and its receipt available to
`retry-settlement-commit!`; it never recreates an actionable review. Structural
release rejects failed partition persistence, duplicate/substituted handles,
and any durable leaf whose terminal commit is incomplete. Commit callbacks see
only portable operation/descriptor data—never execution contexts or live
systems from Spindel's internal settlement payload. Failed compensation is
returned as explicit recovery state with the durable receipt still attached;
`retry-settlement-abort!` repairs that durable intent before the still-open
capability can receive a new decision.

Simmis still needs to supply the durable half: store the descriptor as a GC
root, index its systems into the existing ForkSet, and journal per-system
settlement. After restart it reopens the named Datahike/Geschichte branches
under that durable ownership claim; it does not deserialize the old Spindel
reactive context.

### Multi-agent campaign

Several Runs contribute independent forks. The Proposal may retain each
contribution for per-agent/per-scope decisions, or construct an additional
integration world and merge selected contributions into it. The integration
world is another canonical fork, not a special proposal branch implementation.

Needs: multiple adopted descriptors and explicit contribution edges. ForkSet
already supplies most governance semantics.

### Thought experiment

Fork a snapshot of selected state, substitute recorded/stubbed effects, execute
a hypothetical conversation or program, return an explicit result, and discard
the world. It creates no Proposal unless deliberately promoted.

Needs: selected systems/effect policy. Message-only discourse branching remains
available as a cheaper operation when no state isolation is required.

### SMC particles

Create `N` snapshot world forks at a checkpoint. Each particle receives an
independent reactive state and an explicit external-system/effect policy.
Observe evidence, resample descriptors/contexts, and discard unselected
particles. Only an explicitly selected particle can be promoted or merged.

Spindel's inference coordinator now accepts `:world-policy :fork`: initial and
resampled particles are frozen canonical Yggdrasil forks, and the complete
speculative tree is discarded after immutable particle results have been
captured. Dvergr's SCI `infer/smc-infer`, `infer/importance-sampling`, and
`infer/kernel-infer` wrappers select that policy by default. A caller may pass
`:world-policy :fresh` only for a model known to be pure. `infer/values`,
`infer/log-weights`, `infer/ess`, and `infer/worlds` project results and portable
world descriptors without exposing live settlement handles.

This solves isolation and affine cleanup for one inference population. It does
not promote a posterior particle automatically: promotion remains an explicit
Run/proposal operation, and PGAS ancestor scoring is rejected for canonical
worlds until retained-world ancestry has a sound ownership contract.

### MCTS

Each tree edge forks its node world, applies an action, runs or simulates effects,
and accumulates reward/evidence. Pruning consumes and discards the corresponding
handles. Transpositions may share immutable snapshots but never writable
capabilities.

Needs: cheap nested handles, deterministic seeds/virtual time, selective/stubbed
effects, resource accounting, and aggressive lifecycle/GC. No new fork algebra
is required.

### Continuous reactive cooperation

Long-lived agents observe and communicate in one Room without creating a Run or
fork for every message. Runs identify bounded computations when useful; Room
signals and speech acts remain the continuous FRP substrate.

Needs: no additional fork primitive. Attention and steering policies compose
above the world model.

### Self-programming and verified training

An agent constructs an immutable Roster/workflow, stores durable program or
skill changes in forked Geschichte/Datahike, executes it in a controlled world,
and receives a trusted verifier result plus Kontor resource receipts. The world
and verifier basis make the environment replayable; reward is not inferred from
model prose.

Needs: versioned environment descriptors, fork basis, resource allocation and
effect receipts. SCI Vars/atoms may later become more deeply forkable, but
durable definitions should remain in owned substrates rather than depend on
serializing live evaluator internals.

### Long-lived child Room

A child Room that needs independent policy and substrate adopts/detaches a world
fork as its durable root. It is no longer an unsettled speculative fork. On
restart, it rehydrates from its descriptor and authoritative Room facts.

Needs: adoption and descriptor-based hydration. The current per-Room context
provisioning is a partial implementation without the unified lifecycle.

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

## Migration sequence

1. **Done in this pass:** stabilize this vocabulary and its core laws as
   characterization tests in Spindel.
2. **Done in this pass:** enrich `ForkHandle` with a portable descriptor and single-settlement/transfer
   boundary, reusing the distributed descriptor schema.
3. **Partial:** selected-system and snapshot-world policies exist; repair
   inference particle isolation next.
4. **Done in this pass:** change Dvergr `fork-room` to call `ygg/fork!` and retain
   its handle.
5. **Done in this pass:** keep `RunWorld` as Run policy plus the canonical
   handle/Room view (its small atom caches the policy decision, not substrate
   state).
6. **Done in this pass:** route `dvergr.rooms.forks` review and settlement through
   that handle.
7. **Done:** removed `dvergr.agent.subagent/hire!`, `dvergr.room/hire`,
   `dvergr.discourse/hire`, and the independent `chat.context/fork-sub-chat`
   hierarchy. All bounded delegation and executable branching is Run-backed;
   conversational branches remain message/thread projections.
8. **Partial:** Dvergr now exposes durability-first affine adoption. Implement
   the Simmis GC root, whole-world ForkSet projection, and recoverable settlement
   journal. Add affine world partitioning before exposing per-scope settlement.
9. Replace boot-time blanket orphan cleanup with descriptor/owner-aware GC.
10. Build SMC, MCTS, resource-aware campaigns, and training environments on the
    same world policy.
