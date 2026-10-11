(ns dvergr.catalog.room-run-test
  "The `room-run` shell is a client of the room path: the bundle goes into a
   room of a dvergr home through room_write, the benchmark runs through
   catalog_benchmark, the report comes from experiment_report. Nothing is
   written beside the home's stores, and running again resumes."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dvergr.agent.experiment.runner :as runner]
            [dvergr.catalog.room :as room-wf]
            [dvergr.catalog.room-run :as room-run]
            [dvergr.catalog.room-test :as room-test]
            [dvergr.chat.agent :as chat-agent]
            [dvergr.model.chat :as model-chat]
            [dvergr.model.providers :as providers]
            [dvergr.ops :as ops]
            [dvergr.substrate.paths :as paths]))

(defn- tmp [prefix] (str (System/getProperty "java.io.tmpdir") "/" prefix (random-uuid)))

(defn- bundle-dir
  "The test bundle as a directory, as `room-run` is given one."
  []
  (let [dir (io/file (tmp "dvergr-room-run-bundle-") "competitors")]
    (doseq [[p t] room-test/files]
      (let [f (io/file dir p)] (io/make-parents f) (spit f t)))
    (str dir)))

(defn- writer
  "Writes `answer` on an attempt's first call, ends the turn on its second;
   counts its calls."
  [calls answer]
  (fn [messages _]
    (swap! calls inc)
    (if (not-any? #(= :tool-result (or (:role %) (:message/role %))) messages)
      {:content "" :tool-calls [{:id (str "w" (random-uuid)) :name "write_file"
                                 :input {:path "/out/competitors.md" :content answer}}]
       :usage {:input-tokens 800 :output-tokens 50} :stop-reason :tool-use}
      {:content "Done." :tool-calls nil :usage {:input-tokens 900 :output-tokens 5} :stop-reason :end-turn})))

(defn- broken [_ _] (throw (ex-info "Max retries exceeded" {:status 503})))

(defn- bench! [model b home & {:as opts}]
  (with-redefs [providers/ensure-initialized! (constantly nil)
                chat-agent/messages->api-format (fn [messages _ _] messages)
                model-chat/chat model]
    (room-run/benchmark! b (merge {:home home :models ["claude-haiku-4-5"] :repetitions 2 :timeout-ms 60000}
                                  opts))))

(defn- loose-files
  "Files and directories under `dir` named like the old side channels."
  [dir]
  (->> (file-seq (io/file dir))
       (filter #(#{"report.md" "artifacts"} (.getName ^java.io.File %)))
       (mapv str)))

(deftest a-bundle-directory-runs-in-a-room-of-its-home
  (let [prev (paths/home)
        dir (bundle-dir)
        before (into {} (map (juxt str slurp)) (filter #(.isFile ^java.io.File %) (file-seq (io/file dir))))
        home (tmp "dvergr-room-run-home-")
        calls (atom 0)
        b (room-wf/read-dir dir)
        first-run (bench! (writer calls "Wato\nPromptQL\nDust\nBuzz\n") b home)]
    (testing "scored by the bundle's checker, through the ops"
      (is (zero? (:failed-cells first-run)) (pr-str (dissoc (:job first-run) :result)))
      (is (= "competitors" (:room first-run)))
      (is (= 1.0 (get-in first-run [:scorecard :scorecard/summary 0 :reward-mean])))
      (is (= 4 @calls)))
    (testing "the report is the room's, with the frontier"
      (is (str/starts-with? (:report first-run) "# Competitors"))
      (is (str/includes? (:report first-run) "## Frontier"))
      (is (str/includes? (:report first-run) "| 2 | 2 (100%)")))
    (testing "nothing is written beside the home's stores, nor into the bundle"
      (is (= [] (loose-files home)))
      (is (= before (into {} (map (juxt str slurp)) (filter #(.isFile ^java.io.File %) (file-seq (io/file dir)))))))
    (testing "the process gets its home back"
      (is (= prev (paths/home))))
    (testing "running again resumes: no cell runs again"
      (let [again (bench! (writer calls "nothing") b home)]
        (is (= 4 @calls))
        (is (= (:experiment first-run) (:experiment again)))
        (is (= 1.0 (get-in again [:scorecard :scorecard/summary 0 :reward-mean])))))
    (testing "another library version is another experiment: nothing resumes into it"
      (let [bumped (with-redefs [runner/library-versions (constantly {"org.replikativ/spindel" "9.9.9"})]
                     (bench! (writer calls "Wato\nPromptQL\nDust\nBuzz\n") b home))]
        (is (not= (:experiment first-run) (:experiment bumped)))
        (is (= 8 @calls) "its cells ran")))
    (testing "the report reads back through experiment_report, and the bundle is in the room"
      (runner/with-home home
        (fn [daemon]
          (let [{:keys [markdown complete]} (ops/invoke daemon :experiment/report {:room "competitors"})]
            (is complete)
            (is (str/includes? markdown "## Frontier")))
          (is (= (get room-test/files "checker.clj")
                 (:text (ops/invoke daemon :room/read {:room "competitors"
                                                       :path "workflows/competitors/checker.clj"})))))))
    (testing "a file removed from the bundle is removed from the room"
      (let [smaller (room-wf/bundle "competitors" (dissoc room-test/files "gold.edn"))]
        (runner/with-home home
          (fn [daemon]
            (room-run/sync-bundle! daemon "competitors" "competitors" (:files smaller))
            (is (thrown? Exception (ops/invoke daemon :room/read {:room "competitors"
                                                                  :path "workflows/competitors/gold.edn"})))))))))

(deftest faulted-cells-run-again-on-resume
  (let [home (tmp "dvergr-room-run-home-")
        b (room-wf/bundle "competitors" room-test/files)
        calls (atom 0)
        failed (bench! broken b home :repetitions 1 :fault-retries 0)]
    (is (= 1 (:failed-cells failed)))
    (is (nil? (:scorecard failed)))
    (is (str/includes? (:report failed) "**Incomplete:**") (:report failed))
    (let [done (bench! (writer calls "Wato\nPromptQL\nDust\nBuzz\n") b home :repetitions 1)]
      (is (zero? (:failed-cells done)))
      (is (= (:experiment failed) (:experiment done)) "the same experiment, resumed")
      (is (= 2 @calls) "only the faulted cell ran")
      (is (= 1.0 (get-in done [:scorecard :scorecard/summary 0 :reward-mean]))))))

(deftest an-old-experiment-directory-is-refused
  (let [home (tmp "dvergr-room-run-old-")]
    (.mkdirs (io/file home "artifacts"))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"old layout"
                          (room-run/benchmark! (room-wf/bundle "competitors" room-test/files)
                                               {:home home :models ["claude-haiku-4-5"]})))
    (is (= ["artifacts"] (mapv #(.getName ^java.io.File %) (.listFiles (io/file home)))) "left as it was")))

(deftest options-cross-the-op-or-are-refused
  (let [home (tmp "dvergr-room-run-home-")
        b (room-wf/bundle "competitors" room-test/files)
        calls (atom 0)]
    (testing "a judge function or a granted tier cannot cross catalog_benchmark"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Not through catalog_benchmark"
                            (room-run/benchmark! b {:home home :models ["claude-haiku-4-5"] :judge-fn identity}))))
    (testing "params, prompt and parallelism reach the experiment"
      (let [{:keys [scorecard]} (bench! (writer calls "Wato\nPromptQL\nDust\nBuzz\n") b home
                                        :params {:product "Acme"} :prompt "You list competitors." :parallelism 2)
            exp (:scorecard/experiment scorecard)]
        (is (str/includes? (pr-str (get-in exp [:experiment/dataset :dataset/environments])) "competing with Acme"))
        (is (= (map :candidate/agent-content-id
                    (:experiment/candidates
                     (runner/experiment-def (assoc (room-wf/experiment-plan b {:models ["claude-haiku-4-5"]
                                                                               :prompt "You list competitors."})
                                                   :id :any))))
               (map :candidate/agent-content-id (:experiment/candidates exp)))
            "the candidate is the one with that prompt")))))

(def ^:private case-files
  {"workflow.edn" (pr-str {:title "Sums" :task "Answer /docs/q.txt in /out/a.txt." :capture ["/out"]})
   "checker.clj" "(ns s) (defn check [{:keys [files gold]}] (let [ok (= (:a gold) (get files \"/out/a.txt\"))] {:checks {:right? ok} :reward (if ok 1.0 0.0)}))"
   "cases/one/gold.edn" (pr-str {:a "2"}) "cases/one/fixtures/docs/q.txt" "1+1"
   "cases/two/gold.edn" (pr-str {:a "4"}) "cases/two/fixtures/docs/q.txt" "2+2"})

(deftest a-case-selection-by-id-faults-and-resumes
  (let [home (tmp "dvergr-room-run-home-")
        b (room-wf/bundle "sums" case-files)
        failed (bench! broken b home :repetitions 1 :fault-retries 0 :cases ["two"])]
    (is (= 1 (:failed-cells failed)) "one selected case, one cell")
    (is (str/includes? (:report failed) "**Incomplete:**"))))
