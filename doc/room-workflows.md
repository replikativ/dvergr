# Workflows defined in a room

Status: **design, agreed** (2026-09-27; decisions at the end).

## Why

A catalog workflow (`dvergr.catalog/workflows`: the wiki family) is code in dvergr: a task, an
evaluator, fixtures and gold, a benchmark plan. The goal is that dvergr (an agent in a room,
or a client over MCP such as Claude Code) **creates a workflow, benchmarks it, deploys it and
exports it**, for a user's own use case: competitor discovery for simmis first, then contract
review, CRM hygiene, a Monday account brief. That needs workflows that live in a room, as data
and sandbox code, with the same machinery the built-in ones have.

## A workflow bundle

A directory in the room's repository, `workflows/<name>/`:

| File | What |
|---|---|
| `workflow.edn` | `{:title :doc :params {…defaults} :task "template with {param}" :profile :capture ["/out"] :timeout-ms}` |
| `checker.clj` | a namespace with `(check {:files … :params … :gold …}) → {:checks {k bool} :reward 0..1}`, over the files the Attempt left under the captured directories (receipts later) |
| `gold.edn`, `fixtures/` | the reference facts and the documents a benchmark world starts from, or |
| `generator.clj` | `(world seed opts) → {:docs … :gold …}`, for generated benchmark sets with splits |
| `calibration/` | a reference answer and damaged variants (see below) |

It is ordinary room content: versioned, forked and merged with the room, reviewable as a diff.

## Lifecycle

1. **Author**: `workflows/author!` in the sandbox (or files written through MCP) creates the
   bundle; `workflows/check` validates its shape.
2. **Calibrate**: a checker earns trust the way the wiki benchmark did. `catalog_calibrate`
   scores the reference answer (must score top) and each damaged variant (must lose what it
   damaged). An uncalibrated checker can run, but its Scorecards say so.
3. **Benchmark**: `catalog_benchmark {workflow: "<room>/<name>", models: […]}`; worlds are
   seeded from the fixtures or the generator; Scorecards, ranges, the baseline comparison and
   cost at list price as for any workflow. Candidates include external agents (a CLI or any
   MCP client working in the Attempt's world).
4. **Promote**: the room's owner promotes a calibrated bundle (like skills' `promote!`); its
   verifier trust moves from `:ad-hoc` to `:room` (`dvergr.agent.evaluation/trust-tiers`),
   which Scorecards show. Only host code is `:trusted`.
5. **Deploy**: a schedule in a room (`dvergr.scheduler`) runs the workflow on the room's own
   data, e.g. "competitor watch, weekly", with the winning candidate.
6. **Export / import**: `workflow_export` returns the bundle (an archive of its directory with
   a manifest: content ids, the dvergr version it was calibrated on, the calibration result);
   `workflow_import` installs it in another room
   or on another machine, where `dvergr workflow run <bundle>` (CLI) or the local MCP server
   runs and benchmarks it.

## Trust

The checker runs in a sandbox with no effects except reading the Attempt's captured evidence
(the effect boundary's `:read-only` mode, doc/effects.md; until then, a sandbox without the
network and write namespaces). Its trust tier is part of every Scorecard; a room-authored
checker is `:ad-hoc` until promoted, then `:room`; never `:trusted`.

## Landed

