(ns dvergr.agent.checkpoint-admission-test
  (:require [clojure.test :refer [deftest is]]
            [datahike.api :as db]
            [dvergr.agent.authority :as authority]
            [dvergr.agent.environment :as environment]
            [dvergr.agent.evaluation :as evaluation]
            [dvergr.agent.program :as program]
            [dvergr.agent.roster :as roster]
            [dvergr.agent.run :as run]
            [dvergr.agent.world :as world]
            [dvergr.discourse :as d]
            [dvergr.room.store.memory :as memory]
            [dvergr.room.registry :as registry]
            [dvergr.substrate.geschichte :as gs]
            [geschichte.bytes :as bytes]
            [geschichte.query :as query]
            [geschichte.repo :as repo]
            [muschel.fs :as fs]
            [malli.core :as m]
            [dvergr.sandbox.ns.agent :as sci-agent]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.yggdrasil :as ygg]
            [yggdrasil.adapters.geschichte :as gy]))

(defn- origin []
  {:version 1 :kind :datahike :commit (random-uuid)
   :source {:store (random-uuid) :repository (random-uuid)}})
(defn- setup [basis checkpoints]
  (evaluation/make-world-setup {:id :checkpoint/fixture :basis basis :checkpoints checkpoints
                                :prepare (constantly nil)}))
(defn- definition [setup-ref]
  (environment/make-environment
   {:id :checkpoint/environment :task :read-workspace
    :verifier {:id :checkpoint/check :version 1}
    :limits {:timeout-ms 15000 :cancel-timeout-ms 3000}
    :world {:isolation :ctx :settlement :discard :setup setup-ref}}))
(def evaluator
  (evaluation/make-evaluator
   {:id :checkpoint/check :observe (fn [{:keys [default]}] default)
    :verify (fn [_ evidence]
              (let [ok? (= {:state "C1" :prepared "Ready at C1"} (:result evidence))]
                {:checks {:exact? ok?} :reward (if ok? 1.0 0.0)}))}))

