(ns dvergr.analysis.queries
  "Query helpers for extracting analysis data from Datahike.

   Makes it easy to query chat contexts, budgets, tool usage, and agent activity
   without writing raw Datalog queries."
  (:require [datahike.api :as d]))

;; ============================================================================
;; Message Queries
;; ============================================================================

(defn get-chat-messages
  "Get all messages for a chat, optionally filtered by role.

   Args:
     db - Datahike db value
     chat-id - Chat UUID
     role - Optional filter by :user/:assistant/:system

   Returns:
     Vector of message entities with [:message/role :message/content :message/created-at]"
  ([db chat-id]
   (d/q '[:find [(pull ?m [:message/id
                           :message/role
                           :message/content
                           :message/created-at
                           :message/tokens
                           :message/important?]) ...]
          :in $ ?chat-id
          :where
          [?c :chat/id ?chat-id]
          [?m :message/chat ?c]]
        db chat-id))
  ([db chat-id role]
   (d/q '[:find [(pull ?m [:message/id
                           :message/role
                           :message/content
                           :message/created-at
                           :message/tokens
                           :message/important?]) ...]
          :in $ ?chat-id ?role
          :where
          [?c :chat/id ?chat-id]
          [?m :message/chat ?c]
          [?m :message/role ?role]]
        db chat-id role)))

(defn get-message-count
  "Count messages in a chat, optionally by role.

   Args:
     db - Datahike db value
     chat-id - Chat UUID
     role - Optional filter by :user/:assistant/:system

   Returns:
     Integer count"
  ([db chat-id]
   (or (d/q '[:find (count ?m) .
              :in $ ?chat-id
              :where
              [?c :chat/id ?chat-id]
              [?m :message/chat ?c]]
            db chat-id)
       0))
  ([db chat-id role]
   (or (d/q '[:find (count ?m) .
              :in $ ?chat-id ?role
              :where
              [?c :chat/id ?chat-id]
              [?m :message/chat ?c]
              [?m :message/role ?role]]
            db chat-id role)
       0)))

(defn get-recent-messages
  "Get the N most recent messages for a chat.

   Args:
     db - Datahike db value
     chat-id - Chat UUID
     n - Number of messages to retrieve (default 10)

   Returns:
     Vector of message entities, most recent first"
  ([db chat-id] (get-recent-messages db chat-id 10))
  ([db chat-id n]
   (->> (get-chat-messages db chat-id)
        (sort-by :message/created-at #(compare %2 %1))
        (take n)
        vec)))

;; ============================================================================
;; Tool Usage Queries
;; ============================================================================

(defn get-tool-calls
  "Get all tool calls for a chat.

   Args:
     db - Datahike db value
     chat-id - Chat UUID

   Returns:
     Vector of tool call entities"
  [db chat-id]
  (d/q '[:find [(pull ?tc [:tool-call/id
                           :tool-call/name
                           :tool-call/status
                           :tool-call/started-at
                           :tool-call/completed-at
                           :tool-call/error]) ...]
         :in $ ?chat-id
         :where
         [?c :chat/id ?chat-id]
         [?m :message/chat ?c]
         [?tc :tool-call/message ?m]]
       db chat-id))

(defn get-tool-usage-stats
  "Get aggregated statistics for tool usage in a chat.

   Args:
     db - Datahike db value
     chat-id - Chat UUID

   Returns:
     Map of {:total-calls N
             :by-name {tool-name count}
             :by-status {:success N :error N :pending N}
             :error-rate 0.0-1.0}"
  [db chat-id]
  (let [calls (get-tool-calls db chat-id)
        total (count calls)
        by-name (frequencies (map :tool-call/name calls))
        by-status (frequencies (map :tool-call/status calls))
        errors (get by-status :error 0)]
    {:total-calls total
     :by-name by-name
     :by-status by-status
     :error-rate (if (pos? total)
                   (/ (double errors) total)
                   0.0)}))

