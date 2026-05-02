# Scheduler Integration Design: ABM ↔ Spindel

## The Core Challenge

ABM frameworks (mesa, Agents.jl) use **discrete-time scheduling** where:
- All agents activate at t=0, t=1, t=2, ... (discrete time steps)
- Activation order is controllable (sequential, random, simultaneous)
- Model explicitly controls time advancement (`model.step()`)

Spindel uses **reactive dataflow** where:
- Spins react to changes (signal updates, deferred delivery)
- Time is continuous (or implicit)
- No explicit "step" - events propagate when they happen

**The tension**: ABM needs explicit control, FRP is declarative/reactive.

## Three Integration Approaches

### Option 1: Agents as Sleeping Spins (Simplest)

Agents are just spins that sleep between activations:

```clojure
(defn agent-process [agent dt]
  "Agent sleeps, wakes at intervals, queries state"
  (spin
    (loop [t 0]
      (await (comb/sleep dt))  ; Sleep until next activation
      (let [state (pull-state-from-model agent)]  ; Pull-based query
        (agent-step! agent state t))
      (recur (+ t dt)))))

;; Start all agents
(doseq [agent agents]
  (agent-process agent 1.0))  ; All sleep 1.0s between steps
```

**Pros:**
- Uses existing spindel primitives
- No new scheduler abstraction
- Naturally parallel (all agents are independent spins)

**Cons:**
- No control over activation order (random based on sleep timing)
- Wasteful (many sleeping spins)
- Hard to implement staged activation

### Option 2: Coordinator Spin with Batch Execution

Single coordinator controls agent activation:

```clojure
(defn discrete-time-coordinator [agents dt]
  "Coordinator wakes agents in controlled order"
  (spin
    (loop [t 0]
      (await (comb/sleep dt))

      ;; Activate agents in specified order
      (case activation-order
        :sequential
        (doseq [agent agents]
          (agent-step! agent t))

        :random
        (doseq [agent (shuffle agents)]
          (agent-step! agent t))

        :simultaneous
        (await (comb/parallel
                 (map #(spin (agent-step! % t)) agents))))

      (recur (+ t dt)))))
```

**Pros:**
- Explicit control over activation order
- Single sleep (not N sleeping spins)
- Matches mesa/Agents.jl semantics

**Cons:**
- Coordinator becomes bottleneck
- Still uses sleep (not ideal for simulation time)

### Option 3: Virtual Time with Event Queue

Extend spindel with virtual time control:

```clojure
;; Engine already has :engine/virtual-time and :engine/time-mode
;; in ExecutionContext (see context.cljc:239-240)

(defn advance-virtual-time! [ctx dt]
  "Advance virtual time without sleep"
  (rtp/swap-state! ctx [:engine/virtual-time] + dt)
  ;; Process all events scheduled up to new time
  (process-delayed-spins-until! ctx (rtp/get-state ctx [:engine/virtual-time])))

(defn discrete-time-sim [agents dt max-steps]
  (binding [rtc/*execution-context* ctx]
    (loop [step 0]
      (when (< step max-steps)
        ;; Activate agents (no sleep - instant in virtual time)
        (activate-agents! agents step)
        ;; Advance virtual time
        (advance-virtual-time! ctx dt)
        (recur (inc step))))))
```

**Pros:**
- No actual sleeping (fast simulation)
- Deterministic (not dependent on wall-clock timing)
- Can run faster than real-time
- Matches simulation literature (DES)

**Cons:**
- Requires virtual time support in spindel
- Need to carefully handle :real vs :virtual time mode

## What Spindel Already Has

Looking at `runtime/context.cljc`:

```clojure
;; Line 239-240
:engine/virtual-time 0
:engine/time-mode :real

;; Lines 127-139 - schedule-delayed-execution! already handles this!
(schedule-delayed-execution! [this delay-ms spin-fn]
  (let [spin-id (simple/schedule-delayed-spin! this delay-ms spin-fn)
        time-mode (rtp/get-state this [:engine/time-mode])]

    ;; In real-time mode, schedule executor timer to process the event queue
    (when (and (= time-mode :real) executor)
      (sched/execute-after!
        executor
        delay-ms
        #(simple/process-delayed-spins! this executor)))

    spin-id))
```

