(ns dvergr.principal
  "Who calls an op, and whether they may: the two seams every surface (MCP
   over stdio, TCP and HTTP, the JSON API, the web actions, the MCP REPL and
   the agents' registry tools) passes through before `dvergr.ops` acts.

   A principal is a plain map:

     {:principal/id     \"party/5f0c…\"     ; stable, compared by =; a string or keyword
      :principal/kind   :human             ; :human | :agent | :local
      :principal/actor  :party/5f0c…       ; the discourse actor it posts as (optional;
                                           ;   derived from :principal/id when absent)
      :principal/scopes #{:toolset/rooms}  ; what MCP may show it (`dvergr.mcp.surface/capped`)
      :principal/claims {…}}               ; the authenticator's evidence, opaque here

   `local-principal` is the process itself: whoever holds the daemon (its REPL,
   the TUI, room-run, Telegram commands) or reaches it the way only the local
   user can (the stdio relay, loopback TCP, the token file of MCP over HTTP).
   It is never sent to the authorizer. Only `default-authenticate` (and the
   in-process callers that hold the daemon, which name it explicitly) produce
   it; nothing defaults to it: `dvergr.ops/invoke` takes a principal.

   Two seams, configured on the daemon (`:auth` in its config) or, for a host
   that runs dvergr's rooms without a daemon, process-wide (`configure!`); the
   daemon's win. A value is a function or a qualified symbol naming one:

     :authenticate (fn [request] -> principal | nil)
        `request` is {:transport :mcp-stdio|:mcp-tcp|:mcp-http|:web|:telegram
                      :remote-addr \"…\" :bearer \"…\" :headers {…}
                      :local-token \"…\"             ; MCP over HTTP: <state>/mcp/http-token
                      :telegram/user {…} :telegram/chat-id …}
        nil refuses the caller (401, a closed connection, a refused command).
        Configuring one REPLACES `default-authenticate`; call it from yours to
        keep local access.

     :authorize (fn [principal request] -> decision)
        `request` is {:op :room/post :action :write :resource [:room \"general\"]
                      :resolved {:room \"<room id>\"} :args {…}}
        (`dvergr.ops/authz-request` builds it from the op's `:authz`): the
        resource as the caller named it, and what dvergr resolved it to (a
        room's id; a job's room and owner; nil when it resolves to nothing). A
        decision is true/false/nil or {:allow? bool :reason \"…\"}. A throw
        denies (so does a resolution that throws). Default: `allow-all`."
  (:require [clojure.string :as str]))

;; ============================================================================
;; Principals
;; ============================================================================

(def local-principal
  "The process itself (see the ns doc): every toolset, never asked about."
  {:principal/id :local :principal/kind :local :principal/scopes #{:dvergr/admin}})

(defn local?
  "Whether `principal` is the local one."
  [principal]
  (= :local (:principal/kind principal)))

(defn agent-principal
  "The principal of the agent `actor` (an actor keyword), as its tool calls run."
  [actor]
  {:principal/id actor :principal/kind :agent :principal/actor actor :principal/scopes #{}})

(defn actor
  "The discourse actor `principal` posts as: its `:principal/actor`, else its
   id as a keyword (\"party/1\" → :party/1)."
  [principal]
  (or (:principal/actor principal)
      (let [id (:principal/id principal)]
        (cond (keyword? id) id
              (string? id) (let [[a b] (str/split id #"/" 2)] (if b (keyword a b) (keyword a)))
              :else (keyword (str id))))))

;; ============================================================================
;; Authentication
;; ============================================================================

(defn loopback?
  "Whether `addr` (an IP string, possibly with a leading `/`) is loopback."
  [addr]
  (boolean
   (when-let [a (some-> addr str (str/replace #"^/" "") not-empty)]
     (try (.isLoopbackAddress (java.net.InetAddress/getByName a))
          (catch Exception _ false)))))

(defn- constant-time= [^String a ^String b]
  (and a b (java.security.MessageDigest/isEqual (.getBytes a "UTF-8") (.getBytes b "UTF-8"))))

(defn bearer
  "The token of an `Authorization: Bearer …` header value, or nil."
  [authorization]
  (some->> authorization (re-matches #"(?i)Bearer\s+(\S+)") second))

(defn default-authenticate
  "Local access only: the stdio relay; TCP and the web server from a loopback
   peer; MCP over HTTP with the token of `<state>/mcp/http-token`; a Telegram
   sender who reached the bot (its allowlist decides who does). Each is the
   local principal; anything else is refused."
  [{:keys [transport remote-addr local-token] :as request}]
  (when (case transport
          (:mcp-stdio :telegram) true
          (:mcp-tcp :web) (loopback? remote-addr)
          :mcp-http (constant-time= local-token (:bearer request))
          false)
    local-principal))

(defn allow-all
  "The default authorizer: everything (local trust)."
  [_principal _request]
  true)

(defn- seam [x]
  (cond (nil? x) nil
        (symbol? x) @(requiring-resolve x)
        (ifn? x) x
        :else (throw (ex-info (str "Not a function or symbol: " (pr-str x)) {:value x}))))

(defonce ^:private configured (atom {}))

(defn configure!
  "Set the process-wide seams, `{:authenticate f :authorize f}` (either may be
   left out), for hosts without a daemon or a daemon without `:auth`. nil
   values reset to the defaults."
  [seams]
  (reset! configured (select-keys seams [:authenticate :authorize])))

(defn authenticator
  "The authenticate fn: daemon `config`'s `[:auth :authenticate]`, else the
   process-wide one (`configure!`), else `default-authenticate`."
  [config]
  (or (seam (get-in config [:auth :authenticate])) (seam (:authenticate @configured))
      default-authenticate))

(defn authorizer
  "The authorize fn: daemon `config`'s `[:auth :authorize]`, else the
   process-wide one (`configure!`), else `allow-all`."
  [config]
  (or (seam (get-in config [:auth :authorize])) (seam (:authorize @configured)) allow-all))

;; ============================================================================
;; Authorization
;; ============================================================================

(def actions
  "What an op does to its resource, weakest first (as `dvergr.authority`):
   see it, change it, land a fork onto it, administer it (archive, purge,
   agents, the host's files)."
  #{:read :write :merge :admin})

(defn- describe [{:keys [op action resource]}]
  (str (some-> op (#(str (namespace %) "_" (name %)))) " (" (some-> action name) " "
       (pr-str resource) ")"))

(defn decide
  "Ask the authorizer of `daemon` about `request` for `principal`:
   `{:allow? bool :reason str}`. The local principal is always allowed."
  [daemon principal request]
  (if (local? principal)
    {:allow? true}
    (let [answer (try ((authorizer (:config daemon)) principal request)
                      (catch Exception e {:allow? false :reason (str "authorizer failed: " (ex-message e))}))
          allow? (if (map? answer) (true? (:allow? answer)) (true? answer))]
      {:allow? allow?
       :reason (when-not allow?
                 (or (when (map? answer) (:reason answer))
                     (str "Not authorized: " (pr-str (:principal/id principal)) " may not " (describe request))))})))

(defn current-daemon
  "The daemon of this process, without loading the daemon namespace when it is
   not loaded (then there is none, and the defaults hold)."
  []
  (some-> (find-ns 'dvergr.orchestration.daemon) (ns-resolve 'current-daemon) deref deref))
