# The evaluation model: attempts as a first-class verb

Status: design, agreed direction (2026-09-20), partly built; see *Order of
work* for what exists. It follows a systematic map of the current harness; every
claim about existing code names its namespace.

## Why

Dvergr is a recursively self-programmable harness: an agent can hire agents
into forked worlds, which can hire further. Evaluation should be the same
kind of thing: something an agent does, at any depth, in a further nested
world. Today it is not. The generic machinery exists but only host code can
drive it, and the one real benchmark (tau2) goes around it.

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

Definitions are already portable, content-addressed values an agent can
author in its sandbox: `EnvironmentDef`, `DatasetDef`, `ExperimentDef`
(`dvergr.agent/environment`, `dataset`, `experiment` in SCI). An
EnvironmentDef *names* its verifier (`{:verifier/id :version :basis}`) and
never contains it.

Forgery is prevented in three layers, all in place: value-level
cross-checks (`dvergr.agent.attempt`), an `Evaluator` that is a process-local
record unreachable from SCI, and a governed Datahike writer
(`dvergr.agent.attempt.governance`) that rejects unauthorized creation and
any mutation of Attempts and Scorecards.

So `evaluate` already is about 90% of `attempt!`.

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

## What is wrong today

**tau2 bypasses the generic path** (`dvergr.benchmarks.tau2.*`,
`dvergr.agent.conversation`):

- Episodes run in plain child Rooms, not forks: no RunWorld, no settlement
  axis, no locked world.
- The grader is called inline, in the same code that builds the evidence
  and the receipt. There is no evaluator boundary, so the trust model above
  does not hold for tau2 scores.
- Its EnvironmentDefs are structurally rejected by `evaluate`
  (`:world {:isolation :room}`, limits `:max-steps`/`:max-errors`).
- It re-implements the job matrix, parallelism and Scorecard assembly of
  `experiment/run`. It adds **resume**, which the generic runner lacks.
- Snapshot and branch is bespoke to tau2 (`episode/branch!`).

**Invariants that block the agreed direction:**

| Wanted | Blocked by |
|---|---|
| Function candidates | A candidate must be an AgentDef; programs are only `:echo :scripted :llm`; `make-attempt` cross-checks agent version and program kind |
| Agent-authored verifiers | `Evaluator` is a host record matched by exact ref; trust is binary; no registry in `src/` (only `dev/`) |
| Nested evaluation | SCI `hire!` forbids `:deferred`; children are limited to `:echo`/`:scripted` with provider effects off; `persist!` is unreachable from SCI; `experiment/run` forces `:discard` |
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

Verifiers move into a registry in `src/`, keyed by the verifier ref. Every
Attempt records its verifier's identity and a trust tier:

