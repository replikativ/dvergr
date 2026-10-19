(ns dvergr.agent.authority-test
  (:require [clojure.test :refer [deftest is]]
            [dvergr.agent.authority :as authority]
            [dvergr.agent.experiment-admission :as admission]
            [dvergr.agent.experiment-admission-test :as admission-fixture]
            [dvergr.agent.program :as program]
            [dvergr.agent.roster :as roster]
            [dvergr.agent.run :as run]
            [dvergr.agent.world :as world]
            [dvergr.discourse :as discourse]
            [dvergr.room.store.memory :as memory]
            [dvergr.sandbox :as sandbox]
            [dvergr.sandbox.ns.agent :as agent-ns]
            [dvergr.sandbox.extensions :as extensions]
            [dvergr.tools :as tools]
            [org.replikativ.spindel.engine.core :as ec]))

(def policy {:tools #{:clojure_eval :read_file}
             :sandboxes #{#{:test/worker} #{:test/coordinator}}
             :providers #{:test} :max-attempts 2 :max-parallelism 1})

(def worker {:agent/id :worker :agent/tools #{:read-file}
             :agent/sandbox #{:test/worker}
             :agent/program {:kind :llm} :agent/model-policy {:provider :test}})

(deftest policy-checks-exact-sandbox-combinations-tools-and-providers
  (is (= worker (authority/check-agent! worker {:delegation-policy policy})))
  (is (= worker (authority/check-agent! worker {})))
  (doseq [agent [(assoc worker :agent/tools #{:mail_send})
                 (assoc worker :agent/sandbox #{})
                 (assoc worker :agent/sandbox #{:test/worker :test/coordinator})
                 (assoc-in worker [:agent/model-policy :provider] :claude-code)]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"exceeds"
                          (authority/check-agent! agent {:delegation-policy policy}))))
  (doseq [p [nil {} (assoc policy :tools :all) (assoc policy :max-attempts 0)
             (assoc policy :max-parallelism 17) (assoc policy :unknown true)]]
    (is (thrown? clojure.lang.ExceptionInfo (authority/validate-policy! p)))))

(deftest changing-an-offered-extension-changes-delegation-input-identity
  (with-redefs [extensions/refs (fn [ids] (mapv #(hash-map :extension/id % :extension/version 1) (sort ids)))]
    (let [inputs (authority/inputs policy)]
      (is (= #{:test/worker :test/coordinator} (set (map :extension/id (:sandbox-refs inputs)))))
      (is (not= inputs (authority/inputs (assoc policy :providers #{})))))
    (let [before (authority/inputs policy)]
      (with-redefs [extensions/refs (fn [ids] (mapv #(hash-map :extension/id % :extension/version 2) (sort ids)))]
        (is (not= before (authority/inputs policy)))))))

(deftest native-delegation-forwards-the-same-host-policy
  (let [room (discourse/make-room {:id :native-delegation-policy :store (memory/make)})
        captured (atom nil)]
    (try
      (with-redefs-fn {#'tools/current-daemon (constantly nil)
                       #'tools/load-agent-profile (constantly nil)
                       #'tools/delegation-tools (constantly #{})
                       #'program/hire-in! (fn [& args]
                                            (reset! captured (last args))
                                            (throw (ex-info "admission intercepted" {})))}
        (fn []
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo #"admission intercepted"
               (#'tools/delegate-agent!
                {:task :work} {:room room :execution-ctx (:ctx room)
                               :model-policy {:provider :test :model "fixture"}
                               :agent-program-ceiling {:program-kinds #{:echo :scripted}
                                                       :funded-program-kinds #{:llm}
                                                       :delegation-policy policy}} :discard)))
          (is (= {:delegation-policy policy} @captured))))
      (finally (discourse/close-room! room)))))

(deftest policy-is-retained-in-the-child-authority-and-run-input-projection
  (let [ceiling (#'program/child-program-authority (random-uuid) {:delegation-policy policy})]
    (is (= policy (:delegation-policy ceiling)))
    (is (= 1 (:max-parallelism ceiling)))
    (is (= 2 (:max-attempts ceiling))))
  (with-redefs [extensions/refs (constantly [])]
    (is (= (authority/inputs policy)
           (:delegation-policy (#'program/with-experiment-inputs {:inputs/kind :llm}
                                                                 {:delegation-policy policy}))))))

(deftest central-hire-refuses-before-world-or-run-admission
  (let [team (roster/make-agent (roster/make-roster) {:id :escape :tools #{:clojure_eval} :program {:kind :echo}})]
    (with-redefs [world/open! (fn [& _] (throw (ex-info "world must not open" {})))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"exceeds"
                            (program/hire-in! nil nil team :escape {:task :work}
                                              {:delegation-policy policy}))))))

(deftest experiment-preflight-rejects-policy-escalation-before-starting-a-job
  (let [{:keys [team experiment]} (admission-fixture/definition :echo {})
        bounded (assoc admission-fixture/ceiling :delegation-policy
                       (assoc policy :sandboxes #{#{}}))]
    (is (= 2 (:cells (admission/plan team experiment {} bounded))))
    (is (thrown? clojure.lang.ExceptionInfo (admission/plan team experiment {:parallelism 2} bounded)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (admission/plan team experiment {} (assoc-in bounded [:delegation-policy :max-attempts] 1))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (admission/plan team experiment {} (assoc-in bounded [:delegation-policy :sandboxes] #{}))))))

(deftest sci-observation-and-cancellation-cannot-reach-parent-or-sibling
  (let [room (discourse/make-room {:id :scoped-run-controls :store (memory/make)})
        parent (:run/id (run/start! room :parent (random-uuid) nil))
        current (:run/id (run/start! room :current (random-uuid) nil {:parent parent}))
        sibling (:run/id (run/start! room :sibling (random-uuid) nil {:parent parent}))
        child (:run/id (run/start! room :child (random-uuid) nil {:parent current}))
        ctx (sandbox/fork-for-session (:ctx room))]
    (try
      (agent-ns/add-programming-ns! ctx (:id room) (:ctx room) {:parent-run current :delegation-policy policy})
      (binding [ec/*execution-context* (:ctx room)]
        (doseq [op ["observe" "cancel!"] target [parent sibling]]
          (let [result (sandbox/eval-code ctx (str "(dvergr.agent/" op " #uuid \"" target "\")"))]
            (is (false? (:success result)))))
        (is (not (run/cancel-requested? parent)))
        (is (not (run/cancel-requested? sibling)))
        (is (= child (get-in (sandbox/eval-code ctx (str "(dvergr.agent/observe #uuid \"" child "\")"))
                             [:value :run/id])))
        (is (= policy (:value (sandbox/eval-code ctx "(dvergr.agent/delegation-policy)")))))
      (finally
        (doseq [id [child sibling current parent]] (run/finish! id :completed))
        (discourse/close-room! room)))))
