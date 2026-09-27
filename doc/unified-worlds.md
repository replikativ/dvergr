# One algebra for worlds, savepoints and budgets

Status: **design** (2026-09-27, agreed in direction: "unify our stack and FRP algebra").

dvergr grew its own versions of things spindel now provides: Run worlds beside spindel's
savepoint worlds, a resource ledger beside spindel's resource authority, a resume path beside
`hydrate!`, world-level effect handler tables beside spindel's handler tables. Each pair does
the same thing with different names and different guarantees. This document maps them and
says how each dvergr concept becomes an instance of the spindel one, so that the laws spindel
states (and tests) hold for dvergr too.

## The map

| dvergr today | spindel primitive | Gap |
|---|---|---|
| Run world: `world/open!` → `d/fork-room` → a `ygg/fork!` handle the Room owns and settles (merge, discard, review) | a world of a savepoint session: `world-scope/fork!`, handle owned by the scope | the scope's handle is private: a scope world cannot be a settleable Room |
| Run budget: `dvergr.resource` wallets (Kontor), conserved transfers keyed by Run | `world.scope/PResourceAuthority`: `grant!` on fork, `return!` on discard, `escrow!`/`claim!` across a process boundary, keyed by world | none in kind: the same four moves; the key differs (Run vs world) |
| per-Run model budget (`budget-dollars` on the chat) | a resource in the authority | the chat budget is not in the ledger, so it is neither conserved nor escrowed |
| turn savepoints: `:conversation/turn`, persisted on the Run (#194) | `savepoint`, `portable/persist` | done; `persist` needs a session even without escrow (spindel bug) |
| `resume!`: a new Run in a Run world forked at the savepoint's snapshots | `portable/hydrate!`: fork a session root pinned at the snapshots, prepare it, run the named function | hydrate! forks through the scope (see row 1); `continue-llm-run` is a label, not the continuation (Law 5 not exercised) |
| effect handler configuration in world state (`[:dvergr/effects :handlers]`, inherited by forks, composed) | savepoint handler tables in world state (`[:savepoint/handlers]`, inherited by forks, replaceable) | two tables, one pattern; dvergr's composes algebraically, spindel's merges |
| Attempt worlds: one forked world per cell, discarded after scoring | a `world-scope` over the experiment's worlds, with quiescence and discard | experiments manage forks and cleanup themselves |

## The plan

1. **spindel (PRs for review):**
   - `persist` without a session (the NPE: `@(:scope (sp/session world))` with no session).
   - `portable/hydrate-into!`: hydrate `data` into a world *the embedder* forked (pinned at
     `(:world/systems data)`), in a session: clear the inherited savepoint bookkeeping, write
     the declared state and seed, repin components, claim the escrow, `start-in!` the named
     function. `hydrate!` becomes `fork` + `hydrate-into!`. The embedder keeps its handle, and
     the law is unchanged.
2. **dvergr authority:** `dvergr.resource/authority`, a `PResourceAuthority` over the ledger,
   mapping a world to the Run that owns it (`:run-id` in the Room's meta): `grant!` =
   allocate from the parent Run's wallet, `return!` = return the remainder, `escrow!` = move
   the remainder into an escrow account named by the savepoint id, `claim!` = move it into
   the claiming Run's wallet, once. Run worlds' savepoint sessions carry it; turn savepoints
   persist with `{:escrow? true}`.
3. **Resume through the law:** `resume!` = fork a Run world at the savepoint's snapshots
   (dvergr owns the handle) + `hydrate-into!`, running `continue-llm-run` for real: it
   re-enters the LLM loop at the recorded step in that world, as the Run its payload names
   (a new Run caused by the old one). The escrow carries the budget; `claim-resume!`'s once
   becomes the escrow's once.
4. **Model budget in the ledger:** a Run's `budget-dollars` becomes its wallet's microUSD
   allocation, charged as model calls account (`resource/consume!`), so it is conserved,
   escrowed and resumed with everything else. Closes the gap that a resumed Run starts with a
   fresh chat budget.
5. **One handler table:** dvergr's effect configuration and spindel's savepoint handlers are
   both "handlers in world state, inherited by forks". Keep dvergr's algebra (normalize,
   compose, narrowing) and store both under spindel's world-state conventions; a later spindel
   step may give handler tables the composition dvergr has.
6. **Experiments as scopes:** an experiment's Attempt worlds as one `world-scope` (quiescence,
   discard, descriptors for audit, the authority for their budgets).

Steps 1 and 2 are independent; 3 needs both; 4 needs 2; 5 and 6 are refactors without new
behaviour and come last.

## What does not change

SCI effects stay tail-resumptive handlers (doc/effects.md): SCI code is not CPS-transformed,
so its effects cannot be spindel effects. The effect *configuration* unifies (step 5); the
effect *mechanism* in the sandbox stays dvergr's.
