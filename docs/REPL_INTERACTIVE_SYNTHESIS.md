# REPL Interactive Code Synthesis

**Date**: 2026-01-26
**Status**: 🎨 Design Exploration

## Overview

Design for interactive code synthesis from the REPL where the human programmer:
1. **Writes actual code** in native mode (fast, trusted)
2. **Spawns agents** for experiments, research, and collaborative tasks
3. **Manages state sharing** via datahike branching (what agents can see/modify)
4. **Merges agent work** back into main project with review
5. **Maintains discipline** through tests, pure functions, and isolation

This explores the **dual role** of the programmer: direct coder + agent orchestrator.

## Core Insight: Branching for Everything

**Key**: Both human and agents work on **branches**. The main branch is just another branch.

```
┌─────────────────────────────────────────────────────────────┐
│                   Yggdrasil World (CoW)                     │
│                                                              │
│  branch:main (human)                                         │
│  ├── Source code (src/...)                                   │
│  ├── REPL history (datahike)                                 │
│  ├── Application state (datoms)                              │
│  └── Tests (test/...)                                        │
│                                                              │
│  branch:agent-refactor-auth (agent 1 - experiment)          │
│  ├── Fork from main                                          │
│  ├── Agent can read main's history                           │
│  ├── Agent's experiments isolated                            │
│  └── Merge back when done                                    │
│                                                              │
│  branch:research-jwt-libs (agent 2 - background research)   │
│  ├── Fork from main                                          │
│  ├── Read-only tools (no write)                              │
│  └── Results stored in datahike                              │
│                                                              │
│  branch:experiment-optimizations (human - speculative)      │
│  ├── Fork from main                                          │
│  ├── Human's own experiments                                 │
│  └── Discard or merge                                        │
└─────────────────────────────────────────────────────────────┘
```

## REPL Workflow Patterns

### Pattern 1: Direct Development (Native Mode)

**Use case**: Human writes code directly in main branch.

```clojure
;; Start REPL in main branch
(require '[dvergr.repl :as repl])

(def rt (repl/start-session
          {:branch :main
           :cwd "/home/user/myproject"
           :isolation :native}))  ; Fast, trusted

;; Work directly - no overhead
(defn my-function [x]
  (* x 2))

;; Run tests
(require '[clojure.test :refer [run-tests]])
(run-tests 'myapp.core-test)

;; Code is immediately in main branch
;; Auto-indexed via rewrite-clj
;; REPL history in datahike
```

**Characteristics**:
- Fast (JVM, not SCI)
- Full Clojure semantics
- Direct file writes
- Auto-indexed for agent queries
- REPL history tracked

### Pattern 2: Spawn Agent for Task

**Use case**: Delegate specific task to agent in isolated branch.

```clojure
(require '[dvergr.agent.primitives :as prim]
         '[is.simm.spindel.runtime.core :as rtc])

;; Spawn agent for refactoring
(binding [rtc/*execution-context* rt]
  (def refactor-spin
    (prim/spawn!
      (agent/make-agent
        {:name "refactor-auth"
         :isolation :sci           ; Sandboxed
         :tools #{:read-file :write-file :clojure-edit :code-query}
         :permissions #{}})
      "Refactor authentication to use JWT"
      {:from-branch :main          ; Fork from main
       :to-branch :agent-refactor-auth
       :datahike-view :read-main   ; Can query main's code metadata
       :on-turn #(println "Agent:" %)})))

;; Continue working while agent runs
(defn other-work [] ...)

;; Check progress later
(def result (await refactor-spin))

;; Review diff
(ygg/diff rt :main :agent-refactor-auth)

;; Merge if good
(ygg/merge! rt :agent-refactor-auth :main)
```

**Characteristics**:
- Non-blocking (spawn returns immediately)
- Agent works on branch
- Human can continue on main
- Review before merge

### Pattern 3: Parallel Research Agents

**Use case**: Multiple agents research independently.

