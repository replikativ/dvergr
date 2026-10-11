(ns dvergr.rooms.files-test
  "A room's workspace files from outside the room: listed, read as text or
   base64 in chunks, and written as one commit of exactly the named paths,
   through the effect boundary (a read-only world refuses and receipts it)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dvergr.catalog.workspace :as ws]
            [dvergr.discourse :as d]
            [dvergr.effects :as effects]
            [dvergr.intake.bash :as bash]
            [dvergr.mcp.surface :as surface]
            [dvergr.room.store.memory :as memory]
            [dvergr.rooms.files :as files]
            [dvergr.rooms.forks :as forks]
            [dvergr.substrate.geschichte :as gs]
            [dvergr.tools :as tools]
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
    (is (some #(and (= :git/commit (:effect %)) (= :denied (:decision %)) (= :read-only (:by %))
                    (= "Write data/cases.csv" (get-in % [:resource :message])))
              @sink)
        "the denial is receipted in the world's sink, before anything is written")
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

(defn- worktree-text [r p] (some-> (mfs/read-file (ws/room-fs r) p) str))

(deftest a-refused-batch-leaves-other-peoples-files-alone
  ;; an untracked draft and an unstaged edit, both named by the batch
  (let [r (room)]
    (files/write! r {"a.txt" {:text "committed"}} {})
    (mfs/write-string! (ws/room-fs r) "/draft.txt" "untracked draft" false)
    (mfs/write-string! (ws/room-fs r) "/a.txt" "unstaged edit" false)
    (effects/install-world! (:ctx r) [[:read-only]])
    (is (thrown? clojure.lang.ExceptionInfo
                 (files/write! r {"a.txt" {:text "x"} "draft.txt" {:text "y"} "z.txt" nil} {})))
    (is (= "unstaged edit" (worktree-text r "/a.txt")))
    (is (= "untracked draft" (worktree-text r "/draft.txt")))))

(deftest the-commit-is-decided-before-anything-is-written
  (let [r (room)
        before (commits r)
        boundary (constantly {:handlers [(fn [effect next]
                                           (if (= :git/commit (:effect effect))
                                             (effects/deny! effect :admission "no commits")
                                             (next effect)))]})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no commits"
                          (files/write! r {"a.txt" {:text "a"}} {:boundary boundary})))
    (is (nil? (worktree-text r "/a.txt")))
    (is (= before (commits r)))))

(deftest a-failure-after-staging-undoes-index-and-files
  (let [r (room)
        execute-git gs/execute-git]
    (files/write! r {"a.txt" {:text "old"} "gone.txt" {:text "keep me"}} {})
    (with-redefs [gs/execute-git (fn [ws argv]
                                   (if (= "commit" (first argv))
                                     {:exit 1 :stdout "" :stderr "boom"}
                                     (execute-git ws argv)))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"boom"
                            (files/write! r {"a.txt" {:text "new"} "b/c.txt" {:text "c"} "gone.txt" nil} {}))))
    (is (= "old" (worktree-text r "/a.txt")))
    (is (= "keep me" (worktree-text r "/gone.txt")))
    (is (not (mfs/exists? (ws/room-fs r) "/b")) "the directory it made is gone too")
    (is (empty? (forks/workspace-changes r)) "nothing staged, nothing dirty")))

