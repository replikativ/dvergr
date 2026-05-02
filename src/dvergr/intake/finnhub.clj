(ns dvergr.intake.finnhub
  "Finnhub stock market data intake — quotes, earnings, news, insider trades.
   Free API key required: https://finnhub.io/
   Rate limit: 60 requests per minute (free tier).
   Set FINNHUB_API_KEY env var."
  (:require [dvergr.intake.core :as intake]
            [dvergr.tools :as tools]
            [clojure.string :as str]))

(def ^:private api-base "https://finnhub.io/api/v1")

(defn- api-key []
  (System/getenv "FINNHUB_API_KEY"))

(defn- finnhub-get
  "GET from Finnhub API with API key."
  [path & {:keys [params]}]
  (if-not (api-key)
    {:error "FINNHUB_API_KEY not set. Get a free key at https://finnhub.io/"}
    (intake/fetch-json (str api-base path)
                       :query-params (assoc (or params {}) :token (api-key)))))

(defn fetch-quote
  "Get current stock quote for a ticker symbol.
   Returns {:current :high :low :open :previous-close :change :change-pct :timestamp}."
  [symbol]
  (let [data (finnhub-get "/quote" :params {:symbol (str/upper-case symbol)})]
    (if (:error data)
      data
      {:current        (:c data)
       :high           (:h data)
       :low            (:l data)
       :open           (:o data)
       :previous-close (:pc data)
       :change         (:d data)
       :change-pct     (:dp data)
       :timestamp      (:t data)})))

(defn fetch-company-profile
  "Get company profile for a ticker.
   Returns {:name :country :exchange :industry :market-cap :shares :ipo :logo :url :ticker}."
  [symbol]
  (let [data (finnhub-get "/stock/profile2" :params {:symbol (str/upper-case symbol)})]
    (if (:error data)
      data
      (if (empty? data)
        {:error (str "No company profile found for " symbol)}
        {:name        (:name data)
         :country     (:country data)
         :exchange    (:exchange data)
         :industry    (:finnhubIndustry data)
         :market-cap  (:marketCapitalization data)
         :shares      (:shareOutstanding data)
         :ipo         (:ipo data)
         :logo        (:logo data)
         :url         (:weburl data)
         :ticker      (:ticker data)
         :currency    (:currency data)}))))

(defn fetch-earnings
  "Get quarterly earnings for a ticker (actual vs estimate).
   Returns [{:period :actual :estimate :surprise :surprise-pct}]."
  [symbol & {:keys [count] :or {count 4}}]
  (let [data (finnhub-get "/stock/earnings" :params {:symbol (str/upper-case symbol)
                                                      :limit count})]
    (if (:error data)
      data
      (->> data
           (mapv (fn [e]
                   {:period       (:period e)
                    :actual       (:actual e)
                    :estimate     (:estimate e)
                    :surprise     (:surprise e)
                    :surprise-pct (:surprisePercent e)
                    :symbol       (:symbol e)}))))))

(defn fetch-company-news
  "Get recent news articles about a company.
   Returns [{:headline :summary :source :url :datetime :category}]."
  [symbol & {:keys [days-back count]
             :or {days-back 7 count 10}}]
  (let [from-date (intake/days-ago-iso days-back)
        to-date (intake/days-ago-iso 0)
        data (finnhub-get "/company-news" :params {:symbol (str/upper-case symbol)
                                                    :from from-date
                                                    :to to-date})]
    (if (:error data)
      data
      (->> data
           (take count)
           (mapv (fn [n]
                   {:headline (:headline n)
                    :summary  (:summary n)
                    :source   (:source n)
                    :url      (:url n)
                    :datetime (:datetime n)
                    :category (:category n)}))))))