```clojure
(require '[dvergr.agent.combinators :as comb])

(binding [rtc/*execution-context* rt]
  (def research-results
    (await (comb/all
             ;; Agent 1: Research JWT libraries
             (prim/spawn!
               (agent/make-agent
                 {:name "research-jwt"
                  :isolation :sci
                  :tools #{:read-file :web-search :code-query}})
               "Research Clojure JWT libraries, compare buddy vs jwt-clj"
               {:from-branch :main
                :to-branch :research-jwt
                :datahike-view :read-main})

             ;; Agent 2: Research OAuth patterns
             (prim/spawn!
               (agent/make-agent
                 {:name "research-oauth"
                  :isolation :sci
                  :tools #{:read-file :web-search :code-query}})
               "Research OAuth2 patterns in Clojure"
               {:from-branch :main
                :to-branch :research-oauth
                :datahike-view :read-main})

             ;; Agent 3: Analyze current auth code
             (prim/spawn!
               (agent/make-agent
                 {:name "analyze-auth"
                  :isolation :sci
                  :tools #{:read-file :code-query}})
               "Analyze current authentication code, find issues"
               {:from-branch :main
                :to-branch :analyze-auth
                :datahike-view :read-main})))))

;; All research stored in datahike on separate branches
;; Human can query results
(doseq [result research-results]
  (println "Agent:" (:agent result))
  (println "Result:" (prim/extract-result result)))

;; Synthesize findings manually or with another agent
(binding [rtc/*execution-context* rt]
  (def synthesis
    (prim/ask!
      (agent/make-agent
        {:name "synthesizer"
         :isolation :native})  ; Trusted synthesis agent
      "Synthesize research from branches: research-jwt, research-oauth, analyze-auth"
      {:datahike-view [:read-branch :research-jwt :research-oauth :analyze-auth]})))
```

**Characteristics**:
- Parallel execution
- Each agent on separate branch
- Research stored in datahike
- Human or agent synthesizes findings

### Pattern 4: Iterative Refinement with Review

**Use case**: Agent produces code, human reviews, iterate.

```clojure
(binding [rtc/*execution-context* rt]
  (loop [iteration 0]
    (when (< iteration 3)
      ;; Agent implements
      (def impl
        (prim/ask!
          (agent/make-agent
            {:name "implementer"
             :isolation :sci
             :tools #{:read-file :write-file :clojure-edit :shell}})
          (if (zero? iteration)
            "Implement JWT authentication"
            (str "Refine based on feedback: " @feedback))
          {:from-branch (if (zero? iteration) :main :agent-impl-jwt)
           :to-branch :agent-impl-jwt}))

      ;; Human reviews
      (println "Agent produced:")
      (println (ygg/diff rt :main :agent-impl-jwt))

      ;; Human provides feedback
      (print "Feedback (or 'merge' to accept): ")
      (flush)
      (def user-input (read-line))

      (if (= user-input "merge")
        (do
          (ygg/merge! rt :agent-impl-jwt :main)
          (println "Merged!"))
        (do
          (reset! feedback user-input)
          (recur (inc iteration)))))))
```

**Characteristics**:
- Human in the loop
- Iterative refinement
- Explicit review step
- Human decides when to merge

### Pattern 5: Collaborative Agents with Shared Context

**Use case**: Multiple agents working together on shared problem.

```clojure
(require '[dvergr.agent.core :as agent])

;; Create shared SCI context for collaboration
(def shared-ctx (agent-sci/create-shared-sci-context rt))

(binding [rtc/*execution-context* rt]
  (def design-results
    (await (comb/all
             ;; Architect designs schema
             (prim/spawn!
               (agent/make-agent
                 {:name "architect"
                  :isolation :shared-sci})
               "Design database schema for user auth"
               {:from-branch :main
                :to-branch :design-collab
                :shared-sci-ctx shared-ctx})

             ;; Security expert reviews
             (prim/spawn!
               (agent/make-agent
                 {:name "security"
                  :isolation :shared-sci})
               "Review schema for security issues"
               {:from-branch :main
                :to-branch :design-collab
                :shared-sci-ctx shared-ctx})

             ;; Performance expert considers
             (prim/spawn!
               (agent/make-agent
                 {:name "performance"
                  :isolation :shared-sci})
               "Analyze schema performance implications"
               {:from-branch :main
                :to-branch :design-collab
                :shared-sci-ctx shared-ctx})))))

;; Agents share definitions in SCI context
;; Consensus emerges via shared state
;; Human reviews final design
```

