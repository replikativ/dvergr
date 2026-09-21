# Benchmarks

Dvergr runs public benchmarks natively, as Clojure transcriptions whose
environment state is an immutable value. A fork is a structural share and a
replay is a reduction, so resetting and branching an environment costs nothing.
A transcription counts as the benchmark only after it has been shown
equivalent to the upstream implementation (see *Equivalence method* below).

The providers are not part of the `dvergr` jar. They live in
[`benchmarks/`](../benchmarks/README.md) and ship as
`org.replikativ/dvergr-benchmarks` (in this repo: `clj -A:benchmarks`; the
suite, `clojure -M:test`, includes them). `dvergr` itself keeps what a
benchmark is built with, so that a benchmark of your own is one more provider
in your own repo: `dvergr.agent.evaluation`, `environment`, `experiment`,
`experiment.runner`, `verifiers`, `roster`.

| Tier | Meaning | Status |
| --- | --- | --- |
| 0 | Fully native, in-memory, forkable | tau2-bench retail, airline, banking_knowledge (bm25), telecom; BFCL v4 single-turn (Python) |
| 1 | Frozen/recorded IO, no containers | planned |
| 2 | Container-bound public leaderboards (Terminal-Bench, SWE-bench) | calibration only, via an external adapter |

## What a benchmark brings

Every benchmark is a *provider* on one evaluation path
(`doc/evaluation-model.md`): each cell of an experiment is one
`dvergr.agent.evaluation/evaluate`, a certified Attempt in a forked world that
is discarded afterwards. Two benchmarks of opposite shape run on it without a
change to the path, which is the evidence that the contract is general:

| | tau2 | BFCL |
| --- | --- | --- |
| Shape | multi-turn conversation with a simulated customer | one question, one response |
| World | a database that tools mutate | none |
| Driver | yes (the customer, a paid model) | no |
| Judge | an LLM for natural-language assertions | none |
| Grade | final world, actions, communicated facts | the structure of the emitted calls |
| Tokens to validate the transcription | none | none |

A provider is a namespace (`dvergr.benchmarks.<name>.provider`) with:

1. **`capabilities`**: `{:world-setup :protocol :evaluator}`, trusted closures
   made with `evaluation/make-world-setup`, `make-protocol` and
   `make-evaluator`.
   - *World setup* installs the task's initial world in the Run's forked world
     and returns evidence about it (tau2: the initial DB hash; BFCL: the digest
     of the tool specs the candidate is given).
   - *Protocol* is the interaction the Run hosts: tau2's conversation, BFCL's
     single model step. It returns portable data, never the grade.
     Its model calls are the Run's: the protocol worker runs under the Run's
     `resource/*model-scope*`, and the protocol is given the scope as
     `:model-scope` for calls that happen on other threads (`live/scoped`
     wraps a generate fn with it). An environment that declares
     `model-dispatches` resources therefore bounds the candidate AND the
     driver; the judge runs in verification, outside the Run's wallet.
   - *Evaluator* observes the finished Run and verifies the evidence. It
     carries a trust tier, recorded on every receipt. One boolean check per
     reason a candidate can lose makes a Scorecard say *why*.
2. **Private data stays in the closures.** Gold actions and possible answers
   never appear in an EnvironmentDef, which names a task by id only. The same
   boundary serves a benchmark one agent writes for another.
3. **`environment-def`**: the content-addressed EnvironmentDef of one task. The
   upstream revision and every model that is part of what a score means
   (driver, judge) are in the capability references, hence in its content id.
4. **A candidate roster**: AgentDefs; what varies (model, action space, prompt
   digest) is in the AgentDef so that a change is a new candidate, never
   resumed into old cells.
5. **An equivalence proof** when the benchmark is a transcription (below), and
   a scripted candidate that replays gold answers, so the whole path is tested
   end to end without a model.

`dvergr.agent.experiment.runner/run!` is everything else: the experiment directory
and Room, Claude Code settings for the run, waiting out subscription usage
windows, resume, the Scorecard. A provider's `experiment/run!` is a call to it.

Still shared by accident, not by design: the Python-semantics layer and the
JSON reader live under `dvergr.benchmarks.tau2` (`python`, `pyjson`) and BFCL
requires them from there.

## BFCL v4 (single-turn, Python)

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
(bx/run! {:dir ".dvergr/benchmarks/bfcl-smoke"
          :categories ["simple_python" "parallel" "irrelevance"] :sample 5
          :candidates [{:id :sonnet :model "claude-code-sonnet"}]})
