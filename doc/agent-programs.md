# Agent programs in the room REPL

Status: deterministic and native LLM/tool interpreters plus conserved resource
delegation are implemented. The data model and Run boundary are intentional;
simulation, replay, provider-usage debiting, and durable continuation are later
interpreters over the same contract.

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

Structured attention decisions now separate memory, activation, control, and
execution-boundary requests. Queueing, merging observations, switch-to-latest
steering, approval gates, and long-lived output/effect streams should be derived
work-admission combinators over the same substrate. They must not be encoded as
special cases of a global turn loop. `:llm` is the first convenient process
interpreter, not the definition of an agent.

## Admit activity over time

`hire!` starts one causally bounded Run. For a source that keeps producing work,
compose the same Run-producing or provider-free programs with `spindel.work`:

```clojure
(require '[spindel.work :as work]
         '[org.replikativ.spindel.spin.cps :refer [spin]]
         '[org.replikativ.spindel.effects.await :refer [await]])

(def revisions
  (work/latest
   (fn [request]
     (work/task
       ;; This body may await Spins, hire agents, query the room, or update
       ;; fork-local state. Every accepted request gets fresh Spin identity.
       (solve request)))))

(work/submit! revisions message-id request)
(work/close! revisions)
@(spin (await (work/completion revisions)))
```

Choose policy from semantics rather than from an LLM loop shape:

| Policy | Meaning |
|---|---|
| `latest` | New evidence supersedes active work; replacement waits for actual hand-back. |
| `serial` | Preserve every accepted value in FIFO order. |
| `busy` | Admit only while idle; explicitly suppress overlap. |
| `parallel` | Gather independent work up to a declared concurrency bound. |

Controllers expose a hot event stream (`events` plus `next-event`) and a
fork-local `snapshot`. `close!` drains accepted work; `cancel!` cancels owned
work; `completion` joins real quiescence. They are intentionally process-local
reactive machinery. Durable intent, Run identity, messages, and accounting stay
in the Room store. Reconstructing a controller after restart means replaying the
durable admitted intent under an explicit recovery policy, not serializing live
continuations.

Attention is the conversational policy layer above this mechanism. A policy may
remember an utterance without admission, enqueue it for serial work, or request
switch-to-latest integration at a supported execution boundary. That mapping is
an interpreter owned by the participant; message tags themselves never acquire
cancellation authority. Native LLM attention decisions are durable typed Room
projections rather than synthetic speech: `include` admits model input at the
next supported boundary, `remember` preserves awareness outside provider
context, and unsupported plans remain explicitly deferred for a future capable
interpreter. Stable decision facts and separate applied dispositions make crash
windows observable without pretending that replaying a model/tool effect is
always safe; recovery reconciles unapplied decisions with durable Runs first.

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

A recursive `hire!` is structurally owned by the current Run. Ownership does
not depend on retaining or awaiting the returned handle: parent cleanup, world
settlement, and resource return wait until every owned child is durably
terminal, and parent cancellation propagates to them. This prevents an ignored
child from returning resources into an already-settled parent wallet. Long-lived
ambient work will require an explicit detached/service operation with its own
durable owner and resource grant; dropping a handle is not detachment.

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
`:budget-dollars` is still a provider-loop ceiling (default $1), not itself a
Kontor debit. When it is exceeded after a tool-bearing model step, the Run
settles as `:waiting` with reason `:budget-exhausted`; no process remains active,
and its partial world is retained for review. Connecting measured provider usage
to the Run's conserved wallet is a separate next contract.

## Conserved resource delegation

When the Room has a Datahike resource store, `hire!` may atomically split a
positive resource vector from the current Run (or the Room root at top level):

First, a trusted host provisions the Room root with an idempotent receipt ID;
this API is deliberately absent from SCI:

```clojure
(require '[dvergr.resource :as resource])

(resource/mint! room
                {:id #uuid "3f1e13e2-f5ca-4f88-a847-f45978599d40"
                 :resources {"microUSD" 1000000}})
```

The stable `:id` is part of the effect protocol: retrying the same provisioning
request cannot mint twice. An operator may instead install and fund other
coordinates through the same trusted host boundary.

