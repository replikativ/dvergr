(ns dvergr.sandbox.extensions-test
  "An AgentDef's :sandbox names extensions; a working context installs them,
   and only them; runs record what they resolved to."
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.agent.evaluation :as evaluation]
            [dvergr.agent.room-context :as room-context]
            [dvergr.agent.roster :as roster]
            [dvergr.agent.turn :as turn]
            [dvergr.chat.context :as chat-context]
            [dvergr.discourse :as d]
            [dvergr.discourse.llm :as llm]
            [dvergr.orchestration.daemon :as daemon]
            [dvergr.room.store.memory :as mem]
            [dvergr.sandbox :as sandbox]
            [dvergr.sandbox.extensions :as extensions]
            [org.replikativ.spindel.core :as sp]
            [sci.core :as sci]
            [org.replikativ.spindel.engine.context :as context]
            [org.replikativ.spindel.engine.core :as ec]))

(deftest the-registry-checks-what-it-is-given
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"qualified keyword"
                        (extensions/register! :plain {:version 1 :install (fn [_ _])})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"positive integer"
                        (extensions/register! :test/x {:version 0 :install (fn [_ _])})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"function or a qualified symbol"
                        (extensions/register! :test/x {:version 1 :install "code"})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"portable data"
                        (extensions/register! :test/x {:version 1 :install (fn [_ _]) :basis {:f (fn [])}})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"a map"
                        (extensions/register! :test/x {:version 1 :install (fn [_ _]) :basis "1.0"})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown sandbox extension"
                        (extensions/refs [:test/never-registered])))
  (let [[ref] (extensions/refs [:foerster/inference])]
    (is (= :foerster/inference (:extension/id ref)))
    (is (= 1 (:extension/version ref)))
    (is (contains? (:extension/basis ref) :foerster))
    (is (roster/data-value? ref))))