```

### Equivalence

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

### Upstream data faults

Building an accepted answer for every task from its own possible answers found
four tasks that **no answer can pass** (`bfcl/unsatisfiable`, pinned by a
test): `live_multiple_862-181-3` and `live_multiple_964-207-0` (the possible
answers and the function's parameters disagree), `live_simple_106-63-0` and
`live_simple_112-68-0` (required array parameters with an empty list of
accepted values). They cap `live_simple` at 256/258 and `live_multiple` at
1051/1053 for every model. EnvironmentDefs mark them (`:unsatisfiable?`).

### What is comparable

Per-category accuracies are comparable with upstream's columns of the same
name for a function-calling model with `underscore_to_dot`. `bfcl/summary`
also gives upstream's two aggregates over these categories. Not comparable:
upstream's "simple" and non-live overall columns average in the Java and
JavaScript categories, which are not transcribed (their values are
string-encoded literals with converters of their own).

### Candidates

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

### Live checks

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

`bfcl.inspect` reads an experiment directory: `report`, `failures`,
`print-report`.

### Not done

Java and JavaScript categories, multi-turn (eight stateful API simulations:
the natural tier-0 follow-up, as forkable worlds), agentic (memory; web search
needs the network and stays out), a full run.

## tau2-bench (retail)

Upstream: `sierra-research/tau2-bench` at the revision pinned in
`dvergr.benchmarks.tau2.core/upstream`. Data is read from a checkout at
`../tau2-bench`, and every file is verified against its pinned SHA-256.

| Namespace | Role |
| --- | --- |
| `dvergr.benchmarks.pyjson` | Python `json.dumps`, float `repr`, and `round`, which DB hashing and tool output need to match exactly |
| `dvergr.benchmarks.tau2.retail` | Pure retail tools: `(respond db name args) -> {:db :content :error}` |
| `dvergr.benchmarks.tau2.core` | Data loading, upstream prompts, the half-duplex episode protocol, grading |
| `dvergr.benchmarks.live` | Generate functions over Dvergr providers (Claude Code, Anthropic, OpenAI, …) |
| `dvergr.benchmarks.tau2.runner` | Headless batch runs, resumable EDN episode logs, pass^k |
| `dvergr.benchmarks.tau2.equivalence` | Differential replay against the Python oracle, plus a seeded fuzz corpus |

Run from the REPL:

```clojure
(require '[dvergr.benchmarks.tau2.core :as t2]
         '[dvergr.benchmarks.tau2.runner :as tr])
(def dom (t2/load-domain "retail"))
(tr/run! dom {:split "test" :trials 4 :parallelism 3
              :agent {:model "claude-code-sonnet"}
              :user {:model "claude-code-sonnet"}
              :judge {:model "claude-code-sonnet"}})
;; => {:dir ".dvergr/benchmarks/tau2/<run>" :header {...}
;;     :metrics {:avg-reward .. :pass-hat-k {1 .. 4 ..} ...}}
```

Each episode is appended to `<dir>/episodes.edn` as it finishes. An entry
holds the full transcript, termination reason, grade breakdown, usage, and
duration. Passing the same `:dir` again resumes the run. Infrastructure
failures are recorded under `:failure` and excluded from metrics, as upstream
does, and never scored as model failures.

### What is comparable

The run header records the upstream revision, split, trials, and the agent,
user-simulator, and judge models. Upstream results use `gpt-4.1` at
temperature 0 as both the user simulator and the NL-assertion judge. A
number produced with a different user or judge model is a Dvergr-internal
measurement, not a leaderboard entry. Use the upstream `train` split (74
tasks) for tuning and self-optimization. Report on `test` (40 tasks), which
tuning must never see.

### Protocol notes

- The agent opens with `"Hi! How can I help you today?"`. Either side ends
  the episode with `###STOP###`; the user can also end it with
  `###TRANSFER###` or `###OUT-OF-SCOPE###`.
- Upstream's default is `enforce_communication_protocol = False`. A message
  with both text and tool calls goes to the environment, and the user never
  sees its text. An empty message is an agent error. Enable the strict rule
  with `:enforce-protocol? true`.
- Limits are 200 steps and 10 tool errors. Any termination other than an
  agent or user stop scores 0.
- The reward is the product over the task's `reward_basis`: DB hash against
  the replayed gold actions, communicate-info substring checks, and
  LLM-judged NL assertions. The predicted DB is the episode's final value, not
  a replay of its calls, which is equivalent because tools are deterministic.

### Baselines

| Date | Agent | User sim / judge | Split | Trials | pass^1 | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| 2026-09-18 | Sonnet 5 via Claude Code (`claude-code-sonnet`) | same | test (40) | 1 | 0.80 | Internal only: not a leaderboard setting |
| 2026-09-18 | reference loop, Sonnet 5 via Claude Code | Opus 5 via Claude Code | test (40) | 1 | 0.925 | Room path (`retail-cmp-A`) |
| 2026-09-18 | Dvergr `llm-agent`, JSON tools, Sonnet 5 | Opus 5 | test (40) | 1 | 0.925 | Room path; ~113k agent input tokens per episode |
| 2026-09-18 | Dvergr `llm-agent`, REPL, Sonnet 5 | Opus 5 | test (40) | 1 | 0.900 | Room path; ~80k agent input tokens per episode (−30%) |

In `retail-cmp-A` all three candidates fail tasks 27 and 38 (DB) and 68 (NL
assertion); the REPL candidate also fails task 64. Every one of the 120
episodes passes `verify-world` and `verify-episode`. One trial cannot rank
the candidates. The REPL candidate needs about 30% fewer agent input tokens,
because it composes several tool calls per `clojure_eval` (263 evaluations
for 366 domain calls).

`retail-cmp-A` predates the host-context note (below): the CLI's injected
operator email reached the candidates, which looked it up with
`find_user_id_by_email` (or offered it to the customer) in 11/40 REPL, 3/40
JSON-tools and 3/40 reference episodes. None of these are failed tasks, so
the rewards stand, but the REPL candidate's step, error and token counts are
inflated.

### Claude Code as the model backend

Claude Code (`claude -p`, billed to a Pro/Max subscription) injects host
context into every call: the signed-in account's email and the wall-clock
date. No CLI flag, setting or Agent SDK option removes it (Claude Code
2.1.277; only `ANTHROPIC_UNIX_SOCKET` skips the email). Experiments that use
Claude Code models therefore:

- can run the CLI isolated with `:claude-env (cc/token-env dir token)`: an
  empty `CLAUDE_CONFIG_DIR` (no stored account profile, so no email to
  inject) plus a long-lived inference token from `claude setup-token`, which
  never refreshes and cannot disturb the interactive login. Stripping the
  email from a config directory that shares the login's credentials is not
  safe: the CLI re-fetches the profile every 24 h and a token refresh from a
  second config directory can rotate the shared refresh token. Verified
  2026-09-19: with the token and an empty config directory the model sees no
  email (the same probe on the normal login lists it), the CLI stores no
  account profile, usage reports still arrive, and the REPL probe that used
  the email in 4/6 calls without a note used it in 0/6. Only the date remains;
  the note covers it;
- append `tx/host-context-note` to every system prompt (agent, user
  simulator, judge), telling the model that account details and dates outside
  its system prompt belong to the harness operator (`:host-context-note`
  overrides it, `false` disables it). The note is a fallback, not a fix:
  in single-call probes on task 42's opening the JSON-tools prompt stopped
  using the email (3/4 without the note, 0/4 with it), but the REPL prompt
  still used it in 2/6 calls;
- pin the CLI with `:claude-cli`, a copied versioned binary, e.g.
  `.dvergr/tools/claude-cli/claude-2.1.277` (the updater may prune
  `~/.local/share/claude/versions/*`, and `--bare`, which ignores
  subscription login, is announced as a future default for `-p`);
- record `{:claude-cli version :host-context-note-id id}` under the
  ExperimentDef's `:host` metadata, so changing either starts fresh cells;
- read the CLI's `rate_limit_event`s (`cc/rate-limit-status`) and pause
  before each cell while a usage window is at least
  `:usage-pause-threshold` (default 0.97) utilized. A cell that fails
  because the subscription rejected a call keeps its `:failed` Attempt and
  is re-run after the reset, at most `:usage-retries` (3) times. An error
  result from the CLI is now an error, never a model reply.

```clojure
(def claude-env (cc/token-env ".dvergr/benchmarks/claude-config"
                              (slurp (str (System/getProperty "user.home")
                                          "/.dvergr/secrets/claude-oauth-token"))))
(tx/run! {... :claude-cli ".dvergr/tools/claude-cli/claude-2.1.277"
          :claude-env claude-env})
```

Anthropic's terms allow a subscription through the unmodified CLI for one's
own use; products built for other users must use API keys.

Failure classes in the first (host-runner) run were:

- incomplete multi-request (tasks 71, 97);
- ordering against the one-return-or-exchange policy (27);
- wrong variant choice (60);
- no write at all (39);
- a cancel reason the scenario never specifies (38);
- an unstated order total, failed by the judge (68);
- one tool call emitted without `<tool_use>` tags (90). This was a
  Claude Code adapter gap, since fixed.

### Judge re-grading (replay)

Recorded episodes can be re-graded without re-running them: only the judge
is called. `retail-cmp-A`'s 33 episodes with NL assertions (45 assertions),
re-judged 2026-09-19, compared with the recorded Opus verdicts:

| Judge | Assertions agreeing | Episode verdicts agreeing |
| --- | --- | --- |
| Opus 5, second pass | 42/45 | 30/33 |
| Sonnet 5 | 43/45 | 31/33 |
| Haiku 4.5 | 42/45 | 30/33 |

Every disagreement is on a recorded "not met" (Opus said unmet, the other
pass said met), and all but one are task 68 ("Agent should tell the user the
order total is $829.43"). In all three candidates' episodes the agent stated
$829.43 among three order totals but, lacking order dates, never identified
it as the most recent order's. The literal reading of the assertion is met,
its intent is not, and judges (Opus included) split on it. On unambiguous
assertions every judge agreed. So judge variance is set by assertion
ambiguity, not by judge size: a cheaper judge is as consistent with the
recorded Opus verdicts as Opus itself on this set. The set is small
(4 failing episodes), so treat NL-assertion outcomes on ambiguous tasks as
noise when ranking candidates.

### Candidates

A candidate is whatever answers the simulated user. Grading, protocol and
worlds are identical across candidates.

| Candidate | Agent spec | What is under test |
| --- | --- | --- |
| Reference | `{:model m}` | tau2's `LLMAgent`: one model step per protocol step (`live/model-generate`) |
| Dvergr loop | `{:model m :harness :dvergr :action-space :tools}` | Dvergr's own agent turn (`chat.agent/run-agent-turn!`) inside one working chat context per episode, with the domain tools as JSON tools |
| Dvergr REPL | `{:model m :harness :dvergr :action-space :repl :repl-guidance g}` | The same loop with one `clojure_eval` tool. The domain tools are documented SCI functions `tau2/<tool>` returning upstream's strings, plus `tau2/parse` and `tau2/shape` |

The REPL candidate gets the same information as the JSON-tools candidate: its
prompt lists every tool as a Clojure call with its full description and
arguments (7.9k characters against 11.8k of JSON schema), and
`(clojure.repl/doc tau2/<tool>)` / `(sandbox/doc 'tau2)` show the same docs at
runtime. Before 2026-09-19 it saw only tool names. `:repl-guidance` selects the
action-space guidance (`:shape`, the default; `:compute`; `:inspect`); each
variant is a distinct candidate because AgentDefs carry their system prompt's
sha256. `tau2/shape` exists because REPL agents computed over data they had
not looked at: product `variants` are a map keyed by item id, and
`(filter #(get % "available") variants)` over its entries counted nothing.

### Result types (malli)

`dvergr.benchmarks.tau2.schemas` holds curated malli types for tool results
(retail: order, user, product, variant, payment method, ...), drafted with
`malli.provider` from every result of the gold trajectories and a seeded fuzz
corpus and then curated (id-keyed maps as `:map-of` with a description,
payment methods as a union, enums and nullable fields from the data). The
schema test validates every corpus result against them. The REPL candidate
sees each tool's result type in its doc and prompt, and has `(tau2/types)`
and `(tau2/check type x)` at runtime.

The full type section is deliberately NOT in the default prompt. Interleaved
probes on task 2 (count available T-shirts): `:shape` 15/16, `:typed` (same
prompt plus all type definitions) 8/16, p = 0.007. With the types in front of
them agents trusted the schema, went straight to `(count variants)` and never
looked at the records whose `available` flags decide the answer. Types say
what a value's structure is, not which fields matter.

Probe samples run in one parallel batch are correlated (the same variant
went 2/8 and 8/8 in consecutive batches), so compare variants interleaved
within the same rounds.

### Checkpoints and branches (copy-on-write)

An episode's whole live state is forkable: the tau2 world, the graded log
and step/error counters, the customer's history, the reference agent's
history and the Dvergr candidate's working context (chat history and SCI
REPL heap) all live in the episode Room's execution context
(`episode/CtxAtom` cells).

```clojure
(let [{cp :checkpoint} (ep/run! (assoc opts :checkpoint-at 3))]   ; pause before the 3rd customer message
  (try
    (mapv (fn [_] (ep/branch! cp)) (range 8))                       ; 8 certified continuations
    (finally (ep/release-checkpoint! cp))))
```

- `run!` with `:checkpoint-at k` stops when the customer has produced its
  k-th message and holds it back: the candidate is idle, so the Room is a
  clean fork point. Nothing is graded or certified; the Room stays open.
- `branch!` forks the checkpoint Room's execution context (`:mode :frozen`,
  O(1) copy-on-write) into a new episode Room on the experiment store,
  projects the candidate's working context into it (`room-context/fork-ctx!`),
  rebinds the `tau2/*` tools to the branch's world, delivers the held
  message and runs to the end. Each branch is an ordinary certified Attempt
  whose metrics and evidence carry `{:branch {:checkpoint-room :at}}`; its
  trajectory starts with the checkpoint's log. Branches never see each other
  and never change the checkpoint (`tau2_lab_test`: gold vs idle
  continuations 1.0 / 0.0 from one checkpoint; a REPL definition made before
  the checkpoint is inherited, a redefinition in one branch is invisible to
  the others).
