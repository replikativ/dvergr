# Agent Composition Design: Recursive Abstractions for Multi-Agent Systems

## Overview

This document defines the agent composition algebra for dvergr, synthesizing:
- Theoretical foundations (Milner's bigraphs, coalgebra, FRP)
- Practical patterns (opencode, clawdbot, grain behavior trees)
- Spindel primitives (signals, parallel, race, await)

**Core principle**: Agents are recursive - an agent can spawn sub-agents, which can spawn further sub-agents. Nesting is naturally bounded by budget and optional depth limits.

**Design philosophy**: Plain Clojure first, DSL optional.
- Primary interface: `ask!`, `spawn!`, `all`, `race` with regular `let` bindings
- All composition runs as spindel spins - behavior trees are executed via spindel combinators
- Declarative structure notation (behavior tree DSL) is optional, for when you need:
  - Runtime inspection/modification by admin
  - Serialization to datahike for persistence/replay
  - Visual rendering of execution plan

---

## Part 1: Core Abstractions

### 1.1 Agent

An **Agent** is a function that, given a task and context, produces a result over multiple turns.

```clojure
;; Agent constructor - returns an agent instance
(defn make-agent
  "Create an agent that can be asked or spawned.

   Options:
   - :name        - Agent identifier
   - :model       - LLM model to use
   - :provider    - LLM provider (:anthropic, :openai, :fireworks)
   - :max-turns   - Maximum turns before forced completion
   - :tools       - Tool permissions (set or predicate)
   - :permissions - Agent permissions #{:spawn-agents :admin}
   - :on-turn     - Callback for each turn (observation)
   - :body        - Optional custom agent logic (fn [task ctx] -> result)"
  [{:keys [name model provider max-turns tools permissions body]
    :or {provider :anthropic
         max-turns 20
         tools :all
         permissions #{}}}]
  ;; Returns an Agent record/map
  {:type :agent
   :name name
   :model model
   :provider provider
   :max-turns max-turns
   :tools tools
   :permissions permissions
   :body body})
```

**From outside**: An agent is opaque - you `ask!` it and get a result.
**From inside**: An agent has full autonomy - it decides when to call tools, iterate, stop, or spawn sub-agents.

### 1.2 Communication Primitives

```clojure
;; Blocking: run agent, wait for result
(ask! agent task)
(ask! agent task {:budget 5000 :timeout-ms 60000})
; => {:status :complete/:error/:timeout, :result ..., :turns n}

;; Non-blocking: start agent, return handle
(spawn! agent task)
(spawn! agent task {:budget 5000})
; => <spin> (can be awaited later, or ignored)

;; Fire-and-forget: start agent, discard handle
(tell! agent message)
; => nil (agent runs in background)
```

**Implementation mapping**:
- `ask!` = create agent spin + `await`
- `spawn!` = create agent spin (return spin, don't await)
- `tell!` = `spawn!` + discard handle

### 1.3 Composition Combinators

These map directly to spindel's existing combinators:

```clojure
;; All: run in parallel, collect all results
(all (ask! a task-a) (ask! b task-b) (ask! c task-c))
; => [result-a result-b result-c]

;; Race: run in parallel, first to complete wins
(race (ask! fast task) (ask! slow task))
; => first-result (losers cancelled)

;; Timeout: race against deadline
(timeout (ask! agent task) 30000 :timed-out)
; => result or :timed-out

;; Sequence: chain results (just regular let bindings)
(let [research (ask! researcher task)
      code (ask! coder (str "implement: " research))]
  code)
```

**Spindel mapping**:
| Agent Combinator | Spindel Primitive |
|------------------|-------------------|
| `all` | `parallel` |
| `race` | `race` |
| `timeout` | `timeout` |
| sequence | regular `let` bindings |

---

## Part 2: Plain Clojure Composition (Primary)

The primary way to compose agents is plain Clojure code with linguistic abstractions.

### 2.1 Sequential Composition

```clojure
;; Regular let bindings for sequential flow
(defn research-and-implement [topic]
  (let [research (ask! researcher (str "Research " topic))
        impl (ask! coder (str "Implement based on: " (:result research)))
        tests (ask! tester (str "Test: " (:result impl)))]
    {:research research :implementation impl :tests tests}))
```

### 2.2 Parallel Composition

```clojure
;; Multiple perspectives in parallel
(defn get-perspectives [question]
  (let [[security perf ux] (await (all
                                    (spawn! security-expert question)
                                    (spawn! performance-expert question)
                                    (spawn! ux-expert question)))]
    (synthesize security perf ux)))

;; Race - first to complete wins
(defn quick-answer [question]
  (await (race
           (spawn! fast-model question)
           (spawn! thorough-model question))))
```

### 2.3 Conditional and Dynamic

```clojure
;; Full language power for conditionals
(defn smart-delegation [task]
  (let [analysis (ask! analyzer task)]
    (case (:complexity analysis)
      :simple (ask! junior-dev task)
      :medium (ask! senior-dev task)
      :complex (let [[a b] (await (all (spawn! expert-1 task)
                                        (spawn! expert-2 task)))]
                 (ask! integrator (str "Combine: " a " and " b))))))
```

### 2.4 Recursive Spawning (Bounded)

```clojure
;; Agent can spawn sub-agents within its body
(def lead-agent
  (make-agent
    {:name "lead"
     :permissions #{:spawn-agents}
     :body (fn [task ctx]
             ;; Analyze, then delegate
             (let [subtasks (analyze-subtasks task)]
               (if (> (count subtasks) 1)
                 ;; Spawn sub-agents for each subtask
                 (let [results (await (all (for [st subtasks]
                                             (spawn! (make-agent {:name (str "sub-" (:id st))})
                                                     (:description st)
                                                     {:budget 5000}))))]
                   (combine-results results))
                 ;; Do it ourselves
                 (execute-directly task))))}))
```

---

## Part 3: Group Chat Abstraction

### 3.1 Group as Agent

A **Group** is itself an agent - from outside, you `ask!` it like any agent. Inside, it coordinates multiple agents with shared context.

```clojure
(defn make-group
  "Create a group chat with multiple agents.

   Options:
   - :name         - Group identifier
   - :participants - Initial agents in the group
   - :structure    - Behavior tree structure (default: parallel)
   - :admin        - Admin context (can pause/modify)
   - :budget       - Total budget for group
   - :messages     - Shared message signal"
  [{:keys [name participants structure admin budget]
    :or {structure :parallel
         budget 50000}}]
  {:type :group
   :name name
   :participants (atom (vec participants))
   :structure structure
   :admin admin
   :context (make-group-context {:budget budget})
   :status (signal :active)})
```

### 2.2 Group Operations

```clojure
;; Ask a group (runs according to structure)
(ask! group "implement feature X")

;; Post message to group (all agents can see)
(post! group :user "What's the status?")

;; Admin operations
(add-agent! group new-agent)
(remove-agent! group agent-name)
(pause! group)
(resume! group)
(set-structure! group [:sequence agent-a agent-b])

;; Observation
(watch! group {:on-message fn, :on-turn fn, :on-complete fn})
```

### 3.3 Behavior Tree Structure (Optional DSL)

For groups where you want declarative, inspectable structure, you can optionally
specify a behavior tree. This is useful for:
- Admin inspection/modification at runtime
- Persistence and replay
- Visual rendering

```clojure
;; Plain Clojure (preferred for most cases)
(defn dev-workflow [task]
  (let [research (ask! researcher task)
        [c1 c2] (await (all (spawn! coder-1 research)
                            (spawn! coder-2 research)))
        final (ask! integrator (str c1 "\n" c2))]
    final))

;; Equivalent declarative structure (optional, for inspection)
(def dev-workflow-structure
  [:sequence
    [:action researcher]
    [:parallel {:threshold 2}
      [:action coder-1]
      [:action coder-2]]
    [:action integrator]])

;; Group can use either approach
(make-group {:participants [researcher coder-1 coder-2 integrator]
             :workflow dev-workflow})  ; fn approach

(make-group {:participants [researcher coder-1 coder-2 integrator]
             :structure dev-workflow-structure})  ; declarative approach
```

**The declarative structure compiles to the same spindel primitives** - it's just
a different notation that can be inspected/modified at runtime.

### 3.4 Structure Execution in Spindel

The behavior tree structure is interpreted using spindel combinators:

```clojure
;; [:parallel ...] → (all (spawn! ...) (spawn! ...))
;; [:sequence ...] → nested let bindings with ask!
;; [:fallback ...] → (or (ask! a) (ask! b))
;; [:action agent] → (ask! agent task)
```

This means all agent coordination runs as spindel spins, benefiting from:
- O(1) CoW context forking
- Signal-based reactivity for admin control
- Consistent timeout/cancellation semantics

### 3.5 Mapping to Behavior Trees

| BT Node | Group Behavior | Result |
|---------|----------------|--------|
| `:action` | Single agent execution | Agent's result |
| `:sequence` | Run children in order | Last result or first failure |
| `:parallel` | Run children concurrently | All results (or threshold) |
| `:fallback` | Try until success | First success or all failures |
| `:condition` | Check predicate | Success/failure, no side effects |

**Tick semantics**:
- `:success` - Agent/subtree completed successfully
- `:failure` - Agent/subtree failed
- `:running` - Agent/subtree still working (more turns needed)

---

## Part 4: Context and State

### 4.1 ChatContext (Existing)

Each agent/group has a `ChatContext` providing:
- `messages-signal` - Conversation history (deltaable)
- `budget-signal` - Token budget tracking
- `status-signal` - :active/:paused/:completed/:cancelled
- `spindel-ctx` - Spindel execution context
- `parent-ctx` / `child-ctxs` - Hierarchy

### 4.2 Forking Model

When agent A spawns agent B:

```clojure
;; B gets a forked context (O(1) CoW)
(let [child-ctx (fork-context parent-ctx {:budget 5000})]
  (run-agent-in-context child-ctx agent-b task))
```

**Visibility**:
- B sees a snapshot of A's context at spawn time
- B's changes are isolated (CoW semantics)
- B can write to shared signals if explicitly provided

### 4.3 Shared Signals for Group Communication

```clojure
;; Group creates shared message signal
(def shared-messages (signal (deltaable-vector [])))

;; All agents in group track this signal
(spin
  (let [messages (track shared-messages)]
    ;; React to new messages
    (when (needs-response? messages)
      (respond! ...))))
```

---

## Part 5: Implementation Structure

### 5.1 File Organization

```
src/dvergr/agent/
  core.clj        ; Agent record, make-agent
  primitives.clj  ; ask!, spawn!, tell!
  combinators.clj ; all, race, timeout (thin wrappers over spindel)

src/dvergr/group/
  core.clj        ; Group record, make-group
  operations.clj  ; add-agent!, remove-agent!, pause!, etc.
  behavior.clj    ; Behavior tree execution

src/dvergr/chat/
  context.clj     ; ChatContext (existing)
  agent.clj       ; Agent turn execution (existing)
  group.clj       ; Group chat coordination (refactor existing)
```

### 5.2 Agent Execution Flow

```clojure
(defn run-agent
  "Run an agent on a task. Returns a spin.

   The spin completes when agent finishes (success/failure/timeout)."
  [agent task {:keys [budget parent-ctx on-turn]}]
  (let [ctx (if parent-ctx
              (fork-context parent-ctx {:budget budget})
              (create-context {:budget budget}))]
    (spin
      (loop [turn 0]
        (let [status @(:status-signal ctx)]
          (cond
            ;; Check termination conditions
            (not= status :active) {:status status :turns turn}
            (>= turn (:max-turns agent)) {:status :max-turns :turns turn}
            (budget-exceeded? ctx) {:status :budget-exceeded :turns turn}

            ;; Run a turn
            :else
            (let [result (run-turn! ctx agent)]
              (when on-turn (on-turn {:turn turn :result result}))
              (case result
                :continue (recur (inc turn))
                :complete {:status :complete :turns (inc turn) :result (get-result ctx)}
                :error {:status :error :turns (inc turn)}))))))))

(defn ask!
  "Blocking call to agent. Returns result."
  ([agent task] (ask! agent task {}))
  ([agent task opts]
   (await (run-agent agent task opts))))

(defn spawn!
  "Non-blocking. Returns spin that can be awaited later."
  ([agent task] (spawn! agent task {}))
  ([agent task opts]
   (run-agent agent task opts)))
```

### 5.3 Group Execution Flow (Optional Declarative)

```clojure
(defn run-group
  "Run a group according to its behavior tree structure."
  [group task]
  (let [ctx (:context group)
        structure (:structure group)]
    ;; Add task to shared messages
    (add-message! ctx {:role :user :content task})

    ;; Execute according to structure
    (execute-structure structure ctx)))

(defmulti execute-structure
  "Execute a behavior tree node."
  (fn [node ctx] (if (keyword? node) node (first node))))

(defmethod execute-structure :parallel
  [[_ opts & children] ctx]
  (let [threshold (get opts :threshold (count children))
        results (await (apply all (map #(execute-structure % ctx) children)))
        successes (filter #(= :success (:status %)) results)]
    (if (>= (count successes) threshold)
      {:status :success :results results}
      {:status :failure :results results})))

(defmethod execute-structure :sequence
  [[_ & children] ctx]
  (loop [[child & rest] children
         results []]
    (if-not child
      {:status :success :results results}
      (let [result (await (execute-structure child ctx))]
        (if (= :success (:status result))
          (recur rest (conj results result))
          {:status :failure :results (conj results result)})))))

(defmethod execute-structure :action
  [[_ agent] ctx]
  ;; Run single agent
  (run-agent agent (get-current-task ctx) {:parent-ctx ctx}))
```

---

## Part 6: Admin and Observation

### 6.1 Admin Control

The admin (typically the parent context or user) can:

```clojure
;; Pause all agents in group
(pause! group)
; Sets status-signal to :paused
; Agents check status at turn boundaries and yield

;; Resume
(resume! group)

;; Cancel (forceful)
(cancel! group)
; Cancels all running spins

;; Modify participants
(add-agent! group (make-agent {:name "expert" ...}))
(remove-agent! group "underperforming-agent")

;; Modify structure
(set-structure! group [:sequence a b c])
```

### 6.2 Observation

```clojure
(watch! group
  :on-message (fn [msg] (println "Message:" (:content msg)))
  :on-turn (fn [{:keys [agent turn result]}] ...)
  :on-agent-complete (fn [{:keys [agent status]}] ...)
  :on-group-complete (fn [{:keys [status results]}] ...))
```

**Implementation**: Uses spindel's `track` on signals:

```clojure
(defn watch! [group handlers]
  (let [{:keys [on-message on-turn]} handlers]
    (spin
      (let [messages (track (:messages-signal (:context group)))]
        (when-let [new-msg (last-new-message messages)]
          (when on-message (on-message new-msg)))))))
```

---

## Part 7: Permissions and Boundaries

### 7.1 Permission Model

```clojure
;; Agent permissions
#{:spawn-agents    ; Can create sub-agents
  :admin           ; Can pause/cancel/modify groups
  :use-tools       ; Can use tools (default: true)
  :network         ; Can make network requests
  :filesystem}     ; Can read/write files

;; Check before spawning
(defn can-spawn? [agent]
  (contains? (:permissions agent) :spawn-agents))
```

### 7.2 Budget Boundaries

```clojure
;; Parent allocates budget to child
(ask! sub-agent task {:budget 5000})
; 5000 tokens deducted from parent's budget
; Child cannot exceed 5000

;; Unused budget returns to parent on completion
(complete-sub-context! child-ctx {:return-unused? true})
```

### 7.3 Depth Boundaries (Optional)

```clojure
;; Limit nesting depth
(make-agent {:name "worker"
             :max-depth 2  ; Can spawn 2 levels deep
             ...})

;; Check before spawning
(defn can-spawn-at-depth? [ctx agent]
  (let [current-depth (get-depth ctx)
        max-depth (:max-depth agent ##Inf)]
    (< current-depth max-depth)))
```

---

## Part 8: Examples

### 8.1 Simple Pipeline

```clojure
(def researcher (make-agent {:name "researcher" :max-turns 5}))
(def coder (make-agent {:name "coder" :max-turns 10}))
(def tester (make-agent {:name "tester" :max-turns 5}))

;; Sequential pipeline
(let [research (ask! researcher "research JWT best practices")
      code (ask! coder (str "implement JWT auth based on: " research))
      tests (ask! tester (str "test this code: " code))]
  (if (:passed? tests)
    {:status :complete :code code}
    {:status :failed :failures (:failures tests)}))
```

### 8.2 Parallel Research

```clojure
;; Multiple researchers in parallel, collect all perspectives
(let [perspectives (await (all
                            (ask! (make-agent {:name "security-expert"}) task)
                            (ask! (make-agent {:name "performance-expert"}) task)
                            (ask! (make-agent {:name "ux-expert"}) task)))]
  (synthesize perspectives))
```

### 8.3 Group Chat with Declarative Structure

```clojure
(def team (make-group
            {:name "dev-team"
             :participants [researcher coder-1 coder-2 tester]
             :structure [:sequence
                          [:action researcher]
                          [:parallel {:threshold 1}  ; first coder to finish
                            [:action coder-1]
                            [:action coder-2]]
                          [:action tester]]
             :budget 100000}))

(ask! team "implement user authentication")
```

### 8.4 Nested Groups (Agent Spawns Sub-Team)

```clojure
(def lead-agent
  (make-agent
    {:name "lead"
     :permissions #{:spawn-agents}
     :body (fn [task ctx]
             ;; Lead decides to delegate to sub-team
             (let [sub-team (make-group
                             {:participants [(make-agent {:name "sub-researcher"})
                                            (make-agent {:name "sub-coder"})]
                              :structure :parallel
                              :budget 20000})]
               ;; Ask sub-team and use result
               (let [sub-result (ask! sub-team "implement sub-feature")]
                 (finalize-result task sub-result))))}))

(ask! lead-agent "implement feature with delegation")
```

### 8.5 Monitored Group with Admin

```clojure
(def group (make-group {:participants [a b c] :budget 50000}))

;; Admin watches and intervenes
(watch! group
  :on-turn (fn [{:keys [agent turn]}]
             (when (> turn 10)
               (println "Agent" (:name agent) "taking too long")
               (prompt-agent! agent "Please wrap up soon")))
  :on-budget-warning (fn [{:keys [remaining]}]
                       (when (< remaining 5000)
                         (pause! group)
                         (println "Budget low, pausing for review"))))

(spawn! group "complex task")  ; Run in background

;; Admin can intervene later
(Thread/sleep 10000)
(add-agent! group (make-agent {:name "expert"}))
(resume! group)
```

---

## Part 9: Comparison with Other Systems

| Aspect | Dvergr | OpenCode | Clawdbot |
|--------|----------|----------|----------|
| Nesting | Recursive (bounded by budget/depth) | Flat (task disabled for subagents) | Flat (subagents can't spawn) |
| Coordination | Behavior tree | Parent-child session tree | Broadcast parallel/sequential |
| Communication | Signals + ask!/spawn! | Bus pub/sub | Hooks + events |
| Turn-taking | Explicit in tree structure | N/A (stateless subagents) | Strategy config |
| Observation | watch! + signal tracking | Bus.subscribe | Hooks system |

---

## Part 10: Implementation Roadmap

### Phase 1: Core Primitives (Plain Clojure)
- [ ] `make-agent` - Agent constructor with config (model, provider, permissions)
- [ ] `ask!`, `spawn!`, `tell!` - Communication primitives wrapping spindel
- [ ] `all`, `race`, `timeout` - Thin wrappers over spindel combinators
- [ ] Integrate with existing `chat/context.clj` forking model
- [ ] Basic turn loop with tool execution

### Phase 2: Group Abstraction
- [ ] `make-group` - Group constructor (workflow fn or declarative structure)
- [ ] `add-agent!`, `remove-agent!`, `pause!`, `resume!`
- [ ] Shared message signal for group communication
- [ ] Group-level budget tracking and allocation

### Phase 3: SCI Isolation Layer
See zeitlauf SCI integration docs for full details.

**Agent sandboxing:**
- [ ] Per-agent SCI context (isolated namespace)
- [ ] Per-group shared SCI context (for collaborative agents)
- [ ] `BoundaryTask` wrapper for native→SCI task interop
- [ ] Or: `SyncedRuntimeVar` for transparent binding sync

**Spindel in SCI:**
- [ ] Load partial-cps into SCI for CPS effects (`await`, `track`)
- [ ] Expose `make-spin`, `signal`, spindel combinators to SCI
- [ ] Expose runtime protocol methods (`get-state`, `swap-state!`)

**Tool isolation:**
- [ ] Code execution tool runs in agent's SCI context
- [ ] File/network tools have permission checks
- [ ] Tool results flow back through spindel signals

**Configuration options:**
```clojure
(make-agent {:name "coder"
             :isolation :sci        ; :sci | :native | :shared-sci
             :sci-context sci-ctx   ; optional pre-configured context
             :permissions #{:spawn-agents :code-execution}
             ...})

(make-group {:name "team"
             :isolation :shared-sci  ; all agents share SCI context
             :participants [...]})
```

### Phase 4: Optional Declarative Structure
- [ ] `execute-structure` multimethod for behavior tree interpretation
- [ ] `compile-structure` - convert declarative to workflow fn
- [ ] Structure introspection for admin tools

### Phase 5: Admin and Observation
- [ ] `watch!` - Observation handlers via signal tracking
- [ ] Admin control via status signals
- [ ] Budget propagation and return

### Phase 6: Testing and Refinement
- [ ] Unit tests for primitives
- [ ] Integration tests for composition patterns
- [ ] SCI isolation tests (sandbox escape prevention)
- [ ] Performance testing (native vs SCI overhead)
- [ ] Multi-agent scenarios with mixed isolation levels

---

## Part 11: SCI Integration Rationale

### Why SCI for Agents?

Even for agents that aren't primarily coding assistants, SCI provides value:

1. **Safe code execution**: Agents can write and run Clojure snippets safely
2. **Isolation between agents**: Each agent's state is sandboxed
3. **Clojure as reasoning language**: Agents can use Clojure for data manipulation,
   even during web search or research tasks
4. **Tool composition**: Agents can define custom tool pipelines in Clojure
5. **Forking**: O(1) CoW context forking works with SCI contexts

### Isolation Levels

| Level | Use Case | Performance | Safety |
|-------|----------|-------------|--------|
| `:native` | Trusted code, hot paths | Best | Lowest |
| `:sci` | Untrusted code, sandboxing | 5-10x overhead | High |
| `:shared-sci` | Collaborative group | 5-10x overhead | Medium (shared state) |

### SCI/Spindel Interaction

From zeitlauf research (SCI_INTEGRATION_FINDINGS.md):

```
SCI code → spindel spin → native runtime
     ↑                          ↓
     └──── BoundaryTask ←───────┘
```

**Key insight**: When partial-cps loads entirely in SCI, the CPS transformation
works correctly. `await` and `track` effects function properly because SCI's
`resolve` can see SCI symbols.

**Practical implication**: Agents running in SCI can use full spindel semantics:
- `spawn!` sub-agents
- `await` results
- `track` signals
- Fork contexts

### Agent SCI Context Setup

```clojure
(defn create-agent-sci-context
  "Create SCI context for an agent with spindel access."
  [runtime agent-config]
  (let [base-ctx (sci/init
                   {:namespaces
                    {'agent {'ask! ask!
                             'spawn! spawn!
                             'tell! tell!}
                     'spindel {'all parallel
                               'race race
                               'timeout timeout
                               'signal signal
                               'track track}}
                    :classes {:allow :all}})]  ; Configure per agent permissions

    ;; Load partial-cps for CPS effects
    (load-partial-cps-into-sci! base-ctx)

    ;; Bind runtime (via SyncedVar or wrapper approach)
    (bind-runtime-to-sci! base-ctx runtime)

    base-ctx))
```

### When to Use Each Isolation Level

**`:native`** - No SCI overhead
- System agents with fixed, trusted code
- Performance-critical inner loops
- Agents that don't execute dynamic code

**`:sci`** - Full isolation
- User-defined agents
- Agents that execute LLM-generated code
- Research/exploration tasks with unknown code

**`:shared-sci`** - Group collaboration
- Team of agents working on shared codebase
- Agents that need to share definitions
- Collaborative debugging/exploration

---

## Appendix: Spindel Integration Points

### Signals Used
- `messages-signal` - Deltaable vector of messages
- `status-signal` - Agent/group status
- `budget-signal` - Token budget tracking

### Effects Used
- `await` - Wait for spin completion
- `track` - React to signal changes
- `yield` - For async sequences (future: streaming)

### Combinators Used
- `parallel` - Concurrent execution
- `race` - First-to-complete
- `timeout` - Deadline racing
- `sleep` - Delays (for rate limiting)

### Context Operations
- `fork-context` - O(1) CoW for sub-agents
- `create-execution-context` - Fresh context
- `serialize-context` - Checkpointing

---

## Design Philosophy Summary

1. **Plain Clojure first**: Use `ask!`, `spawn!`, `all`, `race` with regular `let` bindings
2. **Spindel underneath**: All coordination runs as spindel spins
3. **DSL optional**: Declarative behavior tree structure only when needed for inspection/persistence
4. **Recursive but bounded**: Agents can spawn sub-agents, bounded by budget and depth
5. **Linguistic abstractions**: `ask!`, `spawn!`, `tell!` even if simple underneath

The goal is clarity over novelty - a programming model that's easy to understand and compose.

---

## References

**Dvergr docs:**
- `docs/AGENT_TIME_MODEL.md` - Independent time axes, synchronization
- `docs/AGENT_THEORY_SYNTHESIS.md` - Theoretical foundations

**Zeitlauf SCI integration:**
- `../zeitlauf/SCI_INTEGRATION_FINDINGS.md` - CPS transformation in SCI works
- `../zeitlauf/SCI_RUNTIME_BOUNDARY_DESIGN.md` - BoundaryTask wrapper approach
- `../zeitlauf/SCI_SYNCED_VAR_DESIGN.md` - SyncedRuntimeVar (transparent interop)
- `../zeitlauf/SCI_BENCHMARKING_NOTES.md` - Performance overhead analysis

**Related projects:**
- `grain/components/behavior-tree-v2/` - Behavior tree implementation
- `spindel/src/is/simm/spindel/spin/combinators.cljc` - Spindel combinators
- `sci/` - Small Clojure Interpreter
