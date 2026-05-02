(ns dvergr.agent.budget
  "Agent scoring and budget adjustment based on emoji reactions.

   Workflow:
   1. Human or agent reacts to an agent's output with +1/-1/rocket/etc.
   2. record-score! persists the score in Datahike
   3. Periodically (e.g., daily by Vár), adjust-budget! recalculates
      agent budgets based on accumulated scores

   Score values:
     \"+1\"    → +1
     \"-1\"    → -1
     \"rocket\" → +2
     \"eyes\"   → 0  (acknowledgment, no score impact)

   Budget formula:
     new-budget = base-budget * (1 + score-ratio * 0.1)
     where score-ratio = total-score / max(1, num-scores)"
  (:require [datahike.api :as d]))

;; =============================================================================
;; Score Values
;; =============================================================================

(def emoji-values
  "Map of emoji → numeric score value."
  {"+1"    1
   "-1"    -1
   "rocket" 2
   "eyes"   0
   "star"   2
   "thumbsdown" -1
   "thumbsup" 1})

(defn emoji->value
  "Convert an emoji string to its numeric score value. Default: 0."
  [emoji]
  (get emoji-values emoji 0))

;; =============================================================================
;; Score Recording
;; =============================================================================

(defn record-score!
  "Persist a score for an agent's output.

   Args:
     conn        - Datahike connection
     agent-id    - Keyword, agent being scored
     emoji       - String, the reaction emoji
     scorer-id   - Keyword, who scored (:human or agent keyword)
     message-ref - String, reference to the scored output"
  [conn agent-id emoji scorer-id message-ref]
  (d/transact conn [{:score/id          (random-uuid)
                      :score/agent-id    agent-id
                      :score/scorer-id   scorer-id
                      :score/emoji       emoji
                      :score/value       (emoji->value emoji)
                      :score/message-ref message-ref
                      :score/created-at  (java.util.Date.)}]))

;; =============================================================================
;; Score Queries
;; =============================================================================

(defn get-agent-score
  "Get score summary for an agent.

   Returns {:total N :positive M :negative K :count C :recent [...]}

   Options:
     :since - java.util.Date, only count scores after this time
     :limit - Max recent scores to return (default 20)"
  [conn agent-id & {:keys [since limit] :or {limit 20}}]
  (let [scores (d/q '[:find [(pull ?e [*]) ...]
                       :in $ ?aid
                       :where [?e :score/agent-id ?aid]]
                     @conn agent-id)
        filtered (if since
                   (filter #(.after (or (:score/created-at %) (java.util.Date. 0)) since)
                           scores)
                   scores)
        values (map #(or (:score/value %) 0) filtered)]
    {:total    (reduce + 0 values)
     :positive (count (filter pos? values))
     :negative (count (filter neg? values))
     :count    (count filtered)
     :recent   (->> filtered
                    (sort-by #(- (.getTime (or (:score/created-at %) (java.util.Date. 0)))))
                    (take limit)
                    vec)}))

(defn get-all-scores
  "Get score summaries for all agents that have been scored.

   Returns map of {agent-id -> {:total :positive :negative :count}}."
  [conn & {:keys [since]}]
  (let [agent-ids (d/q '[:find [?aid ...]
                          :where [?e :score/agent-id ?aid]]
                        @conn)]
    (into {}
          (map (fn [aid]
                 [aid (dissoc (get-agent-score conn aid :since since) :recent)])
               agent-ids))))

;; =============================================================================
;; Budget Adjustment
;; =============================================================================

(defn adjust-budget!
  "Adjust an agent's budget based on accumulated scores.

   Formula: new-budget = base-budget * (1 + score-ratio * 0.1)
   where score-ratio = total-score / max(1, num-scores)

   Clamped to [0.5 * base, 2.0 * base] to prevent runaway.

   Args:
     conn       - Datahike connection
     agent-id   - Keyword agent ID
     base-budget - Long, the default budget in microdollars

   Returns the new budget value."
  [conn agent-id base-budget]
  (let [{:keys [total count]} (get-agent-score conn agent-id)
        score-ratio (if (pos? count) (/ (double total) (max 1 count)) 0.0)
        multiplier  (max 0.5 (min 2.0 (+ 1.0 (* score-ratio 0.1))))
        new-budget  (long (* base-budget multiplier))]
    new-budget))
