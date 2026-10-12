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
whose cause is the old one; [run-resume.md](run-resume.md) describes it.

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
be resumed from its savepoint ([run-resume.md](run-resume.md)), and the sandbox
effect boundary keeps receipts and can answer effects from a recording
([effects.md](effects.md)). An ordinary Run's receipts are held in memory; an
evaluated Attempt stores its world's receipts as an effect-log artifact linked
from the Attempt (`dvergr.agent.evaluation`). Not built: a durable receipt log
for every Run, retrying a failed Run (a new
trigger or a resume starts a new Run), and replaying a Run's external effects.
These can extend the stable Run identity without replacing it.
