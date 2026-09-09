(ns dvergr.model.gateway
  "Trusted provider egress and credential boundary.

   Sandboxed code asks dvergr to invoke a model; it never receives credentials
   or an arbitrary authenticated HTTP primitive. Providers describe a request
   and attach a Credentials implementation. The gateway validates the target,
   injects authentication at the last possible moment, and owns the one-shot
   unauthorized recovery path used by rotating credentials."
  (:require [hato.client :as hc]
            [dvergr.resource :as resource])
  (:import [java.io Closeable]
           [java.net.http HttpClient HttpClient$Redirect]
           [java.net URI]))

(defprotocol Credentials
  (credential-kind [this]
    "Non-secret identifier used for diagnostics.")
  (allowed-origins [this]
    "Exact lower-case URI origins this credential may authorize.")
  (resolve-auth! [this request]
    "Return {:headers {...} :version opaque-version} for request.")
  (recover-auth! [this request prior-auth]
    "Recover after a 401. Return truthy to retry the request exactly once."))

(defn request-origin
  "Return the normalized scheme://host[:port] origin for an absolute URL."
  [url]
  (let [uri (URI. url)
        scheme (some-> (.getScheme uri) .toLowerCase)
        host (some-> (.getHost uri) .toLowerCase)
        port (.getPort uri)]
    (when-not (and scheme host (contains? #{"http" "https"} scheme))
      (throw (ex-info "Provider URL must be absolute HTTP(S)"
                      {:url url})))
    (str scheme "://" host (when (not= -1 port) (str ":" port)))))

(deftype StaticCredentials [kind auth-headers origins]
  Credentials
  (credential-kind [_] kind)
  (allowed-origins [_] origins)
  (resolve-auth! [_ _]
    {:headers auth-headers
     :version :static})
  (recover-auth! [_ _ _] false)

  Object
  (toString [_] (str "#<StaticCredentials " (name kind) ">")))

(defn static-credentials
  "Create non-refreshing credentials restricted to configured origins."
  [kind auth-headers origins]
  (when-not (seq origins)
    (throw (ex-info "Credentials require at least one allowed origin"
                    {:kind kind})))
  (StaticCredentials. kind auth-headers (set (map #(.toLowerCase ^String %) origins))))

(defn unauthenticated-credentials
  "Create an origin-confined, intentionally unauthenticated capability for a
   local or otherwise credential-free provider."
  [origins]
  (static-credentials :unauthenticated {} origins))

(def ^:dynamic *request-fn*
  "Injectable hato-compatible request function for deterministic tests."
  hc/request)

(defn- close-body! [response]
  (when-let [body (:body response)]
    (when (instance? Closeable body)
      (.close ^Closeable body))))

(defn- authorize
  [{:keys [url headers credentials] :as request}]
  (when-not (satisfies? Credentials credentials)
    (throw (ex-info "Provider request has no trusted credentials"
                    {:url url})))
  (let [origin (request-origin url)
        allowed (allowed-origins credentials)]
    (when-not (contains? allowed origin)
      (throw (ex-info "Credential is not permitted for provider origin"
                      {:credential-kind (credential-kind credentials)
                       :origin origin
                       :allowed-origins allowed})))
    (let [auth (resolve-auth! credentials request)]
      {:request (-> request
                    (dissoc :credentials)
                    ;; Credential headers win over provider-supplied headers.
                    (assoc :headers (merge headers (:headers auth))))
       :auth auth})))

(defn- dispatch! [request]
  (resource/admit-model-dispatch!)
  (let [response (*request-fn* request)]
    (when (and resource/*model-scope* (<= 300 (:status response) 399))
      (close-body! response)
      (throw (ex-info "Governed model dispatch does not follow redirects"
                      {:type ::redirect-not-supported :status (:status response)})))
    response))

(defn- governed-client [request]
  (if-not resource/*model-scope*
    request
    (let [client (or (:http-client request)
                     (hc/build-http-client {:connect-timeout 30000 :redirect-policy :never}))]
      (when-not (and (instance? HttpClient client)
                     (= HttpClient$Redirect/NEVER (.followRedirects ^HttpClient client)))
        (throw (ex-info "Governed model dispatch requires a non-redirecting HTTP client"
                        {:type ::redirecting-client})))
      (assoc request :http-client client))))

(defn request!
  "Execute a trusted provider request.

   The input is a hato request map plus :credentials. A refreshable credential
   may recover one 401; response bodies from the rejected attempt are closed
   before retrying. Authentication headers are never returned in errors or
   telemetry by this layer."
  [request]
  (let [request (governed-client request)
        {authorized :request auth :auth} (authorize request)
        response (dispatch! authorized)]
    (if (and (= 401 (:status response))
             (recover-auth! (:credentials request) request auth))
      (do
        (close-body! response)
        (let [{retry :request} (authorize request)]
          (dispatch! retry)))
      response)))
