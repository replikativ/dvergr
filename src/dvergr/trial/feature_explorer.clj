(ns dvergr.trial.feature-explorer
  "Feature explorer agent for living software.

   Analyzes activity patterns and implements improvements in a forked context.
   Works autonomously using coding tools until it signals completion.

   Architecture:
   - Uses ask! which auto-forks spindel context
   - App tools (list-notes, search!, etc.) auto-use forked datahike via YggRef
   - Coding tools (read_file, write_file, clojure_eval) work in forked context
   - Agent signals completion with summary when done
   - Human reviews and approves/rejects the fork

   Usage:
     (require '[dvergr.trial.feature-explorer :as explorer])
     (app/init!)
     (sim/simulate-mixed 42)  ; create activity

     ;; Run explorer
     (def result @(explorer/explore! \"Analyze search patterns and add helpful suggestions\"))

     ;; Review
     (explorer/show-summary result)
     (explorer/show-files-changed result)

     ;; Decide
     (explorer/approve! result)
     ;; or
     (explorer/reject! result)"
  (:require [dvergr.agent.task :as prim]
            [dvergr.agent.config :as agent]
            [dvergr.tools :as tools]
            [dvergr.sandbox :as sandbox]
            [dvergr.trial.app :as app]
            [org.replikativ.spindel.engine.core :as rtc]
            [org.replikativ.spindel.core :refer [spin]]
            [org.replikativ.spindel.core :refer [await]]
            [clojure.string :as str]
            [clojure.edn :as edn]))

;; ============================================================================
;; Completion Extraction
;; ============================================================================

(defn- extract-completion-from-messages
  "Extract completion summary from agent messages.
   Looks for signal_complete tool use and its result.

   Tries two approaches:
   1. Look for :message/tool-uses in assistant messages (preferred)
   2. Look for 'Exploration complete' in tool-result messages (fallback)"
  [messages]
  ;; Primary: Look for signal_complete in tool-uses
  (let [tool-uses (->> messages
                       (filter #(= :assistant (:message/role %)))
                       (mapcat :message/tool-uses)
                       (filter #(= "signal_complete" (:tool-use/name %))))]
    (if-let [complete-tool (last tool-uses)]
      ;; Found in tool-uses - input is now a structured entity
      ;; Keys are namespaced: :tool-input.signal-complete/summary
      (let [input (:tool-use/input complete-tool)
            ;; Handle both namespaced (from DB) and plain (from runtime) keys
            summary (or (:tool-input.signal-complete/summary input)
                        (:summary input))
            files (or (:tool-input.signal-complete/files-changed input)
                      (:files_changed input))]
        {:summary summary
         :files-changed files})
      ;; Fallback: Look for completion in tool-result content
      (when-let [completion-result (->> messages
                                         (filter #(= :tool-result (:message/role %)))
                                         (filter #(str/starts-with?
                                                    (str (:message/content %))
                                                    "Exploration complete"))
                                         last)]
        ;; Parse the completion result
        (let [content (:message/content completion-result)
              ;; Extract summary (between "Summary:\n" and "\n\nFiles changed:" or end)
              summary-match (re-find #"Summary:\n([\s\S]*?)(?:\n\nFiles changed:|$)" content)
              summary (when summary-match (str/trim (second summary-match)))
              ;; Extract files changed
              files-match (re-find #"Files changed:\n([\s\S]*?)$" content)
              files (when files-match
                      (->> (str/split-lines (second files-match))
                           (map str/trim)
                           (filter #(str/starts-with? % "- "))
                           (map #(subs % 2))
                           vec))]
          (when summary
            {:summary summary
             :files-changed files}))))))

;; ============================================================================
;; Signal Complete Tool (only tool needed - app access via SCI)
;; ============================================================================

(def signal-complete-tool
  "Tool for signaling exploration completion.
   Passed via local tool context, not global registry."
  {:name "signal_complete"
   :description "Signal that you are done exploring. Call this when your implementation is complete.

   IMPORTANT: Call this tool when you have:
   1. Analyzed the activity patterns
   2. Implemented a feature or improvement
   3. Tested it works (via clojure_eval)

   Provide a summary of what you built and how it helps users."
   :parameters {:type "object"
                :properties {:summary {:type "string"
                                       :description "Summary of what you implemented"}
                             :files_changed {:type "array"
                                             :items {:type "string"}
                                             :description "List of files you created or modified"}}
                :required ["summary"]}
   :execute (fn [{:keys [summary files_changed]} _]
              {:type :success
               :content (str "Exploration complete. Summary:\n" summary
                             (when (seq files_changed)
                               (str "\n\nFiles changed:\n"
                                    (str/join "\n" (map #(str "  - " %) files_changed)))))
               :metadata {:completed true
                          :summary summary
                          :files-changed files_changed}})})

;; ============================================================================
;; Explorer System Prompt
;; ============================================================================

(def explorer-system-prompt
  "You are a Feature Explorer agent in a living software system.

Your job is to discover how users are using the app, find pain points or opportunities, and implement an improvement.

## Your Environment
- You are running in a FORKED context - your changes are isolated
- Changes won't affect the main app until approved
- You have full access to Clojure via clojure_eval
- Your code should be MERGEABLE - write proper Clojure that can go into the codebase

## Writing Proper Clojure

IMPORTANT: Always use standard require statements. Your code will be merged back into the codebase.

```clojure
;; CORRECT - use require for standard library namespaces
(require '[clojure.string :as str])
(str/lower-case \"HELLO\")

(require '[clojure.set :as set])
(set/union #{1 2} #{3 4})

;; The app namespace is pre-loaded, no require needed
(app/list-notes)
```

## App Namespace (pre-loaded)

The `app` namespace is available without require:

```clojure
(app/list-notes)           ; all notes
(app/search! \"query\")      ; search by content
(app/activity-summary)     ; usage statistics
@app/activity              ; raw activity data {:searches [...] :creates [...]}
(app/detect-patterns)      ; pattern detection
(app/create-note! \"content\" [:tag1])  ; create note
```

## Tools
- clojure_eval: Evaluate Clojure code - use this for everything
- read_file, write_file, edit_file: File operations (for persistent code)
- glob, grep: Find files and content
- signal_complete: MUST call when done (see below)

## Your Workflow

1. EXPLORE: Investigate how the app is being used
   - What does the activity data show?
   - What are users doing? What's working? What's not?

2. DISCOVER: Find a problem or opportunity
   - Don't assume - let the data tell you
   - Look for patterns, frustrations, unmet needs

3. IMPLEMENT: Build something useful
   - First require any namespaces you need (clojure.string, etc.)
   - Define functions with clojure_eval
   - Keep it simple and focused
   - Test as you go

4. VERIFY: Make sure it works
   - Run your code, check the results

5. COMPLETE: You MUST call signal_complete when done
   - Summarize what you discovered and built
   - List any files you changed

## CRITICAL: Completion

When you are done, you MUST call the signal_complete tool. Do not just stop.
Your work is not complete until you explicitly call signal_complete with a summary.

## Guidelines
- Explore first, then decide what to build
- Focus on ONE concrete improvement
- Write proper Clojure with require statements
- Keep implementations simple (5-30 lines)
- Code should be mergeable into the codebase")

;; ============================================================================
;; Explorer Agent
;; ============================================================================

(defn make-explorer-agent
  "Create a feature explorer agent.

   Options:
   - :model      - LLM model (default: kimi-k2p5)
   - :provider   - LLM provider (default: :fireworks)

   Runs until natural termination (budget exhausted or task complete)."
  [& {:keys [model provider]
      :or {model "accounts/fireworks/models/kimi-k2p5"
           provider :fireworks}}]
  ;; Combine base tools with signal-complete-tool via local context
  ;; Must be a set - spec requires :all, set?, or fn?
  (let [explorer-tools (-> (tools/all-tools) set (conj signal-complete-tool))]
    (agent/make-agent
      {:name "feature-explorer"
       :model model
       :provider provider
       :tools explorer-tools  ; explicit tools, not global :all
       :permissions #{:use-tools}
       :isolation :sci  ; SCI sandbox - can eval code, no filesystem writes
       :system-prompt explorer-system-prompt})))

;; ============================================================================
;; Default Goal
;; ============================================================================

(def default-goal
  "Explore how the app is being used. Look at the activity data, notes, and usage patterns.
Find something that could be improved and implement a solution.
When you're done, call signal_complete with a summary of what you discovered and built.")

;; ============================================================================
;; Main API
;; ============================================================================

(defn explore!
  "Launch a feature explorer to discover issues and implement improvements.

   The explorer runs in a forked context and must discover problems on its own
   by exploring the app's activity data, notes, and usage patterns.

   The agent has access to:
   - App namespace via clojure_eval: (app/list-notes), (app/activity-summary), etc.
   - Coding tools: read_file, write_file, clojure_eval, glob, grep
   - signal_complete to finish (MUST be called)

   Args:
     goal - Optional custom goal. If not provided, uses open-ended default.

   Options:
     :model         - LLM model (default: kimi-k2p5)
     :provider      - Provider (default: :fireworks)
     :budget-dollars - Budget in dollars (default: $0.50)

   Runs until natural termination (budget exhausted or task complete).

   Returns a Spin that resolves to result map.

   Example:
     ;; Open-ended exploration (recommended)
     (def result @(explore!))

     ;; Or with custom goal
     (def result @(explore! \"Focus on improving search\"))

     (show-summary result)
     (approve! result)"
  [& args]
  (let [;; Parse args: optional goal string, then keyword args
        [goal opts] (if (and (seq args) (string? (first args)))
                      [(first args) (rest args)]
                      [nil args])
        {:keys [model provider budget-dollars]
         :or {model "accounts/fireworks/models/kimi-k2p5"
              provider :fireworks
              budget-dollars 0.50}} (apply hash-map opts)

        ;; Use provided goal or default
        task (or goal default-goal)

        ;; Create agent
        explorer (make-explorer-agent :model model
                                       :provider provider)]

    (println "\n" (apply str (repeat 60 "=")) "\n")
    (println "       FEATURE EXPLORER")
    (println "\n" (apply str (repeat 60 "=")) "\n")
    (println "Goal:" task)
    (println "Model:" model)
    (println "Budget: $" budget-dollars)
    (println "\nStarting exploration...\n")

    ;; Run exploration - ask! handles forking
    ;; setup-sci-fn adds the trial app namespace to SCI so agent can use (app/...)
    (spin
      (let [result (await (prim/ask! explorer task
                                      {:budget-dollars budget-dollars
                                       :setup-sci-fn sandbox/add-trial-app-ns!}))
            ;; Extract completion from messages
            completion (extract-completion-from-messages (:messages result))]
        (assoc result
               :completion completion
               :goal goal)))))

;; ============================================================================
;; Result Inspection
;; ============================================================================

(defn show-summary
  "Show the explorer's completion summary."
  [result]
  (println "\n" (apply str (repeat 60 "-")))
  (println "EXPLORATION SUMMARY")
  (println (apply str (repeat 60 "-")) "\n")

  (println "Status:" (:status result))
  (println "Turns:" (:turns result))
  (println "Goal:" (:goal result))

  (when-let [completion (:completion result)]
    (println "\n--- What was built ---")
    (println (:summary completion))
    (when (seq (:files-changed completion))
      (println "\n--- Files changed ---")
      (doseq [f (:files-changed completion)]
        (println "  -" f))))

  (println "\n" (apply str (repeat 60 "-"))))

(defn show-conversation
  "Show the full conversation history."
  [result]
  (println "\n--- Conversation History ---\n")
  (doseq [msg (:messages result)]
    (println (str "[" (name (:message/role msg)) "]"))
    (println (subs (:message/content msg) 0 (min 500 (count (:message/content msg)))))
    (println)))

(defn show-tool-uses
  "Show all tool calls made by the explorer."
  [result]
  (let [tool-uses (prim/extract-tool-uses result)]
    (println "\n--- Tool Uses (" (count tool-uses) ") ---\n")
    (doseq [{:keys [tool-use/name tool-use/input]} tool-uses]
      (println name ":" (pr-str input)))))

(defn show-errors
  "Show all errors from tool results for debugging.
   Looks for actual tool errors, not just file contents containing 'error'."
  [result]
  (println "\n--- Errors ---\n")
  (let [tool-results (->> (:messages result)
                          (filter #(= :tool-result (:message/role %)))
                          (map :message/content))]
    (doseq [content tool-results]
      ;; Only match actual error patterns from tools, not file contents
      (when (or (str/starts-with? (str content) "Evaluation error:")
                (str/starts-with? (str content) "Error:")
                (str/starts-with? (str content) "File not found:")
                (str/starts-with? (str content) "Path is a directory")
                (re-find #"^[A-Z][a-z]+Exception:" (str content)))
        (println "---")
        (println content)
        (println))))
  (println "--- End Errors ---"))

(defn show-full-conversation
  "Show full conversation with complete content (no truncation)."
  [result]
  (println "\n" (apply str (repeat 70 "=")) "\n")
  (println "FULL CONVERSATION HISTORY")
  (println (apply str (repeat 70 "=")) "\n")
  (doseq [msg (:messages result)]
    (println (str "\n[" (name (:message/role msg)) "]"))
    (println (apply str (repeat 50 "-")))
    (println (:message/content msg)))
  (println "\n" (apply str (repeat 70 "="))))

(defn debug-result
  "Print comprehensive debug info about the result."
  [result]
  (println "\n" (apply str (repeat 70 "=")) "\n")
  (println "DEBUG INFO")
  (println (apply str (repeat 70 "=")) "\n")

  (println "Status:" (:status result))
  (println "Turns:" (:turns result))
  (println "Agent:" (:agent result))
  (println "Isolation:" (:isolation result))
  (println "Working dir:" (:working-dir result))
  (println "Message count:" (count (:messages result)))

  ;; Show tool uses
  (let [tool-uses (prim/extract-tool-uses result)]
    (println "\nTool calls:" (count tool-uses))
    (doseq [{:keys [tool-use/name]} tool-uses]
      (println "  -" name)))

  ;; Show errors - only actual tool errors, not file content matches
  (println "\n--- Errors found in tool results ---")
  (let [errors (->> (:messages result)
                    (filter #(= :tool-result (:message/role %)))
                    (map :message/content)
                    (filter #(or (str/starts-with? (str %) "Evaluation error:")
                                 (str/starts-with? (str %) "Error:")
                                 (str/starts-with? (str %) "File not found:")
                                 (str/starts-with? (str %) "Path is a directory")
                                 (re-find #"^[A-Z][a-z]+Exception:" (str %)))))]
    (if (seq errors)
      (doseq [err errors]
        (println err)
        (println "---"))
      (println "No errors found")))

  (println "\n" (apply str (repeat 70 "="))))

;; ============================================================================
;; Merge/Discard
;; ============================================================================

(defn approve!
  "Approve and merge the explorer's changes to main context."
  [result]
  (println "Merging explorer's changes to main...")
  (prim/merge! result)
  (println "Done. Changes are now in main context."))

(defn reject!
  "Reject and discard the explorer's changes."
  [result]
  (println "Discarding explorer's changes...")
  (prim/discard! result)
  (println "Done. Changes discarded."))

;; ============================================================================
;; Convenience
;; ============================================================================

(defn quick-explore!
  "Quick exploration with default settings. Blocks until complete."
  [goal]
  @(explore! goal))

;; ============================================================================
;; REPL Usage
;; ============================================================================

(comment
  ;; Setup
  (require '[dvergr.trial.app :as app])
  (require '[dvergr.trial.simulation :as sim])
  (require '[dvergr.trial.feature-explorer :as explorer] :reload)

  (app/init!)
  (sim/simulate-mixed 42)

  ;; Run exploration
  (def result @(explorer/explore!
                 "Analyze search patterns. Many searches return 0 results - help users find content."))

  ;; Or quick version
  (def result (explorer/quick-explore! "Help users find content better"))

  ;; Inspect results
  (explorer/show-summary result)
  (explorer/show-conversation result)
  (explorer/show-tool-uses result)

  ;; Decide
  (explorer/approve! result)
  ;; or
  (explorer/reject! result)

  ;; Cleanup
  (app/stop!))
