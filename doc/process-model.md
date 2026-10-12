# Process model — deliberable long-running work

A process (the record `dvergr.agent.process.Proc`, named so it does not collide with
`java.lang.Process`) wraps a body so long-running work (a sandbox
eval, an LLM turn, a delegated /task) can be **paused at checkpoints,
inspected, redirected, and resumed** by a manager — the user pressing a
TUI key, or the agent itself looking at its own running processes.

This file is the design contract for `dvergr.agent.process`. The
implementation lives at `src/dvergr/agent/process.clj`.

Status: built for `clojure_eval` (each eval is a process, soft checkpoint
after 30 s), for budget exhaustion (a tracked process that waits for a
decision while the turn ends), in the TUI's Processes view and in the
sandbox's `processes` namespace. Two effect ops are built
(`:extend-budget`, `:inject-message`). Not built: model, temperature and
tool-cap effects, wrapping the LLM turn in a process, and trimming
terminated processes from the registry. The sections below say which is
which.

## Why this exists

Two-state ("running or killed") is the wrong model for agentic work:

- A 60s timeout shouldn't necessarily kill — it should check in.
  ("Still useful? Here's what I have so far.")
- A worker over budget shouldn't necessarily fail — the manager might
  prefer to raise the budget.
- The agent itself, watching its own loop, should be able to redirect
  ("never mind, look at the cache table instead") without restarting.

Spindel already gives us first-class continuations via CPS. A
parked-and-resumable spin is the right unit; we just need a registry
plus a directive surface.

## Vocabulary

- **Process** — a body registered with a chat-ctx (run on a future by
  `->process`, or owned elsewhere and only tracked by `register!`), with a
  status signal, a progress signal, and a first-wins directive slot.
- **Checkpoint** — a call inside the process body that snapshots state
  and awaits a directive. The body's thread waits here.
- **Directive** — a manager-issued instruction (data) that determines
  whether the process resumes, aborts, or runs effects first.
- **Effect** — a side-effect on chat-ctx (extend budget, inject a
  message) applied before the process resumes.

## Two directive types, effects as data

```clojure
{:type :continue
 :effects [{:op :extend-budget  :dollars 0.5}
           {:op :inject-message :role :system :content "Focus on …"}]}

{:type :abort :reason "user pressed esc"}
```

The body doesn't know about effect types. `directive!` runs the
effects against chat-ctx *before* delivering the directive (a latch the body waits on).
By the time `checkpoint!` returns, the world (chat-ctx signals,
messages, accounting ledger) is already in the new state; the body
observes the diff on its next iteration.

### Effect ops

| Op | Implementation | Use |
|---|---|---|
| `:extend-budget` `{:dollars N}` | `swap!` on the chat-ctx's `:budget-signal` | "Keep going, here's more money" |
| `:inject-message` `{:role :content}` | `chat-ctx/add-message!` | "Refocus on …" |

Not built: `:swap-model`, `:set-temperature` and `:set-tool-cap`. The
`:swap-model` and `:set-temperature` sugar types below still desugar to
those ops, which then land on the `:default` method and are skipped.

`run-effect!` is a multimethod (`[chat-ctx op]`). Unknown ops are
logged + skipped — never crash the process.

### Sugar

For ergonomic manager-side use, sugar shorthands desugar to
`:continue` + a single-effect bundle:

```clojure
(directive! chat-ctx pid {:type :extend-budget :dollars 0.5})
;; ≡ {:type :continue :effects [{:op :extend-budget :dollars 0.5}]}

(directive! chat-ctx pid {:type :refocus :hint "look at cache hits"})
;; ≡ {:type :continue :effects [{:op :inject-message :role :system :content "..."}]}
```

Power users compose multi-effect directives directly.

## Defaults

- **Soft-continue**: if no directive arrives within `:grace-ms` after a
  checkpoint, `checkpoint!` returns `{:type :continue :effects-applied []}`.
  The process keeps going. Matches how a human assistant works.
- **`:grace-ms 5000`** is the default. Per-process configurable.
- **First-wins** on the directive: subsequent `directive!`
  calls are no-ops; the manager can detect rejection from the return
  value and re-issue if needed.

## Visibility

The registry is a map `{process-id → Proc}` in the chat-ctx's execution
context state, under `[:dvergr/processes]`; `list-processes` returns a
vector of snapshots.

- **TUI**: the Processes view (cycled to from the tree view) lists the
  running and awaiting processes of the selected chat; `a` aborts the
  focused one, `c` continues it, `e` extends its budget by $0.10.
- **SCI sandbox**: `(processes/list)`, `(processes/snapshot pid)`,
  `(processes/directive! pid dir)` (`ns-io/add-process-ns!`, installed with
  each chat's sandbox), so an agent can see and direct its own running processes from
  `clojure_eval`. A directive that extends a budget is refused there: a
  budget is extended by whoever gave it.
- **Vár's prompt** (`resources/agents/var.md`) tells her about
  `(processes/list)`.

## Lifecycle states

```
        :running ──── checkpoint! ───► :awaiting-decision
            ▲                                 │
            │   directive!                    │
            │   (effects ran, returns)        │
            └─────────────────────────────────┘
                                              │
                                              │   :abort
                                              ▼
                                         :aborted

            :running ──── body returns ──► :completed
```

`:completed` and `:aborted` are terminal. Nothing removes a terminated
process from the registry (not built: the trim); it stays until its
chat-ctx goes. The TUI shows a terminated process for 30 s, then hides it.

## API

```clojure
;; Create + register
(->process chat-ctx
           {:id (random-uuid)            ;; optional, defaults to UUID
            :description "Vár turn"      ;; required, human-readable
            :grace-ms 5000               ;; optional
            :on-complete (fn [result])   ;; optional
            :on-abort (fn [reason])}     ;; optional
           (fn [] … body using checkpoint! …))   ;; 0-arity fn, run on a future
;; → Process

;; Tracking only: no body thread; the work owner polls (aborted? proc)
(register! chat-ctx {:description "…"})

;; Inside the process body
(checkpoint! {:state {:elapsed-ms 30000 :budget-used 4000}
              :description "still computing"})
;; → {:type :continue :effects-applied [...]} on resume
;; → throws CancellationException on abort

;; Manager-side
(directive! chat-ctx process-id {:type :continue
                                  :effects [...]})
;; → :delivered | :already-decided | :no-such-process

(snapshot process)             ;; latest progress-signal value
(list-processes chat-ctx)      ;; vector of snapshots
```

## Implementation tiers

| | Slice | Status |
|---|---|---|
| **1** | Core ns + record + checkpoint! + directive! + effect multimethod | built (`src/dvergr/agent/process.clj`; the registry in ctx state, not a `:processes-signal`) |
| **2** | Wire `clojure_eval` to use `->process` | built (`dvergr.tools`, grace 30 s; an abort reaches `sandbox/eval-code` through its `:cancel?`) |
| **3** | Wrap the LLM turn in `->process`; Esc → `directive! :abort` | not built. Esc in the TUI cancels the room's turn through its Run (`daemon/cancel-room-turn!`). Budget exhaustion registers a tracked process (`budget-exhausted!`) that waits for a decision while the turn ends; it never blocks the drain |
| **4** | Processes view in TUI + key bindings | built (`dvergr.tui.app`) |
| **5** | SCI exposure of `(processes/list :directive! :snapshot)` + Vár prompt | built (`dvergr.sandbox.ns.io/add-process-ns!`, `resources/agents/var.md`) |

`checkpoint!` blocks the future thread that runs the process body; parking
the body as a spin without holding a thread is not built.
