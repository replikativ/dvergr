(ns dvergr.web.agents
  "Agent list page with status, stats, and chat links.

   Routes (mounted in web.server):
     GET /agents  — list all registered agents with chat buttons"
  (:require [dvergr.web.layout :as layout]
            [dvergr.registry :as registry]
            [dvergr.stats :as stats]
            [hiccup.util :as hu]
            [clojure.string :as str]))

;; ============================================================================
;; CSS
;; ============================================================================

(def ^:private css
  ".agent-card {
     background: #1a1a2e;
     border: 1px solid #2a2a4a;
     border-radius: 8px;
     padding: 12px 16px;
     margin: 8px 0;
     transition: border-color 0.2s;
   }
   .agent-card:hover { border-color: #667eea; }
   .agent-header {
     display: flex;
     align-items: center;
     gap: 8px;
     flex-wrap: wrap;
   }
   .agent-name {
     font-size: 1.05em;
     font-weight: 600;
     color: #e0e0e0;
   }
   .status-badge {
     display: inline-block;
     padding: 1px 8px;
     border-radius: 8px;
     font-size: 0.72em;
     font-weight: 600;
     text-transform: uppercase;
   }
   .status-running { background: #1e3a1e; color: #52b788; }
   .status-registered { background: #1e2a3a; color: #89b4fa; }
   .status-stopped { background: #3a1e1e; color: #f38ba8; }
   .status-unknown { background: #2a2a2a; color: #888; }
   .agent-desc { color: #888; font-size: 0.88em; margin-top: 4px; display: block; }
   .agent-stats {
     display: flex;
     gap: 8px;
     align-items: center;
     margin-top: 6px;
     font-size: 0.82em;
     color: #555;
   }
   .agent-stats .stat-sep { color: #333; }
   .agent-summary {
     color: #777;
     font-size: 0.82em;
     margin-top: 4px;
     white-space: nowrap;
     overflow: hidden;
     text-overflow: ellipsis;
     max-width: 700px;
   }
   .agent-actions { margin-left: auto; }
   .btn-chat {
     display: inline-block;
     padding: 4px 16px;
     border-radius: 6px;
     background: #1e1040;
     color: #a78bfa;
     border: 1px solid #2a2a4a;
     font-size: 0.82em;
     font-weight: 500;
     transition: all 0.2s;
   }
   .btn-chat:hover {
     background: #2a1850;
     border-color: #a78bfa;
     text-decoration: none;
   }")

;; ============================================================================
;; Page
;; ============================================================================

(defn agents-list-page []
  (let [agents (registry/list-agents)
        n (count agents)]
    (layout/page-chrome
      {:title "Agents" :active-page :agents :extra-css css :htmx? false}
      [:h1 "Agents"]
      [:div.count-line (str n (if (= n 1) " agent" " agents"))]
      (if (seq agents)
        (map (fn [agent-info]
               (let [st (stats/get-stats (:id agent-info))
                     id-str (name (:id agent-info))
                     status (name (or (:status agent-info) :unknown))
                     cost-str (if (:cost-dollars st)
                                (format "$%.3f" (double (:cost-dollars st)))
                                "--")]
                 [:div.agent-card
                  [:div.agent-header
                   [:span.agent-name (hu/escape-html id-str)]
                   [:span {:class (str "status-badge status-" status)} status]
                   [:div.agent-actions
                    [:a.btn-chat {:href (str "/chat/" id-str)} "Chat"]]]
                  (when (seq (:description agent-info))
                    [:span.agent-desc (hu/escape-html (:description agent-info))])
                  [:div.agent-stats
                   [:span cost-str]
                   [:span.stat-sep "\u00b7"]
                   [:span (or (:last-active-str st) "--")]]
                  (when (:summary st)
                    [:div.agent-summary (hu/escape-html (:summary st))])]))
             agents)
        [:p {:style "color:#555;"} "No agents registered."]))))
