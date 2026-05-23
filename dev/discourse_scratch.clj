(ns dev.discourse-scratch
  "Scratch: Step 1 of dvergr/doc/discourse-model.md §11.

   Minimum-viable discourse skeleton — Message / Participant / Room with
   mailbox-driven participant spins. Validate: 2 scripted participants
   exchange messages and the room log captures them in order.

   Run via REPL on port 47899."
  (:require [org.replikativ.spindel.core :as sp]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.spin.sync :as sync]))

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
;;
;; post! is the only state-mutating primitive: it appends to the room log AND
;; routes the message into the addressee's mailbox. Cascades happen naturally
;; as participant spins react to inbox arrivals.
;; ============================================================================

(declare route-and-log!)

(defn room
  "Create a fresh room with its own execution context."
  [id]
  (let [ctx (sp/create-execution-context)]
    (->Room id (atom {}) (atom []) ctx)))

(defn route-and-log!
  "Internal: append msg to log; deliver to addressee's mailbox if present."
  [room msg]
  (swap! (:log room) conj msg)
  (when-let [target (get @(:participants room) (:to msg))]
    (sync/post! (:inbox target) msg))
  msg)

(defn post!
  "Public post: route a Message into the room."
  [room msg]
  (route-and-log! room msg))

;; ============================================================================
;; Participants
;;
;; A participant has a mailbox (inbox), a state atom, and an on-message handler
;; that, given an incoming message, returns either:
;;   - nil (no reply)
;;   - {:to <id> :content <str>}   (a reply spec; from/id/ts auto-filled)
;;
;; Its spin is started via spawn! and loops forever: await mailbox → handle →
;; route reply through the room → recur. This is the §5.1 continuous-time
;; participant pattern in its smallest form.
;; ============================================================================

(defn- participant-spin
  "Long-running participant loop. Captures `room` so replies are routed
   back through the same delivery medium."
  [p room]
  (sp/spin
    (loop []
      (let [msg (sp/await (:inbox p))]
        (reset! (:state p) :thinking)
        (let [reply-spec ((:on-message p) p msg)]
          (when reply-spec
            (let [reply (new-message (:id p) (:to reply-spec) (:content reply-spec))]
              (route-and-log! room reply))))
        (reset! (:state p) :idle))
      (recur))))

(defn join
  "Add a participant to a room and spawn its spin."
  [room p]
  (swap! (:participants room) assoc (:id p) p)
  (binding [ec/*execution-context* (:ctx room)]
    (let [proc (participant-spin p room)]
      (sp/spawn! proc)
      (assoc p :process proc))))

;; ============================================================================
;; Built-in participant kinds
;; ============================================================================

(defn participant
  "Build a base Participant. Caller supplies `on-message`."
  [{:keys [id on-message ctx]}]
  (let [ctx   (or ctx ec/*execution-context*)
        inbox (sync/create-mailbox ctx)
        state (atom :idle)]
    (->Participant id inbox state on-message nil)))

(defn script
  "Scripted participant. `replies` is a vector of {:to id :content str};
   each incoming message consumes the next reply. When exhausted, emits nothing."
  [id replies ctx]
  (let [remaining (atom (vec replies))
        on-message (fn [_p _msg]
                     (when-let [next-reply (first @remaining)]
                       (swap! remaining subvec 1)
                       next-reply))]
    (participant {:id id :on-message on-message :ctx ctx})))

(defn echo
  "Echo participant: replies to whoever sent the message with the same content,
   prefixed by 'echo: '. Useful for ping-pong tests."
  [id ctx]
  (participant
    {:id id :ctx ctx
     :on-message (fn [_p msg]
                   {:to (:from msg) :content (str "echo: " (:content msg))})}))

;; ============================================================================
;; Scenario builder for tests
;; ============================================================================

(defn run-scenario
  "Create a room, join participants, kick off with `initial-msg`, wait `wait-ms`,
   return the room log. Useful one-shot test driver."
  [{:keys [participants-spec initial-msg wait-ms]
    :or {wait-ms 500}}]
  (let [r (room :test)]
    (binding [ec/*execution-context* (:ctx r)]
      (doseq [p-spec participants-spec]
        (let [p (case (:kind p-spec)
                  :script (script (:id p-spec) (:replies p-spec) (:ctx r))
                  :echo   (echo   (:id p-spec) (:ctx r)))]
          (join r p)))
      (post! r initial-msg)
      (Thread/sleep wait-ms)
      {:log @(:log r)
       :participant-states (into {} (for [[id p] @(:participants r)]
                                      [id @(:state p)]))})))

(comment
  ;; --- Smoke test 1 — echo participant, single bounce
  (run-scenario
    {:participants-spec [{:kind :echo :id :echo-bot}]
     :initial-msg       (new-message :test-driver :echo-bot "hello")
     :wait-ms 300})
  ;; expected: log has 2 messages — the initial one (driver → echo-bot)
  ;;           and echo-bot's reply (echo-bot → test-driver)

  ;; --- Smoke test 2 — two scripted participants ping-pong
  (run-scenario
    {:participants-spec
      [{:kind :script :id :alice
        :replies [{:to :bob :content "Hello Bob"}
                  {:to :bob :content "How are you Bob?"}
                  {:to :bob :content "Goodbye Bob"}]}
       {:kind :script :id :bob
        :replies [{:to :alice :content "Hi Alice"}
                  {:to :alice :content "Doing well, thanks"}
                  {:to :alice :content "Bye Alice"}]}]
     :initial-msg (new-message :test-driver :alice "kickoff")
     :wait-ms     500}))
