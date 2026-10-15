(ns dvergr.mcp.http
  "MCP over Streamable HTTP (spec 2026-07-28, and the handshake revisions
   2025-03-26 … 2025-11-25 on the same endpoint): one `POST /mcp` endpoint on
   its own loopback listener, next to the TCP transport (`dvergr.mcp.server`),
   answering through the same dispatch.

   Security: an `Origin` that is not loopback is refused (403, DNS
   rebinding), and every request is authenticated (401 when refused) by the
   `:authenticate` seam (`dvergr.principal`) from its `Authorization: Bearer`
   token and socket peer; the default takes the token read from — or created
   at — `:token-file` (mode 0600), sent by a loopback peer without a
   forwarding header, as the local principal, and refuses a non-loopback
   bind. A handshake session
   belongs to the principal that opened it: a later request on it by another
   principal is answered as for an unknown session.

   Stateless requests (`MCP-Protocol-Version: 2026-07-28`) carry everything:
   the `MCP-Protocol-Version`, `Mcp-Method` and `Mcp-Name` headers must match
   the body (400, -32020 `HeaderMismatch`); an unsupported version is 400
   (-32022), an unknown method 404 (-32601). A response is one JSON object,
   or an SSE stream when the request reports progress (a `progressToken` on a
   long request) and always for `subscriptions/listen`, whose stream stays
   open (keep-alive comments) and carries only what it subscribed to.
   Closing a stream cancels its request. A notification gets 202.

   Handshake requests (an older `MCP-Protocol-Version`, or none: 2025-03-26)
   get a session: `initialize` mints an `Mcp-Session-Id`, later requests name
   it, DELETE ends it. There is no GET stream (405), which those revisions
   allow; their server-initiated notifications are not delivered over HTTP."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [dvergr.mcp.json-rpc :as json-rpc]
            [dvergr.mcp.server :as server]
            [dvergr.mcp.surface :as surface]
            [dvergr.principal :as principal]
            [jsonista.core :as json]
            [org.httpkit.server :as http])
  (:import (java.nio.file Files LinkOption)
           (java.nio.file.attribute PosixFilePermissions)
           (java.security SecureRandom)
           (java.util Base64 HexFormat)))

(def ^:private write-mapper
  (json/object-mapper {:encode-key-fn (fn [k] (if (keyword? k) (subs (str k) 1) (str k)))}))
(def ^:private read-mapper (json/object-mapper {:decode-key-fn keyword}))

(defn- ->json [m] (json/write-value-as-string m write-mapper))

;; ---------------------------------------------------------------------------
;; Token

(defn- random-token []
  (let [b (byte-array 32)]
    (.nextBytes (SecureRandom.) b)
    (.formatHex (HexFormat/of) b)))

(defn ensure-token!
  "The bearer token in `file`, created (mode 0600) when missing."
  [file]
  (let [f (io/file file)]
    (if (.isFile f)
      (str/trim (slurp f))
      (let [t (random-token)]
        (io/make-parents f)
        (spit f t)
        (try (Files/setPosixFilePermissions (.toPath f) (PosixFilePermissions/fromString "rw-------"))
             (catch UnsupportedOperationException _ nil))
        t))))

;; ---------------------------------------------------------------------------
;; Request checks