The room-bound SCI program can then inspect and split only its available
authority:

```clojure
(let [child (agent/hire! team :analyst
                         {:task "Inspect the evidence"
                          :resources {"microUSD" 250000}})]
  {:remaining (agent/balance)
   :result (await (agent/result-spin child))})
```

Coordinates are extensible strings; `"microUSD"` is merely the first installed
unit. Kontor records the allocation and return as balanced, idempotent receipts,
and its Datahike writer predicate rejects overdrafts or unreceipted changes.
Allocation happens after durable Run admission but before the trigger or any
program effect. If it fails, the Run is durably failed and no agent effect
starts. After all owned work quiesces, unused resources return to the structural
parent; recursive vectors therefore remain conserved across fork/join.

SCI exposes only `agent/balance` and the `:resources` argument to `hire!`. It
does not expose connections, minting, arbitrary transfers, or a way to redirect
`:parent-run`. Installation and minting remain trusted host operations.

## Execution and world settlement

Execution and settlement are independent durable axes. `:run/status` describes
what the program did (`:completed`, `:waiting`, `:failed`, or `:cancelled`).
`:run/settlement-status` describes what happened to its isolated effects
(`:merged`, `:review`, or `:discarded`). `hire!` accepts:

- `:settlement :automatic` (default): merge completed work;
- `:settlement :review`: retain completed work in the Room tree;
- `:settlement :discard`: execute for its result/trace, then drop its effects.
- `:settlement :deferred`: host-owned two-phase gate; retain the world but
  reject merge/adoption until the host explicitly releases it.

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
`:scripted` children. Generic resource vectors can already split recursively;
paid recursive LLM work remains closed until measured provider usage is debited
from that wallet. A top-level Room REPL has no such program-kind ceiling.

The `spawn_agent` and `propose_change` model tools are convenience adapters over
this same boundary. They construct a portable one-agent Roster, call `hire!`,
carry the current Run as explicit structural parent, and select `:automatic` or
`:review` settlement. The native-tool and SCI paths receive the same delegation
ceiling, so changing interface cannot mint provider authority. A trusted
top-level Participant/tool context may delegate an LLM child; a paid AgentDef
Run must first receive an eventual Kontor-backed provider allocation.

## Evaluation ladder

An evaluation environment is an immutable `EnvironmentDef`, separate from any
attempt to execute it:

```clojure
(agent/environment
 {:id :programming/review-v1
  :task {:artifact "candidate.edn"}
  :verifier {:id :checks/review-v1 :version 1
             :basis "git:<verifier-commit>"}
  :limits {:timeout-ms 120000 :max-model-steps 8}
  :world {:isolation :ctx :settlement :review}})
```

The value has a Hasch-derived `:environment/content-id`; map ordering does not
affect it, while changing the task, verifier reference, limits, or world policy
does. A verifier is deliberately a versioned reference rather than an embedded
function: untrusted SCI can author and fork definitions, but only the trusted
runner resolves exact verifier and world-setup references and computes reward.
The benchmark interpreter derives its timeout, cancellation grace, restrictive
model-step and dollar limits, settlement policy, and resource grants from that
validated definition. It rejects unsupported policy keys instead of silently
certifying a different scenario. In particular, world `:setup` remains closed
until the host supplies a versioned trusted setup resolver. Each attempt still
receives a fresh Run ID, so content identity never collapses distinct
executions. The current REPL benchmark reports retain both the exact definition
and its compact reference.

Every opt-in REPL attempt returns a content-addressed `:attempt-receipt`. It binds the
unique root Run to the exact EnvironmentDef, provider/model, exact assembled
system-prompt ID, per-resource usage, timing, trusted checks/reward, Run/tool
trace, and Kontor receipts when present. SCI does not receive the receipt
constructor or verifier registry, so an evaluated agent can propose an
environment but cannot certify its own reward.

