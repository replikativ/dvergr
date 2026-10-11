(ns dvergr.substrate.config-test
  "Config resolution: no implicit example config, real env tokens beat placeholders,
   and the daemon config forwards the allowlist policy."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [dvergr.substrate.config :as cfg]))

(def ^:private config-atom @#'cfg/config-atom)

(use-fixtures :each
  (fn [t]
    (let [saved @config-atom]
      (try (t) (finally (reset! config-atom saved))))))

(defn- tmp-dir []
  (doto (io/file (System/getProperty "java.io.tmpdir")
                 (str "dvergr-config-test-" (random-uuid)))
    (.mkdirs)))

(defn- write-edn! [dir file m]
  (spit (io/file dir file) (pr-str m)))

(def ^:private example
  {:telegram      {:token "YOUR_TELEGRAM_BOT_TOKEN"}
   :github        {:token "ghp_YOUR_TOKEN_HERE"}
   :allowed-users []
   :http          {:port 8080}})

(defn- env-of [m] (fn [k] (get m k)))

(defmacro ^:private with-env
  "Run body with the config namespace seeing only the env vars in `m`."
  [m & body]
  `(with-redefs-fn {(var cfg/getenv) (env-of ~m)} (fn [] ~@body)))

(deftest example-config-is-never-loaded
  (let [dir (tmp-dir)]
    (write-edn! dir "config.example.edn" example)
    (with-env {}
      (testing "only an example file present: the config is empty"
        (is (= {} (cfg/load-config dir))))
      (testing "no config means no HTTP server and no Telegram"
        (let [dc (cfg/daemon-config)]
          (is (not (contains? dc :http)))
          (is (not (contains? dc :telegram)))))
      (testing "a local config is still picked up"
        (write-edn! dir "config.local.edn" {:default-agent :coder})
        (is (= {:default-agent :coder} (cfg/load-config dir))))
      (testing "DVERGR_CONFIG wins over the local file"
        (write-edn! dir "explicit.edn" {:default-agent :explicit})
        (with-env {"DVERGR_CONFIG" (str (io/file dir "explicit.edn"))}
          (is (= {:default-agent :explicit} (cfg/load-config dir))))))))

(deftest env-token-beats-placeholder
  (let [dir (tmp-dir)]
    (write-edn! dir "config.local.edn" example)
    (with-env {"TELEGRAM_BOT_TOKEN"  "123456:real-env-token"
               "GITHUB_DVERGR_TOKEN" "ghp_realenv"}
      (cfg/load-config dir)
      (testing "a copied example placeholder does not shadow the environment"
        (is (= "123456:real-env-token" (cfg/telegram-token)))
        (is (= "123456:real-env-token" (get-in (cfg/daemon-config) [:telegram :token])))
        (is (= "ghp_realenv" (cfg/github-token)))))
    (with-env {}
      (testing "a placeholder alone is no token: Telegram stays off"
        (is (nil? (cfg/telegram-token)))
        (is (not (contains? (cfg/daemon-config) :telegram)))
        (is (nil? (cfg/github-token)))))
    (write-edn! dir "config.local.edn" {:telegram {:token "999:configured"}})
    (with-env {"TELEGRAM_BOT_TOKEN" "123456:real-env-token"}
      (cfg/load-config dir)
      (testing "a real configured token keeps its priority over the environment"
        (is (= "999:configured" (cfg/telegram-token)))))))

(deftest daemon-config-forwards-allowlist-policy
  (let [dir (tmp-dir)]
    (with-env {}
      (write-edn! dir "config.local.edn" {:allowed-users [] :strict-allowlist? true})
      (cfg/load-config dir)
      (is (true? (:strict-allowlist? (cfg/daemon-config))))
      (is (= [] (:allowed-users (cfg/daemon-config))))
      (write-edn! dir "config.local.edn" {})
      (cfg/load-config dir)
      (is (false? (:strict-allowlist? (cfg/daemon-config)))
          "absent means the documented default: not strict"))))

(deftest secret-specs-skip-placeholders
  (let [dir (tmp-dir)]
    (write-edn! dir "config.local.edn"
                (assoc example
                       :zulip {:email "bot@example.org" :api-key "YOUR_ZULIP_KEY"}
                       :secrets [{:name "GITHUB_TOKEN" :config-path [:github :token]
                                  :env "GITHUB_TOKEN"}
                                 {:name "ZULIP_AUTH"
                                  :basic-auth-config-paths [[:zulip :email] [:zulip :api-key]]}]))
    (with-env {}
      (cfg/load-config dir)
      (let [[gh zulip] (cfg/secret-specs)]
        (is (nil? (:value gh)) "a placeholder :config-path value leaves the :env source in charge")
        (is (= ["bot@example.org" nil] (:basic-auth zulip)))))
    (write-edn! dir "config.local.edn"
                {:github {:token "ghp_real"}
                 :secrets [{:name "GITHUB_TOKEN" :config-path [:github :token]}]})
    (with-env {}
      (cfg/load-config dir)
      (is (= "ghp_real" (:value (first (cfg/secret-specs))))))))
