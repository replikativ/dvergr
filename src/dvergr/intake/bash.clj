(ns dvergr.intake.bash
  "Shell access for agents, backed by muschel.

   Two doors lead into one per-chat-ctx muschel session:

     - `shell` tool   — top-level JSON-schema tool registered in
                        dvergr.tools; what agents reach for by default
                        when the task is shell-native (git, ls, rg, …).
     - intake.bash/run — SCI-exposed fn for composing bash inside
                        clojure_eval (parse output, branch on exit
                        codes, etc.).

   Both use `(get-or-create-session chat-ctx)` so `cd`, env updates,
   and bg jobs accumulate consistently regardless of which door the
   agent walked through.

   ## Containment

   The session runs against a `muschel.host.builtin/BuiltinHost`
   wrapping a `muschel.fs.disk/DiskFS` rooted at the chat's
   workspace. So:

     - All builtins (ls, cat, sed, …) read/write the FS through the
       muschel.fs protocol; paths outside the root return 'No such
       file or directory'.
     - Redirects (`< file`, `> file`, `>> file`) route through the
       same FS — `wc -l < /etc/passwd` is refused.
     - Glob expansion (`*.txt`) walks the FS, never real disk.
     - The builtin host's `:fallback-allowlist` controls which
       system binaries can be invoked at all. Default is `git` +
       `gh` for the common dev workflow. Anything not in builtins
       or the allowlist refuses with exit 126.

   ## Substrate-fork interaction

   The session lives in the chat-ctx's spindel execution context.
   When dvergr.discourse/fork-room with :isolation :ctx forks the
   chat-ctx, the muschel session's env-atom is CoW-forked alongside
   — a worker's `cd` doesn't leak to the parent. Filesystem effects
   of the commands themselves still need a git-worktree workspace
   to be truly contained (PR #8 — yggdrasil git-worktree wiring)."
  (:require [clojure.string :as str]
            [muschel.builtins.posix :as posix]
            [muschel.core :as m]
            [muschel.env :as menv]
            [muschel.fs.disk :as fs.disk]
            [muschel.host.builtin :as hb]
            [muschel.host.jvm :as host.jvm]
            [muschel.session :as msession]
            [muschel.session.spindel :as ms]
            [org.replikativ.spindel.engine.core :as ec]
            [taoensso.telemere :as tel]))

;; ============================================================================
;; Configuration
;; ============================================================================

(def default-fallback-allowlist
  "System binaries the BuiltinHost may delegate to. Everything else
   refuses unless it's a Clojure builtin (touch / ls / sed / awk / …).

   Reach for this list when you want to expand what the agent can call.
   Pair with a permit rule if you want to allow only a subset of a
   tool's argv (e.g. `git push` allow / `git push --force` deny)."
  #{"git"
    "gh"
    "clj"
    "clojure"
    "bb"
    "lein"
    "boot"
    "npm"
    "npx"
    "yarn"
    "pnpm"
    "jq"
    "make"
    "cargo"
    "rustc"
    "go"
    "python"
    "python3"
    "pip"
    "pip3"
    "uv"
    "node"})

(defn default-workspace
  "JVM cwd. Agents launched alongside a project see the project root.
   Override via opts to `make-host` if you want per-chat isolation."
  []
  (System/getProperty "user.dir"))

;; ---------------------------------------------------------------------------
;; Sanitised environment for the agent's shell
;;
;; muschel.env/new-env slurps `System/getenv()` wholesale, which means an
;; agent calling `env` or `printenv` sees every secret in the daemon
;; process: OPENAI_API_KEY, GITHUB tokens, CLOJARS passwords, etc.
;;
;; We construct an explicit allowlist (vars the agent legitimately needs)
;; plus a denylist of name-fragments that always strip (the secrets
;; suffixes). Anything else from the host gets dropped.
;; ---------------------------------------------------------------------------

