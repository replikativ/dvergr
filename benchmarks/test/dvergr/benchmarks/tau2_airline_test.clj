(ns dvergr.benchmarks.tau2-airline-test
  "Provider-free contracts for the tau2 `airline` transcription. Expected
   values are digests of upstream Python replays
   (`benchmarks/dev/tau2/airline/oracle_airline.py`); data-dependent tests
   need the pinned `../tau2-bench` checkout and are skipped with a note when
   it is absent."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dvergr.benchmarks.tau2.airline :as air]
            [dvergr.benchmarks.tau2.airline.corpus :as corpus]
            [dvergr.benchmarks.tau2.airline.pydantic :as pd]
            [dvergr.benchmarks.tau2.core :as t2]
            [dvergr.benchmarks.tau2.equivalence :as eqv]
            [dvergr.benchmarks.pyjson :as pj]))

(def ^:private checkout?
  (.exists (io/file t2/default-root "data/tau2/domains/airline/db.json")))

(def ^:private domain (delay (air/load-airline t2/default-root)))

(defn- fixture []
  (edn/read-string (slurp (io/resource "dvergr/benchmarks/tau2/airline_oracle_digests.edn"))))

(defn- digests [dom corpus]
  (eqv/world-replay-digests (:respond dom) (constantly (:db dom)) (:world-hash dom) corpus))

(defmacro ^:private with-checkout [test-name & body]
  `(if-not checkout?
     (println ~(str "SKIP " test-name ": no ../tau2-bench checkout"))
     (do ~@body)))

;; ---------------------------------------------------------------------------
;; Pinned against pydantic 2.13.5 through the oracle (no checkout needed)

(deftest pydantic-validation-text
  (is (= (str "Error: 2 validation errors for Passenger\n"
              "last_name\n  Field required [type=missing, input_value={'first_name': 'a'}, input_type=dict]\n"
              "    For further information visit https://errors.pydantic.dev/2.13/v/missing\n"
              "dob\n  Field required [type=missing, input_value={'first_name': 'a'}, input_type=dict]\n"
              "    For further information visit https://errors.pydantic.dev/2.13/v/missing")
         (:content (air/respond {} "update_reservation_passengers"
                                {"reservation_id" "X" "passengers" [{"first_name" "a"}]}))))
  (testing "reprs over 50 characters keep the first 25 and last 24"
    (is (str/includes?
         (pd/error-text "Passenger" [{:loc ["last_name"] :type "string_type"
                                      :msg "Input should be a valid string"
                                      :input {"x" (vec (take 90 (cycle [1 2 3])))}}])
         "input_value={'x': [1, 2, 3, 1, 2, 3, ...2, 3, 1, 2, 3, 1, 2, 3]}, input_type=dict]")))
  (testing "lax coercions"
    (is (= [:ok 3] (pd/v-int "3.00")))
    (is (= [:ok 1] (pd/v-int true)))
    (is (= "int_from_float" (-> (pd/v-int 12.5) second first :type)))
    (is (= "int_parsing" (-> (pd/v-int "1e3") second first :type)))
    (is (= [:ok 1000.0] (pd/v-float "1_000")))))

(deftest argument-errors-match-python
  (is (= "Error: AirlineTools.get_user_details() missing 1 required positional argument: 'user_id'"
         (:content (air/respond {} "get_user_details" {}))))
  (is (= "Error: AirlineTools.list_all_airports() got an unexpected keyword argument 'country'"
         (:content (air/respond {} "list_all_airports" {"country" "US"}))))
  (is (= "Error: Tool 'think' not found." (:content (air/respond {} "think" {"thought" "x"})))))

;; ---------------------------------------------------------------------------
;; Upstream data

(deftest airline-equivalence-with-upstream
  (with-checkout "airline-equivalence-with-upstream"
    (let [dom @domain
          fx (fixture)]
      (is (= (:revision t2/upstream) (get-in fx [:upstream :revision])))
      (is (= (:initial-db-hash fx) (:initial-db-hash dom)))
      (testing "gold action replays of all 50 tasks"
        (is (= (:gold fx) (digests dom (eqv/gold-corpus (vals (:tasks dom)))))))
      (testing "seeded fuzz corpus (state-dependent paths, validation errors, partial effects)"
        (doseq [{:keys [seed n] :as run} (:fuzz fx)]
          (is (= (:digests run) (digests dom (corpus/fuzz-corpus (:db dom) seed n)))
              (str "seed " seed)))))))

(deftest airline-prompts-and-tools-match-upstream
  (with-checkout "airline-prompts-and-tools-match-upstream"
    (let [dom @domain
          {:keys [agent users]} (:prompts (fixture))]
      (is (= agent (pj/sha256-hex (t2/agent-system-prompt dom))))
      (is (= users (into (sorted-map)
                         (map (fn [[id task]] [id (pj/sha256-hex (t2/user-system-prompt dom task))]))
                         (:tasks dom))))
      (is (= (:tools (fixture)) (pj/sha256-hex (pj/dumps (:tool-schemas dom)))))
      (is (= (set (map #(get-in % ["function" "name"]) (:tool-schemas dom)))
             (set (keys air/tools))))
      (is (= (:mutating (fixture))
             (into (sorted-map) (map (fn [[k {:keys [type]}]] [k (= :write type)])) air/tools))))))

(defn- gold-agent [task]
  (let [actions (get-in task ["evaluation_criteria" "actions"])]
    (fn [{:keys [messages]}]
      (let [done (count (filter :tool-calls messages))]
        (if (< done (count actions))
          (let [{:strs [name arguments action_id]} (nth actions done)]
            {:tool-calls [{:id action_id :name name :arguments arguments}]})
          ;; Say every communicate_info item so the COMMUNICATE check passes.
          {:content (str (str/join " " (get-in task ["evaluation_criteria" "communicate_info"]))
                         " Done. ###STOP###")})))))

(deftest airline-gold-episodes-certify-every-task
  (with-checkout "airline-gold-episodes-certify-every-task"
    (let [dom @domain]
      (is (= 50 (count (:tasks dom))))
      (doseq [[id task] (:tasks dom)
              :let [task (update-in task ["evaluation_criteria" "reward_basis"]
                                    #(vec (remove #{"NL_ASSERTION"} %)))
                    episode (t2/run-episode dom task {:agent (gold-agent task)
                                                      :user (constantly {:content "Hi."})})
                    grade (t2/grade dom task episode {})]]
        (is (= :agent-stop (:termination episode)) id)
        (is (= 1.0 (:reward grade)) id)))))
