(ns dvergr.mcp.server
  "MCP (Model Context Protocol) server for exposing dvergr capabilities.

   Transports:
   - TCP on loopback (newline-delimited JSON-RPC), started by the daemon
     (`:mcp` config or `--mcp`). Clients reach it through `bin/dvergr-mcp`, a
     stdio relay that starts and reconnects to the daemon (see doc/mcp.md).
   - stdio in this process (`--stdio`): tools answer only when a daemon runs
     in the same JVM.

   Which tools a connection sees (profiles, toolsets), their annotations and
   the result encoding are `dvergr.mcp.surface`.

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
            [dvergr.mcp.surface :as surface]
            [dvergr.ops :as ops]
            [dvergr.rooms.facts :as facts]
            [dvergr.chat.context :as chat-context]
            [org.replikativ.spindel.engine.core :as ec]
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
;; Tools — DERIVED from the central dvergr.ops specification
;; ============================================================================
;;
;; The MCP surface is a projection of `dvergr.ops` (rooms/agents/forks) — no
;; parallel agent registry, no unsandboxed eval. A new op added to the spec
;; appears here automatically, named identically across every binding. (Coding
;; tools — the `dvergr.tools` registry, sandboxed — are a follow-up.)

;; Kept so the daemon can wire a db conn if it wants; ops resolves its own.
(defonce server-db-conn (atom nil))
(defn set-db-conn! [conn] (reset! server-db-conn conn))

(defn- current-daemon []
  (some-> (requiring-resolve 'dvergr.orchestration.daemon/current-daemon) deref deref))

(defn- keywordize [m]
  (reduce-kv (fn [a k v] (assoc a (keyword k) v)) {} (or m {})))

(defn strict-schema
  "An input schema in the form strict model APIs accept: an object at the root
   with `properties`, closed (`additionalProperties false`)."
  [schema]
  (-> (or schema {})
      (assoc :type "object")
      (update :properties #(or % {}))
      (assoc :additionalProperties false)))

(def ^:private no-daemon "No dvergr daemon is running in this process.")

(defn- op->tool
  "Project one dvergr.ops spec entry to an MCP tool: {:name :def :handler}. The
   handler resolves the live daemon, invokes the op (which ctx-binds + calls the
   shared fn), and returns the data as JSON. Reads and writes are both exposed
   as callable tools."
  [op {:keys [doc kind]}]
  (let [tname (ops/op->name op)
        annotations (surface/op-annotations op kind)]
    {:name tname
     :def  {:name tname :title (:title annotations) :description doc
            :inputSchema (strict-schema (ops/input-schema op))
            :annotations annotations
            :dvergr/toolset (surface/tool-toolset tname op false)}
     :handler
     (fn [_context arguments]
       (if-let [dmn (current-daemon)]
         (try
           (surface/data-result (ops/invoke dmn op (keywordize arguments)))
           (catch Throwable e
             (surface/error-result (str "Error: " (.getMessage e)))))
         (surface/error-result no-daemon)))}))

(def ^:private ops-tools
  (mapv (fn [[op spec]] (op->tool op spec)) ops/specification))

;; ---- Coding tools: the dvergr.tools registry, re-served ROOM-SCOPED + sandboxed.
;; Each gets an optional `room` param; when given, the tool runs in that room's
;; SCI sandbox + git/datahike workspace (fork-isolated) via the room's chat-ctx.

(defn- with-room-param
  "A registry tool's parameters with a required `room`: over MCP a coding tool
   always runs in a room's sandbox and workspace, never in the daemon's own."
  [params]
  (-> (or params {:type "object" :properties {}})
      (update :properties assoc :room
              {:type "string"
               :description "Room id or slug; the tool runs in that room's sandbox and workspace"})
      (update :required #(vec (distinct (conj (vec %) "room"))))
      strict-schema))

(defn- run-coding-tool [room tname input]
  (let [execute      (requiring-resolve 'dvergr.tools/execute)
        make-context (requiring-resolve 'dvergr.tools/make-context)
        ensure-ctx!  (requiring-resolve 'dvergr.agent.room-context/ensure-ctx!)]
    (binding [ec/*execution-context* (:ctx room)]
      (let [cctx (ensure-ctx! room :mcp {})]
        (execute tname input (make-context {:sci-ctx  (chat-context/sci-context-in
                                                       cctx (:ctx room))
                                            :db-conn  (:db-conn cctx)
                                            :chat-ctx cctx
                                            :execution-ctx (:ctx room)}))))))

(defn- coding-tool [tname tdef]
  (let [annotations (surface/tool-annotations tname)]
    {:name tname
     :def  {:name tname :title (:title annotations)
            :description (str (:description tdef) "\n\nRuns in the given room's sandbox and workspace.")
            :inputSchema (with-room-param (:parameters tdef))
            :annotations annotations
            :dvergr/toolset (surface/tool-toolset tname nil true)}
     :handler
     (fn [_context arguments]
       (if-let [dmn (current-daemon)]
         (let [args  (keywordize arguments)
               input (dissoc args :room)
               room  (when-not (str/blank? (str (:room args)))
                       (ops/resolve-room dmn (:room args)))]
           (if-not room
             (surface/error-result
              (if (str/blank? (str (:room args)))
                (str tname " needs a room: pass `room` (id or slug; room_list lists them)")
                (str "No room " (pr-str (:room args)) "; room_list lists them")))
             (try
               (surface/tool-result (run-coding-tool room tname input))
               (catch Throwable e
                 (surface/error-result (str "Error: " (.getMessage e)))))))
         (surface/error-result no-daemon)))}))

(def ^:private coding-tools
  (mapv (fn [[tname tdef]] (coding-tool tname tdef))
        (deref (deref (requiring-resolve 'dvergr.tools/registry)))))

(def ^:private all-tools (into ops-tools coding-tools))

(def tool-definitions
  "MCP tool defs: dvergr.ops (rooms/agents/forks) + the room-scoped coding tools
   (dvergr.tools registry). An atom so `register-tool!` can add dynamic tools."
  (atom (mapv :def all-tools)))

(def tool-handlers
  "tool-name -> handler, for all derived tools."
  (atom (into {} (map (juxt :name :handler)) all-tools)))

;; ============================================================================
;; Resources — DERIVED from dvergr.ops :read ops (each room is a live resource)
;; ============================================================================
;;
;; A read with no required args → a fixed resource (room://list); a read with a
;; required arg → a resource TEMPLATE (room://{room}/messages). `resources/read`
;; maps a URI back to op + args and invokes it. `resources/subscribe` on a uri about
;; a room (room://, experiment://…/progress, …) attaches the room's facts feed
;; (dvergr.rooms.facts), which pushes notifications/resources/updated after
;; every durable change — so a room's reads are LIVE resources.

(defn- op->resource [op {:keys [doc]}]
  (let [scheme (namespace op) rname (name op)
        required (:required (ops/input-schema op))]
    (if (empty? required)
      {:kind :fixed :op op :uri (str scheme "://" rname)
       :def {:uri (str scheme "://" rname) :name (ops/op->name op)
             :description doc :mimeType "application/json"}}
      (let [arg (name (first required))]
        {:kind :template :op op :arg (keyword arg) :scheme scheme :rname rname
         :def {:uriTemplate (str scheme "://{" arg "}/" rname) :name (ops/op->name op)
               :description doc :mimeType "application/json"}}))))

(def ^:private ops-resources
  (mapv (fn [[op spec]] (op->resource op spec)) (ops/reads)))

(def resource-definitions
  "Fixed resources + resource templates, derived from dvergr.ops reads."
  (atom {:resources (mapv :def (filter #(= :fixed (:kind %)) ops-resources))
         :templates (mapv :def (filter #(= :template (:kind %)) ops-resources))}))

(def ^:private fixed-by-uri
  (into {} (for [{:keys [kind uri op]} ops-resources :when (= :fixed kind)] [uri op])))
(def ^:private template-by-key
  (into {} (for [{:keys [kind scheme rname op arg]} ops-resources :when (= :template kind)]
             [[scheme rname] {:op op :arg arg}])))

(defn- uri->op+args
  "Map an MCP resource URI back to a dvergr.ops [op args], or nil."
  [uri]
  (let [[scheme rst] (str/split uri #"://" 2)
        segs (str/split (or rst "") #"/")]
    (cond
      (get fixed-by-uri uri) {:op (get fixed-by-uri uri) :args {}}
      (= 2 (count segs))     (when-let [{:keys [op arg]} (get template-by-key [scheme (second segs)])]
                               {:op op :args {arg (first segs)}})
      :else nil)))

(defn- read-resource [uri]
  (if-let [dmn (current-daemon)]
    (if-let [{:keys [op args]} (uri->op+args uri)]
      {:contents [{:uri uri :mimeType "application/json"
                   :text (-> (surface/data-result (ops/invoke dmn op args)) :content first :text)}]}
      {:contents [] :isError true})
    {:contents []}))

(defonce ^:private resource-subs (atom {}))  ; uri -> #{send-fn}
(defonce ^:private room-uris (atom {}))      ; room-id -> #{uri} with a live facts watcher

(defn- notify-room! [room-id]
  (doseq [u (get @room-uris room-id)
          sf (get @resource-subs u)]
    (try (sf {:jsonrpc "2.0" :method "notifications/resources/updated" :params {:uri u}})
         (catch Throwable _ nil))))

(defn- uri-room
  "The room a resource URI reads (`room://<room>/…`, `experiment://<room>/progress`, …)."
  [dmn uri]
  (when-let [{:keys [args]} (uri->op+args uri)]
    (some->> (:room args) (ops/resolve-room dmn))))

(defn- subscribe-resource! [uri send-fn]
  (swap! resource-subs update uri (fnil conj #{}) send-fn)
  ;; A resource about a room is live: the room's facts feed (every durable
  ;; change: messages, Runs, tool calls, Attempts) notifies each subscribed uri
  ;; of that room, at a bounded rate. One watcher per room.
  (when-let [dmn (current-daemon)]
    (when-let [room (uri-room dmn uri)]
      (let [rid (:id room)
            [old _] (swap-vals! room-uris update rid (fnil conj #{}) uri)]
        (when (empty? (get old rid))
          (facts/watch! room [::mcp rid] #(notify-room! rid)))))))

(defn- release-uri!
  "Forget `uri` once nobody subscribes to it; stop its room's watcher with its
   last uri."
  [uri]
  (when (empty? (get @resource-subs uri))
    (swap! resource-subs dissoc uri)
    (doseq [[rid uris] @room-uris
            :when (contains? uris uri)]
      (let [left (disj uris uri)]
        (if (empty? left)
          (do (swap! room-uris dissoc rid)
              (facts/unwatch! rid [::mcp rid]))
          (swap! room-uris assoc rid left))))))

(defn- unsubscribe-resource! [uri send-fn]
  (swap! resource-subs update uri disj send-fn)
  (release-uri! uri))

(defn- drop-subscriber! [send-fn]
  (let [uris (keys @resource-subs)]
    (swap! resource-subs (fn [m] (into {} (map (fn [[u s]] [u (disj s send-fn)])) m)))
    (run! release-uri! uris)))

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
  (swap! tool-definitions conj (merge {:dvergr/toolset :extra
                                       :annotations (surface/tool-annotations (:name tool-def))}
                                      tool-def))
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
         {:server-info {:name "dvergr" :title "dvergr" :version "0.1.0"}
          :instructions surface/instructions})))

(defn- meta-selection
  "The tool selection a client asks for in `initialize` `_meta`
   (`dvergr/profile`, `dvergr/toolsets`), over the connection's default."
  [default params]
  (let [m (:_meta params)
        profile (or (get m :dvergr/profile) (get m (keyword "dvergr/profile")))
        toolsets (or (get m :dvergr/toolsets) (get m (keyword "dvergr/toolsets")))]
    (if (or profile toolsets)
      (surface/selection {:profile (or profile (:profile default)) :toolsets toolsets})
      default)))

(defn session-context
  "The per-connection dispatch context: `send-fn` writes to the client;
   `selection` (from `surface/selection`) is the default tool selection, which
   the client may replace in `initialize` `_meta`."
  [send-fn selection]
  (let [sel (atom selection)]
    {:session (make-session)
     :send-fn send-fn
     :tool-defs tool-definitions
     :tool-handlers tool-handlers
     :tool-visible? (fn [td] (surface/visible? @sel td))
     :on-initialize (fn [params] (reset! sel (meta-selection selection params)))
     :selection sel
     :resource-defs resource-definitions
     :read-resource read-resource
     :subscribe-resource subscribe-resource!
     :unsubscribe-resource unsubscribe-resource!}))

;; ============================================================================
;; Connection handling (shared by TCP and stdio)
;; ============================================================================

(defn- handle-connection
  "Handle a single MCP connection. Reads JSON lines from reader,
   dispatches via json-rpc/handle-message, writes responses via writer.
   Blocks until reader is closed."
  [^BufferedReader reader ^BufferedWriter writer label selection]
  (let [send-fn (fn [message]
                  (locking writer
                    (.write writer (json/write-value-as-string message json-write-mapper))
                    (.write writer "\n")
                    (.flush writer)))
        context (session-context send-fn selection)]
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
        (drop-subscriber! send-fn)
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
     :bind - Bind address (default \"127.0.0.1\" — loopback only; pass
             \"0.0.0.0\" explicitly to expose the server on the network)
     :profile, :toolsets - the default tool selection (`dvergr.mcp.surface`);
             a client may choose its own in `initialize` `_meta`"
  [& {:keys [port bind profile toolsets] :or {port 17888 bind "127.0.0.1"}}]
  (when @server-state
    (throw (ex-info "Server already running" {:port port})))
  (let [selection (surface/selection {:profile profile :toolsets toolsets})
        ^ServerSocket server-socket (ServerSocket. port 50 (java.net.InetAddress/getByName bind))
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
                                                (handle-connection reader writer addr selection))
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
  "Run MCP server on stdio. Blocks until stdin closes. Tools answer only when a
   daemon runs in this process; clients normally use `bin/dvergr-mcp`, which
   relays stdio to the daemon's TCP port."
  [& {:keys [profile toolsets]}]
  (let [reader (BufferedReader. (InputStreamReader. System/in))
        writer (BufferedWriter. (OutputStreamWriter. System/out))]
    (handle-connection reader writer "stdio"
                       (surface/selection {:profile profile :toolsets toolsets}))))

;; ============================================================================
;; Entry point
;; ============================================================================

(defn -main
  "Entry point. Default: TCP on 127.0.0.1:17888. Use --stdio for subprocess mode,
   --port <n> for the TCP port, --bind <addr> to change the bind address (pass
   0.0.0.0 to expose on the network), --profile / --toolsets for the default
   tool selection."
  [& args]
  (let [args-set (set args)
        opt (fn [flag] (second (drop-while #(not= % flag) args)))]
    (cond
      (args-set "--stdio")
      (run-stdio :profile (opt "--profile") :toolsets (opt "--toolsets"))

      :else
      (let [port (if-let [p (opt "--port")]
                   (Integer/parseInt p)
                   (if-let [p (some #(when (re-matches #"\d+" %) %) args)]
                     (Integer/parseInt p)
                     17888))
            bind (or (opt "--bind") "127.0.0.1")]
        (start! :port port :bind bind :profile (opt "--profile") :toolsets (opt "--toolsets"))
        ;; Keep main thread alive
        (.join ^Thread (:accept-thread @server-state))))))
