(ns dvergr.agent.conversation-store-test
  "The durable experiment store (`conversation/open-store!`)."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as dh]
            [dvergr.agent.conversation :as conv]
            [dvergr.substrate.datahike :as sdh])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-dir []
  (str (Files/createTempDirectory "dvergr-exp-store" (make-array FileAttribute 0))))

(defn- delete-tree! [dir]
  (doseq [f (reverse (file-seq (io/file dir)))] (.delete ^java.io.File f)))

(deftest a-new-experiment-store-buffers-diffs
  ;; every Attempt is a small commit; without diff buffering each one rewrites
  ;; whole index paths (hundreds of KB), and experiment stores grow to GBs
  (let [dir (temp-dir)]
    (try
      (let [xs (conv/open-store! dir)]
        (try
          (is (= sdh/diff-buf-size
                 (get-in (:config @(:conn xs)) [:index-config :diff-buf-size])))
          (finally (conv/close-store! xs))))
      (testing "and reopens with what it was created with"
        (let [xs (conv/open-store! dir)]
          (try
            (is (= sdh/diff-buf-size
                   (get-in (:config @(:conn xs)) [:index-config :diff-buf-size])))
            (finally (conv/close-store! xs)))))
      (finally (delete-tree! dir)))))

(deftest a-store-created-without-the-option-still-opens
  ;; the option is create-time-fixed: datahike raises on a conflicting value at
  ;; connect, so an existing experiment directory must not be given one
  (let [dir (temp-dir)
        cfg (#'conv/store-config dir)]
    (try
      (dh/create-database cfg)
      (let [xs (conv/open-store! dir)]
        (try
          (is (some? (:conn xs)))
          (is (contains? #{nil 0} (get-in (:config @(:conn xs)) [:index-config :diff-buf-size])))
          (finally (conv/close-store! xs))))
      (finally (delete-tree! dir)))))
