# Agentic Workflows Design

**Date**: 2026-01-25
**Status**: 🎨 Design Exploration

## Overview

Design patterns for multi-agent workflows using dvergr primitives (ask!/spawn!/tell!) and spindel composition (await/parallel/race).

## Core Patterns

### Pattern 1: Sequential Pipeline

**Use Case**: Multi-stage processing where each stage depends on previous.

```clojure
(defn research-implement-test [topic]
  (spin
    (let [;; Stage 1: Research
          research (await (spawn! researcher
                            (str "Research " topic)))

          ;; Stage 2: Implementation (depends on research)
          code (await (spawn! coder
                        (str "Implement based on: "
                             (extract-result research))))

          ;; Stage 3: Testing (depends on code)
          tests (await (spawn! tester
                         (str "Test: "
                              (extract-result code))))]

      {:research research
       :code code
       :tests tests})))
```

**Characteristics**:
- Linear dependency chain
- Each stage blocks on previous
- Clear data flow
- Easy to debug

**When to use**:
- Clear stage dependencies
- Each stage needs full previous output
- Total time = sum of stage times

### Pattern 2: Parallel Fan-Out

**Use Case**: Independent tasks that can run concurrently.

```clojure
(defn parallel-research [topics]
  (spin
    (let [;; Spawn all in parallel
          spins (map #(spawn! researcher (str "Research " %))
                     topics)

          ;; Await all results
          results (await (parallel spins))]

      ;; Aggregate
      {:topics topics
       :results results
       :summary (synthesize results)})))
```

**Characteristics**:
- No inter-agent dependencies
- Maximum parallelism
- Reduced total time
- Results arrive together

**When to use**:
- Independent subtasks
- Time-sensitive (want results fast)
- Aggregation step at end

### Pattern 3: Competitive Race

**Use Case**: Multiple approaches, first winner wins.

```clojure
(defn fastest-implementation [spec]
  (spin
    (let [;; Different agents, different approaches
          impl-a (spawn! fast-coder "Quick implementation")
          impl-b (spawn! thorough-coder "Thorough implementation")
          impl-c (spawn! creative-coder "Creative implementation")

          ;; First to complete wins
          winner (await (race impl-a impl-b impl-c))]

      {:winner (:agent winner)
       :implementation winner})))
```

**Characteristics**:
- Redundant work
- Fast time to first result
- Quality uncertain (fastest ≠ best)

**When to use**:
- Time critical
- Multiple valid approaches
- Can validate/test winner quickly

### Pattern 4: Iterative Refinement

**Use Case**: Agent produces output, critic reviews, iterate until acceptable.

```clojure
(defn iterative-refinement [task max-iterations]
  (spin
    (loop [iteration 0
           current-output nil]

      (if (>= iteration max-iterations)
        {:status :max-iterations :output current-output}

        (let [;; Producer creates/refines
              production (await (spawn! producer
                                  (if current-output
                                    (str "Refine based on feedback: "
                                         current-output)
                                    (str "Initial attempt: " task))))

              ;; Critic evaluates
              critique (await (spawn! critic
                                (str "Evaluate: "
                                     (extract-result production))))]

          ;; Check if acceptable
          (if (acceptable? critique)
            {:status :success
             :output production
             :iterations (inc iteration)}

            ;; Continue refining
            (recur (inc iteration)
                   (extract-result production))))))))
```

**Characteristics**:
- Feedback loop
- Quality improves over iterations
- Bounded by max iterations
- Two roles: producer + critic

**When to use**:
- Quality matters more than speed
- Objective criteria for "good enough"
- Improvement possible via feedback

### Pattern 5: Hierarchical Delegation

**Use Case**: Manager agent coordinates specialized sub-agents.

```clojure
(defn hierarchical-workflow [project-spec]
  (spin
    (let [;; Manager analyzes and delegates
          plan (await (spawn! manager
                        (str "Break down project: " project-spec)))

          ;; Extract subtasks from plan
          subtasks (parse-subtasks (extract-result plan))

          ;; Assign to specialists
          specialist-results
          (await (parallel
                   (for [{:keys [task agent-type]} subtasks]
                     (spawn! (get-specialist agent-type) task))))

          ;; Manager integrates
          integration (await (spawn! manager
                               (str "Integrate results: "
                                    specialist-results)))]

      {:plan plan
       :subtask-results specialist-results
       :final integration})))
```

**Characteristics**:
- Centralized coordination
- Specialized agents
- Manager makes decisions
- Hierarchical structure

**When to use**:
- Complex projects with distinct phases
- Different expertise needed
- Clear integration step

### Pattern 6: Peer Collaboration (Group)

**Use Case**: Agents working together on shared context.

```clojure
(defn peer-collaboration [problem]
  (let [;; Create group with shared context
        group (make-group
                {:name "design-team"
                 :participants [architect designer coder]
                 :isolation :shared-sci})]

    (spin
      (let [;; Parallel brainstorming
            initial-ideas (await (ask-group! group
                                   (str "Brainstorm solutions for: " problem)))

            ;; Each agent can see others' ideas
            ;; Consensus emerges via shared context

            ;; Final synthesis
            synthesis (await (ask-group! group
                               "Synthesize best approach"))]

        {:ideas initial-ideas
         :synthesis synthesis}))))
```

