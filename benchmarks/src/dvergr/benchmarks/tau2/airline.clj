(ns dvergr.benchmarks.tau2.airline
  "Pure Clojure transcription of tau2-bench's airline domain.

   The FlightDB is an immutable value; every tool is a pure transition
   `(db, arguments) -> {:db db' :result value}` and `respond` renders the
   result exactly as tau2's `Environment.get_response` does. Upstream is
   transcribed literally, including behavior that looks like a bug, because
   grading hashes the final database and agents read every error text:

   - the \"current time\" is fixed at `2024-05-15T15:00:00`, new reservation ids
     are HATHAT/HATHAU/HATHAV, certificates are `certificate_3221322..24`;
   - arguments are only validated where upstream builds a pydantic model
     (`FlightInfo`/`Passenger`/`Payment` from all-dict lists, `Reservation`,
     `Certificate`, `Payment` for update charges); elsewhere raw values flow
     through Python arithmetic and are stored unvalidated (e.g.
     `update_reservation_baggages` stores `total_baggages` as given, and
     `update_reservation_passengers` stores a non-dict list or a string);
   - `search_onestop_flight` computes the next day as `2024-05-{day+1}`
     without zero padding, so overnight connections from days 01-08 find
     nothing;
   - cancelling releases no seats, updating flights books no seats, and an
     unknown cabin only fails when a new flight is looked up;
   - a payment id listed twice in `book_reservation` passes the balance check
     per entry, and a repeated certificate raises `KeyError` after earlier
     deductions were applied (partial effects are kept, as Python mutates in
     place before raising).

   Upstream: sierra-research/tau2-bench `src/tau2/domains/airline/`, checked
   for equivalence by `benchmarks/dev/tau2/airline/oracle_airline.py`."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [dvergr.benchmarks.tau2.airline.pydantic :as pd]
            [dvergr.benchmarks.pyjson :as pj]
            [dvergr.benchmarks.python :as py]
            [dvergr.benchmarks.tau2.retail :as retail]))

;; ---------------------------------------------------------------------------
;; Python helpers

(defn- raise [type msg] (py/raise type msg))

(defn- with-db
  "Run `f`; a Python error escaping it carries `db` as the state upstream
   leaves behind (in-place mutations before the raise)."
  [db f]
  (try
    (f)
    (catch clojure.lang.ExceptionInfo e
      (if (and (py/py-exception? e) (not (contains? (ex-data e) ::db)))
        (throw (ex-info (.getMessage e) (assoc (ex-data e) ::db db)))
        (throw e)))))

(defn- ordered [& kvs] (apply array-map kvs))