**Characteristics**:
- Shared SCI context
- Peer collaboration
- Emergent consensus
- Single branch output

## State Sharing Model

### What Agents Can See

Agents get **views** of datahike based on trust level:

```clojure
;; View types
:read-main          ; Read main branch code metadata + REPL history
:read-branch        ; Read specific branch(es)
:read-all           ; Read all branches (dangerous!)
:read-code-only     ; Only code metadata, no REPL history or app data
:read-tests-only    ; Only test code + results
```

**Example**: Research agent with limited view

```clojure
(prim/spawn! researcher
  "Research best practices"
  {:from-branch :main
   :to-branch :research-practices
   :datahike-view :read-code-only  ; Can't see REPL history or app data
   :tools #{:read-file :web-search :code-query}})
```

**Example**: Refactoring agent with full view

```clojure
(prim/spawn! refactorer
  "Refactor authentication"
  {:from-branch :main
   :to-branch :refactor-auth
   :datahike-view :read-main  ; Full access to main's context
   :tools #{:read-file :write-file :clojure-edit :code-query :shell}})
```

### What Agents Can Modify

Agents **always work on their own branch**. Cannot modify main directly.

```clojure
;; Agent's world view
{:current-branch :agent-refactor-auth  ; Agent thinks this is "main"
 :writable-paths ["src/" "test/"]      ; Filesystem restrictions
 :datahike-view :read-main             ; Can query main
 :datahike-write :own-branch}          ; Can only write to own branch
```

This creates **fork isolation**:
- Agent can query main's state (code, history, data)
- Agent writes go to its own branch
- Human decides if/when to merge

### Datahike Branching for Isolation

```clojure
;; Schema includes branch reference
{:repl/id          {:db/unique :db.unique/identity}
 :repl/session-id  {:db/valueType :db.type/ref}
 :repl/branch      {:db/valueType :db.type/keyword}  ; :main, :agent-foo, etc.
 :repl/input       {:db/valueType :db.type/string}
 :repl/output      {:db/valueType :db.type/string}
 ...}

;; Query main's history (visible to agent)
(d/q '[:find ?input ?output
       :where
       [?e :repl/branch :main]
       [?e :repl/input ?input]
       [?e :repl/output ?output]]
  @(ygg/as-of world :agent-refactor-auth))  ; Agent's view

;; Agent writes to own branch
(d/transact! (ygg/branch-conn world :agent-refactor-auth)
  [{:repl/id (uuid)
    :repl/branch :agent-refactor-auth  ; Own branch
    :repl/input "(defn foo [x] (* x 2))"
    :repl/output "42"}])
```

## Software Engineering Discipline

### Required for Effective Agent Collaboration

**1. Tests are Critical**

Agents need tests to validate their changes:

```clojure
;; Human writes tests first (TDD)
(deftest test-authenticate
  (is (= :success (authenticate "user" "pass")))
  (is (= :failure (authenticate "user" "wrong"))))

;; Agent can verify implementation
(binding [rtc/*execution-context* rt]
  (prim/ask! implementer
    "Implement authenticate function to pass tests"
    {:from-branch :main
     :to-branch :impl-auth
     :tools #{:read-file :write-file :shell}}))  ; Agent can run tests
```

**Without tests**: Agent has no way to verify correctness.

**2. Pure Functions Preferred**

Pure functions are easier for agents (and JIT):

```clojure
;; GOOD - pure function
(defn calculate-discount [price user-tier]
  (case user-tier
    :gold (* price 0.8)
    :silver (* price 0.9)
    price))

;; Agent can easily reason about this
;; Agent can write tests
;; Beichte can promote to JVM (future)

;; BAD - side effects
(defn apply-discount! [order-id]
  (let [order (db/get-order order-id)  ; Side effect
        discount (calculate-discount-somewhere)]
    (db/update-order! order-id {:discount discount})))  ; Side effect

;; Agent needs to understand db state
;; Hard to test in isolation
;; Can't be promoted to JVM
```

**Strategy**: Factor pure logic out of effectful shells.

