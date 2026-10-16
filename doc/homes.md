# Homes

A dvergr **home** is the directory a process keeps its state in: the system DB, the stores of
the rooms registered there, and a few files beside them. A daemon runs on one home, and an
experiment is kept in a room of one. This page says what is in a home, how a process picks
it, how an experiment opens one of its own, and what happens when a home is moved or copied.

## Which home a process uses

`dvergr.substrate.paths/home` resolves the home, in this order:

1. an explicit `(dvergr.substrate.paths/set-home! "…")`;
2. the `DVERGR_HOME` environment variable;
3. `./.dvergr`, relative to the working directory (project state, not per user).

A process has one home at a time, and one daemon on it. `runner/open-home!` refuses to start
when the process already runs a daemon.

The config file is not part of the home. `dvergr.substrate.config` reads the process's
`$DVERGR_CONFIG`, else `./config.local.edn` in the working directory, whatever home is open.
So a headless experiment home (below) gives a room sandbox the `:secrets` of that file, as an
interactive daemon does. The Claude Code setup token is per user too
(`~/.dvergr/secrets/claude-oauth-token`, or `DVERGR_CLAUDE_TOKEN_FILE`), outside any home.

## What is in a home

```
<home>/
  home.edn                    the home's id and the path it was last opened at (dvergr.system.home)
  system-db/  system-db.id    the system DB: parties, systems, rooms, grants, actors (dvergr.system.db)
  systems/                    every room's stores, one directory per store:
    <uuid>/   <uuid>.id         a room's messages store, its KB, its Geschichte repo, agent :data stores
    <uuid>-ft/                  the fulltext index of a messages store (dvergr.search.secondary)
  geschichte-workspace/       the fallback Geschichte workspace store (room-less sandboxes)
  workspace/                  the sandbox load root for room-less sandboxes (a clone of the sandbox stdlib)
  blobs/                      /drive blobs, unless :blob-store names another backend
  local-token                 the owner's credential (mode 0600): MCP over TCP and HTTP, the web
  mcp.sock                    the MCP server's Unix domain socket (bin/dvergr-mcp connects here)
                              (a home from before keeps its mcp/http-token's value here)
  mcp/tools-*.edn             the stdio relay's tool cache (bin/dvergr-mcp)
  mcp-daemon.nrepl-port       written when bin/dvergr-mcp starts a daemon for itself
  logs/mcp-daemon.log         that daemon's log
  daemon.pid                  the CLI daemon's pid, which the relay checks
  claude-config/              the empty Claude Code config directory candidates run with
  dvergr.log                  the log, when the process logs to a file (the CLI, room-run)
  workflow-runs/<bundle>/     room-run's default homes, when DVERGR_HOME is set (homes of their own)
```

Other files appear on use: `transcripts/`, `mail/<account>/`, `exports/` (`attempt_export`),
`workflow-promotions.edn`, `jail/`. Every Datahike store has its id in a file beside it,
`<store>.id` (`dvergr.substrate.store-ids`). Konserve's store id is a logical id the caller
chooses; dvergr records it once, when it creates the store, so it does not change when the
directory moves. The `.nrepl-port` of an interactive REPL is at the project root, not in the
home.

## Moved and copied homes

`home.edn` lets a home tell a move from a copy. Before any store opens, the daemon calls
`dvergr.system.home/claim!`:

- a home without `home.edn` gets a fresh id (`:created`);
- a home opened at the path it records is the same home (`:same`);
- a home opened at another path, whose recorded path no longer holds a home with that id, was
  **moved**: it is adopted and its path updated (`:moved`). `dvergr.system.db/rehome-scopes!`
  then points the registry's store paths at this home's `systems/`;
- a home opened at another path while its original is still there, with the same id, is a
  **copy** and is refused. A copy holds the same stores under the same ids, so running it as a
  home of its own would make two writers of one logical store.

Dvergr never copies stores. To try something on a home's data without changing it, fork the
room (a copy-on-write fork, [worlds-and-forks.md](worlds-and-forks.md)).

## An experiment's home

Every benchmark's `experiment/run!` takes `:dir`, a home. `dvergr.agent.experiment.runner/run!`
is three steps:

- `(dvergr.agent.experiment.runner/open-home! dir)` makes `dir` the process's home and starts a
  **headless daemon** on it (`dvergr.orchestration.daemon/start!` with
  `{:agents {} :headless? true :gc false}`): no Telegram, web or MCP listener, no agents
  (neither configured, autostart nor room members), the home's schedules suspended
  (`dvergr.rooms.scheduler/suspend!`), no storage GC. A Claude Code candidate that works in a
  room reaches it over MCP on the home's Unix domain socket
  (`dvergr.mcp.server/ensure-listening!`). Returns the handle `close-home!` takes.
