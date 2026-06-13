(ns dvergr.web.dashboard
  "Dashboard page using hiccup with HTMX for live updates.

   Single-page dashboard showing:
   - The room/agent/fork tree (same signal source as the TUI)
   - Active agents and their status
   - Active schedules
   - System health"
  (:require [hiccup2.core :as h]
            [hiccup.util :as hu]
            [clojure.string :as str]
            [org.replikativ.spindel.engine.core :as ec]
            [nextjournal.markdown :as md]
            [nextjournal.markdown.transform :as mdt]
            [dvergr.rooms.tree :as rooms-tree]
            [dvergr.rooms.stats :as rstats]))

(def ^:private css
  "body {
     font-family: 'Fira Sans', -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
     background: #0d0d0d;
     color: #ccc;
     margin: 0;
     padding: 20px;
     line-height: 1.6;
   }
   h1 {
     color: #52b788;
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
     background: #18181b;
     border: 1px solid #333;
     border-radius: 8px;
     padding: 12px 16px;
     margin: 8px 0;
     transition: border-color 0.2s;
   }
   .agent-card:hover, .schedule-card:hover {
     border-color: #52b788;
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
     color: #52b788;
     text-decoration: none;
     font-weight: 500;
     margin-left: 8px;
   }
   .agent-card a:hover { text-decoration: underline; }
   /* agent roster cards (shared by the dashboard Agents section + config page) */
   .agent-name { font-size:1.02em; font-weight:600; color:#e0e0e0; }
   .agent-name a { color:#e0e0e0; margin-left:0; font-weight:600; }
   .agent-name a:hover { color:#52b788; }
   .agent-actions { float:right; display:flex; gap:6px; }
   .live-dot { color:#52b788; font-size:0.7em; margin:0 4px; }
   .agent-meta { color:#777; font-size:0.82em; margin-top:4px; }
   .agent-meta .sep { color:#333; padding:0 6px; }
   .status-online { background:#1b4332; color:#52b788; }
   .status-offline { background:#2a2a2a; color:#888; }
   .status-retired { background:#3d0000; color:#f38ba8; }
   .status-unknown { background:#2a2a2a; color:#888; }
   .btn {
     display:inline-block; padding:4px 14px; border-radius:6px;
     background:#1a1a1d; color:#52b788; border:1px solid #2a2a30;
     font-size:0.82em; font-weight:500; margin-left:0;
   }
   .btn:hover { background:#26262b; border-color:#52b788; text-decoration:none; }
   .btn-danger { color:#f38ba8; }
   .btn-danger:hover { border-color:#f38ba8; }
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
   }
   .tree-node { line-height: 1.4; }
   .tree-node .label { color: #e0e0e0; }
   .tree-node .agents-count { color: #888; font-size: 0.85em; }
   .tree-node .fork-line {
     color: #c9b300;
     font-size: 0.9em;
     font-family: ui-monospace, SFMono-Regular, monospace;
   }
   .tree-node .agent-line { color: #b0b0b0; }
   .tree-node .agent-line .agent-status-running { color: #52b788; }
   .tree-node .agent-line .agent-status-stopped { color: #ff6b6b; }
   .tree-node .agent-line .agent-status-registered { color: #c9b300; }
   .tree-empty { color: #555; font-style: italic; }
   .tree-node .room-stats { color: #666; font-size: 0.82em; }
   .tree-node .room-stats .stat-cost { color: #8fae93; }
   .stats-strip {
     display: flex; flex-wrap: wrap; align-items: center;
     font-size: 0.84em; color: #888; margin: 6px 0 14px;
   }
   .stats-strip .stat { padding: 0 12px; border-right: 1px solid #2a2a2a; }
   .stats-strip .stat:first-child { padding-left: 0; }
   .stats-strip .stat:last-child { border-right: none; }
   .stats-strip .stat-cost { color: #a3c9a8; font-weight: 600; }
   .stats-strip .on { color: #52b788; }
   .stats-strip .off { color: #666; }
   .sys-panel {
     display: flex; flex-wrap: wrap;
     background: #18181b; border: 1px solid #333; border-radius: 8px;
     padding: 10px 4px;
   }
   .sys-panel .sys-stat {
     flex: 1; text-align: center; padding: 4px 12px;
     border-right: 1px solid #2a2a2a; min-width: 90px;
   }
   .sys-panel .sys-stat:last-child { border-right: none; }
   .sys-panel .sys-num { display: block; font-size: 1.5em; font-weight: 600; color: #e0e0e0; }
   .sys-panel .sys-num.cost { color: #a3c9a8; }
   .sys-panel .sys-label {
     font-size: 0.72em; text-transform: uppercase;
     letter-spacing: 0.08em; color: #777;
   }
   .ctx { margin: 2px 0 12px; }
   .ctx-bar {
     position: relative; height: 8px; background: #1a1a1d;
     border: 1px solid #2a2a2a; border-radius: 4px;
   }
   .ctx-fill { height: 100%; background: #52b788; border-radius: 4px; }
   .ctx-fill.warn { background: #c9b300; }
   .ctx-thresh { position: absolute; top: -1px; bottom: -1px; width: 2px; background: rgba(255,255,255,0.4); }
   .ctx-label { font-size: 0.78em; color: #888; }
   .tree-node .room-stats .ctx-mini { color: #8fae93; }
   .fork-act { margin-left: 8px; font-size: 0.8em; color: #52b788; text-decoration: none; }
   .fork-act:hover { text-decoration: underline; }
   .fork-act.danger { color: #f38ba8; }
   .fork-banner {
     background: #18181b; border: 1px solid #333; border-left: 3px solid #c9b300;
     border-radius: 6px; padding: 10px 14px; margin: 10px 0;
     font-size: 0.85em; color: #bbb;
   }
   .fork-banner .fb-head { color: #c9b300; font-weight: 600; margin-bottom: 6px; }
   .fork-banner pre {
     background: #0d0d0d; border: 1px solid #2a2a2a; border-radius: 4px;
     padding: 8px; overflow-x: auto; font-size: 0.82em; color: #9aa; margin: 6px 0 0;
   }
   .fork-banner a.btn-merge, .fork-banner a.btn-discard {
     display: inline-block; margin: 8px 8px 0 0; padding: 4px 14px;
     border-radius: 6px; text-decoration: none; font-size: 0.9em;
   }
   .fork-banner a.btn-merge { background: #1b4332; color: #52b788; border: 1px solid #2a2a2a; }
   .fork-banner a.btn-discard { background: #3d0000; color: #f38ba8; border: 1px solid #2a2a2a; }")

;; =============================================================================
;; Shared page chrome — ONE shell for every dvergr web page
;; =============================================================================
;; The single source of page chrome (this css + HTMX + a .container). Every
;; page — dashboard, room, agent-config — renders through it, so the web has one
;; consistent look (replaces the old dvergr.web.layout nav-bar, which pointed at
;; long-deleted feed/wiki/entities/… pages). Nav is intentionally minimal: the
;; dashboard at `/` IS the index (rooms + agents + schedules), and sub-pages link
;; back to it via their own header.

(defn shell
  "Wrap hiccup `body` in the shared <html> chrome. Opts: :title (appended to the
   page title) and :extra-css (page-specific styles, e.g. the config form)."
  [{:keys [title extra-css]} & body]
  (str
   (h/html
    [:html
     [:head
      [:meta {:charset "utf-8"}]
      [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
      [:title (str "dvergr" (when title (str " · " title)))]
      [:script {:src "https://unpkg.com/htmx.org@2.0.4" :crossorigin "anonymous"}]
      [:style (hu/raw-string css)]
      (when extra-css [:style (hu/raw-string extra-css)])]
     [:body [:div.container body]]])))

;; =============================================================================
;; Tree rendering — same data source as the TUI tree view
;; =============================================================================

(defn- agent-line-hiccup
  [{:keys [id status description]}]
  [:div.agent-line
   {:style "padding-left:1.2em;"}
   [:span {:class (str "agent-status-" (name (or status :registered)))}
    (case status
      :running    "●"
      :stopped    "○"
      :registered "·"
      "·")]
   " "
   [:strong (str id)]
   (when (seq description)
     [:span {:style "color:#888;"} "  " description])])

(declare node-hiccup)

(defn- fork-detail-for-slug
  "forks/detail for a tree node by slug. Call with ctx bound (the fragment is)."
  [slug]
  (try
    (when-let [room ((requiring-resolve 'dvergr.room.registry/lookup)
                     ((requiring-resolve 'dvergr.room.store/slug->room-id) slug))]
      ((requiring-resolve 'dvergr.rooms.forks/detail) room))
    (catch Throwable _ nil)))

(defn- fork-node-hiccup
  [{:keys [slug title children] :as _node} agents-by-id]
  (let [fd (fork-detail-for-slug slug)]
    [:details
     {:open true :class "tree-node" :style "margin-left:0.5em;"}
     [:summary
      [:span.fork-line "⎘ "
       [:a {:href (str "/rooms/" slug) :style "color:#c9b300;text-decoration:none;"}
        [:strong (or title slug)]]]
      (when fd
        [:span.room-stats
         " · " (name (:isolation fd))
         (when (pos? (:commits fd))
           (str " · " (:commits fd) " commit" (when (not= 1 (:commits fd)) "s")))
         (when (seq (:files fd)) (str " · " (count (:files fd)) " files"))
         " · " (:msgs-since fd) " new"])
      [:a.fork-act {:href (str "/api/rooms/" slug "/merge")
                    :onclick "return confirm('Merge this fork into its parent?');"}
       "merge"]
      [:a.fork-act.danger {:href (str "/api/rooms/" slug "/discard")
                           :onclick "return confirm('Discard this fork? This deletes its branch.');"}
       "discard"]]
     (when (seq children)
       [:div {:style "padding-left:1em; border-left:1px dotted #333; margin-left:0.5em;"}
        (map (fn [c] (node-hiccup c agents-by-id)) children)])]))

(defn- room-stats-for-slug
  "room-stats for a tree node, by slug. Call with ctx bound (the fragment does)."
  [slug]
  (try
    (when-let [room ((requiring-resolve 'dvergr.room.registry/lookup)
                     ((requiring-resolve 'dvergr.room.store/slug->room-id) slug))]
      (rstats/room-stats room))
    (catch Throwable _ nil)))

(defn- room-node-hiccup
  [{:keys [slug title agent-ids children] :as _node} agents-by-id]
  (let [room-agents (->> agent-ids (keep agents-by-id) (sort-by :id))
        agent-count (count room-agents)
        st          (room-stats-for-slug slug)]
    [:details
     {:open true :class "tree-node" :style "margin-left:0.5em;"}
     [:summary
      [:span.label "🏛 "
       [:a {:href (str "/rooms/" slug)
            :style "color:#e0e0e0;text-decoration:none;"}
        [:strong (or title slug)]]]
      (when (pos? agent-count)
        [:span.agents-count " · " agent-count " agent" (when (not= 1 agent-count) "s")])
      (when st
        [:span.room-stats " · " (:message-count st) " msgs"
         " · " [:span.stat-cost (rstats/fmt-cost (:cost-dollars st))]
         (when-let [c (:context st)]
           [:span.ctx-mini (str " · " (min 100 (Math/round (* 100.0 (double (or (:pct c) 0))))) "% ctx"
                                (when (:should-compact? c) " ⚠"))])
         (when (:last-active-str st) (str " · " (:last-active-str st)))
         (when (pos? (:fork-count st 0))
           (str " · " (:fork-count st) " fork" (when (not= 1 (:fork-count st)) "s")))])
      [:a.fork-act {:href (str "/api/rooms/" slug "/fork")
                    :onclick "return confirm('Fork this room into an isolated branch?');"}
       "fork"]
      " "
      [:a {:href (str "/api/rooms/" slug "/delete")
           :style "color:#777;font-size:0.8em;margin-left:8px;text-decoration:none;"
           :onclick "return confirm('Delete this room?');"}
       "×"]]
     (when (seq room-agents)
       [:div (map agent-line-hiccup room-agents)])
     (when (seq children)
       [:div
        {:style "padding-left:1em; border-left:1px dotted #333; margin-left:0.5em;"}
        (map (fn [c] (node-hiccup c agents-by-id)) children)])]))

(defn- node-hiccup
  "Render a tree node — :room or :fork — recursively."
  [node agents-by-id]
  (case (:kind node)
    :fork (fork-node-hiccup node agents-by-id)
    (room-node-hiccup node agents-by-id)))

(defn- ensure-tree-booted!
  "Lazy-boot dvergr.rooms.tree if it hasn't been started yet. The TUI's
   `run` boots it eagerly; the web endpoint may be hit without a TUI
   running, so do it on demand. The tree now reads from the unified
   room registry, no datahike conn argument needed."
  [base-ctx]
  (binding [ec/*execution-context* base-ctx]
    (when-not (rooms-tree/tree-signal)
      (rooms-tree/start! {:ctx base-ctx :poll-ms 5000}))))

(defn rooms-tree-fragment
  "HTMX-friendly hiccup fragment of the current tree value. Self-contained
   block intended to be swapped into the #rooms div."
  [base-ctx]
  (ensure-tree-booted! base-ctx)
  (binding [ec/*execution-context* base-ctx]
    (let [tree-value   (rooms-tree/current-tree)
          roots        (:roots tree-value)
          agents       (:agents tree-value)
          agents-by-id (into {} (map (juxt :id identity)) agents)]
      (str
       (h/html
        [:div
         (if (empty? roots)
           [:p.tree-empty "No rooms yet."]
           (map (fn [r] (node-hiccup r agents-by-id)) roots))])))))

;; =============================================================================
;; Stats — one shared source (dvergr.rooms.stats) the TUI renders too
;; =============================================================================

(defn- stats-strip-hiccup
  "Render a room-stats map as a horizontal strip — the SAME tokens the TUI
   shows (dvergr.rooms.stats/strip-parts), each in its own `.stat` cell."
  [st]
  [:div.stats-strip
   (map (fn [part]
          [:span {:class (str "stat" (when (str/starts-with? part "$") " stat-cost"))}
           part])
        (rstats/strip-parts st))])

(defn room-stats-fragment
  "HTMX fragment: the stats strip for one room (polled live). The context
   window is shown per-agent in the expandable Context panel below, not as a
   single room-wide bar here."
  [base-ctx slug]
  (binding [ec/*execution-context* base-ctx]
    (if-let [room ((requiring-resolve 'dvergr.room.registry/lookup)
                   ((requiring-resolve 'dvergr.room.store/slug->room-id) slug))]
      (let [st (rstats/room-stats room)]
        (str (h/html
              [:div (stats-strip-hiccup st)])))
      "")))

(defn- agent-context-row
  "One per-AGENT context bar: name (model) · its cost on the right, then the
   same fill bar + token label as the room summary, then a compact sandbox line
   (defined vars + namespace count — `sb` from dvergr.sandbox/sandbox-status).
   Drives the expandable per-agent Context panel."
  [{:keys [agent model pct tokens limit compact-pct should-compact? cost-dollars]} sb]
  (let [pp (min 100 (Math/round (* 100.0 (double (or pct 0)))))
        cp (min 100 (Math/round (* 100.0 (double (or compact-pct 0.7)))))]
    [:div.ctx-agent
     [:div.ctx-agent-head
      [:span.ctx-agent-name (name agent)]
      (when model [:span.ctx-agent-model (rstats/short-model model)])
      [:span.ctx-agent-cost (rstats/fmt-cost cost-dollars)]]
     [:div.ctx-bar
      [:div.ctx-fill {:class (when should-compact? "warn") :style (str "width:" pp "%;")}]
      [:div.ctx-thresh {:style (str "left:" cp "%;") :title "auto-compaction"}]]
     [:span.ctx-label
      pp "% · " (rstats/fmt-tokens tokens) "/" (rstats/fmt-tokens limit)
      (when should-compact? " · ⚠ compaction")]
     (when sb
       [:div.ctx-agent-sandbox
        {:title (str "/sandbox " (name agent) " — its SCI session")}
        "🧰 "
        (if (seq (:vars sb))
          (str (count (:vars sb)) " var" (when (not= 1 (count (:vars sb))) "s")
               ": " (str/join ", " (:vars sb)))
          "no vars yet")
        " · " (:ns-count sb) " ns"])]))

(defn room-agents-context-fragment
  "HTMX fragment: one context bar PER active agent in the room (polled live),
   each with a compact sandbox summary. Body of the expandable Context panel."
  [base-ctx slug]
  (binding [ec/*execution-context* base-ctx]
    (if-let [room ((requiring-resolve 'dvergr.room.registry/lookup)
                   ((requiring-resolve 'dvergr.room.store/slug->room-id) slug))]
      (let [rid    (:id room)
            cs     (rstats/room-agent-contexts room)
            lookup (requiring-resolve 'dvergr.agent.room-context/lookup)
            sbstat (requiring-resolve 'dvergr.sandbox/sandbox-status)
            sb-of  (fn [aid] (try (sbstat (:sci-ctx (lookup rid aid))) (catch Throwable _ nil)))]
        (str (h/html
              (if (seq cs)
                (into [:div.ctx-agents] (map #(agent-context-row % (sb-of (:agent %))) cs))
                [:div.ctx-empty "No active agent context yet — agents appear here after their first turn."]))))
      "")))

(defn system-stats-fragment
  "HTMX fragment: the whole-system rollup panel (polled live on the dashboard)."
  [base-ctx]
  (binding [ec/*execution-context* base-ctx]
    (let [{:keys [room-count message-count agents-online cost-dollars]} (rstats/system-stats)]
      (str
       (h/html
        [:div.sys-panel
         [:div.sys-stat [:span.sys-num room-count] [:span.sys-label "rooms"]]
         [:div.sys-stat [:span.sys-num message-count] [:span.sys-label "messages"]]
         [:div.sys-stat [:span.sys-num agents-online] [:span.sys-label "agents online"]]
         [:div.sys-stat [:span.sys-num.cost (rstats/fmt-cost cost-dollars)]
          [:span.sys-label "spend"]]])))))

;; =============================================================================
;; Room page — chat-in-a-room
;; =============================================================================

;; --- Markdown → HTML -------------------------------------------------------
;; Reuse the SAME parser the TUI uses (nextjournal.markdown, already on the
;; classpath via spindel-tui) and render to hiccup — so message formatting is
;; consistent across surfaces and needs no extra dependency. Output is XSS-safe:
;; hiccup escapes all text, raw HTML nodes are rendered as literal text, and
;; link/image URLs are scheme-filtered (drops javascript:/data: etc.).

(defn- safe-href
  "Allow only http(s)/mailto/relative/anchor URLs; nil otherwise (so a
   javascript:/data: link renders as un-clickable text)."
  [u]
  (when (string? u)
    (let [t (str/trim u)]
      (when (or (re-matches #"(?i)https?://.*" t)
                (re-matches #"(?i)mailto:.*" t)
                (str/starts-with? t "/")
                (str/starts-with? t "#"))
        t))))

(def ^:private md-renderers
  (assoc mdt/default-hiccup-renderers
         ;; Untrusted inline/block HTML → its literal text (hiccup escapes it),
         ;; not the default "Unknown type" placeholder.
         :html-inline (fn [_ n] (mdt/->text n))
         :html-block  (fn [_ n] (mdt/->text n))
         :link  (fn [ctx n]
                  (mdt/into-markup
                   [:a {:href (safe-href (-> n :attrs :href))
                        :rel "noopener noreferrer" :target "_blank"}]
                   ctx n))
         ;; Don't load remote images from chat content; show a placeholder.
         :image (fn [_ n] [:span {:style "color:#888;"} "🖼 " (or (-> n :attrs :alt) "image")])))

(defn- md->hiccup
  "Render message markdown to hiccup, trimming the LLM's leading/trailing blank
   lines. Falls back to pre-wrapped plain text on any parse error."
  [content]
  (if-let [s (some-> content str/trim not-empty)]
    (try
      (mdt/->hiccup md-renderers (md/parse s))
      (catch Throwable _ [:div {:style "white-space:pre-wrap;word-break:break-word;"} s]))
    "(empty)"))

(defn- fmt-ts
  "Epoch-millis → \"HH:mm\", or nil — the per-message time shown in the header."
  [ts]
  (when ts
    (try (.format (java.text.SimpleDateFormat. "HH:mm") (java.util.Date. (long ts)))
         (catch Throwable _ nil))))

(defn- think-tool-details
  "Collapsed <details> for an agent message's reasoning trace (💭) and its tool
   calls WITH inputs (🔧 'what happened') — both default-collapsed, shown under
   the message body. Renders nothing when neither is present.

   `id` (the stable message id) makes each <details> `hx-preserve`able: the
   message list is HTMX-polled every few seconds and swaps innerHTML, which would
   otherwise recreate every <details> collapsed and snap an expanded one shut.
   `hx-preserve` keeps the user's open/closed state (and the static content)
   across swaps."
  [id reasoning tool-uses]
  (let [pres (fn [suffix] (when id {:id (str suffix "-" id) :hx-preserve "true"}))]
    (list
     (when (and reasoning (not (str/blank? (str reasoning))))
       [:details.msg-extra (pres "think")
        [:summary "💭 thinking"]
        [:pre.msg-extra-body (str reasoning)]])
     (when (seq tool-uses)
       [:details.msg-extra (pres "tools")
        [:summary "🔧 " (count tool-uses) " tool call" (when (not= 1 (count tool-uses)) "s")]
        (for [tu tool-uses]
          [:div.msg-extra-tool
           [:span.tool-name (str (or (:tool-use/name tu) (:name tu)))]
           [:pre.msg-extra-body (pr-str (or (:tool-use/input tu) (:input tu)))]])]))))

(defn- message-hiccup
  [{:keys [id from content role ts reasoning tool-uses]}]
  (let [name-str (or (some-> from name) "—")
        ;; Per-speaker colour from the SHARED role→colour table — same hues the
        ;; TUI uses (dvergr.rooms.theme); was hardcoded green for every post.
        color    ((requiring-resolve 'dvergr.rooms.theme/hex) role)]
    [:div {:style (str "margin:8px 0;padding:6px 10px;background:#18181b;"
                       "border-radius:6px;border-left:3px solid " color ";")}
     [:div {:style (str "display:flex;align-items:baseline;gap:8px;"
                        "color:" color ";font-weight:600;font-size:0.9em;margin-bottom:2px;")}
      [:span name-str]
      ;; Time dimmed + right-aligned (Telegram-style), per message.
      (when-let [t (fmt-ts ts)]
        [:span {:style "margin-left:auto;color:#666;font-weight:400;font-size:0.85em;"} t])]
     [:div.msg-body (md->hiccup content)]
     (think-tool-details id reasoning tool-uses)]))

(defn room-page
  "Full HTML page for a single Room: header, message list, send form."
  [base-ctx slug]
  (ensure-tree-booted! base-ctx)
  (binding [ec/*execution-context* base-ctx]
    (let [room ((requiring-resolve 'dvergr.room.registry/lookup)
                ((requiring-resolve 'dvergr.room.store/slug->room-id) slug))]
      (shell
       {:title slug
        :extra-css
        ".msg-list { max-height: 60vh; overflow-y: auto;
                      border:1px solid #333; padding:8px;
                      border-radius:8px; background:#0d0d0d; }
          .msg-body { color:#ddd; word-break:break-word; line-height:1.5; }
          .msg-body > :first-child { margin-top:0; }
          .msg-body > :last-child { margin-bottom:0; }
          .msg-body p { margin:0.4em 0; }
          .msg-body h1,.msg-body h2,.msg-body h3 { color:#e0e0e0; margin:0.6em 0 0.3em; line-height:1.2; }
          .msg-body h1 { font-size:1.25em; } .msg-body h2 { font-size:1.15em; } .msg-body h3 { font-size:1.05em; }
          .msg-body a { color:#52b788; }
          .msg-body code { background:#0d0d0d; border:1px solid #2a2a2a; border-radius:3px;
                           padding:0 4px; font-size:0.88em; color:#a3c9a8; }
          .msg-body pre { background:#0d0d0d; border:1px solid #2a2a2a; border-radius:6px;
                          padding:8px 10px; overflow-x:auto; margin:0.5em 0; }
          .msg-body pre code { background:none; border:none; padding:0; color:#cdd6cf; }
          .msg-body ul,.msg-body ol { margin:0.4em 0; padding-left:1.4em; }
          .msg-body li { margin:0.15em 0; }
          .msg-body blockquote { border-left:3px solid #333; margin:0.5em 0; padding:0.1em 0.8em; color:#aaa; }
          .msg-body table { border-collapse:collapse; margin:0.5em 0; }
          .msg-body th,.msg-body td { border:1px solid #2a2a2a; padding:3px 8px; }
          .msg-extra { margin-top:4px; }
          .msg-extra > summary { cursor:pointer; color:#7a8a7d; font-size:0.78em;
                                 user-select:none; list-style:none; }
          .msg-extra > summary::-webkit-details-marker { display:none; }
          .msg-extra > summary::before { content:'\\25B8  '; color:#555; }
          .msg-extra[open] > summary::before { content:'\\25BE  '; }
          .msg-extra-body { margin:3px 0 6px; padding:6px 8px; background:#0d0d0d;
                            border:1px solid #2a2a2a; border-radius:5px; color:#9aa;
                            font-size:0.8em; white-space:pre-wrap; word-break:break-word;
                            overflow-x:auto; }
          .msg-extra-tool .tool-name { color:#a3c9a8; font-size:0.8em; }
          .ctx-footer { margin:2px 0 14px; border:1px solid #2a2a2a; border-radius:8px;
                        background:#0d0d0d; }
          .ctx-footer > summary { cursor:pointer; padding:6px 12px; color:#9bb0a0;
                                  font-size:0.82em; user-select:none; list-style:none; }
          .ctx-footer > summary::-webkit-details-marker { display:none; }
          .ctx-footer > summary::before { content:'\\25B8  '; color:#666; }
          .ctx-footer[open] > summary::before { content:'\\25BE  '; }
          .ctx-footer-body { padding:2px 12px 10px; }
          .ctx-agent { margin:9px 0; }
          .ctx-agent:first-child { margin-top:3px; }
          .ctx-agent-head { display:flex; gap:8px; align-items:baseline;
                            font-size:0.82em; margin-bottom:3px; }
          .ctx-agent-name { color:#cdd6cf; font-weight:600; }
          .ctx-agent-model { color:#888; font-size:0.92em; }
          .ctx-agent-cost { margin-left:auto; color:#a3c9a8; }
          .ctx-agent-sandbox { font-size:0.76em; color:#7a8a7d; margin-top:3px;
                               word-break:break-word; }
          .ctx-empty { color:#666; font-size:0.82em; padding:4px 0; }"}
       [:h1 [:a {:href "/dashboard"
                 :style "color:#52b788;text-decoration:none;font-size:0.6em;
                          margin-right:12px;"} "←"]
        (if room (or (:title room) slug) slug)
        (when room
          [:span {:style "font-size:0.45em;color:#888;margin-left:12px;"}
           "participants: " (str/join ", " (map name (keys @(:participants room))))])
        ;; The room's served app (worktree app/index.html) — see dvergr.web.apps.
        (when (some-> ((requiring-resolve 'dvergr.system.db/room-by-slug) slug)
                      :room/id
                      ((requiring-resolve 'dvergr.web.apps/app-exists?)))
          [:a {:href (str "/apps/" slug "/") :target "_blank"
               :style "font-size:0.45em;color:#52b788;margin-left:12px;
                        border:1px solid #2a4a3a;border-radius:5px;padding:2px 8px;
                        text-decoration:none;"}
           "▶ app"])]
       (when-let [fd (when room
                       ((requiring-resolve 'dvergr.rooms.forks/detail) room))]
         [:div.fork-banner
          [:div.fb-head "⎘ fork of " (or (:parent-slug fd) "?")
           " · " (name (:isolation fd))
           (when (pos? (:commits fd))
             (str " · " (:commits fd) " commit" (when (not= 1 (:commits fd)) "s")))
           " · " (:msgs-since fd) " new messages"]
          (when (and (:diff-stat fd) (seq (str/trim (:diff-stat fd))))
            [:pre (hu/escape-html (:diff-stat fd))])
                ;; Database (datahike) diff — what the KB / messages-&-schedules
                ;; stores gained in the fork, alongside the git diff above. Both
                ;; are what `merge!` collapses into the parent. (`:db-changes` is
                ;; computed once by `forks/detail`, shared with the TUI.)
          (when (seq (:db-changes fd))
            [:div {:style "margin-top:6px;font-size:0.55em;color:#9bb;"}
             [:div {:style "color:#888;"} "database changes:"]
             (for [{:keys [store added removed entities]} (:db-changes fd)]
               [:div "• " (case store :knowledge "knowledge base"
                                :messages  "messages & schedules"
                                (str store))
                ": +" added " / −" removed " datoms · " entities
                " entit" (if (= 1 entities) "y" "ies")])])
          (when (:mergeable? fd)
            [:a.btn-merge {:href (str "/api/rooms/" slug "/merge")
                           :onclick "return confirm('Merge this fork into its parent?');"}
             "Merge into parent"])
          [:a.btn-discard {:href (str "/api/rooms/" slug "/discard")
                           :onclick "return confirm('Discard this fork? This deletes its branch.');"}
           "Discard"]])
       (when room
         [:div#room-stats
          {:hx-get (str "/api/rooms/" slug "/stats")
           :hx-trigger "load, every 5s"
           :hx-headers "{\"Accept\": \"text/html\"}"
           :hx-swap "innerHTML"}])
             ;; Per-AGENT context panel — one context bar per active agent (the
             ;; only context view; no separate room-wide bar). Open by default;
             ;; collapsible to reclaim space. The <details> wrapper is STATIC
             ;; page HTML (not swapped), so its open state survives the inner
             ;; body's 5s poll; the persist script below restores it across
             ;; reloads via localStorage.
       (when room
         [:details.ctx-footer {:data-slug slug :open true}
          [:summary "Context"]
          [:div.ctx-footer-body
           {:hx-get (str "/api/rooms/" slug "/agents-context")
            :hx-trigger "load, every 5s"
            :hx-headers "{\"Accept\": \"text/html\"}"
            :hx-swap "innerHTML"}]])
       (if-not room
         [:p {:style "color:#888;"} "Room not found."]
         (list
          [:div.msg-list
           {:hx-get (str "/api/rooms/" slug "/messages")
            :hx-trigger "load, every 3s"
            :hx-headers "{\"Accept\": \"text/html\"}"
            :hx-swap "innerHTML"}
           [:p {:style "color:#666;"} "Loading messages…"]]
          [:form {:method "post" :action (str "/rooms/" slug "/post")
                  :style "margin-top:12px;display:flex;gap:8px;"}
           [:input {:type "text" :name "content" :placeholder "Type a message…"
                    :required true
                    :style "flex:1;background:#18181b;border:1px solid #333;
                                   color:#e0e0e0;padding:8px 12px;border-radius:6px;"}]
           [:button {:type "submit"
                     :style "background:#52b788;color:#fff;border:none;
                                    padding:8px 18px;border-radius:6px;cursor:pointer;"}
            "Send"]]))
       [:div.footer "dvergr · room"]
        ;; Keep the chat pinned to the newest message — but only auto-scroll
        ;; when the user is already near the bottom, so reading history isn't
        ;; interrupted by the 3s poll's innerHTML swap (which else jumps to top).
       [:script (hu/raw-string
                 "(function(){var box=document.querySelector('.msg-list');if(!box)return;
                     var stick=true;
                     box.addEventListener('scroll',function(){
                       stick=(box.scrollHeight-box.scrollTop-box.clientHeight)<80;});
                     document.body.addEventListener('htmx:afterSwap',function(e){
                       if(e.target===box&&stick){box.scrollTop=box.scrollHeight;}});})();")]
        ;; Persist the Context panel's open/closed state across reloads (keyed
        ;; per room slug). The <details> is static, so this runs once on load.
       [:script (hu/raw-string
                 "(function(){var d=document.querySelector('.ctx-footer');if(!d)return;
                     var k='ctx-open:'+d.dataset.slug,v=localStorage.getItem(k);
                     if(v==='1')d.open=true; else if(v==='0')d.open=false;
                     d.addEventListener('toggle',function(){
                       localStorage.setItem(k,d.open?'1':'0');});})();")]))))

(defn room-messages-fragment
  "HTMX-friendly hiccup for a Room's message log."
  [base-ctx slug]
  (binding [ec/*execution-context* base-ctx]
    (when-let [room ((requiring-resolve 'dvergr.room.registry/lookup)
                     ((requiring-resolve 'dvergr.room.store/slug->room-id) slug))]
      (let [msgs ((requiring-resolve 'dvergr.discourse/messages) room {:limit 200})]
        (str
         (h/html
          [:div
           (if (empty? msgs)
             [:p {:style "color:#666;"} "(no messages yet)"]
             (map message-hiccup msgs))]))))))

;; =============================================================================
;; Dashboard page
;; =============================================================================

(defn- field-input-style []
  "background:#18181b;border:1px solid #333;color:#e0e0e0;padding:6px 10px;border-radius:6px;")

(defn dashboard-page
  "Render the dashboard HTML page as a string. The single index: System, the
   full Agents roster (create + configure + chat), Rooms, and Schedules."
  []
  (shell
   {}
   [:h1 [:span.health-indicator] "dvergr"
    (for [[anchor label] [["#agents" "agents"] ["#rooms" "rooms"] ["#schedules" "schedules"]]]
      [:a {:href anchor
           :style "font-size:0.45em;font-weight:400;margin-left:16px;color:#52b788;vertical-align:middle;text-decoration:none;"}
       label])]

   [:h2 "System"]
   [:div#system
    {:hx-get "/api/system"
     :hx-trigger "load, every 5s"
     :hx-headers "{\"Accept\": \"text/html\"}"
     :hx-swap "innerHTML"}
    [:p "Loading system stats..."]]

    ;; Agents — the FULL roster (folded in from the old /agents page). Create
    ;; here; each card links to Configure (primary) + Chat, matching the TUI.
   [:h2 "Agents"]
   [:form {:method "post" :action "/agents/new"
           :style "margin:0 0 12px 0;display:flex;gap:8px;align-items:center;flex-wrap:wrap;"}
    [:input {:type "text" :name "id" :placeholder "new-agent-id" :required true
             :style (str (field-input-style) "flex:1;max-width:240px;")}]
    [:input {:type "text" :name "model" :placeholder "model (optional)"
             :style (str (field-input-style) "flex:1;max-width:320px;")}]
    [:button {:type "submit"
              :style "background:#52b788;color:#fff;border:none;padding:6px 14px;border-radius:6px;cursor:pointer;"}
     "+ New agent"]]
   [:div#agents
    {:hx-get "/api/agents"
     :hx-trigger "load, every 5s"
     :hx-headers "{\"Accept\": \"text/html\"}"
     :hx-swap "innerHTML"}
    [:p "Loading agents..."]]

   [:h2 "Rooms"]
   [:form {:method "post" :action "/api/rooms"
           :style "margin:0 0 12px 0;display:flex;gap:8px;align-items:center;"}
    [:input {:type "text" :name "slug" :placeholder "slug" :required true
             :style (str (field-input-style) "flex:1;max-width:240px;")}]
    [:input {:type "text" :name "title" :placeholder "title (optional)"
             :style (str (field-input-style) "flex:1;max-width:240px;")}]
    [:button {:type "submit"
              :style "background:#52b788;color:#fff;border:none;padding:6px 14px;border-radius:6px;cursor:pointer;"}
     "+ New room"]]
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

   [:div.footer "dvergr agent runtime"]))
