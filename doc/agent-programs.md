# Agent programs in the room REPL

Status: deterministic and native LLM/tool interpreters implemented. The data
model and Run boundary are intentional; simulation, replay, resource splitting,
and durable continuation are later interpreters over the same contract.

Dvergr agents can construct specialized workers and compose their executions
from the SCI REPL. The initial surface separates four concepts:

| Concept | Meaning |
|---|---|
| `AgentDef` | Immutable, versioned executable data: role, prompt, skills, tools, model policy, and program reference. |
| `Roster` | Immutable collection of AgentDefs plus portable defaults and scope data. |
| `Run` | Durable identity and lifecycle of one bounded execution in a Room. |
| `RunHandle` | Opaque process-local identity/control capability for a live Run; it exposes a native result Spin explicitly. |

An actor is durable identity; an AgentDef describes behavior; a Run is one
performance. They must not collapse into one mutable "agent object."

## Reactive process semantics

The primitive is a reactive process, not a chat turn. A model API exchange is a
discrete integration step inside one interpreter; a Run may instead be a single
calculation, a queued dialogue, a continuously updated monitor, a fork/join
workflow, or a probabilistic search. Room messages are the durable event source,
thread views select conversational context, and the Run is the causal/resource
lifetime that owns effects.

The current surface deliberately reuses Spindel's algebra:

| Composition | Conversational interpretation |
|---|---|
| `await` / join | depend on one or several completed computations |
| parallel Spins | gather independent evidence concurrently |
| `race` | take the first satisfactory branch and cancel losers |
| signals / reactive atoms | accumulate beliefs, memory, or workflow state with fork semantics |
| context fork | explore a copy-on-write alternative or particle |
| cancellation | withdraw attention and terminate owned effects |

Queueing, merging observations, switch-to-latest steering, approval gates, and
long-lived output/effect streams should be derived attention combinators over
the same substrate. They must not be encoded as special cases of a global turn
loop. `:llm` is the first convenient process interpreter, not the definition of
an agent.

## Construct and compose

Inside a room-bound agent sandbox:

```clojure
(require '[dvergr.agent :as agent]
         '[org.replikativ.spindel.spin.cps :refer [spin]]
         '[org.replikativ.spindel.effects.await :refer [await]])

(let [team (-> (agent/roster {:id :investigation})
               (agent/make-agent
                {:id :analyst
                 :skills #{:research}
                 :program {:kind :scripted :reply "evidence"}})
               (agent/make-agent
                {:id :reviewer
                 :skills #{:review}
                 :program {:kind :echo}}))
      analyst (agent/hire! team :analyst {:task "inspect the claim"})
      reviewer (agent/hire! team :reviewer {:task {:claim 42}})]
  @(spin
     {:evidence (-> (await (agent/result-spin analyst)) :run/value)
      :review   (-> (await (agent/result-spin reviewer)) :run/value)}))
```

`roster`, `make-agent`, and `revise-agent` have no bang because they are pure:
each returns a new value and leaves its input unchanged. `hire!` has a bang
because it admits a durable `:agent-task` Run, posts the precise trigger, and
starts its Spin in the current Room. Admission is synchronous and
durability-first: once `hire!` returns, `observe` and `cancel!` can address the
Run without a startup race. If trigger persistence or spawning fails, the
already-admitted Run is durably failed.

Use `await` on `(agent/result-spin handle)` inside `spin`. This is a passive
observer: several branches may await one Run, and cancelling one observation
does not cancel the shared work. Use `(agent/owned-result-spin handle)` for a
race arm whose loss should cancel the hired Run. The opaque handle does not
pretend to implement Spindel's graph protocol; the explicit distinction keeps
ownership and dependency tracking visible in the owning fork. Dereference is
available at a REPL or test boundary, but workflow code should stay
compositional. A top-level blocking `await` shim is not required.

The handle supports:

```clojure
(agent/run-id handle)        ; durable UUID
(agent/result-spin handle)   ; passive observer Spin; safe to share
(agent/owned-result-spin handle) ; owning race arm; losing cancels the Run
(agent/observe handle)       ; durable Run projection
(agent/cancel! handle)       ; targeted cooperative cancellation
```

## Native model and tool programs

An `:llm` program lifts Dvergr's existing single-exchange model/tool core directly behind
the same Run boundary:

```clojure
(let [team (agent/make-agent
            (agent/roster {:id :research})
            {:id :analyst
             :prompt "Investigate the task and report evidence with caveats."
             :tools #{:clojure_eval :knowledge_search}
             :model-policy {:provider :codex-subscription
                            :model "codex-subscription-sol"}
             :program {:kind :llm
                       :max-model-steps 32
                       :budget-dollars 0.50}})
      work (agent/hire! team :analyst {:task "Test the strongest contrary case"})]
  @(spin (await (agent/result-spin work))))
```

