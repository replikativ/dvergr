# dvergr-cli

The dvergr command-line entry point. `clojure -M:cli` starts **the daemon** (the
system), an **nREPL server** on `:7888`, and — unless `--no-tui` — a terminal chat
UI over your rooms, built on [spindel-tui](https://github.com/replikativ/spindel-tui)
+ the `dvergr.runtime.bus` + `dvergr.discourse.llm` substrate. The TUI is a frontend:
the same daemon also drives the web dashboard, Telegram, and nREPL clients.

## Run

```bash
# From a dvergr checkout — daemon + nREPL(:7888) + TUI
clojure -M:cli

# Headless: daemon + nREPL only (a server box, or attach a client later)
clojure -M:cli --no-tui

# Server box: daemon + nREPL + web dashboard, no TUI
clojure -M:cli --no-tui --web
```

### Flags

| Flag | Default | Meaning |
|------|---------|---------|
| `-p`, `--port PORT` | `7888` | nREPL port (written to `.nrepl-port` for editor/`clj-nrepl-eval` auto-discovery) |
| `--no-tui` | — | run without the TUI (daemon + nREPL only) |
| `--web` | — | also start the web dashboard |
| `--web-port PORT` | `17880` | web dashboard port (with `--web`) |
| `--web-bind IP` | `127.0.0.1` | web bind address — the UI/API are unauthenticated, so default is loopback |
| `-h`, `--help` | — | show usage |

**Provider, model, and agents are not CLI flags** — they come from
`config.local.edn` (with an env fallback). The shipped default leaves the provider
unpinned and auto-selects the best one you have a key for (Anthropic → Fireworks →
OpenAI → the local `claude` CLI). See [provider-setup.md](provider-setup.md) and
[configuration.md](configuration.md).

## Layout

```
┌─ rooms ─────────┬─ dvergr · room: scratch · $0.12 / $1.00 · last 8.3s 245tok $0.0012 · ● generating ─┐
│ rooms           │                                                                                    │
│ ────────────    │  you  > Refactor src/app.clj                                                       │
│ ▸ scratch       │  coder> I'll start by reading the file.                                            │
│   plan          │         [tool: read_file src/app.clj]                                              │
│   triage        │         ...                                                                        │
│                 │                                                                                    │
│ Ctrl-N: new     │ > _                                                                                │
└─────────────────┴────────────────────────────────────────────────────────────────────────────────────┘
                      Enter: send · Ctrl-N: new room · Tab: cycle · Esc: cancel · Ctrl-C/q: quit
```

- **Sidebar** (left): rooms list, active room marked `▸`
- **Header**: room name, live budget, last-turn elapsed/tokens/cost, in-flight tool, generation status
- **Body**: scrolling messages — your turns prefixed `you  > `, the agent's `coder> `; an in-progress reply streams into a draft line until the turn finishes
- **Footer**: input prompt + key hints

## Keys

| Key | Action |
|---|---|
| **Enter** | Send the input line to the active room's agent |
| **Ctrl-N** | Create a new room and switch to it |
| **Tab** | Cycle the active room |
| **Esc** | Cancel the current turn (interrupts the agent's generation) |
| **Ctrl-C** / **q** (input empty) | Quit |
| **Backspace** / printable chars | Edit input |

## Persistence

Rooms, their message history, knowledge, and per-agent budget **persist
automatically** in Datahike under `./.dvergr/` (the daemon owns the stores). There
is no save/resume step — restart `clojure -M:cli` and your rooms rehydrate from the
registry + stores, the agent continuing with full conversational state. Forks made
with `:isolation :ctx` carry their own branched stores until merged or discarded.

(Long conversations are kept in bounds by context compaction — summarize + prune
before a turn as the model's window fills; see [tools-and-sandbox.md](tools-and-sandbox.md).)

## Agents & tools

What the TUI talks to are the **agents joined to each room**, configured in
`config.local.edn` / `resources/agents/*.md` — not a CLI flag. Each agent's tool set
is its capability boundary (a `:tools` allowlist or a role tag like `#{:coding}`);
the default secretary (`var`) routes and chats. See
[rooms-and-agents.md](rooms-and-agents.md) and
[tools-and-sandbox.md](tools-and-sandbox.md).

## Telemetry

The TUI renders each room from the live `:room-messages` signal (a fold of the room
bus) plus per-chat stats. The header reflects:

- Live budget / cost — from the chat-ctx `:budget-signal` + `dvergr.rooms.stats`
- Generation status — `● generating` / `○ idle` / `✕ error`

(`:partial/token` streaming and `:telemetry/*` turn events are bus primitives — see
`examples/scenario_streaming_partial.clj` — not currently wired into the header.)
External consumers (the web dashboard, a billing system, a logging agent) subscribe
to the same bus channels.

## Troubleshooting

**Agent doesn't reply** — make sure a provider is configured (a key in env or
`config.local.edn`). Reply errors print to stderr; the daemon log is `.dvergr/dvergr.log`.
List registered models with `(dvergr.model.registry/list-models)` over the nREPL.

**Garbled output / Unicode issues** — the terminal needs UTF-8 + 256-color. The TUI
uses JLine; in an unusual terminal set `TERM=xterm-256color`.

**Tab doesn't cycle rooms** — some terminals map Tab to focus-change; Ctrl-Tab is
also wired.

## See also

- **[Getting Started](getting-started.md)** — REPL + CLI tutorial
- **[Configuration](configuration.md)** — config + the `.dvergr/` state layout
- **[Programming Model](programming-model.md)** — the bus + tagged-message model the CLI exposes
