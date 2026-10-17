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
      :principal/room   \"…\" :principal/tools #{…} ; a run token's pins (`mint-run-token!`)
      :principal/claims {…}}               ; the authenticator's evidence, opaque here

   `local-principal` is the process itself: whoever holds the daemon (its REPL,
   the TUI, room-run), spawned it (stdio), or presents its credential, the
   local token (`local-token`, `<home>/local-token`, mode 0600). Where a
   request comes from grants nothing: a loopback peer without the token is
   refused like any other. It is never sent to the authorizer. It is recognized by identity (an
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
                      :bearer \"…\"                  ; the credential presented
                      :remote-addr \"…\" :headers {…} ; informational only
                      :local-token \"…\"             ; when a listener names its own
                      :telegram/user {…} :telegram/chat-id … :telegram/owner? bool}
        nil refuses the caller (401, a closed connection, a refused command).
        Configuring one REPLACES `default-authenticate`; a hosted
        authenticator should hand out the local principal to nobody, and
        should map a bearer it does not know through `run-token-principal`
        so dvergr's own agent runs keep working.

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
        local one, so the host can grant its creator. It may be called again
        for the same principal and resource (a retry): make it idempotent."
  (:require [clojure.string :as str]
            [dvergr.security.allowlist :as allowlist]
            [taoensso.telemere :as tel]))

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

(defn- constant-time=
  "Equal, compared in constant time; never for a blank or missing value."
  [^String a ^String b]
  (and (not (str/blank? a)) (not (str/blank? b))
       (java.security.MessageDigest/isEqual (.getBytes a "UTF-8") (.getBytes b "UTF-8"))))

