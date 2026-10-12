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
            [org.replikativ.spindel.engine.context :as context]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.spin.cps :refer [spin]]))

(defn- messages [e] (loop [e e acc []] (if e (recur (ex-cause e) (conj acc (ex-message e))) acc)))

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
