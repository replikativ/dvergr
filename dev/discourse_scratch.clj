(ns dev.discourse-scratch
  "Scratch: Steps 1-4 of dvergr/doc/discourse-model.md §11.

   Step 1: Message / Participant / Room with mailbox-driven spins.
   Step 2: ask / fan-out / race / quorum / pipeline + with-latency / flaky.
   Step 3: fork-room / merge-room / discard via spindel sp/fork-context.
   Step 4: iterative-refinement / debate / moderate / align-on /
           simulate-reply (the theory-of-mind killer demo).

   Participants gain a `:factory` field so they can be cloned into a forked
   room with their current internal state captured (e.g., a script's
   remaining replies)."
  (:require [clojure.string :as str]
            [org.replikativ.spindel.core :as sp]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.spin.sync :as sync]
            [org.replikativ.spindel.spin.combinators :as comb]))

;; ============================================================================
;; Records
;; ============================================================================

(defrecord Message     [id from to content ts])
(defrecord Participant [id inbox state on-message factory process])
(defrecord Room        [id participants log ctx forked-at-len])

(defn new-message [from to content]
  (->Message (random-uuid) from to content (System/currentTimeMillis)))

;; ============================================================================
;; Room delivery
;; ============================================================================

(defn route-and-log!
  [room msg]
  (swap! (:log room) conj msg)
  (when-let [target (get @(:participants room) (:to msg))]
    (sync/post! (:inbox target) msg))
  msg)

