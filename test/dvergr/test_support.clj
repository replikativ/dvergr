(ns dvergr.test-support
  "Small serial fixtures; frozen forks publish through the production descriptor writer."
  (:require [clojure.java.io :as io]
            [datahike.api :as d]
            [dvergr.room :as room]
            [dvergr.store :as store]
            [dvergr.world :as world]
            [dvergr.world.sci :as sci]
            [geschichte.repo :as repo]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.yggdrasil :as y]
            [yggdrasil.protocols :as p]))

(defn error-code [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo e
         (or (:dvergr/error (ex-data e))
             (some-> (.getCause e) ex-data :dvergr/error)))))

(defn control-config []
  {:installation-id (random-uuid) :control-store-id (random-uuid) :test? true
   :database {:store {:backend :memory :id (random-uuid)}}})

(defn work-connection
  ([] (work-connection {}))
  ([config]
   (let [config (merge {:store {:backend :memory :id (random-uuid)}
                        :schema-flexibility :write :keep-history? true :commit-graph? true
                        :writer {:backend :self :writer-ownership :exclusive}}
                       config)]
     (d/create-database config)
     (let [conn (d/connect config)]
       (d/transact conn [{:db/ident :item/value :db/valueType :db.type/string
                          :db/cardinality :db.cardinality/one}
                         {:db/ident :control/payload :db/valueType :db.type/string
                          :db/cardinality :db.cardinality/one}])
       conn))))

(defn system-spec [id conn]
  {:id id :connection conn :adapter :datahike :ownership :owned
   :operations #{:datahike/pull :datahike/transact} :attributes #{:item/value :control/payload}})

(defn assembly
  ([] (assembly []))
  ([systems]
   {:systems (vec systems) :injections {:bindings {} :operations {}}
    :effect-policy :unavailable-in-slice-1 :resource-authority :unbound}))

(defn work-assembly [specs]
  (assoc-in (assembly specs) [:injections :operations]
            {'work {'read {:system (:id (first specs)) :operation :datahike/pull}
                    'write {:system (:id (first specs)) :operation :datahike/transact}}}))

(defn frozen!
  ([installation parent] (frozen! installation parent {}))
  ([installation parent {:keys [systems sci? snapshots] :or {sci? true}}]
   (world/live! installation parent)
   (let [selected (or systems (set (keys (:systems parent))))
         opts (cond-> {:mode :frozen :systems selected :sync? true
                       :forkable-components (if (and sci? (:sci-ref parent))
                                              #{(:id (:sci-ref parent))} #{})}
                snapshots (assoc :snapshots snapshots))
         handle (binding [ec/*execution-context* (:context parent)] (y/fork! opts))]
     (try
       (let [specs (binding [ec/*execution-context* (:child-ctx handle)]
                     (into {} (map (fn [[id spec]]
                                     [id (assoc-in spec [:manifest :basis]
                                                   (p/snapshot-id (y/system id)))]))
                           (select-keys (:systems parent) selected)))]
         (world/publish! installation (:child-ctx handle) (world/descriptor installation parent)
                         specs (when sci? (:sci-ref parent)) (y/fork-descriptor handle) handle))
       (catch Throwable e
         (binding [ec/*execution-context* (:context parent)] (y/discard-fork! handle {:sync? true}))
         (throw e))))))

(defn release! [installation world]
  (doseq [child (filter #(= (get-in world [:descriptor :world/id])
                            (get-in % [:descriptor :world/source])) (vals @(:live installation)))]
    (release! installation child))
  (if-let [handle (:fork-handle world)]
    (do (binding [ec/*execution-context* (:parent-ctx handle)]
          (y/discard-fork! handle {:sync? true}))
        (swap! (:live installation) dissoc (get-in world [:descriptor :world/id])))
    (world/close! installation world)))

(defn with-installation [f]
  (let [control (store/open! (control-config))
        installation (world/installation control)]
    (try (f installation)
         (finally
           (doseq [root (filter #(nil? (get-in % [:descriptor :world/source]))
                                (vals @(:live installation)))]
             (release! installation root))
           (store/close! control)))))

(defn evaluate [installation world source] (sci/eval! installation world source))

(defn geschichte-spec [id]
  (let [conn (work-connection)]
    (repo/init! conn)
    (assoc (system-spec id conn) :adapter :geschichte :operations #{} :attributes #{})))

(defn process-identity []
  [(.pid (java.lang.ProcessHandle/current))
   (.getStartTime (java.lang.management.ManagementFactory/getRuntimeMXBean))])

(defn persist-process-evidence! [directory]
  (.mkdirs (io/file directory))
  (let [config (assoc (control-config) :test? false
                      :database {:store {:backend :file :id (random-uuid) :path (str directory "/control")}})
        control (store/open! config)
        i (world/installation control)]
    (try
      (let [r (room/create! i {:id (random-uuid) :kind :root
                               :assembly (assembly [(system-spec :work (work-connection))])})
            root (room/world i r)
            a (frozen! i root)
            b (frozen! i a)
            sibling (frozen! i root)
            records (mapv #(world/descriptor i %) [root a b sibling])
            evidence {:config config :records records :room (:record r)
                      :writer-process (process-identity)}]
        (spit (io/file directory "evidence.edn") (pr-str evidence))
        (release! i root)
        evidence)
      (finally (store/close! control)))))