(defn bearer
  "The token of an `Authorization: Bearer …` header value, or nil."
  [authorization]
  (some->> authorization (re-matches #"(?i)Bearer\s+(\S+)") second))

;; ---- the local token: the owner's credential --------------------------------

(defn random-token
  "32 random bytes, hex."
  []
  (let [b (byte-array 32)]
    (.nextBytes (java.security.SecureRandom.) b)
    (.formatHex (java.util.HexFormat/of) b)))

(def ^:private owner-only (java.nio.file.attribute.PosixFilePermissions/fromString "rw-------"))
(def ^:private owner-dir (java.nio.file.attribute.PosixFilePermissions/fromString "rwx------"))

(defn- posix? [^java.nio.file.Path path]
  (try (some? (java.nio.file.Files/getPosixFilePermissions
               path (into-array java.nio.file.LinkOption [java.nio.file.LinkOption/NOFOLLOW_LINKS])))
       (catch UnsupportedOperationException _ false)))

(defn private-dir!
  "Make directory `dir` (creating it) readable by its owner only (0700)."
  [dir]
  (let [path (.toPath (java.io.File. (str dir)))]
    (java.nio.file.Files/createDirectories path (make-array java.nio.file.attribute.FileAttribute 0))
    (when (posix? path)
      (java.nio.file.Files/setPosixFilePermissions path owner-dir))
    path))

(defonce ^{:doc "Why the local token was last replaced (an exposed file), for the
  refusals clients see; nil when it never was."}
  rotation-notice
  (atom nil))

(defonce ^{:doc "Bumped whenever a token file is written anew: browser sessions opened
  before end with it (`dvergr.web.server`)."}
  token-generation
  (atom 0))

(defn- write-private!
  "Write `content` to `f` as the owner's alone: a new temp file created with
   mode 0600 (never readable on the way), then moved over `f` atomically."
  [^java.io.File f ^String content]
  (let [dir (private-dir! (.getParentFile f))
        posix (posix? dir)
        tmp (if posix
              (java.nio.file.Files/createTempFile dir ".token-" ".tmp"
                                                  (into-array java.nio.file.attribute.FileAttribute
                                                              [(java.nio.file.attribute.PosixFilePermissions/asFileAttribute owner-only)]))
              (java.nio.file.Files/createTempFile dir ".token-" ".tmp" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (java.nio.file.Files/writeString tmp content (make-array java.nio.file.OpenOption 0))
    (java.nio.file.Files/move tmp (.toPath f) (into-array java.nio.file.CopyOption
                                                          [java.nio.file.StandardCopyOption/ATOMIC_MOVE
                                                           java.nio.file.StandardCopyOption/REPLACE_EXISTING]))
    (swap! token-generation inc)))

(defn- read-private
  "The token in `f`, or nil when it is missing, blank, a symbolic link, or
   readable by others than its owner (then it is no secret any more: the
   caller makes a new one)."
  [^java.io.File f]
  (let [path (.toPath f)
        nofollow (into-array java.nio.file.LinkOption [java.nio.file.LinkOption/NOFOLLOW_LINKS])]
    (cond
      (not (java.nio.file.Files/exists path nofollow)) nil
      (java.nio.file.Files/isSymbolicLink path)
      (throw (ex-info (str f " is a symbolic link; refusing it as a credential") {:type ::token-symlink :file (str f)}))
      ;; only group and other bits matter (0400 is as private as 0600)
      (and (posix? path)
           (some (set (java.nio.file.Files/getPosixFilePermissions path nofollow))
                 [java.nio.file.attribute.PosixFilePermission/GROUP_READ
                  java.nio.file.attribute.PosixFilePermission/GROUP_WRITE
                  java.nio.file.attribute.PosixFilePermission/OTHERS_READ
                  java.nio.file.attribute.PosixFilePermission/OTHERS_WRITE]))
      (do (tel/log! {:level :warn :id ::token-exposed :data {:file (str f)}}
                    "A token file was readable by others; it is replaced with a new token")
          (reset! rotation-notice
                  ;; generic: told to callers without a credential, so no path
                  "the local token was replaced because its file was readable by others; read it again")
          nil)
      :else (not-empty (str/trim (slurp f))))))

(defn ensure-token!
  "The token in `file`, created (random, mode 0600, its directory 0700) when
   missing, blank, or readable by others."
  [file]
  (let [f (java.io.File. (str file))]
    (or (read-private f)
        (let [t (random-token)] (write-private! f t) t))))

(defn local-token-file
  "Where the local token of the state root `home` lives: `<home>/local-token`."
  [home]
  (java.io.File. (str home) "local-token"))

(defn local-token
  "The local token of the state root `home` (default: this process's,
   `dvergr.substrate.paths/home`), created (mode 0600) when missing. A home
   from before it had one keeps the token of its `mcp/http-token`, so a
   client configured with that one still connects (and now reaches the web
   and TCP with it too)."
  ([] (local-token ((requiring-resolve 'dvergr.substrate.paths/home))))
  ([home]
   (let [f (local-token-file home)
         old (java.io.File. (str home) "mcp/http-token")]
     (or (read-private f)
         (when-let [t (and (.isFile old) (read-private old))] (write-private! f t) t)
         (ensure-token! f)))))

;; ---- run tokens: an agent's credential for one invocation --------------------

(defonce ^:private run-tokens
  ;; token -> {:principal :expires-at :on-revoke #{fn}}
  (atom {}))

(def ^:private reserved-run-claim-keys
  #{:principal/id :principal/actor :principal/room :principal/tools
    :principal/kind :principal/scopes ::local})

(defn mint-run-token!
  "A token for one invocation of an agent that reaches dvergr over MCP (a
   `claude -p` candidate through `bin/dvergr-mcp`): it authenticates as
   `(agent-principal actor)`, pinned to room `room` (an id string) and the
   tool names `tools`, until `revoke-run-token!` or `ttl-ms` (default an
   hour). It carries no scope of its own: the pins are all it may use, over
   MCP only. Optional `claims` is a map merged into the principal and passed
   untouched to the host authorizer. It may not contain :principal/id,
   :principal/actor, :principal/room, :principal/tools, :principal/kind,
   :principal/scopes or :dvergr.principal/local. Returns the token."
  [{:keys [actor room tools ttl-ms claims] :or {ttl-ms 3600000}}]
  (when-not (or (nil? claims) (map? claims))
    (throw (ex-info "Run token claims must be a map" {:type ::invalid-run-claims})))
  (when-let [reserved (not-empty (filterv reserved-run-claim-keys (keys claims)))]
    (throw (ex-info "Run token claims may not override dvergr authority"
                    {:type ::reserved-run-claims :keys reserved})))
  (let [t (random-token)]
    (swap! run-tokens assoc t
           {:principal (assoc (merge (agent-principal (or actor :agent)) claims)
                              :principal/id (str "run/" (subs t 0 16))
                              :principal/actor (or actor :agent)
                              :principal/room (str room)
                              :principal/tools (set (map str tools)))
            :expires-at (+ (System/currentTimeMillis) ttl-ms)})
    t))

(defn on-revoke!
  "Call `(f)` when run token `t` ends (revoked or expired): a connection
   authenticated with it closes."
  [t f]
  (swap! run-tokens (fn [m] (if (contains? m t) (update-in m [t :on-revoke] (fnil conj #{}) f) m)))
  nil)

(defn revoke-run-token!
  "End run token `t`: it authenticates nobody from now on, and what it
   authenticated is closed."
  [t]
  (let [[old _] (swap-vals! run-tokens dissoc t)]
    (doseq [f (get-in old [t :on-revoke])]
      (try (f) (catch Exception _ nil))))
  nil)

(defn run-token?
  "Whether `t` is a live run token."
  [t]
  (boolean (and t (get @run-tokens t))))

(defn run-token-principal
  "The principal of run token `t`, or nil (unknown, revoked or expired). A
   host authenticator that receives a bearer it does not know delegates to
   this, so agents dvergr runs keep their credentials."
  [t]
  (when t
    (let [{:keys [principal expires-at]} (get @run-tokens t)]
      (cond
        (nil? principal) nil
        (< expires-at (System/currentTimeMillis)) (do (revoke-run-token! t) nil)
        :else principal))))

(defn- live-run?
  "Whether the run principal `principal` still has a live token."
  [principal]
  (let [id (:principal/id principal)]
    (boolean (some (fn [[_ {:keys [expires-at] p :principal}]]
                     (and (= id (:principal/id p)) (>= expires-at (System/currentTimeMillis))))
                   @run-tokens))))

(defn pinned?
  "Whether `principal` is pinned to one room (a run token's)."
  [principal]
  (some? (:principal/room principal)))

;; ---- the default authenticator: credentials only -----------------------------

(defn telegram-principal
  "The principal of Telegram sender `user` (its `:from`): `telegram/<id>`, a
   human with no scopes."
  [user]
  {:principal/id (str "telegram/" (:id user)) :principal/kind :human :principal/scopes #{}})

(defn default-authenticate
  "Credentials only; where a request comes from grants nothing.
   - the stdio transport in this process (`run-stdio`): its owner spawned it,
     the local principal;
   - a bearer (`:bearer`; TCP's first line, HTTP's `Authorization`, the web's
     cookie) that is a live run token: that run's agent principal; that is
     the local token (`:local-token`, else `local-token`): the local
     principal; anything else: refused;
   - a Telegram sender: the local principal when the host lists it as owner
     (`:telegram/owner?`, from `:telegram {:owners [...]}`), else
     `telegram-principal` when the allowlist is not empty and lists it, else
     refused."
  [{:keys [transport bearer] given-token :local-token :as request}]
  (case transport
    :mcp-stdio local-principal
    :telegram (let [user (:telegram/user request)]
                (cond
                  (:telegram/owner? request) local-principal
                  (and (not (allowlist/open?)) (allowlist/allowed? user)) (telegram-principal user)
                  :else nil))
    (when bearer
      (or (run-token-principal bearer)
          (when (constant-time= (or given-token (local-token)) bearer) local-principal)))))

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

    ;; a run token that ended authorizes nothing more (an open stream, a session)
    (and (pinned? principal) (not (live-run? principal)))
    {:allow? false :reason "This run token has ended"}

    ;; A run token works in its room only. ops marks a merge's parent check
    ;; with the fork it already authorized; the host still sees the parent.
    (and (pinned? principal)
         (not= (:principal/room principal)
               (if (and (= :room/merge (:op request)) (= :merge (:action request)))
                 (or (:via-room (:resolved request)) (:room (:resolved request)))
                 (:room (:resolved request)))))
    {:allow? false :reason (str "This run token is for room " (:principal/room principal) " only")}

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
