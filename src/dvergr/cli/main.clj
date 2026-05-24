(ns dvergr.cli.main
  "dvergr-cli — a TUI chat client for discourse rooms.

   Wires:
     - spindel-tui (rendering loop on spindel signals)
     - dvergr.discourse Room with one (or many) llm-agent participant
     - dvergr.bus subscriptions for inbox + streaming :partial/token
     - dvergr.cli.streaming/make-run-turn-fn (LLM token → bus bridge)

   Run:
     clj -M:cli                                ; default (claude-code if avail)
     clj -M:cli :model \"<model-id>\"           ; override model
     clj -M:cli :system-prompt \"...\"          ; custom system prompt
     clj -M:cli :budget-dollars 2.0            ; raise budget cap
     clj -M:cli :resume <session-id>           ; resume an EDN-snapshotted session

   Keys:
     Enter      send the input line to the active room's agent
     Ctrl-N     create a new room (room-N) with the same defaults
     Tab        cycle the active room
     Ctrl-C / q quit (saves session snapshot first)"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [is.simm.partial-cps.sequence :as aseq]
            [org.replikativ.spindel.core :as sp]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.engine.context :as ctx]
            [org.replikativ.spindel.spin.cps :refer [spin]]
            [org.replikativ.spindel.effects.await :refer [await]]
            [org.replikativ.spindel-tui.tui :as tui]
            [dvergr.bus :as bus]
            [dvergr.discourse :as d]
            [dvergr.discourse.llm :as llm]
            [dvergr.chat.context :as cc]
            [dvergr.participant.context :as pctx]
            [dvergr.cli.streaming :as cli-stream]
            [dvergr.cli.view :as view]
            [dvergr.sandbox :as sandbox]
            [dvergr.tools :as tools])
  (:gen-class))

(def ^:private USER-ID :you)

;; ============================================================================
;; Persistence — minimal EDN snapshot per session
;; ============================================================================

(defn- sessions-dir
  []
  (let [d (io/file (System/getProperty "user.home") ".dvergr-cli" "sessions")]
    (.mkdirs d)
    d))

(defn- session-file
  [session-id]
  (io/file (sessions-dir) (str (name session-id) ".edn")))

(defn- strip-binary-snapshot
  "Strip non-serializable fields from a session snapshot (the
   spindel-snapshot is binary and not EDN-safe)."
  [s]
  (-> s
      (update :rooms
              (fn [m]
                (reduce-kv (fn [acc id v]
                             (assoc acc id
                                    (update v :chat dissoc :spindel-snapshot)))
                           {} m)))))

(defn- save-session!
  "Snapshot every room's chat-ctx + view messages to ~/.dvergr-cli/sessions/<id>.edn."
  [session-id rooms-meta signal-rooms]
  (let [snapshot {:session-id session-id
                  :saved-at  (System/currentTimeMillis)
                  :rooms (reduce-kv
                           (fn [m room-id {:keys [chat-ctx]}]
                             (assoc m room-id
                                    {:chat (cc/snapshot-chat chat-ctx)
                                     :view (get signal-rooms room-id)}))
                           {} rooms-meta)}]
    (spit (session-file session-id)
          (pr-str (strip-binary-snapshot snapshot)))
    session-id))

(defn- load-session
  [session-id]
  (let [f (session-file session-id)]
    (when (.exists f)
      (edn/read-string (slurp f)))))

;; ============================================================================
;; Defaults
;; ============================================================================

(defn- claude-cli-available?
  []
  (try
    (let [p (.start (ProcessBuilder. ["claude" "--version"]))
          ok (zero? (.waitFor p))]
      ok)
    (catch Exception _ false)))

(defn- default-provider-model
  []
  (if (claude-cli-available?)
    {:provider :claude-code :model "claude-code-sonnet"}
    {:provider :fireworks   :model "accounts/fireworks/models/kimi-k2p6"}))

(defn- default-config
  []
  (merge
    (default-provider-model)
    {:system-prompt
     "You are a helpful coding assistant. Be concise. When you need to read files or run code, use the available tools."
     :budget-dollars 1.0
     :max-turns      8
     :tools          #{"read_file" "glob" "grep" "code_query"
                       "write_file" "edit_file"
                       "clojure_eval" "shell"}}))

;; ============================================================================
;; Room construction
;; ============================================================================

