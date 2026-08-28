(ns dvergr.agent.program-test
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.agent.program :as program]
            [dvergr.agent.roster :as roster]
            [dvergr.agent.run :as run]
            [dvergr.discourse :as d]
            [dvergr.room.registry :as registry]
            [dvergr.room.store.memory :as memory]
            [org.replikativ.spindel.core :as sp]
            [org.replikativ.spindel.effects.await :refer [await]]
            [org.replikativ.spindel.engine.context :as context]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.spin.combinators :as comb]))

(defn- test-roster []
  (-> (roster/make-roster {:id :test-team})
      (roster/make-agent {:id :analyst
                          :skills #{:research}
                          :program {:kind :scripted :reply "evidence"}})
      (roster/make-agent {:id :reviewer
                          :skills #{:review}
                          :program {:kind :echo}})))

(defn- test-room [id]
  (d/make-room {:id id :store (memory/make)}))

(defn- wait-until [pred timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (pred) true
        (>= (System/currentTimeMillis) deadline) false
        :else (do (Thread/sleep 5) (recur))))))

(deftest hire-is-run-backed-and-output-correlated
  (let [room (test-room :program-hire)
        team (test-roster)]
    (try
      (let [handle (binding [ec/*execution-context* (:ctx room)]
                     (program/hire! room team :analyst {:task "investigate"}))
            result (binding [ec/*execution-context* (:ctx room)] @handle)
            durable (program/observe room handle)
            messages (d/messages room {:limit 10})
            trigger (first (filter #(= (:run/trigger durable) (:id %)) messages))
            output (first (filter #(= (:run/id durable)
                                      (get-in % [:metadata :run-id]))
                                  messages))]
        (is (= :completed (:run/status result)))
        (is (= "evidence" (:run/value result)))
        (is (= :agent-task (:run/kind durable)))
        (is (= :analyst (:run/actor durable)))
        (is (= :test-team (:run/roster durable)))
        (is (= 1 (:run/agent-version durable)))
        (is (= :scripted (:run/program-kind durable)))
        (is (= program/interpreter-version (:run/interpreter-version durable)))
        (is (uuid? (:run/agent-def-hash durable)))
        (is (= "investigate" (:content trigger)))
        (is (= program/run-sink (:to trigger)))
        (is (= "evidence" (:content output)))
        (is (= program/run-sink (:to output)))
        (is (= (:id trigger) (:in-reply-to output)))
        (is (empty? (run/active-runs :program-hire))))
      (finally
        (d/close-room! room)))))

(deftest run-handles-compose-through-spindel-await
  (let [room (test-room :program-compose)
        team (test-roster)]
    (try
      (binding [ec/*execution-context* (:ctx room)]
        (let [a (program/hire! room team :analyst {:task "research"})
              b (program/hire! room team :reviewer {:task {:review "claim"}})
              joined (sp/spin
                      [(-> (await (program/result-spin a)) :run/value)
                       (-> (await (program/result-spin b)) :run/value)])]
          (is (= ["evidence" {:review "claim"}] @joined))))
      (finally
        (d/close-room! room)))))

(deftest cancellation-is-targeted-and-acknowledged
  (let [room (test-room :program-cancel)
        team (roster/make-agent
              (roster/make-roster)
              {:id :slow
               :program {:kind :scripted :delay-ms 500 :reply "too late"}})]
    (try
      (binding [ec/*execution-context* (:ctx room)]
        (let [handle (program/hire! room team :slow {:task "stop"})]
          (is (some? (program/observe room handle))
              "hire! returns only after durable admission")
          (is (program/cancel! handle))
          (is (= :cancelled (:run/status @handle)))
          (is (= :cancelled (:run/status (program/observe room handle))))
          (is (empty? (filter #(= (program/run-id handle)
                                  (get-in % [:metadata :run-id]))
                              (d/messages room {:limit 10}))))))
      (finally
        (d/close-room! room)))))

(deftest structural-parent-is-explicit
  (let [room (test-room :program-parent)
        team (test-roster)
        parent-id (random-uuid)]
    (try
      (binding [ec/*execution-context* (:ctx room)]
        (let [handle (program/hire! room team :analyst
                                    {:task "child" :parent-run parent-id})]
          @handle
          (is (= parent-id (:run/parent (program/observe room handle))))))
      (finally
        (d/close-room! room)))))

(deftest hire-validates-the-effect-envelope-before-starting
  (let [room (test-room :program-validation)
        team (test-roster)]
    (try
      (binding [ec/*execution-context* (:ctx room)]
        (is (thrown? clojure.lang.ExceptionInfo
                     (program/hire! room team :missing {:task "work"})))
        (is (thrown? clojure.lang.ExceptionInfo
                     (program/hire! room team :analyst {})))
        (is (thrown? clojure.lang.ExceptionInfo
                     (program/hire! room team :analyst
                                    {:task "work" :metadata {:callback identity}})))
        (is (thrown? clojure.lang.ExceptionInfo
                     (program/hire!
                      room
                      (roster/make-agent
                       (roster/make-roster)
                       {:id :typo :program {:kind :scripted :repy "missed"}})
                      :typo {:task "work"})))
        (is (empty? (run/active-runs (:id room))))
        (is (empty? (d/messages room {:limit 10}))))
      (finally
        (d/close-room! room)))))

(deftest closed-admission-rejects-hire-before-posting
  (let [room (test-room :program-admission-closed)
        team (test-roster)
        posts (atom 0)]
    (try
      (run/close-room-admission! room)
      (with-redefs [d/post! (fn [& _] (swap! posts inc))]
        (binding [ec/*execution-context* (:ctx room)]
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo
               #"admission is closed"
               (program/hire! room team :analyst {:task "too late"})))))
      (is (zero? @posts))
      (is (empty? (run/active-runs (:id room))))
      (is (empty? (d/messages room {:limit 10})))
      (finally
        (d/close-room! room)))))

(deftest teardown-cannot-miss-a-hire-between-admission-and-trigger-post
  (let [room (test-room :program-admission-race)
        team (roster/make-agent
              (roster/make-roster)
              {:id :slow
               :program {:kind :scripted :delay-ms 500 :reply "too late"}})
        entered-post (promise)
        release-post (promise)
        original-post d/post!]
    (try
      (with-redefs [d/post! (fn [target message]
                              (deliver entered-post true)
                              @release-post
                              (original-post target message))]
        (let [hire-future
              (future
                (binding [ec/*execution-context* (:ctx room)]
                  (program/hire! room team :slow {:task "racing close"})))
              _ (is (= true (deref entered-post 1000 ::timeout)))
              admitted-id (:run/id (first (run/active-runs (:id room))))
              close-future (future (d/close-room! room))]
          (is (wait-until #(run/cancel-requested? admitted-id) 1000)
              "close fenced admission and found the reserved Run")
          (is (false? (realized? close-future))
              "teardown waits rather than removing the substrate")
          (deliver release-post true)
          (let [handle (deref hire-future 2000 ::timeout)]
            (is (not= ::timeout handle))
            (is (nil? (deref close-future 2000 ::timeout)))
            (is (= :cancelled (:run/status (program/observe room handle))))
            (is (empty? (run/active-runs (:id room)))))))
      (finally
        (deliver release-post true)
        (d/close-room! room)))))

(deftest structured-race-cancels-the-losing-run-spin
  (let [room (test-room :program-race)
        team (roster/make-agent
              (roster/make-roster)
              {:id :slow
               :program {:kind :scripted :delay-ms 500 :reply "too late"}})]
    (try
      (binding [ec/*execution-context* (:ctx room)]
        (dotimes [_ 10]
          (let [handle (program/hire! room team :slow {:task "race"})
                winner (sp/spin :winner)]
            (is (= :winner @(comb/race winner (program/result-spin handle))))
            (is (wait-until #(= :cancelled
                                (:run/status (program/observe room handle)))
                            1000))))
        (is (empty? (run/active-runs (:id room)))))
      (finally
        (d/close-room! room)))))

(deftest direct-program-input-does-not-wake-a-same-id-participant
  (let [room (test-room :program-routing)
        team (test-roster)
        deliveries (atom 0)]
    (try
      (binding [ec/*execution-context* (:ctx room)]
        (d/join room
                (d/participant
                 {:id :analyst
                  :on-message (fn [_ _]
                                (sp/spin (swap! deliveries inc) nil))}))
        @(program/hire! room team :analyst {:task "direct"})
        (Thread/sleep 25)
        (is (zero? @deliveries)))
      (finally
        (d/close-room! room)))))

(deftest room-scoped-cancellation-rejects-another-rooms-run-id
  (let [a (test-room :program-room-a)
        b (test-room :program-room-b)
        team (roster/make-agent
              (roster/make-roster)
              {:id :slow
               :program {:kind :scripted :delay-ms 500 :reply "done"}})]
    (try
      (let [handle (binding [ec/*execution-context* (:ctx b)]
                     (program/hire! b team :slow {:task "private"}))]
        (is (false? (program/cancel! a (program/run-id handle))))
        (is (true? (program/cancel! b (program/run-id handle))))
        (binding [ec/*execution-context* (:ctx b)] @handle)
        (is (= :cancelled (:run/status (program/observe b handle)))))
      (finally
        (d/close-room! a)
        (d/close-room! b)))))

(deftest live-handles-reject-cross-fork-observation
  (let [room (test-room :program-context-owner)
        team (test-roster)
        fork-ctx (context/fork-context (:ctx room))]
    (try
      (let [handle (binding [ec/*execution-context* (:ctx room)]
                     (program/hire! room team :analyst {:task "owned"}))]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"another Spindel context"
             (binding [ec/*execution-context* fork-ctx]
               (program/result-spin handle))))
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"another Spindel context"
             (binding [ec/*execution-context* fork-ctx]
               (program/cancel! handle))))
        (binding [ec/*execution-context* (:ctx room)]
          (is (= :completed (:run/status @handle)))))
      (finally
        (context/close-context! fork-ctx)
        (d/close-room! room)))))

(deftest discarding-an-isolated-fork-settles-hired-runs-first
  (let [parent (test-room :program-fork-parent)
        fork (d/fork-room parent {:isolation :ctx})
        team (roster/make-agent
              (roster/make-roster)
              {:id :slow
               :program {:kind :scripted :delay-ms 500 :reply "too late"}})
        events (atom [])
        watch-key (Object.)]
    (run/watch-runs! watch-key #(swap! events conj %))
    (try
      (let [handle (binding [ec/*execution-context* (:ctx fork)]
                     (program/hire! fork team :slow {:task "discard"}))
            id (program/run-id handle)]
        (d/discard fork)
        (is (empty? (run/active-runs (:id fork))))
        (is (= :cancelled
               (some (fn [{:keys [type run]}]
                       (when (and (= :run/finished type)
                                  (= id (:run/id run)))
                         (:run/status run)))
                     @events)))
        (is (empty? (filter #(= id (get-in % [:metadata :run-id]))
                            (d/messages fork {:limit 10}))))
        (is (binding [ec/*execution-context* (:ctx parent)]
              (nil? (registry/lookup (:id fork))))))
      (finally
        (run/unwatch-runs! watch-key)
        (d/close-room! parent)))))

(deftest closing-a-room-settles-its-program-runs-before-closing-spindel
  (let [room (test-room :program-close)
        team (roster/make-agent
              (roster/make-roster)
              {:id :slow
               :program {:kind :scripted :delay-ms 500 :reply "too late"}})
        handle (binding [ec/*execution-context* (:ctx room)]
                 (program/hire! room team :slow {:task "close"}))]
    (d/close-room! room)
    (is (empty? (run/active-runs (:id room))))
    (is (= :cancelled (:run/status (program/observe room handle))))
    (is (nil? (d/close-room! room)) "close remains idempotent")))
