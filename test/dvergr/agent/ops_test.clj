(ns dvergr.agent.ops-test
  "Tests for dvergr.agent.ops — the shared agent-management layer that keeps
   the durable actor row and the project-local persona file in sync. Uses an
   in-memory Datahike registered under the canonical chat-db key, and a tmp
   `.dvergr` home so persona writes don't touch the repo."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [datahike.api :as dh]
            [dvergr.actors :as actors]
            [dvergr.agent.fields :as fields]
            [dvergr.agent.ops :as ops]
            [dvergr.ops :as spec]
            [dvergr.orchestration.daemon :as daemon]
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

;; ============================================================================
;; Wire values: what MCP, the JSON API and the REPL send (strings)
;; ============================================================================

(defn- status-of [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (spec/error-status e))))

(deftest normalize-coerces-wire-values-and-keeps-typed-ones
  (is (= {:tags #{:a :b} :provider :openai :budget-dollars 1.5 :name "N"}
         (fields/normalize {:tags "a, :b" :provider "openai" :budget-dollars "1.5" :name " N "})))
  (is (= {:tags #{:a} :provider :fireworks :budget-dollars 2.0}
         (fields/normalize {:tags #{:a} :provider :fireworks :budget-dollars 2})))
  (is (= {:tags #{:a :b}} (fields/normalize {:tags ["a" :b]})))
  (is (= {:tags #{}} (fields/normalize {:tags ""})) "a blank tag box clears")
  (is (= {:rooms ["x"]} (fields/normalize {:rooms ["x"] :name "  "}))
      "a blank field is omitted; non-field keys pass through")
  (doseq [bad [{:tags 42} {:provider 7} {:budget-dollars "lots"} {:model :m} {:tags [1]}]]
    (is (= 400 (status-of #(fields/normalize bad))) (pr-str bad))))

(def ^:private dmn {:execution-ctx nil})

(deftest update-op-stores-wire-tags-and-provider-typed
  (ops/create-agent! {:id :w :model "m1" :tags #{:old}})
  (spec/invoke dmn :agent/update {:id "w" :patch {:tags "a b" :provider "openai"}})
  (let [a (ops/get-agent :w)]
    (is (= #{:a :b} (:tags a)) "tags are keywords, the old one replaced")
    (is (= :openai (:provider a)) "provider is a keyword")))

(deftest update-op-rejects-an-invalid-patch-before-writing
  (ops/create-agent! {:id :w :model "m1" :tags #{:old}})
  (is (= 400 (status-of #(spec/invoke dmn :agent/update {:id "w" :patch {:model "m2" :tags 42}}))))
  (let [a (ops/get-agent :w)]
    (is (= "m1" (:model a)) "nothing of the patch was applied")
    (is (= #{:old} (:tags a)))))

(deftest update-op-of-an-unknown-agent-is-an-error
  (is (= 404 (status-of #(spec/invoke dmn :agent/update {:id "ghost" :patch {:model "x"}})))))

(deftest create-op-stores-wire-values-typed-and-refuses-bad-ids
  (with-redefs [daemon/provision-agent! (fn [_ fields] (ops/create-agent! fields))]
    (is (= {:created "probe"}
           (spec/invoke dmn :agent/create {:id " probe " :tags "a b" :provider "openai"})))
    (let [a (ops/get-agent :probe)]
      (is (= #{:a :b} (:tags a)))
      (is (= :openai (:provider a))))
    (is (= 400 (status-of #(spec/invoke dmn :agent/create {:id "   "}))) "a blank id")
    (is (= 409 (status-of #(spec/invoke dmn :agent/create {:id "probe"}))) "a taken id")
    (is (= 400 (status-of #(spec/invoke dmn :agent/create {:id "p2" :tags 42}))))
    (is (nil? (ops/get-agent :p2)) "an invalid create writes nothing")))

(deftest invite-op-of-an-unknown-agent-or-room-is-an-error
  (with-redefs [spec/resolve-room (fn [_ room] (when (= "r" room) {:id :r}))
                daemon/agent-join-config (fn [_ _] nil)]
    (is (= 404 (status-of #(spec/invoke dmn :room/invite {:room "r" :agent "nobody"}))))
    (is (= 404 (status-of #(spec/invoke dmn :room/invite {:room "nowhere" :agent "nobody"}))))))