**Characteristics**:
- Shared state/context
- Peer relationships (no hierarchy)
- Emergent consensus
- Collaborative refinement

**When to use**:
- Design/brainstorming tasks
- Benefit from diverse perspectives
- Shared vocabulary/definitions helpful

### Pattern 7: Adaptive Pipeline

**Use Case**: Workflow adjusts based on intermediate results.

```clojure
(defn adaptive-pipeline [input]
  (spin
    (let [;; Initial analysis
          analysis (await (spawn! analyzer "Analyze input"))

          complexity (:complexity (extract-result analysis))

          ;; Adapt based on complexity
          result (cond
                   ;; Simple: single agent
                   (< complexity 0.3)
                   (await (spawn! simple-agent "Quick solution"))

                   ;; Medium: parallel specialists
                   (< complexity 0.7)
                   (let [results (await (parallel
                                          (spawn! specialist-a "Part A")
                                          (spawn! specialist-b "Part B")))]
                     (await (spawn! integrator "Combine results")))

                   ;; Complex: hierarchical with review
                   :else
                   (iterative-refinement "Complex solution" 5))]

      {:complexity complexity
       :approach (cond
                   (< complexity 0.3) :simple
                   (< complexity 0.7) :parallel
                   :else :hierarchical)
       :result result})))
```

**Characteristics**:
- Dynamic structure
- Runtime decisions
- Optimizes cost/quality tradeoff
- Complexity-aware

**When to use**:
- Variable input complexity
- Want to optimize resources
- Multiple strategies available

### Pattern 8: Monitoring with Intervention

**Use Case**: Observer monitors worker, intervenes if needed.

```clojure
(defn monitored-execution [task]
  (spin
    (let [;; Start worker
          worker-spin (spawn! worker task)

          ;; Monitor progress
          monitor-spin (spawn! monitor
                         (str "Monitor worker on: " task))

          ;; Race: worker completion vs monitor timeout
          result (await (race
                          worker-spin
                          (timeout monitor-spin 60000 :timeout)))]

      (case (:status result)
        ;; Worker completed
        :complete result

        ;; Monitor timed out - intervention needed
        :timeout (do
                   (println "Worker timeout, trying alternative")
                   (await (spawn! backup-agent task)))

        ;; Monitor flagged issue
        :flagged (do
                   (println "Monitor flagged issue")
                   (await (spawn! expert-agent
                            (str "Fix issue: " result))))))))
```

**Characteristics**:
- Continuous monitoring
- Intervention on issues
- Timeout handling
- Fallback strategies

**When to use**:
- Reliability critical
- Agents may get stuck
- Fallback strategies exist

## Composition Principles

### 1. Spins All the Way Down

Every workflow returns a Spin:

```clojure
;; Primitive
(defn simple-task [x]
  (spawn! agent task))  ; Returns Spin

;; Composite
(defn complex-workflow [x]
  (spin  ; Returns Spin
    (let [a (await (simple-task x))
          b (await (simple-task a))]
      b)))

;; Usage is uniform
(await (simple-task x))
(await (complex-workflow x))
```

**Benefits**:
- Uniform interface
- Easy composition
- Can await/parallel/race anything

### 2. Let Bindings for Sequencing

Natural Clojure `let` for sequential dependencies:

```clojure
(spin
  (let [a (await (task-1))
        b (await (task-2 a))
        c (await (task-3 a b))]
    {:a a :b b :c c}))
```

Dependencies are clear from lexical scope.

### 3. Combinators for Coordination

Use spindel combinators explicitly:

```clojure
;; Parallel
(await (parallel spin-a spin-b spin-c))

;; Race
(await (race fast-approach slow-approach))

;; Timeout
(await (timeout long-task 30000 :timeout))
```

Make coordination explicit in code.

### 4. Higher-Order Workflows

Workflows that create workflows:

```clojure
(defn make-pipeline [stages]
  (fn [input]
    (spin
      (loop [data input
             [stage & rest-stages] stages]
        (if stage
          (let [result (await (spawn! stage data))]
            (recur (extract-result result) rest-stages))
          data)))))

;; Create specific pipeline
(def research-pipeline
  (make-pipeline [analyzer researcher coder tester]))

;; Use it
(await (research-pipeline "Build auth system"))
```

**Benefits**:
- Reusable patterns
- Parameterized workflows
- Easy to test/modify

## Error Handling Patterns

### Pattern 1: Graceful Degradation

```clojure
(defn with-fallback [primary-spin fallback-spin]
  (spin
    (let [result (await primary-spin)]
      (if (successful? result)
        result
        (do
          (println "Primary failed, trying fallback")
          (await fallback-spin))))))
```

### Pattern 2: Retry with Backoff

