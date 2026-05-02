# CLAUDE.md - Dvergr Development Notes

## ⚠️ CRITICAL: Never use :reload-all

The `io.aviso.ansi` library has a transitive dependency that **hangs indefinitely** on `:reload-all`. Always use `:reload` instead:

```clojure
;; GOOD - use this
(require '[dvergr.core :as r] :reload)
(require '[dvergr.agent :as agent] :reload)

;; BAD - will hang forever, requires killing the REPL
(require '[dvergr.agent :as agent] :reload-all)  ; DON'T DO THIS
```

## Project Overview

Dvergr is a Clojure-based AI agent harness supporting multiple LLM providers (Anthropic, OpenAI, Fireworks). It provides:
- Multi-provider SSE streaming
- Tool calling with parallel execution
- Session management with EDN persistence
- Automatic retry with exponential backoff (429, 5xx errors)

## Quick Start

### Start REPL
```bash
clj -Sdeps '{:deps {nrepl/nrepl {:mvn/version "1.3.0"}}}' -M -m nrepl.cmdline --port 0
```

### Run an Agent Task
```clojure
(require '[dvergr.core :as r] :reload)

;; Simple task
(r/run "List all Clojure files and count them"
       :provider :fireworks
       :model "accounts/fireworks/models/qwen3-coder-480b-a35b-instruct"
       :max-turns 5)

;; With Anthropic
(r/run "Explain what core.clj does"
       :provider :anthropic
       :model "claude-sonnet-4-5-20250514")
```

### Testing

```clojure
;; Quick test
(require '[dvergr.core :as r] :reload)
(r/run "Say hello" :provider :fireworks :model "accounts/fireworks/models/qwen3-coder-480b-a35b-instruct")
```

## Recommended Models

### Fireworks.ai (tested, fast, cost-effective)

```clojure
;; Qwen3 Coder 480B - Best for code tasks
:model "accounts/fireworks/models/qwen3-coder-480b-a35b-instruct"

;; Kimi K2 Thinking - Advanced reasoning
:model "accounts/fireworks/models/kimi-k2-thinking"
```

## Architecture

```
dvergr.core     - Public API (run, resume, sessions)
dvergr.agent    - Agent loop, turn execution
dvergr.provider - LLM API calls, SSE streaming, retries
dvergr.tools    - Tool registry, execution
dvergr.session  - Session state, persistence (EDN files)
```

## Provider Support

| Provider | Streaming | Tools | Status |
|----------|-----------|-------|--------|
| Anthropic | Yes | Yes | Tested |
| OpenAI | Yes | Yes | Implemented |
| Fireworks | Yes | Yes | Tested |

## Error Handling

- Automatic retry (3 attempts) for: 429, 500, 502, 503, 504
- Exponential backoff with jitter
- Clear error messages in agent output

## Simulation System

Simulations are executable Clojure models (SCI) with dedicated Datahike DBs. They are NOT limited to digital twins — they can model competitors, markets, hypothetical scenarios, or any structured data.

### Architecture
```
dvergr.simulations.core    - Lifecycle (create!, run!, list, update-code!, delete!)
dvergr.simulations.db      - Dedicated Datahike DB provisioning per simulation
dvergr.simulations.sandbox - SCI environment (intake, dh, llm, spindel, NO file I/O)
```

### Agent Interaction
Agents interact with simulations programmatically via SCI `sim` namespace — **no tools**, just Clojure functions:
```clojure
(sim/create {:name "xtdb" :code source :maintainer :sentinel})
(sim/run "xtdb")
(sim/query "xtdb" '[:find ?e :where [?e :xtdb.signal/type]])
(sim/list)
(sim/update-code "xtdb" new-code)
```

### SCI Environment Inside Simulations
Simulation code runs in isolated SCI with:
- `dh/q`, `dh/transact!`, `dh/db` — simulation's OWN Datahike DB
- `intake.hn`, `intake.reddit`, `intake.web`, etc. — read-only external data
- `llm/call`, `llm/summarize` — cheap LLM calls
- `spin`, `await`, `spindel.comb/*` — reactive composition
- `calendar/*` — calendar queries

NOT available: file I/O, shell, main DB writes.

### Template
See `resources/simulations/template.clj` for skeleton simulation code.

## Future Integration

See **DESIGN.md** for full architecture document.

Planned integration with:
- **SCI** for sandboxed REPL isolation (each session gets isolated ctx)
- **Datahike** for REPL history storage and querying
- **Yggdrasil** for CoW branching (fork/merge sessions)
- **Scriptum** for versioned source code + fulltext search
- **Beichte** for purity analysis enabling JIT compilation

## Key Design Decisions

