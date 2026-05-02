(ns dvergr.channels.telegram-test
  "Tests for the Telegram channel with mock HTTP."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [dvergr.channels.core :as ch]
            [dvergr.channels.telegram :as tg]
            [dvergr.tools :as tools]
            [dvergr.mcp.server :as mcp]))

;; ============================================================================
;; Fixtures
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
;; Mock Telegram API
;; ============================================================================

(def ^:private mock-responses (atom {}))

(defn- install-mock-api!
  "Replace the Telegram api-call with a mock that returns canned responses."
  [responses]
  (reset! mock-responses responses))

;; We mock at the hato level by rebinding
(def ^:private original-post hato.client/post)

(defn- mock-hato-post [url opts]
  (let [method (last (clojure.string/split url #"/"))
        response (get @mock-responses method)]
    (if response
      {:status 200
       :body (jsonista.core/write-value-as-string
              {:ok true :result response})}
      {:status 404
       :body (jsonista.core/write-value-as-string
              {:ok false :description (str "Mock: no response for " method)})})))

;; ============================================================================
;; Tests
;; ============================================================================

(deftest test-make-telegram
  (testing "make-telegram creates valid channel"
    (let [ch (tg/make-telegram {:token "123456:ABC" :poll? false})]
      (is (= :telegram (:type ch)))
      (is (keyword? (:id ch)))
      (is (contains? (:capabilities ch) :tg/send-text))
      (is (contains? (:capabilities ch) :tg/receive))
      (is (contains? (:capabilities ch) :tg/send-photo))
      (is (contains? (:capabilities ch) :tg/get-updates))
      (is (contains? (:capabilities ch) :tg/chat-info)))))

(deftest test-default-permissions
  (testing "Default permissions exclude send-photo"
    (let [ch (tg/make-telegram {:token "123456:ABC" :poll? false})]
      (is (contains? (:permissions ch) :tg/send-text))
      (is (contains? (:permissions ch) :tg/receive))
      (is (contains? (:permissions ch) :tg/get-updates))
      (is (contains? (:permissions ch) :tg/chat-info))
      (is (not (contains? (:permissions ch) :tg/send-photo))))))

(deftest test-custom-permissions
  (testing "Custom permissions override defaults"
    (let [ch (tg/make-telegram {:token "123456:ABC"
                                :poll? false
                                :permissions #{:tg/send-text :tg/send-photo}})]
      (is (= #{:tg/send-text :tg/send-photo} (:permissions ch))))))

(deftest test-connect-registers-tools
  (testing "Connecting registers tools in global registry and MCP"
    (let [tg-ch (tg/make-telegram {:token "123456:ABC" :poll? false})]
      ;; Connect without polling
      (with-redefs [hato.client/post mock-hato-post]
        (install-mock-api! {"getUpdates" []})
        (ch/connect! tg-ch))

      ;; Default permission tools should be registered
      (is (some? (tools/get-tool "telegram_send")))
      (is (some? (tools/get-tool "telegram_updates")))
      (is (some? (tools/get-tool "telegram_chat_info")))
      (is (some? (tools/get-tool "telegram_poll")))

      ;; telegram_send_photo should NOT be registered (not in default permissions)
      (is (nil? (tools/get-tool "telegram_send_photo")))

      ;; Clean up
      (ch/disconnect! (:id tg-ch)))))

(deftest test-send-message-tool
  (testing "telegram_send tool calls Telegram API"
    (let [tg-ch (tg/make-telegram {:token "123456:ABC" :poll? false})
          sent-requests (atom [])]
      (with-redefs [hato.client/post
                    (fn [url opts]
                      (let [method (last (clojure.string/split url #"/"))
                            body (jsonista.core/read-value (:body opts)
                                   (jsonista.core/object-mapper {:decode-key-fn keyword}))]
                        (swap! sent-requests conj {:method method :body body})
                        {:status 200
                         :body (jsonista.core/write-value-as-string
                                {:ok true
                                 :result {:message_id 42
                                          :chat {:id (:chat_id body)}}})}))]
        (ch/connect! tg-ch)

        (let [ctx (tools/make-context {:cwd "."})
              result (tools/execute "telegram_send"
                                    {:chat_id 12345 :text "Hello!"}
                                    ctx)]
          (is (= :success (:type result)))
          (is (clojure.string/includes? (:content result) "Message sent"))
          (is (clojure.string/includes? (:content result) "12345")))

        ;; Verify API was called
        (is (some #(= "sendMessage" (:method %)) @sent-requests))

        (ch/disconnect! (:id tg-ch))))))

(deftest test-get-updates-tool
  (testing "telegram_updates tool returns normalized messages"
    (let [tg-ch (tg/make-telegram {:token "123456:ABC" :poll? false})]
      (with-redefs [hato.client/post
                    (fn [_url _opts]
                      {:status 200
                       :body (jsonista.core/write-value-as-string
                              {:ok true
                               :result [{:update_id 100
                                         :message {:message_id 1
                                                   :from {:id 999 :username "testuser" :first_name "Test"}
                                                   :chat {:id 12345}
                                                   :text "Hello bot!"
                                                   :date 1700000000}}]})})]
        (ch/connect! tg-ch)

        (let [ctx (tools/make-context {:cwd "."})
              result (tools/execute "telegram_updates" {:limit 5} ctx)]
          (is (= :success (:type result)))
          (is (clojure.string/includes? (:content result) "Test"))
          (is (clojure.string/includes? (:content result) "Hello bot!")))

        (ch/disconnect! (:id tg-ch))))))

(deftest test-disconnect-unregisters-tools
  (testing "Disconnecting unregisters all channel tools"
    (let [tg-ch (tg/make-telegram {:token "123456:ABC" :poll? false})]
      (with-redefs [hato.client/post mock-hato-post]
        (install-mock-api! {"getUpdates" []})
        (ch/connect! tg-ch))

      (is (some? (tools/get-tool "telegram_send")))

      (ch/disconnect! (:id tg-ch))

      (is (nil? (tools/get-tool "telegram_send")))
      (is (nil? (tools/get-tool "telegram_updates"))))))

(deftest test-on-message-wiring
  (testing "Incoming messages are dispatched to on-message callback"
    (let [received (atom [])
          tg-ch (tg/make-telegram {:token "123456:ABC" :poll? false})]
      (with-redefs [hato.client/post mock-hato-post]
        (install-mock-api! {"getUpdates" []})
        (ch/connect! tg-ch :on-message (fn [msg] (swap! received conj msg))))

      ;; Simulate what the polling loop would do
      (let [on-msg (:on-message @(:state tg-ch))]
        (is (fn? on-msg))
        (on-msg {:channel :telegram :text "test message"})
        (is (= 1 (count @received)))
        (is (= "test message" (:text (first @received)))))

      (ch/disconnect! (:id tg-ch)))))

(deftest test-token-validation
  (testing "make-telegram requires non-empty token"
    (is (thrown? AssertionError
          (tg/make-telegram {:token ""})))))