```clojure
;; Pure core
(defn calculate-order-total [items discounts]
  (reduce + (map #(apply-discount % discounts) items)))

;; Effectful shell (human writes, agent stays away)
(defn process-order! [order-id]
  (let [order (db/get-order order-id)
        total (calculate-order-total (:items order) (:discounts order))]
    (db/update-order! order-id {:total total})))
```

**Agent task**: "Implement calculate-order-total to handle bulk discounts"
- Agent works on pure function
- Agent can write tests
- Human integrates into effectful shell

**3. Well-Factored Code**

Small, focused functions are easier for agents:

```clojure
;; GOOD - well factored
(defn parse-jwt [token] ...)
(defn verify-signature [jwt secret] ...)
(defn extract-claims [jwt] ...)

(defn authenticate [token secret]
  (let [jwt (parse-jwt token)]
    (when (verify-signature jwt secret)
      (extract-claims jwt))))

;; Agent can understand and modify individual pieces
;; Agent can query: "What calls verify-signature?"

;; BAD - monolithic
(defn authenticate [token secret]
  ;; 200 lines of parsing, verification, extraction
  ...)

;; Agent struggles to understand
;; Hard to modify safely
```

**4. Isolating Effects**

Use **protocols** to isolate effects from logic:

```clojure
;; Protocol for effects
(defprotocol UserStore
  (get-user [this user-id])
  (save-user [this user]))

;; Pure business logic
(defn register-user [user-store email password]
  (when-not (get-user user-store email)
    (save-user user-store {:email email
                           :password-hash (hash-password password)})))

;; Real implementation (human writes)
(defrecord DatabaseUserStore [conn]
  UserStore
  (get-user [_ user-id] (db/query conn ...))
  (save-user [_ user] (db/insert! conn ...)))

;; Test implementation (agent can use)
(defrecord MockUserStore [state]
  UserStore
  (get-user [_ user-id] (get @state user-id))
  (save-user [_ user] (swap! state assoc (:email user) user)))

;; Agent can test without database
(let [store (->MockUserStore (atom {}))]
  (register-user store "alice@example.com" "pass123"))
```

**Agent task**: "Add email validation to register-user"
- Agent works with pure logic
- Agent uses MockUserStore for testing
- No database needed

## Human as Orchestrator

### The Dual Role

As a programmer using dvergr, you are:

**1. Direct Coder** (native mode)
- Writing tests
- Factoring code cleanly
- Handling effects
- Making architectural decisions
- Reviewing and merging agent work

**2. Agent Orchestrator** (background agents)
- Delegating research tasks
- Spawning parallel explorations
- Managing branches
- Synthesizing results
- Maintaining project discipline

### Example Session

```clojure
;; Morning: Design session
;; Human on main branch
(comment
  (defn process-payment [amount user]
    ;; TODO: Implement
    )

  (deftest test-process-payment
    (is (= :success (process-payment 100 test-user)))
    (is (= :failure (process-payment -10 test-user)))))

;; Spawn research agents (parallel)
(binding [rtc/*execution-context* rt]
  (def research
    (await (comb/all
             (prim/spawn! researcher-1
               "Research payment gateway integrations for Clojure"
               {:to-branch :research-payments})
             (prim/spawn! researcher-2
               "Find security best practices for payment handling"
               {:to-branch :research-payment-security})))))

;; Continue coding while agents work
(defn validate-payment-amount [amount]
  (and (number? amount)
       (pos? amount)
       (<= amount 100000)))

;; Review research results
(doseq [r research]
  (println (prim/extract-result r)))

;; Spawn implementation agent
(binding [rtc/*execution-context* rt]
  (def impl
    (prim/ask! implementer
      "Implement process-payment based on research and tests"
      {:from-branch :main
       :to-branch :impl-payment
       :datahike-view [:read-main :research-payments :research-payment-security]})))

;; Review diff
(ygg/diff rt :main :impl-payment)

;; Run tests
(ygg/checkout rt :impl-payment)
(run-tests 'myapp.payment-test)

;; Merge if tests pass
(when (tests-pass?)
  (ygg/merge! rt :impl-payment :main)
  (ygg/cleanup-branch! rt :impl-payment))
```

