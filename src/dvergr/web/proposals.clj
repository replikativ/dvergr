(ns dvergr.web.proposals
  "Web pages for viewing and managing merge proposals.

   Routes (mounted in web.server):
     GET  /proposals              — list all proposals
     GET  /proposals/:id          — view proposal detail
     POST /proposals/:id/accept   — accept proposal
     POST /proposals/:id/reject   — reject proposal"
  (:require [hiccup2.core :as h]
            [hiccup.util :as hu]
            [dvergr.agent.proposals :as proposals]
            [clojure.string :as str]
            [dvergr.web.layout :as layout]
            [taoensso.telemere :as tel]
            [yggdrasil.types])
  (:import [yggdrasil.types GitDiff DatahikeDiff DiffError]))

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
  (when d (subs (str d) 0 16)))

(defn- status-badge [status]
  (let [[bg fg] (case status
                  :pending  ["#1e2a3a" "#89b4fa"]
                  :accepted ["#1e3a1e" "#52b788"]
                  :rejected ["#3a1e1e" "#f38ba8"]
                  :failed   ["#3a2a1e" "#fab387"]
                  ["#2a2a2a" "#888"])]
    [:span {:style (str "background:" bg ";color:" fg
                        ";padding:1px 7px;border-radius:8px;font-size:0.75em;margin-left:8px;")}
     (name (or status :unknown))]))

(defn- truncate [s n]
  (if (and s (> (count s) n))
    (str (subs s 0 n) "...")
    s))

;; ============================================================================
;; CSS (matching wiki/rooms dark theme)
;; ============================================================================

