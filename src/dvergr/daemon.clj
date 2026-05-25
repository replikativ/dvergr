(ns dvergr.daemon
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
  (:require [dvergr.config :as config]
            [dvergr.discourse :as d]
            [dvergr.discourse.enrichment :as enr]
            [dvergr.discourse.llm :as llm]
            [dvergr.proposals :as proposals]
            [dvergr.chat.agent :as chat-agent]
            [dvergr.channels.core :as channels]
            [dvergr.channels.telegram :as tg]
            [dvergr.registry :as registry]
            [dvergr.sessions :as sessions]
            [dvergr.chat.context :as chat-ctx]
            [dvergr.tools :as tools]
            [dvergr.git :as git]
            [dvergr.web.server :as web-server]
            [dvergr.scheduler.core :as scheduler]
            [dvergr.calendar.core :as cal]
            [dvergr.calendar.schema :as cal-schema]
            [dvergr.calendar.ical :as cal-ical]
            [dvergr.calendar.dispatcher :as cal-dispatch]
            [dvergr.security.allowlist :as allowlist]
            [dvergr.sandbox :as sandbox]
            [dvergr.stats :as stats]
            [dvergr.rooms :as rooms]
            [dvergr.rooms.bus :as bus]
            [dvergr.rooms.telegram-bridge :as telegram-bridge]
            [dvergr.search :as search]
            ;; Intake tools — require for side-effect registration
            [dvergr.intake.hn]
            [dvergr.intake.lobsters]
            [dvergr.intake.reddit]
            [dvergr.intake.devto]
            [dvergr.intake.github-intake]
            [dvergr.intake.github-metrics]
            [dvergr.intake.knowledge-loader]
            [dvergr.intake.mail]
            [dvergr.intake.slack]
            [dvergr.intake.mastodon]
            [dvergr.intake.bluesky]
            [dvergr.intake.web-fetch]
            [dvergr.intake.youtube]
            [dvergr.intake.twitter]
            [dvergr.intake.adzuna]
            [dvergr.llm-call]
            [datahike.api :as dh]
            [yggdrasil.adapters.datahike :as dh-adapter]
            [org.replikativ.spindel.engine.core :as rtc]
            [org.replikativ.spindel.engine.context :as ctx]
            [org.replikativ.spindel.yggdrasil :as ygg]
            [org.replikativ.spindel.distributed.core :as sdist]
            [org.replikativ.spindel.core :as sp :refer [spin await]]
            [org.replikativ.spindel.spin.sync :as sync]
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
   discourse-room  ;; dvergr.discourse Room — all agents are participants here
   telegram-ch     ;; Connected Telegram channel (or nil)
   http-server     ;; HTTP server state (or nil)
   system-watcher  ;; Atom<future> for the spin draining the :_system inbox
   response-sinks  ;; Atom<[sink-fn ...]> - each (fn [agent-id text]) called on response
   status])        ;; Atom<:starting/:running/:stopping/:stopped>

;; Global daemon atom for REPL access: @current-daemon, (stop! @current-daemon)
(defonce current-daemon (atom nil))

