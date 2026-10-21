(ns dvergr.orchestration.daemon-lifecycle-test
  "Standalone daemon lifecycle contracts. Never nested inside a live daemon fixture."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [dvergr.orchestration.daemon :as daemon]
            [dvergr.system.db :as sdb]
            [dvergr.substrate.paths :as paths]
            [dvergr.substrate.config :as config]
            [dvergr.security.allowlist :as allowlist]
            [dvergr.test-registries :as test-registries]))

(defn- tmp-db-path []
  (str (System/getProperty "java.io.tmpdir") "/dvergr-daemon-lifecycle-db-" (random-uuid)))

(use-fixtures :each
  (fn [f]
    (let [predecessor @daemon/current-daemon
          _ (when (and predecessor (not= :stopped @(:status predecessor)))
              (throw (ex-info "Lifecycle fixture requires no active predecessor daemon" {})))
          snap (test-registries/snapshot)
          previous-home (paths/home)
          joined? (atom false)]
      (try
        (paths/set-home! (str (System/getProperty "java.io.tmpdir") "/dvergr-daemon-lifecycle-home-" (random-uuid)))
        (sdb/reset-conn!)
        (f)
        (finally
          ;; Even assertion/exception exits must leave no active owner for the
          ;; next test. A normal test already stopped this daemon.
          (try
            (let [d @daemon/current-daemon]
              (when (and d (not (identical? d predecessor)) (not= :stopped @(:status d)))
                (daemon/stop! d))
              (reset! joined? true))
            (finally
              (try
                (test-registries/restore! snap)
                (finally
                  (try
                    ;; Unknown native shutdown is not permission to discard its
                    ;; remaining connection ownership or force-clear room state.
                    (when @joined? (sdb/reset-conn!))
                    (finally (paths/set-home! previous-home))))))))))))

(deftest test-daemon-start-stop-no-telegram
  (testing "Daemon starts and stops without Telegram"
    (let [d (daemon/start! {:db-path (tmp-db-path)
                            :agents {:echo {:provider :fireworks
                                            :model "test-model"
                                            :system-prompt "Echo agent"
                                            :tags #{:echo}
                                            :description "Test echo agent"}}})]
      (try
        (is (= :running @(:status d)))
        (is (nil? (:telegram-ch d)))

      ;; Check the config agent is registered. Presence (online-actors) is a
      ;; JVM-global view across all daemons in the test JVM (single-daemon-per-JVM
      ;; design), so assert membership of THIS daemon's :echo, not an exact count.
        (let [by-id (into {} (map (juxt :id identity)) (daemon/list-agents d))]
          (is (contains? by-id :echo) ":echo agent is hosted")
          (is (= #{:echo} (:tags (by-id :echo)))))

      ;; Check daemon status
        (let [status (daemon/daemon-status d)]
          (is (= :running (:status status)))
          (is (pos? (:agents status)))
          (is (false? (:telegram-connected? status))))

      ;; Stop
        (is (= :stopped (daemon/stop! d)))
        (is (= :stopped @(:status d)))
        (finally (when (not= :stopped @(:status d)) (daemon/stop! d)))))))

(deftest test-daemon-start-initialises-allowlist
  (testing "every start installs the configured allowlist policy, empty lists included,
            so a restart never keeps the previous process's state"
    (let [outsider {:id 999 :username "outsider"}]
      (try
        (let [d (daemon/start! {:db-path (tmp-db-path) :allowed-users [{:id 123}]})]
          (try
            (is (true? (allowlist/allowed? {:id 123})))
            (is (false? (allowlist/allowed? outsider)))
            (finally (daemon/stop! d))))
        (let [d (daemon/start! {:db-path (tmp-db-path)
                                :allowed-users [] :strict-allowlist? true})]
          (try
            (is (false? (allowlist/allowed? {:id 123})) "previous users are cleared")
            (is (false? (allowlist/allowed? outsider)) "strict + empty denies")
            (finally (daemon/stop! d))))
        (let [d (daemon/start! {:db-path (tmp-db-path)})]
          (try
            (is (true? (allowlist/allowed? outsider))
                "no allowlist and no strict flag: open, as documented")
            (finally (daemon/stop! d))))
        (finally
          (allowlist/configure! {:users []}))))))

(deftest test-start-from-config-rereads-the-file
  (testing "a restart through start-from-config! applies an edited config file"
    (let [file   (java.io.File/createTempFile "dvergr-daemon-config" ".edn")
          cfg    @#'config/config-atom
          saved  @cfg
          start! (fn [m]
                   (spit file (pr-str m))
                   (with-redefs-fn {#'config/getenv (fn [k] (when (= k "DVERGR_CONFIG")
                                                              (str file)))}
                     #(daemon/start-from-config! {:db-path (tmp-db-path)})))]
      (try
        (let [d (start! {:allowed-users [{:id 123}]})]
          (try (is (true? (allowlist/allowed? {:id 123})))
               (finally (daemon/stop! d))))
        (let [d (start! {:allowed-users [] :strict-allowlist? true})]
          (try (is (false? (allowlist/allowed? {:id 123})) "the revoked user is out")
               (finally (daemon/stop! d))))
        (finally
          (reset! cfg saved)
          (.delete file)
          (allowlist/configure! {:users []}))))))

(deftest test-daemon-no-agents-config
  (testing "Daemon starts cleanly with no :agents in config — the file-driven
            var secretary (resources/agents/var.md, autostart: true) still boots"
    (let [d (daemon/start! {:db-path (tmp-db-path)})]
      (try
        (is (= :running @(:status d)))
        (is (contains? (into #{} (map :id) (daemon/list-agents d)) :var)
            "var autostarts from its definition file even with no :agents config")
        (finally (daemon/stop! d))))))
