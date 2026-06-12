(ns dvergr.sandbox.ns.io
  "SCI injectors — the I/O surface (security-sensitive): file, fs (path-safe +
   audited), proc (capability-gated), git (worktree-scoped), env, http
   (domain-policy gated), bash (muschel), process (monitoring). Includes the
   path/domain safety policies. Split out of dvergr.sandbox (Phase 4)."
  (:require [sci.core :as sci]
            [clojure.string :as str]
            [jsonista.core :as j]
            [babashka.fs :as fs])
  (:import [java.io File]))

(declare fs-safe-resolve git-run* parse-porcelain-status parse-git-log)

(defn- audit!
  "Append an IO event to the audit log (no-op when log is nil)."
  [log op data]
  (when log
    (swap! log conj {:op op :t (System/currentTimeMillis) :data data})))

(defn sensitive-path-policy
  "Throw if path matches known-sensitive OS path patterns.
   Call this synchronously before opening a file."
  [path]
  (when (and path
             (re-find #"(?i)(\.ssh[/\\]|\.gnupg[/\\]|/etc/shadow|/etc/passwd|
                             /etc/sudoers|/proc/|/sys/|\.aws[/\\]|\.azure[/\\]|
                             \.gcloud[/\\]|/run/secrets|\.env$|\.env\.)"
                      path))
    (throw (ex-info "Access denied: sensitive path" {:path path}))))

(defn- domain-allows?
  "True if `url` is exactly an allowed origin or a path under it. Anchored so
   that an allowed `https://api.github.com` does NOT match the look-alike
   `https://api.github.com.attacker.com` (a bare prefix check would)."
  [allowed-domains url]
  (some (fn [d]
          (or (= url d)
              (str/starts-with? url (str d "/"))))
        allowed-domains))

(defn make-domain-policy
  "Return a policy fn that throws when url is not an allowed domain (exact origin
   or a path under it — see `domain-allows?`). An empty or nil allowed-domains
   set permits all domains (open)."
  [allowed-domains]
  (when (seq allowed-domains)
    (fn [url]
      (when-not (domain-allows? allowed-domains url)
        (throw (ex-info "HTTP request to unauthorized domain"
                        {:url url :allowed allowed-domains}))))))

;; ===========================================================================
;; Boundary secret injection (doc/boundary-secret-injection.md)
;;
;; An agent USES an API key without ever SEEING it: `env/get` returns an opaque
;; PLACEHOLDER; the gated HTTP egress (`do-request`) substitutes the real value
;; only just before the bytes leave — bound to a destination + slot — and scrubs
;; any reflected value out of the response. The real value lives ONLY in this
;; host-side registry (closed over the ns injectors, never an SCI value) and
;; transiently inside `do-request`. Single-point substitution ⇒ single-point
;; scrub is complete (the agent never holds plaintext to leak elsewhere).
;; ===========================================================================

(defn- b64 ^String [^String s]
  (.encodeToString (java.util.Base64/getEncoder) (.getBytes s "UTF-8")))