(defn- assoc-ordered
  "Python `d[k] = v` preserving insertion order at any size."
  [m k v]
  (if (contains? m k)
    (if (instance? clojure.lang.PersistentArrayMap m)
      (assoc m k v)
      (apply array-map (mapcat (fn [[k' v']] [k' (if (= k' k) v v')]) m)))
    (apply array-map (concat (mapcat identity m) [k v]))))

(defn- dissoc-ordered [m k]
  (apply array-map (mapcat identity (remove #(= k (key %)) m))))

(defn- hashable! [x]
  (when (or (map? x) (sequential? x))
    (raise "TypeError" (str "unhashable type: '" (py/type-name x) "'"))))

(defn- py-contains?
  "Python `key in d` for a dict with string keys."
  [m key]
  (hashable! key)
  (contains? m key))

(defn- py-iter
  "Python iteration of a JSON value: list items, str characters, dict keys."
  [x]
  (cond
    (sequential? x) (seq x)
    (string? x) (map #(String. (Character/toChars (int %))) (iterator-seq (.iterator (.codePoints ^String x))))
    (map? x) (keys x)
    :else (raise "TypeError" (str "'" (py/type-name x) "' object is not iterable"))))

(defn- py-len [x]
  (cond
    (string? x) (.codePointCount ^String x 0 (count x))
    (or (sequential? x) (map? x)) (count x)
    :else (raise "TypeError" (str "object of type '" (py/type-name x) "' has no len()"))))

(defn- num-like? [x] (or (number? x) (boolean? x)))
(defn- numeric [x] (if (boolean? x) (if x 1 0) x))

(defn- py-arith
  "Python `a <op> b` for int/float/bool operands; TypeError otherwise."
  [op-name f a b]
  (if (and (num-like? a) (num-like? b))
    (let [a (numeric a) b (numeric b)]
      (if (or (float? a) (float? b))
        (f (double a) (double b))
        (f a b)))
    (raise "TypeError" (str "unsupported operand type(s) for " op-name ": '"
                            (py/type-name a) "' and '" (py/type-name b) "'"))))

(defn- py-mul-int
  "Python `n * x` for an int `n`: str repetition, numeric product."
  [n x]
  (cond
    (string? x) (apply str (repeat (max 0 n) x))
    (sequential? x) (vec (apply concat (repeat (max 0 n) x)))
    :else (py-arith "*" *' n x)))

(defn- py-add [a b] (py-arith "+=" +' a b))
(defn- py-sub [a b] (py-arith "-" -' a b))

(defn- py-max0
  "Python `max(0, x)`: the first maximal argument, so ties keep int 0."
  [x]
  (if (and (num-like? x) (> (numeric x) 0)) x
      (if (num-like? x) 0
          (raise "TypeError" (str "'>' not supported between instances of '"
                                  (py/type-name x) "' and 'int'")))))

(defn- attr-error [x attr]
  (raise "AttributeError" (str "'" (py/type-name x) "' object has no attribute '" attr "'")))

;; ---------------------------------------------------------------------------
;; Models (pydantic declaration order = model_dump order)

(def ^:private cabin-v (pd/v-literal "business" "economy" "basic_economy"))

(def flight-info-fields [["flight_number" pd/v-str] ["date" pd/v-str]])
(def passenger-fields [["first_name" pd/v-str] ["last_name" pd/v-str] ["dob" pd/v-str]])
(def payment-fields [["payment_id" pd/v-str] ["amount" pd/v-int]])
(def certificate-fields [["source" (pd/v-literal "certificate")] ["id" pd/v-str]
                         ["amount" pd/v-float]])

(def reservation-flight-fields
  [["flight_number" pd/v-str] ["origin" pd/v-str] ["destination" pd/v-str]
   ["date" pd/v-str] ["price" pd/v-int]])

(def reservation-fields
  [["reservation_id" pd/v-str] ["user_id" pd/v-str] ["origin" pd/v-str]
   ["destination" pd/v-str] ["flight_type" (pd/v-literal "round_trip" "one_way")]
   ["cabin" cabin-v]
   ["flights" (pd/v-list (pd/v-model "ReservationFlight" reservation-flight-fields))]
   ["passengers" (pd/v-list (pd/v-model "Passenger" passenger-fields))]
   ["payment_history" (pd/v-list (pd/v-model "Payment" payment-fields))]
   ["created_at" pd/v-str] ["total_baggages" pd/v-int] ["nonfree_baggages" pd/v-int]
   ["insurance" (pd/v-literal "yes" "no")]
   ["status" (pd/v-optional (pd/v-literal "cancelled"))]])

;; ---------------------------------------------------------------------------
;; Database normalization (FlightDB.model_validate + model_dump)

(defn- ->float [x] (when (some? x) (double x)))

(defn- flight-date-status [s]
  (let [f #(apply ordered (mapcat (fn [k] [k (get s k)]) %))]
    (case (get s "status")
      "available" (ordered "status" "available"
                           "available_seats" (apply array-map (mapcat identity (get s "available_seats")))
                           "prices" (apply array-map (mapcat identity (get s "prices"))))
      "landed" (f ["status" "actual_departure_time_est" "actual_arrival_time_est"])
      "cancelled" (f ["status"])
      "delayed" (f ["status" "estimated_departure_time_est" "estimated_arrival_time_est"])
      "flying" (f ["status" "actual_departure_time_est" "estimated_arrival_time_est"])
      "on time" (f ["status" "estimated_departure_time_est" "estimated_arrival_time_est"]))))

(defn- flight [fl]
  (ordered "flight_number" (get fl "flight_number") "origin" (get fl "origin")
           "destination" (get fl "destination")
           "scheduled_departure_time_est" (get fl "scheduled_departure_time_est")
           "scheduled_arrival_time_est" (get fl "scheduled_arrival_time_est")
           "dates" (apply array-map (mapcat (fn [[d s]] [d (flight-date-status s)]) (get fl "dates")))))

(defn- payment-method [pm]
  (case (get pm "source")
    "credit_card" (ordered "source" "credit_card" "id" (get pm "id")
                           "brand" (get pm "brand") "last_four" (get pm "last_four"))
    "gift_card" (ordered "source" "gift_card" "id" (get pm "id") "amount" (->float (get pm "amount")))
    "certificate" (ordered "source" "certificate" "id" (get pm "id") "amount" (->float (get pm "amount")))))

(defn- passenger [p]
  (ordered "first_name" (get p "first_name") "last_name" (get p "last_name") "dob" (get p "dob")))

(defn- user [u]
  (ordered "user_id" (get u "user_id")
           "name" (ordered "first_name" (get-in u ["name" "first_name"])
                           "last_name" (get-in u ["name" "last_name"]))
           "address" (let [a (get u "address")]
                       (ordered "address1" (get a "address1") "address2" (get a "address2")
                                "city" (get a "city") "country" (get a "country")
                                "state" (get a "state") "zip" (get a "zip")))
           "email" (get u "email")
           "dob" (get u "dob")
           "payment_methods" (apply array-map (mapcat (fn [[k pm]] [k (payment-method pm)])
                                                      (get u "payment_methods")))
           "saved_passengers" (mapv passenger (get u "saved_passengers"))
           "membership" (get u "membership")
           "reservations" (vec (get u "reservations"))))

(defn- reservation [r]
  (ordered "reservation_id" (get r "reservation_id") "user_id" (get r "user_id")
           "origin" (get r "origin") "destination" (get r "destination")
           "flight_type" (get r "flight_type") "cabin" (get r "cabin")
           "flights" (mapv #(ordered "flight_number" (get % "flight_number")
                                     "origin" (get % "origin")
                                     "destination" (get % "destination")
                                     "date" (get % "date") "price" (get % "price"))
                           (get r "flights"))
           "passengers" (mapv passenger (get r "passengers"))
           "payment_history" (mapv #(ordered "payment_id" (get % "payment_id")
                                             "amount" (get % "amount"))
                                   (get r "payment_history"))
           "created_at" (get r "created_at")
           "total_baggages" (get r "total_baggages")
           "nonfree_baggages" (get r "nonfree_baggages")
           "insurance" (get r "insurance")
           "status" (get r "status")))

(defn normalize-db
  "Raw `db.json` data into FlightDB model_dump form. `flights` keeps file
   order (searches iterate it); users and reservations are only looked up
   by key and hashed with sorted keys, so they are plain hash maps."
  [raw]
  (ordered "flights" (apply array-map (mapcat (fn [[k f]] [k (flight f)]) (get raw "flights")))
           "users" (into {} (map (fn [[k u]] [k (user u)])) (get raw "users"))
           "reservations" (into {} (map (fn [[k r]] [k (reservation r)])) (get raw "reservations"))))

(defn db-hash
  "tau2 `get_db_hash`: sha256 of `json.dumps(model_dump(), sort_keys=True)`."
  [db]
  (pj/dict-hash db))

;; ---------------------------------------------------------------------------
;; Lookups

(def current-datetime "`AirlineTools._get_datetime`." "2024-05-15T15:00:00")

(defn- get-user [db user-id]
  (if (py-contains? (get db "users") user-id)
    (get-in db ["users" user-id])
    (raise "ValueError" (str "User " (py/py-str user-id) " not found"))))

(defn- get-reservation [db reservation-id]
  (if (py-contains? (get db "reservations") reservation-id)
    (get-in db ["reservations" reservation-id])
    (raise "ValueError" (str "Reservation " (py/py-str reservation-id) " not found"))))

(defn- get-flight [db flight-number]
  (if (py-contains? (get db "flights") flight-number)
    (get-in db ["flights" flight-number])
    (raise "ValueError" (str "Flight " (py/py-str flight-number) " not found"))))

(defn- get-flight-instance [db flight-number date]
  (let [fl (get-flight db flight-number)]
    (if (py-contains? (get fl "dates") date)
      (get-in fl ["dates" date])
      (raise "ValueError" (str "Flight " (py/py-str flight-number) " not found on date "
                               (py/py-str date))))))

(defn- new-reservation-id [db]
  (or (first (remove #(contains? (get db "reservations") %) ["HATHAT" "HATHAU" "HATHAV"]))
      (raise "ValueError" "Too many reservations")))

(defn- convert-all
  "`if all(isinstance(x, dict) for x in xs): xs = [Model(**x) for x in xs]`.
   Returns `{:models [...]}` when converted, else `{:raw xs}`."
  [xs model-name fields]
  (let [items (py-iter xs)]
    (if (every? map? items)
      {:models (mapv #(pd/construct! model-name fields %) items)}
      {:raw xs})))

(defn- model-items
  "Iterate converted models, or raise the AttributeError Python raises on the
   first raw item's attribute access."
  [{:keys [models raw]} attr]
  (if models
    models
    (let [items (py-iter raw)]
      (when (seq items) (attr-error (first items) attr))
      [])))

(defn- value-of [{:keys [models raw] :as c}] (if (contains? c :models) models raw))

;; ---------------------------------------------------------------------------
;; Tools

(defn- search-direct
  "`_search_direct_flight`: DirectFlight dumps, in flight-table order."
  [db date origin destination leave-after]
  (vec (for [[_ fl] (get db "flights")
             :when (and (or (nil? origin) (= (get fl "origin") origin))
                        (or (nil? destination) (= (get fl "destination") destination))
                        (py-contains? (get fl "dates") date)
                        (= "available" (get-in fl ["dates" date "status"]))
                        (or (nil? leave-after)
                            (>= (compare (get fl "scheduled_departure_time_est") leave-after) 0)))]
         (let [s (get-in fl ["dates" date])]
           (ordered "flight_number" (get fl "flight_number") "origin" (get fl "origin")
                    "destination" (get fl "destination") "status" "available"
                    "scheduled_departure_time_est" (get fl "scheduled_departure_time_est")
                    "scheduled_arrival_time_est" (get fl "scheduled_arrival_time_est")
                    "date" nil
                    "available_seats" (get s "available_seats")
                    "prices" (get s "prices"))))))

(defn- search-onestop [db {:strs [origin destination date]}]
  {:db db
   :result (vec (for [r1 (search-direct db date origin nil nil)
                      :let [r1 (assoc r1 "date" date)
                            arrival (get r1 "scheduled_arrival_time_est")
                            date2 (if (str/includes? arrival "+1")
                                    (str "2024-05-" (inc (py/py-int (subs date (max 0 (- (count date) 2))))))
                                    date)]
                      r2 (search-direct db date2 (get r1 "destination") destination arrival)]
                  [r1 (assoc r2 "date" date2)]))})

(defn- payment-for-update
  "`_payment_for_update`: returns `[db payment-or-nil]`; the gift-card
   deduction happens before the Payment is validated (partial effect)."
  [db user payment-id total-price]
  (let [pms (get user "payment_methods")
        uid (get user "user_id")]
    (when-not (py-contains? pms payment-id)
      (raise "ValueError" "Payment method not found"))
    (let [pm (get pms payment-id)]
      (cond
        (= "certificate" (get pm "source"))
        (raise "ValueError" "Certificate cannot be used to update reservation")
        (and (= "gift_card" (get pm "source")) (< (get pm "amount") (numeric total-price)))
        (raise "ValueError" "Gift card balance is not enough"))
      (let [db (if (= "gift_card" (get pm "source"))
                 (update-in db ["users" uid "payment_methods" payment-id "amount"]
                            #(py-arith "-=" - % total-price))
                 db)]
        [db (when-not (zero? (numeric total-price))
              (with-db db #(pd/construct! "Payment" payment-fields
                                          {"payment_id" payment-id "amount" total-price})))]))))

(defn- book-reservation
  [db {:strs [user_id origin destination flight_type cabin flights passengers payment_methods
              total_baggages nonfree_baggages insurance]}]
  (let [flights (convert-all flights "FlightInfo" flight-info-fields)
        passengers (convert-all passengers "Passenger" passenger-fields)
        payments (convert-all payment_methods "Payment" payment-fields)
        u (get-user db user_id)
        rid (new-reservation-id db)
        res (pd/construct! "Reservation" reservation-fields
                           {"reservation_id" rid "user_id" user_id "origin" origin
                            "destination" destination "flight_type" flight_type "cabin" cabin
                            "flights" [] "passengers" (value-of passengers)
                            "payment_history" (value-of payments)
                            "created_at" current-datetime "total_baggages" total_baggages
                            "nonfree_baggages" nonfree_baggages "insurance" insurance}
                           {"status" nil})
        ;; Validation succeeded, so passengers/payments are model lists.
        n (count (get res "passengers"))
        [res-flights seat-keys total]
        (reduce (fn [[rfs seats total] fi]
                  (let [fnum (get fi "flight_number")
                        fl (get-flight db fnum)
                        fdd (get-flight-instance db fnum (get fi "date"))]
                    (when (not= "available" (get fdd "status"))
                      (raise "ValueError" (str "Flight " fnum " not available on date " (get fi "date"))))
                    (when (< (get-in fdd ["available_seats" cabin]) n)
                      (raise "ValueError" (str "Not enough seats on flight " fnum)))
                    (let [price (get-in fdd ["prices" cabin])]
                      [(conj rfs (ordered "flight_number" fnum "origin" (get fl "origin")
                                          "destination" (get fl "destination")
                                          "date" (get fi "date") "price" price))
                       (conj seats [fnum (get fi "date")])
                       (+' total (*' price n))])))
                [[] [] 0]
                (model-items flights "flight_number"))
        total (if (= insurance "yes") (+' total (*' 30 n)) total)
        total (py-add total (py-mul-int 50 nonfree_baggages))
        pms (get u "payment_methods")]
    (doseq [{:strs [payment_id amount]} (get res "payment_history")]
      (when-not (contains? pms payment_id)
        (raise "ValueError" (str "Payment method " payment_id " not found")))
      (let [upm (get pms payment_id)]
        (when (and (#{"gift_card" "certificate"} (get upm "source"))
                   (< (get upm "amount") amount))
          (raise "ValueError" (str "Not enough balance in payment method " payment_id)))))
    (let [total-payment (reduce +' 0 (map #(get % "amount") (get res "payment_history")))]
      (when-not (== total-payment total)
        (raise "ValueError" (str "Payment amount does not add up, total price is "
                                 (py/py-str total) ", but paid " (py/py-str total-payment)))))
    (let [db (reduce (fn [db {:strs [payment_id amount]}]
                       (let [upm (get-in db ["users" user_id "payment_methods" payment_id])]
                         (when-not upm
                           (throw (ex-info "KeyError" {::py/exception "KeyError"
                                                       ::py/message (py/py-repr payment_id)
                                                       ::db db})))
                         (case (get upm "source")
                           "gift_card" (update-in db ["users" user_id "payment_methods" payment_id "amount"]
                                                  #(- % amount))
                           "certificate" (update-in db ["users" user_id "payment_methods"]
                                                    dissoc-ordered payment_id)
                           db)))
                     db
                     (get res "payment_history"))
          db (reduce (fn [db [fnum date]]
                       (update-in db ["flights" fnum "dates" date "available_seats" cabin] - n))
                     db seat-keys)
          res (assoc res "flights" res-flights)]
      {:db (-> db
               (assoc-in ["reservations" rid] res)
               (update-in ["users" user_id "reservations"] conj rid))
       :result res})))

(defn- cancel-reservation [db {:strs [reservation_id]}]
  (let [res (get-reservation db reservation_id)
        refunds (mapv #(ordered "payment_id" (get % "payment_id") "amount" (-' (get % "amount")))
                      (get res "payment_history"))
        res (-> res
                (update "payment_history" into refunds)
                (assoc "status" "cancelled"))]
    {:db (assoc-in db ["reservations" reservation_id] res) :result res}))

(defn- update-reservation-baggages
  [db {:strs [reservation_id total_baggages nonfree_baggages payment_id]}]
  (let [res (get-reservation db reservation_id)
        u (get-user db (get res "user_id"))
        total (py-mul-int 50 (py-max0 (py-sub nonfree_baggages (get res "nonfree_baggages"))))
        [db payment] (payment-for-update db u payment_id total)
        res (cond-> res
              payment (update "payment_history" conj payment)
              true (assoc "total_baggages" total_baggages "nonfree_baggages" nonfree_baggages))]
    {:db (assoc-in db ["reservations" reservation_id] res) :result res}))

(defn- update-reservation-flights [db {:strs [reservation_id cabin flights payment_id]}]
  (let [flights (convert-all flights "FlightInfo" flight-info-fields)
        res (get-reservation db reservation_id)
        u (get-user db (get res "user_id"))
        n-of #(py-len (get res "passengers"))
        [total rfs]
        (reduce
         (fn [[total rfs] fi]
           (let [fnum (get fi "flight_number") date (get fi "date")
                 match (first (filter #(and (= (get % "flight_number") fnum)
                                            (= (get % "date") date)
                                            (py/py-eq cabin (get res "cabin")))
                                      (get res "flights")))]
             (if match
               [(+' total (*' (get match "price") (n-of))) (conj rfs match)]
               (let [fl (get-flight db fnum)
                     fdd (get-flight-instance db fnum date)]
                 (when (not= "available" (get fdd "status"))
                   (raise "ValueError" (str "Flight " fnum " not available on date " date)))
                 (hashable! cabin)
                 (let [seats (get fdd "available_seats")]
                   (when-not (contains? seats cabin)
                     (raise "KeyError" (py/py-repr cabin)))
                   (when (< (get seats cabin) (n-of))
                     (raise "ValueError" (str "Not enough seats on flight " fnum))))
                 (let [price (get-in fdd ["prices" cabin])]
                   [(+' total (*' price (n-of)))
                    (conj rfs (ordered "flight_number" fnum "origin" (get fl "origin")
                                       "destination" (get fl "destination")
                                       "date" date "price" price))])))))
         [0 []]
         (model-items flights "flight_number"))
        total (-' total (*' (reduce +' 0 (map #(get % "price") (get res "flights"))) (n-of)))
        [db payment] (payment-for-update db u payment_id total)
        res (cond-> res
              payment (update "payment_history" conj payment)
              true (assoc "flights" rfs "cabin" cabin))]
    {:db (assoc-in db ["reservations" reservation_id] res) :result res}))

(defn- update-reservation-passengers [db {:strs [reservation_id passengers]}]
  (let [ps (value-of (convert-all passengers "Passenger" passenger-fields))
        res (get-reservation db reservation_id)]
    (when (not= (py-len ps) (py-len (get res "passengers")))
      (raise "ValueError" "Number of passengers does not match"))
    (let [res (assoc res "passengers" ps)]
      {:db (assoc-in db ["reservations" reservation_id] res) :result res})))

(defn- send-certificate [db {:strs [user_id amount]}]
  (let [u (get-user db user_id)
        pms (get u "payment_methods")]
    (or (some (fn [pid]
                (when-not (contains? pms pid)
                  (let [cert (pd/construct! "Certificate" certificate-fields
                                            {"id" pid "amount" amount "source" "certificate"})]
                    {:db (update-in db ["users" user_id "payment_methods"] assoc-ordered pid cert)
                     :result (str "Certificate " pid " added to user " (py/py-str user_id)
                                  " with amount " (py/py-str amount) ".")})))
              (map #(str "certificate_" %) [3221322 3221323 3221324]))
        (raise "ValueError" "Too many certificates"))))

(defn- calculate [expression]
  (cond
    (string? expression) (retail/calculate-expression expression)
    :else (do (py-iter expression)
              (raise "TypeError" "eval() arg 1 must be a string, bytes or code object"))))

(def airports
  (mapv (fn [[iata city]] (ordered "iata" iata "city" city))
        [["SFO" "San Francisco"] ["JFK" "New York"] ["LAX" "Los Angeles"] ["ORD" "Chicago"]
         ["DFW" "Dallas"] ["DEN" "Denver"] ["SEA" "Seattle"] ["ATL" "Atlanta"]
         ["MIA" "Miami"] ["BOS" "Boston"] ["PHX" "Phoenix"] ["IAH" "Houston"]
         ["LAS" "Las Vegas"] ["MCO" "Orlando"] ["EWR" "Newark"] ["CLT" "Charlotte"]
         ["MSP" "Minneapolis"] ["DTW" "Detroit"] ["PHL" "Philadelphia"] ["LGA" "LaGuardia"]]))

(defn- reader [f] (fn [db args] {:db db :result (f db args)}))

(def tools
  "Tool name -> {:params [..] :type :read|:write|:generic :fn (db args) -> {:db :result}}.
   Parameter order is the Python signature order (TypeError text)."
  {"book_reservation" {:params ["user_id" "origin" "destination" "flight_type" "cabin" "flights"
                                "passengers" "payment_methods" "total_baggages" "nonfree_baggages"
                                "insurance"]
                       :type :write :fn book-reservation}
   "calculate" {:params ["expression"] :type :generic
                :fn (reader (fn [_ {:strs [expression]}] (calculate expression)))}
   "cancel_reservation" {:params ["reservation_id"] :type :write :fn cancel-reservation}
   "get_reservation_details" {:params ["reservation_id"] :type :read
                              :fn (reader (fn [db {:strs [reservation_id]}]
                                            (get-reservation db reservation_id)))}
   "get_user_details" {:params ["user_id"] :type :read
                       :fn (reader (fn [db {:strs [user_id]}] (get-user db user_id)))}
   "list_all_airports" {:params [] :type :read :fn (reader (constantly airports))}
   "search_direct_flight" {:params ["origin" "destination" "date"] :type :read
                           :fn (reader (fn [db {:strs [origin destination date]}]
                                         (search-direct db date origin destination nil)))}
   "search_onestop_flight" {:params ["origin" "destination" "date"] :type :read
                            :fn search-onestop}
   "send_certificate" {:params ["user_id" "amount"] :type :write :fn send-certificate}
   "transfer_to_human_agents" {:params ["summary"] :type :generic
                               :fn (reader (constantly "Transfer successful"))}
   "update_reservation_baggages" {:params ["reservation_id" "total_baggages" "nonfree_baggages"
                                           "payment_id"]
                                  :type :write :fn update-reservation-baggages}
   "update_reservation_flights" {:params ["reservation_id" "cabin" "flights" "payment_id"]
                                 :type :write :fn update-reservation-flights}
   "update_reservation_passengers" {:params ["reservation_id" "passengers"] :type :write
                                    :fn update-reservation-passengers}
   "get_flight_status" {:params ["flight_number" "date"] :type :read
                        :fn (reader (fn [db {:strs [flight_number date]}]
                                      (get (get-flight-instance db flight_number date) "status")))}})

(defn- python-list-names [names]
  (let [q (mapv #(str "'" % "'") names)]
    (case (count q)
      1 (first q)
      2 (str (q 0) " and " (q 1))
      (str (str/join ", " (butlast q)) ", and " (last q)))))

(defn- check-arguments! [tool-name params args]
  (when-let [unexpected (first (remove (set params) (keys args)))]
    (raise "TypeError" (str "AirlineTools." tool-name "() got an unexpected keyword argument '"
                            unexpected "'")))
  (let [missing (remove #(contains? args %) params)]
    (when (seq missing)
      (raise "TypeError" (str "AirlineTools." tool-name "() missing " (count missing)
                              " required positional argument" (when (> (count missing) 1) "s")
                              ": " (python-list-names missing))))))

(defn invoke
  "Apply one tool call: `{:db db' :result value}`, or throw a Python error."
  [db tool-name args]
  (let [{:keys [params fn]} (or (get tools tool-name)
                                (raise "ValueError" (str "Tool '" (py/py-str tool-name) "' not found.")))
        args (or args {})]
    (check-arguments! tool-name params args)
    (with-db db #(fn db args))))

(defn tool-content
  "tau2 `Environment.to_json_str`: strings as is, everything else
   `json.dumps` of the model dump."
  [result]
  (if (string? result) result (pj/dumps result)))

(defn- error-message [e]
  (if (py/py-exception? e) (py/exception-message e) (retail/py-message e)))

(defn respond
  "tau2 `Environment.get_response` for the assistant: never throws for
   Python errors. `{:db db' :content string :error boolean}`."
  [db tool-name args]
  (try
    (let [{db' :db result :result} (invoke db tool-name args)]
      {:db db' :content (tool-content result) :error false})
    (catch clojure.lang.ExceptionInfo e
      (if (or (py/py-exception? e) (contains? (ex-data e) ::retail/py-message))
        {:db (get (ex-data e) ::db db) :content (str "Error: " (error-message e)) :error true}
        (throw e)))))

;; ---------------------------------------------------------------------------
;; Domain loading

(def file-digests
  "SHA-256 of the upstream data files at `dvergr.benchmarks.tau2.core/upstream`."
  {"db.json" "1af9fea6e03ca7ca15a22bb3fcaf3e351393e3fc9070b6777947da8996f7531b"
   "tasks.json" "ccd8ba737b4cc371415af70151187788f728d6108d0916e73bb4317b40542052"
   "policy.md" "10dc0525421521208be39cee235bba84a16e2bcba9899eb93d92cd81d2f62fc4"
   "split_tasks.json" "b22ced4d9a9850ac9aea31c53bdcb6d6009058140bd9acc7db37c1d36222ba8b"
   :user-guidelines "740a29dfa64d7bc08eea3bf7493575b914a63f744acbaf7f199ee07eddaf72d3"
   :user-guidelines-tools "cbf3d8a4d8642fd04e559862f1afef55d7dd4e6a7e727ca49e239023c599de0c"})

(defn- verified-slurp [path expected]
  (let [text (slurp path)
        digest (pj/sha256-hex text)]
    (when (not= digest expected)
      (throw (ex-info "tau2 data file does not match the pinned upstream revision"
                      {:type :dvergr.benchmarks.tau2.core/data-digest-mismatch
                       :path (str path) :expected expected :actual digest})))
    text))

(defn- respond-world
  "Domain `:respond`: airline has no user tools, so user calls raise."
  [world requestor name args]
  (if (= requestor :user)
    {:world world :content "Error: User tools not available" :error true}
    (let [{d :db :keys [content error]} (respond world name args)]
      {:world d :content content :error error})))

(defn load-airline
  "The airline domain map (same keys as retail's) from a pinned tau2-bench
   checkout at `root`."
  [root]
  (let [dir (io/file root "data/tau2/domains/airline")
        read-text #(verified-slurp (io/file dir %) (get file-digests %))
        guidelines #(verified-slurp (io/file root "data/tau2/user_simulator" %1) (get file-digests %2))
        db (normalize-db (pj/parse (read-text "db.json")))]
    {:domain "airline"
     :db db
     :initial-db-hash (db-hash db)
     ;; Airline tasks carry no initial state.
     :initial-world (fn [_task] db)
     :respond respond-world
     :world-hash db-hash
     :policy (read-text "policy.md")
     :tasks (into (array-map) (map (juxt #(get % "id") identity)) (pj/parse (read-text "tasks.json")))
     :splits (pj/parse (read-text "split_tasks.json"))
     :tool-schemas (pj/parse (slurp (io/resource "benchmarks/tau2/airline-tools.json")))
     :user-tool-schemas (constantly nil)
     :user-guidelines (guidelines "simulation_guidelines.md" :user-guidelines)
     :user-guidelines-tools (guidelines "simulation_guidelines_tools.md" :user-guidelines-tools)}))
