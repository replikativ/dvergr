(ns dvergr.world
  "Root assembly and durable publication; production snapshots await prerequisites."
  (:require [datahike.api :as d]
            [dvergr.store :as store]
            [dvergr.value :as v]
            [dvergr.world.sci :as sci]
            [dvergr.world.systems :as systems]
            [org.replikativ.spindel.engine.context :as context]))

(def ^:private versions
  {:spindel "0.1.148" :sci "0.15.59-replikativ.5" :yggdrasil "0.3.52"
   :datahike "0.8.1910" :kontor "0.1.40" :geschichte "0.1.25"})

(defn installation
  "Create a host-owned live directory for this explicit control capability."
  [control]
  (systems/installation control))

(defn ^:no-doc live! [installation world]
  (when-not (identical? world (get @(:live installation) (get-in world [:descriptor :world/id])))
    (v/fail! :world-unavailable {}))
  world)

(defn descriptor
  "Return the immutable published assembly record."
  [installation world]
  (:descriptor (live! installation world)))

(defn ^:no-doc publish!
  "Shared publication path for roots and upstream frozen test fixtures."
  [installation ctx source systems sci-ref upstream fork-handle]
  (let [id (context/get-fork-id ctx)
        record {:world/id id :world/source (:world/id source)
                :world/root (or (:world/root source) id)
                :world/relation (if source :test-frozen-fork :root)
                :assembly/version 1
                :assembly/control-store (:control-store/id (store/info (:control installation)))
                :assembly/upstream-versions versions
                :assembly/spindel-descriptor upstream
                :assembly/cut (when source {:kind :uncoordinated-frozen :id nil :p1? false})
                :assembly/claims #{:selected-world-resolution}
                :assembly/components
                (cond-> (into {} (map (fn [[id spec]] [id (:manifest spec)])) systems)
                  sci-ref (assoc (:id sci-ref) {:classification :snapshotted :mechanism :sci-forkable
                                                :tier nil :classifier :dvergr.injection/v1}))
                :assembly/effect-policy :unavailable-in-slice-1 :assembly/resource-authority :unbound}
        world {:context ctx :descriptor record :systems systems :sci-ref sci-ref
               :fork-handle fork-handle :active (atom 0)}]
    (store/put-world! (:control installation) record)
    (swap! (:live installation) assoc id world)
    world))

(defn fork!
  "Refuse unsupported guarantees before acquiring any native branch."
  [installation world request]
  (live! installation world)
  (let [requested (or (:tier request) (:mode request))]
    (case requested
      (:snapshot :hot :warm)
      (v/fail! :missing-prerequisite {:prerequisite (case requested :hot :P3 :warm :P6 :P1)})
      (v/fail! :unsupported-mode {}))))

(defn- validate-assembly! [installation spec]
  (when-not (and (= #{:systems :injections :effect-policy :resource-authority} (set (keys spec)))
                 (vector? (:systems spec))
                 (= :unavailable-in-slice-1 (:effect-policy spec))
                 (= :unbound (:resource-authority spec)))
    (v/fail! :unsupported-guarantee {}))
  (let [ids (mapv :id (:systems spec))]
    (when-not (= (count ids) (count (set ids))) (v/fail! :duplicate-system {})))
  (doseq [system (:systems spec)] (systems/validate-spec! (:control installation) system))
  (sci/validate-manifest! (:injections spec))
  (let [systems (into {} (map (juxt :id identity)) (:systems spec))]
    (doseq [[_ names] (get-in spec [:injections :operations])
            [_ {:keys [system operation]}] names]
      (when-not (contains? systems system) (v/fail! :system-unavailable {}))
      (when-not (contains? (:operations (get systems system)) operation)
        (v/fail! :unsupported-operation {}))))
  spec)

(defn create!
  "Assemble an independent root and persist its descriptor before live publication."
  [installation spec]
  (validate-assembly! installation spec)
  (locking (:lock installation)
    (let [ctx (context/create-execution-context)
          assembly {:context ctx :control (:control installation) :installation installation
                    :systems (atom {}) :sci-ref (atom nil) :published (atom false)}]
      (try
        (doseq [system (:systems spec)] (systems/register! assembly system))
        (sci/install! assembly (:injections spec))
        (let [world (publish! installation ctx nil @(:systems assembly) @(:sci-ref assembly) nil nil)]
          (reset! (:published assembly) true)
          world)
        (catch Throwable e
          (try
            (context/close-context! ctx)
            (doseq [{:keys [connection ownership]} (:systems spec) :when (= :owned ownership)]
              (d/release connection))
            (catch Throwable cleanup
              (throw (ex-info "Assembly cleanup unresolved"
                              {:dvergr/error :unresolved-acquisition} cleanup))))
          (throw e))))))

(defn selected!
  "Select only a recognised bound world descended from the explicit anchor."
  [installation anchor]
  (systems/selected! installation (:world/id (descriptor installation anchor))))

(defn close!
  "Close an idle root after its owned children have been settled by their owner."
  [installation world]
  (locking (:lock installation)
    (live! installation world)
    (when (or (seq (get @(:owners installation) (get-in world [:descriptor :world/id])))
              (pos? @(:active world)) (:fork-handle world)
              (some #(= (get-in world [:descriptor :world/id])
                        (get-in % [:descriptor :world/source])) (vals @(:live installation))))
      (v/fail! :world-busy {}))
    (context/close-context! (:context world))
    (doseq [{:keys [connection ownership]} (vals (:systems world)) :when (= :owned ownership)]
      (d/release connection))
    (swap! (:live installation) dissoc (get-in world [:descriptor :world/id]))
    nil))
