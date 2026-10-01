(ns case-pack.checker
  "The attempt's /out/answer.edn (a map from field to value) against the
   case's outcome, field by field, each under its rule (params :fields):
   :exact (as written; numbers as numbers), :ci (ignoring case and spacing),
   :number (within :tolerance, default 0.005; 1.234,56 and 1,234.56 read as
   numbers), :set (a vector, or a list split on , ; |), :date (ISO
   yyyy-mm-dd or dd.mm.yyyy). The reward is the share of fields right."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

(defn check-key [field] (keyword (str/replace (str/lower-case (str field)) #"[^a-z0-9]+" "-")))

(defn- answer [files]
  (let [a (try (edn/read-string (get files "/out/answer.edn" "")) (catch Exception _ nil))]
    (when (map? a) (into {} (map (fn [[k v]] [(if (keyword? k) (name k) (str k)) v])) a))))

(defn- text [x] (str/replace (str/trim (str x)) #"\s+" " "))

(defn- number [x]
  (if (number? x)
    (double x)
    (let [s (str/replace (str/trim (str x)) #"[\s'€$]" "")
          s (cond
              (and (str/includes? s ",") (str/includes? s "."))
              (if (> (str/last-index-of s ",") (str/last-index-of s "."))
                (str/replace (str/replace s "." "") "," ".")
                (str/replace s "," ""))
              (re-matches #"-?\d+,\d{1,2}" s) (str/replace s "," ".")
              :else (str/replace s "," ""))]
      (when (re-matches #"-?(\d+\.?\d*|\.\d+)([eE][-+]?\d+)?" s) (parse-double s)))))

(defn- date [x]
  (let [s (str/trim (str x))]
    (or (when-let [[_ y m d] (re-matches #"(\d{4})-(\d{1,2})-(\d{1,2}).*" s)] [(parse-long y) (parse-long m) (parse-long d)])
        (when-let [[_ d m y] (re-matches #"(\d{1,2})\.(\d{1,2})\.(\d{4})" s)] [(parse-long y) (parse-long m) (parse-long d)]))))

(defn- items [x]
  (set (map (comp str/lower-case text)
            (remove #(str/blank? (str %)) (if (sequential? x) x (str/split (str x) #"[,;|]"))))))

(defn- right? [{:keys [rule tolerance]} want got]
  (and (some? got)
       (case rule
         :ci (= (str/lower-case (text want)) (str/lower-case (text got)))
         :number (let [w (number want) g (number got)] (and w g (let [d (- w g)] (<= (if (neg? d) (- d) d) (or tolerance 0.005)))))
         :set (= (items want) (items got))
         :date (let [w (date want)] (and w (= w (date got))))
         (let [w (number want) g (number got)]
           (if (and w g (number? got)) (== w g) (= (text want) (text got)))))))

(defn check [{:keys [files gold params]}]
  (let [a (answer files)
        fields (:fields params)
        checks (into {:answer-present (some? a)}
                     (for [[f spec] fields]
                       [(check-key f) (boolean (and a (right? spec (get-in gold [:expected f]) (get a f))))]))
        n (count fields)]
    {:checks checks
     :reward (if (zero? n) 0.0 (double (/ (count (filter #(get checks (check-key %)) (keys fields))) n)))}))
