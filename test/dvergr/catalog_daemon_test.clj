(ns dvergr.catalog-daemon-test
  "A catalog workflow end to end on durable daemon rooms: fixtures seeded and
   committed, attempts that write files in their worlds, scored by the checker,
   reviewed with the files they changed, one adopted by a pinned merge. Durable
   rooms matter here: a memory room has no workspace, and a fork's uncommitted
   files were silently lost on merge."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [dvergr.catalog :as catalog]
            [dvergr.chat.agent :as chat-agent]
            [dvergr.model.chat :as model-chat]
            [dvergr.model.providers :as providers]
            [dvergr.ops :as ops]
            [dvergr.orchestration.daemon :as daemon]
            [dvergr.rooms.forks :as forks]
            [dvergr.substrate.paths :as paths]
            [dvergr.system.db :as sdb]))

(def ^:dynamic *daemon* nil)

(use-fixtures :once
  (fn [f]
    (let [prev-home (paths/home)]
      (paths/set-home! (str (System/getProperty "java.io.tmpdir") "/dvergr-catalog-test-" (random-uuid)))
      (sdb/reset-conn!)
      (let [d (daemon/start! {:agents {}})]
        (try
          (binding [*daemon* d] (f))
          (finally
            (try (daemon/stop! d) (catch Exception _))
            (sdb/reset-conn!)
            (paths/set-home! prev-home)))))))

(defn- wiki-writer
  "A scripted model: its first call in each attempt writes a wiki that states
   every fixture fact with citations, its second ends the turn."
  [calls]
  (let [fixtures ((get-in (catalog/lookup "wiki/v1") [:benchmark :fixtures]))
        page (str "# Tessellate Instruments\n\n" (str/join "\n\n" (vals fixtures))
                  "\n\n[source](../docs/company.md) [products](../docs/products.md)")]
    (fn [_messages _opts]
      (if (odd? (swap! calls inc))
        {:content ""
         :tool-calls [{:id (str "index-" @calls) :name "write_file"
                       :input {:path "/wiki/index.md" :content "# Wiki\n\n- [Tessellate](tessellate.md)\n"}}
                      {:id (str "page-" @calls) :name "write_file"
                       :input {:path "/wiki/tessellate.md" :content page}}]
         :usage {:input-tokens 1000 :output-tokens 400}
         :stop-reason :tool-use}
        {:content "Wrote the wiki." :tool-calls nil
         :usage {:input-tokens 1200 :output-tokens 20} :stop-reason :end-turn}))))

(defn- run-job [args]
  (let [started (ops/invoke *daemon* :catalog/start args)]
    (loop [n 0]
      (let [st (ops/invoke *daemon* :job/status {:job (:id started) :wait-ms 20000})]
        (if (or (not= "running" (:status st)) (< 6 n))
          (assoc st :room (:room started) :mode (:mode started))
          (recur (inc n)))))))

(deftest the-wiki-benchmark-runs-scores-and-adopts-on-a-durable-room
  (let [calls (atom 0)]
    (with-redefs [providers/ensure-initialized! (constantly nil)
                  chat-agent/messages->api-format (fn [messages _ _] messages)
                  model-chat/chat (wiki-writer calls)]
      (let [{:keys [status result room mode] :as done}
            (run-job {:workflow "wiki/v1" :attempts 2 :models ["claude-haiku-4-5"]})
            [a b] (:attempts result)]
        (is (= "completed" status) (pr-str (dissoc done :result)))
        (is (= "benchmark" mode))
        (testing "the benchmark room holds the fixtures, committed"
          (let [r (ops/resolve-room *daemon* room)]
            (is (= 6 (count (catalog/read-tree r "/docs"))))
            (is (nil? (forks/workspace-changes r)) "a dirty parent refuses merges")))
        (testing "each attempt is scored by the checker against the gold facts"
          (is (= [1.0 1.0] (mapv :reward [a b])))
          (is (true? (get-in a [:checks :fact/founding])))
          (is (true? (get-in a [:checks :every-page-cited?]))))
        (testing "each world is reviewable, with the files it wrote committed"
          (doseq [x [a b]]
            (is (= :reviewable (get-in x [:review :tier])))
            (is (empty? (get-in x [:review :uncommitted])))
            (is (some #(str/includes? (pr-str %) "wiki/tessellate.md")
                      (vals (get-in x [:review :systems]))))))
        (testing "one world is adopted by a pinned merge, the other discarded"
          (is (= (:world a) (:merged (ops/invoke *daemon* :room/merge
                                                 {:room (:world a) :expect-state (get-in a [:review :state])}))))
          (is (= (:world b) (:discarded (ops/invoke *daemon* :room/discard {:room (:world b)}))))
          (let [r (ops/resolve-room *daemon* room)]
            (is (= #{"/wiki/index.md" "/wiki/tessellate.md"} (set (keys (catalog/read-tree r "/wiki")))))
            (is (nil? (forks/workspace-changes r)))))))))

(deftest a-fork-s-uncommitted-files-are-reviewed-and-adopted
  (let [room (:id (ops/invoke *daemon* :room/create {:title "fork files" :slug "fork-files"}))
        fork (:id (ops/invoke *daemon* :room/fork {:room room}))
        f (ops/resolve-room *daemon* fork)]
    ;; A raw write, as a tool leaves it: not committed.
    (#'catalog/room-fs f)
    (muschel.fs/write-string! (#'catalog/room-fs f) "/notes.md" "A note." false)
    (let [rev (ops/invoke *daemon* :room/review {:room fork})]
      (is (= :reviewable (:tier rev)) "uncommitted work is not trivial")
      (is (some #(str/includes? % "notes.md") (:uncommitted rev)))
      (ops/invoke *daemon* :room/merge {:room fork :expect-state (:state rev)})
      (is (= {"/notes.md" "A note."} (select-keys (catalog/read-tree (ops/resolve-room *daemon* room) "/")
                                                  ["/notes.md"]))))))
