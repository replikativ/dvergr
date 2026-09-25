(ns dvergr.rooms.context-scope-test
  "A room's context holds only the room's own systems. An embedder may register
   systems on the daemon root (simmis registers each room's store as a `kb:`
   system there); a room created afterwards, and the worlds forked from it, must
   not see or branch them."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [datahike.api :as dh]
            [dvergr.ops :as ops]
            [dvergr.orchestration.daemon :as daemon]
            [dvergr.rooms.forks :as forks]
            [dvergr.substrate.datahike :as sdh]
            [dvergr.substrate.paths :as paths]
            [dvergr.system.db :as sdb]
            [konserve.core :as k]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.yggdrasil :as ygg]))

(def ^:dynamic *daemon* nil)

(use-fixtures :once
  (fn [f]
    (let [prev-home (paths/home)]
      (paths/set-home! (str (System/getProperty "java.io.tmpdir") "/dvergr-scope-test-" (random-uuid)))
      (sdb/reset-conn!)
      (let [d (daemon/start! {:agents {}})]
        (try
          (binding [*daemon* d] (f))
          (finally
            (try (daemon/stop! d) (catch Exception _))
            (sdb/reset-conn!)
            (paths/set-home! prev-home)))))))

(defn- systems [ctx]
  (binding [ec/*execution-context* ctx] (set (keys (ygg/registered-systems)))))

(deftest a-room-sees-none-of-the-roots-systems
  (let [root (:execution-ctx *daemon*)
        cfg {:store {:backend :memory :id (random-uuid)} :schema-flexibility :read}
        _ (dh/create-database cfg)
        conn (binding [ec/*execution-context* root]
               (sdh/provision! {:cfg cfg :schema? false :system-name "kb:embedder"}))
        branches #(k/get (:store @conn) :branches nil {:sync? true})
        before (branches)]
    (try
      (is (contains? (systems root) "kb:embedder") "registered on the root")
      (ops/invoke *daemon* :room/create {:slug "scoped-room"})
      (let [room (ops/resolve-room *daemon* "scoped-room")
            world (binding [ec/*execution-context* root] (forks/fork! room))]
        (try
          (testing "the room and its worlds hold only the room's own systems"
            (is (not (contains? (systems (:ctx room)) "kb:embedder")))
            (is (not (contains? (systems (:ctx world)) "kb:embedder")))
            (is (some #(re-find #"^room-msgs-" %) (systems (:ctx room)))))
          (testing "nor do they branch the root's store"
            (is (= before (branches))))
          (finally (binding [ec/*execution-context* root] (forks/discard! world)))))
      (finally
        (binding [ec/*execution-context* root] (ygg/unregister! "kb:embedder"))
        (dh/release conn)
        (dh/delete-database cfg)))))
