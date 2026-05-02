(ns examples.code-review-agent
  "Practical example: Code review agent with git isolation.

   Use case: An agent reviews code and proposes fixes. The parent (human or
   orchestrator) reviews the diff before deciding to merge or discard.

   This demonstrates:
   - Agent working in isolated git branch
   - Making file modifications
   - Parent reviewing changes via git diff
   - Controlled merge or discard

   Run with:
     clj -M:dev
     (require '[examples.code-review-agent :as cr] :reload)
     (cr/demo-code-review)"
  (:require [is.simm.spindel.runtime.context :as ctx]
            [is.simm.spindel.runtime.core :as rtc]
            [is.simm.spindel.yggdrasil :as ygg]
            [dvergr.git :as git]
            [dvergr.agent.core :as agent]
            [dvergr.agent.primitives :as prim]
            [yggdrasil.protocols :as ygg-proto]
            [clojure.java.shell :refer [sh]]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; =============================================================================
;; Setup
;; =============================================================================

(defn setup-git-context
  "Create execution context with git integration."
  []
  (let [rt (ctx/create-execution-context)]
    (binding [rtc/*execution-context* rt]
      (let [ygit (ygg/register! (git/create-git-system))]
        {:runtime rt
         :ygit ygit}))))

;; =============================================================================
;; Code Review Agent
;; =============================================================================

(defn create-review-agent
  "Create an agent configured for code review tasks."
  []
  (agent/make-agent
    {:name "code-reviewer"
     :max-turns 10
     :permissions #{:use-tools}
     :system-prompt "You are a code reviewer. Analyze code for:
                     - Bug fixes
                     - Performance improvements
                     - Code clarity
                     Make concrete file changes when you find issues."}))

;; =============================================================================
;; Agent Task Execution with Git Isolation
;; =============================================================================

(defn execute-review-in-fork
  "Execute agent review in isolated git fork.

   Returns:
     {:fork fork-handle
      :result agent-result
      :branch branch-name
      :worktree-path path}"
  [ygit agent task]
  (let [fork (ygg/fork!)
        result (ygg/with-fork fork
                 (let [wt-path (git/worktree-path @ygit)
                       branch (ygg-proto/current-branch @ygit)]
                   (println "Agent working on branch:" branch)
                   (println "Worktree path:" wt-path)

                   ;; Agent executes with access to isolated worktree
                   (let [agent-result (prim/ask! agent task
                                                 {:working-dir wt-path
                                                  :git-system @ygit})]

                     ;; Agent commits its changes
                     (when (zero? (:exit (sh "git" "diff" "--quiet" :dir wt-path)))
                       (sh "git" "add" "." :dir wt-path)
                       (sh "git" "commit" "-m"
                           (str "Code review fixes by " (:name agent))
                           :dir wt-path))

                     {:result agent-result
                      :branch branch
                      :worktree-path wt-path})))]
    (assoc result :fork fork)))

;; =============================================================================
;; Review and Decision
;; =============================================================================

(defn show-diff
  "Show git diff between parent and agent's branch."
  [ygit fork]
  (let [parent-branch (ygg-proto/current-branch @ygit)
        agent-branch (ygg/with-fork fork
                      (ygg-proto/current-branch @ygit))
        diff-result (ygg-proto/diff @ygit parent-branch agent-branch)]
    (println "\n" (str/repeat 60 "="))
    (println "DIFF: Changes proposed by agent")
    (println (str/repeat 60 "="))
    (println (:diff diff-result))
    (println (str/repeat 60 "=") "\n")
    diff-result))

(defn approve-changes?
  "Human decision point: approve changes?

   In a real system, this might:
   - Run tests in the forked branch
   - Check code quality metrics
   - Prompt human reviewer
   - Run security scans"
  [diff agent-result]
  (println "\nAgent completed with status:" (:status agent-result))
  (println "\nReview the diff above.")
  (print "Approve changes? (y/n): ")
  (flush)
  (let [response (read-line)]
    (= "y" (str/lower-case (str/trim response)))))

(defn finalize-review
  "Merge or discard agent's work based on approval."
  [ygit fork approved?]
  (if approved?
    (do
      (ygg/merge-fork! fork)
      (println "\n✓ Changes approved and merged to" (ygg-proto/current-branch @ygit)))
    (do
      (ygg/discard-fork! fork)
      (println "\n✗ Changes rejected and discarded"))))

;; =============================================================================
;; Complete Workflow
;; =============================================================================

(defn review-code-workflow
  "Complete code review workflow with git isolation.

   Steps:
   1. Fork execution context (automatic git branch)
   2. Agent analyzes code and makes fixes in isolated branch
   3. Show diff to parent/human
   4. Approve or reject
   5. Merge or discard based on decision"
  [task]
  (let [{:keys [runtime ygit]} (setup-git-context)]
    (binding [rtc/*execution-context* runtime]
      (println "Starting code review workflow...")
      (println "Current branch:" (ygg-proto/current-branch @ygit))

      ;; Create and execute agent in fork
      (let [agent (create-review-agent)
            {:keys [fork result]} (execute-review-in-fork ygit agent task)]

        ;; Review changes
        (let [diff (show-diff ygit fork)
              approved? (approve-changes? diff result)]

          ;; Finalize
          (finalize-review ygit fork approved?)

          {:status (if approved? :merged :discarded)
           :agent-result result
           :diff diff})))))

;; =============================================================================
;; Demo
;; =============================================================================

(defn demo-code-review
  "Interactive demo of code review agent."
  []
  (println "\n╔════════════════════════════════════════════════════════╗")
  (println "║       Code Review Agent with Git Isolation            ║")
  (println "╚════════════════════════════════════════════════════════╝\n")

  (review-code-workflow
    "Review the code in src/dvergr/core.clj and propose improvements.
     Focus on error handling and code clarity."))

(defn demo-automated-review
  "Automated review (no human approval) for testing."
  []
  (let [{:keys [runtime ygit]} (setup-git-context)]
    (binding [rtc/*execution-context* runtime]
      (let [agent (create-review-agent)
            {:keys [fork result]} (execute-review-in-fork
                                    ygit agent
                                    "Check for TODOs in src/ and fix them")]

        ;; Automated decision: merge if agent succeeded
        (let [approved? (= :success (:status result))]
          (finalize-review ygit fork approved?)
          {:status (if approved? :merged :discarded)
           :agent-result result})))))

;; =============================================================================
;; Example Usage
;; =============================================================================

(comment
  ;; Interactive demo (prompts for approval)
  (demo-code-review)

  ;; Automated demo (auto-approves)
  (demo-automated-review)

  ;; Custom task
  (review-code-workflow "Refactor examples/ to use consistent docstrings")

  ;; Multiple sequential reviews
  (let [{:keys [runtime ygit]} (setup-git-context)]
    (binding [rtc/*execution-context* runtime]
      ;; Review 1
      (let [agent (create-review-agent)
            r1 (execute-review-in-fork ygit agent "Fix error handling")]
        (show-diff ygit (:fork r1))
        (finalize-review ygit (:fork r1) true))

      ;; Review 2 (starts from merged state)
      (let [agent (create-review-agent)
            r2 (execute-review-in-fork ygit agent "Add docstrings")]
        (show-diff ygit (:fork r2))
        (finalize-review ygit (:fork r2) true)))))
