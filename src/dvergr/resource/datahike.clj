(ns dvergr.resource.datahike
  "Trusted Datahike resource installation, separate from the runtime resource
   operations used by model gateways and credential brokers."
  (:require [dvergr.agent.attempt.governance :as attempt-governance]
            [dvergr.resource :as resource]
            [dvergr.resource.ledger :as ledger]
            [kontor.resource :as kontor]))

(defn install-connection!
  "Install the minimal Kontor kernel and a Room's root wallet. The chat entity
   must already exist. Restores accounting, certification and purpose guards.
   Existing unclassified wallets require an explicit migration or a fresh
   financial store; ordinary opening never infers or backfills their purpose."
  [conn room-id chat-id]
  (kontor/install! conn)
  (ledger/ensure-schema! conn)
  ;; Kontor currently owns Datahike's single tx-predicate registration slot.
  ;; Restore all named application guards after every installation so boot
  ;; order cannot replace accounting, certification or purpose invariants.
  (attempt-governance/govern! conn)
  (ledger/govern! conn)
  (kontor/install-unit! conn {:symbol resource/microdollars
                              :name "Micro US dollars"
                              :precision 0})
  (ledger/open-account! conn {:id (resource/room-wallet-id room-id)
                              :purpose :room
                              :owner [:chat/id chat-id]
                              :name (str "Room " room-id)})
  conn)
