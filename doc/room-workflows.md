# Workflows defined in a room

Status: **implemented** (see Landed); the Lifecycle below is the original design
(agreed 2026-09-27; decisions at the end).

Over MCP, the ops on this page (`catalog_check`, `catalog_calibrate`, `catalog_benchmark`,
`catalog_cases`, `catalog_freeze`, `catalog_deploy`, `catalog_export`, `catalog_import`) are in
the `bench` toolset: connect with `--profile bench` (the default `offload` profile does not
list them; doc/mcp.md). The profile also has the `files` toolset (`room_files`, `room_read`,
`room_write`), so a client can put a table into a room and read a bundle back. `catalog_promote`
is in the `admin` toolset.

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
| `calibration.edn` | a reference answer and damaged variants (see below) |

It is ordinary room content: versioned, forked and merged with the room, reviewable as a diff.

## Lifecycle

1. **Author**: write the bundle's files into `workflows/<name>/` of the room (from the
   sandbox or through MCP); `catalog_check` validates its shape.
2. **Calibrate**: a checker earns trust the way the wiki benchmark did. `catalog_calibrate`
   scores the reference answer (must score top) and each damaged variant (must lose what it
   damaged). An uncalibrated checker can run, but its Scorecards say so.
3. **Benchmark**: `catalog_benchmark {workflow: "<room>/<name>", models: […]}`; worlds are
   seeded from the fixtures or the generator; Scorecards, ranges, the baseline comparison and
   cost as for any workflow (billed and list price, see below). Candidates include external agents (a CLI or any
   MCP client working in the Attempt's world).
4. **Promote**: `catalog_promote` (the `admin` profile only) calibrates the bundle again and,
   when that holds, raises its checker's trust from `:ad-hoc` to `:room`
   (`dvergr.agent.evaluation/trust-tiers`), recorded on the host in
   `<home>/workflow-promotions.edn` (outside every workspace). Scorecards show the tier. It
   does not pick a winning candidate or variant, and nothing requires it: an unpromoted
   bundle benchmarks and deploys as well, its Scorecards say `ad-hoc`. Only host code is
   `:trusted`.
5. **Deploy**: `catalog_deploy` puts a schedule in a room (`dvergr.scheduler`) that gives the
   workflow's task to an agent you name (a participant of the room), e.g. "competitor watch,
   weekly"; you pick the agent, e.g. from the Scorecard.
6. **Export / import**: `catalog_export` returns the bundle (its files and a manifest:
   content id, the dvergr version it was exported from, the calibration result);
   `catalog_import` installs it in another room or on another machine, where
   `dvergr.catalog.room-run` or the local MCP server runs and benchmarks it (Part 3).

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
content on the same host is already promoted).

An export is an EDN map, not an archive. To run it from a directory, write its `:files`
under a directory named like the bundle: `(dvergr.catalog.casepack/write-dir!
"competitors" export)` writes every path of `:files` under `competitors/`. Then run it
from the shell, without a daemon of your own:

```
clojure -M -m dvergr.catalog.room-run path/to/competitors --check
clojure -M -m dvergr.catalog.room-run path/to/competitors --models claude-haiku-4-5,codex-subscription-luna --repetitions 2 --home runs/competitors
```

`room-run` is a client of the room path, the one an MCP client takes: it calls the same ops in
process (`dvergr.ops/invoke` on the home's daemon, no MCP transport). It opens the dvergr
home `--home` (default `$DVERGR_HOME/workflow-runs/<bundle name>` when `DVERGR_HOME` is set,
else `workflow-runs/<bundle name>` in the working directory) with a headless daemon, creates the room
`<bundle name>` there once, writes the bundle into its workspace as `workflows/<bundle name>/`
with `room_write` (and removes what the bundle no longer has), and runs `catalog_benchmark`
into that room as the experiment `<bundle name>`; `--check` runs `catalog_calibrate` instead.
Everything it keeps is in the home's stores: the bundle in the room's repository, the Runs,
Attempts and Scorecard in the room's Datahike store. Running it again resumes, keeping cells
that have a verdict made with the current inputs (the assembled system prompt, the tools, the
resolved model: doc/running-benchmarks.md); cells that faulted run once more within the run. A
changed bundle or model list is a new experiment in the same room, so its cells run afresh. `--cases N` takes the
first N cases of a dataset in a fixed shuffle (seed 20261001), so the same N gives the same
cases every time. It prints each candidate's reward and two costs, then the report from
`experiment_report` (to stdout, or to `--report FILE` when given), and exits 1 when cells did
not finish. A `claude-code-*` candidate works through dvergr's MCP tools, which the home's
daemon serves on its home's Unix domain socket. `dvergr.catalog.room/experiment!` (and
`dvergr.catalog.room-run/benchmark!`) is the same path from the REPL.

