(ns dvergr.daemon-test
  "Integration tests for the daemon with mock agents."
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

(use-fixtures :each
  (fn [f]
    (let [orig-registry @registry/registry
          orig-sessions @sessions/sessions
          orig-channels @ch/channels
          orig-tools @tools/registry
          orig-mcp-tools @mcp/tool-definitions
          orig-mcp-handlers @mcp/tool-handlers]
      (try
        (f)
        (finally
          (reset! registry/registry orig-registry)
          (reset! sessions/sessions orig-sessions)
          (reset! ch/channels orig-channels)
          (reset! tools/registry orig-tools)
          (reset! mcp/tool-definitions orig-mcp-tools)
          (reset! mcp/tool-handlers orig-mcp-handlers))))))

;; ============================================================================
;; Tests
;; ============================================================================

(deftest test-daemon-start-stop-no-telegram
  (testing "Daemon starts and stops without Telegram"
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
      (is (= :stopped @(:status d)))
      (is (empty? (registry/agent-ids))))))

(deftest test-daemon-multiple-agents
  (testing "Daemon creates multiple agents"
    (let [d (daemon/start! {:agents {:agent-a {:provider :fireworks
                                               :model "model-a"
                                               :tags #{:coding}}
                                     :agent-b {:provider :fireworks
                                               :model "model-b"
                                               :tags #{:research}}}})]
      (is (= 2 (count (daemon/list-agents d))))
      (is (= 2 (count (registry/agent-ids))))

      ;; Both agents should be in registry
      (is (some? (registry/get-agent :agent-a)))
      (is (some? (registry/get-agent :agent-b)))

      ;; Tags should be correct
      (is (= #{:coding} (:tags (registry/lookup :agent-a))))
      (is (= #{:research} (:tags (registry/lookup :agent-b))))

      (daemon/stop! d))))

(deftest test-daemon-create-agent-dynamic
  (testing "Create agent dynamically after daemon start"
    (let [d (daemon/start! {:agents {}})]
      (is (empty? (daemon/list-agents d)))

      ;; Create an agent dynamically
      (let [ag (daemon/create-agent! d {:id :dynamic
                                        :provider :fireworks
                                        :model "test"
                                        :tags #{:dynamic}})]
        (is (some? ag))
        (is (= 1 (count (daemon/list-agents d))))
        (is (some? (registry/get-agent :dynamic))))

      (daemon/stop! d))))

(deftest test-daemon-stop-agent
  (testing "Stop individual agent"
    (let [d (daemon/start! {:agents {:temp {:provider :fireworks
                                            :model "test"}}})]
      (is (= 1 (count (daemon/list-agents d))))

      (daemon/stop-agent! d :temp)
      (is (nil? (registry/get-agent :temp)))

      (daemon/stop! d))))

(deftest test-daemon-dispatch-creates-session
  (testing "Dispatch creates session for new chat-id"
    (let [d (daemon/start! {:agents {:var {:provider :fireworks
                                                  :model "test"
                                                  :system-prompt "Hi"}}
                            :default-agent :var})]
      ;; Simulate incoming message
      (daemon/dispatch! d {:chat-id 12345
                           :text "Hello!"
                           :from {:username "testuser"
                                  :first_name "Test"}})

      ;; Session should be created
      (let [listed (daemon/list-sessions d)]
        (is (= 1 (count listed)))
        (is (= 12345 (:chat-id (first listed))))
        (is (= :var (:agent-id (first listed)))))

      (daemon/stop! d))))

(deftest test-daemon-dispatch-ignores-blank
  (testing "Dispatch ignores blank messages"
    (let [d (daemon/start! {:agents {:var {:provider :fireworks
                                                  :model "test"}}
                            :default-agent :var})]
      (daemon/dispatch! d {:chat-id 12345 :text "" :from {:username "u"}})
      (daemon/dispatch! d {:chat-id 12345 :text nil :from {:username "u"}})
      (daemon/dispatch! d {:chat-id 12345 :text "   " :from {:username "u"}})

      ;; No sessions should be created
      (is (= 0 (count (daemon/list-sessions d))))

      (daemon/stop! d))))

(deftest test-daemon-no-agents-config
  (testing "Daemon starts with no agents"
    (let [d (daemon/start! {})]
      (is (= :running @(:status d)))
      (is (empty? (daemon/list-agents d)))
      (daemon/stop! d))))
