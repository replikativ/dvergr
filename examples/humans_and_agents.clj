(ns humans-and-agents
  "End-to-end example: humans + agents in a long-lived dvergr.discourse Room.

   Demonstrates the four primitives that build the foundation for the
   coding-agent workflows you find in opencode, openclaw, and Claude
   Code's subagent mode:

   1. **Humans as discourse participants** — `dvergr.discourse.human`.
      The human's id is a routing endpoint; agents address them
      directly; an `on-receive` callback hands each inbox message off
      to whatever transport the embedding app provides (here: stdout).

   2. **Mention-routed posting** — broadcast vs `@`-addressed routing,
      driven by parsing the user's message. Patterns the simmis chat
      ships with.

   3. **Background tasks with notifications** —
      `dvergr.discourse.background/spawn-task!`. Fire-and-forget a goal
      to an agent, get a typed `:notification/type :task-complete`
      message back in the human's inbox when done — Claude-Code-style
      'I ran it in the background, ping when ready'.

   4. **Branching + merging via proposals** — `dvergr.proposals/propose!`
      + `accept-proposal!` / `reject-proposal!`. Fork the room, let an
      agent work in isolation, hand the human the result; accept merges
      the fork, reject discards. This is the multi-shot 'agent
      proposes, human approves' pattern that opencode / coding agents
      run on every diff.

   Run as an alias (`clojure -X humans-and-agents/run`) or step through
   in a REPL. Uses scripted mock agents only — no API keys, no network.

   To extend with a real LLM, swap the scripted participants for
   `(dvergr.discourse.llm/llm-agent {:spec {:provider :anthropic :model
   \"claude-sonnet-4-6\" :system-prompt \"…\"} :tools #{:web_fetch
   :clojure_eval}})`. The discourse primitives don't change."
  (:refer-clojure :exclude [await])
  (:require [datahike.api :as dh]
            [dvergr.discourse :as d]
            [dvergr.discourse.human :as human]
            [dvergr.discourse.background :as bg]
            [dvergr.proposals :as proposals]
            [dvergr.chat.schema :as schema]
            [org.replikativ.spindel.core :as sp :refer [spin await]]
            [org.replikativ.spindel.engine.core :as ec]))

;; ===========================================================================
;; A tiny scripted "agent" — replays a sequence of replies in order.
;; In a real app this is dvergr.discourse.llm/llm-agent (Anthropic /
;; Fireworks / OpenAI / local). The shape is identical.
;; ===========================================================================

