(ns dvergr.benchmarks.tau2.banking
  "Pure Clojure transcription of tau2-bench's `banking_knowledge` domain with
   the `bm25` retrieval configuration.

   A world is an immutable value

     {:db TransactionalDB
      :agent-unlocked {tool-name state}   ; KnowledgeTools._agent_discoverable_tools_state
      :user-given {tool-name state}       ; KnowledgeTools._user_discoverable_tools_state
      :allowlist #{tool-name}}            ; read-log allowlist from the gold trajectory

   Only `:db` is graded (the DB hash); the unlock/give state changes behavior
   but not the hash, exactly like upstream's in-memory toolkit state. Agent
   and user share one database.

   Tools: the 15 agent tools of `KnowledgeToolsWithKBSearch` (KB_search over
   BM25), 44 agent-discoverable tools (`banking.tools-a/b/c`), the user tools
   of `KnowledgeUserTools`, and 4 user-discoverable tools. Upstream quirks are
   kept on purpose, e.g. `list_discoverable_*_tools` test for \"No results
   found\" (never produced), and discoverable tools are directly callable by
   name without unlocking.

   Upstream: `src/tau2/domains/banking_knowledge/` and
   `src/tau2/knowledge/{pipeline,retrievers/bm25_retriever}.py`, checked by
   `benchmarks/dev/tau2/oracle.py`."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [dvergr.benchmarks.tau2.banking.db :as db]
            [dvergr.benchmarks.tau2.banking.tools-a :as tools-a]
            [dvergr.benchmarks.tau2.banking.tools-b :as tools-b]
            [dvergr.benchmarks.tau2.banking.tools-c :as tools-c]
            [dvergr.benchmarks.pyjson :as pj]
            [dvergr.benchmarks.python :as py]))

;; ---------------------------------------------------------------------------
;; Vendored upstream metadata (oracle `schema` export)

(def metadata
  (delay (pj/parse (slurp (io/resource "benchmarks/tau2/banking-tools.json")))))

(defn- tool-info [kind name] (get-in @metadata [kind name]))

;; ---------------------------------------------------------------------------
;; BM25 (rank_bm25.BM25Okapi, k1=1.5 b=0.75 epsilon=0.25)