Part 1 (`dvergr.catalog.room`): a bundle is read from `workflows/<name>/` (`bundle-files`,
`read-bundle`, `list-bundles`), checked for shape (`check-files`: a malli schema for
`workflow.edn`, a checker, fixtures), content-addressed (its id is the verifier's `:basis`,
so a changed checker is a different verifier), and turned into a world setup (fixtures
seeded at the world root), an Evaluator (captures `:capture`, runs the checker) and an
experiment plan. The checker runs in a fresh SCI interpreter with the base sandbox's core
and no load path: no files, network, host classes or other code; bounded in time; its
verdict validated. Ops: `catalog_check {room name answer?}` (problems, or the bundle and,
with an answer, the checker's verdict on it), `catalog_list {room}` lists a room's bundles,
`catalog_benchmark {workflow: "<room>/<name>"}` benchmarks one like a catalog workflow.

Part 2: `calibration.edn` holds a reference answer and damaged variants, each naming the
checks it damages. `catalog_calibrate` runs the checker on them: the reference must pass
every check and score highest, each variant must fail what it damaged and score lower.
`catalog_promote` (admin toolset: the owner's connection) calibrates the bundle now and, when
that holds, records its content id on the host, under the state root and outside every
workspace, so sandbox code cannot forge a promotion. From then on its verifier is `:room`;
a changed bundle is a new id, `:ad-hoc` again. `catalog_list {room}` shows each bundle's
tier and `scorecard_detail` lists the tiers among a Scorecard's Attempts
(`:verifier-trust`).

Part 3: `catalog_export {room name}` returns `{manifest files}` (format `dvergr-workflow/1`,
the bundle's content id, the dvergr version, its calibration there); `catalog_import {room
export as?}` installs it as `workflows/<as>/`, committed, refused when the files are not the
bundle the manifest names. Trust does not travel: a host promotes a bundle itself (the same
content on the same host is already promoted). Run one without a daemon from a directory:

```
clojure -M -m dvergr.catalog.room-run path/to/competitors --check
clojure -M -m dvergr.catalog.room-run path/to/competitors --models claude-haiku-4-5,codex-subscription-luna --repetitions 2
```

A bundle with `:fetched true` in `workflow.edn` records its Attempts' effects (`:world
:effects {:record true}`), and its checker is given `:fetched {url body}`: the successful GET
responses of the Attempt, as text (at most 40 pages, 200,000 characters each: markup can be
most of a page, and the words a citation quotes may lie past any cut of the raw HTML). A citation counts only
if the Attempt fetched the page. `calibration.edn` may give `:fetched` for its reference and
variants.

A `:judge {:model … :max-requests n}` in `workflow.edn` answers what a checker cannot decide
by itself (is this new find relevant?): its `judge-requests` returns `[{:id :prompt}]`, the
host asks the model once when the Attempt is observed and keeps the answers as evidence,
and `check` receives them as `:judgements {id answer}`. The checker stays pure; the model is
in the verifier's basis; re-verifying asks no one; `calibration.edn` gives `:judgements` for
its cases.

`:blocked-sources ["simm.is"]` names the hosts a task's answer is published on: the
environment refuses requests to them and their subdomains (`[:deny-hosts …]`, a filter in the
effect algebra that composes by union and sits outside answering handlers, so a frozen web or
a replay cannot answer them either), and freezing leaves them out.

`catalog_freeze {room name as?}` makes a stable benchmark of a live one: a new bundle
(`<name>-frozen`) whose `web.edn` is the pages its live Attempts fetched (as text) and whose
`workflow.edn` says `:web :frozen`. Every attempt in it meets the same web
(`dvergr.io.frozen-web`: pages by URL, search lexical over them, 404 otherwise, no network),
so its scores are comparable over time. It is a bundle of its own, calibrated and promoted
on its own.

`catalog_deploy {room name agent every on? at? params?}` schedules the bundle's task for an
agent of the room (the Scorecard's pick); the room's REPL lists and cancels it
(`dvergr.scheduler/list`, `cancel`).

## The first one: competitor discovery

Task: find products competing with a given one (simmis), each with its site, a one-line
claim and a quote from a page the Run actually fetched. Reference: the simm.is comparison
(Wato, PromptQL, Dust, Buzz). Checks: recall of the reference, every entry's quote present in
a fetched page (HTTP receipts, as `benchmarks/discovery_citations.clj` does), every URL
resolved, no duplicates, and relevance of new finds (a cheap judge tier, or reviewed). A live
run is recorded and frozen by replay (doc/effects.md) into a stable benchmark, with a "live"
variant. Search through the configured `BRAVE_API_KEY` (approved; queries counted).

## Decisions (agreed 2026-09-27)

- **Layout**: `workflows/<name>/` with `workflow.edn`, `checker.clj`, `gold.edn`, `fixtures/`,
  optional `generator.clj`, `calibration/`.
- **Checkers run in SCI**: sandboxed and portable (the same checker in dvergr, simmis and on
  a user's machine after export); a host fast path later if checkers get slow.
- **Export**: a plain archive plus a manifest (content ids, the dvergr version it was
  calibrated on, the calibration result); history stays in the room.
- **Search**: the configured `BRAVE_API_KEY` may be used, sparingly; queries are counted in
  the benchmark's records.
