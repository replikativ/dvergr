(ns dvergr.agent.arenas.renewal-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as dh]
            [dvergr.agent.arenas.renewal :as renewal]
            [dvergr.agent.evaluation :as evaluation]
            [dvergr.agent.experiment :as experiment]
            [dvergr.agent.program :as program]
            [dvergr.agent.roster :as roster]
            [dvergr.agent.run :as run]
            [dvergr.agent.world :as world]
            [dvergr.chat.agent :as chat-agent]
            [dvergr.discourse :as discourse]
            [dvergr.model.chat :as model-chat]
            [dvergr.model.providers :as providers]
            [dvergr.orchestration.daemon :as daemon]
            [dvergr.resource :as resource]
            [dvergr.room.registry :as registry]
            [dvergr.room.store :as store]
            [dvergr.rooms :as rooms]
            [dvergr.substrate.paths :as paths]
            [dvergr.system.db :as system-db]
            [dvergr.system.rooms :as system-rooms]
            [dvergr.tools :as tools]
            [kontor.governance :as kontor-governance]
            [org.replikativ.spindel.engine.context :as context]
            [org.replikativ.spindel.engine.core :as ec]))

(defn- delete-tree! [path]
  (when (.exists (io/file path))
    (run! io/delete-file (reverse (file-seq (io/file path))))))

