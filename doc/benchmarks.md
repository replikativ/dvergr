# Benchmarks

## Experiments inside agent programs

An agent can construct rosters, environments and datasets as immutable Clojure
data and invoke `dvergr.agent/run-experiment!` from SCI. The experiment is a
child Job Run; its candidate Runs fork the caller's current world, while Runs,
Attempts and Scorecards remain in the control room. This is the same evaluator
used by host-started benchmarks, not a separate agent orchestration engine.

`(agent/offered)` lists the verifier and fixture references the host offers.
The host may supply `:experiment-capabilities` to a Run: that scope replaces the
global catalog, including when empty, and follows recursive hiring and child
experiments. Only its portable references enter input identity; verifier
functions and authority never enter agent-authored definitions. Writing a test
or checker does not register it as a trusted verifier.

Paid candidates require funded per-cell resources in their environments and
an explicit aggregate grant on the call:

```clojure
(agent/run-experiment! team experiment
  {:parallelism 1
   :resources {"microUSD" 200000M "model-dispatch" 8M}})
```

The grant must cover all candidates × environments × repetitions. It transfers
from the current Run into the Job, then into each candidate; unused resources
return along those same edges. Nothing mints money or admissions. Initial paid
child experiments admit accounted native providers, not CLI/relay execution.
The host can tighten cell-count and parallelism ceilings; cell settlement is
discard-only. Dispatch accounting bounds model requests, not arbitrary CPU or
every possible business effect.

The call returns an observer Spin. Await it to obtain `:job`, `:status` and
`:scorecard`; ignoring it does not detach the work from a hired parent's
supervisor. Cancellation stops child work, but neither parent terminality nor
wallet return precedes physical cleanup. Effect receipts must not print or
dereference that observer while recording admission.

### Reading child outputs

`(agent/attempt-output attempt-id {:path [:files "/out/answer.edn"]})`
reads only the `:agent/output` field deliberately published by the evaluator's
`observe` function into immutable Attempt evidence. Nothing is public by default:
the rest of the evidence, including private verifier fixtures, is not exposed.
Existing evaluators need to opt in; this is not yet a generic Simmis captured-file
adapter. Output is readable after the child world is discarded, without reopening it.

An ambient consumer Run is mandatory. Exact structural ancestry in the same
control-room store authorizes the read, independently of observation/history limits;
parent, sibling and other-room Attempts cannot be read through this surface.
Successful child reads record a durable `:run/caused-by` edge on the live consumer.
Identity fields refer to the original immutable Attempt; the read creates no new
result store or certification.

Select maps/vectors with `:path` (at most 16 components). Strings support
`:offset`/`:length` chunks of at most 16384 UTF-16 code units, returning
`:next-offset` when more remains. Other values are bounded to 2048 nodes, depth 32,
and 16384 scalar characters; oversized selections fail instead of silently
truncating evidence. These string offsets are not file byte offsets.

This core API does not itself enable a Simmis worker profile, research-network
access, scheduling, crash recovery or permission to promote a proposed checker.

### Delegation policy

Hosts can attach a portable `:delegation-policy` alongside evaluator capabilities.
It must explicitly enumerate `:tools`, exact allowed `:sandboxes` combinations
and native `:providers`; optional `:max-attempts`/`:max-parallelism` narrow each
child experiment. Missing policy retains trusted host behavior; an empty or
malformed policy is not an unrestricted grant. It follows SCI hires, delegation
tools, experiment Jobs and evaluation candidates. Central Run admission checks
it before opening a world, and experiment preflight checks every candidate before
creating a Job. Extra sandbox extensions are not accepted as an extension union.
Tool spellings use the same hyphen/underscore normalization as the tool registry.

Policy data and exact offered sandbox extension refs enter model input identity.
`agent/delegation-policy` lets code discover its bounds; `agent/current-run-id`
identifies its host-issued consumer scope. Inside an ambient Run, `agent/observe`
and `agent/cancel!` accept only that Run and its structural descendants, including
when passed a handle. Trusted room operators without an ambient Run keep their
room-wide controls. Effect handlers remain inherited, compositional world policy;
this allowlist does not replace them or make irreversible effects fork-safe.

## Public benchmark providers

Dvergr runs public benchmarks natively, as Clojure transcriptions whose
environment state is an immutable value. A fork is a structural share and a
replay is a reduction, so resetting and branching an environment costs nothing.
A transcription counts as the benchmark only after it has been shown
equivalent to the upstream implementation (see *Equivalence* below).
How to run one, what its report means, and what an experiment guarantees
(resume, faults, cost at list price, held-out discipline):
[running-benchmarks.md](running-benchmarks.md).

