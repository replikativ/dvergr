(ns dvergr.system.relocate-test
  "A home moved to another directory opens its stores: each store's id is kept
   beside it, not derived from its path, and the registry's scopes are rehomed
   before any room store opens. A copy of a home whose original still exists is
   refused: it is the same stores under the same ids, not a home of its own."
  (:require [clojure.edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as dh]
            [dvergr.ops :as ops]
            [dvergr.orchestration.daemon :as daemon]
            [dvergr.substrate.datahike :as sdh]
            [dvergr.substrate.store-ids :as store-ids]
            [dvergr.system.home :as home]
            [dvergr.substrate.paths :as paths]
            [dvergr.system.db :as sdb]
            [dvergr.system.rooms :as srooms]
            [org.replikativ.spindel.engine.core :as ec])
  (:import [java.nio.file Files LinkOption Path StandardCopyOption]
           [java.io File]
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

(defn- start-room-home!
  "Create home `dir` with room \"moved\" holding one note; stopped afterwards."
  [dir]
  (paths/set-home! dir)
  (sdb/reset-conn!)
  (let [d (daemon/start! {:agents {}})]
    (try
      (ops/invoke d :room/create {:title "moved" :slug "moved"})
      (note! (ops/resolve-room d "moved") "written-in-a")
      (finally (daemon/stop! d))))
  (let [room-id (:room/id (sdb/room-by-slug "moved"))]
    (sdb/reset-conn!)
    {:room-id room-id}))

(defn- move! [^String from ^String to]
  (Files/move (.toPath (io/file from)) (.toPath (io/file to))
              (into-array java.nio.file.CopyOption [StandardCopyOption/ATOMIC_MOVE])))

(defn- open-moved-room
  "Start the daemon at `dir` and check the moved room; returns its sync scope."
  [dir room-id]
  (paths/set-home! dir)
  (sdb/reset-conn!)
  (let [d (daemon/start! {:agents {}})]
    (try
      (let [room (ops/resolve-room d "moved")]
        (testing "the registry names this home's stores"
          (is (every? #(.startsWith (str (:system/scope %)) dir)
                      (filter #(#{:msgs :kb :repo} (:system/type %)) (sdb/all-systems)))))
        (testing "the moved home reads what was written before and writes on"
          (is (contains? (notes room) "written-in-a"))
          (note! room "written-in-b")
          (is (contains? (notes room) "written-in-b")))
        (srooms/room-msgs-store-id room-id))
      (finally (daemon/stop! d) (sdb/reset-conn!)))))

(deftest a-moved-home-opens-its-stores
  (let [prev-home (paths/home)
        a (tmp "a")
        b (tmp "b")]
    (try
      (let [{:keys [room-id]} (start-room-home! a)
            sync-scope (do (paths/set-home! a) (srooms/room-msgs-store-id room-id))]
        (sdb/reset-conn!)
        (move! a b)
        (testing "the sync scope a consumer bound to is unchanged"
          (is (= sync-scope (open-moved-room b room-id))))
        (testing "the home records where it lives now"
          (is (= (.getCanonicalPath (io/file b))
                 (:home/path (clojure.edn/read-string (slurp (io/file b "home.edn")))))))
        (is (= :same (home/claim!)) "and opens as itself afterwards"))
      (finally
        (sdb/reset-conn!)
        (paths/set-home! prev-home)))))

(deftest a-copied-home-is-refused-while-its-original-exists
  (let [prev-home (paths/home)
        a (tmp "a")
        b (tmp "b")]
    (try
      (start-room-home! a)
      (copy-tree! a b)
      (let [original (tree-digest a)]
        (paths/set-home! b)
        (sdb/reset-conn!)
        (is (= ::home/copied-home
               (try (daemon/start! {:agents {}}) nil
                    (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))
        (is (= original (tree-digest a)) "the original was not written"))
      (finally
        (sdb/reset-conn!)
        (paths/set-home! prev-home)))))

(deftest a-home-moved-before-its-stores-had-id-files-is-rehomed
  ;; An older version kept no id files for room stores; the registry's
  ;; original scopes still give the ids they were derived with.
  (let [prev-home (paths/home)
        a (tmp "a")
        b (tmp "b")]
    (try
      (let [store-id store-ids/store-id
            {:keys [room-id]} (with-redefs [store-ids/store-id
                                            ;; the older version: room stores'
                                            ;; ids derived from their paths, kept nowhere
                                            (fn [path legacy]
                                              (if (str/includes? (str path) "/systems/")
                                                (legacy path)
                                                (store-id path legacy)))]
                                (start-room-home! a))]
        (is (empty? (filter #(.endsWith (.getName ^File %) ".id") (file-seq (io/file a "systems")))))
        (move! a b)
        (open-moved-room b room-id)
        (testing "the ids derived from the original paths are recorded beside the stores"
          (is (seq (filter #(.endsWith (.getName ^File %) ".id") (file-seq (io/file b "systems")))))))
      (finally
        (sdb/reset-conn!)
        (paths/set-home! prev-home)))))

(deftest a-new-store-gets-a-fresh-id-kept-beside-it
  (let [path (str (tmp "store") "/db")
        id (store-ids/store-id path (fn [_] (throw (ex-info "not a legacy store" {}))))
        cfg {:store {:backend :file :path path :id id}}]
    (try
      (dh/create-database cfg)
      (is (not= (store-ids/path-derived "" path) id))
      (is (.exists (store-ids/id-file path)))
      (is (= id (store-ids/store-id path (constantly nil))) "read back after the cache is dropped")
      (finally (sdh/delete-database! cfg)))
    (is (not (.exists (store-ids/id-file path))) "deleting the store deletes its id")))

(deftest a-store-deleted-with-its-directory-gets-a-new-id
  (let [dir (tmp "repo")
        path (str dir "/datahike")
        first-id (store-ids/store-id path (constantly nil))]
    (.mkdirs (io/file path))
    (spit (io/file path "x") "store")
    (doseq [^File f (reverse (file-seq (io/file dir)))] (.delete f))
    (is (not= first-id (store-ids/store-id path (constantly :legacy))))
    (is (.exists (store-ids/id-file path)))))

(deftest a-store-created-with-a-path-derived-id-keeps-it
  (let [path (str (tmp "legacy") "/db")
        legacy (store-ids/path-derived "" path)
        cfg {:store {:backend :file :path path :id legacy}}]
    (try
      (dh/create-database cfg)
      (is (= legacy (store-ids/store-id path (partial store-ids/path-derived ""))))
      (is (str/includes? (slurp (store-ids/id-file path)) "derived")
          "marked as derived: the store has not confirmed it")
      (dh/release (dh/connect (assoc-in cfg [:store :id] (store-ids/store-id path (constantly nil)))))
      (finally (sdh/delete-database! cfg)))))

(deftest only-another-homes-systems-stores-are-foreign
  (let [foreign-scope @#'sdb/foreign-scope
        here (tmp "here")
        sys (str here "/systems")]
    (.mkdirs (io/file sys "abc"))
    (is (= (io/file sys "abc") (foreign-scope "/elsewhere/old-home/systems/abc" sys)))
    (is (nil? (foreign-scope "/home/someone/drive/abc" sys)) "a drive path")
    (is (nil? (foreign-scope (str (io/file sys "abc")) sys)) "already here")
    (is (nil? (foreign-scope "abc" sys)) "not a path")))

(deftest an-id-once-published-is-not-replaced
  (let [path (str (tmp "race") "/db")
        f (store-ids/id-file path)
        first-id (random-uuid)]
    (is (= first-id (#'store-ids/write-new! f first-id)))
    (is (= first-id (#'store-ids/write-new! f (random-uuid))) "a second writer gets the published id")
    (is (= first-id (store-ids/record! path (random-uuid))))
    (is (= first-id (store-ids/store-id path (constantly nil))))))

(deftest a-replaced-id-file-is-read-again
  (let [path (str (tmp "replaced") "/db")
        a (store-ids/store-id path (constantly nil))
        b (random-uuid)]
    (spit (store-ids/id-file path) (str b "\n"))
    (is (not= a b))
    (is (= b (store-ids/store-id path (constantly nil))) "another process replaced the store and its id")))

(deftest a-failed-delete-keeps-the-id-of-the-store-that-is-left
  (let [path (str (tmp "undeleted") "/db")
        id (store-ids/store-id path (constantly nil))
        cfg {:store {:backend :file :path path :id id}}]
    (try
      (dh/create-database cfg)
      (with-redefs [dh/delete-database (fn [_] (throw (ex-info "disk busy" {})))]
        (is (thrown? clojure.lang.ExceptionInfo (sdh/delete-database! cfg))))
      (is (= id (store-ids/store-id path (constantly nil))))
      (testing "also when the backend fails to delete without saying so"
        (with-redefs [dh/delete-database (constantly nil)]
          (sdh/delete-database! cfg))
        (is (= id (store-ids/store-id path (constantly nil)))))
      (finally (sdh/delete-database! cfg)))))

(deftest a-home-moved-before-it-kept-store-ids-says-what-to-do
  ;; Every store as an older version made it: path-derived ids, no id files.
  (let [prev-home (paths/home)
        a (tmp "a")
        b (tmp "b")]
    (try
      (with-redefs [store-ids/store-id (fn [path legacy] (legacy (str path)))]
        (start-room-home! a))
      (.delete (io/file a "home.edn"))
      (is (empty? (filter #(.endsWith (.getName ^File %) ".id") (file-seq (io/file a)))))
      (move! a b)
      (paths/set-home! b)
      (sdb/reset-conn!)
      (is (= ::store-ids/identity-mismatch
             (try (daemon/start! {:agents {}}) nil
                  (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))
      (testing "moved back, it opens, as the error says"
        (sdb/reset-conn!)
        (move! b a)
        (paths/set-home! a)
        (let [d (daemon/start! {:agents {}})]
          (try (is (contains? (notes (ops/resolve-room d "moved")) "written-in-a"))
               (finally (daemon/stop! d)))))
      (finally
        (sdb/reset-conn!)
        (paths/set-home! prev-home)))))
