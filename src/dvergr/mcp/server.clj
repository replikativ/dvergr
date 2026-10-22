(ns dvergr.mcp.server
  "MCP (Model Context Protocol) server for exposing dvergr capabilities.

   Transports:
   - TCP on loopback (newline-delimited JSON-RPC), started by the daemon
     (`:mcp` config or `--mcp`). Clients reach it through `bin/dvergr-mcp`, a
     stdio relay that starts and reconnects to the daemon (see doc/mcp.md).
   - stdio in this process (`run-stdio`): tools answer only when a daemon
     runs in the same JVM.

   Which tools a connection sees (profiles, toolsets), their annotations and
   the result encoding are `dvergr.mcp.surface`.

   Every connection (and every HTTP request) has a principal, from the
   daemon's authenticator (`dvergr.principal`): the selection it sees is capped
   to what the principal may see (`surface/capped`), and every op, registry
   tool, resource read and subscription is authorized for it.

   REPL workflow:
     (require '[dvergr.mcp.server :as mcp])
     (mcp/start! {:port 17888})   ; start TCP server
     ;; ... develop, modify tool handlers, they're live ...
     (mcp/stop!)                  ; stop TCP server"
  (:require [clojure.string :as str]
            [dvergr.mcp.json-rpc :as json-rpc]
            [dvergr.mcp.repl :as repl]
            [dvergr.mcp.surface :as surface]
            [dvergr.ops :as ops]
            [dvergr.principal :as principal]
            [dvergr.runtime.host-lifetime :as host-lifetime]
            [dvergr.rooms.files :as room-files]
            [dvergr.rooms.facts :as facts]
            [dvergr.chat.context :as chat-context]
            [dvergr.substrate.paths :as paths]
            [malli.json-schema :as mjs]
            [org.replikativ.spindel.engine.core :as ec]
            [jsonista.core :as json])
  (:import (java.net ServerSocket Socket)
           (java.io BufferedReader InputStreamReader BufferedWriter OutputStreamWriter)))

;; ============================================================================
;; JSON mappers
;; ============================================================================

(def ^:private json-write-mapper
  ;; keys keep their namespace: the protocol's `_meta` keys are
  ;; `io.modelcontextprotocol/…`
  (json/object-mapper {:encode-key-fn (fn [k] (if (keyword? k) (subs (str k) 1) (str k)))}))

(def ^:private json-read-mapper
  (json/object-mapper {:decode-key-fn keyword}))

;; ============================================================================
;; Tools — DERIVED from the central dvergr.ops specification
;; ============================================================================
;;
;; The MCP surface is a projection of `dvergr.ops` (rooms/agents/forks) — no
;; parallel agent registry, no unsandboxed eval. A new op added to the spec
;; appears here automatically, named identically across every binding. The
;; coding tools (the `dvergr.tools` registry, sandboxed) are served beside them
;; and kept in step with the registry (`sync-registry-tools!`).

;; Kept so the daemon can wire a db conn if it wants; ops resolves its own.
(defonce server-db-conn (atom nil))
(defn set-db-conn! [conn] (reset! server-db-conn conn))

(defonce ^:private embedded-host (atom nil))

(defn set-host!
  "Use `host` for MCP operations in an embedding application without a daemon.
   The same map `dvergr.ops/invoke` accepts: `:execution-ctx` and optional
   `:config {:auth {:authorize …}}`. Does not start a daemon or open stores.
   The host owns this process-level transport configuration, not a room or
   fork. Set before starting listeners; stop them before clearing with nil.
   Authentication remains the transport's responsibility."
  [host]
  (when (and host (not (and (map? host) (:execution-ctx host))))
    (throw (ex-info "An MCP host needs :execution-ctx" {:type ::invalid-host})))
  (reset! embedded-host host)
  nil)

(defn- current-daemon []
  (or @embedded-host
      (some-> (requiring-resolve 'dvergr.orchestration.daemon/current-daemon) deref deref)))

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

(defn- context-principal
  "The principal a connection's `context` runs for (`session-context` sets
   it); a context without one runs nothing."
  [context]
  (or (:principal context)
      (throw (ex-info "No principal on this connection" {:type ::no-principal}))))

(defn- op->tool
  "Project one dvergr.ops spec entry to an MCP tool: {:name :def :handler}. The
   handler resolves the live daemon, invokes the op (which ctx-binds + calls the
   shared fn), and returns the data as JSON. Reads and writes are both exposed
   as callable tools."
  ([op spec]
   (op->tool op spec (strict-schema (mjs/transform (:schema spec)))))
  ([op {:keys [doc kind]} input-schema]
   (let [tname (ops/op->name op)
         annotations (surface/op-annotations op kind)]
     {:name tname
      :def  {:name tname :title (:title annotations) :description doc
             :inputSchema input-schema
             :annotations annotations
             :dvergr/toolset (surface/tool-toolset tname op false)}
      :handler
      (fn [context arguments]
        (if-let [dmn (current-daemon)]
          (try
            (surface/data-result (ops/invoke dmn (context-principal context) op
                                             (cond->> (keywordize arguments)
                                               (:room (some-> context :selection deref))
                                               (surface/pinned-args @(:selection context)))))
            (catch Throwable e
              (surface/error-result (str "Error: " (.getMessage e)))))
          (surface/error-result no-daemon)))})))

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

(defn- run-coding-tool
  "Run registry tool `tname` in `room` for `principal`, authorized first (its
   action on the room: `tools/authz-request`; `clojure_eval` is `:admin`).
   `clojure_eval` runs in the REPL session of the connection's `selection`
   and principal, which gets the ops that selection shows as the `dvergr.ops`
   namespace (`dvergr.mcp.repl`)."
  [dmn principal selection room tname input]
  (let [execute      (requiring-resolve 'dvergr.tools/execute)
        make-context (requiring-resolve 'dvergr.tools/make-context)
        authz-request (requiring-resolve 'dvergr.tools/authz-request)
        ensure-ctx!  (requiring-resolve 'dvergr.agent.room-context/ensure-ctx!)
        repl?        (and selection (= "clojure_eval" tname))]
    (ops/check! dmn principal (authz-request tname nil input room))
    (binding [ec/*execution-context* (:ctx room)]
      (let [cctx (ensure-ctx! room (cond repl? (repl/session-actor selection principal)
                                         (principal/local? principal) :mcp
                                         :else (principal/actor principal)) {})
            sci-ctx (cond-> (chat-context/sci-context-in cctx (:ctx room))
                      repl? (repl/install! dmn selection room))]
        ;; the REPL's dvergr.ops functions run for this caller, whoever defined them
        (binding [repl/*caller* {:principal principal :selection selection}]
          (execute tname input (make-context {:sci-ctx  sci-ctx
                                              :db-conn  (:db-conn cctx)
                                              :chat-ctx cctx
                                              :execution-ctx (:ctx room)
                                              ;; `execute` authorizes for it again
                                              :principal principal
                                              :authz-room room})))))))

(defn- coding-tool [tname tdef]
  (let [annotations (surface/tool-annotations tname)]
    {:name tname
     :def  {:name tname :title (:title annotations)
            :description (str (:description tdef) "\n\nRuns in the given room's sandbox and workspace."
                              (when (= "clojure_eval" tname)
                                (str " The `dvergr.ops` namespace holds every op this connection may call, as"
                                     " functions taking one map: (dvergr.ops/room-list {}),"
                                     " (dvergr.ops/job-status {:job id}); (dir dvergr.ops) lists them.")))
            :inputSchema (with-room-param (:parameters tdef))
            :annotations annotations
            :dvergr/toolset (surface/tool-toolset tname nil true)
            ;; derived from the dvergr.tools registry, kept in step with it
            :dvergr/registry? true}
     :handler
     (fn [context arguments]
       (if-let [dmn (current-daemon)]
         (let [args  (surface/pinned-args (some-> context :selection deref) (keywordize arguments))
               input (dissoc args :room)
               room  (when-not (str/blank? (str (:room args)))
                       (ops/resolve-room dmn (:room args)))]
           (if-not room
             (surface/error-result
              (if (str/blank? (str (:room args)))
                (str tname " needs a room: pass `room` (id or slug; room_list lists them)")
                (str "No room " (pr-str (:room args)) "; room_list lists them")))
             (try
               (surface/tool-result (run-coding-tool dmn (context-principal context)
                                                     (some-> context :selection deref) room tname input))
               (catch Throwable e
                 (surface/error-result (str "Error: " (.getMessage e)))))))
         (surface/error-result no-daemon)))}))

;; ---- repl_describe: the REPL's API, for REPL-first clients (the `code` profile)

(def ^:private describe-form
  "Evaluated in the connection's REPL session: its dvergr namespaces and their
   functions (name, arglists, first doc line), all or those matching `q`.
   Docs sit on the var or, for injected fns, on the value."
  "(fn [q]
     (let [q (some-> q clojure.string/lower-case)
           info (fn [sym v]
                  (let [m (if (var? v) (merge (meta v) (meta (deref v))) (meta v))]
                    {:name (str sym)
                     :arglists (some-> (:arglists m) pr-str)
                     :doc (some-> (:doc m) clojure.string/split-lines first)}))
           hit? (fn [nsn f] (or (nil? q)
                                (some #(clojure.string/includes? (clojure.string/lower-case (str %)) q)
                                      [nsn (:name f) (:doc f)])))]
       (->> (all-ns)
            (map ns-name)
            (filter #(clojure.string/starts-with? (str %) \"dvergr\"))
            sort
            (keep (fn [nsn]
                    (let [fs (->> (ns-publics nsn) (sort-by key) (map (fn [[k v]] (info k v)))
                                  (filter #(hit? nsn %)) vec)]
                      (when (seq fs)
                        {:ns (str nsn)
                         :fns (if q fs (mapv :name fs))}))))
            vec)))")

(defn- repl-describe [dmn principal selection room query]
  (let [ensure-ctx! (requiring-resolve 'dvergr.agent.room-context/ensure-ctx!)
        eval-string* (requiring-resolve 'sci.core/eval-string*)
        authz-request (requiring-resolve 'dvergr.tools/authz-request)]
    (ops/check! dmn principal (authz-request "repl_describe" nil {} room))
    (binding [ec/*execution-context* (:ctx room)]
      (let [cctx (ensure-ctx! room (repl/session-actor selection principal) {})
            sci-ctx (repl/install! (chat-context/sci-context-in cctx (:ctx room)) dmn selection room)]
        ;; the call is evaluated in SCI too: all-ns needs its context
        (eval-string* sci-ctx (str "(" describe-form " " (pr-str query) ")"))))))

(def ^:private describe-tool
  (let [tname "repl_describe"
        annotations (surface/tool-annotations tname)]
    {:name tname
     :def {:name tname :title (:title annotations)
           :description (str "The API of the given room's REPL (`clojure_eval`): its dvergr namespaces and "
                             "functions, with signatures and the first line of their docs. Without `query`, the "
                             "namespaces and their function names; with it, the functions whose namespace, name "
                             "or doc matches. `dvergr.ops` holds every op this connection may call.")
           :inputSchema (strict-schema {:type "object"
                                        :properties {:room {:type "string" :description "Room id or slug"}
                                                     :query {:type "string" :description "Filter: part of a namespace, function name or doc"}}
                                        :required ["room"]})
           :annotations annotations
           :dvergr/toolset (surface/tool-toolset tname nil true)}
     :handler
     (fn [context arguments]
       (if-let [dmn (current-daemon)]
         (let [{:keys [room query]} (keywordize arguments)
               r (when-not (str/blank? (str room)) (ops/resolve-room dmn room))]
           (if-not r
             (surface/error-result (str "No room " (pr-str room) "; room_list lists them"))
             (try
               (surface/data-result (repl-describe dmn (context-principal context)
                                                   (or (some-> context :selection deref)
                                                       (surface/selection {}))
                                                   r (when-not (str/blank? (str query)) query)))
               (catch Throwable e
                 (surface/error-result (str "Error: " (.getMessage e)))))))
         (surface/error-result no-daemon)))}))

(def ^:private fixed-tools (conj ops-tools describe-tool))

(def tool-definitions
  "MCP tool defs: dvergr.ops (rooms/agents/forks) + the room-scoped coding tools
   (dvergr.tools registry, kept in step with it by `sync-registry-tools!`). An
   atom so `register-tool!` can add dynamic tools."
  (atom (mapv :def fixed-tools)))

(def tool-handlers
  "tool-name -> handler, for all derived tools."
  (atom (into {} (map (juxt :name :handler)) fixed-tools)))

(def ^:private tools-lock (Object.))

(def ^:private host-op-schemas
  ;; Transport metadata only. Retain just CURRENT host schemas/projections,
  ;; never implementations, authorizers, principals or room execution state.
  (atom {}))

(declare broadcast-notification!)

(defn- tools-registry [] (deref (requiring-resolve 'dvergr.tools/registry)))

(defn sync-registry-tools!
  "Serve every tool of the `dvergr.tools` registry as a room-scoped coding tool,
   as the registry is now: tools register as their namespaces load (mail and
   schedule tools when the daemon starts, after this namespace), so the list
   follows the registry rather than a snapshot. A name an op, `repl_describe`
   or an explicit `register-tool!` holds is left to it. Clients hear of a
   change through tools/list_changed."
  []
  (let [changed?
        (locking tools-lock
          (let [defs @tool-definitions
                held (into #{} (comp (remove :dvergr/registry?) (map :name)) defs)
                want (into (sorted-map)
                           (for [[tname tdef] @(tools-registry)
                                 :when (not (held tname))]
                             [tname (coding-tool tname tdef)]))
                old  (into {} (comp (filter :dvergr/registry?) (map (juxt :name identity))) defs)
                new  (into {} (map (fn [[n t]] [n (:def t)])) want)]
            (when (not= old new)
              (reset! tool-definitions (into (vec (remove :dvergr/registry? defs)) (vals new)))
              (swap! tool-handlers
                     (fn [hs]
                       (merge (apply dissoc hs (remove new (keys old)))
                              ;; a handler closes over nothing that changes: keep it
                              (into {} (for [[n t] want :when (not= (get old n) (get new n))]
                                         [n (:handler t)])))))
              true)))]
    (when changed?
      (broadcast-notification! "notifications/tools/list_changed" {} :toolsListChanged))))

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

(def ^:private resources-of
  (memoize (fn [reads] (mapv (fn [[op spec]] (op->resource op spec)) reads))))

(defn- ops-resources []
  ;; room_read takes a path, which its own template carries (`file-template`);
  ;; derived from `ops/operations`, so a host's registered reads are resources too
  (resources-of (dissoc (ops/reads) :room/read)))

(def ^:private file-template
  "A room's workspace files as resources: a file's contents (text, or a blob
   for binary), a directory's listing (room_files) as JSON."
  {:uriTemplate "room://{room}/files/{+path}" :name "room_file"
   :description (str "A file of a room's workspace: its contents (text, or a base64 blob for binary, "
                     "up to 4 MiB; room_read reads larger ones in chunks), or a directory's listing as JSON.")})

(defn- shown?
  "Whether `principal` may see op `op`'s resources: its toolset is within the
   principal's (`surface/principal-toolsets`), as for the tools, and a run
   token's pinned tools name it."
  [principal op]
  (let [allowed (surface/principal-toolsets principal)
        pinned (:principal/tools principal)]
    (and (or (nil? allowed) (contains? allowed (surface/tool-toolset (ops/op->name op) op false)))
         (or (nil? pinned) (contains? pinned (ops/op->name op))))))

(defn resource-definitions
  "Fixed resources + resource templates, derived from the reads of
   `ops/operations` that `principal` may see (its toolsets: `surface/capped`)."
  [principal]
  (let [rs (filter #(shown? principal (:op %)) (ops-resources))]
    {:resources (mapv :def (filter #(= :fixed (:kind %)) rs))
     :templates (cond-> (mapv :def (filter #(= :template (:kind %)) rs))
                  (shown? principal :room/read) (conj file-template))}))

(defn- fixed-by-uri []
  (into {} (for [{:keys [kind uri op]} (ops-resources) :when (= :fixed kind)] [uri op])))
(defn- template-by-key []
  (into {} (for [{:keys [kind scheme rname op arg]} (ops-resources) :when (= :template kind)]
             [[scheme rname] {:op op :arg arg}])))

(defn- uri->op+args
  "Map an MCP resource URI back to a dvergr.ops [op args], or nil."
  [uri]
  (let [[scheme rst] (str/split uri #"://" 2)
        segs (str/split (or rst "") #"/")]
    (cond
      (get (fixed-by-uri) uri) {:op (get (fixed-by-uri) uri) :args {}}
      ;; room://{room}/files/{+path}: a workspace file or directory
      (and (= "room" scheme) (< 2 (count segs)) (= "files" (second segs)))
      {:op :room/read :file? true
       :args {:room (first segs)
              :path (str "/" (str/join "/" (map #(java.net.URLDecoder/decode (str/replace % "+" "%2B") "UTF-8")
                                                (drop 2 segs))))}}
      (= 2 (count segs))     (when-let [{:keys [op arg]} (get (template-by-key) [scheme (second segs)])]
                               {:op op :args {arg (first segs)}})
      :else nil)))

(defn- file-contents
  "The contents of the workspace file resource `uri`: the file whole (text or
   blob), or a directory's listing as JSON. Authorized as `room_read`."
  [dmn principal uri {:keys [room path] :as args}]
  (host-lifetime/call dmn
                      (fn []
                        (ops/authorize! dmn principal :room/read args)
  ;; uncut: a resource is a document, and a listing is a bounded page
                        (let [json-text #(json/write-value-as-string % json-write-mapper)]
                          (try
                            (let [{:keys [kind data target-stamp]} (ops/room-file-read dmn principal :resource args)
                                  {:keys [text base64 media-type size length]} data]
                              (when (and (= :file kind) (< length size))
                                (throw (ex-info (str path " has " size " bytes, more than a resource returns ("
                                                     room-files/max-resource-bytes "); read it with room_read and offset")
                                                {:json-rpc/code -32602 :json-rpc/data {:uri uri}})))
                              [(cond-> (if (= :directory kind)
                                         {:uri uri :mimeType "application/json"
                                          :text (json-text (cond-> data target-stamp (assoc :target-stamp target-stamp)))}
                                         (cond-> {:uri uri :mimeType media-type}
                                           text (assoc :text text) base64 (assoc :blob base64)))
                                 target-stamp (assoc :_meta {:dvergr/target-stamp target-stamp}))])
                            (catch clojure.lang.ExceptionInfo e
                              (if (#{::room-files/not-found ::ops/no-room} (:type (ex-data e)))
                                (throw (ex-info (str "Resource not found: " uri) {:json-rpc/code -32002 :json-rpc/data {:uri uri}}))
                                (throw e))))))))

(defn- read-resource [principal uri]
  (if-let [{:keys [op args file?]} (when-let [m (uri->op+args uri)]
                                     ;; outside the principal's toolsets: not there
                                     (when (shown? principal (:op m)) m))]
    (if-let [dmn (current-daemon)]
      {:contents (if file?
                   (file-contents dmn principal uri args)
                   [{:uri uri :mimeType "application/json"
                     :text (-> (surface/data-result (ops/invoke dmn principal op args)) :content first :text)}])}
      {:contents []})
    ;; the protocol's error for a resource that does not exist (the
    ;; stateless era answers it as -32602, `json-rpc/handle-stateless`)
    (throw (ex-info (str "Resource not found: " uri)
                    {:json-rpc/code -32002 :json-rpc/data {:uri uri}}))))

(defonce ^:private resource-subs (atom {}))  ; uri -> {send-fn principal}
(defonce ^:private room-uris (atom {}))      ; room-id -> #{uri} with a live facts watcher

(declare unsubscribe-resource!)

(defn- may-read-uri?
  "Whether `principal` may read the resource `uri` now (the op it maps to,
   authorized with its args). Anything that throws on the way denies."
  [dmn principal uri]
  (try (when-let [{:keys [op args]} (uri->op+args uri)]
         (when (shown? principal op)
           (ops/authorize! dmn principal op args)
           true))
       (catch Exception _ false)))

(defn- notify-room!
  "Tell every subscriber of a uri about room `room-id` that it changed, each
   authorized again first: a subscriber whose access was revoked is dropped,
   not notified."
  [room-id]
  (let [dmn (current-daemon)]
    (doseq [u (get @room-uris room-id)
            [sf p] (get @resource-subs u)]
      (if (may-read-uri? dmn p u)
        (try (sf {:jsonrpc "2.0" :method "notifications/resources/updated" :params {:uri u}})
             (catch Throwable _ nil))
        (unsubscribe-resource! u sf)))))

(defn- uri-room
  "The room a resource URI reads (`room://<room>/…`, `experiment://<room>/progress`, …)."
  [dmn uri]
  (when-let [{:keys [args]} (uri->op+args uri)]
    (some->> (:room args) (ops/resolve-room dmn))))

(defn- subscribe-resource!
  "Subscribe `send-fn` to `uri` for `principal`, who must be allowed to read
   it (else an error, -32002 as for a resource that is not there)."
  [principal uri send-fn]
  (let [dmn (current-daemon)
        m (uri->op+args uri)]
    ;; authorized with or without a daemon: without one, only the local principal reads
    (when-not (and m (shown? principal (:op m)) (may-read-uri? dmn principal uri))
      (throw (ex-info (str "Resource not found or not readable: " uri)
                      {:json-rpc/code -32002 :json-rpc/data {:uri uri}}))))
  (swap! resource-subs update uri (fnil assoc {}) send-fn principal)
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
  (swap! resource-subs update uri dissoc send-fn)
  (release-uri! uri))

(defn- drop-subscriber! [send-fn]
  (let [uris (keys @resource-subs)]
    (swap! resource-subs (fn [m] (into {} (map (fn [[u s]] [u (dissoc s send-fn)])) m)))
    (run! release-uri! uris)))

;; ============================================================================
;; Connected clients (for server-initiated notifications)
;; ============================================================================

;; send-fn -> session atom of every open connection
(defonce ^:private connected-send-fns (atom {}))

;; [send-fn subscription-id] -> {:tag fn :accepted filter} — the open
;; subscriptions/listen requests (stateless era)
(defonce ^:private subscriptions (atom {}))

(def ^:private subscription-id-key :io.modelcontextprotocol/subscriptionId)

(defn- tagged
  "`send-fn` putting subscription `id` on every message's `_meta`."
  [send-fn id]
  (fn [message] (send-fn (assoc-in message [:params :_meta subscription-id-key] id))))

(defn- safe-send! [send-fn message]
  (try (send-fn message)
       (catch Exception e
         (binding [*err* *err*]
           (.println *err* (str "dvergr-mcp: notification error: " (.getMessage e)))
           (.flush *err*)))))

(defn- broadcast-notification!
  "Send a list-changed notification: untagged to every connection that did
   the handshake (it opted in with `initialize`), tagged to every
   subscription that asked for `filter-key`. A stateless client that did
   not subscribe gets nothing, as the protocol asks."
  [method params filter-key]
  (let [notification {:jsonrpc "2.0" :method method :params params}]
    (doseq [[send-fn session] @connected-send-fns
            :when (:initialized @session)]
      (safe-send! send-fn notification))
    (doseq [[_ {:keys [tag accepted]}] @subscriptions
            :when (get accepted filter-key)]
      (safe-send! tag notification))))

(defn- listen!
  "Open subscription `id` for the connection writing through `send-fn`, for
   `principal`: acknowledge (first, with the filter this server honours: tools
   list changes and the existing resource URIs the principal may read; it has
   no prompts and does not announce resource list changes), then deliver."
  [principal send-fn {:keys [id params]}]
  (let [{:keys [toolsListChanged resourceSubscriptions]} (:notifications params)
        dmn (current-daemon)
        uris (vec (filter #(and (uri->op+args %) (may-read-uri? dmn principal %))
                          resourceSubscriptions))
        accepted (cond-> {}
                   (true? toolsListChanged) (assoc :toolsListChanged true)
                   (seq uris) (assoc :resourceSubscriptions uris))
        tag (tagged send-fn id)]
    (tag {:jsonrpc "2.0" :method "notifications/subscriptions/acknowledged"
          :params {:notifications accepted}})
    (swap! subscriptions assoc [send-fn id] {:tag tag :accepted accepted})
    (doseq [u uris] (subscribe-resource! principal u tag))))

(defn- end-subscription!
  "Close subscription `id` of `send-fn`; with `respond?`, send the graceful
   closing response first (the server ends it, not the client)."
  [send-fn id respond?]
  (when-let [{:keys [tag accepted]} (get @subscriptions [send-fn id])]
    (swap! subscriptions dissoc [send-fn id])
    (doseq [u (:resourceSubscriptions accepted)] (unsubscribe-resource! u tag))
    (when respond?
      (safe-send! send-fn {:jsonrpc "2.0" :id id
                           :result {:resultType "complete" :_meta {subscription-id-key id}}}))))

(defn close-subscriptions!
  "End every open subscription gracefully (server shutdown)."
  []
  (doseq [[send-fn id] (keys @subscriptions)] (end-subscription! send-fn id true)))

;; ============================================================================
;; Dynamic tool registration
;; ============================================================================

(defn register-tool!
  "Register a new tool dynamically. Broadcasts tools/list_changed to all clients.
   tool-def: {:name \"...\" :description \"...\" :inputSchema {...}}
   handler-fn: (fn [context arguments] -> {:content [...] :isError bool})"
  [tool-def handler-fn]
  (locking tools-lock
    ;; replaces a tool of the same name (one derived from the registry, say)
    (swap! tool-definitions (fn [defs]
                              (conj (vec (remove #(= (:name %) (:name tool-def)) defs))
                                    (merge {:dvergr/toolset :extra
                                            :annotations (surface/tool-annotations (:name tool-def))}
                                           (dissoc tool-def :dvergr/registry?)))))
    (swap! tool-handlers assoc (:name tool-def) handler-fn))
  (broadcast-notification! "notifications/tools/list_changed" {} :toolsListChanged))

(defn unregister-tool!
  "Remove a tool by name. Broadcasts tools/list_changed to all clients. A
   registry tool of that name is served again as a coding tool."
  [tool-name]
  (locking tools-lock
    (swap! tool-definitions (fn [defs] (vec (remove #(= (:name %) tool-name) defs))))
    (swap! tool-handlers dissoc tool-name))
  (broadcast-notification! "notifications/tools/list_changed" {} :toolsListChanged)
  (sync-registry-tools!))

(defn sync-op-tools!
  "Serve the ops a host registered (`ops/register-op!`) as tools beside
   dvergr's own, as `ops/operations` is now (toolset `:host`). Clients hear of
   a change through tools/list_changed."
  []
  (let [changed?
        (locking tools-lock
          (let [defs @tool-definitions
                ;; One captured registry supplies schema, docs and kind. Looking
                ;; up an op again while projecting could mix two registrations.
                snapshot (into (sorted-map)
                               (remove (fn [[op _]] (contains? ops/specification op)))
                               (ops/operations))
                prior-schemas @host-op-schemas
                schemas (into {}
                              (for [[op spec] snapshot
                                    :let [source (:schema spec)
                                          prior (get prior-schemas op)]]
                                [op (if (and prior (= source (:source prior))) prior
                                        {:source source :json (strict-schema (mjs/transform source))})]))
                want (into (sorted-map)
                           (for [[op spec] snapshot]
                             (let [t (op->tool op spec (:json (get schemas op)))] [(:name t) t])))
                old (into {} (comp (filter :dvergr/host-op?) (map (juxt :name identity))) defs)
                new (into (sorted-map) (map (fn [[n t]] [n (assoc (:def t) :dvergr/host-op? true)])) want)]
            ;; Successful projection evicts removed schemas; failed projection
            ;; leaves the old published surface and its cache intact.
            (reset! host-op-schemas schemas)
            (when (not= old new)
              (reset! tool-definitions (into (vec (remove :dvergr/host-op? defs)) (vals new)))
              (swap! tool-handlers #(merge (apply dissoc % (keys old))
                                           (into {} (map (fn [[n t]] [n (:handler t)])) want)))
              true)))]
    (when changed?
      (broadcast-notification! "notifications/tools/list_changed" {} :toolsListChanged))))

(sync-op-tools!)
(ops/watch-operations! ::op-tools sync-op-tools!)

;; The registry as it is at load, then every change to it.
(sync-registry-tools!)
(add-watch (tools-registry) ::registry-tools
           (fn [_ _ old new] (when-not (identical? old new) (sync-registry-tools!))))

;; ============================================================================
;; Session factory
;; ============================================================================

(defn make-session
  "Create a fresh MCP session atom. Each connection gets its own session."
  []
  (atom (json-rpc/create-session
         {:server-info {:name "dvergr" :title "dvergr" :version "0.1.0"}
          :instructions surface/instructions
          :home (paths/home)})))

(defn- meta-selection
  "The tool selection a client asks for in `_meta` (`dvergr/profile`,
   `dvergr/toolsets`, `dvergr/room`, `dvergr/tools`) of `initialize` or of a
   stateless request, over the connection's default."
  [default params]
  (let [m (:_meta params)
        profile (or (get m :dvergr/profile) (get m (keyword "dvergr/profile")))
        toolsets (or (get m :dvergr/toolsets) (get m (keyword "dvergr/toolsets")))
        room (or (get m :dvergr/room) (get m (keyword "dvergr/room")))
        tools (or (get m :dvergr/tools) (get m (keyword "dvergr/tools")))]
    (if (or profile toolsets room tools)
      (surface/selection {:profile (or profile (:profile default)) :toolsets toolsets
                          :room room :tools tools})
      default)))

(defn session-context
  "The per-connection dispatch context for `principal` (authenticated by the
   transport): `send-fn` writes to the client; `selection` (from
   `surface/selection`) is the default tool selection, which the client may
   replace in `initialize` `_meta` (handshake era) or name in each request's
   `_meta` (stateless era, `:request-context`). Whatever it asks for is capped
   to what the principal may see (`surface/capped`): a client narrows its
   selection, never widens it. Every call, read and subscription runs for the
   principal."
  [send-fn selection principal]
  (when-not (and (map? principal) (some? (:principal/id principal)))
    (throw (ex-info "A connection needs a principal with an id" {:type ::no-principal})))
  (let [cap #(surface/capped principal %)
        scoped (fn [sel]
                 {:tool-visible? (fn [td] (surface/visible? @sel td))
                  :tool-view (fn [td] (surface/pinned-view @sel td))
                  :selection sel})
        sel (atom (cap selection))
        context (merge {:session (make-session)
                        :principal principal
                        :send-fn send-fn
                        :tool-defs tool-definitions
                        :tool-handlers tool-handlers
                        :on-initialize (fn [params] (reset! sel (cap (meta-selection selection params))))
                        :resource-defs (delay (resource-definitions principal))
                        :read-resource #(read-resource principal %)
                        :subscribe-resource #(subscribe-resource! principal %1 %2)
                        :unsubscribe-resource unsubscribe-resource!
                        :listen (fn [message] (listen! principal send-fn message))
                        :on-cancel (fn [id] (end-subscription! send-fn id false))}
                       (scoped sel))]
    ;; a stateless request brings its own selection in `_meta`, over the
    ;; connection's default; it does not change the connection's
    (assoc context :request-context
           (fn [params] (merge context (scoped (atom (cap (meta-selection selection params)))))))))

;; ============================================================================
;; Connection handling (shared by TCP and stdio)
;; ============================================================================

(def ^:private concurrent-methods
  "Requests that may take long (a tool, a resource read: a job wait, an
   evaluation). Each runs on its own virtual thread, so a connection keeps
   answering while one waits; everything else (the handshake, lists,
   notifications) runs in order on the read loop."
  #{"tools/call" "resources/read"})

(def ^:dynamic *progress-interval-ms*
  "How often a long request with a `progressToken` reports that it is still
   running."
  10000)

(defn- progress-reporter
  "For a request carrying `token`: `{:progress! (fn [progress & [total
   message]]) :done! (fn [])}`. Progress only increases and stops at
   `done!` (called before the response is sent), as the protocol asks; a
   heartbeat reports the elapsed seconds every `interval-ms` until then."
  [send-fn token interval-ms cancelled?]
  (let [state (atom {:done false :last 0})
        lock (Object.)
        progress! (fn [progress & [total message]]
                    (locking lock
                      (let [{:keys [done last]} @state]
                        (when (and (not done) (not (cancelled?)) (> progress last))
                          (swap! state assoc :last progress)
                          (safe-send! send-fn {:jsonrpc "2.0" :method "notifications/progress"
                                               :params (cond-> {:progressToken token :progress progress}
                                                         total (assoc :total total)
                                                         message (assoc :message message))})))))
        started (System/currentTimeMillis)]
    (Thread/startVirtualThread
     (fn []
       (loop []
         (Thread/sleep (long interval-ms))
         (when-not (:done @state)
           (let [secs (quot (- (System/currentTimeMillis) started) 1000)]
             ;; whole seconds; a heartbeat faster than a second counts ticks
             (progress! (max (inc (:last @state)) secs) nil (str "running for " secs " s")))
           (recur)))))
    {:progress! progress!
     :done! #(locking lock (swap! state assoc :done true))}))

(defn dispatch!
  "Handle one parsed `message` on a connection: answer it through `send-fn`,
   concurrently for `concurrent-methods`. A request cancelled
   (`notifications/cancelled`) before it finishes gets no response, as the
   protocol asks; its work is not interrupted. A concurrent request with a
   `progressToken` in `_meta` reports progress while it runs (a heartbeat,
   and whatever its handler reports through `:progress!`)."
  [context send-fn message]
  (let [id (:id message)
        cancelled? #(contains? (:cancelled-requests @(:session context)) id)
        forget! #(swap! (:session context) update :cancelled-requests disj id)
        concurrent? (and id (concurrent-methods (:method message)))
        token (when concurrent? (get-in message [:params :_meta :progressToken]))
        reporter (when (some? token)
                   (progress-reporter send-fn token *progress-interval-ms* cancelled?))
        context (cond-> context reporter (assoc :progress! (:progress! reporter)))
        answer! (fn []
                  (let [response (try (json-rpc/handle-message context message)
                                      (finally (some-> reporter :done! (apply []))))]
                    (if (and response id (cancelled?))
                      (forget!)
                      (when response (send-fn response)))))]
    (if concurrent?
      (Thread/startVirtualThread
       (fn []
         (try (answer!)
              (catch Throwable t
                (binding [*err* *err*]
                  (.println *err* (str "dvergr-mcp: request " id " failed: " (.getMessage t))))))))
      (answer!))))

(defn- handle-connection
  "Handle a single MCP connection. Reads JSON lines from reader,
   dispatches via json-rpc/handle-message, writes responses via writer.
   Blocks until reader is closed."
  [^BufferedReader reader ^BufferedWriter writer label selection principal]
  (let [send-fn (fn [message]
                  (locking writer
                    (.write writer (json/write-value-as-string message json-write-mapper))
                    (.write writer "\n")
                    (.flush writer)))
        context (session-context send-fn selection principal)]
    (swap! connected-send-fns assoc send-fn (:session context))
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
              (dispatch! context send-fn message))
            (recur))))
      (finally
        (swap! connected-send-fns dissoc send-fn)
        ;; the transport closed: its subscriptions end without a response
        (doseq [[sf id] (keys @subscriptions) :when (= sf send-fn)] (end-subscription! sf id false))
        (drop-subscriber! send-fn)
        (binding [*err* *err*]
          (.println *err* (str "dvergr-mcp: connection closed (" label ")"))
          (.flush *err*))))))

;; ============================================================================
;; TCP transport
;; ============================================================================

(defn tcp-auth-line
  "The first line a TCP client sends: its credential `token`."
  [token]
  (json/write-value-as-string {:jsonrpc "2.0" :method "dvergr/authenticate" :params {:token token}}))

(defonce ^:private server-state (atom nil))
(defonce connections (atom #{}))

(def ^:private max-auth-line
  "Longest first line (the credential) a connection may send."
  8192)

(def ^:private auth-timeout-ms
  "How long a connection has to send its credential."
  10000)

(def ^:private max-connections
  "Connections open at once, authenticated or not; more are closed at once."
  256)

(def ^:private max-unauthenticated
  "Connections that have not sent their credential yet; more are closed."
  32)

(defonce ^:private open-count (atom 0))
(defonce ^:private pending-count (atom 0))

(defn- take-slot!
  "Count one more in `counter` if it is below `limit`, atomically: true when it was."
  [counter limit]
  (let [[old new] (swap-vals! counter #(if (< % limit) (inc %) %))]
    (not= old new)))

(defn- read-auth-line
  "The first line of `reader`, at most `max-auth-line` chars, or nil."
  [^BufferedReader reader]
  (let [sb (StringBuilder.)]
    (loop []
      (let [c (.read reader)]
        (cond
          (or (neg? c) (= c (int \newline))) (when (pos? (.length sb)) (str sb))
          (>= (.length sb) max-auth-line) nil
          :else (do (.append sb (char c)) (recur)))))))

(defn- stream-principal
  "The principal of a connection from its first line (`tcp-auth-line`), and
   the token it presented; nil for a missing, oversized, unreadable or
   refused credential."
  [authenticate ^BufferedReader reader transport remote-addr]
  (let [msg (try (json/read-value (str (read-auth-line reader)) json-read-mapper) (catch Exception _ nil))
        token (some-> msg :params :token str)]
    (when (= "dvergr/authenticate" (:method msg))
      (when-let [p (try (authenticate {:transport transport :bearer token :remote-addr remote-addr})
                        (catch Exception _ nil))]
        [p token]))))

(defn- serve-stream!
  "Serve one connection (a TCP socket or a Unix domain socket channel,
   `closeable`, with its streams): its credential first, within
   `auth-timeout-ms`, then MCP for its principal. A connection authenticated
   by a run token closes when that token ends."
  [^java.io.Closeable closeable in out label selection authenticate transport remote-addr]
  (let [reader (BufferedReader. (InputStreamReader. in))
        writer (BufferedWriter. (OutputStreamWriter. out))
        authed (promise)
        _ (when-not (take-slot! pending-count max-unauthenticated)
            (throw (ex-info "too many connections awaiting their credential" {})))
        release! (let [done (atom false)] #(when (compare-and-set! done false true) (swap! pending-count dec)))]
    ;; no credential in time: closed (a socket timeout does not reach a channel)
    (Thread/startVirtualThread
     #(when-not (deref authed auth-timeout-ms nil) (try (.close closeable) (catch Exception _ nil))))
    (if-let [[p token] (try (stream-principal authenticate reader transport remote-addr)
                            (finally (release!)))]
      (do (deliver authed true)
          (when (principal/run-token? token)
            (principal/on-revoke! token #(try (.close closeable) (catch Exception _ nil))))
          (handle-connection reader writer label selection p))
      (do (deliver authed true)
          ;; refused: one error, then the connection closes
          (doto writer
            (.write (json/write-value-as-string
                     {:jsonrpc "2.0" :id nil
                      :error {:code -32001
                              :message (str "Not authenticated"
                                            (some->> @principal/rotation-notice (str ": ")))}}))
            (.write "\n")
            (.flush))))))

(defn- serve-in-thread!
  "Serve `closeable` on a thread of its own, unless `max-connections` are open."
  [^java.io.Closeable closeable label f]
  (if-not (take-slot! open-count max-connections)
    (try (.close closeable) (catch Exception _ nil))
    (do (swap! connections conj closeable)
        (doto (Thread.
               (fn []
                 (try (f)
                      (catch Exception e
                        (binding [*err* *err*]
                          (.println *err* (str "dvergr-mcp: error on " label ": " (.getMessage e)))
                          (.flush *err*)))
                      (finally
                        (swap! connections disj closeable)
                        (swap! open-count dec)
                        (try (.close closeable) (catch Exception _ nil))))))
          (.setDaemon true)
          (.start)))))

(defn- nofollow [] (into-array java.nio.file.LinkOption [java.nio.file.LinkOption/NOFOLLOW_LINKS]))

(defn- uid
  "This process's numeric user id (the owner of a file it creates)."
  []
  (let [f (java.io.File/createTempFile "dvergr-uid" nil)]
    (try (java.nio.file.Files/getAttribute (.toPath f) "unix:uid" (nofollow))
         (finally (.delete f)))))

(defn private-dir?
  "Whether `dir` is a directory, not a symbolic link, owned by this user
   (by uid) and closed to everyone else (mode 0700)."
  [dir]
  (let [p (.toPath (java.io.File. (str dir)))]
    (and (java.nio.file.Files/isDirectory p (nofollow))
         (not (java.nio.file.Files/isSymbolicLink p))
         (= (uid) (java.nio.file.Files/getAttribute p "unix:uid" (nofollow)))
         (= "rwx------" (java.nio.file.attribute.PosixFilePermissions/toString
                         (java.nio.file.Files/getPosixFilePermissions p (nofollow)))))))

(defn socket-dir
  "Where the socket of a home whose own path is too long for one lives: the
   user's runtime directory (`$XDG_RUNTIME_DIR`, else `/run/user/<uid>`)
   under `dvergr/`, else `/tmp/dvergr-<uid>`."
  []
  (let [runtime (some #(when (and % (private-dir? %)) %)
                      [(System/getenv "XDG_RUNTIME_DIR") (str "/run/user/" (uid))])]
    (if runtime (str runtime "/dvergr") (str "/tmp/dvergr-" (uid)))))

(defn socket-path
  "The Unix domain socket of the MCP server of state root `home`:
   `<home>/mcp.sock` (the home is 0700, `principal/private-dir!`), or, when
   that path is too long for a socket, `<socket-dir>/<hash of home>.sock`."
  [home]
  (let [home (.getCanonicalPath (java.io.File. (str home)))
        p (str home "/mcp.sock")]
    (if (<= (count (.getBytes p "UTF-8")) 100)
      p
      (let [h (.formatHex (java.util.HexFormat/of)
                          (.digest (java.security.MessageDigest/getInstance "SHA-256") (.getBytes home "UTF-8")))]
        (str (socket-dir) "/" (subs h 0 16) ".sock")))))

(defn- open-socket-channel
  "Bind a Unix domain socket for state root `home` (`socket-path`), in a
   directory only this user may enter: created 0700 when missing, else
   checked (not followed when a symbolic link, owned by this uid, 0700);
   nil (logged) when that cannot be assured."
  [home]
  (try
    (let [p (socket-path home)
          dir (.getParentFile (java.io.File. ^String p))
          dpath (.toPath dir)]
      (when-not (java.nio.file.Files/exists dpath (nofollow))
        (java.nio.file.Files/createDirectory
         dpath (into-array java.nio.file.attribute.FileAttribute
                           [(java.nio.file.attribute.PosixFilePermissions/asFileAttribute
                             (java.nio.file.attribute.PosixFilePermissions/fromString "rwx------"))])))
      ;; this user's own directory, not a link: closed to others (0700)
      (when (and (java.nio.file.Files/isDirectory dpath (nofollow))
                 (not (java.nio.file.Files/isSymbolicLink dpath))
                 (= (uid) (java.nio.file.Files/getAttribute dpath "unix:uid" (nofollow))))
        (java.nio.file.Files/setPosixFilePermissions
         dpath (java.nio.file.attribute.PosixFilePermissions/fromString "rwx------")))
      (when-not (private-dir? dir)
        (throw (ex-info (str dir " is not this user's private directory (0700, not a link)") {:dir (str dir)})))
      (java.nio.file.Files/deleteIfExists (.toPath (java.io.File. ^String p)))
      (doto (java.nio.channels.ServerSocketChannel/open java.net.StandardProtocolFamily/UNIX)
        (.bind (java.net.UnixDomainSocketAddress/of ^String p))))
    (catch Exception e
      (binding [*err* *err*]
        (.println *err* (str "dvergr-mcp: no Unix domain socket for " home ": " (.getMessage e)))
        (.flush *err*))
      nil)))

(defn start!
  "Start the MCP server: on a Unix domain socket for the state root
   (`socket-path`, what `bin/dvergr-mcp` uses: only this user can reach it,
   so the relay never hands its credential to a server squatting a port),
   and on TCP when `:tcp?` (off by default: a TCP client hands its
   credential to whatever holds the port). Returns the server state.
   Options:
     :tcp? - also listen on TCP (default false)
     :port - TCP port (default 17888)
     :bind - Bind address (default \"127.0.0.1\"; pass \"0.0.0.0\" explicitly to
             expose the server on the network)
     :home - the state root of the socket (default `dvergr.substrate.paths/home`)
     :profile, :toolsets - the default tool selection (`dvergr.mcp.surface`);
             a client may choose its own in `initialize` `_meta`
     :authenticate - (fn [request] -> principal | nil) for each connection
             (`dvergr.principal`; default `principal/default-authenticate`).
             A connection's first line is its credential,
             `{\"jsonrpc\":\"2.0\",\"method\":\"dvergr/authenticate\",\"params\":{\"token\":\"…\"}}`
             (`tcp-auth-line`, at most 8 KiB, within 10 s): the local token,
             or a run token. Without it the connection gets one error
             (-32001) and is closed."
  [& {:keys [tcp? port bind home profile toolsets authenticate] :or {port 17888 bind "127.0.0.1"}}]
  (when @server-state
    (throw (ex-info "Server already running" {:port port})))
  (let [selection (surface/selection {:profile profile :toolsets toolsets})
        authenticate (or authenticate principal/default-authenticate)
        home (or home (paths/home))
        ^ServerSocket server-socket (when tcp? (ServerSocket. port 50 (java.net.InetAddress/getByName bind)))
        ^java.nio.channels.ServerSocketChannel uds (open-socket-channel home)
        _ (when-not (or server-socket uds)
            (throw (ex-info "MCP: neither a Unix domain socket nor TCP (:tcp)" {:home (str home)})))
        accept-thread (when server-socket
                        (doto (Thread.
                               (fn []
                                 (loop []
                                   (when-not (.isClosed server-socket)
                                     (when-let [^Socket client (try (.accept server-socket) (catch Exception _e nil))]
                                       (let [addr (str (.getInetAddress client) ":" (.getPort client))]
                                         (serve-in-thread!
                                          client addr
                                          #(serve-stream! client (.getInputStream client) (.getOutputStream client)
                                                          addr selection authenticate :mcp-tcp
                                                          (.getHostAddress (.getInetAddress client))))))
                                     (recur))))
                               "dvergr-mcp-accept")
                          (.setDaemon true)))
        uds-thread (when uds
                     (doto (Thread.
                            (fn []
                              (loop []
                                (when (.isOpen uds)
                                  (when-let [^java.nio.channels.SocketChannel ch (try (.accept uds) (catch Exception _ nil))]
                                    (serve-in-thread!
                                     ch "unix"
                                     #(serve-stream! ch (java.nio.channels.Channels/newInputStream ch)
                                                     (java.nio.channels.Channels/newOutputStream ch)
                                                     "unix" selection authenticate :mcp-tcp nil)))
                                  (recur))))
                            "dvergr-mcp-accept-unix")
                       (.setDaemon true)))
        tcp-port (some-> server-socket .getLocalPort)]
    (some-> ^Thread accept-thread .start)
    (some-> ^Thread uds-thread .start)
    (reset! server-state {:server-socket server-socket
                          :uds uds
                          :socket-path (when uds (socket-path home))
                          :accept-thread accept-thread
                          :port tcp-port})
    (.println *err* (str "dvergr-mcp: listening on "
                         (str/join " and " (remove nil? [(when uds (socket-path home))
                                                         (when tcp-port (str bind ":" tcp-port))]))))
    (.flush *err*)
    {:port tcp-port :bind bind :socket-path (when uds (socket-path home))}))

(defn ensure-listening!
  "This process's MCP server, started (its Unix domain socket only) when none
   runs: an external agent (`claude -p` as a benchmark candidate) reaches a
   room through it, over the relay. Returns the server state."
  []
  (locking server-state
    (or @server-state
        (do (start! :authenticate (principal/authenticator (:config (current-daemon))))
            @server-state))))

(defn stop!
  "Stop the MCP server (TCP and the Unix domain socket)."
  []
  (when-let [{:keys [^ServerSocket server-socket uds socket-path]} @server-state]
    ;; subscriptions end gracefully: a response before the stream closes
    (close-subscriptions!)
    (some-> server-socket .close)
    (when uds
      (try (.close ^java.io.Closeable uds) (catch Exception _ nil))
      (try (java.nio.file.Files/deleteIfExists (.toPath (java.io.File. ^String socket-path))) (catch Exception _ nil)))
    (doseq [^java.io.Closeable conn @connections]
      (try (.close conn) (catch Exception _)))
    (reset! connections #{})
    (reset! server-state nil)
    (.println *err* "dvergr-mcp: server stopped")
    (.flush *err*)
    :stopped))

(defn status
  "Return server status."
  []
  (if-let [{:keys [port]} @server-state]
    {:running true :port port :connections (count @connections)}
    {:running false}))

;; ============================================================================
;; Stdio transport (in this process)
;; ============================================================================

(defn run-stdio
  "Run MCP server on stdio. Blocks until stdin closes. Tools answer only when a
   daemon runs in this process; clients normally use `bin/dvergr-mcp`, which
   relays stdio to the daemon's TCP port."
  [& {:keys [profile toolsets authenticate]}]
  (let [reader (BufferedReader. (InputStreamReader. System/in))
        writer (BufferedWriter. (OutputStreamWriter. System/out))
        p ((or authenticate principal/default-authenticate) {:transport :mcp-stdio})]
    (when-not p
      (throw (ex-info "stdio MCP: not authenticated" {:type ::not-authenticated})))
    (handle-connection reader writer "stdio"
                       (surface/selection {:profile profile :toolsets toolsets}) p)))
