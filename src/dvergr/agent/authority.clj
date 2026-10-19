(ns dvergr.agent.authority
  "Host-owned, portable delegation bounds shared by every admission adapter."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [dvergr.agent.run :as run]
            [dvergr.sandbox.extensions :as extensions]))

(def host-option-keys [:experiment-capabilities :delegation-policy])

(defn validate-policy! [policy]
  (when-not
   (and (map? policy)
        (every? #{:tools :sandboxes :providers :program-kinds :max-attempts :max-parallelism} (keys policy))
        (every? #(and (set? (get policy %)) (every? keyword? (get policy %))) [:tools :providers])
        (set? (:sandboxes policy))
        (every? #(and (set? %) (every? qualified-keyword? %)) (:sandboxes policy))
        (or (not (contains? policy :program-kinds))
            (and (set? (:program-kinds policy))
                 (every? #{:echo :scripted :llm :sci} (:program-kinds policy))))
        (every? (fn [[key maximum]]
                  (or (not (contains? policy key))
                      (and (pos-int? (get policy key)) (<= (get policy key) maximum))))
                [[:max-attempts 256] [:max-parallelism 16]]))
    (throw (ex-info "Invalid host delegation policy" {:type ::invalid-policy})))
  policy)

(defn- tool-name [k] (str/replace (name k) "-" "_"))

(defn check-agent!
  "Check before any Run, world or wallet admission. Missing policy preserves
   trusted legacy callers; an explicit policy must enumerate all three grants."
  [agent host-options]
  (when (contains? host-options :delegation-policy)
    (let [{:keys [tools sandboxes providers] :as policy}
          (validate-policy! (:delegation-policy host-options))]
      (when-not (and (set/subset? (into #{} (map tool-name) (:agent/tools agent))
                                  (into #{} (map tool-name) tools))
                     (contains? sandboxes (set (:agent/sandbox agent)))
                     (contains? (get policy :program-kinds #{:echo :scripted :llm})
                                (get-in agent [:agent/program :kind]))
                     (or (not= :llm (get-in agent [:agent/program :kind]))
                         (contains? providers (get-in agent [:agent/model-policy :provider]))))
        (throw (ex-info "Agent exceeds the host delegation policy"
                        {:type ::exceeds-policy :agent/id (:agent/id agent)})))))
  agent)

(defn inputs
  "Portable policy plus exact offered extension refs, never installer functions."
  [policy]
  (validate-policy! policy)
  (assoc policy :sandbox-refs (extensions/refs (reduce set/union #{} (:sandboxes policy)))))

(defn require-scoped-run!
  "Exact same-store ancestry, independent of bounded observation/history lists.
   Nil scope is deliberately not a room-operator grant at this boundary."
  [room scope id]
  (when-not (and (uuid? scope) (uuid? id))
    (throw (ex-info "An ambient Run and target Run UUID are required" {:type ::invalid-scope})))
  (loop [candidate id seen #{}]
    (when (or (nil? candidate) (contains? seen candidate) (>= (count seen) 1024))
      (throw (ex-info "Run is outside the current Run subtree" {:type ::outside-scope})))
    (let [r (run/run room candidate)]
      (when-not r
        (throw (ex-info "Run is outside the current Run subtree" {:type ::outside-scope})))
      (if (= candidate scope)
        (run/run room id)
        (recur (:run/parent r) (conj seen candidate))))))
