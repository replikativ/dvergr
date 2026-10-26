(ns dvergr.room.store.bounded-scorecard-test
  (:require [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [dvergr.agent.attempt :as attempt]
            [dvergr.agent.environment :as environment]
            [dvergr.agent.experiment :as experiment]
            [dvergr.agent.roster :as roster]
            [dvergr.artifact :as artifact]
            [dvergr.ops :as ops]
            [dvergr.room.store :as store]
            [dvergr.room.store.datahike :as native]
            [dvergr.scorecard.bounds :as bounds]
            [hasch.core :as hasch])
  (:import [java.util.concurrent Callable CountDownLatch Executors TimeUnit]))

(defn- with-store [f]
  (let [cfg {:store {:backend :memory :id (random-uuid)} :keep-history? false
             :schema-flexibility :write :max-string-length 0 :max-bytes-length 0}]
    (d/create-database cfg)
    (let [conn (d/connect cfg)]
      (try (f conn (native/make conn))
           (finally (d/release conn) (d/delete-database cfg))))))

(defn- seed! [st {:keys [environments repetitions candidates]
                  :or {environments 1 repetitions 1 candidates 1}}]
  (let [room :bounded-scorecard-test now (java.util.Date. 1000)
        agents (mapv (fn [n]
                       (let [id (keyword (str "candidate-" n))]
                         (-> (roster/make-roster)
                             (roster/make-agent {:id id :program {:kind :echo}})
                             (roster/agent id)))) (range candidates))
        agents-by-id (into {} (map (juxt :agent/id identity)) agents)
        worlds (mapv (fn [n]
                       (environment/make-environment
                        {:id (keyword "bounded" (str "case-" n)) :task n
                         :verifier {:id :bounded/exact :version 1}
                         :world {:isolation :ctx :settlement :review}})) (range environments))
        definition (experiment/make-experiment
                    {:id :bounded/report :dataset (experiment/make-dataset {:id :bounded/report :environments worlds})
                     :candidates agents :repetitions repetitions})]
    (store/-store-room! st room {:slug (name room)})
    (store/-store-room! st :foreign-scorecard-room {:slug "foreign-scorecard-room"})
    (let [results
          (vec (for [candidate (:experiment/candidates definition) world worlds rep (range repetitions)
                     :let [agent (get agents-by-id (:candidate/id candidate)) run (random-uuid)
                           receipt (environment/make-attempt-receipt
                                    world {:run-id run :provider :dvergr :model "echo" :status :completed
                                           :started-at 1000 :elapsed-ms 10
                                           :metrics {:program-kind :echo :model-resolution :not-applicable
                                                     :agent-version 1 :agent-def-hash (hasch/uuid agent) :interpreter-version 5}
                                           :checks {:exact? true} :reward 1.0
                                           :trace {:runs [{:run/id run :run/status :completed}]}})
                           value (attempt/make-attempt world agent receipt
                                                       {:trace {:runs [{:run/id run :run/status :completed}]}} :review)]]
                 (do
                   (store/-store-run! st room
                                      {:run/id run :run/kind :agent-task :run/room room :run/actor (:candidate/id candidate)
                                       :run/trigger (random-uuid) :run/status :completed :run/created-at now
                                       :run/started-at now :run/updated-at now :run/ended-at (java.util.Date. 1010)
                                       :run/agent-version 1 :run/program-kind :echo :run/interpreter-version 5
                                       :run/agent-def-hash (hasch/uuid agent)})
                   (store/-store-attempt! st room value)
                   {:experiment/job {:candidate/id (:candidate/id candidate) :candidate/agent (:candidate/agent candidate)
                                     :candidate/agent-content-id (:candidate/agent-content-id candidate)
                                     :environment (environment/environment-ref world) :repetition rep}
                    :attempt value})))
          scorecard (experiment/make-scorecard definition results)]
      (store/-store-scorecard! st room scorecard)
      {:room room :scorecard scorecard :id (:scorecard/content-id scorecard)})))

(deftest bounded-read-keeps-native-certification-and-summary-semantics
  (with-store
    (fn [_ st]
      (let [{:keys [room scorecard id]} (seed! st {})
            loaded (store/-load-scorecard-bounded st room id {})]
        (is (= scorecard loaded (store/-load-scorecard st room id)))
        (is (= scorecard (experiment/scorecard-bounded {:store st :id room} id)))
        (with-redefs [artifact/get-value (fn [& _] (throw (AssertionError. "Unbounded artifact read")))
                      store/-load-attempt (fn [& _] (throw (AssertionError. "Attempt payload read")))]
          (is (= (@#'ops/scorecard-data scorecard) (ops/scorecard-summary loaded)))
          (is (= scorecard (store/-load-scorecard-bounded st room id {})))
          (is (nil? (store/-load-scorecard-bounded st :foreign-scorecard-room id {}))))))))

(deftest both-relation-limits-precede-any-child-or-payload-expansion
  (doseq [[fixture caps] [[{:repetitions 2} {:max-cells 1}]
                          [{:candidates 2} {:max-candidates 1}]]]
    (with-store
      (fn [_ st]
        (let [{:keys [room id]} (seed! st fixture) pull d/pull]
          (with-redefs [artifact/get-bounded-value (fn [& _] (throw (AssertionError. "Payload expanded before relation bounds")))
                        d/pull (fn [db pattern entity]
                                 (is (not-any? #{:attempt/id :scorecard.summary/id} pattern) "Only scalar header pull admitted")
                                 (pull db pattern entity))]
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"relation exceeds"
                                  (store/-load-scorecard-bounded st room id caps)))))))))

(deftest protocol-invocation-cannot-raise-or-bypass-the-read-ceilings
  (with-store
    (fn [_ st]
      (with-redefs [d/q (fn [& _] (throw (AssertionError. "Invalid policy reached native lookup")))]
        (doseq [options [{:max-bytes (inc (:max-bytes bounds/ceilings))} {:max-cells 0}
                         {:max-candidates -1} {:unknown 1} nil]]
          (is (thrown? clojure.lang.ExceptionInfo
                       (store/-load-scorecard-bounded st :anything (random-uuid) options))))))))

(deftest bounded-reader-preserves-more-than-the-default-pull-limit
  (with-store
    (fn [_ st]
      ;; 11 environments x 91 repetitions is 1001 cells, within EACH
      ;; dimensional ceiling and the independent matrix/payload ceilings.
      (let [{:keys [room scorecard id]} (seed! st {:environments 11 :repetitions 91})
            loaded (store/-load-scorecard-bounded st room id {})]
        (is (= 1001 (count (:scorecard/entries loaded))))
        (is (= scorecard loaded))))))

(deftest cheap-value-bounds-and-byte-caps-precede-canonical-validation
  (with-store
    (fn [_ st]
      (let [{:keys [room id scorecard]} (seed! st {})]
        (with-redefs-fn
          {#'native/validate-scorecard (fn [& _] (throw (AssertionError. "Oversized input reached canonical validation")))}
          (fn []
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"read limit"
                                  (store/-load-scorecard-bounded st room id {:max-bytes 1})))
            (doseq [value [(assoc-in scorecard [:scorecard/experiment :experiment/repetitions] 129)
                           (assoc scorecard :scorecard/entries (vec (repeat 4097 {})))
                           (assoc-in scorecard [:scorecard/experiment :experiment/candidates] (vec (repeat 33 {})))
                           (-> scorecard
                               (assoc-in [:scorecard/experiment :experiment/candidates] (vec (repeat 32 {})))
                               (assoc-in [:scorecard/experiment :experiment/dataset :dataset/environments] (vec (repeat 128 {})))
                               (assoc-in [:scorecard/experiment :experiment/repetitions] 2))]]
              ;; A sequencing probe at the artifact seam. The real managed
              ;; payload/hash path is exercised by the native round-trip tests.
              (with-redefs [artifact/get-bounded-value (fn [& _] {:value value})]
                (is (thrown? clojure.lang.ExceptionInfo
                             (store/-load-scorecard-bounded st room id {})))))))))))

(deftest native-projection-corruption-is-still-rejected
  (with-store
    (fn [_ st]
      (let [{:keys [room id]} (seed! st {}) pull d/pull]
        (with-redefs [d/pull (fn [db pattern entity]
                               (cond-> (pull db pattern entity)
                                 (some #{:scorecard.summary/reward-mean} pattern)
                                 (assoc :scorecard.summary/reward-mean 0.0)))]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"projection differs"
                                (store/-load-scorecard-bounded st room id {}))))))))

(deftest missing-payload-and-unsupported-store-never-fall-back
  (with-store
    (fn [_ st]
      (let [{:keys [room id]} (seed! st {})]
        (with-redefs [artifact/get-bounded-value (constantly nil)
                      artifact/get-value (fn [& _] (throw (AssertionError. "Unbounded fallback")))]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unavailable"
                                (store/-load-scorecard-bounded st room id {})))))))
  (let [legacy (reify store/PScorecardStore
                 (-store-scorecard! [_ _ _] nil)
                 (-load-scorecard [_ _ _] (throw (AssertionError. "Unbounded protocol fallback")))
                 (-list-scorecards [_ _ _] []))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no bounded"
                          (experiment/scorecard-bounded {:id :legacy :store legacy} (random-uuid))))))

