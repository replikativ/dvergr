(ns dvergr.agent.experiment.home-test
  "An experiment's directory is a dvergr home: `run!` opens it with a
   headless daemon, keeps the experiment in a room of it (one per
   ExperimentDef), reads the report through the `experiment/report` op, and
   gives the process its home back whatever happens."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dvergr.agent.experiment.runner :as runner]
            [dvergr.catalog.wiki :as wiki]
            [dvergr.chat.agent :as chat-agent]
            [dvergr.mcp.server :as mcp]
            [dvergr.model.api.claude-code :as cc]
            [dvergr.model.chat :as model-chat]
            [dvergr.model.providers :as providers]
            [dvergr.discourse.definitions]
            [dvergr.actors]
            [dvergr.rooms]
            [dvergr.discourse]
            [dvergr.rooms.scheduler]
            [dvergr.ops :as ops]
            [dvergr.orchestration.daemon :as daemon]
            [dvergr.substrate.paths :as paths]))

(defn- tmp [] (str (System/getProperty "java.io.tmpdir") "/dvergr-home-" (random-uuid)))

(deftest a-home-is-opened-headless-and-closed-whatever-happens
  (let [prev (paths/home)
        dir (tmp)]
    (testing "a headless daemon: no Telegram, web or MCP listener"
      (let [h (runner/open-home! dir)]
        (try
          (is (= (.getCanonicalPath (io/file dir)) (paths/home)))
          (is (identical? (:daemon h) @daemon/current-daemon))
          (is (every? nil? (map #(get (:daemon h) %) [:telegram-ch :http-server :mcp-server :mcp-http])))
          (is (not (:running (mcp/status))) "no MCP server on a fixed port")
          (testing "its rooms run no schedules"
            (ops/invoke (:daemon h) dvergr.principal/local-principal :room/create {:title "Deployed" :slug "deployed"})
            (is (nil? (get @@#'dvergr.rooms.scheduler/room-schedulers :deployed))))
          (testing "a second home in the same process is refused"
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"runs a daemon" (runner/open-home! (tmp)))))
          (testing "a candidate's MCP server is the home's socket alone (no TCP), and closed with the home"
            (let [st (mcp/ensure-listening!)]
              (is (nil? (:port st)) "no TCP port")
              (is (some? (:socket-path st)))))
          (finally (runner/close-home! h))))
      (is (not (:running (mcp/status))))
      (is (false? @@#'dvergr.rooms.scheduler/suspended) "schedules run again outside it")
      (is (nil? @daemon/current-daemon))
      (is (= prev (paths/home))))
    (testing "an exception inside closes the home too"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"boom"
                            (runner/with-home dir (fn [_] (throw (ex-info "boom" {}))))))
      (is (nil? @daemon/current-daemon))
      (is (= prev (paths/home))))
    (testing "an experiment directory of the old layout is refused, and left as it was"
      (let [old (tmp)]
        (.mkdirs (io/file old "artifacts"))
        (.mkdirs (io/file old "store"))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"old layout" (runner/open-home! old)))
        (is (= #{"artifacts" "store"} (set (map #(.getName ^java.io.File %) (.listFiles (io/file old))))))
        (is (= prev (paths/home)))))))

(defn- silent-model [calls]
  (fn [_ _]
    (swap! calls inc)
    {:content "" :tool-calls nil :usage {:input-tokens 500 :output-tokens 0} :stop-reason :end-turn}))

(defn- run-wiki [dir calls & {:as opts}]
  (with-redefs [providers/ensure-initialized! (constantly nil)
                chat-agent/messages->api-format (fn [messages _ _] messages)
                model-chat/chat (silent-model calls)]
    (wiki/experiment! (merge {:dir dir :version 2 :models ["claude-haiku-4-5"] :repetitions 1
                              :timeout-ms 60000 :fault-retries 0}
                             opts))))

(deftest run-keeps-the-experiment-in-a-room-of-its-home
  (let [dir (tmp)
        calls (atom 0)
        first-run (run-wiki dir calls)
        n @calls]
    (testing "the experiment's room is named after its ExperimentDef"
      (is (str/starts-with? (:room first-run) "catalog-wiki-"))
      (is (= (runner/experiment-slug (:experiment first-run)) (:room first-run)))
      (is (= 1 (:attempt-count (first (get-in first-run [:scorecard :scorecard/summary]))))))
    (testing "the report is the room's, and nothing lies beside the stores"
      (is (str/includes? (:report first-run) "## Frontier"))
      (is (empty? (filter #(#{"report.md" "artifacts" "store"} (.getName ^java.io.File %))
                          (.listFiles (io/file dir)))))
      (is (empty? (filter #(#{"report.md" "artifacts"} (.getName ^java.io.File %)) (file-seq (io/file dir))))))
    (testing "running it again resumes in the same room: no cell runs again"
      (let [again (run-wiki dir calls)]
        (is (= (:room first-run) (:room again)))
        (is (= n @calls))
        (is (nil? (get-in again [:scorecard :incomplete])))))
    (testing "a changed ExperimentDef is a new room: nothing resumes into it"
      (let [changed (run-wiki dir calls :timeout-ms 50000)]
        (is (not= (:room first-run) (:room changed)))
        (is (< n @calls))))
    (testing "progress and the report read back from the home"
      (is (= 2 (count (:rooms (runner/progress dir)))))
      (is (str/includes? (:markdown (runner/report dir (:room first-run))) "## Frontier"))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"name one" (runner/report dir))))))

(deftest run-in-a-running-daemon-uses-its-home
  (let [prev (paths/home)
        dir (tmp)
        calls (atom 0)]
    (paths/set-home! dir)
    (dvergr.system.db/reset-conn!)
    (let [d (daemon/start! {:agents {}})]
      (try
        (let [r (run-wiki nil calls :daemon d :experiment-id :wiki-in-daemon)]
          (is (= d @daemon/current-daemon) "no second daemon")
          (is (= 1 (:attempt-count (first (get-in r [:scorecard :scorecard/summary])))))
          (is (some? (ops/resolve-room d (:room r))) "a room of the daemon's home")
          (is (:complete (ops/invoke d dvergr.principal/local-principal :experiment/report {:room (:room r)})))
          (testing "a setup that fails gives Claude Code's settings back"
            (let [before (cc/settings-snapshot)]
              (with-redefs [cc/cli-version (constantly nil)]
                (is (thrown-with-msg? clojure.lang.ExceptionInfo #"CLI not runnable"
                                      (run-wiki nil calls :daemon d :experiment-id :wiki-cc
                                                :models ["claude-code-haiku"] :host-context-note "a note"))))
              (is (= before (cc/settings-snapshot))))))
        (finally
          (daemon/stop! d)
          (dvergr.system.db/reset-conn!)
          (paths/set-home! prev))))))

(deftest a-home-s-daemon-brings-no-agent-online
  ;; neither a durable agent of the home nor a vetted autostart file of the
  ;; user's or the project's
  (let [created (atom [])]
    (with-redefs [daemon/create-agent! (fn [_ cfg] (swap! created conj (:id cfg)) nil)
                  dvergr.discourse.definitions/autostart-agents (constantly {:autostarter {:id :autostarter :rooms ["*"]}})
                  dvergr.actors/list-actors (fn [& _] [{:id :durable-one :kind :agent :status :active}])]
      (runner/with-home (tmp) (fn [_] nil)))
    (is (= [] @created))))

(deftest a-start-that-fails-halfway-is-undone
  (let [prev (paths/home)
        dir (tmp)]
    (let [closed (atom [])
          close! dvergr.discourse/close-room!]
      (with-redefs [dvergr.rooms/hydrate-registry! (fn [_] (throw (ex-info "hydration broke" {})))
                    dvergr.discourse/close-room!
                    (fn
                      ([r] (swap! closed conj (:id r)) (close! r))
                      ([r opts] (swap! closed conj (:id r)) (close! r opts)))]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"hydration broke" (runner/open-home! dir))))
      (is (some #{:daemon} @closed) "the root room (its context's drain) is closed")
      (is (nil? @@#'daemon/starting-room)))
    (is (nil? @daemon/current-daemon))
    (is (= prev (paths/home)))
    (testing "and the home opens afterwards"
      (is (= :opened (runner/with-home dir (fn [d] (when (some? d) :opened))))))))

(deftest claude-code-settings-come-back-with-the-home
  (let [before (cc/settings-snapshot)]
    (with-redefs-fn {#'daemon/configure-claude-code!
                     (fn [_] (cc/configure! {:env {"CLAUDE_CONFIG_DIR" "/elsewhere"} :system-note "the home's"}))}
      (fn []
        (runner/with-home (tmp) (fn [_] (is (= "the home's" (:system-note (cc/settings-snapshot))))))
        (is (= before (cc/settings-snapshot)))
        (is (thrown? Exception (runner/with-home (tmp) (fn [_] (throw (ex-info "x" {}))))))
        (is (= before (cc/settings-snapshot)))))))