When the Room has a store, trusted certification persists an immutable Attempt
before the world can become reviewable or be discarded. The typed Datahike row
indexes Run, environment, verifier, provider/model, status, timing, reward,
checks, prompt/program/interpreter provenance, and evidence references. The
exact EnvironmentDef, AgentDef, receipt, and portable evidence live together in
Dvergr's immutable blob CAS; the row holds the content reference rather than an
opaque EDN/JSON column. SCI can query this projection but its Datahike surface
rejects writes or retractions in the `:attempt/*` and `:attempt.check/*`
namespaces. A Hasch identity detects content mutation; it does not authenticate
an external writer, so cross-process imports require a trusted manifest or
signature before their rewards are admitted.

The reusable host boundary is `dvergr.agent.evaluation/evaluate`. It returns a
Spin rather than starting a second scheduler: when executed, it admits an
ordinary Run in an ordinary isolated RunWorld, waits for physical quiescence,
then invokes a version-matched host `Evaluator` to produce portable evidence,
checks, reward, and the receipt. Successful evaluation worlds may be retained
for review or discarded, but first settle as `:deferred`; ordinary merge and
adoption/discard reject that same world until trusted scoring atomically claims
release or trusted cleanup. Observer and verifier callbacks run on an external
worker rather than Spindel's drain path. Cancellation and certification race at
the settlement claim: a cancelled evaluation can never later make its world
landable, even if an uncooperative callback returns late. Certification failure
discards the uncertified world while preserving the Run ID in the structured
error. The evaluator capability contains functions and therefore remains
process-local and absent from SCI; SCI can author the portable EnvironmentDef
it names.

An Episode is a pure export/read projection, not another durable lifecycle:

```text
Episode = immutable certified Attempt
        + current durable Run settlement
        + referenced Runs/messages/activity/resource facts
```

Review may later merge or discard the Run world. That changes the joined Run
projection while the historical AttemptReceipt stays content-identical.
Dataset acceptance, train/eval splits, rankings, particles, and proposal status
are separate later projections; none mutate certification.

An ensemble is composition, not another primitive:

```clojure
(let [attempts (mapv #(evaluation/evaluate room team % environment evaluator)
                     candidate-agent-refs)]
  @(apply comb/parallel attempts))
```

Pure policy can rank/select the resulting receipts. Existing room-fork
merge/discard/adoption remains the only settlement authority. SMC, MCTS,
Anglican-style inference, and Raster training can consume this same boundary
without changing Run, Room, or world semantics.

For training, an accepted episode is a projection across the exact
EnvironmentDef, AgentDef/interpreter identity, Run/tool trajectory, conserved
resources, checks/reward, and world/memory basis. Raster can consume that
projection, while a `pretrained-rstr` git-like memory is simply another forked
system in the episode world. Neither training nor a particular inference
algorithm changes the execution algebra.

Dollar/token, elapsed-time, and conserved resource limits are the ordinary
governors. `:max-model-steps` remains only a high emergency fuse for a provider
loop that keeps returning tool continuations; it is not the conversational or
FRP unit of progress.

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
   Runs/resources, model steps, token/cost usage, wall time, task version,
   model, and a stable ID of the exact assembled system prompt. Durable receipt
   persistence, trusted-writer authorization, and settlement against a retained
   benchmark world remain follow-ups.

The initial model-facing task set should include pure roster specialization,
parallel research/review and reduction, race-with-loser-cancellation, explicit
structural child Runs, delegation-ceiling recognition, and creation of a nested
durable Room. Provider-free attention tests cover capability negotiation,
queue/observe/restart, run-local cancellation, and reactive boundaries;
`latest`/`serial`/`busy`/`parallel` work admission covers computation overlap as
a separate FRP layer.
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
(bench/run-resource-v1! :codex-subscription "codex-subscription-sol")
(bench/run-self-programming-v1! :codex-subscription "codex-subscription-sol")

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

The resource-delegation environment adds a Datahike/Kontor control plane. The
root Run receives a conserved vector, splits it between two specialists from
SCI, and joins their result Spins. Its trusted verifier checks the canonical
Kontor allocation and return receipts for every wallet edge, durable child
parentage and settlement, empty terminal Run wallets, the restored Room balance,
and global quiescence. In the 2026-08-30 Codex Sol probe it passed all checks in
three model exchanges. This evaluates affine delegation and return; it does not
pretend the provider call itself has been debited yet.

