# The dvergr programming model — specification

Status: **agreed, version 1 (2026-10-26)**. Normative for the core written on the `next` branch.
It supersedes the contract parts of `worlds-and-forks.md`, `programming-model.md`, `runs.md` and
`effects.md`, which describe the current `main` implementation. Normative prose describes the
**target contract** unless labelled *current*. Implementation plan, size budget and branch decisions
are not part of this contract.

**Law status.** *holds*: implemented and checked by the named test. *implemented*: in dvergr, no
law test. *upstream*: provided by an owner, not integration-tested in dvergr. *target*: not
implemented. A law that holds only for a narrower statement is split into a lettered sublaw.
*Definition needed* marks a law whose oracle is not yet fixed. Statuses refer to dvergr
`origin/main` `fd04f72c`, Spindel `2e394753`, Kontor 0.1.40; the new core re-establishes every law
with its own tests. Wherever a law says *exactly once*, *resolves* or *is repaired*, it is a safety
statement plus an explicit progress assumption; safety holds while a service or recovery is
unavailable, progress does not.

## 0. Scope, owners, prerequisites

dvergr specifies who may start work, in which world, with whose resources, what happened, and who
may accept it. It states the upstream guarantees its composition requires and tests their
integration; their mechanisms and canonical state machines remain owned upstream.

| Owner | Owns | dvergr's distinction |
|---|---|---|
| Spindel | reactive computation; scopes, owners, obligations, leases, parked runs per (world, runtime run), lineage; fork modes; coordinated cuts; savepoints and hydration; affine transitions; durable settlement recovery; `PResourceAuthority` | P1–P6 are requested capabilities; dvergr adds no competing substrate authority |
| SCI | heap realisation, world-selecting handles, direct-cell `Forkable` classification, continuation-context capture and retargeting | direct-cell classification does not make injected graphs safe; dvergr restricts what it injects (W4) |
| Yggdrasil and adapters | per-system snapshot, branch, diff, conflicts, merge, discard | capability flags alone establish neither a cut, nor validation against a changing parent, nor safe compensation, nor retention; each needs a versioned adapter contract |
| Kontor | resource arithmetic, transfer validation, command identity and payload checking, business-book intent extraction and gate replay | Kontor does not supply the ancestry gate, receipt completeness or durable dispatch ownership |
| Datahike | transactions, immutable values, history, storage | — |
| dvergr | policy selection, authenticated delegation, contribution-to-intent mapping, control projections, external dispatch and its recovery | — |

**Upstream prerequisites.** Where a pinned version lacks one, the dvergr operation needing it
refuses.

- **P1 coordinated cut** (Spindel). A cut records a fixed participating component set and a basis
  that corresponds to one allowed state of the fenced coordination domain. Every admitted mutation
  is classified as completed before the cut, parked at a supported boundary, or excluded/refused; no
  included mutation is half-applied across the cut. Cut acquisition does not await the suspended
  hire caller or other deliberately parked computations; non-cooperating execution causes refusal
  or timeout, and timeout does not establish physical completion. Cut failure releases temporary
  fences and keeps ownership of ambiguous operations. *Current:* frozen-cut coherence is violated
  (AS-IS I19).
- **P2 suspend–unwind–cut–resume** (Spindel + SCI): an awaited operation inside SCI unwinds out of
  the active evaluation and releases the SCI permit before the coordinator acts.
  *Current (upstream by reading, not tested; SCI `0d38f0c9`):* the permit is a per-thread read lock
  held for one entry into SCI (`world.cljc:153`, conveyed fns `vars.cljc:317`); a spin parked at an
  await unwinds and releases it, a resumed continuation re-acquires it for its slice, and `fork-world`
  (`world.cljc:921`) refuses while the calling thread holds a permit. The contract holds without an
  SCI change provided (a) the cut runs as its own event after the unwind, never inline in the await
  handler, (b) with several SCI components the exclusive permits are taken in world-id order, and
  (c) interpreted code blocking inside an evaluation keeps its permit, so the cut times out and
  refuses (the refused blocking `hire!`).
- **P3 world-local obligations and parked runs** (Spindel): ownership per (world, runtime run);
  cancelling or resuming one copy does not affect others (AS-IS I6, I21).
- **P4 quiescent merge and post-seal cleanup** (Spindel).
- **P5 durable settlement recovery** (Spindel): step identities, completion witnesses, forward
  recovery and owned unresolved operations, **including intent-system stamping and its internal
  transactions after the enclosing affine transition is terminal**. A pending queue held only in
  parent runtime state does not satisfy P5. *Current:* `run-stamp!` failure after `merge-single!`
  records a runtime-only pending plan (Spindel `yggdrasil.cljc:1937,2126`).
- **P6 portable savepoints and hydration** (Spindel): what a warm resume may rebuild, versioned.

Related upstream exclusions: following-mode batch leakage (AS-IS I18) and restored-snapshot timer
ownership (AS-IS I23) are Spindel's to resolve; dvergr never uses following mode for Runs.

Out of the model (products): LLM loop and prompts, providers, evaluation methodology and
experiments, catalog and benchmarks, ops, MCP, web, TUI/CLI, channels, host daemon, scheduling
policy, intake, drive, media, developer tooling.

