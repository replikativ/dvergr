# dvergr

**A Clojure agentic programming framework** built on continuous-time
collaborative multi-agent rooms. Agents are reactive processes; users
(humans, LLMs, scripts) are participants in the same shape; everything
composes through tagged messages on a small pub/sub kernel.

Built on [Spindel](https://github.com/replikativ/spindel) (functional
reactive runtime), [Datahike](https://github.com/replikativ/datahike)
(immutable Datalog), and
[Yggdrasil](https://github.com/replikativ/yggdrasil)
(copy-on-write branching across git + database).

## What it gives you

- **Discourse rooms** where humans + LLM agents exchange messages
- **Tagged routing** — agents escalate `:escalation/budget`,
  policy-bots subscribe by capability tag; neither hardcodes the other
- **Substrate forks** — a "what would the coder write?" probe runs on
  a branched git worktree + datahike, then merges or discards atomically
- **Live streaming** — token-by-token responses through a small
  `:partial/token` stream, with per-consumer SLA (sliding-1 for UI,
  fixed-256 for audit)
- **Compositional kernel** — five primitives (tagged message,
  capability sub, dynamic subscribe, fork-room, GenerationHandle)
  cover the whole programming surface

## 60-second quickstart

```bash
# Clone + run the TUI chat client
git clone https://github.com/replikativ/dvergr.git
cd dvergr

# Anthropic via local `claude` CLI (no API key needed if you have a
# Claude Code subscription)
clojure -M:cli

# Or use any OpenAI-compatible provider
export FIREWORKS_API_KEY=...
clojure -M:cli :provider :fireworks :model "accounts/fireworks/models/kimi-k2p6"
```

Type a message, press **Enter**. Use **Ctrl-N** for a new room, **Tab**
to switch, **q** or **Ctrl-C** to quit (session is auto-saved). Resume
with `clojure -M:cli :resume <session-id>`.

## REPL quickstart

```clojure
(require '[dvergr.core :as d])

;; Build a room and join a coder agent
(def room (d/room :scratch))
(binding [org.replikativ.spindel.engine.core/*execution-context* (:ctx room)]
  (d/join room (d/coder {:id :coder})))

;; Send a user message; the agent replies asynchronously
(d/post! room (d/message :you :coder "Add input validation to src/app.clj"))

;; Read the message log
(d/log room)
;; => [{:from :you :to :coder :content "..." :type :user/message}
;;     {:from :coder :to :you :content "..." :type :user/message}
;;     ...]
```

For tagged routing, streaming, persistence, and the
fork-and-merge proposal pattern, see
[doc/getting-started.md](doc/getting-started.md).

## Documentation

- **[Getting Started](doc/getting-started.md)** — first-room tutorial,
  REPL + CLI paths
- **[Sidecar](doc/sidecar.md)** — `dvergr.sidecar/spawn-agent!`: load
  dvergr into any Clojure REPL, drive agents as values
- **[Programming Model](doc/programming-model.md)** — bus, tagged
  routing, GenerationHandle, the distributive law λ
- **[CLI Reference](doc/cli.md)** — `dvergr-cli` keys, persistence,
  provider config
- **[Discourse Model](doc/discourse-model.md)** — the formal
  architecture: §5.4a bialgebra, §5.5a bus, drivers, ToM via
  substrate fork
- **[Doc index](doc/README.md)** — full table of contents

## Building blocks

| Namespace | What it provides |
|---|---|
| `dvergr.core` | Public facade — re-exports the most-used vars |
| `dvergr.discourse` | Room, participant, post!, ask, fork-room, merge-room |
| `dvergr.bus` | Pub/sub routing kernel + opinionated buffer policy table |
| `dvergr.discourse.llm` | `llm-agent` — directive-aware participant |
| `dvergr.discourse.generation` | `GenerationHandle` + sync/future/external/streaming adapters |
| `dvergr.participant.context` | `ParticipantContext` — uniform memory+budget across LLM/human/hybrid |
| `dvergr.personas` | `researcher`, `coder`, `reviewer` — pre-built agents |
| `dvergr.proposals` | `propose!` / `accept-proposal!` / `reject-proposal!` over fork-room |
| `dvergr.cli.main` | `-main` of the TUI chat client |

## Provider setup

```clojure
(require '[dvergr.model.providers :as providers])

;; Auto-registers from env: ANTHROPIC_API_KEY, OPENAI_API_KEY, FIREWORKS_API_KEY
(providers/ensure-initialized!)

;; Anthropic via the local `claude` CLI (no API key needed)
;; Auto-detected if `claude` is on PATH.

;; Any OpenAI-compatible provider
(providers/register-openai-compatible!
  :groq
  {:base-url "https://api.groq.com/openai/v1"
   :api-key  (System/getenv "GROQ_API_KEY")})

;; Local model via Ollama
(providers/register-openai-compatible!
  :ollama
  {:base-url "http://localhost:11434/v1"
   :api-key  "ollama"})
```

## Dependencies

| Library | Role |
|---|---|
| [Spindel](https://github.com/replikativ/spindel) | FRP reactive runtime, CoW context forking, pub/sub |
| [Datahike](https://github.com/replikativ/datahike) | Immutable Datalog database (conversation + knowledge) |
| [Yggdrasil](https://github.com/replikativ/yggdrasil) | Copy-on-write branching across git + Datahike |
| [SCI](https://github.com/babashka/sci) | Sandbox for `clojure_eval` and agent code |
| [spindel-tui](https://github.com/replikativ/spindel-tui) | Terminal UI built on JLine + Spindel signals |
| [hato](https://github.com/gnarroway/hato) | HTTP client for provider APIs |
| [Telemere](https://github.com/taoensso/telemere) | Structured logging + observability |

## License

Copyright © 2026 Christian Weilbach. Apache License 2.0 — see [LICENSE](LICENSE).
