(ns dvergr.benchmarks.tau2.telecom.device
  "The user side of tau2 `telecom`: `TelecomUserTools` over the simulated
   phone (`MockPhoneAttributes`) and the user's surroundings.

   Same calling convention as `telecom.agent`: `(f world args) -> {:world
   :result}`, Python exceptions raised, `agent/raise-w` for mutations that
   happen before an exception.

   Upstream quirks reproduced on purpose:
   - `set_network_mode_preference` stores a non-string `mode` unvalidated,
     runs a network search (falling back to 4G) and then fails on `.value`;
     the junk value stays on the device.
   - `set_apn_settings` stores a non-dict argument unvalidated; every later
     access to the APN (network search, reboot, MMS check) raises
     AttributeError until a valid dict replaces it.
   - `default_vpn_details` is one shared object: `break_vpn` degrades it, so
     every later `connect_vpn` in the same world gets a POOR server
     (`:vpn-performance` in the world). Upstream the object is a class
     attribute shared by the whole process; the port models a fresh process.

   Upstream: `src/tau2/domains/telecom/user_tools.py`, `user_data_model.py`."
  (:require [clojure.string :as str]
            [dvergr.benchmarks.tau2.python :as py]
            [dvergr.benchmarks.tau2.telecom.agent :as agent :refer [raise-w]]
            [dvergr.benchmarks.tau2.telecom.db :as tdb]))

