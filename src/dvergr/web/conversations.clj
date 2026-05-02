(ns dvergr.web.conversations
  "Generic conversation viewer for all chat contexts.

   Shows message history for any chat-id — agent sweeps, web sessions,
   Telegram rooms, sub-chats. Linked from wiki backlinks and the nav bar.

   Routes (mounted in web.server):
     GET /conversations           — list all chats
     GET /conversations/:chat-id  — view message history, ?at=ms for scroll anchor"
  (:require [hiccup2.core :as h]
            [hiccup.util :as hu]
            [datahike.api :as dh]
            [clojure.string :as str]
            [dvergr.web.layout :as layout]
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
;; Helpers
;; ============================================================================

(defn- fmt-date [d]
  (when d (subs (str d) 0 10)))

(defn- fmt-time [d]
  (when d
    (let [s (str d)]
      (if (>= (count s) 16)
        (subs s 11 16)
        s))))

(defn- fmt-datetime [d]
  (when d
    (let [s (str d)]
      (if (>= (count s) 19)
        (subs s 0 19)
        s))))

(defn- extract-agent-name
  "Extract agent name from chat title like 'huginn-1771786801167'."
  [chat-title]
  (when chat-title
    (cond
      (str/starts-with? chat-title "eval-")
      (second (str/split chat-title #"-"))
      :else
      (first (str/split chat-title #"[-\s]")))))

(defn- classify-chat
  "Classify a chat entity into a type for display."
  [chat]
  (cond
    (:room/slug chat)           :room
    (str/starts-with? (or (:chat/title chat) "") "var") :var
    (str/starts-with? (or (:chat/title chat) "") "web-")      :web
    :else                       :agent))

(defn- type-label [chat-type]
  (case chat-type
    :room       "room"
    :var        "var"
    :web        "web"
    :agent      "agent"))

(defn- truncate [s n]
  (if (and s (> (count s) n))
    (str (subs s 0 n) "...")
    s))

;; ============================================================================
;; Queries
;; ============================================================================

(defn- all-chats []
  (when-let [conn @conn-a]
    (try
      (->> (dh/q '[:find [(pull ?c [:chat/id :chat/title :chat/status
                                     :chat/created-at :chat/updated-at
                                     :room/slug :room/type]) ...]
                   :where [?c :chat/id _]]
                 @conn)
           (sort-by #(- (.getTime (or (:chat/updated-at %) (java.util.Date. 0))))))
      (catch Exception e
        (tel/log! {:level :warn :id :conversations/list-error
                   :data {:error (.getMessage e)}}
                  "Conversation list query failed")
        nil))))

(defn- get-chat [chat-id-str]
  (when-let [conn @conn-a]
    (try
      (let [chat-uuid (java.util.UUID/fromString chat-id-str)]
        (dh/q '[:find (pull ?c [*]) .
                :in $ ?cid
                :where [?c :chat/id ?cid]]
              @conn chat-uuid))
      (catch Exception _ nil))))

(defn- get-messages [chat-id-str]
  (when-let [conn @conn-a]
    (try
      (let [chat-uuid (java.util.UUID/fromString chat-id-str)]
        (->> (dh/q '[:find [(pull ?m [:message/id :message/role :message/content
                                      :message/created-at :message/source-user
                                      :message/tool-use-id
                                      {:message/tool-uses [:tool-use/id :tool-use/name]}]) ...]
                     :in $ ?cid
                     :where
                     [?c :chat/id ?cid]
                     [?m :message/chat ?c]]
                   @conn chat-uuid)
             (sort-by #(.getTime (or (:message/created-at %) (java.util.Date. 0))))
             vec))
      (catch Exception _ nil))))

(defn- message-count [conn chat-uuid]
  (try
    (or (dh/q '[:find (count ?m) .
                :in $ ?cid
                :where
                [?c :chat/id ?cid]
                [?m :message/chat ?c]]
              @conn chat-uuid)
        0)
    (catch Exception _ 0)))

;; ============================================================================
;; CSS
;; ============================================================================

(def ^:private css
  ".chat-card {
     background: #1a1a2e;
     border: 1px solid #2a2a4a;
     border-radius: 8px;
     padding: 10px 16px;
     margin: 6px 0;
     transition: border-color 0.2s;
   }
   .chat-card:hover { border-color: #667eea; }
   .chat-title-link { font-size: 1em; font-weight: 600; color: #e0e0e0; }
   .chat-meta { font-size: 0.78em; color: #555; margin-top: 3px; }
   .type-badge {
     display: inline-block;
     padding: 1px 7px;
     border-radius: 8px;
     font-size: 0.72em;
     margin-left: 8px;
     vertical-align: middle;
   }
   .type-room      { background: #1e2a3a; color: #89b4fa; }
   .type-agent     { background: #1e3a1e; color: #52b788; }
   .type-var       { background: #3a1e3a; color: #e0a0e0; }
   .type-web       { background: #2a2a2a; color: #aaa; }
   .msg-count-badge {
     display: inline-block;
     background: #1e1040;
     color: #a78bfa;
     padding: 1px 7px;
     border-radius: 8px;
     font-size: 0.72em;
     margin-left: 8px;
     vertical-align: middle;
   }

   .breadcrumb { color: #555; margin-bottom: 1em; font-size: 0.88em; }
   .breadcrumb a { color: #667eea; }
   .page-title { font-size: 1.5em; font-weight: 700; color: #e0e0e0; }

   .messages-container {
     background: #12122a;
     border: 1px solid #2a2a4a;
     border-radius: 8px;
     padding: 12px 16px;
     max-height: 75vh;
     overflow-y: auto;
   }
   .msg-row {
     padding: 6px 0;
     border-bottom: 1px solid #1a1a2e;
     scroll-margin-top: 40px;
   }
   .msg-row:last-child { border-bottom: none; }
   .msg-row.highlight {
     background: #1e1040;
     border-left: 3px solid #a78bfa;
     padding-left: 10px;
     margin-left: -13px;
     border-radius: 0 4px 4px 0;
   }
   .msg-user { font-weight: 600; color: #a78bfa; font-size: 0.88em; }
   .msg-user.role-assistant { color: #52b788; }
   .msg-user.role-system { color: #555; }
   .msg-user.role-tool-result { color: #c9d15c; }
   .msg-time { color: #555; font-size: 0.75em; margin-left: 8px; }
   .msg-content {
     color: #ccc;
     margin-top: 2px;
     white-space: pre-wrap;
     word-break: break-word;
     font-size: 0.92em;
   }
   .msg-tools { margin-top: 2px; }
   .tool-badge {
     display: inline-block;
     background: #1a2a1a;
     color: #c9d15c;
     padding: 1px 6px;
     border-radius: 6px;
     font-size: 0.72em;
     margin-right: 4px;
   }
   details { margin-top: 4px; }
   details summary { cursor: pointer; color: #667eea; font-size: 0.8em; }
   details pre {
     background: #0f0f23;
     border: 1px solid #2a2a4a;
     border-radius: 6px;
     padding: 8px 12px;
     color: #aaa;
     font-size: 0.82em;
     white-space: pre-wrap;
     word-break: break-word;
     max-height: 300px;
     overflow-y: auto;
   }
   .filter-bar { margin: 0.8em 0 1.2em; display: flex; gap: 6px; align-items: center; flex-wrap: wrap; }
   .filter-bar .label { color: #555; font-size: 0.82em; margin-right: 4px; }
   .filter-btn {
     display: inline-block;
     padding: 3px 12px;
     border-radius: 14px;
     font-size: 0.8em;
     background: #1a1a2e;
     border: 1px solid #2a2a4a;
     color: #aaa;
     transition: border-color 0.2s, background 0.2s;
   }
   .filter-btn:hover { border-color: #667eea; text-decoration: none; }
   .filter-btn.active { background: #667eea; color: #fff; border-color: #667eea; }
   .filter-sep { color: #333; margin: 0 4px; }")

;; ============================================================================
;; Page Chrome
;; ============================================================================

(defn- page-chrome [title-str & content]
  (apply layout/page-chrome
         {:title title-str :active-page :conversations :extra-css css :htmx? false}
         content))

;; ============================================================================
;; List Page
;; ============================================================================

(defn- parse-query-string [qs]
  (when (and qs (not (str/blank? qs)))
    (into {}
          (keep (fn [pair]
                  (let [[k v] (str/split pair #"=" 2)]
                    (when (and k v)
                      [(keyword k) v]))))
          (str/split qs #"&"))))

(defn conversations-list-page [query-string]
  (let [conn    @conn-a
        params  (parse-query-string query-string)
        agent-filter (:agent params)
        type-filter  (some-> (:type params) keyword)
        chats   (or (all-chats) [])
        ;; Classify each chat
        classified (map (fn [c] (assoc c ::type (classify-chat c)
                                         ::agent (extract-agent-name (:chat/title c))))
                        chats)
        ;; Collect known agents for filter bar
        agents (->> classified (keep ::agent) distinct sort vec)
        ;; Apply filters
        filtered (cond->> classified
                   agent-filter (filter #(= agent-filter (::agent %)))
                   type-filter  (filter #(= type-filter (::type %))))
        n (count filtered)]
    (page-chrome "Conversations"
      [:h1 "Conversations"]
      ;; Filter bar
      [:div.filter-bar
       [:span.label "Agent:"]
       [:a {:href "/conversations"
            :class (str "filter-btn" (when-not agent-filter " active"))} "all"]
       (map (fn [a]
              [:a {:href (str "/conversations?agent=" a
                              (when type-filter (str "&type=" (name type-filter))))
                   :class (str "filter-btn" (when (= a agent-filter) " active"))}
               a])
            agents)
       [:span.filter-sep "|"]
       [:span.label "Type:"]
       [:a {:href (str "/conversations?"
                       (when agent-filter (str "agent=" agent-filter)))
            :class (str "filter-btn" (when-not type-filter " active"))} "all"]
       (map (fn [t]
              [:a {:href (str "/conversations?type=" (name t)
                              (when agent-filter (str "&agent=" agent-filter)))
                   :class (str "filter-btn" (when (= t type-filter) " active"))}
               (name t)])
            [:agent :room :var :web])]
      [:div.count-line (str n " conversation" (when (not= n 1) "s"))]
      (if (seq filtered)
        (map (fn [c]
               (let [chat-id  (str (:chat/id c))
                     title    (or (:chat/title c) chat-id)
                     ct       (::type c)
                     msg-n    (when conn (message-count conn (:chat/id c)))]
                 [:div.chat-card
                  [:div
                   [:a.chat-title-link {:href (str "/conversations/" chat-id)}
                    (hu/escape-html (truncate title 80))]
                   [:span {:class (str "type-badge type-" (name ct))}
                    (type-label ct)]
                   (when (and msg-n (pos? msg-n))
                     [:span.msg-count-badge (str msg-n " msgs")])]
                  [:div.chat-meta
                   (when-let [d (:chat/updated-at c)]
                     [:span (str "updated " (fmt-date d))])
                   (when-let [d (:chat/created-at c)]
                     [:span {:style "margin-left:12px;"} (str "created " (fmt-date d))])]]))
             filtered)
        [:p {:style "color:#555;"} "No conversations found."]))))

;; ============================================================================
;; Detail Page
;; ============================================================================

(defn conversation-detail-page [chat-id-str query-string]
  (let [params     (parse-query-string query-string)
        at-ms      (try (some-> (:at params) parse-long) (catch Exception _ nil))
        chat       (get-chat chat-id-str)]
    (if chat
      (let [title    (or (:chat/title chat) chat-id-str)
            msgs     (or (get-messages chat-id-str) [])
            agent-name (extract-agent-name title)]
        (page-chrome title
          [:div.breadcrumb
           [:a {:href "/conversations"} "Conversations"] " / "
           (hu/escape-html (truncate title 60))]
          [:div
           [:span.page-title (hu/escape-html title)]
           (when agent-name
             [:span {:class (str "type-badge type-agent")
                     :style "margin-left:10px;"} agent-name])
           (when (:room/slug chat)
             [:span {:class "type-badge type-room"
                     :style "margin-left:10px;"} "room"])]
          [:div.chat-meta {:style "margin-top:6px;"}
           [:span (str (count msgs) " messages")]
           (when-let [d (:chat/created-at chat)]
             [:span {:style "margin-left:12px;"} (str "created " (fmt-date d))])
           (when-let [d (:chat/updated-at chat)]
             [:span {:style "margin-left:12px;"} (str "updated " (fmt-date d))])
           (when-let [s (:chat/status chat)]
             [:span {:style "margin-left:12px;"} (str "status: " (name s))])]
          (when-let [summary (:chat/summary chat)]
            [:div {:style "margin:0.8em 0;color:#bbb;font-size:0.92em;"}
             (hu/escape-html summary)])
          (if (seq msgs)
            (list
              [:div.messages-container
               (map-indexed
                 (fn [_i m]
                   (let [role       (or (:message/role m) :user)
                         user       (or (:message/source-user m) (name role))
                         content    (or (:message/content m) "")
                         tool-uses  (:message/tool-uses m)
                         created    (:message/created-at m)
                         msg-ms     (when created (.getTime created))
                         ;; Highlight if within 2 seconds of the at-ms target
                         highlight? (and at-ms msg-ms
                                         (<= (Math/abs (- msg-ms at-ms)) 2000))
                         msg-id     (str "msg-" msg-ms)]
                     [:div {:class (str "msg-row" (when highlight? " highlight"))
                            :id msg-id}
                      [:span {:class (str "msg-user role-" (name role))}
                       (hu/escape-html user)]
                      [:span.msg-time (fmt-datetime created)]
                      (when (seq tool-uses)
                        [:div.msg-tools
                         (map (fn [tu]
                                [:span.tool-badge (:tool-use/name tu)])
                              tool-uses)])
                      (when (and (not (str/blank? content))
                                 (not= role :system))
                        (if (> (count content) 500)
                          [:details
                           [:summary (str (hu/escape-html (truncate content 200)))]
                           [:pre (hu/escape-html content)]]
                          [:div.msg-content (hu/escape-html content)]))]))
                 msgs)]
              ;; Auto-scroll to highlighted message
              (when at-ms
                [:script (hu/raw-string
                           (str "document.addEventListener('DOMContentLoaded',function(){"
                                "var el=document.querySelector('.msg-row.highlight');"
                                "if(el)el.scrollIntoView({behavior:'smooth',block:'center'});"
                                "});"))]))
            [:p {:style "color:#555;"} "No messages in this conversation."])))
      (page-chrome "Not found"
        [:div.breadcrumb
         [:a {:href "/conversations"} "Conversations"] " / " (hu/escape-html chat-id-str)]
        [:div.not-found
         [:p (str "No conversation with id \"" (hu/escape-html chat-id-str) "\"")]
         [:p [:a {:href "/conversations"} "back to Conversations"]]]))))
