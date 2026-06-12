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
            [dvergr.substrate.git :as dgit]
            [dvergr.agent.process :as dproc]
            [muschel.budget :as mbudget]
            [muschel.builtins.posix :as posix]
            [muschel.core :as m]
            [muschel.env :as menv]
            [muschel.fs.disk :as fs.disk]
            [muschel.host :as host]
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
   refuses unless it's a Clojure builtin (touch / ls / sed / awk /
   jq / …).

   ## Why so minimal

   Every entry here is a tool that runs OUTSIDE muschel's FS
   containment. Once we hand control to `python3` / `node` / `clj`
   / `npm` / `cargo` / etc., the tool's own stdlib can read+write
   any path on real disk — defeating the entire sandbox. The
   agent already has `clojure_eval` (in-process SCI sandbox) for
   any scripting need; it doesn't need a second runtime that
   escapes containment.

   Default is therefore the smallest set that:
     - is well-scoped (does only what it advertises)
     - is what agents *cannot* reasonably reimplement in
       clojure_eval (network protocols, git plumbing)

   `git` qualifies — it has escape hatches (`git -C /etc ...`,
   post-commit hooks, `git clone <url> /any/path`) but most uses
   are bounded to the working tree, and the permit layer
   already denies the obvious foot-guns (`git push --force`,
   `git reset --hard`, `git clean -fd`).

   `gh` is NOT here by default. The user explicitly authorising
   GitHub interaction (PR creation, issue comments, releases)
   is a per-project / per-chat decision, not a default — once an
   agent has gh, it can speak in the user's name to the wider
   world. Re-enable via `:fallback-allowlist` when you want PR
   workflows specifically.

   ## Extending per-project

   Override via `:fallback-allowlist` on `make-host` if a specific
   project legitimately needs a heavier tool (gh for PR creation,
   pytest for a Python repo, cargo for a Rust crate). That choice
   belongs to the human running dvergr — not to a default that
   ships open."
  #{"git"})

;; ---------------------------------------------------------------------------
;; git is the one allowlisted real-process fallback, and it runs UNJAILED (it
;; gets raw args, so `git clone /abs`, `git -C /abs`, `git push <remote>` reach
;; the host filesystem / network). We can't rely on the DiskFS jail for it, so we
;; gate it at the permit layer: an ALLOWLIST of workspace-local subcommands.
;; Network commands (clone/fetch/push/pull/remote/submodule) and path-redirect
;; forms (`git -C …`, `--git-dir`, `--work-tree` — a flag BEFORE the subcommand,
;; so it can't prefix-match an allowed `["git" <subcmd>]`) all fall through to the
;; default-deny. Read + local-write only; no exfil, no tamper, no escape.
;; ---------------------------------------------------------------------------

(def git-local-subcommands
  "git subcommands the sandboxed agent may run — read + workspace-local write.
   Everything NOT here (clone/fetch/push/pull/remote/submodule/archive/bundle/
   init/apply/am/format-patch/send-email/daemon/svn/… and every `git -C`/
   `--git-dir`/`--work-tree` form) is denied."
  #{"status" "log" "diff" "show" "branch" "describe" "rev-parse" "rev-list"
    "ls-files" "ls-tree" "blame" "reflog" "shortlog" "for-each-ref" "show-ref"
    "cat-file" "name-rev" "symbolic-ref" "add" "commit" "checkout" "switch"
    "restore" "reset" "stash" "merge" "rebase" "cherry-pick" "revert" "mv" "rm"
    "tag"})
;; NOTE: `config` is deliberately NOT allowed — `git config core.fsmonitor=…`,
;; `core.sshCommand`, `core.pager`, alias.* etc. are config-driven RCE. The agent
;; gets its commit identity + hooks-off from env (git-security-env) instead.

