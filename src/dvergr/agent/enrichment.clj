(ns dvergr.agent.enrichment
  "Composable think-fn wrappers for context enrichment.

   These are pure function transformations — no daemon knowledge.
   Each wrapper takes a think-fn and returns a wrapped think-fn with
   the same signature: (fn [agent task] -> spin).

   Compose at daemon config time:
     (-> base-think-fn
         (enrichment/with-self-filter :huginn)
         (enrichment/with-room-context conn room-ids)
         (enrichment/with-relevance-filter [\"clojure\" \"deploy\"])
         (enrichment/with-silence-option))"
  (:require [dvergr.rooms :as rooms]
            [org.replikativ.spindel.core :refer [spin]]
            [org.replikativ.spindel.spin.sync :as sync]
            [taoensso.telemere :as tel]
            [clojure.string :as str]))

;; ============================================================================
;; Self-Filter (Echo Loop Prevention)
;; ============================================================================

(defn with-self-filter
  "Drop :source events authored by this agent (prevents echo loops).

   When an agent posts a response to a room, the bus re-delivers it to
   all subscribers — including the authoring agent.  This wrapper drops
   those self-authored events so the agent doesn't react to its own output.

   Args:
     think-fn - (fn [agent task] -> spin)
     agent-id - Keyword agent ID of the current agent

   Returns wrapped think-fn."
  [think-fn agent-id]
  (fn [agent task]
    (if (and (= :source (:type task))
             (= (name agent-id)
                (get-in task [:msg :source-agent-id])))
      (spin {:status :skipped :reason :self-echo :agent-id (:id agent)})
      (think-fn agent task))))

;; ============================================================================
;; Room Context Enrichment
;; ============================================================================

(defn with-room-context
  "Wrap think-fn to inject room history into task before each call.

   For every invocation, fetches the latest messages from each room-id
   and attaches them as :room-context on the task map.

   Args:
     think-fn - (fn [agent task] -> spin)
     conn     - Datahike connection
     room-ids - Seq of room chat-ids (UUIDs) to include
     :limit   - Max messages per room (default 20)

   Returns wrapped think-fn."
  [think-fn conn room-ids & {:keys [limit] :or {limit 20}}]
  (fn [agent task]
    (let [history (->> room-ids
                       (mapv (fn [rid]
                               {:room-id rid
                                :messages (rooms/get-messages conn rid :limit limit)}))
                       (filter #(seq (:messages %))))
          enriched (if (map? task)
                     (assoc task :room-context history)
                     {:content task :room-context history})]
      (think-fn agent enriched))))

;; ============================================================================
;; Relevance Filtering
;; ============================================================================

(defn with-relevance-filter
  "Wrap think-fn to skip source events that don't match keywords.

   Only applies to tasks with :type :source — all other task types
   pass through unchanged.

   Args:
     think-fn - (fn [agent task] -> spin)
     keywords - Seq of strings; at least one must appear in the source
                event's :msg (case-insensitive substring match)

   Returns wrapped think-fn. Returns nil for filtered events (agent
   loop treats nil as no-op)."
  [think-fn keywords]
  (let [kws (mapv str/lower-case keywords)]
    (fn [agent task]
      (if (and (= :source (:type task))
               (not-any? (fn [kw]
                           (str/includes?
                             (str/lower-case (str (:msg task)))
                             kw))
                         kws))
        nil  ;; Skip irrelevant source events
        (think-fn agent task)))))

;; ============================================================================
;; Source-to-Text Conversion
;; ============================================================================

(defn with-source-as-text
  "Wrap think-fn to convert :source events into text tasks.

   When a source event arrives, formats it as a user-readable message
   and passes it to think-fn as a text content task instead of the
   raw source map.

   Args:
     think-fn  - (fn [agent task] -> spin)
     format-fn - (fn [source-event] -> string) converts source event
                 to task text. Default: uses :msg preview.

   Returns wrapped think-fn."
  ([think-fn] (with-source-as-text think-fn nil))
  ([think-fn format-fn]
   (let [fmt (or format-fn
                 (fn [{:keys [name msg]}]
                   (str "New message in " (some-> name clojure.core/name) ": "
                        (if (map? msg)
                          (str "[" (or (:source msg) "unknown") "] "
                               (or (:preview msg) (:content msg) ""))
                          (str msg)))))]
     (fn [agent task]
       (if (= :source (:type task))
         (think-fn agent {:type :source-text
                          :content (fmt task)
                          :original task})
         (think-fn agent task))))))

;; ============================================================================
;; Silence Option (Boardroom Noise Reduction)
;; ============================================================================

(defn with-silence-option
  "Wrap think-fn to allow agents to choose silence.

   When an agent's response starts with '[SKIP]' or is empty/whitespace-only,
   the output is treated as intentional silence — nothing is posted to the room.

   This is the simplest fix for boardroom chaos: agents can choose not to respond
   when they have nothing substantive to add.

   The system prompt is augmented with a silence instruction so the model
   knows it can stay silent.

   Args:
     think-fn - (fn [agent task] -> spin)

   Returns wrapped think-fn that filters silent responses."
  [think-fn]
  (fn [agent task]
    (let [result-spin (think-fn agent task)]
      (spin
        (let [result (org.replikativ.spindel.effects.await/await result-spin)]
          (if (map? result)
            ;; Check text output for silence markers
            (let [text (or (:text result) (:content result) "")]
              (if (or (str/blank? text)
                      (str/starts-with? (str/trim text) "[SKIP]"))
                {:status :skipped :reason :silence :agent-id (:id agent)}
                result))
            result))))))

;; ============================================================================
;; Intel Room Response Routing
;; ============================================================================

(defn- post-to-intel-room!
  "Attempt to post a result back to the source intel room.
   Called from the inner spin's resolve callback — not inside another spin."
  [conn agent source-room-slug result]
  (when (and source-room-slug
             (map? result)
             (not= :skipped (:status result)))
    (let [text    (or (:content result) (:text result) "")
          trimmed (str/trim text)]
      (when (and (> (count trimmed) 30)
                 (not (str/starts-with? trimmed "[SKIP]")))
        (try
          (when-let [room (rooms/get-room-by-slug conn source-room-slug)]
            (rooms/post-message! conn (:chat/id room)
              {:content text
               :role :assistant
               :source-user (name (:id agent))
               :source-agent-id (name (:id agent))}))
          (catch Exception e
            (tel/log! {:level :error :id :intel-routing/post-error
                       :data {:agent (:id agent) :slug source-room-slug
                              :err (.getMessage e)}} "Intel routing post failed")))))))

(defn with-intel-room-routing
  "Post agent responses back to the room that triggered them.

   When an agent receives a :source event from a room (e.g. market-intel),
   and produces a non-skipped, non-silent response, this wrapper posts
   the response back to that exact room via rooms/post-message!.

   This is the correct routing mechanism for multi-room analyst agents
   (GLM, Kimi) — responses go to the source room, not broadcast to all rooms.

   Apply AFTER with-silence-option so only substantive responses are routed.

   Args:
     think-fn - (fn [agent task] -> spin)
     conn     - Datahike connection (for rooms/get-room-by-slug)

   Returns wrapped think-fn."
  [think-fn conn]
  (fn [agent task]
    (let [source-room-slug (when (= :source (:type task))
                             (some-> (:name task) name))
          inner-spin (think-fn agent task)]
      (let [fw (java.io.FileWriter. "/tmp/routing-debug.log" true)]
        (.write fw (str "[routing] outer-fn: inner-spin=" (type inner-spin) " slug=" source-room-slug "\n"))
        (.flush fw)
        (.close fw))
      (spin
        (let [fw2 (java.io.FileWriter. "/tmp/routing-debug.log" true)
              _ (do (.write fw2 (str "[routing] spin-body: slug=" source-room-slug "\n")) (.flush fw2) (.close fw2))
              result (org.replikativ.spindel.effects.await/await inner-spin)
              fw3 (java.io.FileWriter. "/tmp/routing-debug.log" true)
              _ (do (.write fw3 (str "[routing] await-done: slug=" source-room-slug " status=" (:status result) "\n")) (.flush fw3) (.close fw3))]
          (post-to-intel-room! conn agent source-room-slug result)
          result)))))
