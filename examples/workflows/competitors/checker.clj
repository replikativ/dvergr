(ns competitors.checker
  "Competitor discovery: a list of products competing with the given one, each
   with its site, a one-line claim, and a quote from a page the run fetched.
   Scored on recall of the reference set, on grounding (a quote counts only if
   it is on a page the run actually fetched, `:fetched`) and on relevance: the
   judge model says whether each entry competes with the product."
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

(defn- listed
  "The entries, without the product itself."
  [files params]
  (let [self (norm (:product params))]
    (vec (remove #(= self (norm (:name %))) (entries files)))))

(defn judge-requests
  "One question per entry: does it compete with the product? The judge sees the
   entry's claim and the page it cited, as the run fetched it."
  [{:keys [files fetched params]}]
  (vec (for [{:keys [name url claim source-url]} (listed files params)
             :let [page (str (get fetched source-url ""))]]
         {:id (norm name)
          :prompt (str "Product: " (:product params) ", " (:description params) "\n\n"
                       "Candidate: " name " (" url "), described as: " claim "\n"
                       "From its page: " (subs page 0 (min (count page) 1500)) "\n\n"
                       "Is the candidate a product that competes with " (:product params)
                       ", i.e. a system where people and AI agents work together on an organization's shared state? "
                       "Answer yes or no, then one sentence.")})))

(defn- relevant? [judgements entry]
  (str/starts-with? (str/lower-case (str/trim (get judgements (norm (:name entry)) ""))) "yes"))

(defn check [{:keys [files fetched judgements gold params]}]
  (let [es (listed files params)
        found (filter (fn [g] (some #(matches? % g) es)) (:reference gold))
        recall (/ (count found) (max 1 (count (:reference gold))))
        grounded (filter #(grounded? fetched %) es)
        grounding (if (seq es) (/ (count grounded) (count es)) 0)
        relevant (filter #(relevant? judgements %) es)
        relevance (if (seq es) (/ (count relevant) (count es)) 0)
        names (map (comp norm :name) es)]
    {:checks {:wrote-list? (boolean (seq es))
              :complete-entries? (boolean (and (seq es) (every? #(every? (comp seq str %) [:name :url :claim :quote :source-url]) es)))
              :found-reference? (= (count found) (count (:reference gold)))
              :every-quote-fetched? (boolean (and (seq es) (= (count grounded) (count es))))
              :no-duplicates? (= (count names) (count (distinct names)))
              :all-relevant? (boolean (and (seq es) (= (count relevant) (count es))))}
     :reward (double (+ (* 0.4 recall) (* 0.3 grounding) (* 0.3 relevance)))}))
