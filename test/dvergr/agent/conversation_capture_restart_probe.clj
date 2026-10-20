(ns dvergr.agent.conversation-capture-restart-probe
  "Opt-in TWO-PROCESS durability probe. Invoke write, let JVM exit, then read
   against the SAME explicitly supplied fresh file store. Never auto-spawns a JVM.
   It creates no provider work and never deletes a caller's database."
  (:require [clojure.edn :as edn]
            [datahike.api :as d]
            [dvergr.agent.conversation-capture :as capture]
            [dvergr.chat.schema :as schema]
            [dvergr.discourse :as discourse]
            [dvergr.room.store :as store]
            [dvergr.room.store.datahike :as native]
            [org.replikativ.spindel.engine.hash :as h]))

(def ^:private room-id :conversation-restart-probe)

(defn- expected-messages [id]
  [{:message/id (java.util.UUID/nameUUIDFromBytes (.getBytes (str id "/message") "UTF-8"))
    :message/role :user :message/content "retained across actual process exit"
    :message/created-at (java.util.Date. 1) :message/compacted? false}])

(defn -main [mode config-edn run-uuid]
  (let [cfg (edn/read-string config-edn)
        id (parse-uuid run-uuid)]
    (when-not (and (= :file (get-in cfg [:store :backend])) (uuid? (get-in cfg [:store :id])) id)
      (throw (ex-info "Probe requires an explicit fresh file-store config and Run UUID" {})))
    (case mode
      "write"
      (do
        (when (d/database-exists? cfg) (throw (ex-info "Refusing existing probe database" {})))
        (d/create-database cfg)
        (let [conn (d/connect cfg)]
          (schema/ensure-full-schema! conn)
          (let [s (native/make conn)
                room (discourse/make-room {:id room-id :store s})]
            (try
              (let [now (java.util.Date.)
                    chat (random-uuid)
                    run {:run/id id :run/kind :agent-task :run/room room-id :run/actor :worker
                         :run/trigger (random-uuid) :run/status :running :run/created-at now
                         :run/started-at now :run/updated-at now :run/world :probe-world :run/chat-id chat}
                    prepared (capture/prepare (expected-messages id))
                    body {:savepoint/site :conversation/turn :savepoint/seq 0
                          :savepoint/payload {:run id :step 0 :control-room room-id
                                              :conversation (capture/manifest prepared)}
                          :savepoint/resume {:fn 'dvergr.agent.program/continue-llm-run :args [id 0]}
                          :world/seed 1 :world/state {} :world/systems {} :world/pinned {}}
                    data (assoc body :savepoint/id (h/content-hash body) :savepoint/address [:probe])]
                (d/transact conn [(schema/create-chat-entity {:id chat})])
                (store/-store-run! s room-id run)
                (store/-record-run-savepoint! s room-id id {:world :probe-world :chat chat}
                                              nil data prepared)
                (store/-store-run! s room-id (assoc run :run/status :failed :run/ended-at now))
                (println :written id))
              (finally (discourse/close-room! room) (d/release conn))))))
      "read"
      (let [conn (d/connect cfg)
            s (native/make conn)]
        (try
          (let [run (store/-load-run s room-id id)
                result (store/-load-run-conversation s room-id id (:run/savepoint run))]
            (assert (= :failed (:run/status run)))
            (assert (= (expected-messages id) (:messages result)))
            ;; Original writer completion, not merely dispatch, precedes the
            ;; post-GC restore and release of this exact process-owned handle.
            (assert (set? @(d/gc-storage conn (java.util.Date. (inc (System/currentTimeMillis)))
                                         {:min-age-ms 0})))
            (assert (= (expected-messages id)
                       (:messages (store/-load-run-conversation s room-id id (:run/savepoint run)))))
            (println :restored-after-process-exit-and-gc id))
          (finally (d/release conn))))
      (throw (ex-info "Expected write or read mode" {})))))
