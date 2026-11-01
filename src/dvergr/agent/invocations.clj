(ns dvergr.agent.invocations
  "Exact HOST invocation offers. Only portable references enter Run inputs;
   trusted handlers and their physical owners never enter agent-visible data."
  (:require [dvergr.agent.roster :as roster]))

(defn- refuse! [reason]
  (throw (ex-info "Experiment invocation is not admitted" {:type ::refused :reason reason})))

(defn valid-ref? [ref]
  (and (map? ref)
       (= #{:invocation/id :invocation/version :invocation/basis} (set (keys ref)))
       (qualified-keyword? (:invocation/id ref))
       (pos-int? (:invocation/version ref))
       (some? (:invocation/basis ref))
       (roster/data-value? (:invocation/basis ref))))

(defn refs
  "Validate a host-owned offer map and return references, never handlers."
  [scope]
  (let [offers (:invocations scope {})]
    (when-not (and (map? offers)
                   (every? (fn [[ref handler]] (and (valid-ref? ref) (fn? handler))) offers))
      (refuse! :invalid-offers))
    (set (keys offers))))

(defn invoke!
  "HOST bridge used by the injected closure. Handler receives ambient authority,
   never authority supplied by agent code. It owns fixed-plan validation and must
   use the native child owner before starting work. No global catalog fallback."
  [control-room work-room authority ref team opts]
  (when-not (and (uuid? (:parent-run authority)) (valid-ref? ref) (map? opts))
    (refuse! :invalid-invocation))
  (let [scope (:experiment-capabilities authority)]
    (refs scope)
    (if-let [handler (get-in scope [:invocations ref])]
      (handler {:control-room control-room :work-room work-room :authority authority}
               team opts)
      (refuse! :unknown-offer))))
