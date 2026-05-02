(ns dvergr.channels.email-test
  "Tests for the email channel with mocked IMAP/SMTP."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [dvergr.channels.core :as ch]
            [dvergr.channels.email :as email]
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
;; Tests
;; ============================================================================

(deftest test-make-email
  (testing "make-email creates valid channel"
    (let [ch (email/make-email
               {:imap {:host "imap.test.com" :port 993 :user "test@test.com" :pass "pass"}
                :smtp {:host "smtp.test.com" :port 587 :user "test@test.com" :pass "pass"}})]
      (is (= :email (:type ch)))
      (is (keyword? (:id ch)))
      (is (= #{:email/list :email/read :email/send :email/search
               :email/mark-read :email/delete :email/move}
             (:capabilities ch))))))

(deftest test-default-permissions-no-delete
  (testing "Default permissions exclude :email/delete and :email/move"
    (let [ch (email/make-email
               {:imap {:host "imap.test.com" :port 993 :user "test@test.com" :pass "pass"}
                :smtp {:host "smtp.test.com" :port 587 :user "test@test.com" :pass "pass"}})]
      (is (contains? (:permissions ch) :email/list))
      (is (contains? (:permissions ch) :email/read))
      (is (contains? (:permissions ch) :email/send))
      (is (contains? (:permissions ch) :email/search))
      (is (contains? (:permissions ch) :email/mark-read))
      (is (not (contains? (:permissions ch) :email/delete)))
      (is (not (contains? (:permissions ch) :email/move))))))

(deftest test-custom-permissions
  (testing "Custom permissions override defaults"
    (let [ch (email/make-email
               {:imap {:host "imap.test.com" :port 993 :user "test@test.com" :pass "pass"}
                :smtp {:host "smtp.test.com" :port 587 :user "test@test.com" :pass "pass"}
                :permissions #{:email/list :email/read :email/delete}})]
      (is (= #{:email/list :email/read :email/delete} (:permissions ch)))
      (is (not (contains? (:permissions ch) :email/send))))))

(deftest test-tool-definitions-count
  (testing "Email channel has 7 tool definitions"
    (let [ch (email/make-email
               {:imap {:host "imap.test.com" :port 993 :user "test@test.com" :pass "pass"}
                :smtp {:host "smtp.test.com" :port 587 :user "test@test.com" :pass "pass"}})]
      (is (= 7 (count (:tools ch)))))))

(deftest test-config-validation
  (testing "make-email rejects missing IMAP config"
    (is (thrown? AssertionError
          (email/make-email {:imap {:host "test"} ;; missing user/pass
                             :smtp {:host "test" :port 587 :user "x" :pass "y"}}))))

  (testing "make-email rejects missing SMTP config"
    (is (thrown? AssertionError
          (email/make-email {:imap {:host "test" :port 993 :user "x" :pass "y"}
                             :smtp {:host "test"}})))))  ;; missing user/pass

(deftest test-channel-id-derived-from-user
  (testing "Channel ID is derived from email user"
    (let [ch (email/make-email
               {:imap {:host "imap.test.com" :port 993 :user "alice@example.com" :pass "pass"}
                :smtp {:host "smtp.test.com" :port 587 :user "alice@example.com" :pass "pass"}})]
      (is (= :email-alice (:id ch))))))

(deftest test-max-list-results-config
  (testing "max-list-results is stored in config"
    (let [ch (email/make-email
               {:imap {:host "imap.test.com" :port 993 :user "test@test.com" :pass "pass"}
                :smtp {:host "smtp.test.com" :port 587 :user "test@test.com" :pass "pass"}
                :max-list-results 25})]
      (is (= 25 (get-in ch [:config :max-list-results]))))))

(deftest test-tool-names
  (testing "Tool names match expected values"
    (let [ch (email/make-email
               {:imap {:host "imap.test.com" :port 993 :user "test@test.com" :pass "pass"}
                :smtp {:host "smtp.test.com" :port 587 :user "test@test.com" :pass "pass"}})
          tool-names (set (map :name (:tools ch)))]
      (is (= #{"email_list" "email_read" "email_send" "email_search"
               "email_mark_read" "email_delete" "email_move"}
             tool-names)))))

(deftest test-capability-tool-mapping
  (testing "Each capability maps to exactly one tool"
    (let [ch (email/make-email
               {:imap {:host "imap.test.com" :port 993 :user "test@test.com" :pass "pass"}
                :smtp {:host "smtp.test.com" :port 587 :user "test@test.com" :pass "pass"}})
          caps (set (map :capability (:tools ch)))]
      (is (= (:capabilities ch) caps))
      ;; Each capability appears exactly once
      (is (= (count (:capabilities ch)) (count (:tools ch)))))))
