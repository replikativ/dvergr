(ns dvergr.rooms.theme
  "Single source of truth for per-speaker message colors, so the TUI and the web
   theme a conversation identically. Keyed on the normalized message `:role`
   (`dvergr.rooms.messages`): the colour identity lives here once; each surface
   reads the channel it can render — `:ansi` (0-255, spindel-tui) or `:hex` (web
   CSS).")

(def palette
  "role → {:ansi <0-255> :hex \"#rrggbb\"}. Same hue per role across surfaces."
  {:assistant {:ansi 45  :hex "#56b6c2"}   ; agent reply  — cyan
   :user      {:ansi 78  :hex "#52b788"}   ; human / other — green
   :tool      {:ansi 220 :hex "#d8a657"}   ; tool activity — amber
   :divider   {:ansi 109 :hex "#7088a8"}   ; fork boundary — slate
   :system    {:ansi 244 :hex "#9aa0a6"}}) ; system note   — grey

(def default
  "Fallback for an unknown/absent role — the user/green slot."
  (:user palette))

(defn speaker-color
  "Colour for a message `role` → {:ansi … :hex …}. Unknown roles fall back to the
   user/green slot."
  [role]
  (get palette role default))

(defn ansi [role] (:ansi (speaker-color role)))
(defn hex  [role] (:hex  (speaker-color role)))
