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

**A connection pinned to one room.** `--room ROOM --tools read_file,write_file` gives a
connection exactly those tools, in that room only: their `room` parameter disappears (the
connection supplies it) and a call naming another room is refused. This is how an external
agent works in a benchmark Attempt: a `claude-code-*` candidate runs `claude -p` INTO its
Attempt's world, with this relay in `--mcp-config` (its own built-in tools disabled, only
`mcp__dvergr__<tool>` allowed, its own loop of up to 60 turns); the CLI calls the same tools
every other candidate gets, natively, and the evaluator scores the world when it answers. The
daemon (or any process running an Attempt) serves MCP on a loopback port for it.

## Profiles and toolsets

A connection sees one selection of tools, chosen by `--profile` / `--toolsets` (the relay puts
them into `initialize` `_meta` as `dvergr/profile` and `dvergr/toolsets`). Tools outside it are
neither listed nor callable.

| Profile | Toolsets | Tools |
| --- | --- | --- |
| `offload` (default) | rooms, worlds, attempts, catalog, wallets, repl | 26 |
| `code` | repl, describe (its REPL calls the ops of rooms, worlds, attempts, catalog, bench, wallets) | 2 |
| `readonly` | every read op | read-only only |
| `admin` | everything | all |

| Toolset | What |
| --- | --- |
| `rooms` | create, list, detail, delete (archives: history kept), messages, post |
| `worlds` | fork, review, diff, merge, discard |
| `attempts` | `workflow_start` and the job tools; Attempts, Scorecards |
| `runs` | the blocking `workflow_attempt`; Runs |
| `catalog` | `catalog_list`, `catalog_start`: workflows with their own checker |
| `wallets` | `room_wallet`, `models_list` |
| `repl` | `clojure_eval` in the room's SCI sandbox |
| `describe` | `repl_describe`: the REPL's API (dvergr namespaces, functions, signatures, docs), filtered by a query |
| `agents` | agent administration, `room_invite` |
| `system` | statistics |
| `admin` | archived rooms: `room_unarchive` brings one back, `room_purge` removes it and its stores for good (admin profile only) |
| `code` | files, search, shell, tasks: for hosts without their own (off by default) |
| `extra` | tools registered at runtime (channels) |

**REPL-first (`--profile code`).** Two tools: `clojure_eval`, whose `dvergr.ops` namespace
calls every op of the listed toolsets (see below), and `repl_describe` to discover the API
without writing code. A client writes one program where it would chain tool calls.

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
  `job_cancel` stops it. A job is a durable Run of its room, and the attempts it starts are its
  child Runs, so a client can restart and poll again (`job_list`), and a job outlives the
  daemon: after a restart it reads as it finished, or `failed` with reason `orphaned` if the
  daemon stopped while it ran. A workflow runs up to `parallelism` attempts at once (default 4).
- **A room's reads are live.** Every read op is also a resource (`room://{room}/messages`,
  `experiment://{room}/progress`, …). Subscribing to one about a room sends
  `notifications/resources/updated` after every durable change of that room (a message, a Run,
  a tool call, an Attempt), at most a few times a second; re-read the resource for what changed.
- **Your code in, a patch out.** `room_import {room, source}` creates a room whose workspace is a
  clone of a local checkout (its committed state, with history) or an https remote. Work on it with
  `workflow_start`, adopt an attempt with `room_merge`, then `room_export {room}` returns the changes
  since the import as a patch: `git apply` it in your checkout. Only these two ops reach a
  repository outside the room; a room's own sandbox Git has no network transport.
