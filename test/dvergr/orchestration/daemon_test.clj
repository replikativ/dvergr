(ns dvergr.orchestration.daemon-test
  "Integration tests for the daemon with mock agents.

   These tests share one daemon (`*shared-daemon*`) — each `daemon/start!`
   does heavy I/O (system-db and room stores, Geschichte workspaces, room
   subsystems) that takes ~3-5s and dominates the suite.
   Standalone start/stop tests live in daemon-lifecycle-test so they never
   open a second daemon over this fixture's live room stores."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [dvergr.orchestration.daemon :as daemon]
            [dvergr.actors :as actors]
            [dvergr.discourse :as disc]
            [dvergr.discourse.llm :as llm]
            [dvergr.ops :as ops]
            [dvergr.room.registry :as rreg]
            [dvergr.room.store :as rstore]
            [dvergr.system.db :as sdb]
            [dvergr.substrate.paths :as paths]
            [dvergr.channels.telegram :as tg]
            [dvergr.security.allowlist :as allowlist]
            [dvergr.test-registries :as test-registries]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.yggdrasil :as ygg]))

;; ============================================================================
;; Fixtures
;; ============================================================================

(defn- tmp-db-path
  "Unique temp datahike path so the test daemon never writes actor/chat
   rows into the repo's real .datahike (test isolation)."
  []
  (str (System/getProperty "java.io.tmpdir") "/dvergr-daemon-test-" (random-uuid)))

(def ^:dynamic *shared-daemon* nil)

