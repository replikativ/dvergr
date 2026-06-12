(ns dvergr.adapters.core-test
  "Tests for the transport-agnostic medium adapter routing topology.

   Uses ephemeral discourse rooms + an injected recording send-fn + an
   echo agent standing in for the routed agent (:var). No network, no
   persistence — the datahike/room provisioning is injected via
   :ensure-room / :ensure-actor, so these tests exercise only the
   inbound→room-as-user / agent-reply→egress→send topology."
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.adapters.core :as adapters]
            [dvergr.discourse :as d]
            [org.replikativ.spindel.engine.core :as rtc]
            [org.replikativ.spindel.engine.context :as ctx]))

(defn- await-until
  "Spin-poll a predicate up to ~2s (the egress send happens on the
   participant spin executor, asynchronously)."
  [pred]
  (loop [n 0]
    (cond (pred)      true
          (>= n 200)  false
          :else       (do (Thread/sleep 10) (recur (inc n))))))

(defn- setup
  "Build an adapter over a single shared room with an echo agent joined as
   :var. Returns {:adapter :room :sent} where :sent is an atom of
   [[chat-id text] …] recording egress sends."
  []
  (let [c    (ctx/create-execution-context)
        room (binding [rtc/*execution-context* c] (d/room :tg-100 c))
        sent (atom [])]
    ;; Echo agent stands in for :var — replies to whoever asked.
    (binding [rtc/*execution-context* c]
      (d/join room (d/echo :var c)))
    {:ctx  c
     :room room
     :sent sent
     :adapter
     (adapters/make-adapter
      {:ctx          c
       :default-agent :var
       :send-fn      (fn [chat-id text] (swap! sent conj [chat-id text]))
       :ensure-room  (fn [_chat-id] room)
       :ensure-actor (fn [user] (:id user))})}))

(deftest inbound-posts-as-user-and-routes-reply-to-egress
  (testing "inbound message is authored by the user-actor and the agent's reply is sent back to the venue"
    (let [{:keys [adapter room sent]} (setup)
          actor-id (adapters/inbound! adapter
                                      {:chat-id 100 :user {:id :alice} :text "ping"})]
      (is (= :alice actor-id))
      ;; echo replies "echo: ping" :to :alice → routes to alice's egress
      ;; participant → send-fn records it, speaker-prefixed with the sender (:var,
      ;; via the default name resolver).
      (is (await-until #(seq @sent)))
      (is (= [[100 "[var] echo: ping"]] @sent))
      ;; The inbound message is in the room, authored by the user-actor.
      (let [log (binding [rtc/*execution-context* (:ctx room)] (d/messages room))]
        (is (some #(and (= :alice (:from %)) (= "ping" (:content %))) log))))))

(deftest mirror-started-once-per-venue
  (testing "one room mirror per venue regardless of user count; all replies go to the venue"
    (let [{:keys [adapter room sent]} (setup)]
      (adapters/inbound! adapter {:chat-id 100 :user {:id :alice} :text "one"})
      (adapters/inbound! adapter {:chat-id 100 :user {:id :alice} :text "two"})
      (adapters/inbound! adapter {:chat-id 100 :user {:id :bob}   :text "three"})
      (is (await-until #(>= (count @sent) 3)))
      ;; ONE mirror for the venue room — not one egress-per-user.
      (is (= #{(:id room)} @(:mirrored adapter)))
      ;; the venue's own inbound (alice/bob "one"/"two"/"three") is injected, so
      ;; it is NOT echoed back — only the agent's replies are relayed.
      (is (not-any? #(#{"one" "two" "three"} (second %)) @sent))
      ;; every relayed message went back to the same venue chat-id 100.
      (is (every? #(= 100 (first %)) @sent)))))

(deftest mirror-relays-reply-addressed-to-another-participant
  (testing "an agent reply addressed to ANOTHER participant (a TUI user, not the
            venue user) is STILL relayed — the :to-filtered-egress silent-drop bug"
    (let [{:keys [adapter room sent ctx]} (setup)]
      ;; First inbound starts the mirror; alice's echo reply confirms it's live.
      (adapters/inbound! adapter {:chat-id 100 :user {:id :alice} :text "hi"})
      (is (await-until #(seq @sent)))
      (reset! sent [])
      ;; An agent posts a reply addressed :to :local (a DIFFERENT participant —
      ;; e.g. a TUI user — not the venue user alice). The old egress only saw
      ;; [:to me]/[:to nil], so it dropped this; the mirror relays it.
      (binding [rtc/*execution-context* ctx]
        (d/post! room (d/message :var :local "reply to the TUI user" nil)))
      (is (await-until #(seq @sent)) "reply :to another participant was relayed to the venue")
      (is (= [[100 "[var] reply to the TUI user"]] @sent)))))

(deftest rich-adapter-no-egress-just-posts
  (testing "a rich adapter (no :send-fn) posts inbound as the user-actor but joins NO egress participant"
    (let [c    (ctx/create-execution-context)
          room (binding [rtc/*execution-context* c] (d/room :rich-1 c))
          adapter (adapters/make-adapter
                   {:ctx c
                    :default-agent :var
                     ;; no :send-fn → rich adapter
                    :ensure-room  (fn [_] room)
                    :ensure-actor (fn [user] (:id user))})
          actor-id (adapters/inbound! adapter
                                      {:chat-id 1 :user {:id :alice} :text "rich hello"})]
      (is (= :alice actor-id))
      (is (empty? @(:joined adapter)) "no egress participant joined")
      (is (await-until
           #(some (fn [m] (and (= :alice (:from m)) (= "rich hello" (:content m))))
                  (binding [rtc/*execution-context* c] (d/messages room))))
          "inbound posted as the user-actor for the frontend to observe"))))

(deftest empty-text-is-ignored
  (testing "blank / non-string inbound is dropped"
    (let [{:keys [adapter sent]} (setup)]
      (is (nil? (adapters/inbound! adapter {:chat-id 100 :user {:id :alice} :text "   "})))
      (is (nil? (adapters/inbound! adapter {:chat-id 100 :user {:id :alice} :text nil})))
      (is (empty? @sent)))))
