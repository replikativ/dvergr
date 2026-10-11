(ns dvergr.test-registries-test
  "The registry fixture undoes a test's own changes and keeps what namespace
   loads registered meanwhile (#278)."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [dvergr.channels.core :as ch]
            [dvergr.mcp.server :as mcp]
            [dvergr.test-registries :as test-registries]
            [dvergr.tools :as tools]))

(defn- tool [tool-name description]
  {:name tool-name :description description
   :parameters {:type "object" :properties {}}
   :execute (fn [_ _] {:type :success :content description})})

(defn- mcp-names [] (set (map :name @mcp/tool-definitions)))

(deftest a-test-s-changes-are-undone-and-loads-are-kept
  (let [existing (str "registries-existing-" (random-uuid))
        added (str "registries-added-" (random-uuid))
        explicit (str "registries-explicit-" (random-uuid))
        loaded (str "registries-loaded-" (random-uuid))
        channel-tool (str "registries-channel-" (random-uuid))
        channel-id (keyword (str "registries-" (random-uuid)))
        source (io/file (System/getProperty "java.io.tmpdir") (str "registries-" (random-uuid) ".clj"))]
    (tools/register! (tool existing "before"))
    (try
      (let [snap (test-registries/snapshot)
            original (tools/get-tool existing)]
        ;; what a test does: replace a tool, add one, add an MCP tool, connect
        ;; a channel
        (swap! tools/registry assoc existing (assoc original :description "changed"))
        (tools/register! (tool added "added by the test"))
        (mcp/register-tool! {:name explicit :description "explicit" :inputSchema {:type "object"}}
                            (fn [_ _] {:content []}))
        (ch/connect! (ch/make-channel
                      {:id channel-id :type :mock
                       :capabilities #{:mock/read} :permissions #{:mock/read}
                       :tools [{:capability :mock/read :name channel-tool
                                :description "channel tool"
                                :parameters {:type "object" :properties {}}}]
                       :handlers {channel-tool (fn [_ _] {:type :success :content "x"})}
                       :connect! identity}))
        ;; what a namespace first loaded during the test does
        (spit source (pr-str `(dvergr.tools/register!
                               {:name ~loaded :description "loaded"
                                :parameters {:type "object" :properties {}}
                                :execute (fn [~'_ ~'_] {:type :success :content "loaded"})})))
        (load-file (.getPath source))
        (is (contains? (mcp-names) added) "the registry's tools are served over MCP")
        (test-registries/restore! snap)
        (testing "the test's own changes are undone"
          (is (= original (tools/get-tool existing)))
          (is (nil? (tools/get-tool added)))
          (is (not (contains? (mcp-names) added)))
          (is (not (contains? (mcp-names) explicit)))
          (is (nil? (get @mcp/tool-handlers explicit)))
          (is (nil? (ch/get-channel channel-id)))
          (is (nil? (tools/get-tool channel-tool)))
          (is (nil? (get @mcp/tool-handlers channel-tool))))
        (testing "a tool a namespace load registered stays"
          (is (= "loaded" (:description (tools/get-tool loaded))))
          (is (contains? (mcp-names) loaded))))
      (finally
        (.delete source)
        (swap! tools/registry dissoc existing loaded)))))
