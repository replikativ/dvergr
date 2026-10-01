(ns dvergr.catalog.casepack
  "A case pack: a table of historical cases, each the inputs it had and the
   outcome it got, turned into a room-workflow dataset (`dvergr.catalog.room`)
   that benchmarks any agent workflow against those outcomes.

   A spec names the table's columns:

     {:name     \"invoice-coding\"            ; the bundle's name
      :title    \"Invoice coding\"
      :id       \"invoice_no\"                ; the column naming a case
      :inputs   [\"vendor\" \"amount\" \"text\"]  ; into /docs/case.edn
      :attachments [\"document\"]              ; a text file next to the table → /docs/<name>
      :expected {\"account\" {:rule :exact :doc \"the SKR04 account\"}
                 \"amount\"  {:rule :number :tolerance 0.01}
                 \"tags\"    {:rule :set}
                 \"vendor_name\" {:rule :ci}
                 \"due\"     {:rule :date}}
      :task     \"…\"                          ; optional; else one is written
      :shared   {\"/docs/accounts.txt\" \"…\"}}  ; files every case's world has

   Every case becomes `cases/<id>/`: its inputs as `/docs/case.edn` (and its
   attachments), its outcome as `gold.edn`. The checker reads the attempt's
   `/out/answer.edn`, a map from expected field to value, and scores each
   field by its rule; the reward is the share of fields right.

   Certification decides which cases can grade an answer at all, and says why
   the others cannot: no id or a duplicate one, an outcome left empty,
   identical inputs with different outcomes (label noise), or an outcome the
   checker itself does not accept as the right answer. Only certified cases
   enter the bundle; `certification.edn` lists every case's verdict."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [dvergr.catalog.room :as room]
            [jsonista.core :as json]))

;; ---------------------------------------------------------------------------
;; Reading a table
;; ---------------------------------------------------------------------------

(defn parse-csv
  "Rows of CSV `text` (RFC 4180: quoted fields with \"\" escapes and line
   breaks, CRLF or LF, a leading byte-order mark), as vectors of strings.
   `delimiter` defaults to whichever of `,` `;` and tab the first line has
   most of (German exports use `;`)."
  ([text] (parse-csv text nil))
  ([text delimiter]
   (let [text (cond-> (str text) (str/starts-with? (str text) "\uFEFF") (subs 1))
         first-line (first (str/split-lines text))
         delim (or delimiter
                   (first (apply max-key second (for [d [\, \; \tab]] [d (count (filter #{d} first-line))]))))
         n (count text)]
     (loop [i 0 field (StringBuilder.) row [] rows [] quoted? false]
       (if (>= i n)
         (let [row (conj row (str field))]
           (cond-> rows (not (and (= 1 (count row)) (= "" (first row)))) (conj row)))
         (let [c (.charAt ^String text i)]
           (cond
             quoted?
             (cond
               (and (= c \") (< (inc i) n) (= \" (.charAt ^String text (inc i))))
               (recur (+ i 2) (.append field \") row rows true)
               (= c \") (recur (inc i) field row rows false)
               :else (recur (inc i) (.append field c) row rows true))
             (and (= c \") (zero? (.length field))) (recur (inc i) field row rows true)
             (= c delim) (recur (inc i) (StringBuilder.) (conj row (str field)) rows false)
             (= c \return) (recur (inc i) field row rows false)
             (= c \newline)
             (let [row (conj row (str field))]
               (recur (inc i) (StringBuilder.) []
                      (cond-> rows (not (and (= 1 (count row)) (= "" (first row)))) (conj row)) false))
             :else (recur (inc i) (.append field c) row rows false))))))))

(defn table-rows
  "The rows of a table's `text` as maps from column name to value: CSV (header
   first), JSON lines, or EDN (a vector of maps), by the extension of `path`
   or `:format`."
  [text path & [{:keys [format delimiter]}]]
  (let [fmt (or format (keyword (str/lower-case (or (second (re-find #"\.([^./]+)$" (str path))) "csv"))))]
    (case fmt
      (:csv :tsv :txt)
      (let [[header & rows] (parse-csv text (or delimiter (when (= fmt :tsv) \tab)))
            header (mapv str/trim header)]
        (mapv #(zipmap header (concat % (repeat ""))) rows))
      (:jsonl :ndjson)
      (into [] (comp (remove str/blank?) (map #(json/read-value %))) (str/split-lines text))
      :edn (let [v (edn/read-string text)]
             (mapv #(update-keys % (fn [k] (if (keyword? k) (name k) (str k)))) v))
      (throw (ex-info (str "Not a table format: " fmt) {:type ::format :format fmt})))))

(defn read-table
  "The rows of the table file at `path` (`table-rows`)."
  [path & [opts]]
  (table-rows (slurp path) path opts))

;; ---------------------------------------------------------------------------
;; The checker
;; ---------------------------------------------------------------------------

(def ^:private check-key-fn
  "One spelling, in the checker and here, of the check a field becomes."
  "(defn check-key [field] (keyword (str/replace (str/lower-case (str field)) #\"[^a-z0-9]+\" \"-\")))")

(defn check-key [field] (keyword (str/replace (str/lower-case (str field)) #"[^a-z0-9]+" "-")))

(def checker-source
  (str "(ns case-pack.checker
  \"The attempt's /out/answer.edn (a map from field to value) against the
   case's outcome, field by field, each under its rule (params :fields):
   :exact (as written; numbers as numbers), :ci (ignoring case and spacing),
   :number (within :tolerance, default 0.005; 1.234,56 and 1,234.56 read as
   numbers), :set (a vector, or a list split on , ; |), :date (ISO
   yyyy-mm-dd or dd.mm.yyyy). The reward is the share of fields right.\"
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

" check-key-fn "

(defn- answer [files]
  (let [a (try (edn/read-string (get files \"/out/answer.edn\" \"\")) (catch Exception _ nil))]
    (when (map? a) (into {} (map (fn [[k v]] [(if (keyword? k) (name k) (str k)) v])) a))))

(defn- text [x] (str/replace (str/trim (str x)) #\"\\s+\" \" \"))

(defn- number [x]
  (if (number? x)
    (double x)
    (let [s (str/replace (str/trim (str x)) #\"[\\s'€$]\" \"\")
          s (cond
              (and (str/includes? s \",\") (str/includes? s \".\"))
              (if (> (str/last-index-of s \",\") (str/last-index-of s \".\"))
                (str/replace (str/replace s \".\" \"\") \",\" \".\")
                (str/replace s \",\" \"\"))
              (re-matches #\"-?\\d+,\\d{1,2}\" s) (str/replace s \",\" \".\")
              :else (str/replace s \",\" \"\"))]
      (when (re-matches #\"-?(\\d+\\.?\\d*|\\.\\d+)([eE][-+]?\\d+)?\" s) (parse-double s)))))

(defn- date [x]
  (let [s (str/trim (str x))]
    (or (when-let [[_ y m d] (re-matches #\"(\\d{4})-(\\d{1,2})-(\\d{1,2}).*\" s)] [(parse-long y) (parse-long m) (parse-long d)])
        (when-let [[_ d m y] (re-matches #\"(\\d{1,2})\\.(\\d{1,2})\\.(\\d{4})\" s)] [(parse-long y) (parse-long m) (parse-long d)]))))

(defn- items [x]
  (set (map (comp str/lower-case text)
            (remove #(str/blank? (str %)) (if (sequential? x) x (str/split (str x) #\"[,;|]\"))))))

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
"))

;; ---------------------------------------------------------------------------
;; Certification and the bundle
;; ---------------------------------------------------------------------------

(defn- case-id [v] (str/replace (str/trim (str v)) #"[^A-Za-z0-9._-]+" "_"))

(defn- blank? [v] (or (nil? v) (and (string? v) (str/blank? v)) (and (coll? v) (empty? v))))

(defn- default-task [{:keys [inputs attachments expected]}]
  (str "A case is in /docs/case.edn: a map of " (str/join ", " (map pr-str inputs)) "."
       (when (seq attachments) (str " Its documents are in /docs/ (" (str/join ", " attachments) " columns)."))
       " Determine its outcome and write /out/answer.edn: an EDN map from each of these fields (the name as a"
       " string) to its value:"
       (str/join (for [[f {:keys [rule doc]}] expected]
                   (str "\n- " (pr-str f) (when doc (str ": " doc))
                        (case rule
                          :number " (a number)" :set " (a vector of values)" :date " (a date, yyyy-mm-dd)" ""))))))

(defn certify
  "Each row's verdict: `{:id :status :reasons [...]}`, status :certified or
   :excluded."
  [{:keys [id inputs expected] :as spec} rows read-attachment]
  (let [ids (frequencies (keep #(let [v (get % id)] (when-not (blank? v) (case-id v))) rows))
        input-of (fn [r] (select-keys r inputs))
        ;; identical inputs, different outcomes: the history disagrees with itself
        outcomes (reduce (fn [m r] (update m (input-of r) (fnil conj #{}) (select-keys r (keys expected)))) {} rows)]
    (vec
     (for [[i r] (map-indexed vector rows)
           :let [raw (get r id)
                 cid (when-not (blank? raw) (case-id raw))
                 unlabelled (vec (filter #(blank? (get r %)) (keys expected)))
                 missing-attachments (vec (for [a (:attachments spec)
                                                :let [p (get r a)]
                                                :when (or (blank? p) (nil? (read-attachment (str p))))]
                                            a))
                 gold {:expected (select-keys r (keys expected))}
                 self (when (and cid (empty? unlabelled))
                        (room/run-checker checker-source
                                          {:files {"/out/answer.edn" (pr-str (:expected gold))}
                                           :gold gold :params {:fields expected}}))
                 rejected (vec (for [f (keys expected) :when (and self (not (get-in self [:checks (check-key f)])))] f))
                 reasons (cond-> []
                           (nil? cid) (conj {:reason :no-id :row (inc i)})
                           (and cid (< 1 (get ids cid))) (conj {:reason :duplicate-id})
                           (seq unlabelled) (conj {:reason :unlabelled :fields unlabelled})
                           (seq missing-attachments) (conj {:reason :missing-attachment :columns missing-attachments})
                           (< 1 (count (get outcomes (input-of r)))) (conj {:reason :conflicting-outcomes})
                           (seq rejected) (conj {:reason :outcome-not-gradable :fields rejected}))]]
       {:id (or cid (str "row-" (inc i))) :status (if (empty? reasons) :certified :excluded) :reasons reasons}))))

(defn case-pack
  "The bundle files (`{relative-path text}`) and certification for `spec`
   over `rows`; `read-attachment` is a fn of an attachment's path (as the
   table names it) to its text, or nil when there is none."
  [{:keys [title doc id inputs attachments expected task shared] :as spec} rows read-attachment]
  (let [read-attachment (or read-attachment (constantly nil))
        verdicts (certify spec rows read-attachment)
        certified (into [] (keep (fn [[r v]] (when (= :certified (:status v)) [r (:id v)]))) (map vector rows verdicts))
        case-files (fn [r cid]
                     (into {(str "cases/" cid "/fixtures/docs/case.edn") (pr-str (select-keys r inputs))
                            (str "cases/" cid "/gold.edn") (pr-str {:expected (select-keys r (keys expected))})}
                           (for [a attachments :let [p (str (get r a))]]
                             [(str "cases/" cid "/fixtures/docs/" (.getName (io/file p))) (read-attachment p)])))
        [ref-row ref-id] (first certified)
        wrong (fn [f v] (if (= :number (get-in expected [f :rule]))
                          (str (+ 1000.0 (* 1000.0 (or (get-in expected [f :tolerance]) 1.0))
                                  (or (parse-double (str/replace (str v) "," ".")) 0.0)))
                          (str "not " v)))
        summary (frequencies (mapcat (fn [v] (if (seq (:reasons v)) (map :reason (:reasons v)) [:certified])) verdicts))]
    {:certification {:cases (count rows) :certified (count certified)
                     :by-reason (into (sorted-map) summary) :verdicts verdicts}
     :files
     (when ref-row
       (merge
        {"workflow.edn" (pr-str (cond-> {:title (or title (:name spec))
                                         :task (or task (default-task spec))
                                         :params {:fields expected}
                                         :capture ["/out"]}
                                  doc (assoc :doc doc)))
         "checker.clj" checker-source
         "calibration.edn"
         (pr-str {:case ref-id
                  :reference {"/out/answer.edn" (pr-str (select-keys ref-row (keys expected)))}
                  :damaged (into {} (for [f (keys expected)]
                                      [(check-key f)
                                       {:files {"/out/answer.edn" (pr-str (assoc (select-keys ref-row (keys expected))
                                                                                 f (wrong f (get ref-row f))))}
                                        :loses [(check-key f)]}]))})
         "certification.edn" (with-out-str (pprint/pprint {:cases (count rows) :certified (count certified)
                                                           :by-reason (into (sorted-map) summary)
                                                           :excluded (filterv #(= :excluded (:status %)) verdicts)}))}
        (into {} (for [[path text] shared] [(str "fixtures" (if (str/starts-with? path "/") path (str "/" path))) text]))
        (into {} (mapcat (fn [[r cid]] (case-files r cid)) certified))))}))

(defn from-table
  "`case-pack` over the table at `path` (its directory holds the attachments)."
  [spec path & [table-opts]]
  (let [dir (.getParentFile (.getCanonicalFile (io/file path)))]
    (case-pack spec (read-table path table-opts)
               (fn [p] (let [f (.getCanonicalFile (io/file dir p))]
                         ;; only files beside or below the table
                         (when (and (.isFile f) (str/starts-with? (.getPath f) (str (.getPath dir) "/")))
                           (slurp f)))))))

(defn write-dir!
  "Write a case pack's bundle files under `dir`."
  [dir {:keys [files]}]
  (doseq [[p text] files]
    (let [f (io/file dir p)] (io/make-parents f) (spit f text)))
  dir)
