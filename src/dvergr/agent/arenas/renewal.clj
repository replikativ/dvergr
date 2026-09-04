(ns dvergr.agent.arenas.renewal
  "State-backed renewal-intervention arena.

   The account, evidence and proposed plan live in the evaluation Run's
   fork-local Room store. Conserved review capacity lives in the durable
   control Room's Kontor book. A candidate can therefore speculate on business
   state without copying or rolling back the authority it spent doing so."
  (:require [clojure.edn :as edn]
            [datahike.api :as d]
            [dvergr.agent.environment :as environment]
            [dvergr.agent.evaluation :as evaluation]
            [dvergr.agent.observation :as observation]
            [dvergr.resource :as resource]
            [dvergr.room.store :as store]
            [dvergr.room.store.datahike :as datahike-store]
            [dvergr.tools :as tools]
            [hasch.core :as hasch])
  (:import [java.util UUID]))

(def arena-version 1)
(def review-unit "renewal-review")
(def account-id :acme)

(def sales-signal-id
  (hasch/uuid [:dvergr/renewal-signal arena-version account-id :sales]))

(def support-signal-id
  (hasch/uuid [:dvergr/renewal-signal arena-version account-id :support]))

(def specialist-output-contracts
  {:sales
   {:record :renewal.signal
    :exact-fields
    [:renewal.signal/id :renewal.signal/source
     :renewal.signal/kind :renewal.signal/value]}
   :support
   {:record :renewal.signal
    :exact-fields
    [:renewal.signal/id :renewal.signal/source
     :renewal.signal/kind :renewal.signal/count
     :renewal.signal/severity]}})

(def arena-basis
  {:account {:id account-id
             :name "Acme"
             :value-microusd 120000000000
             :days-remaining 14}
   :signals [{:id sales-signal-id
              :source :sales
              :kind :renewal-date
              :value "14"}
             {:id support-signal-id
              :source :support
              :kind :open-critical
              :count 3
              :severity :high}]
   :required-plan {:risk :high
                   :action :executive-escalation
                   :status :proposed}
   :resource {review-unit 1}})

(def task-contract
  {:objective :propose-renewal-intervention
   :account account-id
   :specialists [{:id :sales :task :report-sales-evidence
                  :returns (:sales specialist-output-contracts)}
                 {:id :support :task :report-support-evidence
                  :returns (:support specialist-output-contracts)}]
   :coordination {:hire :both
                  :await :both
                  :use-returned-signal-ids-as-plan-evidence true}
   :submission-tool "renewal_plan"
   :requires-resource-receipt true
   :result {:exact-shape {:plan/id :uuid}}})

