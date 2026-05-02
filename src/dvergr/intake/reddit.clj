(ns dvergr.intake.reddit
  "Reddit intake via public JSON endpoints (no auth required).
   Uses old.reddit.com JSON endpoints.

   NOTE: Reddit blocks requests with an explicit Accept header (even */*) when
   combined with a non-browser User-Agent.  We bypass fetch-json and call hato
   directly so the JVM default Accept header (absent) is used instead."
  (:require [dvergr.intake.core :as intake]
            [dvergr.tools :as tools]
            [hato.client :as http]
            [jsonista.core :as json]))

;; Browser UA is required — reddit 403s bot UAs on subreddit-scoped searches.
(def ^:private reddit-ua
  "Mozilla/5.0 (X11; Linux x86_64; rv:124.0) Gecko/20100101 Firefox/124.0 (dvergr intake)")

(defn- fetch-reddit-json
  "GET a reddit .json endpoint.  Does NOT send an Accept header — Reddit
   returns 403 when it sees Accept: application/json from non-browser clients."
  [url query-params]
  (try
    (let [resp (http/get url
                         {:headers      {"User-Agent" reddit-ua}
                          :query-params query-params
                          :throw-exceptions? false
                          :as :string})]
      (if (<= 200 (:status resp) 299)
        (json/read-value (:body resp) json/keyword-keys-object-mapper)
        {:error (str "HTTP " (:status resp))}))
    (catch Exception e
      {:error (.getMessage e)})))

(defn- parse-post [post-data]
  (let [d (:data post-data)]
    {:title    (:title d)
     :url      (if (:is_self d)
                 (str "https://old.reddit.com" (:permalink d))
                 (:url d))
     :score    (:score d)
     :comments (:num_comments d)
     :subreddit (:subreddit d)
     :source   :reddit
     :summary  (when (and (:is_self d) (:selftext d))
                 (let [text (:selftext d)]
                   (if (> (count text) 200)
                     (str (subs text 0 200) "...")
                     text)))}))

(defn fetch-top
  "Fetch top posts from a subreddit."
  [subreddit & {:keys [count time-range]
                :or {count 20 time-range "day"}}]
  (let [url  (str "https://old.reddit.com/r/" subreddit "/top.json")
        data (fetch-reddit-json url {:t time-range :limit (min count 100) :raw_json 1})]
    (if (:error data)
      data
      (->> (get-in data [:data :children])
           (mapv parse-post)))))

(defn search-posts
  "Search Reddit posts."
  [query & {:keys [subreddit count time-range]
            :or {count 20 time-range "week"}}]
  (let [url  (if subreddit
               (str "https://old.reddit.com/r/" subreddit "/search.json")
               "https://old.reddit.com/search.json")
        params (cond-> {:q query :limit (min count 100) :sort "relevance"
                        :t time-range :raw_json 1}
                 subreddit (assoc :restrict_sr "on"))
        data (fetch-reddit-json url params)]
    (if (:error data)
      data
      (->> (get-in data [:data :children])
           (mapv parse-post)))))

(tools/register!
 {:name "reddit_top"
  :description "Get top posts from a Reddit subreddit. Good for community discussions and trends. Popular tech subs: MachineLearning, LocalLLaMA, Clojure, programming, dataengineering."
  :parameters {:type "object"
               :properties {:subreddit  {:type "string"
                                         :description "Subreddit name (without r/)"}
                            :count      {:type "integer"
                                         :description "Number of posts (default 20, max 100)"}
                            :time_range {:type "string"
                                         :description "Time range: hour, day, week, month, year, all (default day)"
                                         :enum ["hour" "day" "week" "month" "year" "all"]}}
               :required ["subreddit"]}
  :execute (fn [{:keys [subreddit count time_range]} _ctx]
             (let [posts (fetch-top subreddit
                                    :count (or count 20)
                                    :time-range (or time_range "day"))]
               (if (:error posts)
                 (intake/error-response (:error posts))
                 (intake/success-response
                  (intake/format-items (str "r/" subreddit " top") posts)
                  :reddit (clojure.core/count posts) posts))))})

(tools/register!
 {:name "reddit_search"
  :description "Search Reddit posts by keyword. Can search all of Reddit or within a specific subreddit."
  :parameters {:type "object"
               :properties {:query      {:type "string"
                                         :description "Search query"}
                            :subreddit  {:type "string"
                                         :description "Optional: limit search to this subreddit"}
                            :count      {:type "integer"
                                         :description "Number of results (default 20, max 100)"}
                            :time_range {:type "string"
                                         :description "Time range: hour, day, week, month, year, all (default week)"
                                         :enum ["hour" "day" "week" "month" "year" "all"]}}
               :required ["query"]}
  :execute (fn [{:keys [query subreddit count time_range]} _ctx]
             (let [posts (search-posts query
                                       :subreddit subreddit
                                       :count (or count 20)
                                       :time-range (or time_range "week"))]
               (if (:error posts)
                 (intake/error-response (:error posts))
                 (intake/success-response
                  (intake/format-items (str "Reddit search: " query) posts)
                  :reddit (clojure.core/count posts) posts))))})