The providers are not part of the `dvergr` jar. They live in
[`benchmarks/`](../benchmarks/README.md) and ship as
`org.replikativ/dvergr-benchmarks` (in this repo: `clj -A:benchmarks`; the
suite, `clojure -M:test`, includes them). `dvergr` itself keeps what a
benchmark is built with, so that a benchmark of your own is one more provider
in your own repo: `dvergr.agent.evaluation`, `environment`, `experiment`,
`experiment.runner`, `verifiers` (a registry for capabilities by reference;
the tau2, BFCL, BIRD and AutomationBench providers have a `register!` for it
that nothing calls, and nothing in `src/` reads it: the runner is handed the
capabilities), `roster`.

| Tier | Meaning | Status |
| --- | --- | --- |
| 0 | Fully native, in-memory, forkable | tau2-bench retail, airline, banking_knowledge (bm25), telecom; BFCL v4 single-turn (Python) |
| 1 | Frozen/recorded IO, no containers | planned |
| 2 | Container-bound public leaderboards (Terminal-Bench, SWE-bench) | calibration only, via an external adapter |

Other providers: AutomationBench (upstream's Python behind a sidecar), BIRD (text-to-SQL on
SQLite and Datahike), SpreadsheetBench (graded on rechentafel), and the room workflows
(competitor discovery, contract review, the bank-booking case pack; doc/room-workflows.md).
*The benchmarks* below lists them all, each with a page of its own under
[`benchmarks/doc/`](../benchmarks/doc/). Data setup per benchmark:
[benchmarks/README.md](../benchmarks/README.md).

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
   resumed into old cells (tau2's `:repl-guidance` included). What a protocol
   assembles at Run start from code rather than from the AgentDef (a system
   prompt template, tool schemas) it declares as `:inputs`, so that a change re-runs the cells made before it
   (see "Resume and inputs ids").
5. **An equivalence proof** when the benchmark is a transcription (below), and
   a scripted candidate that replays gold answers, so the whole path is tested
   end to end without a model.

**Effects of the world** (`:world :effects` in an EnvironmentDef, doc/effects.md): the
handlers everything in the Attempt's isolated world performs under, the candidate's sandbox
and an external agent's MCP eval alike. Being in the EnvironmentDef, they are in its content
id, so a faulty environment is a different environment on a Scorecard.

```clojure
:world {:isolation :ctx :settlement :discard
        :effects {:faults {:seed 7 :rate 0.1 :only #{:http/request}
                           :kinds [:rate-limit :server-error :timeout]}
                  :record true      ; record every effect (for replay)
                  :read-only true}} ; or :admit #{:read :network}
```

Faults are seeded by `(seed, effect, occurrence)`: every Attempt meets the same faults, so
candidates are compared on the same draws (paired). Robustness is then the difference
between a candidate's Scorecard on the environment with and without `:faults`.

`dvergr.agent.experiment.runner/run!` is everything else: the experiment's home (its
`:dir`, opened with a headless daemon, `runner/open-home!`) and the Room kept there for the
ExperimentDef, Claude Code settings for the run, waiting out subscription usage windows,
resume, the Scorecard, and the report (`experiment_report`, the result's `:report`). A
provider's `experiment/run!` is a call to it; with `:daemon` instead of `:dir` it runs in a
running daemon's home (`runner/run-in` underneath, as `catalog_benchmark`).

**Resume and inputs ids.** The ExperimentDef's content id (dataset, candidate AgentDefs, metadata
with provider, grader and library versions) names what is fixed before anything runs. A Run
also resolves inputs only when it starts, and those reach the candidate without being in the
ExperimentDef. Each Attempt records them as its inputs id (`:attempt/inputs-id`, beside
`:attempt/prompt-id`), the content id of `dvergr.agent.program/run-inputs`:

| Candidate | In the inputs id |
|---|---|
| LLM program | the assembled system prompt (its prompt id: harness preamble, the agent's prompt, the skills eligible in the world's workspace, the tool-use guideline, the sandbox pointer, the workspace's AGENTS.md); the tools offered, each one's name, description and input schema (a Claude Code candidate is offered the same tools over MCP); the model: its alias resolved, provider, and the registry entry that shapes each request (API type, quirks such as sampling defaults, output limit, context size, capabilities, instruction role, reasoning efforts), and the compaction model likewise when the program compacts; the Run calls those models throughout, and one whose registry entry changes while it runs records no inputs id (`:inputs-drifted?`) |
| Protocol | the protocol's ref, the resolved model of the AgentDef's policy, and what the protocol's own `:inputs` (`evaluation/make-protocol`) says it assembles |
| `:echo`, `:scripted` | nothing (nil): a deterministic program resolves nothing |

Not in it, because they are already in the ExperimentDef or do not change what the candidate is
presented with: the task (an answer contract appended to a case pack's task is part of the
EnvironmentDef), budgets and limits (the AgentDef, the EnvironmentDef), the Claude Code CLI
version and host note (the metadata), the model's prices (they change what a call costs, not
what it asks; a models.dev refresh that changes a limit does change the id), the date note and planning-mode guideline (a room
participant's turns get them, a Run's model steps do not), and the files of the
experiment room's repository other than AGENTS.md and its skills (world state, seeded once when
the room is made; Attempts discard their forks).

Running again works like a spin's B-gate, which re-runs a spin iff its captured environment
changed: before any cell starts, `experiment/run` computes the inputs id each candidate would get
now, without a model call (the prompt is assembled in the world the cells fork, not sent). A
cell with a verdict is kept only when its Attempt recorded that id; the others run again
(`:stale-cells` in the result). The old Attempts stay in the store and count in no Scorecard.
The report and `experiment_progress` read the store alone, so they take a candidate's current
inputs to be those of its most recently certified Attempt: they count only cells made with them,
pass over a Scorecard whose cells ran again with other inputs, and state how many cells ran again,
how many await a re-run, and how many Attempts are superseded. A preflight's pilot, the
Scorecard, the frontier and the paired comparisons use that same set. A Run whose recorded
inputs differ from the prediction (a world setup that rewrites the workspace's skills or
AGENTS.md) is logged (`:dvergr.agent.experiment/inputs-not-predicted`), and no Scorecard takes
it: the result stays incomplete (`:incomplete {:unpredicted n}`, also stated in `run!`'s report)
and a resume runs it again. Every cell of a Scorecard was thus made with exactly the inputs
computed for it, whether it was kept or just ran. A Run resumed from a turn savepoint records
the system prompt it continues with, the stopped Run's.

Attempts certified before inputs ids were recorded have none. A deterministic candidate's are
kept (its inputs id is nil too); an LLM or protocol candidate's are stale, since nothing says what
it was presented with, so resuming such an experiment re-runs its cells once. That costs one more
run of every interrupted experiment resumed after the upgrade (a finished one only when run again;
its report reads as before), and it never folds two prompt texts into one Scorecard.

**Subscription allowance.** A subscription (Codex, Claude Code) has no bill, but it is the
user's own quota. `dvergr.model.subscription` meters it from what the provider reports with
every call (Codex: `x-codex-primary-used-percent` and friends, whole percent of a weekly
window; Claude Code: utilization per window), and an experiment runs under an allowance,
by default `{:share 0.10 :pause-at 0.80}`: no new cell starts once the experiment has used
ten points of any window since it started, or once a window is 80% used by anyone. Refused
cells do not run, the result says why (`:refused`), no Scorecard is persisted, and running
the same home again resumes them. `catalog_benchmark` runs under the same default. The
result's `:subscription` holds the readings before and after, which with the Scorecard's
tokens measure what a unit of work costs in window points.

**Preflight.** `run!` with `:preflight {:budget {:dollars d :subscription share}}` first runs a
pilot: one cell per candidate per stratum (default: the environment id's namespace, e.g. an
AutomationBench domain), first repetition (`dvergr.agent.experiment.preflight`). The
pilot's cells are cells of the experiment, so nothing is spent twice. From their bills it
extrapolates the rest per candidate, as `:expected` (the mean), `:conservative` (a one-sided
95% upper bound on the mean) and `:worst-seen` (every cell at the dearest pilot cell, since
agent costs are heavy-tailed), for list-price and billed dollars, tokens, wall time at the
run's parallelism and subscription window points (the points the pilot moved per token; a
pilot that moved no whole point counts as one, an upper bound). When the conservative
estimate exceeds the budget the run stops with the estimate (`:stopped :over-budget`);
running again with a larger budget resumes from the pilot. A candidate with cells left
whose pilot left no Attempt with a verdict (its pilot cells faulted or were refused) has no
measured cost: the estimate lists it under `:unpiloted` and the gate refuses (`:over
{:unpiloted …}`) rather than pass on the other candidates' bills; running again pilots it. A
faulted pilot cell (a provider outage) is never extrapolated, since it stopped wherever the
fault struck; what it spent is reported under `:faulted`. This is the experiment predicting
its own resource use before committing to it; the measurements it keeps are what later
predictions, and tuning on pilots before a full run, build on.