(defn get-failed-tool-calls
  "Get all failed tool calls with their error messages.

   Args:
     db - Datahike db value
     chat-id - Chat UUID

   Returns:
     Vector of {:tool-name str :error str :timestamp instant}"
  [db chat-id]
  (d/q '[:find ?name ?error ?timestamp
         :in $ ?chat-id
         :where
         [?c :chat/id ?chat-id]
         [?m :message/chat ?c]
         [?tc :tool-call/message ?m]
         [?tc :tool-call/status :error]
         [?tc :tool-call/name ?name]
         [?tc :tool-call/error ?error]
         [?tc :tool-call/started-at ?timestamp]]
       db chat-id))

;; ============================================================================
;; Budget & Accounting Queries
;; ============================================================================

(defn get-budget-status
  "Get budget information for a chat.

   Args:
     db - Datahike db value
     chat-id - Chat UUID

   Returns:
     Map with :total, :used, :remaining in microdollars"
  [db chat-id]
  (let [result (d/q '[:find [?total ?used]
                      :in $ ?chat-id
                      :where
                      [?c :chat/id ?chat-id]
                      [?c :chat/budget-total ?total]
                      [?c :chat/budget-used ?used]]
                    db chat-id)]
    (when result
      (let [[total used] result
            remaining (- total used)]
        {:total total :used used :remaining remaining}))))

(defn get-ledger-entries
  "Get all ledger entries for a chat.

   Args:
     db - Datahike db value
     chat-id - Chat UUID

   Returns:
     Vector of ledger entries sorted by timestamp"
  [db chat-id]
  (->> (d/q '[:find [(pull ?l [:ledger/id
                               :ledger/resource-type
                               :ledger/amount
                               :ledger/cost-microdollars
                               :ledger/model
                               :ledger/provider
                               :ledger/timestamp
                               :ledger/metadata]) ...]
              :in $ ?chat-id
              :where
              [?c :chat/id ?chat-id]
              [?l :ledger/chat ?c]]
            db chat-id)
       (sort-by :ledger/timestamp)
       vec))

(defn get-cost-by-resource-type
  "Get total cost broken down by resource type (input-tokens, output-tokens, etc).

   Args:
     db - Datahike db value
     chat-id - Chat UUID

   Returns:
     Map of {resource-type total-cost-microdollars}"
  [db chat-id]
  (let [entries (get-ledger-entries db chat-id)]
    (reduce (fn [acc entry]
              (update acc
                      (:ledger/resource-type entry)
                      (fnil + 0)
                      (:ledger/cost-microdollars entry)))
            {}
            entries)))

(defn get-cost-by-model
  "Get total cost broken down by model.

   Args:
     db - Datahike db value
     chat-id - Chat UUID

   Returns:
     Map of {model-name total-cost-microdollars}"
  [db chat-id]
  (let [entries (get-ledger-entries db chat-id)]
    (reduce (fn [acc entry]
              (update acc
                      (:ledger/model entry)
                      (fnil + 0)
                      (:ledger/cost-microdollars entry)))
            {}
            entries)))

;; ============================================================================
;; Sub-chat Queries
;; ============================================================================

(defn get-sub-chats
  "Get all sub-chats of a parent chat.

   Args:
     db - Datahike db value
     parent-chat-id - Parent chat UUID

   Returns:
     Vector of sub-chat entities"
  [db parent-chat-id]
  (d/q '[:find [(pull ?sc [:chat/id
                           :chat/title
                           :chat/status
                           :chat/created-at
                           :chat/completed-at]) ...]
         :in $ ?parent-id
         :where
         [?p :chat/id ?parent-id]
         [?sc :chat/parent ?p]]
       db parent-chat-id))

(defn get-chat-hierarchy
  "Get full chat hierarchy starting from root.

   Args:
     db - Datahike db value
     chat-id - Any chat UUID in the hierarchy

   Returns:
     Map with :root, :ancestors, :current, :children"
  [db chat-id]
  (let [current (d/pull db '[:chat/id :chat/title {:chat/parent [:chat/id :chat/title]}] [:chat/id chat-id])
        parent-chain (loop [c current
                            chain []]
                       (if-let [parent (:chat/parent c)]
                         (recur (d/pull db '[:chat/id :chat/title {:chat/parent [:chat/id :chat/title]}]
                                        [:chat/id (:chat/id parent)])
                                (conj chain parent))
                         chain))
        root (last parent-chain)
        children (get-sub-chats db chat-id)]
    {:root root
     :ancestors (vec (reverse parent-chain))
     :current current
     :children children}))

;; ============================================================================
;; Knowledge Graph Queries
;; ============================================================================

(defn get-entities
  "Get all knowledge graph entities mentioned in a chat.

   Args:
     db - Datahike db value
     chat-id - Chat UUID

   Returns:
     Vector of {:entity/name str :mention-count N}"
  [db chat-id]
  (d/q '[:find ?name (count ?e)
         :in $ ?chat-id
         :where
         [?c :chat/id ?chat-id]
         [?m :message/chat ?c]
         [?e :entity/message ?m]
         [?e :entity/name ?name]]
       db chat-id))

