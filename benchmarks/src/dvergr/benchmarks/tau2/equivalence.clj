(ns dvergr.benchmarks.tau2.equivalence
  "Differential equivalence between the Clojure tau2 transcription and the
   upstream Python environment.

   A corpus is a vector of `{\"id\" str \"calls\" [{\"name\" str \"arguments\" map}]}`.
   The upstream side is produced once per port version by
   `benchmarks/dev/tau2/oracle.py`; this namespace replays the same corpus in
   Clojure and reports every divergence in tool content, error flag, or final
   database hash."
  (:require [clojure.string :as str]
            [dvergr.benchmarks.pyjson :as pj]))

(defn replay
  "Replay one call sequence from `db` with `respond-fn`
   `(db name args) -> {:db :content :error}`."
  [respond-fn db {:strs [id calls]}]
  (let [[db outputs] (reduce (fn [[db outputs] {:strs [name arguments]}]
                               (let [{db' :db :keys [content error]}
                                     (respond-fn db name arguments)]
                                 [db' (conj outputs {"content" content
                                                     "error" error})]))
                             [db []]
                             calls)]
    {"id" id "outputs" outputs "db" db}))

(defn compare-corpus
  "Compare Clojure replays against oracle results. `hash-fn` hashes a final db.
   Returns a summary with every mismatching call and hash."
  [respond-fn hash-fn db corpus oracle-results]
  (let [by-id (into {} (map (juxt #(get % "id") identity)) oracle-results)
        diffs
        (for [sequence corpus
              :let [ours (replay respond-fn db sequence)
                    theirs (get by-id (get sequence "id"))
                    call-diffs (keep-indexed
                                (fn [i [o t]]
                                  (when (not= o t)
                                    {:call i
                                     :call/name (get-in sequence ["calls" i "name"])
                                     :ours o :theirs t}))
                                (map vector (get ours "outputs")
                                     (get theirs "outputs")))
                    our-hash (hash-fn (get ours "db"))
                    hash-diff (when (not= our-hash (get theirs "db_hash"))
                                {:ours our-hash :theirs (get theirs "db_hash")})]
              :when (or (nil? theirs) (seq call-diffs) hash-diff)]
          {:id (get sequence "id")
           :missing-oracle? (nil? theirs)
           :calls (vec (take 3 call-diffs))
           :call-diff-count (count call-diffs)
           :hash hash-diff})]
    {:sequences (count corpus)
     :calls (reduce + (map #(count (get % "calls")) corpus))
     :mismatched-sequences (count diffs)
     :diffs (vec (take 10 diffs))}))

(defn write-corpus! [path corpus]
  (spit path (pj/dumps corpus)))

;; ---------------------------------------------------------------------------
;; Seeded fuzz corpus for the retail domain

(defn- pick [^java.util.Random rng xs]
  (let [xs (vec xs)] (when (seq xs) (nth xs (.nextInt rng (count xs))))))

(defn- seeded-shuffle [^java.util.Random rng xs]
  (let [al (java.util.ArrayList. ^java.util.Collection (vec xs))]
    (java.util.Collections/shuffle al rng)
    (vec al)))

(defn- chance [^java.util.Random rng p] (< (.nextDouble rng) p))

(defn- retail-call [^java.util.Random rng db]
  (let [orders (vals (get db "orders"))
        order (pick rng orders)
        order (if (chance rng 0.6)
                (or (pick rng (filter #(#{"pending" "delivered"} (get % "status")) orders))
                    order)
                order)
        oid (if (chance rng 0.05) "#W0000000" (get order "order_id"))
        user (get-in db ["users" (get order "user_id")])
        pms (keys (get user "payment_methods"))
        pm (cond (chance rng 0.05) "credit_card_0000000"
                 (chance rng 0.1) (pick rng (mapcat #(keys (get % "payment_methods"))
                                                    (take 20 (vals (get db "users")))))
                 :else (pick rng pms))
        items (mapv #(get % "item_id") (get order "items"))
        chosen (vec (take (inc (.nextInt rng (max 1 (count items)))) (seeded-shuffle rng items)))
        chosen (cond-> chosen
                 (chance rng 0.1) (conj (first chosen))
                 (chance rng 0.05) (subvec 0 0))
        new-for (fn [item-id]
                  (let [it (first (filter #(= item-id (get % "item_id")) (get order "items")))
                        variants (keys (get-in db ["products" (get it "product_id") "variants"]))]
                    (if (chance rng 0.1) item-id (or (pick rng variants) item-id))))
        new-items (mapv new-for chosen)
        new-items (if (chance rng 0.05) (vec (butlast new-items)) new-items)
        addr {"address1" "1 Test Way" "address2" "" "city" "Austin" "state" "TX"
              "country" "USA" "zip" "78701"}
        product (pick rng (keys (get db "products")))
        variant-id (pick rng (keys (get-in db ["products" product "variants"])))
        expr (pick rng ["2 + 2" "10 / 4" "3 * (4 + 5)" "1 / 3" "100 - 0.01"
                        "2 ** 10" "7 // 2" "-3 + 1" "1 / 0" "2 +" "abc" "(1 + 2"
                        "0.1 + 0.2" "1234.5678 * 3" "007" "1e5" ".5 + .25"
                        "999999999999 * 999999999999"])
        call (case (.nextInt rng 17)
               0 ["calculate" {"expression" expr}]
               1 ["cancel_pending_order" {"order_id" oid
                                          "reason" (pick rng ["no longer needed" "ordered by mistake" "other"])}]
               2 ["exchange_delivered_order_items" {"order_id" oid "item_ids" chosen
                                                    "new_item_ids" new-items "payment_method_id" pm}]
               3 ["find_user_id_by_name_zip" {"first_name" (str (get-in user ["name" "first_name"]))
                                              "last_name" (if (chance rng 0.2) "Nobody"
                                                              (get-in user ["name" "last_name"]))
                                              "zip" (get-in user ["address" "zip"])}]
               4 ["find_user_id_by_email" {"email" (if (chance rng 0.2) "x@y.z"
                                                       (.toUpperCase ^String (get user "email")))}]
               5 ["get_order_details" {"order_id" oid}]
               6 ["get_product_details" {"product_id" (if (chance rng 0.1) variant-id product)}]
               7 ["get_item_details" {"item_id" (if (chance rng 0.1) product variant-id)}]
               8 ["get_user_details" {"user_id" (if (chance rng 0.1) "nobody_1" (get user "user_id"))}]
               9 ["list_all_product_types" {}]
               10 ["modify_pending_order_address" (assoc addr "order_id" oid)]
               11 ["modify_pending_order_items" {"order_id" oid "item_ids" chosen
                                                 "new_item_ids" new-items "payment_method_id" pm}]
               12 ["modify_pending_order_payment" {"order_id" oid "payment_method_id" pm}]
               13 ["modify_user_address" (assoc addr "user_id" (get user "user_id"))]
               14 ["return_delivered_order_items" {"order_id" oid "item_ids" chosen
                                                   "payment_method_id" pm}]
               15 ["transfer_to_human_agents" {"summary" "help"}]
               16 (pick rng [["get_order_details" {}]
                             ["get_order_details" {"order_id" oid "extra" 1}]
                             ["modify_user_address" {"user_id" "x"}]
                             ["cancel_pending_order" {}]
                             ["no_such_tool" {}]]))]
    {"name" (first call) "arguments" (second call)}))

(defn retail-fuzz-corpus
  "`n` seeded sequences of 1-8 calls. Calls within a sequence tend to target
   the same order so state-dependent paths (double modification, cancel after
   exchange, gift-card depletion) are reached."
  [db seed n]
  (let [rng (java.util.Random. (long seed))]
    (vec (for [i (range n)]
           {"id" (str "fuzz-" seed "-" i)
            "calls" (let [first-call (retail-call rng db)
                          oid (get-in first-call ["arguments" "order_id"])]
                      (into [first-call]
                            (mapv (fn [_]
                                    (let [c (retail-call rng db)]
                                      (if (and oid (contains? (get c "arguments") "order_id")
                                               (chance rng 0.7))
                                        (assoc-in c ["arguments" "order_id"] oid)
                                        c)))
                                  (range (.nextInt rng 8)))))}))))

;; ---------------------------------------------------------------------------
;; Pinned oracle digests (so equivalence is re-checkable without Python)

(defn sequence-digest
  "One digest over a replayed sequence: every tool content, error flag, and
   the final database hash."
  [outputs db-hash]
  (pj/sha256-hex (pj/dumps [outputs db-hash])))

(defn oracle-digests
  "`{sequence-id digest}` from oracle replay results."
  [oracle-results]
  (into (sorted-map)
        (map (fn [{:strs [id outputs db_hash]}]
               [id (sequence-digest outputs db_hash)]))
        oracle-results))

(defn replay-digests [respond-fn hash-fn db corpus]
  (into (sorted-map)
        (map (fn [sequence]
               (let [{:strs [id outputs db]} (replay respond-fn db sequence)]
                 [id (sequence-digest outputs (hash-fn db))])))
        corpus))

;; ---------------------------------------------------------------------------
;; Banking core corpus (base agent tools, KB_search, discoverable mechanics,
;; user tools). Discoverable agent tools have their own per-chunk corpora.

(def ^:private kb-queries
  ["credit card annual fee" "how do I dispute a transaction" "Gold Rewards Card cash back"
   "transfer to human agent reasons" "debit card PIN reset" "" "   " "the"
   "overdraft fee refund policy checking account" "Rho-Bank+ subscription benefits"
   "zzzz nonexistent qqqq" "BNPL" "credit limit increase eligibility 14 days"
   "a a a a" "Crypto-Cash Back wallet" "unlock_discoverable_agent_tool"])

(defn banking-core-corpus
  "Seeded sequences over the core tools. `db` is the base TransactionalDB."
  [db seed n]
  (let [rng (java.util.Random. (long seed))
        pick #(let [xs (vec %)] (nth xs (.nextInt rng (count xs))))
        chance #(< (.nextDouble rng) %)
        users (vec (vals (get-in db ["users" "data"])))
        cc-ids (vec (keys (get-in db ["credit_card_accounts" "data"])))
        account-ids (vec (keys (get-in db ["accounts" "data"])))
        user-tools ["submit_cash_back_dispute_0589" "get_referral_link"
                    "get_card_last_4_digits" "deposit_check_3847"]
        agent-call
        (fn [u]
          (let [uid (if (chance 0.1) (pick ["nobody" "12\"3" 123]) (get u "user_id"))]
            (case (int (.nextInt rng 16))
              0 ["KB_search" {"query" (pick kb-queries)}]
              1 ["KB_search" {"query" (str (pick kb-queries) " " (pick kb-queries))}]
              2 ["get_user_information_by_id" {"user_id" uid}]
              3 ["get_user_information_by_name" {"customer_name" (if (chance 0.2) (str/lower-case (get u "name")) (get u "name"))}]
              4 ["get_user_information_by_email" {"email" (get u "email")}]
              5 ["change_user_email" {"user_id" uid "new_email" "new@example.com"}]
              6 ["get_referrals_by_user" {"user_id" uid}]
              7 ["get_credit_card_transactions_by_user" {"user_id" uid}]
              8 ["get_credit_card_accounts_by_user" {"user_id" uid}]
              9 ["log_verification" {"name" (get u "name") "user_id" (get u "user_id")
                                     "address" (get u "address") "email" (get u "email")
                                     "phone_number" (get u "phone_number")
                                     "date_of_birth" (get u "date_of_birth")
                                     "time_verified" (pick ["2025-11-14 03:40:00 EST" "2025-11-14"])}]
              10 ["give_discoverable_user_tool"
                  {"discoverable_tool_name" (pick (conj user-tools "apply_for_credit_card" "nope"))
                   "arguments" (pick ["{}" (str "{\"user_id\": \"" (get u "user_id") "\"}")
                                      "{\"bogus\": 1}" "{\"self\": 1}" "not json" "[\"user_id\"]"
                                      "{\"user_id\": \"x\", \"transaction_id\": \"t\"}"])}]
              11 ["unlock_discoverable_agent_tool" {"agent_tool_name" (pick ["example_agent_tool_0000" "nope_1234"])}]
              12 ["call_discoverable_agent_tool" {"agent_tool_name" (pick ["example_agent_tool_0000" "nope_1234"])
                                                  "arguments" (pick ["{}" "{\"x\": 1}" "{bad" "[]"])}]
              13 ["list_discoverable_agent_tools" {}]
              14 ["transfer_to_human_agents" (cond-> {"summary" "help"}
                                               (chance 0.8) (assoc "reason" (pick ["other" "fraud_or_security_concern" "made_up"])))]
              15 (pick [["get_current_time" {}] ["get_user_information_by_id" {}]
                        ["log_verification" {"name" "x"}] ["no_such_tool" {}]
                        ["KB_search" {"query" 7}] ["KB_search" {"query" 0}]]))))
        user-call
        (fn [u]
          (let [uid (get u "user_id")]
            (case (int (.nextInt rng 9))
              0 ["apply_for_credit_card" (cond-> {"card_type" (pick ["Gold Rewards Card" "EcoCard" "Titanium Card"])
                                                  "customer_name" (get u "name")
                                                  "annual_income" (pick [100000 100000.0 "85000" "lots" 52000.5])}
                                           (chance 0.5) (assoc "rho_bank_subscription" (pick [true false "yes"])))]
              1 ["submit_referral" {"user_id" uid "account_type" (pick ["Gold Rewards Card" "checking"])}]
              2 ["call_discoverable_user_tool"
                 {"discoverable_tool_name" (pick (conj user-tools "nope"))
                  "arguments" (pick [(str "{\"user_id\": \"" uid "\", \"transaction_id\": \"txn_8d1aa1219382\"}")
                                     (str "{\"user_id\": \"" uid "\", \"card_name\": \"Gold Rewards Card\"}")
                                     (str "{\"credit_card_account_id\": \"" (pick cc-ids) "\"}")
                                     (str "{\"account_id\": \"" (pick account-ids) "\", \"check_amount\": " (pick ["120" "99.5" "-3" "\"abc\""]) "}")
                                     "{}" "{\"extra\": 1}" "{oops"])}]
              3 ["list_discoverable_user_tools" {}]
              4 ["request_human_agent_transfer" {}]
              5 ["submit_transaction" {"user_id" uid "credit_card_type" (pick ["Gold Rewards Card" "Silver Rewards Card" "Unknown Card"])
                                       "merchant_name" (pick ["Costco" "Delta"]) "amount" (pick [127.43 100 "12.5" 0.005])
                                       "category" (pick ["Travel" "Groceries" "Software"])}]
              6 [(pick user-tools) (pick [{"user_id" uid "transaction_id" "txn_8d1aa1219382"}
                                          {"user_id" uid "card_name" "Gold Rewards Card"}
                                          {"credit_card_account_id" (pick cc-ids)}
                                          {"account_id" (pick account-ids) "check_amount" (pick [50 50.0 "7"])}])]
              7 ["get_user_information_by_id" {"user_id" uid}]
              8 ["submit_transaction" {"user_id" uid}])))]
    (vec (for [i (range n)]
           (let [u (pick users)]
             {"id" (str "bk-core-" seed "-" i)
              "task" (when (chance 0.2) (pick ["task_026" "task_027" "task_031"]))
              "calls" (mapv (fn [_]
                              (let [[requestor [name args]] (if (chance 0.35)
                                                              ["user" (user-call u)]
                                                              ["assistant" (agent-call u)])]
                                {"name" name "arguments" args "requestor" requestor}))
                            (range (inc (.nextInt rng 10))))})))))

(defn mask-timing
  "KB search results end with wall-clock timings upstream; compare modulo them."
  [content]
  (if (string? content)
    (str/replace content #"\[Timing: retrieval=\d+ms(, reranking=\d+ms)?, total=\d+ms\]"
                 "[Timing: masked]")
    content))

(defn compare-world-corpus
  "Like `compare-corpus` for world-valued domains with requestors and tasks:
   `respond-fn` is `(fn [world requestor name args] -> {:world :content :error})`,
   `initial-world-fn` receives the whole sequence map (its \"task\" id and
   any fixture keys), `hash-fn` hashes a world."
  [respond-fn initial-world-fn hash-fn corpus oracle-results]
  (let [by-id (into {} (map (juxt #(get % "id") identity)) oracle-results)
        diffs
        (for [{:strs [id task calls] :as sequence} corpus
              :let [theirs (get by-id id)
                    [world outputs]
                    (reduce (fn [[w outs] {:strs [name arguments requestor]}]
                              (let [{w' :world :keys [content error]}
                                    (respond-fn w (if (= "user" requestor) :user :assistant)
                                                name arguments)]
                                [w' (conj outs {"content" (mask-timing content) "error" error})]))
                            [(initial-world-fn sequence) []]
                            calls)
                    call-diffs (keep-indexed
                                (fn [i [o t]]
                                  (let [t (update t "content" mask-timing)]
                                    (when (not= o t)
                                      {:call i :call/name (get-in calls [i "name"])
                                       :call/args (get-in calls [i "arguments"])
                                       :ours o :theirs t})))
                                (map vector outputs (get theirs "outputs")))
                    our-hash (hash-fn world)
                    hash-diff (when (not= our-hash (get theirs "db_hash"))
                                {:ours our-hash :theirs (get theirs "db_hash")})]
              :when (or (nil? theirs) (seq call-diffs) hash-diff)]
          {:id id :task task :missing-oracle? (nil? theirs)
           :calls (vec (take 3 call-diffs)) :call-diff-count (count call-diffs)
           :hash hash-diff})]
    {:sequences (count corpus)
     :calls (reduce + (map #(count (get % "calls")) corpus))
     :mismatched-sequences (count diffs)
     :diffs (vec diffs)}))

(defn via-unlock
  "Rewrite direct calls of `discoverable` tools as unlock + call through
   `call_discoverable_agent_tool` (JSON-string args, parsed upstream with
   `parse_int=float`), exercising the path an agent actually uses."
  [discoverable corpus]
  (vec (for [s corpus]
         (-> s
             (update "id" #(str "unlock-" %))
             (update "calls"
                     (fn [calls]
                       (vec (mapcat (fn [{:strs [name arguments] :as c}]
                                      (if (contains? discoverable name)
                                        [{"name" "unlock_discoverable_agent_tool"
                                          "arguments" {"agent_tool_name" name}
                                          "requestor" "assistant"}
                                         {"name" "call_discoverable_agent_tool"
                                          "arguments" {"agent_tool_name" name
                                                       "arguments" (pj/dumps arguments)}
                                          "requestor" "assistant"}]
                                        [c]))
                                    calls))))))))

(defn gold-corpus
  "One sequence per task replaying its gold actions (with requestors)."
  [tasks]
  (vec (for [task tasks]
         {"id" (get task "id") "task" (get task "id")
          "calls" (mapv (fn [{:strs [name arguments requestor]}]
                          {"name" name "arguments" arguments
                           "requestor" (or requestor "assistant")})
                        (get-in task ["evaluation_criteria" "actions"]))})))

(defn world-oracle-digests
  "`{id digest}` from oracle results, timing lines masked."
  [oracle-results]
  (into (sorted-map)
        (map (fn [{:strs [id outputs db_hash]}]
               [id (sequence-digest (mapv #(update % "content" mask-timing) outputs) db_hash)]))
        oracle-results))

(defn world-replay-digests [respond-fn initial-world-fn hash-fn corpus]
  (into (sorted-map)
        (map (fn [{:strs [id calls] :as sequence}]
               (let [[world outputs]
                     (reduce (fn [[w outs] {:strs [name arguments requestor]}]
                               (let [{w' :world :keys [content error]}
                                     (respond-fn w (if (= "user" requestor) :user :assistant)
                                                 name arguments)]
                                 [w' (conj outs {"content" (mask-timing content) "error" error})]))
                             [(initial-world-fn sequence) []]
                             calls)]
                 [id (sequence-digest outputs (hash-fn world))])))
        corpus))
