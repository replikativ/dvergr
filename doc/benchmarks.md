# Benchmarks

Dvergr runs public benchmarks natively, as Clojure transcriptions whose
environment state is an immutable value. A fork is a structural share and a
replay is a reduction, so resetting and branching an environment costs nothing.
A transcription counts as the benchmark only after it has been shown
equivalent to the upstream implementation (see *Equivalence method* below).

| Tier | Meaning | Status |
| --- | --- | --- |
| 0 | Fully native, in-memory, forkable | tau2-bench retail, tau2-bench banking_knowledge (bm25) |
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

### Baselines

| Date | Agent | User sim / judge | Split | Trials | pass^1 | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| 2026-09-18 | Sonnet 5 via Claude Code (`claude-code-sonnet`) | same | test (40) | 1 | 0.80 | Internal only: not a leaderboard setting |

Failure classes in that run were:

- incomplete multi-request (tasks 71, 97);
- ordering against the one-return-or-exchange policy (27);
- wrong variant choice (60);
- no write at all (39);
- a cancel reason the scenario never specifies (38);
- an unstated order total, failed by the judge (68);
- one tool call emitted without `<tool_use>` tags (90). This was a
  Claude Code adapter gap, since fixed.

## tau2-bench (banking_knowledge, `bm25` retrieval)

This is τ³'s knowledge domain. It has 97 tasks over a bank database, a
698-document knowledge base, and dual control: the simulated user has tools
too. The leaderboard requires reporting the retrieval configuration. Dvergr
transcribes `bm25`, which is sparse `KB_search` over `rank_bm25.BM25Okapi`
and needs no embedding API.

| Namespace | Role |
| --- | --- |
| `dvergr.benchmarks.tau2.python` | Python 3.12 semantics that tool outputs expose: `repr`/`str`, `format(x, '.2f')`, `json.loads` with CPython's error messages, `json.dumps(indent=2)`, `str.title`, `float()`/`int()`, and comparisons that raise `TypeError` |
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
  pinned from the oracle in `resources/benchmarks/tau2/banking-tools.json`.
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

`test/dvergr/benchmarks/tau2_banking_test.clj` pins all of this as digests.
The directly-called corpus is vendored as `banking_corpus.json`, and its
generators live in `dev/benchmarks/tau2/banking/`.

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
4. Port the airline and telecom domains the same way: oracle, fuzz,
   equivalence, then live. Banking's other retrieval configurations
   (`grep_only`, `full_kb`, `golden_retrieval`) need no embeddings and reuse
   the same tools.
