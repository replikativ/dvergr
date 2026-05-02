# Agent Time Model: Independent Axes with Explicit Synchronization

## Core Insight

From Milner's bigraphs and FRP/spindel:

**Time is flexible and local** - each agent has its own time axis (turn counter) that advances with each reaction (LLM turn). There is no global clock. Agents only synchronize when they:

1. **Share signals** - tracking the same reactive state
2. **Explicitly wait** - request-response pattern
3. **Admin control** - external intervention pauses execution

## Algebraic Structure

### From Milner's Reactive Systems

```
Reaction: a → a'     (agent changes state)
Context:  c ◦ a      (agent within environment)
Location: ĩ         (where reaction occurs)
```

In bigraphs:
- **Place graph**: containment hierarchy (group chat contains agents)
- **Link graph**: communication channels (signals between agents)

The key property: **reactions at different locations are independent**.

### From Spindel

```clojure
;; Each spin has its own logical time
(spin
  (let [messages (track messages-signal)]  ; reactive dependency
    ;; This code re-runs when messages-signal changes
    ;; The spin's "time" advances each re-execution
    (process messages)))
```

Spindel's model:
- **Inside a spin**: one-shot computation (no internal time)
- **From outside**: reactive to signal changes (time = re-executions)
- **Forking**: O(1) CoW creates independent time axes

### Mapping to Agents

| Milner Concept | Spindel Concept | Agent Concept |
|----------------|-----------------|---------------|
| Reaction | Spin re-execution | Agent turn |
| Location | ExecutionContext | Agent instance |
| Place graph | Context nesting | Group/sub-agent hierarchy |
| Link graph | Signal dependencies | Shared state |

## Agent Execution Model

### Independent Time Axes

```
Agent A:  t₀ → t₁ → t₂ → t₃ → ...
Agent B:  s₀ → s₁ → s₂ → ...
Agent C:  u₀ → u₁ → u₂ → u₃ → u₄ → ...

No synchronization barriers between them.
Each advances at its own pace.
```

### Synchronization Points

Synchronization only occurs at explicit points:

1. **Signal dependency** (implicit)
   ```clojure
   ;; Agent A writes to result-signal
   ;; Agent B tracks result-signal
   ;; B re-executes when A updates the signal
   ```

2. **Ask-and-wait** (explicit blocking)
   ```clojure
   ;; Agent A asks Agent B a question and blocks
   (let [answer (ask! agent-b "what is X?")]
     ;; A's time is suspended until B responds
     (use answer))
   ```

3. **Fire-and-forget** (no sync)
   ```clojure
   ;; Agent A spawns Agent B but doesn't wait
   (spawn! agent-b task)
   ;; A continues immediately on its own time axis
   ```

4. **Admin control** (external intervention)
   ```clojure
   ;; Admin sets pause-signal
   ;; Agents check pause-signal and yield if set
   (when-not (paused? ctx)
     (continue-execution))
   ```

## Implementation with Spindel

### Agent as a Spin

```clojure
(defn agent-spin
  "Create a spin that runs agent turns.

   The spin tracks:
   - messages-signal: incoming messages
   - status-signal: :active/:paused/:complete
   - budget-signal: resource limits

   It runs one-shot internally, but is reactive
   to external changes."
  [ctx {:keys [provider model]}]
  (spin
    (let [status (track (:status-signal ctx))
          messages (track (:messages-signal ctx))
          budget (track (:budget-signal ctx))]

      (when (and (= status :active)
                 (needs-response? messages)
                 (within-budget? budget))

        ;; Run one turn (one-shot from our perspective)
        (run-turn! ctx provider model)

        ;; Our time advances, but only when signals change
        ;; do we re-execute from outside's perspective
        ))))
```

### Forking for Sub-Agents

```clojure
(defn spawn-sub-agent!
  "Fork context and run agent in parallel.

   Parent and child have independent time axes.
   Communication via signals."
  [parent-ctx agent task]
  (let [;; Fork gives O(1) CoW context
        child-ctx (fork-context parent-ctx)

        ;; Create result signal for communication
        result-signal (signal nil)]

    ;; Child runs on its own time axis
    (future
      (let [result (run-agent-in-fork! child-ctx agent task)]
        ;; Signal result back - this triggers parent if tracking
        (reset! result-signal result)))

    ;; Return immediately - no blocking
    result-signal))
```

