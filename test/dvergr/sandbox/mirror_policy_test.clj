(ns dvergr.sandbox.mirror-policy-test
  "The host-namespace mirror policy is a trust boundary: agents read untrusted
   input (mail, web, chat), so anything reachable from `(require …)` inside the
   sandbox is reachable by prompt injection.

   These tests pin the boundary's POLARITY. The policy used to be a denylist,
   which meant every namespace nobody thought to name was reachable — and
   `ensure-mirrored!` copies every public var of whatever it mirrors, so that
   included the host's credentials and live database connections."
  (:require [clojure.test :refer [deftest testing is]]
            [dvergr.sandbox.deps :as deps]
            [org.replikativ.spindel.engine.context :as ctx]
            [org.replikativ.spindel.engine.core :as rtc]))

(defmacro with-ctx
  "Policy WRITES go through the spindel execution context (so a forked room
   carries its own policy), which needs one bound. Reads deliberately do not —
   they fall back to the built-in defaults, which deny."
  [& body]
  `(binding [rtc/*execution-context* (ctx/create-execution-context)]
     ~@body))

(deftest denies-by-default
  (testing "a namespace nobody enumerated is NOT mirrorable"
    ;; The regression that matters: this must fail closed, not open.
    (is (not (deps/namespace-mirrorable? 'com.example.nobody.thought.of.this)))
    (is (not (deps/namespace-mirrorable? 'some.new.dvergr.subsystem)))))

(deftest denies-credential-bearing-namespaces
  (testing "host config / auth namespaces are unreachable"
    ;; dvergr.substrate.config exposes github-token / telegram-token;
    ;; is.simm.runtimes.auth-config exposes the JWT signing secret as a public var.
    (doseq [ns- '[dvergr.substrate.config
                  is.simm.runtimes.auth-config
                  is.simm.model.access]]
      (is (not (deps/namespace-mirrorable? ns-)) (str ns- " must not be mirrorable")))))

(deftest denies-cross-tenant-data-access
  (testing "system DB and room registries are unreachable"
    ;; Reaching these yields conns to OTHER rooms and to the shared system DB,
    ;; which is a cross-tenant boundary, not just a dvergr-internals boundary.
    (doseq [ns- '[is.simm.model.system-db
                  dvergr.system.db
                  dvergr.system.rooms
                  dvergr.room.registry]]
      (is (not (deps/namespace-mirrorable? ns-)) (str ns- " must not be mirrorable")))))

(deftest denies-governance-and-substrate
  (testing "datahike.tx-preds stays unreachable"
    ;; The accounting governor is a per-store tx-predicate enforced inside
    ;; datahike's writer, which is what makes it hold even for a raw d/transact
    ;; on a conn the agent legitimately owns. Mirroring this namespace exposes
    ;; unregister-tx-pred! — one call and that guarantee is gone.
    (is (not (deps/namespace-mirrorable? 'datahike.tx-preds))))
  (testing "raw datahike/konserve/kabel stay unreachable"
    ;; dvergr.sandbox.ns.datahike injects the data-ops while keeping
    ;; create/connect/delete room-guarded; mirroring the raw API would undo it.
    (doseq [ns- '[datahike.api datahike.connector konserve.core kabel.peer]]
      (is (not (deps/namespace-mirrorable? ns-)) (str ns- " must not be mirrorable"))))
  (testing "sci's own internals stay unreachable"
    (is (not (deps/namespace-mirrorable? 'sci.core))))
  (testing "host filesystem/process access stays unreachable"
    ;; The agent gets muschel's virtual FS; clojure.java.io would bypass it.
    (doseq [ns- '[clojure.java.io clojure.java.shell]]
      (is (not (deps/namespace-mirrorable? ns-)) (str ns- " must not be mirrorable")))))

(deftest allows-the-curated-library-surface
  (testing "pure data/format libraries remain available"
    (doseq [ns- '[cheshire.core clojure.data.xml clojure.zip clojure.test babashka.fs]]
      (is (deps/namespace-mirrorable? ns-) (str ns- " should stay mirrorable")))))

(deftest add-libs-provenance-widens-but-not-past-the-hard-denylist
  (with-ctx
    (testing "an approved add-libs makes its own namespaces requirable"
      (deps/allow-added-lib-namespaces! '[org.clojure/data.csv])
      (is (deps/namespace-mirrorable? 'clojure.data.csv)))
    (testing "provenance cannot be used to reach the host application"
      ;; A coord whose name collides with a denied prefix must not open it up.
      (deps/allow-added-lib-namespaces! '[is.simm/model my.group/tx-preds])
      (is (not (deps/namespace-mirrorable? 'is.simm.model.system-db)))
      (is (not (deps/namespace-mirrorable? 'datahike.tx-preds))))))

(deftest caller-allowlist-cannot-widen-past-the-hard-denylist
  (with-ctx
    (testing "set-namespace-allowlist! is bounded by the hard denylist"
      (deps/set-namespace-allowlist! [".*"])            ; maximally permissive
      (is (not (deps/namespace-mirrorable? 'is.simm.runtimes.auth-config)))
      (is (not (deps/namespace-mirrorable? 'datahike.tx-preds)))
      (is (not (deps/namespace-mirrorable? 'sci.core)))
      ;; ...and the curated surface still resolves under the wide allowlist
      (is (deps/namespace-mirrorable? 'cheshire.core)))))
