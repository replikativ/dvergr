(ns dvergr.rooms
  "Room CRUD and queries.

   Rooms are persistent chat entities that mirror Telegram groups or exist
   as internal-only channels. Each room is a :chat/* entity with additional
   :room/* attributes (type, slug, telegram-chat-id).

   Messages posted to rooms are stored with :message/source-user metadata
   for attribution in the web UI.

   Usage:
     (init! datahike-conn)
     (create-room! conn {:title \"General\" :slug \"general\" :type :internal})
     (post-message! conn chat-id {:content \"hello\" :role :user :source-user \"Alice\"})
     (get-messages conn chat-id :limit 50)"
  (:require [datahike.api :as dh]
            [dvergr.chat.schema :as schema]
            [dvergr.rooms.bus :as bus]
            [clojure.edn :as edn]
            [clojure.set]
            [clojure.string]
            [taoensso.telemere :as tel]))

;; ============================================================================
;; State
;; ============================================================================

(defonce ^:private conn-a (atom nil))

(defn init!
  "Store the shared Datahike connection. Call once at startup."
  [datahike-conn]
  (reset! conn-a datahike-conn))

;; ============================================================================
;; Room CRUD
;; ============================================================================

(defn create-room!
  "Create a new room entity.

   Opts:
     :title            - Display title
     :slug             - URL-friendly identifier (unique)
     :type             - :telegram-mirror or :internal
     :telegram-chat-id - Telegram chat ID (for mirrors, long)
     :agent-ids        - Set of agent keywords participating

   Returns the chat-id (UUID) of the created room."
  [conn {:keys [title slug type telegram-chat-id agent-ids]}]
  (let [chat-entity (schema/create-chat-entity {:title (or title slug)})
        chat-id (:chat/id chat-entity)
        room-entity (cond-> (merge chat-entity
                                   {:room/slug slug
                                    :room/type (or type :internal)})
                      telegram-chat-id (assoc :room/telegram-chat-id (long telegram-chat-id))
                      (seq agent-ids)  (assoc :room/agent-ids (set agent-ids)))]
    (dh/transact conn [room-entity])
    (tel/log! {:id :rooms/created :data {:slug slug :chat-id chat-id}} "Room created")
    chat-id))

(defn get-room-by-slug
  "Find a room by its URL slug. Returns entity map or nil."
  [conn slug]
  (dh/q '[:find (pull ?e [*]) .
          :in $ ?slug
          :where [?e :room/slug ?slug]]
        @conn slug))

(defn get-room-by-telegram-id
  "Find a room by its Telegram chat ID. Returns entity map or nil."
  [conn telegram-chat-id]
  (dh/q '[:find (pull ?e [*]) .
          :in $ ?tid
          :where [?e :room/telegram-chat-id ?tid]]
        @conn (long telegram-chat-id)))

(defn list-rooms
  "List all rooms, optionally filtered by type.

   Returns vector of room entity maps sorted by last update."
  [conn & {:keys [type]}]
  (let [rooms (if type
                (dh/q '[:find [(pull ?e [*]) ...]
                        :in $ ?t
                        :where
                        [?e :room/slug _]
                        [?e :room/type ?t]]
                      @conn type)
                (dh/q '[:find [(pull ?e [*]) ...]
                        :where [?e :room/slug _]]
                      @conn))]
    (->> rooms
         (sort-by #(- (.getTime (or (:chat/updated-at %) (java.util.Date. 0))))))))

;; ============================================================================
;; Messages
;; ============================================================================

(defn post-message!
  "Post a message to a room.

   Opts:
     :content         - Message text
     :role            - :user or :assistant (default :user)
     :source-user     - Display name of sender
     :source-username - Username (e.g. Telegram @handle)
     :source-user-id  - Numeric user ID
     :source-agent-id - Agent ID string (for self-filter in enrichment)

   Updates room's :chat/updated-at timestamp."
  [conn chat-id {:keys [content role source-user source-username source-user-id
                        source-agent-id]}]
  (let [msg (cond-> (schema/create-message-entity
                      {:chat-id chat-id
                       :role (or role :user)
                       :content content})
              source-user     (assoc :message/source-user source-user)
              source-username (assoc :message/source-username source-username)
              source-user-id  (assoc :message/source-user-id (long source-user-id)))]
    (dh/transact conn [msg
                       {:db/id [:chat/id chat-id]
                        :chat/updated-at (java.util.Date.)}])
    ;; Publish lightweight event to the room bus for agent subscriptions
    (when (bus/initialized?)
      (let [room (dh/q '[:find (pull ?e [:room/slug]) .
                          :in $ ?cid
                          :where [?e :chat/id ?cid]]
                        @conn chat-id)]
        (when (:room/slug room)
          (bus/publish! (cond-> {:room-slug  (:room/slug room)
                                 :room-id    chat-id
                                 :role       (or role :user)
                                 :source     (or source-user "unknown")
                                 :preview    (when content
                                               (subs content 0 (min 120 (count content))))
                                 :timestamp  (java.util.Date.)}
                          source-agent-id (assoc :source-agent-id source-agent-id))))))))

(defn get-messages
  "Get messages for a room, ordered by creation time (oldest first).

   Options:
     :limit  - Max messages to return (default 100)
     :offset - Skip this many oldest messages (default 0)"
  [conn chat-id & {:keys [limit] :or {limit 100}}]
  (->> (dh/q '[:find [(pull ?m [:message/id :message/role :message/content
                                :message/created-at :message/source-user
                                :message/source-username]) ...]
               :in $ ?cid
               :where
               [?c :chat/id ?cid]
               [?m :message/chat ?c]]
             @conn chat-id)
       (sort-by #(.getTime (or (:message/created-at %) (java.util.Date. 0))))
       (take-last limit)
       vec))

;; ============================================================================
;; Agent membership
;; ============================================================================

(defn join-agent!
  "Add an agent to a room's participant list."
  [conn chat-id agent-id]
  (dh/transact conn [{:db/id [:chat/id chat-id]
                       :room/agent-ids agent-id}]))

(defn leave-agent!
  "Remove an agent from a room's participant list."
  [conn chat-id agent-id]
  (dh/transact conn [[:db/retract [:chat/id chat-id] :room/agent-ids agent-id]]))

;; ============================================================================
;; Context formatting
;; ============================================================================

(defn format-history-for-context
  "Format the last N room messages as a readable transcript for agent context.

   Returns a string like:
     [Alice] Hello everyone
     [bot] Hi Alice!
     [Bob] What's new?"
  [conn chat-id & {:keys [limit] :or {limit 30}}]
  (let [msgs (get-messages conn chat-id :limit limit)]
    (if (seq msgs)
      (->> msgs
           (map (fn [m]
                  (let [user (or (:message/source-user m)
                                (name (or (:message/role m) :unknown)))]
                    (str "[" user "] " (:message/content m)))))
           (clojure.string/join "\n"))
      "")))

;; ============================================================================
;; Message Reactions & Voting
;; ============================================================================

(defn react!
  "Add a reaction (emoji) to a message.

   Reactions are stored as an EDN map: {emoji -> [agent-id ...]}.
   Duplicate agent reactions to the same emoji are ignored.

   Key emojis:  \"+1\" \"-1\" \"eyes\" \"rocket\"

   Args:
     conn       - Datahike connection
     message-id - UUID of the message
     emoji      - String emoji key
     agent-id   - Keyword agent-id of the reactor"
  [conn message-id emoji agent-id]
  (let [msg (dh/q '[:find (pull ?m [:message/reactions]) .
                     :in $ ?mid
                     :where [?m :message/id ?mid]]
                   @conn message-id)
        current (if-let [r (:message/reactions msg)]
                  (clojure.edn/read-string r)
                  {})
        existing (get current emoji [])
        updated  (if (some #{agent-id} existing)
                   current  ; Already reacted
                   (assoc current emoji (conj existing agent-id)))]
    (dh/transact conn [{:db/id [:message/id message-id]
                        :message/reactions (pr-str updated)}])))

(defn get-reactions
  "Get reactions for a message.

   Returns map of {emoji -> [agent-id ...]} or empty map."
  [conn message-id]
  (let [msg (dh/q '[:find (pull ?m [:message/reactions]) .
                     :in $ ?mid
                     :where [?m :message/id ?mid]]
                   @conn message-id)]
    (if-let [r (:message/reactions msg)]
      (clojure.edn/read-string r)
      {})))

(def ^:private board-members
  "Agents with board voting rights."
  #{:var :volva :runa :mimir :skald :muninn :huginn})

(defn tally-votes
  "Tally votes on a message using +1/-1 reactions.

   Returns {:for N :against M :abstain K :decided? bool :result :approved/:rejected/nil}
   A decision is reached when a majority of board members have voted."
  [conn message-id]
  (let [reactions (get-reactions conn message-id)
        for-ids  (set (get reactions "+1" []))
        against-ids (set (get reactions "-1" []))
        voted    (clojure.set/union for-ids against-ids)
        board-voted (clojure.set/intersection voted board-members)
        for-count (count (clojure.set/intersection for-ids board-members))
        against-count (count (clojure.set/intersection against-ids board-members))
        total-board (count board-members)
        majority (inc (quot total-board 2))
        decided? (or (>= for-count majority) (>= against-count majority))]
    {:for for-count
     :against against-count
     :abstain (- total-board (count board-voted))
     :decided? decided?
     :result (cond
               (>= for-count majority) :approved
               (>= against-count majority) :rejected
               :else nil)}))

;; ============================================================================
;; Room Hierarchy
;; ============================================================================

(defn get-child-rooms
  "Get child rooms of a parent room.

   Args:
     conn          - Datahike connection
     parent-chat-id - UUID of the parent room"
  [conn parent-chat-id]
  (dh/q '[:find [(pull ?e [*]) ...]
          :in $ ?pid
          :where [?e :room/parent-id ?pid]]
        @conn parent-chat-id))

(defn summarize-to-parent!
  "Summarize recent messages in a child room and post to its parent.

   Uses llm/summarize on messages since the last summary (or last 50 messages).
   Posts the summary to the parent room as a system message.

   Args:
     conn     - Datahike connection
     child-chat-id - UUID of the child room

   Returns the summary string, or nil if no parent or no messages."
  [conn child-chat-id]
  (let [room (dh/q '[:find (pull ?e [:room/parent-id :room/slug]) .
                      :in $ ?cid
                      :where [?e :chat/id ?cid]]
                    @conn child-chat-id)]
    (when-let [parent-id (:room/parent-id room)]
      (let [msgs (get-messages conn child-chat-id :limit 50)]
        (when (seq msgs)
          ;; Use LLM to summarize
          (require 'dvergr.llm-call)
          (let [call-fn @(ns-resolve 'dvergr.llm-call 'cheap-llm-call)
                transcript (->> msgs
                                (map (fn [m]
                                       (str "[" (or (:message/source-user m) "system") "] "
                                            (:message/content m))))
                                (clojure.string/join "\n"))
                summary-result (call-fn
                                 "Summarize this room discussion concisely (2-4 bullets). Focus on decisions, action items, and key insights:"
                                 transcript
                                 {:max-tokens 300})]
            (when-let [summary (:text summary-result)]
              (post-message! conn parent-id
                             {:content (str "Summary from #" (or (:room/slug room) "child") ":\n" summary)
                              :role :user
                              :source-user "system"})
              summary)))))))

(defn set-parent!
  "Set the parent room for a child room.

   Args:
     conn          - Datahike connection
     child-chat-id - UUID of the child room
     parent-chat-id - UUID of the parent room"
  [conn child-chat-id parent-chat-id]
  (dh/transact conn [{:db/id [:chat/id child-chat-id]
                       :room/parent-id parent-chat-id}]))
