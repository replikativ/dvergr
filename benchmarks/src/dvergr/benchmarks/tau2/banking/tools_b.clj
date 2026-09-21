(ns dvergr.benchmarks.tau2.banking.tools-b
  "tau2 `banking_knowledge` agent-discoverable tools, part B: credit limit
   increases, payment history, bank account open/close/transfer/credit,
   interest discrepancy reports, and bank account transaction history.

   Byte-faithful transcription of `KnowledgeTools` methods from
   `submit_credit_limit_increase_request_7392` through
   `get_bank_account_transactions_9173` in upstream
   `src/tau2/domains/banking_knowledge/tools.py`. Upstream quirks are kept on
   purpose, e.g. `close_bank_account_7392` only charges early-closure fees for
   class `savings` while the data spells it `saving`, `open_bank_account_4821`
   stores `account_type`/`account_class` although every other account uses
   `class`/`level`, `submit_interest_discrepancy_report_7294` writes to a
   table that does not exist (so nothing is stored), and
   `approve_credit_limit_increase_5847` updates the limit even when its
   approval record id already exists."
  (:require [clojure.string :as str]
            [dvergr.benchmarks.tau2.banking.db :as db]
            [dvergr.benchmarks.python :as py])
  (:import [java.time LocalDateTime]
           [java.time.temporal ChronoUnit]
           [java.util.regex Matcher Pattern]))

;; ---------------------------------------------------------------------------
;; Private Python helpers

