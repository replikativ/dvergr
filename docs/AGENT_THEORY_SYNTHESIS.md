# Agent Theory Synthesis: Algebraic Foundations for Dvergr

## Overview

This document synthesizes research from multiple theoretical foundations to establish
a rigorous framework for agent composition in dvergr. The key insight is that
**time is flexible and local** - each agent operates on its own time axis, with
synchronization occurring only at explicit points.

Sources:
- Milner's bigraphs (spatial semantics, reactions)
- Jacobs' coalgebra (behavioral semantics, bisimilarity)
- FRP/Spindel (flexible time, signals, O(1) CoW)
- Dendron behavior trees (optimal modularity)
- DSPy (module composition patterns)
- OpenCode (context management, compaction)

---

## Part 1: Theoretical Foundations

### 1.1 Milner's Bigraphs: Space and Motion

From "The Space and Motion of Communicating Agents" (2009):

**Core Structure:**
```
Bigraph B = (Place graph, Link graph)

Place graph: Tree of locations/containment
Link graph: Hypergraph of communication channels
```

**Key Properties:**
- **Reactions are local**: `a → a'` occurs at a specific location
- **Context composition**: `c ◦ a` places agent `a` within context `c`
- **Independence**: Reactions at different locations don't interfere

**Mapping to Agents:**

| Bigraph Concept | Agent Concept |
|-----------------|---------------|
| Place graph | Group chat contains sub-agents |
| Link graph | Signals/channels between agents |
| Reaction | Agent turn (state transition) |
| Location | Execution context |
| Mobility | Fork/join of contexts |

### 1.2 Coalgebra: Behavioral Semantics

From Jacobs' "Introduction to Coalgebra" (2017):

**Core Principle:** Coalgebras capture **behavior** (what a system does) vs algebras
(what a system is made of).

```
Coalgebra: X → F(X)

Where:
- X is the state space
- F is a functor describing observable behavior
- Homomorphisms preserve behavior
```

**Key Concepts:**

1. **Bisimilarity**: Two states are equivalent if they produce the same observations
   ```
   x ↔ y  iff  for all observations o: observe(x) = observe(y)
   ```

2. **Coinduction**: Prove properties by showing they hold after each step
   ```
   If P is invariant (P ⊆ ○P) and P(x), then □P(x)  (henceforth)
   ```

3. **Temporal Operators**:
   - `○P` (nexttime): P holds in the next state
   - `□P` (henceforth): P holds from now on
   - `◇P` (eventually): P will hold at some point

**Application to Agents:**
- Agent state: current messages, budget, status
- Behavior functor: `Agent → (Response × Agent)` (produces output + new state)
- Bisimilarity: agents with same observable behavior are interchangeable

### 1.3 FRP and Flexible Time

From Rhine FRP and Spindel:

**Key Insight:** Time is not wall-clock but **logical** - advances when signals change.

```clojure
;; Inside a spin: one-shot computation (no internal time)
;; From outside: reactive to signal changes (time = re-executions)

(spin
  (let [messages (track messages-signal)]
    ;; This body re-runs when messages-signal changes
    ;; Each re-execution is a "tick" of logical time
    (process messages)))
```

**Spindel Model:**

| Concept | Description |
|---------|-------------|
| Signal | Observable state container |
| Spin | Reactive computation unit |
| Track | Creates dependency on signal |
| Fork | O(1) CoW context creation |
| Deltaable | Incremental update support |

**Interval Structure** (from Incremental λ-Calculus):
```
Interval I = ⟨old: A, new: A, δ: List(Delta)⟩

Combinators preserve O(δ) complexity:
- map: transforms deltas element-wise
- filter: enter/exit semantics
- reduce: requires invertible aggregation
```

### 1.4 Behavior Trees: Optimal Modularity

From Dendron paper (Kelley 2024):

**Core Abstraction:**
```
tick() → SUCCESS | FAILURE | RUNNING
```

