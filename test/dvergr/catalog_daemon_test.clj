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
   every fixture fact with citations, its second (once the attempt's history
   holds the tool results) ends the turn. Decided per attempt, since attempts
   run at the same time."
  [calls]
  (let [fixtures ((get-in (catalog/lookup "wiki/v1") [:benchmark :fixtures]))
        page (str "# Tessellate Instruments\n\n" (str/join "\n\n" (vals fixtures))
                  "\n\n[source](../docs/company.md) [products](../docs/products.md)")]
    (fn [messages _opts]
      (let [n (swap! calls inc)]
        (if (not-any? #(= :tool-result (:role %)) messages)
          {:content ""
           :tool-calls [{:id (str "index-" n) :name "write_file"
                         :input {:path "/wiki/index.md" :content "# Wiki\n\n- [Tessellate](tessellate.md)\n"}}
                        {:id (str "page-" n) :name "write_file"
                         :input {:path "/wiki/tessellate.md" :content page}}]
           :usage {:input-tokens 1000 :output-tokens 400}
           :stop-reason :tool-use}
          {:content "Wrote the wiki." :tool-calls nil
           :usage {:input-tokens 1200 :output-tokens 20} :stop-reason :end-turn})))))

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
            ;; Both attempts at once, on one durable room: concurrent Runs
            ;; used to kill its Datahike writer (the Scriptum publication
            ;; race, fixed in datahike 0.8.1899).
            (run-job {:workflow "wiki/v1" :attempts 2 :parallelism 2 :models ["claude-haiku-4-5"]})
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
    (dvergr.catalog.workspace/room-fs f)
    (muschel.fs/write-string! (dvergr.catalog.workspace/room-fs f) "/notes.md" "A note." false)
    (let [rev (ops/invoke *daemon* :room/review {:room fork})]
      (is (= :reviewable (:tier rev)) "uncommitted work is not trivial")
      (is (some #(str/includes? % "notes.md") (:uncommitted rev)))
      (ops/invoke *daemon* :room/merge {:room fork :expect-state (:state rev)})
      (is (= {"/notes.md" "A note."} (select-keys (catalog/read-tree (ops/resolve-room *daemon* room) "/")
                                                  ["/notes.md"]))))))

(deftest a-dirty-parent-refuses-a-merge-without-fencing-the-fork
  ;; geschichte refuses a merge into a dirty workspace; refused inside the merge
  ;; that left the fork fenced ("Room is already fenced for lifecycle work").
  (let [room (:id (ops/invoke *daemon* :room/create {:title "dirty parent" :slug "dirty-parent"}))
        fork (:id (ops/invoke *daemon* :room/fork {:room room}))
        parent (ops/resolve-room *daemon* room)]
    (muschel.fs/write-string! (dvergr.catalog.workspace/room-fs (ops/resolve-room *daemon* fork)) "/fork.md" "Fork work." false)
    (muschel.fs/write-string! (dvergr.catalog.workspace/room-fs parent) "/parent.md" "Parent work." false)
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"has uncommitted changes \(\?\? parent.md\)"
                          (ops/invoke *daemon* :room/merge {:room fork})))
    (testing "the fork stays mergeable once the parent is clean"
      (muschel.fs/delete (dvergr.catalog.workspace/room-fs parent) "/parent.md")
      (is (nil? (forks/workspace-changes parent)))
      (is (= fork (:merged (ops/invoke *daemon* :room/merge {:room fork}))))
      (is (= "Fork work." (get (catalog/read-tree parent "/") "/fork.md"))))))

(deftest a-benchmark-runs-as-an-experiment-in-a-daemon-room
  ;; No separate process: the experiment is a job in a durable daemon room,
  ;; its progress a query over that room's store.
  (let [calls (atom 0)]
    (with-redefs [providers/ensure-initialized! (constantly nil)
                  chat-agent/messages->api-format (fn [messages _ _] messages)
                  model-chat/chat (wiki-writer calls)]
      (let [started (ops/invoke *daemon* :catalog/benchmark
                                {:workflow "wiki/v1" :models ["claude-haiku-4-5"] :repetitions 2})
            done (loop [n 0]
                   (let [st (ops/invoke *daemon* :job/status {:job (:id started) :wait-ms 20000})]
                     (if (or (not= "running" (:status st)) (< 8 n)) st (recur (inc n)))))
            progress (ops/invoke *daemon* :experiment/progress {:room (:room started)})
            [cand] (get-in progress [:experiments 0 :candidates])]
        (is (= "completed" (:status done)) (pr-str (dissoc done :result)))
        (is (= 1.0 (get-in done [:result :summary 0 :reward-mean])))
        (is (= 2 (:done cand) (:verdicts cand)))
        (is (pos? (:microdollars cand)))
        (is (empty? (:running progress)) "nothing left running")))))