(deftest agent-definitions-name-extensions
  (let [team (roster/make-agent (roster/make-roster) {:id :analyst :sandbox [:foerster/inference]})]
    (is (= #{:foerster/inference} (get-in team [:roster/agents :analyst :agent/sandbox]))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"collection of keywords"
                        (roster/make-agent (roster/make-roster) {:id :analyst :sandbox ["foerster"]}))))

(defn- with-working-ctx [extensions f]
  (let [execution-ctx (context/create-execution-context)
        cctx (turn/new-working-ctx (cond-> {:execution-ctx execution-ctx :title "extensions" :durable? false}
                                     extensions (assoc :sandbox-extensions extensions)))]
    (try (f (fn [code] (sandbox/eval-code (chat-context/sci-context-in cctx execution-ctx) code
                                          :execution-context execution-ctx :timeout-ms 60000)))
         (finally
           (chat-context/close-chat! cctx)
           (context/close-context! execution-ctx)))))

(def ^:private conjugate-run
  ;; μ ~ N(0, 1), y ~ N(μ, 1) at 1, 2, 1.5: μ | y ~ N(1.125, 0.5²)
  (str "(require '[org.replikativ.spindel.spin.cps :refer [spin]] "
       "         '[org.replikativ.spindel.effects.await :refer [await]] "
       "         '[org.replikativ.foerster.effects :refer [sample observe]] '[dist] '[infer]) "
       "(let [model (spin (let [mu (sample (dist/normal 0.0 1.0))] "
       "                    (loop [ys [1.0 2.0 1.5]] (when (seq ys) (observe (dist/normal mu 1.0) (first ys)) (recur (rest ys)))) "
       "                    mu)) "
       "      post @(spin (await (infer/run model {:method :mh :iterations 1500 :chains 2 :burn 300 :seed 1})))] "
       "  (select-keys (infer/summary post) [:mean :sd :chains]))"))

(deftest a-working-context-installs-its-extensions
  (testing "with :foerster/inference"
    (with-working-ctx #{:foerster/inference}
      (fn [ev]
        (let [{:keys [success value error]} (ev conjugate-run)]
          (is success (str error))
          (is (= 2 (:chains value)))
          (is (< (Math/abs (- 1.125 (:mean value))) 0.15))
          (is (< (Math/abs (- 0.5 (:sd value))) 0.15)))
        (testing "the namespaces are documented"
          (is (:success (ev "(clojure.repl/doc infer/run)")))))))
  (testing "without it, no foerster in the sandbox"
    (with-working-ctx nil
      (fn [ev]
        (let [{:keys [success error]} (ev "(infer/run nil {})")]
          (is (not success))
          (is (re-find #"infer" (str (:message error))))))))
  (testing "an unknown extension fails the construction"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown sandbox extension"
                          (with-working-ctx #{:test/never-registered} (fn [_]))))))

(deftest runs-record-what-the-extensions-resolved-to
  (let [identity-of #'evaluation/execution-identity
        with (identity-of {:agent/id :a :agent/version 1 :agent/sandbox #{:foerster/inference}
                           :agent/program {:kind :llm}} {} {})
        without (identity-of {:agent/id :a :agent/version 1 :agent/program {:kind :llm}} {} {})]
    (is (= [:foerster/inference] (mapv :extension/id (get-in with [:metrics :sandbox-extensions]))))
    (is (nil? (get-in without [:metrics :sandbox-extensions])))
    (is (not= (get-in with [:metrics :agent-def-hash]) (get-in without [:metrics :agent-def-hash])))))

(defn- busy-chain-threads []
  (->> (Thread/getAllStackTraces)
       (filter (fn [[t st]] (and (= Thread$State/RUNNABLE (.getState ^Thread t))
                                 (some #(re-find #"foerster\.(trace|kernel|core|nuts)" (str %)) st))))
       count))

(deftest a-timed-out-evaluation-stops-what-it-waited-for
  (with-working-ctx #{:foerster/inference}
    (fn [_]
      (let [execution-ctx (context/create-execution-context)
            cctx (turn/new-working-ctx {:execution-ctx execution-ctx :title "timeout" :durable? false
                                        :sandbox-extensions #{:foerster/inference}})
            long-run (str "(require '[org.replikativ.spindel.spin.cps :refer [spin]] "
                          "         '[org.replikativ.spindel.effects.await :refer [await]] "
                          "         '[org.replikativ.foerster.effects :refer [sample observe]] '[dist] '[infer]) "
                          "@(spin (await (infer/run (spin (let [mu (sample (dist/normal 0.0 1.0))] (observe (dist/normal mu 1.0) 1.0) mu)) "
                          "                         {:method :mh :iterations 100000 :chains 2})))")]
        (try
          (let [r (sandbox/eval-code (chat-context/sci-context-in cctx execution-ctx) long-run
                                     :execution-context execution-ctx :timeout-ms 2000)]
            (is (not (:success r)))
            (is (re-find #"Timed out" (str (get-in r [:error :message]))))
            (Thread/sleep 3000)
            (is (zero? (busy-chain-threads)) "the chains stopped with the evaluation"))
          (finally
            (chat-context/close-chat! cctx)
            (context/close-context! execution-ctx)))))))

(def ^:private prelude
  (str "(require '[org.replikativ.spindel.spin.cps :refer [spin]] "
       "         '[org.replikativ.spindel.effects.await :refer [await]] "
       "         '[org.replikativ.foerster.effects :refer [sample observe]] '[dist] '[infer]) "
       "(def model (spin (let [mu (sample (dist/normal 0.0 1.0))] (observe (dist/normal mu 1.0) 1.0) mu))) "))

(defn- with-agent-sandbox
  "`f` of an eval fn and the chat context of a working context with foerster."
  [f]
  (let [execution-ctx (context/create-execution-context)
        cctx (turn/new-working-ctx {:execution-ctx execution-ctx :title "lifecycle" :durable? false
                                    :sandbox-extensions #{:foerster/inference}})
        ev (fn [code timeout] (sandbox/eval-code (chat-context/sci-context-in cctx execution-ctx) code
                                                 :execution-context execution-ctx :timeout-ms timeout))]
    (try (f ev cctx)
         (finally
           (try (chat-context/close-chat! cctx) (catch Throwable _ nil))
           (context/close-context! execution-ctx)))))

(deftest interruption-cleanup-holds-whatever-the-code-does
  (with-agent-sandbox
    (fn [ev _]
      (is (:success (ev (str prelude "(def cached (spin 41)) @cached") 30000)))
      (testing "code that catches the interrupt still loses the work it waited for"
        (ev "(try @(spin (await (infer/run model {:method :mh :iterations 100000 :chains 2}))) (catch Throwable _ :caught))" 2000)
        (Thread/sleep 3000)
        (is (zero? (busy-chain-threads))))
      (testing "a spin the timed-out eval had already dereferenced keeps its value"
        (ev "(do @cached @(spin (await (infer/run model {:method :mh :iterations 100000 :chains 2}))))" 2000)
        (Thread/sleep 3000)
        (is (zero? (busy-chain-threads)))
        (is (= 41 (:value (ev "@cached" 10000))))))))

(deftest background-jobs-end-with-their-interpreter
  (with-agent-sandbox
    (fn [ev cctx]
      (let [r (ev (str prelude "(def job (infer/start model {:method :mh :iterations 100000 :chains 2})) (infer/status job)") 30000)]
        (is (= {:status :running} (:value r)) (pr-str (:error r)))
        (is (loop [i 0] (cond (pos? (busy-chain-threads)) true (> i 50) false :else (do (Thread/sleep 100) (recur (inc i)))))
            "the job runs after the evaluation returned")
        (chat-context/close-chat! cctx)
        (Thread/sleep 3000)
        (is (zero? (busy-chain-threads)) "releasing the interpreter cancelled it")))))

(defn- has-infer? [cctx execution-ctx]
  (:value (sandbox/eval-code (chat-context/sci-context-in cctx execution-ctx)
                             "(some? (find-ns 'infer))"
                             :execution-context execution-ctx :timeout-ms 30000)))

(deftest room-agents-name-extensions
  (testing "an agent's config names them; an unknown one fails its creation"
    (is (= #{:foerster/inference}
           (:sandbox (daemon/resolve-safe-config {:id :analyst :sandbox [:foerster/inference]}))))
    (is (not (contains? (daemon/resolve-safe-config {:id :analyst}) :sandbox)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown sandbox extension"
                          (daemon/resolve-safe-config {:id :analyst :sandbox #{:test/never-registered}})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown sandbox extension"
                          (llm/llm-agent {:id :analyst :spec {} :sandbox #{:test/never-registered}})))))

(deftest a-room-working-context-installs-the-agents-extensions
  (let [parent-ctx (context/create-execution-context)
        child-ctx (atom nil)
        parent (d/make-room {:id :ext-parent :ctx parent-ctx :store (mem/make)})]
    (try
      (let [with (room-context/ensure-ctx! parent :analyst {:budget-dollars 1.0 :sandbox #{:foerster/inference}})
            without (room-context/ensure-ctx! parent :plain {:budget-dollars 1.0})]
        (is (true? (has-infer? with parent-ctx)))
        (is (false? (has-infer? without parent-ctx)))
        (testing "a context built without them (a scheduled task before the agent joined) gets them later"
          (let [upgraded (room-context/ensure-ctx! parent :plain {:sandbox #{:foerster/inference}})]
            (is (true? (has-infer? upgraded parent-ctx)))
            (is (identical? upgraded (room-context/ensure-ctx! parent :plain {:sandbox #{:foerster/inference}})))
            (is (identical? upgraded (room-context/ensure-ctx! parent :plain {})))))
        (testing "naming fewer rebuilds it: an interpreter cannot give back what its code holds"
          (let [plain (room-context/ensure-ctx! parent :plain {:sandbox #{}})]
            (is (false? (has-infer? plain parent-ctx)))
            (is (identical? plain (room-context/ensure-ctx! parent :plain {:sandbox #{}})))))
        (testing "a fork of the room keeps them"
          (let [c (reset! child-ctx (context/fork-context parent-ctx :mode :frozen))
                child (d/make-room {:id :ext-child :ctx c :store (mem/make)})
                projected (room-context/fork-ctx! parent child :analyst)]
            (is (some? projected))
            (is (true? (has-infer? projected c))))))
      (finally
        (room-context/drop-ctx! :ext-child :analyst)
        (room-context/drop-ctx! :ext-parent :analyst)
        (room-context/drop-ctx! :ext-parent :plain)
        (some-> @child-ctx context/close-context!)
        (context/close-context! parent-ctx)))))

(deftest a-room-joined-agent-has-its-extensions
  (let [room (d/room :ext-joined)
        seen (promise)]
    (try
      (binding [ec/*execution-context* (:ctx room)]
        (d/join room (llm/llm-agent
                      {:id :analyst
                       :spec {:provider :mock :model "mock"}
                       :sandbox #{:foerster/inference}
                       :run-turn-fn (fn [cctx _]
                                      (deliver seen (has-infer? cctx (:ctx room)))
                                      (chat-context/add-message! cctx {:role :assistant :content "ok"})
                                      :complete)})))
      (let [p (promise)]
        (binding [ec/*execution-context* (:ctx room)]
          (sp/spawn! (sp/spin (deliver p (sp/await (d/ask room :analyst {:content "hi"}))))))
        (is (= "ok" (:content (deref p 60000 ::timeout))))
        (is (true? (deref seen 1000 ::timeout)))
        (is (= #{:foerster/inference} (:sandbox (get @(:participants room) :analyst)))
            "the Participant names them for room code outside a turn (scheduled tasks)"))
      (finally
        (room-context/drop-ctx! :ext-joined :analyst)
        (d/close-room! room)))))

(deftest a-rejoined-agent-loses-the-extensions-it-no-longer-names
  (let [room (d/room :ext-rejoined)
        agent (fn [sandbox seen]
                (llm/llm-agent
                 (cond-> {:id :analyst
                          :spec {:provider :mock :model "mock"}
                          :run-turn-fn (fn [cctx _]
                                         (deliver seen (has-infer? cctx (:ctx room)))
                                         (chat-context/add-message! cctx {:role :assistant :content "ok"})
                                         :complete)}
                   sandbox (assoc :sandbox sandbox))))
        ask! (fn []
               (let [p (promise)]
                 (binding [ec/*execution-context* (:ctx room)]
                   (sp/spawn! (sp/spin (deliver p (sp/await (d/ask room :analyst {:content "hi"}))))))
                 (deref p 60000 ::timeout)))]
    (try
      (let [first-seen (promise)
            second-seen (promise)]
        (binding [ec/*execution-context* (:ctx room)]
          (d/join room (agent #{:foerster/inference} first-seen)))
        (is (= "ok" (:content (ask!))))
        (is (true? (deref first-seen 1000 ::timeout)))
        (binding [ec/*execution-context* (:ctx room)]
          (d/leave room :analyst)
          (d/join room (agent nil second-seen)))
        (is (= "ok" (:content (ask!))))
        (is (false? (deref second-seen 1000 ::timeout))
            "the cached interpreter of the first agent is not reused"))
      (finally
        (room-context/drop-ctx! :ext-rejoined :analyst)
        (d/close-room! room)))))

(deftest a-failed-installation-leaves-no-context-behind
  (let [registry @#'extensions/registry
        installs (atom 0)
        ctx (context/create-execution-context)
        room (d/make-room {:id :ext-failing :ctx ctx :store (mem/make)})]
    (extensions/register! :test/flaky
                          {:version 1
                           :install (fn [sci-ctx _]
                                      (swap! installs inc)
                                      (sci/add-namespace! sci-ctx 'flaky-ext {'marker 1})
                                      (when (= 1 @installs)
                                        (throw (ex-info "installer failed part way" {}))))})
    (try
      (room-context/ensure-ctx! room :analyst {:budget-dollars 1.0})
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"part way"
                            (room-context/ensure-ctx! room :analyst {:sandbox #{:test/flaky}})))
      (is (nil? (room-context/lookup :ext-failing :analyst)) "the partly installed context is dropped")
      (let [rebuilt (room-context/ensure-ctx! room :analyst {:sandbox #{:test/flaky}})]
        (is (= 2 @installs) "the next call installs again")
        (is (true? (:value (sandbox/eval-code (chat-context/sci-context-in rebuilt ctx)
                                              "(some? (find-ns 'flaky-ext))"
                                              :execution-context ctx :timeout-ms 30000)))))
      (finally
        (swap! registry dissoc :test/flaky)
        (room-context/drop-ctx! :ext-failing :analyst)
        (context/close-context! ctx)))))
