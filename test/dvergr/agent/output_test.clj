(ns dvergr.agent.output-test
  (:require [clojure.test :refer [deftest is]]
            [dvergr.agent.environment :as environment]
            [dvergr.agent.evaluation :as evaluation]
            [dvergr.agent.output :as output]
            [dvergr.agent.roster :as roster]
            [dvergr.agent.run :as run]
            [dvergr.discourse :as d]
            [dvergr.room.store :as store]
            [dvergr.room.store.memory :as memory]
            [dvergr.sandbox :as sandbox]
            [dvergr.sandbox.ns.agent :as agent-ns]
            [org.replikativ.spindel.engine.core :as ec]))

(defn- with-certified-output [publish? value f]
  (let [room (d/make-room {:id (keyword (str "published-output-" (random-uuid)))
                           :store (memory/make)})
        parent (:run/id (run/start! room :parent (random-uuid) nil))
        sibling (:run/id (run/start! room :sibling (random-uuid) nil))
        team (roster/make-agent (roster/make-roster) {:id :worker :program {:kind :echo}})
        env (environment/make-environment
             {:id :test/output :task :answer :verifier {:id :test/output :version 1}
              :world {:isolation :ctx :settlement :discard}})
        evaluator (evaluation/make-evaluator
                   {:id :test/output :version 1
                    :observe (fn [{:keys [default]}]
                               (cond-> (assoc default :private-fixture "must not leak")
                                 publish? (assoc :agent/output value)))
                    :verify (fn [_ _] {:checks {:done true} :reward 1.0})})]
    (try
      (binding [ec/*execution-context* (:ctx room)]
        (let [result @(evaluation/evaluate room team :worker env evaluator {:parent-run parent})]
          (f room parent sibling (:run/id result))))
      (finally
        (run/finish! parent :completed)
        (run/finish! sibling :completed)
        (evaluation/await-cleanups! room 5000)
        (d/close-room! room)))))

(defn- error-type [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))

(deftest exact-ancestry-does-not-use-truncated-history-and-rejects-broken-chains
  (let [ids (vec (repeatedly 520 random-uuid))
        ancestry (into {} (map-indexed (fn [i id]
                                         [id {:run/id id :run/parent (when (pos? i) (nth ids (dec i)))}]) ids))]
    (with-redefs [run/run (fn [_ id] (get ancestry id))
                  run/runs (fn [& _] (throw (ex-info "Must not use bounded history" {})))]
      (is (= (first ids) (:run/id (#'output/scoped-run! nil (first ids) (last ids))))))
    (with-redefs [run/run (fn [_ id] {:run/id id :run/parent id})]
      (is (= :dvergr.agent.output/outside-scope
             (error-type #(#'output/scoped-run! nil (first ids) (last ids))))))))

(deftest output-bounds-stop-deep-and-runtime-values
  (doseq [value [(iterate inc 0) (atom :secret) (fn [] :secret)]]
    (is (= :dvergr.agent.output/unsupported
           (error-type #(#'output/bounded-data! value)))))
  (is (= :dvergr.agent.output/too-large
         (error-type #(#'output/bounded-data! (nth (iterate vector 0) 33)))))
  (let [value (with-meta {:answer [(with-meta 'answer {:private (atom :secret)})]}
                {:private (fn [] :secret)})
        public (#'output/bounded-data! value)]
    (is (= value public))
    (is (nil? (meta public)))
    (is (nil? (meta (first (:answer public)))))))

(deftest string-chunks-preserve-unicode-and-always-make-progress
  (let [value (str (apply str (repeat 16383 "x")) "🦋" "z")
        first-chunk (#'output/string-chunk value 0 16384)
        last-chunk (#'output/string-chunk value (:next-offset first-chunk) 16384)]
    (is (= 16383 (:next-offset first-chunk)))
    (is (= value (str (:output first-chunk) (:output last-chunk))))
    (is (= "🦋z" (:output last-chunk)))
    (is (not (contains? last-chunk :next-offset)))
    (is (= :dvergr.agent.output/invalid-options
           (error-type #(#'output/string-chunk value 16384 10))))
    (is (= :dvergr.agent.output/invalid-options
           (error-type #(#'output/string-chunk value 16383 1))))))

(deftest published-output-is-immutable-scoped-and-causal
  (with-certified-output
    true {:files {"/out/answer.edn" "{:answer 42}"} :answer 42}
    (fn [room parent sibling id]
      (let [certified (store/-load-attempt (:store room) (store/conversation-id room) id)
            before (:run/caused-by (run/run room parent))
            result (output/read-output room parent id {:path [:files "/out/answer.edn"]})]
        (is (= "{:answer 42}" (:output result)))
        (is (= (:attempt/content-id certified) (:attempt/content-id result)))
        (is (= #{:attempt/id :attempt/run-id :attempt/content-id :attempt/evidence-content-id
                 :path :output :encoding :offset :size}
               (set (keys result))))
        (is (not (contains? before id)))
        (is (= :dvergr.agent.output/not-certified
               (error-type #(output/read-output room parent parent))))
        (is (contains? (:run/caused-by (run/run room parent)) id))
        (is (= :discarded (:run/settlement-status (run/run room id))))
        (is (= :dvergr.agent.output/outside-scope
               (error-type #(output/read-output room sibling id))))
        (is (= :dvergr.agent.output/invalid-scope
               (error-type #(output/read-output room nil id))))
        (is (empty? (:run/caused-by (run/run room sibling))))
        (is (= certified (store/-load-attempt (:store room) (store/conversation-id room) id)))))))

(deftest publication-is-opt-in-not-the-entire-verifier-evidence
  (with-certified-output
    false nil
    (fn [room parent _ id]
      (is (= :dvergr.agent.output/not-published
             (error-type #(output/read-output room parent id))))
      (is (empty? (:run/caused-by (run/run room parent)))))))

(deftest published-values-are-bounded-and-strings-can-be-paged
  (with-certified-output
    true {:large (apply str (repeat 20000 "x")) :many (vec (range 3000)) :nil nil}
    (fn [room parent _ id]
      (is (= :dvergr.agent.output/too-large (error-type #(output/read-output room parent id))))
      (is (= :dvergr.agent.output/too-large
             (error-type #(output/read-output room parent id {:path [:many]}))))
      (is (empty? (:run/caused-by (run/run room parent))))
      (let [a (output/read-output room parent id {:path [:large] :length 12000})
            b (output/read-output room parent id {:path [:large] :offset (:next-offset a)})]
        (is (= 12000 (:next-offset a)))
        (is (= 20000 (+ (count (:output a)) (count (:output b)))))
        (is (nil? (:next-offset b))))
      (is (nil? (:output (output/read-output room parent id {:path [:nil]}))))
      (doseq [opts [{:path [:absent]} {:path [:many 99999]} {:path [:large] :offset 20001}
                    {:path [:nil] :offset 0} {:length 16385} {:room :another-room}]]
        (is (some? (error-type #(output/read-output room parent id opts))))))))

(deftest sci-can-read-a-certified-descendant-without-host-store-access
  (with-certified-output
    true {:answer 42}
    (fn [room parent _ id]
      (let [sci-ctx (sandbox/fork-for-session (:ctx room))]
        (agent-ns/add-programming-ns! sci-ctx (:id room) (:ctx room) {:parent-run parent})
        (let [result (sandbox/eval-code
                      sci-ctx (str "(require '[dvergr.agent :as agent])"
                                   "(agent/attempt-output #uuid \"" id "\" {:path [:answer]})"))]
          (is (:success result) (pr-str (:error result)))
          (is (= 42 (get-in result [:value :output]))))
        (is (contains? (:run/caused-by (run/run room parent)) id))))))