(def verification-contract
  {:setup :exact-scenario
   :plan {:count 1 :owner :root-run :id :content-derived
          :decision (:required-plan arena-basis)
          :evidence #{sales-signal-id support-signal-id}}
   :children {:count 2 :actors #{:sales :support}
              :status :completed :settlement :merged
              :causality :all-results-observed
              :results {:match :exact-seeded-record-by-actor
                        :contracts specialist-output-contracts}}
   :tool {:name "renewal_plan" :completed-count 1}
   :resource {:kind :consume :unit review-unit :amount 1}
   :result :returns-plan-id
   :reward {:all-checks 1.0 :otherwise 0.0}})

(def schema
  [{:db/ident :arena/id
    :db/valueType :db.type/uuid
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}
   {:db/ident :arena/kind
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}
   {:db/ident :arena/version
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one}

   {:db/ident :renewal.account/id
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}
   {:db/ident :renewal.account/name
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :renewal.account/value-microusd
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one}
   {:db/ident :renewal.account/days-remaining
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one}

   {:db/ident :renewal.signal/id
    :db/valueType :db.type/uuid
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}
   {:db/ident :renewal.signal/account
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one}
   {:db/ident :renewal.signal/source
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}
   {:db/ident :renewal.signal/kind
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}
   {:db/ident :renewal.signal/value
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :renewal.signal/count
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one}
   {:db/ident :renewal.signal/severity
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}

   {:db/ident :renewal.plan/id
    :db/valueType :db.type/uuid
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}
   {:db/ident :renewal.plan/account
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one}
   {:db/ident :renewal.plan/run-id
    :db/valueType :db.type/uuid
    :db/cardinality :db.cardinality/one}
   {:db/ident :renewal.plan/risk
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}
   {:db/ident :renewal.plan/action
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}
   {:db/ident :renewal.plan/evidence
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/many}
   {:db/ident :renewal.plan/status
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}])

(def arena-content-id
  (hasch/uuid [:dvergr/renewal-arena arena-version
               {:scenario arena-basis
                :task task-contract
                :schema schema
                :verification verification-contract}]))

(def setup-ref
  {:setup/id :business/renewal-arena
   :setup/version arena-version
   :setup/basis {:scenario/content-id arena-content-id}})

(def verifier-ref
  {:verifier/id :business/renewal-intervention
   :verifier/version arena-version
   :verifier/basis {:scenario/content-id arena-content-id}})

(def seed-tx
  [{:arena/id arena-content-id
    :arena/kind :renewal-risk
    :arena/version arena-version}
   {:renewal.account/id account-id
    :renewal.account/name "Acme"
    :renewal.account/value-microusd 120000000000
    :renewal.account/days-remaining 14}
   {:renewal.signal/id sales-signal-id
    :renewal.signal/account [:renewal.account/id account-id]
    :renewal.signal/source :sales
    :renewal.signal/kind :renewal-date
    :renewal.signal/value "14"}
   {:renewal.signal/id support-signal-id
    :renewal.signal/account [:renewal.account/id account-id]
    :renewal.signal/source :support
    :renewal.signal/kind :open-critical
    :renewal.signal/count 3
    :renewal.signal/severity :high}])

(defn plan-id [run-id]
  (hasch/uuid [:dvergr/renewal-plan arena-content-id run-id account-id]))

(defn charge-id [run-id]
  (hasch/uuid [:dvergr/renewal-charge arena-content-id run-id account-id]))

(defn mint-id [room-id amount]
  (hasch/uuid [:dvergr/renewal-mint arena-content-id room-id amount]))

(defn environment-def []
  (environment/make-environment
   {:id :business/renewal-intervention-v1
    :version arena-version
    :task task-contract
    :verifier {:id :business/renewal-intervention
               :version arena-version
               :basis {:scenario/content-id arena-content-id}}
    :limits {:timeout-ms 120000 :cancel-timeout-ms 10000
             ;; A malfunction fuse, not the conversational work budget.
             :max-model-steps 16 :budget-dollars 2.0}
    :world {:isolation :ctx
            :settlement :discard
            :resources {review-unit 1}
            :setup setup-ref}}))

(defn world-setup []
  (evaluation/make-world-setup
   {:id (:setup/id setup-ref)
    :version (:setup/version setup-ref)
    :basis (:setup/basis setup-ref)
    :prepare
    (fn [{:keys [room]}]
      (when-not (instance? dvergr.room.store.datahike.DatahikeStore
                           (:store room))
        (throw (ex-info "Renewal arena requires a fork-local Datahike Room store"
                        {:type ::datahike-world-required
                         :room/id (:id room)})))
      (let [conn (:conn (:store room))]
        (d/transact conn schema)
        (d/transact conn seed-tx)
        {:arena/content-id arena-content-id
         :account/id account-id
         :signal/ids #{sales-signal-id support-signal-id}}))}))

(defn provision-review-capacity!
  "Install and mint `amount` review units in the durable control Room."
  [room amount]
  (when-not (and (integer? amount) (pos? amount))
    (throw (ex-info "Renewal review capacity must be a positive integer"
                    {:type ::invalid-review-capacity :amount amount})))
  (resource/install-unit! room {:symbol review-unit
                                :name "Renewal reviews"
                                :precision 0})
  (resource/mint! room {:id (mint-id (:id room) amount)
                        :resources {review-unit amount}}))

(defn- parse-evidence-uuid [value]
  (cond
    (uuid? value) value
    (string? value) (try (UUID/fromString value)
                         (catch IllegalArgumentException _ nil))
    :else nil))

(defn- exact-arena? [conn]
  (= [:renewal-risk arena-version]
     (d/q '[:find [?kind ?version]
            :in $ ?id
            :where
            [?e :arena/id ?id]
            [?e :arena/kind ?kind]
            [?e :arena/version ?version]]
          @conn arena-content-id)))

(defn- load-account [conn]
  (d/q '[:find (pull ?e [:renewal.account/id
                         :renewal.account/name
                         :renewal.account/value-microusd
                         :renewal.account/days-remaining]) .
         :in $ ?id
         :where [?e :renewal.account/id ?id]]
       @conn account-id))

(defn- signals [conn]
  (d/q '[:find [(pull ?e [:renewal.signal/id
                          :renewal.signal/source
                          :renewal.signal/kind
                          :renewal.signal/value
                          :renewal.signal/count
                          :renewal.signal/severity]) ...]
         :in $ ?account
         :where
         [?a :renewal.account/id ?account]
         [?e :renewal.signal/account ?a]]
       @conn account-id))

