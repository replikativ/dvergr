# Git Worktree Workflow Guide

Dvergr integrates git worktrees for session isolation, enabling each agent session to work in its own isolated branch and working directory without interfering with other sessions.

## Overview

### Key Concepts

- **Worktree**: A separate working directory linked to the same git repository
- **Session Branch**: Each session gets its own git branch (e.g., `session-session-123`)
- **Isolation**: Changes in one session don't affect other sessions until explicitly merged
- **Nested Sessions**: Child sessions can branch from parent session branches

### Physical vs Logical Structure

**Physical** (worktree locations):
```
repo/
  .git/
  .worktrees/
    session-session-123/      # Parent session worktree
    session-session-456/      # Nested session worktree (sibling)
```

**Logical** (branch hierarchy):
```
main
  └─ session-session-123      # Parent session branch
       └─ session-session-456 # Nested session branch
```

All worktrees are physically siblings, but branches maintain parent-child relationships for merging.

## Basic Workflow

### 1. Simple Session (from main branch)

```clojure
(require '[dvergr.core :as r])

;; Run agent task in isolated worktree
(def result
  (r/run "Add user authentication"
         :repo-path "/path/to/your/project"
         :provider :anthropic
         :model "claude-sonnet-4-5-20250514"
         :max-turns 20))

;; Get session ID for later use
(:session-id result)
;; => "session-1769338996514"
```

**What happens:**
1. Creates branch `session-session-1769338996514` from `main`
2. Creates worktree at `.worktrees/session-session-1769338996514/`
3. Agent executes all file operations in the worktree
4. Session persisted for later review/merge

### 2. Review Session Changes

```clojure
(require '[dvergr.session :as session])

;; Load the session
(def sess (session/load-session "session-1769338996514"))

;; Check git status
(r/git-status sess)
;; => {:branch :session-session-1769338996514
;;     :worktree-path ".worktrees/session-session-1769338996514"
;;     :has-changes? true
;;     :commit-sha "abc123..."
;;     :status-text "M  src/auth.clj\nA  src/user.clj\n"}

;; View diff from parent branch
(r/show-diff sess)
;; => Shows git diff output
```

### 3. Merge Session to Main

```clojure
;; Merge changes back to main branch
(r/finalize sess
           :merge? true        # Merge to parent branch
           :cleanup? true)     # Delete worktree after merge
;; => {:success true
;;     :merged true
;;     :merge-result {:success true :commit-sha "def456..."}
;;     :cleanup true
;;     :cleanup-result {:success true}}
```

**What happens:**
1. Checks for merge conflicts
2. If no conflicts, merges session branch to main
3. Creates merge commit
4. Optionally deletes worktree and branch

### 4. Discard Session (No Merge)

```clojure
;; Just cleanup without merging
(r/finalize sess :cleanup? true)
;; => {:success true :merged false}
```

## Nested Agent Workflow

Use nested sessions when you want sub-tasks to work on branches derived from a parent session's work.

### Example: Refactoring with Sub-tasks

```clojure
;; Parent session: Refactor authentication
(def parent
  (r/run "Refactor authentication system to use JWT"
         :repo-path "/path/to/project"
         :max-turns 30))

;; Load parent session
(def parent-sess (session/load-session (:session-id parent)))

;; Child session 1: Add tests (branches from parent's work)
(def child1
  (r/run "Add comprehensive tests for JWT authentication"
         :repo-path "/path/to/project"
         :parent-session parent-sess
         :max-turns 15))

;; Child session 2: Add documentation (also branches from parent)
(def child2
  (r/run "Document the new JWT authentication system"
         :repo-path "/path/to/project"
         :parent-session parent-sess
         :max-turns 10))
```

**Branch structure created:**
```
main
  └─ session-parent-123
       ├─ session-child1-456  (tests)
       └─ session-child2-789  (docs)
```

### Merging Nested Sessions

