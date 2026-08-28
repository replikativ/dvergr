(ns dvergr.model.api.codex-auth
  "ChatGPT subscription credentials for the native Codex transport.

   This is intentionally a credential backend, not an agent runtime. It reads
   Codex's file-backed login, refreshes expiring OAuth tokens, preserves rotated
   refresh tokens, and only exposes request headers through model.gateway. A
   keyring-backed Codex login is left to the CLI compatibility provider until
   dvergr has a portable keyring backend."
  (:require [clojure.java.io :as io]
            [dvergr.model.gateway :as gateway]
            [hato.client :as hc]
            [jsonista.core :as json])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file AtomicMoveNotSupportedException CopyOption Files Path
            StandardCopyOption]
           [java.nio.file.attribute FileAttribute PosixFilePermission]
           [java.security MessageDigest]
           [java.time Instant]
           [java.util Base64]))

(def ^:private codex-origin "https://chatgpt.com")
(def ^:private refresh-url "https://auth.openai.com/oauth/token")
(def ^:private oauth-client-id "app_EMoamEEZ73f0CkXaXp7hrann")
(def ^:private refresh-window-seconds (* 5 60))
(def ^:private stale-refresh-seconds (* 8 24 60 60))
(def ^:private auth-claims-key (keyword "https://api.openai.com/auth"))

(defn default-auth-path []
  (let [codex-home (or (System/getenv "CODEX_HOME")
                       (str (System/getProperty "user.home") "/.codex"))]
    (.toPath (io/file codex-home "auth.json"))))

(defn- read-auth-file [^Path path]
  (json/read-value (Files/readString path StandardCharsets/UTF_8)
                   json/keyword-keys-object-mapper))

