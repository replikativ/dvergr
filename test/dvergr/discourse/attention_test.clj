(ns dvergr.discourse.attention-test
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.discourse.attention :as attention]
            [org.replikativ.spindel.core :as sp]
            [org.replikativ.spindel.engine.context :as context]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.engine.impl.simple :as engine]))

(deftest decisions-keep-observation-activation-and-control-independent
  (is (= {:memory :remember
          :activation :none
          :control :continue
          :at :next-safe-boundary
          :priority 0
          :reason :test/passive}
         (attention/observe :test/passive)))
  (is (= :enqueue (:activation (attention/enqueue :test/later))))
  (is (= :quiescent (:at (attention/enqueue :test/later))))
  (is (= :include (:memory (attention/restart :test/correction))))
  (is (= :restart (:control (attention/restart :test/correction)))))

(deftest former-actions-lift-and-project-through-the-compatibility-boundary
  (is (= :steer (attention/legacy-action :steer)))
  (is (= :queue (attention/legacy-action :queue)))
  (is (= :observe (attention/legacy-action :observe)))
  (is (= :steer (attention/legacy-action
                 (attention/restart :test/structured))))
  (is (nil? (attention/legacy-action
             (attention/decision {:activation :wake})))
      "valid future decisions remain explicit instead of acquiring guessed semantics")
  (is (nil? (attention/legacy-action
             (attention/decision {:memory :ignore
                                  :control :restart})))
      "restart does not silently discard the requested memory semantics")
  (is (nil? (attention/legacy-action
             (attention/decision {:control :restart :at :quiescent})))
      "restart does not silently discard the requested boundary")
  (is (nil? (attention/legacy-action
             (attention/decision {:control :restart :priority 10})))
      "the compatibility executor does not pretend to implement priority"))

(deftest malformed-decisions-fail-at-the-pure-boundary
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown keys"
                        (attention/decision {:wake? true})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"control mode"
                        (attention/decision {:control :explode})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"priority"
                        (attention/decision {:priority :high})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"reason"
                        (attention/decision {:reason "because"}))))

(deftest execution-boundaries-are-provider-neutral-data
  (is (= {:attention.boundary/type :after-tool
          :attention.boundary/data {:tool-call :search}}
         (attention/boundary-event :after-tool {:tool-call :search})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown execution boundary"
                        (attention/boundary-event :provider/private-step))))

(deftest attention-composes-reactively-with-execution-boundaries
  (testing "continuous observation can defer integration until a safe boundary"
    (let [ctx (context/create-execution-context)]
      (try
        (binding [ec/*execution-context* ctx]
          (let [incoming (sp/signal nil)
                boundary (sp/signal :before-model)
                plan (sp/spin
                      (let [message (:new (sp/track incoming))
                            at (:new (sp/track boundary))]
                        (cond
                          (nil? message) (attention/observe :test/idle)
                          (= :urgent (:force message))
                          (attention/decision {:memory :include
                                               :control :cancel
                                               :at :now
                                               :reason :test/urgent})
                          (= :after-tool at)
                          (attention/decision {:memory :include
                                               :control :integrate
                                               :at :after-tool
                                               :reason :test/boundary})
                          :else (attention/observe :test/deferred))))]
            (is (= :continue (:control @plan)))
            (reset! incoming {:force :ordinary})
            (engine/await-drain-complete! ctx :timeout-ms 1000)
            (is (= :continue (:control @plan)) "message is retained but does not preempt")
            (reset! boundary :after-tool)
            (engine/await-drain-complete! ctx :timeout-ms 1000)
            (is (= :integrate (:control @plan)) "same fact integrates at the boundary")
            (reset! incoming {:force :urgent})
            (engine/await-drain-complete! ctx :timeout-ms 1000)
            (is (= :cancel (:control @plan)) "authorized urgency can preempt immediately")))
        (finally
          (context/close-context! ctx))))))