The self-programming environment asks the root model to construct two cheap
simulated particles plus an independent verifier specification, join all three
through Spindel, and interpret the verifier data as a pure check. Trusted host
code scores the exact answer, structural parentage, child completion and
settlement, durable causal observation of every child result, durable root
completion, and global quiescence. This deliberately
uses stubbed child effects: it tests whether a model can author recursive
orchestration before paid recursive LLM delegation is enabled. In the
2026-08-30 Codex Sol probe, the model discovered the programming API through
the REPL, authored and executed the three-specialist program, and passed every
check in six model exchanges and 31.823 seconds. The durable root's causal
inputs exactly matched the three child Runs.

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

The evaluation path follows the same placement rule end to end:

| State | Representation |
|---|---|
| workflow dependency, awaiting, cancellation | Spin nodes and continuations in the execution context |
| changing beliefs, populations, queues, or search policy | Spindel signals/runtime atoms in the execution context |
| Room, Run, message, ledger, and certified Attempt facts | the Datahike Yggdrasil system registered in that context |
| files and other world substrates | their registered Yggdrasil systems |
| Roster, AgentDef, EnvironmentDef, evidence, receipt | immutable values flowing through Spins |
| provider stream, native worker, verifier function, live Run/Fork handle | process-local capability, never semantic or portable state |

Consequently `evaluate` is a lazy Spin rather than another scheduler. It hires
an ordinary Run in a Yggdrasil-forked world, awaits that Run through Spindel,
and returns values. Certification writes one immutable Attempt into the
branch-correct Room store while the affine world-settlement gate is held.
`episode/export` is only a read projection joining that Attempt to current Run
facts; it introduces no lifecycle, clock, mutable cell, or settlement authority.

Probabilistic programs use the same placement rule. In SCI, Dvergr defaults
`infer/smc-infer`, `infer/importance-sampling`, and `infer/kernel-infer` to
Spindel's `:world-policy :fork`. Each particle and resampling descendant owns a
canonical Yggdrasil fork. Before a result crosses into SCI, Dvergr removes the
execution contexts and exposes only portable results, weights, statistics, and
world descriptors. `infer/predict` likewise receives values rather than native
contexts. For example:

```clojure
(spin
  (let [posterior (await (infer/smc-infer (scenario-model) 64))]
    {:values  (infer/values posterior)
     :weights (infer/log-weights posterior)
     :ess     (infer/ess posterior)
     :worlds  (infer/worlds posterior)}))
```

Pass `{:world-policy :fresh}` only when the model is proven not to touch Room,
Datahike, repository, accounting, or other registered world state. Inference
does not introduce an `AgentParticle` entity and does not turn every sample into
a Run. A Run remains the durable identity of an application-level computation;
particles are its internal execution placements unless the program explicitly
launches separately audited evaluations.

Particle-independent Metropolis-Hastings and particle Gibbs are not exposed in
SCI yet. Their repeated-sweep ownership and settlement contract must be made
canonical before they become part of this surface.

The current canonical fork covers Spindel execution state and every registered
Yggdrasil system. It does not yet fork mutable cells allocated inside SCI: a
captured SCI atom or Var is shared by model invocations. Treat SCI closures as
pure apart from the fork-aware operations above. Full interpreter-state
isolation depends on the forkable SCI runtime work and will strengthen this
contract without changing the portable posterior shape.

Small JVM atoms used inside a native-worker supervisor or verifier hand-off are
short-lived synchronization primitives for non-forkable capabilities. They may
decide which already-durable transition wins, but their contents are not the
workflow's recoverable meaning. If a value must survive a continuation, affect
branch semantics, or be available after restart, it belongs in Spindel state or
one of the registered Yggdrasil systems instead.

If a workflow needs changing state, allocate it through Spindel's fork-aware
state vocabulary (signals or reactive atoms) in the relevant execution context.
Do not place semantic workflow state in a JVM atom, dynamic singleton, or
process-global registry. SCI vars, atoms, bindings, and continuations now fork
with the interpreter world, so top-level definitions are valid transient
branch-local program state. They are deliberately discarded rather than merged;
durable or reviewable meaning still belongs in Room substrates and Run/effect
records.

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
