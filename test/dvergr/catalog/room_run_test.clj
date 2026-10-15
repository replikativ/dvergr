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
            [dvergr.model.api.claude-code :as claude-code]
            [dvergr.model.chat :as model-chat]
            [dvergr.model.providers :as providers]
            [dvergr.ops :as ops]
            [dvergr.substrate.paths :as paths]
            [dvergr.test-support]))

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
        (is (some? (get-in again [:job :result :scorecard])) "the fully resumed job names its Scorecard")
        (is (= (get-in first-run [:job :result :scorecard]) (get-in again [:job :result :scorecard])))
        (is (= 1.0 (get-in again [:scorecard :scorecard/summary 0 :reward-mean])))))
    (testing "another library version is another experiment: nothing resumes into it"
      (let [bumped (with-redefs [runner/library-versions (constantly {"org.replikativ/spindel" "9.9.9"})]
                     (bench! (writer calls "Wato\nPromptQL\nDust\nBuzz\n") b home))]
        (is (not= (:experiment first-run) (:experiment bumped)))
        (is (= 8 @calls) "its cells ran")))
    (testing "the report reads back through experiment_report, and the bundle is in the room"
      (runner/with-home home
        (fn [daemon]
          (let [{:keys [markdown complete]} (ops/invoke daemon dvergr.principal/local-principal :experiment/report {:room "competitors"})]
            (is complete)
            (is (str/includes? markdown "## Frontier")))
          (is (= (get room-test/files "checker.clj")
                 (:text (ops/invoke daemon dvergr.principal/local-principal :room/read {:room "competitors"
                                                                                        :path "workflows/competitors/checker.clj"})))))))
    (testing "a file of the bundle that became a directory syncs"
      (runner/with-home home
        (fn [daemon]
          (room-run/sync-bundle! daemon "competitors" "competitors" (assoc room-test/files "fixtures/notes" "a file"))
          (room-run/sync-bundle! daemon "competitors" "competitors" (assoc room-test/files "fixtures/notes/a.md" "now a directory"))
          (is (= "now a directory" (:text (ops/invoke daemon dvergr.principal/local-principal :room/read {:room "competitors"
                                                                                                          :path "workflows/competitors/fixtures/notes/a.md"})))))))
    (testing "and a directory that became a file again, across a reopened home"
      (runner/with-home home
        (fn [daemon]
          (room-run/sync-bundle! daemon "competitors" "competitors" (assoc room-test/files "fixtures/deep/er/a.md" "nested"))))
      (runner/with-home home
        (fn [daemon]
          (room-run/sync-bundle! daemon "competitors" "competitors" (assoc room-test/files "fixtures/deep" "a file again"))
          (is (= "a file again" (:text (ops/invoke daemon dvergr.principal/local-principal :room/read {:room "competitors"
                                                                                                       :path "workflows/competitors/fixtures/deep"})))))))
    (testing "a file removed from the bundle is removed from the room"
      (let [smaller (room-wf/bundle "competitors" (dissoc room-test/files "gold.edn"))]
        (runner/with-home home
          (fn [daemon]
            (room-run/sync-bundle! daemon "competitors" "competitors" (:files smaller))
            (is (thrown? Exception (ops/invoke daemon dvergr.principal/local-principal :room/read {:room "competitors"
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

(deftest refused-cells-are-not-retried-with-a-fresh-allowance
  (let [home (tmp "dvergr-room-run-home-")
        b (room-wf/bundle "competitors" room-test/files)
        asked (atom 0)
        benchmarks (atom 0)
        calls (atom 0)
        invoke ops/invoke]
    (with-redefs [runner/admit-for (fn [& _] (fn [_] (swap! asked inc) {:reason "allowance reached"}))
                  ops/invoke (fn [d k args] (when (= :catalog/benchmark k) (swap! benchmarks inc)) (invoke d k args))]
      (let [r (bench! (writer calls "Wato") b home)]
        (is (= "refused" (get-in r [:job :reason])) (pr-str (:job r)))
        (is (pos? @asked))
        (is (= 1 @benchmarks) "no second catalog_benchmark: it would grant a fresh share")
        (is (zero? @calls))
        (is (= 2 (:failed-cells r)))))))

(deftest a-bundle-of-more-files-than-a-listing-reads-is-read-back-whole
  ;; a workspace listing reads at most `max-files` (500): with it at 3, the
  ;; bundle (six files) is still read whole, else it would lose cases and
  ;; still score
  (let [home (tmp "dvergr-room-run-home-")
        b (room-wf/bundle "sums" case-files)]
    (with-redefs-fn {#'dvergr.catalog.workspace/max-files 3}
      (fn []
        (let [r (room-run/benchmark! b {:home home :check? true})]
          (is (= (str (:id b)) (get-in r [:calibration :bundle]))))
        (runner/with-home home
          (fn [daemon]
            (let [room (ops/resolve-room daemon "sums")]
              (is (= 3 (count (dvergr.catalog.workspace/read-tree room "/workflows/sums")))
                  "an ordinary listing stops at its bound"))))))
    (testing "a second run writes nothing: the room already holds the bundle"
      (let [writes (atom 0)
            invoke ops/invoke]
        (with-redefs [ops/invoke (fn [d p k a] (when (= :room/write k) (swap! writes inc)) (invoke d p k a))]
          (room-run/benchmark! b {:home home :check? true}))
        (is (zero? @writes))))))

(deftest a-bundle-that-cannot-be-listed-whole-is-not-read
  (let [home (tmp "dvergr-room-run-home-")
        list-dir muschel.fs/list-dir]
    (runner/with-home home
      (fn [daemon]
        (ops/invoke daemon dvergr.principal/local-principal :room/create {:title "Sums" :slug "sums"})
        (room-run/sync-bundle! daemon "sums" "sums" case-files)
        (let [r (ops/resolve-room daemon "sums")]
          (with-redefs [muschel.fs/list-dir (fn [fs path]
                                              (if (str/includes? (str path) "cases/two")
                                                (throw (ex-info "listing failed" {}))
                                                (list-dir fs path)))]
            (is (thrown-with-msg? Exception #"listing failed"
                                  (binding [org.replikativ.spindel.engine.core/*execution-context* (:ctx r)]
                                    (room-wf/bundle-files r "sums"))))))))))

(def ^:private fake-claude
  ;; `claude -p` as far as a room workflow sees it: it launches the MCP relay
  ;; its --mcp-config names (with that config's env), lists the tools,
  ;; writes the answer through write_file, and reports a result
  "#!/usr/bin/env bb
(require '[cheshire.core :as json] '[babashka.process :as p])
(def args *command-line-args*)
(when (some #{\"--version\"} args) (println \"2.1.999 (Claude Code)\") (System/exit 0))
(defn opt [f] (second (drop-while #(not= % f) args)))
(slurp *in*)
(when-let [cfg (get-in (json/parse-string (opt \"--mcp-config\")) [\"mcpServers\" \"dvergr\"])]
  (let [proc (p/process (into [(get cfg \"command\")] (get cfg \"args\")) {:extra-env (get cfg \"env\") :err :inherit})
        w (java.io.PrintWriter. (:in proc) true)
        r (java.io.BufferedReader. (java.io.InputStreamReader. (:out proc)))
        rpc (fn [id method params]
              (.println w (json/generate-string {:jsonrpc \"2.0\" :id id :method method :params params}))
              (loop [] (let [m (json/parse-string (.readLine r) true)] (if (= id (:id m)) m (recur)))))]
    (rpc 1 \"initialize\" {:protocolVersion \"2025-06-18\" :capabilities {} :clientInfo {:name \"fake-claude\" :version \"0\"}})
    (.println w (json/generate-string {:jsonrpc \"2.0\" :method \"notifications/initialized\"}))
    (rpc 2 \"tools/list\" {})
    (rpc 3 \"tools/call\" {:name \"write_file\" :arguments {:path \"/out/competitors.md\" :content \"Wato\\nPromptQL\\nDust\\nBuzz\\n\"}})
    (.close w)
    (p/destroy proc)))
(println (json/generate-string {:type \"result\" :result \"Done.\" :session_id \"s\" :total_cost_usd 0
                                :usage {:input_tokens 10 :output_tokens 2} :modelUsage {(opt \"--model\") {}}}))
")

(defn- bb? []
  (some #(.canExecute (io/file % "bb")) (str/split (str (System/getenv "PATH")) #":")))

(deftest a-claude-code-candidate-writes-nothing-into-the-checkout
  ;; the CLI's MCP relay was launched without DVERGR_HOME, so it kept its
  ;; tool cache in <checkout>/.dvergr/mcp, whatever the home
  (if-not (bb?)
    (dvergr.test-support/skip! "a-claude-code-candidate-writes-nothing-into-the-checkout: no bb on PATH")
    (let [home (tmp "dvergr-room-run-cc-")
          script (doto (io/file (tmp "fake-claude-") "claude") io/make-parents (spit fake-claude) (.setExecutable true))
          checkout-mcp (io/file (System/getProperty "user.dir") ".dvergr" "mcp")
          listing #(set (map str (file-seq checkout-mcp)))
          before (listing)
          settings (claude-code/settings-snapshot)
          b (room-wf/bundle "competitors" room-test/files)]
      (try
        (claude-code/configure! {:cli (.getAbsolutePath ^java.io.File script)})
        (let [r (with-redefs [providers/ensure-initialized! (fn [] (providers/register-claude-code! {}))]
                  (room-run/benchmark! b {:home home :models ["claude-code-haiku"] :repetitions 1 :timeout-ms 120000}))]
          (is (zero? (:failed-cells r)) (pr-str (dissoc (:job r) :result)))
          (is (= 1.0 (get-in r [:scorecard :scorecard/summary 0 :reward-mean])) "it answered through the relay")
          (is (= before (listing)) "nothing new under the checkout's .dvergr")
          (is (seq (filter #(re-find #"tools-.*-room-.*\.json$" (str %)) (file-seq (io/file home "mcp"))))
              "the relay's tool cache is in the home, keyed by its room"))
        (finally (claude-code/restore-settings! settings))))))

(deftest a-poll-says-how-far-the-benchmark-is
  ;; job_status waits at most 25 s (below the clients' call timeouts); a
  ;; newcomer's assistant polled twenty times and learnt nothing
  (let [home (tmp "dvergr-room-run-home-")
        seen (atom [])
        r (bench! (writer (atom 0) "Wato\nPromptQL\nDust\nBuzz\n") (room-wf/bundle "competitors" room-test/files) home
                  :on-progress #(swap! seen conj %))]
    (is (zero? (:failed-cells r)))
    (is (seq @seen))
    (is (every? #(= 2 (:cells-total %)) @seen) (pr-str @seen))
    (is (= {:cells-done 2 :cells-total 2 :faults 0 :phase "done"} (last @seen)))))

(deftest the-default-home-is-under-dvergr-home
  (is (str/ends-with? (room-run/default-home "vat-codes") "workflow-runs/vat-codes")))
