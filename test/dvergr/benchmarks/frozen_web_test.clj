(ns dvergr.benchmarks.frozen-web-test
  (:require [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [dvergr.artifact :as artifact]
            [dvergr.benchmarks.frozen-web :as web]
            [dvergr.chat.schema :as schema]
            [dvergr.io.acquisition :as acquisition]
            [dvergr.sandbox.ns.io :as io]
            [jsonista.core :as j]
            [sci.core :as sci]))

(def pages
  {"https://example.org/aster" {:title "Aster" :body "Persistent agent teams share organizational memory."}
   "https://example.org/beryl" {:title "Beryl" :body "Agent teams request human approval."}
   "https://example.org/cinder" {:title "Cinder" :body "Personal autocomplete editor."}})

(deftest frozen-search-is-query-dependent-and-order-independent
  (let [request {:url web/search-url :query-params {:q "agent teams"}}
        transport (web/transport pages)
        response (transport request)
        hits #(get-in (j/read-value (:body %) j/keyword-keys-object-mapper) [:web :results])]
    (is (= response ((web/transport (into (sorted-map) pages)) request)))
    (is (= ["Aster" "Beryl"] (mapv :title (hits response))))
    (is (= ["Beryl"] (mapv :title (hits (transport (assoc-in request [:query-params :q] "approval"))))))
    (is (empty? (hits (transport (assoc-in request [:query-params :q] "astronomy")))))
    (doseq [bad [(assoc request :method :post)
                 (assoc-in request [:query-params :freshness] "pd")
                 (assoc-in request [:query-params :count] 0)
                 {:url "https://unknown.invalid"}]]
      (is (<= 400 (:status (transport bad)))))
    (is (= "Persistent agent teams share organizational memory."
           (:body (transport {:url "https://example.org/aster"}))))))

(deftest sci-fixtures-are-local-and-produce-fresh-durable-receipts
  (let [cfg {:store {:backend :memory :id (random-uuid)} :schema-flexibility :write}
        _ (d/create-database cfg)
        conn (d/connect cfg)
        ctx (sci/init {})
        other (sci/init {})
        run-id (random-uuid)
        artifacts (artifact/datahike-store conn)]
    (try
      (schema/ensure-full-schema! conn)
      (io/add-http-ns! ctx :fixture-transport (web/transport pages))
      (io/add-http-ns! other :allowed-domains #{"https://example.org"} :fixture-transport
                       (web/transport {"https://example.org/aster" {:title "Other" :body "Other world"}}))
      ;; Resolving .invalid would fail if a fixture accidentally used real DNS.
      (is (= 404 (sci/eval-string* ctx "(:status (babashka.http-client/get \"https://unknown.invalid\"))")))
      (is (= "Other world" (sci/eval-string* other "(:body (babashka.http-client/get \"https://example.org/aster\"))")))
      (is (thrown? Exception (sci/eval-string* other "(babashka.http-client/get \"https://unknown.invalid\")")))
      (binding [acquisition/*scope* {:conn conn :room-id :fixture :run-id run-id :artifacts artifacts
                                     :capture-policy {:allowed-origins #{"https://example.org"} :max-bytes 1024}}]
        (let [code "(babashka.http-client/get \"https://example.org/aster\")"
              a (sci/eval-string* ctx code)
              b (sci/eval-string* ctx code)
              rows (acquisition/list-for-run conn :fixture run-id 10)]
          (is (= (:body a) (:body b)))
          (is (not= (get-in a [:dvergr/acquisition :id]) (get-in b [:dvergr/acquisition :id])))
          (is (= 2 (count rows)))
          (is (every? #(= (:dvergr/fixture-id a) (:acquisition/fixture-id %)) rows))
          (is (= {:body (:body a)} (artifact/get-value artifacts (:acquisition/body-store-ref (first rows)))))))
      (finally (d/release conn) (d/delete-database cfg)))))
