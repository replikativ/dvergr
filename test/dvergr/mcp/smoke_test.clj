(ns dvergr.mcp.smoke-test
  "dvergr as an MCP client uses it: a daemon serving MCP on a loopback port,
   JSON-RPC over the socket (what `bin/dvergr-mcp` relays), no model. The
   REPL-first `code` profile, an eval that calls the API through
   `dvergr.ops`, and a connection pinned to one room (what a benchmark gives
   an external agent). `dev/mcp_smoke.py` drives the same through the relay."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [dvergr.ops :as ops]
            [dvergr.orchestration.daemon :as daemon]
            [dvergr.substrate.paths :as paths]
            [dvergr.system.db :as sdb]
            [dvergr.test-support :as support]
            [jsonista.core :as j])
  (:import (java.io BufferedReader BufferedWriter InputStreamReader OutputStreamWriter)
           (java.net Socket)))

(def ^:dynamic *daemon* nil)

(use-fixtures :once
  (fn [f]
    (let [prev-home (paths/home)]
      (paths/set-home! (str (System/getProperty "java.io.tmpdir") "/dvergr-mcp-smoke-" (random-uuid)))
      (sdb/reset-conn!)
      (let [d (daemon/start! {:agents {} :mcp {:port 0}})]
        (try
          (binding [*daemon* d] (f))
          (finally
            (try (daemon/stop! d) (catch Exception _))
            (sdb/reset-conn!)
            (paths/set-home! prev-home)))))))

(defn- connect
  "An MCP session over the daemon's socket, initialized with `meta` as
   `_meta` (what the relay sends): a fn from [method params] to the result."
  [meta]
  (let [s (Socket. "127.0.0.1" (int (get-in *daemon* [:mcp-server :port])))
        in (BufferedReader. (InputStreamReader. (.getInputStream s)))
        out (BufferedWriter. (OutputStreamWriter. (.getOutputStream s)))
        n (atom 0)
        send! (fn [m] (.write out (j/write-value-as-string m)) (.write out "\n") (.flush out))
        rpc (fn [method params]
              (let [id (swap! n inc)]
                (send! {:jsonrpc "2.0" :id id :method method :params params})
                (loop []
                  (let [r (j/read-value (.readLine in) j/keyword-keys-object-mapper)]
                    (if (= id (:id r)) r (recur))))))]
    (rpc "initialize" {:protocolVersion "2025-06-18" :capabilities {}
                       :clientInfo {:name "smoke" :version "0"} :_meta meta})
    (send! {:jsonrpc "2.0" :method "notifications/initialized"})
    {:rpc rpc :close #(.close s)}))

(defn- text [r] (apply str (map :text (get-in r [:result :content]))))

(deftest the-code-profile-is-a-repl-onto-the-whole-api
  (ops/invoke *daemon* :room/create {:slug "smoke" :title "Smoke room"})
  (let [{:keys [rpc close]} (connect {:dvergr/profile "code"})]
    (try
      (is (= #{"clojure_eval" "repl_describe"}
             (set (map :name (get-in (rpc "tools/list" {}) [:result :tools])))))
      (testing "one program calls the API"
        (let [r (rpc "tools/call" {:name "clojure_eval"
                                   :arguments {:room "smoke"
                                               :code "(mapv :id (dvergr.ops/room-list {}))"}})]
          (is (re-find #"\"smoke\"" (text r)) (text r))))
      (testing "and the API is discoverable"
        (is (re-find #"scorecard-detail"
                     (text (rpc "tools/call" {:name "repl_describe"
                                              :arguments {:room "smoke" :query "scorecard"}})))))
      (finally (close)))))

(deftest a-pinned-connection-works-in-its-room-only
  (ops/invoke *daemon* :room/create {:slug "pinned" :title "Pinned room"})
  (let [{:keys [rpc close]} (connect {:dvergr/room "pinned" :dvergr/tools "read_file,write_file"})]
    (try
      (let [tools (get-in (rpc "tools/list" {}) [:result :tools])]
        (is (= #{"read_file" "write_file"} (set (map :name tools))))
        (is (not-any? #(get-in % [:inputSchema :properties :room]) tools)))
      (is (re-find #"Wrote" (text (rpc "tools/call" {:name "write_file"
                                                     :arguments {:path "/wiki/a.md" :content "# a"}}))))
      (is (= "# a" (text (rpc "tools/call" {:name "read_file" :arguments {:path "/wiki/a.md"}}))))
      (is (re-find #"room pinned only"
                   (text (rpc "tools/call" {:name "read_file" :arguments {:path "/x" :room "smoke"}}))))
      (finally (close)))))

(deftest the-relay-serves-a-stateless-client-with-its-pins
  ;; bin/dvergr-mcp between a 2026-07-28 client (no initialize) and the
  ;; daemon: discover is the daemon's, the relay's --room/--tools pins reach
  ;; each request, a subscription is acknowledged. Needs babashka.
  (if-not (some #(.canExecute (java.io.File. (str % "/bb")))
                (clojure.string/split (System/getenv "PATH") #":"))
    (support/skip! "the-relay-serves-a-stateless-client-with-its-pins: no bb on PATH")
    (do
      (ops/invoke *daemon* :room/create {:slug "relayed" :title "Relayed room"})
      (let [p (.start (ProcessBuilder. ["bb" "bin/dvergr-mcp" "--no-start"
                                        "--port" (str (get-in *daemon* [:mcp-server :port]))
                                        "--room" "relayed" "--tools" "read_file,write_file"]))
            out (BufferedWriter. (OutputStreamWriter. (.getOutputStream p)))
            in (BufferedReader. (InputStreamReader. (.getInputStream p)))
            meta {:io.modelcontextprotocol/protocolVersion "2026-07-28"
                  :io.modelcontextprotocol/clientInfo {:name "relay-test" :version "0"}
                  :io.modelcontextprotocol/clientCapabilities {}}
            send! (fn [m] (.write out (j/write-value-as-string m)) (.write out "\n") (.flush out))
            read! (fn [] (j/read-value (.readLine in) j/keyword-keys-object-mapper))
            await (fn [pred] (loop [n 0] (let [m (read!)] (if (or (pred m) (> n 50)) m (recur (inc n))))))]
        (try
          (send! {:jsonrpc "2.0" :id 1 :method "server/discover" :params {:_meta meta}})
          (let [r (await #(= 1 (:id %)))]
            (is (some #{"2026-07-28"} (get-in r [:result :supportedVersions])))
            (is (not (re-find #"starting" (str (get-in r [:result :instructions])))) "the daemon's answer, not the relay's"))
          (send! {:jsonrpc "2.0" :id 2 :method "tools/list" :params {:_meta meta}})
          (is (= #{"read_file" "write_file"}
                 (set (map :name (get-in (await #(= 2 (:id %))) [:result :tools]))))
              "the relay's pins reach a request without initialize")
          (send! {:jsonrpc "2.0" :id 3 :method "subscriptions/listen"
                  :params {:_meta meta :notifications {:toolsListChanged true}}})
          (let [ack (await #(= "notifications/subscriptions/acknowledged" (:method %)))]
            (is (= 3 (get-in ack [:params :_meta :io.modelcontextprotocol/subscriptionId]))))
          (finally (.destroy p)))))))

