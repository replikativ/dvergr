(ns dvergr.intake.twitter
  "Twitter/X tweet lookup via FXTwitter API — no auth required for public tweets.
   FXTwitter API docs: https://github.com/FixTweet/FxTwitter

   Also supports fetching linked URLs from tweet text for deeper content ingestion."
  (:require [dvergr.intake.core :as intake]
            [dvergr.tools :as tools]
            [clojure.string :as str]))

(defn extract-tweet-id
  "Parse a tweet ID from a twitter.com/x.com URL or return the string if it's a bare ID."
  [url-or-id]
  (or (some-> (re-find #"(?:twitter\.com|x\.com)/[^/]+/status/(\d+)" url-or-id) second)
      (when (re-matches #"\d{10,}" url-or-id) url-or-id)))

(defn- fxtwitter-url
  "Build FXTwitter API URL. The username path component is required but can be
   any value — FXTwitter looks up by tweet ID only. We use the real username
   if available in the source URL, otherwise fall back to 'i'."
  [tweet-id source-url]
  (let [username (or (some-> (re-find #"(?:twitter\.com|x\.com)/([^/]+)/status/" source-url) second)
                     "i")]
    (str "https://api.fxtwitter.com/" username "/status/" tweet-id)))

(defn lookup-tweet
  "Fetch tweet data via FXTwitter API.
   Returns {:tweet-id :author :handle :text :created :url :links} or {:error}."
  [url-or-id]
  (let [tweet-id (extract-tweet-id url-or-id)]
    (if-not tweet-id
      {:error (str "Could not extract tweet ID from: " url-or-id)}
      (let [api-url (fxtwitter-url tweet-id url-or-id)
            result  (intake/fetch-json api-url)]
        (if (:error result)
          {:error (str "FXTwitter API error for " tweet-id ": " (:error result))}
          (if-not (= 200 (:code result))
            {:error (str "FXTwitter: " (:message result))}
            (let [tweet  (:tweet result)
                  author (get-in tweet [:author :name])
                  handle (get-in tweet [:author :screen_name])
                  text   (:text tweet)
                  ;; Extract non-t.co links — FXTwitter puts expanded links in :links
                  links  (->> (concat (:links tweet)
                                      (get-in tweet [:entities :urls]))
                              (keep #(or (:url %) (:expanded_url %)))
                              (remove #(or (str/includes? % "pic.twitter.com")
                                           (str/includes? % "t.co")))
                              distinct
                              vec)]
              {:tweet-id tweet-id
               :author   author
               :handle   handle
               :text     text
               :created  (:created_at tweet)
               :url      (or (:url tweet) (str "https://x.com/" handle "/status/" tweet-id))
               :links    links})))))))

(tools/register!
  {:name        "tweet_lookup"
   :description "Fetch a tweet by URL or ID using the FXTwitter API (no auth needed). Returns tweet text, author, and any embedded links. For tech Twitter monitoring and link harvesting."
   :parameters  {:type       "object"
                 :properties {:url {:type        "string"
                                    :description "Tweet URL (twitter.com/... or x.com/...) or numeric tweet ID"}}
                 :required   ["url"]}
   :execute     (fn [{:keys [url]} _ctx]
                  (let [result (lookup-tweet url)]
                    (if (:error result)
                      (intake/error-response (:error result))
                      (intake/success-response
                       (str "@" (:handle result) " (" (:author result) "):\n\n"
                            (:text result)
                            (when (seq (:links result))
                              (str "\n\nLinks:\n"
                                   (str/join "\n" (map #(str "- " %) (:links result))))))
                       :twitter 1
                       [{:title   (str "@" (:handle result) ": "
                                       (subs (:text result) 0 (min 100 (count (:text result)))))
                         :url     (:url result)
                         :summary (:text result)}]))))})
