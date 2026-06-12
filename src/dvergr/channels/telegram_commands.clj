(ns dvergr.channels.telegram-commands
  "Telegram binding for `dvergr.ops` — the Telegram analogue of `dvergr.web.ops`.
   Derives the bot's slash-command MENU (`setMyCommands`) and dispatches inbound
   `/commands` to `ops/invoke`, with the current chat's Room implicit: in Telegram
   a chat IS a room, so room-scoped ops drop their `:room` arg from the command
   name and operate on the chat they're typed in.

   The central spec stays pure semantics; this module carries only the Telegram
   specifics — a short command name per op and how an op's result reads back as a
   chat reply. Description text for the menu is pulled from each op's spec `:doc`.

   Scope (auth deferred): reads + reversible room-local writes. Destructive ops
   (room/delete, agent create/delete) are intentionally NOT exposed here until the
   capability/auth layer lands — that's the one knob this overlay gates."
  (:require [clojure.string :as str]
            [dvergr.ops :as ops]))

(defn- current-daemon []
  (some-> (requiring-resolve 'dvergr.orchestration.daemon/current-daemon) deref deref))

;; ---------------------------------------------------------------------------
;; Reply formatters — plain text (no Markdown escaping headaches), tolerant of
;; keyword/string/nil so a shape tweak in an op never throws in a chat.
;; ---------------------------------------------------------------------------

(defn- kw-name [x] (cond (keyword? x) (name x) (nil? x) nil :else (str x)))
(defn- money [x] (format "$%.3f" (double (or x 0))))

(defn- fmt-rooms [rooms]
  (if (seq rooms)
    (str "Rooms (" (count rooms) "):\n"
         (str/join "\n" (for [r rooms]
                          (let [n (count (:participants r))]
                            (str "• " (:title r) "  [" n " participant" (when (not= 1 n) "s") "]")))))
    "No rooms."))

(defn- fmt-agents [agents]
  (if (seq agents)
    (str "Agents (" (count agents) "):\n"
         (str/join "\n" (for [a agents]
                          (str (if (:online? a) "🟢 " "⚪ ") (kw-name (:id a))
                               (when (:model a) (str " · " (:model a)))))))
    "No agents."))

(defn- fmt-system [s]
  (str "System:\n"
       "• rooms: " (:room-count s) "\n"
       "• messages: " (:message-count s) "\n"
       "• agents online: " (:agents-online s) "\n"
       "• total cost: " (money (:cost-dollars s))))

(defn- fmt-models [ms]
  (str "Models (" (count ms) "):\n"
       (str/join "\n" (for [m (take 40 ms)] (str "• " (:id m) " (" (kw-name (:provider m)) ")")))))

(defn- fmt-stats [s]
  (str (:title s) ":\n"
       "• messages: " (:message-count s) "\n"
       "• agents: " (count (:agents s)) "\n"
       "• cost: " (money (:cost-dollars s)) "\n"
       "• forks: " (:fork-count s) "\n"
       "• last active: " (:last-active-str s)))

(defn- fmt-detail [d]
  (str (:title d) " (" (:slug d) ")\n"
       (when (:fork? d) (str "↳ fork of " (:forked-from d) "\n"))
       "participants: " (if (seq (:participants d)) (str/join ", " (:participants d)) "—")))

(defn- fmt-history [msgs]
  (if (seq msgs)
    (str/join "\n" (for [m (take-last 15 msgs)]
                     (let [c (str (:content m))]
                       (str (:from m) ": " (if (> (count c) 200) (str (subs c 0 200) "…") c)))))
    "No messages."))

;; ---------------------------------------------------------------------------
;; The command overlay — short cmd → op. :room? injects the chat's Room as :room;
;; :args-fn builds extra args from the words after the command.
;; ---------------------------------------------------------------------------

(def commands
  [{:cmd "rooms"   :op :room/list     :reply fmt-rooms}
   {:cmd "agents"  :op :agent/list    :reply fmt-agents}
   {:cmd "system"  :op :system/stats  :reply fmt-system}
   {:cmd "models"  :op :models/list   :reply fmt-models}
   {:cmd "stats"   :op :room/stats    :room? true :reply fmt-stats}
   {:cmd "detail"  :op :room/detail   :room? true :reply fmt-detail}
   {:cmd "history" :op :room/messages :room? true :reply fmt-history}
   {:cmd "fork"    :op :room/fork     :room? true
    :reply (fn [r] (str "Forked into " (:title r) " (" (:id r) ").\nReply /merge to apply or /discard to drop."))}
   {:cmd "merge"   :op :room/merge    :room? true
    :reply (fn [r] (str "Merged " (:merged r) " into its parent."))}
   {:cmd "discard" :op :room/discard  :room? true
    :reply (fn [r] (str "Discarded " (:discarded r) "."))}
   {:cmd "invite"  :op :room/invite   :room? true
    :args-fn (fn [argv] {:agent (first argv)})
    :reply (fn [r] (if (:error r) (str "⚠ " (:error r)) (str "Invited " (:invited r) ".")))}])

(def ^:private by-cmd (into {} (map (juxt :cmd identity)) commands))

(defn menu
  "The `setMyCommands` payload — [{:command :description}], description from each
   op's spec :doc (Telegram caps descriptions at 256 chars)."
  []
  (vec (for [{:keys [cmd op]} commands]
         (let [d (str (get-in ops/specification [op :doc] cmd))]
           {:command cmd :description (subs d 0 (min 256 (count d)))}))))

(defn- parse [text]
  (let [t (str/trim (or text ""))]
    (when (str/starts-with? t "/")
      (let [head (first (str/split t #"\s+" 2))
            argv (->> (str/split (str/trim (subs t (count head))) #"\s+")
                      (remove str/blank?) vec)]
        {:cmd (str/lower-case (subs head 1)) :argv argv}))))

(defn command?
  "Cheap check — is `text` one of our registered slash-commands?"
  [text]
  (boolean (some-> (parse text) :cmd by-cmd)))

(defn dispatch!
  "If `text` is a known ops command, run it against the current chat's room
   (resolved lazily via `room-thunk`, a 0-arg fn returning the Room, called only
   when the command is room-scoped) and return the reply string; else nil."
  [text room-thunk]
  (when-let [{:keys [cmd argv]} (parse text)]
    (when-let [{:keys [op room? args-fn reply]} (by-cmd cmd)]
      (try
        (let [room   (when room? (room-thunk))
              args   (cond-> {}
                       room?   (assoc :room (:slug room))
                       args-fn (merge (args-fn argv)))
              result (ops/invoke (current-daemon) op args)]
          ((or reply str) result))
        (catch Throwable e
          (str "⚠ /" cmd " failed: " (.getMessage e)))))))
