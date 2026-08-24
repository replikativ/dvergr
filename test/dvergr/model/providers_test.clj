(ns dvergr.model.providers-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [dvergr.model.api.anthropic :as anthropic]
            [dvergr.model.api.claude-code :as claude-code]
            [dvergr.model.api.openai :as openai]
            [dvergr.model.provider :as provider]
            [dvergr.model.providers :as providers]
            [dvergr.model.registry :as registry]))

(def ^:private openai-url "https://api.openai.com/v1/chat/completions")
(def ^:private fireworks-base-url "https://api.fireworks.ai/inference/v1")
(def ^:private fireworks-url (str fireworks-base-url "/chat/completions"))
(def ^:private test-tool
  {:name "lookup"
   :description "Look up a value"
   :parameters {:type "object" :properties {}}})

(use-fixtures
  :each
  (fn [f]
    (let [before @providers/providers]
      (try
        (providers/clear-all!)
        (f)
        (finally
          (reset! providers/providers before))))))

(defn- with-provider-env [env f]
  ;; Keep this contract test independent of the developer machine's Anthropic
  ;; key and local Claude CLI; only OpenAI and Fireworks are under test here.
  (with-redefs [anthropic/create-if-available (constantly nil)
                claude-code/create-if-available (constantly nil)
                registry/load-models-resource! (constantly nil)]
    (providers/init-defaults! env)
    (f)))

(defn- request-for [provider-key]
  (provider/build-request
   (providers/get-provider! provider-key)
   [{:role :user :content "test"}]
   {:model "gpt-5.6-sol"
    :tools [test-tool]}))

(defn- bearer [request]
  (get-in request [:headers "Authorization"]))

(defn- assert-default-provider [env expected]
  (let [{:keys [provider]} (providers/default-spec env)]
    (is (= expected provider))
    (is (providers/registered? provider)
        "auto-selection must return a registered provider")))

(deftest no-provider-credentials
  (with-provider-env
    {}
    (fn []
      (is (empty? (providers/list-providers)))
      (is (nil? (providers/default-spec {})))
      (is (= "OPENAI_API_KEY"
             (:env (ex-data (try
                              (openai/create {} {})
                              (catch clojure.lang.ExceptionInfo e e))))))
      (is (= "FIREWORKS_API_KEY"
             (:env (ex-data (try
                              (openai/create-fireworks {} {})
                              (catch clojure.lang.ExceptionInfo e e)))))))))

(deftest openai-only-configuration
  (let [env {"OPENAI_API_KEY" "openai-only-credential"}]
    (with-provider-env
      env
      (fn []
        (is (= #{:openai} (set (providers/list-providers))))
        (assert-default-provider env :openai)
        (let [request (request-for :openai)]
          (is (= :openai (provider/provider-id (providers/get-provider! :openai))))
          (is (= openai-url (:url request)))
          (is (= "Bearer openai-only-credential" (bearer request)))
          (is (= "none" (get-in request [:body :reasoning_effort]))
              "native OpenAI keeps its model-specific request workaround"))))))

(deftest openai-custom-base-configuration
  (let [base-url "https://compatible.example.test/v1"
        env {"OPENAI_API_KEY" "openai-custom-credential"
             "OPENAI_BASE_URL" base-url}]
    (with-provider-env
      env
      (fn []
        (is (= #{:openai} (set (providers/list-providers))))
        (assert-default-provider env :openai)
        (let [request (request-for :openai)]
          (is (= :openai (provider/provider-id (providers/get-provider! :openai))))
          (is (= (str base-url "/chat/completions") (:url request)))
          (is (= "Bearer openai-custom-credential" (bearer request)))
          (is (not (contains? (:body request) :reasoning_effort))
              "a compatible endpoint must not receive an OpenAI-native field"))))))

(deftest fireworks-only-configuration
  (let [env {"FIREWORKS_API_KEY" "fireworks-only-credential"}]
    (with-provider-env
      env
      (fn []
        (is (= #{:fireworks} (set (providers/list-providers))))
        (assert-default-provider env :fireworks)
        (let [request (request-for :fireworks)]
          (is (= :fireworks (provider/provider-id (providers/get-provider! :fireworks))))
          (is (= fireworks-url (:url request)))
          (is (= "Bearer fireworks-only-credential" (bearer request)))
          (is (not (contains? (:body request) :reasoning_effort))))))))

(deftest both-provider-credentials
  (let [env {"OPENAI_API_KEY" "openai-both-credential"
             "FIREWORKS_API_KEY" "fireworks-both-credential"}]
    (with-provider-env
      env
      (fn []
        (is (= #{:openai :fireworks} (set (providers/list-providers))))
        (assert-default-provider env :fireworks)
        (let [openai-request (request-for :openai)
              fireworks-request (request-for :fireworks)]
          (is (= openai-url (:url openai-request)))
          (is (= "Bearer openai-both-credential" (bearer openai-request)))
          (is (= fireworks-url (:url fireworks-request)))
          (is (= "Bearer fireworks-both-credential" (bearer fireworks-request))))))))

(deftest identical-base-urls-keep-provider-scope
  (let [env {"OPENAI_API_KEY" "openai-shared-url-credential"
             "OPENAI_BASE_URL" fireworks-base-url
             "FIREWORKS_API_KEY" "fireworks-shared-url-credential"}]
    (with-provider-env
      env
      (fn []
        (is (= #{:openai :fireworks} (set (providers/list-providers))))
        (assert-default-provider env :fireworks)
        (let [openai-provider (providers/get-provider! :openai)
              fireworks-provider (providers/get-provider! :fireworks)
              openai-request (request-for :openai)
              fireworks-request (request-for :fireworks)]
          (is (= :openai (provider/provider-id openai-provider)))
          (is (= :fireworks (provider/provider-id fireworks-provider)))
          (is (= (:url openai-request) (:url fireworks-request) fireworks-url))
          (is (= "Bearer openai-shared-url-credential" (bearer openai-request)))
          (is (= "Bearer fireworks-shared-url-credential" (bearer fireworks-request)))
          (is (not (contains? (:body openai-request) :reasoning_effort)))
          (is (not (contains? (:body fireworks-request) :reasoning_effort))))))))