## 1. Definitions

- **World**: a Spindel execution context with the SCI components and Yggdrasil systems registered in
  it, its effect policy and its resource-authority binding. A **root world** has no parent.
- **Domains.** *Work state* (SCI managed heap, reactive values, declared parked computations, work
  databases, workspace, speculative business book, speculative conversation messages) forks.
  *Control state* (Run, Attempt, grant, reservation, receipt, admission, cancellation,
  settlement-operation and recovery records) lives in the **control store**, reached through a
  non-forking capability, never as a selected work system. *Native resources* (threads, provider
  streams, sockets, subprocesses, transport subscriptions) are scope-owned and never copied.
- **Room**: a durable discourse venue bound to a world; an independent root world, or a snapshot
  fork taken at its creation; never a long-lived following fork of the daemon root. Daemon state
  (registry, clocks, configuration) reaches it only through explicit daemon-owned signals or
  capabilities.
- **Handle**: a logical name injected into SCI and resolved in the selected world on every use
  (*current:* `WorldConnection`, `src/dvergr/sandbox/ns/datahike.clj:183`).
- **Run** (dvergr): durable identity of one causally bounded execution; distinct from a Spindel
  *runtime run*, a live computation.
- **Execution scope**: the Spindel scope owning a Run's live work. **Settlement scope**: the set of
  registered systems a settlement capability covers. They are different things.
- **Contribution**: a world's work-state changes relative to its retained base, per registered
  system.
- **Admission index**: the control-store record of every admitted effect, written before execution (§5).
- **Settlement capability**: Spindel's affine handle authorising consuming transitions (merge,
  discard, transfer, partition) over a settlement scope.
- **Acceptance**: the merge of a scoped contribution into its parent. **Acceptance-complete**: every
  required merge, intent-replay and application-integration step of that acceptance has a completion
  witness.
- **Receipt**: a control record of one effect's intent, dispatch and known outcome.
- **Dependency manifest**: for a staged intent, the contribution scopes and observations its
  payload, destination and authorisation decision depend on.
- **Certification manifest**: the identities an Attempt covers (§8).
- **Attempt**: immutable certification over a Run and its certification manifest.

## 2. Worlds and assembly

Assembly records, per component, whether it is snapshotted, deliberately shared, externally governed
or unsupported, and refuses a requested guarantee it cannot provide. The descriptor is Spindel's
canonical fork descriptor plus dvergr references; dvergr creates no second world identity or
settlement token. Assembly excludes control records from every work-system snapshot and merge.
Lineage: every world records immutable source-world and cut lineage independently of live parent
pointers; durable Run parentage, resume causality, world ancestry and settlement-successor lineage
are distinct relations.

| Law | Statement | Status |
|---|---|---|
| W1a | An SCI atom or namespace mapping mutated in a fork leaves the parent's realisation unchanged. | holds on `next`, synchronous admitted SCI surface (`test/dvergr/laws/world_test.clj:21`, `w1a-managed-state-isolation`; `test/dvergr/adapters/world_test.clj:18`, `w1a-selected-sci-contexts`) |
| W1 | A closure defined in the parent capturing a handle reads and writes the selected world's system when invoked in parent, child or sibling (including var roots and metadata); invocation from an unrelated lineage is refused. | holds on `next`, synchronous Datahike operations (`test/dvergr/laws/world_test.clj:47`, `w1-handle-selects-current-world`; `test/dvergr/laws/world_test.clj:101`, `w1-lineage-anchors-and-installation-selection`; `test/dvergr/adapters/world_test.clj:31`, `w1-datahike-bases-and-callable-aliases`) |
| W2a | A Geschichte workspace excluded from a fork cannot be reached through the selected-workspace resolvers. | holds on `next`, approved Geschichte adapter (`test/dvergr/laws/world_test.clj:73`, `w2-excluded-systems-never-fall-back`; `test/dvergr/adapters/world_test.clj:61`, `w2a-geschichte-native-workspace`) |
| W2 | No inherited resolver reaches a system excluded from the fork; no parent fallback; no retained raw connection. | holds on `next`, admitted synchronous handles and selected component subsets (`test/dvergr/laws/world_test.clj:73`, `w2-excluded-systems-never-fall-back`) |
| W3 | Host vars, execution contexts and raw database connections are not sandbox values (SCI vars are managed state). | holds on `next` for audited slice 1 surfaces (`test/dvergr/laws/world_test.clj:176`, `w3-host-values-and-escape-surface`) |
| W4 | Every value dvergr injects into SCI has a declared fork classification; a mutable host value reachable from an injected value without classification is refused. | holds on `next` for the closed `:dvergr.injection/v1` grammar, including metadata (`test/dvergr/laws/world_test.clj:152`, `w4-recursive-injection-grammar`; `test/dvergr/laws/world_test.clj:125`, `w4-assembly-preflight-and-bounded-operations`; `test/dvergr/adapters/world_test.clj:31`, `w1-datahike-bases-and-callable-aliases`) |
| W5 | Work-store merge cannot create, overwrite or retract a control record; work-message branches hold only message contributions and references to control identities. | holds on `next` for Datahike work merges and the approved adapter registration boundary (`test/dvergr/adapters/world_test.clj:75`, `w5-work-merge-cannot-change-control`; `test/dvergr/adapters/world_test.clj:106`, `w5-control-store-aliases-refuse`); work-message integration remains target |
| W6 | Lineage is reconstructible after the parent's runtime objects are released. | holds on `next` for durable assembly/source evidence, including sequential JVM restart; no runtime hydration claim (`test/dvergr/laws/world_test.clj:208`, `w6-durable-tree-lineage`; `test/dvergr/laws/world_test.clj:229`, `w6-publication-refusal-and-root-room-gates`; `test/dvergr/adapters/world_test.clj:126`, `w6-control-reopen-and-role-gates`; `test/dvergr/process/lineage_test.clj:17`, `w6-process-reopen-lineage-and-room`; two-phase command in `doc/slice1.md`) |

