(ns dvergr.mcp.conformance-test
  "The protocol as each version sees it, in process and over TCP: the
   handshake versions (2024-11-05 … 2025-11-25: `initialize`, then requests)
   and the stateless 2026-07-28 (every request names its version in `_meta`,
   `server/discover`, results that say they are complete and cacheable). One
   connection may carry both eras. No daemon: tools answer with a clean
   error, which is enough to see the envelope."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [dvergr.mcp.json-rpc :as json-rpc]
            [dvergr.mcp.server :as server]
            [dvergr.mcp.surface :as surface]
            [jsonista.core :as json])
  (:import (java.io BufferedReader BufferedWriter InputStreamReader OutputStreamWriter)
           (java.net Socket)))

(def ^:private modern "2026-07-28")
(def ^:private handshake-versions ["2024-11-05" "2025-06-18" "2025-11-25"])

(defn- modern-meta [& [extra]]
  (merge {:io.modelcontextprotocol/protocolVersion modern
          :io.modelcontextprotocol/clientInfo {:name "t" :version "1"}
          :io.modelcontextprotocol/clientCapabilities {}}
         extra))

;; ---- transports: each is (fn [message] -> response or nil)

(defn- in-process []
  (let [c (server/session-context (fn [_] nil) (surface/selection {}))]
    (fn [message] (json-rpc/handle-message c message))))

(use-fixtures :once
  (fn [f]
    ;; this namespace's own server, stopped after: a daemon test starts one too
    (let [started? (not (:running (server/status)))]
      (when started? (server/start! :port 0))
      (try (f) (finally (when started? (server/stop!)))))))

(def ^:private read-mapper (json/object-mapper {:decode-key-fn keyword}))

(defn- tcp
  "A client connection to this process's MCP server. A request waits for the
   response with its id; a notification returns nil at once."
  []
  (let [s (Socket. "127.0.0.1" (int (:port (server/status))))
        r (BufferedReader. (InputStreamReader. (.getInputStream s)))
        w (BufferedWriter. (OutputStreamWriter. (.getOutputStream s)))]
    (.setSoTimeout s 10000)
    (fn
      ([] (.close s))
      ([message]
       (.write w (json/write-value-as-string message))
       (.write w "\n")
       (.flush w)
       (when (contains? message :id)
         (loop []
           (let [resp (json/read-value (.readLine r) read-mapper)]
             (if (= (:id message) (:id resp)) resp (recur)))))))))

(def ^:private transports {:in-process in-process :tcp tcp})

