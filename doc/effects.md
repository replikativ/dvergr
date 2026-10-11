# Effects: one boundary between sandbox code and the world

Status: **implemented** (`dvergr.effects`; the design was agreed 2026-09-27, decisions at
the end). Built in steps, each noted where it lands; grants (eacl) are still open.

## Why

Code in a room's sandbox (an agent's, or an MCP client's `clojure_eval`) reaches the world
through the capabilities dvergr injects: files, HTTP, databases, rooms, models, mail,
schedules, processes. Today each capability guards and records itself, or does not. One
boundary through which every effect passes gives, as ordinary handlers instead of per-feature
code:

- **authority**: the same answer to "may this subject do this to that object" everywhere,
  shared with simmis (`is.simm.model.access/can?`, eacl behind it later);
- **a read-only eval that is honest**: "deny every effect that writes or reaches out" holds
  because nothing effectful bypasses the boundary;
- **receipts for every effect**, not only HTTP: the audit trail, and the log that replay,
  claim-level citations and training data need;
- **replay**: answer effects from a recorded log (HTTP fixtures, generalised), for
  reproducible Attempts;
- **fault injection**: a benchmark knob ("10% of writes fail", "the API rate-limits") for
  robustness;
- **preflight**: record what code would do, without doing it, for approval.

## What exists (inventory, 2026-09-27)

Guards: HTTP domain policy (`dvergr.sandbox.ns.io/make-domain-policy`), SSRF guard, secret
substitution and scrubbing, the sensitive-path policy and path clamps, muschel shell permits,
the dependency approval gate (`dvergr.sandbox.deps`), the host-namespace mirror allowlist, the
JVM class allowlist (`lock-interop!`), the delegation ceiling for `hire!`/`run-experiment!`,
the certified-evaluation write guard, self-delete/self-close guards, memory/timeout limits.

Records: acquisition receipts (HTTP only, only with a control-room store and a Run); tool-call
records (one per tool call: a `clojure_eval` is one record, the effects inside it are not);
an IO audit log that is built and then discarded (`rebind-working-ctx!`).

Where a boundary goes: nearly every capability is injected by
`dvergr.sandbox/setup-agent-namespaces!` (plus `add-bash-ns!`, `add-media-ns!`,
`add-process-ns!` from `rebind-working-ctx!`, consumer injectors, and `dvergr.mcp.repl`);
registry tools pass `dvergr.tools/execute`, which already has an allowlist and a per-tool
`:authorize` hook.

## Hardening first (independent of the boundary)

The inventory found holes that are bugs today. They are fixed before the boundary, in their
own PR, each with a test that fails first. **Fixed** (`test/dvergr/sandbox/hardening_test.clj`):
1, 2, 3, 4, 6, 7, 8; 5 is the boundary's receipt stream (below).

For 7 and 8 the working context carries the acting identity (`:agent-id`, from the agent the
context was built for; MCP connections act as `:mcp/<profile>`) into the sandbox's world
binding, where code cannot change it. `post!` authors as that identity (`:sandbox` when there
is none) and refuses `:from`/`:source-user…`. An agent changes its own actor row and those of
agents it spawned (`:spawned-by` in their config), cannot re-spawn an existing id or register
a human, settles tasks assigned to or dispatched by it or posted in its room, and rewrites
only its own prompt. The host and MCP connections keep their reach.

1. `clojure.data.xml/parse` of a non-string (a `java.net.URI` is an allowed class) runs the
   host's `slurp`: a network and file read past every guard. Accept strings and readers the
   sandbox produced only.
2. File tools without a virtual workspace accept absolute host paths with no clamp or
   sensitive-path check (`tools/tool-path`); `clj_kondo` reads host paths. Clamp to the
   workspace root like the sandbox's own `fs-safe-resolve`.
3. `add-tap`/`remove-tap` register sandbox fns in the JVM-global tap set: a side channel
   across sandboxes. Withhold them (keep `tap>`).
