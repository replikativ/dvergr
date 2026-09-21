(ns dvergr.benchmarks.tau2.telecom.agent
  "The agent side of tau2 `telecom`: `TelecomTools` over the TelecomDB value.

   Every function takes the whole world `{:db :user :vpn-performance
   :bill-seq}` and returns `{:world w :result r}` where `r` is already the
   string `Environment.to_json_str` produces. Python exceptions are raised
   with `dvergr.benchmarks.python/raise` (the environment renders them as
   `Error: <message>`); `raise-w` carries a partially mutated world, like an
   upstream method that mutates before it raises.

   Upstream: `src/tau2/domains/telecom/tools.py`. Deliberate deviation: new
   draft bills are named `B%08x` from a per-world counter instead of
   `B{uuid4().hex[:8]}` (the oracle pins uuid4 to the same counter)."
  (:require [clojure.string :as str]
            [dvergr.benchmarks.python :as py]
            [dvergr.benchmarks.tau2.telecom.db :as tdb]))

(def today "2025-02-25")

(defn raise-w
  "Raise a Python exception after in-place mutations of `world`."
  [world type message]
  (throw (ex-info message {::py/exception type ::py/message message ::world world})))

(defn attribute-error [x attr]
  (py/raise "AttributeError" (str "'" (py/type-name x) "' object has no attribute '" attr "'")))

;; ---------------------------------------------------------------------------
;; Lookups (raise ValueError like the helpers)

(defn- index-of [coll k v]
  (first (keep-indexed (fn [i m] (when (py/py-eq (get m k) v) i)) coll)))

(defn customer-index [db customer-id]
  (or (index-of (get db "customers") "customer_id" customer-id)
      (py/raise "ValueError" (str "Customer with ID " (py/py-str customer-id) " not found"))))

(defn line-index [db line-id]
  (or (index-of (get db "lines") "line_id" line-id)
      (py/raise "ValueError" (str "Line with ID " (py/py-str line-id) " not found"))))

(defn line-index-by-phone [db phone]
  (or (index-of (get db "lines") "phone_number" phone)
      (py/raise "ValueError" (str "Line with phone number " (py/py-str phone) " not found"))))

