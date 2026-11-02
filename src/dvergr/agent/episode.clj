(ns dvergr.agent.episode
  "Pure read/export projections over certified Attempts and Room facts.

   Episode has no lifecycle, scheduler, or settlement authority. Its current
   Run projection is joined at read time; the immutable receipt remains exactly
   as certified even when review later changes world settlement."
  (:require [dvergr.agent.attempt :as certified]
            [dvergr.attempt.bounds :as bounds]
            [dvergr.room.store :as store]))

(defn attempt
  "Load one exact certified Attempt from `room`, or nil."
  [room attempt-id]
  (when-let [room-store (:store room)]
    (when (satisfies? store/PAttemptStore room-store)
      (store/-load-attempt room-store (store/conversation-id room) attempt-id))))

(defn attempt-bounded
  "Load one exact managed certified Attempt under fixed byte/collection limits.
   Options may only lower ceilings. Caller owns current authorization, selected
   context and Room/artifact lifetime; no legacy or unbounded fallback."
  ([room attempt-id] (attempt-bounded room attempt-id {}))
  ([room attempt-id options]
   (let [budget (bounds/limits options)]
     (when-not (uuid? attempt-id)
       (throw (ex-info "An Attempt UUID is required" {:type ::invalid-attempt-id})))
     (when-let [room-store (:store room)]
       (if (satisfies? store/PBoundedAttemptStore room-store)
         (store/-load-attempt-bounded room-store (store/conversation-id room) attempt-id budget)
         (throw (ex-info "Room store has no bounded Attempt reader"
                         {:type ::unsupported-bounded-attempts})))))))

(defn attempts
  "List exact certified Attempts using the store's bounded typed filters."
  ([room] (attempts room {}))
  ([room opts]
   (if-let [room-store (:store room)]
     (if (satisfies? store/PAttemptStore room-store)
       (store/-list-attempts room-store (store/conversation-id room) opts)
       [])
     [])))

(def ^:private run-time-keys
  [:run/created-at :run/started-at :run/updated-at :run/ended-at])

(defn- portable-run [run]
  (reduce (fn [projection key]
            (if-let [instant (get projection key)]
              (assoc projection key (.getTime ^java.util.Date instant))
              projection))
          run run-time-keys))

(defn- required-run [room-store room-id attempt-id run-id kind]
  (if-let [run (store/-load-run room-store room-id run-id)]
    (portable-run run)
    (throw (ex-info "Certified Episode references a missing Run"
                    {:type ::missing-run
                     :episode/id attempt-id
                     :run/id run-id
                     :run/kind kind}))))

(defn export
  "Assemble one portable Episode from immutable certification plus current facts.

   `:episode/attempt` is historical and byte/content-stable. `:episode/run` and
   `:episode/evidence-runs` are current durable projections, so settlement may
   advance without mutating the certified receipt."
  [room attempt-id]
  (when-let [certified (attempt room attempt-id)]
    (certified/validate-attempt certified)
    (let [room-store (:store room)
          room-id (store/conversation-id room)
          run-ids (sort-by str (:attempt/evidence-run-ids certified))]
      {:episode/id attempt-id
       :episode/attempt certified
       :episode/environment (:attempt/environment certified)
       :episode/agent (:attempt/agent certified)
       :episode/receipt (:attempt/receipt certified)
       :episode/evidence (:attempt/evidence certified)
       :episode/run (required-run room-store room-id attempt-id
                                  (:attempt/run-id certified) :root)
       :episode/evidence-runs
       (mapv #(required-run room-store room-id attempt-id % :evidence) run-ids)
       :episode/evidence-message-ids
       (or (:attempt/evidence-message-ids certified) #{})
       :episode/resources
       (get-in certified [:attempt/receipt :attempt/resources])})))
