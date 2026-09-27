(ns dvergr.sandbox.hardening-test
  "Holes the 2026-09-27 effect inventory found (doc/effects.md, Hardening
   first), each pinned closed with the capability it was closed without
   breaking: an XML parse that read the network or disk past every guard, a
   JVM-global tap side channel, file tools unclamped on the host disk, model
   calls from code that skipped the budget, an agent extending its own
   budget, authorship claimed by the caller, and global writes an agent made
   on other agents' rows."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as dh]
            [dvergr.actors :as actors]
            [dvergr.agent.persona]
            [dvergr.chat.schema :as schema]
            [dvergr.discourse :as d]
            [dvergr.orchestration.tasks :as tasks]
            [dvergr.room.store.memory :as memory]
            [dvergr.sandbox.ns.agent :as agent-ns]
            [dvergr.media.vision :as vision]
            [dvergr.model.chat :as model-chat]
            [dvergr.sandbox :as sandbox]
            [dvergr.sandbox.ns.io :as ns-io]
            [dvergr.sandbox.ns.kb :as ns-kb]
            [dvergr.tools :as tools]
            [dvergr.tools.llm-call :as llm-call]
            [org.replikativ.spindel.engine.context :as ctx]
            [org.replikativ.spindel.engine.core :as rtc]
            [sci.core :as sci]))

(defn- with-sandbox [f]
  (let [ec (ctx/create-execution-context)
        sci-ctx (sandbox/fork-for-session ec)]
    (try
      (sandbox/setup-agent-namespaces! sci-ctx ec)
      (f sci-ctx ec)
      (finally (ctx/stop-context! ec)))))

