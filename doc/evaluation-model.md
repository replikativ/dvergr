# The evaluation model: attempts as a first-class verb

Status: step 1 of *Order of work* is built (2026-09-20): every benchmark
provider and every room workflow is scored through `evaluate`. Of step 2,
model spend is debited from Run wallets and a sandbox can run an experiment
against the verifiers its host offers; the rest of steps 2-4 is not built.
Every claim about existing code names its namespace.

## Why

Dvergr is a recursively self-programmable harness: an agent can hire agents
into forked worlds, which can hire further. Evaluation should be the same
kind of thing: something an agent does, at any depth, in a further nested
world. It is partly that: the generic machinery scores every benchmark, and
an agent can start an experiment (`dvergr.agent/run-experiment!` in the
sandbox), but only against verifiers the host offers, and inside a paid
LLM Run only over `:echo`/`:scripted` candidates.

The goal is one verb, `attempt!`, from which tests, benchmarks, retries,
search and training data all follow, plus a small contract for what a
benchmark must bring itself.

## What exists

Three layers, each built on the one below.

1. **Conversation.** Rooms, participants, messages (`dvergr.discourse`).
2. **Delegation.** `hire!` (`dvergr.agent.program`) runs an AgentDef as a
   durable **Run** in a forked child world (`dvergr.agent.world`). When the
   Run ends the world is settled: merged, held for review, or discarded.
   Budgets are a conserved resource vector that thins as delegation nests,
   cancellation flows down to owned children, and every Run carries
   provenance.
3. **Evaluation.** `dvergr.agent.evaluation/evaluate` is delegation plus
   judgment. It hires the candidate into a world whose settlement is locked
   (`:deferred`), observes and verifies on a trusted host thread through an
   `Evaluator`, certifies an immutable **Attempt**, then unlocks and settles.
   `dvergr.agent.experiment/run` folds it over dataset x candidates into a
   **Scorecard**.

Definitions are portable, content-addressed values an agent can
author in its sandbox: `EnvironmentDef`, `DatasetDef`, `ExperimentDef`
(`dvergr.agent/environment`, `dataset`, `experiment` in SCI). An
EnvironmentDef *names* its verifier (`{:verifier/id :version :basis}`) and
never contains it.

Forgery is prevented in three layers, all in place: value-level
cross-checks (`dvergr.agent.attempt`), an `Evaluator` that is a process-local
record unreachable from SCI, and a governed Datahike writer
(`dvergr.agent.attempt.governance`) that rejects unauthorized creation and
any mutation of Attempts and Scorecards.

So `evaluate` is most of `attempt!`.

### Two planes

One separation in the existing code makes the rest compositional.

- **Work plane**: the forked world where a candidate acts. It holds
  application state and is settled by merge, review or discard.
- **Control plane**: the nearest ancestor Room that is not itself a Run's
  world (`control-room!` in `dvergr.sandbox.ns.agent` walks up past every
  Run world). Runs, the resource ledger and Attempts are recorded there, even
  from deeply nested hires, linked by `:run/parent`. The ledger is never
  forked or settled with a world.

Discarding a world discards its side effects and keeps the evidence.

## What was wrong, and what still blocks

Until step 1 (2026-09-20), tau2 went around this path: its episodes ran in
plain child Rooms rather than forks, its grader was called inline with no
evaluator boundary, and it re-implemented the job matrix and resume. tau2
now runs as a provider (`dvergr.benchmarks.tau2.provider`: world setup,
conversation protocol, grader as Evaluator), and `experiment/run` resumes.
Only checkpoints and branches (`episode/run!`, `branch!`) still use the
Room path (`dvergr.agent.conversation`).

**Invariants that block the rest of the direction:**

| Wanted | Blocked by |
|---|---|
| Function candidates | A candidate must be an AgentDef; programs are only `:echo :scripted :llm`; `make-attempt` cross-checks agent version and program kind |
| Agent-authored verifiers | `Evaluator` is a host record matched by exact ref (`dvergr.agent.evaluation/make-evaluator`); the sandbox can only name a verifier its host offers (`dvergr.agent.evaluators`) |
| Nested evaluation | SCI `hire!` forbids `:deferred`; children and the candidates of a sandbox experiment are limited to `:echo`/`:scripted` with provider effects off (`child-program-authority` in `dvergr.agent.program`); `experiment/run` requires `:discard` |
| Branch any Run | A world can only be branched when nothing runs in it; `RunHandle` rejects cross-fork use; the Run registry is process-local; RunWorlds fork with `:forkable-components #{}` |

