(ns dvergr.intake.rss
  "RSS/Atom feed intake — autodiscover and parse feeds from any website.
   No API key needed. Supports RSS 2.0, Atom, and autodiscovery via HTML <link>.

   Ported to the sandbox stdlib: the JDK SAX parser is replaced by the `xml`
   primitive — `(xml/parse-str s)` returns a {:tag :attrs :content} tree and we
   tree-walk it (see intake/arxiv.clj for the same pattern). Feed autodiscovery
   stays regex-based over the fetched HTML."
  (:require [clojure.data.xml :as xml] [dvergr.intake.core :as intake]
            [clojure.string :as str]))

(def ^:private browser-ua
  "Mozilla/5.0 (X11; Linux x86_64; rv:124.0) Gecko/20100101 Firefox/124.0 (dvergr intake)")

(def ^:private feed-accept
  "application/rss+xml, application/atom+xml, application/xml, text/xml, text/html")

(defn- fetch-raw
  "GET a URL, return body string or {:error}."
  [url]
  (intake/fetch-text url :headers {"User-Agent" browser-ua "Accept" feed-accept}))

;; ── Feed autodiscovery (regex over HTML) ──────────────────────────────────────

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
            link-pattern  #"<link[^>]*rel=[\"']alternate[\"'][^>]*>"
            type-pattern  #"type=[\"']([^\"']+)[\"']"
            href-pattern  #"href=[\"']([^\"']+)[\"']"
            title-pattern #"title=[\"']([^\"']+)[\"']"
            links (re-seq link-pattern body)
            discovered (->> links
                            (keep (fn [link-tag]
                                    (let [type-match  (re-find type-pattern link-tag)
                                          href-match  (re-find href-pattern link-tag)
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
                                  (let [feed-url (str base-url path)
                                        ;; PORT-NOTE: no HEAD primitive in the sandbox; probe with
                                        ;; a GET via fetch-text — a non-error body means the feed exists.
                                        resp     (fetch-raw feed-url)]
                                    (when-not (:error resp)
                                      {:url   feed-url
                                       :title path
                                       :type  "probe"}))))
                          vec))]
        (if (seq discovered)
          discovered
          (or (seq probed) []))))))

;; ── Feed parsing (xml/parse-str tree-walk) ─────────────────────────────────────────

(defn- elems
  "Child element nodes of `node` whose (lower-cased) tag matches `tag`."
  [node tag]
  (filter #(and (map? %) (= tag (some-> (:tag %) name str/lower-case)))
          (:content node)))

(defn- elem [node tag] (first (elems node tag)))

(defn- etext
  "Flattened, trimmed text of `node`."
  [node]
  (some-> (xml/text node) str/trim))

(defn- find-first
  "Depth-first search for the first element node (anywhere in the tree) whose
   lower-cased tag is in the `tags` set."
  [node tags]
  (when (map? node)
    (if (contains? tags (some-> (:tag node) name str/lower-case))
      node
      (some #(find-first % tags) (filter map? (:content node))))))

(defn- find-all
  "Collect every element node in the tree whose lower-cased tag is in `tags`."
  [node tags]
  (when (map? node)
    (let [self (when (contains? tags (some-> (:tag node) name str/lower-case)) [node])
          kids (mapcat #(find-all % tags) (filter map? (:content node)))]
      (concat self kids))))

(defn- clean-summary
  "Strip HTML, collapse whitespace, trim to 300 chars."
  [s]
  (when s
    (-> s
        (str/replace #"<[^>]+>" " ")
        (str/replace #"\s+" " ")
        str/trim
        (as-> t (if (> (count t) 300) (str (subs t 0 300) "...") t)))))

(defn- parse-item
  "Parse a single <item> (RSS) or <entry> (Atom) node into an item map."
  [node]
  (let [title   (etext (elem node "title"))
        ;; <link> is text in RSS 2.0, but an href attribute in Atom.
        links   (elems node "link")
        atom-href (some (fn [l]
                          (let [rel  (get-in l [:attrs :rel])
                                href (get-in l [:attrs :href])]
                            (when (and href (or (nil? rel) (= "alternate" rel))) href)))
                        links)
        text-link (some etext links)
        url     (or atom-href (when (seq text-link) text-link))
        summary (or (etext (elem node "description"))
                    (etext (elem node "summary"))
                    (etext (elem node "content")))
        date    (or (etext (elem node "pubdate"))
                    (etext (elem node "published"))
                    (etext (elem node "updated"))
                    (etext (elem node "date")))
        author  (or (etext (elem node "author"))
                    (etext (elem node "creator")))
        tags    (->> (elems node "category")
                     (map (fn [c] (or (get-in c [:attrs :term]) (etext c))))
                     (remove nil?)
                     vec)]
    (cond-> {}
      title         (assoc :title title)
      url           (assoc :url url)
      summary       (assoc :summary (clean-summary summary))
      date          (assoc :date date)
      author        (assoc :author author)
      (seq tags)    (assoc :tags tags))))

(defn- parse-xml-feed
  "Parse an RSS/Atom XML feed into {:feed-title :items [...]}.
   Handles both RSS 2.0 (<rss><channel><item>) and Atom (<feed><entry>)."
  [xml-string]
  (let [root (try (xml/parse-str xml-string)
                  (catch Throwable e {:error (str "XML parse error: " (.getMessage e))}))]
    (if (and (map? root) (:error root))
      root
      (let [;; Feed-level title: the <title> on the channel/feed, not inside an item.
            channel    (or (find-first root #{"channel"}) root)
            feed-title (some-> (some (fn [c]
                                       (when (and (map? c)
                                                  (= "title" (some-> (:tag c) name str/lower-case)))
                                         c))
                                     (:content channel))
                               etext)
            item-nodes (find-all root #{"item" "entry"})
            items      (mapv parse-item item-nodes)]
        {:feed-title feed-title
         :items items}))))

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
