(ns examples.yggdrasil-agent-workflows
  "Demonstration of agent workflows with yggdrasil git integration.

   This example shows how spindel-yggdrasil integration enables:
   - Isolated file system operations per agent (automatic git worktrees)
   - Parent-controlled merge/discard of agent work
   - Parallel agents working on different features without conflicts
   - Nested agent workflows with hierarchical branching

   Each agent fork automatically gets:
   - Its own git branch (e.g., main-ygg-fork-123)
   - Its own filesystem worktree (isolated working directory)
   - Version-controlled changes (all work is committed to git)

   The parent agent controls whether to merge or discard the work."
  (:require [is.simm.spindel.runtime.context :as ctx]
            [is.simm.spindel.runtime.core :as rtc]
            [is.simm.spindel.yggdrasil :as ygg]
            [is.simm.spindel.spin.cps :refer [spin]]
            [is.simm.spindel.effects.await :refer [await]]
            [dvergr.git :as git]
            [dvergr.agent.core :as agent]
            [dvergr.agent.primitives :as prim]
            [yggdrasil.protocols :as ygg-proto]
            [clojure.java.shell :refer [sh]]
            [clojure.java.io :as io]))

;; =============================================================================
;; Example 1: Basic Agent with Git Isolation
;; =============================================================================