### Orchestration Patterns

**Pattern: Parallel Exploration**

```clojure
(defn explore-approaches [problem approaches]
  (binding [rtc/*execution-context* rt]
    (let [spins (map (fn [[name approach]]
                       (prim/spawn! explorer
                         (str "Explore: " approach)
                         {:to-branch (keyword (str "explore-" name))}))
                     approaches)]
      (await (comb/all spins)))))

;; Usage
(explore-approaches "authentication"
  {:jwt "JWT tokens with buddy library"
   :session "Server-side sessions with ring"
   :oauth "OAuth2 with third-party providers"})

;; Review all explorations
;; Pick winner or synthesize
```

**Pattern: Background Compilation**

```clojure
(defn watch-and-compile []
  (binding [rtc/*execution-context* rt]
    (prim/tell! compiler
      "Watch src/ and run tests on changes"
      {:to-branch :compiler
       :tools #{:read-file :shell}
       :background? true})))

;; Agent runs in background
;; Reports test failures
;; Human codes on main branch
```

**Pattern: Research → Design → Implement → Test**

```clojure
(binding [rtc/*execution-context* rt]
  (spin
    ;; Phase 1: Research (parallel)
    (let [research (await (parallel
                            (spawn! researcher-a "Research X")
                            (spawn! researcher-b "Research Y")
                            (spawn! researcher-c "Research Z")))

          ;; Phase 2: Design (single agent, sees research)
          design (await (spawn! architect
                          "Design solution based on research"
                          {:datahike-view [:read-all-research]}))

          ;; Phase 3: Implement (human or agent)
          _ (println "Design:" (extract-result design))
          _ (println "Human implements based on design...")

          ;; Phase 4: Test (agent validates)
          tests (await (spawn! tester
                         "Create comprehensive tests"
                         {:from-branch :main}))]

      {:research research
       :design design
       :tests tests})))
```

## Merge Strategies

### Strategy 1: Auto-Merge Pure Functions

If beichte confirms pure + tests pass:

```clojure
(defn safe-auto-merge? [branch]
  (and
    ;; All modified functions are pure
    (every? pure? (modified-functions branch))

    ;; Tests pass
    (tests-pass? branch)

    ;; No merge conflicts
    (empty? (ygg/conflicts rt :main branch))))

(when (safe-auto-merge? :agent-refactor)
  (ygg/merge! rt :agent-refactor :main)
  (println "Auto-merged: all functions pure + tests pass"))
```

### Strategy 2: Human Review for Effects

If any effects, require human review:

```clojure
(defn requires-review? [branch]
  (or
    ;; Modified functions with effects
    (some impure? (modified-functions branch))

    ;; Modified tests
    (modified-tests? branch)

    ;; New dependencies
    (new-dependencies? branch)))

(when (requires-review? :agent-impl)
  (println "Requires review:")
  (println (ygg/diff rt :main :agent-impl))
  (print "Approve merge? (y/n): ")
  (when (= "y" (read-line))
    (ygg/merge! rt :agent-impl :main)))
```

### Strategy 3: Staged Merge

Merge in stages with test validation:

```clojure
(defn staged-merge [branch]
  ;; 1. Merge code only (no tests yet)
  (ygg/merge! rt branch :main {:only ["src/"]})

  ;; 2. Run existing tests
  (if (tests-pass? :main)
    (do
      ;; 3. Merge test changes
      (ygg/merge! rt branch :main {:only ["test/"]})

      ;; 4. Run all tests
      (if (tests-pass? :main)
        (println "Merge complete!")
        (do
          ;; Rollback
          (ygg/revert! rt :main)
          (println "Tests failed with new tests, rolled back"))))
    (do
      ;; Rollback
      (ygg/revert! rt :main)
      (println "Tests failed, rolled back"))))
```

## Practical API Design

### REPL-Friendly Functions

