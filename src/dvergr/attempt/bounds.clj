(ns dvergr.attempt.bounds
  "Restricted Attempt read ceilings, independent of execution and certification.
   A valid native Attempt may exceed this reader's contract.")

(def ceilings
  {:max-bytes (* 4 1024 1024)
   :max-checks 1024
   :max-evidence-runs 4096
   :max-evidence-messages 4096})

(defn- refuse! [reason & [dimension]]
  (throw (ex-info "Attempt exceeds the bounded reader contract"
                  (cond-> {:type ::refused :reason reason}
                    dimension (assoc :dimension dimension)))))

(defn limits [options]
  (when-not (and (map? options) (every? (set (keys ceilings)) (keys options)))
    (refuse! :invalid-limits))
  (doseq [[key value] options]
    (when-not (and (integer? value) (pos? value) (<= value (get ceilings key)))
      (refuse! :invalid-limits key)))
  (merge ceilings options))

(defn- bounded-items! [items maximum dimension]
  ;; Artifact decoding produces eager EDN collections. Do not realize a lazy
  ;; sequence supplied by an unsupported reader/extension to check its size.
  (when-not (or (nil? items) (map? items) (vector? items) (set? items) (list? items))
    (refuse! :invalid-structure dimension))
  (when (> (count items) maximum) (refuse! :too-many-items dimension)))

(defn check-value!
  "Check collection work before canonical reconstruction/hashing. Actual byte
   and parser-depth bounds are enforced by the managed artifact reader first."
  [value budget]
  (let [budget (limits budget)
        receipt (:attempt/receipt value)
        evidence (:attempt/evidence value)]
    (when-not (and (map? value) (map? receipt) (map? evidence)
                   (map? (:attempt/checks receipt))
                   (set? (:attempt/evidence-run-ids value))
                   (or (nil? (:attempt/evidence-message-ids value))
                       (set? (:attempt/evidence-message-ids value))))
      (refuse! :invalid-structure))
    (doseq [[dimension items]
            [[:max-checks (:attempt/checks receipt)]
             [:max-evidence-runs (:attempt/evidence-run-ids value)]
             [:max-evidence-messages (:attempt/evidence-message-ids value)]
             [:max-evidence-runs (get-in evidence [:trace :runs])]
             [:max-evidence-messages (get-in evidence [:trace :messages])]
             [:max-evidence-runs (get-in receipt [:attempt/trace :runs])]
             [:max-evidence-messages (get-in receipt [:attempt/trace :messages])]]]
      (bounded-items! items (get budget dimension) dimension))
    value))
