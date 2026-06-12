(ns dvergr.mcp.server-test
  "Tests for the dvergr MCP server — now a pure projection of `dvergr.ops` (+ the
   `dvergr.tools` registry for coding tools). These check the derivation and the
   JSON-RPC protocol shape in-process (no live daemon, no network). Live tool/
   resource round-trips against a running daemon are exercised from the REPL."
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.mcp.server :as server]
            [dvergr.mcp.json-rpc :as json-rpc]
            [dvergr.ops :as ops]))

(defn- ctx []
  {:session       (server/make-session)
   :send-fn       (fn [_] nil)
   :tool-defs     server/tool-definitions
   :tool-handlers server/tool-handlers
   :resource-defs server/resource-definitions})

(defn- init! [c]
  (json-rpc/handle-message c {:jsonrpc "2.0" :id 1 :method "initialize"
                              :params {:protocolVersion "2024-11-05" :capabilities {}
                                       :clientInfo {:name "t" :version "1"}}})
  (json-rpc/handle-message c {:jsonrpc "2.0" :method "notifications/initialized"})
  c)

(deftest tools-derived-from-ops-and-registry
  (testing "every dvergr.ops op is an MCP tool (named by op->name) + coding tools"
    (let [names (set (map :name @server/tool-definitions))]
      (doseq [op (keys ops/specification)]
        (is (contains? names (ops/op->name op)) (str op " is a tool")))
      (is (contains? names "clojure_eval") "registry coding tools re-served")
      (is (= (set (keys @server/tool-handlers)) names) "one handler per tool def"))))

(deftest resources-derived-from-reads
  (testing "fixed resources + templates derive from the spec's :read ops"
    (let [{:keys [resources templates]} @server/resource-definitions]
      (is (some #(= "room://list" (:uri %)) resources))
      (is (some #(= "agent://list" (:uri %)) resources))
      (is (some #(= "room://{room}/messages" (:uriTemplate %)) templates))
      (is (some #(= "agent://{id}/config" (:uriTemplate %)) templates)))))

(deftest initialize-advertises-tools-and-resources
  (let [resp (json-rpc/handle-message (ctx)
                                      {:jsonrpc "2.0" :id 1 :method "initialize"
                                       :params {:protocolVersion "2024-11-05" :capabilities {} :clientInfo {}}})
        caps (get-in resp [:result :capabilities])]
    (is (some? (:tools caps)))
    (is (true? (get-in caps [:resources :subscribe])))))

(deftest tools-list-and-resources-list-return-derived
  (let [c (init! (ctx))]
    (let [tools (get-in (json-rpc/handle-message c {:jsonrpc "2.0" :id 2 :method "tools/list" :params {}})
                        [:result :tools])]
      (is (pos? (count tools)))
      (is (some #(= "room_list" (:name %)) tools)))
    (let [res (get-in (json-rpc/handle-message c {:jsonrpc "2.0" :id 3 :method "resources/list" :params {}})
                      [:result :resources])]
      (is (some #(= "room://list" (:uri %)) res)))))

(deftest tool-call-without-daemon-errors-cleanly
  (let [c (init! (ctx))
        resp (json-rpc/handle-message
              c {:jsonrpc "2.0" :id 2 :method "tools/call"
                 :params {:name "room_list" :arguments {}}})]
    ;; No current-daemon in a fresh JVM → a clean error result, never a crash.
    (is (map? (:result resp)))
    (is (true? (get-in resp [:result :isError])))))
