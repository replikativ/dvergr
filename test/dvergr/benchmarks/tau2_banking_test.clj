(ns dvergr.benchmarks.tau2-banking-test
  "Provider-free contracts for the tau2 `banking_knowledge` transcription
   (bm25 retrieval). Expected values are digests of upstream Python replays
   (`dev/benchmarks/tau2/oracle.py`); tests need the pinned `../tau2-bench`
   checkout and are skipped with a note when it is absent."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dvergr.benchmarks.tau2.banking :as banking]
            [dvergr.benchmarks.tau2.banking.db :as db]
            [dvergr.benchmarks.tau2.core :as t2]
            [dvergr.benchmarks.tau2.equivalence :as eqv]
            [dvergr.benchmarks.tau2.pyjson :as pj]))

(def ^:private checkout?
  (.exists (io/file t2/default-root "data/tau2/domains/banking_knowledge/db.json")))

(def ^:private domain (delay (t2/load-domain "banking_knowledge")))

(defn- fixture []
  (edn/read-string (slurp (io/resource "dvergr/benchmarks/tau2/banking_oracle_digests.edn"))))

(defn- chunk-corpus []
  (pj/parse (slurp (io/resource "dvergr/benchmarks/tau2/banking_corpus.json"))))

(defn- initial-world [dom]
  (fn [{:strs [task inject]}]
    (reduce (fn [w [table id rec]] (update w :db db/put-record table id rec))
            ((:initial-world dom) (get-in dom [:tasks task]))
            inject)))

(defn- digests [dom corpus]
  (eqv/world-replay-digests (:respond dom) (initial-world dom) (:world-hash dom) corpus))

(defmacro ^:private with-checkout [test-name & body]
  `(if-not checkout?
     (println ~(str "SKIP " test-name ": no ../tau2-bench checkout"))
     (do ~@body)))

(deftest banking-equivalence-with-upstream
  (with-checkout "banking-equivalence-with-upstream"
    (let [dom @domain
          fx (fixture)
          discoverable (set (map :name banking/discoverable-agent-tools))]
      (is (= (:revision t2/upstream) (get-in fx [:upstream :revision])))
      (is (= "af324a7d72aced5889daf6cc044e573ad94ffa3bfe7cf65114fd3cfed5daaa48"
             (:initial-db-hash dom)))
      (testing "gold action replays of all tasks (agent and user requestors)"
        (is (= (:gold fx) (digests dom (eqv/gold-corpus (vals (:tasks dom)))))))
      (testing "core tools, KB_search, discoverable mechanics, user tools"
        (let [{:keys [seed n] :as core} (:core fx)]
          (is (= (:digests core)
                 (digests dom (mapv #(if (get % "task") % (dissoc % "task"))
                                    (eqv/banking-core-corpus (:db dom) seed n)))))))
      (testing "44 discoverable agent tools called directly"
        (is (= (:chunks fx) (digests dom (chunk-corpus)))))
      (testing "the same calls through unlock + call_discoverable_agent_tool"
        (is (= (:unlock fx) (digests dom (eqv/via-unlock discoverable (chunk-corpus)))))))))

(deftest banking-prompts-match-upstream
  (with-checkout "banking-prompts-match-upstream"
    (let [dom @domain
          {:keys [agent users]} (:prompts (fixture))]
      (is (= agent (pj/sha256-hex (t2/agent-system-prompt dom))))
      (is (= users (into (sorted-map)
                         (map (fn [[id task]] [id (pj/sha256-hex (t2/user-system-prompt dom task))]))
                         (:tasks dom)))))))

(deftest banking-tool-surface-matches-upstream
  (with-checkout "banking-tool-surface-matches-upstream"
    (let [meta @banking/metadata
          kits (banking/toolkits (banking/build-bm25 [{:id "d" :title "t" :content "c"}]))
          sig (fn [t] (mapv (fn [[p & default]] [p (empty? default)]) (:params t)))]
      (is (= (set (keys (get meta "agent_discoverable")))
             (set (map :name banking/discoverable-agent-tools))))
      (doseq [[kit params] [[:agent "agent_tool_params"] [:user "user_tool_params"]]
              [tool-name tool-def] (get kits kit)]
        (is (= (get-in meta [params tool-name]) (sig tool-def)) tool-name)
        (is (= (get-in meta ["mutating" tool-name]) (boolean (:mutates? tool-def))) tool-name)))))

(defn- gold-pair
  "Scripted agent/user replaying gold actions in order; the side owning the
   next action acts, the other hands the turn over."
  [task]
  (let [actions (vec (get-in task ["evaluation_criteria" "actions"]))
        cursor (atom 0)
        next-action #(get actions @cursor)
        user? #(= "user" (get % "requestor"))
        emit (fn [] (let [{:strs [name arguments action_id]} (next-action)]
                      (swap! cursor inc)
                      {:tool-calls [{:id (or action_id (str "a" @cursor))
                                     :name name :arguments arguments}]}))]
    {:agent (fn [_] (cond (nil? (next-action)) {:content "Done. ###STOP###"}
                          (not (user? (next-action))) (emit)
                          :else {:content "Please go ahead on your side."}))
     :user (fn [_] (if (and (next-action) (user? (next-action))) (emit) {:content "Okay."}))}))

(deftest banking-gold-episodes-certify-every-task
  (with-checkout "banking-gold-episodes-certify-every-task"
    (let [dom @domain]
      (doseq [[id task] (:tasks dom)
              :let [task (update-in task ["evaluation_criteria" "reward_basis"]
                                    #(vec (remove #{"NL_ASSERTION"} %)))
                    episode (t2/run-episode dom task (gold-pair task))
                    grade (t2/grade dom task episode {})]]
        (is (= :agent-stop (:termination episode)) id)
        (is (= 1.0 (:reward grade)) id)))))

(deftest action-checks-follow-upstream-comparison
  (let [task {"evaluation_criteria"
              {"actions" [{"name" "submit_referral"
                           "arguments" {"user_id" "u1" "account_type" "Gold"}}]}}
        run (fn [args] (:met (first (t2/action-checks
                                     task [{:role :user :tool-calls [{:name "submit_referral"
                                                                      :arguments args}]}]))))]
    (is (run {"user_id" "u1" "account_type" "Gold"}))
    (is (run {"user_id" "u1"}) "upstream compares only the predicted call's keys")
    (is (not (run {"user_id" "u1" "account_type" "Gold" "extra" 1})))
    (is (not (run {"user_id" "u2" "account_type" "Gold"})))))

(deftest kb-search-reports-bm25-results
  (let [index (banking/build-bm25 [{:id "a" :title "Annual fees" :content "annual fee waiver policy"}
                                   {:id "b" :title "" :content "debit card pin reset"}
                                   {:id "c" :title "Other" :content "unrelated text"}])]
    (is (str/starts-with? (banking/kb-search index "annual fee") "1. Annual fees\n   ID: a\n"))
    (is (str/includes? (banking/kb-search index "pin") "Untitled"))
    (is (str/starts-with? (banking/kb-search index "   ") "No relevant documents found."))))
