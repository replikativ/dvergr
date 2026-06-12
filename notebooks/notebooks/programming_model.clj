(ns notebooks.programming-model
  "Live Clay notebook: the compositional kernel — tagged messages, capability
   subscriptions, escalation, and per-consumer buffer policy. No LLM, no network.
   Reuses the scripted participants from
   examples/{scenario_auditor,scenario_manager_escalation,scenario_streaming_partial}.clj
   and drives rooms inline (never calls their `-main`)."
  (:require [notebooks.support :refer [settle]]
            [dvergr.discourse :as d :refer [with-room]]
            [scicloj.kindly.v4.kind :as kind]
            [scenario-auditor :as aud]
            [scenario-manager-escalation :as esc]
            [scenario-streaming-partial :as stream]))

;; # The programming model — a compositional kernel

;; Everything in dvergr composes through **tagged messages** on a small pub/sub
;; kernel. A message's `:type` is its *illocutionary force* — what the utterance
;; does — and participants **subscribe by the kinds of speech act they answer**
;; rather than by hardcoded sender→receiver wiring. (See
;; [discourse-theory.md](../../doc/discourse-theory.md) for that framing.)
;;
;; This notebook shows three faces of that one idea — each runs live, with no
;; model. Every block binds the room's execution context with `with-room`.

;; ## 1. Capability subscription — listen beyond your inbox

;; A `Participant` always receives messages addressed to it (`[:to id]`). It can
;; add a **second subscription** with `d/subscribe!` on a capability *tag* — and
;; then it sees messages it was never addressed in. That is the basis for
;; cross-cutting monitoring (auditors, budget policies) without coupling.

(def audit-room (d/room :audit-demo))
(def seen (atom []))

(kind/hidden
 (with-room audit-room
   (let [auditor (d/join audit-room (aud/make-auditor seen))]
     ;; the extra subscription: ALL :escalation/budget messages, regardless of :to
     (d/subscribe! audit-room auditor [:type :escalation/budget])
     (d/join audit-room (aud/make-noisy-agent :worker-a audit-room))
     (d/join audit-room (aud/make-noisy-agent :worker-b audit-room)))
   nil))

;; Each worker escalates on every message; the auditor also gets one direct
;; message to its inbox.

(kind/hidden
 (with-room audit-room
   (d/post! audit-room (d/message :user :worker-a "do something"))
   (d/post! audit-room (d/message :user :worker-b "do something else"))
   (d/post! audit-room (d/message :user :auditor  "what have you seen?"))
   nil))

(settle 300)

;; `:as-inbox true` = addressed to the auditor; `false` = picked up via the tag
;; subscription. The auditor observed escalations addressed to *other* agents.

(kind/table @seen)

(let [escalations (count (filter #(= :escalation/budget (:type %)) @seen))
      inboxed     (count (filter :as-inbox @seen))]
  (kind/pprint {:escalations-observed escalations   ; via subscribe!, not addressed to :auditor
                :inbox-messages        inboxed        ; addressed to :auditor
                :total-seen            (count @seen)}))

(kind/hidden (with-room audit-room (d/close-room! audit-room) nil))

;; ## 2. Escalation — post a tag, a policy answers

;; The same mechanism drives escalation. `alice` escalates by **posting an
;; `:escalation/budget` message** — not by calling a named manager. A separate
;; `policy-bot`, subscribed on that tag, replies with `:directive/raise-budget`
;; addressed back to her. Neither hardcodes the other; they compose because the
;; room is a tagged-message namespace.

(def budget-room (d/room :budget-demo))
(def budget (atom 50))          ; cents; each reply costs 40
(def wrapping-up? (atom false))

(kind/hidden
 (with-room budget-room
   (d/join budget-room (esc/make-alice budget-room budget wrapping-up?))
   (esc/spawn-policy-bot! budget-room)
   nil))

(kind/md (str "Initial budget: **" @budget " cents**"))

;; Round 1 — enough budget: alice answers, balance drops to 10.

(kind/hidden
 (with-room budget-room
   (d/post! budget-room (d/message :user :alice "what is 2 + 2?")) nil))
(settle 200)
(kind/md (str "After round 1: **" @budget " cents**"))

;; Round 2 — the next reply would cost 40 but only 10 remain, so alice posts
;; `:escalation/budget` instead. policy-bot raises it by 100, addressed back to
;; her; the user retries and the answer goes through.

(kind/hidden
 (with-room budget-room
   (d/post! budget-room (d/message :user :alice "what is 5 * 7?")) nil))
(settle 300)
(kind/hidden
 (with-room budget-room
   (d/post! budget-room (d/message :user :alice "what is 5 * 7?")) nil))
(settle 200)

(kind/pprint {:alice-budget @budget
              :wrapping-up? @wrapping-up?
              :log-size     (count (d/log budget-room))})

;; The log shows alice's `:escalation/budget` and policy-bot's
;; `:directive/raise-budget` addressed back to her.

(kind/table
 (map #(select-keys % [:from :to :type :content]) (d/log budget-room)))

(kind/hidden (with-room budget-room (d/close-room! budget-room) nil))

;; ## 3. Buffer policy — one stream, per-consumer SLAs

;; Tags also carry *streams*. One producer fires 50 `:partial/token` messages;
;; two consumers tap the **same** `[:type :partial/token]` topic with
;; **different buffer policies**:
;;
;; - **audit** — the `:partial` default (`fixed-buffer 256`): tokens are discrete
;;   data, so the default is lossless.
;; - **UI** — overridden to `sliding-buffer 1`: a view that only wants the latest
;;   accumulated state opts *into* lossy semantics, per subscription.
;;
;; The producer is unaware of either; each consumer picks its own SLA.

(def stream-room (d/room :stream-demo))
(def ui-got (atom []))
(def audit-got (atom []))
(def n 50)

;; Pubs start their pump on first subscription, so consumers come first. The UI
;; consumer sleeps 5ms/token to simulate a slow re-render.

(kind/hidden
 (do (stream/spawn-ui-consumer!    stream-room ui-got)
     (stream/spawn-audit-consumer! stream-room audit-got)
     (stream/spawn-token-producer! stream-room n)
     nil))
(settle (+ 200 (* 5 n)))

;; Audit consumer — lossless, sees every token (count = n):

(kind/pprint {:tokens-seen (count @audit-got)
              :expected    n
              :first-10    (vec (take 10 @audit-got))})

;; UI consumer — lossy on purpose, sees only a sample (varies run-to-run):

(kind/pprint {:tokens-seen (count @ui-got) :sample @ui-got})

;; The room log keeps everything regardless of per-consumer policy:

(kind/pprint {:log-size (count (d/log stream-room)) :expected n})

(kind/hidden (with-room stream-room (d/close-room! stream-room) nil))

;; ## Where to go next
;;
;; - **Forks & proposals** — fork a room, let a worker change a branched git +
;;   datahike, then `merge!` or `discard` atomically (`forks_and_proposals`).
;; - **Agents & tools** — a real LLM agent, the `clojure_eval` sandbox, the
;;   muschel shell, budgets, and context compaction (`agents_and_tools`).
;;
;; The deeper algebra — `ask` / `fan-out` / `race` / `quorum` / `pipeline` and
;; the theory-of-mind `simulate-reply` — is in
;; [programming-model.md](../../doc/programming-model.md) and
;; [discourse-theory.md](../../doc/discourse-theory.md).
