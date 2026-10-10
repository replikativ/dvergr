(ns dvergr.web.guard
  "The browser boundary of the web server. Binding to loopback keeps other
   machines out, but not the user's own browser: any page it shows can submit
   a form to `http://127.0.0.1:17880`, and a DNS name rebound to 127.0.0.1
   makes the dashboard same-origin with the attacker's page. Three checks
   close that:

   - Host: every request must name this server — a loopback name, the bind
     address (every local interface address when bound to a wildcard), or a
     configured `:allowed-hosts` name. A rebound name fails here, reads included.
   - Origin: a state-changing request (not GET/HEAD/OPTIONS) whose `Origin` —
     or, without one, `Referer` — is not this server's own origin (the Host it
     was sent to) or a configured `:allowed-origins` entry is refused.
   - CSRF token: a state-changing request to the HTML UI must carry the
     session's token, in the `X-CSRF-Token` header or the `csrf` form field.
     The session is a random id in an HttpOnly cookie; the token is an HMAC of
     it under a secret minted when the server starts, so a cookie set by
     another local site does not yield a valid token. Pages read the token from
     `*csrf-token*` while they render. The JSON API under `/api/v1/` has no
     session; its writes must be `application/json`, which a cross-site page
     can only send after a CORS preflight that this server does not grant."
  (:require [clojure.string :as str])
  (:import (java.io ByteArrayInputStream)
           (java.net InetAddress NetworkInterface URI)
           (java.nio.charset StandardCharsets)
           (java.security MessageDigest SecureRandom)
           (javax.crypto Mac)
           (javax.crypto.spec SecretKeySpec)
           (java.util HexFormat)))

(def ^:dynamic *csrf-token*
  "The CSRF token of the request being handled, for the page it renders."
  nil)

(def session-cookie "dvergr_sid")

(defn- random-hex [n]
  (let [b (byte-array n)]
    (.nextBytes (SecureRandom.) b)
    (.formatHex (HexFormat/of) b)))

(defn new-secret
  "A fresh secret for the session tokens of one server."
  []
  (random-hex 32))

(defn csrf-token
  "The CSRF token of session id `sid` under `secret`."
  [secret sid]
  (let [mac (Mac/getInstance "HmacSHA256")]
    (.init mac (SecretKeySpec. (.getBytes ^String secret StandardCharsets/UTF_8) "HmacSHA256"))
    (.formatHex (HexFormat/of) (.doFinal mac (.getBytes ^String sid StandardCharsets/UTF_8)))))

(defn- constant-time= [^String a ^String b]
  (and a b (MessageDigest/isEqual (.getBytes a StandardCharsets/UTF_8)
                                  (.getBytes b StandardCharsets/UTF_8))))

;; ---------------------------------------------------------------------------
;; Hosts

