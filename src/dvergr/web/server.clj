(ns dvergr.web.server
  "HTTP server for dvergr — dashboard, API endpoints, and agent-mounted UIs.

   Uses http-kit with manual prefix routing (no Ring/reitit/compojure deps).
   Agent handlers are mounted at /agents/{id}/ with automatic prefix stripping
   so agents write standard Ring handlers seeing '/' as root.

   Lifecycle follows daemon pattern:
     (start! daemon :port 17880)   ; binds 127.0.0.1 by default
     (stop!)

   API endpoints return JSON by default, HTML fragments when Accept: text/html."
  (:require [org.httpkit.server :as http]
            [jsonista.core :as json]
            [clojure.string :as str]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.yggdrasil :as ygg]
            [dvergr.actors :as actors]
            [dvergr.agent.ops :as ops]
            [dvergr.web.dashboard :as web-dashboard]
            [dvergr.web.agents :as web-agents]
            [dvergr.web.ops :as web-ops]
            [dvergr.web.api :as web-api]))

;; ============================================================================
;; State
;; ============================================================================

(defonce server-state (atom nil))         ; {:stop-fn fn :port int :daemon Daemon}

(def ^:private json-mapper
  (json/object-mapper {:encode-key-fn name
                       :decode-key-fn keyword}))

;; ============================================================================
;; Response Helpers
;; ============================================================================

(defn- json-response [status body]
  {:status status
   :headers {"Content-Type" "application/json"
             "Access-Control-Allow-Origin" "*"}
   :body (json/write-value-as-string body json-mapper)})

(defn- html-response [status body]
  {:status status
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body body})

(defn- wants-html? [req]
  (some-> (get-in req [:headers "accept"])
          (str/includes? "text/html")))

