(ns dvergr.agent.environment-test
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.agent.environment :as environment]
            [hasch.core :as hasch]))

(def base-spec
  {:id :programming/join
   :version 1
   :task "Build and execute a two-agent join."
   :verifier {:id :programming/join-checks :version 1
              :basis "git:abc123"}
   :limits {:timeout-ms 120000 :max-model-steps 8}
   :world {:isolation :ctx}})

(deftest definitions-have-stable-content-identity
  (let [a (environment/make-environment base-spec)
        b (environment/make-environment
           (into (array-map) (reverse (seq base-spec))))]
    (is (= a b))
    (is (= {:environment/id :programming/join
            :environment/version 1
            :environment/content-id (:environment/content-id a)}
           (environment/environment-ref a)))
    (doseq [changed [(assoc base-spec :task "Different task")
                     (assoc-in base-spec [:verifier :version] 2)
                     (assoc-in base-spec [:limits :max-model-steps] 9)]]
      (is (not= (:environment/content-id a)
                (:environment/content-id
                 (environment/make-environment changed)))))))

(deftest definitions-reject-live-or-ambiguous-state
  (testing "a trusted verifier is a versioned reference, never an embedded function"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"reference map"
                          (environment/make-environment
                           (assoc base-spec :verifier identity)))))
  (testing "policy fields remain portable forkable data"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"portable data"
                          (environment/make-environment
                           (assoc base-spec :metadata {:state (atom 0)})))))
  (testing "unknown keys fail rather than silently changing hash semantics"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"unknown keys"
                          (environment/make-environment
                           (assoc base-spec :verify identity))))))

(deftest references-reject-stale-or-fabricated-content-identities
  (let [definition (environment/make-environment base-spec)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"does not match"
                          (environment/environment-ref
                           (assoc definition :environment/task "mutated"))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"missing canonical"
                          (environment/environment-ref
                           (environment/environment-ref definition))))
    (is (= definition (environment/validate-environment definition)))))

(deftest trusted-attempt-receipts-bind-run-environment-evidence-and-reward
  (let [definition (environment/make-environment base-spec)
        run-id (random-uuid)
        opts {:run-id run-id
              :provider :codex-subscription
              :model "codex-subscription-sol"
              :status :failed
              :started-at 1000
              :elapsed-ms 42
              :metrics {:prompt-id (random-uuid)
                        :model-steps 8
                        :usage {:by-type {:input-tokens 100}}}
              :checks {:exact-result? false :quiescent? true}
              :reward 0.0
              :result nil
              :trace {:runs [{:run/id run-id :run/status :failed}]}}
        a (environment/make-attempt-receipt definition opts)
        b (environment/make-attempt-receipt definition
                                            (into (array-map) (reverse (seq opts))))
        rehash (fn [changes]
                 (let [changed (merge (dissoc a :attempt/content-id) changes)]
                   (assoc changed :attempt/content-id
                          (hasch/uuid [:dvergr/environment-attempt changed]))))]
    (is (= a b))
    (is (= run-id (:attempt/id a) (:attempt/run-id a)))
    (is (= (environment/environment-ref definition)
           (:attempt/environment a)))
    (is (= a (environment/validate-attempt-receipt a)))
    (is (uuid? (:attempt/content-id a)))
    (is (not= (:attempt/content-id a)
              (:attempt/content-id
               (environment/make-attempt-receipt
                definition (assoc opts :reward 1.0)))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"does not match"
                          (environment/validate-attempt-receipt
                           (assoc a :attempt/reward 1.0))))
    (doseq [[message changes]
            [[#"UUID" {:attempt/id "run" :attempt/run-id "run"}]
             [#"EnvironmentRef" {:attempt/environment :garbage}]
             [#"non-negative" {:attempt/elapsed-ms -1}]
             [#"keywords to booleans" {:attempt/checks {:truth :unknown}}]
             [#"finite" {:attempt/reward ##NaN}]]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo message
                            (environment/validate-attempt-receipt
                             (rehash changes)))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"keywords to booleans"
                          (environment/make-attempt-receipt
                           (environment/make-environment base-spec)
                           {:run-id (random-uuid) :provider :stub :model "stub"
                            :status :completed :started-at 0 :elapsed-ms 0
                            :checks {:truth :unknown} :reward 0.0}))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"finite"
                        (environment/make-attempt-receipt
                         (environment/make-environment base-spec)
                         {:run-id (random-uuid) :provider :stub :model "stub"
                          :status :completed :started-at 0 :elapsed-ms 0
                          :checks {} :reward ##Inf}))))
