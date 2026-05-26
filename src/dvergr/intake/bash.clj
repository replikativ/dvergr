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
            [dvergr.process :as dproc]
            [muschel.budget :as mbudget]
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

   The session is seeded with a *sanitised* env via make-sandboxed-env
   so the agent never sees the daemon's API keys / tokens — even if
   the agent runs `export` mid-session, the inherited base never had
   secrets in it.

   Stored under [:dvergr/bash-session] in the chat-ctx's spindel-ctx
   state so it forks via CoW alongside everything else when the
   chat-ctx is forked."
  [chat-ctx]
  (binding [ec/*execution-context* (:spindel-ctx chat-ctx)]
    (or (ec/get-state session-path)
        (let [s (ms/spindel-session-using (:spindel-ctx chat-ctx)
                                          (make-sandboxed-env {}))]
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
   inspect what a command would be allowed to do before committing.

   Opts:
     :prompter  default `m/allow-all-prompter`; pass
                `m/deny-all-prompter` to inspect what the safe-default
                policy would reject."
  ([cmd] (check cmd {}))
  ([cmd {:keys [prompter] :or {prompter m/allow-all-prompter}}]
   (let [ast (m/parse cmd)]
     (m/check {:ast ast :rulesets [m/default-rules]
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
  "Build a muschel interrupt-fn that polls a dvergr.process for
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
     :process      a `dvergr.process` to honour for cancellation;
                   when aborted, the bash run interrupts at the next
                   safe point. Defaults to
                   `dvergr.process/*current-process*` when bound,
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
                    :permit      {:rulesets [m/default-rules] :prompter prompter}
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