(defn- posix-600! [^Path path]
  (try
    (Files/setPosixFilePermissions
     path
     #{PosixFilePermission/OWNER_READ PosixFilePermission/OWNER_WRITE})
    (catch UnsupportedOperationException _ nil)))

(defn- write-auth-file! [^Path path auth]
  (let [parent (.getParent path)
        _ (Files/createDirectories parent (make-array FileAttribute 0))
        temp (Files/createTempFile parent ".dvergr-auth-" ".json"
                                   (make-array FileAttribute 0))]
    (try
      (Files/writeString temp (json/write-value-as-string auth)
                         StandardCharsets/UTF_8
                         (make-array java.nio.file.OpenOption 0))
      (posix-600! temp)
      (try
        (Files/move temp path
                    (into-array CopyOption
                                [StandardCopyOption/ATOMIC_MOVE
                                 StandardCopyOption/REPLACE_EXISTING]))
        (catch AtomicMoveNotSupportedException _
          (Files/move temp path
                      (into-array CopyOption
                                  [StandardCopyOption/REPLACE_EXISTING]))))
      (posix-600! path)
      (finally
        (Files/deleteIfExists temp)))))

(defn- response-body-string [body]
  (cond
    (string? body) body
    (nil? body) ""
    :else (slurp body)))

(defn- default-refresh-request [payload]
  (let [response (hc/request {:method :post
                              :url refresh-url
                              :headers {"Content-Type" "application/json"
                                        "originator" "dvergr"}
                              :body (json/write-value-as-string payload)
                              :as :string
                              :throw-exceptions false})]
    {:status (:status response)
     :body (response-body-string (:body response))}))

(defn- decode-jwt-payload [token]
  (try
    (let [[_ payload _] (.split ^String token "\\." 3)
          bytes (.decode (Base64/getUrlDecoder) ^String payload)]
      (json/read-value (String. bytes StandardCharsets/UTF_8)
                       json/keyword-keys-object-mapper))
    (catch Exception _ nil)))

(defn- jwt-expiry [token]
  (some-> (decode-jwt-payload token) :exp long Instant/ofEpochSecond))

(defn- parse-instant [value]
  (try
    (when value (Instant/parse (str value)))
    (catch Exception _ nil)))

(defn- refresh-needed? [auth]
  (let [now (Instant/now)
        access (get-in auth [:tokens :access_token])]
    (if-let [expiry (and access (jwt-expiry access))]
      (not (.isAfter expiry (.plusSeconds now refresh-window-seconds)))
      (when-let [last-refresh (parse-instant (:last_refresh auth))]
        (.isBefore last-refresh (.minusSeconds now stale-refresh-seconds))))))

(defn- token-version [token]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                        (.getBytes ^String token StandardCharsets/UTF_8))]
    (apply str (map #(format "%02x" (bit-and (int %) 0xff)) (take 12 digest)))))

(defn- chatgpt-auth? [auth]
  (and (= "chatgpt" (some-> (:auth_mode auth) name))
       (seq (get-in auth [:tokens :access_token]))
       (seq (get-in auth [:tokens :refresh_token]))
       (seq (or (get-in auth [:tokens :account_id])
                (get-in (decode-jwt-payload (get-in auth [:tokens :id_token]))
                        [auth-claims-key :chatgpt_account_id])))))

(defn- refresh-auth! [auth load-auth save-auth refresh-request]
  (let [refresh-token (get-in auth [:tokens :refresh_token])
        response (refresh-request {:client_id oauth-client-id
                                   :grant_type "refresh_token"
                                   :refresh_token refresh-token})
        body (try
               (json/read-value (response-body-string (:body response))
                                json/keyword-keys-object-mapper)
               (catch Exception _ {}))]
    (when-not (<= 200 (long (:status response 0)) 299)
      (throw (ex-info "Codex subscription token refresh failed"
                      {:status (:status response)
                       :code (or (get-in body [:error :code]) (:code body))
                       :message (or (get-in body [:error :message]) (:message body))})))
    ;; Re-read immediately before persisting so unrelated fields changed by a
    ;; concurrently upgraded Codex installation are retained.
    (let [current (or (load-auth) auth)
          updated (cond-> (assoc current :last_refresh (str (Instant/now)))
                    (:id_token body) (assoc-in [:tokens :id_token] (:id_token body))
                    (:access_token body) (assoc-in [:tokens :access_token] (:access_token body))
                    (:refresh_token body) (assoc-in [:tokens :refresh_token] (:refresh_token body)))]
      (save-auth updated)
      updated)))

(defn- auth-headers [auth]
  (let [access (get-in auth [:tokens :access_token])
        claims (decode-jwt-payload (get-in auth [:tokens :id_token]))
        account-id (or (get-in auth [:tokens :account_id])
                       (get-in claims [auth-claims-key :chatgpt_account_id]))
        fedramp? (true? (get-in claims
                                [auth-claims-key
                                 :chatgpt_account_is_fedramp]))]
    (cond-> {"Authorization" (str "Bearer " access)
             "ChatGPT-Account-ID" account-id}
      fedramp? (assoc "X-OpenAI-Fedramp" "true"))))

(deftype CodexFileCredentials [load-auth save-auth refresh-request refresh-lock]
  gateway/Credentials
  (credential-kind [_] :codex-subscription)
  (allowed-origins [_] #{codex-origin})
  (resolve-auth! [_ _]
    (locking refresh-lock
      (let [loaded (load-auth)]
        (when-not (chatgpt-auth? loaded)
          (throw (ex-info "No usable file-backed Codex ChatGPT login"
                          {:credential-kind :codex-subscription})))
        (let [auth (if (refresh-needed? loaded)
                     (refresh-auth! loaded load-auth save-auth refresh-request)
                     loaded)
              access (get-in auth [:tokens :access_token])]
          {:headers (auth-headers auth)
           :version (token-version access)}))))
  (recover-auth! [_ _ prior-auth]
    (locking refresh-lock
      (let [loaded (load-auth)
            loaded-version (some-> loaded (get-in [:tokens :access_token]) token-version)]
        (if (and loaded-version (not= loaded-version (:version prior-auth)))
          true
          (try
            (refresh-auth! loaded load-auth save-auth refresh-request)
            true
            (catch Exception refresh-error
              ;; Another Codex process may have won a rotating-refresh-token
              ;; race. Accept its newly persisted access token when present.
              (let [reloaded (load-auth)
                    reloaded-version (some-> reloaded
                                             (get-in [:tokens :access_token])
                                             token-version)]
                (if (and reloaded-version
                         (not= reloaded-version (:version prior-auth)))
                  true
                  (throw refresh-error)))))))))

  Object
  (toString [_] "#<CodexFileCredentials>"))

(defn create
  "Create file-backed Codex credentials.

   Tests and alternative secure stores may inject :load-auth, :save-auth and
   :refresh-request without exposing tokens to provider code."
  [config]
  (let [path (or (:auth-path config) (default-auth-path))
        path (if (instance? Path path) path (.toPath (io/file path)))
        load-auth (or (:load-auth config)
                      #(read-auth-file path))
        save-auth (or (:save-auth config)
                      #(write-auth-file! path %))
        refresh-request (or (:refresh-request config) default-refresh-request)]
    (CodexFileCredentials. load-auth save-auth refresh-request (Object.))))

(defn available?
  "True when a usable file-backed ChatGPT login is available. Never logs token
   material. Keyring-only logins deliberately return false for now."
  [config]
  (try
    (let [path (or (:auth-path config) (default-auth-path))
          path (if (instance? Path path) path (.toPath (io/file path)))
          load-auth (or (:load-auth config) #(read-auth-file path))]
      (boolean (chatgpt-auth? (load-auth))))
    (catch Exception _ false)))