- `(dvergr.agent.experiment.runner/run-in room opts)` runs the experiment in a room of that
  home, as a Spin.
- `(dvergr.agent.experiment.runner/close-home! handle)` stops the daemon (and an MCP server a
  candidate started) and gives the process back its previous home and Claude Code settings,
  whatever happened.

`(dvergr.agent.experiment.runner/with-home dir f)` calls `(f daemon)` between the two. In a
process that already runs a daemon, pass `run!` `:daemon` (and an `:experiment-id`) instead
of `:dir`: the experiment then lives in that daemon's home. So run `run!` with `:dir` in its
own JVM. A directory of the layout before homes (`artifacts/` beside `store/`, or `store/`
and `home/`) is refused (`dvergr.agent.experiment.runner/old-layout?`), not read.

`dvergr.catalog.room-run` opens a home the same way: `--home DIR`, default
`$DVERGR_HOME/workflow-runs/<bundle name>` when `DVERGR_HOME` is set (a home of its own: a
daemon may have `DVERGR_HOME` open), else `workflow-runs/<bundle name>` in the working
directory (`dvergr.catalog.room-run/default-home`). Its log is `<home>/dvergr.log`.

### Which room keeps an experiment

- `run!` keeps each ExperimentDef in a room of its own,
  `dvergr.agent.experiment.runner/experiment-slug`: the readable id (up to 40 characters) and
  the first 8 characters of the content id. A changed provider prompt or grader version,
  candidate list or library version is a new ExperimentDef, so a new room.
- `room-run` keeps every version in the one room `<bundle name>`, as the experiment
  `<bundle name>`. `catalog_benchmark` keeps it in its `room` (default the new fixture room)
  under its `name` (`experiment` is the deprecated alias). A changed bundle or model list is a
  new ExperimentDef in that same room. `experiment_report` takes the ExperimentDef content id
  the job returned as `experiment`, not the name.

### Resume

Running again on the same home (and the same experiment) resumes. A cell with a verdict is
kept only while its candidate would get the same inputs now: what a Run resolves when it
starts (the assembled system prompt, the tools offered, the model an alias names) is recorded
on each Attempt as its inputs id (`dvergr.agent.program/run-inputs`). Cells made with other
inputs, and faulted cells, run again; their old Attempts stay in the store and count in no
Scorecard. Attempts from before inputs ids count as stale and run again once.
[running-benchmarks.md](running-benchmarks.md#resume-identity-and-faults) has the details.

### Reading a home back

- `(dvergr.agent.experiment.runner/progress dir)`: what the experiments kept in the home have
  done (the `experiment/progress` op of each experiment room).
- `(dvergr.agent.experiment.runner/report dir)` or `(report dir room)`: the
  `experiment/report` Markdown of the home's only experiment room, or of `room`.
- `(dvergr.agent.experiment.runner/open-experiment dir)`: `{:home :room :store :room-id}` to
  read the records with the store functions; `close-experiment` closes it.

Each of these opens the home with a headless daemon, so call them where no daemon runs.

## Storage GC

Datahike's indices are immutable: every transaction writes new index nodes and leaves the
superseded ones behind, and only a GC frees them. With `:gc {:enabled? true}` a daemon runs
`dvergr.system.rooms/gc-stores!` (the system DB and every room's stores) on a thread of its
own, once at boot and then every `:interval-ms`. It is set by `:gc` in `config.local.edn`:

```clojure
:gc {:enabled? true          ; default OFF, see below
     :interval-ms 21600000   ; every 6 h (default)
     :retention-days 30}     ; default nil: keep all history
```

**Storage GC is off by default.** Datahike's `gc-storage` fails open: when its mark is
incomplete (a branch walk that dies on an Error), the keep-set shrinks to `#{:branches}`
and the sweep deletes every other key of the store; that wiped a system DB on 2026-09-29.
Until a datahike that fails closed (datahike#1117) is released and dvergr depends on it,
deleting GC (`gc-stores!`, `dvergr.room/gc!` without `:dry-run?`) is refused unless the
daemon enables it, for that daemon's home and while it runs; keep a backup when you do.
A dry run is always allowed; it skips the system DB, whose GC has no dry run. A system-DB
GC that returns nothing or times out is logged as a failure (`:rooms/gc-failed`), not as
`:reclaimed 0`; a room's reports come from the yggdrasil adapters, which still report a
dead collection as zero reclaimed.

With `:retention-days` nil, the sweep keeps every snapshot and reclaims only what no snapshot
or branch head reaches. With a number, snapshots older than that are collapsed:
history before the cut-off is gone. At boot the daemon also removes the Geschichte workspace
branches of forks from earlier runs (`dvergr.system.rooms/gc-orphan-fork-branches!`): forks
live in memory and do not survive a restart.

An experiment's headless daemon runs no storage GC (`:gc false`): its stores live as long as
the experiment.
