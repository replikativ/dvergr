(ns dvergr.tui.app
  "TUI app for the dvergr daemon. A *rich* medium adapter: input is posted
   into discourse Rooms and the view renders by OBSERVING room state — the
   same model as Telegram/web/simmis, only in-process.

   Views:
   - :tree      — Tree of rooms / forks / agents (default landing view)
   - :room      — Chat inside a discourse Room (a chat with an agent IS its
                  own per-agent DM Room; generic rooms are entered from the tree)
   - :processes — Long-running spawned processes

   Chat history lives in the Room (its store / bus log), not a session
   chat-ctx. Input is posted straight into the current room via `room-post!`,
   addressed by the canonical room rule (`dvergr.discourse/room-target`): a
   1-agent room (or a fork of one) addresses its agent, a group broadcasts —
   the SAME rule the web + REPL use. The agent rehydrates from the room log and
   replies into the room. Re-render is driven reactively by the room mirror,
   which tracks the current room's shared
   message signal (`dvergr.rooms.messages`) into `:room-messages` — no
   per-client bus subscription, no store re-read, no response-sink, no dispatch!.

   The :tree view reads from `dvergr.rooms.tree`'s shared signal; an adapter
   spin keeps a spindel-tui tree-component state in sync with updates from the
   rooms-tree signal so the same data feeds both surfaces (this TUI + web)."
  (:require [org.replikativ.spindel-tui.tui :as tui]
            [org.replikativ.spindel-tui.style.core :as s]
            [org.replikativ.spindel-tui.components.text-input :as ti]
            [org.replikativ.spindel-tui.components.spinner :as spinner]
            [org.replikativ.spindel-tui.components.tree :as tree-c]
            [org.replikativ.spindel-tui.components.progress :as progress]
            [org.replikativ.spindel-tui.markdown :as md]
            [org.replikativ.spindel.spin.cps :refer [spin]]
            [org.replikativ.spindel.effects.track :refer [track]]
            [org.replikativ.spindel.incremental.interval :as iv]
            [dvergr.discourse :as d]
            [dvergr.discourse.commands :as commands]
            [dvergr.sandbox :as sandbox]
            [dvergr.sandbox.ns.io :as ns-io]
            [dvergr.agent.room-context :as room-context]
            [dvergr.agent.turn :as turn]
            [dvergr.agent.ops :as ops]
            [dvergr.agent.fields :as fields]
            [dvergr.ops :as dops]
            [sci.core :as sci]
            [dvergr.room.registry :as rreg]
            [dvergr.room.store :as rstore]
            [dvergr.rooms.messages :as rmsg]
            [dvergr.rooms.theme :as theme]
            [dvergr.rooms.stats :as rstats]
            [dvergr.rooms.forks :as forks]
            [dvergr.orchestration.daemon :as daemon]
            [dvergr.agent.process :as proc]
            [dvergr.orchestration.stats :as stats]
            [dvergr.rooms.tree :as rooms-tree]
            [dvergr.runtime.peer-bus :as peer-bus]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.yggdrasil :as ygg]
            [clojure.string :as str]))

;; ============================================================================
;; View Helpers
;; ============================================================================

(defn- pad-line
  "Pad a line to exactly the given width."
  [line width]
  (let [current (s/string-width line)
        padding (max 0 (- width current))]
    (str line (apply str (repeat padding " ")))))

(defn- clip
  "Truncate a plain (un-styled) string to at most `n` display columns, with an
   ellipsis. box-line pads but does not truncate, so callers building lines from
   variable-length data (paths, model ids, prompt text) clip first."
  [s n]
  (if (> (s/string-width s) n)
    (str (subs s 0 (max 0 (dec n))) "…")
    s))

(defn- box-line
  "Create a bordered line: │ content ... │"
  [content inner-width]
  (let [cw (s/string-width content)
        padding (max 0 (- inner-width cw))]
    (str "│ " content (apply str (repeat padding " ")) " │")))

(defn- header-line [title width]
  ;; ╭─ <title> <dashes>╮  — fixed cells: ╭ ─ ␠ (before title) + ␠ (after title)
  ;; + ╮ = 5. dashes = width - title-width - 5 so the whole line is exactly `width`.
  (let [tlen (s/string-width title)]
    (str "╭─ " (s/render (s/style :fg s/cyan :bold true) title) " "
         (apply str (repeat (max 0 (- width tlen 5)) "─")) "╮")))

(defn- footer-line [width]
  (str "╰" (apply str (repeat (- width 2) "─")) "╯"))

(defn- separator-line [width]
  (str "├" (apply str (repeat (- width 2) "─")) "┤"))

;; ============================================================================
;; Per-room SCI sandbox
;; ============================================================================
;; For command $(…) / :handler eval — full namespace kit (intake, dh, search,
;; bash bound to the room's chat-ctx, …), cached so state persists. This gives
;; command code the SAME sandboxed surface as the agent's clojure_eval. (There
;; is no bespoke TUI REPL: developers use the daemon nREPL, agents use
;; clojure_eval — see doc decision 2026-06.)
(defonce ^:private room-sandboxes (atom {}))

