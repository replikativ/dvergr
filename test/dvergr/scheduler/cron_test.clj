(ns dvergr.scheduler.cron-test
  "Next-fire computation: every recurring rule lands strictly in the future,
   and a one-shot `:at` parses with or without a zone."
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.scheduler.cron :as cron])
  (:import [java.time Instant]))

(defn- inst [s] (Instant/parse s))

(deftest hourly-at-is-minute-past-every-hour
  (testing "the minute of `:at` applies to the current or next hour, never the past"
    (is (= (inst "2026-10-10T12:30:00Z")
           (cron/next-fire-time {:every :hour :at "09:30" :tz "UTC"} (inst "2026-10-10T12:00:00Z"))))
    (is (= (inst "2026-10-10T13:30:00Z")
           (cron/next-fire-time {:every :hour :at "09:30" :tz "UTC"} (inst "2026-10-10T12:30:00Z"))))
    (is (= (inst "2026-10-10T13:30:00Z")
           (cron/next-fire-time {:every :hour :at "00:30" :tz "UTC"} (inst "2026-10-10T12:45:00Z")))))
  (testing "a DST gap that shifts one hour's minute does not shift the next"
    ;; Lord Howe moves its clocks 30 minutes: 02:15 does not exist that night
    (is (= (inst "2026-10-03T16:15:00Z")                 ; 03:15+11:00
           (cron/next-fire-time {:every :hour :at "00:15" :tz "Australia/Lord_Howe"}
                                (inst "2026-10-03T15:50:00Z")))))  ; 02:50+11:00
  (testing "after a fire, the next one is an hour later, not a minute later"
    (let [fired (inst "2026-10-10T12:30:00Z")
          e     (assoc (cron/spec->attrs {:every :hour :at "00:30" :tz "UTC"}
                                         (java.util.Date/from (inst "2026-10-10T12:00:00Z")))
                       :schedule/last-run (java.util.Date/from fired))]
      (is (= (inst "2026-10-10T13:30:00Z")
             (.toInstant (cron/compute-next-fire e (java.util.Date/from fired))))))))

(deftest every-recurring-rule-is-strictly-future
  (let [now (inst "2026-10-10T12:00:00Z")]
    (doseq [spec [{:every :minute} {:every :hour} {:every :hour :at "12:00"}
                  {:every :day :at "12:00"} {:every :week :on :saturday :at "12:00"}
                  {:every :month :on-day 10 :at "12:00"}]]
      (is (.isAfter (cron/next-fire-time (assoc spec :tz "UTC") now) now) (pr-str spec)))))

(deftest once-parses-with-and-without-zone
  (testing "a local datetime (what scheduler/at and the docs pass) uses :tz"
    (is (= (inst "2026-10-11T09:00:00Z")
           (cron/next-fire-time {:once true :at "2026-10-11T09:00" :tz "UTC"})))
    (is (= (inst "2026-10-11T07:00:00Z")
           (cron/next-fire-time {:once true :at "2026-10-11T09:00" :tz "Europe/Berlin"}))))
  (testing "a zoned or offset datetime keeps its own zone"
    (is (= (inst "2026-10-11T09:00:00Z")
           (cron/next-fire-time {:once true :at "2026-10-11T09:00:00Z"})))
    (is (= (inst "2026-10-11T07:00:00Z")
           (cron/next-fire-time {:once true :at "2026-10-11T09:00+02:00" :tz "UTC"}))))
  (testing "spec->attrs accepts the documented one-shot form"
    (is (= :once (:schedule/kind (cron/spec->attrs {:at "2026-08-01T09:00" :once true}
                                                   (java.util.Date.)))))))
