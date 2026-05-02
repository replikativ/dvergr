(ns dvergr.intake.web-fetch
  "Generic URL fetcher — strips HTML to readable text.
   Suitable for articles, blog posts, docs, any public web page.
   YouTube and Twitter have dedicated tools (youtube_transcript, tweet_lookup)."
  (:require [dvergr.intake.core :as intake]
            [dvergr.tools :as tools]
            [dvergr.search :as search]
            [hato.client :as http]
            [clojure.string :as str]))

(def ^:private browser-ua
  "Mozilla/5.0 (X11; Linux x86_64; rv:124.0) Gecko/20100101 Firefox/124.0 (dvergr intake)")

(defn- strip-html
  "Remove HTML markup and decode basic entities, returning readable plain text."
  [html]
  (-> html
      ;; Remove script/style blocks entirely
      (str/replace #"(?i)<(script|style)[^>]*>[\s\S]*?</\1>" " ")
      ;; Remove all tags
      (str/replace #"<[^>]+>" " ")
      ;; HTML entities
      (str/replace #"&amp;" "&")
      (str/replace #"&lt;" "<")
      (str/replace #"&gt;" ">")
      (str/replace #"&quot;" "\"")
      (str/replace #"&#39;" "'")
      (str/replace #"&nbsp;" " ")
      ;; Collapse whitespace
      (str/replace #"\s+" " ")
      str/trim))

(defn fetch-page
  "Fetch a URL and return its text content.
   Returns {:url :text :title} or {:url :error}."
  [url & {:keys [max-chars] :or {max-chars 8000}}]
  (try
    (let [resp (http/get url
                         {:headers {"User-Agent"      browser-ua
                                    "Accept"          "text/html,application/xhtml+xml,text/plain"
                                    "Accept-Language" "en-US,en;q=0.9"}
                          :throw-exceptions? false
                          :as :string
                          :connect-timeout 15000})]
      (if (<= 200 (:status resp) 299)
        (let [ct   (get-in resp [:headers "content-type"] "")
              body (:body resp)
              text (if (str/includes? ct "html") (strip-html body) (str/trim body))
              text (if (> (count text) max-chars)
                     (str (subs text 0 max-chars) "\n\n[content truncated]")
                     text)
              ;; Try to extract title
              title (some-> (re-find #"(?i)<title[^>]*>([^<]+)</title>" body) second str/trim)]
          {:url url :text text :title title})
        {:url url :error (str "HTTP " (:status resp))}))
    (catch Exception e
      {:url url :error (.getMessage e)})))

(tools/register!
  {:name        "web_fetch"
   :description "Fetch a web page and return its readable text content. Use for articles, blog posts, documentation, GitHub pages, or any URL. For YouTube videos use youtube_transcript; for tweets use tweet_lookup."
   :parameters  {:type       "object"
                 :properties {:url       {:type        "string"
                                          :description "URL to fetch"}
                              :max_chars {:type        "integer"
                                          :description "Max characters to return (default 8000)"}}
                 :required   ["url"]}
   :execute     (fn [{:keys [url max_chars]} _ctx]
                  (let [result (fetch-page url :max-chars (or max_chars 8000))]
                    (if (:error result)
                      (intake/error-response (str "Failed to fetch " url ": " (:error result)))
                      (do
                        ;; Index in fulltext search
                        (when (search/initialized?)
                          (search/index-document!
                            {:id        (str "web/" (hash url))
                             :source    "web"
                             :title     (or (:title result) url)
                             :content   (:text result)
                             :url       url
                             :domain    (search/extract-domain url)
                             :timestamp (System/currentTimeMillis)}))
                        (let [header (str (when (:title result)
                                           (str "**" (:title result) "**\n"))
                                          url)]
                          (intake/success-response
                            (str "## " header "\n\n" (:text result))
                            :web 1
                            [{:title (or (:title result) url)
                              :url   url
                              :summary (subs (:text result) 0 (min 200 (count (:text result))))}]))))))})
