(ns dvergr.mcp.authz-test
  "MCP for a principal: what a client asks for in `_meta` is capped to what
   its principal may see, every tool call and subscription is authorized, a
   denial is a clear error, and an HTTP session belongs to whoever opened it."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [dvergr.agent.run :as run]
            [dvergr.discourse :as d]
            [dvergr.mcp.http :as mcp-http]
            [dvergr.mcp.json-rpc :as json-rpc]
            [dvergr.mcp.server :as server]
            [dvergr.mcp.surface :as surface]
            [dvergr.principal :as principal]
            [dvergr.room.registry :as rreg]
            [dvergr.room.store.memory :as memory]
            [jsonista.core :as json]
            [org.replikativ.spindel.engine.context :as ctx]
            [org.replikativ.spindel.engine.core :as ec])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)))

(def alice {:principal/id "party/alice" :principal/kind :human :principal/scopes #{:dvergr/admin}})
(def bob {:principal/id "party/bob" :principal/kind :human :principal/scopes #{:dvergr/admin}})

(def ^:private authorize
  ;; the test's authorizer: Alice may do anything to the open room; nobody
  ;; else may touch a room
  (atom (fn [p {:keys [resolved resource]}]
          (or (= [:global] resource)
              (and (= "party/alice" (:principal/id p)) (= {:room "mcp-authz-open"} resolved))))))

(def ^:dynamic *ctx* nil)

(defn- daemon [c] {:execution-ctx c :config {:auth {:authorize (fn [p r] (@authorize p r))}}})

(use-fixtures :each
  (fn [f]
    (let [c (ctx/create-execution-context)
          rooms [(d/make-room {:id :mcp-authz-open :ctx c :store (memory/make)})
                 (d/make-room {:id :mcp-authz-closed :ctx c :store (memory/make)})]
          policy @authorize]
      (try
        (binding [ec/*execution-context* c] (run! rreg/register! rooms))
        (binding [*ctx* c]
          ;; a value, not the binding: HTTP requests run on other threads
          (with-redefs [server/current-daemon (constantly (daemon c))] (f)))
        (finally
          (reset! authorize policy)
          (binding [ec/*execution-context* c]
            (doseq [r rooms]
              (try (rreg/unregister! (:id r)) (catch Throwable _ nil))
              (d/close-room! r))))))))

(defn- session [principal & [meta]]
  (let [sent (atom [])
        c (server/session-context #(swap! sent conj %) (surface/selection {}) principal)]
    (json-rpc/handle-message c {:jsonrpc "2.0" :id 1 :method "initialize"
                                :params (cond-> {:protocolVersion "2025-06-18" :capabilities {}
                                                 :clientInfo {:name "t" :version "1"}}
                                          meta (assoc :_meta meta))})
    (json-rpc/handle-message c {:jsonrpc "2.0" :method "notifications/initialized"})
    (assoc c ::sent sent)))

(defn- tool-names [c]
  (set (map :name (get-in (json-rpc/handle-message c {:jsonrpc "2.0" :id 2 :method "tools/list" :params {}})
                          [:result :tools]))))

(defn- call-tool [c tname args]
  (json-rpc/handle-message c {:jsonrpc "2.0" :id 3 :method "tools/call"
                              :params {:name tname :arguments args}}))

;; ---------------------------------------------------------------------------
;; Server-side caps

(deftest a-client-narrows-its-selection-but-never-widens-it
  (let [rooms-only (assoc alice :principal/scopes #{:toolset/rooms})
        rooms-tools (tool-names (session rooms-only))]
    (testing "asking for the admin profile in _meta gets the principal's toolsets"
      (let [asked (tool-names (session rooms-only {:dvergr/profile "admin"}))]
        (is (seq asked))
        (is (= asked rooms-tools))
        (is (contains? asked "room_post"))
        (is (not-any? asked ["room_purge" "agent_delete" "shell" "clojure_eval" "catalog_benchmark"]))))
    (testing "nor by naming a tool outside them"
      (let [c (session rooms-only {:dvergr/tools "room_purge,room_list"})]
        (is (= #{"room_list"} (tool-names c)))
        (is (= -32602 (get-in (call-tool c "room_purge" {:room "x" :confirm "x"}) [:error :code])))))
    (testing "a client may still narrow"
      (is (= #{"room_list"} (tool-names (session alice {:dvergr/tools "room_list"})))))
    (testing "the read-only scope keeps it read-only, whatever it asks"
      (let [ro (tool-names (session (update alice :principal/scopes conj :dvergr/read-only)
                                    {:dvergr/profile "admin"}))]
        (is (contains? ro "room_list"))
        (is (not-any? ro ["room_post" "room_create" "clojure_eval"]))))
    (testing "no principal scope: nothing"
      (is (empty? (tool-names (session (assoc bob :principal/scopes #{}) {:dvergr/profile "admin"})))))
    (testing "a runtime tool (no authorization of its own) is the local principal's only"
      (is (nil? (surface/principal-toolsets principal/local-principal)))
      (is (not (contains? (surface/principal-toolsets alice) :extra))))))

;; ---------------------------------------------------------------------------
;; Authorized calls

(deftest a-denied-tool-call-is-an-error-result
  (let [ok (call-tool (session alice {:dvergr/profile "admin"}) "room_detail" {:room "mcp-authz-open"})
        denied (call-tool (session bob {:dvergr/profile "admin"}) "room_detail" {:room "mcp-authz-open"})]
    (is (false? (get-in ok [:result :isError])) (pr-str ok))
    (is (true? (get-in denied [:result :isError])))
    (is (re-find #"Not authorized" (-> denied :result :content first :text)))))

(deftest a-coding-tool-is-authorized-before-it-touches-the-room
  (let [r (call-tool (session bob {:dvergr/profile "admin"}) "clojure_eval"
                     {:room "mcp-authz-open" :code "(+ 1 2)"})]
    (is (true? (get-in r [:result :isError])))
    (is (re-find #"Not authorized.*clojure_eval.*admin" (-> r :result :content first :text)))))

(deftest room-list-is-filtered-over-mcp
  (let [ids #(set (map (fn [r] (get r "id")) (get-in % [:result :structuredContent "result"])))]
    (is (= #{"mcp-authz-open"} (ids (call-tool (session alice {:dvergr/profile "admin"}) "room_list" {}))))
    (is (= #{} (ids (call-tool (session bob {:dvergr/profile "admin"}) "room_list" {}))))))

(deftest a-subscription-ends-when-its-access-does
  (let [c (session alice {:dvergr/profile "admin"})
        uri "experiment://mcp-authz-open/progress"
        updates #(filter (fn [m] (= "notifications/resources/updated" (:method m))) @(::sent c))
        room (binding [ec/*execution-context* *ctx*] (rreg/lookup :mcp-authz-open))
        change! #(binding [ec/*execution-context* *ctx*]
                   (run/finish! (:run/id (run/start! room :worker (random-uuid) nil)) :completed))
        wait-for (fn [pred] (loop [n 0] (cond (pred) true (< n 100) (do (Thread/sleep 20) (recur (inc n))) :else false)))]
    (testing "bob may not subscribe at all"
      (is (= -32002 (get-in (json-rpc/handle-message (session bob {:dvergr/profile "admin"})
                                                     {:jsonrpc "2.0" :id 7 :method "resources/subscribe"
                                                      :params {:uri uri}})
                            [:error :code]))))
    (json-rpc/handle-message c {:jsonrpc "2.0" :id 7 :method "resources/subscribe" :params {:uri uri}})
    (change!)
    (is (wait-for #(seq (updates))) "alice is notified")
    (reset! authorize (constantly false))
    (Thread/sleep 400)
    (let [n (count (updates))]
      (change!)
      (Thread/sleep 600)
      (is (= n (count (updates))) "revoked: no more notifications, re-checked at emit"))))

;; ---------------------------------------------------------------------------
;; Over HTTP

(def ^:private client (HttpClient/newHttpClient))

(defn- http-call [port token body & [headers]]
  (let [b (-> (HttpRequest/newBuilder (URI. (str "http://127.0.0.1:" port "/mcp")))
              (.method (if body "POST" "DELETE")
                       (if body
                         (HttpRequest$BodyPublishers/ofString (json/write-value-as-string body))
                         (HttpRequest$BodyPublishers/noBody))))]
    (doseq [[k v] (merge {"Authorization" (str "Bearer " token) "Content-Type" "application/json"
                          "Accept" "application/json, text/event-stream"}
                         headers)]
      (.header b k v))
    (let [r (.send client (.build b) (HttpResponse$BodyHandlers/ofString))]
      {:status (.statusCode r)
       :sid (first (.allValues (.headers r) "mcp-session-id"))
       :body (let [s (.body r)] (when-not (str/blank? s)
                                  (try (json/read-value s json/keyword-keys-object-mapper) (catch Exception _ s))))})))

(deftest over-http-a-principal-is-authenticated-per-request-and-owns-its-session
  (let [token-file (str (System/getProperty "java.io.tmpdir") "/dvergr-mcp-authz-" (random-uuid) "/token")
        s (mcp-http/start! {:port 0 :token-file token-file :profile "admin"
                            :authenticate (fn [{:keys [bearer]}] ({"ta" alice "tb" bob} bearer))})
        port (:port s)
        legacy {"MCP-Protocol-Version" "2025-06-18"}
        init {:jsonrpc "2.0" :id 1 :method "initialize"
              :params {:protocolVersion "2025-06-18" :capabilities {} :clientInfo {:name "t" :version "1"}}}
        detail {:jsonrpc "2.0" :id 2 :method "tools/call"
                :params {:name "room_detail" :arguments {:room "mcp-authz-open"}}}]
    (try
      (testing "the configured authenticator replaces the token file"
        (is (= 401 (:status (http-call port (:token s) init legacy))) "the local token is not configured")
        (is (= 401 (:status (http-call port "nobody" init legacy)))))
      (let [{:keys [sid status]} (http-call port "ta" init legacy)]
        (is (= 200 status))
        (http-call port "ta" {:jsonrpc "2.0" :method "notifications/initialized"} (assoc legacy "Mcp-Session-Id" sid))
        (testing "alice calls in her session"
          (let [r (http-call port "ta" detail (assoc legacy "Mcp-Session-Id" sid))]
            (is (false? (get-in r [:body :result :isError])) (pr-str r))))
        (testing "bob holding alice's session id gets nothing"
          (is (= 404 (:status (http-call port "tb" detail (assoc legacy "Mcp-Session-Id" sid)))))
          (http-call port "tb" nil (assoc legacy "Mcp-Session-Id" sid))
          (is (= 200 (:status (http-call port "ta" detail (assoc legacy "Mcp-Session-Id" sid))))
              "nor can he end it")))
      (testing "a denied call is an error result, stateless era included"
        (let [r (http-call port "tb" (assoc-in detail [:params :_meta]
                                               {:io.modelcontextprotocol/protocolVersion "2026-07-28"
                                                :io.modelcontextprotocol/clientInfo {:name "t" :version "1"}
                                                :io.modelcontextprotocol/clientCapabilities {}
                                                :dvergr/profile "admin"})
                           {"MCP-Protocol-Version" "2026-07-28" "Mcp-Method" "tools/call"
                            "Mcp-Name" "room_detail"})]
          (is (= 200 (:status r)))
          (is (true? (get-in r [:body :result :isError])))
          (is (re-find #"Not authorized" (get-in r [:body :result :content 0 :text])))))
      (finally ((:stop s))))))

(deftest resources-follow-the-scope-cap
  (let [rooms-only (assoc alice :principal/scopes #{:toolset/rooms})
        c (session rooms-only)
        uri "experiment://mcp-authz-open/progress"]
    (testing "a resource of a toolset outside the scopes is not there"
      (is (= -32002 (get-in (json-rpc/handle-message c {:jsonrpc "2.0" :id 5 :method "resources/read"
                                                        :params {:uri uri}})
                            [:error :code])))
      (is (= -32002 (get-in (json-rpc/handle-message c {:jsonrpc "2.0" :id 6 :method "resources/subscribe"
                                                        :params {:uri uri}})
                            [:error :code])))
      (is (not-any? #(= "experiment_progress" (:name %))
                    (get-in (json-rpc/handle-message c {:jsonrpc "2.0" :id 7 :method "resources/templates/list"
                                                        :params {}})
                            [:result :resourceTemplates]))))
    (testing "within them it reads"
      (is (seq (get-in (json-rpc/handle-message c {:jsonrpc "2.0" :id 8 :method "resources/read"
                                                   :params {:uri "room://mcp-authz-open/messages"}})
                       [:result :contents]))))))

(deftest a-forwarding-header-never-makes-a-local-caller-over-real-http
  ;; http-kit's :remote-addr IS the first X-Forwarded-For entry; the seam must
  ;; see the socket's peer, and the default must refuse any forwarded request
  (let [token-file (str (System/getProperty "java.io.tmpdir") "/dvergr-mcp-xff-" (random-uuid) "/token")
        seen (atom nil)
        s (mcp-http/start! {:port 0 :token-file token-file :profile "admin"})
        t (mcp-http/start! {:port 0 :token-file token-file :profile "admin"
                            :authenticate (fn [r] (reset! seen (:remote-addr r)) nil)})
        init {:jsonrpc "2.0" :id 1 :method "initialize"
              :params {:protocolVersion "2025-06-18" :capabilities {} :clientInfo {:name "t" :version "1"}}}
        legacy {"MCP-Protocol-Version" "2025-06-18"}]
    (try
      (is (= 200 (:status (http-call (:port s) (:token s) init legacy))) "the token, directly: local")
      (is (= 401 (:status (http-call (:port s) (:token s) init (assoc legacy "X-Forwarded-For" "127.0.0.1"))))
          "the same token, forwarded: refused")
      (http-call (:port t) "x" init (assoc legacy "X-Forwarded-For" "10.9.9.9"))
      (is (= "127.0.0.1" @seen) "the authenticator sees the socket's peer, not the header")
      (finally ((:stop s)) ((:stop t))))))
