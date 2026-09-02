(ns dvergr.agent.observation-test
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as dh]
            [dvergr.activity :as activity]
            [dvergr.agent.observation :as observation]
            [dvergr.agent.run :as run]
            [dvergr.chat.schema :as schema]
            [dvergr.discourse :as d]
            [dvergr.room.store.datahike :as datahike]
            [dvergr.room.store.memory :as memory]))

(deftest snapshots-are-bounded-to-one-structural-run-tree
  (let [room (d/make-room {:id :scoped-observation :store (memory/make)})
        root-trigger (d/post! room (d/message :operator :orchestrator
                                              "prepare the revenue brief"))
        sibling-trigger (d/post! room (d/message :operator :unrelated
                                                 "unrelated private work"))
        root (run/start! room :orchestrator root-trigger nil)
        sibling (run/start! room :unrelated sibling-trigger nil)
        child-trigger (d/post! room (d/message :orchestrator :analyst
                                               "inspect renewal risk"))
        child (run/start! room :analyst child-trigger nil
                          {:parent (:run/id root)})
        fact {:activity/id (random-uuid)
              :activity/run-id (:run/id child)
              :activity/kind :tool
              :activity/verb :invoke
              :activity/tool-name "crm_query"
              :activity/tool-use-id "crm-1"
              :activity/at (java.util.Date.)}]
    (try
      (d/post! room
               (d/reply :analyst :_activity
                        (apply str (repeat 80 "risk "))
                        child-trigger
                        {:role :tool
                         :run-id (:run/id child)
                         :activities [fact]
                         :tool-uses [{:id "crm-1" :name "crm_query"
                                      :input {:account :private}}]}))
      (run/finish! (:run/id child) :completed)
      (let [scoped (observation/snapshot room (:run/id root)
                                         {:content-limit 32})
            operator (observation/snapshot room nil)
            actors (mapv :run/actor (:observation/runs scoped))
            tool-message (some #(when (= (:run/id child)
                                         (:message/run-id %))
                                  %)
                               (:observation/messages scoped))]
        (testing "a nested observer sees its root and descendants, not siblings"
          (is (= [:orchestrator :analyst] actors))
          (is (= [(:run/id root)] (:observation/frontier scoped)))
          (is (= 1 (get-in scoped [:observation/summary :activities])))
          (is (empty? (:observation/failures scoped)))
          (is (= #{:orchestrator :analyst :unrelated}
                 (set (map :run/actor (:observation/runs operator))))))
        (testing "tool inputs stay out of the compact observation"
          (is (= [{:tool-use/id "crm-1" :tool-use/name "crm_query"}]
                 (:message/tool-uses tool-message)))
          (is (not (re-find #"private" (pr-str tool-message))))
          (is (.endsWith ^String (:message/content-preview tool-message) "…")))
        (testing "the semantic activity keeps exact Run correlation"
          (is (= (:run/id child)
                 (-> scoped :observation/activities first
                     :activity/run-id)))
          (is (= (activity/message-run-id
                  {:metadata {:run-id (:run/id child)}})
                 (:run/id child)))))
      (testing "a foreign or stale scope fails closed"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"not a Run in this Room"
                              (observation/snapshot room (random-uuid)))))
      (finally
        (run/finish! (:run/id child) :completed)
        (run/finish! (:run/id root) :completed)
        (run/finish! (:run/id sibling) :completed)
        (d/close-room! room)))))

(deftest observation-limits-are-context-safety-boundaries
  (let [room (d/make-room {:id :observation-limits :store (memory/make)})]
    (try
      (is (thrown? clojure.lang.ExceptionInfo
                   (observation/snapshot room nil {:run-limit 0})))
      (is (thrown? clojure.lang.ExceptionInfo
                   (observation/snapshot room nil {:message-limit 5001})))
      (is (thrown? clojure.lang.ExceptionInfo
                   (observation/snapshot room nil {:content-budget 64001})))
      (is (thrown? clojure.lang.ExceptionInfo
                   (observation/snapshot room nil {:detail-limit 501})))
      (is (thrown? clojure.lang.ExceptionInfo
                   (observation/snapshot room nil {:raw-tool-inputs? true})))
      (finally
        (d/close-room! room)))))

