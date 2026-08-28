# Runs

A **Run** is one causally bounded execution by an actor in a Room. It is the
durable answer to: what started, what exact message caused it, who performed it,
what output/activity belongs to it, and how it ended?

The first implemented Run kind is `:agent-turn`. One such Run spans every model
round, tool call, same-thread steer, and model switch needed to handle one
trigger. A Run is not a Room, Thread, actor identity, model request, UI spinner,
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
| `:failed` | The turn failed or produced no usable output. |
| `:cancelled` | The executor acknowledged cancellation. |

`:cancelling` exists only in the live snapshot between request and
acknowledgement. A durable `:running` row after process loss means unfinished,
not proof that work is still live; reconciliation is a later feature.
Targeted cancellation uses a private per-Run token. It does not cancel or poison
the actor's reusable ChatContext, so a later Run by the same actor can proceed.

`:waiting` currently means this execution stopped at a resumable product
boundary; it is no longer live and therefore does not appear in `active-runs`.
A later message starts a new Run rather than resuming a parked continuation.
When durable continuations exist, we can refine this state without changing Run
identity or correlation.

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

This slice intentionally does not yet define generic effect receipts, approvals,
retry semantics, resource scopes, replay, or arbitrary SCI ProgramRefs. Those can
extend the stable Run identity without replacing it.
