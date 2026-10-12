# Scheduling

Each Room runs a reactive scheduler that tracks the daemon clock and fires the
room's due schedules on every minute boundary. A schedule is stored in the
room's own datahike store (`:schedule/*`), so it forks and merges with the room
and is dropped when the room is deleted.

Two kinds of scheduled task:

- **Prompt schedule** — posts a message to an agent as a normal turn. The agent
  runs it (with its tools and, usually, an LLM call). Use for "every morning,
  summarize my inbox".
- **`:code` schedule** — evals a Clojure form in the agent's sandbox on each
  fire (deterministic, no LLM turn) and posts the result as a room activity
  message. The **durable-pipeline primitive**: put functions in a namespace in
  your workspace repo, then schedule a call to them. The eval runs in a
  `future`, off the engine's drain thread, with a five-minute timeout, so a slow
  task does not hold up the scheduler or the engine while it runs.

What does run on the drain is the tick itself. The room's scheduler spin is
spawned on the root execution context and, each minute, queries the room's store
for due schedules, posts each prompt task (`disc/post!`, which appends it durably)
and transacts each schedule's new `:schedule/next-fire` inside its spin body
(`dvergr.rooms.scheduler/fire-due!`). Those store reads and writes are synchronous
there, so a slow store delays other work on the root context's drain for as long
as they take.

## From an agent's sandbox

```clojure
(require '[dvergr.scheduler :as scheduler])

;; prompt schedules
(scheduler/every :day  "09:00" :huginn  "Run the morning intake sweep")
(scheduler/every :week :monday "14:00" :analyst "Weekly market review")
(scheduler/at "2026-08-01T09:00" :var "One-shot reminder")
(scheduler/interval 900000 :ops "Check the queue")   ; every 15 minutes (ms)

;; code schedule — deterministic, no LLM
(scheduler/create
  {:agent-id :me
   :schedule {:every :day :at "07:00"}
   :code     "(require 'intake.news)(intake.news/scan!)"
   :description "morning news scan"})

(scheduler/list)              ; the room's schedules
(scheduler/cancel schedule-id)
```

## Cadence forms

| Form | Meaning |
|---|---|
| `{:every :day :at "07:00"}` | daily at a wall-clock time |
| `{:every :week :on :monday}` | calendar cadence (day-of-week) |
| `{:every :hour :at "00:15"}` | every hour at a minute past it (the hour of `:at` is ignored); a second, `"00:15:30"`, is kept but fires at the next minute's tick |
| `{:every :hour :n 4}` | every 4 hours — `:n` multiplies a fixed unit |
| `{:interval-ms N}` / `{:every-ms N}` | raw fixed interval |
| `{:at "2026-08-01T09:00" :once true}` | one-shot at an instant (a local datetime is read in `:tz`, default the system zone; `"…Z"` or `"…+02:00"` keeps its own) |

`:n` is a **positive integer** with `:every :minute\|:hour\|:day\|:week` and
means "every N of that unit". It's for fixed intervals, so it can't combine with
a wall-clock `:at` or a calendar `:on`/`:on-day`.

Validation is strict — an **unknown spec key**, a non-positive `:n` or
`:interval-ms`, or an incompatible combination is **rejected loudly** rather
than silently producing the wrong cadence (a silent drop is how `{:every :hour
:n 4}` could once have become an unintended hourly schedule).

## Minute ticks

The daemon clock (`dvergr.runtime.clock`) is one signal holding the current minute,
truncated to `:00` seconds, and every room's scheduler re-runs when it changes. A
tick fires each schedule whose `:schedule/next-fire` is at or before that minute.
So a schedule fires at the first minute boundary at or after its fire time: an
`:at` with seconds (stored as `:schedule/second`, so the computed fire
times keep it) fires at the next minute's tick, and an interval shorter than a
minute fires at most once a minute.

## From tools and MCP

- **Registry tools.** `schedule_create` (`agent_id`, `task`, `interval_minutes`, at
  least 1; an optional `description`), `schedule_list` and `schedule_cancel` act on
  the current room's schedules (`dvergr.scheduler.tools`, required at daemon start).
  They make prompt schedules at a fixed interval only. An agent has them when its
  `:tools` lists them; over MCP they are in the `code` toolset.
- **`catalog_deploy`** (MCP `bench` toolset). Deploys a room's workflow bundle: it
  creates a prompt schedule in that room (`every` `hour`, `day` or `week`, with an
  optional `at` `HH:MM` and `on` weekday) whose task is the bundle's task with
  `params` filled in, given to `agent`, a participant of the room. The room's REPL
  lists and cancels it like any other (`dvergr.scheduler/list`, `cancel`).

## Programmatic API

Outside the sandbox, `dvergr.scheduler.core` exposes `create-schedule!`,
`cancel-schedule!`, and `list-schedules` on a Room value — the same functions
the sandbox namespace wraps.
