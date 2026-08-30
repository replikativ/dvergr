(ns dvergr.chat.agent-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dvergr.chat.agent :as agent]
            [dvergr.chat.context :as chat-ctx]
            [dvergr.model.chat :as model-chat]))

(defn- response [content]
  {:content content
   :tool-calls []
   :usage {}
   :stop-reason :stop})

(deftest empty-response-after-tools-cannot-complete-with-stale-placeholder
  (testing "one corrective integration step obtains a real final result"
    (let [ctx (chat-ctx/create-chat-context
               {:title "empty-response" :with-sci? false})
          run-id (random-uuid)
          responses (atom [(response "") (response "verified result")])]
      (try
        (chat-ctx/add-message!
         ctx {:role :assistant
              :content "Tool calls: [\"clojure_eval\"]"
              :tool-uses [{:tool-use/id "call-1"
                           :tool-use/name "clojure_eval"
                           :tool-use/input {:code "(+ 1 1)"}}]})
        (chat-ctx/add-message!
         ctx {:role :tool-result :tool-use-id "call-1" :content "2"})
        (with-redefs [agent/messages->api-format (fn [messages _ _] messages)
                      model-chat/chat (fn [& _]
                                        (let [result (first @responses)]
                                          (swap! responses subvec 1)
                                          result))]
          (is (= :continue
                 (agent/run-agent-turn!
                  ctx {:provider :test :model "stub" :tools {}
                       :auto-compact? false :turn-number 1 :run-id run-id})))
          (is (.startsWith ^String
               (-> (chat-ctx/get-messages ctx) last :message/content)
                           agent/empty-response-nudge))
          (is (= :complete
                 (agent/run-agent-turn!
                  ctx {:provider :test :model "stub" :tools {}
                       :auto-compact? false :turn-number 2 :run-id run-id})))
          (is (= "verified result"
                 (->> (chat-ctx/get-messages ctx)
                      (filter #(= :assistant (:message/role %)))
                      last
                      :message/content))))
        (finally
          (chat-ctx/close-chat! ctx))))))

(deftest repeated-empty-response-fails-instead-of-reusing-old-assistant-content
  (let [ctx (chat-ctx/create-chat-context
             {:title "repeated-empty" :with-sci? false})
        run-id (random-uuid)]
    (try
      (with-redefs [agent/messages->api-format (fn [messages _ _] messages)
                    model-chat/chat (fn [& _] (response nil))]
        (is (= :continue
               (agent/run-agent-turn!
                ctx {:provider :test :model "stub" :tools {}
                     :auto-compact? false :turn-number 0 :run-id run-id})))
        (is (= :error
               (agent/run-agent-turn!
                ctx {:provider :test :model "stub" :tools {}
                     :auto-compact? false :turn-number 1 :run-id run-id}))))
      (finally
        (chat-ctx/close-chat! ctx)))))

(deftest successful-run-does-not-consume-a-later-runs-empty-retry
  (let [ctx (chat-ctx/create-chat-context
             {:title "run-scoped-empty" :with-sci? false})
        first-run (random-uuid)
        later-run (random-uuid)
        responses (atom [(response "") (response "first result") (response "")])]
    (try
      (with-redefs [agent/messages->api-format (fn [messages _ _] messages)
                    model-chat/chat (fn [& _]
                                      (let [result (first @responses)]
                                        (swap! responses subvec 1)
                                        result))]
        (is (= :continue
               (agent/run-agent-turn!
                ctx {:provider :test :model "stub" :tools {}
                     :auto-compact? false :turn-number 0 :run-id first-run})))
        (is (= :complete
               (agent/run-agent-turn!
                ctx {:provider :test :model "stub" :tools {}
                     :auto-compact? false :turn-number 1 :run-id first-run})))
        (is (= :continue
               (agent/run-agent-turn!
                ctx {:provider :test :model "stub" :tools {}
                     :auto-compact? false :turn-number 0 :run-id later-run})))
        (is (= 2
               (->> (chat-ctx/get-messages ctx)
                    (filter #(and (= :system (:message/role %))
                                  (str/starts-with? (:message/content %)
                                                    agent/empty-response-nudge)))
                    count))))
      (finally
        (chat-ctx/close-chat! ctx)))))
