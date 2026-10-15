# MCP: dvergr from Claude Code, Codex, Cursor and other clients

dvergr serves the Model Context Protocol so that a coding client can hand work to it: run a
task on forks of a room, evaluate Clojure in the room's REPL, adopt a result by merge, and see
what it cost. The tools are derived from `dvergr.ops/specification` (and the room-scoped
`dvergr.tools` registry); a new op appears in MCP, the JSON API and the web without extra code.

## Connecting a client

A client launches `bin/dvergr-mcp` (babashka) as a stdio server. It relays to the daemon's
loopback TCP port (default `127.0.0.1:17888`). Its files live under the state root,
`DVERGR_HOME` (default `<checkout>/.dvergr`):

- when no daemon listens, it starts one (`clojure -M:cli --no-tui --mcp …`, log in
  `$DVERGR_HOME/logs/mcp-daemon.log`, nREPL port in `$DVERGR_HOME/mcp-daemon.nrepl-port`) and
  answers `initialize` itself meanwhile, from the last tool list it saw for the same selection
  (`$DVERGR_HOME/mcp/`), so clients with short startup timeouts connect. The first boot takes
  about two minutes (dependencies, then the stores); the tools appear when it is up
  (`tools/list_changed`);
- it attaches only to a daemon of its own state root: one on the port that reports another
  home (`server/discover`'s `_meta` `dvergr/home`) is refused, and every call says so. Give the
  relay another `--port` for another home (one daemon per home, one port per daemon);
- when the daemon restarts, it reconnects, replays the handshake and sends
  `tools/list_changed`; a call in flight gets an error result saying the connection dropped;
- it never starts a second daemon on a state root whose daemon (`$DVERGR_HOME/daemon.pid`) runs
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
`DVERGR_MCP_TOOLSETS`, `--room` / `DVERGR_MCP_ROOM` and `--tools a,b` / `DVERGR_MCP_TOOLS`
(below), `--port` / `DVERGR_MCP_PORT`, `--host` (default `127.0.0.1`), `--dir` / `DVERGR_DIR`
(the checkout the daemon starts in), `DVERGR_HOME` (state root), `DVERGR_MCP_START` (the start
command), `--no-start`, `--wait-s` (default 120).

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
This path needs the relay at `bin/dvergr-mcp` under the process's working directory (a
checkout; `DVERGR_MCP_RELAY` names another path) and babashka (`bb`) to run it. Without the
relay, or without a work room and tools, `claude -p` answers once and calls tools through the
text `<tool_use>` protocol instead (`dvergr.model.api.claude-code`), so scores from the two
paths are not the same measurement.

**Over HTTP, without the relay.** Give the daemon `:mcp {:port 17888 :http {:port 17889}}` and
it also serves Streamable HTTP at `http://127.0.0.1:17889/mcp` (loopback only). Without an
authenticator of the daemon's own (below), requests need the bearer token the daemon writes
to `mcp/http-token` in its state root (`$DVERGR_HOME`, default `.dvergr` in the directory it
started in; mode 0600) on its first start; it makes the caller the local principal.
Claude Code:

```sh
claude mcp add --transport http dvergr http://127.0.0.1:17889/mcp \
  --header "Authorization: Bearer $(cat "${DVERGR_HOME:-.dvergr}/mcp/http-token")"
```

or in `.mcp.json`:

```json
{"mcpServers": {"dvergr": {"type": "http", "url": "http://127.0.0.1:17889/mcp",
                           "headers": {"Authorization": "Bearer <token>"}}}}
```

Other clients that speak Streamable HTTP take the same URL and header. The selection
(`dvergr/profile`, `dvergr/toolsets`, `dvergr/room`, `dvergr/tools`) goes in each request's
`_meta`, or in `initialize`'s for a handshake client.

