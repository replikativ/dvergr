# AutomationBench (an adapter)

Part of the [benchmark catalog](../../doc/benchmarks.md); how to run an experiment and read its
report: [doc/running-benchmarks.md](../../doc/running-benchmarks.md).

Zapier's AutomationBench (https://github.com/zapier/AutomationBench, pinned at `4a8e106`):
600 scored business workflows (100 each in sales, marketing, operations, support, finance,
hr; `simple`, 200 more, is a tool-use baseline outside the score) over 47 simulated SaaS
apps. A task is a prompt, an initial world and assertions on the final world; there is no
simulated user and no judge.

It is the first benchmark here that is **not transcribed**: the simulated apps are ~85k lines
of Python and the rubric ~350 assertion types, too much to port and to keep in step with
upstream. Instead upstream's own code runs behind a stateless service
(`benchmarks/resources/benchmarks/automationbench/sidecar.py`, JSON lines over a pipe) and
dvergr owns the episode (`dvergr.benchmarks.automationbench.{sidecar,provider,experiment}`):

| | |
| --- | --- |
| World | upstream's `WorldState` as JSON, kept in the Run's forked world; every call sends it and gets the new one back |
| Protocol | upstream's loop: the task's system and user prompt, the toolset's tools (`api`, upstream's default: `api_search`, `api_fetch`, `base64_encode`), model steps until a reply without tool calls or 50 steps |
| Tools | upstream's functions, schemas as its runner shows them (the injected `world` hidden), `{}` arguments dropped, a failed call's text `f"{e}"` |
| Grade | upstream's `partial_credit` (the reward) and `task_completed_correctly` (the check `:passed`), plus a check per failed assertion type |
| Certification | the verifier replays the recorded calls in one process on one world, as upstream's runner would, and checks that world is the one the Run kept (`:world-replays`) |

What makes the replay exact: upstream's worlds take wall-clock defaults
(`default_factory=datetime.now`) and random ids (`uuid4`), so two builds of one task differ.
The service makes each world at a recorded instant with the clock frozen (`time-machine`)
and randomness seeded by task and call index; the Attempt records the instants. Verified:
all 800 initial worlds survive the JSON round trip unchanged and grade identically through
it; a stateless episode that mutates (a Gmail send) ends in the world a one-process replay
makes; scripted candidates through `evaluate` (do the task: 1.0, `:world-replays`; do
nothing or the wrong thing: 0.0, with the failed assertion named)
(`benchmarks/test/dvergr/benchmarks/automationbench_test.clj`, skipped without the checkout).

First live run (2026-09-28, Luna on the Codex subscription, one task per domain, `api`
toolset): 1 of 6 passed (sales), mean partial credit 0.56, 2.05M input tokens (1.79M cached)
for $0.075 per task at list prices. Every Attempt's world replayed. Where it lost: Gmail
messages to the wrong recipient or without the required content (hr, finance, marketing),
a sheet row not written (hr, finance), a Slack post into a channel it should have left
alone (support), and 50 model steps without the Drive and Notion actions (operations).
For scale, upstream's public-set leaderboard has GPT-5.6 Terra at 37% and Sol at 46%, at
maximum reasoning effort.

**Dvergr's loop as a candidate** (`automationbench.harness`, `:harness :dvergr`): Dvergr's
agent loop (`run-agent-turn!`) in a working chat of the Run's world instead of upstream's,
with `:action-space :tools` (upstream's three tools as Dvergr tools) or `:repl` (one
`clojure_eval`; the APIs are `ab/search`, `ab/fetch`, `ab/base64`, returning upstream's text,
and `ab/parse` reads JSON, so matching and summing over records happens in code). Every call
still goes through `episode/call-tool!`, so its world replays like the reference's.

Three loops on the same 12 tasks (two per domain, Luna, one attempt each, 2026-09-28):

| Candidate | Mean partial credit | Passed | Model steps | Tool calls | List price per task |
| --- | --- | --- | --- | --- | --- |
| upstream's loop (reference) | 0.68 | 2 | 25 | 24 | $0.055 |
| Dvergr's loop, upstream's tools | 0.72 | 2 | 28 | 27 | $0.059 |
| Dvergr's loop, REPL | 0.61 | 3 | 21 | 31 | $0.040 |

At this size the means are noise: an earlier run of the same cells (before the fix below)
gave 0.63 / 0.68 / 0.61, and single tasks swing from 1.0 to 0.5 between runs of one
candidate.

A larger run (2026-09-28, 18 tasks, three per domain, two repetitions, 108 Attempts, all
replayed; under a preflight, which estimated 1.9–2.5 points of the weekly Codex window for
the cells after its pilot, and the whole run moved it about one):

