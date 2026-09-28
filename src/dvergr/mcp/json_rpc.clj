(ns dvergr.mcp.json-rpc
  "Clean-room JSON-RPC 2.0 dispatch and MCP protocol handlers, for both eras
   of the protocol:
   - the handshake versions 2024-11-05 through 2025-11-25: `initialize`, then
     requests on that connection's session;
   - the stateless 2026-07-28: each request names its version, client info
     and capabilities in `_meta` (`io.modelcontextprotocol/*`), no
     `initialize`; `server/discover` tells a client what the server speaks.
   The era is decided per request (a stateless version in `_meta`), so one
   connection may carry both. `handle-message` answers one message; the
   transport (`dvergr.mcp.server/dispatch!`) decides what runs concurrently.

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

(def supported-versions
  "Handshake protocol versions, oldest first."
  ["2024-11-05" "2025-03-26" "2025-06-18" "2025-11-25"])

(def stateless-versions
  "Stateless protocol versions (no handshake; the version is in each
   request's `_meta`), oldest first."
  ["2026-07-28"])

(defn negotiate-version
  "The version to answer `requested` with: the same when supported, else the
   latest this server speaks (the spec's rule; the client then decides whether
   it can continue)."
  [requested]
  (if (some #{requested} supported-versions)
    requested
    (peek supported-versions)))

(defn- initialize-handler
  "Negotiate protocol version, store client info, return server capabilities.
   `(:on-initialize context)`, when present, sees the params first (the server
   reads its per-session profile from `_meta` there)."
  [context message]
  (let [session-atom (:session context)
        params (:params message)
        version (negotiate-version (:protocolVersion params))]
    (swap! session-atom assoc
           :client-info (:clientInfo params)
           :client-capabilities (:capabilities params)
           :protocol-version version)
    (when-let [f (:on-initialize context)] (f params))
    (cond-> {:protocolVersion version
             :serverInfo (:server-info @session-atom)
             :capabilities {:tools {:listChanged true}
                            :resources {:subscribe true :listChanged true}}}
      (:instructions @session-atom) (assoc :instructions (:instructions @session-atom)))))

(defn- initialized-notification-handler
  "Client confirms initialization. Switch to post-init handler map."
  [context _message]
  (let [session-atom (:session context)]
    (swap! session-atom assoc
           :initialized true
           :handler-by-method (post-init-handlers context))
    nil))

(def ^:private tool-def-keys [:name :title :description :inputSchema :outputSchema :annotations])

(defn- session-tool-defs
  "The tool definitions this session may see and call: all of them, or those
   `(:tool-visible? context)` admits, as `(:tool-view context)` presents them
   (a room-pinned session drops the `room` parameter)."
  [context]
  (let [visible? (or (:tool-visible? context) (constantly true))
        view (or (:tool-view context) identity)]
    (mapv view (filterv visible? @(:tool-defs context)))))

(defn- tools-list-handler
  "Return the session's tool definitions from the dynamic registry."
  [context _message]
  {:tools (mapv #(select-keys % tool-def-keys) (session-tool-defs context))})

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
        tool-exists? (some #(= (:name %) tool-name) (session-tool-defs context))]
    (if-not tool-exists?
      (throw (ex-info (str "Unknown tool: " tool-name)
                      {:tool-name tool-name :json-rpc/code -32602}))
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
                                 (initialized-notification-handler ctx msg))
   ;; a stateless client never initializes and may still cancel
   "notifications/cancelled" cancelled-notification-handler})

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
;; The stateless era (2026-07-28)
;; ============================================================================

(def ^:private mcp-meta
  "The `_meta` keys the stateless protocol reserves."
  {:protocol-version :io.modelcontextprotocol/protocolVersion
   :client-info :io.modelcontextprotocol/clientInfo
   :client-capabilities :io.modelcontextprotocol/clientCapabilities
   :server-info :io.modelcontextprotocol/serverInfo})

(defn request-version
  "The protocol version a request names in its `_meta`, or nil (a handshake-era
   request)."
  [message]
  (get-in message [:params :_meta (:protocol-version mcp-meta)]))

(defn stateless?
  "Whether `message` is answered in the stateless era: `server/discover`, or a
   request whose `_meta` names a version that is not a handshake version (an
   unsupported one is answered with that era's error)."
  [message]
  (let [v (request-version message)]
    (or (= "server/discover" (:method message))
        (and (some? v) (not (some #{v} supported-versions))))))

(def ^:private cache-hints
  "`ttlMs`/`cacheScope` of each cacheable result. `private`: what a connection
   sees depends on its selection (profile, toolsets, pinned room), so no
   shared cache may reuse it; a read is live, so it is not reused at all."
  {"server/discover" {:ttlMs 300000 :cacheScope "private"}
   "tools/list" {:ttlMs 60000 :cacheScope "private"}
   "resources/list" {:ttlMs 60000 :cacheScope "private"}
   "resources/templates/list" {:ttlMs 60000 :cacheScope "private"}
   "resources/read" {:ttlMs 0 :cacheScope "private"}})

(def ^:private stateless-capabilities
  ;; list changes and resource subscriptions arrive with subscriptions/listen
  {:tools {} :resources {}})

(defn- discover-handler [context _message]
  (let [session @(:session context)]
    (cond-> {:supportedVersions (into (vec stateless-versions) (rseq supported-versions))
             :capabilities stateless-capabilities}
      (:instructions session) (assoc :instructions (:instructions session)))))

(def ^:private stateless-handlers
  {"server/discover" discover-handler
   "tools/list" tools-list-handler
   "tools/call" tools-call-handler
   "resources/list" resources-list-handler
   "resources/templates/list" resources-templates-list-handler
   "resources/read" resources-read-handler
   "notifications/cancelled" cancelled-notification-handler})

(defn- unsupported-version-response [id requested]
  {:jsonrpc "2.0" :id id
   :error {:code -32022
           :message (str "Unsupported protocol version: " requested)
           :data {:supported (into (vec stateless-versions) (rseq supported-versions))
                  :requested requested}}})

(defn- error-response
  "The response for exception `e` from a handler: the protocol error it names
   (`:json-rpc/code`), else an internal error."
  [id ^Exception e]
  (let [{code :json-rpc/code data :json-rpc/data} (ex-data e)]
    (cond
      (= -32602 code) (invalid-params-response id (.getMessage e))
      ;; any other protocol error a handler names (e.g. -32002, resource
      ;; not found)
      (integer? code) {:jsonrpc "2.0" :id id
                       :error (cond-> {:code code :message (.getMessage e)}
                                data (assoc :data data))}
      :else (internal-error-response id (.getMessage e)))))

(defn- handle-stateless
  "Answer a stateless-era message. The request carries everything: its
   selection comes from its own `_meta` (`(:request-context context)`), the
   connection's session is not consulted except for cancellations. Every
   result says it is complete and which server answered."
  [context message]
  (let [{:keys [id method params]} message
        version (request-version message)
        handler (get stateless-handlers method)]
    (cond
      (and version (not (some #{version} stateless-versions)))
      (when id (unsupported-version-response id version))

      (nil? handler)
      (when id (method-not-found-response id))

      (nil? id)
      (try (handler context message) nil
           (catch Exception _ nil))

      :else
      (try
        (let [ctx (if-let [f (:request-context context)] (f params) context)
              result (handler ctx message)]
          {:jsonrpc "2.0" :id id
           :result (-> result
                       (merge (get cache-hints method))
                       (assoc :resultType "complete")
                       (assoc-in [:_meta (:server-info mcp-meta)] (:server-info @(:session context))))})
        (catch Exception e
          (let [resp (error-response id e)]
            ;; a missing resource is invalid params in this era (-32002 before)
            (cond-> resp
              (= -32002 (get-in resp [:error :code])) (assoc-in [:error :code] -32602))))))))

;; ============================================================================
;; Session factory
;; ============================================================================

(defn create-session
  "Create a fresh MCP session state map.
   Options:
     :server-info  - {:name \"...\" :version \"...\"}
     :instructions - text returned from initialize (optional)"
  [{:keys [server-info instructions]}]
  {:initialized false
   :instructions instructions
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

      (stateless? message)
      (handle-stateless context message)

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
          (error-response id e))))))
