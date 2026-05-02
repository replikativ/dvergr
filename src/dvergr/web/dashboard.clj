(ns dvergr.web.dashboard
  "Dashboard page using hiccup with HTMX for live updates.

   Single-page dashboard showing:
   - Active agents and their status
   - Agent UI links
   - Active schedules
   - System health"
  (:require [hiccup2.core :as h]
            [hiccup.util :as hu]))

(def ^:private css
  "body {
     font-family: 'Fira Sans', -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
     background: #0f0f23;
     color: #ccc;
     margin: 0;
     padding: 20px;
     line-height: 1.6;
   }
   h1 {
     background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
     -webkit-background-clip: text;
     -webkit-text-fill-color: transparent;
     font-size: 2em;
     margin-bottom: 0.5em;
   }
   h2 {
     color: #999;
     font-size: 1.1em;
     text-transform: uppercase;
     letter-spacing: 0.1em;
     border-bottom: 1px solid #333;
     padding-bottom: 0.3em;
     margin-top: 1.5em;
   }
   .container { max-width: 900px; margin: 0 auto; }
   .agent-card, .schedule-card {
     background: #1a1a2e;
     border: 1px solid #333;
     border-radius: 8px;
     padding: 12px 16px;
     margin: 8px 0;
     transition: border-color 0.2s;
   }
   .agent-card:hover, .schedule-card:hover {
     border-color: #667eea;
   }
   .agent-card strong, .schedule-card strong { color: #e0e0e0; }
   .agent-card small { color: #888; }
   .agent-desc { display: block; color: #888; margin: 2px 0 6px; }
   .agent-header { margin-bottom: 2px; }
   .agent-stats { font-size: 0.82em; color: #666; margin-top: 4px; }
   .stat-cost { color: #a3c9a8; font-weight: 500; }
   .stat-age { color: #888; }
   .stat-sep { color: #444; }
   .stat-summary { color: #777; font-style: italic; }
   .agent-card a {
     color: #667eea;
     text-decoration: none;
     font-weight: 500;
     margin-left: 8px;
   }
   .agent-card a:hover { text-decoration: underline; }
   .status-badge {
     display: inline-block;
     padding: 2px 8px;
     border-radius: 12px;
     font-size: 0.8em;
     font-weight: 500;
   }
   .status-running { background: #1b4332; color: #52b788; }
   .status-registered { background: #2d2d00; color: #c9b300; }
   .status-stopped { background: #3d0000; color: #ff6b6b; }
   .health-indicator {
     display: inline-block;
     width: 8px; height: 8px;
     border-radius: 50%;
     background: #52b788;
     margin-right: 6px;
     animation: pulse 2s infinite;
   }
   @keyframes pulse {
     0%, 100% { opacity: 1; }
     50% { opacity: 0.5; }
   }
   .footer {
     margin-top: 2em;
     color: #555;
     font-size: 0.85em;
     text-align: center;
   }")

(defn dashboard-page
  "Render the dashboard HTML page as a string."
  []
  (str
    (h/html
      [:html
       [:head
        [:meta {:charset "utf-8"}]
        [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
        [:title "dvergr"]
        [:script {:src "https://unpkg.com/htmx.org@2.0.4"
                  :crossorigin "anonymous"}]
        [:style (hu/raw-string css)]]
       [:body
        [:div.container
         [:h1 [:span.health-indicator] "dvergr"
              [:a {:href "/wiki"
                   :style "font-size:0.45em;font-weight:400;margin-left:16px;color:#667eea;vertical-align:middle;text-decoration:none;"}
               "wiki"]
              [:a {:href "/rooms"
                   :style "font-size:0.45em;font-weight:400;margin-left:16px;color:#667eea;vertical-align:middle;text-decoration:none;"}
               "rooms"]
              [:a {:href "/proposals"
                   :style "font-size:0.45em;font-weight:400;margin-left:16px;color:#667eea;vertical-align:middle;text-decoration:none;"}
               "proposals"]]

         [:h2 "Agents"]
         [:div#agents
          {:hx-get "/api/agents"
           :hx-trigger "load, every 5s"
           :hx-headers "{\"Accept\": \"text/html\"}"
           :hx-swap "innerHTML"}
          [:p "Loading agents..."]]

         [:h2 "Rooms"]
         [:div#rooms
          {:hx-get "/api/rooms"
           :hx-trigger "load, every 10s"
           :hx-headers "{\"Accept\": \"text/html\"}"
           :hx-swap "innerHTML"}
          [:p "Loading rooms..."]]

         [:h2 "Schedules"]
         [:div#schedules
          {:hx-get "/api/schedules"
           :hx-trigger "load, every 10s"
           :hx-headers "{\"Accept\": \"text/html\"}"
           :hx-swap "innerHTML"}
          [:p "Loading schedules..."]]

         [:div.footer
          "dvergr agent runtime"]]]])))
