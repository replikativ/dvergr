(ns dvergr.ops-test
  "Daemon-independent checks on the central operations spec. The :impl bodies are
   exercised live against a running daemon (see the room CRUD/fork round-trip in
   the REPL); here we lock the spec's shape and the derivation helpers."
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [dvergr.ops :as ops]))

(deftest spec-entries-well-formed
  (testing "every op carries :doc :kind :schema :impl, kind is :read|:write"
    (doseq [[op spec] ops/specification]
      (is (qualified-keyword? op) (str op " is a namespaced key"))
      (is (string? (:doc spec))            (str op " :doc"))
      (is (#{:read :write} (:kind spec))   (str op " :kind"))
      ;; :schema is a malli [:map …] that validates + projects to JSON Schema
      (is (= :map (m/type (m/schema (:schema spec)))) (str op " :schema is a malli map"))
      (is (= "object" (:type (ops/input-schema op)))  (str op " projects to a JSON object"))
      (is (fn? (:impl spec))               (str op " :impl")))))

(deftest op->name-flattens-namespace
  (is (= "room_post"   (ops/op->name :room/post)))
  (is (= "room_messages" (ops/op->name :room/messages)))
  (is (= "agent_list"  (ops/op->name :agent/list))))

(deftest reads-and-writes-partition-the-spec
  (let [r (set (keys (ops/reads)))
        w (set (keys (ops/writes)))]
    (is (empty? (clojure.set/intersection r w)) "no op is both read and write")
    (is (= (set (keys ops/specification)) (clojure.set/union r w)) "every op is classified")
    (is (every? #(= :read  (:kind (ops/specification %))) r))
    (is (every? #(= :write (:kind (ops/specification %))) w))))

(deftest invoke-rejects-unknown-op
  (is (thrown? clojure.lang.ExceptionInfo (ops/invoke nil :no/such-op {}))))
