(ns dvergr.discourse.enrichment
  "Composable on-message wrappers for participants in dvergr.discourse.

   Each wrapper is a `Participant → Participant` decorator that transforms
   the participant's `:on-message` handler — and the `:factory` so the
   wrapping survives fork-room cloning. Wrappers compose in a `(-> p w1 w2)`
   pipeline; the outermost wrapper runs first.

   Ported from the legacy `dvergr.agent.enrichment` (`(fn [think-fn] new-think-fn)`
   middleware). The discourse equivalent operates on the Participant value
   itself, so it composes naturally with `d/with-cadence` / `d/with-sources`
   driver attachments at the same level.

   The four wrappers:
     - `with-self-filter`        — drop :source events the participant authored
     - `with-room-context`       — prepend formatted room history to incoming
                                   message content
     - `with-silence`            — treat [SKIP] or blank replies as no-reply
     - `with-intel-room-routing` — post reply back into the room that
                                   delivered the :source event

   Daemon-side composition (typical):

     (-> (llm-agent {…})
         (enr/with-self-filter)
         (enr/with-room-context {:conn conn :room-ids ids})
         (enr/with-silence)
         (enr/with-intel-room-routing {:conn conn})
         (d/with-sources sources))"
  (:require [clojure.string :as str]
            [org.replikativ.spindel.core :as sp]
            [dvergr.discourse :as d]
            [dvergr.rooms :as rooms]
            [taoensso.telemere :as tel]))

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
  "Drop :source envelopes authored by this participant.

   When a participant posts a reply into a room, the bus may re-deliver
   that same message back as a :source event (any subscriber, including
   the author, sees it). Without filtering, the agent reacts to its own
   output — echo loop. This wrapper checks `:source-agent-id` on the
   source `:msg` against the participant's own id and drops the event
   when they match.

   Source events with no `:source-agent-id` are passed through unchanged."
  [p]
  (let [orig    (:on-message p)
        my-name (name (:id p))]
    (wrap-on-message
      p
      (fn [pp env]
        (sp/spin
          (if (and (= :source (:type env))
                   (= my-name (get-in env [:msg :source-agent-id])))
            nil
            (sp/await (orig pp env)))))
      with-self-filter)))

;; ============================================================================
;; Room Context Enrichment
;; ============================================================================

(defn- format-history-for-llm
  "Render a small chunk of room history as a markdown system-style block."
  [history]
  (str/join "\n\n"
    (for [{:keys [room-id messages]} history
          :when (seq messages)]
      (str "## Room " room-id "\n"
           (str/join "\n"
             (for [m messages]
               (let [r (or (:message/role m) (:role m) "?")
                     u (or (:message/source-user m) (:source-user m))
                     c (or (:message/content m) (:content m) "")]
                 (str "- [" (name r)
                      (when u (str ":" u))
                      "] " c))))))))

(defn with-room-context
  "Prepend formatted room history to the incoming envelope's :content.

   For each invocation, fetches the latest `:limit` messages from each
   room id in `:room-ids` via `dvergr.rooms/get-messages`, formats them
   as a markdown block, and prepends to the envelope before calling
   on-message. Only applies when the envelope is a Message (i.e. from the
   inbox); tick / source envelopes pass through unchanged.

   Opts:
     :conn     — Datahike connection (required)
     :room-ids — seq of chat-id UUIDs to include (required)
     :limit    — max messages per room (default 20)"
  [p {:keys [conn room-ids limit] :or {limit 20}}]
  {:pre [(some? conn) (seq room-ids)]}
  (let [orig (:on-message p)]
    (wrap-on-message
      p
      (fn [pp env]
        (sp/spin
          (let [history (->> room-ids
                             (mapv (fn [rid]
                                     {:room-id  rid
                                      :messages (rooms/get-messages
                                                  conn rid :limit limit)}))
                             (filter #(seq (:messages %))))
                hist-text (when (seq history) (format-history-for-llm history))
                enriched (if (and hist-text (instance? dvergr.discourse.Message env))
                           (assoc env :content
                                  (str "Recent room context:\n\n"
                                       hist-text
                                       "\n\n---\n\n"
                                       (:content env)))
                           env)]
            (sp/await (orig pp enriched)))))
      #(with-room-context % {:conn conn :room-ids room-ids :limit limit}))))

;; ============================================================================
;; Silence Option
;; ============================================================================

(defn with-silence
  "Filter out replies that opt for silence: a reply whose `:content` starts
   with `[SKIP]` or is blank is treated as no-reply (returns nil).

   This is the simplest fix for boardroom chaos — agents that have nothing
   substantive to add can stay quiet. The system prompt should mention the
   `[SKIP]` convention so the model knows it can use it."
  [p]
  (let [orig (:on-message p)]
    (wrap-on-message
      p
      (fn [pp env]
        (sp/spin
          (let [reply (sp/await (orig pp env))]
            (when reply
              (let [c (str (:content reply))]
                (when-not (or (str/blank? c)
                              (str/starts-with? (str/trim c) "[SKIP]"))
                  reply))))))
      with-silence)))

;; ============================================================================
;; Intel Room Response Routing
;; ============================================================================

(defn- post-to-source-room!
  "Best-effort: persist `reply` from `pid` back into the room identified by
   `source-slug`. Errors are logged and swallowed — routing is a side
   effect and must not break the participant loop."
  [conn pid source-slug reply]
  (when (and source-slug reply)
    (let [text    (str (:content reply))
          trimmed (str/trim text)]
      (when (and (> (count trimmed) 30)
                 (not (str/starts-with? trimmed "[SKIP]")))
        (try
          (when-let [room (rooms/get-room-by-slug conn source-slug)]
            (rooms/post-message! conn (:chat/id room)
              {:content text
               :role :assistant
               :source-user (name pid)
               :source-agent-id (name pid)}))
          (catch Exception e
            (tel/log! {:level :error
                       :id :discourse.enrichment/intel-routing-error
                       :data {:participant pid :slug source-slug
                              :err (.getMessage e)}}
                      "Intel routing post failed")))))))

(defn with-intel-room-routing
  "After on-message produces a reply, if the triggering envelope was a
   `:source` event, post the reply back into the room that delivered it
   (via `rooms/get-room-by-slug` + `rooms/post-message!`). Pure side
   effect — the reply itself is also returned for normal routing.

   The source's `:name` is taken to be the room slug (or `kw -> name`).
   Apply AFTER `with-silence` so only substantive replies get persisted.

   Opts:
     :conn — Datahike connection (required)"
  [p {:keys [conn]}]
  {:pre [(some? conn)]}
  (let [orig (:on-message p)
        pid  (:id p)]
    (wrap-on-message
      p
      (fn [pp env]
        (sp/spin
          (let [source-slug (when (= :source (:type env))
                              (some-> env :name name))
                reply       (sp/await (orig pp env))]
            (post-to-source-room! conn pid source-slug reply)
            reply)))
      #(with-intel-room-routing % {:conn conn}))))