(defn build-secret-registry
  "Host-side secret registry from config `specs` — NEVER exposed to SCI. Each spec:
     {:name  <logical lookup name; what `env/get` is called with>
      :value <literal>      ; OR
      :env   <ENV_VAR>      ; resolved via System/getenv
      :basic-auth <[user pass]>  ; pre-encodes Authorization: Basic base64(user:pass),
                                  ; for intakes that send HTTP Basic (the placeholder
                                  ; then stands for the whole base64 credential, since
                                  ; the agent can't base64 a placeholder usefully)
      :allowed-domains   [..]
      :allowed-locations [:header :query]  ; default [:header :query]; add :body to allow
      :header-names      [\"X-Subscription-Token\"]}
   The CALLER (config/secret-specs) resolves any config-path → :value/:basic-auth
   first, so this fn only sees literals + :env. Skips specs whose value is
   unset/blank. Keyed by `:name` (fallback `:env`). Returns {} for nil/empty specs."
  [specs]
  (into {}
        (for [{:keys [name env value basic-auth allowed-domains allowed-locations header-names]} specs
              ;; KEY = the string the intake passes to `(env/get …)` — the env-var
              ;; name when there is one, else the chosen `:name` (config/basic-auth
              ;; sources still get a stable lookup key).
              :let [k (or env name)
                    v (cond
                        basic-auth (let [[u p] basic-auth]
                                     ;; Basic-auth: the secret may be in either slot
                                     ;; (zulip = email:KEY, companies-house = KEY:).
                                     ;; Encode iff at least one slot is set.
                                     (when (some (complement str/blank?) basic-auth)
                                       (b64 (str u ":" p))))
                        (not (str/blank? value)) value
                        env        (System/getenv env))]
              :when (and k (not (str/blank? v)))]
          [k {:placeholder       (str "@@secret:" k "@@")
              :value             v
              :allowed-domains   (set allowed-domains)
              :allowed-locations (set (map keyword (or (seq allowed-locations) [:header :query])))
              :header-names      (set header-names)}])))

(defn- contains-ph? [placeholder v]
  (and (string? v) (str/includes? v placeholder)))

(defn- substitute-one
  "Replace `spec`'s placeholder with its real value in `opts` (headers/query/body),
   enforcing the secret's domain + slot policy. Throws on ANY violation (never
   strip-and-send). Audits placeholder slots (never values). Returns rewritten opts."
  [audit-log url opts {:keys [placeholder value allowed-domains allowed-locations header-names]}]
  (let [hit-header (some (fn [[_ v]] (contains-ph? placeholder v)) (:headers opts))
        hit-query  (some (fn [[_ v]] (contains-ph? placeholder v)) (:query-params opts))
        hit-body   (contains-ph? placeholder (:body opts))]
    (if-not (or hit-header hit-query hit-body)
      opts
      (do
        (when (and (seq allowed-domains) (not (domain-allows? allowed-domains url)))
          (audit! audit-log :http/secret-denied {:reason :domain :url url})
          (throw (ex-info "secret not permitted for this destination"
                          {:muschel/denied true :url url :allowed allowed-domains})))
        (cond-> opts
          hit-header
          (update :headers
                  (fn [hs]
                    (reduce-kv
                     (fn [m k v]
                       (if (contains-ph? placeholder v)
                         (do (when-not (and (contains? allowed-locations :header)
                                            (or (empty? header-names) (contains? header-names k)))
                               (throw (ex-info "secret not permitted in this header"
                                               {:muschel/denied true :header k})))
                             (audit! audit-log :http/secret-injected {:slot [:header k]})
                             (assoc m k (str/replace v placeholder value)))
                         (assoc m k v)))
                     {} hs)))
          hit-query
          (update :query-params
                  (fn [qs]
                    (when-not (contains? allowed-locations :query)
                      (throw (ex-info "secret not permitted in query" {:muschel/denied true})))
                    (audit! audit-log :http/secret-injected {:slot :query})
                    (reduce-kv (fn [m k v]
                                 (assoc m k (if (contains-ph? placeholder v)
                                              (str/replace v placeholder value) v)))
                               {} qs)))
          hit-body
          (as-> o
                (do (when-not (contains? allowed-locations :body)
                      (throw (ex-info "secret not permitted in body" {:muschel/denied true})))
                    (audit! audit-log :http/secret-injected {:slot :body})
                    (update o :body str/replace placeholder value))))))))

(defn- substitute-secrets!
  "Apply every registry secret's substitution to `opts`. Throws on policy violation."
  [secrets audit-log url opts]
  (reduce (fn [o [_ spec]] (substitute-one audit-log url o spec)) opts (or secrets {})))

(defn- scrub-response
  "Re-mask any real secret value reflected in the response body/headers back to its
   placeholder before it reaches SCI — the only re-entry path for the plaintext."
  [secrets resp]
  (if (empty? secrets)
    resp
    (let [pairs (keep (fn [[_ {:keys [value placeholder]}]] (when value [value placeholder])) secrets)
          scrub (fn [s] (if (string? s)
                          (reduce (fn [s [v ph]] (str/replace s v ph)) s pairs)
                          s))]
      (-> resp
          (update :body scrub)
          (update :headers (fn [hs] (into {} (map (fn [[k v]] [k (scrub v)]) hs))))))))

(defn- internal-address? [^java.net.InetAddress addr]
  (or (.isLoopbackAddress addr) (.isLinkLocalAddress addr)
      (.isSiteLocalAddress addr) (.isAnyLocalAddress addr)
      (.isMulticastAddress addr)
      (let [h (.getHostAddress addr)]
        (or (str/starts-with? h "169.254.")          ; link-local / cloud metadata
            (str/starts-with? h "127.")
            (str/starts-with? h "0.")))))

(defn ssrf-guard!
  "Reject non-http(s) schemes and any URL whose host resolves to a loopback /
   private / link-local / cloud-metadata (169.254.169.254) address — even when
   the per-agent domain allowlist is open. Blocks SSRF to internal services. Public
   hosts pass. (Does NOT re-check across redirects — a residual; see security audit
   H1. For full containment run the daemon network-namespaced.)"
  [url]
  (let [uri    (java.net.URI. (str url))
        scheme (some-> (.getScheme uri) str/lower-case)
        host   (.getHost uri)]
    (when-not (#{"http" "https"} scheme)
      (throw (ex-info "HTTP scheme not allowed (only http/https)" {:url url :scheme scheme})))
    (when (str/blank? host)
      (throw (ex-info "HTTP url has no resolvable host" {:url url})))
    (doseq [^java.net.InetAddress addr (java.net.InetAddress/getAllByName host)]
      (when (internal-address? addr)
        (throw (ex-info "HTTP request to internal/loopback address blocked (SSRF)"
                        {:url url :resolved (.getHostAddress addr)}))))))

(defn add-fs-ns!
  "Expose rich filesystem operations as 'fs namespace in SCI.

   Backed by babashka.fs. All user-supplied paths are canonicalised via
   File.getCanonicalFile before use, which resolves '..' components and follows
   symlinks.  Any path that resolves outside base-path throws immediately.
   Sensitive OS path patterns (/etc/passwd, .ssh/, .env, etc.) are also blocked.

   :base-path  - absolute root for all relative path resolution (default: user.dir)
   :audit-log  - atom from (make-audit-log); records every FS op with timestamp

   Usage in SCI:
     (require '[fs])
     (fs/ls \"src\")
     ;; => [{:name \"core.clj\" :path \"/abs/src/core.clj\" :type :file :size 1234 :modified \"...\"}]
     (fs/ls \"src\" \"*.clj\")
     (fs/glob \"**/*.clj\")
     (fs/stat \"src/core.clj\")    ; => {:path :type :size :modified}
     (fs/mkdir \"new/nested/dir\")
     (fs/delete \"tmp/scratch.clj\")
     (fs/move \"old.clj\" \"new.clj\")
     (fs/copy \"src.clj\" \"dst.clj\")
     (fs/read \"src/core.clj\")
     (fs/write \"src/out.clj\" content)"
  [sci-ctx & {:keys [base-path audit-log]
              :or   {base-path ((requiring-resolve 'dvergr.substrate.git/safe-workspace-root))}}]
  (require 'babashka.fs)
  (let [fs-ns          (find-ns 'babashka.fs)
        r              (fn [s] @(ns-resolve fs-ns s))
        base-canonical (-> (java.io.File. (str base-path)) .getCanonicalFile)
        ;; Path-clamp every user path: canonical check + sensitive-path guard.
        sr             (fn [p] (let [ps (str p)] (sensitive-path-policy ps) (fs-safe-resolve base-canonical ps)))
        bb-parent      (r 'parent)
        bb-create-dirs (r 'create-dirs)
        mkdir          (fn [p] (let [f (sr p)] (audit! audit-log :fs/mkdir {:path (str f)}) (str (bb-create-dirs f))))
        del            (fn [bb] (fn [p] (let [f (sr p)] (audit! audit-log :fs/delete {:path (str f)}) (bb f))))
        cpmv           (fn [op bb] (fn [a b & m] (let [fa (sr a) fb (sr b)]
                                                   (audit! audit-log op {:src (str fa) :dst (str fb)})
                                                   (apply bb fa fb m) (str fb))))
        pred           (fn [bb] (fn [p] (bb (sr p))))]
    ;; The real babashka.fs SUBSET, every path clamped to base-path. Returns strings
    ;; (not Path objects) so SCI agents get serialisable values. Content read/write
    ;; is `slurp`/`spit` (below), as in real Clojure — NOT an fs fn.
    (sci/add-namespace! sci-ctx 'babashka.fs
                        {'list-dir           (fn [d & more] (audit! audit-log :fs/ls {:path (str (sr d))})
                                               (mapv str (apply (r 'list-dir) (sr d) more)))
                         'glob               (fn [d pat & more] (mapv str (apply (r 'glob) (sr d) pat more)))
                         'exists?            (pred (r 'exists?))
                         'directory?         (pred (r 'directory?))
                         'regular-file?      (pred (r 'regular-file?))
                         'sym-link?          (pred (r 'sym-link?))
                         'size               (pred (r 'size))
                         'last-modified-time (fn [p] (str ((r 'last-modified-time) (sr p))))
                         'create-dir         mkdir
                         'create-dirs        mkdir
                         'delete             (del (r 'delete))
                         'delete-if-exists   (del (r 'delete-if-exists))
                         'delete-tree        (del (r 'delete-tree))
                         'move               (cpmv :fs/move (r 'move))
                         'copy               (cpmv :fs/copy (r 'copy))
                         'copy-tree          (cpmv :fs/copy (r 'copy-tree))
                         'parent             (fn [p] (some-> (bb-parent (sr p)) str))
                         'file-name          (fn [p] (str ((r 'file-name) p)))
                         'absolutize         (fn [p] (str (sr p)))
                         'canonicalize       (fn [p] (str (sr p)))})
    ;; File CONTENT I/O under the clojure.core names the model reaches for, sandboxed.
    (sci/merge-opts sci-ctx
                    {:namespaces
                     {'clojure.core
                      {'slurp (fn [p & opts] (let [f (sr p)] (audit! audit-log :fs/read {:path (str f)})
                                                  (apply clojure.core/slurp f opts)))
                       'spit  (fn [p content & opts]
                                (let [f (sr p)] (audit! audit-log :fs/write {:path (str f)})
                                     (when-let [par (bb-parent f)] (bb-create-dirs par))
                                     (apply clojure.core/spit f content opts) (str f)))}}})))

