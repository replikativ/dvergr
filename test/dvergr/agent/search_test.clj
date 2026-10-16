(ns dvergr.agent.search-test
  "A search: alternatives of one task in their own worlds, weighed, joined by
   a policy, and recorded on every member's Run (doc/design/runs-as-worlds.md)."
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.agent.run :as run]
            [dvergr.agent.search :as search]
            [dvergr.chat.agent :as chat-agent]
            [dvergr.discourse :as d]
            [dvergr.model.chat :as model-chat]
            [dvergr.model.providers :as providers]
            [dvergr.room.registry :as rreg]
            [dvergr.room.store :as store]
            [dvergr.room.store.memory :as memory]
            [org.replikativ.spindel.engine.core :as ec]))

(deftest decisions-follow-the-weights
  (let [w [-1.0 -0.2 ##-Inf -0.2 -3.0]]
    (testing ":automatic merges the heaviest possible member, the earlier on a tie"
      (is (= [:discard :merge :discard :discard :discard] (search/decide w {:policy :automatic}))))
    (testing ":retain keeps the next-heaviest for inspection"
      (is (= [:review :merge :discard :review :discard] (search/decide w {:policy :automatic :retain 2}))))
    (testing ":review hands the heaviest to a person"
      (is (= [:discard :review :discard :discard :discard] (search/decide w {:policy :review}))))
    (testing ":keep-all keeps every member"
      (is (every? #{:review} (search/decide w {:policy :keep-all}))))
    (testing "an impossible member is never selected or retained"
      (is (= [:discard :discard] (search/decide [##-Inf ##-Inf] {:policy :automatic :retain 1}))))
    (testing "unknown policies and retentions are refused"
      (is (thrown? clojure.lang.ExceptionInfo (search/decide w {:policy :best})))
      (is (thrown? clojure.lang.ExceptionInfo (search/decide w {:retain -1}))))))

(deftest the-default-weight-is-the-log-reward
  (is (= 0.0 (search/reward-weight {:attempt {:attempt/receipt {:attempt/reward 1.0}}})))
  (is (= ##-Inf (search/reward-weight {:attempt {:attempt/receipt {:attempt/reward 0.0}}}))))

(defmacro ^:private with-stub-model [& body]
  `(with-redefs [providers/ensure-initialized! (constantly nil)
                 chat-agent/messages->api-format (fn [messages# _# _#] messages#)
                 model-chat/chat (fn [_# _#]
                                   {:content "done: nothing to change"
                                    :tool-calls nil
                                    :usage {:input-tokens 100 :output-tokens 10}
                                    :stop-reason :end-turn})]
     ~@body))

(defn- durable-search [room run-id]
  (run/search-record (store/-load-run (:store room) (store/conversation-id room) run-id)))

(defn- with-room [id f]
  (let [room (d/make-room {:id id :store (memory/make)})]
    (try
      (binding [ec/*execution-context* (:ctx room)] (rreg/register! room))
      (with-stub-model (f room))
      (finally
        (try (binding [ec/*execution-context* (:ctx room)] (rreg/unregister! (:id room))) (catch Throwable _ nil))
        (d/close-room! room)))))

(deftest a-search-merges-one-world-and-records-every-member
  (with-room :search-test
    (fn [room]
      (let [;; three alternatives, weighed by a judge: the second is best
            weights (atom [-2.0 -0.1 -1.0])
            weigh (fn [_] (let [w (first @weights)] (swap! weights subvec 1) w))
            outcome (search/search! room {:task "tidy the notes" :attempts 3
                                          :models ["claude-haiku-4-5"]
                                          :weigh weigh :retain 1})
            members (:search/members outcome)
            by-decision (group-by :search/decision members)]
        (testing "the heaviest member's world was merged, the next kept, the last discarded"
          (is (= [:discard :merge :review] (mapv :search/decision members)))
          (is (= (:run/id (second members)) (:search/selected outcome)))
          (is (every? (comp :ok? :search/outcome) members)))
        (binding [ec/*execution-context* (:ctx room)]
          (testing "only the retained world is still open"
            (is (some? (rreg/lookup (:world (first (:review by-decision))))))
            (is (nil? (rreg/lookup (:world (first (:merge by-decision))))))
            (is (nil? (rreg/lookup (:world (first (:discard by-decision))))))))
        (testing "every member's Run records the search, its weight and its decision"
          (doseq [{:keys [run/id search/weight search/decision]} members]
            (is (= {:search/id (:search/id outcome) :search/policy :automatic :search/members 3
                    :search/weight weight :search/decision decision}
                   (durable-search room id)))))
        (testing "the Runs' settlement follows the decision"
          (let [status #(:run/settlement-status
                         (store/-load-run (:store room) (store/conversation-id room) (:run/id %)))]
            (is (= :merged (status (first (:merge by-decision)))))
            (is (= :discarded (status (first (:discard by-decision)))))
            (is (= :review (status (first (:review by-decision)))))))))))

(deftest a-search-with-no-possible-member-merges-nothing
  (with-room :search-none-test
    (fn [room]
      (let [outcome (search/search! room {:task "tidy" :attempts 2 :models ["claude-haiku-4-5"]
                                          :policy :review :weigh (constantly ##-Inf)})]
        (is (nil? (:search/selected outcome)))
        (is (= [:discard :discard] (mapv :search/decision (:search/members outcome))))
        (is (every? #(= ##-Inf (:search/weight (durable-search room (:run/id %))))
                    (:search/members outcome))
            "an impossible weight survives the EDN round trip")))))
