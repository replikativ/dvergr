(ns dvergr.tools
  "Tool registry and execution for the agent harness."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [datahike.api :as d]
            [dvergr.sandbox :as sandbox]
            [dvergr.storage :as storage]
            [dvergr.tools.structural :as structural]
            [dvergr.tools.code-metadata :as code-meta]
            [dvergr.chat.compaction :as compaction]
            [org.replikativ.spindel.engine.core :as rtc])
  (:import [java.io File]
           [java.util.concurrent TimeUnit]))

;; ---------------------------------------------------------------------------
;; Tool Registry
;; ---------------------------------------------------------------------------

(def registry (atom {}))

(defn register!
  "Register a tool in the registry."
  [tool-def]
  (swap! registry assoc (:name tool-def) tool-def))

(defn get-tool [name]
  (get @registry name))

(defn all-tools []
  (vals @registry))

(defn tool-definitions
  "Return tool definitions in the format expected by LLM APIs.
   Returns both :input_schema (Anthropic) and :parameters (OpenAI).

   If tools-map is provided, uses those tools. Otherwise uses all registered tools."
  ([] (tool-definitions @registry))
  ([tools-map]
   (mapv (fn [{:keys [name description parameters]}]
           {:name name
            :description description
            :input_schema parameters
            :parameters parameters})
         (vals tools-map))))

;; ---------------------------------------------------------------------------
;; Agent Roles & Capability Scoping
;; ---------------------------------------------------------------------------

(def role-capabilities
  "Map of role -> set of allowed tool names.
   nil means unrestricted (all tools allowed).
   Roles reduce the tool schema sent to the LLM (fewer input tokens)
   and prevent unintended actions."
  {:researcher   #{"read_file" "glob" "grep" "datalog_query" "knowledge_search"
                   "clojure_eval" "repl_history" "code_query" "fulltext_search"}
   :coder        #{"read_file" "write_file" "edit_file" "clojure_edit" "glob"
                   "grep" "shell" "clojure_eval" "datalog_query" "run_tests"
                   "code_query" "repl_history" "clj_kondo"}
   :reviewer     #{"read_file" "glob" "grep" "datalog_query" "code_query"
                   "clojure_eval" "repl_history" "clj_kondo"}
   :planner      #{"read_file" "glob" "grep" "datalog_query" "knowledge_search"
                   "request_plan_review"}
   :unrestricted nil})

(defn tools-for-role
  "Filter a tools map by agent role.
   Returns only the tools allowed for the given role.
   If role is nil or :unrestricted, returns all tools."
  [tools-map role]
  (if-let [allowed (get role-capabilities role)]
    (select-keys tools-map (filter allowed (keys tools-map)))
    tools-map))

(defn role-allows-tool?
  "Check if a role is allowed to use a specific tool.
   Returns true if role is nil, :unrestricted, or tool is in allowed set."
  [role tool-name]
  (if-let [allowed (get role-capabilities role)]
    (contains? allowed tool-name)
    true))

;; ---------------------------------------------------------------------------
;; Execution Context
;; ---------------------------------------------------------------------------