(def ^:private css
  ".proposal-card {
     background: #1a1a2e;
     border: 1px solid #2a2a4a;
     border-radius: 8px;
     padding: 10px 16px;
     margin: 6px 0;
     transition: border-color 0.2s;
   }
   .proposal-card:hover { border-color: #667eea; }
   .proposal-title { font-size: 1em; font-weight: 600; color: #e0e0e0; }
   .proposal-summary { color: #888; font-size: 0.85em; margin-top: 4px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; max-width: 700px; }
   .proposal-meta { font-size: 0.78em; color: #555; margin-top: 3px; }
   .detail-section {
     background: #12122a;
     border: 1px solid #2a2a4a;
     border-radius: 8px;
     padding: 12px 16px;
     margin-top: 12px;
   }
   .detail-section h3 { color: #888; font-size: 0.85em; text-transform: uppercase; letter-spacing: 0.1em; margin: 0 0 8px 0; }
   .detail-content { color: #ccc; white-space: pre-wrap; word-break: break-word; font-size: 0.9em; }
   .actions { margin-top: 16px; }
   .btn {
     display: inline-block;
     padding: 6px 20px;
     border-radius: 6px;
     border: 1px solid #2a2a4a;
     color: #ccc;
     font-size: 0.88em;
     cursor: pointer;
     margin-right: 8px;
     text-decoration: none;
   }
   .btn-accept { background: #1e3a1e; border-color: #52b788; color: #52b788; }
   .btn-accept:hover { background: #2a4a2a; }
   .btn-reject { background: #3a1e1e; border-color: #f38ba8; color: #f38ba8; }
   .btn-reject:hover { background: #4a2a2a; }
   .breadcrumb { color: #555; margin-bottom: 1em; font-size: 0.88em; }
   .breadcrumb a { color: #667eea; }
   .flash { background: #1e3a1e; border: 1px solid #52b788; color: #52b788; padding: 8px 16px; border-radius: 8px; margin-bottom: 16px; }
   .flash-error { background: #3a1e1e; border-color: #f38ba8; color: #f38ba8; }
   .diff-block {
     background: #0d0d1a;
     border: 1px solid #222;
     border-radius: 6px;
     padding: 10px 14px;
     margin: 6px 0;
     font-family: 'JetBrains Mono', 'Fira Code', monospace;
     font-size: 0.82em;
     overflow-x: auto;
     white-space: pre;
     line-height: 1.5;
   }
   .diff-line-add { color: #52b788; }
   .diff-line-del { color: #f38ba8; }
   .diff-line-hdr { color: #667eea; font-weight: 600; }
   .diff-line-ctx { color: #777; }
   .datom-table { width: 100%; border-collapse: collapse; font-size: 0.85em; }
   .datom-table th { text-align: left; color: #667eea; padding: 4px 8px; border-bottom: 1px solid #2a2a4a; }
   .datom-table td { padding: 4px 8px; border-bottom: 1px solid #1a1a2e; color: #ccc; font-family: monospace; }
   .datom-added td:first-child::before { content: '+'; color: #52b788; margin-right: 6px; }
   .datom-removed td:first-child::before { content: '-'; color: #f38ba8; margin-right: 6px; }
   .sys-header { color: #888; font-size: 0.78em; text-transform: uppercase; letter-spacing: 0.08em; margin: 12px 0 4px 0; }")

;; ============================================================================
;; Diff Rendering Protocol
;; ============================================================================

(defprotocol DiffRenderable
  "Polymorphic rendering of yggdrasil diff results to hiccup."
  (render-diff-html [this] "Return hiccup data structure for this diff."))

(extend-protocol DiffRenderable
  GitDiff
  (render-diff-html [diff]
    (let [patch (:patch diff)]
      (if (str/blank? patch)
        [:p {:style "color:#555;"} "No file changes."]
        [:div
         (when (seq (:files diff))
           [:div {:style "margin-bottom:8px;"}
            (for [{:keys [status path]} (:files diff)]
              [:div {:style "font-size:0.85em;"}
               [:span {:style (str "color:"
                                   (case status :added "#52b788" :deleted "#f38ba8" "#667eea")
                                   ";margin-right:6px;")}
                (case status :added "A" :deleted "D" :modified "M" (name status))]
               [:span {:style "color:#ccc;"} (hu/escape-html path)]])])
         [:div.diff-block
          (for [line (str/split-lines patch)]
            (let [cls (cond
                        (str/starts-with? line "+") "diff-line-add"
                        (str/starts-with? line "-") "diff-line-del"
                        (str/starts-with? line "@@") "diff-line-hdr"
                        :else "diff-line-ctx")]
              [:span {:class cls} (hu/escape-html line) "\n"]))]])))

  DatahikeDiff
  (render-diff-html [diff]
    (let [{:keys [added removed summary]} diff]
      (if (and (empty? added) (empty? removed))
        [:p {:style "color:#555;"} "No database changes."]
        [:div
         [:div {:style "color:#888;font-size:0.82em;margin-bottom:6px;"}
          (str (:added-datoms summary) " added, "
               (:removed-datoms summary) " removed, "
               (:entities-touched summary) " entities touched")]
         [:table.datom-table
          [:thead [:tr [:th "Op"] [:th "Entity"] [:th "Attribute"] [:th "Value"]]]
          [:tbody
           (for [[_op e a v] added]
             [:tr.datom-added
              [:td "+"] [:td (str e)] [:td (str a)] [:td (hu/escape-html (pr-str v))]])
           (for [[_op e a v] removed]
             [:tr.datom-removed
              [:td "-"] [:td (str e)] [:td (str a)] [:td (hu/escape-html (pr-str v))]])]]])))

  DiffError
  (render-diff-html [diff]
    [:p {:style "color:#f38ba8;"} (str "Diff error: " (hu/escape-html (:error diff)))]))

;; ============================================================================
;; Diff Computation
;; ============================================================================

(defn- compute-proposal-diffs
  "Compute diffs for a pending proposal from its cached child-ctx."
  [proposal-id]
  (when-let [result (proposals/get-cached-result proposal-id)]
    (when-let [child-ctx (:child-ctx result)]
      (try
        (require 'org.replikativ.spindel.yggdrasil)
        (let [context-diff-fn (ns-resolve 'org.replikativ.spindel.yggdrasil 'context-diff)]
          (context-diff-fn child-ctx))
        (catch Exception e
          (tel/log! {:id :proposal/diff-error :data {:error (.getMessage e)}}
                    "Failed to compute proposal diffs")
          nil)))))

(defn- render-diffs
  "Render all system diffs as hiccup."
  [diffs]
  (when (seq diffs)
    [:div.detail-section
     [:h3 "Changes"]
     (for [[sys-id {:keys [type child-branch parent-branch diff]}] diffs]
       [:div
        [:div.sys-header
         (str (name type) " — " sys-id
              " (" (name parent-branch) " → " (name child-branch) ")")]
        (render-diff-html diff)])]))

;; ============================================================================
;; Page Chrome
;; ============================================================================

(defn- page-chrome [title-str & content]
  (apply layout/page-chrome
         {:title title-str :active-page :proposals :extra-css css}
         content))

;; ============================================================================
;; List Page
;; ============================================================================

(defn proposals-list-page [& {:keys [flash flash-error]}]
  (let [conn @conn-a
        all-proposals (if conn (proposals/list-proposals conn) [])
        n (count all-proposals)]
    (page-chrome "Proposals"
      [:h1 "Proposals"]
      (when flash [:div.flash (hu/escape-html flash)])
      (when flash-error [:div.flash.flash-error (hu/escape-html flash-error)])
      [:div.count-line (str n (if (= n 1) " proposal" " proposals"))]
      (if (seq all-proposals)
        (map (fn [p]
               (let [pid (:proposal/id p)]
                 [:div.proposal-card
                  [:div
                   [:a.proposal-title {:href (str "/proposals/" pid)}
                    (hu/escape-html (or (truncate (:proposal/task p) 80) "Untitled"))]
                   (status-badge (:proposal/status p))]
                  [:div.proposal-summary
                   (hu/escape-html (truncate (:proposal/summary p) 120))]
                  [:div.proposal-meta
                   (str (when-let [a (:proposal/agent-id p)] (str (name a) " · "))
                        (fmt-date (:proposal/created-at p)))]]))
             all-proposals)
        [:p {:style "color:#555;"} "No proposals yet."]))))

;; ============================================================================
;; Detail Page
;; ============================================================================

(defn proposal-detail-page [proposal-id-str & {:keys [flash flash-error]}]
  (let [conn @conn-a
        pid (try (java.util.UUID/fromString proposal-id-str) (catch Exception _ nil))
        proposal (when (and conn pid) (proposals/get-proposal conn pid))]
    (if proposal
      (page-chrome (str "Proposal " (subs proposal-id-str 0 8))
        [:div.breadcrumb
         [:a {:href "/proposals"} "Proposals"] " / " (subs proposal-id-str 0 8)]
        (when flash [:div.flash (hu/escape-html flash)])
        (when flash-error [:div.flash.flash-error (hu/escape-html flash-error)])
        [:div
         [:span {:style "font-size:1.3em;font-weight:700;color:#e0e0e0;"}
          (hu/escape-html (or (truncate (:proposal/task proposal) 80) "Untitled"))]
         (status-badge (:proposal/status proposal))]
        [:div.proposal-meta
         (str "Agent: " (some-> (:proposal/agent-id proposal) name)
              " · Created: " (fmt-date (:proposal/created-at proposal))
              (when (:proposal/resolved-at proposal)
                (str " · Resolved: " (fmt-date (:proposal/resolved-at proposal)))))]

        ;; Task
        [:div.detail-section
         [:h3 "Task"]
         [:div.detail-content (hu/escape-html (or (:proposal/task proposal) ""))]]

        ;; Summary
        (when (:proposal/summary proposal)
          [:div.detail-section
           [:h3 "Summary"]
           [:div.detail-content (hu/escape-html (:proposal/summary proposal))]])

        ;; Diff (only for pending proposals with live context)
        (when (= :pending (:proposal/status proposal))
          (when-let [diffs (compute-proposal-diffs pid)]
            (render-diffs diffs)))

        ;; Test result
        (when (:proposal/test-result proposal)
          [:div.detail-section
           [:h3 "Test Result"]
           [:div.detail-content (hu/escape-html (:proposal/test-result proposal))]])

        ;; Actions (only for pending proposals)
        (when (= :pending (:proposal/status proposal))
          [:div.actions
           [:form {:method "POST" :action (str "/proposals/" proposal-id-str "/accept")
                   :style "display:inline;"}
            [:button.btn.btn-accept {:type "submit"} "Accept"]]
           [:form {:method "POST" :action (str "/proposals/" proposal-id-str "/reject")
                   :style "display:inline;"}
            [:button.btn.btn-reject {:type "submit"} "Reject"]]]))
      (page-chrome "Not found"
        [:div.breadcrumb
         [:a {:href "/proposals"} "Proposals"] " / " (hu/escape-html proposal-id-str)]
        [:div.not-found
         [:p (str "No proposal \"" (hu/escape-html proposal-id-str) "\"")]
         [:p [:a {:href "/proposals"} "back to Proposals"]]]))))

;; ============================================================================
;; Action Handlers
;; ============================================================================

(defn handle-accept [proposal-id-str]
  (let [conn @conn-a
        pid (try (java.util.UUID/fromString proposal-id-str) (catch Exception _ nil))]
    (if (and conn pid)
      (let [result (proposals/accept-proposal! conn pid)]
        (if (= :accepted result)
          {:status 303
           :headers {"Location" (str "/proposals/" proposal-id-str)}
           :body ""}
          {:status 303
           :headers {"Location" "/proposals"}
           :body ""}))
      {:status 303
       :headers {"Location" "/proposals"}
       :body ""})))

(defn handle-reject [proposal-id-str]
  (let [conn @conn-a
        pid (try (java.util.UUID/fromString proposal-id-str) (catch Exception _ nil))]
    (if (and conn pid)
      (let [result (proposals/reject-proposal! conn pid)]
        {:status 303
         :headers {"Location" (str "/proposals/" proposal-id-str)}
         :body ""})
      {:status 303
       :headers {"Location" "/proposals"}
       :body ""})))
