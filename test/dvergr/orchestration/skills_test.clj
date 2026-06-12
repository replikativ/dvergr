(ns dvergr.orchestration.skills-test
  "Tests for dvergr.orchestration.skills — parsing, eligibility, dispatch."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [datahike.api :as dh]
            [dvergr.actors :as actors]
            [dvergr.chat.schema :as schema]
            [dvergr.orchestration.skills :as skills]))

;; ============================================================================
;; Fixture
;; ============================================================================

(def ^:dynamic *conn* nil)

(defn- mem-db-fixture [f]
  (let [cfg {:store {:backend :memory :id (random-uuid)}
             :keep-history? false
             :schema-flexibility :write}]
    (dh/create-database cfg)
    (let [conn (dh/connect cfg)]
      (schema/install-schema! conn)
      (binding [*conn* conn]
        (try (f)
             (finally (dh/release conn) (dh/delete-database cfg)))))))

(use-fixtures :each mem-db-fixture)

;; ============================================================================
;; Frontmatter parser
;; ============================================================================

(deftest parses-extended-frontmatter
  (testing "provides + vetted + source coerced to right types"
    (let [fm "---
name: example
description: a test skill
provides: [:research, :prose]
requires_tools: [tool_a, tool_b]
vetted: true
vetted_at: 2026-01-01
source: dvergr
---

body content here"
          parsed (skills/parse-skill-frontmatter fm)]
      (is (= "example" (:name parsed)))
      (is (= [:research :prose] (:provides parsed)))
      (is (= ["tool_a" "tool_b"] (:requires-tools parsed)))
      (is (true? (:vetted parsed)))
      (is (= "2026-01-01" (:vetted-at parsed)))
      (is (= "dvergr" (:source parsed)))
      (is (= "body content here" (:content parsed))))))

(deftest parses-list-style-requires
  (testing "  - foo  style lists still parse"
    (let [fm "---
name: x
provides: [:a]
requires_tools:
  - tool_a
  - tool_b
---

content"
          parsed (skills/parse-skill-frontmatter fm)]
      (is (= ["tool_a" "tool_b"] (:requires-tools parsed)))
      (is (= [:a] (:provides parsed))))))

;; ============================================================================
;; Eligibility
;; ============================================================================

(deftest eligible?-matches-when-tools-present
  ;; Eligibility now also requires :vetted (the vetting gate) — vetted skills
  ;; with satisfied tools are eligible.
  (is (skills/eligible? {:vetted true :requires-tools ["a" "b"]} ["a" "b" "c"]))
  (is (not (skills/eligible? {:vetted true :requires-tools ["a" "missing"]} ["a"])))
  (is (skills/eligible? {:vetted true} [])
      "vetted + no tool requirements = eligible"))

(deftest eligible?-gates-on-vetting
  ;; An unvetted skill (or one missing the field) is NOT eligible even when its
  ;; tools/env are satisfied — it stays loadable/listable, just not auto-injected.
  (is (not (skills/eligible? {:requires-tools []} []))
      "missing :vetted → not eligible")
  (is (not (skills/eligible? {:vetted false :requires-tools []} []))
      "vetted:false → not eligible"))

(deftest eligible?-respects-env
  ;; Env vars vary by machine; assert behavior with a known-missing one
  (is (not (skills/eligible? {:vetted true :requires-env ["DEFINITELY_NOT_A_REAL_ENV_VAR_FOR_TESTS"]}
                             ["any-tool"]))))

;; ============================================================================
;; Priority + ranking
;; ============================================================================

(deftest priority-uses-kind-default
  (is (= 100 (skills/priority-for {:kind :agent}   :anything)))
  (is (= 50  (skills/priority-for {:kind :external} :anything)))
  (is (= 10  (skills/priority-for {:kind :human}   :anything)))
  (is (= 0   (skills/priority-for {:kind :service} :anything))))

(deftest priority-override-beats-kind
  (let [actor {:kind :agent :skill-priorities {:research 1000}}]
    (is (= 1000 (skills/priority-for actor :research)))
    (is (= 100  (skills/priority-for actor :unrelated)))))

(deftest rank-providers-orders-by-priority-then-recency
  ;; Three actors all provide :research:
  ;;   :early    — agent, kind-default 100, created earliest
  ;;   :late     — agent, kind-default 100, created latest
  ;;   :pinned   — human, but :skill-priorities {:research 500}
  (let [t0 (java.util.Date. 1700000000000)
        t1 (java.util.Date. 1700001000000)
        t2 (java.util.Date. 1700002000000)]
    (actors/spawn-agent! *conn* {:id :early  :skills #{:research} :created-at t0})
    (actors/spawn-agent! *conn* {:id :late   :skills #{:research} :created-at t1})
    (actors/spawn-agent! *conn* {:id :pinned
                                 :skills #{:research}
                                 :created-at t2
                                 :skill-priorities {:research 500}})
    (let [ranked (skills/rank-providers *conn* :research)]
      (is (= [:pinned :early :late] (mapv :id ranked))
          "pinned (priority 500) > early (priority 100, oldest) > late"))))

(deftest rank-providers-filters-offline
  (actors/spawn-agent! *conn* {:id :a :skills #{:x}})
  (actors/spawn-agent! *conn* {:id :b :skills #{:x}})
  (actors/dismiss! *conn* :a)
  (is (= [:b] (mapv :id (skills/rank-providers *conn* :x)))))

(deftest dispatch-target-returns-first-or-nil
  (is (nil? (skills/dispatch-target *conn* :nobody-provides)))
  (actors/spawn-agent! *conn* {:id :only :skills #{:thing}})
  (is (= :only (:id (skills/dispatch-target *conn* :thing)))))

;; ============================================================================
;; find-providers (kind-agnostic)
;; ============================================================================

(deftest find-providers-includes-offline
  (actors/spawn-agent! *conn* {:id :a :skills #{:y}})
  (actors/spawn-agent! *conn* {:id :b :skills #{:y}})
  (actors/dismiss! *conn* :a)
  (is (= #{:a :b} (set (skills/find-providers *conn* :y)))
      "find-providers returns all, regardless of status"))
