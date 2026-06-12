(ns dvergr.actors.transport-test
  "Tests for dvergr.actors.transport — the protocol seam embedders
   plug into for non-agent / non-human actor kinds."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [datahike.api :as dh]
            [dvergr.actors :as actors]
            [dvergr.actors.transport :as t]
            [dvergr.chat.schema :as schema]
            [dvergr.orchestration.tasks :as tasks]))

(def ^:dynamic *conn* nil)

(defn- mem-db-fixture [f]
  (let [cfg {:store {:backend :memory :id (random-uuid)}
             :keep-history? false
             :schema-flexibility :write}]
    (dh/create-database cfg)
    (let [conn (dh/connect cfg)]
      (schema/install-schema! conn)
      (binding [*conn* conn]
        (try (f) (finally (dh/release conn) (dh/delete-database cfg)))))))

;; The registry is shared mutable state; capture & restore around tests
;; so registering fake transports doesn't leak into other suites.
(defn- with-isolated-transports [f]
  (let [transports-atom @(ns-resolve 'dvergr.actors.transport (symbol "transports"))
        saved @transports-atom]
    (try (f)
         (finally (reset! transports-atom saved)))))

(use-fixtures :each mem-db-fixture with-isolated-transports)

;; ============================================================================
;; Defaults are present
;; ============================================================================

(deftest defaults-registered
  (is (contains? (t/registered-kinds) :agent))
  (is (contains? (t/registered-kinds) :human))
  (is (some? (t/get-transport :agent)))
  (is (some? (t/get-transport :human))))

;; ============================================================================
;; :agent transport — no-op invoke that just acknowledges
;; ============================================================================

(deftest agent-transport-acknowledges-without-side-effects
  (actors/spawn-agent! *conn* {:id :scribe :skills #{:writing}})
  (let [actor (actors/lookup *conn* :scribe)
        r     (t/dispatch *conn* actor {:task "draft something"})]
    (is (= :dispatched (:status r)))
    (is (= :scribe (:id (:actor r))))
    (is (nil? (:task r)) "no task ledger entry for agents")
    (is (= 0 (count (tasks/list-tasks *conn*))))))

;; ============================================================================
;; :human transport — task ledger + room post
;; ============================================================================

(deftest human-transport-creates-task
  (actors/spawn-human! *conn*
                       {:id :alice
                        :external-refs {:email "a@x"}
                        :skills #{:legal-review}})
  (let [actor (actors/lookup *conn* :alice)
        r     (t/dispatch *conn* actor
                          {:task "review NDA"
                           :room-id :boardroom
                           :skill :legal-review
                           :from-actor :var})]
    (is (= :dispatched (:status r)))
    (is (= :alice (:id (:actor r))))
    (is (some? (:task r)))
    (is (= "review NDA" (:content (:task r))))
    (is (= :var (:from-actor (:task r))))
    (is (= 1 (count (tasks/list-tasks *conn*))))))

;; ============================================================================
;; Unknown kind → :unsupported (the embedder gap)
;; ============================================================================

(deftest no-transport-yields-unsupported
  (actors/spawn-agent! *conn* {:id :a})
  (let [actor (actors/lookup *conn* :a)
        ;; Simulate an unsupported kind by clearing the registry
        ;; for :agent specifically.
        actor (assoc actor :kind :external)
        r     (t/dispatch *conn* actor {:task "do mcp things"})]
    (is (= :unsupported (:status r)))
    (is (= actor (:actor r)))
    (is (re-find #"No transport registered" (:error r)))))

;; ============================================================================
;; nil actor / missing task — input validation
;; ============================================================================

(deftest nil-actor-yields-no-provider
  (is (= :no-provider (:status (t/dispatch *conn* nil {:task "x"})))))

(deftest missing-task-yields-error
  (actors/spawn-agent! *conn* {:id :a})
  (let [actor (actors/lookup *conn* :a)
        r     (t/dispatch *conn* actor {})]
    (is (= :error (:status r)))
    (is (re-find #":task is required" (:error r)))))

;; ============================================================================
;; Custom transport registration — the embedder seam
;; ============================================================================

(defrecord FakeMcpTransport [calls]
  t/PActorTransport
  (invoke [_ _conn actor opts]
    (swap! calls conj {:actor (:id actor) :opts opts})
    {:actor actor :status :dispatched :task {:id "fake-task" :content (:task opts)}})
  (probe-skills [_ _conn _actor]
    #{:fake-mcp-skill}))

(deftest custom-transport-overrides-default
  (let [calls (atom [])]
    (t/register-transport! :external (->FakeMcpTransport calls))
    (actors/spawn-agent! *conn* {:id :weather-service})
    (let [actor (-> (actors/lookup *conn* :weather-service)
                    (assoc :kind :external))
          r     (t/dispatch *conn* actor {:task "what's it like in Berlin?"})]
      (is (= :dispatched (:status r)))
      (is (= "fake-task" (:id (:task r))))
      (is (= 1 (count @calls)))
      (is (= :weather-service (:actor (first @calls)))))))

(deftest probe-skills-uses-transport
  (let [transport (->FakeMcpTransport (atom []))]
    (t/register-transport! :external transport)
    (is (= #{:fake-mcp-skill}
           (t/probe-skills transport *conn* {:id :x :kind :external})))))

;; ============================================================================
;; Transport that throws — should be caught + reported as :error
;; ============================================================================

(defrecord ExplodingTransport []
  t/PActorTransport
  (invoke [_ _conn _actor _opts] (throw (ex-info "boom" {})))
  (probe-skills [_ _conn _actor] #{}))

(deftest exception-becomes-error-result
  (t/register-transport! :external (->ExplodingTransport))
  (actors/spawn-agent! *conn* {:id :crashy})
  (let [actor (-> (actors/lookup *conn* :crashy)
                  (assoc :kind :external))
        r     (t/dispatch *conn* actor {:task "boom"})]
    (is (= :error (:status r)))
    (is (= "boom" (:error r)))
    (is (= actor (:actor r)))))
