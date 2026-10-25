(ns dvergr.rooms.file-source-test
  (:require [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [dvergr.rooms.file-source :as source]
            [dvergr.rooms.files :as files]
            [dvergr.rooms.forks :as forks]
            [dvergr.rooms.targeted-files-test :as fixture]
            [geschichte.repo :as repo])
  (:import [java.io File FileOutputStream]
           [java.nio.file Files LinkOption]))

(defn- with-file [size f]
  (let [file (File/createTempFile "dvergr-finalized" ".bin")]
    (try
      (with-open [out (FileOutputStream. file)]
        (let [buffer (byte-array 65536)]
          (loop [remaining size]
            (when (pos? remaining)
              (let [n (min remaining (alength buffer))]
                (.write out buffer 0 n) (recur (- remaining n)))))))
      (f file)
      (finally (.delete file)))))

(defn- with-target [f]
  (@#'fixture/with-repositories
   ["room-repo-other" "room-repo-owned"]
   (fn [room repositories]
     (f room (get repositories "room-repo-owned") (get repositories "room-repo-other")))))

(deftest source-is-single-use-thread-bound-and-retired-on-exit
  (with-file
    3
    (fn [file]
      (let [escaped (atom nil)]
        (source/with-finalized-file!
          file
          (fn [owned]
            (reset! escaped owned)
            (is (= 3 (:size (source/identity-of owned))))
            (is (= true @(future (try (source/identity-of owned) false (catch Exception _ true)))))
            (is (= 0 (source/with-input! owned #(.read %))))
            (is (thrown? Exception (source/with-input! owned identity)))))
        (is (thrown? Exception (source/identity-of @escaped)))))))

(deftest finalized-source-refuses-symlinks-and-directories
  (with-file
    0
    (fn [file]
      (let [link (.resolveSibling (.toPath file) (str (.getName file) ".link"))]
        (try
          (Files/createSymbolicLink link (.toPath file) (make-array java.nio.file.attribute.FileAttribute 0))
          (is (thrown? Exception (source/with-finalized-file! (.toFile link) identity)))
          (is (thrown? Exception (source/with-finalized-file! (.getParentFile file) identity)))
          (finally (Files/deleteIfExists link)))))))

(deftest host-publication-supports-large-files-without-changing-public-caps
  (with-target
    (fn [room {:keys [system conn]} other]
      (let [other-before (repo/worktree (:conn other)) size (inc files/max-file-bytes)]
        (with-file
          size
          (fn [file]
            (source/with-finalized-file!
              file
              (fn [owned]
                (let [identity (source/identity-of owned)
                      result (files/write-file-to-completion! room system "captures/segment.webm" owned
                                                              {:expected-worktree {"/captures/segment.webm" nil}})]
                  (is (= :returned (:completion result)))
                  (is (nil? (:error result)))
                  (is (true? (get-in result [:result :applied?])))
                  (is (= identity (select-keys (get (repo/worktree conn) "captures/segment.webm") [:content :size])))
                  (is (= size (get-in result [:result :written 0 :size])))
                  (is (empty? (:staged (repo/status conn)))))))))
        (is (= other-before (repo/worktree (:conn other))))
        (is (= (* 8 1024 1024) files/max-file-bytes))))))

(deftest stale-or-unsupported-plans-do-not-start-native-publication
  (with-target
    (fn [room {:keys [system conn]} _]
      (with-file
        1
        (fn [file]
          (doseq [opts [{} {:expected-worktree {"/identity.txt" nil}}
                        {:expected-worktree {"/new.bin" nil} :validated-basis {}}
                        {:expected-worktree {"/new.bin" nil} :expected-subtree {}}]]
            (source/with-finalized-file!
              file
              (fn [owned]
                (let [before (repo/worktree conn)
                      result (files/write-file-to-completion! room system "new.bin" owned opts)]
                  (is (= :not-started (:completion result)))
                  (is (some? (:error result)))
                  (is (= before (repo/worktree conn))))))))))))

(deftest changed-finalized-bytes-refuse-before-worktree-publication
  (with-target
    (fn [room {:keys [system conn]} _]
      (with-file
        3
        (fn [file]
          (source/with-finalized-file!
            file
            (fn [owned]
              (spit file "new")
              (let [before (repo/worktree conn)
                    result (files/write-file-to-completion! room system "changed.bin" owned
                                                            {:expected-worktree {"/changed.bin" nil}})]
                (is (some? (:error result)))
                (is (not= :returned (:completion result)))
                (is (= before (repo/worktree conn)))
                (is (empty? (:staged (repo/status conn))))))))))))

(deftest saved-forwarding-cannot-start-a-late-native-write
  (with-target
    (fn [room {:keys [system conn]} _]
      (with-file
        3
        (fn [file]
          (let [saved (atom nil)
                result (source/with-finalized-file!
                         file
                         (fn [owned]
                           (files/write-file-to-completion!
                            room system "late.bin" owned
                            {:expected-worktree {"/late.bin" nil}
                             :boundary (constantly {:handlers [(fn [effect next]
                                                                 (reset! saved #(next effect)) :deferred)]})})))]
            (is (= :not-started (:completion result)))
            (is (thrown? Exception (@saved)))
            (is (nil? (get (repo/worktree conn) "late.bin")))))))))

(deftest post-dispatch-failure-preserves-foreign-mode-and-index-edits
  (with-target
    (fn [room {:keys [system conn]} _]
      (with-file
        3
        (fn [file]
          (source/with-finalized-file!
            file
            (fn [owned]
              (let [foreign-index (atom nil)
                    result (with-redefs [forks/commit-paths-in-workspace!
                                         (fn [& _]
                                           (d/transact conn [[:db/add [:geschichte.work/path "new.bin"] :geschichte.work/mode 33261]])
                                           (repo/write! conn "foreign.txt" (.getBytes "preserve" "UTF-8"))
                                           (repo/stage-all! conn)
                                           (reset! foreign-index (repo/index-tree conn))
                                           (throw (ex-info "commit failed after foreign edit" {})))]
                             (files/write-file-to-completion! room system "new.bin" owned
                                                              {:expected-worktree {"/new.bin" nil}}))]
                (is (= :pending (:completion result)))
                (is (= 33261 (:mode (get (repo/worktree conn) "new.bin"))))
                (is (= @foreign-index (repo/index-tree conn)))))))))))

(deftest lost-commit-acknowledgement-stays-pending-with-published-bytes-intact
  (with-target
    (fn [room {:keys [system conn]} _]
      (with-file
        3
        (fn [file]
          (source/with-finalized-file!
            file
            (fn [owned]
              (let [commit! forks/commit-paths-in-workspace!
                    result (with-redefs [forks/commit-paths-in-workspace!
                                         (fn [& args]
                                           (apply commit! args)
                                           (throw (java.io.IOException. "commit acknowledgement lost")))]
                             (files/write-file-to-completion! room system "committed.bin" owned
                                                              {:expected-worktree {"/committed.bin" nil}}))]
                (is (= :pending (:completion result)))
                (is (= (:content (source/identity-of owned))
                       (:content (get (repo/tree conn :head) "committed.bin")))))
              (is (empty? (:staged (repo/status conn)))))))))))

(deftest repeated-forwarding-consumes-only-one-native-source
  (with-target
    (fn [room {:keys [system conn]} _]
      (with-file
        3
        (fn [file]
          (source/with-finalized-file!
            file
            (fn [owned]
              (let [read! source/with-input! calls (atom 0)
                    result (with-redefs [source/with-input! (fn [& args] (swap! calls inc) (apply read! args))]
                             (files/write-file-to-completion!
                              room system "once.bin" owned
                              {:expected-worktree {"/once.bin" nil}
                               :boundary (constantly {:handlers [(fn [effect next] (next effect) (next effect))]})}))]
                (is (= :returned (:completion result)))
                (is (= 1 @calls))
                (is (= 3 (:size (get (repo/worktree conn) "once.bin"))))))))))))

(deftest denied-write-does-not-consume-the-source
  (with-target
    (fn [room {:keys [system conn]} _]
      (with-file
        3
        (fn [file]
          (source/with-finalized-file!
            file
            (fn [owned]
              (let [result (files/write-file-to-completion!
                            room system "denied.bin" owned
                            {:expected-worktree {"/denied.bin" nil}
                             :boundary (constantly {:handlers [(fn [effect next]
                                                                 (if (= :fs/write (:effect effect)) :denied (next effect)))]})})]
                (is (not= :returned (:completion result)))
                (is (nil? (get (repo/worktree conn) "denied.bin")))
                (is (= 0 (source/with-input! owned #(.read %))))))))))))

(deftest existing-identical-files-cannot-be-replaced-through-source-publication
  (with-target
    (fn [room {:keys [system conn]} _]
      (with-file
        3
        (fn [file]
          (source/with-finalized-file!
            file #(is (= :returned (:completion (files/write-file-to-completion! room system "existing.bin" %
                                                                                 {:expected-worktree {"/existing.bin" nil}})))))
          (let [entry (get (repo/worktree conn) "existing.bin")]
            (doseq [expected [nil entry]]
              (source/with-finalized-file!
                file
                (fn [owned]
                  (let [result (files/write-file-to-completion! room system "existing.bin" owned
                                                                {:expected-worktree {"/existing.bin" expected}})]
                    (is (= :not-started (:completion result)))
                    (is (some? (:error result)))
                    (is (= 0 (source/with-input! owned #(.read %))))
                    (is (= entry (get (repo/worktree conn) "existing.bin")))))))))))))

(deftest concurrent-and-incomplete-destination-rows-refuse-atomically
  (doseq [incomplete? [false true]]
    (with-target
      (fn [room {:keys [system conn]} _]
        (with-file
          3
          (fn [file]
            (source/with-finalized-file!
              file
              (fn [owned]
                (let [foreign (atom nil)
                      result (files/write-file-to-completion!
                              room system "raced.bin" owned
                              {:expected-worktree {"/raced.bin" nil}
                               :boundary (constantly
                                          {:handlers [(fn [effect next]
                                                        (when (= :fs/write (:effect effect))
                                                          (if incomplete?
                                                            (d/transact conn [{:geschichte.work/path "raced.bin"}])
                                                            (repo/write! conn "raced.bin" (.getBytes "foreign" "UTF-8")))
                                                          (reset! foreign (d/pull @conn '[*] [:geschichte.work/path "raced.bin"])))
                                                        (next effect))]})})]
                  (is (= :pending (:completion result)))
                  (is (some? (:error result)))
                  (is (= @foreign (d/pull @conn '[*] [:geschichte.work/path "raced.bin"]))))))))))))
