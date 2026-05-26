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
  (:require [muschel.builtins.posix :as posix]
            [muschel.core :as m]
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
          env0  (binding [ec/*execution-context* (:spindel-ctx chat-ctx)]
                  (msession/-env sess))
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
