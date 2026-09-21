(ns dvergr.benchmarks.tau2-telecom-test
  "Provider-free contracts for the tau2 `telecom` transcription. Expected
   values are digests of upstream Python replays
   (`benchmarks/dev/tau2/telecom/oracle_telecom.py`); tests needing the
   pinned `../tau2-bench` checkout are skipped with a note when it is absent."
  (:require [dvergr.test-support :as support]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dvergr.benchmarks.tau2.core :as t2]
            [dvergr.benchmarks.pyjson :as pj]
            [dvergr.benchmarks.python :as py]
            [dvergr.benchmarks.tau2.telecom :as tc]
            [dvergr.benchmarks.tau2.telecom.agent :as agent]
            [dvergr.benchmarks.tau2.telecom.corpus :as cp]
            [dvergr.benchmarks.tau2.telecom.db :as tdb]
            [dvergr.benchmarks.tau2.telecom.device :as device]))

(def ^:private checkout?
  (.exists (io/file t2/default-root "data/tau2/domains/telecom/db.toml")))

(def ^:private domain (delay (tc/load-domain)))

(defn- fixture []
  (edn/read-string (slurp (io/resource "dvergr/benchmarks/tau2/telecom_oracle_digests.edn"))))

(defn- combined [m] (pj/sha256-hex (pj/dumps (vec (sort m)))))

