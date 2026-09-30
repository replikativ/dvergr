(ns dvergr.mcp.http-test
  "MCP over Streamable HTTP, over a real loopback listener: security (Origin,
   bearer token), the stateless era's header validation and status codes,
   JSON and SSE responses (progress, subscriptions/listen), notifications,
   and handshake sessions on the same endpoint. No daemon."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [dvergr.mcp.http :as mcp-http]
            [dvergr.mcp.server :as server]
            [jsonista.core :as json])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)
           (java.util.concurrent TimeUnit)))

(def ^:dynamic *server* nil)

(use-fixtures :once
  (fn [f]
    (let [token-file (str (System/getProperty "java.io.tmpdir") "/dvergr-mcp-http-" (random-uuid) "/token")
          s (mcp-http/start! {:port 0 :token-file token-file :profile "admin"})]
      (try (binding [*server* s] (f))
           (finally ((:stop s)))))))

(def ^:private client (HttpClient/newHttpClient))
(def ^:private modern "2026-07-28")

(defn- meta' [] {:io.modelcontextprotocol/protocolVersion modern
                 :io.modelcontextprotocol/clientInfo {:name "t" :version "1"}
                 :io.modelcontextprotocol/clientCapabilities {}})

(defn- request
  "An HttpRequest to the endpoint; `headers` override the defaults (a
   valid token and the stateless headers derived from `body`)."
  [method body & [headers]]
  (let [defaults (cond-> {"Authorization" (str "Bearer " (:token *server*))
                          "Content-Type" "application/json"
                          "Accept" "application/json, text/event-stream"}
                   (map? body) (assoc "MCP-Protocol-Version" modern "Mcp-Method" (:method body))
                   (and (map? body) (get-in body [:params :name])) (assoc "Mcp-Name" (get-in body [:params :name])))
        hs (reduce-kv (fn [m k v] (if (nil? v) (dissoc m k) (assoc m k v))) defaults (or headers {}))
        b (-> (HttpRequest/newBuilder (URI. (str "http://127.0.0.1:" (:port *server*) "/mcp")))
              (.method method (if body
                                (HttpRequest$BodyPublishers/ofString (json/write-value-as-string body))
                                (HttpRequest$BodyPublishers/noBody))))]
    (doseq [[k v] hs] (.header b k v))
    (.build b)))

