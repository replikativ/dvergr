(ns dvergr.tui.app-test
  "Tests for the dvergr TUI — the pure room-message renderer and headless
   integration tests of the :room view via the spindel-tui harness. Rendering
   is driven by the room mirror (start-room-mirror!), which tracks the current
   room's shared message signal (dvergr.rooms.messages) into :room-messages."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [dvergr.tui.app :as app]
            [dvergr.discourse :as d]
            [dvergr.rooms.messages :as rmsg]
            [dvergr.orchestration.daemon :as daemon]
            [dvergr.agent.turn :as turn]
            [dvergr.chat.context :as cctx]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.engine.context :as ctx]
            [org.replikativ.spindel.engine.impl.simple :as engine]
            [org.replikativ.spindel-tui.components.text-input :as ti]
            [org.replikativ.spindel-tui.components.spinner :as spinner]
            [org.replikativ.spindel-tui.harness :as h]))

(defn- strip [s] (str/replace s #"\[[0-9;?]*[A-Za-z]" ""))

(def ^:private render-room-message #'app/render-room-message)
(def ^:private dashboard-view      #'app/dashboard-view)
(def ^:private start-room-mirror!  #'app/start-room-mirror!)
(def ^:private dashboard-on-key    #'app/dashboard-on-key)
(def ^:private command-menu        #'app/command-menu)
(def ^:private command-menu-lines  #'app/command-menu-lines)

(deftest command-popup-helpers
  (testing "menu filters the registry by prefix; nil when not a bare command"
    (is (some #(= "fork" (:name %)) (command-menu "/fo")))
    (is (nil? (command-menu "/fork foo")))   ; arg started
    (is (nil? (command-menu "hello"))))
  (testing "menu-lines render the items (selected highlighted), no crash"
    (let [menu  (command-menu "/")
          lines (command-menu-lines menu 0 60 (fn [s _w] s))]
      (is (= (count menu) (count lines)))
      (is (re-find #"▸" (str (first lines)))))))

(defn- start-mirror! [hh]
  (start-room-mirror! {:ctx (:ctx hh) :signals (:signals hh)}))

(defn- wait-frame
  "Settle the reactive bus→signal→render chain, then test the rendered frame.
   Each iteration does a SHORT `await-drain-complete!` — its managedBlock park
   actively helps the engine make progress (compensating ForkJoinPool threads),
   unlike a plain sleep — then re-checks the frame. The drain timeout is kept
   short ON PURPOSE: under full-suite load the engine's running-node set is never
   fully idle (other tests' background spins), so a long drain would just burn its
   whole timeout without converging; short drains let us nudge-and-recheck the
   frame frequently against a generous wall-clock deadline. Returns the instant
   the rendered frame reflects the message."
  [hh pred]
  (let [deadline (+ (System/currentTimeMillis) 10000)]
    (loop []
      (engine/await-drain-complete! (:ctx hh) :timeout-ms 250)
      (cond (pred (strip ((:text hh)))) true
            (>= (System/currentTimeMillis) deadline) false
            :else (recur)))))

(deftest render-room-message-distinguishes-tool-activity
  (testing "a :role :tool message renders its content with no speaker line"
    (let [lines (render-room-message {:from :var :role :tool
                                      :content "🔧 grep, read_file"} 60 false)
          text  (strip (str/join "\n" lines))]
      (is (str/includes? text "grep, read_file"))
      (is (not (str/includes? text "var:")) "no speaker line for tool activity")))
  (testing "a chat message renders a speaker line + content"
    (let [lines (render-room-message {:from :alice :role :user
                                      :content "hello there"} 60 false)
          text  (strip (str/join "\n" lines))]
      (is (str/includes? text "alice:"))
      (is (str/includes? text "hello there"))))
  (testing "an assistant message renders speaker, markdown body, and inline tool calls"
    (let [lines (render-room-message
                 {:from :var :role :assistant
                  :content "Done — here is the result."
                  :tool-uses [{:tool-use/name "clojure_eval"
                               :tool-use/input {:tool-input.clojure-eval/code "(+ 1 2)"}}]}
                 60 false)
          text  (strip (str/join "\n" lines))]
      (is (str/includes? text "var:") "speaker line")
      (is (str/includes? text "Done — here is the result.") "body")
      (is (str/includes? text "clojure_eval") "inline tool-call name")
      (is (str/includes? text "(+ 1 2)") "inline tool-call code"))))

(deftest render-room-message-trace-mode-expands-reasoning-and-inputs
  (testing "trace? off: a tool-activity row shows only the summary"
    (let [m {:from :var :role :tool :content "🔧 clojure_eval"
             :reasoning "I should compute the product"
             :tool-uses [{:tool-use/name "clojure_eval"
                          :tool-use/input {:tool-input.clojure-eval/code "(* 19 23)"}}]}
          off (strip (str/join "\n" (render-room-message m 60 false)))]
      (is (not (str/includes? off "I should compute")) "reasoning hidden when collapsed")
      (is (not (str/includes? off "(* 19 23)")) "tool input hidden when collapsed")))
  (testing "trace? on: the same row expands reasoning + tool input"
    (let [m {:from :var :role :tool :content "🔧 clojure_eval"
             :reasoning "I should compute the product"
             :tool-uses [{:tool-use/name "clojure_eval"
                          :tool-use/input {:tool-input.clojure-eval/code "(* 19 23)"}}]}
          on (strip (str/join "\n" (render-room-message m 60 true)))]
      (is (str/includes? on "I should compute the product") "reasoning shown in trace")
      (is (str/includes? on "(* 19 23)") "tool input shown in trace"))))

(defn- room-harness [c room-id extra-signals]
  (h/harness
   {:execution-context c
    :signals (merge {:view-mode :room :current-room room-id
                     :scroll 0 :room-messages []
                     :input (ti/text-input-state :prompt "» ")}
                    extra-signals)
    :render (fn [sm w ht] (dashboard-view sm w ht))
    :on-key (fn [_ _] nil)
    :size   {:width 80 :height 30}}))

(deftest room-view-renders-conversation-and-activity
  (testing "the :room view (mirror) shows chat + tool activity, reactively"
    (let [c    (ctx/create-execution-context)
          room (binding [ec/*execution-context* c]
                 (let [r (d/room :tg-view c)]
                   (d/post! r (d/message :alice :var "search please"))
                   (d/post! r (d/message :var :_activity "🔧 grep" nil {:role :tool}))
                   (d/post! r (d/message :var :alice "found it"))
                   r))
          hh   (room-harness c :tg-view nil)]
      (try
        (start-mirror! hh)
        (is (wait-frame hh #(and (str/includes? % "search please")
                                 (str/includes? % "grep")
                                 (str/includes? % "found it")))
            "room view rendered the conversation + tool activity (no nudge)")
        (finally (rmsg/drop-room! :tg-view) ((:stop! hh)))))))

(deftest room-mirror-rerenders-on-new-message
  (testing "a new bus message re-renders the :room view with no keypress/resize"
    (let [c    (ctx/create-execution-context)
          room (binding [ec/*execution-context* c]
                 (let [r (d/room :tg-watch c)]
                   (d/post! r (d/message :alice :var "first message")) r))
          hh   (room-harness c :tg-watch nil)]
      (try
        (start-mirror! hh)
        (Thread/sleep 100)
        (binding [ec/*execution-context* c]
          (d/post! room (d/message :var :alice "a fresh reply")))
        (is (wait-frame hh #(str/includes? % "a fresh reply"))
            "new message appeared with no keypress/resize nudge")
        (is (pos? (count ((:get hh) :room-messages))) ":room-messages populated")
        (finally (rmsg/drop-room! :tg-watch) ((:stop! hh)))))))

(deftest room-mirror-clears-status-on-agent-reply
  (testing "the mirror flips :status :idle when an agent reply becomes the
            latest message; tool activity keeps it running"
    (let [c    (ctx/create-execution-context)
          room (binding [ec/*execution-context* c] (d/room :dm-st c))
          hh   (room-harness c :dm-st {:status :running
                                       :spinner (spinner/spinner-state :dots)})]
      (try
        (start-mirror! hh)
        (Thread/sleep 50)
        (is (= :running ((:get hh) :status)))
        (binding [ec/*execution-context* c]
          (d/post! room (d/message :bot :_activity "🔧 grep" nil {:role :tool})))
        (Thread/sleep 120)
        (is (= :running ((:get hh) :status)) "tool activity keeps it running")
        (binding [ec/*execution-context* c]
          (d/post! room (d/message :bot :alice "done" nil {:role :assistant})))
        (is (loop [n 0] (cond (= :idle ((:get hh) :status)) true
                              (>= n 200) false
                              :else (do (Thread/sleep 20) (recur (inc n)))))
            ":status cleared to :idle when the reply landed")
        (finally (rmsg/drop-room! :dm-st) ((:stop! hh)))))))

(def ^:private register-room-turn!   turn/register-room-turn!)
(def ^:private unregister-room-turn! turn/unregister-room-turn!)

(deftest daemon-room-turn-registry-cancels
  (testing "the room-turn registry exposes a live chat-ctx that cancel flips
            to :cancelled (the handle the TUI Esc uses)"
    (let [c (cctx/create-chat-context {:budget-dollars 0.01 :with-sci? false})]
      (try
        (is (not (daemon/room-turn-running? :r1)))
        (register-room-turn! :r1 :bot c)
        (is (daemon/room-turn-running? :r1))
        (is (= 1 (daemon/cancel-room-turn! nil :r1)) "one turn signalled")
        (is (= :cancelled (cctx/get-status c)) "chat-ctx status flipped")
        (finally
          (unregister-room-turn! :r1 :bot)
          (is (not (daemon/room-turn-running? :r1)) "unregister clears it")
          (is (zero? (daemon/cancel-room-turn! nil :r1)) "nothing left to cancel"))))))

(deftest room-escape-cancels-running-turn-else-leaves
  (testing "Esc in :room cancels an in-flight turn (stays in room); when idle
            it leaves to :tree"
    (let [c    (ctx/create-execution-context)
          turn (cctx/create-chat-context {:budget-dollars 0.01 :with-sci? false})
          hh   (h/harness
                {:execution-context c
                 :signals {:view-mode :room :current-room :dm-esc
                           :scroll 0 :room-messages []
                           :input (ti/text-input-state :prompt "» ")}
                 :render (fn [sm w ht] (dashboard-view sm w ht))
                 :on-key (fn [sm ev] (dashboard-on-key {:execution-ctx c} sm ev (atom nil)))
                 :size   {:width 80 :height 30}})]
      (try
        (register-room-turn! :dm-esc :bot turn)
        ((:send-key hh) {:key "escape"})
        (is (= :cancelled (cctx/get-status turn)) "Esc cancelled the turn")
        (is (= :room ((:get hh) :view-mode)) "stayed in the room while cancelling")
        (unregister-room-turn! :dm-esc :bot)
        ((:send-key hh) {:key "escape"})
        (is (= :tree ((:get hh) :view-mode)) "Esc when idle returns to the tree")
        (finally
          (unregister-room-turn! :dm-esc :bot)
          ((:stop! hh)))))))

(deftest room-input-routes-to-agent-and-reply-renders
  (testing "typing in a 1-agent room addresses that agent via room-target (no
            UI selection, no adapter); the agent's reply lands in the room and
            the mirror renders it — no daemon, no LLM"
    (let [c    (ctx/create-execution-context)
          room (binding [ec/*execution-context* c]
                 (let [r (d/room :dm-bot c)]
                   (d/join r (d/scripted :bot ["reply from bot"]))
                   r))
          hh   (h/harness
                {:execution-context c
                 :signals {:view-mode :room :current-room :dm-bot
                           :scroll 0 :room-messages []
                           :input (ti/text-input-state :prompt "» " :value "ping")}
                 :render (fn [sm w ht] (dashboard-view sm w ht))
                 :on-key (fn [sm ev] (dashboard-on-key {:execution-ctx c} sm ev (atom nil)))
                 :size   {:width 80 :height 30}})]
      (try
        (start-mirror! hh)
        ((:send-key hh) {:key "enter"})
        (is (wait-frame hh #(and (str/includes? % "ping")
                                 (str/includes? % "reply from bot")))
            "user message + agent reply both rendered in the room")
        (let [msgs (binding [ec/*execution-context* c] (d/messages room {:limit 50}))
              u    (first (filter #(= :local (:from %)) msgs))]
          (is (some? u) "user message present, from :local")
          (is (= "ping" (:content u))))
        (finally (rmsg/drop-room! :dm-bot) ((:stop! hh)))))))