- `opts` may vary the customer (`:user`), the judge and, for the reference
  harness, the agent's generate fn; the Dvergr candidate keeps the
  checkpoint's working context (its prompt and model are fixed there).

Limits: checkpoints are taken live (a certified log can only be replayed
into a probe, since it does not contain the candidate's REPL heap); forks
happen only between model steps.

### Decision-point probes

`dvergr.benchmarks.tau2.probe` re-samples one candidate turn from a recorded
episode: the world is rebuilt by replaying the certified effects, the
candidate's history is the recorded dialogue with earlier tool traffic in its
own action space, and only the answer to one customer message runs, `n` times
in parallel. A probe costs one agent turn instead of an episode (seconds,
~0.01% of a Max weekly window), so prompt and harness changes are checked at
the decision points where candidates went wrong before a full run confirms
them. Probes are diagnostics, not certified results.

```clojure
(pr/probe! dom task (:trajectory evidence) cut
           {:action-space :repl :repl-guidance :shape :model "claude-code-sonnet"} 6)
```

Task 2 (count available T-shirts), 2026-09-19: JSON tools 4/4; REPL with
names-only prompt failed in both full episodes; REPL with signatures
`:compute` 6/6 and `:shape` 6/6, `:shape` with 3.8 evaluations per turn
against 5.3.

`dvergr.benchmarks.tau2.harness` binds each harness turn to the episode's
world value. Every tool call, including calls made inside `clojure_eval`, is
recorded as a tau2 tool-call message, so DB and ACTION grading and step/error
accounting are unchanged. Harness candidates change the orchestration, so
their results would be tau2 "custom" submissions.

First comparison (2026-09-18) on retail train tasks 0–8, 1 trial, Sonnet 5 via
Claude Code as agent, user and judge: reference 8/8, Dvergr loop 8/8,
Dvergr REPL 6/8. The REPL failures were a correct DB with an unfiltered count
of unavailable variants (task 3, judged) and a wrong exchange variant (task 6).
At this sample size the candidates are indistinguishable; the comparison
proves the plumbing, not a ranking.

### Certified experiments

Experiments run on Dvergr's generic evaluation path
(`doc/evaluation-model.md`): every cell is one
`dvergr.agent.evaluation/evaluate`, folded by `dvergr.agent.experiment/run`.
tau2 brings only what is its own (`dvergr.benchmarks.tau2.provider`):

- a **world setup**: the task's initial world, installed in the Run's forked
  world;
- a **protocol**: the conversation with the simulated customer, hosted by the
  Run inside that world;
- a **verifier**: the grader behind the evaluator boundary. The trusted
  observer reads the final world; the verifier sees portable evidence.

Their references carry the upstream revision and the simulator and judge
models, so those are part of every EnvironmentDef's content id. The forked
world is discarded after certification, so the certified evidence is the
record: the graded log, the candidate's own transcript (REPL code and tool
results), its per-turn Runs and, for a failed Run, what failed. Every Attempt
records its verifier's trust tier (`:verifier-trust`) and its experiment cell.

