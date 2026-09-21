(ns dvergr.benchmarks.tau2.airline.corpus
  "Seeded differential corpus for the airline transcription.

   Sequences are built around one user so that state-dependent paths are
   reached: booking with split certificate/gift-card/credit-card payments and
   then changing, cancelling, or re-booking the new reservation; certificates
   sent and then spent; gift cards drained by baggage and flight changes;
   cabin changes that re-price every leg. Every tool also gets malformed
   variants (wrong JSON types, missing or extra fields, mixed lists, unknown
   ids, repeated payment ids) so pydantic validation text, Python TypeErrors,
   and partial effects are compared too. The corpus is a pure function of the
   base database and the seed; `benchmarks/dev/tau2/airline/oracle_airline.py`
   replays it upstream."
  (:require [clojure.string :as str]))

(defn- rng-fns [seed]
  (let [rng (java.util.Random. (long seed))
        pick (fn [xs] (let [xs (vec xs)] (when (seq xs) (nth xs (.nextInt rng (count xs))))))
        chance (fn [p] (< (.nextDouble rng) p))
        int* (fn [n] (.nextInt rng (int n)))]
    {:pick pick :chance chance :int int*}))

(def ^:private cabins ["business" "economy" "basic_economy"])
(def ^:private airports ["SFO" "JFK" "LAX" "ORD" "DFW" "DEN" "SEA" "ATL" "MIA" "BOS" "PHX" "IAH"
                         "LAS" "MCO" "EWR" "CLT" "MSP" "DTW" "PHL" "LGA"])

