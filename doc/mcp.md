# MCP: dvergr from Claude Code, Codex, Cursor and other clients

dvergr serves the Model Context Protocol so that a coding client can hand work to it: run a
task on forks of a room, evaluate Clojure in the room's REPL, adopt a result by merge, and see
what it cost. The tools are derived from `dvergr.ops/specification` (and the room-scoped
`dvergr.tools` registry); a new op appears in MCP, the JSON API and the web without extra code.

## Connecting a client

A client launches `bin/dvergr-mcp` (babashka) as a stdio server. It relays to the daemon's
loopback TCP port (default `127.0.0.1:17888`):

- when no daemon listens, it starts one (`clojure -M:cli --no-tui --mcp …`, log in
  `.dvergr/logs/mcp-daemon.log`) and answers `initialize` itself meanwhile, from the last tool
  list it saw (`.dvergr/mcp/`), so clients with short startup timeouts connect;
- when the daemon restarts, it reconnects, replays the handshake and sends
  `tools/list_changed`; a call in flight gets an error result saying the connection dropped;
- it never starts a second daemon on a state root whose daemon (`.dvergr/daemon.pid`) runs
  without MCP; restart that one with `--mcp`, or set `:mcp` in its config.

Claude Code (`.mcp.json` or `claude mcp add`):

```json
{"mcpServers": {"dvergr": {"type": "stdio",
                           "command": "/path/to/dvergr/bin/dvergr-mcp",
                           "args": ["--profile", "offload"]}}}
```

Codex (`~/.codex/config.toml`):

```toml
[mcp_servers.dvergr]
command = "/path/to/dvergr/bin/dvergr-mcp"
startup_timeout_sec = 30
tool_timeout_sec = 300
```

Options (flags or environment): `--profile` / `DVERGR_MCP_PROFILE`, `--toolsets a,b` /
`DVERGR_MCP_TOOLSETS`, `--port` / `DVERGR_MCP_PORT`, `--dir` / `DVERGR_DIR` (the checkout the
daemon starts in), `DVERGR_HOME` (state root), `DVERGR_MCP_START` (the start command),
`--no-start`, `--wait-s`.

To run the daemon yourself: `clojure -M:cli --no-tui --mcp [--mcp-port 17888]`, or
`:mcp {:port 17888 :profile "offload"}` in the config file. The daemon inherits its
environment: a `TELEGRAM_BOT_TOKEN` there connects that bot.

## Profiles and toolsets

A connection sees one selection of tools, chosen by `--profile` / `--toolsets` (the relay puts
them into `initialize` `_meta` as `dvergr/profile` and `dvergr/toolsets`). Tools outside it are
neither listed nor callable.

| Profile | Toolsets | Tools |
| --- | --- | --- |
| `offload` (default) | rooms, worlds, attempts, wallets, repl | 22 |
| `readonly` | every read op | read-only only |
| `admin` | everything | all |

| Toolset | What |
| --- | --- |
| `rooms` | create, list, detail, delete, messages, post |
| `worlds` | fork, review, diff, merge, discard |
| `attempts` | `workflow_start` and the job tools; Attempts, Scorecards |
| `runs` | the blocking `workflow_attempt`; Runs |
| `wallets` | `room_wallet`, `models_list` |
| `repl` | `clojure_eval` in the room's SCI sandbox |
| `agents` | agent administration, `room_invite` |
| `system` | statistics |
| `code` | files, search, shell, tasks: for hosts without their own (off by default) |
| `extra` | tools registered at runtime (channels) |

The default is small on purpose: some clients cap the tools of all their servers together
around 40, and every definition costs context in every session.

## What each tool promises

- **Handles, not sessions.** Every call names its `room` (id or slug) or fork; the server keeps
  no per-connection state beyond the selection. (MCP 2026-07-28 removes protocol sessions;
  this surface already fits it.)
- **A coding tool always runs in a room**: `room` is required, and the tool runs in that room's
  sandbox and workspace, never in the daemon's own directory.
- **Annotations** on every tool (`readOnlyHint`, `destructiveHint`, `idempotentHint`,
  `openWorldHint`, `title`), from the op's `:kind` and the tables in `dvergr.mcp.surface`.
  Clients use them for confirmation: `clojure_eval`, `room_merge` and `room_discard` are
  destructive, so e.g. Codex asks before calling them.
- **Results** are JSON text plus the same value as `structuredContent`; text beyond 60k
  characters is cut with a note (Claude Code caps a result at 25k tokens).
- **Schemas** are flat and closed (`additionalProperties: false`, no `oneOf`/`anyOf`/`$ref`) and
  names match `^[a-z][a-z0-9_]{0,39}$`, so strict model APIs accept them; `server_test` checks
  every tool.
- **Long work is a job.** `workflow_start` returns a job at once; `job_status {job, wait-ms}`
  answers when it finishes or after at most 25 s (below every common client timeout), and
  `job_cancel` stops it. Jobs belong to the daemon: a client can restart and poll again
  (`job_list`); a daemon restart drops them, while their Attempts stay recorded.
- **Merges are pinned to the review.** `room_review` (and every workflow attempt's review)
  carries the fork's `state`, a hash of its systems' snapshot ids; `room_merge {room,
  expect-state}` refuses a fork that changed since. A failed merge is an error, not a success.
- **Money is in the result.** A workflow result has each attempt's spend, the per-model table
  (cost per completed attempt) and the room's wallet afterwards.
- REPL definitions live in the daemon's memory: they do not survive a daemon restart. Keep
  lasting code in the room's workspace.

## Protocol

Handshake versions 2024-11-05, 2025-03-26, 2025-06-18 and 2025-11-25; an unknown version is
answered with the latest. Tools, resources (derived from the read ops) with subscriptions, and
`listChanged`. Unknown tools are `-32602`.

## Next

1. `repl_describe` (search the bound API) next to `clojure_eval`; evals metered to the wallet;
   a `code-mode` profile.
2. MCP 2026-07-28 (stateless, `server/discover`, `_meta` per request) and Streamable HTTP for
   hosted use, then OAuth.
3. A `data` toolset over pg-datahike (load a `pg_dump`, migrate on a fork, merge), and a client
   test pass over Cursor, VS Code, Gemini CLI, n8n and ChatGPT.
