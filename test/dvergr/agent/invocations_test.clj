(ns dvergr.agent.invocations-test
  (:require [clojure.test :refer [deftest is]]
            [dvergr.agent.invocations :as invocations]
            [dvergr.room.registry :as registry]
            [dvergr.agent.evaluators :as evaluators]
            [dvergr.agent.program :as program]
            [dvergr.agent.roster :as roster]
            [dvergr.sandbox.ns.agent :as agent-ns]
            [org.replikativ.spindel.engine.context :as context]
            [org.replikativ.spindel.engine.core :as ec]
            [sci.core :as sci]))

(def ref {:invocation/id :test/fixed-experiment :invocation/version 1
          :invocation/basis #uuid "95f78d5f-7241-44ab-9115-ea4117a3c989"})

(deftest offers-are-closed-and-portable
  (let [scope {:evaluators {} :world-setups {} :invocations {ref (fn [& _] :never-published)}}]
    (is (= #{ref} (invocations/refs scope)))
    (is (invocations/valid-ref? (assoc ref :invocation/basis {:admission-id (random-uuid) :output-verifier {:verifier/id :test/capture}})))
    (is (= {:evaluators [] :world-setups [] :invocations [ref]} (evaluators/offered scope)))
    (is (= {:evaluators [] :world-setups []} (evaluators/offered {})))
    (doseq [bad [{(assoc ref :extra true) (fn [& _])}
                 {(assoc ref :invocation/basis (fn [])) (fn [& _])}
                 {ref :not-a-handler} nil]]
      (is (thrown? Exception (invocations/refs {:invocations bad}))))
    (is (thrown? Exception
                 (#'program/with-experiment-inputs {:inputs/kind :scripted}
                                                   {:experiment-capabilities {:invocations {(assoc ref :invocation/basis (fn [])) identity}}})))
    (let [base {:inputs/kind :scripted}
          inputs (#'program/with-experiment-inputs base {:experiment-capabilities scope})]
      (is (= #{ref} (get-in inputs [:experiment-capabilities :invocations])))
      (is (roster/data-value? inputs))
      (is (not (contains? (:experiment-capabilities
                           (#'program/with-experiment-inputs base {:experiment-capabilities {}})) :invocations))))))

(deftest invocation-receives-only-ambient-authority-and-never-falls-back
  (let [called (atom []) parent (random-uuid)
        scope {:invocations {ref (fn [host team opts] (swap! called conj [host team opts]) :result)}}
        authority {:parent-run parent :experiment-capabilities scope :own-child! identity}]
    (is (= :result (invocations/invoke! :control :caller authority ref :team {:resources {}})))
    (is (= [[{:control-room :control :work-room :caller :authority authority} :team {:resources {}}]] @called))
    (doseq [[a r opts] [[{} ref {}] [(assoc authority :experiment-capabilities {}) ref {}]
                        [authority (assoc ref :invocation/version 2) {}] [authority ref nil]]]
      (is (thrown? Exception (invocations/invoke! :control :caller a r :team opts))))
    (is (= 1 (count @called)))))

(deftest sci-invocation-uses-the-ambient-host-offer
  (let [ctx (context/create-execution-context)
        room {:id :test/control :ctx ctx}
        interpreter (sci/init {}) parent (random-uuid) calls (atom [])
        scope {:invocations {ref (fn [host team opts]
                                   (is (= parent (get-in host [:authority :parent-run])))
                                   (swap! calls conj [team opts]) :observed)}}]
    (try
      (binding [ec/*execution-context* ctx]
        (with-redefs [registry/lookup (constantly room)]
          (agent-ns/add-programming-ns! interpreter (:id room) ctx
                                        {:parent-run parent :experiment-capabilities scope}
                                        (constantly room))
          (is (= :observed
                 (sci/eval-string* interpreter (str "(dvergr.agent/invoke-experiment! " (pr-str ref) " {} {})"))))
          (is (= 1 (count @calls)))
          (is (thrown? Exception
                       (sci/eval-string* interpreter
                                         (str "(dvergr.agent/invoke-experiment! " (pr-str (assoc ref :invocation/version 2)) " {} {})"))))
          (is (= 1 (count @calls)))))
      (finally (context/close-context! ctx)))))

(deftest native-child-authority-retains-exact-invocation-scope
  (let [scope {:evaluators {} :world-setups {} :invocations {ref (fn [& _] :fixed)}}
        supervisor {:experiment-capabilities scope}
        child (#'program/child-program-authority (random-uuid) supervisor)]
    (is (identical? scope (:experiment-capabilities child)))
    (is (= #{ref} (invocations/refs (:experiment-capabilities child))))
    (is (not (contains? (#'program/child-program-authority (random-uuid) {}) :experiment-capabilities)))))