- **trusted**: host registry (tau2's grader).
- **room**: authored in this Room and vetted, through the same gate skills
  already use.
- **ad-hoc**: authored and unvetted. Usable for an agent's own iteration;
  never aggregated into a Scorecard a parent reads as authoritative.

Every Attempt is stored, in every tier: trust is a field on the record, not
a storage decision. The host assigns the tier by resolving the verifier in
the registry, so an agent cannot claim a higher one. Policy applies when
aggregating: a Scorecard requires a minimum tier (one more check in
`validate-scorecard-attempts`, which already re-verifies every entry).
Nothing is ephemeral, so anything can be forked or re-verified later.

In every tier a verifier is content-addressed, pure, and runs with no IO in
a fork, so any verdict can be re-derived. A score means what its verifier's
identity says, not what its reporter claims.

A verifier may offer a **partial** form, called at checkpoints, returning
reward components so far. It is optional; without it a benchmark has
terminal rewards only (see Caveats).

### What a benchmark brings

Four things. The rest is shared.

1. **World setup**: the initial world (tau2's store, the customer's phone).
2. **Protocol and driver**. Today an `:llm` program is one task, one answer:
   the Run completes on the model's first text reply
   (`program/execute-llm-program`). An EnvironmentDef therefore names a
   protocol:
   - `:task` (default): today's behaviour.
   - `:conversation`: the hired Run *is the episode*. It joins the candidate
     and a trusted **driver** in the work world, runs the turn loop under the
     environment's limits and stop conditions, and ends when the driver says
     so. Each candidate reply is an `:agent-turn` Run, a structural child of
     the episode Run.

   The conversation protocol is generic; tau2 supplies only its driver, the
   simulated customer with its own tools and private scenario. The
   alternative, exposing the customer as a tool inside one ordinary Run, was
   rejected: customer messages would reach the model as tool results rather
   than user turns, which is a different benchmark from upstream's.
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

### Accounting

Today two accounting systems are not connected:

| | Conserved ledger (`dvergr.resource`, Kontor) | Chat budget (`budget-signal`) |
|---|---|---|
| Lives in | control Room store; never forked | the working context; forks with the world |
| Tracks | authority to spend: a grant at hire, the remainder returned | actual spend: tokens x price; stops the Run on budget |
| Gap | `consume!` has no callers: spend is never debited | not conserved, not tied to a wallet |

This is why nested paid work is closed (`:provider-effects? false` in
`child-program-authority`: "until provider usage and Kontor receipts form
one atomic path"). The prerequisite for nested attempts is therefore to
debit model spend from the Run's wallet with `consume!`, under a stable id
so a retried effect cannot charge twice.

With that, an attempt is funded as follows. The caller grants an **attempt
wallet**; the attempt sub-grants to each paid role, each an ordinary Run with
its own wallet: the **candidate**, the **driver**, the **judge**. Per-role
limits come from the EnvironmentDef; unused amounts return upward as today;
the receipt's `:resources` reports spend per role.

- Every candidate gets the same candidate budget whatever the simulator
  does, so verdicts are comparable.
- The environment's own cost is visible separately.
- The total is bounded by the caller's grant, so recursion cannot overspend.

(tau2 today budgets only the candidate; the customer's and the judge's usage
is logged but unbounded.)

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
  verifiers. Spindel also lacks a `factor` effect (weights can only come
  from densities at `observe` sites) and resamples at a global lockstep
  barrier that throws when particles end at different turn counts. MCTS
  (`spindel.search.mcts`, in 0.1.49) fits terminal rewards better.
- **A model step cannot be forked.** A step is blocking host work. Snapshots
  are taken between steps.
- **Training starts at SFT.** `finetune-rstr` has real SFT with
  response-only loss masks. There is no DPO, RL, reward model or logprob
  path in the raster repositories, and `pretrained-rstr`'s KV-cache fork is
  real but cannot be requested by a client. `claude -p` gives no logprobs,
  token ids or seeds, ever. So now: export successful trajectories and
  same-prefix preference pairs as data. RL needs a policy we serve.
- **Providers are nondeterministic.** Branches are samples, not replays.

## Order of work

Refactor first. Each step keeps the suite green.

1. **Fold tau2 into `evaluate`.** DONE (2026-09-20), except the last item.
   - Verifier registry with trust tiers in `src/` (`dvergr.agent.verifiers`);
     the tier is recorded on every receipt.
   - Environment protocols (`evaluation/make-protocol`): a Run hosts a
     trusted interaction in its world, under its own supervisor.
   - `experiment/run`: protocols, each Attempt's cell identity, `:resume?`,
     `:complete-only?`.
   - `dvergr.benchmarks.tau2.provider`: world setup, conversation protocol,
     grader as Evaluator. `tx/run!` is `experiment/run`; its own runner is
     gone. All four domains and all three candidates verified on it.
   - OPEN: checkpoints and branches (`episode/run!`, `branch!`) still use the
     Room path, which keeps `dvergr.agent.conversation`'s certification
     alive. They move with step 3. The tau2 episode namespace is not renamed
     yet.
2. **Widen the primitive.** First the prerequisite: debit model spend from
   Run wallets (`consume!`, effectively once), then attempt wallets with
   per-role sub-grants; this is also what lets `:provider-effects?` open for
   delegation generally. Then `:fn` candidates; agent-authored verifiers in
   the `:room` and `:ad-hoc` tiers; a minimum tier for Scorecards;
   `attempt!`, `evaluate!` and reading the subtree's Attempts in the sandbox,
   with the attenuation `hire!` already applies.
3. **Run-level snapshot and branch** in `dvergr.agent.world`, replacing
   tau2's. The seam exists: `hire-prepared-in!` already separates the control
   Room from the world parent, so a branch is an evaluation whose world
   parent is a retained snapshot world. Then the step log with prefix ids.
4. **Policies.** Best-of-N and retries from a snapshot; per-turn value
   curves; partial verifiers; then search, which needs `factor` and a
   barrier that tolerates uneven episode lengths in spindel.

## Decided

- Attempts of every trust tier are stored; tiers gate aggregation.
- Paid roles (candidate, driver, judge) are separate Runs with their own
  wallets under one attempt wallet.
- Nested worlds return what their program returns; records always reach the
  control plane and are read by ancestry.