(deftest observation-bounds-total-content-and-nested-detail
  (let [room (d/make-room {:id :observation-detail-limits
                           :store (memory/make)})
        trigger (d/post! room (d/message :human :agent "start"))
        current (run/start! room :agent trigger nil)
        facts (mapv (fn [index]
                      {:activity/id (random-uuid)
                       :activity/run-id (:run/id current)
                       :activity/kind :tool
                       :activity/verb :invoke
                       :activity/tool-name (str "tool-" index)})
                    (range 25))
        tool-uses (mapv (fn [index]
                          {:id (str "tool-" index)
                           :name (str "tool-" index)
                           :input {:secret index}})
                        (range 25))]
    (try
      (dotimes [_ 2]
        (d/post! room
                 (d/message :agent :_activity (apply str (repeat 20 "x"))
                            nil
                            {:run-id (:run/id current)
                             :activities facts
                             :tool-uses tool-uses})))
      (let [view (observation/snapshot room (:run/id current)
                                       {:content-limit 10 :content-budget 12})
            messages (:observation/messages view)
            detailed (filter :message/activity-count messages)]
        (is (<= (reduce + 0 (map #(count (or (:message/content-preview %) ""))
                                 messages))
                12))
        (is (every? #(= 25 (:message/activity-count %)) detailed))
        (is (every? #(= 20 (count (:message/activities %))) detailed))
        (is (every? :message/activities-truncated? detailed))
        (is (every? #(= 20 (count (:message/tool-uses %))) detailed))
        (is (not (re-find #"secret" (pr-str messages)))))
      (let [view (observation/snapshot room (:run/id current)
                                       {:detail-limit 10})]
        (is (<= (+ (count (:observation/activities view))
                   (reduce + 0
                           (map #(count (:message/tool-uses %))
                                (:observation/messages view))))
                10)))
      (finally
        (run/finish! (:run/id current) :completed)
        (d/close-room! room)))))

(deftest an-unfunded-datahike-room-has-no-resource-view
  (let [cfg {:store {:backend :memory :id (random-uuid)}
             :keep-history? false
             :schema-flexibility :write}]
    (dh/create-database cfg)
    (let [conn (dh/connect cfg)]
      (try
        (schema/ensure-full-schema! conn)
        (let [room (d/make-room {:id :observation-unfunded
                                 :store (datahike/make conn)})]
          (try
            (let [root-trigger (d/post! room (d/message :human :root "root"))
                  sibling-trigger (d/post! room (d/message :human :sibling
                                                           "private"))
                  root (run/start! room :root root-trigger nil)
                  child-trigger (d/post! room (d/message :root :child "child"))
                  child (run/start! room :child child-trigger nil
                                    {:parent (:run/id root)})
                  sibling (run/start! room :sibling sibling-trigger nil)]
              (try
                (let [view (observation/snapshot room (:run/id root)
                                                 {:run-limit 2
                                                  :message-limit 2})]
                  (is (= [:root :child]
                         (mapv :run/actor (:observation/runs view))))
                  (is (= #{(:id root-trigger) (:id child-trigger)}
                         (set (map :message/id
                                   (:observation/messages view)))))
                  (is (not (contains? view :observation/resources))))
                (finally
                  (run/finish! (:run/id child) :completed)
                  (run/finish! (:run/id root) :completed)
                  (run/finish! (:run/id sibling) :completed))))
            (finally
              (d/close-room! room))))
        (finally
          (dh/release conn)
          (dh/delete-database cfg))))))

(deftest scope-filtering-happens-before-room-global-limits
  (let [room (d/make-room {:id :observation-noisy-room :store (memory/make)})
        root-trigger (d/post! room (d/message :human :root "root"))
        root (run/start! room :root root-trigger nil)
        child-trigger (d/post! room (d/message :root :child "child"))
        child (run/start! room :child child-trigger nil {:parent (:run/id root)})]
    (try
      (dotimes [index 205]
        (let [trigger (d/post! room (d/message :noise :noise (str index)))
              sibling (run/start! room :noise trigger nil)]
          (run/finish! (:run/id sibling) :completed)))
      (let [view (observation/snapshot room (:run/id root)
                                       {:run-limit 2 :message-limit 2})]
        (is (= [:root :child]
               (mapv :run/actor (:observation/runs view))))
        (is (= #{(:id root-trigger) (:id child-trigger)}
               (set (map :message/id (:observation/messages view))))))
      (is (= [:root]
             (mapv :run/actor
                   (:observation/runs
                    (observation/snapshot room (:run/id root)
                                          {:run-limit 1})))))
      (finally
        (run/finish! (:run/id child) :completed)
        (run/finish! (:run/id root) :completed)
        (d/close-room! room)))))
