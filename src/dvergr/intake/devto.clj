(ns dvergr.intake.devto
  "Dev.to intake via Forem API (no auth required)."
  (:require [dvergr.intake.core :as intake]
            [dvergr.tools :as tools]))

(def ^:private api-base "https://dev.to/api")

(defn- parse-article [article]
  {:title (:title article)
   :url (:url article)
   :score (:public_reactions_count article)
   :comments (:comments_count article)
   :tags (some-> (:tag_list article)
                 (as-> tl (if (string? tl)
                            (clojure.string/split tl #",\s*")
                            tl)))
   :source :devto
   :summary (:description article)})

(defn fetch-top
  "Fetch top Dev.to articles."
  [& {:keys [tag time-range count]
      :or {count 20 time-range 1}}]
  (let [params (cond-> {:per_page (min count 30)
                        :top time-range}
                 tag (assoc :tag tag))
        data (intake/fetch-json (str api-base "/articles")
                                :query-params params)]
    (if (:error data)
      data
      (mapv parse-article data))))

(tools/register!
 {:name "devto_top"
  :description "Get top articles from Dev.to. Developer-focused blog platform. Supports tag filtering and time range."
  :parameters {:type "object"
               :properties {:tag {:type "string"
                                  :description "Filter by tag (e.g. 'ai', 'machinelearning', 'webdev', 'clojure')"}
                            :time_range {:type "integer"
                                         :description "Top articles from last N days (default 1)"}
                            :count {:type "integer"
                                    :description "Number of articles (default 20, max 30)"}}
               :required []}
  :execute (fn [{:keys [tag time_range count]} _ctx]
             (let [articles (fetch-top :tag tag
                                       :time-range (or time_range 1)
                                       :count (or count 20))]
               (if (:error articles)
                 (intake/error-response (:error articles))
                 (intake/success-response
                  (intake/format-items
                   (str "Dev.to Top" (when tag (str " [" tag "]")))
                   articles)
                  :devto (clojure.core/count articles) articles))))})
