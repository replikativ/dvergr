(ns dvergr.intake.linkedin
  "LinkedIn page parser — extracts structured data from raw page captures.

   Parses the text/meta sent by the Dvergr Feed browser extension into
   structured company, profile, and job data.

   Language-agnostic: uses numeric patterns and structural heuristics
   rather than English-only field labels, since LinkedIn renders in the
   user's locale (German, French, etc.).

   Available in SCI sandbox as intake.linkedin:
     (intake.linkedin/parse-company {:url ... :title ... :text ... :meta ... :linkedin ...})
     (intake.linkedin/parse-profile {:url ... :title ... :text ... :meta ...})
     (intake.linkedin/parse-jobs {:url ... :title ... :text ...})"
  (:require [clojure.string :as str]))

;; ============================================================================
;; Helpers
;; ============================================================================

(defn- clean-company-name
  "Strip LinkedIn suffixes from title in any language.
   'Acme Corp: Übersicht | LinkedIn' → 'Acme Corp'
   'Acme Corp | LinkedIn' → 'Acme Corp'"
  [s]
  (when (seq s)
    (-> s
        (str/replace #"\s*\|\s*LinkedIn$" "")
        (str/replace #"\s*[-–—]\s*LinkedIn$" "")
        ;; Remove locale page-type suffixes like ': Übersicht', ': Overview', ': Présentation'
        (str/replace #":\s*\S+$" "")
        str/trim)))

;; ============================================================================
;; Company Page Parser
;; ============================================================================

(defn- parse-summary-line
  "Parse the LinkedIn company summary line format:
   'IT Services and IT Consulting San Ramon, California 278K followers 5K-10K employees'

   Returns map of extracted fields or nil."
  [text]
  (let [lines (str/split-lines text)]
    (some (fn [line]
            ;; Match lines containing both 'followers' and 'employees'
            (when (and (re-find #"(?i)followers?" line)
                       (re-find #"(?i)employees|Beschäftigte|employés" line))
              (let [;; Employee count: "5K-10K employees" or "5,001-10,000 employees"
                    emp (or (when-let [m (re-find #"(\d[\d,.]*(?:K|M)?)\s*[-–]\s*(\d[\d,.]*(?:K|M)?)\s+(?i)(?:employees|Beschäftigte|employés)" line)]
                              (str (second m) "-" (nth m 2)))
                            (when-let [m (re-find #"(\d[\d,.]*(?:K|M)?)\+?\s+(?i)(?:employees|Beschäftigte|employés)" line)]
                              (second m)))
                    ;; Followers: "278K followers" or "278,319 followers"
                    foll (when-let [m (re-find #"([\d,.]+(?:K|M)?)\s+(?i)(?:followers?)" line)]
                           (second m))
                    ;; Location: "City, State" before followers count
                    ;; Use a tighter pattern: 1-3 capitalized words, comma, 1-3 capitalized words
                    loc (when-let [m (re-find #"((?:[\p{Lu}][\p{L}]+\s*){1,3},\s+(?:[\p{Lu}][\p{L}]+\s*){1,3})\s+[\d,.]+(?:K|M)?\s+(?i)(?:followers?)" line)]
                          (str/trim (second m)))
                    ;; Industry: everything before the location
                    ind (when loc
                          (let [idx (str/index-of line loc)]
                            (when (and idx (pos? idx))
                              (str/trim (subs line 0 idx)))))]
                {:employee-count emp
                 :followers foll
                 :headquarters loc
                 :industry ind})))
          lines)))

(defn parse-company-page
  "Parse a LinkedIn company page capture into structured data.

   LinkedIn company pages have a summary line with all key data:
   'Industry Location Followers Employees'
   e.g. 'IT Services and IT Consulting San Ramon, California 278K followers 5K-10K employees'

   Also extracts from About section fields (Website, Founded, etc.)
   and the og:description meta tag.

   Input: map with :url :title :text :meta :linkedin (from extension)
   Returns: {:company-name :industry :employee-count :headquarters
             :description :specialties :website :founded :followers :url}"
  [{:keys [url title text meta linkedin]}]
  (let [company-data (or (:companyData linkedin) {})
        og-title (or (:ogTitle linkedin) (get meta "og:title") "")
        og-desc (or (:ogDescription linkedin) (get meta "og:description") "")
        text (or text "")

        ;; Company name
        company-name (or (clean-company-name og-title)
                         (clean-company-name title)
                         "")

        ;; Parse the summary line for industry, location, employees, followers
        summary (parse-summary-line text)

        employee-count (or (:employeeCount company-data) (:employee-count summary))
        industry (or (:industry company-data) (:industry summary))
        headquarters (or (:headquarters company-data) (:headquarters summary))
        followers (:followers summary)

        ;; About section fields (on /about page or expanded view)
        website (or (:website company-data)
                    (when-let [m (re-find #"(?:Website|Webseite|Site web)\s*[\n:]+\s*(https?://[^\s\n]+)" text)]
                      (str/trim (second m))))

        founded (or (:founded company-data)
                    (when-let [m (re-find #"(?:Founded|Gegründet|Fondée?)\s*[\n:]+\s*(\d{4})" text)]
                      (second m))
                    ;; Also try inline: "Founded in Silicon Valley in 2006"
                    (when-let [m (re-find #"[Ff]ounded\s+(?:in\s+)?(?:[\p{L}\s]+\s+)?in\s+(\d{4})" text)]
                      (second m)))

        specialties (or (:specialties company-data)
                        (when-let [m (re-find #"(?:Specialties|Spezialgebiete|Spécialités)\s*[\n:]+\s*([^\n]+)" text)]
                          (str/trim (second m))))

        ;; Description: og:description or first substantial paragraph
        description (or (when (seq og-desc) (str/trim og-desc))
                        (some (fn [line]
                                (let [l (str/trim line)]
                                  (when (and (> (count l) 100)
                                             (not (re-find #"(?i)follower|notification|skip to" l)))
                                    l)))
                              (str/split-lines text)))]

    (cond-> {:company-name company-name
             :url url}
      (seq industry) (assoc :industry industry)
      employee-count (assoc :employee-count employee-count)
      (seq headquarters) (assoc :headquarters headquarters)
      (seq description) (assoc :description description)
      (seq specialties) (assoc :specialties specialties)
      (seq website) (assoc :website website)
      founded (assoc :founded founded)
      followers (assoc :followers followers))))

;; ============================================================================
;; Profile Page Parser
;; ============================================================================

(defn parse-profile-page
  "Parse a LinkedIn profile page capture into structured data.

   Input: map with :url :title :text :meta :linkedin
   Returns: {:name :headline :company :location :connections :url}"
  [{:keys [url title text meta linkedin]}]
  (let [profile-data (or (:profileData linkedin) {})
        og-title (or (:ogTitle linkedin) (get meta "og:title") "")
        text (or text "")

        ;; Name and headline from og:title "Name - Headline | LinkedIn"
        name (or (:name profile-data)
                 (when (seq og-title)
                   (let [parts (str/split og-title #"\s+-\s+")]
                     (when (seq parts)
                       (str/trim (first parts))))))

        headline (or (:headline profile-data)
                     (when (seq og-title)
                       (let [parts (str/split og-title #"\s+-\s+")]
                         (when (> (count parts) 1)
                           (-> (str/join " - " (rest parts))
                               (str/replace #"\s*\|\s*LinkedIn$" "")
                               str/trim)))))

        ;; Location — multilingual
        location (or (:location profile-data)
                     (when-let [m (re-find #"(?:Location|Standort|Lieu|Ubicación|Località)\s*[\n:]+\s*([^\n]+)" text)]
                       (str/trim (second m))))

        ;; Connections — multilingual
        connections (or (:connections profile-data)
                        (when-let [m (re-find #"(\d+)\+?\s+(?:connections|Kontakte|relations|conexiones|collegamenti)" text)]
                          (second m)))]

    (cond-> {:url url}
      (seq name) (assoc :name name)
      (seq headline) (assoc :headline headline)
      (seq location) (assoc :location location)
      connections (assoc :connections connections))))

;; ============================================================================
;; Jobs Page Parser
;; ============================================================================

(defn parse-jobs-page
  "Parse a LinkedIn jobs page capture into job listings.

   Input: map with :url :title :text :linkedin
   Returns: [{:title :company :location :posted}]"
  [{:keys [url title text linkedin]}]
  (let [jobs-data (or (:jobsData linkedin) {})
        listings (or (:listings jobs-data) [])]
    (if (seq listings)
      ;; Use extension-extracted listings
      (mapv (fn [{:keys [title company location]}]
              (cond-> {}
                (seq title) (assoc :title (str/trim title))
                (seq company) (assoc :company (str/trim company))
                (seq location) (assoc :location (str/trim location))))
            listings)
      ;; Fallback: minimal data from title/url
      [{:title (or title "Job search")
        :url url}])))
