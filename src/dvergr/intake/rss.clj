(ns dvergr.intake.rss
  "RSS/Atom feed intake — autodiscover and parse feeds from any website.
   No API key needed. Supports RSS 2.0, Atom, and autodiscovery via HTML <link>."
  (:require [dvergr.intake.core :as intake]
            [dvergr.tools :as tools]
            [dvergr.search :as search]
            [clojure.string :as str]
            [hato.client :as http])
  (:import [java.io StringReader]
           [javax.xml.parsers SAXParserFactory]
           [org.xml.sax InputSource]
           [org.xml.sax.helpers DefaultHandler]))

(def ^:private browser-ua
  "Mozilla/5.0 (X11; Linux x86_64; rv:124.0) Gecko/20100101 Firefox/124.0 (dvergr intake)")

(defn- fetch-raw
  "GET a URL, return body string or {:error}."
  [url]
  (try
    (let [resp (http/get url
                         {:headers {"User-Agent" browser-ua
                                    "Accept" "application/rss+xml, application/atom+xml, application/xml, text/xml, text/html"}
                          :throw-exceptions? false
                          :as :string
                          :connect-timeout 15000})]
      (if (<= 200 (:status resp) 299)
        (:body resp)
        {:error (str "HTTP " (:status resp))}))
    (catch Exception e
      {:error (.getMessage e)})))

