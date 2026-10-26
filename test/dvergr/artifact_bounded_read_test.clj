(ns dvergr.artifact-bounded-read-test
  (:require [clojure.test :refer [deftest is]]
            [datahike.blob :as blob]
            [dvergr.artifact :as artifact]
            [konserve.core :as k])
  (:import [java.io ByteArrayInputStream]))

(defn- read-source [text stream?]
  (let [bytes (.getBytes ^String text "UTF-8") ref (blob/blob-id bytes)
        store (artifact/datahike-store (atom {:store ::backend}))]
    (with-redefs [k/bget (fn [backend actual consume options]
                           (is (= ::backend backend))
                           (is (= ref actual))
                           (is (= {:sync? true} options))
                           (consume {:input-stream (if stream? (ByteArrayInputStream. bytes) bytes)}))]
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
