(ns dvergr.web.chat
  "Web-based chat interface for dvergr agents.

   Routes (mounted in web.server):
     GET  /chat/:agent-id             — chat page with message history + input
     POST /chat/:agent-id/send        — submit a message to the agent
     GET  /api/chat/:agent-id/messages — HTMX-polled message fragment

   Uses cookie-based sessions (dvergr-web-id) to identify browser users.
   Keyword chat-ids like :web-<uuid>-<agent> bypass the Telegram allowlist."
  (:require [dvergr.web.layout :as layout]
            [dvergr.registry :as registry]
            [dvergr.sessions :as sessions]
            [dvergr.agent.process :as agent]
            [dvergr.chat.context :as chat-ctx]
            [hiccup2.core :as h]
            [hiccup.util :as hu]
            [clojure.string :as str]
            [org.replikativ.spindel.engine.core :as rtc]))

;; ============================================================================
;; CSS
;; ============================================================================

(def ^:private css
  ".chat-container {
     display: flex;
     flex-direction: column;
     height: calc(100vh - 180px);
     min-height: 400px;
   }
   .chat-header {
     display: flex;
     align-items: center;
     gap: 10px;
     margin-bottom: 12px;
   }
   .chat-agent-name {
     font-size: 1.3em;
     font-weight: 700;
     color: #e0e0e0;
   }
   .chat-status {
     display: inline-block;
     padding: 1px 8px;
     border-radius: 8px;
     font-size: 0.72em;
     font-weight: 600;
     text-transform: uppercase;
   }
   .chat-status-running { background: #1e3a1e; color: #52b788; }
   .chat-status-stopped { background: #3a1e1e; color: #f38ba8; }
   .messages-panel {
     flex: 1;
     overflow-y: auto;
     background: #12122a;
     border: 1px solid #2a2a4a;
     border-radius: 8px;
     padding: 12px 16px;
     margin-bottom: 12px;
   }
   .msg-row {
     padding: 6px 0;
     border-bottom: 1px solid #1a1a2e;
   }
   .msg-row:last-child { border-bottom: none; }
   .msg-role {
     font-weight: 600;
     font-size: 0.88em;
   }
   .msg-role-user { color: #a78bfa; }
   .msg-role-assistant { color: #52b788; }
   .msg-content {
     color: #ccc;
     margin-top: 2px;
     white-space: pre-wrap;
     word-break: break-word;
     font-size: 0.92em;
   }
   .msg-time {
     color: #555;
     font-size: 0.72em;
     margin-left: 8px;
   }
   .thinking {
     color: #667eea;
     font-size: 0.85em;
     padding: 8px 0;
   }
   .thinking::after {
     content: '...';
     animation: dots 1.5s steps(4, end) infinite;
   }
   @keyframes dots {
     0%, 20% { content: ''; }
     40% { content: '.'; }
     60% { content: '..'; }
     80%, 100% { content: '...'; }
   }
   .chat-input-form {
     display: flex;
     gap: 8px;
   }
   .chat-input {
     flex: 1;
     background: #1a1a2e;
     border: 1px solid #2a2a4a;
     border-radius: 6px;
     color: #ccc;
     padding: 8px 12px;
     font-size: 0.92em;
     font-family: inherit;
     outline: none;
     resize: none;
   }
   .chat-input:focus { border-color: #667eea; }
   .btn-send {
     padding: 8px 20px;
     border-radius: 6px;
     background: #667eea;
     color: #fff;
     border: none;
     font-size: 0.88em;
     font-weight: 500;
     cursor: pointer;
     transition: background 0.2s;
   }
   .btn-send:hover { background: #7b8ff0; }
   .empty-chat { color: #555; text-align: center; padding: 3em 0; }")

;; ============================================================================
;; Cookie Helpers
;; ============================================================================

(defn- get-web-id
  "Parse dvergr-web-id from Cookie header, or nil."
  [req]
  (when-let [cookie-str (get-in req [:headers "cookie"])]
    (some (fn [part]
            (let [trimmed (str/trim part)]
              (when (str/starts-with? trimmed "dvergr-web-id=")
                (subs trimmed (count "dvergr-web-id=")))))
          (str/split cookie-str #";"))))

(defn- make-chat-id
  "Build a keyword chat-id from web-id and agent-id string."
  [web-id agent-id-str]
  (keyword (str "web-" web-id "-" agent-id-str)))

(defn- set-cookie-header
  "Return Set-Cookie header value for a new web-id."
  [web-id]
  (str "dvergr-web-id=" web-id "; HttpOnly; SameSite=Lax; Max-Age=31536000; Path=/"))

;; ============================================================================
;; Time Formatting
;; ============================================================================

(defn- fmt-time [d]
  (when d
    (let [s (str d)]
      (if (>= (count s) 16) (subs s 11 16) s))))

;; ============================================================================
;; Message Rendering (HTML fragment)
;; ============================================================================

(defn- render-messages
  "Render messages as an HTML fragment string for the messages panel."
  [messages last-is-user?]
  (str
    (h/html
      {:escape-strings? false}
      (if (empty? messages)
        [:div.empty-chat "Send a message to start chatting."]
        (list
          (map (fn [m]
                 (let [role (or (:role m) (:message/role m))
                       content (or (:content m) (:message/content m) "")
                       created (or (:created-at m) (:message/created-at m))]
                   (when (and (#{:user :assistant} role)
                              (not (str/blank? content)))
                     [:div.msg-row
                      [:span {:class (str "msg-role msg-role-" (name role))}
                       (if (= :user role) "you" "agent")]
                      [:span.msg-time (fmt-time created)]
                      [:div.msg-content (hu/escape-html content)]])))
               messages)
          (when last-is-user?
            [:div.thinking "Thinking"]))))
    ;; Auto-scroll script
    "<script>document.querySelector('.messages-panel').scrollTop=document.querySelector('.messages-panel').scrollHeight;</script>"))

;; ============================================================================
;; Chat Page (GET /chat/:agent-id)
;; ============================================================================

(defn chat-page
  "Render the full chat page for an agent."
  [req daemon agent-id-str]
  (let [agent-kw (keyword agent-id-str)
        agent-info (some #(when (= (:id %) agent-kw) %) (registry/list-agents))]
    (if-not agent-info
      {:status 404
       :headers {"Content-Type" "text/html; charset=utf-8"}
       :body (layout/page-chrome
               {:title "Not Found" :active-page :agents}
               [:div.not-found
                [:p (str "No agent \"" (hu/escape-html agent-id-str) "\"")]
                [:p [:a {:href "/agents"} "back to Agents"]]])}
      (let [existing-web-id (get-web-id req)
            web-id (or existing-web-id (str (java.util.UUID/randomUUID)))
            new-cookie? (nil? existing-web-id)
            kw-chat-id (make-chat-id web-id agent-id-str)
            user-info {:username "web-user" :first_name "Web"}
            ;; Get or create session within execution context
            session (binding [rtc/*execution-context* (:execution-ctx daemon)]
                      (sessions/get-or-create-session! kw-chat-id agent-kw user-info))
            messages (chat-ctx/get-messages (:chat-ctx session))
            visible (filter (fn [m]
                              (let [role (or (:role m) (:message/role m))]
                                (#{:user :assistant} role)))
                            messages)
            last-role (or (:role (last visible)) (:message/role (last visible)))
            status (name (or (:status agent-info) :unknown))
            body (layout/page-chrome
                   {:title (str "Chat — " agent-id-str) :active-page :agents :extra-css css}
                   [:div.chat-container
                    [:div.chat-header
                     [:span.chat-agent-name (hu/escape-html agent-id-str)]
                     [:span {:class (str "chat-status chat-status-" status)} status]]
                    [:div.messages-panel
                     {:id "messages"
                      :hx-get (str "/api/chat/" agent-id-str "/messages")
                      :hx-trigger "every 2s"
                      :hx-swap "innerHTML"}
                     (hu/raw-string (render-messages visible (= :user last-role)))]
                    [:form.chat-input-form
                     {:method "POST" :action (str "/chat/" agent-id-str "/send")}
                     [:input.chat-input
                      {:type "text" :name "message" :autocomplete "off"
                       :placeholder (str "Message " agent-id-str "...")
                       :autofocus true}]
                     [:button.btn-send {:type "submit"} "Send"]]])]
        (cond-> {:status 200
                 :headers {"Content-Type" "text/html; charset=utf-8"}
                 :body body}
          new-cookie? (assoc-in [:headers "Set-Cookie"] (set-cookie-header web-id)))))))

;; ============================================================================
;; Send Handler (POST /chat/:agent-id/send)
;; ============================================================================

(defn handle-send
  "Process a chat message submission."
  [req daemon agent-id-str]
  (let [agent-kw (keyword agent-id-str)
        ;; Parse form body
        body-str (slurp (:body req))
        params (into {}
                     (map (fn [pair]
                            (let [[k v] (str/split pair #"=" 2)]
                              [(keyword k) (java.net.URLDecoder/decode (or v "") "UTF-8")]))
                          (str/split body-str #"&")))
        text (str/trim (or (:message params) ""))
        web-id (get-web-id req)]
    (when (and (not (str/blank? text)) web-id)
      (let [kw-chat-id (make-chat-id web-id agent-id-str)
            user-info {:username "web-user" :first_name "Web"}
            session (binding [rtc/*execution-context* (:execution-ctx daemon)]
                      (sessions/get-or-create-session! kw-chat-id agent-kw user-info))]
        (when-let [ag (registry/get-agent agent-kw)]
          (binding [rtc/*execution-context* (:execution-ctx daemon)]
            (agent/send! ag {:content text
                             :chat-id kw-chat-id
                             :user-info user-info
                             :chat-ctx (:chat-ctx session)})))))
    {:status 303
     :headers {"Location" (str "/chat/" agent-id-str)}
     :body ""}))

;; ============================================================================
;; Messages API (GET /api/chat/:agent-id/messages)
;; ============================================================================

(defn api-chat-messages
  "Return HTML fragment of chat messages for HTMX polling."
  [req daemon agent-id-str]
  (let [web-id (get-web-id req)
        agent-kw (keyword agent-id-str)]
    (if-not web-id
      {:status 200
       :headers {"Content-Type" "text/html; charset=utf-8"}
       :body "<div class=\"empty-chat\">No session. Reload the page.</div>"}
      (let [kw-chat-id (make-chat-id web-id agent-id-str)
            session (sessions/get-session kw-chat-id agent-kw)]
        (if-not session
          {:status 200
           :headers {"Content-Type" "text/html; charset=utf-8"}
           :body "<div class=\"empty-chat\">Send a message to start chatting.</div>"}
          (let [messages (chat-ctx/get-messages (:chat-ctx session))
                visible (filter (fn [m]
                                  (let [role (or (:role m) (:message/role m))]
                                    (#{:user :assistant} role)))
                                messages)
                last-role (or (:role (last visible)) (:message/role (last visible)))]
            {:status 200
             :headers {"Content-Type" "text/html; charset=utf-8"}
             :body (render-messages visible (= :user last-role))}))))))
