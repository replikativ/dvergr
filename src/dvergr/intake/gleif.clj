(ns dvergr.intake.gleif
  "GLEIF LEI intake — Legal Entity Identifiers and corporate ownership chains.
   Free, no API key required. Rate limit: reasonable use.

   LEI (Legal Entity Identifier) is a 20-character ISO 17442 code assigned to
   legal entities participating in financial transactions. GLEIF Level 2 data
   answers 'who owns whom' — direct and ultimate parent relationships."
  (:require [dvergr.intake.core :as intake]
            [dvergr.tools :as tools]
            [clojure.string :as str]))

(def ^:private api-base "https://api.gleif.org/api/v1")

(defn search-entities
  "Search GLEIF for legal entities by name.
   Returns [{:lei :name :country :jurisdiction :status :address :registered-at}]."
  [query & {:keys [count] :or {count 10}}]
  (let [data (intake/fetch-json (str api-base "/lei-records")
                                :query-params {"filter[entity.legalName]" query
                                               "page[size]" count})]
    (if (:error data)
      data
      (->> (get data :data [])
           (mapv (fn [record]
                   (let [attrs (get record :attributes {})
                         entity (get attrs :entity {})
                         legal-addr (get entity :legalAddress {})
                         reg (get attrs :registration {})]
                     {:lei          (get record :id)
                      :name         (get-in entity [:legalName :name])
                      :country      (get legal-addr :country)
                      :city         (get legal-addr :city)
                      :region       (get legal-addr :region)
                      :postal-code  (get legal-addr :postalCode)
                      :jurisdiction (get entity :jurisdiction)
                      :legal-form   (get-in entity [:legalForm :id])
                      :status       (get entity :status)
                      :category     (get entity :category)
                      :registered-at (get reg :initialRegistrationDate)
                      :last-update  (get reg :lastUpdateDate)
                      :next-renewal (get reg :nextRenewalDate)})))))))

(defn fetch-entity
  "Get full LEI record for a specific entity by LEI code."
  [lei]
  (let [data (intake/fetch-json (str api-base "/lei-records/" lei))]
    (if (:error data)
      data
      (let [record (get data :data {})
            attrs (get record :attributes {})
            entity (get attrs :entity {})
            legal-addr (get entity :legalAddress {})
            hq-addr (get entity :headquartersAddress {})
            reg (get attrs :registration {})]
        {:lei          (get record :id)
         :name         (get-in entity [:legalName :name])
         :other-names  (->> (get entity :otherNames [])
                            (mapv :name))
         :country      (get legal-addr :country)
         :city         (get legal-addr :city)
         :legal-address (str/join ", " (remove nil?
                          [(first (get legal-addr :addressLines))
                           (get legal-addr :city)
                           (get legal-addr :region)
                           (get legal-addr :postalCode)
                           (get legal-addr :country)]))
         :hq-address   (str/join ", " (remove nil?
                          [(first (get hq-addr :addressLines))
                           (get hq-addr :city)
                           (get hq-addr :region)
                           (get hq-addr :postalCode)
                           (get hq-addr :country)]))
         :jurisdiction (get entity :jurisdiction)
         :legal-form   (get-in entity [:legalForm :id])
         :status       (get entity :status)
         :category     (get entity :category)
         :creation     (get entity :creationDate)
         :registered-at (get reg :initialRegistrationDate)
         :managing-lou (get reg :managingLou)}))))

(defn fetch-direct-parent
  "Get the direct parent entity of an LEI.
   Returns {:parent-lei :parent-name :relationship-type} or {:error} if no parent."
  [lei]
  (let [data (intake/fetch-json (str api-base "/lei-records/" lei
                                     "/direct-parent-relationship"))]
    (if (:error data)
      ;; 404 means no parent relationship registered
      (if (str/includes? (str (:error data)) "404")
        {:no-parent true :lei lei}
        data)
      (let [record (get data :data {})
            attrs (get record :attributes {})
            rel (get attrs :relationship {})]
        {:lei lei
         :parent-lei (get-in rel [:startNode :id])
         :relationship-type (get rel :type)
         :status (get rel :status)
         :start-date (get rel :startDate)}))))

(defn fetch-ultimate-parent
  "Get the ultimate parent entity of an LEI.
   Returns {:parent-lei :parent-name} or {:no-parent true}."
  [lei]
  (let [data (intake/fetch-json (str api-base "/lei-records/" lei
                                     "/ultimate-parent-relationship"))]
    (if (:error data)
      (if (str/includes? (str (:error data)) "404")
        {:no-parent true :lei lei}
        data)
      (let [record (get data :data {})
            attrs (get record :attributes {})
            rel (get attrs :relationship {})]
        {:lei lei
         :ultimate-parent-lei (get-in rel [:startNode :id])
         :relationship-type (get rel :type)
         :status (get rel :status)}))))

