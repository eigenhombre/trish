(ns trish.time
  (:require [java-time.api :as jt]))

(defn age-in-days
  "
  Calculate the age in days of an ISO 8601 timestamp string.
  "
  [iso8601-string]
  (let [timestamp (jt/instant iso8601-string)
        now (jt/instant)]
    (jt/time-between timestamp now :days)))

(defn iso8601-days-ago
  "
  Return an ISO 8601 string representing the current time minus the
  given number of days.
  "
  [days]
  (-> (jt/instant)
      (jt/minus (jt/days days))
      str))

(defn extract-year-month
  "
  Extract year-month string (YYYY-MM) from ISO 8601 timestamp.
  "
  [iso8601-string]
  (when iso8601-string
    (subs iso8601-string 0 7)))

(defn month-name
  "
  Get month name from YYYY-MM string.
  "
  [year-month]
  (let [month-num (Integer/parseInt (subs year-month 5 7))
        months ["January" "February" "March" "April" "May" "June"
                "July" "August" "September" "October" "November"
                "December"]]
    (nth months (dec month-num))))

(defn get-recent-months
  "
  Get list of YYYY-MM strings for current month and N months back.
  "
  [n]
  (let [now (jt/local-date)]
    (for [i (range (inc n))]
      (let [date (jt/minus now (jt/months i))
            year (jt/as date :year)
            month (jt/as date :month-of-year)]
        (format "%d-%02d" year month)))))

(comment
  (age-in-days "2024-10-10T00:00:00.000Z")
  (iso8601-days-ago 100)
  (extract-year-month "2026-03-15T10:30:00Z")
  (month-name "2026-03")
  (get-recent-months 2))