(def default-env-allow
  "Vars copied from the daemon's environment into the agent's shell.
   Curated to cover what real tools (git, npm, clj) reach for, without
   exposing identity / credentials."
  #{"PATH" "HOME" "USER" "LOGNAME" "SHELL" "LANG" "LC_ALL"
    "TERM" "COLORTERM" "PAGER" "TZ"
    "JAVA_HOME" "NODE_PATH" "GOPATH"
    "GIT_AUTHOR_NAME" "GIT_AUTHOR_EMAIL"
    "GIT_COMMITTER_NAME" "GIT_COMMITTER_EMAIL"
    "GIT_EDITOR"})

(def default-env-deny-substrings
  "Lowercased substrings that mark a var as secret. Any var whose
   name contains one of these is excluded even if it's on the allow
   list — defence-in-depth so a misnamed var doesn't slip through."
  #{"token" "secret" "key" "password" "pass" "auth"})

(defn- secret-name? [^String n]
  (let [lower (str/lower-case n)]
    (some #(.contains lower ^String %) default-env-deny-substrings)))

(defn- sanitise-env-map
  "Return a {name → value} subset of `host-env` containing only the
   allowlisted vars whose names don't trip the deny-substring list."
  [host-env]
  (into {}
        (for [[k v] host-env
              :when (and (default-env-allow k)
                         (not (secret-name? k)))]
          [k v])))

(defn make-sandboxed-env
  "Build a muschel env value with only the curated subset of the host
   process environment. The agent sees PATH (so allowlisted system
   binaries resolve), HOME, USER, locale + git author identity — but
   no API keys, tokens, or other credentials.

   Options:
     :cwd       starting cwd (default: workspace / JVM cwd)
     :extra     {name → value} to add (e.g. project-specific vars)"
  [{:keys [cwd extra]}]
  (let [host-env (into {} (System/getenv))
        clean-env (sanitise-env-map host-env)
        all-env (merge clean-env extra)
        cwd' (or cwd (default-workspace))]
    (-> (menv/empty-env)
        (assoc :cwd cwd'
               :prev-cwd cwd'
               :vars (into {} (for [[k v] all-env]
                                [k {:value v :exported? true :readonly? false}]))))))

;; ============================================================================
;; Per-chat-ctx host (forks with the chat-ctx)
;; ============================================================================

(def ^:private session-path [:dvergr/bash-session])
(def ^:private host-path    [:dvergr/bash-host])

(defn make-host
  "Build a BuiltinHost wrapping a DiskFS rooted at `:workspace`.

   Options:
     :workspace          (default: JVM cwd)
     :fallback-allowlist (default: default-fallback-allowlist)
     :builtins           (default: muschel.builtins.posix/standard)"
  [{:keys [workspace fallback-allowlist builtins]
    :or {workspace          (default-workspace)
         fallback-allowlist default-fallback-allowlist
         builtins           posix/standard}}]
  (let [fs (fs.disk/make workspace)]
    (hb/make {:fs fs
              :fallback-host (host.jvm/make)
              :builtins builtins
              :fallback-allowlist fallback-allowlist})))

(defn get-or-create-session!
  "Return the chat-ctx's muschel session, creating it on first use.

   Stored under [:dvergr/bash-session] in the chat-ctx's spindel-ctx
   state so it forks via CoW alongside everything else when the
   chat-ctx is forked."
  [chat-ctx]
  (binding [ec/*execution-context* (:spindel-ctx chat-ctx)]
    (or (ec/get-state session-path)
        (let [s (ms/spindel-session-using (:spindel-ctx chat-ctx))]
          (ec/swap-state! session-path (fn [existing] (or existing s)))
          (ec/get-state session-path)))))

(defn get-or-create-host!
  "Return the chat-ctx's BuiltinHost, creating it on first use.
   Stored under [:dvergr/bash-host] alongside the session so both
   travel together through fork-context."
  [chat-ctx]
  (binding [ec/*execution-context* (:spindel-ctx chat-ctx)]
    (or (ec/get-state host-path)
        (let [h (make-host {})]
          (ec/swap-state! host-path (fn [existing] (or existing h)))
          (ec/get-state host-path)))))

;; ============================================================================
;; Public API
;; ============================================================================

(defn check
  "Permit-check a bash source string against the default ruleset.

   Returns muschel's check map; `(= :allow (:decision result))` is the
   typical green-light. Useful for `clojure_eval` agents that want to
   inspect what a command would be allowed to do before committing."
  [cmd]
  (let [ast (m/parse cmd)]
    (m/check {:ast ast :rulesets [m/default-rules]
              :prompter m/allow-all-prompter})))

(defn builtins
  "Return a sorted seq of builtin command names the agent's
   BuiltinHost recognises. Useful for SCI-level introspection
   when an agent needs to know what tooling it has."
  []
  (sort (keys posix/standard)))

(defn allowlist
  "Return the set of system-binary names the BuiltinHost will
   delegate to."
  []
  default-fallback-allowlist)

(defn run
  "Execute a bash source string against the chat-ctx's session.

   Options:
     :chat-ctx     - explicit chat-ctx (otherwise resolved from
                     *current-chat-ctx* binding, if you set one)
     :host         - override the muschel host (default: BuiltinHost
                     rooted at the workspace via get-or-create-host!)
     :max-out      - cap stdout / stderr returned (default 8000 chars
                     each — protects the agent context window)

   Returns:
     {:stdout str :stderr str :exit int :cwd str :truncated? bool}
     or {:error str :exit 126} on permit deny."
  [chat-ctx cmd & {:keys [host max-out]
                   :or {max-out 8000}}]
  (try
    (let [sess  (get-or-create-session! chat-ctx)
          ast   (m/parse cmd)
          host  (or host (get-or-create-host! chat-ctx))
          ;; Build a sanitised env each run so secrets stay out. The
          ;; session's own env tracks shell-mutated state (cd, var
          ;; assignments) — we merge that on top of the sanitised
          ;; base so PATH / HOME / etc. always come from our curated
          ;; allowlist, never from a poisoned session.
          base-env  (make-sandboxed-env {})
          sess-env  (binding [ec/*execution-context* (:spindel-ctx chat-ctx)]
                      (msession/-env sess))
          env0      (cond-> base-env
                      (:cwd sess-env)  (assoc :cwd (:cwd sess-env))
                      (:vars sess-env) (update :vars merge (:vars sess-env)))
          permit-result (m/check {:ast ast
                                  :rulesets [m/default-rules]
                                  :prompter m/allow-all-prompter})]
      (if (= :deny (:decision permit-result))
        (let [denied (->> (:per-call permit-result)
                          (filter #(= :deny (:decision %)))
                          first)]
          (tel/log! {:level :warn :id :bash/denied
                     :data {:cmd cmd :reason (:reason denied)}}
                    "Bash command denied")
          {:error (str "permit denied: " (or (:reason denied) "no reason"))
           :exit 126})
        (let [{:keys [stdout stderr exit env]}
              (m/run-and-capture env0 ast
                                 {:session sess :host host
                                  ;; agents don't write stdin; never
                                  ;; inherit System/in (would block
                                  ;; under nREPL / when daemonised).
                                  :in (java.io.ByteArrayInputStream.
                                        (.getBytes ""))})
              clip (fn [s]
                     (if (and s (> (count s) max-out))
                       (str (subs s 0 max-out) "\n[...truncated]")
                       (or s "")))]
          {:stdout     (clip stdout)
           :stderr     (clip stderr)
           :exit       exit
           :cwd        (some-> env :cwd)
           :truncated? (or (and stdout (> (count stdout) max-out))
                           (and stderr (> (count stderr) max-out)))})))
    (catch Exception e
      (tel/log! {:level :error :id :bash/error
                 :data {:cmd cmd :error (.getMessage e)}}
                "Bash run error")
      {:error (.getMessage e) :exit 1})))