(def ^:private loopback-origin
  #"^https?://(localhost|127\.0\.0\.1|\[::1\])(:\d+)?$")

(defn- origin-ok? [origin allowed]
  (or (nil? origin) (re-matches loopback-origin origin) (contains? allowed origin)))

(defn- decode-header
  "A header value in the Base64 sentinel form (`=?base64?…?=`) decoded."
  [v]
  (if-let [[_ b64] (and v (re-matches #"=\?base64\?(.*)\?=" v))]
    (String. (.decode (Base64/getDecoder) ^String b64) "UTF-8")
    v))

(def ^:private named-methods
  {"tools/call" :name "resources/read" :uri "prompts/get" :name})

(defn header-mismatch
  "Why a stateless request's headers do not match its body, or nil."
  [headers {:keys [method params] :as message}]
  (let [hv (get headers "mcp-protocol-version")
        bv (json-rpc/request-version message)
        hm (get headers "mcp-method")
        name-key (named-methods method)
        hn (decode-header (get headers "mcp-name"))
        bn (some-> name-key params str)]
    (cond
      (nil? hv) "MCP-Protocol-Version header is missing"
      (not= hv bv) (str "MCP-Protocol-Version header '" hv "' does not match the body's '" bv "'")
      (nil? hm) "Mcp-Method header is missing"
      (not= hm method) (str "Mcp-Method header '" hm "' does not match body value '" method "'")
      (and name-key (nil? hn)) "Mcp-Name header is missing"
      (and name-key (not= hn bn)) (str "Mcp-Name header value '" hn "' does not match body value '" bn "'")
      :else nil)))

(defn- error-body [id code message & [data]]
  {:jsonrpc "2.0" :id id :error (cond-> {:code code :message message} data (assoc :data data))})

(defn- json-response [status body & [headers]]
  {:status status
   :headers (merge {"Content-Type" "application/json"} headers)
   :body (->json body)})

(defn- status-of
  "The HTTP status of a JSON-RPC response on the MCP endpoint."
  [response]
  (case (get-in response [:error :code])
    -32601 404
    (-32022 -32020 -32021 -32700) 400
    200))

;; ---------------------------------------------------------------------------
;; Answering: one JSON object or an SSE stream

(defn- sse-event [message] (str "data: " (->json message) "\n\n"))

(def ^:dynamic *keep-alive-ms*
  "How often an open listen stream sends a keep-alive comment."
  25000)

(defn- response? [m] (and (contains? m :id) (or (contains? m :result) (contains? m :error))))

(defn- answer
  "Dispatch `message` on `context` and answer the HTTP request through an
   async channel: `stream?` as SSE (notifications, then the response, which
   ends the stream; a listen stream stays open), else the response as JSON.
   Closing the channel cancels the request (and ends a subscription)."
  [req context message stream? extra-headers]
  (let [id (:id message)
        listen? (= "subscriptions/listen" (:method message))]
    (http/as-channel
     req
     {:on-open
      (fn [ch]
        (let [started (atom false)
              answered (atom false)
              send-fn (fn [m]
                        (when (and (response? m) (= id (:id m))) (reset! answered true))
                        (when (http/open? ch)
                          (if stream?
                            (let [chunk (sse-event m)
                                  close? (and (response? m) (= id (:id m)))]
                              (if (compare-and-set! started false true)
                                (http/send! ch {:status 200
                                                :headers (merge {"Content-Type" "text/event-stream"
                                                                 "Cache-Control" "no-cache"
                                                                 "X-Accel-Buffering" "no"}
                                                                extra-headers)
                                                :body chunk}
                                            close?)
                                (http/send! ch chunk close?)))
                            (when (response? m)
                              (http/send! ch (json-response (status-of m) m extra-headers) true)))))
              ;; a stateless request's context writes its own stream
              context (if (:stateless? context)
                        (server/session-context send-fn (:selection-value context) (:principal context))
                        context)]
          (when listen?
            (Thread/startVirtualThread
             (fn []
               (loop []
                 (Thread/sleep (long *keep-alive-ms*))
                 (when (http/open? ch)
                   (when @started (http/send! ch ":\n\n" false))
                   (recur))))))
          (http/on-close ch (fn [_status]
                              ;; closed before its response: the client cancelled it
                              (when-not @answered
                                (json-rpc/handle-message context {:jsonrpc "2.0" :method "notifications/cancelled"
                                                                  :params (cond-> {:requestId id}
                                                                            (json-rpc/request-version message)
                                                                            (assoc :_meta (get-in message [:params :_meta])))}))))
          (server/dispatch! context send-fn message)))})))

;; ---------------------------------------------------------------------------
;; The endpoint

(defn- stateless-request? [headers message]
  (let [hv (get headers "mcp-protocol-version")]
    (or (some #{hv} json-rpc/stateless-versions)
        (json-rpc/stateless? message)
        (and hv (not (some #{hv} json-rpc/supported-versions))))))

(defn- handle-stateless [req headers message selection principal]
  (let [hv (get headers "mcp-protocol-version")]
    (cond
      (and hv (not (some #{hv} json-rpc/stateless-versions)) (not (some #{hv} json-rpc/supported-versions)))
      (json-response 400 (error-body (:id message) -32022 (str "Unsupported protocol version: " hv)
                                     {:supported (into (vec json-rpc/stateless-versions) (rseq json-rpc/supported-versions))
                                      :requested hv}))

      :else
      (if-let [why (header-mismatch headers message)]
        (json-response 400 (error-body (:id message) -32020 (str "Header mismatch: " why)))
        (if-not (contains? message :id)
          {:status 202}
          (let [stream? (or (= "subscriptions/listen" (:method message))
                            (and (#{"tools/call" "resources/read"} (:method message))
                                 (some? (get-in message [:params :_meta :progressToken]))))]
            (answer req {:stateless? true :selection-value selection :principal principal :session (atom {})}
                    message stream? nil)))))))

(defn- own-session
  "The session `sid` when `principal` opened it, else nil: a session id is
   no credential, so another principal holding it gets nothing."
  [sessions sid principal]
  (let [ctx (get @sessions sid)]
    (when (and ctx (= (:principal/id (:principal ctx)) (:principal/id principal)))
      ctx)))

(defn- handle-handshake [req headers message sessions selection principal]
  (let [sid (get headers "mcp-session-id")]
    (cond
      (= "initialize" (:method message))
      (let [new-sid (str (random-uuid))
            ctx (server/session-context (fn [_]) selection principal)]
        (swap! sessions assoc new-sid ctx)
        (answer req ctx message false {"Mcp-Session-Id" new-sid}))

      (nil? sid) (json-response 400 (error-body (:id message) -32600 "Mcp-Session-Id header is missing (initialize first)"))

      (not (own-session sessions sid principal))
      (json-response 404 (error-body (:id message) -32600 "Unknown or ended session"))

      (not (contains? message :id))
      (do (json-rpc/handle-message (own-session sessions sid principal) message) {:status 202})

      :else (answer req (own-session sessions sid principal) message false nil))))

(defn- authenticate-request
  "The principal of request `req`, or nil: `authenticate` (the seam) on its
   bearer token, peer and headers. A seam that throws refuses."
  [authenticate token {:keys [headers remote-addr] :as req}]
  (try (authenticate {:transport :mcp-http
                      :bearer (principal/bearer (get headers "authorization"))
                      ;; the socket's peer, not http-kit's X-Forwarded-For
                      :remote-addr (if (:async-channel req) (principal/socket-peer req) remote-addr)
                      :headers headers
                      :local-token token})
       (catch Exception _ nil)))

(defn handler
  "The Ring handler of the MCP endpoint. `:authenticate` is the seam
   (default `principal/default-authenticate`: the bearer `token` is the local
   principal); every request is authenticated, a handshake session included."
  [{:keys [token allowed-origins selection sessions path authenticate]
    :or {path "/mcp" authenticate principal/default-authenticate}}]
  (fn [{:keys [uri request-method headers body] :as req}]
    (let [p (delay (authenticate-request authenticate token req))]
      (cond
        (not= path uri) {:status 404 :body "Not found"}

        (not (origin-ok? (get headers "origin") (set allowed-origins)))
        (json-response 403 (error-body nil -32600 "Forbidden origin"))

        (nil? @p)
        {:status 401 :headers {"WWW-Authenticate" "Bearer"} :body "Unauthorized"}

        (= :delete request-method)
        (if-let [sid (get headers "mcp-session-id")]
          (do (when (own-session sessions sid @p) (swap! sessions dissoc sid)) {:status 204})
          {:status 405 :headers {"Allow" "POST"}})

        (not= :post request-method) {:status 405 :headers {"Allow" "POST"}}

        :else
        (let [message (try (json/read-value (slurp body) read-mapper) (catch Exception _ ::unreadable))]
          (cond
            (or (= ::unreadable message) (not (map? message)))
            (json-response 400 json-rpc/parse-error-response)

            (stateless-request? headers message) (handle-stateless req headers message selection @p)

            :else (handle-handshake req headers message sessions selection @p)))))))

(defn start!
  "Serve MCP over HTTP: `{:port 17889 :bind \"127.0.0.1\" :token-file …
   :allowed-origins #{} :profile :toolsets :authenticate}` (`:authenticate`:
   the seam, see `handler`). Returns `{:port :token :stop}`."
  [{:keys [port bind token-file allowed-origins profile toolsets authenticate]
    :or {port 17889 bind "127.0.0.1"}}]
  (principal/check-bind! "MCP over HTTP" bind (or authenticate principal/default-authenticate))
  (let [token (ensure-token! token-file)
        sessions (atom {})
        stop (http/run-server (handler {:token token :allowed-origins allowed-origins :sessions sessions
                                        :authenticate (or authenticate principal/default-authenticate)
                                        :selection (surface/selection {:profile profile :toolsets toolsets})})
                              {:port port :ip bind :legacy-return-value? false})]
    {:port (http/server-port stop) :token token
     :stop (fn [] (server/close-subscriptions!) (http/server-stop! stop))}))
