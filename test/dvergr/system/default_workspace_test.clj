(ns dvergr.system.default-workspace-test
  (:require [clojure.test :refer [deftest is]]
            [dvergr.system.db :as db]
            [dvergr.system.rooms :as rooms]
            [dvergr.substrate.geschichte :as geschichte]
            [org.replikativ.spindel.engine.context :as context]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.yggdrasil :as ygg]
            [yggdrasil.protocols :as p])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- fixture [f]
  (let [ctx (context/create-execution-context)
        room (random-uuid) repository (random-uuid)
        registry {:system/id repository :system/type :repo :system/scope "/virtual/room-repo"}
        system {:id "room-repo-room-repo" :type :geschichte
                :conn (atom {:config {:store {:backend :file :id (random-uuid)
                                              :path "/virtual/room-repo/datahike"} :branch :db}})}]
    (try
      (with-redefs [db/get-conn (constantly (atom ::registry-snapshot))
                    db/default-repository (fn [snapshot id]
                                            (is (= ::registry-snapshot snapshot))
                                            (when (= room id) registry))
                    db/all-systems (fn [snapshot]
                                     (is (= ::registry-snapshot snapshot))
                                     [registry])
                    rooms/room-ctx-for (fn [id] (when (= room id) ctx))
                    ygg/registered-systems (fn []
                                             (is (identical? ctx ec/*execution-context*))
                                             {(:id system) system})
                    p/system-id :id p/system-type :type
                    geschichte/gy-connection :conn geschichte/gy-workspace-id (constantly :fixture)]
        (f {:ctx ctx :room room :repository repository :system system :registry registry}))
      (finally (context/close-context! ctx)))))

(deftest resolution-binds-the-explicit-repository-in-its-own-room-context
  (fixture
   (fn [{:keys [room repository system]}]
     (let [resolved (rooms/default-workspace room)]
       (is (= repository (:registry-system resolved)))
       (is (= (:id system) (:runtime-system resolved)))
       (is (identical? (:conn system) (:conn resolved)))
       (is (= :db (get-in resolved [:repository :workspace-branch])))))))

(deftest missing-marker-or-context-cannot-select-an-attached-workspace
  (fixture
   (fn [{:keys [room]}]
     (with-redefs [db/default-repository (constantly nil)]
       (is (thrown? clojure.lang.ExceptionInfo (rooms/default-workspace room))))
     (with-redefs [rooms/room-ctx-for (constantly nil)]
       (is (thrown? clojure.lang.ExceptionInfo (rooms/default-workspace room))))
     (with-redefs [db/default-repository (fn [& _] (throw (AssertionError. "Invalid id queried registry")))]
       (doseq [id [nil (str room) :any]]
         (is (thrown? clojure.lang.ExceptionInfo (rooms/default-workspace id))))))))

(deftest similar-name-wrong-type-duplicate-or-missing-runtime-never-falls-back
  (fixture
   (fn [{:keys [room system]}]
     (doseq [systems [{}
                      {"attached" (assoc system :id "room-repo-attached")}
                      {"one" system "two" system}
                      {"one" (assoc system :type :datahike)}]]
       (with-redefs [ygg/registered-systems (constantly systems)]
         (is (thrown? clojure.lang.ExceptionInfo (rooms/default-workspace room))))))))

(deftest runtime-name-alone-does-not-prove-the-physical-store-binding
  (fixture
   (fn [{:keys [room system]}]
     (doseq [conn [nil
                   (atom {:config {:store {:backend :file :id (random-uuid) :path "/other/datahike"} :branch :db}})
                   (atom {:config {:store {:backend :memory :id (random-uuid)} :branch :db}})
                   (atom {:config {:store {:backend :file :path "/virtual/room-repo/datahike"} :branch :db}})]]
       (with-redefs [ygg/registered-systems (constantly {"one" (assoc system :conn conn)})]
         (is (thrown? clojure.lang.ExceptionInfo (rooms/default-workspace room))))))))

(deftest canonical-registry-aliases-cannot-share-physical-ownership
  (fixture
   (fn [{:keys [room registry]}]
     (doseq [scope ["/virtual/else/../room-repo" "/virtual/./room-repo"]]
       (with-redefs [db/all-systems (constantly [registry (assoc registry :system/id (random-uuid)
                                                                 :system/scope scope)])]
         (is (thrown? clojure.lang.ExceptionInfo (rooms/default-workspace room))))))))

(deftest distinct-repository-roots-cannot-alias-the-same-physical-store
  (let [attrs (make-array FileAttribute 0)
        root (.toAbsolutePath (Files/createTempDirectory "dvergr-default-alias-" attrs))
        left (Files/createDirectory (.resolve root "left") attrs)
        right (Files/createDirectory (.resolve root "right") attrs)
        store (Files/createDirectory (.resolve left "datahike") attrs)
        alias (.resolve right "datahike")]
    (try
      (Files/createSymbolicLink alias store attrs)
      (is (Files/isSameFile alias store))
      (fixture
       (fn [{:keys [room registry system]}]
         (let [registered (assoc registry :system/scope (str left))
               runtime (assoc system :id "room-repo-left"
                              :conn (atom {:config {:store {:backend :file :id (random-uuid)
                                                            :path (str store)} :branch :db}}))]
           (with-redefs [db/default-repository (constantly registered)
                         db/all-systems (constantly [registered
                                                     (assoc registry :system/id (random-uuid)
                                                            :system/scope (str right))])
                         ygg/registered-systems (constantly {"one" runtime})]
             (is (thrown? clojure.lang.ExceptionInfo (rooms/default-workspace room)))))))
      (finally
        (Files/deleteIfExists alias)
        (Files/deleteIfExists store)
        (Files/deleteIfExists right)
        (Files/deleteIfExists left)
        (Files/deleteIfExists root)))))
