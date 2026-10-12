# Running benchmarks

How to run a benchmark or a case pack, what the numbers in its report mean,
and what an experiment guarantees. `doc/benchmarks.md` describes each
benchmark; `doc/room-workflows.md` the bundle format.

## Run one

Every benchmark has an `experiment/run!` in `benchmarks/` (on the classpath
with `-M:benchmarks`, included in `:dev` and `:test`). Its `:dir` is a dvergr
**home**: the system DB and the stores of the rooms registered there, nothing
else, besides its `home.edn`. `run!` opens it with a headless daemon (no
Telegram, web or MCP listener, no agents, the home's schedules suspended, no
storage GC; `runner/open-home!`), keeps the experiment in a room of it, runs it
(`runner/run-in`) and closes the home again, whatever happens. The home is the
process's for the call, so run it in its own JVM; in a process that runs a
daemon, pass `:daemon` (and an `:experiment-id`) and it runs in that daemon's
home. A moved home is adopted; a copied one is refused.

```clojure
(require '[dvergr.benchmarks.bird.experiment :as bird])
(bird/run! {:dir "runs/bird-held-out" :split :eval :sample 10 :seed 1
            :parallelism 2
            :candidates [{:id :sqlite  :model "codex-subscription-luna" :engine :sqlite}
                         {:id :datalog :model "codex-subscription-luna" :engine :datalog}]})
```

The same shape for `spreadsheetbench.experiment`, `tau2.experiment`,
`bfcl.experiment` and `automationbench.experiment`. A room workflow or case
pack (a directory with `workflow.edn`, `checker.clj`, cases) runs from the
command line:

```bash
clojure -M -m dvergr.catalog.room-run examples/workflows/bank-booking \
  --models codex-subscription-luna,claude-code-haiku --cases 30 --home runs/bank-booking
```

