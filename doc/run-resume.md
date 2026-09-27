# Resuming Runs from savepoints

Status: **design, for decision** (2026-09-27). Builds on spindel savepoints (spindel#56:
`effects.savepoint`, `savepoint.portable`, shipped in 0.1.53; dvergr pins 0.1.54) and on the
effect logs with idempotency classes (doc/effects.md).

## Today

A Run that was running when its process stopped is failed on the next start
(`run/reconcile-orphaned-runs!`, reason `:orphaned`). Its conversation is persisted (the
Run's chat in the control room store, `program/run-chat-id`), its world is a fork of the
room, and its effects are receipted, but nothing continues it.

## Proposal

1. **Declare the turn gaps.** The LLM program's loop publishes a savepoint between model
   steps, after the step's tool results are recorded:

   ```clojure
   (savepoint :conversation/turn {:run run-id :step k}
              {:resume `dvergr.agent.program/continue-llm-run :args [run-id k]
               :state [[:dvergr/effects] ...]})
   ```

   With no handler it costs a map and a lookup; nothing changes for existing Runs.

2. **Persist at each gap.** A handler installed for Runs that ask to be resumable persists
   the portable form (`savepoint.portable/persist`: the named function and arguments, the
   declared state, the Yggdrasil snapshot ids of the world's systems) on the Run record
   (`:run/savepoint`) and resumes at once. The content hash is a prefix identity: the same
   across processes, usable for benchmark checkpoints and rollout records too.

3. **Resume instead of failing.** On start, an orphaned Run with a `:run/savepoint` is
   hydrated (`hydrate!`: a fork of the host world pinned at the recorded snapshots, the
   declared state written) and `continue-llm-run` rebuilds the chat from its persisted
   messages and continues the loop at step `k`. Without a savepoint it fails as today.

4. **The step that was cut off.** Effects after the last savepoint belong to a step that did
   not finish. The step is redone from the savepoint. Its receipts say what already happened:
   `:idempotent` effects are simply redone; a `:once` effect (a post, a commit, a shell
   command) that the receipts show as performed makes the Run `:waiting` for a decision
   instead of continuing. That needs the receipts of a running Run to be durable per step,
   not only at the Attempt's end (a change to `effect-log`).

## Decisions needed

1. **World.** The loop runs in the control room's context; the Run's files live in its work
   world (a fork). The savepoint must be published in the work world so `persist` records
   its snapshots. Recommendation: run the loop's model steps under a savepoint session
   opened on the work world (`savepoint/open!`), the control context keeping only the Run's
   records.
2. **When to resume.** Automatically on start, or on request (an MCP op `run_resume {room
   run}` and a flag on the Run). Recommendation: on request first, automatic once it has
   been exercised.
3. **Wallet.** `persist {:escrow? true}` moves the Run's remaining budget into an escrow the
   hydration claims once (conserving, so the Run cannot be resumed twice), or hydrate
   unfunded. Recommendation: escrow, since a Run's budget is conserved everywhere else.
4. **Scope.** LLM programs first; protocol Runs (tau2 conversations) have a driver with its
   own state and come later.

## Not proposed

Replaying the effect log to reconstruct a live Run: DSec (arXiv:2609.22978) abandoned this
for preemption recovery, and a replay of external effects is not the same world. Replay
stays what it is for: reproducing an Attempt and freezing a benchmark.
