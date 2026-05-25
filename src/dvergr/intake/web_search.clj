(ns dvergr.intake.web-search
  "Web search via the Brave Search API.

   Recovered from the deleted dvergr.web.search namespace (commit 0e390be);
   the rest of that file was knowledge-graph + summarization plumbing tied
   to the removed web UI. Here we keep just the reliable API call so the
   agent can compose it from SCI like any other intake fn:

     (require '[intake.web :as web])
     (web/search \"datahike clojure\" :count 5)

   Configuration:
     BRAVE_API_KEY (or BRAVE_TOKEN) environment variable."
  (:require [hato.client :as http]
            [jsonista.core :as json]
            [clojure.string :as str]))

(def ^:private brave-api-url
  "https://api.search.brave.com/res/v1/web/search")

(def ^:private max-count 50)

(defn- get-brave-api-key []
  (let [api-key (System/getenv "BRAVE_API_KEY")
        token   (System/getenv "BRAVE_TOKEN")]
    (cond
      (not (str/blank? api-key)) api-key
      (not (str/blank? token))   token
      :else
      (throw (ex-info "BRAVE_API_KEY (or BRAVE_TOKEN) environment variable not set"
                      {:error :missing-api-key})))))

(defn- parse-results
  "Pull the relevant fields out of Brave's web result objects."
  [response]
  (let [body (json/read-value (:body response) json/keyword-keys-object-mapper)
        web  (get-in body [:web :results] [])]
    (mapv (fn [r]
            {:title       (:title r)
             :url         (:url r)
             :description (:description r)
             :age         (:age r)
             :site-name   (some-> (:url r) (java.net.URI.) .getHost)})
          web)))

(defn search
  "Search the web via Brave. Returns a map:

     {:query Q :results [{:title :url :description :age :site-name} ...]}
                                      ;; on success
     {:query Q :error  ERR}            ;; on failure

   Options:
     :count      Number of results (1–50, default 5)
     :freshness  pd (24h), pw (week), pm (month), py (year)
     :country    2-letter country code (default \"US\")"
  [query & {:keys [count freshness country]
            :or {count 5 country "US"}}]
  (try
    (let [api-key (get-brave-api-key)
          params  (cond-> {:q     query
                           :count (min count max-count)}
                    freshness (assoc :freshness freshness)
                    country   (assoc :country country))
          resp    (http/get brave-api-url
                            {:query-params params
                             :headers      {"Accept"               "application/json"
                                            "X-Subscription-Token" api-key}
                             :connect-timeout 30000
                             :throw-exceptions? false})]
      (if (= 200 (:status resp))
        {:query query :results (parse-results resp)}
        {:query query
         :error (str "Brave API error: HTTP " (:status resp))}))
    (catch Exception e
      {:query query :error (.getMessage e)})))
