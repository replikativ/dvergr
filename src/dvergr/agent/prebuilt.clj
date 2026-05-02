(ns dvergr.agent.prebuilt
  "Pre-built agent configurations with specialized prompts.

   These agents are ready-to-use with appropriate tool permissions
   and system prompts optimized for their roles."
  (:require [dvergr.agent.config :as agent]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; ============================================================================
;; Prompt Loading
;; ============================================================================

(defn- load-prompt
  "Load agent prompt from agents/ directory."
  [filename]
  (try
    ;; Try resource path first (for packaged deployments)
    (-> (str "agents/" filename)
        io/resource
        slurp)
    (catch Exception e
      ;; Fallback: try relative path from project root
      (try
        (slurp (str "agents/" filename))
        (catch Exception e2
          ;; Final fallback: try from working directory
          (try
            (slurp (str (System/getProperty "user.dir") "/agents/" filename))
            (catch Exception e3
              (throw (ex-info (str "Could not load prompt: " filename)
                             {:filename filename
                              :attempted-paths ["agents/"
                                               (str (System/getProperty "user.dir") "/agents/")]
                              :cause (.getMessage e3)})))))))))

(defn- build-system-prompt
  "Build complete system prompt from agent prompt + context."
  [agent-prompt-md & {:keys [cwd isolation tools]}]
  (str agent-prompt-md
       "\n\n## Current Context\n\n"
       (when cwd
         (str "- Working directory: " cwd "\n"))
       (when isolation
         (str "- Isolation mode: " (name isolation) "\n"))
       (when tools
         (str "- Available tools: " (str/join ", " (map name tools)) "\n"))))

;; ============================================================================
;; Pre-Built Agents
;; ============================================================================

(defn researcher
  "Create a researcher agent optimized for information gathering and analysis.

   The researcher has:
   - Read-only tools (read_file, glob, grep, code_query, web_search)
   - Native execution (fast, trusted)
   - Specialized prompt for systematic research

   Options: same as agent/make-agent (override defaults)"
  [& {:keys [name model provider tools isolation]
      :or {name "researcher"
           tools #{:read-file :glob :grep :code-query :web-search}
           isolation :native}}]
  (let [prompt-md (load-prompt "researcher.md")
        system-prompt (build-system-prompt prompt-md
                                           :isolation isolation
                                           :tools tools)]
    (agent/make-agent
      {:name name
       :model (or model "claude-sonnet-4-5-20250514")
       :provider (or provider :anthropic)
       :tools tools
       :isolation isolation
       :system-prompt system-prompt})))

(defn coder
  "Create a coder agent optimized for implementation and testing.

   The coder has:
   - Full file manipulation tools (read, write, edit)
   - REPL evaluation (clojure_eval in SCI sandbox)
   - Shell access for running tests
   - SCI isolation by default (sandboxed execution)
   - Specialized prompt for TDD and code quality

   Options: same as agent/make-agent (override defaults)"
  [& {:keys [name model provider tools isolation]
      :or {name "coder"
           tools #{:read-file :write-file :edit-file :clojure-edit
                   :shell :clojure-eval :code-query :glob}
           isolation :sci}}]  ; Sandboxed by default
  (let [prompt-md (load-prompt "coder.md")
        system-prompt (build-system-prompt prompt-md
                                           :isolation isolation
                                           :tools tools)]
    (agent/make-agent
      {:name name
       :model (or model "claude-sonnet-4-5-20250514")
       :provider (or provider :anthropic)
       :tools tools
       :isolation isolation
       :system-prompt system-prompt})))

(defn reviewer
  "Create a reviewer agent optimized for code review and quality analysis.

   The reviewer has:
   - Read-only tools (cannot modify code)
   - Code analysis tools (read, grep, code_query)
   - Shell access for running tests (read-only checks)
   - Native execution (fast, trusted)
   - Specialized prompt for constructive review

   Options: same as agent/make-agent (override defaults)"
  [& {:keys [name model provider tools isolation]
      :or {name "reviewer"
           tools #{:read-file :glob :grep :code-query :shell}  ; Read-only
           isolation :native}}]
  (let [prompt-md (load-prompt "reviewer.md")
        system-prompt (build-system-prompt prompt-md
                                           :isolation isolation
                                           :tools tools)]
    (agent/make-agent
      {:name name
       :model (or model "claude-sonnet-4-5-20250514")
       :provider (or provider :anthropic)
       :tools tools
       :isolation isolation
       :system-prompt system-prompt})))

;; ============================================================================
;; Helper: Custom Agent from Prompt
;; ============================================================================

(defn from-prompt
  "Create agent from a custom prompt file.

   Useful for project-specific agents:
   (from-prompt \"my-project-agent.md\"
                :tools #{:read-file :write-file}
                :isolation :sci)

   The prompt file should be in agents/ directory."
  [prompt-filename & {:keys [name model provider tools isolation]
                      :or {name "custom"
                           tools :all
                           isolation :native}}]
  (let [prompt-md (load-prompt prompt-filename)
        system-prompt (build-system-prompt prompt-md
                                           :isolation isolation
                                           :tools tools)]
    (agent/make-agent
      {:name name
       :model (or model "claude-sonnet-4-5-20250514")
       :provider (or provider :anthropic)
       :tools tools
       :isolation isolation
       :system-prompt system-prompt})))

(comment
  ;; Create pre-built agents
  (def my-researcher (researcher))
  (def my-coder (coder))
  (def my-reviewer (reviewer))

  ;; Customize pre-built agents
  (def fast-researcher (researcher :model "claude-haiku-4-20250514"))

  (def trusted-coder (coder :isolation :native  ; Override default :sci
                            :name "trusted-impl"))

  ;; Create custom agent from prompt
  (def domain-expert (from-prompt "domain-expert.md"
                                  :tools #{:read-file :code-query}
                                  :isolation :native)))