Each experiment directory holds one durable store with the experiment Room,
a certified Attempt per cell, and a Scorecard once every cell completed (an
infrastructure fault is not a verdict; re-running resumes and re-runs exactly
those cells). Records made before 2026-09-20 used episode Rooms instead;
`inspect` reads both shapes.

Run it in a dedicated JVM, because it isolates Dvergr's home to the
experiment directory:

```clojure
(require '[dvergr.benchmarks.tau2.core :as t2]
         '[dvergr.benchmarks.tau2.experiment :as tx]
         '[dvergr.benchmarks.tau2.inspect :as insp]
         '[dvergr.agent.conversation :as conv])
(def dom (t2/load-domain "retail"))
(tx/run! {:dir ".dvergr/benchmarks/retail-cmp" :domain dom :split "base"
          :repetitions 4 :parallelism 3
          :candidates [{:id :reference :harness :reference :model "claude-code-sonnet"}
                       {:id :dvergr-tools :harness :dvergr :action-space :tools :model "claude-code-sonnet"}
                       {:id :dvergr-repl :harness :dvergr :action-space :repl :model "claude-code-sonnet"}]
          :user {:model "claude-code-opus"} :judge {:model "claude-code-opus"}})
;; Re-running the same call resumes: completed cells are skipped.

(def xs (conv/open-store! ".dvergr/benchmarks/retail-cmp"))
(insp/summary xs :tau2/retail-cmp)                ; reward, pass^k, failures
(def e (insp/episode xs :tau2/retail-cmp attempt-id))
(insp/print-transcript e)                        ; dialogue, REPL code, effects
(insp/verify-world dom task e)                   ; replay reproduces the certified hash
(insp/verify-episode e)                          ; Room-path records only
```

