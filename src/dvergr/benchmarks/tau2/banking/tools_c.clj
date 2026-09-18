(ns dvergr.benchmarks.tau2.banking.tools-c
  "tau2 `banking_knowledge` agent-discoverable debit card tools (chunk C):
   ordering, activation (three issue-reason variants), close, freeze and
   unfreeze, fraud-alert/velocity clearing, PIN reset/change, listing, and
   temporary limit increases.

   Byte-faithful transcription of `KnowledgeTools` methods
   `order_debit_card_5739` .. `request_temporary_debit_card_limit_increase_8374`
   in upstream `src/tau2/domains/banking_knowledge/tools.py`; verified
   differentially against the Python oracle (`dev/benchmarks/tau2/oracle.py`).

   Deliberately reproduced upstream behaviour:
   - PIN/CVV/last-4 arguments sent as numbers raise Python's
     `AttributeError: 'int' object has no attribute 'isdigit'` (upstream
     calls `str.isdigit` on them unguarded).
   - `order_debit_card_5739` renders fees with `str(float)` (`$10.0`) in the
     item list but `.2f` in totals, and records an unparseable
     `excess_replacement_fee` as int `0`.
   - The activation tools only format-check the CVV and ignore the
     expiration date."
  (:require [clojure.string :as str]
            [dvergr.benchmarks.tau2.banking.db :as db]
            [dvergr.benchmarks.tau2.python :as py])
  (:import [java.time LocalDate]
           [java.time.temporal ChronoUnit]
           [java.util Locale]))

;; ---------------------------------------------------------------------------
;; Python helpers (private; candidates for python.clj / db.clj)

(defn- ordered [& kvs] (apply array-map kvs))

(defn- attribute-error [x attr]
  (py/raise "AttributeError" (str "'" (py/type-name x) "' object has no attribute '" attr "'")))

