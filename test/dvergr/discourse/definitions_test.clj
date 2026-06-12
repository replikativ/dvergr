(ns dvergr.discourse.definitions-test
  "Unit tests for the unified file-driven definition loader (skills + agents)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [dvergr.discourse.definitions :as defs]))

(deftest parse-frontmatter-basics
  (testing "frontmatter is parsed and the body is returned separately"
    (let [p (defs/parse-frontmatter
              (str "---\n"
                   "kind: agent\n"
                   "name: scout\n"
                   "provides: [:research, :triage]\n"
                   "tools: [clojure_eval, web_fetch]\n"
                   "autostart: true\n"
                   "rooms: [boardroom, \"*\"]\n"
                   "vetted: false\n"
                   "---\n"
                   "# Scout\n\nbody text here"))]
      (is (= :agent (:kind p)))
      (is (= "scout" (:name p)))
      (is (= [:research :triage] (:provides p)) "provides coerced to keywords")
      (is (= ["clojure_eval" "web_fetch"] (:tools p)) "tools kept as strings")
      (is (true? (:autostart p)))
      (is (= ["boardroom" "*"] (:rooms p)))
      (is (false? (:vetted p)))
      (is (= "# Scout\n\nbody text here" (:content p)))
      (is (not (str/starts-with? (:content p) "---")) "body has no frontmatter"))))

(deftest parse-frontmatter-no-frontmatter
  (testing "a file with no frontmatter yields the whole text as :content"
    (let [p (defs/parse-frontmatter "# Just A Body\n\nno frontmatter")]
      (is (= "# Just A Body\n\nno frontmatter" (:content p)))
      (is (nil? (:kind p))))))

(deftest load-agents-have-frontmatter-and-clean-bodies
  (testing "the 9 builtin agent definitions parse as :agent with stripped bodies"
    (let [ags (defs/load-kind "agents")]
      (is (<= 9 (count ags)) "at least the 9 builtin agents")
      (doseq [id ["coder" "researcher" "reviewer" "var" "worker"
                  "developer" "mimir" "ops" "planner"]]
        (let [a (get ags id)]
          (is (some? a) (str id " is loaded"))
          (is (= :agent (:kind a)) (str id " kind is :agent"))
          (is (seq (:provides a)) (str id " declares provides"))
          (is (not (str/starts-with? (:content a) "---"))
              (str id " body is frontmatter-stripped"))
          (is (= :builtin (:scope a)) (str id " scope is :builtin")))))))

(deftest load-skills-still-work
  (testing "skills load through the same shared loader"
    (let [sks (defs/load-kind "skills")]
      (is (pos? (count sks)))
      (when-let [slack (get sks "slack")]
        (is (= [:chat-bridge :slack] (:provides slack)))
        (is (= ["clojure_eval"] (:requires-tools slack)))))))

(deftest body-and-load-one
  (testing "load-one + body resolve a single definition's parsed map / body"
    (is (= :agent (:kind (defs/load-one "agents" "coder"))))
    (is (str/starts-with? (defs/body "agents" "coder") "# Coder"))
    (is (nil? (defs/load-one "agents" "does-not-exist")))))

(deftest agent->config-maps-frontmatter
  (testing "an :agent definition maps to a daemon agent-config"
    (let [c (defs/agent->config
              {:name "scout" :provider "fireworks" :model "m"
               :tools ["clojure_eval"] :provides [:scouting]
               :rooms ["boardroom"] :autostart true :vetted true
               :description "d"})]
      (is (= :scout (:id c)))
      (is (= :fireworks (:provider c)))
      (is (= ["clojure_eval"] (:tools c)))
      (is (= #{:scouting} (:tags c)) "provides → :tags")
      (is (= #{:scouting} (:skills c)) "provides → :skills")
      (is (= ["boardroom"] (:rooms c)))
      (is (true? (:autostart c)))
      (is (true? (:vetted c))))))

(deftest autostart-agents-gates-on-flags
  (testing "autostart-agents returns only autostart+vetted agents from a scope"
    (let [dir (java.io.File/createTempFile "defs-as" "")
          _   (.delete dir)
          ag  (java.io.File. dir "agents")]
      (.mkdirs ag)
      (spit (java.io.File. ag "yes.md")
            "---\nkind: agent\nname: yes\nautostart: true\nvetted: true\nprovides: [:x]\n---\nb")
      (spit (java.io.File. ag "unvetted.md")
            "---\nkind: agent\nname: unvetted\nautostart: true\nvetted: false\n---\nb")
      (spit (java.io.File. ag "manual.md")
            "---\nkind: agent\nname: manual\nautostart: false\nvetted: true\n---\nb")
      (try
        (let [ids (set (map first (defs/autostart-agents (.getPath dir))))]
          (is (contains? ids :yes) "autostart+vetted included")
          (is (not (contains? ids :unvetted)) "unvetted excluded (vetting gate)")
          (is (not (contains? ids :manual)) "autostart:false excluded"))
        (finally
          (doseq [n ["yes.md" "unvetted.md" "manual.md"]] (.delete (java.io.File. ag n)))
          (.delete ag) (.delete dir))))))

(deftest author-then-promote-roundtrip
  (testing "author! writes a vetted:false definition; promote! flips it vetted
            with reviewer + date — surviving a parse round-trip"
    (let [dir (java.io.File/createTempFile "defs-auth" "")
          _   (.delete dir)]
      (try
        (let [path (defs/author! "skills" (.getPath dir) "demo"
                     {:description "d" :provides [:demo]
                      :requires-tools ["clojure_eval"]}
                     "the body")
              s1   (get (defs/load-kind "skills" (.getPath dir)) "demo")]
          (is (= false (:vetted s1)) "agent-authored defaults to vetted:false")
          (is (= [:demo] (:provides s1)))
          (is (= ["clojure_eval"] (:requires-tools s1)))
          (is (= "the body" (:content s1)) "body survives, frontmatter stripped")
          (defs/promote! path "ch_weil" "2026-06-12")
          (let [s2 (get (defs/load-kind "skills" (.getPath dir)) "demo")]
            (is (= true (:vetted s2)) "promote! flips vetted true")
            (is (= "ch_weil" (:vetted-by s2)))
            (is (= "2026-06-12" (:vetted-at s2)))))
        (finally
          (let [sk (java.io.File. dir "skills")]
            (.delete (java.io.File. sk "demo.md")) (.delete sk) (.delete dir)))))))

(deftest room-scope-overlays-globals
  (testing "a room-dir adds the room's own definitions as the highest-precedence
            :room scope; without it they are invisible"
    (let [dir (java.io.File/createTempFile "defs-room" "")
          _   (.delete dir)
          sk  (java.io.File. dir "skills")]
      (.mkdirs sk)
      (spit (java.io.File. sk "roomonly.md")
            (str "---\nkind: skill\nname: roomonly\nprovides: [:roomonly]\n"
                 "requires_tools: [clojure_eval]\nvetted: true\n---\nbody"))
      (try
        (let [global (defs/load-kind "skills")
              scoped (defs/load-kind "skills" (.getPath dir))]
          (is (not (contains? global "roomonly")) "global excludes the room skill")
          (is (contains? scoped "roomonly") "room scope includes it")
          (is (= :room (:scope (get scoped "roomonly")))))
        (finally
          (.delete (java.io.File. sk "roomonly.md"))
          (.delete sk) (.delete dir))))))
