(ns dvergr.cli.view
  "Pure render for dvergr-cli. Given a signal-map, return seq of lines.

   Signal-map shape:
     :rooms         — atom {room-id {:messages [] :draft \"\" :input \"\"}}
     :active-room   — atom of currently focused room-id
     :status        — atom :idle | :generating | :error
     :budget-used   — atom (microdollars used)
     :budget-total  — atom (microdollars total)

   Layout: a fixed-width left sidebar lists known rooms; the right
   pane shows the active room's messages and input."
  (:require [clojure.string :as str]))

(def ^:private SIDEBAR-WIDTH 16)

(defn- fmt-budget
  [used total]
  (let [m->d (fn [m] (format "$%.4f" (/ m 1000000.0)))]
    (str (m->d used) " / " (m->d total))))

(defn- fmt-elapsed
  [ms]
  (cond
    (< ms 1000)   (str ms "ms")
    (< ms 60000)  (format "%.1fs" (/ ms 1000.0))
    :else         (format "%dm%ds" (quot ms 60000) (mod (quot ms 1000) 60))))

(defn- fmt-cost-micros
  [m]
  (cond
    (zero? m) "$0"
    (< m 1000) (str m "μ$")
    :else      (format "$%.4f" (/ m 1000000.0))))

(defn- fmt-tokens
  [by-type]
  (let [in  (or (:input-tokens by-type) (:token-input by-type) 0)
        out (or (:output-tokens by-type) (:token-output by-type) 0)]
    (when (or (pos? in) (pos? out))
      (str in "→" out "tok"))))

(defn- last-turn-line
  "Format the last :telemetry/turn-complete payload."
  [tc]
  (when (seq tc)
    (let [p (:payload tc)
          parts (->> [(some-> p :elapsed-ms fmt-elapsed)
                      (fmt-tokens (:tokens-by-type p))
                      (some-> p :cost-microdollars fmt-cost-micros)]
                     (remove nil?)
                     (remove #(= "$0" %)))]
      (when (seq parts) (str "last " (str/join " " parts))))))

(defn- header
  [signals width]
  (let [active     @(:active-room signals)
        rooms      @(:rooms signals)
        status     @(:status signals)
        used       (or (get-in rooms [active :budget-used])
                       (some-> signals :budget-used deref))
        total      (or (get-in rooms [active :budget-total])
                       (some-> signals :budget-total deref))
        last-turn  (get-in rooms [active :last-turn])
        in-tool    (get-in rooms [active :in-flight-tool])
        status-str (case status
                     :generating "● generating"
                     :error "✕ error"
                     :idle "○ idle"
                     (str status))
        left  (str " dvergr-cli · room: " (name active)
                   (when (> (count rooms) 1) (str " (of " (count rooms) ")")))
        ;; Compose right-hand chips, drop ones that don't apply
        chips (->> [(when (and used total) (fmt-budget used total))
                    (last-turn-line last-turn)
                    (when in-tool (str "tool: " (name (:name in-tool))
                                       " (" (fmt-elapsed (- (System/currentTimeMillis)
                                                            (:started-at in-tool))) ")"))
                    status-str]
                   (remove nil?)
                   (remove #(when (string? %) (str/blank? %))))
        right (str (str/join " · " chips) " ")
        pad   (max 1 (- width (count left) (count right)))]
    (str left (apply str (repeat pad " ")) right)))

(defn- footer
  [signals width]
  (let [active   @(:active-room signals)
        rooms    @(:rooms signals)
        input    (get-in rooms [active :input] "")
        cursor   "▏"
        prompt   "> "
        prefix   (str " " prompt input cursor)
        hint     " Enter: send · Ctrl-N: new room · Ctrl-Tab: cycle · Ctrl-C/q: quit "
        pad      (max 1 (- width (count prefix) (count hint)))]
    (str prefix (apply str (repeat pad " ")) hint)))

(defn- render-message-line
  "Format a single message into one or more lines wrapped to `width`."
  [msg width]
  (let [role    (or (:role msg) (:message/role msg))
        content (or (:content msg) (:message/content msg))
        sender  (case role
                  :user "you  > "
                  "user" "you  > "
                  :assistant "agent> "
                  "assistant" "agent> "
                  :system "sys  > "
                  "system" "sys  > "
                  (str (name (or role :?)) "> "))
        body    (if (string? content)
                  content
                  (str content))
        wrap-w  (max 10 (- width 8))]
    (->> (str/split-lines body)
         (mapcat (fn [line]
                   (if (<= (count line) wrap-w)
                     [line]
                     (loop [s line acc []]
                       (if (<= (count s) wrap-w)
                         (conj acc s)
                         (recur (subs s wrap-w)
                                (conj acc (subs s 0 wrap-w))))))))
         (map-indexed (fn [i line]
                        (if (zero? i)
                          (str sender line)
                          (str "       " line))))
         vec)))

(defn- visible-tail
  "Take the last N lines such that they fit in `body-rows`."
  [lines body-rows]
  (if (<= (count lines) body-rows)
    (into [] (concat lines (repeat (- body-rows (count lines)) "")))
    (subvec lines (- (count lines) body-rows))))

(defn- pad-right
  [s n]
  (if (>= (count s) n)
    (subs s 0 n)
    (str s (apply str (repeat (- n (count s)) " ")))))

(defn- render-sidebar
  [signals rows]
  (let [active @(:active-room signals)
        rooms  @(:rooms signals)
        room-ids (or (some-> signals :room-order deref) (vec (keys rooms)))
        header  (pad-right " rooms" SIDEBAR-WIDTH)
        sep     (apply str (repeat SIDEBAR-WIDTH "─"))
        room-lines
        (mapv (fn [id]
                (pad-right
                  (str (if (= id active) " ▸ " "   ") (name id))
                  SIDEBAR-WIDTH))
              room-ids)
        hint (pad-right " Ctrl-N: new" SIDEBAR-WIDTH)
        ;; rows = header + content + bottom hint = 1 + (rows-2) + 1
        body-rows (max 0 (- rows 3))
        body (into (vec (take body-rows room-lines))
                   (repeat (max 0 (- body-rows (count room-lines)))
                           (pad-right "" SIDEBAR-WIDTH)))]
    (into [header sep] (conj body hint))))

(defn view
  "Render signals into a seq of lines fitting (width × height).
   Layout: sidebar (left, fixed) + main pane (right)."
  [signals width height]
  (let [active   @(:active-room signals)
        rooms    @(:rooms signals)
        messages (get-in rooms [active :messages] [])
        draft    (get-in rooms [active :draft] "")
        ;; Sidebar takes SIDEBAR-WIDTH cols; +1 for separator.
        sidebar-w (min SIDEBAR-WIDTH (max 0 (- width 20)))
        sep-w     (if (pos? sidebar-w) 1 0)
        main-w    (- width sidebar-w sep-w)
        ;; Main: 1 header + body + 1 footer
        body-rows (max 1 (- height 2))
        msg-lines (vec (mapcat #(render-message-line % main-w) messages))
        all-lines (if (str/blank? draft)
                    msg-lines
                    (into msg-lines
                          (render-message-line {:role :assistant :content draft} main-w)))
        body      (visible-tail all-lines body-rows)
        main      (into [(header signals main-w)]
                        (conj (vec body) (footer signals main-w)))
        sidebar   (when (pos? sidebar-w) (render-sidebar signals height))]
    (if (zero? sidebar-w)
      main
      (mapv (fn [l-side l-main]
              (str (or l-side (apply str (repeat sidebar-w " ")))
                   "│" l-main))
            sidebar main))))
