(ns dvergr.agent.workflow-settlement-test
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.agent.workflow :as workflow]
            [dvergr.agent.review :as review]
            [dvergr.agent.roster :as roster]
            [dvergr.agent.run :as run]
            [dvergr.discourse :as d]
            [dvergr.jobs :as jobs]
            [dvergr.ops :as ops]
            [dvergr.principal :as principal]
            [dvergr.resource :as resource]
            [dvergr.room.registry :as rreg]
            [dvergr.room.store.memory :as memory]
            [org.replikativ.spindel.engine.core :as ec]))

(defn- echo-candidates [_]
  {:team (roster/make-agent (roster/make-roster {:id :settlement/test})
                            {:id :echo :prompt "Echo" :tools #{}
                             :program {:kind :echo}})
   :ids [:echo]})

(defmacro with-room [[room] & body]
  `(let [~room (d/make-room {:id (keyword (str "settlement-" (random-uuid)))
                             :store (memory/make)})]
     (try
       (binding [ec/*execution-context* (:ctx ~room)] (rreg/register! ~room))
       (with-redefs [workflow/candidates echo-candidates] ~@body)
       (finally (d/close-room! ~room)))))

(defn- opts [room]
  {:task "prepare" :attempts 1 :principal (assoc principal/local-principal
                                                 :principal/delegation "test-grant"
                                                 :principal/delegation-scope {:paths ["wiki/"]})
   :daemon {:execution-ctx (:ctx room)}
   :workflow/writes {:repo ["wiki/"]}
   :review {:review/checks [] :review/on-pass :propose}})

(defn- results [started]
  (binding [ec/*execution-context* (:ctx started)] @(:spin started)))

(deftest hostile-policy-cannot-grant-merge-or-remove-host-checks
  (with-room [room]
    (let [caller {:principal/id :restricted :principal/kind :human
                  :principal/delegation "unscoped-grant"}
          daemon {:execution-ctx (:ctx room)
                  :config {:auth {:authorize (fn [_ request] (not= :merge (:action request)))}}}
          [row] (workflow/attempt! room (assoc (opts room) :principal caller :daemon daemon
                                               :workflow/writes nil
                                               :review {:review/checks [] :review/on-pass :merge
                                                        :review/on-fail :discard}))
          [rv] (review/reviews room)
          policy (:review/effective-policy rv)]
      (is (= :escalated (:review/decision rv)))
      (is (= :propose (:review/on-pass policy)))
      (is (= [:fork/review-tier [:diff/only-paths []]] (:review/checks policy)))
      (is (= 2 (count (:review/downgrades policy))))
      (is (some? (binding [ec/*execution-context* (:ctx room)] (rreg/lookup (:world row)))))
      (is (= 1 (count (filter #(= :review/escalated (get-in % [:metadata :kind])) (d/messages room))))))))

(deftest reviewer-uses-caller-model-admission-and-budget
  (with-room [room]
    (let [caller {:principal/id :restricted :principal/kind :human
                  :principal/models #{"claude-haiku-4-5"} :principal/budget-dollars 0.03
                  :principal/run (random-uuid)}
          options (assoc (opts room) :principal caller :budget-dollars 0.02)
          policy {:review/reviewer {:model "claude-haiku-4-5" :instructions "Check" :budget-dollars 10}}
          seen (atom [])]
      (with-redefs [resource/wallet-microdollars (fn [_ payer]
                                                   (is (= (:principal/run caller) payer)) 10000M)]
        (is (= 0.01 (get-in (workflow/bounded-reviewer-spec room options policy)
                            [:review/reviewer :budget-dollars]))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Model outside caller policy"
                            (workflow/bounded-reviewer-spec room options
                                                            (assoc-in policy [:review/reviewer :model] "claude-sonnet-4-5"))))
      (with-redefs [review/model-reviewer (fn [_ _ _ spec p]
                                            (swap! seen conj [spec p])
                                            (constantly {:review/verdict :approve}))
                    resource/wallet-microdollars (fn [_ _] 10000M)]
        (workflow/attempt! room (assoc options :review policy)))
      (is (= 0.01 (get-in @seen [0 0 :review/reviewer :budget-dollars])))
      (is (= caller (get-in @seen [0 1])))
      (is (= 0.01 (get-in (first (review/reviews room))
                          [:review/effective-policy :review/reviewer :budget-dollars]))))))

(deftest construction-failure-escalates-and-next-attempt-is-settled
  (with-room [room]
    (let [calls (atom 0)]
      (with-redefs [review/model-reviewer
                    (fn [& _]
                      (if (= 1 (swap! calls inc))
                        (throw (ex-info "broken constructor" {}))
                        (constantly {:review/verdict :approve})))]
        (is (= 2 (count (workflow/attempt! room
                                           (-> (opts room) (assoc :attempts 2)
                                               (assoc-in [:review :review/reviewer]
                                                         {:model "claude-haiku-4-5" :instructions "Check"})))))))
      (let [records (review/reviews room)]
        (is (= 2 @calls))
        (is (= 2 (count records)))
        (is (every? #(= :escalated (:review/decision %)) records))
        (is (some #(re-find #"broken constructor" (str (:review/reasons %))) records))
        (is (= 2 (count (filter #(= :review/escalated (get-in % [:metadata :kind])) (d/messages room)))))))))

(deftest repeated-and-concurrent-finish-review-once
  (with-room [room]
    (let [entered (promise) release (promise) calls (atom 0)
          started (workflow/start room (-> (opts room)
                                           (assoc-in [:review :review/reviewer] {:instructions "Check"})
                                           (assoc :reviewer (fn [_]
                                                              (swap! calls inc)
                                                              (deliver entered true)
                                                              @release
                                                              {:review/verdict :approve}))))
          rs (results started)
          a (future ((:finish started) rs))]
      (is (= true (deref entered 5000 :timeout)))
      (let [b (future ((:finish started) rs))]
        (deliver release true)
        (is (= @a @b))
        (is (= @a ((:finish started) rs))))
      (is (= 1 @calls))
      (is (= 1 (count (review/reviews room)))))))

(deftest job-cancellation-stops-settlement-before-merge
  (with-room [room]
    (let [entered (promise) release (promise) started (promise)
          merge-calls (atom 0)
          invoke ops/invoke
          job (binding [ec/*execution-context* (:ctx room)]
                (jobs/start! room {:kind :workflow}
                             (fn [id]
                               (let [s (workflow/start room
                                                       (-> (opts room) (assoc :parent-run id :attempts 4)
                                                           (assoc-in [:review :review/on-pass] :merge)
                                                           (assoc-in [:review :review/reviewer] {:instructions "Check"})
                                                           (assoc :reviewer (fn [_]
                                                                              (deliver entered true)
                                                                              (try @release (catch InterruptedException _ nil))
                                                                              {:review/verdict :approve}))))]
                                 (deliver started s) (:spin s)))
                             :finish #((:finish @started) %)))]
      (with-redefs [ops/invoke (fn [& args]
                                 (when (= :room/merge (nth args 2)) (swap! merge-calls inc))
                                 (apply invoke args))]
        (is (= true (deref entered 5000 :timeout)))
        (let [cancelled (binding [ec/*execution-context* (:ctx room)] (jobs/cancel! (:id job)))]
          (deliver release true)
          (is (= :cancelled (:status cancelled)))))
      (is (zero? @merge-calls))
      (is (= 4 (count (review/reviews room))))
      (is (every? #(= :escalated (:review/decision %)) (review/reviews room)))
      (is (= 4 (count (filter #(= :review/escalated (get-in % [:metadata :kind])) (d/messages room)))))
      (is (empty? (filter #(= :settlement (:run/kind %)) (run/active-runs (:id room))))))))

(deftest host-scope-is-independent-of-bundle-checks-and-actions
  (let [policy (workflow/effective-policy
                {:principal {:principal/delegation "grant"
                             :principal/delegation-scope {:paths ["wiki/"] :kb {:knowledge #{:wiki/atom}}}}
                 :workflow/writes {:repo ["wiki/"] :kb {:knowledge #{:wiki/atom}}}
                 :review {:review/checks [] :review/on-pass :merge :review/on-fail :discard}}
                {:merge? true :discard? false})]
    (is (= [:fork/review-tier [:diff/only-paths ["wiki/"]]
            [:diff/only-kb-pages {:system :knowledge :kinds #{:wiki/atom}}]]
           (:review/checks policy)))
    (is (= :merge (:review/on-pass policy)))
    (is (= :escalate (:review/on-fail policy)))
    (is (= :review/on-fail (:key (first (:review/downgrades policy))))))
  (is (= :propose (:review/on-pass (workflow/effective-policy
                                    {:review {:review/on-pass :merge}} {:merge? true})))))

(deftest review-boundary-failure-does-not-abort-subsequent-attempts
  (with-room [room]
    (let [calls (atom 0) review! review/review!]
      (with-redefs [review/review! (fn [& args]
                                     (if (= 1 (swap! calls inc))
                                       (throw (ex-info "review boundary failed" {}))
                                       (apply review! args)))]
        (is (= 2 (count (workflow/attempt! room (assoc (opts room) :attempts 2))))))
      (is (= 2 @calls))
      (is (= 2 (count (review/reviews room))))
      (is (some #(re-find #"review boundary failed" (str (:review/reasons %))) (review/reviews room))))))

(deftest delegated-reviewer-cannot-use-standing-agent-authority
  (with-room [room]
    (let [caller {:principal/id :restricted :principal/kind :human
                  :principal/delegation "unscoped-grant"}
          daemon {:execution-ctx (:ctx room)
                  :config {:auth {:authorize (fn [p request]
                                               (or (= :agent (:principal/kind p))
                                                   (not= :merge (:action request))))}}}]
      (workflow/attempt! room (assoc (opts room) :principal caller :daemon daemon
                                     :review {:review/checks [] :review/on-pass :merge}))
      (is (= :escalated (:review/decision (first (review/reviews room)))))
      (is (= :propose (get-in (first (review/reviews room)) [:review/effective-policy :review/on-pass]))))))

(deftest room-teardown-waits-for-settlement-worker-quiescence
  (let [room (d/make-room {:id (keyword (str "settlement-drain-" (random-uuid))) :store (memory/make)})
        entered (promise) release (promise)]
    (try
      (binding [ec/*execution-context* (:ctx room)] (rreg/register! room))
      (with-redefs [workflow/candidates echo-candidates]
        (let [work (future
                     (try
                       (workflow/attempt! room
                                          (-> (opts room)
                                              (assoc-in [:review :review/on-pass] :merge)
                                              (assoc-in [:review :review/reviewer] {:instructions "Check"})
                                              (assoc :reviewer
                                                     (fn [_]
                                                       (deliver entered true)
                                                       (loop []
                                                         (when-not (try (deref release 50 false)
                                                                        (catch InterruptedException _ false))
                                                           (recur)))
                                                       {:review/verdict :approve}))))
                       (catch Throwable _ :stopped)))]
          (is (= true (deref entered 5000 :timeout)))
          (let [closing (future (d/close-room! room))]
            (Thread/sleep 100)
            (is (not (realized? closing)))
            (deliver release true)
            (is (not= :timeout (deref closing 5000 :timeout)))
            (is (not= :timeout (deref work 5000 :timeout)))
            (is (empty? (run/active-runs (:id room)))))))
      (finally
        (deliver release true)
        (d/close-room! room)))))

(deftest scope-intersection-attenuates-paths-and-kb-kinds
  (let [declared {:repo ["/" "" "docs/"]
                  :kb {:knowledge #{:wiki/atom :wiki/index} :other #{:wiki/atom}}}
        delegated {:paths ["wiki/"] :kb {:knowledge #{:wiki/atom :wiki/overview}}}
        policy (workflow/effective-policy
                {:principal {:principal/delegation "grant" :principal/delegation-scope delegated}
                 :workflow/writes declared :review {:review/on-pass :merge}}
                {:merge? true})]
    (is (= {:paths ["wiki/"] :kb {:knowledge #{:wiki/atom}}} (:review/write-scope policy)))
    (is (= declared (:review/declared-writes policy)))
    (is (= delegated (:review/delegation-scope policy)))
    (is (= :merge (:review/on-pass policy))))
  (let [policy (workflow/effective-policy
                {:principal {:principal/delegation "grant" :principal/delegation-scope {:paths ["wiki/"]}}
                 :workflow/writes {:repo ["docs/"]} :review {:review/on-pass :merge}}
                {:merge? true})]
    (is (= :propose (:review/on-pass policy)))
    (is (= {:paths [] :kb {}} (:review/write-scope policy)))))
