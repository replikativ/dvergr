(ns dvergr.clients.client
  "The dvergr REPL programming model — one Clojure surface over a running (or
   booted-lite) daemon, on the spindel/discourse algebra.

   Mental model:
     - A `Room` is the one handle. It carries its own execution context, so every
       fn binds the ctx for you — you never write `(binding …)`, and forking just
       works because a fork's Room carries the fork's ctx.
     - `(room :id)` and `(spawn …)` hand you a Room; every other fn takes one.
     - `(invoke op args)` reaches ANY operation in the `dvergr.ops` spec by name —
       the same call-path the web / MCP / Telegram / TUI use, so the REPL is
       symmetric with them for free.

   Usage from any nREPL session:

     (require '[dvergr.clients.client :as c])
     (c/start!)                                   ; boot-lite or attach
     (def app (c/room :boardroom))                ; id → Room (explicit)
     (c/post! app :repl :var \"status?\")           ; post: room from to text
     (c/history app)                              ; read the reply

     (def f (c/submit-task! app \"add a /health route, commit\"))  ; fork + goal → fork Room
     (deref (c/quiet f) 120000 :busy)             ; let the coder work
     (c/diff f)                                    ; review the worktree diff
     (c/merge! f)                                  ; land it   (or (c/discard! f))

   `(c/spawn …)` covers the embed-one-agent-as-a-value case (no pre-existing
   daemon needed — `start!` boots a lite one)."
  (:require [dvergr.actors :as actors]
            [dvergr.orchestration.daemon :as daemon]
            [dvergr.discourse :as d]
            [dvergr.discourse.personas :as personas]
            [dvergr.room.registry :as rreg]
            [dvergr.ops :as ops]
            [dvergr.orchestration.skills :as skills]
            [dvergr.orchestration.tasks :as tasks]
            [dvergr.system.db :as sdb]
            [org.replikativ.spindel.engine.core :as rtc]
            [org.replikativ.spindel.yggdrasil :as ygg]))

;; ============================================================================
;; Daemon attach + ctx binding
;; ============================================================================

(defn- current-daemon! []
  (or @daemon/current-daemon
      (throw (ex-info "No daemon running — `(c/start!)` or launch `clj -M:cli`."
                      {:cause :daemon-not-running}))))

(defn- with-ctx
  "Bind the daemon's ctx; pass `[daemon ctx conn]` to `f`. For daemon-level ops
   (registry, actors). Room-level ops bind the Room's own ctx instead.
   RF5 S4.3: there is no chat-db — `conn` is nil; ops read system-db (sdb) or the
   in-memory registry directly. The arg is kept for call-site shape compat."
  [f]
  (let [daem (current-daemon!)
        ctx  (:execution-ctx daem)]
    (binding [rtc/*execution-context* ctx]
      (f daem ctx nil))))

(defmacro ^:private in-room
  "Run body with the ROOM's execution context bound (forks carry their own)."
  [room & body]
  `(binding [rtc/*execution-context* (:ctx ~room)] ~@body))

;; ============================================================================
;; Lifecycle — boot-lite-or-attach
;; ============================================================================

(defonce ^:private !lite-ctx (atom nil))

(defn start!
  "Ensure a daemon is attached. If one is running (`clj -M:cli` sets
   current-daemon), no-op (attach). Otherwise boot a lightweight daemon — ctx +
   peer-bus + git — and register it as current-daemon so every `c/*` fn drives it.
   Idempotent. Returns :attached | :booted-lite.

   Lite options: :worktrees-dir (default .dvergr/worktrees)."
  [& {:keys [worktrees-dir]
      :or {worktrees-dir ".dvergr/worktrees"}}]
  (if @daemon/current-daemon
    :attached
    ;; :with-git? false — like the real daemon (daemon.clj). Booting a root git on
    ;; the host cwd would register dvergr's OWN checkout as a git system that every
    ;; room ctx then inherits, so current-git-system could resolve to the dvergr
    ;; source tree (a sandbox escape). Rooms register their own repo; room-less use
    ;; falls back to safe-workspace-root.
    (let [ctx (daemon/create-shared-context
               :worktrees-dir worktrees-dir
               :with-git? false :with-datahike? true)]
      (reset! !lite-ctx ctx)
      (reset! daemon/current-daemon {:execution-ctx ctx :status (atom :running) :config {}})
      :booted-lite)))

(defn stop!
  "Tear down a lite daemon booted by `start!` (no-op when attached to a real one)."
  []
  (if-let [ctx @!lite-ctx]
    (do (binding [rtc/*execution-context* ctx]
          (try ((requiring-resolve 'dvergr.agent.room-context/clear-all!)) (catch Throwable _))
          (try ((requiring-resolve 'dvergr.rooms.messages/clear-all!))     (catch Throwable _))
          (try ((requiring-resolve 'dvergr.system.rooms/clear-room-ctxs!)) (catch Throwable _)))
        ;; release the ctx's drain thread / executor (was leaked — never closed)
        (try ((requiring-resolve 'org.replikativ.spindel.engine.context/close-context!) ctx)
             (catch Throwable _))
        (reset! !lite-ctx nil)
        (reset! daemon/current-daemon nil)
        :stopped)
    :attached-noop))

;; ============================================================================
;; Rooms — the handle
;; ============================================================================

(defn rooms
  "All Rooms in the registry (persistent + forks) — a vector of Room values."
  []
  (with-ctx (fn [_ _ _] (vec (rreg/list-rooms)))))

(defn room
  "Look up a Room by id (keyword) or slug (string). → Room | nil. The ONE explicit
   id→Room step; every other fn takes the Room."
  [id-or-slug]
  (with-ctx (fn [_ _ _] (rreg/lookup (if (string? id-or-slug) (keyword id-or-slug) id-or-slug)))))

(defn spawn
  "Create a room with one agent joined; return the Room. The agent is room-safe by
   default (self-filter etc.). opts (all optional): :id, :persona (default
   `personas/coder`), :tools, :model, :provider, :system-prompt, :budget (dollars),
   :register? (add to the registry so the web/TUI see it)."
  [{:keys [id persona register?] :or {persona personas/coder} :as opts}]
  (with-ctx
    (fn [_ ctx _]
      (let [id    (or id (keyword (str "agent-" (subs (str (random-uuid)) 0 8))))
            room  (d/room id ctx)
            agent (binding [rtc/*execution-context* ctx]
                    (persona (-> (dissoc opts :persona :register?)
                                 (assoc :id id :ctx ctx))))]
        (binding [rtc/*execution-context* ctx] (d/join room agent))
        (when register? (rreg/register! room))
        room))))

(defn register!
  "Put `room` in the registry so the web/TUI/other REPL sessions can reach it by
   id. Returns the Room. (Orthogonal to spawn — spawn :register? true is the same.)"
  [room]
  (with-ctx (fn [_ _ _] (rreg/register! room))))

;; ============================================================================
;; Messages — one explicit verb
;; ============================================================================

(defn post!
  "Post a message into `room` (a Room). `to` = nil broadcasts to everyone; an id
   addresses that participant. `from` is provenance (`:repl` for you; routing
   ignores it). Returns the posted Message. The agent's reply is async — read it
   with `history`/`watch`."
  [room from to text]
  (in-room room
           (let [m (d/message from to text nil {:role :user})]
             (d/post! room m)
             m)))

;; ============================================================================
;; Reads / control (all take a Room)
;; ============================================================================

(defn history
  "The room's message history (store-backed) as `[{:from :role :content} …]`."
  ([room] (history room 50))
  ([room n]
   (in-room room
            (->> (d/messages room {:limit n})
                 (mapv (fn [m] {:from (:from m) :role (:role m) :content (:content m)}))))))

(defn watch
  "The last `n` messages off the live bus log (poll-friendly)."
  ([room] (watch room 20))
  ([room n] (in-room room (->> (d/log room) (take-last n) vec))))

(defn close!
  "Stop the room's agent(s) and tear down the room (closes the bus + spins)."
  [room]
  (in-room room (d/close-room! room))
  :closed)

;; ============================================================================
;; Forks — the self-modifying flow
;; ============================================================================

(defn fork
  "Fork `room` into a fresh git worktree + DB branch (CoW). Returns the fork Room."
  ([room] (fork room {:isolation :ctx}))
  ([room opts] (in-room room (d/fork-room room opts))))

(defn diff
  "The git diff this fork has accumulated over its parent — {:branch :parent-branch
   :commits :stat}."
  [fork]
  ((requiring-resolve 'dvergr.substrate.git/diff-since-fork) (:ctx fork)))

(defn merge!
  "Merge the fork into its parent (derived from the fork's :parent-id) — land it."
  [fork]
  (with-ctx
    (fn [_ ctx _]
      (let [parent (rreg/lookup (:parent-id fork))]
        (binding [rtc/*execution-context* ctx] (d/merge-room parent fork))
        :merged))))

(defn discard!
  "Drop the fork's worktree without merging."
  [fork]
  (with-ctx (fn [_ ctx _] (binding [rtc/*execution-context* ctx] (d/discard fork)) :discarded)))

(defn- promise-watching-log
  "A Java promise delivered the first time `(pred snapshot entry)` is truthy for an
   append to `log-atom`. Auto-removes its watch on delivery. @-able at the REPL."
  [log-atom pred]
  (let [p (promise), wid (random-uuid)
        try! (fn [snap e] (when-not (realized? p) (when-let [v (pred snap e)] (deliver p v))))]
    (add-watch log-atom wid
               (fn [_ _ old new] (try! new (when (> (count new) (count old)) (peek new)))))
    (doseq [e @log-atom :while (not (realized? p))] (try! @log-atom e))
    (future (try @p (finally (remove-watch log-atom wid))))
    p))

(defn done
  "A promise that resolves when the fork's agent signals completion — by default a
   message carrying `:dvergr/proposal` (propose-merge!). `@(c/done f)` or
   `(deref (c/done f) ms default)`."
  [fork & {:keys [pred] :or {pred #(some? (:dvergr/proposal %))}}]
  (promise-watching-log (:log (:bus fork)) (fn [_ e] (when (and e (pred e)) e))))

(defn quiet
  "A promise that resolves once the fork's bus has been idle for `:idle-ms`
   (default 10000) — the robust 'the agent stopped' signal when it doesn't emit a
   formal proposal (most don't; `diff` is the real check)."
  [fork & {:keys [idle-ms] :or {idle-ms 10000}}]
  (let [log-atom (:log (:bus fork))
        p (promise), last-at (atom (System/currentTimeMillis)), wid (random-uuid)]
    (add-watch log-atom wid (fn [_ _ _ _] (reset! last-at (System/currentTimeMillis))))
    (future
      (try
        (loop []
          (when-not (realized? p)
            (let [idle (- (System/currentTimeMillis) @last-at)]
              (if (>= idle idle-ms)
                (deliver p @log-atom)
                (do (Thread/sleep (max 100 (- idle-ms idle))) (recur))))))
        (finally (remove-watch log-atom wid))))
    p))

(defn submit-task!
  "Fork `room` and post `goal` to the fork's agent. Returns the fork Room — review
   with `diff`, land with `merge!`, drop with `discard!`, await with `quiet`/`done`."
  [room goal & {:keys [from] :or {from :repl}}]
  (let [f (fork room)]
    (post! f from (in-room f (d/room-target f)) goal)
    f))

;; ============================================================================
;; The spec, verbatim — symmetric with web / MCP / Telegram / TUI
;; ============================================================================

(defn invoke
  "Run any `dvergr.ops` operation by name against the running daemon.
   e.g. (c/invoke :room/diff {:room :boardroom}). The exact call-path the other
   surfaces use — see `(c/ops)` for the catalogue."
  [op args]
  (ops/invoke (current-daemon!) op (or args {})))

(defn ops
  "The op catalogue: `{op-key :doc}` for every operation in the spec, by name."
  []
  (into (sorted-map) (map (fn [[k v]] [k (:doc v)]) ops/specification)))

;; ============================================================================
;; Durable identity — the roster (distinct from live agents)
;; ============================================================================

;; RF5 S3: actor identity is global — it lives in the system-db, not the
;; per-room chat-db `with-ctx` hands over. We keep the `with-ctx` wrapper for
;; the daemon-presence guard (the client is a daemon operator), but resolve the
;; conn from system-db.
(defn actors
  "Actors in the durable table. Filter kwargs: :kind :status :skill."
  [& kvs]
  (with-ctx (fn [_ _ _] (apply actors/list-actors (sdb/get-conn) kvs))))

(defn actor
  "Actor record by id, or nil."
  [id]
  (with-ctx (fn [_ _ _] (actors/lookup (sdb/get-conn) id))))

(defn register-actor!
  "Persist a durable actor row (identity in the roster — NOT a live agent; use
   `spawn` for that). opts {:id …, :kind :agent|:human, …}. Default :agent."
  [opts]
  (with-ctx (fn [_ _ _]
              (if (= :human (:kind opts))
                (actors/spawn-human! (sdb/get-conn) opts)
                (actors/spawn-agent! (sdb/get-conn) opts)))))

(defn dismiss-actor!
  "Flag an actor as :retired."
  [id]
  (with-ctx (fn [_ _ _] (actors/dismiss! (sdb/get-conn) id))))

;; ============================================================================
;; Operating the daemon — system verbs (distinct from the room programming model)
;; ============================================================================

(defn daemon-info
  "Status summary of the running daemon."
  []
  (with-ctx (fn [daem _ _]
              {:status              @(:status daem)
               :execution-ctx-bound true
               :global-room         (or (:global-room-slug (:config daem)) "boardroom")})))

(defn dispatch
  "Pick an actor for `skill` and route `task` through its transport. See
   dvergr.orchestration.skills/dispatch!."
  [skill task & {:keys [room-id from-actor] :or {room-id :boardroom}}]
  ;; RF5 S3: actors AND the task ledger both live in the global system-db, so
  ;; dispatch! runs on ONE conn — the actor-pick and the task write are coherent
  ;; again (resolving the S3a split). The bound ctx is still needed for the
  ;; transport (it posts the task into the room).
  (with-ctx (fn [_ _ _]
              (skills/dispatch! (sdb/get-conn) skill
                                (cond-> {:task task :room-id room-id}
                                  from-actor (assoc :from-actor from-actor))))))

(defn list-tasks
  "Human task-ledger entries (filters: :actor-id, :status)."
  [& kvs]
  (apply tasks/list-tasks (sdb/get-conn) kvs))

(defn accept-task!   [task-id]        (tasks/accept!   (sdb/get-conn) task-id))
(defn complete-task! [task-id result] (tasks/complete! (sdb/get-conn) task-id result))
(defn ignore-task!   [task-id]        (tasks/ignore!   (sdb/get-conn) task-id))
