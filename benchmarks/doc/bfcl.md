# BFCL v4 (single-turn, Python)

Part of the [benchmark catalog](../../doc/benchmarks.md); how to run an experiment and read its
report: [doc/running-benchmarks.md](../../doc/running-benchmarks.md).

The Berkeley Function Calling Leaderboard, pinned at gorilla `6ea5797`
(Apache-2.0; a checkout at `../gorilla`, data verified by digest on load).
Eleven categories, 3491 tasks: `simple_python`, `multiple`, `parallel`,
`parallel_multiple`, `irrelevance`, and the user-contributed `live_*`
counterparts plus `live_relevance`.

A task is a question and a set of function docs. The answer is the function
call(s) in the model's **first** response; nothing is executed. The grade is
structural: upstream's AST checker compares the calls with the task's possible
answers (per parameter, a list of accepted values; `""` marks an optional
parameter). Irrelevance tasks expect no call, relevance tasks at least one.

- `bfcl.core`: data, tool compilation (upstream's Python pre-processing and
  `convert_to_tool`, Anthropic style: `.` in a function name becomes `_`), the
  checker with Python's semantics (`type(x) ==` is exact, `bool` is not `int`,
  `1 == 1.0 == True` under `in`) and upstream's quirks (the second loop of
  `dict_checker` overrides the first one's error type; `type_checker` falls
  through after a nested failure).
- `bfcl.provider`, `bfcl.experiment`: the provider and its experiments.
  `:sample n` takes a stable, seeded slice per category.

```clojure
(require '[dvergr.benchmarks.bfcl.experiment :as bx])
(bx/run! {:dir "runs/bfcl-smoke"
          :categories ["simple_python" "parallel" "irrelevance"] :sample 5
          :candidates [{:id :sonnet :model "claude-code-sonnet"}]})
```

## Equivalence

`benchmarks/dev/bfcl/oracle.py` runs **upstream's own code**: the checker is
imported with `model_config` stubbed, and the helper functions of `utils.py`
and `model_handler/utils.py` are executed from their source text, so no
provider SDK is needed. Verified on 2026-09-21:

| Check | Result |
| --- | --- |
| Compiled tool specs, all 3491 tasks | identical |
| A seeded corpus of 37403 candidate answers (gold answers and mutations of type, value, string format, parameters, function names, call order and count) | every verdict and error type identical |
| Branches reached | 3100 type errors, 1699 string mismatches, 2472 parallel match failures, nested-type, dict and list-of-dict errors |

`benchmarks/test/dvergr/benchmarks/bfcl_test.clj` pins the digests of the oracle's
outputs, so the suite proves this without Python whenever the checkout is
present. Grading the corpus takes 1.6 s here and 1.5 s upstream.

## Upstream data faults

