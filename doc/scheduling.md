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
  your workspace repo, then schedule a call to them. The eval runs off the
  reactive engine, so a slow task never blocks other rooms.

## From an agent's sandbox

```clojure
(require '[dvergr.scheduler :as scheduler])

;; prompt schedules
(scheduler/every :day  "09:00" :huginn  "Run the morning intake sweep")
(scheduler/every :week :monday "14:00" :analyst "Weekly market review")
(scheduler/at "2026-08-01T09:00" :var "One-shot reminder")

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
| `{:every :week :on :mon}` | calendar cadence (day-of-week) |
| `{:every :hour :n 4}` | every 4 hours — `:n` multiplies a fixed unit |
| `{:interval-ms N}` / `{:every-ms N}` | raw fixed interval |
| `{:at "2026-08-01T09:00" :once true}` | one-shot at an instant |

`:n` is a **positive integer** with `:every :minute\|:hour\|:day\|:week` and
means "every N of that unit". It's for fixed intervals, so it can't combine with
a wall-clock `:at` or a calendar `:on`/`:on-day`.

Validation is strict — an **unknown spec key**, a non-positive `:n` or
`:interval-ms`, or an incompatible combination is **rejected loudly** rather
than silently producing the wrong cadence (a silent drop is how `{:every :hour
:n 4}` could once have become an unintended hourly schedule).

## Programmatic API

Outside the sandbox, `dvergr.scheduler.core` exposes `create-schedule!`,
`cancel-schedule!`, and `list-schedules` on a Room value — the same functions
the sandbox namespace wraps.
