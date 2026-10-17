(ns dvergr.catalog-daemon-test
  "A catalog workflow end to end on durable daemon rooms: fixtures seeded and
   committed, attempts that write files in their worlds, scored by the checker,
   reviewed with the files they changed, one adopted by a pinned merge. Durable
   rooms matter here: a memory room has no workspace, and a fork's uncommitted
   files were silently lost on merge."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [dvergr.agent.experiment.runner :as runner]
            [dvergr.agent.review :as review]
            [dvergr.agent.run :as run]
            [dvergr.catalog.room :as room-wf]
            [dvergr.discourse :as d]
            [org.replikativ.spindel.engine.core :as ec]
            [dvergr.catalog :as catalog]
            [dvergr.catalog.wiki-gen :as gen]
            [dvergr.catalog.wiki-v4 :as v4]
            [dvergr.chat.agent :as chat-agent]
            [dvergr.model.chat :as model-chat]
            [dvergr.model.providers :as providers]
            [dvergr.jobs :as jobs]
            [dvergr.ops :as ops]
            [dvergr.orchestration.daemon :as daemon]
            [dvergr.rooms.forks :as forks]
            [dvergr.resource :as resource]
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
        ;; Stored messages carry `:message/role`, API-shaped ones `:role`.
        (if (not-any? #(= :tool-result (or (:role %) (:message/role %))) messages)
          {:content ""
           :tool-calls [{:id (str "index-" n) :name "write_file"
                         :input {:path "/wiki/index.md" :content "# Wiki\n\n- [Tessellate](tessellate.md)\n"}}
                        {:id (str "page-" n) :name "write_file"
                         :input {:path "/wiki/tessellate.md" :content page}}]
           :usage {:input-tokens 1000 :output-tokens 400}
           :stop-reason :tool-use}
          {:content "Wrote the wiki." :tool-calls nil
           :usage {:input-tokens 1200 :output-tokens 20} :stop-reason :end-turn})))))

(defn- run-job
  ([args] (run-job args dvergr.principal/local-principal))
  ([args principal]
   (let [started (ops/invoke *daemon* principal :catalog/start args)]
     (loop [n 0]
       (let [st (ops/invoke *daemon* dvergr.principal/local-principal :job/status {:job (:id started) :wait-ms 20000})]
         (if (or (not= "running" (:status st)) (< 6 n))
           (assoc st :room (:room started) :mode (:mode started))
           (recur (inc n))))))))

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
          (is (= (:world a) (:merged (ops/invoke *daemon* dvergr.principal/local-principal :room/merge
                                                 {:room (:world a) :expect-state (get-in a [:review :state])}))))
          (is (= (:world b) (:discarded (ops/invoke *daemon* dvergr.principal/local-principal :room/discard {:room (:world b)}))))
          (let [r (ops/resolve-room *daemon* room)]
            (is (= #{"/wiki/index.md" "/wiki/tessellate.md"} (set (keys (catalog/read-tree r "/wiki")))))
            (is (nil? (forks/workspace-changes r)))))))))

(deftest a-fork-s-uncommitted-files-are-reviewed-and-adopted
  (let [room (:id (ops/invoke *daemon* dvergr.principal/local-principal :room/create {:title "fork files" :slug "fork-files"}))
        fork (:id (ops/invoke *daemon* dvergr.principal/local-principal :room/fork {:room room}))
        f (ops/resolve-room *daemon* fork)]
    ;; A raw write, as a tool leaves it: not committed.
    (dvergr.catalog.workspace/room-fs f)
    (muschel.fs/write-string! (dvergr.catalog.workspace/room-fs f) "/notes.md" "A note." false)
    (let [rev (ops/invoke *daemon* dvergr.principal/local-principal :room/review {:room fork})]
      (is (= :reviewable (:tier rev)) "uncommitted work is not trivial")
      (is (some #(str/includes? % "notes.md") (:uncommitted rev)))
      (ops/invoke *daemon* dvergr.principal/local-principal :room/merge {:room fork :expect-state (:state rev)})
      (is (= {"/notes.md" "A note."} (select-keys (catalog/read-tree (ops/resolve-room *daemon* room) "/")
                                                  ["/notes.md"]))))))

(deftest a-fork-that-merged-a-nested-fork-lands-everything
  ;; spindel#136: merging B into A replaced A's overlays with plain systems, and
  ;; A's merge into the room then landed nothing (A's own writes included)
  (let [op #(ops/invoke *daemon* dvergr.principal/local-principal %1 %2)
        room (:id (op :room/create {:title "nested merge" :slug "nested-merge"}))
        a (:id (op :room/fork {:room room}))
        _ (op :room/write {:room a :files {"a.md" {:text "from A"}}})
        b (:id (op :room/fork {:room a}))
        _ (op :room/write {:room b :files {"b.md" {:text "from B"}}})]
    (op :room/merge {:room b})
    (op :room/merge {:room a})
    (is (= {"/a.md" "from A" "/b.md" "from B"}
           (select-keys (catalog/read-tree (ops/resolve-room *daemon* room) "/") ["/a.md" "/b.md"])))))

(deftest a-dirty-parent-refuses-a-merge-without-fencing-the-fork
  ;; geschichte refuses a merge into a dirty workspace; refused inside the merge
  ;; that left the fork fenced ("Room is already fenced for lifecycle work").
  (let [room (:id (ops/invoke *daemon* dvergr.principal/local-principal :room/create {:title "dirty parent" :slug "dirty-parent"}))
        fork (:id (ops/invoke *daemon* dvergr.principal/local-principal :room/fork {:room room}))
        parent (ops/resolve-room *daemon* room)]
    (muschel.fs/write-string! (dvergr.catalog.workspace/room-fs (ops/resolve-room *daemon* fork)) "/fork.md" "Fork work." false)
    (muschel.fs/write-string! (dvergr.catalog.workspace/room-fs parent) "/parent.md" "Parent work." false)
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"has uncommitted changes \(\?\? parent.md\)"
                          (ops/invoke *daemon* dvergr.principal/local-principal :room/merge {:room fork})))
    (testing "the fork stays mergeable once the parent is clean"
      (muschel.fs/delete (dvergr.catalog.workspace/room-fs parent) "/parent.md")
      (is (nil? (forks/workspace-changes parent)))
      (is (= fork (:merged (ops/invoke *daemon* dvergr.principal/local-principal :room/merge {:room fork}))))
      (is (= "Fork work." (get (catalog/read-tree parent "/") "/fork.md"))))))

