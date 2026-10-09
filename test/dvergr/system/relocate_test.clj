(ns dvergr.system.relocate-test
  "A home moved or copied to another directory opens its own stores: store ids
   are the ones the stores were created with, not hashes of their paths, and
   the registry's scopes are rehomed before any room store opens, so a copy
   never writes into the original."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as dh]
            [dvergr.ops :as ops]
            [dvergr.orchestration.daemon :as daemon]
            [dvergr.substrate.datahike :as sdh]
            [dvergr.substrate.paths :as paths]
            [dvergr.system.db :as sdb]
            [dvergr.system.rooms :as srooms]
            [org.replikativ.spindel.engine.core :as ec])
  (:import [java.nio.file Files LinkOption Path StandardCopyOption]
           [java.security MessageDigest]))

(defn- tmp [label]
  (.getAbsolutePath (io/file (System/getProperty "java.io.tmpdir")
                              (str "dvergr-relocate-" label "-" (random-uuid)))))

(defn- copy-tree! [^String from ^String to]
  (let [src (.toPath (io/file from)) dst (.toPath (io/file to))]
    (with-open [paths (Files/walk src (make-array java.nio.file.FileVisitOption 0))]
      (doseq [^Path p (iterator-seq (.iterator paths))
              :let [target (.resolve dst (.relativize src p))]]
        (if (Files/isDirectory p (make-array LinkOption 0))
          (Files/createDirectories target (make-array java.nio.file.attribute.FileAttribute 0))
          (Files/copy p target ^"[Ljava.nio.file.CopyOption;"
                      (into-array java.nio.file.CopyOption [StandardCopyOption/COPY_ATTRIBUTES])))))))

(defn- tree-digest
  "Every file under `dir` by relative path, with a SHA-256 of its bytes."
  [dir]
  (let [root (io/file dir)]
    (into (sorted-map)
          (for [^java.io.File f (file-seq root) :when (.isFile f)]
            [(str (.relativize (.toPath root) (.toPath f)))
             (let [md (MessageDigest/getInstance "SHA-256")]
               (apply str (map #(format "%02x" %) (.digest md (Files/readAllBytes (.toPath f))))))]))))

(defn- note! [room text]
  (binding [ec/*execution-context* (:ctx room)]
    (dh/transact (srooms/msgs-conn-for-slug "moved")
                 [{:tool-call/id (random-uuid) :tool-call/name text
                   :tool-call/status :completed :tool-call/started-at (java.util.Date.)}])))

(defn- notes [room]
  (binding [ec/*execution-context* (:ctx room)]
    (set (dh/q '[:find [?n ...] :where [?e :tool-call/name ?n]]
               @(srooms/msgs-conn-for-slug "moved")))))

(deftest a-copied-home-opens-its-own-stores-and-leaves-the-original-alone
  (let [prev-home (paths/home)
        a (tmp "a")
        b (tmp "b")]
    (try
      (paths/set-home! a)
      (sdb/reset-conn!)
      (let [d (daemon/start! {:agents {}})]
        (try
          (ops/invoke d :room/create {:title "moved" :slug "moved"})
          (note! (ops/resolve-room d "moved") "written-in-a")
          (finally (daemon/stop! d))))
      (let [room-id (:room/id (sdb/room-by-slug "moved"))
            sync-scope (srooms/room-msgs-store-id room-id)
            kinds (into {} (map (fn [{:system/keys [scope type name]}]
                                  [(.getName (io/file (str scope))) [type name]]))
                        (sdb/all-systems))]
        (sdb/reset-conn!)
        (copy-tree! a b)
        (let [original (tree-digest a)]
          (paths/set-home! b)
          (sdb/reset-conn!)
          (let [d (daemon/start! {:agents {}})]
            (try
              (let [room (ops/resolve-room d "moved")]
              (testing "the registry names this home's stores"
                (is (every? #(.startsWith (str (:system/scope %)) b)
                            (filter #(#{:msgs :kb :repo} (:system/type %)) (sdb/all-systems)))))
              (testing "the copy reads what was written before and writes to itself"
                (is (contains? (notes room) "written-in-a"))
                (note! room "written-in-b")
                (is (contains? (notes room) "written-in-b")))
              (testing "the sync scope a consumer bound to is unchanged"
                (is (= sync-scope (srooms/room-msgs-store-id room-id)))))
              (finally (daemon/stop! d))))
          (testing "the original home was not written"
            (let [after (tree-digest a)]
              (is (= #{} (set (for [f (distinct (concat (keys original) (keys after)))
                                    :when (not= (original f) (after f))
                                    :let [[top sys] (str/split f #"/")]]
                                (if (= "systems" top) (kinds sys [:unknown sys]) top)))))))))
      (finally
        (sdb/reset-conn!)
        (paths/set-home! prev-home)))))

(deftest store-ids-are-created-not-derived-from-paths
  (let [path (str (tmp "store") "/db")
        cfg {:store {:backend :file :path path :id (sdh/file-store-id path)}}]
    (try
      (dh/create-database cfg)
      (testing "a new store's id is not its path's hash"
        (is (not= (java.util.UUID/nameUUIDFromBytes (.getBytes ^String path))
                  (sdh/stored-store-id {:backend :file :path path}))))
      (testing "the id is the stored one, also after the cache is dropped"
        (let [id (get-in cfg [:store :id])]
          (sdh/forget-file-store-id! path)
          (is (= id (sdh/file-store-id path)))))
      (finally (sdh/delete-database! cfg)))))

(deftest a-store-created-with-a-path-derived-id-keeps-it
  (let [path (str (tmp "legacy") "/db")
        legacy (java.util.UUID/nameUUIDFromBytes (.getBytes ^String path))
        cfg {:store {:backend :file :path path :id legacy}}]
    (try
      (dh/create-database cfg)
      (sdh/forget-file-store-id! path)
      (is (= legacy (sdh/file-store-id path)))
      (dh/release (dh/connect (assoc-in cfg [:store :id] (sdh/file-store-id path))))
      (finally (sdh/delete-database! cfg)))))

(deftest scopes-outside-a-systems-directory-are-not-rehomed
  (let [rehomed-scope @#'sdb/rehomed-scope
        here (tmp "here")
        sys (str here "/systems")]
    (.mkdirs (io/file sys "abc"))
    (is (= (str (io/file sys "abc")) (rehomed-scope "/elsewhere/old-home/systems/abc" sys)))
    (is (nil? (rehomed-scope "/elsewhere/old-home/systems/missing" sys)) "not in this home")
    (is (nil? (rehomed-scope "/home/someone/drive/abc" sys)) "a drive path")
    (is (nil? (rehomed-scope (str (io/file sys "abc")) sys)) "already here")
    (is (nil? (rehomed-scope "abc" sys)) "not a path")))