(defn make-context
  "Create an execution context for tool calls.

   Options:
   - :cwd           - Working directory
   - :sci-ctx       - SCI context for sandboxed eval
   - :db-conn       - Datahike connection for queries
   - :chat-ctx      - ChatContext for budget tracking
   - :tools         - Local tool registry map (checked before global registry)
   - :isolation     - Execution isolation mode (:native, :sci, :shared-sci)
                      When :native, clojure_eval uses real Clojure eval instead of SCI
   - :eval-ns       - Atom holding current namespace for native eval (default: user)
   - :execution-ctx - Spindel execution context (for spawn_agent tool)"
  [{:keys [cwd sci-ctx db-conn chat-ctx tools isolation eval-ns execution-ctx]}]
  {:cwd (or cwd (System/getProperty "user.dir"))
   :sci-ctx sci-ctx
   :db-conn db-conn
   :chat-ctx chat-ctx
   :tools tools  ; Local tool registry - checked first by execute
   :isolation isolation
   :eval-ns (or eval-ns (atom (the-ns 'user)))
   :execution-ctx execution-ctx
   :abort (atom false)})

(defn execute
  "Execute a tool by name with given input and context.

   Looks up tool in this order:
   1. Check role permissions (if :agent-role in ctx)
   2. ctx[:tools] - local/scoped tool registry (if provided)
   3. Global registry - standard registered tools

   Tool outputs are automatically truncated to prevent context overflow.
   Max output is ~15K tokens (~60K chars) with middle-preserving truncation."
  [tool-name input ctx]
  ;; Check role permissions first
  (if (and (:agent-role ctx) (not (role-allows-tool? (:agent-role ctx) tool-name)))
    {:type :error
     :error (str "Tool " tool-name " is not available for role " (name (:agent-role ctx))
                 ". Allowed tools: " (str/join ", " (get role-capabilities (:agent-role ctx))))}
    (if-let [tool (or (get-in ctx [:tools tool-name])  ; Local registry first
                      (get-tool tool-name))]            ; Then global registry
      (try
        (-> (if-let [exec-fn (:execute tool)]
              (exec-fn input ctx)
              (when-let [handler-fn (:handler tool)]
                (handler-fn input)))
            (compaction/truncate-tool-result))
        (catch Exception e
          {:type :error
           :error (.getMessage e)
           :exception (class e)}))
      {:type :error
       :error (str "Unknown tool: " tool-name)})))

(defn execute-parallel
  "Execute multiple tool calls in parallel."
  [tool-calls ctx]
  (let [futures (mapv (fn [{:keys [id name input]}]
                        {:id id
                         :future (future (execute name input ctx))})
                      tool-calls)]
    (mapv (fn [{:keys [id future]}]
            {:id id
             :result (deref future 60000 {:type :error :error "Timeout"})})
          futures)))

;; ---------------------------------------------------------------------------
;; Code Indexing Hooks
;; ---------------------------------------------------------------------------

(defn- index-file-if-clojure!
  "Automatically index a Clojure file after writing if a DB connection exists.

   NOTE: Currently a no-op. Will be connected to ChatContext's datahike in future."
  [file-path db-conn]
  ;; TODO: Connect to ChatContext's db-conn when available in tool context
  nil)

;; ---------------------------------------------------------------------------
;; Core Tools
;; ---------------------------------------------------------------------------

(register!
  {:name "read_file"
   :description "Read the contents of a file at the given path. Returns the file contents as a string."
   :parameters {:type "object"
                :properties {:path {:type "string"
                                    :description "The absolute or relative path to the file"}}
                :required ["path"]}
   :execute (fn [{:keys [path]} {:keys [cwd]}]
              (let [file (if (fs/absolute? path)
                           (fs/file path)
                           (fs/file cwd path))]
                (if (fs/exists? file)
                  (if (fs/directory? file)
                    {:type :error
                     :error (str "Path is a directory, not a file: " path)
                     :suggestion "Use glob to list directory contents"}
                    {:type :success
                     :content (slurp file)
                     :metadata {:path (str file)
                                :size (fs/size file)}})
                  {:type :error
                   :error (str "File not found: " path)
                   :suggestion "Check if the path is correct. Use glob to find files."})))})

(register!
  {:name "write_file"
   :description "Write content to a file. Creates the file if it doesn't exist, overwrites if it does."
   :parameters {:type "object"
                :properties {:path {:type "string"
                                    :description "The path to write to"}
                             :content {:type "string"
                                       :description "The content to write"}}
                :required ["path" "content"]}
   :execute (fn [{:keys [path content]} {:keys [cwd session-id]}]
              (let [file (if (fs/absolute? path)
                           (fs/file path)
                           (fs/file cwd path))]
                (fs/create-dirs (fs/parent file))
                (spit file content)
                ;; Auto-index if Clojure file
                (index-file-if-clojure! file session-id)
                {:type :success
                 :content (str "Wrote " (count content) " bytes to " (str file))
                 :metadata {:path (str file)
                            :bytes (count content)}}))})

(register!
  {:name "edit_file"
   :description "Edit a file by replacing an exact string match with new content. The old_string must match exactly (including whitespace)."
   :parameters {:type "object"
                :properties {:path {:type "string"
                                    :description "The path to the file"}
                             :old_string {:type "string"
                                          :description "The exact string to find and replace"}
                             :new_string {:type "string"
                                          :description "The replacement string"}}
                :required ["path" "old_string" "new_string"]}
   :execute (fn [{:keys [path old_string new_string]} {:keys [cwd session-id]}]
              (let [file (if (fs/absolute? path)
                           (fs/file path)
                           (fs/file cwd path))]
                (if (fs/exists? file)
                  (let [content (slurp file)
                        occurrences (count (re-seq (re-pattern (java.util.regex.Pattern/quote old_string)) content))]
                    (cond
                      (zero? occurrences)
                      {:type :error
                       :error "String not found in file"
                       :suggestion "Make sure the old_string matches exactly, including whitespace and newlines"}

                      (> occurrences 1)
                      {:type :error
                       :error (str "String found " occurrences " times, must be unique")
                       :suggestion "Include more surrounding context to make the match unique"}

                      :else
                      (let [new-content (str/replace-first content old_string new_string)]
                        (spit file new-content)
                        ;; Auto-index if Clojure file
                        (index-file-if-clojure! file session-id)
                        {:type :success
                         :content (str "Replaced string in " path)
                         :metadata {:path (str file)
                                    :old-length (count old_string)
                                    :new-length (count new_string)}})))
                  {:type :error
                   :error (str "File not found: " path)})))})

(register!
  {:name "glob"
   :description "Find files matching a glob pattern. Returns a list of matching file paths."
   :parameters {:type "object"
                :properties {:pattern {:type "string"
                                       :description "Glob pattern (e.g., '**/*.clj', 'src/*.md')"}}
                :required ["pattern"]}
   :execute (fn [{:keys [pattern]} {:keys [cwd]}]
              (let [matches (fs/glob cwd pattern)]
                {:type :success
                 :content (str/join "\n" (map str matches))
                 :metadata {:count (count matches)
                            :pattern pattern}}))})

(register!
  {:name "shell"
   :description "Execute a shell command. Returns stdout, stderr, and exit code."
   :parameters {:type "object"
                :properties {:command {:type "string"
                                       :description "The shell command to execute"}
                             :timeout_ms {:type "integer"
                                          :description "Timeout in milliseconds (default: 30000)"}}
                :required ["command"]}
   :execute (fn [{:keys [command timeout_ms]} {:keys [cwd]}]
              (let [timeout (or timeout_ms 30000)
                    pb (ProcessBuilder. ["bash" "-c" command])
                    _ (.directory pb (io/file cwd))
                    _ (.redirectErrorStream pb false)
                    proc (.start pb)
                    stdout (future (slurp (.getInputStream proc)))
                    stderr (future (slurp (.getErrorStream proc)))
                    finished (.waitFor proc timeout TimeUnit/MILLISECONDS)]
                (if finished
                  (let [exit (.exitValue proc)]
                    {:type (if (zero? exit) :success :error)
                     :content (str "Exit code: " exit "\n\nSTDOUT:\n" @stdout
                                   (when (seq @stderr)
                                     (str "\n\nSTDERR:\n" @stderr)))
                     :metadata {:exit-code exit
                                :stdout @stdout
                                :stderr @stderr}})
                  (do
                    (.destroyForcibly proc)
                    {:type :error
                     :error (str "Command timed out after " timeout "ms")
                     :suggestion "Try increasing timeout_ms or simplifying the command"}))))})

(register!
  {:name "grep"
   :description "Search for a pattern in files. Uses ripgrep-style regex."
   :parameters {:type "object"
                :properties {:pattern {:type "string"
                                       :description "Regex pattern to search for"}
                             :glob {:type "string"
                                    :description "Optional glob pattern to filter files (e.g., '*.clj')"}
                             :-i {:type "boolean"
                                  :description "Case-insensitive search"}}
                :required ["pattern"]}
   :execute (fn [{:keys [pattern glob -i]} {:keys [cwd]}]
              (let [cmd (cond-> ["grep" "-rn" "--color=never"]
                          -i (conj "-i")
                          true (conj pattern ".")
                          glob (into ["--include" glob]))
                    pb (ProcessBuilder. cmd)
                    _ (.directory pb (io/file cwd))
                    proc (.start pb)
                    stdout (slurp (.getInputStream proc))
                    _ (.waitFor proc)]
                {:type :success
                 :content (if (str/blank? stdout)
                            "No matches found"
                            stdout)
                 :metadata {:pattern pattern
                            :matches (count (str/split-lines stdout))}}))})

(defn- native-eval
  "Evaluate Clojure code natively (not in SCI sandbox).
   Uses eval-ns atom from context to track namespace across evaluations."
  [code eval-ns-atom]
  (let [stdout (java.io.StringWriter.)
        stderr (java.io.StringWriter.)]
    (try
      (let [form (binding [*ns* @eval-ns-atom]
                   (read-string code))
            result (binding [*ns* @eval-ns-atom
                             *out* stdout
                             *err* stderr]
                     (let [r (eval form)]
                       ;; Capture ns after eval (ns/in-ns modify *ns*)
                       (reset! eval-ns-atom *ns*)
                       r))]
        {:type :success
         :content (str "=> " (pr-str result)
                       (when (pos? (.length (.getBuffer stdout)))
                         (str "\n\nOutput:\n" (str stdout))))
         :metadata {:value result
                    :stdout (str stdout)
                    :stderr (str stderr)}})
      (catch Throwable e
        {:type :error
         :error (.getMessage e)
         :content (str "Evaluation error: " (.getSimpleName (class e)) ": " (.getMessage e)
                       (when (pos? (.length (.getBuffer stdout)))
                         (str "\n\nPartial output:\n" (str stdout))))
         :metadata {:exception (class e)
                    :message (.getMessage e)}}))))

(register!
  {:name "clojure_eval"
   :description "Evaluate Clojure code in the session's REPL environment.

   Each session has its own REPL environment where:
   - (def x 1) persists within the session
   - You can define functions with defn
   - You can require namespaces
   - Changes don't affect other sessions

   Execution budget: a 60-second hard timeout applies to each call. If
   you exceed it the tool returns a TimeoutException with a hint about
   likely causes (hung @spin, infinite loop, oversized inference). Keep
   loops bounded; for inference use modest particle counts (e.g. 50–200)
   and prefer loop/recur/dotimes over map/for/repeatedly for iterated
   sample/observe.

   Use this to:
   - Test Clojure code snippets
   - Perform calculations
   - Define helper functions for your task
   - Explore data structures

   Example: (clojure_eval {:code \"(reduce + (range 10))\"})
   Returns: => 45"
   :parameters {:type "object"
                :properties {:code {:type "string"
                                    :description "Clojure code to evaluate"}}
                :required ["code"]}
   :execute (fn [{:keys [code]} {:keys [sci-ctx isolation eval-ns]}]
              ;; Native mode: use real Clojure eval
              (if (= :native isolation)
                (native-eval code eval-ns)
                ;; SCI mode: use sandboxed eval.
                ;;
                ;; Hard timeout: a hung eval (e.g. `@(spin …)` on a spin
                ;; that never resolves) parks a core.async dispatch
                ;; thread forever. Core.async's dispatch pool is fixed
                ;; (≈8 threads); a handful of hung evals deadlocks it
                ;; and the entire agent system hangs. The watchdog in
                ;; sandbox/eval-code interrupts the eval thread after
                ;; the timeout and returns an error result, freeing
                ;; the dispatch thread.
                (if sci-ctx
                  (let [result (sandbox/eval-code sci-ctx code :timeout-ms 60000)]
                    (if (:success result)
                      {:type :success
                       :content (str "=> " (pr-str (:value result))
                                     (when (not-empty (:stdout result))
                                       (str "\n\nOutput:\n" (:stdout result))))
                       :metadata {:value (:value result)
                                  :stdout (:stdout result)
                                  :stderr (:stderr result)}}
                      ;; Enhanced error output with SCI stacktrace
                      (let [error (:error result)
                            stacktrace (:stacktrace error)
                            stacktrace-str (when (seq stacktrace)
                                             (str "\n\nStacktrace:\n"
                                                  (clojure.string/join "\n"
                                                    (map (fn [{:keys [ns name line]}]
                                                           (str "  " (or ns "?") "/" (or name "?")
                                                                (when line (str ":" line))))
                                                         (take 10 stacktrace)))))]
                        {:type :error
                         :error (:message error)
                         :content (str "Evaluation error: " (:message error)
                                       (when (:type error)
                                         (str "\nType: " (:type error)))
                                       stacktrace-str
                                       (when (not-empty (:stdout result))
                                         (str "\n\nPartial output:\n" (:stdout result))))
                         :metadata error})))
                  {:type :error
                   :error "No evaluation context. Pass :sci-ctx or use :isolation :native."})))})

(register!
  {:name "clojure_edit"
   :description "Edit a Clojure form structurally by finding it by type and name.

   PREFER this over edit_file for Clojure code as it:
   - Finds forms semantically (no exact string matching needed)
   - Auto-validates Clojure syntax
   - Safer for parentheses-heavy code

   Operations:
   - replace: Replace the entire form with new code
   - insert_before: Insert new code before the form
   - insert_after: Insert new code after the form

   Example: Replace a function
   {\"file_path\": \"src/core.clj\",
    \"form_type\": \"defn\",
    \"form_name\": \"greet\",
    \"operation\": \"replace\",
    \"new_source\": \"(defn greet [name] (str \\\"Hi, \\\" name))\"}

   Example: Insert a helper before a function
   {\"file_path\": \"src/core.clj\",
    \"form_type\": \"defn\",
    \"form_name\": \"main-fn\",
    \"operation\": \"insert_before\",
    \"new_source\": \"(defn helper [x] (* x 2))\"}"
   :parameters {:type "object"
                :properties {:file_path {:type "string"
                                         :description "Path to the Clojure file"}
                             :form_type {:type "string"
                                         :description "Type of form: defn, def, ns, defmethod, etc."}
                             :form_name {:type "string"
                                         :description "Name of the form to find"}
                             :operation {:type "string"
                                         :enum ["replace" "insert_before" "insert_after"]
                                         :description "What to do with the form"}
                             :new_source {:type "string"
                                          :description "New Clojure source code"}}
                :required ["file_path" "form_type" "form_name" "operation" "new_source"]}
   :execute (fn [{:keys [file_path form_type form_name operation new_source]} {:keys [cwd session-id]}]
              (let [full-path (if (fs/absolute? file_path)
                                file_path
                                (str cwd "/" file_path))
                    op-keyword (keyword operation)
                    result (structural/edit-clojure-form full-path form_type form_name op-keyword new_source)]
                (if (:success result)
                  (do
                    (spit full-path (:content result))
                    ;; Auto-index (always Clojure file)
                    (index-file-if-clojure! full-path session-id)
                    {:type :success
                     :content (str "Successfully edited " form_type " " form_name
                                   " in " full-path)
                     :metadata {:file full-path
                                :form-type form_type
                                :form-name form_name}})
                  {:type :error
                   :error (:error result)})))})

(register!
  {:name "datalog_query"
   :description "Query Clojure code metadata using Datalog-like patterns.

   This tool indexes Clojure files and lets you query them semantically.
   Use this to find functions, understand code structure, etc.

   Supported query patterns:
   - Find all functions: [:find ?name :where [?e :type :defn]]
   - Find by name: [:find ?file ?line :where [?e :name 'greet]]
   - Find in file: [:find ?name :where [?e :type :defn] [?e :file \"sample.clj\"]]

   Variables start with ? and match any value.
   Literals (keywords, symbols, strings) must match exactly.

   Returns vector of results matching the :find clause.

   Example: Find all defn names
   {\"directory\": \"src\",
    \"query\": \"[:find ?name :where [?e :type :defn]]\"}
   Returns: [[greet] [farewell] [calculate]]

   Example: Find where 'greet' is defined
   {\"directory\": \".\",
    \"query\": \"[:find ?file ?line :where [?e :name 'greet]]\"}
   Returns: [[\"src/core.clj\" 10]]"
   :parameters {:type "object"
                :properties {:directory {:type "string"
                                         :description "Directory to index (recursively finds all .clj files)"}
                             :query {:type "string"
                                     :description "Datalog query pattern as EDN string"}}
                :required ["directory" "query"]}
   :execute (fn [{:keys [directory query]} {:keys [cwd]}]
              (try
                (let [dir-path (if (fs/absolute? directory)
                                 directory
                                 (str cwd "/" directory))
                      index (code-meta/index-directory dir-path)
                      query-edn (read-string query)
                      results (code-meta/query-defs index query-edn)]
                  {:type :success
                   :content (str "Query results:\n" (pr-str results))
                   :metadata {:num-results (count results)
                              :indexed-files (count index)}})
                (catch Exception e
                  {:type :error
                   :error (str "Query error: " (.getMessage e))})))})\n
(register!
  {:name "code_query"
   :description "Query indexed Clojure code metadata from the session database.

   This tool uses the datahike-indexed code metadata to answer questions about
   your codebase structure. Code is automatically indexed when you write/edit files.

   Available queries:
   - find_function: Look up a function by name
     Example: {\"operation\": \"find_function\", \"name\": \"greet\"}

   - list_functions: List all functions (optionally filtered by namespace)
     Example: {\"operation\": \"list_functions\"}
     Example: {\"operation\": \"list_functions\", \"namespace\": \"sample.core\"}

   - find_callers: Find what calls a function
     Example: {\"operation\": \"find_callers\", \"name\": \"greet\"}

   - find_callees: Find what a function calls
     Example: {\"operation\": \"find_callees\", \"name\": \"calculate\"}

   - search_by_doc: Search functions by docstring
     Example: {\"operation\": \"search_by_doc\", \"term\": \"greeting\"}

   Returns structured data about functions including:
   - name, qualified-name
   - file path and line number
   - docstring
   - argument lists
   - call relationships"
   :parameters {:type "object"
                :properties {:operation {:type "string"
                                        :enum ["find_function" "list_functions" "find_callers"
                                               "find_callees" "search_by_doc"]
                                        :description "The query operation to perform"}
                             :name {:type "string"
                                   :description "Function name (for find_function, find_callers, find_callees)"}
                             :namespace {:type "string"
                                        :description "Namespace symbol (for list_functions filter)"}
                             :term {:type "string"
                                   :description "Search term (for search_by_doc)"}}
                :required ["operation"]}
   :execute (fn [{:keys [operation name namespace term]} {:keys [db-conn]}]
              (if db-conn
                (try
                  (let [result (case operation
                                 "find_function"
                                 (storage/find-function db-conn (symbol name))

                                 "list_functions"
                                 (if namespace
                                   (storage/list-functions db-conn (symbol namespace))
                                   (storage/list-functions db-conn))

                                 "find_callers"
                                 (storage/find-callers db-conn (symbol name))

                                 "find_callees"
                                 (storage/find-callees db-conn (symbol name))

                                 "search_by_doc"
                                 (storage/search-functions-by-doc db-conn term)

                                 {:error "Unknown operation"})]

                    (if (or (nil? result) (and (coll? result) (empty? result)))
                      {:type :success
                       :content (str "No results found for " operation
                                   (when name (str " with name '" name "'"))
                                   (when term (str " matching '" term "'"))
                                   "\n\nNote: Only Clojure files that have been written/edited are indexed.")}
                      {:type :success
                       :content (str "Query results:\n\n" (with-out-str (clojure.pprint/pprint result)))
                       :metadata {:operation operation
                                 :result-count (if (map? result) 1 (count result))}}))

                  (catch Exception e
                    {:type :error
                     :error (str "Query error: " (.getMessage e))}))

                {:type :error
                 :error "No database connection available. Pass :db-conn in tool context."}))})\n
(register!
  {:name "repl_history"
   :description "Query your REPL evaluation history from this session.

   Use this to recall what you've evaluated before, what libraries you required,
   what functions you defined, etc. This helps you understand the current REPL state.

   The query supports:
   - limit: max results to return (default 10)
   - success: filter by success (true/false, optional)
   - pattern: regex pattern to match in input code (optional)

   Returns list of past evaluations with input, output, and timestamps.

   Example: Find all successful evaluations
   {\\\"limit\\\": 20, \\\"success\\\": true}

   Example: Find evals that used 'require'
   {\\\"pattern\\\": \\\"require\\\"}"
   :parameters {:type "object"
                :properties {:limit {:type "integer"
                                     :description "Maximum results to return (default 10)"}
                             :success {:type "boolean"
                                       :description "Filter by success status"}
                             :pattern {:type "string"
                                       :description "Regex pattern to match in input code"}}
                :required []}
   :execute (fn [{:keys [limit success pattern]} {:keys [db-conn]}]
              (if db-conn
                (try
                  (let [query-opts (cond-> {:limit (or limit 10)}
                                     (some? success) (assoc :success success)
                                     pattern (assoc :pattern (re-pattern pattern)))
                        results (storage/query-evals db-conn query-opts)
                        formatted (map (fn [eval]
                                        (str "Input: " (:eval/input eval) "\n"
                                             (if (:eval/success eval)
                                               (str "=> " (:eval/output eval))
                                               (str "Error: " (:eval/exception eval)))
                                             (when (not-empty (:eval/stdout eval))
                                               (str "\nOutput: " (:eval/stdout eval)))
                                             "\n---"))
                                      results)]
                    {:type :success
                     :content (if (empty? results)
                                "No REPL history found matching query."
                                (str/join "\n" formatted))
                     :metadata {:count (count results)
                                :query query-opts}})
                  (catch Exception e
                    {:type :error
                     :error (str "Query error: " (.getMessage e))}))
                {:type :error
                 :error "No database connection available. Pass :db-conn in tool context."}))})

;; ---------------------------------------------------------------------------
;; Task Management Tools (Datahike-backed)
;; ---------------------------------------------------------------------------

(register!
  {:name "task_create"
   :description "Create a new task in the task tracker.

   Tasks are stored in datahike and can be queried, updated, and completed.
   When working in an isolated context, task changes are part of the fork
   and will only persist if merged.

   Required:
   - title: Short task title

   Optional:
   - description: Detailed description
   - priority: :low :medium :high :critical (default :medium)
   - status: :pending :in-progress :completed :blocked (default :pending)
   - assigned_to: Who should work on this
   - tags: List of string tags for categorization

   Returns the created task with its ID."
   :parameters {:type "object"
                :properties {:title {:type "string"
                                     :description "Task title"}
                             :description {:type "string"
                                           :description "Detailed description"}
                             :priority {:type "string"
                                        :description "Priority: low, medium, high, critical"}
                             :status {:type "string"
                                      :description "Status: pending, in-progress, completed, blocked"}
                             :assigned_to {:type "string"
                                           :description "Who to assign this task to"}
                             :tags {:type "array"
                                    :items {:type "string"}
                                    :description "Tags for categorization"}}
                :required ["title"]}
   :execute (fn [{:keys [title description priority status assigned_to tags]}
                 {:keys [db-conn]}]
              (if db-conn
                (try
                  (let [task-id (random-uuid)
                        task (cond-> {:task/id task-id
                                      :task/title title
                                      :task/status (keyword (or status "pending"))
                                      :task/priority (keyword (or priority "medium"))
                                      :task/created-at (java.util.Date.)
                                      :task/created-by "agent"}
                               description (assoc :task/description description)
                               assigned_to (assoc :task/assigned-to assigned_to)
                               (seq tags) (assoc :task/tags (set tags)))]
                    (d/transact db-conn [task])
                    {:type :success
                     :content (str "Created task: " title "\nID: " task-id)
                     :metadata {:task-id (str task-id)
                                :task task}})
                  (catch Exception e
                    {:type :error
                     :error (str "Failed to create task: " (.getMessage e))}))
                {:type :error
                 :error "No database connection available."}))})

(register!
  {:name "task_list"
   :description "List tasks from the task tracker.

   Query tasks with optional filters. Returns all matching tasks.

   Filters (all optional):
   - status: Filter by status (pending, in-progress, completed, blocked)
   - priority: Filter by priority (low, medium, high, critical)
   - assigned_to: Filter by assignee
   - tag: Filter by tag

   Returns list of tasks with their details."
   :parameters {:type "object"
                :properties {:status {:type "string"
                                      :description "Filter by status"}
                             :priority {:type "string"
                                        :description "Filter by priority"}
                             :assigned_to {:type "string"
                                           :description "Filter by assignee"}
                             :tag {:type "string"
                                   :description "Filter by tag"}}
                :required []}
   :execute (fn [{:keys [status priority assigned_to tag]} {:keys [db-conn]}]
              (if db-conn
                (try
                  (let [;; Build query dynamically based on filters
                        base-query '[:find [(pull ?t [*]) ...]
                                     :where [?t :task/id _]]
                        ;; Add filters to where clause
                        where-clauses (cond-> []
                                        status (conj ['?t :task/status (keyword status)])
                                        priority (conj ['?t :task/priority (keyword priority)])
                                        assigned_to (conj ['?t :task/assigned-to assigned_to])
                                        tag (conj ['?t :task/tags tag]))
                        query (if (empty? where-clauses)
                                base-query
                                (update base-query 2 (fn [w] (vec (concat [w] where-clauses)))))
                        results (d/q query @db-conn)
                        formatted (map (fn [task]
                                        (str "- [" (name (:task/status task)) "] "
                                             (:task/title task)
                                             (when (:task/assigned-to task)
                                               (str " (@" (:task/assigned-to task) ")"))
                                             (when (:task/priority task)
                                               (str " [" (name (:task/priority task)) "]"))
                                             "\n  ID: " (:task/id task)
                                             (when (:task/description task)
                                               (str "\n  " (:task/description task)))))
                                      results)]
                    {:type :success
                     :content (if (empty? results)
                                "No tasks found."
                                (str "Tasks (" (count results) "):\n\n"
                                     (str/join "\n\n" formatted)))
                     :metadata {:count (count results)
                                :tasks results}})
                  (catch Exception e
                    {:type :error
                     :error (str "Query failed: " (.getMessage e))}))
                {:type :error
                 :error "No database connection available."}))})

(register!
  {:name "task_update"
   :description "Update a task's status or other fields.

   Use this to mark tasks as in-progress, completed, or to update other fields.

   Required:
   - id: Task ID (UUID string)

   Optional (at least one required):
   - status: New status (pending, in-progress, completed, blocked)
   - priority: New priority (low, medium, high, critical)
   - assigned_to: New assignee
   - description: Updated description

   Returns the updated task."
   :parameters {:type "object"
                :properties {:id {:type "string"
                                  :description "Task ID to update"}
                             :status {:type "string"
                                      :description "New status"}
                             :priority {:type "string"
                                        :description "New priority"}
                             :assigned_to {:type "string"
                                           :description "New assignee"}
                             :description {:type "string"
                                           :description "Updated description"}}
                :required ["id"]}
   :execute (fn [{:keys [id status priority assigned_to description]} {:keys [db-conn]}]
              (if db-conn
                (try
                  (let [task-id (parse-uuid id)
                        ;; Find existing task
                        existing (d/q '[:find (pull ?t [*]) .
                                        :in $ ?id
                                        :where [?t :task/id ?id]]
                                      @db-conn task-id)]
                    (if existing
                      (let [updates (cond-> {}
                                      status (assoc :task/status (keyword status))
                                      priority (assoc :task/priority (keyword priority))
                                      assigned_to (assoc :task/assigned-to assigned_to)
                                      description (assoc :task/description description)
                                      (= status "completed") (assoc :task/completed-at (java.util.Date.)))
                            tx-data [(merge {:task/id task-id} updates)]]
                        (d/transact db-conn tx-data)
                        {:type :success
                         :content (str "Updated task: " (:task/title existing)
                                      (when status (str "\n  Status: " status))
                                      (when priority (str "\n  Priority: " priority)))
                         :metadata {:task-id id
                                    :updates updates}})
                      {:type :error
                       :error (str "Task not found: " id)}))
                  (catch Exception e
                    {:type :error
                     :error (str "Update failed: " (.getMessage e))}))
                {:type :error
                 :error "No database connection available."}))})

;; ---------------------------------------------------------------------------
;; Knowledge Graph Tools
;; ---------------------------------------------------------------------------

(register!
  {:name "knowledge_search"
   :description "Search the knowledge graph for entities mentioned in conversations.

   The knowledge graph is built from [[Entity]] wiki-links extracted during
   context compaction. Use this to find information about concepts, decisions,
   and project knowledge accumulated over conversations.

   Returns entities with their contexts, mention counts, and related entities.

   Example: Find all knowledge about authentication
   {\"query\": \"auth\"}

   Example: List most mentioned entities
   {\"operation\": \"top\", \"limit\": 10}"
   :parameters {:type "object"
                :properties {:query {:type "string"
                                     :description "Search term to match in entity titles"}
                             :operation {:type "string"
                                         :enum ["search" "top" "get"]
                                         :description "Operation: search (by query), top (most mentioned), get (exact title)"}
                             :title {:type "string"
                                     :description "Exact entity title (for get operation)"}
                             :limit {:type "integer"
                                     :description "Max results to return (default 10)"}}
                :required []}
   :execute (fn [{:keys [query operation title limit]} {:keys [db-conn]}]
              (if db-conn
                (try
                  (let [limit (or limit 10)
                        results (case (or operation "search")
                                  "search"
                                  (when query
                                    (let [pattern (re-pattern (str "(?i)" query))
                                          all-entities (d/q '[:find [(pull ?e [*]) ...]
                                                              :where [?e :entity/id _]]
                                                            @db-conn)]
                                      (->> all-entities
                                           (filter #(re-find pattern (:entity/title %)))
                                           (sort-by #(- (or (:entity/mention-count %) 0)))
                                           (take limit))))

                                  "top"
                                  (->> (d/q '[:find [(pull ?e [*]) ...]
                                              :where [?e :entity/id _]]
                                            @db-conn)
                                       (sort-by #(- (or (:entity/mention-count %) 0)))
                                       (take limit))

                                  "get"
                                  (when title
                                    [(d/q '[:find (pull ?e [*]) .
                                            :in $ ?t
                                            :where [?e :entity/title ?t]]
                                          @db-conn title)]))]

                    (if (seq results)
                      {:type :success
                       :content (str "Knowledge Graph Results (" (count results) "):\n\n"
                                     (str/join "\n\n"
                                       (map (fn [e]
                                              (str "[[" (:entity/title e) "]]\n"
                                                   "  Mentions: " (:entity/mention-count e) "\n"
                                                   (when (:entity/summary e)
                                                     (str "  Summary: " (:entity/summary e) "\n"))
                                                   (when (seq (:entity/contexts e))
                                                     (str "  Contexts: " (str/join ", " (take 3 (:entity/contexts e)))
                                                          (when (> (count (:entity/contexts e)) 3)
                                                            " ...")))))
                                            results)))
                       :metadata {:count (count results)
                                  :entities results}}
                      {:type :success
                       :content "No entities found matching query."
                       :metadata {:count 0}}))
                  (catch Exception e
                    {:type :error
                     :error (str "Knowledge search failed: " (.getMessage e))}))
                {:type :error
                 :error "No database connection available."}))})

(register!
  {:name "knowledge_add"
   :description "Add or update an entity in the knowledge graph.

   Use this to record knowledge, intake signals, decisions, or important findings.
   Entities accumulate contexts over time — calling again with the same title adds context.

   Examples:
     ;; Intake signal
     knowledge_add {:title \"HN: Datalog databases discussion\"
                    :source \"hn\"
                    :url \"https://news.ycombinator.com/item?id=12345\"
                    :summary \"23-comment thread comparing Datalog databases; positive sentiment\"
                    :relevance 4}

     ;; Architectural decision
     knowledge_add {:title \"Agent FRP Design\"
                    :summary \"Agents use spindel mailboxes for message passing, not direct calls\"
                    :context \"Decided 2026-02-22 when refactoring agent loop\"}"
   :parameters {:type "object"
                :properties {:title     {:type "string"
                                         :description "Entity title — concise, searchable"}
                             :summary   {:type "string"
                                         :description "1-3 sentence summary of what this is"}
                             :context   {:type "string"
                                         :description "Additional context or details"}
                             :source    {:type "string"
                                         :description "Source: hn, reddit, lobsters, mail, slack, internal"}
                             :url       {:type "string"
                                         :description "URL if available"}
                             :relevance {:type "integer"
                                         :description "Relevance score 1-5 (5=direct product mention)"}
                             :entity_type {:type "string"
                                           :description "Entity type: competitor, client, partner, project, technology, person, company"}
                             :tags     {:type "array"
                                        :items {:type "string"}
                                        :description "Tags for categorization (e.g. [\"database\" \"clojure\"])"}
                             :sync_sources {:type "array"
                                            :items {:type "object"
                                                    :properties {:type {:type "string"
                                                                         :description "Source type: linkedin, twitter, github, bluesky, web"}
                                                                 :url  {:type "string"
                                                                        :description "URL to fetch on sync"}}
                                                    :required ["type" "url"]}
                                            :description "URLs to fetch periodically for entity updates (stored in DB, used by entity_sync)"}
                             :role    {:type "string"
                                       :description "Job title/role for person entities (e.g. 'VP of Engineering at Acme Corp')"}
                             :employer {:type "string"
                                        :description "Entity title of employing company (for person entities)"}}
                :required ["title"]}
   :execute (fn [{:keys [title summary context source url relevance entity_type tags sync_sources role employer]} {:keys [db-conn chat-ctx]}]
              (cond
                (str/blank? title)
                {:type :error
                 :error "Required parameter 'title' is missing or blank. Call knowledge_add with at least {:title \"your title here\"}"}

                (not db-conn)
                {:type :error
                 :error "No database connection available."}

                :else
                (try
                  (let [;; Build context string from intake fields
                        intake-ctx (when (or source url relevance)
                                     (str/join " " (remove nil? [(when source (str "source:" source))
                                                                  (when url (str "url:" url))
                                                                  (when relevance (str "relevance:" relevance))])))
                        full-context (str/join "\n" (remove str/blank? [intake-ctx context]))
                        session-id (when chat-ctx (:chat-id chat-ctx))
                        ;; Check if entity exists
                        existing (d/q '[:find [?e ?mc]
                                        :in $ ?t
                                        :where
                                        [?e :entity/title ?t]
                                        [?e :entity/mention-count ?mc]]
                                      @db-conn title)]
                    (if (some? existing)
                      ;; Update existing
                      (let [[eid mc] existing
                            etype (when entity_type (keyword entity_type))
                            employer-eid (when (not (str/blank? employer))
                                           (d/q '[:find ?e .
                                                  :in $ ?t
                                                  :where [?e :entity/title ?t]]
                                                @db-conn employer))
                            tx-data (cond-> {:db/id eid
                                             :entity/mention-count (inc mc)
                                             :entity/updated-at (java.util.Date.)}
                                      (not (str/blank? full-context))
                                      (update :entity/contexts (fnil conj []) full-context)
                                      summary (assoc :entity/summary summary)
                                      etype (assoc :entity/type etype)
                                      url (assoc :entity/url url)
                                      (seq tags) (assoc :entity/tags (set tags))
                                      (seq sync_sources) (assoc :entity/sync-sources (pr-str (vec sync_sources)))
                                      (not (str/blank? role)) (assoc :entity/role role)
                                      employer-eid (assoc :entity/employer employer-eid)
                                      session-id (update :entity/from-sessions
                                                         (fnil conj []) session-id))]
                        (d/transact db-conn [tx-data])
                        {:type :success
                         :content (str "Updated: [[" title "]] (mentions: " (inc mc) ")"
                                       (when etype (str " type:" (name etype))))})

                      ;; Create new
                      (let [etype (when entity_type (keyword entity_type))
                            employer-eid (when (not (str/blank? employer))
                                           (d/q '[:find ?e .
                                                  :in $ ?t
                                                  :where [?e :entity/title ?t]]
                                                @db-conn employer))
                            entity (cond-> {:entity/id (random-uuid)
                                            :entity/title title
                                            :entity/contexts (if (not (str/blank? full-context))
                                                               [full-context] [])
                                            :entity/mention-count 1
                                            :entity/created-at (java.util.Date.)
                                            :entity/updated-at (java.util.Date.)}
                                     summary (assoc :entity/summary summary)
                                     etype (assoc :entity/type etype)
                                     url (assoc :entity/url url)
                                     (seq tags) (assoc :entity/tags (set tags))
                                     (seq sync_sources) (assoc :entity/sync-sources (pr-str (vec sync_sources)))
                                     (not (str/blank? role)) (assoc :entity/role role)
                                     employer-eid (assoc :entity/employer employer-eid)
                                     session-id (assoc :entity/from-sessions [session-id]))]
                        (d/transact db-conn [entity])
                        ;; Index in fulltext search (lazy-load to avoid circular dep)
                        (try
                          (when-let [idx-fn (requiring-resolve 'dvergr.search/index-document!)]
                            (when ((requiring-resolve 'dvergr.search/initialized?))
                              (idx-fn {:id        (str "knowledge/" (random-uuid))
                                       :source    "knowledge"
                                       :title     title
                                       :content   (str/join "\n" (remove str/blank? [summary full-context]))
                                       :url       (or url "")
                                       :timestamp (System/currentTimeMillis)
                                       :metadata  (cond-> {:entity-type entity_type}
                                                    source    (assoc :source source)
                                                    relevance (assoc :relevance relevance)
                                                    (seq tags) (assoc :tags tags))})))
                          (catch Exception _ nil))
                        {:type :success
                         :content (str "Stored: [[" title "]]"
                                       (when etype (str " type:" (name etype)))
                                       (when relevance (str " relevance:" relevance)))})))
                  (catch Exception e
                    {:type :error
                     :error (str "Failed to add knowledge: " (.getMessage e))}))))})

;; ---------------------------------------------------------------------------
;; Entity Sync
;; ---------------------------------------------------------------------------

(register!
  {:name "entity_sync"
   :description "Fetch all stored sync sources for an entity, extract new context, and update the knowledge graph.

   Requires the entity to have sync_sources set (via knowledge_add).
   For each source, fetches the URL and uses LLM to extract a concise update.
   Returns a summary of what changed.

   Example:
     entity_sync {:title \"Alice Chen\"}
     entity_sync {:title \"AcmeDB\" :max_sources 3}"
   :parameters {:type "object"
                :properties {:title       {:type "string"
                                           :description "Entity title to sync"}
                             :max_sources {:type "integer"
                                           :description "Max number of sources to fetch (default 3)"}}
                :required ["title"]}
   :execute (fn [{:keys [title max_sources]} {:keys [db-conn]}]
              (cond
                (str/blank? title)
                {:type :error :error "title is required"}

                (not db-conn)
                {:type :error :error "No database connection available."}

                :else
                (try
                  (let [entity (d/q '[:find (pull ?e [:entity/sync-sources :entity/summary :entity/title :entity/id]) .
                                      :in $ ?t
                                      :where [?e :entity/title ?t]]
                                    @db-conn title)]
                    (if-not entity
                      {:type :error :error (str "Entity not found: [[" title "]]")}
                      (let [sources-str (:entity/sync-sources entity)
                            sources (when sources-str (clojure.edn/read-string sources-str))]
                        (if (empty? sources)
                          {:type :error :error (str "No sync sources for [[" title "]]. Add via knowledge_add :sync_sources.")}
                          (let [n (min (or max_sources 3) (count sources))
                                results (for [src (take n sources)]
                                          (try
                                            (let [web-fetch (requiring-resolve 'dvergr.intake.web-fetch/fetch-page)
                                                  result    (web-fetch (:url src) :max-chars 4000)
                                                  page-text (:text result)
                                                  llm-call  (requiring-resolve 'dvergr.llm-call/cheap-llm-call)
                                                  extract   (llm-call
                                                              (str "Extract key facts about '" title "' from this page. "
                                                                   "Focus on: role/position, projects, recent activity, relationships. "
                                                                   "Be concise (3-5 sentences):")
                                                              (or page-text "")
                                                              {:max-tokens 300})]
                                              {:url (:url src)
                                               :type (:type src)
                                               :extracted (:text extract)})
                                            (catch Exception e
                                              {:url (:url src) :type (:type src) :error (.getMessage e)})))
                                new-contexts (->> results
                                                  (filter :extracted)
                                                  (map #(str "sync:" (:type %) " " (:extracted %))))
                                summary-str (->> results
                                                 (map #(if (:error %)
                                                         (str "  - " (:type %) ": ERROR " (:error %))
                                                         (str "  - " (:type %) ": " (or (:extracted %) "no content"))))
                                                 (str/join "\n"))]
                            ;; Transact updates to entity
                            (when (seq new-contexts)
                              (d/transact db-conn [{:db/id    [:entity/title title]
                                                    :entity/last-synced (java.util.Date.)
                                                    :entity/contexts new-contexts}]))
                            {:type :success
                             :content (str "Synced [[" title "]] from " (count sources) " sources:\n" summary-str)})))))
                  (catch Exception e
                    {:type :error :error (str "entity_sync failed: " (.getMessage e))}))))})

;; ---------------------------------------------------------------------------
;; Fulltext Search
;; ---------------------------------------------------------------------------

(register!
  {:name "fulltext_search"
   :description "Search across all indexed data: browser captures, YouTube transcripts, RSS feeds, web pages, knowledge entities, and agent conversations. Returns matching documents with title, URL, source, and relevance score."
   :parameters {:type "object"
                :properties {:query  {:type "string"
                                      :description "Search query"}
                             :source {:type "string"
                                      :enum ["capture" "youtube" "rss" "web" "knowledge" "conversation"]
                                      :description "Filter by source type (optional)"}
                             :limit  {:type "integer"
                                      :description "Max results (default 20)"}}
                :required ["query"]}
   :execute (fn [{:keys [query source limit]} _ctx]
              (try
                (let [search-fn (requiring-resolve 'dvergr.search/search)
                      init?-fn  (requiring-resolve 'dvergr.search/initialized?)]
                  (if-not (init?-fn)
                    {:type :error :error "Search index not initialized"}
                    (let [results (search-fn query
                                   :source source
                                   :limit (or limit 20))]
                      (if (empty? results)
                        {:type :success :content (str "No results for: " query)}
                        {:type :success
                         :content (str (count results) " results for \"" query "\"\n\n"
                                       (str/join "\n\n"
                                         (map-indexed
                                           (fn [i r]
                                             (str (inc i) ". **" (or (:title r) (:id r)) "**"
                                                  (when (:source r) (str " [" (:source r) "]"))
                                                  (when (:url r) (str "\n   " (:url r)))
                                                  (when (:domain r) (str " (" (:domain r) ")"))
                                                  (when (:score r) (str " — score: " (format "%.2f" (double (:score r)))))))
                                           results)))}))))
                (catch Exception e
                  {:type :error :error (str "Search failed: " (.getMessage e))})))})

;; ---------------------------------------------------------------------------
;; Agent Self-Improvement
;; ---------------------------------------------------------------------------

(register!
  {:name "update_agent_profile"
   :description "Rewrite an agent's system-prompt profile file (resources/agents/<name>.md).
Use this to improve an agent's instructions based on observed performance.

Only permitted for agents in resources/agents/. Pass the complete updated
markdown content — the file will be overwritten in full.

Example:
  update_agent_profile {:agent-name \"huginn\"
                        :content \"# Huginn ...\\n\\nImproved instructions...\"}

Note: changes take effect on the next agent restart or reload."
   :parameters {:type "object"
                :properties {:agent-name {:type "string"
                                          :description "Agent profile name, e.g. \"huginn\" (no path, no .md extension)"}
                             :content    {:type "string"
                                          :description "Full markdown content to write to the profile file"}}
                :required ["agent-name" "content"]}
   :execute (fn [{:keys [agent-name content]} _ctx]
              (cond
                (str/blank? agent-name)
                {:type :error :error "agent-name is required"}

                (str/blank? content)
                {:type :error :error "content is required — pass the full profile markdown"}

                ;; Safety: only allow simple names, no path traversal
                (not (re-matches #"^[a-zA-Z0-9_-]+$" agent-name))
                {:type :error :error (str "Invalid agent-name '" agent-name "'. Use only letters, digits, _ and -")}

                :else
                (try
                  (let [resource-path (clojure.java.io/resource (str "agents/" agent-name ".md"))
                        ;; Write to the actual filesystem path (resources/ on classpath)
                        ;; Find via classpath first, fallback to project resources/
                        file (if resource-path
                               (java.io.File. (.toURI resource-path))
                               (java.io.File. (str "resources/agents/" agent-name ".md")))]
                    (spit file content)
                    {:type :success
                     :content (str "Updated agent profile: " (.getPath file)
                                   "\nChanges take effect on next daemon restart or profile reload.")})
                  (catch Exception e
                    {:type :error :error (str "Failed to write profile: " (.getMessage e))}))))})

;; ---------------------------------------------------------------------------
;; Code Linting Tools
;; ---------------------------------------------------------------------------

;; Lazy require clj-kondo
(defn- clj-kondo-ns []
  (require 'clj-kondo.core)
  (find-ns 'clj-kondo.core))

(register!
  {:name "clj_kondo"
   :description "Run clj-kondo static analysis on Clojure code.

   clj-kondo is a linter for Clojure that catches errors, warnings, and style issues.
   Use this to check code quality before committing or to find issues in existing code.

   Options:
   - lint: Paths to lint (files or directories, required)
   - config: Optional config map (merged with project .clj-kondo/config.edn)

   Returns findings (errors, warnings, info) and summary counts.

   Example: Lint a file
   {\"lint\": [\"src/myns/core.clj\"]}

   Example: Lint entire src directory
   {\"lint\": [\"src\"]}

   Example: Lint with custom config
   {\"lint\": [\"src\"], \"config\": {\":linters\": {\":unused-binding\": {\":level\": \":off\"}}}}"
   :parameters {:type "object"
                :properties {:lint {:type "array"
                                    :items {:type "string"}
                                    :description "Paths to lint (files or directories)"}
                             :config {:type "object"
                                      :description "Optional clj-kondo config map"}}
                :required ["lint"]}
   :execute (fn [{:keys [lint config]} {:keys [cwd]}]
              (try
                (let [kondo-ns (clj-kondo-ns)
                      run! (ns-resolve kondo-ns 'run!)
                      ;; Resolve paths relative to cwd
                      paths (mapv (fn [p]
                                    (if (fs/absolute? p)
                                      p
                                      (str cwd "/" p)))
                                  lint)
                      opts (cond-> {:lint paths}
                             config (assoc :config config))
                      result (run! opts)
                      findings (:findings result)
                      summary (:summary result)]
                  {:type (if (zero? (or (:error summary) 0)) :success :error)
                   :content (str "clj-kondo analysis:\n"
                                 "  Errors: " (:error summary 0) "\n"
                                 "  Warnings: " (:warning summary 0) "\n"
                                 "  Info: " (:info summary 0) "\n"
                                 "  Duration: " (:duration summary) "ms\n"
                                 (when (seq findings)
                                   (str "\nFindings:\n"
                                        (str/join "\n"
                                          (map (fn [{:keys [type level filename row col message]}]
                                                 (str "  " (name level) ": " filename ":" row ":" col " - " message))
                                               findings)))))
                   :metadata {:summary summary
                              :findings findings
                              :paths paths}})
                (catch Exception e
                  {:type :error
                   :error (str "clj-kondo failed: " (.getMessage e))})))})

;; ---------------------------------------------------------------------------
;; Test Execution Tool
;; ---------------------------------------------------------------------------

;; Lazy require kaocha
(defn- kaocha-ns []
  (require 'kaocha.api)
  (find-ns 'kaocha.api))

(defn- format-test-output
  "Format test results for display."
  [result]
  (let [pass (:pass result 0)
        fail (:fail result 0)
        error (:error result 0)
        total (+ pass fail error)]
    (str "Test Results:\n"
         "  Total:   " total "\n"
         "  Passed:  " pass "\n"
         "  Failed:  " fail "\n"
         "  Errors:  " error "\n"
         "\n"
         (if (zero? (+ fail error))
           "✓ All tests passed"
           (str "✗ " (+ fail error) " test(s) failed\n\n"
                "Run with detailed reporter to see failure details")))))

(register!
  {:name "run_tests"
   :description "Run Clojure tests using Kaocha in the current working directory.

   This tool executes tests programmatically without requiring shell access.
   Tests run in the agent's working directory (forked worktree context),
   so agents can verify their code changes in isolation.

   Options:
   - focus: Run specific test namespace (e.g., 'dvergr.knowledge.links-test')
   - pattern: Regex pattern to match test namespaces (e.g., 'knowledge')
   - fail_fast: Stop on first failure (default false)

   Returns structured test results including:
   - Pass/fail/error counts
   - Exit code (0 = all pass, 1 = failures)
   - Summary output

   Example: Run specific test namespace
   {\"focus\": \"dvergr.knowledge.links-test\"}

   Example: Run all tests matching pattern
   {\"pattern\": \"knowledge\"}

   Example: Run all tests
   {}

   Note: Tests execute in the tool's :cwd, which for agents is their
   forked git worktree. This allows safe isolated testing."
   :parameters {:type "object"
                :properties {:focus {:type "string"
                                     :description "Specific test namespace to run"}
                             :pattern {:type "string"
                                       :description "Regex pattern for test namespaces"}
                             :fail_fast {:type "boolean"
                                         :description "Stop on first failure (default false)"}}
                :required []}
   :execute (fn [{:keys [focus pattern fail_fast]} {:keys [cwd]}]
              (try
                ;; Lazy load kaocha
                (let [kaocha-ns (kaocha-ns)
                      kaocha-run! (ns-resolve kaocha-ns 'run)

                      ;; Build kaocha config
                      config (cond-> {:color? false
                                      :fail-fast (boolean fail_fast)
                                      :cwd (or cwd ".")}

                               ;; Focus on specific test namespace
                               focus
                               (assoc :kaocha/tests
                                      [{:kaocha.testable/id :unit
                                        :kaocha.testable/type :kaocha.type/clojure.test
                                        :kaocha/ns-patterns [(re-pattern (str focus "$"))]}])

                               ;; Match pattern in test namespaces
                               pattern
                               (assoc :kaocha.filter/regex (re-pattern pattern)))

                      ;; Run tests
                      result (kaocha-run! config)

                      ;; Extract summary
                      pass (:kaocha.result/count result 0)
                      fail (:kaocha.result/fail result 0)
                      error (:kaocha.result/error result 0)
                      exit-code (if (and (zero? fail) (zero? error)) 0 1)]

                  {:type (if (zero? exit-code) :success :error)
                   :content (format-test-output {:pass pass
                                                 :fail fail
                                                 :error error})
                   :metadata {:passed pass
                              :failed fail
                              :errors error
                              :exit-code exit-code
                              :cwd (or cwd ".")}})

                (catch Exception e
                  {:type :error
                   :error (str "Test execution failed: " (.getMessage e))
                   :details (str "Cause: " (when-let [cause (.getCause e)]
                                             (.getMessage cause)))})))})

;; ---------------------------------------------------------------------------
;; Budget Tool
;; ---------------------------------------------------------------------------

;; Lazy require accounting
(defn- accounting-ns []
  (require 'dvergr.chat.accounting)
  (find-ns 'dvergr.chat.accounting))

(register!
  {:name "budget"
   :description "Check your remaining budget and usage breakdown.

   Use this to understand your resource constraints and plan accordingly.
   Budget is tracked in microdollars (μ$) where 1 USD = 1,000,000 μ$.

   Operations:
   - check: Get current budget status (default)
   - breakdown: Get detailed cost breakdown by resource type
   - estimate: Estimate cost of a planned operation

   The response includes:
   - Monetary budget remaining
   - Token usage (input/output)
   - Which constraints are tight (approaching limits)
   - Recommendations for budget-conscious operation

   Examples:
   {\"operation\": \"check\"}
   {\"operation\": \"breakdown\"}
   {\"operation\": \"estimate\", \"model\": \"claude-sonnet-4-5-20250514\",
    \"input_tokens\": 5000, \"output_tokens\": 2000}"
   :parameters {:type "object"
                :properties {:operation {:type "string"
                                         :enum ["check" "breakdown" "estimate"]
                                         :description "Operation to perform (default: check)"}
                             :model {:type "string"
                                     :description "Model ID for cost estimation"}
                             :input_tokens {:type "integer"
                                            :description "Estimated input tokens (for estimate)"}
                             :output_tokens {:type "integer"
                                             :description "Estimated output tokens (for estimate)"}}
                :required []}
   :execute (fn [{:keys [operation model input_tokens output_tokens]}
                 {:keys [chat-ctx]}]
              (if chat-ctx
                (try
                  (let [acc-ns (accounting-ns)
                        budget-status (ns-resolve acc-ns 'budget-status)
                        format-budget (ns-resolve acc-ns 'format-budget)
                        format-detailed-budget (ns-resolve acc-ns 'format-detailed-budget)
                        estimate-chat-cost (ns-resolve acc-ns 'estimate-chat-cost)
                        MICRODOLLARS-PER-DOLLAR (ns-resolve acc-ns 'MICRODOLLARS-PER-DOLLAR)

                        ;; Lazy require runtime for context binding
                        _ (require 'org.replikativ.spindel.engine.core)
                        rtc-ns (find-ns 'org.replikativ.spindel.engine.core)
                        exec-ctx-var (ns-resolve rtc-ns '*execution-context*)

                        ;; Get budget from chat context - need spindel context for signal read
                        budget-signal (:budget-signal chat-ctx)
                        spindel-ctx (:spindel-ctx chat-ctx)
                        {:keys [total used by-type]} (if (and budget-signal spindel-ctx)
                                                       (with-bindings {exec-ctx-var spindel-ctx}
                                                         @budget-signal)
                                                       {:total 0 :used 0 :by-type {}})
                        status (budget-status total used)]

                    (case (or operation "check")
                      "check"
                      {:type :success
                       :content (format-budget status)
                       :metadata {:status status}}

                      "breakdown"
                      {:type :success
                       :content (format-detailed-budget status by-type)
                       :metadata {:status status :by-type by-type}}

                      "estimate"
                      (if (and model input_tokens)
                        (let [est (estimate-chat-cost model
                                                      input_tokens
                                                      (or output_tokens 500))]
                          {:type :success
                           :content (str "Estimated cost for " model ":\n"
                                        "  Input tokens:  " input_tokens "\n"
                                        "  Output tokens: " (or output_tokens 500) "\n"
                                        "  Total cost:    $"
                                        (format "%.6f"
                                          (/ (:estimated-microdollars est)
                                             (double @MICRODOLLARS-PER-DOLLAR)))
                                        "\n\n"
                                        (if (> (:remaining status) (:estimated-microdollars est))
                                          "✓ Within budget"
                                          "⚠ Exceeds remaining budget!"))
                           :metadata {:estimate est :status status}})
                        {:type :error
                         :error "Estimate requires 'model' and 'input_tokens' parameters"})

                      {:type :error
                       :error (str "Unknown operation: " operation)}))
                  (catch Exception e
                    {:type :error
                     :error (str "Budget check failed: " (.getMessage e))}))
                ;; No chat context - return mock response for testing
                {:type :success
                 :content (str "Budget Status:\n"
                              "  Total:     $1.0000\n"
                              "  Used:      $0.0000\n"
                              "  Remaining: $1.0000 (100%)\n\n"
                              "Note: No chat context available. "
                              "Using mock response for testing.")
                 :metadata {:mock? true}}))})

;; ---------------------------------------------------------------------------
;; Agent Spawning Tool
;; ---------------------------------------------------------------------------

;; Lazy require agent primitives to avoid circular deps
(defn- agent-primitives-ns []
  (require 'dvergr.agent.task)
  (find-ns 'dvergr.agent.task))

(defn- agent-config-ns []
  (require 'dvergr.agent.config)
  (find-ns 'dvergr.agent.config))

(defn- load-agent-profile
  "Load a markdown agent profile from resources/agents/<name>.md."
  [profile-name]
  (when-let [resource (io/resource (str "agents/" (name profile-name) ".md"))]
    (slurp resource)))

(register!
  {:name "spawn_agent"
   :description "Spawn a sub-agent to handle a delegated task.

   The sub-agent runs in a forked execution context (isolated git branch,
   separate datahike) and returns its result. The parent agent can review
   the output before deciding whether to merge or discard changes.

   Use this to delegate sub-tasks to specialized agents.

   Parameters:
   - task: The task description for the sub-agent (required)
   - profile: Agent profile name — loads system prompt from resources/agents/<profile>.md
              Available: 'worker' (default), 'developer' (dvergr self-programming), 'planner'
   - budget: Budget in dollars for the sub-agent (default: 0.50)
   - phases: Optional list of phase names to run as a workflow.
             Available phases: 'explore', 'plan', 'implement', 'verify', 'research'
             When provided, the agent runs through each phase sequentially with
             per-phase tool narrowing and automatic self-check on verify phases.

   Returns the sub-agent's final text response.

   Example: Delegate research
   {\"task\": \"Research Clojure transducers and write a summary\"}

   Example: Delegate with developer profile
   {\"task\": \"Add a new tool that counts lines of code\", \"profile\": \"developer\", \"budget\": 1.0}

   Example: Delegate with workflow phases
   {\"task\": \"Implement feature X\", \"phases\": [\"explore\", \"implement\", \"verify\"], \"budget\": 1.0}"
   :parameters {:type "object"
                :properties {:task {:type "string"
                                    :description "Task description for the sub-agent"}
                             :profile {:type "string"
                                       :description "Agent profile: worker (default), developer, planner"}
                             :budget {:type "number"
                                      :description "Budget in dollars (default 0.50)"}
                             :phases {:type "array"
                                      :items {:type "string"}
                                      :description "Workflow phases: explore, plan, implement, verify, research"}}
                :required ["task"]}
   :execute (fn [{:keys [task profile budget phases]} {:keys [execution-ctx chat-ctx]}]
              (try
                (let [prim-ns (agent-primitives-ns)
                      cfg-ns (agent-config-ns)
                      ask! (ns-resolve prim-ns 'ask!)
                      extract-result (ns-resolve prim-ns 'extract-result)
                      make-agent (ns-resolve cfg-ns 'make-agent)

                      profile-name (or profile "worker")
                      system-prompt (or (load-agent-profile profile-name)
                                       "You are a capable AI worker. Complete the given task thoroughly.")

                      agent-cfg (make-agent
                                  {:name (str profile-name "-sub")
                                   :provider :fireworks
                                   :model "accounts/fireworks/models/qwen3-coder-480b-a35b-instruct"
                                   :isolation :sci
                                   :system-prompt system-prompt})

                      ;; Use provided execution context, or current binding
                      ctx (or execution-ctx
                             (try
                               (require 'org.replikativ.spindel.engine.core)
                               @(ns-resolve (find-ns 'org.replikativ.spindel.engine.core)
                                            '*execution-context*)
                               (catch Exception _ nil)))]

                  (if ctx
                    (let [result (binding [rtc/*execution-context* ctx]
                                  @(ask! agent-cfg task
                                         (cond-> {:budget-dollars (or budget 0.50)
                                                  :parent-chat-ctx chat-ctx}
                                           (seq phases)
                                           (assoc :workflow (mapv keyword phases)))))
                          text (extract-result result)]
                      {:type :success
                       :content (str "Sub-agent (" profile-name ") result:\n\n" text)
                       :metadata {:profile profile-name
                                  :status (:status result)
                                  :turns (:turns result)
                                  :agent (:agent result)}})
                    {:type :error
                     :error "No execution context available. spawn_agent requires a spindel runtime."}))
                (catch Exception e
                  {:type :error
                   :error (str "spawn_agent failed: " (.getMessage e))})))})

;; ---------------------------------------------------------------------------
;; Proposal Tool
;; ---------------------------------------------------------------------------

;; Lazy resolve to avoid circular deps with `dvergr.daemon` and to keep
;; tools.clj agnostic of the proposals/personas namespaces at compile
;; time (these are stable but the lazy edge mirrors how spawn_agent
;; already resolves agent.task).
(defn- proposals-propose! []
  (requiring-resolve 'dvergr.proposals/propose!))

(defn- llm-agent []
  (requiring-resolve 'dvergr.discourse.llm/llm-agent))

(defn- current-daemon []
  (some-> (requiring-resolve 'dvergr.daemon/current-daemon) deref deref))

(register!
  {:name "propose_change"
   :description "Propose a change via a sub-agent that works in a forked context.

   Unlike spawn_agent (which auto-merges), propose_change creates a human-reviewed
   proposal. The sub-agent's work is held in isolation until a human accepts or
   rejects it via the /proposals web UI.

   Use this when you want to delegate code changes, new modules, schema migrations,
   or any structural work that should be reviewed before merging.

   Parameters:
   - task: Detailed task description for the sub-agent (required)
   - profile: Agent profile name (default: 'developer')
   - budget: Budget in dollars for the sub-agent (default: 0.50)
   - phases: Optional workflow phases (e.g. ['explore', 'implement', 'verify'])

   Returns the proposal ID, status, summary, and web URL for review.

   Example: Propose a new monitoring module
   {\"task\": \"Create src/dvergr/twins/zulip.clj that syncs Zulip messages into Datahike\",
    \"budget\": 1.0,
    \"phases\": [\"explore\", \"implement\", \"verify\"]}

   Example: Propose a schema change
   {\"task\": \"Add :event/source and :event/timestamp attrs to the knowledge schema\",
    \"profile\": \"developer\"}"
   :parameters {:type "object"
                :properties {:task {:type "string"
                                    :description "Detailed task description for the sub-agent"}
                             :profile {:type "string"
                                       :description "Agent profile: developer (default), worker, planner"}
                             :budget {:type "number"
                                      :description "Budget in dollars (default 0.50)"}
                             :phases {:type "array"
                                      :items {:type "string"}
                                      :description "Workflow phases: explore, plan, implement, verify, research"}}
                :required ["task"]}
   :execute (fn [{:keys [task profile budget _phases]} {:keys [execution-ctx db-conn]}]
              (try
                (let [propose!     (proposals-propose!)
                      profile-name (or profile "developer")
                      prompt-text  (or (load-agent-profile profile-name)
                                       "You are a capable developer. Complete the given task thoroughly.")
                      daemon       (current-daemon)
                      room         (:discourse-room daemon)
                      ctx          (or execution-ctx (:execution-ctx daemon))]
                  (cond
                    (nil? room)
                    {:type :error
                     :error "propose_change requires a running daemon (no discourse room found)."}

                    (nil? db-conn)
                    {:type :error
                     :error "propose_change requires a database connection (no db-conn in tool-ctx)."}

                    :else
                    (let [make-worker (llm-agent)
                          worker (binding [rtc/*execution-context* ctx]
                                   (make-worker
                                     {:id      (keyword (str profile-name "-proposal"))
                                      :spec    {:provider      :fireworks
                                                :model         "accounts/fireworks/models/minimax-m2p5"
                                                :system-prompt prompt-text}
                                      :budget  {:dollars (or budget 0.50)}
                                      :ctx     ctx}))
                          proposal (binding [rtc/*execution-context* ctx]
                                     @(propose! {:room   room
                                                 :worker worker
                                                 :goal   task
                                                 :conn   db-conn
                                                 :budget-dollars (or budget 0.50)}))
                          pid (:proposal/id proposal)]
                      {:type :success
                       :content (str "Proposal created: " pid "\n"
                                     "Status: " (name (:proposal/status proposal)) "\n"
                                     "Review: /proposals/" pid "\n\n"
                                     "Summary:\n" (:proposal/summary proposal))
                       :metadata {:proposal-id (str pid)
                                  :status (:proposal/status proposal)
                                  :url (str "/proposals/" pid)}})))
                (catch Exception e
                  {:type :error
                   :error (str "propose_change failed: " (.getMessage e))})))})

(comment
  ;; Test tools
  (def ctx (make-context {:cwd "/home/christian-weilbach/Development/dvergr"}))

  (execute "read_file" {:path "deps.edn"} ctx)
  (execute "glob" {:pattern "**/*.clj"} ctx)
  (execute "shell" {:command "ls -la"} ctx)
  (execute "grep" {:pattern "defn"} ctx)
  (execute "clj_kondo" {:lint ["src/dvergr/core.clj"]} ctx)
  (execute "budget" {:operation "check"} ctx))
