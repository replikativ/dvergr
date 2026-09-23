(ns dvergr.mcp.server-test
  "Tests for the dvergr MCP server — now a pure projection of `dvergr.ops` (+ the
   `dvergr.tools` registry for coding tools). These check the derivation and the
   JSON-RPC protocol shape in-process (no live daemon, no network). Live tool/
   resource round-trips against a running daemon are exercised from the REPL."
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.discourse :as d]
            [dvergr.mcp.server :as server]
            [dvergr.mcp.json-rpc :as json-rpc]
            [dvergr.mcp.surface :as surface]
            [dvergr.ops :as ops]
            [dvergr.room.registry :as rreg]
            [dvergr.room.store.memory :as memory]
            [jsonista.core]
            [org.replikativ.spindel.engine.core :as ec]))

(defn- ctx []
  (server/session-context (fn [_] nil) (surface/selection {:profile "admin"})))

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

;; ============================================================================
;; Profiles, annotations, schemas, results
;; ============================================================================

(defn- list-tools [c]
  (get-in (json-rpc/handle-message c {:jsonrpc "2.0" :id 2 :method "tools/list" :params {}})
          [:result :tools]))

(defn- session [& [meta]]
  (let [c (server/session-context (fn [_] nil) (surface/selection {}))]
    (json-rpc/handle-message c {:jsonrpc "2.0" :id 1 :method "initialize"
                                :params (cond-> {:protocolVersion "2025-06-18" :capabilities {}
                                                 :clientInfo {:name "t" :version "1"}}
                                          meta (assoc :_meta meta))})
    (json-rpc/handle-message c {:jsonrpc "2.0" :method "notifications/initialized"})
    c))

(deftest the-default-profile-is-small-and-leaves-out-host-tools
  (let [names (set (map :name (list-tools (session))))]
    (is (<= (count names) 22) "fits clients that cap tools around 40, with room for others")
    (is (every? names ["workflow_attempt" "room_fork" "room_merge" "room_discard"
                       "room_wallet" "clojure_eval" "room_list"]))
    (is (not-any? names ["shell" "read_file" "write_file" "agent_delete" "system_stats"])
        "file/shell tools duplicate the host's; admin ops are opt-in")))

