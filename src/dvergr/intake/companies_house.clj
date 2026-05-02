(ns dvergr.intake.companies-house
  "UK Companies House intake — company data, directors, filings.
   Free API key required: https://developer.company-information.service.gov.uk/
   Rate limit: 600 requests per 5 minutes.
   Set COMPANIES_HOUSE_API_KEY env var."
  (:require [dvergr.intake.core :as intake]
            [dvergr.tools :as tools]
            [clojure.string :as str])
  (:import [java.util Base64]))

(def ^:private api-base "https://api.company-information.service.gov.uk")

(defn- api-key []
  (or (System/getenv "COMPANIES_HOUSE_API_KEY")
      (System/getenv "COMPANY_HOUSE_KEY")))

(defn- auth-headers []
  (when-let [k (api-key)]
    ;; Companies House uses HTTP Basic Auth with API key as username, no password
    (let [encoded (.encodeToString (Base64/getEncoder) (.getBytes (str k ":")))]
      {"Authorization" (str "Basic " encoded)})))

(defn search-companies
  "Search for UK companies by name.
   Returns [{:company-number :title :status :address :type :date-of-creation}]."
  [query & {:keys [count] :or {count 10}}]
  (if-not (api-key)
    {:error "COMPANIES_HOUSE_API_KEY not set. Get a free key at https://developer.company-information.service.gov.uk/"}
    (let [data (intake/fetch-json (str api-base "/search/companies")
                                  :headers (auth-headers)
                                  :query-params {:q query
                                                 :items_per_page count})]
      (if (:error data)
        data
        (->> (get data :items [])
             (mapv (fn [item]
                     {:company-number (:company_number item)
                      :title          (:title item)
                      :status         (:company_status item)
                      :type           (:company_type item)
                      :date-of-creation (:date_of_creation item)
                      :address        (let [a (:address item)]
                                        (str/join ", " (remove nil? [(:premises a) (:address_line_1 a)
                                                                      (:locality a) (:postal_code a)])))})))))))

(defn fetch-company
  "Get full details for a UK company by company number."
  [company-number]
  (if-not (api-key)
    {:error "COMPANIES_HOUSE_API_KEY not set"}
    (let [data (intake/fetch-json (str api-base "/company/" company-number)
                                  :headers (auth-headers))]
      (if (:error data)
        data
        {:company-number (:company_number data)
         :name           (:company_name data)
         :status         (:company_status data)
         :type           (:type data)
         :created        (:date_of_creation data)
         :sic-codes      (:sic_codes data)
         :address        (let [a (:registered_office_address data)]
                           (str/join ", " (remove nil? [(:address_line_1 a) (:address_line_2 a)
                                                         (:locality a) (:region a) (:postal_code a)
                                                         (:country a)])))
         :accounts       (:accounts data)
         :confirmation-statement (:confirmation_statement data)
         :jurisdiction   (:jurisdiction data)
         :has-charges    (:has_charges data)
         :has-insolvency-history (:has_insolvency_history data)}))))

(defn fetch-officers
  "Get officers (directors, secretaries) for a UK company."
  [company-number & {:keys [count] :or {count 20}}]
  (if-not (api-key)
    {:error "COMPANIES_HOUSE_API_KEY not set"}
    (let [data (intake/fetch-json (str api-base "/company/" company-number "/officers")
                                  :headers (auth-headers)
                                  :query-params {:items_per_page count})]
      (if (:error data)
        data
        (->> (get data :items [])
             (mapv (fn [officer]
                     {:name        (:name officer)
                      :role        (:officer_role officer)
                      :appointed   (:appointed_on officer)
                      :resigned    (:resigned_on officer)
                      :nationality (:nationality officer)
                      :occupation  (:occupation officer)
                      :country     (get-in officer [:country_of_residence])})))))))

(defn fetch-filing-history
  "Get filing history for a UK company."
  [company-number & {:keys [count category]
                     :or {count 10}}]
  (if-not (api-key)
    {:error "COMPANIES_HOUSE_API_KEY not set"}
    (let [params (cond-> {:items_per_page count}
                   category (assoc :category category))
          data (intake/fetch-json (str api-base "/company/" company-number "/filing-history")
                                  :headers (auth-headers)
                                  :query-params params)]
      (if (:error data)
        data
        (->> (get data :items [])
             (mapv (fn [filing]
                     {:date        (:date filing)
                      :category    (:category filing)
                      :type        (:type filing)
                      :description (:description filing)
                      :url         (when-let [links (:links filing)]
                                     (str "https://find-and-update.company-information.service.gov.uk"
                                          (:document_metadata links)))})))))))

