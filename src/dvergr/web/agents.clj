(ns dvergr.web.agents
  "Agent surface: the roster fragment (rendered into the dashboard's Agents
   section) and the per-agent configuration page. The human surface over
   `dvergr.agent.ops` — the same shared layer the TUI drives.

   Clicking an agent opens its CONFIG (not a chat); chat is one click further
   (the agent's DM room at `/agents/<id>/open`).

   Routes (in web.server):
     GET  /api/agents            — roster fragment (HTMX, into dashboard #agents)
     GET  /agents/:id/config     — config + persona edit page
     POST /agents/:id/config     — save edits
     POST /agents/new            — create an agent
     GET  /agents/:id/delete     — delete an agent"
  (:require [clojure.string :as str]
            [hiccup2.core :as h]
            [hiccup.util :as hu]
            [dvergr.web.dashboard :as dash]
            [dvergr.agent.ops :as ops]
            [dvergr.agent.fields :as fields]
            [dvergr.orchestration.stats :as stats]))

;; ============================================================================
;; Config-page CSS (the form; card/roster styles live in the shared dashboard css)
;; ============================================================================

(def ^:private config-css
  ".cfg-form { background:#141417; border:1px solid #26262b; border-radius:8px;
               padding:18px 20px; margin:12px 0; }
   .cfg-form label { display:block; color:#9a9a9a; font-size:0.8em;
                     text-transform:uppercase; letter-spacing:0.05em;
                     margin:14px 0 4px; }
   .cfg-form input[type=text], .cfg-form input[type=number], .cfg-form select,
   .cfg-form textarea {
     width:100%; box-sizing:border-box; background:#0f0f12; color:#e0e0e0;
     border:1px solid #2a2a30; border-radius:6px; padding:8px 10px;
     font-size:0.9em; font-family:inherit;
   }
   .cfg-form textarea { min-height:320px; font-family:'Fira Mono',monospace;
                        font-size:0.85em; line-height:1.5; resize:vertical; }
   .cfg-form .row { display:flex; gap:16px; }
   .cfg-form .row > div { flex:1; }
   .cfg-actions { margin-top:18px; display:flex; gap:10px; align-items:center; }
   .persona-src { color:#666; font-size:0.78em; margin-left:auto; }")

;; ============================================================================
;; Roster — the shared agent card + the dashboard fragment
;; ============================================================================

(defn- status-class [status]
  (str "status-badge status-" (name (or status :unknown))))

(defn- agent-card
  "One roster card. Primary action Configure (matches the TUI's Enter→config);
   Chat is secondary."
  [a]
  (let [id-str (name (:id a))]
    [:div.agent-card
     [:div.agent-actions
      [:a.btn {:href (str "/agents/" id-str "/config")} "Configure"]
      [:a.btn {:href (str "/agents/" id-str "/open")} "Chat"]]
     [:div.agent-header
      [:span.agent-name [:a {:href (str "/agents/" id-str "/config")} id-str]]
      (when (:online? a) [:span.live-dot "● live"])
      [:span {:class (status-class (:status a))} (name (or (:status a) :unknown))]]
     (when (seq (:description a))
       [:span.agent-desc (hu/escape-html (:description a))])
     [:div.agent-meta
      [:span (or (:model a) "—")]
      (when (:provider a) [:span [:span.sep "·"] (name (:provider a))])
      (when (seq (:tags a))
        [:span [:span.sep "·"] (str/join " " (map #(str "#" (name %)) (:tags a)))])
      [:span.sep "·"]
      [:span (str "persona: " (name (:persona-source a)))]]]))

(defn agents-fragment
  "HTML string of the agent roster — rendered into the dashboard's #agents
   section (HTMX). ctx-bound by the caller (the daemon's execution context)."
  []
  (let [agents (ops/list-agents)]
    (str
     (h/html
      [:div#agent-roster
       (if (seq agents)
         (map agent-card agents)
         [:p {:style "color:#555;"} "No agents yet — create one above."])]))))

;; ============================================================================
;; Config / edit page
;; ============================================================================

;; The config form is DERIVED from `dvergr.agent.fields` — labels, widget types,
;; option sources and seed values all come from the shared spec. Adding a field
;; there makes it appear here (and in the TUI) with no edit to this file.

(defn- field-input
  "Widget for field `f` seeded from agent `a`, per its :type."
  [f a]
  (let [nm  (fields/param-name f)
        val ((:format f) a)]
    (case (:type f)
      :select   [:select {:name nm}
                 (cons [:option {:value "" :selected (str/blank? val)} "— pick a model —"]
                       (for [[g opts] (sort-by (comp str key) (group-by :group ((:options f) a)))]
                         [:optgroup {:label (name g)}
                          (for [o opts]
                            [:option {:value (:value o) :selected (= (str (:value o)) val)}
                             (:label o)])]))]
      :textarea [:textarea {:name nm} val]
      [:input (cond-> {:type (if (= :number (:type f)) "number" "text") :name nm :value val}
                (:placeholder f)      (assoc :placeholder (:placeholder f))
                (= :number (:type f)) (assoc :step "0.01" :min "0"))])))

(defn- row-groups
  "Consecutive fields sharing a non-nil :row become one side-by-side group;
   every other field is its own full-width group."
  [fs]
  (reduce (fn [acc f]
            (let [last (peek acc)]
              (if (and (:row f) (= (:row f) (:row (first last))))
                (conj (pop acc) (conj last f))
                (conj acc [f]))))
          [] fs))

(defn- config-form-body [a]
  (for [group (row-groups fields/fields)]
    (if (> (count group) 1)
      [:div.row (for [f group] [:div [:label (:label f)] (field-input f a)])]
      (let [f (first group)] (list [:label (:label f)] (field-input f a))))))

(defn agent-config-page [id-str]
  (let [id (keyword id-str)
        a  (ops/get-agent id)]
    (if-not a
      (dash/shell
       {:title id-str :extra-css config-css}
       [:h1 [:a {:href "/dashboard"
                 :style "color:#52b788;text-decoration:none;font-size:0.6em;margin-right:12px;"} "←"]
        "Agent not found"]
       [:p {:style "color:#888;"} (str "No agent '" id-str "'. ")
        [:a {:href "/dashboard"} "Back to dashboard"]])
      (let [st (stats/get-stats id)]
        (dash/shell
         {:title (str "configure " id-str) :extra-css config-css}
         [:h1 [:a {:href "/dashboard"
                   :style "color:#52b788;text-decoration:none;font-size:0.6em;margin-right:12px;"} "←"]
          (hu/escape-html (or (:name a) id-str))
          [:span {:class (status-class (:status a))
                  :style "font-size:0.45em;margin-left:12px;vertical-align:middle;"}
           (name (or (:status a) :unknown))]
          [:span.agent-actions
           [:a.btn {:href (str "/agents/" id-str "/open")} "Chat"]
           [:a.btn.btn-danger
            {:href (str "/agents/" id-str "/delete")
             :onclick (str "return confirm('Delete agent " id-str "? This removes its row and project persona.');")}
            "Delete"]]]
         [:div {:style "color:#666;font-size:0.85em;margin-bottom:8px;"}
          (str "id " id-str
               (when (:cost-dollars st) (format " · $%.3f spent" (double (:cost-dollars st))))
               (when (:last-active-str st) (str " · last active " (:last-active-str st))))]
         [:form.cfg-form {:method "post" :action (str "/agents/" id-str "/config")}
          (config-form-body a)
          [:div.cfg-actions
           [:button.btn {:type "submit"} "Save"]
           [:a.btn {:href "/dashboard"} "Back"]
           [:span.persona-src (str "persona source: " (name (:persona-source a)))]]])))))
