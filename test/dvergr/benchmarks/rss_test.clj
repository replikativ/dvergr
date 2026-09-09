(ns dvergr.benchmarks.rss-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [dvergr.agent.evaluation :as evaluation]
            [dvergr.agent.roster :as roster]
            [dvergr.benchmarks.rss :as rss]
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

(def repaired-source
  (str/replace rss/original-source
               #"(?s)\(if \(str/starts-with\? feed-url \"http\"\).*?feed-url\)\)\)"
               "(str (.resolve (java.net.URI. url) feed-url))"))

(deftest reference-cases-check-resolution-and-preserved-behavior
  (is (= 18 (count rss/url-cases)))
  (is (not= rss/original-source repaired-source))
  (let [broken (rss/check-source rss/original-source)
        repaired (rss/check-source repaired-source)]
    (is (:evaluated? broken))
    (is (false? (:url-0 broken)))
    (doseq [id [:fetch-error :fallback :rss-parse-and-limit :atom-parse]]
      (is (true? (get broken id)) (str id)))
    (is (every? true? (vals repaired)) (pr-str repaired)))
  (doseq [source ["nil" "(" "(ns dvergr.intake.rss)"]]
    (is (false? (:evaluated? (rss/check-source source)))))
  (is (= {:source? false} (rss/check-source nil))))

(deftest standalone-setup-requires-a-workspace
  (let [runtime (ctx/create-execution-context)]
    (try
      (binding [ec/*execution-context* runtime]
        (with-redefs [g/filesystem (constantly nil)]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"registered Geschichte workspace"
                                ((:prepare (rss/world-setup)) {:room {:ctx runtime}})))))
      (finally (ctx/close-context! runtime)))))

(def regression-source
  (str "(ns rss-repair-test (:require [clojure.test :refer [deftest is]] "
       "[dvergr.intake.rss :as rss] [dvergr.intake.core :as intake]))\n"
       "(deftest relative-url (with-redefs [intake/fetch-text "
       "(fn [& _] \"<link rel='alternate' type='application/rss+xml' href='feed.xml'>\")] "
       "(is (= \"https://example.org/blog/feed.xml\" "
       "(:url (first (rss/discover-feeds \"https://example.org/blog/\")))))))\n"))

(deftest rss-repair-through-real-sci-tools-and-disposal
  (doseq [variant [:unchanged :repaired :failed]]
    (let [{:keys [conn close!]} (gfs/memory-repository! {:name "rss-coding-run"})
          room (d/make-room {:id :rss-coding-run :store (memory/make)})
          source (if (= :unchanged variant) rss/original-source repaired-source)
          team (roster/make-agent (roster/make-roster)
                                  {:id :coder :tools #{:clojure_eval}
                                   :model-policy {:provider :test :model "stub"}
                                   :program {:kind :llm :max-model-steps 3 :auto-compact? false}})
          calls (atom 0)
          tool-result (atom nil)
          code (str "(spit " (pr-str rss/source-path) " " (pr-str source) ") "
                    "(spit " (pr-str rss/test-path) " " (pr-str regression-source) ") "
                    "(require 'dvergr.intake.rss :reload) "
                    "(load-string (slurp " (pr-str rss/test-path) ")) "
                    "(clojure.test/run-tests 'rss-repair-test)")]
      (try
        (binding [ec/*execution-context* (:ctx room)]
          (ygg/register! (gy/create conn {:system-name "room-repo-rss-test"}))
          ;; Parent has a different dependency; setup must replace only the fork.
          (fs/write-string! (g/filesystem) rss/dependency-path "parent sentinel" false)
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
                              (if (= :failed variant)
                                (throw (ex-info "Provider failed after RSS repair" {}))
                                {:content "Saved source and tests." :tool-calls nil
                                 :usage {:input-tokens 0 :output-tokens 0} :stop-reason :end-turn}))))]
            (let [done (promise)
                  computation (evaluation/evaluate room team :coder (rss/definition) (rss/evaluator)
                                                   {:world-setup (rss/world-setup)})
                  _ (computation #(deliver done {:result %}) #(deliver done {:error %}))
                  outcome (deref done 60000 ::timeout)]
              (is (not= ::timeout outcome))
              (when-let [error (:error outcome)] (throw error))
              (let [result (:result outcome)
                    receipt (:attempt-receipt result)
                    persisted (first (store/-list-attempts (:store room) (:id room) {}))]
                (is (= (if (= :failed variant) :failed :completed) (:attempt/status receipt)))
                (is (= (if (= :repaired variant) 1.0 0.0) (:attempt/reward receipt)))
                (is (str/includes? (str @tool-result) ":test 1") (str @tool-result))
                (is (str/includes? (str @tool-result) ":error 0") (str @tool-result))
                (is (str/includes? (str @tool-result) (if (= :unchanged variant) ":fail 1" ":fail 0"))
                    (str @tool-result))
                (is (= source (get-in persisted [:attempt/evidence :artifacts :files rss/source-path :source])))
                (is (= regression-source (get-in persisted [:attempt/evidence :artifacts :files rss/test-path :source])))
                (is (nil? (registry/lookup (get-in result [:run/result :run/world]))))
                (is (nil? (fs/stat (g/filesystem) rss/source-path)))
                (is (nil? (fs/stat (g/filesystem) rss/test-path)))
                (is (= "parent sentinel" (fs/read-file (g/filesystem) rss/dependency-path)))
                (is (= 2 @calls))))))
        (finally
          (evaluation/await-cleanups! room)
          (d/close-room! room)
          (close!))))))
