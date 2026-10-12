# Runs

A **Run** is one causally bounded execution by an actor in a Room. It is the
durable answer to: what started, what exact message caused it, who performed it,
what output/activity belongs to it, and how it ended?

The Run kind this page describes is `:agent-turn` (the default of
`run/start!`). One such Run spans every model round, tool call, same-thread
steer, and model switch needed to handle one trigger. Other subsystems admit
Runs of their own kinds: a hired agent task is `:agent-task`
(`dvergr.agent.program`), a job is a `:workflow` or `:experiment` Run of its
room (`dvergr.jobs`), and a conversation episode is an `:episode` Run of its
experiment room (`dvergr.agent.conversation`). A Run is not a Room, Thread, actor identity, model request, UI spinner,
or private copy of the transcript.

```text
trigger message
└── agent-turn Run
    ├── model round
    ├── tool activity
    ├── model round
    └── output message
```

## Durable shape

```clojure
{:run/id         #uuid "..."
 :run/kind       :agent-turn
 :run/room       :research
 :run/actor      :researcher
 :run/trigger    trigger-message-id
 :run/parent     parent-run-id       ; optional explicit spawning/containment
 :run/status     :running
 :run/created-at instant
 :run/started-at instant
 :run/updated-at instant
 :run/ended-at   instant}            ; terminal only
```

The trigger is the canonical causal and topical link. Its
`:message/thread-root-id` determines the Run's Thread; Run rows do not duplicate
thread identity. When an output message from one Run triggers another agent, its
typed `:message/run-id` remains available through the trigger as a causal edge;
it does not become `:run/parent`. Parent is reserved for explicit structural
spawning/containment, which stays meaningful across fork/join and multi-input
workflows.

Output and `:_activity` messages carry `:message/run-id`. Activity messages also
reply to the trigger and preserve its thread root. Clients can therefore query
by Thread, by Run, or combine both without chronological heuristics.

Admission is durability-first: a Run is neither executed nor published to live
subscribers unless its initial row is stored. Likewise, `:run/finished` is
published only after the terminal projection is durable. When a turn returns a
visible reply, the Room persists that correlated output before the Run becomes
`:completed`, so a finished event is a safe query frontier for clients.

Durable statuses currently are:

| Status | Meaning |
|---|---|
| `:running` | The execution was admitted and may have a live handle. |
| `:waiting` | It stopped at a resumable product boundary, currently budget exhaustion. |
| `:completed` | It ended successfully, with or without a visible reply. |
| `:failed` | The turn threw, its reply could not be posted, or its process stopped while it ran. |
| `:cancelled` | The executor acknowledged cancellation. |

A terminal or waiting row may carry `:run/reason` (and `:run/error` for a
failure). The reasons an `:agent-turn` Run ends with (`dvergr.discourse.llm`,
`dvergr.agent.run`):

| Status | `:run/reason` | When |
|---|---|---|
| `:completed` | none | The reply was posted, or the turn ended without one. |
| `:completed` | `:silent` | The reply opted for silence (`[SKIP]`, `[NO_REPLY]`, blank; `dvergr.discourse.enrichment/with-silence`). |
| `:completed` | `:withheld` | A wrapper dropped the reply without giving a reason. |
| `:completed` | another keyword | A wrapper dropped the reply and named its own reason (`withhold-reply!`). |
| `:waiting` | `:budget-exhausted`, `:attention-suspended` | The turn stopped at a resumable boundary. |
| `:failed` | `:error` | The turn or its handler threw. |
| `:failed` | `:reply-emission-failed` | Posting the reply failed. |
| `:failed` | `:orphaned` | The process stopped while the Run was running (see below). |
| `:cancelled` | `:cancel-requested` | The turn was cancelled (`cancel-run!`, `cancel-room-turn!`). |
| `:cancelled` | `:participant-left` | The agent left the room mid-turn. |

