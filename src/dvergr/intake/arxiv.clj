(ns dvergr.intake.arxiv
  "arXiv intake via the public arXiv API (no auth required).
   Returns Atom XML parsed into paper records.
   API base: https://export.arxiv.org/api/query
   Rate limit: max 1 req/3s per arXiv policy."
  (:require [dvergr.intake.core :as intake]
            [dvergr.tools :as tools]
            [clojure.string :as str])
  (:import [java.io StringReader]
           [javax.xml.parsers SAXParserFactory]
           [org.xml.sax InputSource]
           [org.xml.sax.helpers DefaultHandler]))

(def ^:private api-base "https://export.arxiv.org/api/query")

(defn- parse-arxiv-atom
  "Parse arXiv Atom XML into a seq of paper maps."
  [xml-string]
  (let [papers   (atom [])
        current  (atom nil)
        path     (atom [])
        text-buf (StringBuilder.)
        in-entry?  #(some? @current)
        in-author? #(and (= :name (last @path))
                         (= :author (last (butlast @path))))
        handler
        (proxy [DefaultHandler] []
          (startElement [_uri _local qname attrs]
            (let [tag     (keyword (str/lower-case qname))
                  n-attrs (into {}
                                (for [i (range (.getLength attrs))]
                                  [(.getLocalName attrs i) (.getValue attrs i)]))]
              (swap! path conj tag)
              (.setLength text-buf 0)
              (case tag
                :entry (reset! current {:authors [] :categories []})
                :link  (when (in-entry?)
                         (let [rel  (get n-attrs "rel" "")
                               href (get n-attrs "href" "")]
                           (cond
                             (= rel "alternate")
                             (swap! current assoc :abs-url href)
                             (= (get n-attrs "title" "") "pdf")
                             (swap! current assoc :pdf-url href))))
                :category (when (in-entry?)
                            (when-let [term (get n-attrs "term")]
                              (swap! current update :categories conj term)))
                nil)))
          (endElement [_uri _local qname]
            (let [tag  (keyword (str/lower-case qname))
                  text (str/trim (str text-buf))]
              (when (in-entry?)
                (case tag
                  :id        (let [raw (str/replace text #"^http://arxiv\.org/abs/" "")]
                               (swap! current assoc
                                      :id raw
                                      :abs-url (str "https://arxiv.org/abs/" raw)))
                  :title     (swap! current assoc :title
                                    (str/replace text #"\s+" " "))
                  :summary   (swap! current assoc :summary
                                    (-> text
                                        (str/replace #"\s+" " ")
                                        (as-> s (if (> (count s) 500)
                                                  (str (subs s 0 500) "...")
                                                  s))))
                  :published (swap! current assoc :published
                                    (subs text 0 (min 10 (count text))))
                  :updated   (swap! current assoc :updated
                                    (subs text 0 (min 10 (count text))))
                  :name      (when (in-author?)
                               (swap! current update :authors conj text))
                  nil))
              (when (= tag :entry)
                (swap! papers conj @current)
                (reset! current nil))
              (swap! path (fn [p] (if (seq p) (pop p) p)))))
          (characters [ch start length]
            (.append text-buf ch start length)))]
    (try
      (let [factory (SAXParserFactory/newInstance)]
        (.setFeature factory "http://apache.org/xml/features/nonvalidating/load-external-dtd" false)
        (.setFeature factory "http://xml.org/sax/features/external-general-entities" false)
        (.setFeature factory "http://xml.org/sax/features/external-parameter-entities" false)
        (.parse (.newSAXParser factory) (InputSource. (StringReader. xml-string)) handler))
      @papers
      (catch Exception e {:error (str "XML parse error: " (.getMessage e))}))))

(defn search-papers
  "Search arXiv papers.
   query      - arXiv query string, e.g. ti:transformer or cat:cs.AI
   count      - max results (default 10, max 100)
   sort-by    - relevance | lastUpdatedDate | submittedDate (default relevance)
   sort-order - descending | ascending (default descending)
   start      - result offset for pagination (default 0)
   Returns a vector of paper maps or {:error ...}."
  [query & {:keys [count sort-by sort-order start]
            :or {count 10 sort-by "relevance" sort-order "descending" start 0}}]
  (let [params {:search_query query
                :max_results  (min count 100)
                :sortBy       sort-by
                :sortOrder    sort-order
                :start        start}
        body   (intake/fetch-text api-base :query-params params)]
    (if (and (map? body) (:error body))
      body
      (let [parsed (parse-arxiv-atom body)]
        (if (and (map? parsed) (:error parsed))
          parsed
          (vec parsed))))))

(defn fetch-paper
  "Fetch a single arXiv paper by its ID (e.g. 2303.08774 or 2303.08774v1).
   Returns the paper map or {:error ...}."
  [arxiv-id]
  (let [clean-id (str/replace arxiv-id #"^https?://arxiv\.org/abs/" "")
        params   {:id_list    clean-id
                  :max_results 1}
        body     (intake/fetch-text api-base :query-params params)]
    (if (and (map? body) (:error body))
      body
      (let [parsed (parse-arxiv-atom body)]
        (if (and (map? parsed) (:error parsed))
          parsed
          (first parsed))))))

(defn- format-paper [paper]
  {:title   (:title paper)
   :url     (or (:abs-url paper) (str "https://arxiv.org/abs/" (:id paper)))
   :summary (str (when (:published paper) (str (:published paper) " - "))
                 (when (seq (:authors paper))
                   (str (str/join ", " (take 3 (:authors paper)))
                        (when (> (count (:authors paper)) 3) " et al.")
                        " - "))
                 (or (:summary paper) ""))
   :tags    (take 3 (:categories paper))})

(tools/register!
 {:name        "arxiv_search"
  :description "Search arXiv preprints by keyword or structured query. Supports category filters like cat:cs.AI, cat:cs.LG, cat:quant-ph. Use ti: for title, au: for author, abs: for abstract. Returns papers with abstracts, authors, and links."
  :parameters  {:type       "object"
                :properties {:query      {:type "string" :description "arXiv query string. Examples: transformer attention, ti:RLHF, cat:cs.AI LLM, au:Hinton deep learning"}
                             :count      {:type "integer" :description "Number of results to return (default 10, max 100)"}
                             :sort_by    {:type "string" :description "Sort order: relevance, lastUpdatedDate, or submittedDate (default relevance)" :enum ["relevance" "lastUpdatedDate" "submittedDate"]}
                             :sort_order {:type "string" :description "Sort direction: descending or ascending (default descending)" :enum ["descending" "ascending"]}
                             :start      {:type "integer" :description "Result offset for pagination (default 0)"}}
                :required   ["query"]}
  :execute     (fn [{:keys [query count sort_by sort_order start]} _ctx]
                 (let [papers (search-papers query
                                             :count      (or count 10)
                                             :sort-by    (or sort_by "relevance")
                                             :sort-order (or sort_order "descending")
                                             :start      (or start 0))]
                   (if (:error papers)
                     (intake/error-response (:error papers))
                     (intake/success-response
                      (intake/format-items (str "arXiv: " query) (map format-paper papers))
                      :arxiv (clojure.core/count papers) papers))))})

(tools/register!
 {:name        "arxiv_fetch"
  :description "Fetch a specific arXiv paper by its ID (e.g. 2303.08774). Returns full metadata including abstract, authors, categories, and PDF link."
  :parameters  {:type       "object"
                :properties {:arxiv_id {:type "string" :description "arXiv paper ID, e.g. 2303.08774 or 2303.08774v2. Also accepts full abstract URL."}}
                :required   ["arxiv_id"]}
  :execute     (fn [{:keys [arxiv_id]} _ctx]
                 (let [paper (fetch-paper arxiv_id)]
                   (if (or (nil? paper) (:error paper))
                     (intake/error-response (or (:error paper) (str "Paper not found: " arxiv_id)))
                     (let [content (str "## " (:title paper) "\n\n"
                                        "**ID:** " (:id paper) "\n"
                                        "**Published:** " (:published paper) "\n"
                                        "**Authors:** " (str/join ", " (:authors paper)) "\n"
                                        "**Categories:** " (str/join ", " (:categories paper)) "\n"
                                        "**Abstract URL:** " (:abs-url paper) "\n"
                                        (when (:pdf-url paper) (str "**PDF:** " (:pdf-url paper) "\n"))
                                        "\n**Abstract:**\n" (:summary paper))]
                       (intake/success-response content :arxiv 1 [paper])))))})