(defn- make-room
  "Create a discourse room with a coder agent joined. Returns
   {:id :room :agent :pctx :chat-ctx :tool-ctx :sci-ctx}."
  [room-id config tui-ctx]
  (binding [ec/*execution-context* tui-ctx]
    (let [room  (d/room room-id tui-ctx)
          chat (cc/create-chat-context
                 {:title          (name room-id)
                  :budget-dollars (:budget-dollars config)
                  :with-sci?      false})
          _ (cc/add-message! chat
                             {:role :system
                              :content (:system-prompt config)})
          pc (pctx/from-chat-context room-id chat)
          ;; Sandbox: each room gets its own SCI ctx forked off the base.
          sci-ctx  (sandbox/fork-for-session tui-ctx)
          ;; Resolve tool NAMES to the registered tool DEFs (a map).
          ;; llm-agent + run-agent-turn! both expect map-shaped tools.
          agent-tools (select-keys @tools/registry (:tools config))
          tool-ctx (tools/make-context
                     {:cwd           (System/getProperty "user.dir")
                      :sci-ctx       sci-ctx
                      :chat-ctx      chat
                      :tools         agent-tools
                      :isolation     :sci
                      :execution-ctx tui-ctx})
          run-turn-fn (cli-stream/make-run-turn-fn
                        {:bus           (:bus room)
                         :assistant-id  room-id
                         :recipient-id  USER-ID})
          agent (let [p (llm/llm-agent
                          {:id   room-id
                           :ctx  (:ctx room)
                           :spec {:provider      (:provider config)
                                  :model         (:model config)
                                  :system-prompt (:system-prompt config)}
                           :tools agent-tools
                           :tool-ctx tool-ctx
                           :participant-context pc
                           :budget {:dollars (:budget-dollars config)
                                    :max-turns (:max-turns config)}
                           :run-turn-fn run-turn-fn})]
                  (d/join room p))]
      {:id room-id :room room :agent agent :pctx pc
       :chat-ctx chat :tool-ctx tool-ctx :sci-ctx sci-ctx})))

;; ============================================================================
;; Bus pumps — translate room events into signal updates
;; ============================================================================

(defn- spawn-pumps!
  [{:keys [room id chat-ctx]} signal-map]
  (binding [ec/*execution-context* (:ctx room)]
    (let [inbox-sub  (bus/subscribe! (:bus room) [:to USER-ID])
          token-sub  (bus/subscribe! (:bus room) [:type :partial/token])
          tt-started (bus/subscribe! (:bus room) [:type :telemetry/turn-started])
          tt-complete (bus/subscribe! (:bus room) [:type :telemetry/turn-complete])
          update-room! (fn [f]
                         (swap! (:rooms signal-map)
                                update id (fn [r] (f (or r {:messages [] :draft "" :input ""})))))
          spawn (requiring-resolve 'org.replikativ.spindel.spin.sync/spawn!)]
      ;; Initialise per-room budget from chat-ctx (one-shot).
      (when chat-ctx
        (try
          (let [{:keys [total used]} (cc/get-budget chat-ctx)]
            (update-room! #(assoc % :budget-total total :budget-used used)))
          (catch Throwable _ nil)))
      ;; Inbox drain: finalized messages from the agent (or system messages)
      (spawn
       (spin
         (loop [s (:aseq inbox-sub)]
           (when-let [r (await (aseq/anext s))]
             (let [[msg rest-s] r]
               (when (and (not= :partial/token (:type msg))
                          (some? (:content msg)))
                 (update-room!
                   (fn [rm]
                     (-> rm
                         (assoc :draft "")
                         (update :messages conj
                                 {:role :assistant
                                  :content (:content msg)
                                  :from (:from msg)})))))
               (recur rest-s))))))
      ;; Token drain: stream into the room's :draft
      (spawn
       (spin
         (loop [s (:aseq token-sub)]
           (when-let [r (await (aseq/anext s))]
             (let [[msg rest-s] r]
               (when (= id (:from msg))
                 (update-room!
                   #(update % :draft (fn [d] (str d (:payload msg))))))
               (recur rest-s))))))
      ;; Telemetry: turn-started → set :generating
      (spawn
       (spin
         (loop [s (:aseq tt-started)]
           (when-let [r (await (aseq/anext s))]
             (let [[_msg rest-s] r]
               (reset! (:status signal-map) :generating)
               (recur rest-s))))))
      ;; Telemetry: turn-complete → update last-turn + budget delta
      (spawn
       (spin
         (loop [s (:aseq tt-complete)]
           (when-let [r (await (aseq/anext s))]
             (let [[msg rest-s] r
                   delta (or (get-in msg [:payload :cost-microdollars]) 0)]
               (update-room!
                 (fn [rm]
                   (-> rm
                       (assoc :last-turn msg)
                       (update :budget-used (fnil + 0) delta))))
               (reset! (:status signal-map) :idle)
               (recur rest-s)))))))))

;; ============================================================================
;; on-key
;; ============================================================================

(defn- on-key
  [signal-map rooms-meta-atom config tui-ctx {:keys [key char]}]
  (let [active     @(:active-room signal-map)
        rooms      @(:rooms signal-map)
        cur-input  (get-in rooms [active :input] "")]
    (cond
      ;; Quit
      (or (= :ctrl-c key)
          (and (str/blank? cur-input)
               (or (= \q char) (= "q" key))))
      :quit

      ;; Submit
      (or (= :enter key) (= \return char) (= \newline char))
      (when-not (str/blank? cur-input)
        (let [content cur-input
              room (get-in @rooms-meta-atom [active :room])]
          (swap! (:rooms signal-map) update active
                 (fn [r]
                   (-> r
                       (assoc :input "")
                       (update :messages conj
                               {:role :user :content content :from USER-ID}))))
          (reset! (:status signal-map) :generating)
          (future
            (binding [ec/*execution-context* (:ctx room)]
              (try
                (d/post! room (d/message USER-ID active content))
                (catch Throwable _ nil)
                (finally
                  (reset! (:status signal-map) :idle)))))))

      ;; Backspace
      (or (= :backspace key) (= \backspace char))
      (swap! (:rooms signal-map) update-in [active :input]
             (fn [s] (if (seq s) (subs s 0 (dec (count s))) "")))

      ;; New room: Ctrl-N
      (= :ctrl-n key)
      (let [order (or (some-> signal-map :room-order deref)
                      (vec (keys @(:rooms signal-map))))
            n (count order)
            new-id (keyword (str "room-" (inc n)))
            rs (make-room new-id config tui-ctx)]
        (swap! rooms-meta-atom assoc new-id rs)
        (swap! (:rooms signal-map) assoc new-id
               {:messages [] :draft "" :input ""})
        (when (:room-order signal-map)
          (swap! (:room-order signal-map) conj new-id))
        ;; Spawn pumps for this new room.
        ((requiring-resolve 'dvergr.cli.main/spawn-pumps!) rs signal-map)
        (reset! (:active-room signal-map) new-id))

      ;; Cycle active room (Tab without shift)
      (or (= :ctrl-tab key) (= :tab key))
      (let [order (or (some-> signal-map :room-order deref)
                      (vec (keys @(:rooms signal-map))))
            idx (.indexOf order active)
            nxt (nth order (mod (inc idx) (count order)))]
        (reset! (:active-room signal-map) nxt))

      ;; Printable char
      (and char (>= (int char) 32) (not= (int char) 127))
      (swap! (:rooms signal-map) update-in [active :input]
             (fn [s] (str s char)))

      :else nil)))

;; ============================================================================
;; -main
;; ============================================================================

(defn- parse-args
  [args]
  (->> (partition 2 args)
       (reduce (fn [m [k v]]
                 (let [k' (if (keyword? k)
                            k
                            (keyword (str/replace (str k) #"^:" "")))]
                   (assoc m k' (cond
                                 (re-matches #"^-?\d+$" (str v))
                                 (Long/parseLong (str v))

                                 (re-matches #"^-?\d+\.\d+$" (str v))
                                 (Double/parseDouble (str v))

                                 :else v))))
               {})))

(defn -main
  [& args]
  (let [opts    (parse-args args)
        config  (merge (default-config) opts)
        tui-ctx (ctx/create-execution-context)
        resume-id  (some-> (:resume opts) keyword)
        snapshot   (when resume-id (load-session resume-id))
        session-id (or resume-id (keyword (str "s-" (subs (str (random-uuid)) 0 8))))
        ;; Construct rooms (either fresh single :scratch, or from snapshot).
        rooms-list (if snapshot
                     (vec (keys (:rooms snapshot)))
                     [:scratch])
        first-id   (first rooms-list)
        rooms-meta (into {}
                         (map (fn [rid]
                                [rid (make-room rid config tui-ctx)])
                              rooms-list))
        rooms-meta-atom (atom rooms-meta)
        ;; Replay snapshot messages into the chat contexts.
        _ (when snapshot
            (doseq [[rid {:keys [chat]}] (:rooms snapshot)]
              (when-let [msgs (seq (:messages chat))]
                (let [rs (get rooms-meta rid)]
                  (binding [ec/*execution-context* (:ctx (:room rs))]
                    (cc/replace-messages! (:chat-ctx rs) (vec msgs)))))))
        ;; Initial view state.
        initial-rooms
        (reduce-kv
          (fn [acc rid {:keys [chat]}]
            (assoc acc rid {:messages (vec (:messages chat))
                             :draft "" :input ""}))
          {}
          (if snapshot (:rooms snapshot)
              (zipmap rooms-list (repeat {:chat {:messages []}}))))
        pumps-spawned? (atom false)]
    (when snapshot
      (println "Resumed session" (name session-id)
               "with" (count rooms-list) "room(s)"))
    (try
      (tui/start!
        {:execution-context tui-ctx
         :signals {:rooms        initial-rooms
                   :active-room  first-id
                   :room-order   rooms-list
                   :status       :idle
                   :budget-used  0
                   :budget-total (long (* (:budget-dollars config) 1000000))}
         :view (fn [signal-map width height]
                 (when (compare-and-set! pumps-spawned? false true)
                   ;; Pumps need the live signal-map; spawn for every room.
                   (doseq [[_ rs] @rooms-meta-atom]
                     (spawn-pumps! rs signal-map)))
                 (view/view signal-map width height))
         :on-key (fn [signal-map event]
                   (let [result (on-key signal-map rooms-meta-atom config tui-ctx event)]
                     (when (= :quit result)
                       (try
                         (save-session! session-id @rooms-meta-atom @(:rooms signal-map))
                         (catch Throwable _ nil)))
                     result))})
      (finally
        (println "session" (name session-id) "ended — saved to"
                 (.getAbsolutePath (session-file session-id)))))))