(deftest a-benchmark-runs-as-an-experiment-in-a-daemon-room
  ;; No separate process: the experiment is a job in a durable daemon room,
  ;; its progress a query over that room's store.
  (let [calls (atom 0)]
    (with-redefs [providers/ensure-initialized! (constantly nil)
                  chat-agent/messages->api-format (fn [messages _ _] messages)
                  model-chat/chat (wiki-writer calls)]
      (let [started (ops/invoke *daemon* dvergr.principal/local-principal :catalog/benchmark
                                {:workflow "wiki/v1" :models ["claude-haiku-4-5"] :repetitions 2})
            done (loop [n 0]
                   (let [st (ops/invoke *daemon* dvergr.principal/local-principal :job/status {:job (:id started) :wait-ms 20000})]
                     (if (or (not= "running" (:status st)) (< 8 n)) st (recur (inc n)))))
            progress (ops/invoke *daemon* dvergr.principal/local-principal :experiment/progress {:room (:room started)})
            [cand] (get-in progress [:experiments 0 :candidates])]
        (is (= "completed" (:status done)) (pr-str (dissoc done :result)))
        (is (= 1.0 (get-in done [:result :summary 0 :reward-mean])))
        (is (= 2 (:done cand) (:verdicts cand)))
        (is (pos? (:microdollars cand)))
        (is (empty? (:running progress)) "nothing left running")))))

