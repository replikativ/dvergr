(ns dvergr.benchmarks.tau2.retail
  "Pure Clojure transcription of tau2-bench's retail domain.

   The database is an immutable value, so every world fork is a structural
   share and replay is a reduction. Tools are pure transitions
   `(db, arguments) -> {:db db' :result value}` and raise Python-compatible
   error messages. Upstream quirks are reproduced deliberately, because
   grading hashes the final database: e.g. `modify_pending_order_items`
   assigns the price/options of the LAST requested variant to every modified
   item, and its payment amount is not rounded.

   Upstream: sierra-research/tau2-bench `src/tau2/domains/retail/tools.py`,
   checked for equivalence by `benchmarks/dev/tau2/oracle.py`."
  (:require [clojure.string :as str]
            [dvergr.benchmarks.pyjson :as pj]))

;; ---------------------------------------------------------------------------
;; Python-compatible errors

(defn py-error
  "Raise an exception whose message is what tau2 shows as `Error: <msg>`."
  [message]
  (throw (ex-info message {::py-message message})))

(defn- partial-effects
  "Run `f`; if it raises a Python-compatible error, attach `db` as the state
   upstream would have left behind (Python mutates in place before raising)."
  [db f]
  (try
    (f)
    (catch clojure.lang.ExceptionInfo e
      (if (and (contains? (ex-data e) ::py-message)
               (not (contains? (ex-data e) ::db)))
        (throw (ex-info (.getMessage e) (assoc (ex-data e) ::db db)))
        (throw e)))))

(defn py-message [^Throwable e]
  (or (::py-message (ex-data e))
      (.getMessage e)))

;; ---------------------------------------------------------------------------
;; Schema normalization (pydantic model_dump order, defaults, float coercion)

(defn- ordered [& kvs] (apply array-map kvs))

(defn- ->float [x]
  (if (nil? x) nil (double x)))

(defn- ordered-dict [m f]
  (apply array-map (mapcat (fn [[k v]] [k (f v)]) m)))

(defn- address [a]
  (ordered "address1" (get a "address1") "address2" (get a "address2")
           "city" (get a "city") "country" (get a "country")
           "state" (get a "state") "zip" (get a "zip")))

(defn- variant [v]
  (ordered "item_id" (get v "item_id") "options" (get v "options")
           "available" (get v "available") "price" (->float (get v "price"))))

(defn- product [p]
  (ordered "name" (get p "name") "product_id" (get p "product_id")
           "variants" (ordered-dict (get p "variants") variant)))

(defn- payment-method [pm]
  (case (get pm "source")
    "credit_card" (ordered "source" "credit_card" "id" (get pm "id")
                           "brand" (get pm "brand")
                           "last_four" (get pm "last_four"))
    "gift_card" (ordered "source" "gift_card" "id" (get pm "id")
                         "balance" (->float (get pm "balance")))
    "paypal" (ordered "source" "paypal" "id" (get pm "id"))))

(defn- user [u]
  (ordered "user_id" (get u "user_id")
           "name" (ordered "first_name" (get-in u ["name" "first_name"])
                           "last_name" (get-in u ["name" "last_name"]))
           "address" (address (get u "address"))
           "email" (get u "email")
           "payment_methods" (ordered-dict (get u "payment_methods") payment-method)
           "orders" (vec (get u "orders"))))

(defn- order-item [i]
  (ordered "name" (get i "name") "product_id" (get i "product_id")
           "item_id" (get i "item_id") "price" (->float (get i "price"))
           "options" (get i "options")))

(defn- order-payment [p]
  (ordered "transaction_type" (get p "transaction_type")
           "amount" (->float (get p "amount"))
           "payment_method_id" (get p "payment_method_id")))