## 3. Forks

**Modes.** *snapshot* is upstream frozen mode plus a P1 cut; it is the only mode for Runs and
Attempts and is not `snapshot-context`. *following* is explicit, exploratory and short-lived.

**Tiers**, stated per component in the descriptor: *hot* (managed SCI and reactive state plus
registered systems, including supported parked computations; needs P1, P3), *warm* (rebuilt from a
portable savepoint and durable systems; needs P6), *cold* (an external system's snapshot or branch
under its versioned adapter contract). A composite world claims the composition of its components'
claims; a component with uncontrolled writers cannot be in a coherent-cut claim.

**Fork cut versus settlement closure.** A fork cut parks participating work, captures and reopens
the parent; the parent scope stays open. Settlement closure (§4) ends the child's work.

**Snapshot initialisation is not commit validation.** Each system's merge policy states whether it
validates observed reads, only conflicting writes, or neither; dependencies it does not cover may be
stale without preventing acceptance (contrast Worlds' read-freeze and commit-time read validation).

**`hire!` from SCI.** `hire!` is awaited inside `spin`. It suspends through the upstream CPS boundary
and unwinds completely out of the active SCI evaluation before fork coordination begins (P2); the
coordinator never forks inline while servicing the await registration. The fork point is the P1
barrier reached after that unwind, after every previously admitted participating mutation has completed or
reached a supported parked boundary satisfying P1, and before either the caller's continuation or the child takes its next step. A blocking `hire!` that
keeps the evaluation permit is refused. The child starts the requested entrypoint against inherited
managed state; it never runs a copy of the caller's hire continuation and inherits none of its
delivery callbacks, cancellation authority, payer binding or native operations. Dormant inherited
continuations are retained state, not active work.

**Admission order** (a partial order): durable request and recovery identity precede any operation
whose outcome can become ambiguous; the cut and confirmed funding precede child execution;
publication associates the exact cut, grant and delegation; cancellation prevents later executable
publication. The ledger is outside the snapshot. Failure before publication exposes no executable
child; confirmed partial branches are cleaned up and confirmed grants reconciled; ambiguous
operations stay fenced under durable recovery ownership until resolved.

**Following-mode anomalies** (stated, not hidden): reads of untouched parent paths may change after
earlier reads; torn views across components (a followed signal beside a frozen database value);
history dependence of shadowed paths; scheduling as an input; retention coupling through the parent
lease.

| Law | Statement | Status |
|---|---|---|
| F1 | Application execution in isolated work state does not mutate parent or sibling realisations. Settlement may publish registered-system changes incrementally; a failed settlement may leave visible partial changes, recorded as unresolved acceptance work (S7), and does not restore speculative isolation. Deliberately shared resources and immediate external effects are outside this law. | target (SCI part: W1a) |
| F2 | A snapshot child starts from one P1 cut of its declared participating work components; its reads observe that basis plus its own and explicitly accepted mutations; later parent mutations do not change the basis. Control-store reads and external observations are governed separately. | target (needs P1) |
| F3 | Each admitted hire continuation receives at most one admission outcome. Successful admission resumes it once with the child handle, and the child runs a fresh entrypoint in the caller's immediate world as of the fork point; cancellation or failure completes its obligation under the upstream cancellation contract without resuming cancelled application work; no permit deadlock. | target (needs P1, P2, P3) |
| F4 | A failed fork exposes no executable child; every confirmed partial operation is cleaned up or reconciled; ambiguous ones stay under durable recovery ownership. | target |
| F5 | A descriptor's per-component tier claim satisfies that component's versioned adapter predicate; an unsupported claim is refused. | target; definition needed: adapter predicates |
| F6 | The admission partial order holds under cancellation at every admission stage. | target |

## 4. Settlement

**Authority.** Spindel's settlement capability is the only substrate settlement authority. dvergr
chooses policy (automatic, review, discard, deferred), checks current authority, records evidence and
projects outcomes; its projection and dispatch-recovery records reference canonical upstream
operation identities.

**Settlement closure, in order:** stop admission; finish or cancel and physically join all work the
child's execution scope owns, closing participants, buses, timers and watchers; seal the work and
evidence basis; check policy and current authority; validate each system's conflicts against the
parent basis used for mutation, through that adapter's declared lock, version check or equivalent
(a preflight check alone is not commit validation); invoke the upstream transition; persist
projections; evaluate staged intents (§5); release resources and retention roots whose obligations
are complete. After sealing, owner-authorised cancellation, unsubscription and release of native
components remain legal and idempotent; the registered-system set is frozen and new execution is
refused. Discard cancels or abandons the world's owned parked computations; late callbacks cannot
resume them or recreate state; parent and sibling copies are unaffected.

**Acceptance completion.** A terminal affine disposition does not by itself establish that every
contribution has been applied. Acceptance evidence names every required merge, intent-replay and
application-integration step with its completion witness. Pending or uncertain steps prevent
acceptance-complete status and staged-intent release, and keep durable recovery ownership after the
capability is consumed.

**Per-system acceptance.** Datahike work db: three-way merge against the retained base; missing base
refuses. Geschichte workspace: native merge with conflict and expected-base checks. Kontor business
book: intent extraction, parent-claim check, replay through the gate; replay is several transactions,
recoverable under P5, and its `:pending` outbox rows never authorise dispatch. SCI heap and reactive
state: never merged; results return as values; a mutable handle or closure crossing a settled-world
boundary needs a declared ownership policy or is refused. Cold external systems: only their
advertised operations. Each system names its merge policy and its acceptance-order requirements, and acceptance evidence
records the merge-policy version used; determinism is claimed only under them.

**Saga.** Cross-system acceptance is a saga-style sequence with explicit incomplete recovery states;
it does not guarantee ACID, PSI or serialisability.
Intermediate committed steps may be visible. Each step declares a forward-recovery procedure and
whether compensation exists; absent compensation is explicit; compensation may fail and is not
erasure of history (resetting a branch is not undo once other writers advanced it). An indefinitely
incomplete operation is a supported failure state, not a promise of eventual completion.

**Retained bases.** Every unsettled contribution retains the exact adapter bases and lineage needed
for inspection, conflict checking and recovery. Transfer and partition move these retention
obligations without gaps. A base is released only when no live successor or recovery operation needs
it; a missing base causes refusal, never an additive fallback.

| Law | Statement | Status |
|---|---|---|
| S1 | Each live settlement capability authorises at most one consuming transition. Transfer preserves the settlement scope and invalidates the predecessor; partition replaces it with disjoint successors exhausting the scope. Each scoped contribution receives at most one terminal acceptance or discard. Transfer and partition are not acceptance. | holds in-process (`test/dvergr/fork_settlement_test.clj:30`; `test/dvergr/runtime/peer_bus_review_test.clj:199,262,380`) |
| S1r | Retrying an operation with its original identity looks up its recorded result without repeating substrate mutation; a changed payload under that identity, a stale capability, or a conflicting operation is refused; recovery uses the original identity, including after restart. | target (*current:* a competing discard returns the fork unchanged, `fork_settlement_test.clj:42`; `src/dvergr/agent/world.clj:79-86` caches without identity comparison) |
| S2 | Merge is refused until the child's execution scope has no live work (workers, pumps, timers, watchers). | target (needs P4; *current:* merge precedes `leave-all!`, `src/dvergr/discourse.clj:2545,2574`; buses have no close) |
| S3a | The affine substrate disposition (terminal or incomplete) is projected onto the Run. | implemented (`src/dvergr/agent/world.clj:89-108`, `src/dvergr/agent/program.clj:1917-1933`) — this is not acceptance completion |
| S3 | A completed substrate transition is never undone by a failed projection write; the projection is repaired from the settlement record without repeating substrate effects. | target |
| S4a | A failure in the mutation phase of ordinary registered-system merge (not preflight refusal, which leaves the world open) is classified `:incomplete`, keeps recovery ownership and refuses transfer. | holds (`test/dvergr/agent/settlement_truth_test.clj:118`) |
| S4 | An incomplete operation fences new application execution, registry changes, ordinary settlement and effect release. | target |
| S6 | Once durable settlement preparation begins, restart reconstructs a fenced operation with its identity and completed, pending or unknown steps (including stamping); no completed step's logical effect is duplicated; recovery may query or idempotently replay a step under its adapter contract. A crash before durable preparation implies neither acceptance nor partial mutation. | target (needs P5) |
| S7 | Acceptance-complete status requires a completion witness for every required step; a failure in any step, including after the affine transition is terminal, yields an unresolved acceptance with durable recovery ownership and blocks dependent staged intents. | target (*current counterexample:* Spindel `yggdrasil.cljc:1937,2126`; dvergr projects `:merged`, `agent/world.clj:104`) |
| S8 | Discarding a world cancels or abandons its own parked computations; late callbacks neither resume them nor recreate state; parent and sibling copies are unaffected. | target (needs P3) |
| S9 | After sealing, cancellation, unsubscription and native release remain legal and idempotent; registry changes and new execution are refused. | target (needs P4) |
| S10 | A retained base needed by a live successor or recovery operation is never collected; transfer and partition move retention obligations without a gap. | target |

## 5. Effects

Every injected effectful capability passes one boundary that supplies subject, selected world,
operation identity, resource target and canonical request; applies authority (rechecked at dispatch
and acceptance; a snapshot does not freeze revocation), resource admission and containment; then
executes, refuses, replays or stages, recording the disposition.

**World-bound handlers.** Behind the fixed boundary, every effect is answered by the handler stack
bound in the selected world: world state, inherited by forks, resolved at the moment of the call on
whichever thread, continuation or worker performs it (resolution across threads and resumptions is
Spindel's current-scope contract). Handlers *execute* (the real executor), *stage* (record an intent),
*replay* (a recorded outcome), *simulate* (a model of the external party, e.g. a simulated mail
recipient or an organisation simulator), *substitute* (a different executor of the same operation,
e.g. a cheaper model for speculation), *inject faults*, or *refuse*. The boundary's own layers —
identity, authority, admission, durable admission index and receipts — are outside the handler stack
and cannot be rebound. A grant bounds permitted operations, targets and providers, data exposure,
credentials, resource accounts and limits, and execution placement or release conditions. Rebinding
may change how answers are produced only within those bounds; it cannot remove staging, eligibility,
revocation or containment requirements, and an executor authorised for eventual release is not
thereby authorised during speculation. Substitution is permitted only when the actual executor,
request, exposure and resource use satisfy the grant; a lower advertised price establishes no
narrowing. Before dispatch the boundary validates the effective request and reserves against its
actual authorised cost bound; a larger bound needs additional admission. A simulation that performs
real inference or other external work admits and accounts for those effects separately. Handler
implementations get no unchecked route to external execution: replacement requests pass the fixed
boundary, and replacing an inherited answering handler does not replace inherited restrictions.

Every answer is receipted with the identity of the handler that produced it. **Observation provenance
is immutable and transitive:** replay records both the replay handler and the source outcome's
handler identity, version, effective executor and request, and simulation or substitution
classification; replay, nested acceptance, transfer and partition do not erase it; contributions and
intents keep the provenance of every influencing observation (conservatively, absent trusted narrower
tracking). A world whose effects were simulated or substituted records that in its descriptor.
Acceptance policy evaluates the transitive provenance; by default, automatic acceptance refuses
contributions that depend on simulated observations; an explicit policy may accept them with recorded
assumptions, but acceptance does not establish that a simulated external action occurred or that
simulated observations describe the real environment. Simulated completion never discharges a real
staged intent, which needs its own identity, dependency coverage, validation, reservation and release
authorisation. Attempts identify the environment and executor actually exercised; evidence about a
substitute does not establish equivalent behaviour of the requested executor. Handler state
(recordings, replay cursors, fault counters, quotas) is world-local unless a policy shares it (E5).

**Placement** (exclusive): branch-local · immediate external (authorised during speculation by its
own admission rule; discard does not undo it) · staged external · prohibited. **Replay properties**
(combinable): remotely deduplicated, locally retryable, compensable, possibly uncertain after a lost
acknowledgement. Default placement by world kind (O3), installed as default handler bindings:

| Effect | Root world | Speculative world |
|---|---|---|
| model inference | immediate, against the world's wallet | immediate, against the world's wallet |
| observation (fetch, search, read requests) | immediate, capability and exposure policy | immediate, capability and exposure policy |
| messages to authenticated participants of the room (including humans over a channel) | branch-local history; live delivery immediate external | branch-local history; live delivery immediate external, marked as from the fork, not undone by discard |
| other egress (mail, publication, posts, external writes) | immediate under its advance capability | staged |
| money | immediate, capability and budget | staged |

The participant-delivery row is O3's explicit immediate permission under D5; other recipients follow
the other-egress row. "Money" means transfers or payments requested as operations; charges incurred by
authorised immediate inference or observation follow those operations' placement and §6 reservation
rules. A capability may be granted explicitly as immediate in forks.

**Durable effect admission.** Before an executor may run, the control store durably records the
effect's operation identity, Run and world attribution, canonical request identity, placement and
recovery owner in the **admission index**. Failure or ambiguity of this write prevents execution until
resolved; every executor entry references such an admission. Sealing atomically fences further
admission for the included Run scopes and records their final admission sequence bounds;
certification includes every admission within those bounds, including those without a known outcome,
and a missing outcome is never read as evidence that execution did not occur. Candidate and verifier
execution have separate admission scopes and seals; verifier execution cannot reopen the candidate's
sealed scope.

**Invocation paths.** Every supported invocation path installs a consistent selected Spindel world,
the corresponding SCI component context, Run attribution and execution ownership before application
code runs; captured contexts are retargeted through the supported continuation or invocation
contract, or invocation is refused; a missing or incompatible context never falls back to the
capability's owner. Blocking workers keep their selected world and ownership until physical exit; a
worker holding an SCI permit may prevent a cut, which then times out or refuses (P1, P2).

**Receipts.** Intent, dispatch and known outcome are recorded separately in the control store;
bounded in-memory sinks are diagnostics. A replay match requires program/schema version, logical
operation identity, canonical request and a recorded outcome; replay substitutes that outcome without
entering the executor; divergence creates a new operation or refuses. Each admitted operation records the selected
handler-stack version and the effective executor and request, so concurrent rebinding cannot change
that operation's interpretation. Replay matches a logical operation to its recorded answer and keeps
its provenance; it does not imply deterministic scheduling or reproduce unrecorded simulator
behaviour. A deterministic re-execution claim additionally identifies handler versions, initial
state, randomness and ordering inputs, or is not made.

**Staged intents.** A staged operation returns a typed pending-intent reference, never a fabricated
result. Each intent has a stable identity, canonical payload, originating contribution, dependency
manifest, reservation and release policy. Dependencies include influences on payload, destination
and authorisation, not only identifiers in the payload; coverage is tracked or conservatively
approximated, never established by an untrusted declaration alone. A manifest is sealed only after
no owned execution can add a dependency or intent to it. Acceptance into a speculative parent records
how dependencies carry into the parent's contribution; transfer changes the authority holder without
removing them; partition preserves the mapping and assigns each intent and reservation exactly once.
Settlement transformations (e.g. Kontor renumbering) produce a final canonical payload with
validation evidence; the approved payload and endpoint idempotency key are fixed before the first
dispatch, and a changed payload is never retried under a used key.

**Initial dependency policy.** Until a trusted dependency policy establishes narrower coverage, each
staged intent depends on its entire originating contribution and its enclosing speculative
contributions; partitioned release is refused when this coverage cannot be represented and verified;
the intent's control identity and reservation stay unique even when its dependencies span several
successor scopes.

**Eligibility.** An intent becomes eligible when acceptance-complete evidence covers its whole
dependency manifest and required speculative ancestry, its final payload is validated, current
authority permits release and its reservation is valid. An unrelated discarded partition does not
block an intent whose complete manifest excludes it; discard of a required dependency cancels it
permanently; unknown coverage blocks partitioned release. A root world releases under a policy identified
in its descriptor whose completion predicate is stated there (definition needed: the initial policy
set). Three predicates stay distinct — evidence is sealed, dependencies are completely represented,
release is authorised — and none implies another; a complete dependency manifest does not establish
that observed values are still valid at acceptance.

**Dispatch.** Eligibility does not authorise concurrent sends. Before sending, a dispatcher durably
claims the intent's attempt identity. Without endpoint deduplication, an uncertain attempt is never
reassigned for another send; with deduplication, retries keep the approved payload, key and endpoint
deduplication scope. This also governs competing first attempts.

**Waits.** Child execution completion and child acceptance into an open speculative parent never
require that parent's settlement or external-effect completion. Awaiting a staged effect is refused,
before the wait is entered and while the scope is still running, whenever the effect's release
depends on closure of the waiting execution's own scope or an ancestor's.

| Law | Statement | Status |
|---|---|---|
| E1 | No effectful capability reaches its executor without passing the boundary. | target (*current:* a nil boundary goes to the executor, `src/dvergr/effects.clj:575`) |
| E2 | Normalised filter composition is associative with identity. | holds (`test/dvergr/effects_test.clj:132,137`) |
| E3 | A staged intent is not dispatched before eligibility. Without endpoint deduplication or authoritative outcome lookup, an ambiguous dispatch is not repeated automatically; with a stable idempotency key, retries yield at most one logical external action within the endpoint's deduplication scope. | target |
| E4 | No executor runs without a durable admission-index entry; certification includes every admission within the sealed bounds and refuses unless each has a durable receipt with an explicit disposition (unknown included). | target (*current:* sink capped at 2,000, `src/dvergr/effects.clj:167`; log failure tolerated, `src/dvergr/agent/evaluation.clj:648`) |
| E5 | Replay cursors, fault counters and quotas are world-local unless a policy shares them explicitly. | target (`src/dvergr/effects.clj:330`) |
| E6 | Dependencies of a staged intent survive nested acceptance, transfer and partition unchanged in coverage; an intent is assigned to exactly one successor. | target; definition needed: dependency tracking mechanism |
| E7 | Awaiting a staged effect whose release depends on the waiter's own or an ancestor's closure is refused before the wait. | target |
| H1 | An effect is answered by the handlers of the world selected at the call, whichever thread, continuation or worker performs it (parent-created conveyed functions invoked in children, deferred callbacks, mailbox delivery, blocking workers); a thread without a conveyed world gets an error, never the parent's, root's or capability owner's handlers. | target (*current:* `src/dvergr/runtime/ctx.clj:57` falls back to the capability owner; Spindel current-scope requirement: the engine rebinds the world holding a continuation on drain, deferred/mailbox delivery, embedder-thread blocking work and SCI-conveyed fns; drain paths done in `simple.cljc`, others unaudited) |
| H2 | Rebinding never removes a grant's bounds (operations, targets, exposure, credentials, resources, placement and release conditions); substitution satisfies the grant on its actual request and cost bound; the boundary's identity, authority, admission and receipt layers are not rebindable. | target (*current:* `write-refusal` runs after answering handlers, `src/dvergr/effects.clj:713-717`) |
| H3 | Every answer is receipted with the answering handler's identity; simulation and substitution provenance is immutable and transitive through replay, nested acceptance, transfer and partition; default automatic acceptance refuses contributions depending on simulated observations; simulated completion never discharges a real staged intent. | target |
| E8 | Each eligible intent has at most one claimed dispatch attempt at a time; an uncertain attempt without endpoint deduplication is never reassigned. | target |

## 6. Resources

The resource authority maps rooms, Runs and funded worlds to Kontor accounts in the control store.
The initial deployment has one active execution authority and one durable control ledger; restart
reuses that ledger; concurrent process handoff is unsupported without an upstream fencing and
recovery contract. Wallets, reservations, holds and escrow are disjoint account classes, each counted
once; an accounting domain is closed under internal transfers. Commands carry stable identities and
immutable payloads, including timestamps. Escrow moves available value only; every outstanding
reservation keeps a durable owner, disposition and refund destination independent of its source
world's lifetime; claim publication and recovery refer to the original immutable command; a late
refund is a new identified transfer (or an explicitly specified sweep), never a changed replay of an
earlier return. Chat budgets and spend reports are limits and projections, never spending authority.
A business book is work state (§4), not the ledger.

| Law | Statement | Status |
|---|---|---|
| R1 | For each resource unit and accounting domain, over a specified interval and one authoritative ledger snapshot, the sum over wallets, reservations, holds and escrow equals initial funding plus authorised inflows minus recorded consumption and authorised outflows; grant, return, reserve, escrow and claim conserve it. Money and call units are conserved separately. | target (examples: `test/dvergr/resource/authority_test.clj:274,424`) |
| R2a | A governed model dispatch with a bound payer and positive bound is reserved before it is sent. | implemented (`src/dvergr/model/gateway.clj:94-97`, `src/dvergr/resource.clj:504-512`) |
| R2 | Every funded dispatch has prior reservation evidence; balances stay non-negative under concurrent exhaustion, including zero-bound and missing-payer cases. | target |
| R3 | Discard returns only available, unreserved value; reservations for dispatched, accepted-pending or uncertain effects keep a durable recovery owner until consumed, cancelled or reconciled; late refunds are neither lost nor spendable twice. | target (*current:* dispatch ownership in `*dispatches*`, `src/dvergr/resource.clj:492-493,555-565`; reconstructed timestamps, `src/dvergr/resource/authority.clj:111-127`) |
| R4a | An identical grant command replayed after a lost acknowledgement moves nothing again. | holds (`test/dvergr/resource/authority_test.clj:274-315`) |
| R4b | The same command identity with a changed route, amount, actor or timestamp is refused. | upstream (Kontor `resource.clj:322-352`) |
| R4 | R4a and R4b hold across restart for every command kind. | target |
| R5 | No non-ledger budget or projection can enable an otherwise unfunded dispatch. | target |

## 7. Discourse

A **message** has stable identity, authenticated author, recipients, a speech-act tag (`:type`),
reply causality and optional Run attribution. Speculative conversation messages are work history;
admission, cancellation, settlement and recovery events are control history. A **subscription**
delivers matching messages to a participant handler within a **live-delivery session**, which begins
when the handler joins and ends on leave, cancellation or process loss. Live delivery is at most once
per session and is not guaranteed to be complete or ordered. The durable history contains each
successfully persisted envelope once within its declared retention horizon; its query order is the
store's stable ordering key, which does not imply causal order across independently forked histories;
consumers that need completeness read it. History absorbed from a merge or replay is
never delivered to live handlers. **`ask`** creates a scoped reply obligation with a deadline; a reply arriving after the deadline is
handled by the declared deadline-arbitration rule (the deadline wins once timeout has been recorded; the late
reply stays in the history as an ordinary message linked by reply-to).
A **decision handle** swaps who decides without changing the loop. A **discourse branch** is a
conversation projection sharing the world; a **world fork** is the only way to isolate execution; they
are separate operations. Delivering a speculative message outside its permitted domain is an effect
(§5), staged unless explicitly immediate; persistence before publication is not permission to publish.

| Law | Statement | Status |
|---|---|---|
| D1 | On a store-backed bus, posting persists the envelope before live publication; a retried post with the same identity is one message. | holds (`test/dvergr/runtime/durable_cursor_bus_test.clj:16,48`; `test/dvergr/runtime/multicast_test.clj:10`) |
| D2 | Each participant handler receives a matching message at most once per live-delivery session; raw bus subscribers may see redelivery after cursor recovery and are not participants. | target (raw redelivery intended, `durable_cursor_bus_test.clj:70`) |
| D3 | An `ask` has at most one terminal resolution — reply, timeout, cancellation or leave (a missing participant resolves as leave, handler failure as an error reply, deliberate suppression as timeout). The deadline makes timeout due; resolution and cleanup complete eventually under the declared scheduler and recovery progress assumptions; no subscription or obligation leaks. | target |
| D4 | Creating a discourse branch creates no isolated world; programs in it act on the shared world within their capabilities. | target (*current:* `fork-room :none` and `:ctx` share one name) |
| D5 | Control-history events are never written to a work-message branch, and speculative messages leave their domain only through the effect boundary. | target |

## 8. Runs and Attempts

A Run's live work belongs to its execution scope; observing a result confers no cancellation
authority; ignoring a hired child does not release the parent's ownership. Four observations are
distinct: **admission completion**, **child execution completion**, **child acceptance into its
immediate parent**, and **external-effect completion**. Parent closure waits for owned child execution
and required cleanup, not for external effects whose recovery ownership has been durably handed to
the control service. A cancellation request is not physical completion; failure or timeout keeps
ownership and prevents success certification and release of unsettled obligations. Child authority
is never broader than the current delegation.

**Construction.** A fresh `hire!` is a hot snapshot fork (§3). **Resume** is warm (P6): a new Run
identity in a world forked at a portable savepoint, continuing through hydration. Hot state is not
durable. *Current:* the public result is delivered after supervisor quiescence, resource return and
settlement (`src/dvergr/agent/program.clj:1945-1985`); the four observations replace it.

**Certification.** Candidate execution evidence is sealed before verification; verifier execution has
separately attributed effects and evidence. The certification manifest identifies the included Run
identities and child-Run relationships, effect-admission sequence bounds, artifact identities and
verifier evidence; a sealed admission index independently determines which effects must appear, and
certification checks that every required entry has an explicit disposition (pending and unknown
included). An Attempt records validator, program/schema identity, evidence digest, assumptions and
claim scope; runtime receipts alone do not imply semantic verification. Publication seals and roots
the combined manifest; evidence derived from work state is copied into control-owned immutable
artifacts before the work branch can be discarded; later external outcomes are append-only records
linked to the Attempt and never rewrite it. An Attempt also records its certification strength
(runtime evidence, test oracle, or mechanised proof) so runtime evidence is never mistaken for a
semantic guarantee.

**Evidence retention.** A published Attempt roots its certification manifest and referenced evidence
until an explicitly authorised retention-release operation (O10). Pending settlement and
external-effect recovery independently root every record their reconciliation needs: admission
indexes, dependency mappings, settlement witnesses. Required roots are acquired before publication or
work-branch release; transfer between owners leaves no retention gap; releasing an Attempt's
retention does not release records still needed by recovery.

| Law | Statement | Status |
|---|---|---|
| X1a | Run lifecycle publication to the store contains none of the checked live-handle keys. | holds (`test/dvergr/agent/run_test.clj:40,238`) |
| X1 | Lifecycle records are recursively portable data and survive restart of a durable backend. | target |
| X2a | A closed admission rejects `hire!` before anything is posted. | holds (`test/dvergr/agent/run_test.clj:189`; `test/dvergr/agent/program_test.clj:2071,2089`) |
| X2 | No child effect occurs before the admission partial order completes (F6), including ambiguous funding and cancellation. | target |
| X3 | An ignored owned child still delays the parent's resource settlement; a race cancels and settles its loser; passive observers cannot cancel. | holds for owned-Run paths (`test/dvergr/agent/program_test.clj:477,2124,2167,2194`) |
| X4 | Every fresh `hire!` produces a hot snapshot fork of the caller's immediate world (F2, F3); every resume produces a warm world from a savepoint with recorded provenance. | target (*current:* `:forkable-components #{}`, `src/dvergr/agent/world.clj:55`) |
| X5a | On the tested evaluation paths, merge before trusted scoring is refused and a timed-out candidate quiesces before certification. | holds (`test/dvergr/agent/evaluation_test.clj:266,304,912`) |
| X5 | Certification waits for or refuses on physical exit of every native worker class. | target |
| X6a | A certified Attempt is canonical, immutable and Run-backed in the memory store. | holds (`test/dvergr/agent/attempt_test.clj:64,75`) |
| X6 | The certification manifest matches the sealed admission index, its evidence stays rooted as the retention rule states, and pending outcomes are explicit. | target |
| X7 | A resumed Run has a new identity, reuses only matching known results and never resends an unknown non-idempotent effect automatically. | target |
| X8 | Every supported product entrypoint runs as a durable Run through the common admission, ownership and effect path. | target; definition needed: versioned entrypoint inventory (*current:* tau2 episodes fork directly, `benchmarks/src/dvergr/benchmarks/tau2/episode.clj:933`) |
| X9 | A cancellation request never counts as physical completion; until completion, ownership is kept and success certification is refused. | target |
| X10 | A value returned across a settled-world boundary carries no mutable handle or closure without a declared ownership policy. | target |
| X11 | Child authority is never broader than the current delegation; revocation applies at dispatch and acceptance. | target |
| X12 | The four observations have distinct outcomes and completion conditions; child execution and immediate-parent acceptance do not await ancestor settlement or external-effect completion; external-effect completion remains conditional on eligibility and endpoint and recovery progress. | target |

## 9. Contract decisions

| Id | Question | State |
|---|---|---|
| O1 | Owner of cuts and settlement recovery | decided: Spindel |
| O2 | Recursive `hire!` | decided: call-site suspension inside `spin`; blocking use refused |
| O3 | Which effects may run during speculation | decided: by world kind and recipient (§5 table), installed as world-bound default handlers |
| O4 | Compatibility | decided: no API compatibility; one migration tool for organisational state and balances; old Runs/Attempts/benchmarks exported read-only as an evidence archive |
| O6 | Durability envelope | decided: one daemon process per installation is the execution authority with one control ledger; kabel clients are replicas; a benchmark JVM with its own dir is its own installation; remote workers later behind fencing |
| O8 | Rooms | decided: root worlds or snapshot forks at creation |
| O10 | Attempt evidence retention | decided: no implicit expiry in the core. Manifests and control records are retained; products may explicitly release large evidence (e.g. at experiment teardown) only after other retention obligations are satisfied; discarded-fork bases are released after required certification evidence is independently rooted and no successor, settlement or recovery operation needs them; released evidence is marked unavailable, and a retained digest does not preserve its verification content |
| O9 | Unrelated discarded partitions | decided: conservative initial policy (the whole originating contribution); the permissive rule only with trusted dependency tracking |
