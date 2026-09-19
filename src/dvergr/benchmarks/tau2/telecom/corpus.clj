(ns dvergr.benchmarks.tau2.telecom.corpus
  "Equivalence corpora for the telecom transcription and their Clojure
   replay, in the shape `dev/benchmarks/tau2/telecom/oracle_telecom.py
   replay` produces.

   A sequence is `{\"id\" \"task\"? \"env_calls\"? \"calls\" \"assertions\"?}`:
   the task's initial state, optional initialization-function calls, tool
   calls with requestors, and env assertions evaluated at the end."
  (:require [clojure.string :as str]
            [dvergr.benchmarks.tau2.pyjson :as pj]
            [dvergr.benchmarks.tau2.python :as py]
            [dvergr.benchmarks.tau2.telecom :as tc]
            [dvergr.benchmarks.tau2.telecom.agent :as agent]
            [dvergr.benchmarks.tau2.telecom.db :as tdb]))

;; ---------------------------------------------------------------------------
;; Replay (mirrors the oracle's `replay`)

(defn- err-text [e] (str (py/exception-type e) ": " (py/exception-message e)))

(defn- result-repr
  "Python `repr` of an env-function result (`remove_app_permission` returns a
   tuple)."
  [r]
  (if (vector? r)
    (str "(" (str/join ", " (map py/py-repr r)) ")")
    (py/py-repr r)))

(defn- py-catch [f on-error]
  (try (f)
       (catch clojure.lang.ExceptionInfo e
         (if (py/py-exception? e) (on-error e) (throw e)))))