(deftest a-client-chooses-its-profile-and-toolsets-in-initialize-meta
  (let [admin (set (map :name (list-tools (session {:dvergr/profile "admin"}))))
        plus-code (set (map :name (list-tools (session {:dvergr/toolsets "code"}))))
        ro (list-tools (session {:dvergr/profile "readonly"}))]
    (is (contains? admin "shell"))
    (is (= (count admin) (count @server/tool-definitions)) "admin sees every tool")
    (is (and (contains? plus-code "read_file") (contains? plus-code "workflow_attempt")))
    (is (seq ro))
    (is (every? #(true? (get-in % [:annotations :readOnlyHint])) ro))
    (is (not-any? #{"room_post" "workflow_attempt" "clojure_eval"} (map :name ro)))))

(deftest an-unknown-profile-fails-at-connect
  (let [c (server/session-context (fn [_] nil) (surface/selection {}))
        resp (json-rpc/handle-message c {:jsonrpc "2.0" :id 1 :method "initialize"
                                         :params {:protocolVersion "2025-06-18"
                                                  :_meta {:dvergr/profile "everything"}}})]
    (is (:error resp))
    (is (re-find #"Unknown dvergr MCP profile" (get-in resp [:error :message])))))

(deftest a-hidden-tool-cannot-be-called
  (let [resp (json-rpc/handle-message (session) {:jsonrpc "2.0" :id 3 :method "tools/call"
                                                 :params {:name "shell" :arguments {:command "id"}}})]
    (is (:error resp) "not listed for this session, so not callable")
    (is (nil? (:result resp)))))

(deftest every-tool-is-classified
  (testing "every op has a toolset and every write op its annotations"
    (doseq [[op {:keys [kind]}] ops/specification]
      (is (contains? surface/op-toolsets op) (str op " has a toolset"))
      (when (= :write kind)
        (is (contains? surface/write-annotations op) (str op " has write annotations")))))
  (testing "every registry tool has annotations"
    (doseq [tname (keys @@(requiring-resolve 'dvergr.tools/registry))]
      (is (contains? surface/coding-annotations tname) (str tname " has annotations")))))

(defn- schema-problems
  "What a strict model API (OpenAI strict, Gemini) would reject in `schema`."
  [schema]
  (let [problems (atom [])]
    (letfn [(walk [x path]
              (cond
                (map? x) (do (doseq [k [:oneOf :anyOf :allOf :not :$ref :$defs :definitions]]
                               (when (contains? x k) (swap! problems conj [path k])))
                             (doseq [[k v] x] (walk v (conj path k))))
                (sequential? x) (doseq [[i v] (map-indexed vector x)] (walk v (conj path i)))))]
      (walk schema [])
      (when-not (= "object" (:type schema)) (swap! problems conj [:root :type]))
      (when-not (false? (:additionalProperties schema)) (swap! problems conj [:root :additionalProperties]))
      (when-not (map? (:properties schema)) (swap! problems conj [:root :properties])))
    @problems))

(deftest every-tool-works-in-strict-clients
  (doseq [{:keys [name inputSchema annotations description title]} @server/tool-definitions]
    (is (re-matches #"[a-z][a-z0-9_]{0,39}" name)
        (str name ": a-z0-9_ and at most 40 chars (Cursor's 60 with the server prefix)"))
    (is (empty? (schema-problems inputSchema)) (str name ": " (schema-problems inputSchema)))
    (is (seq description) (str name " is described"))
    (is (seq title) (str name " has a title"))
    (is (every? #(boolean? (get annotations %))
                [:readOnlyHint :destructiveHint :idempotentHint :openWorldHint])
        (str name " carries every hint"))))

(deftest version-negotiation-answers-what-the-client-speaks
  (doseq [v ["2024-11-05" "2025-03-26" "2025-06-18" "2025-11-25"]]
    (is (= v (json-rpc/negotiate-version v))))
  (is (= "2025-11-25" (json-rpc/negotiate-version "2099-01-01")) "else the latest")
  (let [resp (json-rpc/handle-message (server/session-context (fn [_] nil) (surface/selection {}))
                                      {:jsonrpc "2.0" :id 1 :method "initialize"
                                       :params {:protocolVersion "2025-06-18"}})]
    (is (= "2025-06-18" (get-in resp [:result :protocolVersion])))
    (is (re-find #"room" (get-in resp [:result :instructions])) "instructions are sent")))

(deftest results-are-json-with-structured-content
  (let [r (surface/data-result {:run/status :completed :n 2 :id #uuid "00000000-0000-0000-0000-000000000001"})]
    (is (= {"run/status" "completed" "n" 2 "id" "00000000-0000-0000-0000-000000000001"}
           (:structuredContent r)))
    (is (re-find #"\"run/status\":\"completed\"" (-> r :content first :text))))
  (is (= {"result" [1 2]} (:structuredContent (surface/data-result [1 2]))) "non-maps are wrapped")
  (let [big (surface/data-result {:s (apply str (repeat (+ 10 surface/max-text-chars) "x"))})]
    (is (nil? (:structuredContent big)))
    (is (re-find #"result cut at" (-> big :content first :text))))
  (is (true? (:isError (surface/tool-result {:type :error :error "nope" :suggestion "try x"}))))
  (is (= "hello" (-> (surface/tool-result {:type :success :content "hello"}) :content first :text))))

(deftest a-coding-tool-needs-a-room
  (with-redefs [server/current-daemon (fn [] {:execution-ctx nil})]
    (let [resp (json-rpc/handle-message (session) {:jsonrpc "2.0" :id 3 :method "tools/call"
                                                   :params {:name "clojure_eval"
                                                            :arguments {:code "(+ 1 2)"}}})]
      (is (true? (get-in resp [:result :isError])))
      (is (re-find #"needs a room" (-> resp :result :content first :text))))))

(deftest an-op-round-trips-through-the-protocol
  (let [room (d/make-room {:id :mcp-server-test :store (memory/make)})]
    (try
      (binding [ec/*execution-context* (:ctx room)]
        (rreg/register! room)
        (with-redefs [server/current-daemon (fn [] {:execution-ctx (:ctx room)})]
          (let [resp (json-rpc/handle-message (session) {:jsonrpc "2.0" :id 3 :method "tools/call"
                                                         :params {:name "room_detail"
                                                                  :arguments {:room "mcp-server-test"}}})
                result (:result resp)]
            (is (false? (:isError result)) (pr-str result))
            (is (map? (:structuredContent result)))
            (is (= (:structuredContent result)
                   (jsonista.core/read-value (-> result :content first :text)))
                "text and structuredContent carry the same value"))))
      (finally
        (try (rreg/unregister! (:id room)) (catch Throwable _ nil))
        (d/close-room! room)))))
