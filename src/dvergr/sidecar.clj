(ns dvergr.sidecar
  "Sidecar API — load dvergr into any Clojure REPL and drive agents
   programmatically. The complement to `dvergr.cli` (terminal app).

   ## Use case

   Same JVM, same classpath as your project. The agent has direct
   access to your live in-memory state. You can:

     - Construct an agent in one line
     - Send synchronous asks from your REPL
     - Intercept bus traffic for telemetry, gating, supervisors
     - Spawn multiple agents in parallel rooms
     - Have an agent operate on the *files of the very project the
       REPL is running in* (set `:cwd` to `.`)

   ## Minimum example

       (require '[dvergr.sidecar :as s])

       (def agent (s/spawn-agent! {:cwd \".\"
                                    :model \"claude-code-sonnet\"}))

       @(s/ask agent \"read src/hello.clj and add a goodbye fn\")
       ;; => \"Added (defn goodbye ...) at line 10.\"

   ## What's in the agent value

   The map returned by `spawn-agent!` carries everything you might
   reach for in a REPL — the underlying `Room`, the execution context,
   the chat-ctx (for inspecting message history), the participant
   context, and a `stop!` thunk for cleanup.

   ## How it differs from dvergr-cli

   - The CLI is a TUI; the sidecar is a library
   - The CLI owns the terminal; the sidecar plugs into your existing dev session
   - The CLI is multi-room with sidebar; the sidecar is one agent per
     `spawn-agent!` call (compose many)
   - The CLI handles input streams; the sidecar is `ask`/`stream`-driven"
  (:require [clojure.string :as str]
            [is.simm.partial-cps.sequence :as aseq]
            [org.replikativ.spindel.core :as sp]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.engine.context :as ctx]
            [org.replikativ.spindel.spin.cps :refer [spin]]
            [org.replikativ.spindel.spin.sync :as sync]
            [org.replikativ.spindel.effects.await :refer [await]]
            [dvergr.bus :as bus]
            [dvergr.discourse :as d]
            [dvergr.discourse.llm :as llm]
            [dvergr.chat.context :as cc]
            [dvergr.cli.streaming :as cli-stream]
            [dvergr.participant.context :as pctx]
            [dvergr.sandbox :as sandbox]
            [dvergr.tools :as tools]
            [dvergr.model.providers :as providers]))

;; ============================================================================
;; Provider auto-detect (same logic as dvergr.cli.main, lifted here so the
;; sidecar doesn't depend on the CLI)
;; ============================================================================

(defn- claude-cli-available?
  []
  (try
    (zero? (.waitFor (.start (ProcessBuilder. ["claude" "--version"]))))
    (catch Exception _ false)))

(defn- default-provider-model
  []
  (cond
    (System/getenv "ANTHROPIC_API_KEY")
    {:provider :anthropic :model "claude-sonnet-4-6"}

    (claude-cli-available?)
    {:provider :claude-code :model "claude-code-sonnet"}

    :else
    {:provider :fireworks :model "accounts/fireworks/models/kimi-k2p6"}))

(def ^:private tool-presets
  "Curated tool-name sets. Override with an explicit `#{...}` if you need
   something else."
  {:read-only #{"read_file" "glob" "grep" "code_query"}
   :coder     #{"read_file" "glob" "grep" "code_query"
                "write_file" "edit_file"
                "clojure_eval" "shell"}
   :all       :all})

(defn- resolve-tools
  [preset-or-set]
  (cond
    (set? preset-or-set)            preset-or-set
    (= :all preset-or-set)          (set (keys @tools/registry))
    (contains? tool-presets preset-or-set)
    (let [v (get tool-presets preset-or-set)]
      (if (= :all v)
        (set (keys @tools/registry))
        v))
    :else
    (throw (ex-info "Unknown tool preset" {:got preset-or-set
                                            :known (keys tool-presets)}))))

(def ^:private default-system-prompt
  "You are an integrated coding assistant inside a Clojure REPL session.
   The user's project is at the current working directory. Use the
   available tools (read_file, write_file, edit_file, clojure_eval,
   shell, glob, grep) to do real work in this project. Be concise and
   direct. Confirm changes with the user when behaviour is ambiguous.")

;; ============================================================================
;; spawn-agent!
;; ============================================================================

(defn spawn-agent!
  "Construct an llm-agent and join it to a fresh room. Returns a map:

     {:id            participant id
      :room          dvergr.discourse Room
      :ctx           spindel execution context
      :chat-ctx      dvergr.chat.context ChatContext
      :pctx          dvergr.participant.context ParticipantContext
      :tool-ctx      tool execution context
      :inbox         atom of [Message] — every message addressed to :you
      :turns         atom of [TurnComplete] — :telemetry/turn-complete payloads
      :stop!         (fn []) tear-down}

   Options:
     :id              participant id (default :assistant)
     :cwd             working directory for tools (default JVM cwd)
     :user-id         the human's id for routing (default :you)
     :provider        provider key (default auto-detect)
     :model           model id (default per provider)
     :system-prompt   string (default coder prompt)
     :tools           keyword preset or set of names (default :coder)
                       :read-only, :coder, :all
     :isolation       :native (default) or :sci
     :budget-dollars  dollar cap (default 1.0)
     :max-turns       turn cap per generation (default 8)
     :ctx             reuse an existing execution context"
  [{:keys [id cwd user-id provider model system-prompt tools isolation
           budget-dollars max-turns ctx]
    :or   {id      :assistant
           user-id :you
           tools   :coder
           isolation :native
           budget-dollars 1.0
           max-turns 8}}]
  (providers/ensure-initialized!)
  (let [pm        (default-provider-model)
        provider  (or provider (:provider pm))
        model     (or model (:model pm))
        prompt    (or system-prompt default-system-prompt)
        ctx       (or ctx (ctx/create-execution-context))
        cwd       (or cwd (System/getProperty "user.dir"))
        agent-tools (select-keys @tools/registry (resolve-tools tools))]
    (binding [ec/*execution-context* ctx]
      (let [room      (d/room id ctx)
            chat      (cc/create-chat-context
                        {:title          (name id)
                         :budget-dollars budget-dollars
                         :with-sci?      false})
            _         (cc/add-message! chat {:role :system :content prompt})
            pc        (pctx/from-chat-context id chat)
            sci-ctx   (sandbox/fork-for-session ctx)
            tool-ctx  (tools/make-context
                        {:cwd           cwd
                         :sci-ctx       sci-ctx
                         :chat-ctx      chat
                         :tools         agent-tools
                         :isolation     isolation
                         :execution-ctx ctx})
            run-turn  (cli-stream/make-run-turn-fn
                        {:bus           (:bus room)
                         :assistant-id  id
                         :recipient-id  user-id})
            _         (d/join room
                        (llm/llm-agent
                          {:id   id
                           :ctx  ctx
                           :spec {:provider      provider
                                  :model         model
                                  :system-prompt prompt}
                           :tools agent-tools
                           :tool-ctx tool-ctx
                           :participant-context pc
                           :budget {:dollars   budget-dollars
                                    :max-turns max-turns}
                           :run-turn-fn run-turn}))
            inbox     (atom [])
            turns     (atom [])
            sub-r     (bus/subscribe! (:bus room) [:to user-id])
            sub-t     (bus/subscribe! (:bus room) [:type :telemetry/turn-complete])
            running?  (atom true)]
        ;; Drain bus subscriptions into atoms.
        (sync/spawn!
          (spin
            (loop [s (:aseq sub-r)]
              (when @running?
                (when-let [r (await (aseq/anext s))]
                  (let [[msg rest-s] r]
                    (when (not= :partial/token (:type msg))
                      (swap! inbox conj msg))
                    (recur rest-s)))))))
        (sync/spawn!
          (spin
            (loop [s (:aseq sub-t)]
              (when @running?
                (when-let [r (await (aseq/anext s))]
                  (let [[msg rest-s] r]
                    (swap! turns conj (:payload msg))
                    (recur rest-s)))))))
        {:id id
         :user-id user-id
         :room room
         :ctx ctx
         :chat-ctx chat
         :pctx pc
         :tool-ctx tool-ctx
         :inbox inbox
         :turns turns
         :stop! (fn []
                  (reset! running? false)
                  (bus/unsubscribe! sub-r)
                  (bus/unsubscribe! sub-t))}))))

;; ============================================================================
;; ask — synchronous question/answer
;; ============================================================================

(defn ask
  "Post `content` from `user-id` to the agent, wait for the next finalised
   assistant reply, return its content as a string.

   Pass `:timeout-ms` to bound how long to wait (default 5 min). Returns
   nil on timeout (the message was posted; the reply may still arrive
   later via the inbox atom)."
  [{:keys [room id user-id inbox] :as _agent} content
   & {:keys [timeout-ms] :or {timeout-ms 300000}}]
  (let [before     (count @inbox)
        deadline   (+ (System/currentTimeMillis) timeout-ms)
        msg        (d/message user-id id content)]
    (binding [ec/*execution-context* (:ctx room)]
      (d/post! room msg))
    (loop []
      (let [now    (System/currentTimeMillis)
            after  (count @inbox)]
        (cond
          (> after before)
          (:content (last @inbox))

          (> now deadline)
          nil

          :else
          (do (Thread/sleep 200) (recur)))))))

;; ============================================================================
;; with-room — bind the agent's ctx for ad-hoc d/* calls
;; ============================================================================

(defmacro with-room
  "Execute `body` with the agent's room execution-context bound. Useful
   when calling d/post!, d/ask, d/fork-room, etc. directly."
  [agent & body]
  `(binding [ec/*execution-context* (:ctx (:room ~agent))]
     ~@body))

;; ============================================================================
;; budget / messages — convenience accessors
;; ============================================================================

(defn budget
  "Live budget snapshot from the agent's chat-ctx."
  [agent]
  (cc/get-budget (:chat-ctx agent)))

(defn messages
  "Full conversation history (system + user + assistant + tool messages)."
  [agent]
  (cc/get-messages (:chat-ctx agent)))

(defn last-reply
  "Most recent assistant reply (string), or nil."
  [agent]
  (let [m (->> (messages agent)
               reverse
               (filter #(let [r (or (:role %) (:message/role %))]
                          (or (= r :assistant) (= r "assistant"))))
               first)]
    (when m (or (:message/content m) (:content m)))))

;; ============================================================================
;; propose! — substrate-isolated worker, review, accept | reject
;; ============================================================================

(defn propose!
  "Fork the agent's room with substrate isolation (yggdrasil-managed
   datahike + a fresh execution context). Spawn a worker that pursues
   `goal` against the fork. Return a *blocking* result map once the
   worker replies — the fork stays open so you can inspect and then
   accept (merge atomically) or reject (discard all branches).

   ## ⚠️ Filesystem-isolation caveat

   `fork-room :isolation :ctx` branches every yggdrasil system
   registered on the parent's execution context (datahike conn, KB,
   git worktree, …). It does NOT shadow the OS filesystem: if the
   worker's `:cwd` points at a real directory, its `write_file` /
   `edit_file` / `shell` calls go through to that directory and the
   parent sees them immediately.

   For TRUE substrate-fork filesystem isolation you must register a
   git worktree (`spindel.yggdrasil/register-git-worktree!`) on the
   parent ctx BEFORE forking — then `:isolation :ctx` branches the
   worktree to a temp directory and the worker's `:cwd` resolves
   there. `accept!` fast-forwards/merges that branch; `reject!`
   removes the temp worktree.

   Without a registered worktree, `propose!` is still useful for
   speculative *suggestions* the worker emits in text — you review
   the reply text and decide whether to accept the change yourself.
   But the worker's filesystem mutations are not contained.

   Returns:
     {:reply       <last-assistant-message-string>
      :fork        <forked Room>
      :forked-at   <timestamp ms>
      :elapsed-ms  <wall-clock to first reply>
      :worker-id   <participant id>}

   Blocks the caller — fits naturally with clj-nrepl-eval (the harness
   that orchestrates can simply wait on the eval).

   Use `accept!` / `reject!` to resolve the fork.

   Options:
     :worker-spec — extra opts forwarded to llm-agent (model, tools,
                    isolation, budget-dollars, ...). Defaults inherit
                    from the parent agent: same provider, same model.
     :timeout-ms  — bound the wait (default 10 min)"
  [{:keys [room id chat-ctx tool-ctx] :as parent-agent} goal
   & {:keys [worker-spec timeout-ms]
      :or   {timeout-ms 600000}}]
  (let [start  (System/currentTimeMillis)
        ;; Fork the room with FULL substrate isolation. The fork has
        ;; its own ctx; any yggdrasil systems registered (datahike,
        ;; git worktrees) get branched automatically.
        fork   (binding [ec/*execution-context* (:ctx room)]
                 (d/fork-room room {:isolation :ctx}))
        worker-id (keyword (str "worker-" (subs (str (random-uuid)) 0 8)))
        ;; The worker uses the same provider/model as the parent unless
        ;; overridden. We rebuild a fresh chat-ctx + pctx inside the
        ;; fork's ctx — the fork keeps its writes isolated.
        provider (or (:provider worker-spec)
                     ;; Pull from the parent llm-agent's spec via factory
                     ;; round-trip would be cleaner; for now read from
                     ;; chat-ctx's first system message via a dumb default.
                     (:provider (default-provider-model)))
        model    (or (:model worker-spec) (:model (default-provider-model)))
        worker-prompt (or (:system-prompt worker-spec)
                          default-system-prompt)
        cwd       (or (:cwd worker-spec) (System/getProperty "user.dir"))
        budget    (or (:budget-dollars worker-spec) 1.0)
        tools-set (or (:tools worker-spec) :coder)
        tools-map (select-keys @tools/registry (resolve-tools tools-set))
        worker-ctx (:ctx fork)
        worker-chat (binding [ec/*execution-context* worker-ctx]
                      (cc/create-chat-context
                        {:title (str (name worker-id))
                         :budget-dollars budget
                         :with-sci? false}))
        _ (binding [ec/*execution-context* worker-ctx]
            (cc/add-message! worker-chat
              {:role :system :content worker-prompt}))
        worker-pc (pctx/from-chat-context worker-id worker-chat)
        worker-sci (sandbox/fork-for-session worker-ctx)
        worker-tools-ctx (tools/make-context
                          {:cwd           cwd
                           :sci-ctx       worker-sci
                           :chat-ctx      worker-chat
                           :tools         tools-map
                           :isolation     (or (:isolation worker-spec) :native)
                           :execution-ctx worker-ctx})
        run-turn (cli-stream/make-run-turn-fn
                   {:bus           (:bus fork)
                    :assistant-id  worker-id
                    :recipient-id  :proposer})
        worker-msgs (atom [])
        sub (bus/subscribe! (:bus fork) [:to :proposer])
        _ (binding [ec/*execution-context* worker-ctx]
            (sync/spawn!
              (spin
                (loop [s (:aseq sub)]
                  (when-let [r (await (aseq/anext s))]
                    (let [[msg rest-s] r]
                      (when (and (not= :partial/token (:type msg))
                                 (some? (:content msg)))
                        (swap! worker-msgs conj msg))
                      (recur rest-s)))))))
        _ (binding [ec/*execution-context* worker-ctx]
            (d/join fork
              (llm/llm-agent
                {:id worker-id
                 :ctx worker-ctx
                 :spec {:provider      provider
                        :model         model
                        :system-prompt worker-prompt}
                 :tools tools-map
                 :tool-ctx worker-tools-ctx
                 :participant-context worker-pc
                 :budget {:dollars budget :max-turns (or (:max-turns worker-spec) 8)}
                 :run-turn-fn run-turn})))
        ;; Send the goal
        _ (binding [ec/*execution-context* worker-ctx]
            (d/post! fork (d/message :proposer worker-id goal)))
        ;; Block until reply (or timeout)
        deadline (+ start timeout-ms)
        reply (loop []
                (cond
                  (seq @worker-msgs) (-> @worker-msgs first :content)
                  (> (System/currentTimeMillis) deadline) nil
                  :else (do (Thread/sleep 200) (recur))))]
    {:reply reply
     :fork  fork
     :forked-at start
     :elapsed-ms (- (System/currentTimeMillis) start)
     :worker-id worker-id
     :worker-chat-ctx worker-chat}))

(defn accept!
  "Accept a proposal: atomically merge the fork's yggdrasil-managed
   branches (datahike, git worktree, etc.) back into the parent room.
   Returns :accepted."
  [parent-agent {:keys [fork] :as _proposal}]
  (binding [ec/*execution-context* (:ctx (:room parent-agent))]
    (d/merge-room (:room parent-agent) fork))
  :accepted)

(defn reject!
  "Reject a proposal: discard the fork's branches (datahike rolled back,
   git worktrees removed). Nothing leaks into the parent room.
   Returns :rejected."
  [_parent-agent {:keys [fork] :as _proposal}]
  (d/discard fork)
  :rejected)
