(ns dvergr.web.rooms
  "Web pages for browsing chat rooms.

   Routes (mounted in web.server):
     GET /rooms          — list all rooms
     GET /rooms/:slug    — view room message history
     GET /api/rooms      — HTML fragment for dashboard HTMX polling"
  (:require [hiccup2.core :as h]
            [hiccup.util :as hu]
            [dvergr.rooms :as rooms]
            [clojure.string :as str]
            [dvergr.web.layout :as layout]
            [taoensso.telemere :as tel]))

;; ============================================================================
;; State
;; ============================================================================

(defonce ^:private conn-a (atom nil))

(defn init!
  "Store the shared Datahike connection for room queries. Call once at startup."
  [datahike-conn]
  (reset! conn-a datahike-conn))

;; ============================================================================
;; Helpers
;; ============================================================================

(defn- fmt-date [d]
  (when d (subs (str d) 0 16)))

(defn- fmt-time [d]
  (when d
    (let [s (str d)]
      (if (>= (count s) 16)
        (subs s 11 16)
        s))))

(defn- type-badge [room-type]
  (case room-type
    :telegram-mirror [:span {:style "background:#1e2a3a;color:#89b4fa;padding:1px 7px;border-radius:8px;font-size:0.75em;margin-left:8px;"} "telegram"]
    :internal [:span {:style "background:#1e3a1e;color:#52b788;padding:1px 7px;border-radius:8px;font-size:0.75em;margin-left:8px;"} "internal"]
    nil))

;; ============================================================================
;; CSS (matching wiki dark theme)
;; ============================================================================

(def ^:private css
  ".room-card {
     background: #1a1a2e;
     border: 1px solid #2a2a4a;
     border-radius: 8px;
     padding: 10px 16px;
     margin: 6px 0;
     transition: border-color 0.2s;
   }
   .room-card:hover { border-color: #667eea; }
   .room-title { font-size: 1em; font-weight: 600; color: #e0e0e0; }
   .room-preview { color: #888; font-size: 0.85em; margin-top: 4px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; max-width: 700px; }
   .room-meta { font-size: 0.78em; color: #555; margin-top: 3px; }
   .msg-row { padding: 6px 0; border-bottom: 1px solid #1a1a2e; }
   .msg-row:last-child { border-bottom: none; }
   .msg-user { font-weight: 600; color: #a78bfa; font-size: 0.88em; }
   .msg-user.assistant { color: #52b788; }
   .msg-time { color: #555; font-size: 0.75em; margin-left: 8px; }
   .msg-content { color: #ccc; margin-top: 2px; white-space: pre-wrap; word-break: break-word; }
   .messages-container {
     background: #12122a;
     border: 1px solid #2a2a4a;
     border-radius: 8px;
     padding: 12px 16px;
     max-height: 70vh;
     overflow-y: auto;
   }
   .breadcrumb { color: #555; margin-bottom: 1em; font-size: 0.88em; }
   .breadcrumb a { color: #667eea; }")

;; ============================================================================
;; Page Chrome
;; ============================================================================

(defn- page-chrome [title-str & content]
  (apply layout/page-chrome
         {:title title-str :active-page :rooms :extra-css css :htmx? false}
         content))

;; ============================================================================
;; List Page
;; ============================================================================

(defn rooms-list-page []
  (let [conn @conn-a
        all-rooms (if conn (rooms/list-rooms conn) [])
        n (count all-rooms)]
    (page-chrome "Rooms"
      [:h1 "Rooms"]
      [:div.count-line (str n (if (= n 1) " room" " rooms"))]
      (if (seq all-rooms)
        (let [grouped (group-by :room/type all-rooms)]
          (mapcat
            (fn [[room-type rooms-in-group]]
              (cons
                [:h2 (name (or room-type :unknown))]
                (map (fn [room]
                       (let [slug (:room/slug room)
                             msgs (when conn
                                    (try (rooms/get-messages conn (:chat/id room) :limit 1)
                                         (catch Exception _ nil)))
                             last-msg (last msgs)]
                         [:div.room-card
                          [:div
                           [:a.room-title {:href (str "/rooms/" (hu/escape-html slug))}
                            (hu/escape-html (or (:chat/title room) slug))]
                           (type-badge room-type)]
                          (when last-msg
                            [:div.room-preview
                             (str (or (:message/source-user last-msg) "")
                                  ": "
                                  (let [c (or (:message/content last-msg) "")]
                                    (if (> (count c) 120) (str (subs c 0 120) "...") c)))])
                          [:div.room-meta (fmt-date (:chat/updated-at room))]]))
                     rooms-in-group)))
            grouped))
        [:p {:style "color:#555;"} "No rooms yet."]))))

;; ============================================================================
;; Detail Page
;; ============================================================================

(defn room-detail-page [slug]
  (let [conn @conn-a
        room (when conn (rooms/get-room-by-slug conn slug))]
    (if room
      (let [msgs (rooms/get-messages conn (:chat/id room) :limit 200)]
        (page-chrome (or (:chat/title room) slug)
          [:div.breadcrumb
           [:a {:href "/rooms"} "Rooms"] " / " (hu/escape-html (or (:chat/title room) slug))]
          [:div
           [:span {:style "font-size:1.3em;font-weight:700;color:#e0e0e0;"}
            (hu/escape-html (or (:chat/title room) slug))]
           (type-badge (:room/type room))]
          [:div.count-line (str (count msgs) " messages")]
          (if (seq msgs)
            [:div.messages-container
             (map (fn [m]
                    (let [role (or (:message/role m) :user)
                          user (or (:message/source-user m) (name role))]
                      [:div.msg-row
                       [:span {:class (str "msg-user" (when (= role :assistant) " assistant"))}
                        (hu/escape-html user)]
                       [:span.msg-time (fmt-time (:message/created-at m))]
                       [:div.msg-content (hu/escape-html (or (:message/content m) ""))]]))
                  msgs)]
            [:p {:style "color:#555;"} "No messages yet."])))
      (page-chrome "Not found"
        [:div.breadcrumb
         [:a {:href "/rooms"} "Rooms"] " / " (hu/escape-html slug)]
        [:div.not-found
         [:p (str "No room \"" (hu/escape-html slug) "\"")]
         [:p [:a {:href "/rooms"} "back to Rooms"]]]))))

;; ============================================================================
;; Dashboard Fragment (HTMX)
;; ============================================================================

(defn api-rooms
  "Return HTML fragment for dashboard HTMX polling."
  [_req]
  (let [conn @conn-a
        all-rooms (if conn
                    (try (rooms/list-rooms conn) (catch Exception _ []))
                    [])]
    {:status 200
     :headers {"Content-Type" "text/html; charset=utf-8"}
     :body (str
             (h/html
               [:div
                (if (seq all-rooms)
                  (map (fn [room]
                         (let [slug (:room/slug room)]
                           [:div {:style "background:#1a1a2e;border:1px solid #333;border-radius:8px;padding:8px 14px;margin:6px 0;"}
                            [:a {:href (str "/rooms/" (hu/escape-html slug))
                                 :style "color:#e0e0e0;font-weight:500;"}
                             (hu/escape-html (or (:chat/title room) slug))]
                            (type-badge (:room/type room))
                            [:span {:style "float:right;color:#555;font-size:0.8em;"}
                             (fmt-date (:chat/updated-at room))]]))
                       all-rooms)
                  [:p {:style "color:#555;"} "No rooms."])]))}))
