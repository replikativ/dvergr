(ns dvergr.intake.workspace-delete-test
  "Recursive delete is allowed inside the workspace, refused on system paths.

   muschel denies `rm -r*` outright — correct for a library that cannot assume
   its embedder's workspace is recoverable. dvergr's IS: a geschichte-backed
   fork with history, behind a jailed FS. And the blanket deny bought nothing
   measurable, because `babashka.fs/delete-tree` performs the same recursive
   deletion through the sandbox's fs surface with no permit layer in front of
   it. Measured before this change, in one session:

     rm -rf scratch                    ;=> exit 126, \"permit denied: recursive delete\"
     (babashka.fs/delete-tree \"scratch\") ;=> deleted

   So the rule did not stop recursive deletion; it moved it off the audited
   shell onto an unaudited door. These tests pin the corrected posture — and
   the system-path refusal that must survive it, since the rules are
   last-match-wins and an over-broad allow would silently undo it."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [dvergr.sandbox :as sandbox]
            [dvergr.sandbox.ns.io :as ns-io]
            [org.replikativ.spindel.engine.core :as rtc]
            [org.replikativ.spindel.engine.context :as ctx]))

(defn- with-shell [f]
  (let [ec      (ctx/create-execution-context)
        sci-ctx (sandbox/fork-for-session ec)]
    (try
      (sandbox/setup-agent-namespaces! sci-ctx ec)
      (ns-io/add-bash-ns! sci-ctx {:spindel-ctx ec})
      (f sci-ctx ec)
      (finally (ctx/stop-context! ec)))))

(defn- ev [sci-ctx ec code]
  (binding [rtc/*execution-context* ec]
    (let [r (sandbox/eval-code sci-ctx code)]
      (if (:success r) (:value r) {:err (get-in r [:error :message])}))))

(defn- sh [sci-ctx ec cmd]
  (ev sci-ctx ec (str "(let [r (babashka.process/shell " (pr-str cmd) ")] "
                      "{:exit (:exit r) :err (str (:err r))})")))

(deftest workspace-local-recursive-delete-is-allowed
  (with-shell
    (fn [sci-ctx ec]
      (sh sci-ctx ec "mkdir -p wsdel/nested")
      (sh sci-ctx ec "sh -c 'echo hi > wsdel/nested/f.txt'")
      (is (true? (ev sci-ctx ec "(babashka.fs/exists? \"wsdel/nested/f.txt\")"))
          "fixture must exist before we try to delete it")

      (testing "rm -rf on a workspace-local folder now succeeds"
        (let [r (sh sci-ctx ec "rm -rf wsdel")]
          (is (zero? (:exit r))
              (str "expected the delete to be permitted, got: " (:err r)))))

      (testing "and the tree is actually gone"
        (is (false? (ev sci-ctx ec "(babashka.fs/exists? \"wsdel/nested/f.txt\")")))))))

(deftest system-paths-are-still-refused
  (with-shell
    (fn [sci-ctx ec]
      (testing "the allow must not have swallowed the critical-path deny"
        (doseq [target ["/" "/etc" "/home" "/usr" "/var" "/bin"]]
          (let [r (sh sci-ctx ec (str "rm -rf " target))]
            (is (not (zero? (:exit r)))
                (str "rm -rf " target " must stay refused")))))

      (testing "…in every flag arrangement, not just the one shape we thought of"
        ;; muschel's own critical-path rule was an :argv-shape with `:**` in the
        ;; MIDDLE, which never matches — so it only ever appeared to work because
        ;; the blanket recursive deny sat in front of it. These arrangements are
        ;; what a position-sensitive rule gets wrong.
        (doseq [cmd ["rm -rf /etc"
                     "rm -r -f /etc"
                     "rm -r --force /etc"
                     "rm --recursive /etc"
                     "rm -fr /etc"
                     "rm -v -r -f /etc"
                     "rm /etc"]]
          (let [r (sh sci-ctx ec cmd)]
            (is (not (zero? (:exit r)))
                (str cmd " must stay refused"))))))))

(deftest non-recursive-delete-is-unaffected
  (with-shell
    (fn [sci-ctx ec]
      (sh sci-ctx ec "sh -c 'mkdir -p wsdel2 && echo x > wsdel2/a.txt'")
      (let [r (sh sci-ctx ec "rm wsdel2/a.txt")]
        (is (zero? (:exit r))))
      (is (false? (ev sci-ctx ec "(babashka.fs/exists? \"wsdel2/a.txt\")"))))))
