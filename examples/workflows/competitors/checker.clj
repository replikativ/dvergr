(ns competitors.checker
  "Competitor discovery: a list of products competing with the given one, each
   with its site, a one-line claim, and a quote from a page the run fetched.
   Scored on recall of the reference set, and on grounding: a quote counts only
   if it is on a page the run actually fetched (`:fetched`)."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

(defn- norm [s]
  (-> (str s) str/lower-case (str/replace #"<[^>]*>" " ") (str/replace #"&[a-z#0-9]+;" " ")
      (str/replace #"[^a-z0-9]+" " ") str/trim))

(defn- entries [files]
  (try
    (let [v (edn/read-string (get files "/out/competitors.edn" ""))]
      (when (and (sequential? v) (every? map? v)) (vec v)))
    (catch Exception _ nil)))

(defn- matches?
  "Same product: the same name, or a site under one of its known addresses
   (a host, or a host and path for a repository)."
  [entry {:keys [name hosts]}]
  (let [url (str/lower-case (str (:url entry)))
        url (str/replace url #"^https?://(?:www\.)?" "")]
    (or (= (norm (:name entry)) (norm name))
        (some #(str/starts-with? url %) hosts))))

(defn- grounded? [fetched {:keys [quote source-url]}]
  (let [q (norm quote)
        body (get fetched source-url)]
    (and (<= 20 (count q)) body (str/includes? (norm body) q))))

(defn check [{:keys [files fetched gold params]}]
  (let [es (entries files)
        self (norm (:product params))
        es (remove #(= self (norm (:name %))) es)
        found (filter (fn [g] (some #(matches? % g) es)) (:reference gold))
        recall (/ (count found) (max 1 (count (:reference gold))))
        grounded (filter #(grounded? fetched %) es)
        grounding (if (seq es) (/ (count grounded) (count es)) 0)
        names (map (comp norm :name) es)]
    {:checks {:wrote-list? (boolean (seq es))
              :complete-entries? (boolean (and (seq es) (every? #(every? (comp seq str %) [:name :url :claim :quote :source-url]) es)))
              :found-reference? (= (count found) (count (:reference gold)))
              :every-quote-fetched? (boolean (and (seq es) (= (count grounded) (count es))))
              :no-duplicates? (= (count names) (count (distinct names)))}
     :reward (double (+ (* 0.5 recall) (* 0.5 grounding)))}))