(defn- plan [conn id]
  (d/q '[:find (pull ?e [:renewal.plan/id
                         :renewal.plan/run-id
                         :renewal.plan/risk
                         :renewal.plan/action
                         :renewal.plan/status
                         {:renewal.plan/account [:renewal.account/id]}
                         {:renewal.plan/evidence [:renewal.signal/id]}]) .
         :in $ ?id
         :where [?e :renewal.plan/id ?id]]
       @conn id))

(defn- expected-decision [account signals]
  (let [critical (some #(and (= :support (:renewal.signal/source %))
                             (= :open-critical (:renewal.signal/kind %))
                             (= :high (:renewal.signal/severity %))
                             (pos? (or (:renewal.signal/count %) 0)))
                       signals)
        urgent (<= (:renewal.account/days-remaining account) 30)]
    (when (and critical urgent)
      {:risk :high :action :executive-escalation})))

(defn- reject! [message data]
  (throw (ex-info message (assoc data :type ::invalid-plan-command))))

(def renewal-plan-tool
  {:name "renewal_plan"
   :description
   (str "After hiring and awaiting the :sales and :support specialists, propose "
        "the verified Acme intervention using the exact signal UUID returned by "
        "each child. Consumes one renewal-review unit.")
   :parameters
   {:type "object"
    :properties
    {:account {:type "string" :enum ["acme"]}
     :risk {:type "string" :enum ["high"]}
     :action {:type "string" :enum ["executive-escalation"]}
     :evidence {:type "array" :items {:type "string"} :minItems 2 :maxItems 2}}
    :required ["account" "risk" "action" "evidence"]}
   :execute
   (fn [{:keys [account risk action evidence]} ctx]
     (let [conn (:db-conn ctx)
           control-room (:control-room ctx)
           run-id (:run-id ctx)]
       (when-not (and conn control-room (uuid? run-id))
         (reject! "renewal_plan requires a Run-scoped Datahike/Kontor context"
                  {:run/id run-id}))
       (when-not (exact-arena? conn)
         (reject! "renewal_plan is unavailable outside the exact renewal arena"
                  {:arena/content-id arena-content-id}))
       (let [evidence-values (when (sequential? evidence) (vec evidence))
             parsed-evidence (when evidence-values
                               (mapv parse-evidence-uuid evidence-values))]
         (when-not (and (= 2 (count evidence-values))
                        (every? some? parsed-evidence)
                        (= 2 (count (distinct parsed-evidence))))
           (reject! "renewal_plan requires exactly two distinct signal UUIDs"
                    {:evidence evidence}))
         (let [account-row (load-account conn)
               signal-rows (signals conn)
               supplied-evidence (set parsed-evidence)
               expected-evidence (into #{} (map :renewal.signal/id) signal-rows)
               expected (expected-decision account-row signal-rows)
               command {:account (if (keyword? account) account (keyword account))
                        :risk (if (keyword? risk) risk (keyword risk))
                        :action (if (keyword? action) action (keyword action))}]
           (when-not (= account-id (:account command))
             (reject! "renewal_plan names an unknown account" {:account account}))
           (when-not (= expected (select-keys command [:risk :action]))
             (reject! "renewal_plan decision is not supported by arena state"
                      {:expected expected :actual (select-keys command [:risk :action])}))
           (when-not (= expected-evidence supplied-evidence)
             (reject! "renewal_plan requires the exact account evidence set"
                      {:expected expected-evidence :actual supplied-evidence}))
           (let [plan-id (plan-id run-id)
                 charge-id (charge-id run-id)
                 receipt (resource/consume!
                          control-room run-id
                          {:id charge-id :resources {review-unit 1}
                           :actor (:actor ctx)})]
             (d/transact
              conn
              [{:renewal.plan/id plan-id
                :renewal.plan/account [:renewal.account/id account-id]
                :renewal.plan/run-id run-id
                :renewal.plan/risk (:risk expected)
                :renewal.plan/action (:action expected)
                :renewal.plan/evidence
                (mapv (fn [id] [:renewal.signal/id id])
                      (sort-by str expected-evidence))
                :renewal.plan/status :proposed}])
             (let [value {:plan/id plan-id
                          :charge/id charge-id
                          :receipt/id (:id receipt)}]
               {:type :success
                :content (pr-str value)
                :metadata {:value value}}))))))})

(defn register-tool! []
  (tools/register! renewal-plan-tool)
  renewal-plan-tool)

(def expected-specialist-results
  {:sales {:renewal.signal/id sales-signal-id
           :renewal.signal/source :sales
           :renewal.signal/kind :renewal-date
           :renewal.signal/value "14"}
   :support {:renewal.signal/id support-signal-id
             :renewal.signal/source :support
             :renewal.signal/kind :open-critical
             :renewal.signal/count 3
             :renewal.signal/severity :high}})

(defn- read-result [value]
  (if (string? value)
    (try (edn/read-string value) (catch Throwable _ nil))
    value))

(defn- specialist-results-match? [children]
  (= expected-specialist-results
     (into {}
           (map (fn [child]
                  [(:run/actor child) (read-result (:run/value child))]))
           children)))

(defn- returned-plan-match? [plan result]
  (= {:plan/id (:renewal.plan/id plan)} result))

(defn- attach-specialist-results [children messages]
  (mapv
   (fn [child]
     (let [output
           (some #(when (and (= (:run/id child) (:message/run-id %))
                             (= (:run/actor child) (:message/from %))
                             (= :_runs (:message/to %))
                             (not (:message/content-truncated? %)))
                    (:message/content-preview %))
                 messages)]
       ;; Parse only the already-bounded projection and retain the canonical
       ;; value, never the candidate's raw message body.
       (assoc child :run/value (read-result output))))
   children))