`:cancelling` exists only in the live snapshot between request and
acknowledgement. A durable `:running` row after process loss means unfinished,
not that work is still live: when the daemon loads its durable Rooms again,
before a Room admits work, `run/reconcile-orphaned-runs!` fails every `:running` Run no live
execution owns, with reason `:orphaned`. `:waiting` Runs are left as they are.
Targeted cancellation uses a private per-Run token. It does not cancel or poison
the actor's reusable ChatContext, so a later Run by the same actor can proceed.

`:waiting` currently means this execution stopped at a resumable product
boundary; it is no longer live and therefore does not appear in `active-runs`.
A later message starts a new Run. A stopped Run with a savepoint can be resumed
on request (`program/resume!`, the `run_resume` op): the resume is a new Run
whose cause is the old one ([Resuming a Run](#resuming-a-run)).

## How a turn ends

The handler of an LLM agent owns its turn's Run, but its reply passes through
wrappers it does not know (silence filters, reply gating). So the Run is
finished through a *reply obligation* (`dvergr.discourse`):

- `reply-obligation!` registers one for the running handler, with callbacks for
  a posted reply (`:on-emitted`), a failed post (`:on-failed`) and a dropped
  reply (`:on-dropped`, with a reason);
- `carry-obligation` marks the reply that settles it;
- `withhold-reply!` is what a wrapper calls when it drops a reply, so its reason
  (e.g. `:silent`) reaches the Run;
- `release-obligation!` takes it back when the handler finishes the Run itself.

The participant settles every obligation exactly once. One still open when the
handler ends is settled as `:withheld`, as `:handler-failed` when the handler
threw, and as `:abandoned` when the participant left; the table above shows the
Run status each becomes.

## Resuming a Run

A Run that was running when its process stopped is failed on the next start
(`run/reconcile-orphaned-runs!`, reason `:orphaned`). Its conversation is persisted (the
Run's chat in the control room store, `program/run-chat-id`), its world is a fork of the
room and its effects are receipted. `program/resume!` (and the `run_resume` op) continues
it, or any other stopped Run with a savepoint, as a new Run. It builds on spindel
savepoints (`effects.savepoint`, `savepoint.portable`; deps.edn has the spindel version
dvergr pins) and on the effect logs with idempotency classes ([effects.md](effects.md)).
The proposal and the decisions it was built from are in
[design/run-resume.md](design/run-resume.md).

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
  Run (terminal) with a savepoint and hires a new Run: its world forked at the savepoint's
  snapshot ids (`world/open!` `:snapshots`), its chat seeded with the old Run's persisted
  messages, its step count on from the savepoint, the old Run as its cause (`:run/caused-by`,
  recorded with its admission). The resume is atomic for the caller: `run/reserve-resume!`
  excludes a concurrent resume in the process without writing anything; the new Run's grant
  comes straight from the old Run's wallet; and only then, before it executes, the new Run
  claims the old one once (`run/claim-resume!`, `:run/resumed-by`). A resume that fails
  leaves the old Run with its wallet, resumable again; a successor that failed before it
  executed (admission, allocation or spawn) does not count as a resume. `run_detail` shows
  `resumable-from-step` and `resumed-by`.
- **Inputs.** The resumed Run continues the stopped Run's conversation, system prompt
  included, and records that prompt, the one it is sent, in its inputs id
  (`program/run-inputs`: prompt id, offered tools, resolved model), not the prompt a fresh
  Run would assemble now. An experiment resume compares this id with the one its candidate
  would get now (doc/benchmarks.md, "Resume and inputs ids").
- **Through spindel.** The world is a Run world of the room forked at the recorded
  snapshots (so registry, settlement and review are as for any Run), and
  `savepoint.portable/hydrate-into!` makes it the savepoint's continuation before the first
  step: `continue-llm-run` runs there and resolves with the resumed Run's result
  ([worlds-and-forks.md](worlds-and-forks.md)). The budget moves with dvergr's resource
  wallets (the old Run's remainder granted from its wallet to the new Run's, which returns
  what it leaves to the parent); spindel's escrow is for a planned handoff, and turn
  savepoints do not escrow.
- **Open.** Receipts of a running Run are not yet durable per step, so the step a Run stopped
  in is redone without checking its `:once` effects; `run_resume`'s doc says so. Automatic
  resume on start comes after on-request resume has been exercised. Protocol Runs (tau2) are
  not resumable yet.
- **Not proposed.** Replaying the effect log to reconstruct a live Run: a replay of external
  effects is not the same world. Replay stays what it is for: reproducing an Attempt and
  freezing a benchmark.

## Processes: checkpoints and directives

A process (the record `dvergr.agent.process.Proc`, named so it does not collide with
`java.lang.Process`) wraps long-running work so a manager (the user pressing a TUI key, or
the agent looking at its own running processes) can inspect it, and the work can wait at a
checkpoint for a decision: continue (after running effects), or abort. Two-state ("running
or killed") is the wrong model for agentic work: a timeout may better check in, and a worker
over budget may better get more budget than fail. A process is not a Run; it lives in the
chat-ctx of the agent whose work it tracks.

Built: each `clojure_eval` is a listed process (its body never calls `checkpoint!`, so the
grace period is unused, and an `:abort` directive does not stop it: `directive!` flips the
status only for a tracked-only process, and the eval polls only that status; cancelling the
enclosing turn does stop it); budget exhaustion registers a tracked process
(`budget-exhausted!`) that waits for a decision while the turn ends, and never blocks the
drain; the TUI's Processes view; the sandbox's `processes` namespace. Not built: model,
temperature and tool-cap effects, wrapping the LLM turn in a process (Esc in the TUI cancels
the room's turn through its Run, `cancel-room-turn!`), parking a checkpointed body as a spin
without holding a thread (`checkpoint!` blocks the future thread that runs the body), and
trimming terminated processes from the registry.

- **Process**: a body registered with a chat-ctx (run on a future by `->process`, or owned
  elsewhere and only tracked by `register!`), with a status, a progress snapshot and a
  first-wins directive slot.
- **Checkpoint**: a call inside the body that snapshots state and waits for a directive.
- **Directive**: data that says whether the process resumes, aborts, or runs effects first.
- **Effect**: a change to the chat-ctx (extend the budget, inject a message) applied before
  the process resumes.

```clojure
{:type :continue
 :effects [{:op :extend-budget  :dollars 0.5}
           {:op :inject-message :role :system :content "Focus on …"}]}

{:type :abort :reason "user pressed esc"}
```

`directive!` runs the effects against the chat-ctx *before* delivering the directive (a
latch the body waits on), so by the time `checkpoint!` returns the chat-ctx is already in the
new state. `run-effect!` is a multimethod (`[chat-ctx op]`); unknown ops are logged and
skipped, never crash the process.

| Op | Implementation | Use |
|---|---|---|
| `:extend-budget` `{:dollars N}` | `swap!` on the chat-ctx's `:budget-signal` | "Keep going, here's more money" |
| `:inject-message` `{:role :content}` | `chat-ctx/add-message!` | "Refocus on …" |

Sugar types desugar to `:continue` with one effect: `{:type :extend-budget :dollars 0.5}`,
`{:type :refocus :hint "…"}` (a system message). The `:swap-model` and `:set-temperature`
sugar types desugar to ops that land on the `:default` method and are skipped.

Defaults: if no directive arrives within `:grace-ms` (5000) after a checkpoint,
`checkpoint!` returns `{:type :continue :effects-applied []}` and the process keeps going.
The first directive wins; later `directive!` calls return `:already-decided`.

```
        :running ──── checkpoint! ───► :awaiting-decision
            ▲                                 │
            │   directive! (effects ran)      │
            └─────────────────────────────────┤
                                              │ :abort
                                              ▼
                                         :aborted
            :running ──── body returns ──► :completed
```

`:completed` and `:aborted` are terminal. Nothing removes a terminated process from the
registry; it stays until its chat-ctx goes. The TUI shows a terminated process for 30 s,
then hides it.

```clojure
;; create + register; the 0-arity body runs on a future
(->process chat-ctx {:description "Vár turn"          ; required
                     :id (random-uuid) :grace-ms 5000  ; optional
                     :on-complete (fn [result]) :on-abort (fn [reason])}
           (fn [] … (checkpoint! {:state {:elapsed-ms 30000} :description "still computing"}) …))
;; checkpoint! → {:type :continue :effects-applied [...]}, or throws CancellationException on abort

(register! chat-ctx {:description "…"})   ; tracking only: the owner polls (aborted? proc)

(directive! chat-ctx process-id {:type :continue :effects [...]})
;; → :delivered | :already-decided | :no-such-process
(snapshot process)          ; latest progress value
(list-processes chat-ctx)   ; vector of snapshots
```

The registry is a map `{process-id → Proc}` in the chat-ctx's execution context state, under
`[:dvergr/processes]`. Where processes are visible:

- **TUI**: the Processes view (cycled to from the tree view) lists the running and awaiting
  processes of the selected chat; `a` aborts the focused one, `c` continues it, `e` extends
  its budget by $0.10.
- **SCI sandbox**: `(processes/list)`, `(processes/snapshot pid)`,
  `(processes/directive! pid dir)` (`ns-io/add-process-ns!`, installed with each chat's
  sandbox), so an agent can see and direct its own running processes from `clojure_eval`. A
  directive that extends a budget is refused there: a budget is extended by whoever gave it.
- **Vár's prompt** (`resources/agents/var.md`) tells her about `(processes/list)`.

## REPL use

The most common operations are re-exported from `dvergr.core` and the REPL
client:

```clojure
(require '[dvergr.core :as d])

(d/active-runs)                 ; every live Run
(d/active-runs :research)       ; one runtime Room id
(d/runs room)                   ; recent durable Runs, newest first
(d/runs room {:actor :researcher :status :failed :limit 20})
(d/run room run-id)             ; one durable Run
(d/cancel-run! run-id)          ; targeted cooperative cancellation
```

Lifecycle subscription emits one atomic initial frontier followed by changes:

```clojure
(d/watch-runs!
 ::ui
 (fn [{:keys [type run runs]}]
   (case type
     :runs/snapshot       (render-all! runs)
     :run/started         (render! run)
     :run/cancel-requested (render! run)
     :run/finished        (render! run))))

(d/unwatch-runs! ::ui)
```

Snapshots and events contain only serializable Run fields. The live ChatContext,
per-Run cancellation token, Spindel context, provider stream, and SCI context
remain process-local.

The existing room-wide surface remains compatible:

```clojure
(dvergr.agent.turn/room-turn-running? :research)
(dvergr.agent.turn/cancel-room-turn! :research)
```

It is now a projection over active Runs; room-wide cancel delegates to targeted
cancellation for every Run in that Room.

## UI projection

Simmis and other clients can build active/recent Run cards directly:

```text
researcher · running · 2m 14s
8 correlated activity messages
[Cancel] [Details]
```

Use the live subscription for immediate status and the room store for durable
history. Join `:message/run-id` to `:run/id` for raw activity/output, and join
`:run/trigger` to `:message/id` for Thread membership. Do not infer ownership
from timestamps or assume the final reply is the completion record.

Some neighbouring pieces exist beside the Run row rather than in it: each Run
has its own conserved wallet (`dvergr.resource/run-wallet-id`), a stopped Run can
be resumed from its savepoint ([Resuming a Run](#resuming-a-run)), and the sandbox
effect boundary keeps receipts and can answer effects from a recording
([effects.md](effects.md)). An ordinary Run's receipts are held in memory; an
evaluated Attempt stores its world's receipts as an effect-log artifact linked
from the Attempt (`dvergr.agent.evaluation`). Not built: a durable receipt log
for every Run, retrying a failed Run (a new
trigger or a resume starts a new Run), and replaying a Run's external effects.
These can extend the stable Run identity without replacing it.
