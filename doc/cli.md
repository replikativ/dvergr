# dvergr-cli

A terminal chat client for discourse rooms. Built on
[spindel-tui](https://github.com/replikativ/spindel-tui) + the
`dvergr.bus` + `dvergr.discourse.llm` substrate. Streams tokens,
shows live budget + last-turn telemetry, supports multi-room and
session persistence.

## Run

```bash
# From a dvergr checkout — default provider is auto-detected
clojure -M:cli

# Or via :local override (sibling repos)
clojure -M:cli:local
```

The CLI auto-detects the local `claude` CLI; if present, default
provider is `:claude-code` (no API key needed). Otherwise it falls
back to `:fireworks` (needs `FIREWORKS_API_KEY`).

## Overrides

```bash
# Pick a specific provider + model
clojure -M:cli :provider :fireworks \
              :model "accounts/fireworks/models/kimi-k2p6"

# Anthropic via local CLI explicitly
clojure -M:cli :model "claude-code-sonnet"     ; opus, haiku, sonnet

# Override the system prompt
clojure -M:cli :system-prompt "You answer in Latin."

# Raise the dollar cap (default $1)
clojure -M:cli :budget-dollars 5

# Resume a saved session
clojure -M:cli :resume s-abc12345
```

Args are pairs of keyword + value. Numeric values auto-parse.

## Layout

```
┌─ rooms ─────────┬─ dvergr-cli · room: scratch · $0.12 / $1.00 · last 8.3s 245tok $0.0012 · ● generating ─┐
│ rooms           │                                                                                       │
│ ────────────    │  you  > Refactor src/app.clj                                                          │
│ ▸ scratch       │  coder> I'll start by reading the file.                                               │
│   plan          │         [tool: read_file src/app.clj]                                                 │
│   triage        │         ...                                                                           │
│                 │                                                                                       │
│ Ctrl-N: new     │ > _                                                                                   │
└─────────────────┴───────────────────────────────────────────────────────────────────────────────────────┘
                      Enter: send · Ctrl-N: new room · Tab: cycle · Ctrl-C/q: quit
```

- **Sidebar** (left, 16 cols on terminals ≥ 36 cols): rooms list, active room marked with `▸`
- **Header**: room name, live budget, last-turn elapsed/tokens/cost, in-flight tool, generation status
- **Body**: scrolling messages — your turns prefixed `you  > `, agent's `coder> `; an in-progress reply streams into a "draft" line until the turn finishes
- **Footer**: input prompt + key hints

## Keys

| Key | Action |
|---|---|
| **Enter** | Send the input line to the active room's agent |
| **Ctrl-N** | Create a new room (`room-2`, `room-3`, …) and switch to it |
| **Tab** | Cycle the active room |
| **Ctrl-C** | Quit (saves session) |
| **q** (when input empty) | Quit (saves session) |
| **Backspace** | Edit input |
| Printable chars | Append to input |

## Persistence

Every session has an id (auto-generated on first run or supplied via
`:resume`). On quit, each room's chat-ctx is snapshotted to
`~/.dvergr-cli/sessions/<id>.edn`. The snapshot contains:

- Message history per room (system + user + assistant + tool)
- Budget state (`:total`, `:used`, `:by-type`, `:crossed-thresholds`)
- Status (`:active`, `:wrapping-up`, …)

Resume:

```bash
ls ~/.dvergr-cli/sessions/                # list saved sessions
clojure -M:cli :resume s-abc12345         # reopen
```

The fresh chat contexts are constructed and `cc/replace-messages!`
replays the prior history. The agent continues with full conversational
state from where you left off.

**Not** persisted across restart:

- In-flight generations (cancelled if the CLI was killed mid-turn)
- Spindel signal subscriptions (re-attached on resume)
- Tool-execution state (each turn is fresh)

For programmatic use, see `cc/snapshot-chat` and `cc/restore-chat` in
`dvergr.chat.context`.

## Tools

The CLI ships with the **coder set** by default:

- **Read-only:** `read_file`, `glob`, `grep`, `code_query`
- **Write:** `write_file`, `edit_file` (scoped to cwd)
- **Execution:** `clojure_eval` (sandboxed SCI), `shell`

Override:

```bash
# Read-only only
clojure -M:cli :tools '#{read_file glob grep code_query}'

# Add knowledge tools
clojure -M:cli :tools '#{read_file glob grep clojure_eval knowledge_search knowledge_add}'
```

(Argument parsing for `#{...}` may need quoting depending on your shell.)

## Provider precedence

1. `:provider` arg if given
2. `:claude-code` if `claude --version` succeeds
3. `:fireworks` (final fallback)

The model defaults match:

| Provider | Default model |
|---|---|
| `:claude-code` | `claude-code-sonnet` (uses `claude -p` under the hood) |
| `:fireworks` | `accounts/fireworks/models/kimi-k2p6` |

Other registered providers (Anthropic direct, OpenAI, Groq, Ollama, etc.)
work — just pass `:provider` + `:model`.

## Telemetry

The CLI subscribes to four bus tag-channels for each room:

- `[:to :you]` — finalized agent replies (and any direct user-addressed messages from other participants)
- `[:type :partial/token]` — streaming token chunks
- `[:type :telemetry/turn-started]` — turn began (model, provider)
- `[:type :telemetry/turn-complete]` — turn finished (elapsed-ms, tokens, cost)

The header reflects:

- Live budget (from `chat-ctx :budget-signal`)
- Last-turn elapsed / tokens / cost (from `:telemetry/turn-complete`)
- In-flight tool name + elapsed (from `:telemetry/tool-called` / `:tool-returned` — when supported by `run-turn-fn`)
- Generation status (`● generating` / `○ idle` / `✕ error`)

External consumers (a dashboard, billing system, logging agent) subscribe
to the same channels. The bus is the single shared substrate.

## Troubleshooting

**"FIREWORKS_API_KEY not set"** — set the env var, or use `:provider
:claude-code` (no key needed if `claude` is on PATH).

**Agent doesn't reply** — check the model is in the registry:
`(dvergr.model.registry/list-models)`. The CLI prints reply errors to
stderr; check `~/.dvergr-cli/dvergr-cli.log` if file logging is
configured.

**Garbled output / Unicode issues** — your terminal needs UTF-8 +
256-color support. The TUI uses JLine; if you're in an unusual
terminal, set `TERM=xterm-256color`.

**Tab doesn't cycle rooms** — some terminals map Tab to focus-change.
Try Ctrl-Tab as a fallback (also wired).

**Session file too large** — old chat contexts can grow. Either
configure compaction (default is sync-before-turn at 70% context-window),
or delete old sessions in `~/.dvergr-cli/sessions/`.

## See also

- **[Getting Started](getting-started.md)** — REPL + CLI tutorial
- **[Programming Model](programming-model.md)** — the bus + tagged messages model the CLI exposes
- **[examples/](../examples/)** — runnable scenarios you can replicate in the CLI