The host runner (`dvergr.benchmarks.tau2.runner`) remains available for
quick, unrecorded batches.

## tau2-bench (banking_knowledge, `bm25` retrieval)

This is τ³'s knowledge domain. It has 97 tasks over a bank database, a
698-document knowledge base, and dual control: the simulated user has tools
too. The leaderboard requires reporting the retrieval configuration. Dvergr
transcribes `bm25`, which is sparse `KB_search` over `rank_bm25.BM25Okapi`
and needs no embedding API.

| Namespace | Role |
| --- | --- |
| `dvergr.benchmarks.python` | Python 3.12 semantics that tool outputs expose: `repr`/`str`, `format(x, '.2f')`, `json.loads` with CPython's error messages, `json.dumps(indent=2)`, `str.title`, `float()`/`int()`, and comparisons that raise `TypeError` |
| `dvergr.benchmarks.tau2.banking.db` | The TransactionalDB value, `db_query`, deterministic ids, validation helpers, Python keyword binding, and the tool-definition format |
| `dvergr.benchmarks.tau2.banking` | BM25, the 15 agent tools, the unlock/give/call discoverable-tool mechanics, user tools, and the environment dispatcher |
| `dvergr.benchmarks.tau2.banking.tools-{a,b,c}` | The 43 agent-discoverable tools (plus the upstream example tool) |

A banking world is `{:db :agent-unlocked :user-given :allowlist}`. Only `:db`
is graded; unlock and give state is behavioral, like upstream's in-memory
toolkit state. `(t2/load-domain "banking_knowledge")` works with the same
episode driver and runner as retail. The driver runs both sides: user tool
calls execute with requestor `:user`, and results go back to the simulated
user only. Grading adds tau2's ACTION component. Note that upstream compares
only the argument keys of the *predicted* call unless `compare_args` is set;
the port does the same.

Faithfulness choices:

- **Knowledge-base document order.** Upstream takes it from `glob()`, which
  depends on the filesystem, and it breaks BM25 score ties. The order is
  pinned from the oracle in `benchmarks/resources/benchmarks/tau2/banking-tools.json`.
- **Task source.** Tasks load from `tasks/task_*.json`, as upstream's
  `get_tasks` does. The combined `tasks.json` next to them is stale upstream:
  13 tasks differ.
- **Search timing.** `KB_search` ends with a wall-clock timing line upstream.
  The port prints zeros so worlds stay pure functions of their inputs, and
  equivalence masks the digits.
- **Other upstream quirks, reproduced deliberately:**
  - numbers in discoverable-tool JSON are parsed as floats (`parse_int=float`);
  - discoverable tools can be called directly by name, without unlocking;
  - `list_discoverable_*_tools` tests for a message that is never produced;
  - calls that mutate and then raise keep their partial writes;
  - a savings-account early-closure fee never triggers, because the code
    checks `savings` but the data says `saving`;
  - `str.upper()` status checks;
  - JSON-injection through f-string query constraints.

  The transcription comments name each one.

Verified on 2026-09-18 against upstream `b7ea907`, with 0 mismatches:

| Check | Calls |
| --- | --- |
| Gold actions of all 97 tasks (agent and user requestors) | 955 |
| Seeded core corpus: `KB_search`, core tools, the unlock/give/call mechanics, user tools, and malformed or wrongly-typed arguments | 3315 |
| All 44 discoverable agent tools called directly (int/float/str/bool spellings, every error branch, dependent chains, injected fixture records) | 1661 |
| The same calls through `unlock` + `call_discoverable_agent_tool` (float-parsed JSON) | 3316 |
| Agent prompt, 97 user-simulator prompts, per-task user tool sets, tool signatures, mutation flags | identical |
| Gold agent and user through the dual-control episode protocol | 97/97 reward 1.0 |

