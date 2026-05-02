(ns dvergr.web.layout
  "Shared page chrome for all dvergr web pages.

   Provides a consistent nav bar, CSS base, and HTML wrapper so every page
   has the same look and navigation."
  (:require [hiccup2.core :as h]
            [hiccup.util :as hu]))

;; ============================================================================
;; Shared CSS
;; ============================================================================

(def shared-css
  "Base CSS shared across all dvergr web pages."
  "body {
     font-family: 'Fira Sans', -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
     background: #0f0f23;
     color: #ccc;
     margin: 0;
     padding: 20px;
     line-height: 1.6;
   }
   a { color: #667eea; text-decoration: none; }
   a:hover { text-decoration: underline; }
   h1 {
     background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
     -webkit-background-clip: text;
     -webkit-text-fill-color: transparent;
     font-size: 2em;
     margin-bottom: 0.3em;
   }
   h2 {
     color: #777;
     font-size: 0.85em;
     text-transform: uppercase;
     letter-spacing: 0.1em;
     border-bottom: 1px solid #222;
     padding-bottom: 0.3em;
     margin-top: 1.5em;
   }
   .container { max-width: 900px; margin: 0 auto; }
   .count-line { color: #555; font-size: 0.82em; margin-bottom: 0.5em; }
   .not-found { color: #666; padding: 3em 0; text-align: center; }
   .footer { margin-top: 2.5em; color: #444; font-size: 0.82em; text-align: center; }

   /* Navigation bar */
   .nav-bar {
     display: flex;
     align-items: center;
     gap: 6px;
     margin-bottom: 1.5em;
     padding-bottom: 0.8em;
     border-bottom: 1px solid #1a1a2e;
     flex-wrap: wrap;
   }
   .nav-brand {
     font-weight: 700;
     font-size: 1.1em;
     background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
     -webkit-background-clip: text;
     -webkit-text-fill-color: transparent;
     margin-right: 12px;
   }
   .nav-brand:hover { text-decoration: none; }
   .nav-link {
     display: inline-block;
     padding: 3px 12px;
     border-radius: 14px;
     font-size: 0.82em;
     color: #888;
     background: #1a1a2e;
     border: 1px solid transparent;
     transition: all 0.2s;
   }
   .nav-link:hover {
     color: #ccc;
     border-color: #667eea;
     text-decoration: none;
   }
   .nav-link.active {
     background: #1e1040;
     color: #a78bfa;
     border-color: #1e1040;
   }")

;; ============================================================================
;; Nav Bar
;; ============================================================================

(defn nav-bar
  "Horizontal navigation bar. `active-page` is a keyword like :feed, :agents, etc."
  [active-page]
  (let [links [{:page :feed          :href "/"              :label "Feed"}
               {:page :calendar      :href "/calendar"      :label "Calendar"}
               {:page :agents        :href "/agents"        :label "Agents"}
               {:page :conversations :href "/conversations" :label "Conversations"}
               {:page :wiki          :href "/wiki"          :label "Wiki"}
               {:page :rooms         :href "/rooms"         :label "Rooms"}
               {:page :proposals     :href "/proposals"     :label "Proposals"}
               {:page :entities      :href "/entities"      :label "Entities"}
               {:page :search        :href "/search"        :label "Search"}]]
    [:nav.nav-bar
     [:a.nav-brand {:href "/"} "dvergr"]
     (map (fn [{:keys [page href label]}]
            [:a {:href href
                 :class (str "nav-link" (when (= page active-page) " active"))}
             label])
          links)]))

;; ============================================================================
;; Page Chrome
;; ============================================================================

(defn page-chrome
  "Full HTML page wrapper with nav bar, shared CSS, and optional extras.

   opts map:
     :title       - page title string
     :active-page - keyword for nav highlight (:feed, :agents, :wiki, etc.)
     :extra-css   - additional CSS string for page-specific styles
     :htmx?       - include HTMX script (default true)

   Remaining args are hiccup body content."
  [{:keys [title active-page extra-css htmx?]
    :or {htmx? true}}
   & content]
  (str
    (h/html
      {:escape-strings? false}
      [:html
       [:head
        [:meta {:charset "utf-8"}]
        [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
        [:title (str (hu/escape-html (or title "dvergr")) " — dvergr")]
        [:style (hu/raw-string shared-css)]
        (when extra-css
          [:style (hu/raw-string extra-css)])
        (when htmx?
          [:script {:src "https://unpkg.com/htmx.org@2.0.4"
                    :crossorigin "anonymous"}])]
       [:body
        [:div.container
         (nav-bar active-page)
         content
         [:div.footer "dvergr"]]]])))