(defn- eval-in [sci-ctx ec code]
  (binding [rtc/*execution-context* ec]
    (let [r (sandbox/eval-code sci-ctx code)]
      (if (:success r) {:ok (:value r)} {:err (get-in r [:error :message])}))))

(deftest xml-parse-reads-no-uri
  (with-sandbox
    (fn [sci-ctx ec]
      (testing "a URI is refused, not slurped by the host"
        (let [f (doto (java.io.File/createTempFile "hardening" ".xml") (spit "<secret>host file</secret>"))
              r (eval-in sci-ctx ec (str "(clojure.data.xml/parse (java.net.URI. \"" (.toURI f) "\"))"))]
          (is (:err r))
          (is (re-find #"string or a reader" (str (:err r))))
          (.delete f)))
      (testing "parsing a string still works"
        (is (= "x" (:ok (eval-in sci-ctx ec "(clojure.data.xml/text (clojure.data.xml/parse \"<a>x</a>\"))"))))))))

(deftest the-jvm-global-tap-set-is-out-of-reach
  (with-sandbox
    (fn [sci-ctx ec]
      (is (:err (eval-in sci-ctx ec "(add-tap (fn [x] x))")) "add-tap is withheld")
      (is (:err (eval-in sci-ctx ec "(remove-tap identity)")))
      (is (contains? #{true false} (:ok (eval-in sci-ctx ec "(tap> 1)"))) "tap> still works"))))

(deftest file-tools-stay-in-their-workspace-on-the-host-disk
  (let [root (.getAbsoluteFile (doto (io/file (System/getProperty "java.io.tmpdir") (str "hardening-" (random-uuid))) .mkdirs))
        ctx (tools/make-context {:cwd (str root)})
        read (fn [path] (tools/execute "read_file" {:path path} ctx))]
    (try
      (spit (io/file root "notes.md") "inside")
      (testing "paths in the workspace, relative or absolute, work"
        (is (re-find #"inside" (str (:content (read "notes.md")))))
        (is (re-find #"inside" (str (:content (read (str root "/notes.md")))))))
      (testing "a host path outside it is refused"
        (let [outside (doto (java.io.File/createTempFile "outside" ".txt") (spit "host"))]
          (is (not (re-find #"host" (str (:content (read (str outside)))))))
          (is (= :error (:type (read (str outside)))))
          (.delete outside))
        (is (= :error (:type (read "../../../../etc/hostname"))) "traversal is canonicalised"))
      (finally
        (doseq [f (reverse (file-seq root))] (.delete f))))))

(deftest model-calls-from-code-are-the-agents-spend
  (let [charged (atom [])
        chat ::chat]
    (with-redefs [llm-call/cheap-llm-call (fn [& _] {:text "ok" :usage {:input-tokens 10 :output-tokens 5} :model "m"})
                  llm-call/account-response! (fn [c r] (swap! charged conj [c (:usage r)]))
                  dvergr.chat.context/budget-exceeded? (fn [c] (= c ::broke))]
      (testing "llm/call is charged to the chat"
        (let [sci-ctx (sci/init {})]
          (ns-kb/add-llm-ns! sci-ctx nil chat)
          (is (= "ok" (:text (sci/eval-string* sci-ctx "(llm/call \"s\" \"c\")"))))
          (is (= [[chat {:input-tokens 10 :output-tokens 5}]] @charged))))
      (testing "and refused over budget"
        (let [sci-ctx (sci/init {})]
          (ns-kb/add-llm-ns! sci-ctx nil ::broke)
          (is (thrown-with-msg? Exception #"Budget exceeded" (sci/eval-string* sci-ctx "(llm/call \"s\" \"c\")"))))))
    (testing "vision reports every response, so the sandbox wrapper can charge it"
      (let [seen (atom nil)]
        (with-redefs [model-chat/chat (fn [_ _] {:content "a cat" :usage {:input-tokens 7}})]
          (binding [vision/*on-response* #(reset! seen %)]
            (is (= "a cat" (vision/describe (byte-array [1 2 3]) "image/png")))))
        (is (= {:input-tokens 7} (:usage @seen)))))))

(deftest an-agent-does-not-extend-its-own-budget
  (let [sci-ctx (sci/init {})]
    (ns-io/add-process-ns! sci-ctx nil)
    (is (thrown-with-msg? Exception #"supervisor"
                          (sci/eval-string* sci-ctx "(processes/directive! #uuid \"00000000-0000-0000-0000-000000000001\" {:type :extend-budget :dollars 1})")))
    (is (thrown-with-msg? Exception #"supervisor"
                          (sci/eval-string* sci-ctx "(processes/directive! #uuid \"00000000-0000-0000-0000-000000000001\" {:type :continue :effects [{:op :extend-budget :dollars 1}]})")))))

(deftest post-takes-its-author-from-the-runtime
  (let [room (d/make-room {:id :hardening-post :store (memory/make)})
        acting (atom :var)
        post! ('post! (ns-kb/room-ops-map (:ctx room) nil
                                          (select-keys room [:id :incarnation])
                                          {:acting-agent #(deref acting)}))
        last-from #(:from (last (d/messages room {})))]
    (testing "the acting agent is the author"
      (post! (:id room) {:content "hello"})
      (is (= :var (last-from))))
    (testing "a claimed author is refused, not posted"
      (doseq [claim [{:from :user} {:source-user "alice"} {:source-username "alice"}
                     {:source-user-id 42}]]
        (is (thrown-with-msg? Exception #"author from the runtime"
                              (post! (:id room) (assoc claim :content "spoof")))))
      (is (= :var (last-from))))
    (testing "with no acting agent the sandbox is the author, never :user"
      (reset! acting nil)
      (post! (:id room) {:content "bare"})
      (is (= :sandbox (last-from))))))

(defn- with-sys-conn [f]
  (let [cfg {:store {:backend :memory :id (random-uuid)}
             :keep-history? false :schema-flexibility :write}]
    (dh/create-database cfg)
    (let [conn (dh/connect cfg)]
      (schema/install-schema! conn)
      (try (f conn) (finally (dh/release conn) (dh/delete-database cfg))))))

(defn- sandbox-as [conn binding]
  (let [sci-ctx (sci/init {})
        resolver (constantly binding)]
    (agent-ns/add-actors-ns! sci-ctx conn resolver)
    (agent-ns/add-tasks-ns! sci-ctx conn resolver)
    #(sci/eval-string* sci-ctx %)))

(deftest global-writes-are-scoped-to-the-acting-agent
  (with-sys-conn
    (fn [conn]
      (actors/spawn-agent! conn {:id :var :name "Var"})
      (actors/spawn-agent! conn {:id :huginn :name "Huginn"})
      (let [as-var (sandbox-as conn {:agent-id :var :room-runtime-id :ops})]
        (testing "an agent changes its own row"
          (as-var "(dvergr.actors/add-skill! :var :prose)")
          (is (contains? (:skills (actors/lookup conn :var)) :prose)))
        (testing "but not another agent's"
          (doseq [code ["(dvergr.actors/update! :huginn {:name \"Mallory\"})"
                        "(dvergr.actors/add-skill! :huginn :prose)"
                        "(dvergr.actors/dismiss! :huginn)"
                        "(dvergr.actors/spawn-agent! {:id :huginn :name \"Mallory\"})"
                        "(dvergr.actors/spawn-human! {:id :eve :external-refs {:telegram 1}})"]]
            (is (thrown-with-msg? Exception #"only its own|exists|owner" (as-var code)) code))
          (is (= "Huginn" (:name (actors/lookup conn :huginn))))
          (is (not= :retired (:status (actors/lookup conn :huginn))))
          (is (nil? (actors/lookup conn :eve))))
        (testing "an agent it spawned is its own to change"
          (as-var "(dvergr.actors/spawn-agent! {:id :scribe :name \"Scribe\"})")
          (is (= :var (get-in (actors/lookup conn :scribe) [:config :spawned-by])))
          (as-var "(dvergr.actors/dismiss! :scribe)")
          (is (= :retired (:status (actors/lookup conn :scribe)))))
        (testing "tasks: its own and its room's, not another room's"
          (let [mine (tasks/create-task! conn {:actor-id :alice :from-actor :var :room-id :elsewhere :content "a"})
                here (tasks/create-task! conn {:actor-id :alice :room-id :ops :content "b"})
                other (tasks/create-task! conn {:actor-id :alice :from-actor :huginn :room-id :elsewhere :content "c"})]
            (as-var (str "(dvergr.tasks/ignore! #uuid \"" (:id mine) "\")"))
            (as-var (str "(dvergr.tasks/accept! #uuid \"" (:id here) "\")"))
            (is (thrown-with-msg? Exception #"only its own tasks"
                                  (as-var (str "(dvergr.tasks/complete! #uuid \"" (:id other) "\" \"done\")"))))
            (is (= :pending (:status (tasks/lookup conn (:id other))))))))
      (testing "an MCP connection (the owner's) keeps its reach"
        ((sandbox-as conn {:agent-id :mcp/code}) "(dvergr.actors/add-skill! :huginn :prose)")
        (is (contains? (:skills (actors/lookup conn :huginn)) :prose))))))

(deftest update-agent-profile-is-the-acting-agents-own
  (let [written (atom [])]
    (with-redefs [dvergr.agent.persona/write-prompt! (fn [id _content] (swap! written conj id) true)]
      (let [run (fn [agent-id target]
                  (tools/execute "update_agent_profile" {:agent-name target :content "# me"}
                                 (tools/make-context {:cwd (System/getProperty "java.io.tmpdir")
                                                      :chat-ctx {:agent-id agent-id}})))]
        (is (= :success (:type (run :var "var"))))
        (is (= :error (:type (run :var "huginn"))))
        (is (= :success (:type (run :mcp/admin "huginn"))) "an MCP connection keeps its reach")
        (is (= [:var :huginn] @written))))))
