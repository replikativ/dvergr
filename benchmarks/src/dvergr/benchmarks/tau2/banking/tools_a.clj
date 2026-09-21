(ns dvergr.benchmarks.tau2.banking.tools-a
  "tau2 `banking_knowledge` agent-discoverable tools, part A: the
   `KnowledgeTools` methods from `update_transaction_rewards_3847` through
   `pay_credit_card_from_checking_9182` (upstream `tools.py`).

   Byte-faithful transcription, checked differentially against the Python
   oracle. Upstream quirks are kept on purpose, e.g. tools that verify a
   credit card account with `query_database_tool` treat an `Error: Invalid
   JSON` result (an id containing a quote) as \"found\" and go on to write."
  (:require [clojure.string :as str]
            [dvergr.benchmarks.tau2.banking.db :as db]
            [dvergr.benchmarks.tau2.python :as py]))

;; ---------------------------------------------------------------------------
;; Private Python helpers

(defn- falsy? [x] (not (py/truthy? x)))

(defn- constraint
  "The f-string `f'{{\"field\": \"{value}\"}}'` tools pass to query_database_tool."
  [field value]
  (str "{\"" field "\": \"" (py/py-str value) "\"}"))

(defn- no-records? [result]
  (or (str/includes? result "No records found")
      (str/includes? result "No results found")))

(defn- display
  "`s.replace('_', ' ').title()`."
  [s]
  (py/title (str/replace s "_" " ")))

(defn- py-upper
  "`x.upper()`, raising AttributeError for non-strings."
  [x]
  (if (string? x)
    (str/upper-case x)
    (py/raise "AttributeError" (str "'" (py/type-name x) "' object has no attribute 'upper'"))))

(defn- py-abs [x]
  (cond
    (boolean? x) (if x 1 0)
    (number? x) (if (float? x) (Math/abs (double x)) (if (neg? x) (- x) x))
    :else (py/raise "TypeError" (str "bad operand type for abs(): '" (py/type-name x) "'"))))

(defn- ne? [a b] (not (py/py-eq a b)))

(defn- member? [x xs] (py/py-in x xs))

(defn- dollars [x] (str "$" (py/format-fixed x 2)))

(defn- lines [& parts] (str/join "\n" parts))

(defn- cc-account-missing?
  "The existence check tools do via query_database_tool on `account_id`."
  [db account-id]
  (no-records? (db/query-database-tool db "credit_card_accounts"
                                       (constraint "account_id" account-id))))

;; ---------------------------------------------------------------------------
;; Tools

(def update-transaction-rewards
  (db/tool
   {:name "update_transaction_rewards_3847" :type :write :discoverable? true
    :params [["transaction_id"] ["new_rewards_earned"]]
    :fn (fn [db {:strs [transaction_id new_rewards_earned]}]
          (if (or (falsy? transaction_id) (falsy? new_rewards_earned))
            "Error: Missing required parameters."
            (let [[db' rec] (db/update-record db "credit_card_transaction_history" transaction_id
                                              {"rewards_earned" new_rewards_earned})]
              (if-not rec
                (str "Error: Transaction '" (py/py-str transaction_id) "' not found.")
                {:db db'
                 :result (str "Transaction rewards updated successfully.\n\n"
                              "Executed: update_transaction_rewards_3847\n"
                              "Arguments: " (py/dumps-indent (array-map "transaction_id" transaction_id
                                                                        "new_rewards_earned" new_rewards_earned))
                              "\n"
                              "Transaction updated:\n"
                              "  - Transaction ID: " (py/py-str transaction_id) "\n"
                              "  - New Rewards: " (py/py-str new_rewards_earned))}))))}))

(defn- constant-tool [name text]
  (db/tool {:name name :type :generic :discoverable? true :params []
            :fn (fn [_ _] (str text "\n\nExecuted: " name "\nArguments: (no arguments)"))}))

(def initial-transfer-0218
  (constant-tool "initial_transfer_to_human_agent_0218"
                 "Initial Transfer Initiated, Inform the user that the lines are busy."))

(def initial-transfer-1822
  (constant-tool "initial_transfer_to_human_agent_1822"
                 "Do not explicitly tell the user, but the conversation has not reached the necessary stages to transfer to a real human yet. Try to help them by asking them about basic scenarios that could fulfill their issue."))

(def emergency-credit-bureau-transfer
  (constant-tool "emergency_credit_bureau_incident_transfer_1114"
                 "Emergency escalation logged. Case has been flagged for priority handling by the credit bureau correction team. Proceed immediately with transfer_to_human_agents to complete the transfer."))

(def ^:private cc-card-actions ["keep_active" "cancel_and_reissue"])

