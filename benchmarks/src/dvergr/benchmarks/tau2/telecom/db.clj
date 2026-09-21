(ns dvergr.benchmarks.tau2.telecom.db
  "Data layer of the tau2 `telecom` transcription: a minimal TOML reader for
   `db.toml`/`user_db.toml`, the pydantic models as ordered maps (field
   declaration order, defaults filled, exactly what `model_dump()` yields),
   and the two database hashes.

   Value conventions (so `json.dumps(model_dump(), default=str)` renders
   byte-identically): enums are their string values, `date` fields are
   `YYYY-MM-DD` strings and `datetime` fields `YYYY-MM-DD HH:MM:SS` strings
   (Python `str()` of the value), floats are doubles."
  (:require [clojure.string :as str]
            [dvergr.benchmarks.pyjson :as pj]
            [dvergr.benchmarks.python :as py]))

;; ---------------------------------------------------------------------------
;; Ordered maps

(defn oassoc
  "assoc that keeps insertion order for any size (array maps only stay
   ordered while assoc replaces existing keys)."
  [m k v]
  (if (contains? m k)
    (assoc m k v)
    (apply array-map (concat (mapcat identity m) [k v]))))

;; ---------------------------------------------------------------------------
;; Minimal TOML (the subset tau2's telecom data uses)

(defn- parse-string [^String s i]
  ;; s[i] is the opening quote; returns [value next-index]
  (let [sb (StringBuilder.)]
    (loop [j (inc i)]
      (let [c (.charAt s j)]
        (cond
          (= c \") [(.toString sb) (inc j)]
          (= c \\) (let [e (.charAt s (inc j))]
                     (case e
                       \u (do (.append sb (char (Integer/parseInt (subs s (+ j 2) (+ j 6)) 16)))
                              (recur (+ j 6)))
                       (do (.append sb (case e \n \newline \t \tab \r \return \" \" \\ \\ e))
                           (recur (+ j 2)))))
          :else (do (.append sb c) (recur (inc j))))))))

(defn- parse-value [^String s]
  (let [s (str/trim s)]
    (cond
      (str/starts-with? s "\"") (first (parse-string s 0))
      (= s "true") true
      (= s "false") false
      (str/starts-with? s "[")
      (let [inner (str/trim (subs s 1 (dec (count s))))]
        (if (str/blank? inner)
          []
          (loop [i 0 acc []]
            (let [i (loop [i i] (if (and (< i (count inner)) (#{\space \, \tab} (.charAt inner i))) (recur (inc i)) i))]
              (if (>= i (count inner))
                acc
                (let [[v j] (parse-string inner i)] (recur j (conj acc v))))))))
      (re-matches #"[+-]?\d+" s) (Long/parseLong s)
      :else (Double/parseDouble s))))

(defn- o-assoc-in [doc path k v]
  (if (empty? path)
    (oassoc doc k v)
    (let [p (first path)]
      (if (vector? doc)
        (assoc doc p (o-assoc-in (nth doc p) (rest path) k v))
        (oassoc doc p (o-assoc-in (get doc p) (rest path) k v))))))

(defn- node-at [doc path] (reduce (fn [d p] (if (vector? d) (nth d p) (get d p))) doc path))

(defn- set-at [doc path v]
  (let [path (vec path)]
    (o-assoc-in doc (pop path) (peek path) v)))

(defn- resolve-table
  "Header `[a.b]` / `[[a.b]]`: returns `[doc path]` of the table to fill."
  [doc segs array?]
  (loop [doc doc path [] [seg & more] segs]
    (let [node (get (node-at doc path) seg)
          last? (empty? more)]
      (cond
        (and last? array?)
        (let [arr (or node [])
              doc (set-at doc (conj path seg) (conj arr (array-map)))]
          [doc (conj path seg (count arr))])
        (vector? node) (recur doc (conj path seg (dec (count node))) more)
        (map? node) (if last? [doc (conj path seg)] (recur doc (conj path seg) more))
        :else (let [doc (set-at doc (conj path seg) (array-map))]
                (if last? [doc (conj path seg)] (recur doc (conj path seg) more)))))))

(defn parse-toml
  "Parse the TOML subset of tau2's telecom data files into ordered maps."
  [text]
  (loop [[line & more] (str/split-lines text) doc (array-map) path []]
    (if (nil? line)
      doc
      (let [t (str/trim line)]
        (cond
          (or (str/blank? t) (str/starts-with? t "#")) (recur more doc path)
          (str/starts-with? t "[[")
          (let [[doc path] (resolve-table doc (str/split (subs t 2 (- (count t) 2)) #"\.") true)]
            (recur more doc path))
          (str/starts-with? t "[")
          (let [[doc path] (resolve-table doc (str/split (subs t 1 (dec (count t))) #"\.") false)]
            (recur more doc path))
          :else
          (let [i (str/index-of t "=")
                k (str/trim (subs t 0 i))
                v (parse-value (subs t (inc i)))]
            (recur more (o-assoc-in doc path k v) path)))))))

;; ---------------------------------------------------------------------------
;; Models: [field default coerce]; ::required marks fields without default

(defn- datetime-str
  "pydantic parses `2025-01-20T14:30:00`; `str(datetime)` renders it with a space."
  [s]
  (when (some? s) (str/replace s "T" " ")))

(defn- as-float [x] (when (some? x) (double x)))

(declare model)

(def address-fields [["street" ::required] ["city" ::required] ["state" ::required] ["zip_code" ::required]])

(def plan-fields
  [["plan_id" ::required] ["name" ::required] ["data_limit_gb" ::required as-float]
   ["price_per_month" ::required as-float] ["data_refueling_price_per_gb" ::required as-float]])

(def device-fields
  [["device_id" ::required] ["device_type" ::required] ["model" ::required] ["imei" nil]
   ["is_esim_capable" ::required] ["activated" false] ["activation_date" nil datetime-str]
   ["last_esim_transfer_date" nil datetime-str]])

(def line-fields
  [["line_id" ::required] ["phone_number" ::required] ["status" "Pending Activation"]
   ["plan_id" ::required] ["device_id" nil] ["data_used_gb" 0.0 as-float]
   ["data_refueling_gb" 0.0 as-float] ["roaming_enabled" false] ["contract_end_date" nil]
   ["last_plan_change_date" nil] ["last_sim_replacement_date" nil] ["suspension_start_date" nil]])

(def line-item-fields
  [["description" ::required] ["amount" ::required as-float] ["date" ::required] ["item_type" ::required]])

(def bill-fields
  [["bill_id" ::required] ["customer_id" ::required] ["period_start" ::required]
   ["period_end" ::required] ["issue_date" ::required] ["total_due" ::required as-float]
   ["due_date" ::required] ["line_items" [] #(mapv (partial model line-item-fields) %)]
   ["status" "Draft"]])

(def payment-method-fields
  [["method_type" ::required] ["account_number_last_4" ::required] ["expiration_date" ::required]])

(def customer-fields
  [["customer_id" ::required] ["full_name" ::required] ["date_of_birth" ::required]
   ["email" ::required] ["phone_number" ::required]
   ["address" ::required #(model address-fields %)]
   ["account_status" "Pending Verification"]
   ["payment_methods" [] #(mapv (partial model payment-method-fields) %)]
   ["line_ids" []] ["bill_ids" []]
   ;; DEFAULT_START_DATE is a `date` although the field is a datetime.
   ["created_at" "2025-01-01" datetime-str]
   ["last_extension_date" nil] ["goodwill_credit_used_this_year" 0.0 as-float]])

(defn model
  "Build an ordered model map from parsed data: declaration order, defaults
   filled, values coerced like pydantic's validation of the data files."
  [fields data]
  (apply array-map
         (mapcat (fn [[k default coerce]]
                   (let [present (contains? data k)]
                     (when (and (not present) (= ::required default))
                       (throw (ex-info "Missing required field" {:field k :data data})))
                     [k (let [v (if present (get data k) default)]
                          (if (and coerce present) (coerce v) v))]))
                 fields)))

(defn agent-db
  "TelecomDB from parsed `db.toml`."
  [doc]
  (array-map
   "plans" (mapv (partial model plan-fields) (get doc "plans" []))
   "customers" (mapv (partial model customer-fields) (get doc "customers" []))
   "lines" (mapv (partial model line-fields) (get doc "lines" []))
   "bills" (mapv (partial model bill-fields) (get doc "bills" []))
   "devices" (mapv (partial model device-fields) (get doc "devices" []))))

;; --- user device -----------------------------------------------------------

(def apn-fields
  [["apn_name" "internet"] ["reset_at_reboot" false] ["mms_apn" "mms"]
   ["mmsc_url" "http://mms.carrier.com/mms/wapenc"] ["mms_proxy" nil] ["mms_port" nil]])

(def default-apn (model apn-fields {}))

(def permission-fields [["sms" false] ["storage" false] ["phone" false] ["network" false]])

(defn- app-status [data]
  (array-map "app_name" (get data "app_name")
             "permissions" (model permission-fields (get data "permissions" {}))))

(def device-state-fields
  [["sim_card_status" "active"] ["sim_card_missing" false] ["airplane_mode" false]
   ["network_signal_strength" "good"] ["network_technology_connected" "5G"]
   ["network_connection_status" "connected"] ["battery_level" 80] ["data_enabled" true]
   ["roaming_enabled" false] ["network_mode_preference" "4g_5g_preferred"]
   ["active_apn_settings" default-apn #(model apn-fields %)]
   ["wifi_enabled" false] ["wifi_connected" false] ["wifi_ssid" nil]
   ["wifi_signal_strength" "none"] ["wifi_calling_enabled" false]
   ["wifi_calling_mms_over_wifi" false] ["data_saver_mode" false]
   ["vpn_enabled_setting" false] ["vpn_connected" false] ["vpn_details" nil]
   ["app_statuses"
    (array-map "messaging" (app-status {"app_name" "messaging"
                                        "permissions" {"sms" true "storage" true "phone" true}})
               "browser" (app-status {"app_name" "browser"
                                      "permissions" {"network" true "storage" true}}))
    #(apply array-map (mapcat (fn [[k v]] [k (app-status v)]) %))]])

(def surroundings-fields
  [["name" nil] ["phone_number" nil] ["is_abroad" false] ["roaming_allowed" false]
   ["signal_strength" (array-map "2G" "poor" "3G" "fair" "4G" "good" "5G" "excellent")]
   ["mobile_data_usage_exceeded" false] ["line_active" true] ["payment_request" nil]])

(defn user-db
  "TelecomUserDB from parsed `user_db.toml`."
  [doc]
  (array-map "device" (model device-state-fields (get doc "device" {}))
             "surroundings" (model surroundings-fields (get doc "surroundings" {}))))

;; ---------------------------------------------------------------------------
;; Hashes (`get_dict_hash(db.model_dump())`)

(defn db-hash [db] (pj/dict-hash db))

;; ---------------------------------------------------------------------------
;; Python `json.dumps(..., default=str)` of tool results

(defn dumps
  "Python `json.dumps(x, default=str)` for model dumps / processed results."
  [x]
  (pj/dumps x))

(defn py-process
  "`Environment.to_json_str`'s `_process` for non-model values: numbers and
   bools become `str(x)`, containers recurse (models are dumped as they are)."
  [x]
  (cond
    (nil? x) nil
    (string? x) x
    (or (boolean? x) (number? x)) (py/py-str x)
    (map? x) (apply array-map (mapcat (fn [[k v]] [k (py-process v)]) x))
    (sequential? x) (mapv py-process x)
    :else x))
