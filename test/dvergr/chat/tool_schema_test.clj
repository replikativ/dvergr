(ns dvergr.chat.tool-schema-test
  "Tests for critical tool schema generation features."
  (:require [clojure.test :refer [deftest is testing]]
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
