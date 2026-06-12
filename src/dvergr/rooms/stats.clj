(ns dvergr.rooms.stats
  "Shared room + system statistics — ONE source the TUI and web both render, so
   the two perspectives always agree.

   - cost: summed straight from the `:ledger/*` entries in the chat DB (each
     ledger entry links to its `:chat/id`; the per-[room,agent] chat-ctx title
     is \"<agent>-<room>\", so per-room cost = ledger entries whose chat title
     ends with \"-<room>\" — the same mechanism `dvergr.orchestration.stats`
     uses per-agent, just matched on the room side).
   - message counts: from the room store (`dvergr.discourse/messages`).
   - presence: from the actor registry (`dvergr.actors/online?`).

   Call with the daemon execution context bound (the renderers do)."
  (:require [clojure.string :as str]
            [dvergr.room.registry :as rreg]
            [dvergr.discourse :as d]
            [dvergr.actors :as actors]
            [dvergr.chat.compaction :as compaction]
            [dvergr.chat.accounting :as accounting]
            [dvergr.model.registry :as registry]
            [dvergr.agent.room-context :as room-context]
            [datahike.api :as dh]
            [org.replikativ.spindel.engine.core :as ec]))

(defn- room-conn
  "A room's OWN msgs-store datahike conn — where its cost ledger now lives
   (RF5 S4). Read straight off the Room value."
  [room]
  (some-> room :store :conn))

(defn age-str
  "Compact human age of a timestamp (ms), e.g. \"5m ago\". nil → nil."
  [ts]
  (when ts
    (let [s (quot (- (System/currentTimeMillis) (long ts)) 1000)]
      (cond
        (< s 5)     "just now"
        (< s 60)    (str s "s ago")
        (< s 3600)  (str (quot s 60) "m ago")
        (< s 86400) (str (quot s 3600) "h ago")
        :else       (str (quot s 86400) "d ago")))))

(defn- room-cost-dollars
  "Total spend (dollars) attributable to this room — ledger entries whose chat
   title ends with \"-<room>\". nil on any error/no-conn."
  [conn room-id]
  (when conn
    (try
      (let [suffix (str "-" (name room-id))
            res (dh/q '[:find (sum ?cost) :with ?e :in $ ?suffix
                        :where [?e :ledger/context ?c]
                        [?c :chat/title ?t]
                        [?e :ledger/cost-microdollars ?cost]
                        [(clojure.string/ends-with? ?t ?suffix)]]
                      @conn suffix)]
        (/ (double (or (ffirst res) 0)) 1000000.0))
      (catch Throwable _ nil))))

(defn- total-cost-dollars [conn]
  (when conn
    (try
      (/ (double (or (ffirst (dh/q '[:find (sum ?cost) :with ?e
                                     :where [?e :ledger/cost-microdollars ?cost]]
                                   @conn))
                     0))
         1000000.0)
      (catch Throwable _ nil))))

(defn- agent-list
  "The room's agent participants (reserved :_ ids dropped) + live presence."
  [room]
  (->> (some-> room :participants deref keys)
       (remove #(.startsWith (name %) "_"))
       (sort)
       (mapv (fn [a] {:id a :online? (actors/online? a)}))))

;; ---------------------------------------------------------------------------
;; Context-window fullness — the compaction progress bar's data. Per
;; [room,agent] chat-ctx: active tokens vs the model's context window, with the
;; 70% auto-compaction line, plus that chat-ctx's exact cost.
;; ---------------------------------------------------------------------------

(def ^:private default-context-window 200000)

(defn- latest-model
  "Most recently used model id for a chat-id (from the ledger), or nil."
  [conn chat-id]
  (try
    (->> (dh/q '[:find ?m (max ?ts) :in $ ?cid
                 :where [?c :chat/id ?cid]
                 [?l :ledger/context ?c]
                 [?l :ledger/timestamp ?ts]
                 [?l :ledger/model ?m]]
               @conn chat-id)
         (sort-by second >) ffirst)
    (catch Throwable _ nil)))

