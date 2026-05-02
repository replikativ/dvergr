# Nested Execution Contexts: A Lattice-Based Design

## Core Insight

**Inside a spin, computation is one-shot/sequential. From outside, it updates incrementally.**

This document formalizes this two-level structure using:
- Multi-dimensional lattice timestamps (à la Naiad)
- Modal staging levels (à la S4/MetaML)
- Graded execution contexts

---

## 1. The Fundamental Distinction

### One-Shot vs Incremental

```
┌─────────────────────────────────────────────────────────────┐
│ OUTER CONTEXT (Reactive/Incremental)                        │
│                                                             │
│   Signals change → Spins re-execute → Deltas propagate     │
│                                                             │
│   ┌─────────────────────────────────────────────────────┐   │
│   │ INNER CONTEXT (One-Shot/Sequential)                 │   │
│   │                                                     │   │
│   │   (spin                                             │   │
│   │     (let [x (fetch-data)      ; happens once        │   │
│   │           y (compute x)        ; happens once       │   │
│   │           z (transform y)]     ; happens once       │   │
│   │       z))                                           │   │
│   │                                                     │   │
│   │   From inside: imperative, sequential, one-shot     │   │
│   └─────────────────────────────────────────────────────┘   │
│                         ↓                                   │
│   From outside: this spin is a node that may re-execute    │
│   when its dependencies change                              │
└─────────────────────────────────────────────────────────────┘
```

### The Nesting Case

A spin can internally run another spindel context:

```clojure
(spin :outer
  (let [;; Create nested reactive context
        inner-ctx (create-execution-context)

        ;; Run reactive computation inside
        result (with-context inner-ctx
                 (let [sig-a (signal 1)
                       sig-b (signal 2)
                       sig-sum (spin (+ @sig-a @sig-b))]
                   ;; Inner context is reactive
                   (reset! sig-a 10)
                   ;; sig-sum recomputes to 12
                   @sig-sum))]

    ;; From outer's perspective: inner-ctx ran once
    ;; and produced result 12
    result))
```

**Key question**: How do we track time/versions across these levels?

---

## 2. Multi-Dimensional Lattice Timestamps

### Naiad's Insight

Naiad uses timestamps of the form `(epoch, ⟨c₀, c₁, ..., cₖ⟩)` where:
- `epoch` = external version/input batch
- `⟨cᵢ⟩` = loop iteration counters at each nesting level

We generalize this for execution contexts:

### Spindel Timestamp Structure

```clojure
(defrecord Timestamp
  [version      ; External version (signal changes from parent)
   levels])     ; Vector of level-specific counters

;; Example timestamps:
;; [3, []]           - Version 3, no nesting
;; [3, [2]]          - Version 3, inner loop iteration 2
;; [3, [2, 5]]       - Version 3, outer loop iter 2, inner loop iter 5
;; [4, [0]]          - Version 4 (parent changed), inner reset to 0
```

### Timestamp Ordering (Partial Order)

```clojure
(defn ts-leq?
  "Timestamp partial order: t1 ≤ t2 iff t1 could-have-caused t2"
  [t1 t2]
  (and
    ;; Version must be ≤
    (<= (:version t1) (:version t2))
    ;; Levels must be prefix-compatible and ≤
    (levels-leq? (:levels t1) (:levels t2))))

(defn levels-leq? [l1 l2]
  (cond
    (empty? l1) true  ; [] ≤ anything
    (empty? l2) false ; [x...] ≰ []
    :else (and (<= (first l1) (first l2))
               (levels-leq? (rest l1) (rest l2)))))

;; Examples:
;; [3, [2]] ≤ [3, [2, 5]]     ✓ (same version, prefix)
;; [3, [2]] ≤ [4, [0]]        ✓ (earlier version)
;; [3, [2, 5]] ≤ [3, [2]]     ✗ (not a prefix)
;; [3, [2]] ≤ [3, [3]]        ✓ (same version, earlier iter)
;; [3, [2]] ∥ [3, [1, 5]]     incomparable (different paths)
```

### Why Partial Order Matters

**Total order** (traditional): All events linearly ordered, must process in sequence.

**Partial order** (differential): Events on independent paths can be processed in parallel. Only causally-related events need ordering.

```
        [3, []]
       /       \
  [3, [0]]    [3, [1]]     ← These are incomparable!
      |           |           Can process in parallel
  [3, [0,0]]  [3, [1,0]]
      |           |
  [3, [0,1]]  [3, [1,1]]
       \       /
        [4, []]            ← New version joins all paths
```

---

## 3. Execution Context Levels

### Level Structure

