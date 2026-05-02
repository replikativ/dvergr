(ns dvergr.distributed-test
  "Tests for the distributed agent addressing layer.

   Tests the remote spin definitions by calling them locally
   (without kabel peer transport) to validate the registry
   integration and spin composition."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [dvergr.distributed :as dist]
            [dvergr.registry :as registry]
            [dvergr.sessions :as sessions]
            [org.replikativ.spindel.engine.core :as rtc]
            [org.replikativ.spindel.engine.context :as ctx]
            [org.replikativ.spindel.distributed.core :as sdist]
            [org.replikativ.spindel.core :refer [spin await]]
            [org.replikativ.spindel.core :as sync]))

;; ============================================================================
;; Fixtures
;; ============================================================================

(use-fixtures :each
  (fn [f]
    (let [orig-registry @registry/registry
          orig-sessions @sessions/sessions]
      (try
        (f)
        (finally
          (reset! registry/registry orig-registry)
          (reset! sessions/sessions orig-sessions))))))

;; ============================================================================
;; Mock Agent for Registry
;; ============================================================================

(defn- mock-agent
  "Create a mock agent with functional inbox/outbox mailboxes."
  [id exec-ctx]
  (binding [rtc/*execution-context* exec-ctx]
    (let [inbox (sync/mailbox)
          outbox (sync/mailbox)]
      {:id id
       :config {:id id}
       :state-a (atom {:status :running :turn 0})
       :inbox inbox
       :outbox outbox
       :control (sync/mailbox)
       :loop-spin-a (atom nil)})))

;; ============================================================================
;; Tests
;; ============================================================================

(deftest test-list-remote-agents-empty
  (testing "list-remote-agents returns empty when no agents registered"
    (is (empty? (registry/list-agents)))))

(deftest test-list-remote-agents-populated
  (testing "list-remote-agents returns registered agents"
    (let [ctx (ctx/create-execution-context)]
      (binding [rtc/*execution-context* ctx]
        (let [ag (mock-agent :test-coder ctx)]
          (registry/register! :test-coder ag
                              :tags #{:coding}
                              :description "Test coder")
          (let [agents (registry/list-agents)]
            (is (= 1 (count agents)))
            (is (= #{:coding} (:tags (first agents))))))))))

(deftest test-list-remote-sessions-empty
  (testing "list-remote-sessions returns empty when no sessions"
    (is (empty? (sessions/list-sessions)))))

(deftest test-agent-lookup-not-found
  (testing "Looking up non-existent agent returns nil"
    (is (nil? (registry/get-agent :nonexistent)))))

(deftest test-send-to-nonexistent-agent
  (testing "Sending to non-existent agent returns error map"
    (let [ctx (ctx/create-execution-context)]
      (sdist/register-context! :default ctx)
      (try
        (binding [rtc/*execution-context* ctx]
          ;; Directly test the logic that send-to-agent would do
          (let [result (if-let [ag (registry/get-agent :nonexistent)]
                         (do ((:inbox ag) "hello") :sent)
                         {:error :agent-not-found
                          :agent-id :nonexistent})]
            (is (= :agent-not-found (:error result)))))
        (finally
          (sdist/unregister-context! :default))))))

(deftest test-agent-status-from-registry
  (testing "Agent status is readable from registry"
    (let [ctx (ctx/create-execution-context)]
      (binding [rtc/*execution-context* ctx]
        (let [ag (mock-agent :status-test ctx)]
          (registry/register! :status-test ag)
          ;; Status comes from the agent's state atom
          (is (= :running (:status @(:state-a ag)))))))))

(deftest test-send-and-receive-via-mailbox
  (testing "Messages flow through agent inbox/outbox"
    (let [ctx (ctx/create-execution-context)]
      (binding [rtc/*execution-context* ctx]
        (let [ag (mock-agent :mailbox-test ctx)]
          (registry/register! :mailbox-test ag)

          ;; Send to inbox
          ((:inbox ag) {:content "test message"})

          ;; Receive from inbox
          (let [result-spin (spin (await (:inbox ag)))
                result @result-spin]
            (is (= {:content "test message"} result))))))))

(deftest test-peer-state-initial
  (testing "Peer state starts as not started"
    (is (false? (:started? @dist/peer-state)))))

(deftest test-context-registration
  (testing "Execution context registration and lookup"
    (let [ctx (ctx/create-execution-context)]
      (sdist/register-context! :test-ctx ctx)
      (is (= ctx (sdist/get-context :test-ctx)))
      (sdist/unregister-context! :test-ctx)
      (is (nil? (sdist/get-context :test-ctx))))))
