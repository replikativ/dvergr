(ns dvergr.sandbox.ns.io
  "SCI injectors — the I/O surface (security-sensitive): file, fs (path-safe +
   audited), proc (capability-gated), git (worktree-scoped), env, http
   (domain-policy gated), bash (muschel), process (monitoring). Includes the
   path/domain safety policies. Split out of dvergr.sandbox (Phase 4)."
  (:require [dvergr.substrate.load :as load]
            [sci.core :as sci]
            [clojure.string :as str]
            [jsonista.core :as j]
            [babashka.fs :as fs]
            [muschel.fs :as mfs]
            [dvergr.effects :as effects]
            [dvergr.io.acquisition :as acquisition]
            [org.replikativ.spindel.engine.core :as ec]
            [dvergr.sandbox.ns.doc :as doc])
  (:import [java.io File]))

(declare fs-safe-resolve git-run* worktree-top in-git-metadata? parse-porcelain-status parse-git-log git-log-format)

(defn install-http-fixture!
  "Host-only world setup: install an immutable offline capability in the current
   Spindel context before constructing candidate interpreters. Forks inherit it;
   it is not a durable function serialization scheme. Reconstruct via WorldSetup.
   Existing interpreters retain their installed transport. env contains dummy,
   non-secret configuration for the simulated service."
  [{:keys [id transport env] :as fixture}]
  (when-not (and (uuid? id) (fn? transport) (map? env)
                 (every? string? (concat (keys env) (vals env))))
    (throw (ex-info "Invalid host HTTP fixture" {})))
  (ec/swap-state! [::http-fixture] (constantly fixture))
  id)

(defn- fx
  "Perform one effect through the sandbox's boundary (`dvergr.effects`)."
  ([boundary kind resource f] (effects/perform! boundary {:effect kind :resource resource} f))
  ([boundary kind resource result-of f]
   (effects/perform! boundary {:effect kind :resource resource :result-of result-of} f)))

(defn- sized-write-fx
  "Perform a write of `content`: its size in bytes is part of the effect, what a
   quota counts."
  [boundary resource content f]
  (effects/perform! boundary {:effect :fs/write :resource resource :result-of (constantly (str content))
                              :bytes (alength (.getBytes (str content) "UTF-8"))}
                    f))

(defn sensitive-path-policy
  "Throw if path matches known-sensitive OS path patterns.
   Call this synchronously before opening a file.

   The pattern MUST stay on one line. A Clojure regex literal is NOT
   whitespace-insensitive (no `(?x)`), so a newline+indentation inside the
   alternation becomes part of an alternative: when this was wrapped across
   three lines, `/etc/sudoers` and `~/.gcloud/` silently required a leading
   `\\n<spaces>` to match and so were never blocked at all — a dead branch that
   read as covered. Keep it flat.

   `.git` is on it because a repository's config and hooks are host command
   execution for whoever runs git there next (`diff.external`, a `clean`
   filter, a `pre-commit` hook); a path through `.git` is never workspace
   content."
  [path]
  (when (and path
             (re-find #"(?i)(\.ssh[/\\]|\.gnupg[/\\]|/etc/shadow|/etc/passwd|/etc/sudoers|/proc/|/sys/|\.aws[/\\]|\.azure[/\\]|\.gcloud[/\\]|/run/secrets|\.env$|\.env\.|(^|[/\\])\.git([/\\]|$))"
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
  [effects url opts {:keys [placeholder value allowed-domains allowed-locations header-names]}]
  (let [hit-header (some (fn [[_ v]] (contains-ph? placeholder v)) (:headers opts))
        hit-query  (some (fn [[_ v]] (contains-ph? placeholder v)) (:query-params opts))
        hit-body   (contains-ph? placeholder (:body opts))]
    (if-not (or hit-header hit-query hit-body)
      opts
      (do
        (when (and (seq allowed-domains) (not (domain-allows? allowed-domains url)))
          (effects/note! effects :http/secret-denied {:reason :domain :url url})
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
                             (effects/note! effects :http/secret-injected {:slot [:header k]})
                             (assoc m k (str/replace v placeholder value)))
                         (assoc m k v)))
                     {} hs)))
          hit-query
          (update :query-params
                  (fn [qs]
                    (when-not (contains? allowed-locations :query)
                      (throw (ex-info "secret not permitted in query" {:muschel/denied true})))
                    (effects/note! effects :http/secret-injected {:slot :query})
                    (reduce-kv (fn [m k v]
                                 (assoc m k (if (contains-ph? placeholder v)
                                              (str/replace v placeholder value) v)))
                               {} qs)))
          hit-body
          (as-> o
                (do (when-not (contains? allowed-locations :body)
                      (throw (ex-info "secret not permitted in body" {:muschel/denied true})))
                    (effects/note! effects :http/secret-injected {:slot :body})
                    (update o :body str/replace placeholder value))))))))

(defn- substitute-secrets!
  "Apply every registry secret's substitution to `opts`. Throws on policy violation."
  [secrets effects url opts]
  (reduce (fn [o [_ spec]] (substitute-one effects url o spec)) opts (or secrets {})))

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

(defn- internal-address?
  "Loopback, link-local, private, wildcard, multicast — plus the two private
   ranges `InetAddress` does not call site-local: IPv6 unique-local fc00::/7
   (RFC 4193) and IPv4 shared address space 100.64.0.0/10 (RFC 6598, used for
   cloud VPC and pod-network services, e.g. a metadata endpoint at
   100.100.100.200)."
  [^java.net.InetAddress addr]
  (let [b (.getAddress addr)]
    (or (.isLoopbackAddress addr) (.isLinkLocalAddress addr)
        (.isSiteLocalAddress addr) (.isAnyLocalAddress addr)
        (.isMulticastAddress addr)
        (and (instance? java.net.Inet6Address addr)
             (= 0xfc (bit-and (aget b 0) 0xfe)))
        (and (instance? java.net.Inet4Address addr)
             (= 100 (bit-and (aget b 0) 0xff))
             (= 0x40 (bit-and (aget b 1) 0xc0)))
        (let [h (.getHostAddress addr)]
          (or (str/starts-with? h "169.254.")          ; link-local / cloud metadata
              (str/starts-with? h "127.")
              (str/starts-with? h "0."))))))

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

