(ns dvergr.artifact-bounded-read-test
  (:require [clojure.test :refer [deftest is]]
            [datahike.blob :as blob]
            [dvergr.artifact :as artifact]
            [konserve.core :as k]
            [konserve.filestore :as fs])
  (:import [java.io ByteArrayInputStream InputStream]
           [java.nio.file Files]))

(defn- read-source [text stream?]
  (let [bytes (.getBytes ^String text "UTF-8") ref (blob/blob-id bytes)
        store (artifact/datahike-store (atom {:store ::backend}))]
    (with-redefs [k/bget (fn [backend actual consume options]
                           (is (= ::backend backend))
                           (is (= ref actual))
                           (is (= {:sync? true :streaming? true} options))
                           (consume (if stream? {:input-stream (ByteArrayInputStream. bytes)}
                                        {:input-stream bytes :blob bytes})))]
      (artifact/get-bounded-value store ref 1024))))

(deftest eof-is-reader-state-and-cannot-be-supplied-as-a-keyword
  (doseq [stream? [true false]]
    (is (= :dvergr.artifact/eof (:value (read-source ":dvergr.artifact/eof" stream?)))
        "the former public sentinel remains a valid ordinary EDN value")
    (is (nil? (:value (read-source "nil\n" stream?))))
    (is (= {:answer 1} (:value (read-source "{:answer 1}\n " stream?))))
    (doseq [text ["" "{:answer 1} :dvergr.artifact/eof"
                  "{:answer 1} :dvergr.artifact/eof {:hidden 2}"]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"one EDN value" (read-source text stream?))))))

(defn- counting-stream [^InputStream source consumed]
  (proxy [InputStream] []
    (read
      ([] (let [n (.read source)] (when (not= -1 n) (swap! consumed inc)) n))
      ([buffer offset length]
       (let [n (.read source buffer offset length)]
         (when (pos? n) (swap! consumed + n)) n)))))

(deftest oversize-streams-stop-at-the-first-extra-byte
  (let [bytes (.getBytes (pr-str {:answer (apply str (repeat 10000 "x"))}) "UTF-8")
        ref (blob/blob-id bytes) consumed (atom 0)
        store (artifact/datahike-store (atom {:store ::backend}))]
    (with-redefs [k/bget (fn [_ _ consume options]
                           (is (= {:sync? true :streaming? true} options))
                           (consume {:input-stream (counting-stream (ByteArrayInputStream. bytes) consumed)}))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"exceeds read limit"
                            (artifact/get-bounded-value store ref 64)))
      (is (= 65 @consumed)))))

(deftest unrecognized-backend-handles-do-not-use-a-materializing-fallback
  (let [store (artifact/datahike-store (atom {:store ::backend}))]
    (with-redefs [k/bget (fn [_ _ consume _] (consume {:input-stream ::unsupported}))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"streaming view"
                            (artifact/get-bounded-value store (random-uuid) 64))))))

(deftest actual-filestore-lends-a-stream-without-materializing-the-payload
  (let [path (str (Files/createTempDirectory "bounded-artifact-" (make-array java.nio.file.attribute.FileAttribute 0)))
        backend (fs/connect-fs-store path :opts {:sync? true})
        bytes (.getBytes (pr-str {:answer (apply str (repeat 10000 "x"))}) "UTF-8")
        ref (blob/blob-id bytes) consumed (atom 0)
        store (artifact/datahike-store (atom {:store backend}))
        bget k/bget]
    (try
      (k/bassoc backend ref bytes {:sync? true})
      (with-redefs [k/bget (fn [actual key consume options]
                             (is (= {:sync? true :streaming? true} options))
                             (bget actual key
                                   (fn [{:keys [input-stream] :as borrowed}]
                                     (is (instance? InputStream input-stream))
                                     (is (not (instance? ByteArrayInputStream input-stream))
                                         "Native filestore did not preallocate a full byte array")
                                     (consume (assoc borrowed :input-stream (counting-stream input-stream consumed))))
                                   options))]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"exceeds read limit"
                              (artifact/get-bounded-value store ref 64)))
        (is (= 65 @consumed)))
      (finally (fs/delete-store path)))))
