(ns dvergr.discourse.llm
  "LLM-backed participant constructor for `dvergr.discourse` rooms.

   `(llm-agent {…})` returns a Participant whose `on-message` handler runs
   one or more LLM turns via `dvergr.chat.agent/run-agent-turn!`. The
   blocking LLM call is bridged into the spin via a Clojure future that
   delivers to a spindel deferred — the participant's loop remains
   non-blocking. Tools, accounting, and compaction flow through
   `dvergr.chat.context` and `dvergr.tools` unchanged.

   Usage:

     (require '[dvergr.discourse :as d] '[dvergr.discourse.llm :as llm])

     (d/join room
       (llm/llm-agent
         {:id     :researcher
          :spec   {:provider :anthropic
                   :model    \"claude-sonnet-4-6\"
                   :system-prompt \"You are a research assistant.\"}
          :tools  #{:clojure_eval}
          :budget {:dollars 0.50}}))

   When the dollar budget hits zero, the agent loop escalates via
   `dvergr.agent.process/register!` — the manager sees the agent as
   :awaiting-decision in the Processes pane and can call
   `(proc/directive! chat-ctx pid {:type :continue :effects [{:op :extend-budget :dollars 0.25}]})`
   to keep it going, or `{:type :abort}` to stop it. If no directive
   arrives within :checkpoint-grace-ms (default 60s), the loop runs a
   single final turn with NO tools and a wrap-up system message, then
   exits cleanly.

   Tests pass `:run-turn-fn` (a stub returning :continue/:complete and
   writing to chat-ctx directly) to avoid real LLM calls — see
   `dvergr.discourse.llm-test/make-mock-turn-fn`.

   Defaults override-able via :ctx, :tool-ctx, :compaction options.
   Conversation state is per-participant (one chat-ctx per agent instance).
   On fork-room, the agent is re-created fresh (no history carryover; this
   matches ToM probe semantics). Future: snapshot-based history carryover."
  (:require [clojure.string :as str]
            [org.replikativ.spindel.core :as sp]
            [org.replikativ.spindel.engine.core :as ec]
            [dvergr.discourse :as d]
            [dvergr.discourse.enrichment :as enr]
            [dvergr.discourse.generation :as gen]
            [dvergr.chat.context :as cc]
            [dvergr.chat.agent :as ca]
            [dvergr.agent.turn :as turn]
            [dvergr.agent.room-context :as room-context]
            [dvergr.agent.prompt :as prompt]
            [dvergr.discourse.commands :as commands]
            [dvergr.chat.compaction :as compaction]
            [dvergr.chat.accounting :as acct]
            [dvergr.participant.context :as pctx]
            [dvergr.agent.process :as proc]
            [dvergr.room.store :as rstore]
            [dvergr.system.rooms :as srooms]
            [dvergr.tools :as tools]))

;; ============================================================================
;; Helpers
;; ============================================================================

(defn- default-run-turn
  "Default `run-turn-fn`: delegates to `dvergr.chat.agent/run-agent-turn!`."
  [chat-ctx opts]
  (ca/run-agent-turn! chat-ctx opts))

(defn- assistant-text
  "Extract a string from an assistant message entity. Handles plain-string
   content and block-vector content (Anthropic-style)."
  [msg]
  (let [content (or (:message/content msg) (:content msg))]
    (cond
      (nil?        content) nil
      (string?     content) content
      (sequential? content)
      (->> content
           (filter #(or (= "text" (:type %)) (= :text (:type %))))
           (map :text)
           (str/join "\n"))
      :else (str content))))

(defn- last-assistant-message
  [chat-ctx]
  (->> (cc/get-messages chat-ctx)
       reverse
       (filter #(let [r (or (:role %) (:message/role %))]
                  (or (= r :assistant) (= r "assistant"))))
       first))

;; ============================================================================
;; Wrap-up prompt for budget-checkpoint :wrap-up resolution
;; ============================================================================

(def ^:private wrap-up-prompt
  "Time to wrap up. Budget exhausted and no extension granted. Write a brief reply summarizing what you've done and what's unfinished. Don't call any more tools.")

;; ============================================================================
;; Public API
;; ============================================================================

(defn llm-agent
  "Construct a discourse Participant backed by an LLM.

   :id          — keyword participant id (required)
   :spec        — {:provider :model :system-prompt} (required)
   :tools       — set/vector of tool names from `dvergr.tools` registry, OR
                  a map of name → tool-def (for pre-wrapped tools)
   :db-conn     — datahike connection for chat persistence (optional)
   :budget      — {:dollars n :checkpoint-grace-ms n}
                  (default {:dollars 1.0 :checkpoint-grace-ms 60000}).
                  When the dollar budget runs out, the loop pauses and
                  escalates via dvergr.agent.process; if no extension arrives
                  within grace-ms, the agent gets one no-tools wrap-up
                  turn and exits.
   :compaction  — {:auto? bool :model str} (default {:auto? true})
   :chat-ctx    — pre-built ChatContext (optional). When provided, llm-agent
                  uses it as-is (no fresh creation, no system-prompt seeding).
                  Use this when the caller needs richer setup — replayed
                  history, custom SCI bindings, owner-specific persistence.
   :tool-ctx    — pre-built tool execution context (optional). Default is
                  `(tools/make-context {:db-conn :chat-ctx})`. Override
                  when SCI sandbox, KB write namespaces, or pre-wrapped
                  tools are needed.
   :run-turn-fn — (fn [chat-ctx opts] → :complete | :continue | :error)
                  Override for testing or to inject per-turn behaviour
                  (e.g. usage logging). Default calls
                  `dvergr.chat.agent/run-agent-turn!`.
   :ctx         — discourse room's execution context
                  (default: `*execution-context*`)"
  [{:keys [id spec tools db-conn budget compaction
           chat-ctx participant-context tool-ctx run-turn-fn ctx room-safe?]
    :or   {budget      {:dollars 1.0}
           compaction  {:auto? true :strategy :sync-before-turn}
           run-turn-fn default-run-turn
           room-safe?  true}}]
  {:pre [(keyword? id) (map? spec)]}
  (let [ctx       (or ctx ec/*execution-context*)
        ;; Room-less FALLBACK working ctx (sidecar / d/hire / tests). When the
        ;; agent is joined to a ROOM, the per-[room,agent] room-context ctx is
        ;; used instead — seeded from the room store, kept current by a bus fold,
        ;; stable id (budget + persistence across restart/fork) — resolved per
        ;; turn in on-message. Priority: :participant-context > :chat-ctx > fresh.
        ;; :with-sci? true so a room-less agent's clojure_eval has a sandbox.
        fallback-chat-ctx
        (or (when participant-context
              (pctx/->chat-context participant-context))
            chat-ctx
            (let [c (turn/new-working-ctx
                     {:execution-ctx  ctx
                      :title          (str "agent " (name id))
                      :budget-dollars (:dollars budget 1.0)
                      :db-conn        db-conn})]
              (when-let [sp (:system-prompt spec)]
                (cc/add-message! c {:role :system :content sp}))
              c))
        ;; Grace window for the manager to extend the budget after exhaustion.
        grace-ms  (long (or (:checkpoint-grace-ms budget) 60000))
        compaction-strategy (:strategy compaction :sync-before-turn)
        ;; In race mode, disable run-turn-fn's internal sync compaction — we
        ;; drive it from the agent's spin-race below.
        race-compaction?    (= :race-with-turn compaction-strategy)]
    (let [;; Mutable state controlled by directives (message-channel).
          ;; The budget-checkpoint above is a SEPARATE channel via
          ;; dvergr.agent.process — both paths can fire; first to act wins.
          cancelled?   (atom false)             ; flip on :directive/cancel
          spec-atom    (atom spec)              ; swap on :directive/switch-model
          ;; Race-arm state: at most one in-flight compaction handle.
          compaction-h (atom nil)
          participant
          (d/participant
           {:id  id
            :ctx ctx
            :on-message
            (fn [p msg]
              (sp/spin
               (let [room     (:room p)        ; the Room this participant is joined to
                     turn-ctx (if room (:ctx room) ctx)  ; run the turn in the ROOM's ctx
                   ;; Per-[room,agent] working chat-ctx (design D): seeded from the
                   ;; room store, kept current by a bus fold, stable id (budget +
                   ;; persistence across restart/fork). Room-less → the fallback ctx.
                   ;; Directives AND the turn loop share this resolved chat-ctx.
                     chat-ctx (if room
                                (room-context/ensure-ctx! room id
                                                          {:system-prompt  (:system-prompt spec)
                                                           :budget-dollars (:dollars budget 1.0)})
                                fallback-chat-ctx)]
                 (case (:type msg)

               ;; --- directive: extend the dollar budget ---
               ;; Bumps :total on the chat-ctx budget signal. The
               ;; budget-checkpoint! path is the preferred way to do
               ;; this (via Processes pane + processes/directive!),
               ;; but message-channel still works.
                   :directive/raise-budget
                   (let [dollars  (or (get-in msg [:payload :dollars]) 0.25)
                         micro    (long (* dollars acct/MICRODOLLARS-PER-DOLLAR))]
                     (binding [ec/*execution-context* (:spindel-ctx chat-ctx)]
                       (swap! (:budget-signal chat-ctx)
                              (fn [b] (update b :total + micro))))
                     nil)

               ;; --- directive: hard cancel current + future generations ---
                   :directive/cancel
                   (do (reset! cancelled? true) nil)

               ;; --- directive: swap the model (or provider) live ---
                   :directive/switch-model
                   (do (swap! spec-atom merge (:payload msg)) nil)

               ;; --- directive: inject a system message into chat-ctx ---
                   :directive/system-message
                   (do (cc/add-message! chat-ctx
                                        {:role :system
                                         :content (get-in msg [:payload :content])})
                       nil)

               ;; --- probe: read-only inspection of memory ---
                   :probe/memory
                   {:to (:from msg)
                    :type :probe/memory-response
                    :payload {:messages (cc/get-messages chat-ctx)}}

               ;; --- default: user/agent content → run generation ---
                   (let [posted   (atom 0)        ; 🔧 activity watermark (dedup)
                     ;; SCI sandbox: the chat-ctx's own, set up ONCE by
                     ;; turn/new-working-ctx (room fold AND room-less fallback alike)
                     ;; with the agent namespaces injected — so clojure_eval has
                     ;; dh/room/intake without a per-turn re-fork.
                         sci-ctx  (:sci-ctx chat-ctx)
                     ;; Normalize once: name→tool-def map (also the execute-side
                     ;; authoritative allowlist below).
                         tool-map (tools/normalize-tools tools)
                         tool-ctx (or tool-ctx
                                      (-> (tools/make-context
                                        ;; The RESOLVED ctx's conn (a fork's isolated
                                        ;; conn under a fork) so KB/tool writes land in
                                        ;; the fork, not the parent — fork isolation.
                                           {:db-conn   (or (:db-conn chat-ctx) db-conn)
                                            :chat-ctx  chat-ctx
                                            :sci-ctx   sci-ctx
                                         ;; AUTHORITATIVE allowlist — execute refuses
                                         ;; any tool outside this set (defense in depth
                                         ;; vs. hallucinated / injected tool names).
                                            :tools     tool-map
                                            :isolation (or (:isolation spec) :sci)})
                                      ;; P2c: the room's own + attached code repos for
                                      ;; the SCI load-fn (resolved first, base after).
                                          (assoc :workspace-roots
                                                 (when room
                                                   (srooms/roots-for-slug
                                                    (rstore/room-id->slug (:id room)))))
                                      ;; RF4: the room's OWN KB conn (fork-aware) —
                                      ;; knowledge_add/search write/read
                                      ;; here, while :task/logging tools keep :db-conn
                                      ;; (the chat DB). nil when room-less or the room
                                      ;; isn't provisioned → tools fall back to :db-conn.
                                          (assoc :kb-conn
                                                 (when room
                                                   (srooms/kb-conn-for-slug
                                                    (rstore/room-id->slug (:id room)))))
                                      ;; RF5: the Room itself, so room-scoped tools
                                      ;; (schedule_*) write into THIS room's store.
                                          (assoc :room room)))
                         turn-opts {:provider         (:provider spec)
                                ;; Per-room /model override (commands registry)
                                ;; wins over the spec's model, matching the daemon.
                                    :model            (or (when room
                                                            (commands/model-override (:id room) id))
                                                          (:model spec))
                                    :tools            tools
                                    :tool-ctx         tool-ctx
                                ;; SSE-abort predicate (Esc-cancel flips status).
                                    :cancel?          (turn/cancel?-fn chat-ctx turn-ctx)
                                ;; TRANSIENT per-turn system note(s) — applied to
                                ;; THIS call only, never persisted (run-agent-turn!
                                ;; appends to the first system message): always the
                                ;; current date/time (so the model anchors 'today'
                                ;; to reality, not its training cutoff), plus the
                                ;; /plan guideline when the room is in plan mode.
                                    :system-suffix    (str/join
                                                       "\n\n"
                                                       (cond-> [(prompt/now-note)]
                                                         (and room (= :plan (commands/room-mode (:id room))))
                                                         (conj prompt/planning-mode-guideline)))
                                    :auto-compact?    (and (:auto? compaction true)
                                                           (not race-compaction?))
                                    :compaction-model (:model compaction)}]
                 ;; The just-arrived user message. ROOM path: append-inbound!
                 ;; (deduped against the bus fold by msg id, decorated with author
                 ;; + time). Room-less: add directly to the fallback ctx.
                     (if room
                       (room-context/append-inbound! (:id room) id (:id msg)
                                                     :user (:content msg)
                                                     (room-context/display-name room (:from msg))
                                                     (:ts msg))
                       (cc/add-message! chat-ctx {:role :user :content (:content msg)}))
                 ;; Publish the live chat-ctx so a frontend (TUI/web) can
                 ;; Esc-cancel this turn (turn/cancel-room-turn! flips its status).
                     (when room (turn/register-room-turn! (:id room) id chat-ctx))
                 ;; Race each turn against the cancel flag. The future-handle
                 ;; bridges the blocking LLM call into spindel; we await done.
                 ;;
                 ;; In :race-with-turn mode, kick off a parallel future-handle
                 ;; running compact! whenever (should-compact?) AND no
                 ;; compaction is already in flight. The next turn picks up
                 ;; the compacted chat-ctx state once it lands.
                 ;; Loop state:
                 ;;   turn             — running turn counter (informational
                 ;;                       only; no upper bound)
                 ;;   wrap-up-allowed? — true after a budget-checkpoint
                 ;;                       resolved to :wrap-up. Next turn
                 ;;                       runs with NO tools and must
                 ;;                       produce the final reply; loop
                 ;;                       exits after it.
                     (loop [turn             0
                            wrap-up-allowed? false]
                       (if @cancelled?
                         nil
                         (do
                           (when race-compaction?
                             (when (or (nil? @compaction-h)
                                       (some-> ^java.util.concurrent.Future @compaction-h
                                               .isDone))
                               (when (compaction/should-compact? chat-ctx)
                                 (reset! compaction-h
                                         (future
                                           (binding [ec/*execution-context* turn-ctx]
                                             (try
                                               (compaction/maybe-compact!
                                                chat-ctx
                                                :model (:model compaction))
                                               (catch Throwable _ nil))))))))
                           (let [h (gen/future-handle
                                    turn-ctx
                                    #(run-turn-fn chat-ctx
                                                  (assoc turn-opts
                                                         :turn-number turn
                                                         :spec @spec-atom
                                                      ;; Final-turn lock-out: no tools,
                                                      ;; LLM must give a textual wrap-up.
                                                         :tools (if wrap-up-allowed? {} tools))))
                                 result (sp/await (:done h))]
                         ;; Mirror this turn's tool calls into the room as 🔧
                         ;; play-by-play rows (same as daemon agents).
                             (when room (turn/post-turn-activity! room id chat-ctx posted))
                             (cond
                               @cancelled? nil

                               (gen/error-result? result) nil

                           ;; LLM finished cleanly (no more tool calls).
                               (not= result :continue) nil

                           ;; The wrap-up slot was used — the loop made
                           ;; its single no-tools turn, exit now.
                               wrap-up-allowed? nil

                           ;; Budget exhausted on a :continue turn:
                           ;; escalate to manager via the Processes pane.
                               (cc/budget-exceeded? chat-ctx)
                               (case (proc/budget-checkpoint! id chat-ctx grace-ms)
                                 :extended (recur (inc turn) false)
                                 :abort    nil
                                 :wrap-up  (do
                                             (cc/add-message! chat-ctx
                                                              {:role    :system
                                                               :content wrap-up-prompt})
                                             (recur (inc turn) true)))

                           ;; Normal :continue, budget OK → next turn.
                               :else (recur (inc turn) wrap-up-allowed?))))))

                     (when room (turn/unregister-room-turn! (:id room) id))
                     (when-let [last-asst (last-assistant-message chat-ctx)]
                       (when-let [reply (assistant-text last-asst)]
                     ;; Carry this turn's interleaved-thinking trace into the room
                     ;; record (metadata → store → seeding) so reasoning models
                     ;; (MiniMax M2 / Kimi / DeepSeek) keep their <think> context
                     ;; across a rehydrate/restart, not just within a live session.
                         (let [reasoning (or (:message/reasoning last-asst) (:reasoning last-asst))]
                           (cond-> {:to (:from msg) :content reply}
                             (seq reasoning) (assoc :metadata {:reasoning reasoning}))))))))))

            :factory
            (fn [new-ctx]
         ;; Fork-room semantics for an LLM agent: re-create fresh in the
         ;; new context. The fork's agent has no prior conversation,
         ;; matching the §6.5 ToM-probe semantics ("what would they say,
         ;; given only the priming I set up?"). Future enhancement: pass
         ;; an :init-snapshot to carry conversation forward.
              (llm-agent {:id          id
                          :spec        spec
                          :tools       tools
                          :db-conn     db-conn
                          :budget      budget
                          :compaction  compaction
                          :run-turn-fn run-turn-fn
                          :ctx         new-ctx}))})]
      ;; Room-safe by DEFAULT: self-filter (never answer your own messages —
      ;; the echo-loop guard), silence ([SKIP] → no post), plain-reply. The
      ;; daemon, sidecar, and raw callers all get a loop-safe agent. Pass
      ;; :room-safe? false for a bare agent (ask-only, single-recipient use).
      (cond-> participant
        room-safe? enr/with-self-filter
        room-safe? enr/with-silence
        room-safe? enr/with-plain-reply))))