Two unrelated things share a name: `dvergr.agent.episode` (a read-only join
over Attempts) and `dvergr.benchmarks.tau2.episode` (a live runner). The
latter should be renamed when it is folded in.

## The model

### `attempt!`

`attempt!` is `hire!` with a verdict attached.

| Step | What happens | Piece |
|---|---|---|
| 1 | Fork a child world for the candidate, settlement locked | `world/open!`, `:deferred` (exists) |
| 2 | Prepare the world; start the environment's driver if it has one | `WorldSetup` (exists); driver (new) |
| 3 | Run the candidate to the end under limits and budget | `hire!` machinery (exists) |
| 4 | Observe and verify on the trusted side | `Evaluator` (exists) |
| 5 | Record an immutable Attempt: reward, checks, evidence, verifier identity and trust | governed certification (exists) |
| 6 | Unlock and settle the world, normally discard | `settle!` (exists) |

`evaluate` stays the host-side implementation and the only writer of
Attempts. `attempt!` is that path, widened in three places and exposed to
the sandbox. `evaluate!` over an experiment is a fold of `attempt!`, with
resume.

Everything else is a policy over attempts, differing only in what is done
with the verdict:

| Use | Policy |
|---|---|
| Delegation | ignore the verdict (`hire!`) |
| Test | assert it: function candidate, deterministic verifier |
| Benchmark | aggregate it over dataset x candidates x repetitions |
| Retries, best-of-N | select on it, from one snapshot |
| Search (SMC, MCTS) | resample or back up on it |
| Training | learn from it |

Snapshot and branch is orthogonal: it answers *from when* an attempt
starts; `attempt!` answers who runs, against what, judged by whom.

### Candidates

A candidate is an AgentDef ref or a function. A function candidate is a new
program kind, `:fn`, whose portable identity is its source (content-addressed,
like every other definition), interpreted in the child world's sandbox. It
needs no model call, which makes a test the cheapest attempt and lets an
agent iterate on its own code before it spends tokens.

### Verifiers and trust

As built, the trust tier is a field of the `Evaluator` record
(`dvergr.agent.evaluation`, `trust-tiers`), set by the host code that
constructs it (`make-evaluator :tier`, default `:trusted`), and every receipt
records it as `:verifier-trust` in its metrics:

- **trusted**: host code (the benchmark providers' graders, tau2's among
  them).
- **room**: a room workflow's checker whose bundle the host promoted after
  calibration (`dvergr.catalog.room/promote!`, `catalog_promote`).
- **ad-hoc**: an unpromoted bundle's checker. Usable for an agent's own
  iteration.

