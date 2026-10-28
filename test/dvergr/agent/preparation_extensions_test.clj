(ns dvergr.agent.preparation-extensions-test
  (:require [clojure.test :refer [deftest is]]
            [sci.core :as sci]
            [dvergr.agent.program :as program]
            [dvergr.agent.turn :as turn]
            [dvergr.agent.room-context :as room-context]
            [dvergr.actors :as actors]
            [dvergr.chat.context :as chat-context]
            [dvergr.discourse :as d]
            [dvergr.discourse.llm :as llm]
            [dvergr.runtime.preparation :as preparation]
            [dvergr.sandbox :as sandbox]
            [dvergr.sandbox.extensions :as extensions]
            [dvergr.tools :as tools]
            [org.replikativ.spindel.core :as sp]
            [org.replikativ.spindel.engine.core :as ec])
  (:import [java.util.concurrent CountDownLatch]))

(defn- prepare! [room participant ids before-publish]
  (binding [ec/*execution-context* (:ctx room)]
    (let [scope (program/preparation-scope (:ctx room))
          lease (preparation/admit!
                 room participant
                 {:cancel! #(program/cancel-preparation! scope)
                  :join! #(program/await-preparation! scope %)
                  :finished? #(program/preparation-finished? scope)})]
      (try
        (let [worker (program/start-preparation!
                      scope #(room-context/stage-ctx!
                              room (:id participant) {:sandbox ids :budget-dollars 1.0} lease
                              (partial program/register-preparation-cleanup! scope)))
              stage @(program/preparation-result worker)]
          (when before-publish (before-publish stage))
          (preparation/with-current!
            lease #(program/with-preparation-decision! scope
                     (fn [] (room-context/publish-staged! stage)))))
        (catch Throwable t
          (program/cancel-preparation! scope)
          (throw t))
        (finally
          (program/complete-preparation! scope)
          (program/await-preparation! scope 5000))))))

(defn- owned-extension [resources id fail?]
  {:version 1
   :install (fn [interpreter execution-context]
              (let [key [id (:env interpreter)]
                    closed? (atom false)]
                (swap! resources assoc key {:closed? closed? :releases 0 :ctx execution-context})
                (sci/add-namespace! interpreter (symbol (name id))
                                    {'ping (fn [] (if @closed? :closed :alive))})
                (when (and fail? @fail?) (throw (ex-info "extension setup failed" {:type ::setup-failed})))))
   :release (fn [interpreter]
              (let [key [id (:env interpreter)]]
                (when-let [resource (get @resources key)]
                  (reset! (:closed? resource) true)
                  (swap! resources update-in [key :releases] inc))))})

(defn- with-fixture [f]
  (with-redefs-fn
    {#'extensions/registry (atom {})}
    (fn []
      (let [room (d/make-room {:id (keyword (str "extension-preparation-" (random-uuid)))})
            resources (atom {})
            fail? (atom false)]
        (try
          (extensions/register! :test/owned-a (owned-extension resources :test/owned-a nil))
          (extensions/register! :test/owned-b (owned-extension resources :test/owned-b fail?))
          (let [participant (d/join room (d/participant {:id :agent :on-message (fn [_ _] (sp/spin nil))}))]
            (f room participant resources fail?))
          (finally (d/close-room! room)))))))

(deftest expansion-retains-user-state-and-releases-original-and-added-envs
  (with-fixture
    (fn [room participant resources _]
      (let [original (prepare! room participant #{:test/owned-a} nil)
            interpreter (chat-context/sci-context original)]
        (sci/eval-string* interpreter "(def user-state (atom [:kept])) (def saved owned-a/ping)")
        (let [expanded (prepare! room participant #{:test/owned-a :test/owned-b} nil)
              selected (chat-context/sci-context expanded)]
          (is (= [:kept] (sci/eval-string* selected "@user-state")))
          (is (= :alive (sci/eval-string* selected "(saved)")))
          (is (= :alive (sci/eval-string* selected "(owned-b/ping)")))
          (is (= 2 (count @resources)))
          (is (every? #(zero? (:releases %)) (vals @resources)))
          (d/close-room! room)
          (is (every? #(= 1 (:releases %)) (vals @resources))))))))

(deftest first-expanded-turns-clojure-tool-uses-the-published-interpreter
  (with-fixture
    (fn [room participant _ _]
      (let [original (prepare! room participant #{:test/owned-a} nil)
            tool-result (promise)]
        (sci/eval-string* (chat-context/sci-context original) "(def user-state :kept)")
        (d/leave room :agent)
        (let [_expanded (d/join
                         room
                         (llm/llm-agent
                          {:id :agent :sandbox #{:test/owned-a :test/owned-b}
                           :tools #{:clojure_eval} :spec {:provider :test :model "fixture"}
                           :run-turn-fn
                           (fn [ctx opts]
                             (deliver tool-result
                                      (tools/execute "clojure_eval"
                                                     {:code "[user-state (owned-a/ping) (owned-b/ping)]"}
                                                     (:tool-ctx opts)))
                             (chat-context/add-message! ctx {:role :assistant :content "checked"})
                             :complete)}))]
          (d/post! room (d/message :human :agent "check"))
          (is (= [:kept :alive :alive]
                 (get-in (deref tool-result 10000 ::timeout) [:metadata :value]))))))))

(deftest failed-and-stale-expansions-release-only-staged-additions
  (doseq [failure [:installation :publication]]
    (with-fixture
      (fn [room participant resources fail?]
        (let [original (prepare! room participant #{:test/owned-a} nil)
              interpreter (chat-context/sci-context original)]
          (sci/eval-string* interpreter "(def user-state :retained)")
          (reset! fail? (= failure :installation))
          (is (thrown? clojure.lang.ExceptionInfo
                       (prepare!
                        room participant #{:test/owned-a :test/owned-b}
                        (when (= failure :publication)
                          (fn [_]
                            (sandbox/replace-spindel-sci-context!
                             (:ctx room) (:sci-component original) interpreter (sci/fork interpreter)))))))
          (is (identical? original (room-context/lookup (:id room) :agent)))
          (is (= :retained (sci/eval-string* (chat-context/sci-context original) "user-state")))
          (is (= :alive (sci/eval-string* (chat-context/sci-context original) "(owned-a/ping)")))
          (is (= [0] (mapv :releases (vals (into {} (filter #(= :test/owned-a (ffirst %))) @resources)))))
          (is (= [1] (mapv :releases (vals (into {} (filter #(= :test/owned-b (ffirst %))) @resources)))))
          (d/close-room! room)
          (is (every? #(= 1 (:releases %)) (vals @resources))))))))

(deftest child-projection-never-releases-its-parents-extension-chain
  (with-fixture
    (fn [room participant resources _]
      (prepare! room participant #{:test/owned-a} nil)
      (let [expanded (prepare! room participant #{:test/owned-a :test/owned-b} nil)
            child (binding [ec/*execution-context* (:ctx room)]
                    (d/fork-room room {:isolation :ctx :clone-participants? false}))
            discarded? (atom false)]
        (try
          (let [projected (binding [ec/*execution-context* (:ctx child)]
                            (room-context/fork-ctx! room child :agent))]
            (is (= 1 (count (:extension-owners projected))))
            (is (every? #(identical? (:ctx child) (:execution-context %))
                        (:extension-owners projected)))
            (d/discard child)
            (reset! discarded? true)
            (let [parent-resources (filter #(identical? (:ctx room) (:ctx %)) (vals @resources))
                  child-resources (filter #(identical? (:ctx child) (:ctx %)) (vals @resources))]
              (is (= 2 (count parent-resources)))
              (is (= 2 (count child-resources)))
              (is (every? #(zero? (:releases %)) parent-resources))
              (is (every? #(= 1 (:releases %)) child-resources)))
            (is (= :alive (sci/eval-string* (chat-context/sci-context expanded) "(owned-a/ping)")))
            (d/close-room! room)
            (is (every? #(= 1 (:releases %)) (vals @resources))))
          (finally
            (when-not @discarded?
              (d/discard child))))))))

(deftest staged-construction-retains-canonical-capability-options
  (with-fixture
    (fn [room participant _ _]
      (let [captured (atom [])
            construct turn/new-working-ctx]
        (with-redefs [actors/lookup (fn [_ _] {:config {:allowed-domains #{"https://example.test"}}})
                      turn/new-working-ctx (fn [opts] (swap! captured conj opts) (construct opts))]
          (room-context/ensure-ctx! room :sync-agent {:sandbox #{:test/owned-a} :budget-dollars 1.0})
          (prepare! room participant #{:test/owned-a} nil)
          (let [[synchronous staged] @captured
                shared [:execution-ctx :db-conn :kb-conn :room-id :room-runtime-id
                        :room-incarnation :allowed-domains :durable? :sandbox-extensions :budget-dollars]]
            (is (= (select-keys synchronous shared) (select-keys staged shared)))
            (is (= #{"https://example.test"} (:allowed-domains staged)))
            (is (= :agent (:agent-id staged)))
            (is (= (:incarnation room) (:room-incarnation staged)))
            (is (fn? (:own-cleanup! staged)))))))))

(deftest pending-extension-preparation-protects-existing-interpreter-from-sync-drop
  (with-fixture
    (fn [room participant resources _]
      (prepare! room participant #{:test/owned-a} nil)
      (let [scope (binding [ec/*execution-context* (:ctx room)]
                    (program/preparation-scope (:ctx room)))
            lease (preparation/admit!
                   room participant
                   {:cancel! #(program/cancel-preparation! scope)
                    :join! #(program/await-preparation! scope %)
                    :finished? #(program/preparation-finished? scope)})]
        (try
          (is (= ::preparation/not-quiescent
                 (try (room-context/ensure-ctx! room :agent {:sandbox #{}}) nil
                      (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))
          (is (= ::preparation/not-quiescent
                 (try (room-context/drop-ctx! room :agent) nil
                      (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))
          (is (every? #(zero? (:releases %)) (vals @resources)))
          (finally
            (preparation/cancel! lease)
            (program/await-preparation! scope 5000)))))))

(deftest cache-retirement-is-retained-across-a-fresh-participant-rejoin
  (with-fixture
    (fn [room participant resources _]
      (let [original (prepare! room participant #{:test/owned-a} nil)
            entered (promise)
            gate (CountDownLatch. 1)
            release! chat-context/release-sci-in!]
        (d/leave room :agent)
        (try
          (with-redefs [chat-context/release-sci-in!
                        (fn [ctx execution-ctx]
                          (when (= (:capability-id original) (:capability-id ctx))
                            (deliver entered true)
                            (.await gate))
                          (release! ctx execution-ctx))]
            (let [retired (future (room-context/drop-ctx! room :agent {:if-absent? true}))]
              (is (= true (deref entered 3000 ::timeout)))
              (is (nil? (room-context/lookup (:id room) :agent)))
              (is (= [:room-cleanup] (mapv :kind (vals (get @(:meta room) preparation/leases-key)))))
              (let [replacement (d/join room (d/participant {:id :agent :on-message (fn [_ _] (sp/spin nil))}))
                    fresh (prepare! room replacement #{:test/owned-a} nil)]
                (is (not= (:capability-id original) (:capability-id fresh)))
                (is (identical? fresh (room-context/lookup (:id room) :agent)))
                (is (= ::preparation/not-quiescent
                       (try (binding [ec/*execution-context* (:ctx room)]
                              (d/fork-room room {:clone-participants? false}))
                            nil
                            (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))
                (.countDown gate)
                (is (nil? (deref retired 3000 ::timeout)))
                (is (identical? fresh (room-context/lookup (:id room) :agent)))
                (is (= [0 1] (sort (map :releases (vals @resources)))))
                (room-context/drop-ctx! room :agent {:if-absent? true})
                (is (identical? fresh (room-context/lookup (:id room) :agent)))
                (d/close-room! room)
                (is (every? #(= 1 (:releases %)) (vals @resources))))))
          (finally (.countDown gate)))))))
