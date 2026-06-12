(ns dvergr.code.index-test
  "P3: the code ACSet — Defs keyed by qname Identity + a Ref graph, built from
   text and kept current via diff-source, and cross-referenced with the KB over
   the shared Identity. Runs under the katzen-enabled (:dev) alias."
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.code.index :as idx]
            [dvergr.kb.schema :as kb]
            [katzen.acset :as a]
            [katzen.acset.datahike :as kdh]
            [katzen.xref :as xref]))

(def src-v1
  "(ns demo.core)\n\n(defn a [x] (atom x))\n\n(defn b [y] (atom y))\n\n(defn c [] 1)\n")

(deftest indexes-defs-and-the-reference-graph
  (let [ix (idx/create)]
    (idx/index-file! ix "demo/core.clj" src-v1)
    (testing "defs are present, keyed by qname URI"
      (is (= #{"demo.core/a" "demo.core/b" "demo.core/c"} (idx/defs ix)))
      (is (= "(defn a [x] (atom x))" (idx/def-source ix "demo.core/a"))))
    (testing "the Ref graph answers find-references for a library qname"
      (is (= #{"demo.core/a" "demo.core/b"} (idx/dependents ix "clojure.core/atom"))
          "a and b call atom; c does not"))))

(deftest diff-source-drives-incremental-reindex
  (let [ix (idx/create)
        _  (idx/index-file! ix "demo/core.clj" src-v1)
        ;; v2: modify a (drop the atom call), add d, remove c
        src-v2 "(ns demo.core)\n\n(defn a [x] (* 2 x))\n\n(defn b [y] (atom y))\n\n(defn d [] 9)\n"]
    (idx/apply-diff! ix "demo/core.clj" src-v1 src-v2)
    (testing "only the changed defs are reflected"
      (is (= #{"demo.core/a" "demo.core/b" "demo.core/d"} (idx/defs ix))
          "c removed, d added, a/b kept")
      (is (= "(defn a [x] (* 2 x))" (idx/def-source ix "demo.core/a")) "a updated in place"))
    (testing "the Ref graph updated with the defs"
      (is (= #{"demo.core/b"} (idx/dependents ix "clojure.core/atom"))
          "a no longer references atom; only b does"))))

(deftest code-cross-references-the-kb-over-shared-identity
  (testing "a KB entity and a code Def with the same qname URI link via xref"
    (let [ix (idx/create)
          _  (idx/index-file! ix "demo/core.clj" src-v1)
          ;; a KB ACSet with an entity titled like a code def (a doc note about it)
          kbset (-> (kdh/datahike-acset kb/kb-schema)
                    (a/add-part-with :Entity {:entity/title "demo.core/a"
                                              :entity/type :technology})
                    (a/add-part-with :Entity {:entity/title "Unrelated Thing"
                                              :entity/type :company}))
          matches (xref/xref kbset kb/identity-attr ix idx/identity-attr)]
      (is (= 1 (count matches)))
      (is (= "demo.core/a" (:id (first matches))) "the documented def links to its KB entity")
      (testing "dangling: KB entities with no code def"
        (is (= #{"Unrelated Thing"}
               (set (map :id (xref/dangling kbset kb/identity-attr ix idx/identity-attr)))))))))
