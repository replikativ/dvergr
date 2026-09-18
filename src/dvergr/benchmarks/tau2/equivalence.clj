(ns dvergr.benchmarks.tau2.equivalence
  "Differential equivalence between the Clojure tau2 transcription and the
   upstream Python environment.

   A corpus is a vector of `{\"id\" str \"calls\" [{\"name\" str \"arguments\" map}]}`.
   The upstream side is produced once per port version by
   `dev/benchmarks/tau2/oracle.py`; this namespace replays the same corpus in
   Clojure and reports every divergence in tool content, error flag, or final
   database hash."
  (:require [dvergr.benchmarks.tau2.pyjson :as pj]))

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