**Billed and list price.** `billed` is what the run was charged: about $0 for subscription
candidates (`codex-subscription-*`, `claude-code-*`). `list price` is the same tokens at the
list price of the API model a subscription model corresponds to (its registry entry's
`:list-price-of`; for any other model it equals the bill), which is what compares models
(`dvergr.agent.spend`); the report uses it.

**In a daemon**, `catalog_benchmark` runs the experiment in a room (`runner/run-in`) as a
job (budget per attempt: $0.50 unless given; `cases` takes the first N cases, as above).
With `name` (`experiment` is its deprecated alias) the experiment keeps its identity: benchmarking again with the
same name, workflow and models into the same `room` resumes it (cells with a verdict made with
the current inputs are kept). Without `room` the results stay in the new fixture room, and
without `name` every call is a new experiment, so neither resumes. `case-ids` names the
cases instead of `cases`; `params` and `prompt` override the workflow's task parameters and the
candidates' system prompt. `experiment_report {room, experiment?}` renders the report from the
room's store. Its `experiment` is not the `name`: it is the ExperimentDef content id, which the
job returns as `experiment` (beside its `scorecard` once completed).

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

`:allowed-sources ["dust.tt" "github.com"]` names the only hosts a task may read (and their
subdomains): `[:allow-hosts …]`, refusing others with `:not-allowed`. Two allowlists meet in
what both allow (`{x.com}` and `{docs.x.com}` meet in `{docs.x.com}`, kept minimal so
composition stays associative); with a blocklist, a host must be allowed and not blocked.

`:blocked-sources ["simm.is"]` names the hosts a task's answer is published on: the
environment refuses requests to them and their subdomains (`[:deny-hosts …]`, a filter in the
effect algebra that composes by union and sits outside answering handlers, so a frozen web or
a replay cannot answer them either), and freezing leaves them out. An attempt that
reached for one fails the check `:no-blocked-fetch?`: each Attempt's world has a receipt sink
every sandbox in it writes to, and the capture counts its denials by who decided them
(`:denials {:blocked n :authority n …}`, kept as evidence).

`catalog_freeze {room name as?}` makes a stable benchmark of a live one: a new bundle
(`<name>-frozen`) whose `web.edn` is the pages its live Attempts fetched (as text) and whose
`workflow.edn` says `:web :frozen`. Every attempt in it meets the same web
(`dvergr.io.frozen-web`: pages by URL, search lexical over them, 404 otherwise, no network),
so its scores are comparable over time. It is a bundle of its own, calibrated and promoted
on its own.

**Datasets.** A bundle with `cases/<id>/fixtures/…` and `cases/<id>/gold.edn` is a dataset:
one environment per case (its fixtures over the shared ones, its gold), the case in the
environment's metadata; `calibration.edn` names the case whose gold it scores against
(`:case`). `examples/workflows/contract-review/` is one: eight CUAD contracts
(`dev/cuad_bundle.py` builds more).

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
  optional `generator.clj`, `calibration.edn`.
- **Checkers run in SCI**: sandboxed and portable (the same checker in dvergr, simmis and on
  a user's machine after export); a host fast path later if checkers get slow.
- **Export**: the bundle's files plus a manifest (content id, the dvergr version it was
  exported from, the calibration result), as one EDN map; history stays in the room.
- **Search**: the configured `BRAVE_API_KEY` may be used, sparingly; queries are counted in
  the benchmark's records.

## Case packs: a benchmark from your own history

`catalog_cases {room name table spec}` (`dvergr.catalog.casepack`) turns a table of
historical cases in a room's workspace into a dataset bundle: the same workflow,
benchmarked against the outcomes those cases actually had. Most organisations have this
already: invoices with the account they were booked to, tickets with the team that solved
them, applications with the decision.

