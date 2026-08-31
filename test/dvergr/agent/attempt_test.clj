(ns dvergr.agent.attempt-test
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.agent.attempt :as attempt]
            [dvergr.agent.environment :as environment]
            [dvergr.agent.episode :as episode]
            [dvergr.agent.roster :as roster]
            [dvergr.room.store :as store]
            [dvergr.room.store.memory :as memory]
            [hasch.core :as hasch]))

(defn- fixture []
  (let [run-id (random-uuid)
        agent (-> (roster/make-roster)
                  (roster/make-agent {:id :candidate
                                      :program {:kind :echo}})
                  (roster/agent :candidate))
        definition
        (environment/make-environment
         {:id :bench/exact
          :task {:answer 42}
          :verifier {:id :bench/exact :version 2 :basis "git:abc"}
          :limits {:timeout-ms 1000}
          :world {:isolation :ctx :settlement :review}})
        receipt
        (environment/make-attempt-receipt
         definition
         {:run-id run-id :provider :dvergr :model "echo"
          :status :completed :started-at 1000 :elapsed-ms 25
          :metrics {:program-kind :echo :model-resolution :not-applicable
                    :agent-version 1 :agent-def-hash (hasch/uuid agent)
                    :interpreter-version 5}
          :checks {:exact? true} :reward 1.0
          :result {:answer 42}
          :trace {:runs [{:run/id run-id :run/status :completed}]}})
        certified (attempt/make-attempt definition agent receipt
                                        {:result {:answer 42}
                                         :trace {:runs [{:run/id run-id
                                                         :run/status :completed}]}}
                                        :review)]
    {:run-id run-id :agent agent :definition definition
     :receipt receipt :attempt certified}))

(defn- terminal-run [run-id room-id agent]
  (let [now (java.util.Date. 1000)]
    {:run/id run-id :run/kind :agent-task :run/room room-id
     :run/actor (:agent/id agent) :run/trigger (random-uuid)
     :run/status :completed :run/created-at now :run/started-at now
     :run/updated-at now :run/ended-at (java.util.Date. 1025)
     :run/agent-version (:agent/version agent) :run/program-kind :echo
     :run/interpreter-version 5 :run/agent-def-hash (hasch/uuid agent)}))

(defn- attempt-with-trace [run-id agent definition trace]
  (let [receipt
        (environment/make-attempt-receipt
         definition
         {:run-id run-id :provider :dvergr :model "echo"
          :status :completed :started-at 1000 :elapsed-ms 25
          :metrics {:program-kind :echo :model-resolution :not-applicable
                    :agent-version 1 :agent-def-hash (hasch/uuid agent)
                    :interpreter-version 5}
          :checks {:exact? true} :reward 1.0 :trace trace})]
    (attempt/make-attempt definition agent receipt {:trace trace} :review)))

(deftest certified-attempt-is-canonical-portable-data
  (let [{:keys [attempt run-id]} (fixture)]
    (is (= attempt (attempt/validate-attempt attempt)))
    (is (= run-id (:attempt/id attempt)))
    (is (= #{run-id} (:attempt/evidence-run-ids attempt)))
    (is (= (get-in attempt [:attempt/receipt :attempt/content-id])
           (:attempt/content-id attempt)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (attempt/validate-attempt
                  (assoc attempt :attempt/certified-at 9999))))))

(deftest memory-attempt-store-is-immutable-and-run-backed
  (let [{:keys [attempt run-id agent]} (fixture)
        room-id :attempt-memory
        st (memory/make)]
    (store/-store-room! st room-id {:slug (name room-id)})
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"missing or non-terminal"
                          (store/-store-attempt! st room-id attempt)))
    (store/-store-run! st room-id (terminal-run run-id room-id agent))
    (let [missing-run (random-uuid)
          missing-message (random-uuid)]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"evidence Run is missing"
           (store/-store-attempt!
            st room-id
            (attempt-with-trace
             run-id agent (:attempt/environment attempt)
             {:runs [{:run/id run-id} {:run/id missing-run}]}))))
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"evidence message is missing"
           (store/-store-attempt!
            st room-id
            (attempt-with-trace
             run-id agent (:attempt/environment attempt)
             {:runs [{:run/id run-id}]
              :messages [{:message/id missing-message}]})))))
    (is (= attempt (store/-store-attempt! st room-id attempt)))
    (is (= attempt (store/-store-attempt! st room-id attempt))
        "identical certification replay is idempotent")
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"identity is immutable"
                          (store/-store-attempt!
                           st room-id
                           (assoc attempt :attempt/settlement-intent :discard))))
    (is (= [attempt]
           (store/-list-attempts st room-id
                                 {:environment-id :bench/exact
                                  :provider :dvergr :status :completed})))
    (is (empty? (store/-list-attempts st room-id {:model "other"})))))

(deftest episode-is-a-read-join-not-a-second-lifecycle
  (let [{:keys [attempt run-id agent]} (fixture)
        room-id :episode-read
        st (memory/make)
        room {:id room-id :store st}]
    (store/-store-room! st room-id {:slug (name room-id)})
    (store/-store-run! st room-id (terminal-run run-id room-id agent))
    (store/-store-attempt! st room-id attempt)
    (let [exported (episode/export room run-id)]
      (is (= attempt (:episode/attempt exported)))
      (is (= :completed (get-in exported [:episode/run :run/status])))
      (is (integer? (get-in exported [:episode/run :run/started-at])))
      (is (= [run-id] (mapv :run/id (:episode/evidence-runs exported)))))
    (swap! (:state st) update-in [:runs room-id] dissoc run-id)
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"missing Run"
                          (episode/export room run-id)))))