(defn- index [db]
  (let [flights (vec (vals (get db "flights")))
        available (vec (for [f flights [d s] (get f "dates") :when (= "available" (get s "status"))]
                         [f d]))]
    {:flights flights
     :available available
     :by-route (group-by (juxt #(get % "origin") #(get % "destination")) flights)
     :by-origin (group-by #(get % "origin") flights)
     :users (vec (sort-by #(get % "user_id") (vals (get db "users"))))
     :dates (vec (for [d (range 1 32)] (format "2024-05-%02d" d)))}))

(defn- passengers-of [{:keys [pick int chance]} user n]
  (let [self {"first_name" (get-in user ["name" "first_name"])
              "last_name" (get-in user ["name" "last_name"]) "dob" (get user "dob")}
        pool (into [self] (get user "saved_passengers"))]
    (vec (take n (concat pool (repeat {"first_name" (pick ["Ava" "Noah" "Mia"])
                                       "last_name" (pick ["Lee" "Kim" "Diaz"])
                                       "dob" (format "19%02d-0%d-1%d" (+ 50 (int 49)) (inc (int 9)) (int 10))}))))))

(defn- mangle-passengers [{:keys [pick chance]} ps]
  (pick [(assoc-in ps [0 "dob"] 19900101)
         (update ps 0 dissoc "dob")
         (conj ps "Jane Doe")
         (update ps 0 assoc "middle_name" "Q")
         "Jane Doe"
         (first ps)
         nil
         []
         (mapv #(dissoc % "last_name") ps)]))

(defn- flight-info [f d] {"flight_number" (get f "flight_number") "date" d})

(defn- plan-trip
  "An available outbound instance plus an optional return on the reverse route."
  [{:keys [pick chance]} {:keys [available by-route]}]
  (let [[f d] (pick available)
        back (when (chance 0.4)
               (let [cands (for [g (get by-route [(get f "destination") (get f "origin")])
                                 [d2 s] (get g "dates")
                                 :when (and (= "available" (get s "status")) (pos? (compare d2 d)))]
                             [g d2])]
                 (pick cands)))]
    {:legs (cond-> [[f d]] back (conj back))
     :origin (get f "origin") :destination (get f "destination")
     :flight_type (if back "round_trip" (pick ["one_way" "one_way" "round_trip"]))}))

(defn- price-of [db [f d] cabin]
  (get-in db ["flights" (get f "flight_number") "dates" d "prices" cabin]))

(defn- split-payments
  "Pay `total` from certificates, then gift cards, then a credit card."
  [{:keys [pick chance]} user total]
  (let [pms (vals (get user "payment_methods"))
        by (group-by #(get % "source") pms)
        [pays remaining]
        (reduce (fn [[pays remaining] pm]
                  (if (and (pos? remaining) (chance 0.7))
                    (let [amt (min remaining (long (Math/floor (get pm "amount"))))]
                      (if (pos? amt) [(conj pays {"payment_id" (get pm "id") "amount" amt}) (- remaining amt)]
                          [pays remaining]))
                    [pays remaining]))
                [[] total]
                (concat (take 1 (get by "certificate")) (take 2 (get by "gift_card"))))
        card (pick (get by "credit_card"))]
    (cond-> pays
      (and card (pos? remaining)) (conj {"payment_id" (get card "id") "amount" remaining}))))

(defn- book-call [{:keys [pick chance int] :as r} db ix user & [valid?]]
  (let [{:keys [legs origin destination flight_type]} (plan-trip r ix)
        legs (if (chance 0.05) (conj legs (first legs)) legs)
        cabin (pick cabins)
        n (inc (int 3))
        insurance (pick ["yes" "no" "no"])
        nonfree (pick [0 0 1 2])
        total (+ (* n (reduce + (map #(or (price-of db % cabin) 0) legs)))
                 (if (= insurance "yes") (* 30 n) 0) (* 50 nonfree))
        pays (split-payments r user total)
        ps (passengers-of r user n)
        args {"user_id" (get user "user_id") "origin" origin "destination" destination
              "flight_type" flight_type "cabin" cabin
              "flights" (mapv (fn [[f d]] (flight-info f d)) legs)
              "passengers" ps "payment_methods" pays
              "total_baggages" (+ nonfree (int 3)) "nonfree_baggages" nonfree
              "insurance" insurance}]
    (if (or valid? (chance 0.55))
      args
      (case (int 17)
        0 (assoc args "user_id" (pick ["nobody_1" nil 7]))
        1 (assoc args "cabin" (pick ["first" "Economy" nil]))
        2 (assoc args "flight_type" (pick ["roundtrip" 1]) "insurance" (pick ["maybe" true "no"]))
        3 (assoc args "passengers" (mangle-passengers r ps))
        4 (assoc args "payment_methods" (if (seq pays)
                                          (pick [(update-in pays [0 "amount"] inc)
                                                 (conj pays (first pays))
                                                 (assoc-in pays [0 "amount"] (str (get-in pays [0 "amount"])))
                                                 (assoc-in pays [0 "amount"] (+ 0.5 (get-in pays [0 "amount"])))
                                                 (update pays 0 dissoc "amount")
                                                 (conj pays "credit_card_0000000")
                                                 (assoc-in pays [0 "payment_id"] "credit_card_0000000")])
                                          []))
        5 (assoc args "nonfree_baggages" (pick [(str nonfree) (double nonfree) true "two" 1.5 nil]))
        6 (assoc args "total_baggages" (pick ["3" 2.0 -1 "x" [1]]))
        7 (assoc args "flights" (pick [[] "HAT001" {"flight_number" "HAT001" "date" "2024-05-20"}
                                       [{"flight_number" "HAT001"}]
                                       (conj (get args "flights") "HAT002")
                                       [{"flight_number" "HAT999" "date" "2024-05-20"}]
                                       [{"flight_number" (get-in args ["flights" 0 "flight_number"])
                                         "date" (pick ["2024-05-01" "2024-06-01" "2024-05-15"])}]
                                       [{"flight_number" 1 "date" "2024-05-20"}]]))
        8 (assoc args "origin" (pick [1 nil "XXX"]) "destination" (pick [destination ["JFK"]]))
        9 (dissoc args (pick (vec (keys args))))
        10 (assoc args "coupon" "SAVE10")
        11 (assoc args "passengers" (vec (repeat 9 (first ps)))
                  "payment_methods" (split-payments r user (* 9 (reduce + (map #(or (price-of db % cabin) 0) legs)))))
        12 (let [certs (filter #(= "certificate" (get % "source")) (vals (get user "payment_methods")))]
             (if-let [c (first certs)]
               (assoc args "payment_methods" [{"payment_id" (get c "id") "amount" 1}
                                              {"payment_id" (get c "id") "amount" 1}
                                              {"payment_id" (get (first (vals (get user "payment_methods"))) "id")
                                               "amount" (- total 2)}])
               (assoc args "payment_methods" (conj pays (first pays)))))
        13 (assoc args "payment_methods" [{"payment_id" "certificate_3221322" "amount" total}])
        14 (assoc args "flights" [{"flight_number" (get (pick (:flights ix)) "flight_number")
                                   "date" (pick (:dates ix))}])
        15 (assoc args "passengers" {} "payment_methods" "" "flights" [])
        16 (assoc args "insurance" "yes" "payment_methods" (split-payments r user (+ total (* 30 n))))))))

(defn- flights-update-call [{:keys [pick chance int] :as r} db ix res-id res]
  (let [cur (mapv #(select-keys % ["flight_number" "date"]) (get res "flights"))
        cabin (if (chance 0.5) (get res "cabin") (pick cabins))
        swap-leg (fn [fl]
                   (let [f (get-in db ["flights" (get fl "flight_number")])
                         alts (for [g (get (:by-route ix) [(get f "origin") (get f "destination")])
                                    [d s] (get g "dates")
                                    :when (= "available" (get s "status"))]
                                (flight-info g d))]
                     (or (pick alts) fl)))
        flights (case (int 5)
                  0 cur
                  1 (if (seq cur) (update cur (int (count cur)) swap-leg) cur)
                  2 (mapv swap-leg cur)
                  3 (vec (take 1 cur))
                  4 (conj cur (let [[f d] (pick (:available ix))] (flight-info f d))))
        user (get-in db ["users" (get res "user_id")])
        pm (if (chance 0.1) "credit_card_0000000" (pick (keys (get user "payment_methods"))))]
    ["update_reservation_flights"
     (if (chance 0.8)
       {"reservation_id" res-id "cabin" cabin "flights" flights "payment_id" pm}
       (case (int 7)
         0 {"reservation_id" res-id "cabin" (pick ["first" nil "Business"]) "flights" flights "payment_id" pm}
         1 {"reservation_id" res-id "cabin" "first" "flights" [] "payment_id" pm}
         2 {"reservation_id" res-id "cabin" cabin "flights" (conj flights "HAT001") "payment_id" pm}
         3 {"reservation_id" res-id "cabin" cabin "flights" [{"flight_number" "HAT001"}] "payment_id" pm}
         4 {"reservation_id" res-id "cabin" cabin "flights" [] "payment_id" pm}
         5 {"reservation_id" res-id "cabin" cabin "flights" nil "payment_id" [pm]}
         6 {"reservation_id" res-id "cabin" cabin
            "flights" [{"flight_number" (get (pick (:flights ix)) "flight_number")
                        "date" (pick (:dates ix))}]
            "payment_id" pm}))]))

(defn- py-count [x] (cond (string? x) (count x) (coll? x) (count x) :else 1))

(defn- call [{:keys [pick chance int] :as r} db ix user res-id]
  (let [uid (get user "user_id")
        res (get-in db ["reservations" res-id])
        pms (vec (keys (get user "payment_methods")))
        pm (cond (chance 0.08) "credit_card_0000000"
                 (chance 0.3) (pick (filter #(str/starts-with? % "gift_card") pms))
                 :else (pick pms))
        pm (or pm (pick pms) "credit_card_0000000")
        rid (if (chance 0.06) (pick ["ZZZZZZ" nil ["ABC"]]) res-id)
        [f d] (pick (:available ix))
        some-flight (pick (:flights ix))]
    (case (int 20)
      0 ["get_user_details" {"user_id" (if (chance 0.1) (pick ["nobody_1" nil 5 {"id" 1}]) uid)}]
      1 ["get_reservation_details" {"reservation_id" rid}]
      2 ["search_direct_flight" {"origin" (if (chance 0.1) (pick [nil "XXX" 1]) (get f "origin"))
                                 "destination" (if (chance 0.1) (pick [nil "XXX"]) (get f "destination"))
                                 "date" (if (chance 0.1) (pick ["2024-05-99" "May 20" nil ["2024-05-20"]]) d)}]
      3 ["search_onestop_flight" {"origin" (if (chance 0.1) (pick [nil "XXX"]) (get f "origin"))
                                  "destination" (if (chance 0.15) (pick [nil "XXX"]) (pick airports))
                                  "date" (if (chance 0.1) (pick ["2024-05-99" nil 20]) (pick (:dates ix)))}]
      4 ["get_flight_status" {"flight_number" (if (chance 0.1) (pick ["HAT999" nil]) (get some-flight "flight_number"))
                              "date" (if (chance 0.1) (pick ["2024-06-01" 5 ["x"]]) (pick (:dates ix)))}]
      5 (if (chance 0.8) ["list_all_airports" {}] ["list_all_airports" {"country" "US"}])
      6 ["calculate" {"expression" (pick ["2 + 2" "10 / 4" "3 * (4 + 5)" "1 / 3" "100 - 0.01" "(1 + 2"
                                          "1 / 0" "abc" "250 * 3 + 30" "0.1 + 0.2" "7 // 2"])}]
      7 ["transfer_to_human_agents" {"summary" "Customer wants a refund."}]
      8 ["send_certificate" {"user_id" (if (chance 0.1) "nobody_1" uid)
                             "amount" (if (chance 0.25) (pick [100.0 "150" "abc" nil true 12.5 "1e3" [50]])
                                          (pick [50 100 150 200 250 400]))}]
      9 ["cancel_reservation" {"reservation_id" rid}]
      10 ["update_reservation_baggages"
          {"reservation_id" rid
           "total_baggages" (if (chance 0.1) (pick ["3" 2.5 nil]) (int 5))
           "nonfree_baggages" (if (chance 0.15) (pick [1.5 2.3 "2" nil true 0.0 -1])
                                  (int 4))
           "payment_id" pm}]
      11 (if res (flights-update-call r db ix res-id res)
             ["update_reservation_flights" {"reservation_id" rid "cabin" "economy"
                                            "flights" [(flight-info f d)] "payment_id" pm}])
      12 (let [n (py-count (get res "passengers"))
               ps (passengers-of r user (max 1 (if (chance 0.8) n (inc (int 3)))))]
           ["update_reservation_passengers"
            {"reservation_id" rid
             "passengers" (if (chance 0.25) (mangle-passengers r ps) ps)}])
      13 ["book_reservation" (book-call r db ix user)]
      14 ["book_reservation" (book-call r db ix user)]
      15 ["update_reservation_baggages" {"reservation_id" rid "total_baggages" 2 "nonfree_baggages" 1
                                         "payment_id" (pick (concat pms ["certificate_3221322"]))}]
      16 ["update_reservation_flights" {"reservation_id" (pick ["HATHAT" "HATHAU" rid]) "cabin" (pick cabins)
                                        "flights" [(flight-info f d)] "payment_id" pm}]
      17 ["cancel_reservation" {"reservation_id" (pick ["HATHAT" "HATHAU" rid])}]
      18 ["get_reservation_details" {"reservation_id" (pick ["HATHAT" "HATHAU" "HATHAV"])}]
      19 (pick [["get_user_details" {}]
                ["get_reservation_details" {"reservation_id" rid "user_id" uid}]
                ["book_reservation" {"user_id" uid}]
                ["cancel_reservation" {"reservation_id" rid "reason" "change of plans"}]
                ["update_reservation_baggages" {"reservation_id" rid}]
                ["search_direct_flight" {"origin" "JFK" "date" "2024-05-20"}]
                ["send_certificate" {"user_id" uid "amount" 100 "reason" "delay"}]
                ["think" {"thought" "hmm"}]
                ["no_such_tool" {}]]))))

(defn- scenario
  "Hand-shaped sequences for deep paths random calls rarely reach."
  [{:keys [pick chance int] :as r} db ix user res-id]
  (let [uid (get user "user_id")
        pms (vals (get user "payment_methods"))
        of (fn [src] (filter #(= src (get % "source")) pms))
        card (get (first (of "credit_card")) "id")
        gift (get (first (of "gift_card")) "id")
        cert (first (of "certificate"))
        res (get-in db ["reservations" res-id])
        book #(vector "book_reservation" (book-call r db ix user true))
        fl (fn [] (let [[f d] (pick (:available ix))] (flight-info f d)))]
    (case (int 9)
      ;; four bookings: the fourth exhausts HATHAT/HATHAU/HATHAV
      0 (-> (vec (repeatedly 4 book))
            (conj ["get_user_details" {"user_id" uid}]
                  ["cancel_reservation" {"reservation_id" "HATHAT"}]
                  ["update_reservation_flights" {"reservation_id" "HATHAT" "cabin" (pick cabins)
                                                 "flights" [(fl)] "payment_id" (or gift card "x")}]))
      ;; certificates: send until exhausted, then spend one
      1 (-> (vec (for [a (take (+ 2 (int 3)) (cycle [100 250.0 "75" 50]))]
                   ["send_certificate" {"user_id" uid "amount" a}]))
            (conj ["get_user_details" {"user_id" uid}]
                  ["update_reservation_baggages" {"reservation_id" res-id "total_baggages" 3
                                                  "nonfree_baggages" 3 "payment_id" "certificate_3221322"}]
                  (let [[f d] (pick (:available ix))
                        cabin (pick cabins)
                        price (or (price-of db [f d] cabin) 0)]
                    ["book_reservation" {"user_id" uid "origin" (get f "origin")
                                         "destination" (get f "destination") "flight_type" "one_way"
                                         "cabin" cabin "flights" [(flight-info f d)]
                                         "passengers" (passengers-of r user 1)
                                         "payment_methods" (cond-> [{"payment_id" "certificate_3221322"
                                                                     "amount" (min 100 price)}]
                                                             (> price 100)
                                                             (conj {"payment_id" (or card gift "x")
                                                                    "amount" (- price 100)}))
                                         "total_baggages" 0 "nonfree_baggages" 0 "insurance" "no"}])
                  ["get_user_details" {"user_id" uid}]))
      ;; the same certificate twice: KeyError after partial deductions
      2 (let [[f d] (pick (:available ix))
              cabin (pick cabins)
              price (or (price-of db [f d] cabin) 0)
              pays (cond-> []
                     gift (conj {"payment_id" gift "amount" 1})
                     cert (into [{"payment_id" (get cert "id") "amount" 1}
                                 {"payment_id" (get cert "id") "amount" 1}]))
              rest* (- price (reduce + (map #(get % "amount") pays)))]
          [["book_reservation" {"user_id" uid "origin" (get f "origin") "destination" (get f "destination")
                                "flight_type" "one_way" "cabin" cabin "flights" [(flight-info f d)]
                                "passengers" (passengers-of r user 1)
                                "payment_methods" (cond-> pays card (conj {"payment_id" card "amount" rest*}))
                                "total_baggages" 0 "nonfree_baggages" 0 "insurance" "no"}]
           ["get_user_details" {"user_id" uid}]])
      ;; fractional baggage: gift card charged, then Payment(int) rejects it
      3 (let [base (let [n (get res "nonfree_baggages")] (if (number? n) n 0))]
          [["update_reservation_baggages" {"reservation_id" res-id "total_baggages" 4
                                           "nonfree_baggages" (+ base (pick [0.33 1.5 2.0 0.01 1.77]))
                                           "payment_id" (or gift card "x")}]
           ["update_reservation_baggages" {"reservation_id" res-id "total_baggages" "5"
                                           "nonfree_baggages" (+ base (pick [1 2 0.5]))
                                           "payment_id" (or gift card "x")}]
           ["get_user_details" {"user_id" uid}]
           ["get_reservation_details" {"reservation_id" res-id}]])
      ;; unknown cabin: only a new flight's seat lookup fails
      4 [["update_reservation_flights" {"reservation_id" res-id "cabin" (pick ["first" "premium" "Business"])
                                        "flights" (if (chance 0.5)
                                                    (mapv #(select-keys % ["flight_number" "date"]) (get res "flights"))
                                                    [(fl)])
                                        "payment_id" (or card gift "x")}]
         ["update_reservation_flights" {"reservation_id" res-id "cabin" "first" "flights" []
                                        "payment_id" (or gift card "x")}]
         ["get_reservation_details" {"reservation_id" res-id}]
         ["get_user_details" {"user_id" uid}]]
      ;; string baggage count reaches Python's `int += str`
      5 [(let [[_ args] (book)] ["book_reservation" (assoc args "nonfree_baggages" (pick ["1" "0" " 2 " "1.0"]))])
         (let [[_ args] (book)] ["book_reservation" (assoc args "nonfree_baggages" (pick [1.0 true 0.0]))])
         ["get_reservation_details" {"reservation_id" "HATHAT"}]]
      ;; raw passengers stored, then reused by length
      6 (let [n (count (get res "passengers"))]
          [["update_reservation_passengers" {"reservation_id" res-id
                                             "passengers" (pick [(apply str (repeat n "x"))
                                                                 (vec (repeat n "Jane"))
                                                                 (into [{"first_name" "A"}] (repeat (dec n) 7))])}]
           ["get_reservation_details" {"reservation_id" res-id}]
           ["update_reservation_flights" {"reservation_id" res-id "cabin" (get res "cabin")
                                          "flights" (mapv #(select-keys % ["flight_number" "date"]) (get res "flights"))
                                          "payment_id" (or card gift "x")}]
           ["cancel_reservation" {"reservation_id" res-id}]])
      ;; cabin change and leg swap on the user's own reservation, twice
      7 (let [[_ a] (flights-update-call r db ix res-id res)
              [_ b] (flights-update-call r db ix res-id res)]
          [["update_reservation_flights" a] ["update_reservation_flights" b]
           ["update_reservation_baggages" {"reservation_id" res-id "total_baggages" 2 "nonfree_baggages" 2
                                           "payment_id" (or gift card "x")}]
           ["cancel_reservation" {"reservation_id" res-id}]
           ["cancel_reservation" {"reservation_id" res-id}]])
      ;; one-stop searches from early days (unpadded next-day date)
      8 (vec (for [_ (range 3)]
               (let [f (pick (:flights ix))]
                 ["search_onestop_flight" {"origin" (get f "origin")
                                           "destination" (pick (conj airports nil))
                                           "date" (format "2024-05-%02d" (inc (int 30)))}]))))))

(defn fuzz-corpus
  "`n` seeded sequences of 1-9 calls around one user and (mostly) one of
   their reservations, occasionally issued with the `user` requestor; one in
   five sequences is a hand-shaped scenario (see `scenario`)."
  [db seed n]
  (let [{:keys [pick chance int] :as r} (rng-fns seed)
        ix (index db)]
    (vec (for [i (range n)]
           (let [user (pick (:users ix))
                 res-id (or (pick (get user "reservations")) (pick (keys (get db "reservations"))))]
             {"id" (str "air-fuzz-" seed "-" i)
              "calls" (if (chance 0.2)
                        (mapv (fn [[name args]] {"name" name "arguments" args "requestor" "assistant"})
                              (scenario r db ix user res-id))
                        (vec (for [_ (range (inc (int 9)))]
                               (let [[name args] (call r db ix user
                                                       (if (chance 0.85) res-id
                                                           (pick (get user "reservations"))))]
                                 {"name" name "arguments" args
                                  "requestor" (if (chance 0.02) "user" "assistant")}))))})))))
