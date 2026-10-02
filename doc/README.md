# dvergr Documentation

New to dvergr? Start with **[Getting Started](getting-started.md)**.

## 🚀 Getting started

- **[Main README](../README.md)** — project overview + quickstart
- **[Getting Started](getting-started.md)** — first-room tutorial: REPL + CLI paths, tagged messages, streaming
- **[REPL meta-harness contract](repl-contract-test.md)** — deterministic, provider-free end-to-end test
- **[Configuration](configuration.md)** — config + state layers, the `.dvergr/` layout
- **[Provider & model setup](provider-setup.md)** — API keys, providers, the `models.edn` registry
- **[CLI reference](cli.md)** — `dvergr-cli`: flags, persistence, telemetry
- **[MCP](mcp.md)** — dvergr as an MCP server: the relay, HTTP, profiles and toolsets, what each tool promises

## 📚 Concepts

- **[Rooms & Agents](rooms-and-agents.md)** — the core model: Rooms + Participants on a Bus, addressing (DM vs broadcast), forks
- **[Programming Model](programming-model.md)** — the compositional kernel: tagged messages, capability routing, dynamic subscriptions, escalation
- **[Recursive work orchestration](orchestration.md)** — Rooms, threads, tasks, executions, recursive Workrooms, and provider-neutral collaboration
- **[Runs](runs.md)** — durable execution identity, lifecycle subscription, correlation, and targeted cancellation
- **[Agent programs](agent-programs.md)** — immutable specialized-agent rosters, Run-backed hiring, Spindel composition, and fork-safe state placement
- **[Forks, worlds, and settlement](fork-and-world-model.md)** — the cross-project Yggdrasil/Spindel/Dvergr/Simmis fork contract, ownership laws, and use-case coverage
- **[Discourse Theory](discourse-theory.md)** — why "discourse": speech acts, theory of mind, and the Rational-Speech-Acts/FRP lineage (short, optional)
- **[Architecture](architecture.md)** — the L0–L7 layer map, subsystem graph, inbound message flow, per-file table
- **[State Model](state-model.md)** — the three-tier copy-on-write state + workspace model
- **[Effects](effects.md)** — one boundary between sandbox code and the world: handlers, faults, recording, replay
- **[Unified worlds](unified-worlds.md)** — one algebra for worlds, savepoints and budgets
- **[Resuming Runs](run-resume.md)** — resuming Runs from savepoints

## 🔧 Reference

- **[Tools & the SCI sandbox](tools-and-sandbox.md)** — the tool registry + the sandbox agents run code in, and its safety boundaries
- **[Media — voice, vision & files](media.md)** — speech-to-text across every frontend, image describe/extract, document text, the `/drive` mount
- **[Channels — Telegram](channels.md)** — bridging a chat surface into Rooms: setup, allowlist, voice/documents, slash commands
- **[Scheduling](scheduling.md)** — per-room recurring / one-shot tasks, incl. deterministic `:code` schedules
- **[Boundary secret injection](boundary-secret-injection.md)** — how an agent uses an API key it never sees (credential handling + the `:secrets` config)
- **[Process model](process-model.md)** — the pausable/resumable Process abstraction

## 📏 Evaluation and benchmarks

- **[Workflows defined in a room](room-workflows.md)** — a room's own benchmark: bundles, checkers, calibration, case packs from your own history, `room-run`
- **[Benchmarks](benchmarks.md)** — the providers (tau2-bench's four domains, BFCL, AutomationBench, BIRD, SpreadsheetBench, the wiki and room workflows), equivalence, results
- **[Running benchmarks](running-benchmarks.md)** — how to run one, what its report means, resume, faults, cost at list price, held-out discipline
- **[Evaluation model](evaluation-model.md)** — Attempts, Evaluators, Experiments, Scorecards
- **[Practical agent evaluation](practical-agent-evaluation.md)** — frozen-web discovery, citation verification, market evidence
- **[Coding benchmarks](coding-benchmarks.md)** — coding repair in the SCI workspace
- **[Conversation evaluation](conversation-evaluation.md)** — certified conversational episodes

## 🤝 Contributing

- **[CONTRIBUTING](../CONTRIBUTING.md)** — the L0–L7 layer convention, running tests, repo layout, commit style

## 🧪 Examples & notebooks

**Literate Clay notebooks** ([`notebooks/notebooks/`](../notebooks/notebooks/)) —
live-running, browsable at [replikativ.github.io/dvergr](https://replikativ.github.io/dvergr/);
build with `clj -M:clay -m notebooks.render`. Start with
[`getting_started.clj`](../notebooks/notebooks/getting_started.clj), then
`programming_model`, `humans_and_agents` and `agents_and_tools`.

**Runnable scenario scripts** ([`examples/`](../examples/)) — what the notebooks
import; run with `clj -M:examples -m <ns>` (or `-X:examples humans-and-agents/run`):

- `humans_and_agents.clj` — humans + scripted agents, background tasks, propose/accept-reject, substrate forks
- `scenario_manager_escalation.clj` — capability routing for `:escalation/budget` (no hardcoded managers)
- `scenario_streaming_partial.clj` — per-consumer buffer policy: sliding-1 UI vs fixed-buffer audit
- `scenario_auditor.clj` — `d/subscribe!` tag-watchers alongside an inbox

## See also

- **[Spindel](https://github.com/replikativ/spindel)** — the FRP runtime dvergr is built on
- **[Datahike](https://github.com/replikativ/datahike)** — the immutable Datalog database for conversation + knowledge
- **[Yggdrasil](https://github.com/replikativ/yggdrasil)** — copy-on-write branching across git + Datahike
