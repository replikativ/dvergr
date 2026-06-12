(ns dvergr.sandbox.deps
  "Gated dependency addition for SCI sandbox agents.

   The standard `clojure.repl.deps/add-libs` modifies the JVM classpath
   globally — anything you add becomes visible to every running agent
   and the daemon itself. For agents driving long-running daemons we
   want a manager-mediated gate: the agent calls add-libs, the call is
   inspected by a policy fn, the policy fn either lets it through
   transparently, denies it with a reason, or escalates to a human
   manager who fulfills the decision.

   From the agent's side, the call is synchronous: one invocation,
   one outcome.

       :approve  → add-libs runs on the host, new namespaces are
                   registered in the SCI ctx, function returns :loaded
       {:deny r} → function throws ex-info with the structured reason

   The policy fn is configured per execution context via
   `(install-policy! ctx fn)`. The fn signature is

       (fn [coord ctx] → :approve | {:deny <string>} | :ask-human)

   `:ask-human` is the asynchronous escape hatch: the request is
   parked on a clojure.core/promise, posted to the peer-bus, and the
   call blocks until a human / manager calls `decide!` to resolve it.

   The default policy: a coord-pattern allowlist. If the coord's
   group-id matches one of the patterns, auto-approve; else return
   `:ask-human`. The allowlist lives in `(ec/get-state [:dvergr/deps-policy :allowlist])`
   (a vector of regex patterns); install via `set-allowlist!`."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [dvergr.sandbox.workspace :as workspace]
            [dvergr.runtime.peer-bus :as peer-bus]
            [org.replikativ.spindel.core :as sp]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.spin.sync :as sync]
            [taoensso.telemere :as tel]))

;; ============================================================================
;; State keys
;; ============================================================================

(def ^:private POLICY-KEY    [:dvergr/deps-policy :fn])
(def ^:private ALLOWLIST-KEY [:dvergr/deps-policy :allowlist])
(def ^:private PENDING-KEY   [:dvergr/deps-policy :pending])

;; ============================================================================
;; Default policy: coord-pattern allowlist
;; ============================================================================

(def default-allowlist
  "Group-id / lib coord patterns that auto-approve. These are common,
   well-maintained Clojure libraries that an agent might reasonably
   reach for in a coding session. Override per ctx via
   (set-allowlist! ctx patterns)."
  ["^org\\.clojure/.*"
   "^hiccup/.*"
   "^http-kit/.*"
   "^ring/.*"
   "^metosin/.*"
   "^cheshire/.*"
   "^hato/.*"
   "^babashka/.*"
   "^clojure\\..*"
   "^io\\.github\\.cognitect-labs/.*"])

(defn- coord-matches-allowlist?
  "Does `coord` (a symbol like `'io.foo/bar`) match any pattern in
   `patterns`? Both group-id and full coord are tried."
  [coord patterns]
  (let [s (str coord)]
    (some (fn [p]
            (try (re-find (re-pattern p) s)
                 (catch Throwable _ nil)))
          patterns)))

(defn allowlist-policy
  "A policy that auto-approves coords matching the ctx's allowlist
   (default: `default-allowlist`); anything else returns :ask-human."
  [coord _ctx]
  (let [patterns (or (ec/get-state ALLOWLIST-KEY) default-allowlist)]
    (if (coord-matches-allowlist? coord patterns)
      :approve
      :ask-human)))

;; ============================================================================
;; Policy installation
;; ============================================================================

(defn install-policy!
  "Install a custom policy fn `(fn [coord ctx])` for the current ctx.
   Defaults to `allowlist-policy` if never installed."
  [policy-fn]
  (ec/swap-state! POLICY-KEY (constantly policy-fn)))

(defn set-allowlist!
  "Override the default allowlist with `patterns` (a vector of regex
   strings) on the current ctx."
  [patterns]
  (ec/swap-state! ALLOWLIST-KEY (constantly (vec patterns))))

(defn current-policy
  "The currently installed policy fn, or `allowlist-policy` if none."
  []
  (or (ec/get-state POLICY-KEY) allowlist-policy))

;; ============================================================================
;; Pending requests (for human / manager escalation)
;; ============================================================================

