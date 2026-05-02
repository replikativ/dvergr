(ns dvergr.intake.sec-edgar
  "SEC EDGAR intake — free US public company filings, financials, and insider trades.
   No API key required, just a User-Agent header with contact info.
   Rate limit: 10 requests/second.

   Key endpoints:
   - Company facts (XBRL): revenue, assets, EPS etc.
   - Filing search: 10-K, 10-Q, 8-K etc.
   - Company tickers: CIK lookup"
  (:require [dvergr.intake.core :as intake]
            [dvergr.tools :as tools]
            [clojure.string :as str]))

(def ^:private edgar-base "https://data.sec.gov")
(def ^:private efts-base "https://efts.sec.gov/LATEST")

;; SEC requires a User-Agent with company name and contact email
(def ^:private ua-header
  (or (System/getenv "SEC_EDGAR_USER_AGENT")
      "dvergr/1.0 (contact@replikativ.io)"))

(def ^:private headers
  {"User-Agent" ua-header
   "Accept"     "application/json"})

(defn- pad-cik
  "Pad CIK to 10 digits with leading zeros."
  [cik]
  (let [s (str cik)]
    (str (apply str (repeat (- 10 (count s)) "0")) s)))

(defn search-companies
  "Search for companies by name or ticker.
   Returns [{:cik :name :ticker :file-type :date}].
   Uses EDGAR full-text search index which searches filings."
  [query & {:keys [count] :or {count 10}}]
  (let [data (intake/fetch-json (str efts-base "/search-index")
                                :headers headers
                                :query-params {:q query})]
    (if (:error data)
      data
      (let [hits (get-in data [:hits :hits] (get data :hits []))]
        (->> hits
             (take count)
             (mapv (fn [hit]
                     (let [src (get hit :_source {})]
                       {:cik       (first (get src :ciks))
                        :name      (first (get src :display_names))
                        :file-type (get src :file_type)
                        :date      (get src :file_date)
                        :form      (get src :form)
                        :sic       (first (get src :sics))})))
             ;; Deduplicate by CIK
             (reduce (fn [acc item]
                       (if (some #(= (:cik %) (:cik item)) acc)
                         acc
                         (conj acc item)))
                     []))))))

(defn fetch-company-facts
  "Fetch XBRL financial facts for a company by CIK.
   Returns structured financial data (revenue, assets, net income, etc.)
   from the companyfacts API.

   cik can be numeric or string (auto-padded to 10 digits)."
  [cik & {:keys [taxonomy]
          :or {taxonomy "us-gaap"}}]
  (let [padded (pad-cik cik)
        url (str edgar-base "/api/xbrl/companyfacts/CIK" padded ".json")
        data (intake/fetch-json url :headers headers)]
    (if (:error data)
      data
      (let [entity-name (get data :entityName "Unknown")
            facts-map (get-in data [:facts (keyword taxonomy)] {})
            ;; Extract key financial metrics
            extract-latest (fn [concept-key & {:keys [prefer-annual?] :or {prefer-annual? false}}]
                             (when-let [concept (get facts-map concept-key)]
                               (let [unit-map (get concept :units {})
                                     units (or (get unit-map :USD)
                                               (get unit-map :shares)
                                               (first (vals unit-map)))
                                     ;; Sort by filed date (most recently filed first)
                                     sorted (->> units
                                                  (filter :val)
                                                  (sort-by :filed)
                                                  reverse)]
                                 (if prefer-annual?
                                   ;; For revenue/income, prefer annual (FY) filings
                                   (or (first (filter #(= "FY" (:fp %)) sorted))
                                       (first sorted))
                                   (first sorted)))))]
        {:entity-name entity-name
         :cik padded
         :revenue (extract-latest :Revenues :prefer-annual? true)
         :revenue-alt (extract-latest :RevenueFromContractWithCustomerExcludingAssessedTax :prefer-annual? true)
         :net-income (extract-latest :NetIncomeLoss :prefer-annual? true)
         :total-assets (extract-latest :Assets)
         :total-liabilities (extract-latest :Liabilities)
         :stockholders-equity (extract-latest :StockholdersEquity)
         :eps (extract-latest :EarningsPerShareBasic)
         :shares-outstanding (extract-latest :CommonStockSharesOutstanding)
         :employees (extract-latest :EntityCommonStockSharesOutstanding)
         :available-concepts (take 50 (sort (map name (keys facts-map))))}))))

(defn fetch-filings
  "Fetch recent SEC filings for a company by CIK.
   filing-type: '10-K' '10-Q' '8-K' 'DEF 14A' etc. or nil for all."
  [cik & {:keys [filing-type count]
          :or {count 10}}]
  (let [padded (pad-cik cik)
        url (str edgar-base "/submissions/CIK" padded ".json")
        data (intake/fetch-json url :headers headers)]
    (if (:error data)
      data
      (let [recent (get data :filings {})
            files (get recent :recent {})
            forms (get files :form [])
            dates (get files :filingDate [])
            accessions (get files :accessionNumber [])
            descriptions (get files :primaryDocDescription [])
            docs (get files :primaryDocument [])
            entries (map (fn [i]
                           {:form (nth forms i nil)
                            :date (nth dates i nil)
                            :accession (nth accessions i nil)
                            :description (nth descriptions i nil)
                            :document (nth docs i nil)
                            :url (when-let [acc (nth accessions i nil)]
                                   (str "https://www.sec.gov/Archives/edgar/data/"
                                        padded "/" (str/replace acc "-" "") "/"
                                        (nth docs i "")))})
                         (range (clojure.core/count forms)))]
        {:entity-name (get data :name)
         :cik padded
         :sic (get data :sic)
         :sic-description (get data :sicDescription)
         :state (get data :stateOfIncorporation)
         :fiscal-year-end (get data :fiscalYearEnd)
         :filings (->> entries
                       (filter #(or (nil? filing-type)
                                    (= filing-type (:form %))))
                       (take count)
                       vec)}))))

(defn fetch-insider-trades
  "Search for insider trading filings (Form 4) for a company."
  [company-name & {:keys [count] :or {count 10}}]
  (let [data (intake/fetch-json (str efts-base "/search-index")
                                :headers headers
                                :query-params {:q company-name
                                               :forms "4"
                                               :dateRange "custom"
                                               :startdt (intake/days-ago-iso 90)
                                               :enddt "2030-01-01"})]
    (if (:error data)
      data
      (->> (get data :hits [])
           (take count)
           (mapv (fn [hit]
                   (let [src (get hit :_source {})]
                     {:filer      (get src :display_names)
                      :date       (get src :file_date)
                      :form       (get src :file_type)
                      :company    (get src :entity_name)
                      :url (str "https://www.sec.gov/cgi-bin/browse-edgar?action=getcompany&CIK="
                                (get src :entity_id)
                                "&type=4&dateb=&owner=include&count=10")})))))))

;; Tool registrations

(tools/register!
 {:name "sec_search"
  :description "Search SEC EDGAR for US public companies by name or ticker. Returns CIK numbers needed for other SEC tools."
  :parameters {:type "object"
               :properties {:query {:type "string"
                                    :description "Company name or ticker to search for"}
                            :count {:type "integer"
                                    :description "Number of results (default 10)"}}
               :required ["query"]}
  :execute (fn [{:keys [query count]} _ctx]
             (let [results (search-companies query :count (or count 10))]
               (if (:error results)
                 (intake/error-response (:error results))
                 (intake/success-response
                  (intake/format-items (str "SEC Companies: " query)
                                      (map (fn [r] {:title (str (:name r) " [" (:ticker r) "]")
                                                    :url (str "CIK: " (:cik r))
                                                    :summary (str "Filed: " (:date r) " — " (:file-type r))})
                                           results))
                  :sec (clojure.core/count results) results))))})

(tools/register!
 {:name "sec_financials"
  :description "Get financial data (revenue, net income, assets, equity, EPS) for a US public company from SEC EDGAR XBRL filings. Requires CIK number (use sec_search to find it)."
  :parameters {:type "object"
               :properties {:cik {:type "string"
                                  :description "SEC CIK number (e.g. '0001018724' for a public company)"}}
               :required ["cik"]}
  :execute (fn [{:keys [cik]} _ctx]
             (let [facts (fetch-company-facts cik)]
               (if (:error facts)
                 (intake/error-response (:error facts))
                 (let [fmt-val (fn [v] (when v (str "$" (format "%,.0f" (double (:val v)))
                                                    " (" (:end v) ")")))
                       content (str "## SEC Financials: " (:entity-name facts) "\n\n"
                                    "CIK: " (:cik facts) "\n\n"
                                    "| Metric | Value |\n|--------|-------|\n"
                                    "| Revenue | " (or (fmt-val (:revenue facts))
                                                       (fmt-val (:revenue-alt facts)) "N/A") " |\n"
                                    "| Net Income | " (or (fmt-val (:net-income facts)) "N/A") " |\n"
                                    "| Total Assets | " (or (fmt-val (:total-assets facts)) "N/A") " |\n"
                                    "| Total Liabilities | " (or (fmt-val (:total-liabilities facts)) "N/A") " |\n"
                                    "| Stockholders Equity | " (or (fmt-val (:stockholders-equity facts)) "N/A") " |\n"
                                    "| EPS | " (or (some-> (:eps facts) :val str) "N/A") " |\n\n"
                                    "Available concepts: " (str/join ", " (take 20 (:available-concepts facts))))]
                   (intake/success-response content :sec 1 [facts])))))})

(tools/register!
 {:name "sec_filings"
  :description "Get recent SEC filings (10-K, 10-Q, 8-K, etc.) for a company. Returns filing dates, types, and document links."
  :parameters {:type "object"
               :properties {:cik {:type "string"
                                  :description "SEC CIK number"}
                            :filing_type {:type "string"
                                          :description "Filter by form type: '10-K', '10-Q', '8-K', 'DEF 14A', etc."}
                            :count {:type "integer"
                                    :description "Number of filings to return (default 10)"}}
               :required ["cik"]}
  :execute (fn [{:keys [cik filing_type count]} _ctx]
             (let [result (fetch-filings cik
                                         :filing-type filing_type
                                         :count (or count 10))]
               (if (:error result)
                 (intake/error-response (:error result))
                 (intake/success-response
                  (str "## SEC Filings: " (:entity-name result) "\n"
                       "SIC: " (:sic result) " — " (:sic-description result) "\n"
                       "State: " (:state result) " | FY End: " (:fiscal-year-end result) "\n\n"
                       (intake/format-items "Filings"
                                           (map (fn [f] {:title (str (:form f) " — " (:description f))
                                                         :url (:url f)
                                                         :summary (:date f)})
                                                (:filings result))))
                  :sec (clojure.core/count (:filings result)) (:filings result)))))})