(defn- parse-form-params
  "Parse an application/x-www-form-urlencoded request body into a string-keyed
   map. Handles `+` → space and percent-decoding."
  [req]
  (let [body (slurp (:body req))]
    (into {}
          (for [pair (str/split body #"&")
                :let [[k v] (str/split pair #"=" 2)]
                :when (and k (not (str/blank? k)))]
            [k (java.net.URLDecoder/decode
                (str/replace (or v "") #"\+" " ") "UTF-8")]))))

;; ============================================================================
;; System API Routes
;; ============================================================================

(defn- api-health [_req daemon]
  (let [status (when daemon @(:status daemon))]
    (json-response 200
                   {:status (or status :unknown)
                    :agents (count (actors/online-ids))
                    :uptime-ms (- (System/currentTimeMillis)
                                  (or (:started-at @server-state) (System/currentTimeMillis)))})))

(defn- api-agents [req _daemon]
  (if (wants-html? req)
    ;; The roster fragment (shared card renderer) rendered into the dashboard's
    ;; #agents section — same surface, deduplicated with the config page.
    (html-response 200 (web-agents/agents-fragment))
    (json-response 200
                   {:agents (mapv (fn [a]
                                    {:id      (name (:id a))
                                     :status  (some-> (:status a) name)
                                     :model   (:model a)
                                     :online  (boolean (:online? a))})
                                  (ops/list-agents))})))

(defn- api-agent-inbox [_req daemon agent-id-str]
  (let [agent-id (keyword agent-id-str)]
    (if (actors/online? agent-id)
      (do
        ;; Send a simple text message to the agent's inbox
        ;; Body parsing would need to read the request body
        (json-response 202 {:status "dispatched" :agent-id agent-id-str}))
      (json-response 404 {:error (str "Agent not found: " agent-id-str)}))))

(defn- api-schedules [req daemon]
  ;; Lazy-load scheduler to avoid circular deps. RF5: schedules are per-room, so
  ;; this is a fan-out aggregation across every room's store.
  (try
    (require 'dvergr.scheduler.core)
    (require 'org.replikativ.spindel.engine.core)
    (let [list-all (ns-resolve (find-ns 'dvergr.scheduler.core) 'list-all-schedules)
          ec-var   (ns-resolve (find-ns 'org.replikativ.spindel.engine.core)
                               '*execution-context*)
          schedules (with-bindings (if-let [ctx (:execution-ctx daemon)]
                                     {ec-var ctx} {})
                      (list-all))]
      (if (wants-html? req)
        (html-response 200
                       (str "<div>"
                            (if (seq schedules)
                              (str/join "\n"
                                        (map (fn [s]
                                               (str "<div class=\"schedule-card\">"
                                                    "<strong>" (or (:description s) (:task s)) "</strong>"
                                                    " — " (name (or (:agent-id s) "?"))
                                                    " in #" (:room s)
                                                    (when (:next-fire s)
                                                      (str " (next: " (:next-fire s) ")"))
                                                    "</div>"))
                                             schedules))
                              "<p>No schedules active.</p>")
                            "</div>"))
        (json-response 200 {:schedules schedules})))
    (catch Exception _
      (json-response 200 {:schedules []}))))

;; ============================================================================
;; Agent API dispatch (POST /api/agents/:id/inbox)
;; ============================================================================

(defn- handle-agent-api
  "Route /api/agents/:id/... requests."
  [req daemon]
  (let [uri (:uri req)
        [_ agent-id-str action] (re-matches #"/api/agents/([^/]+)(/.*)" uri)]
    (cond
      (and agent-id-str (= action "/inbox") (= :post (:request-method req)))
      (api-agent-inbox req daemon agent-id-str)

      agent-id-str
      (json-response 404 {:error (str "Unknown agent API action: " action)})

      :else
      (json-response 404 {:error "Invalid agent API path"}))))

;; ============================================================================
;; Root Handler
;; ============================================================================

(defn- root-handler [daemon req]
  ;; CRITICAL: http-kit's worker threads have no `*execution-context*`
  ;; bound, so anything that reads from the engine state (registry,
  ;; signals, peer-bus) returns empty/nil. Bind the daemon's ctx for
  ;; the duration of the request so every downstream handler sees a
  ;; live context.
  (binding [ec/*execution-context* (:execution-ctx daemon)]
    (let [uri     (:uri req)
        ;; Spec-derived JSON HTTP API: dvergr.web.api projects EVERY op in
        ;; dvergr.ops to a reitit route (+ Swagger UI at /api/v1/docs), malli-
        ;; coercing args. A ring handler that returns nil off /api/v1/* + docs.
        ;; Thread THIS server's system value in (not a global) so the API acts
        ;; on the same world as the HTML routes — one handle, no singleton.
          api     (web-api/handler (assoc req :dvergr/daemon daemon))
        ;; HTML action routes: dvergr.web.ops projects dvergr.ops onto HTMX-shaped
        ;; HTTP (room/agent lifecycle). Returns a ring response or nil.
          derived (when-not api (web-ops/dispatch req daemon))]
      (cond
        api     api
        derived derived

      ;; API routes
        (= uri "/api/health")               (api-health req daemon)
        (= uri "/api/agents")               (api-agents req daemon)
        (str/starts-with? uri "/api/agents/") (handle-agent-api req daemon)
        (= uri "/api/schedules")            (api-schedules req daemon)

      ;; Whole-system stats panel (HTMX poll)
        (= uri "/api/system")
        (html-response 200 (web-dashboard/system-stats-fragment (:execution-ctx daemon)))

      ;; Rooms
        (and (= uri "/api/rooms") (= :get (:request-method req)))
        (html-response 200 (web-dashboard/rooms-tree-fragment (:execution-ctx daemon)))

      ;; room create / delete / fork / merge / discard are now DERIVED from
      ;; dvergr.ops via dvergr.web.ops (the `derived` branch above).

      ;; Per-room per-AGENT context bars — body of the expandable Context
      ;; panel (HTMX poll). NB: must precede the broader /stats and /messages
      ;; matches is unnecessary (distinct suffix), but keep it before /.+ catch-alls.
        (re-matches #"/api/rooms/.+/agents-context" uri)
        (let [slug (subs uri (count "/api/rooms/")
                         (- (count uri) (count "/agents-context")))]
          (html-response 200 (web-dashboard/room-agents-context-fragment
                              (:execution-ctx daemon) slug)))

      ;; Per-room stats strip (HTMX poll)
        (re-matches #"/api/rooms/.+/stats" uri)
        (let [slug (subs uri (count "/api/rooms/")
                         (- (count uri) (count "/stats")))]
          (html-response 200 (web-dashboard/room-stats-fragment
                              (:execution-ctx daemon) slug)))

      ;; Per-room messages fragment (HTMX poll)
        (re-matches #"/api/rooms/.+/messages" uri)
        (let [slug (subs uri (count "/api/rooms/")
                         (- (count uri) (count "/messages")))]
          (html-response 200 (web-dashboard/room-messages-fragment
                              (:execution-ctx daemon) slug)))

      ;; Room page
        (re-matches #"/rooms/(?!.+/post$).+" uri)
        (let [slug (subs uri (count "/rooms/"))]
          (html-response 200 (web-dashboard/room-page (:execution-ctx daemon) slug)))

      ;; Send a message to a room
        (re-matches #"/rooms/.+/post" uri)
        (let [slug (subs uri (count "/rooms/") (- (count uri) (count "/post")))
              body (slurp (:body req))
              params (into {} (for [pair (str/split body #"&")
                                    :let [[k v] (str/split pair #"=" 2)]
                                    :when (and k v)]
                                [k (java.net.URLDecoder/decode (str/replace v #"\+" " ") "UTF-8")]))
              content (some-> (get params "content") str/trim)]
          (require 'dvergr.discourse 'dvergr.room.registry 'dvergr.room.store
                   'dvergr.discourse.commands)
          (let [post!   (requiring-resolve 'dvergr.discourse/post!)
                message (requiring-resolve 'dvergr.discourse/message)
                execute! (requiring-resolve 'dvergr.discourse.commands/execute!)
                cmd?    (requiring-resolve 'dvergr.discourse.commands/command-input?)
                room    (when (seq content)
                          ((requiring-resolve 'dvergr.room.registry/lookup)
                           ((requiring-resolve 'dvergr.room.store/slug->room-id) slug)))
              ;; /fork etc. switch rooms — capture the target for the redirect.
                dest    (atom slug)
              ;; Canonical addressing — the SAME rule the TUI + REPL use
              ;; (dvergr.discourse/room-target): a 1-agent room (or a fork of
              ;; one) addresses its agent so it REPLIES; a group/empty room
              ;; broadcasts (:to nil, display-only).
                target  (when room
                          ((requiring-resolve 'dvergr.discourse/room-target) room))]
            (when (and room (cmd? content))
              (execute! content
                        {:room room
                         :agent-id target
                         :daemon daemon
                       ;; Operator console — grant executing tool commands.
                         :tool-exec? true
                         :available-tools (keys @@(requiring-resolve 'dvergr.tools/registry))
                         :post-user!   (fn [t] (post! room (message :web target t nil {:role :user})))
                         :notify!      (fn [t] (post! room (message :system :_activity
                                                                    (str "⌘ " t) nil {:role :assistant})))
                         :switch-room! (fn [nr] (reset! dest (:slug nr)))}))
            (when (and room (not (cmd? content)))
            ;; Explicit :role :user — without it store/infer-role sees a keyword
            ;; :from and mislabels the human's post as :assistant, so the agent's
            ;; next turn rehydrates it as its OWN words and just echoes its last
            ;; reply instead of answering. (Same fix the TUI room post uses.)
              (post! room (message :web target content nil {:role :user :source-user "web"})))
            {:status 303 :headers {"Location" (str "/rooms/" @dest)} :body ""}))

      ;; Homepage / ops dashboard
        (or (= uri "/") (= uri "/dashboard"))
        (html-response 200 (web-dashboard/dashboard-page))

      ;; The roster now lives in the dashboard's Agents section — keep /agents
      ;; as a redirect so old links/bookmarks land there.
        (= uri "/agents")
        {:status 303 :headers {"Location" "/dashboard#agents"} :body ""}

      ;; Per-agent config PAGE (GET render only). The save (POST) and create
      ;; (POST /agents/new) are DERIVED from dvergr.ops via dvergr.web.ops (the
      ;; `derived` branch above), parsing the form through the shared field-spec.
        (and (re-matches #"/agents/[^/]+/config" uri) (= :get (:request-method req)))
        (let [id (subs uri (count "/agents/") (- (count uri) (count "/config")))]
          (html-response 200 (web-agents/agent-config-page id)))

      ;; agent delete / open are now DERIVED from dvergr.ops via dvergr.web.ops
      ;; (the `derived` branch above). agent/delete stops-then-deletes in the op.

        :else                                (json-response 404 {:error "Not found"})))))

;; ============================================================================
;; Lifecycle
;; ============================================================================

(defn start!
  "Start the HTTP server.

   Args:
     daemon - Daemon record (for API access to agents, config, etc.)
     :port  - Port to listen on (default 17880)
     :ip    - Bind address (default \"127.0.0.1\" — loopback only). The dashboard
              and JSON API are UNAUTHENTICATED, so they bind to localhost; pass
              \"0.0.0.0\" explicitly (--web-bind / :http {:ip}) to expose them.

   Returns the server state map."
  [daemon & {:keys [port ip] :or {port 17880 ip "127.0.0.1"}}]
  (when @server-state
    (println "[web] Server already running on port" (:port @server-state))
    (throw (ex-info "Server already running" {:port (:port @server-state)})))
  (println "[web] Starting HTTP server on" (str ip ":" port) "...")
  (let [stop-fn (http/run-server
                 (fn [req] (root-handler daemon req))
                 {:port port
                  :ip   ip
                  :max-body (* 1024 1024)  ; 1MB max body
                  :thread 4})]
    (reset! server-state {:stop-fn stop-fn
                          :port port
                          :ip ip
                          :daemon daemon
                          :started-at (System/currentTimeMillis)})
    (println "[web] HTTP server started at" (str "http://" ip ":" port))
    @server-state))

(defn stop!
  "Stop the HTTP server."
  []
  (when-let [state @server-state]
    (println "[web] Stopping HTTP server...")
    ((:stop-fn state))
    (reset! server-state nil)
    (println "[web] HTTP server stopped.")
    :stopped))

(defn running?
  "Check if the HTTP server is running."
  []
  (some? @server-state))

(defn server-port
  "Get the port the server is running on, or nil."
  []
  (:port @server-state))