(defn fetch-insider-transactions
  "Get insider transactions (buys/sells) for a ticker.
   Returns [{:name :share :change :transaction-price :transaction-type :filing-date}]."
  [symbol]
  (let [data (finnhub-get "/stock/insider-transactions" :params {:symbol (str/upper-case symbol)})]
    (if (:error data)
      data
      (->> (get data :data [])
           (take 20)
           (mapv (fn [t]
                   {:name              (:name t)
                    :share             (:share t)
                    :change            (:change t)
                    :transaction-price (:transactionPrice t)
                    :transaction-type  (:transactionType t)
                    :filing-date       (:filingDate t)}))))))

(defn fetch-peers
  "Get list of peer/competitor ticker symbols for a company."
  [symbol]
  (finnhub-get "/stock/peers" :params {:symbol (str/upper-case symbol)}))

(defn fetch-basic-financials
  "Get basic financial metrics (P/E, P/B, margins, growth, etc.) for a ticker."
  [symbol]
  (let [data (finnhub-get "/stock/metric" :params {:symbol (str/upper-case symbol)
                                                    :metric "all"})]
    (if (:error data)
      data
      (let [m (get data :metric {})]
        {:pe-annual         (get m :peBasicExclExtraTTM)
         :pb-annual         (get m :pbAnnual)
         :ps-annual         (get m :psAnnual)
         :ev-ebitda          (get m :currentEv/freeCashFlowAnnual)
         :dividend-yield    (get m :dividendYieldIndicatedAnnual)
         :roe               (get m :roeTTM)
         :roa               (get m :roaTTM)
         :gross-margin      (get m :grossMarginTTM)
         :operating-margin  (get m :operatingMarginTTM)
         :net-margin        (get m :netProfitMarginTTM)
         :revenue-growth-3y (get m :revenueGrowth3Y)
         :revenue-growth-5y (get m :revenueGrowth5Y)
         :eps-growth-3y     (get m :epsGrowth3Y)
         :eps-growth-5y     (get m :epsGrowth5Y)
         :52-week-high      (get m :52WeekHigh)
         :52-week-low       (get m :52WeekLow)
         :beta              (get m :beta)
         :market-cap        (get m :marketCapitalization)}))))

;; Tool registrations

(tools/register!
 {:name "stock_quote"
  :description "Get current stock price quote for a ticker symbol. Returns current price, change, high/low. Requires FINNHUB_API_KEY."
  :parameters {:type "object"
               :properties {:symbol {:type "string"
                                     :description "Stock ticker symbol (e.g. 'GDYN', 'AAPL')"}}
               :required ["symbol"]}
  :execute (fn [{:keys [symbol]} _ctx]
             (let [quote (fetch-quote symbol)
                   profile (fetch-company-profile symbol)]
               (if (:error quote)
                 (intake/error-response (:error quote))
                 (let [content (str "## " (or (:name profile) symbol) " (" (str/upper-case symbol) ")\n\n"
                                    (when-not (:error profile)
                                      (str "Industry: " (:industry profile)
                                           " | Exchange: " (:exchange profile)
                                           " | Market Cap: $" (when (:market-cap profile)
                                                                 (format "%.1fM" (double (:market-cap profile))))
                                           "\n\n"))
                                    "| Metric | Value |\n|--------|-------|\n"
                                    "| Current | $" (:current quote) " |\n"
                                    "| Change | " (:change quote) " (" (:change-pct quote) "%) |\n"
                                    "| Open | $" (:open quote) " |\n"
                                    "| High | $" (:high quote) " |\n"
                                    "| Low | $" (:low quote) " |\n"
                                    "| Prev Close | $" (:previous-close quote) " |")]
                   (intake/success-response content :finnhub 1 [{:quote quote :profile profile}])))))})

