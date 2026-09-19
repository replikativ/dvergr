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

- append `tx/host-context-note` to every system prompt (agent, user
  simulator, judge), telling the model that account details and dates outside
  its system prompt belong to the harness operator (`:host-context-note`
  overrides it, `false` disables it);
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

### Candidates

A candidate is whatever answers the simulated user. Grading, protocol and
worlds are identical across candidates.

| Candidate | Agent spec | What is under test |
| --- | --- | --- |
| Reference | `{:model m}` | tau2's `LLMAgent`: one model step per protocol step (`live/model-generate`) |
| Dvergr loop | `{:model m :harness :dvergr :action-space :tools}` | Dvergr's own agent turn (`chat.agent/run-agent-turn!`) inside one working chat context per episode, with the domain tools as JSON tools |
| Dvergr REPL | `{:model m :harness :dvergr :action-space :repl}` | The same loop with one `clojure_eval` tool. The domain tools are SCI functions `tau2/<tool>` returning upstream's strings, plus `tau2/parse` |

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

### Certified runs inside Rooms

Experiments normally run as certified conversational episodes inside
Dvergr's programming model; see `doc/conversation-evaluation.md` for the
design. Each experiment directory holds one durable store with:

- the experiment Room;
- one episode Room per cell, containing the dialogue, the candidate's
  `:agent-turn` Runs and tool activity, and the environment's effect rows;
- a certified Attempt per cell;
- a Scorecard once the experiment is complete and fault-free.

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
(insp/verify-episode e)                          ; graded log equals the Room rows
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

1. Fork-at-turn rollouts. These need a frozen context fork and a store that
   follows the fork (`doc/conversation-evaluation.md`, revision 10).
2. Measure the candidates properly: full `base` split, 4 trials, and a
   user simulator that differs from the agent model. Then tune the REPL
   candidate's prompt and affordances on `train` only.
3. Use forks for branching. Branch at a user turn for counterfactual rollouts
   and cheap pass^k/GRPO-style groups from one shared prefix.
4. Port the airline and telecom domains the same way: oracle, fuzz,
   equivalence, then live. Banking's other retrieval configurations
   (`grep_only`, `full_kb`, `golden_retrieval`) need no embeddings and reuse
   the same tools.