`benchmarks/test/dvergr/benchmarks/tau2_banking_test.clj` pins all of this as digests.
The directly-called corpus is vendored as `banking_corpus.json`, and its
generators live in `benchmarks/dev/tau2/banking/`.

## tau2-bench (airline)

This domain has 50 tasks over a flight database: 300 flights with per-date
status, 500 users, and 2000 reservations. It has 14 agent tools and no user
tools. Every task's `reward_basis` is DB plus COMMUNICATE.

| Namespace | Role |
| --- | --- |
| `dvergr.benchmarks.tau2.airline` | FlightDB normalization, the 14 tools, `respond`, and `load-airline`, which verifies the data files against pinned digests |
| `dvergr.benchmarks.tau2.airline.pydantic` | The lax-mode pydantic 2.13 coercions and the exact `ValidationError` text the tools expose, including `input_value` truncation |
| `dvergr.benchmarks.tau2.airline.corpus` | The seeded differential corpus: random calls plus hand-shaped scenarios |

Tool schemas are vendored from the oracle as
`benchmarks/resources/benchmarks/tau2/airline-tools.json`. The oracle is
`benchmarks/dev/tau2/airline/oracle_airline.py`. It drives the real upstream
`Environment.get_response` and can record a DB hash after every call.

The port reproduces these upstream quirks deliberately:

- **Fixed values.** The current time is fixed at `2024-05-15T15:00:00`.
  Reservation ids are `HATHAT`/`HATHAU`/`HATHAV`, and a fourth booking fails.
  Certificate ids are `certificate_3221322..24`.
- **Validation only where upstream builds a model.** Arguments are checked
  only when upstream constructs a pydantic model. Elsewhere, raw values go
  through Python arithmetic and are stored as given:
  - `update_reservation_baggages` stores `"5"` or `1.5`;
  - `update_reservation_passengers` stores a string or a mixed list whose
    length matches;
  - `50 * "1"` in `book_reservation` raises `int += str`.
- **Seats.** Cancelling releases no seats, and changing flights books none.
- **Unknown cabins.** An unknown cabin fails only with a `KeyError` on a new
  leg's seat lookup. With an empty flight list, it is stored.
- **Unpadded next-day date.** `search_onestop_flight` builds the next day
  as `2024-05-{day+1}` without zero padding.
- **Partial effects.** A repeated certificate in `book_reservation` passes
  the balance check once per entry, then raises `KeyError` after the earlier
  gift-card and certificate deductions have been applied. A fractional
  baggage charge debits the gift card before `Payment(amount=int)` rejects
  it. Both partial effects are kept.

Verified on 2026-09-19 against upstream `b7ea907` (pydantic 2.13.5), with 0
mismatches in tool content, error flags, and final DB hashes:

| Check | Sequences / calls |
| --- | --- |
| Initial DB hash | identical |
| Gold actions of all 50 tasks | 50 / 142 |
| Seeded corpus (seed 11): bookings with split certificate, gift-card, and credit-card payments, changes, cancellations, and certificates; 2261 error outputs covering validation text, TypeErrors, KeyErrors, unhashable ids, argument errors, and user-requestor calls | 1200 / 5888 |
| Earlier development corpora (seeds 1 and 2, not pinned) | 700 / 3394 |
| Agent prompt, 50 user-simulator prompts, tool schemas, mutation flags | identical |
| Gold agent through the episode protocol | 50/50 reward 1.0 |

`benchmarks/test/dvergr/benchmarks/tau2_airline_test.clj` pins all of this as digests
in `airline_oracle_digests.edn`. The corpus is regenerated from its seed, so
no corpus file is vendored. Environment replay of the pinned corpus takes
about 8.5 minutes in Python and about 60 s in Clojure. The Clojure time is
dominated by hashing the 7 MB database once per sequence.

## tau2-bench (telecom)

Telecom is a technical-support domain with **dual control**: the agent works
on the carrier's database (customers, lines, plans, bills), and the simulated
user operates their own phone (airplane mode, SIM, mobile data, roaming, APN,
network mode, VPN, Wi-Fi calling, app permissions, payments). Tasks are
graded almost entirely by **environment assertions** on the final state,
e.g. "mobile data works and the speed test reports Excellent".

| Namespace | Role |
| --- | --- |
| `dvergr.benchmarks.tau2.telecom` | Environment: dispatch (`respond`), `sync-tools`, initialization actions, env assertions, world hash, `load-telecom` |
| `dvergr.benchmarks.tau2.telecom.db` | TOML subset reader, the pydantic models as ordered maps, both hashes |
| `dvergr.benchmarks.tau2.telecom.agent` | The 13 agent tools of `TelecomTools`, plus initialization functions and assertions |
| `dvergr.benchmarks.tau2.telecom.device` | The 30 user tools of `TelecomUserTools` (the phone simulation), the `APNSettings` validation, initialization functions and assertions |
| `dvergr.benchmarks.tau2.telecom.corpus` | Gold, fuzz, flow and grading corpora, Clojure replay, digests |

A telecom world is `{:db :user :vpn-performance :bill-seq}`. `:db` is the
agent's TelecomDB, `:user` the TelecomUserDB (`device` plus `surroundings`).
Upstream grades both databases, so `world-hash` is `agent-hash|user-hash`.
`(tc/load-domain)` returns a domain map with the same keys as
`load-banking`, plus `:env-assertion-checks` and `:settle`.

### Upstream configuration, read from the code

- **Environment**: the registry's `"telecom"` domain is
  `get_environment(policy_type="manual")`, not solo. The `"telecom-workflow"`
  variant (tech_support_workflow.md) and solo mode (main_policy_solo.md) are
  not ported.
