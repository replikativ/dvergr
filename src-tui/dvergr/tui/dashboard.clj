(ns dvergr.tui.dashboard
  "TUI dashboard for the dvergr daemon.

   Provides three views:
   - :agents   — List registered agents with status indicators
   - :chat     — Chat with a selected agent (scrollable, markdown)
   - :sessions — List active sessions

   The session's chat-ctx :messages-signal is the single source of truth
   for chat history — the view derefs it directly each render. The daemon
   writes user/tool/assistant messages there during a turn; the response
   sink only flips :status back to :idle to trigger a re-render."
  (:require [org.replikativ.spindel-tui.tui :as tui]
            [org.replikativ.spindel-tui.style.core :as s]
            [org.replikativ.spindel-tui.style.width :as w]
            [org.replikativ.spindel-tui.components.text-input :as ti]
            [org.replikativ.spindel-tui.components.spinner :as spinner]
            [org.replikativ.spindel-tui.markdown :as md]
            [dvergr.daemon :as daemon]
            [dvergr.registry :as registry]
            [dvergr.sessions :as sessions]
            [dvergr.chat.context :as chat-ctx]
            [dvergr.stats :as stats]
            [org.replikativ.spindel.engine.core :as ec]
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

(defn- box-line
  "Create a bordered line: │ content ... │"
  [content inner-width]
  (let [cw (s/string-width content)
        padding (max 0 (- inner-width cw))]
    (str "│ " content (apply str (repeat padding " ")) " │")))

(defn- header-line [title width]
  (let [tlen (count title)]
    (str "╭─ " (s/render (s/style :fg s/cyan :bold true) title) " "
         (apply str (repeat (max 0 (- width tlen 6)) "─")) "╮")))

(defn- footer-line [width]
  (str "╰" (apply str (repeat (- width 2) "─")) "╯"))

(defn- separator-line [width]
  (str "├" (apply str (repeat (- width 2) "─")) "┤"))

(defn- status-indicator [status]
  (case status
    :running (s/render (s/style :fg s/green :bold true) "●")
    :idle    (s/render (s/style :fg s/yellow) "○")
    :stopped (s/render (s/style :fg s/red) "✕")
    (s/render (s/style :fg (s/ansi256 240)) "?")))

;; ============================================================================
;; REPL Evaluation
;; ============================================================================

(defonce ^:private eval-ns (atom (the-ns 'user)))

(defn- detect-input-type
  "Returns :repl if text looks like a Clojure expression, :chat otherwise."
  [text]
  (let [t (str/trim text)]
    (if (and (seq t) (#{\( \[ \{ \# \' \`} (first t)))
      :repl
      :chat)))

(defn- eval-clojure
  "Evaluate Clojure code in-process. Returns {:result :output :error}."
  [code]
  (let [out (java.io.StringWriter.)]
    (try
      (let [form (binding [*ns* @eval-ns] (read-string code))
            result (binding [*ns* @eval-ns *out* out *err* out]
                     (let [r (eval form)]
                       (reset! eval-ns *ns*)
                       r))]
        {:result (pr-str result)
         :output (let [s (str out)] (when (seq s) s))
         :error  nil})
      (catch Throwable e
        {:result nil
         :output (let [s (str out)] (when (seq s) s))
         :error  (str (.getSimpleName (class e)) ": " (.getMessage e))}))))

;; ============================================================================
;; Agents View
;; ============================================================================

(defn- format-cost
  "Format a cost in dollars: '$0.027', '$1.23', or '—' if nil."
  [cost-dollars]
  (if cost-dollars
    (format "$%.3f" cost-dollars)
    "—"))

(defn- agents-view
  "Render the agents list view with per-agent stats (cost, last-active, summary)."
  [signals width height]
  (let [agents (registry/list-agents)
        selected @(:selected-idx signals)
        inner-w (- width 4)
        ;; Each agent renders as 2–3 lines: header + stats [+ summary]
        items (mapcat
                (fn [idx ag]
                  (let [id     (name (:id ag))
                        status (:status ag)
                        desc   (or (:description ag) "")
                        st     (stats/get-stats (:id ag))
                        sel?   (= idx selected)
                        prefix (if sel? (s/render (s/style :fg s/cyan :bold true) "> ") "  ")
                        id-str (if sel? (s/render (s/style :fg s/cyan :bold true) id) id)
                        ;; Line 1: status indicator + name + description
                        line1  (box-line
                                 (str prefix (status-indicator status) " " id-str
                                      (when (seq desc)
                                        (str "  " (s/render (s/style :fg (s/ansi256 240)) desc))))
                                 inner-w)
                        ;; Line 2: cost · last-active
                        cost-str (format-cost (:cost-dollars st))
                        age-str  (or (:last-active-str st) "—")
                        stats-dim (s/style :fg (s/ansi256 244))
                        line2  (box-line
                                 (str "    "
                                      (s/render (s/style :fg (s/ansi256 214)) cost-str)
                                      (s/render stats-dim (str " · " age-str)))
                                 inner-w)
                        ;; Line 3: 1-sentence LLM summary (only if available)
                        summary (:summary st)
                        line3  (when summary
                                 (let [max-len (- inner-w 4)
                                       short (if (> (count summary) max-len)
                                               (str (subs summary 0 (- max-len 1)) "…")
                                               summary)]
                                   (box-line
                                     (str "    " (s/render (s/style :fg (s/ansi256 240) :italic true) short))
                                     inner-w)))]
                    (cond-> [line1 line2]
                      line3 (conj line3))))
                (range) agents)
        available-h (- height 5)
        visible (take available-h items)
        empty-count (max 0 (- available-h (count visible)))]
    (concat
      [(header-line "Agents" width)]
      (if (seq visible)
        visible
        [(box-line (s/render (s/style :fg (s/ansi256 240)) "No agents registered") inner-w)])
      (repeat empty-count (box-line "" inner-w))
      [(separator-line width)
       (pad-line (str "│ " (s/render (s/style :fg (s/ansi256 240))
                                     "Tab:view  Enter:chat  j/k:nav  Ctrl+C:quit")
                      (apply str (repeat (max 0 (- inner-w 44)) " ")) " │")
                 width)
       (footer-line width)])))

;; ============================================================================
;; Chat View
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

(defn- normalize-msg
  "Normalize a message entry from either the chat-ctx (namespaced
   `:message/*` keys) or in-flight TUI-side (un-namespaced) into a
   single shape the renderer can dispatch on."
  [msg]
  (cond-> msg
    (and (nil? (:role msg))    (:message/role msg))    (assoc :role    (:message/role msg))
    (and (nil? (:content msg)) (:message/content msg)) (assoc :content (:message/content msg))))

(defn- render-message
  "Render a single history entry into lines.
   Handles :type :repl, :type :tool, and plain {:role ...} chat messages.
   Accepts both raw and chat-ctx-normalized message shapes."
  [msg inner-width]
  (let [msg (normalize-msg msg)]
  (case (:type msg)
    ;; REPL evaluation entry
    :repl
    (let [input   (:input msg)
          output  (:output msg)
          error?  (:error? msg)
          stdout  (:stdout msg)
          input-line (str (s/render (s/style :fg s/yellow :bold true) "λ ") input)
          stdout-lines (when stdout
                         (mapcat (fn [l]
                                   (map #(s/render (s/style :fg (s/ansi256 240)) (str "  " %))
                                        (wrap-text l (- inner-width 2))))
                                 (str/split-lines stdout)))
          output-lines (when output
                         (mapcat (fn [l]
                                   (map #(if error?
                                           (s/render (s/style :fg s/red) %)
                                           (s/render (s/style :fg s/white) %))
                                        (wrap-text l (- inner-width 2))))
                                 (str/split-lines output)))]
      (concat [input-line] stdout-lines output-lines [""]))

    ;; Tool call entry (shown inline in chat history)
    :tool
    (let [tool-name (:tool-name msg)
          result    (str (:result msg))
          header    (s/render (s/style :fg s/cyan) (str "  tool:" tool-name))
          preview   (if (> (count result) 160) (str (subs result 0 157) "...") result)
          result-lines (mapcat (fn [l]
                                 (map #(s/render (s/style :fg (s/ansi256 245)) (str "    " %))
                                      (wrap-text l (- inner-width 4))))
                               (str/split-lines preview))]
      (concat [header] result-lines [""]))

    ;; Default: plain chat message (:role :user / :assistant / :system)
    (let [role     (:role msg)
          content  (or (:content msg) "")
          role-str (case role
                     :user      (s/render (s/style :fg s/green :bold true) "You")
                     :assistant (s/render (s/style :fg s/cyan :bold true) "Agent")
                     (s/render (s/style :fg (s/ansi256 240)) (name (or role :unknown))))
          rendered (if (= role :assistant)
                     (try (md/render-inline content)
                          (catch Exception _ content))
                     content)
          text-lines (str/split-lines rendered)
          wrapped    (mapcat #(wrap-text % (- inner-width 2)) text-lines)]
      (concat [(str role-str ":")] (map #(str "  " %) wrapped) [""])))))

(defn- chat-view
  "Render the chat view.

   The chat-ctx's :messages-signal is the source of truth — derefed each
   render via `chat-ctx/get-messages` (which binds the chat-ctx's own
   spindel context internally)."
  [signals width height]
  (let [agent-id @(:selected-agent signals)
        cctx @(:chat-ctx signals)
        history (if cctx (chat-ctx/get-messages cctx) [])
        input-state @(:input signals)
        spinner-state @(:spinner signals)
        scroll @(:scroll signals)
        inner-w (- width 4)
        agent-name (if agent-id (name agent-id) "none")
        title (str "Chat — " agent-name)

        ;; Render all messages
        msg-lines (vec (mapcat #(render-message % inner-w) history))

        ;; Calculate visible area (leave room for header, input, help, footer)
        available-h (- height 7)
        total-lines (count msg-lines)

        ;; `scroll` = lines back from the bottom. 0 → newest (autoscroll
        ;; tail); page_up grows it (toward older history); page_down
        ;; shrinks toward 0. Clamp on display so an over-pressed PageUp
        ;; doesn't carry phantom value back through PageDown.
        max-scroll (max 0 (- total-lines available-h))
        effective-scroll (max 0 (min scroll max-scroll))
        ;; Keep the signal in sync so on-key sees the clamped value —
        ;; otherwise over-pressed PageUps build up as phantom history
        ;; the user has to PageDown back through later.
        _ (when (not= scroll effective-scroll)
            (reset! (:scroll signals) effective-scroll))
        offset (- max-scroll effective-scroll)
        visible-lines (->> msg-lines
                           (drop offset)
                           (take available-h))
        padded-count (max 0 (- available-h (count visible-lines)))

        ;; Input line
        input-view (ti/view input-state)

        ;; Spinner for thinking
        thinking? (= :running @(:status signals))
        spinner-line (when thinking?
                       (spinner/view spinner-state))]
    (concat
      [(header-line title width)]
      (map #(box-line % inner-w) visible-lines)
      (repeat padded-count (box-line "" inner-w))
      [(separator-line width)]
      [(box-line (or spinner-line "") inner-w)]
      [(box-line input-view inner-w)]
      [(separator-line width)
       (pad-line (str "│ " (s/render (s/style :fg (s/ansi256 240))
                                     "Tab:view  Enter:send  PgUp/Dn:scroll  Ctrl+C:quit")
                      (apply str (repeat (max 0 (- inner-w 50)) " ")) " │")
                 width)
       (footer-line width)])))

;; ============================================================================
;; Sessions View
;; ============================================================================

(defn- sessions-view
  "Render the sessions list view."
  [signals width height]
  (let [sess (sessions/list-sessions)
        inner-w (- width 4)
        items (map (fn [s]
                     (let [cid (str (:chat-id s))
                           aid (name (or (:agent-id s) :unknown))
                           user (get-in s [:user-info :username] "?")]
                       (box-line (str (s/render (s/style :fg s/yellow) cid)
                                      " -> "
                                      (s/render (s/style :fg s/cyan) aid)
                                      "  user:" user)
                                 inner-w)))
                   sess)
        available-h (- height 5)
        visible (take available-h items)
        empty-count (max 0 (- available-h (count visible)))]
    (concat
      [(header-line "Sessions" width)]
      (if (seq visible)
        visible
        [(box-line (s/render (s/style :fg (s/ansi256 240)) "No active sessions") inner-w)])
      (repeat empty-count (box-line "" inner-w))
      [(separator-line width)
       (pad-line (str "│ " (s/render (s/style :fg (s/ansi256 240))
                                     "Tab:view  Ctrl+C:quit")
                      (apply str (repeat (max 0 (- inner-w 22)) " ")) " │")
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
           :agents   (agents-view signals width height)
           :chat     (chat-view signals width height)
           :sessions (sessions-view signals width height)
           (agents-view signals width height)))))

;; ============================================================================
;; Key Handler
;; ============================================================================

(defn- next-view-mode [mode]
  (case mode
    :agents   :chat
    :chat     :sessions
    :sessions :agents
    :agents))

(defn- dashboard-on-key
  "Handle key events for the dashboard."
  [daemon signals {:keys [key] :as event}]
  (let [mode @(:view-mode signals)]
    (cond
      ;; Quit
      (= key "ctrl+c")
      :quit

      ;; Switch view
      (= key "tab")
      (swap! (:view-mode signals) next-view-mode)

      ;; Mode-specific handling
      (= mode :agents)
      (let [agents (registry/list-agents)
            agent-count (count agents)]
        (cond
          ;; Navigate
          (or (= key "j") (= key :down))
          (swap! (:selected-idx signals) #(min (dec agent-count) (inc %)))

          (or (= key "k") (= key :up))
          (swap! (:selected-idx signals) #(max 0 (dec %)))

          ;; Select agent and switch to chat. Eagerly create the
          ;; session so its chat-ctx (with :messages-signal) exists
          ;; before the user sends — the view derefs that signal as
          ;; the source of truth for chat history.
          (= key "enter")
          (when (pos? agent-count)
            (let [idx @(:selected-idx signals)
                  agent-id (:id (nth agents idx))
                  session (sessions/get-or-create-session!
                            :tui agent-id {:username "local"})]
              (reset! (:selected-agent signals) agent-id)
              (reset! (:chat-ctx signals) (:chat-ctx session))
              (reset! (:status signals) :idle)
              (reset! (:scroll signals) 0)
              (reset! (:view-mode signals) :chat)))))

      (= mode :chat)
      (cond
        ;; Scroll. `scroll` = lines back from the bottom; page_up moves
        ;; further into history, page_down moves toward newest. The view
        ;; clamps to [0, max-scroll] on display.
        (= key "page_up")
        (swap! (:scroll signals) + 10)

        (= key "page_down")
        (swap! (:scroll signals) #(max 0 (- % 10)))

        (= key "ctrl+u")
        (swap! (:scroll signals) + 5)

        (= key "ctrl+d")
        (swap! (:scroll signals) #(max 0 (- % 5)))

        ;; Send message or eval expression.
        ;;
        ;; All output (user msgs, REPL evals, assistant replies) flows
        ;; through the session's chat-ctx — the view picks it up via
        ;; (:messages-signal chat-ctx). The :status flip drives the
        ;; spinner AND triggers a TUI re-render via signal-values.
        (= key "enter")
        (let [val (ti/value @(:input signals))
              cctx @(:chat-ctx signals)]
          (when (and (not (str/blank? val)) cctx)
            (swap! (:input signals) ti/reset)
            (reset! (:scroll signals) 0)
            (case (detect-input-type val)
              ;; REPL expression — eval locally and record the
              ;; outcome as a :system message in chat-ctx so the
              ;; agent can reference it on the next turn. (The
              ;; chat-ctx schema only persists role+content, so we
              ;; encode the eval transcript as text.)
              :repl
              (let [{:keys [result error output]} (eval-clojure val)
                    body (str "REPL> " val "\n"
                              (when output (str output "\n"))
                              (if error
                                (str "Error: " error)
                                (str "=> " result)))]
                (chat-ctx/add-message! cctx {:role :system :content body}))

              ;; Chat message — daemon writes user + assistant turns
              ;; into the session's chat-ctx. We just flip the spinner
              ;; and dispatch.
              (do
                (reset! (:status signals) :running)
                (daemon/dispatch! daemon
                  {:chat-id :tui
                   :text    val
                   :from    {:username "local"}})))))

        ;; Text input
        :else
        (swap! (:input signals) #(ti/handle-key % event)))

      ;; Sessions view - no special keys
      :else nil)))

;; ============================================================================
;; Public API
;; ============================================================================

(defn run
  "Start the TUI dashboard, blocking the current thread.

   The daemon must already be started. We share the daemon's execution
   context so registry/sessions/stats lookups read the same ctx the
   daemon writes to.

   Re-render trigger: the chat-ctx's :messages-signal isn't in the TUI
   signal-map, so changes there don't directly tick the render loop.
   The :status signal is — flipping it back to :idle from the response
   sink forces signal-values to differ, which makes the loop re-render
   and pull fresh messages from chat-ctx."
  [daemon]
  (let [status-ref-atom (atom nil)]
    ;; Response sink: just flip status → triggers re-render; the
    ;; assistant's content is already in chat-ctx by the time we get here.
    (daemon/register-response-sink! daemon
      (fn [_agent-id _text]
        (when-let [status-ref @status-ref-atom]
          (binding [ec/*execution-context* (:execution-ctx daemon)]
            (reset! status-ref :idle)))))

    (tui/start!
      {:execution-context (:execution-ctx daemon)
       :signals {:view-mode      :agents
                 :selected-idx   0
                 :selected-agent nil
                 :chat-ctx       nil
                 :input          (ti/text-input-state :prompt "" :placeholder "Message agent...")
                 :scroll         0
                 :status         :idle
                 :spinner        (spinner/spinner-state :dots :label "Thinking...")}
       :view (fn [signals width height]
               (when (nil? @status-ref-atom)
                 (reset! status-ref-atom (:status signals)))
               ;; Tick spinner when running
               (when (= :running @(:status signals))
                 (swap! (:spinner signals) spinner/tick))
               (dashboard-view signals width height))
       :on-key (fn [signals event]
                 (dashboard-on-key daemon signals event))})))
