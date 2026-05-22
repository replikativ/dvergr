(ns dvergr.mcp.server
  "MCP (Model Context Protocol) server for exposing dvergr capabilities.

   Two transport modes:
   - TCP (default for development): listens on a port, REPL-friendly
   - stdio (for deployment): reads stdin, writes stdout (uberjar mode)

   REPL workflow:
     (require '[dvergr.mcp.server :as mcp])
     (mcp/start! {:port 17888})   ; start TCP server
     ;; ... develop, modify tool handlers, they're live ...
     (mcp/stop!)                  ; stop TCP server

   Standalone:
     java -jar dvergr-mcp.jar --stdio          # stdio mode
     java -jar dvergr-mcp.jar --port 17888     # TCP mode"
  (:require [clojure.string :as str]
            [dvergr.mcp.json-rpc :as json-rpc]
            [jsonista.core :as json])
  (:import (java.net ServerSocket Socket)
           (java.io BufferedReader InputStreamReader BufferedWriter OutputStreamWriter))
  (:gen-class))

;; ============================================================================
;; JSON mappers
;; ============================================================================

(def ^:private json-write-mapper
  (json/object-mapper {:encode-key-fn name}))

(def ^:private json-read-mapper
  (json/object-mapper {:decode-key-fn keyword}))

;; ============================================================================
;; Agent registry (server-level, shared across connections)
;; ============================================================================

;; Atom of {agent-id-kw -> Agent}. Server-level state shared across connections.
(defonce agent-registry (atom {}))

;; Optional datahike connection — set by dvergr.daemon via set-db-conn!
;; Enables memory_query / memory_transact / memory_history tools.
(defonce server-db-conn (atom nil))

(defn set-db-conn!
  "Wire the daemon's datahike connection into the MCP server.
   Call this after daemon start so memory_* tools have access."
  [conn]
  (reset! server-db-conn conn))

;; ============================================================================
;; Tool definitions — dynamic atom for hot-swap and agent-created tools
;; ============================================================================

(defn- stub-tool-fn
  "Stub tool handler. Replace with real implementations by resetting tool-handlers."
  [tool-name]
  (fn [_context arguments]
    {:content [{:type "text"
                :text (str "dvergr-mcp: tool '" tool-name "' called with args: "
                           (pr-str arguments)
                           "\n\n[STUB] Tool implementation pending. "
                           "This confirms MCP communication is working.")}]
     :isError false}))

(def tool-handlers
  "Atom of tool-name → handler-fn. Swap to hot-reload handlers from REPL."
  (atom
   {;; Agent tools
    "agent_list"
    (fn [_context _arguments]
      (let [agents @agent-registry
            entries (mapv (fn [[id agent]]
                           {:id (name id)
                            :status (:status @(:state-a agent))
                            :config (select-keys (:config agent) [:provider :model :system-prompt])})
                         agents)]
        {:content [{:type "text"
                    :text (if (seq entries)
                            (pr-str entries)
                            "No active agents.")}]
         :isError false}))

    "agent_create"
    (fn [_context arguments]
      (let [agent-id (keyword (or (:agent_id arguments) (str (gensym "agent-"))))
            config {:id agent-id
                    :provider (keyword (or (:provider arguments) "fireworks"))
                    :model (or (:model arguments) "accounts/fireworks/models/qwen3-coder-480b-a35b-instruct")
                    :system-prompt (:system_prompt arguments)}]
        ;; Store config in registry — actual agent start requires spindel context
        (swap! agent-registry assoc agent-id {:config config
                                              :state-a (atom {:status :created})})
        {:content [{:type "text"
                    :text (str "Agent created: " (name agent-id)
                               "\nConfig: " (pr-str config))}]
         :isError false}))

    "agent_send_message"
    (fn [_context arguments]
      (let [agent-id (keyword (:agent_id arguments))
            message (:message arguments)
            timeout-ms (or (:timeout_ms arguments) 120000)
            agent (get @agent-registry agent-id)]
        (if-not agent
          {:content [{:type "text" :text (str "Agent not found: " (name agent-id))}]
           :isError true}
          (if-not (:inbox agent)
            {:content [{:type "text" :text (str "Agent " (name agent-id) " has no inbox (not started as reactive process)")}]
             :isError true}
            ;; Blocking send + await pattern (deferred+future bridge)
            (let [result (deref
                          (future
                            (try
                              ((:inbox agent) message)
                              ;; Await outbox (blocking at thread boundary)
                              (let [outbox-fn (:outbox agent)]
                                ;; Simple deref with timeout
                                (deref (future (outbox-fn)) timeout-ms ::timeout))
                              (catch Exception e
                                {:error (.getMessage e)})))
                          timeout-ms ::timeout)]
              (if (= result ::timeout)
                {:content [{:type "text" :text (str "Timeout after " timeout-ms "ms waiting for agent " (name agent-id))}]
                 :isError true}
                {:content [{:type "text" :text (pr-str result)}]
                 :isError (boolean (:error result))}))))))

    "agent_stop"
    (fn [_context arguments]
      (let [agent-id (keyword (:agent_id arguments))
            agent (get @agent-registry agent-id)]
        (if-not agent
          {:content [{:type "text" :text (str "Agent not found: " (name agent-id))}]
           :isError true}
          (do
            (when-let [control (:control agent)]
              (control {:cmd :stop}))
            (swap! agent-registry dissoc agent-id)
            {:content [{:type "text" :text (str "Agent stopped and removed: " (name agent-id))}]
             :isError false}))))

    "runtime_list"
    (fn [_context _arguments]
      (let [agents @agent-registry]
        {:content [{:type "text"
                    :text (str "MCP server — active agents (" (count agents) "):\n"
                               (if (seq agents)
                                 (str/join "\n"
                                   (map (fn [[id ag]]
                                          (str "  " (name id) ": "
                                               (name (get @(:state-a ag) :status :unknown))))
                                        agents))
                                 "  (none)")
                               "\ndb-conn: " (if @server-db-conn "connected" "not configured"))}]
         :isError false}))

    "repl_eval"
    (fn [_context arguments]
      (let [code (:code arguments)
            ns-str (or (:namespace arguments) "user")
            timeout-ms (or (:timeout_ms arguments) 30000)]
        (try
          (let [ns-sym (symbol ns-str)
                the-ns (or (find-ns ns-sym) (create-ns ns-sym))
                out (java.io.StringWriter.)
                result-p (future
                           (binding [*out* out *ns* the-ns]
                             (eval (read-string code))))
                result (deref result-p timeout-ms ::timeout)]
            (if (= result ::timeout)
              (do (future-cancel result-p)
                  {:content [{:type "text"
                               :text (str "Evaluation timed out after " timeout-ms "ms")}]
                   :isError true})
              {:content [{:type "text"
                          :text (str (let [s (str out)] (when (seq s) (str "Output:\n" s "\n")))
                                     "=> " (pr-str result))}]
               :isError false}))
          (catch Exception e
            {:content [{:type "text" :text (str "Error: " (.getMessage e))}]
             :isError true}))))

    "memory_query"
    (fn [_context arguments]
      (if-let [conn @server-db-conn]
        (try
          (require 'datahike.api)
          (let [d-q (ns-resolve (find-ns 'datahike.api) 'q)
                query (read-string (:query arguments))
                extra-args (mapv read-string (or (:args arguments) []))
                result (apply d-q query @conn extra-args)]
            {:content [{:type "text" :text (pr-str result)}]
             :isError false})
          (catch Exception e
            {:content [{:type "text" :text (str "Query error: " (.getMessage e))}]
             :isError true}))
        {:content [{:type "text"
                    :text "No database connection. Call set-db-conn! or start via daemon."}]
         :isError true}))

    "memory_transact"
    (fn [_context arguments]
      (if-let [conn @server-db-conn]
        (try
          (require 'datahike.api)
          (let [d-transact! (ns-resolve (find-ns 'datahike.api) 'transact!)
                tx-data (read-string (:tx_data arguments))
                result (d-transact! conn tx-data)]
            {:content [{:type "text"
                        :text (str "Transaction complete. tx-id: " (:db/current-tx result))}]
             :isError false})
          (catch Exception e
            {:content [{:type "text" :text (str "Transaction error: " (.getMessage e))}]
             :isError true}))
        {:content [{:type "text"
                    :text "No database connection. Call set-db-conn! or start via daemon."}]
         :isError true}))

    "memory_history"
    (fn [_context arguments]
      (if-let [conn @server-db-conn]
        (try
          (require 'datahike.api)
          (let [d-q (ns-resolve (find-ns 'datahike.api) 'q)
                d-history (ns-resolve (find-ns 'datahike.api) 'history)
                entity-id (:entity_id arguments)
                attr (keyword (str/replace (str (:attribute arguments)) #"^:" ""))
                result (d-q '[:find ?v ?tx :in $ ?e ?a :where [?e ?a ?v ?tx true]]
                            (d-history @conn) entity-id attr)]
            {:content [{:type "text" :text (pr-str (sort-by second result))}]
             :isError false})
          (catch Exception e
            {:content [{:type "text" :text (str "History error: " (.getMessage e))}]
             :isError true}))
        {:content [{:type "text"
                    :text "No database connection. Call set-db-conn! or start via daemon."}]
         :isError true}))}))

(def tool-definitions
  "Atom of tool definition vectors. Dynamic for agent-created tools and REPL hot-swap."
  (atom
   [{:name "runtime_list"
     :description "List all active runtime forks and their relationships."
     :inputSchema {:type "object" :properties {}}}

    {:name "runtime_fork"
     :description "Create an isolated runtime fork for speculative execution. All memory (datahike), indices, and bindings are copied-on-write (O(1) cost). Changes in the fork don't affect the parent until merged."
     :inputSchema {:type "object"
                   :properties {:branch_name {:type "string"
                                              :description "Name for the new fork branch"}
                                :description {:type "string"
                                              :description "Description of what this fork is for"}}
                   :required ["branch_name"]}}

    {:name "runtime_merge"
     :description "Merge a fork back to its parent branch (or another branch). Combines all memory changes, index updates, and file modifications."
     :inputSchema {:type "object"
                   :properties {:from_fork {:type "string"
                                            :description "Fork ID to merge from"}
                                :to_branch {:type "string"
                                            :description "Target branch to merge into (default: parent)"}
                                :strategy {:type "string"
                                           :enum ["auto" "manual"]
                                           :description "Merge strategy (default: auto)"}}
                   :required ["from_fork"]}}

    {:name "runtime_discard"
     :description "Discard a fork without merging. All changes in the fork are lost."
     :inputSchema {:type "object"
                   :properties {:fork_id {:type "string"
                                          :description "Fork ID to discard"}}
                   :required ["fork_id"]}}

    {:name "memory_query"
     :description "Query datahike memory with Datalog. Returns structured results from the knowledge base. Datalog is a declarative query language - use :find to specify what to return, :where for patterns to match."
     :inputSchema {:type "object"
                   :properties {:runtime {:type "string"
                                          :description "Runtime/fork ID to query (default: main)"}
                                :query {:type "string"
                                        :description "Datalog query as EDN string, e.g. '[:find ?e ?v :where [?e :file/path ?v]]'"}
                                :args {:type "array"
                                       :description "Additional query arguments"
                                       :items {:type "string"}}}
                   :required ["query"]}}

    {:name "memory_transact"
     :description "Add or update facts in datahike memory. Facts are EAV triples (entity-attribute-value). Use negative db/id for new entities."
     :inputSchema {:type "object"
                   :properties {:runtime {:type "string"
                                          :description "Runtime/fork ID to transact in (default: main)"}
                                :tx_data {:type "string"
                                          :description "Transaction data as EDN string, e.g. '[{:db/id -1 :file/path \"src/core.clj\"}]'"}}
                   :required ["tx_data"]}}

    {:name "memory_history"
     :description "Get the history of an entity attribute across time. Shows all values the attribute has had, with transaction IDs and timestamps."
     :inputSchema {:type "object"
                   :properties {:runtime {:type "string"
                                          :description "Runtime/fork ID (default: main)"}
                                :entity_id {:type "integer"
                                            :description "Entity ID to get history for"}
                                :attribute {:type "string"
                                            :description "Attribute keyword, e.g. 'file/content'"}}
                   :required ["entity_id" "attribute"]}}

    {:name "repl_eval"
     :description "Evaluate Clojure code in the runtime context. Code has access to all loaded namespaces and can interact with datahike, spindel, and other dvergr subsystems."
     :inputSchema {:type "object"
                   :properties {:runtime {:type "string"
                                          :description "Runtime/fork ID (default: main)"}
                                :code {:type "string"
                                       :description "Clojure code to evaluate"}
                                :namespace {:type "string"
                                            :description "Namespace to evaluate in (default: user)"}
                                :timeout_ms {:type "integer"
                                             :description "Evaluation timeout in milliseconds (default: 30000)"}}
                   :required ["code"]}}

    ;; Agent management tools
    {:name "agent_list"
     :description "List all active agents and their status."
     :inputSchema {:type "object" :properties {}}}

    {:name "agent_create"
     :description "Create an agent with provider/model/prompt configuration. The agent is registered but not started as a reactive process until a message is sent."
     :inputSchema {:type "object"
                   :properties {:agent_id {:type "string"
                                           :description "Unique identifier for the agent"}
                                :provider {:type "string"
                                           :enum ["anthropic" "openai" "fireworks"]
                                           :description "LLM provider (default: fireworks)"}
                                :model {:type "string"
                                        :description "Model identifier"}
                                :system_prompt {:type "string"
                                                :description "System prompt for the agent"}}
                   :required ["agent_id"]}}

    {:name "agent_send_message"
     :description "Send a message to an agent's inbox and await the response from its outbox. Blocks until the agent responds or timeout is reached."
     :inputSchema {:type "object"
                   :properties {:agent_id {:type "string"
                                           :description "Agent identifier"}
                                :message {:type "string"
                                          :description "Message to send to the agent"}
                                :timeout_ms {:type "integer"
                                             :description "Timeout in milliseconds (default: 120000)"}}
                   :required ["agent_id" "message"]}}

    {:name "agent_stop"
     :description "Stop an agent and remove it from the registry."
     :inputSchema {:type "object"
                   :properties {:agent_id {:type "string"
                                           :description "Agent identifier to stop"}}
                   :required ["agent_id"]}}]))

;; ============================================================================
;; Connected clients (for server-initiated notifications)
;; ============================================================================

;; Atom of #{send-fn}. Each connected client registers its send-fn here.
(defonce ^:private connected-send-fns (atom #{}))

(defn- broadcast-notification!
  "Send a JSON-RPC notification to all connected clients."
  [method params]
  (let [notification {:jsonrpc "2.0" :method method :params params}]
    (doseq [send-fn @connected-send-fns]
      (try
        (send-fn notification)
        (catch Exception e
          (binding [*err* *err*]
            (.println *err* (str "dvergr-mcp: broadcast error: " (.getMessage e)))
            (.flush *err*)))))))

;; ============================================================================
;; Dynamic tool registration
;; ============================================================================

(defn register-tool!
  "Register a new tool dynamically. Broadcasts tools/list_changed to all clients.
   tool-def: {:name \"...\" :description \"...\" :inputSchema {...}}
   handler-fn: (fn [context arguments] -> {:content [...] :isError bool})"
  [tool-def handler-fn]
  (swap! tool-definitions conj tool-def)
  (swap! tool-handlers assoc (:name tool-def) handler-fn)
  (broadcast-notification! "notifications/tools/list_changed" {}))

(defn unregister-tool!
  "Remove a tool by name. Broadcasts tools/list_changed to all clients."
  [tool-name]
  (swap! tool-definitions (fn [defs] (vec (remove #(= (:name %) tool-name) defs))))
  (swap! tool-handlers dissoc tool-name)
  (broadcast-notification! "notifications/tools/list_changed" {}))

;; ============================================================================
;; Session factory
;; ============================================================================

(defn make-session
  "Create a fresh MCP session atom. Each connection gets its own session."
  []
  (atom (json-rpc/create-session
         {:server-info {:name "dvergr-mcp" :version "0.1.0"}})))

;; ============================================================================
;; Connection handling (shared by TCP and stdio)
;; ============================================================================

(defn- handle-connection
  "Handle a single MCP connection. Reads JSON lines from reader,
   dispatches via json-rpc/handle-message, writes responses via writer.
   Blocks until reader is closed."
  [^BufferedReader reader ^BufferedWriter writer label]
  (let [session (make-session)
        send-fn (fn [message]
                  (locking writer
                    (.write writer (json/write-value-as-string message json-write-mapper))
                    (.write writer "\n")
                    (.flush writer)))
        context {:session session
                 :send-fn send-fn
                 :tool-defs tool-definitions
                 :tool-handlers tool-handlers}]
    (swap! connected-send-fns conj send-fn)
    (binding [*err* *err*]
      (.println *err* (str "dvergr-mcp: connection opened (" label ")"))
      (.flush *err*))
    (try
      (loop []
        (when-some [line (.readLine reader)]
          (let [message (try
                          (json/read-value line json-read-mapper)
                          (catch Exception _e
                            (send-fn json-rpc/parse-error-response)
                            nil))]
            (when message
              (when-some [response (json-rpc/handle-message context message)]
                (send-fn response)))
            (recur))))
      (finally
        (swap! connected-send-fns disj send-fn)
        (binding [*err* *err*]
          (.println *err* (str "dvergr-mcp: connection closed (" label ")"))
          (.flush *err*))))))

;; ============================================================================
;; TCP transport
;; ============================================================================

(defonce ^:private server-state (atom nil))
(defonce connections (atom #{}))

(defn start!
  "Start TCP MCP server. Returns the server state.
   Options:
     :port - TCP port (default 17888)
     :bind - Bind address (default \"0.0.0.0\")"
  [& {:keys [port bind] :or {port 17888 bind "0.0.0.0"}}]
  (when @server-state
    (throw (ex-info "Server already running" {:port port})))
  (let [^ServerSocket server-socket (ServerSocket. port 50 (java.net.InetAddress/getByName bind))
        accept-thread (Thread.
                       (fn []
                         (loop []
                           (when-not (.isClosed server-socket)
                             (let [client (try (.accept server-socket)
                                               (catch Exception _e nil))]
                               (when client
                                 (let [^Socket client client
                                       addr (str (.getInetAddress client) ":" (.getPort client))]
                                   (swap! connections conj client)
                                   (doto (Thread.
                                          (fn []
                                            (try
                                              (let [reader (BufferedReader. (InputStreamReader. (.getInputStream client)))
                                                    writer (BufferedWriter. (OutputStreamWriter. (.getOutputStream client)))]
                                                (handle-connection reader writer addr))
                                              (catch Exception e
                                                (when-not (.isClosed client)
                                                  (binding [*err* *err*]
                                                    (.println *err* (str "dvergr-mcp: error on " addr ": " (.getMessage e)))
                                                    (.flush *err*))))
                                              (finally
                                                (swap! connections disj client)
                                                (.close client)))))
                                     (.setDaemon true)
                                     (.start))))
                               (recur)))))
                       "dvergr-mcp-accept")]
    (.setDaemon accept-thread true)
    (.start accept-thread)
    (reset! server-state {:server-socket server-socket
                          :accept-thread accept-thread
                          :port port})
    (.println *err* (str "dvergr-mcp: TCP server listening on " bind ":" port))
    (.flush *err*)
    {:port port :bind bind}))

(defn stop!
  "Stop the TCP MCP server."
  []
  (when-let [{:keys [^ServerSocket server-socket]} @server-state]
    (.close server-socket)
    (doseq [^Socket conn @connections]
      (try (.close conn) (catch Exception _)))
    (reset! connections #{})
    (reset! server-state nil)
    (.println *err* "dvergr-mcp: TCP server stopped")
    (.flush *err*)
    :stopped))

(defn status
  "Return server status."
  []
  (if-let [{:keys [port]} @server-state]
    {:running true :port port :connections (count @connections)}
    {:running false}))

;; ============================================================================
;; Stdio transport (for uberjar / subprocess mode)
;; ============================================================================

(defn run-stdio
  "Run MCP server on stdio. Blocks until stdin closes."
  []
  (let [reader (BufferedReader. (InputStreamReader. System/in))
        writer (BufferedWriter. (OutputStreamWriter. System/out))]
    (handle-connection reader writer "stdio")))

;; ============================================================================
;; Entry point
;; ============================================================================

(defn -main
  "Entry point. Default: TCP on port 17888. Use --stdio for subprocess mode."
  [& args]
  (let [args-set (set args)]
    (cond
      (args-set "--stdio")
      (run-stdio)

      :else
      (let [port (if-let [p (some #(when (re-matches #"\d+" %) %) args)]
                   (Integer/parseInt p)
                   (if-let [p (args-set "--port")]
                     (Integer/parseInt (first (rest (drop-while #(not= % "--port") args))))
                     17888))]
        (start! :port port)
        ;; Keep main thread alive
        (.join ^Thread (:accept-thread @server-state))))))
