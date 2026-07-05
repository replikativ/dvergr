(ns dvergr.drive.drive-test
  "Contract tests for the room drive: the CAS blob store, the fs-node tree CRUD,
   and the muschel FS adapter. Uses an in-memory datahike tree conn to exercise
   the tree/FS without the room-ctx machinery (that seam is create-room-db!,
   tested via system.rooms)."
  (:require [clojure.test :refer [deftest testing is]]
            [dvergr.drive.blobs :as blobs]
            [dvergr.drive.core :as drive]
            [dvergr.drive.fs :as dfs]
            [muschel.fs :as mfs]
            [datahike.api :as d]))

(defn- mem-drive-conn []
  (let [cfg {:store {:backend :memory :id (random-uuid)}
             :schema-flexibility :write :keep-history? false}]
    (d/create-database cfg)
    (let [conn (d/connect cfg)]
      (d/transact conn drive/fs-node-schema)
      conn)))

(deftest blob-store-roundtrip
  (testing "content-addressed store! + get-bytes"
    (let [bs (.getBytes "hello blob" "UTF-8")
          {:blob/keys [id size]} (blobs/store! bs "text/plain")]
      (is (= 64 (count id)) "sha-256 hex")
      (is (= (count bs) size))
      (is (= "hello blob" (String. (blobs/get-bytes id) "UTF-8")))
      (testing "idempotent — same bytes → same id"
        (is (= id (:blob/id (blobs/store! bs "text/plain")))))
      (is (nil? (blobs/get-bytes "deadbeef")) "missing → nil"))))

(deftest tree-crud
  (let [conn (mem-drive-conn)]
    (testing "mkdir / ensure-path / put-file / resolve / read"
      (let [dir  (drive/ensure-path! conn ["telegram" "docs"])
            node (drive/put-file! conn dir "report.txt"
                                  (.getBytes "line one" "UTF-8")
                                  :mime "text/plain" :source :telegram)]
        (is (= :file (:fs.node/kind node)))
        (is (= "line one" (String. (drive/read-file conn (:fs.node/id node)) "UTF-8")))
        (is (= (:fs.node/id node)
               (:fs.node/id (drive/resolve-path conn "telegram/docs/report.txt"))))))
    (testing "same name ⇒ new version (node points at new content)"
      (let [dir (drive/ensure-path! conn ["telegram" "docs"])]
        (drive/put-file! conn dir "report.txt" (.getBytes "line two" "UTF-8") :mime "text/plain")
        (is (= "line two"
               (String. (drive/read-file conn (:fs.node/id (drive/resolve-path conn "telegram/docs/report.txt")))
                        "UTF-8")))))
    (testing "ls sorts dirs first"
      (let [root (drive/ls conn)]
        (is (= ["telegram"] (mapv :fs.node/name root)))))
    (testing "rm on a non-empty dir throws; empty succeeds"
      (let [f (drive/resolve-path conn "telegram/docs/report.txt")]
        (is (thrown? Exception (drive/rm! conn (:fs.node/id (drive/resolve-path conn "telegram")))))
        (is (= :removed (drive/rm! conn (:fs.node/id f))))
        (is (nil? (drive/resolve-path conn "telegram/docs/report.txt")))))))

(deftest drive-fs-adapter
  (let [conn (mem-drive-conn)
        dir  (drive/ensure-path! conn ["inbox"])
        _    (drive/put-file! conn dir "note.md" (.getBytes "# hi" "UTF-8") :mime "text/markdown")
        fs   (dfs/make conn)]
    (testing "muschel FS reads the tree through ls/read"
      (is (= ["note.md"] (mapv :name (mfs/-list-dir fs "/inbox"))))
      (is (= "# hi" (mfs/-read-file fs "/inbox/note.md")))
      (is (some? (mfs/-stat fs "/inbox")))
      (is (nil? (mfs/-read-bytes fs "/inbox/absent"))))
    (testing "write through the FS commits to the tree + CAS"
      (let [sink (mfs/-open-sink fs "/inbox/written.txt" false)]
        (reset! (:acc sink) "from the shell")
        (is (= "from the shell" (mfs/-read-file fs "/inbox/written.txt")))))))
