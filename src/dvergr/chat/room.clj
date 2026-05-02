(ns dvergr.chat.room
  "Pub-sub based chat room for multi-agent coordination.

   A ChatRoom provides:
   - Message publishing with topic-based routing
   - Multiple participants (agents) can subscribe to relevant topics
   - Built-in topics: :broadcast, :system, :role-{role}, :agent-{id}
   - Integration with FeedbackContext for manager-agent loops

   Architecture:
   - Each room has a message-pub that routes by topic
   - Participants subscribe to topics they care about
   - Messages are automatically persisted to Datahike
   - Spindel signals track room state reactively

   Topic routing:
   - :broadcast - all participants receive
   - :system - system messages (moderator, budget warnings)
   - :role-manager / :role-worker - role-based routing
   - :agent-{id} - direct message to specific agent"
  (:require [org.replikativ.spindel.engine.core :as rtc]
            [org.replikativ.spindel.engine.context :as ctx]
            [org.replikativ.spindel.signal :as sig]
            [org.replikativ.spindel.atom :as ratom]
            [org.replikativ.spindel.core :as sync]
            [org.replikativ.spindel.core :as pub]
            [org.replikativ.spindel.core :as buf]
            [org.replikativ.spindel.core :refer [spin]]
            [org.replikativ.spindel.core :refer [await]]
            [is.simm.partial-cps.sequence :refer [PAsyncSeq anext]]
            [dvergr.chat.context :as chat-ctx]
            [dvergr.chat.schema :as schema]
            [datahike.api :as dh]))

;; ============================================================================
;; Message Types
;; ============================================================================

(defn message-topic
  "Extract topic(s) from a message for routing.

   Messages can have:
   - :topic - explicit topic keyword
   - :to - recipient agent-id (routes to :agent-{id})
   - :role - target role (routes to :role-{role})
   - defaults to :broadcast if none specified

   Returns the primary topic keyword."
  [msg]
  (cond
    (:topic msg) (:topic msg)
    (:to msg) (keyword (str "agent-" (name (:to msg))))
    (:role msg) (keyword (str "role-" (name (:role msg))))
    :else :broadcast))

(defn create-message
  "Create a room message with standard fields.

   Args:
     content - Message content (string or map)
     opts - Map with:
       :from - Sender agent-id
       :topic - Explicit topic (or use :to/:role)
       :to - Direct to specific agent-id
       :role - Direct to role (e.g., :manager, :worker)
       :type - Message type (:chat, :completion, :feedback, :system)
       :metadata - Additional metadata"
  [content {:keys [from topic to role type metadata]
            :or {type :chat}}]
  (cond-> {:id (random-uuid)
           :content content
           :type type
           :timestamp (java.util.Date.)
           :from from}
    topic (assoc :topic topic)
    to (assoc :to to)
    role (assoc :role role)
    metadata (assoc :metadata metadata)))

;; ============================================================================
;; Message Source (for pub input)
;; ============================================================================

