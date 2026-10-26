(ns dvergr.catalog.context-fixture-test
  (:require [clojure.test :refer [deftest is]]
            [datahike.api :as db]
            [dvergr.agent.checkpoint-admission-test :as fixture]
            [dvergr.agent.checkpoint-prediction-test :as prediction]
            [dvergr.agent.evaluation :as evaluation]
            [dvergr.catalog.workspace :as catalog]
            [dvergr.substrate.geschichte :as workspace]
            [geschichte.repo :as repo]
            [geschichte.bytes :as bytes]
            [geschichte.query :as query]
            [muschel.fs :as fs]
            [muschel.fs.geschichte :as primary]))

(def limits {:max-files 10 :max-file-bytes 1024 :max-bytes 4096})

(defn- error [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (or (:reason (ex-data e)) (:type (ex-data e)) :exception))))

(defn- with-child [f]
  (@#'fixture/with-room-workspace
   (fn [room conn]
     (@#'fixture/commit! conn "C0")
     (let [branches (db/branches conn)
           result (evaluation/with-checkpoint-fixture! room room nil (@#'prediction/checkpoint conn)
                    #(f (:conn (workspace/current-workspace)) conn))]
       (is (= branches (db/branches conn)))
       (is (= "C0" (fs/read-file (workspace/filesystem) "/state.md")))
       (is (false? (fs/exists? (workspace/filesystem) "/wiki/Company.md")))
       result))))

(deftest bounded-primary-seeding-is-readable-and-repeated-input-is-a-logical-no-op
  (with-child
    (fn [child parent]
      (let [files {"/wiki/Company.md" "# Company €\n" "/helpers/code.clj" "(+ 1 2)" "/helpers/empty.txt" ""}
           ;; An ambient mount pointing at the parent must never redirect the
           ;; fixed primary-repository initializer.
            parent-fs (primary/make parent)
            result (with-redefs [workspace/filesystem (fn [& _] parent-fs)]
                     (catalog/seed-in-context! files limits))
            head (:head (repo/status child))
            repeated (catalog/seed-in-context! files limits)]
        (is (= 3 (:files result)))
        (is (:changed? result))
        (is (= (set (keys files)) (set (keys (:manifest result)))))
        (is (= "# Company €\n" (fs/read-file (workspace/filesystem) "/wiki/Company.md")))
        (is (= "" (fs/read-file (workspace/filesystem) "/helpers/empty.txt")))
        (is (:clean? (repo/status child)))
        (is (false? (:changed? repeated)))
        (is (= (:manifest result) (:manifest repeated)))
        (is (= head (:head (repo/status child))))
        (is (false? (fs/exists? parent-fs "/helpers/code.clj")))
        :ok))))

(deftest empty-input-is-read-only-and-dirty-input-is-never-committed
  (with-child
    (fn [child _]
      (let [before (db/commit-id @child)
            result (catalog/seed-in-context! {} limits)]
        (is (= {:files 0 :bytes 0 :changed? false :manifest {}} result))
        (is (= before (db/commit-id @child))))
      (fs/write-string! (workspace/filesystem) "/unrelated.md" "Do not commit" false)
      (let [before (db/commit-id @child)]
        (is (= :dirty-workspace (error #(catalog/seed-in-context! {} limits))))
        (is (= :dirty-workspace (error #(catalog/seed-in-context! {"/wiki/Company.md" "x"} limits))))
        (is (= before (db/commit-id @child)))
        (is (empty? (:staged (repo/status child)))))
      :ok)))

(deftest invalid-entire-input-refuses-before-any-mutation
  (with-child
    (fn [child _]
      (doseq [[files caps]
              [[{"/a-valid.md" "x" "relative.md" "bad"} limits]
               [{"/a-valid.md" "x" "/z/../bad.md" "bad"} limits]
               [{"/a-valid.md" "x" "//a-valid.md" "alias"} limits]
               [{"/a" "file" "/a/nested" "child"} limits]
               [{"/state.md/nested" "child"} limits]
               [{"/unicode.txt" "€"} (assoc limits :max-file-bytes 2)]
               [{"/a" "é" "/b" "é"} (assoc limits :max-bytes 3)]
               [{"/a" "x" "/b" "y"} (assoc limits :max-files 1)]
               [{"/a" "x"} (assoc limits :max-files 201)]
               [{"/a-valid.md" "x" "/bad.md" (str (char 0xd800))} limits]
               [{"/a-valid.md" "x" "/bad.md" (str (char 0xdc00))} limits]
               [{(str "/" (char 0xd800) ".md") "x"} limits]]]

        (let [before (db/commit-id @child)]
          (is (some? (error #(catalog/seed-in-context! files caps))))
          (is (= before (db/commit-id @child)))
          (is (:clean? (repo/status child)))))
      :ok)))

(deftest existing-executable-mode-is-preserved-with-new-and-repeated-bytes
  (with-child
    (fn [child _]
      (repo/write! child "exec.sh" (bytes/utf8 "before") {:mode 33261})
      (repo/stage-all! child)
      (repo/commit! child {:message "Executable baseline"})
      (let [result (catalog/seed-in-context! {"/exec.sh" "after"} limits)
            repeated (catalog/seed-in-context! {"/exec.sh" "after"} limits)]
        (is (= 33261 (get-in result [:manifest "/exec.sh" :mode])))
        (is (= 33261 (get-in (query/worktree @child) ["exec.sh" :mode])))
        (is (= "after" (fs/read-file (workspace/filesystem) "/exec.sh")))
        (is (false? (:changed? repeated))))
      :ok)))

(deftest an-unselected-context-refuses-without-reading-a-fallback-workspace
  (with-child
    (fn [child _]
      (let [before (db/commit-id @child)]
        (with-redefs [workspace/workspace-selected? (constantly false)
                      workspace/current-workspace (fn [] (throw (AssertionError. "No workspace fallback")))]
          (is (= :workspace-not-selected (error #(catalog/seed-in-context! {"/wiki/Company.md" "x"} limits)))))
        (is (= before (db/commit-id @child))))
      :ok)))
