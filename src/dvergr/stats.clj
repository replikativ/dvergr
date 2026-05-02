(ns dvergr.stats
  "Per-agent aggregate statistics: spend, last-active, 1-line LLM status summary.

   Queries the datahike ledger for cost and last-active timestamp; calls
   cheap-llm-call to generate a one-sentence status summary from the agent's
   most recent assistant message.

   All values are cached with short TTLs to avoid DB/LLM pressure on every
   render:
     cost / last-active — refreshed every 30 s
     summary            — refreshed every 5 min (LLM call, async)

   Usage:
     (stats/init! datahike-conn)    ; call once at daemon startup
     (stats/get-stats :huginn)      ; => {:cost-dollars 0.027
                                    ;      :last-active #inst \"2026-02-22T...\"
                                    ;      :last-active-str \"5m ago\"
                                    ;      :summary \"Sweep stored 1 signal...\"}
     (stats/refresh-all!)           ; force-refresh all agents"
  (:require [datahike.api :as dh]
            [dvergr.llm-call :as llm]
            [dvergr.model.registry :as model-registry]
            [dvergr.registry :as registry]
            [clojure.string :as str]
            [taoensso.telemere :as tel]))

;; ============================================================================
;; State
;; ============================================================================

(defonce ^:private conn-a  (atom nil))  ; raw datahike.connector.Connection
(defonce ^:private cache-a (atom {}))   ; {agent-id -> stat-map}

(def ^:private cost-ttl-ms    (* 30 1000))   ; 30 s
(def ^:private summary-ttl-ms (* 300 1000))  ; 5 min

(defn init!
  "Store the shared Datahike connection for stats queries.
   Call once from daemon startup after the datahike system is registered."
  [datahike-conn]
  (reset! conn-a datahike-conn))

;; ============================================================================
;; Helpers
;; ============================================================================

(defn- age-str
  "Human-readable age of a java.util.Date: '5m ago', '2h ago', '3d ago'."
  [^java.util.Date ts]
  (when ts
    (let [ms (- (System/currentTimeMillis) (.getTime ts))]
      (cond
        (< ms 60000)    "just now"
        (< ms 3600000)  (format "%dm ago"  (long (/ ms 60000)))
        (< ms 86400000) (format "%dh ago"  (long (/ ms 3600000)))
        :else           (format "%dd ago"  (long (/ ms 86400000)))))))

;; ============================================================================
;; Datahike Queries
;; ============================================================================

(defn- query-cost
  "Sum ledger costs (in dollars) for all chats whose title starts with prefix."
  [db prefix]
  (try
    (let [res (dh/q '[:find (sum ?cost)
                       :with ?e
                       :in $ ?prefix
                       :where [?e :ledger/context ?c]
                              [?c :chat/title ?t]
                              [?e :ledger/cost-microdollars ?cost]
                              [(clojure.string/starts-with? ?t ?prefix)]]
                    db prefix)]
      (/ (double (or (ffirst res) 0)) 1000000.0))
    (catch Exception _ nil)))

(defn- query-last-active
  "Most recent ledger timestamp for all chats whose title starts with prefix."
  [db prefix]
  (try
    (ffirst (dh/q '[:find (max ?ts)
                    :in $ ?prefix
                    :where [?e :ledger/context ?c]
                           [?c :chat/title ?t]
                           [?e :ledger/timestamp ?ts]
                           [(clojure.string/starts-with? ?t ?prefix)]]
                  db prefix))
    (catch Exception _ nil)))

(defn- query-recent-content
  "Get the most recent assistant message body (≤800 chars) from agent's chats."
  [db agent-id]
  (try
    (let [prefix (name agent-id)
          msgs   (dh/q '[:find ?content ?ts
                          :in $ ?prefix
                          :where [?c :chat/title ?t]
                                 [(clojure.string/starts-with? ?t ?prefix)]
                                 [?m :message/chat ?c]
                                 [?m :message/role :assistant]
                                 [?m :message/content ?content]
                                 [?m :message/created-at ?ts]]
                       db prefix)]
      (when (seq msgs)
        (let [content (first (first (sort-by second #(compare %2 %1) msgs)))]
          (when (seq content)
            (subs content 0 (min 800 (count content)))))))
    (catch Exception _ nil)))

;; ============================================================================
;; Refresh Logic
;; ============================================================================

(defn- refresh-cost!
  "Synchronously update cost and last-active for agent-id in the cache."
  [agent-id]
  (when-let [conn @conn-a]
    (try
      (let [db      @conn
            prefix  (name agent-id)
            cost    (query-cost db prefix)
            last-at (query-last-active db prefix)]
        (swap! cache-a update agent-id merge
               {:cost-dollars        cost
                :last-active         last-at
                :last-active-str     (age-str last-at)
                :cost-refreshed-at   (System/currentTimeMillis)}))
      (catch Exception e
        (tel/log! {:level :warn :id :stats/cost-error
                   :data {:agent-id agent-id :error (.getMessage e)}}
                  "Stats cost refresh failed")))))

(defn- refresh-summary-async!
  "Fire a background thread to update the LLM status summary for agent-id."
  [agent-id]
  (future
    (try
      (when-let [conn @conn-a]
        (let [db      @conn
              content (query-recent-content db agent-id)]
          (when content
            (let [summary-model (or (model-registry/get-default :summary-model)
                                     "accounts/fireworks/models/llama-v3p2-3b-instruct")
                  result (llm/cheap-llm-call
                           (str "Summarize in ONE concise sentence (max 15 words) "
                                "what this AI agent accomplished in its most recent activity:")
                           content
                           ;; 200 tokens needed — Fireworks models use internal thinking tokens
                           {:max-tokens 200 :model summary-model})]
              (let [summary (str/trim (or (:text result) ""))]
                (when (and (not (:error result)) (seq summary))
                  (swap! cache-a update agent-id assoc
                         :summary              summary
                         :summary-refreshed-at (System/currentTimeMillis))))))))
      (catch Exception e
        (tel/log! {:level :warn :id :stats/summary-error
                   :data {:agent-id agent-id :error (.getMessage e)}}
                  "Stats summary refresh failed")))))

;; ============================================================================
;; Public API
;; ============================================================================

(defn get-stats
  "Return cached stats for agent-id, triggering async refreshes when stale.

   Returns map:
     :cost-dollars     — total spend (nil if unknown)
     :last-active      — java.util.Date of last ledger entry (nil if unknown)
     :last-active-str  — human-readable age string (nil if unknown)
     :summary          — 1-sentence LLM status (nil while pending/unavailable)"
  [agent-id]
  (let [cached    (get @cache-a agent-id)
        now       (System/currentTimeMillis)
        cost-stale?    (or (nil? cached)
                           (> (- now (or (:cost-refreshed-at cached) 0))
                              cost-ttl-ms))
        summary-stale? (or (nil? (:summary cached))
                           (> (- now (or (:summary-refreshed-at cached) 0))
                              summary-ttl-ms))]
    (when cost-stale?
      (future (refresh-cost! agent-id)))
    (when summary-stale?
      (refresh-summary-async! agent-id))
    (or cached {:cost-dollars nil :last-active nil :last-active-str nil :summary nil})))

(defn refresh-all!
  "Force-refresh stats for all registered agents. Blocks for cost queries,
   fires async for LLM summaries."
  []
  (doseq [agent-id (registry/agent-ids)]
    (refresh-cost! agent-id)
    (refresh-summary-async! agent-id)))
