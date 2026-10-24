(ns dvergr.benchmarks.company-cash.generate
  "Reproducible case generation. The four regression specs reproduce the recovered v1 data."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [dvergr.benchmarks.company-cash.kernel :refer [payment-day summarize simulate]]
            [dvergr.benchmarks.company-cash.scoring :as scoring :refer [valid-plan? payment-skill]]))

(def today (java.time.LocalDate/parse "2026-11-02"))
(defn date [day] (str (.plusDays today day)))

(defn rng [seed] (java.util.Random. seed))
(defn unif [^java.util.Random r] (.nextDouble r))
(defn between [r a b] (+ a (* (- b a) (unif r))))
(defn gauss [^java.util.Random r] (.nextGaussian r))
(defn pick [^java.util.Random r xs] (nth xs (.nextInt r (count xs))))

;; ---------------------------------------------------------------- the world

(defn customers [r {:keys [names segments]}]
  (into (sorted-map)
        (map-indexed (fn [i n]
                       (let [{:keys [mu tau default]} (nth segments (mod i (count segments)))]
                         [n {:mu (+ mu (* tau (gauss r)))
                             :sigma (between r 0.45 0.85)
                             :default (* default (between r 0.5 1.5))}]))
                     names)))

(defn effects [r]
  {:reminder (between r 0.6 0.85)
   :call {:effect (between r 0.35 0.6) :default-factor (between r 0.35 0.65)}})

