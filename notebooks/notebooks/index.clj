(ns notebooks.index
  "Landing page for the dvergr example notebooks — rendered as the book's index
   (see notebooks.render, :first-as-index).")

;; # dvergr — examples

;; **dvergr** is a Clojure framework for *continuous-time collaborative
;; multi-agent rooms*: agents are reactive processes; humans, LLMs, and scripts
;; are participants of the **same shape**; and everything composes through
;; **tagged messages** on a small pub/sub kernel — over a forkable substrate (a
;; spindel reactive runtime + Datahike + yggdrasil CRDTs).

;; These notebooks build that model up from the kernel. Each one **runs live** —
;; the outputs you see are produced by evaluating the code — and needs no API key
;; or network, except where an LLM is explicitly used.

;; ## The notebooks

;; 1. **Getting started** — a from-zero tour: rooms, participants, tagged
;;    messages, capability subscriptions, and the fork-and-merge proposal.
;; 2. **Programming model** — the compositional kernel in depth: tag routing,
;;    capability subscriptions beyond your inbox, escalation as a posted tag, and
;;    per-consumer buffer policy.
;; 3. **Humans and agents** — humans as first-class participants, background
;;    tasks with notifications, and substrate-isolated fork → propose →
;;    merge/discard.
;; 4. **Agents and tools** — wiring LLM agents with tools into a room.

;; Use the sidebar to navigate. The source lives under `notebooks/` in the
;; [repository](https://github.com/replikativ/dvergr).