Which to use: the **stdio relay** starts the daemon when none runs and hides its restarts, so a
client never sees its server die; **HTTP** needs a running daemon, has no relay process, and
is the way to a hosted dvergr (behind a TLS proxy; the listener itself stays on loopback).
A hosted dvergr also needs an authenticator and an authorizer of its own (below): the
defaults trust whoever reaches the daemon the way only its local user can.

## Who is calling: principals, authentication, authorization

Every call has a **principal** (`dvergr.principal`), a plain map:

```clojure
{:principal/id     "party/5f0c…"        ; stable, compared by =
 :principal/kind   :human               ; :human | :agent | :local
 :principal/actor  :party/5f0c…         ; who it posts as (default: derived from the id)
 :principal/scopes #{:toolset/rooms}    ; what MCP may show it
 :principal/claims {…}}                 ; the authenticator's evidence, opaque to dvergr
```

Three seams decide who it is, what it may do, and what it owns. They are set in the daemon
config, `:auth {:authenticate f :authorize f :on-created f}` (a function, or a qualified symbol
naming one), or, for a host that runs dvergr's rooms without a daemon, process-wide with
`principal/configure!`:

- **`:authenticate`** `(fn [request] -> principal | nil)`. `request` carries `:transport`
  (`:mcp-stdio`, `:mcp-tcp`, `:mcp-http`, `:web`, `:telegram`), `:remote-addr` (the socket's
  peer, never a forwarding header: http-kit's own `:remote-addr` is the first
  `X-Forwarded-For` entry), `:bearer` (the `Authorization: Bearer` token), `:headers`, for MCP
  over HTTP `:local-token` (the token file's), for Telegram `:telegram/user` and
  `:telegram/chat-id`. nil refuses the caller: 401 over HTTP, an error and a closed connection
  over TCP, a refusal in Telegram.
  The default, `principal/default-authenticate`, admits local access only, as the **local
  principal**: the stdio relay; a loopback peer of TCP or the web server; the token of
  `mcp/http-token` from a loopback peer; a Telegram sender on a non-empty allowlist (on an open
  one a sender is `telegram/<id>`, a principal the authorizer decides about). A request that
  carries a forwarding header (`X-Forwarded-For`, `Forwarded`, `X-Real-IP`, …) is never local:
  a proxy on the same host makes every peer loopback. A listener bound off loopback refuses to
  start with the default (`principal/check-bind!`). A configured authenticator replaces it;
  a hosted one should not hand out the local principal at all.
- **`:authorize`** `(fn [principal request] -> decision)`. `request` is `{:op :action :resource
  :resolved :args}`: the op (`:room/post`, or `:tool/clojure_eval` for a registry tool), its
  action (`:read`, `:write`, `:merge`, `:admin`), the resource as the caller named it (`[:room
  "general"]`, `[:job "<id>"]`, `[:agent "var"]`, `[:global]`) and what dvergr resolved it to
  (`{:room "<room id>"}`; for a job also `:owner`, the actor that started it; nil for a handle
  that resolves to nothing). Deny `:resolved nil` except where the op creates what it names.
  A decision is a boolean or `{:allow? … :reason …}`. A throw denies, and so does a resolution
  that throws. The default, `principal/allow-all`, allows everything.
- **`:on-created`** `(fn [principal resource])`, called after a principal other than the local
  one created a room (`room_create`, `room_fork`, `room_import`, `agent_open`, a catalog
  fixture room) or an agent (`agent_create`), so the host can grant it its creator. It may be
  called again for the same principal and resource: make it idempotent. When it throws, the
  op fails (500) and is recoverable: a fork or an agent whose grant failed is removed again
  (a retry creates it anew); a room from `room_create` or `room_import` stays, recorded as
  that principal's (`:room/creator` in the system DB), and the same call by the same
  principal grants it again instead of answering "exists". A catalog fixture room whose grant
  failed stays ungranted; each benchmark makes a new one.

A principal never receives a room it did not create: `room_create` and `room_import` refuse
an existing slug (409) unless that principal created it, and `agent_open` gives each
principal other than the local one a DM room of its own (`dm-<agent>-<hash>`; the local
user keeps the shared `dm-<agent>`).

The local principal is the process itself and is never asked. It is recognized by identity
(an unforgeable marker), not by `:principal/kind`: `principal/decide` and `invoke` refuse a
principal that claims kind `:local` without being it, without asking the authorizer.

What each op acts on is declared in `dvergr.ops/specification`, `:authz {:action :resource
:also :filter}`. Every write declares it; a read with a `room` argument may leave it to the
default (reading that room). `:also` adds checks beyond the op's own resource:
`room_merge` reads the fork and needs `:merge` on its parent (no parent, no merge);
`catalog_benchmark` writes `[:global]` (it creates a fixture room) and reads the room of a
room workflow (`<room>/<name>`); `room_invite` and `catalog_deploy` write the agent they put
to work; `attempt_export` with `file` is `:admin` on `[:global]` (the host's state root).
Archiving (`room_delete`), discarding a fork, purging, agent administration, imports from a
host path, promoting a verifier (`[:global]`) and the admin toolset are `:admin`; so is a
developer-profile Run (`workflow_start`/`workflow_attempt` with `profile: developer`,
`catalog_start` on a room), whose agents edit files and run a shell. Listings (`room_list`,
`job_list`) show only the rows the principal may read. A job records the principal that
started it as its owner.

Registry tools are authorized per call, for the principal in the tool context (MCP, operator
commands) or the agent whose turn it is (`:actor`), on the room the call acts for:
`clojure_eval`, `shell` and `run_tests` are `:admin` (the room's whole capability), the others
`:read` or `:write` as their effect is, a tool without a classification `:admin`. A call that
names neither a principal nor an actor is refused; host code passes
`principal/local-principal`. `dvergr.principal-test` checks that every op and every registry
tool has a declaration.

The principal reaches every surface: each MCP connection (and each HTTP request) is
authenticated, every tool call, resource read and subscription is authorized for it, and a
denial is a tool error (`isError: true`, "Not authorized: …") or, for a resource, `-32002`.
A subscription is authorized again before each notification: one whose access was revoked is
dropped. What a client asks for in `_meta` (`dvergr/profile`, `dvergr/toolsets`,
`dvergr/tools`) is **capped** to the principal's scopes (`surface/capped`): `:toolset/<name>`
grants a toolset, `:dvergr/admin` all of them, `:dvergr/read-only` keeps it read-only. The cap
holds for resources too. A client narrows its selection, never widens it; a principal without
scopes sees no tools. Tools registered at runtime (`register-tool!`, the `extra` toolset) have
no authorization of their own and are the local principal's only. An HTTP handshake session
belongs to the principal that opened it: another principal holding its `Mcp-Session-Id` gets
404.

`room_post` posts as the principal (`principal/actor`); its `as` is for the local principal
only, and refused for anyone else. Slash commands (`dvergr.discourse.commands`) run for the
`:principal` their host passes and are authorized on their room as `:command/<name>`:
`/help` reads, a prompt or skill writes (it posts a turn), a handler and every other
built-in (`/model`, `/sandbox`, `/build`, `/fork`, `/invite`) is `:admin`; a command without
a principal is refused. `dvergr.ops/invoke` takes the principal explicitly; there is
no default. In-process code that holds the daemon (the REPL, the TUI, `room-run`, the
experiment runner) passes `principal/local-principal`. A Telegram message is authenticated
once; its ops commands, tool commands and posts all run for that principal.

A host adds its own ops with `ops/register-op!` (a spec entry as in `specification`, with an
`:authz` unless its schema has a `room`): MCP serves it (toolset `host`), the JSON API routes
it, the REPL's `dvergr.ops` has it, and each call is authorized as dvergr's own. A host without
a dvergr daemon calls `ops/invoke` with `{:execution-ctx ctx}` as the daemon.

## Profiles and toolsets

A connection sees one selection of tools, chosen by `--profile` / `--toolsets` (the relay puts
them into `initialize` `_meta` as `dvergr/profile` and `dvergr/toolsets`). Tools outside it are
neither listed nor callable.

| Profile | Toolsets | Tools |
| --- | --- | --- |
| `offload` (default) | rooms, worlds, attempts, catalog, wallets, repl | 26 |
| `bench` | rooms, files, attempts, catalog, bench, wallets | 32 |
| `code` | repl, describe (its REPL calls the ops of rooms, files, worlds, attempts, catalog, bench, wallets) | 2 |
| `readonly` | every read op | read-only only |
| `admin` | everything | all |

| Toolset | What |
| --- | --- |
| `rooms` | create, list, detail, delete (archives: history kept), messages, post |
| `files` | `room_files`, `room_read`, `room_write`: the room's workspace files (in `bench`; add with `--toolsets files`) |
| `worlds` | fork, review, diff, merge, discard |
| `attempts` | `workflow_start` and the job tools; Attempts, Scorecards |
| `runs` | the blocking `workflow_attempt`; Runs |
| `catalog` | `catalog_list`, `catalog_start`: workflows with their own checker |
| `bench` | `catalog_benchmark`, `catalog_check`, `catalog_calibrate`, `catalog_cases`, `catalog_freeze`, `catalog_deploy`, `catalog_export`, `catalog_import`, `experiment_progress`, `experiment_report`, `attempt_export` |
| `wallets` | `room_wallet`, `models_list` |
| `repl` | `clojure_eval` in the room's SCI sandbox |
| `describe` | `repl_describe`: the REPL's API (dvergr namespaces, functions, signatures, docs), filtered by a query |
| `agents` | agent administration (`agent_create`/`agent_update` take `tools`, below), `room_invite` |
| `system` | statistics |
| `admin` | `room_unarchive` brings an archived room back, `room_purge` removes it and its stores for good, `catalog_promote`, `run_resume` (admin profile only) |
| `host` | ops a host registered (`dvergr.ops/register-op!`); none by default |
| `code` | every `dvergr.tools` registry tool but `clojure_eval` and `repl_describe`: files, search, shell, tasks, and the `schedule_*` and `mail_*` tools the daemon registers at start; for hosts without their own (off by default). The list follows the registry (`tools/list_changed`) |
| `extra` | tools registered at runtime that are neither ops nor registry tools (channels) |

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
- **A failure is an error.** An op that refuses or fails throws, and MCP returns it as a tool
  error (`isError: true`) with its message. The JSON API answers with the status the op gave
  it: 400 for a request the caller can fix (an agent with a blank id, a bad field value), 404
  for an unknown agent, or an unknown room in the agent, room file and `experiment_report`
  ops, 409 for a taken agent id or a write that met a concurrent change; a failure without a
  status is 500. Not every op checks its room: several reads answer null for an unknown one.
  `agent_delete` is idempotent: it answers `{deleted: id}` whether or not the agent existed.
- **Schemas** are flat and closed (`additionalProperties: false`, no `oneOf`/`anyOf`/`$ref`) and
  names match `^[a-z][a-z0-9_]{0,39}$`, so strict model APIs accept them; `server_test` checks
  every tool.
- **Long work is a job.** `workflow_start` returns a job at once; `job_status {job, wait-ms}`
  answers when it finishes or after at most 25 s (below every common client timeout), and
  `job_cancel` stops it. A benchmark job's status carries `progress` (`cells-done`,
  `cells-total`, `faults`, and `phase`: `running`, `settling` while its fixture room is
  archived after the last cell, `done`) and `settled`; poll until `status` is no longer
  `running` and `settled` is not `false`, as `room-run` does. A job is a durable Run of its room, and the attempts it starts are its
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
- **A room's files without a checkout.** `room_files {room, path}` lists a directory of the room's
  workspace (name, kind, size, content id; up to 200 entries per call, `offset`/`next-offset` for more),
  `room_read {room, path}` returns a file as `text` (UTF-8) or `base64` with its `media-type`
  (.xlsx, PDF), at most 24 KiB per call (`offset`, `next-offset` for the rest), and `room_write
  {room, files, message}` writes a batch as one commit of the room's repository: `files` maps a
  path to `{"text": …}`, `{"base64": …}` or `null` (remove it), and only those paths are
  committed; writing what is already there commits nothing (`commit` is null). At most 200
  paths and 16 MiB per call, 8 MiB per file. It is all or nothing, with one exception: a refused
  or failed write undoes the batch's own changes, and a path another writer changed meanwhile
  fails it rather than committing their bytes; but a path changed by someone else between the
  write and the undo is left as they made it, and the error names it (`conflicted`). There is
  no client-side compare-and-write argument: each write compares against the batch's own
  snapshot. It refuses while the workspace has staged changes; symbolic links are neither read
  nor written through. Paths stay in the workspace (no `..`, nothing under `.git`, no path the
  sandbox's sensitive-path policy refuses); a read-only world refuses the write and receipts
  the refusal. There is no Git API:
  history is the room's (`room_fork`, `room_diff`, `room_review`, `room_merge`, `room_export`).
  Files are also resources, `room://{room}/files/{+path}` (a file whole up to 4 MiB, a directory as
  JSON).
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
- **A benchmark from your own cases, over MCP alone** (`bench` profile; the case-pack section of
  [room-workflows.md](room-workflows.md) has the details):

  ```
  room_create       {title: "Books", slug: "books"}
  room_write        {room: "books", message: "Cases from 2025",
                     files: {"data/cases.csv": {"text": "no;vendor;text;doc;account;amount\n1;Acme;toner;inv1.txt;4930;119,00\n…"},
                             "data/inv1.txt":  {"text": "Invoice 1: toner 119,00 EUR"}}}
  catalog_cases     {room: "books", name: "invoice-coding", table: "/data/cases.csv",
                     spec: {id: "no", inputs: ["vendor", "text"], attachments: ["doc"],
                            expected: {account: {rule: "exact"}, amount: {rule: "number", tolerance: 0.01}}}}
  room_files        {room: "books", path: "/workflows/invoice-coding"}     # the bundle it wrote
  room_read         {room: "books", path: "/workflows/invoice-coding/certification.edn"}
  catalog_calibrate {room: "books", name: "invoice-coding"}
  catalog_benchmark {workflow: "books/invoice-coding", models: ["…", "…"], room: "books",
                     name: "invoice-coding", cases: 30}
  job_status        {job: <id>, wait-ms: 25000}                         # until settled
  experiment_report {room: "books", experiment: <the job's experiment>}
  ```
- **A benchmark can report into your room.** `catalog_benchmark {workflow, models, room}` (the
  `bench` profile) still
  runs every cell in a world forked from a new fixture room, but keeps the job, its Attempts and
  the Scorecard in `room`, where its dashboards (and `experiment_progress {room}`) show them.
  `name` names the experiment (`experiment` is its deprecated alias): benchmarking again with
  the same name, workflow and models into the same `room` resumes it (cells with a verdict made
  with the current inputs are kept, the others run again). Without `room` each call keeps its results in its own new
  fixture room, and without `name` each call is a new experiment, so neither resumes.
  `cases` takes a dataset's first N cases of a fixed shuffle, `case-ids` names them instead.
- **Reports.** `experiment_report {room, experiment?, scorecard?, baseline?}` renders a room's
  experiment report as Markdown from its store. Its `experiment` is the ExperimentDef content
  id, the `experiment` a `catalog_benchmark` job returns, not the name you passed it; without
  it, the room's newest Scorecard made with the candidates' current inputs. The report's
  paired comparisons (exact McNemar on the cases where two candidates differ, Holm-adjusted)
  are also in its result as `paired`. `scorecard_detail {room, id, baseline?}` returns one
  Scorecard as data: the leaderboard, every cell, the pass rate per check, `comparison`
  (each candidate against `baseline`, default the top of the leaderboard) and `paired` (each
  against `baseline` when given; else every pair, beyond five candidates each against the
  best).
- **Agents.** `agent_create` and `agent_update` take `tools`, a list of tool names (`[]` for
  none); an agent given `tools` gets exactly those, one without them gets the tools its tags
  choose.
- **Money is in the result.** A workflow result has each attempt's spend, the per-model table
  (cost per completed attempt) and the room's wallet afterwards.
- **A query cannot reach the host.** Datahike queries and transactions from the sandbox
  (`clojure_eval`, `d/q` on a room's databases) resolve functions as Datahike's server does:
  pure `clojure.core`, `clojure.string`, subqueries and registered functions, never `slurp`, a
  shell or reflection.
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
  so a connection never reaches a function another one installed; a principal other than the
  local one has sessions of its own besides. Each function runs for the principal and
  selection of the evaluation calling it (`dvergr.mcp.repl/*caller*`), never for whoever
  defined it, and is refused outside an evaluation (a later step of a Spin, another thread). `room-delete` and
  `room-purge` refuse the room the REPL runs in. An agent's own sandbox gets none of this.
- REPL definitions live in the daemon's memory: they do not survive a daemon restart. Keep
  lasting code in the room's workspace.

## Protocol

| | stdio (relay) | TCP (loopback) | Streamable HTTP |
|---|---|---|---|
| Handshake 2024-11-05 … 2025-11-25 | ✓ | ✓ | ✓ (`Mcp-Session-Id`; no GET stream) |
| Stateless 2026-07-28 | ✓ | ✓ | ✓ (headers validated; SSE for listen/progress) |
| `subscriptions/listen` | ✓ (tagged on the shared channel) | ✓ | ✓ (its own SSE stream) |
| Progress | ✓ | ✓ | ✓ (SSE) |
| Cancellation | `notifications/cancelled` | `notifications/cancelled` | closing the stream |

`test/dvergr/mcp/conformance_test.clj` checks the versions in process and over TCP,
`http_test.clj` the HTTP transport, `smoke_test.clj` a daemon through the relay and over HTTP.

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
`POST /mcp` endpoint on its own loopback listener. Every request is authenticated from its
`Authorization: Bearer <token>` (401 when refused; by default the token in
`<state>/mcp/http-token`, mode 0600, created on first start); an `Origin` that is not loopback
is refused (403). Stateless requests must carry
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
response.

A connection answers requests concurrently: every `tools/call` and `resources/read` runs on
its own virtual thread, so a `job_status` wait or a long evaluation does not hold up the
connection's other requests (a `ping`, a list, another tool). The handshake, lists and
notifications stay in order. Responses to concurrent requests may therefore arrive out of
order, as JSON-RPC allows. A request cancelled with `notifications/cancelled` before it
finishes gets no response. Its work is not interrupted: a job keeps running until
`job_cancel`.

## Next

1. Native tools for Codex as a benchmark candidate (Claude Code's CLI already reaches a Run's
   tools over `--mcp-config`, above); a read-only eval (needs a restricted sandbox, not only fewer
   ops) and evals metered to the wallet.
2. Hosted use: an OAuth flow on the HTTP transport (the authenticator seam takes a validated
   token today), and multi-round-trip requests (sampling, elicitation) if a tool needs input
   from the client.
3. A `data` toolset over pg-datahike (load a `pg_dump`, migrate on a fork, merge), and a client
   test pass over Cursor, VS Code, Gemini CLI, n8n and ChatGPT.
