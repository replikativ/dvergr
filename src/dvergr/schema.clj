(ns dvergr.schema
  "Versioned immutable control records; future lifecycle families remain reserved."
  (:require [dvergr.value :as v]))

(def ^:no-doc limits {:depth 48 :nodes 50000 :string-size 262144})

(defn- component? [[id component]]
  (and (map? component)
       (= :snapshotted (:classification component))
       (if (= :sci-forkable (:mechanism component))
         (and (uuid? id)
              (= #{:classification :mechanism :tier :classifier} (set (keys component)))
              (nil? (:tier component)) (= :dvergr.injection/v1 (:classifier component)))
         (and (or (keyword? id) (string? id))
              (= #{:classification :adapter :tier :basis} (set (keys component)))
              (case (:adapter component)
                :dvergr.adapter.datahike/v1
                (and (= :cold (:tier component)) (string? (:basis component)) (seq (:basis component)))
                :dvergr.adapter.geschichte/v1 (nil? (:tier component))
                false)))))

(defn control-schema
  "Return slice 1's control-only schema."
  []
  (mapv (fn [[id type unique?]]
          (cond-> {:db/ident id :db/valueType type :db/cardinality :db.cardinality/one}
            unique? (assoc :db/unique :db.unique/identity)))
        [[:control/key :db.type/string true]
         [:control/payload :db.type/string false]
         [:world/id :db.type/keyword true]
         [:world/source :db.type/keyword false]
         [:world/root :db.type/keyword false]
         [:room/id :db.type/uuid true]]))

(defn control-record!
  "Validate the complete versioned record, including its immutable identity."
  [kind record]
  (v/portable! record limits)
  (when-not (#{:installation :world :room} kind)
    (v/fail! :reserved-record-kind {:kind kind}))
  (let [required (case kind
                   :installation #{:installation/id :control-store/id :schema/version :store/role}
                   :world #{:world/id :world/source :world/root :world/relation
                            :assembly/version :assembly/control-store :assembly/upstream-versions
                            :assembly/spindel-descriptor :assembly/cut :assembly/claims
                            :assembly/components :assembly/effect-policy :assembly/resource-authority}
                   :room #{:room/id :room/world :room/kind :schema/version})]
    (when-not (and (map? record) (= required (set (keys record))))
      (v/fail! :invalid-control-record {:kind kind})))
  (try
    (case kind
      :installation
      (do (v/identity! :installation (:installation/id record))
          (v/identity! :control-store (:control-store/id record))
          (when-not (and (= 1 (:schema/version record)) (= :control (:store/role record)))
            (v/fail! :invalid-control-record {})))
      :room
      (do (v/identity! :room (:room/id record))
          (v/identity! :world (:room/world record))
          (when-not (and (= 1 (:schema/version record)) (= :root (:room/kind record)))
            (v/fail! :invalid-control-record {})))
      :world
      (do (doseq [k [:world/id :world/root]] (v/identity! :world (get record k)))
          (v/identity! :control-store (:assembly/control-store record))
          (when-let [source (:world/source record)] (v/identity! :world source))
          (when-not (and (= 1 (:assembly/version record))
                         (= :unavailable-in-slice-1 (:assembly/effect-policy record))
                         (= :unbound (:assembly/resource-authority record))
                         (map? (:assembly/components record))
                         (every? component? (:assembly/components record))
                         (= #{:selected-world-resolution} (:assembly/claims record))
                         (map? (:assembly/upstream-versions record))
                         (every? string? (vals (:assembly/upstream-versions record)))
                         (case (:world/relation record)
                           :root (and (nil? (:world/source record))
                                      (= (:world/id record) (:world/root record))
                                      (nil? (:assembly/spindel-descriptor record))
                                      (nil? (:assembly/cut record)))
                           :test-frozen-fork
                           (and (:world/source record)
                                (map? (:assembly/spindel-descriptor record))
                                (= (:world/id record) (get-in record [:assembly/spindel-descriptor :fork/id]))
                                (= (:world/source record) (get-in record [:assembly/spindel-descriptor :fork/parent]))
                                (= {:kind :uncoordinated-frozen :id nil :p1? false}
                                   (:assembly/cut record)))
                           false))
            (v/fail! :invalid-control-record {}))))
    (catch clojure.lang.ExceptionInfo _
      (v/fail! :invalid-control-record {:kind kind})))
  record)
