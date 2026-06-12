(ns scenario-auditor
  "Auditor scenario — a Participant subscribes to a capability tag in
   ADDITION to its [:to id] inbox via `d/subscribe!`.

   The auditor's own messages (addressed to :auditor) flow through the
   default inbox; escalations addressed to other agents flow through the
   extra [:type :escalation/budget] subscription. Both land on the same
   spin via the merge mailbox.

   Run:
     clojure -M:examples -m scenario-auditor"
  (:require [org.replikativ.spindel.core :as sp]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.spin.cps :refer [spin]]
            [dvergr.discourse :as d]))

(defn make-auditor
  "A participant that logs every message it sees, distinguishing inbox
   (:to :auditor) messages from extra-subscription (:type :escalation/*)
   messages."
  [seen-atom]
  (d/participant
   {:id  :auditor
    :on-message
    (fn [_p msg]
      (spin
       (swap! seen-atom conj {:type     (:type msg)
                              :from     (:from msg)
                              :to       (:to msg)
                              :as-inbox (= :auditor (:to msg))})
       nil))}))

(defn make-noisy-agent
  "Posts an escalation on every message it receives."
  [id room]
  (d/participant
   {:id id
    :on-message
    (fn [_p msg]
      (spin
       (d/post! room {:type :escalation/budget
                      :from id
                      :payload {:reason :running-low}})
       nil))}))

(defn -main
  [& _args]
  (let [room  (d/room :audit-demo)
        seen  (atom [])]
    (binding [ec/*execution-context* (:ctx room)]
      (let [auditor (d/join room (make-auditor seen))]
        ;; Add the extra subscription — auditor now also receives
        ;; ALL :escalation/budget messages, not just those :to :auditor.
        (d/subscribe! room auditor [:type :escalation/budget])
        (d/join room (make-noisy-agent :worker-a room))
        (d/join room (make-noisy-agent :worker-b room))))

    (println "=== Auditor scenario ===")
    (println "Posting messages to two workers; they each escalate once.")

    (d/post! room (d/message :user :worker-a "do something"))
    (d/post! room (d/message :user :worker-b "do something else"))
    (d/post! room (d/message :user :auditor  "what have you seen?"))

    (Thread/sleep 300)

    (println)
    (println "Auditor saw" (count @seen) "messages:")
    (doseq [s @seen]
      (println "  " s))

    (println)
    (let [escalations (count (filter #(= :escalation/budget (:type %)) @seen))
          inboxed     (count (filter :as-inbox @seen))]
      (println "  escalations observed:" escalations
               "(NOT addressed to :auditor — came via subscribe!)")
      (println "  inbox messages observed:" inboxed
               "(addressed to :auditor)"))
    (System/exit 0)))
