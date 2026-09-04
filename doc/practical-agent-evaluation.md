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

## Port order driven by failures

1. Complete scoped observation and expose it in the REPL/UI.
2. Add DatasetDef/ExperimentDef/Scorecard projections and repeated paired runs.
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