- **Agent policy**: `"<main_policy>\n" + main_policy.md + "\n</main_policy>\n<tech_support_policy>\n" + tech_support_manual.md + "\n</tech_support_policy>"`.
  The tech-support manual is part of the agent's system prompt.
- **Tasks**: `get_tasks` loads `tasks.json`. It is byte-identical to
  `tasks_full.json` (2285 tasks). `tasks_small.json` is no longer used. The
  default split is `base` (`--task-split-name` defaults to `"base"`): 114
  tasks, returned in `tasks.json` order. `train` (74) + `test` (40) = `base`.
  `small` (20) is disjoint from `base`. `full` is all 2285.
- **User simulator**: every task gets all 30 user tools (`task.user_tools` is
  never set), so the user simulator uses `simulation_guidelines_tools.md`.
  40 of the 114 base tasks have a persona string (`[PERSONA:Easy|Hard]`).
- **Initial state**: no task has initialization data or message history.
  Every task has initialization actions (`set_user_info`, `turn_airplane_mode_on`,
  `break_apn_settings`, `set_data_usage`, `suspend_line_for_overdue_bill`, …),
  replayed by `set_state`. Each action is followed by `sync_tools`, and one
  more sync runs at the end.
- **Reward basis**: `ENV_ASSERTION` for 94 base tasks, and `ENV_ASSERTION` +
  `ACTION` for 20. No telecom task uses DB, COMMUNICATE or NL_ASSERTION.

### Protocol notes

- **Sync.** `Environment.get_response` runs `TelecomEnvironment.sync_tools`
  after every successful tool call, from either side. The orchestrator also
  runs it after every step. Sync copies the agent DB's state for the user's
  line onto the phone: `line_active`, `roaming_allowed`, and
  `mobile_data_usage_exceeded` (data used ≥ plan limit + refueled). It also
  settles payments: a paid request marks its bill `Paid`, then the first bill
  awaiting payment becomes the new request. `respond` does the same.
  A sync that raises (a phone number with no line) turns a successful call
  into an error but keeps the call's effects, as upstream does.
- **Grading.** Upstream rebuilds the predicted environment by replaying the
  trajectory's mutating calls (`set_state`), ends with a sync, and runs each
  assertion followed by a sync. The port grades the episode's final world
  after one `settle` (a sync; syncing is idempotent). The two agree on every
  trajectory checked (see below).
- **ACTION** tasks expect `transfer_to_human_agents` with `compare_args: []`,
  so only the tool name is matched. `core/action-checks` already implements this.
- **DB is not usable for telecom.** Upstream's gold environment replays the
  gold actions with `make_tool_call`, which does not sync, so its user DB
  never matches a live one. On 71 of the 114 base tasks the DB check fails
  even for the gold trajectory. It is not in any reward basis, so this
  changes no score. `core/grade` still computes `:db-match`; ignore it here.

### Deliberate deviations (upstream nondeterminism)

- **Draft-bill ids.** `_apply_one_time_charge` names a new draft bill
  `B{uuid4().hex[:8]}`, which is random. The port uses a per-world counter
  (`B00000001`, …). The oracle pins `uuid4` to the same counter. Upstream,
  any `refuel_data` for a customer without a draft bill therefore makes the
  DB hash irreproducible. Only C1001 has a draft bill, and the gold
  trajectories only touch C1001.
- **Shared VPN details.** `TelecomUserTools.default_vpn_details` is a class
  attribute. `connect_vpn` assigns that object to the device, and `break_vpn`
  mutates it in place (server performance POOR). Within one episode this
  means every later `connect_vpn` gets a POOR server; the port carries it as
  `:vpn-performance`. Upstream the object is also shared across the whole
  process: after any task ran `break_vpn`, a later task in the same process
  that connects a VPN gets a slow connection (0.1× speed), which can fail
  `assert_internet_speed`. The port models a fresh process per episode. The
  oracle resets the attribute before every sequence.

### Upstream quirks reproduced

The transcription comments name each one.

- Tool arguments are not validated (tools are plain methods), so errors are
  Python's own: `'int' object has no attribute 'startswith'`,
  `'<=' not supported between instances of 'str' and 'int'`,
  `unhashable type: 'list'`, `slice indices must be integers or None …`, and
  keyword-binding `TypeError`s. That includes argument names that collide with
  `Environment.make_tool_call`'s own parameters (`requestor`, `tool_name`, `self`).
- `set_network_mode_preference` stores a non-string mode unvalidated, runs a
  network search (falling back to 4G), then fails on `.value`. The junk value
  stays on the phone. `mode=None` returns "Failed to set …" but still
  changes the preference and searches.
- `set_apn_settings` validates dicts with pydantic 2.13. The ValidationError
  text is reproduced byte for byte: enum, bool parsing, int-from-float, extra
  keys, and 50-character repr truncation. Non-dict arguments are stored
  unvalidated, and every later APN access raises `AttributeError` until a
  valid dict replaces them, including a partial network search.
- Dict tool results go through `Environment.to_json_str._process`, which turns
  numbers into strings: `get_data_usage` returns `"data_used_gb": "8.7"`,
  and `refuel_data` returns `"charge": "4.0"`. Model results keep real floats.
  `datetime` fields render as `2025-01-20 14:30:00` (`default=str`).
- `refuel_data` does not check the line status (the check is commented out
  upstream). It echoes `gb_amount` with `str()` (`2 GB`, `True GB`).
