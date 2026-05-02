(ns dvergr.config
  "Load local configuration from config.local.edn (gitignored).

   Config file location (in priority order):
   1. DVERGR_CONFIG env var path
   2. ./config.local.edn  (project root — gitignored, put secrets here)
   3. ./config.edn.example (fallback for development, no secrets)

   Usage:
     (require '[dvergr.config :as cfg])
     (cfg/load-config)          ; load/reload from disk
     (cfg/config)               ; get current config map
     (cfg/github-token)         ; get GitHub token
     (cfg/mail-account :datahike-contact)  ; get mail account config"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(def ^:private config-atom (atom nil))

(defn- config-path []
  (or (System/getenv "DVERGR_CONFIG")
      (let [local (io/file "config.local.edn")]
        (when (.exists local) (.getAbsolutePath local)))
      (let [example (io/file "config.edn.example")]
        (when (.exists example) (.getAbsolutePath example)))))

(defn load-config
  "Load config from disk. Returns the config map."
  []
  (if-let [path (config-path)]
    (let [cfg (edn/read-string (slurp path))]
      (reset! config-atom cfg)
      (println "[config] Loaded from" path)
      cfg)
    (do
      (println "[config] No config.local.edn found — using empty config")
      (reset! config-atom {})
      {})))

(defn config
  "Return current config map, loading from disk if not yet loaded."
  []
  (or @config-atom (load-config)))

;; ============================================================================
;; Accessors
;; ============================================================================

(defn github-token
  "GitHub API token. Checks config :github :token, then GITHUB_DVERGR_TOKEN env var."
  []
  (or (get-in (config) [:github :token])
      (System/getenv "GITHUB_DVERGR_TOKEN")))

(defn telegram-token
  "Telegram bot token."
  []
  (or (get-in (config) [:telegram :token])
      (System/getenv "TELEGRAM_BOT_TOKEN")))

(defn slack-token
  "Slack user token (xoxp-...). Checks config :slack :token, then SLACK_USER_TOKEN env var."
  []
  (or (get-in (config) [:slack :token])
      (System/getenv "SLACK_USER_TOKEN")))

(defn mail-account
  "IMAP/SMTP config for a named mail account.
   Returns map with :email :imap :smtp :data-path, or nil."
  [account-id]
  (get-in (config) [:mail account-id]))

(defn agents-config
  "Agent configuration map {:agent-id {:provider :model :tags ...}}."
  []
  (get (config) :agents {}))

(defn allowed-users
  "List of allowed Telegram users."
  []
  (get (config) :allowed-users []))

(defn default-agent
  "Default agent ID for new sessions."
  []
  (get (config) :default-agent :var))

(defn zulip-config
  "Zulip bot config {:email :api-key :site}."
  []
  (get (config) :zulip))

(defn http-config []
  (get (config) :http {:port 8080}))

(defn daemon-config
  "Build a daemon start! config map from the loaded config.
   Merges telegram token, agent configs, allowed users, and defaults."
  []
  (let [cfg (config)]
    (cond-> {:agents       (agents-config)
             :default-agent (default-agent)
             :allowed-users (allowed-users)}
      (telegram-token) (assoc :telegram {:token (telegram-token)})
      (:http cfg)      (assoc :http (:http cfg)))))
