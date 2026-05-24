(ns dvergr.discourse.human
  "Generic human-participant constructor for `dvergr.discourse` Rooms.

   A `human-participant` represents an external (non-LLM) consumer of
   messages joined into a Room. Its inbox is the canonical routing
   endpoint for any message addressed to that human — solicited replies
   from an llm-agent that uses `(:from msg)` for `:to`, or unsolicited
   posts from a ticking / source-driven agent, or a `:notification`
   posted by `dvergr.discourse.background/spawn-task!`.

   The participant does not auto-reply — a real human reads the message
   in whatever UI they have and decides what to do. The library exposes
   a pluggable `:on-receive` callback so the caller can persist the
   message, push it to a WebSocket, write it to stdout, ring a bell,
   etc. dvergr stays UI-agnostic; whoever embeds it supplies the
   transport.

   Usage:

     (require '[dvergr.discourse :as d]
              '[dvergr.discourse.human :as human])

     (def me
       (human/human-participant
         {:id :user/christian
          :ctx (:ctx room)
          :on-receive (fn [msg]
                        (println \"[\"  (:from msg) \"]\" (:content msg)))}))

     (d/join room me)

     ;; Now any agent that replies addressed to :user/christian, or
     ;; posts an unsolicited message :to :user/christian, lands in our
     ;; on-receive."
  (:require [dvergr.discourse :as d]
            [org.replikativ.spindel.core :as sp]
            [org.replikativ.spindel.engine.core :as ec]))

(defn human-participant
  "Construct a discourse `Participant` representing a human.

   Required keys:
     :id          — keyword/uuid identifier (the human's stable id)
     :on-receive  — (fn [msg]) called for every inbox Message. Side-effects
                    only; return value is ignored. Errors do NOT propagate
                    — the participant catches Throwable so a bad handler
                    doesn't take the participant-spin loop down. The
                    callback runs synchronously inside the spin body, so
                    long-running work belongs in another thread.

   Optional keys:
     :ctx         — execution context (default: `ec/*execution-context*`).
                    Use this when constructing outside a binding —
                    e.g. on app boot, before any spin body runs.

   Returns a Participant. The participant's `:factory` re-creates the
   same shape against a new ctx so `dvergr.discourse/fork-room` works
   (the fork's human always resolves to nil in `simulate-reply` because
   `on-receive` returns nil — there is no model of the human's reply at
   this layer)."
  [{:keys [id ctx on-receive]}]
  {:pre [(some? id) (some? on-receive)]}
  (d/participant
    {:id  id
     :ctx (or ctx ec/*execution-context*)
     :on-message
     (fn [_p msg]
       (sp/spin
         (try (on-receive msg)
              (catch Throwable _ nil))
         nil))
     :factory
     (fn [new-ctx]
       (human-participant {:id id :ctx new-ctx :on-receive on-receive}))}))
