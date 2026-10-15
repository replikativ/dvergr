(ns dvergr.web.guard
  "The browser boundary of the web server. Binding to loopback keeps other
   machines out, but not the user's own browser: any page it shows can submit
   a form to `http://127.0.0.1:17880`, and a DNS name rebound to 127.0.0.1
   makes the dashboard same-origin with the attacker's page. Two checks
   close that, besides the credential, which a page can never send for the
   user: it travels only in the `Authorization` header (`dvergr.web.server`),
   never in a cookie, so a cross-site request carries none and needs no CSRF
   token:

   - Host: every request must name this server — a loopback name, the bind
     address (every local interface address when bound to a wildcard), or a
     configured `:allowed-hosts` name. A rebound name fails here, reads included.
   - Origin: a state-changing request (not GET/HEAD/OPTIONS) whose `Origin` —
     or, without one, `Referer` — is not this server's own origin (the Host it
     was sent to) or a configured `:allowed-origins` entry is refused.
   The JSON API's writes must besides be `application/json`, which a
   cross-site page can only send after a CORS preflight that this server
   does not grant."
  (:require [clojure.string :as str])
  (:import (java.net InetAddress NetworkInterface URI)))

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

;; ---------------------------------------------------------------------------
;; The middleware

(def ^:private safe-methods #{:get :head :options})

(defn- forbidden [why]
  {:status 403 :headers {"Content-Type" "text/plain; charset=utf-8"} :body why})

(defn- json-content? [req]
  (some-> (get-in req [:headers "content-type"]) str/lower-case
          (str/starts-with? "application/json")))

(defn wrap
  "Ring middleware applying the Host and Origin checks to `handler`.
   Opts: `:ip` (the bind address), `:allowed-hosts` and `:allowed-origins`
   (for a reverse proxy)."
  [handler {:keys [ip allowed-hosts allowed-origins]}]
  (let [hosts (host-set ip allowed-hosts)]
    (fn [req]
      (let [headers (:headers req)
            host (get headers "host")
            unsafe? (not (safe-methods (:request-method req)))
            origin (or (get headers "origin") (get headers "referer"))
            api? (str/starts-with? (or (:uri req) "") "/api/v1/")]
        (cond
          (not (host-allowed? host hosts))
          (forbidden "Forbidden: unknown Host")

          (and unsafe? origin (not (same-origin? origin (name (or (:scheme req) :http)) host allowed-origins)))
          (forbidden "Forbidden: cross-origin request")

          (and unsafe? api? (not (json-content? req)))
          {:status 415 :headers {"Content-Type" "text/plain; charset=utf-8"}
           :body "API writes take application/json"}

          :else (handler req))))))
