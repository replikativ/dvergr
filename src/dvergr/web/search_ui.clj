(ns dvergr.web.search-ui
  "Web search page — fulltext search across all indexed data."
  (:require [dvergr.web.layout :as layout]
            [dvergr.search :as search]
            [hiccup2.core :as h]
            [hiccup.util :as hu]
            [clojure.string :as str]))

;; ============================================================================
;; CSS
;; ============================================================================

(def ^:private search-css
  ".search-box {
     display: flex;
     gap: 8px;
     margin-bottom: 1em;
   }
   .search-box input[type=text] {
     flex: 1;
     padding: 8px 14px;
     background: #1a1a2e;
     border: 1px solid #2a2a4a;
     border-radius: 8px;
     color: #ccc;
     font-size: 1em;
     outline: none;
   }
   .search-box input[type=text]:focus {
     border-color: #667eea;
   }
   .search-box select {
     padding: 8px 12px;
     background: #1a1a2e;
     border: 1px solid #2a2a4a;
     border-radius: 8px;
     color: #ccc;
     font-size: 0.9em;
   }
   .search-box button {
     padding: 8px 20px;
     background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
     border: none;
     border-radius: 8px;
     color: #fff;
     font-size: 0.95em;
     cursor: pointer;
   }
   .search-box button:hover { opacity: 0.9; }
   .result-card {
     background: #1a1a2e;
     border: 1px solid #2a2a4a;
     border-radius: 8px;
     padding: 12px 16px;
     margin-bottom: 8px;
   }
   .result-header {
     display: flex;
     align-items: center;
     gap: 8px;
     margin-bottom: 4px;
   }
   .result-title {
     font-weight: 600;
     color: #ddd;
     font-size: 0.95em;
   }
   .result-title a { color: #89b4fa; }
   .result-title a:hover { text-decoration: underline; }
   .source-badge {
     display: inline-block;
     padding: 1px 8px;
     border-radius: 10px;
     font-size: 0.72em;
     font-weight: 600;
     text-transform: uppercase;
     letter-spacing: 0.05em;
   }
   .source-capture      { background: #1e3a1e; color: #52b788; }
   .source-youtube      { background: #3a1e1e; color: #e06060; }
   .source-rss          { background: #3a2e1e; color: #e0a050; }
   .source-web          { background: #1e2a3a; color: #89b4fa; }
   .source-knowledge    { background: #1e1040; color: #a78bfa; }
   .source-conversation { background: #2a2a2a; color: #aaa; }
   .result-meta {
     font-size: 0.78em;
     color: #666;
     display: flex;
     gap: 10px;
     flex-wrap: wrap;
   }
   .result-meta a { color: #667eea; font-size: 0.95em; }
   .search-stats {
     color: #555;
     font-size: 0.82em;
     margin-bottom: 0.8em;
   }")

;; ============================================================================
;; Rendering
;; ============================================================================

(defn- render-result [result]
  (let [{:keys [id source title url domain score]} result]
    [:div.result-card
     [:div.result-header
      [:span {:class (str "source-badge source-" (or source "web"))}
       (or source "?")]
      [:span.result-title
       (if (not (str/blank? url))
         [:a {:href url :target "_blank"} (or title id)]
         (or title id))]
      (when score
        [:span {:style "color:#555;font-size:0.75em;margin-left:auto"}
         (format "%.2f" (double score))])]
     [:div.result-meta
      (when (not (str/blank? domain))
        [:span domain])
      (when (not (str/blank? url))
        [:a {:href url :target "_blank"} (hu/escape-html
                                           (if (> (count url) 80)
                                             (str (subs url 0 77) "...")
                                             url))])]]))

(defn- parse-query-params
  "Parse query string into a map."
  [qs]
  (when (not (str/blank? qs))
    (into {}
      (keep (fn [pair]
              (let [[k v] (str/split pair #"=" 2)]
                (when (and k v)
                  [(keyword k) (java.net.URLDecoder/decode v "UTF-8")])))
            (str/split qs #"&")))))

;; ============================================================================
;; Page
;; ============================================================================

(defn search-page
  "Render the search page. query-string is the raw URL query string."
  [query-string]
  (let [params  (parse-query-params query-string)
        q       (:q params)
        source  (:source params)
        results (when (not (str/blank? q))
                  (search/search q
                    :source (when (not= source "all") source)
                    :limit 50))
        doc-ct  (search/doc-count)
        sources ["all" "capture" "youtube" "rss" "web" "knowledge" "conversation"]]
    (layout/page-chrome
      {:title (if q (str "Search: " q) "Search")
       :active-page :search
       :extra-css search-css}
      [:h1 "Search"]
      [:form.search-box {:method "get" :action "/search"}
       [:input {:type "text" :name "q" :placeholder "Search all indexed data..."
                :value (or q "") :autofocus true}]
       [:select {:name "source"}
        (map (fn [s]
               [:option (cond-> {:value s}
                          (= s (or source "all")) (assoc :selected true))
                s])
             sources)]
       [:button {:type "submit"} "Search"]]
      (if (str/blank? q)
        [:p.search-stats (str doc-ct " documents indexed")]
        [:div
         [:p.search-stats
          (str (count results) " result" (when (not= 1 (count results)) "s")
               " for \"" (hu/escape-html q) "\""
               (when (and source (not= source "all"))
                 (str " in " source)))]
         (if (seq results)
           (map render-result results)
           [:p.not-found "No results found."])]))))
