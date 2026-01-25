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

(comment
  (age-in-days "2024-10-10T00:00:00.000Z")
  (iso8601-days-ago 100))
