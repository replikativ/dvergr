(ns dvergr.laws.world-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [datahike.api :as d]
            [dvergr.room :as room]
            [dvergr.schema :as schema]
            [dvergr.store :as store]
            [dvergr.test-support :as t]
            [dvergr.value :as value]
            [dvergr.world :as world]
            [dvergr.world.sci :as sci]
            [dvergr.world.systems :as systems]
            [org.replikativ.spindel.engine.context :as context]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.yggdrasil :as y]
            [sci.fork :as fork]
            [yggdrasil.adapters.datahike :as dh]))

(deftest w1a-managed-state-isolation
  "W1a: atom, var root, var/namespace metadata and mappings isolate parent and siblings."
  (t/with-installation
    (fn [i]
      (let [parent (world/create! i (t/assembly))
            ev #(t/evaluate i %1 %2)
            _ (ev parent "(def a (atom 0)) (def x 0) (def mapped 0) nil")
            child (t/frozen! i parent)
            sibling (t/frozen! i parent)
            worlds [parent child sibling]
            read-state "[@a x (:tag (meta #'x)) (:tag (meta (find-ns 'user))) mapped]"
            changes ["(reset! a 1) nil"
                     "(alter-var-root #'x (constantly 2)) nil"
                     "(alter-meta! #'x assoc :tag 3) nil"
                     "(alter-meta! (find-ns 'user) assoc :tag 4) nil"
                     "(ns-unmap 'user 'mapped) (def mapped 5) nil"]
            states (atom [[0 0 nil nil 0] [0 0 nil nil 0] [0 0 nil nil 0]])]
        (doseq [[idx change] (map-indexed vector changes)]
          (ev child change)
          (swap! states assoc-in [1 idx] (inc idx))
          (is (= @states (mapv #(ev % read-state) worlds))))
        (doseq [[idx w] [[0 parent] [2 sibling]]]
          (ev w "(reset! a 9) (alter-var-root #'x (constantly 8)) (alter-meta! #'x assoc :tag 7) (alter-meta! (find-ns 'user) assoc :tag 6) (ns-unmap 'user 'mapped) (def mapped 4) nil")
          (swap! states assoc idx [9 8 7 6 4])
          (is (= @states (mapv #(ev % read-state) worlds))))))))

(deftest w1-handle-selects-current-world
  "W1: inherited closures, var roots, metadata and nested aliases resolve at each invocation."
  (t/with-installation
    (fn [i]
      (let [conn (t/work-connection)
            _ (d/transact conn [[:db/add 100 :item/value "initial"]])
            parent (world/create! i (t/work-assembly [(t/system-spec :work conn)]))
            ev #(t/evaluate i %1 %2)
            _ (ev parent "(def writer work/write) (def reader work/read) (alter-meta! #'writer assoc :write work/write) (def nested {:ops [work/write]}) (def write-captured (let [f work/write] (fn [v] (f [[:db/add 100 :item/value v]])))) (def read-captured (let [f work/read] (fn [] (f {:selector [:item/value] :eid 100})))) nil")
            child (t/frozen! i parent)
            sibling (t/frozen! i parent)
            worlds [parent child sibling]]
        (doseq [writer ["write-captured" "writer" "(:write (meta #'writer))" "(first (:ops nested))"]]
          (doseq [[idx w] (map-indexed vector worlds)]
            (ev w (if (= "write-captured" writer)
                    (str "(write-captured \"" idx "\") nil")
                    (str "(" writer " [[:db/add 100 :item/value \"" idx "\"]]) nil"))))
          (is (= ["0" "1" "2"] (mapv #(get (ev % "(read-captured)") :item/value) worlds))))
        (let [h (systems/handle i parent :work #{:datahike/pull})
              unrelated (world/create! i (assoc-in (t/assembly) [:injections :bindings]
                                                   {'foreign {'h h}}))]
          (binding [ec/*execution-context* (:context unrelated)]
            (with-redefs [y/system (fn [_] (throw (AssertionError. "Adapter entered")))]
              (is (= :unrelated-lineage
                     (t/error-code #(systems/invoke! i h :datahike/pull {:selector [:item/value] :eid 100})))))))))))

(deftest w2-excluded-systems-never-fall-back
  "W2/W2a: nested selected subsets exclude Datahike and Geschichte before adapter access."
  (t/with-installation
    (fn [i]
      (let [specs [(t/system-spec :a (t/work-connection))
                   (t/system-spec :b (t/work-connection)) (t/geschichte-spec :files)]
            root (world/create! i (t/work-assembly specs))
            handles (into {} (map (fn [id] [id (systems/handle i root id
                                                               (if (= :files id) #{} #{:datahike/pull}))])
                                  [:a :b :files]))]
        (is (= :no-selected-world (t/error-code #(systems/resolve! i (:a handles) :datahike/pull))))
        (doseq [subset [#{} #{:a} #{:b} #{:files} #{:a :b} #{:a :files} #{:b :files} #{:a :b :files}]]
          (let [child (t/frozen! i root {:systems subset})
                nested (t/frozen! i child {:systems #{}})]
            (doseq [w [child nested] id [:a :b :files]
                    :when (not (contains? (set (keys (:systems w))) id))]
              (binding [ec/*execution-context* (:context w)]
                (with-redefs [y/system (fn [_] (throw (AssertionError. "Excluded adapter entered")))]
                  (is (= :system-unavailable
                         (t/error-code #(systems/resolve! i (get handles id) :datahike/pull)))))))
            (when-not (contains? subset :a)
              (is (= :system-unavailable
                     (t/error-code #(t/evaluate i child "(work/read {:selector [:item/value] :eid 100})")))))
            (t/release! i child)))
        (let [child (t/frozen! i root {:sci? false})]
          (is (= :system-unavailable (t/error-code #(t/evaluate i child "42")))))
        (is (nil? (:item/value (d/pull @(:connection (first specs)) [:item/value] 100))))))))

(deftest w1-lineage-anchors-and-installation-selection
  "W1: introduction anchors attenuate sibling access; explicit installations recognise exact live contexts."
  (t/with-installation
    (fn [i]
      (let [root (world/create! i (t/assembly [(t/system-spec :work (t/work-connection))]))
            child (t/frozen! i root)
            sibling (t/frozen! i root)
            grandchild (t/frozen! i child)
            h (systems/handle i child :work #{:datahike/pull})]
        (is (= (systems/handle-data h) (value/portable! (systems/handle-data h) schema/limits)))
        (doseq [w [root sibling]]
          (binding [ec/*execution-context* (:context w)]
            (with-redefs [y/system (fn [_] (throw (AssertionError. "Foreign adapter entered")))]
              (is (= :unrelated-lineage (t/error-code #(systems/resolve! i h :datahike/pull)))))))
        (binding [ec/*execution-context* (:context grandchild)]
          (is (some? (systems/resolve! i h :datahike/pull)))
          (is (= :operation-denied (t/error-code #(systems/resolve! i h :datahike/transact)))))
        (binding [ec/*execution-context* (assoc (:context child) :metadata {:forged true})]
          (is (= :unknown-world (t/error-code #(systems/resolve! i h :datahike/pull)))))
        (is (= :no-selected-world (t/error-code #(world/selected! i root))))
        (let [other-directory (world/installation (:control i))]
          (binding [ec/*execution-context* (:context child)]
            (is (= :unknown-world (t/error-code #(systems/resolve! other-directory h :datahike/pull))))))))))

(deftest w4-assembly-preflight-and-bounded-operations
  "W4/assembly gates: closed options and unsupported schemas refuse before context acquisition."
  (t/with-installation
    (fn [i]
      (doseq [spec [(assoc-in (t/assembly) [:injections :sci-opts] {:classes :all})
                    (assoc-in (t/assembly) [:injections :bindings] {'user {'unsafe (atom 0)}})
                    (assoc (t/assembly) :effect-policy :arbitrary)
                    (t/assembly [{:id :unapproved :adapter :arbitrary}])]]
        (with-redefs [context/create-execution-context
                      (fn [& _] (throw (AssertionError. "Context acquired")))]
          (is (some? (t/error-code #(world/create! i spec))))))
      (let [conn (t/work-connection)]
        (try
          (d/transact conn [{:db/ident :item/many :db/valueType :db.type/string
                             :db/cardinality :db.cardinality/many}])
          (with-redefs [context/create-execution-context
                        (fn [& _] (throw (AssertionError. "Context acquired")))]
            (is (= :unsupported-adapter
                   (t/error-code #(world/create! i (t/assembly [(assoc (t/system-spec :many conn)
                                                                       :attributes #{:item/many})]))))))
          (finally (d/release conn)))))))

(defrecord UnknownRecord [x])
(deftype MaliciousForkable [called]
  fork/Forkable
  (fork-value [this] (reset! called true) this))

(deftest w4-recursive-injection-grammar
  "W4: generated nested graphs inspect keys and metadata and refuse unaudited leaves unforced."
  (let [forced (atom false)
        called (atom false)
        lazy-value (lazy-seq (reset! forced true) [1])
        bad [(atom 1) (ref 1) (volatile! 1) (object-array 1) identity #'identity
             (java.util.ArrayList.) lazy-value (delay (reset! forced true))
             (->UnknownRecord 1) (MaliciousForkable. called) (sorted-map :x 1)]
        data (gen/recursive-gen
              (fn [inner] (gen/one-of [(gen/vector inner 0 3) (gen/map gen/keyword inner {:max-elements 3})]))
              (gen/one-of [gen/keyword gen/int gen/string-alphanumeric]))]
    (is (:pass? (tc/quick-check 80 (prop/for-all [x data]
                                                 (= :dvergr.injection/v1 (:classifier (sci/classify! x)))) :seed 2027)))
    (doseq [leaf bad container [(fn [x] [1 {:nested [x]}])
                                (fn [x] {x :key})
                                (fn [x] (with-meta [:ok] {:nested x}))
                                (fn [x] (with-meta 'value {:nested x}))]]
      (is (= :unclassified-value (t/error-code #(sci/classify! (container leaf))))))
    (is (false? @forced))
    (is (false? @called))
    (is (= :injection-limit (t/error-code #(sci/classify! (vec (repeat 10001 nil))))))
    (is (= :nonportable-value
           (t/error-code #(value/portable! (with-meta [] {:x 1}) schema/limits))))))

(deftest w3-host-values-and-escape-surface
  "W3: host capabilities refuse injection/export; sandbox probes expose no raw objects or reports."
  (t/with-installation
    (fn [i]
      (let [conn (t/work-connection)
            root (world/create! i (t/work-assembly [(t/system-spec :work conn)]))
            objects [#'identity (:context root) (:control i) i conn @conn (dh/create conn)
                     (ex-info "host error" {:connection conn})]]
        (doseq [x objects]
          (is (= :unclassified-value (t/error-code #(sci/classify! {:nested [x]})))))
        (doseq [source ["(.getClass work/read)" "(.-handle work/read)" "(class work/read)"
                        "(java.lang.System/getenv)" "(new java.lang.Thread)"
                        "(require 'datahike.api)" "(load-string \"42\")" "(future 1)"
                        "(eval '(+ 1 2))" "(var-get (intern 'clojure.core 'eval))"
                        "(ns-map (find-ns 'clojure.core))" "(ns-publics 'clojure.core)"
                        "(in-ns 'clojure.core) (def eval)" "(ns clojure.core)"
                        "(resolve 'clojure.core/eval)"]]
          (is (= :evaluation-failure (t/error-code #(t/evaluate i root source))) source))
        (doseq [source ["(atom 0)" "(fn [] 1)" "#'work/read" "(find-ns 'user)"
                        "{:nested [work/read]}"]]
          (is (= :nonportable-result (t/error-code #(t/evaluate i root source))) source))
        (is (= #{:tempids :datom-count}
               (set (keys (t/evaluate i root "(work/write [[:db/add -1 :item/value \"portable\"]])")))))
        (doseq [source ["(work/read {:selector [*] :eid 1})"
                        "(work/write [[:db.fn/call :arbitrary]])"
                        "(work/write [[:db/add 1 :db/ident :hijacked]])"]]
          (is (some? (t/error-code #(t/evaluate i root source))) source))
        (is (= :adapter-failure
               (t/evaluate i root "(try (work/write [[:db/add 100 :item/value 42]]) (catch Exception e (:dvergr/error (ex-data e))))")))
        (is (= :invalid-request
               (t/evaluate i root "(try (work/write [[:db/add 1 :db/ident :x]]) (catch Exception e (:dvergr/error (ex-data e))))")))))))

(deftest w6-durable-tree-lineage
  "W6: generated native world trees reconstruct exact paths from immutable control records after release."
  (t/with-installation
    (fn [i]
      (let [root (world/create! i (t/assembly))
            nodes (atom [root])
            paths (atom {(:world/id (world/descriptor i root)) [(:world/id (world/descriptor i root))]})]
        (doseq [index (gen/generate (gen/vector gen/nat 8) 8 2027)]
          (let [parent (nth @nodes (mod index (count @nodes)))
                child (t/frozen! i parent)
                record (world/descriptor i child)]
            (is (= {:kind :uncoordinated-frozen :id nil :p1? false} (:assembly/cut record)))
            (swap! nodes conj child)
            (swap! paths assoc (:world/id record) (conj (get @paths (:world/source record)) (:world/id record)))))
        (let [expected @paths]
          (t/release! i root)
          (reset! nodes [])
          (is (empty? @(:live i)))
          (doseq [[id path] expected]
            (is (= path (mapv :world/id (store/lineage (:control i) id))))))))))

(deftest w6-publication-refusal-and-root-room-gates
  "W6/assembly gates: durable-before-live, immutable retries, prerequisite refusals and fixed independent rooms."
  (t/with-installation
    (fn [i]
      (let [root (world/create! i (t/assembly))
            record (world/descriptor i root)
            control (:control i)
            before @(:live i)]
        (doseq [[request code] [[{:mode :snapshot} :missing-prerequisite]
                                [{:tier :hot} :missing-prerequisite]
                                [{:tier :warm} :missing-prerequisite]
                                [{:mode :following} :unsupported-mode]]]
          (with-redefs [y/fork! (fn [& _] (throw (AssertionError. "Native fork acquired")))]
            (is (= code (t/error-code #(world/fork! i root request))))))
        (doseq [kind [:snapshot :following]]
          (with-redefs [world/create! (fn [& _] (throw (AssertionError. "Root acquired")))]
            (is (= (if (= :snapshot kind) :missing-prerequisite :unsupported-mode)
                   (t/error-code #(room/create! i {:id (random-uuid) :kind kind :assembly (t/assembly)}))))))
        (binding [ec/*execution-context* (:context root)]
          (let [r (room/create! i {:id (random-uuid) :kind :root :assembly (t/assembly)})
                w (room/world i r)]
            (is (nil? (:parent-ctx (:context w))))
            (is (nil? (:world/source (world/descriptor i w))))
            (is (= (:record r) (room/lookup control (get-in r [:record :room/id]))))
            (world/close! i w)
            (is (= :room-offline (t/error-code #(room/world i r))))))
        (is (= record (store/put-world! control record)))
        (is (= :identity-conflict
               (t/error-code #(store/put-world! control (assoc-in record [:assembly/upstream-versions :sci] "changed")))))
        (is (= :reserved-record-kind (t/error-code #(schema/control-record! :run {}))))
        (with-redefs [store/put-world! (fn [& _] (throw (ex-info "disk unavailable" {})))]
          (is (thrown? clojure.lang.ExceptionInfo (world/create! i (t/assembly)))))
        (is (= before @(:live i)))))))