## The benchmarks

Each provider's notes (protocol, equivalence or calibration, upstream faults, candidates,
live checks, what is not done) are on a page of its own under
[`benchmarks/doc/`](../benchmarks/doc/); the results below are each page's headline.

| Benchmark | Kind | Page |
| --- | --- | --- |
| tau2-bench: retail, banking_knowledge (`bm25`), airline, telecom | transcription, tier 0 | [tau2.md](../benchmarks/doc/tau2.md) |
| BFCL v4, single-turn Python categories | transcription, tier 0 | [bfcl.md](../benchmarks/doc/bfcl.md) |
| AutomationBench | adapter: upstream's Python behind a stateless sidecar | [automationbench.md](../benchmarks/doc/automationbench.md) |
| BIRD (text-to-SQL on SQLite, SQL and Datalog on Datahike) | provider over upstream's data and grading | [bird.md](../benchmarks/doc/bird.md) |
| SpreadsheetBench Verified | provider, graded on rechentafel | [spreadsheetbench.md](../benchmarks/doc/spreadsheetbench.md) |
| Wiki (`wiki/v1`–`v3`) | our own catalog workflow, calibrated | [wiki.md](../benchmarks/doc/wiki.md) |
| Coding repair (`permutation`, `rss`) | Dvergr fixture | [coding.md](../benchmarks/doc/coding.md) |
| Discovery, citations, frozen web, market evidence | Dvergr fixtures | [discovery.md](../benchmarks/doc/discovery.md) |
| Competitor discovery, contract review (CUAD) | room workflows (doc/room-workflows.md) | *Room workflows* below; the bank-booking case pack: doc/room-workflows.md |

