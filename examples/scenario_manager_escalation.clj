(ns scenario-manager-escalation
  "Compositional kernel scenario: budget escalation via tagged messages.

   Demonstrates that the agent (`alice`) escalates by POSTING A TAGGED
   MESSAGE, not by calling a named manager. The handler — `policy-bot` —
   is a separate subscription on `[:type :escalation/budget]` that
   replies with a `:directive/raise-budget` *addressed back to alice*.

   alice's on-message has no awareness of who handles escalations. The
   policy-bot has no awareness of who escalates. They compose because
   the room is a tagged-message namespace.

   Run:
     cd ../dvergr
     clojure -M:local -m scenario-manager-escalation

   What you should see:
     1. alice processes a user msg → spends $0.40
     2. budget drops below threshold → escalates
     3. policy-bot raises budget by $1.00
     4. alice continues processing"
  (:require [is.simm.partial-cps.sequence :as aseq]
            [org.replikativ.spindel.core :as sp]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.spin.cps :refer [spin]]
            [org.replikativ.spindel.spin.sync :as sync]
            [org.replikativ.spindel.effects.await :refer [await]]
            [dvergr.bus :as bus]
            [dvergr.discourse :as d]))

;; ============================================================================
;; alice — a worker participant with a budget
;; ============================================================================

(defn make-alice
  [room budget-atom wrapping-up?]
  (d/participant
    {:id  :alice
     :ctx (:ctx room)
     :on-message
     (fn [_p msg]
       (spin
         (case (:type msg)

           ;; --- a regular user/agent message ---
           (:user/message :agent/message)
           (let [cost 40]                           ; cents
             (cond
               @wrapping-up?
               {:to (:from msg) :content "[wrapping up] short reply"}

               (< @budget-atom cost)
               (do
                 (println "  alice: budget" @budget-atom "below cost" cost "— escalating")
                 (d/post! room {:type :escalation/budget
                                :from :alice
                                :payload {:remaining @budget-atom
                                          :requested 100}
                                :metadata {:capability-required :budget-decision}})
                 nil)

               :else
               (do
                 (swap! budget-atom - cost)
                 (println "  alice: replied to" (:from msg) "— spent" cost "remaining" @budget-atom)
                 {:to (:from msg) :content (str "alice's reply (#" cost " spent)")})))

           ;; --- manager raised our budget ---
           :directive/raise-budget
           (let [amount (get-in msg [:payload :amount] 100)]
             (swap! budget-atom + amount)
             (println "  alice: received raise-budget +" amount "→" @budget-atom)
             nil)

           ;; --- manager told us to wrap up ---
           :directive/wrap-up
           (do
             (reset! wrapping-up? true)
             (println "  alice: received wrap-up; will keep replies short")
             nil)

           ;; unknown — ignore
           nil)))}))

;; ============================================================================
;; policy-bot — a bus-only handler subscribed by capability tag
;; ============================================================================

(defn spawn-policy-bot!
  "Subscribe directly to the bus on `[:type :escalation/budget]`. For each
   escalation, post back a `:directive/raise-budget` to the requester.

   Note: NOT a discourse Participant — it has no :to inbox of its own.
   It is a bus-level handler. This is the compositional kernel: the
   programming model lets us address handlers by capability without
   participant ceremony."
  [room]
  (let [sub (binding [ec/*execution-context* (:ctx room)]
              (bus/subscribe! (:bus room) [:type :escalation/budget]))]
    (binding [ec/*execution-context* (:ctx room)]
      (sp/spawn!
        (spin
          (loop [s (:aseq sub)]
            (when-let [r (await (aseq/anext s))]
              (let [[msg rest-s] r]
                (println "  policy-bot: saw escalation from" (:from msg)
                         "with payload" (:payload msg))
                ;; Decision: always raise the budget by $1.
                (d/post! room {:to (:from msg)
                               :type :directive/raise-budget
                               :from :policy-bot
                               :payload {:amount 100}})
                (recur rest-s)))))))
    sub))

;; ============================================================================
;; main
;; ============================================================================

(defn -main
  [& _args]
  (let [room (d/room :budget-demo)
        budget (atom 50)        ; cents
        wrapping-up? (atom false)]
    (binding [ec/*execution-context* (:ctx room)]
      (d/join room (make-alice room budget wrapping-up?))
      (spawn-policy-bot! room))

    (println "=== Manager-escalation scenario ===")
    (println "Initial: budget =" @budget)
    (println)

    ;; Round 1: enough budget — alice replies directly.
    (println "→ User asks alice question 1")
    (d/post! room (d/message :user :alice "what is 2 + 2?"))
    (Thread/sleep 200)

    ;; Round 2: budget is now too low — alice escalates; policy-bot raises.
    (println "→ User asks alice question 2")
    (d/post! room (d/message :user :alice "what is 5 * 7?"))
    (Thread/sleep 300)
    ;; Re-send because the first reply was suppressed by escalation.
    (println "→ User retries question 2 (now that budget was raised)")
    (d/post! room (d/message :user :alice "what is 5 * 7?"))
    (Thread/sleep 200)

    (println)
    (println "=== Final state ===")
    (println "  alice's budget: " @budget)
    (println "  wrapping up?    " @wrapping-up?)
    (println "  log size:       " (count (d/log room)))
    (println)
    (println "Log:")
    (doseq [m (d/log room)]
      (println "  " (select-keys m [:from :to :type :content])))

    (System/exit 0)))
