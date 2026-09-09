(ns dvergr.benchmarks.permutation-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [dvergr.agent.evaluation :as evaluation]
            [dvergr.agent.roster :as roster]
            [dvergr.benchmarks.coding-workspace :as workspace]
            [dvergr.benchmarks.permutation :as p]
            [dvergr.chat.agent :as chat-agent]
            [dvergr.discourse :as d]
            [dvergr.model.chat :as model-chat]
            [dvergr.model.providers :as providers]
            [dvergr.room.registry :as registry]
            [dvergr.room.store :as store]
            [dvergr.room.store.memory :as memory]
            [dvergr.substrate.geschichte :as g]
            [muschel.fs :as fs]
            [muschel.fs.geschichte :as gfs]
            [org.replikativ.spindel.engine.context :as ctx]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.yggdrasil :as ygg]
            [yggdrasil.adapters.geschichte :as gy]))

(deftest reference-checks-distinguish-the-seeded-regression
  (is (= 24 (count p/inputs)))
  (is (= 576 (count (:binary p/expected))))
  (is (str/includes? p/seeded-source "qi  (apply-perm p i)"))
  (is (every? true? (vals (p/check-source p/original-source))))
  (let [checks (p/check-source p/seeded-source)]
    (is (:evaluated? checks))
    (is (false? (:binary checks)))
    (is (false? (:ternary checks)))
    (is (:nullary checks)))
  (is (false? (:evaluated? (p/check-source "("))))
  (is (false? (:evaluated? (p/check-source "nil"))))
  (is (= {:source? false} (p/check-source nil)))
  (is (= {:source? false} (p/check-source (apply str (repeat 32769 "a"))))))

