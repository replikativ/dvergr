(ns dvergr.channels.rss
  "RSS feed channel for dvergr.

   Fetches RSS/Atom feeds via HTTP, parses XML, deduplicates entries by link/guid,
   and stores them in datahike. Pairs with the scheduler for periodic polling.

   Usage:
     (require '[dvergr.channels.core :as ch])
     (require '[dvergr.channels.rss :as rss])

     (def feeds (ch/connect!
                  (rss/make-rss {:feeds [{:url \"https://news.ycombinator.com/rss\"
                                          :name \"HN\"}]})
                  :on-message (fn [entry] (println \"New:\" (:title entry)))))"
  (:require [dvergr.channels.core :as channels]
            [hato.client :as http]
            [clojure.string :as str]
            [clojure.xml :as xml]
            [clojure.java.io :as io]
            [datahike.api :as d])
  (:import [java.io ByteArrayInputStream]))

;; ============================================================================
;; XML Parsing
;; ============================================================================

(defn- parse-xml [xml-string]
  (xml/parse (ByteArrayInputStream. (.getBytes xml-string "UTF-8"))))

(defn- find-tags
  "Find all child elements with the given tag name."
  [element tag]
  (filter #(= tag (:tag %)) (:content element)))

(defn- text-content
  "Get text content of an element."
  [element]
  (when element
    (str/join (filter string? (:content element)))))

(defn- parse-rss-item [item]
  (let [find-tag (fn [tag] (first (find-tags item tag)))]
    {:title       (text-content (find-tag :title))
     :link        (or (text-content (find-tag :link))
                      (get-in (find-tag :link) [:attrs :href]))
     :description (text-content (find-tag :description))
     :guid        (or (text-content (find-tag :guid))
                      (text-content (find-tag :id)))
     :pub-date    (or (text-content (find-tag :pubDate))
                      (text-content (find-tag :published))
                      (text-content (find-tag :updated)))}))

(defn- parse-feed
  "Parse RSS or Atom feed XML into a seq of entry maps."
  [xml-string]
  (let [root (parse-xml xml-string)]
    (cond
      ;; RSS 2.0: <rss><channel><item>
      (= :rss (:tag root))
      (let [channel (first (find-tags root :channel))]
        (mapv parse-rss-item (find-tags channel :item)))

      ;; Atom: <feed><entry>
      (= :feed (:tag root))
      (mapv parse-rss-item (find-tags root :entry))

      ;; RDF/RSS 1.0: <rdf:RDF><item>
      :else
      (mapv parse-rss-item (find-tags root :item)))))

;; ============================================================================
;; Feed Fetching
;; ============================================================================

(defn fetch-feed!
  "Fetch and parse an RSS/Atom feed URL. Returns vector of entry maps."
  [url]
  (let [resp (http/get url {:as :string
                             :throw-exceptions false
                             :timeout 30000
                             :headers {"User-Agent" "dvergr/1.0 RSS reader"}})]
    (if (= 200 (:status resp))
      (parse-feed (:body resp))
      (throw (ex-info (str "Feed fetch failed: HTTP " (:status resp))
                      {:url url :status (:status resp)})))))

;; ============================================================================
;; Datahike Schema
;; ============================================================================

(def schema
  [{:db/ident       :rss-feed/url
    :db/valueType   :db.type/string
    :db/unique      :db.unique/identity
    :db/cardinality :db.cardinality/one}
   {:db/ident       :rss-feed/name
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident       :rss-feed/active?
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one}
   {:db/ident       :rss-entry/guid
    :db/valueType   :db.type/string
    :db/unique      :db.unique/identity
    :db/cardinality :db.cardinality/one}
   {:db/ident       :rss-entry/title
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident       :rss-entry/link
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident       :rss-entry/description
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident       :rss-entry/feed-url
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident       :rss-entry/fetched-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}])

(defn install-schema!
  "Install RSS schema into datahike. Idempotent."
  [db-conn]
  (try
    (d/transact db-conn schema)
    (catch Exception _ nil)))

;; ============================================================================
;; Deduplication
;; ============================================================================

(defn- entry-seen?
  "Check if an entry has already been stored (by guid or link)."
  [db guid link]
  (or (when guid
        (seq (d/q '[:find ?e :in $ ?g :where [?e :rss-entry/guid ?g]] db guid)))
      (when link
        (seq (d/q '[:find ?e :in $ ?l :where [?e :rss-entry/link ?l]] db link)))))

(defn- store-entry!
  "Store a new RSS entry in datahike."
  [db-conn entry feed-url]
  (let [guid (or (:guid entry) (:link entry))]
    (when guid
      (d/transact db-conn
        [(cond-> {:rss-entry/guid      guid
                  :rss-entry/feed-url  feed-url
                  :rss-entry/fetched-at (java.util.Date.)}
           (:title entry)       (assoc :rss-entry/title (:title entry))
           (:link entry)        (assoc :rss-entry/link (:link entry))
           (:description entry) (assoc :rss-entry/description
                                       (subs (:description entry)
                                             0 (min 2000 (count (:description entry))))))]))))

;; ============================================================================
;; Feed polling with dedup
;; ============================================================================

(defn fetch-new-entries!
  "Fetch a feed and return only new (unseen) entries. Stores them in datahike."
  [db-conn feed-url]
  (let [entries (fetch-feed! feed-url)
        db @db-conn
        new-entries (filterv #(not (entry-seen? db (:guid %) (:link %))) entries)]
    (doseq [entry new-entries]
      (store-entry! db-conn entry feed-url))
    new-entries))

;; ============================================================================
;; Tool handlers
;; ============================================================================

(defn- handle-add-feed [input _ctx channel]
  (let [url (:url input)
        name (:name input)
        db-conn (:db-conn @(:state channel))]
    (when-not url (throw (ex-info "url is required" {})))
    ;; Verify feed is reachable
    (let [entries (fetch-feed! url)]
      (when db-conn
        (d/transact db-conn
          [{:rss-feed/url url
            :rss-feed/name (or name url)
            :rss-feed/active? true}]))
      {:type :success
       :content (str "Feed added: " (or name url) "\n"
                     "URL: " url "\n"
                     "Current entries: " (count entries))
       :metadata {:url url :entry-count (count entries)}})))

(defn- handle-list-feeds [_input _ctx channel]
  (let [db-conn (:db-conn @(:state channel))]
    (if db-conn
      (let [feeds (d/q '[:find [(pull ?f [*]) ...]
                          :where [?f :rss-feed/url _]]
                       @db-conn)]
        {:type :success
         :content (if (seq feeds)
                    (str "RSS feeds (" (count feeds) "):\n\n"
                         (str/join "\n"
                           (map (fn [f]
                                  (str "- " (:rss-feed/name f) "\n"
                                       "  URL: " (:rss-feed/url f)
                                       (when-not (:rss-feed/active? f) " [inactive]")))
                                feeds)))
                    "No feeds configured.")
         :metadata {:count (count feeds)}})
      {:type :success
       :content "No database connection — feeds are in-memory only."
       :metadata {:count 0}})))

(defn- handle-fetch-new [input _ctx channel]
  (let [url (:url input)
        db-conn (:db-conn @(:state channel))]
    (when-not url (throw (ex-info "url is required" {})))
    (let [new-entries (if db-conn
                        (fetch-new-entries! db-conn url)
                        (fetch-feed! url))
          on-msg (:on-message @(:state channel))]
      ;; Notify on-message callback for each new entry
      (when on-msg
        (doseq [entry new-entries]
          (on-msg (assoc entry :channel :rss :feed-url url))))
      {:type :success
       :content (if (seq new-entries)
                  (str "New entries (" (count new-entries) ") from " url ":\n\n"
                       (str/join "\n\n"
                         (map-indexed
                           (fn [i e]
                             (str (inc i) ". " (:title e) "\n"
                                  "   " (:link e)
                                  (when (:pub-date e)
                                    (str "\n   " (:pub-date e)))))
                           (take 20 new-entries))))
                  (str "No new entries from " url))
       :metadata {:new-count (count new-entries)
                  :entries (take 20 new-entries)}})))

;; ============================================================================
;; Tool definitions
;; ============================================================================

(def ^:private rss-capabilities
  #{:rss/add-feed :rss/list-feeds :rss/fetch-new})

(def ^:private default-permissions
  #{:rss/add-feed :rss/list-feeds :rss/fetch-new})

(defn- make-tool-defs []
  [{:capability :rss/add-feed
    :name "rss_add_feed"
    :description "Add an RSS/Atom feed to monitor. Verifies the feed is reachable."
    :parameters {:type "object"
                 :properties {:url {:type "string"
                                    :description "Feed URL (RSS or Atom XML)"}
                              :name {:type "string"
                                     :description "Human-readable name for the feed"}}
                 :required ["url"]}}

   {:capability :rss/list-feeds
    :name "rss_list_feeds"
    :description "List all configured RSS feeds."
    :parameters {:type "object"
                 :properties {}}}

   {:capability :rss/fetch-new
    :name "rss_fetch_new"
    :description "Fetch new (unseen) entries from an RSS feed. Automatically deduplicates."
    :parameters {:type "object"
                 :properties {:url {:type "string"
                                    :description "Feed URL to fetch"}}
                 :required ["url"]}}])

;; ============================================================================
;; Channel constructor
;; ============================================================================

(defn make-rss
  "Create an RSS channel.

   Config:
     :feeds       - Initial feeds [{:url \"...\" :name \"...\"}] (optional)
     :permissions - Override default permissions (optional)
     :db-conn     - Datahike connection for persistence (optional)

   Returns a channel map ready for (channels/connect!)."
  [{:keys [feeds permissions db-conn] :as config}]
  (let [channel-id :rss
        perms (or permissions default-permissions)
        state (atom {:connected? false
                     :db-conn db-conn
                     :on-message nil})]
    (channels/make-channel
     {:id           channel-id
      :type         :rss
      :config       config
      :capabilities rss-capabilities
      :permissions  perms
      :tools        (make-tool-defs)
      :handlers     {"rss_add_feed"  (fn [input ctx] (handle-add-feed input ctx
                                                       (channels/get-channel channel-id)))
                     "rss_list_feeds" (fn [input ctx] (handle-list-feeds input ctx
                                                        (channels/get-channel channel-id)))
                     "rss_fetch_new"  (fn [input ctx] (handle-fetch-new input ctx
                                                        (channels/get-channel channel-id)))}
      :connect!     (fn [channel]
                      ;; Install schema if db-conn available
                      (when db-conn
                        (install-schema! db-conn))
                      ;; Add initial feeds
                      (when (and db-conn (seq feeds))
                        (doseq [{:keys [url name]} feeds]
                          (try
                            (d/transact db-conn
                              [{:rss-feed/url url
                                :rss-feed/name (or name url)
                                :rss-feed/active? true}])
                            (catch Exception _))))
                      (swap! state assoc :connected? true)
                      channel)
      :disconnect!  (fn [_channel]
                      (swap! state assoc :connected? false))
      :state        state})))