- **Merges are pinned to the review.** `room_review` (and every workflow attempt's review)
  carries the fork's `state`, a hash of its systems' snapshot ids; `room_merge {room,
  expect-state}` refuses a fork that changed since. A failed merge is an error, not a success.
- **A merge adopts a fork's files.** A workspace change is only in the diff once committed; an
  attempt's world is committed when its Run ends, and `room_merge` commits a fork's remaining
  changes before merging (`room_review` lists them as `uncommitted`, and they make a fork
  reviewable). Without this a merge silently dropped uncommitted files.
- **Catalog workflows are scored by their own checker.** `catalog_start {workflow: "wiki/v1"}`
  without a room runs on the benchmark set (fixtures seeded into a new room, ten known facts);
  with a room, on its own `/docs`, scored by citations, links and structure. Each attempt's
  `checks` say what it got right (one per fact), `reward` weighs them.
- **A benchmark can report into your room.** `catalog_benchmark {workflow, models, room}` still
  runs every cell in a world forked from a new fixture room, but keeps the job, its Attempts and
  the Scorecard in `room`, where its dashboards (and `experiment_progress {room}`) show them.
- **Money is in the result.** A workflow result has each attempt's spend, the per-model table
  (cost per completed attempt) and the room's wallet afterwards.
- **The API is in the REPL too.** `clojure_eval`'s `dvergr.ops` namespace holds every op the
  connection may call, as a function taking one map (`room-list`, `catalog-benchmark`,
  `job-status`, `scorecard-detail`, … and `(call :room/list {})`), documented from the spec
  (`(doc dvergr.ops/job-status)`, `(dir dvergr.ops)`, `apropos`) and validated against its
  schema. So a client can write one program instead of a chain of tool calls:

  ```clojure
  (let [scs (dvergr.ops/scorecard-list {:room "r"})
        d   (dvergr.ops/scorecard-detail {:room "r" :id (:id (first scs))})]
    (select-keys (first (:leaderboard d)) [:candidate :pass-rate-interval]))
  ```

  The REPL has exactly the authority of the connection's tools: only the ops its selection
  shows are installed, and each selection has a REPL session of its own (`:mcp/offload`, …),
  so a connection never reaches a function another one installed. `room-delete` and
  `room-purge` refuse the room the REPL runs in. An agent's own sandbox gets none of this.
- REPL definitions live in the daemon's memory: they do not survive a daemon restart. Keep
  lasting code in the room's workspace.

## Protocol

Both eras of the protocol, decided per request, so one connection may carry both:

- **Handshake versions** 2024-11-05, 2025-03-26, 2025-06-18 and 2025-11-25: `initialize`, then
  requests; an unknown version is answered with the latest. Tools, resources (derived from the
  read ops) with subscriptions, `listChanged` and `ping`. Unknown tools are `-32602`, unknown
  resources `-32002`.
- **Stateless 2026-07-28**: no `initialize`; each request names its version, client info and
  capabilities in `_meta` (`io.modelcontextprotocol/protocolVersion`, `…/clientInfo`,
  `…/clientCapabilities`), and its tool selection too (`dvergr/profile`, `dvergr/toolsets`,
  `dvergr/room`, `dvergr/tools`), which holds for that request only. `server/discover` names
  every supported version, the capabilities and the instructions. Every result has
  `resultType: "complete"` and the server's info in `_meta` (`io.modelcontextprotocol/serverInfo`);
  lists and reads carry `ttlMs` and `cacheScope` (`private`: what a client sees depends on its
  selection; a read is live, `ttlMs` 0). A version the server does not speak is refused with
  `-32022` and `data {supported, requested}`; an unknown resource is `-32602`; `ping` is gone.
  `subscriptions/listen` opens a subscription: the request stays open, the server first sends
  `notifications/subscriptions/acknowledged` with the part of the filter it honours
  (`toolsListChanged`, `resourceSubscriptions` for existing resources; no prompts, no resource
  list changes), then tagged notifications (`io.modelcontextprotocol/subscriptionId`, the
  listen request's id). `notifications/cancelled` ends it; at shutdown the server answers the
  listen request (`resultType: "complete"`) before closing. A stateless client that did not
  subscribe gets no notifications; a handshake connection gets list changes as before.

**Streamable HTTP** (`dvergr.mcp.http`, `:mcp {:http {:port 17889}}` in the daemon config): one
`POST /mcp` endpoint on its own loopback listener. Every request needs `Authorization: Bearer
<token>` (the token is in `<state>/mcp/http-token`, mode 0600, created on first start); an
`Origin` that is not loopback is refused (403). Stateless requests must carry
`MCP-Protocol-Version`, `Mcp-Method` and (for `tools/call`, `resources/read`) `Mcp-Name`
matching the body (else 400, -32020); an unsupported version is 400 (-32022), an unknown method
404. The response is JSON, or SSE for `subscriptions/listen` (an open stream with keep-alive
comments) and for a long request with a `progressToken`; closing a stream cancels its request.
Handshake clients get an `Mcp-Session-Id` from `initialize` (DELETE ends it); there is no GET
stream (405).

**The relay** (`bin/dvergr-mcp`) speaks both eras too: it forwards `server/discover` to the
daemon (answering it itself, with the same versions, while the daemon starts), puts its
`--profile`/`--toolsets`/`--room`/`--tools` pins into each stateless request's `_meta` (there is
no `initialize` to carry them), opens a connection for a stateless request, and re-opens the
client's subscriptions after a daemon restart (dropping the repeated acknowledgement). A test
keeps its version lists equal to the server's.

**Progress** (both eras): a `tools/call` or `resources/read` with `_meta.progressToken` gets
`notifications/progress` while it runs — a heartbeat every 10 s (`progress` = elapsed seconds,
only increasing) plus whatever the tool reports through `:progress!` — and none after its
response. The HTTP transport comes next.

A connection answers requests concurrently: every `tools/call` and `resources/read` runs on
its own virtual thread, so a `job_status` wait or a long evaluation does not hold up the
connection's other requests (a `ping`, a list, another tool). The handshake, lists and
notifications stay in order. Responses to concurrent requests may therefore arrive out of
order, as JSON-RPC allows. A request cancelled with `notifications/cancelled` before it
finishes gets no response. Its work is not interrupted: a job keeps running until
`job_cancel`.

## Next

1. Native tools for Claude Code and Codex as benchmark candidates (their CLIs reach a Run's
   tools over `--mcp-config`); a read-only eval (needs a restricted sandbox, not only fewer
   ops) and evals metered to the wallet.
2. MCP 2026-07-28 (stateless, `server/discover`, `_meta` per request) and Streamable HTTP for
   hosted use, then OAuth. Concurrent dispatch and cancellation (above) are the first step.
3. A `data` toolset over pg-datahike (load a `pg_dump`, migrate on a fork, merge), and a client
   test pass over Cursor, VS Code, Gemini CLI, n8n and ChatGPT.