1. **SCI by default** - All evals go through SCI for isolation
2. **State in datahike** - REPL history, conversations, ctx snapshots
3. **Branches = Sessions** - Each session maps to a yggdrasil branch
4. **Pure functions can be promoted** - JIT from SCI to JVM when safe

## Spindel Integration (../spindel)

Spindel is the reactive runtime powering agent composition. Key concepts:

### Core Abstractions

**CRITICAL DESIGN PRINCIPLE**: Spindel is an **FRP (Functional Reactive Programming) system** for composable reactive computations, NOT a future/promise library.

- **@deref is ONLY for REPL convenience** - DO NOT use `@spin` inside other spins!
- **Compose spins using `await` inside `spin` macro** - builds reactive dependency graphs
- **Spins are cached and automatically re-execute** when dependencies change
- **The goal is composition**, not one-off blocking calls that lose reactivity

```clojure
;; CORRECT - Composable reactive pattern
(def result-spin
  (spin
    (let [a (await spin-a)  ; Non-blocking suspension
          b (await spin-b)]
      (+ a b))))  ; Re-executes when spin-a or spin-b change

;; WRONG - Breaks composition
(defn broken []
  (let [a @spin-a  ; Blocking deref - loses reactivity!
        b @spin-b]
    (+ a b)))

;; REPL/Test boundary - @deref is OK here
(binding [rtc/*execution-context* runtime]
  @result-spin)  ; Fine - this is the REPL boundary

;; Spin - reactive computation unit (like React hooks or SolidJS signals)
(ns ... (:require [is.simm.spindel.spin.cps :refer [spin]]
                  [is.simm.spindel.effects.await :refer [await]]
                  [is.simm.spindel.effects.track :refer [track]]
                  [is.simm.spindel.runtime.core :as rtc]
                  [is.simm.spindel.spin.combinators :as comb]))

;; Create a spin - automatically CPS-transformed
(spin
  (let [x (await some-other-spin)
        y (await another-spin)]
    (+ x y)))

;; Lower-level API (no CPS transformation)
(spin-core/make-spin
  (fn [resolve reject]
    ;; Manual CPS - call resolve/reject when done
    (resolve 42))
  :my-spin-id)

;; Dynamic bindings - CRITICAL for all spindel operations
(binding [rtc/*execution-context* runtime-ctx
          rtc/*spin-id* current-spin-id]
  ;; Spindel code here
  )
```

### Two Categories of Abstractions

**CRITICAL DISTINCTION**: Spindel has two different abstraction categories:

#### 1. Signals (External World Integration)
Signals are for integrating **external information** into the system as deltas. They represent the boundary with the outside world - UI events, sensor data, external API updates. The system adaptively re-runs computations when signals change.

```clojure
;; Signals = External world boundary
(def mouse-pos (sig/signal [0 0]))     ; UI input
(def config (sig/signal {:theme :dark})) ; External config
(def time-tick (sig/signal (now)))      ; Clock

;; track signals in spins for reactive re-execution
(spin
  (let [{:keys [x y]} (:new (track mouse-pos))]
    (render-cursor x y)))  ; Re-runs when mouse moves
```

**DO NOT use signals for internal agent coordination!**

#### 2. Sync Primitives (Internal Coordination)
Use these for coordination **between processes/agents** within the system:

| Primitive | Purpose | Pattern |
|-----------|---------|---------|
| `Deferred` | Single-assignment, one-time completion | Request-response, promises |
| `Mailbox` | FIFO queue, producer-consumer | Message passing, task queues |
| `Pub/Sub` | Fan-out (mult) and topic routing | Event distribution |
| `Semaphore` | Permit-based resource limiting | Rate limiting, connection pools |
| `gen-aseq` | Lazy async sequences with yield | Streaming, generators |

```clojure
;; Sync primitives = Internal coordination
(def inbox (sync/mailbox))      ; Agent receives tasks
(def result-d (sync/deferred))  ; One-time completion signal
(def rate-limit (sem/semaphore 10))  ; Max 10 concurrent

;; Agent loop using mailbox
(spin
  (loop []
    (let [task (await inbox)]  ; Wait for task
      (process task)
      (recur))))
```

### Key Namespaces

| Namespace | Purpose | Key Functions |
|-----------|---------|---------------|
| `is.simm.spindel.spin.cps` | Spin macro with CPS | `spin`, `effect` |
| `is.simm.spindel.spin.core` | Core Spin type | `make-spin` |
| `is.simm.spindel.effects.await` | Suspend until complete | `await` |
| `is.simm.spindel.effects.track` | Reactive signal tracking | `track` |
| `is.simm.spindel.spin.combinators` | Composition | `parallel`, `race`, `timeout`, `sleep` |
| `is.simm.spindel.spin.sync` | Sync primitives | `deferred`, `mailbox`, `deliver!`, `post!` |
| `is.simm.spindel.pubsub.core` | Pub/Sub | `mult`, `tap`, `pub`, `sub` |
| `is.simm.spindel.state.semaphore` | Rate limiting | `semaphore`, `acquire`, `release`, `holding` |
| `is.simm.spindel.sequence.core` | Async sequences | `gen-aseq`, `yield` |
| `is.simm.spindel.runtime.core` | Execution context | `create-runtime`, `fork-runtime`, `*execution-context*` |
| `is.simm.spindel.state.signal` | External signals | `signal` (for world boundary only!) |

