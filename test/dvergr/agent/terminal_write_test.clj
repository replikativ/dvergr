(ns dvergr.agent.terminal-write-test
  "A finished Run is published only once its terminal state is durable. When
   the store refuses that write the settlement watcher retries, and says so:
   silently it looked exactly like a hang."
  (:require [clojure.test :refer [deftest is]]
            [dvergr.agent.program :as program]
            [dvergr.agent.run :as run]
            [taoensso.telemere :as tel]))

(deftest a-refused-terminal-write-is-retried-loudly-then-published
  (let [attempts (atom 0)
        published (atom [])
        id (random-uuid)
        result {:run/id id :run/status :completed}
        {signals :signals value :value}
        (with-redefs [run/retain-finished! (fn [_ _ _]
                                             (if (< (swap! attempts inc) 4)
                                               (throw (ex-info "No space left on device" {}))
                                               {:run/id id}))
                      run/publish-finished! (fn [run-id _ r] (swap! published conj [run-id r]))]
          (tel/with-signals (#'program/publish-result-and-release! id nil result {})))
        ids (map :id signals)]
    (is (= result value))
    (is (= 4 @attempts))
    (is (= [[id result]] @published) "published once, after the write succeeded")
    (is (= [::program/terminal-write-failing ::program/terminal-write-recovered] (vec ids))
        "warned when it started failing (once within the minute) and when it recovered")
    (is (= :warn (:level (first signals))))
    (is (re-find #"No space" (str (get-in (first signals) [:data :error]))))))
