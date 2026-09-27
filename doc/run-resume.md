# Resuming Runs from savepoints

Status: **implemented** (2026-09-27), as below; see *As built* for where it differs from the proposal. Builds on spindel savepoints (spindel#56:
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

## Decisions (agreed 2026-09-27: each recommendation)

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

## As built

- **Savepoints.** After every completed model step the LLM loop publishes
  `(savepoint :conversation/turn {:run :step :task :agent :control-room} {:resume
  `continue-llm-run :args [run-id step]})` in the Run's work world (`turn-savepoint!`). The
  work world has a savepoint session (`install-turn-savepoints!`; a nested Run's world
  inherits its parent's and only installs the handler), closed with the Run. The handler
  persists the portable form (`savepoint.portable/persist`) onto the Run
  (`run/record-savepoint!`, `:run/savepoint` as EDN) and continues at once; a savepoint that
  cannot be persisted costs resumability, never progress. Only LLM Runs whose control room
  has a store get the handler.
- **Resume.** `program/resume!` (MCP `run_resume {room run}`, admin toolset) takes a stopped
  Run (terminal) with a savepoint, claims it once (`run/claim-resume!`, `:run/resumed-by`),
  and hires a new Run: its world forked at the savepoint's snapshot ids (`world/open!`
  `:snapshots`), its chat seeded with the old Run's persisted messages, its step count on from
  the savepoint, the old Run as its cause (`:run/caused-by`). `run_detail` shows
  `resumable-from-step` and `resumed-by`.
- **Differences from the proposal.** The world is a Run world of the room forked at the
  recorded snapshots, not `hydrate!`'s fork of a session root: that keeps registry,
  settlement and review as for any Run (`continue-llm-run` is the portable name, and
  `resume!` the operation). The budget moves with dvergr's own resource wallets (the old
  Run's remainder returned to its parent and granted to the new Run), which is where a Run's
  budget lives, rather than spindel's escrow authority.
- **Open.** Receipts of a running Run are not yet durable per step, so the step a Run stopped
  in is redone without checking its `:once` effects; `run_resume`'s doc says so. Automatic
  resume on start comes after on-request resume has been exercised. Protocol Runs (tau2) are
  not resumable yet.
- **spindel:** `persist` (0.1.54) dereferences the session's scope even without escrow, so it
  throws on a world without a savepoint session (`@(:scope (sp/session world))`); a
  `some->` there would let session-less worlds persist.
