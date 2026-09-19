(ns dvergr.benchmarks.tau2.telecom
  "Pure Clojure transcription of tau2-bench's `telecom` domain (manual tech
   support policy, dual control, not solo).

   A world is an immutable value

     {:db TelecomDB              ; agent side (plans, customers, lines, bills, devices)
      :user TelecomUserDB        ; the user's phone (`device`) and `surroundings`
      :vpn-performance str       ; TelecomUserTools.default_vpn_details (shared, see device ns)
      :bill-seq n}               ; deterministic stand-in for uuid4 draft-bill ids

   Both databases are graded upstream (`get_db_hash` and `get_user_db_hash`);
   `world-hash` joins them as `agent|user`. Every successful tool call is
   followed by `TelecomEnvironment.sync_tools` (`sync`), which projects the
   agent DB onto the phone (line active, roaming allowed, data exhausted) and
   settles payments, exactly like `Environment.get_response`.

   Telecom tasks are graded by ENV_ASSERTION (plus ACTION on some): assertion
   functions evaluated on the final world (`env-assertion-checks`), which
   `dvergr.benchmarks.tau2.core/grade` calls for the ENV_ASSERTION
   component. This namespace does not require `core`, so
   `core/load-domain` can require it.

   Upstream: `src/tau2/domains/telecom/{environment,tools,user_tools}.py`,
   `src/tau2/environment/environment.py`, `src/tau2/evaluator/evaluator_env.py`,
   checked by `dev/benchmarks/tau2/telecom/oracle_telecom.py`."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [dvergr.benchmarks.tau2.banking.db :as bdb]
            [dvergr.benchmarks.tau2.pyjson :as pj]
            [dvergr.benchmarks.tau2.python :as py]
            [dvergr.benchmarks.tau2.telecom.agent :as agent]
            [dvergr.benchmarks.tau2.telecom.db :as tdb]
            [dvergr.benchmarks.tau2.telecom.device :as device]))

;; ---------------------------------------------------------------------------
;; Provenance

(def upstream-revision "b7ea9074c1cba482b30687fecdb5c8425fd6f619")

(def default-root "../tau2-bench")

(def file-digests
  {"db.toml" "562d647ef9d7df8df91eafd8ee76036e707c8f9e32aaedc4a7fde06975aea2c0"
   "user_db.toml" "4886107ea0c16f8e16d74bef91cfb557de61822afe9361f5a9b241806aea2e4c"
   "main_policy.md" "95943844a0cf11fdf0e2842b483b81d9f9338aa53235712ed131db075d086ed7"
   "tech_support_manual.md" "015a3ee49ec8c199c5e1a059c922081937b92cf3a5ea74fedbc4357816f389ba"
   "tasks.json" "37e562e1ae3242577407e1303b1548bc64e7ea68e37d36173e6747990ceaf8a4"
   "split_tasks.json" "605b488bb9a6acb3c7f4505240a855fdc8681d09aadb16a8f38b2efcfc5c3aec"
   :user-guidelines "740a29dfa64d7bc08eea3bf7493575b914a63f744acbaf7f199ee07eddaf72d3"
   :user-guidelines-tools "cbf3d8a4d8642fd04e559862f1afef55d7dd4e6a7e727ca49e239023c599de0c"})

(defn- verified-slurp [path expected]
  (let [text (slurp path)
        digest (pj/sha256-hex text)]
    (when (not= digest expected)
      (throw (ex-info "tau2 data file does not match the pinned upstream revision"
                      {:type ::data-digest-mismatch :path (str path) :expected expected
                       :actual digest :upstream-revision upstream-revision})))
    text))

(def metadata
  "Oracle `schema` export: tool schemas, parameters, mutation flags, hashes."
  (delay (pj/parse (slurp (io/resource "benchmarks/tau2/telecom-tools.json")))))

;; ---------------------------------------------------------------------------
;; Toolkits and dispatch

(def toolkits
  {:assistant {:owner "TelecomTools" :functions agent/functions}
   :user {:owner "TelecomUserTools" :functions device/functions}})

(defn- run-fn
  "Bind Python keyword arguments and run one toolkit function."
  [kit name world args]
  (let [{:keys [owner functions]} (get toolkits kit)
        f (get functions name)]
    ((:fn f) world (bdb/bind-args {:name name :owner owner :params (:params f)} args))))

