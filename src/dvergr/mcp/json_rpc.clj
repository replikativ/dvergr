(ns dvergr.mcp.json-rpc
  "Clean-room JSON-RPC 2.0 dispatch and MCP protocol handlers.
   Implements MCP spec (2024-11-05 and 2025-11-25) with synchronous dispatch.

   All handlers are plain fns: (fn [context message] -> result-map | nil).
   handle-message returns a response map directly (no promises).")

;; ============================================================================
;; JSON-RPC 2.0 error responses
;; ============================================================================

(def parse-error-response
  "Static parse error response (malformed JSON)."
  {:jsonrpc "2.0"
   :error {:code -32700 :message "Parse error"}
   :id nil})

(defn method-not-found-response [id]
  {:jsonrpc "2.0"
   :error {:code -32601 :message "Method not found"}
   :id id})

(defn invalid-params-response [id msg]
  {:jsonrpc "2.0"
   :error {:code -32602 :message (str "Invalid params: " msg)}
   :id id})

(defn internal-error-response [id msg]
  {:jsonrpc "2.0"
   :error {:code -32603 :message (str "Internal error: " msg)}
   :id id})

;; ============================================================================
;; MCP protocol handlers
;; ============================================================================

(declare post-init-handlers)

(defn- ping-handler [_context _message]
  {})

(def ^:private supported-versions #{"2024-11-05" "2025-11-25"})

(defn- initialize-handler
  "Negotiate protocol version, store client info, return server capabilities."
  [context message]
  (let [session-atom (:session context)
        params (:params message)
        requested-version (:protocolVersion params)
        version (if (supported-versions requested-version)
                  requested-version
                  "2024-11-05")]
    (swap! session-atom assoc
           :client-info (:clientInfo params)
           :client-capabilities (:capabilities params)
           :protocol-version version)
    {:protocolVersion version
     :serverInfo (:server-info @session-atom)
     :capabilities {:tools {:listChanged true}
                    :resources {:subscribe true :listChanged true}}}))

(defn- initialized-notification-handler
  "Client confirms initialization. Switch to post-init handler map."
  [context _message]
  (let [session-atom (:session context)]
    (swap! session-atom assoc
           :initialized true
           :handler-by-method (post-init-handlers context))
    nil))

(defn- tools-list-handler
  "Return current tool definitions from the dynamic registry."
  [context _message]
  (let [tool-defs @(:tool-defs context)]
    {:tools (mapv #(select-keys % [:name :description :inputSchema]) tool-defs)}))

(defn- stub-handler
  "Default stub handler for tools without explicit implementations."
  [tool-name _context arguments]
  {:content [{:type "text"
              :text (str "dvergr-mcp: tool '" tool-name "' called with args: "
                         (pr-str arguments)
                         "\n\n[STUB] Tool implementation pending. "
                         "This confirms MCP communication is working.")}]
   :isError false})

(defn- tools-call-handler
  "Look up and execute a tool by name."
  [context message]
  (let [params (:params message)
        tool-name (:name params)
        arguments (or (:arguments params) {})
        tool-defs @(:tool-defs context)
        tool-exists? (some #(= (:name %) tool-name) tool-defs)]
    (if-not tool-exists?
      (throw (ex-info (str "Unknown tool: " tool-name) {:tool-name tool-name}))
      (let [handler (get @(:tool-handlers context) tool-name)]
        (try
          (if handler
            (handler context arguments)
            (stub-handler tool-name context arguments))
          (catch Exception e
            {:content [{:type "text"
                        :text (str "Error executing tool '" tool-name "': " (.getMessage e))}]
             :isError true}))))))

(defn- cancelled-notification-handler
  "Track cancelled request IDs."
  [context message]
  (let [session-atom (:session context)
        request-id (get-in message [:params :requestId])]
    (when request-id
      (swap! session-atom update :cancelled-requests (fnil conj #{}) request-id))
    nil))

;; ---- Resources (derived from dvergr.ops reads; see dvergr.mcp.server) ----
;; The server supplies the resource defs + read/subscribe fns via context.

(defn- resources-list-handler [context _message]
  {:resources (:resources @(:resource-defs context))})

(defn- resources-templates-list-handler [context _message]
  {:resourceTemplates (:templates @(:resource-defs context))})

(defn- resources-read-handler [context message]
  ((:read-resource context) (get-in message [:params :uri])))

(defn- resources-subscribe-handler [context message]
  ((:subscribe-resource context) (get-in message [:params :uri]) (:send-fn context))
  {})

(defn- resources-unsubscribe-handler [context message]
  ((:unsubscribe-resource context) (get-in message [:params :uri]) (:send-fn context))
  {})

;; ============================================================================
;; Handler maps (pre-init and post-init)
;; ============================================================================

(defn- pre-init-handlers
  "Handler map before initialization is complete."
  [context]
  {"ping" ping-handler
   "initialize" initialize-handler
   "notifications/initialized" (fn [ctx msg]
                                 (initialized-notification-handler ctx msg))})

(defn- post-init-handlers
  "Handler map after initialization is complete."
  [_context]
  {"ping" ping-handler
   "tools/list" tools-list-handler
   "tools/call" tools-call-handler
   "resources/list" resources-list-handler
   "resources/templates/list" resources-templates-list-handler
   "resources/read" resources-read-handler
   "resources/subscribe" resources-subscribe-handler
   "resources/unsubscribe" resources-unsubscribe-handler
   "notifications/cancelled" cancelled-notification-handler})

;; ============================================================================
;; Session factory
;; ============================================================================

(defn create-session
  "Create a fresh MCP session state map.
   Options:
     :server-info - {:name \"...\" :version \"...\"}
     :protocol-version - initial version string"
  [{:keys [server-info]}]
  {:initialized false
   :handler-by-method nil ;; set during first handle-message
   :server-info (or server-info {:name "mcp-server" :version "0.1.0"})
   :protocol-version nil
   :client-info nil
   :client-capabilities nil
   :cancelled-requests #{}})

;; ============================================================================
;; Message dispatch
;; ============================================================================

(defn handle-message
  "Dispatch a parsed JSON-RPC message. Returns a response map for requests
   (messages with :id), or nil for notifications (no :id).
   Context must contain:
     :session     - atom wrapping create-session map
     :tool-defs   - atom of tool definition vectors
     :tool-handlers - atom of {tool-name -> handler-fn}
     :send-fn     - fn for server-initiated notifications (optional)"
  [context message]
  (let [session-atom (:session context)
        ;; Lazily initialize handler map on first message
        _ (when-not (:handler-by-method @session-atom)
            (swap! session-atom assoc :handler-by-method (pre-init-handlers context)))
        method (:method message)
        id (:id message)
        handlers (:handler-by-method @session-atom)
        handler (get handlers method)]
    (cond
      ;; No method field — ignore (could be a response to something we sent)
      (nil? method)
      nil

      ;; Unknown method
      (nil? handler)
      (when id
        (method-not-found-response id))

      ;; Notification (no id) — call handler, return nil
      (nil? id)
      (try
        (handler context message)
        nil
        (catch Exception e
          (binding [*err* *err*]
            (.println *err* (str "dvergr-mcp: notification handler error: " (.getMessage e)))
            (.flush *err*))
          nil))

      ;; Request (has id) — call handler, wrap in JSON-RPC envelope
      :else
      (try
        (let [result (handler context message)]
          {:jsonrpc "2.0"
           :id id
           :result result})
        (catch Exception e
          (internal-error-response id (.getMessage e)))))))
