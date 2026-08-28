# Agent programs in the room REPL

Status: first provider-free slice implemented. The data model and Run boundary
are intentional; the built-in interpreter currently supports only deterministic
`:echo` and `:scripted` programs while the native LLM/tool interpreter is being
lifted onto the same contract.

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
because it immediately posts the precise trigger, admits a durable
`:agent-task` Run, and starts its Spin in the current Room. Admission is
synchronous and durability-first: once `hire!` returns, `observe` and `cancel!`
can address the Run without a startup race.

Use `await` on `(agent/result-spin handle)` inside `spin`. The opaque handle does
not pretend to implement Spindel's graph protocol; exposing the native Spin
preserves structured cancellation and dependency tracking in the owning fork.
Dereference is available at a REPL or test boundary, but workflow
code should stay compositional. A top-level blocking `await` shim is not
required.

The handle supports:

```clojure
(agent/run-id handle)        ; durable UUID
(agent/result-spin handle)   ; fresh native observer Spin for await/race/parallel
(agent/observe handle)       ; durable Run projection
(agent/cancel! handle)       ; targeted cooperative cancellation
```

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

## Current boundary and next interpreter

The deterministic interpreter proves the bounded programming contract without an LLM:
parallel composition, targeted cancellation, exact trigger/output correlation,
and room-scoped execution all run through the same SCI surface an agent uses.

The next interpreter should lift Dvergr's existing agent-turn core rather than
wrap another harness. It must preserve:

- Dvergr-owned ChatContext assembly and compaction;
- the room-bound SCI sandbox and forked Geschichte/Datahike workspace;
- authoritative tool allowlists and approval boundaries;
- native API, Codex subscription, Claude Code subscription, and local providers;
- Run-correlated activity/output and resource accounting;
- blocking provider I/O off the Spindel drain thread;
- pure scope attenuation before execution.

`:roster/scope` is currently portable policy data, not an enforced resource
grant. Until a Kontor-backed split/settlement interpreter lands, the
provider-free program kinds are deliberately the only accepted kinds. This keeps
the public boundary honest: declaring a budget or authority in data must not
pretend to enforce it.
