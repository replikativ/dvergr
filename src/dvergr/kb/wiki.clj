(ns dvergr.kb.wiki
  "Attribute conventions shared by the KB wiki projection and review check."
  (:require [datahike.api :as d]))

(def defaults
  {:kind :S.Page/kind :title :S.Page/title :name :S.Page/name
   :source :S.Page/source :entry-point :S.Page/entry-point
   :archived :S.Page/archived :created-at :S.Page/created-at :updated-at :S.Page/updated-at
   :parent :block/parent :order :block/order :content :block/content
   :collapsed :block/collapsed :references :block/references :mentions :block/mentions
   :page-uuid :entity/uuid :block-uuid :entity/uuid})

(defn attrs
  "The simmis attributes, resolved through attr-of when installed. Overrides
   use the keys of `defaults` (e.g. `{:title :entity/title}`)."
  [db overrides]
  (let [attr-of (try (requiring-resolve 'is.simm.model.morphism/attr-of)
                     (catch java.io.FileNotFoundException _ nil))]
    (merge (if attr-of
             (into {} (for [[k a] defaults
                            :let [morphism-name (str "S/" (if (or (= "S.Page" (namespace a)) (= k :page-uuid)) "Page/" "Block/") (name a))
                                  morphism (when db
                                             (d/q '[:find (pull ?e [*]) . :in $ ?name
                                                    :where [?e :entity/name ?name]] db morphism-name))]]
                        [k (if morphism (attr-of morphism)
                               (if (= "S.Page" (namespace a))
                                 (attr-of {:entity/name morphism-name}) a))]))
             defaults)
           overrides)))

(defn datoms
  "Current EAV triples, with attribute ids resolved even in attribute-ref DBs."
  [db]
  (into #{} (map (fn [dt]
                   [(:e dt) (if (keyword? (:a dt)) (:a dt) (:db/ident (d/entity db (:a dt)))) (:v dt)]))
        (d/datoms db :eavt)))
