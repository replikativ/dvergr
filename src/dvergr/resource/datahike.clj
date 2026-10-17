(ns dvergr.resource.datahike
  "Trusted Datahike resource installation, separate from the runtime resource
   operations used by model gateways and credential brokers."
  (:require [dvergr.agent.attempt.governance :as attempt-governance]
            [dvergr.resource :as resource]
            [kontor.resource :as kontor]))

(defn install-connection!
  "Install the minimal Kontor kernel and a Room's root wallet. The chat entity
   must already exist. Composes both writer governors on every installation."
  [conn room-id chat-id]
  (kontor/install! conn)
  ;; Kontor currently owns Datahike's single tx-predicate registration slot.
  ;; Recompose the Attempt governor after every (idempotent) installation so
  ;; neither invariant family can replace the other by boot order.
  (attempt-governance/govern! conn)
  (kontor/install-unit! conn {:symbol resource/microdollars
                              :name "Micro US dollars"
                              :precision 0})
  (kontor/open-account! conn {:id (resource/room-wallet-id room-id)
                              :owner [:chat/id chat-id]
                              :name (str "Room " room-id)})
  conn)
