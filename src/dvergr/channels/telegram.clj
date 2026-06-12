(ns dvergr.channels.telegram
  "Telegram Bot API channel for dvergr.

   Uses hato (already in deps) for HTTP calls to the Telegram Bot API.
   Zero new dependencies.

   Usage:
     (require '[dvergr.channels.core :as ch])
     (require '[dvergr.channels.telegram :as tg])

     (def bot (ch/connect!
                (tg/make-telegram {:token (System/getenv \"TELEGRAM_BOT_TOKEN\")})
                :on-message (fn [msg] (println \"Got:\" msg))))"
  (:require [hato.client :as hc]
            [jsonista.core :as json]
            [dvergr.channels.core :as channels]
            [dvergr.adapters.core :as adapters]
            [dvergr.security.allowlist :as allowlist]
            [dvergr.actors :as actors]
            [dvergr.channels.telegram-commands :as tc]
            [dvergr.discourse.commands :as commands]
            [org.replikativ.spindel.engine.core :as rtc]
            [taoensso.telemere :as tel]
            [clojure.string :as str]))

(def ^:private json-mapper
  (json/object-mapper {:decode-key-fn keyword}))

;; ============================================================================
;; Telegram Bot API HTTP helpers
;; ============================================================================

(defn- api-url [token method]
  (str "https://api.telegram.org/bot" token "/" method))

(defn api-call
  "Call a Telegram Bot API method. Returns the parsed :result on success."
  [token method params]
  (let [resp (hc/post (api-url token method)
                      {:content-type :json
                       :body (json/write-value-as-string params)
                       :as :string
                       :throw-exceptions false})
        body (json/read-value (:body resp) json-mapper)]
    (if (:ok body)
      (:result body)
      (throw (ex-info (str "Telegram API error: " (:description body))
                      {:method method
                       :error_code (:error_code body)
                       :description (:description body)})))))

(defn set-my-commands!
  "Register the bot's slash-command menu with Telegram (`setMyCommands`), derived
   from `dvergr.ops` via `telegram-commands/menu`. Best-effort: a failure is
   logged, never fatal (the dispatcher still works without the menu)."
  [token]
  (try
    (let [menu (tc/menu)]
      (api-call token "setMyCommands" {:commands menu})
      (tel/log! {:level :info :id :telegram/commands-registered
                 :data {:count (count menu)}}))
    (catch Throwable e
      (tel/log! {:level :warn :id :telegram/commands-failed
                 :data {:error (.getMessage e)}}))))

(defn send-chat-action!
  "Send a chat action (e.g. \"typing\") to `chat-id` — the native, ephemeral
   'the bot is doing something' cue. Telegram clears it after ~5s or when a
   message arrives. Best-effort: never throws."
  ([token chat-id] (send-chat-action! token chat-id "typing"))
  ([token chat-id action]
   (try (api-call token "sendChatAction" {:chat_id chat-id :action action})
        (catch Throwable _ nil))))

;; ============================================================================
;; Long message chunking
;; ============================================================================

(def ^:const ^:private max-message-length
  "Telegram's maximum message length."
  4096)

(defn- chunk-text
  "Split text into chunks respecting Telegram's message size limit.
   Tries to break at paragraph, newline, then space boundaries."
  [text max-len]
  (if (<= (count text) max-len)
    [text]
    (loop [remaining text
           chunks []]
      (if (<= (count remaining) max-len)
        (conj chunks remaining)
        (let [break-at (or
                        (let [idx (str/last-index-of remaining "\n\n" max-len)]
                          (when (and idx (> idx (/ max-len 4)))
                            (+ idx 2)))
                        (let [idx (str/last-index-of remaining "\n" max-len)]
                          (when (and idx (> idx (/ max-len 4)))
                            (+ idx 1)))
                        (let [idx (str/last-index-of remaining " " max-len)]
                          (when (and idx (> idx (/ max-len 4)))
                            (+ idx 1)))
                        max-len)]
          (recur (subs remaining break-at)
                 (conj chunks (subs remaining 0 break-at))))))))

(defn send-long-message!
  "Send a text message to Telegram, chunking if it exceeds the limit.

   Args:
     token      - Bot API token
     chat-id    - Telegram chat ID
     text       - Message text (may be longer than 4096 chars)
     parse-mode - Optional parse mode (\"Markdown\", \"HTML\", etc.)

   Returns vector of message-ids for all sent chunks."
  [token chat-id text & {:keys [parse-mode]}]
  (let [chunks (chunk-text text max-message-length)]
    (mapv (fn [chunk]
            (let [params (cond-> {:chat_id chat-id :text chunk}
                           parse-mode (assoc :parse_mode parse-mode))
                  result (api-call token "sendMessage" params)]
              (:message_id result)))
          chunks)))

;; ============================================================================
;; Message normalization
;; ============================================================================

(defn- normalize-message
  "Normalize a Telegram update to a common channel message format.
   Handles both regular messages and callback queries (inline keyboard buttons)."
  [update]
  (if-let [callback (:callback_query update)]
    ;; Callback query from inline keyboard
    {:channel      :telegram
     :chat-id      (get-in callback [:message :chat :id])
     :message-id   (get-in callback [:message :message_id])
     :from         (select-keys (:from callback) [:id :username :first_name :last_name])
     :text         (:data callback)
     :callback-id  (:id callback)
     :type         :callback
     :timestamp    (java.util.Date.)
     :raw          update}
    ;; Regular message
    (let [msg (or (:message update) (:edited_message update))]
      (when msg
        {:channel    :telegram
         :chat-id    (get-in msg [:chat :id])
         :message-id (:message_id msg)
         :from       (select-keys (:from msg) [:id :username :first_name :last_name])
         :text       (:text msg)
         :type       :message
         :timestamp  (when-let [ts (:date msg)]
                       (java.util.Date. (* ts 1000)))
         :raw        update}))))

;; ============================================================================
;; Long polling
;; ============================================================================

(defn- poll-updates
  "Get updates from Telegram with long polling. Returns vector of updates."
  [token offset timeout-secs]
  (try
    (api-call token "getUpdates"
              {:offset offset
               :timeout timeout-secs
               :allowed_updates ["message" "callback_query"]})
    (catch Exception e
      ;; Inbound boundary — a poll failure (transient 409 during restart, network)
      ;; means we briefly stopped receiving. :warn (recoverable, we back off + retry).
      (tel/log! {:level :warn :id :telegram/poll-failed :data {:error (.getMessage e)}})
      ;; Back off on error
      (Thread/sleep 5000)
      [])))

(defn- start-polling!
  "Start background long-polling thread. Returns the future."
  [channel]
  (future
    ;; Register the spec-derived slash-command menu once, when polling starts.
    (set-my-commands! (get-in channel [:config :token]))
    (loop [offset 0]
      (when (:connected? @(:state channel))
        (let [token (get-in channel [:config :token])
              updates (poll-updates token offset 30)
              new-offset (if (seq updates)
                           (inc (:update_id (last updates)))
                           offset)]
          (doseq [update updates]
            (when-let [msg (normalize-message update)]
              (when-let [on-msg (:on-message @(:state channel))]
                (try
                  (on-msg msg)
                  (catch Exception e
                    ;; An inbound message failed to route — visible, not lost.
                    (tel/log! {:level :error :id :telegram/inbound-failed
                               :data {:chat-id (:chat-id msg) :error (.getMessage e)}}))))))
          (recur new-offset))))))

;; ============================================================================
;; Tool handlers
;; ============================================================================

(defn- handle-send-text
  "Send a text message to a Telegram chat."
  [input _ctx channel]
  (let [token (get-in channel [:config :token])
        chat-id (:chat_id input)
        text (:text input)
        parse-mode (:parse_mode input)]
    (when-not chat-id
      (throw (ex-info "chat_id is required" {})))
    (when-not text
      (throw (ex-info "text is required" {})))
    (let [params (cond-> {:chat_id chat-id :text text}
                   parse-mode (assoc :parse_mode parse-mode))
          result (api-call token "sendMessage" params)]
      {:type :success
       :content (str "Message sent to chat " chat-id ". Message ID: " (:message_id result))
       :metadata {:message_id (:message_id result)
                  :chat_id chat-id}})))

(defn- handle-get-updates
  "Get recent updates/messages."
  [input _ctx channel]
  (let [token (get-in channel [:config :token])
        limit (min (or (:limit input) 10) 100)
        updates (api-call token "getUpdates" {:limit limit})]
    {:type :success
     :content (if (seq updates)
                (str "Recent updates (" (count updates) "):\n\n"
                     (clojure.string/join "\n\n"
                                          (map (fn [u]
                                                 (let [msg (normalize-message u)]
                                                   (str "From: " (get-in msg [:from :first_name])
                                                        (when-let [un (get-in msg [:from :username])]
                                                          (str " (@" un ")"))
                                                        "\nChat: " (:chat-id msg)
                                                        "\nText: " (:text msg)
                                                        "\nTime: " (:timestamp msg))))
                                               updates)))
                "No recent updates.")
     :metadata {:count (count updates)
                :updates (mapv normalize-message updates)}}))

(defn- handle-chat-info
  "Get info about a chat."
  [input _ctx channel]
  (let [token (get-in channel [:config :token])
        chat-id (:chat_id input)]
    (when-not chat-id
      (throw (ex-info "chat_id is required" {})))
    (let [chat (api-call token "getChat" {:chat_id chat-id})]
      {:type :success
       :content (str "Chat info:\n"
                     "  ID: " (:id chat) "\n"
                     "  Type: " (:type chat) "\n"
                     "  Title: " (or (:title chat) "N/A") "\n"
                     "  Username: " (or (:username chat) "N/A") "\n"
                     "  First name: " (or (:first_name chat) "N/A"))
       :metadata chat})))

(defn- handle-send-inline-keyboard
  "Send a message with an inline keyboard (buttons with callback data)."
  [input _ctx channel]
  (let [token (get-in channel [:config :token])
        chat-id (:chat_id input)
        text (:text input)
        buttons (:buttons input)]
    (when-not chat-id (throw (ex-info "chat_id is required" {})))
    (when-not text (throw (ex-info "text is required" {})))
    (when-not (seq buttons) (throw (ex-info "buttons are required" {})))
    (let [;; buttons is a vec of {:text "Label" :data "callback_data"}
          keyboard-rows (mapv (fn [btn]
                                [{:text (:text btn)
                                  :callback_data (:data btn)}])
                              buttons)
          params (cond-> {:chat_id chat-id
                          :text text
                          :reply_markup {:inline_keyboard keyboard-rows}}
                   (:parse_mode input) (assoc :parse_mode (:parse_mode input)))
          result (api-call token "sendMessage" params)]
      {:type :success
       :content (str "Message with " (count buttons) " button(s) sent to chat " chat-id)
       :metadata {:message_id (:message_id result)
                  :chat_id chat-id}})))

(defn- handle-answer-callback
  "Answer a callback query (acknowledge button press)."
  [input _ctx channel]
  (let [token (get-in channel [:config :token])
        callback-id (:callback_id input)
        text (:text input)]
    (when-not callback-id (throw (ex-info "callback_id is required" {})))
    (api-call token "answerCallbackQuery"
              (cond-> {:callback_query_id callback-id}
                text (assoc :text text)))
    {:type :success
     :content (str "Callback answered: " callback-id)
     :metadata {:callback_id callback-id}}))

(defn- handle-send-photo
  "Send a photo to a Telegram chat."
  [input _ctx channel]
  (let [token (get-in channel [:config :token])
        chat-id (:chat_id input)
        photo (:photo input)
        caption (:caption input)]
    (when-not chat-id
      (throw (ex-info "chat_id is required" {})))
    (when-not photo
      (throw (ex-info "photo URL is required" {})))
    (let [params (cond-> {:chat_id chat-id :photo photo}
                   caption (assoc :caption caption))
          result (api-call token "sendPhoto" params)]
      {:type :success
       :content (str "Photo sent to chat " chat-id ". Message ID: " (:message_id result))
       :metadata {:message_id (:message_id result)
                  :chat_id chat-id}})))

;; ============================================================================
;; Tool definitions
;; ============================================================================

(def ^:private telegram-capabilities
  #{:tg/send-text :tg/receive :tg/send-photo :tg/get-updates :tg/chat-info
    :tg/inline-keyboard :tg/answer-callback})

(def ^:private default-permissions
  #{:tg/send-text :tg/receive :tg/get-updates :tg/chat-info
    :tg/inline-keyboard :tg/answer-callback})

(defn- make-tool-defs []
  [{:capability :tg/send-text
    :name "telegram_send"
    :description "Send a text message to a Telegram chat."
    :parameters {:type "object"
                 :properties {:chat_id {:type "integer"
                                        :description "Telegram chat ID to send to"}
                              :text {:type "string"
                                     :description "Message text to send"}
                              :parse_mode {:type "string"
                                           :enum ["Markdown" "MarkdownV2" "HTML"]
                                           :description "Optional parse mode for formatting"}}
                 :required ["chat_id" "text"]}}

   {:capability :tg/get-updates
    :name "telegram_updates"
    :description "Get recent Telegram messages/updates."
    :parameters {:type "object"
                 :properties {:limit {:type "integer"
                                      :description "Max number of updates to return (default 10, max 100)"}}}}

   {:capability :tg/chat-info
    :name "telegram_chat_info"
    :description "Get info about a Telegram chat (type, title, members)."
    :parameters {:type "object"
                 :properties {:chat_id {:type "integer"
                                        :description "Telegram chat ID"}}
                 :required ["chat_id"]}}

   {:capability :tg/send-photo
    :name "telegram_send_photo"
    :description "Send a photo to a Telegram chat by URL."
    :parameters {:type "object"
                 :properties {:chat_id {:type "integer"
                                        :description "Telegram chat ID"}
                              :photo {:type "string"
                                      :description "Photo URL to send"}
                              :caption {:type "string"
                                        :description "Optional photo caption"}}
                 :required ["chat_id" "photo"]}}

   {:capability :tg/receive
    :name "telegram_poll"
    :description "Trigger a manual poll for new Telegram messages. Note: if long-polling is active, messages are delivered automatically via on-message callback."
    :parameters {:type "object"
                 :properties {:limit {:type "integer"
                                      :description "Max messages to poll (default 10)"}}}}

   {:capability :tg/inline-keyboard
    :name "telegram_inline_keyboard"
    :description "Send a message with inline keyboard buttons. Each button triggers a callback with its data value."
    :parameters {:type "object"
                 :properties {:chat_id {:type "integer"
                                        :description "Telegram chat ID"}
                              :text {:type "string"
                                     :description "Message text above buttons"}
                              :buttons {:type "array"
                                        :items {:type "object"
                                                :properties {:text {:type "string"}
                                                             :data {:type "string"}}
                                                :required ["text" "data"]}
                                        :description "Buttons: [{text: \"Approve\", data: \"merge\"}, ...]"}
                              :parse_mode {:type "string"
                                           :enum ["Markdown" "MarkdownV2" "HTML"]
                                           :description "Optional parse mode"}}
                 :required ["chat_id" "text" "buttons"]}}

   {:capability :tg/answer-callback
    :name "telegram_answer_callback"
    :description "Answer a callback query (acknowledge an inline keyboard button press)."
    :parameters {:type "object"
                 :properties {:callback_id {:type "string"
                                            :description "Callback query ID from the callback message"}
                              :text {:type "string"
                                     :description "Optional notification text shown to user"}}
                 :required ["callback_id"]}}])

;; ============================================================================
;; Channel constructor
;; ============================================================================

(defn make-telegram
  "Create a Telegram bot channel.

   Config:
     :token       - Telegram Bot API token (required)
     :poll?       - Start long-polling for incoming messages (default true)
     :permissions - Override default permissions (optional)

   Returns a channel map ready for (channels/connect!)."
  [{:keys [token poll? permissions] :or {poll? true} :as config}]
  {:pre [(string? token) (seq token)]}
  (let [channel-id (keyword (str "telegram-" (subs token (max 0 (- (count token) 6)))))
        perms (or permissions default-permissions)
        state (atom {:connected? false :polling-future nil :on-message nil})]
    (channels/make-channel
     {:id           channel-id
      :type         :telegram
      :config       config
      :capabilities telegram-capabilities
      :permissions  perms
      :tools        (make-tool-defs)
      :handlers     {"telegram_send"             (fn [input ctx] (handle-send-text input ctx
                                                                                   (channels/get-channel channel-id)))
                     "telegram_updates"          (fn [input ctx] (handle-get-updates input ctx
                                                                                     (channels/get-channel channel-id)))
                     "telegram_chat_info"        (fn [input ctx] (handle-chat-info input ctx
                                                                                   (channels/get-channel channel-id)))
                     "telegram_send_photo"       (fn [input ctx] (handle-send-photo input ctx
                                                                                    (channels/get-channel channel-id)))
                     "telegram_poll"             (fn [input ctx] (handle-get-updates input ctx
                                                                                     (channels/get-channel channel-id)))
                     "telegram_inline_keyboard"  (fn [input ctx] (handle-send-inline-keyboard input ctx
                                                                                              (channels/get-channel channel-id)))
                     "telegram_answer_callback"  (fn [input ctx] (handle-answer-callback input ctx
                                                                                         (channels/get-channel channel-id)))}
      :connect!     (fn [channel]
                      (swap! state assoc :connected? true)
                      (when poll?
                        (swap! state assoc :polling-future (start-polling! channel)))
                      channel)
      :disconnect!  (fn [channel]
                      (swap! state assoc :connected? false)
                      (when-let [f (:polling-future @state)]
                        (future-cancel f))
                      (swap! state assoc :polling-future nil))
      :state        state})))

;; ============================================================================
;; Telegram → discourse medium adapter
;;
;; A Telegram conversation is a per-chat discourse Room (the venue). Inbound
;; messages post into that Room AS the sender's user-actor; the routed agent
;; (joined to the Room) replies, routed back out via the user's egress
;; Participant. This is the Participant-on-Bus model — no `:_system`, no
;; response-sinks for the interactive path.
;;
;; Extracted from dvergr.orchestration.daemon (which must not be required here — daemon
;; depends on this ns). The daemon-side capabilities (room/actor provisioning,
;; the markdown→HTML chunked send) are injected as a `caps` map:
;;   :ctx           — execution context the rooms live on
;;   :token         — bot token (kept for symmetry; send-fn already closes over it)
;;   :default-agent — agent inbound messages route to by default
;;   :send-fn       — (fn [chat-id text]) send text out to a chat
;;   :ensure-room   — (fn [chat-id agent-id]) find-or-create the venue Room and
;;                    join `agent-id`; returns the Room
;;   :ensure-actor  — (fn [user-info]) upsert the remote user's durable actor,
;;                    returns its id
;; ============================================================================

(defn parse-command
  "Parse agent addressing and commands from message text.

   Returns one of:
   - {:command :list-agents}                  for /agents
   - {:agent-id :worker :text \"research X\"}  for /worker research X
   - nil                                       for plain text (use default agent)"
  [text]
  (let [trimmed (str/trim text)]
    (cond
      (= (str/lower-case trimmed) "/agents")
      {:command :list-agents}

      ;; /agent-name text — slash-addressed agent (optional ,;:! after the name)
      (re-matches #"^/([a-zA-Z_][a-zA-Z0-9_-]*)[,;:!]?\s+(.+)" trimmed)
      (let [[_ agent-name rest-text]
            (re-matches #"^/([a-zA-Z_][a-zA-Z0-9_-]*)[,;:!]?\s+([\s\S]+)" trimmed)]
        ;; Don't shadow the secretary commands (/status /approve /reject /task)
        (when-not (#{"status" "approve" "reject" "task"} (str/lower-case agent-name))
          {:agent-id (keyword agent-name) :text rest-text}))

      :else nil)))

(defn format-agent-list
  "Format the live (room-present) agents as a human-readable list for Telegram."
  []
  (let [agents (actors/online-actors)]
    (if (seq agents)
      (str "Registered agents:\n\n"
           (str/join "\n"
                     (map (fn [{:keys [id status description tags]}]
                            (str "- *" (name id) "* "
                                 (when (seq description) (str "— " description " "))
                                 "[" (name (or status :unknown)) "]"
                                 (when (seq tags)
                                   (str " " (str/join " " (map #(str "#" (name %)) tags))))))
                          agents))
           "\n\nAddress an agent: `/worker do something`")
      "No agents registered.")))

(defn make-daemon-adapter
  "Build the Telegram medium adapter from the daemon-injected `caps`. Thin:
   each remote user gets an egress Participant (it's a `:send-fn` adapter)."
  [{:keys [ctx default-agent send-fn ensure-room ensure-actor speaker-name]}]
  (adapters/make-adapter
   {:ctx           ctx
    :default-agent default-agent
    :send-fn       send-fn
    :ensure-room   (fn [chat-id] (ensure-room chat-id default-agent))
    :ensure-actor  ensure-actor
    :speaker-name  speaker-name}))

(defn handle-inbound!
  "Handle one inbound Telegram message via the medium `adapter`. Preserves the
   allowlist gate and the `/agents` / `/<agent>` grammar; everything else is
   posted into the per-chat Room as the user-actor. `caps` as in
   `make-daemon-adapter`."
  [adapter msg {:keys [ctx send-fn ensure-room default-agent typing-fn daemon tool-exec?] :as _caps}]
  (binding [rtc/*execution-context* ctx]
    (let [chat-id   (:chat-id msg)
          text      (:text msg)
          user-info (:from msg)
          reply!    (fn [t] (send-fn chat-id t))
          working!  (fn [] (when typing-fn (try (typing-fn chat-id) (catch Throwable _ nil))))]
      (when (and chat-id text (not (str/blank? text)))
        (if-not (allowlist/allowed? user-info)
          (do (tel/log! {:level :warn :id :telegram/unauthorized
                         :data {:user-id (:id user-info) :username (:username user-info)}}
                        "Rejected unauthorized user")
              (reply! "Access denied. You are not authorized to use this bot."))
          (let [parsed (parse-command text)]
            (cond
              ;; Spec-derived ops commands (/stats /fork /system /invite …) run
              ;; against THIS chat's room and reply directly — never posted to the
              ;; room or routed to an agent. Checked first so /invite etc. aren't
              ;; mistaken for agent addressing.
              (tc/command? text)
              (reply! (tc/dispatch! text #(ensure-room chat-id default-agent)))

              ;; Unified slash-command registry (dvergr.discourse.commands): the
              ;; SAME surface the TUI and web dispatch through — /sandbox, /plan,
              ;; /build, /model, /skill, and (step 3) the agent-scoped tool
              ;; commands. Checked after the ops commands (so /fork etc. keep
              ;; their ops behaviour) and before agent addressing. Authz is the
              ;; bot allowlist already enforced above; executing tool-commands
              ;; add their own finer gate (step 3).
              (commands/command-input? text)
              (let [room      (ensure-room chat-id default-agent)
                    notified? (volatile! false)
                    notify!   (fn [t] (vreset! notified? true) (reply! t))
                    result    (commands/execute!
                               text
                               {:room       room
                                :agent-id   default-agent
                                :daemon     daemon
                                :exec-ctx   ctx
                                :tool-exec? tool-exec?
                                :notify!    notify!
                                 ;; A :prompt/:skill command posts a user turn.
                                :post-user! (fn [t]
                                              (working!)
                                              (adapters/inbound!
                                               adapter
                                               {:chat-id chat-id :user user-info :text t}))})]
                ;; notify! already replied for handler/builtin commands; only the
                ;; bare :reply path (e.g. "unknown command") still needs sending.
                (when (and (:reply result) (not @notified?))
                  (reply! (:reply result))))

              (= :list-agents (:command parsed))
              (reply! (format-agent-list))

              (:agent-id parsed)
              (if (actors/online? (:agent-id parsed))
                (let [target (:agent-id parsed)]
                  (ensure-room chat-id target)   ; make sure target is joined
                  (working!)
                  (adapters/inbound! (assoc adapter :default-agent target)
                                     {:chat-id chat-id :user user-info :text (:text parsed)}))
                (reply! (str "Unknown agent: " (name (:agent-id parsed))
                             "\n\nUse /agents to see available agents.")))

              :else
              (do (working!)
                  (adapters/inbound! adapter
                                     {:chat-id chat-id :user user-info :text text})))))))))
