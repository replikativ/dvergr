(ns dvergr.chat.persist
  "One durability policy for message persistence.

   Before this, the two persist paths disagreed on failure: the chat-ctx path
   (`context/add-message!`) transacted UNGUARDED — a bad transact threw into the
   turn — while the room-store path (`store.datahike/-store-message!`) caught the
   throwable and SILENTLY log-dropped it (message loss, no surfacing, no retry),
   with which path ran selected by a `:durable?` boolean set elsewhere.

   `persist-tx!` is the single seam both call: attempt → retry once (transient
   lock/contention, e.g. a write-lock held for a moment) → on a second failure
   surface LOUDLY at :error and dead-letter the payload. It never throws and
   never silently drops."
  (:require [datahike.api :as d]
            [taoensso.telemere :as log]))

(def ^:private dead-letter-cap
  "Bound the in-memory dead-letter buffer so a persistent failure can't grow it
   without limit."
  256)

;; Messages that failed to persist even after a retry — kept for post-mortem and
;; manual recovery (inspect/replay via the REPL). Bounded ring; every add is also
;; surfaced at :error.
(defonce dead-letters (atom []))

(defn persist-tx-result!
  "Apply the shared retry/dead-letter policy, returning the Datahike report on
   success and false after terminal failure."
  ([conn tx-data] (persist-tx-result! conn tx-data {}))
  ([conn tx-data {:keys [op room-id msg-id] :as ctx}]
   (letfn [(attempt [] (d/transact conn tx-data))]
     (try
       (attempt)
       (catch Throwable t1
         (log/log! {:level :warn :id :persist/retry
                    :data {:op op :room-id room-id :msg-id msg-id :error (.getMessage t1)}}
                   "message persist failed — retrying once")
         (try
           (attempt)
           (catch Throwable t2
             (swap! dead-letters
                    (fn [dl] (vec (take-last dead-letter-cap
                                             (conj dl {:ctx ctx
                                                       :error (.getMessage t2)
                                                       :tx-data tx-data})))))
             (log/log! {:level :error :id :persist/dead-letter
                        :data {:op op :room-id room-id :msg-id msg-id
                               :error (.getMessage t2)
                               :dead-letter-count (count @dead-letters)}}
                       "message persist failed twice — DEAD-LETTERED (message not durable)")
             false)))))))

(defn persist-tx!
  "Transact under the shared retry/dead-letter policy. Never throws; returns
   true on success and false after terminal failure."
  ([conn tx-data] (persist-tx! conn tx-data {}))
  ([conn tx-data ctx]
   (boolean (persist-tx-result! conn tx-data ctx))))
