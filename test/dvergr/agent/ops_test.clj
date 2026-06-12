(ns dvergr.agent.ops-test
  "Tests for dvergr.agent.ops — the shared agent-management layer that keeps
   the durable actor row and the project-local persona file in sync. Uses an
   in-memory Datahike registered under the canonical chat-db key, and a tmp
   `.dvergr` home so persona writes don't touch the repo."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [datahike.api :as dh]
            [dvergr.actors :as actors]
            [dvergr.agent.ops :as ops]
            [dvergr.agent.persona :as persona]
            [dvergr.chat.schema :as schema]
            [dvergr.substrate.paths :as paths]
            [dvergr.system.db :as sdb]
            [org.replikativ.spindel.engine.context :as sctx]
            [org.replikativ.spindel.engine.core :as ec]))

(def ^:dynamic *conn* nil)

(defn- mem-db+ctx-fixture [f]
  (let [cfg     {:store {:backend :memory :id (random-uuid)}
                 :keep-history? false
                 :schema-flexibility :write}
        tmp-home (str (io/file (System/getProperty "java.io.tmpdir")
                               (str "dvergr-ops-test-" (random-uuid))))]
    (dh/create-database cfg)
    (let [conn (dh/connect cfg)
          ctx  (sctx/create-execution-context)]
      (schema/install-schema! conn)
      (paths/set-home! tmp-home)
      ;; RF5 S3: ops actor reads now resolve the global system-db. Reset its
      ;; cached conn so it rebuilds against THIS test's tmp home (the prior
      ;; test's home was deleted in teardown — a stale conn would hang).
      (reset! @#'sdb/conn-atom nil)
      (binding [*conn* conn
                ec/*execution-context* ctx]
        (ec/swap-state! [:external-refs "dvergr-chat-db"]
                        (constantly {:conn conn}))
        (try (f)
             (finally
               (reset! @#'sdb/conn-atom nil)
               (paths/set-home! nil)
               (dh/release conn)
               (dh/delete-database cfg)
               (when (.exists (io/file tmp-home))
                 (run! io/delete-file
                       (reverse (file-seq (io/file tmp-home)))))))))))

(use-fixtures :each mem-db+ctx-fixture)

;; ============================================================================
;; create + get
;; ============================================================================

(deftest create-writes-row-and-prompt
  (testing "create-agent! writes an actor row carrying the system prompt"
    (let [a (ops/create-agent! {:id :scribe
                                :name "Scribe"
                                :provider :fireworks
                                :model "test-model"
                                :tags #{:writing}
                                :description "writes prose"
                                :budget-dollars 0.5
                                :prompt "You are Scribe."})]
      (is (= :scribe (:id a)))
      (is (= "Scribe" (:name a)))
      (is (= :fireworks (:provider a)))
      (is (= "test-model" (:model a)))
      (is (= #{:writing} (:tags a)))
      (is (= "writes prose" (:description a)))
      (is (= 0.5 (:budget-dollars a)))
      (is (= "You are Scribe." (:prompt a)))
      (is (= :db (:persona-source a)) "prompt resolves from the actor row")
      ;; durable: the underlying actor row carries the config + prompt
      ;; (RF5 S3: actor identity lives in the global system-db)
      (let [row (actors/lookup (sdb/get-conn) :scribe)]
        (is (= :agent (:kind row)))
        (is (= "test-model" (get-in row [:config :model])))
        (is (= "You are Scribe." (:system-prompt row)))))))

(deftest create-is-idempotent-guarded
  (testing "create-agent! refuses to overwrite an existing id"
    (ops/create-agent! {:id :dup :model "m1"})
    (is (nil? (ops/create-agent! {:id :dup :model "m2"})))
    (is (= "m1" (:model (ops/get-agent :dup))) "first write wins")))

(deftest get-unknown-returns-nil
  (is (nil? (ops/get-agent :nope))))

;; ============================================================================
;; update — partial patch must not clobber untouched config
;; ============================================================================

(deftest update-merges-config-not-clobbers
  (testing "patching one config field preserves the others"
    (ops/create-agent! {:id :v :provider :fireworks :model "m1"
                        :description "first" :budget-dollars 0.25})
    (ops/update-agent! :v {:model "m2"})
    (let [a (ops/get-agent :v)]
      (is (= "m2" (:model a))         "model updated")
      (is (= :fireworks (:provider a)) "provider preserved")
      (is (= "first" (:description a)) "description preserved")
      (is (= 0.25 (:budget-dollars a)) "budget preserved"))))

(deftest update-name-tags-and-prompt
  (testing "identity fields + persona update together"
    (ops/create-agent! {:id :v :model "m1" :tags #{:a}})
    (ops/update-agent! :v {:name "Vee" :tags #{:b :c} :prompt "New prompt."})
    (let [a (ops/get-agent :v)]
      (is (= "Vee" (:name a)))
      (is (= #{:b :c} (:tags a)))
      (is (= "New prompt." (:prompt a)))
      (is (= "New prompt." (persona/resolve-prompt :v))))))

(deftest update-unknown-returns-nil
  (is (nil? (ops/update-agent! :ghost {:model "x"}))))

;; ============================================================================
;; list
;; ============================================================================

(deftest list-returns-all-agent-rows-sorted
  (ops/create-agent! {:id :charlie :model "m"})
  (ops/create-agent! {:id :alice :model "m"})
  (ops/create-agent! {:id :bob :model "m"})
  ;; a non-agent actor must not appear (seed into the same system-db ops reads)
  (actors/spawn-human! (sdb/get-conn) {:id :human-x :external-refs {:telegram 1}})
  (let [ids (mapv :id (ops/list-agents))]
    (is (= [:alice :bob :charlie] ids) "only agents, sorted by id")))

;; ============================================================================
;; delete
;; ============================================================================

(deftest delete-removes-row-and-prompt
  (testing "delete-agent! retracts the row (the stored prompt goes with it)"
    (ops/create-agent! {:id :tmp :model "m" :prompt "bye"})
    (is (= :db (persona/source :tmp)))
    (is (= :deleted (ops/delete-agent! :tmp)))
    (is (nil? (actors/lookup (sdb/get-conn) :tmp)) "actor row gone")
    (is (= :none (persona/source :tmp)) "no stored prompt, no builtin resource")
    (is (nil? (ops/delete-agent! :tmp)) "second delete is a no-op")))
