(ns dvergr.chat.tool-schema-test
  "Tests for critical tool schema generation features."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [dvergr.chat.tool-schema :as ts]))

;; NOTE: Removed reset-installed-schemas! fixture - no longer needed
;; since schema installation is now per-database, not globally tracked.

(def simple-tool
  {:name "greet"
   :parameters {:type "object"
                :properties {:name {:type "string"}
                             :age {:type "integer"}}
                :required ["name"]}})

(def nested-tool
  {:name "config"
   :parameters {:type "object"
                :properties {:server {:type "object"
                                      :properties {:host {:type "string"}
                                                   :port {:type "integer"}}}
                             :enabled {:type "boolean"}}
                :required ["server"]}})

(defn- test-conn []
  (let [cfg {:store {:backend :memory :id (random-uuid)}
             :schema-flexibility :write}]
    (d/create-database cfg)
    {:cfg cfg :conn (d/connect cfg)}))

(defn- error-type [error]
  (loop [error error]
    (when error
      (or (:type (ex-data error))
          (recur (ex-cause error))))))

(deftest generate-tool-schema-test
  (testing "Simple tool schema generation"
    (let [schema (ts/generate-tool-schema simple-tool)]
      (is (vector? schema))
      (is (= 2 (count schema)))
      (is (some #(= :tool-input.greet/name (:db/ident %)) schema))
      (is (some #(= :tool-input.greet/age (:db/ident %)) schema))))

  (testing "Nested object becomes ref with component"
    (let [schema (ts/generate-tool-schema nested-tool)
          server-attr (first (filter #(= :tool-input.config/server (:db/ident %)) schema))]
      (is (= :db.type/ref (:db/valueType server-attr)))
      (is (:db/isComponent server-attr))
      ;; Nested properties exist
      (is (some #(= :tool-input.config.server/host (:db/ident %)) schema))
      (is (some #(= :tool-input.config.server/port (:db/ident %)) schema)))))

(deftest tool-input->entity-test
  (testing "Simple input conversion"
    (let [entity (ts/tool-input->entity simple-tool {:name "Alice" :age 30})]
      (is (= "Alice" (:tool-input.greet/name entity)))
      (is (= 30 (:tool-input.greet/age entity)))))

  (testing "Nested input becomes nested entity map"
    (let [entity (ts/tool-input->entity nested-tool
                                        {:server {:host "localhost" :port 8080}
                                         :enabled true})]
      (is (map? (:tool-input.config/server entity)))
      (let [server (:tool-input.config/server entity)]
        (is (= "localhost" (:tool-input.config.server/host server)))
        (is (= 8080 (:tool-input.config.server/port server))))
      (is (= true (:tool-input.config/enabled entity)))))

  (testing "Arrays become vectors"
    (let [tool {:name "batch"
                :parameters {:type "object"
                             :properties {:items {:type "array"
                                                  :items {:type "string"}}}}}
          entity (ts/tool-input->entity tool {:items ["a" "b" "c"]})]
      (is (= ["a" "b" "c"] (:tool-input.batch/items entity))))))

(deftest incompatible-concurrent-first-installs-have-one-durable-winner
  (let [{:keys [cfg conn]} (test-conn)
        ready (java.util.concurrent.CountDownLatch. 2)
        start (java.util.concurrent.CountDownLatch. 1)
        tool (fn [type]
               {:name "schema_race"
                :parameters {:type "object"
                             :properties {:value {:type type}}}})
        attempt
        (fn [type]
          (future
            (.countDown ready)
            (.await start)
            (try
              (ts/install-tool-schema! conn (tool type))
              {:status :installed :type type}
              (catch Throwable error
                {:status :rejected :type type :error error}))))
        string-attempt (attempt "string")
        integer-attempt (attempt "integer")]
    (try
      (is (.await ready 5 java.util.concurrent.TimeUnit/SECONDS))
      (.countDown start)
      (let [string-outcome (deref string-attempt 5000 ::timeout)
            integer-outcome (deref integer-attempt 5000 ::timeout)
            outcomes [string-outcome integer-outcome]
            installed (filter #(= :installed (:status %)) outcomes)
            rejected (filter #(= :rejected (:status %)) outcomes)
            installed-value-type
            (get {"string" :db.type/string "integer" :db.type/long}
                 (:type (first installed)))]
        (is (not= ::timeout string-outcome))
        (is (not= ::timeout integer-outcome))
        (is (= 1 (count installed)) outcomes)
        (is (= 1 (count rejected)) outcomes)
        (is (= ::ts/incompatible-installed-tool-schema
               (error-type (:error (first rejected)))))
        (is (= installed-value-type
               (:db/valueType (d/entity @conn :tool-input.schema-race/value)))
            "the losing first use cannot rewrite an unused durable attribute"))
      (finally
        (.countDown start)
        (future-cancel string-attempt)
        (future-cancel integer-attempt)
        (d/release conn)
        (d/delete-database cfg)))))

(deftest compatible-schema-extension-installs-only-new-attributes
  (let [{:keys [cfg conn]} (test-conn)
        base {:name "schema_extension"
              :parameters {:type "object"
                           :properties {:value {:type "string"}}}}
        extended (assoc-in base [:parameters :properties :revision]
                           {:type "integer"})]
    (try
      (is (= 1 (ts/install-tool-schema! conn base)))
      (is (= 2 (ts/install-tool-schema! conn extended)))
      ;; An older compatible process may race after the extension. Its subset
      ;; must neither fail nor retract the already-installed field.
      (is (= 1 (ts/install-tool-schema! conn base)))
      (is (= :db.type/string
             (:db/valueType
              (d/entity @conn :tool-input.schema-extension/value))))
      (is (= :db.type/long
             (:db/valueType
              (d/entity @conn :tool-input.schema-extension/revision))))
      (finally
        (d/release conn)
        (d/delete-database cfg)))))