(def git-sandbox-rules
  "muschel permit ruleset, applied AFTER the defaults (last-match-wins): deny ALL
   `git`, then re-allow only `git <local-subcommand>`. Closes the unjailed-git
   exfil/tamper/escape hole (verified: `git clone /abs` exfiltrated host repos)."
  [{:tool :bash :pattern {:kind :cmd-name :name "git"} :action :deny
    :reason "git is restricted to workspace-local subcommands in the sandbox"
    :origin :default}
   {:tool :bash :pattern {:kind :argv-vec :vec ["git" git-local-subcommands]}
    :action :allow :origin :default}])

(def ^:private git-identity-defaults
  "Commit identity for the agent (so it can commit without the disallowed
   `git config`). A host GIT_AUTHOR_*/GIT_COMMITTER_* overrides these."
  {"GIT_AUTHOR_NAME" "dvergr agent"    "GIT_AUTHOR_EMAIL" "agent@dvergr.local"
   "GIT_COMMITTER_NAME" "dvergr agent" "GIT_COMMITTER_EMAIL" "agent@dvergr.local"})

(def ^:private git-security-env
  "Command-level git config (highest precedence — beats any repo-local config the
   agent writes): disable ALL hooks so a planted `.git/hooks/*` can't execute on a
   local commit, ignore system config, and never prompt for credentials."
  {"GIT_CONFIG_COUNT"   "2"
   "GIT_CONFIG_KEY_0"   "core.hooksPath"   "GIT_CONFIG_VALUE_0" "/dev/null"
   "GIT_CONFIG_KEY_1"   "core.fsmonitor"   "GIT_CONFIG_VALUE_1" "false"
   "GIT_CONFIG_NOSYSTEM" "1"
   "GIT_CONFIG_GLOBAL"   "/dev/null"
   "GIT_TERMINAL_PROMPT" "0"})