Every Attempt is stored, in every tier: trust is a field on the record, not
a storage decision. An agent cannot claim a higher tier, because only host
code constructs an Evaluator and agent code can only name one by ref. There
are two host registries. `dvergr.agent.evaluators` holds what the
sandbox's `run-experiment!` may use (the wiki benchmark and the workflow
completion check). `dvergr.agent.verifiers` is a second registry with a
`register!` in the tau2, BFCL, BIRD and AutomationBench providers, but no
code path calls them and only the registry's own test reads it: the shared
runner (`dvergr.agent.experiment.runner`) builds the capability maps
`experiment/run` takes from the provider's `{:world-setup :protocol
:evaluator}` directly.

Not built: a minimum tier for Scorecards (one more check in
`validate-scorecard-attempts`, which already re-verifies every entry);
today a Scorecard aggregates Attempts of any tier, and `scorecard_detail`
lists the tiers among its Attempts. Agent-authored verifiers
outside the room-workflow checkers are not built either.

The checks are split in two. `observe` runs on the host with the live world
and may do IO: a room workflow's observer asks its judge model and keeps the
answers in the evidence (`:judgements`), and its `capture` reads the world's
files. `verify` is a function of the EnvironmentDef and the portable evidence
`observe` returned, so such a verdict can be re-derived from the stored
evidence. tau2's observer, too, asks its judge model for a task's
natural-language assertions (`nl-assertion-checks` in
`dvergr.benchmarks.tau2.core`) and keeps the answers in the evidence
(`:nl-assertions`); its verify grades from them, so a certified tau2 Attempt
re-verifies without a model call (grader version 4; Attempts of version 3
asked the judge in verify and do not mix with them). A score means what its
verifier's identity says, not what its reporter claims.

A verifier may offer a **partial** form, called at checkpoints, returning
reward components so far. It is optional; without it a benchmark has
terminal rewards only (see Caveats).

### What a benchmark brings

Four things. The rest is shared.

1. **World setup**: the initial world (tau2's store, the customer's phone).
2. **Protocol and driver**. By default an `:llm` program is one task, one
   answer: the Run completes on the model's first text reply
   (`program/execute-llm-program`). An EnvironmentDef may instead name a
   protocol by exact ref in its world (`{:protocol/id :protocol/version
   :protocol/basis}`); the host resolves it to a `Protocol` capability
   (`evaluation/make-protocol`) whose `run` hosts the whole interaction in
   the Run's work world, under the Run's supervisor, and returns the Run's
   value. Each provider brings its own: tau2's `:tau2/conversation` joins the
   candidate and a trusted **driver** (the simulated customer with its own
   tools and private scenario) and runs the turn loop under `:max-steps` and
   `:max-errors`; BFCL, BIRD, SpreadsheetBench and AutomationBench have
   theirs. A protocol's `inputs` says what it presents to the candidate
   beyond the AgentDef (tau2: the system prompt), which enters the Attempt's
   inputs id.

   Exposing the customer as a tool inside one ordinary Run was rejected:
   customer messages would reach the model as tool results rather than user
   turns, which is a different benchmark from upstream's.
3. **Verifier**, terminal and optionally partial.
4. **Private data**: what the candidate must never see (gold actions). The
   interpreter projects tasks through this boundary, so the same mechanism
   serves tau2's test split and a benchmark one agent authors for another.

Single-turn benchmarks use the `:task` protocol and have no driver. Container-bound ones keep an external
adapter as their world setup.

### Recursion, and what surfaces

A candidate inside an attempt may call `attempt!` and gets a grandchild
world. Controls are those of `hire!`: budgets thin out, cancellation flows
down, authority narrows.

What comes back travels on two channels, which stay separate:

- **Value channel, programmable.** A Run returns `:run/value`; the nested
  program decides what that is (a Scorecard, a summary, the winner). Values
  carry references (attempt ids, content ids), not copies.
- **Record channel, automatic.** Attempts and Runs go to the control plane
  whatever the program returns. A parent reads them with the scope
  `agent/inspect` already has: a Run and its structural descendants. So
  reading attempts means "attempts in my subtree". Visibility follows
  ancestry, and no agent has to remember to return its evidence.

An attempt may designate a child Room as its control plane, so a bulk
experiment does not flood its parent (what tau2's experiment Room does
informally).

### Comparing candidates

Every candidate of an experiment runs on every case, so two candidates
compare **paired**: on the same cases, looking only at the ones where they
disagree. The report's *Paired comparisons* section (`experiment_report`;
the data is its `:paired`, and `scorecard_detail`'s) gives, per pair: the
cases compared, both passed / A only / B only / both failed, the exact
McNemar p (the two-sided exact binomial test of "A only" against
Binomial(A only + B only, 1/2), `dvergr.agent.experiment.stats/mcnemar-p`)
and a verdict: A better, B better, or no detectable difference at these
cases. 12 cases against 2 decides (p = 0.013); 6 against 5 says nothing
(p = 1).

- **The pass** is the Scorecard's `:passed?`: every check of the receipt
  held. A reward in (0, 1) that is not a pass is a fail here, whatever its
  value; the reward difference stays in `scorecard_detail`'s `comparison`.
- **Only verdicts count.** A case enters a pair when both candidates have a
  verdict on it; a faulted cell (a failure of the path to the model) or one
  not run yet leaves its case out of that pair, and the report says how
  many; so does a Scorecard entry whose Attempt cannot be loaded. Without a
  Scorecard the section covers the Attempts certified so far (cases no
  candidate has reached yet are not counted).
- **Repetitions.** Repetitions of a case are exchangeable, not paired with
  each other (repetition 2 of A has nothing to do with repetition 2 of B),
  and they are not independent cases either. So a case counts once: on the
  repetitions both candidates have, the one with the higher pass rate wins
  the case, equal rates tie (*Tied*: neither always nor never). That is the
  exact sign test over cases, which is McNemar's test at one repetition.
- **Many candidates.** Up to five candidates every pair is compared (ten
  pairs); beyond that, each candidate against the one with the highest pass
  rate. With `baseline`, each candidate against that one.
- **Multiple comparisons.** The p-values are Holm-adjusted, and the verdict
  uses the adjusted p (below 0.05). The family is every pair, also when only
  those against the best are shown (choosing the best looked at all of
  them); against a `baseline` named in advance, its comparisons. A pair whose
  disagreements are too few for even a clean sweep to reach 0.05 (five or
  fewer) says so.

"No detectable difference" is not "equal": it means these cases do not
separate the two. More cases, or harder ones, may.

### Accounting

Model spend reaches the conserved ledger (`dvergr.resource`, Kontor) when a
Run is allocated microdollars: the LLM program binds the Run's wallet
(`resource/*spend-wallet*`, `dvergr.agent.program`) around its model steps,
and every model call's cost is debited from it with `consume!`
(`charge-spend!`, called from `dvergr.chat.context`). An empty wallet ends
the Run's budget; a resumed Run spends what the stopped one left (a new Run,
so new debit ids; what the stopped Run paid stays paid). Model
dispatches can also be admitted one by one (`admit-model-dispatch!`), and
arena commands debit their own units (`dvergr.agent.arenas.renewal`).

| | Conserved ledger | Chat budget (`budget-signal`) |
|---|---|---|
| Lives in | control Room store; never forked | the working context; forks with the world |
| Tracks | a grant at hire, model spend debited per call, the remainder returned | tokens x price; stops the Run on budget |
| Idempotence | each debit's id is derived from its charge scope (the Run and model step) and its ordinal there, so a retried step debits once | not conserved; only capped by the wallet when one is bound |

A protocol Run's model calls (a tau2 conversation's, the candidate's and the
customer's alike) are debited from the Run's wallet too, in the charge scope
`[:protocol run-id]`: the protocol worker binds the wallet, and
`dvergr.benchmarks.live/scoped` carries the binding to the threads its
participants call models on. A protocol's observer still reports those calls
as the Attempt's `:spend` on its receipt. The model calls an Attempt's
observation makes, a judge's (a room workflow's `model-judge` in
`dvergr.catalog.room`, tau2's NL-assertion judge), are paid by the wallet the
candidate's Run was granted from, its parent Run's or else the control Room's,
never by the candidate's own (scope `[:observe run-id]`). A wallet is charged
only when it holds microdollars. Those calls are recorded, wallet or not, as
`:judge-spend` in the receipt's metrics, apart from `:spend`: `report.md`
lists them in a Judge table (list price and billed), `scorecard_detail` as
`judge-spend` on each leaderboard row, and no candidate's cost per attempt or
per pass includes them. A nested model Run is admitted only when its grant
funds it (money and dispatch admissions, `dvergr.resource/funded-grant?`),
each dispatch reserving a bound of its cost first.

Not built: attempt wallets. The plan: an attempt is funded as follows. The caller grants an **attempt
wallet**; the attempt sub-grants to each paid role, each an ordinary Run with
its own wallet: the **candidate**, the **driver**, the **judge**. Per-role
limits come from the EnvironmentDef; unused amounts return upward as today;
the receipt's `:resources` reports spend per role.

- Every candidate gets the same candidate budget whatever the simulator
  does, so verdicts are comparable.
- The environment's own cost is visible separately.
- The total is bounded by the caller's grant, so recursion cannot overspend.

Attempt wallets are one instance of the general budget tree in [design/runs-as-worlds.md](design/runs-as-worlds.md#budgets-as-a-tree): funded child Runs with a role and a split declared by the parent, which is also how searches, experiments and optimizers divide a budget.

(tau2 today budgets only the candidate's chat; with a Run wallet, the
customer's calls come out of that same wallet and the judge's out of the
caller's.)

### One log

An Attempt's evidence carries a step log. Its key is the **prefix id**: the
content hash of everything before the step.

```clojure
{:step/attempt-id  uuid     :step/seq     long
 :step/prefix-id   uuid     :step/parent  [prefix-id seq]   ; branch lineage
 :step/world       {...}    ; snapshot / fork descriptor
 :step/role        :assistant|:tool|:user
 :step/action      {...}
 :step/candidate   {:agent-def-hash .. :provider .. :model ..}
 :step/verifier    {:verifier/id .. :version .. :trust ..}
 :step/reward      {:terminal n? :partial n? :components {kw n}}
 :step/log-weight  double   ; search
 :step/tokens      {:input n :output n :cached n}
 ;; only a model we serve ourselves can fill these:
 :step/token-ids [long] :step/loss-rows [long] :step/logprobs [double]}
```

Benchmarks group by attempt. Search groups by generation. Two steps with
one prefix and different rewards are a preference pair. A prefix, its action
and its reward are a policy-gradient sample. tau2's trajectory log already
has most of the first half.

## Caveats, stated plainly

- **Terminal-only rewards make SMC equal to best-of-N.** tau2 and the
  current `Evaluator` score at the end. Particle filtering needs partial
  verifiers, or a value estimate of the reward to come. foerster (0.1.45)
  has the machinery: `(factor w :barrier true)` weighs and resamples at a
  scored step, particles that end early are carried along, and
  `infer/steer` builds the twisted step loop — a value estimate ψ at each
  step, the reward at the end, target p(trajectory)·exp(reward). With no
  value estimate it is exactly best-of-N weighted by the reward. MCTS
  (`spindel.search.mcts`, in 0.1.49) fits terminal rewards too.
- **A model step cannot be forked.** A step is blocking host work. Snapshots
  are taken between steps.
- **Training starts at SFT.** `finetune-rstr` has real SFT with
  response-only loss masks. There is no DPO, RL, reward model or logprob
  path in the raster repositories, and `pretrained-rstr`'s KV-cache fork is
  real but cannot be requested by a client. `claude -p` gives no logprobs,
  token ids or seeds, ever. So now: export successful trajectories and
  same-prefix preference pairs as data. RL needs a policy we serve.
  Trajectory export exists: `attempt_export {room min-reward? file?}`
  (`dvergr.agent.trajectory`) gives one JSON object per certified Attempt with
  the task, the candidate and model, every model turn with its tool calls and
  results, the effects on its world (receipts with idempotency and result
  digests, from the Attempt's effect log) and the verdict (reward, checks,
  verifier trust, spend). Preference pairs are not built yet.
- **Providers are nondeterministic.** Branches are samples, not replays.

## Order of work

Refactor first. Each step keeps the suite green.

1. **Fold tau2 into `evaluate`.** DONE (2026-09-20), except the last item.
   - Trust tiers on the Evaluator (`make-evaluator :tier`), recorded on
     every receipt (`dvergr.agent.verifiers`, the registry this step added,
     is unused outside its tests).
   - Environment protocols (`evaluation/make-protocol`): a Run hosts a
     trusted interaction in its world, under its own supervisor.
   - `experiment/run`: protocols, each Attempt's cell identity, `:resume?`,
     `:complete-only?`. A resume keeps a cell only while its Attempt's inputs
     id (`program/run-inputs`: what the Run resolved at its start and
     presented to the candidate, the assembled system prompt, the tools
     offered, the resolved model, a protocol's `:inputs`) equals the one the
     cell would get now; stale cells re-run and their Attempts count in no
     Scorecard (doc/benchmarks.md, "Resume and inputs ids").
   - `dvergr.benchmarks.tau2.provider`: world setup, conversation protocol,
     grader as Evaluator. `tx/run!` is `experiment/run`; the host runner
     (`tau2.runner/run!`) remains for unrecorded batches only. All four domains and all three candidates verified on it.
   - OPEN: checkpoints and branches (`episode/run!`, `branch!`) still use the
     Room path, which keeps `dvergr.agent.conversation`'s certification
     alive. They move with step 3. The tau2 episode namespace is not renamed
     yet.
2. **Widen the primitive.** First the prerequisite: debit model spend from
   Run wallets (`consume!`; built, a debit once per charge), then attempt wallets with
   per-role sub-grants; this is also what lets `:provider-effects?` open for
   delegation generally. Then `:fn` candidates; agent-authored verifiers in
   the `:room` and `:ad-hoc` tiers; a minimum tier for Scorecards;
   `attempt!`, `evaluate!` and reading the subtree's Attempts in the sandbox,
   with the attenuation `hire!` already applies. Built so far: the sandbox's
   `run-experiment!` (`dvergr.sandbox.ns.agent`) runs an experiment as a job
   under the calling Run, against host-offered verifiers; inside a paid LLM
   Run its candidates are limited to `:echo` and `:scripted`.
3. **Run-level snapshot and branch** in `dvergr.agent.world`, replacing
   tau2's. The seam exists: `hire-prepared-in!` already separates the control
   Room from the world parent, so a branch is an evaluation whose world
   parent is a retained snapshot world. Then the step log with prefix ids.
4. **Policies.** Best-of-N and retries from a snapshot; per-turn value
   curves; partial verifiers; then search: SMC over steps with
   `infer/steer` (partial verifiers or value estimates as twists), and MCTS.

## Decided

- Attempts of every trust tier are stored; tiers gate aggregation.
- Paid roles (candidate, driver, judge) are separate Runs with their own
  wallets under one attempt wallet.
- Nested worlds return what their program returns; records always reach the
  control plane and are read by ancestry.
