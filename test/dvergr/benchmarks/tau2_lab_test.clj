(ns dvergr.benchmarks.tau2-lab-test
  "Checkpoints and branches: an episode paused before a customer message
   forks copy-on-write into independent certified continuations. Branches
   inherit the world, dialogue, customer history and the candidate's REPL
   heap, never see each other, and leave the checkpoint unchanged."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [dvergr.agent.conversation :as conv]
            [dvergr.agent.roster :as roster]
            [dvergr.benchmarks.tau2.core :as t2]
            [dvergr.benchmarks.tau2.episode :as ep]
            [dvergr.benchmarks.tau2.experiment :as tx]
            [dvergr.discourse :as d]
            [dvergr.model.chat :as model-chat]
            [dvergr.room.store :as store]
            [dvergr.substrate.paths :as paths]
            [dvergr.system.db :as sdb]))

(def ^:private checkout?
  (.exists (io/file t2/default-root "data/tau2/domains/retail/db.json")))

(use-fixtures :each (fn [f] (try (f) (finally (paths/set-home! nil) (sdb/reset-conn!)))))

(defn- temp-dir []
  (str (java.nio.file.Files/createTempDirectory "tau2-lab" (make-array java.nio.file.attribute.FileAttribute 0))
       "/exp"))

(defn- without-nl [domain]
  (update domain :tasks
          (fn [tasks] (into (array-map)
                            (map (fn [[k t]] [k (update-in t ["evaluation_criteria" "reward_basis"]
                                                           #(vec (remove #{"NL_ASSERTION"} %)))]))
                            tasks))))

(defn- gold-agent [task]
  (let [actions (vec (remove #(= "user" (get % "requestor"))
                             (get-in task ["evaluation_criteria" "actions"])))]
    (fn [{:keys [messages]}]
      (let [done (count (filter :tool-calls messages))]
        (if (< done (count actions))
          (let [{:strs [name arguments action_id]} (nth actions done)]
            {:tool-calls [{:id action_id :name name :arguments arguments}]})
          {:content "All done. ###STOP###"})))))

(defn- lab [dir dom task-id specs]
  (conv/isolate-home! dir)
  (let [xs (conv/open-store! dir)
        room (d/make-room {:id :tau2/lab-test :store (:store xs)})
        team (tx/candidate-roster specs dom)]
    {:xs xs :room room :team team
     :definition (tx/environment-def dom task-id {:max-steps 200 :max-errors 10})
     :task (get-in dom [:tasks task-id])}))

(defn- close-lab! [{:keys [room xs]}]
  (try (d/close-room! room) (catch Throwable _ nil))
  (conv/close-store! xs))

(deftest reference-branches-diverge-from-one-checkpoint
  (if-not checkout?
    (println "SKIP reference-branches-diverge-from-one-checkpoint: no ../tau2-bench checkout")
    (let [dom (without-nl (t2/load-domain "retail"))
          {:keys [xs room team definition task] :as l}
          (lab (temp-dir) dom "0" [{:id :ref :harness :reference :model "claude-code-sonnet"}])
          agent (roster/agent team :ref)
          user (constantly {:content "I need help with an exchange."})]
      (try
        (let [{:keys [checkpoint termination]}
              (ep/run! {:experiment-room room :store (:store xs) :domain dom :task task
                        :definition definition :agent agent :user user
                        :agent-generate (constantly {:content "unused"})
                        :checkpoint-at 1 :timeout-ms 60000})
              initial-hash ((:world-hash dom) ((:initial-world dom) task))]
          (is (= :checkpoint termination))
          (is (= "I need help with an exchange." (get-in checkpoint [:pending :content])))
          (let [a (ep/branch! checkpoint {:agent-generate (gold-agent task)})
                b (ep/branch! checkpoint {:agent-generate (constantly {:content "Goodbye. ###STOP###"})})
                reward #(get-in % [:attempt :attempt/receipt :attempt/reward])
                traj #(get-in % [:attempt :attempt/evidence :trajectory])]
            (testing "branches are independent continuations of the same prefix"
              (is (= 1.0 (reward a)) "the gold continuation succeeds")
              (is (= 0.0 (reward b)) "the idle continuation fails")
              (is (= (:log checkpoint) (take (count (:log checkpoint)) (traj a))))
              (is (= (:log checkpoint) (take (count (:log checkpoint)) (traj b))))
              (is (every? #(= {:checkpoint-room (:id (:room checkpoint)) :at 1}
                              (get-in % [:attempt :attempt/receipt :attempt/metrics :branch]))
                          [a b])))
            (testing "the checkpoint's world is untouched by its branches"
              (is (= initial-hash ((:world-hash dom) (#'ep/world (:room checkpoint))))))
            (testing "only branches are certified"
              (is (= 2 (count (store/-list-attempts (:store xs) (:id room) {:limit 100})))))
            (ep/release-checkpoint! checkpoint)))
        (finally (close-lab! l))))))

(def ^:private usage {:input-tokens 1 :output-tokens 1})

(defn- scripted-chat
  "A model that answers each call with the next response and records what it saw."
  [responses seen]
  (let [n (atom -1)]
    (fn [messages _opts]
      (swap! seen conj messages)
      (nth responses (min (swap! n inc) (dec (count responses)))))))

(defn- eval-call [code] {:content "" :usage usage :tool-calls [{:id (str (random-uuid)) :name "clojure_eval" :input {:code code}}]})

(defn- last-eval-value
  "The value the model's last clojure_eval returned (`=> v` in the newest
   message it saw); the message also carries a random tool-use id."
  [seen]
  (second (re-find #"=> (\S+)" (str (:content (last (last @seen)))))))

(deftest dvergr-branches-inherit-and-isolate-the-repl-heap
  (if-not checkout?
    (println "SKIP dvergr-branches-inherit-and-isolate-the-repl-heap: no ../tau2-bench checkout")
    (let [dom (without-nl (t2/load-domain "retail"))
          {:keys [xs room team definition task] :as l}
          (lab (temp-dir) dom "0" [{:id :dv :harness :dvergr :action-space :repl :model "claude-code-sonnet"}])
          agent (roster/agent team :dv)
          user (constantly {:content "One more thing."})
          branch-with (fn [cp responses]
                        (let [seen (atom [])]
                          (with-redefs [model-chat/chat (scripted-chat responses seen)]
                            [(ep/branch! cp {}) seen])))]
      (try
        (let [{:keys [checkpoint]}
              (with-redefs [model-chat/chat (scripted-chat [(eval-call "(def helper 41)")
                                                            {:content "Noted. Anything else?" :usage usage}]
                                                           (atom []))]
                (ep/run! {:experiment-room room :store (:store xs) :domain dom :task task
                          :definition definition :agent agent :user user
                          :checkpoint-at 2 :timeout-ms 60000}))
              _ (is (some? checkpoint) "paused before the second customer message")
              [a seen-a] (branch-with checkpoint [(eval-call "(inc helper)") {:content "Done. ###STOP###" :usage usage}])
              [b seen-b] (branch-with checkpoint [(eval-call "(do (def helper 99) helper)") {:content "Done. ###STOP###" :usage usage}])
              [c seen-c] (branch-with checkpoint [(eval-call "helper") {:content "Done. ###STOP###" :usage usage}])]
          (testing "a branch inherits the checkpoint's REPL definitions"
            (is (= "42" (last-eval-value seen-a))))
          (testing "a redefinition in one branch is invisible to the others"
            (is (= "99" (last-eval-value seen-b)) "the redefinition happened in its own branch")
            (is (= "41" (last-eval-value seen-c))))
          (testing "every branch completes and is certified"
            (is (every? #(= :agent-stop (:termination %)) [a b c])))
          (ep/release-checkpoint! checkpoint))
        (finally (close-lab! l))))))