```clojure
;; Merge child sessions back to parent
(def child1-sess (session/load-session (:session-id child1)))
(def child2-sess (session/load-session (:session-id child2)))

(r/finalize child1-sess :merge? true :cleanup? true)
;; Merges child1 → parent branch

(r/finalize child2-sess :merge? true :cleanup? true)
;; Merges child2 → parent branch

;; Finally, merge parent to main
(r/finalize parent-sess :merge? true :cleanup? true)
;; Merges parent → main
```

## Advanced Patterns

### Manual Git Operations

Since each session has a real git worktree, you can use git commands directly:

```bash
# Navigate to session worktree
cd .worktrees/session-session-123/

# Normal git operations
git status
git add .
git commit -m "Manual changes"
git log
```

### Conflict Resolution

If merge conflicts occur:

```clojure
(def result (r/finalize sess :merge? true))

(when-not (:success result)
  (println "Conflicts detected:")
  (clojure.pprint/pprint (:conflicts result))

  ;; Resolve manually or programmatically
  ;; Then retry merge
)
```

### Parallel Agent Sessions

Multiple agents can work on different branches simultaneously:

```clojure
;; Start multiple agents in parallel (different features)
(def feature-a
  (future
    (r/run "Implement feature A"
           :repo-path "/path/to/project")))

(def feature-b
  (future
    (r/run "Implement feature B"
           :repo-path "/path/to/project")))

;; Both work in isolated worktrees, no interference
(def result-a @feature-a)
(def result-b @feature-b)
```

## Best Practices

### 1. Always Review Before Merging

```clojure
;; Check what changed
(r/show-diff sess)
(r/git-status sess)

;; Then merge if satisfied
(r/finalize sess :merge? true)
```

### 2. Use Nested Sessions for Sub-tasks

When a task naturally decomposes into sub-tasks, use nested sessions rather than sequential operations in one session.

### 3. Cleanup Old Sessions

```clojure
;; List all sessions
(session/list-all)

;; Cleanup sessions you don't need
(r/finalize old-sess :cleanup? true)
```

### 4. Explicit Commits

Agent tools (like `write_file`, `edit_file`) don't auto-commit. This gives you control over commit granularity. Use git commands in the worktree or let the agent use git tools.

## Session Metadata

Each session tracks:
- Git branch name
- Parent branch
- Worktree path
- Datahike database (for REPL history)
- SCI context (for isolated evaluation)

```clojure
(def sess (session/load-session "session-123"))

(:git-branch sess)      ;; => :session-session-123
(:parent-branch sess)   ;; => :main
(:cwd sess)            ;; => ".worktrees/session-session-123"
```

## Troubleshooting

### "Branch worktree not found"

The worktree may have been manually deleted. Either:
1. Recreate it: `git worktree add .worktrees/session-X session-X`
2. Or cleanup the session: `(r/finalize sess :cleanup? true)`

### "Merge conflicts detected"

Resolve conflicts manually in the worktree, then retry merge.

### Session not isolated?

Ensure you're passing `:repo-path` when calling `r/run`. Without it, the session uses the current directory without worktree isolation.

## Example: Complete Workflow

```clojure
;; 1. Start task
(def result
  (r/run "Add user authentication with JWT"
         :repo-path "~/myproject"
         :max-turns 30))

;; 2. Review results
(def sess (session/load-session (:session-id result)))
(println (r/show-diff sess))
(println (r/git-status sess))

;; 3. Test in worktree
;; (manually or with additional agent calls)

;; 4. Merge if satisfied
(r/finalize sess :merge? true :cleanup? true)

;; Done! Changes are now in main branch
```

## Implementation Notes

- Uses [yggdrasil](https://github.com/replikativ/yggdrasil) git adapter for protocol-based git operations
- Each session gets isolated SCI context and datahike database
- Worktrees share `.git` directory (efficient, no duplication)
- Branch names follow pattern: `session-session-{timestamp}`
