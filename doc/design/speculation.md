# Speculation, nested simulation and nested inference

Status: proposal (2026-10-15).  It continues step 6 of [unified-worlds.md](unified-worlds.md), which deferred "experiments as scopes" until an algorithm runs over Attempts.  Speculation and inference are such algorithms.

An agent should be able to try alternatives before committing to one, simulate what an action would do without doing it, and reason under uncertainty by inference, and it should be able to do each of these inside the others: a simulation inside a particle of an inference, an inference inside a speculative Run.  dvergr and its libraries already provide most of the parts.  This document names the one operation they share, the laws that keep it composable, the gaps that break those laws today, and an order in which to close them.

## What exists

| Part | Where | Guarantee |
|---|---|---|
| Forking and settlement | the fork contract in [worlds-and-forks.md](../worlds-and-forks.md) | a world forks; a child is merged, discarded or transferred exactly once by an affine token; a nested merge targets the immediate parent |
| Conserved budgets | `dvergr.resource` wallets on one ledger; `dvergr.resource.authority/authority` as spindel's `PResourceAuthority` | a fork moves authority (grant), a discard returns it, a resume grants the remainder; transfer ids make retries idempotent |
| Model spend | `dvergr.resource/charge-spend!` against the bound `dvergr.resource/*spend-wallet*` | every model call is charged once, to the wallet of its charge scope (a Run's model step, a protocol Run, an Attempt's observation) |
| Inference in forked worlds | foerster's `:world-policy :fork` with `:authority` and `:grant` | the root draws the grant, particles split it, copies split their ancestor's remainder at resampling, discards refund |
| Search over agent steps | `org.replikativ.foerster.steer/model`; best-of-n over Runs with `:review` settlement | SMC over scored steps; Runs side by side, each in its own fork |
| Inference in rooms | the `:foerster/inference` sandbox extension (`dvergr.sandbox.ns.data/install-foerster!`) | `infer/` and `dist/` in the agent's interpreter, results as plain data, fresh worlds by default |

## One operation

Speculative execution, simulation and inference are one operation: **fork worlds with moved authority, run a program in each, and join**.  They differ in how many worlds they fork and in the join.

| Use | Worlds | Join | What reaches the parent |
|---|---|---|---|
| speculation: try alternatives | k | select one and merge it, discard the rest | one settled world |
| simulation: what would happen | 1 to k | discard all | the result values |
| inference: what is likely | N particles, copied and dropped | a weighted mixture | a posterior and its evidence |
| nesting | any of the above inside a world of another | the inner join's result is a value in the outer world | for inference, the inner evidence multiplies the outer weight |

A Run is then a weighted world: its result is a value, its verifier's or judge's score is a log weight (`factor`), and its settlement is the join of the world that was selected.  Best-of-n is inference over Runs with a selection join, and `steer` is the same with resampling between steps.

## Laws

Each law is a test, run at one level and at two nested levels.

1. **Isolation.**  A world's writes reach its parent only through a join.  A simulation leaves the parent's systems as they were; a speculation changes them only by the one merge.
2. **Conservation.**  For every wallet involved, the ledger's total after the operation equals the total before it minus what was spent; no fork, copy or discard creates or destroys budget.  This holds when particles are copied at resampling and when an inference runs inside a particle.
3. **Refund.**  What a discarded world did not spend returns to the wallet it was granted from, at every nesting level, before the operation's result is delivered.
4. **Spend follows the world.**  A model call made in a world is charged to that world's wallet, so a particle or a speculative Run cannot spend more than its share.  (Today it does not hold; see the gaps.)
5. **One settlement, to the immediate parent.**  Unchanged from the fork contract, and tested through nesting: an inner speculation settles into the particle's world, never past it.
6. **Evidence composes.**  An inner inference's evidence multiplies the outer weight, and an observation shared by both levels is counted at one of them.  Tested on a conjugate model whose nested evidence is known exactly.
7. **Irreversible effects do not happen inside speculation.**  A capability is either forkable (Datahike, files: free in a fork), charged (reads and model calls: allowed, charged to the world), or irreversible (mail, payment, a remote write).  An irreversible call in a speculative world is refused, or recorded as an obligation that runs only when the root settles.  This is the adoption boundary Simmis already draws.
8. **Reproducibility.**  Every world's random stream is derived from its parent's seed and its fork id, so a speculation replays and a resumed Run continues the same draws.

