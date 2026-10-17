(ns dvergr.agent.settlement-truth-test
  (:require [clojure.test :refer [deftest is]]
            [datahike.api :as dh]
            [dvergr.agent.program :as program]
            [dvergr.agent.run :as run]
            [dvergr.agent.search :as search]
            [dvergr.agent.world :as world]
            [dvergr.discourse :as d]
            [dvergr.effects :as effects]
            [dvergr.room.registry :as registry]
            [dvergr.room.store :as store]
            [dvergr.room.store.memory :as memory]
            [dvergr.rooms.forks :as forks]
            [dvergr.substrate.datahike :as substrate]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.yggdrasil :as ygg]))

(defn- with-world [f]
  (let [room (d/make-room {:id (keyword (str "settlement-truth-" (random-uuid))) :store (memory/make)})
        configs (mapv (fn [_] {:store {:backend :memory :id (random-uuid)}
                               :schema-flexibility :write :keep-history? true :commit-graph? true}) (range 2))
        systems (mapv #(str "settlement-truth-" %) (range 2))
        connections (atom [])]
    (try
      (binding [ec/*execution-context* (:ctx room)]
        (registry/register! room)
        (doseq [[cfg sid] (map vector configs systems)]
          (let [conn (substrate/provision!
                      {:cfg cfg :system-name sid :schema? false
                       :extra-schema [{:db/ident :datum/id :db/valueType :db.type/keyword
                                       :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
                                      {:db/ident :datum/text :db/valueType :db.type/string
                                       :db/cardinality :db.cardinality/one}]})]
            (swap! connections conj conn)
            (dh/transact conn [{:datum/id :entry :datum/text "parent"}])))
        (let [w (world/open! room (random-uuid) :automatic)]
          (binding [ec/*execution-context* (:ctx (:work w))]
            (doseq [sid systems]
              (dh/transact (:conn (ygg/system sid)) [{:datum/id :entry :datum/text "child"}])))
          (f room w systems @connections)))
      (finally
        (d/close-room! room)
        (doseq [conn @connections] (dh/release conn))
        (doseq [cfg configs] (dh/delete-database cfg))))))

(defn- fail-after-first-system [f]
  (let [seat-var (ns-resolve 'org.replikativ.spindel.yggdrasil 'seat-merged!)
        seat @seat-var landed (atom nil)]
    (with-redefs-fn
      {seat-var (fn [ctx sid signal value opts]
                  (when (and @landed (not= sid @landed))
                    (throw (ex-info "Second system cannot finish settlement" {:test/failure :partial-merge})))
                  (let [result (seat ctx sid signal value opts)]
                    (compare-and-set! landed nil sid)
                    result))}
      #(f landed))))

(deftest partial-mutation-is-recovery-not-review-or-success
  (with-world
    (fn [room w systems conns]
      (let [result (fail-after-first-system
                    (fn [landed]
                      (let [result (world/settle! w :completed)]
                        (is (some? @landed))
                        (is (= "child" (dh/q '[:find ?text . :where [_ :datum/text ?text]]
                                             @(nth conns (.indexOf systems @landed)))))
                        result)))
            fork (:work w)]
        (is (= [:recovery-required :settlement-incomplete] ((juxt :status :reason) result)))
        (is (= :incomplete (:status (ygg/fork-disposition (d/fork-handle fork)))))
        (is (= fork (registry/lookup (:id fork))) "Structural recovery root remains registered")
        (is (nil? (d/settlement fork)))
        (is (false? (@#'search/confirmed-merged? fork)) "Inactive merge claim is not terminal evidence")
        (is (= :recovery-required (:status (world/settle! w :completed))))
        (let [prepared (atom 0)
              error (try
                      (d/transfer-fork! fork :review {:prepare! (fn [_] (swap! prepared inc)) :abort! (fn [_])})
                      nil (catch clojure.lang.ExceptionInfo error error))]
          (is (= :dvergr.discourse/fork-not-open (:type (ex-data error))))
          (is (zero? @prepared)))
        (with-redefs [world/settle! (fn [& _] (throw (ex-info "settlement unavailable" {})))]
          (let [projection (@#'program/settlement-result w {:run/id (random-uuid) :run/status :completed})]
            (is (= :recovery-required (get-in projection [:result :run/settlement-status])))))))))

(deftest preflight-refusal-leaves-an-open-reviewable-world
  (with-world
    (fn [_ w _ conns]
      (with-redefs [ygg/fork-conflicts (constantly [{:system :fixture}])]
        ;; Explicit guard failure is before substrate mutation and permits review.
        (let [merge-room d/merge-room
              result (with-redefs [d/merge-room (fn [parent fork]
                                                  (merge-room parent fork {:guard (fn [_] (throw (ex-info "Preflight refused" {})))}))]
                       (world/settle! w :completed))]
          (is (= :review (:status result)))
          (is (ygg/open-fork? (d/fork-handle (:work w))))
          (is (every? #(= "parent" (dh/q '[:find ?text . :where [_ :datum/text ?text]] @%)) conns))
          (d/discard (:work w)))))))

(deftest search-never-projects-partial-settlement-as-a-successful-winner
  (doseq [failure-stage [:none :run-projection :search-row :stale-success]]
    (with-world
      (fn [room w _ _]
        (let [fork (:work w) run-id (:run-id @(:meta fork))
              read-run #(store/-load-run (:store room) (store/conversation-id room) run-id)]
          (swap! (:meta fork) assoc :settlement-policy :deferred)
          (effects/install-world! (:ctx fork) [[:admit (:admit search/speculative-effects)] [:speculative]])
          (store/-store-run! (:store room) (store/conversation-id room)
                             {:run/id run-id :run/room (:id room) :run/actor :fixture
                              :run/kind :echo :run/trigger (random-uuid)
                              :run/created-at (java.util.Date.) :run/started-at (java.util.Date.)
                              :run/updated-at (java.util.Date.) :run/ended-at (java.util.Date.)
                              :run/status :completed :run/world (:id fork)
                              :run/settlement-status :deferred})
          (let [search-id (random-uuid)
                failed? (atom false)
                update! run/update-durable-settlement!
                record! run/record-search!
                member {:run/id run-id :attempt {:attempt/receipt {:attempt/run-id run-id}}
                        :world (:id fork) :review {:state (forks/state-token fork)}}
                initial (with-redefs
                         [run/update-durable-settlement!
                          (fn [r id status & more]
                            (when (and (= :run-projection failure-stage) (= :recovery-required status)
                                       (compare-and-set! failed? false true))
                              (throw (ex-info "Recovery projection unavailable" {})))
                            (apply update! r id status more))
                          run/record-search!
                          (fn [r id data]
                            (when (and (= :search-row failure-stage) (= :incomplete (:search/status data))
                                       (compare-and-set! failed? false true))
                              (throw (ex-info "Search projection unavailable" {})))
                            (record! r id data))]
                          (try (fail-after-first-system
                                (fn [landed]
                                  (let [result (search/select! room [member] {:policy :automatic :search-id search-id
                                                                              :weigh (constantly 0.0)})]
                                    (is (some? @landed))
                                    result)))
                               (catch clojure.lang.ExceptionInfo error error)))
                write-failure? (boolean (#{:run-projection :search-row} failure-stage))
                _ (is (= write-failure? @failed?))
                _ (is (= write-failure? (instance? Throwable initial)))
                _ (when (= :stale-success failure-stage)
                    ;; Reproduce a result falsely projected by the old claim-
                    ;; based proof. Canonical incomplete must override it.
                    (run/update-durable-settlement! room run-id :merged :search-selected)
                    (run/record-search! room run-id
                                        (assoc (run/search-record (read-run))
                                               :search/status :settled :search/outcome :merged)))
                merge-calls (atom 0)
                result (if (= :none failure-stage) initial
                           (with-redefs [forks/merge! (fn [& _] (swap! merge-calls inc)
                                                        (throw (ex-info "Must not repeat substrate merge" {})))]
                             (search/select! room [member] {:policy :automatic :search-id search-id})))
                row (first (:search/members result))]
            (is (zero? @merge-calls))
            (is (nil? (:search/selected result)))
            (is (= [:recovery-required :incomplete] ((juxt :search/outcome :search/status) row)))
            (is (= :recovery-required (:run/settlement-status (read-run))))
            (is (= :incomplete (:status (ygg/fork-disposition (d/fork-handle fork)))))
            (let [again (search/select! room [member] {:policy :automatic :search-id (:search/id result)})]
              (is (nil? (:search/selected again)))
              (is (= [:recovery-required :incomplete]
                     ((juxt :search/outcome :search/status) (first (:search/members again)))))
              (is (= :recovery-required (:run/settlement-status (read-run)))))
            (is (= fork (registry/lookup (:id fork))))))))))
