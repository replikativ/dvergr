(ns dvergr.agent.turn
  "Shared turn mechanics for the ONE LLM-agent turn handler
   (`dvergr.discourse.llm/llm-agent`, which every agent now runs on — personas
   and daemon agents alike). Lives in a LOWER layer than discourse.llm and the
   daemon so there is no require cycle, and so the room-turn registry is a single
   shared map (Esc-cancel sees every agent's turn).

   Holds:
   - the room-turn registry — the handle a frontend (TUI/web) uses to cancel an
     in-flight agent turn (`[room-id agent-id]` → live chat-ctx)
   - `post-turn-activity!` — the 🔧 tool-activity play-by-play (→ room `:_activity`)
   - `cancel?-fn` — the SSE-abort predicate threaded into `run-agent-turn!`"
  (:require [clojure.string :as str]
            [dvergr.activity :as activity]
            [org.replikativ.spindel.engine.core :as rtc]
            [dvergr.discourse :as d]
            [dvergr.chat.context :as chat-ctx]
            [dvergr.agent.run :as run]
            [dvergr.sandbox :as sandbox]
            [dvergr.sandbox.ns.io :as ns-io]
            ;; loaded so add-process-ns! can (find-ns 'dvergr.agent.process)
            [dvergr.agent.process]))

;; ----------------------------------------------------------------------------
;; The ONE working-context factory.
;;
;; Before: a ChatContext was built several ways with INCONSISTENT sandbox
;; completeness — `create-chat-context :with-sci?` forks a SCI context but never
;; runs `setup-agent-namespaces!`, so clojure_eval silently lacked
;; dh/room/intake/search. This factory is the single correct builder; every
;; sourcing path composes it, so the sandbox is always set up the same way.
;;
;; Establishment contract: the sandbox's dh/calendar/entity namespaces project
;; from the global system-db (dvergr.system.db), and its `room` namespace resolves
;; each room's own stores fork-aware off the registry (RF5 S4 — there is no shared
;; chat-db). Callers pass the room/agent execution-ctx; setup binds it.
;; ----------------------------------------------------------------------------
(defn new-working-ctx
  "Create a ChatContext whose SCI sandbox has the ctx-bound agent namespaces
   injected (dh/room/intake/search/…). Does NOT seed a system prompt — callers do
   that their own way (signal-only for a room fold, durable add-message! for a
   transient/standalone ctx). `durable?` (when supplied) overrides the chat-ctx's
   datahike-write behaviour (room folds pass false: the room store is the durable
   writer). Returns the ChatContext."
  [{:keys [execution-ctx chat-id title budget-dollars db-conn kb-conn room-id
           room-runtime-id room-incarnation agent-program-ceiling durable? allowed-domains]}]
  (binding [rtc/*execution-context* execution-ctx]
    (let [cctx (cond-> (chat-ctx/create-chat-context
                        (cond-> {:budget-dollars (or budget-dollars 1.0)
                                 :db-conn        db-conn
                                 :with-sci?      true
                                 :title          (or title "agent")}
                          chat-id (assoc :chat-id chat-id)))
                 (some? durable?) (assoc :durable? durable?)
                 ;; the ctx knows its room: embedder hooks (e.g. the bash
                 ;; mount provider) resolve room-scoped resources from it
                 room-id (assoc :room-id room-id))]
      ;; create-chat-context forks a sci-ctx but does NOT inject the ctx-bound
      ;; namespaces — do it here so clojure_eval has the room/kb/intake nses
      ;; everywhere. `db-conn` is the room's OWN messages store (= `*room*`);
      ;; `kb-conn` its OWN knowledge base (= `*kb*`) — both fork-aware, never sdb.
      (when-let [sci (:sci-ctx cctx)]
        (sandbox/setup-agent-namespaces! sci execution-ctx
                                         :room-conn db-conn :kb-conn kb-conn :room-id room-id
                                         :room-runtime-id room-runtime-id
                                         :room-incarnation room-incarnation
                                         :agent-program-ceiling agent-program-ceiling
                                         ;; per-agent network egress scoping (nil/empty = open)
                                         :allowed-http-domains allowed-domains)
        ;; Two namespaces bound to THIS chat-ctx (not just the spindel ctx), so
        ;; they can't live in setup-agent-namespaces!: `bash` (the muschel shell
        ;; session this chat-ctx owns — same one the `shell` tool drives) and
        ;; `processes` (the turn process registry — list/snapshot/directive! the
        ;; agent's own work). The daemon path wired these per-turn; now every
        ;; working ctx gets them at creation.
        (ns-io/add-bash-ns!    sci cctx)
        (ns-io/add-media-ns!   sci cctx)
        (ns-io/add-process-ns! sci cctx))
      cctx)))

;; Reserved `:to` id for agent tool-activity messages posted into a room.
;; Nothing subscribes to it, so no participant (agent or egress) receives these
;; — they land only in the room log + store, where rich frontends (TUI, web,
;; simmis) render the play-by-play. Keeps activity out of agents' inboxes and
;; off thin channels (Telegram), while making the room the complete transcript.
(def activity-id :_activity)

;; ----------------------------------------------------------------------------
;; Compatibility projection over the Run registry. New callers use
;; dvergr.agent.run directly; TUI/web room-wide turn controls keep their existing
;; API while gaining targeted Run cancellation underneath.
;; ----------------------------------------------------------------------------
(defonce ^:private room-turn-watch-keys (atom {}))

(defn register-room-turn! [room-id agent-id chat-ctx]
  (run/register-live! room-id agent-id chat-ctx))

(defn unregister-room-turn!
  ([run-id] (run/unregister-live! run-id))
  ([room-id agent-id] (run/unregister-live! room-id agent-id)))

(defn room-turn-running?
  "True if any agent currently has an in-flight turn in `room-id`."
  [room-id]
  (boolean (seq (run/active-runs room-id))))

(defn watch-room-turns!
  "Subscribe `f` — a fn of `[room-id running?]` — to be called whenever a room's
   in-flight-turn set transitions empty↔non-empty. Frontends use this to drive an
   optimistic spinner off the TURN lifecycle rather than off reply-arrival, so a
   silent (`[SKIP]`) turn — which posts no message — still clears the spinner.
   `key` identifies the watch (idempotent; remove via `unwatch-room-turns!`)."
  [key f]
  (let [run-key [::room-turn-watch key]
        previous (atom nil)]
    (swap! room-turn-watch-keys assoc key run-key)
    (run/watch-runs!
     run-key
     (fn [_event]
       (let [now (->> (run/active-runs)
                      (group-by :run/room)
                      (map (fn [[rid runs]] [rid (boolean (seq runs))]))
                      (into {}))]
         (if-let [old @previous]
           (doseq [rid (into (set (keys old)) (set (keys now)))]
             (let [was (boolean (get old rid))
                   running? (boolean (get now rid))]
               (when (not= was running?)
                 (try (f rid running?) (catch Throwable _ nil)))))
           nil)
         (reset! previous now))))
    key))

(defn unwatch-room-turns! [key]
  (when-let [run-key (get @room-turn-watch-keys key)]
    (run/unwatch-runs! run-key)
    (swap! room-turn-watch-keys dissoc key))
  nil)

(defn cancel-room-turn!
  "Cancel every in-flight agent turn in `room-id` through its private Run token
   (cooperative bail + SSE abort). Returns the number of turns signalled. The
   2-arity `_daemon` arg is kept for the existing daemon/TUI call signature."
  ([room-id] (cancel-room-turn! nil room-id))
  ([_daemon room-id]
   (run/cancel-room-runs! room-id)))

(def active-runs run/active-runs)
(def watch-runs! run/watch-runs!)
(def unwatch-runs! run/unwatch-runs!)
(def cancel-run! run/cancel-run!)
(def cancel-requested? run/cancel-requested?)

(defn cancel?-fn
  "Build the `:cancel?` predicate for `run-agent-turn!`. Chat-wide shutdown and
   an optional Run-local predicate both abort the in-flight SSE; targeted Run
   cancellation never mutates the reusable ChatContext."
  ([chat-ctx execution-ctx]
   (cancel?-fn chat-ctx execution-ctx nil))
  ([chat-ctx execution-ctx run-cancelled?]
   (fn []
     (or (boolean (and run-cancelled? (run-cancelled?)))
         (binding [rtc/*execution-context* execution-ctx]
           (= :cancelled (chat-ctx/get-status chat-ctx)))))))

;; ----------------------------------------------------------------------------
;; Tool-activity play-by-play
;; ----------------------------------------------------------------------------
(defn tool-activity-count
  "Number of assistant messages in `chat-ctx` that already contain tool uses.
   A new Run initializes its watermark here so rehydrated historical activity is
   never reposted or attributed to the new Run."
  [chat-ctx]
  (->> (chat-ctx/get-messages chat-ctx)
       (filter #(= :assistant (or (:role %) (:message/role %))))
       (filter #(seq (or (:message/tool-uses %) (:tool-uses %))))
       count))

(defn- activity-message [agent-id content run-id trigger metadata]
  (let [metadata (cond-> metadata run-id (assoc :run-id run-id))]
    (if trigger
      (d/reply agent-id activity-id content trigger metadata)
      (d/message agent-id activity-id content nil metadata))))

(defn post-turn-activity!
  "Post an agent's tool-call activity into `room` so rich frontends render the
   play-by-play. Each tool-bearing assistant message (read from `chat-ctx`)
   becomes a `:role :tool` message addressed to `activity-id`, carrying its
   structured `:tool-uses` (and `:reasoning` if present). `posted` is an atom of
   how many tool-bearing messages were already emitted, so repeated calls across
   the turn loop don't duplicate. Returns nil."
  ([room agent-id chat-ctx posted]
   (post-turn-activity! room agent-id chat-ctx posted nil nil))
  ([room agent-id chat-ctx posted run-id trigger]
   (let [tool-msgs (->> (chat-ctx/get-messages chat-ctx)
                        (filter #(= :assistant (or (:role %) (:message/role %))))
                        (filter #(seq (or (:message/tool-uses %) (:tool-uses %))))
                        vec)]
     (when (> (count tool-msgs) @posted)
       (binding [rtc/*execution-context* (:ctx room)]
         (doseq [m (subvec tool-msgs @posted)]
           (let [uses    (vec (or (:message/tool-uses m) (:tool-uses m)))
                 names   (keep #(or (:tool-use/name %) (:name %)) uses)
                 summary (str "🔧 " (str/join ", " names))
                 reason  (or (:message/reasoning m) (:reasoning m))
                 source-id (or (:message/id m) (:id m))]
             (d/post! room (activity-message
                            agent-id summary run-id trigger
                            (cond-> {:role :tool
                                     :tool-uses uses
                                     :activities (activity/tool-activities
                                                  run-id source-id uses)}
                              (seq reason) (assoc :reasoning reason)))))))
       (reset! posted (count tool-msgs)))
     nil)))

(defn post-budget-warning!
  "Surface budget exhaustion as a visible, NON-triggering activity row: the
   agent has hit its dollar ceiling and has STOPPED. Nothing is running, nothing
   further is being spent, and nothing expires — raise the room's budget and
   speak, and the agent picks up where it left off.

   The old copy promised a countdown (\"raise it within 60s or I wrap up\").
   That was untrue in two directions: the deadline forced a decision on a
   stopwatch a human never agreed to, and letting it lapse SPENT MORE MONEY on a
   wrap-up turn — after the ceiling had already been hit. No-op when `room` is
   nil. Returns nil."
  ([room agent-id used-dollars total-dollars]
   (post-budget-warning! room agent-id used-dollars total-dollars nil nil))
  ([room agent-id used-dollars total-dollars run-id trigger]
   (when room
     (binding [rtc/*execution-context* (:ctx room)]
       (d/post! room
                (activity-message
                 agent-id
                 (format "⚠️ budget exhausted — $%.2f of $%.2f used. I have stopped and am spending nothing. Raise the room budget and message me to continue."
                         (double used-dollars) (double total-dollars))
                 run-id trigger
                 {:role :tool
                  :activities [(activity/lifecycle-activity
                                run-id :budget :exhaust :blocked
                                "Run budget exhausted")]}))))
   nil))

(defn post-turn-error!
  "Surface a FAILED agent turn as a visible, NON-triggering activity row (→ room
   `:_activity`, `:role :tool`, like `post-turn-activity!`) — so the turn loop can
   report the failure instead of falling through to re-post a STALE prior reply
   (which the room's other agents would answer, looping — the \"repeating\" bug).
   `err` is the throwable from the errored turn result. No-op when `room` is nil.
   Returns nil."
  ([room agent-id err]
   (post-turn-error! room agent-id err nil nil))
  ([room agent-id err run-id trigger]
   (when room
     (binding [rtc/*execution-context* (:ctx room)]
       (let [detail (or (some-> err ex-message) (some-> err str) "LLM error")]
         (d/post! room
                  (activity-message
                   agent-id (str "⚠️ turn failed — " detail) run-id trigger
                   {:role :tool
                    :activities [(activity/lifecycle-activity
                                  run-id :run :fail :failed detail)]})))))
   nil))
