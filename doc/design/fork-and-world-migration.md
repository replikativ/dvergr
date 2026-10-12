# Forks and worlds: use cases and migration sequence (design history)

Status: history. The contract as built is [worlds-and-forks.md](../worlds-and-forks.md);
this page keeps the use cases the contract was designed against and the migration sequence it
was built in. Steps 1, 2 and 4-7 of the sequence are built; 3 and 8 are partial; 9 and 10 are
not built. The "Needs:" lines are the plan as written, not a list of what is missing today.

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

The canonical nested ownership path now exists: SCI `dvergr.agent/hire!` (host:
`dvergr.agent.program/hire!`) and the
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
`infer/kernel-infer` wrappers run in fresh worlds unless a model asks for that
policy: a fresh world cannot reach room state, since a stable handle called
from an inference world outside its owner's lineage throws
(`dvergr.runtime.ctx/selected-context`). `infer/values`,
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
