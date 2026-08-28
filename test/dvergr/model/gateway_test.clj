(ns dvergr.model.gateway-test
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.model.api.anthropic :as anthropic]
            [dvergr.model.api.openai :as openai]
            [dvergr.model.gateway :as gateway]
            [dvergr.model.provider :as provider])
  (:import [java.io ByteArrayInputStream]))

(deftype RotatingCredentials [token recovered?]
  gateway/Credentials
  (credential-kind [_] :rotating-test)
  (allowed-origins [_] #{"https://provider.test"})
  (resolve-auth! [_ _]
    {:headers {"Authorization" (str "Bearer " @token)}
     :version @token})
  (recover-auth! [_ _ _]
    (reset! recovered? true)
    (reset! token "fresh")
    true))

(defn- empty-body []
  (ByteArrayInputStream. (byte-array 0)))

(deftest gateway-injects-static-credentials-at-the-egress-boundary
  (let [seen (atom nil)
        credentials (gateway/static-credentials
                     :test-key
                     {"Authorization" "Bearer secret"}
                     #{"https://provider.test"})]
    (binding [gateway/*request-fn*
              (fn [request]
                (reset! seen request)
                {:status 200 :body (empty-body)})]
      (gateway/request! {:method :post
                         :url "https://provider.test/v1/responses"
                         :headers {"Content-Type" "application/json"}
                         :credentials credentials})
      (is (= "Bearer secret" (get-in @seen [:headers "Authorization"])))
      (is (nil? (:credentials @seen))))))

(deftest gateway-rejects-credential-confusion
  (let [called? (atom false)
        credentials (gateway/static-credentials
                     :test-key
                     {"Authorization" "Bearer secret"}
                     #{"https://provider.test"})]
    (binding [gateway/*request-fn* (fn [_] (reset! called? true))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"not permitted"
                            (gateway/request! {:method :post
                                               :url "https://attacker.test/steal"
                                               :credentials credentials})))
      (is (false? @called?)))))

(deftest gateway-recovers-one-unauthorized-response
  (let [token (atom "stale")
        recovered? (atom false)
        attempts (atom [])
        credentials (RotatingCredentials. token recovered?)]
    (binding [gateway/*request-fn*
              (fn [request]
                (swap! attempts conj (get-in request [:headers "Authorization"]))
                {:status (if (= 1 (count @attempts)) 401 200)
                 :body (empty-body)})]
      (is (= 200 (:status (gateway/request!
                           {:method :post
                            :url "https://provider.test/responses"
                            :credentials credentials}))))
      (is @recovered?)
      (is (= ["Bearer stale" "Bearer fresh"] @attempts)))))

(deftest api-providers-use-the-shared-credential-boundary
  (testing "OpenAI build-request contains no bearer token"
    (let [instance (openai/create {:api-key "openai-secret"})
          request (provider/build-request instance [{:role "user" :content "hi"}]
                                          {:model "gpt-test"})]
      (is (nil? (get-in request [:headers "Authorization"])))
      (is (satisfies? gateway/Credentials (:credentials request)))
      (is (not (re-find #"openai-secret" (str (:credentials request)))))))
  (testing "Anthropic build-request contains no API key"
    (let [instance (anthropic/create {:api-key "anthropic-secret"})
          request (provider/build-request instance [{:role "user" :content "hi"}]
                                          {:model "claude-test"})]
      (is (nil? (get-in request [:headers "x-api-key"])))
      (is (satisfies? gateway/Credentials (:credentials request)))
      (is (not (re-find #"anthropic-secret" (str (:credentials request)))))))
  (testing "OpenAI and Fireworks credentials stay provider-scoped"
    (let [openai-request (provider/build-request
                          (openai/create {:api-key "openai-secret"
                                          :base-url "https://openai.test/v1"})
                          [{:role "user" :content "hi"}]
                          {:model "gpt-test"})
          fireworks-request (provider/build-request
                             (openai/create-fireworks
                              {:api-key "fireworks-secret"
                               :base-url "https://fireworks.test/v1"})
                             [{:role "user" :content "hi"}]
                             {:model "fireworks-test"})]
      (is (= "https://openai.test/v1/chat/completions"
             (:url openai-request)))
      (is (= "https://fireworks.test/v1/chat/completions"
             (:url fireworks-request)))
      (is (= #{"https://openai.test"}
             (gateway/allowed-origins (:credentials openai-request))))
      (is (= #{"https://fireworks.test"}
             (gateway/allowed-origins (:credentials fireworks-request))))
      (is (= :openai (provider/provider-id
                      (openai/create {:api-key "openai-secret"}))))
      (is (= :fireworks (provider/provider-id
                         (openai/create-fireworks
                          {:api-key "fireworks-secret"})))))))
