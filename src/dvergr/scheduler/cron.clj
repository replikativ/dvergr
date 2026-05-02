(ns dvergr.scheduler.cron
  "Cron-like scheduling with java.time.

  Supports simple schedule specs without a cron expression parser:
    {:every :hour}
    {:every :day :at \"09:00\"}
    {:every :week :on :monday :at \"09:00\"}
    {:every :month :on-day 1 :at \"09:00\"}
    {:at \"2026-03-20T14:00\" :once true}
    {:every-ms 300000}

  All times are timezone-aware (default: system timezone)."
  (:import [java.time ZonedDateTime ZoneId LocalTime Duration
            DayOfWeek Instant]
           [java.time.format DateTimeFormatter]
           [java.time.temporal ChronoUnit TemporalAdjusters]))

;; =============================================================================
;; Time Resolution
;; =============================================================================

(def ^:private day-of-week
  {:monday    DayOfWeek/MONDAY
   :tuesday   DayOfWeek/TUESDAY
   :wednesday DayOfWeek/WEDNESDAY
   :thursday  DayOfWeek/THURSDAY
   :friday    DayOfWeek/FRIDAY
   :saturday  DayOfWeek/SATURDAY
   :sunday    DayOfWeek/SUNDAY})

(defn- parse-time
  "Parse \"HH:MM\" or \"HH:MM:SS\" to LocalTime."
  [s]
  (let [parts (clojure.string/split s #":")]
    (case (count parts)
      2 (LocalTime/of (Integer/parseInt (first parts)) (Integer/parseInt (second parts)))
      3 (LocalTime/of (Integer/parseInt (first parts))
                      (Integer/parseInt (second parts))
                      (Integer/parseInt (nth parts 2)))
      (throw (ex-info "Invalid time format, use HH:MM or HH:MM:SS" {:time s})))))

(defn- zone [tz]
  (ZoneId/of (or tz (str (ZoneId/systemDefault)))))

;; =============================================================================
;; Next Fire Time
;; =============================================================================

(defn next-fire-time
  "Calculate the next fire time for a schedule spec.

  Returns java.time.Instant of next execution.

  Spec keys:
    :every    - :minute, :hour, :day, :week, :month
    :every-ms - interval in milliseconds (simple recurring)
    :at       - time string \"HH:MM\" or ISO datetime for :once
    :on       - day keyword for :week (:monday, :tuesday, etc.)
    :on-day   - day number for :month (1-28)
    :once     - true for one-shot scheduling
    :tz       - timezone string (default: system)"
  ([spec] (next-fire-time spec (Instant/now)))
  ([spec from-instant]
   (let [tz-id (zone (:tz spec))
         now (ZonedDateTime/ofInstant from-instant tz-id)
         time-of-day (when (:at spec)
                       (if (:once spec)
                         nil ;; full datetime, not time-of-day
                         (parse-time (:at spec))))]
     (case (:every spec)
       :minute
       (.toInstant (.plusMinutes now 1))

       :hour
       (let [target (-> now
                        (.truncatedTo ChronoUnit/HOURS)
                        (.plusHours 1))]
         (if time-of-day
           (.toInstant (.with target (LocalTime/of (.getHour (parse-time (:at spec)))
                                                   (.getMinute (parse-time (:at spec))))))
           (.toInstant target)))

       :day
       (let [target-time (or time-of-day (LocalTime/of 9 0))
             target (-> now
                        (.with target-time))]
         (.toInstant (if (.isAfter target now) target (.plusDays target 1))))

       :week
       (let [target-time (or time-of-day (LocalTime/of 9 0))
             target-day (get day-of-week (or (:on spec) :monday))
             target (-> now
                        (.with (TemporalAdjusters/nextOrSame target-day))
                        (.with target-time))]
         (.toInstant (if (.isAfter target now)
                       target
                       (.with target (TemporalAdjusters/next target-day)))))

       :month
       (let [target-time (or time-of-day (LocalTime/of 9 0))
             target-day (or (:on-day spec) 1)
             target (-> now
                        (.withDayOfMonth (min target-day 28))
                        (.with target-time))]
         (.toInstant (if (.isAfter target now) target (.plusMonths target 1))))

       ;; :every-ms — simple interval
       (nil)
       (cond
         (:every-ms spec)
         (.plusMillis (Instant/now) (:every-ms spec))

         (:once spec)
         (let [dt (ZonedDateTime/parse (:at spec))]
           (.toInstant dt))

         :else
         (throw (ex-info "Invalid schedule spec" {:spec spec})))))))

(defn ms-until
  "Milliseconds from now until an Instant."
  [^Instant target]
  (max 0 (.toMillis (Duration/between (Instant/now) target))))

(defn next-fire-ms
  "Milliseconds until next fire for a schedule spec."
  [spec]
  (ms-until (next-fire-time spec)))

;; =============================================================================
;; Schedule Sequence (for chime-like patterns)
;; =============================================================================

(defn fire-seq
  "Lazy sequence of fire times (Instants) for a recurring schedule.
  Returns nil for :once specs after the first fire."
  [spec]
  (if (:once spec)
    [(next-fire-time spec)]
    (lazy-seq
      (let [next-t (next-fire-time spec)]
        (cons next-t
              (lazy-seq
                (fire-seq (assoc spec :_after next-t))))))))
