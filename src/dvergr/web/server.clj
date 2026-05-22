(ns dvergr.web.server
  "HTTP server for dvergr — dashboard, API endpoints, and agent-mounted UIs.

   Uses http-kit with manual prefix routing (no Ring/reitit/compojure deps).
   Agent handlers are mounted at /agents/{id}/ with automatic prefix stripping
   so agents write standard Ring handlers seeing '/' as root.

   Lifecycle follows daemon pattern:
     (start! daemon :port 8080)
     (stop!)

   API endpoints return JSON by default, HTML fragments when Accept: text/html."
  (:require [org.httpkit.server :as http]
            [jsonista.core :as json]
            [clojure.string :as str]
            [dvergr.registry :as registry]
            [dvergr.stats :as stats]
            [dvergr.web.dashboard :as web-dashboard]
            [dvergr.web.agents :as web-agents]
            [dvergr.web.chat :as web-chat]))

;; ============================================================================
;; State
;; ============================================================================

(defonce server-state (atom nil))         ; {:stop-fn fn :port int :daemon Daemon}
(defonce agent-handlers (atom {}))        ; {agent-id -> ring-handler-fn}

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

;; ============================================================================
;; System API Routes
;; ============================================================================

(defn- api-health [_req daemon]
  (let [status (when daemon @(:status daemon))]
    (json-response 200
      {:status (or status :unknown)
       :agents (count (registry/agent-ids))
       :uptime-ms (- (System/currentTimeMillis)
                     (or (:started-at @server-state) (System/currentTimeMillis)))})))

(defn- format-agent-info [agent-info]
  (let [st (stats/get-stats (:id agent-info))]
    {:id           (name (:id agent-info))
     :status       (name (or (:status agent-info) :unknown))
     :description  (:description agent-info)
     :tags         (mapv name (:tags agent-info))
     :has-ui       (boolean (get @agent-handlers (:id agent-info)))
     :cost-dollars (:cost-dollars st)
     :last-active  (some-> (:last-active st) str)
     :last-active-str (or (:last-active-str st) "—")
     :summary      (:summary st)}))

(defn- api-agents [req daemon]
  (let [agents (registry/list-agents)
        data (mapv format-agent-info agents)]
    (if (wants-html? req)
      (html-response 200
        (str "<div>"
             (if (seq data)
               (str/join "\n"
                 (map (fn [a]
                        (let [cost-str (if (:cost-dollars a)
                                         (format "$%.3f" (double (:cost-dollars a)))
                                         "—")]
                          (str "<div class=\"agent-card\">"
                               "<div class=\"agent-header\">"
                               "<strong>" (:id a) "</strong>"
                               " <span class=\"status-badge status-" (:status a) "\">"
                               (:status a) "</span>"
                               (when (:has-ui a)
                                 (str " <a href=\"/agents/" (:id a) "/\">UI</a>"))
                               "</div>"
                               "<small class=\"agent-desc\">" (:description a) "</small>"
                               "<div class=\"agent-stats\">"
                               "<span class=\"stat-cost\">" cost-str "</span>"
                               "<span class=\"stat-sep\"> · </span>"
                               "<span class=\"stat-age\">" (or (:last-active-str a) "—") "</span>"
                               (when (:summary a)
                                 (str "<span class=\"stat-sep\"> · </span>"
                                      "<span class=\"stat-summary\">" (:summary a) "</span>"))
                               "</div>"
                               "</div>")))
                      data))
               "<p>No agents registered.</p>")
             "</div>"))
      (json-response 200 {:agents data}))))

(defn- api-agent-inbox [_req daemon agent-id-str]
  (let [agent-id (keyword agent-id-str)]
    (if-let [ag (registry/get-agent agent-id)]
      (do
        ;; Send a simple text message to the agent's inbox
        ;; Body parsing would need to read the request body
        (json-response 202 {:status "dispatched" :agent-id agent-id-str}))
      (json-response 404 {:error (str "Agent not found: " agent-id-str)}))))