Every hire opens a cheap isolated Run world. The orchestration graph, trigger,
tool-activity summaries, output, and durable Run projection remain in the parent
Room's control plane; database, Geschichte/filesystem, SCI, and nested-agent
effects resolve through the world's forked Room/context. Parallel hires therefore
have independent dialogue and budget state while starting from the same parent
substrate. The AgentDef tool set is both
the schema shown to the model and the authoritative execution allowlist. An
omitted tool set means no tools.

An LLM Run records a deterministic `:run/chat-id`. With a Datahike-backed Room,
that ID addresses the persistent model/tool trace; an ephemeral Room owns and
closes the per-Run chat database when the worker has physically terminated.
Automatic reconstruction of an interrupted live exchange is not implemented yet:
the durable trace supports inspection and a later explicit retry/resume policy,
not resurrection of native streams after process loss.

Blocking provider I/O runs off Spindel's drain thread. Dvergr owns prompt and
context assembly, compaction, tool dispatch, SCI isolation, cancellation,
activity messages, and Run settlement; providers—including Codex and Claude
subscription transports—only supply model responses.

`:max-model-steps` bounds automatic model/effect integration (default 32,
maximum 256). A step is one provider exchange, not a conversational turn and
not Dvergr's scheduling clock. Reactive attention may queue, merge, observe,
fork, or switch work around these discrete transport boundaries.
`:budget-dollars` is a per-execution spending ceiling (default $1), not a Kontor
resource allocation. When it is exceeded after a tool-bearing model step, the Run
settles as `:waiting` with reason `:budget-exhausted`; no process remains active,
and its partial world is retained for review.
A true resumable continuation and affine resource settlement are separate later
contracts.

## Execution and world settlement

Execution and settlement are independent durable axes. `:run/status` describes
what the program did (`:completed`, `:waiting`, `:failed`, or `:cancelled`).
`:run/settlement-status` describes what happened to its isolated effects
(`:merged`, `:review`, or `:discarded`). `hire!` accepts:

- `:settlement :automatic` (default): merge completed work;
- `:settlement :review`: retain completed work in the Room tree;
- `:settlement :discard`: execute for its result/trace, then drop its effects.

Failure and cancellation always discard the work plane. Waiting and automatic
merge conflicts retain it for review. A later merge/discard through
`dvergr.rooms.forks` updates the owning Run's settlement projection. World
settlement runs only after the executor and native-worker supervisor have
physically quiesced, outside the Spindel drain graph that used the fork.

Recursive execution separates the durable control Room from the immediate world
parent. If root Run `A` hires child `B`, `B` is recorded beside `A` in the root
Room but its world is `fork(A-world)`, so automatic settlement changes `A-world`
rather than trunk. `hire-in!` is the host boundary carrying those two Rooms;
the SCI `hire!` closure and model-tool adapters resolve them automatically.

An LLM-created sandbox can still build and revise arbitrary immutable rosters,
but its `hire!` authority currently accepts only provider-free `:echo` and
`:scripted` children. This prevents a child from minting new provider spend,
tools, or recursive LLM work before Kontor can split a resource vector from the
parent Run. A top-level Room REPL has no such program-kind ceiling.

The `spawn_agent` and `propose_change` model tools are convenience adapters over
this same boundary. They construct a portable one-agent Roster, call `hire!`,
carry the current Run as explicit structural parent, and select `:automatic` or
`:review` settlement. The native-tool and SCI paths receive the same delegation
ceiling, so changing interface cannot mint provider authority. A trusted
top-level Participant/tool context may delegate an LLM child; a paid AgentDef
Run must first receive an eventual Kontor-backed provider allocation.

## Evaluation ladder

The API and the instructions that teach it are evaluated together at three
levels:

1. Deterministic law/contract tests exercise fork ownership, parallel join,
   race cancellation, admission, drain, authority, and durability without a
   provider.
2. Stub-model interpreter tests exercise prompt/context assembly, exact tool
   schemas, model-step continuation, activity correlation, budgets, and cleanup.
3. Opt-in model environments give the same room-REPL tasks to Codex, Claude
   Code, API models, and local models. A trusted host verifier scores the exact
   result plus durable Room/Run facts; model prose is never the reward source.
   Reports retain individual checks, binary reward, generated SCI, leaked
   Runs/resources, model steps, wall time, task version, and model. Token/cost
   receipts and a stable hash of the assembled system prompt remain reporting
   follow-ups.

The initial model-facing task set should include pure roster specialization,
parallel research/review and reduction, race-with-loser-cancellation, explicit
structural child Runs, delegation-ceiling recognition, and creation of a nested
durable Room. Later attention combinators add queue/observe/steer/switch tests.
Passing only one model is not sufficient evidence that the programming surface
is clear.

The first opt-in environments live in `dvergr.agent.program-bench` on the
`:dev` classpath. They run through the production prompt, provider, tool, SCI,
Spindel, and Run paths while retaining every generated `clojure_eval` call in
their reports:

```clojure
(require '[dvergr.agent.program-bench :as bench])
(bench/run-v1! :codex-subscription "codex-subscription-sol")
(bench/run-race-v1! :claude-code "claude-code-sonnet")

;; The generic entry point makes the task/version explicit.
(bench/run-environment! :programming/race-v1
                        :codex-subscription "codex-subscription-sol")
```

