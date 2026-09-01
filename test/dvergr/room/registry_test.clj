(ns dvergr.room.registry-test
  (:require [clojure.test :refer [deftest is]]
            [dvergr.discourse :as d]
            [dvergr.room.registry :as registry]
            [dvergr.room.store.memory :as memory]
            [org.replikativ.spindel.engine.context :as context]
            [org.replikativ.spindel.engine.core :as ec]))

(deftest fork-registration-runs-admission-fences-before-publication
  (let [root-ctx (context/create-execution-context)
        child-ctx (context/fork-context root-ctx :mode :frozen)
        room-id (keyword (str "rejected-fork-" (random-uuid)))
        parent-id (keyword (str "parent-" (random-uuid)))
        room (d/make-room {:id room-id
                           :ctx child-ctx
                           :store (memory/make)})
        hook-id ::reject-this-fork]
    ;; make-room auto-registers ordinary Rooms; remove that initial projection
    ;; so this test exercises register-fork!'s publication boundary itself.
    (binding [ec/*execution-context* root-ctx]
      (registry/unregister! room-id))
    (try
      (binding [ec/*execution-context* root-ctx]
        (registry/add-pre-register-hook!
         hook-id
         (fn [candidate]
           (when (= room-id (:id candidate))
             (throw (ex-info "rejected by admission fence" {})))))
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo #"rejected by admission fence"
             (registry/register-fork! room parent-id (random-uuid))))
        (is (nil? (registry/lookup room-id))
            "a rejected fork is never globally visible as a Room")
        (is (empty? (filter #(= room-id (:fork/id %))
                            (registry/structural-children parent-id)))
            "a rejected fork publishes no topology edge"))
      (finally
        ;; Hooks are process-global and keyed for reload; neutralize this
        ;; test-specific fence without disturbing production hooks.
        (registry/add-pre-register-hook! hook-id (constantly nil))
        (context/close-context! child-ctx)
        (context/close-context! root-ctx)))))