(def ^:private loopback-names #{"localhost" "127.0.0.1" "[::1]"})
(def ^:private wildcard-binds #{"0.0.0.0" "::" "[::]" "0:0:0:0:0:0:0:0"})

(defn- ip-literal? [host]
  (boolean (or (re-matches #"\d{1,3}(\.\d{1,3}){3}" host)
               (re-matches #"\[[0-9a-fA-F:.%]+\]" host))))

(defn- literal-address
  "The InetAddress of an IP-literal host (no DNS lookup), else nil."
  [host]
  (when (ip-literal? host)
    (try (InetAddress/ofLiteral (str/replace host #"^\[|\]$" ""))
         (catch Exception _ nil))))

(defn- host-form
  "A bind address or host name as it appears in a Host header."
  [s]
  (let [s (str/lower-case (str/trim s))]
    (if (and (str/includes? s ":") (not (str/starts-with? s "[")))
      (str "[" s "]")
      s)))

(defn- interface-addresses []
  (try
    (into #{}
          (comp (mapcat #(enumeration-seq (.getInetAddresses ^NetworkInterface %)))
                (map #(host-form (str/replace (.getHostAddress ^InetAddress %) #"%.*$" ""))))
          (enumeration-seq (NetworkInterface/getNetworkInterfaces)))
    (catch Exception _ #{})))

(defn host-set
  "The host names a request may address: loopback, the bind address (every
   interface address for a wildcard bind) and `extra` names."
  [ip extra]
  (let [ip (some-> ip str/trim str/lower-case)]
    (into loopback-names
          (concat (cond (nil? ip) nil
                        (wildcard-binds ip) (interface-addresses)
                        :else [(host-form ip)])
                  (map host-form extra)))))

(defn- split-authority
  "`host[:port]` → [lower-cased host, port or nil]."
  [authority]
  (when-let [a (some-> authority str/trim str/lower-case not-empty)]
    (if-let [[_ h p] (re-matches #"(\[[^\]]+\]|[^:\[\]]+)(?::(\d+))?" a)]
      [h (some-> p parse-long)]
      nil)))

(defn host-allowed?
  "True when the Host header names this server."
  [host-header allowed]
  (when-let [[h] (split-authority host-header)]
    (or (contains? allowed h)
        (when-let [addr (literal-address h)]
          (some #(= addr (literal-address %)) (filter ip-literal? allowed))))))

;; ---------------------------------------------------------------------------
;; Origins

(defn- url-origin
  "[scheme host port] of an absolute http(s) URL, the port defaulted, or nil."
  [s]
  (try
    (let [u (URI. s)
          scheme (some-> (.getScheme u) str/lower-case)
          host (some-> (.getHost u) host-form)]
      (when (and (#{"http" "https"} scheme) host)
        [scheme host (if (neg? (.getPort u)) (if (= "https" scheme) 443 80) (.getPort u))]))
    (catch Exception _ nil)))

(defn- normalize-origin [s]
  (when-let [[scheme host port] (url-origin s)]
    (str scheme "://" host ":" port)))

(defn same-origin?
  "True when `url` (an Origin or Referer) is the origin the request was sent
   to — its scheme (`req-scheme`, \"http\" or \"https\") and Host header — or
   one of the `allowed` origins. A TLS-terminating proxy makes the two schemes
   differ, so its origin goes in `allowed`."
  [url req-scheme host-header allowed]
  (when-let [[scheme host port] (url-origin url)]
    (or (let [[h p] (split-authority host-header)]
          (and (= scheme req-scheme) (= host h)
               (= port (or p (if (= "https" scheme) 443 80)))))
        (contains? (into #{} (keep normalize-origin) allowed)
                   (normalize-origin url)))))

;; ---------------------------------------------------------------------------
;; Sessions and tokens

(defn- cookie-value [req name]
  (some (fn [part]
          (let [[k v] (str/split (str/trim part) #"=" 2)]
            (when (= k name) v)))
        (some-> (get-in req [:headers "cookie"]) (str/split #";"))))

(defn- session-id [req]
  (let [v (cookie-value req session-cookie)]
    (when (and v (re-matches #"[0-9a-f]{32}" v)) v)))

(defn- form-urlencoded? [req]
  (some-> (get-in req [:headers "content-type"]) str/lower-case
          (str/starts-with? "application/x-www-form-urlencoded")))

(defn- form-field
  "The value of field `k` in an urlencoded body string."
  [body k]
  (some (fn [pair]
          (let [[fk v] (str/split pair #"=" 2)]
            (when (= k (java.net.URLDecoder/decode (or fk "") "UTF-8"))
              (java.net.URLDecoder/decode (str/replace (or v "") #"\+" " ") "UTF-8"))))
        (str/split body #"&")))

(defn- buffer-body
  "`req` with its form body read into memory (and re-readable), plus the body
   string."
  [req]
  (if (and (:body req) (form-urlencoded? req))
    (let [bytes (.readAllBytes ^java.io.InputStream (:body req))]
      [(assoc req :body (ByteArrayInputStream. bytes))
       (String. bytes StandardCharsets/UTF_8)])
    [req nil]))

;; ---------------------------------------------------------------------------
;; The middleware

(def ^:private safe-methods #{:get :head :options})

(defn- forbidden [why]
  {:status 403 :headers {"Content-Type" "text/plain; charset=utf-8"} :body why})

(defn- json-content? [req]
  (some-> (get-in req [:headers "content-type"]) str/lower-case
          (str/starts-with? "application/json")))

(defn wrap
  "Ring middleware applying the Host, Origin and CSRF checks to `handler`.
   Opts: `:secret` (from `new-secret`), `:ip` (the bind address),
   `:allowed-hosts` and `:allowed-origins` (for a reverse proxy)."
  [handler {:keys [secret ip allowed-hosts allowed-origins]}]
  (let [hosts (host-set ip allowed-hosts)]
    (fn [req]
      (let [headers (:headers req)
            host (get headers "host")
            unsafe? (not (safe-methods (:request-method req)))
            origin (or (get headers "origin") (get headers "referer"))
            api? (str/starts-with? (or (:uri req) "") "/api/v1/")
            sid (session-id req)
            new-sid (when-not sid (random-hex 16))
            token (csrf-token secret (or sid new-sid))]
        (cond
          (not (host-allowed? host hosts))
          (forbidden "Forbidden: unknown Host")

          (and unsafe? origin (not (same-origin? origin (name (or (:scheme req) :http)) host allowed-origins)))
          (forbidden "Forbidden: cross-origin request")

          (and unsafe? api? (not (json-content? req)))
          {:status 415 :headers {"Content-Type" "text/plain; charset=utf-8"}
           :body "API writes take application/json"}

          :else
          (let [[req body] (if (and unsafe? (not api?)) (buffer-body req) [req nil])
                sent (or (get headers "x-csrf-token") (some-> body (form-field "csrf")))]
            (if (and unsafe? (not api?) (not (and sid (constant-time= sent token))))
              (forbidden "Forbidden: missing or stale CSRF token (reload the page)")
              (let [resp (binding [*csrf-token* token] (handler req))]
                (cond-> resp
                  (and new-sid (map? resp))
                  (assoc-in [:headers "Set-Cookie"]
                            (str session-cookie "=" new-sid
                                 "; Path=/; HttpOnly; SameSite=Lax")))))))))))
