# dvergr Documentation

New to dvergr? Start with **[Getting Started](getting-started.md)**.

## 🚀 Getting started

- **[Main README](../README.md)** — project overview + quickstart
- **[Getting Started](getting-started.md)** — prerequisites, first-room tutorial: REPL + CLI paths, tagged messages, streaming
- **[Configuration](configuration.md)** — config file, env, secrets, skills and agent definitions
- **[Homes](homes.md)** — the directory a process keeps its state in: layout, moved vs copied homes, experiment homes, storage GC
- **[Provider & model setup](provider-setup.md)** — API keys, providers, the `models.edn` registry
- **[CLI reference](cli.md)** — `dvergr-cli`: flags, persistence, telemetry
- **[MCP](mcp.md)** — dvergr as an MCP server: the relay, HTTP, profiles and toolsets, what each tool promises
- **[Web dashboard and JSON API](web.md)** — the dashboard, room apps, `/api/v1`, the loopback stance
- **[Testing](testing.md)** — running the suite, how CI splits it across containers, waits that hold, the REPL meta-harness contract

## 📚 Concepts

- **[Programming Model](programming-model.md)** — Rooms and Participants on a Bus, addressing, forks; tagged messages, capability routing, escalation; why "discourse"
- **[Architecture](architecture.md)** — the L0–L7 layer map, subsystem graph, inbound message flow, per-namespace table
- **[Runs](runs.md)** — durable execution identity, kinds, lifecycle and reasons, how a turn ends, cancellation, processes, resuming from savepoints
- **[Agent programs](agent-programs.md)** — immutable specialized-agent rosters, Run-backed hiring, Spindel composition, and fork-safe state placement
- **[Participant preparation](participant-preparation.md)** — supervised off-engine initialization, interpreter expansion, cancellation and native lifecycle ownership
- **[Worlds, forks and state](worlds-and-forks.md)** — where every kind of state lives, what a fork forks, the fork contract, worlds, savepoints and budgets
- **[Effects](effects.md)** — one boundary between sandbox code and the world: handlers, faults, recording, replay, secrets at the boundary

## 🔧 Reference

- **[Tools & the SCI sandbox](tools-and-sandbox.md)** — the tool registry + the sandbox agents run code in, and its safety boundaries
- **[Media — voice, vision & files](media.md)** — speech-to-text across every frontend, image describe/extract, document text, the `/drive` mount
- **[Channels — Telegram](channels.md)** — bridging a chat surface into Rooms: setup, allowlist, voice/documents, slash commands
- **[Scheduling](scheduling.md)** — per-room recurring / one-shot tasks, incl. deterministic `:code` schedules

## 📏 Evaluation and benchmarks

- **[Workflows defined in a room](room-workflows.md)** — a room's own benchmark: bundles, checkers, calibration, case packs from your own history, `room-run`
- **[Benchmarks](benchmarks.md)** — the catalog (tau2-bench's four domains, BFCL, AutomationBench, BIRD, SpreadsheetBench, the wiki, room workflows), the provider contract, results
- **[Running benchmarks](running-benchmarks.md)** — how to run one, what its report means, resume, faults, cost at list price, held-out discipline
- **[Evaluation model](evaluation-model.md)** — Attempts, Evaluators, Experiments, Scorecards
- Per-benchmark notes in [`benchmarks/doc/`](../benchmarks/doc/):
  [tau2-bench](../benchmarks/doc/tau2.md), [BFCL](../benchmarks/doc/bfcl.md),
  [BIRD](../benchmarks/doc/bird.md), [SpreadsheetBench](../benchmarks/doc/spreadsheetbench.md),
  [AutomationBench](../benchmarks/doc/automationbench.md), [the wiki](../benchmarks/doc/wiki.md),
  [coding repair](../benchmarks/doc/coding.md), [frozen-web discovery](../benchmarks/doc/discovery.md)
- **[Reference setups](../benchmarks/reference/README.md)** — BIRD and SpreadsheetBench as their authors run them, on the cases a Dvergr experiment ran, and how to pair the two

## 🗂 Design history

[`design/`](design/) keeps proposals and plans as they were written, each with a status line
saying what was built and where the as-built page is:
[recursive work orchestration](design/orchestration.md),
[certified conversational episodes](design/conversation-evaluation.md),
[resuming Runs: the proposal](design/run-resume.md),
[one algebra for worlds, savepoints and budgets](design/unified-worlds.md),
[forks and worlds: use cases and migration](design/fork-and-world-migration.md).

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
