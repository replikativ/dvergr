(ns dvergr.channels.core-test
  "Tests for the channel framework core."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [dvergr.channels.core :as ch]
            [dvergr.tools :as tools]
            [dvergr.mcp.server :as mcp]))

;; ============================================================================
;; Fixtures: clean up channels between tests
;; ============================================================================

(use-fixtures :each
  (fn [f]
    (let [orig-channels @ch/channels
          orig-tools @tools/registry
          orig-mcp-tools @mcp/tool-definitions
          orig-mcp-handlers @mcp/tool-handlers]
      (try
        (f)
        (finally
          (reset! ch/channels orig-channels)
          (reset! tools/registry orig-tools)
          (reset! mcp/tool-definitions orig-mcp-tools)
          (reset! mcp/tool-handlers orig-mcp-handlers))))))

;; ============================================================================
;; Helpers: mock channel
;; ============================================================================

(defn- make-mock-channel
  "Create a mock channel for testing with configurable permissions."
  [& {:keys [id permissions connected-state]
      :or {id :test-channel
           permissions #{:mock/read :mock/write}
           connected-state false}}]
  (let [connect-called (atom false)
        disconnect-called (atom false)
        state (atom {:connected? connected-state :on-message nil})]
    {:channel
     (ch/make-channel
      {:id           id
       :type         :mock
       :config       {:test true}
       :capabilities #{:mock/read :mock/write :mock/admin}
       :permissions  permissions
       :tools        [{:capability :mock/read
                       :name "mock_read"
                       :description "Mock read tool"
                       :parameters {:type "object" :properties {}}}
                      {:capability :mock/write
                       :name "mock_write"
                       :description "Mock write tool"
                       :parameters {:type "object"
                                    :properties {:data {:type "string"}}
                                    :required ["data"]}}
                      {:capability :mock/admin
                       :name "mock_admin"
                       :description "Mock admin tool (restricted)"
                       :parameters {:type "object" :properties {}}}]
       :handlers     {"mock_read"  (fn [_input _ctx]
                                     {:type :success
                                      :content "mock read result"})
                      "mock_write" (fn [input _ctx]
                                     {:type :success
                                      :content (str "wrote: " (:data input))})
                      "mock_admin" (fn [_input _ctx]
                                     {:type :success
                                      :content "admin action performed"})}
       :connect!     (fn [channel]
                       (reset! connect-called true)
                       (swap! state assoc :connected? true)
                       channel)
       :disconnect!  (fn [_channel]
                       (reset! disconnect-called true)
                       (swap! state assoc :connected? false))
       :state        state})
     :connect-called connect-called
     :disconnect-called disconnect-called
     :state state}))

;; ============================================================================
;; Tests
;; ============================================================================

(deftest test-make-channel
  (testing "make-channel creates a valid channel map"
    (let [{:keys [channel]} (make-mock-channel)]
      (is (= :test-channel (:id channel)))
      (is (= :mock (:type channel)))
      (is (set? (:capabilities channel)))
      (is (set? (:permissions channel)))
      (is (vector? (:tools channel)))
      (is (map? (:handlers channel)))
      (is (fn? (:connect! channel)))
      (is (instance? clojure.lang.Atom (:state channel))))))

(deftest test-make-channel-preconditions
  (testing "make-channel rejects invalid permissions (not subset of capabilities)"
    (is (thrown? AssertionError
          (ch/make-channel
           {:id :bad
            :type :mock
            :capabilities #{:a}
            :permissions #{:a :b}  ;; :b not in capabilities!
            :tools []
            :handlers {}
            :connect! identity})))))

(deftest test-connect-disconnect
  (testing "connect! and disconnect! lifecycle"
    (let [{:keys [channel connect-called disconnect-called]} (make-mock-channel)]
      ;; Before connect
      (is (not (ch/connected? channel)))
      (is (empty? (ch/list-channels)))

      ;; Connect
      (ch/connect! channel)
      (is @connect-called)
      (is (ch/connected? (:id channel)))

      ;; Should appear in list
      (is (= 1 (count (ch/list-channels))))
      (is (= :test-channel (:id (first (ch/list-channels)))))

      ;; Disconnect
      (ch/disconnect! (:id channel))
      (is @disconnect-called)
      (is (not (ch/connected? channel)))
      (is (empty? (ch/list-channels))))))

