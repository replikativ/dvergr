(ns dvergr.calendar.ical
  "iCal (.ics) feed parsing and synchronization.

   Parses VEVENT components from iCal feeds without external dependencies.
   Handles RFC 5545 basics: DTSTART, DTEND, SUMMARY, DESCRIPTION, LOCATION,
   UID, STATUS, and line folding (continuation lines starting with space/tab)."
  (:require [hato.client :as http]
            [clojure.string :as str]
            [datahike.api :as d]
            [dvergr.calendar.core :as cal]
            [taoensso.telemere :as tel])
  (:import [java.text SimpleDateFormat]
           [java.util Date TimeZone UUID]))

;; ============================================================================
;; iCal Parsing
;; ============================================================================

(defn- unfold-lines
  "Unfold continuation lines per RFC 5545.
   Lines starting with a space or tab are continuations of the previous line."
  [text]
  (str/replace text #"\r?\n[ \t]" ""))

(defn- parse-ical-datetime
  "Parse an iCal date/time string to java.util.Date.
   Handles:
     20260224T100000Z       - UTC
     20260224T100000        - local (treated as UTC)
     20260224               - date only (midnight UTC)
     TZID=Europe/Berlin:20260224T100000 - timezone-qualified"
  [value]
  (try
    (let [;; Strip TZID prefix if present
          [tz-id datetime-str]
          (if (str/starts-with? value "TZID=")
            (let [colon-idx (str/index-of value ":")]
              [(subs value 5 colon-idx) (subs value (inc colon-idx))])
            [nil value])
          clean (str/replace datetime-str #"[^0-9TZ]" "")]
      (cond
        ;; UTC datetime: 20260224T100000Z
        (str/ends-with? clean "Z")
        (let [fmt (SimpleDateFormat. "yyyyMMdd'T'HHmmss'Z'")]
          (.setTimeZone fmt (TimeZone/getTimeZone "UTC"))
          (.parse fmt clean))

        ;; Datetime with timezone
        (and tz-id (str/includes? clean "T"))
        (let [fmt (SimpleDateFormat. "yyyyMMdd'T'HHmmss")]
          (.setTimeZone fmt (TimeZone/getTimeZone tz-id))
          (.parse fmt clean))

        ;; Local datetime (no timezone, treat as UTC)
        (str/includes? clean "T")
        (let [fmt (SimpleDateFormat. "yyyyMMdd'T'HHmmss")]
          (.setTimeZone fmt (TimeZone/getTimeZone "UTC"))
          (.parse fmt clean))

        ;; Date only: 20260224
        :else
        (let [fmt (SimpleDateFormat. "yyyyMMdd")]
          (.setTimeZone fmt (TimeZone/getTimeZone "UTC"))
          (.parse fmt clean))))
    (catch Exception e
      (tel/log! {:level :warn :id :ical/parse-date-error
                 :data {:value value :error (.getMessage e)}}
                "Failed to parse iCal date")
      nil)))

(defn- extract-property
  "Extract a property value from a VEVENT line.
   Handles property parameters like DTSTART;TZID=...:value"
  [line prop-name]
  (when (str/starts-with? (str/upper-case line) (str (str/upper-case prop-name)))
    (let [colon-idx (str/index-of line ":")]
      (when colon-idx
        (str/trim (subs line (inc colon-idx)))))))

(defn- extract-datetime-property
  "Extract a datetime property, handling TZID parameters.
   Returns the raw value including any TZID= prefix for parse-ical-datetime."
  [line prop-name]
  (when (str/starts-with? (str/upper-case line) (str (str/upper-case prop-name)))
    (let [;; Check for ;TZID= parameter
          semicolon-idx (str/index-of line ";")
          colon-idx (str/index-of line ":")
          has-tzid? (and semicolon-idx colon-idx (< semicolon-idx colon-idx)
                        (str/includes? (subs line semicolon-idx colon-idx) "TZID="))]
      (if has-tzid?
        ;; Return TZID=...:datetime so parse-ical-datetime can handle it
        (let [tzid-start (+ (str/index-of line "TZID=") 0)]
          (subs line (+ semicolon-idx 1)))
        ;; No TZID, just return the value after :
        (when colon-idx
          (str/trim (subs line (inc colon-idx))))))))

(defn- parse-vevent
  "Parse a single VEVENT block into an event map."
  [lines]
  (reduce
    (fn [event line]
      (cond
        (extract-datetime-property line "DTSTART")
        (assoc event :start (parse-ical-datetime (extract-datetime-property line "DTSTART")))

        (extract-datetime-property line "DTEND")
        (assoc event :end (parse-ical-datetime (extract-datetime-property line "DTEND")))

        (extract-property line "SUMMARY")
        (assoc event :title (extract-property line "SUMMARY"))

        (extract-property line "DESCRIPTION")
        (assoc event :description (extract-property line "DESCRIPTION"))

        (extract-property line "LOCATION")
        (assoc event :location (extract-property line "LOCATION"))

        (extract-property line "UID")
        (assoc event :uid (extract-property line "UID"))

        (extract-property line "STATUS")
        (let [status (str/upper-case (extract-property line "STATUS"))]
          (assoc event :status (case status
                                 "CONFIRMED"  :confirmed
                                 "TENTATIVE"  :tentative
                                 "CANCELLED"  :cancelled
                                 :confirmed)))

        (extract-property line "RRULE")
        (assoc event :rrule (extract-property line "RRULE"))

        :else event))
    {}
    lines))

(defn parse-ics
  "Parse an .ics text string into a sequence of event maps.
   Returns [{:title :start :end :uid :description :location :status :rrule} ...]"
  [ics-text]
  (let [unfolded (unfold-lines ics-text)
        lines (str/split-lines unfolded)]
    (loop [lines lines
           in-event? false
           current-lines []
           events []]
      (if (empty? lines)
        events
        (let [line (first lines)
              upper (str/upper-case (str/trim line))]
          (cond
            (= upper "BEGIN:VEVENT")
            (recur (rest lines) true [] events)

            (and in-event? (= upper "END:VEVENT"))
            (let [evt (parse-vevent current-lines)]
              (recur (rest lines) false [] (if (:title evt) (conj events evt) events)))

            in-event?
            (recur (rest lines) true (conj current-lines line) events)

            :else
            (recur (rest lines) false current-lines events)))))))

;; ============================================================================
;; Feed Fetching
;; ============================================================================

(defn fetch-ical-feed
  "Fetch and parse an iCal feed from a URL. Returns parsed events."
  [url]
  (let [resp (http/get url {:as :string
                            :connect-timeout 10000
                            :timeout 30000
                            :headers {"User-Agent" "dvergr-calendar/1.0"}})]
    (if (= 200 (:status resp))
      (parse-ics (:body resp))
      (throw (ex-info "Failed to fetch iCal feed"
                      {:status (:status resp) :url url})))))

;; ============================================================================
;; Sync
;; ============================================================================

(defn sync-ical-feed!
  "Synchronize events from an iCal feed into Datahike.

   - New events (by UID) are inserted
   - Existing events (matching :cal/source-uid) are updated
   - Uses :cal/source :ical-import for all imported events

   Returns {:added n :updated n :errors n}"
  [conn url source-name]
  (try
    (let [events (fetch-ical-feed url)
          results (atom {:added 0 :updated 0 :errors 0})]
      (doseq [evt events]
        (when (and (:title evt) (:start evt) (:uid evt))
          (try
            (let [existing (d/q '[:find (pull ?e [*]) .
                                  :in $ ?uid
                                  :where [?e :cal/source-uid ?uid]]
                                @conn (:uid evt))]
              (if existing
                ;; Update existing event
                (do
                  (d/transact conn
                    [(cond-> {:cal/source-uid (:uid evt)
                              :cal/title      (:title evt)}
                       (:start evt)       (assoc :cal/start (:start evt))
                       (:end evt)         (assoc :cal/end (:end evt))
                       (:description evt) (assoc :cal/description (:description evt))
                       (:location evt)    (assoc :cal/location (:location evt))
                       (:status evt)      (assoc :cal/status (:status evt))
                       (:rrule evt)       (assoc :cal/rrule (:rrule evt)))])
                  (swap! results update :updated inc))
                ;; Insert new event
                (do
                  (d/transact conn
                    [(cond-> {:cal/id         (UUID/randomUUID)
                              :cal/title      (:title evt)
                              :cal/start      (:start evt)
                              :cal/end        (or (:end evt) (:start evt))
                              :cal/source     :ical-import
                              :cal/source-uid (:uid evt)
                              :cal/status     (or (:status evt) :confirmed)
                              :cal/created-at (Date.)
                              :cal/created-by (str "ical-import:" (or source-name "unknown"))
                              :cal/type       :external}
                       (:description evt) (assoc :cal/description (:description evt))
                       (:location evt)    (assoc :cal/location (:location evt))
                       (:rrule evt)       (assoc :cal/rrule (:rrule evt)))])
                  (swap! results update :added inc))))
            (catch Exception e
              (tel/log! {:level :warn :id :ical/sync-event-error
                         :data {:uid (:uid evt) :error (.getMessage e)}}
                        "Failed to sync event")
              (swap! results update :errors inc)))))
      (let [r @results]
        (tel/log! {:level :info :id :ical/sync-complete
                   :data (assoc r :source source-name :url url)}
                  "iCal sync complete")
        r))
    (catch Exception e
      (tel/log! {:level :error :id :ical/sync-error
                 :data {:url url :source source-name :error (.getMessage e)}}
                "iCal sync failed")
      {:added 0 :updated 0 :errors 1 :error (.getMessage e)})))