(defn sync-tools
  "`TelecomEnvironment.sync_tools`."
  [world]
  (let [phone (get-in world [:user "surroundings" "phone_number"])]
    (if (nil? phone)
      world
      (let [db (:db world)
            line (get-in db ["lines" (agent/line-index-by-phone db phone)])
            plan (agent/plan db (get line "plan_id"))
            world (update-in world [:user "surroundings"] assoc
                             "line_active" (= "Active" (get line "status"))
                             "roaming_allowed" (py/truthy? (get line "roaming_enabled")))
            world (assoc-in world [:user "surroundings" "mobile_data_usage_exceeded"]
                            (py/py-compare :ge (get line "data_used_gb")
                                           (+ (get plan "data_limit_gb") (get line "data_refueling_gb"))))
            pr (get-in world [:user "surroundings" "payment_request"])
            world (if (and pr (get pr "paid"))
                    (-> world
                        (update :db agent/set-bill-paid (get pr "bill_id"))
                        (assoc-in [:user "surroundings" "payment_request"] nil))
                    world)]
        (if (some? (get-in world [:user "surroundings" "payment_request"]))
          world
          (let [customer (agent/customer-by-phone (:db world) phone)
                [bill] (agent/bills-awaiting-payment (:db world) customer)]
            (if bill
              (assoc-in world [:user "surroundings" "payment_request"]
                        (array-map "bill_id" (get bill "bill_id")
                                   "amount_due" (double (get bill "total_due"))
                                   "paid" false))
              world)))))))

(defn- error-world [e world]
  (or (::agent/world (ex-data e)) world))