(defn- agent-context
  "Context-fullness + cost for one [room,agent] chat-ctx, or nil if the agent
   has no live ctx yet (no turn taken)."
  [conn room-id agent-id]
  (when-let [cc (room-context/lookup room-id agent-id)]
    (try
      (let [model (latest-model conn (:chat-id cc))
            cw    (or (some-> model registry/context-window) default-context-window)
            full  (binding [ec/*execution-context* (:spindel-ctx cc)]
                    (compaction/context-fullness cc cw))
            cost  (when conn (/ (double (accounting/get-total-cost conn (:chat-id cc))) 1e6))]
        (assoc full :agent agent-id :model model :cost-dollars cost))
      (catch Throwable _ nil))))

(defn- room-context-info
  "The fullest agent's context for the room (closest to compaction) — one bar
   per room. nil when no agent has a live ctx yet."
  [conn room-id agent-ids]
  (->> agent-ids
       (keep #(agent-context conn room-id %))
       (sort-by :pct >)
       first))

(defn room-agent-contexts
  "The FULL per-agent context list for a room (not collapsed) — one entry per
   agent that has a live ctx, each `{:agent :model :pct :tokens :limit
   :should-compact? :compact-pct :cost-dollars}`, sorted fullest-first. Drives
   the expandable per-agent context view; `room-context-info` is the one-line
   summary (the fullest of these)."
  [room]
  (let [conn (room-conn room)
        rid  (:id room)]
    (->> (agent-list room)
         (map :id)
         (keep #(agent-context conn rid %))
         (sort-by :pct >)
         vec)))

(defn room-stats
  "Stats map for one Room. Call with the daemon ctx bound."
  [room]
  (let [conn   (room-conn room)
        msgs   (d/messages room {:limit 5000})
        rid    (:id room)
        agents (agent-list room)
        last-ts (:ts (last msgs))]
    {:slug            (:slug room)
     :title           (or (:title room) (name rid))
     :message-count   (count msgs)
     :last-ts         last-ts
     :last-active-str (age-str last-ts)
     :agents          agents
     :cost-dollars    (room-cost-dollars conn rid)
     :context         (room-context-info conn rid (map :id agents))
     ;; Only ACTUAL forks count — a child by :parent-id can be a normal nested
     ;; room (e.g. a user room defaulting to the boardroom parent) or an agent DM
     ;; room, neither of which is a fork. The canonical fork marker is
     ;; `:forked-from` in the room meta (same key `dvergr.rooms.forks/fork?` and
     ;; the tree key on).
     :fork-count      (count (rreg/list-rooms
                              :where #(and (= rid (:parent-id %))
                                           (some-> % :meta deref :forked-from))))}))

;; ---------------------------------------------------------------------------
;; Shared formatting — the TUI and web render these SAME tokens, so the two
;; perspectives are textually identical (the web just wraps each in a span).
;; ---------------------------------------------------------------------------

(defn fmt-cost
  "Dollar amount, precision scaled so tiny dev-costs stay legible."
  [c]
  (let [c (double (or c 0))]
    (cond
      (zero? c)  "$0.000"
      (< c 0.01) (format "$%.4f" c)
      (< c 1)    (format "$%.3f" c)
      :else      (format "$%.2f" c))))

(defn fmt-tokens
  "Compact token count: 82345 → \"82k\", 1048576 → \"1.0M\"."
  [n]
  (let [n (long (or n 0))]
    (cond
      (>= n 1000000) (format "%.1fM" (/ n 1e6))
      (>= n 1000)    (str (Math/round (/ n 1000.0)) "k")
      :else          (str n))))

(defn context-bar
  "ASCII fill bar for `pct` (0.0–1.0+), `width` cells wide. Shared so the TUI
   renders it verbatim; the web shows the same numbers with a CSS bar."
  [pct width]
  (let [w      (max 1 width)
        filled (min w (max 0 (int (Math/round (* (double (or pct 0)) w)))))]
    (str "[" (apply str (repeat filled "█"))
         (apply str (repeat (- w filled) "░")) "]")))

(defn context-summary
  "One-line context-fullness summary, e.g. \"[████░░░░░░] 41% · 82k/200k\"
   (a trailing ⚠ once past the auto-compaction line)."
  [{:keys [pct tokens limit should-compact?]}]
  (str (context-bar pct 10) " "
       (Math/round (* 100.0 (double (or pct 0)))) "%"
       "  " (fmt-tokens tokens) "/" (fmt-tokens limit)
       (when should-compact? "  ⚠ compaction")))

(defn short-model
  "Drop the provider path from a model id for display: \"accounts/fireworks/
   models/minimax-m2p5\" → \"minimax-m2p5\". nil → nil."
  [model]
  (when model (last (clojure.string/split (str model) #"/"))))

(defn agent-context-summary
  "One-line per-AGENT context summary — the agent name + its model + the
   context bar + that ctx's cost, e.g.
   \"var (minimax-m2p5)  [████░░░░░░] 41% · 82k/200k · $0.012\".
   Shared so the TUI and web render the same tokens."
  [{:keys [agent model cost-dollars] :as ctx}]
  (str (name agent)
       (when-let [m (short-model model)] (str " (" m ")"))
       "  " (context-summary ctx)
       (when cost-dollars (str " · " (fmt-cost cost-dollars)))))

(defn- presence-dots [agents]
  (let [n-on (count (filter :online? agents))
        n    (count agents)]
    (str (apply str (repeat n-on "●")) (apply str (repeat (- n n-on) "○")))))

(defn strip-parts
  "A room's stats as an ordered seq of short strings — the exact tokens both
   the TUI (joined by \" · \") and web (one `.stat` span each) display.
   e.g. (\"8 msgs\" \"1 agent ●\" \"$0.000\" \"11m ago\" \"1 fork\")."
  [{:keys [message-count agents cost-dollars last-active-str fork-count]}]
  (let [n (count agents)]
    (cond-> [(str message-count " msg" (when (not= 1 message-count) "s"))
             (str n " agent" (when (not= 1 n) "s")
                  (when (pos? n) (str " " (presence-dots agents))))
             (fmt-cost cost-dollars)]
      last-active-str                     (conj last-active-str)
      (and fork-count (pos? fork-count))  (conj (str fork-count " fork"
                                                     (when (not= 1 fork-count) "s"))))))

(defn system-stats
  "Whole-system rollup. Call with the daemon ctx bound."
  []
  (let [rooms (rreg/list-rooms)]
    {:room-count    (count rooms)
     :message-count (reduce + 0 (map (fn [r] (count (d/messages r {:limit 5000}))) rooms))
     ;; Actor-backed online count — the SAME view the tree's "Agents [N]" group
     ;; uses (online-ids ⋈ actor rows), so internal participants like :_system
     ;; and transient askers don't inflate the rollup.
     :agents-online (count (actors/online-actors))
     ;; RF5 S4: cost is per-room now — fan out over each room's own ledger.
     :cost-dollars  (reduce + 0.0 (keep #(total-cost-dollars (room-conn %)) rooms))}))