(defn shocked
  "The customers after the news in the notes: what the history does not show."
  [cs shocks]
  (reduce (fn [cs {:keys [customer mu-shift default-mult]}]
            (cond-> cs
              mu-shift (update-in [customer :mu] + mu-shift)
              default-mult (update-in [customer :default] #(min 0.6 (* % default-mult)))))
          cs shocks))

(defn uniforms [^java.util.Random r] (vec (repeatedly 9 #(unif r))))

(defn history
  "Paid and written-off invoices from the past 18 months; the action taken
   on each was assigned at random."
  [r cs fx {:keys [history-range]}]
  (let [[lo hi] history-range]
    (->> (for [[c p] cs
               _ (range (+ lo (.nextInt r (inc (- hi lo)))))
               :let [issued (- (+ 75 (.nextInt r 470)))
                     due (+ issued 30)
                     action (pick r [:none :none :reminder :reminder :call])
                     amount (* 250 (+ 4 (.nextInt r 24)))
                     ;; the lateness as it was then: no "after today" condition
                     paid (payment-day p fx {:due-day 0} action (uniforms r))]]
           (cond
             (nil? paid) (when (< (+ due 120) 0)
                           {:customer c :issued issued :due due :amount amount :action action :outcome :written-off})
             (< (+ due paid) 0)
             {:customer c :issued issued :due due :amount amount :action action :outcome :paid
              :paid (long (Math/floor (+ due paid)))}))
         (remove nil?)
         (sort-by :issued)
         vec)))

(defn open-invoices [r cs {:keys [n-invoices big]}]
  (vec (for [i (range n-invoices)
             :let [due (- (.nextInt r 70) 28)]]
         {:id (str "INV-" (+ 2041 i))
          :customer (if (and big (zero? i)) (:customer big) (pick r (vec (keys cs))))
          :amount (if (and big (zero? i)) (:amount big) (* 250 (+ 4 (.nextInt r 24))))
          :due-day due :issued (- due 30)})))

(defn schedule
  "Recurring costs on `day` (1 = tomorrow): rent on the 1st, the accountant
   on the 15th, salaries on the 25th, software every Monday."
  [{:keys [rent accountant payroll software]} day]
  (let [d (.plusDays today day)]
    (cond-> []
      (= 1 (.getDayOfMonth d)) (conj ["Office rent" rent])
      (= 15 (.getDayOfMonth d)) (conj ["Accounting services" accountant])
      (= 25 (.getDayOfMonth d)) (conj ["Salaries" payroll])
      (= java.time.DayOfWeek/MONDAY (.getDayOfWeek d)) (conj ["Software subscriptions" software]))))

(defn outflows [costs weeks]
  (vec (for [w (range weeks)]
         (reduce + (for [day (range (inc (* 7 w)) (inc (* 7 (inc w))))
                         [_ amount] (schedule costs day)]
                     amount)))))

;; ------------------------------------------------------------ the baseline

(defn mean [xs] (/ (reduce + xs) (double (count xs))))
(defn sd [xs] (let [m (mean xs)] (Math/sqrt (/ (reduce + (map #(Math/pow (- % m) 2) xs)) (max 1 (dec (count xs)))))))

(defn pooled
  "Every customer the average customer: one lognormal fitted to all paid
   history, action effects as ratios of geometric means, one default rate."
  [hist cs]
  (let [late (fn [h] (Math/log (max 0.5 (double (- (:paid h) (:due h))))))
        paid (filter #(= :paid (:outcome %)) hist)
        by (fn [a] (map late (filter #(= a (:action %)) paid)))
        base (by :none)
        _ (when (empty? base)
            (throw (ex-info "Pooled baseline needs a paid untreated history row"
                            {:type ::unusable-history :reason :no-paid-controls})))
        ratio (fn [a] (let [xs (by a)] (if (seq xs) (min 1.0 (Math/exp (- (mean xs) (mean base)))) 1.0)))
        p (/ (count (filter #(= :written-off (:outcome %)) hist)) (double (count hist)))]
    {:reminder (ratio :reminder)
     :call {:effect (ratio :call) :default-factor 0.5}
     :customers (into (sorted-map) (for [c (keys cs)] [c {:mu (mean base) :sigma (sd base) :default p}]))}))

(defn odds
  "P(paid by day 14, 28, 56) of each open invoice if left alone, by Monte Carlo."
  [model invoices n seed]
  (let [r (rng seed)]
    (into (sorted-map)
          (for [inv invoices
                :let [days (repeatedly n #(payment-day (get-in model [:customers (:customer inv)]) model inv :none (uniforms r)))]]
            [(:id inv) (mapv (fn [h] (/ (count (filter #(and % (<= % h)) days)) (double n))) [14 28 56])]))))

;; ------------------------------------------------------------- the oracle

(defn score [g plan] (let [s (summarize (simulate g (:truth g) plan))] (assoc s :plan plan :viable? (<= (:p-ruin s) (:max-ruin g)))))

(defn better? [a b]
  (if (= (:viable? a) (:viable? b))
    (if (:viable? a) (> (:expected-value a) (:expected-value b)) (< (:p-ruin a) (:p-ruin b)))
    (:viable? a)))

(defn search [g]
  (let [ids (mapv :id (:invoices g))]
    (loop [best (score g (zipmap ids (repeat :reminder))) pass 0]
      (let [next (reduce (fn [best id]
                           (reduce (fn [best a]
                                     (let [plan (assoc (:plan best) id a)]
                                       (if (valid-plan? g plan)
                                         (let [s (score g plan)] (if (better? s best) s best))
                                         best)))
                                   best [:none :reminder :call :factor]))
                         best ids)]
        (if (or (= (:plan next) (:plan best)) (= pass 6)) next (recur next (inc pass)))))))

;; ------------------------------------------------------------- documents

(defn money [x] (format "%.2f" (double x)))

(defn invoices-csv [hist open]
  (str "invoice,customer,issued,due,amount_eur,status,paid_on,collection_action\n"
       (str/join (for [[i h] (map-indexed vector hist)]
                   (str (format "INV-%04d" (+ 1101 i)) "," (:customer h) "," (date (:issued h)) "," (date (:due h)) ","
                        (money (:amount h)) "," (name (:outcome h)) "," (if (:paid h) (date (:paid h)) "") ","
                        (name (:action h)) "\n")))
       (str/join (for [inv open]
                   (str (:id inv) "," (:customer inv) "," (date (:issued inv)) "," (date (:due-day inv)) ","
                        (money (:amount inv)) ",open,,\n")))))

(defn bank-csv
  "The last eight weeks of the business account, ending at today's balance."
  [hist costs cash]
  (let [rows (sort-by first
                      (concat (for [h hist :when (and (:paid h) (<= -56 (:paid h) -1))]
                                [(:paid h) (str "Payment " (:customer h)) (:amount h)])
                              (for [day (range -56 1) [label amount] (schedule costs day)]
                                [day label (- amount)])))
        start (- cash (reduce + (map #(nth % 2) rows)))]
    (str "date,description,amount_eur,balance_eur\n"
         (first (reduce (fn [[out bal] [day label amount]]
                          (let [b (+ bal amount)]
                            [(str out (date day) "," label "," (money amount) "," (money b) "\n") b]))
                        ["" start] rows)))))

(defn notes-md [{:keys [company notes]}]
  (str "# " company ": notes and correspondence\n\n"
       (str/join "\n" (for [{:keys [date from text]} notes]
                        (str "## " date " — " from "\n\n" text "\n")))))

(defn terms [spec g]
  {:company (:company spec) :today (str today) :cash (:cash g) :horizon-days 56
   :founder-hours (:founder-hours g) :factoring-fee (:factoring-fee g) :max-ruin (:max-ruin g)})

;; ------------------------------------------------------------------ cases

(def regression-specs
  {"dev-studio"
   {:seed 1101 :company "Lindqvist & Ode Software" :cash 21000 :founder-hours 3 :n-invoices 12
    :costs {:rent 2400 :accountant 450 :payroll 15500 :software 190}
    :names ["Aster Freight" "Brightmoor Clinics" "Calder Foods" "Dunmore Legal" "Eskvale Energy" "Fernwick Retail"]
    :segments [{:mu 2.3 :tau 0.3 :default 0.03} {:mu 3.2 :tau 0.4 :default 0.10}]
    :history-range [5 10]
    :shocks [{:customer "Calder Foods" :mu-shift 0.6}]
    :notes [{:date "2026-10-20" :from "Email from Calder Foods, accounts payable"
             :text "From November we move all supplier payments to one monthly payment run at the end of the month following approval. Invoices approved after the 20th go into the next run."}
            {:date "2026-10-28" :from "Email from Dunmore Legal"
             :text "Please note our new accounts contact, Priya Raman, from 1 November. Nothing else changes on our side."}]}
   "dev-runway"
   {:seed 2202 :company "Hartwell Data Studio" :cash 4000 :founder-hours 2 :n-invoices 12
    :costs {:rent 1900 :accountant 380 :payroll 12800 :software 160}
    :names ["Glenrock Logistics" "Holloway Print" "Ivybridge Care" "Juniper Hotels" "Kestrel Marine" "Larchfield Farms"]
    :segments [{:mu 2.5 :tau 0.3 :default 0.04} {:mu 3.4 :tau 0.3 :default 0.12}]
    :history-range [4 9]
    :big {:customer "Holloway Print" :amount 18000}
    :shocks [{:customer "Kestrel Marine" :default-mult 3.0 :mu-shift 0.3}]
    :notes [{:date "2026-10-14" :from "Regional business news (clipping)"
             :text "Kestrel Marine lost its largest charter contract this autumn. Two of its suppliers say payments have slowed and the company has asked for extended terms."}
            {:date "2026-10-30" :from "Call note, Holloway Print"
             :text "Spoke with their finance lead. The October invoice is approved; they pay as usual, nothing unusual expected."}]}
   "holdout-sparse"
   {:seed 3303 :company "Marrow & Finch Analytics" :cash 8000 :founder-hours 3 :n-invoices 13
    :costs {:rent 2100 :accountant 420 :payroll 14000 :software 210}
    :names ["Northgate Dental" "Oakhurst Builders" "Pennant Media" "Quarry Lane Bakery" "Rushmere Clinics" "Saltash Shipping" "Thornbury Schools" "Upwell Garden Co"]
    :segments [{:mu 2.2 :tau 0.5 :default 0.05} {:mu 3.3 :tau 0.5 :default 0.10}]
    :history-range [1 3]
    :shocks [{:customer "Pennant Media" :mu-shift -0.7}
             {:customer "Saltash Shipping" :default-mult 2.5}]
    :notes [{:date "2026-10-09" :from "Email from Pennant Media"
             :text "We have switched to automatic payment of approved invoices by direct debit from this month, so you should see payments on the due date from now on."}
            {:date "2026-10-22" :from "Trade association newsletter (clipping)"
             :text "Freight volumes on the Saltash routes fell sharply in Q3; several operators there are reported to be restructuring."}
            {:date "2026-10-27" :from "Email from Thornbury Schools"
             :text "Our finance office is closed for half-term the week of 26 October."}]}
   "holdout-whale"
   {:seed 4404 :company "Penrose Systems" :cash 11000 :founder-hours 2 :n-invoices 12
    :costs {:rent 2600 :accountant 500 :payroll 16500 :software 240}
    :names ["Vale Hospitality" "Westbury Motors" "Yarrow Pharmacy" "Zennor Telecom" "Abbeyfield Homes" "Birchley Events" "Cranmore Steel"]
    :segments [{:mu 2.4 :tau 0.3 :default 0.03} {:mu 3.6 :tau 0.3 :default 0.15}]
    :history-range [4 10]
    :big {:customer "Zennor Telecom" :amount 24000}
    :shocks [{:customer "Zennor Telecom" :mu-shift 0.5}]
    :notes [{:date "2026-10-18" :from "Email from Zennor Telecom, procurement"
             :text "All invoices above EUR 20,000 now need a second approval from our CFO, which usually adds two to three weeks."}
            {:date "2026-10-29" :from "Email from Birchley Events"
             :text "Thanks for the reminder last month. We have caught up on our backlog."}]}})

(defn generate-case
  "One company, its observable documents and private scoring truth. No file I/O."
  [id {:keys [seed costs shocks] :as spec}]
  (let [r (rng seed)
        cs (customers r spec)
        fx (effects r)
        before (merge fx {:customers cs})
        hist (history r cs fx spec)
        open (open-invoices r cs spec)
        truth (assoc before :customers (shocked cs shocks))
        base (try (pooled hist cs)
                  (catch clojure.lang.ExceptionInfo error
                    (throw (ex-info "Generated business has unusable baseline history"
                                    (merge (ex-data error) {:case id :seed seed}) error))))
        g {:case id :truth truth :pooled base
           :invoices (mapv #(select-keys % [:id :customer :amount :due-day]) open)
           :cash (:cash spec) :outflows (outflows costs 8) :founder-hours (:founder-hours spec)
           :factoring-fee 0.06 :max-ruin 0.05 :seed (+ 1000 seed) :worlds 300}
        best (search g)
        idle (score g {})
        truth-odds (odds truth open 4000 (+ 17 seed))
        pooled-odds (odds base open 4000 (+ 17 seed))
        gold (assoc g
                    :oracle (select-keys best [:plan :expected-value :p-ruin :cash])
                    :idle (select-keys idle [:expected-value :p-ruin :cash])
                    :payment-odds (into (sorted-map) (for [k (keys truth-odds)]
                                                       [k {:truth (truth-odds k) :pooled (pooled-odds k)}])))]
    {:id id :gold gold
     :observations {:notes (notes-md spec)
                    :history (mapv (fn [row]
                                     (cond-> row (:paid row) (assoc :days-late (- (:paid row) (:due row))))) hist)
                    :company (select-keys g [:invoices :cash :outflows :founder-hours :factoring-fee :max-ruin])}
     :files {"invoices.csv" (invoices-csv hist open)
             "bank.csv" (bank-csv hist costs (:cash spec))
             "notes.md" (notes-md spec)
             "terms.edn" (str (pr-str (terms spec g)) "\n")}}))

(defn varied-spec
  "A new business from a template, with explicit seed and unique customer names.
   Family is the template mechanism; these variations do not prove robustness
   to mechanisms outside that family. Splits use disjoint seed ranges."
  [split index root-seed]
  (let [seed (+ root-seed (* (if (= split :development) 1 2) 100000) index)
        r (rng seed)
        template (nth (vec (sort-by key regression-specs)) (mod index 4))
        original (val template)
        prefix (str (name split) "-" index)
        rename (zipmap (:names original) (map #(str prefix " " %) (:names original)))
        multiplier (between r 0.65 1.35)
        replace-names (fn [text] (reduce-kv str/replace text rename))]
    (-> original
        (assoc :seed seed :company (str "Company " prefix)
               :names (mapv rename (:names original))
               :cash (long (* multiplier (:cash original))))
        (update :costs #(into {} (map (fn [[k v]] [k (long (* multiplier v))]) %)))
        (update :shocks #(mapv (fn [shock] (update shock :customer rename)) %))
        (update :notes #(mapv (fn [note] (-> note (update :from replace-names) (update :text replace-names))) %))
        (cond-> (:big original) (update :big #(-> % (update :customer rename)
                                                  (update :amount (fn [amount] (long (* multiplier amount))))))))))

(defn checker-source
  "The effect-free room checker, assembled from the same kernel/scorer used
   natively. The private truth remains an input of the host scorer."
  []
  (let [kernel (slurp (io/resource "dvergr/benchmarks/company_cash/kernel.clj"))
        scoring (slurp (io/resource "dvergr/benchmarks/company_cash/scoring.clj"))]
    (str "(ns company-cash-checker (:require [clojure.edn :as edn]))\n\n"
         (subs kernel (.indexOf kernel "(def modulus"))
         (subs scoring (.indexOf scoring "(defn pinball")))))

(defn write-case!
  "Write one generated case. observations.edn is public, without hidden truth."
  [out {:keys [id gold files observations]}]
  (let [dir (io/file out "cases" id)
        inputs (io/file dir "fixtures/inputs")]
    (.mkdirs inputs)
    (spit (io/file dir "gold.edn") (str (pr-str gold) "\n"))
    (doseq [[path content] (assoc files "observations.edn" (str (pr-str observations) "\n"))]
      (spit (io/file inputs path) content))))

(defn reference-answer
  "Privileged synthetic-truth reference for checker calibration, not an agent baseline."
  [gold]
  {:actions (get-in gold [:oracle :plan])
   :cash (get-in gold [:oracle :cash])
   :p-ruin (get-in gold [:oracle :p-ruin])
   :payments (into {} (for [[id odds] (:payment-odds gold)]
                        [id (zipmap [:by-day-14 :by-day-28 :by-day-56] (:truth odds))]))})

(defn calibration
  "Calibrate against this exact generated business; fail generation if the
   privileged reference or the intended damaged answers do not discriminate."
  [{:keys [id gold]}]
  (let [reference (reference-answer gold)
        files (fn [answer] {"/out/answer.edn" (pr-str answer)})
        check (fn [answer] (scoring/check {:files (files answer) :gold gold}))
        ref (check reference)
        damaged {:invalid-plan {:answer (assoc-in reference [:actions "UNKNOWN-INVOICE"] :call)
                                :loses [:plan-valid?]}
                 :bad-cash {:answer (assoc reference :cash {:p10 1.0e12 :p50 1.0e12 :p90 1.0e12})
                            :loses [:cash-forecast-skilful?]}}]
    (when-not (every? true? (vals (:checks ref)))
      (throw (ex-info "Generated calibration reference does not pass" {:case id :checks (:checks ref)})))
    (doseq [[variant {:keys [answer loses]}] damaged]
      (let [verdict (check answer)]
        (when-not (and (< (:reward verdict) (:reward ref))
                       (every? #(false? (get-in verdict [:checks %])) loses))
          (throw (ex-info "Generated damage does not discriminate" {:case id :variant variant})))))
    {:case id :reference (files reference)
     :damaged (into {} (for [[k {:keys [answer loses]}] damaged]
                         [k {:files (files answer) :loses loses}]))}))

(defn write-bundle!
  "Generate a room workflow into an empty directory. Defaults reproduce the
   four v1 regression businesses. {:development 8 :held-out 16 :seed N}
   creates additional, disjoint businesses with the same synthetic mechanisms."
  [out {:keys [development held-out seed] :or {seed 20261024} :as opts}]
  (let [dir (io/file out)
        _ (when (and (.exists dir) (seq (.list dir)))
            (throw (ex-info "Refusing to overwrite a nonempty bundle directory" {:path (str dir)})))
        expanded? (or (some? development) (some? held-out))
        specs (if expanded?
                (into (sorted-map)
                      (for [[split n] [[:development (or development 8)] [:held-out (or held-out 16)]]
                            :let [_ (when-not (and (integer? n) (<= 1 n 1000))
                                      (throw (ex-info "Case count must be between 1 and 1000" {:split split :count n})))]
                            i (range n)]
                        [(format "%s-%03d" (name split) i) (varied-spec split i seed)]))
                (into (sorted-map) regression-specs))
        splits (if expanded?
                 (into {} (for [split [:development :held-out]]
                            [split (vec (filter #(str/starts-with? % (name split)) (keys specs)))]))
                 {:development ["dev-studio" "dev-runway"] :held-out ["holdout-sparse" "holdout-whale"]})
        brief (slurp (io/resource "dvergr/benchmarks/company_cash/brief.md"))
        cases (mapv (fn [[id spec]] (generate-case id spec)) specs)
        generated-calibration (when expanded? (calibration (first cases)))]
    (.mkdirs dir)
    (.mkdirs (io/file dir "fixtures/inputs"))
    (spit (io/file dir "fixtures/inputs/brief.md") brief)
    (doseq [name ["workflow.edn" "calibration.edn"]]
      ;; Expanded bundles calibrate against the first development business,
      ;; using a newly generated truth reference below rather than stale data.
      (when-not (and expanded? (= name "calibration.edn"))
        (spit (io/file dir name) (slurp (io/resource (str "dvergr/benchmarks/company_cash/" name))))))
    (spit (io/file dir "checker.clj") (checker-source))
    (spit (io/file dir "benchmark.edn") (str (pr-str {:version 1 :splits splits}) "\n"))
    (when generated-calibration
      (spit (io/file dir "calibration.edn") (str (pr-str generated-calibration) "\n")))
    (doseq [case cases]
      (write-case! dir case))
    {:path (str dir) :cases (count specs) :splits splits :seed seed :regression? (not expanded?)}))
