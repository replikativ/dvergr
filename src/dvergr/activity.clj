(ns dvergr.activity
  "Small, durable semantic observations attached to canonical room messages.

   Activities are projections of work that already happened. They are not
   executable effects, lifecycle owners, or a second event log: room messages
   supply discourse identity and Runs supply execution identity."
  (:require [clojure.string :as str]
            [hasch.core :as hasch])
  (:import [java.util Date]))

(defn- stable-id
  [parts]
  (hasch/uuid [:dvergr/activity parts]))

(defn tool-activities
  "Project the tool requests in an assistant message into compact semantic
   observations. Raw arguments and results remain in the existing tool trace.

   `source-id` should identify the source assistant message when available.
   The ordinal keeps repeated provider tool-use ids distinct."
  [run-id source-id tool-uses]
  (mapv (fn [ordinal tool-use]
          (let [tool-id (or (:tool-use/id tool-use) (:id tool-use))
                name    (or (:tool-use/name tool-use) (:name tool-use))]
            (cond-> {:activity/id (stable-id [run-id source-id tool-id ordinal])
                     :activity/kind :tool
                     :activity/verb :invoke
                     :activity/at (Date.)}
              run-id (assoc :activity/run-id run-id)
              tool-id (assoc :activity/tool-use-id (str tool-id))
              name (assoc :activity/tool-name (str name)))))
        (range)
        tool-uses))

(defn lifecycle-activity
  "Create a semantic Run lifecycle observation for a visible activity message."
  [run-id kind verb status outcome]
  (cond-> {:activity/id (random-uuid)
           :activity/kind kind
           :activity/verb verb
           :activity/status status
           :activity/critical? true
           :activity/at (Date.)}
    run-id (assoc :activity/run-id run-id)
    outcome (assoc :activity/outcome (str outcome))))

(defn message-activities
  "Return the semantic activity facts carried by a normalized or raw message."
  [message]
  (or (:activities message)
      (get-in message [:metadata :activities])
      []))

(defn message-run-id
  "Return the Run correlated with MESSAGE, accepting normalized and raw shapes."
  [message]
  (or (:run-id message)
      (get-in message [:metadata :run-id])))

(defn short-id
  "Compact a UUID-like identity for human-facing correlation labels."
  [id]
  (when id
    (let [s (str id)]
      (subs s 0 (min 8 (count s))))))

(defn summary
  "Compact, non-authoritative presentation of one semantic activity fact."
  [fact]
  (str/join " · "
            (keep identity
                  [(some-> (or (:activity/verb fact) (:activity/kind fact)) name)
                   (:activity/tool-name fact)
                   (some-> (:activity/status fact) name)
                   (:activity/outcome fact)])))
