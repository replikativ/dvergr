(ns dvergr.substrate.geschichte-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [dvergr.substrate.geschichte :as g]
            [geschichte.repo :as repo]
            [geschichte.yggdrasil :as gy]
            [muschel.fs :as fs]))

(deftest persistent-system-exposes-a-virtual-workspace
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory
                      "dvergr-geschichte-"
                      (make-array java.nio.file.attribute.FileAttribute 0)))
        scope (.getPath (io/file dir "store"))
        system (g/create-system :scope scope :system-name "room-repo-test"
                                :source (.getPath (io/file "../dvergr-sandbox")))
        conn (gy/connection system)
        filesystem (g/filesystem {:system system :conn conn
                                  :id (gy/workspace-id system)
                                  :repository {:conn conn
                                               :config (:config @conn)}})]
    (try
      (is (seq (repo/files conn)))
      (is (nil? (fs/physical-path filesystem "/")))
      (is (some? (get (repo/configuration conn) "remote.upstream.url")))
      (finally
        (d/release conn)
        (g/delete-repository! scope)))))