(defn evaluator []
  (evaluation/make-evaluator
   {:id :business/renewal-intervention
    :version arena-version
    :basis {:scenario/content-id arena-content-id}
    :observe
    (fn [{control-room :room world-room :world/room
          setup-evidence :setup/evidence run-id :run-id result :result}]
      (let [conn (some-> world-room :store :conn)
            control-conn (some-> control-room :store :conn)
            plan-id (plan-id run-id)
            snapshot (observation/snapshot
                      control-room run-id
                      {:run-limit 20 :message-limit 100
                       :content-limit 1000 :content-budget 32000
                       :detail-limit 100})
            runs (:observation/runs snapshot)
            children (filterv #(= run-id (:run/parent %)) runs)
            messages (:observation/messages snapshot)
            children (attach-specialist-results children messages)
            receipt (store/-resource-receipt (:store control-room)
                                             (charge-id run-id))]
        {:setup setup-evidence
         :result (:run/value result)
         :plan (when conn (plan conn plan-id))
         :root (some #(when (= run-id (:run/id %)) %) runs)
         :children children
         :activities (:observation/activities snapshot)
         :completed-plan-tool-count
         (or (when control-conn
               (d/q '[:find (count ?call) .
                      :in $ ?run
                      :where
                      [?call :tool-call/run-id ?run]
                      [?call :tool-call/name "renewal_plan"]
                      [?call :tool-call/status :completed]]
                    @control-conn run-id))
             0)
         :receipt (select-keys receipt [:id :kind :source :destination
                                        :resources])}))
    :verify
    (fn [_ {:keys [setup result plan root children activities receipt
                   completed-plan-tool-count]}]
      (let [child-ids (into #{} (map :run/id) children)
            evidence-ids (into #{} (map :renewal.signal/id)
                               (:renewal.plan/evidence plan))
            plan-tools (filter #(= "renewal_plan" (:activity/tool-name %))
                               activities)
            result (if (string? result)
                     (try (edn/read-string result) (catch Throwable _ nil))
                     result)
            checks
            {:exact-setup? (= {:arena/content-id arena-content-id
                               :account/id account-id
                               :signal/ids #{sales-signal-id support-signal-id}}
                              setup)
             :one-plan? (some? plan)
             :root-owned-plan? (= (:run/id root) (:renewal.plan/run-id plan))
             :stable-plan-id? (= (plan-id (:run/id root)) (:renewal.plan/id plan))
             :decision-derived? (= [:high :executive-escalation :proposed]
                                   ((juxt :renewal.plan/risk
                                          :renewal.plan/action
                                          :renewal.plan/status) plan))
             :exact-evidence? (= #{sales-signal-id support-signal-id} evidence-ids)
             :two-specialists? (= #{:sales :support}
                                  (set (map :run/actor children)))
             :structural-parentage? (= 2 (count children))
             :all-results-observed? (= child-ids (set (:run/caused-by root)))
             :specialist-results? (specialist-results-match? children)
             :specialists-completed? (every? #(= :completed (:run/status %)) children)
             :specialists-merged? (every? #(= :merged (:run/settlement-status %)) children)
             :one-plan-tool? (and (= 1 (count plan-tools))
                                  (= 1 completed-plan-tool-count))
             :charged-once? (and (= :consume (:kind receipt))
                                 (= #{review-unit} (set (keys (:resources receipt))))
                                 (== 1 (get (:resources receipt) review-unit 0)))
             :returned-plan? (returned-plan-match? plan result)}]
        {:checks checks
         :reward (if (every? true? (vals checks)) 1.0 0.0)}))}))