(defn scripted-agent
  "Participant that, on each inbound message, returns the next entry of
   `replies`. When exhausted, emits nothing. Has a `:factory` so
   `fork-room` clones it correctly."
  [id replies]
  (let [remaining (atom (vec replies))]
    (d/participant
      {:id id
       :on-message (fn [_p msg]
                     (spin
                       (when-let [next-reply (first @remaining)]
                         (swap! remaining subvec 1)
                         {:to (:from msg) :content next-reply})))
       :factory (fn [new-ctx]
                  (binding [ec/*execution-context* new-ctx]
                    (scripted-agent id (vec @remaining))))})))

;; ===========================================================================
;; Helper: humans-on-receive that just prints. Real apps swap this for
;; a WebSocket push, a DB transact, a desktop notification, etc.
;; ===========================================================================

(defn- println-receive [user-tag]
  (fn [msg]
    (let [nt (some-> msg :metadata :notification/type)]
      (cond
        nt
        (println (format "[%s] (notification :%s) %s — from %s"
                         user-tag (name nt) (:content msg) (:from msg)))
        :else
        (println (format "[%s] <- %s: %s"
                         user-tag (:from msg) (:content msg)))))))

;; ===========================================================================
;; Walk through each piece
;; ===========================================================================

(defn demo-1-humans-and-agents
  "Two humans, one agent, mention-routed broadcast vs addressed."
  []
  (println "\n=== Demo 1: humans + agents in a room ===")
  (let [room (d/room :demo-1)
        alice (binding [ec/*execution-context* (:ctx room)]
                (human/human-participant
                  {:id :alice :on-receive (println-receive "alice")}))
        bob   (binding [ec/*execution-context* (:ctx room)]
                (human/human-participant
                  {:id :bob :on-receive (println-receive "bob")}))
        sage  (binding [ec/*execution-context* (:ctx room)]
                (scripted-agent :sage ["The weather is fine."
                                       "Cardinality is 3."
                                       "I'd suggest pair programming."]))]
    (d/join room alice)
    (d/join room bob)
    (d/join room sage)
    ;; Alice asks sage directly.
    (d/post! room (d/message :alice :sage "What's the weather?" nil nil))
    ;; Bob asks sage.
    (d/post! room (d/message :bob :sage "Cardinality of a 3-set?" nil nil))
    (Thread/sleep 200)
    (println "(both humans saw sage's replies addressed to them)")
    (d/close-room! room)))

(defn demo-2-background-with-notification
  "Alice fires a background task; sage works on it; alice gets a
   :task-complete notification when done."
  []
  (println "\n=== Demo 2: background task + notification ===")
  (let [room (d/room :demo-2)
        alice (binding [ec/*execution-context* (:ctx room)]
                (human/human-participant
                  {:id :alice :on-receive (println-receive "alice")}))
        sage  (binding [ec/*execution-context* (:ctx room)]
                (scripted-agent :sage ["Sources reviewed; PR ready."]))]
    (d/join room alice)
    (d/join room sage)
    ;; Alice doesn't block — she kicks off the task and goes about her day.
    (binding [ec/*execution-context* (:ctx room)]
      (bg/spawn-task!
        {:room   room
         :agent  :sage
         :task   {:content "Research X and prepare a PR"}
         :notify :alice
         :metadata {:project "X"}}))
    (Thread/sleep 200)
    (println "(alice received a :task-complete notification when sage was done)")
    (d/close-room! room)))

(defn demo-3-propose-accept
  "Alice asks coder for a feature; coder works in a forked room and posts
   a proposal; alice accepts; the fork merges into the parent room's log."
  []
  (println "\n=== Demo 3: propose → accept (fork → merge) ===")
  ;; In-memory datahike just for the proposal schema. In simmis this is
  ;; the room DB; in dvergr CLI this might be ~/.dvergr/proposals.
  (let [cfg {:store {:backend :memory :id (random-uuid)}}
        _ (dh/create-database cfg)
        conn (dh/connect cfg)
        _ (schema/ensure-full-schema! conn)
        room (d/room :demo-3)
        alice (binding [ec/*execution-context* (:ctx room)]
                (human/human-participant
                  {:id :alice :on-receive (println-receive "alice")}))
        coder (binding [ec/*execution-context* (:ctx room)]
                (scripted-agent :coder ["Proposed: add :feature/x with default {}"]))]
    (d/join room alice)
    ;; coder is NOT joined to the parent — propose! joins it into the fork only.
    (binding [ec/*execution-context* (:ctx room)]
      (let [proposal @(proposals/propose!
                        {:room   room
                         :worker coder
                         :goal   "design :feature/x"
                         :conn   conn})]
        (println "(alice sees proposal:" (:proposal/summary proposal) ")")
        ;; Alice approves; the fork's log merges into the parent.
        (let [outcome (proposals/accept-proposal! conn (:proposal/id proposal))]
          (println "(accept outcome:" outcome ")")
          (println "(parent room log size:" (count @(:log room)) ")"))))
    (d/close-room! room)
    (dh/release conn)))

(defn demo-4-propose-reject
  "Same setup but alice rejects — fork is discarded, parent untouched."
  []
  (println "\n=== Demo 4: propose → reject (fork → discard) ===")
  (let [cfg {:store {:backend :memory :id (random-uuid)}}
        _ (dh/create-database cfg)
        conn (dh/connect cfg)
        _ (schema/ensure-full-schema! conn)
        room (d/room :demo-4)
        alice (binding [ec/*execution-context* (:ctx room)]
                (human/human-participant
                  {:id :alice :on-receive (println-receive "alice")}))
        coder (binding [ec/*execution-context* (:ctx room)]
                (scripted-agent :coder ["Proposed: rename :feature/x to :feature/y"]))]
    (d/join room alice)
    (binding [ec/*execution-context* (:ctx room)]
      (let [proposal @(proposals/propose!
                        {:room   room
                         :worker coder
                         :goal   "what should :feature/x be named?"
                         :conn   conn})]
        (println "(alice sees proposal:" (:proposal/summary proposal) ")")
        (let [outcome (proposals/reject-proposal! conn (:proposal/id proposal))]
          (println "(reject outcome:" outcome ")")
          (println "(parent room log size — should be unchanged:"
                   (count @(:log room)) ")"))))
    (d/close-room! room)
    (dh/release conn)))

(defn run
  "Run all four demos sequentially. Invoke from `clojure -X` or REPL.

       clojure -X:dev humans-and-agents/run"
  [& _]
  (demo-1-humans-and-agents)
  (demo-2-background-with-notification)
  (demo-3-propose-accept)
  (demo-4-propose-reject)
  (println "\nDone."))
