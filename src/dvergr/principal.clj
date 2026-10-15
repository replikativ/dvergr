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
   the TUI, room-run) or reaches it the way only the local user can (the stdio
   relay, a loopback socket peer with no forwarding header, the token file of
   MCP over HTTP from such a peer, a sender on a non-empty Telegram allowlist).
   It is never sent to the authorizer. It is recognized by identity (an
   unforgeable marker, `local?`), never by its `:principal/kind`: a map an
   authenticator builds cannot become it, and `dvergr.ops/invoke` refuses a
   principal that claims kind `:local` without being it. Only
   `default-authenticate` (and the in-process callers that hold the daemon,
   which name it explicitly) produce it; nothing defaults to it.

   Two seams, configured on the daemon (`:auth` in its config) or, for a host
   that runs dvergr's rooms without a daemon, process-wide (`configure!`); the
   daemon's win. A value is a function or a qualified symbol naming one:

     :authenticate (fn [request] -> principal | nil)
        `request` is {:transport :mcp-stdio|:mcp-tcp|:mcp-http|:web|:telegram
                      :remote-addr \"…\" :bearer \"…\" :headers {…}
                      :local-token \"…\"             ; MCP over HTTP: <state>/mcp/http-token
                      :telegram/user {…} :telegram/chat-id …}
        `:remote-addr` is the socket peer, never a forwarding header (those are
        in `:headers`; behind a proxy, deciding what they are worth is the
        authenticator's job). nil refuses the caller (401, a closed
        connection, a refused command). Configuring one REPLACES
        `default-authenticate`; a hosted authenticator should not return the
        local principal at all. A listener bound to a non-loopback address
        refuses to start with the default (`check-bind!`).

     :authorize (fn [principal request] -> decision)
        `request` is {:op :room/post :action :write :resource [:room \"general\"]
                      :resolved {:room \"<room id>\"} :args {…}}
        (`dvergr.ops/authz-request` builds it from the op's `:authz`): the
        resource as the caller named it, and what dvergr resolved it to (a
        room's id; a job's room and owner; nil when it resolves to nothing). A
        decision is true/false/nil or {:allow? bool :reason \"…\"}. A throw
        denies (so does a resolution that throws). `:resolved nil` means the
        handle names nothing: deny it, except for the ops that create what
        they name. Default: `allow-all`.

     :on-created (fn [principal resource]) — called after an op created
        `resource` (`[:room id]`, `[:agent id]`) for a principal other than the
        local one, so the host can grant its creator; a throw fails the op
        (the thing exists; the error says so)."
  (:require [clojure.string :as str]
            [dvergr.security.allowlist :as allowlist]))

;; ============================================================================
;; Principals
;; ============================================================================

(def ^:private local-marker
  ;; identity, not a value: no EDN, JSON or claim can produce it
  (Object.))

(def local-principal
  "The process itself (see the ns doc): every toolset, never asked about."
  {:principal/id :local :principal/kind :local :principal/scopes #{:dvergr/admin}
   ::local local-marker})

(defn local?
  "Whether `principal` is the local one: it carries the process's marker. A
   map that only says `:principal/kind :local` is not."
  [principal]
  (identical? local-marker (::local principal)))

(defn forged-local?
  "Whether `principal` claims kind `:local` without being the local one."
  [principal]
  (and (= :local (:principal/kind principal)) (not (local? principal))))

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

(def forwarding-headers
  "Headers a proxy adds to name the client it forwards for. Their presence
   means the socket peer is not the client."
  #{"x-forwarded-for" "forwarded" "x-real-ip" "x-forwarded-host" "x-client-ip"})

(defn forwarded?
  "Whether ring-style `headers` (lower-case names) carry a forwarding header."
  [headers]
  (boolean (some #(contains? forwarding-headers (str/lower-case (str %))) (keys headers))))

(defn socket-peer
  "The IP of the socket peer of an http-kit `req` (its `:async-channel`), or
   nil when it cannot be told. Not `:remote-addr`: http-kit fills that from
   `X-Forwarded-For` when the header is present."
  [req]
  (try
    (when-let [ch (:async-channel req)]
      (let [f (doto (.getDeclaredField (class ch) "key") (.setAccessible true))
            ^java.nio.channels.SelectionKey k (.get f ch)
            ^java.nio.channels.SocketChannel sc (.channel k)
            addr (.getRemoteAddress sc)]
        (when (instance? java.net.InetSocketAddress addr)
          (.getHostAddress (.getAddress ^java.net.InetSocketAddress addr)))))
    (catch Exception _ nil)))

(defn telegram-principal
  "The principal of Telegram sender `user` (its `:from`): `telegram/<id>`, a
   human with no scopes."
  [user]
  {:principal/id (str "telegram/" (:id user)) :principal/kind :human :principal/scopes #{}})

(defn default-authenticate
  "Local access only. The local principal for: the stdio relay; a loopback
   socket peer of TCP or the web server; MCP over HTTP with the token of
   `<state>/mcp/http-token` from a loopback peer; a Telegram sender on a
   non-empty allowlist. A request with a forwarding header is never local
   (a proxy on the same host makes every peer loopback). Any other TCP, web
   or HTTP caller is refused; another Telegram sender (an open allowlist) is
   `telegram-principal`, which the authorizer decides about."
  [{:keys [transport remote-addr local-token headers] :as request}]
  (let [direct? (and (loopback? remote-addr) (not (forwarded? headers)))]
    (case transport
      :mcp-stdio local-principal
      (:mcp-tcp :web) (when direct? local-principal)
      :mcp-http (when (and direct? (constant-time= local-token (:bearer request))) local-principal)
      :telegram (let [user (:telegram/user request)]
                  (if (and (not (allowlist/open?)) (allowlist/allowed? user))
                    local-principal
                    (when (allowlist/allowed? user) (telegram-principal user))))
      nil)))

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
  "Set the process-wide seams, `{:authenticate f :authorize f :on-created f}` (any may be
   left out), for hosts without a daemon or a daemon without `:auth`. nil
   values reset to the defaults."
  [seams]
  (reset! configured (select-keys seams [:authenticate :authorize :on-created])))

(defn on-created
  "The on-created fn: daemon `config`'s `[:auth :on-created]`, else the
   process-wide one, else nil."
  [config]
  (or (seam (get-in config [:auth :on-created])) (seam (:on-created @configured))))

(defn check-bind!
  "Refuse a listener on `bind` (an address) that would authenticate with
   `authenticate` = `default-authenticate` while not on loopback: the default
   trusts the network position of its peers, which a non-loopback bind gives
   to everyone who can reach it."
  [listener bind authenticate]
  (when (and (identical? authenticate default-authenticate) (not (loopback? bind)))
    (throw (ex-info (str listener " on " bind " needs an authenticator (:auth :authenticate): "
                         "the default admits loopback peers only and is refused off loopback")
                    {:type ::default-off-loopback :listener listener :bind bind}))))

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
  (cond
    (local? principal)
    {:allow? true}

    ;; claims to be the process without being it: never asked, never allowed
    (forged-local? principal)
    {:allow? false :reason "A principal of kind :local that is not the process's is refused"}

    :else
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