(defn- order [o]
  (ordered "order_id" (get o "order_id")
           "user_id" (get o "user_id")
           "address" (address (get o "address"))
           "items" (mapv order-item (get o "items"))
           "status" (get o "status")
           "fulfillments" (mapv #(ordered "tracking_id" (vec (get % "tracking_id"))
                                          "item_ids" (vec (get % "item_ids")))
                                (get o "fulfillments"))
           "payment_history" (mapv order-payment (get o "payment_history"))
           "cancel_reason" (get o "cancel_reason")
           "exchange_items" (some-> (get o "exchange_items") vec)
           "exchange_new_items" (some-> (get o "exchange_new_items") vec)
           "exchange_payment_method_id" (get o "exchange_payment_method_id")
           "exchange_price_difference" (->float (get o "exchange_price_difference"))
           "return_items" (some-> (get o "return_items") vec)
           "return_payment_method_id" (get o "return_payment_method_id")))

(defn normalize-db
  "Validate raw `db.json` data into RetailDB model_dump form."
  [raw]
  (ordered "products" (ordered-dict (get raw "products") product)
           "users" (ordered-dict (get raw "users") user)
           "orders" (ordered-dict (get raw "orders") order)))

(defn load-db [path]
  (normalize-db (pj/parse-file path)))

(defn db-hash
  "tau2 `get_db_hash`: sha256 over the sorted-key JSON of the database."
  [db]
  (pj/dict-hash db))

;; ---------------------------------------------------------------------------
;; Lookups

(defn- get-order [db order-id]
  (or (get-in db ["orders" order-id]) (py-error "Order not found")))

(defn- get-user [db user-id]
  (or (get-in db ["users" user-id]) (py-error "User not found")))

(defn- get-product [db product-id]
  (or (get-in db ["products" product-id]) (py-error "Product not found")))

(defn- get-item [db item-id]
  (or (some (fn [[_ p]] (get-in p ["variants" item-id])) (get db "products"))
      (py-error "Item not found")))

(defn- get-variant [db product-id variant-id]
  (let [p (get-product db product-id)]
    (or (get-in p ["variants" variant-id]) (py-error "Variant not found"))))

(defn- get-payment-method [db user-id pm-id]
  (let [u (get-user db user-id)]
    (or (get-in u ["payment_methods" pm-id])
        (py-error "Payment method not found"))))

(defn- gift-card? [pm] (= "gift_card" (get pm "source")))

(defn- pending? [o] (str/includes? (get o "status") "pending"))

