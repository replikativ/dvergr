(ns dvergr.web.wiki
  "Read-only wiki view for knowledge graph entries.

   Routes (mounted in web.server):
     GET /wiki          — list all entries sorted by mention count
     GET /wiki/:title   — view a single entry with contexts, backlinks, source URL"
  (:require [hiccup2.core :as h]
            [hiccup.util :as hu]
            [datahike.api :as dh]
            [clojure.string :as str]
            [dvergr.web.layout :as layout]
            [taoensso.telemere :as tel]))

;; ============================================================================
;; State
;; ============================================================================

(defonce ^:private conn-a (atom nil))

(defn init!
  "Store the shared Datahike connection for wiki queries. Call once at startup."
  [datahike-conn]
  (reset! conn-a datahike-conn))

;; ============================================================================
;; Queries
;; ============================================================================

(defn- all-entities []
  (when-let [conn @conn-a]
    (try
      (->> (dh/q '[:find [(pull ?e [:entity/id :entity/title :entity/summary
                                    :entity/mention-count :entity/updated-at]) ...]
                   :where [?e :entity/id _]]
                 @conn)
           (sort-by #(- (or (:entity/mention-count %) 0))))
      (catch Exception e
        (tel/log! {:level :warn :id :wiki/list-error :data {:error (.getMessage e)}}
                  "Wiki list query failed")
        nil))))

(defn- get-entity [title]
  (when-let [conn @conn-a]
    (try
      (dh/q '[:find (pull ?e [*]) .
              :in $ ?t
              :where [?e :entity/title ?t]]
            @conn title)
      (catch Exception _ nil))))

