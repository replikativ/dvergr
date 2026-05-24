# dvergr Documentation

New to dvergr? Start with **[Getting Started](getting-started.md)**.

## 🚀 Getting Started

- **[Main README](../README.md)** — project overview + 60-second quickstart
- **[Getting Started](getting-started.md)** — first-room tutorial: REPL + CLI paths, tagged messages, streaming
- **[CLI Reference](cli.md)** — `dvergr-cli`: keys, persistence, providers, telemetry

## 📚 Programming Model

- **[Programming Model](programming-model.md)** — the compositional kernel:
  - Discourse Room + Bus + Tagged messages
  - Capability routing + opinionated buffer policy table
  - Dynamic subscriptions (`d/subscribe!`)
  - GenerationHandle (the F-side primitive)
  - The bialgebra distributive law λ — extracting decision shapes
  - Escalation pattern (no hardcoded managers)

## 🔧 Reference

- **[Discourse Model](discourse-model.md)** — the formal architecture
  doc. The bialgebra (§5.4a), theory-of-mind via substrate fork
  (§5.4b), drivers (§5.5), bus (§5.5a), GenerationHandle (§5.5b),
  patterns (§6), thought experiments (§6.5), test strategy (§9),
  related work surveys.

## 🧪 Scenarios

Runnable example programs in [`examples/`](../examples/):

- `scenario_manager_escalation.clj` — capability routing for `:escalation/budget`
- `scenario_streaming_partial.clj` — per-consumer SLA: sliding-1 UI vs. fixed-buffer audit
- `scenario_auditor.clj` — `d/subscribe!` for tag-watchers alongside an inbox
- `humans_and_agents.clj` — 5 demos: humans + scripted agents, background tasks, propose/accept, propose/reject, substrate-fork

Run any of them via:

```bash
clojure -M:local -m scenario-manager-escalation
```

## 📂 Internals (advanced)

The discourse-model.md doc is the canonical internals reference.
It covers:

- Continuous-time / mailbox-driven participants (§5.1)
- Heterogeneous participants share one shape (§5.2)
- Identity + isolation (§5.3)
- Coalgebraic view + bisimulation (§5.4)
- Algebra meets coalgebra — bialgebra (§5.4a)
- Theory of mind as coinductive fixed point (§5.4b)
- Drivers: inbox is not the only signal (§5.5)
- Bus is the routing substrate (§5.5a)
- GenerationHandle: the F-side primitive (§5.5b)
- Personas, gating, sub-agent hiring (§5.6-5.8)
- The algebra: ask, fan-out, race, quorum, pipeline (§6)
- Thought experiments and ToM (§6.5)
- Laws (§7)
- Belief + common ground (§8)
- Testing strategy (§9)

## See also

- **[Spindel docs](https://github.com/replikativ/spindel/tree/main/docs)** — the FRP runtime dvergr is built on
- **[Datahike docs](https://github.com/replikativ/datahike/tree/main/doc)** — the immutable Datalog database for conversation + knowledge
- **[Yggdrasil docs](https://github.com/replikativ/yggdrasil/tree/main/doc)** — copy-on-write branching across git + Datahike