(defn respond
  "Environment `get_response`: `{:world w' :content str :error bool}`, never
   throws for Python exceptions. `requestor` is `:assistant` or `:user`."
  [world requestor tool-name args]
  (let [args (or args {})
        kit (if (= requestor :user) :user :assistant)]
    (try
      ;; `make_tool_call(message.name, requestor=..., **message.arguments)`
      (when (contains? args "requestor")
        (py/raise "TypeError" (str "tau2.environment.environment.Environment.make_tool_call() "
                                   "got multiple values for keyword argument 'requestor'")))
      (when-let [k (first (filter #{"self" "tool_name"} (keys args)))]
        (py/raise "TypeError" (str "Environment.make_tool_call() got multiple values for argument '" k "'")))
      (when-not (get-in toolkits [kit :functions tool-name :tool?])
        (py/raise "ValueError" (str "Tool '" (py/py-str tool-name) "' not found.")))
      (let [{w :world result :result} (run-fn kit tool-name world args)]
        (try
          {:world (sync-tools w) :content result :error false}
          (catch clojure.lang.ExceptionInfo e
            (if (py/py-exception? e)
              {:world w :content (str "Error: " (py/exception-message e)) :error true}
              (throw e)))))
      (catch clojure.lang.ExceptionInfo e
        (if (py/py-exception? e)
          {:world (error-world e world) :content (str "Error: " (py/exception-message e)) :error true}
          (throw e))))))

(defn run-env-function-call
  "`Environment.run_env_function_call`: any toolkit function (tools,
   initialization functions, assertions), then `sync_tools`. Returns
   `{:world :result}`; Python exceptions propagate (with the partially
   mutated world in `ex-data` under `::agent/world`)."
  [world {:strs [env_type func_name arguments]}]
  (let [kit (if (= "user" env_type) :user :assistant)
        {:keys [owner functions]} (get toolkits kit)]
    (when-not (contains? functions func_name)
      (py/raise "AttributeError" (str "'" owner "' object has no attribute '" func_name "'")))
    (let [{w :world result :result} (run-fn kit func_name world arguments)]
      (try
        {:world (sync-tools w) :result result}
        (catch clojure.lang.ExceptionInfo e
          (throw (ex-info (ex-message e) (assoc (ex-data e) ::agent/world w))))))))

(defn run-env-assertion
  "`Environment.run_env_assertion(..., raise_assertion_error=False)`:
   `{:world :met bool}`."
  [world {:strs [func_name assert_value] :as assertion}]
  (let [{w :world res :result} (run-env-function-call world assertion)]
    (when-not (boolean? res)
      (py/raise "ValueError" (str "Function " func_name " returned <class '" (py/type-name res)
                                  "'> instead of bool")))
    {:world w :met (= res (if (contains? assertion "assert_value") assert_value true))}))

;; ---------------------------------------------------------------------------
;; Worlds

(defn base-world [db user-db]
  {:db db :user user-db :vpn-performance "excellent" :bill-seq 0})

(defn initial-world
  "`Environment.set_state(initialization_data=None, initialization_actions,
   [])`: the task's initialization actions, each followed by a sync, then a
   final sync. Telecom tasks carry no initialization data or history."
  [base task]
  (let [{:strs [initialization_data initialization_actions message_history]}
        (get task "initial_state")]
    (when (or (some? initialization_data) (seq message_history))
      (throw (ex-info "Unsupported telecom initial state" {:task (get task "id")})))
    (sync-tools (reduce (fn [w action] (:world (run-env-function-call w action)))
                        base initialization_actions))))

(defn world-hash
  "Upstream `get_db_hash()` and `get_user_db_hash()` joined as `agent|user`."
  [world]
  (str (tdb/db-hash (:db world)) "|" (tdb/db-hash (:user world))))

;; ---------------------------------------------------------------------------
;; Grading

(defn settle
  "The evaluator's predicted environment ends `set_state` with a sync; the
   live world is already synced after every successful call, so this only
   matters after a final erroring call."
  [world]
  (sync-tools world))

(defn env-assertion-checks
  "`EnvironmentEvaluator`'s ENV_ASSERTION component on a final world:
   `[{:assertion a :met bool}]`, run in order on the settled world (each
   assertion is followed by a sync, as upstream)."
  [task world]
  (loop [w (settle world) [a & more] (get-in task ["evaluation_criteria" "env_assertions"]) acc []]
    (if (nil? a)
      acc
      (let [{w' :world met :met} (run-env-assertion w a)]
        (recur w' more (conj acc {:assertion a :met met}))))))

;; ---------------------------------------------------------------------------
;; Data

(defn policy
  "`get_environment(policy_type=\"manual\")`: main policy + tech support manual."
  [main manual]
  (str "<main_policy>\n" main "\n</main_policy>\n<tech_support_policy>\n" manual "\n</tech_support_policy>"))

(defn load-telecom
  "The telecom domain map (same keys as `core/load-banking`, plus
   `:env-assertion-checks` and `:settle`). Tasks: all of `tasks.json`
   (`get_tasks` reads it and filters by split; the default split is
   \"base\")."
  [root]
  (let [dir (io/file root "data/tau2/domains/telecom")
        read (fn [f] (verified-slurp (io/file dir f) (get file-digests f)))
        db (tdb/agent-db (tdb/parse-toml (read "db.toml")))
        user-db (tdb/user-db (tdb/parse-toml (read "user_db.toml")))
        base (base-world db user-db)
        tasks (pj/parse (read "tasks.json"))
        guidelines #(verified-slurp (io/file root "data/tau2/user_simulator" %1) (get file-digests %2))
        meta @metadata
        user-schemas (get meta "user_tools")]
    {:domain "telecom"
     :policy-type "manual"
     :db db
     :initial-db-hash (tdb/db-hash db)
     :initial-world (fn [task] (if task (initial-world base task) base))
     :respond respond
     :world-hash world-hash
     :policy (policy (read "main_policy.md") (read "tech_support_manual.md"))
     :tasks (into (array-map) (map (juxt #(get % "id") identity)) tasks)
     :splits (pj/parse (read "split_tasks.json"))
     :tool-schemas (get meta "tools")
     :user-tool-schemas (fn [task]
                          (let [include (get task "user_tools")]
                            (not-empty
                             (if (nil? include)
                               user-schemas
                               (filterv #(some #{(get-in % ["function" "name"])} include) user-schemas)))))
     :user-guidelines (guidelines "simulation_guidelines.md" :user-guidelines)
     :user-guidelines-tools (guidelines "simulation_guidelines_tools.md" :user-guidelines-tools)
     :env-assertion-checks env-assertion-checks
     :settle settle}))

(defn load-domain
  ([] (load-domain {}))
  ([{:keys [root] :or {root default-root}}] (load-telecom root)))
