(ns dvergr.catalog.room-test
  "A workflow defined as files (doc/room-workflows.md): checked for shape, its
   checker run in SCI with no effects, and benchmarked like a catalog
   workflow, the checker's verdict on every Attempt with its `:ad-hoc` tier."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dvergr.agent.evaluation :as evaluation]
            [dvergr.catalog.room :as room-wf]
            [dvergr.catalog.workspace :as ws]
            [dvergr.discourse :as d]
            [dvergr.effects :as effects]
            [dvergr.room.store.memory :as memory]
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

(def calibration
  {:reference {"/out/competitors.md" "Wato\nPromptQL\nDust\nBuzz"}
   :damaged {:one-missing {:files {"/out/competitors.md" "Wato\nPromptQL\nDust"} :loses [:found-all?]}
             :empty {:files {} :loses [:wrote-list? :found-all?]}}})

(deftest a-checker-earns-trust-by-calibration
  (let [b (room-wf/bundle "competitors" (assoc files "calibration.edn" (pr-str calibration)))]
    (testing "the reference passes and scores highest; every damage is noticed"
      (let [c (room-wf/calibrate b)]
        (is (:ok? c) (pr-str (:problems c)))
        (is (= 1.0 (get-in c [:reference :reward])))
        (is (every? :ok? (vals (:damaged c))))))
    (testing "a checker that misses a damage does not calibrate"
      (let [lenient (room-wf/bundle "competitors"
                                    (assoc files
                                           "calibration.edn" (pr-str calibration)
                                           "checker.clj" "(ns c) (defn check [_] {:checks {:wrote-list? true :found-all? true} :reward 1.0})"))
            c (room-wf/calibrate lenient)]
        (is (not (:ok? c)))
        (is (some #(re-find #"one-missing still passes" %) (:problems c)))))
    (testing "without a calibration there is nothing to earn trust with"
      (is (not (:ok? (room-wf/calibrate (room-wf/bundle "competitors" files))))))
    (testing "promotion: calibrated now, recorded on the host, the verifier trusted as :room"
      (let [prev (paths/home)]
        (try
          (paths/set-home! (str (System/getProperty "java.io.tmpdir") "/dvergr-promote-" (random-uuid)))
          (is (= :ad-hoc (room-wf/tier b)))
          (is (:promoted? (room-wf/promote! b "marketing")))
          (is (= :room (room-wf/tier b)))
          (is (= :room (evaluation/evaluator-tier (room-wf/evaluator b {}))))
          (testing "a changed bundle is new and not promoted"
            (is (= :ad-hoc (room-wf/tier (room-wf/bundle "competitors"
                                                         (assoc files "calibration.edn" (pr-str calibration)
                                                                "gold.edn" (pr-str {:competitors ["Wato"]})))))))
          (testing "one that fails calibration is not promoted"
            (is (false? (:promoted? (room-wf/promote! (room-wf/bundle "competitors" files) "marketing")))))
          (finally (paths/set-home! prev)))))))

(deftest a-bundle-travels-as-files-and-a-manifest
  (let [room (d/make-room {:id :room-workflow-export :store (memory/make)})
        other (d/make-room {:id :room-workflow-import :store (memory/make)})
        fs (assoc files "calibration.edn" (pr-str calibration))]
    (ws/ensure-workspace! room)
    (ws/seed! room (into {} (map (fn [[p t]] [(str "/workflows/competitors/" p) t])) fs))
    (let [{:keys [manifest] :as export} (room-wf/export room "competitors")]
      (testing "the manifest names the bundle and its calibration"
        (is (= "dvergr-workflow/1" (:format manifest)))
        (is (= (str (:id (room-wf/bundle "competitors" fs))) (:bundle manifest)))
        (is (true? (get-in manifest [:calibration :ok?]))))
      (testing "imported elsewhere, it is the same bundle"
        (let [b (room-wf/import! other export "rivals")]
          (is (= (:bundle manifest) (str (:id b))))
          (is (= ["rivals"] (room-wf/list-bundles other)))
          (is (= (:id b) (:id (room-wf/read-bundle other "rivals"))))))
      (testing "files changed after export are refused"
        (is (thrown-with-msg? Exception #"changed after export"
                              (room-wf/import! other (assoc-in export [:files "gold.edn"] "{:competitors []}")))))
      (testing "and it runs from a directory"
        (let [dir (io/file (System/getProperty "java.io.tmpdir") (str "bundle-" (random-uuid)) "competitors")]
          (doseq [[p t] (:files export)]
            (io/make-parents (io/file dir p))
            (spit (io/file dir p) t))
          (is (= (:bundle manifest) (str (:id (room-wf/read-dir dir)))))
          (doseq [f (reverse (file-seq (.getParentFile dir)))] (.delete f)))))))

(deftest a-checker-can-be-given-the-pages-the-attempt-fetched
  (let [fs {"workflow.edn" (pr-str {:title "Quotes" :task "Quote a page." :capture ["/out"] :fetched true})
            "checker.clj" "(ns q (:require [clojure.string :as str]))
                           (defn check [{:keys [files fetched]}]
                             (let [quote (get files \"/out/quote.md\" \"\")
                                   ok (boolean (some #(str/includes? % quote) (vals fetched)))]
                               {:checks {:quote-fetched? ok} :reward (if ok 1.0 0.0)}))"
            "fixtures/README.md" "Quote a page."}
        b (room-wf/bundle "quotes" fs)
        room (d/make-room {:id :room-workflow-fetched :store (memory/make)})]
    (testing "the environment records the attempt's effects"
      (let [plan (room-wf/experiment-plan b {:models ["claude-haiku-4-5"]})]
        (is (= {:record true} (get-in plan [:environments 0 :environment/world :effects])))))
    (ws/ensure-workspace! room)
    (ws/seed! room {"/out/quote.md" "the page says hello"})
    (let [r (effects/recording!)
          boundary (constantly {:handlers (effects/handlers [[:record {:id r}]])})
          get! #(effects/perform! boundary {:effect :http/request :resource {:method :get :url %1}}
                                  (constantly {:status %2 :headers {} :body %3}))]
      (get! "https://example.test/a" 200 "<p>the page says hello</p>")
      (get! "https://example.test/missing" 404 "not found")
      (effects/set-world-recording! (:ctx room) r)
      (let [ev (room-wf/evaluator b {})
            captured ((:capture ev) {:world/room room})]
        (is (= {"https://example.test/a" "<p>the page says hello</p>"} (:fetched captured))
            "successful GETs only")
        (is (= 1.0 (:reward ((:verify ev) nil {:run-status :completed :files (:files captured)
                                               :fetched (:fetched captured)}))))
        (is (= 0.0 (:reward ((:verify ev) nil {:run-status :completed :files (:files captured) :fetched {}})))
            "a quote from a page not fetched does not count"))
      (effects/release! r))))