(defn- get-conversations
  "Find conversations that called knowledge_add for this entity title.
   Returns [{:chat-title \"...\" :created-at inst :chat-id uuid}] sorted newest-first."
  [title]
  (when-let [conn @conn-a]
    (try
      (->> (dh/q '[:find ?chat-title ?created ?chat-id
                    :in $ ?entity-title
                    :where
                    [?tu :tool-use/name "knowledge_add"]
                    [?tu :tool-use/input ?inp]
                    [?inp :tool-input.knowledge-add/title ?entity-title]
                    [?m :message/tool-uses ?tu]
                    [?m :message/created-at ?created]
                    [?m :message/chat ?c]
                    [?c :chat/title ?chat-title]
                    [?c :chat/id ?chat-id]]
                  @conn title)
           (map (fn [[chat-title created chat-id]]
                  {:chat-title chat-title
                   :created-at created
                   :chat-id chat-id}))
           ;; Deduplicate by chat-id, keep earliest per conversation
           (group-by :chat-id)
           vals
           (map (fn [group]
                  (let [first-mention (apply min-key #(.getTime (:created-at %)) group)]
                    (assoc first-mention :mention-count (count group)))))
           (sort-by #(- (.getTime (:created-at %)))))
      (catch Exception _ nil))))

;; ============================================================================
;; Helpers
;; ============================================================================

(defn- encode-title [^String title]
  (java.net.URLEncoder/encode title "UTF-8"))

(defn- extract-agent-name
  "Extract agent name from a chat title like 'huginn-1771786801167' or 'var with Web'."
  [chat-title]
  (when chat-title
    (first (str/split chat-title #"[-\s]"))))

(defn- parse-context
  "Extract structured fields from a context string like
   'source:hn url:https://... relevance:4\\nFree-text...'"
  [s]
  {:url       (second (re-find #"url:(\S+)" s))
   :source    (second (re-find #"source:(\S+)" s))
   :relevance (some-> (re-find #"relevance:(\d+)" s) second)
   :text      (-> s
                  (str/replace #"(source|url|relevance):\S+" "")
                  str/trim
                  (str/replace #"\n{2,}" "\n"))})

(defn- fmt-date [d]
  (when d (subs (str d) 0 10)))

(defn- text->nodes
  "Split text on [[wiki-links]], returning a flat seq of hiccup-compatible nodes.
   Plain text segments are HTML-escaped; wiki-links become <a> elements."
  [text]
  (when (seq text)
    (let [matcher (re-matcher #"\[\[([^\]]+)\]\]" text)
          nodes   (transient [])
          last-end (atom 0)]
      (while (.find matcher)
        (let [plain (subs text @last-end (.start matcher))]
          (when (seq plain)
            (conj! nodes (hu/raw-string
                           (-> plain hu/escape-html
                               (str/replace "\n" "<br>"))))))
        (let [t (.group matcher 1)]
          (conj! nodes [:a.wiki-link {:href (str "/wiki/" (encode-title t))} t]))
        (reset! last-end (.end matcher)))
      (let [remaining (subs text @last-end)]
        (when (seq remaining)
          (conj! nodes (hu/raw-string
                         (-> remaining hu/escape-html
                             (str/replace "\n" "<br>"))))))
      (persistent! nodes))))

;; ============================================================================
;; CSS
;; ============================================================================

(def ^:private css
  "a.wiki-link { color: #89b4fa; }
   .entry-card {
     background: #1a1a2e;
     border: 1px solid #2a2a4a;
     border-radius: 8px;
     padding: 10px 16px;
     margin: 6px 0;
     transition: border-color 0.2s;
   }
   .entry-card:hover { border-color: #667eea; }
   .entry-title { font-size: 1em; font-weight: 600; color: #e0e0e0; }
   .entry-summary { color: #888; font-size: 0.88em; margin-top: 3px; }
   .mention-badge {
     display: inline-block;
     background: #1e1040;
     color: #a78bfa;
     padding: 1px 7px;
     border-radius: 8px;
     font-size: 0.72em;
     margin-left: 8px;
     vertical-align: middle;
   }
   .meta { font-size: 0.78em; color: #555; margin-top: 3px; }
   .page-title { font-size: 1.5em; font-weight: 700; color: #e0e0e0; }
   .summary-block { margin: 0.8em 0 1em; line-height: 1.75; color: #bbb; }
   .ctx-block {
     background: #12122a;
     border-left: 3px solid #2a2a4a;
     border-radius: 0 6px 6px 0;
     padding: 8px 12px;
     margin: 6px 0;
     font-size: 0.88em;
   }
   .ctx-block:hover { border-left-color: #667eea; }
   .ctx-meta { margin-bottom: 4px; }
   .source-badge {
     display: inline-block;
     background: #1e2a3a;
     color: #89b4fa;
     padding: 1px 7px;
     border-radius: 8px;
     font-size: 0.75em;
     margin-right: 6px;
   }
   .relevance-badge { color: #f9a825; font-size: 0.8em; }
   .ctx-url { font-size: 0.82em; word-break: break-all; margin-top: 3px; }
   .ctx-text { color: #999; margin-top: 5px; line-height: 1.6; }
   .filter-bar { margin: 0.8em 0 1.2em; }
   .filter-bar input {
     background: #1a1a2e;
     border: 1px solid #2a2a4a;
     border-radius: 6px;
     color: #ccc;
     padding: 5px 12px;
     font-size: 0.88em;
     width: 280px;
     outline: none;
   }
   .filter-bar input:focus { border-color: #667eea; }
   .source-url-link { font-size: 0.82em; margin-left: 10px; vertical-align: middle; }
   .conv-block {
     background: #12122a;
     border: 1px solid #2a2a4a;
     border-radius: 6px;
     padding: 6px 12px;
     margin: 4px 0;
     display: flex;
     align-items: center;
     gap: 8px;
     font-size: 0.88em;
   }
   .conv-block:hover { border-color: #667eea; }
   .conv-agent {
     display: inline-block;
     background: #1e1040;
     color: #a78bfa;
     padding: 1px 7px;
     border-radius: 8px;
     font-size: 0.75em;
   }
   .conv-date { color: #555; font-size: 0.82em; margin-left: auto; }
   .conv-count { color: #667eea; font-size: 0.75em; }")

;; ============================================================================
;; Page Chrome
;; ============================================================================

(defn- page-chrome [title-str & content]
  (apply layout/page-chrome
         {:title title-str :active-page :wiki :extra-css css :htmx? false}
         content))

;; ============================================================================
;; List Page
;; ============================================================================

(defn wiki-list-page []
  (let [entities (or (all-entities) [])
        n        (count entities)]
    (page-chrome "Knowledge Graph"
      [:h1 "Knowledge Graph"]
      [:div.filter-bar
       [:input {:type        "text"
                :placeholder "Filter entries…"
                :oninput     (str "var v=this.value.toLowerCase();"
                                  "document.querySelectorAll('.entry-card').forEach(function(c){"
                                  "  c.style.display=c.dataset.title.toLowerCase().includes(v)?'':'none'"
                                  "})")}]]
      [:div.count-line (str n (if (= n 1) " entry" " entries"))]
      (if (seq entities)
        (map (fn [e]
               (let [title    (:entity/title e)
                     summary  (:entity/summary e)
                     mentions (or (:entity/mention-count e) 0)]
                 [:div.entry-card {:data-title title}
                  [:div
                   [:a.entry-title {:href (str "/wiki/" (encode-title title))} title]
                   [:span.mention-badge (str mentions "×")]]
                  (when summary
                    [:div.entry-summary summary])
                  [:div.meta (fmt-date (:entity/updated-at e))]]))
             entities)
        [:p {:style "color:#555;"} "No entries yet."]))))

;; ============================================================================
;; Entry Page
;; ============================================================================

(defn wiki-entry-page [title]
  (if-let [entity (get-entity title)]
    (let [contexts    (seq (:entity/contexts entity))
          parsed-ctxs (map parse-context contexts)
          first-url   (some :url parsed-ctxs)
          convos      (get-conversations title)]
      (page-chrome title
        [:div.breadcrumb
         [:a {:href "/wiki"} "Knowledge Graph"] " / " (hu/escape-html title)]
        [:div
         [:span.page-title (hu/escape-html title)]
         (when first-url
           [:a.source-url-link {:href first-url :target "_blank"} "↗ source"])]
        (when-let [summary (:entity/summary entity)]
          (into [:div.summary-block] (text->nodes summary)))
        [:div.meta
         [:span (str (or (:entity/mention-count entity) 0) " mentions")]
         (when-let [d (:entity/created-at entity)]
           [:span {:style "margin-left:12px;"} (str "added " (fmt-date d))])
         (when-let [d (:entity/updated-at entity)]
           [:span {:style "margin-left:12px;"} (str "updated " (fmt-date d))])]
        ;; Conversations that updated this entity
        (when (seq convos)
          (list
            [:h2 "Conversations"]
            (map (fn [{:keys [chat-id chat-title created-at mention-count]}]
                   (let [agent-name (extract-agent-name chat-title)
                         at-ms      (when created-at (.getTime created-at))
                         conv-href  (str "/conversations/" chat-id
                                         (when at-ms (str "?at=" at-ms)))]
                     [:a.conv-block {:href conv-href
                                     :style "text-decoration:none;color:inherit;"}
                      [:span.conv-agent agent-name]
                      [:span (hu/escape-html chat-title)]
                      (when (> mention-count 1)
                        [:span.conv-count (str mention-count "×")])
                      [:span.conv-date (fmt-date created-at)]]))
                 convos)))
        (when contexts
          (list
            [:h2 "Context"]
            (map (fn [{:keys [source relevance url text]}]
                   [:div.ctx-block
                    [:div.ctx-meta
                     (when source [:span.source-badge source])
                     (when relevance [:span.relevance-badge (str "relevance " relevance)])]
                    (when url
                      [:div.ctx-url [:a {:href url :target "_blank"} url]])
                    (when (seq text)
                      (into [:div.ctx-text] (text->nodes text)))])
                 parsed-ctxs)))))
    (page-chrome "Not found"
      [:div.breadcrumb
       [:a {:href "/wiki"} "Knowledge Graph"] " / " (hu/escape-html title)]
      [:div.not-found
       [:p (str "No entry for \u201c" (hu/escape-html title) "\u201d")]
       [:p [:a {:href "/wiki"} "← back to Knowledge Graph"]]])))