**Key insight:** Spindel ALREADY has virtual time infrastructure!
- `:engine/time-mode` can be `:real` or `:virtual`
- `:engine/virtual-time` tracks simulation time
- In `:virtual` mode, delayed spins don't use executor timers

## Proposed Integration: Leverage Existing Virtual Time

### Step 1: Simple Discrete-Time Pattern

```clojure
(defn create-simulation-context []
  "Create execution context in virtual time mode"
  (rtc/create-runtime
    {:impl :atoms
     :scheduler (sched/synchronous-executor)  ; Deterministic
     :initial-state {:engine/time-mode :virtual
                     :engine/virtual-time 0.0}}))

(defn discrete-time-step [agents model time]
  "Activate all agents for one time step"
  (spin
    ;; Activate in specified order
    (doseq [agent (shuffle agents)]  ; or sequential, or parallel
      (agent-step! agent model time))))

(defn run-simulation [agents model max-steps dt]
  (let [ctx (create-simulation-context)]
    (binding [rtc/*execution-context* ctx]
      (loop [step 0]
        (when (< step max-steps)
          (let [time (* step dt)]
            ;; Activate agents
            @(discrete-time-step agents model time)
            ;; Advance virtual time
            (rtp/swap-state! ctx [:engine/virtual-time] + dt)
            (recur (inc step))))))))
```

**This uses existing spindel primitives!**

### Step 2: Add Convenience Functions

```clojure
(ns dvergr.simulation.discrete-time
  "Discrete-time simulation patterns using spindel's virtual time.")

(defn advance-time!
  "Advance virtual time by dt"
  [ctx dt]
  (rtp/swap-state! ctx [:engine/virtual-time] + dt))

(defn current-time
  "Get current simulation time"
  [ctx]
  (rtp/get-state ctx [:engine/virtual-time]))

(defn activate-sequential [agents model time]
  (spin
    (doseq [agent agents]
      (agent-step! agent model time))))

(defn activate-random [agents model time]
  (spin
    (doseq [agent (shuffle agents)]
      (agent-step! agent model time))))

(defn activate-parallel [agents model time]
  (spin
    (await (comb/parallel
             (map #(spin (agent-step! % model time)) agents)))))

(defn activate-simultaneous [agents model time]
  "All agents read state at same time, then all update"
  (spin
    ;; Phase 1: All agents decide (read state)
    (let [decisions (await (comb/parallel
                             (map #(spin (agent-decide % model time)) agents)))]
      ;; Phase 2: All agents act (update state)
      (doseq [[agent decision] (map vector agents decisions)]
        (agent-act! agent decision model)))))
```

## Towards Adaptive Scheduling & Multiscale

The discrete-time pattern is a **special case** of more general adaptive scheduling.

### Declarative Specification

To enable compilation/optimization, specify dynamics declaratively:

```clojure
(defrecord AgentDynamics
  [state-fn     ; (fn [agent state t] -> next-state) - pure function
   dt-fn        ; (fn [agent state t] -> dt) - adaptive time step
   error-fn     ; (fn [agent predicted actual] -> error) - for adaptive stepping
   coupling-fn  ; (fn [agent neighbors] -> influence) - inter-agent coupling
   metadata])   ; {:spatial-locality true, :parallel-safe true}

(defrecord SimulationSpec
  [agents              ; Vector of agents with dynamics
   time-stepper        ; :fixed | :adaptive | :multiscale
   error-tolerance     ; For adaptive methods
   spatial-structure   ; :grid | :graph | :continuous
   integration-method  ; :euler | :rk4 | :verlet
   compilation-target]) ; :jvm | :gpu | :multicore
```

