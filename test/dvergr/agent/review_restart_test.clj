(ns dvergr.agent.review-restart-test
  (:require [clojure.test :refer [deftest is]]
            [dvergr.agent.review :as review]
            [dvergr.agent.workflow :as workflow]
            [dvergr.agent.roster :as roster]
            [dvergr.discourse :as d]
            [dvergr.ops :as ops]
            [dvergr.orchestration.daemon :as daemon]
            [dvergr.principal :as principal]
            [dvergr.substrate.paths :as paths]
            [dvergr.system.db :as sdb]
            [org.replikativ.spindel.engine.core :as ec]))

(deftest startup-escalates-a-durably-claimed-review-once
  (let [previous (paths/home)
        home (str (System/getProperty "java.io.tmpdir") "/review-restart-" (random-uuid))
        current (atom nil)
        run-id (random-uuid)
        policy {:review/checks [:fork/review-tier [:diff/only-paths ["wiki/"]]]
                :review/on-pass :merge :review/on-fail :escalate}]
    (try
      (paths/set-home! home)
      (sdb/reset-conn!)
      (reset! current (daemon/start! {:agents {}}))
      (let [room-id (:id (ops/invoke @current principal/local-principal :room/create {:slug "pending-review"}))
            room (ops/resolve-room @current room-id)
            fork-id (:id (ops/invoke @current principal/local-principal :room/fork {:room room-id}))]
        (binding [ec/*execution-context* (:ctx room)]
          (is (review/claim! room run-id fork-id policy))
          (is (nil? (review/decision room run-id))))
        (daemon/stop! @current)
        (reset! current nil)
        (sdb/reset-conn!)
        (reset! current (daemon/start! {:agents {}}))
        (let [room (ops/resolve-room @current room-id)
              rv (binding [ec/*execution-context* (:ctx room)] (review/decision room run-id))]
          (is (= :escalated (:review/decision rv)))
          (is (= policy (:review/effective-policy rv)))
          (is (re-find #"restart" (str (:review/reasons rv))))
          (is (= 1 (count (filter #(= :review/escalated (get-in % [:metadata :kind])) (d/messages room)))))
          (binding [ec/*execution-context* (:ctx room)]
            (review/recover-interrupted! room)
            (is (= rv (review/decision room run-id)))
            (is (false? (review/claim! room run-id fork-id policy))))))
      (finally
        (when @current (daemon/stop! @current))
        (sdb/reset-conn!)
        (paths/set-home! previous)))))

(deftest startup-escalates-the-whole-batch-after-the-first-claim
  (let [previous (paths/home)
        home (str (System/getProperty "java.io.tmpdir") "/review-batch-restart-" (random-uuid))
        current (atom nil)
        claimed (atom 0)
        run-ids (atom nil)]
    (try
      (paths/set-home! home)
      (sdb/reset-conn!)
      (reset! current (daemon/start! {:agents {}}))
      (let [room-id (:id (ops/invoke @current principal/local-principal :room/create {:slug "review-batch"}))
            room (ops/resolve-room @current room-id)
            claim! review/claim!]
        (with-redefs [workflow/candidates
                      (fn [_]
                        {:team (roster/make-agent (roster/make-roster {:id :restart/test})
                                                  {:id :echo :prompt "Echo" :tools #{} :program {:kind :echo}})
                         :ids [:echo]})]
          (let [started (workflow/start room {:task "prepare" :attempts 2
                                              :principal principal/local-principal :daemon @current
                                              :workflow/writes {:repo ["wiki/"]}
                                              :review {:review/on-pass :propose}})
                rs (binding [ec/*execution-context* (:ctx started)] @(:spin started))]
            (reset! run-ids (mapv :run/id rs))
            ;; Simulate process death after the first claim: durable writes
            ;; stop working, including failure handlers. No reviewer can run.
            (with-redefs [review/claim! (fn [& args]
                                          (when (apply claim! args)
                                            (swap! claimed inc))
                                          (throw (ex-info "process stopped" {})))
                          review/failure! (fn [& _] (throw (ex-info "store stopped" {})))]
              (is (thrown? Exception ((:finish started) rs))))
            (is (= 1 @claimed))
            (is (every? nil? (map #(review/decision room %) @run-ids)))))
        (daemon/stop! @current)
        (reset! current nil)
        (sdb/reset-conn!)
        (reset! current (daemon/start! {:agents {}}))
        (let [room (ops/resolve-room @current room-id)
              decisions (mapv #(review/decision room %) @run-ids)]
          (is (= 2 (count decisions)))
          (is (every? #(= :escalated (:review/decision %)) decisions))
          (is (every? #(re-find #"restart" (str (:review/reasons %))) decisions))
          (is (= 2 (count (filter #(= :review/escalated (get-in % [:metadata :kind])) (d/messages room)))))))
      (finally
        (when @current (daemon/stop! @current))
        (sdb/reset-conn!)
        (paths/set-home! previous)))))