(defn- add-physical-fs-ns!
  "Expose rich filesystem operations as 'fs namespace in SCI.

   Backed by babashka.fs. All user-supplied paths are canonicalised via
   File.getCanonicalFile before use, which resolves '..' components and follows
   symlinks.  Any path that resolves outside base-path throws immediately.
   Sensitive OS path patterns (/etc/passwd, .ssh/, .env, etc.) are also blocked.

   :base-path  - absolute root for all relative path resolution (default: user.dir)
   :effects    - boundary fn (`dvergr.effects/boundary-resolver`); every op is an effect

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
  [sci-ctx & {:keys [base-path effects]
              :or   {base-path ((requiring-resolve 'dvergr.substrate.git/safe-workspace-root))}}]
  (load/require! 'babashka.fs)
  (let [fs-ns          (find-ns 'babashka.fs)
        r              (fn [s] @(ns-resolve fs-ns s))
        base-canonical (-> (java.io.File. (str base-path)) .getCanonicalFile)
        ;; Path-clamp every user path: canonical check + sensitive-path guard.
        ;; The policy is checked on the path as written AND on where it
        ;; resolves: a symlink `alias -> .git` must not make `alias/config`
        ;; writable.
        sr             (fn [p] (let [ps (str p)]
                                 (sensitive-path-policy ps)
                                 (doto (fs-safe-resolve base-canonical ps)
                                   (-> str sensitive-path-policy)
                                   ;; the repository's git directory, whatever
                                   ;; it is called (`.git -> metadata`)
                                   (as-> f (when (in-git-metadata? base-canonical f)
                                             (throw (ex-info "Access denied: sensitive path (git metadata)"
                                                             {:path ps})))))))
        ;; Relativize a resolved (absolute) path back to a workspace-relative
        ;; string, so agents never see the real `.dvergr/systems/<uuid>/…`
        ;; location — and get paths they can pass straight back to fs/slurp
        ;; (which re-resolve via sr). The workspace root itself → ".".
        base-str       (.getCanonicalPath base-canonical)
        ;; Anything resolving OUTSIDE the workspace (fs/parent of the root,
        ;; a symlink whose target escapes base) → nil, NEVER the raw host
        ;; path — leaking `.dvergr/systems/<uuid>/…` is the bug this guards,
        ;; and such a path can't be fed back through `sr` anyway. Callers
        ;; drop nils (listings) or propagate them (parent of root → nil).
        rel            (fn [p] (let [pc (.getCanonicalPath (java.io.File. (str p)))]
                                 (cond
                                   (= pc base-str) "."
                                   (.startsWith pc (str base-str java.io.File/separator))
                                   (subs pc (inc (count base-str)))
                                   :else nil)))
        bb-parent      (r 'parent)
        bb-create-dirs (r 'create-dirs)
        ;; Receipts name the path as the code gave it, never the host path;
        ;; resolution and containment run inside the effect, so a refused
        ;; path is receipted too.
        mkdir          (fn [p] (fx effects :fs/mkdir {:path (str p)}
                                   #(let [f (sr p)] (bb-create-dirs f) (rel f))))
        del            (fn [bb] (fn [p] (fx effects :fs/delete {:path (str p)} #(bb (sr p)))))
        ;; A tree copy reads and writes every path under its roots, not just
        ;; the roots: a symlink inside the source can point out of the
        ;; workspace, and one inside the destination (`alias -> .git`) can
        ;; redirect a write. Each entry is clamped on both sides; symlinked
        ;; directories are checked, not descended (no cycles).
        check-tree!    (fn [a b]
                         (let [fa (sr a)]
                           (when (.isDirectory fa)
                             (loop [pending (vec (.listFiles fa))]
                               (when-let [^java.io.File f (peek pending)]
                                 (let [r (str (.relativize (.toPath fa) (.toPath f)))]
                                   (sr (str a "/" r))
                                   (sr (str b "/" r))
                                   (recur (into (pop pending)
                                                (when (and (.isDirectory f)
                                                           (not (java.nio.file.Files/isSymbolicLink (.toPath f))))
                                                  (.listFiles f))))))))))
        ;; A recursive delete or a move takes everything under its root along:
        ;; refuse when anything under it is sensitive (`.git`, `.env`, …).
        check-descendants! (fn [a]
                             (let [fa (sr a)]
                               (when (.isDirectory fa)
                                 (loop [pending (vec (.listFiles fa))]
                                   (when-let [^java.io.File f (peek pending)]
                                     (let [p (str a "/" (.relativize (.toPath fa) (.toPath f)))
                                           link? (java.nio.file.Files/isSymbolicLink (.toPath f))]
                                       ;; a symlink is deleted or moved itself, not
                                       ;; its target: judge it by its name
                                       (if link? (sensitive-path-policy p) (sr p))
                                       (recur (into (pop pending)
                                                    (when (and (.isDirectory f) (not link?))
                                                      (.listFiles f))))))))))
        cpmv           (fn [op bb & [tree?]]
                         (fn [a b & m] (fx effects op {:src (str a) :dst (str b)}
                                           #(let [fa (sr a) fb (sr b)]
                                              (case tree?
                                                :copy (check-tree! a b)
                                                :move (do (check-descendants! a) (check-tree! a b))
                                                nil)
                                              (apply bb fa fb m) (rel fb)))))
        pred           (fn [bb] (fn [p] (fx effects :fs/stat {:path (str p)} #(bb (sr p)))))]
    ;; The real babashka.fs SUBSET, every path clamped to base-path. Returns strings
    ;; (not Path objects) so SCI agents get serialisable values. Content read/write
    ;; is `slurp`/`spit` (below), as in real Clojure — NOT an fs fn.
    (sci/add-namespace! sci-ctx 'babashka.fs
                        {'list-dir           (fn [d & more]
                                               (fx effects :fs/list {:path (str d)}
                                                   #(into [] (keep rel) (apply (r 'list-dir) (sr d) more))))
                         ;; 1-arg is pattern-only (root = workspace root) — the
                         ;; form an LLM reaches for, `(fs/glob "**/*.clj")`.
                         ;; babashka.fs/glob is root-first, so the raw 1-arg call
                         ;; is an arity error; SCI ≤0.13 silently nil-padded it
                         ;; and ≥0.14 (correctly) rejects it, so spell the arity
                         ;; out rather than rely on that leniency. (The virtual
                         ;; adapter below already carries the same 1-arg form.)
                         'glob               (fn
                                               ([pat] (fx effects :fs/list {:path "." :glob (str pat)}
                                                          #(into [] (keep rel) ((r 'glob) (sr ".") pat))))
                                               ([d pat & more] (fx effects :fs/list {:path (str d) :glob (str pat)}
                                                                   #(into [] (keep rel) (apply (r 'glob) (sr d) pat more)))))
                         'exists?            (pred (r 'exists?))
                         'directory?         (pred (r 'directory?))
                         'regular-file?      (pred (r 'regular-file?))
                         'sym-link?          (pred (r 'sym-link?))
                         'size               (pred (r 'size))
                         'last-modified-time (fn [p] (fx effects :fs/stat {:path (str p)}
                                                         #(str ((r 'last-modified-time) (sr p)))))
                         'create-dir         mkdir
                         'create-dirs        mkdir
                         'delete             (del (r 'delete))
                         'delete-if-exists   (del (r 'delete-if-exists))
                         'delete-tree        (let [d (del (r 'delete-tree))]
                                               (fn [p] (check-descendants! p) (d p)))
                         'move               (cpmv :fs/move (r 'move) :move)
                         'copy               (cpmv :fs/copy (r 'copy))
                         'copy-tree          (cpmv :fs/copy (r 'copy-tree) :copy)
                         'parent             (fn [p] (some-> (bb-parent (sr p)) rel))
                         'file-name          (fn [p] (str ((r 'file-name) p)))
                         'absolutize         (fn [p] (rel (sr p)))
                         'canonicalize       (fn [p] (rel (sr p)))})
    ;; File CONTENT I/O under the clojure.core names the model reaches for, sandboxed.
    (sci/merge-opts sci-ctx
                    {:namespaces
                     {'clojure.core
                      {'slurp (fn [p & opts] (fx effects :fs/read {:path (str p)}
                                                 #(apply clojure.core/slurp (sr p) opts)))
                       'spit  (fn [p content & opts]
                                (sized-write-fx effects {:path (str p)} content
                                                #(let [f (sr p)]
                                                   (when-let [par (bb-parent f)] (bb-create-dirs par))
                                                   (apply clojure.core/spit f content opts) (str f))))}}})))

(defn- virtual-path [filesystem path]
  (let [path (str path)]
    (sensitive-path-policy path)
    (or (mfs/resolve filesystem path)
        (throw (ex-info "Invalid virtual filesystem path" {:path path})))))

