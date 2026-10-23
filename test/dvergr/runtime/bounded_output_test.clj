(ns dvergr.runtime.bounded-output-test
  (:require [clojure.test :refer [deftest is]]
            [dvergr.runtime.bounded-output :as output])
  (:import [java.io Writer]))

(deftest capture-is-bounded-for-writer-overloads-and-snapshots-are-immutable
  (let [{:keys [writer snapshot]} (output/create 8) ^Writer writer writer]
    (.write writer "abc")
    (let [before (snapshot)]
      (.write writer (char-array "defghi") 0 6)
      (.write writer (int \!))
      (is (= {:text "abc" :truncated? false} before))
      (is (= {:text "abcdefgh" :truncated? true} (snapshot)))
      (is (= "abcdefgh" (str writer)))))
  (let [{:keys [writer snapshot]} (output/create 0) ^Writer writer writer]
    (.write writer "discarded")
    (is (= {:text "" :truncated? true} (snapshot)))))

(deftest unicode-quota-does-not-return-a-dangling-surrogate
  (let [{:keys [writer snapshot]} (output/create 2) ^Writer writer writer]
    (.write writer "a😀z")
    (is (= {:text "a" :truncated? true} (snapshot)))))

(deftest integer-writes-follow-the-java-writer-low-sixteen-bit-contract
  (let [{:keys [writer snapshot]} (output/create 2) ^Writer writer writer]
    (.write writer (int 65537))
    (.write writer (int -1))
    (is (= {:text (str (char 1) (char 65535)) :truncated? false} (snapshot)))))
