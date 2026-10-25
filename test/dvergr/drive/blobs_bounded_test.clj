(ns dvergr.drive.blobs-bounded-test
  (:require [clojure.test :refer [deftest is]]
            [dvergr.drive.blobs :as blobs]
            [konserve.core :as k]
            [konserve.store :as stores])
  (:import [java.io ByteArrayInputStream IOException]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def sample (.getBytes "exact bytes" "UTF-8"))
(def digest (blobs/sha256-hex sample))

(defn- read-from [stream limit]
  (with-redefs [k/bget (fn [_ id callback opts]
                         (is (= digest id))
                         (is (= {:sync? true :streaming? true} opts))
                         (callback {:input-stream stream}))]
    ;; Use a private test-only store seam; no production store is connected.
    (with-redefs-fn {(var dvergr.drive.blobs/the-store) (constantly :fixture)}
      #(blobs/get-bytes-bounded digest limit))))

(deftest bounded-callback-consumption
  (let [consumed (atom 0) closed (atom false)
        stream (proxy [ByteArrayInputStream] [sample]
                 (readNBytes [n] (swap! consumed + n) (proxy-super readNBytes n))
                 (close [] (reset! closed true)))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"exceeds" (read-from stream 3)))
    (is (= 4 @consumed))
    (is (= (- (alength sample) 4) (.available stream)))
    (is (false? @closed) "the callback borrows the stream"))
  (is (= (seq sample) (seq (read-from (ByteArrayInputStream. sample) (alength sample)))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"digest mismatch"
                        (read-from (ByteArrayInputStream. (.getBytes "changed" "UTF-8")) 50)))
  (is (thrown? IOException
               (read-from (proxy [java.io.InputStream] []
                            (read
                              ([] (throw (IOException. "disk failure")))
                              ([_ _ _] (throw (IOException. "disk failure"))))) 10))))

(deftest invalid-requests-never-open-storage
  (with-redefs-fn {(var dvergr.drive.blobs/the-store) #(throw (AssertionError. "storage touched"))}
    #(doseq [[id limit] [["bad" 1] [digest -1] [digest Integer/MAX_VALUE] [digest 1.5] [nil 1]]]
       (is (thrown? clojure.lang.ExceptionInfo (blobs/get-bytes-bounded id limit))))))

(defn- exercise-store! [store]
  (with-redefs-fn {(var dvergr.drive.blobs/the-store) (constantly store)}
    (fn []
      (is (nil? (blobs/get-bytes-bounded (apply str (repeat 64 "0")) 10)))
      (let [{id :blob/id} (blobs/store! sample "text/plain")]
        (is (= (seq sample) (seq (blobs/get-bytes-bounded id 100))))
        (let [detached (blobs/get-bytes-bounded id 100)]
          (aset-byte detached 0 (byte 0))
          (is (= (seq sample) (seq (blobs/get-bytes-bounded id 100)))))
        (is (thrown? clojure.lang.ExceptionInfo (blobs/get-bytes-bounded id 3))))
      (let [{id :blob/id} (blobs/store! (byte-array 0) "application/octet-stream")]
        (is (zero? (alength (blobs/get-bytes-bounded id 0))))))))

(deftest actual-filestore-streaming-read
  (let [path (Files/createTempDirectory "bounded-blobs-" (make-array FileAttribute 0))
        config {:backend :file :path (str path "/store") :id (random-uuid)}
        store (stores/create-store config {:sync? true})]
    (try
      (exercise-store! store)
      (finally
        (stores/release-store config store {:sync? true})
        (doseq [file (reverse (file-seq (.toFile path)))] (.delete file))))))

(deftest memory-store-byte-arrays
  (let [config {:backend :memory :id (random-uuid)}
        store (stores/create-store config {:sync? true})]
    (try (exercise-store! store)
         (finally (stores/release-store config store {:sync? true})))))