(defn pending-requests
  "Return the map {request-id → {:coord :requested-at :deferred}} of
   approval requests waiting on a human decision."
  []
  (or (ec/get-state PENDING-KEY) {}))

(defn- record-pending! [req]
  (ec/swap-state! PENDING-KEY (fn [m] (assoc (or m {}) (:id req) req))))

(defn- remove-pending! [id]
  (ec/swap-state! PENDING-KEY (fn [m] (dissoc (or m {}) id))))

(defn decide!
  "Resolve a pending approval request. `decision` is `:approve` or
   `{:deny <reason-string>}`. Returns the decision or nil if no such
   request is pending."
  [request-id decision]
  (let [{:keys [deferred ctx]} (get (pending-requests) request-id)]
    (when deferred
      ;; sync/deliver! is the safe cross-thread delivery primitive.
      (binding [ec/*execution-context* (or ctx ec/*execution-context*)]
        (sync/deliver! deferred decision))
      (remove-pending! request-id)
      decision)))

(defn approve!
  "Convenience: resolve a pending request as approved."
  [request-id]
  (decide! request-id :approve))

(defn deny!
  "Convenience: resolve a pending request as denied with `reason`."
  [request-id reason]
  (decide! request-id {:deny (str reason)}))

;; ============================================================================
;; The gate
;; ============================================================================

(defn- await-on-thread
  "Block the calling thread until spindel Deferred `d` is delivered;
   return its value. Bridges spindel's spin-async world (where
   reading a Deferred uses `(await d)` inside a spin) into a regular
   blocking thread (the agent's tool-execution thread).

   Mechanism: spawn a future whose body is a spin that awaits the
   deferred and delivers to a plain Clojure promise; the calling
   thread derefs the promise. Identical pattern to
   dvergr.discourse.generation/future-handle, inlined here so the
   bookkeeping fn stays self-contained and we don't pull discourse
   into the sandbox.deps dependency graph."
  [ctx d]
  (let [p (promise)]
    (future
      (try
        (binding [ec/*execution-context* ctx]
          (sp/spawn!
           (sp/spin
            (deliver p (sp/await d)))))
        (catch Throwable t
          (deliver p {:deny (str "Bridge error: " (.getMessage t))}))))
    @p))

(defn- ask-human
  "Park the decision on a fresh spindel Deferred, log it, surface it
   on the peer-bus, and block until someone calls `decide!`. Returns
   the decision map (:approve | {:deny reason}).

   Using a spindel Deferred (not a Clojure promise) keeps the state
   fork-safe via ec-state and lets spin code observe / compose with
   the request if needed. We bridge to a thread-blocking read via
   `await-on-thread`."
  [coord _ctx]
  (let [ctx        (ec/current-execution-context)
        request-id (str (random-uuid))
        d          (sync/create-deferred ctx)
        req        {:id           request-id
                    :coord        coord
                    :requested-at (java.util.Date.)
                    :deferred     d
                    :ctx          ctx}]
    (record-pending! req)
    (tel/log! {:id :sandbox.deps/approval-requested
               :data {:request-id request-id :coord coord}}
              "Agent requested off-list dep — awaiting manager decision")
    (try
      (peer-bus/post! {:type      :dvergr/dep-approval-requested
                       :request-id request-id
                       :coord     coord})
      (catch Throwable _))
    (await-on-thread ctx d)))

(defn check-coord!
  "Run `coord` through the installed policy. Returns the final
   decision: `:approve` or `{:deny <reason>}`. Blocks if the policy
   escalates to a human."
  ([coord] (check-coord! coord {}))
  ([coord ctx]
   (let [policy   (current-policy)
         outcome  (policy coord ctx)]
     (case outcome
       :approve outcome
       :ask-human (ask-human coord ctx)
       outcome))))

;; ============================================================================
;; Add-libs wrapper
;; ============================================================================

(defn- libs->coords
  "`libs` is a map {sym {:mvn/version ...}} or {sym {:git/url ...}}.
   Returns a seq of sym keys."
  [libs]
  (cond
    (map? libs)
    (keys libs)

    (and (sequential? libs) (every? symbol? libs))
    libs

    :else
    (throw (ex-info "add-libs expects a map {coord {:mvn/version ...}} or a vector of coord symbols"
                    {:type :dvergr/deps-arg :got libs}))))

;; ============================================================================
;; Namespace denylist (sensitive host internals)
;; ============================================================================

(def default-namespace-denylist
  "Namespaces the sandbox MUST NOT mirror into the agent's SCI ctx,
   even when they're on the host classpath. The curated SCI surface
   (dvergr.sandbox/add-*-ns! family) is the agent's authorized view
   of dvergr internals; reaching for the raw host equivalents would
   leak the daemon's authority into the sandbox."
  ["^dvergr\\.daemon($|\\..*)"
   "^dvergr\\.config($|\\..*)"
   "^dvergr\\.cli($|\\..*)"
   "^dvergr\\.web($|\\..*)"
   "^dvergr\\.actors($|\\..*)"
   "^dvergr\\.skills($|\\..*)"
   "^dvergr\\.rooms($|\\..*)"
   "^dvergr\\.discourse($|\\..*)"
   "^dvergr\\.sandbox($|\\..*)"
   "^dvergr\\.tools($|\\..*)"
   "^dvergr\\.registry($|\\..*)"
   ;; clojure.repl and clojure.repl.deps are intentionally custom-wrapped
   ;; in dvergr.sandbox; mirroring the host versions would bypass our gates.
   "^clojure\\.repl$"
   "^clojure\\.repl\\..*"
   ;; tools.deps is the dep-resolution machinery; nothing good comes
   ;; from giving an agent direct handle on it.
   "^clojure\\.tools\\.deps.*"
   ;; sci's own internals — keep the sandbox from peeking at its host.
   "^sci\\..*"
   ;; spindel + yggdrasil — the substrate that backs forks. Manager
   ;; controls those; agents should not.
   "^org\\.replikativ\\.spindel($|\\..*)"
   "^org\\.replikativ\\.yggdrasil($|\\..*)"])

(def ^:private NS-DENYLIST-KEY [:dvergr/deps-policy :ns-denylist])

(defn set-namespace-denylist!
  "Override the default namespace denylist with regex patterns."
  [patterns]
  (ec/swap-state! NS-DENYLIST-KEY (constantly (vec patterns))))

(defn namespace-denied?
  "Is `ns-sym` blocked from being mirrored into an SCI ctx?"
  [ns-sym]
  (let [patterns (or (ec/get-state NS-DENYLIST-KEY) default-namespace-denylist)
        s        (str ns-sym)]
    (boolean
     (some (fn [p] (try (re-find (re-pattern p) s)
                        (catch Throwable _ nil)))
           patterns))))

;; ============================================================================
;; Mirror host namespace into SCI
;; ============================================================================

(defn- ns->sci-entries
  "Walk `(ns-publics ns)` and produce the {sym → value} map SCI
   wants for `add-namespace!`. Vars are deref'd to their current
   value. Macros are flagged so the agent gets a meaningful error
   (SCI can't run host macros at sandbox eval time)."
  [^clojure.lang.Namespace ns]
  (into {}
        (for [[sym v] (ns-publics ns)
              :let [m (meta v)
                    macro? (:macro m)]]
          [sym
           (if macro?
             ;; Macros can't be expanded inside SCI without serious
             ;; surgery. Expose them as a fn that errors clearly so
             ;; the agent learns what's available vs not.
             (fn [& _]
               (throw (ex-info
                       (str "Macro " (ns-name ns) "/" sym
                            " can't be invoked from the SCI sandbox. "
                            "Use the function form if one exists, or "
                            "ask the manager to expose this via the "
                            "curated SCI surface.")
                       {:type :dvergr/macro-not-in-sci
                        :ns   (ns-name ns)
                        :name sym})))
             (deref v))])))

(defn ensure-mirrored!
  "Try to load `ns-sym` on the host and mirror its publics into
   `sci-ctx`. Returns true if mirrored (or already present),
   false if denied or unloadable.

   Used by both `mirror-namespaces-into-sci!` (post-add-libs walk)
   and the `:load-fn` interceptor (lazy on require)."
  [sci-ctx ns-sym]
  (require 'sci.core)
  (let [add-namespace! @(resolve 'sci.core/add-namespace!)]
    (cond
      (namespace-denied? ns-sym)
      (do (tel/log! {:id :sandbox.deps/ns-denied
                     :data {:ns ns-sym}}
                    "Namespace mirror denied by denylist")
          false)

      :else
      (try
        ;; Require on the host (no-op if already loaded). This needs
        ;; to happen on a thread with normal var bindings.
        (require ns-sym)
        (let [ns* (find-ns ns-sym)]
          (when ns*
            (add-namespace! sci-ctx ns-sym (ns->sci-entries ns*))
            (tel/log! {:id :sandbox.deps/ns-mirrored
                       :data {:ns ns-sym
                              :public-count (count (ns-publics ns*))}}
                      "Namespace mirrored into SCI")
            true))
        (catch Throwable t
          (tel/log! {:level :warn :id :sandbox.deps/ns-mirror-failed
                     :data {:ns ns-sym :error (.getMessage t)}})
          false)))))

(defn- mirror-namespaces-into-sci!
  "After the host classpath has new JARs on it, walk every namespace
   newly loaded as a side effect of add-libs and register their
   publics in the SCI ctx.

   Most lib namespaces won't be loaded yet at this point — add-libs
   only mounts JARs; it doesn't auto-require them. The lazy
   `:load-fn` interceptor catches them when the agent first requires.
   We still do this eager pass for namespaces that the host happens
   to load right away."
  [sci-ctx pre-ns-set]
  (let [post-ns-set (set (all-ns))
        new-nss     (clojure.set/difference post-ns-set pre-ns-set)]
    (doseq [^clojure.lang.Namespace ns* new-nss]
      (ensure-mirrored! sci-ctx (ns-name ns*)))
    new-nss))

;; ============================================================================
;; SCI :load-fn — lazy mirror-on-require
;; ============================================================================

(defn make-load-fn
  "Return an SCI :load-fn that mirrors host namespaces on demand.

   Install via `(sci/merge-opts ctx {:load-fn (make-load-fn ctx)})`
   at ctx-build time. When an agent does `(require '[foo.bar])`
   and foo.bar isn't already in SCI's namespaces map, SCI calls
   this fn. We:

     1. Check the denylist; if denied, return nil (SCI errors with
        a standard 'could not find namespace' message — the agent
        sees this and can ask the manager).
     2. Try to require the namespace on the host.
     3. On success, mirror publics via `sci/add-namespace!`.
     4. Return a non-nil map WITHOUT `:handled true` — that signals
        SCI to skip the source-eval step but still run its alias /
        refer setup (so `:as` / `:refer` work in the require form).

   On host failure (no JAR / no source), return nil; SCI's default
   error path takes over."
  [sci-ctx]
  (fn [{:keys [namespace libname] :as req}]
    ;; Try the agent's OWN workspace files FIRST (a room repo source), so
    ;; `(require '[my.ns])` of code the agent just wrote resolves. This load-fn
    ;; REPLACES the workspace load-fn set at ctx build (SCI keeps one), so it must
    ;; carry it — else installing the deps surface silently breaks workspace
    ;; require (the bug Phase-1 hit). Fall through to host-namespace mirroring
    ;; (for add-libs'd deps) only when the workspace doesn't have it.
    (or (workspace/load-fn req)
        (let [ns-sym (or namespace libname)]
          (when (ensure-mirrored! sci-ctx ns-sym)
            ;; Returning a map without :handled true causes SCI to fall
            ;; through to `handle-require-libspec-env`, which reads the
            ;; freshly-registered namespace out of its env and creates
            ;; the alias / refers from the original require form.
            {:dvergr/mirrored ns-sym})))))

(defn add-libs!
  "Gated add-libs: invoke the policy, on approve call the host
   `clojure.repl.deps/add-libs` and mirror newly-loaded namespaces
   into `sci-ctx`. On deny, throw ex-info.

   `libs` is the same shape as host add-libs:
     '{io.foo/bar {:mvn/version \"1.0\"}}
   or a vector of coords:
     '[io.foo/bar io.baz/qux]"
  [sci-ctx libs]
  (require 'clojure.repl.deps)
  (let [host-add-libs (or (resolve 'clojure.repl.deps/add-libs)
                          (throw (ex-info "clojure.repl.deps/add-libs not available — needs Clojure 1.12+"
                                          {:type :dvergr/deps-arg})))
        coords        (libs->coords libs)
        ;; Check every coord; first denial wins
        decisions     (mapv (fn [c] [c (check-coord! c {})]) coords)
        denials       (filter (fn [[_ d]] (map? d)) decisions)]
    (cond
      (seq denials)
      (let [[coord {:keys [deny]}] (first denials)]
        (tel/log! {:id :sandbox.deps/denied
                   :data {:coord coord :reason deny}}
                  "Dep denied by policy")
        (throw (ex-info (str "Dep denied: " coord " — " deny)
                        {:type   :dvergr/dep-denied
                         :coord  coord
                         :reason deny})))

      :else
      (let [pre-ns (set (all-ns))]
        ;; clojure.repl.deps/add-libs guards on `clojure.core/*repl*`
        ;; being bound to true. We're a server-side call, not a REPL,
        ;; but the gate has already enforced human approval — so bind
        ;; the flag while we invoke.
        (with-bindings {#'clojure.core/*repl* true}
          (host-add-libs (if (map? libs)
                           libs
                           (into {} (for [c coords] [c {:mvn/version "RELEASE"}])))))
        (tel/log! {:id :sandbox.deps/approved
                   :data {:coords coords}}
                  "Deps approved + loaded")
        (let [new-nss (mirror-namespaces-into-sci! sci-ctx pre-ns)]
          {:status :loaded
           :coords (vec coords)
           :namespaces (mapv ns-name new-nss)})))))

;; ============================================================================
;; sync-deps — reconcile classpath with the fork's deps.edn
;; ============================================================================

(defn- read-deps-edn
  "Read the deps map from `<worktree>/deps.edn`. Returns the full
   parsed EDN map, or nil if the file is missing / unreadable."
  [worktree]
  (try
    (let [f (io/file worktree "deps.edn")]
      (when (and (.exists f) (.canRead f))
        (edn/read-string (slurp f))))
    (catch Throwable t
      (tel/log! {:level :warn :id :sandbox.deps/deps-edn-read-failed
                 :data {:worktree worktree :error (.getMessage t)}})
      nil)))

(defn- maven-coord?
  "Coord we know how to reload via add-libs (mvn-resolved). Local-root
   and git coords are also fine for add-libs but we exclude them from
   the diff because they generally don't need reloading after a clj
   restart — the host already had them via :local/root."
  [coord-spec]
  (and (map? coord-spec)
       (or (contains? coord-spec :mvn/version)
           (contains? coord-spec :git/url))))

(defn sync-deps!
  "Read the fork's deps.edn and call add-libs! for any maven/git
   coords that aren't yet loaded into the running JVM.

   Differs from stdlib clojure.repl.deps/sync-deps: we read THIS
   fork's deps.edn (via dvergr.substrate.git/current-worktree-path), not the
   JVM's launch basis. So when the agent edits deps.edn in their
   worktree, calling sync-deps! brings those new deps into the
   running runtime — even though the JVM was started by the daemon
   from a different deps.edn.

   Every coord still flows through the policy gate; off-list coords
   require manager approval. Returns the same shape as add-libs!
   ({:status :loaded :coords ... :namespaces ...}), or
   {:status :no-changes :reason ...} when there's nothing to do."
  [sci-ctx]
  (require 'dvergr.substrate.git)
  (let [worktree (or ((requiring-resolve 'dvergr.substrate.git/current-worktree-path))
                     (System/getProperty "user.dir"))
        deps-map (read-deps-edn worktree)]
    (cond
      (nil? deps-map)
      {:status :no-changes :reason "no readable deps.edn in worktree"}

      (empty? (:deps deps-map))
      {:status :no-changes :reason ":deps key is empty"}

      :else
      (let [candidates (->> (:deps deps-map)
                            (filter (fn [[_ spec]] (maven-coord? spec)))
                            (into {}))]
        (if (empty? candidates)
          {:status :no-changes
           :reason "only :local/root deps — already on classpath"
           :worktree worktree}
          (do
            (tel/log! {:id :sandbox.deps/sync-deps-starting
                       :data {:worktree worktree
                              :candidate-count (count candidates)}}
                      "sync-deps reading worktree deps.edn")
            (add-libs! sci-ctx candidates)))))))