(defn- py-isdigit?
  "`str.isdigit()` for a string; non-strings raise AttributeError."
  [x]
  (if (string? x)
    (boolean (and (seq x) (every? #(Character/isDigit ^char %) x)))
    (attribute-error x "isdigit")))

(defn- py-upper [x]
  (if (string? x) (.toUpperCase ^String x Locale/ROOT) (attribute-error x "upper")))

(defn- py-lower [x]
  (if (string? x) (.toLowerCase ^String x Locale/ROOT) (attribute-error x "lower")))

(defn- try-float
  "`float(x)` guarded by `except (TypeError, ValueError)`: `[v]` or nil."
  [x]
  (try
    [(py/py-float x)]
    (catch clojure.lang.ExceptionInfo e
      (if (#{"TypeError" "ValueError"} (py/exception-type e)) nil (throw e)))))

(defn- dissoc-ordered [m k]
  (apply array-map (mapcat identity (remove #(= k (key %)) m))))

(defn- validate-pin
  "`_validate_pin`: `db/validate-pin` plus the AttributeError a truthy
   non-string PIN raises at `pin.isdigit()`."
  [pin]
  (when (and (py/truthy? pin) (not (string? pin)))
    (attribute-error pin "isdigit"))
  (db/validate-pin pin))

(defn- validate-activation
  "`_validate_activation_common`. `db/validate-activation-common` returns
   format messages for non-string pin/cvv/last-4 where upstream raises
   AttributeError at `.isdigit()`; raise those first, in upstream order."
  [args db allowed tool-name]
  (let [{:strs [card_id last_4_digits expiration_date cvv pin]} args]
    (when (every? py/truthy? [card_id last_4_digits expiration_date cvv pin])
      (when (nil? (validate-pin pin))
        (when (and (py-isdigit? cvv) (= 3 (count cvv)))
          (py-isdigit? last_4_digits))))
    (db/validate-activation-common args db allowed tool-name)))

(defn- strptime-mdy
  "`datetime.strptime(s, '%m/%d/%Y')` as a LocalDate, or nil where Python
   raises ValueError. Non-strings raise TypeError like CPython."
  [s]
  (when-not (string? s)
    (py/raise "TypeError" (str "strptime() argument 1 must be str, not " (py/type-name s))))
  (when-let [[_ m d y] (re-matches #"(?i)(1[0-2]|0[1-9]|[1-9])/(3[01]|[12]\d|0[1-9]|[1-9]| [1-9])/(\d\d\d\d)" s)]
    (let [y (Integer/parseInt y) m (Integer/parseInt m) d (Integer/parseInt (str/trim d))]
      (when (pos? y)
        (try (LocalDate/of (int y) (int m) (int d))
             (catch java.time.DateTimeException _ nil))))))

(defn- card [db id] (db/record db "debit_cards" id))

(defn- card? [db id] (contains? (db/table db "debit_cards") id))

(defn- account? [db id] (contains? (db/table db "accounts") id))

(defn- set-card [db id & kvs]
  (reduce (fn [db [k v]] (db/set-field db "debit_cards" id k v)) db (partition 2 kvs)))

(defn- lines [parts] (str/join "\n" parts))

;; ---------------------------------------------------------------------------
;; order_debit_card_5739

(defn- issue-reason-for-new-card [db account-id]
  (let [existing (filter #(py/py-eq (get % "account_id") account-id)
                         (vals (db/table db "debit_cards")))
        closed (first (filter #(= "CLOSED" (get % "status")) existing))]
    (cond
      (py/truthy? closed)
      (let [reason (get closed "closure_reason" "first_card")]
        (if (py/py-in reason ["lost" "stolen" "fraud" "fraud_suspected"])
          (if (not= reason "fraud_suspected") reason "fraud")
          "first_card"))
      (seq existing) "first_card"
      :else "new_account")))

(defn- expiration-from [order-date]
  (let [parts (str/split order-date #"/" -1)]
    (if (< (count parts) 3)
      "12/31/2029"
      (let [exp-month (parts 0)
            year (try (py/py-int (parts 2))
                      (catch clojure.lang.ExceptionInfo _ nil))]
        (if (nil? year)
          "12/31/2029"
          (str exp-month "/"
               (cond (#{"01" "03" "05" "07" "08" "10" "12"} exp-month) "31"
                     (= "02" exp-month) "28"
                     :else "30")
               "/" (+ year 4)))))))

(defn- digits-prefix [s n]
  (py/zfill (apply str (take n (filter #(Character/isDigit ^char %) s))) n))

(defn- order-debit-card
  [db {:strs [account_id user_id delivery_option delivery_fee card_design design_fee
              shipping_address excess_replacement_fee]}]
  (let [excess (if (py/truthy? excess_replacement_fee) excess_replacement_fee 0)
        dfee (when (some? delivery_fee) (try-float delivery_fee))
        gfee (when (some? design_fee) (try-float design_fee))]
    (cond
      (and (some? delivery_fee) (nil? dfee)) "Error: delivery_fee must be a number."
      (and (some? design_fee) (nil? gfee)) "Error: design_fee must be a number."
      :else
      (let [dfee (first dfee)
            gfee (first gfee)
            excess (if-let [[v] (try-float excess)] v 0)]
        (if-not (and (py/truthy? account_id) (py/truthy? user_id) (py/truthy? delivery_option)
                     (some? dfee) (py/truthy? card_design) (some? gfee)
                     (py/truthy? shipping_address))
          "Error: Missing required parameters. Required: account_id, user_id, delivery_option, delivery_fee, card_design, design_fee, shipping_address."
          (let [valid-delivery ["STANDARD" "EXPEDITED" "RUSH"]
                valid-design ["CLASSIC" "PREMIUM" "CUSTOM"]]
            (cond
              (not (some #{(py-upper delivery_option)} valid-delivery))
              (str "Error: Invalid delivery_option. Must be one of: " (py/py-repr valid-delivery))
              (not (some #{(py-upper card_design)} valid-design))
              (str "Error: Invalid card_design. Must be one of: " (py/py-repr valid-design))
              (not (account? db account_id))
              (str "Error: Account '" (py/py-str account_id) "' not found.")
              :else
              (let [delivery-option (py-upper delivery_option)
                    card-design (py-upper card_design)
                    account (db/record db "accounts" account_id)
                    holdings (or (first (try-float (str/replace (py/py-str (get account "current_holdings" "0"))
                                                                "," "")))
                                 0.0)]
                (cond
                  (not= "checking" (get account "class"))
                  (str "Error: Debit cards can only be ordered for checking accounts. Account '"
                       (py/py-str account_id) "' is a " (py/py-str (get account "class")) " account.")
                  (not= "OPEN" (get account "status"))
                  (str "Error: Account must be OPEN. Account '" (py/py-str account_id)
                       "' has status: " (py/py-str (get account "status")))
                  (not (py/py-eq (get account "user_id") user_id))
                  (str "Error: Account '" (py/py-str account_id) "' does not belong to user '"
                       (py/py-str user_id) "'.")
                  (< holdings 25.0)
                  (str "Error: Account must have a minimum balance of $25. Current balance: $"
                       (py/format-fixed holdings 2))
                  (some #(and (py/py-eq (get % "account_id") account_id) (= "PENDING" (get % "status")))
                        (vals (db/table db "debit_card_orders")))
                  (str "Error: There is already a pending debit card order for account '"
                       (py/py-str account_id) "'.")
                  (some #(and (py/py-eq (get % "account_id") account_id) (= "ACTIVE" (get % "status")))
                        (vals (db/table db "debit_cards")))
                  (str "Error: Account '" (py/py-str account_id)
                       "' already has an active debit card. Maximum 1 active card per checking account.")
                  :else
                  (let [total (+ (+ dfee gfee) excess)]
                    (if (and (> total 0) (< holdings total))
                      (str "Error: Insufficient funds for fees. Total fees: $" (py/format-fixed total 2)
                           ". Current balance: $" (py/format-fixed holdings 2))
                      (let [expected ({"STANDARD" "7-10 business days"
                                       "EXPEDITED" "3-5 business days"
                                       "RUSH" "1-2 business days"} delivery-option)
                            order-date db/today-str
                            order-id (db/debit-card-order-id account_id user_id delivery-option)
                            order-record (ordered "order_id" order-id
                                                  "account_id" account_id
                                                  "user_id" user_id
                                                  "delivery_option" delivery-option
                                                  "card_design" card-design
                                                  "shipping_address" shipping_address
                                                  "delivery_fee" dfee
                                                  "design_fee" gfee
                                                  "excess_replacement_fee" excess
                                                  "total_fee" total
                                                  "order_date" order-date
                                                  "expected_delivery" expected
                                                  "status" "PENDING")
                            [db ok?] (db/add-record db "debit_card_orders" order-id order-record)]
                        (if-not ok?
                          "Error: Failed to create debit card order. Order may already exist."
                          (let [db (if (> total 0)
                                     (let [parts (cond-> []
                                                   (> dfee 0) (conj (str "Delivery $" (py/py-str dfee)))
                                                   (> gfee 0) (conj (str "Design $" (py/py-str gfee)))
                                                   (> excess 0) (conj (str "Excess Replacement $"
                                                                           (py/format-fixed excess 0))))
                                           fee-txn-id (str "btxn_dcfee_" (subs order-id (- (count order-id) 8)))
                                           db (db/set-field db "accounts" account_id "current_holdings"
                                                            (py/format-fixed (- holdings total) 2))]
                                       (first (db/add-record db "bank_account_transaction_history" fee-txn-id
                                                             (ordered "transaction_id" fee-txn-id
                                                                      "account_id" account_id
                                                                      "date" order-date
                                                                      "description" (str "DEBIT CARD ORDER FEE - "
                                                                                         (str/join ", " parts))
                                                                      "amount" (- total)
                                                                      "type" "debit_card_fee"
                                                                      "status" "posted"))))
                                     db)
                                card-id (db/debit-card-id account_id user_id order-date)
                                seed (str "card_details:" card-id)
                                last4 (digits-prefix (db/deterministic-id (str seed ":last4") 8) 4)
                                cvv (digits-prefix (db/deterministic-id (str seed ":cvv") 6) 3)
                                cardholder (if (contains? (db/table db "users") user_id)
                                             (py-upper (get (db/record db "users" user_id) "name" "CARDHOLDER"))
                                             "CARDHOLDER")
                                issue-reason (issue-reason-for-new-card db account_id)
                                [db _] (db/add-record db "debit_cards" card-id
                                                      (ordered "card_id" card-id
                                                               "account_id" account_id
                                                               "user_id" user_id
                                                               "cardholder_name" cardholder
                                                               "last_4_digits" last4
                                                               "cvv" cvv
                                                               "status" "PENDING"
                                                               "issue_date" order-date
                                                               "expiration_date" (expiration-from order-date)
                                                               "card_design" card-design
                                                               "issue_reason" issue-reason))
                                fee-lines
                                (if (> total 0)
                                  (let [details (cond-> []
                                                  (> dfee 0) (conj (str "Delivery: $" (py/py-str dfee)))
                                                  (> gfee 0) (conj (str "Design: $" (py/py-str gfee)))
                                                  (> excess 0) (conj (str "Excess Replacement: $"
                                                                          (py/format-fixed excess 0))))]
                                    [(str "Total Fees: $" (py/format-fixed total 2) " ("
                                          (str/join ", " details) ") - CHARGED to account "
                                          (py/py-str account_id))
                                     (str "New Account Balance: $" (py/format-fixed (- holdings total) 2))])
                                  ["Total Fees: $0 (No additional charges)"])]
                            {:db db
                             :result (lines (concat
                                             ["Debit Card Order Confirmed"
                                              (str "Order ID: " order-id)
                                              (str "Card ID: " card-id)
                                              (str "Linked Account: " (py/py-str account_id))
                                              (str "Delivery Option: " delivery-option)
                                              (str "Card Design: " card-design)
                                              (str "Shipping Address: " (py/py-str shipping_address))
                                              (str "Expected Delivery: " expected)
                                              ""
                                              "Note: Card will arrive with status PENDING. Customer must call to activate after receiving the card."]
                                             fee-lines))}))))))))))))))

;; ---------------------------------------------------------------------------
;; Activation

(defn- other-active-cards [db card-id account-id]
  (for [[oid oc] (db/table db "debit_cards")
        :when (and (not= oid card-id)
                   (py/py-eq (get oc "account_id") account-id)
                   (= "ACTIVE" (get oc "status")))]
    oid))

(defn- activate
  "Shared activation flow: validate, activate, then transition the other
   ACTIVE cards of the account with `(retire db card-id issue-reason)`."
  [db args allowed tool-name reason-default retire render]
  (let [[error c] (validate-activation args db allowed tool-name)]
    (if error
      error
      (let [card-id (get args "card_id")
            account-id (get c "account_id")
            issue-reason (get c "issue_reason" reason-default)
            db (set-card db card-id "status" "ACTIVE" "activated_date" db/today-str)
            others (vec (other-active-cards db card-id account-id))
            db (reduce #(retire %1 %2 issue-reason) db others)]
        {:db db :result (render card-id issue-reason others)}))))

(defn- reason-title [r] (py/title (str/replace r "_" " ")))

(defn- activate-8291 [db args]
  (activate db args ["new_account" "first_card"] "activate_debit_card_8291" nil
            (fn [db oid _]
              (set-card db oid "status" "DEACTIVATED" "deactivated_date" db/today-str
                        "deactivation_reason" "New card activated"))
            (fn [card-id _ others]
              (lines (cond-> ["New Debit Card Activation Successful"
                              (str "Card ID: " card-id)
                              "Status: ACTIVE"
                              (str "Activation Date: " db/today-str)
                              ""
                              "Your card is now ready to use at any ATM or point of sale terminal."
                              "For security, please sign the back of your card."]
                       (seq others)
                       (conj (str "\nNote: Previous card(s) have been deactivated: "
                                  (str/join ", " others))))))))

(defn- activate-8292 [db args]
  (activate db args ["lost" "stolen" "fraud"] "activate_debit_card_8292" "lost"
            (fn [db oid reason]
              (set-card db oid "status" "DEACTIVATED" "deactivated_date" db/today-str
                        "deactivation_reason" (str "Replacement card activated (" reason ")")))
            (fn [card-id reason others]
              (lines (cond-> ["Replacement Debit Card Activation Successful"
                              (str "Card ID: " card-id)
                              (str "Replacement Reason: " (reason-title reason))
                              "Status: ACTIVE"
                              (str "Activation Date: " db/today-str)
                              ""
                              "Your replacement card is now ready to use."
                              ""
                              "IMPORTANT SECURITY REMINDERS:"
                              "- Please review your recent transactions for any unauthorized charges"
                              "- Report any suspicious activity immediately"]
                       (= reason "fraud")
                       (conj "- Since fraud was suspected, we recommend changing your online banking password")
                       (seq others)
                       (conj (str "\nPrevious card(s) have been deactivated for security: "
                                  (str/join ", " others))))))))

(defn- activate-8293 [db args]
  (activate db args ["expired" "damaged" "upgrade" "bank_reissue"] "activate_debit_card_8293" "expired"
            (fn [db oid reason]
              (set-card db oid "status" "GRACE_PERIOD" "grace_period_ends" db/today-str
                        "deactivation_reason" (str "Reissued card activated (" reason ")")))
            (fn [card-id reason others]
              (lines (cond-> ["Reissued Debit Card Activation Successful"
                              (str "Card ID: " card-id)
                              (str "Reissue Reason: " (reason-title reason))
                              "Status: ACTIVE"
                              (str "Activation Date: " db/today-str)
                              ""
                              "Your reissued card is now ready to use."]
                       (seq others)
                       (conj (str "\nNote: Your previous card(s) (" (str/join ", " others)
                                  ") will remain active for 24 hours as a grace period.")
                             "After 24 hours, the old card(s) will be automatically deactivated.")
                       (#{"expired" "bank_reissue"} reason)
                       (conj "\nReminder: If your card number changed, please update any recurring payments with your new card details."))))))

;; ---------------------------------------------------------------------------
;; Close / freeze / unfreeze / fraud alert

(defn- close-card [db {:strs [card_id reason]}]
  (if (or (not (py/truthy? card_id)) (not (py/truthy? reason)))
    "Error: Missing required parameters. Required: card_id, reason."
    (let [valid ["lost" "stolen" "fraud_suspected" "damaged" "no_longer_needed" "account_closing"]]
      (cond
        (not (some #{(py-lower reason)} valid))
        (str "Error: Invalid reason. Must be one of: " (py/py-repr valid))
        (not (card? db card_id))
        (str "Error: Debit card '" (py/py-str card_id) "' not found.")
        (not (#{"ACTIVE" "PENDING"} (get (card db card_id) "status")))
        (str "Error: Debit card '" card_id "' cannot be closed. Current status: "
             (py/py-str (get (card db card_id) "status")) ". Only ACTIVE or PENDING cards can be closed.")
        :else
        (let [reason (py-lower reason)
              previous (get (card db card_id) "status")]
          {:db (set-card db card_id "status" "CLOSED" "closed_date" db/today-str "closure_reason" reason)
           :result (lines (concat
                           ["Debit Card Closed Successfully"
                            (str "Card ID: " card_id)
                            (str "Previous Status: " previous)
                            "New Status: CLOSED"
                            (str "Closure Reason: " (reason-title reason))
                            (str "Closure Date: " db/today-str)
                            ""]
                           (when (#{"lost" "stolen" "fraud_suspected"} reason)
                             (concat ["IMPORTANT: This card has been immediately deactivated for security."
                                      "Any pending transactions may still be processed."]
                                     (when (= reason "fraud_suspected")
                                       ["Please advise the customer to review recent transactions and file disputes for any unauthorized charges."
                                        "Also recommend changing their online banking password."])))
                           [""
                            "Note: This card cannot be reactivated. If the customer needs a new card, they can order one through the standard ordering process."
                            "Any recurring payments linked to this card will need to be updated with new payment information."]))})))))

(defn- freeze-card [db {:strs [card_id]}]
  (cond
    (not (py/truthy? card_id)) "Error: Missing required parameter: card_id."
    (not (card? db card_id)) (str "Error: Debit card '" (py/py-str card_id) "' not found.")
    (= "FROZEN" (get (card db card_id) "status")) (str "Error: Debit card '" card_id "' is already frozen.")
    (not= "ACTIVE" (get (card db card_id) "status"))
    (str "Error: Debit card '" card_id "' cannot be frozen. Current status: "
         (py/py-str (get (card db card_id) "status")) ". Only ACTIVE cards can be frozen.")
    :else
    {:db (set-card db card_id "status" "FROZEN" "frozen_date" db/today-str)
     :result (lines ["Debit Card Frozen Successfully"
                     (str "Card ID: " card_id)
                     "Status: FROZEN"
                     (str "Frozen Date: " db/today-str)
                     ""
                     "While frozen:"
                     "- All new purchase transactions will be declined"
                     "- Recurring payments and subscriptions will be declined"
                     "- Pending transactions already authorized may still process"
                     ""
                     "To unfreeze, the customer can call customer service or use the mobile app."
                     "If the card is confirmed lost or stolen, recommend closing the card permanently instead."])}))

(defn- unfreeze-card [db {:strs [card_id]}]
  (cond
    (not (py/truthy? card_id)) "Error: Missing required parameter: card_id."
    (not (card? db card_id)) (str "Error: Debit card '" (py/py-str card_id) "' not found.")
    (= "ACTIVE" (get (card db card_id) "status")) (str "Error: Debit card '" card_id "' is already active.")
    (not= "FROZEN" (get (card db card_id) "status"))
    (str "Error: Debit card '" card_id "' cannot be unfrozen. Current status: "
         (py/py-str (get (card db card_id) "status")) ". Only FROZEN cards can be unfrozen.")
    :else
    (let [account-id (get (card db card_id) "account_id")]
      (if (and (py/truthy? account-id) (account? db account-id)
               (not= "OPEN" (get (db/record db "accounts" account-id) "status")))
        (str "Error: The linked checking account '" (py/py-str account-id)
             "' is no longer open. Card cannot be unfrozen.")
        {:db (set-card db card_id "status" "ACTIVE" "unfrozen_date" db/today-str)
         :result (lines ["Debit Card Unfrozen Successfully"
                         (str "Card ID: " card_id)
                         "Status: ACTIVE"
                         (str "Unfrozen Date: " db/today-str)
                         ""
                         "The card is now active and ready to use immediately."
                         "All transactions will process normally."])}))))

(defn- clear-fraud-alert [db {:strs [card_id reason]}]
  (cond
    (not (py/truthy? card_id)) "Error: Missing required parameter: card_id."
    (not (py/truthy? reason)) "Error: Missing required parameter: reason."
    (not (py/py-in reason ["customer_verified" "velocity_clear"]))
    (str "Error: Invalid reason '" (py/py-str reason)
         "'. Must be one of: customer_verified, velocity_clear")
    (not (card? db card_id)) (str "Error: Debit card '" (py/py-str card_id) "' not found.")
    (= reason "velocity_clear")
    (if-not (py/truthy? (get (card db card_id) "velocity_blocked" false))
      (str "Error: Debit card '" card_id "' does not have an active velocity block.")
      {:db (set-card db card_id "velocity_blocked" false "velocity_cleared_date" db/today-str)
       :result (lines ["Velocity Block Cleared Successfully"
                       (str "Card ID: " card_id)
                       (str "Cleared Date: " db/today-str)
                       ""
                       "The card is now unblocked and ready for normal use."
                       "The velocity monitoring will continue - if the same unusual patterns recur,"
                       "the card may be blocked again automatically."])})
    :else
    (let [c (card db card_id)]
      (cond
        (not (py/truthy? (get c "fraud_alert_active" false)))
        (str "Error: Debit card '" card_id "' does not have an active fraud alert.")
        (= "bank_initiated" (get c "alert_source"))
        "Error: BANK_INITIATED_ALERT - This fraud alert was initiated by the bank's fraud detection system and cannot be cleared by customer service agents. Please transfer the customer to the security team using transfer_to_human_agents."
        :else
        {:db (set-card db card_id "fraud_alert_active" false "alert_source" nil
                       "fraud_alert_cleared_date" db/today-str)
         :result (lines ["Fraud Alert Cleared Successfully"
                         (str "Card ID: " card_id)
                         (str "Cleared Date: " db/today-str)
                         ""
                         "The fraud alert has been removed from the card."
                         "All transactions will process normally."
                         ""
                         "Remind the customer to review recent transactions and report any unauthorized charges."])}))))

;; ---------------------------------------------------------------------------
;; PINs

(defn- reset-pin [db {:strs [card_id last_4_digits new_pin]}]
  (cond
    (not (every? py/truthy? [card_id last_4_digits new_pin]))
    "Error: Missing required parameters. Required: card_id, last_4_digits, new_pin."
    (or (not (py-isdigit? last_4_digits)) (not= 4 (count last_4_digits)))
    "Error: Last 4 digits must be exactly 4 digits."
    :else
    (if-let [pin-error (validate-pin new_pin)]
      (str "Error: " pin-error)
      (cond
        (not (card? db card_id)) (str "Error: Debit card '" (py/py-str card_id) "' not found.")
        (not (py/py-eq (get (card db card_id) "last_4_digits") last_4_digits))
        "Error: Card verification failed. The last 4 digits do not match our records."
        (not= "ACTIVE" (get (card db card_id) "status"))
        (str "Error: Cannot reset PIN. Card status is " (py/py-str (get (card db card_id) "status"))
             ". Only ACTIVE cards can have their PIN reset.")
        :else
        {:db (set-card db card_id "pin_last_changed" db/today-str "pin_locked" false
                       "pin_attempts_remaining" 3)
         :result (lines ["Debit Card PIN Reset Successfully"
                         (str "Card ID: " card_id)
                         (str "PIN Changed: " db/today-str)
                         ""
                         "The new PIN is effective immediately."
                         "Your card has been unlocked and is ready to use."
                         "Customer can use the new PIN for ATM withdrawals and point-of-sale transactions."
                         ""
                         "Security reminder: Never share your PIN with anyone."])}))))

(defn- change-pin [db {:strs [card_id current_pin new_pin]}]
  (cond
    (not (every? py/truthy? [card_id current_pin new_pin]))
    "Error: Missing required parameters. Required: card_id, current_pin, new_pin."
    (or (not (py-isdigit? current_pin)) (not= 4 (count current_pin)))
    "Error: Current PIN must be exactly 4 digits."
    :else
    (if-let [pin-error (validate-pin new_pin)]
      (str "Error: " pin-error)
      (cond
        (= current_pin new_pin) "Error: New PIN must be different from current PIN."
        (not (card? db card_id)) (str "Error: Debit card '" (py/py-str card_id) "' not found.")
        (not= "ACTIVE" (get (card db card_id) "status"))
        (str "Error: Cannot change PIN. Card status is " (py/py-str (get (card db card_id) "status"))
             ". Only ACTIVE cards can have their PIN changed.")
        :else
        {:db (set-card db card_id "pin_last_changed" db/today-str)
         :result (lines ["Debit Card PIN Changed Successfully"
                         (str "Card ID: " card_id)
                         (str "PIN Changed: " db/today-str)
                         ""
                         "The new PIN is effective immediately."
                         "Customer can use the new PIN for ATM withdrawals and point-of-sale transactions."
                         ""
                         "Security reminder: Never share your PIN with anyone."])}))))

;; ---------------------------------------------------------------------------
;; get_debit_cards_by_account_id_7823

(defn- rename-last [m from to]
  (db/assoc-ordered (dissoc-ordered m from) to (get m from)))

(defn- card-info [card-id c]
  (let [info (reduce (fn [m [k v]] (db/assoc-ordered m k v)) (array-map "card_id" card-id) c)
        info (if (and (contains? info "last_4_digits") (not (contains? info "card_number_last_4")))
               (rename-last info "last_4_digits" "card_number_last_4")
               info)]
    (cond
      (and (contains? info "issue_date") (not (contains? info "date_issued")))
      (rename-last info "issue_date" "date_issued")
      (and (contains? info "created_date") (not (contains? info "date_issued")))
      (rename-last info "created_date" "date_issued")
      :else info)))

(defn- get-debit-cards [db {:strs [account_id]}]
  (cond
    (not (py/truthy? account_id)) "Error: Missing required parameter 'account_id'."
    (not (account? db account_id)) (str "Error: Account '" (py/py-str account_id) "' not found.")
    (not (#{"checking" "business_checking"}
          (py-lower (get (db/record db "accounts" account_id) "class" ""))))
    (str "Error: Account '" account_id
         "' is not a checking account. Debit cards are only available for checking accounts.")
    :else
    (let [cards (for [[cid c] (db/table db "debit_cards")
                      :when (py/py-eq (get c "account_id") account_id)]
                  (card-info cid c))]
      (if (empty? cards)
        (str "No debit cards found for account '" account_id "'.")
        (py/dumps-indent
         (vec (sort-by #(let [d (get % "date_issued")] (if (py/truthy? d) d ""))
                       #(compare %2 %1)
                       cards)))))))

;; ---------------------------------------------------------------------------
;; request_temporary_debit_card_limit_increase_8374

(def ^:private default-atm-limits
  {"blue account" 500 "green account" 600 "light blue account" 400
   "green fee-free account" 500 "evergreen account" 750 "bluest account" 1500
   "dark green account" 300 "gold years account" 600 "purple account" 1000})

(defn- float-integer? [^double f]
  (and (not (Double/isNaN f)) (not (Double/isInfinite f)) (== f (Math/floor f))))

(defn- limit-increase [db {:strs [card_id limit_type new_limit]}]
  (cond
    (not (py/truthy? card_id)) "Error: Missing required parameter: card_id."
    (not (py/truthy? limit_type)) "Error: Missing required parameter: limit_type."
    (not (py/py-in limit_type ["atm" "purchase"]))
    (str "Error: Invalid limit_type '" (py/py-str limit_type) "'. Must be 'atm' or 'purchase'.")
    (nil? new_limit) "Error: Missing required parameter: new_limit."
    :else
    (let [[f] (try-float new_limit)]
      (cond
        (nil? f) (str "Error: new_limit must be an integer, got '" (py/py-str new_limit) "'.")
        (not (float-integer? f)) (str "Error: new_limit must be an integer, got '" (py/py-str f) "'.")
        :else
        (let [new-limit (py/py-int f)]
          (cond
            (<= new-limit 0) "Error: new_limit must be a positive amount."
            (not (card? db card_id)) (str "Error: Debit card '" (py/py-str card_id) "' not found.")
            (not= "ACTIVE" (get (card db card_id) "status"))
            (str "Error: Debit card '" card_id "' is not active. Current status: "
                 (py/py-str (get (card db card_id) "status"))
                 ". Only ACTIVE cards can have limit increases.")
            :else
            (let [c (card db card_id)
                  account-id (get c "account_id")]
              (if (or (not (py/truthy? account-id)) (not (account? db account-id)))
                (str "Error: Could not find linked account for debit card '" card_id "'.")
                (let [account (db/record db "accounts" account-id)
                      opened-str (get account "date_opened")
                      age (delay (when (py/truthy? opened-str)
                                   (when-let [opened (strptime-mdy opened-str)]
                                     (.between ChronoUnit/DAYS opened (LocalDate/of 2025 11 14)))))]
                  (cond
                    (not= "OPEN" (get account "status"))
                    (str "Error: The linked account '" (py/py-str account-id)
                         "' is not in good standing. Account status: " (py/py-str (get account "status")) ".")
                    (and @age (< @age 60))
                    (str "Error: Account must be at least 60 days old for a temporary limit increase. Account age: "
                         @age " days.")
                    :else
                    (let [atm? (= limit_type "atm")
                          limit-field (if atm? "daily_atm_limit" "daily_purchase_limit")
                          limit-name (if atm? "Daily ATM Withdrawal Limit" "Daily Purchase Limit")
                          card-limit (get c limit-field)
                          level (when (and atm? (nil? card-limit))
                                  (py-lower (get account "level" "")))
                          current (if (and atm? (nil? card-limit))
                                    (when-not (str/includes? level "light green")
                                      (get default-atm-limits level))
                                    card-limit)]
                      (cond
                        (and atm? (nil? card-limit) (str/includes? level "light green"))
                        "Error: Light Green Account (teen checking) cards have policy-based limits that cannot be modified. The daily ATM withdrawal limit of $150 is fixed by account policy for safety reasons. The customer may request the parent/guardian to withdraw cash from their own account if needed."
                        (and atm? (nil? current))
                        (str "Error: Could not determine the default ATM limit for account type '"
                             (py/py-str (get account "level")) "'. Please verify the account type.")
                        (nil? current)
                        (str "Error: The card does not have a " (str/lower-case limit-name)
                             " configured. This may be a restricted account type where limits are set by account policy and cannot be modified.")
                        :else
                        (let [max-allowed (py/py-int (* current 1.5))]
                          (cond
                            (> new-limit max-allowed)
                            (str "Error: Requested limit $" new-limit
                                 " exceeds the maximum allowed temporary increase. Maximum temporary limit is $"
                                 max-allowed " (150% of current $" (py/py-str current) " limit).")
                            (<= new-limit current)
                            (str "Error: Requested limit $" new-limit
                                 " is not higher than the current limit of $" (py/py-str current) ".")
                            :else
                            {:db (set-card db card_id
                                           limit-field new-limit
                                           (str "temporary_" limit_type "_limit_increase") true
                                           (str "original_" limit_type "_limit") current
                                           (str "temporary_" limit_type "_limit_expires") db/today-str)
                             :result (lines [(str "Temporary " limit-name " Increase Granted Successfully")
                                             (str "Card ID: " card_id)
                                             (str "Previous Limit: $" (py/py-str current))
                                             (str "New Temporary Limit: $" new-limit)
                                             (str "Increase Amount: $" (py/py-str (- new-limit current)))
                                             ""
                                             "Important Information:"
                                             "- This temporary increase expires in 24 hours"
                                             (str "- After expiration, the limit will revert to $" (py/py-str current))
                                             "- Only one temporary increase is allowed per 24-hour period"
                                             ""
                                             "Note: If the customer is at a third-party (non-Rho-Bank) ATM, that ATM may have its own"
                                             "per-transaction or daily limits that Rho-Bank cannot override. The customer may need to"
                                             "use a Rho-Bank ATM or make multiple smaller withdrawals at third-party ATMs."])}))))))))))))))

;; ---------------------------------------------------------------------------
;; Tool table

(def ^:private activation-params
  [["card_id"] ["last_4_digits"] ["expiration_date"] ["cvv"] ["pin"]])

(def tools
  [(db/tool {:name "order_debit_card_5739" :type :write :discoverable? true
             :params [["account_id"] ["user_id"] ["delivery_option"] ["delivery_fee"]
                      ["card_design"] ["design_fee"] ["shipping_address"]
                      ["excess_replacement_fee" nil]]
             :fn order-debit-card})
   (db/tool {:name "activate_debit_card_8291" :type :write :discoverable? true
             :params activation-params :fn activate-8291})
   (db/tool {:name "activate_debit_card_8292" :type :write :discoverable? true
             :params activation-params :fn activate-8292})
   (db/tool {:name "activate_debit_card_8293" :type :write :discoverable? true
             :params activation-params :fn activate-8293})
   (db/tool {:name "close_debit_card_4721" :type :write :discoverable? true
             :params [["card_id"] ["reason"]] :fn close-card})
   (db/tool {:name "freeze_debit_card_3892" :type :write :discoverable? true
             :params [["card_id"]] :fn freeze-card})
   (db/tool {:name "unfreeze_debit_card_3893" :type :write :discoverable? true
             :params [["card_id"]] :fn unfreeze-card})
   (db/tool {:name "clear_debit_card_fraud_alert_4892" :type :write :discoverable? true
             :params [["card_id"] ["reason"]] :fn clear-fraud-alert})
   (db/tool {:name "reset_debit_card_pin_6284" :type :write :discoverable? true
             :params [["card_id"] ["last_4_digits"] ["new_pin"]] :fn reset-pin})
   (db/tool {:name "change_debit_card_pin_6285" :type :write :discoverable? true
             :params [["card_id"] ["current_pin"] ["new_pin"]] :fn change-pin})
   (db/tool {:name "get_debit_cards_by_account_id_7823" :type :read :discoverable? true
             :params [["account_id"]] :fn get-debit-cards})
   (db/tool {:name "request_temporary_debit_card_limit_increase_8374" :type :write :discoverable? true
             :params [["card_id"] ["limit_type"] ["new_limit"]] :fn limit-increase})])