(comment
  "Agent makes changes in isolated branch, parent decides to merge or discard."

  ;; Setup: Create runtime and register git system
  (def rt (ctx/create-execution-context))

  (binding [rtc/*execution-context* rt]
    ;; Register git system (current repo)
    (def ygit (ygg/register! (git/create-git-system)))

    (println "Initial branch:" (ygg-proto/current-branch @ygit))
    ;; => :main

    ;; Fork for agent work
    (def agent-fork (ygg/fork!))

    ;; Agent works in forked context
    (ygg/with-fork agent-fork
      (println "Agent working on branch:" (ygg-proto/current-branch @ygit))
      ;; => :main-ygg-fork-123

      (let [wt-path (git/worktree-path @ygit)
            test-file (str wt-path "/agent-feature.txt")]
        ;; Agent makes changes
        (spit test-file "Feature implemented by agent")
        (sh "git" "add" "agent-feature.txt" :dir wt-path)
        (sh "git" "commit" "-m" "Add agent feature" :dir wt-path)
        (println "Agent committed changes in:" wt-path)))

    ;; Back in parent context - review and decide
    (println "Parent still on:" (ygg-proto/current-branch @ygit))
    ;; => :main

    ;; Option 1: Merge the work
    (ygg/merge-fork! agent-fork)
    (println "Merged! File exists:" (.exists (io/file "agent-feature.txt")))

    ;; Option 2: Discard the work
    ;; (ygg/discard-fork! agent-fork)
    ))

;; =============================================================================
;; Example 2: Parallel Feature Development
;; =============================================================================

(comment
  "Multiple agents working on different features in parallel, no conflicts."

  (binding [rtc/*execution-context* rt]
    (def ygit (ygg/register! (git/create-git-system)))

    ;; Fork for feature A
    (def feature-a-fork (ygg/fork!))
    (ygg/with-fork feature-a-fork
      (let [wt-path (git/worktree-path @ygit)]
        (spit (str wt-path "/feature-a.clj") "(ns feature-a)")
        (sh "git" "add" "feature-a.clj" :dir wt-path)
        (sh "git" "commit" "-m" "Feature A" :dir wt-path)))

    ;; Fork for feature B (parallel, independent)
    (def feature-b-fork (ygg/fork!))
    (ygg/with-fork feature-b-fork
      (let [wt-path (git/worktree-path @ygit)]
        (spit (str wt-path "/feature-b.clj") "(ns feature-b)")
        (sh "git" "add" "feature-b.clj" :dir wt-path)
        (sh "git" "commit" "-m" "Feature B" :dir wt-path)))

    ;; Parent merges both (no conflicts, different files)
    (ygg/merge-fork! feature-a-fork)
    (ygg/merge-fork! feature-b-fork)

    (println "Both features merged:")
    (println "  feature-a.clj:" (.exists (io/file "feature-a.clj")))
    (println "  feature-b.clj:" (.exists (io/file "feature-b.clj")))))

;; =============================================================================
;; Example 3: Code Review Agent Pattern
;; =============================================================================

(comment
  "Agent proposes changes, parent reviews diff before merging."

  (binding [rtc/*execution-context* rt]
    (def ygit (ygg/register! (git/create-git-system)))

    ;; Agent proposes fix
    (def fix-fork (ygg/fork!))
    (ygg/with-fork fix-fork
      (let [wt-path (git/worktree-path @ygit)
            file-path (str wt-path "/src/example.clj")]
        ;; Agent makes changes
        (spit file-path "(defn fixed [] :improved)")
        (sh "git" "add" "src/example.clj" :dir wt-path)
        (sh "git" "commit" "-m" "Fix: Improve example function" :dir wt-path)))

    ;; Parent reviews diff before merging
    (let [parent-branch (ygg-proto/current-branch @ygit)
          agent-branch (ygg/with-fork fix-fork
                        (ygg-proto/current-branch @ygit))
          diff (ygg-proto/diff @ygit parent-branch agent-branch)]
      (println "Review diff:")
      (println (:diff diff))

      ;; Decision based on review
      (if (approve? diff)
        (do
          (ygg/merge-fork! fix-fork)
          (println "✓ Changes approved and merged"))
        (do
          (ygg/discard-fork! fix-fork)
          (println "✗ Changes rejected"))))))

;; =============================================================================
;; Example 4: Nested Agent Workflow
;; =============================================================================

(comment
  "Agent spawns sub-agents, each with their own isolated branch."

  (binding [rtc/*execution-context* rt]
    (def ygit (ygg/register! (git/create-git-system)))

    ;; Outer agent fork
    (def outer-fork (ygg/fork!))

    (ygg/with-fork outer-fork
      (println "Outer agent on:" (ygg-proto/current-branch @ygit))
      ;; => :main-ygg-fork-456

      (let [wt-path (git/worktree-path @ygit)]
        ;; Outer agent makes initial changes
        (spit (str wt-path "/outer-work.txt") "Outer agent work")
        (sh "git" "add" "outer-work.txt" :dir wt-path)
        (sh "git" "commit" "-m" "Outer agent initial work" :dir wt-path))

      ;; Outer agent spawns inner agent
      (def inner-fork (ygg/fork!))

      (ygg/with-fork inner-fork
        (println "Inner agent on:" (ygg-proto/current-branch @ygit))
        ;; => :main-ygg-fork-456-ygg-fork-789 (nested!)

        (let [wt-path (git/worktree-path @ygit)]
          ;; Inner agent makes changes
          (spit (str wt-path "/inner-work.txt") "Inner agent work")
          (sh "git" "add" "inner-work.txt" :dir wt-path)
          (sh "git" "commit" "-m" "Inner agent work" :dir wt-path)))

      ;; Outer agent merges inner work
      (ygg/merge-fork! inner-fork)
      (println "Outer agent merged inner work"))

    ;; Parent merges all outer work (includes merged inner work)
    (ygg/merge-fork! outer-fork)
    (println "Parent merged all work")))

;; =============================================================================
;; Example 5: Try-Multiple-Approaches Pattern
;; =============================================================================

(comment
  "Agent tries multiple implementations in parallel, parent picks best."

  (binding [rtc/*execution-context* rt]
    (def ygit (ygg/register! (git/create-git-system)))

    ;; Try approach 1: Functional
    (def functional-fork (ygg/fork!))
    (ygg/with-fork functional-fork
      (let [wt-path (git/worktree-path @ygit)]
        (spit (str wt-path "/solution.clj")
              "(defn solve [x] (reduce + (map inc x)))")
        (sh "git" "add" "solution.clj" :dir wt-path)
        (sh "git" "commit" "-m" "Functional approach" :dir wt-path)))

    ;; Try approach 2: Loop-based
    (def loop-fork (ygg/fork!))
    (ygg/with-fork loop-fork
      (let [wt-path (git/worktree-path @ygit)]
        (spit (str wt-path "/solution.clj")
              "(defn solve [x] (loop [xs x acc 0] ...))")
        (sh "git" "add" "solution.clj" :dir wt-path)
        (sh "git" "commit" "-m" "Loop approach" :dir wt-path)))

    ;; Parent evaluates both, picks best
    (let [functional-perf (benchmark functional-fork)
          loop-perf (benchmark loop-fork)]
      (if (< functional-perf loop-perf)
        (do
          (ygg/merge-fork! functional-fork)
          (ygg/discard-fork! loop-fork)
          (println "Chose functional approach"))
        (do
          (ygg/merge-fork! loop-fork)
          (ygg/discard-fork! functional-fork)
          (println "Chose loop approach"))))))

;; =============================================================================
;; Example 6: Agent with Real LLM Integration
;; =============================================================================

(comment
  "Full example with actual agent execution and git isolation."

  (binding [rtc/*execution-context* rt]
    ;; Setup git integration
    (def ygit (ygg/register! (git/create-git-system)))

    ;; Create coding agent
    (def coder (agent/make-agent
                 {:name "coder"
                  :max-turns 5
                  :permissions #{:use-tools}}))

    ;; Fork for agent's work
    (def agent-fork (ygg/fork!))

    ;; Agent works in isolated context
    (def agent-result
      (ygg/with-fork agent-fork
        (let [wt-path (git/worktree-path @ygit)]
          ;; Agent executes with access to isolated worktree
          (prim/ask! coder
                     (str "Implement a simple parser in " wt-path "/parser.clj")
                     {:working-dir wt-path
                      :git-system @ygit}))))

    ;; Review agent's work
    (println "Agent result:" (:status agent-result))
    (let [diff (ygg/with-fork agent-fork
                 (ygg-proto/diff @ygit
                                :main
                                (ygg-proto/current-branch @ygit)))]
      (println "Changes made:")
      (println (:diff diff)))

    ;; Decide
    (if (= :success (:status agent-result))
      (ygg/merge-fork! agent-fork)
      (ygg/discard-fork! agent-fork))))

;; =============================================================================
;; Example 7: Refactoring Agent with Rollback
;; =============================================================================

(comment
  "Agent attempts refactoring, parent can test and rollback if needed."

  (binding [rtc/*execution-context* rt]
    (def ygit (ygg/register! (git/create-git-system)))
    (def refactorer (agent/make-agent {:name "refactorer"}))

    ;; Fork for refactoring
    (def refactor-fork (ygg/fork!))

    ;; Agent refactors
    (def refactor-result
      (ygg/with-fork refactor-fork
        (let [wt-path (git/worktree-path @ygit)]
          (prim/ask! refactorer
                     "Refactor src/core.clj to use protocols"
                     {:working-dir wt-path}))))

    ;; Test the refactored version
    (def tests-pass?
      (ygg/with-fork refactor-fork
        (let [wt-path (git/worktree-path @ygit)]
          (zero? (:exit (sh "clj" "-M:test" :dir wt-path))))))

    ;; Merge if tests pass, rollback otherwise
    (if tests-pass?
      (do
        (ygg/merge-fork! refactor-fork)
        (println "✓ Refactoring successful, merged"))
      (do
        (ygg/discard-fork! refactor-fork)
        (println "✗ Tests failed, refactoring discarded")))))

;; =============================================================================
;; Helper Functions
;; =============================================================================

(defn approve?
  "Mock approval function - in real use, this would be human review or checks."
  [diff]
  ;; Real implementation: prompt human, run checks, etc.
  true)

(defn benchmark
  "Mock benchmark function - in real use, this would run actual benchmarks."
  [fork-handle]
  ;; Real implementation: run benchmarks in the forked worktree
  (rand-int 1000))

;; =============================================================================
;; Key Takeaways
;; =============================================================================

(comment
  "What makes this powerful:

   1. **Automatic Isolation**: Just ygg/fork! and agents get their own branch/worktree
   2. **No Conflicts**: Agents can work in parallel on the same repo
   3. **Parent Control**: Only parent can merge/discard (permission model)
   4. **Nested Workflows**: Agents can spawn sub-agents with hierarchical branches
   5. **Version Control**: All changes are git-tracked automatically
   6. **Rollback**: Easy to discard failed experiments
   7. **Review**: Inspect diffs before merging

   Compared to manual git operations:
   - No need to manage branch names (generated automatically)
   - No need to create/cleanup worktrees (handled by yggdrasil)
   - No need to track parent-child relationships (stored in ForkHandle)
   - No risk of agents interfering with each other's branches

   The pattern:
     1. Parent forks: (def f (ygg/fork!))
     2. Agent works: (ygg/with-fork f ...)
     3. Parent decides: (ygg/merge-fork! f) or (ygg/discard-fork! f)")
