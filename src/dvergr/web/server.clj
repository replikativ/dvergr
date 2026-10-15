(ns dvergr.web.server
  "HTTP server for dvergr: the HTMX dashboard, its HTML fragments and actions,
   room apps (`/apps/`) and the spec-derived JSON API (`/api/v1/`, see
   `dvergr.web.api`).

   http-kit with hand-written routing for the HTML routes. Every request passes
   `dvergr.web.guard` first (Host, Origin and CSRF checks): loopback binding
   keeps other machines out, not the pages the user's browser shows.

   Lifecycle follows daemon pattern:
     (start! daemon :port 17880)   ; binds 127.0.0.1 by default
     (stop!)

   API endpoints return JSON by default, HTML fragments when Accept: text/html.
   Every state-changing route is a POST.

   Every request is authenticated (`dvergr.principal`, transport `:web`): by
   default a loopback peer is the local principal and any other is refused
   (401). The JSON API (`/api/v1/`) and the derived actions run for the
   request's principal; room apps need a read of their room; the rest of the
   dashboard (hand-written views over the daemon) answers the local principal
   only (403 for anyone else)."
  (:require [org.httpkit.server :as http]
            [jsonista.core :as json]
            [clojure.string :as str]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.yggdrasil :as ygg]
            [dvergr.actors :as actors]
            [dvergr.agent.ops :as ops]
            [dvergr.web.dashboard :as web-dashboard]
            [dvergr.web.agents :as web-agents]
            [dvergr.web.apps :as web-apps]
            [dvergr.web.ops :as web-ops]
            [dvergr.web.api :as web-api]
            [dvergr.web.guard :as guard]
            [dvergr.ops :as dops]
            [dvergr.principal :as principal]
            [clojure.java.io :as io]
            [hiccup2.core :as h]
            [hiccup.util :as hu]
            [taoensso.telemere :as tel]))

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
   :headers {"Content-Type" "application/json"}
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
                       (str (h/html
                             [:div
                              (if (seq schedules)
                                (for [s schedules]
                                  [:div.schedule-card
                                   [:strong (str (or (:description s) (:task s)))]
                                   " — " (name (or (:agent-id s) "?"))
                                   " in #" (str (:room s))
                                   (when (:next-fire s)
                                     (str " (next: " (:next-fire s) ")"))])
                                [:p "No schedules active."])])))
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

(declare route dashboard-route)

(defn- local-only
  "The hand-written dashboard: views and actions straight over the daemon,
   not through `dvergr.ops`, so the local principal's only."
  [principal resp-fn]
  (if (principal/local? principal)
    (resp-fn)
    (json-response 403 {:error "The dashboard is local; use the JSON API (/api/v1/)"})))

(def session-ttl-ms
  "How long a browser session lasts after its login."
  (* 12 60 60 1000))

(def ^:private max-sessions
  "Browser sessions kept at once; the oldest go first."
  1000)

(def app-link-ttl-ms
  "How long a room app's capability link lasts."
  (* 60 60 1000))

