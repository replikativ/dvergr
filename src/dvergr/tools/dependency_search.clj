(ns dvergr.tools.dependency-search
  "Search for Clojure/Java dependencies across Clojars, Maven Central, and GitHub."
  (:require [jsonista.core :as json])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers]))

;; ============================================================================
;; HTTP Helper
;; ============================================================================

(defn- http-get-json
  "Simple HTTP GET that returns parsed JSON.

   Args:
     url - URL string

   Returns:
     Parsed JSON as Clojure data"
  [url]
  (let [client (HttpClient/newHttpClient)
        request (-> (HttpRequest/newBuilder)
                    (.uri (URI. url))
                    (.header "Accept" "application/json")
                    (.GET)
                    (.build))
        response (.send client request (HttpResponse$BodyHandlers/ofString))
        body (.body response)]
    (json/read-value body json/keyword-keys-object-mapper)))

;; ============================================================================
;; Clojars API
;; ============================================================================

(defn search-clojars
  "Search Clojars for a library.

   Args:
     query - Search term (library name)

   Returns:
     Vector of {:group :name :version :description}"
  [query]
  (try
    (let [url (str "https://clojars.org/search?q=" (java.net.URLEncoder/encode query "UTF-8") "&format=json")
          response (http-get-json url)
          results (:results response)]
      (mapv (fn [result]
              {:source :clojars
               :group (:group_name result)
               :name (:jar_name result)
               :coordinate (str (:group_name result) "/" (:jar_name result))
               :version (:version result)
               :description (:description result)
               :url (str "https://clojars.org/" (:group_name result) "/" (:jar_name result))})
            (take 5 results)))
    (catch Exception e
      [{:error (.getMessage e)}])))

;; ============================================================================
;; Maven Central API
;; ============================================================================

(defn search-maven-central
  "Search Maven Central for a library.

   Args:
     query - Search term (library name)

   Returns:
     Vector of {:group :name :version :description}"
  [query]
  (try
    (let [url (str "https://search.maven.org/solrsearch/select?q="
                   (java.net.URLEncoder/encode query "UTF-8")
                   "&rows=5&wt=json")
          response (http-get-json url)
          docs (get-in response [:response :docs])]
      (mapv (fn [doc]
              {:source :maven-central
               :group (:g doc)
               :name (:a doc)
               :coordinate (str (:g doc) "/" (:a doc))
               :version (:latestVersion doc)
               :description (or (:description doc) "")
               :url (str "https://search.maven.org/artifact/" (:g doc) "/" (:a doc))})
            docs))
    (catch Exception e
      [{:error (.getMessage e)}])))

;; ============================================================================
;; Combined Search
;; ============================================================================

(defn search-dependency
  "Search for a dependency across Clojars and Maven Central.

   Args:
     query - Search term (library name, e.g., 'buddy', 'http-kit', 'jackson')
     source - Optional: :clojars, :maven-central, or :all (default :all)

   Returns:
     {:clojars [...] :maven-central [...]} or results from single source"
  ([query] (search-dependency query :all))
  ([query source]
   (case source
     :clojars {:clojars (search-clojars query)}
     :maven-central {:maven-central (search-maven-central query)}
     :all {:clojars (search-clojars query)
           :maven-central (search-maven-central query)})))

(defn format-search-results
  "Format search results for display.

   Args:
     results - Map from search-dependency

   Returns:
     Formatted string with top results"
  [results]
  (let [format-result (fn [r]
                        (str "  " (:coordinate r) " \"" (:version r) "\"\n"
                             "    " (:description r) "\n"
                             "    " (:url r)))
        clojars-results (:clojars results)
        maven-results (:maven-central results)]
    (str "## Clojars Results\n"
         (if (seq clojars-results)
           (clojure.string/join "\n\n" (map format-result clojars-results))
           "  No results found\n")
         "\n\n## Maven Central Results\n"
         (if (seq maven-results)
           (clojure.string/join "\n\n" (map format-result maven-results))
           "  No results found"))))

;; ============================================================================
;; Tool Definition
;; ============================================================================

(def search-dependency-tool
  {:name "search_dependency"
   :description "Search for Clojure/Java libraries across Clojars and Maven Central.

Use this to find dependencies before requesting them. Returns Maven coordinates
and latest versions.

Example searches:
- \"buddy\" - finds buddy-core, buddy-auth, buddy-hashers
- \"http-kit\" - finds http-kit server library
- \"jackson\" - finds Jackson JSON libraries
- \"ring\" - finds Ring web server libraries

Returns top 5 results from each repository with:
- Maven coordinate (group/artifact)
- Latest version
- Description
- URL for more info"
   :parameters {:type "object"
                :properties {:query {:type "string"
                                     :description "Library name to search for"}
                             :source {:type "string"
                                      :enum ["all" "clojars" "maven-central"]
                                      :description "Which repository to search (default: all)"}}
                :required ["query"]}
   :execute (fn [{:keys [query source]} _ctx]
              (try
                (let [results (search-dependency query (or (keyword source) :all))
                      formatted (format-search-results results)]
                  {:type :success
                   :content formatted
                   :metadata results})
                (catch Exception e
                  {:type :error
                   :error (str "Dependency search failed: " (.getMessage e))})))})