**tau2-bench.** All four domains are transcribed and shown equivalent to upstream (retail: 114
gold sequences and 1500 seeded fuzz sequences byte-identical; banking 97/97, airline 50/50,
telecom 114/114 gold reward 1.0 through the episode protocol). Retail baselines on the Room
path (2026-09-18, test split, one trial, Opus 5 as user and judge): the reference loop and
Dvergr's JSON-tools agent with Sonnet 5 at 0.925, the REPL agent at 0.900 with about 30% fewer
agent input tokens. Experiments run on the generic provider path (`tx/run!`, "Certified
experiments").

**BFCL v4.** 3491 tasks in eleven categories; upstream's checker run as an oracle agrees on
every verdict and error type of a 37403-answer corpus. Four tasks no answer can pass are
marked. Live checks are wiring checks: on the Codex backend, JSON tools could not express a
parallel action until `:parallel-tool-calls true` sends the full Responses shape.

**AutomationBench.** 600 scored business workflows over 47 simulated SaaS apps, upstream's
own code behind a sidecar; every Attempt's world is certified by a one-process replay. First
live run (2026-09-28, Luna, one task per domain): 1 of 6 passed, mean partial credit 0.56.

**BIRD.** With the dialect rewrite, 1372 of 1534 gold queries (89.4%) return SQLite's exact
rows on pg-datahike. Held out (`:eval`, Luna, paired), Datalog on Datahike is within noise of
SQL on SQLite (2026-09-30, 84 questions: 0.708 vs 0.732, difference −0.024 [−0.08, +0.03]) at
about 1.6× SQLite's tokens. The [reference setup](../benchmarks/reference/README.md) runs
BIRD's own one-prompt harness on the same questions (Luna 51 of 100, 61 with one formatting
line, against 65 in Dvergr's SQLite workflow).

**SpreadsheetBench.** 390 of the 400 Verified tasks are certified by a zero-token oracle;
experiments run only those. Held out (2026-10-01, 100 certified `:eval` tasks, Luna): 85 / 100
(95% interval 77–91%). Not comparable to the public leaderboard (a certified held-out subset,
another tool interface, one test case per task); the
[reference setup](../benchmarks/reference/README.md) runs upstream's harnesses on 59 of them.

**Wiki.** Our own task, argued valid by calibration (a reference wiki and damaged variants
each lose exactly what they damaged). Validation on 2026-09-27 (`wiki/v3` over MCP):
`codex-subscription-luna` 0.919 and `claude-code-haiku` 0.909 on three generated dev worlds,
not separated (paired difference −0.009, −0.07 to +0.05), with Luna at a seventh to a ninth of
Haiku's list price.

## Room workflows

### Competitor discovery (a room workflow)

`examples/workflows/competitors/`, authored into a daemon room through the MCP REPL and run
with `catalog_benchmark` (doc/room-workflows.md). Task: find products competing with Simmis,
each with its site, a claim and a verbatim quote from a page the run fetched. Reward: 0.4
recall of the reference set (the simm.is comparison: Wato, PromptQL, Dust, Buzz), 0.3
grounding (the quote is on a page the run fetched), 0.3 relevance (a judge,
`claude-code-haiku`, answers per entry whether it competes). simm.is is a blocked source:
its comparison is the answer. At most 8 Brave searches per attempt.

Results (2026-09-27, two attempts per candidate, 95% ranges wide at this size):

| Run | Luna (Codex subscription) | Claude Code Haiku | List price per attempt |
|---|---|---|---|
| live web, judged | 0.51 [0.09, 0.92] | 0.28 [0.0, 1.0] | Luna $0.020, Haiku $0.39 |
| frozen web (51 pages from 8 live Attempts), judged | 0.63 [0.0, 1.0] | 0.20 [0.0, 1.0] | Luna $0.076, Haiku $0.16 |

Neither found the whole reference set (Luna found Dust in two of four attempts); both found
relevant products the reference lacks (OpenAgents, Taskade, Symio, Onplana), which the judge
credits. Haiku paraphrased quotes more often (grounding 30–50% of entries against Luna's
80–100%) and one frozen attempt did not complete. What the runs fixed: the first live run
scored every quote ungrounded because fetched pages were cut as raw HTML before the quoted
words (now captured as text), and recall alone gave relevant new finds nothing (now the
judge). Next: more repetitions on the frozen variant, which costs no search queries.

### Contract review (CUAD, a room workflow dataset)

`examples/workflows/contract-review/`: eight contracts from the CUAD test split, one case
each (`cases/<id>/fixtures` + `gold.edn`), authored into a daemon room through the MCP REPL
and run with `catalog_benchmark`. Task: for eight clause types (Governing Law,
Anti-Assignment, Cap On Liability, Termination For Convenience, Exclusivity, Change Of
Control, Non-Compete, Audit Rights), say whether the contract has one and quote it verbatim.
Reward per clause type: 0 for the wrong presence, 1 for a correct absence, 1 for a present
clause whose quote matches a lawyer-annotated span, else 0.5; the mean over the eight. No
judge, no network.

Results (2026-09-27, one attempt per contract, both on subscriptions; list prices are
notional):

| Candidate | Mean reward | Contracts fully right | List price per attempt |
|---|---|---|---|
| Luna (Codex subscription) | 0.92 | 4 / 8 | $0.009 |
| Claude Code Haiku | 0.89 | 4 / 8 | $0.062 |

Every remaining loss is a presence call, mostly a clause claimed that CUAD does not annotate
(Exclusivity, Audit Rights, Termination For Convenience), a few of which a reader may
dispute (a six-month notice "for any reason other than breach" is not labelled Termination
For Convenience). What the first run fixed: the checker scored quotes by token F1 against a
single annotated sentence, so a model quoting the whole numbered section, or one item of a
long annotated list, lost half the clause; 7 of 16 answers did. A quote now matches when it
overlaps, contains or lies inside an annotated span (the README has the bounds). Under the
first checker both scored 0.875.


## Equivalence

A transcription counts as the benchmark only once a differential check against upstream's own
code agrees: an oracle script under `benchmarks/dev/<name>/` runs the upstream checkout (never
inside Dvergr) over gold answers and a seeded corpus of mutations, and the provider's test pins
its outputs as digests, so the suite rechecks equivalence without Python whenever the checkout
is present. The method and its results per benchmark: tau2
([Equivalence method](../benchmarks/doc/tau2.md#equivalence-method) for retail, and each
domain's section), BFCL ([Equivalence](../benchmarks/doc/bfcl.md#equivalence)). Providers
that are not transcriptions argue validity otherwise: AutomationBench runs upstream's code
and replays every world, BIRD cross-checks its grader against upstream's `execute_sql`,
SpreadsheetBench certifies tasks with an oracle, and the wiki is calibrated.
