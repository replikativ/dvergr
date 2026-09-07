(ns dvergr.artifact-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.core.async :as async]
            [datahike.api :as d]
            [datahike.gc :as gc]
            [datahike.gc-guard :as guard]
            [datahike.versioning :as versioning]
            [datahike.store :as ds]
            [dvergr.artifact :as artifact]
            [dvergr.drive.blobs :as blobs]))

(defn- config [history?]
  {:store {:backend :file :id (random-uuid)
           :path (str (java.nio.file.Files/createTempDirectory
                       "artifact-store-ref-" (make-array java.nio.file.attribute.FileAttribute 0)) "/db")}
   :schema-flexibility :write :keep-history? history? :commit-graph? false
   :writer {:backend :self :writer-ownership :exclusive}})

(def schema [{:db/ident :test/id :db/valueType :db.type/keyword
              :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
             {:db/ident :test/payload :db/valueType :db.type/store-ref
              :db/cardinality :db.cardinality/one}])

(deftest publication-guards-bytes-through-commit-and-releases-on-failure
  (let [cfg (config false)
        _ (d/create-database cfg)
        conn (d/connect cfg)
        st (artifact/datahike-store conn)
        sid (ds/canonical-store-id (:store @conn) (get-in @conn [:config :store]))]
    (try
      (d/transact conn schema)
      (is (thrown? Exception (artifact/put-value! st {:body "unsafe"})))
      (let [ref (artifact/publish-value! st {:body "source"}
                                         (fn [ref]
                    (is (guard/in-flight? sid))
                    (is (not (contains? (set (async/<!! (gc/gc-storage! @conn))) ref))
                        "collection during the unpublished write window must spare the bytes")
                                           (is (= {:body "source"} (artifact/get-value st ref)))
                                           (d/transact conn [{:test/id :one :test/payload ref}])
                                           ref))]
        (is (uuid? ref))
        (is (not (guard/in-flight? sid)))
        (is (contains? (async/<!! (gc/reachable-store-refs @conn)) ref))
        (is (not (contains? (set (async/<!! (gc/gc-storage! @conn))) ref)))
        (is (thrown-with-msg? Exception #"fixture failure"
                              (artifact/publish-value! st {:body "orphan"}
                                                       (fn [_] (is (guard/in-flight? sid))
                                                         (throw (ex-info "fixture failure" {}))))))
        (is (not (guard/in-flight? sid)))
        (d/transact conn [[:db/retractEntity [:test/id :one]]])
        (is (contains? (set (async/<!! (gc/gc-storage! @conn))) ref)))
      (finally (d/release conn) (d/delete-database cfg)))))

(deftest retained-history-and-cold-reopen-keep-captured-content
  (let [cfg (config true)
        _ (d/create-database cfg)
        conn (d/connect cfg)
        st (artifact/datahike-store conn)]
    (try
      (d/transact conn schema)
      (let [ref (artifact/publish-value! st {:body "historical source"}
                                         (fn [ref] (d/transact conn [{:test/id :one :test/payload ref}]) ref))]
        (d/transact conn [[:db/retractEntity [:test/id :one]]])
        (is (contains? (async/<!! (gc/reachable-store-refs @conn)) ref))
        (is (not (contains? (set (async/<!! (gc/gc-storage! @conn))) ref)))
        (d/release conn)
        (let [reopened (d/connect cfg)]
          (try
            (is (= {:body "historical source"}
                   (artifact/get-value (artifact/datahike-store reopened) ref)))
            (finally (d/release reopened)))))
      (finally (d/release conn) (d/delete-database cfg)))))

(deftest legacy-string-reference-reads-remain-available
  (with-redefs [blobs/get-bytes (fn [ref]
                                  (is (= "legacy-sha" ref))
                                  (.getBytes "{:body \"old\"}" "UTF-8"))]
    (is (= {:body "old"}
           (artifact/get-value (artifact/datahike-store nil) "legacy-sha")))))

(deftest another-branch-keeps-a-payload-live
  (let [cfg (config false)
        _ (d/create-database cfg)
        conn (d/connect cfg)
        st (artifact/datahike-store conn)]
    (try
      (d/transact conn schema)
      (let [ref (artifact/publish-value! st {:body "branch-only source"}
                                         (fn [ref] (d/transact conn [{:test/id :one :test/payload ref}]) ref))]
        (versioning/branch! conn :db :retained)
        (d/transact conn [[:db/retractEntity [:test/id :one]]])
        (is (contains? (async/<!! (gc/reachable-store-refs @conn)) ref))
        (is (not (contains? (set (async/<!! (gc/gc-storage! @conn))) ref)))
        (is (= {:body "branch-only source"} (artifact/get-value st ref)))
        (versioning/delete-branch! conn :retained)
        (is (contains? (set (async/<!! (gc/gc-storage! @conn))) ref)))
      (finally (d/release conn) (d/delete-database cfg)))))
