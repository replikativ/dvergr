(ns dvergr.catalog.room-test
  "A workflow defined as files (doc/room-workflows.md): checked for shape, its
   checker run in SCI with no effects, and benchmarked like a catalog
   workflow, the checker's verdict on every Attempt with its `:ad-hoc` tier."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dvergr.agent.evaluation :as evaluation]
            [dvergr.catalog.room :as room-wf]
            [dvergr.chat.agent :as chat-agent]
            [dvergr.model.chat :as model-chat]
            [dvergr.model.providers :as providers]
            [dvergr.substrate.paths :as paths]
            [dvergr.system.db :as sdb]))

(def checker
  "(ns competitors.checker
     (:require [clojure.string :as str]))

   (defn check [{:keys [files gold]}]
     (let [text (str/lower-case (get files \"/out/competitors.md\" \"\"))
           found (filter #(str/includes? text (str/lower-case %)) (:competitors gold))
           recall (/ (count found) (max 1 (count (:competitors gold))))]
       {:checks {:wrote-list? (boolean (seq text))
                 :found-all? (= (count found) (count (:competitors gold)))}
        :reward (double recall)}))")

(def files
  {"workflow.edn" (pr-str {:title "Competitors"
                           :doc "Name the products competing with a given one."
                           :task "Read /docs/market.md and write the products competing with {product}, one per line, to /out/competitors.md."
                           :params {:product "Simmis"}
                           :capture ["/out"]})
   "checker.clj" checker
   "gold.edn" (pr-str {:competitors ["Wato" "PromptQL" "Dust" "Buzz"]})
   "fixtures/docs/market.md" "Simmis competes with Wato, PromptQL, Dust and Buzz."})

(deftest a-bundle-is-checked-for-shape
  (is (= [] (room-wf/check-files files)))
  (is (some #(str/includes? % "workflow.edn is missing") (room-wf/check-files (dissoc files "workflow.edn"))))
  (is (some #(str/includes? % "checker.clj is missing") (room-wf/check-files (dissoc files "checker.clj"))))
  (is (some #(str/includes? % "not EDN") (room-wf/check-files (assoc files "workflow.edn" "{:title"))))
  (is (some #(str/includes? % ":task") (room-wf/check-files (assoc files "workflow.edn" (pr-str {:title "x"})))))
  (is (some #(str/includes? % "fixtures/ is empty")
            (room-wf/check-files (dissoc files "fixtures/docs/market.md"))))
  (testing "a bundle is content-addressed and fills its task template"
    (let [b (room-wf/bundle "competitors" files)]
      (is (= {"/docs/market.md" "Simmis competes with Wato, PromptQL, Dust and Buzz."} (:fixtures b)))
      (is (str/includes? (room-wf/task b {}) "competing with Simmis"))
      (is (str/includes? (room-wf/task b {:product "Acme"}) "competing with Acme"))
      (is (not= (:id b) (:id (room-wf/bundle "competitors" (assoc files "gold.edn" "{:competitors []}"))))))))

(deftest the-checker-runs-without-effects
  (let [gold {:competitors ["Wato" "Dust"]}]
    (is (= {:checks {:wrote-list? true :found-all? true} :reward 1.0}
           (room-wf/run-checker checker {:files {"/out/competitors.md" "wato\ndust"} :gold gold})))
    (is (= 0.5 (:reward (room-wf/run-checker checker {:files {"/out/competitors.md" "Wato"} :gold gold})))))
  (testing "it cannot reach the world"
    (doseq [[what source] [["a file" "(ns c) (defn check [_] (slurp \"/etc/hostname\"))"]
                           ["the network" "(ns c) (defn check [_] (slurp \"https://example.com\"))"]
                           ["a host class" "(ns c) (defn check [_] (System/getenv \"HOME\"))"]
                           ["other code" "(ns c (:require [dvergr.room])) (defn check [_] {:checks {} :reward 1.0})"]]]
      (is (thrown? Exception (room-wf/run-checker source {})) what)))
  (testing "a verdict must be one"
    (is (thrown-with-msg? Exception #"no verdict" (room-wf/run-checker "(ns c) (defn check [_] {:reward 2})" {})))
    (is (thrown-with-msg? Exception #"defines no `check`" (room-wf/run-checker "(ns c) (defn chek [_] nil)" {})))))

(defn- scripted-model
  "First call in an attempt: write the list (`answer`); second: end the turn."
  [calls answer]
  (fn [_messages _opts]
    (if (odd? (swap! calls inc))
      {:content ""
       :tool-calls [{:id (str "w" @calls) :name "write_file"
                     :input {:path "/out/competitors.md" :content answer}}]
       :usage {:input-tokens 800 :output-tokens 50}
       :stop-reason :tool-use}
      {:content "Done." :tool-calls nil :usage {:input-tokens 900 :output-tokens 5} :stop-reason :end-turn})))

(defn- run [answer]
  (let [prev (paths/home)
        dir (str (System/getProperty "java.io.tmpdir") "/dvergr-room-workflow-" (random-uuid))
        calls (atom 0)]
    (try
      (with-redefs [providers/ensure-initialized! (constantly nil)
                    chat-agent/messages->api-format (fn [messages _ _] messages)
                    model-chat/chat (scripted-model calls answer)]
        (room-wf/experiment! (room-wf/bundle "competitors" files)
                             {:dir dir :models ["claude-haiku-4-5"] :repetitions 2 :timeout-ms 60000}))
      (finally
        (paths/set-home! prev)
        (sdb/reset-conn!)))))

(deftest a-bundle-benchmarks-like-a-catalog-workflow
  (testing "a complete answer"
    (let [{:keys [scorecard failed-cells]} (run "Wato\nPromptQL\nDust\nBuzz\n")
          [summary] (:scorecard/summary scorecard)]
      (is (zero? failed-cells) (pr-str (:incomplete scorecard)))
      (is (= 2 (:attempt-count summary)))
      (is (= 1.0 (:reward-mean summary)))
      (is (every? :passed? (:scorecard/entries scorecard)))))
  (testing "a partial one scores its recall; an unpromoted verifier is ad hoc"
    (let [{:keys [scorecard]} (run "Wato\nDust\n")
          [summary] (:scorecard/summary scorecard)]
      (is (= 0.5 (:reward-mean summary)))
      (is (not-any? :passed? (:scorecard/entries scorecard)))
      (is (= :ad-hoc (evaluation/evaluator-tier
                      (get-in (room-wf/experiment-plan (room-wf/bundle "competitors" files)
                                                       {:models ["claude-haiku-4-5"]})
                              [:capabilities :evaluator])))))))
