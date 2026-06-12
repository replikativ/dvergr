(ns dvergr.kb.schema-test
  "P2: the knowledge base IS a katzen ACSet. Builds it on a datahike conn from
   `dvergr.kb.schema/kb-schema`, exercises it through the generic ACSet protocol
   (parts, Homs, Attrs), and cross-references code through the shared Identity
   via `katzen.xref`. Runs under the katzen-enabled (:dev) alias."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [dvergr.chat.schema :as cs]
            [dvergr.kb.schema :as kb]
            [katzen.acset :as a]
            [katzen.acset.datahike :as kdh]
            [katzen.xref :as xref]))

(defn- sample-kb
  "A KB ACSet with a company and a person who is employed there (the
   :entity/employer Hom), plus a 'technology' entity whose title is a code URI."
  []
  (let [k0 (kdh/datahike-acset kb/kb-schema)
        [k company] (a/add-part k0 :Entity)
        k (-> k (a/set-subpart :entity/title company "Acme Corp")
              (a/set-subpart :entity/type  company :company))
        [k person] (a/add-part k :Entity)
        k (-> k (a/set-subpart :entity/title    person "Ada Lovelace")
              (a/set-subpart :entity/type     person :person)
              (a/set-subpart :entity/employer person company))   ; the Hom
        [k tech] (a/add-part k :Entity)
        k (-> k (a/set-subpart :entity/title tech "demo.core/parse-config")
              (a/set-subpart :entity/type  tech :technology))]
    {:kb k :company company :person person :tech tech}))

(deftest kb-is-an-acset-queryable-through-the-protocol
  (let [{:keys [kb person company]} (sample-kb)]
    (testing "parts and typed Attrs"
      (is (= 3 (a/nparts kb :Entity)))
      (is (= "Ada Lovelace" (a/subpart kb :entity/title person)))
      (is (= :person (a/subpart kb :entity/type person))))
    (testing "the :entity/employer Hom is a function Entity → Entity"
      (is (= company (a/subpart kb :entity/employer person))
          "person's employer resolves to the company part"))
    (testing "following the Hom then an Attr — 'where does Ada work?'"
      (is (= "Acme Corp"
             (a/subpart kb :entity/title (a/subpart kb :entity/employer person)))))
    (testing "incident: inverse image — 'who are the people?'"
      (is (= [person] (a/incident kb :entity/type :person))))))

(deftest wraps-a-live-knowledge-schema-conn-as-an-acset
  (testing "an existing dvergr KB datahike conn (real knowledge-schema, no katzen
            markers, :entity/title already :db.unique/value) wraps cleanly"
    (let [cfg {:store {:backend :memory :id (random-uuid)}
               :schema-flexibility :write :keep-history? false}
          _   (d/create-database cfg)
          conn (d/connect cfg)]
      (d/transact conn cs/knowledge-schema)
      ;; entities written the dvergr way — no :katzen/ob, an :entity/employer ref
      (let [r (d/transact conn [{:db/id "acme" :entity/id (random-uuid)
                                 :entity/title "Acme Ltd" :entity/type :company}
                                {:entity/id (random-uuid) :entity/title "Ada"
                                 :entity/type :person :entity/employer "acme"}])
            nu (get-in r [:tempids "acme"])
            ;; wrap the EXISTING conn — must not conflict on pre-existing attrs
            ac (kb/as-acset conn)]
        (is (= 2 (a/nparts ac :Entity)) "existing entities enumerated after tagging")
        (is (= #{"Acme Ltd" "Ada"} (set (vals (a/subpart-all ac :entity/title)))))
        (let [ada (first (a/incident ac :entity/title "Ada"))]
          (is (= nu (a/subpart ac :entity/employer ada)) "employer Hom resolved")
          (is (= "Acme Ltd" (a/subpart ac :entity/title (a/subpart ac :entity/employer ada)))
              "Ada works at Acme Ltd, via Hom then Attr"))
        (testing "idempotent: re-wrapping tags nothing new"
          (is (= 0 (kb/ensure-object-markers! conn))))
        (testing "the app's unique constraint on :entity/title is untouched"
          (is (thrown? Exception
                       (d/transact conn [{:entity/id (random-uuid) :entity/title "Acme Ltd"}]))))))))

(deftest kb-cross-references-code-over-the-shared-identity
  (testing "a KB entity and a code def with the same URI link via katzen.xref"
    (let [{:keys [kb tech]} (sample-kb)
          ;; a minimal code ACSet (P3 will be the real one); Identity = string URI
          code-schema {:objects [:Def] :homs []
                       :attr-types [:Identity :String]
                       :attrs [{:name :def/qname  :dom :Def :codom :Identity}
                               {:name :def/source :dom :Def :codom :String}]}
          code (-> (kdh/datahike-acset code-schema)
                   (a/add-part-with :Def {:def/qname "demo.core/parse-config"
                                          :def/source "(defn parse-config [s] ...)"})
                   (a/add-part-with :Def {:def/qname "demo.core/other"
                                          :def/source "(defn other [] ...)"}))
          ;; pullback of KB Entity titles ⋈ code Def qnames over Identity
          matches (xref/xref kb kb/identity-attr code :def/qname)]
      (is (= 1 (count matches)) "only the 'demo.core/parse-config' entity matches a def")
      (is (= "demo.core/parse-config" (:id (first matches))))
      (is (= tech (:a (first matches))) "the matched KB part is the technology entity")
      (testing "dangling: KB entities with no corresponding code def"
        (is (= #{"Acme Corp" "Ada Lovelace"}
               (set (map :id (xref/dangling kb kb/identity-attr code :def/qname)))))))))
