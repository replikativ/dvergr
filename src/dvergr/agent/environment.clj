(ns dvergr.agent.environment
  "Portable, content-addressed definitions for verified agent environments.

   An EnvironmentDef describes what may be attempted and names trusted verifier
   code. It never embeds the verifier function, a live world, or a Run handle.
   The content ID identifies this exact definition; individual attempts retain
   fresh Run identities."
  (:require [dvergr.agent.roster :as roster]
            [hasch.core :as hasch]))

(def ^:private allowed-keys
  #{:id :version :task :verifier :limits :world :metadata})

(def ^:private definition-keys
  #{:environment/id :environment/version :environment/task
    :environment/verifier :environment/limits :environment/world
    :environment/metadata :environment/content-id})

(def ^:private required-definition-keys
  #{:environment/id :environment/version :environment/task
    :environment/verifier :environment/limits :environment/world
    :environment/content-id})

(defn- invalid! [message type data]
  (throw (ex-info message (assoc data :type type))))

(defn- positive-version! [label version]
  (when-not (and (integer? version) (pos? version))
    (invalid! (str label " must be a positive integer")
              ::invalid-version
              {:label label :version version}))
  version)

(defn- verifier-ref! [verifier]
  (when-not (map? verifier)
    (invalid! "Environment :verifier must be a reference map"
              ::invalid-verifier {:verifier verifier}))
  (when-let [unknown (seq (remove #{:id :version :basis} (keys verifier)))]
    (invalid! "Environment :verifier contains unknown keys"
              ::unknown-verifier-keys {:unknown (set unknown)}))
  (let [{:keys [id version basis]} verifier]
    (when-not (keyword? id)
      (invalid! "Verifier :id must be a keyword"
                ::invalid-verifier-id {:id id}))
    (positive-version! "Verifier :version" (or version 1))
    (when-not (roster/data-value? basis)
      (invalid! "Verifier :basis must contain only portable data"
                ::non-portable-verifier-basis {:basis basis}))
    (cond-> {:verifier/id id
             :verifier/version (or version 1)}
      basis (assoc :verifier/basis basis))))

(defn make-environment
  "Construct a portable, content-addressed EnvironmentDef.

   Required options are keyword `:id`, portable `:task`, and `:verifier` as
   `{:id keyword :version positive-int}`. Optional `:basis` identifies immutable
   verifier source. `:limits`, `:world`, and `:metadata` are portable policy
   data interpreted by the runner."
  [{:keys [id version task verifier limits world metadata]
    :or {version 1 limits {} world {}}
    :as opts}]
  (when-let [unknown (seq (remove allowed-keys (keys opts)))]
    (invalid! "Environment contains unknown keys"
              ::unknown-environment-keys
              {:unknown (set unknown) :allowed allowed-keys}))
  (when-not (keyword? id)
    (invalid! "Environment :id must be a keyword"
              ::invalid-environment-id {:id id}))
  (positive-version! "Environment :version" version)
  (when-not (contains? opts :task)
    (invalid! "Environment requires :task"
              ::missing-task {:environment/id id}))
  (doseq [[label value] [[:task task] [:limits limits] [:world world]
                         [:metadata metadata]]]
    (when-not (roster/data-value? value)
      (invalid! (str "Environment " label " must contain only portable data")
                ::non-portable-data {:label label :value value})))
  (when-not (map? limits)
    (invalid! "Environment :limits must be a map"
              ::invalid-limits {:limits limits}))
  (when-not (map? world)
    (invalid! "Environment :world must be a map"
              ::invalid-world {:world world}))
  (when (and metadata (not (map? metadata)))
    (invalid! "Environment :metadata must be a map"
              ::invalid-metadata {:metadata metadata}))
  (let [definition (cond-> {:environment/id id
                            :environment/version version
                            :environment/task task
                            :environment/verifier (verifier-ref! verifier)
                            :environment/limits limits
                            :environment/world world}
                     metadata (assoc :environment/metadata metadata))]
    (assoc definition
           :environment/content-id
           (hasch/uuid [:dvergr/environment-definition definition]))))

(defn validate-environment
  "Validate that `environment` is canonical portable data and that its content
   ID still names its exact current value. Returns the unchanged definition."
  [environment]
  (when-not (map? environment)
    (invalid! "EnvironmentDef must be a map"
              ::invalid-definition {:value environment}))
  (when-let [unknown (seq (remove definition-keys (keys environment)))]
    (invalid! "EnvironmentDef contains unknown canonical keys"
              ::unknown-definition-keys {:unknown (set unknown)}))
  (when-let [missing (seq (remove #(contains? environment %)
                                  required-definition-keys))]
    (invalid! "EnvironmentDef is missing canonical keys"
              ::missing-definition-keys {:missing (set missing)}))
  (when-not (roster/data-value? environment)
    (invalid! "EnvironmentDef must contain only portable data"
              ::non-portable-data {:value environment}))
  (let [claimed (:environment/content-id environment)
        content (dissoc environment :environment/content-id)
        actual (hasch/uuid [:dvergr/environment-definition content])]
    (when-not (= claimed actual)
      (invalid! "EnvironmentDef content ID does not match its content"
                ::content-id-mismatch {:claimed claimed :actual actual})))
  (let [verifier (:environment/verifier environment)
        spec (cond-> {:id (:environment/id environment)
                      :version (:environment/version environment)
                      :task (:environment/task environment)
                      :verifier (cond-> {:id (:verifier/id verifier)
                                         :version (:verifier/version verifier)}
                                  (contains? verifier :verifier/basis)
                                  (assoc :basis (:verifier/basis verifier)))
                      :limits (:environment/limits environment)
                      :world (:environment/world environment)}
               (contains? environment :environment/metadata)
               (assoc :metadata (:environment/metadata environment)))]
    (when-not (= environment (make-environment spec))
      (invalid! "EnvironmentDef is not in canonical form"
                ::non-canonical-definition {:value environment})))
  environment)

(defn environment-ref
  "Stable reference to one exact EnvironmentDef."
  [environment]
  (select-keys (validate-environment environment)
               [:environment/id :environment/version :environment/content-id]))
