(ns dvergr.sandbox.random-test
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.sandbox :as sandbox]
            [org.replikativ.spindel.engine.context :as ctx]))

(defn- value [interpreter runtime code]
  (let [r (sandbox/eval-code interpreter code :execution-context runtime)]
    (is (:success r) (pr-str (select-keys r [:error :stderr])))
    (:value r)))

(defn- refused? [interpreter runtime code]
  (not (:success (sandbox/eval-code interpreter code :execution-context runtime))))

(defn check-random [interpreter runtime]
  (testing "the same seed draws the same numbers"
    (is (= (value interpreter runtime "(let [r (java.util.Random. 42)] [(.nextInt r 100) (.nextDouble r) (.nextGaussian r)])")
           (value interpreter runtime "(let [r (java.util.Random. 42)] [(.nextInt r 100) (.nextDouble r) (.nextGaussian r)])")))
    (is (= (value interpreter runtime "(let [r (java.util.SplittableRandom. 7)] (vec (repeatedly 3 #(.nextDouble r))))")
           (value interpreter runtime "(let [r (java.util.SplittableRandom. 7)] (vec (repeatedly 3 #(.nextDouble r))))"))))
  (testing "split streams are repeatable and differ from their parent"
    (let [code "(let [r (java.util.SplittableRandom. 7) a (.split r)] [(.nextLong r) (.nextLong a)])"
          [parent child] (value interpreter runtime code)]
      (is (= [parent child] (value interpreter runtime code)))
      (is (not= parent child))))
  (testing "draws only: no streams, no SecureRandom"
    (is (refused? interpreter runtime "(.doubles (java.util.Random. 1))"))
    (is (refused? interpreter runtime "(.ints (java.util.SplittableRandom. 1))"))
    (is (refused? interpreter runtime "(java.security.SecureRandom.)"))
    (is (refused? interpreter runtime "(java.util.Random/from (java.util.SplittableRandom. 1))"))
    (is (refused? interpreter runtime "(let [f java.util.Random/from] (f (java.util.SplittableRandom. 1)))")
        "a static method taken as a value obeys the closed class")))

(deftest seeded-random-in-session-and-managed-world
  (let [runtime (ctx/create-execution-context)]
    (try
      (testing "ordinary session"
        (check-random (sandbox/fork-for-session runtime) runtime))
      (testing "managed world"
        (let [ref (sandbox/create-spindel-sci-world! runtime)
              interpreter (sandbox/sci-context-in runtime ref)]
          (sandbox/setup-agent-namespaces! interpreter runtime)
          (check-random interpreter runtime)))
      (finally (ctx/close-context! runtime)))))
