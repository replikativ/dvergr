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
| `--nrepl-port-file PATH` | `.nrepl-port` | where to write the nREPL port (parent directories are created; a file that cannot be written stops the start with exit code 1) |
| `--no-tui` | — | run without the TUI (daemon + nREPL only) |
| `--web` | — | also start the web dashboard |
| `--web-port PORT` | `17880` | web dashboard port (with `--web`) |
| `--web-bind IP` | `127.0.0.1` | web bind address — the UI/API are unauthenticated, so default is loopback. Loopback does not keep out the pages your browser shows, so the server also checks the Host header (DNS rebinding), the Origin of every state-changing request and a per-session CSRF token; a reverse proxy's name and origin go in `:http {:allowed-hosts […] :allowed-origins […]}` |
| `--mcp` | — | also serve MCP on loopback TCP (clients connect through `bin/dvergr-mcp`; [mcp.md](mcp.md)). The daemon names its state root in `server/discover`'s `_meta` (`dvergr/home`), and the relay refuses a daemon of another state root |
| `--mcp-port PORT` | `17888` | MCP port (with `--mcp`) |
| `-h`, `--help` | — | show usage |

**Provider, model, and agents are not CLI flags** — they come from
`config.local.edn` (with an env fallback). The shipped default leaves the provider
unpinned and auto-selects the best one you have a key or login for (Anthropic →
Fireworks → OpenAI → native Codex subscription → isolated Codex CLI → the local
`claude` CLI). See [provider-setup.md](provider-setup.md) and
[configuration.md](configuration.md).

## Views

The TUI has a few views; **Tab** switches between the tree and the process list,
and leaves a room for the tree.

- **Tree** (`Rooms · Agents · Forks`): rooms, their forks and the agents. Keys not
  listed below go to the tree component (moving the selection), **Enter** opens a
  room (or an agent's config), and single keys act on the selection.
- **Room**: the room's messages with an input line; a reply streams in as it is
  written. Typing `/` opens the command menu (↑/↓ select, Tab or Enter complete).
- **Processes**: the running processes of the chat, with abort / continue / extend.
- **Agent config**: an agent's fields, edited in place.

## Keys

| Key | Where | Action |
|---|---|---|
| **Ctrl-C** | anywhere | Quit |
| **Tab** | anywhere | Switch view: tree ↔ processes; from a room, back to the tree |
| **Ctrl-O** | anywhere | Toggle mouse reporting (on: wheel scrolls; off: the terminal selects and copies text) |
| **Enter** | tree | Open the selected room, or the selected agent's config |
| **n** / **a** | tree | New room (asks for a slug) / new agent (asks for an id); Esc cancels the prompt |
| **c** | tree | Chat with the selected agent |
| **f** | tree | Fork the selected room (`:ctx` isolation) |
| **m** / **x** | tree | Merge the selected fork into its parent / discard it (asks y / n) |
| **d** | tree | Archive the selected room |
| **Enter** | room | Send the input line (a `/command` runs instead) |
| **Esc** | room | Cancel the running turn; when none runs, back to the tree |
| **PageUp** / **PageDown**, wheel | room | Scroll |
| **Ctrl-R** | room | Voice input — press to start recording, again to stop; transcribes and posts as a `🎤` message ([setup](media.md#recording-repl--tui)) |
| **Ctrl-T** / **Ctrl-E** | room | Toggle the trace view / per-agent context bars |
| **j** / **k**, **e** or **Enter**, **p**, **c**, **x**, **Esc** | agent config | Move, edit a field, edit the prompt in `$EDITOR`, chat, delete (asks y / n), back |
| **j** / **k**, **a** / **c** / **e** | processes | Move; abort, continue, or extend the focused process by $0.10 |

## Persistence

Rooms, their message history, knowledge, and per-agent budget **persist
automatically** in Datahike under the state root (`$DVERGR_HOME`, default `./.dvergr`;
the daemon owns the stores). There
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
`config.local.edn`). Reply errors print to stderr; the daemon log is `dvergr.log` in the state root
(`$DVERGR_HOME`, default `./.dvergr`).
List registered models with `(dvergr.model.registry/list-models)` over the nREPL.

**Garbled output / Unicode issues** — the terminal needs UTF-8 + 256-color. The TUI
uses JLine; in an unusual terminal set `TERM=xterm-256color`.

## See also

- **[Getting Started](getting-started.md)** — REPL + CLI tutorial
- **[Configuration](configuration.md)** — config + the `.dvergr/` state layout
- **[Programming Model](programming-model.md)** — the bus + tagged-message model the CLI exposes