(defn- authenticate
  "The principal of credential `token` (a bearer, the login's token), or
   nil. A run token (a pinned agent's) is for MCP only, never the web."
  [authenticate-fn req token]
  (let [p (try (authenticate-fn {:transport :web
                                 :bearer token
                                 :remote-addr (:remote-addr req)
                                 :headers (:headers req)})
               (catch Exception e
                 (tel/log! {:level :warn :id ::authenticate-failed :data {:error (ex-message e)}})
                 nil))]
    (when-not (principal/pinned? p) p)))

(defn- query-param [req k]
  (some (fn [part]
          (let [[pk v] (str/split part #"=" 2)]
            (when (= k pk) (some-> v (java.net.URLDecoder/decode "UTF-8")))))
        (some-> (:query-string req) (str/split #"&"))))

(defn- form-param
  "Field `k` of `req`'s urlencoded body (read once)."
  [req k]
  (some-> (:body req) slurp (str/split #"&")
          (->> (some (fn [part]
                       (let [[pk v] (str/split part #"=" 2)]
                         (when (= k pk) (some-> v (str/replace "+" " ") (java.net.URLDecoder/decode "UTF-8")))))))))

(defn- local-path
  "`uri` when it is a path of this server (one leading `/`), else `/`: a
   redirect never leaves it (`//host` is another server)."
  [uri]
  (if (and (str/starts-with? (str uri) "/") (not (str/starts-with? (str uri) "//"))) uri "/"))

(defn- open-session!
  "A new session for `principal` in `sessions`: expired ones are swept, the
   oldest dropped beyond `max-sessions`. Returns its id."
  [sessions principal]
  (let [sid (principal/random-token)
        now (System/currentTimeMillis)]
    (swap! sessions
           (fn [m]
             (let [live (into {} (remove (fn [[_ v]] (< (:expires-at v) now))) m)
                   kept (if (< (count live) max-sessions)
                          live
                          (into {} (take (dec max-sessions) (sort-by (comp - :expires-at val) live))))]
               (assoc kept sid {:principal principal :expires-at (+ now session-ttl-ms)
                                ;; a new local token ends every session opened before it
                                :generation @principal/token-generation}))))
    sid))

(defn- session-principal
  "The principal of the live session `sid` in `sessions`, or nil."
  [sessions sid]
  (when-let [{:keys [principal expires-at generation]} (and sid (get @sessions sid))]
    (if (or (< expires-at (System/currentTimeMillis)) (not= generation @principal/token-generation))
      (do (swap! sessions dissoc sid) nil)
      principal)))

(defn- login-page
  "The page a browser without an accepted session sees (`state` `:shell`: none
   sent; `:rejected`: the one sent was refused, so the page drops it and never
   retries it). Its script (/static/dvergr.js) picks a new session up from the
   fragment, has the service worker attach it, or asks for the token (POSTed,
   so it stays out of the history)."
  [state error]
  (str
   "<!DOCTYPE html>"
   (h/html
    [:html {:data-dvergr-auth (name state)}
     [:head [:meta {:charset "utf-8"}] [:title "dvergr"]
      [:link {:rel "icon" :href "data:,"}]
      (for [src web-dashboard/static-scripts] [:script {:src src}])
      [:style "body{background:#111;color:#ddd;font-family:sans-serif;margin:3em}
               input{background:#18181b;border:1px solid #333;color:#e0e0e0;padding:6px 10px}"]]
     [:body
      [:div#login {:style "display:none"}
       [:h1 "dvergr"]
       [:p#login-note {:style "color:#d77"} error]
       [:form {:method "post" :action "/login"}
        [:p "The token is in the daemon's state root, " [:code "local-token"] "."]
        [:input {:type "password" :name "token" :autocomplete "off" :placeholder "local token"}]
        " " [:button {:type "submit"} "Open"]]]]])))

(def ^:private page-csp
  "The dashboard's pages run this origin's scripts only (vendored htmx and
   /static/dvergr.js; no inline script, no eval), embed no plugins, keep their
   base URL, post forms to this origin only, and are framed by nobody (the
   pages' own inline styles stay allowed)."
  (str "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; "
       "connect-src 'self'; form-action 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'"))

(defn- static-file
  "A file of resources/dvergr/web/static, public (no credential: scripts the
   login page needs), or nil."
  [uri]
  (when-let [[_ f] (re-matches #"/static/([a-z0-9.-]+\.js)" (str uri))]
    (when-let [r (io/resource (str "dvergr/web/static/" f))]
      {:status 200
       :headers {"Content-Type" "text/javascript; charset=utf-8" "Cache-Control" "no-cache"}
       :body (slurp r)})))

(defn- service-worker
  "The service worker that attaches the session (dvergr-sw.js), at the root so
   its scope is the whole origin."
  []
  {:status 200
   :headers {"Content-Type" "text/javascript; charset=utf-8" "Cache-Control" "no-cache"}
   :body (slurp (io/resource "dvergr/web/static/dvergr-sw.js"))})

(defn- login-response
  "After a valid token: a new session, and a redirect to `path` with the
   session id in the fragment, which the page's script keeps (no cookie)."
  [sessions principal path]
  (let [sid (open-session! sessions principal)]
    {:status 303
     :headers {"Location" (str (local-path path) "#sid=" sid)
               "Cache-Control" "no-store"
               "Referrer-Policy" "no-referrer"}
     :body ""}))

(defn- page-request?
  "A browser navigation to a page (not the API, a room app, or a fragment
   htmx fetches): answered with the login page when it has no session."
  [req]
  (and (#{:get :head} (:request-method req))
       (not (str/starts-with? (str (:uri req)) "/api/"))
       (not (str/starts-with? (str (:uri req)) "/apps/"))))

(defn- serve-app
  "A room app for `principal` (its read of the room authorized on the very
   row `dvergr.web.apps` serves), sandboxed: an opaque origin, so its scripts
   reach nothing of this server."
  [daemon principal req]
  (some-> (try (web-apps/handle req daemon
                                (fn [row slug]
                                  (dops/authorize! daemon principal :room/read
                                                   {:room (or (:room/slug row) slug) :path "/app"})))
               (catch clojure.lang.ExceptionInfo e
                 (json-response (dops/error-status e) {:error (ex-message e)})))
          (assoc-in [:headers "Content-Security-Policy"] "sandbox allow-scripts")
          ;; its capability is in its URL: never in a Referer
          (assoc-in [:headers "Referrer-Policy"] "no-referrer")))

(defn- app-by-capability
  "`/apps/<slug>/~<cap>/<path>`: a room app through a capability link
   (`/api/app-link`), its read authorized for the principal that minted it.
   nil when `req` is not such a request."
  [daemon app-caps req]
  (when-let [[_ enc cap rest] (re-matches #"/apps/([^/]+)/~([0-9a-f]{64})(/.*)?" (str (:uri req)))]
    (let [slug (java.net.URLDecoder/decode enc "UTF-8")    ; as the link was minted (and web-apps decodes)
          {:keys [principal room expires-at]} (get @app-caps cap)]
      (cond
        (or (nil? principal) (not= room slug) (< expires-at (System/currentTimeMillis)))
        {:status 401 :headers {"Content-Type" "text/plain"} :body "This app link expired; open the app from its room again."}

        (nil? rest)
        {:status 301 :headers {"Location" (str "/apps/" enc "/~" cap "/")} :body ""}

        :else (serve-app daemon principal (assoc req :uri (str "/apps/" enc rest)))))))

(defn- login-error
  "What a refused login says: generic, so a caller without a credential learns
   nothing of the server's files; a token replaced for being exposed says so."
  []
  (if @principal/rotation-notice
    "That token opens nothing: the local token was replaced (its file was readable by others)."
    "That token opens nothing."))

(def ^:private unauthorized
  {:status 401 :headers {"Content-Type" "application/json" "WWW-Authenticate" "Bearer"}
   :body "{\"error\":\"Not authenticated: open the URL with ?token= the daemon printed, or send Authorization: Bearer\"}"})

(defn- root-handler
  "Credentials travel only in the `Authorization` header (`Bearer`: the local
   token, or a browser session id), never in a cookie: cookies are not scoped
   by port, so any server on this host would receive one and could replay
   it. A browser logs in Jupyter-style: `GET <path>?token=<token>` (or a
   POST of `token` to `/login`, which keeps it out of the history) opens a
   session and redirects to `<path>#sid=<id>`; the page's script keeps the id
   in `sessionStorage` and sends it as the header (`dashboard/auth-script`).
   A page navigation without one gets the login page; room apps open through
   capability links. A request whose Origin is `null` (a sandboxed room app)
   reaches no room app or API at all."
  [daemon authenticate-fn sessions app-caps req]
  (let [uri (str (:uri req))
        cap-resp (when (str/starts-with? uri "/apps/") (app-by-capability daemon app-caps req))]
    (cond
      (and (= "null" (get-in req [:headers "origin"])) (not (str/starts-with? uri "/apps/")))
      {:status 403 :headers {"Content-Type" "application/json"}
       :body "{\"error\":\"A sandboxed page (Origin null) may not call this server\"}"}

      (= "/dvergr-sw.js" uri) (service-worker)

      (static-file uri) (static-file uri)

      cap-resp cap-resp

      (and (#{:get :head} (:request-method req)) (query-param req "token"))
      (if-let [p (authenticate authenticate-fn req (query-param req "token"))]
        (login-response sessions p uri)
        {:status 401 :headers {"Content-Type" "text/html; charset=utf-8"}
         :body (login-page :shell (login-error))})

      (and (= :post (:request-method req)) (= "/login" uri))
      (if-let [p (authenticate authenticate-fn req (form-param req "token"))]
        (login-response sessions p "/")
        {:status 401 :headers {"Content-Type" "text/html; charset=utf-8"}
         :body (login-page :shell (login-error))})

      :else
      (let [bearer (principal/bearer (get-in req [:headers "authorization"]))
            sid-p (session-principal sessions bearer)
            p (or sid-p (when bearer (authenticate authenticate-fn req bearer)))]
        (cond
          ;; a credential was sent and refused (a session that ended): 401, and
          ;; for a page the login page, told to drop that session, not retry it
          (and (nil? p) bearer)
          (if (page-request? req)
            {:status 401 :headers {"Content-Type" "text/html; charset=utf-8" "Cache-Control" "no-store"}
             :body (login-page :rejected nil)}
            unauthorized)

          (nil? p)
          (if (page-request? req)
            {:status 200 :headers {"Content-Type" "text/html; charset=utf-8" "Cache-Control" "no-store"}
             :body (login-page :shell nil)}
            unauthorized)

          ;; a session's change must have passed the service worker's same-origin
          ;; check (it adds the header then); a script with the token needs none
          (and sid-p (not (#{:get :head :options} (:request-method req)))
               (not= "1" (get-in req [:headers "x-dvergr-request"])))
          {:status 403 :headers {"Content-Type" "application/json"}
           :body "{\"error\":\"A session's change must come from this origin's pages\"}"}

          (and (= :post (:request-method req)) (= "/logout" uri))
          (do (when sid-p (swap! sessions dissoc bearer)) {:status 204 :body ""})

          (and (= "/api/app-link" uri) (= :post (:request-method req)))
          (let [slug (query-param req "room")]
            (try (dops/authorize! daemon p :room/read {:room slug :path "/app"})
                 (let [cap (principal/random-token)
                       now (System/currentTimeMillis)]
                   (swap! app-caps (fn [m] (assoc (into {} (remove #(< (:expires-at (val %)) now)) m)
                                                  cap {:principal p :room slug
                                                       :expires-at (+ now app-link-ttl-ms)})))
                   (json-response 200 {:url (str "/apps/" (str/replace (java.net.URLEncoder/encode (str slug) "UTF-8") "+" "%20")
                                                 "/~" cap "/")}))
                 (catch clojure.lang.ExceptionInfo e
                   (json-response (dops/error-status e) {:error (ex-message e)}))))

          :else (route daemon p req))))))

(defn- with-security-headers
  "Every response: no framing (clickjacking) and no content sniffing; a room
   app keeps its own sandbox policy."
  [handler]
  (fn [req]
    (let [resp (handler req)]
      (when (map? resp)
        (cond-> (assoc-in resp [:headers "X-Content-Type-Options"] "nosniff")
          (not (str/starts-with? (str (:uri req)) "/apps/"))
          (-> (assoc-in [:headers "Content-Security-Policy"] page-csp)
              (assoc-in [:headers "X-Frame-Options"] "DENY")))))))

(defn- route [daemon principal req]
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
          api     (web-api/handler (assoc req :dvergr/daemon daemon :dvergr/principal principal))
        ;; HTML action routes: dvergr.web.ops projects dvergr.ops onto HTMX-shaped
        ;; HTTP (room/agent lifecycle). Returns a ring response or nil.
          derived (when-not api (web-ops/dispatch req daemon principal))]
      (cond
        api     api
        derived derived

      ;; Room apps — static serving of <room worktree>/app (see dvergr.web.apps),
      ;; for whoever may read the room
        ;; an app with the header (a program); a browser opens it by capability
        (str/starts-with? uri "/apps/") (serve-app daemon principal req)

        (= uri "/api/health")               (api-health req daemon)

        :else (local-only principal #(dashboard-route daemon req))))))

(defn- dashboard-route [daemon req]
  (let [uri (:uri req)]
    (cond

      ;; API routes
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
      (re-matches #"/rooms/(?!.+/(?:post|voice)$).+" uri)
      (let [slug (subs uri (count "/rooms/"))]
        (html-response 200 (web-dashboard/room-page (:execution-ctx daemon) slug)))

      ;; Voice message to a room: raw audio body (browser MediaRecorder
      ;; webm/opus or any container whisper sniffs) → dvergr.audio.stt →
      ;; posted as a 🎤-prefixed user message, same addressing as /post.
      (re-matches #"/rooms/.+/voice" uri)
      (let [slug (subs uri (count "/rooms/") (- (count uri) (count "/voice")))
            room ((requiring-resolve 'dvergr.room.registry/lookup)
                  ((requiring-resolve 'dvergr.room.store/slug->room-id) slug))]
        (cond
            ;; Bodyless hit (browser GET, prefetch, crawler — the room-page
            ;; regex excludes /voice so those land here): not a voice upload.
          (or (not= :post (:request-method req)) (nil? (:body req)))
          (json-response 405 {:error "POST raw audio bytes"})

            ;; Resolve the room BEFORE transcribing — no paid STT call for
            ;; a nonexistent slug.
          (not room)
          (json-response 404 {:error "room not found"})

          :else
          (let [bytes (.readAllBytes ^java.io.InputStream (:body req))
                mime (get-in req [:headers "content-type"] "audio/webm")
                text ((requiring-resolve 'dvergr.audio.stt/transcribe)
                      {:bytes bytes :mime mime})]
            (if (nil? text)
              (json-response 422 {:error "no speech recognized"})
              (let [post!   (requiring-resolve 'dvergr.discourse/post!)
                    message (requiring-resolve 'dvergr.discourse/message)
                    target  ((requiring-resolve 'dvergr.discourse/room-target) room)]
                (post! room (message :web target (str "🎤 " text) nil
                                     {:role :user :source-user "web"}))
                (json-response 200 {:text text}))))))

      ;; Send a message to a room
      (and (re-matches #"/rooms/.+/post" uri) (not= :post (:request-method req)))
      (json-response 405 {:error "POST a form with `content`"})

      (re-matches #"/rooms/.+/post" uri)
      (let [slug (subs uri (count "/rooms/") (- (count uri) (count "/post")))
            params (if (:body req) (parse-form-params req) {})
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
                       ;; the dashboard is the local principal's (`local-only`)
                       :principal principal/local-principal
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

      :else                                (json-response 404 {:error "Not found"}))))

;; ============================================================================
;; Lifecycle
;; ============================================================================

(defn handler
  "The ring handler of the web server for `daemon`, behind the browser-boundary
   checks of `dvergr.web.guard`. Opts: `:ip` (the bind address), `:allowed-hosts`
   and `:allowed-origins` (names and origins a reverse proxy adds),
   `:authenticate` (default: the daemon's, `principal/authenticator`)."
  [daemon {:keys [authenticate] :as opts}]
  (with-security-headers
    (guard/wrap (let [authn (or authenticate (principal/authenticator (:config daemon)))
                      sessions (atom {})
                      app-caps (atom {})]
                  (fn [req] (root-handler daemon authn sessions app-caps req)))
                opts)))

(defn start!
  "Start the HTTP server.

   Args:
     daemon - Daemon record (for API access to agents, config, etc.)
     :port  - Port to listen on (default 17880)
     :ip    - Bind address (default \"127.0.0.1\"). Every request needs a
              credential, wherever it comes from: the local token (the login
              URL, `:login-url` of the result, opens a session) or what the
              daemon's authenticator accepts.
     :authenticate - the seam (default: the daemon's, `principal/authenticator`)
     :allowed-hosts   - extra Host names to answer (beside loopback and the bind
                        address), e.g. a reverse proxy's name
     :allowed-origins - extra origins whose state-changing requests are accepted,
                        e.g. \"https://dvergr.example.com\" behind a proxy

   Returns the server state map."
  [daemon & {:keys [port ip allowed-hosts allowed-origins authenticate] :or {port 17880 ip "127.0.0.1"}}]
  (when @server-state
    (tel/log! {:level :warn :id ::already-running :data {:port (:port @server-state)}})
    (throw (ex-info "Server already running" {:port (:port @server-state)})))
  (tel/log! {:level :info :id ::starting :data {:ip ip :port port}} "Starting HTTP server")
  (let [authn (or authenticate (principal/authenticator (:config daemon)))
        stop-fn (http/run-server
                 (handler daemon {:ip ip :allowed-hosts allowed-hosts
                                  :allowed-origins allowed-origins :authenticate authn})
                 {:port port
                  :ip   ip
                  :max-body (* 1024 1024)  ; 1MB max body
                  :thread 4})]
    (reset! server-state {:stop-fn stop-fn
                          :port port
                          :ip ip
                          :daemon daemon
                          :started-at (System/currentTimeMillis)})
    (tel/log! {:level :info :id ::started :data {:url (str "http://" ip ":" port)}}
              "HTTP server started (start! returns its login URL, :login-url)")
    ;; Jupyter-style: open it once, the session does the rest. Returned to the
    ;; caller (the CLI prints it), never kept in the server state.
    (assoc @server-state
           :login-url (str "http://" (if (#{"0.0.0.0" "::"} ip) "127.0.0.1" ip) ":"
                           port "/?token=" (principal/local-token)))))

(defn stop!
  "Stop the HTTP server."
  []
  (when-let [state @server-state]
    (tel/log! {:level :info :id ::stopping} "Stopping HTTP server")
    ((:stop-fn state))
    (reset! server-state nil)
    (tel/log! {:level :info :id ::stopped} "HTTP server stopped")
    :stopped))

(defn running?
  "Check if the HTTP server is running."
  []
  (some? @server-state))

(defn server-port
  "Get the port the server is running on, or nil."
  []
  (:port @server-state))