(defn add-git-ns!
  "Expose structured git operations as 'git namespace in SCI.

   Agents working in yggdrasil worktrees need programmatic git access
   to understand workspace state before committing. This namespace returns
   structured Clojure data rather than raw strings.

   :base-path - git working directory (default: user.dir)
   :audit-log - atom from (make-audit-log); records write ops (add, commit)

   Usage in SCI:
     (require '[git])

     (git/status)
     ;; => {:branch \"main\" :staged [\"foo.clj\"] :unstaged [] :untracked [\"bar.clj\"]}

     (git/log {:n 5})
     ;; => [{:hash \"abc1234\" :message \"Fix bug\" :author \"Alice\" :date \"2026-04-15...\"}]

     (git/diff)                ; unstaged changes as string
     (git/diff \"src/foo.clj\") ; single file diff

     (git/add \"src/foo.clj\")  ; stage file(s)
     (git/add \".\")            ; stage all

     (git/commit \"Add feature\")
     ;; => \"[main abc1234] Add feature\""
  [sci-ctx & {:keys [base-path audit-log]
              :or   {base-path ((requiring-resolve 'dvergr.substrate.git/safe-workspace-root))}}]
  (let [run!      (fn [& args] (apply git-run* base-path args))

        status-fn (fn []
                    (parse-porcelain-status
                     (run! "status" "--porcelain=v1" "--branch")))

        log-fn    (fn [& [opts]]
                    (let [n (str "-" (or (:n opts) 10))]
                      (parse-git-log
                       (run! "log" "--format=%H|%s|%an|%ai" n))))

        diff-fn   (fn [& args]
                    (if (seq args)
                      (apply run! "diff" args)
                      (run! "diff")))

        add-fn    (fn [& paths]
                    (audit! audit-log :git/add {:paths (vec paths)})
                    (apply run! "add" paths)
                    :ok)

        commit-fn (fn [message & [opts]]
                    (audit! audit-log :git/commit {:message message})
                    (let [args (cond-> ["commit" "-m" message]
                                 (:author opts) (into ["--author" (:author opts)]))]
                      (str/trim (apply run! args))))]

    (sci/add-namespace! sci-ctx 'git
                        {'status status-fn
                         'log    log-fn
                         'diff   diff-fn
                         'add    add-fn
                         'commit commit-fn})))

(defn add-env-ns!
  "Expose config-scoped key access as the 'env namespace in SCI. Resolves ONLY from
   the per-agent `user-config` map — the sandbox has **NO access to the host process
   environment** (`System/getenv`). So the daemon's own secrets (LLM provider keys,
   cloud/DB creds, the Telegram token, …) can never be read from inside SCI, by any
   name. Grant a key to an agent explicitly via `user-config`.

   (Boundary key-INJECTION — `env/get` returning an opaque placeholder that the HTTP
   primitive substitutes for the real value only at egress to a bound domain — is the
   planned next step; see doc/boundary-secret-injection.md. Until then a granted key
   is a real value the agent can read, so grant narrowly.)

   Usage in SCI:
     (require '[env])
     (env/get \"BRAVE_API_KEY\")        ;; => value if granted in user-config, else nil
     (env/get \"API_KEY\" \"default\")   ;; with fallback
     (env/keys)                        ;; list granted keys (no values)
     (env/set \"KEY\" \"value\")         ;; store in user config (session-local)"
  [sci-ctx & {:keys [user-config secrets]}]
  (let [config-atom (or user-config (atom {}))
        ;; A configured boundary-injection SECRET resolves to its opaque
        ;; PLACEHOLDER (never the real value) — the HTTP egress substitutes the
        ;; real value at the destination. Non-secret granted keys still return
        ;; their real config value.
        get-1  (fn [key]
                 (let [k (str key)]
                   (if-let [s (get secrets k)]
                     (:placeholder s)
                     (or (get @config-atom k) (get @config-atom (keyword key))))))
        get-fn (fn
                 ([key]         (get-1 key))
                 ([key default] (or (get-1 key) default)))
        set-fn (fn [key value] (swap! config-atom assoc (str key) value) :ok)
        keys-fn (fn [] (vec (distinct (concat (map str (keys @config-atom))
                                              (keys (or secrets {}))))))]
    (sci/add-namespace! sci-ctx 'env
                        {'get  get-fn
                         'set  set-fn
                         'keys keys-fn})))

(defn add-http-ns!
  "Expose HTTP client as 'http namespace in SCI.

   Agents can make HTTP requests to build integrations.
   Responses are returned as maps with :status, :headers, :body.
   JSON bodies are auto-parsed.

   :audit-log      - atom from (make-audit-log); records every request (method + url, no body)
   :allowed-domains - set of URL prefix strings; non-empty set restricts outbound requests.
                      Empty or nil permits all domains (open).
                      Example: #{\"https://api.github.com\" \"https://slack.com\"}

   Usage in SCI:
     (require '[http])
     (http/get \"https://api.github.com/repos/clojure/clojure\")
     (http/post \"https://slack.com/api/chat.postMessage\"
       {:headers {\"Authorization\" (str \"Bearer \" (env/get \"MY_SERVICE_TOKEN\"))}
        :json {:channel \"#general\" :text \"Hello from dvergr\"}})
     (http/request {:url \"...\" :method :put :headers {...} :body \"...\"})"
  [sci-ctx & {:keys [audit-log allowed-domains secrets]}]
  (let [domain-check (make-domain-policy allowed-domains)
        do-request
        (fn [{:keys [url method headers body json query-params timeout]
              :or {method :get timeout 30000}}]
          ;; Audit first (even blocked requests should appear in the log)
          ;; then enforce domain policy — order matters for forensics
          (audit! audit-log :http/request {:method method :url url})
          (when domain-check (domain-check url))
          (ssrf-guard! url)
          (require 'hato.client)
          (let [hato-request (requiring-resolve 'hato.client/request)
                opts (cond-> {:url url
                              :method method
                              :connect-timeout timeout
                              :socket-timeout timeout}
                       headers (assoc :headers headers)
                       body (assoc :body body)
                       json (-> (assoc :content-type :json
                                       :body (j/write-value-as-string json))
                                (update :headers merge {"Content-Type" "application/json"}))
                       query-params (assoc :query-params query-params))
                ;; Boundary secret injection: substitute placeholders → real values
                ;; AFTER domain/ssrf guards, just before the bytes leave; the agent
                ;; never holds plaintext. (No-op when no secrets configured.)
                opts (substitute-secrets! secrets audit-log url opts)
                resp (hato-request opts)
                ;; Scrub any reflected secret value back to its placeholder — the
                ;; only path the plaintext could re-enter the sandbox.
                resp (scrub-response secrets resp)]
            ;; Match babashka.http-client: :body is the RAW string (not auto-parsed).
            ;; The agent parses explicitly — (cheshire.core/parse-string (:body r) true).
            {:status (:status resp)
             :headers (into {} (:headers resp))
             :body (:body resp)}))]
    (sci/add-namespace! sci-ctx 'babashka.http-client
                        {'request do-request
                         'get     (fn [url & [opts]] (do-request (merge {:url url :method :get} opts)))
                         'post    (fn [url & [opts]] (do-request (merge {:url url :method :post} opts)))
                         'put     (fn [url & [opts]] (do-request (merge {:url url :method :put} opts)))
                         'patch   (fn [url & [opts]] (do-request (merge {:url url :method :patch} opts)))
                         'head    (fn [url & [opts]] (do-request (merge {:url url :method :head} opts)))
                         'delete  (fn [url & [opts]] (do-request (merge {:url url :method :delete} opts)))})))

(defn add-bash-ns!
  "Expose intake.bash (muschel-backed shell) to SCI, bound to a chat-ctx.

   Agents call:
     (require '[intake.bash :as bash])
     (bash/run \"git log --oneline -3\")     ; execute, returns map
     (bash/check \"rm -rf /\")               ; permit-check only
     (bash/builtins)                          ; what Clojure builtins
                                              ;   are dispatched in-host
     (bash/allowlist)                         ; what system binaries
                                              ;   the host will exec

   The `shell` JSON-schema tool wraps the same `run`, so a worker
   picks whichever door fits the call site — direct tool for typical
   ops, SCI fn for pipelines that mix bash and Clojure transforms."
  [sci-ctx chat-ctx]
  (require 'dvergr.intake.bash)
  (let [run-fn       (var-get (ns-resolve 'dvergr.intake.bash 'run))
        check-fn     (var-get (ns-resolve 'dvergr.intake.bash 'check))
        builtins-fn  (var-get (ns-resolve 'dvergr.intake.bash 'builtins))
        allowlist-fn (var-get (ns-resolve 'dvergr.intake.bash 'allowlist))
        run          (fn [cmd & opts] (apply run-fn chat-ctx cmd opts)) ; → {:stdout :stderr :exit …}
        ->result     (fn [m] (if (:error m)
                               {:exit (or (:exit m) 1) :out "" :err (:error m)}
                               {:exit (:exit m) :out (or (:stdout m) "") :err (or (:stderr m) "")}))
        ;; babashka.process/shell + sh, muschel-backed, CAPTURE → {:exit :out :err}.
        ;; Muschel parses the bash string in-process (pipes/builtins/redirects) — a
        ;; richer shell than bb's /bin/sh shell-out, and jailed. The advanced
        ;; babashka.process surface (async process objects, real pipelines, streaming)
        ;; is intentionally absent in a sandbox.
        shell        (fn [& args]
                       (let [args (if (map? (first args)) (rest args) args)] ; ignore an opts map
                         (->result (run (str/join " " (map str args))))))]
    (sci/add-namespace! sci-ctx 'babashka.process
                        {'shell shell 'sh shell})
    ;; muschel-specific introspection (not part of babashka.process)
    (sci/add-namespace! sci-ctx 'dvergr.shell
                        {'run run 'check check-fn 'builtins builtins-fn 'allowlist allowlist-fn})))

(defn add-process-ns!
  "Expose the deliberable-process surface to SCI.

   Bound to a chat-ctx so the wrapper fns operate on the right registry.
   Vár can call these from clojure_eval to see + steer her own long-
   running work:

     (require '[processes])
     (processes/list)                       ; → vector of snapshots
     (processes/snapshot some-pid)          ; → single snapshot
     (processes/directive! pid {:type :abort :reason \"…\"})
     (processes/directive! pid {:type :extend-budget :dollars 0.10})
     (processes/directive! pid {:type :refocus :hint \"…\"})"
  [sci-ctx chat-ctx]
  (let [proc-ns   (find-ns 'dvergr.agent.process)
        list-fn   (var-get (ns-resolve proc-ns 'list-processes))
        snap-fn   (var-get (ns-resolve proc-ns 'snapshot))
        get-fn    (var-get (ns-resolve proc-ns 'get-process))
        dir-fn    (var-get (ns-resolve proc-ns 'directive!))]
    (sci/add-namespace! sci-ctx 'processes
                        {'list       (fn [] (list-fn chat-ctx))
                         'snapshot   (fn [pid] (when-let [p (get-fn chat-ctx pid)]
                                                 (snap-fn p)))
                         'directive! (fn [pid d] (dir-fn chat-ctx pid d))})))

(defn- fs-safe-resolve
  "Resolve user-supplied path against base-dir, canonicalising symlinks and `..`
   via File.getCanonicalFile so that the result is a real path with no traversal
   components.  Throws ex-info if the canonical result would escape base-dir.

   base-canonical must already be canonical (computed once at ns-io/add-fs-ns! time)."
  ^java.io.File [^java.io.File base-canonical user-path]
  (let [resolved (-> (java.io.File. base-canonical (str user-path))
                     .getCanonicalFile)]
    (when-not (.startsWith (.toPath resolved) (.toPath base-canonical))
      (throw (ex-info "Path escape attempt: resolved path is outside sandbox"
                      {:path user-path :base (str base-canonical)})))
    resolved))

(defn- git-run*
  "Run git in base-path. Returns stdout string or throws on non-zero exit."
  [base-path & args]
  (let [all-args (into ["git"] (map str args))
        pb       (doto (ProcessBuilder. ^java.util.List all-args)
                   (.directory (java.io.File. (str base-path))))
        proc     (.start pb)
        out      (future (slurp (.getInputStream proc)))
        err      (future (slurp (.getErrorStream proc)))
        exit     (.waitFor proc)]
    (if (zero? exit)
      @out
      (throw (ex-info (str "git " (first args) " failed")
                      {:exit exit :out @out :err @err :args args})))))

(defn- parse-porcelain-status
  "Parse `git status --porcelain=v1 --branch` into a structured map."
  [output]
  (let [lines       (str/split-lines output)
        branch-line (first (filter #(str/starts-with? % "## ") lines))
        branch      (when branch-line
                      (-> (subs branch-line 3)
                          (str/split #"\.\.\.")
                          first
                          str/trim))
        file-lines  (remove #(str/starts-with? % "##") lines)
        entries     (->> file-lines
                         (remove str/blank?)
                         (mapv (fn [line]
                                 (when (>= (count line) 4)
                                   (let [xy   (subs line 0 2)
                                         x    (subs xy 0 1)
                                         y    (subs xy 1 2)
                                         path (str/trim (subs line 3))]
                                     {:path       path
                                      ;; X column: staged change (not untracked/ignored/unmodified)
                                      :staged?    (and (not= " " x) (not= "?" x) (not= "!" x))
                                      ;; Y column: unstaged change (not untracked/ignored/unmodified)
                                      :unstaged?  (and (not= " " y) (not= "?" y) (not= "!" y))
                                      :untracked? (= "??" xy)}))))
                         (remove nil?))]
    {:branch    (or branch "unknown")
     :staged    (mapv :path (filter :staged? entries))
     :unstaged  (mapv :path (filter :unstaged? entries))
     :untracked (mapv :path (filter :untracked? entries))}))

(defn- parse-git-log
  "Parse `git log --format=%H|%s|%an|%ai` output into vector of maps."
  [output]
  (->> (str/split-lines output)
       (remove str/blank?)
       (mapv (fn [line]
               (let [[hash msg author date] (str/split line #"\|" 4)]
                 {:hash    (str/trim (str hash))
                  :message (str/trim (str msg))
                  :author  (str/trim (str author))
                  :date    (str/trim (str date))})))))


