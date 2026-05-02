(ns dvergr.intake.core
  "Shared utilities for intake source tools."
  (:require [hato.client :as http]
            [jsonista.core :as json]
            [clojure.string :as str])
  (:import [java.time Instant LocalDate ZoneOffset]
           [java.time.format DateTimeFormatter]))

(def ^:private user-agent "dvergr/1.0 (intake)")

(defn fetch-json
  "GET url, parse JSON response. Returns parsed body or {:error ...}."
  [url & {:keys [headers query-params timeout]
          :or {timeout 15000}}]
  (try
    (let [response (http/get url
                             {:headers (merge {"User-Agent" user-agent
                                               "Accept" "application/json"}
                                              headers)
                              :query-params query-params
                              :connect-timeout timeout
                              :throw-exceptions? false
                              :as :string})]
      (if (<= 200 (:status response) 299)
        (json/read-value (:body response) json/keyword-keys-object-mapper)
        {:error (str "HTTP " (:status response))
         :body (:body response)}))
    (catch Exception e
      {:error (.getMessage e)})))

(defn fetch-text
  "GET url, return body as string. Returns {:error ...} on failure."
  [url & {:keys [headers query-params timeout]
          :or {timeout 15000}}]
  (try
    (let [response (http/get url
                             {:headers (merge {"User-Agent" user-agent
                                               "Accept" "text/html,application/json"}
                                              headers)
                              :query-params query-params
                              :connect-timeout timeout
                              :throw-exceptions? false
                              :as :string})]
      (if (<= 200 (:status response) 299)
        (:body response)
        {:error (str "HTTP " (:status response))
         :body (:body response)}))
    (catch Exception e
      {:error (.getMessage e)})))

(defn days-ago-iso
  "Return ISO date string for N days ago."
  [n]
  (-> (LocalDate/now ZoneOffset/UTC)
      (.minusDays n)
      (.format DateTimeFormatter/ISO_LOCAL_DATE)))

(defn days-ago-epoch
  "Return epoch seconds for N days ago."
  [n]
  (-> (Instant/now)
      (.minusSeconds (* n 86400))
      .getEpochSecond))

(defn format-items
  "Render items as LLM-readable markdown.
   Items are maps with :title :url and optional :score :comments :summary :source."
  [header items]
  (if (empty? items)
    (str "## " header "\n\nNo results found.")
    (str "## " header "\n\n"
         (->> items
              (map-indexed
               (fn [i {:keys [title url score comments summary source tag tags]}]
                 (str (inc i) ". **" (str/trim (or title "Untitled")) "**"
                      (when score (str " (" score " pts"))
                      (when (and score comments) (str ", " comments " comments)"))
                      (when (and score (not comments)) ")")
                      (when (and (not score) comments) (str " (" comments " comments)"))
                      (when tag (str " [" tag "]"))
                      (when (seq tags) (str " [" (str/join ", " tags) "]"))
                      "\n   " url
                      (when summary (str "\n   " summary)))))
              (str/join "\n\n")))))

(defn success-response
  "Build a standard tool success response."
  [content source count items]
  {:type :success
   :content content
   :metadata {:source source :count count :items items}})

(defn error-response
  "Build a standard tool error response."
  [message]
  {:type :error
   :error message})