(defn fetch-children
  "Get all direct child entities of an LEI (subsidiaries).
   Returns [{:child-lei :relationship-type}]."
  [lei & {:keys [count] :or {count 50}}]
  (let [data (intake/fetch-json (str api-base "/lei-records/" lei
                                     "/direct-child-relationships")
                                :query-params {"page[size]" count})]
    (if (:error data)
      (if (str/includes? (str (:error data)) "404")
        []
        data)
      (->> (get data :data [])
           (mapv (fn [record]
                   (let [attrs (get record :attributes {})
                         rel (get attrs :relationship {})]
                     {:child-lei (get-in rel [:endNode :id])
                      :relationship-type (get rel :type)
                      :status (get rel :status)})))))))

(defn map-corporate-tree
  "Map the full corporate tree for a company: parent + all children.
   Returns {:entity {...} :parent {...} :ultimate-parent {...} :children [...]}."
  [lei]
  (let [entity (fetch-entity lei)
        parent (fetch-direct-parent lei)
        ultimate (fetch-ultimate-parent lei)
        children (fetch-children lei)]
    {:entity entity
     :parent parent
     :ultimate-parent ultimate
     :children children}))

;; Tool registrations

(tools/register!
 {:name "gleif_search"
  :description "Search GLEIF for legal entities by company name. Returns LEI codes needed for ownership lookups. LEI is a global standard for identifying legal entities in finance."
  :parameters {:type "object"
               :properties {:query {:type "string"
                                    :description "Company name to search for"}
                            :count {:type "integer"
                                    :description "Number of results (default 10)"}}
               :required ["query"]}
  :execute (fn [{:keys [query count]} _ctx]
             (let [results (search-entities query :count (or count 10))]
               (if (:error results)
                 (intake/error-response (:error results))
                 (intake/success-response
                  (intake/format-items (str "GLEIF Entities: " query)
                                      (map (fn [r] {:title (str (:name r) " [" (:lei r) "]")
                                                    :url (str "https://search.gleif.org/#/record/" (:lei r))
                                                    :summary (str (:country r) " | " (:jurisdiction r)
                                                                  " | Status: " (:status r))})
                                           results))
                  :gleif (clojure.core/count results) results))))})

(tools/register!
 {:name "gleif_ownership"
  :description "Get corporate ownership tree for a company via GLEIF LEI. Shows parent company, ultimate parent, and subsidiaries. Requires LEI code (use gleif_search to find it)."
  :parameters {:type "object"
               :properties {:lei {:type "string"
                                  :description "20-character LEI code"}}
               :required ["lei"]}
  :execute (fn [{:keys [lei]} _ctx]
             (let [tree (map-corporate-tree lei)]
               (if (:error (:entity tree))
                 (intake/error-response (str "Failed to fetch entity: " (:error (:entity tree))))
                 (let [e (:entity tree)
                       content (str "## " (:name e) " (" (:lei e) ")\n\n"
                                    "| Field | Value |\n|-------|-------|\n"
                                    "| Country | " (:country e) " |\n"
                                    "| Jurisdiction | " (:jurisdiction e) " |\n"
                                    "| Legal Address | " (:legal-address e) " |\n"
                                    "| HQ Address | " (:hq-address e) " |\n"
                                    "| Status | " (:status e) " |\n"
                                    "| Category | " (:category e) " |\n"
                                    "| Created | " (:creation e) " |\n\n"
                                    (if (:no-parent (:parent tree))
                                      "**No direct parent registered.**\n\n"
                                      (str "### Direct Parent\nLEI: " (:parent-lei (:parent tree)) "\n\n"))
                                    (if (:no-parent (:ultimate-parent tree))
                                      "**No ultimate parent registered.**\n\n"
                                      (str "### Ultimate Parent\nLEI: " (:ultimate-parent-lei (:ultimate-parent tree)) "\n\n"))
                                    (if (empty? (:children tree))
                                      "**No subsidiaries registered.**"
                                      (str "### Subsidiaries (" (clojure.core/count (:children tree)) ")\n\n"
                                           (str/join "\n" (map (fn [c]
                                                                 (str "- LEI: " (:child-lei c)
                                                                      " (" (:relationship-type c) ")"))
                                                               (:children tree))))))]
                   (intake/success-response content :gleif 1 [tree])))))})