(defn- virtual-parent [path]
  (let [i (.lastIndexOf ^String path "/")]
    (cond (<= i 0) "/" :else (subs path 0 i))))

(defn- virtual-mkdirs! [filesystem path]
  (loop [path path pending []]
    (if (mfs/exists? filesystem path)
      (doseq [directory (reverse pending)]
        (mfs/mkdir filesystem directory))
      (recur (virtual-parent path) (conj pending path))))
  path)

(defn- virtual-walk [filesystem root]
  (letfn [(walk [path]
            (let [stat (mfs/stat filesystem path)]
              (cons path
                    (when (= :dir (:type stat))
                      (mapcat #(walk (str (str/replace path #"/$" "")
                                          "/" (:name %)))
                              (mfs/list-dir filesystem path))))))]
    (walk root)))

(defn- virtual-write-bytes! [filesystem path bytes]
  (when-let [sink (mfs/-open-sink filesystem path false)]
    (if (instance? java.io.OutputStream sink)
      (do (.write ^java.io.OutputStream sink ^bytes bytes)
          (.close ^java.io.OutputStream sink)
          true)
      (throw (ex-info "Virtual filesystem sink does not accept binary content"
                      {:path path})))))

(defn- world-filesystem
  "A stable Muschel handle whose every operation resolves the ambient world.

   SCI code may retain this value across an interpreter fork.  Delegating at
   the protocol boundary prevents such saved functions from retaining the
   parent's Geschichte workspace."
  [resolver]
  (letfn [(fs! [] (or (resolver)
                      (throw (ex-info "Current world has no filesystem" {}))))]
    (reify mfs/FS
      (-resolve [_ path] (mfs/-resolve (fs!) path))
      (-cwd [_] (mfs/-cwd (fs!)))
      (-cd! [_ path] (mfs/-cd! (fs!) path))
      (-exists? [_ path] (mfs/-exists? (fs!) path))
      (-stat [_ path] (mfs/-stat (fs!) path))
      (-list-dir [_ path] (mfs/-list-dir (fs!) path))
      (-read-file [_ path] (mfs/-read-file (fs!) path))
      (-read-bytes [_ path] (mfs/-read-bytes (fs!) path))
      (-open-source [_ path] (mfs/-open-source (fs!) path))
      (-open-sink [_ path append?] (mfs/-open-sink (fs!) path append?))
      (-mkdir [_ path] (mfs/-mkdir (fs!) path))
      (-delete [_ path] (mfs/-delete (fs!) path))
      (-rename [_ from to] (mfs/-rename (fs!) from to))
      (-touch [_ path] (mfs/-touch (fs!) path))
      (-chmod [_ path mode] (mfs/-chmod (fs!) path mode))
      (-symlink [_ target link-path] (mfs/-symlink (fs!) target link-path))
      (-chown [_ path owner group] (mfs/-chown (fs!) path owner group))
      (-sandbox-relativize [_ path] (mfs/-sandbox-relativize (fs!) path))
      (-physical-path [_ path] (mfs/-physical-path (fs!) path)))))

(defn- glob-regex [pattern]
  (-> pattern
      (str/replace "." "\\.")
      (str/replace "**/" "\u0001")
      (str/replace "**" "\u0002")
      (str/replace "*" "\u0003")
      (str/replace "?" "\u0004")
      (str/replace "\u0001" "(?:.*/)?")
      (str/replace "\u0002" ".*")
      (str/replace "\u0003" "[^/]*")
      (str/replace "\u0004" "[^/]")))