(defn replay-sequence [dom {:strs [id task env_calls calls assertions]}]
  (let [init (py-catch #(vector :ok ((:initial-world dom) (when task (get-in dom [:tasks task]))))
                       #(vector :err (err-text %)))]
    (if (= :err (first init))
      {"id" id "init_error" (second init)}
      (let [[w env-outputs]
            (reduce (fn [[w outs] c]
                      (py-catch #(let [{w' :world r :result} (tc/run-env-function-call w c)]
                                   [w' (conj outs {"result" (result-repr r) "error" nil})])
                                (fn [e] [(or (::agent/world (ex-data e)) w)
                                         (conj outs {"result" nil "error" (err-text e)})])))
                    [(second init) []] env_calls)
            [w outputs] (reduce (fn [[w outs] {:strs [name arguments requestor]}]
                                  (let [{w' :world :keys [content error]}
                                        (tc/respond w (if (= "user" requestor) :user :assistant) name arguments)]
                                    [w' (conj outs {"content" content "error" error})]))
                                [w []] calls)
            [w checks] (reduce (fn [[w acc] a]
                                 (py-catch #(let [{w' :world met :met} (tc/run-env-assertion w a)]
                                              [w' (conj acc met)])
                                           (fn [e] [w (conj acc (err-text e))])))
                               [w []] assertions)
            a (tdb/db-hash (:db w))
            u (tdb/db-hash (:user w))]
        (cond-> {"id" id "agent_db_hash" a "user_db_hash" u "db_hash" (str a "|" u)
                 "outputs" outputs}
          (seq env_calls) (assoc "env_outputs" env-outputs)
          (seq assertions) (assoc "assertions" checks))))))

(defn digest
  "One digest per replayed sequence (outputs, both hashes, env-call results,
   assertion results, or the initialization error)."
  [{:strs [outputs db_hash env_outputs assertions init_error]}]
  (pj/sha256-hex (pj/dumps [outputs db_hash env_outputs assertions init_error])))

(defn replay-digests [dom corpus]
  (into (sorted-map) (map (fn [s] [(get s "id") (digest (replay-sequence dom s))])) corpus))

(defn oracle-digests [results]
  (into (sorted-map) (map (fn [r] [(get r "id") (digest r)])) results))

(defn compare-results
  "Clojure replay vs oracle results: mismatching sequences with the first
   differing fields."
  [dom corpus oracle-results]
  (let [by-id (into {} (map (juxt #(get % "id") identity)) oracle-results)
        diffs (for [s corpus
                    :let [ours (replay-sequence dom s)
                          theirs (get by-id (get s "id"))]
                    :when (not= (digest ours) (some-> theirs digest))]
                {:id (get s "id")
                 :missing-oracle? (nil? theirs)
                 :fields (vec (for [k ["init_error" "agent_db_hash" "user_db_hash" "env_outputs" "assertions"]
                                    :when (not= (get ours k) (get theirs k))]
                                [k (get ours k) (get theirs k)]))
                 :calls (vec (take 3 (keep-indexed (fn [i [o t]] (when (not= o t) {:call i :seq-call (get-in s ["calls" i]) :ours o :theirs t}))
                                                   (map vector (get ours "outputs") (get theirs "outputs")))))})]
    {:sequences (count corpus)
     :calls (reduce + (map #(count (get % "calls")) corpus))
     :env-calls (reduce + (map #(count (get % "env_calls")) corpus))
     :assertions (reduce + (map #(count (get % "assertions")) corpus))
     :mismatched (count diffs)
     :diffs (vec (take 8 diffs))}))

;; ---------------------------------------------------------------------------
;; Gold corpus: every task's gold actions (both requestors) + its assertions

(defn gold-corpus [tasks]
  (vec (for [task tasks]
         {"id" (get task "id") "task" (get task "id")
          "calls" (mapv (fn [{:strs [name arguments requestor]}]
                          {"name" name "arguments" arguments "requestor" (or requestor "assistant")})
                        (get-in task ["evaluation_criteria" "actions"]))
          "assertions" (vec (get-in task ["evaluation_criteria" "env_assertions"]))})))

;; ---------------------------------------------------------------------------
;; Seeded fuzz corpus

(def ^:private phones ["555-123-2001" "555-123-2002" "555-123-2002" "555-123-2003" "555-123-2004"
                       "555-123-2005" "555-123-2009"])

(def ^:private line-phones
  {"C1001" ["555-123-2001" "555-123-2002" "555-123-2003"] "C1002" ["555-123-2004" "555-123-2005"]
   "C1003" ["555-123-2009"]})

(def ^:private customer-lines
  {"C1001" ["L1001" "L1002" "L1003"] "C1002" ["L1004" "L1005" "L1006" "L1007" "L1008"]
   "C1003" ["L1009"] "C1004" []})

(def ^:private user-tools
  ["check_status_bar" "check_network_status" "check_network_mode_preference" "set_network_mode_preference"
   "run_speed_test" "toggle_airplane_mode" "check_sim_status" "reseat_sim_card" "toggle_data"
   "toggle_roaming" "check_data_restriction_status" "toggle_data_saver_mode" "check_apn_settings"
   "set_apn_settings" "reset_apn_settings" "check_wifi_status" "toggle_wifi" "check_wifi_calling_status"
   "toggle_wifi_calling" "check_vpn_status" "connect_vpn" "disconnect_vpn" "check_installed_apps"
   "check_app_status" "check_app_permissions" "grant_app_permission" "can_send_mms" "reboot_device"
   "check_payment_request" "make_payment"])

(defn- generators
  "Seeded call generators shared by the fuzz and flow corpora."
  [dom ^java.util.Random rng]
  (let [p #(< (.nextDouble rng) %)
        pick (fn [xs] (let [xs (vec xs)] (nth xs (.nextInt rng (count xs)))))
        task-ids (vec (keys (:tasks dom)))
        cust #(if (p 0.9) (pick ["C1001" "C1001" "C1002" "C1003" "C1004"]) (pick ["C9999" 1001 nil]))
        line-for (fn [c] (if (and (p 0.85) (seq (customer-lines c)))
                           (pick (customer-lines c))
                           (pick ["L1002" "L1004" "L1009" "L9999" 2])))
        junk #(pick [5 nil true 1.5 [] {} ["x"] {"a" 1} "" -1])
        apn (fn []
              (cond
                (p 0.05) (junk)
                (p 0.5) (into (array-map)
                              (filter (fn [_] (p 0.5)))
                              [["apn_name" (pick ["internet" "internet" "broken"])]
                               ["reset_at_reboot" (pick [true false])]
                               ["mms_apn" (pick ["mms" "mms2" nil])]
                               ["mmsc_url" (pick ["http://mms.carrier.com/mms/wapenc" nil "http://x"])]
                               ["mms_proxy" (pick [nil "10.0.0.1"])]
                               ["mms_port" (pick [nil 8080 "80" 80.0])]])
                :else (into (array-map)
                            (filter (fn [_] (p 0.4)))
                            [["apn_name" (pick ["x" 5 "INTERNET" nil "broken"])]
                             ["reset_at_reboot" (pick ["yes" "no" "TRUE" 2 nil 1.0 " on " "f"])]
                             ["mms_port" (pick [1.5 "8 0" true " 12 " [] (apply str (repeat 60 "x"))])]
                             ["mms_apn" (pick [3 ["a"] "ok"])]
                             ["extra" (pick [1 "z"])]
                             ["mmsc_url" (pick [nil {} "u"])]])))
        user-call
        (fn []
          (let [tool (pick user-tools)
                args (case tool
                       "set_network_mode_preference"
                       {"mode" (cond (p 0.7) (pick ["4g_5g_preferred" "4g_only" "3g_only" "2g_only"])
                                     (p 0.6) (pick ["5g" "LTE" "4G_ONLY"])
                                     :else (junk))}
                       "set_apn_settings" {"apn_settings" (apn)}
                       ("check_app_status" "check_app_permissions")
                       {"app_name" (if (p 0.85) (pick ["messaging" "browser" "camera"]) (junk))}
                       "grant_app_permission"
                       {"app_name" (if (p 0.9) (pick ["messaging" "messaging" "browser" "camera"]) (junk))
                        "permission" (if (p 0.9) (pick ["sms" "storage" "phone" "network" "SMS" "camera"]) (junk))}
                       {})]
            {"name" tool "arguments" args "requestor" "user"}))
        agent-call
        (fn []
          (let [c (cust) l (line-for c)
                [tool args]
                (pick
                 [["get_customer_by_phone" {"phone_number" (if (p 0.85) (pick phones) (pick ["555-000-0000" 5]))}]
                  ["get_customer_by_id" {"customer_id" c}]
                  ["get_customer_by_name" {"full_name" (if (p 0.9) (pick ["John Smith" "john smith" "Sarah Johnson" "Nobody"]) (junk))
                                           "dob" (pick ["1985-06-15" "1990-11-22" "2000-01-01"])}]
                  ["get_details_by_id" {"id" (if (p 0.9)
                                               (pick ["C1001" "C1003" "L1002" "L1009" "D1001" "D1009" "B1001" "B1003"
                                                      "B1005" "P1001" "P1003" "L9999" "X1" "B00000001" ""])
                                               (junk))}]
                  ["suspend_line" {"customer_id" c "line_id" l "reason" "travel"}]
                  ["resume_line" {"customer_id" c "line_id" l}]
                  ["get_bills_for_customer" (cond-> {"customer_id" c}
                                              (p 0.6) (assoc "limit" (pick [12 1 2 0 -1 nil 1.5 "3" true 100])))]
                  ["send_payment_request" {"customer_id" c
                                           "bill_id" (pick ["B1001" "B1002" "B1003" "B1004" "B1005" "B1006"
                                                            "B1234321" "B00000001" 5])}]
                  ["get_data_usage" {"customer_id" c "line_id" l}]
                  ["enable_roaming" {"customer_id" c "line_id" l}]
                  ["disable_roaming" {"customer_id" c "line_id" l}]
                  ["transfer_to_human_agents" {"summary" (pick ["help" "I cannot fix the issue." 5])}]
                  ["refuel_data" {"customer_id" c "line_id" l
                                  "gb_amount" (if (p 0.85) (pick [2.0 1 0.5 3.3 10 1e-3 2.0 7.25])
                                                  (pick [0 -1 "2" nil true [] 0.0]))}]])]
            {"name" tool "arguments" args "requestor" "assistant"}))
        odd-call
        (fn []
          (pick [{"name" "no_such_tool" "arguments" {} "requestor" (pick ["user" "assistant"])}
                 {"name" "check_status_bar" "arguments" {} "requestor" "assistant"}
                 {"name" "get_customer_by_id" "arguments" {"customer_id" "C1001"} "requestor" "user"}
                 {"name" "check_sim_status" "arguments" {"extra" 1} "requestor" "user"}
                 {"name" "get_data_usage" "arguments" {"customer_id" "C1001"} "requestor" "assistant"}
                 {"name" "refuel_data" "arguments" {"customer_id" "C1001" "line_id" "L1002" "gb_amount" 1 "x" 2}
                  "requestor" "assistant"}
                 {"name" "check_app_status" "arguments" {} "requestor" "user"}
                 {"name" "toggle_data" "arguments" {"requestor" "x"} "requestor" "user"}
                 {"name" "get_customer_by_id" "arguments" {"tool_name" "x" "customer_id" "C1001"} "requestor" "assistant"}
                 {"name" "check_wifi_status" "arguments" {"self" 1} "requestor" "user"}
                 {"name" "set_data_usage" "arguments" {"customer_id" "C1001" "line_id" "L1002" "data_used_gb" 1.0}
                  "requestor" "assistant"}
                 {"name" "simulate_network_search" "arguments" {} "requestor" "user"}]))
        env-call
        (fn []
          (let [c (pick ["C1001" "C1001" "C1002" "C1003"])
                l (pick (customer-lines c))]
            (pick
             [{"env_type" "user" "func_name" "set_user_location" "arguments" {"abroad" (pick [true false])}}
              {"env_type" "user" "func_name" "turn_airplane_mode_on" "arguments" {}}
              {"env_type" "user" "func_name" "turn_airplane_mode_off" "arguments" {}}
              {"env_type" "user" "func_name" "unseat_sim_card" "arguments" {}}
              {"env_type" "user" "func_name" "lock_sim_card" "arguments" {"mode" (pick ["pin" "puk" "other"])}}
              {"env_type" "user" "func_name" "turn_data_off" "arguments" {}}
              {"env_type" "user" "func_name" "turn_data_on" "arguments" {}}
              {"env_type" "user" "func_name" "turn_roaming_on" "arguments" {}}
              {"env_type" "user" "func_name" "turn_roaming_off" "arguments" {}}
              {"env_type" "user" "func_name" "turn_data_saver_mode_on" "arguments" {}}
              {"env_type" "user" "func_name" "turn_data_saver_mode_off" "arguments" {}}
              {"env_type" "user" "func_name" "break_apn_settings" "arguments" {}}
              {"env_type" "user" "func_name" "break_apn_mms_setting" "arguments" {}}
              {"env_type" "user" "func_name" "set_wifi_calling"
               "arguments" (cond-> {"enabled" (pick [true false])} (p 0.5) (assoc "mms_over_wifi" (pick [true false])))}
              {"env_type" "user" "func_name" "break_vpn" "arguments" {}}
              {"env_type" "user" "func_name" "remove_app_permission"
               "arguments" {"app_name" (pick ["messaging" "browser" "camera"]) "permission" (pick ["sms" "storage" "network" "bogus"])}}
              {"env_type" "user" "func_name" "set_network_mode_preference"
               "arguments" {"mode" (pick ["2g_only" "3g_only" "4g_only" "4g_5g_preferred"])}}
              {"env_type" "user" "func_name" "simulate_network_search" "arguments" {}}
              {"env_type" "assistant" "func_name" "set_data_usage"
               "arguments" {"customer_id" c "line_id" l "data_used_gb" (pick [15.1 4.9 5.0 1000.0 0.0 20])}}
              {"env_type" "assistant" "func_name" "suspend_line_for_overdue_bill"
               "arguments" {"customer_id" c "line_id" l "new_bill_id" (pick ["B1234321" "B7777777"])
                            "contract_ended" (pick [true false])}}
              {"env_type" "assistant" "func_name" "enable_roaming" "arguments" {"customer_id" c "line_id" l}}
              {"env_type" "assistant" "func_name" "disable_roaming" "arguments" {"customer_id" c "line_id" l}}])))
        assertion
        (fn []
          (let [a (pick
                   [{"env_type" "user" "func_name" "assert_mobile_data_status" "arguments" {"expected_status" (pick [true false])}}
                    {"env_type" "user" "func_name" "assert_internet_speed"
                     "arguments" (pick [{"expected_speed" 200 "expected_desc" "excellent"} {"expected_speed" 5}
                                        {"expected_speed" 0 "expected_desc" "no connection"} {"expected_speed" 1.5 "expected_desc" "Poor"}])}
                    {"env_type" "user" "func_name" "assert_internet_not_excellent" "arguments" {}}
                    {"env_type" "user" "func_name" "assert_can_send_mms" "arguments" {"expected_status" (pick [true false])}}
                    {"env_type" "user" "func_name" "assert_service_status" "arguments" {"expected_status" (pick ["connected" "no_service" "searching" "bogus"])}}
                    {"env_type" "user" "func_name" "assert_airplane_mode_status" "arguments" {"expected_status" (pick [true false])}}
                    {"env_type" "user" "func_name" "assert_mobile_roaming_status" "arguments" {"expected_status" (pick [true false])}}
                    {"env_type" "user" "func_name" "assert_mobile_data_saver_mode_status" "arguments" {"expected_status" (pick [true false])}}
                    {"env_type" "user" "func_name" "assert_mobile_data_usage_exceeded" "arguments" {"expected_status" (pick [true false])}}
                    {"env_type" "assistant" "func_name" "assert_data_refueling_amount"
                     "arguments" {"customer_id" "C1001" "line_id" (pick ["L1002" "L1001"]) "expected_amount" (pick [2.0 0.0 1])}}
                    {"env_type" "assistant" "func_name" "assert_line_status"
                     "arguments" {"customer_id" "C1001" "line_id" "L1002" "expected_status" (pick ["Active" "Suspended"])}}
                    {"env_type" "assistant" "func_name" "assert_no_overdue_bill" "arguments" {"overdue_bill_id" (pick ["B1234321" "B1005" "B1001"])}}
                    {"env_type" "assistant" "func_name" "assert_overdue_bill_exists"
                     "arguments" {"customer_id" (pick ["C1001" "C1002"]) "overdue_bill_id" (pick ["B1234321" "B1005"])}}])]
            (cond-> a (p 0.2) (assoc "assert_value" false))))]
    {:p p :pick pick :junk junk :cust cust :line-for line-for :apn apn :user-call user-call
     :agent-call agent-call :odd-call odd-call :env-call env-call :assertion assertion :task-ids task-ids}))

(defn fuzz-corpus
  "`n` seeded sequences over initial states (default, any of the 2285 task
   states, or a random stack of initialization functions), 1-14 calls from
   both requestors (valid, wrongly typed and malformed arguments), and 0-4
   env assertions."
  [dom seed n]
  (let [rng (java.util.Random. (long seed))
        {:keys [p pick user-call agent-call odd-call env-call assertion task-ids]} (generators dom rng)]
    (vec (for [i (range n)]
           (let [start (.nextDouble rng)
                 task (when (< start 0.5) (pick task-ids))
                 env-calls (when (>= start 0.75)
                             (into [{"env_type" "user" "func_name" "set_user_info"
                                     "arguments" {"name" "Fuzz" "phone_number"
                                                  (cond (p 0.94) (pick phones)
                                                        (p 0.5) (pick ["555-123-1002" "555-123-1003"])
                                                        :else "555-000-0000")}}]
                                   (repeatedly (.nextInt rng 5) env-call)))
                 calls (vec (repeatedly (inc (.nextInt rng 14))
                                        #(let [r (.nextDouble rng)]
                                           (cond (< r 0.58) (user-call) (< r 0.94) (agent-call) :else (odd-call)))))
                 assertions (vec (repeatedly (.nextInt rng 5) assertion))]
             (cond-> {"id" (str "tc-fuzz-" seed "-" i) "calls" calls}
               task (assoc "task" task)
               (seq env-calls) (assoc "env_calls" env-calls)
               (seq assertions) (assoc "assertions" assertions)))))))

;; ---------------------------------------------------------------------------
;; Flow corpus: scenario templates reaching stateful paths the uniform fuzz
;; rarely hits (payments + sync, junk APN, SIM locks, draft bills, VPN).

(defn flow-corpus
  [dom seed n]
  (let [rng (java.util.Random. (long seed))
        {:keys [p pick user-call agent-call assertion]} (generators dom rng)
        u (fn [name & [args]] {"name" name "arguments" (or args {}) "requestor" "user"})
        ag (fn [name args] {"name" name "arguments" args "requestor" "assistant"})
        shuffle* (fn [xs] (let [al (java.util.ArrayList. ^java.util.Collection (vec xs))]
                            (java.util.Collections/shuffle al rng) (vec al)))
        noise (fn [] (vec (repeatedly (.nextInt rng 3) #(if (p 0.6) (user-call) (agent-call)))))
        flows
        {:payment
         (fn []
           (let [c (pick ["C1001" "C1001" "C1002" "C1003"])
                 bills (get {"C1001" ["B1001" "B1002" "B1003" "B1234321"] "C1002" ["B1004" "B1005"]
                             "C1003" ["B1006"]} c)
                 lines (customer-lines c)
                 overdue? (p 0.5)]
             {"env_calls" (cond-> [{"env_type" "user" "func_name" "set_user_info"
                                    "arguments" {"name" "Flow" "phone_number" (pick (line-phones c))}}]
                            overdue? (conj {"env_type" "assistant" "func_name" "suspend_line_for_overdue_bill"
                                            "arguments" {"customer_id" c "line_id" (pick lines) "new_bill_id" "B1234321"
                                                         "contract_ended" (p 0.5)}}))
              "calls" (vec (concat
                            (noise)
                            [(ag "get_bills_for_customer" {"customer_id" c})
                             (ag "send_payment_request" {"customer_id" c "bill_id" (pick bills)})
                             (u "check_payment_request")]
                            (shuffle* (concat [(u "make_payment") (u "check_payment_request")]
                                              (when (p 0.5) [(u "make_payment")])
                                              (when (p 0.5) [(ag "send_payment_request" {"customer_id" c "bill_id" (pick bills)})])
                                              (when (p 0.6) [(ag "resume_line" {"customer_id" c "line_id" (pick lines)})])
                                              (when (p 0.5) [(u "reboot_device")])
                                              (noise)))
                            [(ag "get_bills_for_customer" {"customer_id" c "limit" (pick [12 2])})
                             (u "check_status_bar")]))}))
         :junk-apn
         (fn []
           {"calls" (vec (concat
                          (noise)
                          [(u "set_apn_settings" {"apn_settings" (pick ["abc" 5 nil [] true 1.5])})]
                          (shuffle* (take (+ 2 (.nextInt rng 6))
                                          (shuffle* [(u "reset_apn_settings") (u "reboot_device") (u "can_send_mms")
                                                     (u "check_apn_settings") (u "toggle_data") (u "toggle_airplane_mode")
                                                     (u "reseat_sim_card") (u "toggle_roaming") (u "run_speed_test")
                                                     (u "set_network_mode_preference" {"mode" "3g_only"})
                                                     (u "check_status_bar")])))
                          (when (p 0.5) [(u "set_apn_settings" {"apn_settings" {"apn_name" "internet"}})
                                         (u "check_apn_settings") (u "can_send_mms")])))})
         :sim
         (fn []
           {"env_calls" [{"env_type" "user" "func_name" "set_user_info"
                          "arguments" {"name" "Flow" "phone_number" (pick phones)}}
                         (pick [{"env_type" "user" "func_name" "lock_sim_card" "arguments" {"mode" (pick ["pin" "puk"])}}
                                {"env_type" "user" "func_name" "unseat_sim_card" "arguments" {}}
                                {"env_type" "user" "func_name" "break_apn_settings" "arguments" {}}])]
            "calls" (vec (concat (shuffle* [(u "check_sim_status") (u "reseat_sim_card") (u "reboot_device")
                                            (u "check_network_status") (u "run_speed_test") (u "reset_apn_settings")])
                                 (noise) [(u "check_status_bar")]))})
         :refuel
         (fn []
           (let [c (pick ["C1002" "C1003" "C1001"])
                 l (pick (customer-lines c))]
             {"env_calls" (cond-> [{"env_type" "user" "func_name" "set_user_info"
                                    "arguments" {"name" "Flow" "phone_number" (pick (line-phones c))}}]
                            (p 0.7) (conj {"env_type" "assistant" "func_name" "set_data_usage"
                                           "arguments" {"customer_id" c "line_id" l
                                                        "data_used_gb" (pick [1000.0 15.1 999.5 5.0])}}))
              "calls" (vec (concat
                            [(u "run_speed_test") (ag "get_data_usage" {"customer_id" c "line_id" l})]
                            (repeatedly (inc (.nextInt rng 3))
                                        #(ag "refuel_data" {"customer_id" c "line_id" (pick (customer-lines c))
                                                            "gb_amount" (pick [2.0 1 0.5 3.3 100])}))
                            (shuffle* [(ag "get_bills_for_customer" {"customer_id" c "limit" (pick [1 2 12 nil -1])})
                                       (ag "get_details_by_id" {"id" (pick ["B00000001" "B00000002" "B1003"])})
                                       (ag "get_data_usage" {"customer_id" c "line_id" l})
                                       (u "run_speed_test") (u "check_status_bar")])
                            (noise)))}))
         :vpn
         (fn []
           {"env_calls" (cond-> [] (p 0.6) (conj {"env_type" "user" "func_name" "break_vpn" "arguments" {}}))
            "calls" (vec (concat (shuffle* [(u "check_vpn_status") (u "connect_vpn") (u "run_speed_test")
                                            (u "disconnect_vpn") (u "connect_vpn") (u "check_vpn_status")
                                            (u "toggle_airplane_mode") (u "toggle_data_saver_mode")
                                            (u "run_speed_test")])
                                 (noise)))})}]
    (vec (for [i (range n)]
           (let [kind (pick [:payment :payment :junk-apn :sim :refuel :vpn])
                 s ((get flows kind))
                 assertions (vec (repeatedly (.nextInt rng 4) assertion))]
             (cond-> (assoc s "id" (str "tc-flow-" seed "-" i "-" (name kind)))
               (empty? (get s "env_calls")) (dissoc "env_calls")
               (seq assertions) (assoc "assertions" assertions)))))))

;; ---------------------------------------------------------------------------
;; Grading corpus: perturbed gold trajectories of the base split, graded by
;; `core/grade` here and by upstream's evaluators in the oracle (`grade`).

(defn grade-corpus [dom seed]
  (let [rng (java.util.Random. (long seed))
        {:keys [user-call agent-call]} (generators dom rng)
        noise #(if (< (.nextDouble rng) 0.6) (user-call) (agent-call))
        insert (fn [xs x] (let [i (.nextInt rng (inc (count xs)))] (vec (concat (subvec xs 0 i) [x] (subvec xs i)))))]
    (vec (for [task (map #(get-in dom [:tasks %]) (get-in dom [:splits "base"]))
               :let [id (get task "id")
                     gold (get (first (gold-corpus [task])) "calls")]
               [variant calls] [["gold" gold]
                                ["drop-last" (vec (butlast gold))]
                                ["drop-first" (vec (rest gold))]
                                ["reversed" (vec (reverse gold))]
                                ["gold+noise" (reduce insert gold (repeatedly (inc (.nextInt rng 3)) noise))]
                                ["noise" (vec (repeatedly 3 noise))]]]
           {"id" (str id "#" variant) "task" id "calls" calls}))))

(defn trajectory-episode
  "An episode whose trajectory is the tool calls of `calls` (each its own
   message, answered by the environment), ending with an agent stop."
  [dom task calls]
  (let [[w msgs] (reduce (fn [[w msgs] [i {:strs [name arguments requestor]}]]
                           (let [role (if (= "user" requestor) :user :assistant)
                                 {w' :world :keys [content error]} ((:respond dom) w role name arguments)]
                             [w' (conj msgs
                                       {:role role :content nil
                                        :tool-calls [{:id (str "g" i) :name name :arguments arguments}]}
                                       {:role :tool :id (str "g" i) :content content :error error :requestor role})]))
                         [((:initial-world dom) task) []]
                         (map-indexed vector calls))]
    {:messages (conj msgs {:role :assistant :content "###STOP###"})
     :termination :agent-stop :world w}))

(defn write-json! [path x] (spit path (pj/dumps x)))
