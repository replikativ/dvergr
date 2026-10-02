# Running benchmarks

How to run a benchmark or a case pack, what the numbers in its report mean,
and what an experiment guarantees. `doc/benchmarks.md` describes each
benchmark; `doc/room-workflows.md` the bundle format.

## Run one

Every benchmark has an `experiment/run!` in `benchmarks/` (on the classpath
with `-M:benchmarks`, included in `:dev` and `:test`). Run it in its own JVM:
`run!` moves Dvergr's state root into its `:dir`.

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
  --models codex-subscription-luna,claude-code-haiku --cases 30 --out runs
```

The experiment lives in `runs/bank-booking`; `report.md` is written there.
The exit code is 1 when cells did not finish. Inside a daemon, use the
`catalog_benchmark` MCP tool (the `bench` profile) or `runner/run-in`.

A table of historical cases becomes a case pack with
`dvergr.catalog.casepack/from-table` and `write-dir!`; certification
(`certification.edn`) lists the cases that cannot grade an answer and why
(no id, duplicate id, unlabelled, conflicting outcomes, missing attachment).

## What runs where

Every benchmark takes the same path: `runner/run!` → `experiment/run` → one
evaluation per cell in a fork of the experiment Room, certified into its store,
then discarded. What lives in the fork differs:

| Benchmark | The candidate's world |
| --- | --- |
| Room workflows, case packs, wiki | the fork's files and sandbox (Claude Code CLI candidates reach them over MCP) |
| tau2, AutomationBench | world state in the fork's context; tools are host functions over it |
| SpreadsheetBench | an immutable workbook value per episode (isolated by immutability) |
| BIRD | shared databases on the host, read-only: one SELECT/WITH statement per query, no ATTACH, a fresh pg-datahike session per query |
| BFCL | nothing executes; calls are recorded and compared |

## Resume, identity and faults

- Running again on the same `:dir` resumes: cells with a verdict are kept,
  the rest run.
- A cell that faults (the path to the model failed: transport, provider
  limits, a sidecar) has no verdict and runs again, once more within the same
  run by default (`:fault-retries`, default 1), and on every resume. A model
  that answers wrongly, or not at all, gets a verdict: that is a result.
- An experiment's identity covers its environments, candidates, the
  providers' prompt and grader versions, and the versions of the libraries
  it runs on (spindel, datahike, pg-datahike, rechentafel, kontor). Changing
  any of them starts a new experiment instead of mixing two in one Scorecard.
- A Scorecard exists only when every cell has a verdict; until then the
  report says how many cells did not finish.

## The report

`report.md` beside every experiment:

- **Frontier**: per candidate, attempts, passes with a 95 % interval
  (Jeffreys), mean reward, cost per attempt and per pass, tokens per attempt,
  median time.
- **Resources**: input tokens per attempt (cached ones included), output
  tokens, the share of input read from the provider's cache (– where not
  recorded), median and p90 time per attempt, *summed* time (every attempt's
  own time added up) and *wall clock* (first start to last end), total cost.
- **Where answers fail**: how often each check failed.
- **Cases**: for a case pack, which cases could grade an answer.

Costs are at the models' **list prices**, whether or not a subscription paid
for them: a subscription model names the API model its tokens are worth
(`:list-price-of` in the registry). Cached input is billed at the cache rate.
The console of `room-run` prints both what was billed and the list price.

To compare two candidates, compare them on the same cases (paired): the
experiment runs every candidate on every case, and a paired test (McNemar on
the cases where they differ) separates a difference from run-to-run
variance, which is several points on 100 questions.

## Candidates

- **API models** (Codex subscription, OpenAI-compatible such as Fireworks,
  Anthropic API) call tools natively.
- **Claude Code CLI models** (`claude-code-haiku/-sonnet/-opus`): in a room
  workflow they work in the attempt's world through Dvergr's MCP tools (the
  runner serves them from a headless daemon when none runs in the process);
  in a protocol benchmark (BIRD, SpreadsheetBench, BFCL, tau2) they use a text
  tool protocol: only the calls a response opens with run (later ones were
  written without seeing a result), arguments are typed by the tool's
  schema.
- **Subscriptions** are metered by their usage windows: the runner stops
  admitting cells at 80 % of a window (`:usage-pause-threshold`) and resumes
  after the reset. The Claude subscription is shared with interactive use.

## Tuning and reporting

- Tune on the `:dev` split, report on `:eval` (a fixed third of the questions
  is dev, by digest).
- Confirm a change tuned on any results on questions those results did not
  include: a fresh sample (another seed, excluding the questions already
  looked at).
- Report intervals, and the cost and time beside the success rate.

## Limits today

- One experiment per JVM; not inside a daemon (`run!` re-roots the process).
- At `:parallelism` 8 a scripted experiment spends about 0.35 s per cell in
  the harness: every cell writes to the experiment's one store (datahike's
  single writer), and a cell's admission (forking its world, the Run's
  durable start) still runs on the experiment's drain.
