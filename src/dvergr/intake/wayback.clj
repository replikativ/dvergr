(ns dvergr.intake.wayback
  "Internet Archive Wayback Machine intake — historical website tracking.
   Free, no API key required.

   Uses the CDX Server API to search for historical snapshots of URLs.
   Track website changes over time: messaging pivots, product launches,
   team page changes, pricing changes, etc."
  (:require [dvergr.intake.core :as intake]
            [dvergr.tools :as tools]
            [clojure.string :as str]
            [hato.client :as http]))

(def ^:private cdx-base "https://web.archive.org/cdx/search/cdx")
(def ^:private availability-base "https://archive.org/wayback/available")

(defn search-snapshots
  "Search the Wayback Machine CDX index for snapshots of a URL.
   Returns [{:timestamp :url :mime-type :status :digest :length}].

   Options:
   - :from     — start date (YYYYMMDD or YYYY)
   - :to       — end date
   - :count    — max results
   - :collapse — collapse by field (e.g. 'digest' to dedupe identical pages)
   - :filter   — status code filter (e.g. 'statuscode:200')"
  [url & {:keys [from to count collapse filter]
          :or {count 50}}]
  (let [params (cond-> {:url url
                        :output "json"
                        :limit count}
                 from (assoc :from from)
                 to (assoc :to to)
                 collapse (assoc :collapse collapse)
                 filter (assoc :filter filter))
        data (try
               (let [resp (http/get cdx-base
                            {:query-params params
                             :headers {"User-Agent" "dvergr/1.0 (contact@replikativ.io)"}
                             :throw-exceptions? false
                             :as :string
                             :connect-timeout 30000})]
                 (if (<= 200 (:status resp) 299)
                   (let [parsed (jsonista.core/read-value (:body resp) jsonista.core/keyword-keys-object-mapper)]
                     (if (seq parsed)
                       parsed
                       []))
                   {:error (str "HTTP " (:status resp))}))
               (catch Exception e
                 {:error (.getMessage e)}))]
    (if (:error data)
      data
      (let [;; First row is the header
            header (first data)
            rows (rest data)]
        (->> rows
             (mapv (fn [row]
                     (zipmap (map keyword header) row))))))))

(defn check-availability
  "Check if a specific URL has been archived and get the closest snapshot.
   Returns {:available :url :timestamp :snapshot-url} or {:available false}."
  [url & {:keys [timestamp]}]
  (let [params (cond-> {:url url}
                 timestamp (assoc :timestamp timestamp))
        data (intake/fetch-json availability-base :query-params params)]
    (if (:error data)
      data
      (let [snap (get-in data [:archived_snapshots :closest])]
        (if snap
          {:available true
           :url url
           :timestamp (:timestamp snap)
           :snapshot-url (:url snap)
           :status (:status snap)}
          {:available false :url url})))))