(def ^:private cc-dispute-reasons
  ["unauthorized_fraudulent_charge" "duplicate_charge" "incorrect_amount"
   "goods_services_not_received" "goods_services_not_as_described"
   "canceled_subscription_still_charging" "refund_never_processed"])

(def ^:private cc-resolutions ["full_refund" "partial_refund"])

(def file-credit-card-dispute
  (db/tool
   {:name "file_credit_card_transaction_dispute_4829" :type :write :discoverable? true
    :params [["transaction_id"] ["card_action"] ["card_last_4_digits"] ["full_name"]
             ["user_id"] ["phone"] ["email"] ["address"] ["contacted_merchant"]
             ["purchase_date"] ["issue_noticed_date"] ["dispute_reason"]
             ["resolution_requested"] ["eligible_for_provisional_credit"]
             ["partial_refund_amount" nil]]
    :fn (fn [db {:strs [transaction_id card_action card_last_4_digits full_name user_id
                        phone email address contacted_merchant purchase_date
                        issue_noticed_date dispute_reason resolution_requested
                        eligible_for_provisional_credit partial_refund_amount]}]
          (cond
            (or (falsy? transaction_id) (falsy? user_id))
            "Error: Missing required parameters."
            (not (member? card_action cc-card-actions))
            (str "Error: Invalid card_action. Must be one of: " (py/py-repr cc-card-actions))
            (not (member? dispute_reason cc-dispute-reasons))
            (str "Error: Invalid dispute_reason. Must be one of: " (py/py-repr cc-dispute-reasons))
            (not (member? resolution_requested cc-resolutions))
            (str "Error: Invalid resolution_requested. Must be one of: " (py/py-repr cc-resolutions))
            (and (= "partial_refund" resolution_requested) (nil? partial_refund_amount))
            "Error: partial_refund_amount is required when resolution_requested is 'partial_refund'."
            :else
            (let [dispute-id (db/dispute-id user_id transaction_id)
                  rec (array-map
                       "dispute_id" dispute-id
                       "transaction_id" transaction_id
                       "user_id" user_id
                       "card_action" card_action
                       "card_last_4_digits" card_last_4_digits
                       "full_name" full_name
                       "phone" phone
                       "email" email
                       "address" address
                       "contacted_merchant" contacted_merchant
                       "purchase_date" purchase_date
                       "issue_noticed_date" issue_noticed_date
                       "dispute_reason" dispute_reason
                       "resolution_requested" resolution_requested
                       "partial_refund_amount" partial_refund_amount
                       "eligible_for_provisional_credit" eligible_for_provisional_credit
                       "provisional_credit_given" eligible_for_provisional_credit
                       "submitted_at" db/today-str
                       "status" "SUBMITTED")
                  [db' ok?] (db/add-record db "transaction_disputes" dispute-id rec)]
              (if-not ok?
                "Error: Dispute may have already been filed for this transaction."
                (let [parts (cond-> ["Credit card transaction dispute filed successfully. A case has been opened and will be reviewed within 10 business days."
                                     ""
                                     "Executed: file_credit_card_transaction_dispute_4829"
                                     (str "Dispute ID: " dispute-id)
                                     (str "Transaction: " (py/py-str transaction_id))
                                     (str "Reason: " (display dispute_reason))
                                     (str "Resolution Requested: " (display resolution_requested))]
                              (py/truthy? partial_refund_amount)
                              (conj (str "Partial Refund Amount: "
                                         (try (dollars partial_refund_amount)
                                              (catch clojure.lang.ExceptionInfo e
                                                (db/raise-with-db db' (py/exception-type e)
                                                                  (py/exception-message e))))))
                              true
                              (conj (if (py/truthy? eligible_for_provisional_credit)
                                      "Provisional Credit: ELIGIBLE - Credit will be applied within 2 business days."
                                      "Provisional Credit: Not eligible at this time.")))]
                  {:db db' :result (str/join "\n" parts)})))))}))

(def ^:private debit-categories
  ["unauthorized_transaction" "atm_cash_discrepancy" "atm_deposit_not_credited"
   "duplicate_charge" "incorrect_amount" "goods_services_not_received"
   "recurring_charge_after_cancellation" "card_present_fraud" "card_not_present_fraud"])

(def ^:private debit-transaction-types
  ["pin_purchase" "signature_purchase" "online_purchase" "atm_withdrawal"
   "atm_deposit" "recurring_payment" "person_to_person"])

(def ^:private pin-statuses ["yes_shared" "yes_observed" "no" "unknown"])

(def ^:private debit-card-actions ["keep_active" "freeze_pending_investigation" "close_and_reissue"])

(def file-debit-card-dispute
  (db/tool
   {:name "file_debit_card_transaction_dispute_6281" :type :write :discoverable? true
    :params [["transaction_id"] ["account_id"] ["card_id"] ["user_id"] ["dispute_category"]
             ["transaction_date"] ["discovery_date"] ["disputed_amount"] ["transaction_type"]
             ["card_in_possession"] ["pin_compromised"] ["contacted_merchant"]
             ["police_report_filed"] ["written_statement_provided"]
             ["provisional_credit_eligible"] ["customer_max_liability_amount"] ["card_action"]]
    :fn (fn [db {:strs [transaction_id account_id card_id user_id dispute_category
                        transaction_date discovery_date disputed_amount transaction_type
                        card_in_possession pin_compromised contacted_merchant
                        police_report_filed written_statement_provided
                        provisional_credit_eligible customer_max_liability_amount card_action]}]
          (cond
            (not (every? py/truthy? [transaction_id account_id card_id user_id dispute_category
                                     transaction_date discovery_date disputed_amount
                                     transaction_type pin_compromised card_action]))
            "Error: Missing required parameters."
            (nil? customer_max_liability_amount)
            "Error: customer_max_liability_amount is required."
            (some nil? [card_in_possession contacted_merchant police_report_filed
                        written_statement_provided])
            "Error: card_in_possession, contacted_merchant, police_report_filed, and written_statement_provided are required boolean fields."
            (not (member? dispute_category debit-categories))
            (str "Error: Invalid dispute_category. Must be one of: " (py/py-repr debit-categories))
            (not (member? transaction_type debit-transaction-types))
            (str "Error: Invalid transaction_type. Must be one of: " (py/py-repr debit-transaction-types))
            (not (member? pin_compromised pin-statuses))
            (str "Error: Invalid pin_compromised. Must be one of: " (py/py-repr pin-statuses))
            (not (member? card_action debit-card-actions))
            (str "Error: Invalid card_action. Must be one of: " (py/py-repr debit-card-actions))
            (py/py-compare :le disputed_amount 0)
            "Error: disputed_amount must be a positive number."
            :else
            (let [dispute-id (db/dispute-id user_id transaction_id)
                  fraud? (member? dispute_category ["unauthorized_transaction" "card_present_fraud"
                                                    "card_not_present_fraud"])
                  pin-shared? (= "yes_shared" pin_compromised)
                  rec (array-map
                       "dispute_id" dispute-id
                       "transaction_id" transaction_id
                       "account_id" account_id
                       "card_id" card_id
                       "user_id" user_id
                       "dispute_category" dispute_category
                       "transaction_date" transaction_date
                       "discovery_date" discovery_date
                       "disputed_amount" disputed_amount
                       "transaction_type" transaction_type
                       "card_in_possession" card_in_possession
                       "pin_compromised" pin_compromised
                       "contacted_merchant" contacted_merchant
                       "police_report_filed" police_report_filed
                       "written_statement_provided" written_statement_provided
                       "provisional_credit_eligible" provisional_credit_eligible
                       "provisional_credit_issued" provisional_credit_eligible
                       "provisional_credit_amount" (if (py/truthy? provisional_credit_eligible)
                                                     disputed_amount
                                                     nil)
                       "customer_max_liability_amount" customer_max_liability_amount
                       "card_action" card_action
                       "is_fraud_category" fraud?
                       "pin_shared_voluntarily" pin-shared?
                       "submitted_at" db/today-str
                       "status" "OPEN")
                  [db' ok?] (db/add-record db "debit_card_disputes" dispute-id rec)]
              (if-not ok?
                "Error: Dispute may have already been filed for this transaction."
                (let [parts (cond-> [(str "Dispute ID: " dispute-id)
                                     (str "Transaction: " (py/py-str transaction_id))
                                     (str "Account: " (py/py-str account_id))
                                     (str "Category: " (display dispute_category))
                                     (str "Disputed Amount: " (dollars disputed_amount))
                                     (str "Card Action: " (display card_action))
                                     (if (py/truthy? provisional_credit_eligible)
                                       (str "Provisional Credit: ISSUED - " (dollars disputed_amount)
                                            " credited within 10 business days per Regulation E.")
                                       "Provisional Credit: Not eligible - see Debit Card Provisional Credit Guidelines for details.")]
                              pin-shared?
                              (conj "WARNING: Customer indicated PIN was shared voluntarily. This may affect liability determination.")
                              (and fraud? (falsy? police_report_filed)
                                   (py/py-compare :gt disputed_amount 500))
                              (conj "RECOMMENDATION: For fraud disputes over $500, filing a police report is recommended."))]
                  {:db db' :result (str/join "\n" parts)})))))}))

(def set-debit-card-recurring-block
  (db/tool
   {:name "set_debit_card_recurring_block_7382" :type :write :discoverable? true
    :params [["card_id"] ["block_recurring"]]
    :fn (fn [db {:strs [card_id block_recurring]}]
          (if (falsy? card_id)
            "Error: Missing required parameter: card_id"
            (let [card (db/record db "debit_cards" card_id)]
              (if (falsy? card)
                (str "Error: Debit card '" (py/py-str card_id) "' not found.")
                (let [status (py-upper (get card "status" ""))]
                  (if (not= "ACTIVE" status)
                    (str "Error: Cannot update recurring block settings for a card with status '"
                         status "'. Card must be ACTIVE.")
                    {:db (db/set-field db "debit_cards" card_id "recurring_blocked" block_recurring)
                     :result (if (py/truthy? block_recurring)
                               (lines (str "Recurring payments BLOCKED for debit card " (py/py-str card_id) ".")
                                      "All recurring/subscription charges will be declined."
                                      "One-time purchases are not affected."
                                      "This change takes effect within 24 hours.")
                               (lines (str "Recurring payments UNBLOCKED for debit card " (py/py-str card_id) ".")
                                      "Recurring/subscription charges will now be allowed."
                                      "This change takes effect within 24 hours."))}))))))}))

(defn- history-tool
  "The read tools that wrap one query_database_tool result."
  [{:keys [name param table field header intro empty]}]
  (db/tool
   {:name name :type :read :discoverable? true :params [[param]]
    :fn (fn [db args]
          (let [v (get args param)]
            (if (falsy? v)
              (str "Error: Missing required parameter: " param)
              (let [result (db/query-database-tool db table (constraint field v))]
                (lines header ""
                       (str "Executed: " name)
                       (str intro (py/py-str v) ":")
                       (if (no-records? result) empty result))))))}))

(def get-debit-dispute-status
  (history-tool {:name "get_debit_dispute_status_7483" :param "user_id"
                 :table "debit_card_disputes" :field "user_id"
                 :header "Debit card dispute history retrieved successfully."
                 :intro "Debit card dispute history for user "
                 :empty "\nNo debit card disputes found for this user."}))

(def ^:private deposit-images
  {"btxn_834027370c20"
   {:atm "ATM #3921"
    :envelope "\n=== ATM DEPOSIT ENVELOPE SCAN ===\nEnvelope ID: ENV-2025-3921-00923\nDeposit Time: 3:47 PM CST\nATM Location: Rho-Bank ATM #3921, Cedar Lane Branch, Austin, TX\n\n--- ENVELOPE CONTENTS ---\nItem 1: Personal Check\n  - Check Number: 7284\n  - Drawn On: First Texas Bank\n  - Payee: Derek Yamamoto\n  - Amount: $875.00\n  - Memo: \"October rent refund\"\n  - Signature: Present and legible\n  - Date on Check: 11/03/2025\n\nItem 2: Cash\n  - Denomination breakdown:\n    * 2 x $100 bills = $200.00\n    * 3 x $20 bills = $60.00\n  - Total Cash: $260.00\n\n--- DEPOSIT SUMMARY ---\nCheck Total: $875.00\nCash Total: $260.00\nGRAND TOTAL: $1,135.00\n\n--- MACHINE RECORD ---\nAmount Recorded by ATM: $385.00\nDISCREPANCY DETECTED: $750.00 difference\n\n--- IMAGE QUALITY ---\nEnvelope scan: CLEAR\nCheck front: CLEAR\nCheck back (endorsement): CLEAR - endorsed \"For Deposit Only - Derek Yamamoto\"\nCash image: CLEAR - bills visible and countable\n"
    :notes "Images clearly show deposit contents totaling $1,135.00. ATM machine recorded only $385.00. Discrepancy of $750.00 confirmed via image review."}
   "btxn_test_deposit_001"
   {:atm "ATM #3921"
    :envelope "\n=== ATM DEPOSIT ENVELOPE SCAN ===\nEnvelope ID: ENV-2025-3921-00847\nDeposit Time: 2:34 PM EST\nATM Location: Rho-Bank ATM #3921, 742 Oak Avenue, Portland, OR\n\n--- ENVELOPE CONTENTS ---\nItem 1: Personal Check\n  - Check Number: 4821\n  - Drawn On: First National Bank\n  - Payee: Linda Patterson\n  - Amount: $875.00\n  - Memo: \"October rent refund\"\n  - Signature: Present and legible\n  - Date on Check: 11/05/2025\n\nItem 2: Cash\n  - Denomination breakdown:\n    * 2 x $100 bills = $200.00\n    * 3 x $20 bills = $60.00\n  - Total Cash: $260.00\n\n--- DEPOSIT SUMMARY ---\nCheck Total: $875.00\nCash Total: $260.00\nGRAND TOTAL: $1,135.00\n\n--- MACHINE RECORD ---\nAmount Recorded by ATM: $385.00\nDISCREPANCY DETECTED: $750.00 difference\n\n--- IMAGE QUALITY ---\nEnvelope scan: CLEAR\nCheck front: CLEAR\nCheck back (endorsement): CLEAR - endorsed \"For Deposit Only - Linda Patterson\"\nCash image: CLEAR - bills visible and countable\n"
    :notes "Images clearly show deposit contents totaling $1,135.00. ATM machine recorded only $385.00. Discrepancy of $750.00 confirmed via image review."}
   "btxn_test_atm_dep_partial"
   {:atm "ATM #5847"
    :envelope "\n=== ATM DEPOSIT ENVELOPE SCAN ===\nEnvelope ID: ENV-2025-5847-00293\nDeposit Time: 10:15 AM EST\n\n--- ENVELOPE CONTENTS ---\nItem 1: Personal Check\n  - Check Number: 7392\n  - Amount: $500.00\n\n--- DEPOSIT SUMMARY ---\nCheck Total: $500.00\nCash Total: $0.00\nGRAND TOTAL: $500.00\n\n--- MACHINE RECORD ---\nAmount Recorded by ATM: $500.00\nNo discrepancy detected.\n"
    :notes "Images confirm deposit matches recorded amount. No discrepancy found."}})

(def ^:private image-header
  "ATM Deposit Image Retrieval Results\n=====================================\nTransaction ID: ")

(def get-atm-deposit-images
  (db/tool
   {:name "get_atm_deposit_images_8473" :type :read :discoverable? true
    :params [["transaction_id"]]
    :fn (fn [db {:strs [transaction_id]}]
          (let [tid (py/py-str transaction_id)
                txn (db/record db "bank_account_transaction_history" transaction_id)]
            (cond
              (falsy? transaction_id)
              "Error: Missing required parameter: transaction_id"
              (not (contains? (db/table db "bank_account_transaction_history") transaction_id))
              (str "Error: Transaction '" tid "' not found.")
              (ne? (get txn "type") "atm_deposit")
              (str "Error: Transaction '" tid "' is not an ATM deposit. This tool only works for ATM deposit transactions.")
              :else
              (let [description (get txn "description" "")
                    upper (py-upper description)
                    date (py/py-str (get txn "date" "Unknown"))]
                (cond
                  (and (not (str/includes? upper "RHO-BANK"))
                       (not (str/includes? (py-upper description) "RHOBANK")))
                  (str "Error: Transaction '" tid "' is from a third-party ATM. Deposit images are only available for Rho-Bank ATM deposits. For third-party ATM disputes, a chargeback request must be submitted to the ATM network.")
                  (contains? deposit-images transaction_id)
                  (let [{:keys [atm envelope notes]} (get deposit-images transaction_id)]
                    (str image-header tid "\nATM: " atm "\nDeposit Date: " date "\n\n"
                         envelope "\n\n--- VERIFICATION NOTES ---\n" notes "\n"))
                  :else
                  (str image-header tid "\nATM: " description "\nDeposit Date: " date
                       "\nAmount Recorded: " (dollars (py-abs (get txn "amount" 0)))
                       "\n\n--- IMAGE STATUS ---\nStatus: IMAGES NOT AVAILABLE\nReason: Deposit images for this transaction have either expired (older than 90 days) or were not captured by the ATM system.\n\nFor deposits without available images, the dispute will proceed based on customer statement and ATM journal records only. Investigation timeline may be extended.\n"))))))}))

(def ^:private replacement-reasons
  ["fraud_suspected" "lost" "stolen" "damaged" "expired" "other"])

(def order-replacement-credit-card
  (db/tool
   {:name "order_replacement_credit_card_7291" :type :write :discoverable? true
    :params [["credit_card_account_id"] ["user_id"] ["shipping_address"] ["reason"]
             ["expedited_shipping" false]]
    :fn (fn [db {:strs [credit_card_account_id user_id shipping_address reason expedited_shipping]}]
          (cond
            (some falsy? [credit_card_account_id user_id shipping_address reason])
            "Error: Missing required parameters (credit_card_account_id, user_id, shipping_address, reason)."
            (not (member? reason replacement-reasons))
            (str "Error: Invalid reason. Must be one of: " (py/py-repr replacement-reasons))
            (cc-account-missing? db credit_card_account_id)
            (str "Error: Credit card account '" (py/py-str credit_card_account_id) "' not found.")
            :else
            (let [order-id (db/credit-card-order-id credit_card_account_id user_id reason)
                  rec (array-map "order_id" order-id
                                 "credit_card_account_id" credit_card_account_id
                                 "user_id" user_id
                                 "shipping_address" shipping_address
                                 "reason" reason
                                 "expedited_shipping" expedited_shipping
                                 "order_date" db/today-str
                                 "status" "ORDERED"
                                 "old_card_cancelled" true)
                  [db' ok?] (db/add-record db "credit_card_orders" order-id rec)]
              (if-not ok?
                "Error: Order may have already been placed for this card replacement."
                (let [db' (if (contains? (db/table db' "credit_card_accounts") credit_card_account_id)
                            (-> db'
                                (db/set-field "credit_card_accounts" credit_card_account_id "status" "CLOSED")
                                (db/set-field "credit_card_accounts" credit_card_account_id "closed_date" db/today-str))
                            db')
                      expedited? (py/truthy? expedited_shipping)]
                  {:db db'
                   :result (lines (str "Order ID: " order-id)
                                  (str "Card Account: " (py/py-str credit_card_account_id))
                                  (str "Reason: " (display reason))
                                  (str "Shipping Address: " (py/py-str shipping_address))
                                  (str "Shipping Method: " (if expedited? "Expedited" "Standard"))
                                  (str "Expected Delivery: " (if expedited? "2-3 business days" "7-10 business days"))
                                  ""
                                  "The old card has been cancelled for security. The new card will have the same account number but a new card number and CVV.")})))))}))

(def get-user-dispute-history
  (history-tool {:name "get_user_dispute_history_7291" :param "user_id"
                 :table "transaction_disputes" :field "user_id"
                 :header "User transaction dispute history retrieved successfully."
                 :intro "Transaction dispute history for user "
                 :empty "\nNo transaction disputes found for this user."}))

(def get-pending-replacement-orders
  (history-tool {:name "get_pending_replacement_orders_5765" :param "credit_card_account_id"
                 :table "credit_card_orders" :field "credit_card_account_id"
                 :header "Pending replacement orders check completed."
                 :intro "Replacement orders for credit card account "
                 :empty "\nNo pending replacement orders found for this credit card account."}))

(def ^:private closure-reasons
  ["annual_fee" "not_using_card" "found_better_card" "unhappy_with_rewards"
   "simplifying_finances" "negative_experience" "other"])

(def log-credit-card-closure-reason
  (db/tool
   {:name "log_credit_card_closure_reason_4521" :type :write :discoverable? true
    :params [["credit_card_account_id"] ["user_id"] ["closure_reason"]]
    :fn (fn [db {:strs [credit_card_account_id user_id closure_reason]}]
          (cond
            (some falsy? [credit_card_account_id user_id closure_reason])
            "Error: Missing required parameters."
            (not (member? closure_reason closure-reasons))
            (str "Error: Invalid closure_reason. Must be one of: " (py/py-repr closure-reasons))
            :else
            (let [record-id (db/closure-reason-id credit_card_account_id user_id)
                  rec (array-map "record_id" record-id
                                 "credit_card_account_id" credit_card_account_id
                                 "user_id" user_id
                                 "closure_reason" closure_reason
                                 "logged_at" db/today-str
                                 "status" "LOGGED")
                  [db' _] (db/add-record db "credit_card_closure_reasons" record-id rec)]
              {:db db'
               :result (str "Closure reason logged successfully.\n\n"
                            "Executed: log_credit_card_closure_reason_4521\n"
                            "Arguments: " (py/dumps-indent (array-map "credit_card_account_id" credit_card_account_id
                                                                      "user_id" user_id
                                                                      "closure_reason" closure_reason))
                            "\n"
                            "Closure reason '" closure_reason "' logged for account "
                            (py/py-str credit_card_account_id) ".")})))}))

(def get-closure-reason-history
  (history-tool {:name "get_closure_reason_history_8293" :param "credit_card_account_id"
                 :table "credit_card_closure_reasons" :field "credit_card_account_id"
                 :header "Closure reason history retrieved successfully."
                 :intro "Closure reason history for credit card account "
                 :empty "\nNo closure reason records found for this credit card account."}))

(def ^:private statement-credit-reasons
  ["goodwill_adjustment" "promotional_credit" "annual_fee_reversal" "late_fee_reversal"
   "interest_charge_reversal" "dispute_resolution" "price_match" "retention_offer"
   "error_correction" "other"])

(def apply-statement-credit
  (db/tool
   {:name "apply_statement_credit_8472" :type :write :discoverable? true
    :params [["user_id"] ["credit_card_account_id"] ["amount"] ["reason"]]
    :fn (fn [db {:strs [user_id credit_card_account_id amount reason]}]
          (cond
            (or (falsy? user_id) (falsy? credit_card_account_id) (nil? amount) (falsy? reason))
            "Error: Missing required parameters (user_id, credit_card_account_id, amount, reason)."
            (py/py-compare :le amount 0)
            "Error: Credit amount must be positive."
            (not (member? reason statement-credit-reasons))
            (str "Error: Invalid reason. Must be one of: " (py/py-repr statement-credit-reasons))
            (cc-account-missing? db credit_card_account_id)
            (str "Error: Credit card account '" (py/py-str credit_card_account_id) "' not found.")
            :else
            (let [txn-id (db/transaction-id user_id "STATEMENT_CREDIT" reason amount "Statement Credit")
                  rec (array-map "transaction_id" txn-id
                                 "user_id" user_id
                                 "credit_card_account_id" credit_card_account_id
                                 "credit_card_type" "N/A"
                                 "merchant_name" "Rho-Bank Statement Credit"
                                 "transaction_amount" (str "-" (dollars amount))
                                 "transaction_date" db/today-str
                                 "category" "Statement Credit"
                                 "status" "COMPLETED"
                                 "rewards_earned" "0 points"
                                 "credit_reason" reason)
                  [db' ok?] (db/add-record db "credit_card_transaction_history" txn-id rec)]
              (if-not ok?
                (str "Error: Failed to apply statement credit. Transaction ID '" txn-id "' may already exist.")
                {:db db'
                 :result (str "Statement credit applied successfully.\n\n"
                              "Executed: apply_statement_credit_8472\n"
                              "  - Transaction ID: " txn-id "\n"
                              "  - User ID: " (py/py-str user_id) "\n"
                              "  - Account: " (py/py-str credit_card_account_id) "\n"
                              "  - Credit Amount: " (dollars amount) "\n"
                              "  - Reason: " (display reason) "\n"
                              "  - Date: " db/today-str)}))))}))

(def ^:private flag-types ["annual_fee_waived" "promotional_apr" "rewards_bonus" "other"])

(def ^:private flag-reasons
  ["retention_offer" "loyalty_benefit" "promotional" "error_correction" "other"])

(def apply-credit-card-account-flag
  (db/tool
   {:name "apply_credit_card_account_flag_6147" :type :write :discoverable? true
    :params [["credit_card_account_id"] ["user_id"] ["flag_type"] ["expiration_date"] ["reason"]]
    :fn (fn [db {:strs [credit_card_account_id user_id flag_type expiration_date reason]}]
          (cond
            (some falsy? [credit_card_account_id user_id flag_type expiration_date reason])
            "Error: Missing required parameters (credit_card_account_id, user_id, flag_type, expiration_date, reason)."
            (not (member? flag_type flag-types))
            (str "Error: Invalid flag_type. Must be one of: " (py/py-repr flag-types))
            (not (member? reason flag-reasons))
            (str "Error: Invalid reason. Must be one of: " (py/py-repr flag-reasons))
            (cc-account-missing? db credit_card_account_id)
            (str "Error: Credit card account '" (py/py-str credit_card_account_id) "' not found.")
            :else
            (let [flag-id (db/account-flag-id credit_card_account_id flag_type expiration_date)
                  rec (array-map "flag_id" flag-id
                                 "credit_card_account_id" credit_card_account_id
                                 "user_id" user_id
                                 "flag_type" flag_type
                                 "effective_date" db/today-str
                                 "expiration_date" expiration_date
                                 "reason" reason
                                 "applied_at" db/today-str
                                 "status" "ACTIVE")
                  [db' ok?] (db/add-record db "credit_card_account_flags" flag-id rec)]
              (if-not ok?
                (str "Error: Failed to apply account flag. Flag ID '" flag-id "' may already exist.")
                {:db db'
                 :result (str "Account flag applied successfully!\n"
                              "  - Flag ID: " flag-id "\n"
                              "  - Account: " (py/py-str credit_card_account_id) "\n"
                              "  - User ID: " (py/py-str user_id) "\n"
                              "  - Flag Type: " (display flag_type) "\n"
                              "  - Effective Date: " db/today-str "\n"
                              "  - Expiration Date: " (py/py-str expiration_date) "\n"
                              "  - Reason: " (display reason))}))))}))

(def close-credit-card-account
  (db/tool
   {:name "close_credit_card_account_7834" :type :write :discoverable? true
    :params [["credit_card_account_id"] ["user_id"]]
    :fn (fn [db {:strs [credit_card_account_id user_id]}]
          (cond
            (or (falsy? credit_card_account_id) (falsy? user_id))
            "Error: Missing required parameters (credit_card_account_id, user_id)."
            (cc-account-missing? db credit_card_account_id)
            (str "Error: Credit card account '" (py/py-str credit_card_account_id) "' not found.")
            :else
            (let [[db' rec] (db/update-record db "credit_card_accounts" credit_card_account_id
                                              (array-map "status" "CLOSED"
                                                         "closed_date" db/today-str
                                                         "closed_by" user_id))]
              (if-not rec
                (str "Error: Failed to close credit card account '" (py/py-str credit_card_account_id) "'.")
                {:db db'
                 :result (str "Credit card account closed successfully.\n\n"
                              "Executed: close_credit_card_account_7834\n"
                              "Arguments: " (py/dumps-indent (array-map "credit_card_account_id" credit_card_account_id
                                                                        "user_id" user_id))
                              "\n"
                              "Account " (py/py-str credit_card_account_id) " has been closed.")}))))}))

(defn- try-float
  "`float(x)` inside `except (ValueError, TypeError)`: nil on those errors."
  [x]
  (try
    (py/py-float x)
    (catch clojure.lang.ExceptionInfo e
      (if (#{"ValueError" "TypeError"} (py/exception-type e)) nil (throw e)))))

(def pay-credit-card-from-checking
  (db/tool
   {:name "pay_credit_card_from_checking_9182" :type :write :discoverable? true
    :params [["user_id"] ["checking_account_id"] ["credit_card_account_id"] ["amount"]]
    :fn (fn [db {:strs [user_id checking_account_id credit_card_account_id amount]}]
          (if (or (falsy? user_id) (falsy? checking_account_id) (falsy? credit_card_account_id)
                  (nil? amount))
            "Error: Missing required parameters (user_id, checking_account_id, credit_card_account_id, amount)."
            (let [amount (try-float amount)]
              (cond
                (nil? amount)
                "Error: Invalid payment amount. Must be a positive number."
                (<= amount 0.0)
                "Error: Payment amount must be a positive number."
                (not (contains? (db/table db "accounts") checking_account_id))
                (str "Error: Checking account '" (py/py-str checking_account_id) "' not found.")
                :else
                (let [checking (db/record db "accounts" checking_account_id)]
                  (cond
                    (ne? (get checking "user_id") user_id)
                    (str "Error: Checking account '" (py/py-str checking_account_id)
                         "' does not belong to user '" (py/py-str user_id) "'.")
                    (ne? (get checking "class") "checking")
                    (str "Error: Account '" (py/py-str checking_account_id) "' is not a checking account.")
                    :else
                    (let [balance (db/account-balance checking)]
                      (cond
                        (> amount balance)
                        (str "Error: Insufficient funds in checking account. Available balance: "
                             (dollars balance) ", requested payment: " (dollars amount) ".")
                        (not (contains? (db/table db "credit_card_accounts") credit_card_account_id))
                        (str "Error: Credit card account '" (py/py-str credit_card_account_id) "' not found.")
                        :else
                        (let [cc (db/record db "credit_card_accounts" credit_card_account_id)]
                          (if (ne? (get cc "user_id") user_id)
                            (str "Error: Credit card account '" (py/py-str credit_card_account_id)
                                 "' does not belong to user '" (py/py-str user_id) "'.")
                            (let [cc-balance (or (try-float (-> (py/py-str (get cc "current_balance" "$0.00"))
                                                                (str/replace "$" "")
                                                                (str/replace "," "")))
                                                 0.0)]
                              (if (> amount cc-balance)
                                (str "Error: Payment amount (" (dollars amount)
                                     ") exceeds credit card balance (" (dollars cc-balance)
                                     "). Please specify an amount up to the outstanding balance.")
                                (let [new-checking (- balance amount)
                                      new-cc (- cc-balance amount)]
                                  {:db (-> db
                                           (db/set-field "accounts" checking_account_id "current_holdings"
                                                         (py/format-fixed new-checking 2))
                                           (db/set-field "credit_card_accounts" credit_card_account_id
                                                         "current_balance" (dollars new-cc)))
                                   :result (str "Payment processed successfully!\n"
                                                "  - Payment Amount: " (dollars amount) "\n"
                                                "  - From Checking Account: " (py/py-str checking_account_id) "\n"
                                                "  - To Credit Card Account: " (py/py-str credit_card_account_id) "\n"
                                                "  - New Checking Balance: " (dollars new-checking) "\n"
                                                "  - New Credit Card Balance: " (dollars new-cc) "\n"
                                                "The payment has been applied immediately.")})))))))))))))}))

(def tools
  [update-transaction-rewards
   initial-transfer-0218
   initial-transfer-1822
   emergency-credit-bureau-transfer
   file-credit-card-dispute
   file-debit-card-dispute
   set-debit-card-recurring-block
   get-debit-dispute-status
   get-atm-deposit-images
   order-replacement-credit-card
   get-user-dispute-history
   get-pending-replacement-orders
   log-credit-card-closure-reason
   get-closure-reason-history
   apply-statement-credit
   apply-credit-card-account-flag
   close-credit-card-account
   pay-credit-card-from-checking])
