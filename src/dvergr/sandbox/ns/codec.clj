(ns dvergr.sandbox.ns.codec
  "JSON / XML / base64 / url / html for the SCI sandbox, mounted under the
   ESTABLISHED names the model already knows (babashka/clojure):

     cheshire.core      — the REAL JSON lib, verbatim (generate-string/parse-string/…)
     clojure.data.xml   — our HARDENED XML parser under the standard name. We do NOT
                          expose the real clojure.data.xml (XXE/entity-expansion);
                          `parse-str` here forbids DOCTYPE → no XXE / billion-laughs / SSRF.
     dvergr.codec       — base64 / url / html helpers (no babashka equivalent; ours)."
  (:require [clojure.string :as str]
            [sci.core :as sci])
  (:import [javax.xml.parsers SAXParserFactory]
           [javax.xml XMLConstants]
           [org.xml.sax InputSource]
           [org.xml.sax.helpers DefaultHandler]
           [java.io StringReader]
           [java.net URLEncoder URLDecoder]
           [java.util Base64]))

;; ---------------------------------------------------------------------------
;; url / base64 / html  (dvergr.codec)
;; ---------------------------------------------------------------------------

(defn- url-encode [s] (URLEncoder/encode (str s) "UTF-8"))
(defn- url-decode [s] (URLDecoder/decode (str s) "UTF-8"))

(defn- ->bytes ^bytes [x] (if (bytes? x) x (.getBytes (str x) "UTF-8")))
(defn- b64-encode [x] (.encodeToString (Base64/getEncoder) (->bytes x)))
(defn- b64-decode-str [s] (String. (.decode (Base64/getDecoder) (str s)) "UTF-8"))

(def ^:private named-entities
  {"&amp;" "&" "&lt;" "<" "&gt;" ">" "&quot;" "\"" "&apos;" "'" "&#39;" "'"
   "&nbsp;" " " "&mdash;" "—" "&ndash;" "–" "&hellip;" "…" "&rsquo;" "’"
   "&lsquo;" "‘" "&ldquo;" "“" "&rdquo;" "”"})

(defn- html-decode-entities [s]
  (when s
    (-> (reduce (fn [acc [k v]] (str/replace acc k v)) (str s) named-entities)
        (str/replace #"&#(\d+);" (fn [[_ n]] (str (char (Integer/parseInt n)))))
        (str/replace #"&#x([0-9A-Fa-f]+);" (fn [[_ h]] (str (char (Integer/parseInt h 16))))))))

(defn- html-strip-tags [s]
  (when s
    (-> s
        ;; Drop <script>/<style> blocks ENTIRELY (tag + inner JS/CSS). Without
        ;; this, only the tags are removed and the CSS/JS body survives as text
        ;; soup (e.g. a wttr.in page's inline stylesheet). (?is) = case-insensitive
        ;; + dotall so the non-greedy body spans newlines.
        (str/replace #"(?is)<(script|style)\b[^>]*>.*?</\1>" " ")
        (str/replace #"<[^>]*>" " ")
        html-decode-entities
        (str/replace #"[ \t]+" " ")
        (str/replace #"\n{3,}" "\n\n")
        str/trim)))

;; ---------------------------------------------------------------------------
;; xml — hardened SAX → {:tag :attrs :content} (clojure.data.xml shape)
;; ---------------------------------------------------------------------------

(def ^:private max-xml-bytes (* 8 1024 1024))

(defn- hardened-factory ^SAXParserFactory []
  (doto (SAXParserFactory/newInstance)
    ;; disallow-doctype-decl: no DOCTYPE ⇒ no entity defs ⇒ kills XXE, billion-laughs, SSRF.
    (.setFeature "http://apache.org/xml/features/disallow-doctype-decl" true)
    (.setFeature "http://xml.org/sax/features/external-general-entities" false)
    (.setFeature "http://xml.org/sax/features/external-parameter-entities" false)
    (.setFeature XMLConstants/FEATURE_SECURE_PROCESSING true)
    (.setNamespaceAware false)
    (.setValidating false)))

(defn- xml-parse
  "Parse an XML string into {:tag :attrs :content [..]} (clojure.data.xml's shape).
   Bounded input; no DOCTYPE / external entities."
  [^String xml-str]
  (when (and xml-str (> (count xml-str) max-xml-bytes))
    (throw (ex-info "xml input too large" {:max-bytes max-xml-bytes})))
  (let [stack (atom (list {:content []}))
        add!  (fn [c] (swap! stack (fn [s] (conj (rest s) (update (first s) :content conj c)))))
        handler (proxy [DefaultHandler] []
                  (startElement [_ _ qname attrs]
                    (swap! stack conj
                           {:tag (keyword qname)
                            :attrs (into {} (map #(vector (keyword (.getQName attrs %))
                                                          (.getValue attrs %)))
                                         (range (.getLength attrs)))
                            :content []}))
                  (endElement [_ _ _]
                    (swap! stack (fn [s]
                                   (conj (rest (rest s))
                                         (update (second s) :content conj (first s))))))
                  (characters [ch start length]
                    (let [t (String. ^chars ch (int start) (int length))]
                      (when-not (str/blank? t) (add! t)))))]
    (.parse (.newSAXParser (hardened-factory))
            (InputSource. (StringReader. xml-str)) handler)
    (-> @stack first :content first)))

(defn- xml-text
  "Concatenated text content of an xml node (recursive) — handy for feeds. Not a
   clojure.data.xml fn, but a useful extra we keep on the namespace."
  [node]
  (cond
    (string? node) node
    (map? node)    (apply str (map xml-text (:content node)))
    :else          ""))

;; ---------------------------------------------------------------------------
;; mount
;; ---------------------------------------------------------------------------

(defn add-codec-namespaces!
  "Mount cheshire.core (real), clojure.data.xml (hardened), dvergr.codec (extras)."
  [sci-ctx]
  ;; the REAL cheshire.core — generate-string/parse-string/encode/decode/…
  (require 'cheshire.core)
  (sci/add-namespace! sci-ctx 'cheshire.core
                      (into {} (map (fn [[s v]] [s (deref v)])) (ns-publics 'cheshire.core)))
  ;; clojure.data.xml NAME, our hardened parser (string or reader)
  (sci/add-namespace! sci-ctx 'clojure.data.xml
                      {'parse-str xml-parse
                       'parse     (fn [in] (xml-parse (if (string? in) in (slurp in))))
                       'text      xml-text})
  ;; dvergr.codec — base64 / url / html (no babashka equivalent)
  (sci/add-namespace! sci-ctx 'dvergr.codec
                      {'base64-encode   b64-encode
                       'base64-decode   b64-decode-str
                       'url-encode      url-encode
                       'url-decode      url-decode
                       'decode-entities html-decode-entities
                       'strip-tags      html-strip-tags})
  sci-ctx)
