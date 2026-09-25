(ns dvergr.test-tmp
  "The test run's temporary directory. Tests make homes, stores and checkouts
   under `java.io.tmpdir` and do not remove them; on a machine whose /tmp is a
   RAM-backed tmpfs a few days of runs filled it, and then every datahike write
   failed. The :test alias points `java.io.tmpdir` at target/test-tmp (the JDK
   reads it once, at startup, so it has to be a JVM option), and these kaocha
   hooks create that directory before the tests load and empty it after the run.
   Two concurrent runs in one checkout share it; the first to finish empties it."
  (:require [clojure.java.io :as io]))

(defn- tmp-dir [] (io/file (System/getProperty "java.io.tmpdir")))

(defn- delete-tree! [^java.io.File f]
  (when (.isDirectory f)
    (doseq [c (.listFiles f)] (delete-tree! c)))
  (.delete f))

(defn pre-load
  "Kaocha hook: make sure the run's temporary directory exists."
  [config]
  (.mkdirs (tmp-dir))
  config)

(defn post-run
  "Kaocha hook: remove everything the run left in its temporary directory.
   Only when that directory is the run's own (under target/), never /tmp."
  [result]
  (let [dir (tmp-dir)]
    (when (re-find #"target[/\\]test-tmp$" (.getPath dir))
      (doseq [c (.listFiles dir)] (delete-tree! c))))
  result)