**Key Properties:**
1. **Optimal modularity**: Adding behaviors doesn't affect siblings
2. **Composability**: Complex trees = combinations of subtrees
3. **Reactive control**: Tick loops enable real-time adaptation

**Node Types:**

| Node | Behavior |
|------|----------|
| Sequence | Run children until one fails |
| Fallback | Run children until one succeeds |
| Parallel | Run children concurrently |
| Action | Leaf node performing work |
| Condition | Leaf node checking state |

**Safety Composition Pattern:**
```
if threat-classifier(input) == THREAT:
  handle-unsafe(input)    ; deterministic, safe response
else:
  llm-response(input)     ; unrestricted model
```

This achieves 95-100% defense success vs 23-80% for baseline approaches.

---

## Part 2: Unified Agent Model

### 2.1 Agent as Coalgebra

An agent is a coalgebra:
```
Agent : State → F(State)

Where F captures:
- Observable outputs (text, tool calls)
- State transitions (message history, budget)
- Termination conditions (complete, error, budget-exceeded)
```

**Behavior Functor:**
```clojure
;; Agent behavior type
(defprotocol AgentBehavior
  (observe [state] "Extract observable output")
  (step [state input] "Produce (output, next-state) pair")
  (terminal? [state] "Check if agent has completed"))
```

### 2.2 Time Axes and Synchronization

Each agent has an **independent time axis**:

```
Agent A:  t₀ → t₁ → t₂ → t₃ → ...
Agent B:  s₀ → s₁ → s₂ → ...
Agent C:  u₀ → u₁ → u₂ → u₃ → u₄ → ...

No global clock. Each advances when it reacts.
```

**Synchronization Points:**

1. **Signal dependency** (implicit)
   - Agent A writes to signal S
   - Agent B tracks S → B re-executes when S changes

2. **Ask-and-wait** (explicit blocking)
   - Agent A suspends until Agent B responds
   - A's time axis pauses during wait

3. **Fire-and-forget** (no sync)
   - A spawns B but continues immediately
   - Independent time progression

4. **Admin control** (external)
   - Pause signal suspends all agents
   - Resume signal continues execution

### 2.3 Spatial Hierarchy (Bigraph Structure)

```
Place Graph (containment):
┌─────────────────────────────────────┐
│  Group Chat (parent context)        │
│  ┌───────────┐  ┌───────────┐       │
│  │ Agent A   │  │ Agent B   │       │
│  │ (forked)  │  │ (forked)  │       │
│  └───────────┘  └───────────┘       │
│           ↑          ↑              │
│           └────┬─────┘              │
│                │                    │
│  Link Graph: shared-signal          │
└─────────────────────────────────────┘
```

**Properties:**
- Forked contexts inherit parent's signals
- Each fork has independent state (CoW)
- Communication via shared signals or explicit messages

### 2.4 Behavior Tree Integration

Map agent control flow to behavior trees:

```clojure
[:sequence
  [:action research-agent]      ; Run to completion
  [:action coding-agent]        ; Then run this
  [:parallel {:threshold 2}     ; Run concurrently
    [:action test-agent-1]
    [:action test-agent-2]
    [:action test-agent-3]]]
```

**Mapping to Agent Turns:**
- SUCCESS → task complete, agent done
- FAILURE → task failed, error handling
- RUNNING → need more turns, continue

---

## Part 3: Implementation Patterns

### 3.1 Agent as Spin

```clojure
(defn agent-spin
  "Create a spin that runs agent turns.

   Tracks signals for reactive updates.
   Runs one-shot internally, reactive externally."
  [ctx {:keys [provider model max-turns]}]
  (spin
    (let [status (track (:status-signal ctx))
          messages (track (:messages-signal ctx))
          budget (track (:budget-signal ctx))]

      (when (and (= status :active)
                 (needs-response? messages)
                 (within-budget? budget))

        ;; One turn (one-shot from our perspective)
        (run-turn! ctx provider model)))))
```

### 3.2 Forking for Sub-Agents

