(ns dev.agent-playground
  "Interactive playground for running and debugging coding agents.

   This file demonstrates:
   1. Creating a shared context with git + Datahike forking
   2. Running a coding agent in an isolated fork
   3. Sending feedback to the agent interactively
   4. Querying the Datahike database to inspect agent behavior
   5. Merging or discarding agent work

   Usage:
   1. Start nREPL: clj -M -m nrepl.cmdline --port 0
   2. Connect your editor
   3. Evaluate forms in order")

;; ============================================================================
;; 1. Load Dependencies
;; ============================================================================

(comment
  ;; Evaluate this block first to load all dependencies
  (require '[dvergr.agents.core :as agent] :reload)
  (require '[dvergr.agents.coding :as coding] :reload)
  (require '[dvergr.chat.schema :as schema] :reload)
  (require '[is.simm.spindel.runtime.core :as rtc])
  (require '[is.simm.spindel.spin.cps :refer [spin]])
  (require '[is.simm.spindel.effects.await :refer [await]])
  (require '[datahike.api :as d])
  (println "Dependencies loaded!"))

;; ============================================================================
;; 2. Create Shared Context
;; ============================================================================

(comment
  ;; Create shared context with git worktrees and persistent Datahike
  ;; This creates:
  ;; - Git system for isolated worktrees (agent code changes are isolated)
  ;; - Datahike file store at .datahike/ (survives REPL crashes)
  ;; Both auto-fork when using dvergr.agents.primitives/ask!

  (def ctx (agent/create-shared-context))
  ;; => Registered Datahike system at /path/to/.datahike
  )

;; ============================================================================
;; 3. Start Coding Agent in Fork
;; ============================================================================

(comment
  ;; Run agent in isolated fork
  ;; The fork creates:
  ;; - Isolated git worktree for code changes
  ;; - Isolated Datahike branch for conversation history

  ;; For fork-isolated tasks, use dvergr.agents.primitives:
  ;; (require '[dvergr.agents.primitives :as prim] :reload)
  ;; (require '[dvergr.agent.core :as agent-config] :reload)
  ;;
  ;; (def coder-config (agent-config/make-agent
  ;;                     {:name "wiki-parser"
  ;;                      :provider :fireworks
  ;;                      :model "accounts/fireworks/models/kimi-k2p5"
  ;;                      :isolation :sci}))
  ;; (binding [rtc/*execution-context* ctx]
  ;;   (def result @(prim/ask! coder-config "Implement a wiki-link parser..."
  ;;                           {:budget-dollars 0.50})))
  ;; (prim/merge! result)  ;; or (prim/discard! result)

  ;; Or use a long-lived process agent:
  (binding [rtc/*execution-context* ctx]
    (def coder (coding/start-coding-agent!
                 {:id :wiki-parser
                  :provider :fireworks
                  :model "accounts/fireworks/models/kimi-k2p5"
                  :budget-dollars 0.50
                  :verbose? true}))

    ;; Send task
    (agent/send! coder "Implement a wiki-link parser...")

    ;; Wait for completion
    @(spin (await (:outbox coder))))
  )

;; ============================================================================
;; 4. Send Feedback to Agent (Interactive Loop)
;; ============================================================================

(comment
  ;; After agent signals completion, you can:
  ;; a) Approve the work
  ;; b) Send feedback for revisions

  ;; Check current status
  (agent/status coder)

  ;; Send feedback (agent will continue working)
  (binding [rtc/*execution-context* ctx]
    (agent/send! coder {:feedback "The tests failed with assertion error.
The extract-entities function uses 'distinct' but the test expects duplicates.
Please fix either the test or the implementation to be consistent.
Then run the tests again with clojure_eval before calling signal_completion."}))

  ;; Wait for next completion
  (binding [rtc/*execution-context* ctx]
    @(spin (await (:outbox coder))))

  ;; Or approve if satisfied
  (binding [rtc/*execution-context* ctx]
    (agent/send! coder {:approved true}))
  )

;; ============================================================================
;; 5. Query Datahike to Inspect Agent Behavior
;; ============================================================================

(comment
  ;; Find the fork branch name from the worktrees
  ;; ls .git-worktrees/

  ;; Connect to the forked branch and query tool uses
  (let [fork-branch :db-fork-XXXXX  ;; Replace with actual branch
        db-cfg {:store {:backend :file
                        :path ".datahike"
                        :id #uuid "8862ad9e-8dd3-3a9a-b1f4-0442b23f7659"}
                :keep-history? true
                :schema-flexibility :write
                :branch fork-branch}
        conn (d/connect db-cfg)
        db @conn]

    ;; List all tool uses
    (println "=== Tool Uses ===")
    (let [tool-uses (d/q '[:find ?name ?id
                           :where
                           [?e :tool-use/name ?name]
                           [?e :tool-use/id ?id]]
                         db)]
      (doseq [[name id] (sort-by second tool-uses)]
        (println name)))

    (d/release conn))

  ;; Get detailed tool inputs
  (let [fork-branch :db-fork-XXXXX
        db-cfg {:store {:backend :file
                        :path ".datahike"
                        :id #uuid "8862ad9e-8dd3-3a9a-b1f4-0442b23f7659"}
                :keep-history? true
                :schema-flexibility :write
                :branch fork-branch}
        conn (d/connect db-cfg)
        db @conn]

    ;; Get clojure_eval inputs (to see what code was tested)
    (d/q '[:find ?code
           :where
           [?tu :tool-use/name "clojure_eval"]
           [?tu :tool-use/input ?input]
           [?input :tool-input.clojure-eval/code ?code]]
         db))
  )

;; ============================================================================
;; 6. Check Files Created by Agent
;; ============================================================================

(comment
  ;; List worktrees
  ;; $ ls -la .git-worktrees/

  ;; Find files created in the fork
  ;; $ find .git-worktrees/main-fork-XXXXX -name "*.clj" -newer .git-worktrees/main-fork-XXXXX/.git

  ;; Read the implementation
  (slurp ".git-worktrees/main-fork-XXXXX/src/dvergr/knowledge/links.clj")

  ;; Read the tests
  (slurp ".git-worktrees/main-fork-XXXXX/test/dvergr/knowledge/links_test.clj")
  )

;; ============================================================================
;; 7. Merge or Discard Agent Work
;; ============================================================================

(comment
  ;; For fork-isolated results (from primitives/ask!):
  ;; (require '[dvergr.agents.primitives :as prim])
  ;; (prim/merge! result)   ;; merge agent's work
  ;; (prim/discard! result) ;; or discard
  )

;; ============================================================================
;; 8. Stop Agent
;; ============================================================================

(comment
  ;; Stop the agent loop
  (binding [rtc/*execution-context* ctx]
    (agent/stop! coder))
  )

;; ============================================================================
;; Quick Reference: Available Models
;; ============================================================================

(comment
  ;; Fireworks models (fast, cost-effective)
  "accounts/fireworks/models/kimi-k2p5"                    ;; Good for tool use
  "accounts/fireworks/models/qwen3-coder-480b-a35b-instruct" ;; Best for code
  "accounts/fireworks/models/kimi-k2-thinking"             ;; Advanced reasoning
  "accounts/fireworks/models/deepseek-v3"                  ;; General purpose

  ;; Anthropic models
  "claude-sonnet-4-5-20250514"
  "claude-opus-4-20250514"
  "claude-3-5-haiku-20241022"
  )

;; ============================================================================
;; Quick Reference: Agent Communication
;; ============================================================================

(comment
  ;; Send task
  (agent/send! coder "Your task here...")

  ;; Send feedback after completion
  (agent/send! coder {:feedback "Please fix X..."})

  ;; Approve work
  (agent/send! coder {:approved true})

  ;; Check status
  (agent/status coder)  ;; => {:status :running, :turn 5, ...}

  ;; Pause/resume
  (agent/pause! coder)
  (agent/resume! coder)

  ;; Stop
  (agent/stop! coder)
  )
