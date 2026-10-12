# Contributing to dvergr

Thanks for hacking on dvergr! It's a Clojure AI-agent harness built on a
copy-on-write substrate and a reactive (FRP) runtime. This page is the quick
on-ramp; `doc/architecture.md` has the full map.

## Where code goes — the L0–L7 layer model

Dvergr is an 8-layer stack. Each layer depends only on the ones below it, so a
new namespace belongs at the lowest layer that satisfies its dependencies.

- **L0 — CoW substrate / persistence**: config, git CoW, Datahike storage,
  knowledge schema, code parsing. Pure, dependency-free building blocks.
- **L1 — reactive runtime primitives**: spindel ExecutionContext/FRP, pure cron math.
- **L2 — per-chat / per-room state**: `chat.context`/`chat.schema`, accounting,
  participant + per-room contexts, Room store impls, code index.
- **L3 — discourse**: `discourse` core (Room/Participant/Message, fork/merge,
  algebra), `bus`/`peer-bus`/`room.registry`/`room.store`, `rooms`, adapters.
- **L4 — tools + sandbox**: tool registry/executor, SCI sandbox, code-edit utils,
  channels, git worktree management. The intake data sources live in the
  [dvergr-sandbox](https://github.com/replikativ/dvergr-sandbox) repo cloned into
  each room's workspace; only `intake/bash` and `intake/mail` are in-tree.
- **L5 — agent execution**: the turn loop (`chat.agent`), compaction, the model
  abstraction + provider impls, the LLM-backed participant factory, process.
- **L6 — orchestration**: the `daemon`, sessions, scheduler, actors,
  skills, tasks, stats, workflows, Telegram channel.
- **L7 — clients**: `core` facade, MCP server, nREPL client.

## Repo layout

- `src/` — the core library (`dvergr.*`, the L0–L7 namespaces above) — including
  the **web dashboard** (`dvergr.web.*`) and **MCP server** (`dvergr.mcp.*`), which
  are embeddable library features (start them against a running ctx/daemon).
- `src-clients/` — the standalone app frontends: `cli` and `tui` (they own the
  terminal / process entry — not library code).
- `resources/` — agent profiles (`agents/*.md`), skills, phases, models.edn, etc.
- `doc/` — user-facing docs (architecture, state model, getting started, …).
- `examples/` — runnable scenario scripts and walkthroughs.
- `test/` — test suite (kaocha).

## REPL & tests

Tests run under kaocha against the published deps, as CI does:

```bash
clj -M:test                       # full suite
clj -M:test --focus my.ns         # one namespace
```

CI runs the full suite on every push, split over three containers, each on its
share of the namespaces (`.circleci/config.yml`, `dvergr.test-split`), and checks
formatting with `clojure -M:format`. [doc/testing.md](doc/testing.md) shows how to
run one container's share locally and how to write waits that hold.

For co-development, add the `:local` alias so sibling checkouts (`../spindel`,
`../datahike`, `../yggdrasil`, …) override the published deps: `clj -M:local:test`.

Start a REPL (drop `:local` if you don't have the siblings checked out):

```bash
clj -M:local:dev:repl             # nREPL + CIDER middleware
```

**Always use `:reload`, never `:reload-all`** — a transitive dep of
`io.aviso.ansi` hangs indefinitely on `:reload-all`:

```clojure
(require '[dvergr.core :as r] :reload)   ; good
```

Other handy aliases (see `deps.edn`): `:cli` (start daemon + TUI),
`:build` (jar / uberjar / install / deploy), and `:mcp-server`
(`dvergr.mcp.server/-main`: an MCP server without a daemon, so its tools only
answer that no daemon runs; MCP clients use `bin/dvergr-mcp`, see
[doc/mcp.md](doc/mcp.md)).

## Using dvergr as a library

The published artifact ships `src` + `resources` only (the `src-clients/` TUI + CLI
are standalone apps, not the library). To keep the core footprint small, some deps
are **not** in `:deps` — add them in your project if you use that feature:

- **Web dashboard + JSON API** (`dvergr.web.*`) — ships in the library; add the four
  reitit modules to run it: `metosin/reitit-ring`, `reitit-malli`, `reitit-middleware`,
  `reitit-swagger` (~2.5 MB; **not** the `metosin/reitit` uber-bundle, which is 7.8 MB
  of mostly-unused modules). Then `(require '[dvergr.web.server :as web])` +
  `(web/start! daemon)` (or any ctx — see dvergr-playground's `start-web!`). Add
  `metosin/reitit-swagger-ui` (+2.6 MB) only if you want the interactive `/api/v1/docs`
  page; `swagger.json` + all routes work without it.
- **Mail intake** (`dvergr.intake.mail`) — add `io.forward/clojure-mail` +
  `org.replikativ/briefkasten`. Without them the sandbox just has no `intake.mail`.

Heavier transitive weight (ClojureScript/shadow, Lucene) comes from the
sibling libs (spindel, scriptum, briefkasten), not dvergr's direct deps — trimming
those is an upstream task tracked separately.

## Commits & PRs

A commit's summary line says what is true after it, as a plain sentence,
often after the area it concerns and a colon; no type prefixes:

```
Resume keeps a cell only while its inputs hold
Report: paired comparisons with the exact McNemar test
Web: Host, Origin and CSRF checks; state changes are POSTs
A home can be moved: store ids are kept beside the stores; a copied home is refused
```

The body explains why, in prose. Format with `clojure -M:ffix` before
committing (CI fails on unformatted code). Branch off `main` and open a PR; CI
runs the suite, so run the namespaces you touched locally (`--focus`).

Welcome aboard!
