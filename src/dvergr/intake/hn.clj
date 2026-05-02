(ns dvergr.intake.hn
  "Hacker News intake via Algolia API (no auth required)."
  (:require [dvergr.intake.core :as intake]
            [dvergr.tools :as tools]))

(def ^:private algolia-base "https://hn.algolia.com/api/v1")

(defn- parse-story [hit]
  {:title (:title hit)
   :url (or (:url hit) (str "https://news.ycombinator.com/item?id=" (:objectID hit)))
   :score (:points hit)
   :comments (:num_comments hit)
   :source :hn
   :hn-id (:objectID hit)})

(defn fetch-top
  "Fetch HN front page stories."
  [& {:keys [count min-points query]
      :or {count 20}}]
  (let [params (cond-> {:tags "front_page"
                        :hitsPerPage count}
                 query (assoc :query query))
        data (intake/fetch-json (str algolia-base "/search")
                                :query-params params)]
    (if (:error data)
      data
      (->> (:hits data)
           (map parse-story)
           (filter #(or (nil? min-points) (>= (or (:score %) 0) min-points)))
           vec))))

(defn search-stories
  "Search HN stories by keyword."
  [query & {:keys [count days-back min-points]
            :or {count 20 days-back 7}}]
  (let [params (cond-> {:query query
                        :tags "story"
                        :hitsPerPage count
                        :numericFilters (str "created_at_i>" (intake/days-ago-epoch days-back))}
                 min-points (update :numericFilters #(str % ",points>" min-points)))
        data (intake/fetch-json (str algolia-base "/search_by_date")
                                :query-params params)]
    (if (:error data)
      data
      (->> (:hits data)
           (map parse-story)
           vec))))

;; Tool registration

(tools/register!
 {:name "hn_top"
  :description "Get top stories from Hacker News front page. Returns titles, URLs, scores, and comment counts. Use to discover trending tech discussions."
  :parameters {:type "object"
               :properties {:count {:type "integer"
                                    :description "Number of stories to return (default 20, max 50)"}
                            :min_points {:type "integer"
                                         :description "Minimum point threshold to filter by"}
                            :query {:type "string"
                                    :description "Optional keyword filter on front page stories"}}
               :required []}
  :execute (fn [{:keys [count min_points query]} _ctx]
             (let [stories (fetch-top :count (or count 20)
                                      :min-points min_points
                                      :query query)]
               (if (:error stories)
                 (intake/error-response (:error stories))
                 (intake/success-response
                  (intake/format-items "HN Top Stories" stories)
                  :hn (clojure.core/count stories) stories))))})

(tools/register!
 {:name "hn_search"
  :description "Search Hacker News stories by keyword with date filtering. Returns matching stories sorted by date."
  :parameters {:type "object"
               :properties {:query {:type "string"
                                    :description "Search query"}
                            :days_back {:type "integer"
                                        :description "How many days back to search (default 7)"}
                            :min_points {:type "integer"
                                         :description "Minimum points filter"}
                            :count {:type "integer"
                                    :description "Number of results (default 20, max 50)"}}
               :required ["query"]}
  :execute (fn [{:keys [query days_back min_points count]} _ctx]
             (let [stories (search-stories query
                                           :count (or count 20)
                                           :days-back (or days_back 7)
                                           :min-points min_points)]
               (if (:error stories)
                 (intake/error-response (:error stories))
                 (intake/success-response
                  (intake/format-items (str "HN Search: " query) stories)
                  :hn (clojure.core/count stories) stories))))})
