(ns dvergr.benchmarks.discovery-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [datahike.api :as dh]
            [dvergr.agent.evaluation :as evaluation]
            [dvergr.agent.roster :as roster]
            [dvergr.benchmarks.discovery :as discovery]
            [dvergr.benchmarks.frozen-web :as web]
            [dvergr.benchmarks.market-evidence :as evidence]
            [dvergr.chat.agent :as chat-agent]
            [dvergr.chat.schema :as schema]
            [dvergr.discourse :as d]
            [dvergr.io.acquisition :as acquisition]
            [dvergr.model.chat :as model-chat]
            [dvergr.model.providers :as providers]
            [dvergr.room.store.datahike :as store]
            [dvergr.room.store.memory :as memory]
            [dvergr.sandbox :as sandbox]
            [dvergr.substrate.git :as git]
            [org.replikativ.spindel.engine.core :as ec]))

(deftest fixture-capabilities-follow-context-forks
  (let [parent (d/make-room {:id :fixture-parent :store (memory/make)})]
    (try
      (binding [ec/*execution-context* (:ctx parent)]
        ((:prepare (discovery/world-setup)) {:room parent})
        (let [child (d/fork-room parent {:isolation :ctx})]
          (try
            (binding [ec/*execution-context* (:ctx child)]
              (is (= discovery/fixture-id (:id (ec/get-state [:dvergr.sandbox.ns.io/http-fixture]))))
              (let [sci (sandbox/fork-for-session (:ctx child))]
                (sandbox/setup-agent-namespaces! sci (:ctx child))
                (is (= (:body (get discovery/pages "https://example.org/aster"))
                       (:value (sandbox/eval-code sci "(:body (babashka.http-client/get \"https://example.org/aster\"))"
                                                  :execution-context (:ctx child)))))
                (is (= "offline-fixture" (:value (sandbox/eval-code sci "(env/get \"BRAVE_API_KEY\")"
                                                                    :execution-context (:ctx child)))))))
            (finally (d/close-room! child)))))
      (finally (d/close-room! parent)))))

(def candidate-code
  (str "(require '[babashka.http-client :as http] '[cheshire.core :as json] '[clojure.string :as str]) "
       (pr-str
        '(let [search (http/get "https://api.search.brave.com/res/v1/web/search"
                                {:query-params {:q "agent teams" :count 10}})
               hits (get-in (json/parse-string (:body search) true) [:web :results])]
           {:search (get-in search [:dvergr/acquisition :id])
            :alternatives
            (mapv (fn [hit]
                    (let [page (http/get (:url hit))]
                      {:url (:url hit) :receipt (get-in page [:dvergr/acquisition :id])
                       :quote (:body page)}))
                  (filter #(str/includes? (:description %) "supports") hits))}))))

(deftest provider-free-discovery-uses-real-tools-world-and-certification
  (let [cfg {:store {:backend :memory :id (random-uuid)} :schema-flexibility :write}
        _ (dh/create-database cfg)
        conn (dh/connect cfg)
        _ (schema/ensure-full-schema! conn)
        room (d/make-room {:id :discovery-workflow :store (store/make conn)
                           :meta {:http-capture discovery/capture-policy}})
        team (roster/make-agent (roster/make-roster)
                                {:id :researcher :tools #{:clojure_eval}
                                 :model-policy {:provider :test :model "stub"}
                                 :program {:kind :llm :max-model-steps 2 :auto-compact? false}})
        calls (atom 0)]
    (try
      (with-redefs [providers/ensure-initialized! (constantly nil)
                    git/ensure-repo! (fn [root] (.mkdirs (java.io.File. (str root))) root)
                    chat-agent/messages->api-format (fn [messages _ _] messages)
                    model-chat/chat
                    (fn [messages _]
                      (if (= 1 (swap! calls inc))
                        {:content "" :tool-calls [{:id "discovery" :name "clojure_eval"
                                                   :input {:code candidate-code}}]
                         :usage {:input-tokens 0 :output-tokens 0} :stop-reason :tool-use}
                        (let [content (:message/content (last (filter #(= :tool-result (:message/role %)) messages)))]
                          (when-not (and (string? content) (str/starts-with? content "=> "))
                            (throw (ex-info "Candidate tool failed" {:content content})))
                          {:content (str/trim (subs content 3)) :tool-calls nil
                           :usage {:input-tokens 0 :output-tokens 0} :stop-reason :end-turn})))]
        (binding [ec/*execution-context* (:ctx room)]
          (let [completion (promise)
                spin (evaluation/evaluate room team :researcher
                                          (discovery/definition) (discovery/evaluator)
                                          {:world-setup (discovery/world-setup)})
                _ (spin #(deliver completion {:result %}) #(deliver completion {:error %}))
                outcome (deref completion 200000 ::timeout)]
            (when (= ::timeout outcome) (throw (ex-info "Discovery timed out" {})))
            (when-let [error (:error outcome)] (throw error))
            (let [result (:result outcome)
                  receipt (:attempt-receipt result)
                  rows (acquisition/list-for-run conn (:id room) (:run/id result) 10)]
              (is (= 1.0 (:attempt/reward receipt)) (pr-str (:evidence result)))
              (is (every? true? (vals (:attempt/checks receipt))))
              (is (= 3 (count rows)))
              (is (every? #(= discovery/fixture-id (:acquisition/fixture-id %)) rows))
              (is (= :discarded (get-in result [:run/result :run/settlement-status])))
              (is (nil? (ec/get-state [:dvergr.sandbox.ns.io/http-fixture])))
              (is (= 2 @calls))
              (let [run-id (:run/id result)
                    answer (evidence/parse-answer (get-in result [:evidence :result]))
                    score #(discovery/score room run-id %)
                    scope {:conn conn :room-id (:id room) :run-id run-id
                           :artifacts (:artifacts (:store room))
                           :capture-policy discovery/capture-policy}
                    search (fn [query]
                             (acquisition/record-request!
                              {:url web/search-url :query-params {:q query}}
                              #((web/transport discovery/pages)
                                {:url web/search-url :query-params {:q query}})))]
                (is (zero? (:reward (score (assoc answer :search (random-uuid))))))
                (is (zero? (:reward (score (assoc answer :search "not-a-uuid")))))
                (is (zero? (:reward (discovery/score room (random-uuid) answer))))
                (is (zero? (:reward (score (assoc answer :search (:receipt (first (:alternatives answer))))))))
                (binding [acquisition/*scope* scope]
                  (let [narrow (search "approval")
                        foreign (binding [acquisition/*scope* (assoc scope :run-id (random-uuid))]
                                  (search "agent teams"))]
                    (doseq [response [narrow foreign]]
                      (is (zero? (:reward (score (assoc answer :search (get-in response [:dvergr/acquisition :id])))))))))
                (doseq [status [:failed :waiting :cancelled]]
                  (is (zero? (:reward ((:verify (discovery/evaluator)) (discovery/definition)
                                                                       (assoc (:evidence result) :execution-status status))))))
                (dh/transact conn [{:acquisition/id (:search answer)
                                    :acquisition/fixture-id (random-uuid)}])
                (is (zero? (:reward (score answer)))))))))
      (finally
        (evaluation/await-cleanups! room)
        (d/close-room! room)
        (dh/release conn)
        (dh/delete-database cfg)))))
