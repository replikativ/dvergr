(ns dvergr.agent.verifiers
  "Process-local registry of trusted evaluation capabilities.

   An EnvironmentDef names its verifier and world setup by exact reference
   and never contains them. This registry is where a host resolves those
   references: benchmark providers register their `Evaluator` and
   `WorldSetup` capabilities here, and `evaluators` / `world-setups` return
   the capability maps `dvergr.agent.experiment/run` takes.

   Capabilities are host functions, so the registry is process-local and is
   never exposed to SCI. Registering is idempotent for the same capability
   and refuses to replace a reference with a different one: a reference is
   an identity, and silently rebinding it would change what recorded
   verdicts mean."
  (:require [dvergr.agent.evaluation :as evaluation]))

(defonce ^:private registry (atom {:evaluators {} :world-setups {}}))

(defn- register! [kind ref capability]
  (swap! registry
         (fn [r]
           (let [existing (get-in r [kind ref])]
             (when (and existing (not (identical? existing capability)))
               (throw (ex-info "Reference is already bound to another capability"
                               {:type ::reference-conflict :kind kind :ref ref})))
             (assoc-in r [kind ref] capability))))
  ref)

(defn register-evaluator!
  "Register `evaluator` (from `evaluation/make-evaluator`) under its verifier
   reference. Returns the reference."
  [evaluator]
  (when-not (instance? dvergr.agent.evaluation.Evaluator evaluator)
    (throw (ex-info "Not an Evaluator capability" {:type ::invalid-evaluator})))
  (register! :evaluators (evaluation/evaluator-ref evaluator) evaluator))

(defn register-world-setup!
  "Register `setup` (from `evaluation/make-world-setup`) under its setup
   reference. Returns the reference."
  [setup]
  (when-not (instance? dvergr.agent.evaluation.WorldSetup setup)
    (throw (ex-info "Not a WorldSetup capability" {:type ::invalid-world-setup})))
  (register! :world-setups (evaluation/world-setup-ref setup) setup))

(defn evaluator
  "The Evaluator bound to verifier reference `ref`, or nil."
  [ref]
  (get-in @registry [:evaluators ref]))

(defn world-setup
  "The WorldSetup bound to setup reference `ref`, or nil."
  [ref]
  (get-in @registry [:world-setups ref]))

(defn tier
  "Trust tier of the verifier bound to `ref`, or nil when unregistered."
  [ref]
  (some-> (evaluator ref) evaluation/evaluator-tier))

(defn evaluators
  "`{verifier-ref Evaluator}`: the `evaluators` argument of `experiment/run`."
  []
  (:evaluators @registry))

(defn world-setups
  "`{setup-ref WorldSetup}`: the `:world-setups` option of `experiment/run`."
  []
  (:world-setups @registry))

(defn unregister!
  "Remove the capabilities bound to `ref` (tests, reloading)."
  [ref]
  (swap! registry #(-> % (update :evaluators dissoc ref) (update :world-setups dissoc ref)))
  nil)
