(ns dvergr.discourse.commands-test
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.discourse.commands :as cmd]))

(deftest command-input-detection
  (is (cmd/command-input? "/help"))
  (is (cmd/command-input? "  /fork foo"))
  (is (not (cmd/command-input? "hello")))
  (is (not (cmd/command-input? "/ leading-space")))   ; "/" then space isn't a name
  (is (not (cmd/command-input? "http://x"))))

(deftest argv-parsing
  (is (= ["a" "b c" "d"] (cmd/parse-argv "a \"b c\" d")))
  (is (= ["x" "y z"]     (cmd/parse-argv "x 'y z'")))
  (is (= []              (cmd/parse-argv "")))
  (is (= []              (cmd/parse-argv nil))))

(deftest input-parsing
  (is (= {:name "fork" :suffix nil :args-str "my label" :argv ["my" "label"]}
         (cmd/parse-input "/fork my label")))
  (is (= "skill" (:name (cmd/parse-input "/skill:linear create an issue"))))
  (is (= "linear" (:suffix (cmd/parse-input "/skill:linear create an issue"))))
  (is (= "create an issue" (:args-str (cmd/parse-input "/skill:linear create an issue"))))
  (is (nil? (cmd/parse-input "not a command"))))

(deftest template-expansion
  (testing "positional + $@ + $ARGUMENTS"
    (is (= "first=a all=a b c" (cmd/expand-template "first=$1 all=$@" ["a" "b" "c"])))
    (is (= "x: a b c" (cmd/expand-template "x: $ARGUMENTS" ["a" "b" "c"])))
    (is (= "miss=" (cmd/expand-template "miss=$9" ["a"]))))
  (testing "slices ${@:N} and ${@:N:L}"
    (is (= "b c d" (cmd/expand-template "${@:2}" ["a" "b" "c" "d"])))
    (is (= "b c"   (cmd/expand-template "${@:2:2}" ["a" "b" "c" "d"]))))
  (testing "inline $(clojure) via sci-eval hook"
    (is (= "sum=3" (cmd/expand-template "sum=$(+ 1 2)" [] (fn [code] (eval (read-string code))))))
    (is (= "sum=$(+ 1 2)" (cmd/expand-template "sum=$(+ 1 2)" [])))))  ; no hook → left as-is

(deftest builtins-registered
  (let [names (set (map :name (cmd/all-commands {:available-tools ["clojure_eval"]})))]
    (is (contains? names "help"))
    (is (contains? names "fork"))
    (is (contains? names "worktree"))
    (is (contains? names "model"))
    (is (contains? names "plan"))))

(deftest match-builtins-for-hint
  (is (= ["fork"] (cmd/match-builtins "/fork")))
  (is (= ["build"] (cmd/match-builtins "/bu")))
  (is (contains? (set (cmd/match-builtins "/")) "help"))
  (is (nil? (cmd/match-builtins "/fork foo")))   ; arg started → no hint
  (is (nil? (cmd/match-builtins "hello"))))

(deftest execute-non-command-passes-through
  (is (= {:handled? false} (cmd/execute! "just a message" {}))))

(deftest execute-help-notifies
  (let [seen (atom nil)
        r (cmd/execute! "/help" {:available-tools []
                                 :notify! #(reset! seen %)})]
    (is (:handled? r))
    (is (:no-turn? r))
    (is (re-find #"(?s)Available commands" @seen))))

(deftest execute-unknown-command
  (let [r (cmd/execute! "/definitelynotacommand" {:available-tools []})]
    (is (:handled? r))
    (is (re-find #"Unknown command" (:reply r)))))

(deftest execute-plan-sets-room-mode
  (let [room {:id :test-room}
        notified (atom nil)
        r (cmd/execute! "/plan" {:available-tools [] :room room
                                 :notify! #(reset! notified %)})]
    (is (:no-turn? r))
    (is (= :plan (cmd/room-mode :test-room)))
    (cmd/execute! "/build" {:available-tools [] :room room :notify! identity})
    (is (= :build (cmd/room-mode :test-room)))))

(deftest all-commands-and-execute-see-room-scope-skills-with-room-dir
  (testing "a vetted room-scope skill is invisible to the registry without
            :room-dir, and participates (incl. via execute!) once it's supplied"
    (let [dir (java.io.File/createTempFile "cmd-room" "")
          _   (.delete dir)
          sk  (java.io.File. dir "skills")]
      (.mkdirs sk)
      (spit (java.io.File. sk "roomskill.md")
            (str "---\nkind: skill\nname: roomskill\nprovides: [:roomskill]\n"
                 "vetted: true\n---\nDo the room thing."))
      (try
        (let [without (set (map :name (cmd/all-commands {:available-tools []})))
              with    (set (map :name (cmd/all-commands {:available-tools []
                                                         :room-dir (.getPath dir)})))]
          (is (not (contains? without "skill:roomskill"))
              "without :room-dir, the room skill is invisible")
          (is (contains? with "skill:roomskill")
              "with :room-dir, the room skill appears as /skill:roomskill"))
        (let [posted (atom nil)
              r (cmd/execute! "/skill:roomskill go"
                              {:available-tools []
                               :room-dir (.getPath dir)
                               :post-user! #(reset! posted %)})]
          (is (:handled? r))
          (is (re-find #"Do the room thing" @posted)
              "execute! resolves the room-scope skill once ctx carries :room-dir"))
        (finally
          (.delete (java.io.File. sk "roomskill.md"))
          (.delete sk) (.delete dir))))))

(deftest execute-prompt-command-posts-user-message
  ;; register a throwaway :prompt-style builtin to exercise the template path
  (let [posted (atom nil)]
    (cmd/register! {:name "greet" :kind :builtin :description "t"
                    :run (fn [ctx]
                           ((:run (get @#'cmd/builtins "greet")) ctx))})
    ;; simpler: drive run-prompt! indirectly via a fake :prompt command in lookup
    (let [r (#'cmd/run-prompt! {:template "Hello $1 ($ARGUMENTS)"}
                               {:argv ["world" "again"]
                                :post-user! #(reset! posted %)})]
      (is (:handled? r))
      (is (not (:no-turn? r)))
      (is (= "Hello world (world again)" @posted)))
    (cmd/unregister! "greet")))
