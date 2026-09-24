(ns dvergr.agent.evaluators
  "The trusted Evaluators and WorldSetups this host offers by reference.

   An EnvironmentDef names its verifier (and world setup) by portable ref;
   the capability that scores it is host code. Agent code can build an
   Environment and an Experiment as data, but cannot hand the host a scoring
   function: an experiment an agent starts is scored only by what is
   registered here (the catalog workflows' checkers, the workflow completion
   check), and one naming anything else is refused before any Run starts."
  (:require [dvergr.agent.evaluation :as evaluation]))

(defonce ^:private registry (atom {:evaluators {} :world-setups {}}))

(defn register-evaluator!
  "Offer `evaluator` (an Evaluator capability) under its verifier ref."
  [evaluator]
  (swap! registry assoc-in [:evaluators (evaluation/evaluator-ref evaluator)] evaluator)
  evaluator)

(defn register-world-setup!
  "Offer `setup` (a WorldSetup capability) under its ref."
  [setup]
  (swap! registry assoc-in [:world-setups (evaluation/world-setup-ref setup)] setup)
  setup)

(defn evaluator [ref] (get-in @registry [:evaluators ref]))

(defn offered
  "The refs on offer: `{:evaluators [ref ...] :world-setups [ref ...]}`."
  []
  {:evaluators (vec (keys (:evaluators @registry)))
   :world-setups (vec (keys (:world-setups @registry)))})
(defn world-setup [ref] (get-in @registry [:world-setups ref]))

(defn- environments [experiment]
  (get-in experiment [:experiment/dataset :dataset/environments]))

(defn capabilities-for
  "The host capabilities `experiment` needs: `{:evaluators {ref Evaluator}
   :world-setups {ref WorldSetup}}` for its environments' refs. Throws naming
   every ref this host does not offer."
  [experiment]
  (let [envs (environments experiment)
        verifier-refs (into #{} (map :environment/verifier) envs)
        setup-refs (into #{} (keep #(get-in % [:environment/world :setup])) envs)
        evaluators (into {} (keep (fn [r] (when-let [e (evaluator r)] [r e]))) verifier-refs)
        setups (into {} (keep (fn [r] (when-let [s (world-setup r)] [r s]))) setup-refs)
        missing (concat (remove evaluators verifier-refs) (remove setups setup-refs))]
    (when (seq missing)
      (throw (ex-info "The experiment names verifiers or world setups this host does not offer"
                      {:type ::unknown-capability :missing (vec missing)
                       :offered {:evaluators (vec (keys (:evaluators @registry)))
                                 :world-setups (vec (keys (:world-setups @registry)))}})))
    {:evaluators evaluators :world-setups setups}))
