(ns dvergr.sandbox.hardening-test
  "Holes the 2026-09-27 effect inventory found (doc/effects.md, Hardening
   first), each pinned closed with the capability it was closed without
   breaking: an XML parse that read the network or disk past every guard, a
   JVM-global tap side channel, file tools unclamped on the host disk, model
   calls from code that skipped the budget, and an agent extending its own
   budget."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
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