- `check_vpn_status` prints the details dict with Python's enum repr,
  `<PerformanceLevel.EXCELLENT: 'excellent'>`.

### Verified

Verified 2026-09-19 against upstream `b7ea907` (Python 3.12.2,
pydantic 2.13.5). Every check below has 0 mismatches.

| Check | Size |
| --- | --- |
| Initial agent DB and user DB (full dumps and both hashes) | identical |
| Gold actions (agent and user) + env assertions, **all 2285 tasks**, run from each task's initialization actions | 2285 sequences, 13,215 calls, 3,674 assertions (base: 114 / 516 calls, 393 of them by the user / 209 assertions) |
| Seeded fuzz (seed 11): default state, any of the 2285 task states, or random stacks of initialization functions; both toolkits; wrongly typed, missing, extra and colliding arguments; wrong-requestor and unknown tools; assertions with `assert_value` true/false | 1500 sequences, 11,196 calls, 1,114 initialization calls, 3,068 assertions |
| Seeded flows (seed 23): payment request → pay → sync → resume; junk APN → reset/reboot/MMS; PIN/PUK locks and unseated SIM; data exhaustion, refuel and new draft bills; broken VPN reconnects | 600 sequences, 5,863 calls, 726 initialization calls, 868 assertions |
| Agent system prompt, greeting, 2285 user-simulator prompts, per-task user tool sets, tool schemas, parameters, mutation flags | identical |
| Grading vs upstream `EnvironmentEvaluator` + `ActionEvaluator` on perturbed base trajectories (gold, drop-first, drop-last, reversed, gold+noise, noise) | 684 trajectories, same reward (305 × 1.0, 379 × 0.0) |
| Upstream's own grade of the base gold trajectories | 114/114 reward 1.0 |
| Gold agent + user through `core/run-episode` + `core/grade` | 114/114 reward 1.0; an agent that stops immediately scores 0.0 on all 114 |

The replays compare tool content, error flags, both database hashes,
initialization-call results and assertion results. Digests are pinned in
`benchmarks/test/dvergr/benchmarks/tau2/telecom_oracle_digests.edn`, and
`benchmarks/test/dvergr/benchmarks/tau2_telecom_test.clj` rechecks all of it without
Python (6 tests, 567 assertions, about 80 s including JVM start). The corpora
are regenerated from their seeds by `telecom.corpus`, and the tool schemas are
vendored in `benchmarks/resources/benchmarks/tau2/telecom-tools.json`.

Oracle: `benchmarks/dev/tau2/telecom/oracle_telecom.py`
(`replay | schema | prompts | gold-eval | grade`), run in the checkout with
`uv run --no-sync python …`.

### Not done / unverified

- Solo mode, the workflow policy variant and the voice task set.
- Malli result types and REPL-candidate docs (`schemas.clj`) for telecom.
- Live episodes with a model.
- A second draft bill in one sequence (id `B00000002`) is never reached.
  Junk values written by initialization functions (e.g. a non-numeric
  `set_data_usage`) are not exercised; tasks never produce them.

## Equivalence method

`benchmarks/dev/tau2/oracle.py` is the only Python involved, and it runs
against the upstream checkout (`uv run python …`), never inside Dvergr. It
exports:

- `replay`: tool content, error flag, and final DB hash for any call sequence;
- `schema`: tool schemas (vendored as `benchmarks/resources/benchmarks/tau2/retail-tools.json`);
- `prompts`: exact agent and user-simulator system prompts for every task;
- `judge-prompts`: the exact NL-judge request, captured by stubbing upstream's
  `generate`.

Verified on 2026-09-18 against upstream `b7ea907`:

| Check | Result |
| --- | --- |
| Initial DB hash | identical |
| 114 gold action sequences (550 calls) | byte-identical outputs and final hashes |
| 1500 seeded fuzz sequences (6770 calls: error paths, partial effects, argument errors, `calculate`) | identical after one fix: pydantic coerces `OrderPayment(amount=abs(0))` to `0.0` |
| 114 user-simulator prompts, agent prompt, greeting | identical |
| NL-judge prompts (including Python `repr` quoting) | identical |
| Gold-replay agent through the full episode protocol | 114/114 reward 1.0 |

Upstream quirks are reproduced on purpose, because grading hashes the final
state. For example, `modify_pending_order_items` gives every modified item the
last requested variant's price and options, and a failing tool call can leave
the partial writes that happened before the failure.

`benchmarks/test/dvergr/benchmarks/tau2_test.clj` pins the oracle results as per-sequence
digests (`retail_oracle_digests.edn`), so CI rechecks equivalence without
Python whenever the checkout is present.

Environment replay cost for the fuzz corpus: Python takes 283 s, because it
reloads and re-validates the mutable DB per sequence. Clojure takes 0.43 s,
because every sequence starts from the same immutable value. This measures
environment execution only; model latency dominates live episodes.

## Next steps

1. Fork-at-turn rollouts. These need a frozen context fork and a store that
   follows the fork (`doc/conversation-evaluation.md`, revision 10).
2. Measure the candidates properly: full `base` split, 4 trials, and a
   user simulator that differs from the agent model. Then tune the REPL
   candidate's prompt and affordances on `train` only.
3. Use forks for branching. Branch at a user turn for counterfactual rollouts
   and cheap pass^k/GRPO-style groups from one shared prefix.
4. Port the telecom domain the same way: oracle, fuzz,
   equivalence, then live. Banking's other retrieval configurations
   (`grep_only`, `full_kb`, `golden_retrieval`) need no embeddings and reuse
   the same tools.