(deftest setup-and-capture-use-explicit-virtual-files
  (let [{:keys [close!] :as repository} (gfs/memory-repository! {:name "coding-fixture"})
        filesystem (gfs/make-root repository)
        runtime (ctx/create-execution-context)
        room {:ctx runtime}]
    (try
      (with-redefs [g/filesystem (constantly filesystem)]
        (binding [ec/*execution-context* runtime]
          ((:prepare (p/world-setup)) {:room room})
          (is (= p/seeded-source (fs/read-file filesystem p/source-path)))
          (is (= :file (:type (fs/stat filesystem p/test-path))))
          ;; Reset inherited tests every time, not just the source.
          (fs/write-string! filesystem p/test-path "old answer" false)
          ((:prepare (p/world-setup)) {:room room})
          (is (not= "old answer" (fs/read-file filesystem p/test-path))))
        (let [capture ((:capture (p/evaluator)) {:world/room room})]
          (is (= p/seeded-source (get-in capture [:files p/source-path :source])))
          (is (= :ok (get-in capture [:files p/test-path :status])))))
      (finally
        (ctx/close-context! runtime)
        (close!)))))

(deftest capture-rejects-files-before-reading
  (let [runtime (ctx/create-execution-context)]
    (try
      (is (= {:status :world-unavailable} (workspace/capture nil ["/file"] 4)))
      (doseq [[stat status] [[nil :missing]
                             [{:type :dir :size 0} :not-file]
                             [{:type :file :size 5} :size-rejected]]]
        (with-redefs [g/filesystem (constantly :virtual)
                      fs/stat (fn [& _] stat)
                      fs/read-file (fn [& _] (throw (ex-info "Must not read" {})))]
          (is (= status (get-in (workspace/capture {:ctx runtime} ["/file"] 4)
                                [:files "/file" :status])))))
      (with-redefs [g/filesystem (constantly :virtual)
                    fs/stat (fn [& _] {:type :file :size 2})
                    fs/read-file (fn [& _] "ééé")]
        (is (= :size-rejected (get-in (workspace/capture {:ctx runtime} ["/file"] 4)
                                      [:files "/file" :status]))))
      (finally (ctx/close-context! runtime)))))

(deftest execution-status-is-not-inferred-from-correct-source
  (let [check (p/evaluator)
        artifacts {:status :captured
                   :files {p/source-path {:status :ok :source p/original-source}}}]
    (is (= p/verifier-ref (evaluation/evaluator-ref check)))
    (is (= p/setup-ref (evaluation/world-setup-ref (p/world-setup))))
    (doseq [status [:failed :cancelled :waiting]]
      (let [evidence ((:observe check) {:default {} :execution/evidence artifacts
                                        :result {:run/status status}})
            score ((:verify check) (p/definition) evidence)]
        (is (zero? (:reward score)))
        (is (false? (get-in score [:checks :completed?])))))))

(deftest coding-fixture-through-real-tool-and-world-lifecycle
  (doseq [fail? [false true]]
    (let [{:keys [conn close!]} (gfs/memory-repository! {:name "coding-run"})
          room (d/make-room {:id :coding-fixture-run :store (memory/make)})
          team (roster/make-agent (roster/make-roster)
                                  {:id :coder :tools #{:clojure_eval}
                                   :model-policy {:provider :test :model "stub"}
                                   :program {:kind :llm :max-model-steps 3 :auto-compact? false}})
          calls (atom 0)
          tool-result (atom nil)
          test-source "(ns permutation-repair-test (:require [clojure.test :refer [deftest is]]))\n(deftest identity-check (is (= {} (org.replikativ.spindel.incremental.permutation/compose))))\n"
          code (str "(spit " (pr-str p/source-path) " " (pr-str p/original-source) ") "
                    "(spit " (pr-str p/test-path) " " (pr-str test-source) ") "
                    "(require 'org.replikativ.spindel.incremental.permutation :reload) "
                    "(load-string (slurp " (pr-str p/test-path) ")) "
                    "(clojure.test/run-tests 'permutation-repair-test)")]
      (try
        (binding [ec/*execution-context* (:ctx room)]
          (ygg/register! (gy/create conn {:system-name "room-repo-coding-test"}))
          (with-redefs [providers/ensure-initialized! (constantly nil)
                        chat-agent/messages->api-format (fn [messages _ _] messages)
                        model-chat/chat
                        (fn [messages _]
                          (if (= 1 (swap! calls inc))
                            {:content "" :tool-calls [{:id "repair" :name "clojure_eval" :input {:code code}}]
                             :usage {:input-tokens 0 :output-tokens 0} :stop-reason :tool-use}
                            (do
                              (reset! tool-result
                                      (:message/content
                                       (last (filter #(= :tool-result (:message/role %)) messages))))
                              (if fail?
                                (throw (ex-info "Provider failed after repair" {}))
                                {:content "Saved repair and tests." :tool-calls nil
                                 :usage {:input-tokens 0 :output-tokens 0} :stop-reason :end-turn}))))]
            (let [done (promise)
                  computation (evaluation/evaluate room team :coder (p/definition) (p/evaluator)
                                                   {:world-setup (p/world-setup)})
                  _ (computation #(deliver done {:result %}) #(deliver done {:error %}))
                  outcome (deref done 60000 ::timeout)]
              (is (not= ::timeout outcome))
              (when-let [error (:error outcome)] (throw error))
              (let [result (:result outcome)
                    receipt (:attempt-receipt result)
                    persisted (first (store/-list-attempts (:store room) (:id room) {}))]
                (is (= (if fail? :failed :completed) (:attempt/status receipt)))
                (is (= (if fail? 0.0 1.0) (:attempt/reward receipt)))
                (doseq [summary [":test 1" ":pass 1" ":fail 0" ":error 0"]]
                  (is (str/includes? (str @tool-result) summary) (str @tool-result)))
                (is (= p/original-source
                       (get-in persisted [:attempt/evidence :artifacts :files p/source-path :source])))
                (is (= test-source
                       (get-in persisted [:attempt/evidence :artifacts :files p/test-path :source])))
                (is (nil? (registry/lookup (get-in result [:run/result :run/world]))))
                (is (nil? (fs/stat (g/filesystem) p/source-path)))
                (is (nil? (fs/stat (g/filesystem) p/test-path)))
                (is (= 2 @calls))))))
        (finally
          (evaluation/await-cleanups! room)
          (d/close-room! room)
          (close!))))))
