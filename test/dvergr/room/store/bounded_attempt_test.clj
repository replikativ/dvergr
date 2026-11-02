(ns dvergr.room.store.bounded-attempt-test
  (:require [clojure.test :refer [deftest is]]
            [datahike.api :as dh]
            [dvergr.agent.attempt :as attempt]
            [dvergr.agent.environment :as environment]
            [dvergr.agent.episode :as episode]
            [dvergr.agent.evaluation :as evaluation]
            [dvergr.agent.roster :as roster]
            [dvergr.agent.run :as run]
            [dvergr.artifact :as artifact]
            [dvergr.attempt.bounds :as bounds]
            [dvergr.discourse :as discourse]
            [dvergr.room.registry :as registry]
            [dvergr.room.store :as store]
            [dvergr.room.store.datahike :as native]
            [konserve.core :as k]
            [org.replikativ.spindel.engine.core :as ec]))

(defn- with-certified [f]
  (let [cfg {:store {:backend :memory :id (random-uuid)} :keep-history? false
             :schema-flexibility :write :max-string-length 0 :max-bytes-length 0}]
    (dh/create-database cfg)
    (let [conn (dh/connect cfg) st (native/make conn)
          room (discourse/make-room {:id (keyword (str "bounded-attempt-" (random-uuid))) :store st})
          source-message (discourse/message :test :_room-log "Frozen source evidence" nil {:role :system})
          group (evaluation/cleanup-group)
          team (roster/make-agent (roster/make-roster) {:id :candidate :program {:kind :echo}})
          env (environment/make-environment
               {:id :bounded/exact :task {:answer 42}
                :verifier {:id :bounded/exact :version 1 :basis :bounded/v1}
                :limits {:timeout-ms 30000 :cancel-timeout-ms 1000}
                :world {:isolation :ctx :settlement :discard}})
          evaluator (evaluation/make-evaluator
                     {:id :bounded/exact :version 1 :basis :bounded/v1
                      :capture (fn [_] {:files {"answer.txt" "42\n"}})
                      :observe (fn [{:keys [default] captured :execution/evidence}]
                                 (-> default
                                     (assoc :files (:files captured))
                                     (update-in [:trace :messages] (fnil conj []) {:message/id (:id source-message)})))
                      :verify (fn [_ evidence]
                                {:checks {:exact? (= {:answer 42} (:result evidence))} :reward 1.0})})]
      (try
        (binding [ec/*execution-context* (:ctx room)]
          (store/-store-message! st (:id room) source-message)
          (let [result @(evaluation/evaluate room team :candidate env evaluator {:cleanup-group group})
                id (:run/id result)]
            (is (true? (evaluation/await-cleanups-for! room group 30000)))
            (is (= :discarded (:run/settlement-status (run/run room id))))
            (is (nil? (registry/lookup (get-in result [:run/result :run/world]))))
            (f {:conn conn :store st :room room :id id :value (:attempt result)
                :message (:id source-message)})))
        (finally
          (try (evaluation/await-cleanups-for! room group 30000)
               (finally (discourse/close-room! room)))
          (dh/release conn)
          (dh/delete-database cfg))))))

(deftest certified-evidence-remains-readable-after-the-real-world-is-discarded
  (with-certified
    (fn [{:keys [store room id value message]}]
      (with-redefs [artifact/get-value (fn [& _] (throw (AssertionError. "Unbounded artifact read")))
                    store/-load-attempt (fn [& _] (throw (AssertionError. "Unbounded protocol read")))]
        (let [loaded (episode/attempt-bounded room id)]
          (is (= value loaded))
          (is (= #{message} (:attempt/evidence-message-ids loaded)))
          (is (= {"answer.txt" "42\n"} (get-in loaded [:attempt/evidence :files])))
          (is (= loaded (store/-load-attempt-bounded store (:id room) id {})))
          (is (nil? (store/-load-attempt-bounded store :another-room id {})))
          (is (nil? (episode/attempt-bounded room (random-uuid))))))
      (with-redefs [artifact/get-bounded-value (fn [& _] (throw (AssertionError. "Missing/foreign Attempt read artifact")))]
        (is (nil? (store/-load-attempt-bounded store :another-room id {})))
        (is (nil? (episode/attempt-bounded room (random-uuid))))))))

(deftest invalid-options-and-unsupported-stores-do-not-fall-back
  (doseq [options [nil {:unknown 1} {:max-bytes 0} {:max-checks -1}
                   {:max-bytes (inc (:max-bytes bounds/ceilings))}]]
    (is (thrown? clojure.lang.ExceptionInfo (episode/attempt-bounded {} (random-uuid) options))))
  (is (thrown? clojure.lang.ExceptionInfo (episode/attempt-bounded {} nil)))
  (let [legacy (reify store/PAttemptStore
                 (-store-attempt! [_ _ _] nil)
                 (-load-attempt [_ _ _] (throw (AssertionError. "Unbounded fallback")))
                 (-list-attempts [_ _ _] []))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no bounded"
                          (episode/attempt-bounded {:id :legacy :store legacy} (random-uuid)))))
  (with-certified
    (fn [{:keys [store room id]}]
      (with-redefs [dh/q (fn [& _] (throw (AssertionError. "Invalid input reached native lookup")))]
        (is (thrown? clojure.lang.ExceptionInfo (store/-load-attempt-bounded store nil id {})))
        (is (thrown? clojure.lang.ExceptionInfo (store/-load-attempt-bounded store (:id room) nil {})))
        (is (thrown? clojure.lang.ExceptionInfo (store/-load-attempt-bounded store (:id room) id {:max-checks 0})))))))

(deftest every-relation-prefix-is-bounded-before-child-or-artifact-expansion
  (with-certified
    (fn [{:keys [store room id]}]
      (let [datoms dh/datoms pull dh/pull]
        (doseq [[attribute limit] [[:attempt/checks :max-checks]
                                   [:attempt/evidence-runs :max-evidence-runs]
                                   [:attempt/evidence-messages :max-evidence-messages]]]
          (with-redefs [dh/datoms (fn [db index & components]
                                    (if (and (= :eavt index) (= attribute (second components)))
                                      (repeat 2 {:v -1})
                                      (apply datoms db index components)))
                        dh/pull (fn [db pattern entity]
                                  (is (every? keyword? pattern) "Only scalar header expansion before relation bounds")
                                  (pull db pattern entity))
                        artifact/get-bounded-value (fn [& _] (throw (AssertionError. "Early refusal read payload")))]
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"relation exceeds"
                                  (store/-load-attempt-bounded store (:id room) id {limit 1})))))))))

(deftest codec-managed-reference-and-owned-root-refuse-before-artifact-io
  (with-certified
    (fn [{:keys [store room id]}]
      (let [pull dh/pull]
        (doseq [change [(fn [value] (assoc value :attempt/payload-codec :unsupported))
                        (fn [value] (assoc value :attempt/payload-ref nil :attempt/payload-blob "legacy"))]]
          (with-redefs [dh/pull (fn [db pattern entity]
                                  (let [value (pull db pattern entity)]
                                    (if (some #{:attempt/payload-codec} pattern) (change value) value)))
                        artifact/get-bounded-value (fn [& _] (throw (AssertionError. "Invalid header read payload")))]
            (is (thrown? clojure.lang.ExceptionInfo (episode/attempt-bounded room id)))))
        (doseq [key [:run/id :run/chat]]
          (with-redefs [dh/pull (fn [db pattern entity]
                                  (let [value (pull db pattern entity)]
                                    (if (some #{:run/id} pattern)
                                      (assoc value key (if (= key :run/id) (random-uuid) {:chat/id (random-uuid)})) value)))
                        artifact/get-bounded-value (fn [& _] (throw (AssertionError. "Foreign root read payload")))]
            (is (thrown? clojure.lang.ExceptionInfo (episode/attempt-bounded room id)))))))))

(deftest payload-and-projection-validation-remain-exact-and-bounded
  (with-certified
    (fn [{:keys [store room id value]}]
      (is (thrown? clojure.lang.ExceptionInfo (episode/attempt-bounded room id {:max-bytes 1})))
      (with-redefs [artifact/get-bounded-value (constantly nil)]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unavailable" (episode/attempt-bounded room id))))
      (with-redefs [artifact/get-bounded-value (fn [& _] {:value (assoc-in value [:attempt/receipt :attempt/checks]
                                                                           {:one true :two true})})
                    attempt/validate-attempt (fn [& _] (throw (AssertionError. "Cardinality work reached canonical validation")))]
        (is (thrown? clojure.lang.ExceptionInfo (episode/attempt-bounded room id {:max-checks 1}))))
      (with-redefs [artifact/get-bounded-value (fn [& _] {:value (assoc value :attempt/content-id (random-uuid))})]
        (is (thrown? clojure.lang.ExceptionInfo (episode/attempt-bounded room id))))
      (let [pull dh/pull]
        (with-redefs [dh/pull (fn [db pattern entity]
                                (cond-> (pull db pattern entity)
                                  (some #{:attempt/reward} pattern) (assoc :attempt/reward 0.0)))]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"projection differs" (episode/attempt-bounded room id))))))))

(deftest missing-root-foreign-message-and-duplicate-checks-refuse-before-payload
  (with-certified
    (fn [{:keys [room id]}]
      (let [datoms dh/datoms pull dh/pull]
        (with-redefs [dh/datoms (fn [db index & components]
                                  (if (and (= :eavt index) (= :attempt/run (second components)))
                                    [] (apply datoms db index components)))
                      artifact/get-bounded-value (fn [& _] (throw (AssertionError. "Missing root read payload")))]
          (is (thrown? clojure.lang.ExceptionInfo (episode/attempt-bounded room id))))
        (with-redefs [dh/pull (fn [db pattern entity]
                                (let [value (pull db pattern entity)]
                                  (if (some #{:message/id} pattern)
                                    (assoc value :message/chat {:chat/id (random-uuid)}) value)))
                      artifact/get-bounded-value (fn [& _] (throw (AssertionError. "Foreign message read payload")))]
          (is (thrown? clojure.lang.ExceptionInfo (episode/attempt-bounded room id))))
        ;; Deliberately corrupt the typed relation read; do not bypass native
        ;; governance to construct a new uncertified Attempt as a fixture.
        (with-redefs [dh/datoms (fn [db index & components]
                                  (let [actual (apply datoms db index components)]
                                    (if (and (= :eavt index) (= :attempt/checks (second components)))
                                      (repeat 2 (first actual)) actual)))
                      artifact/get-bounded-value (fn [& _] (throw (AssertionError. "Duplicate checks read payload")))]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"check relation is corrupt"
                                (episode/attempt-bounded room id))))))))

(deftest corrupt-managed-bytes-are-verified-before-edn-or-attempt-decoding
  (with-certified
    (fn [{:keys [room id]}]
      (with-redefs [k/bget (fn [_ _ consume _]
                             (consume {:input-stream (java.io.ByteArrayInputStream. (.getBytes "{}" "UTF-8"))}))
                    attempt/validate-attempt (fn [& _] (throw (AssertionError. "Corrupt bytes reached Attempt validation")))]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"identity mismatch" (episode/attempt-bounded room id)))))))

(deftest projection-facts-stay-on-one-immutable-snapshot
  (with-certified
    (fn [{:keys [conn store room id value]}]
      (let [before @conn pull dh/pull bounded artifact/get-bounded-value]
        (with-redefs [artifact/get-bounded-value
                      (fn [& args]
                        ;; Deterministic intervening unrelated write at the IO
                        ;; seam. Every subsequent projection read must stay old.
                        (store/-store-room! store :changed-during-read {:slug "changed-during-read"})
                        (apply bounded args))
                      dh/pull (fn [db pattern entity]
                                (is (= (:max-tx before) (:max-tx db)))
                                (pull db pattern entity))]
          (is (= value (episode/attempt-bounded room id))))
        (is (< (:max-tx before) (:max-tx @conn)))))))

(deftest restricted-diagnostics-never-reflect-corrupt-native-projection-values
  (with-certified
    (fn [{:keys [room id]}]
      (let [pull dh/pull huge (apply str (repeat (* 1024 1024) "x"))
            error (with-redefs [dh/pull (fn [db pattern entity]
                                          (cond-> (pull db pattern entity)
                                            (some #{:attempt/model} pattern) (assoc :attempt/model huge)))]
                    (try (episode/attempt-bounded room id) nil (catch clojure.lang.ExceptionInfo error error)))]
        (is (= :room-store/invalid-bounded-attempt (:type (ex-data error))))
        (is (= :attempt/model (:key (ex-data error))))
        (is (<= (count (pr-str (ex-data error))) 1024))
        (is (nil? (ex-cause error)))
        (is (not-any? #(contains? (ex-data error) %) [:expected :actual :attempt :payload]))))))
