(ns dvergr.rooms.immutable-file-reads-test
  "Explicit flat native snapshots, not replacements for selected MountFS reads."
  (:require [clojure.test :refer [deftest is]]
            [datahike.api :as dh]
            [dvergr.rooms.files :as files]
            [dvergr.rooms.targeted-files-test :as fixture]
            [geschichte.content :as content]
            [geschichte.fs :as fs]
            [geschichte.repo :as repo]
            [jsonista.core :as json]))

(defn with-repositories [f]
  (@#'fixture/with-repositories ["room-repo-owned" "room-repo-other"]
                                (fn [room repositories]
                                  (f room (:conn (get repositories "room-repo-owned"))
                                     (:conn (get repositories "room-repo-other"))))))

(deftest immutable-file-and-directory-projections-never-reread-current-files
  (with-repositories
    (fn [_ conn _]
      (fs/mkdir! conn "empty")
      (repo/write! conn "docs/old.md" (.getBytes "Pinned" "UTF-8"))
      (let [db @conn]
        (repo/write! conn "docs/old.md" (.getBytes "Newer" "UTF-8"))
        (repo/write! conn "later.md" (.getBytes "Not in snapshot" "UTF-8"))
        (is (= "Pinned" (:text (files/read-file-in db "/docs/old.md" {}))))
        (is (= ["docs" "empty" "identity.txt"] (mapv :name (:entries (files/list-dir-in db "/")))))
        (is (= [] (:entries (files/list-dir-in db "/empty"))))
        (is (= ["old.md"] (mapv :name (:entries (files/list-dir-in db "/docs")))))
        (is (thrown? clojure.lang.ExceptionInfo (files/read-file-in db "later.md" {})))
        (is (thrown? clojure.lang.ExceptionInfo (files/read-file-in db "docs" {})))
        (is (thrown? clojure.lang.ExceptionInfo (files/list-dir-in db "identity.txt")))))))

(deftest immutable-chunks-preserve-utf8-and-binary-contracts
  (with-repositories
    (fn [_ conn _]
      (repo/write! conn "unicode.txt" (.getBytes "γ🙂!" "UTF-8"))
      (repo/write! conn "binary.bin" (byte-array [(unchecked-byte 255) 0 1]))
      (let [db @conn text (files/read-file-in db "unicode.txt" {:length 2})]
        (is (= "γ" (:text text)))
        (is (= 2 (:next-offset text)))
        (is (= "🙂" (:text (files/read-file-in db "unicode.txt" {:offset 2 :length 4}))))
        (is (thrown? clojure.lang.ExceptionInfo (files/read-file-in db "unicode.txt" {:offset 1})))
        (is (thrown? clojure.lang.ExceptionInfo (files/read-file-in db "unicode.txt" {:length 1})))
        (is (= "/wAB" (:base64 (files/read-file-in db "binary.bin" {}))))
        (is (= 3 (:size (files/read-file-in db "binary.bin" {}))))))))

(deftest immutable-path-and-mode-guards-precede-body-decode
  (with-repositories
    (fn [_ conn _]
      (repo/write! conn "link" (.getBytes "identity.txt" "UTF-8") {:mode 0120000})
      (let [db @conn
            eid (dh/q '[:find ?e . :where [?e :geschichte.work/path "identity.txt"]] db)
            oversized (:db-after (dh/with db [[:db/add eid :geschichte.work/size (inc files/max-file-bytes)]]))]
        (with-redefs [content/read-by-id (fn [& _] (throw (AssertionError. "Invalid metadata reached body decode")))]
          (doseq [path ["../outside" ".git/config" ".env" "link"]]
            (is (thrown? clojure.lang.ExceptionInfo (files/read-file-in db path {}))))
          (is (thrown? clojure.lang.ExceptionInfo (files/read-file-in oversized "identity.txt" {}))))
        (with-redefs [content/read-by-id (constantly nil)]
          (is (thrown? clojure.lang.ExceptionInfo (files/read-file-in db "identity.txt" {}))))
        (is (thrown? clojure.lang.ExceptionInfo (files/list-dir-in db "/" {:offset -1})))
        (is (thrown? clojure.lang.ExceptionInfo (files/read-file-in db "identity.txt" {:length 0})))
        (is (thrown? clojure.lang.ExceptionInfo (files/read-file-in nil "identity.txt" {})))))))

(deftest whole-resource-size-is-checked-before-decoding
  (with-repositories
    (fn [_ conn _]
      (let [db @conn
            eid (dh/q '[:find ?e . :where [?e :geschichte.work/path "identity.txt"]] db)
            oversized (:db-after (dh/with db [[:db/add eid :geschichte.work/size (inc files/max-resource-bytes)]]))]
        (with-redefs [content/read-by-id (fn [& _] (throw (AssertionError. "Oversized resource reached body decode")))]
          (is (thrown? clojure.lang.ExceptionInfo
                       (files/read-file-in oversized "identity.txt" {:max files/max-resource-bytes :whole? true}))))))))

(deftest immutable-listing-pages-obey-entry-and-character-limits
  (with-repositories
    (fn [_ conn _]
      (doseq [path ["pages/a.txt" "pages/b.txt" "pages/c.txt"]]
        (repo/write! conn path (.getBytes path "UTF-8")))
      (let [db @conn]
        (with-redefs [files/max-list-entries 2]
          (let [first-page (files/list-dir-in db "/pages")
                last-page (files/list-dir-in db "/pages" {:offset (:next-offset first-page)})]
            (is (= 3 (:total first-page)))
            (is (= ["a.txt" "b.txt"] (mapv :name (:entries first-page))))
            (is (= 2 (:next-offset first-page)))
            (is (= ["c.txt"] (mapv :name (:entries last-page))))
            (is (not (contains? last-page :next-offset)))))
        (with-redefs [files/max-list-chars (inc (count (json/write-value-as-string
                                                        (first (:entries (files/list-dir-in db "/pages"))))))]
          (let [page (files/list-dir-in db "/pages")]
            (is (= ["a.txt"] (mapv :name (:entries page))))
            (is (= 1 (:next-offset page)))))
        ;; Native agent-authored path names have no universal length ceiling.
        ;; Unlike the legacy selected API, this explicit reader refuses one
        ;; entry exceeding its budget rather than making an oversized page.
        (with-redefs [files/max-list-chars 1]
          (is (thrown? clojure.lang.ExceptionInfo (files/list-dir-in db "/pages"))))
        (is (= [] (:entries (files/list-dir-in db "/pages" {:offset 3}))))))))

(deftest immutable-metadata-cap-refuses-before-listing-or-body-decode
  (with-repositories
    (fn [_ conn _]
      ;; Lower only this public policy limit; exercise actual indexed metadata
      ;; rather than mocking datoms or constructing twenty thousand entries.
      (doseq [path ["a.txt" "b.txt" "c.txt"]]
        (repo/write! conn path (.getBytes path "UTF-8")))
      (let [db @conn]
        (is (= 4 (count (dh/datoms db :avet :geschichte.work/path))))
        (with-redefs [files/max-read-index-entries 3
                      fs/list-dir (fn [& _] (throw (AssertionError. "Over-cap index reached listing")))
                      content/read-by-id (fn [& _] (throw (AssertionError. "Over-cap index reached body decode")))]
          (is (thrown? clojure.lang.ExceptionInfo (files/list-dir-in db "/")))
          (is (thrown? clojure.lang.ExceptionInfo (files/read-file-in db "identity.txt" {}))))))))