(defn- conversion-error?
  "Whether `e` is a Python ValueError/TypeError (what `except (ValueError,
   TypeError)` catches)."
  [e]
  (and (py/py-exception? e) (#{"ValueError" "TypeError"} (py/exception-type e))))

(defmacro ^:private try-convert
  "Evaluate `body`; on a Python ValueError/TypeError return `::invalid`."
  [& body]
  `(try ~@body
        (catch clojure.lang.ExceptionInfo e#
          (if (conversion-error? e#) ::invalid (throw e#)))))

(defn- key-in?
  "Python `k in d` for a dict: raises TypeError for unhashable keys."
  [m k]
  (when (or (map? k) (sequential? k) (set? k))
    (py/raise "TypeError" (str "unhashable type: '" (py/type-name k) "'")))
  (contains? m k))

(defn- in-list? [x xs] (boolean (some #(py/py-eq x %) xs)))

(defn- str-method
  "Python `s.<method>()` on a value that should be a str."
  [x method f]
  (if (string? x)
    (f x)
    (py/raise "AttributeError" (str "'" (py/type-name x) "' object has no attribute '" method "'"))))

(defn- py-get
  "Python `d.get(k, default)`: a present key with None value yields None."
  [m k default]
  (if (contains? m k) (get m k) default))

(defn- py-round-float
  "Python `round(x, n)` for a float, keeping the sign of a zero result
   (`round(-1e-05 - ..., 4)` is `-0.0`); `py/py-round` drops it."
  [x n]
  (let [r (py/py-round x n)]
    (if (and (float? x) (zero? r) (neg? (Math/copySign 1.0 (double x))))
      -0.0
      r)))

(defn- float-integer? [^double d]
  (and (not (Double/isNaN d)) (not (Double/isInfinite d)) (== d (Math/floor d))))

(defn- group-thousands
  "Python `format(n, ',')` for an int."
  [n]
  (let [s (str (bigint n))
        neg? (str/starts-with? s "-")
        digits (if neg? (subs s 1) s)
        groups (->> (reverse digits) (partition-all 3) (map (comp str/join reverse)) reverse)]
    (str (when neg? "-") (str/join "," groups))))

(def ^:private strptime-directives
  {\m "(1[0-2]|0[1-9]|[1-9])"
   \d "(3[01]|[12]\\d|0[1-9]|[1-9]| [1-9])"
   \Y "(\\d\\d\\d\\d)"
   \H "(2[0-3]|[0-1]\\d|\\d)"
   \M "([0-5]\\d|\\d)"
   \S "(6[0-1]|[0-5]\\d|\\d)"})

(defn- strptime-pattern [^String fmt]
  (loop [i 0 fields [] sb (StringBuilder.)]
    (if (>= i (count fmt))
      [(Pattern/compile (str sb) Pattern/CASE_INSENSITIVE) fields]
      (let [c (.charAt fmt i)]
        (cond
          (= c \%) (let [d (.charAt fmt (inc i))]
                     (.append sb ^String (strptime-directives d))
                     (recur (+ i 2) (conj fields d) sb))
          (Character/isWhitespace c) (let [j (loop [j i] (if (and (< j (count fmt))
                                                                  (Character/isWhitespace (.charAt fmt j)))
                                                           (recur (inc j)) j))]
                                       (.append sb "\\s+")
                                       (recur j fields sb))
          :else (do (.append sb (Pattern/quote (str c)))
                    (recur (inc i) fields sb)))))))

(def ^:private strptime-patterns (memoize strptime-pattern))

(defn- strptime
  "`datetime.strptime(s, fmt)` for the numeric directives these tools use.
   Returns a LocalDateTime, or nil where Python raises ValueError. Non-str
   input raises TypeError like CPython."
  [s fmt]
  (when-not (string? s)
    (py/raise "TypeError" (str "strptime() argument 1 must be str, not "
                               (if (nil? s) "None" (py/type-name s)))))
  (let [[^Pattern p fields] (strptime-patterns fmt)
        ^Matcher m (.matcher p ^String s)]
    (when (and (.lookingAt m) (= (.end m) (count s)))
      (let [v (zipmap fields (map #(Long/parseLong (str/trim (.group m (int %))))
                                  (range 1 (inc (count fields)))))]
        (when (>= (get v \Y 1900) 1)
          (try
            (LocalDateTime/of (int (get v \Y 1900)) (int (get v \m 1)) (int (get v \d 1))
                              (int (get v \H 0)) (int (get v \M 0)) (int (get v \S 0)))
            (catch java.time.DateTimeException _ nil)))))))

(def ^:private now (LocalDateTime/of 2025 11 14 3 40 0))

(defn- days-since
  "`(get_now() - d).days` (floor division of the timedelta)."
  [^LocalDateTime d]
  (let [secs (.between ChronoUnit/SECONDS d now)]
    (Math/floorDiv secs 86400)))

(def ^:private datetime-min (LocalDateTime/of 1 1 1 0 0 0))

(defn- query-json [field value]
  (str "{\"" field "\": \"" (py/py-str value) "\"}"))

(defn- has-records? [result]
  (not (or (str/includes? result "No records found")
           (str/includes? result "No results found"))))

;; ---------------------------------------------------------------------------
;; Credit limit increases

(def submit-credit-limit-increase-request
  (db/tool
   {:name "submit_credit_limit_increase_request_7392"
    :params [["credit_card_account_id"] ["user_id"] ["requested_increase_amount"]]
    :type :write :discoverable? true
    :fn (fn [db {:strs [credit_card_account_id user_id requested_increase_amount]}]
          (if (or (not (py/truthy? credit_card_account_id)) (not (py/truthy? user_id))
                  (nil? requested_increase_amount))
            "Error: Missing required parameters."
            (let [f (try-convert (py/py-float requested_increase_amount))]
              (cond
                (= ::invalid f) "Error: Invalid requested_increase_amount. Must be a whole number."
                (not (float-integer? f))
                "Error: Invalid requested_increase_amount. Must be a whole number of dollars."
                :else
                (let [amount (py/py-int f)]
                  (cond
                    (<= amount 0) "Error: Requested increase amount must be positive."
                    (not (key-in? (db/table db "credit_card_accounts") credit_card_account_id))
                    (str "Error: Credit card account '" (py/py-str credit_card_account_id) "' not found.")
                    (not (py/py-eq (get (db/record db "credit_card_accounts" credit_card_account_id) "user_id")
                                   user_id))
                    (str "Error: Credit card account '" (py/py-str credit_card_account_id)
                         "' does not belong to user '" (py/py-str user_id) "'.")
                    :else
                    (let [request-id (db/credit-limit-increase-request-id credit_card_account_id user_id amount)
                          rec (array-map "request_id" request-id
                                         "credit_card_account_id" credit_card_account_id
                                         "user_id" user_id
                                         "requested_increase_amount" amount
                                         "submitted_at" db/today-str
                                         "status" "PENDING")
                          [db' ok?] (db/add-record db "credit_limit_increase_requests" request-id rec)]
                      (if-not ok?
                        "Error: A similar request may already exist."
                        {:db db'
                         :result (str "Credit limit increase request submitted successfully.\n\n"
                                      "Executed: submit_credit_limit_increase_request_7392\n"
                                      "  - Request ID: " request-id "\n"
                                      "  - Account: " (py/py-str credit_card_account_id) "\n"
                                      "  - Requested Increase: $" (group-thousands amount) "\n"
                                      "  - Status: PENDING")}))))))))}))

(def get-credit-limit-increase-history
  (db/tool
   {:name "get_credit_limit_increase_history_4829"
    :params [["credit_card_account_id"]]
    :type :read :discoverable? true
    :fn (fn [db {:strs [credit_card_account_id]}]
          (if-not (py/truthy? credit_card_account_id)
            "Error: Missing required parameter: credit_card_account_id"
            (let [result (db/query-database-tool db "credit_limit_increase_requests"
                                                 (query-json "credit_card_account_id" credit_card_account_id))]
              (str/join "\n"
                        ["Credit limit increase history retrieved."
                         ""
                         "Executed: get_credit_limit_increase_history_4829"
                         (str "Credit limit increase history for account " (py/py-str credit_card_account_id) ":")
                         (if (has-records? result)
                           result
                           "\nNo credit limit increase requests found for this account.")]))))}))

(def get-payment-history
  (db/tool
   {:name "get_payment_history_6183"
    :params [["credit_card_account_id"] ["months"]]
    :type :read :discoverable? true
    :fn (fn [db {:strs [credit_card_account_id months]}]
          (if (or (not (py/truthy? credit_card_account_id)) (nil? months))
            "Error: Missing required parameters (credit_card_account_id, months)."
            (let [months (try-convert (py/py-int months))]
              (cond
                (= ::invalid months) "Error: Invalid months value. Must be a positive integer."
                (<= months 0) "Error: months must be a positive integer."
                :else
                (let [payments (filterv #(py/py-eq (get % "credit_card_account_id") credit_card_account_id)
                                        (vals (db/table db "payment_history")))]
                  (if (empty? payments)
                    (str "No payment history found for account '" (py/py-str credit_card_account_id) "'.")
                    (let [sort-key #(let [v (get % "payment_date")] (if (py/truthy? v) v ""))
                          cmp (fn [a b] (cond (py/py-compare :lt a b) -1 (py/py-compare :gt a b) 1 :else 0))
                          payments (vec (sort-by sort-key (fn [a b] (cmp b a)) payments))
                          payments (if (> months (count payments)) payments (subvec payments 0 (int months)))
                          on-time (count (take-while #(py/py-eq (get % "status") "ON_TIME") payments))]
                      (str/join "\n"
                                (concat
                                 [(str "Payment history for account '" (py/py-str credit_card_account_id)
                                       "' (last " (py/py-str months) " months):")
                                  (str "Consecutive on-time payments: " on-time)]
                                 (map (fn [p]
                                        (str "\n  - Payment Date: " (py/py-str (get p "payment_date")) "\n"
                                             "    Amount: " (py/py-str (get p "amount")) "\n"
                                             "    Status: " (py/py-str (get p "status"))))
                                      payments))))))))))}))

(def approve-credit-limit-increase
  (db/tool
   {:name "approve_credit_limit_increase_5847"
    :params [["credit_card_account_id"] ["user_id"] ["new_credit_limit"]]
    :type :write :discoverable? true
    :fn (fn [db {:strs [credit_card_account_id user_id new_credit_limit]}]
          (cond
            (or (not (py/truthy? credit_card_account_id)) (not (py/truthy? user_id))
                (nil? new_credit_limit))
            "Error: Missing required parameters."

            (not (key-in? (db/table db "credit_card_accounts") credit_card_account_id))
            (str "Error: Credit card account '" (py/py-str credit_card_account_id) "' not found.")

            (not (py/py-eq (get (db/record db "credit_card_accounts" credit_card_account_id) "user_id")
                           user_id))
            (str "Error: Credit card account '" (py/py-str credit_card_account_id)
                 "' does not belong to user '" (py/py-str user_id) "'.")

            :else
            (let [cc (db/record db "credit_card_accounts" credit_card_account_id)
                  ineligible?
                  (or (some (fn [d] (and (py/py-eq (get d "user_id") user_id)
                                         (in-list? (get d "status") ["SUBMITTED" "UNDER_REVIEW" "PENDING"])))
                            (vals (db/table db "transaction_disputes")))
                      (some (fn [o] (and (py/py-eq (get o "credit_card_account_id") credit_card_account_id)
                                         (in-list? (get o "status") ["PENDING" "PROCESSING" "SHIPPED"])))
                            (vals (db/table db "credit_card_orders")))
                      (in-list? (str-method (py-get cc "account_status" "") "upper" str/upper-case)
                                ["PAST_DUE" "DELINQUENT" "COLLECTIONS" "CLOSED"]))]
              (if ineligible?
                "Error: Credit limit increase request cannot be approved at this time."
                (let [current-limit (let [v (try-convert
                                             (py/py-float (-> (py/py-str (py-get cc "credit_limit" "$0.00"))
                                                              (str/replace "$" "")
                                                              (str/replace "," ""))))]
                                      (if (= ::invalid v) 0.0 v))
                      new-limit (py/py-float new_credit_limit)
                      increase (- new-limit current-limit)
                      db (db/set-field db "credit_card_accounts" credit_card_account_id "credit_limit"
                                       (str "$" (py/format-fixed new-limit 2)))
                      request-id (db/credit-limit-increase-request-id credit_card_account_id user_id increase)
                      rec (array-map "request_id" request-id
                                     "credit_card_account_id" credit_card_account_id
                                     "user_id" user_id
                                     "previous_limit" (str "$" (py/format-fixed current-limit 2))
                                     "new_limit" (str "$" (py/format-fixed new-limit 2))
                                     "increase_amount" (str "$" (py/format-fixed increase 2))
                                     "decision_date" db/today-str
                                     "status" "APPROVED")
                      [db _] (db/add-record db "credit_limit_increase_requests" request-id rec)]
                  {:db db
                   :result (str "Credit limit increase approved!\n"
                                "  - Account: " (py/py-str credit_card_account_id) "\n"
                                "  - Previous Limit: $" (py/format-fixed current-limit 2) "\n"
                                "  - New Limit: $" (py/format-fixed new-limit 2) "\n"
                                "  - Increase: $" (py/format-fixed increase 2) "\n"
                                "  - Effective Date: " db/today-str "\n"
                                "The customer will receive a confirmation email.")})))))}))

(def ^:private denial-reasons
  ["insufficient_account_age" "cooldown_period_active" "pending_disputes"
   "pending_replacement_card" "past_due_balance" "high_utilization"
   "insufficient_payment_history" "requested_amount_exceeds_limit" "other"])

(def deny-credit-limit-increase
  (db/tool
   {:name "deny_credit_limit_increase_5848"
    :params [["credit_card_account_id"] ["user_id"] ["denial_reason"]]
    :type :write :discoverable? true
    :fn (fn [db {:strs [credit_card_account_id user_id denial_reason]}]
          (cond
            (or (not (py/truthy? credit_card_account_id)) (not (py/truthy? user_id))
                (not (py/truthy? denial_reason)))
            "Error: Missing required parameters."

            (not (in-list? denial_reason denial-reasons))
            (str "Error: Invalid denial_reason. Must be one of: " (str/join ", " denial-reasons))

            (not (key-in? (db/table db "credit_card_accounts") credit_card_account_id))
            (str "Error: Credit card account '" (py/py-str credit_card_account_id) "' not found.")

            :else
            (let [request-id (db/credit-limit-increase-request-id credit_card_account_id user_id 0.0)
                  rec (array-map "request_id" request-id
                                 "credit_card_account_id" credit_card_account_id
                                 "user_id" user_id
                                 "denial_reason" denial_reason
                                 "decision_date" db/today-str
                                 "status" "DENIED")
                  [db _] (db/add-record db "credit_limit_increase_requests" request-id rec)]
              {:db db
               :result (str "Credit limit increase request denied.\n"
                            "  - Account: " (py/py-str credit_card_account_id) "\n"
                            "  - Denial Reason: " (py/py-str denial_reason) "\n"
                            "  - Date: " db/today-str "\n"
                            "The customer will receive a notification explaining the denial.")})))}))

;; ---------------------------------------------------------------------------
;; Bank accounts

(def ^:private account-types ["checking" "savings" "business_checking" "business_savings"])

(defn- account-kind
  "`acc.get(\"account_type\", acc.get(\"class\", \"\"))`."
  [acc]
  (py-get acc "account_type" (py-get acc "class" "")))

(defn- open-or-active? [acc] (in-list? (py-get acc "status" "") ["OPEN" "ACTIVE"]))

(defn- account-age-days
  "`get_account_age_days` in `open_bank_account_4821`: 0 on ValueError."
  [acc]
  (if-let [d (strptime (py-get acc "date_opened" "") "%m/%d/%Y")]
    (days-since d)
    0))

(def open-bank-account
  (db/tool
   {:name "open_bank_account_4821"
    :params [["user_id"] ["account_type"] ["account_class"]]
    :type :write :discoverable? true
    :fn (fn [db {:strs [user_id account_type account_class]}]
          (cond
            (or (not (py/truthy? user_id)) (not (py/truthy? account_type))
                (not (py/truthy? account_class)))
            "Error: Missing required parameters."

            (not (in-list? account_type account-types))
            (str "Error: Invalid account_type. Must be one of: " (py/py-repr account-types))

            :else
            (let [user-accounts (filterv #(py/py-eq (get % "user_id") user_id)
                                         (vals (db/table db "accounts")))
                  not-met "Error: Account eligibility requirements not met."
                  eligibility-error
                  (case account_type
                    "savings"
                    (when-not (some #(and (in-list? (account-kind %) ["checking" "personal_checking"])
                                          (open-or-active? %)
                                          (>= (account-age-days %) 14))
                                    user-accounts)
                      not-met)

                    "business_checking"
                    (cond
                      (some #(py/py-eq (get % "status") "CLOSED") user-accounts) not-met
                      (not (some #(and (in-list? (account-kind %) ["checking" "personal_checking"])
                                       (open-or-active? %))
                                 user-accounts))
                      not-met)

                    "business_savings"
                    (cond
                      (some #(< (db/account-balance %) 0) user-accounts) not-met
                      (not (some #(and (py/py-eq (account-kind %) "business_checking")
                                       (open-or-active? %)
                                       (>= (account-age-days %) 30))
                                 user-accounts))
                      not-met)

                    nil)]
              (or eligibility-error
                  (let [account-id (db/deterministic-id (str "account:" (py/py-str user_id) ":"
                                                             (py/py-str account_type) ":"
                                                             (py/py-str account_class)))
                        rec (array-map "account_id" account-id
                                       "user_id" user_id
                                       "account_type" account_type
                                       "account_class" account_class
                                       "current_holdings" "0.00"
                                       "status" "OPEN"
                                       "date_opened" db/today-str)
                        [db' ok?] (db/add-record db "accounts" account-id rec)]
                    (if-not ok?
                      (str "Failed to open account: Account ID '" account-id "' may already exist.")
                      {:db db'
                       :result (str "Bank account opened successfully!\n"
                                    "  - Account ID: " account-id "\n"
                                    "  - User ID: " (py/py-str user_id) "\n"
                                    "  - Account Type: " (py/py-str account_type) "\n"
                                    "  - Account Class: " (py/py-str account_class) "\n"
                                    "  - Status: OPEN\n"
                                    "  - Initial Balance: $0.00\n"
                                    "  - Date Opened: " db/today-str)}))))))}))

(def ^:private personal-checking-early-closure
  {"Light Blue Account" {"fee" 15 "window_days" 30}
   "Light Green Account" {"fee" 15 "window_days" 30}
   "Green Fee-Free Account" {"fee" 15 "window_days" 30}
   "Blue Account" {"fee" 25 "window_days" 60}
   "Green Account" {"fee" 25 "window_days" 60}
   "Evergreen Account" {"fee" 50 "window_days" 90}
   "Bluest Account" {"fee" 100 "window_days" 180}})

(def ^:private personal-savings-early-closure
  {"Bronze Account" {"fee" 20 "window_days" 60}
   "Silver Account" {"fee" 35 "window_days" 90}
   "Silver Plus Account" {"fee" 35 "window_days" 90}
   "Gold Account" {"fee" 75 "window_days" 180}
   "Gold Plus Account" {"fee" 75 "window_days" 180}
   "Gold Years Account" {"fee" 75 "window_days" 180}
   "Platinum Account" {"fee" 150 "window_days" 270}
   "Platinum Plus Account" {"fee" 150 "window_days" 270}
   "Diamond Elite Account" {"fee" 150 "window_days" 270}})

(def close-bank-account
  (db/tool
   {:name "close_bank_account_7392"
    :params [["account_id"] ["reason" "Customer requested closure"] ["waive_early_closure_fee" false]]
    :type :write :discoverable? true
    :fn (fn [db {:strs [account_id reason waive_early_closure_fee]}]
          (cond
            (not (py/truthy? account_id)) "Error: Missing required parameter (account_id)."

            (not (key-in? (db/table db "accounts") account_id))
            (str "Error: Account '" (py/py-str account_id) "' not found.")

            (py/py-eq (get (db/record db "accounts" account_id) "status") "CLOSED")
            (str "Error: Account '" (py/py-str account_id) "' is already closed.")

            :else
            (let [account (db/record db "accounts" account_id)
                  balance (db/account-balance account)
                  fee (if (py/truthy? waive_early_closure_fee)
                        0.0
                        (let [level (py-get account "level" "")
                              klass (py-get account "class" "")
                              opened (py-get account "date_opened" "")
                              config (cond
                                       (py/py-eq klass "checking") (get personal-checking-early-closure level)
                                       (py/py-eq klass "savings") (get personal-savings-early-closure level))]
                          (if (and config (py/truthy? opened))
                            (let [d (strptime opened "%m/%d/%Y")]
                              (if (and d (< (days-since d) (get config "window_days")))
                                (let [required (get config "fee")]
                                  (if (< balance required) ::unable required))
                                0.0))
                            0.0)))]
              (cond
                (= ::unable fee) "Error: Account unable to be closed."
                (not (== 0 (- balance fee)))
                (str "Error: Account balance must be $0.00 before closing. Current balance: $"
                     (py/format-fixed balance 2))
                :else
                (let [db (-> db
                             (db/set-field "accounts" account_id "status" "CLOSED")
                             (db/set-field "accounts" account_id "date_closed" db/today-str)
                             (db/set-field "accounts" account_id "closure_reason" reason)
                             (db/set-field "accounts" account_id "early_closure_fee_waived"
                                           waive_early_closure_fee))
                      account (db/record db "accounts" account_id)]
                  {:db db
                   :result (str "Bank account closed successfully!\n"
                                "  - Account ID: " (py/py-str account_id) "\n"
                                "  - Account Type: " (py/py-str (py-get account "account_type" "N/A")) "\n"
                                "  - Account Class: " (py/py-str (py-get account "account_class" "N/A")) "\n"
                                "  - Status: CLOSED\n"
                                "  - Date Closed: " db/today-str "\n"
                                "  - Reason: " (py/py-str reason) "\n"
                                "  - Early Closure Fee Waived: "
                                (if (py/truthy? waive_early_closure_fee) "Yes" "No"))})))))}))

(def get-all-user-accounts-by-user-id
  (db/tool
   {:name "get_all_user_accounts_by_user_id_3847"
    :params [["user_id"]]
    :type :read :discoverable? true
    :fn (fn [db {:strs [user_id]}]
          (if-not (py/truthy? user_id)
            "Error: Missing required parameter: user_id"
            (let [accounts (db/query-database-tool db "accounts" (query-json "user_id" user_id))
                  cards (db/query-database-tool db "credit_card_accounts" (query-json "user_id" user_id))]
              (str/join "\n"
                        ["User accounts retrieved successfully."
                         ""
                         "Executed: get_all_user_accounts_by_user_id_3847"
                         (str "Accounts for user " (py/py-str user_id) ":")
                         ""
                         "Bank Accounts:"
                         (if (has-records? accounts) accounts "  No bank accounts found.")
                         "\nCredit Card Accounts:"
                         (if (has-records? cards) cards "  No credit card accounts found.")]))))}))

(def transfer-funds-between-bank-accounts
  (db/tool
   {:name "transfer_funds_between_bank_accounts_7291"
    :params [["source_account_id"] ["destination_account_id"] ["amount"]]
    :type :write :discoverable? true
    :fn (fn [db {:strs [source_account_id destination_account_id amount]}]
          (if (or (not (py/truthy? source_account_id)) (not (py/truthy? destination_account_id))
                  (nil? amount))
            "Error: Missing required parameters (source_account_id, destination_account_id, amount)."
            (let [amt (try-convert (py/py-float amount))
                  accounts (db/table db "accounts")]
              (cond
                (= ::invalid amt) (str "Error: Invalid amount '" (py/py-str amount) "'. Must be a number.")
                (<= amt 0) "Error: Transfer amount must be positive."
                (py/py-eq source_account_id destination_account_id)
                "Error: Source and destination accounts cannot be the same."
                (not (key-in? accounts source_account_id))
                (str "Error: Source account '" (py/py-str source_account_id) "' not found.")
                (not (key-in? accounts destination_account_id))
                (str "Error: Destination account '" (py/py-str destination_account_id) "' not found.")
                (not (in-list? (get-in accounts [source_account_id "status"]) ["ACTIVE" "OPEN"]))
                (str "Error: Source account '" (py/py-str source_account_id) "' is not active.")
                (not (in-list? (get-in accounts [destination_account_id "status"]) ["ACTIVE" "OPEN"]))
                (str "Error: Destination account '" (py/py-str destination_account_id) "' is not active.")
                :else
                (let [source-balance (db/account-balance (get accounts source_account_id))]
                  (if (< source-balance amt)
                    (str "Error: Insufficient funds. Source account balance is $"
                         (py/format-fixed source-balance 2) ", but transfer amount is $"
                         (py/format-fixed amt 2) ".")
                    (let [dest-balance (db/account-balance (get accounts destination_account_id))
                          new-source (- source-balance amt)
                          new-dest (+ dest-balance amt)]
                      {:db (-> db
                               (db/set-field "accounts" source_account_id "current_holdings"
                                             (str "$" (py/format-fixed new-source 2)))
                               (db/set-field "accounts" destination_account_id "current_holdings"
                                             (str "$" (py/format-fixed new-dest 2))))
                       :result (str "Transfer completed successfully!\n"
                                    "  - Amount: $" (py/format-fixed amt 2) "\n"
                                    "  - From: " (py/py-str source_account_id) " (new balance: $"
                                    (py/format-fixed new-source 2) ")\n"
                                    "  - To: " (py/py-str destination_account_id) " (new balance: $"
                                    (py/format-fixed new-dest 2) ")")})))))))}))

(defn- apply-account-credit
  "Shared body of `apply_checking_account_credit_5829` and
   `apply_savings_account_credit_6831`; `spec` carries their differences."
  [{:keys [valid-types class-ok? not-class-error holdings-prefix seed-prefix description]}
   db {:strs [account_id amount credit_type]}]
  (if (or (not (py/truthy? account_id)) (nil? amount) (not (py/truthy? credit_type)))
    "Error: Missing required parameters."
    (let [amt (try-convert (py/py-float amount))]
      (cond
        (= ::invalid amt) "Error: Invalid credit amount. Must be a number."
        (<= amt 0) "Error: Credit amount must be positive."
        (not (in-list? credit_type valid-types))
        (str "Error: Invalid credit_type. Must be one of: " (py/py-repr valid-types))
        (not (key-in? (db/table db "accounts") account_id))
        (str "Error: Account '" (py/py-str account_id) "' not found.")
        :else
        (let [account (db/record db "accounts" account_id)
              klass (str-method (py-get account "class" "") "lower" str/lower-case)]
          (cond
            (not (class-ok? klass)) (str "Error: Account '" (py/py-str account_id) "' " not-class-error)
            (not (in-list? (get account "status") ["ACTIVE" "OPEN"]))
            (str "Error: Account '" (py/py-str account_id) "' is not active.")
            :else
            (let [current (db/account-balance account)
                  new-balance (+ current amt)
                  txn-id (str "txn_" (db/deterministic-id
                                      (str seed-prefix ":" (py/py-str account_id) ":" (py/py-str credit_type)
                                           ":" (py/py-str amt) ":" db/today-str)))
                  txn (array-map "transaction_id" txn-id
                                 "account_id" account_id
                                 "date" db/today-str
                                 "description" (description credit_type)
                                 "amount" amt
                                 "type" credit_type
                                 "status" "posted")]
              {:db (-> db
                       (db/set-field "accounts" account_id "current_holdings"
                                     (str holdings-prefix (py/format-fixed new-balance 2)))
                       (db/put-record "bank_account_transaction_history" txn-id txn))
               :result (str "\nCredit applied successfully!\n"
                            "  - Transaction ID: " txn-id "\n"
                            "  - Account: " (py/py-str account_id) "\n"
                            "  - Credit Type: " (py/py-str credit_type) "\n"
                            "  - Amount: $" (py/format-fixed amt 2) "\n"
                            "  - Previous Balance: $" (py/format-fixed current 2) "\n"
                            "  - New Balance: $" (py/format-fixed new-balance 2))})))))))

(def apply-checking-account-credit
  (db/tool
   {:name "apply_checking_account_credit_5829"
    :params [["account_id"] ["amount"] ["credit_type"]]
    :type :write :discoverable? true
    :fn (partial apply-account-credit
                 {:valid-types ["rebate_credit" "fee_refund"]
                  :class-ok? #(= "checking" %)
                  :not-class-error "is not a checking account. Credits can only be applied to checking accounts."
                  :holdings-prefix "$"
                  :seed-prefix "checking_credit"
                  :description #(if (py/py-eq % "rebate_credit")
                                  "REBATE CREDIT - CUSTOMER SERVICE"
                                  "FEE REFUND - CUSTOMER SERVICE")})}))

(def apply-savings-account-credit
  (db/tool
   {:name "apply_savings_account_credit_6831"
    :params [["account_id"] ["amount"] ["credit_type"]]
    :type :write :discoverable? true
    :fn (partial apply-account-credit
                 {:valid-types ["interest_correction" "fee_refund" "goodwill_credit"]
                  :class-ok? #(contains? #{"saving" "savings"} %)
                  :not-class-error "is not a savings account. This tool only applies to savings accounts."
                  :holdings-prefix ""
                  :seed-prefix "savings_credit"
                  :description #(cond
                                  (py/py-eq % "interest_correction") "INTEREST CORRECTION - CUSTOMER SERVICE"
                                  (py/py-eq % "fee_refund") "FEE REFUND - CUSTOMER SERVICE"
                                  :else "GOODWILL CREDIT - CUSTOMER SERVICE")})}))

(def submit-interest-discrepancy-report
  (db/tool
   {:name "submit_interest_discrepancy_report_7294"
    :params [["account_id"] ["user_id"] ["expected_apy"] ["actual_apy"] ["amount_difference"]]
    :type :write :discoverable? true
    :fn (fn [db {:strs [account_id user_id expected_apy actual_apy amount_difference]}]
          (if (or (not (py/truthy? account_id)) (not (py/truthy? user_id))
                  (nil? expected_apy) (nil? actual_apy) (nil? amount_difference))
            "Error: Missing required parameters."
            (let [nums (try-convert [(py/py-float expected_apy) (py/py-float actual_apy)
                                     (py/py-float amount_difference)])]
              (if (= ::invalid nums)
                "Error: expected_apy, actual_apy, and amount_difference must be numbers."
                (let [[expected actual difference] nums
                      account (get (db/table db "accounts") account_id)
                      user (get (db/table db "users") user_id)]
                  (cond
                    (nil? account) (str "Error: Account '" (py/py-str account_id) "' not found.")
                    (nil? user) (str "Error: User '" (py/py-str user_id) "' not found.")
                    :else
                    (let [report-id (str "IDR_" (db/deterministic-id
                                                 (str "interest_report:" (py/py-str account_id) ":"
                                                      (py/py-str user_id) ":" (py/py-str expected) ":"
                                                      (py/py-str actual) ":" db/today-str)))
                          apy-diff (py-round-float (- expected actual) 4)
                          rec (array-map "report_id" report-id
                                         "account_id" account_id
                                         "user_id" user_id
                                         "account_level" (py-get account "level" "Unknown")
                                         "expected_apy" expected
                                         "actual_apy" actual
                                         "apy_difference" apy-diff
                                         "amount_difference" difference
                                         "submitted_date" db/today-str
                                         "status" "PENDING_REVIEW")
                          ;; Upstream writes to a table TransactionalDB does not
                          ;; declare, so add_to_db returns False and nothing is kept.
                          [db _] (db/add-record db "interest_discrepancy_reports" report-id rec)]
                      {:db db
                       :result (str "\nInterest Discrepancy Report Submitted Successfully!\n"
                                    "  - Report ID: " report-id "\n"
                                    "  - Account: " (py/py-str account_id) " ("
                                    (py/py-str (py-get account "level" "Unknown")) ")\n"
                                    "  - Customer: " (py/py-str (py-get user "name" "Unknown")) "\n"
                                    "  - Expected APY: " (py/py-str expected) "%\n"
                                    "  - Actual APY: " (py/py-str actual) "%\n"
                                    "  - APY Difference: " (py/py-str apy-diff) "%\n"
                                    "  - Amount Difference: $" (py/format-fixed difference 2) "\n"
                                    "  - Status: PENDING_REVIEW\n"
                                    "\nThe backend team will investigate this discrepancy and ensure "
                                    "correct APY calculations are applied going forward.")})))))))}))

(defn- txn-sort-key
  "`txn_sort_key`: the first matching format, else `datetime.min`."
  [[_ rec]]
  (let [s (py/py-str (py-get rec "date" ""))]
    (or (strptime s "%m/%d/%Y %H:%M:%S")
        (strptime s "%m/%d/%Y")
        datetime-min)))

(def get-bank-account-transactions
  (db/tool
   {:name "get_bank_account_transactions_9173"
    :params [["account_id"]]
    :type :read :discoverable? true
    :fn (fn [db {:strs [account_id]}]
          (cond
            (not (py/truthy? account_id)) "Error: Missing required parameter: account_id"

            (not (key-in? (db/table db "accounts") account_id))
            (str "Error: Account '" (py/py-str account_id) "' not found.")

            :else
            (let [txns (->> (db/query db "bank_account_transaction_history" {"account_id" account_id})
                            ;; `sorted(..., reverse=True)` is stable: ties keep stored order.
                            (sort-by txn-sort-key (fn [a b] (compare b a))))]
              (str/join "\n"
                        ["Bank account transactions retrieved successfully."
                         ""
                         "Executed: get_bank_account_transactions_9173"
                         (str "Transactions for account " (py/py-str account_id) ":")
                         (if (seq txns)
                           (str/join "\n"
                                     (concat
                                      [(str "Found " (count txns)
                                            " record(s) in 'bank_account_transaction_history':\n")]
                                      (mapcat (fn [i [id rec]]
                                                (concat [(str i ". Record ID: " id)]
                                                        (map (fn [[f v]] (str "   " f ": " (py/py-str v))) rec)
                                                        [""]))
                                              (iterate inc 1) txns)))
                           "\nNo transactions found for this account.")]))))}))

(def tools
  "Part B of the agent-discoverable `KnowledgeTools`, in upstream order."
  [submit-credit-limit-increase-request
   get-credit-limit-increase-history
   get-payment-history
   approve-credit-limit-increase
   deny-credit-limit-increase
   open-bank-account
   close-bank-account
   get-all-user-accounts-by-user-id
   transfer-funds-between-bank-accounts
   apply-checking-account-credit
   apply-savings-account-credit
   submit-interest-discrepancy-report
   get-bank-account-transactions])