```clojure
(defrecord ExecutionLevel
  [level-id        ; Unique identifier for this level
   parent-level    ; Reference to enclosing level (nil for root)
   iteration       ; Current iteration counter at this level
   context])       ; The ExecutionContext for this level

(defrecord LeveledExecutionContext
  [root-level      ; The outermost level
   current-level   ; Currently executing level
   timestamp       ; Current Timestamp
   version-clock]) ; Monotonic version counter
```

### Entering/Exiting Levels

```clojure
(defn enter-level!
  "Enter a new nesting level (e.g., for nested loop or nested context)"
  [ctx]
  (let [current (:current-level ctx)
        new-level (->ExecutionLevel
                    (gensym "level")
                    current
                    0  ; Start iteration at 0
                    (create-inner-context current))]
    (-> ctx
        (assoc :current-level new-level)
        (update-in [:timestamp :levels] conj 0))))

(defn exit-level!
  "Exit current nesting level, return to parent"
  [ctx]
  (let [current (:current-level ctx)
        parent (:parent-level current)]
    (-> ctx
        (assoc :current-level parent)
        (update-in [:timestamp :levels] pop))))

(defn advance-iteration!
  "Increment iteration counter at current level"
  [ctx]
  (let [levels (:levels (:timestamp ctx))
        n (count levels)]
    (if (zero? n)
      ;; At root level, increment version
      (update-in ctx [:timestamp :version] inc)
      ;; At nested level, increment innermost counter
      (update-in ctx [:timestamp :levels (dec n)] inc))))

(defn advance-version!
  "New external input/change - increment version, reset levels"
  [ctx]
  (-> ctx
      (update-in [:timestamp :version] inc)
      (assoc-in [:timestamp :levels]
                (vec (repeat (count (get-in ctx [:timestamp :levels])) 0)))))
```

---

## 4. The Modal/Staging Interpretation

### S4 Modal Correspondence

| Modal Logic | Spindel | Meaning |
|-------------|---------|---------|
| □A | `(signal A)` | A is stably available (persists across versions) |
| ◇A | `(deferred A)` | A will be available (computed on demand) |
| Level 0 | Inner spin body | One-shot computation |
| Level 1 | Outer reactive context | Incremental updates |
| □□A | Nested signal | Doubly-stable value |

### Code Quoting Analogy

```clojure
;; MetaML-style staging
'(+ 1 2)           ; Quoted code (not yet executed)
(eval '(+ 1 2))    ; Execute quoted code → 3

;; Spindel-style staging
(spin (+ 1 2))     ; Reactive computation (may re-execute)
@(spin (+ 1 2))    ; Dereference to get current value → 3

;; Nesting
(spin                           ; Level 1: reactive
  (let [inner-ctx ...]          ; Level 0: one-shot setup
    (with-context inner-ctx     ; Enter Level 2
      (spin (+ @a @b)))))       ; Level 2: reactive within Level 1
```

### When to Collapse Levels

Following Nada Amin's Pink/Purple insight:

**Collapse when**:
- Inner context completes without side effects to outer
- Semantics are "stable" (no reflection/modification)

**Keep levels when**:
- Inner context modifies signals visible to outer
- Need to track provenance across levels
- Debugging/observability requires level information

```clojure
(defn should-collapse? [inner-ctx outer-ctx]
  (let [inner-writes (get-written-signals inner-ctx)
        outer-visible (get-visible-signals outer-ctx)]
    ;; Collapse if inner writes don't affect outer
    (empty? (set/intersection inner-writes outer-visible))))
```

---

## 5. Delta Propagation Across Levels

### The Interval Structure

From our earlier work, an Interval captures `{old, new, deltas}`:

```clojure
(defrecord LeveledInterval
  [old-value       ; Value before change
   new-value       ; Value after change
   deltas          ; List of delta operations
   timestamp-old   ; Timestamp of old value
   timestamp-new]) ; Timestamp of new value
```

### Propagation Rules

**Within a level** (horizontal):
```
Signal A changes at [v, [i]]
  → Dependent spin B re-executes
  → B's result changes at [v, [i]] (same timestamp)
```

**Across levels** (vertical):
```
Inner context completes at [v, [i, j]]
  → Result propagates to outer at [v, [i]]
  → Outer sees single delta, not inner iterations
```

**Version bump** (external change):
```
External input changes
  → Version increments: v → v+1
  → All level counters reset: [i, j, k] → [0, 0, 0]
  → Full re-execution from root
```

### Integration Function

```clojure
(defn integrate-inner-result
  "Integrate result from inner context into outer context.

   The inner context may have gone through many iterations,
   but outer only sees the final result as a single delta."
  [outer-ctx inner-ctx inner-result]
  (let [;; Inner's full history
        inner-start-ts (:timestamp-at-entry inner-ctx)
        inner-end-ts (:timestamp inner-ctx)

        ;; Outer sees single step
        outer-delta (->LeveledInterval
                      nil  ; No old value (first observation)
                      inner-result
                      [{:op :set :value inner-result}]
                      inner-start-ts
                      ;; Collapse inner timestamps to outer level
                      (collapse-timestamp inner-end-ts))]

    ;; Propagate to outer's dependents
    (propagate-delta! outer-ctx outer-delta)))

(defn collapse-timestamp
  "Remove innermost level from timestamp"
  [ts]
  (update ts :levels pop))
```

