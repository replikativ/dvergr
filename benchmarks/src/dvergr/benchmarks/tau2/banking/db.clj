(ns dvergr.benchmarks.tau2.banking.db
  "Shared state and helpers for the tau2 `banking_knowledge` transcription.

   The TransactionalDB is plain data: an insertion-ordered map from table
   name to `{\"data\" {record-id record} \"notes\" str}`, with records as
   insertion-ordered string-keyed maps holding JSON values. Every helper is a
   pure function of the db value; writers return the new db.

   Tool definitions (see `tool`) are maps

     {:name \"tool_name\" :owner \"KnowledgeTools\"
      :params [[\"user_id\"] [\"reason\" \"other\"]]  ; [name] or [name default]
      :type :read|:write|:generic :mutates? bool :discoverable? bool
      :fn (fn [db args] -> string | {:db db' :result string})}

   `args` contains every parameter (defaults filled) under its string name.
   Python exceptions are raised with `dvergr.benchmarks.python/raise`.

   Upstream: `src/tau2/domains/banking_knowledge/{data_model,db_query,utils,tools}.py`."
  (:require [clojure.string :as str]
            [dvergr.benchmarks.pyjson :as pj]
            [dvergr.benchmarks.python :as py]))

;; ---------------------------------------------------------------------------
;; Schema

(def table-names
  "TransactionalDB field declaration order (model_dump order)."
  ["users" "accounts" "debit_cards" "referrals" "credit_card_applications"
   "user_discoverable_tools" "user_discoverable_tool_calls" "verification_history"
   "credit_card_transaction_history" "cash_back_disputes"
   "bank_account_transaction_history" "credit_card_accounts"
   "agent_discoverable_tools" "task_config" "human_transfer_requests"
   "transaction_disputes" "credit_card_orders" "debit_card_orders"
   "credit_card_closure_reasons" "credit_card_account_flags"
   "credit_limit_increase_requests" "payment_history" "debit_card_disputes"])

(def queryable-tables
  "`db_query._load_databases` order (shown in `Available: [...]` errors)."
  ["users" "accounts" "referrals" "credit_card_applications"
   "user_discoverable_tools" "user_discoverable_tool_calls"
   "agent_discoverable_tools" "task_config" "verification_history"
   "credit_card_transaction_history" "cash_back_disputes"
   "bank_account_transaction_history" "credit_card_accounts"
   "human_transfer_requests" "transaction_disputes" "credit_card_orders"
   "credit_card_closure_reasons" "credit_card_account_flags" "debit_cards"
   "debit_card_orders" "debit_card_disputes" "credit_limit_increase_requests"
   "payment_history"])

(defn- ordered [& kvs] (apply array-map kvs))

(defn assoc-ordered
  "Python `d[k] = v`: keeps insertion order for maps of any size. Plain
   `assoc` turns array maps larger than 8 entries into hash maps, which would
   reorder tool output; always use this (or `set-field`) on records."
  [m k v]
  (if (contains? m k)
    (if (instance? clojure.lang.PersistentArrayMap m)
      (assoc m k v)
      (apply array-map (mapcat (fn [[k' v']] [k' (if (= k' k) v v')]) m)))
    (apply array-map (concat (mapcat identity m) [k v]))))

(defn- dissoc-ordered [m k]
  (apply array-map (mapcat identity (remove #(= k (key %)) m))))

(defn normalize-db
  "pydantic `TransactionalDB.model_validate` + `model_dump` of raw data."
  [raw]
  (when-let [extra (seq (remove (set table-names) (keys raw)))]
    (throw (ex-info "Unknown TransactionalDB tables" {:tables (set extra)})))
  (apply array-map
         (mapcat (fn [t]
                   (let [table (get raw t)]
                     [t (ordered "data" (or (get table "data") (array-map))
                                 "notes" (or (get table "notes") ""))]))
                 table-names)))

(defn deep-update
  "addict `Dict.update`: nested dicts merge recursively, other values replace."
  [base update]
  (reduce (fn [acc [k v]]
            (let [old (get acc k)]
              (assoc-ordered acc k (if (and (map? old) (map? v))
                                     (deep-update old v)
                                     v))))
          (or base (array-map))
          update))

(defn db-hash [db] (pj/dict-hash db))

;; ---------------------------------------------------------------------------
;; Table access and writes

(defn table [db name] (get-in db [name "data"]))

(defn record [db name id] (get-in db [name "data" id]))

(defn add-record
  "`add_to_db`: returns `[db' success?]`; existing ids are left untouched."
  [db name id rec]
  (if (or (not (contains? db name)) (contains? (table db name) id))
    [db false]
    [(update-in db [name "data"] assoc-ordered id rec) true]))

(defn put-record
  "Python `table.data[id] = rec` (replace or append)."
  [db name id rec]
  (update-in db [name "data"] assoc-ordered id rec))

(defn update-record
  "`update_record_in_db`: returns `[db' updated-record-or-nil]`."
  [db name id updates]
  (if-let [rec (record db name id)]
    (let [rec (reduce (fn [r [k v]] (assoc-ordered r k v)) rec updates)]
      [(put-record db name id rec) rec])
    [db nil]))

(defn set-field
  "Python `table.data[id][field] = value`."
  [db name id field value]
  (put-record db name id (assoc-ordered (record db name id) field value)))

(defn remove-record [db name id]
  (update-in db [name "data"] dissoc-ordered id))

;; ---------------------------------------------------------------------------
;; Query (db_query.py)

(defn- constraint-op [k]
  (if (str/includes? k "__")
    (let [i (.lastIndexOf ^String k "__")] [(subs k 0 i) (subs k (+ i 2))])
    [k "eq"]))

(defn- matches-constraint? [actual op expected]
  (try
    (case op
      "eq" (py/py-eq actual expected)
      "ne" (not (py/py-eq actual expected))
      "gt" (py/py-compare :gt actual expected)
      "gte" (py/py-compare :ge actual expected)
      "lt" (py/py-compare :lt actual expected)
      "lte" (py/py-compare :le actual expected)
      "contains" (if (nil? actual) false (py/py-in expected actual))
      "startswith" (if (nil? actual) false (str/starts-with? (py/py-str actual) (py/py-str expected)))
      "endswith" (if (nil? actual) false (str/ends-with? (py/py-str actual) (py/py-str expected)))
      "in" (py/py-in actual expected)
      "nin" (not (py/py-in actual expected))
      (py/py-eq actual expected))
    (catch clojure.lang.ExceptionInfo e
      (if (#{"TypeError" "ValueError"} (py/exception-type e)) false (throw e)))))

(defn record-matches? [rec constraints]
  (every? (fn [[k v]]
            (let [[field op] (constraint-op k)]
              (matches-constraint? (get rec field) op v)))
          constraints))

(defn query
  "`query_db(..., return_ids=True)`: `[[id record] ...]` in table order."
  [db name constraints]
  (if-not (some #{name} queryable-tables)
    []
    (vec (filter (fn [[_ rec]] (record-matches? rec constraints)) (table db name)))))

(defn query-database-tool
  "`query_database_tool(database_name, constraints_json)`: the formatted text
   tools return. `constraints` is the JSON *string* tools build with
   f-strings, so injected quotes fail exactly like upstream."
  [db name constraints]
  (try
    (if-not (some #{name} queryable-tables)
      (str "Error: Database '" name "' not found. Available: " (py/py-repr queryable-tables))
      (let [cdict (try
                    (if (py/truthy? constraints) (py/json-loads constraints) (array-map))
                    (catch clojure.lang.ExceptionInfo e
                      (if (= "JSONDecodeError" (py/exception-type e))
                        (throw (ex-info "json" {::invalid (py/exception-message e)}))
                        (throw e))))
            results (query db name cdict)]
        (if (empty? results)
          (str "No records found in '" name "'.")
          (str/join "\n"
                    (concat
                     [(str "Found " (count results) " record(s) in '" name "':\n")]
                     (mapcat (fn [i [id rec]]
                               (concat [(str i ". Record ID: " id)]
                                       (map (fn [[f v]] (str "   " f ": " (py/py-str v))) rec)
                                       [""]))
                             (iterate inc 1) results))))))
    (catch clojure.lang.ExceptionInfo e
      (cond
        (::invalid (ex-data e)) (str "Error: Invalid JSON: " (::invalid (ex-data e)))
        (py/py-exception? e) (str "Error querying database: " (py/exception-message e))
        :else (throw e)))))

;; ---------------------------------------------------------------------------
;; Time and deterministic ids (utils.py)

(def today-str "11/14/2025")
(def now-iso "2025-11-14T03:40:00")

(defn deterministic-id
  "`_deterministic_id`: the first `length/2` bytes of sha256 as hex."
  ([seed] (deterministic-id seed 16))
  ([^String seed length]
   (subs (pj/sha256-hex seed) 0 (* 2 (quot length 2)))))

(defn transaction-id [user-id card-type merchant amount category & [date]]
  (str "txn_" (deterministic-id
               (str/join ":" (cond-> ["transaction" (py/py-str user-id) (py/py-str card-type)
                                      (py/py-str merchant) (py/format-fixed amount 2)
                                      (py/py-str category)]
                               (py/truthy? date) (conj (py/py-str date))))
               12)))

(defn referral-id [referrer-id account-type & [date]]
  (deterministic-id (str/join ":" (cond-> ["referral" (py/py-str referrer-id) (py/py-str account-type)]
                                    (py/truthy? date) (conj (py/py-str date))))
                    16))

(defn application-id [card-type customer-name annual-income rho-bank-subscription]
  (deterministic-id (str "credit_card:" (py/py-str card-type) ":" (py/py-str customer-name) ":"
                         (py/py-str annual-income) ":" (py/py-str rho-bank-subscription))
                    16))

(defn verification-id [user-id time-verified]
  (str (py/py-str user-id) "_"
       (-> (py/py-str time-verified) (str/replace " " "_") (str/replace ":" "") (str/replace "-" ""))))

(defn user-discoverable-tool-id [tool-name]
  (deterministic-id (str "user_discoverable_tool:" tool-name) 16))

(defn user-discoverable-tool-call-id [tool-name arguments]
  (deterministic-id (str "user_discoverable_tool_call:" tool-name ":" (pj/dumps arguments true)) 16))

(defn agent-discoverable-tool-id [tool-name]
  (deterministic-id (str "agent_discoverable_tool:" tool-name) 16))

(defn dispute-id [user-id transaction-id]
  (str "dsp_" (deterministic-id (str "dispute:" (py/py-str user-id) ":" (py/py-str transaction-id)) 12)))

(defn referral-link-id [user-id card-name]
  (deterministic-id (str "referral_link:" (py/py-str user-id) ":" (py/py-str card-name)) 16))

(defn credit-card-order-id [account-id user-id reason]
  (str "ccord_" (deterministic-id (str "credit_card_order:" (py/py-str account-id) ":"
                                       (py/py-str user-id) ":" (py/py-str reason)) 12)))

(defn closure-reason-id [account-id user-id]
  (str "clsr_" (deterministic-id (str "closure_reason:" (py/py-str account-id) ":" (py/py-str user-id)) 12)))

(defn account-flag-id [account-id flag-type expiration-date]
  (str "ccflag_" (deterministic-id (str "account_flag:" (py/py-str account-id) ":"
                                        (py/py-str flag-type) ":" (py/py-str expiration-date)) 12)))

(defn credit-limit-increase-request-id [account-id user-id amount]
  (str "cli_" (deterministic-id (str "cli_request:" (py/py-str account-id) ":" (py/py-str user-id)
                                     ":" (py/format-fixed amount 2)) 12)))

(defn bank-account-transaction-id [account-id date description amount transaction-type]
  (str "btxn_" (deterministic-id (str "bank_txn:" (py/py-str account-id) ":" (py/py-str date) ":"
                                      (py/py-str description) ":" (py/format-fixed amount 2) ":"
                                      (py/py-str transaction-type)) 12)))

(defn debit-card-order-id [account-id user-id delivery-option]
  (str "dcord_" (deterministic-id (str "debit_card_order:" (py/py-str account-id) ":"
                                       (py/py-str user-id) ":" (py/py-str delivery-option)) 12)))

(defn debit-card-id [account-id user-id issue-date]
  (str "dbc_" (deterministic-id (str "debit_card:" (py/py-str account-id) ":"
                                     (py/py-str user-id) ":" (py/py-str issue-date)) 12)))

;; ---------------------------------------------------------------------------
;; Shared tool helpers (tools.py module level)

(defn parse-balance
  "`_parse_balance`."
  [val]
  (cond
    (boolean? val) (if val 1.0 0.0)
    (number? val) (double val)
    (string? val) (py/py-float (-> val (str/replace "$" "") (str/replace "," "")))
    :else 0.0))

(defn account-balance
  "`_get_account_balance`: `current_holdings`, else `balance`, else 0."
  [account]
  (parse-balance (if (contains? account "current_holdings")
                   (get account "current_holdings")
                   (get account "balance" 0))))

(def ^:private sequential-pins
  #{"0123" "1234" "2345" "3456" "4567" "5678" "6789" "9876" "8765" "7654"
    "6543" "5432" "4321" "3210"})

(defn- py-isdigit?
  "`s.isdigit()`: non-strings raise AttributeError like Python."
  [s]
  (when-not (string? s)
    (py/raise "AttributeError" (str "'" (py/type-name s) "' object has no attribute 'isdigit'")))
  (boolean (and (seq s) (every? #(Character/isDigit ^char %) s))))

(defn validate-pin
  "`_validate_pin`: nil when valid, else the message."
  [pin]
  (cond
    ;; `not pin or not pin.isdigit() or len(pin) != 4`: short-circuits on
    ;; falsy pins, otherwise non-strings raise from `.isdigit()`.
    (or (not (py/truthy? pin)) (not (py-isdigit? pin)) (not= 4 (count pin)))
    "PIN must be exactly 4 digits."
    (sequential-pins pin)
    "PIN cannot be sequential (e.g., 1234). Please choose a more secure PIN."
    (= 1 (count (set pin)))
    "PIN cannot be all the same digit (e.g., 1111). Please choose a more secure PIN."
    :else nil))

(defn validate-activation-common
  "`_validate_activation_common`: `[error-or-nil card-or-nil]`."
  [args db allowed-issue-reasons tool-name]
  (let [{:strs [card_id last_4_digits expiration_date cvv pin]} args]
    (if-not (every? py/truthy? [card_id last_4_digits expiration_date cvv pin])
      ["Error: Missing required parameters. Required: card_id, last_4_digits, expiration_date, cvv, pin." nil]
      (if-let [pin-error (validate-pin pin)]
        [(str "Error: " pin-error) nil]
        (cond
          (not (and (py-isdigit? cvv) (= 3 (count cvv))))
          ["Error: CVV must be exactly 3 digits." nil]
          (not (and (py-isdigit? last_4_digits) (= 4 (count last_4_digits))))
          ["Error: Last 4 digits must be exactly 4 digits." nil]
          (not (contains? (table db "debit_cards") card_id))
          [(str "Error: Debit card '" (py/py-str card_id) "' not found.") nil]
          :else
          (let [card (record db "debit_cards" card_id)
                issue-reason (get card "issue_reason" "new_account")
                account-id (get card "account_id")
                account (when (and (py/truthy? account-id)
                                   (contains? (table db "accounts") account-id))
                          (record db "accounts" account-id))]
            (cond
              (not (some #(py/py-eq issue-reason %) allowed-issue-reasons))
              (let [correct (get {"new_account" "activate_debit_card_8291"
                                  "first_card" "activate_debit_card_8291"
                                  "lost" "activate_debit_card_8292"
                                  "stolen" "activate_debit_card_8292"
                                  "fraud" "activate_debit_card_8292"
                                  "expired" "activate_debit_card_8293"
                                  "damaged" "activate_debit_card_8293"
                                  "upgrade" "activate_debit_card_8293"
                                  "bank_reissue" "activate_debit_card_8293"}
                                 issue-reason "unknown")]
                [(str "Error: Wrong activation tool. This card has issue_reason='"
                      (py/py-str issue-reason) "'. Please use " correct " instead of " tool-name ".")
                 nil])
              (= "ACTIVE" (get card "status"))
              [(str "Error: Debit card '" card_id "' is already active.") nil]
              (not= "PENDING" (get card "status"))
              [(str "Error: Debit card '" card_id "' cannot be activated. Current status: "
                    (py/py-str (get card "status")) ". Only PENDING cards can be activated.")
               nil]
              (not (py/py-eq (get card "last_4_digits") last_4_digits))
              ["Error: Card verification failed. The last 4 digits do not match our records." nil]
              (and account (not= "OPEN" (get account "status")))
              [(str "Error: The linked checking account '" (py/py-str account-id)
                    "' is no longer open. Card cannot be activated.")
               nil]
              :else [nil card])))))))

;; ---------------------------------------------------------------------------
;; Tool definitions

(defn tool
  "Build a tool definition. `mutates?` defaults from `type` (WRITE mutates)."
  [{:keys [name owner params type mutates? discoverable?]
    :or {owner "KnowledgeTools" discoverable? false}
    :as spec}]
  (assert (string? name))
  (assoc spec
         :owner owner
         :params (vec params)
         :discoverable? discoverable?
         :mutates? (if (contains? spec :mutates?) mutates? (= :write type))))

(defn- python-list-names [names]
  (let [q (mapv #(str "'" % "'") names)]
    (case (count q)
      1 (first q)
      2 (str (q 0) " and " (q 1))
      (str (str/join ", " (butlast q)) ", and " (last q)))))

(def ^:private owner-modules
  {"KnowledgeTools" "tau2.domains.banking_knowledge.tools."
   "KnowledgeUserTools" "tau2.domains.banking_knowledge.tools."
   "KBSearchMixin" "tau2.domains.banking_knowledge.retrieval_mixins."})

(defn bind-args
  "Python keyword binding for `owner.name(**args)`: raises TypeError with
   CPython's messages, otherwise returns args with defaults filled."
  [{:keys [name owner params]} args]
  (let [qual (str owner "." name "()")
        known (set (map first params))]
    (when-not (map? args)
      ;; CPython qualifies this one message with the defining module.
      (py/raise "TypeError" (str (get owner-modules owner "") qual
                                 " argument after ** must be a mapping, not "
                                 (py/type-name args))))
    (when-let [unexpected (first (remove known (keys args)))]
      (py/raise "TypeError" (str qual " got an unexpected keyword argument '" unexpected "'")))
    (let [missing (keep (fn [[p & default]] (when (and (empty? default) (not (contains? args p))) p))
                        params)]
      (when (seq missing)
        (py/raise "TypeError" (str qual " missing " (count missing)
                                   " required positional argument" (when (> (count missing) 1) "s")
                                   ": " (python-list-names missing)))))
    (reduce (fn [acc [p & default]]
              (if (contains? acc p) acc (assoc acc p (first default))))
            args params)))

(defn run-tool
  "Bind and run a tool. Returns `{:db db' :result string}`; Python exceptions
   propagate (the environment turns them into `Error: ...` tool messages)."
  [tool-def db args]
  (let [args (bind-args tool-def args)
        out ((:fn tool-def) db args)]
    (if (map? out)
      out
      {:db db :result out})))

(defn raise-with-db
  "Raise a Python exception after in-place mutations: upstream mutates the db
   before raising, so the partially updated `db` is what the episode keeps."
  [db type message]
  (throw (ex-info message {::py/exception type ::py/message message ::db db})))

(defn respond-direct
  "Environment `get_response` for a direct call to `tool-def`: never throws
   for Python exceptions. Returns `{:db :content :error}`."
  [tool-def db args]
  (try
    (let [{db' :db result :result} (run-tool tool-def db args)]
      {:db db' :content result :error false})
    (catch clojure.lang.ExceptionInfo e
      (if (py/py-exception? e)
        {:db (get (ex-data e) ::db db)
         :content (str "Error: " (py/exception-message e))
         :error true}
        (throw e)))))
