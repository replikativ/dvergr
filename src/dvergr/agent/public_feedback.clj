(ns dvergr.agent.public-feedback
  "Portable, explicit disclosure policy and a fixed projection of certified
   checks. This module grants no execution, evaluator or settlement authority."
  (:require [clojure.string :as str]
            [dvergr.agent.attempt :as attempt]
            [dvergr.agent.environment :as environment]
            [dvergr.agent.roster :as roster]
            [dvergr.room.store :as store]
            [hasch.core :as hasch]))

(def max-checks 16)
(def page-size 8)

(defn execution-status [status]
  (if (contains? store/durable-run-statuses status) status :unavailable))

(defn run-state
  "Only known lifecycle values are public; malformed/private keywords are not
   an alternate disclosure channel. These are current facts, not predictions."
  [row]
  {:run/status (execution-status (:run/status row))
   :run/settlement-status (let [status (:run/settlement-status row)]
                            (if (contains? #{:open :review :deferred :merged :discarded :adopted :recovery-required} status)
                              status :unavailable))})

(defn valid?
  "A HOST declaration, included in the exact invocation identity. Descriptions
   are deliberately public text, never extracted from verifier evidence."
  [value]
  (let [{:keys [version verifier checks]} value]
    (and (map? value) (= #{:version :verifier :checks} (set (keys value)))
         (= 1 version)
         (map? verifier)
         (every? #{:verifier/id :verifier/version :verifier/basis} (keys verifier))
         (keyword? (:verifier/id verifier)) (pos-int? (:verifier/version verifier))
         (roster/data-value? (:verifier/basis verifier))
         (map? checks) (<= 1 (count checks) max-checks)
         (every? (fn [[key description]]
                   (and (keyword? key) (<= 1 (count (str key)) 100)
                        (string? description) (<= 1 (count description) 512)
                        (not (str/blank? description)))) checks)
         (<= (alength (.getBytes (pr-str value) java.nio.charset.StandardCharsets/UTF_8)) 4096))))

(defn public-declaration
  "The result envelope needs the public rubric and verifier identity, not the
   verifier's raw basis. Invocation references themselves remain public data."
  [disclosure]
  {:version (:version disclosure) :checks (:checks disclosure)
   :verifier-content-id (hasch/uuid [:dvergr/public-feedback-verifier-v1 (:verifier disclosure)])})

(defn project
  "Read-only projection after the caller has authorized the exact child Job.
   Every identity must match its certified Scorecard cell. Missing checks are
   named unavailable; only declared booleans are disclosed, never evidence."
  [disclosure experiment-id entry certified]
  (when-not (valid? disclosure)
    (throw (ex-info "Invalid public feedback declaration" {:reason :invalid-public-feedback})))
  (attempt/validate-attempt certified)
  (let [receipt (:attempt/receipt certified)
        definition (:attempt/environment certified)
        metrics (:attempt/metrics receipt)]
    (when-not (and (= (:attempt/id entry) (:attempt/id certified) (:attempt/run-id receipt))
                   (= (:attempt/content-id entry) (:attempt/content-id certified) (:attempt/content-id receipt))
                   (= (:candidate/agent-content-id entry) (:attempt/agent-def-hash certified))
                   (= (:environment entry) (environment/environment-ref definition) (:attempt/environment receipt))
                   (= experiment-id (:experiment-content-id metrics))
                   (= (:repetition entry) (:experiment-repetition metrics))
                   (= (:verifier disclosure) (:environment/verifier definition)))
      (throw (ex-info "Public feedback does not match the certified cell"
                      {:reason :feedback-binding-mismatch})))
    (let [keys (sort-by str (keys (:checks disclosure)))
          checks (:attempt/checks receipt)
          available (into {} (keep (fn [key]
                                     (when (boolean? (get checks key)) [key (get checks key)]))) keys)
          unavailable (filterv #(not (contains? available %)) keys)]
      {:attempt/status (execution-status (:attempt/status receipt))
       :feedback (cond-> {:checks available}
                   (seq unavailable) (assoc :unavailable-checks unavailable))})))
