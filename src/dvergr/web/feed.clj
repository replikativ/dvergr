(ns dvergr.web.feed
  "Activity feed for observing agent behaviour in real-time.

   Routes (mounted in web.server):
     GET /feed          — full HTML page, supports ?agent=X&hours=N
     GET /api/feed      — HTML fragment for HTMX polling (every 5s)

   REPL helper:
     (feed 10)              — last 10 events across all agents
     (feed :huginn 5)       — last 5 huginn events
     (feed :sentinel 10 6)  — last 10 sentinel events from last 6 hours"
  (:require [hiccup2.core :as h]
            [hiccup.util :as hu]
            [datahike.api :as dh]
            [clojure.string :as str]
            [dvergr.web.layout :as layout]))

;; ============================================================================
;; State
;; ============================================================================

(defonce ^:private conn-a (atom nil))

(defn init!
  "Store the shared Datahike connection for feed queries. Call once at startup."
  [datahike-conn]
  (reset! conn-a datahike-conn))

;; ============================================================================
;; Helpers
;; ============================================================================

(defn- fmt-time [d]
  (when d
    (let [s (str d)]
      (if (>= (count s) 16)
        (subs s 11 16)
        s))))

(defn- extract-agent-id
  "Parse agent name from a chat title.
   Examples:
     \"huginn-1771818407058\"     → \"huginn\"
     \"sentinel with Christian\"  → \"sentinel\"
     \"eval-huginn-123\"          → \"huginn\""
  [chat-title]
  (when chat-title
    (cond
      (str/starts-with? chat-title "eval-")
      (second (str/split chat-title #"-"))
      :else
      (first (str/split chat-title #"[-\s]")))))

(defn- parse-query-string
  "Parse a query string like \"agent=huginn&hours=6\" into a map."
  [qs]
  (when (and qs (not (str/blank? qs)))
    (into {}
          (keep (fn [pair]
                  (let [[k v] (str/split pair #"=" 2)]
                    (when (and k v)
                      [(keyword k) v]))))
          (str/split qs #"&"))))

(defn- truncate [s n]
  (if (and s (> (count s) n))
    (str (subs s 0 n) "...")
    s))

;; ============================================================================
;; Data Query
;; ============================================================================

(defn- query-feed
  "Query recent messages across all chat contexts.

   Options:
     :agent - agent name string to filter by (e.g. \"huginn\")
     :hours - time window in hours (default 2)
     :limit - max events to return (default 200)"
  [conn {:keys [agent hours limit]}]
  (let [hours   (or hours 2)
        limit   (or limit 200)
        cutoff-ms (- (System/currentTimeMillis) (* hours 3600000))
        msgs (dh/q '[:find [(pull ?m [:message/id :message/role :message/content
                                      :message/created-at :message/source-user
                                      :message/tool-use-id
                                      {:message/tool-uses [:tool-use/id :tool-use/name]}
                                      {:message/chat [:chat/id :chat/title]}]) ...]
                     :in $ ?cutoff-ms
                     :where
                     [?m :message/created-at ?t]
                     [(.getTime ?t) ?tms]
                     [(>= ?tms ?cutoff-ms)]]
                   @conn cutoff-ms)]
    (let [filtered (remove #(= :system (:message/role %)) msgs)
          filtered (if agent
                     (filter #(= agent (extract-agent-id (get-in % [:message/chat :chat/title])))
                             filtered)
                     filtered)]
      (->> filtered
           (sort-by #(- (.getTime (or (:message/created-at %) (java.util.Date. 0)))))
           (take limit)
           vec))))

(defn- known-agents
  "Return sorted list of distinct agent ID strings from all chat titles."
  [conn]
  (->> (dh/q '[:find [?title ...]
               :where [?c :chat/title ?title]]
             @conn)
       (keep extract-agent-id)
       distinct
       sort
       vec))

;; ============================================================================
;; CSS
;; ============================================================================

(def ^:private css
  ".filter-bar {
     display: flex;
     flex-wrap: wrap;
     gap: 6px;
     margin-bottom: 1.2em;
     align-items: center;
   }
   .filter-bar .label { color: #555; font-size: 0.82em; margin-right: 4px; }
   .filter-btn {
     display: inline-block;
     padding: 3px 12px;
     border-radius: 14px;
     font-size: 0.8em;
     background: #1a1a2e;
     border: 1px solid #2a2a4a;
     color: #aaa;
     transition: border-color 0.2s, background 0.2s;
   }
   .filter-btn:hover { border-color: #667eea; text-decoration: none; }
   .filter-btn.active { background: #667eea; color: #fff; border-color: #667eea; }
   .filter-sep { color: #333; margin: 0 4px; }

   .event-card {
     background: #1a1a2e;
     border: 1px solid #2a2a4a;
     border-radius: 8px;
     padding: 8px 14px;
     margin: 5px 0;
     transition: border-color 0.2s;
   }
   .event-card:hover { border-color: #444; }
   .event-header {
     display: flex;
     align-items: center;
     gap: 8px;
     flex-wrap: wrap;
   }
   .event-time { color: #555; font-size: 0.75em; margin-left: auto; }

   .badge {
     display: inline-block;
     padding: 1px 8px;
     border-radius: 8px;
     font-size: 0.75em;
     font-weight: 600;
   }
   .badge-huginn    { background:#1e3a1e; color:#52b788; }
   .badge-sentinel  { background:#1e2a3a; color:#89b4fa; }
   .badge-var       { background:#3a1e3a; color:#e0a0e0; }
   .badge-default   { background:#2a2a2a; color:#aaa; }

   .evt-tool   { color:#c9d15c; font-size:0.78em; }
   .evt-result { color:#888; font-size:0.78em; }
   .evt-text   { color:#52b788; font-size:0.78em; }
   .evt-input  { color:#a78bfa; font-size:0.78em; }

   .event-summary {
     color: #bbb;
     font-size: 0.88em;
     margin-top: 3px;
     white-space: nowrap;
     overflow: hidden;
     text-overflow: ellipsis;
     max-width: 750px;
   }
   details { margin-top: 4px; }
   details summary { cursor: pointer; color: #667eea; font-size: 0.8em; }
   details pre {
     background: #12122a;
     border: 1px solid #2a2a4a;
     border-radius: 6px;
     padding: 8px 12px;
     color: #aaa;
     font-size: 0.82em;
     white-space: pre-wrap;
     word-break: break-word;
     max-height: 300px;
     overflow-y: auto;
   }
   .empty-state { color: #555; text-align: center; padding: 3em 0; }")

;; ============================================================================
;; Page Chrome
;; ============================================================================

(defn- page-chrome [title-str _query-params & content]
  (apply layout/page-chrome
         {:title title-str :active-page :feed :extra-css css}
         content))

;; ============================================================================
;; Event Rendering
;; ============================================================================

(defn- agent-badge [agent-id]
  (let [cls (case agent-id
              "huginn"    "badge badge-huginn"
              "sentinel"  "badge badge-sentinel"
              "var"       "badge badge-var"
              "badge badge-default")]
    [:span {:class cls} (hu/escape-html (or agent-id "?"))]))

(defn- event-type-info [msg]
  (let [role       (:message/role msg)
        tool-uses  (:message/tool-uses msg)
        has-tools? (seq tool-uses)]
    (cond
      (and (= :assistant role) has-tools?)
      {:type :tool :label "tool" :css "evt-tool"}

      (= :assistant role)
      {:type :text :label "response" :css "evt-text"}

      (= :tool-result role)
      {:type :result :label "result" :css "evt-result"}

      (= :user role)
      {:type :input :label "input" :css "evt-input"}

      :else
      {:type :other :label (name (or role "?")) :css "evt-result"})))

(defn- render-event [msg]
  (let [agent-id  (extract-agent-id (get-in msg [:message/chat :chat/title]))
        content   (or (:message/content msg) "")
        tool-uses (:message/tool-uses msg)
        {:keys [type label css]} (event-type-info msg)
        tool-names (when (seq tool-uses)
                     (str/join ", " (map :tool-use/name tool-uses)))
        summary   (case type
                    :tool   (str "called " tool-names)
                    :text   (truncate content 200)
                    :result (truncate content 200)
                    :input  (truncate content 200)
                    (truncate content 200))
        show-details? (or (and (= :tool type) (not (str/blank? content)))
                          (> (count content) 200))]
    [:div.event-card
     [:div.event-header
      (agent-badge agent-id)
      [:span {:class css} label]
      [:span.event-time (fmt-time (:message/created-at msg))]]
     [:div.event-summary (hu/escape-html summary)]
     (when show-details?
       [:details
        [:summary "show detail"]
        [:pre (hu/escape-html content)]])]))

(defn- render-events
  "Render a list of message events as hiccup.
   Shared between feed-page and api-feed."
  [events]
  (if (seq events)
    (map render-event events)
    (list [:div.empty-state "No activity in this time window."])))

;; ============================================================================
;; Web Handlers
;; ============================================================================

(defn feed-page
  "Full HTML page for /feed. Parses ?agent=X&hours=N from query string."
  [query-string]
  (let [conn   @conn-a
        params (parse-query-string query-string)
        agent  (:agent params)
        hours  (try (some-> (:hours params) parse-long) (catch Exception _ nil))
        hours  (or hours 2)
        events (when conn (try (query-feed conn {:agent agent :hours hours})
                               (catch Exception _ [])))
        agents (when conn (try (known-agents conn) (catch Exception _ [])))
        api-qs (str/join "&" (cond-> [(str "hours=" hours)]
                               agent (conj (str "agent=" agent))))]
    (page-chrome "Activity Feed" params
      [:h1 "Activity Feed"]
      ;; Filter bar — agents
      [:div.filter-bar
       [:span.label "Agent:"]
       [:a {:href "/" :class (str "filter-btn" (when-not agent " active"))} "all"]
       (map (fn [a]
              [:a {:href (str "/?agent=" a (when (not= hours 2) (str "&hours=" hours)))
                   :class (str "filter-btn" (when (= a agent) " active"))}
               a])
            agents)
       [:span.filter-sep "|"]
       [:span.label "Window:"]
       (map (fn [h]
              [:a {:href (str "/?" (when agent (str "agent=" agent "&")) "hours=" h)
                   :class (str "filter-btn" (when (= h hours) " active"))}
               (str h "h")])
            [1 2 6 12 24])]
      [:div.count-line (str (count events) " events in last " hours "h"
                            (when agent (str " for " agent)))]
      ;; Events container with HTMX auto-refresh
      [:div {:id "events"
             :hx-get (str "/api/feed?" api-qs)
             :hx-trigger "every 5s"
             :hx-swap "innerHTML"}
       (render-events events)])))

(defn api-feed
  "Return HTML fragment for HTMX polling at /api/feed."
  [req]
  (let [conn   @conn-a
        params (parse-query-string (:query-string req))
        agent  (:agent params)
        hours  (try (some-> (:hours params) parse-long) (catch Exception _ nil))
        hours  (or hours 2)
        events (when conn (try (query-feed conn {:agent agent :hours hours})
                               (catch Exception _ [])))]
    {:status  200
     :headers {"Content-Type" "text/html; charset=utf-8"}
     :body    (str (h/html (render-events events)))}))

;; ============================================================================
;; REPL Helper
;; ============================================================================

(defn feed
  "REPL: print last N agent events in a compact table.

   (feed 10)              — last 10 events across all agents
   (feed :huginn 5)       — last 5 huginn events
   (feed :sentinel 10 6)  — last 10 sentinel events from last 6 hours"
  ([n] (feed nil n 2))
  ([agent-kw n] (feed agent-kw n 2))
  ([agent-kw n hours]
   (let [conn @conn-a]
     (if-not conn
       (println "Feed not initialized. Call (dvergr.web.feed/init! conn) first.")
       (let [agent  (when agent-kw (name agent-kw))
             events (query-feed conn {:agent agent :hours hours :limit n})]
         (if (empty? events)
           (println (str "No events in last " hours "h"
                         (when agent (str " for " agent)) "."))
           (doseq [msg events]
             (let [agent-id  (or (extract-agent-id (get-in msg [:message/chat :chat/title])) "?")
                   content   (or (:message/content msg) "")
                   tool-uses (:message/tool-uses msg)
                   {:keys [type]} (event-type-info msg)
                   label     (case type :tool "tool" :text "response" :result "result" :input "input" "?")
                   summary   (case type
                               :tool (let [names (str/join ", " (map :tool-use/name tool-uses))]
                                       (str names
                                            (when-not (str/blank? content)
                                              (str " — " (truncate content 60)))))
                               (truncate content 80))]
               (printf "%-5s [%-10s] %-8s %s%n"
                       (or (fmt-time (:message/created-at msg)) "?")
                       agent-id
                       label
                       summary)))))))))