4. `llm/*`, `vision/*`, `doc/*` call providers without the budget check and token accounting
   the `llm_call` tool has. Route them through the same accounting.
5. The IO audit log is discarded; keep it on the chat context (it becomes the receipt stream
   below).
6. `processes/directive! :extend-budget` lets an agent extend its own budget; only a
   supervisor or the room's owner may.
7. `post!` accepts `:from`, `:source-user…` from the caller: authorship is spoofable. The
   runtime sets them from the acting identity.
8. Global writes with no guard: `update_agent_profile` (any agent's prompt), `dvergr.actors/*`
   and `dvergr.tasks/*` (system DB). Scope them to the acting agent and its rooms until the
   authority handler decides them.

## The boundary

An **effect** is a map describing what code asks the world for:

```clojure
{:effect   :http/request            ; namespaced kind, from a closed vocabulary
 :class    #{:network :egress}      ; what it touches: :read :write :network :egress :spend
                                    ; :process :lifecycle :schedule :global
 :resource {:url "https://…"}       ; the object acted on (a room, a path, a URL, a KB…)
 :subject  {:party … :run … :agent …}   ; who, from the runtime, never from the caller
 :room     :r  :world "…"           ; where the code runs
 :request  {…}}                     ; the arguments, canonical (hashable for replay)
```

Each injected capability becomes a thin `perform` of its effect; the implementation that does
the work is the **executor** at the end of a handler chain. The chain is configured per
evaluation (per Run, per MCP connection), not per capability:

1. **Admission**: is this effect kind within what the Run or connection was granted? (The
   Run's tools and effect classes; the connection's selection.) Deny by default.
2. **Authority**: `can? subject action resource`, the one predicate shared with simmis
   (`is.simm.model.access`). First over the relations that exist (party → room → grant →
   system); eacl on Datahike behind the same seam later. Cross-room effects (`fork!`,
   `merge!`, `post!`, `messages` on another room) are decided here instead of being open.
3. **Resources**: effects of class `:spend` reserve and debit (Kontor): model calls, and
   eval CPU/wall time as a recorded resource (not charged; see metering).
4. **Containment**: the guards that exist (domain policy, SSRF, secrets, path clamps, shell
   permits) move behind the boundary unchanged.
5. **Mode**, then the executor:
   - `:live` executes;
   - `:read-only` denies every effect whose class is not `#{:read}`;
   - `:replay` answers from the effect log by `(effect, canonical request)`, and fails a
     request it has no answer for (a replay that diverges says so);
   - `:faults` executes, then (or instead) returns a failure drawn from a per-effect
     distribution: error, timeout, rate limit, truncated or corrupted result; seeded, so a
     faulty run is reproducible;
   - `:preflight` executes nothing: reads may be served from a snapshot or a stand-in,
     writes and egress return a stand-in result, and the effect is recorded as planned.
6. **Receipt**: every effect, allowed or denied, is recorded: the effect map, the decision
   and its sources (which handler decided), timing, and a reference to the result. This
   generalises the acquisition receipts and replaces the discarded audit log; tool-call
   records reference the receipts of the effects inside them.

The receipt belongs to the runtime, not the capability: an SCI function cannot fabricate the
authority it ran under (as simmis's `doc/tool-authorization.md` requires).

### Effects and handlers (as built, `dvergr.effects`)

The chain above is a stack of **handlers** in the algebraic-effects sense. An effect is data
(an operation from the closed vocabulary, with a malli signature for its resource and result);
a capability performs it with `perform!` and the function that does the real work, which is
the innermost handler (the world). A handler is `(fn [effect next] value)` and does one of three
things: **answers** with a value (replay, a fault, a preflight stand-in), **refuses** by
throwing, or **forwards** with `next`, doing something around it (receipts, metering,
containment). The receipts handler is outermost and records every effect with who decided or
answered it.

Handlers resume once and at once (tail resumption), so no continuation is captured; SCI code
is not CPS-transformed. Resuming later (an approval) parks the calling thread on a deferred.
Resuming several ways (a counterfactual: "what if this search had returned that") re-executes
against the receipt log: the log is the trace, replay reproduces it, a fault is an
intervention on it. Code running inside spindel spins could later get the same handlers with
real continuations, without changing capabilities.

**Configuration is data and composes algebraically.** A world's handlers live in its binding
(`:effects {:handlers [[:admit #{:read :network}] [:read-only]]}`), set by the runtime and
unreachable from code; they fork with the world. A refusing handler is a filter `admit S`
(`read-only` is `admit #{:read}`); filters compose by intersection, so they commute, are
idempotent, and admitting every class is the identity. `normalize` gives the canonical stack:
receipts, one filter, then answering handlers in the order given, then the world. `compose`
concatenates and normalizes; it is associative with `[]` as identity. A rebind or fork
composes its handlers onto the world's, so authority narrows and never widens (attenuation,
as in simmis's delegation). These laws are test.check properties
(`test/dvergr/effects_test.clj`).

**Landed** (step 2): the vocabulary, `perform!`, receipts (reads by digest; subject = the
acting identity), the filter (admission and `read-only`), receipts kept on the working
context (hardening 5). Routed: sandbox files (physical and virtual), git, HTTP, room ops
(reads, post, join, create, fork, merge, discard, delete), schedules, the shell, process
directives, model calls from code (`llm/*`, `vision/*`, `doc/*`), database transacts and
database creation/deletion. Eval metering: every eval
reports thread CPU and wall time (`:meter`), and `clojure_eval` records it as an `:eval/run`
receipt (recorded, not charged).

Also routed (read-only everywhere, 2026-10): every writer an agent reaches, at the shared
gate of its capability rather than per call site.

- **Registry tools** (`dvergr.tools/execute`): every tool carries `:effect`, stamped from
  `tool-effects` onto the built-in definitions (so a classification belongs to an
  implementation, not a name) and declared by tools defined elsewhere. It is the tool's effect
  (a function of the input, decided before it runs), `:inner` (its effects pass the shared gates
  inside it), `:eval` (`clojure_eval`: inner in the sandbox; under `:isolation :native` the eval
  itself is `:eval/native`, which carries every class) or `:reads`. A tool with no
  classification, or one whose function returns no effect, runs as `:tool/call`, assumed to
  write and reach out; a test fails on any registered tool without one.
- **Workspace files**: `dvergr.tools/workspace-write!` is the one gate for `write_file`,
  `edit_file` and `clojure_edit` (an `:fs/write` with its size, as a sandbox `spit`); a caller
  without a chat passes its own boundary as `:effect-boundary`.
- **Databases**: the tools' transacts (`task_create`, `task_update`, `knowledge_add`) are
  `:db/transact`, as the sandbox's `datahike.api/transact` is.
- **The global registry**: actor rows and a prompt (`dvergr.actors/*`, `update_agent_profile`)
  are `:actor/write`, task settlement and skill dispatch `:task/write`, both `#{:write :global}`
  (a dispatch also `:network :egress`: a transport may deliver it);
  skill files (`dvergr.skills/author!`, `lift!`, `promote!`) are `:fs/write` into the room repo.
  The ownership checks (hardening 8) still run, inside the effect.
- **Runs**: `dvergr.agent/hire!`, `run-experiment!`, `spawn_agent`, `propose_change` are
  `:run/start` (`#{:lifecycle :spend}`), `cancel!` is `:run/cancel`.
- **Room GC** (`dvergr.room/gc!`) is `:room/gc`; **dependency loading**
  (`clojure.repl.deps/add-libs`, `sync-deps`) is `:deps/add` (`#{:network :global}`); **mail
  sync** (`intake.mail/sync!`, `mail_sync`) is `:mail/sync`, and a mail read that first opens
  the local store (creating it, writing the account row) is `:mail/open`; channel tools are
  `:channel/call`; `llm_call` and the schedule tools perform `:model/call` and
  `:schedule/*` like their sandbox counterparts; `clj_kondo` lints without its cache, so it
  only reads.
- Not effects: the runtime's own provenance records, written whatever the code asked for, as
  receipts are: an `inspect` observation receipt and a Run's causal edge when a result is
  awaited. Structural cancellation of a Run the agent owns (the losers of a race or timeout,
  a cancelled parent's children) is the runtime retiring a Run it already admitted, not a new
  effect; an explicit `dvergr.agent/cancel!` is `:run/cancel`.

Not routed, and why: database queries (a query reads an immutable value of a database the
world already holds); the mailbox is a connection handle (reads).

**Authority** (step 3, `dvergr.authority`): `can? relations subject action resource`, shaped
like simmis's `is.simm.model.access/can?` and pure over a relations value (the room registry's
fork/nesting parents and participants), so eacl can answer behind the same signature later.
Actions as in simmis, weakest first: `:read`, `:write` (including writing a fork), `:merge`,
`:admin` (discard, delete). An agent may read, write and merge on its own room and everything
beneath it (forks and nested rooms, transitively), discard or delete what is beneath its room
(never the room itself), and read and write rooms it takes part in; anything else is denied
until grants exist. Re-parenting needs `:admin` on the room moved, so it cannot be used to gain
authority. The authority filter is a **predicate filter**: it composes by conjunction (once is
enough) and sits after the class filter; the runtime adds it for every agent subject, so no
configuration removes it; MCP connections and the host keep their reach. Laws (the action
ladder, monotonicity in relations, authority carrying down the tree) are test.check
properties (`test/dvergr/authority_test.clj`).

An agent that creates a room keeps it by taking part in it (`:agents`) or nesting it
(`:parent-id`) under its own.

**Record, replay, faults** (steps 4 and 5): answering handlers. Their configuration is data
(`[:replay {:id …}]`); the state they need (a recording, a replay cursor, fault counters) is
host-side, created before the run (`recording!`, `replay!`, `faults!`) and unreachable from
code. `[:record]` appends each effect's key (operation, resource, optional finer `:key`) and
its result or error; `[:replay]` answers each effect with the next recorded entry for its key
and never reaches the world, reporting divergence when asked for something unrecorded;
`[:faults]` fails a `rate` of the effects in scope with a fault drawn from `kinds` (`:error`,
`:timeout`, and for HTTP a 429 or 503 answer of the operation's shape), as a function of
`(seed, key, occurrence)`. Answering handlers do not commute (a recording outside faults
records them; inside, only what reached the world). Laws as properties: replay reproduces a
recording without the world; rate 0 is the identity; the same seed gives the same faults.

**World configuration**: a world (a Room's execution context) holds handlers every sandbox
in it runs under, outside each capability's own (`install-world!`, `world-handlers`); forks
inherit them and may add their own without touching the parent. An EnvironmentDef's
`:world :effects` installs them in each Attempt's isolated world (doc/benchmarks.md).

**Quotas**: `[:quota {:id …}]` (host state from `quota!`) counts the `:bytes` of every write
effect (file writes carry their size, database transactions their printed size) and refuses
the write that would exceed the budget, receipted `:by :quota`; reads are free. An
EnvironmentDef asks for one with `:world :effects {:quota-bytes n}`. A shell command's output
is bounded where it is written (`dvergr.intake.bash`), not after it was held in memory.

**Idempotency and durable effect logs**: every operation has a class (`effects/idempotency`):
`:idempotent` (performing it again leaves the world as once: reads, writes of given content,
model calls), `:compensable` (a known inverse undoes it: a move) or `:once` (never again
without confirmation: a post, a commit, a shell command, a transaction); HTTP by method.
Receipts carry it. Every Attempt's world has a receipt sink; the Attempt's metrics keep
`:effects {:count :denials :once :log}`, `:log` the receipts as a content-addressed artifact
of the control room. This is the input for trajectory export and for resuming a Run: from a
spindel savepoint, redoing only `:idempotent` effects, not by replaying a log (DeepSeek's DSec
dropped replay-based recovery; arXiv:2609.22978).

**Registry tools** that touch the workspace (`read_file`, `write_file`, `edit_file`, `glob`,
`grep`, `shell`) pass the same boundary as the sandbox: the chat's receipts and binding, the
world's handlers and sink, so read-only, quotas and denials cover them.

Trajectory export reads the effect logs: `attempt_export` (`dvergr.agent.trajectory`).

Resume from savepoints is built (doc/run-resume.md). **Next**: grants (eacl),
preflight.

### How modes answer the goals

| Goal | Mode / handler | Notes |
|---|---|---|
| Read-only eval | `:read-only` | honest because every effect passes the boundary; pure computation still runs |
| Permissions shared with simmis | authority | the `can?` seam first, eacl later |
| Metering | resources | eval CPU/wall and model tokens as ledger resources, recorded not charged |
| Reproducible Attempts | `:replay` | an Attempt's log replays it; divergence is reported |
| Robustness benchmarks | `:faults` | an environment knob like wiki-gen's noise, reported per Scorecard |
| Approval before running | `:preflight` | a plan of effects; control flow that depends on results needs recorded or typed stand-ins; beichte's static purity analysis proves parts effect-free without running them |
| Claim-level citations | receipts | "a citation counts only if the Run read the document" is a receipt query |
| Training data | receipts | a trajectory is the tool calls plus their effects and results |

### Spindel

Spindel's `spin` macro already has effects (`await`, `track`). Performing sandbox effects as
spindel effects, with the handler chain as the handler, would make modes compose per spin and
per forked world: a fork can run under `:faults` or `:replay` while its parent runs `:live`.
This is a later step and a spindel PR for review; the boundary is designed so the handler
chain can move there without changing capabilities.

## Relation to simmis

simmis's `doc/tool-authorization.md` already states the model this implements: a call runs
when dvergr admitted the tool to the Run, ReBAC admits the party on the object, Kontor admits
the resources, and the sandbox contains the implementation; delegation is explicit
attenuation (a grant to a Run is a subset of its parent's); every call has an authorization
receipt. The boundary is where those four checks meet for effects inside an eval, not only
for tool calls. The effect vocabulary and `can?` resource shapes are shared.

## Plan

1. **Hardening** (the list above), with failing tests first.
2. **Boundary and receipts**: the effect map, `perform`, the chain with admission,
   containment (existing guards moved behind it), `:live`, receipts; capabilities routed
   through it namespace by namespace, starting with network and files. Eval metering as a
   recorded resource.
3. **Authority and read-only**: the `can?` seam over existing relations, cross-room effects
   decided by it; `:read-only`. (Done; grants pending.)
4. **Replay** (and trajectory export from receipts). (Done: replay, and export with `attempt_export`.)
5. **Faults** as a benchmark knob. (Done: `:world :effects :faults`.)
6. **Preflight**, then beichte for static purity.
7. Later: the chain as spindel effect handlers; eacl behind `can?`.

## Decisions (agreed 2026-09-27)

1. **Vocabulary**: closed and namespaced, defined by dvergr only (`:http/request`,
   `:fs/read`, `:fs/write`, `:db/transact`, `:room/fork`, `:room/merge`, `:room/post`,
   `:model/call`, `:process/run`, `:schedule/create`, `:mail/send`, …), each with classes
   from `:read :write :network :egress :spend :process :lifecycle :schedule :global`.
2. **Cross-room effects**: an agent acts on its own room, forks of it, and rooms it
   participates in; anything else needs a grant (`can?`). The room's owner, and MCP
   connections within their selection, keep their reach.
3. **Receipts for reads**: every effect is recorded; reads compactly (kind, resource, a hash
   of the result, no body); full bodies for network reads and for Runs marked for replay
   (benchmark worlds).
4. **Now, before `can?`**: `post!` takes its author from the acting identity and refuses
   caller-supplied `:from`/`:source-user…`; `update_agent_profile` changes the acting
   agent's own prompt only; `dvergr.actors`/`dvergr.tasks` writes are limited to the acting
   agent and its rooms.