(defn- create-message-source
  "Create a PAsyncSeq that yields messages when posted.

   Returns {:source PAsyncSeq, :post-fn (fn [msg])}.
   Call post-fn to push messages into the stream.

   Uses spindel atoms for fork-safety."
  [spindel-ctx]
  (binding [rtc/*execution-context* spindel-ctx]
    (let [;; Fork-safe spindel atoms for state
          messages-atom (ratom/create-atom [])
          waiter-atom (ratom/create-atom nil)  ; Holds current Deferred for waiting consumer
          closed-atom (ratom/create-atom false)

          source (reify PAsyncSeq
                   (anext [this]
                     (binding [rtc/*execution-context* spindel-ctx]
                       (spin
                         (cond
                           ;; Items available
                           (seq @messages-atom)
                           (let [msg (first @messages-atom)]
                             (swap! messages-atom rest)
                             [msg this])

                           ;; Closed
                           @closed-atom
                           nil

                           ;; Wait for items - create deferred and wait
                           :else
                           (let [d (sync/deferred)]
                             (reset! waiter-atom d)
                             ;; Await the deferred (will be delivered by post-fn)
                             (await d)
                             ;; Retry - items should now be available
                             (await (anext this))))))))

          post-fn (fn [msg]
                    (binding [rtc/*execution-context* spindel-ctx]
                      (swap! messages-atom conj msg)
                      ;; Wake up waiter if any
                      (when-let [d @waiter-atom]
                        (reset! waiter-atom nil)
                        (sync/deliver! d :item))))

          close-fn (fn []
                     (binding [rtc/*execution-context* spindel-ctx]
                       (reset! closed-atom true)
                       ;; Wake up waiter if any
                       (when-let [d @waiter-atom]
                         (sync/deliver! d :closed))))]

      {:source source
       :post-fn post-fn
       :close-fn close-fn})))

;; ============================================================================
;; ChatRoom Record
;; ============================================================================

(defrecord ChatRoom
    [;; Identity
     room-id
     title

     ;; Spindel context
     spindel-ctx

     ;; Pub-sub
     message-pub          ; Pub routing messages by topic
     post-fn              ; Function to post messages

     ;; Reactive state
     participants-signal  ; Signal<{agent-id -> {:role :subscription ...}}>
     status-signal        ; Signal<:active|:paused|:closed>
     message-history      ; Spindel Atom<vector> for history (for persistence)

     ;; Parent chat context (for budget tracking, persistence)
     chat-ctx

     ;; Subscriptions by participant
     subscriptions-atom]) ; Atom<{agent-id -> [sub-seq ...]}>

;; ============================================================================
;; Room Creation
;; ============================================================================

(defn create-room
  "Create a new chat room.

   Args:
     opts - Map with:
       :title - Room title
       :chat-ctx - Parent ChatContext (optional, creates one if not provided)
       :budget-dollars - Budget if creating chat-ctx (default $1.00)"
  [{:keys [title chat-ctx budget-dollars]
    :or {budget-dollars 1.0}}]
  (let [room-id (random-uuid)

        ;; Use provided chat-ctx or create one
        chat-ctx (or chat-ctx
                     (chat-ctx/create-chat-context
                      {:title (or title "Chat Room")
                       :budget-dollars budget-dollars}))

        spindel-ctx (:spindel-ctx chat-ctx)

        ;; Create message source
        {:keys [source post-fn close-fn]}
        (create-message-source spindel-ctx)

        ;; Create pub over message source
        message-pub (pub/pub source message-topic)

        ;; Create reactive state
        [participants-signal status-signal message-history subscriptions-atom]
        (binding [rtc/*execution-context* spindel-ctx]
          [(sig/signal {})           ; participants
           (sig/signal :active)      ; status
           (ratom/create-atom [])    ; message history
           (ratom/create-atom {})])  ; subscriptions
        ]

    (->ChatRoom
     room-id
     (or title "Chat Room")
     spindel-ctx
     message-pub
     post-fn
     participants-signal
     status-signal
     message-history
     chat-ctx
     subscriptions-atom)))

;; ============================================================================
;; Participant Management
;; ============================================================================

(defn join-room!
  "Add a participant to the room.

   Args:
     room - ChatRoom
     agent-id - Unique identifier for the participant
     opts - Map with:
       :role - Participant role (:manager, :worker, :observer)
       :topics - Additional topics to subscribe to (beyond defaults)
       :buffer-size - Message buffer size (default 100)

   Returns subscription map with topic subscriptions."
  [room agent-id {:keys [role topics buffer-size]
                  :or {role :worker buffer-size 100}}]
  (let [{:keys [spindel-ctx message-pub participants-signal subscriptions-atom]} room

        ;; Default topics based on role
        default-topics (cond-> [:broadcast :system]
                         ;; Everyone gets their direct topic
                         true (conj (keyword (str "agent-" (name agent-id))))
                         ;; Role-based topic
                         role (conj (keyword (str "role-" (name role)))))

        all-topics (into (set default-topics) topics)

        ;; Create subscriptions for each topic
        buffer (buf/sliding-buffer buffer-size)]

    (binding [rtc/*execution-context* spindel-ctx]
      ;; Subscribe to each topic
      (let [subs (doall
                  (for [topic all-topics]
                    {:topic topic
                     :subscription (pub/sub message-pub topic buffer)}))]

        ;; Register participant
        (swap! participants-signal
               assoc agent-id {:role role
                               :topics all-topics
                               :joined-at (java.util.Date.)})

        ;; Store subscriptions
        (swap! subscriptions-atom assoc agent-id subs)

        {:agent-id agent-id
         :role role
         :topics all-topics
         :subscriptions subs}))))

(defn leave-room!
  "Remove a participant from the room."
  [room agent-id]
  (let [{:keys [spindel-ctx message-pub participants-signal subscriptions-atom]} room]
    (binding [rtc/*execution-context* spindel-ctx]
      ;; Unsubscribe from all topics
      (when-let [subs (get @subscriptions-atom agent-id)]
        (doseq [{:keys [topic subscription]} subs]
          (pub/unsub message-pub topic subscription)))

      ;; Remove from participants
      (swap! participants-signal dissoc agent-id)
      (swap! subscriptions-atom dissoc agent-id))))

(defn get-participants
  "Get all participants in the room."
  [room]
  (binding [rtc/*execution-context* (:spindel-ctx room)]
    @(:participants-signal room)))

;; ============================================================================
;; Message Operations
;; ============================================================================

(defn publish!
  "Publish a message to the room.

   Args:
     room - ChatRoom
     content - Message content
     opts - Map with :from, :topic, :to, :role, :type, :metadata

   The message is:
   1. Routed via pub-sub to appropriate subscribers
   2. Stored in message history
   3. Persisted to Datahike"
  [room content opts]
  (let [{:keys [spindel-ctx post-fn message-history chat-ctx]} room
        msg (create-message content opts)]

    (binding [rtc/*execution-context* spindel-ctx]
      ;; Add to history
      (swap! message-history conj msg)

      ;; Persist to datahike
      (when-let [conn (:db-conn chat-ctx)]
        (dh/transact conn
          [(schema/create-message-entity
            {:chat-id (:chat-id chat-ctx)
             :role (or (:from opts) :system)
             :content (pr-str msg)})]))

      ;; Post to pub-sub (routes to subscribers)
      (post-fn msg))

    msg))

(defn receive-spin
  "Create a spin that awaits the next message for a participant.

   Args:
     room - ChatRoom
     agent-id - The participant's agent-id
     opts - Map with:
       :filter - Optional (fn [msg] -> bool) to filter messages
       :timeout-ms - Optional timeout in milliseconds

   Returns a spin that resolves to the next matching message."
  [room agent-id {:keys [filter timeout-ms]}]
  (let [{:keys [spindel-ctx subscriptions-atom]} room]
    (binding [rtc/*execution-context* spindel-ctx]
      (when-let [subs (get @subscriptions-atom agent-id)]
        ;; For simplicity, take from first subscription with items
        ;; A more sophisticated impl would merge all subscriptions
        (spin
          (loop [remaining-subs subs]
            (if (empty? remaining-subs)
              ;; No messages on any subscription, try again
              (do
                (await (org.replikativ.spindel.core/sleep 10))
                (recur subs))
              (let [{:keys [subscription]} (first remaining-subs)]
                (if-let [[msg _rest] (await (anext subscription))]
                  (if (or (nil? filter) (filter msg))
                    msg
                    (recur (rest remaining-subs)))
                  (recur (rest remaining-subs)))))))))))

(defn get-history
  "Get message history for the room."
  [room]
  (binding [rtc/*execution-context* (:spindel-ctx room)]
    @(:message-history room)))

;; ============================================================================
;; Room Lifecycle
;; ============================================================================

(defn close-room!
  "Close the room and clean up resources."
  [room]
  (let [{:keys [spindel-ctx message-pub status-signal subscriptions-atom]} room]
    (binding [rtc/*execution-context* spindel-ctx]
      ;; Update status
      (reset! status-signal :closed)

      ;; Unsubscribe all
      (pub/unsub-all message-pub)

      ;; Clear subscriptions
      (reset! subscriptions-atom {}))))

(defn room-status
  "Get current room status."
  [room]
  (binding [rtc/*execution-context* (:spindel-ctx room)]
    @(:status-signal room)))

;; ============================================================================
;; Convenience Functions for Feedback Patterns
;; ============================================================================

(defn send-feedback!
  "Send feedback from manager to worker.

   Convenience wrapper for feedback pattern."
  [room from-agent-id to-agent-id feedback-content]
  (publish! room feedback-content
            {:from from-agent-id
             :to to-agent-id
             :type :feedback}))

(defn signal-completion!
  "Signal completion from worker to manager(s).

   Convenience wrapper for completion signaling."
  [room from-agent-id completion-data]
  (publish! room completion-data
            {:from from-agent-id
             :role :manager
             :type :completion}))

(defn send-approval!
  "Send approval from manager to worker.

   Convenience wrapper for approval pattern."
  [room from-agent-id to-agent-id]
  (publish! room {:approved true}
            {:from from-agent-id
             :to to-agent-id
             :type :approval}))

(defn send-rejection!
  "Send rejection from manager to worker.

   Convenience wrapper for rejection pattern."
  [room from-agent-id to-agent-id reason]
  (publish! room {:rejected true :reason reason}
            {:from from-agent-id
             :to to-agent-id
             :type :rejection}))

(comment
  ;; Example usage:

  ;; Create a room
  (def room (create-room {:title "Code Review"}))

  ;; Add participants
  (def manager-sub (join-room! room :manager-1 {:role :manager}))
  (def worker-sub (join-room! room :worker-1 {:role :worker}))

  ;; Worker publishes completion
  (signal-completion! room :worker-1 {:summary "Implemented feature X"})

  ;; Manager receives and reviews
  ;; (receive-spin room :manager-1 {:filter #(= (:type %) :completion)})

  ;; Manager sends feedback
  (send-feedback! room :manager-1 :worker-1 "Please add tests")

  ;; Worker receives feedback
  ;; (receive-spin room :worker-1 {:filter #(= (:type %) :feedback)})

  ;; Eventually manager approves
  (send-approval! room :manager-1 :worker-1)

  ;; Check history
  (get-history room)

  ;; Cleanup
  (close-room! room)
  )
