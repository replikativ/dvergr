(ns dvergr.benchmarks.tau2-rooms-test
  "Certified conversational episodes in Rooms, with scripted models: gold
   agents score 1.0 through the Room path for retail and banking (dual
   control), Dvergr's production participant works in both action spaces,
   experiments resume from their Attempts, and the durable store alone
   reconstructs each episode (world replay reproduces the certified hash)."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [dvergr.agent.conversation :as conv]
            [dvergr.benchmarks.tau2.core :as t2]
            [dvergr.benchmarks.tau2.experiment :as tx]
            [dvergr.benchmarks.tau2.inspect :as insp]
            [dvergr.model.chat :as model-chat]
            [dvergr.substrate.paths :as paths]
            [dvergr.system.db :as sdb]))

(def ^:private checkout?
  (.exists (io/file t2/default-root "data/tau2/domains/banking_knowledge/db.json")))

(defn- restore-home [f]
  (try (f)
       (finally (paths/set-home! nil) (sdb/reset-conn!))))

(use-fixtures :each restore-home)

(defn- temp-dir []
  (str (java.nio.file.Files/createTempDirectory "tau2-rooms" (make-array java.nio.file.attribute.FileAttribute 0))
       "/exp"))

(defn- without-nl [domain]
  (update domain :tasks
          (fn [tasks] (into (array-map)
                            (map (fn [[k t]] [k (update-in t ["evaluation_criteria" "reward_basis"]
                                                           #(vec (remove #{"NL_ASSERTION"} %)))]))
                            tasks))))

(defn- gold-agent
  "Reference-harness generate fn replaying the agent's gold actions."
  [task]
  (let [actions (vec (remove #(= "user" (get % "requestor"))
                             (get-in task ["evaluation_criteria" "actions"])))]
    (fn [{:keys [messages]}]
      (let [done (count (filter :tool-calls messages))]
        (if (< done (count actions))
          (let [{:strs [name arguments action_id]} (nth actions done)]
            {:tool-calls [{:id action_id :name name :arguments arguments}]})
          {:content "All done. ###STOP###"})))))

(deftest retail-gold-through-rooms-with-resume
  (if-not checkout?
    (println "SKIP retail-gold-through-rooms-with-resume: no ../tau2-bench checkout")
    (let [dom (without-nl (t2/load-domain "retail"))
          dir (temp-dir)
          cfg {:dir dir :domain dom :task-ids ["0" "1" "5"] :repetitions 2 :parallelism 2
               :candidates [{:id :gold :harness :reference :model "claude-code-sonnet"}]
               :user-fn (constantly {:content "Hello, I need help."})
               :agent-generate gold-agent}
          first-run (tx/run! cfg)
          resumed (tx/run! cfg)
          xs (conv/open-store! dir)
          room-id (:experiment-room first-run)]
      (try
        (is (= 6 (:results first-run)))
        (is (contains? (:scorecard first-run) :scorecard/content-id))
        (is (= (:scorecard first-run) (:scorecard resumed)) "resume re-certifies nothing")
        (is (= [{:candidate :gold :attempts 6 :failed 0 :avg-reward 1.0}]
               (mapv #(select-keys % [:candidate :attempts :failed :avg-reward])
                     (insp/summary xs room-id))))
        (testing "the store alone reconstructs every episode"
          (doseq [a (insp/attempts xs room-id)
                  :let [e (insp/episode xs room-id (:attempt/id a))
                        task (get-in dom [:tasks (get-in a [:attempt/environment :environment/task :task-id])])]]
            (is (= :completed (:run/status (:episode-run e))))
            (is (every? #(= :completed (:run/status %)) (:runs e)))
            (is (seq (:dialogue e)))
            (is (:match? (insp/verify-world dom task e)))
            (is (= {:effects-match? true :dialogue-match? true
                    :runs-recorded? true :runs-terminal? true}
                   (insp/verify-episode e)))))
        (finally (conv/close-store! xs))))))

(deftest banking-dual-control-gold-through-rooms
  (if-not checkout?
    (println "SKIP banking-dual-control-gold-through-rooms: no ../tau2-bench checkout")
    (let [dom (without-nl (t2/load-domain "banking_knowledge"))
          ;; tasks whose gold trajectory includes user-side tool calls
          task-ids ["task_001" "task_026"]
          dir (temp-dir)]
      ;; A scripted pair shares one cursor over the gold sequence: whichever
      ;; side owns the next action performs it, the other hands the turn over.
      (doseq [task-id task-ids
              :let [task (get-in dom [:tasks task-id])
                    actions (vec (get-in task ["evaluation_criteria" "actions"]))
                    cursor (atom 0)
                    user? #(= "user" (get % "requestor"))
                    emit (fn [] (let [{:strs [name arguments action_id]} (nth actions @cursor)]
                                  (swap! cursor inc)
                                  {:tool-calls [{:id (or action_id (str "a" @cursor))
                                                 :name name :arguments arguments}]}))
                    agent-gen (fn [_]
                                (fn [_]
                                  (let [a (get actions @cursor)]
                                    (cond (nil? a) {:content "All done. ###STOP###"}
                                          (user? a) {:content "Please go ahead on your side."}
                                          :else (emit)))))
                    user (fn [_]
                           (let [a (get actions @cursor)]
                             (if (and a (user? a)) (emit) {:content "Done on my side."})))
                    r (tx/run! {:dir dir :domain dom :task-ids [task-id] :experiment-id :tau2/banking-gold
                                :candidates [{:id :gold :harness :reference :model "claude-code-sonnet"}]
                                :user-fn user :agent-generate agent-gen})]]
        (is (= 1 (:results r)) task-id))
      (let [xs (conv/open-store! dir)]
        (try
          (doseq [a (insp/attempts xs :tau2/banking-gold)
                  :let [e (insp/episode xs :tau2/banking-gold (:attempt/id a))
                        task (get-in dom [:tasks (get-in a [:attempt/environment :environment/task :task-id])])]]
            (is (= 1.0 (get-in a [:attempt/receipt :attempt/reward])))
            (is (some #(= :user (:requestor %)) (:effects e)) "user-side tool effects are recorded")
            (is (:match? (insp/verify-world dom task e))))
          (finally (conv/close-store! xs)))))))

(def ^:private usage {:input-tokens 1 :output-tokens 1})

(defn- scripted-chat [responses]
  (let [n (atom -1)]
    (fn [_ _] (nth responses (min (swap! n inc) (dec (count responses)))))))

(deftest dvergr-participant-in-both-action-spaces
  (if-not checkout?
    (println "SKIP dvergr-participant-in-both-action-spaces: no ../tau2-bench checkout")
    (let [dom (without-nl (t2/load-domain "retail"))
          gold (get-in dom [:tasks "0" "evaluation_criteria" "actions"])
          tool-responses (conj (mapv (fn [{:strs [name arguments action_id]}]
                                       {:content "" :usage usage
                                        :tool-calls [{:id action_id :name name :input arguments}]})
                                     gold)
                               {:content "Done. ###STOP###" :usage usage})
          code (str "(doseq [[n a] " (pr-str (mapv (fn [{:strs [name arguments]}] [name arguments]) gold))
                    "] ((ns-resolve 'tau2 (symbol n)) a))")
          repl-responses [{:content "" :usage usage
                           :tool-calls [{:id "e1" :name "clojure_eval" :input {:code code}}]}
                          {:content "Done. ###STOP###" :usage usage}]]
      (doseq [[action-space responses] [[:tools tool-responses] [:repl repl-responses]]
              :let [dir (temp-dir)
                    r (with-redefs [model-chat/chat (scripted-chat responses)]
                        (tx/run! {:dir dir :domain dom :task-ids ["0"]
                                  :candidates [{:id :dv :harness :dvergr :action-space action-space
                                                :model "claude-code-sonnet"}]
                                  :user-fn (constantly {:content "I need help with an exchange."})}))
                    xs (conv/open-store! dir)]]
        (try
          (let [[a] (insp/attempts xs (:experiment-room r))
                e (insp/episode xs (:experiment-room r) (:attempt/id a))]
            (is (= 1.0 (get-in a [:attempt/receipt :attempt/reward])) (str action-space))
            (is (= (count gold) (count (:effects e))) (str action-space))
            (is (seq (:activities e)) "the candidate's tool uses are recorded")
            (is (every? #(= :completed (:run/status %)) (:runs e)))
            (is (:match? (insp/verify-world dom (get-in dom [:tasks "0"]) e))))
          (finally (conv/close-store! xs)))))))

(deftest faults-are-certified-but-never-scored
  (if-not checkout?
    (println "SKIP faults-are-certified-but-never-scored: no ../tau2-bench checkout")
    (let [dom (without-nl (t2/load-domain "retail"))
          dir (temp-dir)
          r (tx/run! {:dir dir :domain dom :task-ids ["0"]
                      :candidates [{:id :gold :harness :reference :model "claude-code-sonnet"}]
                      :user-fn (fn [_] (throw (ex-info "provider 529" {:status 529})))
                      :agent-generate gold-agent})
          xs (conv/open-store! dir)]
      (try
        (let [[a] (insp/attempts xs (:experiment-room r))
              e (insp/episode xs (:experiment-room r) (:attempt/id a))]
          (is (= :failed (get-in a [:attempt/receipt :attempt/status])))
          (is (= :infrastructure-error (get-in a [:attempt/receipt :attempt/metrics :termination])))
          (is (= :user-model (get-in a [:attempt/evidence :failure :source])))
          (is (= :failed (:run/status (:episode-run e))))
          (is (contains? (:scorecard r) :incomplete) "no Scorecard for a faulted experiment")
          (is (= [{:candidate :gold :attempts 1 :failed 1 :episodes 0}]
                 (mapv #(select-keys % [:candidate :attempts :failed :episodes])
                       (insp/summary xs (:experiment-room r))))))
        (finally (conv/close-store! xs))))))

(deftest step-bound-ends-the-episode-without-another-turn
  (if-not checkout?
    (println "SKIP step-bound-ends-the-episode-without-another-turn: no ../tau2-bench checkout")
    (let [dom (without-nl (t2/load-domain "retail"))
          dir (temp-dir)
          ;; The agent looks up forever; tau2 counts two steps per tool batch.
          looping (fn [_] (fn [_] {:tool-calls [{:id (str (random-uuid)) :name "list_all_product_types"
                                                 :arguments {}}]}))
          r (tx/run! {:dir dir :domain dom :task-ids ["0"] :limits {:max-steps 9 :max-errors 10}
                      :candidates [{:id :looping :harness :reference :model "claude-code-sonnet"}]
                      :user-fn (constantly {:content "Hi."}) :agent-generate looping})
          xs (conv/open-store! dir)]
      (try
        (let [[a] (insp/attempts xs (:experiment-room r))
              e (insp/episode xs (:experiment-room r) (:attempt/id a))]
          (is (= :completed (get-in a [:attempt/receipt :attempt/status])) "a model outcome, scored")
          (is (= :max-steps (get-in a [:attempt/receipt :attempt/metrics :termination])))
          (is (= 0.0 (get-in a [:attempt/receipt :attempt/reward])))
          (is (= 1 (count (:runs e))) "no further candidate turn after the bound")
          (is (every? #(not= :running (:run/status %)) (:runs e)))
          (is (= 4 (count (:effects e))) "1 message + 4 batches x 2 steps reaches 9"))
        (finally (conv/close-store! xs))))))

(deftest repl-candidate-discovers-documented-tools
  (if-not checkout?
    (println "SKIP repl-candidate-discovers-documented-tools: no ../tau2-bench checkout")
    (let [dom (without-nl (t2/load-domain "retail"))
          seen (atom [])
          code (str "[(with-out-str (clojure.repl/doc tau2/cancel_pending_order)) "
                    "(sandbox/doc 'tau2)]")
          responses [{:content "" :usage usage
                      :tool-calls [{:id "e1" :name "clojure_eval" :input {:code code}}]}
                     {:content "Done. ###STOP###" :usage usage}]
          chat (scripted-chat responses)
          dir (temp-dir)]
      (with-redefs [model-chat/chat (fn [messages opts] (swap! seen conj [messages opts]) (chat messages opts))]
        (tx/run! {:dir dir :domain dom :task-ids ["0"]
                  :candidates [{:id :dv :harness :dvergr :action-space :repl :model "claude-code-sonnet"}]
                  :user-fn (constantly {:content "I need help with an exchange."})}))
      (let [[first-messages first-opts] (first @seen)
            ;; The evaluation result is the last message of the second model call.
            result (str (:content (last (first (second @seen)))))
            all-text (pr-str @seen)]
        (testing "the prompt carries the tool signatures and descriptions"
          (is (re-find #"\(tau2/cancel_pending_order \{\\\"order_id\\\" \\\"reason\\\"\}\)" all-text))
          (is (re-find #"ordered by mistake" (pr-str first-messages first-opts))))
        (testing "clojure.repl/doc and sandbox/doc describe the tau2 functions at runtime"
          (is (re-find #"Cancel a pending order" result))
          (is (re-find #"order_id reason" result) "arglists are shown")
          (is (re-find #"get_order_details" result))
          (is (not (re-find #"fns: " result)) "signatures, not bare names"))))))