**Key properties for compilation:**
- `state-fn` is pure → can be vectorized/parallelized
- `dt-fn` enables adaptive stepping
- `error-fn` enables error control
- `coupling-fn` specifies dependencies
- `metadata` hints at optimization opportunities

### Abstract Interpretation

Before execution, analyze the computation graph:

```clojure
(defn analyze-dynamics [sim-spec]
  "Analyze simulation for optimization opportunities"
  {:parallel-groups (find-independent-agents sim-spec)
   :spatial-locality (find-spatial-clusters sim-spec)
   :time-scales (estimate-time-scales sim-spec)
   :error-bounds (estimate-errors sim-spec)
   :gpu-compatible? (check-gpu-compatibility sim-spec)})

(defn compile-simulation [sim-spec analysis]
  "Generate optimized execution plan"
  (case (:compilation-target sim-spec)
    :jvm (compile-to-jvm analysis)
    :gpu (compile-to-cuda analysis)
    :multicore (compile-to-parallel analysis)))
```

### Multiscale / Multifidelity

Different agents can have different time scales:

```clojure
(defrecord MultiscaleAgent
  [dynamics
   time-scale     ; :fast | :medium | :slow
   fidelity       ; :high | :medium | :low
   subcycling])   ; For fast agents: how many substeps per global step

(defn multiscale-step [agents global-dt]
  "Adaptive subcycling based on time scales"
  (let [groups (group-by :time-scale agents)]
    ;; Fast agents: take multiple substeps
    (doseq [agent (:fast groups)]
      (let [substeps (:subcycling agent)]
        (dotimes [i substeps]
          (agent-step! agent (/ global-dt substeps)))))

    ;; Medium agents: single step
    (doseq [agent (:medium groups)]
      (agent-step! agent global-dt))

    ;; Slow agents: step every N global steps
    (when (zero? (mod current-step N))
      (doseq [agent (:slow groups)]
        (agent-step! agent (* N global-dt))))))
```

## Integration Strategy

### Phase 1: Simple Discrete-Time (Now)
- Use spindel's existing virtual time
- Simple activation patterns (sequential, random, parallel)
- No new scheduler abstraction needed
- Focus: get mesa/Agents.jl patterns working

### Phase 2: Declarative Specifications (Later)
- Define `AgentDynamics` and `SimulationSpec` records
- Pure state functions (no side effects)
- Enable analysis before execution

### Phase 3: Abstract Interpretation (Later)
- Analyze computation graph
- Identify parallelization opportunities
- Detect spatial locality
- Estimate time scales and errors

### Phase 4: Compilation (Later)
- Generate optimized JVM code
- Compile to CUDA for GPU
- Vectorize operations
- Apply numerical integration schemes

## Open Questions

1. **Scheduler Extension Points**: Do we need to extend `PScheduler` protocol, or is virtual time + coordination spins enough?

2. **Batch Execution**: Should executor support batch operations (`execute-batch!`), or handle at coordinator level?

3. **Priority/Ordering**: Should we add priority queues to spindel's event queue, or manage ordering externally?

4. **Integration Method Selection**: Should time stepping be part of agent dynamics, or simulation-level config?

5. **GPU Compatibility**: What restrictions on agent dynamics enable GPU compilation? (Probably: pure functions, no dynamic dispatch, bounded memory)

## Recommendation

**Start simple**: Use existing virtual time + coordinator pattern
- No new scheduler abstractions
- Leverage `:engine/time-mode` `:virtual`
- Coordinator spin controls activation order
- Works for mesa/Agents.jl patterns

**Keep declarative**: Design `AgentDynamics` specification
- Pure state functions
- Explicit coupling/dependencies
- Metadata for optimization hints
- Enables future compilation

**Defer compilation**: Don't build compiler yet
- First: get discrete-time working
- Then: experiment with adaptive/multiscale
- Finally: extract patterns worth compiling

This approach:
- Uses existing spindel primitives ✓
- Keeps it declarative ✓
- Enables future optimization ✓
- Doesn't overengineer ✓