```clojure
(defn spawn-sub-agent!
  "Fork context and run agent in parallel.

   O(1) CoW gives independent time axes.
   Communication via result signal."
  [parent-ctx agent task]
  (let [child-ctx (fork-context parent-ctx)
        result-signal (signal nil)]

    ;; Child runs independently
    (future
      (let [result (run-agent-in-fork! child-ctx agent task)]
        (reset! result-signal result)))

    ;; Return immediately - no blocking
    result-signal))
```

### 3.3 Ask-and-Wait Pattern

```clojure
(defn ask-agent!
  "Ask another agent and wait for response.

   Explicit synchronization point.
   Suspends caller's time axis."
  [from-ctx to-agent question]
  (let [response-signal (signal nil)]

    ;; Send request
    (swap! (:inbox-signal to-agent) conj
           {:question question
            :response-signal response-signal})

    ;; Block until response
    (loop []
      (if-let [response @response-signal]
        response
        (do (Thread/sleep 100) (recur))))))
```

### 3.4 Behavior Tree Control Flow

```clojure
(defprotocol BehaviorNode
  (tick [this ctx] "Execute, return {:status :success/:failure/:running}"))

(defn sequence-node [& children]
  (reify BehaviorNode
    (tick [_ ctx]
      (loop [[child & rest] children]
        (if-not child
          {:status :success}
          (let [result (tick child ctx)]
            (case (:status result)
              :success (recur rest)
              result)))))))

(defn parallel-node [{:keys [threshold]} & children]
  (reify BehaviorNode
    (tick [_ ctx]
      (let [results (pmap #(tick % ctx) children)
            successes (count (filter #(= :success (:status %)) results))]
        {:status (if (>= successes threshold) :success :failure)
         :results results}))))
```

### 3.5 Context Compaction (from OpenCode)

```clojure
(defn compact-context!
  "Prune old tool outputs, preserving recent turns.

   Strategy:
   - Keep last 2 user turns + responses
   - Summarize older content
   - Mark tool outputs as compacted"
  [ctx {:keys [target-tokens summary-model]}]
  (let [messages (get-messages ctx)
        recent (take-last 4 messages)  ; 2 turns
        old (drop-last 4 messages)]

    (when (> (token-count old) target-tokens)
      ;; Generate summary of old content
      (let [summary (llm/summarize summary-model old)]
        ;; Replace old messages with summary
        (reset! (:messages-signal ctx)
                (into [{:role :system
                        :content (str "Previous context summary: " summary)}]
                      recent))))))
```

---

## Part 4: Algebraic Properties

### 4.1 Composition Laws

**Sequential Composition:**
```
(agent-A ⊳ agent-B) = run A, then B with A's output
```

**Parallel Composition:**
```
(agent-A ∥ agent-B) = run A and B independently
```

**Choice:**
```
(agent-A ⊕ agent-B) = run A; if fails, try B
```

### 4.2 Bisimilarity for Agents

Two agents are bisimilar if:
```
A ↔ B  iff  for all inputs i:
  observe(step(A, i)) = observe(step(B, i))  AND
  next(A, i) ↔ next(B, i)
```

**Practical implication:** Agents producing the same outputs with same termination
behavior are interchangeable, regardless of internal implementation.

### 4.3 Safety Properties (Invariants)

Using coalgebraic invariants:
```clojure
(defn budget-safe?
  "Invariant: budget never goes negative"
  [ctx]
  (<= 0 (budget-remaining ctx)))

(defn progress-safe?
  "Invariant: active agents eventually complete or fail"
  [ctx]
  (or (not= :active (get-status ctx))
      (can-make-progress? ctx)))
```

### 4.4 Liveness Properties (Eventually)

```clojure
;; Eventually terminates (◇complete)
(defn eventually-terminates?
  [agent max-turns]
  (loop [ctx (init-context agent)
         turn 0]
    (cond
      (terminal? ctx) true
      (>= turn max-turns) false
      :else (recur (step ctx) (inc turn)))))
```