```clojure
(defn retry-with-backoff [task-fn max-retries]
  (spin
    (loop [attempt 0]
      (let [result (await (task-fn))]
        (cond
          (successful? result) result

          (>= attempt max-retries)
          (throw (ex-info "Max retries exceeded" {:result result}))

          :else
          (do
            (await (sleep (* 1000 (Math/pow 2 attempt))))
            (recur (inc attempt))))))))
```

### Pattern 3: Partial Success

```clojure
(defn parallel-with-partial-success [spins]
  (spin
    (let [results (await (parallel spins))

          successes (filter successful? results)
          failures (remove successful? results)]

      (if (empty? successes)
        {:status :total-failure :failures failures}

        {:status :partial-success
         :successes successes
         :failures failures
         :success-rate (/ (count successes)
                          (count results))}))))
```

## Resource Management

### Budget Tracking

```clojure
(defn with-budget-tracking [workflow budget]
  (spin
    (let [start-budget @budget-atom
          result (await workflow)
          end-budget @budget-atom
          used (- start-budget end-budget)]

      (assoc result
        :budget-used used
        :budget-remaining end-budget))))
```

### Concurrent Agent Limits

```clojure
(defn with-concurrency-limit [spins max-concurrent]
  (spin
    (let [semaphore (atom max-concurrent)

          acquire! (fn []
                     (loop []
                       (let [current @semaphore]
                         (if (pos? current)
                           (if (compare-and-set! semaphore current (dec current))
                             :acquired
                             (recur))
                           (do
                             (await (sleep 100))
                             (recur))))))

          release! (fn []
                     (swap! semaphore inc))

          wrapped-spins
          (map (fn [s]
                 (spin
                   (acquire!)
                   (try
                     (await s)
                     (finally
                       (release!)))))
               spins)]

      (await (parallel wrapped-spins)))))
```

## Observability

### Progress Tracking

```clojure
(defn with-progress [workflow progress-atom]
  (spin
    (reset! progress-atom {:status :starting})

    (let [result (await workflow)]
      (reset! progress-atom {:status :complete :result result})
      result)))
```

### Event Logging

```clojure
(defn with-event-log [workflow event-log]
  (spin
    (swap! event-log conj {:type :start :time (System/currentTimeMillis)})

    (let [result (await workflow)]
      (swap! event-log conj {:type :complete :time (System/currentTimeMillis) :result result})
      result)))
```

## Real-World Workflow Examples

### Example 1: Code Review Workflow

```clojure
(defn code-review-workflow [pr-url]
  (spin
    ;; Parallel analysis
    (let [[style security tests] (await (parallel
                                          (spawn! style-checker pr-url)
                                          (spawn! security-scanner pr-url)
                                          (spawn! test-analyzer pr-url)))

          ;; Aggregate issues
          all-issues (concat (:issues style)
                             (:issues security)
                             (:issues tests))

          ;; Human-friendly summary
          summary (await (spawn! summarizer
                           (str "Summarize issues: " all-issues)))]

      {:style style
       :security security
       :tests tests
       :summary summary
       :approved? (empty? (filter :blocking all-issues))})))
```

### Example 2: Documentation Generation

```clojure
(defn generate-documentation [codebase-path]
  (spin
    ;; Analyze codebase
    (let [structure (await (spawn! code-analyzer
                             (str "Analyze structure: " codebase-path)))

          ;; Generate different doc types in parallel
          [api-docs tutorial examples] (await (parallel
                                                (spawn! api-doc-generator structure)
                                                (spawn! tutorial-writer structure)
                                                (spawn! example-generator structure)))

          ;; Combine into cohesive documentation
          final-docs (await (spawn! doc-integrator
                              {:api api-docs
                               :tutorial tutorial
                               :examples examples}))]

      final-docs)))
```

### Example 3: Multi-Language Translation

```clojure
(defn translate-documentation [doc target-languages]
  (spin
    ;; Translate to all languages in parallel
    (let [translations (await (parallel
                                (for [lang target-languages]
                                  (spawn! translator
                                    {:doc doc :target-lang lang}))))

          ;; QA check translations
          qa-results (await (parallel
                              (for [[lang translation] (zipmap target-languages translations)]
                                (spawn! qa-checker
                                  {:original doc
                                   :translation translation
                                   :language lang}))))

          ;; Revise any failed QA
          final-translations
          (for [[lang translation qa] (map vector target-languages translations qa-results)]
            (if (:passed? qa)
              translation
              (await (spawn! revisor
                       {:translation translation
                        :issues (:issues qa)}))))]

      (zipmap target-languages final-translations))))
```

## Next Steps

1. **Prototype Key Patterns**: Implement 3-4 patterns with real agents
2. **Measure Performance**: Parallel vs sequential timing
3. **Error Scenarios**: Test failure/retry patterns
4. **Observability**: Add progress tracking to examples
5. **Documentation**: Create pattern catalog

## References

- `src/dvergr/agent/primitives.clj` - ask!/spawn!/tell!
- `src/dvergr/agent/combinators.clj` - parallel/race/timeout
- `examples/phase_1_2_demo.clj` - Basic composition examples
- `docs/AGENT_COMPOSITION_DESIGN.md` - Theoretical foundations
