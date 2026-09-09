(ns dvergr.benchmarks.coding-tool-comparison-test
  (:require [clojure.test :refer [deftest is]]
            [dvergr.agent.evaluation :as evaluation]
            [dvergr.agent.experiment :as experiment]
            [dvergr.agent.roster :as roster]
            [dvergr.benchmarks.rss :as rss]
            [dvergr.discourse :as d]
            [dvergr.room.store.memory :as memory]
            [dvergr.tools :as tools]
            [muschel.fs :as fs]
            [muschel.fs.geschichte :as gfs]
            [org.replikativ.spindel.core :as sp]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.spin.sync :as sync]))

(load-file "examples/coding_tool_comparison.clj")

(deftest tool-availability-is-the-only-candidate-policy-difference
  (let [{:keys [team blocks]} (coding-tool-comparison/plan {:provider :test :model "stub"})
        repl (roster/agent team :repl)
        editing (roster/agent team :editing)]
    (is (= (dissoc repl :agent/id :agent/tools) (dissoc editing :agent/id :agent/tools)))
    (is (= #{:clojure_eval} (:agent/tools repl)))
    (is (= #{:clojure_eval :clojure_edit :write_file} (:agent/tools editing)))
    (is (= 32 (get-in repl [:agent/program :max-model-steps])))
    (is (= [[:repl :editing] [:editing :repl]]
           (mapv #(mapv :candidate/id (:experiment/candidates %)) blocks)))
    (doseq [block blocks]
      (is (= 1 (:experiment/repetitions block)))
      (is (= [(rss/definition)] (get-in block [:experiment/dataset :dataset/environments]))))))

(deftest comparison-composes-existing-experiments-serially
  (let [room (d/make-room {:id :coding-comparison :store (memory/make)})
        calls (atom [])
        first-started (promise)
        allow-first (sync/create-deferred (:ctx room))
        group (evaluation/cleanup-group)
        plan (coding-tool-comparison/plan {:provider :test :model "stub"})]
    (try
      (with-redefs [experiment/run
                    (fn [_ _ block _ options]
                      (sp/spin
                       (swap! calls conj [(:experiment/id block) options])
                       (when (= :coding/rss-tools-ab (:experiment/id block))
                         (deliver first-started true)
                         (sp/await allow-first))
                       {:block (:experiment/id block)}))]
        (binding [ec/*execution-context* (:ctx room)]
          (let [completion (promise)
                workflow (coding-tool-comparison/run room plan group)]
            (is (empty? @calls))
            (workflow #(deliver completion {:result %}) #(deliver completion {:error %}))
            (is (= true (deref first-started 5000 ::timeout)))
            (is (= [:coding/rss-tools-ab] (mapv first @calls)))
            (sync/deliver! allow-first true)
            (let [outcome (deref completion 5000 ::timeout)]
              (is (= [{:block :coding/rss-tools-ab} {:block :coding/rss-tools-ba}]
                     (:result outcome)))))))
      (is (= [:coding/rss-tools-ab :coding/rss-tools-ba] (mapv first @calls)))
      (doseq [[_ opts] @calls]
        (is (= {:parallelism 1 :max-parallelism 1 :max-attempts 2 :cleanup-group group}
               (dissoc opts :world-setups))))
      (finally (d/close-room! room)))))

(deftest editing-tools-operate-on-the-virtual-filesystem
  (let [{:keys [close!] :as repository} (gfs/memory-repository! {:name "coding-edit-tools"})
        filesystem (gfs/make-root repository)
        context (tools/make-context {:cwd "/" :filesystem filesystem})]
    (try
      (is (= :success (:type (tools/execute "write_file"
                                            {:path "src/demo.clj" :content "(ns demo)\n(defn answer [] 41)\n"}
                                            context))))
      (is (= :success (:type (tools/execute "clojure_edit"
                                            {:file_path "src/demo.clj" :form_type "defn" :form_name "answer"
                                             :operation "replace" :new_source "(defn answer [] 42)"}
                                            context))))
      (is (= "(ns demo)\n(defn answer [] 42)\n" (fs/read-file filesystem "/src/demo.clj")))
      (is (nil? (fs/physical-path filesystem "/src/demo.clj")))
      (finally (close!)))))