- **The table** is CSV (`,`, `;` or tab, whichever the header line has most; quotes, a
  byte-order mark, as Excel and DATEV write it),
  JSON lines or EDN. `spec` names its columns: `id`, `inputs` (written to the case's
  `/docs/case.edn`), `attachments` (text files beside the table, copied to `/docs/`),
  `expected {column {:rule …}}`, and optionally `title`, `task` (else one is written from the
  fields) and `doc`. The task always ends with the answer contract, generated from
  `expected`: write `/out/answer.edn`, an EDN map from each expected column (as a string) to
  its value. A task you write says what to do with a case; it need not repeat where the
  answer goes.
- **Rules** per expected field: `exact` (numbers as numbers), `ci` (ignoring case and
  spacing), `number` (within `tolerance`; `1.234,56` and `1,234.56` both read), `set` (a
  vector, or text split on `, ; |`) and `date` (`yyyy-mm-dd` or `dd.mm.yyyy`). The generated
  checker scores the attempt's `/out/answer.edn` field by field; the reward is the share right.
- **Certification** keeps only cases that can grade an answer and says why the others
  cannot: no id or a duplicate one (every row with that id is dropped, not just the second:
  which one is right is not known), an outcome left empty, a missing attachment, the same
  inputs with different outcomes (the history disagrees with itself), or an outcome the
  checker does not accept as right (an amount that is not a number). `certification.edn` in
  the bundle lists every excluded case with its reasons; the op returns the counts. On real
  data this report is a first result in itself: which of your cases are usable.
- **Calibration** is generated: the reference is a certified case's outcome, and each field
  has a damaged variant that must lose that field's check. Then `catalog_calibrate`,
  `catalog_benchmark` and the rest apply as to any bundle.

Attachments are text for now (a PDF needs its text extracted first).

**Without a daemon.** The table need not be in a room. From the shell,
`dvergr.catalog.casepack-cli` writes the bundle directory (its name is the bundle's), which
`room-run` then benchmarks:

```bash
clojure -M -m dvergr.catalog.casepack-cli export/bookings.csv \
  --id no --inputs vendor,text --outcome account:exact --outcome amount:number:0.01 \
  [--attachments document] [--task "…"] [--title "…"] [--delimiter ";"] --out packs/bookings
clojure -M -m dvergr.catalog.room-run packs/bookings --check
clojure -M -m dvergr.catalog.room-run packs/bookings --models claude-code-haiku,codex-subscription-luna
```

`--outcome column:rule[:tolerance]` is given once per expected column. The same from Clojure:
`dvergr.catalog.casepack/from-table` reads the table (attachments beside it) and `write-dir!`
writes the bundle:

```clojure
(require '[dvergr.catalog.casepack :as casepack])
(casepack/write-dir! "bookings" (casepack/from-table spec "export/bookings.csv"))
```

**Over MCP, from scratch.** In a daemon, `catalog_cases` reads `table` from the room's
workspace, and `room_write` puts it there (the `bench` profile has both): a client needs no
checkout and no agent in the room. The whole flow:

1. `room_create {title: "Books", slug: "books"}`
2. `room_write {room: "books", files: {"data/cases.csv": {"text": "no;vendor;…"}, "data/inv1.txt":
   {"text": "…"}}, message: "Cases from 2025"}`: one commit with exactly these paths. Binary
   files (an .xlsx export, a scanned PDF) go as `{"base64": …}`; `null` removes a path.
3. `catalog_cases {room: "books", name: "invoice-coding", table: "/data/cases.csv", spec: {…}}`
   writes the bundle to `/workflows/invoice-coding/`; `room_files` and `room_read` show it
   (`certification.edn` says which cases were kept and why the others were not).
4. `catalog_calibrate {room: "books", name: "invoice-coding"}`
5. `catalog_benchmark {workflow: "books/invoice-coding", models: [...], room: "books",
   name: "invoice-coding"}`, then `job_status` until it is over and `settled`.
   Each `job_status` waits up to 25 s and shows `progress` (cells done of the total, faults,
   the phase).
6. `scorecard_list {room: "books"}` and `scorecard_detail {room: "books", id}`.
7. `experiment_report {room: "books", experiment}` (the `experiment` content id
   `catalog_benchmark` returned, not the name given): the report as Markdown, with the
   frontier, the resources, the paired comparisons of the candidates (`baseline` to compare
   each against one), the failing checks and the cases that could not grade.

To change the table, `room_write` it again and rerun `catalog_cases`; the room's history keeps
every version.

## Review: from a retained world to a decision

A workflow Run that keeps its world (`:settlement :review`) leaves a fork to adopt.
`dvergr.agent.review/review!` decides on it under a spec (portable data, e.g. on the
bundle):

```clojure
{:review/checks     [:fork/review-tier [:diff/only-paths ["wiki/"]] [:diff/max-files 40] :wiki/v4]
 :review/reviewer   {:model "claude-haiku-4-5"
                     :instructions "Approve faithful updates; escalate anything that contradicts a source."}
 :review/on-pass    :merge        ; or :propose: approved, the merge left to a person
 :review/on-fail    :escalate     ; or :discard
 :review/spot-check {:rate 0.1 :seed 7}}
```

1. Checks, deterministic and first: no conflicts; every changed or uncommitted file under
   the workflow's declared paths and no other system changed (a workflow writes only what
   it declares: membership, grants, code, schedules and the bundle itself always go to a
   person); at most N files; a catalog checker without gold (`:wiki/v4` scores the
   world's wiki with `score-structure`). A failed check escalates (or discards) and the
   reviewer is never asked.
2. The reviewer, when the spec names one: a function the host passes, given the world,
   its state token, the changed files and the check results, returning
   `{:review/verdict :approve|:escalate :review/reasons [..] :review/concerns [..]}`.
   Scripted in tests; a model-backed reviewer runs as its own Run and never edits the
   world.
3. A deterministic spot check (`hash(review id, seed) < rate`) sends a sample of approved
   reviews to a person.
4. The decision: merged, pinned to the state token that was reviewed (a world that
   changed since is not merged and escalates), through `ops` as a principal when the host
   passes `:invoke`; escalated, with a `:review/escalated` message in the room; or
   discarded.

Each review is one immutable Review record in the room's store (`PReviewStore`, memory
and Datahike): world, state token, checks with evidence, verdict, decision, the policy's
content id. `review_list` (MCP toolset `reviews`; `decision: escalated` is what waits
for a person) lists them.

