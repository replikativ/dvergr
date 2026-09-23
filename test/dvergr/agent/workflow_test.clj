(ns dvergr.agent.workflow-test
  "A task attempted several times per model on forks of a room: certified,
   billed, each world kept for review, one adopted, the rest discarded."
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.agent.workflow :as workflow]
            [dvergr.chat.agent :as chat-agent]
            [dvergr.discourse :as d]
            [dvergr.model.chat :as model-chat]
            [dvergr.model.providers :as providers]
            [dvergr.ops :as ops]
            [dvergr.room.registry :as rreg]
            [dvergr.room.store.memory :as memory]
            [dvergr.rooms.forks :as forks]
            [org.replikativ.spindel.engine.core :as ec]))

(def ^:private models ["claude-sonnet-4-5" "claude-haiku-4-5"])

(defmacro ^:private with-stub-model [calls & body]
  `(with-redefs [providers/ensure-initialized! (constantly nil)
                 chat-agent/messages->api-format (fn [messages# _# _#] messages#)
                 model-chat/chat (fn [_# opts#]
                                   (swap! ~calls conj (:model opts#))
                                   {:content "done: nothing to change"
                                    :tool-calls nil
                                    :usage {:input-tokens 1000 :output-tokens 100}
                                    :stop-reason :end-turn})]
     ~@body))

(deftest a-task-is-attempted-per-model-on-retained-forks
  (let [room (d/make-room {:id :workflow-test :store (memory/make)})
        calls (atom [])]
    (try
      (binding [ec/*execution-context* (:ctx room)] (rreg/register! room))
      (with-stub-model calls
        (let [rows (workflow/attempt! room {:task "tidy the notes" :attempts 2 :models models})]
          (testing "every model attempted the task on its own fork"
            (is (= 4 (count rows)))
            (is (= {"claude-sonnet-4-5" 2 "claude-haiku-4-5" 2} (frequencies @calls)))
            (is (= 4 (count (set (map :world rows)))) "one world per attempt"))
          (testing "each attempt is certified and billed"
            (doseq [{:keys [attempt]} rows
                    :let [receipt (:attempt/receipt attempt)]]
              (is (= :completed (:attempt/status receipt)))
              (is (= 1.0 (:attempt/reward receipt)))
              (is (= {:completed? true} (:attempt/checks receipt)))
              (let [sp (get-in receipt [:attempt/metrics :spend])]
                (is (pos? (:microdollars sp)) "priced from the registry")
                (is (= {:input 1000 :output 100} (:tokens sp))))))
          (testing "each world is kept for review, and reviewable"
            (doseq [{:keys [world review]} rows]
              (is (some? (binding [ec/*execution-context* (:ctx room)] (rreg/lookup world))))
              (is (= :trivial (:tier review)) "nothing changed in the stub run")))
          (testing "one world is adopted, the others discarded"
            (binding [ec/*execution-context* (:ctx room)]
              (let [[keep & drop] (map :world rows)]
                (is (:ok? (forks/merge! (rreg/lookup keep))))
                (doseq [w drop] (is (:ok? (forks/discard! (rreg/lookup w)))))
                (is (every? #(nil? (rreg/lookup %)) (map :world rows))
                    "no attempt world is left open"))))))
      (finally
        (try (binding [ec/*execution-context* (:ctx room)] (rreg/unregister! (:id room))) (catch Throwable _ nil))
        (d/close-room! room)))))

(deftest the-op-returns-the-table-and-the-way-to-adopt
  (let [room (d/make-room {:id :workflow-op-test :store (memory/make)})
        calls (atom [])]
    (try
      (binding [ec/*execution-context* (:ctx room)] (rreg/register! room))
      (with-stub-model calls
        (let [out (ops/invoke {:execution-ctx (:ctx room)} :workflow/attempt
                              {:room "workflow-op-test" :task "summarise" :attempts 2
                               :models ["claude-haiku-4-5"]})]
          (is (= :write (:kind (ops/specification :workflow/attempt))))
          (is (= 2 (count (:attempts out))))
          (is (every? #(and (string? (:world %)) (= :trivial (get-in % [:review :tier])))
                      (:attempts out)))
          (is (= [{:model "claude-haiku-4-5" :attempts 2 :completed 2}]
                 (mapv #(select-keys % [:model :attempts :completed]) (:by-model out))))
          (is (pos? (:microdollars-per-completion (first (:by-model out)))))
          (testing "adoption goes through the existing fork ops"
            (let [[a b] (map :world (:attempts out))]
              (is (= {:merged a} (ops/invoke {:execution-ctx (:ctx room)} :room/merge {:room a})))
              (is (= {:discarded b} (ops/invoke {:execution-ctx (:ctx room)} :room/discard {:room b})))))))
      (finally
        (try (binding [ec/*execution-context* (:ctx room)] (rreg/unregister! (:id room))) (catch Throwable _ nil))
        (d/close-room! room)))))

(deftest bounds-are-enforced
  (let [room (d/make-room {:id :workflow-bounds :store (memory/make)})]
    (try
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"needs a task"
                            (workflow/attempt! room {:task " "})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"between 1 and 8"
                            (workflow/attempt! room {:task "x" :attempts 9})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"at most 4 models"
                            (workflow/attempt! room {:task "x" :models ["a" "b" "c" "d" "e"]})))
      (finally (d/close-room! room)))))
