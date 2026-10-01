(ns dvergr.benchmarks.bankbooking
  "Bank booking (Kontierung), the daily work of a German bookkeeper: for each
   line of a bank statement, the contra account (Gegenkonto, SKR04) and the
   DATEV tax key (BU-Schlüssel) it is booked with.

   The demo of a case pack from a firm's own history: a synthetic Mandant's
   year of bank bookings is written as a DATEV EXTF Buchungsstapel (with
   Kontor's codec, as DATEV exports it), read back by Kontor's importer
   (`kontor.import-datev.buchungsstapel`, the path a real export takes), and
   turned into a certified workflow bundle (`dvergr.catalog.casepack`) that
   benchmarks any agent against the bookings that were made.

     (write-example! \"examples/workflows/bank-booking\"
                     \"examples/data/bank-booking-buchungsstapel.csv\")

   Accounts and keys are SKR04 and DATEV's: 4400/4300 are Automatikkonten
   (the USt is implied, no key), expenses carry 9 (19 % Vorsteuer) or
   8 (7 %), and many bookings none (rent, insurance, postage, wages, taxes)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [dvergr.catalog.casepack :as casepack]
            [kontor.import-datev.buchungsstapel :as bs]
            [kontor.import-datev.extf :as extf])
  (:import (java.math BigDecimal RoundingMode)
           (java.time LocalDate LocalDateTime ZoneOffset)
           (java.util Date Random)))

(def accounts
  "The SKR04 accounts the Mandant books to, with the chart's titles."
  [["0670" "Geringwertige Wirtschaftsgüter"]
   ["1200" "Forderungen aus Lieferungen und Leistungen"]
   ["1800" "Bank"]
   ["3300" "Verbindlichkeiten aus Lieferungen und Leistungen"]
   ["3720" "Verbindlichkeiten aus Lohn und Gehalt"]
   ["3730" "Verbindlichkeiten aus Lohn- und Kirchensteuer"]
   ["3740" "Verbindlichkeiten im Rahmen der sozialen Sicherheit"]
   ["3820" "Umsatzsteuer-Vorauszahlungen"]
   ["4300" "Erlöse 7 % USt (Automatikkonto)"]
   ["4400" "Erlöse 19 % USt (Automatikkonto)"]
   ["6310" "Miete (unbewegliche Wirtschaftsgüter)"]
   ["6325" "Gas, Strom, Wasser"]
   ["6400" "Versicherungen"]
   ["6420" "Beiträge"]
   ["6520" "Kfz-Versicherungen"]
   ["6530" "Laufende Kfz-Betriebskosten"]
   ["6600" "Werbekosten"]
   ["6640" "Bewirtungskosten"]
   ["6650" "Reisekosten Arbeitnehmer"]
   ["6800" "Porto"]
   ["6805" "Telefon"]
   ["6815" "Bürobedarf"]
   ["6820" "Zeitschriften, Bücher"]
   ["6827" "Abschluss- und Prüfungskosten"]
   ["6830" "Buchführungskosten"]
   ["6837" "Aufwendungen für Lizenzen, Konzessionen"]
   ["6855" "Nebenkosten des Geldverkehrs"]])

(def tax-keys
  "The DATEV BU-Schlüssel used here."
  [["9" "19 % Vorsteuer"] ["8" "7 % Vorsteuer"] ["3" "19 % Umsatzsteuer"] ["2" "7 % Umsatzsteuer"]
   ["-" "kein Schlüssel (steuerfrei, nicht steuerbar, Automatikkonto oder Verrechnung)"]])

(def ^:private patterns
  "What the Mandant's bank sees: `[text-fn gegenkonto bu [min max] per-year sign]`,
   sign -1 a payment, +1 a receipt."
  [[#(str "Dauerauftrag Immobilien Weber GmbH Miete Büro " (:month %)) "6310" "-" [1450 1450] 12 -1]
   [#(str "SEPA-Lastschrift Stadtwerke München GmbH Abschlag Strom " (:month %)) "6325" "9" [180 220] 12 -1]
   [#(str "Telekom Deutschland GmbH Rechnung " (:n %) " Kd-Nr. 4471120") "6805" "9" [49.95 79.95] 12 -1]
   [#(str "Hetzner Online GmbH Rechnung R00" (:n %) " Server") "6837" "9" [38 64] 12 -1]
   [#(str "DATEV eG Lizenz Unternehmen online " (:month %)) "6837" "9" [29 29] 12 -1]
   [#(str "Amazon EU S.a.r.l. Bestellung 302-" (:n %) " Druckerpapier, Toner") "6815" "9" [18 140] 8 -1]
   [#(str "Büromarkt Böttcher AG Rechnung " (:n %) " Ordner und Stifte") "6815" "9" [12 80] 4 -1]
   [#(str "Amazon EU S.a.r.l. Bestellung 302-" (:n %) " Monitor 27 Zoll") "0670" "9" [249 790] 2 -1]
   [#(str "Deutsche Post AG Briefmarken Filiale " (:n %)) "6800" "-" [17 95] 6 -1]
   [#(str "ARAL Station " (:n %) " Tankung Kfz M-WE 2025") "6530" "9" [55 95] 14 -1]
   [#(str "Shell Deutschland Station " (:n %) " Kraftstoff") "6530" "9" [48 90] 6 -1]
   [#(str "HUK-COBURG Kfz-Versicherung Vertrag 77" (:n %)) "6520" "-" [96 96] 4 -1]
   [#(str "Allianz Versicherungs-AG Betriebshaftpflicht Beitrag " (:month %)) "6400" "-" [64 64] 4 -1]
   [#(str "IHK für München und Oberbayern Beitrag " (:year %)) "6420" "-" [210 210] 1 -1]
   [#(str "Restaurant Zur Post Bewirtung Kunde Müller GmbH Beleg " (:n %)) "6640" "9" [60 240] 5 -1]
   [#(str "DB Fernverkehr AG Online-Ticket " (:n %) " München-Berlin") "6650" "8" [79 189] 6 -1]
   [#(str "Handelsblatt GmbH Abonnement " (:month %)) "6820" "8" [34.90 34.90] 12 -1]
   [#(str "Steuerberatung Huber Buchführung " (:month %)) "6830" "9" [320 320] 12 -1]
   [#(str "Steuerberatung Huber Jahresabschluss " (:prev %)) "6827" "9" [1850 1850] 1 -1]
   [#(str "Google Ireland Ltd Ads Kampagne " (:n %)) "6600" "-" [120 480] 4 -1]
   [#(str "Kontoführungsentgelt " (:month %)) "6855" "-" [12.5 12.5] 12 -1]
   [#(str "Gehalt " (:month %) " Lena Schmidt") "3720" "-" [3120.40 3120.40] 12 -1]
   [#(str "Gehalt " (:month %) " Jonas Becker") "3720" "-" [2788.15 2788.15] 12 -1]
   [#(str "Finanzamt München Lohnsteuer " (:month %) " StNr 143/551/20871") "3730" "-" [980 1120] 12 -1]
   [#(str "AOK Bayern Beitragsnachweis " (:month %)) "3740" "-" [2140 2290] 12 -1]
   [#(str "Finanzamt München USt-VZ " (:month %) " StNr 143/551/20871") "3820" "-" [900 2400] 12 -1]
   [#(str "Überweisung Schmidt Druck GmbH RE " (:n %)) "3300" "-" [240 1600] 6 -1]
   [#(str "Gutschrift Müller GmbH Rechnung RE-" (:year %) "-0" (:n %)) "1200" "-" [800 5200] 14 1]
   [#(str "Gutschrift Bäckerei Hofmann KG Rechnung RE-" (:year %) "-0" (:n %)) "1200" "-" [300 1900] 10 1]
   [#(str "SumUp Payments Ltd Auszahlung Kartenumsätze " (:n %)) "4400" "-" [180 1250] 12 1]])

(def ^:private month-names
  ["Januar" "Februar" "März" "April" "Mai" "Juni" "Juli" "August" "September" "Oktober" "November" "Dezember"])

(defn bookings
  "A year (`year`) of the Mandant's bank bookings, deterministic for `seed`:
   `[{:date LocalDate :amount BigDecimal (+ received, - paid) :text :gegenkonto :bu :beleg}]`.
   `:noise` (default true) adds what a real history has: a duplicate
   voucher number, a transaction booked twice differently, an unbooked one."
  [{:keys [year seed noise] :or {year 2025 seed 20261001 noise true}}]
  (let [rnd (Random. (long seed))
        rows (for [[text-fn gegenkonto bu [lo hi] per-year sign] patterns
                   i (range per-year)
                   :let [month (if (= 12 per-year) i (long (Math/floor (* 12 (/ (+ i (.nextDouble rnd)) per-year)))))
                         day (inc (.nextInt rnd 27))
                         amt (-> (BigDecimal/valueOf (+ (double lo) (* (.nextDouble rnd) (- (double hi) (double lo)))))
                                 (.setScale 2 RoundingMode/HALF_UP))]]
               {:date (LocalDate/of (int year) (int (inc month)) (int day))
                :amount (if (neg? sign) (.negate amt) amt)
                :text (text-fn {:month (str (month-names month) " " year) :year year :prev (dec year)
                                :n (+ 100 (.nextInt rnd 900))})
                :gegenkonto gegenkonto :bu bu})]
    (cond-> (->> rows (sort-by (juxt :date :text)) (map-indexed (fn [i b] (assoc b :beleg (format "B%04d" (inc i))))) vec)
      ;; what a real history has: a voucher number used twice, the same
      ;; transaction booked to two accounts, one left unbooked
      noise (as-> bs
                  (let [n (count bs)]
                    (-> bs
                        (assoc-in [(- n 1) :beleg] (:beleg (nth bs (- n 2))))
                        (conj (let [b (nth bs 5)] (assoc b :beleg (format "B%04d" (inc n)) :gegenkonto (if (= "6815" (:gegenkonto b)) "6800" "6815"))))
                        (assoc-in [10 :gegenkonto] "")))))))

(defn- ->date [^LocalDate d] (Date/from (.toInstant (.atStartOfDay d) ZoneOffset/UTC)))

(defn extf
  "The bookings as a DATEV EXTF Buchungsstapel (Konto 1800 Bank against the
   Gegenkonto: a payment credits the bank, H; a receipt debits it, S)."
  [{:keys [year] :or {year 2025} :as opts} bookings]
  (let [col (into {} (map-indexed (fn [i c] [c i])) bs/columns)
        row (fn [{:keys [date amount text gegenkonto bu beleg]}]
              (let [cells (vec (repeat (count bs/columns) ""))]
                (-> cells
                    (assoc (col "Umsatz (ohne Soll/Haben-Kz)") (extf/format-amount (.abs ^BigDecimal amount)))
                    (assoc (col "Soll/Haben-Kennzeichen") (if (neg? (.signum ^BigDecimal amount)) "H" "S"))
                    (assoc (col "WKZ Umsatz") "EUR")
                    (assoc (col "Konto") "1800")
                    (assoc (col "Gegenkonto (ohne BU-Schlüssel)") gegenkonto)
                    (assoc (col "BU-Schlüssel") (if (= "-" bu) "" bu))
                    (assoc (col "Belegdatum") (extf/format-belegdatum (->date date)))
                    (assoc (col "Belegfeld 1") beleg)
                    (assoc (col "Buchungstext") (subs text 0 (min 60 (count text)))))))
        from (->date (LocalDate/of (int year) 1 1))
        header (extf/render-header
                {:versionsnummer 510 :datenkategorie (extf/datenkategorie :buchungsstapel)
                 :formatname "Buchungsstapel" :erzeugt-am (LocalDateTime/of (int (inc year)) 1 15 9 0)
                 :herkunft "RE" :exportiert-von "Musterfirma GmbH" :berater 1234567 :mandant 10001
                 :wj-beginn (extf/format-period-bound from) :sachkontenlaenge 4
                 :datum-von (extf/format-period-bound from)
                 :datum-bis (extf/format-period-bound (->date (LocalDate/of (int year) 12 31)))
                 :bezeichnung "Bank 2025" :buchungstyp 1 :wkz "EUR"})]
    (str/join "\r\n" (concat [header (extf/render-row bs/columns)] (map (comp extf/render-row row) bookings) [""]))))

(def spec
  {:name "bank-booking"
   :title "Bank booking (SKR04, DATEV)"
   :doc (str "Book a German GmbH's bank transactions as its bookkeeper did: the contra account (SKR04) and the "
             "DATEV tax key. Cases from a DATEV Buchungsstapel (synthetic Mandant), read by Kontor's importer.")
   :id "beleg"
   :inputs ["date" "amount" "text"]
   :expected {"gegenkonto" {:rule :exact :doc "the SKR04 contra account, four digits"}
              "bu" {:rule :exact :doc "the DATEV BU-Schlüssel (9, 8, 3, 2), or - for none"}}
   :task (str "You book bank transactions for Musterfirma GmbH, a German company that keeps its books with "
              "DATEV in SKR04. The transaction is in /docs/case.edn: date, amount in EUR (positive: received, "
              "negative: paid from the bank account 1800), and the bank's text. The accounts and tax keys the "
              "firm uses are in /docs/kontenrahmen.txt. Write /out/answer.edn, an EDN map: "
              "{\"gegenkonto\" \"<the SKR04 contra account>\" \"bu\" \"<the DATEV BU-Schlüssel, or - for none>\"}. "
              "Note: 4400 and 4300 are Automatikkonten (no key); payroll, tax payments and settlements of "
              "receivables or payables carry no key.")})

(defn kontenrahmen []
  (str "SKR04 accounts of Musterfirma GmbH\n"
       (str/join "\n" (for [[c t] accounts] (str c "  " t)))
       "\n\nDATEV BU-Schlüssel\n"
       (str/join "\n" (for [[k t] tax-keys] (str k "  " t)))
       "\n"))

(defn case-pack
  "The certified bundle from an EXTF Buchungsstapel `text`, read by Kontor's
   importer: `{:files :certification}`."
  [text]
  (let [{:keys [bookings]} (bs/parse-buchungsstapel text)
        rows (vec (for [{:keys [amount bu-schluessel date belegfeld-1 gegenkonto] :as b} bookings]
                    ;; the importer signs the amount on the Konto side: the
                    ;; bank's own view (received +, paid -)
                    {"beleg" belegfeld-1
                     "date" (str (.toLocalDate (.atZone (.toInstant ^Date date) ZoneOffset/UTC)))
                     "amount" (str (.toPlainString ^BigDecimal amount))
                     "text" (:text b)
                     "gegenkonto" gegenkonto
                     "bu" (or bu-schluessel "-")}))]
    (casepack/case-pack (assoc spec :shared {"/docs/kontenrahmen.txt" (kontenrahmen)}) rows nil)))

(defn write-example!
  "Generate the Buchungsstapel and its bundle: the EXTF file at `extf-path`,
   the bundle under `dir`. Returns the certification summary."
  [dir extf-path & [opts]]
  (let [text (extf opts (bookings opts))
        f (io/file extf-path)]
    (io/make-parents f)
    (spit f text :encoding "ISO-8859-1")
    (let [{:keys [certification] :as pack} (case-pack (slurp f :encoding "ISO-8859-1"))]
      (casepack/write-dir! dir pack)
      (dissoc certification :verdicts))))
