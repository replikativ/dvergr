# Process model — deliberable long-running work

A `dvergr.agent.process.Process` wraps a spin so long-running work (a sandbox
eval, an LLM turn, a delegated /task) can be **paused at checkpoints,
inspected, redirected, and resumed** by a manager — the user pressing a
TUI key, or the agent itself looking at its own running processes.

This file is the design contract for `dvergr.agent.process`. The
implementation lives at `src/dvergr/agent/process.clj`.

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

- **Process** — a spin registered with a chat-ctx, with a status
  signal, a progress signal, and a directive deferred.
- **Checkpoint** — a call inside the process body that snapshots state
  and awaits a directive. The spin parks here.
- **Directive** — a manager-issued instruction (data) that determines
  whether the process resumes, aborts, or runs effects first.
- **Effect** — a side-effect on chat-ctx (extend budget, inject a
  message, swap a model) applied before the process resumes.

## Two directive types, effects as data

```clojure
{:type :continue
 :effects [{:op :extend-budget  :dollars 0.5}
           {:op :inject-message :role :system :content "Focus on …"}]}

{:type :abort :reason "user pressed esc"}
```

The spin doesn't know about effect types. `directive!` runs the
effects against chat-ctx *before* delivering the directive-deferred.
By the time `checkpoint!` returns, the world (chat-ctx signals,
messages, accounting ledger) is already in the new state; the spin
observes the diff on its next iteration.

### Effect ops shipped in v1

| Op | Implementation | Use |
|---|---|---|
| `:extend-budget` `{:dollars N}` | `swap!` on `:budget-signal` | "Keep going, here's more money" |
| `:inject-message` `{:role :content}` | `chat-ctx/add-message!` | "Refocus on …" |
| `:swap-model` `{:model id}` | mutates current opts (per-process atom) | Escalate or downshift mid-turn |
| `:set-temperature` `{:temp f}` | same | Reduce randomness |
| `:set-tool-cap` `{:n}` | per-process signal | "No more than N more tool calls" |

`run-effect!` is a multimethod (`[chat-ctx op]`). Unknown ops are
logged + skipped — never crash the process.

### Sugar

For ergonomic manager-side use, sugar shorthands desugar to
`:continue` + a single-effect bundle:

```clojure
(directive! pid {:type :extend-budget :dollars 0.5})
;; ≡ {:type :continue :effects [{:op :extend-budget :dollars 0.5}]}

(directive! pid {:type :refocus :hint "look at cache hits"})
;; ≡ {:type :continue :effects [{:op :inject-message :role :system :content "..."}]}
```

Power users compose multi-effect directives directly.

## Defaults

- **Soft-continue**: if no directive arrives within `:grace-ms` after a
  checkpoint, `checkpoint!` returns `{:type :continue :effects-applied []}`.
  The process keeps going. Matches how a human assistant works.
- **`:grace-ms 5000`** is the default. Per-process configurable.
- **First-wins** on the directive deferred: subsequent `directive!`
  calls are no-ops; the manager can detect rejection from the return
  value and re-issue if needed.

## Visibility

The chat-ctx grows a `:processes-signal` — `{process-id → snapshot}`.

- **TUI** reads it for the Processes pane.
- **SCI sandbox** exposes `(processes/list)`, `(processes/snapshot pid)`,
  `(processes/directive! pid dir)` so Vár can see and direct her own
  running processes from `clojure_eval`.
- **Vár's prompt** grows: "Check `(processes/list)` between turns; if a
  tool call is stuck, issue `:abort`."

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

`:completed` and `:aborted` are terminal. Process stays in the
registry briefly (~5min) for inspection, then GC'd.

## API

```clojure
;; Create + register
(->process chat-ctx
           {:id (random-uuid)            ;; optional, defaults to UUID
            :description "Vár turn"      ;; required, human-readable
            :grace-ms 5000               ;; optional
            :on-complete (fn [result])   ;; optional
            :on-abort (fn [reason])}     ;; optional
           (spin (… body using checkpoint! …)))
;; → Process

;; Inside the process body
(checkpoint! {:state {:elapsed-ms 30000 :budget-used 4000}
              :description "still computing"})
;; → {:type :continue :effects-applied [...]} on resume
;; → throws CancellationException on abort

;; Manager-side
(directive! chat-ctx process-id {:type :continue
                                  :effects [...]})
;; → :delivered | :already-decided

(snapshot process)             ;; latest progress-signal value
(list-processes chat-ctx)      ;; map of id → snapshot
```

## Implementation tiers

| | Slice | Touches |
|---|---|---|
| **1** | Core ns + record + checkpoint! + directive! + effect multimethod | `src/dvergr/agent/process.clj`, chat-ctx `:processes-signal` |
| **2** | Wire `clojure_eval` to use `->process` | `dvergr.tools/clojure_eval`, `sandbox/eval-code` |
| **3** | Wrap daemon turn loop in `->process`; converge Esc → `directive! :abort` | the LLM turn (`dvergr.discourse.llm`) + TUI key handler |
| **4** | Processes pane in TUI + key bindings | `dvergr.tui.app` |
| **5** | SCI exposure of `(processes/list :directive! :snapshot)` + Vár prompt | `dvergr.sandbox`, `resources/agents/var.md` |

Tier 1 ships the primitive; everything else is consumers.