(defn- dev [w k] (get-in w [:user "device" k]))
(defn- sur [w k] (get-in w [:user "surroundings" k]))
(defn- set-dev [w & kvs] (update-in w [:user "device"] #(apply assoc % kvs)))
(defn- set-sur [w & kvs] (update-in w [:user "surroundings"] #(apply assoc % kvs)))
(defn- ok [w r] {:world w :result r})

(defn- attr
  "Attribute access on a value that should be a model (map)."
  [w x k]
  (if (map? x) (get x k)
      (raise-w w "AttributeError" (str "'" (py/type-name x) "' object has no attribute '" k "'"))))

(defn- enum-value
  "`x.value` of an enum field; non-strings are junk stored unvalidated."
  [w x]
  (if (string? x)
    x
    (raise-w w "AttributeError" (str "'" (py/type-name x) "' object has no attribute 'value'"))))

;; ---------------------------------------------------------------------------
;; Status bar and core simulation

(def ^:private signal-icons
  {"none" "📵 No Signal" "poor" "📶¹ Poor" "fair" "📶² Fair" "good" "📶³ Good"
   "excellent" "📶⁴ Excellent"})

(defn status-bar [w]
  (let [d (get-in w [:user "device"])
        tech (get d "network_technology_connected")
        parts (concat
               (if (get d "airplane_mode")
                 ["✈️ Airplane Mode"]
                 (concat [(get signal-icons (get d "network_signal_strength") "📵 No Signal")]
                         (when (not= "none" tech) [tech])
                         (if (and (get d "data_enabled") (not= "none" tech))
                           (cons "📱 Data Enabled" (when (get d "data_saver_mode") ["🔽 Data Saver"]))
                           ["📵 Data Disabled"])))
               (when (and (get d "wifi_enabled") (get d "wifi_connected"))
                 [(if (py/truthy? (get d "wifi_ssid"))
                    (str "📡 Connected to " (get d "wifi_ssid"))
                    "📡 Enabled")])
               (when (get d "vpn_connected") ["🔒 VPN Connected"])
               [(str "🔋 " (py/py-str (get d "battery_level")) "%")])]
    (str/join " | " parts)))

(defn- with-bar [w s] (str s "\nStatus Bar: " (status-bar w)))

(defn sim-status [w]
  (if (dev w "sim_card_missing") "missing" (dev w "sim_card_status")))

(defn- no-service [w]
  (set-dev w "network_connection_status" "no_service" "network_technology_connected" "none"
           "network_signal_strength" "none"))

(defn simulate-network-search
  "`simulate_network_search`, including its partial effects when the APN
   settings are junk (the check raises after the earlier assignments)."
  [w]
  (let [signal #(get (sur w "signal_strength") % "none")
        w (if (= "active" (sim-status w))
            (let [w (set-dev w "network_connection_status" "connected")
                  pref (dev w "network_mode_preference")
                  five (signal "5G")]
              (cond
                (and (= "4g_5g_preferred" pref) (= "none" five))
                (set-dev w "network_technology_connected" "4G" "network_signal_strength" (signal "4G"))
                (= "4g_5g_preferred" pref)
                (set-dev w "network_technology_connected" "5G" "network_signal_strength" five)
                (= "3g_only" pref)
                (set-dev w "network_technology_connected" "3G" "network_signal_strength" (signal "3G"))
                (= "2g_only" pref)
                (set-dev w "network_technology_connected" "2G" "network_signal_strength" (signal "2G"))
                ;; 4g_only and the fallback for anything else
                :else (set-dev w "network_technology_connected" "4G" "network_signal_strength" (signal "4G"))))
            (no-service w))
        w (if (dev w "airplane_mode") (no-service w) w)
        w (if (= "broken" (attr w (dev w "active_apn_settings") "apn_name")) (no-service w) w)
        w (if-not (sur w "line_active") (no-service w) w)]
    w))

(defn mobile-data-working? [w]
  (cond
    (or (dev w "airplane_mode") (= "none" (dev w "network_signal_strength"))) false
    (= "no_service" (dev w "network_connection_status")) false
    (and (py/truthy? (sur w "is_abroad"))
         (or (not (dev w "roaming_enabled")) (not (sur w "roaming_allowed")))) false
    (not (dev w "data_enabled")) false
    (sur w "mobile_data_usage_exceeded") false
    :else true))

(defn speed-test
  "`_run_speed_test`: `[speed description]`, speed nil without a connection."
  [w]
  (if-not (mobile-data-working? w)
    [nil "No Connection"]
    (let [vpn (dev w "vpn_details")
          base (if (and (dev w "vpn_connected") vpn (= "poor" (get vpn "server_performance"))) 0.1 1.0)
          base (if (dev w "data_saver_mode") (* base 0.2) base)
          [lo hi] (get {"2G" [0.1 0.4] "3G" [1.0 5.0] "4G" [10.0 100.0] "5G" [50.0 500.0] "none" [0.0 0.0]}
                       (dev w "network_technology_connected") [0.0 0.0])
          sf (get {"poor" 0.2 "fair" 0.5 "good" 0.8 "excellent" 1.0 "none" 0.0}
                  (dev w "network_signal_strength") 0.0)
          speed (py/py-round (* (* (/ (+ lo hi) 2.0) sf) base) 2)]
      [speed (cond (< speed 1) "Very Poor" (< speed 5) "Poor" (< speed 25) "Fair"
                   (< speed 100) "Good" :else "Excellent")])))

(defn can-send-mms? [w]
  (cond
    (not (mobile-data-working? w)) false
    (= "2G" (dev w "network_technology_connected")) false
    (and (dev w "wifi_calling_enabled") (py/truthy? (dev w "wifi_calling_mms_over_wifi"))) false
    (nil? (attr w (dev w "active_apn_settings") "mmsc_url")) false
    :else (let [app (get (dev w "app_statuses") "messaging")]
            (boolean (and app (get-in app ["permissions" "storage"]) (get-in app ["permissions" "sms"]))))))

(defn- dict-get
  "`dict.get(key)`: unhashable keys raise TypeError."
  [m k]
  (if (or (map? k) (sequential? k) (set? k))
    (py/raise "TypeError" (str "unhashable type: '" (py/type-name k) "'"))
    (when (string? k) (get m k))))

(defn- toggle-airplane [w]
  (let [was-on (dev w "airplane_mode")
        w (set-dev w "airplane_mode" (not was-on))
        w (if was-on
            (cond-> (set-dev w "network_connection_status" "searching")
              (dev w "wifi_enabled") (set-dev "wifi_connected" false "wifi_ssid" nil "wifi_signal_strength" "none"))
            (cond-> (set-dev w "wifi_connected" false "wifi_ssid" nil "wifi_signal_strength" "none")
              (dev w "vpn_connected") (set-dev "vpn_connected" false "vpn_details" nil)))]
    (simulate-network-search w)))

(defn- vpn-details [w]
  (array-map "server_address" "192.168.1.1" "protocol" "OpenVPN"
             "server_performance" (:vpn-performance w "excellent")))

(defn- connect-vpn* [w]
  (if (dev w "vpn_connected")
    [w nil]
    [(set-dev w "vpn_connected" true "vpn_details" (vpn-details w)) true]))

(defn- perm-names [perms] (for [[k v] perms :when (py/truthy? v)]
    (.toLowerCase ^String (str/replace k "_" " ") java.util.Locale/ROOT)))

;; ---------------------------------------------------------------------------
;; APNSettings(**d) validation (pydantic 2.13, lax mode, extra=forbid)

(def ^:private docs-version "2.13")

(defn- truncated-repr [x]
  (let [r (py/py-repr x)
        cps (vec (.toArray (.codePoints ^String r)))
        n (count cps)
        s (fn [xs] (String. (int-array xs) 0 (count xs)))]
    (if (> n 50) (str (s (subvec cps 0 25)) "..." (s (subvec cps (- n 24)))) r)))

(defn- v-bool [x]
  (cond
    (boolean? x) [:ok x]
    (and (integer? x) (#{0 1} (long x))) [:ok (= 1 (long x))]
    (and (float? x) (#{0.0 1.0} (double x))) [:ok (= 1.0 (double x))]
    (string? x) (let [t (.toLowerCase ^String x java.util.Locale/ROOT)]
                  (cond (#{"1" "on" "t" "true" "y" "yes"} t) [:ok true]
                        (#{"0" "off" "f" "false" "n" "no"} t) [:ok false]
                        :else [:err "bool_parsing" "Input should be a valid boolean, unable to interpret input"]))
    (number? x) [:err "bool_parsing" "Input should be a valid boolean, unable to interpret input"]
    :else [:err "bool_type" "Input should be a valid boolean"]))

(defn- v-opt-str [x]
  (if (or (nil? x) (string? x)) [:ok x] [:err "string_type" "Input should be a valid string"]))

(defn- v-opt-int [x]
  (cond
    (nil? x) [:ok nil]
    (boolean? x) [:ok (if x 1 0)]
    (integer? x) [:ok x]
    (float? x) (let [d (double x)]
                 (cond (or (Double/isNaN d) (Double/isInfinite d)) [:err "finite_number" "Input should be a finite number"]
                       (not= d (Math/floor d)) [:err "int_from_float" "Input should be a valid integer, got a number with a fractional part"]
                       :else [:ok (long d)]))
    (string? x) (let [t (str/trim x)]
                  (if (re-matches #"[+-]?\d(?:_?\d)*(?:\.0+)?" t)
                    [:ok (py/py-int (str/replace t #"\.0+$" ""))]
                    [:err "int_parsing" "Input should be a valid integer, unable to parse string as an integer"]))
    :else [:err "int_type" "Input should be a valid integer"]))

(defn- v-apn-name [x]
  (if (#{"internet" "broken"} x) [:ok x] [:err "enum" "Input should be 'internet' or 'broken'"]))

(def ^:private apn-validators
  [["apn_name" v-apn-name] ["reset_at_reboot" v-bool] ["mms_apn" v-opt-str] ["mmsc_url" v-opt-str]
   ["mms_proxy" v-opt-str] ["mms_port" v-opt-int]])

(defn apn-settings
  "`APNSettings(**d)`: the model, or raise the ValidationError text."
  [d]
  (let [results (for [[k v] apn-validators :when (contains? d k)] [k (get d k) (v (get d k))])
        known (set (map first apn-validators))
        errors (concat (for [[k input [tag type msg]] results :when (= :err tag)] [k type msg input])
                       (for [[k input] d :when (not (known k))]
                         [k "extra_forbidden" "Extra inputs are not permitted" input]))]
    (if (seq errors)
      (let [n (count errors)]
        (py/raise "ValidationError"
                  (str n " validation error" (when (not= 1 n) "s") " for APNSettings\n"
                       (str/join "\n" (for [[k type msg input] errors]
                                        (str k "\n  " msg " [type=" type ", input_value=" (truncated-repr input)
                                             ", input_type=" (py/type-name input) "]\n"
                                             "    For further information visit https://errors.pydantic.dev/"
                                             docs-version "/v/" type))))))
      (reduce (fn [m [k _ [_ v]]] (assoc m k v)) tdb/default-apn results))))

;; ---------------------------------------------------------------------------
;; Tools

(defn check-status-bar [w _] (ok w (str "Status Bar: " (status-bar w))))

(defn check-network-status [w _]
  (let [d (get-in w [:user "device"])]
    (ok w (str/join "\n"
                    (cond-> [(str "Airplane Mode: " (if (get d "airplane_mode") "ON" "OFF"))
                             (str "SIM Card Status: " (sim-status w))
                             (str "Cellular Connection: " (get d "network_connection_status"))
                             (str "Cellular Signal: " (get d "network_signal_strength"))
                             (str "Cellular Network Type: " (get d "network_technology_connected"))
                             (str "Mobile Data Enabled: " (if (get d "data_enabled") "Yes" "No"))
                             (str "Data Roaming Enabled: " (if (get d "roaming_enabled") "Yes" "No"))
                             (str "Wi-Fi Radio: " (if (get d "wifi_enabled") "ON" "OFF"))
                             (str "Wi-Fi Connected: " (if (get d "wifi_connected") "Yes" "No"))]
                      (get d "wifi_connected") (conj (str "Connected Wi-Fi Network: " (py/py-str (get d "wifi_ssid")))))))))

(defn check-network-mode-preference [w _]
  (ok w (str "Network Mode Preference: " (enum-value w (dev w "network_mode_preference")))))

(def ^:private network-modes ["4g_5g_preferred" "4g_only" "3g_only" "2g_only"])

(defn set-network-mode-preference [w {:strs [mode]}]
  (cond
    (and (string? mode) (not (some #{mode} network-modes)))
    (ok w (with-bar w (str "Failed to set network mode: '" mode "' is not a valid option. Please use one of: "
                           (str/join ", " network-modes))))
    :else
    (let [w (simulate-network-search (set-dev w "network_mode_preference" mode))]
      (if (nil? mode)
        (ok w (with-bar w (str "Failed to set network mode: 'None' is not a valid option. Please use one of: "
                               (str/join ", " network-modes))))
        (ok w (with-bar w (str "Preferred Network Mode set to: " (enum-value w mode))))))))

(defn run-speed-test [w _]
  (let [[speed desc] (speed-test w)]
    (ok w (if (nil? speed)
            (str "Speed test failed: " desc ".")
            (str "Speed Test Result: " (py/format-fixed speed 2) " Mbps (" desc "). "
                 (case desc
                   "Very Poor" "Connection is very slow. Basic web browsing might be difficult."
                   "Poor" "Connection is slow. Web browsing may be sluggish, streaming difficult."
                   "Fair" "Connection is okay for web browsing and some standard definition streaming."
                   "Good" "Connection is good for most activities, including HD streaming."
                   "Excellent" "Connection is very fast."
                   ""))))))

(defn toggle-airplane-mode [w _]
  (let [w (toggle-airplane w)]
    (ok w (with-bar w (str "Airplane Mode is now " (if (dev w "airplane_mode") "ON" "OFF") ".")))))

(defn check-sim-status [w _]
  (ok w (case (sim-status w)
          "active" "Your SIM card is active and working."
          "missing" "No SIM card detected in the phone."
          "locked_pin" "The SIM card is locked with a PIN code."
          "locked_puk" "The SIM card is locked with a PUK code.")))

(defn reseat-sim-card [w _]
  (let [w (simulate-network-search (set-dev w "sim_card_missing" false))]
    (ok w (with-bar w "SIM card re-seated successfully."))))

(defn toggle-data [w _]
  (let [on (not (dev w "data_enabled"))
        w (simulate-network-search (set-dev w "data_enabled" on))]
    (ok w (with-bar w (str "Mobile Data is now " (if on "ON" "OFF") ".")))))

(defn toggle-roaming [w _]
  (let [on (not (dev w "roaming_enabled"))
        w (simulate-network-search (set-dev w "roaming_enabled" on))]
    (ok w (with-bar w (str "Data Roaming is now " (if on "ON" "OFF") ".")))))

(defn check-data-restriction-status [w _]
  (ok w (if (dev w "data_saver_mode") "Data Saver mode is ON (limits data usage)." "Data Saver mode is OFF.")))

(defn toggle-data-saver-mode [w _]
  (let [on (not (dev w "data_saver_mode"))
        w (set-dev w "data_saver_mode" on)]
    (ok w (with-bar w (str "Data Saver Mode is now " (if on "ON" "OFF") ".")))))

(defn check-apn-settings [w _]
  (let [apn (dev w "active_apn_settings")]
    (when-not (map? apn) (attr w apn "model_copy"))
    (ok w (str "Current APN Name: " (get apn "apn_name")
               "\nMMSC URL (for picture messages): " (if (py/truthy? (get apn "mmsc_url")) (get apn "mmsc_url") "Not Set")
               "\n(These are technical settings, usually best left unchanged.)"))))

(defn set-apn-settings [w {:strs [apn_settings]}]
  (let [apn (if (map? apn_settings) (apn-settings apn_settings) apn_settings)
        w (set-dev w "active_apn_settings" apn)
        status (str "APN settings set to: " (attr w apn "apn_name"))
        w (simulate-network-search w)]
    (ok w (with-bar w status))))

(defn reset-apn-settings [w _]
  (let [apn (dev w "active_apn_settings")
        _ (attr w apn "reset_at_reboot")
        w (set-dev w "active_apn_settings" (assoc apn "reset_at_reboot" true))
        w (simulate-network-search w)]
    (ok w (with-bar w "APN settings will reset at reboot."))))

(defn check-wifi-status [w _]
  (ok w (cond
          (not (dev w "wifi_enabled")) "Wi-Fi is turned OFF."
          (dev w "wifi_connected") (str "Wi-Fi is ON and connected to '" (py/py-str (dev w "wifi_ssid"))
                                        "'. Signal strength: " (dev w "wifi_signal_strength") ".")
          :else "Wi-Fi is ON but not connected to any network.")))

(defn toggle-wifi [w _]
  (if (dev w "airplane_mode")
    (ok w (with-bar w "Cannot change Wi-Fi settings while Airplane Mode is ON."))
    (let [on (not (dev w "wifi_enabled"))
          w (cond-> (set-dev w "wifi_enabled" on)
              (not on) (set-dev "wifi_connected" false "wifi_ssid" nil "wifi_signal_strength" "none"))]
      (ok w (with-bar w (str "Wi-Fi is now " (if on "ON" "OFF") "."))))))

(defn check-wifi-calling-status [w _]
  (ok w (str "Wi-Fi Calling is currently turned " (if (dev w "wifi_calling_enabled") "ON" "OFF") ".")))

(defn toggle-wifi-calling [w _]
  (let [on (not (dev w "wifi_calling_enabled"))
        w (set-dev w "wifi_calling_enabled" on)]
    (ok w (with-bar w (str "Wi-Fi Calling is now " (if on "ON" "OFF") ".")))))

(defn- vpn-details-repr [d]
  (str "{'server_address': " (py/py-repr (get d "server_address"))
       ", 'protocol': " (py/py-repr (get d "protocol"))
       ", 'server_performance': <PerformanceLevel." (str/upper-case (get d "server_performance"))
       ": '" (get d "server_performance") "'>}"))

(defn check-vpn-status [w _]
  (ok w (cond
          (dev w "vpn_connected")
          (if-let [d (dev w "vpn_details")]
            (str "VPN is ON and connected. Details: " (vpn-details-repr d))
            "VPN is ON and connected (no specific details available).")
          (dev w "vpn_enabled_setting") "VPN is turned ON in settings, but currently not connected."
          :else "VPN is turned OFF.")))

(defn connect-vpn [w _]
  (let [[w connected] (connect-vpn* w)]
    (ok w (if (nil? connected) "VPN already connected." (with-bar w "VPN connected successfully.")))))

(defn disconnect-vpn [w _]
  (let [was (dev w "vpn_connected")
        w (if was (set-dev w "vpn_connected" false "vpn_details" nil) w)]
    (ok w (with-bar w (if was "VPN disconnected successfully." "No active VPN connection to disconnect.")))))

(defn check-installed-apps [w _]
  (ok w (str "The following apps are installed on the phone: " (str/join ", " (keys (dev w "app_statuses"))))))

(defn check-app-status [w {:strs [app_name]}]
  (let [app (dict-get (dev w "app_statuses") app_name)]
    (ok w (if-not app
            (str "App '" (py/py-str app_name) "' not found on this phone.")
            (let [perms (perm-names (get app "permissions"))]
              (str/join "\n" (concat [(str "Status for App: " app_name)]
                                     (if (empty? perms)
                                       [" - Permissions: None granted."]
                                       (cons " - Permissions Granted:" (map #(str "   - " %) perms))))))))))

(defn check-app-permissions [w {:strs [app_name]}]
  (let [app (dict-get (dev w "app_statuses") app_name)]
    (ok w (if-not app
            (str "App '" (py/py-str app_name) "' not found on this phone.")
            (let [perms (perm-names (get app "permissions"))]
              (if (empty? perms)
                (str "App '" app_name "' currently has no permissions granted.")
                (str "App '" app_name "' has permission for: " (str/join ", " perms) ".")))))))

(defn grant-app-permission [w {:strs [app_name permission]}]
  (let [app (dict-get (dev w "app_statuses") app_name)
        permission (if (string? permission)
                     (.toLowerCase ^String permission java.util.Locale/ROOT)
                     (agent/attribute-error permission "lower"))
        available (vec (keys (get app "permissions")))
        [w success msg]
        (cond
          (nil? app) [w false (str "App '" (py/py-str app_name) "' not found. Cannot grant permission.")]
          (not (some #{permission} available))
          [w false (str "Permission '" permission "' not tracked for app '" app_name
                        "', available permissions: " (py/py-repr available))]
          :else [(update-in w [:user "device" "app_statuses" app_name "permissions"] assoc permission true)
                 true (str "Permission '" permission "' granted to app '" app_name "'.")])]
    (ok w (with-bar w (str (if success "Success. " "Error. ") msg)))))

(defn can-send-mms [w _]
  (ok w (if (can-send-mms? w)
          "Your messaging app can send MMS messages."
          "Your messaging app cannot send MMS messages.")))

(defn reboot-device [w _]
  (let [apn (dev w "active_apn_settings")
        reset? (attr w apn "reset_at_reboot")
        w (if reset? (set-dev w "active_apn_settings" tdb/default-apn) w)
        w (simulate-network-search (set-dev w "network_connection_status" "searching"))]
    (ok w (with-bar w (str/join "\n" (concat (when reset? ["Resetting APN settings..."])
                                             ["Restarting network services..."]))))))

(defn check-payment-request [w _]
  (ok w (if-let [pr (sur w "payment_request")]
          (str "You have a payment request for bill " (get pr "bill_id") " of "
               (py/py-str (get pr "amount_due")) " USD.")
          "No payment request has been made.")))

(defn make-payment [w _]
  (if-let [pr (sur w "payment_request")]
    (ok (set-sur w "payment_request" (assoc pr "paid" true))
        (str "Payment of " (py/py-str (get pr "amount_due")) " USD has been made for bill " (get pr "bill_id") "."))
    (ok w "You do not have a payment request.")))

;; ---------------------------------------------------------------------------
;; Initialization-only functions and assertions

(defn set-user-info [w {:strs [name phone_number]}]
  (ok (set-sur w "name" name "phone_number" phone_number) nil))

(defn set-user-location [w {:strs [abroad]}] (ok (set-sur w "is_abroad" abroad) nil))

(defn turn-airplane-mode-on [w _]
  (let [w (toggle-airplane w) w (if (dev w "airplane_mode") w (toggle-airplane w))]
    (ok w "Airplane Mode is now ON.")))

(defn turn-airplane-mode-off [w _]
  (let [w (toggle-airplane w) w (if (dev w "airplane_mode") (toggle-airplane w) w)]
    (ok w "Airplane Mode is now OFF.")))

(defn unseat-sim-card [w _]
  (ok (simulate-network-search (set-dev w "sim_card_missing" true)) "SIM card un-seated successfully."))

(defn lock-sim-card [w {:strs [mode]}]
  (let [w (case mode "pin" (set-dev w "sim_card_status" "locked_pin")
                "puk" (set-dev w "sim_card_status" "locked_puk") w)]
    (ok (simulate-network-search w) (str "SIM card locked successfully in " (py/py-str mode) " mode."))))

(defn- toggle-flag [w k search?]
  (let [w (set-dev w k (not (dev w k)))] (if search? (simulate-network-search w) w)))

(defn turn-data-on [w _] (ok (set-dev w "data_enabled" true) "Data connection restored."))

(defn turn-data-off [w _]
  (let [w (toggle-flag w "data_enabled" true) w (if (dev w "data_enabled") (toggle-flag w "data_enabled" true) w)]
    (ok w "Data connection broken.")))

(defn turn-roaming-on [w _]
  (let [w (toggle-flag w "roaming_enabled" true) w (if (dev w "roaming_enabled") w (toggle-flag w "roaming_enabled" true))]
    (ok w "Data Roaming is now ON.")))

(defn turn-roaming-off [w _]
  (let [w (toggle-flag w "roaming_enabled" true) w (if (dev w "roaming_enabled") (toggle-flag w "roaming_enabled" true) w)]
    (ok w "Data Roaming is now OFF.")))

(defn turn-data-saver-mode-on [w _]
  (let [w (toggle-flag w "data_saver_mode" false) w (if (dev w "data_saver_mode") w (toggle-flag w "data_saver_mode" false))]
    (ok w "Data Saver Mode is now ON.")))

(defn turn-data-saver-mode-off [w _]
  (let [w (toggle-flag w "data_saver_mode" false) w (if (dev w "data_saver_mode") (toggle-flag w "data_saver_mode" false) w)]
    (ok w "Data Saver Mode is now OFF.")))

(defn break-apn-settings [w _]
  (let [apn (dev w "active_apn_settings")
        _ (attr w apn "apn_name")]
    (ok (simulate-network-search (set-dev w "active_apn_settings" (assoc apn "apn_name" "broken")))
        "APN settings broken. Please call reset_apn_settings() to fix.")))

(defn break-apn-mms-setting [w _]
  (let [apn (dev w "active_apn_settings")
        _ (attr w apn "mmsc_url")]
    (ok (set-dev w "active_apn_settings" (assoc apn "mmsc_url" nil))
        "APN MMS setting broken. Please call reset_apn_settings() to fix.")))

(defn set-wifi-calling [w {:strs [enabled mms_over_wifi]}]
  (let [w (if (py/py-eq (dev w "wifi_calling_enabled") enabled) w (toggle-flag w "wifi_calling_enabled" false))
        w (if (some? mms_over_wifi) (set-dev w "wifi_calling_mms_over_wifi" mms_over_wifi) w)]
    (ok w (str "Wi-Fi Calling is now " (if (py/truthy? enabled) "ON" "OFF") "."
               (when (some? mms_over_wifi)
                 (str "\nMMS over Wi-Fi is now " (if (py/truthy? mms_over_wifi) "ON" "OFF") "."))))))

(defn break-vpn [w _]
  (let [[w _] (connect-vpn* w)
        w (-> (assoc w :vpn-performance "poor")
              (update-in [:user "device" "vpn_details"] assoc "server_performance" "poor"))]
    (ok w "VPN connection broken.")))

(defn remove-app-permission [w {:strs [app_name permission]}]
  (let [app (dict-get (dev w "app_statuses") app_name)
        permission (if (string? permission)
                     (.toLowerCase ^String permission java.util.Locale/ROOT)
                     (agent/attribute-error permission "lower"))]
    (cond
      (nil? app) (ok w [false (str "App '" (py/py-str app_name) "' not found. Cannot remove permission.")])
      (not (contains? (get app "permissions") permission))
      (ok w [false (str "Permission '" permission "' not tracked for app '" app_name "'.")])
      :else (ok (update-in w [:user "device" "app_statuses" app_name "permissions"] assoc permission false)
                [true (str "Permission '" permission "' removed from app '" app_name "'.")]))))

(defn simulate-network-search-fn [w _] (ok (simulate-network-search w) nil))

(defn assert-airplane-mode-status [w {:strs [expected_status]}]
  (ok w (py/py-eq (dev w "airplane_mode") expected_status)))

(def ^:private network-statuses #{"connected" "searching" "no_service" "emergency_only"})

(defn assert-service-status [w {:strs [expected_status]}]
  (when-not (and (string? expected_status) (network-statuses expected_status))
    (py/raise "ValueError" (str (py/py-repr expected_status) " is not a valid NetworkStatus")))
  (ok w (= (dev w "network_connection_status") expected_status)))

(defn assert-mobile-data-status [w {:strs [expected_status]}]
  (ok w (py/py-eq (mobile-data-working? w) expected_status)))

(defn assert-mobile-roaming-status [w {:strs [expected_status]}]
  (ok w (py/py-eq (dev w "roaming_enabled") expected_status)))

(defn assert-mobile-data-saver-mode-status [w {:strs [expected_status]}]
  (ok w (py/py-eq (dev w "data_saver_mode") expected_status)))

(defn assert-internet-speed [w {:strs [expected_speed expected_desc]}]
  (let [[speed desc] (speed-test w)
        speed (or speed 0.0)]
    (ok w (if (nil? expected_desc)
            (py/py-compare :ge speed expected_speed)
            (and (py/py-compare :ge speed expected_speed)
                 (= (.toLowerCase ^String desc java.util.Locale/ROOT)
                    (.toLowerCase ^String expected_desc java.util.Locale/ROOT)))))))

(defn assert-internet-not-excellent [w _]
  (ok w (not= "excellent" (.toLowerCase ^String (second (speed-test w)) java.util.Locale/ROOT))))

(defn assert-can-send-mms [w {:strs [expected_status]}]
  (ok w (py/py-eq (can-send-mms? w) expected_status)))

(defn assert-mobile-data-usage-exceeded [w {:strs [expected_status]}]
  (ok w (py/py-eq (sur w "mobile_data_usage_exceeded") expected_status)))

;; ---------------------------------------------------------------------------
;; Registry

(defn- t [type params f] {:tool? true :type type :params params :fn f})
(defn- f [params fn*] {:params params :fn fn*})

(def functions
  {"check_status_bar" (t :read [] check-status-bar)
   "check_network_status" (t :read [] check-network-status)
   "check_network_mode_preference" (t :read [] check-network-mode-preference)
   "set_network_mode_preference" (t :write [["mode"]] set-network-mode-preference)
   "run_speed_test" (t :read [] run-speed-test)
   "toggle_airplane_mode" (t :write [] toggle-airplane-mode)
   "check_sim_status" (t :read [] check-sim-status)
   "reseat_sim_card" (t :write [] reseat-sim-card)
   "toggle_data" (t :write [] toggle-data)
   "toggle_roaming" (t :write [] toggle-roaming)
   "check_data_restriction_status" (t :read [] check-data-restriction-status)
   "toggle_data_saver_mode" (t :write [] toggle-data-saver-mode)
   "check_apn_settings" (t :read [] check-apn-settings)
   "set_apn_settings" (t :write [["apn_settings"]] set-apn-settings)
   "reset_apn_settings" (t :write [] reset-apn-settings)
   "check_wifi_status" (t :read [] check-wifi-status)
   "toggle_wifi" (t :write [] toggle-wifi)
   "check_wifi_calling_status" (t :read [] check-wifi-calling-status)
   "toggle_wifi_calling" (t :write [] toggle-wifi-calling)
   "check_vpn_status" (t :read [] check-vpn-status)
   "connect_vpn" (t :write [] connect-vpn)
   "disconnect_vpn" (t :write [] disconnect-vpn)
   "check_installed_apps" (t :read [] check-installed-apps)
   "check_app_status" (t :read [["app_name"]] check-app-status)
   "check_app_permissions" (t :read [["app_name"]] check-app-permissions)
   "grant_app_permission" (t :write [["app_name"] ["permission"]] grant-app-permission)
   "can_send_mms" (t :read [] can-send-mms)
   "reboot_device" (t :write [] reboot-device)
   "check_payment_request" (t :read [] check-payment-request)
   "make_payment" (t :write [] make-payment)
   ;; not tools: initialization actions and assertions
   "set_user_info" (f [["name"] ["phone_number"]] set-user-info)
   "set_user_location" (f [["abroad"]] set-user-location)
   "turn_airplane_mode_on" (f [] turn-airplane-mode-on)
   "turn_airplane_mode_off" (f [] turn-airplane-mode-off)
   "unseat_sim_card" (f [] unseat-sim-card)
   "lock_sim_card" (f [["mode"]] lock-sim-card)
   "turn_data_on" (f [] turn-data-on)
   "turn_data_off" (f [] turn-data-off)
   "turn_roaming_on" (f [] turn-roaming-on)
   "turn_roaming_off" (f [] turn-roaming-off)
   "turn_data_saver_mode_on" (f [] turn-data-saver-mode-on)
   "turn_data_saver_mode_off" (f [] turn-data-saver-mode-off)
   "break_apn_settings" (f [] break-apn-settings)
   "break_apn_mms_setting" (f [] break-apn-mms-setting)
   "set_wifi_calling" (f [["enabled"] ["mms_over_wifi" nil]] set-wifi-calling)
   "break_vpn" (f [] break-vpn)
   "remove_app_permission" (f [["app_name"] ["permission"]] remove-app-permission)
   "simulate_network_search" (f [] simulate-network-search-fn)
   "assert_airplane_mode_status" (f [["expected_status"]] assert-airplane-mode-status)
   "assert_service_status" (f [["expected_status"]] assert-service-status)
   "assert_mobile_data_status" (f [["expected_status"]] assert-mobile-data-status)
   "assert_mobile_roaming_status" (f [["expected_status"]] assert-mobile-roaming-status)
   "assert_mobile_data_saver_mode_status" (f [["expected_status"]] assert-mobile-data-saver-mode-status)
   "assert_internet_speed" (f [["expected_speed"] ["expected_desc" nil]] assert-internet-speed)
   "assert_internet_not_excellent" (f [] assert-internet-not-excellent)
   "assert_can_send_mms" (f [["expected_status"]] assert-can-send-mms)
   "assert_mobile_data_usage_exceeded" (f [["expected_status"]] assert-mobile-data-usage-exceeded)})
