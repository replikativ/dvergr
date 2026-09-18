# Benchmarks

Dvergr runs public benchmarks natively, as Clojure transcriptions whose
environment state is an immutable value. A fork is a structural share and a
replay is a reduction, so resetting and branching an environment costs nothing.
A transcription counts as the benchmark only after it has been shown
equivalent to the upstream implementation (see *Equivalence method* below).

| Tier | Meaning | Status |
| --- | --- | --- |
| 0 | Fully native, in-memory, forkable | tau2-bench retail (this document) |
| 1 | Frozen/recorded IO, no containers | planned |
| 2 | Container-bound public leaderboards (Terminal-Bench, SWE-bench) | calibration only, via an external adapter |

## tau2-bench (retail)

Upstream: `sierra-research/tau2-bench` at the revision pinned in
`dvergr.benchmarks.tau2.core/upstream`. Data is read from a checkout at
`../tau2-bench`, and every file is verified against its pinned SHA-256.

| Namespace | Role |
| --- | --- |
| `dvergr.benchmarks.tau2.pyjson` | Python `json.dumps`, float `repr`, and `round`, which DB hashing and tool output need to match exactly |
| `dvergr.benchmarks.tau2.retail` | Pure retail tools: `(respond db name args) -> {:db :content :error}` |
| `dvergr.benchmarks.tau2.core` | Data loading, upstream prompts, the half-duplex episode protocol, grading |
| `dvergr.benchmarks.tau2.live` | Generate functions over Dvergr providers (Claude Code, Anthropic, OpenAI, …) |
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

## Equivalence method

`dev/benchmarks/tau2/oracle.py` is the only Python involved, and it runs
against the upstream checkout (`uv run python …`), never inside Dvergr. It
exports:

- `replay`: tool content, error flag, and final DB hash for any call sequence;
- `schema`: tool schemas (vendored as `resources/benchmarks/tau2/retail-tools.json`);
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

`test/dvergr/benchmarks/tau2_test.clj` pins the oracle results as per-sequence
digests (`retail_oracle_digests.edn`), so CI rechecks equivalence without
Python whenever the checkout is present.

Environment replay cost for the fuzz corpus: Python takes 283 s, because it
reloads and re-validates the mutable DB per sequence. Clojure takes 0.43 s,
because every sequence starts from the same immutable value. This measures
environment execution only; model latency dominates live episodes.

## Next steps

1. Lift episodes into certified Runs. The episode becomes an `EnvironmentDef`
   plus a trusted `Evaluator`. The retail DB value lives in the Run's forked
   world, the simulated user is a room participant, and results become
   `Attempt`s and `Scorecard`s.
2. Add a REPL action-space candidate. Expose the same tools as SCI functions
   through `clojure_eval` and compare against JSON tool calling on the same
   tasks.
3. Use forks for branching. Branch at a user turn for counterfactual rollouts
   and cheap pass^k/GRPO-style groups from one shared prefix.
4. Port the airline domain the same way: oracle, fuzz, equivalence, then live.