`room-run` calls the ops an MCP client calls, in process (`dvergr.ops/invoke`
on the home's daemon, no MCP transport): it opens the home (default
`$DVERGR_HOME/workflow-runs/<bundle name>` when `DVERGR_HOME` is set, else
`workflow-runs/<bundle name>` in the working directory; a home of its own, since a
daemon may have `DVERGR_HOME` itself open), creates the room `bank-booking` there, writes
the bundle into its workspace with `room_write`, runs `catalog_benchmark` into
that room, polls `job_status` until the job is over and settled, and prints the report from
`experiment_report` (or writes it to `--report FILE`). stdout has progress lines (the job
status's `progress`: cells done of the total, faults, phase) and the report; the
log goes to `<home>/dvergr.log`. The exit code is 1 when cells did not finish. Inside a
daemon, use the `catalog_benchmark` MCP tool (the `bench` profile) or
`runner/run-in`.

A table of historical cases becomes a case pack with
`clojure -M -m dvergr.catalog.casepack-cli <table.csv> --id … --inputs … --outcome col:rule
--out <dir>` (or `dvergr.catalog.casepack/from-table` and `write-dir!`); certification
(`certification.edn`) lists the cases that cannot grade an answer and why: no id, a
duplicate id (every row with it is dropped), unlabelled, conflicting outcomes, a missing
attachment, or a recorded outcome the checker does not accept as the right answer
(`outcome-not-gradable`, e.g. an amount that is not a number).

## What runs where

Every benchmark takes the same path: `runner/run!` → `experiment/run` → one
evaluation per cell in a fork of the experiment Room, certified into its store,
then discarded. What lives in the fork differs:

| Benchmark | The candidate's world |
| --- | --- |
| Room workflows, case packs, wiki | the fork's files and sandbox (Claude Code CLI candidates reach them over MCP) |
| tau2, AutomationBench | world state in the fork's context; tools are host functions over it |
| SpreadsheetBench | an immutable workbook value per episode (isolated by immutability) |
| BIRD | shared databases on the host, read-only: one SELECT/WITH statement per query, no ATTACH, a fresh read-only pg-datahike session per query |
| BFCL | nothing executes; calls are recorded and compared |

## Resume, identity and faults

- Running again on the same home resumes: cells with a verdict made with
  the current inputs (below) are kept, the rest run. A directory of the
  layout before homes (`artifacts/` beside `store/`) is refused, not read.
- Where an experiment is kept depends on the path. `run!` keeps each
  ExperimentDef in a room of its own (`runner/experiment-slug`: its readable
  id and the start of its content id), so a changed experiment never resumes
  into another's room. `room-run` keeps every version in the room `<bundle
  name>` as the experiment `<bundle name>`, and `catalog_benchmark` in its
  `room` under its `experiment` name; a changed bundle or model list there is
  a new ExperimentDef in the same room, and its cells start afresh.
- A cell that faults (the path to the model failed: transport, provider
  limits, a sidecar) has no verdict and runs again, once more within the same
  run by default (`:fault-retries`, default 1), and on every resume. A model
  that answers wrongly, or not at all, gets a verdict: that is a result.
- An ExperimentDef's identity covers its environments, candidates, the
  providers' prompt and grader versions, and the versions of the libraries
  it runs on (spindel, datahike, pg-datahike, rechentafel, kontor). Changing
  any of them starts a new experiment instead of mixing two in one Scorecard.
- What a Run resolves only when it starts is not in the ExperimentDef: the
  assembled system prompt (the sandbox pointer, skills, the workspace's
  AGENTS.md), the tools offered, and the model an alias names. Each Attempt
  records these as its inputs id (`program/run-inputs`); a benchmark protocol
  adds what it declares as its `:inputs` (its own prompt and tool schemas).
  Before any cell starts, the run computes the inputs id each candidate would
  get now and keeps a cell only when its Attempt recorded exactly that. The
  others run again; their old Attempts stay in the store and count in no
  Scorecard, and `run!`'s result counts them as `:stale-cells`. Attempts
  from before inputs ids count as stale for LLM and protocol candidates, so
  such an experiment re-runs their cells once.
- A Scorecard exists only when every cell has a verdict; until then the
  report says how many cells did not finish.

## The report

The report is the experiment room's, rendered from its store by the
`experiment_report` op (MCP, REPL `runner/report dir`, the `:report` of
`run!`'s result, `room-run`'s output). It is no file beside the store:

- **Frontier**: per candidate, attempts, passes with a 95 % interval
  (Jeffreys), mean reward, cost per attempt and per pass, tokens per attempt,
  median time.
- **Resources**: input tokens per attempt (cached ones included), output
  tokens, the share of input read from the provider's cache (– where not
  recorded), median and p90 time per attempt, *summed* time (every attempt's
  own time added up) and *wall clock* (first start to last end), total cost.
- **Paired comparisons**: each pair of candidates on the cases both have a
  verdict for (see below).
- **Where answers fail**: how often each check failed.
- **Cases**: for a case pack, which cases could grade an answer.
- **Inputs changed** (only when it applies): how many cells ran again, or
  await a re-run, because their candidate's inputs changed, and how many
  earlier Attempts are superseded.

Costs are at the models' **list prices**, whether or not a subscription paid
for them: a subscription model names the API model its tokens are worth
(`:list-price-of` in the registry). Cached input is billed at the cache rate.
The console of `room-run` prints both what was billed and the list price.

To compare two candidates, compare them on the same cases (paired): the
experiment runs every candidate on every case, and a paired test (McNemar on
the cases where they differ) separates a difference from run-to-run
variance, which is several points on 100 questions. The report's **Paired
comparisons** table does this for you:

| A | B | Cases | Both passed | A only | B only | Both failed | p | p (Holm) | Verdict |
|---|---|---:|---:|---:|---:|---:|---:|---:|---|
| a | b | 100 | 50 | 12 | 2 | 36 | 0.013 | 0.039 | a better |
| a | c | 99 | 44 | 18 | 7 | 30 | 0.043 | 0.087 | no detectable difference at these cases |

- Read *A only* against *B only*: the cases one passed and the other failed.
  Cases both passed or both failed say nothing about which is better.
- *p* is the exact McNemar test on those two counts; the verdict takes the
  Holm-adjusted p (when more than one pair is shown) below 0.05. 12 against 2
  decides; 6 against 5 does not, and five or fewer disagreements never can
  ("too few disagreements to decide").
- A pass is the Frontier's: every check held (a partial reward is a fail).
- A case counts only where both candidates have a verdict; faulted cells
  leave their case out, and the report says how many.
- With repetitions, a case counts once: the candidate with the higher pass
  rate over the case's repetitions wins it (an exact sign test; *Tied* are
  equal rates that are neither all passes nor all fails).
- Up to five candidates every pair is shown; beyond, each against the one with
  the highest pass rate (Holm still over every pair: picking the best looked
  at all of them). `experiment_report` and `scorecard_detail` take
  `baseline` to compare each candidate against one of your choice, and both
  return the table as data (`paired`).
- "No detectable difference" is not "equal": more cases, or cases where the
  candidates differ, can still separate them.

## Candidates

- **API models** (Codex subscription, OpenAI-compatible such as Fireworks,
  Anthropic API) call tools natively.
- **Claude Code CLI models** (`claude-code-haiku/-sonnet/-opus`): in a room
  workflow they work in the attempt's world through Dvergr's MCP tools (the
  home's daemon serves them on a loopback server on an ephemeral port);
  in a protocol benchmark (BIRD, SpreadsheetBench, BFCL, tau2) they use a text
  tool protocol: only the calls a response opens with run (later ones were
  written without seeing a result), arguments are typed by the tool's
  schema.
- **Subscriptions** are metered by their usage windows. The runner starts
  no new cell once a window is 80 % used or the experiment has taken ten
  points of it since it started (`:allowance`, default `{:share 0.10
  :pause-at 0.80}`; `nil` for no bound): the result carries `:refused`, and
  running again after the reset resumes the rest. Separately, an experiment
  with a Claude Code candidate waits before each pass while a window is at
  least `:usage-pause-threshold` used (default 0.97). The Claude subscription
  is shared with interactive use.

## Tuning and reporting

- Tune on the `:dev` split, report on `:eval` (a fixed third of the questions
  is dev, by digest).
- Confirm a change tuned on any results on questions those results did not
  include: a fresh sample (another seed, excluding the questions already
  looked at).
- Report intervals, and the cost and time beside the success rate.

## Limits today

- One home per JVM at a time (`run!` makes `:dir` the process's home for
  the call); inside a daemon, `run!` takes `:daemon`.
- At `:parallelism` 8 a scripted experiment spends about 0.35 s per cell in
  the harness: every cell writes to the experiment's one store (datahike's
  single writer). A cell's admission (forking its world, the Run's durable
  start) runs off the experiment's drain (spindel `blocking`).
