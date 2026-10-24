(ns dvergr.model.effort-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [dvergr.model.api.anthropic :as anthropic]
            [dvergr.model.api.claude-code :as claude]
            [dvergr.model.api.codex-subscription :as codex]
            [dvergr.model.api.openai :as openai]
            [dvergr.model.chat :as chat]
            [dvergr.model.gateway :as gateway]
            [dvergr.model.provider :as p]
            [dvergr.model.providers :as providers]
            [dvergr.model.registry :as registry]
            [jsonista.core :as json])
  (:import [java.io ByteArrayInputStream]))

(use-fixtures :each
  (fn [f]
    (registry/ensure-models-loaded!)
    (with-redefs [registry/registry (atom @registry/registry)
                  registry/aliases (atom @registry/aliases)
                  providers/ensure-initialized! (constantly nil)
                  providers/providers (atom {:openai (openai/create {:api-key "test"} {})
                                             :fireworks (openai/create-fireworks {:api-key "test"} {})
                                             :anthropic (anthropic/create {:api-key "test"})})]
      (f))))

(deftest fixed-effort-is-a-distinct-derived-candidate
  (let [before @registry/registry
        id (registry/resolve-alias "cc-sonnet@medium")
        model (registry/get-model! id)]
    (is (= "claude-code-sonnet@medium" id))
    (is (= "claude-code-sonnet" (:base-model model)))
    (is (= "medium" (:effort model)))
    (is (= (registry/pricing-of "claude-code-sonnet") (:pricing model)))
    (is (= before @registry/registry) "derived candidates are not cached registry entries")
    (is (not= model (registry/get-model! "claude-code-sonnet@high")))))

(deftest effort-resolves-after-aliases-and-retired-models
  (is (= "accounts/fireworks/models/kimi-k3@low"
         (registry/resolve-alias "accounts/fireworks/models/kimi-k2p6@low")))
  (is (= "codex-subscription-sol-6.1@medium"
         (registry/resolve-candidate "codex-sol-6.1" :medium)))
  (is (= "claude-code-sonnet@high"
         (registry/resolve-candidate "cc-sonnet@high" "high")))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Conflicting"
                        (registry/resolve-candidate "cc-sonnet@high" :low)))
  (doseq [id ["gpt-4.1@medium" "cc-sonnet@typo" "missing@high"
              "accounts/fireworks/models/kimi-k3@medium"]]
    (is (thrown? clojure.lang.ExceptionInfo (registry/resolve-alias id)) id)))

(defn- captured-chat [opts]
  (let [requests (atom [])]
    (with-redefs [gateway/request!
                  (fn [r]
                    (swap! requests conj (json/read-value (:body r) json/keyword-keys-object-mapper))
                    {:status 200 :body (ByteArrayInputStream. (.getBytes "data: [DONE]\n\n" "UTF-8"))})]
      {:response (chat/chat [{:role "user" :content "hi"}] opts)
       :requests @requests})))

(deftest api-wire-options-and-response-identity
  (doseq [[id wire effort path]
          [["gpt-5.5@high" "gpt-5.5" "high" [:reasoning_effort]]
           ["accounts/fireworks/models/kimi-k3@low" "accounts/fireworks/models/kimi-k3" "low" [:reasoning_effort]]
           ["claude-sonnet-4-6@medium" "claude-sonnet-4-6" "medium" [:output_config :effort]]]]
    (let [{:keys [response requests]} (captured-chat {:model id})]
      (is (= id (:model response)))
      (is (= wire (:model (first requests))))
      (is (= effort (get-in (first requests) path)))))
  (let [{:keys [response requests]} (captured-chat {:model "gpt-5.5" :effort :low})]
    (is (= "gpt-5.5@low" (:model response)))
    (is (= "low" (:reasoning_effort (first requests)))))
  (is (not (contains? (first (:requests (captured-chat {:model "gpt-5.5"}))) :reasoning_effort))
      "omitted effort preserves provider defaults"))

(deftest native-tools-cannot-silently-change-the-requested-effort
  (let [tool {:name "lookup" :parameters {:type "object" :properties {}}}]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"requires effort none"
                          (captured-chat {:model "gpt-5.6-sol@high" :tools [tool]})))
    (is (= "none" (-> (captured-chat {:model "gpt-5.6-sol@none" :tools [tool]})
                      :requests first :reasoning_effort)))))

(deftest cli-and-native-codex-receive-the-selected-effort
  (testing "Claude command retains the base model alias and explicit effort"
    (let [captured (atom nil)]
      (with-redefs-fn
        {#'claude/run-claude-streaming
         (fn [_ opts]
           (reset! captured (#'claude/build-command opts))
           {:content "ok"})}
        (fn []
          (swap! providers/providers assoc :claude-code (claude/create {}))
          (is (= "claude-code-opus@medium"
                 (:model (chat/chat [] {:model "claude-code-opus@medium"}))))
          (is (some #{"claude-opus-5-5"} @captured))
          (is (= ["--effort" "medium"] (take 2 (drop-while #(not= "--effort" %) @captured))))))))
  (testing "native Codex payload and accumulator resolve the base subscription model"
    (let [opts {:model "codex-subscription-sol-6.1" :effort "medium"}
          provider (codex/create {:credentials {:kind :test}})
          body (:body (p/build-request provider [] opts))
          state (p/create-accumulator provider (registry/get-model! "codex-subscription-sol-6.1@medium"))]
      (is (= "gpt-6.1-sol" (:model body)))
      (is (= "medium" (get-in body [:reasoning :effort])))
      (is (= "gpt-6.1-sol" (:wanted state))))))