(deftest a-benchmark-can-report-into-a-room-of-your-own
  ;; The fixture room is still new and clean (every cell forks it); the job,
  ;; its Attempts and the Scorecard are kept where the caller looks.
  (let [calls (atom 0)
        bench! (fn [room]
                 (let [started (ops/invoke *daemon* dvergr.principal/local-principal :catalog/benchmark
                                           {:workflow "wiki/v1" :models ["claude-haiku-4-5"] :room room})]
                   [started (loop [n 0]
                              (let [st (ops/invoke *daemon* dvergr.principal/local-principal :job/status {:job (:id started) :wait-ms 20000})]
                                (if (or (not= "running" (:status st)) (< 8 n)) st (recur (inc n)))))]))]
    (ops/invoke *daemon* dvergr.principal/local-principal :room/create {:slug "my-benchmarks"})
    (with-redefs [providers/ensure-initialized! (constantly nil)
                  chat-agent/messages->api-format (fn [messages _ _] messages)
                  model-chat/chat (wiki-writer calls)]
      (let [[started done] (bench! "my-benchmarks")
            [_ again] (bench! "my-benchmarks")
            mine (:experiments (ops/invoke *daemon* dvergr.principal/local-principal :experiment/progress {:room "my-benchmarks"}))]
        (is (= "my-benchmarks" (:room started)))
        (is (not= (:room started) (:fixture-room started)))
        (is (= "completed" (:status done) (:status again)) (pr-str (dissoc done :result)))
        (is (= 1.0 (get-in done [:result :summary 0 :reward-mean])))
        (is (= 2 (count mine)) "both experiments are the room's")
        (is (= [1 1] (map #(get-in % [:candidates 0 :done]) mine)))
        (is (loop [n 0]
              (cond (and (nil? (ops/resolve-room *daemon* (:fixture-room started)))
                         (:room/archived-at (sdb/room-by-slug (:fixture-room started)))) true
                    (< 50 n) false
                    :else (do (Thread/sleep 100) (recur (inc n)))))
            "the fixture room is archived once the job is over")
        (let [row (sdb/room-by-slug (:fixture-room started))]
          (is (some? row) "its registry row is kept")
          (is (not-any? #(= (:room/id row) (:room/id %)) (sdb/all-rooms))
              "and it is not hydrated again"))))))

(deftest an-llm-experiment-can-fork-a-fork-of-the-room-keeping-its-records
  ;; The shape of a sub-experiment started inside a Run: the worlds fork the
  ;; Run's world (a fork of the room), the records stay in the room. The two
  ;; contexts differ, so interpreter state (the activity counter) and the
  ;; evaluation's completion must stay in the context that owns them.
  (let [calls (atom 0)
        wf (catalog/lookup "wiki/v1")
        plan ((:experiment-plan wf) {:models ["claude-haiku-4-5"]})
        exp (runner/experiment-def (assoc plan :id :test/fork-of-room :repetitions 1))
        _ (ops/invoke *daemon* dvergr.principal/local-principal :room/create {:slug "fork-parent"})
        room (ops/resolve-room *daemon* "fork-parent")
        world (forks/fork! room)]
    (try
      (with-redefs [providers/ensure-initialized! (constantly nil)
                    chat-agent/messages->api-format (fn [messages _ _] messages)
                    model-chat/chat (wiki-writer calls)]
        (let [job (jobs/start! room {:kind :experiment :ctx (:ctx world)}
                               #(runner/run-in room (assoc plan :experiment exp :parent-run %
                                                           :world-parent world)))
              done (loop [n 0]
                     (let [st (ops/invoke *daemon* dvergr.principal/local-principal :job/status {:job (str (:id job)) :wait-ms 20000})]
                       (if (or (not= "running" (:status st)) (< 8 n)) st (recur (inc n)))))
              [cand] (get-in (ops/invoke *daemon* dvergr.principal/local-principal :experiment/progress {:room "fork-parent"})
                             [:experiments 0 :candidates])]
          (is (= "completed" (:status done)) (pr-str (dissoc done :result)))
          (is (= 1 (:done cand) (:verdicts cand)) "the cell is certified in the room")
          (is (= 1.0 (:reward-mean cand)) "the wiki was written: the model ran past its first tool round")))
      (finally (forks/discard! world)))))

(defn- reference-writer
  "A scripted model that writes a perfect wiki of whichever generated world it
   is in: it reads the blog, finds the seed whose world wrote it, and writes
   that world's reference wiki. Decided per attempt, from the attempt's own
   history. `wiki-of` is the reference of a world (v3's by default)."
  ([calls] (reference-writer calls gen/reference-wiki))
  ([calls wiki-of]
   (fn [messages _opts]
     (swap! calls inc)
    ;; The stub sees stored messages (`:message/role`, …) or API-shaped ones.
     (let [role #(or (:role %) (:message/role %))
           content #(str (or (:content %) (:message/content %)))
           use-id #(str (or (:tool-use-id %) (:message/tool-use-id %)))
           results (filter #(= :tool-result (role %)) messages)
           blog (some (fn [m] (let [c (content m)] (when (str/includes? c "A short history of") c))) results)]
       (cond
         (empty? results)
         {:content "" :tool-calls [{:id "blog" :name "read_file" :input {:path "/docs/blog-history.md"}}]
          :usage {:input-tokens 500 :output-tokens 20} :stop-reason :tool-use}

         (and blog (not-any? #(str/starts-with? (use-id %) "page-") results))
         (let [w (some #(let [w (gen/world %)]
                          (when (str/includes? blog (str (:place w) " got its cooperative in " (:founded-wrong w))) w))
                       (range 1 50))]
           {:content ""
            :tool-calls (vec (map-indexed (fn [i [path text]]
                                            {:id (str "page-" i) :name "write_file" :input {:path path :content text}})
                                          (sort (wiki-of w))))
            :usage {:input-tokens 2000 :output-tokens 3000} :stop-reason :tool-use})

         :else
         {:content "Wrote the wiki." :tool-calls nil
          :usage {:input-tokens 2500 :output-tokens 20} :stop-reason :end-turn})))))

(deftest a-generated-world-benchmark-scores-a-perfect-wiki-top
  ;; wiki/v3 end to end: each environment's world is generated from its seed
  ;; into the attempt's world, and scored against the gold of that seed.
  (let [calls (atom 0)]
    (with-redefs [providers/ensure-initialized! (constantly nil)
                  chat-agent/messages->api-format (fn [messages _ _] messages)
                  model-chat/chat (reference-writer calls)]
      (let [started (ops/invoke *daemon* dvergr.principal/local-principal :catalog/benchmark
                                {:workflow "wiki/v3" :models ["claude-haiku-4-5"] :environments 2})
            done (loop [n 0]
                   (let [st (ops/invoke *daemon* dvergr.principal/local-principal :job/status {:job (:id started) :wait-ms 20000})]
                     (if (or (not= "running" (:status st)) (< 8 n)) st (recur (inc n)))))
            [cand] (get-in (ops/invoke *daemon* dvergr.principal/local-principal :experiment/progress {:room (:room started)})
                           [:experiments 0 :candidates])]
        (is (= "completed" (:status done)) (pr-str (dissoc done :result)))
        (is (= 2 (:done cand) (:verdicts cand)) "one cell per generated world")
        (is (= 1.0 (:reward-mean cand)) "a perfect wiki of each world scores top against that world's gold")
        (is (= 1.0 (get-in done [:result :summary 0 :reward-mean])))))))

(deftest a-v4-benchmark-scores-a-perfect-two-tier-wiki-top
  ;; wiki/v4 end to end on v3's worlds: atoms, overviews, quoted claims
  (let [calls (atom 0)]
    (with-redefs [providers/ensure-initialized! (constantly nil)
                  chat-agent/messages->api-format (fn [messages _ _] messages)
                  model-chat/chat (reference-writer calls v4/reference-wiki)]
      (let [started (ops/invoke *daemon* dvergr.principal/local-principal :catalog/benchmark
                                {:workflow "wiki/v4" :models ["claude-haiku-4-5"] :environments 2})
            done (loop [n 0]
                   (let [st (ops/invoke *daemon* dvergr.principal/local-principal :job/status {:job (:id started) :wait-ms 20000})]
                     (if (or (not= "running" (:status st)) (< 8 n)) st (recur (inc n)))))
            [cand] (get-in (ops/invoke *daemon* dvergr.principal/local-principal :experiment/progress {:room (:room started)})
                           [:experiments 0 :candidates])]
        (is (= "completed" (:status done)) (pr-str (dissoc done :result)))
        (is (= 2 (:done cand) (:verdicts cand)) "one cell per generated world")
        (is (= 1.0 (:reward-mean cand)) "the v4 reference of each world scores top against that world's gold")))))

(deftest catalog-start-runs-wiki-v4-on-its-benchmark-world
  ;; catalog_start's benchmark mode: world 1, the fixed-gold evaluator, whose
  ;; ref must match the environment the workflow builds
  (let [calls (atom 0)]
    (with-redefs [providers/ensure-initialized! (constantly nil)
                  chat-agent/messages->api-format (fn [messages _ _] messages)
                  model-chat/chat (reference-writer calls v4/reference-wiki)]
      (let [{:keys [status result mode] :as done} (run-job {:workflow "wiki/v4" :attempts 1 :models ["claude-haiku-4-5"]})
            [a] (:attempts result)]
        (is (= "completed" status) (pr-str (dissoc done :result)))
        (is (= "benchmark" mode))
        (is (= 1.0 (:reward a)) (pr-str (:checks a)))
        (is (true? (get-in a [:checks :every-claim-quoted?])))))))

(deftest a-checkout-becomes-a-room-and-its-changes-a-patch
  ;; A daemon room's repository starts as a clone of the sandbox stdlib; an
  ;; imported room's starts as a clone of the client's checkout instead.
  (let [src (io/file (System/getProperty "java.io.tmpdir") (str "dvergr-import-" (random-uuid)))
        git! (fn [& args] (let [{:keys [exit err]} (apply sh/sh "git" (concat args [:dir src]))]
                            (when-not (zero? exit) (throw (ex-info err {})))))]
    (.mkdirs (io/file src "src"))
    (try
      (git! "init" "-q" "-b" "main")
      (git! "config" "user.email" "t@example.invalid")
      (git! "config" "user.name" "T")
      (spit (io/file src "src" "core.clj") "(ns core)\n\n(defn answer [] 41)\n")
      (git! "add" ".")
      (git! "commit" "-q" "-m" "Initial")
      (let [imp (ops/invoke *daemon* dvergr.principal/local-principal :room/import {:room "imported-repo" :source (.getPath src)})
            r (ops/resolve-room *daemon* "imported-repo")]
        (is (= 1 (:files imp)) "the checkout's files, and not the stdlib's")
        (is (= {"/src/core.clj" "(ns core)\n\n(defn answer [] 41)\n"} (catalog/read-tree r "/src")))
        (is (thrown-with-msg? Exception #"exists"
                              (ops/invoke *daemon* dvergr.principal/local-principal :room/import {:room "imported-repo" :source (.getPath src)})))
        (muschel.fs/write-string! (dvergr.catalog.workspace/room-fs r) "/src/core.clj"
                                  "(ns core)\n\n(defn answer [] 42)\n" false)
        (let [{:keys [patch files]} (ops/invoke *daemon* dvergr.principal/local-principal :room/export {:room "imported-repo"})]
          (is (= [{:status "M" :path "src/core.clj"}] files))
          (spit (io/file src "room.patch") patch)
          (git! "apply" "room.patch")
          (is (= "(ns core)\n\n(defn answer [] 42)\n" (slurp (io/file src "src" "core.clj"))))))
      (finally (sh/sh "rm" "-rf" (.getPath src))))))

(deftest a-workflow-defined-in-a-room-is-checked-listed-and-benchmarked
  (let [room (:id (ops/invoke *daemon* dvergr.principal/local-principal :room/create {:title "Marketing" :slug "marketing"}))
        r (ops/resolve-room *daemon* room)
        bundle {"/workflows/competitors/workflow.edn"
                (pr-str {:title "Competitors" :task "Write the competitors of {product} to /out/list.md."
                         :params {:product "Simmis"} :capture ["/out"]})
                "/workflows/competitors/checker.clj"
                "(ns c (:require [clojure.string :as str]))
                 (defn check [{:keys [files gold]}]
                   (let [t (get files \"/out/list.md\" \"\")
                         n (count (filter #(str/includes? t %) (:names gold)))]
                     {:checks {:all? (= n (count (:names gold)))} :reward (double (/ n (count (:names gold))))}))"
                "/workflows/competitors/gold.edn" (pr-str {:names ["Wato" "Dust"]})
                "/workflows/competitors/calibration.edn"
                (pr-str {:reference {"/out/list.md" "Wato, Dust"}
                         :damaged {:half {:files {"/out/list.md" "Wato"} :loses [:all?]}}})
                "/workflows/competitors/fixtures/docs/brief.md" "Simmis vs Wato and Dust."}]
    (catalog/seed! r bundle)
    (testing "catalog_check reports the bundle and runs its checker on an answer"
      (let [c (ops/invoke *daemon* dvergr.principal/local-principal :catalog/check {:room "marketing" :name "competitors"
                                                                                    :answer {"/out/list.md" "Wato"}})]
        (is (:ok c) (pr-str c))
        (is (= ["/docs/brief.md"] (:fixtures c)))
        (is (= 0.5 (get-in c [:verdict :reward])))))
    (testing "a broken bundle says why"
      (catalog/seed! r {"/workflows/broken/workflow.edn" "{:title \"x\"}"})
      (is (false? (:ok (ops/invoke *daemon* dvergr.principal/local-principal :catalog/check {:room "marketing" :name "broken"})))))
    (testing "catalog_list with the room lists it, ad hoc until promoted"
      (is (= "ad-hoc" (:verifier (first (filter #(= "marketing/competitors" (:id %))
                                                (ops/invoke *daemon* dvergr.principal/local-principal :catalog/list {:room "marketing"})))))))
    (testing "calibrated, then promoted by the owner's connection"
      (is (:ok? (ops/invoke *daemon* dvergr.principal/local-principal :catalog/calibrate {:room "marketing" :name "competitors"})))
      (is (:promoted? (ops/invoke *daemon* dvergr.principal/local-principal :catalog/promote {:room "marketing" :name "competitors"})))
      (is (= "room" (:verifier (first (filter #(= "marketing/competitors" (:id %))
                                              (ops/invoke *daemon* dvergr.principal/local-principal :catalog/list {:room "marketing"})))))))
    (testing "catalog_benchmark's `name`, and `experiment` as its deprecated alias"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"lowercase letters"
                            (ops/invoke *daemon* dvergr.principal/local-principal :catalog/benchmark
                                        {:workflow "marketing/competitors" :models ["claude-haiku-4-5"]
                                         :name "Not A Name"})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"lowercase letters"
                            (ops/invoke *daemon* dvergr.principal/local-principal :catalog/benchmark
                                        {:workflow "marketing/competitors" :models ["claude-haiku-4-5"]
                                         :experiment "Not A Name"}))
          "the alias is still read")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"deprecated alias"
                            (ops/invoke *daemon* dvergr.principal/local-principal :catalog/benchmark
                                        {:workflow "marketing/competitors" :models ["claude-haiku-4-5"]
                                         :name "one" :experiment "two"}))))
    (testing "catalog_benchmark runs it as \"<room>/<name>\""
      (let [calls (atom 0)]
        (with-redefs [providers/ensure-initialized! (constantly nil)
                      chat-agent/messages->api-format (fn [messages _ _] messages)
                      model-chat/chat (fn [messages _]
                                        (swap! calls inc)
                                        (if (not-any? #(= :tool-result (or (:role %) (:message/role %))) messages)
                                          {:content "" :tool-calls [{:id (str "w" @calls) :name "write_file"
                                                                     :input {:path "/out/list.md" :content "Wato, Dust"}}]
                                           :usage {:input-tokens 500 :output-tokens 20} :stop-reason :tool-use}
                                          {:content "Done." :tool-calls nil
                                           :usage {:input-tokens 600 :output-tokens 5} :stop-reason :end-turn}))]
          (let [started (ops/invoke *daemon* dvergr.principal/local-principal :catalog/benchmark
                                    {:workflow "marketing/competitors" :models ["claude-haiku-4-5"]})
                done (loop [n 0]
                       (let [st (ops/invoke *daemon* dvergr.principal/local-principal :job/status {:job (:id started) :wait-ms 20000})]
                         (if (or (not= "running" (:status st)) (< 8 n)) st (recur (inc n)))))]
            (is (= "completed" (:status done)) (pr-str (dissoc done :result)))
            (is (= 1.0 (get-in done [:result :summary 0 :reward-mean])))
            (testing "exported, imported into another room, deployed there on a schedule"
              (ops/invoke *daemon* dvergr.principal/local-principal :room/create {:title "Sales" :slug "sales"})
              (let [export (ops/invoke *daemon* dvergr.principal/local-principal :catalog/export {:room "marketing" :name "competitors"})
                    imported (ops/invoke *daemon* dvergr.principal/local-principal :catalog/import {:room "sales" :export export :as "rivals"})]
                (is (= "sales/rivals" (:imported imported)))
                (is (= (get-in export [:manifest :bundle]) (:bundle imported)))
                (is (= "room" (:verifier imported)) "same content, same host: already promoted")
                (let [d (ops/invoke *daemon* dvergr.principal/local-principal :catalog/deploy {:room "sales" :name "rivals" :agent "var"
                                                                                               :every "week" :on "monday" :at "09:00"})
                      rows ((requiring-resolve 'dvergr.scheduler.core/list-schedules)
                            (ops/resolve-room *daemon* "sales"))]
                  (is (:schedule d))
                  (is (some #(clojure.string/includes? (str (:task %) (:schedule/task %)) "Simmis") rows)
                      (pr-str rows)))))
            (testing "its Attempts export as trajectories: turns, tool calls, effects, verdict"
              (let [[t] (ops/invoke *daemon* dvergr.principal/local-principal :attempt/export {:room (:room started) :limit 1})]
                (is (= 1.0 (:reward t)))
                (is (= "room" (:verifier-trust t)))
                (is (some #(= "tool-result" (:role %)) (:messages t)) (pr-str (:messages t)))
                (is (some #(= "fs/write" (:effect %)) (:effects t)) (pr-str (:effects t))))
              (let [{:keys [file count]} (ops/invoke *daemon* dvergr.principal/local-principal :attempt/export {:room (:room started) :file "competitors.jsonl"})]
                (is (pos? count))
                (is (= count (clojure.core/count (clojure.string/split-lines (slurp file)))))))
            (testing "and its Scorecard says who vouches for the rewards"
              (let [[sc] (ops/invoke *daemon* dvergr.principal/local-principal :scorecard/list {:room (:room started)})]
                (is (= ["room"] (:verifier-trust (ops/invoke *daemon* dvergr.principal/local-principal :scorecard/detail
                                                             {:room (:room started) :id (:id sc)}))))))))))))

(deftest a-table-of-cases-becomes-a-certified-workflow
  (let [room (:id (ops/invoke *daemon* dvergr.principal/local-principal :room/create {:title "Books" :slug "books"}))
        r (ops/resolve-room *daemon* room)]
    (catalog/seed! r {"/data/cases.csv" (str "no;vendor;text;doc;account;amount\n"
                                             "1;Acme;toner;inv1.txt;4930;119,00\n"
                                             "2;Beta;rent;inv2.txt;4210;1.500,00\n"
                                             "3;Gamma;fuel;inv3.txt;;50\n")
                      "/data/inv1.txt" "Invoice 1: toner 119,00 EUR"
                      "/data/inv2.txt" "Invoice 2: rent 1.500,00 EUR"
                      "/data/inv3.txt" "Invoice 3: fuel"})
    (let [res (ops/invoke *daemon* dvergr.principal/local-principal :catalog/cases
                          {:room "books" :name "invoice-coding" :table "/data/cases.csv"
                           :spec {:id "no" :inputs ["vendor" "text"] :attachments ["doc"]
                                  :expected {"account" {"rule" "exact"} "amount" {:rule "number" :tolerance 0.01}}}})]
      (is (= "books/invoice-coding" (:workflow res)))
      (is (= [3 2] [(:cases res) (:certified res)]))
      (is (= {:certified 2 :unlabelled 1} (:by-reason res)) "case 3 has no account")
      (is (:ok (ops/invoke *daemon* dvergr.principal/local-principal :catalog/check {:room "books" :name "invoice-coding"})))
      (is (:ok? (ops/invoke *daemon* dvergr.principal/local-principal :catalog/calibrate {:room "books" :name "invoice-coding"}))))))

(deftest a-case-pack-is-built-from-scratch-over-the-room-file-ops
  ;; What an MCP client does with no checkout of its own: the table goes in
  ;; with room_write, the bundle comes back out with room_files/room_read.
  (ops/invoke *daemon* dvergr.principal/local-principal :room/create {:title "Ledger" :slug "ledger"})
  (let [w (ops/invoke *daemon* dvergr.principal/local-principal :room/write
                      {:room "ledger" :message "Cases from the 2025 books"
                       :files {"data/cases.csv" {"text" (str "no;vendor;text;doc;account;amount\n"
                                                             "1;Acme;toner;inv1.txt;4930;119,00\n"
                                                             "2;Acme;toner refill;inv2.txt;4930;119,00\n")}
                               "data/inv1.txt" {"text" "Invoice 1: toner 119,00 EUR"}
                               "data/inv2.txt" {"text" "Invoice 2: toner refill 119,00 EUR"}
                               "data/scan.pdf" {"base64" "JVBERi0xLjQKJcfsj6IK"}}})]
    (is (string? (:commit w)) (pr-str w))
    (is (= 4 (count (:written w))))
    (let [res (ops/invoke *daemon* dvergr.principal/local-principal :catalog/cases
                          {:room "ledger" :name "coding" :table "/data/cases.csv"
                           :spec {:id "no" :inputs ["vendor" "text"] :attachments ["doc"]
                                  :expected {"account" {"rule" "exact"} "amount" {"rule" "number" "tolerance" 0.01}}}})]
      (is (= "ledger/coding" (:workflow res)) (pr-str res))
      (is (= 2 (:certified res))))
    (testing "the bundle is in the workspace: listed and read back"
      (let [names (set (map :name (:entries (ops/invoke *daemon* dvergr.principal/local-principal :room/files {:room "ledger" :path "/workflows/coding"}))))]
        (is (every? names ["workflow.edn" "checker.clj" "calibration.edn" "certification.edn" "cases"]) (pr-str names)))
      (is (str/includes? (:text (ops/invoke *daemon* dvergr.principal/local-principal :room/read {:room "ledger" :path "/workflows/coding/workflow.edn"}))
                         ":task"))
      (is (= "application/pdf" (:media-type (ops/invoke *daemon* dvergr.principal/local-principal :room/read {:room "ledger" :path "data/scan.pdf"}))))
      (testing "and as MCP resources"
        (let [read-resource @(requiring-resolve 'dvergr.mcp.server/read-resource)
              [c] (:contents (read-resource dvergr.principal/local-principal "room://ledger/files/data/cases.csv"))
              [pdf] (:contents (read-resource dvergr.principal/local-principal "room://ledger/files/data/scan.pdf"))
              [dir] (:contents (read-resource dvergr.principal/local-principal "room://ledger/files/workflows/coding"))]
          (is (str/starts-with? (:text c) "no;vendor"))
          (is (= "JVBERi0xLjQKJcfsj6IK" (:blob pdf)))
          (is (some #{"checker.clj"} (map :name (:entries (jsonista.core/read-value (:text dir) jsonista.core/keyword-keys-object-mapper))))
              "a directory as a whole JSON listing"))))
    (is (:ok? (ops/invoke *daemon* dvergr.principal/local-principal :catalog/calibrate {:room "ledger" :name "coding"})))
    (testing "benchmarked into the room, scored by the generated checker"
      (with-redefs [providers/ensure-initialized! (constantly nil)
                    chat-agent/messages->api-format (fn [messages _ _] messages)
                    model-chat/chat (fn [messages _]
                                      (if (not-any? #(= :tool-result (or (:role %) (:message/role %))) messages)
                                        {:content "" :tool-calls [{:id (str (random-uuid)) :name "write_file"
                                                                   :input {:path "/out/answer.edn"
                                                                           :content (pr-str {"account" "4930" "amount" "119,00"})}}]
                                         :usage {:input-tokens 500 :output-tokens 20} :stop-reason :tool-use}
                                        {:content "Booked." :tool-calls nil
                                         :usage {:input-tokens 600 :output-tokens 5} :stop-reason :end-turn}))]
        (let [started (ops/invoke *daemon* dvergr.principal/local-principal :catalog/benchmark
                                  {:workflow "ledger/coding" :models ["claude-haiku-4-5"] :room "ledger"})
              done (loop [n 0]
                     (let [st (ops/invoke *daemon* dvergr.principal/local-principal :job/status {:job (:id started) :wait-ms 20000})]
                       (if (or (not= "running" (:status st)) (< 8 n)) st (recur (inc n)))))]
          (is (= "completed" (:status done)) (pr-str (dissoc done :result)))
          (let [[sc] (ops/invoke *daemon* dvergr.principal/local-principal :scorecard/list {:room "ledger"})
                detail (ops/invoke *daemon* dvergr.principal/local-principal :scorecard/detail {:room "ledger" :id (:id sc)})]
            (is (= 1.0 (get-in detail [:leaderboard 0 :reward-mean])) (pr-str detail))
            (is (= 2 (get-in detail [:leaderboard 0 :attempts])))))))))

(deftest settlement-reviews-catalog-attempts-on-a-worker
  (let [op #(ops/invoke *daemon* dvergr.principal/local-principal %1 %2)
        room (:id (op :room/create {:slug "auto-review"}))
        r (ops/resolve-room *daemon* room)
        spec {:review/checks [:fork/review-tier [:diff/only-paths ["wiki/"]] :wiki/v4]
              :review/reviewer {:model "claude-haiku-4-5" :instructions "Check the evidence."}}
        pages (atom (v4/reference-wiki (gen/world 1)))
        reviewer-calls (atom 0)
        workers (atom [])
        pending (atom [])
        caller (assoc dvergr.principal/local-principal :principal/delegation "wiki-delegation"
                      :principal/delegation-scope {:paths ["wiki/"]})
        make-reviewer review/model-reviewer]
    (catalog/seed! r (gen/documents (gen/world 1)))
    (resource/mint! r {:id (random-uuid) :resources {resource/microdollars 2000000M}})
    (with-redefs [catalog/workflows (assoc-in catalog/workflows [:wiki/v4 :review] spec)
                  providers/ensure-initialized! (constantly nil)
                  chat-agent/messages->api-format (fn [messages _ _] messages)
                  review/model-reviewer
                  (fn [daemon room world spec caller]
                    (swap! workers conj {:delegation (:principal/delegation caller)
                                         :spin ec/*spin-id* :thread (.getName (Thread/currentThread))})
                    (make-reviewer daemon room world spec caller))
                  model-chat/chat
                  (fn [messages opts]
                    (if (str/includes? (str (:model opts)) "haiku")
                      (do
                        (swap! reviewer-calls inc)
                        (swap! pending conj (some #(= :review (:run/settlement-status %)) (run/runs r)))
                        {:content "{:review/verdict :approve :review/reasons [] :review/concerns []}"
                         :tool-calls nil :usage {:input-tokens 100 :output-tokens 20}
                         :stop-reason :end-turn})
                      (if (not-any? #(= :tool-result (or (:role %) (:message/role %))) messages)
                        {:content ""
                         :tool-calls (mapv (fn [[path text]]
                                             {:id (str (random-uuid)) :name "write_file"
                                              :input {:path path :content text}}) @pages)
                         :usage {:input-tokens 100 :output-tokens 100} :stop-reason :tool-use}
                        {:content "Done." :tool-calls nil
                         :usage {:input-tokens 100 :output-tokens 10} :stop-reason :end-turn})))]
      (let [good (run-job {:workflow "wiki/v4" :room room :models ["claude-sonnet-4-5"]} caller)
            [merged] (op :review/list {:room room})]
        (is (= "completed" (:status good)) (pr-str good))
        (is (= "merged" (:decision merged)))
        (is (= @pages (catalog/read-tree r "/wiki")))
        (is (= :merged (:run/settlement-status (run/run r (java.util.UUID/fromString (:run merged))))))
        (reset! pages {"/wiki/index.md" "# Broken wiki without links or front matter"})
        (let [bad (run-job {:workflow "wiki/v4" :room room :models ["claude-sonnet-4-5"]} caller)
              records (op :review/list {:room room})
              escalated (first (filter #(= "escalated" (:decision %)) records))]
          (is (= "completed" (:status bad)) (pr-str bad))
          (is (= #{"merged" "escalated"} (set (map :decision records))))
          (is (= 2 (count (review/reviews r))))
          (is (= :review (:run/settlement-status (run/run r (java.util.UUID/fromString (:run escalated))))))
          (is (some #(and (= :review/escalated (get-in % [:metadata :kind]))
                          (= (:id escalated) (str (get-in % [:metadata :object :id]))))
                    (d/messages r)))
          (is (= 1 @reviewer-calls) "failed deterministic checks do not ask the model")
          (is (= [true] @pending) "the candidate stays in review until the decision")
          (is (= 2 (count @workers)))
          (is (every? #(and (= "wiki-delegation" (:delegation %)) (nil? (:spin %))
                            (str/starts-with? (:thread %) "dvergr-agent-native-")) @workers))
          (is (every? #(= :discarded (:run/settlement-status %))
                      (filter #(= :reviewer (:run/actor %)) (run/runs r)))))))))

(deftest a-room-bundle-carries-its-review-policy-into-settlement
  (let [room (:id (ops/invoke *daemon* dvergr.principal/local-principal :room/create {:slug "bundle-review"}))
        r (ops/resolve-room *daemon* room)
        spec {:review/checks [[:diff/only-paths ["wiki/"]]] :review/on-pass :propose}]
    (catalog/seed! r {"/workflows/notes/workflow.edn"
                      (pr-str {:title "Notes" :task "Write notes" :review spec
                               :workflow/writes {:repo ["wiki/"]}})
                      "/workflows/notes/checker.clj"
                      "(ns check) (defn check [_] {:checks {:done? true} :reward 1.0})"
                      "/workflows/notes/fixtures/docs/brief.md" "Prepare wiki notes."})
    (is (= spec (:review (room-wf/plan (room-wf/read-bundle r "notes") {}))))
    (with-redefs [providers/ensure-initialized! (constantly nil)
                  chat-agent/messages->api-format (fn [messages _ _] messages)
                  model-chat/chat (wiki-writer (atom 0))]
      (let [done (run-job {:workflow "bundle-review/notes" :models ["claude-haiku-4-5"]}
                          (assoc dvergr.principal/local-principal
                                 :principal/delegation "notes-delegation"
                                 :principal/delegation-scope {:paths ["wiki/"]}))
            [record] (ops/invoke *daemon* dvergr.principal/local-principal :review/list {:room room})]
        (is (= "completed" (:status done)) (pr-str done))
        (is (= "escalated" (:decision record)))
        (is (some #(str/includes? % "left to a person") (:reasons record)))))))