(deftest legacy-blob-only-scorecard-refuses-without-an-unbounded-read
  (with-store
    (fn [conn _]
      (let [values (atom {})
            legacy (reify artifact/PArtifactStore
                     (-put-value! [_ value]
                       (let [ref (str "legacy-" (random-uuid))]
                         (swap! values assoc ref value) ref))
                     (-get-value [_ ref] (get @values ref)))
            st (native/make conn legacy)
            {:keys [room id]} (seed! st {})]
        (with-redefs [artifact/get-value (fn [& _] (throw (AssertionError. "Unbounded legacy fallback")))
                      artifact/get-bounded-value (fn [& _] (throw (AssertionError. "Legacy reached managed reader")))]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"managed UUID"
                                (store/-load-scorecard-bounded st room id {}))))))))

(deftest blocked-artifact-read-keeps-one-original-projection-snapshot
  (with-store
    (fn [conn st]
      (let [{:keys [room id scorecard]} (seed! st {}) snapshot @conn
            entered (CountDownLatch. 1) release (CountDownLatch. 1)
            pool (Executors/newSingleThreadExecutor) reader-thread (atom nil)
            bounded artifact/get-bounded-value pull d/pull]
        (with-redefs [artifact/get-bounded-value
                      (fn [& arguments]
                        (.countDown entered)
                        (.await release)
                        (apply bounded arguments))
                      d/pull
                      (fn [db pattern entity]
                        (when (identical? @reader-thread (Thread/currentThread))
                          (is (= (:max-tx snapshot) (:max-tx db))
                              "All projection facts come from the original immutable DB"))
                        (pull db pattern entity))]
          (try
            (let [result (.submit pool ^Callable
                                  (bound-fn []
                                    (reset! reader-thread (Thread/currentThread))
                                    (store/-load-scorecard-bounded st room id {})))]
              (is (.await entered 10 TimeUnit/SECONDS))
              (store/-store-room! st :changed-during-read {:slug "changed-during-read"})
              (is (< (:max-tx snapshot) (:max-tx @conn)))
              (.countDown release)
              (is (= scorecard (.get result 30 TimeUnit/SECONDS))))
            (finally
              (.countDown release)
              (.shutdown pool)
              ;; Join physical work before restoring Vars or releasing stores.
              (loop []
                (when-not (.awaitTermination pool 10 TimeUnit/SECONDS)
                  (is false "Reader worker has not physically exited")
                  (.shutdownNow pool)
                  (recur))))))))))

