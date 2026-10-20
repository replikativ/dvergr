(ns dvergr.catalog.immutable-test
  (:require [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [dvergr.catalog.immutable :as projection]
            [dvergr.catalog.room :as room]
            [dvergr.rooms.immutable-file-reads-test :as fixture]
            [geschichte.content :as content]
            [geschichte.query :as query]
            [geschichte.repo :as repo]))

(def valid-source
  {"workflow.edn" "{:title \"Owned\" :task \"Think\" :params {:topic \"markets\"}}"
   "checker.clj" "(throw (ex-info \"MUST NEVER EXECUTE\" {}))"
   "fixtures/input.md" "Owned evidence"})

(defn seed! [conn name source]
  (doseq [[path text] source]
    (repo/write! conn (str "workflows/" name "/" path) (.getBytes ^String text "UTF-8"))))

(deftest descriptions-pin-complete-source-and-portable-bundle-id
  (fixture/with-repositories
    (fn [_ conn _]
      (seed! conn "insight" valid-source)
      (repo/write! conn "workflows/draft/checker.clj" (.getBytes "Draft" "UTF-8"))
      (let [db @conn expected (str (:id (room/bundle "insight" valid-source)))]
        (seed! conn "insight" (assoc valid-source "fixtures/input.md" "Later"))
        (with-redefs [room/tier (constantly :ad-hoc)
                      room/calibrate (fn [& _] (throw (AssertionError. "Checker executed")))
                      room/run-checker (fn [& _] (throw (AssertionError. "Checker executed")))]
          (let [uuid-rows (projection/list-in db "canonical-uuid" "stamp")
                slug-rows (projection/list-in db "display-slug" "stamp")]
            (is (= 1 (count uuid-rows)))
            (is (= expected (:bundle (first uuid-rows)) (:bundle (first slug-rows))))
            (is (= "canonical-uuid/insight" (:id (first uuid-rows))))
            (is (= "display-slug/insight" (:id (first slug-rows))))
            (is (= "stamp" (:target-stamp (first uuid-rows))))))))))

(deftest non-map-and-invalid-definitions-are-inert-malformed-rows
  (fixture/with-repositories
    (fn [_ conn _]
      (doseq [definition ["nil" "false" "3" "\"text\"" "[]" "#{}" "{:title 9}"]]
        (seed! conn "bad" (assoc valid-source "workflow.edn" definition))
        (with-redefs [room/tier (fn [& _] (throw (AssertionError. "Invalid definition reached tier")))
                      room/run-checker (fn [& _] (throw (AssertionError. "Checker executed")))]
          (let [row (first (projection/list-in @conn "room" "stamp"))]
            (is (= ["not well formed; source inspection required"] (:problems row)))
            (is (not (contains? row :bundle)))
            (is (not (contains? row :verifier)))))))))

(deftest whole-subtree-file-count-cap-is-exact-and-metadata-first
  (fixture/with-repositories
    (fn [_ conn _]
      (let [db @conn {:keys [content size mode]} (get (query/worktree db) "identity.txt")
            make-db (fn [n]
                      (:db-after (d/with db
                                         (mapv (fn [i] {:geschichte.work/path (str "workflows/cap/" i ".txt")
                                                        :geschichte.work/content [:geschichte.content/id content]
                                                        :geschichte.work/size size :geschichte.work/mode mode})
                                               (range n)))))]
        (is (= 500 (count (projection/snapshot-in (make-db 500)))))
        (with-redefs [content/read-by-id (fn [& _] (throw (AssertionError. "Overflow reached body")))]
          (is (thrown? clojure.lang.ExceptionInfo (projection/snapshot-in (make-db 501)))))
        (let [valid (make-db 2)
              eid (d/q '[:find ?e . :where [?e :geschichte.work/path "workflows/cap/1.txt"]] valid)
              invalid (:db-after (d/with valid [[:db/add eid :geschichte.work/size (inc size)]]))]
          (with-redefs [content/read-by-id (fn [& _] (throw (AssertionError. "Late corrupt metadata reached first body")))]
            (is (thrown? clojure.lang.ExceptionInfo (projection/snapshot-in invalid)))))))))

(deftest whole-subtree-byte-cap-counts-actual-multibyte-utf8
  (fixture/with-repositories
    (fn [_ conn _]
     ;; γ is two UTF-8 bytes, so exact2MiB is only1Mi characters.
      (let [text (apply str (repeat (/ projection/max-bytes 2) "γ"))]
        (repo/write! conn "workflows/cap/payload.txt" (.getBytes text "UTF-8"))
        (is (= text (get (projection/snapshot-in @conn) "workflows/cap/payload.txt")))
        (repo/write! conn "workflows/cap/payload.txt" (.getBytes (str text "x") "UTF-8"))
        (with-redefs [content/read-by-id (fn [& _] (throw (AssertionError. "Overflow reached body")))]
          (is (thrown? clojure.lang.ExceptionInfo (projection/snapshot-in @conn))))))))

(deftest corrupt-bodies-and-trusted-tier-failures-are-not-malformed-rows
  (fixture/with-repositories
    (fn [_ conn _]
      (seed! conn "insight" valid-source)
      (with-redefs [content/read-by-id (constantly nil)]
        (is (thrown? clojure.lang.ExceptionInfo (projection/list-in @conn "room" "stamp"))))
      (with-redefs [room/tier (fn [& _] (throw (ex-info "Trusted promotion IO failed" {})))]
        (is (thrown? clojure.lang.ExceptionInfo (projection/list-in @conn "room" "stamp")))))))

(deftest output-depth-nodes-and-encoded-bytes-fail-whole
  (fixture/with-repositories
    (fn [_ conn _]
      (seed! conn "insight" valid-source)
      (with-redefs [room/tier (constantly :ad-hoc)]
        (with-redefs [projection/max-output-depth 2]
          (is (thrown? clojure.lang.ExceptionInfo (projection/list-in @conn "room" "stamp"))))
        (with-redefs [projection/max-output-nodes 2]
          (is (thrown? clojure.lang.ExceptionInfo (projection/list-in @conn "room" "stamp"))))
        (with-redefs [projection/max-output-bytes 2]
          (is (thrown? clojure.lang.ExceptionInfo (projection/list-in @conn "room" "stamp"))))))))

(deftest symlinks-invalid-utf8-and-forbidden-paths-fail-as-read-errors
  (fixture/with-repositories
    (fn [_ conn _]
      (repo/write! conn "workflows/bad/bytes.bin" (byte-array [(unchecked-byte 255)]))
      (is (thrown? java.nio.charset.CharacterCodingException (projection/snapshot-in @conn)))
      (let [db @conn
            eid (d/q '[:find ?e . :where [?e :geschichte.work/path "workflows/bad/bytes.bin"]] db)
            link (:db-after (d/with db [[:db/add eid :geschichte.work/mode 0120000]]))
            secret (:db-after (d/with db [[:db/add eid :geschichte.work/path "workflows/bad/.env"]]))]
        (with-redefs [content/read-by-id (fn [& _] (throw (AssertionError. "Unsafe metadata reached body")))]
          (is (thrown? clojure.lang.ExceptionInfo (projection/snapshot-in link)))
          (is (thrown? clojure.lang.ExceptionInfo (projection/snapshot-in secret))))))))