(defn- room-sandbox-eval
  [daemon room agent-id code]
  (let [exec-ctx (:execution-ctx daemon)
        rid      (:id room)
        sci-ctx  (or (get @room-sandboxes rid)
                     (binding [ec/*execution-context* exec-ctx]
                       (let [chat-ctx (room-context/ensure-ctx!
                                       room (or agent-id :var) {:budget-dollars 0.25})
                             c        (sandbox/fork-for-session exec-ctx)]
                         (sandbox/setup-agent-namespaces! c exec-ctx)
                         (ns-io/add-bash-ns! c chat-ctx)
                         (swap! room-sandboxes assoc rid c)
                         c)))]
    (binding [ec/*execution-context* exec-ctx]
      (sci/eval-string* sci-ctx code))))

(defn- room-post!
  "Post user `text` into `room`, addressed by the canonical room rule
   (`discourse/room-target`): a single-agent room — or a fork of one — addresses
   that agent so it replies; a group/empty room broadcasts. Flips the optimistic
   spinner on when an agent will actually answer. THE one place TUI room input is
   sent — both Enter and the slash-command `post-user!` go through it, so the TUI
   matches the web + REPL exactly."
  [signals room text]
  (let [to (d/room-target room)]
    (when (and to (:status signals)) (reset! (:status signals) :running))
    (d/post! room (d/message :local to text nil {:role :user}))))

(defn- command-host-ctx
  "Capabilities the slash-command registry needs, wired to TUI room state.
   `notify!` posts a no-turn `⌘` line to the reserved :_activity sink (visible
   but not consumed); `post-user!` routes a user turn the same way Enter does;
   `switch-room!` re-focuses the (forked) room; `sci-eval` is the local REPL."
  [daemon signals]
  (let [ec*      (:execution-ctx daemon)
        room-id  @(:current-room signals)
        room     (when room-id (rreg/lookup room-id))
        ;; The room's agent (room-target) — addressing derives from the room,
        ;; not UI state, so every surface behaves the same (see room-post!).
        agent-id (when room (d/room-target room))]
    {:room            room
     :agent-id        agent-id
     :daemon          daemon
     :exec-ctx        ec*
     ;; Operator console (already backed by an nREPL) — grant the executing
     ;; agent-scoped tool commands (/clojure_eval <agent> …).
     :tool-exec?      true
     :available-tools (keys @@(requiring-resolve 'dvergr.tools/registry))
     :post-user!
     (fn [text]
       (binding [ec/*execution-context* ec*]
         (when room (room-post! signals room text))))
     :notify!
     (fn [text]
       (binding [ec/*execution-context* ec*]
         (when room
           (d/post! room (d/message :system :_activity (str "⌘ " text) nil
                                    {:role :assistant})))))
     :switch-room!
     (fn [new-room]
       (reset! (:current-room signals) (:id new-room))
       (reset! (:scroll signals) 0))
     :sci-eval
     ;; Command $(…)/:handler eval runs in the ROOM's SCI sandbox (bash, dh,
     ;; search, intake, …) — the SAME surface as the agent's clojure_eval.
     (fn [code]
       (try
         (if room
           (room-sandbox-eval daemon room agent-id code)
           "Error: no current room for eval")
         (catch Throwable e (str "Error: " (.getMessage e)))))}))

;; ---------------------------------------------------------------------------
;; Slash-command popup (fuzzy menu over the full registry)
;; ---------------------------------------------------------------------------

;; all-commands scans skill files, so cache it briefly to avoid I/O per keystroke.
(defonce ^:private cmd-list-cache (atom {:at 0 :items []}))

(defn- all-commands-cached []
  (let [now (System/currentTimeMillis)]
    (when (> (- now (:at @cmd-list-cache)) 3000)
      (reset! cmd-list-cache
              {:at now
               :items (try (commands/all-commands
                            {:available-tools (keys @@(requiring-resolve 'dvergr.tools/registry))})
                           (catch Throwable _ []))}))
    (:items @cmd-list-cache)))

;; Stats (dvergr.rooms.stats) feed both the :room header strip and the tree
;; labels — the SAME source the web renders, so the two surfaces agree. The
;; ledger query is TTL-cached (per room-id) so per-keystroke / per-frame
;; re-renders don't hammer Datahike.
(defonce ^:private tui-ctx-ref (atom nil))
(defonce ^:private room-stats-cache (atom {}))

(defn- room-stats-cached
  "TTL-cached room-stats for a room-id keyword, or nil. Uses the bound ctx if
   present, else the TUI's stashed ctx."
  [room-id]
  (let [now (System/currentTimeMillis)
        ent (get @room-stats-cache room-id)]
    (if (and ent (< (- now (:at ent)) 2500))
      (:val ent)
      (let [ctx (or (when (ec/execution-context-bound?) ec/*execution-context*)
                    @tui-ctx-ref)
            val (try
                  (binding [ec/*execution-context* ctx]
                    (when-let [room (rreg/lookup room-id)]
                      (rstats/room-stats room)))
                  (catch Throwable _ nil))]
        (swap! room-stats-cache assoc room-id {:at now :val val})
        val))))

(defonce ^:private system-stats-cache (atom {:at 0 :val nil}))

(defn- system-stats-cached []
  (let [now (System/currentTimeMillis)]
    (when (> (- now (:at @system-stats-cache)) 2500)
      (let [ctx (or (when (ec/execution-context-bound?) ec/*execution-context*)
                    @tui-ctx-ref)
            val (try (binding [ec/*execution-context* ctx] (rstats/system-stats))
                     (catch Throwable _ nil))]
        (reset! system-stats-cache {:at now :val val})))
    (:val @system-stats-cache)))

(defonce ^:private fork-detail-cache (atom {}))

(defn- fork-detail-cached
  "TTL-cached forks/detail for a fork Room (git diff is a subprocess), or nil."
  [room]
  (when (forks/fork? room)
    (let [rid (:id room)
          now (System/currentTimeMillis)
          ent (get @fork-detail-cache rid)]
      (if (and ent (< (- now (:at ent)) 3000))
        (:val ent)
        (let [ctx (or (when (ec/execution-context-bound?) ec/*execution-context*) @tui-ctx-ref)
              val (try (binding [ec/*execution-context* ctx] (forks/detail room))
                       (catch Throwable _ nil))]
          (swap! fork-detail-cache assoc rid {:at now :val val})
          val)))))

(defn- agent-context-line
  "Per-AGENT context bar for the expanded context panel: the agent name +
   short model, the same styled fill bar, a token label, and that ctx's cost."
  [{:keys [agent model pct tokens limit should-compact? cost-dollars]}]
  (let [p    (min 1.0 (double (or pct 0)))
        fill (s/style :fg (if should-compact? (s/ansi256 178) (s/ansi256 71)))
        dim  (s/style :fg (s/ansi256 238))
        bar  (progress/view (progress/progress-state
                             :width 14 :percent p :bar-style :thin
                             :full-style fill :empty-style dim))]
    (str (s/render (s/style :fg (s/ansi256 252))
                   (str (name agent)
                        (when-let [m (rstats/short-model model)] (str " (" m ")"))))
         "  " bar
         (s/render (s/style :fg (s/ansi256 245))
                   (str "  " (Math/round (* 100.0 p)) "%  "
                        (rstats/fmt-tokens tokens) "/" (rstats/fmt-tokens limit)
                        (when cost-dollars (str " · " (rstats/fmt-cost cost-dollars)))
                        (when should-compact? "  ⚠"))))))

(defn- command-menu
  "Items for the `/`-popup given the raw input, or nil when it shouldn't show
   (no leading `/`, or an argument has been started). Filters the full registry
   by name prefix; capped at 8."
  [text]
  (let [t (str/triml (str text))]
    (when (and (str/starts-with? t "/") (not (re-find #"\s" t)))
      (let [prefix (str/lower-case (subs t 1))
            items  (->> (all-commands-cached)
                        (filter #(str/starts-with? (str/lower-case (:name %)) prefix))
                        (sort-by :name)
                        (take 8)
                        vec)]
        (when (seq items) items)))))

(defn- command-menu-lines
  "Render the popup: one boxed row per item, the selected one highlighted."
  [items idx inner-w box-line-fn]
  (vec
   (map-indexed
    (fn [i {:keys [name argument-hint description]}]
      (let [sel?  (= i idx)
            label (str "/" name (when (seq argument-hint) (str " " argument-hint)))
            raw   (str (if sel? "▸ " "  ") label
                       (when (seq description) (str "  — " description)))
            raw   (if (> (count raw) (- inner-w 2)) (subs raw 0 (- inner-w 2)) raw)
            styled (if sel?
                     (s/render (s/style :fg (s/ansi256 16) :bg (s/ansi256 250)) raw)
                     (s/render (s/style :fg (s/ansi256 245)) raw))]
        (box-line-fn styled inner-w)))
    items)))

;; ============================================================================
;; Tree adapter — dvergr.rooms.tree value → spindel-tui tree component nodes
;; ============================================================================

(defn- agent-summary-line
  "One-line label for an agent in the tree: status glyph, name, spend·age,
   then a short description. Cost/last-active come from `dvergr.orchestration.stats`
   (cached; refreshes async) — the per-agent cost the old :agents view showed,
   now folded into the tree where agents live."
  [{:keys [id status description]}]
  (let [name-str (str id)
        status-glyph (case status
                       :running "●"
                       :stopped "○"
                       :registered "·"
                       "·")
        st       (when id (stats/get-stats id))
        cost     (when (:cost-dollars st) (format "$%.3f" (:cost-dollars st)))
        stat-suffix (let [bits (remove nil? [cost (:last-active-str st)])]
                      (when (seq bits) (str "  " (str/join " · " bits))))
        desc-suffix (when (seq description)
                      (let [d (str/replace description #"\n+" " ")
                            short (if (> (count d) 60) (str (subs d 0 57) "…") d)]
                        (str "  " short)))]
    (str status-glyph " " name-str stat-suffix desc-suffix)))

(defn- agents-for-room
  "Look up registered-agent entries for the agent-ids attached to a room."
  [agent-ids agents-by-id]
  (->> agent-ids
       (keep agents-by-id)
       (sort-by :id)))

(defn- adapt-agent
  [agent-entry parent-tag]
  {:id    (into [:agent] (conj (vec parent-tag) (:id agent-entry)))
   :label (agent-summary-line agent-entry)
   :kind  :agent
   :agent agent-entry})

(declare adapt-node)

(defn- adapt-room
  [node agents-by-id]
  (let [agents-here (agents-for-room (:agent-ids node) agents-by-id)
        agent-nodes (mapv #(adapt-agent % [:room (:slug node)]) agents-here)
        child-nodes (mapv #(adapt-node % agents-by-id) (:children node))
        agent-count (count agents-here)
        st          (room-stats-cached (rstore/slug->room-id (:slug node)))]
    {:id     [:room (:slug node)]
     :label  (str "# " (or (:title node) (:slug node))
                  (when (pos? agent-count) (str "  [" agent-count "]"))
                  (when st
                    (str "  · " (:message-count st) " msgs"
                         " · " (rstats/fmt-cost (:cost-dollars st))
                         (when-let [c (:context st)]
                           (str " · " (Math/round (* 100.0 (double (or (:pct c) 0)))) "% ctx"
                                (when (:should-compact? c) " ⚠")))
                         (when (:last-active-str st) (str " · " (:last-active-str st))))))
     :kind   :room
     :room   node
     :children (concat agent-nodes child-nodes)}))

(defn- adapt-fork-node
  [node agents-by-id]
  (let [child-nodes (mapv #(adapt-node % agents-by-id) (:children node))]
    {:id    [:fork (:id node)]
     :label (str "⎘ " (or (:title node) (:slug node)))
     :kind  :fork
     :fork  node
     :children child-nodes}))

(defn- adapt-node
  "Dispatch on :kind — registry rooms and forks share the same recursive shape."
  [node agents-by-id]
  (case (:kind node)
    :fork (adapt-fork-node node agents-by-id)
    (adapt-room node agents-by-id)))

(defn- adapt-tree-value
  "Convert a dvergr.rooms.tree value (now a flat tree from the room
   registry) to a vector of spindel-tui tree-component nodes. Top-level
   synthetic 'Agents' group holds every registered agent so the user
   has an entry point even when no rooms exist yet."
  [tree-value]
  (let [agents       (:agents tree-value)
        agents-by-id (into {} (map (juxt :id identity)) agents)
        agents-group {:id    [:group :agents]
                      :label (str "Agents  [" (count agents) "]")
                      :kind  :agents-group
                      :children (mapv #(adapt-agent % [:group :agents]) agents)}
        room-nodes   (mapv #(adapt-node % agents-by-id) (:roots tree-value))]
    (vec (cons agents-group room-nodes))))

;; ============================================================================
;; Tree view — replaces the flat agents-view
;; ============================================================================

(defn- tree-view
  "Render the rooms+agents tree using the spindel-tui tree component."
  [signals width height]
  (let [tree-state @(:tree signals)
        inner-w    (- width 4)
        ;; System rollup strip (same source the web dashboard's #system panel
        ;; renders) — reserve its line before sizing the tree viewport.
        sys         (system-stats-cached)
        sys-str     (when sys
                      (str (:room-count sys) " rooms  ·  "
                           (:message-count sys) " msgs  ·  "
                           (:agents-online sys) " online  ·  "
                           (rstats/fmt-cost (:cost-dollars sys)) " spend"))
        ;; Pending fork merge/discard confirmation or its result toast.
        confirm     (some-> (:fork-confirm signals) deref)
        confirm-str (some-> (or (:label confirm) (:msg confirm)))
        available-h (- height 5 (if sys-str 1 0) (if confirm-str 1 0))
        ;; Tell the tree component its viewport height so the cursor
        ;; window can be computed on render.
        sized-state (assoc tree-state :height available-h)
        rows        (tree-c/view sized-state)
        empty-count (max 0 (- available-h (count rows)))]
    (concat
     [(header-line "Rooms · Agents · Forks" width)]
     (when sys-str
       [(box-line (s/render (s/style :fg (s/ansi256 245)) sys-str) inner-w)])
     (mapv #(box-line % inner-w) rows)
     (repeat empty-count (box-line "" inner-w))
     (when confirm-str
       [(box-line (s/render (s/style :fg (s/ansi256 (if (:label confirm) 178 71))) confirm-str)
                  inner-w)])
     [(separator-line width)
       ;; box-line pads ANSI-aware (clip guards narrow terminals) — never the
       ;; hand-rolled magic-number padding that wrapped the box before.
      (box-line (s/render (s/style :fg (s/ansi256 240))
                          (clip "Tab:view  Enter:open/config  n/a:new room/agent  c:chat  f/m/x:fork  d:del  Ctrl+C:quit"
                                inner-w))
                inner-w)
      (footer-line width)])))

(defn- create-room-view
  "Simple full-screen prompt for creating a new room. The user types
   a slug into the input box; Enter creates, Esc cancels."
  [signals width _height]
  (let [inner-w (- width 4)]
    [(header-line "New room" width)
     (box-line "" inner-w)
     (box-line (s/render (s/style :fg (s/ansi256 240))
                         "Type a slug (URL-friendly, e.g. \"my-project\"). Enter to create, Esc to cancel.")
               inner-w)
     (box-line "" inner-w)
     (box-line (ti/view @(:input signals)) inner-w)
     (box-line "" inner-w)
     (separator-line width)
     ;; box-line pads to inner-w via s/string-width (ANSI-aware) — the hand-rolled
     ;; "(- inner-w 36)" was off by one (the text is 37 visible chars), making this
     ;; line width+1 → it overflowed by a column and the terminal wrapped the box.
     (box-line (s/render (s/style :fg (s/ansi256 240))
                         "Enter:create  Esc:cancel  Ctrl+C:quit")
               inner-w)
     (footer-line width)]))

(defn- create-agent-view
  "Full-screen prompt for creating a new agent. The user types an id; Enter
   creates a row (via dvergr.agent.ops) and drops into its config view."
  [signals width _height]
  (let [inner-w (- width 4)]
    [(header-line "New agent" width)
     (box-line "" inner-w)
     (box-line (s/render (s/style :fg (s/ansi256 240))
                         "Type an agent id (e.g. \"scribe\"). Enter to create, Esc to cancel.")
               inner-w)
     (box-line "" inner-w)
     (box-line (ti/view @(:input signals)) inner-w)
     (box-line "" inner-w)
     (separator-line width)
     (box-line (s/render (s/style :fg (s/ansi256 240))
                         "Enter:create  Esc:cancel  Ctrl+C:quit")
               inner-w)
     (footer-line width)]))

;; ============================================================================
;; Agent config view — overview + inline scalar editing over dvergr.agent.ops
;; ============================================================================
;; The agent IS its durable actor row (model/provider/skills/budget) + a
;; project-local persona prompt. This is the TUI surface over the SAME shared
;; ops layer the web config page drives. Scalars are edited inline; the
;; multi-line system prompt is edited in the web UI or via $EDITOR on the file.

;; The config fields are DERIVED from the shared spec (`dvergr.agent.fields`) —
;; labels, parse rules and option sources are the same the web form uses. The
;; long :prompt field is web-only (edited via $EDITOR here), so it's dropped.
(def ^:private agent-cfg-fields
  (filterv (complement :web-only?) fields/fields))

(defn- cfg-field-display
  "String form of agent field `k` for display/seed-editing (never nil)."
  [a k]
  ((:format (fields/by-k k)) a))

(defn- parse-cfg-field
  "Parse the edited text for field `k` back into an ops/update-agent! value."
  [k s]
  ((:parse (fields/by-k k)) s))

(defn- field-options
  "Selectable options for a picker field — [{:value :label}] — or nil for a
   free-text field. Provider/model option data come from the shared spec; for
   :model we narrow to the agent's current provider (falling back to all) and
   tack the provider onto the label, the terminal-picker presentation."
  [a k]
  (when-let [opts-fn (:options (fields/by-k k))]
    (let [opts (opts-fn a)]
      (if (= k :model)
        (let [prov (:provider a)
              fl   (filterv #(= prov (:group %)) opts)]
          (mapv (fn [o] (assoc o :label (str (:label o) "  · " (name (:group o)))))
                (if (seq fl) fl opts)))
        opts))))

(defn- window
  "[(start) (visible-subvec)] of `items` showing at most `n`, centered on idx."
  [items idx n]
  (let [items (vec items) c (count items)]
    (if (<= c n)
      [0 items]
      (let [start (max 0 (min (- c n) (- idx (quot n 2))))]
        [start (subvec items start (min c (+ start n)))]))))

(defn- agent-config-view
  [signals width _height]
  (let [inner-w  (- width 4)
        id       @(:config-agent signals)
        ctx      @tui-ctx-ref
        a        (when id (binding [ec/*execution-context* ctx] (ops/get-agent id)))
        idx      @(:config-field-idx signals)
        editing? @(:config-editing? signals)
        pick-idx @(:config-pick-idx signals)
        confirm  @(:config-confirm signals)
        edit-k   (:k (nth agent-cfg-fields idx))
        opts     (when (and editing? a) (field-options a edit-k))]
    (if-not a
      [(header-line "Agent" width)
       (box-line "" inner-w)
       (box-line (str "Agent " (some-> id name) " not found.") inner-w)
       (separator-line width)
       (box-line (s/render (s/style :fg (s/ansi256 240)) "Esc:back") inner-w)
       (footer-line width)]
      (concat
       [(header-line (str "Configure " (name id)
                          (when (:online? a) "  ● live"))
                     width)
        (box-line "" inner-w)]
        ;; editable scalar fields (model/provider carry a ▾ — they're pickers)
       (map-indexed
        (fn [i {:keys [k label]}]
          (let [sel?   (= i idx)
                pick?  (boolean (#{:provider :model} k))
                mark   (if sel? "▸ " "  ")
                suffix (if pick? " ▾" "")
                val    (cond
                         (and sel? editing? opts)
                         (:label (nth opts (max 0 (min (dec (count opts)) pick-idx))
                                      {:label ""}))
                         (and sel? editing?)
                         (str (ti/value @(:input signals)) "▏")
                         :else (cfg-field-display a k))
                line   (clip (str mark label ": " val suffix) inner-w)]
            (box-line (if sel? (s/render (s/style :fg s/cyan) line) line) inner-w)))
        agent-cfg-fields)
        ;; open picker — windowed option list under the fields
       (when (and editing? opts)
         (let [[start vis] (window opts pick-idx 8)]
           (cons (box-line "" inner-w)
                 (map-indexed
                  (fn [j o]
                    (let [selp? (= (+ start j) pick-idx)
                          ln    (clip (str (if selp? " ► " "   ") (:label o)) inner-w)]
                      (box-line (if selp? (s/render (s/style :fg (s/ansi256 71)) ln) ln)
                                inner-w)))
                  vis))))
       [(box-line "" inner-w)
        (box-line (s/render (s/style :fg (s/ansi256 240))
                            (clip (str "system prompt [source: " (name (:persona-source a)) "]"
                                       (when (= :builtin (:persona-source a)) "  (shipped default)"))
                                  inner-w))
                  inner-w)
        (box-line (s/render (s/style :fg (s/ansi256 240))
                            (clip "(edit the system prompt in the web config page or via the REPL/API)"
                                  inner-w))
                  inner-w)]
        ;; prompt preview (first lines)
       (let [plines (->> (str/split-lines (or (:prompt a) "")) (remove str/blank?) (take 5))]
         (when (seq plines)
           (cons (box-line "" inner-w)
                 (map #(box-line (s/render (s/style :fg (s/ansi256 244))
                                           (clip (str "  " %) inner-w)) inner-w)
                      plines))))
       [(separator-line width)]
       (cond
         confirm        [(box-line (s/render (s/style :fg (s/ansi256 214)) confirm) inner-w)]
         (and editing? opts)
         [(box-line (s/render (s/style :fg (s/ansi256 240))
                              "↑/↓ or j/k:choose  Enter:select  Esc:cancel") inner-w)]
         editing?       [(box-line (s/render (s/style :fg (s/ansi256 240))
                                             "Enter:save  Esc:cancel edit") inner-w)]
         :else          [(box-line (s/render (s/style :fg (s/ansi256 240))
                                             "j/k:field  e/Enter:edit  p:prompt($EDITOR)  c:chat  x:delete  Esc:back") inner-w)])
       [(footer-line width)]))))

;; ============================================================================
;; Message rendering helpers (shared by the :room view)
;; ============================================================================

(defn- wrap-text
  "Word-wrap text to fit within width."
  [text width]
  (if (<= (count text) width)
    [text]
    (loop [remaining text
           lines []]
      (if (empty? remaining)
        lines
        (if (<= (count remaining) width)
          (conj lines remaining)
          (let [break-at (or (str/last-index-of remaining " " width) width)
                break-at (if (zero? break-at) width break-at)]
            (recur (str/trim (subs remaining break-at))
                   (conj lines (subs remaining 0 break-at)))))))))

(defn- render-tool-use
  "Render an assistant's tool *call* (the code/input the agent ran)."
  [tu inner-width]
  (let [tname (or (:tool-use/name tu) (:name tu))
        input (or (:tool-use/input tu) (:input tu))
        ;; For clojure_eval, the interesting payload is :code.
        body  (cond
                (and (map? input) (:tool-input.clojure-eval/code input))
                (:tool-input.clojure-eval/code input)
                (and (map? input) (:code input))   (:code input)
                (map? input)                       (pr-str input)
                :else                              (str input))
        header (s/render (s/style :fg s/yellow :bold true)
                         (str "  → " tname))
        code-lines (mapcat (fn [l]
                             (map #(s/render (s/style :fg (s/ansi256 244))
                                             (str "    " %))
                                  (wrap-text l (- inner-width 4))))
                           (str/split-lines (str body)))]
    (concat [header] code-lines)))

;; ============================================================================
;; Room View — chat inside a discourse Room (unified persistent + fork)
;; ============================================================================

(defn- render-reasoning
  "Render a message's reasoning/thinking trace as dim italic indented lines.
   Only shown in trace mode. [] when there's no reasoning."
  [reasoning inner-w]
  (if (str/blank? reasoning)
    []
    (vec (for [raw (str/split-lines reasoning)
               l   (wrap-text raw (- inner-w 4))]
           (s/render (s/style :fg (s/ansi256 245) :italic true)
                     (str "  ⋮ " l))))))

(defn- fmt-ts
  "Epoch-millis → \"HH:mm\" for the speaker line, or nil."
  [ts]
  (when ts
    (try (.format (java.text.SimpleDateFormat. "HH:mm") (java.util.Date. (long ts)))
         (catch Throwable _ nil))))

(defn- speaker-line
  "A bold colored `speaker:` header with the message time dimmed + right-aligned
   to `inner-w` (Telegram-style), so each turn is timestamped per agent."
  [speaker ts role-ansi inner-w]
  (let [hdr   (str speaker ":")
        clock (or (fmt-ts ts) "")
        pad   (max 1 (- inner-w (s/string-width hdr) (s/string-width clock)))]
    (str (s/render (s/style :fg (s/ansi256 role-ansi) :bold true) hdr)
         (apply str (repeat pad " "))
         (s/render (s/style :fg (s/ansi256 240)) clock))))

(defn- render-room-message
  "Render one room message (unified shape `{:from :content :role :tool-uses}`)
   to styled lines. Three flavors, keyed on `:role`:

     :tool       — agent tool-activity posted for observability. Amber, no
                   speaker line (it's not conversation).
     :assistant  — an agent reply. Cyan speaker line, markdown-rendered body,
                   and any `:tool-uses` rendered inline underneath (the code
                   the agent ran), mirroring the legacy :chat view.
     else        — a user/other post. Green speaker line, plain white body.

   When `trace?` is true, the reasoning trace (dim, italic) and full tool-call
   inputs are also shown. Pure; unit-testable."
  [m inner-w trace?]
  (let [content (or (:content m) "")
        role    (:role m)
        speaker (or (some-> (:from m) name) "—")
        ;; Trace ON → the full reasoning trace; trace OFF but reasoning present →
        ;; a dim one-line 💭 hint (the TUI's "collapsed" form; ^T expands it).
        reason  (cond
                  trace?               (render-reasoning (:reasoning m) inner-w)
                  (seq (:reasoning m)) [(s/render (s/style :fg (s/ansi256 240))
                                                  "  💭 thinking · ^T to show")]
                  :else                nil)]
    (cond
      ;; Fork boundary — a centered separator marking where the inherited
      ;; (pre-fork) history ends and the fork's own conversation begins.
      (:divider? m)
      (let [label      (str " " content " ")
            dashes     (max 0 (- inner-w (s/string-width label)))
            left       (quot dashes 2)]
        [(s/render (s/style :fg (s/ansi256 (theme/ansi :divider)))
                   (str (apply str (repeat left "─"))
                        label
                        (apply str (repeat (- dashes left) "─"))))])

      ;; Tool activity — amber. Lead the first line with the AGENT name so it's
      ;; clear WHO is calling the tool (multiple agents share a room); wrapped
      ;; continuation lines are indented to align under it.
      (= :tool role)
      (let [tool-lines (mapcat #(wrap-text % (- inner-w 2)) (str/split-lines content))]
        (vec (concat
              reason
              (map-indexed
               (fn [i l]
                 (s/render (s/style :fg (s/ansi256 (theme/ansi :tool)))
                           (str "  " (if (and (zero? i) (not= speaker "—"))
                                       (str speaker " ") "  ")
                                l)))
               tool-lines)
              (when trace? (mapcat #(render-tool-use % inner-w) (:tool-uses m))))))

      ;; Agent reply — cyan speaker, markdown body, inline tool calls.
      (= :assistant role)
      (let [rendered   (try (md/render-inline content)
                            (catch Exception _ content))
            body-lines (if (str/blank? content)
                         []
                         (mapcat #(wrap-text % (- inner-w 2))
                                 (str/split-lines rendered)))
            tu-lines   (mapcat #(render-tool-use % inner-w) (:tool-uses m))]
        ;; Append a reset to each body line so a styled span split across
        ;; wrapped lines (e.g. **bold** whose SGR-open and reset land on
        ;; different lines) can't bleed into the box padding/border.
        (into [(speaker-line speaker (:ts m) (theme/ansi :assistant) inner-w)]
              (concat reason (map #(str "  " % "\u001b[0m") body-lines) tu-lines)))

      ;; User / other — green speaker, plain body.
      :else
      (into [(speaker-line speaker (:ts m) (theme/ansi :user) inner-w)]
            (concat reason
                    (for [raw (str/split-lines content)
                          l   (wrap-text raw (- inner-w 2))]
                      (s/render (s/style :fg s/white) (str "  " l))))))))

(defn- room-view
  "Render the :room view: header showing the current Room's title +
   participants, scrollable message list, and an input box for posting
   to the room.

   Renders from the `:room-messages` signal — the current room's shared
   reactive message view (`dvergr.rooms.messages`), mirrored into the signal
   map by the room mirror (see `start-room-mirror!`). No per-render store read,
   no bus subscription here — the render just tracks one signal."
  [signals width height]
  (let [;; The shared room signal's value, mirrored in by start-room-mirror!.
        ;; Tracking this (a signal-map entry) re-renders on every new message.
        msgs    @(:room-messages signals)
        room-id (or @(:current-room signals) :daemon)
        ;; :tui-ctx in spindel-tui's signal map is the raw
        ;; ExecutionContext, not a signal — deref'ing it explodes.
        ctx     (:tui-ctx signals)
        room    (binding [ec/*execution-context* ctx]
                  (rreg/lookup room-id))
        inner-w (- width 4)
        title   (or (:title room) (name room-id) "(no room)")
        agents  (when room
                  (binding [ec/*execution-context* ctx]
                    (vec (keys @(:participants room)))))
        header  (str "# " title
                     (when (seq agents)
                       (str "  · participants: "
                            (str/join ", " (map name agents)))))
        ;; Stats strip — the SAME tokens the web room page shows
        ;; (dvergr.rooms.stats/strip-parts), so the two surfaces agree.
        room-st   (when room (room-stats-cached room-id))
        stats-str (when room-st (str/join "  ·  " (rstats/strip-parts room-st)))
        ;; Per-AGENT context bars (one per active agent in the room). Shown by
        ;; default; Ctrl+E hides them to reclaim message space. There is no
        ;; separate room-wide "global" bar — the per-agent view IS the context.
        ctx-lines (when room
                    (binding [ec/*execution-context* ctx]
                      (when (boolean (some-> (:ctx-expanded? signals) deref))
                        (mapv agent-context-line (rstats/room-agent-contexts room)))))
        ;; Fork detail line (only when this room IS a fork) — its diff/history
        ;; overview vs the parent, plus the merge/discard hint.
        fd        (when (and room (forks/fork? room)) (fork-detail-cached room))
        fork-str  (when fd
                    (s/render (s/style :fg (s/ansi256 109))
                              (str "⎘ fork of " (:parent-slug fd) "  ·  " (name (:isolation fd))
                                   (when (pos? (:commits fd))
                                     (str "  ·  " (:commits fd) " commit" (when (not= 1 (:commits fd)) "s")))
                                   (when (seq (:files fd)) (str "  ·  " (count (:files fd)) " files"))
                                   ;; database (datahike) changes alongside the git diff
                                   (apply str (for [{:keys [store added]} (:db-changes fd)]
                                                (str "  ·  +" added " " (name store))))
                                   "  ·  " (:msgs-since fd) " new msgs"
                                   "    (Esc → tree: m merge · x discard)")))
        trace?    (boolean (some-> (:trace? signals) deref))
        msg-lines (mapcat #(render-room-message % inner-w trace?) msgs)
        ;; Reserve one line for the optimistic spinner (shown while the
        ;; agent is mid-turn — :status flipped on send, cleared by the
        ;; watcher when the reply lands). :status/:spinner are optional so
        ;; the headless harness (which omits them) still renders.
        thinking?    (when-let [st (:status signals)] (= :running @st))
        spinner-line (when (and thinking? (:spinner signals))
                       (spinner/view @(:spinner signals)))
        ;; `/`-popup: while the user types a command name, show the matching
        ;; commands (whole registry) above the input, selected one highlighted.
        menu        (command-menu (ti/value @(:input signals)))
        menu-idx    (when menu (max 0 (min (or (some-> (:cmd-idx signals) deref) 0)
                                           (dec (count menu)))))
        menu-lines  (when menu (command-menu-lines menu menu-idx inner-w box-line))
        available-h (- height 9 (count menu-lines) (if stats-str 1 0) (count ctx-lines) (if fork-str 1 0))
        scroll      @(:scroll signals)
        n           (count msg-lines)
        max-scroll  (max 0 (- n available-h))
        scroll      (min scroll max-scroll)
        end         (max 0 (- n scroll))
        start       (max 0 (- end available-h))
        visible     (subvec (vec msg-lines) start end)
        empty-count (max 0 (- available-h (count visible)))
        input-line  (ti/view @(:input signals))]
    (concat
     [(header-line header width)]
     (when fork-str
       [(box-line fork-str inner-w)])
     (when stats-str
       [(box-line (s/render (s/style :fg (s/ansi256 245)) stats-str) inner-w)])
     (mapv #(box-line % inner-w) (or ctx-lines []))
     (mapv #(box-line % inner-w) visible)
     (repeat empty-count (box-line "" inner-w))
     (or menu-lines [])
     [(separator-line width)
      (box-line (or spinner-line
                    (when menu "  ↑/↓ select · Tab complete · /help for all")
                    "") inner-w)
      (box-line input-line inner-w)
      (separator-line width)
      (pad-line (str "│ " (s/render (s/style :fg (s/ansi256 240))
                                    "Enter:send  Esc:back  ↑/↓:scroll  ^T:trace  ^E:ctx  ^O:mouse  ^C:quit")
                     (apply str (repeat (max 0 (- inner-w 70)) " ")) " │")
                width)
      (footer-line width)])))

;; ============================================================================
;; Processes View
;; ============================================================================

(def ^:private terminal-keep-ms
  "How long terminated processes stay visible in the pane so the user
   sees their abort/completion confirmed before the row disappears."
  30000)

(defn- processes-list
  "Processes for the currently-selected chat-ctx, sorted oldest first.
   Includes running, awaiting-decision, AND recently-terminated entries
   (last 30s) so the user sees confirmation of their abort. Returns []
   if no chat-ctx is selected."
  [signals]
  (if-let [cctx @(:chat-ctx signals)]
    (->> (proc/list-processes cctx)
         (filter (fn [p]
                   (or (#{:running :awaiting-decision} (:status p))
                       (and (:since-term-ms p)
                            (< (:since-term-ms p) terminal-keep-ms)))))
         (sort-by :started-at))
    []))

(defn- format-elapsed [ms]
  (cond
    (< ms 1000)      (str ms "ms")
    (< ms 60000)     (format "%.1fs" (/ ms 1000.0))
    (< ms 3600000)   (format "%dm %02ds" (quot ms 60000) (mod (quot ms 1000) 60))
    :else            (format "%dh %02dm" (quot ms 3600000) (mod (quot ms 60000) 60))))

(defn- processes-view
  "Render the active processes list. Recently-terminated rows stay
   visible for ~30s so the user sees abort/completion confirmed."
  [signals width _height]
  (let [procs (processes-list signals)
        sel @(:proc-idx signals)
        inner-w (- width 4)
        items (map-indexed
               (fn [idx p]
                 (let [status (:status p)
                       terminal? (#{:completed :aborted} status)
                       elapsed (format-elapsed (:elapsed-ms p))
                       sel? (= idx sel)
                       prefix (if sel?
                                (s/render (s/style :fg s/cyan :bold true) "> ")
                                "  ")
                       status-color (case status
                                      :awaiting-decision s/yellow
                                      :running           s/green
                                      :aborted           s/red
                                      :completed         (s/ansi256 247)
                                      (s/ansi256 240))
                        ;; Terminated rows render dimmed; live rows full bold.
                       status-str (s/render (s/style :fg status-color
                                                     :bold (not terminal?))
                                            (name status))
                       elapsed-str (s/render
                                    (s/style :fg (if terminal?
                                                   (s/ansi256 240)
                                                   (s/ansi256 244)))
                                    elapsed)
                       desc-style (if terminal?
                                    (s/style :fg (s/ansi256 240) :italic true)
                                    (s/style :fg s/white))
                       desc-str (s/render desc-style (:description p))
                       line (str prefix status-str "  " elapsed-str "  " desc-str)]
                   (box-line line inner-w)))
               procs)]
    (concat
     [(header-line "Processes" width)]
     (if (seq items)
       items
       [(box-line (s/render (s/style :fg (s/ansi256 240))
                            "No active processes (select an agent + chat first)")
                  inner-w)])
     [(separator-line width)
      (pad-line
       (str "│ " (s/render (s/style :fg (s/ansi256 240))
                           "Tab:view  j/k:nav  a:abort  c:continue  e:extend $0.10  Ctrl+C:quit")
            (apply str (repeat (max 0 (- inner-w 70)) " ")) " │")
       width)
      (footer-line width)])))

;; ============================================================================
;; Main View Dispatch
;; ============================================================================

(defn- dashboard-view
  "Dispatch to the appropriate view based on :view-mode."
  [signals width height]
  (let [mode @(:view-mode signals)]
    (map #(pad-line % width)
         (case mode
           :tree         (tree-view signals width height)
           :room         (room-view signals width height)
           :create-room  (create-room-view signals width height)
           :create-agent (create-agent-view signals width height)
           :agent-config (agent-config-view signals width height)
           :processes    (processes-view signals width height)
           (tree-view signals width height)))))

;; ============================================================================
;; Key Handler
;; ============================================================================

(defn- next-view-mode [mode]
  (case mode
    :tree      :processes
    :processes :tree
    :room      :tree
    :tree))

(defn- open-agent-chat!
  "Open (or reuse) the per-agent DM Room for `agent-id`, ensure the agent is
   joined, start the room-bus watch, and enter the :room view. Shared by the
   :tree view's agent-enter action and the legacy :agents view.

   A chat with an agent IS a room: input posts into it as the `:local`
   user-actor (see the :room enter handler), the agent rehydrates its context
   from the room log and replies into the room, and the watcher re-renders.
   No session chat-ctx, no dispatch!, no response-sink."
  [daemon signals agent-id]
  (binding [ec/*execution-context* (:execution-ctx daemon)]
    (when-let [room (daemon/ensure-agent-room! daemon agent-id)]
      ;; The room mirror follows :current-room — just point it at the room.
      ;; Addressing is room-derived (room-target), so no agent selection needed.
      (reset! (:current-room signals) (:id room))
      (reset! (:input signals)
              (ti/text-input-state :prompt "» "
                                   :placeholder (str "Message " (name agent-id) "…")))
      (reset! (:scroll signals) 0)
      (reset! (:view-mode signals) :room))))

(defn- open-agent-config!
  "Enter the agent-config view for `agent-id` (fresh field selection)."
  [signals agent-id]
  (reset! (:config-agent signals) agent-id)
  (reset! (:config-field-idx signals) 0)
  (reset! (:config-editing? signals) false)
  (reset! (:config-confirm signals) nil)
  (reset! (:view-mode signals) :agent-config))

(defn- edit-prompt-in-editor!
  "Suspend the TUI, open $EDITOR on the agent's current system prompt (dumped to a
   tmpfile), then persist the edited text back to the actor row. The prompt lives
   in the DB (dvergr.agent.persona) — the tmpfile is a throwaway editing surface.
   No-op if the controller exposes no :with-suspended (headless)."
  [daemon controller id]
  (when-let [suspend (:with-suspended controller)]
    (let [a   (binding [ec/*execution-context* (:execution-ctx daemon)] (ops/get-agent id))
          tmp (java.io.File/createTempFile (str "dvergr-prompt-" (name id) "-") ".md")]
      (spit tmp (or (:prompt a) ""))
      (suspend
       (fn []
         (try
           (let [editor (or (System/getenv "EDITOR") (System/getenv "VISUAL") "vi")]
             (-> (ProcessBuilder. ^java.util.List [editor (.getPath tmp)])
                 (.inheritIO) (.start) (.waitFor)))
           (binding [ec/*execution-context* (:execution-ctx daemon)]
             (ops/update-agent! id {:prompt (slurp tmp)}))
           (finally (.delete tmp))))))))

(defn- selected-room
  "The live discourse Room for the selected tree node (room or fork), or nil —
   the tree node only carries data, so resolve the real Room from the registry."
  [daemon sel]
  (when-let [id (:id (or (:room sel) (:fork sel)))]
    (binding [ec/*execution-context* (:execution-ctx daemon)]
      (rreg/lookup id))))

(defn- run-fork-action!
  "Execute a confirmed fork action (:merge or :discard) and stash a result
   message in :fork-confirm for the footer. Invalidates the stats cache so the
   tree refreshes."
  [daemon signals action room]
  (let [res (binding [ec/*execution-context* (:execution-ctx daemon)]
              (case action
                :merge   (forks/merge! room)
                :discard (forks/discard! room)))
        msg (cond
              (:ok? res) (case action
                           :merge   (str "✓ merged into " (or (:parent-slug res) "parent"))
                           :discard "✓ fork discarded")
              :else      (str "✗ " (name action) " failed: " (:error res)))]
    (reset! room-stats-cache {})
    (reset! (:fork-confirm signals) {:msg msg})))

(defn- dashboard-on-key
  "Handle key events for the dashboard. `controller-atom` holds the spindel-tui
   controller (for runtime capabilities like mouse). Room input is posted
   straight into the current room via `room-post!` (canonical addressing)."
  [daemon signals {:keys [key] :as event} controller-atom]
  (let [mode @(:view-mode signals)
        ;; /-popup open? (only in the room view, while typing a command name)
        menu (when (= mode :room) (command-menu (ti/value @(:input signals))))]
    (cond
      ;; /-popup navigation/completion — BEFORE the global Tab/quit/enter
      ;; handlers. ↑/↓ move the selection; Tab AND Enter COMPLETE the selected
      ;; command into the input (they do NOT send — like codex/claude-code, you
      ;; press Enter again, with the popup now closed, to actually run it).
      (and menu (#{:up :down "tab" "enter"} key))
      (let [n   (count menu)
            idx (max 0 (min (or (some-> (:cmd-idx signals) deref) 0) (dec n)))]
        (case key
          :up   (reset! (:cmd-idx signals) (mod (dec idx) n))
          :down (reset! (:cmd-idx signals) (mod (inc idx) n))
          (do (reset! (:input signals)                    ; "tab" or "enter"
                      (ti/set-value @(:input signals)
                                    (str "/" (:name (nth menu idx)) " ")))
              (reset! (:cmd-idx signals) 0))))

      ;; Quit
      (= key "ctrl+c")
      :quit

      ;; Switch view
      (= key "tab")
      (swap! (:view-mode signals) next-view-mode)

      ;; Ctrl+O — toggle mouse reporting. ON = wheel scroll; OFF = the native
      ;; terminal gets the mouse back so the user can select + copy text.
      (= key "ctrl+o")
      (let [on? (swap! (:mouse? signals) not)]
        (when-let [set-mouse! (:set-mouse! @controller-atom)]
          (set-mouse! on?)))

      ;; Tree view: drive the spindel-tui tree component
      (= mode :tree)
      (cond
        ;; `n` — create a new room. Switch to a small create-room input
        ;; mode; on Enter we call rooms/create-room! against the daemon's
        ;; chat-db conn.
        (= key "n")
        (do
          (reset! (:input signals)
                  (ti/text-input-state :prompt "new room slug: "
                                       :placeholder "my-room"))
          (reset! (:view-mode signals) :create-room))

        ;; `a` — create a new agent (id prompt → its config view).
        (= key "a")
        (do
          (reset! (:input signals)
                  (ti/text-input-state :prompt "new agent id: "
                                       :placeholder "scribe"))
          (reset! (:view-mode signals) :create-agent))

        ;; `c` — chat with the selected agent (skip the config view).
        (= key "c")
        (let [sel (tree-c/selected-node @(:tree signals))]
          (when (and (= :agent (:kind sel)) (:id (:agent sel)))
            (open-agent-chat! daemon signals (:id (:agent sel)))))

        ;; A pending fork merge/discard confirmation captures keys first.
        (and (some? @(:fork-confirm signals)) (:action @(:fork-confirm signals)))
        (let [{:keys [action room]} @(:fork-confirm signals)]
          (cond
            (= key "y") (run-fork-action! daemon signals action room)
            (#{"n" "esc" "escape"} key) (reset! (:fork-confirm signals) nil)
            :else nil))

        ;; Any key dismisses a result toast.
        (and (some? @(:fork-confirm signals)) (:msg @(:fork-confirm signals)))
        (reset! (:fork-confirm signals) nil)

        ;; `f` — fork the selected room/fork into an isolated branch (:ctx),
        ;; so it can be merged or discarded later.
        (= key "f")
        (when-let [room (selected-room daemon (tree-c/selected-node @(:tree signals)))]
          (binding [ec/*execution-context* (:execution-ctx daemon)]
            (forks/fork! room))
          (reset! room-stats-cache {}))

        ;; `m` — merge the selected FORK back into its parent. Pressing `m` IS the
        ;; decision to merge, so a real (:ctx) fork goes straight to
        ;; `reconcile-merge!`: a clean identity-keyed union when there are no
        ;; conflicts, or an agent reconciliation of the genuinely-clashing fields.
        ;; It never holds/discards — discard has its own key (`x`). Async so any
        ;; reconciliation LLM call doesn't block the UI. A :none (conversational)
        ;; fork is just a log append → confirm.
        (= key "m")
        (let [room (selected-room daemon (tree-c/selected-node @(:tree signals)))]
          (when (forks/fork? room)
            (if (forks/ctx-fork? room)
              (do
                (reset! (:fork-confirm signals) {:msg (str "⟳ merging " (:slug room) "…")})
                (future
                  (binding [ec/*execution-context* (:execution-ctx daemon)]
                    (let [res (try (forks/reconcile-merge! room)
                                   (catch Throwable t {:ok? false :error (.getMessage t)}))
                          msg (cond
                                (and (:ok? res) (:reconciled res))
                                (str "✓ reconciled " (:slug room) " · "
                                     (:reconciled res) " field conflict(s) resolved")
                                (:ok? res) (str "✓ merged " (:slug room))
                                :else      (str "merge failed: " (:error res)))]
                      (reset! room-stats-cache {})
                      (reset! (:fork-confirm signals) {:msg msg})))))
              (reset! (:fork-confirm signals)
                      {:action :merge :room room
                       :label (str "Merge " (:slug room) " (conversational) into parent?  y / n")}))))

        ;; `x` — discard the selected FORK (deletes its branch) (confirm).
        (= key "x")
        (let [room (selected-room daemon (tree-c/selected-node @(:tree signals)))]
          (when (forks/fork? room)
            (reset! (:fork-confirm signals)
                    {:action :discard :room room
                     :label (str "Discard " (:slug room) " — deletes its branch.  y / n")})))

        ;; `d` — delete the currently-selected room (shared op).
        (= key "d")
        (let [sel (tree-c/selected-node @(:tree signals))]
          (when (and sel (or (= :room (:kind sel)) (= :fork (:kind sel))))
            (let [room (or (:room sel) (:fork sel))]
              (binding [ec/*execution-context* (:execution-ctx daemon)]
                ((requiring-resolve 'dvergr.rooms/delete-room!) room))
              (reset! room-stats-cache {}))))

        :else
        (let [;; Forward navigation/toggle keys to the component
              _ (swap! (:tree signals) tree-c/handle-key event)
              ;; Decide what Enter does based on the selected node kind.
              sel (tree-c/selected-node @(:tree signals))]
          (cond
            ;; Enter on an agent opens its CONFIG (the overview) — chatting is
            ;; one key away (`c` here, or `c` from the config view).
            (and (= key "enter")
                 (= :agent (:kind sel))
                 (some? (:id (:agent sel))))
            (open-agent-config! signals (:id (:agent sel)))

            (and (= key "enter")
                 (or (= :room (:kind sel)) (= :fork (:kind sel))))
            (let [room (or (:room sel) (:fork sel))]
              ;; Addressing is room-derived (room-target): a 1-agent room (or a
              ;; fork of one) addresses its agent, a group broadcasts.
              ;; The room mirror follows :current-room.
              (reset! (:current-room signals) (:id room))
              (reset! (:input signals)
                      (ti/text-input-state :prompt "» " :placeholder "Message room…"))
              (reset! (:scroll signals) 0)
              (reset! (:view-mode signals) :room)))))

      ;; Create-room mode: text input → on Enter call rooms/create-room!
      (= mode :create-room)
      (cond
        (or (= key "escape") (= key "ctrl+c"))
        (do (reset! (:input signals) (ti/text-input-state :prompt "" :placeholder ""))
            (reset! (:view-mode signals) :tree))

        (= key "enter")
        (let [slug (str/trim (ti/value @(:input signals)))]
          (when (seq slug)
            (binding [ec/*execution-context* (:execution-ctx daemon)]
              (when-let [dh-sys (ygg/system "dvergr-chat-db")]
                ((requiring-resolve 'dvergr.rooms/create-room!)
                 (:conn dh-sys)
                 {:title slug :slug slug :type :internal :ctx (:execution-ctx daemon)}))))
          (reset! (:input signals) (ti/text-input-state :prompt "" :placeholder ""))
          (reset! (:view-mode signals) :tree))

        :else
        (swap! (:input signals) #(ti/handle-key % event)))

      ;; Create-agent mode: text input → on Enter call ops/create-agent! and
      ;; drop into the new agent's config view.
      (= mode :create-agent)
      (cond
        (or (= key "escape") (= key "ctrl+c"))
        (do (reset! (:input signals) (ti/text-input-state :prompt "" :placeholder ""))
            (reset! (:view-mode signals) :tree))

        (= key "enter")
        (let [id (str/trim (ti/value @(:input signals)))]
          (reset! (:input signals) (ti/text-input-state :prompt "" :placeholder ""))
          (if (seq id)
            (do ;; provision = create the row + persona AND bring it online (join
                ;; the global room) so it shows in the tree + is chattable.
              (daemon/provision-agent! daemon {:id (keyword id)})
              (open-agent-config! signals (keyword id)))
            (reset! (:view-mode signals) :tree)))

        :else
        (swap! (:input signals) #(ti/handle-key % event)))

      ;; Agent-config mode: navigate fields, inline-edit scalars, chat, delete.
      (= mode :agent-config)
      (let [id       @(:config-agent signals)
            idx      @(:config-field-idx signals)
            nf       (count agent-cfg-fields)
            editing? @(:config-editing? signals)
            confirm  @(:config-confirm signals)]
        (cond
          ;; delete confirmation captures keys first
          confirm
          (cond
            (= key "y")
            (do ;; the op stops the live participant then retracts the row+persona
                ;; — one definition of "delete an agent", shared with web + MCP.
              (dops/invoke daemon :agent/delete {:id (name id)})
              (reset! room-stats-cache {})
              (reset! (:config-confirm signals) nil)
              (reset! (:config-agent signals) nil)
              (reset! (:view-mode signals) :tree))
            (#{"n" "esc" "escape"} key) (reset! (:config-confirm signals) nil)
            :else nil)

          ;; editing a field — pickers (model/provider) vs free-text
          editing?
          (let [{:keys [k]} (nth agent-cfg-fields idx)
                a    (binding [ec/*execution-context* (:execution-ctx daemon)] (ops/get-agent id))
                opts (field-options a k)]
            (if opts
              ;; picker keys
              (let [n (count opts) p @(:config-pick-idx signals)]
                (cond
                  (= key "escape") (reset! (:config-editing? signals) false)
                  (or (= key "j") (= key :down)) (reset! (:config-pick-idx signals) (min (dec n) (inc p)))
                  (or (= key "k") (= key :up))   (reset! (:config-pick-idx signals) (max 0 (dec p)))
                  (= key "enter")
                  (let [v (:value (nth opts (max 0 (min (dec n) p))))]
                    (binding [ec/*execution-context* (:execution-ctx daemon)]
                      (ops/update-agent! id {k v}))
                    (reset! (:config-editing? signals) false))
                  :else nil))
              ;; free-text keys
              (cond
                (= key "escape") (reset! (:config-editing? signals) false)
                (= key "enter")
                (do (binding [ec/*execution-context* (:execution-ctx daemon)]
                      (ops/update-agent! id {k (parse-cfg-field k (ti/value @(:input signals)))}))
                    (reset! (:config-editing? signals) false))
                :else (swap! (:input signals) #(ti/handle-key % event)))))

          ;; navigating
          :else
          (cond
            (= key "escape")
            (do (reset! (:config-agent signals) nil)
                (reset! (:view-mode signals) :tree))
            (or (= key "j") (= key :down)) (reset! (:config-field-idx signals) (mod (inc idx) nf))
            (or (= key "k") (= key :up))   (reset! (:config-field-idx signals) (mod (dec idx) nf))
            (or (= key "e") (= key "enter"))
            (let [a   (binding [ec/*execution-context* (:execution-ctx daemon)] (ops/get-agent id))
                  {:keys [k]} (nth agent-cfg-fields idx)
                  opts (field-options a k)]
              (if opts
                ;; open the picker at the current value's index
                (let [cur (get a k)
                      cur-i (or (some (fn [[i o]] (when (= (:value o) cur) i))
                                      (map-indexed vector opts))
                                0)]
                  (reset! (:config-pick-idx signals) cur-i)
                  (reset! (:config-editing? signals) true))
                ;; free-text edit
                (do (reset! (:input signals)
                            (ti/set-value (ti/text-input-state :prompt "» ")
                                          (cfg-field-display a k)))
                    (reset! (:config-editing? signals) true))))
            (= key "p") (edit-prompt-in-editor! daemon @controller-atom id)
            (= key "c") (open-agent-chat! daemon signals id)
            (= key "x") (reset! (:config-confirm signals)
                                (str "Delete agent " (name id)
                                     "? removes its row + stored prompt.  y / n"))
            :else nil)))

      ;; Room view: typing + posting + scrolling
      (= mode :room)
      (cond
        (= key "escape")
        (let [room-id @(:current-room signals)]
          (if (and room-id (daemon/room-turn-running? room-id))
            ;; A turn is in flight → Esc cancels it (stay in the room). The
            ;; turn loop bails at the next boundary + the in-flight SSE aborts;
            ;; the agent posts "[cancelled by user]" which the watcher renders.
            (binding [ec/*execution-context* (:execution-ctx daemon)]
              (daemon/cancel-room-turn! daemon room-id))
            ;; Idle → Esc leaves the room (the mirror clears :room-messages
            ;; when :current-room becomes nil).
            (do (reset! (:current-room signals) nil)
                (reset! (:input signals) (ti/text-input-state :prompt "" :placeholder ""))
                (reset! (:view-mode signals) :tree))))

        (= key "page_up")
        (swap! (:scroll signals) + 10)

        (= key "page_down")
        (swap! (:scroll signals) #(max 0 (- % 10)))

        ;; Mouse wheel — same :scroll signal, a few lines per notch. (Clamp at
        ;; the bottom; the render's max-scroll clamps the top.)
        (= key :scroll-up)
        (swap! (:scroll signals) + 3)

        (= key :scroll-down)
        (swap! (:scroll signals) #(max 0 (- % 3)))

        ;; Ctrl+T — toggle the trace/verbose view (reasoning + tool inputs).
        (= key "ctrl+t")
        (when (:trace? signals) (swap! (:trace? signals) not))

        ;; Ctrl+E — show/hide the per-AGENT context bars (shown by default).
        (= key "ctrl+e")
        (when (:ctx-expanded? signals) (swap! (:ctx-expanded? signals) not))

        (= key "enter")
        (let [text (str/trim (ti/value @(:input signals)))]
          (when (seq text)
            (binding [ec/*execution-context* (:execution-ctx daemon)]
              (cond
                ;; Slash command — dispatch through the unified registry. If it
                ;; handles the input, we don't post it as a normal message.
                (commands/command-input? text)
                (commands/execute! text (command-host-ctx daemon signals))

                ;; Chat: post into the current room, addressed by the canonical
                ;; room rule (room-post! → discourse/room-target). A DM (or a fork
                ;; of one) addresses its agent; a group broadcasts. No UI-state
                ;; addressing, no per-room adapter indirection.
                :else
                (when-let [room (rreg/lookup @(:current-room signals))]
                  (room-post! signals room text)))))
          (reset! (:scroll signals) 0)
          (swap! (:input signals) ti/reset))

        :else
        (swap! (:input signals) #(ti/handle-key % event)))

      ;; Processes view — list active processes, abort / continue / extend
      ;; the focused one. Navigation: j/k, action keys mirror the help line.
      (= mode :processes)
      (let [cctx  @(:chat-ctx signals)
            procs (when cctx
                    (->> (proc/list-processes cctx)
                         (filter #(#{:running :awaiting-decision} (:status %)))
                         (sort-by :started-at)))
            idx   @(:proc-idx signals)
            focused (when (seq procs) (nth procs (min idx (dec (count procs))) nil))]
        (cond
          (or (= key "j") (= key :down))
          (swap! (:proc-idx signals)
                 #(min (max 0 (dec (count procs))) (inc %)))

          (or (= key "k") (= key :up))
          (swap! (:proc-idx signals) #(max 0 (dec %)))

          (and focused (= key "a"))
          (binding [ec/*execution-context* (:execution-ctx daemon)]
            (proc/directive! cctx (:id focused)
                             {:type :abort :reason "user aborted via TUI"}))

          (and focused (= key "c"))
          (binding [ec/*execution-context* (:execution-ctx daemon)]
            (proc/directive! cctx (:id focused) {:type :continue}))

          (and focused (= key "e"))
          (binding [ec/*execution-context* (:execution-ctx daemon)]
            (proc/directive! cctx (:id focused)
                             {:type :extend-budget :dollars 0.10}))))

      ;; Sessions view - no special keys
      :else nil)))

;; ============================================================================
;; Public API
;; ============================================================================

(defn- start-spinner-driver!
  "Tick the spinner signal every 80ms while :status is :running.

   Animation drivers must live OUTSIDE the render fn in the spin-native
   model — calling (swap! (:spinner signals) ...) from inside the
   render fn would re-fire the render-spin unboundedly (the spinner
   signal is tracked). This thread does the swaps from a non-tracked
   thread; the render-spin picks them up reactively."
  [{:keys [ctx running signals]}]
  (doto (Thread.
         ^Runnable
         (fn []
           (binding [ec/*execution-context* ctx]
             (while @running
               (try
                 (when (= :running @(:status signals))
                   (swap! (:spinner signals) spinner/tick))
                 (Thread/sleep 80)
                 (catch InterruptedException _ nil)))))
         "dvergr-tui-spinner")
    (.setDaemon true)
    (.start)))

(defn- start-tree-adapter!
  "Track the dvergr.rooms.tree signal and push updates into the TUI
   tree-component state. Lives as a spin so re-runs are driven by the
   engine drain whenever the rooms tree changes.

   Auto-expands the top-level :agents group on first build so the user
   sees the agent list immediately on startup."
  [{:keys [ctx signals]} rooms-tree-signal]
  (when rooms-tree-signal
    (binding [ec/*execution-context* ctx]
      (let [first-build? (atom true)
            s (spin
               (let [iv-val (track rooms-tree-signal)
                     tree-value (iv/get-new iv-val)
                     nodes (adapt-tree-value tree-value)]
                 (swap! (:tree signals)
                        (fn [state]
                          (let [next-state (tree-c/set-roots state nodes)]
                            (if @first-build?
                              (do (reset! first-build? false)
                                  (tree-c/set-expanded next-state #{[:group :agents]}))
                              next-state))))
                 ::tree-synced))]
        (s (fn [_] nil)
           (fn [err]
             (binding [*out* *err*]
               (println "tree-adapter error:" err))))))))

(defn- start-room-mirror!
  "One spin that mirrors the current room's shared message signal
   (dvergr.rooms.messages) into the :room-messages signal the render-spin
   tracks. Tracking :current-room re-runs it on room switch (auto-re-target);
   tracking that room's signal re-runs it on each new message. No per-client
   bus subscription, no store re-read, no teardown — entering a room is just
   `(reset! :current-room id)`. Also clears the optimistic spinner when an
   agent reply (not the local echo, not tool activity) becomes the latest msg."
  [{:keys [ctx signals]}]
  ;; Clear the optimistic spinner when the addressed agent's TURN ENDS — drive
  ;; it off the turn lifecycle, not reply-arrival, so a silent ([SKIP]) turn
  ;; (which posts no message) still clears it instead of spinning forever.
  (turn/watch-room-turns! ::spinner
                          (fn [rid running?]
                            (when (and (not running?)
                                       (= rid (some-> (:current-room signals) deref))
                                       (:status signals))
                              (binding [ec/*execution-context* ctx]
                                (reset! (:status signals) :idle)))))
  (binding [ec/*execution-context* ctx]
    (let [s (spin
             (let [rid  (iv/get-new (track (:current-room signals)))
                   room (when rid (rreg/lookup rid))
                   msgs (if room
                          (vec (iv/get-new (track (rmsg/messages-signal room))))
                          [])]
               (reset! (:room-messages signals) msgs)
               (when (:status signals)
                 (when-let [m (last msgs)]
                   (when (and (not= :local (:from m)) (not= :tool (:role m)))
                     (reset! (:status signals) :idle))))
               ::room-synced))]
      (s (fn [_] nil)
         (fn [err]
           (binding [*out* *err*]
             (println "room-mirror error:" err)))))))

(defn run
  "Start the TUI dashboard. Returns the controller and blocks until the
   user quits.

   The daemon must already be started. We share the daemon's execution
   context so registry/sessions/stats lookups read the same ctx the
   daemon writes to.

   Re-render trigger for the :room view: one room mirror (start-room-mirror!)
   tracks :current-room and that room's shared message signal
   (dvergr.rooms.messages), copying it into the :room-messages signal the
   render-spin tracks. No per-client bus subscription, no store re-read."
  [daemon]
  (let [ctx             (:execution-ctx daemon)
        ;; Stash ctx for the stats helpers (room/tree/system strips), which
        ;; run during render where no ctx may be bound.
        _               (reset! tui-ctx-ref ctx)
        ;; Ensure peer-bus exists; idempotent if already created.
        _               (peer-bus/create! ctx)
        ;; Boot the rooms-tree subsystem. Now reads from the unified
        ;; room registry — no datahike conn parameter needed.
        rooms-tree-ctrl (rooms-tree/start! {:ctx ctx :poll-ms 1000})
        ;; Holds the controller so the mouse-toggle key (handled in
        ;; dashboard-on-key, which only gets `signals`) can reach :set-mouse!.
        controller-atom (atom nil)
        controller
        (tui/start!
         {:execution-context ctx
           ;; mouse? true → wheel scroll works out of the box; Ctrl+O toggles it
           ;; off so the user can select/copy text with the native terminal.
          :mouse?  true
          :signals {:view-mode      :tree
                    :tree           (tree-c/tree-state)
                    :chat-ctx       nil
                    :current-room   nil
                    :room-messages  []
                    :trace?         false
                    :ctx-expanded?  true
                    :mouse?         true
                    :proc-idx       0
                    :cmd-idx        0
                    :fork-confirm   nil
                    :config-agent     nil
                    :config-field-idx 0
                    :config-editing?  false
                    :config-pick-idx  0
                    :config-confirm   nil
                    :input          (ti/text-input-state :prompt "" :placeholder "Message agent...")
                    :scroll         0
                    :status         :idle
                    :spinner        (spinner/spinner-state :dots :label "Thinking...")}
          :render (fn [signals width height]
                    (dashboard-view signals width height))
          :on-key (fn [signals event]
                    (dashboard-on-key daemon signals event controller-atom))})
        _ (reset! controller-atom controller)]

    ;; Spinner animation driver — side thread, NOT inside render.
    (start-spinner-driver! controller)

    ;; Room mirror: follow :current-room + its shared message signal into
    ;; :room-messages (the render-spin tracks that). One spin, auto-re-targets.
    (start-room-mirror! controller)

    ;; Tree adapter: keep the spindel-tui tree-component state in sync
    ;; with dvergr.rooms.tree's signal. Single source of truth shared
    ;; with the web dashboard.
    (start-tree-adapter! controller (:tree-signal rooms-tree-ctrl))

    ;; Stash the rooms-tree controller on the TUI controller so the
    ;; caller can stop it cleanly.
    (alter-meta! (:running controller)
                 assoc :rooms-tree-controller rooms-tree-ctrl)

    ;; Block until the user quits.
    ((:await-quit controller))
    ((:stop! rooms-tree-ctrl))
    controller))
