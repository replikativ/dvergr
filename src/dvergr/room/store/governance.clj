(ns dvergr.room.store.governance
  "Named application invariants composed over the current writer predicate.
   A subsystem reinstall may reset the base (Kontor); reinstalling application
   guards restores all named validators without nesting prior composites."
  (:require [datahike.tx-preds :as tx-preds]))

(defonce ^:private installed (atom {}))

(defn compose! [conn key validate-report]
  (let [store-id (get-in @conn [:config :store :id])]
    (locking installed
      (let [entry (get @installed store-id)
            current (tx-preds/tx-pred-for store-id)
            validators (assoc (:validators entry) key validate-report)
            base (if (= current (:composed entry)) (:base entry) current)
            composed (if (and entry (= base (:base entry))
                              (= validators (:validators entry)))
                       (:composed entry)
                       (fn [report]
                         (when base (base report))
                         (doseq [validate (vals validators)] (validate report))
                         report))]
        (swap! installed assoc store-id {:base base :validators validators :composed composed})
        (when-not (= current composed)
          (tx-preds/register-tx-pred! store-id composed))))
    conn))
