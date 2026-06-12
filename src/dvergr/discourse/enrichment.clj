(ns dvergr.discourse.enrichment
  "Composable on-message wrappers for participants in dvergr.discourse.

   Each wrapper is a `Participant → Participant` decorator that transforms
   the participant's `:on-message` handler — and the `:factory` so the
   wrapping survives fork-room cloning.

   With persistent rooms unified into discourse Rooms, the bridge
   wrappers (`with-room-context`, `with-intel-room-routing`) are gone:
   per-room Participants see their room's bus log directly and reply
   into the same room via standard discourse routing.

   Two wrappers remain:
     - `with-self-filter` — drop :source events the participant authored
     - `with-silence`     — treat [SKIP] or blank replies as no-reply"
  (:require [clojure.string :as str]
            [org.replikativ.spindel.core :as sp]))

;; ============================================================================
;; Helpers
;; ============================================================================

(defn- wrap-on-message
  "Replace p's :on-message with `f` and re-thread the wrap through :factory
   so forks carry the wrapper. Each enrichment must use this to be
   fork-stable. `wrap-self` is the enrichment fn itself (so we can recurse
   into the wrapped factory)."
  [p f wrap-self]
  (let [orig-factory (:factory p)]
    (-> p
        (assoc :on-message f)
        (assoc :factory
               (when orig-factory
                 (fn [new-ctx] (wrap-self (orig-factory new-ctx))))))))

;; ============================================================================
;; Self-Filter (Echo Loop Prevention)
;; ============================================================================

(defn with-self-filter
  "Drop envelopes authored by this participant — an agent must never act on its
   own output, or it echo-loops. In the room model a participant subscribes to
   `[:to <id>]` AND `[:to nil]` (broadcast), so its own reply — broadcast or
   addressed back to it — lands in its own mailbox as a Message; we drop any
   Message whose `:from` is us. Anything else passes through unchanged."
  [p]
  (let [orig  (:on-message p)
        my-id (:id p)]
    (wrap-on-message
     p
     (fn [pp env]
       (sp/spin
        (if (= my-id (:from env))
          nil
          (sp/await (orig pp env)))))
     with-self-filter)))

;; ============================================================================
;; Silence Option
;; ============================================================================

(defn silent-reply?
  "True when reply `content` opts for silence — blank, or the canonical `[SKIP]`
   convention (case-insensitive) or a few tolerated variants (`SKIP`, `NO_REPLY`,
   `NOOP`, `[NO_REPLY]`). The system prompt teaches the `[SKIP]` convention (see
   `dvergr.agent.prompt/discourse-preamble`); this is forgiving about how the
   model spells it."
  [content]
  (let [t  (str/trim (str content))
        tl (str/lower-case t)]
    (boolean
     (or (str/blank? t)
         (str/starts-with? tl "[skip]")
         (str/starts-with? tl "[no_reply]")
         (contains? #{"skip" "no_reply" "noop" "no-op"} tl)))))

(defn with-silence
  "Filter out replies that opt for silence (see `silent-reply?`): the reply is
   treated as no-reply (returns nil).

   This is the soft half of multi-agent loop control — an agent that has nothing
   substantive to add stays quiet instead of chiming in. Pairs with the
   `[SKIP]`-teaching system-prompt preamble."
  [p]
  (let [orig (:on-message p)]
    (wrap-on-message
     p
     (fn [pp env]
       (sp/spin
        (let [reply (sp/await (orig pp env))]
          (when (and reply (not (silent-reply? (:content reply))))
            reply))))
     with-silence)))

;; ============================================================================
;; Plain reply (strip a stray context annotation)
;; ============================================================================

(def ^:private annotation-prefix
  "A leading `[name · HH:mm]` — the author·time decoration the system prepends to
   OTHER participants' messages (see dvergr.agent.room-context). A weak model
   occasionally echoes it into its own reply (and adopts the other speaker's
   identity). Matches only the distinctive timestamped form (middot + HH:mm), so
   real content that merely starts with brackets is left alone. Tolerates leading
   whitespace/newlines before it and trailing whitespace after."
  #"^\s*\[[^\]\n]{1,60} · \d{1,2}:\d{2}\]\s*")

(defn strip-context-annotation
  "Remove a leading `[name · time]` prefix from `content` if present. Deterministic
   guarantee that the system-added context decoration never leaks into a posted
   reply, regardless of how well the model follows the prompt."
  [content]
  (when content (clojure.string/replace-first (str content) annotation-prefix "")))

(defn with-plain-reply
  "Strip a stray leading `[name · time]` annotation from the agent's reply (see
   `strip-context-annotation`). The prompt teaches the model not to write it; this
   makes it impossible regardless. Compose OUTERMOST so it runs on the final
   content."
  [p]
  (let [orig (:on-message p)]
    (wrap-on-message
     p
     (fn [pp env]
       (sp/spin
        (let [reply (sp/await (orig pp env))]
          (if (and reply (string? (:content reply)))
            (update reply :content strip-context-annotation)
            reply))))
     with-plain-reply)))

;; with-room-context and with-intel-room-routing are gone — when an
;; agent is a Participant in a Room directly (the post-unification
;; model), the room's bus log IS the history and replies naturally
;; land in the room the message arrived in via discourse routing.