(defn- call [method body & [headers]]
  (let [r (.send client (request method body headers) (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode r)
     :headers (.map (.headers r))
     :content-type (first (.allValues (.headers r) "content-type"))
     :body (let [b (.body r)] (when-not (str/blank? b) (try (json/read-value b json/keyword-keys-object-mapper) (catch Exception _ b))))}))

(defn- rpc [id method params] {:jsonrpc "2.0" :id id :method method :params (assoc params :_meta (meta'))})

(deftest a-stateless-request-is-answered-as-json
  (let [{:keys [status content-type body]} (call "POST" (rpc 1 "tools/list" {}))]
    (is (= 200 status))
    (is (str/starts-with? content-type "application/json"))
    (is (= "complete" (get-in body [:result :resultType])))
    (is (seq (get-in body [:result :tools])))))

(deftest security
  (testing "a request without the token is refused"
    (is (= 401 (:status (call "POST" (rpc 1 "tools/list" {}) {"Authorization" nil}))))
    (is (= 401 (:status (call "POST" (rpc 1 "tools/list" {}) {"Authorization" "Bearer wrong"})))))
  (testing "a foreign Origin is refused (DNS rebinding); loopback is fine"
    (is (= 403 (:status (call "POST" (rpc 1 "tools/list" {}) {"Origin" "https://evil.example"}))))
    (is (= 200 (:status (call "POST" (rpc 1 "tools/list" {}) {"Origin" "http://localhost:3000"})))))
  (testing "the token file is private"
    (is (= 64 (count (:token *server*))))))

(deftest headers-must-match-the-body
  (let [code #(get-in % [:body :error :code])]
    (let [r (call "POST" (rpc 1 "tools/list" {}) {"Mcp-Method" nil})]
      (is (= 400 (:status r))) (is (= -32020 (code r))))
    (let [r (call "POST" (rpc 1 "tools/list" {}) {"Mcp-Method" "tools/call"})]
      (is (= 400 (:status r))) (is (= -32020 (code r))))
    (let [r (call "POST" (rpc 2 "tools/call" {:name "room_list" :arguments {}}) {"Mcp-Name" "other"})]
      (is (= 400 (:status r))) (is (re-find #"Mcp-Name" (get-in r [:body :error :message]))))
    (testing "a Base64-encoded Mcp-Name is decoded first"
      (let [enc (str "=?base64?" (.encodeToString (java.util.Base64/getEncoder) (.getBytes "room_list" "UTF-8")) "?=")]
        (is (= 200 (:status (call "POST" (rpc 3 "tools/call" {:name "room_list" :arguments {}}) {"Mcp-Name" enc}))))))
    (let [r (call "POST" (rpc 4 "tools/list" {}) {"MCP-Protocol-Version" "2025-11-25"})]
      (is (= 400 (:status r)) "the header's version is not the body's") (is (= -32020 (code r))))))

(deftest versions-methods-and-notifications
  (let [r (call "POST" (assoc-in (rpc 1 "tools/list" {}) [:params :_meta :io.modelcontextprotocol/protocolVersion] "2099-01-01")
                {"MCP-Protocol-Version" "2099-01-01"})]
    (is (= 400 (:status r)))
    (is (= -32022 (get-in r [:body :error :code]))))
  (let [r (call "POST" (rpc 2 "no/such" {}))]
    (is (= 404 (:status r)))
    (is (= -32601 (get-in r [:body :error :code]))))
  (is (= 202 (:status (call "POST" {:jsonrpc "2.0" :method "notifications/whatever" :params {:_meta (meta')}}))))
  (is (= 405 (:status (call "GET" nil))) "no GET stream")
  (is (= 400 (:status (call "POST" nil {"Content-Type" "application/json"}))) "no body"))

(defn- sse-lines
  "The data events of an SSE response, as maps, read until `done?` holds of
   the events so far (or `timeout-ms`); `on-open` runs once the stream is up."
  [req done? timeout-ms & [on-open]]
  (let [events (atom [])
        fut (future
              (let [r (.send client req (HttpResponse$BodyHandlers/ofLines))
                    it (.iterator (.body r))]
                (swap! events vary-meta assoc :content-type (first (.allValues (.headers r) "content-type")))
                (loop [opened? false]
                  (when (.hasNext it)
                    (let [line (.next it)]
                      (when (str/starts-with? line "data: ")
                        (swap! events conj (json/read-value (subs line 6) json/keyword-keys-object-mapper)))
                      (when (and on-open (not opened?) (seq @events)) (on-open))
                      (when-not (done? @events) (recur (or opened? (boolean (seq @events))))))))))]
    (try (.get ^java.util.concurrent.Future fut (long timeout-ms) TimeUnit/MILLISECONDS)
         (catch java.util.concurrent.TimeoutException _ (future-cancel fut)))
    @events))

(deftest a-long-request-with-a-progress-token-streams-progress-then-its-response
  (try
    (server/register-tool! {:name "slow_http" :description "sleeps" :inputSchema {:type "object"}}
                           (fn [_ _] (Thread/sleep 450) {:content [{:type "text" :text "done"}] :isError false}))
    (let [body (-> (rpc 5 "tools/call" {:name "slow_http" :arguments {}})
                   (assoc-in [:params :_meta :progressToken] "tok"))
          ;; the request runs on the HTTP server's thread: a binding would not reach it
          previous server/*progress-interval-ms*
          _ (alter-var-root #'server/*progress-interval-ms* (constantly 100))
          events (try (sse-lines (request "POST" body) #(some (fn [e] (= 5 (:id e))) %) 5000)
                      (finally (alter-var-root #'server/*progress-interval-ms* (constantly previous))))]
      (is (str/starts-with? (str (:content-type (meta events))) "text/event-stream"))
      (is (<= 2 (count (filter #(= "notifications/progress" (:method %)) events))))
      (is (= 5 (:id (last events))) "the response ends the stream"))
    (finally (server/unregister-tool! "slow_http"))))

(deftest a-subscription-streams-until-the-client-closes-it
  (try
    (let [body {:jsonrpc "2.0" :id 11 :method "subscriptions/listen"
                :params {:_meta (meta') :notifications {:toolsListChanged true}}}
          events (sse-lines (request "POST" body)
                            #(some (fn [e] (= "notifications/tools/list_changed" (:method e))) %)
                            5000
                            #(server/register-tool! {:name "listen_http" :description "probe" :inputSchema {:type "object"}}
                                                    (fn [_ _] {:content [] :isError false})))]
      (is (= "notifications/subscriptions/acknowledged" (:method (first events))))
      (is (= 11 (get-in (first events) [:params :_meta :io.modelcontextprotocol/subscriptionId])))
      (let [n (last events)]
        (is (= "notifications/tools/list_changed" (:method n)))
        (is (= 11 (get-in n [:params :_meta :io.modelcontextprotocol/subscriptionId])))))
    (finally (server/unregister-tool! "listen_http"))))

(deftest a-handshake-client-gets-a-session
  (let [init (call "POST" {:jsonrpc "2.0" :id 1 :method "initialize"
                           :params {:protocolVersion "2025-06-18" :capabilities {} :clientInfo {:name "t" :version "1"}}}
                   {"MCP-Protocol-Version" nil "Mcp-Method" nil})
        sid (first (get (:headers init) "mcp-session-id"))
        legacy {"MCP-Protocol-Version" "2025-06-18" "Mcp-Method" nil}]
    (is (= 200 (:status init)))
    (is (= "2025-06-18" (get-in init [:body :result :protocolVersion])))
    (is (string? sid))
    (is (= 202 (:status (call "POST" {:jsonrpc "2.0" :method "notifications/initialized"} (assoc legacy "Mcp-Session-Id" sid)))))
    (is (seq (get-in (call "POST" {:jsonrpc "2.0" :id 2 :method "tools/list" :params {}} (assoc legacy "Mcp-Session-Id" sid))
                     [:body :result :tools])))
    (is (= 400 (:status (call "POST" {:jsonrpc "2.0" :id 3 :method "tools/list" :params {}} legacy))) "no session")
    (is (= 204 (:status (call "DELETE" nil (assoc legacy "Mcp-Session-Id" sid)))))
    (is (= 404 (:status (call "POST" {:jsonrpc "2.0" :id 4 :method "tools/list" :params {}} (assoc legacy "Mcp-Session-Id" sid))))
        "an ended session")))
