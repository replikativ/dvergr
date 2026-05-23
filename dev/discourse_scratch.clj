(ns dev.discourse-scratch
  "Scratch: Steps 1-2 of dvergr/doc/discourse-model.md §11.

   Step 1: Message / Participant / Room with mailbox-driven spins.
   Step 2: ask / fan-out / race / quorum / pipeline + with-latency / flaky.

   `on-message` now returns a Spin[ReplySpec | nil] so handlers can sleep,
   await sub-spins, throw — all spin-y things — without blocking the loop."
  (:require [org.replikativ.spindel.core :as sp]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.spin.sync :as sync]
            [org.replikativ.spindel.spin.combinators :as comb]))

;; ============================================================================
;; Records
;; ============================================================================

(defrecord Message     [id from to content ts])
(defrecord Participant [id inbox state on-message process])
(defrecord Room        [id participants log ctx])

(defn new-message [from to content]
  (->Message (random-uuid) from to content (System/currentTimeMillis)))

;; ============================================================================
;; Room delivery
;; ============================================================================

(defn route-and-log!
  "Internal: append to log; deliver to addressee's mailbox if present."
  [room msg]
  (swap! (:log room) conj msg)
  (when-let [target (get @(:participants room) (:to msg))]
    (sync/post! (:inbox target) msg))
  msg)

(defn post!
  "Public post: route a Message into the room."
  [room msg]
  (route-and-log! room msg))

(defn room
  [id]
  (let [ctx (sp/create-execution-context)]
    (->Room id (atom {}) (atom []) ctx)))

;; ============================================================================
;; Participant spin (continuous-time, mailbox-delta-driven, §5.1)
;;
;; on-message returns a Spin yielding {:to id :content str} or nil.
;; The loop awaits that spin — so handlers can sleep, branch, await sub-spins
;; without blocking.
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
  [{:keys [id on-message ctx]}]
  (let [ctx   (or ctx ec/*execution-context*)
        inbox (sync/create-mailbox ctx)
        state (atom :idle)]
    (->Participant id inbox state on-message nil)))

(defn script
  "Scripted participant. replies is a vector of {:to id :content str}; each
   incoming message consumes the next reply. When exhausted, emits nothing."
  [id replies ctx]
  (let [remaining (atom (vec replies))]
    (participant
      {:id id :ctx ctx
       :on-message
       (fn [_p _msg]
         (sp/spin
           (when-let [next-reply (first @remaining)]
             (swap! remaining subvec 1)
             next-reply)))})))

(defn echo
  "Echo participant: replies to sender with 'echo: ' prefix."
  [id ctx]
  (participant
    {:id id :ctx ctx
     :on-message
     (fn [_p msg]
       (sp/spin
         {:to (:from msg) :content (str "echo: " (:content msg))}))}))

;; ============================================================================
;; Step 2 — Combinators (algebraic primitives over the substrate)
;; ============================================================================

(defn ask
  "Send msg-content to target-id, await their reply. Returns Spin[Message].
   Creates a transient observer participant; leaks one mailbox per ask
   until Step 3 lifecycle adds proper leave/stop."
  [room target-id msg-spec]
  (sp/spin
    (let [asker-id (keyword (str "ask-" (subs (str (random-uuid)) 0 8)))
          d        (binding [ec/*execution-context* (:ctx room)]
                     (sync/create-deferred (:ctx room)))
          asker    (participant
                     {:id asker-id
                      :ctx (:ctx room)
                      :on-message (fn [_ msg]
                                    (sp/spin
                                      (sync/deliver! d msg)
                                      nil))})
          _        (join room asker)
          _        (post! room (new-message asker-id target-id (:content msg-spec)))
          reply    (sp/await d)]
      (swap! (:participants room) dissoc asker-id)
      reply)))

(defn fan-out
  "Parallel ask to all targets; awaits all replies. Returns Spin[Vector[Message]]."
  [room targets msg-spec]
  (sp/spin
    (sp/await (apply comb/parallel (mapv #(ask room % msg-spec) targets)))))

(defn race
  "Send to all targets; return the first reply. Losers cancelled.
   Returns Spin[Message]."
  [room targets msg-spec]
  (sp/spin
    (sp/await (apply comb/race (mapv #(ask room % msg-spec) targets)))))

(defn quorum
  "Send to all targets; return the first n replies. Returns Spin[Vector[Message]]."
  [room targets msg-spec n]
  (sp/spin
    (let [d         (sync/create-deferred (:ctx room))
          collected (atom [])
          n-targets (count targets)]
      (doseq [target targets]
        (sp/spawn!
          (sp/spin
            (let [reply (sp/await (ask room target msg-spec))
                  current (swap! collected conj reply)]
              (when (= n (count current))
                (sync/deliver! d current))))))
      (sp/await d))))

(defn pipeline
  "Chain ask through targets: each reply becomes the next's content.
   Returns Spin[Message] (the final reply)."
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
;; Step 2 — Wrappers (decorators on participants)
;; ============================================================================

(defn with-latency
  "Wrap a participant so its on-message handler awaits base-ms + jitter random
   before running. Replies are delayed; the loop is non-blocking because we
   await sp/sleep rather than Thread/sleep."
  [p {:keys [base-ms jitter-ms] :or {jitter-ms 0}}]
  (let [orig-handler (:on-message p)]
    (assoc p :on-message
           (fn [pp msg]
             (sp/spin
               (let [delay (+ base-ms (long (rand jitter-ms)))]
                 (sp/await (comb/sleep delay))
                 (sp/await (orig-handler pp msg))))))))

(defn flaky
  "Wrap participant with simulated errors. With probability error-rate, replies
   with [ERROR: simulated]. With probability timeout-rate (out of remainder),
   replies after a 30s simulated timeout. Otherwise the original handler runs."
  [p {:keys [error-rate timeout-rate] :or {error-rate 0.1 timeout-rate 0.0}}]
  (let [orig-handler (:on-message p)]
    (assoc p :on-message
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
                   (sp/await (orig-handler pp msg)))))))))

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
      (when initial-msg
        (post! r initial-msg))
      (Thread/sleep wait-ms)
      {:room r
       :log @(:log r)
       :participant-states (into {} (for [[id p] @(:participants r)]
                                      [id @(:state p)]))})))

(comment
  ;; ---- Step 1 smoke (regression) ------------------------------------------
  (run-scenario
    {:participants-spec [{:kind :echo :id :echo-bot}]
     :initial-msg       (new-message :test-driver :echo-bot "hello")
     :wait-ms 300})

  (run-scenario
    {:participants-spec
      [{:kind :script :id :alice
        :replies [{:to :bob :content "Hello Bob"}
                  {:to :bob :content "Goodbye Bob"}]}
       {:kind :script :id :bob
        :replies [{:to :alice :content "Hi Alice"}
                  {:to :alice :content "Bye Alice"}]}]
     :initial-msg (new-message :test-driver :alice "kickoff")})

  ;; ---- Step 2: ask -----------------------------------------------------
  (let [r (room :ask-test)]
    (binding [ec/*execution-context* (:ctx r)]
      (join r (echo :echo-bot (:ctx r)))
      (let [reply-spin (ask r :echo-bot {:content "ping"})]
        (sp/spawn! reply-spin)
        (Thread/sleep 200)
        @(:log r))))

  ;; ---- Step 2: pipeline a → b → c ---------------------------------------
  ;; (each step transforms the content; here all three are echoes)
  )
