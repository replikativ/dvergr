(ns dvergr.orchestration.daemon-test
  "Integration tests for the daemon with mock agents.

   Most tests share one daemon (`*shared-daemon*`) — each `daemon/start!`
   does heavy I/O (Datahike connect, Lucene init, ygg system registration,
   calendar schema install) that takes ~3-5s and dominates the suite.
   Tests that specifically exercise start/stop lifecycle keep their own
   per-test daemon."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [dvergr.orchestration.daemon :as daemon]
            [dvergr.actors :as actors]
            [dvergr.discourse :as disc]
            [dvergr.room.registry :as rreg]
            [dvergr.room.store :as rstore]
            [dvergr.system.db :as sdb]
            [dvergr.substrate.paths :as paths]
            [dvergr.channels.core :as ch]
            [dvergr.channels.telegram :as tg]
            [dvergr.tools :as tools]
            [dvergr.mcp.server :as mcp]
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

(defn- snapshot-globals []
  {:channels     @ch/channels
   :tools        @tools/registry
   :mcp-tools    @mcp/tool-definitions
   :mcp-handlers @mcp/tool-handlers})

(defn- restore-globals! [snap]
  (reset! ch/channels           (:channels snap))
  (reset! tools/registry        (:tools snap))
  (reset! mcp/tool-definitions  (:mcp-tools snap))
  (reset! mcp/tool-handlers     (:mcp-handlers snap)))

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

;; :once — start ONE daemon for the whole ns. Tests that need a fresh
;; lifecycle (start-stop-no-telegram, no-agents-config) opt out by
;; starting their own daemon and ignoring *shared-daemon*.
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
    (let [snap     (snapshot-globals)
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
          (restore-globals! snap)
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
        (let [g-snap (snapshot-globals)
              d-snap (snapshot-daemon *shared-daemon*)]
          (try
            (f)
            (finally
              (restore-daemon! *shared-daemon* d-snap)
              (restore-globals! g-snap))))))))

;; ============================================================================
;; Tests
;; ============================================================================

(deftest test-daemon-start-stop-no-telegram
  (testing "Daemon starts and stops without Telegram"
    ;; This test specifically exercises the full lifecycle, so it keeps
    ;; its own daemon — don't reuse *shared-daemon* here.
    (let [d (daemon/start! {:db-path (tmp-db-path)
                            :agents {:echo {:provider :fireworks
                                            :model "test-model"
                                            :system-prompt "Echo agent"
                                            :tags #{:echo}
                                            :description "Test echo agent"}}})]
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
      (is (= :stopped @(:status d))))))

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
      (tg/handle-inbound! adapter
                          {:chat-id chat-id
                           :text    "hello from telegram"
                           :from    {:id uid :username "tguser" :first_name "TG"}}
                          caps)
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

(deftest test-daemon-no-agents-config
  (testing "Daemon starts cleanly with no :agents in config — the file-driven
            var secretary (resources/agents/var.md, autostart: true) still boots"
    (let [d (daemon/start! {:db-path (tmp-db-path)})]
      (is (= :running @(:status d)))
      (is (contains? (into #{} (map :id) (daemon/list-agents d)) :var)
          "var autostarts from its definition file even with no :agents config")
      (daemon/stop! d))))