(defmacro ^:private with-checkout [test-name & body]
  `(if-not checkout?
     (support/skip! ~(str test-name ": no ../tau2-bench checkout"))
     (do ~@body)))

(deftest telecom-equivalence-with-upstream
  (with-checkout "telecom-equivalence-with-upstream"
    (let [dom @domain
          fx (fixture)
          w ((:initial-world dom) nil)]
      (is (= (:revision t2/upstream) (get-in fx [:upstream :revision])))
      (is (= (get-in fx [:initial :agent-db-hash]) (tdb/db-hash (:db w)) (:initial-db-hash dom)))
      (is (= (get-in fx [:initial :user-db-hash]) (tdb/db-hash (:user w))))
      (testing "gold actions + env assertions of every task (agent and user requestors)"
        (let [gold (cp/replay-digests dom (cp/gold-corpus (vals (:tasks dom))))]
          (is (= (get-in fx [:gold-all :tasks]) (count gold)))
          (is (= (get-in fx [:gold-all :digest]) (combined gold)))
          (is (= (:gold-base fx) (select-keys gold (keys (:gold-base fx)))))))
      (testing "seeded fuzz corpus (task states, init functions, both toolkits, junk arguments)"
        (let [{:keys [seed n digests]} (:fuzz fx)]
          (is (= digests (cp/replay-digests dom (cp/fuzz-corpus dom seed n))))))
      (testing "seeded flow corpus (payments, junk APN, SIM locks, draft bills, VPN)"
        (let [{:keys [seed n digests]} (:flow fx)]
          (is (= digests (cp/replay-digests dom (cp/flow-corpus dom seed n)))))))))

(deftest telecom-prompts-match-upstream
  (with-checkout "telecom-prompts-match-upstream"
    (let [dom @domain
          {:keys [agent greeting users-base users-all user-tools-all]} (:prompts (fixture))
          user-sha (fn [[id task]] [id (pj/sha256-hex (t2/user-system-prompt dom task))])]
      (is (= agent (pj/sha256-hex (t2/agent-system-prompt dom))))
      (is (= greeting t2/first-agent-message))
      (is (= users-base (into (sorted-map) (map user-sha) (select-keys (:tasks dom) (keys users-base)))))
      (is (= users-all (combined (into {} (map user-sha) (:tasks dom)))))
      (is (= user-tools-all
             (combined (into {} (map (fn [[id task]]
                                       [id (mapv #(get-in % ["function" "name"]) ((:user-tool-schemas dom) task))]))
                             (:tasks dom))))))))

(deftest telecom-tool-surface-matches-upstream
  (let [meta @tc/metadata
        sig (fn [f] (mapv (fn [[p & default]] [p (empty? default)]) (:params f)))]
    (doseq [[kit params schemas] [[agent/functions "agent_tool_params" "tools"]
                                  [device/functions "user_tool_params" "user_tools"]]]
      (let [tools (into {} (filter (comp :tool? val)) kit)]
        (is (= (set (keys (get meta params))) (set (keys tools))))
        (is (= (set (map #(get-in % ["function" "name"]) (get meta schemas))) (set (keys tools))))
        (doseq [[n f] tools]
          (is (= (get-in meta [params n]) (sig f)) n)
          (is (= (get-in meta ["mutating" n]) (= :write (:type f))) n))))))

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
                      {:tool-calls [{:id (or action_id (str "a" @cursor)) :name name :arguments arguments}]}))]
    {:agent (fn [_] (cond (nil? (next-action)) {:content "Done. ###STOP###"}
                          (not (user? (next-action))) (emit)
                          :else {:content "Please go ahead on your side."}))
     :user (fn [_] (if (and (next-action) (user? (next-action))) (emit) {:content "Okay."}))}))

(deftest telecom-gold-episodes-certify-every-base-task
  (with-checkout "telecom-gold-episodes-certify-every-base-task"
    (let [dom @domain
          fx (fixture)]
      (is (= 114 (count (:upstream-gold-eval fx))))
      (is (every? #{1.0} (vals (:upstream-gold-eval fx))) "upstream's own gold scores 1.0")
      (doseq [task (t2/split-tasks dom "base")
              :let [episode (t2/run-episode dom task (gold-pair task))
                    grade (t2/grade dom task episode {})]]
        (is (= :agent-stop (:termination episode)) (get task "id"))
        (is (= 1.0 (:reward grade)) (get task "id"))
        (is (contains? (:reward-breakdown grade) :env-assertion) (get task "id")))
      (testing "an agent that does nothing fails every base task"
        (doseq [task (t2/split-tasks dom "base")]
          (is (= 0.0 (:reward (t2/grade dom task (t2/run-episode dom task {:agent (fn [_] {:content "Bye ###STOP###"})
                                                                           :user (fn [_] {:content "hi"})})
                                        {})))
              (get task "id")))))))

(deftest telecom-grading-matches-upstream-evaluators
  (with-checkout "telecom-grading-matches-upstream-evaluators"
    (let [dom @domain
          {:keys [seed rewards]} (:grade (fixture))
          corpus (cp/grade-corpus dom seed)]
      (is (= (count rewards) (count corpus)))
      (is (= rewards
             (into (sorted-map)
                   (for [{:strs [id task calls]} corpus
                         :let [t (get-in dom [:tasks task])]]
                     [id (:reward (t2/grade dom t (cp/trajectory-episode dom t calls) {}))])))))))

(deftest toml-subset-and-apn-validation
  (is (= {"a" [{"x" 1 "t" {"y" "z\"q"}} {"x" 2.5 "l" ["p" "q"] "e" []}] "b" {"c" {"d" true}}}
         (tdb/parse-toml (str "[[a]]\nx = 1\n[a.t]\ny = \"z\\\"q\"\n[[a]]\nx = 2.5\nl = [\"p\", \"q\"]\ne = []\n"
                              "[b.c]\nd = true\n"))))
  (is (= "internet" (get (device/apn-settings {"reset_at_reboot" "TRUE" "mms_port" " 12 "}) "apn_name")))
  (let [msg (try (device/apn-settings {"z" 1 "apn_name" 5})
                 (catch clojure.lang.ExceptionInfo e (py/exception-message e)))]
    (is (str/starts-with? msg "2 validation errors for APNSettings\napn_name\n  Input should be 'internet' or 'broken' [type=enum, input_value=5, input_type=int]"))
    (is (str/includes? msg "\nz\n  Extra inputs are not permitted [type=extra_forbidden"))))