(deftest checkpoint-definition-shape-is-portable-without-assuming-a-storage-backend
  (let [origins {"custom" {:kind :custom :revision "A"}}
        ref (evaluation/world-setup-ref (setup "v1" origins))]
    (is (= origins (:setup/checkpoints ref)))
    (is (m/validate @#'sci-agent/SetupRef ref))
    (doseq [native [(atom :native) (fn [] :native)]]
      (is (thrown? clojure.lang.ExceptionInfo (setup "v1" {"custom" native}))))))

(deftest setup-checkpoint-identity-is-distinct-from-arbitrary-caller-basis
  (let [selected {"workspace" (origin)} base {:setup/id :checkpoint/fixture :setup/version 1 :setup/basis "v1"}
        selected-setup (setup "v1" selected)
        changed (setup "v1" (assoc-in selected ["workspace" :commit] (random-uuid)))
        arbitrary (setup {:world/basis "v1" :world/checkpoints selected} nil)]
    (doseq [empty-policy [nil {}]]
      (is (= base (evaluation/world-setup-ref (setup "v1" empty-policy)))))
    (is (= selected (:setup/checkpoints (evaluation/world-setup-ref selected-setup))))
    (is (not= (evaluation/world-setup-ref arbitrary) (evaluation/world-setup-ref selected-setup)))
    (is (not= (:environment/content-id (definition (evaluation/world-setup-ref selected-setup)))
              (:environment/content-id (definition (evaluation/world-setup-ref changed)))))
    (is (not (contains? (set authority/host-option-keys) :checkpoints)))
    (doseq [bad [false [] {"workspace" nil} {nil (origin)}]]
      (is (thrown? clojure.lang.ExceptionInfo (setup "v1" bad))))))

(deftest tainted-setup-capability-refuses-before-native-world-or-run-admission
  (let [selected {"workspace" (origin)} capability (setup "v1" selected)
        env (definition (evaluation/world-setup-ref capability))
        altered (assoc capability :checkpoints (assoc-in selected ["workspace" :commit] (random-uuid)))
        allocations (atom 0)]
    (with-redefs [world/open! (fn [& _] (swap! allocations inc) (throw (ex-info "Must not allocate" {})))]
      (let [error (try (evaluation/evaluate nil nil :candidate env evaluator {:world-setup altered})
                       nil (catch Exception error error))]
        (is (= ::evaluation/setup-checkpoint-mismatch (:type (ex-data error))))
        (is (zero? @allocations))))))

(defn- with-room-workspace [f]
  (let [config {:store {:backend :memory :id (random-uuid)} :schema-flexibility :write
                :keep-history? true :commit-graph? true}
        room (d/make-room {:id (keyword (str "checkpoint-" (random-uuid))) :store (memory/make)})]
    (db/create-database config)
    (let [conn (db/connect config)]
      (try
        (repo/init! conn)
        (binding [ec/*execution-context* (:ctx room)]
          (registry/register! room)
          (ygg/register! (gy/create conn {:system-name "workspace"}))
          (gs/select-workspace! "workspace")
          (f room conn))
        (finally (d/close-room! room) (db/release conn) (db/delete-database config))))))

(defn- commit! [conn text]
  (repo/write! conn "state.md" (bytes/utf8 text))
  (repo/stage-all! conn)
  (repo/commit! conn {:message text :author "checkpoint-test"}))

(deftest evaluation-selects-checkpoint-before-setup-and-ordinary-agent-file-access
  (with-room-workspace
    (fn [room conn]
      (commit! conn "C1")
      (let [selected {"workspace" {:version 1 :kind :datahike :commit (db/commit-id @conn)
                                   :source {:store (get-in @conn [:config :store :id])
                                            :repository (:geschichte.repo/id (query/repository @conn))}}}
            capability
            (evaluation/make-world-setup
             {:id :checkpoint/fixture :basis "v1" :checkpoints selected
              :prepare (fn [_]
                         (let [filesystem (gs/filesystem)]
                           (is (= "C1" (fs/read-file filesystem "/state.md")))
                           (fs/write-string! filesystem "/prepared.md" "Ready at C1" false)
                           {:prepared-at "C1"}))})
            env (definition (evaluation/world-setup-ref capability))
            team (roster/make-agent
                  (roster/make-roster)
                  {:id :candidate :tools #{} :sandbox #{}
                   :program {:kind :sci
                             :source "(ns checkpoint.candidate) (defn main [_] {:state (slurp \"/state.md\") :prepared (slurp \"/prepared.md\")})"
                             :entrypoint 'checkpoint.candidate/main :args [] :timeout-ms 10000}})]
        (commit! conn "C2")
        (let [branches (db/branches conn)
              result @(evaluation/evaluate room team :candidate env evaluator {:world-setup capability})]
          (is (= {:exact? true} (get-in result [:attempt-receipt :attempt/checks])))
          (is (= :discarded (get-in result [:run/result :run/settlement-status])))
          (is (= "C2" (bytes/decode-utf8 (repo/read conn "state.md"))))
          (is (nil? (repo/read conn "prepared.md")))
          (is (= branches (db/branches conn)))
          (is (empty? (run/active-runs (:id room)))))))))

(deftest recursive-sci-hiring-inherits-current-edits-without-replaying-parent-checkpoint
  (with-room-workspace
    (fn [room conn]
      (commit! conn "C1")
      (let [selected {"workspace" {:version 1 :kind :datahike :commit (db/commit-id @conn)
                                   :source {:store (get-in @conn [:config :store :id])
                                            :repository (:geschichte.repo/id (query/repository @conn))}}}
            child {:kind :sci :source "(ns checkpoint.child) (defn main [_] (slurp \"/state.md\"))"
                   :entrypoint 'checkpoint.child/main :args [] :timeout-ms 10000}
            parent-source
            (str "(ns checkpoint.parent) (require '[dvergr.agent :as agent]) "
                 "(defn main [_] (let [initial (slurp \"/state.md\") "
                 "_ (spit \"/state.md\" \"Edited after admission\") "
                 "team (agent/make-agent (agent/roster) '"
                 (pr-str {:id :child :tools #{} :sandbox #{} :program child})
                 ") handle (agent/hire! team :child {:task nil :settlement :discard}) "
                 "result @(agent/result-spin handle)] {:initial initial :child (:run/value result)}))")
            team (roster/make-agent
                  (roster/make-roster)
                  {:id :parent :tools #{} :sandbox #{}
                   :program {:kind :sci :source parent-source :entrypoint 'checkpoint.parent/main
                             :args [] :timeout-ms 15000}})
            policy {:tools #{} :sandboxes #{#{}} :providers #{} :program-kinds #{:sci}
                    :max-attempts 2 :max-parallelism 1}]
        (commit! conn "C2")
        (let [branches (db/branches conn)
              result @(program/hire-in! room room team :parent {:task nil :settlement :discard}
                                        {:checkpoints selected :delegation-policy policy})]
          (is (= :completed (:run/status result)) (pr-str (:run/error result)))
          (is (= {:initial "C1" :child "Edited after admission"} (:run/value result)))
          (is (= 1 (count (filter :run/parent (run/runs room)))))
          (is (= "C2" (bytes/decode-utf8 (repo/read conn "state.md"))))
          (is (= branches (db/branches conn)))
          (is (empty? (run/active-runs (:id room)))))))))

(deftest sci-can-use-exact-host-offered-checkpoint-setup-references
  (with-room-workspace
    (fn [room conn]
      (commit! conn "C1")
      (let [selected {"workspace" {:version 1 :kind :datahike :commit (db/commit-id @conn)
                                   :source {:store (get-in @conn [:config :store :id])
                                            :repository (:geschichte.repo/id (query/repository @conn))}}}
            capability (setup "v1" selected) ref (evaluation/world-setup-ref capability)
            team (roster/make-agent
                  (roster/make-roster)
                  {:id :reader :tools #{} :sandbox #{}
                   :program {:kind :sci :entrypoint 'checkpoint.offered/main :args [] :timeout-ms 10000
                             :source (str "(ns checkpoint.offered) (require '[dvergr.agent :as agent]) "
                                          "(defn main [_] (let [ref (first (:world-setups (agent/offered)))] "
                                          "(agent/environment {:id :offered/checkpoint :task :read "
                                          ":verifier {:id :checkpoint/check :version 1} "
                                          ":world {:isolation :ctx :settlement :discard :setup ref}})))")}})]
        (is (m/validate @#'sci-agent/SetupRef ref))
        (is (not (m/validate @#'sci-agent/SetupRef (assoc-in ref [:setup/checkpoints "workspace"] nil))))
        (let [result @(program/hire-in!
                       room room team :reader {:task nil :settlement :discard}
                       {:experiment-capabilities {:evaluators {(evaluation/evaluator-ref evaluator) evaluator}
                                                  :world-setups {ref capability}}})]
          (is (= :completed (:run/status result)) (pr-str (:run/error result)))
          (is (= ref (get-in result [:run/value :environment/world :setup])))
          (is (uuid? (get-in result [:run/value :environment/content-id]))))))))
