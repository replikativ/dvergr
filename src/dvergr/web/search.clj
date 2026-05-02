(ns dvergr.web.search
  "Web search via Brave API with knowledge graph integration.

   Features:
   - Brave Search API integration
   - Results stored in datahike knowledge graph
   - Content-addressable file storage for fetched pages
   - Optional AI summarization of fetched content
   - Freshness tracking for all indexed content

   Configuration:
   - BRAVE_API_KEY environment variable required
   - Optional config for defaults (count, etc.)

   Usage:
     (search! conn \"clojure web frameworks\" :count 10)
     (fetch! conn \"https://example.com\" :summarize? true)"
  (:require [hato.client :as http]
            [jsonista.core :as json]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [datahike.api :as d]
            [dvergr.model.chat :as model-chat])
  (:import [java.security MessageDigest]
           [java.time Instant]))

;; ============================================================================
;; Configuration
;; ============================================================================

(def ^:private brave-api-url
  "https://api.search.brave.com/res/v1/web/search")

(defn- get-brave-api-key []
  (let [api-key (System/getenv "BRAVE_API_KEY")
        token (System/getenv "BRAVE_TOKEN")]
    (cond
      (not (str/blank? api-key)) api-key
      (not (str/blank? token)) token
      :else (throw (ex-info "BRAVE_API_KEY or BRAVE_TOKEN environment variable not set"
                            {:error :missing-api-key})))))

(def ^:private default-config
  {:default-count 5
   :max-count 50
   :timeout-ms 30000
   :web-pages-dir "data/web-pages"})

;; ============================================================================
;; Content Hashing
;; ============================================================================

(defn- sha256 [s]
  (let [digest (MessageDigest/getInstance "SHA-256")
        hash-bytes (.digest digest (.getBytes (str s) "UTF-8"))]
    (apply str (map #(format "%02x" %) hash-bytes))))

(defn- content-hash
  "Generate SHA256 hash of content for content-addressable storage."
  [content]
  (sha256 content))

(defn- url-hash
  "Generate short hash of URL for identification."
  [url]
  (subs (sha256 url) 0 16))

;; ============================================================================
;; File Storage
;; ============================================================================

(defn- ensure-web-pages-dir! []
  (let [dir (io/file (:web-pages-dir default-config))]
    (when-not (.exists dir)
      (.mkdirs dir))
    dir))

(defn- store-page-content!
  "Store page content to file, return file path.
   Uses content hash for deduplication."
  [content url]
  (let [hash (content-hash content)
        ext (cond
              (str/includes? content "<!DOCTYPE html") ".html"
              (str/includes? content "<html") ".html"
              :else ".txt")
        filename (str hash ext)
        dir (ensure-web-pages-dir!)
        file (io/file dir filename)]
    (when-not (.exists file)
      (spit file content))
    {:path (str file)
     :hash hash
     :size (count content)
     :exists-before? (.exists file)}))

;; ============================================================================
;; Brave Search API
;; ============================================================================

(defn- parse-search-results
  "Parse Brave API response into structured results."
  [response]
  (let [body (json/read-value (:body response) json/keyword-keys-object-mapper)
        web-results (get-in body [:web :results] [])]
    (mapv (fn [r]
            {:title (:title r)
             :url (:url r)
             :description (:description r)
             :age (:age r)
             :site-name (some-> (:url r)
                                (java.net.URI.)
                                .getHost)})
          web-results)))

(defn search-brave
  "Search via Brave API. Returns vector of results.

   Options:
   - :count - Number of results (1-50, default 5)
   - :freshness - Filter: pd (24h), pw (week), pm (month), py (year)
   - :country - 2-letter country code (default US)"
  [query & {:keys [count freshness country]
            :or {count 5
                 country "US"}}]
  (let [api-key (get-brave-api-key)
        params (cond-> {:q query
                        :count (min count (:max-count default-config))}
                 freshness (assoc :freshness freshness)
                 country (assoc :country country))
        response (http/get brave-api-url
                           {:query-params params
                            :headers {"Accept" "application/json"
                                      "X-Subscription-Token" api-key}
                            :connect-timeout (:timeout-ms default-config)
                            :throw-exceptions? false})]
    (if (= 200 (:status response))
      {:success true
       :results (parse-search-results response)
       :query query}
      {:success false
       :error (str "Brave API error: " (:status response))
       :body (:body response)})))

;; ============================================================================
;; Web Fetch
;; ============================================================================

(def ^:private user-agent
  "Mozilla/5.0 (compatible; Dvergr/1.0; +https://github.com/whilo/dvergr)")

(defn fetch-url
  "Fetch content from URL. Returns map with :content, :status, :content-type."
  [url & {:keys [timeout-ms]
          :or {timeout-ms 30000}}]
  (try
    (let [response (http/get url
                             {:headers {"User-Agent" user-agent
                                        "Accept" "text/html,application/xhtml+xml,text/plain"}
                              :connect-timeout timeout-ms
                              :throw-exceptions? false
                              :as :string})]
      (if (= 200 (:status response))
        {:success true
         :content (:body response)
         :content-type (get-in response [:headers :content-type])
         :url url}
        {:success false
         :error (str "HTTP " (:status response))
         :url url}))
    (catch Exception e
      {:success false
       :error (.getMessage e)
       :url url})))

;; ============================================================================
;; HTML to Text Conversion
;; ============================================================================

(defn- strip-html-tags
  "Simple HTML tag stripping. For better results, use a proper HTML parser."
  [html]
  (-> html
      ;; Remove script and style blocks
      (str/replace #"(?is)<script[^>]*>.*?</script>" "")
      (str/replace #"(?is)<style[^>]*>.*?</style>" "")
      ;; Remove HTML comments
      (str/replace #"<!--.*?-->" "")
      ;; Replace block elements with newlines
      (str/replace #"(?i)</(p|div|h[1-6]|li|tr)>" "\n")
      (str/replace #"(?i)<br\s*/?>" "\n")
      ;; Remove remaining tags
      (str/replace #"<[^>]+>" "")
      ;; Decode common entities
      (str/replace "&nbsp;" " ")
      (str/replace "&amp;" "&")
      (str/replace "&lt;" "<")
      (str/replace "&gt;" ">")
      (str/replace "&quot;" "\"")
      ;; Clean up whitespace
      (str/replace #"[ \t]+" " ")
      (str/replace #"\n{3,}" "\n\n")
      str/trim))

;; ============================================================================
;; Summarization
;; ============================================================================

(def ^:private summarization-prompt
  "Summarize this web page content concisely. Focus on:
1. Main topic and key points
2. Important facts, data, or conclusions
3. Relevant technical details

Keep the summary under 500 words. Use [[Entity Name]] wiki-links for key concepts.

Content to summarize:
%s

Summary:")

(defn summarize-content
  "Summarize content using LLM."
  [content & {:keys [prompt model provider max-content-length]
              :or {model "accounts/fireworks/models/minimax-m2p5"
                   provider :fireworks
                   max-content-length 100000}}]
  (let [truncated (if (> (count content) max-content-length)
                    (str (subs content 0 max-content-length) "\n\n[Content truncated...]")
                    content)
        full-prompt (if prompt
                      (str prompt "\n\nContent:\n" truncated)
                      (format summarization-prompt truncated))
        response (model-chat/chat
                   [{:role "user" :content full-prompt}]
                   {:model model
                    :provider provider
                    :tools []})]
    (:content response)))

;; ============================================================================
;; Knowledge Graph Schema
;; ============================================================================

(def web-schema
  "Schema for web search results and fetched pages."
  [;; Search results
   {:db/ident :web-result/id
    :db/valueType :db.type/uuid
    :db/unique :db.unique/identity
    :db/cardinality :db.cardinality/one}

   {:db/ident :web-result/query
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Search query that returned this result"}

   {:db/ident :web-result/url
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident :web-result/title
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident :web-result/description
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident :web-result/site-name
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident :web-result/indexed-at
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident :web-result/source
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc "Search source: :brave, :local"}

   ;; Fetched pages
   {:db/ident :web-page/id
    :db/valueType :db.type/uuid
    :db/unique :db.unique/identity
    :db/cardinality :db.cardinality/one}

   {:db/ident :web-page/url
    :db/valueType :db.type/string
    :db/unique :db.unique/value
    :db/cardinality :db.cardinality/one}

   {:db/ident :web-page/title
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident :web-page/file-path
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Path to stored content file"}

   {:db/ident :web-page/content-hash
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "SHA256 of content for change detection"}

   {:db/ident :web-page/summary
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "AI-generated summary"}

   {:db/ident :web-page/fetched-at
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident :web-page/content-type
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident :web-page/size-bytes
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one}])

(defn install-web-schema!
  "Install web schema into datahike connection."
  [conn]
  (d/transact conn web-schema))

;; ============================================================================
;; Knowledge Graph Storage
;; ============================================================================

(defn store-search-results!
  "Store search results in knowledge graph."
  [conn query results]
  (let [now (java.util.Date.)
        entities (mapv (fn [r]
                         {:web-result/id (random-uuid)
                          :web-result/query query
                          :web-result/url (:url r)
                          :web-result/title (:title r)
                          :web-result/description (:description r)
                          :web-result/site-name (:site-name r)
                          :web-result/indexed-at now
                          :web-result/source :brave})
                       results)]
    (d/transact conn entities)
    {:stored (count entities)}))

(defn store-fetched-page!
  "Store fetched page in knowledge graph and file system."
  [conn url content & {:keys [title summary content-type]}]
  (let [now (java.util.Date.)
        file-info (store-page-content! content url)
        ;; Check if page already exists
        existing (d/q '[:find ?e .
                        :in $ ?url
                        :where [?e :web-page/url ?url]]
                      @conn url)
        entity (cond-> {:web-page/id (or existing (random-uuid))
                        :web-page/url url
                        :web-page/file-path (:path file-info)
                        :web-page/content-hash (:hash file-info)
                        :web-page/fetched-at now
                        :web-page/size-bytes (long (:size file-info))}
                 title (assoc :web-page/title title)
                 summary (assoc :web-page/summary summary)
                 content-type (assoc :web-page/content-type content-type))]
    (d/transact conn [entity])
    (assoc file-info :entity entity)))

;; ============================================================================
;; Query Functions
;; ============================================================================

(defn find-cached-results
  "Find previously indexed search results for query."
  [conn query]
  (d/q '[:find [(pull ?e [*]) ...]
         :in $ ?q
         :where [?e :web-result/query ?q]]
       @conn query))

(defn find-page-by-url
  "Find previously fetched page by URL."
  [conn url]
  (d/q '[:find (pull ?e [*]) .
         :in $ ?url
         :where [?e :web-page/url ?url]]
       @conn url))

(defn list-recent-pages
  "List recently fetched pages."
  [conn & {:keys [limit] :or {limit 20}}]
  (->> (d/q '[:find [(pull ?e [*]) ...]
              :where [?e :web-page/id _]]
            @conn)
       (sort-by #(- (.getTime (:web-page/fetched-at %))))
       (take limit)))

;; ============================================================================
;; High-Level API
;; ============================================================================

(defn search!
  "Search and store results in knowledge graph.

   Args:
     conn - Datahike connection
     query - Search query string

   Options:
     :count - Number of results (1-50, default 5)
     :freshness - pd/pw/pm/py
     :store? - Store in knowledge graph (default true)

   Returns map with :results and :stored count."
  [conn query & {:keys [count freshness store?]
                 :or {count 5 store? true}}]
  (let [response (search-brave query :count count :freshness freshness)]
    (if (:success response)
      (let [results (:results response)
            n (clojure.core/count results)]
        (when (and store? conn (seq results))
          (store-search-results! conn query results))
        {:success true
         :query query
         :results results
         :count n
         :stored (when store? n)})
      response)))

(defn fetch!
  "Fetch URL, optionally summarize, and store in knowledge graph.

   Args:
     conn - Datahike connection
     url - URL to fetch

   Options:
     :summarize? - Generate AI summary (default false)
     :prompt - Custom summarization prompt
     :store? - Store in knowledge graph (default true)
     :extract-text? - Strip HTML tags (default true)

   Returns map with :content, :summary, :file-path."
  [conn url & {:keys [summarize? prompt store? extract-text?]
               :or {store? true extract-text? true}}]
  (let [response (fetch-url url)]
    (if (:success response)
      (let [raw-content (:content response)
            content (if extract-text?
                      (strip-html-tags raw-content)
                      raw-content)
            ;; Extract title from HTML
            title (when-let [match (re-find #"<title[^>]*>([^<]+)</title>" raw-content)]
                    (str/trim (second match)))
            ;; Generate summary if requested
            summary (when summarize?
                      (summarize-content content :prompt prompt))
            ;; Store if requested
            stored (when (and store? conn)
                     (store-fetched-page! conn url raw-content
                                          :title title
                                          :summary summary
                                          :content-type (:content-type response)))]
        {:success true
         :url url
         :title title
         :content content
         :summary summary
         :file-path (:path stored)
         :content-hash (:hash stored)})
      response)))

(comment
  ;; Example usage:

  ;; Search
  (def results (search-brave "clojure web frameworks" :count 5))

  ;; Fetch
  (def page (fetch-url "https://clojure.org"))

  ;; Summarize
  (def summary (summarize-content (:content page)))

  ;; With datahike
  (require '[datahike.api :as d])
  (def cfg {:store {:backend :memory :id (random-uuid)}})
  (d/create-database cfg)
  (def conn (d/connect cfg))
  (install-web-schema! conn)

  (search! conn "clojure async" :count 3)
  (fetch! conn "https://clojure.org" :summarize? true)

  (find-cached-results conn "clojure async")
  (list-recent-pages conn))