(defn fetch-persons-significant-control
  "Get persons with significant control (PSC) — beneficial owners."
  [company-number]
  (if-not (api-key)
    {:error "COMPANIES_HOUSE_API_KEY not set"}
    (let [data (intake/fetch-json (str api-base "/company/" company-number
                                       "/persons-with-significant-control")
                                  :headers (auth-headers))]
      (if (:error data)
        data
        (->> (get data :items [])
             (mapv (fn [psc]
                     {:name               (:name psc)
                      :kind               (:kind psc)
                      :natures-of-control (:natures_of_control psc)
                      :notified-on        (:notified_on psc)
                      :nationality        (:nationality psc)
                      :country            (:country_of_residence psc)
                      :name-elements      (:name_elements psc)})))))))

;; Tool registrations

(tools/register!
 {:name "uk_company_search"
  :description "Search UK Companies House for companies by name. Returns company numbers, status, type, and creation dates. Requires COMPANIES_HOUSE_API_KEY env var."
  :parameters {:type "object"
               :properties {:query {:type "string"
                                    :description "Company name to search for"}
                            :count {:type "integer"
                                    :description "Number of results (default 10)"}}
               :required ["query"]}
  :execute (fn [{:keys [query count]} _ctx]
             (let [results (search-companies query :count (or count 10))]
               (if (:error results)
                 (intake/error-response (:error results))
                 (intake/success-response
                  (intake/format-items (str "UK Companies: " query)
                                      (map (fn [r] {:title (str (:title r) " [" (:company-number r) "]")
                                                    :url (str "https://find-and-update.company-information.service.gov.uk/company/" (:company-number r))
                                                    :summary (str (:status r) " | " (:type r) " | Created: " (:date-of-creation r))})
                                           results))
                  :companies-house (clojure.core/count results) results))))})

(tools/register!
 {:name "uk_company_details"
  :description "Get full details for a UK company including officers, PSC, and filing history. Requires company number (use uk_company_search to find it)."
  :parameters {:type "object"
               :properties {:company_number {:type "string"
                                             :description "UK company registration number (e.g. '01234567')"}
                            :include_officers {:type "boolean"
                                               :description "Include directors/secretaries (default true)"}
                            :include_filings {:type "boolean"
                                              :description "Include recent filings (default true)"}
                            :include_psc {:type "boolean"
                                          :description "Include persons with significant control (default true)"}}
               :required ["company_number"]}
  :execute (fn [{:keys [company_number include_officers include_filings include_psc]} _ctx]
             (let [details (fetch-company company_number)
                   officers (when (not (false? include_officers))
                              (fetch-officers company_number))
                   filings (when (not (false? include_filings))
                             (fetch-filing-history company_number :count 5))
                   psc (when (not (false? include_psc))
                         (fetch-persons-significant-control company_number))]
               (if (:error details)
                 (intake/error-response (:error details))
                 (let [content (str "## " (:name details) " (" (:company-number details) ")\n\n"
                                    "| Field | Value |\n|-------|-------|\n"
                                    "| Status | " (:status details) " |\n"
                                    "| Type | " (:type details) " |\n"
                                    "| Created | " (:created details) " |\n"
                                    "| SIC Codes | " (str/join ", " (:sic-codes details)) " |\n"
                                    "| Address | " (:address details) " |\n"
                                    "| Jurisdiction | " (:jurisdiction details) " |\n"
                                    "| Has Charges | " (:has-charges details) " |\n"
                                    "| Has Insolvency | " (:has-insolvency-history details) " |\n\n"
                                    (when (and officers (not (:error officers)))
                                      (str "### Officers\n\n"
                                           (str/join "\n" (map (fn [o]
                                                                 (str "- **" (:name o) "** — " (:role o)
                                                                      " (appointed: " (:appointed o)
                                                                      (when (:resigned o) (str ", resigned: " (:resigned o)))
                                                                      ")"))
                                                               officers))
                                           "\n\n"))
                                    (when (and psc (not (:error psc)))
                                      (str "### Persons with Significant Control\n\n"
                                           (str/join "\n" (map (fn [p]
                                                                 (str "- **" (:name p) "** — "
                                                                      (str/join ", " (:natures-of-control p))))
                                                               psc))
                                           "\n\n"))
                                    (when (and filings (not (:error filings)))
                                      (str "### Recent Filings\n\n"
                                           (str/join "\n" (map (fn [f]
                                                                 (str "- " (:date f) " — " (:description f)
                                                                      " [" (:category f) "]"))
                                                               filings)))))]
                   (intake/success-response content :companies-house 1
                                            [{:details details :officers officers
                                              :filings filings :psc psc}])))))})
