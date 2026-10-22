(ns dvergr.mcp.host-op-projection-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [dvergr.mcp.server :as server]
            [dvergr.mcp.surface :as surface]
            [dvergr.ops :as ops]
            [malli.json-schema :as mjs]))

(def ^:dynamic *projection* nil)

(defn- isolated [f]
  (let [registered (atom {}) definitions (atom []) handlers (atom {}) cache (atom {})
        notifications (atom []) transforms (atom []) transform mjs/transform]
    (with-redefs-fn {#'ops/registered registered
                     #'server/tool-definitions definitions
                     #'server/tool-handlers handlers
                     #'server/host-op-schemas cache
                     #'server/broadcast-notification! #(swap! notifications conj [%1 %2 %3])
                     #'mjs/transform (fn
                                       ([schema] (swap! transforms conj schema) (transform schema nil))
                                       ([schema options] (transform schema options)))}
      #(binding [*projection* {:registered registered :definitions definitions :handlers handlers
                               :cache cache :notifications notifications :transforms transforms}]
         (ops/watch-operations! ::projection server/sync-op-tools!)
         (f)))))

(use-fixtures :each isolated)

(defn- spec
  ([] (spec [:map [:value {:optional true} :string]]))
  ([schema] {:doc "A host operation" :kind :read :schema schema
             :authz {:action :read :resource (constantly [:global])}
             :impl (fn [_ _] {:version 1})}))

(defn- definitions [] @(:definitions *projection*))
(defn- notifications [] (count @(:notifications *projection*)))
(defn- transforms [] (count @(:transforms *projection*)))

(deftest only-new-or-changed-schemas-are-transformed
  (ops/register-op! ::alpha (spec))
  (is (= 1 (transforms)))
  (ops/register-op! ::beta (spec [:map [:count :int]]))
  (is (= 2 (transforms)) "Adding one operation transforms only its schema")
  (let [before (definitions) notices (notifications)]
    (server/sync-op-tools!)
    (swap! (:registered *projection*) #(into (array-map) (reverse (seq %))))
    (is (= before (definitions)))
    (is (= notices (notifications)))
    (is (= 2 (transforms))))
  (ops/register-op! ::alpha (assoc (spec) :doc "Changed documentation" :kind :write))
  (is (= 2 (transforms)) "Docs and annotations do not require schema conversion")
  (let [alpha (some #(when (= (ops/op->name ::alpha) (:name %)) %) (definitions))]
    (is (= "Changed documentation" (:description alpha)))
    (is (false? (get-in alpha [:annotations :readOnlyHint]))))
  (ops/register-op! ::alpha (spec [:map [:value :int]]))
  (is (= 3 (transforms)))
  (let [alpha (some #(when (= (ops/op->name ::alpha) (:name %)) %) (definitions))]
    (is (= "integer" (get-in alpha [:inputSchema :properties :value :type]))))
  (is (= #{::alpha ::beta} (set (keys @(:cache *projection*)))))
  (is (every? #(= #{:source :json} (set (keys %))) (vals @(:cache *projection*))))
  (ops/unregister-op! ::alpha)
  (ops/unregister-op! ::beta)
  (is (empty? (definitions)))
  (is (empty? @(:handlers *projection*)))
  (is (empty? @(:cache *projection*))))

(deftest same-name-definition-changes-notify-but-unchanged-definitions-do-not
  (let [original (spec)]
    (ops/register-op! ::alpha original)
    (is (= 1 (notifications)))
    (ops/register-op! ::alpha (into {} original))
    (is (= 1 (notifications)))
    (ops/register-op! ::alpha (assoc original :doc "A new description"))
    (is (= 2 (notifications)))
    (ops/register-op! ::alpha (assoc original :kind :write))
    (is (= 3 (notifications)))
    (ops/register-op! ::alpha (spec [:map [:other :boolean]]))
    (is (= 4 (notifications)))
    (is (= 2 (transforms)))
    (ops/unregister-op! ::alpha)
    (is (= 5 (notifications)))))

(deftest schema-and-metadata-come-from-one-registry-snapshot
  (let [calls (atom 0) snapshot {::alpha (assoc (spec) :doc "Captured description")}]
    (with-redefs [ops/operations (fn [] (swap! calls inc) snapshot)
                  ops/input-schema (fn [& _] (throw (AssertionError. "No live per-op schema lookup")))]
      (server/sync-op-tools!))
    (is (= 1 @calls))
    (is (= "Captured description" (:description (first (definitions)))))
    (is (= "string" (get-in (first (definitions)) [:inputSchema :properties :value :type])))))

(deftest cached-definitions-never-cache-implementation-or-authorization
  (let [original (spec) name (ops/op->name ::alpha)
        host {:execution-ctx (Object.)
              :config {:auth {:authorize (fn [_ request] (= :read (:action request)))}}}
        context {:principal {:principal/id "viewer" :principal/kind :human}
                 :selection (atom (surface/selection {}))}]
    (with-redefs-fn {#'server/embedded-host (atom host)}
      #(do
         (ops/register-op! ::alpha original)
         (let [handler (get @(:handlers *projection*) name)]
           (is (= 1 (get-in (handler context {}) [:structuredContent "version"])))
           (ops/register-op! ::alpha (assoc original :impl (fn [_ _] {:version 2})))
           (is (identical? handler (get @(:handlers *projection*) name)))
           (is (= 2 (get-in (handler context {}) [:structuredContent "version"])))
           (ops/register-op! ::alpha (assoc original :authz {:action :write :resource (constantly [:global])}))
           (is (identical? handler (get @(:handlers *projection*) name)))
           (is (true? (:isError (handler context {}))))
           (is (= 1 (notifications)))
           (is (= 1 (transforms))))))))

(deftest projection-failure-does-not-publish-half-a-manifest
  (ops/register-op! ::alpha (spec))
  (let [before (definitions) cache @(:cache *projection*) notices (notifications)]
    (is (thrown? Exception (ops/register-op! ::alpha (spec [:map [:value ::unknown-type]]))))
    (is (= before (definitions)))
    (is (= cache @(:cache *projection*)))
    (is (= notices (notifications)))))

(deftest new-host-operations-do-not-widen-a-signed-tool-ceiling
  (ops/register-op! ::alpha (spec))
  (let [principal {:principal/id "viewer" :principal/kind :human
                   :principal/scopes #{:toolset/host} :principal/tools #{(ops/op->name ::alpha)}}
        selection (surface/capped principal (surface/selection {:profile "admin"}))]
    (ops/register-op! ::beta (spec))
    (is (= [(ops/op->name ::alpha)] (mapv :name (filter #(surface/visible? selection %) (definitions)))))))