(defn- snapshot-daemon [d]
  (when d
    (binding [ec/*execution-context* (:execution-ctx d)]
      ;; Snapshot participants of EVERY registered room, not just the
      ;; :daemon room — agents also join boardroom/config rooms, and
      ;; presence-derived views (actors/online-actors) read all rooms.
      {:rooms (into {} (map (fn [r] [(:id r) @(:participants r)]))
                    (rreg/list-rooms))})))

(defn- restore-daemon! [d snap]
  (when (and d snap)
    (binding [ec/*execution-context* (:execution-ctx d)]
      (doseq [r (rreg/list-rooms)]
        (reset! (:participants r) (get (:rooms snap) (:id r) {}))))))

;; :once — start ONE daemon for the whole ns. Standalone lifecycle tests
;; have a separate namespace/fixture, never a second concurrent daemon.
;;
;; Re-root ALL dvergr state (system-db, workspace, …) to an isolated temp dir for
;; the whole ns: the system-DB conn is a JVM-global singleton at
;; `paths/system-db-dir` that ignores per-daemon `:db-path`, so without this the
;; test daemons read+pollute the real `.dvergr/system-db` (and inherit its rows
;; across runs). `paths/set-home!` + `sdb/reset-conn!` give a clean, throwaway
;; system-db. (Multiple daemons within ONE run still share that temp system-db —
;; by design dvergr is single-daemon-per-JVM — so the per-daemon assertions below
;; are membership-based, not exact global counts.)
(use-fixtures :once
  (fn [f]
    (let [snap     (test-registries/snapshot)
          prev-home (paths/home)
          _        (paths/set-home! (str (System/getProperty "java.io.tmpdir")
                                         "/dvergr-daemon-test-home-" (random-uuid)))
          _        (sdb/reset-conn!)
          d (daemon/start! {:agents {} :db-path (tmp-db-path)})]
      (try
        (binding [*shared-daemon* d]
          (f))
        (finally
          (try (daemon/stop! d) (catch Exception _))
          (test-registries/restore! snap)
          (sdb/reset-conn!)
          (paths/set-home! prev-home))))))

;; :each — between tests, restore the global registry/sessions/etc.
;; AND the shared daemon's room participants + response sinks so
;; per-test agent creation doesn't leak across tests.
;;
;; ALSO bind the daemon's execution-context for the test body so that
;; ctx-scoped state (sessions, stats, schedules, rooms-bus subscribers)
;; reads/writes land on the same ctx the daemon writes to from its
;; dispatch path.
(use-fixtures :each
  (fn [f]
    (let [d *shared-daemon*
          ctx (when d (:execution-ctx d))]
      (binding [ec/*execution-context* (or ctx ec/*execution-context*)]
        (let [g-snap (test-registries/snapshot)
              d-snap (snapshot-daemon *shared-daemon*)]
          (try
            (f)
            (finally
              (restore-daemon! *shared-daemon* d-snap)
              (test-registries/restore! g-snap))))))))

;; ============================================================================
;; Tests
;; ============================================================================

(deftest test-daemon-multiple-agents
  (testing "Daemon hosts multiple agents simultaneously"
    (let [d *shared-daemon*]
      (daemon/create-agent! d {:id :agent-a :provider :fireworks
                               :model "model-a" :tags #{:coding}})
      (daemon/create-agent! d {:id :agent-b :provider :fireworks
                               :model "model-b" :tags #{:research}})
      ;; Membership, not exact count — list-agents is a JVM-global presence view.
      (is (actors/online? :agent-a))
      (is (actors/online? :agent-b))
      (let [by-id (into {} (map (juxt :id identity)) (daemon/list-agents d))]
        (is (= #{:coding}   (:tags (by-id :agent-a))))
        (is (= #{:research} (:tags (by-id :agent-b))))))))

(deftest test-daemon-create-agent-dynamic
  (testing "create-agent! adds an agent to a running daemon"
    (let [d *shared-daemon*]
      (is (not (actors/online? :dynamic)) "not present before create")
      (daemon/create-agent! d {:id :dynamic :provider :fireworks
                               :model "test" :tags #{:dynamic}})
      (is (contains? (into #{} (map :id) (daemon/list-agents d)) :dynamic)
          "appears in list-agents after create")
      (is (actors/online? :dynamic)))))

(deftest create-agent-honors-explicit-tools
  (testing "an agent's :tools decide its tools; without them its tags do"
    (let [d *shared-daemon*
          captured (atom {})
          orig llm/llm-agent]
      (try
        (with-redefs [llm/llm-agent (fn [o] (swap! captured assoc (:id o) o) (orig o))]
          (daemon/create-agent! d {:id :tools-none :provider :fireworks :model "t" :tools #{}})
          (daemon/create-agent! d {:id :tools-read :provider :fireworks :model "t" :tools #{:read-file}})
          (daemon/create-agent! d {:id :tools-default :provider :fireworks :model "t"}))
        (is (= {} (:tools (:tools-none @captured))) "an empty :tools is no tools")
        (is (= #{"read_file"} (set (keys (:tools (:tools-read @captured))))))
        (is (= (set (keys (daemon/safe-tools)))
               (set (keys (:tools (:tools-default @captured)))))
            "no :tools and no tags: the tag-based default")
        (finally
          (doseq [id [:tools-none :tools-read :tools-default]]
            (daemon/stop-agent! d id)))))))

(deftest the-create-op-keeps-explicit-tools-for-later-joins
  (testing ":agent/create with :tools provisions those tools, and a later join reads them back"
    (let [d *shared-daemon*
          captured (atom nil)
          orig llm/llm-agent]
      (try
        (with-redefs [llm/llm-agent (fn [o] (when (= :tools-op (:id o)) (reset! captured o)) (orig o))]
          (is (= {:created "tools-op"}
                 (ops/invoke d dvergr.principal/local-principal :agent/create {:id "tools-op" :provider "fireworks" :model "t" :tools []}))))
        (is (= {} (:tools @captured)) "provisioned with no tools")
        (is (= {} (:tools (daemon/resolve-safe-config (daemon/agent-join-config d :tools-op))))
            "a room join reads the stored :tools")
        (finally (daemon/stop-agent! d :tools-op))))))

(deftest an-agents-sandbox-extensions-outlive-its-creation
  (testing "create-agent! hands :sandbox to the agent and the actor row keeps it for later joins"
    (let [d *shared-daemon*
          captured (atom nil)
          orig llm/llm-agent]
      (try
        (with-redefs [llm/llm-agent (fn [o] (when (= :ext-agent (:id o)) (reset! captured o)) (orig o))]
          (daemon/create-agent! d {:id :ext-agent :provider :fireworks :model "t"
                                   :sandbox [:foerster/inference]}))
        (is (= #{:foerster/inference} (:sandbox @captured)))
        (is (= #{:foerster/inference}
               (:sandbox (daemon/resolve-safe-config (daemon/agent-join-config d :ext-agent))))
            "a later join reads the stored :sandbox")
        (finally (daemon/stop-agent! d :ext-agent)))))
  (testing "a row written before agents had :sandbox keeps the declared one"
    (actors/ensure-agent! (sdb/get-conn) :ext-old {:provider :fireworks :model "t"})
    (let [d (assoc-in *shared-daemon* [:config :agents :ext-old] {:sandbox #{:foerster/inference}})]
      (is (= #{:foerster/inference} (:sandbox (daemon/agent-join-config d :ext-old))))
      (is (nil? (:sandbox (daemon/agent-join-config *shared-daemon* :ext-old)))))))

(deftest test-daemon-stop-agent
  (testing "stop-agent! removes one agent from a running daemon"
    (let [d *shared-daemon*]
      (daemon/create-agent! d {:id :temp :provider :fireworks :model "test"})
      (is (actors/online? :temp))
      (daemon/stop-agent! d :temp)
      (is (not (actors/online? :temp))))))

(deftest test-telegram-inbound-routes-into-per-chat-room
  (testing "the Telegram adapter upserts the user-actor and posts the message into a per-chat room as that actor (role :user)"
    (let [d    *shared-daemon*
          ;; RF5 S3: actors live in the global system-db, not the per-room chat-db.
          conn (sdb/get-conn)
          ;; :probe-agent has no config → ensure-dm-room! joins no live agent,
          ;; so no LLM turn fires; we assert only the inbound routing half.
          ;; (The agent-reply→egress half is covered by adapters.core-test.)
          caps    (#'daemon/telegram-caps d "test-token" :probe-agent false)
          adapter (tg/make-daemon-adapter caps)
          chat-id 778899
          uid     4242]
      ;; a sender is someone only when the allowlist names it (dvergr.principal)
      (dvergr.security.allowlist/set-users! [uid])
      (try
        (tg/handle-inbound! adapter
                            {:chat-id chat-id
                             :text    "hello from telegram"
                             :from    {:id uid :username "tguser" :first_name "TG"}}
                            caps)
        (finally (dvergr.security.allowlist/set-users! [])))
      (let [actor (actors/lookup-by-external-ref conn :telegram uid)]
        (is (some? actor) "user-actor materialized")
        (is (= :human (:kind actor)))
        (binding [ec/*execution-context* (:execution-ctx d)]
          (let [room (rreg/lookup (rstore/slug->room-id (str "tg-" chat-id)))]
            (is (some? room) "per-chat room created and registered")
            ;; persistence listener is async — poll the room log. d/messages
            ;; returns the unified shape {:id :from :content :role …}.
            (is (loop [n 0]
                  (let [msgs (disc/messages room)]
                    (cond (some #(= "hello from telegram" (:content %)) msgs) true
                          (>= n 200) false
                          :else (do (Thread/sleep 10) (recur (inc n))))))
                "message persisted to the room store")
            (let [m (->> (disc/messages room)
                         (filter #(= "hello from telegram" (:content %)))
                         first)]
              ;; authored by the user-actor, stored with role :user (the store
              ;; would mis-label a keyword :from as :assistant without the
              ;; adapter's :role metadata tag)
              (is (= :telegram-4242 (:from m)) "authored by the user-actor")
              (is (= :user (:role m))
                  "human inbound stored as :user, not :assistant"))))))))
