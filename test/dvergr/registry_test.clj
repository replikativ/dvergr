(ns dvergr.registry-test
  "Tests for the agent registry."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [dvergr.registry :as reg]))

;; ============================================================================
;; Fixtures
;; ============================================================================

(use-fixtures :each
  (fn [f]
    (let [orig @reg/registry]
      (try
        (f)
        (finally
          (reset! reg/registry orig))))))

;; ============================================================================
;; Mock agent
;; ============================================================================

(defn- mock-agent [id]
  {:id id
   :config {:id id}
   :state-a (atom {:status :running :turn 0})
   :inbox nil
   :outbox nil
   :control nil
   :loop-spin-a (atom nil)})

;; ============================================================================
;; Tests
;; ============================================================================

(deftest test-register-and-lookup
  (testing "Basic register and lookup"
    (let [ag (mock-agent :test-agent)
          entry (reg/register! :test-agent ag
                               :tags #{:coding}
                               :description "A test agent")]
      (is (= ag (:agent entry)))
      (is (= :registered (:status entry)))
      (is (= #{:coding} (:tags entry)))
      (is (= "A test agent" (:description entry)))
      (is (instance? java.util.Date (:created-at entry)))
      (is (= :agent/test-agent (:context-id entry)))

      ;; Lookup returns same entry
      (let [looked-up (reg/lookup :test-agent)]
        (is (= ag (:agent looked-up)))
        (is (= #{:coding} (:tags looked-up)))))))

(deftest test-lookup-missing
  (testing "Lookup returns nil for missing agent"
    (is (nil? (reg/lookup :nonexistent)))))

(deftest test-get-agent
  (testing "get-agent extracts the agent record"
    (let [ag (mock-agent :ga)]
      (reg/register! :ga ag)
      (is (= ag (reg/get-agent :ga)))
      (is (nil? (reg/get-agent :nonexistent))))))

(deftest test-unregister
  (testing "Unregister removes agent from registry"
    (let [ag (mock-agent :unreg)]
      (reg/register! :unreg ag)
      (is (some? (reg/lookup :unreg)))

      (reg/unregister! :unreg)
      (is (nil? (reg/lookup :unreg))))))

(deftest test-unregister-missing
  (testing "Unregister of missing agent returns nil"
    (is (nil? (reg/unregister! :nonexistent)))))

(deftest test-list-agents
  (testing "List all agents"
    (reg/register! :a (mock-agent :a) :tags #{:coding})
    (reg/register! :b (mock-agent :b) :tags #{:research})
    (reg/register! :c (mock-agent :c) :tags #{:coding :research})

    (let [agents (reg/list-agents)]
      (is (= 3 (count agents)))
      (is (every? :id agents)))))

(deftest test-list-agents-filter-by-tags
  (testing "Filter agents by tags"
    (reg/register! :a (mock-agent :a) :tags #{:coding})
    (reg/register! :b (mock-agent :b) :tags #{:research})
    (reg/register! :c (mock-agent :c) :tags #{:coding :research})

    (let [coding (reg/list-agents :tags #{:coding})]
      (is (= 2 (count coding))))

    (let [both (reg/list-agents :tags #{:coding :research})]
      (is (= 1 (count both))))))

(deftest test-agents-by-tag
  (testing "Get agent ids by tag"
    (reg/register! :a (mock-agent :a) :tags #{:coding})
    (reg/register! :b (mock-agent :b) :tags #{:research})
    (reg/register! :c (mock-agent :c) :tags #{:coding})

    (let [coding-ids (reg/agents-by-tag :coding)]
      (is (= 2 (count coding-ids)))
      (is (every? #{:a :c} coding-ids)))))

(deftest test-agent-ids
  (testing "Get all agent ids"
    (reg/register! :x (mock-agent :x))
    (reg/register! :y (mock-agent :y))

    (let [ids (reg/agent-ids)]
      (is (= 2 (count ids)))
      (is (every? #{:x :y} (set ids))))))

(deftest test-update-status
  (testing "Update registry status"
    (reg/register! :s (mock-agent :s))
    (reg/update-status! :s :running)
    (is (= :running (:status (reg/lookup :s))))))

(deftest test-update-tags
  (testing "Update tags"
    (reg/register! :t (mock-agent :t) :tags #{:old})
    (reg/update-tags! :t #{:new :shiny})
    (is (= #{:new :shiny} (:tags (reg/lookup :t))))))

(deftest test-clear
  (testing "Clear removes all agents"
    (reg/register! :a (mock-agent :a))
    (reg/register! :b (mock-agent :b))
    (is (= 2 (count (reg/agent-ids))))

    (reg/clear!)
    (is (empty? (reg/agent-ids)))))

(deftest test-custom-context-id
  (testing "Custom context-id is used"
    (let [ag (mock-agent :custom)
          entry (reg/register! :custom ag :context-id :my/custom-ctx)]
      (is (= :my/custom-ctx (:context-id entry))))))

(deftest test-default-values
  (testing "Default tags and description"
    (let [ag (mock-agent :defaults)
          entry (reg/register! :defaults ag)]
      (is (= #{} (:tags entry)))
      (is (= "" (:description entry))))))