### Synchronization via Signals

```clojure
(defn ask-agent!
  "Ask another agent and wait for response.

   This is the explicit synchronization point."
  [from-ctx to-agent question]
  (let [response-signal (signal nil)
        request {:question question
                 :response-signal response-signal}]

    ;; Send request to target agent's inbox
    (swap! (:inbox-signal to-agent) conj request)

    ;; Block until response arrives
    ;; This suspends our time axis
    (loop []
      (if-let [response @response-signal]
        response
        (do
          (Thread/sleep 100)  ; Yield
          (recur))))))
```

## Behavior Tree Integration

From Dendron paper and grain's implementation:

```
tick() → SUCCESS | FAILURE | RUNNING
```

Maps to agent turns:
- **SUCCESS**: Task complete, agent done
- **FAILURE**: Task failed, agent done
- **RUNNING**: Need more turns, continue

The behavior tree provides the control structure:

```clojure
[:sequence
  [:action research-agent]      ; Run to completion
  [:action coding-agent]        ; Then run this
  [:parallel {:threshold 2}     ; Run these concurrently
    [:action test-agent-1]
    [:action test-agent-2]
    [:action test-agent-3]]]
```

Each `:action` is an agent with its own time axis.
`:sequence` enforces ordering (explicit sync).
`:parallel` allows independent progress.

## Key Properties

### 1. No Global Time

There is no master clock. Each agent advances when it reacts.
This matches reality: LLM calls take variable time.

### 2. Reactivity is Optional

Inside a spin, computation is deterministic one-shot.
Reactivity only manifests when external signals change.

For most agent tasks, the flow is:
```
receive task → run turns → return result
```
No reactivity needed internally.

### 3. Synchronization is Explicit

Agents don't accidentally block each other.
Sync points are:
- Explicit `ask-and-wait` calls
- Admin pause signals
- Join points in behavior trees

### 4. Forking is Cheap

O(1) CoW means spawning sub-agents is nearly free.
This enables speculative execution and parallel research.

## Comparison with core.async

| Aspect | core.async | Spindel Model |
|--------|-----------|---------------|
| Communication | Channels (push) | Signals (pull) |
| Blocking | go blocks on <! | Spin tracks signals |
| Time | Implicit in channel ops | Explicit in signal changes |
| State | External to channels | Inside signals |
| Forking | Thread/go-block | O(1) context fork |

The spindel model is more natural for agents because:
1. State is first-class (signals) not hidden in channels
2. Forking is O(1) with CoW semantics
3. Reactivity is declarative (track) not imperative (<!)

## Related Research (Synthesized)

See **AGENT_THEORY_SYNTHESIS.md** for comprehensive theoretical foundations.

### Key Findings from Research

**From Coalgebra (Jacobs):**
- Agents are coalgebras: `State → F(State)` where F captures observable behavior
- Bisimilarity gives behavioral equivalence - agents with same observations are interchangeable
- Temporal operators (○ nexttime, □ henceforth, ◇ eventually) specify safety/liveness

**From Behavior Trees (Dendron):**
- Optimal modularity: siblings don't affect each other
- Safety composition: classifier → safe-response vs unrestricted (95%+ defense rate)
- Tick semantics: SUCCESS | FAILURE | RUNNING maps naturally to agent turns

**From DSPy:**
- Module composition with `forward()` chains
- Trajectory accumulation in ReAct loops
- Compile-time optimization via Teleprompter pattern

**From OpenCode:**
- Context compaction preserving last 2 turns + summary
- Session hierarchy for parent-child agent relationships
- Pattern-based permissions per agent type

**From Petri Nets (TB-CSPN 2025):**
- 62.5% faster processing, 66.7% fewer LLM calls vs LangGraph
- Workflow coordination while LLMs handle semantic processing

### Research Gap Identified

**No existing work applies bigraphical reactive systems to LLM multi-agent orchestration.**
This represents a novel research opportunity for dvergr.

## Next Steps

1. Refactor `chat/group.clj` to use spindel signals instead of core.async
2. Implement behavior tree control flow on top of spindel
3. Add admin control via status signals
4. Test independent time progression with parallel agents
5. Add context compaction with summary preservation
6. Implement coalgebraic invariant checking for safety properties
7. Explore bigraph-native modeling for agent hierarchies