(tools/register!
 {:name "stock_financials"
  :description "Get financial metrics (P/E, margins, growth rates, earnings) and insider trades for a stock. Requires FINNHUB_API_KEY."
  :parameters {:type "object"
               :properties {:symbol {:type "string"
                                     :description "Stock ticker symbol"}
                            :include_earnings {:type "boolean"
                                               :description "Include quarterly earnings (default true)"}
                            :include_insiders {:type "boolean"
                                               :description "Include insider transactions (default true)"}}
               :required ["symbol"]}
  :execute (fn [{:keys [symbol include_earnings include_insiders]} _ctx]
             (let [metrics (fetch-basic-financials symbol)
                   earnings (when (not (false? include_earnings))
                              (fetch-earnings symbol))
                   insiders (when (not (false? include_insiders))
                              (fetch-insider-transactions symbol))
                   peers (fetch-peers symbol)]
               (if (:error metrics)
                 (intake/error-response (:error metrics))
                 (let [fmt (fn [v] (if v (format "%.2f" (double v)) "N/A"))
                       content (str "## Financial Metrics: " (str/upper-case symbol) "\n\n"
                                    "| Metric | Value |\n|--------|-------|\n"
                                    "| P/E | " (fmt (:pe-annual metrics)) " |\n"
                                    "| P/B | " (fmt (:pb-annual metrics)) " |\n"
                                    "| P/S | " (fmt (:ps-annual metrics)) " |\n"
                                    "| Gross Margin | " (fmt (:gross-margin metrics)) "% |\n"
                                    "| Operating Margin | " (fmt (:operating-margin metrics)) "% |\n"
                                    "| Net Margin | " (fmt (:net-margin metrics)) "% |\n"
                                    "| ROE | " (fmt (:roe metrics)) "% |\n"
                                    "| Revenue Growth 3Y | " (fmt (:revenue-growth-3y metrics)) "% |\n"
                                    "| 52W High | $" (fmt (:52-week-high metrics)) " |\n"
                                    "| 52W Low | $" (fmt (:52-week-low metrics)) " |\n"
                                    "| Beta | " (fmt (:beta metrics)) " |\n\n"
                                    (when (and peers (not (:error peers)))
                                      (str "**Peers:** " (str/join ", " (take 10 peers)) "\n\n"))
                                    (when (and earnings (not (:error earnings)) (seq earnings))
                                      (str "### Quarterly Earnings\n\n"
                                           "| Period | Actual | Estimate | Surprise |\n"
                                           "|--------|--------|----------|----------|\n"
                                           (str/join "\n" (map (fn [e]
                                                                 (str "| " (:period e)
                                                                      " | " (or (:actual e) "N/A")
                                                                      " | " (or (:estimate e) "N/A")
                                                                      " | " (when (:surprise-pct e)
                                                                               (format "%.1f%%" (double (:surprise-pct e))))
                                                                      " |"))
                                                               earnings))
                                           "\n\n"))
                                    (when (and insiders (not (:error insiders)) (seq insiders))
                                      (str "### Recent Insider Trades\n\n"
                                           (str/join "\n" (map (fn [t]
                                                                 (str "- " (:filing-date t) " — "
                                                                      (:name t) " " (:transaction-type t)
                                                                      " " (:change t) " shares"
                                                                      (when (:transaction-price t)
                                                                        (str " @ $" (:transaction-price t)))))
                                                               (take 10 insiders))))))]
                   (intake/success-response content :finnhub 1
                                            [{:metrics metrics :earnings earnings
                                              :insiders insiders :peers peers}])))))})

(tools/register!
 {:name "stock_news"
  :description "Get recent news articles about a public company from Finnhub. Requires FINNHUB_API_KEY."
  :parameters {:type "object"
               :properties {:symbol {:type "string"
                                     :description "Stock ticker symbol"}
                            :days_back {:type "integer"
                                        :description "How many days back (default 7)"}
                            :count {:type "integer"
                                    :description "Number of articles (default 10)"}}
               :required ["symbol"]}
  :execute (fn [{:keys [symbol days_back count]} _ctx]
             (let [news (fetch-company-news symbol
                                            :days-back (or days_back 7)
                                            :count (or count 10))]
               (if (:error news)
                 (intake/error-response (:error news))
                 (intake/success-response
                  (intake/format-items (str "News: " (str/upper-case symbol))
                                      (map (fn [n] {:title (:headline n)
                                                    :url (:url n)
                                                    :summary (:summary n)})
                                           news))
                  :finnhub (clojure.core/count news) news))))})
