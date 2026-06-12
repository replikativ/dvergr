(ns dvergr.substrate.config
  "Load local configuration from config.local.edn (gitignored).

   Config file location (in priority order):
   1. DVERGR_CONFIG env var path
   2. ./config.local.edn  (project root — gitignored, put secrets here)
   3. ./config.example.edn (fallback for development, no secrets)

   Usage:
     (require '[dvergr.substrate.config :as cfg])
     (cfg/load-config)          ; load/reload from disk
     (cfg/config)               ; get current config map
     (cfg/github-token)         ; get GitHub token
     (cfg/mail-account :datahike-contact)  ; get mail account config"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [dvergr.substrate.paths :as paths]))

(def ^:private config-atom (atom nil))

(defn- config-path []
  (or (System/getenv "DVERGR_CONFIG")
      (let [local (io/file "config.local.edn")]
        (when (.exists local) (.getAbsolutePath local)))
      (let [example (io/file "config.example.edn")]
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

(defn mail-account
  "IMAP/SMTP config for a named mail account.
   Returns map with :email :imap :smtp :data-path, or nil.

   `:data-path` defaults to `.dvergr/mail/<account-id>` (under DVERGR_HOME) when
   the config omits it, so mail intake is portable; an explicit absolute path in
   config still wins. The parent container is pre-created (`paths/dir`) but the
   store leaf itself is left for datahike to create (the store-dir gotcha)."
  [account-id]
  (when-let [acct (get-in (config) [:mail account-id])]
    (update acct :data-path
            (fn [p] (or p (str (paths/dir "mail") "/" (name account-id)))))))

(defn agents-config
  "Agent configuration map {:agent-id {:provider :model :tags ...}}."
  []
  (get (config) :agents {}))

(defn secret-specs
  "Boundary-injection secret specs (doc/boundary-secret-injection.md): a vector of
   `{:name :env|:value|:config-path|:basic-auth-config-paths :allowed-domains
     :allowed-locations :header-names}`. The sandbox binds each to a placeholder,
   so an agent can USE a key without SEEING it. The value is sourced from `:env`
   (host env var, resolved later) or `:value` (literal) or `:config-path` (an
   existing config path, so creds stay in one place) or `:basic-auth-config-paths`
   `[[user-path] [pass-path]]` (pre-encodes Authorization: Basic for Basic-auth
   intakes). Here we resolve config-path sources → :value/:basic-auth (env is left
   for the host-side registry builder). Empty by default."
  []
  (mapv (fn [{:keys [config-path basic-auth-config-paths] :as spec}]
          (cond-> spec
            config-path             (assoc :value (get-in (config) config-path))
            basic-auth-config-paths (assoc :basic-auth (mapv #(get-in (config) %)
                                                             basic-auth-config-paths))))
        (get (config) :secrets [])))

(defn sandbox-env
  "NON-secret config values exposed verbatim to the sandbox `env/get` — identifiers
   and endpoints an intake needs in plaintext to build a URL/body (e.g. a Zulip
   site URL, a handle). Distinct from `:secrets` (which return placeholders): these
   are NOT sensitive. A map {\"ENV_NAME\" value}. Empty by default."
  []
  (get (config) :sandbox-env {}))

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
  (get (config) :http {:port 17880 :ip "127.0.0.1"}))

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