(defn get-entity-context
  "Get all context mentions for a specific entity.

   Args:
     db - Datahike db value
     chat-id - Chat UUID
     entity-name - Name of the entity

   Returns:
     Vector of context strings"
  [db chat-id entity-name]
  (d/q '[:find [?context ...]
         :in $ ?chat-id ?entity-name
         :where
         [?c :chat/id ?chat-id]
         [?m :message/chat ?c]
         [?e :entity/message ?m]
         [?e :entity/name ?entity-name]
         [?e :entity/context ?context]]
       db chat-id entity-name))

;; ============================================================================
;; Time-Range Queries
;; ============================================================================

(defn get-messages-in-range
  "Get messages within a time range.

   Args:
     db - Datahike db value
     chat-id - Chat UUID
     start - java.util.Date start time
     end - java.util.Date end time

   Returns:
     Vector of message entities"
  [db chat-id start end]
  (d/q '[:find [(pull ?m [:message/id
                          :message/role
                          :message/content
                          :message/created-at]) ...]
         :in $ ?chat-id ?start ?end
         :where
         [?c :chat/id ?chat-id]
         [?m :message/chat ?c]
         [?m :message/created-at ?ts]
         [(>= ?ts ?start)]
         [(<= ?ts ?end)]]
       db chat-id start end))

(defn get-activity-timeline
  "Get timeline of activity (messages, tool calls, budget updates).

   Args:
     db - Datahike db value
     chat-id - Chat UUID

   Returns:
     Sorted vector of {:type :message/:tool-call/:budget-update
                       :timestamp instant
                       :data {...}}"
  [db chat-id]
  (let [messages (map (fn [m] {:type :message
                               :timestamp (:message/created-at m)
                               :data m})
                      (get-chat-messages db chat-id))
        tool-calls (map (fn [tc] {:type :tool-call
                                  :timestamp (:tool-call/started-at tc)
                                  :data tc})
                        (get-tool-calls db chat-id))
        ledger (map (fn [l] {:type :ledger-entry
                             :timestamp (:ledger/timestamp l)
                             :data l})
                    (get-ledger-entries db chat-id))]
    (->> (concat messages tool-calls ledger)
         (sort-by :timestamp)
         vec)))

;; ============================================================================
;; Aggregation Helpers
;; ============================================================================

(defn summarize-chat
  "Get a high-level summary of a chat.

   Args:
     db - Datahike db value
     chat-id - Chat UUID

   Returns:
     Map with counts, costs, and statistics"
  [db chat-id]
  (let [budget (get-budget-status db chat-id)
        tool-stats (get-tool-usage-stats db chat-id)
        message-count (get-message-count db chat-id)
        messages (get-chat-messages db chat-id)
        total-tokens (reduce + 0 (keep :message/tokens messages))
        cost-breakdown (get-cost-by-resource-type db chat-id)
        sub-chats (get-sub-chats db chat-id)]
    {:chat-id chat-id
     :messages {:total message-count
                :by-role {:user (get-message-count db chat-id :user)
                          :assistant (get-message-count db chat-id :assistant)
                          :system (get-message-count db chat-id :system)}
                :total-tokens total-tokens}
     :budget (if budget
               (assoc budget
                      :percent-used (if (pos? (:total budget))
                                      (* 100.0 (/ (:used budget) (:total budget)))
                                      0.0))
               {:total 0 :used 0 :remaining 0 :percent-used 0.0})
     :tools tool-stats
     :cost-breakdown cost-breakdown
     :sub-chats (count sub-chats)}))
