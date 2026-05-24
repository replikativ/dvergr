(ns dvergr.cli.main
  "dvergr-cli — a TUI chat client for discourse rooms.

   Wires:
     - spindel-tui (rendering loop on spindel signals)
     - dvergr.discourse Room with one (or many) llm-agent participant
     - dvergr.bus subscriptions for inbox + streaming :partial/token
     - dvergr.cli.streaming/make-run-turn-fn (LLM token → bus bridge)

   Run:
     clj -M:cli                                ; default (Fireworks/kimi-k2p6)
     clj -M:cli :model \"<model-id>\"           ; override
     clj -M:cli :system-prompt \"...\"          ; custom system prompt
     clj -M:cli :budget-dollars 2.0            ; raise budget cap"
  (:require [clojure.string :as str]
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
  [{:keys [room id]} signal-map]
  (binding [ec/*execution-context* (:ctx room)]
    (let [inbox-sub (bus/subscribe! (:bus room) [:to USER-ID])
          token-sub (bus/subscribe! (:bus room) [:type :partial/token])
          update-room! (fn [f]
                         (swap! (:rooms signal-map)
                                update id (fn [r] (f (or r {:messages [] :draft "" :input ""})))))]
      ;; Inbox drain: finalized messages from the agent (or system messages)
      ((requiring-resolve 'org.replikativ.spindel.spin.sync/spawn!)
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
      ((requiring-resolve 'org.replikativ.spindel.spin.sync/spawn!)
       (spin
         (loop [s (:aseq token-sub)]
           (when-let [r (await (aseq/anext s))]
             (let [[msg rest-s] r]
               (when (= id (:from msg))
                 (update-room!
                   #(update % :draft (fn [d] (str d (:payload msg))))))
               (recur rest-s)))))))))

;; ============================================================================
;; on-key
;; ============================================================================

(defn- on-key
  [signal-map rooms-meta {:keys [key char]}]
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
              room (get-in rooms-meta [active :room])]
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

      ;; Cycle active room
      (= :ctrl-tab key)
      (let [ids (vec (keys @(:rooms signal-map)))
            idx (.indexOf ids active)
            nxt (nth ids (mod (inc idx) (count ids)))]
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
        first-id :scratch
        room-state (make-room first-id config tui-ctx)
        rooms-meta {first-id room-state}
        pumps-spawned? (atom false)]
    (try
      (tui/start!
        {:execution-context tui-ctx
         :signals {:rooms        {first-id {:messages [] :draft "" :input ""}}
                   :active-room  first-id
                   :status       :idle
                   :budget-used  0
                   :budget-total (long (* (:budget-dollars config) 1000000))}
         :view (fn [signal-map width height]
                 (when (compare-and-set! pumps-spawned? false true)
                   ;; Pumps need the live signal-map; spawn on first render.
                   (spawn-pumps! room-state signal-map))
                 (view/view signal-map width height))
         :on-key (fn [signal-map event]
                   (on-key signal-map rooms-meta event))})
      (finally
        (println "session ended")))))