```clojure
(ns dvergr.repl
  "REPL-friendly API for interactive development")

;; Start session (creates runtime)
(defn start-session
  [{:keys [branch cwd isolation]
    :or {branch :main cwd "." isolation :native}}]
  ...)

;; Spawn agent (non-blocking)
(defn agent
  [task & {:keys [name tools from-branch to-branch view]}]
  (prim/spawn!
    (agent/make-agent {:name name :tools tools :isolation :sci})
    task
    {:from-branch from-branch :to-branch to-branch :datahike-view view}))

;; Quick parallel spawns
(defmacro agents [& tasks]
  `(await (comb/all ~@(map (fn [t] `(agent ~@t)) tasks))))

;; Usage
(agents
  ["Research JWT" :name "jwt" :view :read-main]
  ["Research OAuth" :name "oauth" :view :read-main]
  ["Analyze auth code" :name "analyze" :view :read-main])

;; Diff helper
(defn diff [branch]
  (ygg/diff rt :main branch))

;; Merge helper
(defn merge! [branch & {:keys [review?] :or {review? true}}]
  (when (or (not review?)
            (do (println (diff branch))
                (print "Merge? (y/n): ")
                (= "y" (read-line))))
    (ygg/merge! rt branch :main)))

;; Cleanup
(defn cleanup [branch]
  (ygg/cleanup-branch! rt branch))
```

### Example REPL Session

```clojure
;; Start
(require '[dvergr.repl :as r])
(def rt (r/start-session {:cwd "~/myproject"}))

;; Spawn research agents
(def research (r/agents
                ["Research JWT libs" :name "jwt"]
                ["Security best practices" :name "security"]))

;; Continue coding
(defn my-function [x] ...)

;; Check research
(map prim/extract-result research)

;; Spawn implementation
(def impl (r/agent "Implement auth"
                   :name "impl-auth"
                   :view [:read-main :jwt :security]))

;; Review
(r/diff :impl-auth)

;; Merge
(r/merge! :impl-auth)

;; Cleanup
(r/cleanup :impl-auth)
```

## Future: JIT Promotion

Once beichte is integrated:

```clojure
;; Agent writes pure function in SCI
(prim/ask! coder "Implement pure calculation"
  {:to-branch :impl-calc})

;; Merge to main (still in SCI)
(r/merge! :impl-calc)

;; Hot function gets promoted automatically
;; beichte detects: pure + frequently called
;; JIT compiles to JVM
;; ~ 10-100x speedup

;; Agent's work now runs at native speed
;; Transparent optimization
```

## Implementation Checklist

**Phase 1: Core Branch Infrastructure** (depends on Phase 5 in DESIGN.md)
- [ ] Yggdrasil integration with datahike
- [ ] Branch creation/deletion/merge
- [ ] Diff visualization
- [ ] Conflict detection

**Phase 2: Agent Branching**
- [ ] Agent spawn with branch parameters
- [ ] Datahike view types (read-main, read-branch, etc.)
- [ ] Branch-scoped tool execution
- [ ] Auto-branch cleanup

**Phase 3: REPL API**
- [ ] `dvergr.repl` namespace
- [ ] REPL-friendly wrappers
- [ ] `agents` macro for parallel spawn
- [ ] Interactive diff/merge helpers

**Phase 4: Merge Strategies**
- [ ] Pure function detection (beichte)
- [ ] Test validation hooks
- [ ] Staged merge support
- [ ] Conflict resolution UI

**Phase 5: State Sharing**
- [ ] Datahike query scoping by branch
- [ ] View permission enforcement
- [ ] Cross-branch queries
- [ ] REPL history visibility control

## Next Steps

1. **Prototype branch infrastructure** - Yggdrasil + datahike integration
2. **Test parallel agent spawns** - Verify isolation
3. **Design merge UX** - How to visualize diffs/conflicts
4. **Implement REPL API** - Make it ergonomic
5. **Write integration tests** - End-to-end workflows

## References

- `DESIGN.md` - Overall architecture, Yggdrasil integration plan
- `AGENTIC_WORKFLOWS_DESIGN.md` - Workflow patterns
- `AGENT_SCI_INTEGRATION.md` - Agent isolation infrastructure
- `SCI_RUNTIME_INTEGRATION_DESIGN.md` - SCI execution design
- `../yggdrasil` - CoW branching protocol
- `src/dvergr/core.clj` - Current API (git worktrees)