## Allocation is a policy

How many worlds to fork, how to split a budget among them, and when to stop are decisions of an allocation policy, separate from the mechanism above: SMC's resampling, racing with a deadline, best-of-n with early stopping when the evidence decides, or a person raising a budget through `:directive/raise-budget`.  The mechanism guarantees the laws whatever the policy decides, so a policy can be changed or learned without re-checking them.

## Gaps

Verified by reading the code on main (2026-10-15):

- **G1, spend does not follow the world.**  Model spend goes to the dynamically bound `dvergr.resource/*spend-wallet*`, the Run's.  A grant from a world authority goes to the world's own wallet (`dvergr.resource.authority/wallet-of`), which model calls never charge.  So a particle funded with a share would refund its share untouched while its model calls draw on the Run's wallet.  Law 4 fails, and Law 2 holds only because both wallets are on the same ledger.
- **G2, the sandbox's inference runs unfunded.**  `infer/` in the sandbox passes `:world-policy :fresh` and no `:authority`, so foerster's particle scope has none and particle worlds have no wallet.  With `:fork`, the particles are copies of the Run's world and spend, through G1, from the Run's wallet without a per-particle bound.
- **G3, two searches.**  Best-of-n over Runs and `steer` over steps implement the same search separately; a Run is not yet a world a scope can copy, weight and select (step 6's "scope whose fork and discard the embedder supplies").
- **G4, no effect classes.**  Capabilities carry no classification, so a speculative world cannot tell a refusable effect from a free one (Law 7).
- **G5, an old foerster.**  dvergr pins foerster 0.1.100.  The sandbox lacks particle MCMC (foerster#73), exact enumeration and PMMH, `foerster.core`'s re-exported effects (foerster#79), and the newer distributions.

To confirm by a test before relying on it: that a `:fork` particle's model call is charged to the Run's wallet as G1 and G2 predict.

## Plan

Each step lands with the tests of the laws it makes true.

1. **Accounting (G1, G2; Laws 2 to 4).**  Bind the spend wallet from the world: when a world has a wallet of its own (`[:dvergr/wallet]`), model calls made in it charge that wallet, falling back to the Run's.  Pass the room's ledger authority to the sandbox's `infer/` together with a `:grant` the agent names, and test conservation: N particles that make model calls, resampled, then the ledger's totals.
2. **foerster in the sandbox (G5).**  Bump foerster, extend `dist/` to the current constructors, add `:enumerate` and `:pmmh` to `foerster.sandbox`, and register `foerster.core`'s effect names in the sandbox's breakpoints.
3. **Runs as worlds of a scope (G3; Laws 1 and 5).**  The enabling change step 6 of unified-worlds named: a spindel scope whose fork and discard dvergr supplies (a Run world is a Room fork, with store, registry and context), with affine ownership, quiescence and authority unchanged.  Best-of-n and `steer` over Runs then use one implementation.
4. **Effect classes (G4; Law 7).**  Classify capabilities as forkable, charged or irreversible; refuse or defer the irreversible ones in speculative worlds, and run deferred obligations at the root's settlement.
5. **Nested evidence (Law 6).**  `infer/conditional` (foerster's nested inference) inside a particle, with the conjugate test, and the rule for observations shared between levels.

Steps 1 and 2 are independent and small; 3 needs 1; 4 is independent; 5 needs 1 and 2.

## Open questions

- **Time as a resource.**  A deadline is spent like a budget but cannot be refunded.  Should the authority carry wall-clock time beside money, with a speculation's deadline its share of the parent's?
- **What a Run's weight is.**  A verifier's pass is a hard constraint, a judge's score a soft one, a process reward a twist.  Which of these may a Run's settlement policy use, and who sets it?
- **People inside speculation.**  A review settlement waits for a person.  Should a speculative world be allowed to ask, or must questions wait for the root, like irreversible effects?
- **Cost of a world.**  A Room fork (store, registry, context) costs more than a bare context fork.  Inference over many cheap particles may want bare worlds, and speculation over few expensive Runs Room forks; the scope should accept both.

## What does not change

The fork contract, the ledger and its transfer ids, Run resume, and settlement by `:automatic`, `:review` and `:discard` stay as they are.  This proposal adds the rule that spend follows the world, a classification of effects, and one search over worlds; everything else is how the existing parts compose.