---

## Part 5: Comparison with Other Approaches

### 5.1 DSPy Module System

| Aspect | DSPy | Dvergr |
|--------|------|----------|
| Composition | Nested modules | Behavior trees + forked contexts |
| State flow | Prediction objects | Signals + deltaable collections |
| Parallelism | Parallel module | Parallel BT node + forked contexts |
| Optimization | Teleprompter compile-time | Budget-based pruning |
| Type safety | Pydantic signatures | Clojure spec / schema |

### 5.2 OpenCode Context Management

| Aspect | OpenCode | Dvergr |
|--------|----------|----------|
| Storage | Session hierarchy | Datahike + signals |
| Compaction | Message parts + pruning | Summary + compaction |
| Permissions | Pattern-based | Per-agent + admin control |
| Sub-agents | Task tool → child session | Fork context → sub-agent |

### 5.3 Process Calculi

| Formalism | Relevance to Dvergr |
|-----------|----------------------|
| π-calculus | Name passing for dynamic channels |
| Petri nets | Resource-aware coordination (TB-CSPN) |
| Mobile ambients | Spatial containment hierarchies |
| Session types | Typed agent communication protocols |

---

## Part 6: Research Gaps and Opportunities

### 6.1 Identified Gaps

1. **Bigraphs for LLM agents**: No academic work applying bigraphical reactive
   systems to multi-agent LLM systems

2. **Session types for orchestration**: Limited work on typed channel systems
   for agent communication

3. **Incremental agent state**: O(δ) updates for large agent state changes

### 6.2 Opportunities for Dvergr

1. **Bigraph-native design**: Model agent hierarchies as bigraphs from the start

2. **Coalgebraic verification**: Use bisimilarity for agent equivalence checking

3. **Behavior tree safety**: Formal verification of agent control flow

4. **Incremental context**: Deltaable message histories for efficient compaction

---

## Part 7: Implementation Roadmap

### Phase 1: Core Infrastructure
- [x] ChatContext with spindel signals
- [x] Fork/join for sub-agents
- [ ] Behavior tree control flow layer
- [ ] Context compaction strategy

### Phase 2: Algebraic Foundation
- [ ] Agent as coalgebra protocol
- [ ] Bisimilarity checking for tests
- [ ] Temporal property specifications
- [ ] Safety/liveness invariants

### Phase 3: Advanced Features
- [ ] Bigraph-style spatial modeling
- [ ] Session types for agent communication
- [ ] Incremental state updates (O(δ))
- [ ] Formal verification integration

---

## References

1. Milner, R. (2009). The Space and Motion of Communicating Agents.
2. Jacobs, B. (2017). Introduction to Coalgebra.
3. Kelley, J. (2024). Behavior Trees in LLM Agent Systems (Dendron paper).
4. Bärenz & Perez (2018). Rhine: FRP with type-level clocks.
5. Cai et al. (2014). A Theory of Changes for Higher-Order Languages (Incremental λ-Calculus).
6. Milner, Parrow, Walker (1992). A Calculus of Mobile Processes (π-calculus).
7. Shoham & Leyton-Brown. Multiagent Systems: Algorithmic, Game-Theoretic, and Logical Foundations.
8. Coecke & Kissinger (2017). Picturing Quantum Processes (diagrammatic reasoning).

---

## Appendix: Key Equations

**Coinduction Principle:**
```
P invariant ∧ P(x)  ⟹  □P(x)
```

**Bisimilarity:**
```
x ↔ y  ⟺  ∃R. R(x,y) ∧ R is a bisimulation
```

**Interval Composition:**
```
f(a ⊕ da) = f(a) ⊕ ∂f(a, da)
```

**Behavior Tree Tick:**
```
tick(sequence(A, B)) = tick(A) ⊳ (λr. if r=SUCCESS then tick(B) else r)
tick(fallback(A, B)) = tick(A) ⊳ (λr. if r=FAILURE then tick(B) else r)
```