(deftest test-tool-registration-on-connect
  (testing "connect! registers permitted tools in global registry"
    (let [{:keys [channel]} (make-mock-channel)]
      (ch/connect! channel)

      ;; mock_read and mock_write should be registered (they're in permissions)
      (is (some? (tools/get-tool "mock_read")))
      (is (some? (tools/get-tool "mock_write")))

      ;; mock_admin should NOT be registered (not in permissions)
      (is (nil? (tools/get-tool "mock_admin")))

      ;; Clean up
      (ch/disconnect! (:id channel))

      ;; Tools should be unregistered
      (is (nil? (tools/get-tool "mock_read")))
      (is (nil? (tools/get-tool "mock_write"))))))

(deftest test-tool-execution-via-registry
  (testing "Channel tools can be executed via tools/execute"
    (let [{:keys [channel]} (make-mock-channel)]
      (ch/connect! channel)

      (let [ctx (tools/make-context {:cwd "."})
            result (tools/execute "mock_read" {} ctx)]
        (is (= :success (:type result)))
        (is (= "mock read result" (:content result))))

      (let [ctx (tools/make-context {:cwd "."})
            result (tools/execute "mock_write" {:data "hello"} ctx)]
        (is (= :success (:type result)))
        (is (= "wrote: hello" (:content result))))

      (ch/disconnect! (:id channel)))))

(deftest test-permission-enforcement
  (testing "Tools not in permissions set are not registered"
    (let [{:keys [channel]} (make-mock-channel :permissions #{:mock/read})]
      (ch/connect! channel)

      ;; Only read should be available
      (is (some? (tools/get-tool "mock_read")))
      (is (nil? (tools/get-tool "mock_write")))
      (is (nil? (tools/get-tool "mock_admin")))

      (ch/disconnect! (:id channel)))))

(deftest test-mcp-tool-registration
  (testing "connect! registers tools in MCP server"
    (let [{:keys [channel]} (make-mock-channel)
          initial-count (count @mcp/tool-definitions)]
      (ch/connect! channel)

      ;; 2 permitted tools should be added
      (is (= (+ initial-count 2) (count @mcp/tool-definitions)))

      ;; Handlers should be registered
      (is (some? (get @mcp/tool-handlers "mock_read")))
      (is (some? (get @mcp/tool-handlers "mock_write")))

      (ch/disconnect! (:id channel))

      ;; Should be unregistered
      (is (= initial-count (count @mcp/tool-definitions))))))

(deftest test-on-message-callback
  (testing "on-message callback is stored in channel state"
    (let [{:keys [channel state]} (make-mock-channel)
          received (atom nil)]
      (ch/connect! channel :on-message (fn [msg] (reset! received msg)))

      ;; Verify callback is stored
      (is (fn? (:on-message @state)))

      ;; Simulate incoming message
      ((:on-message @state) {:text "hello"})
      (is (= {:text "hello"} @received))

      (ch/disconnect! (:id channel)))))

(deftest test-get-channel
  (testing "get-channel returns nil for unknown channels"
    (is (nil? (ch/get-channel :nonexistent))))

  (testing "get-channel returns connected channel"
    (let [{:keys [channel]} (make-mock-channel)]
      (ch/connect! channel)
      (is (= channel (ch/get-channel :test-channel)))
      (ch/disconnect! :test-channel))))

(deftest test-multiple-channels
  (testing "Multiple channels can coexist"
    (let [{ch1 :channel} (make-mock-channel :id :ch-1 :permissions #{:mock/read})
          {ch2 :channel} (make-mock-channel :id :ch-2 :permissions #{:mock/write})]
      (ch/connect! ch1)
      (ch/connect! ch2)

      (is (= 2 (count (ch/list-channels))))
      (is (some? (ch/get-channel :ch-1)))
      (is (some? (ch/get-channel :ch-2)))

      (ch/disconnect! :ch-1)
      (is (= 1 (count (ch/list-channels))))

      (ch/disconnect! :ch-2)
      (is (empty? (ch/list-channels))))))