Building an accepted answer for every task from its own possible answers found
four tasks that **no answer can pass** (`bfcl/unsatisfiable`, pinned by a
test): `live_multiple_862-181-3` and `live_multiple_964-207-0` (the possible
answers and the function's parameters disagree), `live_simple_106-63-0` and
`live_simple_112-68-0` (required array parameters with an empty list of
accepted values). They cap `live_simple` at 256/258 and `live_multiple` at
1051/1053 for every model. EnvironmentDefs mark them (`:unsatisfiable?`).

## What is comparable

Per-category accuracies are comparable with upstream's columns of the same
name for a function-calling model with `underscore_to_dot`. `bfcl/summary`
also gives upstream's two aggregates over these categories. Not comparable:
upstream's "simple" and non-live overall columns average in the Java and
JavaScript categories, which are not transcribed (their values are
string-encoded literals with converters of their own).

## Candidates

| Candidate | What runs |
| --- | --- |
| `:reference` | the model behind one API call with the compiled tools, as upstream's function-calling handlers do |
| `:dvergr` + `:tools` | one step of Dvergr's agent loop, the functions as JSON-schema tools |
| `:dvergr` + `:repl` | one step with a single `clojure_eval` tool; the functions are `bfcl/<name>` in the sandbox, each taking one map |

Candidate options: `:parallel-tool-calls` (any candidate) and `:repl-guidance`
(the REPL candidate), both recorded in the AgentDef's metadata.

The functions of a `:dvergr` candidate are recorders: they note the call and
return a stub, and the step is never followed by a second one
(`bfcl.harness`). The REPL candidate gets the functions' parameters in its
prompt, which is the information the others get as tool schemas.

## Live checks

Wiring checks on stable ten-task slices, not scores.

2026-09-21, Sonnet through `claude -p` (isolated token), six tasks,
`:reference`: 6/6; about 900 input tokens per task, most of it the CLI's own
system prompt, so a full pass is roughly 3 M input tokens per candidate.

2026-09-21, GPT-5.6 Luna through the Codex subscription provider
(`codex-subscription-luna`), ten tasks (two each of `simple_python`,
`parallel`, `parallel_multiple`, `irrelevance`, `live_multiple`), 200 to 500
input tokens per task:

| Candidate | Passed | Lost on |
| --- | --- | --- |
| `:reference` | 5/10 | all four parallel tasks (one call emitted), one string value |
| `:dvergr` `:tools` | 5/10 | the same |
| `:dvergr` `:repl` | 9/10 | the string value |

The four parallel tasks are lost by construction, not by the model: the Codex
provider sends `parallel_tool_calls: false` in its default (`responses-lite`)
mode, so one response carries one call. The REPL candidate writes every call
into one evaluation and is not affected. This is the first thing the benchmark
found about the harness itself: on that backend, JSON tools cannot express a
parallel action and the REPL can.

The same three candidates on five tasks per category (55 tasks, 165 cells, no
failed cell, about 22 k input tokens for the reference candidate):

| Candidate | Passed | parallel (4 categories, 20 tasks) | irrelevance (non-live) |
| --- | --- | --- | --- |
| `:reference` | 29/55 | 0/20 | 4/5 |
| `:dvergr` `:tools` | 29/55 | 0/20 | 4/5 |
| `:dvergr` `:repl` | 44/55 | 16/20 | 2/5 |

The two findings of that slice, and what was done about them (the slices below
use `:seed 7`, not the seed reported above; BFCL has no train split).

**Parallel calls on the Codex backend.** The endpoint answers 400 to
`parallel_tool_calls: true` in a `responses-lite` request, and the Codex client
itself sends `parallel && !use_responses_lite`; every GPT-5.6 model is lite
there. The provider used to drop a request for parallel calls silently. It now
honours `:parallel-tool-calls true` by sending that request in the full
Responses shape (measured on Luna: two to three calls per response, fewer input
tokens than lite, similar latency); lite stays the default otherwise. What the
full shape costs in subscription quota is not known. A candidate asks with
`:parallel-tool-calls true`; the agent loop passes it as `:model-opts`. Six
tasks per category:

| Candidate | Passed | `parallel` | `parallel_multiple` |
| --- | --- | --- | --- |
| `:reference`, parallel | 20/24 | 6/6 | 3/6 |
| `:dvergr` `:tools`, parallel | 21/24 | 6/6 | 3/6 |
| `:dvergr` `:tools`, default | 11/24 | 0/6 | 0/6 |

The three `parallel_multiple` tasks still lost are value mismatches (a rate of
`4` for `0.04`, `"New York City"` for `"New York"`), the same for both.

**The REPL candidate's eagerness.** The prompt said "You answer by calling
functions", so the model called a near-miss function on irrelevance tasks that
the JSON candidates left alone. `:repl-guidance` selects the wording
(`bfcl.harness/repl-guidance`); eight tasks per category:

| Guidance | Passed | `irrelevance` | `live_irrelevance` | `live_relevance` |
| --- | --- | --- | --- | --- |
| `:direct` (the old wording) | 33/40 | 4/8 | 6/8 | 7/8 |
| `:cautious` (also: answer in text when the request lacks a required value) | 33/40 | 8/8 | 7/8 | 3/8 |
| `:neutral` (call when a function fits, else text; the default) | 35/40 | 6/8 | 7/8 | 6/8 |
| `:dvergr` `:tools`, for comparison | 28/40 | 7/8 | 7/8 | 6/8 |

`:cautious` asks the user for the missing value where BFCL's relevance tasks
expect a call; it is kept as a measured negative. With `:neutral` the REPL
candidate is where the JSON candidate is on relevance and irrelevance. At eight
tasks per category these are directions, not scores.

`bfcl.inspect` reads an experiment home: `open` opens it (a headless daemon on
the home, so in a JVM of its own), then `report`, `failures` and
`print-report`, then `close`.

## Not done

Java and JavaScript categories, multi-turn (eight stateful API simulations:
the natural tier-0 follow-up, as forkable worlds), agentic (memory; web search
needs the network and stays out), a full run.