---

## 6. Concrete API Design

### Creating Nested Contexts

```clojure
(defn create-nested-context
  "Create a new execution context nested within current.

   The nested context:
   - Has its own signal graph
   - Tracks its own iteration counter
   - Can be executed incrementally from within
   - Appears as single computation from outside"
  [parent-ctx & {:keys [inherit-signals? budget]}]
  (let [level (enter-level! parent-ctx)
        nested-ctx (->LeveledExecutionContext
                     (:root-level parent-ctx)
                     level
                     (->Timestamp (:version (:timestamp parent-ctx))
                                  (conj (:levels (:timestamp parent-ctx)) 0))
                     (atom 0))]

    ;; Optionally inherit parent signals (read-only)
    (when inherit-signals?
      (inherit-signals-from! nested-ctx parent-ctx))

    ;; Set budget if provided
    (when budget
      (set-budget! nested-ctx budget))

    nested-ctx))
```

### Running Nested Context

```clojure
(defmacro with-nested-context
  "Execute body in a nested context.

   From inside body: reactive execution with signals/spins
   From outside: single computation that returns result"
  [opts & body]
  `(let [parent-ctx# rtc/*execution-context*
         nested-ctx# (create-nested-context parent-ctx# ~@opts)]
     (binding [rtc/*execution-context* nested-ctx#]
       (let [result# (do ~@body)]
         ;; Integrate result back to parent
         (integrate-inner-result parent-ctx# nested-ctx# result#)
         result#))))

;; Usage:
(spin :outer
  (let [data (fetch-external-data)]
    (with-nested-context {:budget 1000}
      ;; This whole block is "one computation" from outer's view
      ;; But internally it's fully reactive
      (let [items (signal (parse-items data))
            processed (spin (map process @items))
            filtered (spin (filter valid? @processed))]
        ;; Can iterate internally
        (dotimes [_ 3]
          (swap! items optimize))
        @filtered))))
```

### Explicit Level Management

```clojure
(defn run-to-fixpoint
  "Run nested context until no more changes (fixpoint).

   Useful for iterative algorithms where inner context
   should stabilize before returning to outer."
  [nested-ctx body-fn]
  (loop [iteration 0]
    (let [before-ts (:timestamp nested-ctx)
          result (body-fn)
          after-ts (:timestamp nested-ctx)]
      (if (= before-ts after-ts)
        ;; No changes - fixpoint reached
        result
        ;; Changes occurred - iterate
        (do
          (advance-iteration! nested-ctx)
          (recur (inc iteration)))))))

;; Usage for iterative algorithms:
(spin :pagerank
  (with-nested-context {:inherit-signals? true}
    (run-to-fixpoint *execution-context*
      (fn []
        ;; PageRank iteration
        (doseq [node @nodes]
          (swap! (get ranks node)
                 (fn [_] (compute-rank node @edges @ranks))))
        @ranks))))
```

---

## 7. Timestamp Lattice Operations

### Join (Least Upper Bound)

```clojure
(defn ts-join
  "Compute join (lub) of two timestamps.
   Used when merging results from parallel branches."
  [t1 t2]
  (->Timestamp
    (max (:version t1) (:version t2))
    (levels-join (:levels t1) (:levels t2))))

(defn levels-join [l1 l2]
  (let [n (max (count l1) (count l2))]
    (vec (for [i (range n)]
           (max (get l1 i 0) (get l2 i 0))))))

;; Example:
;; join([3, [2, 1]], [3, [1, 5]]) = [3, [2, 5]]
```

### Meet (Greatest Lower Bound)

```clojure
(defn ts-meet
  "Compute meet (glb) of two timestamps.
   Represents the latest point that could-have-caused both."
  [t1 t2]
  (->Timestamp
    (min (:version t1) (:version t2))
    (levels-meet (:levels t1) (:levels t2))))

(defn levels-meet [l1 l2]
  (let [n (min (count l1) (count l2))]
    (vec (for [i (range n)]
           (min (get l1 i) (get l2 i))))))
```

### Frontier Tracking

```clojure
(defn update-frontier!
  "Track the frontier of processed timestamps.

   A timestamp t is 'complete' when all t' ≤ t have been processed.
   The frontier is the set of maximal complete timestamps."
  [ctx new-ts]
  (swap! (:frontier ctx)
         (fn [frontier]
           ;; Add new-ts, remove any dominated by it
           (let [not-dominated (remove #(ts-leq? % new-ts) frontier)]
             (if (some #(ts-leq? new-ts %) frontier)
               frontier  ; new-ts already dominated
               (conj (set not-dominated) new-ts))))))

(defn can-emit?
  "Check if result at timestamp t can be emitted to outer level.

   Can emit when all predecessors are complete."
  [ctx t]
  (every? #(ts-leq? % t) @(:frontier ctx)))
```

---

## 8. Integration with Existing Spindel

### Modified ExecutionContext

```clojure
(defrecord ExecutionContext
  [;; Existing fields
   fork-id
   parent-ctx
   overlay-atom
   executor

   ;; New: Leveled execution
   level-info        ; ExecutionLevel record
   timestamp         ; Current Timestamp
   frontier          ; Atom of frontier timestamps

   ;; New: Nested context tracking
   nested-contexts   ; Map of nested context ids → contexts
   integration-mode  ; :eager | :lazy | :manual
   ])
```

### Modified Signal

```clojure
(defrecord Signal
  [;; Existing fields
   id
   value-atom
   dependencies
   dependents

   ;; New: Timestamp tracking
   last-write-ts     ; Timestamp of last write
   write-history     ; Optional: history of (ts, value) pairs
   level-scope])     ; Which level this signal is visible at
```

### Modified Spin

```clojure
(defn spin [& body]
  ;; Existing spin logic plus:
  ;; 1. Record timestamp at start
  ;; 2. Track level during execution
  ;; 3. Record timestamp at end
  ;; 4. Emit delta with timestamp range
  ...)
```

---

## 9. Example: Agent with Nested Reasoning

```clojure
(defn agent-turn [agent-ctx message]
  (spin :agent-turn
    ;; Outer level: agent turn (one-shot from chat's view)
    (let [parsed (parse-message message)]

      ;; Nested level: reasoning loop (reactive internally)
      (with-nested-context {:budget 5000 :inherit-signals? true}
        (let [;; Reasoning signals
              hypothesis (signal nil)
              evidence (signal [])
              confidence (spin (compute-confidence @hypothesis @evidence))]

          ;; Iterative refinement (multiple iterations)
          (run-to-fixpoint *execution-context*
            (fn []
              ;; Generate hypothesis
              (reset! hypothesis (generate-hypothesis parsed @evidence))

              ;; Gather evidence
              (swap! evidence conj (search-knowledge @hypothesis))

              ;; Check if confident enough
              (when (< @confidence 0.9)
                (advance-iteration! *execution-context*))

              {:hypothesis @hypothesis
               :confidence @confidence}))))

      ;; Back to outer level: emit response
      ;; Chat sees single "agent responded" event
      (format-response @hypothesis))))
```

From the chat's perspective:
- Agent turn is a single event at timestamp `[v, []]`
- Internally, reasoning went through iterations `[v, [0]], [v, [1]], [v, [2]]...`
- Those inner iterations are invisible to chat

---

## 10. Summary

### Key Design Decisions

1. **Timestamps are tuples**: `[version, [level-counters...]]`
2. **Partial order**: Enables parallel processing of independent paths
3. **Levels track nesting**: Each nested context adds a counter
4. **Integration collapses levels**: Inner iterations invisible to outer
5. **Frontiers track completion**: Know when to emit to parent

### Categorical Interpretation

| Concept | Category Theory | Spindel |
|---------|-----------------|---------|
| Timestamp | Object in lattice category | `Timestamp` record |
| Delta | Morphism | `LeveledInterval` |
| Version bump | Functor between fibers | `advance-version!` |
| Level nesting | Enrichment | `enter-level!` / `exit-level!` |
| Integration | Colimit | `integrate-inner-result` |
| Collapse | Adjunction counit | `collapse-timestamp` |

### Implementation Priority

1. **Phase 1**: Add `Timestamp` and `ExecutionLevel` to context
2. **Phase 2**: Implement `enter-level!` / `exit-level!` / `advance-*`
3. **Phase 3**: Add `with-nested-context` macro
4. **Phase 4**: Implement frontier tracking and `can-emit?`
5. **Phase 5**: Integration with existing signal propagation

---

## References

- [Naiad: A Timely Dataflow System](https://sigops.org/s/conferences/sosp/2013/papers/p439-murray.pdf)
- [Differential Dataflow](https://github.com/TimelyDataflow/differential-dataflow)
- [Collapsing Towers of Interpreters](https://www.cs.purdue.edu/homes/rompf/papers/amin-popl18.pdf)
- [A 2-Categorical Study of Graded and Indexed Monads](https://arxiv.org/abs/1904.08083)
- [Staged Compilation with Two-Level Type Theory](https://www.researchgate.net/publication/363159932_Staged_compilation_with_two-level_type_theory)
