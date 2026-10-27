(ns dvergr.value
  "Bounded portable data and disjoint identity domains.")

(def ^:private scalars
  #{Boolean String clojure.lang.Keyword clojure.lang.Symbol java.util.UUID
    Long Integer Short Byte Double Float java.math.BigInteger java.math.BigDecimal
    clojure.lang.BigInt clojure.lang.Ratio})

(def ^:no-doc collection-classes
  "Exact immutable collection implementations admitted by the v1 grammar."
  #{clojure.lang.PersistentArrayMap clojure.lang.PersistentHashMap
    clojure.lang.PersistentVector clojure.lang.PersistentList
    clojure.lang.PersistentList$EmptyList clojure.lang.PersistentHashSet})

(defn ^:no-doc fail! [code data]
  (throw (ex-info (name code) (assoc data :dvergr/error code))))

(defn ^:no-doc scalar? [x]
  (or (nil? x) (contains? scalars (class x))))

(defn portable!
  "Validate eager immutable data without metadata under explicit traversal limits."
  [value {:keys [depth nodes string-size] :as limits}]
  (when-not (and (= #{:depth :nodes :string-size} (set (keys limits)))
                 (every? pos-int? [depth nodes string-size]))
    (fail! :value-limit {}))
  (let [remaining (volatile! nodes)]
    (letfn [(walk [x level]
              (when (or (> level depth) (neg? (vswap! remaining dec))
                        (and (string? x) (> (count x) string-size)))
                (fail! :value-limit {}))
              (when-not (or (scalar? x) (contains? collection-classes (class x)))
                (fail! :nonportable-value {}))
              (when (meta x) (fail! :nonportable-value {}))
              (cond
                (scalar? x) nil
                (contains? collection-classes (class x))
                (if (map? x)
                  (doseq [[k v] x] (walk k (inc level)) (walk v (inc level)))
                  (doseq [v x] (walk v (inc level))))
                :else (fail! :nonportable-value {})))]
      (walk value 0)
      value)))

(defn identity!
  "Validate an identity without coercing its domain."
  [kind value]
  (when-not
   (case kind
     :world (and (keyword? value) (nil? (namespace value))
                 (boolean (re-matches #"(?:root-|fork-)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
                                      (name value))))
     (:installation :control-store :room :run :attempt :grant :reservation
                    :receipt :admission :cancellation :settlement :recovery :account)
     (uuid? value)
     :system (or (keyword? value) (and (string? value) (<= 1 (count value) 256)))
     false)
    (fail! :invalid-identity {:kind kind}))
  value)
