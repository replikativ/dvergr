(ns dvergr.agent.environment-test
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.agent.environment :as environment]))

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
