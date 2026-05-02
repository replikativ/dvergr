(ns dvergr.web.entities
  "Entity-centric web pages for the knowledge graph.

   Routes (mounted in web.server):
     GET /entities          — list all entities with type filter tabs
     GET /entities/:title   — view entity detail with contexts, links, proposals"
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
  "Store the shared Datahike connection. Call once at startup."
  [datahike-conn]
  (reset! conn-a datahike-conn))

;; ============================================================================
;; Queries
;; ============================================================================

(defn- all-entities []
  (when-let [conn @conn-a]
    (try
      (->> (dh/q '[:find [(pull ?e [:entity/id :entity/title :entity/summary
                                    :entity/mention-count :entity/updated-at
                                    :entity/type :entity/url :entity/tags]) ...]
                   :where [?e :entity/id _]]
                 @conn)
           (sort-by #(- (or (:entity/mention-count %) 0))))
      (catch Exception e
        (tel/log! {:level :warn :id :entities/list-error :data {:error (.getMessage e)}}
                  "Entity list query failed")
        nil))))

(defn- get-entity [title]
  (when-let [conn @conn-a]
    (try
      (dh/q '[:find (pull ?e [*]) .
              :in $ ?t
              :where [?e :entity/title ?t]]
            @conn title)
      (catch Exception _ nil))))

(defn- get-related-proposals [title]
  (when-let [conn @conn-a]
    (try
      (dh/q '[:find [(pull ?p [:proposal/id :proposal/status :proposal/summary
                                :proposal/task :proposal/created-at]) ...]
              :in $ ?pattern
              :where
              [?p :proposal/id _]
              [?p :proposal/task ?task]
              [(clojure.string/includes? ?task ?pattern)]]
            @conn title)
      (catch Exception _ nil))))

;; ============================================================================
;; Helpers
;; ============================================================================

(defn- encode-title [^String title]
  (java.net.URLEncoder/encode title "UTF-8"))

(defn- fmt-date [d]
  (when d (subs (str d) 0 10)))

(defn- type-color [entity-type]
  (case entity-type
    :competitor ["#3a1e2e" "#f38ba8"]
    :client     ["#1e3a2a" "#52b788"]
    :partner    ["#1e2a3a" "#89b4fa"]
    :project    ["#3a2a1e" "#fab387"]
    :technology ["#2a1e3a" "#cba6f7"]
    ["#2a2a2a" "#888"]))

(defn- type-badge [entity-type]
  (when entity-type
    (let [[bg fg] (type-color entity-type)]
      [:span.type-badge {:style (str "background:" bg ";color:" fg ";")}
       (name entity-type)])))

(defn- tag-pill [tag]
  [:span.tag tag])

(defn- truncate [s n]
  (if (and s (> (count s) n))
    (str (subs s 0 n) "...")
    s))

