# One algebra for worlds, savepoints and budgets

Status: **in progress** (2026-09-27; agreed in direction: "unify our stack and FRP algebra"). Done: steps 1 (spindel#74, released in 0.1.67), 2 (#195), 3 (resume through `hydrate-into!`), 4 (#196). Step 5 is a renaming without a new guarantee and step 6 is deferred until inference runs over Attempts (below).

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
2. **dvergr authority:** `dvergr.resource.authority/authority`, a `PResourceAuthority` over the ledger,
   mapping a world to the Run that owns it (`:run-id` in the Room's meta): `grant!` =
   allocate from the parent Run's wallet, `return!` = return the remainder, `escrow!` = move
   the remainder into an escrow account named by the savepoint id, `claim!` = move it into
   the claiming Run's wallet, once. Run worlds' savepoint sessions carry it; turn savepoints
   persist with `{:escrow? true}`.
3. **Resume through the law (done, spindel 0.1.70):** `resume!` opens a Run world forked at
   the savepoint's snapshots (dvergr owns the handle and settles it), and
   `portable/hydrate-into!` makes that world the savepoint's continuation: seed and sequence,
   bookkeeping cleared, pinned components repinned, and `continue-llm-run` started as the
   session's computation, before the Run's first step. The Run's supervisor drives the loop
   from the recorded step; the continuation resolves with the resumed Run's result, so the
   session's end is the Run's end. The budget moves as step 2 says.
4. **Model budget in the ledger (done):** a Run allocated microdollars pays its model spend
   from its wallet: `account-usage!` charges each cost through `resource/*spend-wallet*`
   (bound by the LLM loop; unlike `*model-scope*` it restricts no provider), a cost beyond
   what is left takes the rest and exhausts the budget, and the Run's budget is at most its
   wallet. Model spend is thereby conserved, escrowed and resumed with everything else. A
   resumed Run without a wallet starts with its budget minus what the stopped Run's chat
   spent, not a fresh one.
5. **One handler table (assessed, not done):** dvergr's effect configuration and spindel's
   savepoint handlers are both "handlers in world state, inherited by forks", but one holds
   portable effect specs with a composition algebra and the other maps savepoint sites to
   functions; storing both under one convention renames keys and adds no guarantee. It
   becomes worth doing if spindel's handler tables gain dvergr's composition.
6. **Experiments as scopes (assessed 2026-09-28, deferred):** an experiment's Attempt worlds as
   one `world-scope`. Each thing a scope gives, the experiment path already guarantees:
   *budgets*: every cell's Run pays its model spend from a wallet of its candidate's budget
   (step 4), so an experiment is bounded by cells × per-Run budget (the preflight reports
   this cap); *discard and quiescence*: cells settle `:discard` after certification and
   cleanup groups join the teardown; *audit*: certified Attempts. What a scope adds is the
   algebra spindel's inference uses (SMC, MCTS), which pays when an algorithm runs over
   Attempts (resampling agent runs, branching at savepoints), not for a fixed grid of cells.
   The enabling change then: a scope whose fork and discard the embedder supplies (a Run
   world is a Room fork: store, registry and context, not a bare context fork), with its
   affine ownership, quiescence and authority unchanged.

Steps 1 and 2 are independent; 3 needs both; 4 needs 2; 5 and 6 are refactors without new
behaviour and come last.

## What does not change

SCI effects stay tail-resumptive handlers (doc/effects.md): SCI code is not CPS-transformed,
so its effects cannot be spindel effects. The effect *configuration* unifies (step 5); the
effect *mechanism* in the sandbox stays dvergr's.
