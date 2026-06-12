(ns dvergr.orchestration.daemon
  "Persistent agent runtime daemon.

   The daemon is the long-running process that holds the spindel execution
   context, agent registry, kabel peer, and channel connections. It's the
   'main' of dvergr when running as a service.

   Responsibilities:
   1. Bootstrap: Create execution context, register as :default
   2. Agent lifecycle: Create, start, stop agents; maintain registry
   3. Channel management: Connect Telegram bot, wire on-message to dispatch
   4. Message dispatch: Route incoming messages to appropriate agents
   5. Shutdown: Orderly cleanup of agents, channels, peer

   Usage:
     (def d (start! {:telegram {:token (System/getenv \"TELEGRAM_BOT_TOKEN\")}
                     :agents {:var {:provider :fireworks
                                    :model \"accounts/fireworks/models/minimax-m2p5\"
                                    :system-prompt \"You are a helpful assistant.\"}}}))

     (list-agents d)
     (stop! d)"
  (:refer-clojure :exclude [await])
  (:require [dvergr.substrate.config :as config]
            [dvergr.substrate.paths :as paths]
            [dvergr.discourse :as d]
            [dvergr.discourse.llm :as llm]
            [dvergr.agent.turn :as turn]
            [dvergr.agent.prompt :as prompt]
            [dvergr.agent.room-context :as room-context]
            [dvergr.agent.persona :as persona]
            [dvergr.room.store :as rstore]
            [dvergr.channels.core :as channels]
            [dvergr.channels.telegram :as tg]
            [dvergr.actors :as actors]
            [dvergr.agent.ops :as ops]
            [dvergr.model.registry :as registry]
            [dvergr.model.providers :as providers]
            [dvergr.discourse.commands :as commands]
            [dvergr.channels.telegram-send :as tg-send]
            [dvergr.chat.context :as chat-ctx]
            [dvergr.tools :as tools]
            [dvergr.system.rooms :as srooms]
            [dvergr.system.db :as sdb]
            [dvergr.system.mail :as mail]
            [dvergr.runtime.clock :as clock]
            [dvergr.substrate.git :as git]
            ;; dvergr.web.server lives in src-clients/ and is loaded
            ;; opt-in via the :cli/:tui aliases. Use `web-server-fn`
            ;; below to resolve it lazily so the core daemon can boot
            ;; without the web layer on the classpath.
            [dvergr.scheduler.core :as scheduler]
            [dvergr.security.allowlist :as allowlist]
            [dvergr.orchestration.stats :as stats]
            [dvergr.rooms :as rooms]
            [dvergr.room.registry :as rreg]
            [dvergr.discourse.definitions :as defs]
            ;; Intakes now live as sandbox SOURCE (resources/sandbox stdlib (cloned from ../dvergr-sandbox)/intake/*),
            ;; loaded via the workspace :load-fn — no native intake requires here.
            ;; dvergr.intake.mail (optional) is guard-required in start! below.
            [dvergr.tools.llm-call]
            [datahike.api :as dh]
            [yggdrasil.adapters.datahike :as dh-adapter]
            [org.replikativ.spindel.engine.core :as rtc]
            [org.replikativ.spindel.engine.context :as ctx]
            [org.replikativ.spindel.yggdrasil :as ygg]
            [org.replikativ.spindel.distributed.core :as sdist]
            [org.replikativ.spindel.core :as sp :refer [spin await]]
            [org.replikativ.spindel.spin.combinators :as comb]
            [clojure.string :as str]
            [clojure.set]
            [clojure.java.io :as io]
            [taoensso.telemere :as tel]))

;; ============================================================================
;; Daemon State
;; ============================================================================

(defrecord Daemon
           [config          ;; Original config map
            execution-ctx   ;; Spindel execution context (== room's :ctx)
            discourse-room  ;; ROOT room — every agent is a participant here, so presence ==
                   ;; membership (an agent is "online" by being joined). Also the
                   ;; venue for daemon-injected system notices (`:from :_system`).
            telegram-ch     ;; Connected Telegram channel (or nil)
            http-server     ;; HTTP server state (or nil)
            status])        ;; Atom<:starting/:running/:stopping/:stopped>

;; Global daemon atom for REPL access: @current-daemon, (stop! @current-daemon)
(defonce current-daemon (atom nil))

;; Tool-activity id + the room-turn registry moved to `dvergr.agent.turn` — a
;; lower layer shared with `discourse.llm/llm-agent` (no daemon↔discourse.llm
;; cycle, and ONE registry so Esc-cancel sees persona turns too). Re-export the
;; public handles the TUI/web call as `daemon/…`.
(def ^:private activity-id     turn/activity-id)
(def room-turn-running?        turn/room-turn-running?)
(def cancel-room-turn!         turn/cancel-room-turn!)

;; ============================================================================
;; Safety
;; ============================================================================

;; Tools excluded from daemon agents regardless of config.
;;
;; shell         — direct OS execution; full system access
;; run_tests     — Kaocha runs on the JVM, not sandboxed; daemon agents should
;;                 write/run tests via clojure_eval + clojure.test inside SCI
;; telegram_*    — the bot must not self-reference its own channel;
;;                 all outbound messaging is handled by the daemon's response sinks
;;
;; Agents needing native test execution must be started manually outside the daemon
;; with :isolation :native — never from an untrusted channel like Telegram.
(def ^:private excluded-from-daemon
  #{"shell" "run_tests"
    "telegram_send" "telegram_poll" "telegram_updates" "telegram_chat_info"})

(defn safe-tools
  "Return tool map with dangerous tools removed. Called at agent creation time
   so the registry is fully populated."
  []
  (into {} (remove (fn [[k _]] (excluded-from-daemon k))) @tools/registry))

;; ============================================================================
;; Shared Execution Context
;;
;; Each daemon owns one spindel execution context with git + datahike systems
;; registered for forkable agent worktrees and persistent chat DB. Moved here
;; from the deleted dvergr.agent.process; the daemon is the only caller.
;; ============================================================================

(defn create-shared-context
  "Create a shared execution context with optional git + datahike systems
   registered for fork-isolated agent work and persistent chat DB.

   Options:
     :with-git?       — register git system for isolated agent worktrees
                        (default true)
     :with-datahike?  — register Datahike system for the chat DB
                        (default true)
     :repo-path       — git repo path (default cwd)
     :worktrees-dir   — where to create worktrees (default: per-repo, derived by
                        create-git-system so multiple repos in one composite
                        don't collide on `<dir>/<branch>` when forked)
     :db-path         — datahike file-store path (default .dvergr/db)"
  [& {:keys [with-git? with-datahike? repo-path worktrees-dir db-path]
      :or {with-git?      true
           with-datahike? true
           ;; The agent code workspace (.dvergr/workspace), NOT dvergr's own
           ;; source tree — the sandbox forks this, not user.dir. Pass an
           ;; explicit :repo-path to point a room at a user project instead.
           ;; worktrees-dir left nil ⇒ create-git-system derives a PER-REPO dir.
           repo-path      (paths/workspace-dir)}}]
  (let [base-ctx (ctx/create-execution-context)]
    (binding [rtc/*execution-context* base-ctx]
      (when with-git?
        (try
          ;; create-git-system auto-initialises the default .dvergr/workspace repo.
          (ygg/register! (git/create-git-system
                          :repo-path repo-path
                          :worktrees-dir worktrees-dir))
          (catch Exception e
            (tel/log! {:level :warn :id :daemon/git-init-failed
                       :data {:error (.getMessage e)}}
                      "Could not register git system"))))
      ;; RF5 S4.3: the shared "dvergr-chat-db" datahike is GONE. The room registry
      ;; + identity live in the global system-db (dvergr.system.db); each room's
      ;; conversation + cost ledger live in its OWN per-room messages store. The
      ;; `with-datahike?`/`db-path` args are retained (no-op) for caller compat.

      ;; Daemon-wide peer-bus. Sits on the base-ctx; every room/fork
      ;; bus relays into it (see dvergr.discourse/fork-room and the
      ;; future Room constructor). Cross-cutting subscribers — TUI
      ;; pending-review badge, audit, oversight agents — tap one
      ;; place instead of N rooms.
      (require 'dvergr.runtime.peer-bus)
      ((resolve 'dvergr.runtime.peer-bus/create!) base-ctx)
      (tel/log! {:id :daemon/peer-bus-registered}
                "Registered daemon peer-bus")
      base-ctx)))

;; ============================================================================
;; Agent Profiles
;; ============================================================================

(defn- load-agent-prompt
  "Persona markdown for `profile-name` — project-local `.dvergr/agents/<name>.md`
   if present, else the built-in `resources/agents/<name>.md`. nil if neither.
   (Resolution lives in `dvergr.agent.persona`.)"
  [profile-name]
  (persona/resolve-prompt profile-name))

;; ============================================================================
;; Skills System — implementation lives in dvergr.orchestration.skills.
;; ============================================================================

;; Prompt assembly (discourse-preamble, tool-use-guideline, planning-mode-guideline,
;; and skills injection) moved to `dvergr.agent.prompt` — the ONE assembler shared
;; with personas. The standard turn path below calls `prompt/assemble-system-prompt`
;; and appends `prompt/planning-mode-guideline` per turn when the room is in /plan.

;; ============================================================================
;; Tool Sets
;; ============================================================================

(defn- chat-tools
  "Return tool map for chat mode: SCI-first. `clojure_eval` is the ONE universal
   capability — bash (`bash/run`), the whole intake.* surface (web/hn/yt/tweet/
   reddit/…), `llm/call`, `kb/*`, `calendar/*`, `agents/*`, fork/
   propose primitives are all just functions in the sandbox, not separate tools.
   Composing Clojure beats a one-shot tool: the agent can chain and transform
   results in the same eval, keeping state in its REPL.

   So we deliberately DROPPED the redundant data wrappers (web_fetch,
   youtube_transcript, tweet_lookup, llm_call) — each is a
   single Clojure call away. We KEEP a few first-class tools: `shell` (a direct
   door to the same muschel session `bash/run` uses — some models reach for a
   shell tool before the REPL) and `knowledge_search`/`knowledge_add` (the
   recall-then-save loop is the secretary's core, and the daemon nudges
   `knowledge_add` by name)."
  []
  (select-keys @tools/registry
               ["clojure_eval"
                "shell"
                "knowledge_search" "knowledge_add"]))

(defn- huginn-tools
  "Return tool map for Huginn (code-first intake monitor).
   Huginn uses clojure_eval + intake SCI namespaces for research
   (intake.web/search, intake.web/fetch, intake.hn/search, …),
   knowledge tools for storage, llm_call for summarization, and
   mail tools for inbox."
  []
  (select-keys @tools/registry
               ["clojure_eval"
                "knowledge_search" "knowledge_add"
                "llm_call"
                "mail_sync" "mail_inbox" "mail_read" "mail_search"]))

;; ============================================================================
;; Shared Helpers
;; ============================================================================

(defn- resolve-tools-fn
  "Pick the tool set based on agent tags."
  [agent-config]
  (let [tags (set (or (:tags agent-config) #{}))]
    (cond
      (contains? tags :secretary) chat-tools
      (contains? tags :intake)    huginn-tools
      :else                       safe-tools)))

;; ============================================================================
;; Agent Creation
;;
;; (RF5/harness cleanup) The legacy `:_system` → system-receiver → response-sink
;; egress is gone: agent replies reach every surface by living on the room bus —
;; thin channels (Telegram) relay via `dvergr.adapters.core/mirror-room!`, rich
;; surfaces (TUI/web) render by observing room state. `:_system` survives only as
;; the `:from` of daemon-injected system notices (see `actors.transport`).
;; ============================================================================

;; Defined just below (online-agent view); create-agent! upserts the actor
;; row via daemon-sys-conn before the helpers' own definitions.
(declare daemon-sys-conn)

(defn resolve-system-prompt
  "Effective system prompt for an agent config: an explicit :system-prompt
   wins; otherwise the profile markdown (resources/agents/<profile|id>.md)
   via load-agent-prompt. nil if neither yields one."
  [agent-config]
  (or (:system-prompt agent-config)
      (load-agent-prompt (or (:profile agent-config) (:id agent-config)))))

(defn resolve-safe-config
  "Apply security defaults to a raw agent-config; explicit values override.
   Resolves :id, :tools (→ the minimal coding set unless given) and :system-prompt
   (→ profile markdown unless given). Pure; shared by create-agent! and the
   medium adapter's on-demand room joins. Pass `:tools (safe-tools)` (or any
   explicit set) to widen the surface beyond the lean default."
  [agent-config]
  (let [agent-id (or (:id agent-config) (keyword (gensym "agent-")))]
    (merge {:isolation                  :sci
            :budget-dollars             0.25
            :budget-checkpoint-grace-ms 60000}
           agent-config
           {:id agent-id
            ;; Lean by default: structured file tools + jailed shell +
            ;; clojure_eval (the escape hatch). Everything functional goes through
            ;; clojure_eval in the SCI sandbox, not a wide tool surface.
            :tools (or (:tools agent-config)
                       (tools/normalize-tools tools/minimal-coding-tools))}
           (when-let [sp (resolve-system-prompt (assoc agent-config :id agent-id))]
             {:system-prompt sp}))))

(defn- build-room-participant
  "Build the enriched Participant for `safe-config`, first joined to
   `target-room`. A daemon agent is just a generic `dvergr.discourse.llm/llm-agent`
   — the SAME turn handler personas run on — wrapped in the daemon's standard
   enrichments. There is no daemon-specific turn handler and no agent-'type':

     - the tool set follows the agent's tags (chat / huginn / safe), resolved once;
     - the system prompt is assembled once here (preamble + profile + skills +
       tool-use guideline + sandbox pointer) — the ONE assembler personas use;
     - llm-agent owns the per-[room,agent] working ctx (room-context fold),
       budget-checkpoint, /plan + /model handling, 🔧 activity, Esc-cancel, and
       the fork factory; the enrichment wrappers re-thread through that factory,
       so a fork clones the whole stack.

   Periodic / 'intake' work is NOT a type either: a scheduled `dvergr.scheduler`
   entry simply posts a task Message into the room, which this same agent handles
   as an ordinary turn (huginn prompt + intake tools make it an intake agent —
   plain config, no engine knowledge)."
  [daemon safe-config target-room]
  (let [exec-ctx      (:execution-ctx daemon)
        base-prompt   (or (:system-prompt safe-config)
                          (load-agent-prompt (or (:profile safe-config)
                                                 (:id safe-config)))
                          ;; No profile file and no explicit :system-prompt → still
                          ;; establish IDENTITY, so the agent knows who it is. An
                          ;; anonymous "helpful assistant" sitting in a room full of
                          ;; another agent's messages adopts that agent's persona
                          ;; (the bug where a fresh `scribe` thought it was `var`).
                          (str "You are " (name (:id safe-config))
                               ", an AI agent in a shared dvergr workspace."
                               (when-let [d (not-empty (str/trim (str (:description safe-config))))]
                                 (str " " d))))
        tools-map     ((resolve-tools-fn safe-config))
        system-prompt (prompt/assemble-system-prompt base-prompt {:tools tools-map})]
    ;; Room-safety (self-filter / silence / plain-reply) is the DEFAULT in
    ;; llm-agent now, so a daemon agent is just a plain llm-agent.
    ;; When an agent pins NEITHER provider nor model, fall back to the env-aware
    ;; default (providers/default-spec) — best AVAILABLE registered provider by
    ;; preference (anthropic→fireworks→openai→claude-code) + its default model — so
    ;; a fresh install works with WHICHEVER provider key is set, not only Fireworks.
    ;; The :fireworks/minimax literals remain only as a last resort when no provider
    ;; is registered at all (the agent then fails at call time; boot already warns).
    (let [ds (when-not (or (:provider safe-config) (:model safe-config))
               (providers/default-spec))]
      (llm/llm-agent
       {:id      (:id safe-config)
        :spec    {:provider      (or (:provider safe-config) (:provider ds) :fireworks)
                  :model         (or (:model safe-config) (:model ds)
                                     "accounts/fireworks/models/minimax-m2p5")
                  :system-prompt system-prompt
                  :isolation     (or (:isolation safe-config) :sci)}
        :tools   tools-map
        :budget  {:dollars             (or (:budget-dollars safe-config) 0.25)
                  :checkpoint-grace-ms (or (:budget-checkpoint-grace-ms safe-config)
                                           60000)}
        :ctx     exec-ctx}))))

(defn join-agent-to-room!
  "Join the agent described by `safe-config` into `room` as a room-bound
   Participant (idempotent — no-op if already a member). Binds the room's
   ctx. Used by create-agent! for startup rooms and by the medium adapter
   to attach the routed agent to a per-chat Room on demand. Returns the room."
  [daemon safe-config room]
  (when-not (contains? @(:participants room) (:id safe-config))
    (binding [rtc/*execution-context* (:ctx room)]
      (d/join room (build-room-participant daemon safe-config room))))
  room)

(defn create-agent!
  "Build a discourse Participant for this agent, apply the enrichment
   chain + drivers, and join it into the daemon's discourse room.

   Security defaults applied unless explicitly overridden:
     :isolation                 - :sci (sandboxed Clojure eval)
     :tools                     - safe-tools (excludes shell, run_tests, telegram_*)
     :budget-dollars            - 0.25 per task
     :budget-checkpoint-grace-ms - 60000 ms grace window when budget
                                  is exhausted and the manager is
                                  asked to extend (see
                                  dvergr.agent.process/budget-checkpoint!)

   To grant extra power, pass explicit overrides in agent-config:
     :isolation :native       — full JVM (trusted agents only, never Telegram)
     :tools (tools/all-tools) — unrestricted (trusted agents only)

   Returns the registered Participant."
  [daemon agent-config]
  (let [exec-ctx        (:execution-ctx daemon)
        room            (:discourse-room daemon)
        safe-config     (resolve-safe-config agent-config)
        agent-id        (:id safe-config)
        room-slugs      (:rooms safe-config)]
    ;; Presence == room membership, so an agent needs a root room to be brought
    ;; online. Refuse loudly rather than write a half-created agent (durable row
    ;; with no presence — invisible in the roster). An embedded system must
    ;; carry a :discourse-room (a full daemon always does).
    (when-not room
      (throw (ex-info "Cannot create agent: the system has no root room (:discourse-room) to bring it online. Start a full daemon, or pass :discourse-room when embedding the web/system."
                      {:agent-id agent-id})))
    (let [;; Base Participant joined to the daemon's root room (for
          ;; Telegram dispatch, system events, ad-hoc messaging).
          participant (build-room-participant daemon safe-config room)]
      (binding [rtc/*execution-context* exec-ctx]
        (d/join room participant)
      ;; ALSO join into:
      ;;   (a) the global room — every agent should be reachable here
      ;;       so the user can chat with anyone in one shared transcript
      ;;   (b) each :rooms slug from the agent's config (extra project rooms, etc.)
      ;; Deduplicate so an agent doesn't join the same room twice if
      ;; their config already lists the global slug.
        (let [global-slug (or (:global-room-slug (:config daemon)) "boardroom")
              all-slugs   (distinct (cons global-slug (map str room-slugs)))]
          (doseq [slug all-slugs]
            (let [room-id (keyword (str slug))]
              (if-let [pr-room (rreg/lookup room-id)]
                (join-agent-to-room! daemon safe-config pr-room)
                (tel/log! {:level :warn :id :daemon/agent-room-not-found
                           :data {:agent-id agent-id :slug slug}})))))
      ;; Ensure a durable actor row exists for this agent (idempotent) — this is
      ;; the SINGLE materialization point for every hosted agent (config-declared,
      ;; file-autostarted, or runtime-created), so the actors ⋈ membership view
      ;; sees them all.
      ;; Use the RAW agent-config, not safe-config: safe-config's :tools
      ;; has been resolved to a tool-def map (with fns), which isn't
      ;; EDN-round-trippable for the actor :config field.
        (when-let [conn (daemon-sys-conn)]
          (actors/ensure-agent! conn agent-id agent-config)))
    ;; Identity = the actor row (above); presence = room membership (the
    ;; d/join calls above). No separate runtime registry.
      participant)))

(defn provision-agent!
  "The UI 'add agent' path. Writes the durable actor row + persona file (via
   `dvergr.agent.ops/create-agent!`) AND brings the agent ONLINE (builds its
   participant and joins the global room) so it appears in the tree and is
   chattable right away — otherwise a newly-created row exists only on the web
   roster but is invisible in the room/agent tree (presence == room membership).
   `fields` is the ops/create-agent! field map (:id required). Returns the
   agent view, or nil if the id already exists / no chat DB."
  [daemon fields]
  (let [view (binding [rtc/*execution-context* (:execution-ctx daemon)]
               (ops/create-agent! fields))]
    (when view
      (create-agent! daemon (-> (select-keys fields [:provider :model :tags
                                                     :description :budget-dollars :rooms])
                                (assoc :id (:id view)))))
    view))

(defn stop-agent!
  "Leave the participant from every room it joined, dropping its presence
   (presence == room membership). The participant's spin and any driver
   pumps continue running on the shared executor — discourse has no
   per-spin cancel today — but no further messages are routed to it. The
   durable actor row is left intact (use actors/dismiss! to retire it)."
  [daemon agent-id]
  (binding [rtc/*execution-context* (:execution-ctx daemon)]
    ;; create-agent! joins the agent to several rooms (daemon + global +
    ;; each config :rooms slug). Leave ALL of them so presence correctly
    ;; drops — leaving only the daemon room would leak membership in the
    ;; global/config rooms and keep `online?` true.
    (doseq [room (rreg/list-rooms)]
      (when (contains? @(:participants room) agent-id)
        (binding [rtc/*execution-context* (:ctx room)]
          (d/leave room agent-id))
        ;; Drop the agent's cached working ctx for this room (unsubscribe its
        ;; bus fold). It re-seeds from the store next time the agent rejoins.
        (room-context/drop-ctx! (:id room) agent-id)))
    :stopped))

;; ============================================================================
;; Online-agent view — actors ⋈ room membership (replaces registry reads)
;;
;; Presence is room membership (see dvergr.actors). The agents the daemon
;; is hosting = `actors/online-actors` (room members projected through
;; their durable actor rows). These thin daemon helpers exist only so the
;; chat-conn / discourse-root needn't be re-derived at every call site.
;; ============================================================================

;; RF5 S3/S4: actor identity + the room registry live in the global, un-forked
;; system-db — there is no per-room chat-db. Resolves without a bound ctx.
(defn- daemon-sys-conn [] (sdb/get-conn))

(defn agent-join-config
  "The raw config used to join `agent-id` into a room — **actor-row-first** so
   that edits made through the agent-management UI (which write the durable
   actor row, see `dvergr.agent.ops`) take effect on the next room join without
   a daemon restart. Reads the actor row's :config (provider/model/tools/rooms/
   budget/description) and merges in :id + :name + :tags; falls back to the
   boot-time `daemon :config` when no row exists yet. nil if unknown to both.
   Callers pass the result through `resolve-safe-config`."
  [daemon agent-id]
  (binding [rtc/*execution-context* (:execution-ctx daemon)]
    (if-let [actor (when-let [conn (daemon-sys-conn)]
                     (actors/lookup conn agent-id))]
      (cond-> (assoc (or (:config actor) {}) :id agent-id)
        (:name actor)        (assoc :name (:name actor))
        (seq (:skills actor)) (assoc :tags (:skills actor)))
      (when-let [cfg (get-in daemon [:config :agents agent-id])]
        (assoc cfg :id agent-id)))))

;; ============================================================================
;; Medium adapter — Telegram (D/E/F step 3b-1)
;;
;; A Telegram conversation is a per-chat discourse Room (the venue). Inbound
;; messages are posted into that Room AS the sender's user-actor; the routed
;; agent (joined to the Room) rehydrates its context from the Room log and
;; replies `:to` the user-actor, which routes through the Bus to that user's
;; egress Participant → sent back out. This is the Participant-on-Bus model:
;; no `:_system`, no response-sinks for the interactive path.
;; ============================================================================

(defn- ensure-dm-room!
  "Find-or-create the per-chat discourse Room for a Telegram chat-id (the
   venue) and ensure `agent-id` (a configured agent) is joined. Returns the
   live discourse Room, or nil if there's no chat DB conn."
  [daemon chat-id agent-id]
  (binding [rtc/*execution-context* (:execution-ctx daemon)]
    (let [slug    (str "tg-" chat-id)
          room-id (rstore/slug->room-id slug)
          room    (or (rreg/lookup room-id)
                      (do (rooms/create-room!
                           {:title            (str "Telegram " chat-id)
                            :slug             slug
                            :type             :telegram-mirror
                            :telegram-chat-id chat-id
                            :ctx              (:execution-ctx daemon)})
                          (rreg/lookup room-id)))]
      (when (and room agent-id)
        (when-let [cfg (agent-join-config daemon agent-id)]
          (join-agent-to-room! daemon (resolve-safe-config cfg) room)))
      room)))

(defn- fmt-tool-use
  "One line of tool detail — '🔧 name  <input>' (input stripped of :db/id noise,
   truncated). Rich enough to be worth expanding."
  [tu]
  (let [nm  (or (:tool-use/name tu) (:name tu))
        inp (not-empty (dissoc (or (:tool-use/input tu) (:input tu) {}) :db/id))
        s   (when inp (let [p (pr-str inp)] (subs p 0 (min 220 (count p)))))]
    (str "🔧 " nm (when s (str "  " s)))))

(defn- turn-tool-lines
  "Tool-call detail lines for the just-finished turn in a Telegram chat's room —
   the `:tool-uses` on the trailing messages after the last user message (tool
   activity lives on the `:_activity` lane, not the reply, so we gather it at
   send time for the collapsed detail block). Best-effort."
  [daemon chat-id]
  (try
    (binding [rtc/*execution-context* (:execution-ctx daemon)]
      (when-let [room (rreg/lookup (rstore/slug->room-id (str "tg-" chat-id)))]
        (->> (d/messages room {:limit 40})
             reverse
             (take-while #(not= :user (:role %)))
             (mapcat :tool-uses)
             (remove nil?)
             (map fmt-tool-use)
             (remove str/blank?)
             distinct
             vec)))
    (catch Throwable _ nil)))

(defn- telegram-caps
  "The daemon-side capabilities the Telegram adapter needs, as a `caps` map
   (`dvergr.channels.telegram/make-daemon-adapter`). Built here because the
   telegram ns must not depend on the daemon."
  [daemon token default-agent-id tool-exec?]
  {:ctx           (:execution-ctx daemon)
   :token         token
   :default-agent default-agent-id
   ;; The daemon value, so the Telegram handler can build the host ctx for the
   ;; unified slash-command registry (dvergr.discourse.commands) — passed as
   ;; data, so telegram.clj still doesn't statically depend on the daemon ns.
   :daemon        daemon
   ;; Whether EXECUTING tool commands (/clojure_eval <agent> …) are allowed from
   ;; Telegram — opt-in via config `:telegram {:tool-commands? true}` (default
   ;; off, since Telegram is a remote surface even with the bot allowlist).
   :tool-exec?    (boolean tool-exec?)
   ;; Reply + a COLLAPSED expandable blockquote of the turn's tool activity, so
   ;; Telegram reflects the internal turn without polluting the channel.
   :send-fn       (fn [chat-id text]
                    (tg-send/send-response! token chat-id text
                                            (tg-send/tool-activity-html (turn-tool-lines daemon chat-id))))
   ;; Native "typing…" cue while the agent works.
   :typing-fn     (fn [chat-id] (tg/send-chat-action! token chat-id "typing"))
   ;; Label each outbound message with its sender — through one bot every
   ;; actor's reply looks the same. Actor display name (:actor/name) if set,
   ;; else the id.
   :speaker-name  (fn [actor-id]
                    (or (when-let [conn (daemon-sys-conn)]
                          (some-> (actors/lookup conn (if (keyword? actor-id)
                                                        actor-id
                                                        (keyword (str actor-id))))
                                  :name))
                        (some-> actor-id name)))
   :ensure-room   (fn [chat-id agent-id] (ensure-dm-room! daemon chat-id agent-id))
   :ensure-actor  (fn [user-info]
                    (when-let [conn (daemon-sys-conn)]
                      (:id (actors/ensure-external-actor!
                            conn :telegram (:id user-info)
                            :name (or (:first_name user-info)
                                      (:username user-info))))))})

(defn ensure-agent-room!
  "Find-or-create the per-agent DM Room for a local (TUI/web) chat with
   `agent-id` and ensure the agent is joined. Returns the live Room, or nil
   if there's no chat DB conn.

   This is the in-process analogue of `ensure-dm-room!`: the venue is the
   agent itself (slug `dm-<agent>`), the transcript is persistent so the
   agent rehydrates its context from the room log, and a rich frontend
   renders by observing the room bus (no egress Participant)."
  [daemon agent-id]
  (binding [rtc/*execution-context* (:execution-ctx daemon)]
    (let [slug    (str "dm-" (name agent-id))
          room-id (rstore/slug->room-id slug)
          room    (or (rreg/lookup room-id)
                      (do (rooms/create-room!
                           {:title (str "Chat with " (name agent-id))
                            :slug  slug
                            :type  :dm
                            :ctx   (:execution-ctx daemon)})
                          (rreg/lookup room-id)))]
      (when (and room agent-id)
        (when-let [cfg (agent-join-config daemon agent-id)]
          (join-agent-to-room! daemon (resolve-safe-config cfg) room)))
      room)))

;; ============================================================================
;; Daemon Lifecycle
;; ============================================================================

;; ---------------------------------------------------------------------------
;; Storage GC — periodic datahike index reclamation on a DEDICATED thread.
;; Runs OFF the reactive loop (it blocking-derefs datahike's async writer at a
;; boundary; doing that on a spin/dispatch thread could deadlock). Config:
;;   :gc {:interval-ms <ms, default 6h> :retention-days <n, default nil = keep all>}
;; retention-days nil ⇒ keep ALL history, reclaim only orphan (fork/branch) garbage;
;; set it to actually collapse old history (the load-bearing knob for bounded growth).
;; ---------------------------------------------------------------------------
(defonce ^:private gc-thread (atom nil))

(defn- run-gc-sweep! [exec-ctx {:keys [retention-days]}]
  (let [remove-before (when retention-days
                        (java.util.Date. (- (System/currentTimeMillis)
                                            (* (long retention-days) 24 60 60 1000))))
        opts (cond-> {} remove-before (assoc :remove-before remove-before))]
    (binding [rtc/*execution-context* exec-ctx]
      (try (let [r (srooms/gc-stores! opts)]
             (tel/log! {:id :rooms/gc-sweep :data r} "Storage GC sweep complete"))
           (catch Throwable e
             (tel/log! {:level :warn :id :rooms/gc-failed :data {:error (.getMessage e)}}
                       "Storage GC sweep failed"))))))

(defn- start-gc-loop! [exec-ctx {:keys [interval-ms] :or {interval-ms 21600000} :as gc-cfg}]
  (let [t (Thread.
           (fn []
             (run-gc-sweep! exec-ctx gc-cfg)          ; boot sweep
             (while (not (.isInterrupted (Thread/currentThread)))
               (try (Thread/sleep (long interval-ms))
                    (run-gc-sweep! exec-ctx gc-cfg)
                    (catch InterruptedException _
                      (.interrupt (Thread/currentThread))))))
           "dvergr-storage-gc")]
    (.setDaemon t true)
    (.start t)
    (reset! gc-thread t)))

(defn- stop-gc-loop! []
  (when-let [t @gc-thread] (.interrupt t) (reset! gc-thread nil)))

(defn start!
  "Start the daemon with the given configuration.

   Config map:
     :telegram {:token \"BOT_TOKEN\"}
     :agents   {:var {:provider :fireworks
                       :model \"...\"
                       :system-prompt \"...\"
                       :tags #{:secretary}
                       :description \"Default greeting agent\"}}
     :default-agent :var  ;; which agent handles new sessions

   Returns Daemon record."
  [config]
  (tel/log! {:id :daemon/starting} "Starting dvergr daemon")
  ;; Optional mail intake: register its tool only if clojure-mail is on the
  ;; classpath (it isn't for a library consumer that didn't add the mail deps).
  (try (require 'dvergr.intake.mail)
       (catch Throwable _
         (tel/log! {:level :info :id :daemon/mail-intake-unavailable}
                   "mail intake unavailable (clojure-mail not on classpath)")))
  ;; Register the agent-facing schedule_* tools (schedule_create/list/cancel).
  ;; The scheduler BACKEND is wired below (install-schema! + restore-schedules!);
  ;; requiring this ns registers the tools so an agent can set up its own
  ;; recurring tasks — agents opt in by listing them in their :tools.
  (require 'dvergr.scheduler.tools)
  ;; Load the model registry from models.edn (the configured Fireworks set +
  ;; pricing/defaults). Without this the registry holds only the built-in
  ;; Anthropic defaults, so cost tracking and the model dropdowns are wrong.
  (registry/ensure-models-loaded!)

  ;; Create execution context, daemon-wide discourse room, and the
  ;; :_system receiver that drains all agent replies into the sink fan-out.
  ;; RF5 (Option B): the daemon root does NOT register the legacy `.dvergr/workspace`
  ;; git system. Its agents run in ROOMS, each of which carries its OWN per-room repo
  ;; (RF5 S2); a root workspace-git was a pre-RF5 vestige that bled into every room
  ;; composite (forked + collided on the shared worktrees-dir). Room-less sandboxes
  ;; (sidecar/REPL) fall back to the `.dvergr/workspace` directory directly
  ;; (workspace-root), so we just keep that dir present.
  (git/ensure-workspace-repo!)
  (let [exec-ctx       (create-shared-context :with-git? false
                                              :with-datahike? true
                                              :db-path (:db-path config))
        discourse-room (d/room :daemon exec-ctx)
        status-a       (atom :starting)
        daemon         (->Daemon config exec-ctx discourse-room
                                 nil nil status-a)]
    (binding [rtc/*execution-context* exec-ctx]
      ;; Stash the root discourse-room on ctx state so the SCI sandbox
      ;; (and any other consumer) can reach it without a Daemon handle.
      ;; Same pattern as peer-bus.
      (rtc/swap-state! [:dvergr/discourse-root]
                       (constantly discourse-room)))

    ;; Register execution context for distributed addressing
    (sdist/register-context! :default exec-ctx)

    ;; Initialize allowlist from config (can be changed at runtime via allowlist/add-user! etc.)
    ;; Config stores full user maps; allowlist checks bare :id numbers or "@username" strings.
    (when-let [users (seq (:allowed-users config))]
      (allowlist/set-users!
       (mapcat (fn [u]
                 (cond
                   (map? u)     (remove nil? [(:id u) (when (:username u) (str "@" (:username u)))])
                   (number? u)  [u]
                   (string? u)  [u]))
               users)))

    ;; Initialize stats + persistent-room registry from the shared
    ;; datahike connection. The legacy dvergr.rooms.bus is gone —
    ;; agents subscribe to rooms by being Participants in their
    ;; discourse Rooms.
    (binding [rtc/*execution-context* exec-ctx]
      ;; RF5 S4.3: chat-db is gone (registry → system-db, conversations → per-room
      ;; stores). stats/rooms init were already no-ops; boot the rest directly.
      (do

        ;; Derive the agent-scoped tool commands (/clojure_eval <agent> …,
        ;; /shell, /knowledge_search) into the unified slash-command registry,
        ;; now that the tool registry is populated. Idempotent.
        ((requiring-resolve 'dvergr.agent.tool-commands/register!))

        ;; (Config-declared agents are materialized into the durable :actor/*
        ;; table lazily by the create-agent! loop below — its ensure-agent! call
        ;; writes the row — so no separate bootstrap pre-write is needed.)

        ;; Build discourse.Room instances for every persistent room in
        ;; Datahike and register them. From here on, the discourse
        ;; layer is the canonical "which rooms exist" surface;
        ;; rooms/* and the Datahike entities back it.
        ;;
        ;; FIRST recreate each room's OWN execution context (RF5 S2) + register
        ;; its systems into it — the composite + room-ctx map are in-memory, so a
        ;; restart must rebuild them. hydrate-registry! then builds each Room ON
        ;; its room-ctx, so this MUST run before it.
        (srooms/hydrate-rooms!)
        (rooms/hydrate-registry! exec-ctx)

        ;; P3: GC abandoned fork worktrees/branches. Fork rooms are in-memory only,
        ;; so any fork branch on disk now is an orphan from a prior run — prune it.
        (try
          (let [n (srooms/gc-orphan-fork-branches!)]
            (when (pos? n)
              (tel/log! {:id :rooms/fork-gc :data {:pruned n}}
                        (str "Pruned " n " orphan fork branch(es) on boot"))))
          (catch Throwable e
            (tel/log! {:level :warn :id :rooms/fork-gc-failed :data {:error (.getMessage e)}}
                      "Orphan-fork GC failed")))

        ;; Storage GC loop (datahike index reclamation) on a dedicated thread —
        ;; boot sweep + periodic. Prevents the unbounded `.dvergr` growth that
        ;; took the old monolithic store to tens of GB.
        (start-gc-loop! exec-ctx (:gc config))

        ;; Auto-create THE global room — the single default channel
        ;; every registered agent auto-joins. Slug is configurable via
        ;; :global-room-slug in config (default "boardroom"). All
        ;; user-created rooms are sub-rooms of this one unless they
        ;; opt out by setting their own :parent-id.
        (let [global-slug (or (:global-room-slug config) "boardroom")
              global-title (or (:global-room-title config) "Boardroom")]
          (when-not (rooms/get-room-by-slug global-slug)
            (rooms/create-room!
             {:title global-title
              :slug  global-slug
              :type  :internal
              :ctx   exec-ctx})
            (tel/log! {:id :daemon/global-room-created
                       :data {:slug global-slug}}
                      "Created global room")))

        ;; Optional additional rooms from config (:bootstrap-rooms).
        ;; Each entry: {:slug "..." :title "..." :parent-slug "..."}.
        ;; Parent defaults to the global room via create-room!.
        (doseq [{:keys [slug title parent-slug]} (:bootstrap-rooms config)]
          (when-not (rooms/get-room-by-slug slug)
            (rooms/create-room!
             (cond-> {:title title
                      :slug  slug
                      :type  :internal
                      :ctx   exec-ctx}
               parent-slug (assoc :parent-id parent-slug)))
            (tel/log! {:id :daemon/bootstrap-room-created :data {:slug slug}}
                      "Created bootstrap room")))

;; (RF5: the calendar folded into the per-room scheduler — `scheduler/*`
        ;; schedules ARE the calendar (`:once` at `:schedule/next-fire` = an event),
        ;; rendered via `dvergr.room/schedules`; the room scheduler spin fires them.
        ;; The standalone calendar subsystem + dispatcher + iCal sync are gone.)
        nil))

    ;; Create configured agents (after bus init so room sources can subscribe)
    (doseq [[agent-id agent-config] (:agents config)]
      (tel/log! {:id :daemon/create-agent :data {:agent-id agent-id}} "Creating agent")
      (create-agent! daemon (assoc agent-config :id agent-id)))

    ;; Host durable agents created at RUNTIME (via the agent-management UI /
    ;; provision-agent!) that aren't in config — otherwise their presence (room
    ;; membership) would be lost on every restart even though the actor row
    ;; persists. Build each one's config from its row (actor-row-first), skip
    ;; retired and already-configured ids.
    (when-let [conn (daemon-sys-conn)]
      (doseq [a (actors/list-actors conn :kind :agent)
              :let [aid (:id a)]
              :when (and (not (get-in config [:agents aid]))
                         (not= :retired (:status a)))]
        (when-let [cfg (agent-join-config daemon aid)]
          (tel/log! {:id :daemon/host-durable-agent :data {:agent-id aid}}
                    "Hosting durable runtime-created agent")
          (create-agent! daemon cfg))))

    ;; File-driven autostart agents: any `agents/*.md` flagged `autostart: true`
    ;; (and `vetted: true`) that isn't already config-declared or a durable row.
    ;; Dropping a vetted autostart agent file boots it — config `:agents` is now
    ;; optional sugar, not the only path. `rooms: ["*"]` expands to every room.
    ;; Idempotent: the first boot writes the actor row, so subsequent boots host
    ;; it via the durable-rows loop above instead.
    (binding [rtc/*execution-context* exec-ctx]
      (let [conn      (daemon-sys-conn)
            all-slugs (mapv :slug (rreg/list-rooms))
            durable?  (fn [aid] (when conn
                                  (some-> (actors/lookup conn aid)
                                          :status (not= :retired))))]
        (doseq [[aid cfg] (defs/autostart-agents)
                :when (and (not (get-in config [:agents aid]))
                           (not (durable? aid)))]
          (let [cfg (cond-> cfg
                      (some #{"*"} (:rooms cfg))
                      (assoc :rooms (vec (remove #{"*"} (concat (:rooms cfg) all-slugs)))))]
            (tel/log! {:id :daemon/autostart-agent :data {:agent-id aid :rooms (:rooms cfg)}}
                      "Autostarting file-defined agent")
            (create-agent! daemon cfg)))))

    ;; Boardroom-mirror sink: DROPPED (D/E/F 3b-1). Agent replies now live in
    ;; the room where the conversation happened; an agent that should post to
    ;; boardroom is a participant there and replies into it directly. DM
    ;; replies stay in their per-chat room (not mirrored to the shared room).
    ;;
    ;; Conversation search-index sink: RE-HOMED to `index-reply!` in the agent
    ;; handler's `->reply` — every reply, from any frontend, indexed at the
    ;; source, with no `:_system` dependency.

    ;; Connect Telegram if configured
    (let [daemon-with-tg
          (if-let [tg-config (:telegram config)]
            (do
              (tel/log! {:id :daemon/telegram-connecting} "Connecting Telegram bot")
              (let [token   (:token tg-config)
                    default-agent-id (or (:default-agent config) :var)
                    ;; Telegram medium adapter (dvergr.channels.telegram) —
                    ;; inbound posts into per-chat Rooms as the sender's
                    ;; user-actor; outbound via the user's egress Participant.
                    ;; The daemon-side capabilities are injected as `caps`.
                    caps    (telegram-caps daemon token default-agent-id
                                           (:tool-commands? tg-config))
                    adapter (tg/make-daemon-adapter caps)
                    tg-ch (tg/make-telegram
                           {:token token
                            :poll? true})
                    connected (channels/connect!
                               tg-ch
                               :on-message (fn [msg]
                                             (try
                                               (tg/handle-inbound! adapter msg caps)
                                               (catch Exception e
                                                 (tel/log! {:level :error :id :daemon/dispatch-error
                                                            :data {:error (.getMessage e)}} "Dispatch error")))))]
                ;; Egress is the adapter's `mirror-room!` (set up per tg-room by
                ;; `adapters/inbound!`): every agent reply on a Telegram room's bus
                ;; relays back to the venue. No response-sink, no `:_system` drain.
                (tel/log! {:id :daemon/telegram-connected :data {:channel-id (:id connected)}} "Telegram connected")
                (assoc daemon :telegram-ch connected)))
            daemon)]

      ;; Start HTTP server if configured AND if dvergr.web.server is on
      ;; the classpath (it lives in src-clients/, loaded via the
      ;; :cli/:tui aliases). Library users not using those aliases
      ;; just don't get a web UI — same as `:http` being absent.
      (let [daemon-with-http
            (if-let [http-config (:http config)]
              (let [port (or (:port http-config) 17880)
                    ip   (or (:ip http-config) "127.0.0.1")
                    start-fn (try (requiring-resolve 'dvergr.web.server/start!)
                                  (catch Throwable _ nil))
                    server-state-var
                    (when start-fn
                      (try (requiring-resolve 'dvergr.web.server/server-state)
                           (catch Throwable _ nil)))]
                (cond
                  (nil? start-fn)
                  (do (tel/log! {:level :info :id :daemon/web-server-skipped}
                                "dvergr.web.server not on classpath — running headless (add the :web alias, e.g. -M:local:web, for the dashboard)")
                      daemon-with-tg)
                  :else
                  (try
                    (start-fn daemon-with-tg :port port :ip ip)
                    (assoc daemon-with-tg :http-server @@server-state-var)
                    (catch java.net.BindException e
                      (tel/log! {:level :warn
                                 :id    :daemon/web-server-bind-failed
                                 :data  {:port port :error (.getMessage e)}}
                                "Web server bind failed; continuing without web UI")
                      daemon-with-tg)
                    (catch Exception e
                      (tel/log! {:level :warn
                                 :id    :daemon/web-server-start-failed
                                 :data  {:port port :error (.getMessage e)}}
                                "Web server start failed; continuing without web UI")
                      daemon-with-tg))))
              daemon-with-tg)]

        ;; RF5: schedules are per-room (rows in each room's store) and fire via
        ;; the room's reactive scheduler spin (dvergr.rooms.scheduler), started
        ;; on the room-register hook + rebuilt by hydrate. No global schema
        ;; install or restore pass — the clock heartbeat below drives them all.

        ;; Start the daemon's single reactive clock heartbeat — the one timer
        ;; that drives all time-based reactivity (per-room schedulers, RF5).
        (try
          (binding [rtc/*execution-context* exec-ctx]
            (clock/start!))
          (catch Exception e
            (tel/log! {:level :warn :id :daemon/clock-start-failed
                       :data {:error (.getMessage e)}} "Clock heartbeat failed to start")))

        ;; Optional mail: attach the configured mailbox to its room + start the
        ;; daemon-side READ-ONLY IMAP pull (local-only; fork/merge never touches the
        ;; server). Config: :mail {:account :datahike-contact :room "slug" :sync-interval-ms N}.
        (when-let [{:keys [account room sync-interval-ms]
                    :or {account :datahike-contact sync-interval-ms 600000}} (:mail config)]
          (try
            (if-let [r (rreg/lookup (rstore/slug->room-id room))]
              (mail/start-integration! {:account-id account :room-ctx (:ctx r)
                                        :interval-ms sync-interval-ms})
              (tel/log! {:level :warn :id :mail/room-not-found :data {:room room}}
                        "Mail room not found; mailbox not attached"))
            (catch Throwable e
              (tel/log! {:level :warn :id :mail/integration-failed :data {:error (.getMessage e)}}
                        "Mail integration failed to start"))))

        (reset! status-a :running)
        (reset! current-daemon daemon-with-http)
        (tel/log! {:id :daemon/started :data {:agent-count (count (:agents config))}} "Daemon started")
        daemon-with-http))))

(defn stop!
  "Orderly shutdown of the daemon.

   1. Stop the system watcher
   2. Leave all participants from the discourse room
   3. Disconnect channels, close sessions
   4. Unregister execution context"
  [daemon]
  (tel/log! {:id :daemon/stopping} "Stopping daemon")
  (reset! (:status daemon) :stopping)
  (stop-gc-loop!)

  ;; Leave all registered participants from the discourse room and
  ;; unregister them. Driver pumps + participant spins remain on the
  ;; executor (discourse has no per-spin cancel today) but no further
  ;; messages are routed to them. Reserved `_`-prefixed ids (e.g. the
  ;; `:_activity` lane) are not agents — skip them.
  (binding [rtc/*execution-context* (:execution-ctx daemon)]
    (doseq [agent-id (->> (some-> (:discourse-room daemon) :participants deref keys)
                          (remove #(.startsWith (name %) "_")))]
      (tel/log! {:id :daemon/stop-agent :data {:agent-id agent-id}} "Stopping agent")
      (try (stop-agent! daemon agent-id)
           (catch Exception _))))

  ;; Drop all cached per-room working ctxs (the cache is a defonce surviving a
  ;; same-process restart; a fresh start must re-seed rather than reuse them).
  (room-context/clear-all!)
  (srooms/clear-room-ctxs!)

  ;; Stop the reactive clock heartbeat (per-room scheduler spins go quiet with
  ;; it; their rows persist in each room's store and resume on next boot).
  (clock/stop!)

  ;; Stop HTTP server (only if dvergr.web.server is on the classpath)
  (when (:http-server daemon)
    (when-let [stop-fn (try (requiring-resolve 'dvergr.web.server/stop!)
                            (catch Throwable _ nil))]
      (stop-fn)))

  ;; Stop the mail IMAP sync loop (if running)
  (mail/stop-sync!)

  ;; Disconnect Telegram
  (when-let [tg-ch (:telegram-ch daemon)]
    (tel/log! {:id :daemon/telegram-disconnecting} "Disconnecting Telegram")
    (channels/disconnect! (:id tg-ch)))

  ;; Unregister execution context
  (sdist/unregister-context! :default)

  ;; Tear down the discourse room — clears participants, stops the
  ;; spindel ExecutionContext drain thread. Without this, dvergr tests
  ;; (or any short-lived embedding) accumulate one drain thread per
  ;; start!/stop! cycle. Virtual-thread-backed since spindel 0.1.10, so
  ;; the leak is invisible — but explicit teardown is the right contract.
  (when-let [room (:discourse-room daemon)]
    (try (d/close-room! room)
         (catch Exception _)))

  (reset! (:status daemon) :stopped)
  (reset! current-daemon nil)
  (tel/log! {:id :daemon/stopped} "Daemon stopped")
  :stopped)

;; ============================================================================
;; Inspection
(defn start-from-config!
  "Start daemon using config.local.edn (or DVERGR_CONFIG env var path).
   Convenience wrapper around start! for REPL and production use.

   (def d (start-from-config!))
   (daemon-status d)"
  []
  (start! (config/daemon-config)))

;; ============================================================================

(defn list-agents
  "List all agents managed by the daemon.

   Self-binds the daemon's execution context so the ctx-scoped registry
   reads land on the daemon's ctx regardless of caller binding."
  [daemon]
  (binding [rtc/*execution-context* (or (:execution-ctx daemon)
                                        rtc/*execution-context*)]
    (actors/online-actors)))

(defn daemon-status
  "Get daemon status summary."
  [daemon]
  (binding [rtc/*execution-context* (or (:execution-ctx daemon)
                                        rtc/*execution-context*)]
    {:status @(:status daemon)
     :agents (count (actors/online-actors))
     :telegram-connected? (some? (:telegram-ch daemon))
     :http-running? (if-let [f (try (requiring-resolve 'dvergr.web.server/running?)
                                    (catch Throwable _ nil))]
                      (f) false)
     :http-port     (when-let [f (try (requiring-resolve 'dvergr.web.server/server-port)
                                      (catch Throwable _ nil))]
                      (f))
     :schedules (count (scheduler/list-all-schedules))}))

(comment
  ;; Example usage:

  ;; Start daemon with Telegram bot
  (def d (start! {:telegram {:token (System/getenv "TELEGRAM_BOT_TOKEN")}
                  :agents {:var {:provider :fireworks
                                 :model "accounts/fireworks/models/minimax-m2p5"
                                 :system-prompt "You are a helpful assistant. Answer questions clearly and concisely."
                                 :tags #{:secretary}
                                 :description "Default agent for new conversations"}}}))

  ;; Check status
  (daemon-status d)
  (list-agents d)
  (list-sessions d)

  ;; Stop
  (stop! d))
