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
            [org.replikativ.spindel.engine.context :as sctx]
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
              (is (= a (:merged (ops/invoke {:execution-ctx (:ctx room)} :room/merge {:room a}))))
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

(defmacro ^:private with-room [[sym id] & body]
  `(let [~sym (d/make-room {:id ~id :store (memory/make)})]
     (try
       (binding [ec/*execution-context* (:ctx ~sym)] (rreg/register! ~sym))
       ~@body
       (finally
         (try (binding [ec/*execution-context* (:ctx ~sym)] (rreg/unregister! (:id ~sym)))
              (catch Throwable _# nil))
         (d/close-room! ~sym)))))

(deftest a-workflow-runs-as-a-job-a-client-polls
  (with-room [room :workflow-job-test]
    (let [daemon {:execution-ctx (:ctx room)} calls (atom [])]
      (with-stub-model calls
        (let [started (ops/invoke daemon :workflow/start
                                  {:room "workflow-job-test" :task "summarise" :attempts 2
                                   :models ["claude-haiku-4-5"]})
              job (:id started)]
          (is (= "running" (:status started)) "returns at once")
          (is (pos? (:poll-after-ms started)))
          (let [done (loop [n 0]
                       (let [st (ops/invoke daemon :job/status {:job job :wait-ms 5000})]
                         (if (or (not= "running" (:status st)) (< 20 n)) st (recur (inc n)))))
                result (:result done)]
            (is (= "completed" (:status done)) (pr-str (dissoc done :result)))
            (is (= 2 (count (:attempts result))))
            (is (every? #(string? (get-in % [:review :state])) (:attempts result))
                "each attempt's review carries the state to pin a merge to")
            (is (contains? result :wallet) "the result says what is left to spend")
            (is (some #(= job (:id %)) (ops/invoke daemon :job/list {:room "workflow-job-test"})))
            (is (not-any? :result (ops/invoke daemon :job/list {})) "lists stay small")
            (let [[a b] (:attempts result)]
              (is (= (:world a) (:merged (ops/invoke daemon :room/merge
                                                     {:room (:world a)
                                                      :expect-state (get-in a [:review :state])}))))
              (ops/invoke daemon :room/discard {:room (:world b)}))))))))

(deftest a-job-can-be-cancelled
  (with-room [room :workflow-cancel-test]
    (let [daemon {:execution-ctx (:ctx room)} release (promise)]
      (with-redefs [providers/ensure-initialized! (constantly nil)
                    chat-agent/messages->api-format (fn [messages _ _] messages)
                    model-chat/chat (fn [_ _]
                                      (deref release 20000 nil)
                                      {:content "late" :tool-calls nil
                                       :usage {:input-tokens 1 :output-tokens 1} :stop-reason :end-turn})]
        (let [job (:id (ops/invoke daemon :workflow/start
                                   {:room "workflow-cancel-test" :task "slow" :models ["claude-haiku-4-5"]}))
              cancelled (ops/invoke daemon :job/cancel {:job job})]
          (is (= "cancelled" (:status cancelled)))
          (deliver release true)
          (Thread/sleep 500)
          (is (= "cancelled" (:status (ops/invoke daemon :job/status {:job job})))
              "work finishing after the cancel does not revive the job"))))))

(deftest a-merge-is-pinned-to-the-reviewed-state
  (with-room [room :workflow-pin-test]
    (let [daemon {:execution-ctx (:ctx room)}
          fork (binding [ec/*execution-context* (:ctx room)] (forks/fork! room))
          fid (name (:id fork))]
      (with-redefs [forks/state-token (fn [_] "state-now")]
        (is (= "state-now" (:state (ops/invoke daemon :room/review {:room fid}))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"changed since it was reviewed"
                              (ops/invoke daemon :room/merge {:room fid :expect-state "state-then"})))
        (is (some? (binding [ec/*execution-context* (:ctx room)] (rreg/lookup (:id fork))))
            "a refused merge leaves the fork open")
        (is (= fid (:merged (ops/invoke daemon :room/merge {:room fid :expect-state "state-now"}))))))))

(deftest a-failed-merge-is-reported-as-a-failure
  (with-room [room :workflow-merge-fail-test]
    (let [daemon {:execution-ctx (:ctx room)}
          fork (binding [ec/*execution-context* (:ctx room)] (forks/fork! room))]
      (with-redefs [forks/reconcile-merge! (fn [_] {:ok? false :error "parent is gone"})]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"failed: parent is gone"
                              (ops/invoke daemon :room/merge {:room (name (:id fork))})))))))

(deftest a-daemon-room-is-evaluated-in-its-own-context
  ;; A daemon room's context is a fork of the daemon's (dvergr.system.rooms).
  ;; Evaluating it in the daemon's context loses the Run's wakeups: it hung
  ;; until its timeout. The ops pass the daemon; the workflow must use the room.
  (let [host (d/make-room {:id :workflow-host :store (memory/make)})
        daemon {:execution-ctx (:ctx host)}
        room-ctx (binding [ec/*execution-context* (:ctx host)] (sctx/fork-context (:ctx host)))
        room (d/make-room {:id :workflow-child :store (memory/make) :ctx room-ctx})
        calls (atom [])]
    (try
      (binding [ec/*execution-context* (:ctx host)] (rreg/register! room))
      (with-stub-model calls
        (let [job (:id (ops/invoke daemon :workflow/start
                                   {:room "workflow-child" :task "summarise" :attempts 2
                                    :models ["claude-haiku-4-5"] :timeout-ms 20000}))
              done (ops/invoke daemon :job/status {:job job :wait-ms 25000})]
          (is (= "completed" (:status done)) (pr-str (dissoc done :result)))
          (is (= ["completed" "completed"] (mapv :status (get-in done [:result :attempts]))))))
      (finally
        (try (binding [ec/*execution-context* (:ctx host)] (rreg/unregister! (:id room))) (catch Throwable _ nil))
        (d/close-room! room)
        (d/close-room! host)))))
