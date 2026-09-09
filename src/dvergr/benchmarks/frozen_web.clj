(ns dvergr.benchmarks.frozen-web
  "Small immutable web environment for discovery experiments. Not a simulation
   of Brave's ranking: lexical overlap, stable URL tie-breaking, no network."
  (:require [hasch.core :as hasch]
            [jsonista.core :as j]))

(def search-url "https://api.search.brave.com/res/v1/web/search")

(defn- terms [s]
  (set (re-seq #"[\p{L}\p{N}]+" (.toLowerCase ^String s java.util.Locale/ROOT))))

(defn transport
  "Compile {url {:title string :body string}} into a pure host HTTP transport.
   Search accepts Brave-style :query-params (:q, :count, :country). Freshness
   and other options are unsupported rather than silently approximated.
   HTTP errors are data; no request can fall through to the live web.
   Install with add-http-ns!'s host-only :fixture-transport option."
  [pages]
  (when-not (and (map? pages) (seq pages)
                 (every? (fn [[url page]]
                           (and (string? url) (not= search-url url)
                                (re-matches #"https://[^\s]+" url)
                                (map? page) (= #{:title :body} (set (keys page)))
                                (every? string? (vals page)))) pages))
    (throw (ex-info "Expected frozen HTTPS pages with title and body" {})))
  (let [fixture-id (hasch/uuid [:frozen-web/v1 pages])
        indexed (mapv (fn [[url {:keys [title body]}]]
                        {:url url :title title :description body
                         :terms (terms (str title " " body))}) pages)
        response (fn [status body]
                   {:status status :headers {} :body body :dvergr/fixture-id fixture-id})]
    (fn [{:keys [url method query-params body json] :or {method :get}}]
      (cond
        (not= :get method) (response 405 "Frozen web only supports GET")
        (or body json) (response 400 "GET bodies are unsupported")
        (= search-url url)
        (let [{:keys [q count country] :or {count 5 country "US"}} query-params]
          (if-not (and (map? query-params) (string? q) (<= 1 (clojure.core/count q) 4096)
                       (integer? count) (<= 1 count 50) (= "US" country)
                       (every? #{:q :count :country} (keys query-params)))
            (response 400 "Unsupported frozen search parameters")
            (let [query (terms q)
                  hits (->> indexed
                            (map #(assoc % :score (clojure.core/count (filter query (:terms %)))))
                            (filter #(pos? (:score %)))
                            (sort-by (juxt (comp - :score) :url))
                            (take count)
                            (mapv #(dissoc % :score :terms)))]
              (response 200 (j/write-value-as-string {:web {:results hits}})))))
        (seq query-params) (response 400 "Frozen pages do not support query parameters")
        (contains? pages url) (response 200 (:body (get pages url)))
        :else (response 404 "Not present in frozen web")))))