(deftest foreign-attempt-ownership-refuses-before-projection-certification
  (with-store
    (fn [_ st]
      (let [{:keys [room id]} (seed! st {}) pull d/pull]
        (with-redefs [d/pull (fn [db pattern entity]
                               (cond-> (pull db pattern entity)
                                 (some #(and (map? %) (contains? % :attempt/chat)) pattern)
                                 (assoc :attempt/chat {:chat/id (random-uuid)})))
                      artifact/get-value (fn [& _] (throw (AssertionError. "Unbounded read")))]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"outside its Room"
                                (store/-load-scorecard-bounded st room id {}))))))))

(deftest unsupported-codec-refuses-before-payload-expansion
  (with-store
    (fn [_ st]
      (let [{:keys [room id]} (seed! st {}) pull d/pull]
        (with-redefs [d/pull (fn [db pattern entity]
                               (cond-> (pull db pattern entity)
                                 (some #{:scorecard/payload-codec} pattern)
                                 (assoc :scorecard/payload-codec :unsupported)))
                      artifact/get-bounded-value (fn [& _] (throw (AssertionError. "Unsupported codec reached decoder")))
                      artifact/get-value (fn [& _] (throw (AssertionError. "Unbounded read")))]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unsupported Scorecard payload codec"
                                (store/-load-scorecard-bounded st room id {}))))))))