For KB wikis, `wiki-v4/kb->pages` projects tiptap blocks into the same scorer
input, and `:diff/only-kb-pages` restricts review to wiki datoms in one named KB.
See [the KB wiki contract](kb-wiki.md) for the projection and composing it with
`only-paths`.

For a model reviewer, the host constructs a callback for one review:

```clojure
(review/review! room world spec
  :reviewer (review/model-reviewer daemon room world spec caller-principal))
```

`model-reviewer` takes `[daemon room world spec caller]`. The spec's
`:review/reviewer` requires `:model` (a registry model id or alias) and `:instructions`.
It hires a separate `:llm` Run in `room`, with `:settlement :discard`, a four-step
limit and a $0.50 budget. The model has no callable tools: its read access is an EDN
snapshot in the task containing the usual review input, plus `:review/fork-diff`,
`:review/fork-review` and `:review/files`. The last maps changed paths to `room_read`
file data (including text or base64), or `{:deleted? true}`. Declared paths come from
`[:diff/only-paths prefixes]`; unreadable or oversized files fail closed. Instructions
and checks' evidence are included. File contents are identified as untrusted data.
The reviewer cannot edit the retained fork, and its Run's world is discarded,
including when it exhausts its limit and stops at `:waiting`.

The final answer must be exactly one EDN map, without fences or trailing text:
`{:review/verdict :approve|:escalate :review/reasons ["…"] :review/concerns ["…"]}`.
Both lists are vectors of strings. Malformed answers and failed Runs escalate.

The callback carries an ops invocation in its metadata, which `review!` uses for
settlement even if `:invoke` is supplied. Its principal is an agent run token with
actor `:reviewer`, pinned to the retained fork id, with tools
`#{"room_diff" "room_review" "room_merge"}` and the caller's
`:principal/delegation` claim unchanged. Diff/review reads and the state-pinned merge
pass through `ops/invoke` and the host authorizer as that principal. The parent's
`:merge` authorization request keeps the parent in `:resolved :room` and adds
`:resolved :via-room` naming the already authorized fork, so the fork pin permits
this parent check. Other room operations remain pinned. The model never
receives the token or the merge capability. `review!` revokes the token after the
decision, also when checks fail; the callback is for one `review!` invocation.
The principal has no discard permission in its tool pins.

Next: starting the review from workflow settlement, and the reviewer benchmark
(generated good and damaged updates: does it approve the good and escalate the bad?).
