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

(defn- tool-result-fact [tool-name value]
  ;; Tool results belong to the private ChatContext/tool-call trace. Room
  ;; activities may reveal only a typed status and an allowlisted diagnostic —
  ;; never a prefix, hash, or derivative of arbitrary user/tool content.
  (when (and (= "clojure_eval" (str tool-name)) (string? value))
    (cond
      (str/starts-with? value "Evaluation cancelled:")
      {:activity/status :cancelled
       :activity/outcome "clojure-eval/cancelled"}

      (str/starts-with? value "Evaluation error:")
      {:activity/status :failed
       :activity/outcome
       (cond
         (str/includes?
          value "Cannot deref @(spin ...) from inside a drain context")
         "spindel/deref-in-drain"

         (str/includes? value "Unknown hire! options")
         "dvergr.agent/unknown-hire-options"

         (str/includes? value "await called outside of asynchronous scope")
         "spindel/await-outside-async-scope"

         :else "clojure-eval/error")}

      (str/starts-with? value "=> ")
      {:activity/status :completed}

      :else nil)))

(defn tool-activities
  "Project the tool requests in an assistant message into compact semantic
   observations. Raw arguments and results remain in the private tool trace;
   an optional result map adds only typed, allowlisted diagnostics.

   `source-id` should identify the source assistant message when available.
   The ordinal keeps repeated provider tool-use ids distinct."
  ([run-id source-id tool-uses]
   (tool-activities run-id source-id tool-uses {}))
  ([run-id source-id tool-uses outcomes]
   (mapv (fn [ordinal tool-use]
           (let [tool-id (or (:tool-use/id tool-use) (:id tool-use))
                 name    (or (:tool-use/name tool-use) (:name tool-use))
                 result-fact (tool-result-fact name (get outcomes tool-id))]
             (cond-> (merge
                      {:activity/id (stable-id [run-id source-id tool-id ordinal])
                       :activity/kind :tool
                       :activity/verb :invoke
                       :activity/at (Date.)}
                      result-fact)
               run-id (assoc :activity/run-id run-id)
               tool-id (assoc :activity/tool-use-id (str tool-id))
               name (assoc :activity/tool-name (str name)))))
         (range)
         tool-uses)))

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

(defn- portable-activity [fact]
  (cond-> (select-keys fact
                       [:activity/id :activity/kind :activity/verb :activity/at
                        :activity/run-id :activity/tool-use-id :activity/tool-name
                        :activity/status :activity/outcome :activity/critical?])
    (instance? Date (:activity/at fact))
    (assoc :activity/at (.getTime ^Date (:activity/at fact)))))

(defn tool-trace-entry
  "Project one normalized or raw tool-activity message into portable causal
   evidence. Raw inputs remain available for replay while Run/tool-use IDs and
   semantic activities preserve correlation under interleaving."
  [message]
  {:message/id (or (:id message) (:message/id message))
   :run/id (message-run-id message)
   :tool-uses
   (mapv #(select-keys % [:tool-use/id :tool-use/name :tool-use/input])
         (or (:tool-uses message)
             (get-in message [:metadata :tool-uses])
             []))
   :activities (mapv portable-activity (message-activities message))})

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
