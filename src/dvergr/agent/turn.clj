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
            [org.replikativ.spindel.engine.core :as rtc]
            [dvergr.discourse :as d]
            [dvergr.chat.context :as chat-ctx]
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
  [{:keys [execution-ctx chat-id title budget-dollars db-conn kb-conn room-id durable?
           allowed-domains]}]
  (binding [rtc/*execution-context* execution-ctx]
    (let [cctx (cond-> (chat-ctx/create-chat-context
                        (cond-> {:budget-dollars (or budget-dollars 1.0)
                                 :db-conn        db-conn
                                 :with-sci?      true
                                 :title          (or title "agent")}
                          chat-id (assoc :chat-id chat-id)))
                 (some? durable?) (assoc :durable? durable?))]
      ;; create-chat-context forks a sci-ctx but does NOT inject the ctx-bound
      ;; namespaces — do it here so clojure_eval has the room/kb/intake nses
      ;; everywhere. `db-conn` is the room's OWN messages store (= `*room*`);
      ;; `kb-conn` its OWN knowledge base (= `*kb*`) — both fork-aware, never sdb.
      (when-let [sci (:sci-ctx cctx)]
        (sandbox/setup-agent-namespaces! sci execution-ctx
                                         :room-conn db-conn :kb-conn kb-conn :room-id room-id
                                         ;; per-agent network egress scoping (nil/empty = open)
                                         :allowed-http-domains allowed-domains)
        ;; Two namespaces bound to THIS chat-ctx (not just the spindel ctx), so
        ;; they can't live in setup-agent-namespaces!: `bash` (the muschel shell
        ;; session this chat-ctx owns — same one the `shell` tool drives) and
        ;; `processes` (the turn process registry — list/snapshot/directive! the
        ;; agent's own work). The daemon path wired these per-turn; now every
        ;; working ctx gets them at creation.
        (ns-io/add-bash-ns!    sci cctx)
        (ns-io/add-process-ns! sci cctx))
      cctx)))

;; Reserved `:to` id for agent tool-activity messages posted into a room.
;; Nothing subscribes to it, so no participant (agent or egress) receives these
;; — they land only in the room log + store, where rich frontends (TUI, web,
;; simmis) render the play-by-play. Keeps activity out of agents' inboxes and
;; off thin channels (Telegram), while making the room the complete transcript.
(def activity-id :_activity)

;; ----------------------------------------------------------------------------
;; Room-turn registry — publishes the LIVE chat-ctx keyed by [room-id agent-id]
;; for the duration of a turn. Esc-cancel looks it up and flips its status to
;; :cancelled — the turn loop bails at the next boundary AND the :cancel?
;; predicate aborts the in-flight SSE stream.
;; ----------------------------------------------------------------------------
(defonce ^:private room-turns (atom {}))

(defn register-room-turn! [room-id agent-id chat-ctx]
  (swap! room-turns assoc-in [room-id agent-id] chat-ctx))

(defn unregister-room-turn! [room-id agent-id]
  (swap! room-turns update room-id dissoc agent-id))

(defn room-turn-running?
  "True if any agent currently has an in-flight turn in `room-id`."
  [room-id]
  (boolean (seq (get @room-turns room-id))))

(defn watch-room-turns!
  "Subscribe `f` — a fn of `[room-id running?]` — to be called whenever a room's
   in-flight-turn set transitions empty↔non-empty. Frontends use this to drive an
   optimistic spinner off the TURN lifecycle rather than off reply-arrival, so a
   silent (`[SKIP]`) turn — which posts no message — still clears the spinner.
   `key` identifies the watch (idempotent; remove via `unwatch-room-turns!`)."
  [key f]
  (add-watch room-turns key
             (fn [_ _ old new]
               (doseq [rid (into (set (keys old)) (set (keys new)))]
                 (let [was (boolean (seq (get old rid)))
                       now (boolean (seq (get new rid)))]
                   (when (not= was now)
                     (try (f rid now) (catch Throwable _ nil))))))))

(defn unwatch-room-turns! [key] (remove-watch room-turns key))

(defn cancel-room-turn!
  "Cancel every in-flight agent turn in `room-id` — sets each live turn's
   chat-ctx status to :cancelled (cooperative bail + SSE abort). Returns the
   number of turns signalled. The 2-arity `_daemon` arg is kept for the existing
   `daemon/cancel-room-turn!` call signature (TUI passes the daemon)."
  ([room-id] (cancel-room-turn! nil room-id))
  ([_daemon room-id]
   (let [ctxs (vals (get @room-turns room-id))]
     (doseq [cctx ctxs]
       (try (chat-ctx/cancel-chat! cctx) (catch Throwable _ nil)))
     (count ctxs))))

(defn cancel?-fn
  "Build the `:cancel?` predicate for `run-agent-turn!` — true once the chat-ctx's
   status flips to :cancelled (Esc-cancel), so the in-flight SSE aborts mid-stream.
   `run-agent-turn!` threads it through `model-chat/chat`."
  [chat-ctx execution-ctx]
  (fn [] (binding [rtc/*execution-context* execution-ctx]
           (= :cancelled (chat-ctx/get-status chat-ctx)))))

;; ----------------------------------------------------------------------------
;; Tool-activity play-by-play
;; ----------------------------------------------------------------------------
(defn post-turn-activity!
  "Post an agent's tool-call activity into `room` so rich frontends render the
   play-by-play. Each tool-bearing assistant message (read from `chat-ctx`)
   becomes a `:role :tool` message addressed to `activity-id`, carrying its
   structured `:tool-uses` (and `:reasoning` if present). `posted` is an atom of
   how many tool-bearing messages were already emitted, so repeated calls across
   the turn loop don't duplicate. Returns nil."
  [room agent-id chat-ctx posted]
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
                reason  (or (:message/reasoning m) (:reasoning m))]
            (d/post! room (d/message agent-id activity-id summary nil
                                     (cond-> {:role :tool :tool-uses uses}
                                       (seq reason) (assoc :reasoning reason)))))))
      (reset! posted (count tool-msgs)))
    nil))