(defn- api-schedules [req daemon]
  ;; Lazy-load scheduler to avoid circular deps
  (try
    (require 'dvergr.scheduler.core)
    (let [list-fn (ns-resolve (find-ns 'dvergr.scheduler.core) 'list-schedules)
          schedules (list-fn)]
      (if (wants-html? req)
        (html-response 200
          (str "<div>"
               (if (seq schedules)
                 (str/join "\n"
                   (map (fn [s]
                          (str "<div class=\"schedule-card\">"
                               "<strong>" (:description s) "</strong>"
                               " — every " (/ (:interval-ms s) 60000.0) "min"
                               " → " (name (:agent-id s))
                               (when (:last-run s)
                                 (str " (last: " (:last-run s) ")"))
                               "</div>"))
                        schedules))
                 "<p>No schedules active.</p>")
               "</div>"))
        (json-response 200 {:schedules schedules})))
    (catch Exception _
      (json-response 200 {:schedules []}))))

;; ============================================================================
;; Agent UI Dispatch
;; ============================================================================

(defn- dispatch-agent-ui
  "Route requests to /agents/{id}/... to the agent's mounted handler.
   Strips the /agents/{id} prefix so the agent handler sees '/' as root."
  [req]
  (let [uri (:uri req)
        ;; Parse /agents/{id}/optional-path
        [_ agent-id-str path] (re-matches #"/agents/([^/]+)(/.*)?" uri)
        agent-id (when agent-id-str (keyword agent-id-str))
        handler (when agent-id (get @agent-handlers agent-id))]
    (if handler
      (try
        (handler (assoc req :uri (or path "/")))
        (catch Exception e
          (html-response 500
            (str "<h2>Agent UI Error</h2><pre>" (.getMessage e) "</pre>"))))
      (json-response 404
        {:error (str "No UI mounted for agent: " agent-id-str)}))))

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
  (let [uri (:uri req)]
    (cond
      ;; API routes
      (= uri "/api/health")               (api-health req daemon)
      (= uri "/api/agents")               (api-agents req daemon)
      (str/starts-with? uri "/api/agents/") (handle-agent-api req daemon)
      (= uri "/api/schedules")            (api-schedules req daemon)

      ;; Chat messages API
      (and (str/starts-with? uri "/api/chat/")
           (str/ends-with? uri "/messages"))
      (let [path-part (subs uri (count "/api/chat/"))
            agent-id-str (subs path-part 0 (str/index-of path-part "/"))]
        (web-chat/api-chat-messages req daemon agent-id-str))

      ;; Homepage / ops dashboard
      (or (= uri "/") (= uri "/dashboard"))
      (html-response 200 (web-dashboard/dashboard-page))

      ;; Agent list
      (= uri "/agents")
      (html-response 200 (web-agents/agents-list-page))

      ;; Agent UI dispatch
      (str/starts-with? uri "/agents/")    (dispatch-agent-ui req)

      ;; Chat send (POST /chat/:agent-id/send)
      (and (str/starts-with? uri "/chat/")
           (str/ends-with? uri "/send")
           (= :post (:request-method req)))
      (let [path-part (subs uri (count "/chat/"))
            agent-id-str (subs path-part 0 (str/index-of path-part "/"))]
        (web-chat/handle-send req daemon agent-id-str))

      ;; Chat page (GET /chat/:agent-id)
      (and (str/starts-with? uri "/chat/")
           (= :get (:request-method req)))
      (let [agent-id-str (subs uri (count "/chat/"))]
        (web-chat/chat-page req daemon agent-id-str))

      :else                                (json-response 404 {:error "Not found"}))))

;; ============================================================================
;; Agent Handler Mounting
;; ============================================================================

(defn mount-agent-handler!
  "Mount a Ring handler for an agent at /agents/{agent-id}/.
   The handler receives requests with prefix stripped (sees '/' as root).
   Returns the full URL path where the handler is mounted."
  [agent-id handler-fn]
  (swap! agent-handlers assoc agent-id handler-fn)
  (let [port (or (:port @server-state) 8080)]
    (str "http://localhost:" port "/agents/" (name agent-id) "/")))

(defn unmount-agent-handler!
  "Remove a mounted agent handler."
  [agent-id]
  (swap! agent-handlers dissoc agent-id))

;; ============================================================================
;; Lifecycle
;; ============================================================================

(defn start!
  "Start the HTTP server.

   Args:
     daemon - Daemon record (for API access to agents, config, etc.)
     :port  - Port to listen on (default 8080)

   Returns the server state map."
  [daemon & {:keys [port] :or {port 8080}}]
  (when @server-state
    (println "[web] Server already running on port" (:port @server-state))
    (throw (ex-info "Server already running" {:port (:port @server-state)})))
  (println "[web] Starting HTTP server on port" port "...")
  (let [stop-fn (http/run-server
                  (fn [req] (root-handler daemon req))
                  {:port port
                   :max-body (* 1024 1024)  ; 1MB max body
                   :thread 4})]
    (reset! server-state {:stop-fn stop-fn
                          :port port
                          :daemon daemon
                          :started-at (System/currentTimeMillis)})
    (println "[web] HTTP server started at http://localhost:" port)
    @server-state))

(defn stop!
  "Stop the HTTP server and clear all agent handlers."
  []
  (when-let [state @server-state]
    (println "[web] Stopping HTTP server...")
    ((:stop-fn state))
    (reset! agent-handlers {})
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
