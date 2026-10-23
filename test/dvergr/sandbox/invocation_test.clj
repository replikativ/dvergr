(ns dvergr.sandbox.invocation-test
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.sandbox :as sandbox]
            [org.replikativ.spindel.core :as sp]
            [org.replikativ.spindel.effects.await :refer [await]]
            [org.replikativ.spindel.engine.context :as context]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.engine.impl.simple :as simple]
            [org.replikativ.spindel.spin.core :as spin-core]
            [sci.core :as sci])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(defn- hold! [^CountDownLatch latch]
  (when-not (.await latch 30 TimeUnit/SECONDS)
    (throw (ex-info "Finite test hold expired" {}))))

(deftest inherited-drain-marker-is-not-an-actual-worker-drain
  (let [world (context/create-execution-context)
        interpreter (sandbox/create-spindel-sci-ctx world)]
    (try
      (let [handle (binding [simple/*in-drain?* true]
                     (sandbox/start-evaluation!
                      "(require '[org.replikativ.spindel.spin.cps :refer [spin]]) @(spin (+ 2 3))"
                      {:execution-context world :timeout-ms 30000
                       :with-interpreter (fn [evaluate _]
                                           (is (false? (simple/on-drain-thread?)))
                                           (is (false? simple/*in-drain?*))
                                           (evaluate interpreter))}))
            result (sandbox/await-evaluation! handle)]
        (is (:success result) (pr-str result))
        (is (= 5 (:value result)))
        (is (sandbox/join-evaluation! handle 30000)))
      (finally (context/close-context! world)))))

(deftest ordinary-legacy-result-maps-retain-their-original-shape
  (let [interpreter (sandbox/create-base-ctx)]
    (doseq [options [[] [:timeout-ms 30000]]]
      (is (= {:value 42 :stdout "" :stderr "" :success true}
             (dissoc (apply sandbox/eval-code interpreter "(+ 20 22)" options) :meter)))
      (is (= {:value "42" :stdout "" :stderr "" :success true}
             (dissoc (apply sandbox/eval-code interpreter "(+ 20 22)" (concat options [:realize? true])) :meter))))))

(deftest owned-evaluation-realizes-and-bounds-output-before-releasing-owner
  (let [owned? (atom false) interpreter (sandbox/create-base-ctx)
        handle (sandbox/start-evaluation!
                "(println \"abcdefghijklmnop\") (range 20)"
                {:realize? true :output-limit 8 :timeout-ms 30000
                 :with-interpreter
                 (fn [evaluate control]
                   (reset! owned? true)
                   (try
                     ((:check! control))
                     (let [r (evaluate interpreter)]
                       (is ((:entered? control)))
                       r)
                     (finally (reset! owned? false))))})
        result (sandbox/await-evaluation! handle)]
    (is (:success result))
    (is (= "abcdefgh" (:stdout result)))
    (is (:stdout-truncated? result))
    (is (:value-truncated? result))
    (is (= "(0 1 2 3" (:value result)))
    (is (:worker-finished? result))
    (is (false? @owned?))
    (is (sandbox/join-evaluation! handle 30000))))

(deftest captured-evaluation-callback-is-sealed-and-never-enters-later
  (let [saved (promise) interpreter (sandbox/create-base-ctx)
        handle (sandbox/start-evaluation! "(def should-not-exist 1)"
                                          {:with-interpreter (fn [evaluate _] (deliver saved evaluate) {:success false})})]
    (is (false? (:success (sandbox/await-evaluation! handle))))
    (is (sandbox/join-evaluation! handle 30000))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no longer owned" (@saved interpreter)))
    (is (nil? (get-in (sci/eval-string* interpreter "(ns-publics 'user)") ['should-not-exist])))))

(deftest interrupt-cancels-awaited-spin-before-sci-can-catch-and-continue
  (let [world (context/create-execution-context)
        interpreter (sandbox/create-base-ctx :resource-limits nil)
        spin-entered (promise) spin-cleaned (promise) caught (promise)
        release (CountDownLatch. 1) handle (atom nil)
        task (binding [ec/*execution-context* world]
               (sp/spin (try
                          (deliver spin-entered true)
                          (await (sp/sleep 60000))
                          (finally (deliver spin-cleaned true)))))]
    (sci/merge-opts interpreter {:namespaces {'probe {'task (fn [] task)
                                                      'caught (fn [] (deliver caught true) (hold! release))}}})
    (try
      (reset! handle (sandbox/start-evaluation!
                      "(try @(probe/task) (catch Throwable _ (probe/caught)))"
                      {:execution-context world :timeout-ms 30000
                       :with-interpreter (fn [evaluate _] (evaluate interpreter))}))
      (is (= true (deref spin-entered 30000 ::expired)))
      (sandbox/cancel-evaluation! @handle)
      (is (= true (deref caught 30000 ::expired)))
      (is (= true (deref spin-cleaned 30000 ::expired))
          "Spin finally runs before the SCI catch can keep its worker busy")
      (is (false? (sandbox/evaluation-finished? @handle)))
      (is (false? (:success (sandbox/await-evaluation! @handle))))
      (.countDown release)
      (is (sandbox/join-evaluation! @handle 30000))
      (finally
        (.countDown release)
        (when @handle (sandbox/cancel-evaluation! @handle) (sandbox/join-evaluation! @handle 30000))
        (context/close-context! world)))))

(deftest ordinary-timed-deref-does-not-cancel-detached-spin
  (let [world (context/create-execution-context)
        interpreter (sandbox/create-base-ctx)
        entered (promise) cleaned (promise)
        task (binding [ec/*execution-context* world]
               (sp/spin (try (deliver entered true) (await (sp/sleep 60000))
                             (finally (deliver cleaned true)))))]
    (sci/merge-opts interpreter {:namespaces {'probe {'task (fn [] task)}}})
    (try
      (let [r (sandbox/eval-code interpreter "(deref (probe/task) 10 :later)"
                                 :execution-context world :timeout-ms 30000)]
        (is (:success r))
        (is (= :later (:value r)))
        (is (= true (deref entered 30000 ::expired)))
        (is (not (realized? cleaned))))
      (finally
        (binding [ec/*execution-context* world] (spin-core/cancel-spin! task))
        (context/close-context! world)))))