(defn- parse-context
  "Extract structured fields from a context string."
  [s]
  {:url       (second (re-find #"url:(\S+)" s))
   :source    (second (re-find #"source:(\S+)" s))
   :relevance (some-> (re-find #"relevance:(\d+)" s) second)
   :text      (-> s
                  (str/replace #"(source|url|relevance):\S+" "")
                  str/trim
                  (str/replace #"\n{2,}" "\n"))})

(defn- text->nodes
  "Split text on [[wiki-links]], returning hiccup-compatible nodes."
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
   .entity-card {
     background: #1a1a2e;
     border: 1px solid #2a2a4a;
     border-radius: 8px;
     padding: 10px 16px;
     margin: 6px 0;
     transition: border-color 0.2s;
   }
   .entity-card:hover { border-color: #667eea; }
   .entity-title { font-size: 1em; font-weight: 600; color: #e0e0e0; }
   .entity-summary { color: #888; font-size: 0.88em; margin-top: 3px; }
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
   .type-badge {
     display: inline-block;
     padding: 1px 8px;
     border-radius: 8px;
     font-size: 0.72em;
     margin-left: 8px;
     vertical-align: middle;
     text-transform: uppercase;
     letter-spacing: 0.05em;
   }
   .tag {
     display: inline-block;
     background: #1a1a2e;
     border: 1px solid #2a2a4a;
     color: #888;
     padding: 0 6px;
     border-radius: 10px;
     font-size: 0.72em;
     margin: 2px 3px 2px 0;
   }
   .tags-row { margin-top: 5px; }
   .url-link {
     font-size: 0.82em;
     margin-left: 10px;
     vertical-align: middle;
     color: #667eea;
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
   .breadcrumb { color: #555; margin-bottom: 1em; font-size: 0.88em; }
   .breadcrumb a { color: #667eea; }
   .filter-tabs { margin: 0.8em 0 1.2em; display: flex; gap: 6px; flex-wrap: wrap; }
   .filter-tab {
     background: #1a1a2e;
     border: 1px solid #2a2a4a;
     color: #888;
     padding: 3px 14px;
     border-radius: 16px;
     font-size: 0.82em;
     cursor: pointer;
     text-decoration: none;
     transition: all 0.2s;
   }
   .filter-tab:hover, .filter-tab.active { border-color: #667eea; color: #ccc; text-decoration: none; }
   .filter-tab.active { background: #1e1040; color: #a78bfa; }
   .proposal-link {
     background: #12122a;
     border: 1px solid #2a2a4a;
     border-radius: 6px;
     padding: 6px 12px;
     margin: 4px 0;
     display: block;
     font-size: 0.88em;
   }
   .proposal-link:hover { border-color: #667eea; text-decoration: none; }")

;; ============================================================================
;; Page Chrome
;; ============================================================================

(defn- page-chrome [title-str & content]
  (apply layout/page-chrome
         {:title title-str :active-page :entities :extra-css css :htmx? false}
         content))

;; ============================================================================
;; List Page
;; ============================================================================

(def ^:private entity-types
  [:competitor :client :partner :project :technology])

(defn entities-list-page
  ([] (entities-list-page nil))
  ([filter-type]
   (let [all (or (all-entities) [])
         entities (if filter-type
                    (filter #(= filter-type (:entity/type %)) all)
                    all)
         n (count entities)
         type-counts (frequencies (keep :entity/type all))]
     (page-chrome "Entities"
       [:h1 "Entities"]
       [:div.filter-tabs
        [:a.filter-tab {:href "/entities"
                        :class (when-not filter-type "active")}
         (str "All (" (count all) ")")]
        (for [t entity-types]
          (let [cnt (get type-counts t 0)]
            (when (pos? cnt)
              [:a.filter-tab {:href (str "/entities?type=" (name t))
                              :class (when (= filter-type t) "active")}
               (str (str/capitalize (name t)) " (" cnt ")")])))]
       [:div.count-line (str n (if (= n 1) " entity" " entities")
                             (when filter-type (str " of type " (name filter-type))))]
       (if (seq entities)
         (map (fn [e]
                (let [title    (:entity/title e)
                      summary  (:entity/summary e)
                      mentions (or (:entity/mention-count e) 0)
                      etype    (:entity/type e)
                      etags    (:entity/tags e)
                      eurl     (:entity/url e)]
                  [:div.entity-card {:data-title title}
                   [:div
                    [:a.entity-title {:href (str "/entities/" (encode-title title))} title]
                    (type-badge etype)
                    [:span.mention-badge (str mentions "x")]
                    (when eurl
                      [:a.url-link {:href eurl :target "_blank"} "link"])]
                   (when summary
                     [:div.entity-summary (truncate summary 150)])
                   (when (seq etags)
                     [:div.tags-row (map tag-pill etags)])
                   [:div.meta (fmt-date (:entity/updated-at e))]]))
              entities)
         [:p {:style "color:#555;"} "No entities found."])))))

;; ============================================================================
;; Detail Page
;; ============================================================================

(defn entity-detail-page [title]
  (if-let [entity (get-entity title)]
    (let [contexts     (seq (:entity/contexts entity))
          parsed-ctxs  (map parse-context contexts)
          etype        (:entity/type entity)
          eurl         (:entity/url entity)
          etags        (:entity/tags entity)
          related-props (get-related-proposals title)
          ;; Get linked entities
          linked       (when-let [links (seq (:entity/links entity))]
                         (keep (fn [link]
                                 (when-let [conn @conn-a]
                                   (try
                                     (let [eid (if (map? link) (:db/id link) link)]
                                       (dh/q '[:find (pull ?e [:entity/title :entity/type]) .
                                               :in $ ?eid
                                               :where [?eid :entity/title _]]
                                             @conn eid))
                                     (catch Exception _ nil))))
                               links))]
      (page-chrome title
        [:div.breadcrumb
         [:a {:href "/entities"} "Entities"] " / " (hu/escape-html title)]
        [:div
         [:span.page-title (hu/escape-html title)]
         (type-badge etype)
         (when eurl
           [:a.url-link {:href eurl :target "_blank"} "visit"])]
        (when (seq etags)
          [:div.tags-row {:style "margin-top:8px;"} (map tag-pill etags)])
        (when-let [summary (:entity/summary entity)]
          (into [:div.summary-block] (text->nodes summary)))
        [:div.meta
         [:span (str (or (:entity/mention-count entity) 0) " mentions")]
         (when-let [d (:entity/created-at entity)]
           [:span {:style "margin-left:12px;"} (str "added " (fmt-date d))])
         (when-let [d (:entity/updated-at entity)]
           [:span {:style "margin-left:12px;"} (str "updated " (fmt-date d))])]
        ;; Contexts
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
                 parsed-ctxs)))
        ;; Related entities (backlinks)
        (when (seq linked)
          (list
            [:h2 "Related Entities"]
            (map (fn [le]
                   [:div {:style "margin:4px 0;"}
                    [:a {:href (str "/entities/" (encode-title (:entity/title le)))}
                     (:entity/title le)]
                    (type-badge (:entity/type le))])
                 linked)))
        ;; Related proposals
        (when (seq related-props)
          (list
            [:h2 "Related Proposals"]
            (map (fn [p]
                   [:a.proposal-link {:href (str "/proposals/" (:proposal/id p))}
                    (str (truncate (:proposal/summary p) 100)
                         " [" (some-> (:proposal/status p) name) "]")])
                 related-props)))))
    (page-chrome "Not found"
      [:div.breadcrumb
       [:a {:href "/entities"} "Entities"] " / " (hu/escape-html title)]
      [:div.not-found
       [:p (str "No entity \"" (hu/escape-html title) "\"")]
       [:p [:a {:href "/entities"} "back to Entities"]]])))
