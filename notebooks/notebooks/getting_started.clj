(ns notebooks.getting-started
  "Live Clay notebook: a from-zero tour of the dvergr.discourse kernel —
   rooms, participants, tagged messages, capability subscriptions, and the
   fork-and-merge proposal. Runs inline; no API keys, no network."
  (:require [notebooks.support :refer [settle]]
            [org.replikativ.spindel.spin.cps :refer [spin]]
            [dvergr.discourse :as d :refer [with-room]]
            [scicloj.kindly.v4.kind :as kind]))

;; # Getting started with dvergr

;; authors: Christian Weilbach

;; **dvergr** is a Clojure framework for *continuous-time collaborative
;; multi-agent rooms*. Agents are reactive processes; humans, LLMs, and scripts
;; are participants of the **same shape**; everything composes through tagged
;; messages on a small pub/sub kernel.
;;
;; This notebook builds that kernel up from nothing — no LLM required. Each form
;; runs live; the outputs you see are produced by evaluating the code.

;; ## A room
;;
;; A **Room** is a continuous-time message namespace with its own execution
;; context (`:ctx` — a forkable spindel runtime + a Datahike database). Every
;; operation on a room runs *inside* that context; we bind it once per block with
;; `with-ctx` (the `dvergr.clients.client` surface binds it for you — here we work
;; with the raw algebra to see the kernel directly).

(def room (d/room :hello))

;; ## A participant
;;
;; A **Participant** has an `:id` (its routing endpoint) and an `:on-message`
;; that returns a `spin` yielding a reply-spec `{:to … :content …}` (or `nil` to
;; stay silent). This one just echoes — swap it for `dvergr.discourse.llm/llm-agent`
;; and the shape is identical.

(defn echo-agent [id]
  (d/participant
   {:id id
    :on-message (fn [_p msg]
                  (spin {:to (:from msg) :content (str "you said: " (:content msg))}))}))

(kind/hidden
 (with-room room
   (d/join room (echo-agent :sage))
   nil))

;; ## Post a message
;;
;; `d/message` builds `[from to content]`; `d/post!` routes it. The agent's spin
;; runs asynchronously, so we let the engine `settle` before reading the log.

(with-room room
  (d/post! room (d/message :you :sage "hello, dvergr")))

(settle)

;; ## Read the log
;;
;; Every post is recorded. The agent's reply is addressed back to `:you`.

(kind/table
 (mapv #(select-keys % [:from :to :content]) (d/log room)))

;; ## Tagged routing — address a *capability*, not a name
;;
;; Messages carry a `:type`. A participant can subscribe to a **tag** in addition
;; to its inbox, so it receives messages it was never directly addressed in. This
;; is how cross-cutting concerns (auditors, budget policies) compose without the
;; sender knowing who handles them.

(def audit-log (atom []))

(kind/hidden
 (with-room room
   (let [auditor (d/join room (d/participant
                               {:id :auditor
                                :on-message (fn [_p msg]
                                              (spin (swap! audit-log conj
                                                           (select-keys msg [:type :from]))
                                                    nil))}))]
     ;; subscribe the auditor to ALL :metric/cost messages, regardless of :to
     (d/subscribe! room auditor [:type :metric/cost]))
   nil))

;; A message tagged `:metric/cost`, addressed to nobody in particular, still
;; reaches the auditor via its tag subscription:

(with-room room
  (d/post! room {:type :metric/cost :from :sage :payload {:usd 0.02}}))

(settle)

(kind/table @audit-log)

;; The auditor saw a message addressed to no one — pure tag routing.

;; ## Where to go next
;;
;; - **Humans + agents, fork & merge** — humans as participants, background
;;   tasks, and the propose → accept/reject pattern (`humans_and_agents`).
;; - **Auditor** — capability subscriptions in depth (`auditor`).
;; - **Escalation** — budget escalation via tagged messages + a bus-level
;;   policy handler (`escalation`).
;; - **Streaming** — per-consumer buffer/SLA policy on a token stream
;;   (`streaming`).
;; - **LLM agent** — drive a *real* model with tools (`llm_agent`).

(kind/hidden (d/close-room! room) nil)
