# Practical agent evaluation

Dvergr should measure complete useful work, not only code generation or clean
single-turn tool use. The same evaluation boundary must cover a simulated
workflow in CI, a frozen integration fixture during development, and a live
provider/service probe from the REPL. Only the effect interpreter changes.

## Workloads

The first portfolio follows workflows people run with general personal and
business agents such as Hermes:

| Workflow | Observable outcome | Required effects |
| --- | --- | --- |
| Daily operations brief | Accurate, delivered brief with evidence | schedule, intake, search, artifact, delivery |
| Revenue-risk review | Correct account actions and prioritization | CRM, support, calendar, delegation |
| Inbox/support triage | Correct state changes with no missed SLA | mail, ticketing, knowledge, approval |
| Competitive/market report | Supported claims and actionable positioning | web intake, browser, citations, report |
| Campaign operation | Approved variants and measured outcomes | analytics, content, publication, accounting |
| Incident response | Restored service and auditable changes | terminal, monitoring, notification, rollback |
| Document/spreadsheet work | Semantically and visually correct artifact | office tools, files, rendering, review |

Hermes' own documentation emphasizes scheduled briefs and monitoring,
cross-platform messaging, browser/terminal work, skills, memory, and delegated
research. Its implementation history also exposes useful reliability contracts:

- scheduled work needs preflight validation, exact attempt admission, overlap
  prevention, restart classification, and explicit delivery outcome;
- child process lifecycle is not the same as verified task success;
- gateway restarts must not silently interrupt message delivery;
- large reports need artifact/chunk semantics rather than transport truncation;
- broad tool registries need progressive disclosure and measurable context cost;
- credentials given to a sandbox need an egress/secret boundary rather than
  ambient host access.

Related references:

- [Hermes scheduled tasks](https://hermes-agent.nousresearch.com/docs/user-guide/features/cron/)
- [Hermes delegation](https://hermes-agent.nousresearch.com/docs/user-guide/features/delegation)
- [Hermes tools](https://hermes-agent.nousresearch.com/docs/user-guide/features/tools/)
- [Lifecycle versus delegated task outcome](https://github.com/NousResearch/hermes-agent/pull/68499)
- [Gateway restart and lost delivery](https://github.com/NousResearch/hermes-agent/issues/83683)
- [Tool-schema context overhead](https://github.com/NousResearch/hermes-agent/issues/6839)
- [Sandbox credential egress boundary](https://github.com/NousResearch/hermes-agent/pull/30179)
- [AutomationBench business environments](https://github.com/zapier/AutomationBench)

These are requirements to express through Dvergr's programming model, not a
reason to port another monolithic agent loop.

## Dvergr interpretation

```text
trigger stream
  -> work-admission policy
  -> Run in an isolated world
  -> capability-scoped effects
  -> durable semantic observations
  -> trusted verification
  -> Attempt / Episode
  -> settlement and delivery
```

A schedule tick, incoming email, user message, webhook, analytics update, or
model completion is an event. Spindel programs combine those events and effects;
there is no separate cron-agent, browser-agent, or business-workflow runtime.
Rooms hold discourse and durable control facts. Runs delimit causal execution.
Worlds isolate speculative effects. Kontor accounts for resources. Evaluators
certify outcomes independently of agent prose.

Machine, container, VM, managed browser, SaaS simulator, and frozen fixture are
world/effect interpreters selected by an EnvironmentDef. Cheap forked state is
the default. Stronger substrates are explicit resources used only when the task
requires their compatibility or isolation.

## Recursive visibility contract

An agent building a nested harness needs the same debugger as the host, within
its authority. `agent/inspect` therefore projects the ambient structural Run
subtree from durable facts. It includes a bounded Run tree, active frontier,
correlated message/activity summaries, failures, settlement, and available
resource balances. It excludes parents, siblings, hidden verifier state, and raw
tool arguments. The host REPL uses the same projection with a wider scope.

This snapshot is the first query. Reactive inspection should later be a Spindel
signal folding the same Run/message/resource facts, not a second event log.
Useful next fields are context composition/pressure, current effect and elapsed
time, world diff/artifact summary, delivery status, retry safety, and the reason
a Run is waiting.

## Evaluation ladder

Each practical EnvironmentDef should have four modes:

1. **Laws** — provider-free tests for authority, lifecycle, cancellation,
   conservation, idempotency, and visibility.
2. **Simulation** — forkable deterministic business state and stubbed effects;
   exact final-state assertions provide dense and binary rewards.
3. **Frozen integration** — recorded mail/web/CRM/browser inputs exercise real
   adapters without network drift or external writes.
4. **Live probe** — opt-in provider and service access from the REPL, with
   approvals and real resource accounting.

Failures are classified at least as setup/configuration, model/provider,
context/tool discovery, orchestration, effect execution, verification,
settlement, delivery, or external-system drift. A failed setup must not be
reported as deficient model reasoning.

The first environment, `:business/renewal-risk-brief-v1`, is deliberately small.
It requires a model to construct sales and support specialists, join evidence,
inspect its own execution tree, and produce an exact risk brief while unrelated
private work exists in the same control Room. Its deterministic SCI contract and
trusted model-backed verifier establish the recursive boundary before real CRM,
support, scheduling, or delivery effects are added.

`dvergr.agent.arenas.renewal` is the first consequential state-backed successor.
Its exact WorldSetup seeds an account and sales/support signals inside every
candidate Run world. A candidate must construct and hire both exact scripted
specialist-service fixtures, obtain their actual child Run results, and call the
semantic `renewal_plan` tool with the exact evidence IDs.
That tool validates the fork-local business state, consumes one conserved
`renewal-review` unit from the Run wallet, and writes the proposed intervention
only into the disposable candidate world. The trusted evaluator then checks the
plan, Run topology, causal observation, tool activity, resource receipt, and
returned result before discard settlement. Static prose cannot pass it.

The host composes it from ordinary definitions rather than a special arena
scheduler:

```clojure
(require '[dvergr.agent.arenas.renewal :as renewal]
         '[dvergr.agent.experiment :as experiment])

(renewal/register-tool!)
(renewal/provision-review-capacity!
 room {:id provisioning-event-id :amount candidate-cells})

@(experiment/run
  room roster experiment-def
  {renewal/verifier-ref (renewal/evaluator)}
  {:world-setups {renewal/setup-ref (renewal/world-setup)}})
```

The deterministic model contract exercises this complete path in CI. Live
Codex/Claude candidates can use the same EnvironmentDef, evaluator and setup;
only their AgentDefs and prompt policies differ. The scripted children measure
recursive construction, causal joining, world/resource ownership, and merge—not
specialist model reasoning. A later arena can replace them with paid specialist
models once nested provider effects debit conserved Run wallets.

The first native Codex-subscription probe of this arena exposed two benchmark
contract bugs before it passed. The portable task originally said only that a
specialist returned a signal and that the root returned a plan ID, while the
trusted verifier required full signal records and a `{:plan/id UUID}` result.
Those shapes now live explicitly in the task data rather than only in hidden
host code. The probe also showed that three provider exchanges and 30 seconds
were accidental turn-like limits: Codex was still inspecting the unfamiliar
SCI surface when the first attempt stopped. The environment now uses time and
resource limits as its work budget and retains sixteen model exchanges only as
a runaway fuse. With a 120-second limit, Codex queried the fork-local state,
authored and ran both specialists, submitted the exact evidence, consumed one
review unit, and passed the then-current fifteen trusted checks in eight model
exchanges. The current contract additionally verifies exact child AgentDef
identities; the historical probe has not yet been rerun against that revision.
This is one successful discoverability probe, not yet a model-quality estimate.
The same path is available from a fully initialized room REPL without exposing
the raw trace to evaluated agents:

```clojure
(require '[dvergr.agent.arenas.renewal :as renewal]
         '[dvergr.agent.arenas.renewal-bench :as renewal-bench])

(renewal/register-tool!)
(renewal/provision-review-capacity!
 room {:id (random-uuid) :amount 1})
(def report
  (renewal-bench/run!
   room
   {:id :codex-renewal
    :provider :codex-subscription
    :model "codex-subscription-sol"}))

(get-in report [:scorecard :scorecard/summary])
(:host/tool-trace report) ; host-only generated inputs and semantic outcomes
```

`run!` uses the caller's real Room and leaves its lifecycle with the caller. It
requires the semantic tool to be installed explicitly and never mutates the
process-global tool registry itself. It does not manufacture a lighter
pseudo-room or copy the experiment scheduler. Evaluation cleanup is joined
before it returns or throws, and the diagnostic trace is restricted to the
admitted root Run and its structural descendants.

The comparison unit is now explicit. A DatasetDef fixes a non-empty ordered set
of exact environments; an ExperimentDef fixes exact candidate AgentDef content
and repetitions. Host-owned admission policy caps total attempts and
parallelism. Its lazy Spindel execution produces the
ordinary certified Attempts and a deterministic Scorecard over the full paired
matrix. This supports repeated provider/model comparisons without giving
Dvergr a second scheduler or allowing evaluated SCI code to own trusted
verifiers. Jobs are realized only one bounded batch at a time, and batch
environments initially require discard settlement until partial experiments
have durable recoverable identity. Completed Scorecards are immutable durable
Room projections over the already-certified Attempts: the exact value lives in
the content-addressed artifact store, while Datahike indexes exact experiment,
dataset, candidate, and Attempt joins. Mandatory writer validation rejects
forged or mutable leaderboard rows. Incomplete batches leave their individual
Attempts but never publish a complete Scorecard; durable resumption of the
partial matrix remains a later execution policy.

An EnvironmentDef may also name an exact WorldSetup reference. The host
resolves that reference before admitting any experiment cell, while the actual
preparer runs separately for every cell inside its already-forked Run world.
This makes fixture construction part of the reproducible environment identity
without exposing live preparers or verifier authority to SCI. Shared substrate
provisioning—such as opening a Datahike/Kontor arena or a managed browser—stays
an outer resource lifecycle; fork-local scenario state belongs to WorldSetup.

The first provider-free room-REPL probe on 2026-09-04 executed two echo
candidates against two environments with two repetitions and parallelism two.
It produced eight distinct durable Runs, eight certified Attempts, two 4/4
candidate summaries, one content-addressed Scorecard, and zero active Runs at
completion. This validates the experiment composition itself; it is not model
quality evidence.

The first generic live migration used the same `:programming/join-v1`
EnvironmentDef to compare Claude Code and Codex subscription AgentDefs. It
immediately found two observable failures: retries left duplicate child Runs,
and the REPL's process worker inherited Spindel's drain marker, so the documented
`@(spin (await ...))` bridge rejected valid composition as a deadlock. Tool
activities now retain typed status and allowlisted diagnostic codes—but never
raw result content—making that class of failure visible in certified evidence
after an ephemeral Room closes. Clearing the drain marker and ambient Spin
identity at the real worker boundary restored the advertised algebra. With the same concise,
content-addressed effect-safety prompt, both providers then passed independently
in three model steps, with exactly two completed children, an exact result, and
zero active Runs. This is one smoke result, not a quality ranking; repetitions
and held-out environments are required for comparative claims.

The first repeated Codex subscription probe then ran
`:programming/self-programming-v1` twice through the Experiment API. Each root
used `clojure_eval` to author a Roster, hired three simulated specialists in
isolated worlds, observed their results through Spindel, and computed the exact
answer. Both Attempts passed all ten trusted checks in four model steps; the two
root Runs and six child Runs quiesced, and the resulting 2/2 Scorecard
round-tripped through the Room store. A deterministic model simulator now
exercises that same complete path in CI—from the native LLM/tool loop through
SCI-authored recursive hires, world settlement, trusted verification, Attempt
certification, and durable Scorecard. Building that contract exposed and fixed
an observation projection typo that had hidden `:run/world` identities, which
is precisely the kind of harness failure these paired probes are intended to
find.

## Port order driven by failures

1. Complete scoped observation and expose it in the REPL/UI.
2. Extend DatasetDef/ExperimentDef/Scorecard live runs beyond the migrated
   setup-free join/race/self-programming environments.
3. Build a forkable business-state simulator and import an AutomationBench
   smoke subset.
4. Add scheduled trigger/execution/delivery receipts using ordinary Runs.
5. Add browser/session ownership, cleanup, artifacts, and replayable frozen
   snapshots.
6. Add credential/egress capabilities for container and VM interpreters.
7. Add mail/CRM/calendar/support adapters where a benchmark demonstrates demand.
8. Add offline report judges and, later, randomized campaign/analytics outcomes.

Every port should arrive with a task that fails without it, a deterministic
contract where possible, and a paired model-backed measurement. Agent-authored
tasks and verifiers remain proposals until a trusted parent reviews and admits
them.
