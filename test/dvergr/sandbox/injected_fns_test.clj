(ns dvergr.sandbox.injected-fns-test
  "Regression tests for behavior of individual fns injected into the agent SCI
   sandbox: room fork/merge/discard/participants, git/log, codec entity
   decoding, env/keys, skills authoring/promotion/find, scheduler/create."
  (:require [clojure.java.io :as jio]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dvergr.discourse :as d]
            [dvergr.room.registry :as rreg]
            [dvergr.room.store.memory :as memory]
            [dvergr.sandbox.ns.agent :as agent-ns]
            [dvergr.sandbox.ns.codec :as codec]
            [dvergr.sandbox.ns.io :as io]
            [dvergr.sandbox.ns.kb :as kb-ns]
            [dvergr.sandbox.workspace :as workspace]
            [muschel.fs.geschichte :as gfs]
            [org.replikativ.spindel.engine.core :as ec]
            [sci.core :as sci]))

(defn- temp-dir [prefix]
  (.toFile (java.nio.file.Files/createTempDirectory
            prefix (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- delete-tree! [^java.io.File f]
  (when (.isDirectory f)
    (doseq [c (.listFiles f)] (delete-tree! c)))
  (.delete f))

;; ---------------------------------------------------------------------------
;; dvergr.room — fork!/merge!/discard!/participants
;; ---------------------------------------------------------------------------

(deftest room-fork-defaults-to-isolated-and-ops-take-refs
  (let [parent (d/make-room {:id :sci-injected-fork-parent
                             :store (memory/make)})
        ops    (kb-ns/room-ops-map (:ctx parent) nil
                                   (select-keys parent [:id :incarnation]))
        fork!  ('fork! ops)
        forks  (atom [])]
    (try
      (testing "fork! with no opts is ISOLATED: its own ctx, writes stay in it"
        (let [fork (fork! parent)]
          (swap! forks conj fork)
          (is (some? fork))
          (is (not (identical? (:ctx fork) (:ctx parent))))
          (binding [ec/*execution-context* (:ctx fork)]
            (ec/swap-state! [::probe] (constantly :fork-only)))
          (is (= :fork-only (binding [ec/*execution-context* (:ctx fork)]
                              (ec/get-state [::probe]))))
          (is (nil? (binding [ec/*execution-context* (:ctx parent)]
                      (ec/get-state [::probe])))
              "a write in the fork's state is invisible in the parent")
          (testing "participants and discard! accept a slug"
            (is (vector? (('participants ops) (:slug fork))))
            (is (= (:id fork) (:id (('discard! ops) (:slug fork)))))
            (is (nil? (binding [ec/*execution-context* (:ctx parent)]
                        (rreg/lookup (:id fork))))
                "discarded fork left the registry"))))
      (testing "explicit opts still win"
        (let [shared (fork! parent {:isolation :none})]
          (swap! forks conj shared)
          (is (identical? (:ctx shared) (:ctx parent)))))
      (testing "merge! accepts refs"
        (let [fork (fork! parent)]
          (swap! forks conj fork)
          (is (= (:id parent)
                 (:id (('merge! ops) (:slug parent) (:slug fork))))
              "merge! returns the parent")
          (is (nil? (binding [ec/*execution-context* (:ctx parent)]
                      (rreg/lookup (:id fork))))
              "merged fork left the registry")))
      (testing "unknown refs are reported, not NPEs"
        (is (:error (('discard! ops) "no-such-room/fork-x")))
        (is (:error (('merge! ops) (:slug parent) "no-such-room/fork-x")))
        (is (nil? (('participants ops) "no-such-room"))))
      (finally
        (doseq [f @forks] (try (d/discard f) (catch Throwable _)))
        (d/close-room! parent)))))

;; ---------------------------------------------------------------------------
;; git/log
;; ---------------------------------------------------------------------------

(def ^:private pipe-subject "fix: a | b | c")

(deftest git-log-survives-pipes-in-subjects
  (testing "real git"
    (let [dir (temp-dir "dvergr-gitlog")
          sh  #(apply shell/sh "git" "-C" (str dir) %&)]
      (try
        (sh "init" "-q" "-b" "main")
        (spit (jio/file dir "a.txt") "a")
        (sh "add" "a.txt")
        (sh "-c" "user.email=t@t" "-c" "user.name=Pipe | Author"
            "commit" "-q" "-m" pipe-subject)
        (let [ctx (sci/init {})]
          (io/add-git-ns! ctx :base-path (str dir))
          (let [[entry :as log] (sci/eval-string* ctx "(git/log {:n 5})")]
            (is (= 1 (count log)))
            (is (= pipe-subject (:message entry)))
            (is (= "Pipe | Author" (:author entry)))
            (is (re-matches #"[0-9a-f]{40}" (:hash entry)))
            (is (re-find #"^\d{4}-\d{2}-\d{2}" (:date entry)))))
        (finally (delete-tree! dir)))))
  (testing "virtual Geschichte workspace"
    (let [{:keys [conn close!] :as repository}
          (gfs/memory-repository! {:name "gitlog-pipes"})
          workspace {:conn conn :id [(get-in @conn [:config :store :id]) :db]
                     :repository repository}
          ctx (sci/init {})]
      (try
        (io/add-fs-ns! ctx :filesystem (gfs/make-root repository))
        (io/add-git-ns! ctx :workspace workspace)
        (sci/eval-string* ctx "(spit \"a.txt\" \"a\")")
        (sci/eval-string* ctx "(git/add \".\")")
        (sci/eval-string* ctx (pr-str (list 'git/commit pipe-subject)))
        (let [log (sci/eval-string* ctx "(git/log {:n 5})")]
          (is (some #(= pipe-subject (:message %)) log) (pr-str log)))
        (finally (close!))))))

;; ---------------------------------------------------------------------------
;; dvergr.codec/decode-entities
;; ---------------------------------------------------------------------------

(deftest decode-entities-decodes-once-and-handles-astral-code-points
  (let [ctx (sci/init {})]
    (codec/add-codec-namespaces! ctx)
    (let [dec #(sci/eval-string* ctx (str "(dvergr.codec/decode-entities " (pr-str %) ")"))]
      (is (= "&lt;" (dec "&amp;lt;")) "decoded once, not twice")
      (is (= "&#60;" (dec "&amp;#60;")))
      (is (= "<a & b>" (dec "&lt;a &amp; b&gt;")))
      (is (= "😀" (dec "&#128512;")) "decimal emoji (above U+FFFF)")
      (is (= "😀" (dec "&#x1F600;")) "hex emoji")
      (is (= "it's" (dec "it&#39;s")))
      (is (= "&bogus; &#99999999;" (dec "&bogus; &#99999999;"))
          "unknown / out-of-range entities are left as written")
      (is (nil? (dec nil))))))

;; ---------------------------------------------------------------------------
;; env/keys ↔ env/get
;; ---------------------------------------------------------------------------

(deftest env-keys-lists-names-env-get-resolves
  (let [ctx (sci/init {})]
    (io/add-env-ns! ctx :user-config (atom {:foo "kw" :ns/bar "nskw" "PLAIN" "str"}))
    (let [ks (sci/eval-string* ctx "(env/keys)")]
      (is (= #{"foo" "ns/bar" "PLAIN"} (set ks)))
      (is (= {"foo" "kw" "ns/bar" "nskw" "PLAIN" "str"}
             (into {} (for [k ks]
                        [k (sci/eval-string* ctx (str "(env/get " (pr-str k) ")"))])))
          "every listed key resolves through env/get"))))

;; ---------------------------------------------------------------------------
;; dvergr.skills
;; ---------------------------------------------------------------------------

(defn- skills-ctx []
  (doto (sci/init {}) (agent-ns/add-skills-ns! nil)))

(deftest skills-author-find-and-promote-on-disk
  (let [dir (temp-dir "dvergr-skills")
        ctx (skills-ctx)
        ev  #(sci/eval-string* ctx %)]
    (try
      (binding [workspace/*workspace-dir* (str dir)]
        (let [path (ev "(dvergr.skills/author! \"room-demo\" {:description \"d\" :provides [:room-only-tag]} \"body\")")]
          (is (.exists (jio/file path)))
          (is (str/starts-with? (.getCanonicalPath (jio/file path))
                                (.getCanonicalPath dir))))
        (testing "find includes the room's own skills"
          (is (= ["room-demo"]
                 (mapv :name (ev "(dvergr.skills/find :room-only-tag)")))))
        (testing "promote! works for a skill on the real filesystem"
          (is (false? (:vetted (get (ev "(dvergr.skills/all)") "room-demo"))))
          (is (true? (ev "(dvergr.skills/promote! \"room-demo\" \"reviewer\" \"2026-09-19\")")))
          (let [s (get (ev "(dvergr.skills/all)") "room-demo")]
            (is (true? (:vetted s)))
            (is (= "reviewer" (:vetted-by s)))))
        (testing "lift! writes an unvetted room skill"
          (ev "(dvergr.skills/lift! \"lifted-demo\" \"https://example.org\" \"b\")")
          (let [s (get (ev "(dvergr.skills/all)") "lifted-demo")]
            (is (= :room (:scope s)))
            (is (false? (:vetted s)))))
        (testing "promote! of an unknown skill throws"
          (is (thrown-with-msg? Exception #"no such skill"
                                (ev "(dvergr.skills/promote! \"nope-nope\" \"r\" \"d\")")))))
      (finally (delete-tree! dir)))))

(deftest skills-writes-need-a-room-repo
  (let [ctx (skills-ctx)]
    (binding [workspace/*workspace-dir* nil]
      (doseq [form ["(dvergr.skills/author! \"x\" {} \"b\")"
                    "(dvergr.skills/lift! \"x\" \"src\" \"b\")"
                    "(dvergr.skills/promote! \"x\" \"r\" \"d\")"]]
        (is (thrown-with-msg? Exception #"needs a room sandbox repo"
                              (sci/eval-string* ctx form))
            form)))))

;; ---------------------------------------------------------------------------
;; dvergr.scheduler/create
;; ---------------------------------------------------------------------------

(deftest scheduler-create-rejects-unknown-top-level-keys
  (let [ctx (doto (sci/init {}) (agent-ns/add-scheduler-ns!))]
    (is (thrown-with-msg?
         Exception #"unknown key\(s\) :intervall-ms"
         (sci/eval-string*
          ctx "(dvergr.scheduler/create {:agent-id :a :task \"t\" :intervall-ms 1000})")))
    (testing "a well-formed cfg gets past the key check (then needs a room)"
      (is (thrown-with-msg?
           Exception #"No current room"
           (sci/eval-string*
            ctx "(dvergr.scheduler/create {:agent-id :a :task \"t\" :interval-ms 1000})"))))))
