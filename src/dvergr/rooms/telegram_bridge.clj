(ns dvergr.rooms.telegram-bridge
  "Bridge between Telegram messages and the room system.

   Auto-creates room entities for new Telegram chat IDs and persists
   every message before dispatch. Called from daemon's on-message callback."
  (:require [dvergr.rooms :as rooms]
            [taoensso.telemere :as tel]))

(defn store-telegram-message!
  "Store a Telegram message in its corresponding room.

   Auto-creates the room if this is the first message from this chat ID.
   Slug format: \"tg-{chat-id}\" (e.g. \"tg-123456789\").

   Args:
     conn - Datahike connection
     msg  - Normalized Telegram message map with :chat-id, :text, :from"
  [conn msg]
  (let [chat-id   (:chat-id msg)
        text      (:text msg)
        user-info (:from msg)]
    (when (and chat-id text (number? chat-id))
      (try
        ;; Find or create room
        (let [room (or (rooms/get-room-by-telegram-id conn chat-id)
                       (let [slug (str "tg-" chat-id)
                             title (or (:title msg)
                                       (str "Telegram " chat-id))
                             room-chat-id (rooms/create-room! conn
                                            {:title title
                                             :slug slug
                                             :type :telegram-mirror
                                             :telegram-chat-id chat-id})]
                         (rooms/get-room-by-telegram-id conn chat-id)))
              room-chat-id (:chat/id room)
              display-name (or (:first_name user-info)
                               (:username user-info)
                               "unknown")]
          (rooms/post-message! conn room-chat-id
            {:content text
             :role :user
             :source-user display-name
             :source-username (:username user-info)
             :source-user-id (:id user-info)}))
        (catch Exception e
          (tel/log! {:level :warn :id :telegram-bridge/store-error
                     :data {:chat-id chat-id :error (.getMessage e)}}
                    "Failed to store Telegram message in room"))))))