(deftest a-staged-version-of-a-batch-path-refuses-the-batch
  ;; HEAD, index and worktree each hold another version: all are kept
  (let [r (room)]
    (files/write! r {"a.txt" {:text "head"}} {})
    (mfs/write-string! (ws/room-fs r) "/a.txt" "index" false)
    (binding [ec/*execution-context* (:ctx r)] (gs/execute-git ["add" "a.txt"]))
    (mfs/write-string! (ws/room-fs r) "/a.txt" "worktree" false)
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"staged changes"
                          (files/write! r {"a.txt" {:text "mine"}} {})))
    (is (= "worktree" (worktree-text r "/a.txt")))
    (is (= "index" (String. ^bytes (repo/read-entry (conn r) (get (repo/tree (conn r) :index) "a.txt")) "UTF-8")))
    (is (= "head" (String. ^bytes (repo/read-entry (conn r) (get (repo/tree (conn r) :head) "a.txt")) "UTF-8")))))

(deftest symbolic-links-are-not-followed
  (let [r (room)
        fs (ws/room-fs r)]
    (mfs/write-string! fs "/.env" "SECRET=1" false)
    (mfs/symlink fs ".env" "/public.txt")
    (mfs/symlink fs "public.txt" "/chained.txt")
    (doseq [p ["public.txt" "chained.txt"]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"symbolic link" (files/read-file r p {})) p)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"symbolic link" (files/write! r {p {:text "x"}} {})) p)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"symbolic link" (files/write! r {p nil} {})) p))
    (is (thrown? clojure.lang.ExceptionInfo (files/read-file r ".env" {})))
    (is (= "SECRET=1" (worktree-text r "/.env")))))

(deftest a-shell-git-waits-for-a-batch
  ;; the room shell's git builtin takes the same lock as a room_write batch
  (let [r (room)
        ws (binding [ec/*execution-context* (:ctx r)] (gs/current-workspace))
        host (bash/make-host {:workspace ws})
        git (get (:builtins host) "git")
        fs (:fs host)]
    (mfs/write-string! fs "/scratch.md" "x" false)
    (let [f (locking (gs/workspace-lock (:conn ws))
              (let [f (future (git ["git" "add" "scratch.md"] fs {:cwd "/"}))]
                (Thread/sleep 300)
                (is (not (realized? f)) "blocked while the batch holds the repository")
                f))]
      (is (= 0 (:exit (deref f 10000 {:exit :timeout}))))
      (is (= ["scratch.md"] (:staged (repo/status (conn r))))))))

(deftest staged-changes-of-others-refuse-the-batch
  (let [r (room)]
    (mfs/write-string! (ws/room-fs r) "/scratch.md" "staged by an agent" false)
    (binding [ec/*execution-context* (:ctx r)] (gs/execute-git ["add" "scratch.md"]))
    (let [e (try (files/write! r {"notes.md" {:text "mine"}} {}) nil
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (= ::files/staged-changes (:type (ex-data e))) (pr-str (ex-data e))))
    (is (not (mfs/exists? (ws/room-fs r) "/notes.md")))
    (is (not (contains? (repo/tree (conn r) :head) "scratch.md")))))

(deftest a-concurrent-writer-fails-the-batch-and-keeps-its-change
  (let [r (room)
        write! tools/workspace-write!]
    (with-redefs [tools/workspace-write! (fn [ctx p c]
                                           (let [res (write! ctx p c)]
                                             ;; an agent writes the same path right after
                                             (mfs/write-string! (ws/room-fs r) p "the agent's" false)
                                             res))]
      (let [e (try (files/write! r {"a.txt" {:text "mine"}} {}) nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (= :dvergr.rooms.forks/concurrent-change (:type (ex-data e))) (pr-str (ex-data e)))))
    (is (= "the agent's" (worktree-text r "/a.txt")) "not overwritten by the undo")
    (is (not (contains? (repo/tree (conn r) :head) "a.txt")) "nothing committed")))

(deftest text-reads-whole-characters
  (let [r (room)]
    (files/write! r {"e.txt" {:text "éé"}} {})
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"shorter than the character"
                          (files/read-file r "e.txt" {:length 1})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"inside a character"
                          (files/read-file r "e.txt" {:offset 1})))
    (is (= {:text "é" :next-offset 2} (select-keys (files/read-file r "e.txt" {:length 3}) [:text :next-offset])))
    (is (= "é" (:text (files/read-file r "e.txt" {:offset 2}))))))

(deftest a-full-chunk-fits-an-mcp-result
  (let [r (room)
        n files/max-read-bytes]
    (files/write! r {"quotes.txt" {:text (apply str (repeat n "\""))}
                     "ctl.txt" {:text (apply str (repeat n "\u0001"))}
                     "bin.dat" {:base64 (b64 (byte-array n (unchecked-byte 0xff)))}} {})
    (testing "control characters read as base64, not six-fold escaped text"
      (is (:base64 (files/read-file r "ctl.txt" {}))))
    (doseq [p ["quotes.txt" "ctl.txt" "bin.dat"]]
      (is (:structuredContent (surface/data-result (files/read-file r p {})))
          (str p ": the whole chunk, not a cut result")))))

(deftest a-large-directory-lists-in-pages
  (let [r (room)]
    (doseq [part (partition-all 150 (range 205))]   ; at most 200 paths per batch
      (files/write! r (into {} (for [i part] [(format "many/f%03d.txt" i) {:text (str i)}])) {}))
    (let [a (files/list-dir r "/many" {})
          b (files/list-dir r "/many" {:offset (:next-offset a)})]
      (is (= [205 200 200] [(:total a) (count (:entries a)) (:next-offset a)]))
      (is (= 5 (count (:entries b))))
      (is (nil? (:next-offset b)))
      (is (= 205 (count (distinct (map :path (concat (:entries a) (:entries b))))))))))

(deftest a-page-of-long-names-fits-an-mcp-result
  (let [r (room)
        names (for [i (range 200)] (str (apply str (repeat 190 "n")) (format "%03d.txt" i)))]
    (files/write! r (into {} (for [n names] [(str "long/" n) {:text "x"}])) {})
    (let [pages (loop [offset 0 acc []]
                  (let [pg (files/list-dir r "/long" {:offset offset})]
                    (is (:structuredContent (surface/data-result pg)) "a whole page, not a cut result")
                    (if-let [n (:next-offset pg)] (recur n (conj acc pg)) (conj acc pg))))]
      (is (< 1 (count pages)))
      (is (= (set names) (set (mapcat #(map :name (:entries %)) pages)))))))

(deftest blank-segments-are-refused-as-geschichte-would-drop-them
  (let [r (room)]
    (mfs/write-string! (ws/room-fs r) "/.env" "SECRET=1" false)
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"blank" (files/read-file r ".env/ " {})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"blank" (files/write! r {"a/ /b" {:text "x"}} {})))
    (is (not (mfs/exists? (ws/room-fs r) "/a")))))

(deftest a-batch-cannot-write-a-file-and-beneath-it
  (let [r (room)
        before (commits r)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"also writes /a as a file"
                          (files/write! r {"a" {:text "x"} "a/b" {:text "y"}} {})))
    (is (not (mfs/exists? (ws/room-fs r) "/a")))
    (is (= before (commits r)))))

(deftest the-undo-keeps-a-write-made-while-it-runs
  (let [r (room)
        execute-git gs/execute-git
        restore-paths! repo/restore-paths!]
    (files/write! r {"a.txt" {:text "old"}} {})
    (with-redefs [gs/execute-git (fn [ws argv]
                                   (if (= "commit" (first argv))
                                     {:exit 1 :stdout "" :stderr "boom"}
                                     (execute-git ws argv)))
                  ;; the undo's first step; an agent writes right after it
                  repo/restore-paths! (fn [& args]
                                        (let [res (apply restore-paths! args)]
                                          (mfs/write-string! (ws/room-fs r) "/a.txt" "the agent's" false)
                                          (mfs/write-string! (ws/room-fs r) "/new.txt" "the agent's too" false)
                                          res))]
      (is (thrown? clojure.lang.ExceptionInfo
                   (files/write! r {"a.txt" {:text "mine"} "new.txt" {:text "mine"}} {}))))
    (is (= "the agent's" (worktree-text r "/a.txt")))
    (is (= "the agent's too" (worktree-text r "/new.txt")) "not deleted as the batch's own")))
