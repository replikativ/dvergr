(ns dvergr.catalog.kb-pages-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [dvergr.catalog.wiki-v4 :as v4]
            [dvergr.agent.review :as review]
            [dvergr.kb.wiki :as kb-wiki]))

(def schema
  (vec (for [[attr type many?] [[:S.Page/kind :db.type/keyword]
                                [:S.Page/title :db.type/string]
                                [:S.Page/source :db.type/string true]
                                [:S.Page/entry-point :db.type/string]
                                [:entity/uuid :db.type/uuid]
                                [:block/parent :db.type/ref]
                                [:block/order :db.type/string]
                                [:block/content :db.type/string]
                                [:block/references :db.type/ref true]
                                [:block/widget-code :db.type/string]
                                [:block/viz-spec :db.type/string]
                                [:grant/permission :db.type/keyword]]]
         {:db/ident attr :db/valueType type :db/cardinality (if many? :db.cardinality/many :db.cardinality/one)})))

(def quote-id #uuid "e161027e-014a-434c-9af2-9c38872480d4")

(def fixture
  [{:db/id 100 :S.Page/kind :wiki/atom :S.Page/title "River"
    :S.Page/source #{"charter#History"} :S.Page/entry-point "The river's history."}
   {:db/id 101 :S.Page/kind :wiki/overview :S.Page/title "Overview"}
   {:db/id 102 :S.Page/kind :wiki/index :S.Page/title "Index"}
   {:db/id 103 :block/parent 100 :block/order "a" :block/content "<h2>A river flows</h2>"}
   {:db/id 104 :block/parent 103 :block/order "a" :block/content "<p>The river flows west [[charter#History]].</p>"}
   {:db/id 105 :block/parent 103 :block/order "b" :entity/uuid quote-id
    :block/content "<blockquote>\"The river flows west through the valley.\"</blockquote><p>[[charter#History]]</p>"}
   {:db/id 106 :block/parent 100 :block/order "b" :block/content "<strong>Sources:</strong> [[charter]]"}
   {:db/id 107 :block/parent 101 :block/order "a" :block/content "<p>[[River]]</p>"}
   {:db/id 108 :block/parent 102 :block/order "a" :block/content "<p>[[Overview]]</p>"}])

(defn with-kb [f]
  (let [cfg {:store {:backend :memory :id (random-uuid)} :schema-flexibility :write :keep-history? true}]
    (d/create-database cfg)
    (let [conn (d/connect cfg)]
      (try (d/transact conn schema) (d/transact conn fixture) (f conn)
           (finally (d/release conn) (d/delete-database cfg))))))

(defn resolve-source [ref]
  (when (str/starts-with? ref "charter")
    {:path "/docs/charter.md" :text "# Charter\n## History\nThe river flows west through the valley.\nIt supplies the farms and the city.\n"}))

(deftest a-hand-built-kb-scores-one
  (with-kb
    (fn [conn]
      (let [projected (v4/kb->pages @conn {:resolve-source resolve-source})
            result (v4/score-structure projected)]
        (is (= 1.0 (:reward result)) (pr-str result))
        (is (= #{"/wiki/index.md" "/wiki/atoms/river.md" "/wiki/overviews/overview.md"} (set (keys (:pages projected)))))
        (is (str/includes? (get-in projected [:pages "/wiki/atoms/river.md"])
                           "> \"The river flows west through the valley.\" [charter#History](/docs/charter.md#History)"))
        (is (= #{"/docs/charter.md"} (set (keys (:sources projected)))))))))

(deftest projection-resolves-block-links-and-source-uris
  (with-kb
    (fn [conn]
      (d/transact conn [{:db/id 107 :block/content (str "<p>[[River]] ((" quote-id ")) [[dh://archive/charter#History][Charter]]</p>")}
                        {:db/id 100 :S.Page/source "dh://archive/charter#History"}])
      (let [projected (v4/kb->pages @conn {:resolve-source (fn [ref] (when (str/starts-with? ref "dh://") (resolve-source "charter")))})
            overview (get-in projected [:pages "/wiki/overviews/overview.md"])]
        (is (str/includes? overview "[River](/wiki/atoms/river.md)"))
        (is (str/includes? overview "[block](/wiki/atoms/river.md#a-river-flows)"))
        (is (str/includes? overview "[Charter](/docs/charter.md#History)")))
      (is (empty? (:sources (v4/kb->pages @conn {:resolve-source (constantly nil)})))))))

(deftest colliding-slugs-get-stable-uuid-suffixes
  (with-kb
    (fn [conn]
      (let [uuid #uuid "7df40fa7-5dd1-47d8-9897-641341f2be8d"]
        (d/transact conn [{:db/id 109 :S.Page/title "River!" :S.Page/kind :wiki/atom :entity/uuid uuid}])
        (let [pages (:pages (v4/kb->pages @conn {}))]
          (is (contains? pages (str "/wiki/atoms/river-" uuid ".md")))
          (is (= 4 (count pages)))
          (is (= pages (:pages (v4/kb->pages @conn {})))))))))

(deftest suffixed-paths-do-not-overwrite-a-third-pages-natural-path
  (doseq [third-id [99 110]]
    (with-kb
      (fn [conn]
        (let [uuid #uuid "7df40fa7-5dd1-47d8-9897-641341f2be8d"
              title (str "River-" uuid)
              natural (str "/wiki/atoms/river-" uuid ".md")]
          (d/transact conn [{:db/id 109 :S.Page/title "River!" :S.Page/kind :wiki/atom :entity/uuid uuid}
                            {:db/id third-id :S.Page/title title :S.Page/kind :wiki/atom}
                            {:db/id 107 :block/content (str "<p>[[River]] [[River!]] [[" title "]]</p>")}])
          (let [pages (:pages (v4/kb->pages @conn {}))
                disambiguated (str "/wiki/atoms/river-" uuid "-2.md")
                overview (pages "/wiki/overviews/overview.md")]
            (is (= 5 (count pages)))
            (is (str/includes? (pages natural) (str "# " title)))
            (is (str/includes? (pages disambiguated) "# River!"))
            (is (some #(str/includes? % "The river flows west") (vals pages)))
            (is (str/includes? overview (str "[" title "](" natural ")")))
            (is (str/includes? overview (str "[River!](" disambiguated ")")))
            (is (= pages (:pages (v4/kb->pages @conn {}))))))))))

(deftest html-is-walked-as-markdown
  (let [render #'v4/html-markdown]
    (is (= "## Claim\nText **bold** and *italic*.\n\n- `code`\n- [link](/x)"
           (render "<h2>Claim</h2><p>Text <strong>bold</strong> and <em>italic</em>.</p><ul><li><code>code</code></li><li><a href='/x'>link</a></li></ul>")))
    (is (= "```\nx < y\n```" (render "<pre><code>x &lt; y</code></pre>")))
    (is (not (str/includes? (render "<blockquote>\"quote words here\"</blockquote><p>[[doc]] extra</p>") "> \"quote words here\" [[doc]] extra")))))

(deftest datom-scope-is-checked-in-both-snapshots
  (with-kb
    (fn [conn]
      (let [before (kb-wiki/datoms @conn)
            offenders #'review/kb-offenders
            kinds #{:wiki/atom :wiki/overview :wiki/index}
            check (fn [datoms] (offenders before datoms kinds))]
        (doseq [tx [[{:db/id 104 :block/content "<p>Edited</p>"}]
                    [{:db/id 104 :block/references 100}]
                    [{:db/id 200 :S.Page/kind :wiki/atom :S.Page/title "New"}]
                    [[:db/retractEntity 104]]]]
          (is (empty? (check (kb-wiki/datoms (:db-after (d/with @conn tx))))) (str tx)))
        (doseq [tx [[{:db/id 100 :grant/permission :owner}]
                    [{:db/id 100 :S.Page/kind :note}]
                    [{:db/id 104 :block/widget-code "code"}]
                    [{:db/id 104 :block/viz-spec "spec"}]
                    [{:db/id 103 :block/parent 104}]
                    [{:db/id 104 :block/parent 999}]
                    [{:db/id 201 :S.Page/kind :note :S.Page/title "Notes"}]
                    [{:db/id 104 :block/parent 201} {:db/id 201 :S.Page/kind :note}]
                    [{:db/ident :new/attribute :db/valueType :db.type/string :db/cardinality :db.cardinality/one}]]]
          (is (seq (check (kb-wiki/datoms (:db-after (d/with @conn tx))))) (str tx)))
        (let [bad (conj before [104 :block/widget-code "code"])]
          (is (seq (offenders bad before kinds)) "retracting forbidden content fails too"))
        (let [chain (into #{[100 :S.Page/kind :wiki/atom]}
                          (for [id (range 200 1202)] [id :block/parent (if (= id 200) 100 (dec id))]))]
          (is (seq (offenders chain (conj chain [1201 :block/content "deep"]) kinds))))))))

(deftest projection-accepts-attribute-overrides
  (with-kb
    (fn [conn]
      (d/transact conn [{:db/ident :entity/title :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
                        {:db/id 100 :entity/title "Renamed"}])
      (is (contains? (:pages (v4/kb->pages @conn {:attrs {:title :entity/title}}))
                     "/wiki/atoms/renamed.md")))))

(deftest offending-evidence-is-bounded
  (let [old (into #{} (for [e (range 30)] [e :S.Page/kind :wiki/atom]))
        new (into old (for [e (range 30)] [e :block/widget-code "code"]))]
    (is (= 20 (count (#'review/kb-offenders old new #{:wiki/atom}))))))

(deftest reflected-morphism-attributes-are-resolved-from-the-kb
  (with-kb
    (fn [conn]
      (d/transact conn [{:db/ident :entity/name :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
                        {:db/ident :morphism/storage-attr :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
                        {:db/ident :entity/title :db/valueType :db.type/string :db/cardinality :db.cardinality/one}])
      (d/transact conn [{:entity/name "S/Page/title" :morphism/storage-attr :entity/title}
                        {:db/id 100 :entity/title "Reflected"}])
      (let [resolve-var requiring-resolve]
        (with-redefs [clojure.core/requiring-resolve
                      (fn [sym]
                        (if (= sym 'is.simm.model.morphism/attr-of)
                          (fn [morphism]
                            (or (:morphism/storage-attr morphism)
                                (let [parts (str/split (:entity/name morphism) #"/")]
                                  (keyword (str/join "." (butlast parts)) (last parts)))))
                          (resolve-var sym)))]
          (is (= :entity/title (:title (kb-wiki/attrs @conn nil))))
          (is (= :S.Page/title (:title (kb-wiki/attrs @conn {:title :S.Page/title}))))
          (is (contains? (:pages (v4/kb->pages @conn {})) "/wiki/atoms/reflected.md")))))))

(deftest path-scope-still-rejects-other-systems-and-outside-files
  (let [check [:diff/only-paths ["wiki/"]]
        ctx {:files ["wiki/index.md"] :other-systems ["kb"] :kb-systems ["kb"]}]
    (is (:pass? (review/run-check check ctx)))
    (is (not (:pass? (review/run-check check (assoc ctx :other-systems ["kb" "other-kb"])))))
    (is (not (:pass? (review/run-check check (assoc ctx :files ["schedules.edn"])))))))

(deftest section-citations-can-resolve-through-the-bare-document
  (with-kb
    (fn [conn]
      (d/transact conn
                  (into [[:db/retract 100 :S.Page/source "charter#History"]
                         [:db/add 100 :S.Page/source "dh://archive/charter#History"]]
                        (for [block fixture :when (#{104 105 106} (:db/id block))]
                          (update block :block/content str/replace "charter" "dh://archive/charter"))))
      (let [result (v4/score-structure
                    (v4/kb->pages @conn {:resolve-source (fn [ref]
                                                           (when (= "dh://archive/charter" ref)
                                                             (resolve-source "charter")))}))]
        (is (= 1.0 (:reward result)) (pr-str result))))))

(deftest bare-sources-citations-resolve-through-a-successful-section
  (with-kb
    (fn [conn]
      (d/transact conn
                  (into [[:db/retract 100 :S.Page/source "charter#History"]
                         [:db/add 100 :S.Page/source "dh://archive/charter#History"]]
                        (for [block fixture :when (#{104 105 106} (:db/id block))]
                          (update block :block/content str/replace "charter" "dh://archive/charter"))))
      (let [projected (v4/kb->pages @conn {:resolve-source (fn [ref]
                                                             (when (= "dh://archive/charter#History" ref)
                                                               (resolve-source "charter")))})
            result (v4/score-structure projected)]
        (is (str/includes? (get-in projected [:pages "/wiki/atoms/river.md"])
                           "**Sources:** [dh://archive/charter](/docs/charter.md)"))
        (is (= #{"/docs/charter.md"} (set (keys (:sources projected)))))
        (is (= 1.0 (:reward result)) (pr-str result))))))
