(ns dvergr.catalog.wiki-test
  "wiki/v1 as an experiment: each attempt's world gets its own workspace with
   the fixtures, a scripted worker writes a wiki there, the checker scores
   it, and the Attempts fold into a Scorecard."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dvergr.catalog.wiki :as wiki]
            [dvergr.chat.agent :as chat-agent]
            [dvergr.model.chat :as model-chat]
            [dvergr.model.providers :as providers]
            [dvergr.substrate.paths :as paths]
            [dvergr.system.db :as sdb]))

(defn- scripted-model
  "First call in an attempt: write a wiki (complete if `complete?`, else an
   index and one uncited page); second call: end the turn."
  [calls complete?]
  (let [page (if complete?
               (str "# Tessellate Instruments\n\n" (str/join "\n\n" (vals (wiki/fixtures)))
                    "\n\n[source](../docs/company.md)")
               "# Tessellate Instruments\n\nA company.")]
    (fn [_messages _opts]
      (if (odd? (swap! calls inc))
        {:content ""
         :tool-calls [{:id (str "i" @calls) :name "write_file"
                       :input {:path "/wiki/index.md" :content "# Wiki\n\n- [Tessellate](tessellate.md)\n"}}
                      {:id (str "p" @calls) :name "write_file"
                       :input {:path "/wiki/tessellate.md" :content page}}]
         :usage {:input-tokens 1000 :output-tokens 300}
         :stop-reason :tool-use}
        {:content "Done." :tool-calls nil
         :usage {:input-tokens 1100 :output-tokens 10} :stop-reason :end-turn}))))

(defn- run [complete?]
  (let [prev (paths/home)
        dir (str (System/getProperty "java.io.tmpdir") "/dvergr-wiki-experiment-" (random-uuid))
        calls (atom 0)]
    (try
      (with-redefs [providers/ensure-initialized! (constantly nil)
                    chat-agent/messages->api-format (fn [messages _ _] messages)
                    model-chat/chat (scripted-model calls complete?)]
        (wiki/experiment! {:dir dir :models ["claude-haiku-4-5"] :repetitions 2 :timeout-ms 60000}))
      (finally
        (paths/set-home! prev)
        (sdb/reset-conn!)))))

(deftest the-benchmark-set-runs-as-an-experiment
  (let [{:keys [scorecard failed-cells results]} (run true)
        [summary] (:scorecard/summary scorecard)]
    (is (zero? failed-cells) (pr-str (:incomplete scorecard)))
    (is (= 2 results))
    (testing "each attempt's world had the fixtures, and the wiki written there scored"
      (is (= 2 (:attempt-count summary)))
      (is (= 1.0 (:reward-mean summary)))
      (is (every? :passed? (:scorecard/entries scorecard)) "every check held, facts included")
      (is (= 2 (:passed-count summary))))
    (testing "the bill is next to the reward"
      (is (pos? (get-in summary [:spend :microdollars]))))))

(deftest a-weaker-wiki-scores-lower-in-the-same-experiment-shape
  (let [{:keys [scorecard]} (run false)
        [summary] (:scorecard/summary scorecard)]
    (is (< (:reward-mean summary) 0.5))
    (is (not-any? :passed? (:scorecard/entries scorecard)))
    (is (zero? (:passed-count summary)))))
