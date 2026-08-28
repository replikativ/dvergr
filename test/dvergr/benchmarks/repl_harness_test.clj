(ns dvergr.benchmarks.repl-harness-test
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.benchmarks.repl-harness :as benchmark]
            [dvergr.discourse.definitions :as definitions]
            [dvergr.orchestration.daemon :as daemon]
            [dvergr.substrate.config :as config]
            [dvergr.substrate.paths :as paths]
            [dvergr.system.db :as system-db]))

(defn run-benchmark!
  "Run the nested-room benchmark in an isolated, provider-free daemon.

   Public so it can be invoked directly at a development REPL. The temporary
   state root is returned in the result for post-run diagnostics; the daemon is
   always stopped and dvergr's original state root is restored."
  []
  (when @daemon/current-daemon
    (throw (ex-info "Stop the current daemon before running the isolated benchmark"
                    {})))
  (let [original-home (paths/home)
        benchmark-home (str (System/getProperty "java.io.tmpdir")
                            "/dvergr-repl-harness-" (random-uuid))
        instance (atom nil)]
    (try
      (paths/set-home! benchmark-home)
      (system-db/reset-conn!)
      ;; No file-autostarted agent means no provider discovery. Empty config also
      ;; keeps local secrets and user configuration out of this deterministic run.
      (with-redefs [definitions/autostart-agents (constantly {})
                    config/config (constantly {})
                    config/secret-specs (constantly [])
                    config/sandbox-env (constantly {})]
        (let [d (daemon/start! {:agents {}
                                :db-path (str benchmark-home "/daemon-db")
                                :gc {:interval-ms 3600000}})]
          (reset! instance d)
          (assoc (benchmark/run! d {:child-slug "nested-room-benchmark"
                                    :title "Nested room benchmark"})
                 :state-root benchmark-home)))
      (finally
        (when-let [d @instance]
          (try (daemon/stop! d) (catch Throwable _)))
        (system-db/reset-conn!)
        (paths/set-home! original-home)))))

(deftest agent-facing-repl-constructs-a-durable-nested-room
  (let [result (run-benchmark!)]
    (testing "every layer observes the room created from clojure_eval"
      (is (:passed? result) (pr-str (:checks result)))
      (is (every? true? (vals (:checks result)))))
    (testing "the benchmark reports independently useful phase timings"
      (is (every? #(and (number? %) (not (neg? %)))
                  (vals (:timings-ms result)))))))
