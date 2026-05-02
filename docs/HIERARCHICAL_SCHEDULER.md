# Hierarchical Scheduler for Agent-Based Modeling

## Overview

Spindel's scheduler has been extended with **hierarchy-aware scheduling** to support agent-based modeling (ABM) at scale. This enables proper integration with frameworks like Agents.jl, Mesa, and economic simulations like Firms.jl.

## Key Insight: Scheduled vs Reactive

ABM frameworks use **scheduled activation**, not reactive propagation:

```clojure
;; ❌ REACTIVE (FRP) - immediate propagation
;; State change → cascade of updates → potential thrashing
(def worker-firm-sig
  (spin
    (let [firm (track firm-signal)]
      (update-worker-state! firm))))

;; ✅ SCHEDULED (ABM) - controlled activation
;; Scheduler decides when agents activate → agents query state
(defn worker-step [worker model time]
  (let [firm (get-firm model (:firm-id worker))]  ; Pull-based query
    (decide-action worker firm)))
```

**Why scheduled activation matters:**
1. **Prevents reactive thrashing** - 10k agents don't all update when one firm changes
2. **Deterministic execution** - explicit ordering via priority queue
3. **Performance control** - batch processing, spatial locality
4. **Matches ABM semantics** - agents activate at discrete time steps

## Architecture

### Hierarchy Levels

```
ExecutionContext (Spindel)
└── HierarchicalScheduler
    ├── Societies (staged coordination)
    │   └── Groups (batch activation)
    │       └── Agents (individual entities)
    │           └── Spins (reactive computations)
    └── Regions (spatial organization)
        └── Agents (locality-aware)
```

**Entity types and their activation semantics:**

| Entity | Activation | Use Case |
|--------|------------|----------|
| **Agent** | Scheduled step function | Individual workers, sheep, cells |
| **Group** | Batch (sequential/parallel/random) | Firms, herds, spatial regions |
| **Society** | Staged (decide → move → interact) | Economy, ecosystem, organization |
| **Region** | Spatial locality | Grid cells, neighborhoods |

### Priority-Based Scheduling

Events are ordered by `(time, priority, entity-id)`:

**Priority levels:**
1. **Societies** - Coordinate first (priority 1)
2. **Groups/Regions** - Batch activation (priority 2)
3. **Agents** - Individual last (priority 3)

This ensures top-down coordination: societies coordinate groups, groups batch-activate agents.

### Integration with Spindel

The `HierarchicalScheduler` **wraps** spindel's `ExecutionContext`, not replaces it:

```clojure
;; Create execution context
(def ctx (rtc/create-runtime {:impl :atoms}))

;; Wrap with hierarchical scheduler
(def scheduler (create-hierarchical-scheduler
                 :exec-ctx ctx
                 :strategy :staged))

;; Both interfaces work
(binding [rtc/*execution-context* ctx]
  ;; FRP-style spins work
  (def my-spin (spin (await some-signal)))

  ;; ABM-style scheduling works
  (hsched/register-agent! scheduler worker-1)
  (hsched/step-simulation! scheduler model))
```

**Key benefit:** Process-based agents (LLM) and simulation agents (ABM) can coexist.

## Scheduling Strategies

### 1. Discrete-Time (Default)

All entities activate at each time step:

```clojure
(def scheduler (create-hierarchical-scheduler
                 :exec-ctx ctx
                 :strategy :discrete-time
                 :time-step 1.0))

;; Run for 100 steps
@(discrete-time-sim scheduler model 100)
```

**Execution order per step:**
1. Societies (priority 1)
2. Groups/Regions (priority 2)
3. Agents (priority 3)

### 2. Event-Queue (Gillespie-style)

Continuous time with event-based activation:

```clojure
(def scheduler (create-hierarchical-scheduler
                 :exec-ctx ctx
                 :strategy :event-queue))

;; Agents schedule their next activation
(defn worker-step [worker model time]
  (do-work worker)
  ;; Schedule next activation based on propensity
  (hsched/schedule-at! worker scheduler
                       (+ time (sample-exponential (:rate worker)))))

;; Run until queue empty or max-time
@(event-driven-sim scheduler model 1000.0)
```

### 3. Staged Activation

All agents execute in phases:

```clojure
(def scheduler (create-hierarchical-scheduler
                 :exec-ctx ctx
                 :strategy :staged
                 :time-step 1.0))

;; Agents have stage-specific functions
(defrecord Agent [... metadata]
  ISchedulable
  (activate! [this model time]
    (let [stage (current-stage scheduler)]
      ((get-in metadata [:stage-fns stage]) this model time))))

;; Run with stages: decide → move → interact
@(staged-sim scheduler model 100 [:decide :move :interact])
```

**Execution per step:**
1. All agents execute `:decide` phase
2. All agents execute `:move` phase
3. All agents execute `:interact` phase

## Comparison with ABM Frameworks

### Mesa (Python)

```python
# Mesa pattern
class Worker(Agent):
    def step(self):
        self.decide()
        self.move()

model.schedule.step()  # Activate all agents
```

**Equivalent in dvergr:**
```clojure
(defn worker-step [worker model time]
  (decide worker model)
  (move worker model))

(hsched/step-simulation! scheduler model)
```

### Agents.jl (Julia)

```julia
# Agents.jl pattern
agent_step!(agent, model) = begin
    decide!(agent, model)
    move_agent!(agent, model)
end

Agents.step!(model, agent_step!, 1)
```

**Equivalent in dvergr:**
```clojure
(defn agent-step! [agent model time]
  (decide! agent model)
  (move-agent! agent model))

(hsched/discrete-time-sim scheduler model 1)
```

### Firms.jl (Economic Simulation)

```julia
# Firms.jl pattern (pull-based queries)
function worker_step!(worker, model)
    current_util = utility(worker, worker.employer)
    friend_utils = [utility(worker, friend.employer) for friend in worker.friends]
    best_employer = argmax(employer -> utility(worker, employer), ...)
    if best_employer != worker.employer
        switch!(worker, best_employer)
    end
end
```

**Equivalent in dvergr:**
```clojure
(defn worker-step [worker model time]
  (let [current-util (utility worker (:firm-id @(:state-a worker)))
        friend-utils (map #(utility worker (get-firm %)) (:friends worker))
        best-firm (apply max-key #(utility worker %) all-firms)]
    (when (> best-util current-util)
      (switch-firm! worker best-firm))))
```

**Key similarity:** Pull-based queries, not push/reactive.

## Performance Patterns

### 1. Batch Activation

Groups activate agents in batches for cache locality:

```clojure
(defrecord Group [... batch-size]
  ISchedulable
  (activate! [this model time]
    ;; Process agents in batches of 100
    (doseq [batch (partition-all 100 @agents-a)]
      (doseq [agent batch]
        (activate! agent model time)))))
```

### 2. Parallel Execution

Large groups activate agents in parallel:

```clojure
(defrecord Group [... parallel-threshold]
  ISchedulable
  (activate! [this model time]
    (if (> (count @agents-a) parallel-threshold)
      ;; Parallel activation for large groups
      (await (comb/parallel
               (map #(spin (activate! % model time)) @agents-a)))
      ;; Sequential for small groups (less overhead)
      (doseq [agent @agents-a]
        (activate! agent model time)))))
```

### 3. Spatial Locality

Regions activate nearby agents together:

```clojure
(defrecord Region [... neighbors]
  ISchedulable
  (activate! [this model time]
    ;; Activate agents in this region
    (doseq [agent @agents-a]
      (activate! agent model time))
    ;; Propagate to neighbors (wavefront)
    (doseq [neighbor-id neighbors]
      (schedule-at! (get-region model neighbor-id) scheduler (+ time 1)))))
```

## Hybrid: Process + Simulation Agents

The hierarchical scheduler enables **hybrid systems**:

```clojure
;; Process-based LLM agent (async, reactive)
(def analyzer-agent (proc/create-agent
                      {:id :analyzer
                       :system-prompt "Analyze market trends..."
                       :model "claude-sonnet-4-5"}))

;; Observe market via mailbox (push-based)
(spin
  (loop []
    (let [event (await (:inbox analyzer-agent))]
      (analyze-market event)
      (recur))))

;; Simulation-based workers (scheduled, pull-based)
(def workers (repeatedly 10000 #(create-worker ...)))
(doseq [w workers]
  (hsched/register-agent! scheduler w))

;; Workers don't know about analyzer
;; Analyzer observes aggregate statistics
(defn worker-step [worker model time]
  (let [firm (get-firm model (:firm-id worker))]
    (decide-and-move worker firm)
    ;; Optionally post aggregate stats to analyzer
    (when (= (mod time 10) 0)
      (post! (:inbox analyzer-agent)
             {:type :market-snapshot
              :time time
              :firm-sizes (map count-employees firms)}))))

;; Run both in same execution context
(binding [rtc/*execution-context* ctx]
  ;; Analyzer process runs continuously
  (def analyzer-loop (agent-loop analyzer-agent))

  ;; Workers activate on schedule
  @(discrete-time-sim scheduler model 1000))
```

**Use cases for hybrid:**
- **Observer pattern**: LLM agent analyzes ABM simulation
- **Control pattern**: LLM agent adjusts ABM parameters
- **Debate pattern**: Multiple LLM agents coordinate ABM experiments

## Comparison to Pure FRP

### Pure FRP (Reactive Propagation)

```clojure
;; State change triggers cascade
(def firm-state-sig (signal {...}))

(def worker-util-spin
  (spin
    (let [firm (track firm-state-sig)]
      (calculate-utility firm))))

;; 10k workers all recompute when firm changes!
```

**Problem:** Reactive thrashing with large populations.

### Scheduled ABM (Pull-Based)

```clojure
;; State stored in atoms (not signals)
(def firm-state-a (atom {...}))

;; Workers activate on schedule
(defn worker-step [worker model time]
  (let [firm @firm-state-a]  ; Pull when scheduled
    (calculate-utility firm)))

;; Only scheduled workers query state
```

**Benefit:** Controlled activation, no thrashing.

## When to Use Which

| Use Case | Approach | Rationale |
|----------|----------|-----------|
| LLM agents (10-100) | Process-based | Async I/O, message passing |
| Economic sim (10k+) | Hierarchical ABM | Scheduled activation, performance |
| Spatial model (100k+) | Hierarchical ABM | Spatial locality, batching |
| Hybrid (LLM + ABM) | Both | LLM observes/controls ABM |
| Real-time UI | Pure FRP | Immediate reactive updates |
| Simulation control | Pure FRP | Reactive to user input |

## Future Extensions

### 1. Time Control

Support both real-time and virtual time:

```clojure
;; Real-time mode (uses execute-after!)
(def ctx (rtc/create-runtime {:impl :atoms}))

;; Virtual time mode (advance-time!)
(def ctx (rtc/create-runtime {:impl :atoms
                              :time-mode :virtual}))

;; Advance virtual time manually
(advance-time! ctx 10.0)  ; Jump 10 time units
```

### 2. Weak References

Like Mesa's `AgentSet`, support weak references for GC:

```clojure
(defrecord WeakAgentSet [agents-weak-a ...]
  (live-agents [this]
    (filter some? (map deref @agents-weak-a))))
```

### 3. Spatial Indexing

Integrate with spatial data structures:

```clojure
(defprotocol ISpatialIndex
  (nearby [this pos radius] "Find agents within radius")
  (move! [this agent old-pos new-pos] "Update spatial index"))

(defrecord GridSpace [dims cell-map-a] ...)
(defrecord QuadTree [bounds tree-a] ...)
```

### 4. Data Collection

Track metrics over time:

```clojure
(defrecord DataCollector [agent-reporters model-reporters data-a]
  (collect! [this agents model step]
    ;; Record time series for analysis
    ))
```

## Summary

The hierarchical scheduler extends spindel with ABM-compatible abstractions:

✅ **Scheduled activation** - prevents reactive thrashing
✅ **Hierarchy-aware** - agents, groups, societies, regions
✅ **Priority-based** - top-down coordination
✅ **Multiple strategies** - discrete-time, event-queue, staged
✅ **Hybrid compatible** - process agents + simulation agents
✅ **Performance patterns** - batching, parallelism, locality

This enables dvergr to support both:
- **Small-scale LLM agents** (10-100) with process-based patterns
- **Large-scale ABM** (10k-1M) with scheduled activation

The two approaches **coexist** in the same execution context, enabling hybrid systems where LLM agents observe, analyze, and control large-scale simulations.