(defn- add-virtual-fs-ns! [sci-ctx filesystem effects]
  (let [resolve! #(virtual-path filesystem %)
        ;; A recursive copy, move or delete takes everything under its root:
        ;; refuse when any path under it, or where it would land, is sensitive
        ;; (`.ssh` passes, `.ssh/key` does not).
        check-tree! (fn [source target]
                      (doseq [p (virtual-walk filesystem source)]
                        (sensitive-path-policy p)
                        (when target
                          (sensitive-path-policy (str target (subs p (count source)))))))
        relative #(str/replace % #"^/+" "")
        stat-map (fn [path]
                   (when-let [stat (mfs/stat filesystem path)]
                     (assoc stat :path (relative path))))
        delete-tree! (fn delete-tree! [path]
                       (doseq [child (reverse (virtual-walk filesystem path))]
                         (mfs/delete filesystem child))
                       true)
        copy-tree! (fn copy-tree! [source target]
                     (let [stat (mfs/stat filesystem source)]
                       (case (:type stat)
                         :dir (do (virtual-mkdirs! filesystem target)
                                  (doseq [entry (mfs/list-dir filesystem source)]
                                    (copy-tree!
                                     (str (str/replace source #"/$" "") "/" (:name entry))
                                     (str (str/replace target #"/$" "") "/" (:name entry)))))
                         :file (do (virtual-mkdirs! filesystem (virtual-parent target))
                                   (virtual-write-bytes!
                                    filesystem target
                                    (mfs/read-bytes filesystem source)))
                         nil)
                       target))]
    (letfn [(stat-fx [f] (fn [p] (fx effects :fs/stat {:path (str p)} #(f p))))
            (write-fx [kind f] (fn [p] (fx effects kind {:path (str p)} #(f p))))
            (glob-paths [directory pattern]
              (let [root (resolve! directory)
                    matcher (re-pattern (str "^" (glob-regex (str pattern)) "$"))
                    prefix (str (str/replace root #"/$" "") "/")]
                (->> (virtual-walk filesystem root)
                     (remove #{root})
                     (map #(if (str/starts-with? % prefix)
                             (subs % (count prefix)) %))
                     (filter #(re-matches matcher %))
                     vec)))]
      (sci/add-namespace!
       sci-ctx 'babashka.fs
       {'list-dir (fn [directory & _]
                    (fx effects :fs/list {:path (str directory)}
                        #(let [path (resolve! directory)]
                           (mapv (fn [e] (relative (str (str/replace path #"/$" "")
                                                        "/" (:name e))))
                                 (or (mfs/list-dir filesystem path) [])))))
        'glob (fn
                ([pattern] (fx effects :fs/list {:path "." :glob (str pattern)}
                               #(glob-paths "." pattern)))
                ([directory pattern & _] (fx effects :fs/list {:path (str directory) :glob (str pattern)}
                                             #(glob-paths directory pattern))))
        'exists? (stat-fx #(mfs/exists? filesystem (resolve! %)))
        'directory? (stat-fx #(= :dir (:type (mfs/stat filesystem (resolve! %)))))
        'regular-file? (stat-fx #(= :file (:type (mfs/stat filesystem (resolve! %)))))
        'sym-link? (stat-fx #(= :symlink (:type (mfs/stat filesystem (resolve! %)))))
        'size (stat-fx #(some-> (mfs/stat filesystem (resolve! %)) :size))
        'last-modified-time (stat-fx #(str (or (:mtime-ms (mfs/stat filesystem (resolve! %))) 0)))
        'create-dir (write-fx :fs/mkdir #(let [path (resolve! %)] (mfs/mkdir filesystem path) (relative path)))
        'create-dirs (write-fx :fs/mkdir #(relative (virtual-mkdirs! filesystem (resolve! %))))
        'delete (write-fx :fs/delete #(mfs/delete filesystem (resolve! %)))
        'delete-if-exists (write-fx :fs/delete #(let [path (resolve! %)]
                                                  (if (mfs/exists? filesystem path)
                                                    (mfs/delete filesystem path) false)))
        'delete-tree (write-fx :fs/delete #(let [path (resolve! %)]
                                             (check-tree! path nil)
                                             (delete-tree! path)))
        'move (fn [source target & _]
                (fx effects :fs/move {:src (str source) :dst (str target)}
                    #(let [source (resolve! source) target (resolve! target)]
                       (check-tree! source target)
                       (mfs/rename filesystem source target)
                       (relative target))))
        'copy (fn [source target & _]
                (fx effects :fs/copy {:src (str source) :dst (str target)}
                    #(let [source (resolve! source) target (resolve! target)]
                       (check-tree! source target)
                       (relative (copy-tree! source target)))))
        'copy-tree (fn [source target & _]
                     (fx effects :fs/copy {:src (str source) :dst (str target)}
                         #(let [source (resolve! source) target (resolve! target)]
                            (check-tree! source target)
                            (relative (copy-tree! source target)))))
        'parent (fn [path] (let [path (resolve! path)]
                             (when-not (= path "/") (relative (virtual-parent path)))))
        'file-name #(last (str/split (str %) #"/"))
        'absolutize #(relative (resolve! %))
        'canonicalize #(relative (resolve! %))
        'stat (stat-fx #(stat-map (resolve! %)))})
      (sci/merge-opts
       sci-ctx
       {:namespaces
        {'clojure.core
         {'slurp (fn [path & _]
                   (fx effects :fs/read {:path (str path)}
                       #(let [path (resolve! path)]
                          (or (mfs/read-file filesystem path)
                              (throw (ex-info "No such virtual file" {:path path}))))))
          'spit (fn [path content & options]
                  (sized-write-fx effects {:path (str path)} content
                                  #(let [path (resolve! path)
                                         append? (boolean (:append (first options)))]
                                     (virtual-mkdirs! filesystem (virtual-parent path))
                                     (mfs/write-string! filesystem path (str content) append?)
                                     (relative path))))}}}))))

(defn add-fs-ns!
  "Expose a filesystem namespace backed by Muschel when `:filesystem` is
  supplied; otherwise retain the transitional physical adapter."
  [sci-ctx & {:keys [filesystem filesystem-resolver] :as options}]
  (if (or filesystem filesystem-resolver)
    (add-virtual-fs-ns! sci-ctx
                        (if filesystem-resolver
                          (world-filesystem filesystem-resolver)
                          filesystem)
                        (:effects options))
    (apply add-physical-fs-ns! sci-ctx (mapcat identity options))))

(defn- git-arg-refused! [msg data]
  (throw (ex-info msg (assoc data :type :dvergr/git-arg-refused :muschel/denied true))))

(def ^:private git-diff-flags
  "The `git/diff` options an agent may pass: output shape and whitespace only.
   Anything else is refused — host git has options that read or write outside
   the workspace (`--no-index`, `--output=`, `-O<file>`, `--ext-diff`, …), and
   the virtual workspace implements this same subset."
  #{"--cached" "--staged" "--stat" "--shortstat" "--numstat" "--name-only"
    "--name-status" "-p" "-u" "--patch" "-w" "--ignore-all-space" "-b"
    "--ignore-space-change" "--no-color" "--no-renames" "-R"})

(defn- workspace-pathspec!
  "Refuse a git path that leaves the workspace or names a sensitive file.
   Pathspec magic (`:(top)x`, `:/x`) is refused outright — git resolves it
   against the repository root, not the path as written. With a physical
   `base-path` the path (relative to it, or absolute) is canonicalised, so `..`
   and a symlink out of the workspace are refused alike. In the virtual
   workspace a leading `/` names the repository root and no path may climb
   above it."
  [base-path path]
  (let [refuse! #(git-arg-refused! (str "git path outside the workspace: " path)
                                   {:path path})]
    (when (str/starts-with? path ":")
      (git-arg-refused! (str "git pathspec magic not allowed: " path) {:path path}))
    (when (try (sensitive-path-policy path) false (catch clojure.lang.ExceptionInfo _ true))
      (git-arg-refused! (str "git path is a sensitive file: " path) {:path path}))
    ;; Lexically first: git resolves `..` in the operand textually, not
    ;; through symlinks, so `link/../../x` can name a file outside even when
    ;; its canonical form is inside. No `..` may climb above where it starts.
    (when (neg? (reduce (fn [d seg]
                          (case seg
                            ("" ".") d
                            ".." (if (zero? d) (reduced -1) (dec d))
                            (inc d)))
                        0 (str/split (str/replace path #"^/+" "") #"/")))
      (refuse!))
    (if base-path
      (let [base (.getCanonicalFile (java.io.File. (str base-path)))
            f (java.io.File. (str path))
            file (.getCanonicalFile (if (.isAbsolute f) f (java.io.File. base (str path))))]
        (when-not (.startsWith (.toPath file) (.toPath base))
          (refuse!))
        (when (try (sensitive-path-policy (str file)) false (catch clojure.lang.ExceptionInfo _ true))
          (git-arg-refused! (str "git path is a sensitive file: " path) {:path path})))
      (when (neg? (reduce (fn [d seg]
                            (case seg
                              ("" ".") d
                              ".." (if (zero? d) (reduced -1) (dec d))
                              (inc d)))
                          0 (str/split (str/replace path #"^/+" "") #"/")))
        (refuse!)))
    path))

(defn- sensitive-name? [path]
  (try (sensitive-path-policy (str path)) false
       (catch clojure.lang.ExceptionInfo _ true)))

(defn git-diff-argv
  "The argv for `(git/diff & args)`: allowlisted options, then `--`, then
   paths — so no argument is ever read as an option it was not checked as, and
   every path stays inside the workspace (`base-path`, or the virtual
   workspace's root when nil). Operands are always paths, never revisions; an
   argument after a caller's own `--` is a path even if it starts with `-`.

   On the host (`base-path` set) the diff also never runs an external diff or
   textconv driver (repository config the agent may have written), and with no
   paths it is limited to `base-path`, which can be a subdirectory of a larger
   repository."
  [base-path args]
  (let [[before after] (split-with #(not= "--" %) (map str args))
        options (filter #(str/starts-with? % "-") before)
        paths (concat (remove #(str/starts-with? % "-") before) (rest after))]
    (doseq [o options
            :when (not (or (contains? git-diff-flags o) (re-matches #"-U\d{1,4}" o)))]
      (git-arg-refused! (str "git/diff option not allowed: " o
                             " (allowed: " (str/join " " (sort git-diff-flags)) " -U<n>)")
                        {:option o}))
    (-> ["diff"]
        (into (when base-path ["--no-ext-diff" "--no-textconv"]))
        (into options)
        (conj "--")
        (into (map #(workspace-pathspec! base-path %)) paths)
        (cond-> (and base-path (empty? paths)) (conj ".")))))

(defn add-git-ns!
  "Expose structured git operations as 'git namespace in SCI.

   Agents working in yggdrasil worktrees need programmatic git access
   to understand workspace state before committing. This namespace returns
   structured Clojure data rather than raw strings.

   :base-path - git working directory (default: user.dir)
   :effects   - boundary fn (`dvergr.effects/boundary-resolver`); add/commit are effects

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
  [sci-ctx & {:keys [base-path effects workspace workspace-resolver]
              :or   {base-path ((requiring-resolve 'dvergr.substrate.git/safe-workspace-root))}}]
  (let [run!      (if (or workspace workspace-resolver)
                    (fn [& args]
                      (let [workspace (if workspace-resolver
                                        (workspace-resolver)
                                        workspace)
                            result ((requiring-resolve
                                     'dvergr.substrate.geschichte/execute-git)
                                    workspace (vec args))]
                        (if (zero? (:exit result))
                          (:stdout result)
                          (throw (ex-info (str/trim (:stderr result)) result)))))
                    (fn [& args] (apply git-run* base-path args)))

        status-fn (fn []
                    (fx effects :git/read {:op :status}
                        #(parse-porcelain-status
                          (run! "status" "--porcelain=v1" "--branch"))))

        log-fn    (fn [& [opts]]
                    (let [n (or (:n opts) 10)]
                      ;; `(str "-" n)` with a string :n was any option
                      ;; (`{:n "-output=/x"}` → `--output=/x`).
                      (when-not (and (integer? n) (pos? n))
                        (git-arg-refused! "git/log :n must be a positive integer" {:n n}))
                      (fx effects :git/read {:op :log}
                          #(parse-git-log
                            (run! "log" git-log-format (str "-" n))))))

        ;; Validate before the effect boundary, so a refusal is not audited as
        ;; a read that happened. Physical paths are checked against the
        ;; workspace on disk; virtual ones against the repository root.
        diff-fn   (fn [& args]
                    (let [host-base (when-not (or workspace workspace-resolver) base-path)
                          argv (git-diff-argv host-base args)
                          sep (.indexOf ^java.util.List argv "--")
                          opts (subvec argv 1 sep)
                          paths (subvec argv (inc sep))]
                      (fx effects :git/read {:op :diff :args (vec args)}
                          ;; Which files does this diff cover? A sensitive one
                          ;; (a tracked `.env`) is left out, as every other
                          ;; file tool leaves it out; the diff then runs on
                          ;; the rest by name. Listed without rename
                          ;; detection, so both sides of a rename are seen,
                          ;; and rerun without it, so a sensitive source
                          ;; cannot come back as a rename's old side. Names
                          ;; are split on NUL only and passed back as literal
                          ;; pathspecs (a file named `*` is that file).
                          #(let [names (->> (apply run! (concat ["diff"]
                                                                (filter #{"--cached" "--staged" "--no-ext-diff" "--no-textconv"} opts)
                                                                ["--no-renames" "--name-only" "-z"]
                                                                (when host-base ["--relative"])
                                                                ["--"] paths))
                                            (re-seq #"[^\u0000]+"))
                                 safe (remove sensitive-name? names)]
                             (cond
                               (= (count safe) (count names)) (apply run! argv)
                               (empty? safe) ""
                               :else (apply run! (concat ["diff"] opts ["--no-renames" "--"]
                                                         (map (fn [n] (str ":(literal)" n)) safe))))))))

        add-fn    (fn [& paths]
                    (let [host-base (when-not (or workspace workspace-resolver) base-path)
                          _ (when (empty? paths)
                              (git-arg-refused! "git/add needs at least one path (\".\" for everything)" {}))
                          paths (mapv #(workspace-pathspec! host-base (str %)) paths)]
                      (fx effects :git/add {:paths paths}
                          ;; `.` or a directory takes every file under it, a
                          ;; tracked `.env` too: on the host, list what the add
                          ;; would stage and stage only the non-sensitive files
                          ;; (as literal, root-relative pathspecs — that is how
                          ;; status names them).
                          #(do (if host-base
                                 (let [names (->> (apply run! (concat ["status" "--porcelain=v1" "-z"
                                                                       "--untracked-files=all" "--no-renames" "--"]
                                                                      paths))
                                                  (re-seq #"[^\u0000]+")
                                                  (keep (fn [e] (when (> (count e) 3) (subs e 3)))))
                                       ;; status names are root-relative: keep only
                                       ;; those inside the workspace
                                       top (worktree-top host-base)
                                       base (.toPath (.getCanonicalFile (java.io.File. (str host-base))))
                                       inside? (fn [n] (and top (.startsWith (.toPath (.getCanonicalFile (java.io.File. ^java.io.File top ^String n))) base)))
                                       safe (remove sensitive-name? (filter inside? names))]
                                   (cond
                                     ;; every listed name is inside and safe: the
                                     ;; operands as given select exactly these
                                     (= (count safe) (count names)) (apply run! "add" "--" paths)
                                     (seq safe) (apply run! "add" "--" (map (fn [n] (str ":(top,literal)" n)) safe))
                                     :else nil))
                                 ;; Virtual: geschichte's status takes no
                                 ;; pathspecs, so select its names by path
                                 ;; prefix ourselves; a glob is refused when
                                 ;; any sensitive file has changes. Its add
                                 ;; reads `-A`/`-u`/`-f` anywhere in argv, even
                                 ;; after `--`: a dash-led operand is refused,
                                 ;; and names go back root-anchored (`/-A` is
                                 ;; the file `-A`).
                                 ;; Names come from Geschichte's structured status
                                 ;; entries (a file name with a line break is one
                                 ;; name, not a forged record).
                                 (let [_ (doseq [p paths :when (str/starts-with? p "-")]
                                           (git-arg-refused! (str "git/add path may not start with -: " p) {:path p}))
                                       {:keys [conn]} (if workspace-resolver (workspace-resolver) workspace)
                                       rules ((requiring-resolve 'geschichte.ignore/rules) conn)
                                       ignored? (requiring-resolve 'geschichte.ignore/ignored?)
                                       names (->> ((requiring-resolve 'geschichte.repo/status-entries) conn)
                                                  (remove (fn [{:keys [path worktree index]}]
                                                            (and (= :untracked worktree) (nil? index)
                                                                 (ignored? rules path))))
                                                  (map :path))
                                       rel (fn [p] (-> (java.nio.file.Paths/get "/" (into-array String [(str p)]))
                                                       .normalize str (str/replace #"^/+|/+$" "")))
                                       ;; operands normalised the same way on both
                                       ;; branches, root-anchored
                                       anchored (mapv (fn [p] (let [r (rel p)] (if (= "" r) "." (str "/" r)))) paths)
                                       selected? (fn [n] (some (fn [p] (let [p (rel p)]
                                                                         (or (= "" p) (= n p)
                                                                             (str/starts-with? n (str p "/")))))
                                                               paths))
                                       sensitive (filter sensitive-name? names)]
                                   (cond
                                     (empty? sensitive) (apply run! "add" "--" anchored)
                                     (some (fn [p] (re-find #"[*?\[]" p)) paths)
                                     (git-arg-refused! "git/add glob not allowed while a sensitive file has changes"
                                                       {:paths paths})
                                     :else (let [safe (remove sensitive-name? (filter selected? names))]
                                             (when (seq safe)
                                               (apply run! "add" "--" (map (fn [n] (str "/" n)) safe)))))))
                               :ok))))

        commit-fn (fn [message & [opts]]
                    (fx effects :git/commit {:message message}
                        #(let [args (cond-> ["commit" "-m" message]
                                      (:author opts) (into ["--author" (:author opts)]))]
                           (str/trim (apply run! args)))))]

    (sci/add-namespace! sci-ctx 'git
                        (doc/with-docs
                          {'status status-fn
                           'log    log-fn
                           'diff   diff-fn
                           'add    add-fn
                           'commit commit-fn}
                          '{status [([]) "Working-tree status of YOUR room's repo, PARSED into a map (not porcelain text) — branch plus changed paths."
                                    [:=> :cat [:map [:branch :string] [:staged [:vector :string]]
                                               [:unstaged [:vector :string]] [:untracked [:vector :string]]]]]
                            log    [([] [opts]) "Recent commits as maps of :hash/:message/:author/:date. `opts` takes :n (default 10)."
                                    [:=> [:cat [:? [:maybe [:map [:n {:optional true} :int]]]]]
                                     [:vector [:map [:hash :string] [:message :string] [:author :string] [:date :string]]]]]
                            diff   [([] [& args]) "Unified diff text. No args = unstaged changes. Options: --staged/--cached, --stat, --shortstat, --numstat, --name-only, --name-status, -U<n>, -w, -b, -R, --no-renames; every other argument is a path inside the workspace."
                                    [:=> [:cat [:* :string]] :string]]
                            add    [([& paths]) "Stage paths for commit. Audited. Returns :ok."
                                    [:=> [:cat [:+ :string]] [:= :ok]]]
                            commit [([message] [message opts]) "Commit staged changes with `message`, returning the trimmed git output. `opts` takes :author. Audited."
                                    [:=> [:cat :string [:? [:maybe [:map [:author {:optional true} :string]]]]] :string]]}))))

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
  [sci-ctx & {:keys [user-config config-resolver config-swap! secrets]}]
  (let [config-atom (or user-config (atom {}))
        config      #(if config-resolver (config-resolver) @config-atom)
        ;; A configured boundary-injection SECRET resolves to its opaque
        ;; PLACEHOLDER (never the real value) — the HTTP egress substitutes the
        ;; real value at the destination. Non-secret granted keys still return
        ;; their real config value.
        get-1  (fn [key]
                 (let [k (str key)]
                   (if-let [s (get secrets k)]
                     (:placeholder s)
                     (or (get (config) k) (get (config) (keyword key))))))
        get-fn (fn
                 ([key]         (get-1 key))
                 ([key default] (or (get-1 key) default)))
        set-fn (fn [key value]
                 (if config-swap!
                   (config-swap! assoc (str key) value)
                   (swap! config-atom assoc (str key) value))
                 :ok)
        ;; List names `env/get` resolves: a keyword config key `:foo` is listed
        ;; as "foo" (a namespaced `:a/b` as "a/b"), not ":foo" — `get-1` finds
        ;; it again through `(keyword key)`.
        key-name (fn [k] (if (keyword? k) (subs (str k) 1) (str k)))
        keys-fn (fn [] (vec (distinct (concat (map key-name (keys (config)))
                                              (keys (or secrets {}))))))]
    (sci/add-namespace! sci-ctx 'env
                        (doc/with-docs
                          {'get  get-fn
                           'set  set-fn
                           'keys keys-fn}
                          '{get  [([key] [key default]) "Read a config key granted to YOU. This is NOT the host process environment — System/getenv is unreachable, so the daemon's own secrets are not visible here. A key configured as an injected secret returns an opaque PLACEHOLDER, never the real value; HTTP egress substitutes the real one at the destination."
                                  [:function
                                   [:=> [:cat [:or :string :keyword :symbol]] [:maybe :any]]
                                   [:=> [:cat [:or :string :keyword :symbol] :any] :any]]]
                            set  [([key value]) "Set a config key for this sandbox session. Returns :ok."
                                  [:=> [:cat [:or :string :keyword :symbol] :any] [:= :ok]]]
                            keys [([]) "Every config key readable here, as names `env/get` accepts, including the names of injected secrets (whose values stay placeholders)."
                                  [:=> :cat [:vector :string]]]}))))

(defn- sent-body
  "The body a request sends: `:json` encoded, else `:body` as given. (The
   transport sends no other body.)"
  [{:keys [body json]}]
  (if (some? json) (j/write-value-as-string json) body))

(defn- body-digest
  "A digest of the `body` a request sends, for its receipt: of its bytes as
   transmitted (a string goes out as UTF-8); nil for none or a stream (read
   once, by the transport)."
  [body]
  (cond
    (nil? body) nil
    (instance? java.io.InputStream body) nil
    :else (let [^bytes bs (if (bytes? body) body (.getBytes (str body) "UTF-8"))
                md (java.security.MessageDigest/getInstance "SHA-256")]
            (apply str (map #(format "%02x" %) (take 12 (.digest md bs)))))))

(defn- encoded-query
  "`query-params` as the transport sends them: hato's own nesting
   (`{:a {:b 1}}` is `a[b]=1`) and encoding (a vector is one parameter per
   element)."
  [query-params]
  (let [nest (requiring-resolve 'hato.middleware/nest-params-request)
        generate (requiring-resolve 'hato.middleware/generate-query-string)]
    (generate (:query-params (nest {:query-params query-params})))))

(defn add-http-ns!
  "Expose HTTP client as 'http namespace in SCI.

   Agents can make HTTP requests to build integrations.
   Responses are returned as maps with :status, :headers, :body.
   JSON bodies are auto-parsed.

   :effects        - boundary fn (`dvergr.effects/boundary-resolver`); every request is
                     an effect (method + URL; the response body by digest only)
   :fixture-transport - trusted host-only offline request function, local to this
                        SCI namespace. Preserves domain checks and acquisition
                        recording; replaces DNS/network and secret injection.
                        Never expose transport installation to candidate code.
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
  [sci-ctx & {:keys [effects allowed-domains secrets fixture-transport]}]
  (let [domain-check (make-domain-policy allowed-domains)
        perform-request
        (fn [{:keys [url method headers json query-params timeout]
              :or {method :get timeout 30000} :as request}]
          ;; The request is already receipted (do-request); a refused
          ;; domain is receipted with its error.
          (when domain-check (domain-check url))
          (ssrf-guard! url)
          (load/require! 'hato.client)
          (let [hato-request (requiring-resolve 'hato.client/request)
                opts (cond-> {:url url
                              :method method
                              :http-client {:redirect-policy :never}
                              :connect-timeout timeout
                              :socket-timeout timeout}
                       headers (assoc :headers headers)
                       (some? (sent-body request)) (assoc :body (sent-body request))
                       json (-> (assoc :content-type :json)
                                (update :headers merge {"Content-Type" "application/json"}))
                       query-params (assoc :query-params query-params))
                ;; Boundary secret injection: substitute placeholders → real values
                ;; AFTER domain/ssrf guards, just before the bytes leave; the agent
                ;; never holds plaintext. (No-op when no secrets configured.)
                opts (substitute-secrets! secrets effects url opts)
                resp (hato-request opts)
                ;; Scrub any reflected secret value back to its placeholder — the
                ;; only path the plaintext could re-enter the sandbox.
                resp (scrub-response secrets resp)]
            ;; Match babashka.http-client: :body is the RAW string (not auto-parsed).
            ;; The agent parses explicitly — (cheshire.core/parse-string (:body r) true).
            {:status (:status resp)
             :headers (into {} (:headers resp))
             :body (:body resp)}))
        ;; Host-only, namespace-local substitution. Never consult a global
        ;; transport Var: concurrent live and simulated interpreters coexist.
        ;; A fixture does no DNS, secret substitution or network fallback.
        do-request (fn [opts]
                     (let [method (or (:method opts) :get)]
                       (effects/perform!
                        effects
                        {:effect :http/request
                         ;; the query names what was asked: two searches of one
                         ;; endpoint are different sources
                         :resource (cond-> {:method method :url (:url opts)}
                                     (seq (:query-params opts))
                                     (assoc :query (encoded-query (:query-params opts)))
                                     ;; so does a request's body: two POSTed
                                     ;; searches of one endpoint ask different things
                                     (body-digest (sent-body opts))
                                     (assoc :body-digest (body-digest (sent-body opts))))
                         ;; a request that sends data is egress, not only network
                         :class (when-not (#{:get :head} method) #{:egress})
                         :result-of :body}
                        #(acquisition/record-request!
                          opts (fn []
                                 (if fixture-transport
                                   (do (when domain-check (domain-check (:url opts)))
                                       (fixture-transport opts))
                                   (perform-request opts)))))))]
    (sci/add-namespace! sci-ctx 'babashka.http-client
                        {'request do-request
                         'get     (fn [url & [opts]] (do-request (merge {:url url :method :get} opts)))
                         'post    (fn [url & [opts]] (do-request (merge {:url url :method :post} opts)))
                         'put     (fn [url & [opts]] (do-request (merge {:url url :method :put} opts)))
                         'patch   (fn [url & [opts]] (do-request (merge {:url url :method :patch} opts)))
                         'head    (fn [url & [opts]] (do-request (merge {:url url :method :head} opts)))
                         'delete  (fn [url & [opts]] (do-request (merge {:url url :method :delete} opts)))})))

(defn add-media-ns!
  "Expose document + vision processing to SCI, bound to a chat-ctx:

     (doc/extract-text \"/drive/telegram/report.pdf\")  ; pdf/text → string
     (vision/describe \"/drive/telegram/photo.jpg\")    ; image → description/OCR
     (vision/describe path {:prompt \"read the receipt total\"})
     ;; structured extraction for business docs (invoice → JSON):
     (vision/extract \"/drive/inbox/invoice.jpg\"
                     {:schema \"invoice_number, date (ISO), vendor, total (number)\"
                      :verify-fields [:total]})

   Paths resolve through the chat-ctx's muschel FS — the same
   filesystem the shell sees, so worktree files AND mounted drives
   (e.g. /drive) both work. Bytes never enter the SCI sandbox; only
   extracted text / parsed data comes back."
  [sci-ctx chat-ctx & [effects]]
  (let [read-bytes (fn [path]
                     (let [host ((requiring-resolve 'dvergr.intake.bash/get-or-create-host!)
                                 chat-ctx)
                           fs (:fs host)]
                       (or ((requiring-resolve 'muschel.fs/read-bytes) fs path)
                           (throw (ex-info (str "no such file: " path) {:path path})))))
        guess-mime (fn [path]
                     (let [p (str path)]
                       (cond
                         (re-find #"(?i)\.pdf$" p) "application/pdf"
                         (re-find #"(?i)\.(jpe?g)$" p) "image/jpeg"
                         (re-find #"(?i)\.png$" p) "image/png"
                         (re-find #"(?i)\.webp$" p) "image/webp"
                         (re-find #"(?i)\.(md|txt|csv|json|xml|edn|clj|cljs|cljc)$" p) "text/plain"
                         :else "application/octet-stream")))
        ;; Model calls here are the agent's spend: refused over budget, and
        ;; every response charged to the chat, like the llm_call tool.
        charged (fn [f]
                  (effects/perform!
                   effects {:effect :model/call :resource {:model "vision"}}
                   (fn []
                     (when (and chat-ctx ((requiring-resolve 'dvergr.chat.context/budget-exceeded?) chat-ctx))
                       (throw (ex-info "Budget exceeded — vision/doc call refused" {:type :budget-exceeded})))
                     (with-bindings {(requiring-resolve 'dvergr.media.vision/*on-response*)
                                     (fn [r] (when chat-ctx
                                               ((requiring-resolve 'dvergr.tools.llm-call/account-response!) chat-ctx r)))}
                       (f)))))
        extract (fn [path]
                  (charged #((requiring-resolve 'dvergr.media.doc/extract-text)
                             (read-bytes path) (guess-mime path))))
        describe (fn [path & [opts]]
                   (charged #((requiring-resolve 'dvergr.media.vision/describe)
                              (read-bytes path) (guess-mime path) opts)))
        extract-data (fn [path opts]
                       (charged #((requiring-resolve 'dvergr.media.vision/extract)
                                  (read-bytes path) (guess-mime path) opts)))]
    (sci/add-namespace! sci-ctx 'doc {'extract-text extract})
    (sci/add-namespace! sci-ctx 'vision {'describe describe
                                         'extract extract-data})))

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
  [sci-ctx chat-ctx & [effects]]
  (load/require! 'dvergr.intake.bash)
  (let [run-fn       (var-get (ns-resolve 'dvergr.intake.bash 'run))
        check-fn     (var-get (ns-resolve 'dvergr.intake.bash 'check))
        builtins-fn  (var-get (ns-resolve 'dvergr.intake.bash 'builtins))
        allowlist-fn (var-get (ns-resolve 'dvergr.intake.bash 'allowlist))
        run          (fn [cmd & opts] ; → {:stdout :stderr :exit …}
                       (effects/perform! effects {:effect :process/run :resource {:cmd (str cmd)}
                                                  :result-of :stdout}
                                         #(apply run-fn chat-ctx cmd opts)))
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
    ;; muschel-specific introspection (not part of babashka.process). The
    ;; canonical shell surface for RUNNING commands is `babashka.process/shell`
    ;; (its real name — what the stdlib + prompt primitives table teach);
    ;; `dvergr.shell` adds the dvergr-only introspection (check/builtins/allowlist).
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
     (processes/directive! pid {:type :refocus :hint \"…\"})"
  [sci-ctx chat-ctx & [effects]]
  (let [proc-ns   (find-ns 'dvergr.agent.process)
        list-fn   (var-get (ns-resolve proc-ns 'list-processes))
        snap-fn   (var-get (ns-resolve proc-ns 'snapshot))
        get-fn    (var-get (ns-resolve proc-ns 'get-process))
        dir-fn    (var-get (ns-resolve proc-ns 'directive!))]
    (sci/add-namespace! sci-ctx 'processes
                        {'list       (fn [] (list-fn chat-ctx))
                         'snapshot   (fn [pid] (when-let [p (get-fn chat-ctx pid)]
                                                 (snap-fn p)))
                         ;; An agent steers its own work, but a budget is set by
                         ;; whoever gave it: no extending it from inside.
                         'directive! (fn [pid d]
                                       (when (or (= :extend-budget (:type d))
                                                 (some #(= :extend-budget (:op %)) (:effects d)))
                                         (throw (ex-info "A budget is extended by its supervisor or the room's owner, not from the sandbox"
                                                         {:type ::budget-self-extension})))
                                       (effects/perform! effects {:effect :process/directive
                                                                  :resource {:pid (str pid) :type (:type d)}}
                                                         #(dir-fn chat-ctx pid d)))})))

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

(def ^:private git-safety-config
  "Config overrides for every host git call. The repository's own config is
   writable from the workspace in physical mode; these keep it from turning a
   `git/status` or `git/commit` into host command execution (hooks, the
   fsmonitor hook, a signing program)."
  ["-c" "core.hooksPath=/dev/null"
   "-c" "core.fsmonitor=false"
   "-c" "commit.gpgSign=false"
   "-c" "log.showSignature=false"
   ;; A submodule is another repository with its own config, filters and
   ;; files, none of which the overrides here or the sensitive-file filter
   ;; see: never recurse into one, never expand its contents.
   "-c" "submodule.recurse=false"
   "-c" "diff.submodule=short"
   "-c" "diff.ignoreSubmodules=all"
   "-c" "status.submoduleSummary=false"
   ;; No automatic gc/maintenance: it runs repository-configured commands
   ;; (gc.recentObjectsHook) that no hook override reaches.
   "-c" "gc.auto=0"
   "-c" "maintenance.auto=false"
   ;; No transport at all (a promisor remote would lazily fetch missing
   ;; objects, and `ext::` runs a command): per-protocol keys win over
   ;; protocol.allow, so each is pinned; GIT_ALLOW_PROTOCOL and
   ;; GIT_NO_LAZY_FETCH below back this up.
   "-c" "protocol.allow=never"
   "-c" "protocol.ext.allow=never"
   "-c" "protocol.file.allow=never"
   "-c" "protocol.git.allow=never"
   "-c" "protocol.ssh.allow=never"
   "-c" "protocol.http.allow=never"
   "-c" "protocol.https.allow=never"])

(defn- filter-overrides
  "`-c filter.<name>.<key>=` for every filter driver git's config defines, so
   none runs whatever selects it (`.gitattributes`, `.git/info/attributes`, a
   global attributes file) and on every git version. An empty command is no
   filter. A driver name `-c` cannot express (one with `=`) refuses the call."
  [base-path]
  (let [pb (doto (ProcessBuilder. ^java.util.List ["git" "config" "-z" "--get-regexp" "^filter\\."])
             (.directory (java.io.File. (str base-path)))
             (.redirectError java.lang.ProcessBuilder$Redirect/DISCARD))
        proc (.start pb)
        out (slurp (.getInputStream proc))
        _ (.waitFor proc)
        names (into #{}
                    (keep (fn [entry]
                            (let [k (first (str/split entry #"\n" 2))]
                              (when-let [[_ n] (re-matches #"(?s)filter\.(.*)\.[^.]+" k)]
                                n))))
                    (str/split out #"\u0000"))]
    ;; `-c` can express neither an empty driver name (`filter..clean`, which
    ;; `filter=` selects) nor one with `=`; git would run it, so refuse.
    (when (some #(or (str/blank? %) (str/includes? % "=")) names)
      (git-arg-refused! "git filter driver name not overridable" {:names names}))
    ;; `required` too: a required filter with no command fails the call.
    (into [] (mapcat (fn [n] (concat (mapcat #(vector "-c" (str "filter." n "." % "="))
                                             ["clean" "smudge" "process"])
                                     ["-c" (str "filter." n ".required=false")])))
          names)))

(defn- worktree-top
  "The directory holding `.git` at or above `base-path` — the worktree host git
   is pinned to."
  [base-path]
  (->> (iterate #(.getParentFile ^java.io.File %)
                (.getCanonicalFile (java.io.File. (str base-path))))
       (take-while some?)
       (filter #(.exists (java.io.File. ^java.io.File % ".git")))
       first))

(defn- read-git-pointer
  "A gitfile's or commondir's target as git reads it: the whole content (after
   the `gitdir: ` prefix for a gitfile) minus trailing CR/LF — a name may
   contain a newline — resolved against `relative-to`."
  [^java.io.File f prefix ^java.io.File relative-to]
  (let [content (str/replace (slurp f) #"[\r\n]+$" "")]
    (when (str/starts-with? content prefix)
      (let [target (java.io.File. (subs content (count prefix)))]
        (.getCanonicalFile (if (.isAbsolute target) target (java.io.File. relative-to (str target))))))))

(defn git-metadata-dirs
  "The canonical git directories of the repository `base-path` is in: the git
   dir (`.git`, or where a `.git` symlink or gitfile points) and, for a linked
   worktree, its common dir (shared config, hooks, objects). Writes, deletes
   and moves must not reach either however it is spelled."
  [base-path]
  (when-let [top (worktree-top base-path)]
    (let [dotgit (java.io.File. ^java.io.File top ".git")
          gitdir (if (.isFile dotgit)
                   (read-git-pointer dotgit "gitdir: " top)
                   (.getCanonicalFile dotgit))
          common-file (some-> gitdir (java.io.File. "commondir"))
          common (when (and common-file (.isFile common-file))
                   (read-git-pointer common-file "" gitdir))]
      (into #{} (remove nil?) [gitdir common]))))

(defn- git-dir-like?
  "Does directory `d` look like a git directory — HEAD plus objects, or the
   commondir/gitdir of a linked worktree? Relocated metadata of any repository
   in the workspace (a nested `child/.git` gitfile pointing to
   `child/metadata`) is found this way, whatever points to it."
  [^java.io.File d]
  (and (.isFile (java.io.File. d "HEAD"))
       (or (.isDirectory (java.io.File. d "objects"))
           (.isFile (java.io.File. d "commondir"))
           (.isFile (java.io.File. d "gitdir")))))

(defn in-git-metadata?
  "Is canonical `file` inside a git directory: the one of `base-path`'s
   repository (however `.git` points to it), or any directory between `file`
   and the workspace that looks like a git directory?"
  [base-path ^java.io.File file]
  (let [base (.getCanonicalFile (java.io.File. (str base-path)))]
    (boolean
     (or (some #(.startsWith (.toPath file) (.toPath ^java.io.File %))
               (git-metadata-dirs base-path))
         (some git-dir-like?
               (->> (iterate #(.getParentFile ^java.io.File %) file)
                    (take-while #(and % (.startsWith (.toPath ^java.io.File %) (.toPath base))))))))))

(defn- git-run*
  "Run git in base-path. Returns stdout string or throws on non-zero exit."
  [base-path & args]
  (let [[cmd & more] (map str args)
        all-args (-> ["git"] (into git-safety-config) (into (filter-overrides base-path))
                     (conj cmd)
                     (into (when (#{"diff" "status"} cmd) ["--ignore-submodules=all"]))
                     (into more))
        pb       (doto (ProcessBuilder. ^java.util.List all-args)
                   (.directory (java.io.File. (str base-path))))
        ;; Attributes stay as the repository has them (text, eol, encoding):
        ;; the commands an attribute can select are disabled by name — every
        ;; configured filter emptied above, diffs run --no-ext-diff
        ;; --no-textconv.
        env      (doto (.environment pb)
                   (.put "GIT_ALLOW_PROTOCOL" "none")
                   (.put "GIT_NO_LAZY_FETCH" "1")
                   (.put "GIT_TERMINAL_PROMPT" "0"))
        ;; The worktree is the directory that holds `.git` above base-path,
        ;; not whatever `core.worktree` (possibly from an included config
        ;; file) says: every path check here is against that directory.
        _        (when-let [top (worktree-top base-path)]
                   (.put env "GIT_WORK_TREE" (str top)))
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

(def ^:private git-log-format
  "`git log --format` for `parse-git-log`: each record STARTS with the ASCII
   record separator (U+001E) and its fields are split by the unit separator
   (U+001F) — control characters that cannot occur in a subject or author name,
   unlike `|`. The separators are passed LITERALLY (not as `%x1e`/`%x1f`) so the
   virtual Geschichte git, which expands only the placeholders themselves,
   emits them unchanged; record-splitting also survives a multi-line message."
  "--format=\u001e%H\u001f%s\u001f%an\u001f%ai")

(defn- parse-git-log
  "Parse `git log` output written with `git-log-format` into a vector of
   {:hash :message :author :date} maps."
  [output]
  (->> (str/split (str output) #"\x1e")
       (remove str/blank?)
       (mapv (fn [record]
               (let [[hash msg author date] (str/split record #"\x1f" 4)]
                 {:hash    (str/trim (str hash))
                  :message (str/trim (str msg))
                  :author  (str/trim (str author))
                  :date    (str/trim (str date))})))))
