(ns dvergr.chat.agent-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dvergr.chat.agent :as agent]
            [dvergr.chat.context :as chat-ctx]
            [dvergr.chat.accounting :as accounting]
            [datahike.api :as dh]
            [dvergr.tools :as tools]
            [dvergr.model.registry :as model-registry]
            [dvergr.model.chat :as model-chat]))

(defn- response [content]
  {:content content
   :tool-calls []
   :usage {}
   :stop-reason :stop})

(deftest dispatch-correlates-each-tool-call
  (let [ctx (chat-ctx/create-chat-context {:title "tool-correlation" :with-sci? false})
        calls [{:id "call-a" :name "fixture" :input {}}
               {:id "call-b" :name "fixture" :input {}}]
        observed (atom [])]
    (try
      (with-redefs [agent/messages->api-format (fn [messages _ _] messages)
                    model-chat/chat (fn [& _] (assoc (response "") :tool-calls calls))
                    tools/execute (fn [_ _ tool-ctx]
                                    (swap! observed conj (:tool-use-id tool-ctx))
                                    {:type :success :content "done"
                                     :authorization {:decision :authorized
                                                     :sources #{:test}}})]
        (is (= :continue
               (agent/run-agent-turn! ctx {:provider :test :model "stub" :tools {}
                                           :tool-ctx {:tool-use-id "stale"}
                                           :auto-compact? false :turn-number 0})))
        (is (= ["call-a" "call-b"] @observed))
        (reset! observed [])
        (tools/execute-parallel calls {:tool-use-id "stale"})
        (is (= #{"call-a" "call-b"} (set @observed))))
      (finally (chat-ctx/close-chat! ctx)))))

(deftest provider-usage-is-accounted-once-with-model-pricing
  (doseq [model ["codex-subscription-sol" "claude-sonnet-4-5"]]
    (let [ctx (chat-ctx/create-chat-context {:title "usage" :with-sci? false})
          usage {:input-tokens 565 :output-tokens 328}]
      (try
        (with-redefs [agent/messages->api-format (fn [messages _ _] messages)
                      model-chat/chat (fn [& _] (assoc (response "verified") :usage usage))]
          (is (= :complete
                 (agent/run-agent-turn! ctx {:provider :test :model model :tools {}
                                             :auto-compact? false :turn-number 0})))
          (is (= usage (:by-type (chat-ctx/get-budget ctx))))
          (is (= #{[:input-tokens 565 model] [:output-tokens 328 model]}
                 (set (dh/q '[:find ?kind ?amount ?model
                              :in $ ?chat-id
                              :where [?c :chat/id ?chat-id]
                              [?l :ledger/context ?c]
                              [?l :ledger/resource ?kind]
                              [?l :ledger/amount ?amount]
                              [?l :ledger/model ?model]]
                            @(:db-conn ctx) (:chat-id ctx)))))
          (is (= 2 (dh/q '[:find (count ?l) . :in $ ?chat-id
                           :where [?c :chat/id ?chat-id] [?l :ledger/context ?c]]
                         @(:db-conn ctx) (:chat-id ctx))))
          (is (= (reduce + (for [[kind amount] usage]
                             (accounting/calculate-cost kind amount {:model model})))
                 (:used (chat-ctx/get-budget ctx))
                 (accounting/get-total-cost (:db-conn ctx) (:chat-id ctx)))))
        (finally (chat-ctx/close-chat! ctx))))))

(deftest provider-failure-does-not-turn-previous-known-usage-into-a-complete-total
  (let [ctx (chat-ctx/create-chat-context {:title "failed-usage" :with-sci? false})]
    (try
      (chat-ctx/account-tokens! ctx :input-tokens 10 {:model "claude-code-haiku"})
      (chat-ctx/account-tokens! ctx :output-tokens 2 {:model "claude-code-haiku"})
      (with-redefs [agent/messages->api-format (fn [messages _ _] messages)
                    model-chat/chat (fn [& _] (throw (ex-info "Fixture provider timed out" {:status 504})))]
        (agent/run-agent-turn! ctx {:provider :test :model "claude-code-haiku" :tools {}
                                    :auto-compact? false :turn-number 1})
        (is (= #{:input-tokens :output-tokens} (:unreported-types (chat-ctx/get-budget ctx))))
        (is (= {:input-tokens 10 :output-tokens 2} (:by-type (chat-ctx/get-budget ctx)))))
      (finally (chat-ctx/close-chat! ctx)))))

(deftest response-execution-id-determines-the-charge
  (let [ctx (chat-ctx/create-chat-context {:title "execution identity" :with-sci? false})
        old "accounts/fireworks/models/kimi-k2p5"
        successor "accounts/fireworks/models/kimi-k3"]
    (try
      (model-registry/ensure-models-loaded!)
      ;; A stored charge keeps its identity and price when a new call resolves.
      (chat-ctx/account-tokens! ctx :input-tokens 1000 {:model old})
      (with-redefs [agent/messages->api-format (fn [messages _ _] messages)
                    model-chat/chat (fn [& _] (assoc (response "done")
                                                     :model successor
                                                     :usage {:input-tokens 1000}))]
        (is (= :complete (agent/run-agent-turn! ctx {:provider :test :model "stub" :tools {}
                                                     :auto-compact? false})))
        (is (= 3600 (:used (chat-ctx/get-budget ctx))
               (accounting/get-total-cost (:db-conn ctx) (:chat-id ctx))))
        (is (= #{[old 600] [successor 3000]}
               (set (dh/q '[:find ?model ?cost :in $ ?chat-id
                            :where [?c :chat/id ?chat-id] [?l :ledger/context ?c]
                            [?l :ledger/cost-microdollars ?cost]
                            [?l :ledger/model ?model]]
                          @(:db-conn ctx) (:chat-id ctx))))))
      (finally (chat-ctx/close-chat! ctx)))))

(deftest message-token-metadata-does-not-charge-for-appending-history
  (let [ctx (chat-ctx/create-chat-context {:title "message-metadata" :with-sci? false})]
    (try
      (let [before (chat-ctx/get-budget ctx)]
        (doseq [role [:user :assistant]]
          (chat-ctx/add-message! ctx {:role role :content "retained history" :tokens 123}))
        (is (= [123 123] (mapv :message/tokens (chat-ctx/get-messages ctx))))
        (is (= before (chat-ctx/get-budget ctx))))
      (finally (chat-ctx/close-chat! ctx)))))

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

(defn- scripted-turns!
  "Runs `ctx`'s turns against `responses` in order (a tool call executes as a
   success), returning each turn's outcome."
  [ctx responses & {:keys [run-id] :or {run-id (random-uuid)}}]
  (let [left (atom (vec responses))]
    (with-redefs [agent/messages->api-format (fn [messages _ _] messages)
                  model-chat/chat (fn [& _]
                                    (let [result (first @left)]
                                      (swap! left subvec 1)
                                      result))
                  tools/execute (fn [_ _ _]
                                  {:type :success :content "written"
                                   :authorization {:decision :authorized :sources #{:test}}})]
      (mapv (fn [turn]
              (agent/run-agent-turn! ctx {:provider :test :model "stub" :tools {}
                                          :auto-compact? false :turn-number turn
                                          :run-id run-id}))
            (range (count responses))))))

(defn- acting
  "A reply that calls a tool; `n` keeps calls distinct (the doom-loop guard
   ends a turn that repeats one)."
  [n]
  (assoc (response "") :tool-calls [{:id (str "call-" n) :name "fixture" :input {:n n}}]))

(defn- called
  "An assistant message calling `code` and its tool result."
  [id code result]
  [{:message/role :assistant
    :message/tool-uses [{:tool-use/id id :tool-use/name "clojure_eval" :tool-use/input {:code code}}]}
   {:message/role :tool-result :message/tool-use-id id :message/content result}])

(deftest a-repeated-call-is-a-doom-loop-only-when-its-result-repeats
  (let [load "(load-string (slurp \"/out/model/m.clj\"))"]
    (testing "the same call, failing the same way, is a loop"
      (is (= "clojure_eval"
             (:tool-use/name (agent/detect-doom-loop
                              (mapcat #(called (str "c" %) load "Evaluation error: boom") (range 3)))))))
    (testing "reloading a file between edits sees the edits: progress"
      (is (nil? (agent/detect-doom-loop
                 (concat (called "c1" load "=> 1") (called "c2" load "=> 2") (called "c3" load "=> 3"))))))))

(deftest silent-after-acting-completes-a-run-without-a-reply
  ;; Codex answers its output schema with {content: "", tool_calls: []} when
  ;; it is done: in a Run that acted, two empty replies end it with no reply
  (let [ctx (chat-ctx/create-chat-context {:title "silent-finish" :with-sci? false})]
    (try
      (chat-ctx/add-message! ctx {:role :user :content "book it"})
      (let [activity (volatile! {})]
        (binding [agent/*run-activity* activity]
          (is (= [:continue :continue :complete]
                 (scripted-turns! ctx [(acting 1) (response "") (response "")]))))
        (is (= {:acted? true :silent? true} @activity)))
      (testing "a later Run, with no user message since the first one acted, still fails on silence"
        (binding [agent/*run-activity* (volatile! {})]
          (is (= [:continue :error] (scripted-turns! ctx [(response "") (response "")])))))
      (testing "a user message mid-Run (steering) does not undo the Run's work"
        (let [activity (volatile! {})]
          (binding [agent/*run-activity* activity]
            (is (= [:continue] (scripted-turns! ctx [(acting 2)])))
            (chat-ctx/add-message! ctx {:role :user :content "also check the date"})
            (is (= [:continue :complete] (scripted-turns! ctx [(response "") (response "")]))))))
      (testing "outside a Run (room drivers, protocols) silence after acting still fails"
        (is (= [:continue :continue :error]
               (scripted-turns! ctx [(acting 3) (response "") (response "")]))))
      (finally (chat-ctx/close-chat! ctx)))))

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

(defn- tool-call-rows [ctx]
  (dh/q '[:find [(pull ?c [:tool-call/tool-use-id :tool-call/status :tool-call/started-at
                           :tool-call/ended-at :tool-call/duration-ms :tool-call/result]) ...]
          :where [?c :tool-call/id _]]
        @(:db-conn ctx)))

(deftest a-tool-call-is-recorded-while-it-runs
  ;; Recorded when it starts, completed when it ends: a call in flight is
  ;; visible, and timed from its real start (the record used to appear only
  ;; afterwards, with the end time as its start).
  (let [ctx (chat-ctx/create-chat-context {:title "tool-in-flight" :with-sci? false})
        entered (promise)
        release (promise)]
    (try
      (with-redefs [agent/messages->api-format (fn [messages _ _] messages)
                    model-chat/chat (fn [& _] (assoc (response "")
                                                     :tool-calls [{:id "call-slow" :name "fixture" :input {}}]))
                    tools/execute (fn [_ _ _]
                                    (deliver entered true)
                                    (deref release 5000 nil)
                                    {:type :success :content "done"})]
        (let [turn (future (agent/run-agent-turn! ctx {:provider :test :model "stub" :tools {}
                                                       :auto-compact? false :turn-number 0}))]
          (is (true? (deref entered 5000 :timeout)))
          (let [[row] (tool-call-rows ctx)]
            (is (= :running (:tool-call/status row)) "visible while it runs")
            (is (inst? (:tool-call/started-at row)))
            (is (nil? (:tool-call/ended-at row))))
          (Thread/sleep 50)
          (deliver release true)
          (is (= :continue (deref turn 5000 :timeout)))
          (let [[row] (tool-call-rows ctx)]
            (is (= :completed (:tool-call/status row)))
            (is (= "call-slow" (:tool-call/tool-use-id row)))
            (is (not (.before ^java.util.Date (:tool-call/ended-at row)
                              ^java.util.Date (:tool-call/started-at row))))
            (is (<= 50 (:tool-call/duration-ms row)) "timed from its real start"))))
      (finally (chat-ctx/close-chat! ctx)))))

(deftest a-tool-that-throws-still-closes-its-record
  (let [ctx (chat-ctx/create-chat-context {:title "tool-throws" :with-sci? false})]
    (try
      (with-redefs [agent/messages->api-format (fn [messages _ _] messages)
                    model-chat/chat (fn [& _] (assoc (response "")
                                                     :tool-calls [{:id "call-bad" :name "fixture" :input {}}]))
                    tools/execute (fn [& _] (throw (ex-info "tool exploded" {})))]
        (try (agent/run-agent-turn! ctx {:provider :test :model "stub" :tools {}
                                         :auto-compact? false :turn-number 0})
             (catch Throwable _ nil))
        (let [[row] (tool-call-rows ctx)]
          (is (= :error (:tool-call/status row)) "not left :running")
          (is (str/includes? (:tool-call/result row) "tool exploded"))))
      (finally (chat-ctx/close-chat! ctx)))))

(deftest retired-model-charges-successor-price-and-persists-execution-id
  (let [ctx (chat-ctx/create-chat-context {:title "successor usage" :with-sci? false})
        old "accounts/fireworks/models/kimi-k2p5"
        successor "accounts/fireworks/models/kimi-k3"
        formatted (atom nil)
        executed (atom nil)]
    (try
      (with-redefs [agent/messages->api-format (fn [messages _ model] (reset! formatted model) messages)
                    model-chat/chat (fn [_ opts]
                                      (reset! executed (:model opts))
                                      (assoc (response "done") :usage {:input-tokens 1000}))]
        (is (= :complete (agent/run-agent-turn! ctx {:provider :fireworks :model old :tools {}
                                                     :auto-compact? false :turn-number 0})))
        (is (= successor @formatted @executed))
        (is (= 3000 (:used (chat-ctx/get-budget ctx))
               (accounting/get-total-cost (:db-conn ctx) (:chat-id ctx))))
        (is (= #{successor}
               (set (dh/q '[:find [?model ...] :in $ ?chat-id
                            :where [?c :chat/id ?chat-id] [?l :ledger/context ?c]
                            [?l :ledger/model ?model]]
                          @(:db-conn ctx) (:chat-id ctx)))))
        ;; Reading an old identity still uses its stored rate, never a successor's.
        (is (= 600 (accounting/calculate-cost :input-tokens 1000 {:model old}))))
      (finally (chat-ctx/close-chat! ctx)))))
