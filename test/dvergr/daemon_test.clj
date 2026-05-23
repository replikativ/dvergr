(ns dvergr.daemon-test
  "Integration tests for the daemon with mock agents.

   Most tests share one daemon (`*shared-daemon*`) — each `daemon/start!`
   does heavy I/O (Datahike connect, Lucene init, ygg system registration,
   calendar schema install) that takes ~3-5s and dominates the suite.
   Tests that specifically exercise start/stop lifecycle keep their own
   per-test daemon."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [dvergr.daemon :as daemon]
            [dvergr.registry :as registry]
            [dvergr.sessions :as sessions]
            [dvergr.channels.core :as ch]
            [dvergr.tools :as tools]
            [dvergr.mcp.server :as mcp]))

;; ============================================================================
;; Fixtures
;; ============================================================================

(def ^:dynamic *shared-daemon* nil)

(defn- snapshot-globals []
  {:registry     @registry/registry
   :sessions     @sessions/sessions
   :channels     @ch/channels
   :tools        @tools/registry
   :mcp-tools    @mcp/tool-definitions
   :mcp-handlers @mcp/tool-handlers})

(defn- restore-globals! [snap]
  (reset! registry/registry     (:registry snap))
  (reset! sessions/sessions     (:sessions snap))
  (reset! ch/channels           (:channels snap))
  (reset! tools/registry        (:tools snap))
  (reset! mcp/tool-definitions  (:mcp-tools snap))
  (reset! mcp/tool-handlers     (:mcp-handlers snap)))

(defn- snapshot-daemon [d]
  (when d
    {:participants (when-let [room (:discourse-room d)] @(:participants room))
     :response-sinks @(:response-sinks d)}))

(defn- restore-daemon! [d snap]
  (when (and d snap)
    (when-let [room (:discourse-room d)]
      (reset! (:participants room) (:participants snap)))
    (reset! (:response-sinks d) (:response-sinks snap))))

;; :once — start ONE daemon for the whole ns. Tests that need a fresh
;; lifecycle (start-stop-no-telegram, no-agents-config) opt out by
;; starting their own daemon and ignoring *shared-daemon*.
(use-fixtures :once
  (fn [f]
    (let [snap (snapshot-globals)
          d (daemon/start! {:agents {}})]
      (try
        (binding [*shared-daemon* d]
          (f))
        (finally
          (try (daemon/stop! d) (catch Exception _))
          (restore-globals! snap))))))

;; :each — between tests, restore the global registry/sessions/etc.
;; AND the shared daemon's room participants + response sinks so
;; per-test agent creation doesn't leak across tests.
(use-fixtures :each
  (fn [f]
    (let [g-snap (snapshot-globals)
          d-snap (snapshot-daemon *shared-daemon*)]
      (try
        (f)
        (finally
          (restore-daemon! *shared-daemon* d-snap)
          (restore-globals! g-snap))))))

;; ============================================================================
;; Tests
;; ============================================================================

(deftest test-daemon-start-stop-no-telegram
  (testing "Daemon starts and stops without Telegram"
    ;; This test specifically exercises the full lifecycle, so it keeps
    ;; its own daemon — don't reuse *shared-daemon* here.
    (let [d (daemon/start! {:agents {:echo {:provider :fireworks
                                            :model "test-model"
                                            :system-prompt "Echo agent"
                                            :tags #{:echo}
                                            :description "Test echo agent"}}})]
      (is (= :running @(:status d)))
      (is (nil? (:telegram-ch d)))

      ;; Check agents registered
      (let [agents (daemon/list-agents d)]
        (is (= 1 (count agents)))
        (is (= #{:echo} (:tags (first agents)))))

      ;; Check daemon status
      (let [status (daemon/daemon-status d)]
        (is (= :running (:status status)))
        (is (= 1 (:agents status)))
        (is (= 0 (:sessions status)))
        (is (false? (:telegram-connected? status))))

      ;; Stop
      (is (= :stopped (daemon/stop! d)))
      (is (= :stopped @(:status d))))))

(deftest test-daemon-multiple-agents
  (testing "Daemon hosts multiple agents simultaneously"
    (let [d *shared-daemon*]
      (daemon/create-agent! d {:id :agent-a :provider :fireworks
                               :model "model-a" :tags #{:coding}})
      (daemon/create-agent! d {:id :agent-b :provider :fireworks
                               :model "model-b" :tags #{:research}})
      (is (= 2 (count (daemon/list-agents d))))
      (is (some? (registry/get-agent :agent-a)))
      (is (some? (registry/get-agent :agent-b)))
      (is (= #{:coding} (:tags (registry/lookup :agent-a))))
      (is (= #{:research} (:tags (registry/lookup :agent-b)))))))

(deftest test-daemon-create-agent-dynamic
  (testing "create-agent! adds an agent to a running daemon"
    (let [d *shared-daemon*
          before (count (daemon/list-agents d))]
      (daemon/create-agent! d {:id :dynamic :provider :fireworks
                               :model "test" :tags #{:dynamic}})
      (is (= (inc before) (count (daemon/list-agents d))))
      (is (some? (registry/get-agent :dynamic))))))

(deftest test-daemon-stop-agent
  (testing "stop-agent! removes one agent from a running daemon"
    (let [d *shared-daemon*]
      (daemon/create-agent! d {:id :temp :provider :fireworks :model "test"})
      (is (some? (registry/get-agent :temp)))
      (daemon/stop-agent! d :temp)
      (is (nil? (registry/get-agent :temp))))))

(deftest test-daemon-dispatch-creates-session
  (testing "Dispatch creates a session for a new chat-id"
    (let [d *shared-daemon*]
      (daemon/create-agent! d {:id :var :provider :fireworks
                               :model "test" :system-prompt "Hi"})
      ;; default-agent comes from daemon config — for the shared daemon
      ;; config is empty, so we pass agent addressing in the message.
      (daemon/dispatch! d {:chat-id 12345
                           :text "/var Hello!"
                           :from {:username "testuser"
                                  :first_name "Test"}})
      (let [listed (daemon/list-sessions d)
            tester (->> listed
                        (filter #(= 12345 (:chat-id %)))
                        first)]
        (is (some? tester) "session was created for chat-id 12345")
        (is (= :var (:agent-id tester)))))))

(deftest test-daemon-dispatch-ignores-blank
  (testing "Dispatch on blank text creates no session"
    (let [d *shared-daemon*
          chat-id 99999
          before  (count (daemon/list-sessions d))]
      (daemon/dispatch! d {:chat-id chat-id :text ""    :from {:username "u"}})
      (daemon/dispatch! d {:chat-id chat-id :text nil   :from {:username "u"}})
      (daemon/dispatch! d {:chat-id chat-id :text "   " :from {:username "u"}})
      ;; No new session for chat-id; total session count unchanged.
      (is (= before (count (daemon/list-sessions d)))))))

(deftest test-daemon-no-agents-config
  (testing "Daemon starts cleanly with no agents in config"
    ;; Fresh daemon — verifying the start! path with empty :agents.
    (let [d (daemon/start! {})]
      (is (= :running @(:status d)))
      (is (empty? (daemon/list-agents d)))
      (daemon/stop! d))))