| Candidate | Mean partial credit | Pass rate | Tokens per task | Model steps | List price per task |
| --- | --- | --- | --- | --- | --- |
| upstream's loop (reference) | 0.56 | 19% | 243k | 25.7 | $0.052 |
| Dvergr's loop, upstream's tools | 0.57 | 19% | 223k | 23.4 | $0.048 |
| Dvergr's loop, REPL | 0.60 | 17% | 216k | 22.1 | $0.047 |

Paired by task against the reference (bootstrap over tasks, 95%): the REPL's partial credit
+0.04 [−0.06, +0.13] and its cost −11%, a difference of −$0.006 [−$0.013, +$0.002]; Dvergr's
loop with upstream's tools +0.01 [−0.11, +0.10] and −7%. Neither is distinguishable from
the reference here. The quarter-to-a-third saving of the 12-task runs did not hold at 18
tasks with repetitions; on AutomationBench with Luna, the REPL is not yet a systematic
saving. What it would take: tasks whose decisions are over many records (where computing
beats reading), guidance tuned on a development split, and a second model.

What the replay check caught: the first comparison had one Attempt (of 36) whose world did
not replay. The adapter parsed worlds into Clojure maps, which do not keep key order past
eight keys, and upstream's sheet writes fill columns in the order of a row's cells: the
candidate's write landed in other columns than upstream's would have. Worlds now cross the
boundary as JSON text, never parsed on the dvergr side (provider version 2; a regression
test replays that write).

**Tuning on pilots, proving on held-out tasks.** Upstream has no split, so `:split :dev` is
a fixed third of each domain's tasks (by the digest of `domain/id`) and `:split :eval` the
rest; nothing tuned on dev is reported from it. `abx/tune!` runs a baseline and variants on
dev tasks and `experiment.select/choose` shortlists the cheaper variants whose mean reward is
within 0.05 of the baseline's. A pilot chooses on point estimates: at 12 worlds a paired
reward interval is about ±0.15 wide, so demanding proof there keeps the baseline every
time (the first version of the rule did exactly that). The shortlist then runs against the
baseline on eval tasks, frozen, where the claim is tested with intervals
(`:rule :non-inferior`).

Development tuning (2026-09-28, 12 dev tasks × 2 repetitions, Luna, against the reference
loop; 120 cells, the weekly Codex window did not move a point):

| Variant | Reward difference [95%] | Cost ratio |
| --- | --- | --- |
| Dvergr's loop, upstream's tools | +0.09 [−0.06, +0.23] | 0.97 |
| REPL, `:compute` guidance (the default so far) | −0.04 [−0.20, +0.12] | 0.83 |
| REPL, `:batch` (fetch everything a decision needs in one evaluation, return only the deciding fields) | +0.06 [−0.06, +0.18] | 0.69 |
| REPL, `:lean` (the APIs' docs, no advice) | +0.01 [−0.13, +0.15] | 0.61 |

Shortlist: `:lean`, `:batch`. The guidance the REPL runs under decides its cost: the
default advice cost a third more than saying nothing.

**Held-out confirmation** (2026-09-28, 30 eval tasks, five per domain, never used in tuning,
× 2 repetitions, Luna; 180 cells; the weekly Codex window did not move a point):

| Candidate | Mean partial credit | Pass rate | List price per task |
| --- | --- | --- | --- |
| upstream's loop (reference) | 0.546 | 13% | $0.062 |
| REPL, `:batch` | 0.536 | 18% | $0.049 |
| REPL, `:lean` | 0.576 | 18% | $0.044 |

Paired by task against the reference (95%):

| Variant | Reward difference | Cost ratio | Cost difference |
| --- | --- | --- | --- |
| `:lean` | +0.030 [−0.056, +0.116] | 0.71 | −$0.018 [−$0.026, −$0.010] |
| `:batch` | −0.010 [−0.078, +0.059] | 0.80 | −$0.012 [−$0.022, −$0.002] |

On held-out tasks the REPL with lean guidance costs 29% less, with an interval that excludes
no saving, at a reward no worse on the estimate. It falls short of the strict non-inferiority
rule at a 0.05 margin by 0.006 (lower bound −0.056). The claim that holds: about 30% cheaper at
equal task success, on held-out AutomationBench tasks, with Luna. Open: a second model and a
second benchmark. The earlier default guidance (`:compute`) is not among these: tuning dropped it.

Not done: the `zapier` meta-tool toolset; the private held-out set, which upstream does not
release.

Setup: `benchmarks/resources/benchmarks/automationbench/README.md`. Run:

```clojure
(require '[dvergr.benchmarks.automationbench.experiment :as abx])
(abx/run! {:dir "runs/ab-smoke" :domains abx/public-domains :sample 1
           :candidates [{:id :luna :model "codex-subscription-luna"}]})
```

