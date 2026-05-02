(ns dvergr.web.intake
  "HTTP handler for browser extension page captures.

   Receives page data from the Dvergr Feed extension, filters by domain,
   stores raw HTML to disk, indexes metadata in Datahike, and publishes
   to the browser-feed room bus.

   Raw HTML is archived at data/captures/<uuid>.html so extractors can
   be re-run and improved over time.

   Route: POST /api/intake/page"
  (:require [jsonista.core :as json]
            [datahike.api :as dh]
            [dvergr.rooms :as rooms]
            [dvergr.rooms.bus :as bus]
            [dvergr.search :as search]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [taoensso.telemere :as tel]))

;; ============================================================================
;; State
;; ============================================================================

(defonce ^:private conn-a (atom nil))

(def ^:private json-mapper
  (json/object-mapper {:encode-key-fn name
                        :decode-key-fn keyword}))

;; Domain allowlist — domains that will be stored. Others return 200 "filtered".
(defonce ^:private allowed-domains (atom #{"linkedin.com"}))

;; Base directory for raw captures
(def ^:private captures-dir "data/captures")

(defn init!
  "Store the shared Datahike connection. Call once at startup."
  [datahike-conn]
  (reset! conn-a datahike-conn)
  ;; Ensure captures directory exists
  (.mkdirs (io/file captures-dir)))

(defn add-allowed-domain!
  "Add a domain to the allowlist."
  [domain]
  (swap! allowed-domains conj domain))

(defn set-allowed-domains!
  "Replace the domain allowlist."
  [domains]
  (reset! allowed-domains (set domains)))

;; ============================================================================
;; Helpers
;; ============================================================================

(defn- extract-domain
  "Extract domain from a URL string. Returns nil on parse failure."
  [url]
  (try
    (let [host (.getHost (java.net.URI. url))]
      (when host
        ;; Strip www. prefix for matching
        (str/replace host #"^www\." "")))
    (catch Exception _ nil)))

(defn- domain-allowed?
  "Check if a URL's domain is in the allowlist."
  [url]
  (when-let [domain (extract-domain url)]
    (some #(or (= domain %) (str/ends-with? domain (str "." %)))
          @allowed-domains)))

(defn- json-response [status body]
  {:status status
   :headers {"Content-Type" "application/json"
             "Access-Control-Allow-Origin" "*"
             "Access-Control-Allow-Methods" "POST, OPTIONS"
             "Access-Control-Allow-Headers" "Content-Type"}
   :body (json/write-value-as-string body json-mapper)})

(defn- read-json-body
  "Read and parse JSON request body."
  [req]
  (try
    (when-let [body (:body req)]
      (let [s (if (string? body) body (slurp body))]
        (json/read-value s json-mapper)))
    (catch Exception _
      nil)))

;; ============================================================================
;; Raw HTML Storage
;; ============================================================================

(defn- save-raw-html!
  "Save raw HTML to disk. Returns the file path."
  [id html]
  (let [path (str captures-dir "/" id ".html")]
    (.mkdirs (io/file captures-dir))
    (spit (io/file path) html :encoding "UTF-8")
    path))

(defn load-raw-html
  "Load raw HTML from a capture path. Returns string or nil."
  [path]
  (let [f (io/file path)]
    (when (.exists f)
      (slurp f :encoding "UTF-8"))))

;; ============================================================================
;; Datahike Storage
;; ============================================================================

(defn- store-page-capture!
  "Store page capture metadata in Datahike, raw HTML on disk.
   Returns [id raw-path]."
  [conn data]
  (let [id (random-uuid)
        domain (or (extract-domain (:url data)) "unknown")
        ;; Save raw HTML to disk if present
        raw-path (when-let [html (:html data)]
                   (save-raw-html! id html))
        entity (cond-> {:page-capture/id id
                        :page-capture/url (or (:url data) "")
                        :page-capture/title (or (:title data) "")
                        :page-capture/domain domain
                        :page-capture/text (let [t (or (:text data) "")]
                                             (if (> (count t) 50000)
                                               (subs t 0 50000)
                                               t))
                        :page-capture/meta (pr-str (merge
                                                     (or (:meta data) {})
                                                     (when (:linkedin data)
                                                       {:linkedin (:linkedin data)})))
                        :page-capture/source (or (:source data) "extension")
                        :page-capture/timestamp (java.util.Date.)}
                 raw-path (assoc :page-capture/raw-path raw-path))]
    (dh/transact conn [entity])
    [id raw-path]))

;; ============================================================================
;; Handler
;; ============================================================================

(defn handle-page-intake
  "Handle POST /api/intake/page from the browser extension.

   Expects JSON body:
     {:url :title :html :text :meta :source :timestamp :linkedin {...}}

   The :html field contains the full DOM (document.documentElement.outerHTML)
   which is saved to disk for archival. Text and meta are indexed in Datahike.

   Returns:
     {:status \"received\" :id <uuid>}      — stored successfully
     {:status \"filtered\" :reason \"...\"}  — domain not in allowlist
     {:status \"error\" :error \"...\"}      — parse/storage failure"
  [req]
  (let [data (read-json-body req)]
    (cond
      (nil? data)
      (json-response 400 {:status "error" :error "Invalid JSON body"})

      (str/blank? (:url data))
      (json-response 400 {:status "error" :error "Missing required field: url"})

      (not (domain-allowed? (:url data)))
      (do
        (tel/log! {:level :debug :id :intake/page-filtered
                   :data {:url (:url data) :domain (extract-domain (:url data))}}
                  "Page capture filtered by domain")
        (json-response 200 {:status "filtered"
                            :reason (str "Domain not in allowlist: " (extract-domain (:url data)))}))

      :else
      (if-let [conn @conn-a]
        (try
          (let [[id raw-path] (store-page-capture! conn data)
                domain (extract-domain (:url data))
                html-size (when (:html data) (count (:html data)))
                preview (str (str/capitalize (or domain ""))
                             (when (:title data)
                               (str ": " (subs (:title data) 0 (min 80 (count (:title data))))))
                             (when html-size
                               (str " [" (quot html-size 1024) "KB]")))]
            ;; Publish to room bus for agent subscriptions
            (when (bus/initialized?)
              (bus/publish! {:room-slug "browser-feed"
                             :role :system
                             :source "extension"
                             :preview preview
                             :timestamp (java.util.Date.)
                             :page-capture-id (str id)
                             :url (:url data)
                             :domain domain
                             :raw-path raw-path}))
            ;; Index in fulltext search
            (when (search/initialized?)
              (search/index-document!
                {:id        (str "capture/" id)
                 :source    "capture"
                 :title     (or (:title data) "")
                 :content   (or (:text data) "")
                 :url       (or (:url data) "")
                 :domain    (or domain "")
                 :timestamp (java.util.Date.)
                 :metadata  {:raw-path raw-path}}))
            (tel/log! {:level :info :id :intake/page-received
                       :data {:url (:url data) :domain domain :id id
                              :html-kb (when html-size (quot html-size 1024))
                              :raw-path raw-path}}
                      "Page capture received")
            (json-response 200 {:status "received" :id (str id)}))
          (catch Exception e
            (tel/log! {:level :error :id :intake/page-store-error
                       :data {:url (:url data) :error (.getMessage e)}}
                      "Page capture storage error")
            (json-response 500 {:status "error" :error (.getMessage e)})))
        (json-response 503 {:status "error" :error "Database not initialized"})))))

(defn handle-cors-preflight
  "Handle OPTIONS preflight for CORS."
  [_req]
  {:status 204
   :headers {"Access-Control-Allow-Origin" "*"
             "Access-Control-Allow-Methods" "POST, OPTIONS"
             "Access-Control-Allow-Headers" "Content-Type"
             "Access-Control-Max-Age" "86400"}})