(defn post!
  "Public post: route a Message into the room. Safe to call from any thread —
   always binds the room's execution context for the underlying sync/post!."
  [room msg]
  (binding [ec/*execution-context* (:ctx room)]
    (route-and-log! room msg)))

(defn room
  [id]
  (let [ctx (sp/create-execution-context)]
    (->Room id (atom {}) (atom []) ctx 0)))

;; ============================================================================
;; Participant spin (§5.1 continuous-time mailbox-delta-driven)
;; ============================================================================

(defn- participant-spin
  [p room]
  (sp/spin
    (loop []
      (let [msg (sp/await (:inbox p))]
        (reset! (:state p) :thinking)
        (let [reply-spec (sp/await ((:on-message p) p msg))]
          (when reply-spec
            (let [reply (new-message (:id p) (:to reply-spec) (:content reply-spec))]
              (route-and-log! room reply))))
        (reset! (:state p) :idle))
      (recur))))

(defn join
  [room p]
  (swap! (:participants room) assoc (:id p) p)
  (binding [ec/*execution-context* (:ctx room)]
    (let [proc (participant-spin p room)]
      (sp/spawn! proc)
      (assoc p :process proc))))

(defn participant
  [{:keys [id on-message factory ctx]}]
  (let [ctx   (or ctx ec/*execution-context*)
        inbox (sync/create-mailbox ctx)
        state (atom :idle)]
    (->Participant id inbox state on-message factory nil)))

(defn script
  "Scripted participant. Factory captures current `remaining` so a fork
   continues from the parent's state, not from scratch."
  [id replies ctx]
  (let [remaining (atom (vec replies))
        on-message (fn [_p _msg]
                     (sp/spin
                       (when-let [next-reply (first @remaining)]
                         (swap! remaining subvec 1)
                         next-reply)))]
    (participant {:id id :ctx ctx
                  :on-message on-message
                  :factory (fn [new-ctx]
                             (script id (vec @remaining) new-ctx))})))

(defn echo
  [id ctx]
  (participant
    {:id id :ctx ctx
     :on-message (fn [_p msg]
                   (sp/spin
                     {:to (:from msg) :content (str "echo: " (:content msg))}))
     :factory (fn [new-ctx] (echo id new-ctx))}))

(defn responder
  "Reply-to-sender scripted participant. `contents` is a vector of strings;
   each incoming message gets the next content as reply, addressed back to
   whoever sent it. Pairs naturally with `ask`-based patterns (iterative-
   refinement, debate, align-on) where the conversation partner is the
   sender, not a fixed target."
  [id contents ctx]
  (let [remaining (atom (vec contents))]
    (participant
      {:id id :ctx ctx
       :on-message (fn [_p msg]
                     (sp/spin
                       (when-let [next-content (first @remaining)]
                         (swap! remaining subvec 1)
                         {:to (:from msg) :content next-content})))
       :factory (fn [new-ctx] (responder id (vec @remaining) new-ctx))})))

;; ============================================================================
;; Step 2 — Combinators
;; ============================================================================

(defn ask
  [room target-id msg-spec]
  (sp/spin
    (let [asker-id (keyword (str "ask-" (subs (str (random-uuid)) 0 8)))
          d        (binding [ec/*execution-context* (:ctx room)]
                     (sync/create-deferred (:ctx room)))
          asker    (participant
                     {:id asker-id :ctx (:ctx room)
                      :on-message (fn [_ msg]
                                    (sp/spin (sync/deliver! d msg) nil))})
          _        (join room asker)
          _        (post! room (new-message asker-id target-id (:content msg-spec)))
          reply    (sp/await d)]
      (swap! (:participants room) dissoc asker-id)
      reply)))

(defn fan-out
  [room targets msg-spec]
  (sp/spin
    (sp/await (apply comb/parallel (mapv #(ask room % msg-spec) targets)))))

(defn race
  [room targets msg-spec]
  (sp/spin
    (sp/await (apply comb/race (mapv #(ask room % msg-spec) targets)))))

(defn quorum
  [room targets msg-spec n]
  (sp/spin
    (let [d         (sync/create-deferred (:ctx room))
          collected (atom [])]
      (doseq [target targets]
        (sp/spawn!
          (sp/spin
            (let [reply   (sp/await (ask room target msg-spec))
                  current (swap! collected conj reply)]
              (when (= n (count current))
                (sync/deliver! d current))))))
      (sp/await d))))

(defn pipeline
  [room targets msg-spec]
  (sp/spin
    (loop [remaining       targets
           current-content (:content msg-spec)
           last-reply      nil]
      (if (empty? remaining)
        last-reply
        (let [reply (sp/await (ask room (first remaining) {:content current-content}))]
          (recur (rest remaining) (:content reply) reply))))))

;; ============================================================================
;; Step 2 — Wrappers
;; ============================================================================

(defn with-latency
  [p {:keys [base-ms jitter-ms] :as opts :or {jitter-ms 0}}]
  (let [orig-handler (:on-message p)
        orig-factory (:factory p)]
    (-> p
        (assoc :on-message
               (fn [pp msg]
                 (sp/spin
                   (let [delay (+ base-ms (long (rand jitter-ms)))]
                     (sp/await (comb/sleep delay))
                     (sp/await (orig-handler pp msg))))))
        (assoc :factory
               (when orig-factory
                 (fn [new-ctx] (with-latency (orig-factory new-ctx) opts)))))))

(defn flaky
  [p {:keys [error-rate timeout-rate] :as opts :or {error-rate 0.1 timeout-rate 0.0}}]
  (let [orig-handler (:on-message p)
        orig-factory (:factory p)]
    (-> p
        (assoc :on-message
               (fn [pp msg]
                 (sp/spin
                   (let [r (rand)]
                     (cond
                       (< r error-rate)
                       {:to (:from msg) :content "[ERROR: simulated failure]"}

                       (< r (+ error-rate timeout-rate))
                       (do (sp/await (comb/sleep 30000))
                           {:to (:from msg) :content "[TIMEOUT]"})

                       :else
                       (sp/await (orig-handler pp msg)))))))
        (assoc :factory
               (when orig-factory
                 (fn [new-ctx] (flaky (orig-factory new-ctx) opts)))))))

;; ============================================================================
;; Step 3 — Forking
;;
;; fork-room creates a sibling room. The spindel execution context is forked
;; via sp/fork-context (overlay backend, continuations copied — yggdrasil-
;; registered systems would fork too when present; see Open Q #11). The room
;; itself gets fresh atoms (participants, log) seeded from parent. Each
;; participant is re-created via its :factory so internal state is captured
;; at fork time and diverges thereafter.
;; ============================================================================

(defn fork-room
  "Create a sibling room. Returns a new Room whose execution context is forked
   from `room` and whose participants are cloned (via their :factory) into
   the fork. Log is snapshotted at fork time."
  [room]
  (let [forked-ctx (sp/fork-context (:ctx room))
        new-id (keyword (str (name (:id room)) "-fork-" (subs (str (random-uuid)) 0 6)))
        parent-log @(:log room)
        new-room (->Room new-id (atom {}) (atom parent-log) forked-ctx
                         (count parent-log))]
    (binding [ec/*execution-context* forked-ctx]
      (doseq [[_id p] @(:participants room)]
        (if-let [fac (:factory p)]
          (let [new-p (fac forked-ctx)]
            (join new-room new-p))
          ;; transient/unfactoryable participant (e.g. live ask-NNN); skip
          nil)))
    new-room))

(defn discard
  "Discard a fork: stop its context (all spins terminate)."
  [fork]
  (sp/stop-context! (:ctx fork))
  fork)

(defn merge-room
  "Merge fork's NEW log entries (those added after the fork point) back into
   parent, then stop the fork. Assumes parent's log was not modified between
   fork and merge (the standard ToM / what-if pattern)."
  [parent fork]
  (let [fork-log     @(:log fork)
        forked-at    (:forked-at-len fork)
        new-entries  (when (and forked-at (> (count fork-log) forked-at))
                       (subvec fork-log forked-at))]
    (when (seq new-entries)
      (swap! (:log parent) into new-entries)))
  (sp/stop-context! (:ctx fork))
  parent)

;; ============================================================================
;; Scenario builder
;; ============================================================================

(defn run-scenario
  [{:keys [participants-spec initial-msg wait-ms]
    :or {wait-ms 500}}]
  (let [r (room :test)]
    (binding [ec/*execution-context* (:ctx r)]
      (doseq [p-spec participants-spec]
        (let [base (case (:kind p-spec)
                     :script (script (:id p-spec) (:replies p-spec) (:ctx r))
                     :echo   (echo   (:id p-spec) (:ctx r)))
              wrapped (cond-> base
                        (:latency p-spec) (with-latency (:latency p-spec))
                        (:flaky   p-spec) (flaky        (:flaky   p-spec)))]
          (join r wrapped)))
      (when initial-msg (post! r initial-msg))
      (Thread/sleep wait-ms)
      {:room r
       :log @(:log r)
       :participant-states (into {} (for [[id p] @(:participants r)]
                                      [id @(:state p)]))})))

;; ============================================================================
;; Step 4 — Higher patterns (everything below decomposes to Steps 1-3)
;; ============================================================================

(defn iterative-refinement
  "Producer drafts, critic reviews; loop until `accept?` fires or max-iter.
   Returns Spin yielding {:result :accepted|::max-iter :iterations n :draft m :review m}."
  [room producer-id critic-id initial-msg
   {:keys [accept? max-iter] :or {max-iter 5}}]
  (sp/spin
    (loop [i 0
           current initial-msg]
      (if (>= i max-iter)
        {:result ::max-iter :iterations i :last current}
        (let [draft  (sp/await (ask room producer-id current))
              review (sp/await (ask room critic-id   {:content (:content draft)}))]
          (if (accept? review)
            {:result :accepted :iterations i :draft draft :review review}
            (recur (inc i)
                   {:content (str "Refine. Last: " (:content draft)
                                  " | Feedback: " (:content review))})))))))

(defn debate
  "Round-robin between targets for N rounds. Each round all targets reply to
   the concatenated previous-round content. Returns Spin yielding the history
   as a vector of round-vectors of replies."
  [room targets {:keys [rounds initial-content] :or {rounds 2 initial-content ""}}]
  (sp/spin
    (loop [round   0
           content initial-content
           history []]
      (if (>= round rounds)
        history
        (let [replies (sp/await (fan-out room targets {:content content}))
              next-content (str/join " | " (map :content replies))]
          (recur (inc round) next-content (conj history replies)))))))

(defn moderate
  "Moderator-driven turn-taking. `pick-fn :: history → next-speaker-id | nil`.
   pick-fn returns the next id to ask, or nil to stop. Each chosen speaker
   replies to the *last* entry in history. Returns Spin yielding history."
  [room initial-msg {:keys [pick-fn max-rounds] :or {max-rounds 10}}]
  (sp/spin
    (loop [round 0
           history [initial-msg]]
      (if (>= round max-rounds)
        history
        (if-let [next-id (pick-fn history)]
          (let [reply (sp/await (ask room next-id {:content (:content (peek history))}))]
            (recur (inc round) (conj history reply)))
          history)))))

(defn align-on
  "Habermas-Machine pattern (§8.3). Mediator drafts a statement; all
   participants critique; mediator incorporates and re-drafts. Loop until
   `accept?` is true for every critique (consensus) or max-rounds.

   accept? :: Message → bool      (a critique counts as accepting)
   Returns Spin yielding {:result :converged|::max-rounds :draft str :rounds n}."
  [room mediator-id participants topic
   {:keys [accept? max-rounds] :or {max-rounds 5}}]
  (sp/spin
    (loop [round 0
           draft topic]
      (if (>= round max-rounds)
        {:result ::max-rounds :draft draft :rounds round}
        (let [critiques   (sp/await (fan-out room participants {:content draft}))
              all-accept? (every? accept? critiques)]
          (if all-accept?
            {:result :converged :draft draft :rounds round
             :final-critiques critiques}
            (let [feedback (str/join "\n"
                             (map #(str "- " (name (:from %)) ": " (:content %))
                                  critiques))
                  refined  (sp/await
                             (ask room mediator-id
                                  {:content (str "Draft: " draft
                                                 "\nCritiques:\n" feedback)}))]
              (recur (inc round) (:content refined)))))))))

;; ============================================================================
;; Step 4 — simulate-reply: the theory-of-mind primitive (§6.5)
;; ============================================================================

(defn simulate-reply
  "Fork the room, ask `other-id` a hypothetical message, capture their reply,
   discard the fork. Parent untouched. The whole theory-of-mind affordance.

   Cheap: O(1) fork + one LLM call. Composes with everything — an
   iterative-refinement step can simulate the critic's response before
   committing; a debate participant can imagine the panel's reaction before
   speaking; a moderator can run three forked openings."
  [room other-id hypothetical-msg]
  (sp/spin
    (let [fork  (fork-room room)
          reply (sp/await (ask fork other-id hypothetical-msg))]
      (discard fork)
      reply)))

(defn imagine-conversation
  "Fork, run a workflow (a Spin), capture the imagined log, discard.
   Returns {:outcome any :imagined-log [Message]}."
  [room workflow-fn]
  (sp/spin
    (let [fork    (fork-room room)
          outcome (sp/await (workflow-fn fork))
          log     @(:log fork)]
      (discard fork)
      {:outcome outcome :imagined-log log})))

