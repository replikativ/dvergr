(ns dvergr.agent.cli-lifecycle-test
  "Offline integration through the real CLI transport, SCI loop and evaluator.
   The only executable is a generated shell fixture; no login/inference occurs."
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [jsonista.core :as json]
            [dvergr.agent.program-test :as fixtures]
            [dvergr.agent.environment :as environment]
            [dvergr.agent.evaluation :as evaluation]
            [dvergr.agent.roster :as roster]
            [dvergr.agent.run :as run]
            [dvergr.model.api.claude-code :as claude]
            [dvergr.model.cli-process :as processes]
            [dvergr.model.providers :as providers]
            [dvergr.resource :as resource]
            [org.replikativ.spindel.core :as sp]
            [org.replikativ.spindel.engine.core :as ec]))

(defn- event [text]
  (json/write-value-as-string {:type "result" :result text
                               :usage {:input_tokens 3 :output_tokens 2}}))

(defn- fixture-script [mode receipt]
  (str "#!/bin/sh\n"
       "if test \"$1\" = --version; then echo fixture-version; exit 0; fi\n"
       "echo $$ >> '" receipt "'\n"
       "request=$(cat)\n"
       "case \"$request\" in\n"
       "*tool_result*) "
       (case mode
         :reply (str "printf '%s\\n' '" (event "diagnostic ok") "'")
         :stall "sleep 30"
         :parent-exit "sleep 30 & exit 0")
       " ;;\n"
       "*) printf '%s\\n' '"
       (event (str "<tool_use>" (json/write-value-as-string
                                 {:name "clojure_eval" :input {:code "(+ 1 2)"}}) "</tool_use>"))
       "' ;;\nesac\n"))

(deftest cli-run-tool-loop-remains-live-and-cleans-up-on-every-outcome
  (doseq [mode [:reply :stall :parent-exit]]
    (let [[room conn] (#'fixtures/resource-test-room (keyword (str "cli-lifecycle-" (name mode))))
          script (doto (java.io.File/createTempFile "cli-lifecycle" ".sh") (.setExecutable true))
          receipt (java.io.File/createTempFile "cli-lifecycle-pids" ".txt")
          before (claude/settings-snapshot)]
      (try
        (spit script (fixture-script mode receipt))
        (claude/configure! {:cli (.getAbsolutePath script)
                            :stall-timeout-ms (if (= :parent-exit mode) 200 10000)
                            :env {"CLAUDE_CONFIG_DIR" "/tmp/dvergr-offline-cli-config"
                                  "CLAUDE_CODE_OAUTH_TOKEN" ""}})
        (resource/install-unit! room {:symbol resource/cli-run-admissions :name "CLI Run admissions" :precision 0})
        (resource/mint! room {:id (random-uuid) :resources {resource/cli-run-admissions 1M}})
        (let [team (roster/make-agent
                    (roster/make-roster)
                    {:id :candidate :tools #{:clojure_eval}
                     :model-policy {:provider :claude-code :model "claude-code-haiku"}
                     :program {:kind :llm :execution-mode :text :single-use? true
                               :provider-policy (claude/execution-policy)
                               :auto-compact? false :max-model-steps 3}})
              env (environment/make-environment
                   {:id :test/cli-lifecycle :task "Use clojure_eval to calculate (+ 1 2), then finish."
                    :verifier {:id :test/cli-lifecycle :version 1}
                    :limits {:timeout-ms (if (= :stall mode) 2000 5000) :cancel-timeout-ms 3000}
                    :world {:isolation :ctx :settlement :discard
                            :resources {resource/cli-run-admissions 1M}}})
              evaluator (evaluation/make-evaluator
                         {:id :test/cli-lifecycle
                          :observe (fn [{:keys [default]}] default)
                          :verify (fn [_ evidence]
                                    (let [ok (= "diagnostic ok" (:result evidence))]
                                      {:checks {:output? ok} :reward (if ok 1.0 0.0)}))})]
          (with-redefs [providers/providers (atom {:claude-code (claude/create {})})
                        providers/ensure-initialized! (constantly nil)]
            (binding [ec/*execution-context* (:ctx room)]
              (let [attempt (evaluation/evaluate room team :candidate env evaluator)
                    waiting (future @attempt)]
                (is (#'fixtures/wait-until #(not (str/blank? (slurp receipt))) 3000))
                (is (= :heartbeat (deref (sp/spin :heartbeat) 1000 ::timeout))
                    "blocking subprocess I/O must not hold the Room's drain")
                (let [result (deref waiting 10000 ::timeout)
                      pids (mapv parse-long (remove str/blank? (str/split-lines (slurp receipt))))
                      status (get-in result [:run/result :run/status])
                      spend (get-in result [:attempt-receipt :attempt/metrics :spend])]
                  (is (not= ::timeout result))
                  (is (= 2 (count pids)) "the real text parser and SCI tool result caused a second CLI exchange")
                  (is (= (case mode :reply :completed :stall :cancelled :parent-exit :failed) status))
                  (if (= :reply mode)
                    (do (is (= 1.0 (get-in result [:attempt-receipt :attempt/reward])))
                        (is (true? (:priced? spend))))
                    (do (is (false? (:priced? spend)))
                        (is (nil? (:notional-microdollars spend)))))
                  (is (every? #(empty? (processes/live-members %)) pids))
                  (is (empty? (resource/balance room)))
                  (is (empty? (run/active-runs (:id room)))))))))
        (finally
          (claude/restore-settings! before)
          (#'fixtures/close-resource-test-room! room conn)
          (.delete script)
          (.delete receipt))))))
