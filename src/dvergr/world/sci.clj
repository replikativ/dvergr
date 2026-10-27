(ns dvergr.world.sci
  "Closed v1 injection grammar and synchronous provider-free SCI entry."
  (:require [dvergr.schema :as schema]
            [dvergr.value :as v]
            [dvergr.world.systems :as systems]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.sci.world :as interpreter]
            [sci.fork :as fork]))

(deftype ^:private Operation [handle operation]
  fork/Forkable
  (fork-value [this] this)
  clojure.lang.IFn
  (invoke [_ request]
    (try
      (systems/invoke! systems/*installation* handle operation request)
      (catch Throwable e
        (throw (ex-info "Work operation refused"
                        {:dvergr/error (or (:dvergr/error (ex-data e)) :adapter-failure)}))))))
(alter-meta! #'->Operation assoc :private true)

(defn classify!
  "Classify the entire bounded injection graph, including keys and metadata."
  [value]
  (let [remaining (volatile! 10000)
        seen (java.util.IdentityHashMap.)]
    (letfn [(walk [x depth]
              (when (or (> depth 32) (neg? (vswap! remaining dec))
                        (and (string? x) (> (count x) 65536)))
                (v/fail! :injection-limit {}))
              (when (.containsKey seen x) (v/fail! :unclassified-value {}))
              (cond
                (v/scalar? x) (when-let [m (meta x)] (walk m (inc depth)))
                (systems/handle-data x)
                (let [{:keys [installation-id anchor system-id operations classifier] :as data}
                      (systems/handle-data x)]
                  (v/portable! data schema/limits)
                  (v/identity! :installation installation-id)
                  (v/identity! :world anchor)
                  (v/identity! :system system-id)
                  (when-not (and (= :dvergr.injection/v1 classifier)
                                 (set? operations) (every? keyword? operations))
                    (v/fail! :unclassified-value {})))
                (instance? Operation x)
                (let [^Operation op x]
                  (walk (.-handle op) (inc depth))
                  (when-not (contains? (:operations (systems/handle-data (.-handle op)))
                                       (.-operation op))
                    (v/fail! :unclassified-value {})))
                (contains? v/collection-classes (class x))
                (do
                  (.put seen x true)
                  (when-let [m (meta x)] (walk m (inc depth)))
                  (if (map? x)
                    (doseq [[k val] x] (walk k (inc depth)) (walk val (inc depth)))
                    (doseq [val x] (walk val (inc depth))))
                  (.remove seen x))
                :else (v/fail! :unclassified-value {})))]
      (walk value 0)
      {:classifier :dvergr.injection/v1 :classification :shared})))

(def ^:private allowed
  '[def defn fn fn* let let* do quote var if try catch finally throw
    and or when when-not cond case loop loop* recur
    + - * / = not= < <= > >= inc dec str pr-str name keyword symbol
    nil? some? true? false? number? string? keyword? symbol? uuid? map? vector? set?
    identity constantly comp partial complement not boolean
    vector hash-map hash-set list assoc dissoc get get-in assoc-in update update-in
    conj disj into merge select-keys keys vals contains? count empty? first second nth
    rest next last peek pop vec set seq concat map mapv filter filterv reduce range
    apply every? some atom deref reset! swap! compare-and-set!
    meta with-meta vary-meta alter-meta! reset-meta! alter-var-root var-get
    create-ns find-ns ns-name ns-unmap resolve
    ex-data ex-message])

(defn ^:no-doc validate-manifest! [{:keys [bindings operations] :as manifest}]
  (when-not (and (map? manifest) (= #{:bindings :operations} (set (keys manifest)))
                 (map? bindings) (map? operations))
    (v/fail! :unsupported-sci-option {}))
  (classify! bindings)
  (v/portable! operations schema/limits)
  (doseq [table [bindings operations]
          [ns-name names] table]
    (when-not (and (symbol? ns-name) (nil? (namespace ns-name))
                   (not (#{'clojure.core 'sci.core 'java.lang} ns-name))
                   (map? names) (every? #(and (symbol? %) (nil? (namespace %))) (keys names)))
      (v/fail! :unsupported-sci-option {})))
  (doseq [[ns-name names] operations [name spec] names]
    (when-not (and (map? spec) (= #{:system :operation} (set (keys spec)))
                   (keyword? (:operation spec))
                   (not= :discourse/write (:operation spec))
                   (not (contains? (get bindings ns-name) name)))
      (v/fail! :unsupported-sci-option {})))
  manifest)

(defn install!
  "Install one forkable interpreter from a closed bindings and operations manifest."
  [assembly {:keys [bindings operations] :as manifest}]
  (when @(:published assembly) (v/fail! :assembly-published {}))
  (when @(:sci-ref assembly) (v/fail! :unsupported-sci-option {}))
  (validate-manifest! manifest)
  (let [namespaces
        (reduce-kv
         (fn [result ns-name names]
           (reduce-kv
            (fn [result name {:keys [system operation]}]
              (assoc-in result [ns-name name]
                        (Operation. (systems/assembly-handle assembly system #{operation}) operation)))
            result names))
         bindings operations)]
    (classify! namespaces)
    (let [ref (interpreter/create!
               (:context assembly)
               {:spindel-primitives? false
                :sci-opts {:namespaces namespaces
                           :allow (into allowed (for [[ns-name names] namespaces name (keys names)]
                                                  (symbol (str ns-name) (str name))))}})]
      (reset! (:sci-ref assembly) ref)
      ref)))

(defn eval!
  "Evaluate synchronously in a live selected interpreter; export only portable data."
  [installation world source]
  (when-not (identical? world (get @(:live installation) (get-in world [:descriptor :world/id])))
    (v/fail! :world-unavailable {}))
  (when-not (and (string? source) (<= (count source) 65536))
    (v/fail! :unsupported-entry {}))
  (when-not (:sci-ref world) (v/fail! :system-unavailable {}))
  (locking (:lock installation)
    (when-not (identical? world (get @(:live installation) (get-in world [:descriptor :world/id])))
      (v/fail! :world-unavailable {}))
    (swap! (:active world) inc)
    (try
      (let [result (binding [ec/*execution-context* (:context world)
                             systems/*installation* installation]
                     (interpreter/eval-string* (:sci-ref world) source))]
        (try (v/portable! result schema/limits)
             (catch clojure.lang.ExceptionInfo _ (v/fail! :nonportable-result {}))))
      (catch Throwable e
        (throw (ex-info "Evaluation refused"
                        {:dvergr/error (or (loop [cause e]
                                             (when cause
                                               (or (:dvergr/error (ex-data cause))
                                                   (recur (.getCause ^Throwable cause)))))
                                           :evaluation-failure)} e)))
      (finally (swap! (:active world) dec)))))
