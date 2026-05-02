# Agent-Yggdrasil Integration Design

**Date:** 2026-01-26
**Status:** Ready for Implementation

## 1. Overview & Goals

This document describes the integration between dvergr agents and yggdrasil version control systems via spindel execution contexts. The goal is to provide:

1. **Automatic isolation** - Agents work in isolated git branches and datahike databases without explicit management
2. **Parent-controlled merging** - Only parent can merge/discard child's work (permission model)
3. **Transparent tooling** - Agents use normal file paths; isolation is invisible to them
4. **Nested workflows** - Agents can spawn sub-agents with hierarchical isolation
5. **Group coordination** - Multiple agents collaborate via shared signals and group databases
6. **Complete history** - All tool uses, evals, and messages are automatically tracked

### Design Principles

- **Think in spindel terms** - Forking execution context forks everything (git, datahike)
- **Agents are unaware of git** - They just use tools; paths resolve automatically
- **History is retained** - Everything is tracked for debugging and learning; GC handled later
- **Programmable coordination** - Group patterns are not hardcoded

## 2. Architecture

### 2.1 Execution Context Structure

Each agent runs in a spindel execution context containing:

```clojure
{:external-refs
  {<git-id>      GitSystem      ; Code files, worktrees
   <datahike-id> DatahikeSystem ; Private: tool uses, evals, messages}

 :state
  {:signals {...}    ; Reactive signals
   :atoms {...}}     ; Runtime atoms

 :parent-ctx <parent-context>  ; For merge operations
 :fork-id <uuid>}              ; Unique fork identifier
```

### 2.2 Automatic Forking via PForkable

When `ctx/fork-context` is called, all `[:external-refs]` are forked automatically via the `PForkable` protocol:

```clojure
;; In spindel runtime/context.cljc
(defn fork-context [parent-ctx]
  (let [fork-id (keyword (str "fork-" (random-uuid)))
        ;; Fork all external refs (git branches, datahike branches)
        forked-refs (reduce-kv
                      (fn [m id ref]
                        (assoc m id (rtp/pfork ref fork-id)))
                      {}
                      (get-state parent-ctx [:external-refs]))]
    ;; Create child context with forked refs
    ...))
```

### 2.3 System Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│                     Root Execution Context                       │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │ [:external-refs]                                           │ │
│  │   ├── ygit (GitSystem)       → main branch, repo worktree  │ │
│  │   └── ydb (DatahikeSystem)   → root database               │ │
│  └────────────────────────────────────────────────────────────┘ │
└───────────────────────────┬─────────────────────────────────────┘
                            │ fork-context
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│                   Agent Execution Context                        │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │ [:external-refs]                                           │ │
│  │   ├── ygit → main-fork-abc branch, isolated worktree       │ │
│  │   └── ydb  → branched database (tool uses, evals, msgs)    │ │
│  └────────────────────────────────────────────────────────────┘ │
│  :parent-ctx → reference to root context                        │
└───────────────────────────┬─────────────────────────────────────┘
                            │ fork-context (nested)
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│                 Sub-Agent Execution Context                      │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │ [:external-refs]                                           │ │
│  │   ├── ygit → main-fork-abc-fork-xyz branch                 │ │
│  │   └── ydb  → further branched database                     │ │
│  └────────────────────────────────────────────────────────────┘ │
│  :parent-ctx → reference to agent context                       │
└─────────────────────────────────────────────────────────────────┘
```

### 2.4 Group Structure

Groups have shared resources plus individual agent contexts:

```
┌─────────────────────────────────────────────────────────────────┐
│                            Group                                 │
│  ┌──────────────────────┐  ┌─────────────────────────────────┐  │
│  │ group-ydb (shared)   │  │ messages-signal (shared)        │  │
│  │ - inter-agent msgs   │  │ - reactive coordination         │  │
│  │ - admin actions      │  │ - agents track for updates      │  │
│  │ - group state        │  └─────────────────────────────────┘  │
│  └──────────────────────┘                                       │
│  ┌──────────────────────┐  ┌─────────────────────────────────┐  │
│  │ coordination-fn      │  │ turn-signal (optional)          │  │
│  │ - programmable       │  │ - who speaks next               │  │
│  │ - round-robin, etc   │  └─────────────────────────────────┘  │
│  └──────────────────────┘                                       │
│                                                                  │
│  ┌───────────┐  ┌───────────┐  ┌───────────┐                   │
│  │  Agent A  │  │  Agent B  │  │  Agent C  │                   │
│  │  own ctx  │  │  own ctx  │  │  own ctx  │                   │
│  │  own ydb  │  │  own ydb  │  │  own ydb  │                   │
│  │  own ygit │  │  own ygit │  │  own ygit │                   │
│  └───────────┘  └───────────┘  └───────────┘                   │
└─────────────────────────────────────────────────────────────────┘
```

## 3. Agent Primitives

### 3.1 Core Functions

```clojure
(ns dvergr.agent.primitives
  (:require [is.simm.spindel.runtime.core :as rtc]
            [is.simm.spindel.runtime.context :as ctx]
            [is.simm.spindel.yggdrasil :as ygg]
            [is.simm.spindel.spin.cps :refer [spin]]
            [is.simm.spindel.effects.await :refer [await]]))

