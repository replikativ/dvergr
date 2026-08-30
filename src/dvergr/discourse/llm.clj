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
          :tools  :knowledge-worker         ; a named profile — or an explicit set
          :budget {:dollars 0.50}}))

   When the dollar budget hits zero the turn simply ENDS: the room gets a
   non-triggering row saying so, the process is marked :awaiting-decision, and
   nothing further is spent. Resumption is an ordinary inbound message — raise
   the room's budget and speak, and the next turn re-enters with the chat-ctx
   seeded from the room store.

   It used to block for :checkpoint-grace-ms waiting on a manager directive.
   That block ran on spindel's DRAIN thread and froze every room in the process;
   see `dvergr.agent.process/budget-exhausted!` for the autopsy.

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
            [dvergr.discourse.attention :as attention]
            [dvergr.discourse.enrichment :as enr]
            [dvergr.discourse.generation :as gen]
            [dvergr.chat.context :as cc]
            [dvergr.chat.agent :as ca]
            [dvergr.agent.run :as run]
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
            [dvergr.model.quirks :as quirks]
            [org.replikativ.spindel.spin.sync :as ssync]
            [taoensso.telemere :as tel]
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

;; ============================================================================
;; Public API
;; ============================================================================

;; ============================================================================
;; Directive effects — ONE implementation for both entry points
;; ============================================================================
;; A directive can arrive while the agent is IDLE (handled by the on-message
;; `case`) or WHILE A TURN IS RUNNING (handled by the turn's inbox arbiter,
;; below). Both paths apply these same fns, so idle and mid-turn behavior
;; cannot drift apart.