(defmacro ^:private with-transport [[sym kind] & body]
  `(let [~sym ((transports ~kind))]
     (try ~@body
          (finally (when (= :tcp ~kind) (~sym))))))

(defn- handshake! [send version]
  (let [resp (send {:jsonrpc "2.0" :id 1 :method "initialize"
                    :params {:protocolVersion version :capabilities {}
                             :clientInfo {:name "t" :version "1"}}})]
    (send {:jsonrpc "2.0" :method "notifications/initialized"})
    resp))

(deftest the-handshake-versions
  (doseq [kind (keys transports) version handshake-versions]
    (testing (str (name kind) " " version)
      (with-transport [send kind]
        (is (= version (get-in (handshake! send version) [:result :protocolVersion])))
        (let [tools (send {:jsonrpc "2.0" :id 2 :method "tools/list" :params {}})]
          (is (seq (get-in tools [:result :tools])))
          (is (nil? (get-in tools [:result :resultType])) "no stateless fields in this era"))
        (is (= {} (:result (send {:jsonrpc "2.0" :id 3 :method "ping"}))))
        (is (= -32002 (get-in (send {:jsonrpc "2.0" :id 4 :method "resources/read"
                                     :params {:uri "nope://x"}})
                              [:error :code]))
            "a missing resource, as this era names it")))))

(deftest the-stateless-version
  (doseq [kind (keys transports)]
    (testing (name kind)
      (with-transport [send kind]
        (testing "server/discover needs no handshake and names every version"
          (let [{:keys [result]} (send {:jsonrpc "2.0" :id 1 :method "server/discover"
                                        :params {:_meta (modern-meta)}})]
            (is (= "complete" (:resultType result)))
            (is (= modern (first (:supportedVersions result))))
            (is (every? (set (:supportedVersions result)) handshake-versions))
            (is (map? (:capabilities result)))
            (is (string? (:instructions result)))
            (is (= "dvergr" (get-in result [:_meta :io.modelcontextprotocol/serverInfo :name])))
            (is (integer? (:ttlMs result)))))
        (testing "a request without initialize is answered, complete and cacheable"
          (let [{:keys [result]} (send {:jsonrpc "2.0" :id 2 :method "tools/list"
                                        :params {:_meta (modern-meta)}})]
            (is (seq (:tools result)))
            (is (= "complete" (:resultType result)))
            (is (#{"public" "private"} (:cacheScope result)))
            (is (integer? (:ttlMs result)))
            (is (some? (get-in result [:_meta :io.modelcontextprotocol/serverInfo])))))
        (testing "resources are listed and a missing one is invalid params"
          (is (= "complete" (get-in (send {:jsonrpc "2.0" :id 3 :method "resources/list"
                                           :params {:_meta (modern-meta)}})
                                    [:result :resultType])))
          (let [{:keys [error]} (send {:jsonrpc "2.0" :id 4 :method "resources/read"
                                       :params {:uri "nope://x" :_meta (modern-meta)}})]
            (is (= -32602 (:code error)))))
        (testing "a tool call is answered without a daemon, as a tool error"
          (let [{:keys [result]} (send {:jsonrpc "2.0" :id 5 :method "tools/call"
                                        :params {:name "room_list" :arguments {} :_meta (modern-meta)}})]
            (is (true? (:isError result)))
            (is (= "complete" (:resultType result)))))
        (testing "ping is gone from this era"
          (is (= -32601 (get-in (send {:jsonrpc "2.0" :id 6 :method "ping"
                                       :params {:_meta (modern-meta)}})
                                [:error :code]))))
        (testing "an unknown version is refused with the versions the server speaks"
          (let [{:keys [error]} (send {:jsonrpc "2.0" :id 7 :method "tools/list"
                                       :params {:_meta (modern-meta {:io.modelcontextprotocol/protocolVersion "2099-01-01"})}})]
            (is (= -32022 (:code error)))
            (is (= "2099-01-01" (get-in error [:data :requested])))
            (is (some #{modern} (get-in error [:data :supported])))))))))

(deftest a-stateless-request-brings-its-own-selection
  (let [send (in-process)
        names #(set (map :name (get-in (send {:jsonrpc "2.0" :id 1 :method "tools/list"
                                              :params {:_meta (modern-meta %)}})
                                       [:result :tools])))
        default (names nil)
        admin (names {:dvergr/profile "admin"})]
    (is (contains? admin "shell"))
    (is (not (contains? default "shell")))
    (is (= default (names nil)) "one request's selection does not stay on the connection")
    (testing "an unknown profile fails that request only"
      (is (:error (send {:jsonrpc "2.0" :id 2 :method "tools/list"
                         :params {:_meta (modern-meta {:dvergr/profile "everything"})}}))))))

(deftest one-connection-carries-both-eras
  (with-transport [send :tcp]
    (handshake! send "2025-11-25")
    (is (nil? (get-in (send {:jsonrpc "2.0" :id 2 :method "tools/list" :params {}}) [:result :resultType])))
    (is (= "complete" (get-in (send {:jsonrpc "2.0" :id 3 :method "tools/list"
                                     :params {:_meta (modern-meta)}})
                              [:result :resultType])))
    (is (= {} (:result (send {:jsonrpc "2.0" :id 4 :method "ping"}))) "the handshake session still stands")))