(defn ask!
  "Ask agent to perform task. Blocks until complete.

   Returns result map with :child-ctx for merge/discard decision.

   Options:
   - :budget         - Token budget (default: half of remaining)
   - :context-mode   - :summary (default), :full-history, :none
   - :auto-merge-on-success? - Auto-merge if successful (default: false)

   Example:
     (let [result (ask! coder \"Implement feature X\")]
       (show-diff result)
       (merge! result))"
  [agent task & {:as opts}]
  ...)

(defn spawn!
  "Start agent on task. Returns spin immediately.

   Use with combinators for parallel execution:
     (let [[a b] @(all (spawn! agent-1 task-1)
                       (spawn! agent-2 task-2))]
       (merge! a)
       (merge! b))"
  [agent task & {:as opts}]
  ...)

(defn tell!
  "Fire-and-forget message to agent. Returns nil."
  [agent message & {:as opts}]
  ...)
```

### 3.2 Result Handling

```clojure
(defn merge!
  "Merge agent's work to parent context.

   - Git branch merged to parent branch
   - Datahike history retained (linked to parent)

   Returns the result for chaining."
  [result]
  (when-let [child-ctx (:child-ctx result)]
    (ygg/merge-to-parent! child-ctx))
  result)

(defn discard!
  "Discard agent's work.

   - Git branch deleted
   - Datahike history retained for debugging (GC later)

   Returns the result for chaining."
  [result]
  (when-let [child-ctx (:child-ctx result)]
    (ygg/discard-from-parent! child-ctx))
  result)

(defn show-diff
  "Show git diff of agent's changes vs parent."
  [result]
  ...)

(defn successful?
  "Check if agent completed successfully."
  [result]
  (= :complete (:status result)))

(defn extract-result
  "Extract agent's final response text."
  [result]
  ...)
```

### 3.3 Result Structure

```clojure
{:status :complete        ; :complete, :error, :max-turns, :budget-exceeded
 :turns 5                 ; Number of turns taken
 :agent "coder"           ; Agent name
 :isolation :native       ; Isolation mode used
 :result "Done..."        ; Final assistant message
 :messages [...]          ; Full conversation
 :child-ctx <ctx>         ; For merge/discard
 :working-dir "/path..."  ; Worktree path used
 :summary "Implemented..."; Auto-generated summary
 }
```

## 4. Group Coordination

### 4.1 Creating Groups

```clojure
(defn make-group
  "Create a group of coordinating agents.

   Options:
   - :coordination - Coordination pattern (:free-form, :round-robin, :moderated, or fn)
   - :shared-context - Initial shared context string

   Example:
     (make-group \"review-team\"
                 [security-reviewer perf-reviewer style-reviewer]
                 {:coordination :round-robin})"
  [name agents & {:as opts}]
  ...)
```

### 4.2 Coordination Patterns

```clojure
;; Built-in patterns
:free-form    ; Any agent can speak anytime (reactive via signals)
:round-robin  ; Agents take turns in order
:moderated    ; Moderator agent controls who speaks

;; Custom pattern (function)
(fn [group-state messages]
  ;; Returns next agent to speak, or nil if done
  ...)
```

### 4.3 Group Communication

```clojure
;; Agents communicate via shared signal
(defn post!
  "Post message to group (from within agent)."
  [message]
  (let [group-signal (get-group-signal)]
    (swap-signal! group-signal conj
                  {:from (:name *current-agent*)
                   :content message
                   :ts (System/currentTimeMillis)})))

;; Agents react to messages (in their spin)
(spin
  (let [{:keys [new]} (track group-messages-signal)]
    (when (relevant-to-me? new)
      (let [response (think-about new)]
        (post! response)))))
```

### 4.4 Running Groups

```clojure
(defn run-group!
  "Run group on a task. Returns when coordination pattern signals completion.

   Returns:
     {:status :complete
      :group-messages [...]     ; Inter-agent discussion
      :agent-results {...}      ; Per-agent results
      :child-contexts {...}}    ; For selective merge/discard"
  [group task]
  ...)

;; Example usage
(let [result (run-group! review-team "Review src/auth.clj")]
  ;; Merge all agent work
  (doseq [[agent-name ctx] (:child-contexts result)]
    (merge! {:child-ctx ctx})))
```

## 5. Tool Integration

### 5.1 Automatic Working Directory Resolution

Tools resolve paths relative to the current context's git worktree:

```clojure
(defn- get-working-dir
  "Get working directory for current context."
  []
  (if-let [systems (seq (ygg/registered-systems))]
    (let [[_id sys] (first (filter #(= :git (ygg-proto/system-type (val %)))
                                   systems))]
      (when sys (git/worktree-path sys)))
    (System/getProperty "user.dir")))

;; In tool execution
(defn execute [tool-name input ctx]
  (let [cwd (or (:cwd ctx) (get-working-dir))
        tool-ctx (assoc ctx :cwd cwd)]
    ((:execute (get-tool tool-name)) input tool-ctx)))
```

### 5.2 Tool Filtering by Isolation Mode

```clojure
(defn tools-for-agent
  "Filter tools based on agent isolation mode."
  [agent all-tools]
  (case (:isolation agent)
    :native all-tools                    ; Full access including shell
    :sci (dissoc all-tools "shell")      ; No shell
    :shared-sci (dissoc all-tools "shell")))
```

### 5.3 Automatic Tool Logging

All tool uses are automatically logged to agent's private datahike:

```clojure
(defn execute-with-logging [tool-name input ctx]
  (let [start-ts (System/currentTimeMillis)
        result (execute tool-name input ctx)
        end-ts (System/currentTimeMillis)]

    ;; Log to agent's private ydb
    (when-let [ydb (get-agent-db)]
      (d/transact! @ydb
        [{:tool-use/tool tool-name
          :tool-use/input (pr-str input)
          :tool-use/output (pr-str result)
          :tool-use/start-ts start-ts
          :tool-use/duration-ms (- end-ts start-ts)
          :tool-use/success? (= :success (:type result))}]))

    result))
```

### 5.4 Available Tools

| Tool | Description | Isolation |
|------|-------------|-----------|
| `read_file` | Read file contents | All |
| `write_file` | Write file | All |
| `edit_file` | String replace in file | All |
| `clojure_edit` | Structural Clojure editing | All |
| `glob` | Find files by pattern | All |
| `grep` | Search file contents | All |
| `clojure_eval` | Evaluate in SCI sandbox | All |
| `shell` | Execute bash command | `:native` only |
| `datalog_query` | Query code metadata | All |
| `code_query` | Query indexed code | All |
| `repl_history` | Query REPL history | All |

## 6. Prompt & History Handling

### 6.1 System Prompt Composition

```clojure
(defn build-system-prompt [agent task opts]
  (str
    ;; Agent identity
    "You are " (:name agent) ".\n\n"

    ;; Custom system prompt if provided
    (when-let [custom (:system-prompt agent)]
      (str custom "\n\n"))

    ;; Working directory
    "Working directory: " (get-working-dir) "\n\n"

    ;; Available tools
    "Available tools:\n"
    (format-tool-descriptions (tools-for-agent agent)) "\n\n"

    ;; Parent context (if provided)
    (when-let [summary (:parent-summary opts)]
      (str "Context from parent:\n" summary "\n\n"))

    ;; The task
    "Your task: " task))
```

### 6.2 Context Passing Modes

```clojure
;; :summary (default) - LLM-generated summary of parent context
(ask! coder "Implement X" :context-mode :summary)

;; :full-history - Full parent conversation (expensive)
(ask! coder "Implement X" :context-mode :full-history)

;; :none - No parent context
(ask! coder "Implement X" :context-mode :none)

;; :custom - Explicit context string
(ask! coder "Implement X" :context "Based on research: ...")
```

### 6.3 Summary Generation

```clojure
(defn generate-summary
  "Generate LLM summary of conversation for context passing."
  [messages]
  (let [summary-prompt (str "Summarize this conversation concisely, "
                           "focusing on decisions made and key findings:\n\n"
                           (format-messages messages))]
    (call-llm {:messages [{:role :user :content summary-prompt}]
               :max-tokens 500})))
```

### 6.4 History Storage (Datahike Schema)

```clojure
;; Agent's private database schema
{:tool-use/tool       {:db/valueType :db.type/string}
 :tool-use/input      {:db/valueType :db.type/string}
 :tool-use/output     {:db/valueType :db.type/string}
 :tool-use/start-ts   {:db/valueType :db.type/instant}
 :tool-use/duration-ms {:db/valueType :db.type/long}
 :tool-use/success?   {:db/valueType :db.type/boolean}

 :eval/code           {:db/valueType :db.type/string}
 :eval/result         {:db/valueType :db.type/string}
 :eval/success?       {:db/valueType :db.type/boolean}
 :eval/ts             {:db/valueType :db.type/instant}

 :message/role        {:db/valueType :db.type/keyword}
 :message/content     {:db/valueType :db.type/string}
 :message/ts          {:db/valueType :db.type/instant}
 :message/turn        {:db/valueType :db.type/long}}

;; Group database schema (additional)
{:group-message/from    {:db/valueType :db.type/string}
 :group-message/content {:db/valueType :db.type/string}
 :group-message/ts      {:db/valueType :db.type/instant}

 :admin/action          {:db/valueType :db.type/keyword}
 :admin/agent           {:db/valueType :db.type/string}
 :admin/ts              {:db/valueType :db.type/instant}}
```

## 7. Permission Model

### 7.1 Agent Permissions

```clojure
(def permission-levels
  #{:use-tools      ; Can use tools (file ops, eval)
    :spawn-agents   ; Can spawn sub-agents
    :network        ; Can make network requests
    :filesystem     ; Can access filesystem (via tools)
    :admin})        ; Can manage groups, override budgets

(defn make-agent [{:keys [name permissions isolation]
                   :or {permissions #{:use-tools}
                        isolation :native}}]
  ...)
```

### 7.2 Isolation Modes

| Mode | Shell | File Tools | Spawn | Description |
|------|-------|------------|-------|-------------|
| `:native` | Yes | Yes | If permitted | Full power, trusted agent |
| `:sci` | No | Yes | If permitted | Sandboxed, own SCI context |
| `:shared-sci` | No | Yes | If permitted | Shared SCI with group |

### 7.3 Budget Management

```clojure
;; Parent allocates budget to child
(ask! coder "Implement X" :budget 10000)  ; 10k tokens

;; Default: half of parent's remaining budget
(ask! coder "Implement X")  ; Gets (/ (budget-remaining) 2)

;; Agent can check and allocate to sub-agents
(when (can-spawn? *current-agent*)
  (let [my-remaining (budget-remaining)
        child-budget (min 5000 (/ my-remaining 2))]
    (ask! helper "Research Y" :budget child-budget)))
```

## 8. REPL Experience

### 8.1 Startup

```clojure
(require '[dvergr.repl :as r])

;; Start with git + datahike integration
(r/start)
;; => "Dvergr started (git + datahike isolation enabled)"
;; => Sets up *runtime*, *ygit*, *ydb*

;; Or with options
(r/start :repo-path "/path/to/repo"
         :with-git? true
         :with-db? true)
```

### 8.2 Pre-configured Agents

```clojure
;; Available out of the box
r/coder       ; General coding, :native isolation
r/researcher  ; Research tasks, limited turns
r/reviewer    ; Code review, read-heavy

;; Create custom
(def my-agent (r/make-agent {:name "specialist"
                             :system-prompt "You specialize in..."
                             :max-turns 15}))
```

### 8.3 Common Workflows

```clojure
;; Simple ask
(def result (r/ask r/coder "Implement rate limiting"))
(r/show-diff result)
(r/merge! result)

;; Auto-merge on success
@(r/ask r/coder "Fix the typo" :auto-merge-on-success? true)

;; Parallel work
(let [[a b] @(r/all (r/spawn r/coder "Implement feature A")
                    (r/spawn r/coder "Implement feature B"))]
  (r/merge! a)
  (r/merge! b))

;; Review before merge
(let [result (r/ask r/coder "Refactor auth module")]
  (r/show-diff result)
  (println "Files changed:" (:files-changed (r/diff-stats result)))
  (when (r/prompt-yn "Merge changes?")
    (r/merge! result)))

;; Group review
(let [result (r/run-group r/review-team "Review src/core.clj")]
  (println "Discussion:")
  (doseq [msg (:group-messages result)]
    (println (:from msg) ":" (:content msg)))
  (r/merge-all! result))
```

### 8.4 Inspection & Debugging

```clojure
;; See agent's tool uses
(r/tool-history result)

;; See agent's REPL evals
(r/eval-history result)

;; Query agent's database
(r/query result '[:find ?tool ?ts
                  :where [?e :tool-use/tool ?tool]
                         [?e :tool-use/start-ts ?ts]])

;; See conversation
(r/messages result)

;; Get branch info
(r/branch-info result)
;; => {:branch :main-fork-abc
;;     :parent :main
;;     :commits 3
;;     :files-changed ["src/rate_limiter.clj"]}
```

## 9. Implementation Plan

### Phase 1: Core Integration (Foundation)

1. **Update `primitives.clj`**
   - Fork context in `spawn!`/`ask!`
   - Return `{:child-ctx ...}` in results
   - Add `merge!`, `discard!`, `show-diff`
   - Set `:cwd` from yggdrasil worktree

2. **Update tool layer**
   - Auto-resolve working directory
   - Filter tools by isolation mode
   - Add automatic tool logging

3. **Basic REPL API**
   - `start`, `stop`
   - `ask`, `spawn`, `merge!`, `discard!`
   - Pre-configured agents

### Phase 2: History & Database

4. **Datahike per agent**
   - Create yggdrasil datahike adapter integration
   - Schema for tool uses, evals, messages
   - Auto-logging in tool execution

5. **Context passing**
   - Summary generation (LLM call)
   - Context modes (`:summary`, `:full-history`, `:none`)
   - System prompt composition

### Phase 3: Group Coordination

6. **Group abstraction**
   - `make-group`, `run-group!`
   - Group datahike for shared state
   - Messages signal for coordination

7. **Coordination patterns**
   - `:free-form`, `:round-robin`, `:moderated`
   - Custom coordination functions

### Phase 4: Polish & Workflows

8. **REPL helpers**
   - Inspection functions
   - Workflow templates
   - Pretty printing

9. **Pre-built workflows**
   - `research-implement-test`
   - `parallel-review`
   - `iterative-refinement`

### Dependencies

```
Phase 1 ──→ Phase 2 ──→ Phase 3 ──→ Phase 4
   │            │
   └────────────┴──→ Can be used incrementally
```

Each phase delivers usable functionality. Phase 1 alone enables basic agent isolation.

## 10. Example: Complete Nested Workflow

```clojure
(require '[dvergr.repl :as r])

;; Start runtime
(r/start)

;; Define lead agent that coordinates sub-agents
(def lead
  (r/make-agent
    {:name "lead"
     :permissions #{:use-tools :spawn-agents}
     :system-prompt "You coordinate implementation tasks.
                     Break complex tasks into subtasks.
                     Spawn sub-agents for parallel work.
                     Review and merge their results."}))

;; Human kicks off
(def project-result
  (r/ask lead "Build a user authentication system with:
               - JWT token handling
               - Password hashing
               - Rate limiting on login attempts"))

;; Lead agent internally does:
;; 1. Spawns researcher for JWT best practices
;; 2. Spawns coder for password hashing
;; 3. Spawns coder for rate limiting
;; 4. Reviews and merges each
;; 5. Integrates the pieces

;; Human reviews total result
(r/show-diff project-result)
;; Shows combined diff of all merged work

;; Branch hierarchy was:
;; main
;; └── main-fork-lead
;;     ├── main-fork-lead-fork-research (merged)
;;     ├── main-fork-lead-fork-passwords (merged)
;;     └── main-fork-lead-fork-ratelimit (merged)

;; Human approves
(r/merge! project-result)
;; All changes now on main
```

## Appendix A: YggRef Usage

```clojure
;; YggRef resolves from current context's [:external-refs]
(def ygit (ygg/register! (git/create-git-system)))

;; In parent context
@ygit  ; => GitSystem on :main

;; In forked context (automatic)
(let [child-ctx (ctx/fork-context parent-ctx)]
  (binding [rtc/*execution-context* child-ctx]
    @ygit))  ; => GitSystem on :main-fork-xyz (different instance!)

;; Same YggRef, different resolution based on context
```

## Appendix B: Data Flow Summary

```
Human Request
     │
     ▼
┌─────────────┐
│ ask!/spawn! │ ──→ Forks context (git + datahike branch)
└─────────────┘
     │
     ▼
┌─────────────┐
│ Agent Loop  │ ──→ Tools log to private ydb
│             │ ──→ Files written to isolated worktree
└─────────────┘
     │
     ▼
┌─────────────┐
│   Result    │ ──→ Contains :child-ctx
└─────────────┘
     │
     ▼
┌─────────────┐      ┌─────────────┐
│  merge!     │  OR  │  discard!   │
│ git merge   │      │ git delete  │
│ history kept│      │ history kept│
└─────────────┘      └─────────────┘
```