### Effects (only work inside `spin` macro)

```clojure
;; await - suspend until spin/deferred completes
(spin
  (let [result (await other-spin)]
    (* result 2)))

;; track - reactive dependency tracking
(spin
  (let [value (:new (track my-signal))]  ; Re-runs when my-signal changes
    (* value 2)))
```

### Combinators

```clojure
;; parallel - run spins concurrently, collect all results
(def results-spin
  (comb/parallel spin-a spin-b spin-c))
;; => Returns spin yielding [result-a result-b result-c]

;; race - first to complete wins, others cancelled
(def winner-spin
  (comb/race fast-spin slow-spin))

;; timeout - race against deadline
(def timed-spin
  (comb/timeout my-spin 5000 :timed-out))

;; sleep - delay (returns spin that completes after ms)
(def delayed-spin
  (spin
    (await (comb/sleep 1000))
    (println "After 1 second")))
```

### Runtime Context

```clojure
;; Create runtime (required for all spindel operations)
(require '[is.simm.spindel.runtime.core :as rtc])

(def rt (rtc/create-runtime {:impl :atoms}))  ; or :stm

;; Bind runtime before using spins
(binding [rtc/*execution-context* rt]
  (def my-spin
    (spin (+ 1 2))))

;; Fork runtime (O(1) CoW) - for agent sub-contexts
(binding [rtc/*execution-context* rt]
  (def forked-rt (rtc/fork-runtime))

  (binding [rtc/*execution-context* forked-rt]
    ;; Runs in forked context
    (def forked-spin (spin (+ 3 4)))))
```

### Blocking vs Non-Blocking

```clojure
;; BLOCKING - use @deref (NEVER inside spin!)
(binding [rtc/*execution-context* rt]
  (def my-spin (spin (+ 1 2)))
  @my-spin)  ; => 3 (blocks until complete)

;; NON-BLOCKING - use await inside spin
(binding [rtc/*execution-context* rt]
  (def consumer-spin
    (spin
      (let [result (await my-spin)]  ; Suspends, doesn't block
        (* result 2)))))

;; CPS interface (for manual control)
(my-spin
  (fn [value] (println "Resolved:" value))
  (fn [error] (println "Rejected:" error)))
```

### Common Patterns

```clojure
;; Sequential composition (natural let bindings)
(spin
  (let [a (await spin-a)
        b (await spin-b)]
    (+ a b)))

;; Parallel composition
(spin
  (let [[a b c] (await (comb/parallel spin-a spin-b spin-c))]
    (+ a b c)))

;; Conditional logic
(spin
  (let [result (await check-spin)]
    (if (> result 10)
      (await big-spin)
      (await small-spin))))

;; Forking for isolation
(binding [rtc/*execution-context* parent-rt]
  (let [child-rt (rtc/fork-runtime)]
    (binding [rtc/*execution-context* child-rt]
      ;; Isolated execution
      (def isolated-spin (spin ...)))))
```

### Critical Rules

1. **Spindel is FRP, not futures** - Compose spins with `await` inside `spin`, don't break the chain with `@deref`
2. **@deref ONLY at REPL/test boundary** - Never `@deref` inside `spin` - use `await` instead
3. **Always bind `*execution-context*`** before creating spins
4. **Effects only work in `spin` macro** - `await`, `track` are CPS-transformed
5. **Parallel needs execution context** - combinator functions access `rtc/*execution-context*`
6. **Forking is O(1) CoW** - cheap to create isolated sub-contexts
7. **Build reactive graphs** - The goal is composition, not one-off blocking calls
8. **Signals for external world, sync primitives for internal** - Don't use signals for agent coordination

## Multiagent System Design

### Agent as Reactive Process

An agent is a **long-lived reactive process**, not a function you call:

```clojure
(defrecord AgentProcess
  [id           ; Unique identifier
   inbox        ; Mailbox - receives tasks/messages
   outbox       ; Mailbox - emits results/thoughts (not Signal!)
   control      ; Deferred - for pause/stop commands
   config])     ; provider, model, system-prompt, tools

;; Agent process loop
(defn agent-loop [agent]
  (spin
    (loop []
      (let [;; Race: wait for task OR control command
            result (await (comb/race
                           (spin (await (:inbox agent)))
                           (spin (await (:control agent)))))]
        (cond
          ;; Control command
          (= :stop (:cmd result)) nil  ; Exit
          (= :pause (:cmd result))
          (do (await (:resume-d result))  ; Wait for resume
              (recur))

          ;; Task received
          :else
          (let [response (await (think agent result))]
            ((:outbox agent) response)  ; Post to outbox
            (recur)))))))
```

### Multiagent Patterns

#### Pipeline (Sequential)
```clojure
(defn pipeline [& agents]
  "A's output feeds B's input feeds C's input..."
  (doseq [[upstream downstream] (partition 2 1 agents)]
    (spin
      (loop []
        (let [msg (await (:outbox upstream))]
          ((:inbox downstream) msg)
          (recur))))))

;; Usage: research -> implement -> review
(pipeline researcher coder reviewer)
((:inbox researcher) {:task "Build JWT auth"})
```

#### Fan-out (Parallel)
```clojure
(defn fan-out [agents task]
  "Send same task to all agents, collect results"
  (spin
    (doseq [a agents] ((:inbox a) task))
    (await (comb/parallel
             (map #(spin (await (:outbox %))) agents)))))

;; Usage: 3 coders solve same problem
(def solutions (await (fan-out [coder-1 coder-2 coder-3] problem)))
```

#### Race (First Wins)
```clojure
(defn race-solve [agents task]
  "First agent to complete wins"
  (spin
    (doseq [a agents] ((:inbox a) task))
    (await (comb/race
             (map #(spin {:agent % :result (await (:outbox %))}) agents)))))
```

#### Debate (Adversarial)
```clojure
(defn debate [agent-a agent-b judge topic max-rounds]
  (spin
    (loop [round 0 history []]
      (if (>= round max-rounds)
        {:timeout true :history history}
        (do
          ;; Both argue
          ((:inbox agent-a) {:argue topic :history history})
          ((:inbox agent-b) {:counter topic :history history})
          (let [[arg-a arg-b] (await (comb/parallel
                                       (spin (await (:outbox agent-a)))
                                       (spin (await (:outbox agent-b)))))]
            ;; Judge evaluates
            ((:inbox judge) {:judge [arg-a arg-b]})
            (let [verdict (await (:outbox judge))]
              (if (:decided verdict)
                verdict
                (recur (inc round) (conj history {:a arg-a :b arg-b}))))))))))
```

#### Manager-Worker (Hierarchical)
```clojure
(defn supervisor [manager workers]
  "Manager delegates tasks, monitors progress"
  (spin
    (loop []
      (let [decision (await (:outbox manager))]
        (case (:action decision)
          :delegate
          (let [worker (nth workers (:worker-id decision))]
            ((:inbox worker) (:task decision))
            (recur))

          :broadcast
          (do (doseq [w workers] ((:inbox w) (:msg decision)))
              (recur))

          :collect
          (let [results (await (comb/parallel
                                 (map #(spin (await (:outbox %))) workers)))]
            ((:inbox manager) {:collected results})
            (recur))

          :done decision)))))
```

#### Request-Response (RPC-style)
```clojure
(defn request [agent message timeout-ms]
  "Send request, wait for response with timeout"
  (let [response-d (sync/deferred)]
    ((:inbox agent) (assoc message :reply-to response-d))
    (comb/timeout
      (spin (await response-d))
      timeout-ms
      {:error :timeout})))

;; Agent handles reply-to
(spin
  (let [task (await inbox)]
    (let [result (process task)]
      (when-let [reply-to (:reply-to task)]
        (sync/deliver! reply-to result)))))
```

### Agent Turn as Async Spin

The LLM call + tool execution should be a spin for proper composition:

```clojure
(defn think
  "Single agent turn - returns a spin"
  [agent task]
  (let [d (sync/deferred)
        ctx (:execution-context agent)]
    ;; Run blocking LLM work on thread pool
    (future
      (try
        (let [result (do-llm-call-and-tools agent task)]
          (binding [rtc/*execution-context* ctx]
            (sync/deliver! d result)))
        (catch Exception e
          (binding [rtc/*execution-context* ctx]
            (sync/deliver! d {:error e})))))
    ;; Return spin that awaits the deferred
    (binding [rtc/*execution-context* ctx]
      (spin (await d)))))
```

### Key Design Principles

1. **Agents are processes, not functions** - Long-lived, reactive, pausable
2. **Communication via mailboxes** - Not function calls or shared state
3. **Coordination via combinators** - `race`, `parallel`, `timeout` compose naturally
4. **Single execution context binding** - At process creation, not scattered everywhere
5. **Blocking I/O wrapped in deferred+future** - Bridge external world to FRP