(defn- count-of [x xs] (count (filter #(= x %) xs)))

(defn- item-ids-present! [order item-ids message-fn]
  (let [all (mapv #(get % "item_id") (get order "items"))]
    (doseq [id item-ids]
      (when (> (count-of id item-ids) (count-of id all))
        (py-error (message-fn id))))))

(defn- first-item [order item-id]
  (first (filter #(= item-id (get % "item_id")) (get order "items"))))

(defn- update-balance [db user-id pm-id f]
  (update-in db ["users" user-id "payment_methods" pm-id "balance"]
             #(pj/py-round (f %) 2)))

(defn- python-sorted [xs] (vec (sort xs)))

;; ---------------------------------------------------------------------------
;; calculate: Python `eval` restricted to digits, + - * / ( ) . and spaces.
;; Integers are exact (Python ints); true division and floats use doubles.

(defn- tokenize [s]
  (loop [i 0 out []]
    (if (>= i (count s))
      out
      (let [c (.charAt ^String s i)]
        (cond
          (= c \space) (recur (inc i) out)
          (or (Character/isDigit c) (= c \.))
          (let [j (loop [j i] (if (and (< j (count s))
                                       (let [d (.charAt ^String s j)]
                                         (or (Character/isDigit d) (= d \.))))
                                (recur (inc j)) j))]
            (recur j (conj out [:num (subs s i j)])))
          (and (= c \*) (< (inc i) (count s)) (= \* (.charAt ^String s (inc i))))
          (recur (+ i 2) (conj out [:op "**"]))
          (and (= c \/) (< (inc i) (count s)) (= \/ (.charAt ^String s (inc i))))
          (recur (+ i 2) (conj out [:op "//"]))
          :else (recur (inc i) (conj out [:op (str c)])))))))

(defn- num-value [^String text]
  (cond
    (re-matches #"\d+" text)
    (if (and (> (count text) 1) (str/starts-with? text "0") (re-find #"[1-9]" text))
      (py-error "leading zeros in decimal integer literals are not permitted; use an 0o prefix for octal integers (<string>, line 1)")
      (bigint text))
    (re-matches #"\d*\.\d*" text)
    (if (= "." text) (py-error "invalid syntax (<string>, line 1)") (Double/parseDouble text))
    :else (py-error "invalid syntax (<string>, line 1)")))

(defn- py-num [x] (if (instance? clojure.lang.BigInt x) x (double x)))

(defn- arith [op a b]
  (let [ints? (and (integer? a) (integer? b))]
    (case op
      "+" (if ints? (+' a b) (+ (double a) (double b)))
      "-" (if ints? (-' a b) (- (double a) (double b)))
      "*" (if ints? (*' a b) (* (double a) (double b)))
      "/" (if (zero? b)
            (py-error (if ints? "division by zero" "float division by zero"))
            (if ints? (double (/ a b)) (/ (double a) (double b))))
      "//" (if (zero? b)
             (py-error (if ints? "integer division or modulo by zero" "float floor division by zero"))
             (if ints? (bigint (Math/floorDiv (long a) (long b)))
                 (Math/floor (/ (double a) (double b)))))
      "**" (if (and ints? (not (neg? b)))
             (bigint (.pow (biginteger a) (int b)))
             (Math/pow (double a) (double b))))))

(defn- parse-expr [tokens]
  ;; Python precedence: + - < * / // < unary +/- < **
  (letfn [(peek-op [ts] (let [[t v] (first ts)] (when (= :op t) v)))
          (atom* [ts]
            (let [[t v] (first ts)]
              (cond
                (= t :num) [(num-value v) (rest ts)]
                (= v "(") (let [[x ts'] (sum (rest ts))]
                            (if (= ")" (peek-op ts'))
                              [x (rest ts')]
                              (py-error "'(' was never closed (<string>, line 1)")))
                :else (py-error "invalid syntax (<string>, line 1)"))))
          (power [ts]
            (let [[base ts'] (atom* ts)]
              (if (= "**" (peek-op ts'))
                (let [[e ts''] (unary (rest ts'))] [(arith "**" base e) ts''])
                [base ts'])))
          (unary [ts]
            (case (peek-op ts)
              "-" (let [[x ts'] (unary (rest ts))] [(if (integer? x) (-' x) (- x)) ts'])
              "+" (unary (rest ts))
              (power ts)))
          (term [ts]
            (loop [[x ts] (unary ts)]
              (let [op (peek-op ts)]
                (if (#{"*" "/" "//"} op)
                  (let [[y ts'] (unary (rest ts))] (recur [(arith op x y) ts']))
                  [x ts]))))
          (sum [ts]
            (loop [[x ts] (term ts)]
              (let [op (peek-op ts)]
                (if (#{"+" "-"} op)
                  (let [[y ts'] (term (rest ts))] (recur [(arith op x y) ts']))
                  [x ts]))))]
    (let [[x ts] (sum tokens)]
      (when (seq ts) (py-error "invalid syntax (<string>, line 1)"))
      x)))

(defn calculate-expression [expression]
  (when-not (every? #(str/includes? "0123456789+-*/(). " (str %)) expression)
    (py-error "Invalid characters in expression"))
  (let [tokens (tokenize expression)]
    (when (empty? tokens)
      (py-error "invalid syntax (<string>, line 1)"))
    (pj/py-str (pj/py-round (double (py-num (parse-expr tokens))) 2))))

;; ---------------------------------------------------------------------------
;; Tools

(defn- new-address [{:strs [address1 address2 city state country zip]}]
  (ordered "address1" address1 "address2" address2 "city" city
           "country" country "state" state "zip" zip))

(defn- payment [type amount pm-id]
  (ordered "transaction_type" type "amount" amount "payment_method_id" pm-id))

(defn- cancel-pending-order [db {:strs [order_id reason]}]
  (let [o (get-order db order_id)]
    (when (not= "pending" (get o "status"))
      (py-error "Non-pending order cannot be cancelled"))
    (when-not (#{"no longer needed" "ordered by mistake"} reason)
      (py-error "Invalid reason"))
    (let [[db refunds]
          (reduce (fn [[db refunds] p]
                    (let [pm-id (get p "payment_method_id")
                          refund (payment "refund" (get p "amount") pm-id)
                          uid (get o "user_id")
                          pm (partial-effects
                              db #(do (get-user db uid)
                                      (get-payment-method db uid pm-id)))]
                      [(if (gift-card? pm)
                         (update-balance db uid pm-id #(+ % (get p "amount")))
                         db)
                       (conj refunds refund)]))
                  [db []]
                  (get o "payment_history"))
          o (-> o
                (assoc "status" "cancelled" "cancel_reason" reason)
                (update "payment_history" into refunds))]
      {:db (assoc-in db ["orders" order_id] o) :result o})))

(defn- price-difference [db o item-ids new-item-ids check-same?]
  (reduce (fn [diff [item-id new-id]]
            (when (and check-same? (= item-id new-id))
              (py-error "The new item id should be different from the old item id"))
            (let [item (or (first-item o item-id)
                           (py-error (str "Item " item-id " not found")))
                  v (get-variant db (get item "product_id") new-id)]
              (when-not (get v "available")
                (py-error (str "New item " new-id " not found or available")))
              (+ diff (- (get v "price") (get item "price")))))
          0
          (map vector item-ids new-item-ids)))

(defn- exchange-delivered-order-items
  [db {:strs [order_id item_ids new_item_ids payment_method_id]}]
  (let [o (get-order db order_id)]
    (when (not= "delivered" (get o "status"))
      (py-error "Non-delivered order cannot be exchanged"))
    (item-ids-present! o item_ids #(str "Number of " % " not found."))
    (when (not= (count item_ids) (count new_item_ids))
      (py-error "The number of items to be exchanged should match."))
    (let [diff (pj/py-round (price-difference db o item_ids new_item_ids false) 2)
          pm (get-payment-method db (get o "user_id") payment_method_id)]
      (when (and (gift-card? pm) (< (get pm "balance") diff))
        (py-error "Insufficient gift card balance to pay for the price difference"))
      (let [o (assoc o
                     "status" "exchange requested"
                     "exchange_items" (python-sorted item_ids)
                     "exchange_new_items" (python-sorted new_item_ids)
                     "exchange_payment_method_id" payment_method_id
                     "exchange_price_difference" diff)]
        {:db (assoc-in db ["orders" order_id] o) :result o}))))

(defn- find-user-id-by-name-zip [db {:strs [first_name last_name zip]}]
  {:db db
   :result (or (some (fn [[uid u]]
                       (when (and (= (str/lower-case (get-in u ["name" "first_name"]))
                                     (str/lower-case first_name))
                                  (= (str/lower-case (get-in u ["name" "last_name"]))
                                     (str/lower-case last_name))
                                  (= (get-in u ["address" "zip"]) zip))
                         uid))
                     (get db "users"))
               (py-error "User not found"))})

(defn- find-user-id-by-email [db {:strs [email]}]
  {:db db
   :result (or (some (fn [[uid u]]
                       (when (= (str/lower-case (get u "email"))
                                (str/lower-case email))
                         uid))
                     (get db "users"))
               (py-error "User not found"))})

(defn- list-all-product-types [db _]
  {:db db
   :result (pj/dumps (into {} (map (fn [[_ p]] [(get p "name") (get p "product_id")]))
                           (get db "products"))
                     true)})

(defn- modify-pending-order-address [db {:strs [order_id] :as args}]
  (let [o (get-order db order_id)]
    (when-not (pending? o) (py-error "Non-pending order cannot be modified"))
    (let [o (assoc o "address" (new-address args))]
      {:db (assoc-in db ["orders" order_id] o) :result o})))

(defn- modify-pending-order-items
  [db {:strs [order_id item_ids new_item_ids payment_method_id]}]
  (let [o (get-order db order_id)]
    (when (not= "pending" (get o "status"))
      (py-error "Non-pending order cannot be modified"))
    (item-ids-present! o item_ids #(str % " not found"))
    (when (not= (count item_ids) (count new_item_ids))
      (py-error "The number of items to be exchanged should match"))
    (let [diff (price-difference db o item_ids new_item_ids true)
          ;; Upstream leaks the loop variable `variant`: every modified item
          ;; receives the last requested variant's price and options.
          last-variant (when (seq item_ids)
                         (get-variant db
                                      (get (first-item o (last item_ids)) "product_id")
                                      (last new_item_ids)))
          uid (get o "user_id")
          pm (get-payment-method db uid payment_method_id)]
      (when (and (gift-card? pm) (< (get pm "balance") diff))
        (py-error "Insufficient gift card balance to pay for the new item"))
      (let [o (update o "payment_history" conj
                      (payment (if (pos? diff) "payment" "refund")
                               ;; OrderPayment(...) validates: amount coerces to float
                               ;; even for an empty modification (abs(0) -> 0.0).
                               (Math/abs (double diff))
                               payment_method_id))
            db (if (gift-card? pm)
                 (update-balance db uid payment_method_id #(- % diff))
                 db)
            items (reduce (fn [items [item-id new-id]]
                            (let [idx (or (first (keep-indexed
                                                  (fn [i it] (when (= item-id (get it "item_id")) i))
                                                  items))
                                          (py-error (str "Item " item-id " not found")))]
                              (update items idx assoc
                                      "item_id" new-id
                                      "price" (get last-variant "price")
                                      "options" (get last-variant "options"))))
                          (get o "items")
                          (map vector item_ids new_item_ids))
            o (assoc o "items" items "status" "pending (item modified)")]
        {:db (assoc-in db ["orders" order_id] o) :result o}))))

(defn- modify-pending-order-payment [db {:strs [order_id payment_method_id]}]
  (let [o (get-order db order_id)]
    (when-not (pending? o) (py-error "Non-pending order cannot be modified"))
    (let [uid (get o "user_id")
          pm (get-payment-method db uid payment_method_id)
          history (get o "payment_history")]
      (when (or (not= 1 (count history))
                (not= "payment" (get (first history) "transaction_type")))
        (py-error "There should be exactly one payment for a pending order"))
      (let [old-id (get (first history) "payment_method_id")
            amount (get (first history) "amount")]
        (when (= old-id payment_method_id)
          (py-error "The new payment method should be different from the current one"))
        (when (and (gift-card? pm) (< (get pm "balance") amount))
          (py-error "Insufficient gift card balance to pay for the order"))
        (let [o (update o "payment_history" into
                        [(payment "payment" amount payment_method_id)
                         (payment "refund" amount old-id)])
              db (if (gift-card? pm)
                   (update-balance db uid payment_method_id #(- % amount))
                   db)
              old-pm (partial-effects
                      (assoc-in db ["orders" order_id] o)
                      #(get-payment-method db uid old-id))
              db (if (gift-card? old-pm)
                   (update-balance db uid old-id #(+ % amount))
                   db)]
          {:db (assoc-in db ["orders" order_id] o) :result o})))))

(defn- modify-user-address [db {:strs [user_id] :as args}]
  (let [u (assoc (get-user db user_id) "address" (new-address args))]
    {:db (assoc-in db ["users" user_id] u) :result u}))

(defn- return-delivered-order-items [db {:strs [order_id item_ids payment_method_id]}]
  (let [o (get-order db order_id)]
    (when (not= "delivered" (get o "status"))
      (py-error "Non-delivered order cannot be returned"))
    (let [u (get-user db (get o "user_id"))
          pm (get-payment-method db (get u "user_id") payment_method_id)]
      (when (and (not (gift-card? pm))
                 (not= payment_method_id
                       (get-in o ["payment_history" 0 "payment_method_id"])))
        (py-error "Payment method should be the original payment method"))
      (item-ids-present! o item_ids (constantly "Some item not found"))
      (let [o (assoc o
                     "status" "return requested"
                     "return_items" (python-sorted item_ids)
                     "return_payment_method_id" payment_method_id)]
        {:db (assoc-in db ["orders" order_id] o) :result o}))))

(defn- reader [f] (fn [db args] {:db db :result (f db args)}))

(def tools
  "Tool name -> {:params [..] :type :read|:write|:generic :fn (db args) -> {:db :result}}.
   Parameter order is the Python signature order (used for TypeError text)."
  {"calculate" {:params ["expression"] :type :generic
                :fn (reader (fn [_ {:strs [expression]}] (calculate-expression expression)))}
   "cancel_pending_order" {:params ["order_id" "reason"] :type :write
                           :fn cancel-pending-order}
   "exchange_delivered_order_items" {:params ["order_id" "item_ids" "new_item_ids" "payment_method_id"]
                                     :type :write :fn exchange-delivered-order-items}
   "find_user_id_by_name_zip" {:params ["first_name" "last_name" "zip"] :type :read
                               :fn find-user-id-by-name-zip}
   "find_user_id_by_email" {:params ["email"] :type :read :fn find-user-id-by-email}
   "get_order_details" {:params ["order_id"] :type :read
                        :fn (reader (fn [db {:strs [order_id]}] (get-order db order_id)))}
   "get_product_details" {:params ["product_id"] :type :read
                          :fn (reader (fn [db {:strs [product_id]}] (get-product db product_id)))}
   "get_item_details" {:params ["item_id"] :type :read
                       :fn (reader (fn [db {:strs [item_id]}] (get-item db item_id)))}
   "get_user_details" {:params ["user_id"] :type :read
                       :fn (reader (fn [db {:strs [user_id]}] (get-user db user_id)))}
   "list_all_product_types" {:params [] :type :read :fn list-all-product-types}
   "modify_pending_order_address" {:params ["order_id" "address1" "address2" "city" "state" "country" "zip"]
                                   :type :write :fn modify-pending-order-address}
   "modify_pending_order_items" {:params ["order_id" "item_ids" "new_item_ids" "payment_method_id"]
                                 :type :write :fn modify-pending-order-items}
   "modify_pending_order_payment" {:params ["order_id" "payment_method_id"] :type :write
                                   :fn modify-pending-order-payment}
   "modify_user_address" {:params ["user_id" "address1" "address2" "city" "state" "country" "zip"]
                          :type :write :fn modify-user-address}
   "return_delivered_order_items" {:params ["order_id" "item_ids" "payment_method_id"] :type :write
                                   :fn return-delivered-order-items}
   "transfer_to_human_agents" {:params ["summary"] :type :generic
                               :fn (reader (constantly "Transfer successful"))}})

(defn- python-list-names [names]
  (let [q (mapv #(str "'" % "'") names)]
    (case (count q)
      1 (first q)
      2 (str (q 0) " and " (q 1))
      (str (str/join ", " (butlast q)) ", and " (last q)))))

(defn- check-arguments! [tool-name params args]
  (let [unexpected (first (remove (set params) (keys args)))]
    (when unexpected
      (py-error (str "RetailTools." tool-name "() got an unexpected keyword argument '"
                     unexpected "'"))))
  (let [missing (remove #(contains? args %) params)]
    (when (seq missing)
      (py-error (str "RetailTools." tool-name "() missing " (count missing)
                     " required positional argument" (when (> (count missing) 1) "s")
                     ": " (python-list-names missing))))))

(defn invoke
  "Apply one tool call. Returns `{:db db' :result value}` or throws with a
   Python-compatible message."
  [db tool-name args]
  (let [{:keys [params fn]} (or (get tools tool-name)
                                (py-error (str "Tool '" tool-name "' not found.")))
        args (or args {})]
    (check-arguments! tool-name params args)
    (fn db args)))

(defn tool-content
  "tau2 `Environment.to_json_str` for a tool result."
  [result]
  (cond
    (string? result) result
    (nil? result) "null"
    :else (pj/dumps result)))

(defn respond
  "tau2 `Environment.get_response`: never throws. Returns
   `{:db db' :content string :error boolean}`; failed calls leave `db` as is."
  [db tool-name args]
  (try
    (let [{db' :db result :result} (invoke db tool-name args)]
      {:db db' :content (tool-content result) :error false})
    (catch clojure.lang.ExceptionInfo e
      (if (contains? (ex-data e) ::py-message)
        {:db (get (ex-data e) ::db db) :content (str "Error: " (py-message e)) :error true}
        (throw e)))))