;; Reserved participant id used as the originator (`:from`) for daemon-injected
;; messages (Telegram dispatch, scheduler ticks). Agent replies addressed to
;; this id flow through a single drain spin that fans them out to all
;; registered response sinks.
(def ^:private system-id :_system)

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
     :worktrees-dir   — where to create worktrees (default \".git-worktrees\")
     :db-path         — datahike file-store path (default \"<repo>/.datahike\")"
  [& {:keys [with-git? with-datahike? repo-path worktrees-dir db-path]
      :or {with-git?      true
           with-datahike? true
           repo-path      (System/getProperty "user.dir")
           worktrees-dir  ".git-worktrees"}}]
  (let [base-ctx      (ctx/create-execution-context)
        db-store-path (or db-path (str repo-path "/.datahike"))]
    (binding [rtc/*execution-context* base-ctx]
      (when with-git?
        (try
          (ygg/register! (git/create-git-system
                           :repo-path repo-path
                           :worktrees-dir worktrees-dir))
          (catch Exception e
            (tel/log! {:level :warn :id :daemon/git-init-failed
                       :data {:error (.getMessage e)}}
                      "Could not register git system"))))
      (when with-datahike?
        (try
          (let [db-id  (java.util.UUID/nameUUIDFromBytes
                         (.getBytes db-store-path))
                db-cfg {:store {:backend :file
                                :path db-store-path
                                :id db-id}
                        :keep-history? true
                        :schema-flexibility :write}
                _      (when-not (dh/database-exists? db-cfg)
                         (dh/create-database db-cfg))
                conn   (dh/connect db-cfg)
                dh-sys (dh-adapter/create
                         conn {:system-name "dvergr-chat-db"})]
            (try
              (require 'dvergr.chat.schema)
              ((resolve 'dvergr.chat.schema/ensure-full-schema!) conn)
              (catch Exception e
                (tel/log! {:level :warn :id :daemon/schema-install-failed
                           :data {:error (.getMessage e)}}
                          "Could not install chat schema")))
            (ygg/register! dh-sys)
            (tel/log! {:id :daemon/datahike-registered
                       :data {:path db-store-path}}
                      "Registered Datahike system"))
          (catch Exception e
            (tel/log! {:level :warn :id :daemon/datahike-init-failed
                       :error e
                       :data {:error (.getMessage e)}}
                      "Could not register Datahike system"))))
      base-ctx)))

;; ============================================================================
;; Agent Profiles
;; ============================================================================

(defn- load-agent-prompt
  "Load a markdown agent profile from resources/agents/<profile-name>.md.
   Returns the file contents as a string, or nil if not found."
  [profile-name]
  (when-let [resource (io/resource (str "agents/" (name profile-name) ".md"))]
    (slurp resource)))

;; ============================================================================
;; Skills System
;; ============================================================================

(defn- parse-inline-value
  "Coerce a frontmatter inline value: `[a, b, c]` → [\"a\" \"b\" \"c\"];
   otherwise return trimmed string."
  [v]
  (let [t (str/trim v)]
    (if (and (str/starts-with? t "[") (str/ends-with? t "]"))
      (let [inner (str/trim (subs t 1 (dec (count t))))]
        (if (str/blank? inner)
          []
          (mapv str/trim (str/split inner #","))))
      t)))

(defn- parse-skill-frontmatter
  "Parse YAML-like frontmatter from a skill markdown file.
   Returns {:name \"...\" :description \"...\" :requires-tools [...] :content \"...\"}
   Frontmatter must be between --- delimiters at the start of the file."
  [text]
  (if (str/starts-with? text "---\n")
    (let [end-idx (str/index-of text "\n---\n" 4)]
      (if end-idx
        (let [fm-text (subs text 4 end-idx)
              content (subs text (+ end-idx 5))
              ;; Parse simple key: value and key:\n  - item lines
              lines   (str/split-lines fm-text)
              {:keys [current-key result]}
              (reduce
                (fn [{:keys [current-key result]} line]
                  (cond
                    ;; List item
                    (str/starts-with? line "  - ")
                    {:current-key current-key
                     :result      (update result current-key (fnil conj []) (str/trim (subs line 4)))}
                    ;; Key: value pair (inline) — supports `[a, b, c]` lists.
                    (re-matches #"^[a-zA-Z_-]+: .+" line)
                    (let [[k v] (str/split line #": " 2)
                          kw    (keyword (str/replace k "_" "-"))]
                      {:current-key kw
                       :result      (assoc result kw (parse-inline-value v))})
                    ;; Key: (list follows)
                    (re-matches #"^[a-zA-Z_-]+:$" line)
                    {:current-key (keyword (str/replace (subs line 0 (dec (count line))) "_" "-"))
                     :result      result}
                    :else {:current-key current-key :result result}))
                {:current-key nil :result {}}
                lines)]
          (assoc result :content (str/trim content)))
        {:content (str/trim text)}))
    {:content (str/trim text)}))

(defn load-skills
  "Load all skills from resources/skills/*.md that are eligible for the given tool set.
   A skill is eligible if all its required tools are present in available-tool-names.

   Returns a vector of skill content strings (the markdown after frontmatter).
   Logs a warning for any skills that are not eligible."
  [available-tool-names]
  (let [skills-dir (io/resource "skills")]
    (if-not skills-dir
      []
      (let [skills-path (java.io.File. (.toURI skills-dir))
            skill-files (when (.isDirectory skills-path)
                          (.listFiles skills-path
                            (reify java.io.FilenameFilter
                              (accept [_ _ name] (str/ends-with? name ".md")))))]
        (if-not (seq skill-files)
          []
          (->> skill-files
               (map (fn [f]
                      (let [text     (slurp f)
                            parsed   (parse-skill-frontmatter text)
                            requires (set (or (:requires-tools parsed) []))]
                        (assoc parsed :file (.getName f) :requires-set requires))))
               (filter (fn [{:keys [requires-set file]}]
                          (let [missing (clojure.set/difference requires-set (set available-tool-names))]
                            (when (seq missing)
                              (tel/log! {:id :skills/ineligible
                                         :data {:file file :missing missing}} "Skill not loaded — tools unavailable"))
                            (empty? missing))))
               (mapv :content)))))))

(defn- inject-skills
  "Append eligible skill contents to a system prompt.
   Only adds skills section if there are eligible skills."
  [system-prompt tool-names]
  (let [skill-contents (load-skills tool-names)]
    (if (seq skill-contents)
      (str system-prompt
           "\n\n---\n\n## Skills\n\n"
           (str/join "\n\n---\n\n" skill-contents))
      system-prompt)))

;; ============================================================================
;; Tool Sets
;; ============================================================================

(defn- chat-tools
  "Return tool map for chat mode: knowledge graph + URL ingestion + cheap LLM.

   Web search isn't here as a tool — agents reach it via
   (intake.web/search …) under clojure_eval."
  []
  (select-keys @tools/registry
               ["knowledge_search" "knowledge_add"
                "web_fetch" "youtube_transcript" "tweet_lookup"
                "llm_call" "fulltext_search"]))

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
                "mail_sync" "mail_inbox" "mail_read" "mail_search"
                "propose_change" "fulltext_search"]))

(defn- intake-tools
  "Return tool map for generic intake agents: all safe tools including intake sources.
   Excludes code-execution tools (clojure_eval, shell, file ops) since generic
   intake agents only need to read external sources and write to the knowledge base."
  []
  (let [code-tools #{"clojure_eval" "glob" "grep" "read_file" "write_file"
                     "edit_file" "run_tests" "bash"}]
    (into {} (remove (fn [[k _]] (or (excluded-from-daemon k) (code-tools k))))
          @tools/registry)))

;; ============================================================================
;; Shared Helpers
;; ============================================================================

(defn- tool-called-in-ctx?
  "Return true if the named tool was called at least once in the given ChatContext.
   Checks :message/tool-uses (namespace-qualified as stored by chat/agent.clj)."
  [chat-ctx tool-name]
  (some (fn [msg]
          ;; Tool uses are stored as :message/tool-uses with :tool-use/name
          (when-let [uses (or (:message/tool-uses msg) (:tool-uses msg))]
            (some #(= tool-name (or (:tool-use/name %) (:name %))) uses)))
        (chat-ctx/get-messages chat-ctx)))

(defn- any-tool-called?
  "Return true if any tool was called at least once in the given ChatContext."
  [chat-ctx]
  (some (fn [msg]
          (seq (or (:message/tool-uses msg) (:tool-uses msg))))
        (chat-ctx/get-messages chat-ctx)))

(defn- extract-last-assistant
  "Extract the last assistant message with text content from a ChatContext.
   Skips tool-call-only messages (no text) to find the most recent
   message with actual content."
  [chat-ctx]
  (let [msgs (chat-ctx/get-messages chat-ctx)]
    (or (->> msgs
             reverse
             (filter #(= :assistant (or (:role %) (:message/role %))))
             (some (fn [m]
                     (let [c (or (:content m) (:message/content m))]
                       (when (and c (not (str/blank? c)))
                         c)))))
        "")))

(defn- resolve-tools-fn
  "Pick the tool set based on agent tags."
  [agent-config]
  (let [tags (set (or (:tags agent-config) #{}))]
    (cond
      (contains? tags :secretary) chat-tools
      (contains? tags :intake)    huginn-tools
      :else                       safe-tools)))

(defn- resolve-chat-context
  "Return a ChatContext for this invocation.
   - If the task carries a :chat-ctx (from a session), use it — preserves conversation history.
   - Otherwise, create a fresh ephemeral context (tick sweeps, direct invocations)."
  [task agent-config ctx]
  (or (:chat-ctx task)
      (binding [rtc/*execution-context* ctx]
        (chat-ctx/create-chat-context
          {:title          (str (name (or (:id agent-config) :agent)) "-" (System/currentTimeMillis))
           :budget-dollars (or (:budget-dollars agent-config) 1.0)}))))

(defn- format-task-message
  "Convert the task envelope to a user message string based on :type."
  [task task-type agent-config]
  (let [intake? (contains? (set (or (:tags agent-config) #{})) :intake)]
    (cond
      (= :tick task-type)
      (str "Run your intake sweep now. Work through all steps in your profile.\n\n"
           "IMPORTANT: You MUST call `knowledge_add` at least once before ending. "
           "Always end with a knowledge_add sweep summary, even if nothing was found. "
           "Do not write a text summary until after knowledge_add has been called.")

      (= :source task-type)
      (let [{:keys [name msg]} task
            {:keys [source preview room-slug]} msg]
        (str "New message in room " (or room-slug name) " from " (or source "unknown") ":\n\n"
             "\"" preview "\"\n\n"
             "Decide if this message is relevant or actionable for your role. "
             "If it's just casual chat or a greeting, you may briefly acknowledge it or skip. "
             "If it's substantive — a question, request, or signal — search your knowledge base, "
             "reflect thoughtfully, and store findings via knowledge_add if warranted. "
             "Not every message needs a knowledge_add entry."))

      ;; Interactive request to intake agent — add tool-use instructions
      (and intake? (nil? task-type) (map? task) (:content task))
      (str (:content task)
           "\n\n---\n"
           "This is an interactive research request. Do the research RIGHT NOW — "
           "do not just acknowledge or describe what you would do. Run "
           "clojure_eval with the intake namespaces ((intake.web/search …), "
           "(intake.web/fetch …), (intake.hn/search …), etc.) plus knowledge_search "
           "and produce a detailed, well-sourced answer with URLs.")

      (string? task) task
      :else (or (:content task) ""))))

;; NOTE: run-turn-loop and handle-secretary-command are inlined directly into
;; the spin body in make-think-fn because `await` requires CPS transformation
;; which doesn't cross function boundaries.

;; Forward declare for fire-evaluator! and make-on-message (both reference
;; the spindel-bridging turn runner, defined just below the evaluator).
(declare run-turn-async!)

;; ============================================================================
;; Evaluator (Self-Improvement)
;; ============================================================================

(defn- fire-evaluator!
  "Fire a background evaluator that reads the sweep summary and optionally
   improves the agent's profile via update_agent_profile.

   Evaluator runs as a fire-and-forget spin — it does not block the next sweep.

   Configuration (in agent-config):
     :evaluator-model    — model for the evaluator (defaults to agent's model)
     :evaluator-provider — provider for the evaluator (defaults to agent's provider)"
  [agent sweep-summary base-prompt agent-config exec-ctx]
  (let [eval-prompt (str "You are an evaluator for the " (name (:id agent)) " intake agent.\n\n"
                         "## Current Agent Profile\n\n" base-prompt "\n\n"
                         "## Latest Sweep Summary\n\n" sweep-summary "\n\n"
                         "## Evaluation Criteria\n\n"
                         "Did the sweep:\n"
                         "1. Call knowledge_add with a sweep summary entry?\n"
                         "2. Find and store at least one relevant signal?\n"
                         "3. Work efficiently (not too many redundant tool calls)?\n"
                         "4. Check prior knowledge before searching?\n\n"
                         "If the profile needs improvement, call update_agent_profile with the full "
                         "updated markdown content. Be conservative — only improve what would "
                         "clearly help. If the profile is performing well, output a brief note only.")]
    (binding [rtc/*execution-context* exec-ctx]
      (let [eval-ctx  (chat-ctx/create-chat-context
                        {:title (str "eval-" (name (:id agent)) "-" (System/currentTimeMillis))
                         :budget-dollars 0.25})
            tools-map (select-keys @tools/registry ["update_agent_profile" "knowledge_search"])
            sci-ctx   (sandbox/fork-for-session exec-ctx)
            _         (sandbox/setup-agent-namespaces! sci-ctx exec-ctx)
            tool-ctx  (tools/make-context
                        {:cwd (System/getProperty "user.dir")
                         :sci-ctx sci-ctx
                         :tools tools-map
                         :chat-ctx eval-ctx
                         :db-conn (:db-conn eval-ctx)
                         :isolation :sci
                         :execution-ctx exec-ctx})
            opts      {:provider (or (:evaluator-provider agent-config)
                                     (:provider agent-config) :fireworks)
                       :model    (or (:evaluator-model agent-config)
                                     (:model agent-config)
                                     "accounts/fireworks/models/minimax-m2p5")
                       :tools    tools-map
                       :tool-ctx tool-ctx}]
        (chat-ctx/add-message! eval-ctx {:role :system
                                          :content "You are a concise agent evaluator. Evaluate performance and optionally improve the profile."})
        (chat-ctx/add-message! eval-ctx {:role :user :content eval-prompt})
        (let [eval-spin
              (spin
                (loop [turn 0]
                  (if (>= turn 3)
                    (tel/log! {:id :agent/eval-max-turns :data {:agent-id (:id agent)}} "Evaluator max turns")
                    (let [r (await (run-turn-async! eval-ctx opts exec-ctx))]
                      (if (or (= :error (:status r)) (= :complete (:result r)))
                        (tel/log! {:level :info :id :agent/eval-done
                                   :data {:agent-id (:id agent)
                                          :result (extract-last-assistant eval-ctx)}}
                                  "Evaluator complete")
                        (recur (inc turn)))))))]
          (eval-spin
            (fn [_] nil)
            (fn [e] (tel/log! {:level :warn :id :agent/eval-error
                                :data {:agent-id (:id agent) :error (str e)}}
                              "Evaluator error"))))))))

;; ============================================================================
;; Turn Bridge — chat-agent/run-agent-turn! into a spindel Deferred
;;
;; Replaces dvergr.agent.turn/run-turn-async. The blocking LLM + tool work
;; runs on a future; deliver! routes the result back into the spin chain.
;; ============================================================================

(defn- run-turn-async!
  "Run one chat-agent turn off the spindel executor; return a Deferred that
   awaits to the turn result map: {:status :ok :result <:continue|:complete>}
   or {:status :error :error e :message m}."
  [chat-ctx opts execution-ctx]
  (let [d (binding [rtc/*execution-context* execution-ctx]
            (sync/deferred))]
    (future
      (binding [rtc/*execution-context* execution-ctx]
        (try
          (sync/deliver! d {:status :ok
                            :result (chat-agent/run-agent-turn! chat-ctx opts)})
          (catch Exception e
            (sync/deliver! d {:status :error :error e
                              :message (.getMessage e)})))))
    d))

;; ============================================================================
;; Envelope Adapter
;;
;; The legacy think-fn took a task map; the new on-message takes a discourse
;; envelope. `task-from-envelope` collapses both into the legacy task shape
;; so format-task-message, resolve-chat-context, and the secretary command
;; pre-dispatch keep working without per-call surgery.
;; ============================================================================

(defn- task-from-envelope
  "Convert a discourse envelope into the legacy task shape:
     Message       → {:content str :chat-id N :user-info U :chat-ctx C}
                     (carried via the Message's :metadata)
     {:type :tick} → {:type :tick}
     {:type :source :name K :msg E}
                   → {:type :source :name K :msg E}"
  [envelope]
  (cond
    (= :tick (:type envelope))
    {:type :tick}

    (= :source (:type envelope))
    {:type :source :name (:name envelope) :msg (:msg envelope)}

    :else
    (merge {:content (:content envelope)} (:metadata envelope))))

;; ============================================================================
;; Unified on-message
;; ============================================================================

(defn- make-on-message
  "Create a dvergr.discourse `on-message` handler for a daemon-managed agent.

   The participant receives:
     - inbox Messages from `dispatch!` (Telegram dialogue) or scheduler
     - {:type :tick} envelopes from `d/with-cadence` (intake sweeps)
     - {:type :source ...} envelopes from `d/with-sources` (room subscriptions)

   It returns a discourse reply-spec `{:to <from-id> :content text}` or
   nil for no reply. The handler's behaviour is tag-driven, matching the
   legacy `make-think-fn`:
     :secretary — chat mode with command pre-dispatch (status/merge/discard/task)
     :intake    — sweep mode with knowledge_add / tool-use enforcement
                  + optional self-evaluator
     (neither)  — generic agent with the standard turn loop

   Per-session chat-ctx (different (chat-id, agent-id) → different history)
   travels through the Message's :metadata; we deliberately do NOT use
   `dvergr.discourse.llm/llm-agent` here because llm-agent assumes a
   single per-participant chat-ctx, which is the wrong model for a daemon
   serving multiple Telegram chats. The /task delegation pathway DOES
   use llm-agent + dvergr.proposals (one-off worker, no shared session)."
  [daemon agent-config]
  (let [base-prompt  (or (:system-prompt agent-config)
                         (load-agent-prompt (or (:profile agent-config)
                                                (:id agent-config)))
                         "You are a helpful AI assistant.")
        tools-fn     (resolve-tools-fn agent-config)
        tags         (set (or (:tags agent-config) #{}))
        secretary?   (contains? tags :secretary)
        intake?      (contains? tags :intake)
        sweep-count  (atom 0)
        eval-every-n (:evaluator-every-n agent-config)
        ;; Worker spec for secretary /task delegation — a fresh llm-agent
        ;; Participant is built per /task call (so each fork gets its own
        ;; clean chat-ctx). The prompt + model are captured here.
        worker-prompt (when secretary?
                        (or (:worker-system-prompt agent-config)
                            (load-agent-prompt
                              (or (:worker-profile agent-config) :worker))
                            "You are a capable AI worker. Complete the given task thoroughly."))]
    (fn [participant envelope]
      (let [aid       (:id participant)
            ctx       (:execution-ctx daemon)
            task      (task-from-envelope envelope)
            task-type (:type task)
            from-id   (when (instance? dvergr.discourse.Message envelope)
                        (:from envelope))
            ;; Build a reply-spec; tolerate missing from-id (tick/source-only
            ;; participants have no individual sender to reply to — the
            ;; response sinks read :from on the Message we emit, so :to
            ;; defaults to :_system for fan-out).
            ->reply   (fn [text]
                        (when (and (string? text) (not (str/blank? text)))
                          {:to (or from-id system-id) :content text}))]
        (binding [rtc/*execution-context* ctx]
          (spin
            (try
              ;; ---- Secretary command pre-dispatch ----
              (let [secretary-cmd
                    (when secretary?
                      (let [text    (get task :content "")
                            trimmed (str/lower-case (str/trim text))
                            chat-id (:chat-id task)
                            cctx    (:chat-ctx task)]
                        (cond
                          (or (= trimmed "status") (= trimmed "/status"))
                          (or (and chat-id (sessions/describe-pending chat-id aid))
                              "No pending work.")

                          (or (= trimmed "merge") (= trimmed "*merge*"))
                          (if-let [pending (and chat-id
                                                (sessions/get-pending-fork chat-id aid))]
                            (let [conn   (some-> (rtc/get-state [:external-refs "dvergr-chat-db"])
                                                 :conn)
                                  room   (:discourse-room daemon)
                                  result (proposals/accept-proposal! room conn (:proposal-id pending))]
                              (sessions/clear-pending-fork! chat-id aid)
                              (if (= :accepted result)
                                "Merged! Changes have been applied to the main context."
                                (str "Merge failed: " (or (:message result) (pr-str result)))))
                            "No pending work to merge.")

                          (or (= trimmed "discard") (= trimmed "*discard*"))
                          (if-let [pending (and chat-id
                                                (sessions/get-pending-fork chat-id aid))]
                            (let [conn   (some-> (rtc/get-state [:external-refs "dvergr-chat-db"])
                                                 :conn)
                                  room   (:discourse-room daemon)
                                  result (proposals/reject-proposal! room conn (:proposal-id pending))]
                              (sessions/clear-pending-fork! chat-id aid)
                              (if (= :rejected result)
                                "Discarded. Work has been thrown away."
                                (str "Discard failed: " (or (:message result) (pr-str result)))))
                            "No pending work to discard.")

                          (str/starts-with? trimmed "/task ")
                          (let [task-text (str/trim (subs text (count "/task ")))
                                conn      (some-> (rtc/get-state [:external-refs "dvergr-chat-db"])
                                                  :conn)
                                room      (:discourse-room daemon)]
                            (when chat-id
                              (doseq [sink @(:response-sinks daemon)]
                                (try (sink aid "Working on your task...")
                                     (catch Exception _))))
                            (if-not (and conn room)
                              "Cannot delegate task: missing db-conn or discourse room."
                              (let [worker   (llm/llm-agent
                                               {:id     (keyword (str (name aid) "-worker"))
                                                :spec   {:provider      (or (:provider agent-config) :fireworks)
                                                         :model         (or (:model agent-config)
                                                                            "accounts/fireworks/models/minimax-m2p5")
                                                         :system-prompt worker-prompt}
                                                :budget {:dollars (or (:budget-dollars agent-config) 0.50)}
                                                :ctx    ctx})
                                    proposal (await (proposals/propose!
                                                      {:room   room
                                                       :worker worker
                                                       :goal   task-text
                                                       :conn   conn
                                                       :budget-dollars (or (:budget-dollars agent-config) 0.50)}))
                                    summary  (str (:proposal/summary proposal))]
                                (when chat-id
                                  (sessions/register-pending-fork!
                                    chat-id aid (:proposal/id proposal) summary))
                                (str summary
                                     (when chat-id
                                       "\n\n---\nWork is ready in an isolated context. Reply *merge* to apply or *discard* to cancel.")))))

                          :else nil)))]
                (if secretary-cmd
                  (->reply secretary-cmd)
                  ;; ---- Standard LLM path ----
                  (let [chat-ctx  (resolve-chat-context task agent-config ctx)
                        user-msg  (format-task-message task task-type agent-config)
                        sci-ctx   (sandbox/fork-for-session ctx)
                        _         (sandbox/setup-agent-namespaces! sci-ctx ctx)
                        tools-map (tools-fn)
                        system-prompt (inject-skills base-prompt (keys tools-map))
                        tool-ctx  (tools/make-context
                                    {:cwd (System/getProperty "user.dir")
                                     :sci-ctx sci-ctx :tools tools-map
                                     :chat-ctx chat-ctx :db-conn (:db-conn chat-ctx)
                                     :isolation :sci :execution-ctx ctx})
                        opts      {:provider (or (:provider agent-config) :fireworks)
                                   :model    (or (:model agent-config)
                                                 "accounts/fireworks/models/minimax-m2p5")
                                   :tools    tools-map
                                   :tool-ctx tool-ctx}
                        max-turns (or (:max-turns agent-config)
                                      (cond secretary? 3 intake? 20 :else 10))
                        ka-required (and intake? (= :tick task-type))]
                    ;; System prompt on first message
                    (when (empty? (chat-ctx/get-messages chat-ctx))
                      (chat-ctx/add-message! chat-ctx
                                             {:role :system :content system-prompt}))
                    ;; User message (with identity prefix for secretary group chats)
                    (when-not (str/blank? user-msg)
                      (let [prefix (when (and secretary? (:user-info task))
                                     (when-let [uname (or (:first_name (:user-info task))
                                                          (:username (:user-info task)))]
                                       (str "[" uname "] ")))]
                        (chat-ctx/add-message! chat-ctx
                                               {:role :user
                                                :content (str prefix user-msg)})))
                    ;; ---- Turn loop (inlined — await needs CPS) ----
                    (loop [turn 0]
                      (cond
                        (>= turn max-turns)
                        (let [summary (extract-last-assistant chat-ctx)]
                          (when (and intake? (= :tick task-type))
                            (let [n (swap! sweep-count inc)]
                              (tel/log! {:level :info :id :agent/sweep-done
                                         :data {:agent-id aid :turns turn}}
                                        "Sweep complete (max-turns)")
                              (when (and eval-every-n (pos? n)
                                         (zero? (mod n eval-every-n)))
                                (fire-evaluator! participant summary
                                                 base-prompt agent-config ctx))))
                          (->reply summary))

                        (not (chat-ctx/check-budget! chat-ctx))
                        (->reply "Budget limit reached.")

                        :else
                        (do
                          (tel/log! {:level :info :id :agent/turn
                                     :data {:agent-id aid :turn turn}}
                                    "Turn starting")
                          (let [r (await (run-turn-async! chat-ctx opts ctx))]
                            (tel/log! {:level :info :id :agent/turn-done
                                       :data {:agent-id aid :turn turn
                                              :result (:result r) :status (:status r)}}
                                      "Turn done")
                            (if (or (= :error (:status r)) (= :complete (:result r)))
                              (cond
                                ;; knowledge_add enforcement for tick sweeps
                                (and ka-required
                                     (= :complete (:result r))
                                     (not (tool-called-in-ctx? chat-ctx "knowledge_add"))
                                     (< turn (dec max-turns)))
                                (do (tel/log! {:level :info
                                               :id :agent/knowledge-add-reminder
                                               :data {:agent-id aid :turn turn}}
                                              "knowledge_add not called — injecting reminder")
                                    (chat-ctx/add-message! chat-ctx
                                      {:role :user
                                       :content (str "Before ending: you haven't called knowledge_add yet. "
                                                     "Call it now with the sweep summary (required). "
                                                     "Even if nothing was found, call: "
                                                     "knowledge_add {:title \"Sweep "
                                                     (java.time.Instant/now) "\""
                                                     " :source \"internal\" :summary \"Brief sweep summary here\"}")})
                                    (recur (inc turn)))

                                ;; Tool-use enforcement for interactive intake requests
                                (and intake? (not ka-required)
                                     (= :complete (:result r))
                                     (zero? turn)
                                     (not (any-tool-called? chat-ctx))
                                     (< turn (dec max-turns)))
                                (do (tel/log! {:level :info
                                               :id :agent/tool-use-reminder
                                               :data {:agent-id aid :turn turn}}
                                              "No tools called on interactive request — injecting reminder")
                                    (chat-ctx/add-message! chat-ctx
                                      {:role :user
                                       :content (str "You did NOT use any tools. This is a research request — "
                                                     "run clojure_eval with (intake.web/search …), "
                                                     "(intake.web/fetch …), or call knowledge_search to actually "
                                                     "research the topic. Do not just describe what you would do — do it now.")})
                                    (recur (inc turn)))

                                ;; Done — extract summary, maybe fire evaluator
                                :else
                                (let [summary (extract-last-assistant chat-ctx)]
                                  (when (and intake? (= :tick task-type))
                                    (let [n (swap! sweep-count inc)]
                                      (tel/log! {:level :info :id :agent/sweep-done
                                                 :data {:agent-id aid :turns turn
                                                        :knowledge-add? (tool-called-in-ctx? chat-ctx "knowledge_add")}}
                                                "Sweep complete")
                                      (when (and eval-every-n (pos? n)
                                                 (zero? (mod n eval-every-n)))
                                        (fire-evaluator! participant summary
                                                         base-prompt agent-config ctx))))
                                  (->reply summary)))
                              (recur (inc turn))))))))))
              (catch Exception e
                (tel/log! {:level :error :id :agent/think-error :error e
                           :data {:agent-id aid}} "Think error")
                (->reply (str "Sorry, I hit an error: " (.getMessage e)))))))))))

;; ============================================================================
;; Response Sinks
;; ============================================================================

(defn register-response-sink!
  "Register a response sink function. Called as (sink-fn agent-id text)
   whenever an agent produces output. Returns the daemon."
  [daemon sink-fn]
  (swap! (:response-sinks daemon) conj sink-fn)
  daemon)

;; ============================================================================
;; System Receiver + Watcher
;;
;; Agent replies addressed to `:_system` flow through one drain spin that
;; fans them out to all registered response sinks. Replaces the legacy
;; per-agent outbox-watcher (one future per agent) with a single drain.
;; ============================================================================

(defn- system-receiver
  "Participant that drains every agent reply addressed to `:_system` and
   fans the text to every registered response sink. The fan-out lives
   directly inside `on-message` because dvergr.discourse/join already
   spawns a single drain spin per participant — adding a second
   watcher-spin on the same inbox-mbx would race for each message and
   only ~half would reach the sinks.

   Returns `(spin nil)` so the participant emits no reply of its own."
  [daemon]
  (d/participant
    {:id system-id
     :on-message
     (fn [_p msg]
       (let [agent-id (:from msg)
             text     (:content msg)]
         (doseq [sink @(:response-sinks daemon)]
           (try (sink agent-id text)
                (catch Exception e
                  (tel/log! {:level :error :id :daemon/sink-error
                             :data {:agent-id agent-id
                                    :error (.getMessage e)}}
                            "Sink error")))))
       (spin nil))}))

;; ============================================================================
;; Agent Creation
;; ============================================================================

(defn create-agent!
  "Build a discourse Participant for this agent, apply the enrichment
   chain + drivers, and join it into the daemon's discourse room.

   Security defaults applied unless explicitly overridden:
     :isolation     - :sci (sandboxed Clojure eval, no native JVM access)
     :tools         - safe-tools (excludes shell, run_tests, telegram_* tools)
     :budget-dollars - 0.25 per task
     :max-turns     - 10 per task

   To grant extra power, pass explicit overrides in agent-config:
     :isolation :native       — full JVM (trusted agents only, never Telegram)
     :tools (tools/all-tools) — unrestricted (trusted agents only)

   Returns the registered Participant."
  [daemon agent-config]
  (let [exec-ctx        (:execution-ctx daemon)
        room            (:discourse-room daemon)
        agent-id        (or (:id agent-config) (keyword (gensym "agent-")))
        profile-name    (or (:profile agent-config) agent-id)
        profile-prompt  (load-agent-prompt profile-name)
        ;; Apply security defaults; explicit config values override these
        safe-config     (merge {:isolation      :sci
                                :budget-dollars 0.25
                                :max-turns      10}
                               agent-config
                               {:id agent-id
                                :tools (or (:tools agent-config) (safe-tools))}
                               (when (and profile-prompt
                                          (not (:system-prompt agent-config)))
                                 {:system-prompt profile-prompt}))
        room-slugs       (:rooms safe-config)
        sources          (when (and (seq room-slugs) (bus/initialized?))
                           (vec (keep (fn [slug]
                                        (when-let [src (bus/subscribe-room (str slug))]
                                          {:name   (keyword "room" (str slug))
                                           :source src}))
                                      room-slugs)))
        intel-room-slugs (remove #{"boardroom"} room-slugs)
        ;; Build the base Participant; on-message captures the agent's
        ;; behaviour (tag-driven), then wrappers add room-aware extras.
        base-p           (d/participant
                           {:id agent-id
                            :ctx exec-ctx
                            :on-message (make-on-message daemon safe-config)})
        conn-of-ref      (fn []
                           (some-> (rtc/get-state [:external-refs "dvergr-chat-db"])
                                   :conn))
        conn             (binding [rtc/*execution-context* exec-ctx]
                           (conn-of-ref))
        room-chat-ids    (when (and conn (seq room-slugs))
                           (->> room-slugs
                                (keep (fn [slug]
                                        (when-let [r (rooms/get-room-by-slug
                                                       conn (str slug))]
                                          (:chat/id r))))
                                vec))
        participant      (cond-> base-p
                           ;; Drop :source events the agent itself authored
                           (seq room-slugs)
                           (enr/with-self-filter)
                           ;; Prepend room history to incoming Messages
                           (and conn (seq room-chat-ids))
                           (enr/with-room-context
                             {:conn conn :room-ids room-chat-ids})
                           ;; Treat [SKIP] / blank replies as no-reply
                           (seq room-slugs)
                           (enr/with-silence)
                           ;; Post replies back to the source room for
                           ;; non-boardroom intel rooms (boardroom uses
                           ;; the global sink instead).
                           (and conn (seq intel-room-slugs))
                           (enr/with-intel-room-routing {:conn conn})
                           ;; Attach drivers
                           (seq sources)        (d/with-sources sources)
                           (:interval-ms safe-config)
                           (d/with-cadence (:interval-ms safe-config)))]
    (binding [rtc/*execution-context* exec-ctx]
      (d/join room participant)
      (registry/register! agent-id participant
                          :tags        (or (:tags agent-config) #{})
                          :description (or (:description agent-config) "")
                          :config      safe-config)
      (registry/update-status! agent-id :running))
    participant))

(defn stop-agent!
  "Leave the participant from the daemon room and unregister it. The
   participant's spin (and any driver pumps) continue running on the
   shared executor — discourse has no per-spin cancel today — but no
   further messages are routed to it."
  [daemon agent-id]
  (binding [rtc/*execution-context* (:execution-ctx daemon)]
    (when-let [room (:discourse-room daemon)]
      (d/leave room agent-id))
    (registry/unregister! agent-id)
    :stopped))

;; ============================================================================
;; Message Dispatch
;; ============================================================================

(defn- parse-agent-command
  "Parse agent addressing and commands from message text.

   Returns one of:
   - {:command :list-agents}                        for /agents
   - {:agent-id :worker :text \"research X\"}       for /worker research X
   - nil                                            for plain text (use session default)"
  [text]
  (let [trimmed (str/trim text)]
    (cond
      ;; /agents command
      (= (str/lower-case trimmed) "/agents")
      {:command :list-agents}

      ;; /agent-name text — slash-addressed agent
      ;; Allow optional punctuation (comma, colon) after agent name:
      ;;   /sentinel research X     — standard
      ;;   /sentinel, research X    — natural language
      ;;   /sentinel: research X    — colon style
      (re-matches #"^/([a-zA-Z_][a-zA-Z0-9_-]*)[,;:!]?\s+(.+)" trimmed)
      (let [[_ agent-name rest-text] (re-matches #"^/([a-zA-Z_][a-zA-Z0-9_-]*)[,;:!]?\s+([\s\S]+)" trimmed)]
        ;; Don't match known secretary commands (/status, /merge, /discard, /task)
        (when-not (#{"status" "merge" "discard" "task"} (str/lower-case agent-name))
          {:agent-id (keyword agent-name)
           :text rest-text}))

      ;; Plain text — no agent addressing
      :else nil)))

(defn- format-agent-list
  "Format the agent registry as a human-readable list for Telegram."
  []
  (let [agents (registry/list-agents)]
    (if (seq agents)
      (str "Registered agents:\n\n"
           (str/join "\n"
             (map (fn [{:keys [id status description tags]}]
                    (str "- *" (name id) "* "
                         (when (seq description) (str "— " description " "))
                         "[" (name (or status :unknown)) "]"
                         (when (seq tags)
                           (str " " (str/join " " (map #(str "#" (name %)) tags))))))
                  agents))
           "\n\nAddress an agent: `/worker do something`")
      "No agents registered.")))

(defn dispatch!
  "Route an incoming Telegram message to the appropriate agent.

   Supports agent addressing:
   - /agents — list available agents
   - /worker task text — route to specific agent
   - plain text — route to session's current agent (secretary chat mode)
   - /task description — secretary delegates to worker in forked context

   Args:
     daemon - Daemon record
     msg    - Normalized Telegram message from channel"
  [daemon msg]
  ;; Bind the daemon's execution context — telegram bridge / web handlers
  ;; / tests may call dispatch! from threads that don't have it bound, and
  ;; ctx-scoped state (sessions, registry, stats, rooms-bus subscribers)
  ;; must land on the daemon's ctx for the routing to see/update it.
  (binding [rtc/*execution-context* (or (:execution-ctx daemon)
                                        rtc/*execution-context*)]
  (let [chat-id (:chat-id msg)
        text (:text msg)
        user-info (:from msg)
        default-agent-id (or (get-in (:config daemon) [:default-agent]) :var)]

    (when (and chat-id text (not (str/blank? text)))
      (if-not (or (keyword? chat-id)           ; local TUI/internal sessions are always trusted
                  (allowlist/allowed? user-info))
        ;; Reject unauthorized users before session creation
        (do
          (tel/log! {:level :warn :id :daemon/unauthorized
                     :data {:user-id (:id user-info) :username (:username user-info)}}
                    "Rejected unauthorized user")
          (when-let [token (get-in (:config daemon) [:telegram :token])]
            (sessions/send-response! token chat-id
              "Access denied. You are not authorized to use this bot.")))
        ;; Authorized — proceed with dispatch
      (let [parsed (parse-agent-command text)]
        (cond
          ;; /agents command — list agents directly
          (= :list-agents (:command parsed))
          (doseq [sink @(:response-sinks daemon)]
            (try (sink default-agent-id (format-agent-list))
                 (catch Exception _)))

          ;; Agent addressed — route to specific agent with its own session
          (:agent-id parsed)
          (if (registry/get-agent (:agent-id parsed))
            (let [target-id (:agent-id parsed)
                  session (sessions/get-or-create-session! chat-id target-id user-info)]
              (d/post! (:discourse-room daemon)
                       (d/message system-id target-id (:text parsed) nil
                                  {:chat-id   chat-id
                                   :user-info user-info
                                   :chat-ctx  (:chat-ctx session)})))
            ;; Unknown agent — send error
            (doseq [sink @(:response-sinks daemon)]
              (try (sink default-agent-id
                         (str "Unknown agent: " (name (:agent-id parsed))
                              "\n\nUse /agents to see available agents."))
                   (catch Exception _))))

          ;; Plain text — route to default agent (var) with its own session
          :else
          (let [session (sessions/get-or-create-session! chat-id default-agent-id user-info)]
            (when (registry/get-agent default-agent-id)
              (d/post! (:discourse-room daemon)
                       (d/message system-id default-agent-id text nil
                                  {:chat-id   chat-id
                                   :user-info user-info
                                   :chat-ctx  (:chat-ctx session)})))))))))))

;; ============================================================================
;; Daemon Lifecycle
;; ============================================================================

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

  ;; Create execution context, daemon-wide discourse room, and the
  ;; :_system receiver that drains all agent replies into the sink fan-out.
  (let [exec-ctx       (create-shared-context :with-git? true
                                              :with-datahike? true)
        discourse-room (d/room :daemon exec-ctx)
        status-a       (atom :starting)
        daemon         (->Daemon config exec-ctx discourse-room
                                 nil nil (atom nil) (atom []) status-a)]
    (binding [rtc/*execution-context* exec-ctx]
      (d/join discourse-room (system-receiver daemon)))

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

    ;; Initialize search index (before agents so indexing works during sweeps)
    (search/init! "data/search-index")

    ;; Initialize stats, wiki, rooms, and room bus with the shared datahike connection
    ;; Bus must be initialized BEFORE agents so subscribe-room works during create-agent!
    (binding [rtc/*execution-context* exec-ctx]
      (bus/init! exec-ctx)
      (when-let [sys (rtc/get-state [:external-refs "dvergr-chat-db"])]
        (stats/init! (:conn sys))
        (rooms/init! (:conn sys))

        ;; Auto-create boardroom room (idempotent)
        (when-not (rooms/get-room-by-slug (:conn sys) "boardroom")
          (rooms/create-room! (:conn sys)
            {:title "Boardroom"
             :slug "boardroom"
             :type :internal})
          (tel/log! {:id :daemon/boardroom-created} "Created boardroom room"))

        ;; Auto-create browser-feed room for extension page captures (idempotent)
        (when-not (rooms/get-room-by-slug (:conn sys) "browser-feed")
          (rooms/create-room! (:conn sys)
            {:title "Browser Feed"
             :slug "browser-feed"
             :type :internal})
          (tel/log! {:id :daemon/browser-feed-created} "Created browser-feed room"))

        ;; Auto-create bootstrap rooms from config (idempotent)
        (doseq [{:keys [slug title]} (:bootstrap-rooms config)]
          (when-not (rooms/get-room-by-slug (:conn sys) slug)
            (rooms/create-room! (:conn sys)
              {:title title
               :slug  slug
               :type  :internal})
            (tel/log! {:id :daemon/intel-room-created :data {:slug slug}} "Created intel room")))


        ;; Calendar: install schema, init web module, sync iCal feeds, start dispatcher
        (try
          (cal/install-schema! (:conn sys))
          (tel/log! {:id :daemon/calendar-schema-installed} "Calendar schema installed")
          ;; Start iCal sync loop if configured
          (when-let [feeds (get-in config [:calendar :ical-feeds])]
            (let [sync-interval (get-in config [:calendar :sync-interval-ms] 300000)
                  conn (:conn sys)]
              (spin
                (loop []
                  (await (comb/sleep sync-interval))
                  (doseq [{:keys [url name]} feeds]
                    (try (cal-ical/sync-ical-feed! conn url name)
                         (catch Exception e
                           (tel/log! {:level :warn :id :daemon/ical-sync-error
                                      :data {:url url :error (.getMessage e)}}
                                     "iCal sync error"))))
                  (recur)))))
          ;; Start calendar event dispatcher
          (cal-dispatch/start-dispatcher! (:conn sys) exec-ctx
            (fn [event]
              (when-let [room (rooms/get-room-by-slug (:conn sys) "boardroom")]
                (rooms/post-message! (:conn sys) (:chat/id room)
                  {:content (str (:cal/title event) " starting now"
                                 (when (seq (:cal/participants event))
                                   (str " -- " (str/join ", " (map name (:cal/participants event))))))
                   :role :system
                   :source-user "calendar"}))))
          (catch Exception e
            (tel/log! {:level :warn :id :daemon/calendar-init-error
                       :data {:error (.getMessage e)}} "Calendar init error")))))

    ;; Create configured agents (after bus init so room sources can subscribe)
    (doseq [[agent-id agent-config] (:agents config)]
      (tel/log! {:id :daemon/create-agent :data {:agent-id agent-id}} "Creating agent")
      (create-agent! daemon (assoc agent-config :id agent-id)))

    ;; Boardroom response sink — posts agent output to the internal boardroom room
    (let [boardroom-agents (into #{}
                             (for [[id cfg] (:agents config)
                                   :when (some #{"boardroom"} (or (:rooms cfg) []))]
                               id))]
      (when (seq boardroom-agents)
        (register-response-sink! daemon
          (fn [agent-id text]
            (when (and (contains? boardroom-agents agent-id)
                       (string? text)
                       (> (count (str/trim text)) 30)
                       (not (str/starts-with? (str/trim text) "Budget limit"))
                       (not (str/starts-with? (str/trim text) "[SKIP]")))
              (try
                (binding [rtc/*execution-context* exec-ctx]
                  (when-let [sys (rtc/get-state [:external-refs "dvergr-chat-db"])]
                    (when-let [room (rooms/get-room-by-slug (:conn sys) "boardroom")]
                      (rooms/post-message! (:conn sys) (:chat/id room)
                        {:content text
                         :role :assistant
                         :source-user (name agent-id)
                         :source-agent-id (name agent-id)}))))
                (catch Exception e
                  (tel/log! {:level :warn :id :daemon/boardroom-sink-error
                             :data {:agent-id agent-id :error (.getMessage e)}}
                            "Boardroom sink error"))))))))

    ;; Conversation indexing sink — indexes agent output in the search index
    (when (search/initialized?)
      (register-response-sink! daemon
        (fn [agent-id text]
          (when (and (string? text)
                     (> (count (str/trim text)) 80)
                     (not (str/starts-with? (str/trim text) "Budget limit")))
            (try
              (search/index-document!
                {:id        (str "conversation/" (name agent-id) "/" (System/currentTimeMillis))
                 :source    "conversation"
                 :title     (str (name agent-id) " response")
                 :content   text
                 :timestamp (System/currentTimeMillis)
                 :metadata  {:agent-id (name agent-id)}})
              (catch Exception e
                (tel/log! {:level :warn :id :daemon/search-sink-error
                           :data {:agent-id agent-id :error (.getMessage e)}}
                          "Search sink error")))))))

    ;; Connect Telegram if configured
    (let [daemon-with-tg
          (if-let [tg-config (:telegram config)]
            (do
              (tel/log! {:id :daemon/telegram-connecting} "Connecting Telegram bot")
              (let [token (:token tg-config)
                    tg-ch (tg/make-telegram
                            {:token token
                             :poll? true})
                    connected (channels/connect!
                                tg-ch
                                :on-message (fn [msg]
                                              (try
                                                ;; Store in room ONLY if not agent-addressed (avoid huginn echo)
                                                (let [parsed (parse-agent-command (:text msg))]
                                                  (when-not (:agent-id parsed)
                                                    (binding [rtc/*execution-context* exec-ctx]
                                                      (when-let [sys (rtc/get-state [:external-refs "dvergr-chat-db"])]
                                                        (telegram-bridge/store-telegram-message! (:conn sys) msg)))))
                                                (dispatch! daemon msg)
                                                (catch Exception e
                                                  (tel/log! {:level :error :id :daemon/dispatch-error
                                                             :data {:error (.getMessage e)}} "Dispatch error")))))]
                ;; Unified response sink: merges session-based + intake-notify into one.
                ;; Each chat-id gets exactly one message (deduped).
                (let [intake-notify
                      (into {}
                            (for [[id cfg] (:agents config)
                                  :let  [notify-ids (seq (:notify-chat-ids cfg))]
                                  :when (and (contains? (set (:tags cfg)) :intake)
                                             notify-ids)]
                              [id (vec notify-ids)]))]
                  (register-response-sink! daemon
                    (fn [agent-id text]
                      (let [;; Session chat-ids (interactive conversations)
                            session-ids (->> (sessions/list-sessions)
                                            (filter #(= agent-id (:agent-id %)))
                                            (map :chat-id)
                                            (remove keyword?))
                            ;; Intake notification chat-ids (autonomous sweep output)
                            notify-ids (get intake-notify agent-id [])
                            ;; Union + dedup — each chat-id gets exactly one message
                            all-ids    (distinct (concat session-ids notify-ids))
                            session-id-set (set session-ids)]
                        (doseq [chat-id all-ids]
                          (let [has-session? (contains? session-id-set chat-id)
                                trimmed      (str/trim (str text))
                                substantial? (and (string? text)
                                                  (> (count trimmed) 80)
                                                  (not (str/starts-with? trimmed "Budget limit"))
                                                  (not (str/starts-with? trimmed "[SKIP]")))]
                            ;; Send if: session exists for this chat, or (notify-only AND substantial)
                            (when (or has-session? substantial?)
                              (try
                                (sessions/send-response! token chat-id (str "[" (name agent-id) "] " text))
                                (catch Exception e
                                  (tel/log! {:level :error :id :daemon/sink-send-error
                                             :data {:agent-id agent-id :chat-id chat-id
                                                    :error (.getMessage e)}}
                                            "Sink send failed")))
                              ;; Store in room with source-agent-id for self-filter
                              (try
                                (binding [rtc/*execution-context* exec-ctx]
                                  (when-let [sys (rtc/get-state [:external-refs "dvergr-chat-db"])]
                                    (when-let [room (rooms/get-room-by-telegram-id (:conn sys) chat-id)]
                                      (rooms/post-message! (:conn sys) (:chat/id room)
                                        {:content text
                                         :role :assistant
                                         :source-user (name agent-id)
                                         :source-agent-id (name agent-id)}))))
                                (catch Exception e
                                  (tel/log! {:level :warn :id :daemon/room-store-error
                                             :data {:agent-id agent-id :chat-id chat-id
                                                    :error (.getMessage e)}}
                                            "Room store error"))))))))))
                (tel/log! {:id :daemon/telegram-connected :data {:channel-id (:id connected)}} "Telegram connected")
                (assoc daemon :telegram-ch connected)))
            daemon)]

      ;; Start HTTP server if configured
      (let [daemon-with-http
            (if-let [http-config (:http config)]
              (let [port (or (:port http-config) 8080)]
                (web-server/start! daemon-with-tg :port port)
                (assoc daemon-with-tg :http-server @web-server/server-state))
              daemon-with-tg)]

        ;; Install scheduler schema and restore schedules
        (try
          (binding [rtc/*execution-context* exec-ctx]
            (when-let [conn (some-> (rtc/get-state [:external-refs "dvergr-chat-db"])
                                    :conn)]
              (scheduler/install-schema! conn)))
          (scheduler/restore-schedules! daemon-with-http)
          (catch Exception e
            (tel/log! {:level :warn :id :daemon/scheduler-restore-skipped
                       :data {:error (.getMessage e)}} "Scheduler restore skipped")))

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

  ;; Cancel the single system watcher (replaces per-agent outbox watchers)
  (when-let [watcher @(:system-watcher daemon)]
    (future-cancel watcher)
    (reset! (:system-watcher daemon) nil))

  ;; Leave all registered participants from the discourse room and
  ;; unregister them. Driver pumps + participant spins remain on the
  ;; executor (discourse has no per-spin cancel today) but no further
  ;; messages are routed to them.
  (doseq [agent-id (registry/agent-ids)]
    (tel/log! {:id :daemon/stop-agent :data {:agent-id agent-id}} "Stopping agent")
    (binding [rtc/*execution-context* (:execution-ctx daemon)]
      (when-let [room (:discourse-room daemon)]
        (try (d/leave room agent-id)
             (catch Exception _))))
    (registry/unregister! agent-id))

  ;; Cancel all schedules
  (tel/log! {:id :daemon/cancel-schedules} "Cancelling schedules")
  (scheduler/cancel-all!)

  ;; Stop calendar dispatcher
  (cal-dispatch/stop-dispatcher!)

  ;; Stop HTTP server
  (when (:http-server daemon)
    (web-server/stop!))

  ;; Disconnect Telegram
  (when-let [tg-ch (:telegram-ch daemon)]
    (tel/log! {:id :daemon/telegram-disconnecting} "Disconnecting Telegram")
    (channels/disconnect! (:id tg-ch)))

  ;; Close all sessions
  (doseq [{:keys [chat-id agent-id]} (sessions/list-sessions)]
    (sessions/close-session! chat-id agent-id))

  ;; Shut down room bus
  (bus/shutdown!)

  ;; Shut down search index
  (search/shutdown!)

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
    (registry/list-agents)))

(defn list-sessions
  "List all active Telegram sessions on this daemon's ctx."
  [daemon]
  (binding [rtc/*execution-context* (or (:execution-ctx daemon)
                                        rtc/*execution-context*)]
    (sessions/list-sessions)))

(defn daemon-status
  "Get daemon status summary."
  [daemon]
  (binding [rtc/*execution-context* (or (:execution-ctx daemon)
                                        rtc/*execution-context*)]
    {:status @(:status daemon)
     :agents (count (registry/agent-ids))
     :sessions (sessions/session-count)
     :telegram-connected? (some? (:telegram-ch daemon))
     :http-running? (web-server/running?)
     :http-port (web-server/server-port)
     :schedules (count (scheduler/list-schedules))}))

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
