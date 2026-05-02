(ns dvergr.intake.bluesky
  "Bluesky intake via AT Protocol (requires BLUESKY_HANDLE + BLUESKY_APP_PASSWORD)."
  (:require [dvergr.intake.core :as intake]
            [dvergr.tools :as tools]
            [hato.client :as http]
            [jsonista.core :as json]
            [clojure.string :as str]))

(def ^:private pds-base "https://bsky.social")

;; Session management — cached, auto-refreshes on 401
(def ^:private session (atom nil))

(defn- get-credentials []
  (let [handle (System/getenv "BLUESKY_HANDLE")
        password (System/getenv "BLUESKY_APP_PASSWORD")]
    (when (and (not (str/blank? handle))
               (not (str/blank? password)))
      {:handle handle :password password})))

(defn- create-session!
  "Authenticate and store session token."
  []
  (if-let [{:keys [handle password]} (get-credentials)]
    (try
      (let [response (http/post (str pds-base "/xrpc/com.atproto.server.createSession")
                                {:content-type :json
                                 :body (json/write-value-as-string
                                        {:identifier handle :password password})
                                 :throw-exceptions? false
                                 :as :string})]
        (if (= 200 (:status response))
          (let [data (json/read-value (:body response) json/keyword-keys-object-mapper)]
            (reset! session {:access-jwt (:accessJwt data)
                             :did (:did data)})
            @session)
          {:error (str "Bluesky auth failed: HTTP " (:status response))}))
      (catch Exception e
        {:error (str "Bluesky auth error: " (.getMessage e))}))
    {:error "Set BLUESKY_HANDLE and BLUESKY_APP_PASSWORD env vars for Bluesky access"}))

(defn- ensure-session! []
  (or @session (create-session!)))

(defn- auth-headers []
  (let [sess (ensure-session!)]
    (if (:error sess)
      nil
      {"Authorization" (str "Bearer " (:access-jwt sess))})))

(defn- parse-post [post]
  (let [record (:record post)
        text (or (:text record) "")]
    {:title (if (> (count text) 120)
              (str (subs text 0 120) "...")
              text)
     :url (let [uri (:uri post)]
            (when uri
              (let [parts (str/split uri #"/")]
                (str "https://bsky.app/profile/"
                     (nth parts 2 "")
                     "/post/"
                     (last parts)))))
     :score (+ (or (:likeCount post) 0)
               (or (:repostCount post) 0))
     :comments (:replyCount post)
     :source :bluesky}))

(defn search-posts
  "Search Bluesky posts. Requires auth."
  [query & {:keys [count days-back]
            :or {count 20}}]
  (let [headers (auth-headers)]
    (if (nil? headers)
      (ensure-session!) ;; returns the error map
      (let [params (cond-> {:q query
                            :limit (min count 25)}
                     days-back (assoc :since (str (intake/days-ago-iso days-back) "T00:00:00Z")))
            data (intake/fetch-json
                  (str pds-base "/xrpc/app.bsky.feed.searchPosts")
                  :headers headers
                  :query-params params)]
        (if (:error data)
          ;; On 401, clear session and retry once
          (if (str/includes? (str (:error data)) "401")
            (do (reset! session nil)
                (let [new-headers (auth-headers)]
                  (if (nil? new-headers)
                    (ensure-session!)
                    (intake/fetch-json
                     (str pds-base "/xrpc/app.bsky.feed.searchPosts")
                     :headers new-headers
                     :query-params params))))
            data)
          (->> (:posts data)
               (mapv parse-post)))))))

(tools/register!
 {:name "bluesky_search"
  :description "Search Bluesky posts by keyword. Requires BLUESKY_HANDLE and BLUESKY_APP_PASSWORD env vars. Growing tech community, especially AI/ML researchers and open-source developers."
  :parameters {:type "object"
               :properties {:query {:type "string"
                                    :description "Search query"}
                            :count {:type "integer"
                                    :description "Number of results (default 20, max 25)"}
                            :days_back {:type "integer"
                                        :description "Only posts from last N days"}}
               :required ["query"]}
  :execute (fn [{:keys [query count days_back]} _ctx]
             (let [posts (search-posts query
                                       :count (or count 20)
                                       :days-back days_back)]
               (if (:error posts)
                 (intake/error-response (:error posts))
                 (intake/success-response
                  (intake/format-items (str "Bluesky: " query) posts)
                  :bluesky (clojure.core/count posts) posts))))})
