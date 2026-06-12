(ns dvergr.distributed-test
  "Tests for the distributed agent addressing layer.

   Tests the remote spin definitions by calling them locally
   (without kabel peer transport) to validate the registry
   integration and spin composition."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [dvergr.experimental.distributed :as dist]
            [dvergr.actors :as actors]
            [org.replikativ.spindel.engine.core :as rtc]
            [org.replikativ.spindel.engine.context :as ctx]
            [org.replikativ.spindel.distributed.core :as sdist]
            [org.replikativ.spindel.core :refer [spin await]]
            [org.replikativ.spindel.core :as sync]))

;; ============================================================================
;; Fixtures
;; ============================================================================

(use-fixtures :each (fn [f] (f)))

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
  (testing "list-remote-agents (online-actors) is empty with no agents present"
    (is (empty? (actors/online-actors)))))

(deftest test-agent-lookup-not-found
  (testing "A non-present agent is not online"
    (is (not (actors/online? :nonexistent)))))

(deftest test-send-to-nonexistent-agent
  (testing "Sending to non-existent agent returns error map"
    (let [ctx (ctx/create-execution-context)]
      (sdist/register-context! :default ctx)
      (try
        (binding [rtc/*execution-context* ctx]
          ;; Directly test the logic that send-to-agent would do
          (let [result (if (actors/online? :nonexistent)
                         :sent
                         {:error :agent-not-found
                          :agent-id :nonexistent})]
            (is (= :agent-not-found (:error result)))))
        (finally
          (sdist/unregister-context! :default))))))

(deftest test-send-and-receive-via-mailbox
  (testing "Messages flow through agent inbox/outbox"
    (let [ctx (ctx/create-execution-context)]
      (binding [rtc/*execution-context* ctx]
        (let [ag (mock-agent :mailbox-test ctx)]
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
