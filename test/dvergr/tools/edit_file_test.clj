(ns dvergr.tools.edit-file-test
  "`edit_file` must be able to change EVERY occurrence, not only a unique one.

   The tool did `str/replace-first` and REFUSED any input matching more than
   once. That default is right — it makes it impossible to change the wrong
   occurrence by accident — but with no way to opt out, renaming a local that
   appears five times meant five calls, each carrying hand-built surrounding
   context to force uniqueness. That is the single biggest reason a coding agent
   abandons the edit tool and reaches for a script (or a second runtime) instead,
   which is exactly what the sandbox is trying not to need.

   `replace_all` opts out explicitly. These tests pin both halves: the safety
   default still refuses an ambiguous edit AND says how to proceed, and the
   opt-in changes all of them and reports how many."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [dvergr.tools :as tools])
  (:import [java.io File]))

(defn- edit-tool []
  (first (filter #(= "edit_file" (:name %)) (vals @@#'tools/registry))))

(defn- with-temp-file
  "Run `(f dir relative-name)` against a real file holding `content`."
  [content f]
  (let [dir  (File. (str (System/getProperty "java.io.tmpdir")
                         "/dvergr-edit-file-test-"
                         (System/nanoTime)))
        _    (.mkdirs dir)
        name "target.txt"
        file (File. dir name)]
    (try
      (spit file content)
      (f dir name file)
      (finally
        (when (.exists file) (.delete file))
        (.delete dir)))))

(deftest ambiguous-edit-is-refused-by-default
  (with-temp-file
    "alpha\nbeta\nalpha\n"
    (fn [dir name file]
      (let [r ((:execute (edit-tool))
               {:path name :old_string "alpha" :new_string "OMEGA"}
               {:cwd (str dir)})]
        (testing "a non-unique match is still refused"
          (is (= :error (:type r)))
          (is (str/includes? (str (:error r)) "must be unique")))
        (testing "and the refusal now names the way forward"
          (is (str/includes? (str (:suggestion r)) "replace_all")
              "an agent that cannot see the opt-out will go write a script instead"))
        (testing "the file is untouched"
          (is (= "alpha\nbeta\nalpha\n" (slurp file))))))))

(deftest replace-all-changes-every-occurrence
  (with-temp-file
    "alpha\nbeta\nalpha\n"
    (fn [dir name file]
      (let [r ((:execute (edit-tool))
               {:path name :old_string "alpha" :new_string "OMEGA" :replace_all true}
               {:cwd (str dir)})]
        (is (= :success (:type r)))
        (is (= "OMEGA\nbeta\nOMEGA\n" (slurp file)))
        (testing "and it reports how many it changed"
          (is (= 2 (:replacements (:metadata r))))
          (is (str/includes? (str (:content r)) "2 occurrences")))))))

(deftest unique-edit-still-works-unchanged
  (with-temp-file
    "alpha\nbeta\ngamma\n"
    (fn [dir name file]
      (let [r ((:execute (edit-tool))
               {:path name :old_string "beta" :new_string "BETA"}
               {:cwd (str dir)})]
        (is (= :success (:type r)))
        (is (= "alpha\nBETA\ngamma\n" (slurp file)))
        (is (= 1 (:replacements (:metadata r))))))))

(deftest replace-all-on-a-single-occurrence-is-fine
  (with-temp-file
    "alpha\nbeta\n"
    (fn [dir name file]
      (let [r ((:execute (edit-tool))
               {:path name :old_string "beta" :new_string "BETA" :replace_all true}
               {:cwd (str dir)})]
        (is (= :success (:type r)))
        (is (= "alpha\nBETA\n" (slurp file)))
        (is (= 1 (:replacements (:metadata r))))))))

(deftest a-string-that-is-absent-still-errors
  (with-temp-file
    "alpha\n"
    (fn [dir name file]
      (let [r ((:execute (edit-tool))
               {:path name :old_string "nowhere" :new_string "x" :replace_all true}
               {:cwd (str dir)})]
        (is (= :error (:type r)))
        (is (str/includes? (str (:error r)) "not found"))
        (is (= "alpha\n" (slurp file)))))))
