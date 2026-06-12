# dvergr Documentation

New to dvergr? Start with **[Getting Started](getting-started.md)**.

## 🚀 Getting started

- **[Main README](../README.md)** — project overview + quickstart
- **[Getting Started](getting-started.md)** — first-room tutorial: REPL + CLI paths, tagged messages, streaming
- **[Configuration](configuration.md)** — config + state layers, the `.dvergr/` layout
- **[Provider & model setup](provider-setup.md)** — API keys, providers, the `models.edn` registry
- **[CLI reference](cli.md)** — `dvergr-cli`: flags, persistence, telemetry

## 📚 Concepts

- **[Rooms & Agents](rooms-and-agents.md)** — the core model: Rooms + Participants on a Bus, addressing (DM vs broadcast), forks
- **[Programming Model](programming-model.md)** — the compositional kernel: tagged messages, capability routing, dynamic subscriptions, escalation
- **[Discourse Theory](discourse-theory.md)** — why "discourse": speech acts, theory of mind, and the Rational-Speech-Acts/FRP lineage (short, optional)
- **[Architecture](architecture.md)** — the L0–L7 layer map, subsystem graph, inbound message flow, per-file table
- **[State Model](state-model.md)** — the three-tier copy-on-write state + workspace model

## 🔧 Reference

- **[Tools & the SCI sandbox](tools-and-sandbox.md)** — the tool registry + the sandbox agents run code in, and its safety boundaries
- **[Boundary secret injection](boundary-secret-injection.md)** — how an agent uses an API key it never sees (credential handling + the `:secrets` config)
- **[Process model](process-model.md)** — the pausable/resumable Process abstraction

## 🤝 Contributing

- **[CONTRIBUTING](../CONTRIBUTING.md)** — the L0–L7 layer convention, running tests, repo layout, commit style

## 🧪 Examples & notebooks

**Literate Clay notebooks** ([`notebooks/notebooks/`](../notebooks/notebooks/)) —
live-running, browsable at [replikativ.github.io/dvergr](https://replikativ.github.io/dvergr/);
build with `clj -M:clay -m notebooks.render`. Start with
[`getting_started.clj`](../notebooks/notebooks/getting_started.clj), then
`humans_and_agents`, `auditor`, `escalation`, `streaming`, and `llm_agent`.

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