(defn default-workspace
  "Resolve the agent's workspace path.

   Priority:
   1. The registered yggdrasil git system's current working-tree
      path. When the surrounding execution context has been forked,
      this is the **forked** worktree, so each fork's bash session
      sees its own isolated FS root automatically — the whole
      isolation story flows through this one lookup.
   2. JVM cwd as a fallback for when no git system is registered
      (e.g. unit tests, bare REPL).

   Callers can still pass `:workspace` explicitly to `make-host` to
   override this."
  []
  ;; current-worktree-path → current-execution-context THROWS when no ctx is bound
  ;; (not nil), so guard it — else the documented fallback is unreachable.
  (or (try (dgit/current-worktree-path) (catch Throwable _ nil))
      ;; No git system in scope → the isolated `.dvergr/workspace` clone, NEVER the
      ;; host JVM cwd (the dvergr source tree). The shell must not escape the sandbox.
      (dgit/safe-workspace-root)))

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
     :cwd       SANDBOX-relative starting cwd (default `/` = the mount root, i.e.
                the worktree under make-host's `:mount-at \"/\"`). muschel ≥ 0.2.16
                cwd/paths are sandbox-relative, NOT real host paths.
     :extra     {name → value} to add (e.g. project-specific vars)"
  [{:keys [cwd extra]}]
  (let [host-env (into {} (System/getenv))
        clean-env (sanitise-env-map host-env)
        ;; identity defaults (overridden by a host GIT_AUTHOR_* if the operator set
        ;; one) so the agent can commit without `git config`; security env LAST so
        ;; it can't be overridden — command-level, beats any repo-local config the
        ;; agent might write: hooks off (planted .git/hooks/* never runs), no system
        ;; config, never prompt for credentials.
        all-env (merge git-identity-defaults clean-env extra git-security-env)
        cwd' (or cwd "/")]
    (-> (menv/empty-env)
        (assoc :cwd cwd'
               :prev-cwd cwd'
               :vars (into {} (for [[k v] all-env]
                                [k {:value v :exported? true :readonly? false}]))))))

;; ============================================================================
;; Per-chat-ctx host (forks with the chat-ctx)
;; ============================================================================

;; Host + session are cached in the chat-ctx's spindel state. The cache
;; key is the workspace path — when a chat-ctx fork lands the
;; execution context on a fresh git worktree, the new path becomes a
;; cache miss and a host/session rooted at that worktree gets
;; built. The parent's host stays alive under its own path, so future
;; runs back in the parent context still resolve to its workspace.
(defn- session-path [workspace] [:dvergr/bash-session workspace])
(defn- host-path    [workspace] [:dvergr/bash-host workspace])

(defn- git-guarded-host
  "Wrap `inner` host so EVERY spawn of `git` — however it is reached (direct, or
   smuggled through `sh -c`, `xargs`, `find -exec`, which bypass muschel's permit
   ruleset and hit `-spawn` straight) — must name an allowed workspace-local
   subcommand as its first argument, else it's denied. This is the spawn-boundary
   backstop behind `git-sandbox-rules`; without it the permit ruleset is bypassable
   (security audit C2: `sh -c 'git clone …'` escaped). Delegates everything else."
  [inner]
  (reify host/Host
    (-write-string!   [_ sink s]       (host/-write-string! inner sink s))
    (-read-all-string [_ source]       (host/-read-all-string inner source))
    (-close!          [_ io]           (host/-close! inner io))
    (-string-sink     [_]              (host/-string-sink inner))
    (-sink->string    [_ sink]         (host/-sink->string inner sink))
    (-string-source   [_ s]            (host/-string-source inner s))
    (-open-file-sink  [_ path append?] (host/-open-file-sink inner path append?))
    (-open-file-source [_ path]        (host/-open-file-source inner path))
    (-file-info       [_ path]         (host/-file-info inner path))
    (-read-file       [_ path]         (host/-read-file inner path))
    (-make-pipe       [_]              (host/-make-pipe inner))
    (-async           [_ thunk]        (host/-async inner thunk))
    (-await           [_ handle]       (host/-await inner handle))
    (-spawn [_ {:keys [cmd args] :as opts}]
      (let [base (when cmd (last (str/split (str cmd) #"/")))]
        (when (and (= "git" base)
                   (not (contains? git-local-subcommands (first args))))
          (throw (ex-info (str "git not permitted in sandbox: git "
                               (str/join " " (take 2 args)))
                          {:muschel/denied true :cmd cmd :args args})))
        (host/-spawn inner opts)))))

(defn make-host
  "Build a BuiltinHost wrapping a DiskFS rooted at `:workspace`. The real-process
   fallback host is git-guarded (see `git-guarded-host`).

   `:mount-at \"/\"` makes the workspace dir ITSELF the sandbox root (so sandbox
   `/` ↔ the worktree), rather than muschel's default `/home/agent` + `/tmp`
   mount layout — i.e. the agent's whole filesystem IS the git worktree. The
   host translates sandbox paths → real disk at the OS-spawn boundary
   (`fs/physical-path`), so git/ls still run in the real worktree.

   Options:
     :workspace          (default: `default-workspace`)
     :fallback-allowlist (default: default-fallback-allowlist)
     :builtins           (default: muschel.builtins.posix/standard)"
  [{:keys [workspace fallback-allowlist builtins]
    :or {workspace          (default-workspace)
         fallback-allowlist default-fallback-allowlist
         builtins           posix/standard}}]
  (let [fs (fs.disk/make workspace {:mount-at "/"})]
    (hb/make {:fs fs
              :fallback-host (git-guarded-host (host.jvm/make))
              :builtins builtins
              :fallback-allowlist fallback-allowlist})))

(defn get-or-create-session!
  "Return the chat-ctx's muschel session for the **current** workspace
   (which depends on whether the surrounding execution context has
   been forked), creating it on first use.

   Sessions are seeded with a sanitised env via make-sandboxed-env so
   the agent never sees the daemon's API keys / tokens. The session's
   env-atom CoW-forks alongside the chat-ctx, so worker-side `cd` and
   `export` don't leak back into the parent's session — but only
   within a single workspace; a fork onto a fresh worktree gets a
   fresh session at that path.

   Stored under `[:dvergr/bash-session WORKSPACE-PATH]`."
  [chat-ctx]
  (binding [ec/*execution-context* (:spindel-ctx chat-ctx)]
    (let [ws (default-workspace)
          path (session-path ws)]
      (or (ec/get-state path)
          ;; cwd is the SANDBOX root "/" (= the worktree via make-host's
          ;; :mount-at "/"); `ws` (the real path) stays only as the cache key.
          (let [s (ms/spindel-session-using (:spindel-ctx chat-ctx)
                                            (make-sandboxed-env {:cwd "/"}))]
            (ec/swap-state! path (fn [existing] (or existing s)))
            (ec/get-state path))))))

(defn get-or-create-host!
  "Return the chat-ctx's BuiltinHost for the current workspace,
   creating it on first use. See `get-or-create-session!` for why
   this is keyed by workspace path."
  [chat-ctx]
  (binding [ec/*execution-context* (:spindel-ctx chat-ctx)]
    (let [ws (default-workspace)
          path (host-path ws)]
      (or (ec/get-state path)
          (let [h (make-host {:workspace ws})]
            (ec/swap-state! path (fn [existing] (or existing h)))
            (ec/get-state path))))))

;; ============================================================================
;; Public API
;; ============================================================================

(defn check
  "Permit-check a bash source string against the default ruleset.

   Returns muschel's check map; `(= :allow (:decision result))` is the
   typical green-light. Useful for `clojure_eval` agents that want to
   inspect what a command would be allowed to do before committing.

   Opts:
     :prompter  default `m/allow-all-prompter`; pass
                `m/deny-all-prompter` to inspect what the safe-default
                policy would reject."
  ([cmd] (check cmd {}))
  ([cmd {:keys [prompter] :or {prompter m/allow-all-prompter}}]
   (let [ast (m/parse cmd)]
     (m/check {:ast ast :rulesets [m/default-rules git-sandbox-rules]
               :prompter prompter}))))

;; ---------------------------------------------------------------------------
;; Interrupt + tracing helpers — bridge muschel's resource budgets and
;; introspection hooks into dvergr's process/telemere surface.
;; ---------------------------------------------------------------------------

(def ^:const default-timeout-ms
  "Per-call wall-clock ceiling. Most bash commands return in ms; a
   build can take a minute. Anything past this almost certainly is
   stuck in a loop or waiting on stdin. Override via `:timeout-ms`
   for legitimately long commands."
  60000)

(defn- process-abort-interrupt-fn
  "Build a muschel interrupt-fn that polls a dvergr.agent.process for
   abort status. Returns nil when no process is active."
  [process]
  (when process
    (fn []
      (when (dproc/aborted? process)
        (throw (ex-info "muschel: process aborted"
                        {:muschel/budget :process-aborted}))))))

(defn- trace-bridge
  "Build a muschel `:trace` map that mirrors every tool/fs/deny event
   into telemere so the TUI + log surface can see what the agent
   actually did."
  []
  {:on-tool (fn [e]
              (tel/log! {:level :debug :id ::tool
                         :data {:cmd (:name e) :argv (:argv e)
                                :exit (:exit e)
                                :stdout-bytes (:stdout-bytes e)
                                :stderr-bytes (:stderr-bytes e)
                                :duration-ms (:duration-ms e)}}))
   :on-fs   (fn [e]
              (tel/log! {:level :trace :id ::fs
                         :data {:op (:op e) :path (:path e)
                                :result (:result e)}}))
   :on-deny (fn [e]
              (tel/log! {:level :warn :id ::denied
                         :data {:tool (:tool e) :argv (:argv e)
                                :reason (:reason e)
                                :rule-id (:rule-id e)}}))})

(defn- trace-summary
  "Compress muschel's full trace snapshot into a small map the agent
   can read without flooding its context. Full trace stays accessible
   via the returned `:trace-full` when callers opt in."
  [trace]
  (when trace
    {:tool-count   (count (:tools trace))
     :fs-reads     (count (:reads trace))
     :fs-writes    (count (:writes trace))
     :denied-count (count (:denied trace))}))

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
     :host         override the muschel host (default: BuiltinHost
                   rooted at the workspace via get-or-create-host!)
     :max-out      cap stdout / stderr returned (default 8000 chars
                   each — protects the agent context window)
     :prompter     muschel prompter for `:ask` permit decisions
                   (default `m/allow-all-prompter`; pass
                   `m/deny-all-prompter` for safer policy)
     :timeout-ms   per-call wall-clock ceiling
                   (default 60_000; agents shouldn't block longer)
     :process      a `dvergr.agent.process` to honour for cancellation;
                   when aborted, the bash run interrupts at the next
                   safe point. Defaults to
                   `dvergr.agent.process/*current-process*` when bound,
                   so any agent turn wrapped as a process gets
                   cancellation for free.
     :trace-full?  include the full muschel trace snapshot (tools,
                   fs ops, denied) in the return map. Default false
                   — only a `:trace` summary is included.

   Returns:
     {:stdout str :stderr str :exit int :cwd str :truncated? bool
      :trace {:tool-count :fs-reads :fs-writes :denied-count}}
     or {:error str :exit 126 :permit ...} on permit deny."
  [chat-ctx cmd & {:keys [host max-out prompter timeout-ms process trace-full?]
                   :or {max-out      8000
                        prompter     m/allow-all-prompter
                        timeout-ms   default-timeout-ms
                        process      dproc/*current-process*}}]
  (try
    (let [sess (get-or-create-session! chat-ctx)
          host (or host (get-or-create-host! chat-ctx))
          ;; The session was seeded with a sanitised env at creation
          ;; time (make-sandboxed-env), so the snapshot is already
          ;; secret-free. Pass it as the explicit starting env so
          ;; one-shot library use stays well-defined.
          env0 (binding [ec/*execution-context* (:spindel-ctx chat-ctx)]
                 (msession/-env sess))
          interrupt-fn (mbudget/combine (process-abort-interrupt-fn process))
          {:keys [stdout stderr exit env permit trace]}
          (m/run-and-capture
           env0 cmd
           (cond-> {:session     sess
                    :host        host
                    ;; Agents don't write stdin; never inherit System/in
                    ;; (would block under nREPL / when daemonised).
                    :in          (java.io.ByteArrayInputStream. (.getBytes ""))
                    :permit      {:rulesets [m/default-rules git-sandbox-rules] :prompter prompter}
                    :timeout-ms  timeout-ms
                    :trace       (trace-bridge)}
             interrupt-fn (assoc :interrupt-fn interrupt-fn)))
          ;; muschel's run-and-capture doesn't propagate :denied-reason
          ;; (it lives one level deeper, on the run result). Re-derive
          ;; from :permit so we can build a useful error message.
          denied-reason (when (and permit (= :deny (:decision permit)))
                          (some->> permit :per-call
                                   (filter #(= :deny (:decision %)))
                                   first :reason))
          clip (fn [s]
                 (if (and s (> (count s) max-out))
                   (str (subs s 0 max-out) "\n[...truncated]")
                   (or s "")))]
      (if denied-reason
        (do (tel/log! {:level :warn :id ::denied
                       :data {:cmd cmd :reason denied-reason}}
                      "Bash command denied")
            (cond-> {:error  (str "permit denied: " denied-reason)
                     :exit   126
                     :permit permit}
              trace-full? (assoc :trace-full trace)))
        (cond-> {:stdout     (clip stdout)
                 :stderr     (clip stderr)
                 :exit       exit
                 :cwd        (some-> env :cwd)
                 :truncated? (or (and stdout (> (count stdout) max-out))
                                 (and stderr (> (count stderr) max-out)))
                 :trace      (trace-summary trace)}
          ;; Full permit result is huge (AST per call). Only include
          ;; when explicitly requested.
          trace-full? (assoc :trace-full trace :permit permit))))
    (catch Exception e
      (let [budget? (mbudget/budget-exceeded? e)]
        (tel/log! {:level (if budget? :warn :error)
                   :id    (if budget? ::budget-exceeded ::error)
                   :data  {:cmd cmd
                           :reason (some-> e ex-data :muschel/budget)
                           :error (.getMessage e)}}
                  (if budget? "Bash budget exceeded" "Bash run error"))
        {:error (.getMessage e)
         :exit  (if budget? 124 1)}))))
