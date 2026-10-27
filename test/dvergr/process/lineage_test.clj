(ns dvergr.process.lineage-test
  "Two sequential JVM phases: prepare via -main, then reopen in the :process suite."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [dvergr.room :as room]
            [dvergr.store :as store]
            [dvergr.test-support :as t]
            [dvergr.world :as world]))

(defn -main
  "Persist phase A evidence and exit before the phase B test JVM starts."
  [directory]
  (t/persist-process-evidence! directory)
  (shutdown-agents))

(deftest w6-process-reopen-lineage-and-room
  "W6: phase B opens only the file control store and reconstructs exact phase A lineage and room evidence."
  (let [external (System/getenv "DVERGR_PROCESS_EVIDENCE")
        directory (or external (str (java.nio.file.Files/createTempDirectory
                                     "dvergr-process-" (make-array java.nio.file.attribute.FileAttribute 0))))
        evidence (if external
                   (edn/read-string (slurp (io/file directory "evidence.edn")))
                   (t/persist-process-evidence! directory))
        {:keys [config records room writer-process]} evidence
        control (store/open! config)]
    (try
      (when external
        (is (vector? writer-process))
        (is (not= writer-process (t/process-identity))))
      (let [by-id (into {} (map (juxt :world/id identity)) records)]
        (doseq [record records]
          (is (= record (store/world-record control (:world/id record))))
          (let [expected (loop [r record result ()]
                           (if r (recur (get by-id (:world/source r)) (conj result r)) (vec result)))]
            (is (= expected (store/lineage control (:world/id record)))))
          (when (:world/source record)
            (is (= {:kind :uncoordinated-frozen :id nil :p1? false} (:assembly/cut record)))
            (is (some? (get-in record [:assembly/components :work :basis])))))
        (is (= room (room/lookup control (:room/id room))))
        (is (empty? @(:live (world/installation control)))))
      (finally (store/close! control)))))