(defn- py-space? [^long cp]
  (or (Character/isWhitespace (int cp))
      (#{0x85 0xa0 0x2007 0x202f} cp)))

(defn- py-split
  "Python `str.split()` (no separator)."
  [^String s]
  (loop [out [] sb (StringBuilder.) [cp & more] (iterator-seq (.iterator (.codePoints s)))]
    (cond
      (nil? cp) (if (pos? (.length sb)) (conj out (.toString sb)) out)
      (py-space? cp) (recur (if (pos? (.length sb)) (conj out (.toString sb)) out)
                            (StringBuilder.) more)
      :else (recur out (.appendCodePoint sb (int cp)) more))))

(defn- tokenize [^String s]
  (py-split (.toLowerCase s java.util.Locale/ROOT)))

(defn build-bm25
  "Index `docs` ([{:id :title :content}] in upstream order)."
  [docs]
  (let [k1 1.5 b 0.75 epsilon 0.25
        corpus (mapv (comp tokenize :content) docs)
        doc-freqs (mapv (fn [tokens]
                          (let [m (java.util.LinkedHashMap.)]
                            (doseq [t tokens] (.put m t (inc (long (or (.get m t) 0)))))
                            m))
                        corpus)
        nd (java.util.LinkedHashMap.)
        _ (doseq [^java.util.LinkedHashMap f doc-freqs
                  t (.keySet f)]
            (.put nd t (inc (long (or (.get nd t) 0)))))
        n (count corpus)
        doc-len (double-array (map count corpus))
        avgdl (/ (double (reduce + (map count corpus))) n)
        idf (java.util.HashMap.)
        [idf-sum negatives]
        (reduce (fn [[sum neg] [word freq]]
                  (let [v (- (Math/log (+ (- n freq) 0.5)) (Math/log (+ freq 0.5)))]
                    (.put idf word v)
                    [(+ sum v) (if (neg? v) (conj neg word) neg)]))
                [0.0 []]
                nd)
        eps (* epsilon (/ idf-sum (count nd)))]
    (doseq [w negatives] (.put idf w eps))
    {:k1 k1 :b b :n n :doc-len doc-len :avgdl avgdl :idf idf :doc-freqs doc-freqs
     :ids (mapv :id docs)
     :titles (into {} (map (juxt :id :title)) docs)
     :contents (into {} (map (juxt :id :content)) docs)}))

(defn- bm25-scores [{:keys [k1 b n ^doubles doc-len avgdl ^java.util.HashMap idf doc-freqs]} query]
  (let [score (double-array n)]
    (doseq [q query]
      (let [w (let [v (.get idf q)] (if (or (nil? v) (zero? (double v))) 0.0 (double v)))]
        (dotimes [i n]
          (let [tf (double (or (.get ^java.util.LinkedHashMap (nth doc-freqs i) q) 0))
                denom (+ tf (* k1 (+ (- 1 b) (/ (* b (aget doc-len i)) avgdl))))]
            (aset score i (+ (aget score i) (* w (/ (* tf (+ k1 1)) denom))))))))
    score))

(defn bm25-retrieve
  "`BM25Retriever.retrieve` + pipeline sort: `[[doc-id score] ...]`."
  [index query top-k]
  (if-not (py/truthy? query)
    []
    (do
      (when-not (string? query)
        (py/raise "AttributeError" (str "'" (py/type-name query) "' object has no attribute 'strip'")))
      (if (str/blank? query)
        []
        (let [^doubles scores (bm25-scores index (tokenize query))
              order (sort (fn [i j] (let [a (aget scores (int i)) c (aget scores (int j))]
                                      (cond (> a c) -1 (< a c) 1 :else 0)))
                          (range (count (:ids index))))]
          (mapv (fn [i] [((:ids index) i) (aget scores (int i))])
                (take (min top-k (count (:ids index))) order)))))))

(def ^:private timing-line
  ;; Upstream prints wall-clock timings. A pure world keeps episodes
  ;; deterministic, so the port reports zeros (equivalence masks the digits).
  "\n\n[Timing: retrieval=0ms, reranking=0ms, total=0ms]")

(defn kb-search [index query]
  (let [results (bm25-retrieve index query 10)]
    (if (empty? results)
      (str "No relevant documents found." timing-line)
      (str (str/join "\n"
                     (map-indexed
                      (fn [i [id score]]
                        (str (inc i) ". " (or (not-empty (get-in index [:titles id])) "Untitled") "\n"
                             "   ID: " id "\n"
                             "   Score: " (py/format-fixed score 4) "\n"
                             "   Content: " (or (get-in index [:contents id]) "") "\n"))
                      results))
           timing-line))))

;; ---------------------------------------------------------------------------
;; Agent tools (KnowledgeTools + KBSearchMixin)

(def transfer-reasons
  ["fraud_or_security_concern" "account_closure_request" "deceased_account_holder"
   "legal_or_regulatory_matter" "account_ownership_dispute" "complex_billing_dispute"
   "abusive_customer_behavior" "third_party_inquiry" "technical_system_error"
   "unconfirmed_external_communication" "customer_demands_after_unavailable_offer_refusal"
   "kb_search_unsuccessful_customer_requests_transfer" "specialized_department_required"
   "accessibility_or_special_needs" "customer_frustrated_demands_human"
   "supervisor_request_service_complaint" "customer_requests_human_no_specific_reason"
   "request_completed_customer_wants_human_followup" "other"])

(defn- query-by
  "Tools that interpolate one argument into a JSON constraint string."
  [table field param]
  (fn [d args]
    (db/query-database-tool d table (str "{\"" field "\": \"" (py/py-str (get args param)) "\"}"))))

(def discoverable-agent-tools
  (into [(db/tool {:name "example_agent_tool_0000" :params [] :type :write :discoverable? true
                   :fn (fn [_ _] "Example tool executed successfully.")})]
        (concat tools-a/tools tools-b/tools tools-c/tools)))

(defn format-discoverable-tool-for-agent [info]
  (let [params (get info "parameters")
        lines (map (fn [[pname pdef]]
                     (str "  - " pname ": " (get pdef "type" "string")
                          (if (get pdef "required" true) " (required)" " (optional)")
                          " - " (get pdef "description" "")))
                   params)]
    (str "Tool: " (get info "name") "\n"
         "Description: " (get info "description") "\n"
         "Parameters:\n" (if (seq lines) (str/join "\n" lines) "  (no parameters)"))))

(declare user-toolkit)

(defn- world-tool
  "A tool whose fn sees the whole world: `(fn [world args] -> {:world w :result r})`."
  [spec]
  (assoc (db/tool spec) ::world-fn (:fn spec)))

(defn- agent-base-tools [index]
  [(world-tool
    {:name "KB_search" :owner "KBSearchMixin" :params [["query"]] :type :read
     :fn (fn [w {:strs [query]}] {:world w :result (kb-search index query)})})
   (db/tool
    {:name "transfer_to_human_agents" :params [["summary"] ["reason" "other"]] :type :generic
     :fn (fn [_ {:strs [reason]}]
           (if-not (some #(py/py-eq reason %) transfer-reasons)
             (str "Error: Invalid transfer reason '" (py/py-str reason) "'. Must be one of: "
                  (str/join ", " transfer-reasons))
             (str "Transfer successful (reason: " (py/py-str reason)
                  "). A human agent will assist you shortly.")))})
   (db/tool {:name "get_current_time" :params [] :type :read
             :fn (fn [_ _] "The current time is 2025-11-14 03:40:00 EST.")})
   (db/tool {:name "get_user_information_by_id" :params [["user_id"]] :type :read
             :fn (query-by "users" "user_id" "user_id")})
   (db/tool {:name "get_user_information_by_name" :params [["customer_name"]] :type :read
             :fn (query-by "users" "name" "customer_name")})
   (db/tool {:name "get_user_information_by_email" :params [["email"]] :type :read
             :fn (query-by "users" "email" "email")})
   (db/tool
    {:name "change_user_email" :params [["user_id"] ["new_email"]] :type :write
     :fn (fn [d {:strs [user_id new_email]}]
           (let [[d' rec] (db/update-record d "users" user_id {"email" new_email})]
             (if-not rec
               (str "Error: User with ID '" (py/py-str user_id) "' not found.")
               {:db d'
                :result (str "Email updated successfully.\n"
                             "  - User ID: " (py/py-str user_id) "\n"
                             "  - New Email: " (py/py-str new_email))})))})
   (db/tool {:name "get_referrals_by_user" :params [["user_id"]] :type :read
             :fn (query-by "referrals" "referrer_id" "user_id")})
   (db/tool {:name "get_credit_card_transactions_by_user" :params [["user_id"]] :type :read
             :fn (query-by "credit_card_transaction_history" "user_id" "user_id")})
   (db/tool {:name "get_credit_card_accounts_by_user" :params [["user_id"]] :type :read
             :fn (query-by "credit_card_accounts" "user_id" "user_id")})
   (db/tool
    {:name "log_verification"
     :params [["name"] ["user_id"] ["address"] ["email"] ["phone_number"] ["date_of_birth"]
              ["time_verified"]]
     :type :write
     :fn (fn [d {:strs [name user_id address email phone_number date_of_birth time_verified]}]
           (when-not (string? time_verified)
             (py/raise "AttributeError" (str "'" (py/type-name time_verified)
                                             "' object has no attribute 'replace'")))
           (let [id (db/verification-id user_id time_verified)
                 rec (array-map "name" name "user_id" user_id "address" address "email" email
                                "phone_number" phone_number "date_of_birth" date_of_birth
                                "time_verified" time_verified)
                 [d' ok] (db/add-record d "verification_history" id rec)]
             (if-not ok
               "Failed to log verification: Record may already exist."
               {:db d'
                :result (str "Verification logged successfully.\n"
                             "  - User: " (py/py-str name) " (ID: " (py/py-str user_id) ")\n"
                             "  - Verified at: " (py/py-str time_verified))})))})
   (world-tool
    {:name "give_discoverable_user_tool" :params [["discoverable_tool_name"] ["arguments" "{}"]]
     :type :generic :mutates? true
     :fn (fn [w {:strs [discoverable_tool_name arguments]}]
           (let [info (when (string? discoverable_tool_name)
                        (tool-info "user_discoverable" discoverable_tool_name))]
             (if-not info
               {:world w :result (str "Error: Unknown discoverable tool '"
                                      (py/py-str discoverable_tool_name) "'.")}
               (let [parsed (try {:ok (py/json-loads arguments)}
                                 (catch clojure.lang.ExceptionInfo e
                                   (if (= "JSONDecodeError" (py/exception-type e))
                                     {:error (py/exception-message e)}
                                     (throw e))))]
                 (if (contains? parsed :error)
                   {:world w :result (str "Error: Invalid JSON in arguments: " (:error parsed))}
                   (let [args (:ok parsed)
                         allowed (into #{"self"} (map first (get-in @metadata ["user_tool_params" discoverable_tool_name])))
                         arg-names (cond (map? args) (keys args)
                                         (string? args) (map str args)
                                         (sequential? args) args
                                         :else (py/raise "TypeError"
                                                         (str "'" (py/type-name args) "' object is not iterable")))
                         bad (first (remove #(and (string? %) (allowed %)) arg-names))]
                     (if (some? bad)
                       {:world w :result (str "Error: Unexpected parameter: " (py/py-str bad))}
                       (let [w (-> w
                                   (update :user-given assoc discoverable_tool_name
                                           {"arguments" args "given_at" db/now-iso "tool_info" info})
                                   (update :db #(first (db/add-record
                                                        % "user_discoverable_tools"
                                                        (db/user-discoverable-tool-id discoverable_tool_name)
                                                        (array-map "tool_name" discoverable_tool_name
                                                                   "status" "GIVEN")))))]
                         {:world w
                          :result (str "Tool given to user: " discoverable_tool_name "\n"
                                       "Description: " (get info "description") "\n"
                                       "Arguments: " (if (py/truthy? args) (py/dumps-indent args) "(no arguments)")
                                       "\n\n"
                                       "The user can now execute this action by calling `call_discoverable_user_tool` "
                                       "with discoverable_tool_name='" discoverable_tool_name "' and the same arguments.")}))))))))})
   (world-tool
    {:name "unlock_discoverable_agent_tool" :params [["agent_tool_name"]] :type :generic :mutates? true
     :fn (fn [w {:strs [agent_tool_name]}]
           (if-let [info (when (string? agent_tool_name)
                           (tool-info "agent_discoverable" agent_tool_name))]
             {:world (update w :agent-unlocked assoc agent_tool_name
                             {"unlocked_at" db/now-iso "tool_info" info})
              :result (str "Tool unlocked: " agent_tool_name "\n"
                           "Description: " (get info "description") "\n\n"
                           (format-discoverable-tool-for-agent info) "\n\n"
                           "You can now use this tool by calling `call_discoverable_agent_tool` with "
                           "agent_tool_name='" agent_tool_name "' and the required arguments.")}
             {:world w :result (str "Error: Unknown agent tool '" (py/py-str agent_tool_name)
                                    "'. This tool is not available.")}))})
   (world-tool
    {:name "call_discoverable_agent_tool" :params [["agent_tool_name"] ["arguments" "{}"]] :type :write
     :fn (fn [w {:strs [agent_tool_name arguments]}]
           (let [tool-def (when (string? agent_tool_name)
                            (some #(when (= agent_tool_name (:name %)) %) discoverable-agent-tools))]
             (cond
               (nil? tool-def)
               {:world w :result (str "Error: Unknown agent tool '" (py/py-str agent_tool_name)
                                      "'. This tool is not available.")}
               (not (contains? (:agent-unlocked w) agent_tool_name))
               {:world w :result (str "Error: Tool '" agent_tool_name "' has not been unlocked. "
                                      "You must first use `unlock_discoverable_agent_tool` to unlock this tool before calling it.")}
               :else
               (let [parsed (try {:ok (py/json-loads arguments {:parse-int :float})}
                                 (catch clojure.lang.ExceptionInfo e
                                   (if (= "JSONDecodeError" (py/exception-type e))
                                     {:error (py/exception-message e)}
                                     (throw e))))]
                 (if (contains? parsed :error)
                   {:world w :result (str "Error: Invalid JSON in arguments: " (:error parsed))}
                   (let [outcome (try {:ok (db/run-tool tool-def (:db w) (:ok parsed))}
                                      (catch clojure.lang.ExceptionInfo e
                                        (if (= "TypeError" (py/exception-type e))
                                          {:type-error e}
                                          (throw e))))]
                     (if-let [e (:type-error outcome)]
                       {:world (assoc w :db (get (ex-data e) ::db/db (:db w)))
                        :result (str "Error: Invalid arguments: " (py/exception-message e))}
                       (let [{d :db result :result} (:ok outcome)
                             log? (or (:mutates? tool-def) (contains? (:allowlist w) agent_tool_name))
                             d (if log?
                                 (first (db/add-record d "agent_discoverable_tools"
                                                       (db/agent-discoverable-tool-id agent_tool_name)
                                                       (array-map "tool_name" agent_tool_name
                                                                  "status" "CALLED")))
                                 d)]
                         {:world (assoc w :db d) :result result}))))))))})
   (db/tool
    {:name "list_discoverable_agent_tools" :params [] :type :read
     :fn (fn [d _]
           (let [r (db/query-database-tool d "agent_discoverable_tools" "{}")]
             (if (str/includes? r "No results found")
               "No agent tools have been called yet. Search the knowledge base to discover available tools."
               (str "Your called agent tools:\n" r))))})])

;; ---------------------------------------------------------------------------
;; User tools (KnowledgeUserTools)

(def valid-credit-card-types
  ["Bronze Rewards Card" "Business Bronze Rewards Card" "Business Gold Rewards Card"
   "Business Platinum Rewards Card" "Business Silver Rewards Card" "Crypto-Cash Back"
   "Diamond Elite Card" "EcoCard" "Gold Rewards Card" "Green Rewards Card"
   "Platinum Rewards Card" "Silver Rewards Card" "Silver Zoom Card"])

(def credit-card-rewards
  (array-map
   "Bronze Rewards Card" (array-map "default" 1.0)
   "Silver Rewards Card" (array-map "Travel" 4.0 "Software" 4.0 "default" 1.0)
   "Gold Rewards Card" (array-map "default" 2.5)
   "Platinum Rewards Card" (array-map "default" 10.0)
   "Business Bronze Rewards Card" (array-map "default" 1.0)
   "Business Silver Rewards Card" (array-map "Travel" 10.0 "Software" 10.0 "default" 1.0)
   "Green Rewards Card" (array-map "Sustainable" 3.0 "default" 1.0)
   "Business Gold Rewards Card" (array-map "Operations" 2.5 "default" 1.0)
   "Business Platinum Rewards Card" (array-map "Travel" 4.0 "Software" 4.0 "Media" 4.0 "default" 1.5)
   "Silver Zoom Card" (array-map "Transportation" 3.0 "default" 1.0)
   "Diamond Elite Card" (array-map "default" 5.0)
   "EcoCard" (array-map "Green" 5.0 "default" 1.0)
   "Crypto-Cash Back" (array-map "default" 2.0)))

(defn- check-tool-given [d tool-name]
  (when (str/includes? (db/query-database-tool d "user_discoverable_tools"
                                               (str "{\"tool_name\": \"" tool-name "\"}"))
                       "No records found")
    (str "Error: Tool '" tool-name "' has not been given to you by the agent. "
         "The agent must first use `give_discoverable_user_tool` to give this tool to you.")))

(defn- log-user-tool-call [d tool-name args]
  (first (db/add-record d "user_discoverable_tool_calls"
                        (db/user-discoverable-tool-call-id tool-name args)
                        (array-map "tool_name" tool-name "arguments" args
                                   "called_at" db/today-str "status" "CALLED"))))

(defn- user-discoverable [name params f]
  (db/tool {:name name :owner "KnowledgeUserTools" :params params
            :type (if (= name "get_card_last_4_digits") :read :write)
            :discoverable? true
            :fn (fn [d args]
                  (or (check-tool-given d name)
                      (let [call-args (apply array-map (mapcat (fn [[p]] [p (get args p)]) params))]
                        (f (log-user-tool-call d name call-args) args call-args))))}))

(def discoverable-user-tools
  [(user-discoverable
    "submit_cash_back_dispute_0589" [["user_id"] ["transaction_id"]]
    (fn [d {:strs [user_id transaction_id]} call-args]
      (let [id (db/dispute-id user_id transaction_id)
            config (get-in d ["task_config" "data"])
            auto? (and (py/truthy? config)
                       (py/truthy? (get (get config "dispute_settings" {}) "auto_resolve_disputes" false)))
            rec (if auto?
                  (array-map "dispute_id" id "user_id" user_id "transaction_id" transaction_id
                             "submitted_at" db/today-str "status" "RESOLVED" "resolution" "APPROVED")
                  (array-map "dispute_id" id "user_id" user_id "transaction_id" transaction_id
                             "submitted_at" db/today-str "status" "SUBMITTED"))
            status-msg (if auto?
                         "Status: RESOLVED - The dispute has been reviewed and approved. The transaction rewards need to be updated."
                         "Status: SUBMITTED - Your dispute has been queued for review.")
            [d' ok] (db/add-record d "cash_back_disputes" id rec)]
        {:db d'
         :result (str "Cash back dispute submitted successfully. Your case has been queued for review.\n\n"
                      "Executed: submit_cash_back_dispute_0589\nArguments: " (py/dumps-indent call-args) "\n"
                      (if ok
                        (str "Dispute ID: " id "\n" status-msg)
                        "Note: Dispute may have already been submitted for this transaction."))})))
   (user-discoverable
    "get_referral_link" [["user_id"] ["card_name"]]
    (fn [d {:strs [user_id card_name]} call-args]
      (let [id (db/referral-link-id user_id card_name)
            rec (array-map "referral_id" id "referrer_id" user_id "referred_account_type" card_name
                           "referral_status" "NO_PROGRESS" "date" db/today-str)
            [d' ok] (db/add-record d "referrals" id rec)]
        {:db d'
         :result (str "Referral link generated successfully. Share this link with the person you want to refer.\n\n"
                      "Executed: get_referral_link\nArguments: " (py/dumps-indent call-args) "\n"
                      (if ok
                        (str "Referral ID: " id "\nReferral link: https://rhobank.com/refer/" id)
                        "Note: A referral link for this card may have already been generated."))})))
   (user-discoverable
    "get_card_last_4_digits" [["credit_card_account_id"]]
    (fn [d {:strs [credit_card_account_id]} call-args]
      (let [r (db/query-database-tool d "credit_card_accounts"
                                      (str "{\"account_id\": \"" (py/py-str credit_card_account_id) "\"}"))]
        (if (or (str/includes? r "No results found") (str/includes? r "No records found"))
          {:db d :result (str "Error: Credit card account '" (py/py-str credit_card_account_id) "' not found.")}
          (let [digits (->> (pj/sha256-hex (str "card_last4:" (py/py-str credit_card_account_id)))
                            (filter #(Character/isDigit ^char %))
                            (take 4)
                            (apply str))
                last4 (str digits (apply str (repeat (- 4 (count digits)) \0)))]
            {:db d
             :result (str "Card information retrieved successfully.\n\n"
                          "Executed: get_card_last_4_digits\nArguments: " (py/dumps-indent call-args) "\n"
                          "Last 4 digits of card: " last4)})))))
   (user-discoverable
    "deposit_check_3847" [["account_id"] ["check_amount"]]
    (fn [d {:strs [account_id check_amount]} call-args]
      (let [amount (try {:ok (py/py-float check_amount)}
                        (catch clojure.lang.ExceptionInfo e
                          (if (#{"TypeError" "ValueError"} (py/exception-type e)) {} (throw e))))]
        (cond
          (not (contains? amount :ok))
          {:db d :result (str "Error: Invalid check amount '" (py/py-str check_amount) "'. Must be a number.")}
          (<= (:ok amount) 0) {:db d :result "Error: Check amount must be positive."}
          (not (contains? (db/table d "accounts") account_id))
          {:db d :result (str "Error: Account '" (py/py-str account_id) "' not found.")}
          :else
          (let [account (db/record d "accounts" account_id)
                amount (:ok amount)]
            (if-not (#{"ACTIVE" "OPEN"} (get account "status"))
              {:db d :result (str "Error: Account '" account_id "' is not active.")}
              (let [current (db/account-balance account)
                    new-balance (+ current amount)]
                {:db (db/set-field d "accounts" account_id "current_holdings"
                                   (str "$" (py/format-fixed new-balance 2)))
                 :result (str "Check deposited successfully. Funds will be available according to your account's deposit policy.\n\n"
                              "Executed: deposit_check_3847\n"
                              "Arguments: " (py/dumps-indent call-args) "\n"
                              "Check deposit processed!\n"
                              "  - Account: " account_id "\n"
                              "  - Check Amount: $" (py/format-fixed amount 2) "\n"
                              "  - Previous Balance: $" (py/format-fixed current 2) "\n"
                              "  - New Balance: $" (py/format-fixed new-balance 2))})))))))])

(def user-base-tools
  [(db/tool
    {:name "apply_for_credit_card" :owner "KnowledgeUserTools"
     :params [["card_type"] ["customer_name"] ["annual_income"] ["rho_bank_subscription" false]]
     :type :write
     :fn (fn [d {:strs [card_type customer_name annual_income rho_bank_subscription]}]
           (if-not (some #(py/py-eq card_type %) valid-credit-card-types)
             (str "Error: Invalid card_type '" (py/py-str card_type) "'. Must be one of: "
                  (py/py-repr valid-credit-card-types))
             (let [income (try {:ok (py/py-float annual_income)}
                               (catch clojure.lang.ExceptionInfo e
                                 (if (#{"TypeError" "ValueError"} (py/exception-type e)) {} (throw e))))]
               (if-not (contains? income :ok)
                 "Error: Invalid annual_income. Must be a number."
                 (let [income (:ok income)
                       id (db/application-id card_type customer_name income rho_bank_subscription)
                       rec (array-map "application_id" id "card_type" card_type
                                      "customer_name" customer_name "annual_income" income
                                      "rho_bank_subscription" rho_bank_subscription
                                      "status" "PENDING" "date" db/today-str)
                       [d' ok] (db/add-record d "credit_card_applications" id rec)]
                   (if-not ok
                     (str "Failed to submit application: Record ID '" id "' may already exist.")
                     {:db d'
                      :result (str "Credit card application submitted:\n"
                                   "Your application has been successfully submitted. "
                                   "You will receive a decision within 5-7 business days via email.")}))))))})
   (db/tool
    {:name "submit_referral" :owner "KnowledgeUserTools" :params [["user_id"] ["account_type"]]
     :type :write
     :fn (fn [d {:strs [user_id account_type]}]
           (let [id (db/referral-id user_id account_type)
                 rec (array-map "referral_id" id "referrer_id" user_id
                                "referred_account_type" account_type
                                "referral_status" "NO_PROGRESS" "date" db/today-str)
                 [d' ok] (db/add-record d "referrals" id rec)]
             (if-not ok
               (str "Failed to submit referral: Record ID '" id "' may already exist.")
               {:db d'
                :result (str "Referral request submitted successfully!\n"
                             "  - Referral ID: " id "\n"
                             "  - Referrer ID: " (py/py-str user_id) "\n"
                             "  - Account Type: " (py/py-str account_type) "\n"
                             "  - Status: NO_PROGRESS\n"
                             "  - Date: " db/today-str "\n\n"
                             "Share your referral ID with the person you're referring. "
                             "They will need to use this when applying for their account.")})))})
   (world-tool
    {:name "call_discoverable_user_tool" :owner "KnowledgeUserTools"
     :params [["discoverable_tool_name"] ["arguments" "{}"]] :type :write
     :fn (fn [w {:strs [discoverable_tool_name arguments]}]
           (let [tool-def (when (string? discoverable_tool_name)
                            (some #(when (= discoverable_tool_name (:name %)) %) discoverable-user-tools))]
             (if-not tool-def
               {:world w :result (str "Error: Unknown discoverable tool '"
                                      (py/py-str discoverable_tool_name) "'.")}
               (let [parsed (try {:ok (py/json-loads arguments {:parse-int :float})}
                                 (catch clojure.lang.ExceptionInfo e
                                   (if (= "JSONDecodeError" (py/exception-type e))
                                     {:error (py/exception-message e)}
                                     (throw e))))]
                 (if (contains? parsed :error)
                   {:world w :result (str "Error: Invalid JSON in arguments: " (:error parsed))}
                   (let [args (:ok parsed)
                         has? #(py/py-in % args)
                         missing (first (remove has? (map first (:params tool-def))))
                         params (set (map first (:params tool-def)))
                         arg-names (cond (map? args) (keys args)
                                         (string? args) (map str args)
                                         :else args)
                         unexpected (first (remove #(and (string? %) (params %)) arg-names))]
                     (cond
                       (some? missing) {:world w :result (str "Error: Missing required parameter: " missing)}
                       (some? unexpected) {:world w :result (str "Error: Unexpected parameter: "
                                                                 (py/py-str unexpected))}
                       :else
                       (try
                         (let [{d :db result :result} (db/run-tool tool-def (:db w) args)]
                           {:world (assoc w :db d) :result result})
                         (catch clojure.lang.ExceptionInfo e
                           (if (= "TypeError" (py/exception-type e))
                             {:world (assoc w :db (get (ex-data e) ::db/db (:db w)))
                              :result (str "Error: Invalid arguments for tool '" discoverable_tool_name
                                           "': " (py/exception-message e))}
                             (throw e)))))))))))})
   (db/tool
    {:name "list_discoverable_user_tools" :owner "KnowledgeUserTools" :params [] :type :read
     :fn (fn [d _]
           (let [r (db/query-database-tool d "user_discoverable_tools" "{}")]
             (if (str/includes? r "No results found")
               "No tools have been given to you yet by the agent."
               (str "Tools given to you by the agent:\n" r))))})
   (db/tool
    {:name "request_human_agent_transfer" :owner "KnowledgeUserTools" :params [] :type :write
     :fn (fn [d _]
           (let [existing (db/query-database-tool d "human_transfer_requests" "{}")
                 n (if (or (str/includes? existing "No records found")
                           (str/includes? existing "No results found"))
                     1
                     (inc (count (re-seq #"request_id" existing))))
                 id (str "transfer_request_" n)
                 [d' _] (db/add-record d "human_transfer_requests" id
                                       (array-map "request_id" id "request_number" n
                                                  "requested_at" db/today-str "status" "PENDING"))]
             {:db d'
              :result (str "Transfer request #" n " submitted.\n"
                           "The agent will process your request.")}))})
   (db/tool
    {:name "submit_transaction" :owner "KnowledgeUserTools"
     :params [["user_id"] ["credit_card_type"] ["merchant_name"] ["amount"] ["category"]]
     :type :write
     :fn (fn [d {:strs [user_id credit_card_type merchant_name amount category]}]
           (if-not (and (string? credit_card_type) (contains? credit-card-rewards credit_card_type))
             (str "Error: Unknown credit card type '" (py/py-str credit_card_type) "'. Available types: "
                  (py/py-repr (vec (keys credit-card-rewards))))
             (let [id (db/transaction-id user_id credit_card_type merchant_name amount category)
                   rewards (get credit-card-rewards credit_card_type)
                   rate (get rewards category (get rewards "default"))
                   _ (when-not (or (number? amount) (boolean? amount))
                       (py/raise "TypeError" "can't multiply sequence by non-int of type 'float'"))
                   points (py/py-int (* (double (if (boolean? amount) (if amount 1 0) amount)) rate))
                   rec (array-map "transaction_id" id "user_id" user_id
                                  "credit_card_type" credit_card_type "merchant_name" merchant_name
                                  "transaction_amount" (str "$" (py/format-fixed amount 2))
                                  "transaction_date" db/today-str "category" category
                                  "status" "COMPLETED" "rewards_earned" (str points " points"))
                   [d' ok] (db/add-record d "credit_card_transaction_history" id rec)]
               (if-not ok
                 (str "Failed to submit transaction: Record ID '" id "' may already exist.")
                 {:db d'
                  :result (str "Transaction submitted successfully!\n"
                               "  - Transaction ID: " id "\n"
                               "  - User ID: " (py/py-str user_id) "\n"
                               "  - Card Type: " credit_card_type "\n"
                               "  - Merchant: " (py/py-str merchant_name) "\n"
                               "  - Amount: $" (py/format-fixed amount 2) "\n"
                               "  - Category: " (py/py-str category) "\n"
                               "  - Date: " db/today-str "\n"
                               "  - Rewards Earned: " points " points (" (py/py-str rate) "% cashback rate)\n")}))))})])

;; ---------------------------------------------------------------------------
;; Environment

(defn toolkits
  "Agent and user toolkits, each `{name tool-def}` including discoverable
   tools (directly callable by name, as upstream's `use_tool` allows)."
  [index]
  {:agent (into {} (map (juxt :name identity))
                (concat (agent-base-tools index) discoverable-agent-tools))
   :user (into {} (map (juxt :name identity))
               (concat user-base-tools discoverable-user-tools))})

(defn- run-in-world [tool-def world args]
  (if-let [f (::world-fn tool-def)]
    (let [args (db/bind-args tool-def args)
          out (f world args)]
      out)
    (let [{d :db result :result} (db/run-tool tool-def (:db world) args)]
      {:world (assoc world :db d) :result result})))

(defn respond
  "Environment `get_response`: `{:world w' :content str :error bool}`; never
   throws for Python exceptions."
  [kits world requestor tool-name args]
  (try
    (let [kit (get kits (if (= requestor :user) :user :agent))
          tool-def (or (get kit tool-name)
                       (py/raise "ValueError" (str "Tool '" (py/py-str tool-name) "' not found.")))
          {w :world result :result} (run-in-world tool-def world (or args {}))]
      {:world w :content (if (string? result) result (pj/dumps result)) :error false})
    (catch clojure.lang.ExceptionInfo e
      (if (py/py-exception? e)
        {:world (if-let [d (::db/db (ex-data e))] (assoc world :db d) world)
         :content (str "Error: " (py/exception-message e))
         :error true}
        (throw e)))))

(defn read-log-allowlist
  "`_derive_read_log_allowlist`: discoverable tools called in gold actions."
  [task]
  (into #{}
        (keep (fn [{:strs [name arguments]}]
                (when (= name "call_discoverable_agent_tool")
                  (let [n (get arguments "agent_tool_name")]
                    (when (py/truthy? n) n)))))
        (get-in task ["evaluation_criteria" "actions"])))

(defn initial-world
  "The task's starting world: base db + initialization data (agent then user,
   deep-merged like addict) + the read-log allowlist."
  [base-db task]
  (let [{:strs [agent_data user_data]} (get-in task ["initial_state" "initialization_data"])
        d (cond-> base-db
            (some? agent_data) (-> (db/deep-update agent_data) db/normalize-db)
            (some? user_data) (-> (db/deep-update user_data) db/normalize-db))]
    {:db d :agent-unlocked (array-map) :user-given (array-map)
     :allowlist (if task (read-log-allowlist task) #{})}))

;; ---------------------------------------------------------------------------
;; Data

(defn load-documents
  "KB documents in the pinned upstream order."
  [documents-dir]
  (let [by-id (into {}
                    (map (fn [^java.io.File f]
                           (let [{:strs [id title content]} (pj/parse (slurp f))]
                             [id {:id id :title title :content content}])))
                    (filter #(str/ends-with? (.getName ^java.io.File %) ".json")
                            (.listFiles (io/file documents-dir))))]
    (mapv (fn [id] (or (by-id id)
                       (throw (ex-info "Knowledge-base document missing" {:id id}))))
          (get @metadata "kb_doc_order"))))

(defn policy
  "`build_policy` for the bm25 variant: template with components substituted."
  [prompts-dir]
  (str/replace (slurp (io/file prompts-dir "classic_rag_bm25_no_grep.md"))
               #"\{\{component:(\w+)\}\}"
               ;; A replacement fn's result is used literally, like re.sub.
               (fn [[_ component]]
                 (slurp (io/file prompts-dir "components" (str component ".md"))))))
