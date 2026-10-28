(ns dvergr.sandbox.scratch-cleanup-test
  (:require [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [dvergr.runtime.ctx :as runtime]
            [dvergr.sandbox :as sandbox]
            [dvergr.sandbox.ns.datahike :as database]
            [org.replikativ.spindel.engine.context :as context]
            [org.replikativ.spindel.engine.core :as ec]))

(deftest scratch-deletion-retry-preserves-release-acks
  (let [a {:connection :conn-a :config {:id :a}}
        b {:connection :conn-b :config {:id :b}}
        state (atom {:ephemeral-databases {"a" a "b" b}})
        fail? (atom true) releases (atom []) deletions (atom [])]
    (with-redefs [d/release #(swap! releases conj %)
                  d/database-exists? (constantly true)
                  d/delete-database (fn [cfg]
                                      (swap! deletions conj (:id cfg))
                                      (when (and (= :a (:id cfg)) @fail?)
                                        (throw (ex-info "Delete failed" {}))))]
      (let [errors (database/dispose-ephemeral-databases! #(deref state) (fn [f & args] (apply swap! state f args)))]
        (is (= ["a"] (mapv :name errors)))
        (is (= #{"a"} (set (keys (:ephemeral-databases @state)))))
        (is (::database/connection-released? (get-in @state [:ephemeral-databases "a"])))
        (is (= #{:conn-a :conn-b} (set @releases))))
      (reset! fail? false)
      (is (empty? (database/dispose-ephemeral-databases! #(deref state) (fn [f & args] (apply swap! state f args)))))
      (is (= 2 (count @releases)) "Successful releases do not repeat when deletion needs retry")
      (is (= [:a :b :a] @deletions))
      (is (empty? (:ephemeral-databases @state))))))

(deftest capability-cleanup-propagates-failure-without-forgetting-the-resource
  (let [ctx (context/create-execution-context)
        id (random-uuid)
        entry {:connection :conn :config {:id :owned}}]
    (try
      (runtime/install-sandbox-binding! ctx id {:ephemeral-databases {"db" entry}})
      (with-redefs [d/release (fn [_] (throw (ex-info "Release refused" {})))]
        (is (= ::sandbox/resource-cleanup-incomplete
               (try (sandbox/release-agent-resources! ctx id) nil
                    (catch Exception e (:type (ex-data e)))))))
      (is (identical? entry (get-in (runtime/sandbox-binding ctx id) [:ephemeral-databases "db"])))
      (finally (context/close-context! ctx)))))

(deftest cleanup-selects-the-explicit-world-instead-of-an-ambient-descendant
  (let [parent (context/create-execution-context) id (random-uuid) calls (atom [])
        _ (runtime/install-sandbox-binding! parent id {:ephemeral-databases {"db" {:connection :parent}}})
        child (context/fork-context parent :mode :frozen)]
    (try
      (runtime/install-sandbox-binding! child id {:ephemeral-databases {"db" {:connection :child}}})
      (with-redefs [d/release #(swap! calls conj %)]
        (binding [ec/*execution-context* child]
          (sandbox/release-agent-resources! parent id)))
      (is (= [:parent] @calls))
      (is (empty? (:ephemeral-databases (runtime/sandbox-binding parent id))))
      (is (= :child (get-in (runtime/sandbox-binding child id) [:ephemeral-databases "db" :connection])))
      (finally (context/close-context! child) (context/close-context! parent)))))
