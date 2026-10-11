(ns dvergr.rooms.files-test
  "A room's workspace files from outside the room: listed, read as text or
   base64 in chunks, and written as one commit of exactly the named paths,
   through the effect boundary (a read-only world refuses and receipts it)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dvergr.catalog.workspace :as ws]
            [dvergr.discourse :as d]
            [dvergr.effects :as effects]
            [dvergr.room.store.memory :as memory]
            [dvergr.rooms.files :as files]
            [dvergr.rooms.forks :as forks]
            [dvergr.substrate.geschichte :as gs]
            [geschichte.repo :as repo]
            [muschel.fs :as mfs]
            [org.replikativ.spindel.engine.core :as ec])
  (:import [java.util Base64]))

(defn- room []
  (let [r (d/make-room {:id (keyword (str "files-test-" (random-uuid))) :store (memory/make)})]
    (ws/ensure-workspace! r)
    r))

(defn- conn [r] (binding [ec/*execution-context* (:ctx r)] (:conn (gs/current-workspace))))
(defn- commits [r] (count (repo/log (conn r) {:limit 1000})))
(defn- b64 [^bytes b] (.encodeToString (Base64/getEncoder) b))
(defn- unb64 [^String s] (.decode (Base64/getDecoder) s))

(def ^:private xlsx-ish
  "Bytes that are not UTF-8 text: a zip header and invalid sequences."
  (byte-array (map unchecked-byte [0x50 0x4b 0x03 0x04 0x00 0xff 0xfe 0x80 0x81 0x00 0x10])))

(deftest a-batch-is-one-commit-and-reads-back
  (let [r (room)
        before (commits r)
        res (files/write! r {"data/cases.csv" {:text "no;account\n1;4930\n"}
                             "/data/book.xlsx" {"base64" (b64 xlsx-ish)}}
                          {:message "Add the cases"})]
    (testing "one commit, with the message, naming both files"
      (is (string? (:commit res)))
      (is (= (inc before) (commits r)))
      (is (= "Add the cases" (:geschichte.commit/message (first (repo/log (conn r) {:limit 1})))))
      (is (= (:commit res) (str (:geschichte.commit/id (repo/head-commit (conn r))))))
      (is (= ["/data/book.xlsx" "/data/cases.csv"] (mapv :path (:written res))))
      (is (every? :content-id (:written res))))
    (testing "listed with kind, size and content id"
      (let [{:keys [entries]} (files/list-dir r "/data")]
        (is (= #{"book.xlsx" "cases.csv"} (set (map :name entries))))
        (is (every? #(= "file" (:kind %)) entries))
        (is (= (count xlsx-ish) (:size (first (filter #(= "book.xlsx" (:name %)) entries)))))
        (is (= (set (map :content-id (:written res))) (set (map :content-id entries)))))
      (is (some #(and (= "data" (:name %)) (= "dir" (:kind %))) (:entries (files/list-dir r nil)))))
    (testing "text as text, binary as base64 with a media type"
      (let [t (files/read-file r "data/cases.csv" {})
            b (files/read-file r "/data/book.xlsx" {})]
        (is (= "no;account\n1;4930\n" (:text t)))
        (is (= "text/csv" (:media-type t)))
        (is (nil? (:base64 t)))
        (is (= (seq xlsx-ish) (seq (unb64 (:base64 b)))))
        (is (= "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" (:media-type b)))
        (is (nil? (:next-offset b)))))
    (testing "the same batch again commits nothing"
      (let [again (files/write! r {"data/cases.csv" {:text "no;account\n1;4930\n"}} {})]
        (is (nil? (:commit again)))
        (is (= (inc before) (commits r)))))
    (testing "null removes a file, committed"
      (let [rm (files/write! r {"data/book.xlsx" nil "data/never-there.txt" nil} {})]
        (is (= ["/data/book.xlsx"] (:removed rm)))
        (is (string? (:commit rm)))
        (is (= ["cases.csv"] (mapv :name (:entries (files/list-dir r "/data")))))
        (is (nil? (get (repo/tree (conn r) :head) "data/book.xlsx")) "gone from HEAD")))))

(deftest only-the-named-paths-are-committed
  (let [r (room)
        fs (ws/room-fs r)]
    (is (mfs/write-string! fs "/scratch.md" "an agent's draft" false))
    (files/write! r {"notes.md" {:text "mine"}} {})
    (is (contains? (repo/tree (conn r) :head) "notes.md"))
    (is (not (contains? (repo/tree (conn r) :head) "scratch.md")) "someone else's change is not swept in")
    (is (some #(str/includes? % "scratch.md") (forks/workspace-changes r)))))

(deftest paths-stay-in-the-workspace
  (let [r (room)
        before (commits r)
        refused? (fn [path]
                   (try (files/write! r {"ok.txt" {:text "fine"} path {:text "x"}} {}) false
                        (catch clojure.lang.ExceptionInfo e (= ::files/invalid (:type (ex-data e))))))]
    (doseq [p ["../escape.txt" "a/../../b" "/a/../b" ".git/config" "sub/.git/hooks/pre-commit"
               "" "/" "a\\b" ".env"]]
      (is (refused? p) (pr-str p)))
    (is (= before (commits r)) "a refused batch writes nothing")
    (is (not (mfs/exists? (ws/room-fs r) "/ok.txt")))
    (testing "content is {text} or {base64}, not both, not something else"
      (doseq [v [{:text "a" :base64 "YQ=="} {:base64 "%%%"} 42 {:other "x"}]]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"text|base64" (files/write! r {"f.txt" v} {})))))
    (testing "a directory is not overwritten or removed"
      (files/write! r {"dir/a.txt" {:text "a"}} {})
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"directory" (files/write! r {"dir" {:text "x"}} {})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"directory" (files/write! r {"dir" nil} {})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"is a file" (files/write! r {"dir/a.txt/b" {:text "x"}} {}))))
    (testing "reads refuse the same paths"
      (is (thrown? clojure.lang.ExceptionInfo (files/read-file r "../x" {})))
      (is (thrown? clojure.lang.ExceptionInfo (files/list-dir r ".git"))))))

(deftest a-read-only-world-refuses-and-receipts-the-write
  (let [r (room)
        sink (effects/make-sink)
        before (commits r)]
    (effects/install-world! (:ctx r) [[:read-only]])
    (effects/set-world-sink! (:ctx r) sink)
    (let [e (try (files/write! r {"data/cases.csv" {:text "x"}} {}) nil
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (= :effect/denied (:type (ex-data e))) (pr-str (ex-data e)))
      (is (re-find #"read-only" (ex-message e))))
    (is (some #(and (= :fs/write (:effect %)) (= :denied (:decision %))
                    (= "/data/cases.csv" (get-in % [:resource :path])))
              @sink)
        "the denial is receipted in the world's sink")
    (is (= before (commits r)))
    (is (not (mfs/exists? (ws/room-fs r) "/data/cases.csv")))))

(deftest a-write-refused-midway-leaves-the-batch-paths-as-they-were
  (let [r (room)]
    (files/write! r {"a.txt" {:text "old"}} {})
    (let [n (atom 0)
          boundary (constantly {:handlers [(fn [effect next]
                                             (if (and (= :fs/write (:effect effect)) (< 1 (swap! n inc)))
                                               (effects/deny! effect :quota "full")
                                               (next effect)))]})]
      (is (thrown? clojure.lang.ExceptionInfo
                   (files/write! r {"a.txt" {:text "new"} "b.txt" {:text "b"}} {:boundary boundary})))
      (is (= "old" (:text (files/read-file r "a.txt" {}))) "the first write is undone")
      (is (not (mfs/exists? (ws/room-fs r) "/b.txt")))
      (is (empty? (forks/workspace-changes r))))))

(deftest a-large-file-reads-in-chunks-on-character-boundaries
  (let [r (room)
        text (apply str (repeat (quot (* 3 files/max-read-bytes) 7) "Grüße\n"))
        _ (files/write! r {"big.txt" {:text text}} {})
        chunks (loop [offset 0 acc []]
                 (let [{:keys [next-offset] :as c} (files/read-file r "big.txt" {:offset offset})]
                   (is (<= (:length c) files/max-read-bytes))
                   (if next-offset (recur next-offset (conj acc c)) (conj acc c))))]
    (is (< 2 (count chunks)))
    (is (= text (apply str (map :text chunks))) "no character is split")
    (is (= 10 (:length (files/read-file r "big.txt" {:offset 0 :length 10}))))
    (is (thrown? clojure.lang.ExceptionInfo (files/read-file r "big.txt" {:offset (* 10 files/max-read-bytes)})))))