It is an explicit REPL benchmark rather than a CI test because it is
nondeterministic, needs local subscription authentication, and consumes model
resources. Its language and lifecycle contracts are duplicated as
provider-free deterministic tests. In the 2026-08-28 probe, Codex Sol and Claude
Code Sonnet each discovered the API, created and joined two child Runs, and
returned the expected value with two `clojure_eval` calls. Earlier attempts
exposed two real harness defects: the native interpreter omitted the shared
sandbox prelude, and Claude Code's own native tools shadowed Dvergr's supplied
tool protocol. The first race probe exposed a missing `:delay-ms` contract in
progressive help and a deeper cancellation bug: a cancelled nested graph could
also cancel/reap its own durable settlement Spin. Settlement now runs as a
detached process-local watcher after executor and native-supervisor quiescence,
and the exact SCI program is covered provider-free.

On 2026-08-28, after that correction, Codex Sol solved
`:programming/race-v1` in three model exchanges and Claude Code in five. Every
verifier check passed: exact `:fast` result, completed winner, durably cancelled
loser, structural parentage, completed root, and zero active Runs.

This is only the seed of a training environment, not yet a reinforcement
learning system. The intended general contract is: initialize a forked Room and
resource authority; run an AgentDef/workflow; verify durable effects and
artifacts with trusted code; settle a reward/resource vector; retain the trace
for replay, comparison, or learning. A durable environment definition should
eventually reference versioned verifier code stored with the room repository,
while Kontor receipts supply resource-aware objectives and constraints.

An explicit `:parent-run` on `hire!` records structural spawning. Causal
succession remains derivable through the new Run's trigger message and that
message's `:run-id`; it is not mislabelled as parenthood.

## Selection and specialization

AgentDefs are ordinary data, so roles and policies can be generated, selected,
revised, stored, reviewed, and passed into reusable workflow functions:

```clojure
(agent/select team {:skill :research})
(agent/select team {:skills #{:legal :research}})

(let [v1  (agent/lookup team :analyst)
      ref (agent/ref v1)
      v2  (agent/revise-agent team :analyst
                              {:prompt "Challenge the strongest contrary case"})]
  ;; A stale reference fails instead of silently running revised behavior.
  (agent/lookup v2 ref))
```

Portable data is enforced at construction. Functions, JVM atoms, provider
clients, connections, streams, and other live handles cannot be embedded in an
AgentDef or Roster.

## State and fork semantics

A Roster is a value, not a hidden registry. Thread it through functions or carry
it as a Spin result. Forked computations can therefore use different derived
rosters without synchronization or leakage.

If a workflow needs changing state, allocate it through Spindel's fork-aware
state vocabulary (signals or reactive atoms) in the relevant execution context.
Do not place semantic workflow state in a JVM atom, dynamic singleton, or
process-global registry. SCI Var forkability is a separate runtime project; do
not rely on a top-level `def` as the portable state boundary until that work is
complete.

A RunHandle is intentionally not durable state and should not be stored inside
an AgentDef. Its awaitable result/cache belongs to the Spindel execution graph;
provider streams and cancellation capabilities remain process-local. The
completion value and admission fence are allocated in the Room's Spindel
context, so those runtime values follow copy-on-write fork semantics. The live
Run registry is likewise an explicitly process-local control plane, not
workflow state: moving only one token into a forkable cell would not make native
handles serializable or restorable. Durable meaning belongs to the Room's
messages, Run projection, artifacts, and later
effect receipts. Rehydrate those facts and construct fresh live handles rather
than serializing a native handle.

A live handle is confined to the Spindel execution-context fork that created it.
Using it from a child or sibling fork fails explicitly: observe the durable Run
facts there, or start a branch-local Run. A future cross-context result bridge
must copy values deliberately rather than sharing continuations across forks.

## Current boundary and next interpreters

The deterministic interpreter proves the bounded programming contract without an LLM:
parallel composition, targeted cancellation, exact trigger/output correlation,
and room-scoped execution all run through the same SCI surface an agent uses.

The native `:llm` interpreter now lifts Dvergr's existing single-exchange core rather
than wrapping another harness. It preserves:

- Dvergr-owned ChatContext assembly and compaction;
- the room-bound SCI sandbox and forked Geschichte/Datahike workspace;
- authoritative tool allowlists and approval boundaries;
- native API, Codex subscription, Claude Code subscription, and local providers;
- Run-correlated activity/output and resource accounting;
- blocking provider I/O off the Spindel drain thread;
- delegation attenuation before child execution.

`:roster/scope` is still portable policy data, not an enforced resource grant.
The native interpreter enforces its concrete tool allowlist, model-step bound, and
ChatContext spending ceiling, but does not pretend those are an affine resource
split. A Kontor-backed admission/settlement interpreter should make declared
resource vectors executable before scopes can delegate assets to child Runs.