(defn fetch-snapshot
  "Fetch the content of a specific Wayback Machine snapshot.
   timestamp format: YYYYMMDDHHMMSS.
   Returns {:url :timestamp :text :title} or {:error}."
  [url timestamp & {:keys [max-chars] :or {max-chars 8000}}]
  (let [snapshot-url (str "https://web.archive.org/web/" timestamp "/" url)]
    (try
      (let [resp (http/get snapshot-url
                   {:headers {"User-Agent" "dvergr/1.0 (contact@replikativ.io)"}
                    :throw-exceptions? false
                    :as :string
                    :connect-timeout 30000})]
        (if (<= 200 (:status resp) 299)
          (let [body (:body resp)
                ;; Strip Wayback Machine toolbar injection
                clean (str/replace body #"(?s)<!-- BEGIN WAYBACK TOOLBAR INSERT -->.*?<!-- END WAYBACK TOOLBAR INSERT -->" "")
                ;; Strip HTML
                text (-> clean
                         (str/replace #"(?i)<(script|style)[^>]*>[\s\S]*?</\1>" " ")
                         (str/replace #"<[^>]+>" " ")
                         (str/replace #"&amp;" "&")
                         (str/replace #"&lt;" "<")
                         (str/replace #"&gt;" ">")
                         (str/replace #"&quot;" "\"")
                         (str/replace #"&nbsp;" " ")
                         (str/replace #"\s+" " ")
                         str/trim)
                text (if (> (count text) max-chars)
                       (str (subs text 0 max-chars) "\n[truncated]")
                       text)
                title (some-> (re-find #"(?i)<title[^>]*>([^<]+)</title>" body) second str/trim)]
            {:url url
             :timestamp timestamp
             :snapshot-url snapshot-url
             :text text
             :title title})
          {:error (str "HTTP " (:status resp)) :url snapshot-url}))
      (catch Exception e
        {:error (.getMessage e) :url snapshot-url}))))

(defn track-changes
  "Get a timeline of how a URL changed over time.
   Uses digest-based deduplication to only show unique page versions.
   Returns [{:timestamp :snapshot-url :digest}] — one entry per unique version."
  [url & {:keys [from to count]
          :or {count 30}}]
  (let [snapshots (search-snapshots url
                    :from from :to to :count count
                    :collapse "digest"
                    :filter "statuscode:200")]
    (if (:error snapshots)
      snapshots
      (->> snapshots
           (mapv (fn [s]
                   {:timestamp    (:timestamp s)
                    :snapshot-url (str "https://web.archive.org/web/" (:timestamp s) "/" url)
                    :digest       (:digest s)
                    :mime-type    (:mimetype s)
                    :length       (:length s)}))))))

;; Tool registrations

(tools/register!
 {:name "wayback_history"
  :description "Track how a website changed over time using the Wayback Machine. Shows unique page versions with timestamps. Use to detect messaging pivots, product launches, pricing changes, acquisitions, etc."
  :parameters {:type "object"
               :properties {:url {:type "string"
                                  :description "URL to track (e.g. 'example.com' or 'www.acme.io/about')"}
                            :from {:type "string"
                                   :description "Start date (YYYY or YYYYMMDD, default: all time)"}
                            :to {:type "string"
                                 :description "End date (YYYY or YYYYMMDD, default: now)"}
                            :count {:type "integer"
                                    :description "Max unique versions to return (default 30)"}}
               :required ["url"]}
  :execute (fn [{:keys [url from to count]} _ctx]
             (let [changes (track-changes url :from from :to to :count (or count 30))]
               (if (:error changes)
                 (intake/error-response (:error changes))
                 (if (empty? changes)
                   (intake/success-response (str "No Wayback Machine snapshots found for " url) :wayback 0 [])
                   (intake/success-response
                    (str "## Wayback History: " url " (" (clojure.core/count changes) " unique versions)\n\n"
                         (str/join "\n" (map-indexed
                                          (fn [i c]
                                            (str (inc i) ". **" (:timestamp c) "**"
                                                 (when (:length c) (str " (" (:length c) " bytes)"))
                                                 "\n   " (:snapshot-url c)))
                                          changes)))
                    :wayback (clojure.core/count changes) changes)))))})

(tools/register!
 {:name "wayback_snapshot"
  :description "Fetch the text content of a specific Wayback Machine snapshot. Use wayback_history to find timestamps first, then fetch specific versions to compare them."
  :parameters {:type "object"
               :properties {:url {:type "string"
                                  :description "Original URL"}
                            :timestamp {:type "string"
                                        :description "Wayback timestamp (YYYYMMDDHHMMSS)"}
                            :max_chars {:type "integer"
                                        :description "Max characters to return (default 8000)"}}
               :required ["url" "timestamp"]}
  :execute (fn [{:keys [url timestamp max_chars]} _ctx]
             (let [result (fetch-snapshot url timestamp :max-chars (or max_chars 8000))]
               (if (:error result)
                 (intake/error-response (str "Failed: " (:error result)))
                 (intake/success-response
                  (str "## Snapshot: " url " @ " timestamp "\n"
                       (when (:title result) (str "**" (:title result) "**\n"))
                       "\n" (:text result))
                  :wayback 1 [result]))))})
