(ns dvergr.runtime.ctx-test
  "Which world a stable handle acts in: its owner, a fork of it, and never the
  owner from a fresh inference world."
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.runtime.ctx :as runtime-ctx]
            [org.replikativ.foerster.core :as infer]
            [org.replikativ.foerster.dist :as dist]
            [org.replikativ.foerster.effects :refer [sample]]
            [org.replikativ.foerster.measure :as m]
            [org.replikativ.spindel.effects.await :refer [await]]
            [org.replikativ.spindel.effects.savepoint :as savepoint]
            [org.replikativ.spindel.engine.context :as context]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.spin.cps :refer [spin]]))

(defn- messages [e] (loop [e e acc []] (if e (recur (ex-cause e) (conj acc (ex-message e))) acc)))

(deftest session-owning-ancestor-is-not-a-fresh-inference-world
  (let [parent (context/create-execution-context)
        parent-session (savepoint/open! parent {:seed 1})
        owner (context/fork-context parent :mode :frozen)
        sibling (context/fork-context parent :mode :frozen)
        unrelated (context/create-execution-context)
        unrelated-session (savepoint/open! unrelated {:seed 2})]
    (try
      (binding [ec/*execution-context* parent]
        (is (identical? owner (runtime-ctx/selected-context owner))
            "a coordinator retains the child-owned handle, not its own projection"))
      (binding [ec/*execution-context* owner]
        (is (identical? owner (runtime-ctx/selected-context parent))))
      (binding [ec/*execution-context* unrelated]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cannot reach room state"
                              (runtime-ctx/selected-context owner))))
      (binding [ec/*execution-context* sibling]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cannot reach room state"
                              (runtime-ctx/selected-context owner))))
      (finally
        (doseq [session [parent-session unrelated-session]]
          (let [done (promise)]
            ((savepoint/close! session) (fn [_] (deliver done :closed)) #(deliver done %))
            (is (= :closed (deref done 5000 ::timeout)))))
        (context/close-context! owner)
        (context/close-context! sibling)
        (context/close-context! unrelated)
        (context/close-context! parent)))))

(defn- run [owner opts]
  (binding [ec/*execution-context* owner]
    (try
      (let [model (spin (sample (dist/normal 0.0 1.0))
                        (identical? owner (runtime-ctx/selected-context owner)))]
        {:values (mapv (comp m/get-value first) (m/get-particles @(spin (await (infer/infer model opts)))))})
      (catch Exception e {:error (messages e)}))))

(deftest a-fresh-inference-world-cannot-reach-the-owner
  (let [owner (context/create-execution-context)]
    (try
      (doseq [opts [{:method :smc :particles 2} {:method :mh :iterations 2 :chains 1}]]
        (let [{:keys [error]} (run owner opts)]
          (is (some #(re-find #"cannot reach room state" (str %)) error) (str (:method opts) " " error))))
      (testing "a canonical fork selects itself"
        (is (= {:values [false false]} (run owner {:method :smc :particles 2 :world-policy :fork}))))
      (testing "an unrelated plain context still gets the owner"
        (let [other (context/create-execution-context)]
          (try (binding [ec/*execution-context* other]
                 (is (identical? owner (runtime-ctx/selected-context owner))))
               (finally (context/close-context! other)))))
      (finally (context/close-context! owner)))))