(defn- apply-raise-budget!
  "Bump the chat-ctx dollar budget by the directive's :dollars (default 0.25)."
  [chat-ctx msg]
  (let [dollars (or (get-in msg [:payload :dollars]) 0.25)
        micro   (long (* dollars acct/MICRODOLLARS-PER-DOLLAR))]
    (binding [ec/*execution-context* (:spindel-ctx chat-ctx)]
      (swap! (:budget-signal chat-ctx)
             (fn [b] (update b :total + micro))))))

(defn- apply-system-message!
  "Inject the directive's content as a system note the agent reads next turn."
  [chat-ctx msg]
  (cc/add-system-note! chat-ctx (get-in msg [:payload :content])))

(defn- probe-memory-reply
  "Read-only memory probe response (reply-spec shape)."
  [chat-ctx msg]
  {:to (:from msg)
   :type :probe/memory-response
   :payload {:messages (cc/get-messages chat-ctx)}})

(defn default-attention-policy
  "Classify conversational input arriving during an active generation.

   The policy receives {:active-message :incoming-message :participant :room}
   and returns a structured attention decision. The former :steer, :queue, and
   :observe keywords remain accepted for compatibility. The default preserves the current
   direct-conversation behavior: same-thread input steers, while another thread
   queues a separate execution. Products with durable actor identity can refine
   this by sender/addressing (for example, human correction versus peer-agent
   chatter) without changing the Room/thread contract."
  [{:keys [active-message incoming-message]}]
  (if (d/same-thread? active-message incoming-message)
    (attention/restart :conversation/same-thread)
    (attention/enqueue :conversation/different-thread)))

(defn llm-agent
  "Construct a discourse Participant backed by an LLM.

   :id          — keyword participant id (required)
   :spec        — {:provider :model :system-prompt} (required)
   :tools       — a `dvergr.tools` profile keyword (:dev-toolbelt,
                  :knowledge-worker), a set/vector of tool names, OR a map of
                  name → tool-def (pre-wrapped). Default: :dev-toolbelt
                  (`minimal-coding-tools`). Pass `#{}` for a no-tools agent.
   :db-conn     — datahike connection for chat persistence (optional)
   :budget      — {:dollars n} (default {:dollars 1.0}). When it runs out the
                  turn ends and the process is marked :awaiting-decision; a
                  human raises the budget and the next message resumes it.
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
   :on-reply    — (fn [reply-content] → {:content str :notes [str…]}) optional
                  embedder hook threaded to `run-agent-turn!` — rewrites the
                  agent's outbound reply (e.g. resolve references) and returns
                  system notes injected into the chat-ctx for the next turn.
   :attention-policy — (fn [{:active-message :incoming-message :participant
                             :room}] → AttentionDecision).
                  Defaults to same-thread steer and different-thread queue.
                  Legacy :steer/:queue/:observe returns remain accepted.
                  Override to distinguish human correction from peer chatter
                  using the deployment's authoritative actor identity. See
                  dvergr.discourse.attention.
   :ctx         — discourse room's execution context
                  (default: `*execution-context*`)"
  [{:keys [id spec tools db-conn budget compaction
           chat-ctx participant-context tool-ctx run-turn-fn ctx room-safe? on-reply
           attention-policy]
    :or   {budget      {:dollars 1.0}
           compaction  {:auto? true :strategy :sync-before-turn}
           run-turn-fn default-run-turn
           attention-policy default-attention-policy
           room-safe?  true
           ;; Sane default toolbelt instead of the bare `#{:clojure_eval}` poverty
           ;; trap — file ops + jailed shell + clojure_eval (web/data/knowledge via
           ;; the SCI sandbox). Override with a `dvergr.tools` profile keyword
           ;; (:dev-toolbelt / :knowledge-worker) or an explicit tool set.
           tools       tools/minimal-coding-tools}}]
  {:pre [(keyword? id) (map? spec)]}
  (let [ctx       (or ctx ec/*execution-context*)
        ;; Room-less FALLBACK working ctx (sidecar / tests). When the
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
                   (do (apply-raise-budget! chat-ctx msg) nil)

               ;; --- directive: hard cancel current + future generations ---
                   :directive/cancel
                   (do (reset! cancelled? true) nil)

               ;; --- directive: swap the model (or provider) live ---
                   :directive/switch-model
                   (do (swap! spec-atom merge (:payload msg)) nil)

               ;; --- directive: inject a system message into chat-ctx ---
                   :directive/system-message
                   (do (apply-system-message! chat-ctx msg) nil)

               ;; --- probe: read-only inspection of memory ---
                   :probe/memory
                   (probe-memory-reply chat-ctx msg)

               ;; --- internal: a cancelled LLM call settling AFTER its turn
               ;; ended (the turn's inbox bridge, below). Nothing to do — the
               ;; live turn consumed its own ::llm-done by call-id; this one
               ;; is stale by definition. Never treat it as room content. ---
                   ::llm-done
                   nil

               ;; --- default: user/agent content → run generation ---
                   (let [posted   (atom (turn/tool-activity-count chat-ctx))
                         ;; The active arbiter temporarily owns content for other
                         ;; threads, then hands it back to the participant's FIFO
                         ;; inbox after this execution. The Room log is already the
                         ;; durable source; these are only live coordination handles.
                         queued-other-threads (atom [])
                         seen-inflight-ids    (atom (cond-> #{} (:id msg) (conj (:id msg))))
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
                         run-ref           (when room (run/start! room id msg chat-ctx))
                         run-id            (:run/id run-ref)
                         ;; Convenience delegation tools still target the same
                         ;; Run interpreter. Give them the current structural
                         ;; parent and world instead of making them rediscover
                         ;; daemon-global state. Long-lived Participants are a
                         ;; trusted/root surface and therefore carry no nested
                         ;; AgentDef ceiling unless their caller supplied one.
                         tool-ctx          (cond-> (assoc tool-ctx
                                                          :execution-ctx turn-ctx
                                                          :control-room room
                                                          :actor id
                                                          :model-policy
                                                          (select-keys spec
                                                                       [:provider :model]))
                                             run-id (assoc :run-id run-id))
                         turn-opts {:provider         (:provider spec)
                                ;; Per-room /model override (commands registry)
                                ;; wins over the spec's model, matching the daemon.
                                    :model            (or (when room
                                                            (commands/model-override (:id room) id))
                                                          (:model spec))
                                    :tools            tools
                                    :tool-ctx         tool-ctx
                                ;; SSE-abort predicate (Esc-cancel flips status).
                                    :cancel?          (turn/cancel?-fn
                                                       chat-ctx turn-ctx
                                                       #(run/cancel-requested? run-id))
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
                                    :compaction-model (:model compaction)
                                ;; Product reference hook (see run-agent-turn!):
                                ;; rewrites outbound refs in the reply + returns
                                ;; system notes for unresolvable ones.
                                    :on-reply         on-reply}
                         errored           (atom nil)
                         waiting?          (atom false)
                         finish-after-reply? (atom false)
                     ;; #38: the last assistant message BEFORE this invocation
                     ;; runs (the store-seeded prior reply, or nil) — the exit
                     ;; path uses it to tell a genuinely NEW reply from stale
                     ;; seeded history.
                         pre-turn-last-asst (last-assistant-message chat-ctx)
                     ;; Inbound fold: ROOM path append-inbound! (deduped
                     ;; against the bus fold by msg id, decorated with author
                     ;; + time); room-less adds directly to the fallback ctx.
                     ;; Used for the triggering message AND for steer folds.
                         fold-inbound!
                         (fn [m]
                           (if room
                             (room-context/append-inbound! (:id room) id (:id m)
                                                           :user (:content m)
                                                           (room-context/display-name room (:from m))
                                                           (:ts m))
                             (cc/add-message! chat-ctx {:role :user :content (:content m)})))]
                 ;; The just-arrived user message (and any message that STEERS a
                 ;; running turn, below) folds in via fold-inbound! from the let.
                     (try
                       (fold-inbound! msg)
                 ;; ONE CONTROL PLANE (steerable turn). Every influence on a
                 ;; running turn arrives as a message on the participant's own
                 ;; inbox — INCLUDING the LLM call's completion, which a bridge
                 ;; spin posts back as ::llm-done. While a turn runs,
                 ;; participant-spin is suspended awaiting this whole handler,
                 ;; so the arbiter below is the inbox's ONLY consumer: steering
                 ;; is loss-free by construction (single-consumer FIFO — no
                 ;; race, no consumed-but-lost message).
                 ;;
                 ;;   same-thread content → STEER: cooperatively cancel the
                 ;;                        in-flight call (status :cancelled →
                 ;;                        the SSE poll aborts; model.chat
                 ;;                        throws CancellationException BEFORE
                 ;;                        anything is persisted), await its
                 ;;                        settle, fold the message in, and
                 ;;                        continue with a fresh call.
                 ;;   other-thread content → QUEUE: retain FIFO order and start
                 ;;                        a separate execution after this one.
                 ;;   :directive/cancel  → settle, then END the turn (the
                 ;;                        durable room log + a later message
                 ;;                        is the resumption — spindel has no
                 ;;                        durable continuations to park on).
                 ;;   :directive/switch-model → swap spec, settle, restart the
                 ;;                        call under the new model.
                 ;;   raise-budget / system-message / probe → applied inline
                 ;;                        via the SAME helpers as the idle
                 ;;                        path; never preempt.
                 ;;   ::llm-done (stale call-id) → a cancelled call settling
                 ;;                        late; dropped.
                 ;;
                 ;; In :race-with-turn mode, kick off a parallel future-handle
                 ;; running compact! whenever (should-compact?) AND no
                 ;; compaction is already in flight. The next turn picks up
                 ;; the compacted chat-ctx state once it lands.
                       (loop [turn 0]
                         (if (or @cancelled? (run/cancel-requested? run-id))
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
                                                           :tools tools
                                                           :run-id run-id)))
                                   call-id (random-uuid)
                                   mbx     (:inbox-mbx p)
                                 ;; Bridge the call's completion into the SAME
                                 ;; inbox steering messages arrive on. (:done h)
                                 ;; is a Deferred — multi-reader — so the settle
                                 ;; awaits below can read it too.
                                   _ (when mbx
                                       (binding [ec/*execution-context* turn-ctx]
                                         (ssync/spawn!
                                          (sp/spin
                                           (let [r (sp/await (:done h))]
                                             (mbx {:type ::llm-done
                                                   :call-id call-id
                                                   :result r}))))))
                                   decision
                                   (if mbx
                                   ;; Arbiter: single consumer of the inbox for
                                   ;; the duration of this call. Awaits the
                                   ;; Mailbox DIRECTLY (its 2-arity carries the
                                   ;; cancel-token — never via anext/aseq).
                                     (loop []
                                       (let [m (if-let [deferred (d/take-deferred-inbox! p)]
                                                 deferred
                                                 (sp/await mbx))]
                                         (cond
                                           (and (= ::llm-done (:type m))
                                                (= call-id (:call-id m)))
                                           {:tag :llm-done :result (:result m)}

                                         ;; a cancelled earlier call settling late
                                           (= ::llm-done (:type m))
                                           (recur)

                                           :else
                                           (case (:type m)
                                             :directive/raise-budget
                                             (do (apply-raise-budget! chat-ctx m) (recur))

                                             :directive/system-message
                                             (do (apply-system-message! chat-ctx m) (recur))

                                             :probe/memory
                                             (do (when room
                                                   (d/post! room (assoc (probe-memory-reply chat-ctx m)
                                                                        :from id)))
                                                 (recur))

                                             :directive/cancel
                                             {:tag :cancel}

                                             :directive/switch-model
                                             (do (swap! spec-atom merge (:payload m))
                                                 {:tag :switch})

                                           ;; Default: classify conversational
                                           ;; content at the thread-aware attention
                                           ;; boundary. Overlapping subscriptions
                                           ;; can deliver the same id more than once;
                                           ;; ignore the duplicate while this inner
                                           ;; arbiter owns the participant inbox.
                                             (let [mid (:id m)]
                                               (if (and mid
                                                        (contains? @seen-inflight-ids mid))
                                                 (recur)
                                                 (do
                                                   (when mid
                                                     (swap! seen-inflight-ids conj mid))
                                                   (let [attention-decision
                                                         (attention-policy
                                                          {:active-message msg
                                                           :incoming-message m
                                                           :participant p
                                                           :room room})
                                                         action
                                                         (try
                                                           (attention/legacy-action
                                                            attention-decision)
                                                           (catch Throwable error
                                                             (tel/log!
                                                              {:level :warn
                                                               :id ::invalid-attention-decision
                                                               :data {:agent id
                                                                      :decision attention-decision
                                                                      :error (.getMessage error)}}
                                                              "Invalid attention decision; queued conservatively")
                                                             ::invalid-attention-decision))]
                                                     (case action
                                                       :steer {:tag :steer :msg m}
                                                       :observe (recur)
                                                       :queue (do
                                                                (swap! queued-other-threads conj m)
                                                                (recur))
                                                       ::invalid-attention-decision
                                                       (do
                                                         (swap! queued-other-threads conj m)
                                                         (recur))
                                                       (do
                                                         (tel/log!
                                                          {:level :warn
                                                           :id ::invalid-attention-decision
                                                           :data {:agent id
                                                                  :decision attention-decision}}
                                                          "Unsupported attention decision; queued conservatively")
                                                         (swap! queued-other-threads conj m)
                                                         (recur)))))))))))
                                   ;; No inbox (room-less participant that was
                                   ;; never joined): plain await, no steering.
                                     {:tag :llm-done :result (sp/await (:done h))})]
                         ;; Mirror this turn's tool calls into the room as 🔧
                         ;; play-by-play rows (same as daemon agents).
                               (when room
                                 (turn/post-turn-activity! room id chat-ctx posted run-id msg))
                               (if (not= :llm-done (:tag decision))
                               ;; Preempted: cancel the in-flight call through
                               ;; the SAME path as Esc (status :cancelled → the
                               ;; SSE :cancel? poll → CancellationException
                               ;; before anything is persisted; between tools
                               ;; the reduce synthesizes :cancelled results so
                               ;; every committed tool_use keeps a paired
                               ;; result). Then AWAIT THE SETTLE — never a hard
                               ;; future-cancel — so history is coherent before
                               ;; the next step.
                                 (do
                                   (cc/set-status! chat-ctx :cancelled)
                                   (sp/await (:done h))
                                   (cc/set-status! chat-ctx :active)
                                   (case (:tag decision)
                                     :cancel (do (reset! cancelled? true) nil)
                                     :switch (recur turn)
                                     :steer  (do (fold-inbound! (:msg decision))
                                                 (recur (inc turn)))))
                                 (let [result (:result decision)]
                                   (cond
                                     (or @cancelled? (run/cancel-requested? run-id)) nil

                                     (gen/error-result? result)
                                     (do (reset! errored (:dvergr.discourse.generation/error result)) nil)

                               ;; LLM finished cleanly (no more tool calls).
                                     (not= result :continue) nil

                               ;; Budget exhausted → the turn ENDS here.
                               ;;
                               ;; It used to BLOCK for grace-ms waiting on a manager
                               ;; directive — on the spin's DRAIN thread, freezing
                               ;; every room in the process (see
                               ;; `proc/budget-exhausted!` for the full autopsy).
                               ;; Nothing is held now: we say so in the room, mark
                               ;; the process :awaiting-decision, and stop.
                               ;;
                               ;; Resumption is an ordinary inbound message. A human
                               ;; raises the room budget and speaks; the next turn
                               ;; re-enters with the chat-ctx seeded from the store.
                               ;; The durable room log IS the continuation — which is
                               ;; the only kind spindel can actually have (it has no
                               ;; durable conts, and a park would be lost on restart).
                                     (cc/budget-exceeded? chat-ctx)
                                     (do
                                       (when room
                                         (let [b (binding [ec/*execution-context* (:spindel-ctx chat-ctx)]
                                                   @(:budget-signal chat-ctx))
                                               used  (/ (:used b) (double acct/MICRODOLLARS-PER-DOLLAR))
                                               total (/ (:total b) (double acct/MICRODOLLARS-PER-DOLLAR))]
                                           (turn/post-budget-warning! room id used total run-id msg)))
                                       (reset! waiting? true)
                                       (proc/budget-exhausted! id chat-ctx)
                                       nil)

                               ;; Normal :continue, budget OK → next turn.
                                     :else (recur (inc turn)))))))))

                     ;; The outer participant loop can now start queued work. Put
                     ;; consumed messages in the participant-owned priority FIFO,
                     ;; not at the live mailbox tail: a newer arrival during this
                     ;; hand-back window must not overtake older topics.
                       (doseq [queued @queued-other-threads]
                         (d/defer-inbox! p queued))
                     ;; A FAILED turn produced no new reply — surface it as a NON-triggering
                     ;; :_activity row and DON'T fall through to re-post the STALE last reply
                     ;; (which the room's other agents answer, looping — the "repeating" bug).
                       (when @errored (turn/post-turn-error! room id @errored run-id msg))
                     ;; #38: the SILENT-failure shape — the turn ended with no
                     ;; error tag AND no new assistant message (e.g. provider
                     ;; resolution failed before any generation). The last
                     ;; assistant message is then the store-SEEDED prior reply;
                     ;; posting it is the repeating bug. Surface it instead.
                     ;; A deliberate cancel stays quiet.
                       (when (and (not @errored) (not @cancelled?)
                                  (not (run/cancel-requested? run-id))
                                  (not= :cancelled (cc/get-status chat-ctx))
                                  (= pre-turn-last-asst (last-assistant-message chat-ctx)))
                         (let [error "turn ended without producing a reply — likely a provider/model resolution failure"]
                           (reset! errored error)
                           (turn/post-turn-error! room id error run-id msg)))
                       (when-let [last-asst (when-not @errored
                                              (let [la (last-assistant-message chat-ctx)]
                                                (when (not= la pre-turn-last-asst) la)))]
                         (when-let [reply (assistant-text last-asst)]
                     ;; Last line of defence: a model that fumbled code into the
                     ;; prose channel TWICE (agent.clj nudged it once) must still
                     ;; not have that fragment posted as its reply — the room
                     ;; would show code as an answer and other agents would reply
                     ;; TO it. Surface it as a non-triggering activity row instead.
                           (if (quirks/code-fragment? reply)
                             (do (tel/log! {:level :warn :id ::reply-was-code-fragment
                                            :data {:agent id}}
                                           "Suppressed a code fragment posing as a reply")
                                 (when room
                                   (let [error (str "emitted code instead of a reply — the tool call "
                                                    "never reached the tool channel, so nothing ran")]
                                     (reset! errored error)
                                     (turn/post-turn-error! room id error run-id msg)))
                                 nil)
                     ;; Carry this turn's interleaved-thinking trace into the room
                     ;; record (metadata → store → seeding) so reasoning models
                     ;; (MiniMax M2 / Kimi / DeepSeek) keep their <think> context
                     ;; across a rehydrate/restart, not just within a live session.
                             (let [reasoning (or (:message/reasoning last-asst) (:reasoning last-asst))
                                   reply-spec
                                   (cond-> {:to (:from msg) :content reply
                                            :metadata {:run-id run-id}}
                                     (seq reasoning) (assoc-in [:metadata :reasoning] reasoning))]
                               (if-not run-id
                                 reply-spec
                                 (do
                                   ;; The outer participant owns Room emission.
                                   ;; Completion is acknowledged only after its
                                   ;; durability-first post succeeds.
                                   (reset! finish-after-reply? true)
                                   (d/after-reply-emission
                                    reply-spec
                                    (fn [_emitted]
                                      (run/finish! run-id :completed))
                                    (fn [error]
                                      (run/finish! run-id :failed
                                                   {:reason :reply-emission-failed
                                                    :error error})))))))))
                       (catch Throwable t
                         (reset! errored t)
                         (throw t))
                       (finally
                         (when (and run-id (not @finish-after-reply?))
                           (let [cancelled-run? (or @cancelled?
                                                    (run/cancel-requested? run-id)
                                                    (= :cancelled (cc/get-status chat-ctx)))
                                 status (cond
                                          cancelled-run? :cancelled
                                          @errored :failed
                                          @waiting? :waiting
                                          :else :completed)]
                             (run/finish! run-id status
                                          (cond-> {}
                                            cancelled-run? (assoc :reason :cancel-requested)
                                            @waiting? (assoc :reason :budget-exhausted)
                                            @errored (assoc :reason :error :error @errored))))))))))))

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
                          :attention-policy attention-policy
                          :ctx         new-ctx}))})]
      ;; Room-safe by DEFAULT: self-filter (never answer your own messages —
      ;; the echo-loop guard), silence ([SKIP] → no post), plain-reply. The
      ;; daemon, sidecar, and raw callers all get a loop-safe agent. Pass
      ;; :room-safe? false for a bare agent (ask-only, single-recipient use).
      (cond-> participant
        room-safe? enr/with-self-filter
        room-safe? enr/with-silence
        room-safe? enr/with-plain-reply))))
