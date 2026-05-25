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

   Substrate-fork interaction: the session lives in the chat-ctx's
   spindel execution context. When dvergr.discourse/fork-room with
   :isolation :ctx forks the chat-ctx, the muschel session's env-
   atom is CoW-forked alongside — a worker's `cd` does not leak to
   the parent. (Filesystem effects of the commands themselves still
   need a git-worktree workspace to be truly contained — see
   dvergr.sidecar's docstring.)"
  (:require [org.replikativ.spindel.engine.core :as ec]
            [muschel.core :as m]
            [muschel.session :as msession]
            [muschel.session.spindel :as ms]
            [taoensso.telemere :as tel]))

;; ============================================================================
;; Per-chat-ctx session (forks with the chat-ctx)
;; ============================================================================

(def ^:private session-path [:dvergr/bash-session])

(defn- session-host []
  ;; Lazy: created on first use. Stateless across calls.
  (m/jvm-host))

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
          ;; In case of a race, read back the winner.
          (ec/get-state session-path)))))

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

(defn run
  "Execute a bash source string against the chat-ctx's session.

   Options:
     :chat-ctx     - explicit chat-ctx (otherwise resolved from
                     *current-chat-ctx* binding, if you set one)
     :host         - override the muschel host (default JVM)
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
          host  (or host (session-host))
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