(defn plan [db plan-id]
  (or (some #(when (py/py-eq (get % "plan_id") plan-id) %) (get db "plans"))
      (py/raise "ValueError" (str "Plan with ID " (py/py-str plan-id) " not found"))))

(defn bill-index [db bill-id]
  (or (index-of (get db "bills") "bill_id" bill-id)
      (py/raise "ValueError" (str "Bill with ID " (py/py-str bill-id) " not found"))))

(defn- device [db device-id]
  (or (some #(when (py/py-eq (get % "device_id") device-id) %) (get db "devices"))
      (py/raise "ValueError" (str "Device with ID " (py/py-str device-id) " not found"))))

(defn customer-by-phone
  "`get_customer_by_phone`: primary number first, then each owned line."
  [db phone]
  (or (some (fn [c]
              (if (py/py-eq (get c "phone_number") phone)
                c
                (some (fn [lid]
                        (let [line (get-in db ["lines" (line-index db lid)])]
                          (when (py/py-eq (get line "phone_number") phone) c)))
                      (get c "line_ids"))))
            (get db "customers"))
      (py/raise "ValueError" (str "Customer with phone number " (py/py-str phone) " not found"))))

(defn target-line-index
  "`_get_target_line`: the customer must own the line."
  [db customer-id line-id]
  (let [c (get-in db ["customers" (customer-index db customer-id)])]
    (when-not (py/py-in line-id (get c "line_ids"))
      (py/raise "ValueError" (str "Line " (py/py-str line-id) " not found for customer "
                                  (py/py-str customer-id))))
    (line-index db line-id)))

(defn bills-awaiting-payment [db customer]
  (vec (keep (fn [bid]
               (let [b (get-in db ["bills" (bill-index db bid)])]
                 (when (= "Awaiting Payment" (get b "status")) b)))
             (get customer "bill_ids"))))

(defn- lower [x attr-owner]
  (if (string? x) (.toLowerCase ^String x java.util.Locale/ROOT) (attribute-error x attr-owner)))

;; ---------------------------------------------------------------------------
;; Tools

(defn- ok [world result] {:world world :result result})

(defn get-customer-by-phone [w {:strs [phone_number]}]
  (ok w (tdb/dumps (customer-by-phone (:db w) phone_number))))

(defn get-customer-by-id [w {:strs [customer_id]}]
  (ok w (tdb/dumps (get-in w [:db "customers" (customer-index (:db w) customer_id)]))))

(defn get-customer-by-name [w {:strs [full_name dob]}]
  (ok w (tdb/dumps
         (filterv (fn [c] (and (= (lower (get c "full_name") "lower") (lower full_name "lower"))
                               (py/py-eq (get c "date_of_birth") dob)))
                  (get-in w [:db "customers"])))))

(defn get-details-by-id [w {:strs [id]}]
  (let [db (:db w)
        starts? (fn [p] (if (string? id) (str/starts-with? id p) (attribute-error id "startswith")))]
    (ok w (tdb/dumps
           (cond
             (starts? "L") (get-in db ["lines" (line-index db id)])
             (starts? "D") (device db id)
             (starts? "B") (get-in db ["bills" (bill-index db id)])
             (starts? "C") (get-in db ["customers" (customer-index db id)])
             (starts? "P") (plan db id)
             :else (py/raise "ValueError" (str "Unknown ID format or type: " id)))))))

(defn suspend-line [w {:strs [customer_id line_id]}]
  (let [i (target-line-index (:db w) customer_id line_id)]
    (when (not= "Active" (get-in w [:db "lines" i "status"]))
      (py/raise "ValueError" "Line must be active to suspend"))
    (let [w (update-in w [:db "lines" i] assoc "status" "Suspended" "suspension_start_date" today)]
      (ok w (tdb/dumps (array-map "message" "Line suspended successfully. $5/month holding fee will apply."
                                  "line" (get-in w [:db "lines" i])))))))

(defn resume-line [w {:strs [customer_id line_id]}]
  (let [i (target-line-index (:db w) customer_id line_id)]
    (when-not (#{"Suspended" "Pending Activation"} (get-in w [:db "lines" i "status"]))
      (py/raise "ValueError" "Line must be suspended to resume"))
    (let [w (update-in w [:db "lines" i] assoc "status" "Active" "suspension_start_date" nil)]
      (ok w (tdb/dumps (array-map "message" "Line resumed successfully"
                                  "line" (get-in w [:db "lines" i])))))))

(defn- py-slice-stop
  "`xs[:limit]` with Python's index rules."
  [xs limit]
  (let [n (count xs)]
    (cond
      (nil? limit) xs
      (or (integer? limit) (boolean? limit))
      (let [k (if (boolean? limit) (if limit 1 0) (long limit))
            k (if (neg? k) (max 0 (+ n k)) (min n k))]
        (subvec xs 0 k))
      :else (py/raise "TypeError" "slice indices must be integers or None or have an __index__ method"))))

(defn get-bills-for-customer [w {:strs [customer_id limit]}]
  (let [db (:db w)
        c (get-in db ["customers" (customer-index db customer_id)])
        bills (mapv #(get-in db ["bills" (bill-index db %)]) (get c "bill_ids"))
        ;; sorted(..., reverse=True) is stable: ties keep their order.
        sorted-bills (vec (sort-by #(get % "issue_date") #(compare %2 %1) bills))]
    (ok w (tdb/dumps (py-slice-stop sorted-bills limit)))))

(defn send-payment-request [w {:strs [customer_id bill_id]}]
  (let [db (:db w)
        c (get-in db ["customers" (customer-index db customer_id)])]
    (when (seq (bills-awaiting-payment db c))
      (py/raise "ValueError" "A bill is already awaiting payment for this customer"))
    (when-not (py/py-in bill_id (get c "bill_ids"))
      (py/raise "ValueError" (str "Bill " (py/py-str bill_id) " not found for customer "
                                  (py/py-str customer_id))))
    (let [i (bill-index db bill_id)
          w (assoc-in w [:db "bills" i "status"] "Awaiting Payment")]
      (ok w (str "Payment request sent to the customer for bill " (get-in w [:db "bills" i "bill_id"]))))))

(defn set-bill-paid [db bill-id]
  (assoc-in db ["bills" (bill-index db bill-id) "status"] "Paid"))

(defn- py-add
  "Python `a + b` for numbers (bool as int)."
  [a b]
  (let [n #(if (boolean? %) (if % 1 0) %)]
    (if (and (number? (n a)) (number? (n b)))
      (+ (n a) (n b))
      (py/raise "TypeError" (str "unsupported operand type(s) for +: '" (py/type-name a)
                                 "' and '" (py/type-name b) "'")))))

(defn- apply-one-time-charge
  "`_apply_one_time_charge`: add a LineItem to the customer's first draft bill,
   creating the next cycle's draft bill when there is none."
  [w customer-id amount description]
  (let [db (:db w)
        ci (customer-index db customer-id)
        draft (some (fn [bid] (let [i (bill-index db bid)]
                                (when (= "Draft" (get-in db ["bills" i "status"])) i)))
                    (get-in db ["customers" ci "bill_ids"]))
        [w bi] (if draft
                 [w draft]
                 (let [n (inc (:bill-seq w 0))
                       id (format "B%08x" n)
                       bill (tdb/model tdb/bill-fields
                                       {"bill_id" id "customer_id" customer-id
                                        "period_start" "2025-03-01" "period_end" "2025-03-31"
                                        "issue_date" "2025-03-01" "total_due" 0
                                        "due_date" "2025-03-15" "status" "Draft"})]
                   [(-> w
                        (assoc :bill-seq n)
                        (update-in [:db "bills"] conj bill)
                        (update-in [:db "customers" ci "bill_ids"] conj id))
                    (count (get-in w [:db "bills"]))]))
        item (array-map "description" description "amount" (double amount) "date" today
                        "item_type" (if (neg? amount) "Credit" "Charge"))]
    (-> w
        (update-in [:db "bills" bi "line_items"] conj item)
        (update-in [:db "bills" bi "total_due"] py-add amount))))

(defn get-data-usage [w {:strs [customer_id line_id]}]
  (let [db (:db w)
        line (get-in db ["lines" (target-line-index db customer_id line_id)])
        p (plan db (get line "plan_id"))]
    (ok w (tdb/dumps (tdb/py-process
                      (array-map "line_id" line_id
                                 "data_used_gb" (get line "data_used_gb")
                                 "data_limit_gb" (get p "data_limit_gb")
                                 "data_refueling_gb" (get line "data_refueling_gb")
                                 "cycle_end_date" "2025-02-28"))))))

(defn enable-roaming [w {:strs [customer_id line_id]}]
  (let [i (target-line-index (:db w) customer_id line_id)]
    (if (py/truthy? (get-in w [:db "lines" i "roaming_enabled"]))
      (ok w "Roaming was already enabled")
      (ok (assoc-in w [:db "lines" i "roaming_enabled"] true) "Roaming enabled successfully"))))

(defn disable-roaming [w {:strs [customer_id line_id]}]
  (let [i (target-line-index (:db w) customer_id line_id)]
    (if-not (py/truthy? (get-in w [:db "lines" i "roaming_enabled"]))
      (ok w "Roaming was already disabled")
      (ok (assoc-in w [:db "lines" i "roaming_enabled"] false) "Roaming disabled successfully"))))

(defn transfer-to-human-agents [w _] (ok w "Transfer successful"))

(defn- py-mul [a b]
  (let [n #(if (boolean? %) (if % 1 0) %)] (* (n a) (n b))))

(defn refuel-data [w {:strs [customer_id line_id gb_amount]}]
  (let [i (target-line-index (:db w) customer_id line_id)]
    (when (py/py-compare :le gb_amount 0)
      (py/raise "ValueError" "Refuel amount must be positive"))
    (let [price (get (plan (:db w) (get-in w [:db "lines" i "plan_id"])) "data_refueling_price_per_gb")
          charge (py-mul gb_amount price)
          w (update-in w [:db "lines" i "data_refueling_gb"] py-add gb_amount)
          w (apply-one-time-charge w customer_id charge
                                   (str "Data refueling: " (py/py-str gb_amount) " GB at $"
                                        (py/py-str price) "/GB"))]
      (ok w (tdb/dumps
             (tdb/py-process
              (array-map "message" (str "Successfully added " (py/py-str gb_amount) " GB of data for line "
                                        (py/py-str line_id) " for $" (py/format-fixed charge 2))
                         "new_data_refueling_gb" (get-in w [:db "lines" i "data_refueling_gb"])
                         "charge" charge)))))))

;; ---------------------------------------------------------------------------
;; Initialization-only functions and assertions (EnvFunctionCall targets)

(defn set-data-usage [w {:strs [customer_id line_id data_used_gb]}]
  (let [i (target-line-index (:db w) customer_id line_id)]
    (ok (assoc-in w [:db "lines" i "data_used_gb"] data_used_gb)
        (str "Data usage set to " (py/py-str data_used_gb) " GB for line " (py/py-str line_id)))))

(defn suspend-line-for-overdue-bill [w {:strs [customer_id line_id new_bill_id contract_ended]}]
  (let [db (:db w)
        li (line-index db line_id)
        line (get-in db ["lines" li])
        _ (when (not= "Active" (get line "status"))
            (py/raise "ValueError" "Line must be active to suspend for unpaid bill"))
        amount (get (plan db (get line "plan_id")) "price_per_month")
        _ (when (<= amount 0) (py/raise "ValueError" "Amount must be positive for overdue bill"))
        ci (customer-index db customer_id)
        _ (when (some #(= "Overdue" (get-in db ["bills" (bill-index db %) "status"]))
                      (get-in db ["customers" ci "bill_ids"]))
            (py/raise "ValueError" "Customer already has an overdue bill"))
        bill (tdb/model tdb/bill-fields
                        {"bill_id" new_bill_id "customer_id" customer_id
                         "period_start" "2025-01-01" "period_end" "2025-01-31"
                         "issue_date" "2025-01-01" "total_due" (+ 0.0 amount)
                         "due_date" "2025-01-15" "status" "Overdue"
                         "line_items" [{"description" (str "Charge for line " (get line "line_id"))
                                        "amount" amount "date" today "item_type" "Charge"}]})
        w (-> w
              (update-in [:db "bills"] conj bill)
              (update-in [:db "customers" ci "bill_ids"] conj new_bill_id)
              (update-in [:db "lines" li] assoc "status" "Suspended" "suspension_start_date" today)
              (cond-> (py/truthy? contract_ended)
                (assoc-in [:db "lines" li "contract_end_date"] "2025-01-31")))]
    (ok w (str "Line " (py/py-str line_id) " suspended for unpaid bill " (py/py-str new_bill_id)
               ". Contract ended: " (py/py-str contract_ended)))))

(defn assert-data-refueling-amount [w {:strs [customer_id line_id expected_amount]}]
  (let [i (target-line-index (:db w) customer_id line_id)]
    (ok w (< (Math/abs (double (- (get-in w [:db "lines" i "data_refueling_gb"]) expected_amount))) 1e-6))))

(defn assert-line-status [w {:strs [customer_id line_id expected_status]}]
  (let [i (target-line-index (:db w) customer_id line_id)]
    (ok w (py/py-eq (get-in w [:db "lines" i "status"]) expected_status))))

(defn assert-overdue-bill-exists [w {:strs [customer_id overdue_bill_id]}]
  (let [db (:db w)
        c (get-in db ["customers" (customer-index db customer_id)])]
    (when-not (py/py-in overdue_bill_id (get c "bill_ids"))
      (py/raise "ValueError" (str "Overdue bill " (py/py-str overdue_bill_id) " not found")))
    (when (not= "Overdue" (get-in db ["bills" (bill-index db overdue_bill_id) "status"]))
      (py/raise "ValueError" (str "Overdue bill " (py/py-str overdue_bill_id) " is not overdue")))
    (ok w true)))

(defn assert-no-overdue-bill [w {:strs [overdue_bill_id]}]
  (let [i (index-of (get-in w [:db "bills"]) "bill_id" overdue_bill_id)]
    (ok w (or (nil? i) (= "Paid" (get-in w [:db "bills" i "status"]))))))

;; ---------------------------------------------------------------------------
;; Registry: name -> {:params [[p] [p default]] :type :fn :tool?}

(def functions
  {"get_customer_by_phone" {:tool? true :type :read :params [["phone_number"]] :fn get-customer-by-phone}
   "get_customer_by_id" {:tool? true :type :read :params [["customer_id"]] :fn get-customer-by-id}
   "get_customer_by_name" {:tool? true :type :read :params [["full_name"] ["dob"]] :fn get-customer-by-name}
   "get_details_by_id" {:tool? true :type :read :params [["id"]] :fn get-details-by-id}
   "suspend_line" {:tool? true :type :write :params [["customer_id"] ["line_id"] ["reason"]] :fn suspend-line}
   "resume_line" {:tool? true :type :write :params [["customer_id"] ["line_id"]] :fn resume-line}
   "get_bills_for_customer" {:tool? true :type :read :params [["customer_id"] ["limit" 12]]
                             :fn get-bills-for-customer}
   "send_payment_request" {:tool? true :type :write :params [["customer_id"] ["bill_id"]]
                           :fn send-payment-request}
   "get_data_usage" {:tool? true :type :read :params [["customer_id"] ["line_id"]] :fn get-data-usage}
   "enable_roaming" {:tool? true :type :write :params [["customer_id"] ["line_id"]] :fn enable-roaming}
   "disable_roaming" {:tool? true :type :write :params [["customer_id"] ["line_id"]] :fn disable-roaming}
   "transfer_to_human_agents" {:tool? true :type :generic :params [["summary"]] :fn transfer-to-human-agents}
   "refuel_data" {:tool? true :type :write :params [["customer_id"] ["line_id"] ["gb_amount"]] :fn refuel-data}
   "set_data_usage" {:params [["customer_id"] ["line_id"] ["data_used_gb"]] :fn set-data-usage}
   "suspend_line_for_overdue_bill" {:params [["customer_id"] ["line_id"] ["new_bill_id"] ["contract_ended"]]
                                    :fn suspend-line-for-overdue-bill}
   "assert_data_refueling_amount" {:params [["customer_id"] ["line_id"] ["expected_amount"]]
                                   :fn assert-data-refueling-amount}
   "assert_line_status" {:params [["customer_id"] ["line_id"] ["expected_status"]] :fn assert-line-status}
   "assert_overdue_bill_exists" {:params [["customer_id"] ["overdue_bill_id"]] :fn assert-overdue-bill-exists}
   "assert_no_overdue_bill" {:params [["overdue_bill_id"]] :fn assert-no-overdue-bill}})