(defn- with-production-room [f]
  (let [original-home (paths/home)
        test-home (str (io/file (System/getProperty "java.io.tmpdir")
                                (str "dvergr-renewal-arena-" (random-uuid))))
        root (atom nil)
        room (atom nil)]
    (try
      (paths/set-home! test-home)
      (system-db/reset-conn!)
      (system-rooms/clear-room-ctxs!)
      (let [root-ctx (daemon/create-shared-context :with-git? false)]
        (reset! root root-ctx)
        (binding [ec/*execution-context* root-ctx]
          (let [slug (str "renewal-arena-" (random-uuid))
                room-id (rooms/create-room! {:slug slug :title "Renewal arena"
                                             :parent-id false})
                live (registry/lookup room-id)]
            (when-not live
              (throw (ex-info "Production renewal test Room was not provisioned"
                              {:room/id room-id})))
            (reset! room live)
            (binding [ec/*execution-context* (:ctx live)]
              (f live)))))
      (finally
        (when-let [live @room]
          (try
            (binding [ec/*execution-context* (:ctx live)]
              (kontor-governance/ungovern! (:conn (:store live)))
              (discourse/close-room! live))
            (catch Throwable _)))
        (system-rooms/clear-room-ctxs!)
        (system-db/reset-conn!)
        (when-let [root-ctx @root]
          (try (context/close-context! root-ctx) (catch Throwable _)))
        (paths/set-home! original-home)
        (delete-tree! test-home)))))

(defn- invoke-setup! [setup work-room run-id]
  ((:prepare setup) {:room work-room
                     :run/id run-id
                     :environment (renewal/environment-def)}))

(deftest portable-task-exposes-recursion-and-verifier-requires-child-results
  (let [task (:environment/task (renewal/environment-def))
        real-children (mapv (fn [[actor value]]
                              {:run/id (random-uuid)
                               :run/actor actor :run/value value})
                            renewal/expected-specialist-results)
        messages (mapv (fn [{:run/keys [id actor value]}]
                         {:message/run-id id :message/from actor
                          :message/to :_runs
                          :message/content-truncated? false
                          :message/content-preview (pr-str value)})
                       real-children)
        children-without-values (mapv #(dissoc % :run/value) real-children)]
    (is (= renewal/task-contract task))
    (is (= #{:sales :support} (set (map :id (:specialists task)))))
    (is (= {:exact-shape {:plan/id :uuid}} (:result task)))
    (is (every? #(= :renewal.signal (get-in % [:returns :record]))
                (:specialists task)))
    (is (every? #(not (contains? (:returns %) :required-fields))
                (:specialists task)))
    (is (true? (#'renewal/specialist-results-match? real-children)))
    (is (false? (#'renewal/specialist-results-match?
                 (update-in real-children [0 :run/value]
                            assoc :renewal.signal/extra true)))
        "the visible exact-fields contract rejects extra child output")
    (is (false? (#'renewal/specialist-results-match?
                 (mapv #(assoc % :run/value nil) real-children)))
        "child topology without the specialists' returned evidence is insufficient")
    (is (true? (#'renewal/specialist-results-match?
                (#'renewal/attach-specialist-results
                 children-without-values messages))))
    (is (false? (#'renewal/specialist-results-match?
                 (#'renewal/attach-specialist-results
                  children-without-values
                  (mapv #(assoc % :message/content-truncated? true) messages))))
        "a truncated child body is never parsed or certified")
    (let [id (random-uuid)
          plan {:renewal.plan/id id}]
      (is (true? (#'renewal/returned-plan-match? plan {:plan/id id})))
      (is (false? (#'renewal/returned-plan-match? plan {:plan-id id})))
      (is (false? (#'renewal/returned-plan-match?
                   plan {:plan/id id :extra true}))))))

(deftest setup-and-semantic-tool-use-the-real-fork-and-affine-book
  (with-production-room
    (fn [room]
      (renewal/provision-review-capacity! room 1)
      (let [run-id (random-uuid)
            trigger (discourse/message :test :_runs "renewal" nil {:role :user})
            run-world (world/open! room run-id :discard)
            work-room (:work run-world)
            setup (renewal/world-setup)]
        (try
          (discourse/post! room trigger)
          (run/start! room :candidate trigger nil {:id run-id})
          (resource/allocate-run! room run-id nil {renewal/review-unit 1})
          (let [setup-evidence (invoke-setup! setup work-room run-id)
                work-conn (:conn (:store work-room))
                parent-conn (:conn (:store room))
                tool-ctx {:db-conn work-conn
                          :control-room room
                          :run-id run-id
                          :actor :candidate
                          :tools {"renewal_plan" renewal/renewal-plan-tool}}
                valid-input {:account "acme"
                             :risk "high"
                             :action "executive-escalation"
                             :evidence [(str renewal/sales-signal-id)
                                        (str renewal/support-signal-id)]}]
            (is (= renewal/arena-content-id (:arena/content-id setup-evidence)))
            (is (= renewal/arena-content-id
                   (dh/q '[:find ?id . :where [?e :arena/id ?id]] @work-conn)))
            (is (nil? (dh/q '[:find ?id . :where [?e :arena/id ?id]] @parent-conn))
                "trusted setup writes only into the Run branch")

            (testing "invalid evidence is rejected before charging"
              (doseq [evidence [[(str renewal/sales-signal-id)]
                                [(str renewal/sales-signal-id)
                                 (str renewal/support-signal-id)
                                 "not-a-uuid"]
                                [(str renewal/sales-signal-id)
                                 (str renewal/sales-signal-id)]]]
                (let [invalid (tools/execute
                               "renewal_plan"
                               (assoc valid-input :evidence evidence)
                               tool-ctx)]
                  (is (= :error (:type invalid)) (pr-str evidence invalid))
                  (is (= {renewal/review-unit 1M}
                         (resource/run-balance room run-id)))
                  (is (nil? (store/-resource-receipt
                             (:store room) (renewal/charge-id run-id)))))))

            (testing "a stable retry neither writes nor charges twice"
              (let [first-result (tools/execute "renewal_plan" valid-input tool-ctx)
                    second-result (tools/execute "renewal_plan" valid-input tool-ctx)
                    charge (store/-resource-receipt
                            (:store room) (renewal/charge-id run-id))]
                (is (= :success (:type first-result) (:type second-result))
                    (pr-str first-result second-result))
                (is (= (get-in first-result [:metadata :value])
                       (get-in second-result [:metadata :value])))
                (is (= :consume (:kind charge)))
                (is (= {renewal/review-unit 1M} (:resources charge)))
                (is (= 1
                       (dh/q '[:find (count ?plan) .
                               :where [?plan :renewal.plan/id]]
                             @work-conn)))
                (is (= {} (resource/run-balance room run-id)))))
            (let [{:keys [status reason]} (world/settle! run-world :completed)]
              (run/finish! run-id :completed
                           {:settlement-status status
                            :settlement-reason reason}))
            (is (nil? (registry/lookup (:id run-world))))
            (is (nil? (dh/q '[:find ?id . :where [?e :arena/id ?id]] @parent-conn))
                "discard leaves the durable parent business state unchanged"))
          (finally
            (when (some #(= run-id (:run/id %)) (run/active-runs))
              (run/finish! run-id :cancelled {:reason :test-cleanup}))
            (when (registry/lookup (:id run-world))
              (discourse/discard (:work run-world)))))))))

(def recursive-evidence-program
  (str
   "(require '[datahike.api :as d] '[dvergr.room :as room] "
   "         '[dvergr.agent :as agent] "
   "         '[org.replikativ.spindel.spin.cps :refer [spin]] "
   "         '[org.replikativ.spindel.effects.await :refer [await]] "
   "         '[spindel.comb :as comb]) "
   "(let [signals (d/q '[:find [(pull ?e [:renewal.signal/id "
   "                                      :renewal.signal/source "
   "                                      :renewal.signal/kind "
   "                                      :renewal.signal/value "
   "                                      :renewal.signal/count "
   "                                      :renewal.signal/severity]) ...] "
   "                           :where [?e :renewal.signal/id]] @room/*room*) "
   "      sales (first (filter #(= :sales (:renewal.signal/source %)) signals)) "
   "      support (first (filter #(= :support (:renewal.signal/source %)) signals)) "
   "      team (-> (agent/roster {:id :renewal-specialists}) "
   "               (agent/make-agent {:id :sales :program {:kind :scripted :reply sales}}) "
   "               (agent/make-agent {:id :support :program {:kind :scripted :reply support}})) "
   "      a (agent/hire! team :sales {:task :report-sales-evidence}) "
   "      b (agent/hire! team :support {:task :report-support-evidence}) "
   "      [ra rb] @(spin (await (comb/parallel (agent/result-spin a) "
   "                                           (agent/result-spin b))))] "
   "  {:sales (:run/value ra) :support (:run/value rb)})"))

(defn- tool-result [messages id]
  (some (fn [message]
          (when (and (= :tool-result (:message/role message))
                     (= id (:message/tool-use-id message)))
            (:message/content message)))
        messages))

(defn- read-tool-edn [content]
  (when (string? content)
    (edn/read-string (if (str/starts-with? content "=> ")
                       (subs content 3)
                       content))))

(deftest deterministic-model-certifies-a-consequential-recursive-business-run
  (with-production-room
    (fn [room]
      (let [previous-tool (tools/get-tool "renewal_plan")]
        (try
          (renewal/register-tool!)
          (renewal/provision-review-capacity! room 1)
          (let [environment (renewal/environment-def)
                team (-> (roster/make-roster {:id :renewal/candidates})
                         (roster/make-agent
                          {:id :simulated-model
                           :tools #{:clojure-eval :renewal-plan}
                           :model-policy {:provider :test :model "deterministic"}
                           :program {:kind :llm :max-model-steps 3
                                     :budget-dollars 2.0 :auto-compact? false}}))
                definition
                (experiment/make-experiment
                 {:id :business/renewal-intervention-v1
                  :dataset (experiment/make-dataset
                            {:id :business/renewal-intervention-v1
                             :environments [environment]})
                  :candidates [(roster/agent team :simulated-model)]})
                calls (atom 0)]
            (with-redefs
             [providers/ensure-initialized! (constantly nil)
              chat-agent/messages->api-format (fn [messages _ _] messages)
              model-chat/chat
              (fn [messages _]
                (case (swap! calls inc)
                  1 {:content ""
                     :tool-calls [{:id "collect-evidence"
                                   :name "clojure_eval"
                                   :input {:code recursive-evidence-program}}]
                     :usage {:input-tokens 10 :output-tokens 20}
                     :stop-reason :tool-use}
                  2 (let [{:keys [sales support]}
                          (read-tool-edn (tool-result messages "collect-evidence"))]
                      {:content ""
                       :tool-calls
                       [{:id "propose-plan"
                         :name "renewal_plan"
                         :input {:account "acme"
                                 :risk "high"
                                 :action "executive-escalation"
                                 :evidence [(str (:renewal.signal/id sales))
                                            (str (:renewal.signal/id support))]}}]
                       :usage {:input-tokens 30 :output-tokens 20}
                       :stop-reason :tool-use})
                  3 (let [plan (read-tool-edn
                                (tool-result messages "propose-plan"))]
                      {:content (pr-str {:plan/id (:plan/id plan)})
                       :tool-calls nil
                       :usage {:input-tokens 30 :output-tokens 10}
                       :stop-reason :end-turn})
                  (throw (ex-info "deterministic model called too often"
                                  {:calls @calls}))))]
              (let [{:keys [attempts scorecard]}
                    @(experiment/run
                      room team definition
                      {renewal/verifier-ref (renewal/evaluator)}
                      {:parallelism 1
                       :world-setups {renewal/setup-ref (renewal/world-setup)}})
                    attempt (first attempts)
                    checks (get-in attempt [:attempt/receipt :attempt/checks])
                    run-id (:attempt/run-id attempt)
                    durable-runs (run/runs room {:limit 20})
                    parent-conn (:conn (:store room))]
                (is (= 3 @calls))
                (is (= 1 (count attempts)))
                (is (every? true? (vals checks)) (pr-str checks))
                (is (= 1.0 (get-in attempt [:attempt/receipt :attempt/reward])))
                (is (= scorecard
                       (experiment/scorecard room (:scorecard/content-id scorecard))))
                (is (= :discarded
                       (:run/settlement-status
                        (some #(when (= run-id (:run/id %)) %) durable-runs))))
                (is (every? #(nil? (registry/lookup (:run/world %))) durable-runs))
                (is (nil? (dh/q '[:find ?id . :where [?e :arena/id ?id]]
                                @parent-conn)))
                (is (= {} (resource/balance room))
                    "the review unit is consumed even though speculative state is discarded"))))
          (finally
            (evaluation/await-cleanups! room 5000)
            (if previous-tool
              (tools/register! previous-tool)
              (swap! tools/registry dissoc "renewal_plan"))))))))
