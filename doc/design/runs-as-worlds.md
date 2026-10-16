# Runs as worlds of a search

Status: plan (2026-10-16), step 3 of [speculation.md](speculation.md).  Verified against spindel v0.1.122, dvergr main and simmis main; the decisions below were taken in discussion.  Steps are built in the order given.

Step 3 asked for "Runs as worlds of a scope", so that best-of-n over Runs and foerster's `steer` share one search.  Making a Run world *be* a spindel scope world would fight nine mismatches, each confirmed in the code: the scope keeps fork handles private while a Room owns and settles its own; the scope forces `:mode :frozen` while Run worlds fork `:following`; a scope fork has no bus, store, registry entry or grants; a Run is a durable identity and a world is process-local; model steps cannot be copied mid-step; a Run's wallet is keyed by the Run, so the ledger authority neither returns nor splits it; quiescence is tracked by scope activities on one side and the Run supervisor on the other; `:review` and `:deferred` worlds outlive any scope; and a Run has two parents (the control Room that records it, the world it forks).

So this plan unifies one level up: **one search over worlds, with two kinds of world behind one small protocol.**  Particles and simulations stay bare scope worlds; Runs stay Room forks.  They share the algorithm (open, weigh, copy, drop, select), the budget (one ledger), and the join (merge, hand to review, or discard).

## The protocol

