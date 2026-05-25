(ns dvergr.search
  "System-wide fulltext search powered by Scriptum (Lucene).

   Indexes all ingested data: browser captures, YouTube transcripts, RSS feeds,
   web fetches, knowledge entities, and agent conversations. Provides a unified
   search interface for agents (via SCI), the web UI, and tools.

   Lifecycle:
     (init! \"data/search-index\")     ;; Open/create index, start commit scheduler
     (index-document! doc-map)          ;; Upsert a document
     (search \"clojure\" :limit 20)     ;; Fulltext search
     (shutdown!)                        ;; Flush, commit, close

   Document schema:
     :id        - unique string: \"capture/<uuid>\", \"youtube/<vid>\", etc.
     :source    - filter: capture, youtube, rss, web, knowledge, conversation
     :url       - original URL
     :title     - fulltext searchable, stored
     :content   - fulltext searchable, NOT stored (too large)
     :domain    - filterable domain string
     :timestamp - epoch millis (long), for sorting
     :metadata  - EDN of source-specific extras (stored only)"
  (:require [scriptum.core :as sc]
            [clojure.string :as str]
            [taoensso.telemere :as tel])
  (:import [org.apache.lucene.analysis.standard StandardAnalyzer]
           [java.util.concurrent ScheduledExecutorService Executors TimeUnit]))

;; ============================================================================
;; State
;; ============================================================================

(defonce ^:private writer-a (atom nil))
(defonce ^:private commit-scheduler (atom nil))
(defonce ^:private pending-count (atom 0))

(def ^:private analyzer (StandardAnalyzer.))

;; ============================================================================
;; Lifecycle
;; ============================================================================

(defn- open-or-create
  "Try `(sc/open-branch …)` (or `create-index` if no branch yet). On
   LockObtainFailedException — typically a stale lock from a prior
   process that didn't shut down cleanly — delete the lock and retry
   once. If a real running process holds the lock, it'll re-create it
   on the next index write and the retry will fail again, surfacing
   the underlying error."
  [index-path]
  (let [open-fn
        (fn []
          (let [branches (try (sc/discover-branches index-path) (catch Exception _ nil))]
            (if (and branches (contains? branches "main"))
              (sc/open-branch index-path "main" {:analyzer analyzer})
              (sc/create-index index-path "main" {:analyzer analyzer}))))]
    (try (open-fn)
         (catch org.apache.lucene.store.LockObtainFailedException _
           (let [lock-file (java.io.File. ^String index-path "main_write.lock")]
             (when (.exists lock-file)
               (tel/log! {:level :warn :id :search/stale-lock-cleared
                          :data {:path (.getPath lock-file)}}
                         "Stale Lucene lock — removing and retrying")
               (.delete lock-file)))
           (open-fn)))))

(defn init!
  "Open or create the search index at `index-path`. Starts a background
   commit scheduler that flushes every 30 seconds.

   Safe to call repeatedly — no-ops if already initialized."
  [index-path]
  (when-not @writer-a
    (let [w (open-or-create index-path)
          sched (Executors/newSingleThreadScheduledExecutor)]
      (reset! writer-a w)
      (reset! pending-count 0)
      ;; Commit every 30s if there are pending docs
      (.scheduleAtFixedRate sched
        (fn []
          (try
            (when (pos? @pending-count)
              (sc/commit! @writer-a "auto-commit")
              (reset! pending-count 0)
              (tel/log! {:level :debug :id :search/auto-commit} "Search index committed"))
            (catch Exception e
              (tel/log! {:level :warn :id :search/commit-error
                         :data {:error (.getMessage e)}} "Search commit error"))))
        30 30 TimeUnit/SECONDS)
      (reset! commit-scheduler sched)
      (tel/log! {:level :info :id :search/initialized
                 :data {:path index-path :docs (sc/num-docs w)}} "Search index initialized"))))

(defn shutdown!
  "Flush, commit, and close the search index. Stops the commit scheduler."
  []
  (when-let [sched @commit-scheduler]
    (.shutdown ^ScheduledExecutorService sched)
    (reset! commit-scheduler nil))
  (when-let [w @writer-a]
    (try
      (when (pos? @pending-count)
        (sc/commit! w "shutdown commit"))
      (sc/close! w)
      (catch Exception e
        (tel/log! {:level :warn :id :search/close-error
                   :data {:error (.getMessage e)}} "Search close error")))
    (reset! writer-a nil)
    (reset! pending-count 0)
    (tel/log! {:id :search/shutdown} "Search index shut down")))

;; ============================================================================
;; Indexing
;; ============================================================================

(defn index-document!
  "Index a document. Upserts by :id (deletes old, adds new).

   Required keys:
     :id        - unique string, e.g. \"capture/abc-123\"
     :source    - string: capture, youtube, rss, web, knowledge, conversation

   Optional keys:
     :title     - searchable title (text field, stored)
     :content   - searchable body (text field, NOT stored)
     :url       - original URL (string field, stored)
     :domain    - domain for filtering (string field, stored)
     :timestamp - epoch millis or java.util.Date or java.time.Instant
     :metadata  - any map, stored as EDN string"
  [{:keys [id source title content url domain timestamp metadata]}]
  (when-let [w @writer-a]
    (when (and id source)
      (try
        ;; Delete previous version if exists
        (sc/delete-docs w "id" id)
        ;; Build Lucene doc
        (let [ts (cond
                   (instance? java.time.Instant timestamp) (.toEpochMilli ^java.time.Instant timestamp)
                   (instance? java.util.Date timestamp)    (.getTime ^java.util.Date timestamp)
                   (number? timestamp)                     (long timestamp)
                   :else                                   (System/currentTimeMillis))
              lucene-doc (cond-> {:id     {:value id     :type :string}
                                  :source {:value source :type :string}
                                  :timestamp {:value ts  :type :long}}
                           (not (str/blank? title))
                           (assoc :title {:value title :type :text :store? true})

                           (not (str/blank? content))
                           (assoc :content {:value content :type :text :store? false})

                           (not (str/blank? url))
                           (assoc :url {:value url :type :string})

                           (not (str/blank? domain))
                           (assoc :domain {:value domain :type :string})

                           metadata
                           (assoc :metadata {:value (pr-str metadata) :type :stored-only}))]
          (sc/add-doc w lucene-doc)
          (swap! pending-count inc)
          ;; Commit immediately if batch threshold reached
          (when (>= @pending-count 50)
            (sc/commit! w "batch commit")
            (reset! pending-count 0)))
        (catch Exception e
          (tel/log! {:level :warn :id :search/index-error
                     :data {:id id :error (.getMessage e)}} "Search index error"))))))

;; ============================================================================
;; Search
;; ============================================================================

(defn- build-query
  "Build a search query: fulltext across title+content, with optional filters."
  [query-str & {:keys [source domain]}]
  (let [text-q  (sc/multi-field-query ["title" "content"] query-str analyzer)
        clauses (cond-> [[text-q :must]]
                  (not (str/blank? source))
                  (conj [{:term [:source source]} :filter])
                  (not (str/blank? domain))
                  (conj [{:term [:domain domain]} :filter]))]
    (if (= 1 (count clauses))
      text-q
      (sc/bool-query clauses))))

(defn- result-fields
  "Standard set of stored fields to retrieve."
  []
  ["id" "source" "title" "url" "domain" "timestamp" "metadata"])

(defn- normalize-result
  "Convert a raw Scriptum result map to a clean keyword map."
  [r]
  (cond-> {:id     (get r "id")
            :source (get r "source")
            :score  (:score r)}
    (get r "title")     (assoc :title (get r "title"))
    (get r "url")       (assoc :url (get r "url"))
    (get r "domain")    (assoc :domain (get r "domain"))
    (get r "timestamp") (assoc :timestamp (get r "timestamp"))
    (get r "metadata")  (assoc :metadata (get r "metadata"))))

(defn search
  "Fulltext search across indexed documents.

   Args:
     query  - search query string
     :source - optional filter: \"capture\", \"youtube\", \"rss\", \"web\", \"knowledge\", \"conversation\"
     :domain - optional domain filter
     :limit  - max results (default 20)

   Returns vector of maps:
     [{:id :source :title :url :domain :timestamp :metadata :score}]"
  [query & {:keys [source domain limit] :or {limit 20}}]
  (when-let [w @writer-a]
    (try
      (if (str/blank? query)
        []
        (let [q (build-query query :source source :domain domain)
              _ (sc/flush! w)
              results (sc/search w q {:limit limit :fields (result-fields)})]
          (mapv normalize-result results)))
      (catch Exception e
        (tel/log! {:level :warn :id :search/query-error
                   :data {:query query :error (.getMessage e)}} "Search query error")
        []))))

(defn search-by-source
  "List documents from a specific source.

   Args:
     source - source string
     :limit - max results (default 20)

   Returns vector of document maps."
  [source & {:keys [limit] :or {limit 20}}]
  (when-let [w @writer-a]
    (try
      (sc/flush! w)
      (let [results (sc/search w {:term [:source source]}
                               {:limit limit :fields (result-fields)})]
        (mapv normalize-result results))
      (catch Exception e
        (tel/log! {:level :warn :id :search/source-query-error
                   :data {:source source :error (.getMessage e)}} "Search source error")
        []))))

;; ============================================================================
;; Utilities
;; ============================================================================

(defn doc-count
  "Number of indexed documents."
  []
  (if-let [w @writer-a]
    (sc/num-docs w)
    0))

(defn strip-html
  "Strip HTML tags and decode entities for re-indexing raw captures."
  [html]
  (-> html
      (str/replace #"(?i)<(script|style)[^>]*>[\s\S]*?</\1>" " ")
      (str/replace #"<[^>]+>" " ")
      (str/replace #"&amp;" "&")
      (str/replace #"&lt;" "<")
      (str/replace #"&gt;" ">")
      (str/replace #"&quot;" "\"")
      (str/replace #"&#39;" "'")
      (str/replace #"&nbsp;" " ")
      (str/replace #"\s+" " ")
      str/trim))

(defn extract-domain
  "Extract domain from a URL string."
  [url]
  (try
    (when-let [host (.getHost (java.net.URI. url))]
      (str/replace host #"^www\." ""))
    (catch Exception _ nil)))

(defn initialized?
  "True if the search index is open."
  []
  (some? @writer-a))