(defn discover-feeds
  "Discover RSS/Atom feeds from a URL by checking:
   1. <link rel='alternate' type='application/rss+xml'> in HTML head
   2. Common feed URL patterns (/feed, /rss, /atom.xml, etc.)
   Returns [{:url :title :type}]."
  [url]
  (let [body (fetch-raw url)]
    (if (:error body)
      body
      (let [;; Parse <link> tags from HTML head
            link-pattern #"<link[^>]*rel=[\"']alternate[\"'][^>]*>"
            type-pattern #"type=[\"']([^\"']+)[\"']"
            href-pattern #"href=[\"']([^\"']+)[\"']"
            title-pattern #"title=[\"']([^\"']+)[\"']"
            links (re-seq link-pattern body)
            discovered (->> links
                            (keep (fn [link-tag]
                                    (let [type-match (re-find type-pattern link-tag)
                                          href-match (re-find href-pattern link-tag)
                                          title-match (re-find title-pattern link-tag)]
                                      (when (and href-match
                                                 type-match
                                                 (or (str/includes? (second type-match) "rss")
                                                     (str/includes? (second type-match) "atom")
                                                     (str/includes? (second type-match) "xml")))
                                        (let [feed-url (second href-match)]
                                          {:url   (if (str/starts-with? feed-url "http")
                                                    feed-url
                                                    (let [base (re-find #"https?://[^/]+" url)]
                                                      (str base (when-not (str/starts-with? feed-url "/") "/") feed-url)))
                                           :title (when title-match (second title-match))
                                           :type  (second type-match)})))))
                            vec)
            ;; Also try common patterns if nothing found
            base-url (re-find #"https?://[^/]+" url)
            common-paths ["/feed" "/rss" "/atom.xml" "/feed.xml" "/rss.xml"
                          "/index.xml" "/blog/feed" "/blog/rss" "/feeds/posts/default"]
            probed (when (empty? discovered)
                     (->> common-paths
                          (keep (fn [path]
                                  (let [feed-url (str base-url path)]
                                    (try
                                      (let [resp (http/head feed-url
                                                            {:headers {"User-Agent" browser-ua}
                                                             :throw-exceptions? false
                                                             :connect-timeout 5000
                                                             :redirect-strategy :none})]
                                        (when (<= 200 (:status resp) 399)
                                          {:url feed-url
                                           :title path
                                           :type "probe"}))
                                      (catch Exception _ nil)))))
                          vec))]
        (if (seq discovered)
          discovered
          (or (seq probed) []))))))

(defn- parse-xml-feed
  "Parse an RSS/Atom XML feed into items.
   Uses SAX parsing (no external XML lib dependency beyond JDK)."
  [xml-string]
  (let [items (atom [])
        current-item (atom nil)
        current-tag (atom nil)
        current-text (StringBuilder.)
        in-item? (atom false)
        feed-title (atom nil)
        handler (proxy [DefaultHandler] []
                  (startElement [_uri _local-name qname _attrs]
                    (let [tag (str/lower-case qname)]
                      (reset! current-tag tag)
                      (.setLength current-text 0)
                      (when (contains? #{"item" "entry"} tag)
                        (reset! in-item? true)
                        (reset! current-item {}))
                      ;; Handle <link> with href attr (Atom)
                      (when (and @in-item? (= tag "link"))
                        (let [attrs-map (into {}
                                              (for [i (range (.getLength _attrs))]
                                                [(.getLocalName _attrs i) (.getValue _attrs i)]))]
                          (when-let [href (get attrs-map "href")]
                            (swap! current-item assoc :url href))))))
                  (endElement [_uri _local-name qname]
                    (let [tag (str/lower-case qname)
                          text (str/trim (str current-text))]
                      (when @in-item?
                        (case tag
                          "title"       (swap! current-item assoc :title text)
                          "link"        (when (and (seq text) (not (:url @current-item)))
                                         (swap! current-item assoc :url text))
                          "description" (swap! current-item assoc :summary text)
                          "summary"     (when-not (:summary @current-item)
                                         (swap! current-item assoc :summary text))
                          "content"     (when-not (:summary @current-item)
                                         (swap! current-item assoc :summary text))
                          "pubdate"     (swap! current-item assoc :date text)
                          "published"   (swap! current-item assoc :date text)
                          "updated"     (when-not (:date @current-item)
                                         (swap! current-item assoc :date text))
                          "dc:date"     (swap! current-item assoc :date text)
                          "author"      (swap! current-item assoc :author text)
                          "dc:creator"  (swap! current-item assoc :author text)
                          "category"    (swap! current-item update :tags (fnil conj []) text)
                          nil))
                      (when (and (not @in-item?) (= tag "title") (nil? @feed-title))
                        (reset! feed-title text))
                      (when (contains? #{"item" "entry"} tag)
                        (reset! in-item? false)
                        (swap! items conj @current-item))
                      (reset! current-tag nil)))
                  (characters [ch start length]
                    (.append current-text ch start length)))]
    (try
      (let [factory (SAXParserFactory/newInstance)]
        ;; Disable DTD loading to avoid network calls and XXE
        (.setFeature factory "http://apache.org/xml/features/nonvalidating/load-external-dtd" false)
        (.setFeature factory "http://xml.org/sax/features/external-general-entities" false)
        (.setFeature factory "http://xml.org/sax/features/external-parameter-entities" false)
        (let [parser (.newSAXParser factory)]
          (.parse parser (InputSource. (StringReader. xml-string)) handler)))
      {:feed-title @feed-title
       :items (mapv (fn [item]
                      (update item :summary
                              (fn [s]
                                (when s
                                  ;; Strip HTML from summaries
                                  (-> s
                                      (str/replace #"<[^>]+>" " ")
                                      (str/replace #"\s+" " ")
                                      str/trim
                                      (as-> t (if (> (count t) 300) (str (subs t 0 300) "...") t)))))))
                    @items)}
      (catch Exception e
        {:error (str "XML parse error: " (.getMessage e))}))))

(defn fetch-feed
  "Fetch and parse an RSS/Atom feed.
   Returns {:feed-title :items [{:title :url :summary :date :author :tags}]}."
  [feed-url & {:keys [count] :or {count 20}}]
  (let [body (fetch-raw feed-url)]
    (if (:error body)
      body
      (let [parsed (parse-xml-feed body)]
        (if (:error parsed)
          parsed
          (update parsed :items #(vec (take count %))))))))

;; Tool registrations

(tools/register!
 {:name "rss_discover"
  :description "Discover RSS/Atom feeds from a website URL. Checks HTML <link> tags and common feed URL patterns. Use this to find feeds to monitor."
  :parameters {:type "object"
               :properties {:url {:type "string"
                                  :description "Website URL to check for feeds"}}
               :required ["url"]}
  :execute (fn [{:keys [url]} _ctx]
             (let [feeds (discover-feeds url)]
               (if (:error feeds)
                 (intake/error-response (:error feeds))
                 (if (empty? feeds)
                   (intake/success-response (str "No RSS/Atom feeds found at " url) :rss 0 [])
                   (intake/success-response
                    (intake/format-items (str "Feeds at " url)
                                        (map (fn [f] {:title (or (:title f) (:url f))
                                                      :url (:url f)
                                                      :summary (str "Type: " (:type f))})
                                             feeds))
                    :rss (clojure.core/count feeds) feeds)))))})

(tools/register!
 {:name "rss_read"
  :description "Fetch and parse an RSS/Atom feed URL. Returns feed items with titles, links, summaries, and dates. Use rss_discover to find feed URLs first."
  :parameters {:type "object"
               :properties {:feed_url {:type "string"
                                       :description "RSS/Atom feed URL"}
                            :count {:type "integer"
                                    :description "Number of items to return (default 20)"}}
               :required ["feed_url"]}
  :execute (fn [{:keys [feed_url count]} _ctx]
             (let [result (fetch-feed feed_url :count (or count 20))]
               (if (:error result)
                 (intake/error-response (:error result))
                 (do
                   ;; Index each RSS item in fulltext search
                   (when (search/initialized?)
                     (doseq [item (:items result)]
                       (when (:url item)
                         (search/index-document!
                           {:id        (str "rss/" (hash (:url item)))
                            :source    "rss"
                            :title     (or (:title item) "")
                            :content   (or (:summary item) "")
                            :url       (:url item)
                            :domain    (search/extract-domain (or (:url item) feed_url))
                            :timestamp (System/currentTimeMillis)
                            :metadata  {:feed-url   feed_url
                                        :feed-title (:feed-title result)
                                        :date       (:date item)
                                        :author     (:author item)}}))))
                   (intake/success-response
                    (intake/format-items (str "Feed: " (or (:feed-title result) feed_url))
                                        (map (fn [item]
                                               {:title (:title item)
                                                :url (:url item)
                                                :summary (str (when (:date item) (str (:date item) " — "))
                                                              (or (:summary item) ""))})
                                             (:items result)))
                    :rss (clojure.core/count (:items result)) (:items result))))))}
)