| Operation | Scope worlds (particles, simulation) | Run worlds (agent Runs) |
|---|---|---|
| `open` from a parent, with a grant | `world-scope/fork!` with `:grant` | `hire-prepared-in!` with `:settlement :deferred` (fork, durable Run, allocation from the parent's wallet) |
| `weight` | the particle's log weight, at any observation | a score on the finished Run: verifier, judge, evaluation `:reward`; mid-Run scores do not exist yet |
| `select` the join | **new** `world-scope/adopt!` (change A), then merge into the source | `forks/release-deferred!` then `forks/merge!` (automatic), or `release-deferred!` to `:review`, where simmis promotes it to a Proposal (`run-world-proposals/promote!`, top-level Runs only) |
| `discard` | `world-scope/release!` | `forks/discard-deferred!` |
| `spent` and `left` | `PResourceAuthority/balance` | the Run wallet; a Run returns its remainder to the parent when it ends, before settlement, so selecting or discarding a finished Run moves no budget |
| `copy` k times | `world-scope/copy!` with `:split?` (synchronous authority, JVM) | **not available**: `resume!` resumes the latest savepoint once, of a terminal Run, with its whole remainder and the same seed (change D) |

Handing a world to review ends the search's ownership of it: a `:review` world waits on a person without a deadline, so a search that kept it could not reach quiescence.

## Changes, in order

**1. dvergr: best-of-n over Runs (no copying).**  A real selector, which does not exist today: `workflow/attempt!` returns N `:review` worlds for a reviewer and `forks/merge-review!` judges one fork at a time.  The search opens N Runs with `:settlement :deferred`, weighs them when they finish, selects by a policy (`:automatic` merges the highest weight, `:review` hands it to a person, `:keep-all` hands all), and discards the rest with `discard-deferred!`.  Admission blocks on the store and must run off the drain thread (spindel#110), as evaluation does.  Laws: conservation across the N Runs (each returns its remainder), one settlement into the immediate parent, refund of the unselected.

**2. dvergr: the authority sees Run wallets.**  Let `LedgerAuthority/return!` and `balance` resolve a world's Run wallet through `wallet-of`, instead of only wallets it opened, so a search can read what a Run has left and a scope can split it.  Do not route Run allocation through the authority: the Run spend path (`model-scope`, `consume!`, `return-unused-resources!`, `resume!`) reads the Run-keyed allocation, and a Run world carrying `[:dvergr/wallet]` would redirect every nested fork's spend.

**3. spindel: a selection join for scope worlds** (`world-scope/adopt!`).  Hand one open world's handle out of the scope (as `ygg/transfer-fork!` hands authority over) so its new owner can merge it into the source.  It must settle what the scope otherwise settles at discard: return or hand over the world's wallet (the authority has no merge operation), remove it from the `:copied` bookkeeping, and settle its copy family's ancestry, which holds the split's rounding remainder.  Optional `:mode` in `:fork-opts`, which today is overwritten with `:frozen`.

**4. Effect classes** (speculation.md step 4) are a prerequisite for copying Runs: a copy redoes the work since its savepoint, so `:once` effects would happen k times.

**5. dvergr: copying a Run at a savepoint.**  A k-way fork from one savepoint: a counted claim (where `claim-resume!` allows one), the remainder split k ways (the authority's `escrow!`/`claim!` has the shape, but claims once), and per-copy seeds derived from each copy's fork id (Law 8), since resuming hydrates the savepoint's own seed and k copies would draw the same.  Only for Runs whose effects since the savepoint are forkable or charged (step 4).

**6. steer over Runs** needs scores at savepoints, mid-Run (a process reward the Run publishes), which nothing provides yet.  Until then, steer runs over scope worlds, as it does in the sandbox.

Steps 1 and 2 are small and useful now.  Step 3 is independent.  Steps 5 and 6 wait on step 4 and on a decision about mid-Run scores.

## What does not change

Room forks, the fork contract, Run admission and resume, settlement policies, adoption into Proposals, the Run-keyed wallets and the ledger's transfer ids.  Model steps stay uncopyable mid-step.

## Constraints from simmis

- `promote!` takes a top-level Run world in `:review`, one adoption per Run; a search's selected Run reaches review this way, the others never do.
- Copies of a Run would appear as k Runs with the same `:run/caused-by`, while `:run/resumed-by` is single-valued; the Run detail and Attempt board group by parent and cause and have no notion of a search or of siblings.
- Rooms hold `:room/budget-dollars` in the system DB; simmis does not mint or install the ledger, so a search's conserved spend is not visible in simmis today.
- Simulated results must stay apart from accepted state (`doc/recursive-work-roadmap.md`): a search whose join is discard, with assumptions and samples recorded.
- Limits: the workflow caps attempts at 8 and models at 4.

## Decisions

1. **Parallel and alternative composition.**  Overlay forks (simmis's private and campaign forks) are for concurrent tasks that coordinate and are all meant to land: parallel composition.  A search is for alternatives of one task, of which at most a few land: alternative composition.  A search's members are Run worlds; overlays organize concurrent work inside any world, and an overlay task may start a search for its own step.
2. **Automatic, inspectable, overridable.**  A search runs under a policy that selects by weight and discards the rest without anyone acting.  While it runs, a person can open any live member, inspect it, and override the policy (select, keep, drop).  A retention rule (keep the best k for inspection until a deadline or a budget runs out) decides what stays inspectable after selection.  Handing a world to review ends the search's ownership of it.
3. **Weights are `observe` and `factor`.**  Anything may weigh a Run.  The interface is foerster's: a world's weight is what its `observe` and `factor` statements accumulate.  A verifier is a `factor` of 0 or −∞, a judge a `factor` of its log score, a person's choice an `observe` of it, and a process reward a `factor` at a savepoint (`:barrier true`), which is what `steer` resamples on, so mid-Run scores need no other mechanism.
4. **Nested Runs are funded, not refused.**  The model is built for recursion.  A child Run funded by a grant from its parent's wallet may make provider calls; the refusal (`:provider-effects? false`) remains for unfunded children.  Conservation bounds a whole recursion by the root's budget.
5. **Simulation is one object with kinds.**  A simulation is a search whose join discards and records: assumptions, samples, results, provenance, behind the epistemic boundary of simmis's roadmap (simulated is not observed).  Kinds: *projection* (what will happen), *counterfactual* (what would have happened), *estimation* (what is true now but unobserved), *hypothetical* (how would an actor respond).  Simmis's Futures view becomes a Simulations view with projection as one kind; `:budget` and `:goal` intents are plans, not simulations, and are routed apart from it.
6. **Searches are recorded durably; settlement after a restart waits for the shared capability store.**  A search's members, weights, policy and outcome are facts in the store, so a search is visible and auditable after a restart.  The live fork handles that allow a merge or discard stay process-local, as for Proposals: after a restart, unsettled members expire and are discarded, and a search can be started again from its members' savepoints.  Settlement that survives a restart is the same problem simmis has for Proposals and is solved once, for both.

## Order of work

1. This plan and its decisions.
2. The authority sees Run wallets (change 2).
3. Best-of-n search over Runs, recorded durably, with weights by `observe`/`factor` and the policies `:automatic`, `:review` and `:keep-all` (change 1, decisions 2, 3, 6).
4. Funded nested Runs (decision 4).
5. `world-scope/adopt!` in spindel (change 3).
6. Simmis: the Simulations view (decision 5) and the search's siblings in the Run detail.
7. Effect classes, then copying Runs at savepoints and steer over Runs with `factor` at savepoints (changes 4 to 6).
