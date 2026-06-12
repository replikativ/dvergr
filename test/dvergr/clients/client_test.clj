(ns dvergr.clients.client-test
  "Tests for dvergr.clients.client — the nREPL-attached convenience wrapper.

   The unboxing ritual (`daemon → ctx → conn`) is what the wrapper
   exists for, so these tests fake a daemon record into
   `dvergr.orchestration.daemon/current-daemon` and exercise the surface."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [datahike.api :as dh]
            [dvergr.actors :as actors]
            [dvergr.chat.schema :as schema]
            [dvergr.clients.client :as client]
            [dvergr.orchestration.daemon :as daemon]
            [dvergr.substrate.paths :as paths]
            [dvergr.system.db :as sdb]
            [org.replikativ.spindel.engine.context :as sctx]
            [org.replikativ.spindel.engine.core :as ec]))

(def ^:dynamic *conn* nil)

;; RF5 S3: actor identity is global — it lives in the system-db, not the
;; per-room chat-db. `*sys-conn*` is the isolated system-db the client's actor
;; ops read/write; `*conn*` remains the room/task chat-db the daemon mock
;; registers under "dvergr-chat-db".
(def ^:dynamic *sys-conn* nil)

(defn- mem-db-fixture [f]
  (let [cfg {:store {:backend :memory :id (random-uuid)}
             :keep-history? false
             :schema-flexibility :write}]
    (dh/create-database cfg)
    (let [conn (dh/connect cfg)]
      (schema/install-schema! conn)
      (binding [*conn* conn]
        (try (f) (finally (dh/release conn) (dh/delete-database cfg)))))))

(defn- sys-db-fixture
  "Isolate the global system-db into a temp home so the client's actor ops
   don't touch the developer's real .dvergr/system-db."
  [f]
  (let [tmp (str (java.nio.file.Files/createTempDirectory
                  "dvergr-client-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        saved-home @@#'paths/home-atom]
    (paths/set-home! tmp)
    (reset! @#'sdb/conn-atom nil)
    (binding [*sys-conn* (sdb/get-conn)]
      (try (f)
           (finally
             (reset! @#'sdb/conn-atom nil)
             (paths/set-home! saved-home))))))

(defn- with-daemon-mock
  "Fake a running daemon so dvergr.clients.client/with-ctx finds something. We
   register the test's Datahike conn under the canonical external-ref
   key (\"dvergr-chat-db\") so client/with-ctx hands the right conn
   to its callees."
  [f]
  (let [ctx (sctx/create-execution-context)
        saved @daemon/current-daemon]
    (binding [ec/*execution-context* ctx]
      (ec/swap-state! [:external-refs "dvergr-chat-db"]
                      (constantly {:conn *conn*})))
    (reset! daemon/current-daemon
            {:execution-ctx ctx
             :config        {}
             :status        (atom :running)})
    (try (f)
         (finally (reset! daemon/current-daemon saved)))))

(use-fixtures :each mem-db-fixture sys-db-fixture with-daemon-mock)

;; ============================================================================
;; Unboxing semantics
;; ============================================================================

(deftest no-daemon-throws
  (testing "calling client/* with no daemon throws a useful error"
    (let [saved @daemon/current-daemon]
      (reset! daemon/current-daemon nil)
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"No daemon running"
                              (client/actors)))
        (finally (reset! daemon/current-daemon saved))))))

(deftest list-agents-roundtrips
  (actors/spawn-agent! *sys-conn* {:id :alpha :skills #{:research}})
  (actors/spawn-agent! *sys-conn* {:id :beta  :skills #{:prose}})
  (let [ids (set (map :id (client/actors)))]
    (is (= #{:alpha :beta} ids))))

(deftest list-agents-filters-pass-through
  (actors/spawn-agent! *sys-conn* {:id :a1 :skills #{:research}})
  (actors/spawn-human! *sys-conn* {:id :h1
                                   :external-refs {:email "h@x"}
                                   :skills #{:research}})
  (is (= 1 (count (client/actors :kind :agent))))
  (is (= 1 (count (client/actors :kind :human))))
  (is (= 2 (count (client/actors :skill :research)))))

(deftest lookup-returns-actor
  (actors/spawn-agent! *sys-conn* {:id :x :name "Ex"})
  (is (= "Ex" (:name (client/actor :x))))
  (is (nil? (client/actor :nonexistent))))

(deftest spawn-and-dismiss
  (let [actor (client/register-actor! {:id :spawned :skills #{:foo}})]
    (is (= :spawned (:id actor)))
    (is (= :online (:status actor)))
    (client/dismiss-actor! :spawned)
    (is (= :retired (:status (client/actor :spawned))))))

(deftest spawn-human-requires-external-refs
  (is (thrown? AssertionError
               (client/register-actor! {:kind :human :id :h :name "H"}))))

;; ============================================================================
;; Dispatch goes through skills/dispatch! → transport
;; ============================================================================

(deftest dispatch-returns-result-map
  ;; RF5 S3: actors + tasks both live in the global system-db; dispatch reads/
  ;; writes there.
  (actors/spawn-human! *sys-conn*
                       {:id :alice
                        :external-refs {:email "a@x"}
                        :skills #{:legal-review}})
  (let [r (client/dispatch :legal-review "review NDA"
                           :from-actor :user-vscode)]
    (is (= :dispatched (:status r)))
    (is (= :alice (:id (:actor r))))
    (is (= "review NDA" (:content (:task r))))))

(deftest dispatch-no-provider
  (let [r (client/dispatch :nobody-provides "tilt")]
    (is (= :no-provider (:status r)))))

;; ============================================================================
;; Tasks
;; ============================================================================

(deftest task-list-and-complete
  (actors/spawn-human! *sys-conn*
                       {:id :alice
                        :external-refs {:email "a@x"}
                        :skills #{:writing}})
  (let [r (client/dispatch :writing "draft a haiku")
        task-id (:id (:task r))]
    (is (= 1 (count (client/list-tasks))))
    (client/complete-task! task-id "done — here's the haiku")
    (let [t (first (client/list-tasks :actor-id :alice :status :completed))]
      (is (= "done — here's the haiku" (:result t)))
      (is (instance? java.util.Date (:completed-at t))))))
