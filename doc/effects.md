# Effects: one boundary between sandbox code and the world

Status: **design, for review** (2026-09-27). Nothing here is built yet except where a
section says so.

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
1, 2, 3, 4, 6. **Open**: 5 becomes the receipt stream of the boundary; 7 and 8 wait for the
decisions below.

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
   decided by it; `:read-only`.
4. **Replay** (and trajectory export from receipts).
5. **Faults** as a benchmark knob.
6. **Preflight**, then beichte for static purity.
7. Later: the chain as spindel effect handlers; eacl behind `can?`.

## Decisions for review

- The effect vocabulary and classes above (closed, namespaced).
- Cross-room effects: deny by default for agents, allow for the room's owner and for MCP
  connections by their selection? (The inventory shows most room ops reach any room today.)
- Receipts for reads: every read (complete, larger), or reads only where a mode needs them
  (replay, citations)?
- Whether hardening item 8 (global writes) waits for the authority handler or is scoped now.
